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

  /**
   * Creates a new <code>DistributedSQLTestBase</code> base object with the
   * given name.
   *
   * @param name
   */
  public PersistenceRecoveryOrderDUnit(String name) {
    super(name);
  }

  protected static final int MAX_WAIT = 60 * 1000;
  protected static String REGION_NAME = "region";

  @Override
  protected String reduceLogging() {
    return "fine";
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
/*    diskDir = new File("diskDir-" + getName()).getAbsoluteFile();
    com.gemstone.gemfire.internal.FileUtil.delete(diskDir);
    diskDir.mkdir();
    diskDir.deleteOnExit();*/
  }

  public void tearDown2() throws Exception {
    super.tearDown2();
  }

  public void SURtestWaitForLatestMember1() throws Exception {
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

  /**
   * Tests to make sure that a persistent region will wait
   * for any members that were online when is crashed before starting up.
   *
   * @throws Throwable
   */
  public void _testWaitForLatestMember() throws Throwable {

    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    getLogWriter().info("Creating region in VM0");
    createPersistentRegion(server1);
    getLogWriter().info("Creating region in VM1");
    createPersistentRegion(server2);

    putAnEntry(server1);

    getLogWriter().info("closing region in server1");
    //closeRegion(server1);
    stopVMNum(-1);

    updateTheEntry(server2);


    getLogWriter().info("closing region in server2");
    //closeRegion(server2);
    stopVMNum(-2);

    //This ought to wait for VM1 to come back
    getLogWriter().info("Creating region in VM0");
    restartVMNums(-1);
    AsyncInvocation future = createPersistentRegionAsync(server1);

    waitForBlockedInitialization(server1);

    assertTrue(future.isAlive());

    restartVMNums(-2);
    getLogWriter().info("Creating region in VM1");
    createPersistentRegion(server2);

    future.join(MAX_WAIT);
    if (future.isAlive()) {
      fail("Region not created within " + MAX_WAIT);
    }
    if (future.exceptionOccurred()) {
      throw new Exception(future.getException());
    }

    checkForEntry(server1);
    checkForEntry(server2);

    checkForRecoveryStat(server2, true);
    checkForRecoveryStat(server1, false);
  }

  /**
   * Tests to make sure that we stop waiting for a member
   * that we revoke.
   * @throws Throwable
   */
  public void testRevokeAMember() throws Throwable {

    startVMs(1, 2);
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

    server1.invoke(new SerializableRunnable("Check for waiting regions") {

      public void run() {
        GemFireCacheImpl cache = (GemFireCacheImpl) Misc.getGemFireCache();
        PersistentMemberManager mm = cache.getPersistentMemberManager();
        Map<String, Set<PersistentMemberID>> waitingRegions = mm.getWaitingRegions();
        assertEquals(0, waitingRegions.size());
      }
    });

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


  /*  vm2.invoke(new SerializableRunnable("Revoke the member") {

      public void run() {
        getCache();
        DistributedSystemConfig config;
        AdminDistributedSystem adminDS = null;
        try {
          config = AdminDistributedSystemFactory.defineDistributedSystem(getSystem(), "");
          adminDS = AdminDistributedSystemFactory.getDistributedSystem(config);
          adminDS.connect();
          Set<PersistentID> missingIds = adminDS.getMissingPersistentMembers();
          getLogWriter().info("waiting members=" + missingIds);
          assertEquals(1, missingIds.size());
          PersistentID missingMember = missingIds.iterator().next();
          adminDS.revokePersistentMember(
              missingMember.getUUID());
        } catch (AdminException e) {
          throw new RuntimeException(e);
        } finally {
          if(adminDS != null) {
            adminDS.disconnect();
          }
        }
      }
    });

    future.join(MAX_WAIT);
    if(future.isAlive()) {
      fail("Region not created within" + MAX_WAIT);
    }

    if(future.exceptionOccurred()) {
      throw new Exception(future.getException());
    }

    checkForRecoveryStat(vm0, true);




    //Check to make sure we recovered the old
    //value of the entry.
    SerializableRunnable checkForEntry = new SerializableRunnable("check for the entry") {

      public void run() {
        Cache cache = getCache();
        Region region = cache.getRegion(REGION_NAME);
        assertEquals("B", region.get("A"));
      }
    };
    vm0.invoke(checkForEntry);

    //Now, we should not be able to create a region
    //in vm1, because the this member was revoked
    getLogWriter().info("Creating region in VM1");
    ExpectedException e = addExpectedException(RevokedPersistentDataException.class.getSimpleName(), vm1);
    try {
      createPersistentRegion(vm1);
      fail("We should have received a split distributed system exception");
    } catch(RuntimeException expected) {
      if(!(expected.getCause() instanceof RevokedPersistentDataException)) {
        throw expected;
      }
    } finally {
      e.remove();
    }

    closeCache(vm1);
    //Restart vm0
    closeCache(vm0);
    createPersistentRegion(vm0);*/


  }


  protected void createPersistentRegion(VM vm) throws Throwable {
    _createPersistentRegion(vm, false);
  }

  private AsyncInvocation _createPersistentRegion(VM vm, boolean wait) throws Throwable {
    AsyncInvocation future = createPersistentRegionAsync(vm);
    long waitTime = wait ? 500 : MAX_WAIT;
    future.join(waitTime);
    if (future.isAlive() && !wait) {
      fail("Region not created within" + MAX_WAIT);
    }
    if (!future.isAlive() && wait) {
      fail("Did not expecte region creation to complete");
    }
    if (!wait && future.exceptionOccurred()) {
      throw new RuntimeException(future.getException());
    }
    return future;
  }

  protected AsyncInvocation createPersistentRegionAsync(final VM vm) {
    SerializableRunnable createRegion = new SerializableRunnable("Create persistent region") {
      public void run() {
        Cache cache = Misc.getGemFireCache();
        DiskStoreFactory dsf = cache.createDiskStoreFactory();
        File dir = getDiskDirForVM(vm);
        dir.mkdirs();
        dsf.setDiskDirs(new File[]{dir});
        dsf.setMaxOplogSize(1);
        DiskStore ds = dsf.create(REGION_NAME);
        RegionFactory rf = new RegionFactory();
        rf.setDiskStoreName(ds.getName());
        rf.setDiskSynchronous(true);
        rf.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(REGION_NAME);
      }
    };
    return vm.invokeAsync(createRegion);
  }

  protected File getDiskDirForVM(final VM vm) {
    File dir = new File(diskDir, String.valueOf(vm.getPid()));
    return dir;
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

  private void putAnEntry(VM vm) {
    vm.invoke(new SerializableRunnable("Put an entry") {

      public void run() {
        Cache cache = Misc.getGemFireCache();
        Region region = cache.getRegion(REGION_NAME);
        region.put("A", "B");
      }
    });
  }

  protected void updateTheEntry(VM vm1) {
    updateTheEntry(vm1, "C");
  }

  protected void updateTheEntry(VM vm1, final String value) {
    vm1.invoke(new SerializableRunnable("change the entry") {

      public void run() {
        Cache cache = Misc.getGemFireCache();
        Region region = cache.getRegion(REGION_NAME);
        region.put("A", value);
      }
    });
  }

  protected void closeRegion(final VM vm) {
    SerializableRunnable closeRegion = new SerializableRunnable("Close persistent region") {
      public void run() {
        Cache cache = Misc.getGemFireCache();
        Region region = cache.getRegion(REGION_NAME);
        region.close();
      }
    };
    vm.invoke(closeRegion);
  }

  private void checkForEntry(VM vm) {
    SerializableRunnable checkForEntry = new SerializableRunnable("check for the entry") {

      public void run() {
        Cache cache = Misc.getGemFireCache();
        Region region = cache.getRegion(REGION_NAME);
        assertEquals("C", region.get("A"));
      }
    };
    vm.invoke(checkForEntry);
  }

  private void checkForRecoveryStat(VM vm, final boolean localRecovery) {
    vm.invoke(new SerializableRunnable("check disk region stat") {

      public void run() {
        Cache cache = Misc.getGemFireCache();
        DistributedRegion region = (DistributedRegion)cache.getRegion(REGION_NAME);
        DiskRegionStats stats = region.getDiskRegion().getStats();
        if (localRecovery) {
          assertEquals(1, stats.getLocalInitializations());
          assertEquals(0, stats.getRemoteInitializations());
        } else {
          assertEquals(0, stats.getLocalInitializations());
          assertEquals(1, stats.getRemoteInitializations());
        }

      }
    });
  }
  /*public void testRevokeAHostBeforeInitialization() throws Throwable {
    startVMs(1, 3);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    Host host = Host.getHost(0);
    VM server1 = host.getVM(0);
    VM server2 = host.getVM(1);
    VM vm2 = host.getVM(2);

    getLogWriter().info("Creating region in VM0");
    createPersistentRegion(server1);
    getLogWriter().info("Creating region in VM1");
    createPersistentRegion(server2);

    putAnEntry(server1);

    server1.invoke(new SerializableRunnable("Check for waiting regions") {

      public void run() {
        GemFireCacheImpl cache = (GemFireCacheImpl) getCache();
        PersistentMemberManager mm = cache.getPersistentMemberManager();
        Map<String, Set<PersistentMemberID>> waitingRegions = mm.getWaitingRegions();
        assertEquals(0, waitingRegions.size());
      }
    });

    getLogWriter().info("closing region in server1");
    closeRegion(server1);

    updateTheEntry(server2);

    getLogWriter().info("closing region in server2");
    closeRegion(server2);

    final File dirToRevoke = getDiskDirForVM(server2);
    vm2.invoke(new SerializableRunnable("Revoke the member") {

      public void run() {
        getCache();
        DistributedSystemConfig config;
        AdminDistributedSystem adminDS = null;
        try {
          config = AdminDistributedSystemFactory.defineDistributedSystem(getSystem(), "");
          adminDS = AdminDistributedSystemFactory.getDistributedSystem(config);
          adminDS.connect();
          adminDS.revokePersistentMember(SocketCreator.getLocalHost(),
              dirToRevoke.getCanonicalPath());
        } catch(Exception e) {
          fail("Unexpected exception", e);
        } finally {
          if(adminDS != null) {
            adminDS.disconnect();
          }
        }
      }
    });

    //This shouldn't wait, because we revoked the member
    getLogWriter().info("Creating region in VM0");
    createPersistentRegion(server1);

    checkForRecoveryStat(server1, true);

    //Check to make sure we recovered the old
    //value of the entry.
    SerializableRunnable checkForEntry = new SerializableRunnable("check for the entry") {

      public void run() {
        Cache cache = getCache();
        Region region = cache.getRegion(REGION_NAME);
        assertEquals("B", region.get("A"));
      }
    };
    server1.invoke(checkForEntry);

    //Now, we should not be able to create a region
    //in server2, because the this member was revoked
    getLogWriter().info("Creating region in VM1");
    ExpectedException e = addExpectedException(RevokedPersistentDataException.class.getSimpleName(), server2);
    try {
      createPersistentRegion(server2);
      fail("We should have received a split distributed system exception");
    } catch(RuntimeException expected) {
      if(!(expected.getCause() instanceof RevokedPersistentDataException)) {
        throw expected;
      }
      //Do nothing
    } finally {
      e.remove();
    }
  }*/
}
