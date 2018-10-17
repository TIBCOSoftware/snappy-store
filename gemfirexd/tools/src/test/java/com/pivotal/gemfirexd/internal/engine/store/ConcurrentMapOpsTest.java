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

package com.pivotal.gemfirexd.internal.engine.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import org.apache.derbyTesting.junit.JDBC;

public class ConcurrentMapOpsTest extends ConcurrentMapOpsHolder {

  public ConcurrentMapOpsTest(String name) {
    super(name);
  }

  protected int isolationLevel;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    System.setProperty("gemfire.statsDisabled", "true");
    System.setProperty("gemfire.WRITE_LOCK_TIMEOUT", "500");
    System.setProperty("gemfire.READ_LOCK_TIMEOUT", "1000");
    this.isolationLevel = Connection.TRANSACTION_NONE;
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("gemfire.statsDisabled");
    System.clearProperty("gemfire.WRITE_LOCK_TIMEOUT");
    System.clearProperty("gemfire.READ_LOCK_TIMEOUT");
    this.isolationLevel = Connection.TRANSACTION_NONE;
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  protected int netPort;

  protected Connection createConnection() throws Exception {
    Connection conn;
    if (netPort > 0) {
      conn = getNetConnection(netPort, "", null);
    }
    else {
      conn = getConnection();
    }
    // Default isolation is RC now
    if (this.isolationLevel == Connection.TRANSACTION_NONE) {
      conn.setAutoCommit(false);
    }
    conn.setTransactionIsolation(this.isolationLevel);
    return conn;
  }

  public void testOpsWithCSLM() throws Throwable {
    setupConnection();
    netPort = startNetserverAndReturnPort();

    this.isolationLevel = Connection.TRANSACTION_NONE;
    addExpectedException("Statistics sampler");
    runOpsWithCSLM(5000);
    runOpsWithCSLM(20000);
    removeExpectedException("Statistics sampler");
  }

  public void _BUG49712_testOpsWithCSLM_TX() throws Throwable {
    this.isolationLevel = Connection.TRANSACTION_READ_COMMITTED;
    setupConnection();
    addExpectedExceptions(new Object[] { "Statistics sampler", "X0Z02" });
    runOpsWithCSLM(5000);
    runOpsWithCSLM(20000);
    removeExpectedExceptions(new Object[] { "Statistics sampler", "X0Z02" });
  }

  public void DISABLED_testOpsWithCSLM_RRTX() throws Throwable {
    this.isolationLevel = Connection.TRANSACTION_REPEATABLE_READ;
    setupConnection();
    addExpectedExceptions(new Object[] { "Statistics sampler", "X0Z02" });
    runOpsWithCSLM(5000);
    runOpsWithCSLM(20000);
    removeExpectedExceptions(new Object[] { "Statistics sampler", "X0Z02" });
  }

  private volatile LongRef refCounter;

  /** compare raw numbers for atomic ops using JDK vs unsafe wrapper classes */
  public void testCompareAtomicOps() {

    // some warmups
    runAtomicOps(1, 50000, intJDKCounterUpdate, longJDKCounterUpdate,
        refJDKCounterUpdate, intUnsafeCounterUpdate, longUnsafeCounterUpdate,
        refUnsafeCounterUpdate);

    // timed runs with single threads to see the raw overheads with no
    // concurrency (as we would expect in most usual cases)
    runAtomicOps(1, 50000000, intJDKCounterUpdate, longJDKCounterUpdate,
        refJDKCounterUpdate, intUnsafeCounterUpdate, longUnsafeCounterUpdate,
        refUnsafeCounterUpdate);

    // now with concurrency
    runAtomicOps(5, 2000000, intJDKCounterUpdate, longJDKCounterUpdate,
        refJDKCounterUpdate, intUnsafeCounterUpdate, longUnsafeCounterUpdate,
        refUnsafeCounterUpdate);
  }

