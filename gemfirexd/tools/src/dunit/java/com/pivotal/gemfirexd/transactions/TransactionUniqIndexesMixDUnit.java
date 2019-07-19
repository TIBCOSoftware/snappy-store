package com.pivotal.gemfirexd.transactions;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class TransactionUniqIndexesMixDUnit extends DistributedSQLTestBase {
  public TransactionUniqIndexesMixDUnit(String name) {
    super(name);
  }

  protected String reduceLogging() {
    return "config";
  }

  protected int getIsolationLevel() {
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  private void createDDLs() throws Exception {
    java.sql.Connection conn = TestUtil.jdbcConn;
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("Create table app.t1 (c1 int not null , c2 int not null," +
        " c3 int not null , c4 int not null, "
        + "primary key(c4)) replicate");
    st.execute("create index localindex on app.t1(c3)");
    st.execute("create unique index uniqindex on app.t1(c1)");
  }
  // In all test create a normal index and a uniq index
  // Test Case 1:
  //   Insert a row
  //
  //
  public void testTransactionalInsertDeleteOnReplicatedTable_1() throws Exception {
    createDDLs();
    java.sql.Connection conn = TestUtil.jdbcConn;
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    ResultSet rs = null;
    Statement st = conn.createStatement();

    st.execute("insert into app.t1 values (10, 10, 10, 10)");
    conn.commit();

    // craete another connection
    Connection conn2 = TestUtil.getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    Statement st2 = conn2.createStatement();
    // st.execute("insert into app.t1 values (10, 10, 10, 10)");
    st.execute("delete from app.t1 where c4 = 10");
    // Insert on another connection should fail
    try {
      st2.execute("insert into app.t1 values (10, 10, 10, 10)");
      fail("above should have failed with unique key violation exception");
    } catch (SQLException sqle) {
      System.out.println("KN: sqlstate = " + sqle.getSQLState());
      assertTrue("23505".equalsIgnoreCase(sqle.getSQLState()) ||
              "X0Z02".equalsIgnoreCase(sqle.getSQLState()));
    }
    // Since an insert is being done after a delete on transaction one
    // this should pass
    st.execute("insert into app.t1 values (10, 10, 10, 10)");

    conn.commit();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER FINAL COMMIT");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    conn.close();
  }

  public void testTransactionalInsertDeleteOnReplicatedTable() throws Exception {
    java.sql.Connection conn = TestUtil.jdbcConn;
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("Create table app.t1 (c1 int not null , c2 int not null," +
        " c3 int not null , c4 int not null, "
        + "primary key(c4)) replicate");
    st.execute("create index localindex on app.t1(c3)");
    st.execute("create unique index uniqindex on app.t1(c1)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    ResultSet rs = null;
    st.execute("insert into app.t1 values (10, 10, 10, 10)");
    st.execute("insert into app.t1 values (20, 20, 20, 20)");
    conn.commit();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER FIRST TWO INSERT");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    st.execute("select * from app.t1");
    st.execute("delete from app.t1 where c4 = 10");
    st.execute("insert into app.t1 values (10, 10, 10, 10)");
    // do few updates again
    st.execute("update app.t1 set c3=50 where c4=10");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: BEFORE UPDATE -- ROLLBACK");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    st.execute("update app.t1 set c1=50 where c4=10");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER UPDATE -- ROLLBACK");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: JUST BEFORE ROLLBACK");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    conn.rollback();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER FIRST ROLLBACK");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    st.execute("delete from app.t1 where c4 = 10");
    st.execute("insert into app.t1 values (10, 10, 10, 10)");
    conn.commit();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER FINAL COMMIT");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    rs = st.executeQuery("select count(*) from app.t1");
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 2);
    assertFalse(rs.next());
    conn.commit();
    conn.close();
  }

  public void testTransactionalScanWhenUpdateOnNonUniqueIndexedColumn() throws Exception {
    java.sql.Connection conn = TestUtil.jdbcConn;
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("Create table app.t1 (c1 int not null , c2 int not null," +
        " c3 int not null , c4 int not null, "
        + "primary key(c4)) replicate");
    st.execute("create index localindex on app.t1(c3)");
    st.execute("create unique index uniqindex on app.t1(c1)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    ResultSet rs = null;
    st.execute("insert into app.t1 values (10, 10, 10, 10)");
    st.execute("insert into app.t1 values (20, 20, 20, 20)");
    conn.commit();
    st.execute("update app.t1 set c3=50 where c4=10");
    st.execute("select c3 from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 50);
    conn.commit();
    st.execute("select c3 from app.t1 where c1 = 10");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 50);
    conn.commit();
    conn.close();
  }

  public void testOtherTxnalScanWhenDelAndInsertOnUniqueIndexedColumnByOtherTxn() throws Exception {
    java.sql.Connection conn = TestUtil.jdbcConn;
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("Create table app.t1 (c1 int not null , c2 int not null," +
        " c3 int not null , c4 int not null, "
        + "primary key(c4)) replicate");
    st.execute("create index localindex on app.t1(c3)");
    st.execute("create unique index uniqindex on app.t1(c1)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    ResultSet rs = null;
    st.execute("insert into app.t1 values (10, 10, 10, 10)");
    st.execute("insert into app.t1 values (20, 20, 20, 20)");
    conn.commit();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER FIRST TWO INSERT");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    st.execute("delete from app.t1 where c4=10");
    st.execute("insert into app.t1 values (10, 70, 70, 70)");
    st.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 10);
    assertEquals(rs.getInt(2), 70);
    assertEquals(rs.getInt(3), 70);
    assertEquals(rs.getInt(4), 70);
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: DELETE INSERT BEFORE COMMIT TX 1");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    // Take another connection and fire the same query ... that should see the
    // committed value
    Connection conn2 = TestUtil.getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    Statement st2 = conn2.createStatement();
    st2.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st2.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 10);
    assertEquals(rs.getInt(2), 10);
    assertEquals(rs.getInt(3), 10);
    assertEquals(rs.getInt(4), 10);
    conn2.commit();
    conn.commit();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER COMMIT TX 1");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    // Now both the connection should see same committed row corresponding to 70
    st.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 10);
    assertEquals(rs.getInt(2), 70);
    assertEquals(rs.getInt(3), 70);
    assertEquals(rs.getInt(4), 70);
    conn.commit();
    st2.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st2.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 10);
    assertEquals(rs.getInt(2), 70);
    assertEquals(rs.getInt(3), 70);
    assertEquals(rs.getInt(4), 70);
    conn2.commit();
    conn.close();
    conn2.close();
  }

  public void testOtherTxnalScanWhenDelAndInsertOnUpdateOfUniqueIndexedColumn() throws Exception {
    java.sql.Connection conn = TestUtil.jdbcConn;
    conn.setAutoCommit(false);
    Statement st = conn.createStatement();
    st.execute("Create table app.t1 (c1 int not null , c2 int not null," +
        " c3 int not null , c4 int not null, "
        + "primary key(c4)) replicate");
    st.execute("create index localindex on app.t1(c3)");
    st.execute("create unique index uniqindex on app.t1(c1)");
    conn.commit();
    conn.setTransactionIsolation(getIsolationLevel());
    ResultSet rs = null;
    st.execute("insert into app.t1 values (10, 10, 10, 10)");
    st.execute("insert into app.t1 values (20, 20, 20, 20)");
    conn.commit();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER FIRST TWO INSERT");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    st.execute("delete from app.t1 where c4=10");
    st.execute("insert into app.t1 values (70, 70, 70, 70)");
    st.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 70");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 70);
    assertEquals(rs.getInt(2), 70);
    assertEquals(rs.getInt(3), 70);
    assertEquals(rs.getInt(4), 70);
    st.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st.getResultSet();
    assertFalse(rs.next());
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: DELETE INSERT BEFORE COMMIT TX 1");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
    // Take another connection and fire the same query ... that should see the
    // committed value
    Connection conn2 = TestUtil.getConnection();
    conn2.setTransactionIsolation(getIsolationLevel());
    Statement st2 = conn2.createStatement();
    st2.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st2.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 10);
    assertEquals(rs.getInt(2), 10);
    assertEquals(rs.getInt(3), 10);
    assertEquals(rs.getInt(4), 10);
    conn2.commit();
    conn.commit();
    // Now both the connection should see same committed row corresponding to 70
    st.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st.getResultSet();
    assertFalse(rs.next());
    st.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 70");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 70);
    assertEquals(rs.getInt(2), 70);
    assertEquals(rs.getInt(3), 70);
    assertEquals(rs.getInt(4), 70);
    conn.commit();
    st2.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 10");
    rs = st2.getResultSet();
    assertFalse(rs.next());
    st2.execute("select * from app.t1 --GEMFIREXD-PROPERTIES index=uniqindex\n where c1 = 70");
    rs = st2.getResultSet();
    assertTrue(rs.next());
    assertEquals(rs.getInt(1), 70);
    assertEquals(rs.getInt(2), 70);
    assertEquals(rs.getInt(3), 70);
    assertEquals(rs.getInt(4), 70);
    conn2.commit();
    conn.close();
    conn2.close();
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        if (GemFireCacheImpl.getInstance() != null &&
            ServerGroupUtils.isDataStore()) {
          String regionPath = "/APP/T1";
          Region r = Misc.getRegionByPath(regionPath);
          DistributedRegion dr = (DistributedRegion) r;
          dr.getLogWriterI18n().fine("KN: AFTER COMMIT TX 1");
          //pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
          GfxdIndexManager idxmgr = (GfxdIndexManager)dr.getIndexUpdater();
          idxmgr.dumpAllIndexes();
        }
      }
    });
  }
  // static lists to transfer clientVMs/serverVMs between tests since each test
  // creates a new test object
  private static List<VM> globalClientVMs;
  private static List<VM> globalServerVMs;

  @Override
  public void beforeClass() throws Exception {
    globalClientVMs = null;
    globalServerVMs = null;
    super.beforeClass();
    super.baseShutDownAll();
    deleteAllOplogFiles();
    getLogWriter().info(getClass() + ".beforeClass: starting 1+3 VMs");
    startVMs(1, 3);
    getLogWriter().info(getClass() + ".beforeClass: started 1+3 VMs: " +
        clientVMs + " ; " + serverVMs);
    currentUserName = GemFireXDUtils.getRandomString(true);
  }

  @Override
  public void setUp() throws Exception {
    super.commonSetUp();
    super.baseSetUp();
    if (globalClientVMs != null) {
      clientVMs.clear();
      clientVMs.addAll(globalClientVMs);
      serverVMs.clear();
      serverVMs.addAll(globalServerVMs);
    }
    resetObservers();
    invokeInEveryVM(TransactionDUnit.class, "resetObservers");
    String userName = currentUserName;
    setupConnection(userName);
    invokeInEveryVM(TransactionDUnit.class, "setupConnection",
        new Object[]{userName});
    getLogWriter().info(getClass() + "." + getTestName() + ".setUp VMs: " +
        clientVMs + " ; " + serverVMs);
  }

  public static void setupConnection(String userName) throws SQLException {
    resetConnection();
    TestUtil.currentUserName = userName;
    TestUtil.currentUserPassword = userName;
    if (GemFireCacheImpl.getInstance() != null) {
      TestUtil.setupConnection();
    }
  }

  @Override
  protected void baseShutDownAll() throws Exception {
    TestUtil.stopNetServer();
    invokeInEveryVM(TestUtil.class, "stopNetServer");
    globalClientVMs = clientVMs;
    globalServerVMs = serverVMs;
  }

  @Override
  public void afterClass() throws Exception {
    globalClientVMs = null;
    globalServerVMs = null;
    super.baseShutDownAll();
    super.afterClass();
  }

  public static void resetObservers() {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache != null) {
      TXManagerImpl txMgr = cache.getCacheTransactionManager();
      for (TXStateProxy tx : txMgr.getHostedTransactionsInProgress()) {
        tx.setObserver(null);
      }
      txMgr.setObserver(null);
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  protected static String currentUserName;
}
