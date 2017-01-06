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
package com.pivotal.gemfirexd.hadoop.mapreduce;

import java.io.IOException;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.CombineFileSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gemstone.gemfire.cache.hdfs.HDFSStore;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HoplogConfig;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.mapreduce.HDFSSplitIterator;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.store.entry.HDFSEventRowLocationRegionEntry;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TableScanResultSet;

/**
 * This class provides hoplog data reader. This class uses a stand-alone gemfirexd
 * instance to transform record byte[] in hoplogs into {@link ResultSet}
 * objects. The user is not expected to advance the result set's cursor.
 * 
 * @author ashvina
 */
public class RowRecordReader extends RecordReader<Key, Row> {
  private static final String PROTOCOL = "jdbc:gemfirexd:";
  private static final String DRIVER_FOR_STAND_ALONE_GEMFIREXD = "io.snappydata.jdbc.EmbeddedDriver";

  protected HDFSSplitIterator splitIterator;
  protected EmbedStatement es;
  protected EmbedResultSet rs;
  boolean isClosed;
  protected final Logger logger;

  public RowRecordReader() {
    logger = LoggerFactory.getLogger(RowRecordReader.class);
  }

  @Override
  public void initialize(InputSplit split, TaskAttemptContext context)
      throws IOException {
    Configuration conf = context.getConfiguration();
    CombineFileSplit cSplit =  (CombineFileSplit) split;
    Path[] path = cSplit.getPaths();
    long[] start = cSplit.getStartOffsets();
    long[] len = cSplit.getLengths();
    
    FileSystem fs = cSplit.getPath(0).getFileSystem(conf);
    
    long startTS = conf.getLong(RowInputFormat.START_TIME_MILLIS, 0l);
    long endTS = conf.getLong(RowInputFormat.END_TIME_MILLIS, 0l);
    this.splitIterator = HDFSSplitIterator.newInstance(fs, path, start, len, startTS, endTS);

    instantiateGfxdLoner(conf);
  }

  protected void instantiateGfxdLoner(Configuration conf) throws IOException {
     String home = conf.get(RowInputFormat.HOME_DIR,
        HDFSStore.DEFAULT_HOME_DIR);

    // create properties needed by local stand alone instance of GemFireXD
    Properties props = new Properties();
    
    //copy gemfirexd properties configured by user
    for (Entry<String, String> entry : conf) {
      String key = entry.getKey();
      if (key.startsWith(RowInputFormat.PROPERTY_PREFIX)) {
        key = key.substring(RowInputFormat.PROPERTY_PREFIX.length());
        if (key.length() <= 0) {
          continue;
        }
        
        System.setProperty(key, entry.getValue());
      }
    }
    System.setProperty(InternalDistributedSystem.ENABLE_SLF4J_LOG_BRIDGE, "true");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"true");
    System.setProperty(HoplogConfig.PERFORM_SECURE_HDFS_CHECK_PROP, "false");
    
    props.put("mcast-port", "0");
    props.put(DistributionConfig.LOCATORS_NAME, "");
    props.put("persist-dd", "false");
    props.put(Property.HADOOP_IS_GFXD_LONER, "true");
    props.put(Attribute.TABLE_DEFAULT_PARTITIONED, "true");
    props.put(Property.GFXD_HD_HOMEDIR, home);
    
    for (Entry<String, String> entry : conf) {
      props.put(Property.HADOOP_GFXD_LONER_PROPS_PREFIX + entry.getKey(),
          entry.getValue());
    }
    
    try {
      Class.forName(DRIVER_FOR_STAND_ALONE_GEMFIREXD).newInstance();
    } catch (InstantiationException e) {
      logger.error("Failed to instantiate stand alone reader", e);
      throw new IOException(e);
    } catch (IllegalAccessException e) {
      logger.error("Failed to instantiate stand alone reader", e);
      throw new IOException(e);
    } catch (ClassNotFoundException e) {
      logger.error("Gemfirexd classes are missing from the classpath", e);
      throw new IOException(e);
    }
    
    EmbedConnection conn = null;
    try {
      conn = (EmbedConnection)DriverManager.getConnection(PROTOCOL, props);
      LanguageConnectionContext context = conn
          .getLanguageConnectionContext();
      context.setHDFSSplit(splitIterator);

      String name = conf.get(RowInputFormat.INPUT_TABLE);
      this.es = (EmbedStatement)conn.createStatement();
      name = RowInputFormat.getFullyQualifiedTableName(name);
      this.rs = (EmbedResultSet)this.es.executeQuery("select * from " + name);
      // setup context stack for lightWeightNext calls
      conn.getTR().setupContextStack();
      this.rs.pushStatementContext(context, true);
    } catch (SQLException e) {
      logger.error("Error in connecting to stand alone reader", e);
      throw new IOException(e);
    }    
  }

  @Override
  public final boolean nextKeyValue() throws IOException, InterruptedException {
    return nextRow();
  }

  protected final boolean nextRow() throws IOException {
    try {
      return rs.lightWeightNext();
    } catch (SQLException e) {
      return false;
    }
  }

  @Override
  public final Key getCurrentKey() throws IOException, InterruptedException {
    Key key = new Key();
    key.setKey(getRowKeyBytes());
    return key;
  }

  protected final byte[] getRowKeyBytes() throws IOException {
    TableScanResultSet source = (TableScanResultSet)rs.getSourceResultSet();

    HDFSEventRowLocationRegionEntry rowLocation = null;

    try {
      rowLocation = (HDFSEventRowLocationRegionEntry)source.getRowLocation();
      // The key will always be a byte[]. It was set by the
      // HDFSSplitIterator.getKey()
      return rowLocation.getRawKeyBytes();
    } catch (StandardException e) {
      logger.error("Error while trying to read row key", e);
      throw new IOException("Error trying to get row key", e);
    }
  }

  @Override
  public final Row getCurrentValue() throws IOException,
      InterruptedException {
    return new Row(rs);
  }

  @Override
  public float getProgress() throws IOException, InterruptedException {
    return getResultSetProgress();
  }

  protected float getResultSetProgress() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public void close() throws IOException {
    if (!isClosed) {
      GfxdConnectionWrapper.restoreContextStack(es, rs);
      try {
        rs.lightWeightClose();
      } catch (SQLException e) {
        logger.warn("Error while trying to free reader resources", e);
      }
      isClosed = true;
    }
  }
}
