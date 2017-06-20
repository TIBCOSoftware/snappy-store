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
package com.pivotal.gemfirexd.internal.engine.hadoop.mapred.hive;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.hadoop.mapred.Key;
import com.pivotal.gemfirexd.hadoop.mapred.Row;
import com.pivotal.gemfirexd.hadoop.mapred.RowInputFormat;
import com.pivotal.gemfirexd.hadoop.mapred.hive.GFXDHiveInputFormat;
import com.pivotal.gemfirexd.hadoop.mapred.hive.GFXDHiveRowRecordReader;
import com.pivotal.gemfirexd.hadoop.mapred.hive.GFXDHiveSplit;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;

public class GFXDHiveInputFormatTest extends JdbcTestBase {
  String HDFS_DIR = "./myhdfs";
  
  public GFXDHiveInputFormatTest(String name) {
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
  }

  public void testHiveInputFormat() throws Exception {
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
    GFXDHiveInputFormat ipformat = new GFXDHiveInputFormat();
    InputSplit[] splits = ipformat.getSplits(job, 2);
    assertEquals(1, splits.length);
    GFXDHiveSplit split = (GFXDHiveSplit) splits[0];
    assertEquals(1, split.getPaths().length);
    assertEquals(list[0].getPath().toString(), split.getPath(0).toString());
    assertEquals(0, split.getOffset(0));
    assertEquals(list[0].getLen(), split.getLength(0));

    RecordReader<Key, Row> rr = ipformat.getRecordReader(split, job, null);
    assertTrue("Row record reader should be an instace of GFXDHiveRowRecordReader " +
    		"but it is an instance of " + rr.getClass(),  (rr instanceof GFXDHiveRowRecordReader));
    Key key = rr.createKey();
    Row value = rr.createValue();

    int count = 0;
    while (rr.next(key, value)) {
      assertEquals(count++, value.getRowAsResultSet().getInt("col1"));
    }
    
    assertEquals(20, count);
    
    TestUtil.shutDown();
  }
  
}
