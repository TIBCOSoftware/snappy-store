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
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.TreeMap;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionEvent;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.FileUtil;
import com.gemstone.gemfire.internal.cache.CacheObserver;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.Oplog;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.junit.UnitTest;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DropTableNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import io.snappydata.test.memscale.OffHeapHelper;
import org.apache.derbyTesting.junit.JDBC;

public class JdbcTestBase extends TestUtil implements UnitTest {

  protected String[] deleteDirs;

  public JdbcTestBase(String name) {
    super(name);
  }

  protected String[] testSpecificDirectoriesForDeletion() {
    return this.deleteDirs;
  }

  protected void clearTestSpecificDirectoriesForDeletion() {
    this.deleteDirs = null;
  }

  @Override
  protected void setUp() throws Exception {
    if (GemFireStore.getBootingInstance() != null) {
      shutDown();
    }
    //Uncommeting it would make all gemfirexd tests run with offheap
    //System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "1G");
    //System.setProperty("gemfirexd.TEST_FLAG_OFFHEAP_ENABLE","true");
    GemFireXDUtils.IS_TEST_MODE = true;
    System.setProperty(HDFSStoreImpl.ALLOW_STANDALONE_HDFS_FILESYSTEM_PROP, "true");
    clearTestSpecificDirectoriesForDeletion();
    super.setUp();
    loadDriver();
    loadNetDriver();
  }

  protected static Map<String, Long> getAllOplogFiles() {
    String currDir = System.getProperty("user.dir");
    File cdir = new File(currDir);

    File[] files = FileUtil.listFiles(cdir);

    Map<String, Long> results = new TreeMap<String, Long>();
    for (File file : files) {
      long length = file.length();
      //Don't count .lk files
      if (file.getName().endsWith(".lk")) {
        continue;
      }
      //crf files are truncated before backing up, so the file length may change
      //we do want to validate krf and idxkrf file length.
      if (file.getName().endsWith(".crf") || file.getName().endsWith(".drf")) {
        length = 0;
      }

      if (file.getName().matches(".*GFXD-DEFAULT-DISKSTORE.*")) {
        results.put(file.getPath(), length);
      }
    }

    return results;
  }

