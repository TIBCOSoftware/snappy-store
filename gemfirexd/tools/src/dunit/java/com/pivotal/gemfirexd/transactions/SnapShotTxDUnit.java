package com.pivotal.gemfirexd.transactions;

import java.io.File;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.*;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;


public class SnapShotTxDUnit extends DistributedSQLTestBase {

  private boolean gotConflict = false;

  private volatile Throwable threadEx;

  public SnapShotTxDUnit(String name) {
    super(name);
  }

  public static final String regionName = "T1";

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
      }
    });
    super.setUp();
  }

  @Override
  public void tearDown2() throws Exception {
    System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "false");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "false");
      }
    });
    super.tearDown2();
  }

  public static void createPR(String partitionedRegionName, Integer redundancy,
      Integer totalNumBuckets) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    assertNotNull(cache);
    DiskStore ds = cache.findDiskStore("disk");
    if(ds == null) {
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
    if(ds == null) {
      ds = cache.createDiskStoreFactory()
          .setDiskDirs(getDiskDirs()).create("disk");
    }
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);


    Region rr = cache.createRegion(regionName, attr.create());
    assertNotNull(rr);
  }

  public void testSnapshotInsertAPI() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    serverSQLExecute(1, "create schema test");
    serverSQLExecute(1, "create table test.XATT2 (intcol int not null, text varchar(100) not null)");

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        getLogWriter().info(" SKSK in server1 doing two operations. ");
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        r.put(1, 1);
        r.put(2, 2);
        // even before commit it should be visible in both vm
        assertEquals(1, r.get(1));
        assertEquals(2, r.get(2));
        r.getCache().getCacheTransactionManager().commit();
      }
    });

    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        // read the entries
        assertEquals(1, r.get(1));// get don't need to take from snapshot
        assertEquals(2, r.get(2));// get don't need to take from snapshot

        //itr will work on a snapshot.
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();

        for (String regionName : txState.getCurrentSnapshot().keySet()) {
          getLogWriter().info(" the snapshot is for region  " + regionName + " is : " +
              " snapshot " + Integer.toHexString(System.identityHashCode(txState.getCurrentSnapshot())) + " "
              + txState.getCurrentSnapshot().get(regionName));
        }

        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null,
            false, false, true, (LocalRegion)r);

        // after this start another insert in a separate thread and those put shouldn't be visible
        Runnable run = new Runnable() {
          @Override
          public void run() {
            ((LocalRegion)r).put(3, 3);
            ((LocalRegion)r).put(4, 4);
          }
        };
        Thread t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          getLogWriter().info("txitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion()
              + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }
        assertEquals(2, num);
        // should be visible if read directly from region
        num = 0;
        while (itr.hasNext()) {
          RegionEntry re = (RegionEntry)itr.next();
          getLogWriter().info("regitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion()
              + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        r.getCache().getCacheTransactionManager().commit();

      }
    });

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        // take new snapshot and all the data should be visisble
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        //itr will work on a snapshot. not other ops
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        r.getCache().getCacheTransactionManager().commit();
      }
    });
  }

  public void testSnapshotInsertAPI2() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        getLogWriter().info(" SKSK in server1 doing two operations. ");
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        r.put(1, 1);
        r.put(2, 2);
        // even before commit it should be visible in both vm
        assertEquals(1, r.get(1));
        assertEquals(2, r.get(2));
        r.getCache().getCacheTransactionManager().commit();
      }
    });

    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        // read the entries
        assertEquals(1, r.get(1));// get don't need to take from snapshot
        assertEquals(2, r.get(2));// get don't need to take from snapshot


        //itr will work on a snapshot.
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();

        for (String regionName : txState.getCurrentSnapshot().keySet()) {
          getLogWriter().info(" the snapshot is for region  " + regionName + " is : "
              + txState.getCurrentSnapshot().get(regionName));
        }

        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null,
            false, false, true, (LocalRegion)r);

        int num = 0;
        /*while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          getLogWriter().info("txitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion() + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }*/
        //assertEquals(2, num);

        // after this start another insert in a separate thread and those put shouldn't be visible
        Runnable run = new Runnable() {
          @Override
          public void run() {
            r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
            ((LocalRegion)r).put(3, 3);
            ((LocalRegion)r).put(4, 4);
            r.getCache().getCacheTransactionManager().commit();
          }
        };
        Thread t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          getLogWriter().info("txitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion()
              + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }
        assertEquals(2, num);
        // should be visible if read directly from region
        num = 0;
        while (itr.hasNext()) {
          RegionEntry re = (RegionEntry)itr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        r.getCache().getCacheTransactionManager().commit();

      }
    });

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        // take new snapshot and all the data should be visisble
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        //itr will work on a snapshot. not other ops
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null,
            false, false, true, (LocalRegion)r);

        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        num = 0;
        while (itr.hasNext()) {
          RegionEntry re = (RegionEntry)itr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        r.getCache().getCacheTransactionManager().commit();
      }
    });
  }

  public void testSnapshotPutAllAPI() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        getLogWriter().info(" SKSK in server1 doing two operations. ");
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        Map m = new HashMap();
        m.put(1, 1);
        m.put(2, 2);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        r.putAll(m);
        // even before commit it should be visible in both vm
        assertEquals(1, r.get(1));
        assertEquals(2, r.get(2));
        r.getCache().getCacheTransactionManager().commit();
      }
    });

    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);

        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        //itr will work on a snapshot. not other ops
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(2, num);
        r.getCache().getCacheTransactionManager().commit();

        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        // read the entries
        assertEquals(1, r.get(1));// get don't need to take from snapshot
        assertEquals(2, r.get(2));// get don't need to take from snapshot

        //itr will work on a snapshot.
        txstate = TXManagerImpl.getCurrentTXState();
        txState = txstate.getLocalTXState();

        for (String regionName : txState.getCurrentSnapshot().keySet()) {
          getLogWriter().info(" the snapshot is for region  " + regionName + " is : " + " snapshot " + Integer.toHexString(System.identityHashCode(txState.getCurrentSnapshot())) + " "
              + txState.getCurrentSnapshot().get(regionName));
        }

        txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null,
            false, false, true, (LocalRegion)r);

        // after this start another insert in a separate thread and those put shouldn't be visible
        Runnable run = new Runnable() {
          @Override
          public void run() {
            Map m = new HashMap();
            m.put(3, 3);
            m.put(4, 4);
            ((LocalRegion)r).putAll(m);
          }
        };
        Thread t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          getLogWriter().info("txitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion() + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }
        assertEquals(2, num);
        // should be visible if read directly from region
        num = 0;
        while (itr.hasNext()) {
          RegionEntry re = (RegionEntry)itr.next();
          getLogWriter().info("regitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion() + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        r.getCache().getCacheTransactionManager().commit();


        // after this start another insert in a separate thread and those put shouldn't be visible
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        txstate = TXManagerImpl.getCurrentTXState();
        txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null,
            false, false, true, (LocalRegion)r);

        run = new Runnable() {
          @Override
          public void run() {
            r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
            Map m = new HashMap();
            m.put(5, 5);
            m.put(6, 6);
            ((LocalRegion)r).putAll(m);
            r.getCache().getCacheTransactionManager().commit();
          }
        };
        t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          getLogWriter().info("txitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion() + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        // should be visible if read directly from region
        num = 0;
        while (itr.hasNext()) {
          RegionEntry re = (RegionEntry)itr.next();
          getLogWriter().info("regitr : re.getVersionStamp() " + re.getVersionStamp().getRegionVersion() + " txState " + txState);
          if (!re.isTombstone())
            num++;
        }
        assertEquals(6, num);

        r.getCache().getCacheTransactionManager().commit();
      }
    });

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        // take new snapshot and all the data should be visisble
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        //itr will work on a snapshot. not other ops
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(6, num);
        r.getCache().getCacheTransactionManager().commit();
      }
    });
  }


  public void testPutAllMultiThreaded() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});


    final int[] keyval = (int[])server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);
        final int[] keyval = new int[20];
        final AtomicInteger l = new AtomicInteger(0);
        final AtomicInteger key = new AtomicInteger(1000);

        Runnable run = new Runnable() {
          @Override
          public void run() {
            int i = key.incrementAndGet();
            final Map m = new HashMap();

            m.put(i, i);
            m.put(2*i, 2*i);
            r.putAll(m);
            getLogWriter().info("Putting " + i);
            getLogWriter().info("Putting " + 2*i);
            keyval[l.getAndIncrement()] = i;
            keyval[l.getAndIncrement()] = 2*i;
          }
        };
        Thread[] tarr = new Thread[10];
        for (int i = 0; i < 10; i++) {
          Thread t = new Thread(run);
          t.start();
          tarr[i] = t;
        }
        for (Thread t : tarr) {
          try {
            t.join();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
        // even before commit it should be visible in both vm
        for (int i : keyval) {
          assertEquals(i, r.get(i));
        }

        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        //itr will work on a snapshot. not other ops
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();
        for (String regionName : txState.getCurrentSnapshot().keySet()) {
          getLogWriter().info(" the snapshot is for region  " + regionName + " is : " +
              " snapshot " +
              Integer.toHexString(System.identityHashCode(txState.getCurrentSnapshot())) + " "
              + txState.getCurrentSnapshot().get(regionName));
        }
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(20, num);
        getLogWriter().info("The total number of rows are " + num);
        getLogWriter().info("The total number of rows should be " + keyval);

        r.getCache().getCacheTransactionManager().commit();
        return keyval;
      }
    });

    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final Region r = cache.getRegion(regionName);

        for (int i : keyval) {
          assertEquals(i, r.get(i));
        }

        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        //itr will work on a snapshot. not other ops
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        TXState txState = txstate.getLocalTXState();
        for (String regionName : txState.getCurrentSnapshot().keySet()) {
          getLogWriter().info(" the snapshot is for region  " + regionName + " is : " +
              " snapshot " +
              Integer.toHexString(System.identityHashCode(txState.getCurrentSnapshot())) + " "
              + txState.getCurrentSnapshot().get(regionName));
        }
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(20, num);
        getLogWriter().info("The total number of rows are " + num);
        getLogWriter().info("The total number of rows should be " + keyval);

        r.getCache().getCacheTransactionManager().commit();

      }
    });

  }

  // There would be conflict now after write write conflict detection
  public void testConflict() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});

    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);

        r.put(1, 1);
        r.put(2, 2);
        r.put(3, 3);
        r.put(4, 4);

        // after this start another insert in a separate thread and those put shouldn't be visible
        Runnable run = new Runnable() {
          @Override
          public void run() {
            ((LocalRegion)r).put(3, 6);
            ((LocalRegion)r).put(4, 8);
          }
        };
        Thread t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        run = new Runnable() {
          @Override
          public void run() {
            r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);

            ((LocalRegion)r).put(1, 2);
            ((LocalRegion)r).put(2, 4);
            r.getCache().getCacheTransactionManager().commit();
          }
        };
        t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        r.getCache().getCacheTransactionManager().commit();

        assertEquals(1, r.get(1));
        assertEquals(2, r.get(2));
        // non tx put so no conflict
        assertEquals(6, r.get(3));
        assertEquals(8, r.get(4));
      }
    });

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        assertEquals(1, r.get(1));
        assertEquals(2, r.get(2));
        assertEquals(6, r.get(3));
        assertEquals(8, r.get(4));

        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone())
            num++;
        }
        assertEquals(4, num);
        r.getCache().getCacheTransactionManager().commit();
      }
    });
  }

  public void testInsertDeleteUpdate() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        Map map = new HashMap();
        map.put(1, 1);
        map.put(2, 2);
        r.putAll(map);
        // even before commit it should be visible
        assertEquals(1, r.get(1));
        assertEquals(2, r.get(2));
        r.getCache().getCacheTransactionManager().commit();
      }
    });

    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        //itr will work on a snapshot.
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        // read the entries
        assertEquals(1, r.get(1));// get don't need to take from snapshot
        assertEquals(2, r.get(2));// get don't need to take from snapshot
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        // after this start another insert in a separate thread and those put shouldn't be visible
        Runnable run = new Runnable() {
          @Override
          public void run() {
            r.put(1, 2);
            r.destroy(2);
          }
        };
        Thread t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone()) {
            num++;
            // 1,1 and 2,2
            BucketRegion bucket = ((PartitionedRegion)r).getDataStore().getLocalBucketByKey(re.getKey());
            assertEquals(re.getKey(), re.getValue(bucket));
          }
        }
        assertEquals(2, num);
        // should be visible if read directly from region
        num = 0;
        while (itr.hasNext()) {
          RegionEntry re = (RegionEntry)itr.next();
          if (!re.isTombstone()) {
            num++;
            assertEquals(1, re.getKey());
            BucketRegion bucket = ((PartitionedRegion)r).getDataStore().getLocalBucketByKey(re.getKey());
            assertEquals(2, re.getValue(bucket));
          }
        }
        assertEquals(1, num);
        r.getCache().getCacheTransactionManager().commit();

      }
    });

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        //itr will work on a snapshot.
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        int num = 0;
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone()) {
            num++;
            assertEquals(1, re.getKey());
            BucketRegion bucket = ((PartitionedRegion)r).getDataStore().getLocalBucketByKey(re.getKey());
            assertEquals(2, re.getValue(bucket));
          }
        }
        assertEquals(1, num);
        // should be visible if read directly from region
        num = 0;
        while (itr.hasNext()) {
          RegionEntry re = (RegionEntry)itr.next();
          if (!re.isTombstone()) {
            num++;
            assertEquals(1, re.getKey());
            BucketRegion bucket = ((PartitionedRegion)r).getDataStore().getLocalBucketByKey(re.getKey());
            assertEquals(2, re.getValue(bucket));
          }
        }
        assertEquals(1, num);
        r.getCache().getCacheTransactionManager().commit();
      }
    });
  }

  public void testRegionEntryGarbageCollection() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();


    for (VM vm : this.serverVMs) {
      vm.invoke(new SerializableRunnable() {
        @Override
        public void run() {
          final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
          cache.setOldEntriesCleanerTimeIntervalAndRestart(500);
        }
      });
    }
    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {

        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        Map map = new HashMap();
        Integer i =new Integer(1);
        map.put(i, new Integer(1));
        map.put(2, 2);
        r.putAll(map);
        map.clear();
        r.put(i,new Integer(3));


        r.put(i, new Integer(4));
        r.put(i,new Integer(5));
        Map<Object, BlockingQueue<RegionEntry>> oldEntries = cache
            .getOldEntriesForRegion("/__PR/_B__T1_0");
        assertNotNull(oldEntries.get(1).iterator().next());
        // even before commit it should be visible
        i=null;
        System.gc();
        oldEntries = cache.getOldEntriesForRegion("/__PR/_B__T1_0");

        //Should not be null as the transaction is currently active
        assertNotNull(oldEntries.get(1).iterator().next());
        r.getCache().getCacheTransactionManager().commit();
        try {
        System.gc();

          Thread.sleep(2000);
        } catch (Exception e) {
          e.printStackTrace();
        }
        oldEntries = cache.getOldEntriesForRegion("/__PR/_B__T1_0");
        System.out.println("Oldentries"+oldEntries);
        //Should be null as no transaction is active and we have executed System.gc
        assertTrue(oldEntries.get(1) == null);
        assertEquals(cache.getCacheTransactionManager().getHostedTransactionsInProgress().size(), 0);
        assertNull(cache.getCacheTransactionManager().getCurrentTXState());

      }
    });
  }

  public void testGettingProperVersion() throws Exception {
    
    startVMs(0, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    conn.createStatement();


    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});
    server2.invoke(SnapShotTxDUnit.class, "createPR", new Object[]{regionName, 1, 1});

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        //take an snapshot again//gemfire level
        final Region r = cache.getRegion(regionName);
        //itr will work on a snapshot.
        // read the entries
        r.put(1, 1);// get don't need to take from snapshot
        r.put(2, 2);// get don't need to take from snapshot
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);

        TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
        Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        // after this start another insert in a separate thread and those put shouldn't be visible
        Runnable run = new Runnable() {
          @Override
          public void run() {
            //itr will work on a snapshot.
            r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
            r.put(1, 3);
            r.put(1, 4);
            //r.destroy(1);
            r.getCache().getCacheTransactionManager().commit();
          }
        };
        Thread t = new Thread(run);
        t.start();
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        int num = 0;

        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone()) {
            num++;
            // 1,1 and 2,2
            BucketRegion bucket = ((PartitionedRegion)r).getDataStore().getLocalBucketByKey(re.getKey());
            assertEquals(re.getKey(), re.getValue(bucket));

          }
        }
        assertEquals(2, num);

        r.getCache().getCacheTransactionManager().commit();

        num = 0;
        r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        txstate = TXManagerImpl.getCurrentTXState();
        txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
        while (txitr.hasNext()) {
          RegionEntry re = (RegionEntry)txitr.next();
          if (!re.isTombstone()) {
            num++;
            // 1,1 and 2,2
            if((Integer)re.getKey()==2) {
              BucketRegion bucket = ((PartitionedRegion)r).getDataStore().getLocalBucketByKey(re.getKey());
              assertEquals(re.getKey(), re.getValue(bucket));
            }else if((Integer)re.getKey()==1) {
              assertEquals(re.getKey(), 1);
              BucketRegion bucket = ((PartitionedRegion)r).getDataStore().getLocalBucketByKey(re.getKey());
              assertEquals(re.getValue(bucket), 4);
            }

          }
        }
        r.getCache().getCacheTransactionManager().commit();

      }
    });

  }

}