  public void runOpsWithCSLM(final int runtimeInMillis) throws Throwable {
    Connection conn = createConnection();
    Statement stmt = conn.createStatement();

    // create a table with unique index, non-unique with some overlap, and
    // non-unique with large overlap (resulting in map creation for value)
    stmt.execute("drop table if exists t.test");
    stmt.execute("create table t.test (id1 varchar(100), "
        + "id2 varchar(100), id3 varchar(100), id4 varchar(100)) replicate "
        + "concurrency level 60");
    stmt.execute("create unique index t.idx1 on t.test(id1)");
    stmt.execute("create index t.idx2 on t.test(id2)");
    stmt.execute("create index t.idx3 on t.test(id3)");
    stmt.execute("create index t.idx4 on t.test(id4)");

    // have some threads doing inserts, some updates and some deletes
    final long startTime = System.currentTimeMillis();
    final AtomicLong endTime1 = new AtomicLong(startTime + runtimeInMillis);
    final Throwable[] failure = new Throwable[1];
    final AtomicInteger offset1 = new AtomicInteger();
    final AtomicInteger offset2 = new AtomicInteger();
    final AtomicInteger offset3 = new AtomicInteger();
    final AtomicInteger offset4 = new AtomicInteger();
    final int numInsertThreads = 15;
    final AtomicInteger numDeleteThreads = new AtomicInteger(10);
    final int numUpdateThreads = 10;
    final int numSelectThreads = 10;
    final AtomicInteger insertOps = new AtomicInteger();
    final AtomicInteger deleteOps = new AtomicInteger();
    final AtomicInteger updateOps = new AtomicInteger();
    final AtomicInteger selectOps = new AtomicInteger();
    final GemFireContainer rc = (GemFireContainer)Misc.getRegionForTable(
        "T.TEST", true).getUserAttribute();

    final Runnable doInserts = new Runnable() {
      @Override
      public void run() {
        try {
          Connection conn = createConnection();
          PreparedStatement pstmt = conn
              .prepareStatement("insert into t.test values (?, ?, ?, ?)");
          final int myOffset = offset1.incrementAndGet();

          int rowIdx = getRowIdxOffset(myOffset, numInsertThreads);
          while (System.currentTimeMillis() < endTime1.get()) {
            final int initRowIdx = rowIdx;
            for (;;) {
              for (int i = 0; i < 1000; i++) {
                boolean breakLoop = false;
                while (!breakLoop) {
                  pstmt.setString(1, "id1_" + rowIdx);
                  pstmt.setString(2, "id2_" + (rowIdx / (50 + (i % 100))));
                  pstmt.setString(3, "id3_" + (rowIdx % 3000));
                  pstmt.setString(4, "id4_" + (rowIdx % 5));
                  try {
                    assertEquals(1, pstmt.executeUpdate());
                    insertOps.incrementAndGet();
                    breakLoop = true;
                  } catch (SQLException sqle) {
                    if (!"X0Z02".equals(sqle.getSQLState())) {
                      if ("23505".equals(sqle.getSQLState())) {
                        // ignore constraint violation here and continue forward
                        breakLoop = true;
                      }
                      else {
                        throw sqle;
                      }
                    }
                  }
                }
                rowIdx = incRowIdx(rowIdx, myOffset, numInsertThreads);
              }
              try {
                conn.commit();
                break;
              } catch (SQLException sqle) {
                if (!"X0Z02".equals(sqle.getSQLState())) {
                  throw sqle;
                }
              }
              rowIdx = initRowIdx;
            }
          }
          conn.commit();
        } catch (Throwable t) {
          getLogger().error(t);
          failure[0] = t;
        }
      }
    };

    final Runnable doDeletes = new Runnable() {
      @Override
      public void run() {
        try {
          Connection conn = createConnection();
          PreparedStatement pstmt = conn
              .prepareStatement("delete from t.test where id1=?");
          final int myOffset = offset2.incrementAndGet();
          final int numThreads = numDeleteThreads.get();

          SanityManager.DEBUG_PRINT("info:TEST", "myOffset=" + myOffset);
          int rowIdx = getRowIdxOffset(myOffset, numThreads);
          while (System.currentTimeMillis() < endTime1.get()
              && rc.getRegion().size() > 0) {
            final int initRowIdx = rowIdx;
            for (;;) {
              for (int i = 0; i < 100; i++) {
                for (;;) {
                  pstmt.setString(1, "id1_" + rowIdx);
                  try {
                    pstmt.executeUpdate();
                    deleteOps.incrementAndGet();
                    break;
                  } catch (SQLException sqle) {
                    if (!"X0Z02".equals(sqle.getSQLState())) {
                      throw sqle;
                    }
                  }
                }
                rowIdx = incRowIdx(rowIdx, myOffset, numThreads);
              }
              try {
                conn.commit();
                break;
              } catch (SQLException sqle) {
                if (!"X0Z02".equals(sqle.getSQLState())) {
                  throw sqle;
                }
              }
              rowIdx = initRowIdx;
            }
          }
          conn.commit();
        } catch (Throwable t) {
          getLogger().error(t);
          failure[0] = t;
        }
      }
    };

    // continue running updates and selects for more time
    final long endTime2 = endTime1.get() + runtimeInMillis;

    final Runnable doUpdates = new Runnable() {
      @Override
      public void run() {
        try {
          Connection conn = createConnection();
          final PreparedStatement pstmt1 = conn
              .prepareStatement("update t.test set id2=?, id3=?, id4=? "
                  + "where id1=?");
          final PreparedStatement pstmt3 = conn
              .prepareStatement("update t.test set id2=?, id3=?, id4=? "
                  + "where id3=?");
          final int myOffset = offset3.incrementAndGet();

          int rowIdx = getRowIdxOffset(myOffset, numUpdateThreads);
          int updateIdx = getRowIdxOffset(myOffset, numUpdateThreads);
          PreparedStatement pstmt = pstmt1;
          boolean useId3 = false;
          long currentTime;
          while ((currentTime = System.currentTimeMillis()) < endTime2) {
            if (!useId3 && currentTime > endTime1.get()) {
              // switch to using updates using id3
              pstmt = pstmt3;
              rowIdx = getRowIdxOffset(myOffset, numUpdateThreads);
              useId3 = true;
              // print out the current table size
              SanityManager.DEBUG_PRINT("TEST", "Current table size="
                  + Misc.getRegionForTable("T.TEST", true).size());
            }
            final int initRowIdx = rowIdx;
            final int initUpdateIdx = updateIdx;
            for (;;) {
              for (int i = 0; i < 10; i++) {
                for (;;) {
                  pstmt.setString(1, "id2_" + (updateIdx / (95 + i)));
                  pstmt.setString(2, "id3_" + (updateIdx % 3000));
                  pstmt.setString(3, "id4_" + (updateIdx % 5));
                  if (useId3) {
                    pstmt.setString(4, "id3_" + (rowIdx % 3000));
                  }
                  else {
                    pstmt.setString(4, "id1_" + rowIdx);
                  }
                  try {
                    pstmt.executeUpdate();
                    updateOps.incrementAndGet();
                    break;
                  } catch (SQLException sqle) {
                    if (!"X0Z02".equals(sqle.getSQLState())) {
                      throw sqle;
                    }
                  }
                }
                rowIdx = incRowIdx(rowIdx, myOffset, numUpdateThreads);
                updateIdx = incRowIdx(updateIdx, myOffset, numUpdateThreads);
              }
              try {
                conn.commit();
                break;
              } catch (SQLException sqle) {
                if (!"X0Z02".equals(sqle.getSQLState())) {
                  throw sqle;
                }
              }
              rowIdx = initRowIdx;
              updateIdx = initUpdateIdx;
            }
          }
          conn.commit();
        } catch (Throwable t) {
          getLogger().error(t);
          failure[0] = t;
        }
      }
    };

    final AtomicInteger qualifiedRows = new AtomicInteger();
    final AtomicInteger requalifySkipped = new AtomicInteger();
    final AtomicInteger requalifySuccess = new AtomicInteger();
    final AtomicInteger requalifyFailed = new AtomicInteger();
    // observer to check that there should not be too many requalify
    // failures else it indicates something else is broken (e.g. index key
    // selected for comparison is incorrect)
    @SuppressWarnings("serial")
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public void afterIndexRowRequalification(Boolean success,
          CompactCompositeIndexKey ccKey, ExecRow row, Activation activation) {
        if (activation.getPreparedStatement().getSource()
            .contains("select * from t.test where id3")) {
          if (success == null) {
            requalifySkipped.incrementAndGet();
          }
          else if (success) {
            requalifySuccess.incrementAndGet();
          }
          else {
            requalifyFailed.incrementAndGet();
          }
        }
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);

    final Runnable doSelects = new Runnable() {
      @Override
      public void run() {
        try {
          Connection conn = createConnection();
          PreparedStatement pstmt1 = conn
              .prepareStatement("select * from t.test where id1=?");
          PreparedStatement pstmt2 = conn
              .prepareStatement("select * from t.test where id2=?");
          PreparedStatement pstmt3 = conn
              .prepareStatement("select * from t.test where id3=?");
          PreparedStatement pstmt4 = conn
              .prepareStatement("select * from t.test where id3>=? and id3<?");
          final int myOffset = offset4.incrementAndGet();

          int rowIdx = getRowIdxOffset(myOffset, numSelectThreads);
          int selectIdx = getRowIdxOffset(myOffset, numSelectThreads);
          while (System.currentTimeMillis() < endTime2) {
            String id1 = "id1_" + rowIdx;
            pstmt1.setString(1, id1);
            String id2 = "id2_" + (selectIdx / (50 + (rowIdx % 89)));
            pstmt2.setString(1, id2);
            String id3 = "id3_" + (rowIdx % 3000);
            pstmt3.setString(1, id3);
            String id3_2 = "id3_" + ((rowIdx % 3000) + 3);
            pstmt4.setString(1, id3);
            pstmt4.setString(2, id3_2);

            ResultSet rs1 = pstmt1.executeQuery();
            ResultSet rs2 = pstmt2.executeQuery();
            ResultSet rs3 = pstmt3.executeQuery();
            ResultSet rs4 = pstmt4.executeQuery();

            checkIndexColumn(rs1, 1, id1, null);
            checkIndexColumn(rs2, 2, id2, null);
            checkIndexColumn(rs3, 3, id3, qualifiedRows);
            checkIndexColumn(rs4, 3, id3, id3_2, qualifiedRows);

            conn.commit();

            rowIdx = incRowIdx(rowIdx, myOffset, numSelectThreads);
            selectIdx = incRowIdx(rowIdx, myOffset, numSelectThreads);
            selectOps.addAndGet(4);
          }
          conn.commit();
        } catch (Throwable t) {
          getLogger().error(t);
          failure[0] = t;
        }
      }
    };

    final Thread[] insertThreads = new Thread[numInsertThreads];
    final Thread[] deleteThreads = new Thread[numDeleteThreads.get()];
    final Thread[] updateThreads = new Thread[numUpdateThreads];
    final Thread[] selectThreads = new Thread[numSelectThreads];
    for (int i = 0; i < numInsertThreads; i++) {
      insertThreads[i] = new Thread(doInserts);
      insertThreads[i].start();
    }
    for (int i = 0; i < numDeleteThreads.get(); i++) {
      deleteThreads[i] = new Thread(doDeletes);
      deleteThreads[i].start();
    }
    for (int i = 0; i < numUpdateThreads; i++) {
      updateThreads[i] = new Thread(doUpdates);
      updateThreads[i].start();
    }
    for (int i = 0; i < numSelectThreads; i++) {
      selectThreads[i] = new Thread(doSelects);
      selectThreads[i].start();
    }

    for (int i = 0; i < numInsertThreads; i++) {
      insertThreads[i].join();
    }
    for (int i = 0; i < numDeleteThreads.get(); i++) {
      deleteThreads[i].join();
    }
    for (int i = 0; i < numUpdateThreads; i++) {
      updateThreads[i].join();
    }
    for (int i = 0; i < numSelectThreads; i++) {
      selectThreads[i].join();
    }

    // check the ratio of qualified vs unqualified and if its too large then it
    // indicates some other problem
    if (requalifySuccess.intValue() < requalifyFailed.intValue()) {
      fail("Too many requalifications failed: qualifiedRows="
          + requalifySuccess.intValue() + ", requalifyFailed="
          + requalifyFailed.intValue());
    }
    // we also should be doing some requalify skips
    if (this.isolationLevel == Connection.TRANSACTION_NONE
        && (requalifySkipped.intValue() < ((requalifySuccess.intValue()
            + requalifyFailed.intValue()) / 20000))) {
      fail("Not enough requalifications skipped: requalifySkipped="
          + requalifySkipped.intValue() + ", requalifySuccess="
          + requalifySuccess.intValue() + ", requalifyFailed="
          + requalifyFailed.intValue());
    }

    getLogger().info(
        "PERF: duration=" + (System.currentTimeMillis() - startTime)
            + "ms numInserts=" + insertOps.get() + " numDeletes="
            + deleteOps.get() + " numUpdates=" + updateOps.get()
            + " numSelects=" + selectOps.get());

    if (failure[0] != null) {
      throw failure[0];
    }

    GemFireXDQueryObserverHolder.clearInstance();

    // validate indexes at the end
    ResultSet rs = stmt
        .executeQuery("values SYSCS_UTIL.CHECK_TABLE('T', 'TEST')");
    JDBC.assertSingleValueResultSet(rs, "1");
    rs.close();
    conn.commit();

    // finally delete everything using multiple threads
    if (this.isolationLevel == Connection.TRANSACTION_NONE) {
      offset2.set(0);
      deleteOps.set(0);
      final int ndelthrs = 30;
      numDeleteThreads.set(ndelthrs);
      // wait forever until table size is 0
      endTime1.set(Long.MAX_VALUE);
      final Thread[] deleteThreads2 = new Thread[ndelthrs];
      // avoid stale plan checks
      System.setProperty(Property.LANGUAGE_STALE_PLAN_CHECK_INTERVAL,
          "99999999");

      PreparedStatement pstmt = conn
          .prepareStatement("put into t.test values (?, ?, ?, ?)");
      for (int i = 1; i <= 100; i++) {
        pstmt.setString(1, "id1_" + i);
        pstmt.setString(2, "id2_" + (i / (50 + (i % 100))));
        pstmt.setString(3, "id3_" + (i % 3000));
        pstmt.setString(4, "id4_" + (i % 5));
        try {
          assertEquals(1, pstmt.executeUpdate());
        } catch (SQLException sqle) {
          // ignore unique constraint violations
          if (!"23505".equals(sqle.getSQLState())) {
            throw sqle;
          }
        }
      }
      conn.commit();

      for (int i = 0; i < ndelthrs; i++) {
        deleteThreads2[i] = new Thread(doDeletes);
        deleteThreads2[i].start();
      }

      boolean allJoined = false;
      while (!allJoined) {
        allJoined = true;
        for (int i = 0; i < ndelthrs; i++) {
          deleteThreads2[i].join(1000);
          allJoined &= !deleteThreads2[i].isAlive();
        }
        if (failure[0] != null) {
          // terminate threads
          endTime1.set(System.currentTimeMillis());
        }
      }

      if (failure[0] != null) {
        throw failure[0];
      }

      conn.commit();

      // validate indexes and zero size at the end
      rs = stmt.executeQuery("select count(*) from t.test");
      JDBC.assertSingleValueResultSet(rs, "0");
      rs.close();
      rs = stmt.executeQuery("values SYSCS_UTIL.CHECK_TABLE('T', 'TEST')");
      JDBC.assertSingleValueResultSet(rs, "1");
      rs.close();
    }

    stmt.execute("drop table t.test");
    conn.commit();
  }

