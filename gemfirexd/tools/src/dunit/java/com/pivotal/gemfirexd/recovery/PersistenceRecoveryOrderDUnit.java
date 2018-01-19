package com.pivotal.gemfirexd.recovery;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.admin.AdminDistributedSystem;
import com.gemstone.gemfire.admin.AdminDistributedSystemFactory;
import com.gemstone.gemfire.admin.AdminException;
import com.gemstone.gemfire.admin.DistributedSystemConfig;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.DiskStoreFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.cache.persistence.RevokedPersistentDataException;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.DiskRegionStats;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberManager;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberPattern;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;


public class PersistenceRecoveryOrderDUnit extends DistributedSQLTestBase {

  protected File diskDir;

  public PersistenceRecoveryOrderDUnit(String name) {
    super(name);
  }

  protected static final int MAX_WAIT = 60 * 1000;
  protected static String REGION_NAME = "region";

  @Override
  protected String reduceLogging() {
    return "info";
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  public void tearDown2() throws Exception {
    super.tearDown2();
  }

  public void testWaitForLatestMember1() throws Exception {
    Properties p = new Properties();
    p.setProperty("default-recovery-delay", "-1");
    p.setProperty("default-startup-recovery-delay", "-1");
    startVMs(1, 2, 0, null, p);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();
    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    st1.execute("CREATE TABLE T1 (COL1 int, COL2 int) partition by column (COL1) persistent redundancy 1 buckets 1");

    st1.execute("INSERT INTO T1 values(1,1)");

    stopVMNum(-1);
    st1.execute("INSERT INTO T1 values(2,2)");
    stopVMNum(-2);

    Thread t = new Thread(new SerializableRunnable("Create persistent table ") {

      @Override
      public void run() {
        try {
          restartVMNums(new int[]{-1}, 0, null, p);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
    t.start();
    assertTrue(t.isAlive());

    waitForBlockedInitialization(server1);
    restartVMNums(-2);
    t.join(500);
  }

  public void testWaitForLatestMember2() throws Exception {
    Properties p = new Properties();
    p.setProperty("default-recovery-delay", "-1");
    p.setProperty("default-startup-recovery-delay", "-1");
    startVMs(1, 2, 0, null, p);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();
    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    st1.execute("CREATE TABLE T1 (COL1 int, COL2 int) partition by column (COL1) persistent redundancy 1 buckets 1");

    st1.execute("INSERT INTO T1 values(1,1)");

    stopVMNum(-1);
    st1.execute("INSERT INTO T1 values(2,2)");
    stopVMNum(-2);

    Thread t = new Thread(
        new SerializableRunnable() {
          @Override
          public void run() {
            try {
              restartVMNums(new int[]{-1}, 0, null, p);
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        });
    t.start();
    assertTrue(t.isAlive());

    waitForBlockedInitialization(server1);

    server1.invoke(
        new SerializableCallable() {
          @Override
          public Object call() throws Exception {
            GemFireCacheImpl cache = Misc.getGemFireCache();
            PersistentMemberManager pmm = cache.getPersistentMemberManager();
            Set<PersistentMemberID> ids = pmm.getWaitingIds();
            pmm.unblockMemberForPattern(new PersistentMemberPattern(ids.iterator().next()));
            return null;
          }
        }
    );
    t.join();

    ResultSet rs = st1.executeQuery("select * from T1");
    int count = 0;
    while(rs.next()) {
      count++;
    }
    assertEquals(1, count);


    restartVMNums(new int[]{-2}, 0, null, p);

    rs = st1.executeQuery("select * from T1");
    count = 0;
    while(rs.next()) {
      count++;
    }
    assertEquals(1, count);

    stopVMNum(-2);
    stopVMNum(-1);

    restartVMNums(new int[]{-1}, 0, null, p);
    restartVMNums(new int[]{-2}, 0, null, p);

    rs = st1.executeQuery("select * from T1");
    count = 0;
    while(rs.next()) {
      count++;
    }
    assertEquals(1, count);
  }


  /**
   * For 2 buckets, restart one latest and one older.
   * @throws Exception
   */
  public void _testWaitForLatestMember3() throws Exception {
    startVMs(1, 3);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();
    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    VM server3 = this.serverVMs.get(1);

    st1.execute("CREATE TABLE APP.T1 (COL1 int, COL2 int) partition by column (COL1) persistent redundancy 1 buckets 2");

    st1.execute("INSERT INTO APP.T1 values(1,1)");
    st1.execute("INSERT INTO APP.T1 values(2,2)");

    // bring down the server with b1 bucket.
    // don't do redundancy recovery
    // then put extra in bucket b2.
    // bring down the server with b2
    // bring down the server with b1 and b2 buckets
    // bring up the server with b1, make it latest
    // bring up the server with b1 and b2..that server should do gii for b1
    // bring up the serve with b2 it should do gii from another one.

    SerializableCallable numB = new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        PartitionedRegion pr = (PartitionedRegion)Misc.getRegionForTable("APP.T1", true);
        return pr.getDataStore().getAllLocalBucketIds().size();
      }
    };


    int server1Bucket = (Integer)server1.invoke(numB);
    int server2Bucket = (Integer)server2.invoke(numB);
    int server3Bucket = (Integer)server3.invoke(numB);

    getLogWriter().info("Server buckets are " + server1Bucket + " " + server2Bucket + " " + server3Bucket);

    stopVMNum(-1);
    st1.execute("INSERT INTO T1 values(3,3)");
    stopVMNum(-2);

    Thread t = new Thread(
        new SerializableRunnable() {
          @Override
          public void run() {
            try {
              restartVMNums(-1);
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        });
    t.start();
    assertTrue(t.isAlive());

    waitForBlockedInitialization(server1);

    server1.invoke(
        new SerializableCallable() {
          @Override
          public Object call() throws Exception {
            GemFireCacheImpl cache = Misc.getGemFireCache();

            PersistentMemberManager pmm = cache.getPersistentMemberManager();
            Set<PersistentMemberID> ids = pmm.getWaitingIds();
            pmm.unblockMemberForPattern(new PersistentMemberPattern(ids.iterator().next()));
            return null;
          }
        }
    );
    t.join(5000);

    ResultSet rs = st1.executeQuery("select * from T1");
    int count = 0;
    while(rs.next()) {
      count++;
    }
    assertEquals(1, count);

    restartVMNums(-2);

    rs = st1.executeQuery("select * from T1");
    count = 0;
    while(rs.next()) {
      count++;
    }
    assertEquals(1, count);

    stopVMNum(-2);
    stopVMNum(-1);

    restartVMNums(-1);
    restartVMNums(-2);

    rs = st1.executeQuery("select * from T1");
    count = 0;
    while(rs.next()) {
      count++;
    }
  }

  protected void waitForBlockedInitialization(VM vm) {
    vm.invoke(new SerializableRunnable() {

      public void run() {
        waitForCriterion(new WaitCriterion() {

          public String description() {
            return "Waiting for another persistent member to come online";
          }

          public boolean done() {
            GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
            if (cache != null) {
              PersistentMemberManager mm = cache.getPersistentMemberManager();
              Map<String, Set<PersistentMemberID>> regions = mm.getWaitingRegions();
              boolean done = !regions.isEmpty();
              return done;
            } else {
              return false;
            }

          }

        }, MAX_WAIT, 100, true);
      }
    });
  }

}
