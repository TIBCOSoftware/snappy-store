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
package com.pivotal.gemfirexd.distributed;

import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.internal.DSFIDFactory;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockService;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.dunit.standalone.DUnitBB;
import io.snappydata.test.util.TestException;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

/**
 * Tests different scenarios for distributed read-write locks including testing
 * of deadlock prevention mechanism built into the distributed write lock.
 * 
 * @since 6.0
 * @author swale
 */
@SuppressWarnings("serial")
public class DistributedLockDUnit extends DistributedSQLTestBase {

  private static final String NumReadLocks = "NUM_READLOCKS";

  private static final String NumReadUnlocks = "NUM_READUNLOCKS";

  private static final long MAX_LEASETIME = 60000;

  public DistributedLockDUnit(final String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  static class AcquireReleaseLock extends SerializableRunnable {

    private final Object[] lockObjects;

    private final boolean readLock;

    private final int numReadUnlocks;

    private final long waitTimeMillis;

    private final long sleepTimeMillis;

    AcquireReleaseLock(final String name, final long waitMillis,
        final long sleepMillis, final boolean isReadLock,
        final int expectedNumReadUnlocks, final Object... locks) {
      super(name);
      if (locks == null) {
        throw new IllegalArgumentException(
            "Expected at least one object to lock");
      }
      this.lockObjects = locks;
      this.waitTimeMillis = waitMillis;
      this.sleepTimeMillis = sleepMillis;
      this.readLock = isReadLock;
      this.numReadUnlocks = expectedNumReadUnlocks;
    }

    @Override
    public void run() throws CacheException {
      final GfxdDRWLockService lockService = Misc.getMemStore()
          .getDDLLockService();
      final Object owner = lockService.newCurrentOwner();
      for (Object lockObject : this.lockObjects) {
        boolean gotLock = false;
        if (this.readLock) {
          gotLock = lockService
              .readLock(lockObject, owner, this.waitTimeMillis);
          incBBFlag(NumReadLocks);
        }
        else {
          if (this.numReadUnlocks > 0) {
            // wait for read locks to be obtained
            waitForBBFlag(NumReadLocks, this.numReadUnlocks, 0);
          }
          gotLock = lockService.writeLock(lockObject, owner,
              this.waitTimeMillis, MAX_LEASETIME);
          if (this.numReadUnlocks > 0) {
            // also check that read locks have been released
            checkBBFlag(NumReadUnlocks, this.numReadUnlocks);
          }
        }
        if (!gotLock) {
          throw new CacheException("failed to obtain the lock") {
          };
        }
        getGlobalLogger().info(this.toString() + " acquired the "
            + (this.readLock ? "read" : "write") + " lock on object "
            + lockObject + "; sleeping for " + this.sleepTimeMillis + "ms");
        try {
          Thread.sleep(this.sleepTimeMillis);
        } catch (final InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new TestException("unexpected interrupt", ie);
        }
        getGlobalLogger().info(this.toString() + (this.readLock ? " read" :
            " write") + " lock on object " + lockObject + "; sleep complete");
      }
      for (Object lockObject : this.lockObjects) {
        if (this.readLock) {
          incBBFlag(NumReadUnlocks);
          lockService.readUnlock(lockObject);
        }
        else {
          lockService.writeUnlock(lockObject, owner);
        }
      }
    }
  }

  private long runInThreads(int numThreads, Runnable run, String msg) {
    final Thread[] threads = new Thread[numThreads];
    for (int index = 0; index < numThreads; ++index) {
      threads[index] = new Thread(run);
    }
    long start = System.currentTimeMillis();
    for (int index = 0; index < numThreads; ++index) {
      threads[index].start();
    }
    try {
      for (int index = 0; index < numThreads; ++index) {
        threads[index].join();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new TestException("unexpected interrupt", ie);
    }
    long end = System.currentTimeMillis();
    getLogWriter().info("Time taken by " + msg + ": " + (end - start) + "ms");
    return (end - start);
  }

  @SuppressWarnings("unchecked")
  private void checkResults(Object result, PartitionedRegion pr) {
    final int[] allBucketIds = new int[pr.getPartitionAttributes()
        .getTotalNumBuckets()];
    for (Set<Integer> bucketIds : (Collection<Set<Integer>>)result) {
      for (Integer bucketId : bucketIds) {
        if (allBucketIds[bucketId.intValue()] != 0) {
          fail("expected no more than one bucket for ID " + bucketId
              + " but got " + allBucketIds[bucketId.intValue()]);
        }
        allBucketIds[bucketId.intValue()] = 1;
      }
    }
    for (int index = 0; index < allBucketIds.length; ++index) {
      if (allBucketIds[index] != 1) {
        fail("expected one bucket for ID " + index + " but got "
            + allBucketIds[index]);
      }
    }
  }

  public static final class TestFunctionMessage extends
      RegionExecutorMessage<Object> {

    static final byte ID = LAST_MSG_ID + 1;

    /** for deserialization */
    public TestFunctionMessage() {
      super(true);
    }

    private TestFunctionMessage(final TestFunctionMessage other) {
      super(other);
    }

    public TestFunctionMessage(ResultCollector<Object, Object> collector,
        LocalRegion region, Set<Object> routingObjects) {
      super(collector, region, routingObjects, null,
          DistributionStats.enableClockStats, true);
    }

    @Override
    protected void setArgsForMember(DistributedMember member,
        Set<DistributedMember> messageAwareMembers) {
    }

    @Override
    protected RegionExecutorMessage<Object> clone() {
      return new TestFunctionMessage(this);
    }

    @Override
    public boolean isHA() {
      return false;
    }

    @Override
    public boolean optimizeForWrite() {
      return true;
    }

    @Override
    public boolean withSecondaries() {
      return false;
    }
    
    @SuppressWarnings("unchecked")
    @Override
    protected void execute() {
      // get bucketIds for this VM and send those back
      lastResult(getLocalBucketSet(GemFireCacheImpl.getExisting().getRegion(
          "/T/TEST1")));
    }

    @Override
    public byte getGfxdID() {
      return ID;
    }
  }

  static final class TestFunction implements Function {

    static final String ID = "TestFunction";

    @Override
    public boolean hasResult() {
      return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void execute(FunctionContext context) {
      // get bucketIds for this VM and send those back
      context.getResultSender().lastResult(
          ((InternalRegionFunctionContext)context)
              .getLocalBucketSet(GemFireCacheImpl.getExisting().getRegion(
                  "/T/TEST1")));
    }

    @Override
    public String getId() {
      return ID;
    }

    @Override
    public boolean optimizeForWrite() {
      return true;
    }

    @Override
    public boolean isHA() {
      return false;
    }
  }

  @Override
  public void vmTearDown() throws Exception {
    // clear the BB vars
    final DUnitBB bb = DUnitBB.getBB();
    bb.remove(NumReadLocks);
    bb.remove(NumReadUnlocks);
    super.vmTearDown();
  }

  public void testLockWaiting() throws Exception {
    // Start three clients and two servers
    startVMs(3, 2);

    final Object lockObject = "Testing";

    // first obtain the read lock on a couple of VMs
    final SerializableRunnable getReadLock = new AcquireReleaseLock(
        "get and release read lock", 10000, 5000, true, 0, lockObject);
    final VM client1 = this.clientVMs.get(1);
    final VM server1 = this.serverVMs.get(1);
    getLogWriter().info(
        "Invoking read lock on client 1 with PID: " + client1.getPid());
    getLogWriter().info(
        "Invoking read lock on server 1 with PID: " + server1.getPid());
    final AsyncInvocation client1Async = client1.invokeAsync(getReadLock);
    final AsyncInvocation server1Async = server1.invokeAsync(getReadLock);

    // now try to obtain write locks
    final SerializableRunnable getWriteLock = new AcquireReleaseLock(
        "get and release write lock", 20000, 5000, false, 2, lockObject);
    final VM client2 = this.clientVMs.get(2);
    final VM server2 = this.serverVMs.get(0);
    getLogWriter().info(
        "Invoking write lock on client 2 with PID: " + client2.getPid());
    getLogWriter().info(
        "Invoking write lock on server 2 with PID: " + server2.getPid());
    final AsyncInvocation client2Async = client2.invokeAsync(getWriteLock);
    final AsyncInvocation server2Async = server2.invokeAsync(getWriteLock);

    // finally join everything and check for any exceptions
    joinAsyncInvocation(client1Async, client1);
    joinAsyncInvocation(server1Async, server1);
    joinAsyncInvocation(client2Async, client2);
    joinAsyncInvocation(server2Async, server2);
  }

  public void testMultipleLockWaiting() throws Exception {
    // Start three clients and two servers
    startVMs(3, 2);

    final Object lockObject = "Testing";

    // first obtain the read lock on a couple of VMs
    final SerializableRunnable getReadLock1 = new AcquireReleaseLock(
        "get and release read lock", 10000, 15000, true, 0, lockObject);
    final SerializableRunnable getReadLock2 = new AcquireReleaseLock(
        "get and release read lock", 10000, 5000, true, 0, lockObject);
    final VM client1 = this.clientVMs.get(1);
    final VM server1 = this.serverVMs.get(1);
    getLogWriter().info(
        "Invoking read lock on client 1 with PID: " + client1.getPid());
    getLogWriter().info(
        "Invoking read lock on server 1 with PID: " + server1.getPid());
    final AsyncInvocation client1Async = client1.invokeAsync(getReadLock1);
    final AsyncInvocation server1Async = server1.invokeAsync(getReadLock2);

    // now try to obtain write locks
    final SerializableRunnable getWriteLock = new AcquireReleaseLock(
        "get and release write lock", 25000, 5000, false, 2, lockObject);
    final VM client2 = this.clientVMs.get(2);
    final VM server2 = this.serverVMs.get(0);
    getLogWriter().info(
        "Invoking write lock on client 2 with PID: " + client2.getPid());
    getLogWriter().info(
        "Invoking write lock on server 2 with PID: " + server2.getPid());
    final AsyncInvocation client2Async = client2.invokeAsync(getWriteLock);
    final AsyncInvocation server2Async = server2.invokeAsync(getWriteLock);

    // finally join everything and check for any exceptions
    joinAsyncInvocation(client1Async, client1);
    joinAsyncInvocation(server1Async, server1);
    joinAsyncInvocation(client2Async, client2);
    joinAsyncInvocation(server2Async, server2);
  }

  public void testDeadlockPrevention() throws Exception {
    // Start three clients and two servers
    startVMs(3, 2);

    final Object lockObject1 = "Testing1";
    final Object lockObject2 = "Testing2";

    // first obtain the read lock on a couple of VMs
    final SerializableRunnable getReadLock1 = new AcquireReleaseLock(
        "get and release read lock12", -1, 5000, true, 0, lockObject1,
        lockObject2);
    final SerializableRunnable getReadLock2 = new AcquireReleaseLock(
        "get and release read lock21", -1, 9000, true, 0, lockObject2,
        lockObject1);
    final VM client1 = this.clientVMs.get(1);
    final VM server1 = this.serverVMs.get(0);
    getLogWriter().info(
        "Invoking read lock 12 on client 1 with PID: " + client1.getPid());
    getLogWriter().info(
        "Invoking read lock 21 on server 1 with PID: " + server1.getPid());
    final AsyncInvocation client1Async = client1.invokeAsync(getReadLock1);
    final AsyncInvocation server1Async = server1.invokeAsync(getReadLock2);

    // now try to obtain write locks
    final SerializableRunnable getWriteLock1 = new AcquireReleaseLock(
        "get and release write lock1", -1, 10000, false, 0, lockObject1);
    final SerializableRunnable getWriteLock2 = new AcquireReleaseLock(
        "get and release write lock2", -1, 5000, false, 0, lockObject2);
    final VM client2 = this.clientVMs.get(2);
    final VM server2 = this.serverVMs.get(1);
    // wait for read locks to be obtained
    waitForBBFlag(NumReadLocks, 2, 0);
    getLogWriter().info(
        "Invoking write lock 1 on client 2 with PID: " + client2.getPid());
    getLogWriter().info(
        "Invoking write lock 2 on server 2 with PID: " + server2.getPid());
    final AsyncInvocation client2Async = client2.invokeAsync(getWriteLock1);
    final AsyncInvocation server2Async = server2.invokeAsync(getWriteLock2);

    // finally join everything and check for any exceptions
    joinAsyncInvocation(client1Async, client1);
    joinAsyncInvocation(server1Async, server1);
    joinAsyncInvocation(client2Async, client2);
    joinAsyncInvocation(server2Async, server2);
  }

  /** Test for {@link GemFireXDUtils#newShortUUID()}. */
  public void testShortUUIDGeneration() throws Throwable {
    // Start two clients and three servers
    startVMs(2, 3);

    final int numThreads = 20;
    final int numKeysPerThread = 50000;
    final int numKeysPerVM = numKeysPerThread * numThreads;

    // generate some large number of keys concurrently on each VM
    final SerializableCallable genKeys = new SerializableCallable() {

      @Override
      public Object call() throws Exception {
        final Thread[] threads = new Thread[numThreads];
        final int[][] allArrays = new int[numThreads][];
        final AtomicInteger threadIndex = new AtomicInteger(0);
        for (int index = 0; index < numThreads; ++index) {
          threads[index] = new Thread(new Runnable() {
            @Override
            public void run() {
              try {
                // wait for signal
                synchronized (allArrays) {
                  if (allArrays[0] == null) {
                    allArrays.wait();
                  }
                }
                final int arrayIndex = threadIndex.getAndIncrement();
                final int[] threadKeys = new int[numKeysPerThread];
                for (int keyIndex = 0; keyIndex < numKeysPerThread; ++keyIndex) {
                  threadKeys[keyIndex] = GemFireXDUtils.newShortUUID();
                }
                allArrays[arrayIndex] = threadKeys;
              } catch (Throwable t) {
                // log any exceptions
                getLogWriter().error("unexpected exception", t);
              }
            }
          });
          threads[index].start();
        }
        // start all threads
        synchronized (allArrays) {
          allArrays.notifyAll();
          allArrays[0] = new int[0];
        }
        // wait for all threads to end
        for (int index = 0; index < numThreads; ++index) {
          threads[index].join();
        }
        return allArrays;
      }
    };
    AsyncInvocation async1 = getServerVM(1).invokeAsync(genKeys);
    AsyncInvocation async2 = getServerVM(2).invokeAsync(genKeys);
    AsyncInvocation async3 = getClientVM(2).invokeAsync(genKeys);
    AsyncInvocation async4 = getServerVM(3).invokeAsync(genKeys);

    // get all results and check for uniqueness
    int[][] arrays1 = (int[][])genKeys.call();
    int[][] arrays2 = (int[][])async1.getResult();
    int[][] arrays3 = (int[][])async2.getResult();
    int[][] arrays4 = (int[][])async3.getResult();
    int[][] arrays5 = (int[][])async4.getResult();

    System.gc();
    IntObjectHashMap<Object> allKeys = new IntObjectHashMap<>(numKeysPerVM * 5);
    checkUniqueKeys(allKeys, arrays1, "currentVM");
    checkUniqueKeys(allKeys, arrays2, getServerVM(1));
    checkUniqueKeys(allKeys, arrays3, getServerVM(2));
    checkUniqueKeys(allKeys, arrays4, getClientVM(2));
    checkUniqueKeys(allKeys, arrays5, getServerVM(3));
    // expect all keys to have been generated
    assertEquals(numKeysPerVM * 5, allKeys.size());
  }

  public void PERF_testFunctionAndMessageCompare() throws Exception {
    // disable elaborate logging
    final Properties props = new Properties();
    props.setProperty("log-level", "config");
    startClientVMs(1, 0, null, props);
    startServerVMs(4, 0, null, props);

    SerializableRunnable register = new SerializableRunnable() {
      @Override
      public void run() {
        FunctionService.registerFunction(new TestFunction());
        DSFIDFactory.registerGemFireXDClass(TestFunctionMessage.ID,
            () -> new TestFunctionMessage());
      }
    };
    invokeInEveryVM(register);
    register.run();

    // create a PR and insert some data to create all buckets
    clientSQLExecute(1,
        "create table T.Test1 (id int primary key, addr varchar(20))");
    for (int id = 1; id <= 200; ++id) {
      clientSQLExecute(1, "insert into T.Test1 values (" + id + ", 'addr" + id
          + "')");
    }

    final int numTimesPerThread = 10000;
    final int numThreads = 3;
    final PartitionedRegion pr = (PartitionedRegion)GemFireCacheImpl
        .getExisting().getRegion("/T/TEST1");
    final CyclicBarrier barrier = new CyclicBarrier(numThreads);
    long elapsed;

    final Runnable funcRun = new Runnable() {
      @Override
      public void run() {
        try {
          barrier.await();
          final GfxdListResultCollector listRC = new GfxdListResultCollector();
          for (int count = 1; count <= numTimesPerThread; ++count) {
            listRC.clearResults();
            final ResultCollector<?, ?> rc = FunctionService.onRegion(pr)
                .withCollector(listRC).execute(TestFunction.ID);
            checkResults(rc.getResult(), pr);
          }
        } catch (Exception ex) {
          fail("failing with exception", ex);
        }
      }      
    };
    final Runnable msgRun = new Runnable() {
      @Override
      public void run() {
        try {
          barrier.await();
          final GfxdListResultCollector rc = new GfxdListResultCollector();
          for (int count = 1; count <= numTimesPerThread; ++count) {
            rc.clearResults();
            TestFunctionMessage msg = new TestFunctionMessage(rc, pr, null);
            checkResults(msg.executeFunction(), pr);
          }
        } catch (Exception ex) {
          fail("failing with exception", ex);
        }
      }
    };

    // --------------- Function execution -----------------
    // warmup runs
    for (int count = 1; count <= numTimesPerThread; ++count) {
      ResultCollector<?, ?> rc = new GfxdListResultCollector();
      rc = FunctionService.onRegion(pr).withCollector(rc)
          .execute(TestFunction.ID);
      checkResults(rc.getResult(), pr);
    }
    // now execute the function some large number of times
    elapsed = runInThreads(numThreads, funcRun, "function");
    getLogWriter().info("Number of invocations per second: "
        + ((numTimesPerThread * numThreads * 1000) / elapsed));
    // do this again
    elapsed = runInThreads(numThreads, funcRun, "function");
    getLogWriter().info("Number of invocations per second: "
        + ((numTimesPerThread * numThreads * 1000) / elapsed));
    // --------------- End function execution -----------------

    // --------------- Function message execution -----------------
    // warmup runs
    for (int count = 1; count <= numTimesPerThread; ++count) {
      GfxdListResultCollector rc = new GfxdListResultCollector();
      TestFunctionMessage msg = new TestFunctionMessage(rc, pr, null);
      checkResults(msg.executeFunction(), pr);
    }
    // now execute the function message some large number of times
    elapsed = runInThreads(numThreads, msgRun, "function message");
    getLogWriter().info("Number of invocations per second: "
        + ((numTimesPerThread * numThreads * 1000) / elapsed));
    // do this again
    elapsed = runInThreads(numThreads, msgRun, "function message");
    getLogWriter().info("Number of invocations per second: "
        + ((numTimesPerThread * numThreads * 1000) / elapsed));
    // --------------- End function message execution -----------------
  }

  private static void checkUniqueKeys(final IntObjectHashMap<Object> allKeys,
      final int[][] arrays, final Object vm) {
    Object oldVM;
    // check uniqueness of keys and also that each list is in ascending order
    for (final int[] array : arrays) {
      long lastKey = -1;
      for (final int key : array) {
        final long currentKey = (key & 0xFFFFFFFFL);
        if (currentKey > lastKey) {
          oldVM = allKeys.get(key);
          if (oldVM == null) {
            allKeys.put(key, vm);
            lastKey = currentKey;
          }
          else {
            getGlobalLogger().info("Failed due to duplicate value. Keys: "
                + Arrays.toString(array));
            throw new TestException("unexpected duplicate " + key + " for VM: "
                + vm + ", oldVM: " + oldVM);
          }
        }
        else {
          getGlobalLogger().info("Failed due to decrease in value. Keys: "
              + Arrays.toString(array));
          throw new TestException("unexpected decrease in value=" + key
              + " currentValue=" + currentKey + " lastValue=" + lastKey
              + " VM: " + vm);
        }
      }
    }
  }
}
