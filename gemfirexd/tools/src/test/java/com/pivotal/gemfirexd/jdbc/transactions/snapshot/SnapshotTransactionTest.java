package com.pivotal.gemfirexd.jdbc.transactions.snapshot;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.RegionShortcut;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.gemstone.gemfire.internal.cache.versions.DiskRegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.org.jgroups.oswego.concurrent.CyclicBarrier;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;


public class SnapshotTransactionTest  extends JdbcTestBase {

  private GemFireCacheImpl cache;

  private boolean gotConflict = false;

  private volatile Throwable threadEx;

  public SnapshotTransactionTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "fine";
  }

  public void testRVVSnapshotContains() throws Exception {
    Connection conn = getConnection();
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    PartitionAttributes prAttr = paf.setTotalNumBuckets(1).create();
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setPartitionAttributes(prAttr);
    final Region r = GemFireCacheImpl.getInstance().createRegion("t1", attr.create());

    DiskStoreID ownerId = new DiskStoreID(0, 0);
    DiskStoreID id1 = new DiskStoreID(0, 1);
    //DiskStoreID id2 = new DiskStoreID(1, 0);

    DiskRegionVersionVector rvv = new DiskRegionVersionVector(ownerId);

    DiskRegionVersionVector rvv1 = new DiskRegionVersionVector(ownerId);

    for (int i = 0; i <= 757; i++) {
      rvv1.recordVersion(id1, i);
    }
    rvv.recordVersion(id1, 758);

    rvv.recordVersions(rvv1);

    System.out.println("rvv " + rvv.fullToString());

    System.out.println("SKSK Contains " + rvv.contains(id1, 758));

    rvv.recordVersion(id1, 760);

    rvv.recordVersion(id1, 762);

    rvv.recordVersion(id1, 758);

    System.out.println("rvv " + rvv.fullToString());

    System.out.println("SKSK Contains " + rvv.contains(id1, 758));

  }

  public void testSnapshotInsertTableAPI() throws Exception {
    Connection conn = getConnection();
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    PartitionAttributes prAttr = paf.setTotalNumBuckets(1).create();
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setPartitionAttributes(prAttr);
    final Region r = GemFireCacheImpl.getInstance().createRegion("t1", attr.create());

    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    r.put(1,1);
    r.put(2,2);
    // even before commit it should be visible
    assertEquals(1, r.get(1));
    assertEquals(2, r.get(2));
    r.getCache().getCacheTransactionManager().commit();

    //take an snapshot again//gemfire level
    // gemfirexd needs to call this to take snapshot
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    // read the entries
    assertEquals(1, r.get(1));// get don't need to take from snapshot
    assertEquals(2, r.get(2));// get don't need to take from snapshot

    //itr will work on a snapshot.
    TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
    Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);

    Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);

    // after this start another insert in a separate thread and those put shouldn't be visible
    Runnable run = new Runnable() {
      @Override
      public void run() {
        ((LocalRegion)r).put(3,3);
        ((LocalRegion)r).put(4,4);
      }
    };
    Thread t = new Thread(run);
    t.start();
    t.join();

    int num = 0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(2, num);
    // should be visible if read directly from region
    num = 0;
    while (itr.hasNext()) {
      RegionEntry re = (RegionEntry)itr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(4, num);
    r.getCache().getCacheTransactionManager().commit();

    // take new snapshot and all the data should be visisble
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    //itr will work on a snapshot. not other ops
    txstate = TXManagerImpl.getCurrentTXState();
    txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
    num = 0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(4, num);
    r.getCache().getCacheTransactionManager().commit();
  }


  public void testSnapshotInsertAPI() throws Exception {
    Connection conn = getConnection();
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    PartitionAttributes prAttr = paf.setTotalNumBuckets(1).create();
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setPartitionAttributes(prAttr);
    final Region r = GemFireCacheImpl.getInstance().createRegion("t1", attr.create());

    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    r.put(1,1);
    r.put(2,2);
    // even before commit it should be visible
    assertEquals(1, r.get(1));
    assertEquals(2, r.get(2));
    r.getCache().getCacheTransactionManager().commit();

    //take an snapshot again//gemfire level
    // gemfirexd needs to call this to take snapshot
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    // read the entries
    assertEquals(1, r.get(1));// get don't need to take from snapshot
    assertEquals(2, r.get(2));// get don't need to take from snapshot

    //itr will work on a snapshot.
    TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
    Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);

    Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);

    // after this start another insert in a separate thread and those put shouldn't be visible
    Runnable run = new Runnable() {
      @Override
      public void run() {
        ((LocalRegion)r).put(3,3);
        ((LocalRegion)r).put(4,4);
      }
    };
    Thread t = new Thread(run);
    t.start();
    t.join();

    int num = 0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(2, num);
    // should be visible if read directly from region
    num = 0;
    while (itr.hasNext()) {
      RegionEntry re = (RegionEntry)itr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(4, num);
    r.getCache().getCacheTransactionManager().commit();

    // take new snapshot and all the data should be visisble
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    //itr will work on a snapshot. not other ops
    txstate = TXManagerImpl.getCurrentTXState();
    txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
    num = 0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(4, num);
    r.getCache().getCacheTransactionManager().commit();
  }

  public void testSnapshotPutAllAPI() throws Exception {

    Connection conn = getConnection();
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    PartitionAttributes prAttr = paf.setTotalNumBuckets(1).create();
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setPartitionAttributes(prAttr);
    final Region r = GemFireCacheImpl.getInstance().createRegion("t1", attr.create());

    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    Map map = new HashMap();
    map.put(1,1);
    map.put(2,2);
    r.putAll(map);

    // even before commit it should be visible
    assertEquals(1, r.get(1));
    assertEquals(2, r.get(2));

    r.getCache().getCacheTransactionManager().commit();

    //take an snapshot again
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    // read the entries
    assertEquals(1, r.get(1));// get don't need to take from snapshot
    assertEquals(2, r.get(2));// get don't need to take from snapshot

    //itr will work on a snapshot.
    TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
    Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
    Iterator itr = ((LocalRegion)r).getSharedDataView().getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);

    // after this start another insert in a separate thread and those put shouldn't be visible
    Runnable run = new Runnable() {
      @Override
      public void run() {
        Map m = new HashMap();
        m.put(3,3);
        m.put(4,4);
        ((LocalRegion)r).putAll(m);
      }
    };
    Thread t = new Thread(run);
    t.start();
    t.join();

    int num = 0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(2, num);
    // should be visible if read directly from region
    num = 0;
    while (itr.hasNext()) {
      RegionEntry re = (RegionEntry)itr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(4, num);
    r.getCache().getCacheTransactionManager().commit();

    // take new snapshot and all the data should be visisble
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    //itr will work on a snapshot. not other ops
    txstate = TXManagerImpl.getCurrentTXState();
    txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r);
    num = 0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(4, num);
    r.getCache().getCacheTransactionManager().commit();
  }

  public void testSnapshotInsertUpdateDeleteAPI() throws Exception {
    Connection conn = getConnection();
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    PartitionAttributes prAttr = paf.setTotalNumBuckets(1).create();
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setPartitionAttributes(prAttr);
    final Region r = GemFireCacheImpl.getInstance().createRegion("t1", attr.create());

    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    Map map = new HashMap();
    map.put(1,1);
    map.put(2,2);
    r.putAll(map);
    // even before commit it should be visible
    assertEquals(1, r.get(1));
    assertEquals(2, r.get(2));
    r.getCache().getCacheTransactionManager().commit();

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
    t.join();

    int num = 0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone()) {
        num++;
        // 1,1 and 2,2
        assertEquals(re.getKey(), re.getValue(null));
      }
    }
    assertEquals(2, num);
    // should be visible if read directly from region
    num = 0;
    while (itr.hasNext()) {
      RegionEntry re = (RegionEntry)itr.next();
      if(!re.isTombstone()) {
        num++;
        assertEquals(1, re.getKey());
        assertEquals(2, re.getValue(null));
      }
    }
    assertEquals(1, num);
    r.getCache().getCacheTransactionManager().commit();

    //take an snapshot again
    r.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    // read the entries
    num = 0;
    while (itr.hasNext()) {
      RegionEntry re = (RegionEntry)itr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(1, num);
    r.getCache().getCacheTransactionManager().commit();
  }


  public void testTwoSnapshotInsertAPI() throws Exception {
    Connection conn = getConnection();
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    PartitionAttributes prAttr = paf.setTotalNumBuckets(1).create();
    AttributesFactory attr = new AttributesFactory();
    attr.setConcurrencyChecksEnabled(true);
    attr.setPartitionAttributes(prAttr);
    final Region r1 = GemFireCacheImpl.getInstance().createRegion("t1", attr.create());
    //final Region r2 = GemFireCacheImpl.getInstance().createRegion("t2", attr.create());

    r1.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    r1.put(1,1);
    r1.put(2, 2);
    r1.getCache().getCacheTransactionManager().commit();

    final Object sync = new Object();
    Runnable run = new Runnable() {
      @Override
      public void run() {
        r1.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
        r1.put(3,3);

        synchronized (sync) {
          try {
            sync.wait();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
        // wait give control to other thread
        // that thread should take snapshot and read. and shouldn't see 3,3
        r1.put(4,4);
        r1.getCache().getCacheTransactionManager().commit();

      }
    };

    Thread t = new Thread(run);
    t.start();

    while(r1.get(3) == null) {
      Thread.sleep(10);
    }
    r1.getCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
    assertEquals(null, r1.get(4));

    synchronized (sync) {
      sync.notifyAll();
    }
    t.join();

    TXStateInterface txstate = TXManagerImpl.getCurrentTXState();
    Iterator txitr = txstate.getLocalEntriesIterator(null, false, false, true, (LocalRegion)r1);
    int num=0;
    while (txitr.hasNext()) {
      RegionEntry re = (RegionEntry)txitr.next();
      if(!re.isTombstone())
        num++;
    }
    assertEquals(2, num);
    r1.getCache().getCacheTransactionManager().commit();

  }

  public void testCommitOnReplicatedTable1() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) replicate"+getSuffix());
    //conn.commit();
    conn = getConnection();
    //conn.setTransactionIsolation(getIsolationLevel());
    //conn.setAutoCommit(true);

    st = conn.createStatement();
    //st.execute("insert into t1 values (10, 10)");

    //conn.rollback();// rollback.

    ResultSet rs = st.executeQuery("Select * from t1");
    assertFalse("ResultSet should be empty ", rs.next());
    rs.close();

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        Connection conn = null;
        try {
          conn = getConnection();
          Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("Select * from t1");
          int numRows = 0;
          while (rs.next()) {
            // Checking number of rows returned, since ordering of results
            // is not guaranteed. We can write an order by query for this (another
            // test).
            numRows++;
          }
          assertEquals("ResultSet should contain two rows ", 2, numRows);
          rs.close();
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }
    });
    t.start();
    t.join();

    //conn.commit(); // commit two rows.
    rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      // Checking number of rows returned, since ordering of results
      // is not guaranteed. We can write an order by query for this (another
      // test).
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    // Close connection, resultset etc...
    rs.close();
    st.close();
    //conn.commit();
    conn.close();
  }

  public void testReadSnapshotOnReplicatedTable() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) replicate persistent enable concurrency checks"+getSuffix());
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);

    st = conn.createStatement();
    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    // withing tx also the row count should be 2
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    rs = st.executeQuery("Select * from t1 where c1=10");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    // withing tx also the row count should be 1
    assertEquals("ResultSet should contain one rows ", 1, numRows);

    conn.commit();
    rs = st.executeQuery("Select * from t1");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    st.execute("delete from t1 where c1=10");
    conn.commit();
    rs = st.executeQuery("Select * from t1");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);

    //start a read tx and another tx for insert, current tx shouldn't see new entry
    rs = st.executeQuery("Select * from t1");
    // do some insert operation in different transaction
    doInsertOpsInTx();
    // even after commit of above tx, as the below was started earlier
    // it shouldn't see entry of previous tx.
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);

    conn.commit();
    // start a read tx, it should see all the changes.
    rs = st.executeQuery("Select * from t1 ");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain eight row ", 8, numRows);
    conn.commit();

    // Close connection, resultset etc...
    rs.close();
    st.close();
    conn.commit();
    conn.close();
  }

  // only insert operations to ignore
  public void testReadSnapshotOnPartitionedTable() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by column (c1) enable concurrency checks "+getSuffix());
    conn = getConnection();
    st = conn.createStatement();

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    // withing tx also the row count should be 2
    assertEquals("ResultSet should contain two row ", 2, numRows);

    rs = st.executeQuery("Select * from t1 where c1=10");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    // withing tx also the row count should be 1
    assertEquals("ResultSet should contain one row ", 1, numRows);
    //conn.commit(); // commit two rows.

    rs = st.executeQuery("Select * from t1");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    st.execute("delete from t1 where c1=10");

    rs = st.executeQuery("Select * from t1 ");
    doInsertOpsInTx();
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);

    st.execute("truncate table t1");

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");
    st.execute("delete from t1 where c1=10");

    rs = st.executeQuery("Select * from t1 where c1 = 30");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 0, numRows);

    rs = st.executeQuery("Select * from t1 where c1 > 1");
    doInsertOpsInTx();
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);
    rs = st.executeQuery("Select * from t1 where c2 > 20");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 7, numRows);

    // Close connection, resultset etc...
    rs.close();
    st.close();
    //conn.commit();
    conn.close();
  }

  // test putAll path
  // test contains path
  // test local index path
  //foreign key?


  public void testSnapshotAgainstUpdateOperations() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
        + "primary key(c1)) partition by column (c1) enable concurrency checks "+getSuffix());
    conn.commit();
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    //conn.setAutoCommit(false);

    st = conn.createStatement();

    st.execute("insert into t1 values (10, 10, 20)");
    st.execute("insert into t1 values (20, 20, 20)");

    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    // within tx also the row count should be 2
    assertEquals("ResultSet should contain two row ", 2, numRows);

    rs = st.executeQuery("Select * from t1 where c1=10");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    // withing tx also the row count should be 1
    assertEquals("ResultSet should contain one row ", 1, numRows);
    conn.commit(); // commit two rows.

    // start a read tx
    rs = st.executeQuery("Select * from t1");
    // another thread update all row
    doUpdateOpsInTx();

    // iterate over the ResultSet
    numRows = 0;
    while (rs.next()) {
      numRows++;
      int c2 = rs.getInt("c3");
      assertEquals("C3 should be  20 ", 20, c2);
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);
    //assert that old value is returned
  }

  public void testSnapshotAgainstDeleteOperations() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null, "
        + "primary key(c1)) partition by column (c1) enable concurrency checks "+getSuffix());
    conn.commit();
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);

    st = conn.createStatement();

    st.execute("insert into t1 values (10, 20, 10)");
    st.execute("insert into t1 values (20, 30, 20)");

    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    // withing tx also the row count should be 2
    assertEquals("ResultSet should contain two row ", 2, numRows);
    conn.commit(); // commit two rows.

    // start a read snapshot
    rs = st.executeQuery("Select * from t1");

    // another thread delete one row
    doDeleteOpsInTx();

    // iterate over the ResultSet
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain two row ", 2, numRows);
    //assert that old value is returned
  }

  public void _testSnapshotAgainstMultipleTable() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by column (c1) enable concurrency checks "+getSuffix());

    st.execute("Create table t2 (c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by column (c1) enable concurrency checks "+getSuffix());
    conn.commit();
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    conn.setAutoCommit(false);

    st = conn.createStatement();

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    st.execute("insert into t2 values (10, 10)");
    st.execute("insert into t2 values (20, 20)");

    conn.commit(); // commit two rows.

    ResultSet rs = st.executeQuery("Select * from t1");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);

    rs = st.executeQuery("Select * from t2");
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain two rows ", 2, numRows);
    conn.commit();

    st.execute("delete from t1 where c1=10");
    conn.commit();

    //start a read tx(different flavor) and another tx for insert, current tx shouldn't see new entry
    //TODO: Currently can't execute multiple query, getting rs closed exception

   // rs = st.executeQuery("Select * from t1");

    ResultSet rs2 = st.executeQuery("Select * from t2");

    doInsertOpsInTx();
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);


    numRows = 0;
    while (rs2.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 2, numRows);

    st.execute("truncate table t1");
    st.execute("truncate table t2");

    st.execute("insert into t1 values (10, 10)");
    st.execute("insert into t1 values (20, 20)");

    st.execute("insert into t2 values (10, 10)");
    st.execute("insert into t2 values (20, 20)");

    conn.commit();
    st.execute("delete from t1 where c1=10");
    st.execute("delete from t2 where c1=10");
    conn.commit();

    rs = st.executeQuery("Select * from t1 where c1 = 30");
    //doInsertOpsInTx();