  private static int incRowIdx(int rowIdx, int myOffset, int numThreads) {
    rowIdx += numThreads;
    return ((rowIdx - 1) % 300000) + 1;
  }

  private static int getRowIdxOffset(int myOffset, int numThreads) {
    int rnd = PartitionedRegion.rand.nextInt(300000);
    return (rnd / numThreads) * numThreads + myOffset;
  }

  private void checkIndexColumn(ResultSet rs, int columnNumber,
      Object expectedValue, AtomicInteger numRows) throws Exception {
    Object[] failedRow = null;
    while (rs.next()) {
      if (!expectedValue.equals(rs.getObject(columnNumber))) {
        failedRow = new Object[rs.getMetaData().getColumnCount()];
        for (int index = 0; index < failedRow.length; index++) {
          failedRow[index] = rs.getObject(index + 1);
        }
      }
      else if (numRows != null) {
        numRows.incrementAndGet();
      }
    }
    rs.close();
    if (failedRow != null) {
      fail("unexpected index column [" + failedRow[columnNumber - 1]
          + "] expected [" + expectedValue + "] for row: "
          + java.util.Arrays.toString(failedRow));
    }
  }

  private <T> void checkIndexColumn(ResultSet rs, int columnNumber,
      Comparable<T> expectedValue1, Comparable<T> expectedValue2,
      AtomicInteger numRows) throws Exception {
    Object[] failedRow = null;
    while (rs.next()) {
      @SuppressWarnings("unchecked")
      T col = (T)rs.getObject(columnNumber);
      if (expectedValue1.compareTo(col) > 0
          || expectedValue2.compareTo(col) <= 0) {
        failedRow = new Object[rs.getMetaData().getColumnCount()];
        for (int index = 0; index < failedRow.length; index++) {
          failedRow[index] = rs.getObject(index + 1);
        }
      }
      else if (numRows != null) {
        numRows.incrementAndGet();
      }
    }
    rs.close();
    if (failedRow != null) {
      fail("unexpected index column [" + failedRow[columnNumber - 1]
          + "] expected [" + expectedValue1 + ", " + expectedValue2
          + ") for row: " + java.util.Arrays.toString(failedRow));
    }
  }

