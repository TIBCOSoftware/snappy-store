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
package com.pivotal.gemfirexd.internal.engine.hadoop.mapred;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.Event.Type;
import com.pivotal.gemfirexd.hadoop.mapred.Key;
import com.pivotal.gemfirexd.hadoop.mapred.Row;
import com.pivotal.gemfirexd.hadoop.mapred.RowInputFormat;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.lib.CombineFileSplit;

public class EventInputFormatTest extends JdbcTestBase {
  String HDFS_DIR = "./myhdfs";

  public void testEventInputFormat() throws Exception {
    getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' batchtimeinterval 5000 milliseconds");
    st.execute("create table app.mytab1 (col1 int primary key, col2 varchar(100)) persistent hdfsstore (myhdfs) BUCKETS 1");

    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    int NUM_ENTRIES = 20;
    for(int i = 0; i < NUM_ENTRIES; i++) {
      ps.setInt(1, i);
      ps.setString(2, "Value-" + System.nanoTime());
      ps.execute();
    }
    //Wait for data to get to HDFS...
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] list = fs.listStatus(new Path(HDFS_DIR + "/APP_MYTAB1/0/"));
    assertEquals(1, list.length);
    
    conf.set(RowInputFormat.INPUT_TABLE, "MYTAB1");
    conf.set(RowInputFormat.HOME_DIR, HDFS_DIR);
    
    JobConf job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    RowInputFormat ipformat = new RowInputFormat();
    InputSplit[] splits = ipformat.getSplits(job, 2);
    assertEquals(1, splits.length);
    CombineFileSplit split = (CombineFileSplit) splits[0];
    assertEquals(1, split.getPaths().length);
    assertEquals(list[0].getPath().toString(), split.getPath(0).toString());
    assertEquals(0, split.getOffset(0));
    assertEquals(list[0].getLen(), split.getLength(0));

    RecordReader<Key, Row> rr = ipformat.getRecordReader(split, job, null);
    Key key = rr.createKey();
    Row value = rr.createValue();

    int count = 0;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    
    assertEquals(20, count);
    
