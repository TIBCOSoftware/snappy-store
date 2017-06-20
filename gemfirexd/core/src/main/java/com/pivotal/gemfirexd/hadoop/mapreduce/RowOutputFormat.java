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
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.InvalidJobConfException;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pivotal.gemfirexd.internal.engine.hadoop.mapreduce.OutputFormatUtil;
import com.pivotal.gemfirexd.internal.engine.hadoop.mapreduce.OutputFormatUtil.RowCommandBatchExecutor;

/**
 * Map Reduce {@link OutputFormat} implementation for Gemfirexd. This class uses
 * client connection to populate records in the DB. User is responsible for
 * knowing name and type of the table columns. User provides a data class which
 * contains setter methods for each column which needs to be populated. This
 * class calls each setter method to populate the insert query.
 * 
 * @author ashvina
 * @param <VALUE>
 *          User's data class containing setter methods for table columns
 */
public class RowOutputFormat<VALUE> extends OutputFormat<Key, VALUE> {
  /**
   * The name of the table to be populated, for example APP.CUSTOMERS. This
   * should match the table name used with the CREATE TABLE statement.
   */
  public static final String OUTPUT_TABLE = OutputFormatUtil.OUTPUT_TABLE;
  
  /**
   * System URL where the output table is existing. All output will be written
   * into this table
   */
  public static final String OUTPUT_URL = OutputFormatUtil.OUTPUT_URL;
  
  /**
   * By default row insert commands are executed in batch mode. The batch size
   * can be configured using this property. The default value is 10K commands.
   * If the batch size is equal or less than 0, then the batch is when close
   * operation is executed
   */
  public static final String OUTPUT_BATCH_SIZE = "gfxd.output.batchsize";
  public static final int OUTPUT_BATCH_SIZE_DEFAULT = 10000;

  private static final String OUTPUT_DRIVER_CLASS = "gfxd.output.driver";
  private static final String DRIVER = "io.snappydata.jdbc.ClientDriver";
  
  private OutputFormatUtil util = new OutputFormatUtil();

  private final Logger logger;

  public RowOutputFormat() {
    this.logger = LoggerFactory.getLogger(RowOutputFormat.class);
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public void checkOutputSpecs(JobContext context) throws IOException,
      InterruptedException {
    validateConfiguration(context.getConfiguration());
  }
  
  /**
   * Validates correctness and completeness of job's output configuration. Job
   * configuration must contain url, table and schema name
   * 
   * @param conf
   *          job conf
   * @throws InvalidJobConfException
   */
  protected static void validateConfiguration(Configuration conf)
      throws InvalidJobConfException {
    // User must configure the output region name.
    String url = conf.get(OUTPUT_URL);
    if (url == null || url.trim().isEmpty()) {
      throw new InvalidJobConfException("Output URL not configured.");
    }

    String table = conf.get(OUTPUT_TABLE);
    if (table == null || table.trim().isEmpty()) {
      throw new InvalidJobConfException("Output table name not provided.");
    }
  }

  protected static String getDriver(Configuration conf) {
    return conf.get(OUTPUT_DRIVER_CLASS, DRIVER);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RecordWriter<Key, VALUE> getRecordWriter(TaskAttemptContext context)
      throws IOException {
    return new GfxdRecordWriter(context.getConfiguration());
  }

  /**
   * {@link RecordWriter} implementation for Gemfirexd. The class uses reflection
   * to identify setter methods in the user provided data class.
   * 
   * @author ashvina
   */
  public class GfxdRecordWriter extends RecordWriter<Key, VALUE> {
    final private RowCommandBatchExecutor batchExecutor;

    // list of setters in the {@code VALUE} class
    List<Method> columnSetters = new ArrayList<Method>();
    private String tableName;

    public GfxdRecordWriter(Configuration conf) throws IOException {
      this.tableName = conf.get(OUTPUT_TABLE);
      try {
        this.batchExecutor = util.new RowCommandBatchExecutor(getDriver(conf),
            conf.get(OUTPUT_URL), conf.getInt(OUTPUT_BATCH_SIZE,
                OUTPUT_BATCH_SIZE_DEFAULT));
      } catch (ClassNotFoundException e) {
        logger.error("Gemfirexd client classes are missing from the classpath", e);
        throw new InvalidJobConfException(e);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(Key key, VALUE value) throws IOException,
        InterruptedException {
      try {
        /**
         * The statement depends on column setters provided by the user. These
         * are not known until first instance of the user's data class is
         * provided. Hence query creation will begin on the first invocation
         */
        if (batchExecutor.isNotInitialized()) {
          // list of setters in the {@code VALUE} class
          columnSetters = util.spotTableColumnSetters(value);
          String query = util.createQuery(tableName, columnSetters);
          logger.debug("Query to be executed by record writer is: " + query);
          batchExecutor.initStatement(query);
        }

        batchExecutor.executeWriteStatement(value, columnSetters);
      } catch (SQLException e) {
        logger.error("Failed to upload data into Gemfirexd", e);
        throw new IOException(e);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close(TaskAttemptContext context) throws IOException,
        InterruptedException {
      batchExecutor.close();
    }
  }

  /**
   * {@inheritDoc}
   */
  public OutputCommitter getOutputCommitter(TaskAttemptContext context)
      throws IOException, InterruptedException {
    return new NullOutputFormat<Key, VALUE>().getOutputCommitter(context);
  }
}