  private void runAtomicOps(final int numThreads, final int numRuns,
      final AtomicIntegerFieldUpdater<ConcurrentMapOpsHolder> intJDKCounter,
      final AtomicLongFieldUpdater<ConcurrentMapOpsHolder> longJDKCounter,
      final AtomicReferenceFieldUpdater<ConcurrentMapOpsHolder, LongRef> refJDKCounter,
      final AtomicIntegerFieldUpdater<ConcurrentMapOpsHolder> intUnsafeCounter,
      final AtomicLongFieldUpdater<ConcurrentMapOpsHolder> longUnsafeCounter,
      final AtomicReferenceFieldUpdater<ConcurrentMapOpsHolder, LongRef> refUnsafeCounter) {

    final AtomicInteger atomicIntCounter = new AtomicInteger();
    final AtomicLong atomicLongCounter = new AtomicLong();
    final AtomicReference<LongRef> atomicRefCounter =
        new AtomicReference<LongRef>(new LongRef(0));

    final Thread[] threads = new Thread[numThreads];
    atomicIntCounter.set(0);
    atomicLongCounter.set(0);
    atomicRefCounter.set(new LongRef(0));
    intJDKCounter.set(this, 0);
    longJDKCounter.set(this, 0);
    refJDKCounter.set(this, new LongRef(0));
    intUnsafeCounter.set(this, 0);
    longUnsafeCounter.set(this, 0);
    refUnsafeCounter.set(this, new LongRef(0));
    refCounter = new LongRef(0);

    final CyclicBarrier barrier = new CyclicBarrier(numThreads + 1);

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(new Runnable() {
        @Override
        public void run() {
          // wait for all threads to come here before starting
          awaitBarrier(barrier);

          for (int i = 0; i < numRuns; i++) {
            atomicIntCounter.incrementAndGet();
          }
          // wait for all threads to end part1
          awaitBarrier(barrier);
          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            atomicLongCounter.incrementAndGet();
          }
          // wait for all threads to end part2
          awaitBarrier(barrier);
          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            while (true) {
              LongRef update = new LongRef(0);
              LongRef expect = atomicRefCounter.get();
              update.v = expect.v + 1;
              if (atomicRefCounter.compareAndSet(expect, update)) {
                break;
              }
            }
          }
          // wait for all threads to end part3
          awaitBarrier(barrier);

          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            intJDKCounter.incrementAndGet(ConcurrentMapOpsTest.this);
          }
          // wait for all threads to end part4
          awaitBarrier(barrier);
          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            longJDKCounter.incrementAndGet(ConcurrentMapOpsTest.this);
          }
          // wait for all threads to end part5
          awaitBarrier(barrier);
          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            while (true) {
              LongRef update = new LongRef(0);
              LongRef expect = refJDKCounter.get(ConcurrentMapOpsTest.this);
              update.v = expect.v + 1;
              if (refJDKCounter.compareAndSet(ConcurrentMapOpsTest.this,
                  expect, update)) {
                break;
              }
            }
          }
          // wait for all threads to end part6
          awaitBarrier(barrier);

          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            intUnsafeCounter.incrementAndGet(ConcurrentMapOpsTest.this);
          }
          // wait for all threads to end part7
          awaitBarrier(barrier);
          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            longUnsafeCounter.incrementAndGet(ConcurrentMapOpsTest.this);
          }
          // wait for all threads to end part8
          awaitBarrier(barrier);
          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            while (true) {
              LongRef update = new LongRef(0);
              LongRef expect = refUnsafeCounter.get(ConcurrentMapOpsTest.this);
              update.v = expect.v + 1;
              if (refUnsafeCounter.compareAndSet(ConcurrentMapOpsTest.this,
                  expect, update)) {
                break;
              }
            }
          }
          // wait for all threads to end part9
          awaitBarrier(barrier);

          awaitBarrier(barrier);
          for (int i = 0; i < numRuns; i++) {
            synchronized (ConcurrentMapOpsTest.this) {
              refCounter = new LongRef(refCounter.v + 1);
            }
          }
          // wait for all threads to end part10
          awaitBarrier(barrier);
        }
      });
      threads[i].start();
    }

    // now start threads for each of the 9 parts one by one and take the timing
    // for each run
    long start, end;

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: AtomicInteger time for " + numRuns + " ops with "
        + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(numRuns * numThreads, atomicIntCounter.get());

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: AtomicLong time for " + numRuns + " ops with "
        + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(numRuns * numThreads, atomicLongCounter.get());

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: AtomicReference time for " + numRuns + " ops with "
        + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(new LongRef(numRuns * numThreads), atomicRefCounter.get());

    doGC();

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: AtomicIntegerFieldUpdater time for " + numRuns
        + " ops with " + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(numRuns * numThreads, intJDKCounter.get(this));

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: AtomicLongFieldUpdater time for " + numRuns
        + " ops with " + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(numRuns * numThreads, longJDKCounter.get(this));

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: AtomicReferenceFieldUpdater time for " + numRuns
        + " ops with " + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(new LongRef(numRuns * numThreads), refJDKCounter.get(this));

    doGC();

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: UnsafeIntegerFieldUpdater time for " + numRuns
        + " ops with " + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(numRuns * numThreads, intUnsafeCounter.get(this));

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: UnsafeLongFieldUpdater time for " + numRuns
        + " ops with " + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(numRuns * numThreads, longUnsafeCounter.get(this));

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: UnsafeRefFieldUpdater time for " + numRuns
        + " ops with " + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(new LongRef(numRuns * numThreads), refUnsafeCounter.get(this));

    doGC();

    start = System.currentTimeMillis();
    awaitBarrier(barrier);
    awaitBarrier(barrier);
    end = System.currentTimeMillis();
    getLogger().info("PERF: Ref sync time for " + numRuns
        + " ops with " + numThreads + " threads: " + (end - start) + " millis");
    assertEquals(new LongRef(numRuns * numThreads), refCounter);

    doGC();

    for (int i = 0; i < numThreads; i++) {
      try {
        threads[i].join();
      } catch (Exception e) {
        fail("unexpected exception in join", e);
      }
    }

    getLogger().info("=====================================================");

    doGC();
  }

  private static final void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception e) {
      fail("unexpected exception in barrier await", e);
    }
  }

  private static void doGC() {
    System.gc();
    System.runFinalization();
    try {
      Thread.sleep(1000);
    } catch (Exception e) {
      fail("unexepected exception in sleep", e);
    }
    System.gc();
    System.runFinalization();
  }
}