//
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 0, numRows);

    conn.commit();
    rs = st.executeQuery("Select * from t1 where c1 > 1");
    rs2 = st.executeQuery("Select * from t2 where c1 > 1");
    doInsertOpsInTxMultiTable();
//
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);

    numRows = 0;
    while (rs2.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 1, numRows);

    conn.commit();
    rs = st.executeQuery("Select * from t1 where c2 > 20");
    //doInsertOpsInTx();
//
    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 7, numRows);

    conn.commit();

//    ResultSet rs3 = st.executeQuery("Select * from t1 where c1 > 1");
//    ResultSet rs4 = st.executeQuery("Select * from t1 where c2 > 20");
//    ResultSet rs5 = st.executeQuery("Select * from t1 where c2 = 20");
    // do some insert operation in different transaction
    // doInsertOpsInTx();


//    numRows = 0;
//    while (rs.next()) {
//      numRows++;
//    }
//    assertEquals("ResultSet should contain one row ", 1, numRows);
//
//    numRows = 0;
//    while (rs3.next()) {
//      numRows++;
//    }
//    assertEquals("ResultSet should contain one row ", 1, numRows);
//
//    numRows = 0;
//    while (rs4.next()) {
//      numRows++;
//    }
//    assertEquals("ResultSet should contain one row ", 0, numRows);
//
//    numRows = 0;
//    while (rs5.next()) {
//      numRows++;
//    }
//    assertEquals("ResultSet should contain one row ", 1, numRows);
//
//
//    conn.commit();
//    // start a read tx, it should see all the changes.
//    rs = st.executeQuery("Select * from t1 ");
//    numRows = 0;
//    while (rs.next()) {
//      numRows++;
//    }
//    assertEquals("ResultSet should contain eight row ", 8, numRows);
//    conn.commit();


    //TODO: start a read tx and another tx for delete, current tx should be able to see old entry


    //TODO: start a read tx and another tx for update, current tx should be able to see old entry


    // Close connection, resultset etc...
    rs.close();
    st.close();
    //conn.commit();
    conn.close();

  }

  public void _testSnapshotAgainstMultipleTableInsert() throws Exception {

  }

  public void _testSnapshotAgainstMultipleTableDelete() throws Exception {

  }

  private void doInsertOpsInTxMultiTable() throws SQLException, InterruptedException {
    final Connection conn2 = getConnection();
    Runnable r = new Runnable(){
      @Override
      public void run() {
        try {
          Statement st = conn2.createStatement();
          conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);
          //conn2.setAutoCommit(false);
          st.execute("insert into t1 values (1, 30)");
          st.execute("insert into t1 values (2, 30)");
          st.execute("insert into t1 values (10, 30)");
          st.execute("insert into t1 values (123, 30)");
          st.execute("insert into t1 values (30, 30)");
          st.execute("insert into t1 values (40, 30)");
          st.execute("insert into t1 values (50, 30)");

          st.execute("insert into t2 values (1, 30)");
          st.execute("insert into t2 values (2, 30)");
          st.execute("insert into t2 values (123, 30)");
          st.execute("insert into t2 values (30, 30)");
          st.execute("insert into t2 values (40, 30)");
          st.execute("insert into t2 values (50, 30)");

          conn2.commit();
          ResultSet rs = st.executeQuery("Select * from t1");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          assertEquals("ResultSet should contain eight rows ", 8, numRows);


          rs = st.executeQuery("Select * from t2");
          numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          assertEquals("ResultSet should contain eight rows ", 8, numRows);
          conn2.commit();
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }
    };
    Thread t = new Thread(r);
    t.start();
    t.join();
  }

  private void doInsertOpsInTx() throws Exception, InterruptedException {
    final Connection conn2 = getConnection();
    final Exception[] tx = new Exception[1];

    Runnable r = new Runnable(){
      @Override
      public void run() {
        try {
          Statement st = conn2.createStatement();
          //conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);
          //conn2.setAutoCommit(true);
          st.execute("insert into t1 values (1, 30)");
          st.execute("insert into t1 values (2, 30)");
          st.execute("insert into t1 values (10, 30)");
          st.execute("insert into t1 values (123, 30)");
          st.execute("insert into t1 values (30, 30)");
          st.execute("insert into t1 values (40, 30)");
          st.execute("insert into t1 values (50, 30)");



          // conn2.commit();
          ResultSet rs = st.executeQuery("Select * from t1");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          assertEquals("ResultSet should contain eight rows ", 8, numRows);
          //conn2.commit();
        } catch (SQLException e) {
          e.printStackTrace();
          tx[0] = e;
        }
      }
    };
    Thread t = new Thread(r);
    t.start();
    t.join();
    if(tx[0] != null) {
      throw tx[0];
    }
  }

  private void doInsertOpsInTxForConcurrencytest() throws Exception, InterruptedException {
    final Connection conn2 = getConnection();
    final Exception[] tx = new Exception[1];
    Runnable r = new Runnable(){
      @Override
      public void run() {
        try {
          Statement st = conn2.createStatement();
          //conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);
          //conn2.setAutoCommit(true);
          st.execute("insert into t1 values (101, 30)");
          st.execute("insert into t1 values (102, 30)");
          st.execute("insert into t1 values (103, 30)");
          st.execute("insert into t1 values (104, 30)");
          st.execute("insert into t1 values (105, 30)");
          st.execute("insert into t1 values (106, 30)");
          st.execute("insert into t1 values (107, 30)");



         // conn2.commit();
          ResultSet rs = st.executeQuery("Select * from t1");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          assertEquals("ResultSet should contain 41 rows ", 41, numRows);
          //conn2.commit();
        } catch (SQLException e) {
          e.printStackTrace();
          tx[0] = e;
        }
      }
    };
    Thread t = new Thread(r);
    t.start();
    t.join();
    if(tx[0] != null) {
      throw tx[0];
    }
  }

  // do both..point update and scan update
  private void doUpdateOpsInTx() throws Exception, InterruptedException {
    final Connection conn2 = getConnection();
    final Exception[] tx = new Exception[1];

    Runnable r = new Runnable(){
      @Override
      public void run() {
        try {
          Statement st = conn2.createStatement();
          conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);
          conn2.setAutoCommit(false);
          st.execute("update t1 set c3=50 where c2 = 20");
          conn2.commit();
          ResultSet rs = st.executeQuery("Select * from t1");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          assertEquals("ResultSet should contain eight rows ", 2, numRows);
          conn2.commit();
        } catch (SQLException e) {
          e.printStackTrace();
          tx[0] = e;
        }
      }
    };
    Thread t = new Thread(r);
    t.start();
    t.join();
    if(tx[0] != null) {
      throw tx[0];
    }
  }

  private void doDeleteOpsInTx() throws Exception, InterruptedException {
    final Connection conn2 = getConnection();
    final Exception[] tx = new Exception[1];
    Runnable r = new Runnable(){
      @Override
      public void run() {
        try {
          Statement st = conn2.createStatement();
          conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);
          conn2.setAutoCommit(false);
          //GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          st.execute("delete from t1 where c2 = 20");
          conn2.commit();
          ResultSet rs = st.executeQuery("Select * from t1");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          assertEquals("ResultSet should contain eight rows ", 1, numRows);
          conn2.commit();
          //GemFireCacheImpl.getInstance().getCacheTransactionManager().commit();
        } catch (SQLException e) {
          tx[0] = e;
        }
      }
    };

    Thread t = new Thread(r);
    t.start();
    t.join();
    if(tx[0] != null) {
      throw tx[0];
    }
  }


  /**
   * Check supported isolation levels.
   */
  public void testIsolationLevels() throws Exception {
    // try {
    Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

    try {
      conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      fail("expected failure in unsupported isolation-level SERIALIZABLE");
    } catch (SQLException ex) {
      if (!ex.getSQLState().equalsIgnoreCase("XJ045")) {
        throw ex;
      }
    }
    conn.close();
  }


  // only insert operations to ignore
  public void SURtestReadSnapshotOnPartitionedTableInConcurrency() throws Exception {
    Connection conn = getConnection();
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    Statement st = conn.createStatement();
    // Use single bucket as it will be easy to test versions
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by column (c1) buckets 1 enable concurrency checks " + getSuffix
        ());
    conn.commit();
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());


    //Inserting 34 records to avoid null pointer exception while getting TxState
    for(int i=0;i<35;i++) {
      st.execute("insert into t1 values ("+i+", 10)");
    }


    //As there is only one bucket there will be only one bucket region
    PartitionedRegion region = (PartitionedRegion)Misc.getRegionForTableByPath("/APP/T1", false);
    BucketRegion bucketRegion = region.getDataStore().getAllLocalBucketRegions().iterator()
        .next();

    ResultSet rs = st.executeQuery("Select * from t1");

    TXState txState = TXManagerImpl.getCurrentSnapshotTXState().getLocalTXState();
    long initialVersion = getRegionVersionForTransaction(txState, bucketRegion);
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }

    st = conn.createStatement();
    // withing tx also the row count should be 34 as we have done rs.next once to begin transaction
    assertEquals("ResultSet should contain 35 rows ", 35, numRows);

    st.execute("delete from t1 where c1=1");

    rs = st.executeQuery("Select * from t1");

    TXState txState1 = TXManagerImpl.getCurrentSnapshotTXState().getLocalTXState();

    long versionAfterDelete = getRegionVersionForTransaction(txState1, bucketRegion);
    doInsertOpsInTxForConcurrencytest();
    long actualVersionAfterInsert = getRegionVersionForTransaction(txState, bucketRegion);

    // The insert done in above method should no affect the snapshot of transaction
    assert (actualVersionAfterInsert == initialVersion);

    //Version after delete operation should be one greater than the initial version
    assert (versionAfterDelete == (initialVersion + 1));


    numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    assertEquals("ResultSet should contain one row ", 33, numRows);


    conn.commit();
    final Object msg = new Object();

    //Start new transaction
    rs = st.executeQuery("Select * from t1");

    rs.next();

    TXState txState2 = TXManagerImpl.getCurrentSnapshotTXState().getLocalTXState();

    long versionBeforeExecutingThread = getRegionVersionForTransaction(txState2, bucketRegion);
    doInsertOpsInThread(msg);
    long versionAfterStartingThread = getRegionVersionForTransaction(txState2, bucketRegion);
    assert(versionBeforeExecutingThread == versionAfterStartingThread);


    //Sleep some time to let thread go in waiting state
    Thread.sleep(3000);
    conn.commit();
    rs = st.executeQuery("Select * from t1");
    rs.next();
    TXState txState3 = TXManagerImpl.getCurrentSnapshotTXState().getLocalTXState();
    //Iterating rs till last record in order for cleaning up the transaction
    while(rs.next());
    long versionAfterExecutingThreadWithNewTx = getRegionVersionForTransaction(txState3,
        bucketRegion);


    assert (versionAfterExecutingThreadWithNewTx == (versionAfterStartingThread + 4));

    synchronized (msg) {
      //Notify thread
      msg.notify();
    }
    synchronized (msg) {
      msg.wait();
    }

    conn.commit();
    rs = st.executeQuery("Select * from t1");
    rs.next();
    //Get old snapshot version of previous transaction to see the effect
    versionAfterExecutingThreadWithNewTx = getRegionVersionForTransaction(txState3,
        bucketRegion);
    TXState txState4 = TXManagerImpl.getCurrentSnapshotTXState().getLocalTXState();
    long versionAfterExecutingUpdate = getRegionVersionForTransaction(txState4,
        bucketRegion);
    assert(versionAfterExecutingUpdate == (versionAfterExecutingThreadWithNewTx+1));
    conn.commit();
    rs.close();
    st.close();
    conn.close();
  }

  protected int getIsolationLevel() {
    return Connection.TRANSACTION_NONE;
  }


  protected String getSuffix() {
    return " ";
  }

  private long getRegionVersionForTransaction(TXState txState, Region region) {
    long version = 0l;

    Map<String, Map<VersionSource,RegionVersionHolder>> expectedSnapshot = txState
        .getCurrentRvvSnapShot
            (region);
    version = expectedSnapshot.get(region.getFullPath()).values().iterator().next()
        .getVersion();
    return version;
  }

  private void doInsertOpsInThread(final Object msg) throws SQLException, InterruptedException {
    final Connection conn2 = getConnection();
    Runnable r = new Runnable(){
      @Override
      public void run() {
        try {
          Statement st = conn2.createStatement();
          conn2.setTransactionIsolation(Connection.TRANSACTION_NONE);
          //conn2.setAutoCommit(false);
          st.execute("insert into t1 values (210, 310)");
          st.execute("insert into t1 values (211, 311)");
          st.execute("insert into t1 values (212, 312)");
          st.execute("insert into t1 values (213, 314)");
          //msg.notify();
          // Wait for parent thread to verify version
          synchronized (msg) {
            msg.wait();
          }
          st.execute("update t1 set c2=410 where c1=210");
          // Wait for parent thread to verify version
          synchronized (msg) {
            msg.notify();
          }

          conn2.commit();
          ResultSet rs = st.executeQuery("Select * from t1");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          assertEquals("ResultSet should contain eight rows ", 45, numRows);
          conn2.commit();
        } catch (SQLException e) {
          e.printStackTrace();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    };
    Thread t = new Thread(r);
    t.start();
  }

  // we need to see how to provide snapshot isolation across table for user.
  public void testSnapshotAcrossRegion() throws Exception {

    Connection conn = getConnection();
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    Statement st = conn.createStatement();
    // Use single bucket as it will be easy to test versions
    st.execute("Create table t1 (c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by column (c1) buckets 1 enable concurrency checks " + getSuffix
        ());
    st.execute("Create table t2 (c1 int not null , c2 int not null, "
        + "primary key(c1)) partition by column (c1) buckets 1 enable concurrency checks " + getSuffix
        ());

    conn.commit();
    conn = getConnection();
    conn.setTransactionIsolation(getIsolationLevel());

    for (int i = 0; i < 10; i++) {
      st.execute("insert into t1 values (" + i + ", 10)");
      st.execute("insert into t2 values (" + (i + 11) + "," + (20 + i) + ")");
    }
    conn.commit();

    GemFireCacheImpl.getInstance().getTxManager().begin(IsolationLevel.SNAPSHOT, null);

    st.execute("insert into t1 values (100,101)");
    st.execute("insert into t1 values (200,201)");
    Thread t = new Thread() {
      @Override
      public void run() {
        try {
          final Connection conn2 = getConnection();
          Statement st2 = conn2.createStatement();
          ResultSet rs = st2.executeQuery("select * from t1 union select * from t2");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }
          System.out.println(numRows);
          // The count should be 20 as one insert was done by pausing recroding version for snapshot
          assert (numRows == 20);
          rs.close();
          conn2.close();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    t.start();
    t.join();

    //conn.commit();
    GemFireCacheImpl.getInstance().getTxManager().commit();

    st.execute("insert into t1 values (300,101)");
    st.execute("insert into t1 values (400,201)");
    conn.commit();

    GemFireCacheImpl.getInstance().getTxManager().begin(IsolationLevel.SNAPSHOT, null);

    st.execute("insert into t1 values (500,101)");
    st.execute("insert into t1 values (600,201)");


    Thread t2 = new Thread() {
      @Override
      public void run() {
        try {
          final Connection conn = getConnection();
          Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("select * from t1 union select * from t2");
          int numRows = 0;
          while (rs.next()) {
            numRows++;
          }

          System.out.println(numRows);
          // The count should be 20 as one insert was done by pausing recroding version for snapshot
          assert (numRows == 24);
          rs.close();
          conn.close();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    t2.start();
    t2.join();
    GemFireCacheImpl.getInstance().getTxManager().commit();

    ResultSet rs = st.executeQuery("select * from t1 union select * from t2");
    int numRows = 0;
    while (rs.next()) {
      numRows++;
    }
    System.out.println(numRows);
    // The count should be 26 as one insert was done by pausing recroding version for snapshot
    assert (numRows == 26);



    ResultSet rs1 = st.executeQuery("select * from t1 union select * from t2");
    int numRows1 = 0;
    while (rs1.next()) {
      numRows1++;
    }
    System.out.println(numRows1);
    // The count should be 21 after releasing the lock and re-initializing snapshot map
    assert (numRows1 == 26);

  }
}

