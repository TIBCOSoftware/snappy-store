/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.transactions;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.Serializable;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InitialImageOperation;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;


public class SnapshotTxGIIDUnit extends DistributedSQLTestBase {

  public SnapshotTxGIIDUnit(String name) {
    super(name);
  }

  public static final String regionName = "GII_TEST_T1";

  @Override
  protected String reduceLogging() {
    return "fine";
  }

  public static File[] getDiskDirs() {
    return new File[]{getDiskDir()};
  }

  private static File getDiskDir() {
    int vmNum = VM.getCurrentVMNum();
    File dir = new File("diskDir", "disk" + String.valueOf(vmNum)).getAbsoluteFile();
    dir.mkdirs();
    return dir;
  }

  @Override
  public void setUp() throws Exception {
    System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "true");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "true");
        System.setProperty("snappydata.snapshot.isolation.gii.lock", "true");
      }
    });
    super.setUp();
  }

  @Override
  public void tearDown2() throws Exception {
    try {
      System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "false");
      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          System.clearProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST");
          System.clearProperty("snappydata.snapshot.isolation.gii.lock");
          InitialImageOperation.resetAllGIITestHooks();
        }
      });
    } finally {
      super.tearDown2();
    }
  }

  public static void createPR(String partitionedRegionName, Integer redundancy,
      Integer totalNumBuckets) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    assertNotNull(cache);
    DiskStore ds = cache.findDiskStore("disk");
    if (ds == null) {
      ds = cache.createDiskStoreFactory()
          .setDiskDirs(getDiskDirs()).create("disk");
    }

    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    paf.setRedundantCopies(redundancy.intValue())
        .setLocalMaxMemory(50).setTotalNumBuckets(
        totalNumBuckets.intValue());
    PartitionAttributes prAttr = paf.create();
    AttributesFactory attr = new AttributesFactory();
    attr.setPartitionAttributes(prAttr);
    attr.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
    attr.setDiskStoreName("disk");
    attr.setDiskSynchronous(true);
    attr.setConcurrencyChecksEnabled(true);

    Region pr = cache.createRegion(partitionedRegionName, attr.create());
    assertNotNull(pr);
  }

  public static void createRR(String regionName) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    assertNotNull(cache);
    DiskStore ds = cache.findDiskStore("disk");
    if (ds == null) {
      ds = cache.createDiskStoreFactory()
          .setDiskDirs(getDiskDirs()).create("disk");
    }
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);


    Region rr = cache.createRegion(regionName, attr.create());
    assertNotNull(rr);
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void putSomeValues(int numValues) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    final Region r = cache.getRegion(regionName);
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    Map<Integer, Integer> m = new HashMap();
    for (int i = 0; i < numValues; i++) {
      r.put(i, i);
    }
    r.getCache().getCacheTransactionManager().commit();
  }

  private void putSomeUncommittedValues(int numValues) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    final Region r = cache.getRegion(regionName);
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    Map<Integer, Integer> m = new HashMap();
    for (int i = 0; i < numValues; i++) {
      r.put(i, i);
    }
  }

  private void readSomeValues(int numValues) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    final Region r = cache.getRegion(regionName);
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    Map<Integer, Integer> m = new HashMap();
    for (int i = 0; i < numValues; i++) {
      r.get(i);
    }
    r.getCache().getCacheTransactionManager().commit();
  }

  public void testSnapshotGII_concurrentWrites() throws Exception {

    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    stopVMNums(-2);

    server1.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    // Put some values to initialize buckets
    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(500);
      }
    });

    blockGII(-2, InitialImageOperation.GIITestHookType.BeforeRequestRVV);

    restartServerVMNums(new int[]{2}, 0, null, null);

    server2 = this.serverVMs.get(1);

    AsyncInvocation ai2 = server1.invokeAsync(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(500);
      }
    });

    // Create the PR region
    server2.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    waitForGIICallbackStarted(-2, InitialImageOperation.GIITestHookType.BeforeRequestRVV);

    unblockGII(-2, InitialImageOperation.GIITestHookType.BeforeRequestRVV);

    // Wait for the PR to get initialized and GII completes on the lone bucket
    waitForRegionInit(-2);

    ai2.join();

    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);

    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" + rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }
  }

  public void testSnapshotGII_concurrentReads() throws Exception {

    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    stopVMNums(-2);

    server1.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    // Put some values to initialize buckets
    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(500);
      }
    });

    blockGII(-2, InitialImageOperation.GIITestHookType.BeforeRequestRVV);

    restartServerVMNums(new int[]{2}, 0, null, null);

    server2 = this.serverVMs.get(1);

    AsyncInvocation ai2 = server1.invokeAsync(new SerializableRunnable() {
      @Override
      public void run() {
        readSomeValues(500);
      }
    });

    // Create the PR region
    server2.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    waitForGIICallbackStarted(-2, InitialImageOperation.GIITestHookType.BeforeRequestRVV);

    unblockGII(-2, InitialImageOperation.GIITestHookType.BeforeRequestRVV);

    // Wait for the PR to get initialized and GII completes on the lone bucket
    waitForRegionInit(-2);

    ai2.join();

    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);

    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" + rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }
  }

  public void testSnapshotGII_killDestination() throws Exception {

    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    stopVMNums(-2);

    server1.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    // Put some values to initialize buckets
    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(100);
      }
    });

    blockGII(-1, InitialImageOperation.GIITestHookType.AfterGIILock);

    restartServerVMNums(new int[]{2}, 0, null, null);

    server2 = this.serverVMs.get(1);


    // Create the PR region
    server2.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    waitForGIICallbackStarted(-1, InitialImageOperation.GIITestHookType.AfterGIILock);

    stopVMNums(-2);

    unblockGII(-1, InitialImageOperation.GIITestHookType.AfterGIILock);


    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(500);
      }
    });

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        assertEquals("After GII request node crashed region put not successful", 500, r.size());
      }
    });
  }

  public void testSnapshotGII_ServerDownWithInProgressTx() throws Exception {

    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    server1.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});
    server2.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    // Put some values to initialize buckets
    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeUncommittedValues(100);
      }
    });
    stopVMNums(-2);
    restartServerVMNums(new int[]{2}, 0, null, null);
    server2.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});
    // Wait for the PR to get initialized and GII completes on the lone bucket
    waitForRegionInit(-2);
    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);

    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" + rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }
  }

  public void testSnapshotGII_noGIIOption() throws Exception {

    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    stopVMNums(-2);

    server1.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    // Put some values to initialize buckets
    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(100);
      }
    });

    blockGII(-2, InitialImageOperation.GIITestHookType.NoGIITrigger);

    blockGII(-2, InitialImageOperation.GIITestHookType.AfterCalculatedUnfinishedOps);

    restartServerVMNums(new int[]{2}, 0, null, null);

    server2 = this.serverVMs.get(1);


    // Create the PR region
    server2.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    waitForGIICallbackStarted(-2, InitialImageOperation.GIITestHookType.AfterCalculatedUnfinishedOps);

    unblockGII(-2, InitialImageOperation.GIITestHookType.AfterCalculatedUnfinishedOps);

    unblockGII(-2, InitialImageOperation.GIITestHookType.NoGIITrigger);


    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(500);
      }
    });

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        assertEquals("After GII request failed for region , " +
            "put not successful", 500, r.size());
      }
    });
  }

  public void testSnapshotGII_failedForOneServer() throws Exception {

    startVMs(0, 3);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server0 = this.serverVMs.get(0);
    VM server1 = this.serverVMs.get(1);
    VM server2 = this.serverVMs.get(2);
    stopVMNums(-2);

    server0.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});
    //server2.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{tableName, 2, 1});

    // Put some values to initialize buckets
    server0.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(100);
      }
    });

    blockGII(-2, InitialImageOperation.GIITestHookType.NoGIITrigger);

    blockGII(-2, InitialImageOperation.GIITestHookType.AfterCalculatedUnfinishedOps);

    restartServerVMNums(new int[]{2}, 0, null, null);

    server1 = this.serverVMs.get(1);


    // Create the PR region
    server1.invoke(SnapshotTxGIIDUnit.class, "createPR", new Object[]{regionName, 2, 1});

    waitForGIICallbackStarted(-2, InitialImageOperation.GIITestHookType.AfterCalculatedUnfinishedOps);

    unblockGII(-2, InitialImageOperation.GIITestHookType.AfterCalculatedUnfinishedOps);

    unblockGII(-2, InitialImageOperation.GIITestHookType.NoGIITrigger);


    server0.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        putSomeValues(500);
      }
    });

    server0.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        assertEquals("After GII request failed for region , " +
            "put not successful", 500, r.size());
      }
    });
  }

  public void blockGII(int vmNum, InitialImageOperation.GIITestHookType type) throws Exception {
    serverExecute(-vmNum, new BlockGII(type));
  }

  public void unblockGII(int vmNum, InitialImageOperation.GIITestHookType type) throws Exception {
    serverExecute(-vmNum, new ReleaseGII(type));
  }

  public void waitForGIICallbackStarted(int vmNum, InitialImageOperation.GIITestHookType type) throws Exception {
    serverExecute(-vmNum, new WaitForGIICallbackStarted(type));
  }

  private static class BlockGII implements Runnable, Serializable {
    private InitialImageOperation.GIITestHookType type;

    public BlockGII(InitialImageOperation.GIITestHookType type) {
      this.type = type;
    }

    @Override
    public void run() {
      InitialImageOperation.setGIITestHook(new Mycallback(type, "_B__GII__TEST__T1_0"));
    }
  }

  private static class WaitForGIICallbackStarted extends SerializableRunnable {
    private InitialImageOperation.GIITestHookType type;

    public WaitForGIICallbackStarted(InitialImageOperation.GIITestHookType type) {
      this.type = type;
    }

    @Override
    public void run() {
      final InitialImageOperation.GIITestHook callback = InitialImageOperation.getGIITestHookForCheckingPurpose(type);
      WaitCriterion ev = new WaitCriterion() {

        public boolean done() {
          return (callback != null && callback.isRunning);
        }

        public String description() {
          return null;
        }
      };

      waitForCriterion(ev, 30000, 200, true);
      if (callback == null || !callback.isRunning) {
        fail("GII tesk hook is not started yet");
      }
    }
  }

  private static class ReleaseGII extends SerializableRunnable {
    private InitialImageOperation.GIITestHookType type;

    public ReleaseGII(InitialImageOperation.GIITestHookType type) {
      this.type = type;
    }

    @Override
    public void run() {
      InitialImageOperation.resetGIITestHook(type, true);

    }
  }

  private static class Mycallback extends InitialImageOperation.GIITestHook {
    private Object lockObject = new Object();

    public Mycallback(InitialImageOperation.GIITestHookType type, String region_name) {
      super(type, region_name);
    }

    @Override
    public void reset() {
      synchronized (this.lockObject) {
        this.lockObject.notify();
      }
    }

    @Override
    public void run() {
      synchronized (this.lockObject) {
        try {
          isRunning = true;
          this.lockObject.wait();
        } catch (InterruptedException e) {
        }
      }
    }
  }

  protected void waitForRegionInit(int vmNum) throws Exception {
    SerializableRunnable waitForRegionInit = new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final PartitionedRegion pr = (PartitionedRegion)cache.getRegion(regionName);
        while (!pr.getRegionAdvisor().areBucketsInitialized()) {
          sleep(500);
        }
        while (!(pr.getRegionAdvisor().getBucket(0) instanceof BucketRegion)) {
          sleep(500);
        }
        LocalRegion lr = (LocalRegion)pr.getRegionAdvisor().getBucket(0);
        lr.waitOnInitialization();
      }
    };
    serverExecute(-vmNum, waitForRegionInit);
  }

  protected RegionVersionVector getRVV(int vmNum) throws Exception {
    SerializableCallable getRVV = new SerializableCallable("getRVV") {

      public Object call() throws Exception {
        Cache cache = GemFireCacheImpl.getInstance();
        System.out.println("RISHI DEBUG - Trying to get " + "/__PR/_B__GII__TEST__T1_0");
        LocalRegion region = (LocalRegion)cache.getRegion("/__PR/_B__GII__TEST__T1_0");
        System.out.println("RISHI DEBUG - Region is a " + region.getConcurrencyChecksEnabled());
        RegionVersionVector rvv = region.getVersionVector();
        System.out.println("RISHI DEBUG - Trying to get rvv" + rvv);
        rvv = rvv.getCloneForTransmission();
        HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);

        //Using gemfire serialization because
        //RegionVersionVector is not java serializable
        DataSerializer.writeObject(rvv, hdos);
        return hdos.toByteArray();
      }
    };

    byte[] result = (byte[])serverExecute(-vmNum, getRVV);
    ByteArrayInputStream bais = new ByteArrayInputStream(result);
    return DataSerializer.readObject(new DataInputStream(bais));
  }
}