abstract class ConcurrentMapOpsHolder extends JdbcTestBase {

  @SuppressWarnings("unused")
  volatile int intJDKCounter;
  @SuppressWarnings("unused")
  volatile long longJDKCounter;
  @SuppressWarnings("unused")
  volatile LongRef refJDKCounter;

  @SuppressWarnings("unused")
  volatile int intUnsafeCounter;
  @SuppressWarnings("unused")
  volatile long longUnsafeCounter;
  @SuppressWarnings("unused")
  volatile LongRef refUnsafeCounter;

  static final AtomicIntegerFieldUpdater<ConcurrentMapOpsHolder>
      intJDKCounterUpdate = AtomicIntegerFieldUpdater.newUpdater(
      ConcurrentMapOpsHolder.class, "intJDKCounter");
  static final AtomicLongFieldUpdater<ConcurrentMapOpsHolder>
      longJDKCounterUpdate = AtomicLongFieldUpdater.newUpdater(
      ConcurrentMapOpsHolder.class, "longJDKCounter");
  static final AtomicReferenceFieldUpdater<ConcurrentMapOpsHolder, LongRef>
      refJDKCounterUpdate = AtomicReferenceFieldUpdater.newUpdater(
      ConcurrentMapOpsHolder.class, LongRef.class, "refJDKCounter");

