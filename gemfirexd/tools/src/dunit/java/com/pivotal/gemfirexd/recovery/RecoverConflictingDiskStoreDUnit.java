package com.pivotal.gemfirexd.recovery;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberManager;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberPattern;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class RecoverConflictingDiskStoreDUnit extends DistributedSQLTestBase {

    protected File diskDir;

    public RecoverConflictingDiskStoreDUnit(String name) {
        super(name);
    }

    protected static final int MAX_WAIT = 60 * 1000;

    @Override
    protected String reduceLogging() {
        return "config";
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    public void tearDown2() throws Exception {
        super.tearDown2();
    }

    @Override
    protected void setOtherCommonProperties(Properties props, int mcastPort,
                                            String serverGroups) {
        System.setProperty("gemfire.DISALLOW_CLUSTER_RESTART_CHECK", "true");
        System.setProperty("gemfire.IGNORE_CONFLICTING_PERSISTENT_DISK_STORES", "true");

    }

    // 2 server, 1 table with redundancy and Some insert
    // server1 down
    // more insert
    // server2 down
    // server1 up, which will wait
    // force start server1
    // some insert (conflcting data)
    // server1 down
    // server2 up, it will start without wait
    // server1 start, will fail with conflictingdiskexception in normal case
    // with the flag it will start and do GII from server1
    // conflicting data will be lost

    public void testForceStartOneServerAndIgnoreConflictingDiskStoreDataLoss() throws Exception {
        Properties p = new Properties();
        p.setProperty("default-recovery-delay", "-1");
        p.setProperty("default-startup-recovery-delay", "-1");
        startVMs(1, 2, 0, null, p);
        Properties props = new Properties();
        final Connection conn = TestUtil.getConnection(props);
        Statement st1 = conn.createStatement();
        VM server1 = this.serverVMs.get(0);
        VM server2 = this.serverVMs.get(1);


        st1.execute("DROP TABLE IF EXISTS T1");
        //st1.execute("CREATE TABLE T1 (COL1 int, COL2 int) partition by column (COL1) persistent redundancy 2 buckets 1");
        st1.execute("CREATE TABLE T1 (COL1 int, COL2 int) persistent redundancy 1 buckets 2");

        for (int i = 0; i < 10; i++) {
            st1.execute("INSERT INTO T1 values(" + i + "," + i + ")");
        }

        stopVMNum(-1);

        for (int i = 10; i < 20; i++) {
            st1.execute("INSERT INTO T1 values(" + i + "," + i + ")");
        }

        stopVMNum(-2);

        Thread t = new Thread(
                new SerializableRunnable() {
                    @Override
                    public void run() {
                        try {
                            restartVMNums(new int[]{-1}, 0, null, p);
                        } catch (Exception e) {
                            getLogWriter().info("Got exception while restarting server1");
                            e.printStackTrace();
                        }
                    }
                });
        t.start();
        assertTrue(t.isAlive());

        getLogWriter().info("Going to wait for initialization. ");
        waitForBlockedInitialization(server1);

        server1.invoke(
                new SerializableCallable() {
                    @Override
                    public Object call() throws Exception {
                        GemFireCacheImpl cache = Misc.getGemFireCache();
                        PersistentMemberManager pmm = cache.getPersistentMemberManager();
                        Map<String, Set<PersistentMemberID>> regions = pmm.getWaitingRegions();
                        getLogWriter().info("Waiting regions are " + regions);
                        Set<PersistentMemberID> ids = pmm.getWaitingIds();
                        getLogWriter().info("Waiting ids are " + ids);
                        pmm.unblockMemberForPattern(new PersistentMemberPattern(ids.iterator().next()));
                        return null;
                    }
                }
        );
        t.join();

        getLogWriter().info("After server1 restart.");

        ResultSet rs = st1.executeQuery("select * from T1");
        int count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(10, count);

        for (int i = 20; i < 30; i++) {
            st1.execute("INSERT INTO T1 values(" + i + "," + i + ")");
        }

        stopVMNum(-1);

        restartVMNums(new int[]{-2}, 0, null, p);
        getLogWriter().info("After server2 restart.");

        rs = st1.executeQuery("select * from T1");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(20, count);

        restartVMNums(new int[]{-1}, 0, null, p);

        rs = st1.executeQuery("select * from T1");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(20, count);


        stopVMNum(-2);
        stopVMNum(-1);

        restartVMNums(new int[]{-1}, 0, null, p);
        restartVMNums(new int[]{-2}, 0, null, p);

        rs = st1.executeQuery("select * from T1");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(20, count);
    }

    /**
     * Same as above test except that this has one more table which doesn't do any operation when a node is down
     * When everythign is up, it shouldn't lose any data.
     * @throws Exception
     */


    public void testUnlrelatedTableForLatestMember22() throws Exception {
        Properties p = new Properties();
        p.setProperty("default-recovery-delay", "-1");
        p.setProperty("default-startup-recovery-delay", "-1");
        startVMs(1, 2, 0, null, p);
        Properties props = new Properties();
        final Connection conn = TestUtil.getConnection(props);
        Statement st1 = conn.createStatement();
        VM server1 = this.serverVMs.get(0);
        VM server2 = this.serverVMs.get(1);


        st1.execute("DROP TABLE IF EXISTS T1");
        //st1.execute("CREATE TABLE T1 (COL1 int, COL2 int) partition by column (COL1) persistent redundancy 2 buckets 1");
        st1.execute("CREATE TABLE T1 (COL1 int, COL2 int) persistent redundancy 1 buckets 2");
        st1.execute("CREATE TABLE T2 (COL1 int, COL2 int) persistent buckets 2");

        for (int i = 0; i < 1000; i++) {
            st1.execute("INSERT INTO T2 values(" + i + "," + i + ")");
        }

        for (int i = 0; i < 10; i++) {
            st1.execute("INSERT INTO T1 values(" + i + "," + i + ")");
        }

        stopVMNum(-1);

        for (int i = 10; i < 20; i++) {
            st1.execute("INSERT INTO T1 values(" + i + "," + i + ")");
        }

        stopVMNum(-2);

        Thread t = new Thread(
                new SerializableRunnable() {
                    @Override
                    public void run() {
                        try {
                            restartVMNums(new int[]{-1}, 0, null, p);
                        } catch (Exception e) {
                            getLogWriter().info("Got exception while restarting server1");
                            e.printStackTrace();
                        }
                    }
                });
        t.start();
        assertTrue(t.isAlive());

        getLogWriter().info("Going to wait for initialization. ");
        waitForBlockedInitialization(server1);

        server1.invoke(
                new SerializableCallable() {
                    @Override
                    public Object call() throws Exception {
                        GemFireCacheImpl cache = Misc.getGemFireCache();
                        PersistentMemberManager pmm = cache.getPersistentMemberManager();
                        Thread.sleep(1000);

                        Map<String, Set<PersistentMemberID>> regions = pmm.getWaitingRegions();
                        Set<PersistentMemberID> ids = pmm.getWaitingIds();
                        Iterator<PersistentMemberID> idItr = ids.iterator();
                        while (idItr.hasNext()) {
                            regions = pmm.getWaitingRegions();
                            getLogWriter().info("Waiting regions are " + regions);
                            pmm.unblockMemberForPattern(new PersistentMemberPattern(idItr.next()));

                        }
                        return null;
                    }
                }
        );
        t.join();

        getLogWriter().info("After server1 restart.");

        ResultSet rs = st1.executeQuery("select * from T1");
        int count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(10, count);

        for (int i = 20; i < 30; i++) {
            st1.execute("INSERT INTO T1 values(" + i + "," + i + ")");
        }

        stopVMNum(-1);

        t = new Thread(
                new SerializableRunnable() {
                    @Override
                    public void run() {
                        try {
                            restartVMNums(new int[]{-2}, 0, null, p);
                        } catch (Exception e) {
                            getLogWriter().info("Got exception while restarting server1");
                            e.printStackTrace();
                        }
                    }
                });
        t.start();
        assertTrue(t.isAlive());

        getLogWriter().info("Going to wait for initialization. ");
        waitForBlockedInitialization(server2);

        server2.invoke(
                new SerializableCallable() {
                    @Override
                    public Object call() throws Exception {
                        GemFireCacheImpl cache = Misc.getGemFireCache();
                        PersistentMemberManager pmm = cache.getPersistentMemberManager();
                        Map<String, Set<PersistentMemberID>> regions = pmm.getWaitingRegions();
                        Set<PersistentMemberID> ids = pmm.getWaitingIds();
                        if (ids.size() == 0) {
                            pmm.unblockMemberForPattern(null);
                        }
                        Thread.sleep(1000);
                        ids = pmm.getWaitingIds();

                        getLogWriter().info("Waiting ids are " + ids);
                        Iterator<PersistentMemberID> idItr = ids.iterator();
                        while (idItr.hasNext())
                            pmm.unblockMemberForPattern(new PersistentMemberPattern(idItr.next()));


                        return null;
                    }
                }
        );

        t.join();

        getLogWriter().info("After server2 restart.");

        rs = st1.executeQuery("select * from T1");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(20, count);

        restartVMNums(new int[]{-1}, 0, null, p);

        rs = st1.executeQuery("select * from T1");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(20, count);

        rs = st1.executeQuery("select * from T2");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(1000, count);


        stopVMNum(-2);
        stopVMNum(-1);


        Thread t1 = new Thread(
                new SerializableRunnable() {
                    @Override
                    public void run() {
                        try {
                            restartVMNums(new int[]{-1}, 0, null, p);
                        } catch (Exception e) {
                            getLogWriter().info("Got exception while restarting server1");
                            e.printStackTrace();
                        }
                    }
                });
        t1.start();
        assertTrue(t1.isAlive());

        Thread t2 = new Thread(
                new SerializableRunnable() {
                    @Override
                    public void run() {
                        try {
                            restartVMNums(new int[]{-2}, 0, null, p);
                        } catch (Exception e) {
                            getLogWriter().info("Got exception while restarting server1");
                            e.printStackTrace();
                        }
                    }
                });
        t2.start();
        assertTrue(t2.isAlive());

        t1.join();
        t2.join();

        rs = st1.executeQuery("select * from T1");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(20, count);

        rs = st1.executeQuery("select * from T2");
        count = 0;
        while (rs.next()) {
            getLogWriter().info("The value is " + rs.getInt(1) + " " + rs.getInt(2));
            count++;
        }
        assertEquals(1000, count);
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
