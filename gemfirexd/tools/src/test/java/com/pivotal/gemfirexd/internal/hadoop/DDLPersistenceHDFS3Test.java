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
package com.pivotal.gemfirexd.internal.hadoop;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.PersistedEventImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.mapreduce.GFInputFormat;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.mapreduce.HDFSSplitIterator;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.store.entry.HDFSEventRowLocationRegionEntry;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TableScanResultSet;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.CombineFileSplit;

public class DDLPersistenceHDFS3Test extends JdbcTestBase {

  private String HDFS_DIR = "./myhdfs";

  public DDLPersistenceHDFS3Test(String name) {
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
   * to read part of a hoplog
   *
   * @throws Exception
   */
  public void testReadHoplogSplitRWTable() throws Exception {
    doReadHoplogSplitTest(false);
  }

  /**
   * Test that we can use the replayed DDL and loaner instance
   * to read part of a hoplog for a write only table
   *
   * @throws Exception
   */
  public void testReadHoplogSplitWOTable() throws Exception {
    doReadHoplogSplitTest(true);
  }

  /**
   * Test that we can use the replayed DDL and loaner instance
   * to read part of a hoplog
   *
   * @throws Exception
   */
  public void doReadHoplogSplitTest(boolean writeOnly) throws Exception {

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
    String doWriteOnly = writeOnly ? " WRITEONLY" : "";
    st.execute("create table emp.mytab1 (col1 int primary key, col2 blob(131072)) persistent hdfsstore (myhdfs)" + doWriteOnly);

    PreparedStatement ps = conn.prepareStatement("insert into mytab1 values (?, ?)");
    byte[] value = new byte[131072];
    int NUM_ENTRIES = 100;
    for (int i = 0; i < NUM_ENTRIES; i++) {
      ps.setInt(1, i);
      ps.setBytes(2, value);
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

      //This is how to set the HDFS split on the context
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
          fail("Did not expect to see a duplicate primary key. key=" +
              rs.getInt("col1") + ", seen=" + seenEntries);
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

    assertEquals("Did not find all of the entries. seen entries - " + seenEntries,
        NUM_ENTRIES, seenEntries.size());
    assertTrue("Should have at least used a couple splits", splits.size() >= 1);
  }
}