  protected static void clearAllOplogFiles() {
    String currDir = System.getProperty("user.dir");
    File cdir = new File(currDir);

    File[] files = FileUtil.listFiles(cdir);

    for (File file : files) {
      if (file.getName().matches(".*GFXD-DEFAULT-DISKSTORE.*")) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      } else if (file.getName().matches("datadictionary")) {
        if (file.isDirectory()) {
          deleteDir(file);
        }
      }
    }
  }

  protected void doOffHeapValidations() throws Exception {
    SimpleMemoryAllocatorImpl sma = null;
    try {
      sma = SimpleMemoryAllocatorImpl.getAllocator();
    }catch(CacheClosedException ignore) {
      sma = null;
    }
    if(sma != null) {
      
      JDBC.assertLiveChunksAndRegionEntryValidity(sma);
      
    }   
  }
  
  protected void doEndOffHeapValidations() throws Exception {
    OffHeapHelper.waitForWanQueuesToDrain();
    OffHeapHelper.verifyOffHeapMemoryConsistency(true);
    OffHeapHelper.closeAllRegions();
    OffHeapHelper.verifyOffHeapMemoryConsistency(true);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      this.doEndOffHeapValidations();
    } finally {
      try {
        shutDown();
        currentUserName = null;
        currentUserPassword = null;
      } finally {
        try {
          super.tearDown();
          GemFireXDUtils.IS_TEST_MODE = false;
          final String[] dirs = testSpecificDirectoriesForDeletion();
          if (dirs != null) {
            for (String dir : dirs) {
              deleteDir(new File(dir));
            }
          }
          clearAllOplogFiles();
        } finally {
          GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
          if (cache != null && !cache.isClosed()) {
            try {
              cache.close();
            } catch (Exception ignore) {
            }
          }
          System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
          System.clearProperty("gemfire." + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
          System.clearProperty("gemfirexd.TEST_FLAG_OFFHEAP_ENABLE");
        }
      }
    }
  }

  /**
   * get the value from a data cell as an int.
   *
   * @param dataValue
   *          the data cell, internally known to be an instance of
   *          datavaluedescriptor[]
   * @param index
   *          the array index (0-based) into the row for the value
   * @return the value of the cell at index as an int
   */
  protected int getIntFromDataValue(Object dataValue, int index)
      throws ClassNotFoundException, NoSuchMethodException,
      IllegalAccessException, InvocationTargetException {
    // value is a DataValueDescriptor
    Object value = Array.get(dataValue, index);
    Class<?> valueClass = Class.forName("com.pivotal.gemfirexd.internal.iapi."
        + "types.DataValueDescriptor");
    Method getIntMethod = valueClass.getMethod("getInt", (Class[])null);
    return ((Integer)getIntMethod.invoke(value, (Object[])null)).intValue();
  }

  static void addAsyncEventListener(String serverGroups, String ID, String className,
      Integer batchSize, Integer batchTimeInterval, Boolean batchConflation,
      Integer maxQueueMem, String diskStoreName, Boolean enablePersistence, Boolean diskSync,
      Integer alertThreshold, String initParamStr) throws SQLException {
    addAsyncEventListenerWithConn(serverGroups, ID, className, batchSize, batchTimeInterval,
        batchConflation, maxQueueMem, diskStoreName, enablePersistence, diskSync,
        alertThreshold, initParamStr, null);
  }

  static void addAsyncEventListenerWithConn(String serverGroups, String ID, String className,
      Integer batchSize, Integer batchTimeInterval, Boolean batchConflation,
      Integer maxQueueMem, String diskStore, Boolean enablePersistence, Boolean diskSync,
      Integer alertThreshold, String initParamStr, Connection conn)
      throws SQLException {

    if (conn == null) {
      conn = getConnection();
    }
    String createDDL = MessageFormat.format("CREATE ASYNCEVENTLISTENER {1} "
        + "(listenerclass ''{2}'' initparams ''{10}'' "
        + "{3} {4} {5} {6} {7} {8} {9} {11}) server groups ({0})",
        serverGroups, ID, className,
        batchSize != null ? "BATCHSIZE " + batchSize : "",
        batchTimeInterval != null ? "BATCHTIMEINTERVAL "
            + batchTimeInterval : "",
        batchConflation != null ? "ENABLEBATCHCONFLATION "
            + batchConflation : "",
        maxQueueMem != null ? "MAXQUEUEMEMORY " + maxQueueMem : "",
        diskStore != null ? "DISKSTORENAME " + diskStore : "",
        enablePersistence != null ? "ENABLEPERSISTENCE "
            + enablePersistence : "",
        alertThreshold != null ? "ALERTTHRESHOLD "
            + alertThreshold : "",
        initParamStr != null ? initParamStr : "",
        diskSync != null ? "DISKSYNCHRONOUS "
               + diskSync : "");

    conn.createStatement().execute(createDDL);
  }

  static void startAsyncEventListener(String ID) throws SQLException {
    startAsyncEventListener(ID, null);
  }

  static void startAsyncEventListener(String ID, Connection conn) throws SQLException {

    try {
      if (conn == null) {
        conn = getConnection();
      }
      CallableStatement cs = conn
          .prepareCall("call SYS.START_ASYNC_EVENT_LISTENER (?)");
      cs.setString(1, ID);
      cs.execute();
    } catch (SQLException sqle) {
      throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
    }
  }

  static void stopAsyncEventListener(String ID) throws SQLException {

    try {
      Connection conn = getConnection();
      CallableStatement cs = conn
          .prepareCall("call SYS.STOP_ASYNC_EVENT_LISTENER (?)");
      cs.setString(1, ID);
      cs.execute();
    } catch (SQLException sqle) {
      throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
    }
  }

  public void cleanUpDirs(final File[] dirs) {
    if (dirs != null) {
      for (File file : dirs) {
        if (file.isDirectory()) {
          File[] files = file.listFiles();
          if (files != null) {
            for (File f : files) {
              f.delete();
            }
          }
          file.delete();
        }
      }
    }
  }
  
  public static class RegionMapClearDetector
      extends GemFireXDQueryObserverAdapter implements CacheObserver {

    private int numExpectedRegionClearCalls = 0;
    private int currentCallCount = 0;

    private void setNumExpectedRegionClearCalls(int numExpected) {
      this.numExpectedRegionClearCalls = numExpected;
      this.currentCallCount = 0;
    }

    public void waitTillAllClear() throws InterruptedException {
      synchronized (this) {
        if (this.currentCallCount < numExpectedRegionClearCalls) {
          this.wait();
        }
      }
    }

    @Override
    public void afterRegionCustomEntryConcurrentHashMapClear() {
      synchronized (this) {
        ++this.currentCallCount;
        if (this.currentCallCount == this.numExpectedRegionClearCalls) {
          this.notifyAll();
        }
      }
    }

    @Override
    public void afterQueryParsing(String query, StatementNode qt,
        LanguageConnectionContext lcc) {
      if (qt instanceof DropTableNode) {
        DropTableNode dtn = (DropTableNode)qt;
        String tableToDrop = dtn.getFullName();
        Region<?, ?> rgn = Misc.getRegionForTable(tableToDrop, false);
        if (rgn != null && rgn.getAttributes().getEnableOffHeapMemory()) {
          if (rgn.getAttributes().getDataPolicy().isPartition()) {
            PartitionedRegion pr = (PartitionedRegion)rgn;
            int numBucketsHosted = pr.getRegionAdvisor().getAllBucketAdvisors()
                .size();
            this.setNumExpectedRegionClearCalls(numBucketsHosted);
          } else {
            this.setNumExpectedRegionClearCalls(1);
          }
        } else {
          this.setNumExpectedRegionClearCalls(0);
        }
      } else {
        this.setNumExpectedRegionClearCalls(0);
      }
    }

    @Override
    public void afterRegionClear(RegionEvent event) {
      // TODO Auto-generated method stub
    }

    @Override
    public void beforeDiskClear() {
      // TODO Auto-generated method stub
    }

    @Override
    public void goingToFlush() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterWritingBytes() {
      // TODO Auto-generated method stub
    }

    @Override
    public void beforeGoingToCompact() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterHavingCompacted() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterConflation(ByteBuffer origBB, ByteBuffer conflatedBB) {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterSettingOplogOffSet(long offset) {
      // TODO Auto-generated method stub
    }

    @Override
    public void beforeSwitchingOplog() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterSwitchingOplog() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterKrfCreated() {
      // TODO Auto-generated method stub
    }

    @Override
    public void beforeStoppingCompactor() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterStoppingCompactor() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterSignallingCompactor() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterMarkingGIIStarted() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterMarkingGIICompleted() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterSwitchingWriteAndFlushMaps() {
      // TODO Auto-generated method stub
    }

    @Override
    public void beforeSettingDiskRef() {
      // TODO Auto-generated method stub
    }

    @Override
    public void afterSettingDiskRef() {
      // TODO Auto-generated method stub
    }

    @Override
    public void beforeDeletingCompactedOplog(Oplog compactedOplog) {
      // TODO Auto-generated method stub
    }

    @Override
    public void beforeDeletingEmptyOplog(Oplog emptyOplog) {
      // TODO Auto-generated method stub
    }

    @Override
    public boolean shouldCreateKRFIRF() {
      // TODO Auto-generated method stub
      return false;
    }
  }
}
