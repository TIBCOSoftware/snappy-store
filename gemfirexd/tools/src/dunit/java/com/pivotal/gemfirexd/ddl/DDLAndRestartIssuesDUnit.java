package com.pivotal.gemfirexd.ddl;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

public class DDLAndRestartIssuesDUnit extends DistributedSQLTestBase {

  public DDLAndRestartIssuesDUnit(String name) {
    super(name);
  }

  public void testNoNewDDLCreationWhenMemberStarting() throws Exception {

    getLogWriter().info("Starting one client and two servers");
    Properties extraProps = new Properties();
    extraProps.setProperty(GfxdConstants.MAX_LOCKWAIT, "60000");
    startVMs(1, 2, 0, null, extraProps);

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    getLogWriter().info("Inserting data");

    st.execute("create table APP.T1(id int primary key, name varchar(20), address varchar(50)) persistent replicate");
    st.execute("insert into APP.T1 values(1, 'name1', 'addr1')");
    st.execute("insert into APP.T1 values(2, 'name2', 'addr2')");
    st.execute("insert into APP.T1 values(3, 'name3', 'addr3')");
    st.execute("insert into APP.T1 values(4, 'name4', 'addr4')");
    st.execute("create index APP.IDX1 on APP.T1(name)");
    getLogWriter().info("Stopping second server");

    VM vm = getServerVM(2);

    stopVMNums(-2);

    // Some more inserts
    st.execute("insert into APP.T1 values(9, 'name9', 'addr9')");
    st.execute("insert into APP.T1 values(10, 'name10', 'addr10')");
    st.execute("create index APP.IDX2 on APP.T1(id)");
    getLogWriter().info("Installing latch on second server");

    vm.invoke(new SerializableRunnable("") {
      @Override
      public void run() throws CacheException {
        getLogWriter().info("KN: setting the test latches");
        DistributedRegion.testLatch = new CountDownLatch(1);
        DistributedRegion.testRegionName = "T1";
      }
    });

    //setPropertyIfAbsent(props, GfxdConstants.MAX_LOCKWAIT, "60000");
    getLogWriter().info("Restarting second server async");

    AsyncVM asyncVM = restartServerVMAsync(2, 0, null, extraProps);

    getLogWriter().info("Waiting until the test latch point arrives");
    waitForCriterion(new WaitCriterion() {
      @Override
      public boolean done() {
        VM serverVM = getServerVM(2);
        return Boolean.TRUE.equals(serverVM.invoke(ConcurrencyChecksDUnit.class,
            "testLatchWaitingGIIComplete"));
      }

      @Override
      public String description() {
        return "waiting for applyAllSuspects to finish";
      }
    }, 180000, 500, true);

    getLogWriter().info("Doing in-flight inserts");

    // Fire more inserts
    st.execute("insert into APP.T1 values(5, 'name5', 'addr5')");
    st.execute("insert into APP.T1 values(6, 'name6', 'addr6')");
    st.execute("insert into APP.T1 values(7, 'name7', 'addr7')");
    st.execute("insert into APP.T1 values(8, 'name8', 'addr8')");

    conn = TestUtil.getConnection();
    st = conn.createStatement();
    try {
      st.execute("drop index APP.IDX1");
      //junit.framework.Assert.fail("The ddl should have timed out");
      assertFalse("The ddl should have timed out", true);
    }
    catch (SQLException sqle) {
      assertTrue("40XL1".equalsIgnoreCase(sqle.getSQLState()));
      getLogWriter().info("Got exception: " + sqle.getSQLState(), sqle);
    }
    getLogWriter().info("Releasing the test latch");

    // Release the latch so that the GII finishes.
    this.getServerVM(2).invoke(new SerializableRunnable("") {
      @Override
      public void run() throws CacheException {
        DistributedRegion.testLatch.countDown();
        DistributedRegion.testLatch = null;
        DistributedRegion.testLatchWaiting = false;
        DistributedRegion.testRegionName = null;
      }
    });

    getLogWriter().info("Waiting for the second server to restart completely");

    joinVM(true, asyncVM);

    // fire the same ddl as above and it should pass.
    st.execute("drop index APP.IDX1");
  }

//  /drop index
  //:q!

}
