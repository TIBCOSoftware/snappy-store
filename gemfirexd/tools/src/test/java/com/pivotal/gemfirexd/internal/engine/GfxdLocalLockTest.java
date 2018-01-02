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

package com.pivotal.gemfirexd.internal.engine;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdReentrantReadWriteLock;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

/**
 * Some basic tests for the ReadWriteLock implementations and compare their
 * performance.
 * 
 * @author swale
 */
public class GfxdLocalLockTest extends JdbcTestBase {

  public GfxdLocalLockTest(String name) {
    super(name);
  }

  private static final SharedStruct sharedVal = new SharedStruct();

  private static final AtomicInteger currentReaders = new AtomicInteger();

  private static final AtomicInteger currentWriters = new AtomicInteger();

  private static AcquireReleaseLocks currentLock;

  private static final AtomicInteger globalId = new AtomicInteger(0);

  @Override
  protected void setUp() throws Exception {
    GemFireCacheImpl.setGFXDSystemForTests();
    super.setUp();
  }

  public void testReadWriteLockWithPerf() throws Exception {
    final int numReaders = 100;
    final int numWriters = 10;
    final int totalRuns = 4000000;
    currentReaders.set(0);
    currentWriters.set(0);
    final Object startObject = new Object();

    Cache cache = new CacheFactory().set("mcast-port", "0").create();
    // create some reader and writer threads
    final Thread[] readers = new Thread[numReaders];
    final Thread[] writers = new Thread[numWriters];

    // runnable for the reader threads
    final Runnable readRun = new Runnable() {

      public void run() {
        try {
          synchronized (startObject) {
            startObject.wait();
          }
          for (int count = 1; count <= totalRuns / numReaders; ++count) {
            currentLock.acquireReadLock();
            try {
              final long longVal = sharedVal.longVal;
              currentReaders.incrementAndGet();
              assertEquals(0, currentWriters.get());
              final double doubleVal = Double.longBitsToDouble(longVal);
              final double actualDouble = sharedVal.doubleVal;
              final long actualDoubleLong = Double
                  .doubleToLongBits(actualDouble);
              assertEquals(longVal, actualDoubleLong);
              assertEquals(doubleVal, actualDouble);
              assertTrue(currentReaders.decrementAndGet() >= 0);
            } finally {
              currentLock.releaseReadLock();
            }
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    };

    // runnable for the writer threads
    final Runnable writeRun = new Runnable() {

      public void run() {
        try {
          synchronized (startObject) {
            startObject.wait();
          }
          for (int count = 1; count <= totalRuns / numReaders; ++count) {
            currentLock.acquireWriteLock();
            assertEquals(1, currentWriters.incrementAndGet());
            try {
              for (int iters = 1; iters <= numReaders / numWriters; ++iters) {
                final long longVal = sharedVal.longVal;
                sharedVal.longVal += 10;
                assertEquals(longVal, Double
                    .doubleToLongBits(sharedVal.doubleVal));
                assertEquals(0, currentReaders.get());
                assertEquals(1, currentWriters.get());
                sharedVal.doubleVal = Double
                    .longBitsToDouble(sharedVal.longVal);
              }
              currentWriters.decrementAndGet();
            } finally {
              currentLock.releaseWriteLock();
            }
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    };

    // test for different ReadWriteLock implementations
    final AcquireReleaseLocks[] allLocks = new AcquireReleaseLocks[] {
        new GfxdAcquireReleaseLocks(),
        new ReentrantAcquireReleaseLocks() };

    // start the threads in parallel
    for (int times = 1; times <= 3; ++times) {
      for (AcquireReleaseLocks lock : allLocks) {
        currentLock = lock;
        for (int index = 0; index < numReaders; ++index) {
          readers[index] = new Thread(readRun);
          readers[index].start();
        }
        for (int index = 0; index < numWriters; ++index) {
          writers[index] = new Thread(writeRun);
          writers[index].start();
        }
        Thread.sleep(500);

        sharedVal.init(0);
        final long start = System.currentTimeMillis();
        synchronized (startObject) {
          startObject.notifyAll();
        }
        for (int index = 0; index < numReaders; ++index) {
          readers[index].join();
        }
        for (int index = 0; index < numWriters; ++index) {
          writers[index].join();
        }
        final long end = System.currentTimeMillis();
        assertEquals(totalRuns * 10, sharedVal.longVal);
        assertEquals(totalRuns * 10, Double
            .doubleToLongBits(sharedVal.doubleVal));
        assertEquals(0, currentReaders.get());
        assertEquals(0, currentWriters.get());
        getLogger().info("Total time taken with " + currentLock
            + " in iteration " + times + ": " + (end - start) + "ms");
      }
    }
    cache.close();
  }

  public void testReadWriteLockWithPerf2() throws Exception {
    final int numReaders = 20;
    final int numWriters = 2;
    final int totalRuns = 4000000;
    final int writerInterval = 1000;
    currentReaders.set(0);
    currentWriters.set(0);
    final Object startObject = new Object();

    // create some reader and writer threads
    final Thread[] readers = new Thread[numReaders];
    final Thread[] writers = new Thread[numWriters];

    Cache cache = new CacheFactory().set("mcast-port", "0").create();
    // runnable for the reader threads
    final Runnable readRun = new Runnable() {

      public void run() {
        try {
          synchronized (startObject) {
            startObject.wait();
          }
          for (int count = 1; count <= totalRuns / numReaders; ++count) {
            currentLock.acquireReadLock();
            try {
              final long longVal = sharedVal.longVal;
              currentReaders.incrementAndGet();
              assertEquals(0, currentWriters.get());
              final double doubleVal = Double.longBitsToDouble(longVal);
              final double actualDouble = sharedVal.doubleVal;
              final long actualDoubleLong = Double
                  .doubleToLongBits(actualDouble);
              assertEquals(longVal, actualDoubleLong);
              assertEquals(doubleVal, actualDouble);
              assertTrue(currentReaders.decrementAndGet() >= 0);
            } finally {
              currentLock.releaseReadLock();
            }
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    };

    // runnable for the writer threads
    final Runnable writeRun = new Runnable() {

      public void run() {
        try {
          final int myOffset = globalId.incrementAndGet() * writerInterval;
          synchronized (startObject) {
            startObject.wait();
          }
          for (int count = 1; count <= totalRuns / numWriters; ++count) {
            // this is just a busy wait to simulate the case where writers
            // appear after some intervals rather than trying to acquire
            // write locks continuously
            if (count < myOffset || ((count - myOffset) % writerInterval) != 0) {
              // do some checks and loop back
              assertTrue(currentReaders.get() >= 0);
              assertTrue(currentWriters.get() >= 0
                  && currentWriters.get() < numWriters);
              continue;
            }
            currentLock.acquireWriteLock();
            assertEquals(1, currentWriters.incrementAndGet());
            try {
              final long longVal = sharedVal.longVal;
              sharedVal.longVal += 10;
              assertEquals(longVal, Double
                  .doubleToLongBits(sharedVal.doubleVal));
              assertEquals(0, currentReaders.get());
              assertEquals(1, currentWriters.get());
              sharedVal.doubleVal = Double.longBitsToDouble(sharedVal.longVal);
              currentWriters.decrementAndGet();
            } finally {
              currentLock.releaseWriteLock();
            }
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    };

    // test for different ReadWriteLock implementations
    final AcquireReleaseLocks[] allLocks = new AcquireReleaseLocks[] {
        new GfxdAcquireReleaseLocks(),
        new ReentrantAcquireReleaseLocks() };

    // start the threads in parallel
    for (int times = 1; times <= 3; ++times) {
      for (AcquireReleaseLocks lock : allLocks) {
        currentLock = lock;
        globalId.set(0);
        for (int index = 0; index < numReaders; ++index) {
          readers[index] = new Thread(readRun);
          readers[index].start();
        }
        for (int index = 0; index < numWriters; ++index) {
          writers[index] = new Thread(writeRun);
          writers[index].start();
        }
        Thread.sleep(500);

        sharedVal.init(10);
        final long start = System.currentTimeMillis();
        synchronized (startObject) {
          startObject.notifyAll();
        }
        for (int index = 0; index < numReaders; ++index) {
          readers[index].join();
        }
        for (int index = 0; index < numWriters; ++index) {
          writers[index].join();
        }
        final long end = System.currentTimeMillis();
        assertEquals(totalRuns * 10 / writerInterval, sharedVal.longVal);
        assertEquals(totalRuns * 10 / writerInterval, Double
            .doubleToLongBits(sharedVal.doubleVal));
        assertEquals(0, currentReaders.get());
        assertEquals(0, currentWriters.get());
        getLogger().info("Total time taken with " + currentLock
            + " in iteration " + times + ": " + (end - start) + "ms");
      }
    }
    cache.close();
  }

  private static final class SharedStruct {

    long longVal;

    double doubleVal;

    void init(long val) {
      this.longVal = val;
      this.doubleVal = Double.longBitsToDouble(val);
    }
  }

  private static interface AcquireReleaseLocks {

    void acquireReadLock() throws InterruptedException;

    void releaseReadLock();

    void acquireWriteLock() throws InterruptedException;

    void releaseWriteLock();
  }

  private static final class GfxdAcquireReleaseLocks implements
      AcquireReleaseLocks {
    private final GfxdReentrantReadWriteLock lock =
      new GfxdReentrantReadWriteLock("GfxdLocalLockTest", false);

    public void acquireReadLock() throws InterruptedException {
      this.lock.attemptReadLock(-1, Thread.currentThread());
    }

    public void acquireWriteLock() throws InterruptedException {
      this.lock.attemptWriteLock(-1, Thread.currentThread());
    }

    public void releaseReadLock() {
      this.lock.releaseReadLock();
    }

    public void releaseWriteLock() {
      this.lock.releaseWriteLock(Thread.currentThread());
    }

    @Override
    public String toString() {
      return this.lock.toString();
    }
  }

  @SuppressWarnings("serial")
  private static final class ReentrantAcquireReleaseLocks extends
      ReentrantReadWriteLock implements AcquireReleaseLocks {

    public void acquireReadLock() throws InterruptedException {
      readLock().lock();
    }

    public void acquireWriteLock() throws InterruptedException {
      writeLock().lock();
    }

    public void releaseReadLock() {
      readLock().unlock();
    }

    public void releaseWriteLock() {
      writeLock().unlock();
    }
  }
}