  static final AtomicIntegerFieldUpdater<ConcurrentMapOpsHolder>
      intUnsafeCounterUpdate = AtomicUpdaterFactory.newIntegerFieldUpdater(
      ConcurrentMapOpsHolder.class, "intUnsafeCounter");
  static final AtomicLongFieldUpdater<ConcurrentMapOpsHolder>
      longUnsafeCounterUpdate = AtomicUpdaterFactory.newLongFieldUpdater(
      ConcurrentMapOpsHolder.class, "longUnsafeCounter");
  static final AtomicReferenceFieldUpdater<ConcurrentMapOpsHolder, LongRef>
      refUnsafeCounterUpdate = AtomicUpdaterFactory.newReferenceFieldUpdater(
      ConcurrentMapOpsHolder.class, LongRef.class, "refUnsafeCounter");

  public static class LongRef {
    long v;

    public LongRef(long v) {
      this.v = v;
    }

    public final long getLong() {
      return this.v;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof LongRef) {
        LongRef lr = (LongRef)o;
        return (this.v == lr.v);
      } else {
        return false;
      }
    }

    @Override
    public int hashCode() {
      int hi = (int)(this.v >>> 32);
      int lo = (int)(this.v & 0xFFFFFFFF);
      return (hi ^ lo);
    }

    @Override
    public String toString() {
      return "LongRef=" + this.v;
    }
  }

  public ConcurrentMapOpsHolder(String name) {
    super(name);
  }
}