    TestUtil.shutDown();
  }
  
  public void testNoSecureHdfsCheck() throws Exception {
    getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    
    
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "'  batchtimeinterval 5000 milliseconds");
    st.execute("create table app.mytab1 (col1 int primary key, col2 varchar(100)) persistent hdfsstore (myhdfs) BUCKETS 1");
    
    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    int NUM_ENTRIES = 20;
    for(int i = 0; i < NUM_ENTRIES; i++) {
      ps.setInt(1, i);
      ps.setString(2, "Value-" + System.nanoTime());
      ps.execute();
    }
    //Wait for data to get to HDFS...
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    stopNetServer();
    FabricServiceManager.currentFabricServiceInstance().stop(new Properties());
    
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] list = fs.listStatus(new Path(HDFS_DIR + "/APP_MYTAB1/0/"));
    assertEquals(1, list.length);
    
    conf.set(RowInputFormat.INPUT_TABLE, "MYTAB1");
    conf.set(RowInputFormat.HOME_DIR, HDFS_DIR);
    conf.set("hadoop.security.authentication", "kerberos");
    
    JobConf job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    RowInputFormat ipformat = new RowInputFormat();
    InputSplit[] splits = ipformat.getSplits(job, 2);
    assertEquals(1, splits.length);
    CombineFileSplit split = (CombineFileSplit) splits[0];
    assertEquals(1, split.getPaths().length);
    assertEquals(list[0].getPath().toString(), split.getPath(0).toString());
    assertEquals(0, split.getOffset(0));
    assertEquals(list[0].getLen(), split.getLength(0));
    
    RecordReader<Key, Row> rr = ipformat.getRecordReader(split, job, null);
    Key key = rr.createKey();
    Row value = rr.createValue();
    
    int count = 0;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    
    assertEquals(20, count);
    
    TestUtil.shutDown();
  }
  
  public void testNBuckets1Split() throws Exception {
    getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' batchtimeinterval 5000 milliseconds");
    st.execute("create table app.mytab1 (col1 int primary key, col2 varchar(100)) persistent hdfsstore (myhdfs) BUCKETS 5");
    
    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    int NUM_ENTRIES = 20;
    for(int i = 0; i < NUM_ENTRIES; i++) {
      ps.setInt(1, i);
      ps.setString(2, "Value-" + System.nanoTime());
      ps.execute();
    }
    //Wait for data to get to HDFS...
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] list = fs.listStatus(new Path(HDFS_DIR + "/APP_MYTAB1/"));
    assertEquals(5, list.length);
    
    int hopCount = 0;
    for (FileStatus bucket : list) {
      FileStatus[] hops = fs.listStatus(bucket.getPath());
      hopCount += hops.length;
    }
    assertEquals(5, hopCount);
    
    conf.set(RowInputFormat.INPUT_TABLE, "MYTAB1");
    conf.set(RowInputFormat.HOME_DIR, HDFS_DIR);
    
    JobConf job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    RowInputFormat ipformat = new RowInputFormat();
    InputSplit[] splits = ipformat.getSplits(job, 2);
    assertEquals(1, splits.length);
    
    CombineFileSplit split = (CombineFileSplit) splits[0];
    assertEquals(hopCount, split.getPaths().length);

    RecordReader<Key, Row> rr = ipformat.getRecordReader(split, job, null);
    Key key = rr.createKey();
    Row value = rr.createValue();
    
    int[] check = new int[NUM_ENTRIES];
    for (int i : check) {
      check[i] = 0;
    }
    while (rr.next(key, value)) {
      int index = value.getRowAsResultSet().getInt("col1");
      check[index]++;
    }
    for (int i : check) {
      assertEquals(check[i], 1);
    }
    
    TestUtil.shutDown();
  }
  
  public void testTimeFilters() throws Exception {
    getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' batchtimeinterval 5000 milliseconds");
    st.execute("create table app.mytab1 (col1 int primary key, col2 varchar(100)) persistent hdfsstore (myhdfs) BUCKETS 1");
    
    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    int NUM_ENTRIES = 10;
    for(int i = 0; i < NUM_ENTRIES; i++) {
      ps.setInt(1, i);
      ps.setString(2, "Value-" + System.nanoTime());
      ps.execute();
      TimeUnit.MILLISECONDS.sleep(10);
    }
    //Wait for data to get to HDFS...
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] list = fs.listStatus(new Path(HDFS_DIR + "/APP_MYTAB1/0/"));
    assertEquals(1, list.length);
    
    conf.set(RowInputFormat.INPUT_TABLE, "MYTAB1");
    conf.set(RowInputFormat.HOME_DIR, HDFS_DIR);
    
    JobConf job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    RowInputFormat ipformat = new RowInputFormat();
    InputSplit[] splits = ipformat.getSplits(job, 2);
    assertEquals(1, splits.length);
    
    RecordReader<Key, Row> rr = ipformat.getRecordReader(splits[0], job, null);
    Key key = rr.createKey();
    Row value = rr.createValue();
    
    int count = 0;
    long ts0 = 0; // timestamp of first event
    long ts3 = 0;
    long ts5 = 0;
    long ts9 = 0; // timestamp of last event
    
    while (rr.next(key, value)) {
      switch (count) {
      case 0:
        ts0 = value.getTimestamp();
        break;
      case 3:
        ts3 = value.getTimestamp();
        break;
      case 5:
        ts5 = value.getTimestamp();
        break;
      case 9:
        ts9 = value.getTimestamp();
        break;
      }
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    assertEquals(10, count);
    assertTrue(ts0 > 0);
    assertTrue(ts3 > 0);
    assertTrue(ts5 > 0);
    assertTrue(ts9 > 0);

    job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    job.setLong(RowInputFormat.START_TIME_MILLIS, ts0 - 1);
    rr = ipformat.getRecordReader(splits[0], job, null);
    key = rr.createKey();
    value = rr.createValue();
    count = 0;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    assertEquals(10, count);
    
    job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    job.setLong(RowInputFormat.START_TIME_MILLIS, ts3);
    rr = ipformat.getRecordReader(splits[0], job, null);
    key = rr.createKey();
    value = rr.createValue();
    count = 3;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    assertEquals(10, count);
    
    job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    job.setLong(RowInputFormat.START_TIME_MILLIS, ts9);
    rr = ipformat.getRecordReader(splits[0], job, null);
    key = rr.createKey();
    value = rr.createValue();
    count = 9;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    assertEquals(10, count);
    
    job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    job.setLong(RowInputFormat.START_TIME_MILLIS, ts9 + 1);
    rr = ipformat.getRecordReader(splits[0], job, null);
    key = rr.createKey();
    value = rr.createValue();
    while (rr.next(key, value)) {
      fail();
    }
    
    job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    job.setLong(RowInputFormat.END_TIME_MILLIS, ts9);
    rr = ipformat.getRecordReader(splits[0], job, null);
    key = rr.createKey();
    value = rr.createValue();
    count = 0;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    assertEquals(10, count);
    
    job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    job.setLong(RowInputFormat.END_TIME_MILLIS, ts5 - 1);
    rr = ipformat.getRecordReader(splits[0], job, null);
    key = rr.createKey();
    value = rr.createValue();
    count = 0;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    assertEquals(5, count);
    
    job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    job.setLong(RowInputFormat.START_TIME_MILLIS, ts3);
    job.setLong(RowInputFormat.END_TIME_MILLIS, ts5);
    rr = ipformat.getRecordReader(splits[0], job, null);
    key = rr.createKey();
    value = rr.createValue();
    count = 3;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    assertEquals(6, count);
    
    TestUtil.shutDown();
  }

  public void testRowSerDe() throws Exception {
    doTestRowSerDe(true);
  }
  public void testRowSerDeNoConcurrencyChecks() throws Exception {
    doTestRowSerDe(false);
  }
  private void doTestRowSerDe(boolean concurrencyChecks) throws Exception {
    getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    final long statTS = System.currentTimeMillis();
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' batchtimeinterval 5000 milliseconds");
    String concurrency = "persistent ENABLE CONCURRENCY CHECKS";
    st.execute("create table app.mytab1 (col1 int primary key, col2 varchar(100)) partition by primary key buckets 1 hdfsstore (myhdfs) "
        +(concurrencyChecks ? concurrency : ""));

    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    ps.setInt(1, 1);
    ps.setString(2, "Value-1");
    ps.execute();
    
    //Wait for data to get to HDFS...
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] list = fs.listStatus(new Path(HDFS_DIR + "/APP_MYTAB1/0/"));
    assertEquals(1, list.length);
    
    conf.set(RowInputFormat.INPUT_TABLE, "MYTAB1");
    conf.set(RowInputFormat.HOME_DIR, HDFS_DIR);
    
    JobConf job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    RowInputFormat ipformat = new RowInputFormat();
    InputSplit[] splits = ipformat.getSplits(job, 2);
    assertEquals(1, splits.length);
    RecordReader<Key, Row> rr = ipformat.getRecordReader(splits[0], job, null);
    Key key = rr.createKey();
    Row value = rr.createValue();
    assertTrue(rr.next(key, value));
    assertEquals(1, value.getRowAsResultSet().getInt(1));
    assertEquals("Value-1", value.getRowAsResultSet().getString(2));
    assertTrue(value.getTimestamp() > statTS);
    assertFalse(value.getRowAsResultSet().next());
    
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    value.write(dos);
    dos.close();
    
    byte[] buf = baos.toByteArray();
    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
    Row row = new Row();
    row.readFields(dis);
    dis.close();
    
    assertEquals(1, row.getRowAsResultSet().getInt(1));
    assertEquals("Value-1", row.getRowAsResultSet().getString(2));
    assertFalse(value.getRowAsResultSet().next());
    
    TestUtil.shutDown();
  }
  
  public void testDeleteForRW() throws Exception {
    deleteTest(false, true);
  }
  
  public void testDeleteForWriteOnly() throws Exception {
    deleteTest(true, true);
  }

  public void testDeleteForWriteOnly_transactional() throws Exception {
    deleteTest(true, true, true);
  }
  
  public void testDeleteForRWNoPk() throws Exception {
    deleteTest(false, false);
  }
  
  public void testDeleteForWriteOnlyNoPK() throws Exception {
    deleteTest(true, false);
  }

  public void testDeleteForWriteOnlyNoPK_transactional() throws Exception {
    deleteTest(true, false, true);
  }
  
  private void deleteTest( boolean writeOnly, boolean primaryKey) throws Exception {
    deleteTest(writeOnly, primaryKey, false);
  }
  public static Connection getTxConnection() throws SQLException {
    Connection conn = getConnection();
    conn.setAutoCommit(true);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    return conn;
  }
  private void deleteTest( boolean writeOnly, boolean primaryKey, boolean isTransactional) throws Exception {
    //getConnection();
    Connection conn = null;
    if (isTransactional) {
      conn = getTxConnection();//startNetserverAndGetLocalNetConnection();
    } else {
      conn = getConnection();
    }
    
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" + HDFS_DIR + "' batchtimeinterval 2000 milliseconds");
    String primaryKeyString = primaryKey ? "primary key" : "";
    st.execute("create table app.mytab1 (col1 int " + primaryKeyString + ", col2 varchar(100)) BUCKETS 1 persistent hdfsstore (myhdfs) " + (writeOnly? " WRITEONLY " : ""));
    

    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    for(int i = 0; i < 3; i++) {
      ps.setInt(1, i);
      ps.setString(2, "Value-" + System.nanoTime());
      ps.execute();
    }
    
    st.execute("delete from mytab1 where col1 = 1");
    //Wait for data to get to HDFS...
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/MYTAB1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    TestUtil.shutDown();

    FileStatus[] list = null;
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    for (int i = 0; i < 100; i++) {
      list = fs.listStatus(new Path(HDFS_DIR + "/APP_MYTAB1/0/"));
      if (list.length == 1) {
        break;
      }
      Thread.sleep(500);
    }
    if (list.length != 1) {
      fail("unexpected files: " + java.util.Arrays.toString(list));
    }

    conf.set(RowInputFormat.INPUT_TABLE, "MYTAB1");
    conf.set(RowInputFormat.HOME_DIR, HDFS_DIR);
    
    JobConf job = new JobConf(conf);
    job.setBoolean(RowInputFormat.CHECKPOINT_MODE, false);
    RowInputFormat ipformat = new RowInputFormat();
    InputSplit[] splits = ipformat.getSplits(job, 2);
    RecordReader<Key, Row> rr = ipformat.getRecordReader(splits[0], job, null);
    Key key = rr.createKey();
    Row value = rr.createValue();

    rr.next(key, value); 
    assertEquals(0, value.getRowAsResultSet().getInt("col1"));
    
    if (!writeOnly) {
      rr.next(key, value);
      checkForDeletedRow(value, primaryKey);
    }
    rr.next(key, value); 
    assertEquals(1, value.getRowAsResultSet().getInt("col1"));
    
    rr.next(key, value); 
    assertEquals(2, value.getRowAsResultSet().getInt("col1"));
    
    if (writeOnly) {
      rr.next(key, value);
      checkForDeletedRow(value, primaryKey);
    }
    assertFalse(rr.next(key,  value));
    
    TestUtil.shutDown();
  }

  private void checkForDeletedRow(Row value, boolean primaryKey) throws IOException, SQLException {
    assertTrue("Operation shoud be destroy but it is " + value.getEventType(), value.getEventType().equals(Type.AFTER_DELETE));
    ResultSet rs = value.getRowAsResultSet();
    if(primaryKey) {
      assertEquals(1, rs.getInt("col1"));
    } else {
      assertEquals(null, rs.getObject("col1"));
    }
    assertEquals(null, rs.getString("col2"));
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
  }

  public EventInputFormatTest(String name) {
    super(name);
  }
}
