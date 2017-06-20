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
package com.gemstone.gemfire.cache.hdfs.internal.hoplog.mapreduce;

import java.io.IOException;

import com.gemstone.gemfire.cache.hdfs.internal.PersistedEventImpl;
import com.gemstone.gemfire.cache.hdfs.internal.SortedHDFSQueuePersistedEvent;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.AbstractHoplog;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HFileSortedOplog;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * An iterator that iterates over a split in a read/write hoplog
 * @author dsmith
 */
public class RWSplitIterator extends HDFSSplitIterator {

  public RWSplitIterator(FileSystem fs, Path[] path, long[] start, long[] len, long startTime, long endTime) throws IOException {
    super(fs, path, start, len, startTime, endTime);
  }

  @Override
  protected AbstractHoplog getHoplog(FileSystem fs, Path path) throws IOException {
    // [sumedh] should not be required with the new metrics2
    // SchemaMetrics.configureGlobally(fs.getConf());
    return HFileSortedOplog.getHoplogForLoner(fs, path); 
  }

  public PersistedEventImpl getDeserializedValue() throws ClassNotFoundException, IOException {
    return SortedHDFSQueuePersistedEvent.fromBytes(iterator.getValue());
  }
}
