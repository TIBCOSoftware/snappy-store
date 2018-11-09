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

package com.pivotal.gemfirexd.ddl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InitialImageOperation;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PrimaryBucketException;
import com.gemstone.gemfire.internal.cache.TombstoneService;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.message.BitSetSet;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.jdbc.Bugs3Test;
import com.pivotal.gemfirexd.tools.internal.JarTools;
import com.pivotal.gemfirexd.tools.internal.MiscTools;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derbyTesting.junit.JDBC;
import org.apache.tools.ant.DirectoryScanner;
import udtexamples.UDTPrice;

/**
 * Note: Moved these concurrency check related tests to ConcurrencyChecksDUnit
 * testSuspects(), testSuspects_REPLICATED_PERSISTENT(), testBug48315(),
 * testBug47945_1(), testBug47945_2(), testBugFKCheckReplicatePartition_44004()
 */
@SuppressWarnings("serial")
public class BugsDUnit extends DistributedSQLTestBase {

  private final String myjar = TestUtil.getResourcesDir()
      + "/lib/myjar.jar";

  private final String myfalsejar = TestUtil.getResourcesDir()
      + "/lib/myfalsejar.jar";

  private final String booksJar = TestUtil.getResourcesDir()
      + "/lib/USECASE1DAP.jar";
  
  public BugsDUnit(String name) {
    super(name);
  }

  @Override
  public String reduceLogging() {
    return "config";
  }

  public static class CacheCloser extends GemFireXDQueryObserverAdapter {
    public void invokeCacheCloseAtMultipleInsert() {
      throw new CacheClosedException("just for testing");
    }
    public boolean isCacheClosedForTesting() {
      return true;
    }
  }
  
  public static void setUpCacheCloser() {
    CacheCloser cacheCloser = new CacheCloser();
    GemFireXDQueryObserverHolder.setInstance(cacheCloser);
  }
  
  public static void unssetCacheCloser() {
    CacheCloser closer = GemFireXDQueryObserverHolder.getObserver(CacheCloser.class);
    if (closer != null) {
      assertTrue(GemFireXDQueryObserverHolder.removeObserver(CacheCloser.class));
    }
  }

  static class GIIExceptionThrower implements InitialImageOperation.TEST_GII_EXCEPTION {
    int after = 0;
    int cnt = 0;
    public GIIExceptionThrower(int after) {
      this.after = after;
    }
    
    public void throwException() {
      getGlobalLogger().info("GIIExceptionThrower.throwExceptionDuringGII called", new Exception());
      if (cnt == after) {
        getGlobalLogger().info("GIIExceptionThrower.throwExceptionDuringGII actually throwing exception");
        throw new RuntimeException();
      }
      cnt++;
    }
  }

  public static void setGiiExceptionSimulator(int after) {
    getGlobalLogger().info("setting Gii exception thrower");
    if (after > 0) {
      InitialImageOperation.giiExceptionSimulate = new GIIExceptionThrower(
          after);
    }
    else {
      InitialImageOperation.giiExceptionSimulate = null;
    }
  }

  public void testIndex() throws Exception {
    Properties props = new Properties();
    props.setProperty("log-level", "config");
    startVMs(1, 3, 0, null, props);
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("CREATE TABLE app.t1 (c1 int not null, c2 int not null) persistent asynchronous");

    PreparedStatement ps = conn.prepareStatement("insert into app.t1 values (?, ?)");
    for (int i = 0; i < 2300000; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.addBatch();
      if ((i + 1) % 1000 == 0) {
        int[] cnts = ps.executeBatch();
        assertEquals(1000, cnts.length);
        for (int cnt : cnts) {
          assertEquals(1, cnt);
        }
      }
    }
    st.execute("ALTER TABLE app.t1 ADD OPP_ID int");
    long start = System.currentTimeMillis();
    getLogWriter().info("Create index start");
    st.execute("create index i1 on app.t1(OPP_ID)");
    long diff = System.currentTimeMillis() - start;
    getLogWriter().info("Create index end");
    // It takes less than 3 sec to complete the index creation.
    // But for parallel mode keeping some tolerance so
    // asserting that the diff should be less than 20 seconds.
    assertTrue(diff < 20000);
  }

  /**
   * This test should fail as is due to non-colocated columns but does not.
   * Keeping test as is till #51134 is open against this.
   */
  public void testBug51039_51746() throws Throwable {
    reduceLogLevelForTest("config");

    final Properties bprops = new Properties();
    bprops.setProperty("auth-provider", "BUILTIN");
    bprops.setProperty("gemfirexd.user.admin", "admin");
    bprops.setProperty("user", "admin");
    bprops.setProperty("password", "admin");
    // increase the DDL lock timeout to ensure it does hang for long
    bprops.setProperty(GfxdConstants.MAX_LOCKWAIT, "1000000000");
    startVMs(1, 3, 0, null, bprops);

    // start network servers
    final int netPort = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);
    startNetworkServer(3, null, null);

    final Properties sysProps = new Properties();
    sysProps.setProperty("user", "admin");
    sysProps.setProperty("password", "admin");
    final Properties props = new Properties();
    props.setProperty("user", "app");
    props.setProperty("password", "app");

    Connection sysConn = TestUtil.getConnection(sysProps);
    Statement sysSt = sysConn.createStatement();
    // define app user
    sysSt.execute("call sys.create_user('app', 'app')");

    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("CREATE TABLE APP.PERSON ("
        + "CLNT_OBJ_ID VARCHAR(100) NOT NULL,"
        + "PERS_OBJ_ID VARCHAR(100) NOT NULL) "
        + "PARTITION BY COLUMN (CLNT_OBJ_ID,PERS_OBJ_ID) BUCKETS 163");
    // import some data
    String importDataFile = TestUtil.getResourcesDir()
        + "/lib/PERSON.dat";
    sysSt.execute("call syscs_util.import_data_ex('APP', 'PERSON', null,null, '"
        + importDataFile + "', ',', NULL, NULL, 0, 0, 8, 0, null, null)");

    try {
      final Throwable[] failure = new Throwable[1];
      // start three threads in background doing expensive query, then DDL, then
      // a new connection to simulate hang reported in #51039
      Thread queryThr = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            // TODO: no cancel support for peer connections yet
            //Connection conn = TestUtil.getConnection(props);
            Connection conn = TestUtil.getNetConnection(netPort, null, props);
            Statement st = conn.createStatement();
            try {
              st.executeQuery("select count(*) from app.person p1, "
                  + "app.person p2, app.person p3 "
                  + "where p1.clnt_obj_id=p2.clnt_obj_id or "
                  + "p2.clnt_obj_id=p3.clnt_obj_id or "
                  + "p1.pers_obj_id=p3.pers_obj_id");
              fail("expected to fail with statement cancelled error");
            } catch (SQLException sqle) {
              if (!"XCL56".equals(sqle.getSQLState())) {
                throw sqle;
              }
            }
            conn.close();
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
      });
      Thread queryNetThr = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn = TestUtil.getNetConnection(netPort, null, props);
            Statement st = conn.createStatement();
            try {
              st.executeQuery("select count(*) from app.person p1, "
                  + "app.person p2, app.person p3 "
                  + "where p1.clnt_obj_id=p2.clnt_obj_id or "
                  + "p2.clnt_obj_id=p3.clnt_obj_id or "
                  + "p1.pers_obj_id=p3.pers_obj_id");
              fail("expected to fail with statement cancelled error");
            } catch (SQLException sqle) {
              if (!"XCL56".equals(sqle.getSQLState())) {
                throw sqle;
              }
            }
            conn.close();
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
      });
      Thread ddlThr = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn = TestUtil.getConnection(props);
            Statement st = conn.createStatement();
            st.execute("truncate table app.person");
            conn.close();
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
      });
      Thread ddlNetThr = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn = TestUtil.getNetConnection(netPort, null, props);
            Statement st = conn.createStatement();
            st.execute("truncate table app.person");
            conn.close();
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
      });
      Thread checkThr = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn = TestUtil.getConnection(props);
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("select count(*) from app.person");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
            assertFalse(rs.next());
            conn.close();
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
      });
      Thread checkNetThr = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn = TestUtil.getNetConnection(netPort, null, props);
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("select count(*) from app.person");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
            assertFalse(rs.next());
            conn.close();
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
      });

      queryThr.start();
      queryNetThr.start();
      Thread.sleep(3000);
      ddlThr.start();
      ddlNetThr.start();
      Thread.sleep(3000);
      checkThr.start();
      checkNetThr.start();
      Thread.sleep(1000);

      // verify that all threads have indeed gotten stuck
      assertTrue(queryThr.isAlive());
      assertTrue(queryNetThr.isAlive());
      assertTrue(ddlThr.isAlive());
      assertTrue(ddlNetThr.isAlive());
      assertTrue(checkThr.isAlive());
      assertTrue(checkNetThr.isAlive());

      // now cancel using a "skip-locks=true" connection
      Properties skipLocksProps = new Properties();
      skipLocksProps.setProperty(Attribute.SKIP_LOCKS, "true");
      skipLocksProps.setProperty("user", "app");
      skipLocksProps.setProperty("password", "app");
      Connection connC, netConnC;

      final String skipUrl = TestUtil.getProtocol();
      final String skipNetUrl = TestUtil.getNetProtocol("localhost", netPort);
      // check that it is disallowed for normal user
      try {
        connC = DriverManager.getConnection(skipUrl, skipLocksProps);
        fail("expected auth exception expected admin user");
      } catch (SQLException sqle) {
        if (!"08004".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      try {
        netConnC = DriverManager.getConnection(skipNetUrl, skipLocksProps);
        fail("expected auth exception expected admin user");
      } catch (SQLException sqle) {
        if (!"08004".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      // should succeed with admin credentials
      skipLocksProps.setProperty("user", "admin");
      skipLocksProps.setProperty("password", "admin");
      connC = DriverManager.getConnection(skipUrl, skipLocksProps);
      netConnC = DriverManager.getConnection(skipNetUrl, skipLocksProps);

      Statement netStmtC = netConnC.createStatement();
      ResultSet netrsC = netStmtC.executeQuery("select current_statement_uuid "
          + "from sys.sessions where current_statement like "
          + "'%from app.person p1%'");
      assertTrue(netrsC.next());
      String cancelId = netrsC.getString(1);
      netrsC.close();
      netStmtC.execute("call sys.cancel_statement('" + cancelId + "')");

      Thread.sleep(2000);
      Statement stmtC = connC.createStatement();
      ResultSet rsC = stmtC.executeQuery("select current_statement_uuid "
          + "from sys.sessions where current_statement like "
          + "'%from app.person p1%'");
      Statement stmtC2 = connC.createStatement();
      assertTrue(rsC.next());
      do {
        stmtC2.execute("call sys.cancel_statement('" + rsC.getString(1)
            + "')");
      } while (rsC.next());

      connC.close();
      netConnC.close();

      // verify that all threads have successfully terminated
      queryThr.join();
      queryNetThr.join();
      ddlThr.join();
      ddlNetThr.join();
      checkThr.join();
      checkNetThr.join();
      if (failure[0] != null) {
        throw failure[0];
      }

      // check if everything is running fine now
      sysSt.execute("call syscs_util.import_data_ex('APP','PERSON',null,null,'"
          + importDataFile + "', ',', NULL, NULL, 0, 0, 8, 0, null, null)");
      ResultSet rs = st.executeQuery("select count(*) from app.person");
      assertTrue(rs.next());
      assertEquals(10000, rs.getInt(1));
      assertFalse(rs.next());

      // drop the explicitly created app user
      sysSt.execute("call sys.drop_user('app')");

      sysConn.close();
      conn.close();
    } catch (Throwable t) {
      getLogWriter().error("failed with exception", t);
      throw t;
    }
  }

  public void testBug50207_replicated() throws Exception {
    try {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfire.GetInitialImage.TRACE_GII_FINER", "true" });
      startVMs(1, 3);
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
      st.execute("create table test (c1 int not null, c2 int not null) replicate persistent");
      PreparedStatement ps = conn
          .prepareStatement("insert into test values(?, ?)");
      for (int i = 0; i < 10; i++) {
        ps.setInt(1, i);
        ps.setInt(2, i);
        ps.executeUpdate();
      }
      stopVMNum(-3);
      for (int i = 10; i < 20; i++) {
        ps.setInt(1, i);
        ps.setInt(2, i);
        ps.executeUpdate();
      }
      VM serverVM3 = serverVMs.get(2);
      serverVM3.invoke(BugsDUnit.class, "setGiiExceptionSimulator",
          new Object[] { Integer.valueOf(5) });
      long start = 0;
      try {
        start = System.currentTimeMillis();
        restartServerVMNums(new int[] { 3 }, 0, null, null);
        fail("Test should not have proceeded");
      } catch (Throwable t) {
        getLogWriter().info("exception during startup", t);
        long end = System.currentTimeMillis();
        long gap = end - start;
        if (gap > 60000) {
          fail("Gap of greater than a minute. Something fishy");
        }
      }
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfire.GetInitialImage.TRACE_GII_FINER", "false" });
      VM serverVM3 = serverVMs.get(2);
      // 0 will indicate that nullify the test hook
      serverVM3.invoke(BugsDUnit.class, "setGiiExceptionSimulator",
          new Object[] { Integer.valueOf(0) });
    }
  }

  public void testBug50207_partitioned() throws Exception {
    try {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfire.GetInitialImage.TRACE_GII_FINER", "true" });

      startVMs(1, 2);
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
      st.execute("create table test (c1 int not null, c2 int not null) buckets 1 redundancy 1 persistent");
      PreparedStatement ps = conn
          .prepareStatement("insert into test values(?, ?)");
      for (int i = 0; i < 10; i++) {
        ps.setInt(1, i);
        ps.setInt(2, i);
        ps.executeUpdate();
      }
      stopVMNum(-2);
      for (int i = 10; i < 20; i++) {
        ps.setInt(1, i);
        ps.setInt(2, i);
        ps.executeUpdate();
      }
      VM serverVM2 = serverVMs.get(1);
      serverVM2.invoke(BugsDUnit.class, "setGiiExceptionSimulator",
          new Object[] { Integer.valueOf(5) });
      long start = 0;
      try {
        start = System.currentTimeMillis();
        restartServerVMNums(new int[] { 2 }, 0, null, null);
        fail("Test should not have proceeded");
      } catch (Throwable t) {
        getLogWriter().info("exception during startup", t);
        long end = System.currentTimeMillis();
        long gap = end - start;
        if (gap > 60000) {
          fail("Gap of greater than a minute. Something fishy");
        }
      }
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { "gemfire.GetInitialImage.TRACE_GII_FINER", "false" });
      VM serverVM3 = serverVMs.get(1);
      // 0 will indicate that nullify the test hook
      serverVM3.invoke(BugsDUnit.class, "setGiiExceptionSimulator",
          new Object[] { Integer.valueOf(0) });
    }
  }

  public void testBug48343() throws Exception {
    startVMs(1, 2);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("create table trade.customers"
        + "(id int not null primary key, name varchar(20) not null, "
        + "addr varchar(20) not null) partition by primary key persistent redundancy 1");
    st.execute("insert into trade.customers values(1, 'one', 'addr_1'), (2, 'two', 'addr_2')");
    stopVMNums(-2);
    st.execute("delete from trade.customers where id = 2");
    st.execute("insert into trade.customers values(3, 'three', 'addr_3')");
    st.execute("delete from trade.customers where id = 3");
    restartVMNums(-2);
    st.execute("call sys.rebalance_all_buckets();");
    stopVMNums(-1);
    st.execute("select * from trade.customers");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertFalse(rs.next());
    st.execute("select count(*) from trade.customers");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertFalse(rs.next());
  }

  public void testBug48335() throws Exception {
    reduceLogLevelForTest("config");

    // Start a client and some server VMs
    startVMs(0, 3, 0, "sync", null);
    startVMs(1, 0);

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    // start derby server
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    final int dbPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    NetworkServerControl netServer = DBSynchronizerTestBase
        .startNetworkServer(dbPort);
    final String derbyDbUrl = "jdbc:derby://localhost:" + dbPort + "/newDB3;";
    Connection dbConn = DriverManager.getConnection(derbyDbUrl + "create=true");
    Statement dbSt = dbConn.createStatement();

    final String partClause =
        "partition by COLUMN ( name ) REDUNDANCY 2 PERSISTENT";
    final String replClause = "replicate PERSISTENT";
    final String createDBSync = "create asynceventlistener T_3_SYNC("
        + "listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
        + "initparams  'org.apache.derby.jdbc.ClientDriver," + derbyDbUrl
        + "' ENABLEPERSISTENCE true MANUALSTART false ALERTTHRESHOLD 240000) "
        + "SERVER GROUPS(sync)";
    final String createDBSync2 = "create asynceventlistener T_3_SYNC("
        + "listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
        + "initparams  'org.apache.derby.jdbc.ClientDriver," + derbyDbUrl
        + "' ENABLEPERSISTENCE true MANUALSTART true ALERTTHRESHOLD 240000) "
        + "SERVER GROUPS(sync)";
    try {
      st.execute("CREATE TABLE t.t_3_1_1(name varchar(16) primary key, "
          + "remark varchar(20)) " + partClause);
      st.execute("CREATE TABLE t.t_3_1_2(name varchar(16) primary key, "
          + "remark varchar(20)) " + replClause);
      st.execute(createDBSync2);
      st.execute("CREATE INDEX t_remark_idx on t.t_3_1_1(remark)");
      st.execute("CREATE INDEX t_remark_idx2 on t.t_3_1_2(remark)");
      dbSt.execute("CREATE TABLE t.t_3_1_1(name varchar(16) primary key, "
          + "remark varchar(20))");
      dbSt.execute("CREATE TABLE t.t_3_1_2(name varchar(16) primary key, "
          + "remark varchar(20))");
      st.execute("call sys.start_async_event_listener('T_3_SYNC')");
      st.execute("ALTER TABLE t.t_3_1_1 set asynceventlistener (t_3_sync)");
      st.execute("ALTER TABLE t.t_3_1_2 set asynceventlistener (t_3_sync)");
      PreparedStatement ps = conn
          .prepareStatement("insert into t.t_3_1_1 values (?, ?)");
      PreparedStatement ps12 = conn
          .prepareStatement("insert into t.t_3_1_2 values (?, ?)");

      final int num = 10000;
      String key;
      for (int i = 1; i <= num; i++) {
        key = "N" + i;
        ps.setString(1, key);
        ps.setString(2, "Remark" + i);
        ps.addBatch();
        ps12.setString(1, key);
        ps12.setString(2, "Remark" + i);
        ps12.addBatch();
        if ((i % 100) == 0) {
          int[] res = ps.executeBatch();
          assertEquals(100, res.length);
          res = ps12.executeBatch();
          assertEquals(100, res.length);
        }
      }

      // now stop a couple of servers, drop the tables, and recreate the tables
      addExpectedException(null, new int[] { 1, 2, 3 }, new Object[] {
          SQLNonTransientConnectionException.class, CacheClosedException.class,
          RegionDestroyedException.class });

      stopVMNums(-1, -2);
      st.execute("drop table t.t_3_1_1");
      st.execute("drop table t.t_3_1_2");
      st.execute("drop asynceventlistener t_3_sync");
      dbSt.execute("drop table t.t_3_1_1");
      dbSt.execute("drop table t.t_3_1_2");
      restartVMNums(new int[] { -1 }, 0,  "sync", null);
      int netPort = startNetworkServer(1, null, null);
      Connection conn2 = TestUtil.getNetConnection(netPort, null, null);
      Statement st2 = conn2.createStatement();
      // below should not hang waiting for server2 since table has been dropped
      st2.execute("CREATE TABLE t.t_3_1_1(name varchar(16) primary key, "
          + "remark varchar(20)) " + partClause);
      st.execute("CREATE TABLE t.t_3_1_2(name varchar(16) primary key, "
          + "remark varchar(20)) " + replClause);
      st2.execute(createDBSync);
      st.execute("ALTER TABLE t.t_3_1_1 set asynceventlistener (t_3_sync)");
      st.execute("ALTER TABLE t.t_3_1_2 set asynceventlistener (t_3_sync)");
      st.execute("CREATE INDEX t_remark_idx on t.t_3_1_1(remark)");
      st2.execute("CREATE INDEX t_remark_idx2 on t.t_3_1_2(remark)");
      dbSt.execute("CREATE TABLE t.t_3_1_1(name varchar(16) primary key, "
          + "remark varchar(20))");
      dbSt.execute("CREATE TABLE t.t_3_1_2(name varchar(16) primary key, "
          + "remark varchar(20))");
      PreparedStatement ps2 = conn2
          .prepareStatement("insert into t.t_3_1_1 values (?, ?)");
      PreparedStatement ps22 = conn2
          .prepareStatement("insert into t.t_3_1_2 values (?, ?)");
      for (int i = 1; i <= num; i++) {
        key = "N" + i;
        ps2.setString(1, key);
        ps2.setString(2, "Remark" + i);
        ps2.addBatch();
        ps22.setString(1, key);
        ps22.setString(2, "Remark" + i);
        ps22.addBatch();
        if ((i % 100) == 0) {
          int[] res = ps2.executeBatch();
          assertEquals(100, res.length);
          res = ps22.executeBatch();
          assertEquals(100, res.length);
        }
      }
      st.execute("alter table t.t_3_1_1 set asynceventlistener ()");
      st.execute("alter table t.t_3_1_2 set asynceventlistener ()");
      st2.execute("drop asynceventlistener t_3_sync");
      dbSt.execute("drop table t.t_3_1_1");
      dbSt.execute("drop table t.t_3_1_2");
      st2.execute("drop table t.t_3_1_1");
      st.execute("drop table t.t_3_1_2");
      st.execute("CREATE TABLE t.t_3_1_1(name varchar(16) primary key, "
          + "remark varchar(20)) " + partClause);
      st2.execute("CREATE TABLE t.t_3_1_2(name varchar(16) primary key, "
          + "remark varchar(20)) " + replClause);
      st2.execute("CREATE INDEX t_remark_idx on t.t_3_1_1(remark)");
      st.execute("CREATE INDEX t_remark_idx2 on t.t_3_1_2(remark)");
      dbSt.execute("CREATE TABLE t.t_3_1_1(name varchar(16) primary key, "
          + "remark varchar(20))");
      dbSt.execute("CREATE TABLE t.t_3_1_2(name varchar(16) primary key, "
          + "remark varchar(20))");
      st2.execute(createDBSync2);
      st.execute("ALTER TABLE t.t_3_1_1 set asynceventlistener (t_3_sync)");
      st.execute("ALTER TABLE t.t_3_1_2 set asynceventlistener (t_3_sync)");
      st.execute("call sys.start_async_event_listener('T_3_SYNC')");

      // now try on the other server too when table has been recreated
      // but the persistent data should not be reused
      restartVMNums(new int[] { -2 }, 0,  "sync", null);
      int netPort2 = startNetworkServer(2, null, null);
      Connection conn3 = TestUtil.getNetConnection(netPort2, null, null);

      addExpectedException(null, new int[] { 1, 2, 3 }, new Object[] {
          SQLNonTransientConnectionException.class, CacheClosedException.class,
          RegionDestroyedException.class });

      // stop sync to derby here to check for queue persistence
      netServer.shutdown();
      while (true) {
        Thread.sleep(500);
        try {
          netServer.ping();
        }
        catch (Exception e) {
          break;
        }
      }
      PreparedStatement ps3 = conn3
          .prepareStatement("insert into t.t_3_1_1 values (?, ?)");
      PreparedStatement ps32 = conn3
          .prepareStatement("insert into t.t_3_1_2 values (?, ?)");
      THashSet keys = new THashSet(num);
      final Set<Object> allKeys;
      for (int i = 1; i <= num; i++) {
        key = "N" + i;
        ps3.setString(1, key);
        ps3.setString(2, "Remark" + i);
        ps3.addBatch();
        ps32.setString(1, key);
        ps32.setString(2, "Remark" + i);
        ps32.addBatch();
        keys.add(key);
        if ((i % 100) == 0) {
          int[] res = ps3.executeBatch();
          assertEquals(100, res.length);
          res = ps32.executeBatch();
          assertEquals(100, res.length);
        }
      }
      allKeys = Collections.unmodifiableSet(new THashSet((Collection)keys));

      ResultSet rs = st.executeQuery("select * from t.t_3_1_1");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = st.executeQuery("select * from t.t_3_1_1 where remark > 'Remark'");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = st.executeQuery("select * from t.t_3_1_2");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = st.executeQuery("select * from t.t_3_1_2 where remark > 'Remark'");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      // check after full restart
      stopVMNums(-1, -3);
      st.execute("call sys.rebalance_all_buckets()");
      stopVMNums(-2);

      // start sync to derby here to check for queue persistence
      netServer.start(SanityManager.GET_DEBUG_STREAM());
      while (true) {
        Thread.sleep(500);
        try {
          netServer.ping();
          break;
        }
        catch (Exception e) {
        }
      }
      restartVMNums(new int[] { -2 }, 0, "sync", null);
      restartVMNums(-1, -3);

      removeExpectedException(null, new int[] { 1, 2, 3 }, new Object[] {
          SQLNonTransientConnectionException.class, CacheClosedException.class,
          RegionDestroyedException.class });

      keys.addAll(allKeys);
      rs = st.executeQuery("select * from t.t_3_1_1");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = st.executeQuery("select * from t.t_3_1_1 where remark > 'Remark'");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = st.executeQuery("select * from t.t_3_1_2");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = st.executeQuery("select * from t.t_3_1_2 where remark > 'Remark'");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      // also check in derby synced via DBSynchronizer
      st.execute("call sys.wait_for_sender_queue_flush('t_3_sync', 1, 0)");

      try {
        dbConn.close();
      } catch (Exception e) {
        // ignored
      }
      dbConn = DriverManager.getConnection(derbyDbUrl);
      dbSt = dbConn.createStatement();

      keys.addAll(allKeys);
      rs = dbSt.executeQuery("select * from t.t_3_1_1");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = dbSt.executeQuery("select * from t.t_3_1_1 where remark > 'Remark'");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = dbSt.executeQuery("select * from t.t_3_1_2");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      keys.addAll(allKeys);
      rs = dbSt.executeQuery("select * from t.t_3_1_2 where remark > 'Remark'");
      while (rs.next()) {
        key = rs.getString(1);
        assertTrue(keys.remove(key));
      }
      assertEquals(0, keys.size());

      st.execute("drop table t.t_3_1_1");
      st.execute("drop table t.t_3_1_2");
      dbSt.execute("drop table t.t_3_1_1");
      dbSt.execute("drop table t.t_3_1_2");

      dbConn.close();
    } finally {
      try {
        netServer.shutdown();
      } catch (Exception e) {
      }
    }
  }

  public void testInsertFailoverbug_47407() throws Exception {
    // System.setProperty("snappydata.client.traceLevel", "");
    // System.setProperty("snappydata.client.traceDirectory",
    // getSysDirName(getGemFireDescription()));
    // final Properties extraProps = new Properties();
    // System.setProperty("gemfirexd.debug.true", "TraceSingleHop,TraceClientHA");
    // System.setProperty("gemfirexd.debug.false", "");
    // SanityManager.TraceClientHA = true;
    // SanityManager.TraceSingleHop = true;
    try {
      startVMs(0, 2, 0, null, null/*extraProps*/);
      final int netPort = startNetworkServer(1, null, null);
      startNetworkServer(2, null, null);
      startNetworkServer(3, null, null);
      VM vm1 = getServerVM(1);
      vm1.invoke(BugsDUnit.class, "setUpCacheCloser");
      VM vm2 = getServerVM(2);
      vm2.invoke(BugsDUnit.class, "setUpCacheCloser");
      VM vm3 = getServerVM(3);
      vm3.invoke(BugsDUnit.class, "setUpCacheCloser");

//      final Properties connProps = new Properties();
//      connProps.setProperty("gemfirexd.debug.true",
//          "TraceSingleHop,TraceClientHA");

      //String url = TestUtil.getNetProtocol(localHost.getHostName(), netPort);
      // Connection connClient = DriverManager.getConnection(url,
      // TestUtil.getNetProperties(connProps));
      Connection connClient = TestUtil.getNetConnection(
          "localhost", netPort, null, null);
      connClient.createStatement().execute("create schema trade");
      Statement s = connClient.createStatement();
      s.execute("set current schema trade");
      s.execute("create table trade.customers(cid int not null, "
          + "cust_name varchar(100), addr varchar(100), tid int, primary key (cid))");
      PreparedStatement psInsert = connClient
          .prepareStatement("insert into trade.customers values(?, ?, ?, ?)");
      for (int i = 0; i < 2; i++) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setString(3, "addr" + i);
        psInsert.setInt(4, i);
        psInsert.addBatch();
      }
      try {
        int[] res = psInsert.executeBatch();
        getLogWriter().info(
            "KN: result of batch insert: " + res + " length: " + res.length
                + " 1st element: " + (res.length > 0 ? res[0] : "nothing"));
        fail("insert should not get past this point");
      } catch (Exception ex) {
        getLogWriter().info("got exception", ex);
      }
    } finally {
      VM vm1 = getServerVM(1);
      vm1.invoke(BugsDUnit.class, "unssetCacheCloser");
      VM vm2 = getServerVM(2);
      vm2.invoke(BugsDUnit.class, "unssetCacheCloser");
      VM vm3 = getServerVM(3);
      vm3.invoke(BugsDUnit.class, "unssetCacheCloser");
    }
  }

  public void testBug47148() throws Exception {
    Properties p = new Properties();
    // start a network server
    int netPort = startNetworkServer(1, null, p);

    final Connection conn = TestUtil.getNetConnection(netPort, null, null);
    boolean secondServerStarted = false;
    try {

      Statement s = conn.createStatement();
      s.execute("Create Table TEST_TABLE(idx numeric(12),"
          + "AccountID varchar(10)," + "OrderNo varchar(20),"
          + "primary key(idx)" + ")" + "PARTITION BY COLUMN ( AccountID )");

      s.execute("CREATE INDEX idx_AccountID ON test_Table (AccountID ASC)");
      PreparedStatement insps = conn
          .prepareStatement("insert into test_table values(?,?,?)");

      insps.setInt(1, 9);
      insps.setString(2, "8");
      insps.setString(3, "8");
      insps.executeUpdate();

      String query = "select accountid from test_table where accountid like '8'";
      ResultSet rs = conn.createStatement().executeQuery(query);
      int num = 0;
      while (rs.next()) {
        num++;
        assertEquals("8", rs.getString(1));
      }
      assertEquals(1, num);
      rs.close();
    } finally {
      stopNetworkServer(1);
    }
  }

  /**
   * There is also a junit for #46584
   * Because client driver and embedded driver
   * might behave differently for the same
   * user input string (varchar/char)
   */
  public void testBug46584() throws Exception {

    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    String url = TestUtil.getNetProtocol("localhost", netPort1);
    Connection conn = DriverManager.getConnection(url);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, exchange varchar(10) not null, constraint sec_pk primary key (sec_id), constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) replicate");
    st.execute("create view trade.securities_vw (sec_id, symbol, exchange) as select sec_id, symbol, exchange from trade.securities");
    st.execute("insert into trade.securities values (1, 'VMW', 'nye')");
    st.execute("select length(symbol) from trade.securities_vw where symbol = 'VMW'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));

    st.execute("select length(symbol) from trade.securities_vw where symbol = 'VMW '");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));

    st.execute("insert into trade.securities values (2, 'EMC ', 'nye')");
    st.execute("select length(symbol) from trade.securities_vw where symbol = 'EMC'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(4, rs.getInt(1));

    st.execute("select length(symbol) from trade.securities_vw where symbol = 'EMC '");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(4, rs.getInt(1));
  }

  public void testDAPBugsFromUseCase1() throws Exception {
    // start some servers
    startVMs(0, 2, 0, null, null);
    // Start network server on the VMs
    final int netPort = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    Connection conn = null;

    String url = TestUtil.getNetProtocol("localhost", netPort);
    conn = DriverManager.getConnection(url,
        TestUtil.getNetProperties(new Properties()));
    Statement s = conn.createStatement();
    s.execute("CREATE TABLE APP.BOOKS(ID VARCHAR(10) NOT NULL, "
        + "NAME VARCHAR(25), TAG VARCHAR(25), SIZE BIGINT, LOCATION VARCHAR(25), "
        + "CONSTRAINT BOOK_PK PRIMARY KEY (ID)) PARTITION BY PRIMARY KEY "
        + "REDUNDANCY 1 EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW PERSISTENT ASYNCHRONOUS");

    s.execute("insert into APP.BOOKS values ('1', 'Spring1','TAG1',20,'Mumbai')");
    s.execute("insert into APP.BOOKS values ('2', 'Spring2','TAG2',10,'Pune')");
    s.execute("insert into APP.BOOKS values ('3', 'Spring3','TAG3',15,'Bangalore')");
    s.execute("insert into APP.BOOKS values ('4', 'Spring4','TAG4',20,'Chennai')");
    s.execute("insert into APP.BOOKS values ('5', 'Spring5','TAG5',10,'calcutta')");
    s.execute("insert into APP.BOOKS values ('6', 'Spring6','TAG6',15,'Bhubaneswar')");
    s.execute("insert into APP.BOOKS values ('7', 'Spring7','TAG7',20,'Delhi')");
    s.execute("insert into APP.BOOKS values ('8', 'Spring8','TAG8',10,'Cuttack')");
    s.execute("insert into APP.BOOKS values ('9', 'Spring9','TAG9',15,'Balangir')");
    s.execute("insert into APP.BOOKS values ('10', 'Spring10','TAG10',20,'Baleswar')");
    s.execute("insert into APP.BOOKS values ('11', 'Spring11','TAG11',10,'Phulbani')");
    s.execute("insert into APP.BOOKS values ('12', 'Spring12','TAG12',15,'Purushoottampur')");
    s.execute("insert into APP.BOOKS values ('13', 'Spring13','TAG13',20,'Puri')");
    s.execute("insert into APP.BOOKS values ('14', 'Spring14','TAG14',10,'Pipili')");
    s.execute("insert into APP.BOOKS values ('15', 'Spring15','TAG15',15,'Keondujhar')");
    s.execute("insert into APP.BOOKS values ('16', 'Spring16','TAG16',20,'Sundargarh')");
    s.execute("insert into APP.BOOKS values ('17', 'Spring17','TAG17',10,'Baripada')");
    s.execute("insert into APP.BOOKS values ('18', 'Spring18','TAG18',15,'Paradip')");
    s.execute("insert into APP.BOOKS values ('19', 'Spring19','TAG19',20,'Parlakhemundi')");
    s.execute("insert into APP.BOOKS values ('20', 'Spring20','TAG20',10,'Gunpur')");
    s.execute("insert into APP.BOOKS values ('21', 'Spring21','TAG21',15,'Aska')");

    s.execute("CALL SQLJ.INSTALL_JAR('" + booksJar
        + "', 'APP.GFXD_LISTBOOKSP', 0);");

    s.execute("CREATE PROCEDURE LIST_BOOKS(IN ID VARCHAR(100) ) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'com.pivotal.vfabric.booksdb.storedproc.ListBooksStoreProc.execute'");

    CallableStatement stmt = conn.prepareCall("CALL LIST_BOOKS(?)");
    System.out
        .println("1.--------- CALL LIST_BOOKS(?): with valid ID : ID=10 ------");
    getLogWriter()
        .info(
            "expecting single valid value for 10 ... no extension ... local execution");
    callStoredProc(stmt, "10", true, false);

    getLogWriter()
        .info(
            "expecting no valid value for 111 ... no extension ... local execution");
    callStoredProc(stmt, "111", false, false);

    CallableStatement stmtOnAll = conn
        .prepareCall("{CALL LIST_BOOKS(?) ON ALL}");

    getLogWriter().info(
        "expecting duplicate valid value for 10 ... on all extension");
    callStoredProc(stmtOnAll, "10", true, true);

    getLogWriter()
        .info("expecting no valid value for 111 ... on all extension");
    callStoredProc(stmtOnAll, "111", false, false);

    CallableStatement stmtWithDefaultRP = conn
        .prepareCall("{CALL LIST_BOOKS(?) WITH RESULT PROCESSOR "
            + "com.pivotal.gemfirexd.internal.engine.procedure.coordinate.DefaultProcedureResultProcessor}");

    getLogWriter()
        .info(
            "expecting single valid value for 10 ... only default processor ... local execution");
    callStoredProc(stmtWithDefaultRP, "10", true, false);

    getLogWriter()
        .info(
            "expecting no valid value for 111 ... only default processor ... local execution");
    callStoredProc(stmtWithDefaultRP, "111", false, false);

    CallableStatement stmtWithCustomRP = conn
        .prepareCall("{CALL LIST_BOOKS(?) WITH RESULT PROCESSOR "
            + "com.pivotal.vfabric.booksdb.storedproc.ListBookResultProcessor}");

    getLogWriter()
        .info(
            "expecting single valid value for 10 ... only custom processor ... local execution");
    callStoredProc(stmtWithCustomRP, "10", true, false);

    getLogWriter()
        .info(
            "expecting no valid value for 111 ... only custom processor ... local execution");
    callStoredProc(stmtWithCustomRP, "111", false, false);

    s.execute("create alias myprocessor for 'com.pivotal.vfabric.booksdb.storedproc.ListBookResultProcessor'");

    CallableStatement stmtWithAliasRP = conn
        .prepareCall("{CALL LIST_BOOKS(?) WITH RESULT PROCESSOR myprocessor}");

    getLogWriter()
        .info(
            "expecting single valid value for 10 ... only default processor(alias) ... local execution");
    callStoredProc(stmtWithAliasRP, "10", true, false);

    getLogWriter()
        .info(
            "expecting no valid value for 111 ... only default processor(alias) ... local execution");
    callStoredProc(stmtWithAliasRP, "111", false, false);

    CallableStatement stmtWithCustomRPAndOnALL = conn
        .prepareCall("{CALL LIST_BOOKS(?) WITH RESULT PROCESSOR "
            + "com.pivotal.vfabric.booksdb.storedproc.ListBookResultProcessor ON ALL}");

    getLogWriter()
        .info(
            "expecting duplicate valid value for 10 ... only custom processor ... local execution");
    callStoredProc(stmtWithCustomRPAndOnALL, "10", true, true);
  }

  public void testBucketIdNotFoundUseCase1_47210() throws Exception {
    // start some servers
    startVMs(0, 2, 0, null, null);
    // Start network server on the VMs
    int netPort = startNetworkServer(1, null, null);
    // startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    Connection conn = null;

    String url = TestUtil.getNetProtocol("localhost", netPort);
    conn = DriverManager.getConnection(url, new Properties());
    Statement s = conn.createStatement();

    s.execute("create table Parent_1 (txn_id varchar(26) not null, "
        + "dummy1 int, dummy2 smallint, "
        + "constraint prnt_pk primary key(txn_id)) "
        + " partition by primary key redundancy 1 "
        + "eviction by lruheappercent evictaction overflow "
        + "persistent asynchronous");

    s.execute("create table Parent_Hist (ts timestamp with "
        + "default current_timestamp, txn_id varchar(26) not null, "
        + "dummy1 int, dummy2 smallint) "
        + " partition by column (txn_id) colocate with (Parent_1) "
        + "redundancy 1 eviction by lruheappercent evictaction overflow "
        + "persistent asynchronous");

    s.execute("insert into parent_1 values ('one', 1, 1), ('two', 2,2), "
        + "('three', 3,3)");

    s.execute("insert into parent_hist (txn_id, dummy1, dummy2) values "
        + "('one', 1, 1), ('two', 2,2), ('three', 3,3)");

    s.execute("create table Parent_RAW (txn_id varchar(26) not null, "
        + "dummy1 int, dummy2 smallint, "
        + "constraint prnt_raw_pk primary key(txn_id)) "
        + " partition by primary key colocate with (parent_1) "
        + "redundancy 1");

    s.execute("create table Parent_RAW2 (txn_id varchar(26) not null, "
        + "dummy1 int, dummy2 smallint, "
        + "constraint prnt_raw_pk2 primary key(txn_id)) "
        + " partition by primary key colocate with (parent_1) "
        + "redundancy 1 eviction by lruheappercent evictaction overflow "
        + "persistent asynchronous");

    stopVMNums(-1, -2);
    restartVMNums(-1, -2);

    netPort = startNetworkServer(1, null, null);
    // startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    conn = null;

    url = TestUtil.getNetProtocol("localhost", netPort);
    conn = DriverManager.getConnection(url, (new Properties()));
    s = conn.createStatement();

    PreparedStatement pstmt = conn.prepareStatement("delete from parent_raw "
        + "where txn_id in (select txn_id from parent_1 where dummy2 > ?)");
    int rowsAffected = s.executeUpdate("delete from parent_raw where txn_id "
        + "in (select txn_id from parent_1 where dummy2 > 0)");
    assertEquals(0, rowsAffected);
    pstmt.setInt(1, 0);
    rowsAffected = pstmt.executeUpdate();
    assertEquals(0, rowsAffected);

    s.execute("insert into Parent_RAW values ('one', 1, 1)");
    rowsAffected = s.executeUpdate("delete from parent_raw where txn_id "
        + "in (select txn_id from parent_1 where dummy2 > 0)");
    assertEquals(1, rowsAffected);
    s.execute("insert into Parent_RAW values ('one', 1, 1)");
    rowsAffected = pstmt.executeUpdate();
    assertEquals(1, rowsAffected);

    // same for colocated persistent table next
    pstmt = conn.prepareStatement("delete from parent_raw2 "
        + "where txn_id in (select txn_id from parent_1 where dummy2 > ?)");
    rowsAffected = s.executeUpdate("delete from parent_raw2 where txn_id "
        + "in (select txn_id from parent_1 where dummy2 > 0)");
    assertEquals(0, rowsAffected);
    pstmt.setInt(1, 0);
    rowsAffected = pstmt.executeUpdate();
    assertEquals(0, rowsAffected);

    s.execute("insert into Parent_RAW2 values ('one', 1, 1)");
    rowsAffected = s.executeUpdate("delete from parent_raw2 where txn_id "
        + "in (select txn_id from parent_1 where dummy2 > 0)");
    assertEquals(1, rowsAffected);
    s.execute("insert into Parent_RAW2 values ('one', 1, 1)");
    rowsAffected = pstmt.executeUpdate();
    assertEquals(1, rowsAffected);
  }

  private static void callStoredProc(CallableStatement stmt, String id,
      boolean resultExpected, boolean dupsExpected) throws Exception {
    getGlobalLogger().info(
        "callStoredProc called with id: " + ", resultExpected: "
            + resultExpected + ", dupsExpected: " + dupsExpected);
    int count = 0;
    stmt.setString(1, id);
    stmt.execute();
    ResultSet rs = stmt.getResultSet();
    while (rs.next()) {
      count++;
    }
    if (resultExpected) {
      assertTrue(count > 0);
      getGlobalLogger().info(
          "callStoredProc called with id: "
              + ", resultExpected assertion through with count: " + count);
    }
    if (dupsExpected) {
      assertEquals(2, count);
      getGlobalLogger().info(
          "callStoredProc called with id: "
              + ", dupsExpected assertion through with count: " + count);
    }
  }

  public void testBug43115() throws Exception {
    startVMs(1, 2);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("create table test_table (id int primary key, val int) partition by (id) redundancy 1");
    PreparedStatement ps = conn
        .prepareStatement("insert into test_table values (?, ?)");
    for (int i = 0; i < 10; i++) {
      ps.setInt(1, i);
      ps.setInt(2, 100 + i);
      ps.executeUpdate();
    }
    st.execute("insert into test_table values (10, 100)");
    st.execute("insert into test_table values (11, 100)");
    ResultSet rs = st
        .executeQuery("select * from test_table where id in (0, 10, 11)");
    int count = 0;
    while (rs.next()) {
      count++;
      System.out.println("#43115 result=" + rs.getInt(1) + "\t" + rs.getInt(2));
    }
    assertEquals(3, count);
  }

  public void testBug43290_precisionProblemWithDecimals() throws Exception {
    startVMs(1, 2);
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 (balance decimal(12,2))");
    s.execute("insert into t1 values(0.0)");
    s.execute("update t1 set balance = 5559.09");
    s.execute("update t1 set balance = balance + 1596.15");
    s.execute("select balance from t1");
    ResultSet rs = s.getResultSet();

    assertTrue(rs.next());
    assertEquals(7155.24, rs.getDouble(1));
  }

  /*
   * Move this test to GfxdJarInstallationDUnit
   * once the locator can be stopped within this test;
   * Locators in two tests in the same DUnit may have
   * issues showing up in error.grep even DUnit passes
   */
  public void testGfxdJarCommands() throws Exception {
    // disabled due to bug #51556
    if (isTransactional) {
      return;
    }
    final Properties locatorProps = new Properties();
    setMasterCommonProperties(locatorProps);
    String locatorBindAddress = "localhost";
    int locatorPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    _startNewLocator(this.getClass().getName(), getName(), locatorBindAddress,
        locatorPort, null, locatorProps);
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    TestUtil.startNetServer(netPort, null);

    // Start network server on the VMs
    final Properties serverProps = new Properties();
    serverProps.setProperty("locators", locatorBindAddress + '[' + locatorPort
        + ']');
    startVMs(0, 1, 0, null, serverProps);
    startNetworkServer(1, null, null);

    Connection conn = TestUtil.getNetConnection(locatorBindAddress,  netPort,  null, new Properties());

    Statement st = conn.createStatement();

    String sql = null;
    CallableStatement mergeCS = null;

    // try with both procedures and "gfxd install-jar/replace-jar" tool

    JarTools.main(new String[] { "install-jar", "-file=" + myjar,
        "-name=app.sample1", "-client-port=" + netPort,
        "-client-bind-address=" + locatorBindAddress });

    String ddl = "create table EMP.TESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)"
        + " REDUNDANCY 1";

    st.execute(ddl);

    st.execute("INSERT INTO EMP.TESTTABLE VALUES (2, 2, '3')");
    st.execute("INSERT INTO EMP.TESTTABLE VALUES (3, 3, '3')");
    st.execute("INSERT INTO EMP.TESTTABLE VALUES (2, 2, '4')");

    st.execute("CREATE PROCEDURE MergeSort () "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA "
        + "READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

    st.execute("CREATE ALIAS MergeSortProcessor FOR "
        + "'myexamples.MergeSortProcessor'");

    sql = "CALL MergeSort() WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.TESTTABLE WHERE 1=1";

    if (mergeCS == null) {
      mergeCS = conn.prepareCall(sql);
    }
    mergeCS.execute();
    checkMergeResults(mergeCS);

    // add a new server, then stop the old server
    startVMs(0, 1, 0, null, serverProps);
    startNetworkServer(2, null, null);
    // recover all buckets first
    st.execute("call sys.rebalance_all_buckets()");
    stopVMNums(-1);

    // call procedure again
    mergeCS.execute();
    checkMergeResults(mergeCS);

    // replace jar

    JarTools.main(new String[] { "replace-jar", "-file=" + myfalsejar,
        "-name=app.sample1", "-client-port=" + netPort,
        "-client-bind-address=" + locatorBindAddress });

    try {
      // call procedure
      mergeCS.execute();
      fail("Should throw 42X51: The class 'myexamples.MergeSortProcessor' "
          + "does not exist or is inaccessible");
    } catch (SQLException sqle) {
      if (!"42X51".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // restart the old VM and stop the new VM
    restartVMNums(new int[] { -1 }, 0, null , serverProps);
    startNetworkServer(1, null, null);
    // recover all buckets first
    st.execute("call sys.rebalance_all_buckets()");
    stopVMNums(-2);

    try {
      // call procedure
      mergeCS.execute();
      fail("Should throw 42X51: The class 'myexamples.MergeSortProcessor' "
          + "does not exist or is inaccessible");
    } catch (SQLException sqle) {
      if (!"42X51".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // replace jar
    JarTools.main(new String[] { "replace-jar", "-file=" + myjar,
        "-name=app.sample1", "-client-port=" + netPort,
        "-client-bind-address=" + locatorBindAddress });

    // call procedure
    mergeCS = conn.prepareCall(sql);
    mergeCS.execute();
    checkMergeResults(mergeCS);

    st.execute("drop alias MergeSortProcessor");
    st.execute("drop procedure MergeSort");
    st.execute("drop table EMP.TESTTABLE");
    JarTools.main(new String[] { "remove-jar", "-name=APP.sample1",
        "-client-port=" + netPort, "-client-bind-address="
            + locatorBindAddress });
  }

  public void testBug46843_1() throws Exception {
    startVMs(1, 4);
    final List<Throwable> exceptions = new ArrayList<Throwable>();
    Statement stmt = null;
    final String query = "select sid, count(*) from txhistory "
        + "where cid>? and sid<?  GROUP BY sid HAVING count(*) >=1";
    Connection conn = null;
    Statement derbyStmt = null;
    String table = "create table txhistory(cid int,  sid int, qty int)";
    String index = "create index txhistory_sid on txhistory(sid)";
    try {

      String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        tempDerbyUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      final String derbyDbUrl = tempDerbyUrl;
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt.execute(table);
      derbyStmt.execute(index);
      Statement derbyStmt1 = derbyConn.createStatement();
      conn = TestUtil.getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      stmt = conn.createStatement();
      stmt.execute(table + "  replicate");
      stmt.execute(index);
      // We create a table...
      PreparedStatement ps_insert_derby = derbyConn
          .prepareStatement("insert into txhistory values(?,?,?)");
      PreparedStatement ps_insert_gfxd = conn
          .prepareStatement("insert into txhistory values(?,?,?)");

      ps_insert_derby.setInt(1, 1);
      ps_insert_derby.setInt(2, 1);
      ps_insert_derby.setInt(3, 1 * 1 * 100);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 1);
      ps_insert_gfxd.setInt(2, 1);
      ps_insert_gfxd.setInt(3, 1 * 1 * 100);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 11);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 1 * 3 * 100);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 11);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 1 * 3 * 100);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 22);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 2 * 3 * 100);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 22);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 2 * 3 * 100);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 3);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 3 * 3 * 100);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 3);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 3 * 3 * 100);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 6);
      ps_insert_derby.setInt(2, 4);
      ps_insert_derby.setInt(3, 6 * 4 * 100);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 6);
      ps_insert_gfxd.setInt(2, 4);
      ps_insert_gfxd.setInt(3, 6 * 4 * 100);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 12);
      ps_insert_derby.setInt(2, 4);
      ps_insert_derby.setInt(3, 12 * 4 * 100);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 12);
      ps_insert_gfxd.setInt(2, 4);
      ps_insert_gfxd.setInt(3, 12 * 4 * 100);
      ps_insert_gfxd.executeUpdate();

      Runnable runnable = new Runnable() {
        @Override
        public void run() {
          try {

            Connection gfxdConn = TestUtil.getConnection();
            Connection derbyConn = DriverManager.getConnection(derbyDbUrl);

            PreparedStatement derbyStmt = derbyConn.prepareStatement(query);

            // Creating a statement object that we can use for
            // running various
            // SQL statements commands against the database.
            PreparedStatement stmt = gfxdConn.prepareStatement(query);
            //Random random = new Random();
            // while (true) {
            int cid = 10;
            int sid = 100;
            stmt.setInt(1, cid);
            stmt.setInt(2, sid);

            derbyStmt.setInt(1, cid);
            derbyStmt.setInt(2, sid);

            TestUtil.validateResults(derbyStmt, stmt, query, false);
          } catch (Throwable th) {
            exceptions.add(th);
          }

        }
      };
      runnable.run();
      if (!exceptions.isEmpty()) {
        for (Throwable e : exceptions) {
          e.printStackTrace();

        }
        fail(exceptions.toString());
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail("Test failed because of exception " + e);
    } finally {
      if (derbyStmt != null) {
        try {
          derbyStmt.execute("drop table txhistory");
        } catch (Exception e) {
          // ignore intentionally
        }

      }
    }
  }

  public void testBug46843_2() throws Exception {

    startVMs(1, 4);
    final List<Throwable> exceptions = new ArrayList<Throwable>();
    Statement stmt = null;
    final String query = "select sid, count(*) from txhistory  where cid>? "
        + "and sid<?  GROUP BY sid HAVING count(*) >=1";
    Connection conn = null;
    Statement derbyStmt = null;
    String table = "create table txhistory(cid int,  sid int, qty int, type varchar(10))";
    String index = "create index txhistory_sid on txhistory(sid,cid)";
    try {

      String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        tempDerbyUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      final String derbyDbUrl = tempDerbyUrl;
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt.execute(table);
      derbyStmt.execute(index);
      Statement derbyStmt1 = derbyConn.createStatement();
      conn = TestUtil.getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      stmt = conn.createStatement();
      stmt.execute(table + "  replicate");
      stmt.execute(index);
      // We create a table...
      PreparedStatement ps_insert_derby = derbyConn
          .prepareStatement("insert into txhistory  values(?,?,?,?)");
      PreparedStatement ps_insert_gfxd = conn
          .prepareStatement("insert into txhistory values(?,?,?,?)");

      ps_insert_derby.setInt(1, 1);
      ps_insert_derby.setInt(2, 1);
      ps_insert_derby.setInt(3, 1 * 1 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 1);
      ps_insert_gfxd.setInt(2, 1);
      ps_insert_gfxd.setInt(3, 1 * 1 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 11);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 1 * 3 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 11);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 1 * 3 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 11);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 1 * 3 * 100);
      ps_insert_derby.setString(4, "buy");
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 11);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 1 * 3 * 100);
      ps_insert_gfxd.setString(4, "buy");
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 11);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 1 * 3 * 100);
      ps_insert_derby.setString(4, "buy");
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 11);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 1 * 3 * 100);
      ps_insert_gfxd.setString(4, "buy");
      ps_insert_gfxd.executeUpdate();


      ps_insert_derby.setInt(1, 22);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 2 * 3 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 22);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 2 * 3 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 3);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 3 * 3 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 3);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 3 * 3 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 6);
      ps_insert_derby.setInt(2, 4);
      ps_insert_derby.setInt(3, 6 * 4 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 6);
      ps_insert_gfxd.setInt(2, 4);
      ps_insert_gfxd.setInt(3, 6 * 4 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 12);
      ps_insert_derby.setInt(2, 4);
      ps_insert_derby.setInt(3, 12 * 4 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 12);
      ps_insert_gfxd.setInt(2, 4);
      ps_insert_gfxd.setInt(3, 12 * 4 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      Runnable runnable = new Runnable() {
        @Override
        public void run() {
          try {

            Connection gfxdConn = TestUtil.getConnection();
            Connection derbyConn = DriverManager.getConnection(derbyDbUrl);

            PreparedStatement derbyStmt = derbyConn.prepareStatement(query);

            // Creating a statement object that we can use for
            // running various
            // SQL statements commands against the database.
            PreparedStatement stmt = gfxdConn.prepareStatement(query);
            //Random random = new Random();
            int cid = 10;
            int sid = 100;
            stmt.setInt(1, cid);
            stmt.setInt(2, sid);

            derbyStmt.setInt(1, cid);
            derbyStmt.setInt(2, sid);

            TestUtil.validateResults(derbyStmt, stmt, query, false);

            String query2 = "select cid, sum(qty) as amount from txhistory " +
                "where sid = ?  GROUP BY cid, type ORDER BY amount";
            stmt = gfxdConn.prepareStatement(query2);
            derbyStmt = derbyConn.prepareStatement(query2);
            stmt.setInt(1, 3);
            derbyStmt.setInt(1, 3);

            TestUtil.validateResults(derbyStmt, stmt, query2, false);
          } catch (Throwable th) {
            exceptions.add(th);
          }

        }
      };
      runnable.run();
      if (!exceptions.isEmpty()) {
        for (Throwable e : exceptions) {
          e.printStackTrace();

        }
        fail(exceptions.toString());
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail("Test failed because of exception " + e);
    } finally {
      if (derbyStmt != null) {
        try {
          derbyStmt.execute("drop table txhistory");
        } catch (Exception e) {
          // ignore intentionally
        }

      }
    }
  }

  public void testBug46843_3() throws Exception {

    startVMs(1, 4);
    final List<Throwable> exceptions = new ArrayList<Throwable>();
    Statement stmt1 = null;
    final String query = "select sid, count(*) from txhistory  where cid>? "
        + "and sid<?  GROUP BY sid HAVING count(*) >=1";
    Connection conn = null;
    Statement derbyStmt1 = null;
    String table = "create table txhistory(cid int,  sid int, qty int, type varchar(10))";
    String index = "create index txhistory_cid on txhistory(cid)";
    try {

      String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        tempDerbyUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      final String derbyDbUrl = tempDerbyUrl;
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt1 = derbyConn.createStatement();
      derbyStmt1.execute(table);
      derbyStmt1.execute(index);

      conn = TestUtil.getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      stmt1 = conn.createStatement();
      stmt1.execute(table + "  ");
      stmt1.execute(index);
      // We create a table...
      PreparedStatement ps_insert_derby = derbyConn
          .prepareStatement("insert into txhistory  values(?,?,?,?)");
      PreparedStatement ps_insert_gfxd = conn
          .prepareStatement("insert into txhistory values(?,?,?,?)");

      ps_insert_derby.setInt(1, 1);
      ps_insert_derby.setInt(2, 1);
      ps_insert_derby.setInt(3, 1 * 1 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 1);
      ps_insert_gfxd.setInt(2, 1);
      ps_insert_gfxd.setInt(3, 1 * 1 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 11);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 1 * 3 * 100);
      ps_insert_derby.setString(4, "sell");
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 11);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 1 * 3 * 100);
      ps_insert_gfxd.setString(4, "sell");
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 11);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 1 * 3 * 100);
      ps_insert_derby.setString(4, "buy");
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 11);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 1 * 3 * 100);
      ps_insert_gfxd.setString(4, "buy");
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 11);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 1 * 3 * 100);
      ps_insert_derby.setString(4, "buy");
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 11);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 1 * 3 * 100);
      ps_insert_gfxd.setString(4, "buy");
      ps_insert_gfxd.executeUpdate();


      ps_insert_derby.setInt(1, 22);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 2 * 3 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 22);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 2 * 3 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 3);
      ps_insert_derby.setInt(2, 3);
      ps_insert_derby.setInt(3, 3 * 3 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 3);
      ps_insert_gfxd.setInt(2, 3);
      ps_insert_gfxd.setInt(3, 3 * 3 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 6);
      ps_insert_derby.setInt(2, 4);
      ps_insert_derby.setInt(3, 6 * 4 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 6);
      ps_insert_gfxd.setInt(2, 4);
      ps_insert_gfxd.setInt(3, 6 * 4 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 12);
      ps_insert_derby.setInt(2, 4);
      ps_insert_derby.setInt(3, 12 * 4 * 100);
      ps_insert_derby.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 12);
      ps_insert_gfxd.setInt(2, 4);
      ps_insert_gfxd.setInt(3, 12 * 4 * 100);
      ps_insert_gfxd.setNull(4, java.sql.Types.VARCHAR);
      ps_insert_gfxd.executeUpdate();

      {
          try {

            Connection gfxdConn = TestUtil.getConnection();


            PreparedStatement derbyStmt = derbyConn.prepareStatement(query);

            // Creating a statement object that we can use for
            // running various
            // SQL statements commands against the database.
            PreparedStatement stmt = gfxdConn.prepareStatement(query);
            //Random random = new Random();
            // while (true) {
            int cid = 10;
            int sid = 100;
            stmt.setInt(1, cid);
            stmt.setInt(2, sid);

            derbyStmt.setInt(1, cid);
            derbyStmt.setInt(2, sid);

            TestUtil.validateResults(derbyStmt, stmt, query, false);
            SerializableRunnable csr = new SerializableRunnable("set observer") {

              @Override
              public void run() {
                GemFireXDQueryObserver old = GemFireXDQueryObserverHolder
                    .setInstance(new GemFireXDQueryObserverAdapter() {
                      @Override
                      public double overrideDerbyOptimizerCostForMemHeapScan(
                          GemFireContainer gfContainer, double optimzerEvalutatedCost) {
                        return Double.MAX_VALUE;
                      }

                      @Override
                      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                          OpenMemIndex memIndex, double optimzerEvalutatedCost) {
                        return 1;
                      }

                    });
              }
            };

            serverExecute(4, csr);

            String query2 = "select cid, sum(qty) as amount from txhistory  where sid = ?  GROUP BY cid, type ORDER BY amount";
            stmt = gfxdConn.prepareStatement(query2);
            derbyStmt = derbyConn.prepareStatement(query2);
            stmt.setInt(1, 3);
            derbyStmt.setInt(1, 3);

            TestUtil.validateResults(derbyStmt, stmt, query2, false);
          } catch (Throwable th) {
            exceptions.add(th);
          }
      }
      if (!exceptions.isEmpty()) {
        for (Throwable e : exceptions) {
          e.printStackTrace();

        }
        fail(exceptions.toString());
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail("Test failed because of exception " + e);
    } finally {
      if (derbyStmt1 != null) {
        try {
          derbyStmt1.execute("drop table txhistory");
        } catch (Exception e) {
          // ignore intentionally
        }

      }
    }
  }


  public void testBug46803_1() throws Exception {
    startVMs(1, 4);
    PreparedStatement psInsert1, psInsert4 = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = TestUtil.getConnection();

    // Creating a statement object that we can use for running various
    // SQL statements commands against the database.
    s = conn.createStatement();

    // We create a table...

    s.execute(" create table securities (sec_id int not null, "
        + "symbol varchar(10) not null,"
        + "exchange varchar(10) not null, tid int, "
        + "constraint sec_pk primary key (sec_id),"
        + " constraint sec_uq unique (symbol, exchange), constraint"
        + " exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse',"
        + " 'fse', 'hkse', 'tse'))) ");
    s.execute(" create table companies (symbol varchar(10) not null, "
        + " exchange varchar(10) not null,  companyname char(100), "
        + " tid int, constraint comp_pk primary key (symbol, exchange) ,"
        + "constraint comp_fk foreign key (symbol, exchange) "
        + "references securities (symbol, exchange) on delete restrict)");

    // and add a few rows...
    psInsert4 = conn
        .prepareStatement("insert into securities values (?, ?, ?,?)");
    for (int i = 1; i < 6; ++i) {
      psInsert4.setInt(1, i);
      psInsert4.setString(2, "symbol" + i);
      psInsert4.setString(3, "lse");
      psInsert4.setInt(4, 1);
      assertEquals(1, psInsert4.executeUpdate());
    }

    psInsert1 = conn
        .prepareStatement("insert into companies values (?, ?,?,?)");
    for (int i = 1; i < 6; ++i) {
      psInsert1.setString(1, "symbol" + i);
      psInsert1.setString(2, "lse");
      psInsert1.setString(3, "name" + i);
      psInsert1.setInt(4, 1);
      assertEquals(1, psInsert1.executeUpdate());
    }
    int n = s.executeUpdate("update securities set tid=7  where sec_id = 1");
    assertEquals(1, n);
    try {
      n = s.executeUpdate("update securities set symbol = 'random1', "
          + "exchange = 'amex' where sec_id = 1");
      fail("Should have thrown constraint violation exception");
    } catch (SQLException expected) {
      if (!expected.getSQLState().equals("23503")) {
        expected.printStackTrace();
        fail("unexpected sql state for sql exception =" + expected);
      }
    }

    try {
      s.executeUpdate("update securities set symbol = 'random1' , "
          + "exchange = 'amex' where sec_id = 2");
      fail("Should have thrown constraint violation exception");
    } catch (SQLException expected) {
      if (!expected.getSQLState().equals("23503")) {
        expected.printStackTrace();
        fail("unexpected sql state for sql exception =" + expected);
      }
    }
    try {
      s.executeUpdate("update securities set symbol = 'random1' , "
          + "exchange = 'amex' where sec_id = 3");
      fail("Should have thrown constraint violation exception");
    } catch (SQLException expected) {
      if (!expected.getSQLState().equals("23503")) {
        expected.printStackTrace();
        fail("unexpected sql state for sql exception =" + expected);
      }
    }

    try {
      s.executeUpdate("update securities set  exchange = 'amex' , "
          + "symbol = 'random1'  where sec_id = 4");
      fail("Should have thrown constraint violation exception");
    } catch (SQLException expected) {
      if (!expected.getSQLState().equals("23503")) {
        expected.printStackTrace();
        fail("unexpected sql state for sql exception =" + expected);
      }
    }

    try {
      s.executeUpdate("update securities set exchange = 'amex' "
          + " where sec_id = 1");
      fail("Should have thrown constraint violation exception");
    } catch (SQLException expected) {
      // expected.printStackTrace();
      if (!expected.getSQLState().equals("23503")) {
        expected.printStackTrace();
        fail("unexpected sql state for sql exception =" + expected);
      }
    }
    try {
      s.executeUpdate("update securities set symbol = 'random1' "
          + " where sec_id = 1");
      fail("Should have thrown constraint violation exception");
    } catch (SQLException expected) {
      // expected.printStackTrace();
      if (!expected.getSQLState().equals("23503")) {
        expected.printStackTrace();
        fail("unexpected sql state for sql exception =" + expected);
      }
    }

    n = s.executeUpdate("update securities set symbol = 'symbol1' "
        + " where sec_id = 1");
    assertEquals(1, n);
    rs = s.executeQuery("select * from securities where symbol = 'random1'");
    assertFalse(rs.next());

  }

  public void testBug46803_2() throws Exception {

    startVMs(1, 4);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    ArrayList<Object> statements = new ArrayList<Object>();
    // PreparedStatements
    PreparedStatement psInsert1, psInsert2, psInsert4 = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = null;
    try {
      conn = TestUtil.getConnection();
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      s = conn.createStatement();
      statements.add(s);

      // We create a table...

      s.execute(" create table securities (sec_id int not null,"
          + " symbol varchar(10) not null,"
          + "exchange varchar(10) not null, tid int,"
          + " constraint sec_pk primary key (sec_id),"
          + " constraint sec_uq unique (symbol, exchange), constraint"
          + " exc_ch check (exchange in ('nasdaq', 'nye', 'amex',"
          + " 'lse', 'fse', 'hkse', 'tse'))) ");
      s.execute(" create table companies (symbol varchar(10) not null, "
          + " exchange varchar(10) not null,  companyname char(100), "
          + " tid int, constraint comp_pk primary key (symbol, exchange) ,"
          + "constraint comp_fk foreign key (symbol, exchange) "
          + "references securities (symbol, exchange) on delete restrict)");

      s.execute("create table buyorders(oid int not null"
          + " constraint buyorders_pk primary key,"
          + " sid int,  tid int, "
          + " constraint bo_sec_fk foreign key (sid) references securities (sec_id) "
          + " on delete restrict)");

      // and add a few rows...
      psInsert4 = conn
          .prepareStatement("insert into securities values (?, ?, ?,?)");
      statements.add(psInsert4);
      for (int i = 1; i < 10; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i);
        psInsert4.setString(3, "lse");
        psInsert4.setInt(4, 1);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into companies values (?, ?,?,?)");
      statements.add(psInsert1);
      for (int i = 1; i < 4; ++i) {
        psInsert1.setString(1, "symbol" + i);
        psInsert1.setString(2, "lse");
        psInsert1.setString(3, "name" + i);
        psInsert1.setInt(4, 1);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into buyorders values (?, ?,?)");
      statements.add(psInsert2);
      for (int i = 1; i < 10; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, 1);
        assertEquals(1, psInsert2.executeUpdate());
      }
      int n = s.executeUpdate("update securities set tid = 7 where sec_id = 3");
      // No violation expected.
      assertEquals(1, n);

      n = s.executeUpdate("update securities set exchange = 'amex' "
          + "where sec_id = 9");
      // No violation expected.
      assertEquals(1, n);

    } finally {
      // Statements and PreparedStatements
      int i = 0;
      while (!statements.isEmpty()) {
        // PreparedStatement extend Statement
        Statement st = (Statement)statements.remove(i);
        if (st != null) {
          st.close();
          st = null;
        }
      }

      TestUtil.shutDown();
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    }

  }

  public void testBug46803_3() throws Exception {

    startVMs(1, 4);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    ArrayList<Object> statements = new ArrayList<Object>();
    // PreparedStatements
    PreparedStatement psInsert1, psInsert2, psInsert4 = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = null;
    try {
      conn = TestUtil.getConnection();
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      s = conn.createStatement();
      statements.add(s);

      // We create a table...

      s.execute(" create table securities ( id int primary key,"
          + " sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int,"
          + " constraint sec_id_uq unique (sec_id),"
          + " constraint sec_uq unique (symbol, exchange), constraint"
          + " exc_ch check (exchange in ('nasdaq', 'nye', 'amex',"
          + " 'lse', 'fse', 'hkse', 'tse'))) ");

      s.execute(" create table companies (symbol varchar(10) not null, "
          + " exchange varchar(10) not null,  companyname char(100), "
          + " tid int, constraint comp_pk primary key (symbol, exchange) ,"
          + "constraint comp_fk foreign key (symbol, exchange) "
          + "references securities (symbol, exchange) on delete restrict)");

      s.execute("create table buyorders(oid int not null"
          + " constraint buyorders_pk primary key,"
          + " sid int,  tid int, "
          + " constraint bo_sec_fk foreign key (sid) references securities (sec_id) "
          + " on delete restrict)");

      // and add a few rows...
      psInsert4 = conn
          .prepareStatement("insert into securities values (?,?, ?, ?,?)");
      statements.add(psInsert4);
      for (int i = 1; i < 10; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setInt(2, i);
        psInsert4.setString(3, "symbol" + i);
        psInsert4.setString(4, "lse");
        psInsert4.setInt(5, 1);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into companies values (?, ?,?,?)");
      statements.add(psInsert1);
      for (int i = 1; i < 4; ++i) {
        psInsert1.setString(1, "symbol" + i);
        psInsert1.setString(2, "lse");
        psInsert1.setString(3, "name" + i);
        psInsert1.setInt(4, 1);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into buyorders values (?, ?,?)");
      statements.add(psInsert2);
      for (int i = 1; i < 10; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, 1);
        assertEquals(1, psInsert2.executeUpdate());
      }
      int n = s.executeUpdate("update securities set sec_id = 7, "
          + "symbol='random7' where sec_id = 7");
      // No violation expected.
      assertEquals(1, n);

      try {
        s.executeUpdate("update securities set symbol = 'random2' , "
            + "sec_id = 2 where sec_id = 2");
        fail("Should have thrown constraint violation exception");
      } catch (SQLException expected) {
        if (!expected.getSQLState().equals("23503")) {
          expected.printStackTrace();
          fail("unexpected sql state for sql exception =" + expected);
        }
      }

      try {
        s.executeUpdate("update securities set symbol = 'random8' , "
            + "sec_id = 18 where sec_id = 8");
        fail("Should have thrown constraint violation exception");
      } catch (SQLException expected) {
        if (!expected.getSQLState().equals("23503")) {
          expected.printStackTrace();
          fail("unexpected sql state for sql exception =" + expected);
        }
      }

      n = s.executeUpdate("update securities set symbol = 'symbol2' , "
          + "sec_id = 2 where sec_id = 2");
      // No violation expected.
      assertEquals(1, n);

    } finally {
      // Statements and PreparedStatements
      int i = 0;
      while (!statements.isEmpty()) {
        // PreparedStatement extend Statement
        Statement st = (Statement)statements.remove(i);
        if (st != null) {
          st.close();
          st = null;
        }
      }

      TestUtil.shutDown();
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    }
  }

  public void testBug46803_4() throws Exception {

    startVMs(1, 4);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    ArrayList<Object> statements = new ArrayList<Object>();
    // PreparedStatements
    PreparedStatement psInsert1, psInsert2, psInsert4 = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = null;
    try {
      conn = TestUtil.getConnection();
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      s = conn.createStatement();
      statements.add(s);

      // We create a table...

      s.execute(" create table securities ( id int primary key,"
          + " sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int,"
          + " constraint sec_id_uq unique (sec_id),"
          + " constraint sec_uq unique (symbol, exchange), constraint"
          + " exc_ch check (exchange in ('nasdaq', 'nye', 'amex',"
          + " 'lse', 'fse', 'hkse', 'tse'))) ");

      s.execute(" create table companies (symbol varchar(10) not null, "
          + " exchange varchar(10) not null,  companyname char(100), "
          + " tid int, constraint comp_pk primary key (symbol, exchange) ,"
          + "constraint comp_fk foreign key (symbol, exchange) "
          + "references securities (symbol, exchange) on delete restrict)");

      s.execute("create table buyorders(oid int not null"
          + " constraint buyorders_pk primary key,"
          + " sid int,  tid int, "
          + " constraint bo_sec_fk foreign key (sid) references securities (sec_id) "
          + " on delete restrict)");

      // and add a few rows...
      psInsert4 = conn
          .prepareStatement("insert into securities values (?,?, ?, ?,?)");
      statements.add(psInsert4);
      for (int i = 1; i < 50; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setInt(2, i);
        psInsert4.setString(3, "symbol" + i);
        psInsert4.setString(4, "lse");
        psInsert4.setInt(5, 1);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into companies values (?, ?,?,?)");
      statements.add(psInsert1);
      for (int i = 1; i < 20; ++i) {
        psInsert1.setString(1, "symbol" + i);
        psInsert1.setString(2, "lse");
        psInsert1.setString(3, "name" + i);
        psInsert1.setInt(4, 1);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into buyorders values (?, ?,?)");
      statements.add(psInsert2);
      for (int i = 1; i < 20; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, 1);
        assertEquals(1, psInsert2.executeUpdate());
      }
      int n = s.executeUpdate("update securities set sec_id = 2100, "
          + "symbol='random21' where id = 21");
      // No violation expected.
      assertEquals(1, n);

      try {
        s.executeUpdate("update securities set symbol = 'random2' , "
            + "sec_id = 2 where id = 2");
        fail("Should have thrown constraint violation exception");
      } catch (SQLException expected) {
        if (!expected.getSQLState().equals("23503")) {
          expected.printStackTrace();
          fail("unexpected sql state for sql exception =" + expected);
        }
      }

      try {
        s.executeUpdate("update securities set symbol = 'symbol8' , "
            + "sec_id = 18 where id = 8");
        fail("Should have thrown constraint violation exception");
      } catch (SQLException expected) {
        if (!expected.getSQLState().equals("23503")) {
          expected.printStackTrace();
          fail("unexpected sql state for sql exception =" + expected);
        }
      }

      n = s.executeUpdate("update securities set symbol = 'symbol2' , "
          + "sec_id = 2 where id = 2");
      // No violation expected.
      assertEquals(1, n);

    } finally {
      // Statements and PreparedStatements
      int i = 0;
      while (!statements.isEmpty()) {
        // PreparedStatement extend Statement
        Statement st = (Statement)statements.remove(i);
        if (st != null) {
          st.close();
          st = null;
        }
      }

      TestUtil.shutDown();
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    }
  }


  public void testBug46803_5() throws Exception {

    startClientVMs(1,0, null);
    startServerVMs(1, 0, "sg1");
    startServerVMs(2, 0, "sg2");

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    ArrayList<Object> statements = new ArrayList<Object>();
    // PreparedStatements
    PreparedStatement psInsert1, psInsert2, psInsert4 = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = null;
    try {
      conn = TestUtil.getConnection();
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      s = conn.createStatement();
      statements.add(s);

      // We create a table...

      s.execute(" create table securities ( id int primary key,"
          + " sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int, sec_id2 int,"
          + " constraint sec_id_uq unique (sec_id),constraint sec_id2_uq unique (sec_id2),"
          + " constraint "
          + " exc_ch check (exchange in ('nasdaq', 'nye', 'amex',"
          + " 'lse', 'fse', 'hkse', 'tse'))) server groups (sg1)");

      // and add a few rows...
      psInsert4 = conn
          .prepareStatement("insert into securities values (?,?, ?, ?,?,?)");
      statements.add(psInsert4);
      for (int i = 1; i < 3; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setInt(2, i);
        psInsert4.setString(3, "symbol" + i);
        psInsert4.setString(4, "lse");
        psInsert4.setInt(5, 1);
        psInsert4.setInt(6, i*3);
        assertEquals(1, psInsert4.executeUpdate());
      }


      s.execute("create table buyorders(oid int not null"
          + " constraint buyorders_pk primary key,"
          + " sid int,  tid int, sec_id2 int,"
          + " constraint bo_sec_fk foreign key (sid) references securities (sec_id) "
          + " on delete restrict," +
          "   constraint bo_sec_fk2 foreign key (sec_id2) references securities (sec_id2) "
          + " on delete restrict" +
          ") server groups (sg2)");

      psInsert2 = conn
          .prepareStatement("insert into buyorders values (?, ?,?,?)");
      statements.add(psInsert2);
      for (int i = 1; i < 2; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, 1);
        psInsert2.setNull(4, Types.INTEGER);
        assertEquals(1, psInsert2.executeUpdate());
      }
      int n = s.executeUpdate("update securities set sec_id = sec_id * sec_id , sec_id2 = sec_id2*4 ");
      // No violation expected.
      assertEquals(2, n);

    } finally {
      // Statements and PreparedStatements
      int i = 0;
      while (!statements.isEmpty()) {
        // PreparedStatement extend Statement
        Statement st = (Statement)statements.remove(i);
        if (st != null) {
          st.close();
          st = null;
        }
      }

      TestUtil.shutDown();
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  public void testBug47204() throws Exception {
    startVMs(1, 2);

    clientSQLExecute(1, "create table warehouse2 ("
        + "w_id        integer   not null,"
        + "w_zip       char(9)"
        + ") replicate persistent");
    serverSQLExecute(1,
        "alter table warehouse2 add constraint pk_warehouse primary key (w_id)");
    clientSQLExecute(1, "insert into warehouse2 values (5, '95020')");
    clientSQLExecute(1, "insert into warehouse2 values (4, '95020')");
    clientSQLExecute(1, "insert into warehouse2 values (3, '95020')");
    clientSQLExecute(1, "insert into warehouse2 values (2, '95020')");
    clientSQLExecute(1, "insert into warehouse2 values (1, '95020')");

    // restart VMs
    stopVMNums(-1, 1, -2);
    restartVMNums(1, -1, -2);

    // check data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from warehouse2", null, "5");
  }

  public void test47193() throws Exception {
    // disabled due to bug #51558
    if (isTransactional) {
      return;
    }
    // reduce lock timeout for this test
    Properties props = new Properties();
    props.setProperty(GfxdConstants.MAX_LOCKWAIT, "6000");
    startVMs(2, 1, 0, null, props);

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_READ_ONLY);

    stmt.execute("create table emp (id int primary key, id2 char(10), "
        + "addr varchar(100) not null)");
    stmt.execute("insert into emp values (1, 'one', 'addr1')");
    stmt.execute("insert into emp values (2, 'two', 'addr2')");
    stmt.execute("insert into emp values (3, 'three', 'addr3')");

    PreparedStatement pstmt = conn.prepareStatement("select * from emp");
    ResultSet rs = stmt.executeQuery("select * from emp");
    ResultSet rs2 = pstmt.executeQuery();

    try {
      stmt.execute("alter table emp drop column id2");
      fail("expected an open result set in drop index");
    } catch (SQLException sqle) {
      if (!"X0X95".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // ResultSet rs will get closed since ALTER TABLE was fired on the
    // same statement
    assertTrue(rs.isClosed());
    assertFalse(rs2.isClosed());
    try {
      clientSQLExecute(2, "alter table emp drop column id2");
      fail("expected a lock timeout in drop index");
    } catch (RMIException ex) {
      if (!(ex.getCause() instanceof SQLException)
          || !"40XL1".equals(((SQLException)ex.getCause()).getSQLState())) {
        throw ex;
      }
    }

    int numResults = 0;
    rs = stmt.executeQuery("select * from emp");
    assertEquals(1, rs.findColumn("ID"));
    assertEquals(2, rs.findColumn("ID2"));
    assertEquals(3, rs.findColumn("ADDR"));
    while (rs.next()) {
      int id = rs.getInt(1);
      String id2 = rs.getString(2);
      String addr = rs.getString(3);

      assertTrue("unexpected id=" + id, id >= 1 && id <= 3);
      assertNotNull(id2);
      assertEquals("addr" + id, addr);

      // check after partial results consumed
      if (numResults == 0) {
        try {
          clientSQLExecute(1, "alter table emp drop column id2");
          fail("expected a lock timeout in drop index");
        } catch (SQLException sqle) {
          if (!"40XL1".equals(sqle.getSQLState())) {
            throw sqle;
          }
        }
      }

      numResults++;
    }
    assertEquals(3, numResults);
    rs.close();

    // alter table should still fail since the second ResultSet is open
    try {
      stmt.execute("alter table emp drop column id2");
      fail("expected an open result set in drop index");
    } catch (SQLException sqle) {
      if (!"X0X95".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    assertFalse(rs2.isClosed());
    try {
      clientSQLExecute(2, "alter table emp drop column id2");
      fail("expected a lock timeout in drop index");
    } catch (RMIException ex) {
      if (!(ex.getCause() instanceof SQLException)
          || !"40XL1".equals(((SQLException)ex.getCause()).getSQLState())) {
        throw ex;
      }
    }

    numResults = 0;
    assertEquals(1, rs2.findColumn("ID"));
    assertEquals(2, rs2.findColumn("ID2"));
    assertEquals(3, rs2.findColumn("ADDR"));
    while (rs2.next()) {
      int id = rs2.getInt(1);
      String id2 = rs2.getString(2);
      String addr = rs2.getString(3);

      assertTrue("unexpected id=" + id, id >= 1 && id <= 3);
      assertNotNull(id2);
      assertEquals("addr" + id, addr);

      // check after partial results consumed
      if (numResults == 1) {
        try {
          clientSQLExecute(2, "alter table emp drop column id2");
          fail("expected a lock timeout in drop index");
        } catch (RMIException ex) {
          if (!(ex.getCause() instanceof SQLException)
              || !"40XL1".equals(((SQLException)ex.getCause()).getSQLState())) {
            throw ex;
          }
        }
      }

      numResults++;
    }
    assertEquals(3, numResults);

    // alter table should pass now
    clientSQLExecute(2, "alter table emp drop column id2");

    // query should get recompiled
    numResults = 0;
    rs = pstmt.executeQuery();
    assertEquals(2, rs.getMetaData().getColumnCount());
    try {
      rs.findColumn("ID2");
      fail("expected failure in ID2 lookup");
    } catch (SQLException sqle) {
      if (!"S0022".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    while (rs.next()) {
      int id = rs.getInt(1);
      String addr = rs.getString(2);

      assertTrue("unexpected id=" + id, id >= 1 && id <= 3);
      assertEquals("addr" + id, addr);

      numResults++;
    }
    assertEquals(3, numResults);

    rs = stmt.executeQuery("select * from emp");
    assertEquals(2, rs.getMetaData().getColumnCount());
    try {
      rs.findColumn("ID2");
      fail("expected failure in ID2 lookup");
    } catch (SQLException sqle) {
      if (!"S0022".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    numResults = 0;
    while (rs.next()) {
      int id = rs.getInt(1);
      String addr = rs.getString(2);

      assertTrue("unexpected id=" + id, id >= 1 && id <= 3);
      assertEquals("addr" + id, addr);

      numResults++;
    }
    assertEquals(3, numResults);

    // alter table should fail because of open scrollable RS
    try {
      clientSQLExecute(1, "alter table emp drop column addr");
      fail("expected a lock timeout in drop index");
    } catch (SQLException sqle) {
      if (!"40XL1".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // should pass after closing rs
    rs.close();
    clientSQLExecute(2, "alter table emp drop column addr");

    // query should get recompiled
    numResults = 0;
    rs = pstmt.executeQuery();
    assertEquals(1, rs.getMetaData().getColumnCount());
    try {
      rs.findColumn("ID2");
      fail("expected failure in ID2 lookup");
    } catch (SQLException sqle) {
      if (!"S0022".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      rs.findColumn("ADDR");
      fail("expected failure in ADDR lookup");
    } catch (SQLException sqle) {
      if (!"S0022".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    while (rs.next()) {
      int id = rs.getInt(1);

      assertTrue("unexpected id=" + id, id >= 1 && id <= 3);

      numResults++;
    }
    assertEquals(3, numResults);

    numResults = 0;
    rs = stmt.executeQuery("select * from emp");
    assertEquals(1, rs.getMetaData().getColumnCount());
    try {
      rs.findColumn("ID2");
      fail("expected failure in ID2 lookup");
    } catch (SQLException sqle) {
      if (!"S0022".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      rs.findColumn("ADDR");
      fail("expected failure in ADDR lookup");
    } catch (SQLException sqle) {
      if (!"S0022".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    while (rs.next()) {
      int id = rs.getInt(1);

      assertTrue("unexpected id=" + id, id >= 1 && id <= 3);

      numResults++;
    }
    assertEquals(3, numResults);
  }

  public void testBug47289() throws Exception {

    // Start two server VMs
    startVMs(1, 2);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    // PreparedStatements
    Statement st = null;
    ResultSet rs = null;
    Connection conn = null;
    String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
    try {
      conn = TestUtil.getConnection();
      st = conn.createStatement();
      st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
      st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
      st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
          + "constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  "
          + "partition by list (exchange) (VALUES ('nasdaq','nye'), VALUES ('amex','lse'), VALUES ('fse','hkse','tse'))");

      PreparedStatement psSec = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?, ?)");

      for (int i = 1; i < 20; ++i) {
        psSec.setInt(1, i);
        psSec.setString(2, getSymbol(1, 6) + "_" + i);
        psSec.setString(2, getSymbol(1, 8));
        psSec.setString(3, exchanges[i % 7]);
        psSec.setInt(4, 50);
        psSec.executeUpdate();
      }
      startServerVMs(2, 0, null);
      st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, "
          + "companytype int, tid int, constraint comp_pk primary key (symbol, exchange))");
      PreparedStatement psComp = conn
          .prepareStatement("insert into trade.companies (symbol, "
              + "exchange, companytype, tid) values (?,?,?,?)");
      rs = st.executeQuery("select * from trade.securities");
      int k = 0;
      while (rs.next()) {
        String symbol = rs.getString(2);
        String exchange = rs.getString(3);
        psComp.setString(1, symbol);
        psComp.setString(2, exchange);
        psComp.setInt(3, k);
        psComp.setInt(4, 50);
        psComp.executeUpdate();
        ++k;
      }
      assertTrue(k >= 1);
      try {
        st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange) "
          + "references trade.securities (symbol, exchange) on delete restrict");
        fail("FK constraint addition should fail");
      }catch(SQLException sqle) {
        if(sqle.getSQLState().equals("0A000")) {
          //Ok
        }else {
          throw sqle;
        }
      }
    } finally {
      TestUtil.shutDown();
    }

  }


  public void testBug47289_1() throws Exception {

    // Start two server VMs
    startVMs(1, 4);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    // PreparedStatements
    Statement st = null;
    ResultSet rs = null;
    Connection conn = null;
    String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
    try {
      conn = TestUtil.getConnection();
      st = conn.createStatement();

      st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
          + "constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  "
          + "partition by list (exchange) (VALUES ('nasdaq','nye'), VALUES ('amex','lse'), VALUES ('fse','hkse','tse'))");

      PreparedStatement psSec = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?, ?)");

      for (int i = 1; i < 20; ++i) {
        psSec.setInt(1, i);
        psSec.setString(2, getSymbol(1, 6)+"_"+i);
        psSec.setString(3, exchanges[i % 7]);
        psSec.setInt(4, 50);
        psSec.executeUpdate();
      }

      st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, "
          + "companytype int, tid int, constraint comp_pk primary key (symbol, exchange)) replicate");
      PreparedStatement psComp = conn
          .prepareStatement("insert into trade.companies (symbol, "
              + "exchange, companytype, tid) values (?,?,?,?)");
      rs = st.executeQuery("select * from trade.securities");
      int k = 0;
      while (rs.next()) {
        String symbol = rs.getString(2);
        String exchange = rs.getString(3);
        psComp.setString(1, symbol);
        psComp.setString(2, exchange);
        psComp.setInt(3, k);
        psComp.setInt(4, 50);
        psComp.executeUpdate();
        ++k;
      }
      assertTrue(k >= 1);
      st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange) "
          + "references trade.securities (symbol, exchange) on delete restrict");


    } finally {
      TestUtil.shutDown();
    }

  }

  public void testBug47655() throws Exception {

    // Not valid for transactions
    if (isTransactional) {
      return;
    }
     // Start two server VMs
        startVMs(1, 2);
        VM dataStore1 = null;
        Connection conn = TestUtil.getConnection();
        Statement s = conn.createStatement();
        try {
          s.execute("create table securities (sec_id int not null, symbol varchar(10) not null,"
              + " exchange varchar(10) not null,tid int, constraint sec_pk primary key (sec_id), "
              + "constraint sec_uq unique (symbol, exchange)) "
              + "  partition by column(tid) redundancy 1");

          PreparedStatement ps = conn.prepareStatement("insert into securities values (?,?,?,?)");
          for(int i =1 ; i < 22; ++i) {
            ps.setInt(1, i);
            ps.setString(2,"symbol"+i );
            ps.setString(3,"fse" );
            ps.setInt(4, i);
            ps.executeUpdate();
          }

          startServerVMs(2, -1, null);

          //wait for gii
          Thread.sleep(5000);
          dataStore1 = serverVMs.get(0);
          dataStore1.invoke(new SerializableRunnable("cache closer") {
            public void run() {
              GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
                @Override
                public void beforeQueryExecutionByStatementQueryExecutor(
                    GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
                  try {
                    TestUtil.shutDown();
                  } catch (SQLException sqle) {
                    // ignore
                  }
                }
              };
             GemFireXDQueryObserverHolder.setInstance(observer);
            }
          });
          s.executeUpdate("update securities set symbol = 'symbol20', exchange = 'fse' where sec_id= 20 and tid = 20");
          s.executeUpdate("delete from securities where sec_id = 20");
        } finally {
          if (dataStore1 != null) {
            dataStore1.invoke(new SerializableRunnable("reset") {
              public void run() {
                GemFireXDQueryObserverHolder.clearInstance();
                try {
                  _startNewServer(BugsDUnit.class.getName(),
                      BugsDUnit.this.getName(), -1, null, null, false);
                } catch (Exception ignore) {
                  ignore.printStackTrace();
                }
              }
            });
          }
          TestUtil.shutDown();
        }
  }

  public void testBug47289_2() throws Exception {

    // Start two server VMs
    startVMs(1, 4);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    // PreparedStatements
    Statement st = null;
    ResultSet rs = null;
    Connection conn = null;
    String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
    try {
      conn = TestUtil.getConnection();
      st = conn.createStatement();

      st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
          + "constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  "
          + "partition by list (exchange) (VALUES ('nasdaq','nye'), VALUES ('amex','lse'), VALUES ('fse','hkse','tse'))");

      /* PreparedStatement psSec = conn
           .prepareStatement("insert into trade.securities values (?, ?, ?, ?)");

       for (int i = 1; i < 20; ++i) {
         psSec.setInt(1, i);
         psSec.setString(2, getSymbol(1, 8));
         psSec.setString(3, exchanges[i % 7]);
         psSec.setInt(4, 50);
         psSec.executeUpdate();
       }*/

      st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, "
          + "companytype int, tid int, constraint comp_pk primary key (symbol, exchange)) replicate");
      PreparedStatement psComp = conn
          .prepareStatement("insert into trade.companies (symbol, "
              + "exchange, companytype, tid) values (?,?,?,?)");
      rs = st.executeQuery("select * from trade.securities");

      String symbol = "xxx";
      String exchange = "amex";
      psComp.setString(1, symbol);
      psComp.setString(2, exchange);
      psComp.setInt(3, 1);
      psComp.setInt(4, 50);
      assertEquals(1, psComp.executeUpdate());
      try {
        st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange) "
            + "references trade.securities (symbol, exchange) on delete restrict");
        fail("FK constraint addition should fail");
      } catch (SQLException sqle) {
        if (sqle.getSQLState().equals("X0Y45")) {
          // Ok
        }
        else {
          throw sqle;
        }
      }

    } finally {
      TestUtil.shutDown();
    }

  }


  public void testBug47289_3() throws Exception {

    // Start two server VMs
    startVMs(1, 4);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    // PreparedStatements
    Statement st = null;
    ResultSet rs = null;
    Connection conn = null;
    String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
    try {
      conn = TestUtil.getConnection();
      st = conn.createStatement();

      st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
          + "constraint sec_uq unique (symbol, exchange, tid),"
          + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  "
          + "partition by column (symbol,tid) ");

      PreparedStatement psSec = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?, ?)");

      for (int i = 1; i < 20; ++i) {
        psSec.setInt(1, i);
        psSec.setString(2, getSymbol(1, 6)+"_"+i);
        psSec.setString(3, exchanges[i % 7]);
        psSec.setInt(4, i);
        psSec.executeUpdate();
      }

      st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, "
          + "companytype int, tid int, constraint comp_pk primary key (symbol, exchange)) replicate");
      PreparedStatement psComp = conn
          .prepareStatement("insert into trade.companies (symbol, "
              + "exchange, companytype, tid) values (?,?,?,?)");
      rs = st.executeQuery("select * from trade.securities");
      int k = 0;
      while (rs.next()) {
        String symbol = rs.getString(2);
        String exchange = rs.getString(3);
        int tid = rs.getInt(4);
        psComp.setString(1, symbol);
        psComp.setString(2, exchange);
        psComp.setInt(3, k);
        psComp.setInt(4, tid);
        psComp.executeUpdate();
        ++k;
      }
      assertTrue(k >= 1);
      st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange, tid) "
          + "references trade.securities (symbol, exchange, tid) on delete restrict");


    } finally {
      TestUtil.shutDown();
    }

  }

  public void testBug47289_47611_4() throws Exception {

    // Start some server VMs
    startVMs(1, 4);

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    // list of Statements
    // PreparedStatements
    Statement st = null;
    ResultSet rs = null;
    Connection conn = null;
    String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
    try {
      conn = TestUtil.getConnection();
      st = conn.createStatement();

      st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
          + " exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
          + "constraint sec_uq unique (symbol, exchange, tid),"
          + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  "
          + "partition by column (symbol) ");

      PreparedStatement psSec = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?, ?)");

      for (int i = 1; i < 20; ++i) {
        psSec.setInt(1, i);
        psSec.setString(2, getSymbol(1, 6)+"_"+i);
        psSec.setString(3, exchanges[i % 7]);
        psSec.setInt(4, i);
        psSec.executeUpdate();
      }

      st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, "
          + "companytype int, tid int, constraint comp_pk primary key (symbol, exchange)) replicate");
      PreparedStatement psComp = conn
          .prepareStatement("insert into trade.companies (symbol, "
              + "exchange, companytype, tid) values (?,?,?,?)");
      rs = st.executeQuery("select * from trade.securities");
      int k = 0;
      while (rs.next()) {
        String symbol = rs.getString(2);
        String exchange = rs.getString(3);
        int tid = rs.getInt(4);
        psComp.setString(1, symbol);
        psComp.setString(2, exchange);
        psComp.setInt(3, k);
        psComp.setInt(4, tid);
        psComp.executeUpdate();
        ++k;
      }
      assertTrue(k >= 1);
      st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange, tid) "
          + "references trade.securities (symbol, exchange, tid) on delete restrict");


    } finally {
      TestUtil.shutDown();
    }
  }

  // #48263: when batch update statement is re-prepared values
  // accumulated till the re-prepare time for putAll are lost
  public void testBug48263() throws Exception {
    startVMs(1, 2);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    st.execute("create table t1 (col1 int, col2 int, col3 int, constraint "
        + "pk1 primary key (col1))");

    st.execute("insert into t1 values (1, 1, 1), (2, 2, 2)");

    // to forcefully re-prepare the update statement
    SerializableRunnable csr = new SerializableRunnable("_48263_") {
      @Override
      public void run() {
        GemFireXDQueryObserver old = GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
              @Override
              public void beforeQueryReprepare(GenericPreparedStatement gp,
                  LanguageConnectionContext lcc) throws StandardException {
                gp.makeInvalid(DependencyManager.CREATE_INDEX, lcc);
              }
            });
      }
    };

    clientExecute(1, csr);
//    serverExecute(1, csr);
//    serverExecute(2, csr);

    PreparedStatement ps1 = conn.prepareStatement("update t1 set col2 = ? "
        + "where col1 = ?");

    for (int i = 1; i <= 2; i++) {
      ps1.setInt(1, 10);
      ps1.setInt(2, i);
      ps1.addBatch();
    }
    ps1.executeBatch();

    // verify the contents of table
    ResultSet rs = st.executeQuery("select col1, col2 from t1 order by col1");
    assertTrue(rs.next());
    assertEquals(10, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(10, rs.getInt(2));
    assertFalse(rs.next());
  }

  public void testBug48232() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(1, "create schema scott");

    Properties props = new Properties();
    props.put("user", "scott");
    // should not fail
    startClientVMs(1, 0, null, props);
    // do some operations
    clientSQLExecute(2, "create table scott.t1(col1 int)");
    clientSQLExecute(2, "insert into scott.t1 values(1)");
    sqlExecuteVerify(new int[] { 2 }, null, "select * from t1", null, "1");
    // now drop the schema...implicit schema created when a VM is started
    clientSQLExecute(2, "drop table scott.t1");
    clientSQLExecute(2, "drop schema scott restrict");
    startClientVMs(1, 0, null, props);
    clientSQLExecute(3, "create table t1(col1 int)");
    clientSQLExecute(3, "insert into scott.t1 values(1)");
    sqlExecuteVerify(new int[] { 3 }, null, "select * from t1", null, "1");
  }

  public void testBug48232_2() throws Exception {
    // also test whether set current schema causes any issue on
    // restart
    Properties props = new Properties();
    props.put("user", "scott1");
    props.put("password", "pwd");
    startVMs(1, 1, 0, null, props);
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema s1");
    st.execute("set current schema s1");
    st.execute("create table t1 (col1 int)");
    conn.close();
    props.put("user", "scott2");
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    st.execute("create schema s2");
    st.execute("set current schema s2");
    st.execute("create table t2 (col1 int)");
    conn.close();
    stopVMNums(-1);
    restartVMNums(-1);
    serverSQLExecute(1, "insert into s1.t1 values(1)");
    serverSQLExecute(1, "insert into s2.t2 values(1)");
    sqlExecuteVerify(null, new int[] { 1 }, "select * from s1.t1", null, "1");
    sqlExecuteVerify(null, new int[] { 1 }, "select * from s2.t2", null, "1");

  }

  public void testUseCase2ImportFailureBug() throws Exception {
    startVMs(1, 2);
    try {
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();

      st.execute("create table app.t1(col1 int, col2 int, col3 int, constraint "
          + "pk1 primary key (col1)) " + "partition by (col1)");
      st.execute("create table app.t1_hist(col1 int, col2 int, col3 int, constraint "
          + "pk2 primary key (col1)) " + "partition by (col1)");

      st.execute("create trigger app.trig1 after insert on app.t1 referencing "
          + "new as newrow for each row insert into "
          + "app.t1_hist values (newrow.col1, newrow.col2, newrow.col3)");

      st.execute("insert into app.t1_hist values (1, 1, 1), (4, 4, 4), (5, 5, 5)");

      PrintWriter p = new PrintWriter(new File("data.csv"));
      // should cause a primary key constraint violation in t1_hist due
      // to the trigger
      p.write("1,2,2,\n");
      p.close();

      try {
        addExpectedException(new int[] { 1 }, new int[] { 1, 2 },
            SQLException.class);
        st.executeUpdate("call SYSCS_UTIL.IMPORT_TABLE("
            + "'app', 't1', 'data.csv', null, null, null, 0)");
      } catch (SQLException se) {
        if (se.getSQLState().equals("XIE0R")
            && se.getMessage().contains("The statement was aborted because "
                + "it would have caused a duplicate key value in a unique "
                + "or primary key constraint or unique index identified by "
                + "'primary key constraint'")) {
          // ignore
        } else {
          throw se;
        }
      } finally {
        removeExpectedException(new int[] { 1 }, new int[] { 1, 2 },
            SQLException.class);
      }

      getLogWriter().info(
          "Creating a table on the same coonnection "
              + "where an import failed");

      st.execute("create table app.t3 (col1 int, col2 int, col3 int)");
      st.execute("insert into app.t3 values(0, 0, 0)");
      st.execute("select col1 from app.t3");
      ResultSet rs = st.getResultSet();
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());
      st.execute("drop table app.t3");
    } finally {
      new File("data.csv").delete();
    }
  }

  public void testUseCase2ExecuteBatchBug() throws Exception {
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties props = new Properties();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    startVMs(0, 1, 0, null, props);
    props.clear();
    props.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, props);

  Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    st.execute("CREATE TABLE APP.TEST1( ID varchar(20), " +
    		"COL1 int, COL2 int, COL3 int, COL4 int, " +
    		"PRIMARY KEY (ID, COL1)) PARTITION BY " +
    		"PRIMARY KEY REDUNDANCY 1 PERSISTENT ASYNCHRONOUS");

    st.execute("insert into APP.test1 values ('3', 3, 1, 1, 1)");

    conn.setAutoCommit(false);

    PreparedStatement stmt = conn.prepareStatement("UPDATE APP.TEST1 " +
    		"SET COL2 = ? WHERE ID = ? AND COL1 = ?");

    for(int i=0; i < 1; i++)
    {
      stmt.setInt(1, 9);
      stmt.setString(2, "3");
      stmt.setInt(3, 3);

      stmt.addBatch();
      if( ((i+1) % 10) == 0)
      {
        stmt.executeBatch();
        conn.commit();
      }
    }
    stmt.executeBatch();
    conn.commit();
    ResultSet rs = st.executeQuery("select * from app.test1");
    assertTrue(rs.next());
    assertEquals(9, rs.getInt(3));
    assertFalse(rs.next());
  }

  public void testBug47976() throws Exception {

    // Start few server VMs
    startVMs(1, 4);

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    st.execute("CREATE TABLE COMSPC.COM_SPC_CHART_MGMT ("
        + "SYS_NAME               VARCHAR(12)      NOT NULL,"
        + "GROUP_NO               DECIMAL(10,0)                 NOT NULL,"
        + "CHART_NO               DECIMAL(10,0)                 NOT NULL,"
        + "CHART_TYPE_NO          NUMERIC(8,2)                 NOT NULL,"
        + "CHART_NAME             VARCHAR(40)      NOT NULL,"
        + "CHART_DESC             VARCHAR(64),"
        + "DC_ITEM_NAME           VARCHAR(40)      NOT NULL,"
        + "UNIT_NAME              VARCHAR(12),"
        + "USL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "UWL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "UCL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "TARGET_VAL             DECIMAL(30,20)                 NOT NULL,"
        + "MEAN_VAL               DECIMAL(30,20)                 NOT NULL,"
        + "LCL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "LWL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "LSL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "CHART_IDNTFY_1_CMT     VARCHAR(64),"
        + "CHART_IDNTFY_2_CMT     VARCHAR(64),"
        + "CHART_IDNTFY_3_CMT     VARCHAR(64),"
        + "UPDATE_TIME            DATE                   NOT NULL,"
        + "UPDATE_USER_NAME       VARCHAR(11)      NOT NULL,"
        + "CHART_CREATE_TIME      DATE,"
        + "CHART_CTRL_TYPE        VARCHAR(10),"
        + "TGHTN_TYPE             VARCHAR(10),"
        + "TGHTN_DERIVE_CHART_NO  DECIMAL(30,20),"
        + "TGHTN_RULE             DECIMAL(30,20),"
        + "TGHTN_TIME             DATE,"
        + "TGHTN_DERIVE_SBGRP_NO  NUMERIC(10,5),"
        + "FAB_NAME               VARCHAR(12),"
        + "PRIMARY KEY (SYS_NAME,GROUP_NO,CHART_NO)"
        + ") REPLICATE PERSISTENT SYNCHRONOUS");

    st.execute("CREATE INDEX COMSPC.COM_SPC_CHART_MGMT_PK ON "
        + "COMSPC.COM_SPC_CHART_MGMT (SYS_NAME,GROUP_NO,CHART_NO)");

    st.execute("CREATE TABLE COMSPC.COM_SPC_CHART_MGMT_HIST ("
        + "SYS_NAME               VARCHAR(12)      NOT NULL,"
        + "GROUP_NO               DECIMAL(10,0)                 NOT NULL,"
        + "CHART_NO               DECIMAL(10,0)                 NOT NULL,"
        + "CHART_TYPE_NO          NUMERIC(8,2)                 NOT NULL,"
        + "CHART_NAME             VARCHAR(40)      NOT NULL,"
        + "CHART_DESC             VARCHAR(64),"
        + "DC_ITEM_NAME           VARCHAR(40)      NOT NULL,"
        + "UNIT_NAME              VARCHAR(12),"
        + "USL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "UWL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "UCL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "TARGET_VAL             DECIMAL(30,20)                 NOT NULL,"
        + "MEAN_VAL               DECIMAL(30,20)                 NOT NULL,"
        + "LCL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "LWL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "LSL_VAL                DECIMAL(30,20)                 NOT NULL,"
        + "CHART_IDNTFY_1_CMT     VARCHAR(64),"
        + "CHART_IDNTFY_2_CMT     VARCHAR(64),"
        + "CHART_IDNTFY_3_CMT     VARCHAR(64),"
        + "UPDATE_TIME            DATE                   NOT NULL,"
        + "UPDATE_USER_NAME       VARCHAR(11)      NOT NULL,"
        + "OPER_DESC             VARCHAR(3),"
        + "OPER_TIME             TIMESTAMP," + "CHART_CREATE_TIME      DATE,"
        + "CHART_CTRL_TYPE        VARCHAR(10),"
        + "TGHTN_TYPE             VARCHAR(10),"
        + "TGHTN_DERIVE_CHART_NO  DECIMAL(30,20),"
        + "TGHTN_RULE             DECIMAL(30,20),"
        + "TGHTN_TIME             DATE,"
        + "TGHTN_DERIVE_SBGRP_NO  NUMERIC(10,5),"
        + "FAB_NAME               VARCHAR(12),"
        + "PRIMARY KEY (SYS_NAME,GROUP_NO,CHART_NO,OPER_TIME,DC_ITEM_NAME)"
        + ") PARTITION BY COLUMN (SYS_NAME,GROUP_NO)"
        + "REDUNDANCY 1 PERSISTENT ASYNCHRONOUS");

    st.execute("CREATE TRIGGER COMSPC.CHART_MGMT_DEL "
        + "AFTER DELETE ON COMSPC.COM_SPC_CHART_MGMT "
        + "REFERENCING OLD AS OLD "
        + "FOR EACH ROW "
        + "INSERT INTO COMSPC.COM_SPC_CHART_MGMT_HIST"
        + "(SYS_NAME, GROUP_NO, CHART_NO, CHART_TYPE_NO, CHART_NAME, "
        + "CHART_DESC, DC_ITEM_NAME, UNIT_NAME,"
        + "USL_VAL, UWL_VAL, UCL_VAL, TARGET_VAL, MEAN_VAL, LCL_VAL, LWL_VAL, LSL_VAL, "
        + "CHART_IDNTFY_1_CMT, CHART_IDNTFY_2_CMT, CHART_IDNTFY_3_CMT, UPDATE_TIME, UPDATE_USER_NAME,"
        + "OPER_DESC, OPER_TIME)"
        + "VALUES (OLD.SYS_NAME, OLD.GROUP_NO, OLD.CHART_NO, OLD.CHART_TYPE_NO, OLD.CHART_NAME, "
        + "OLD.CHART_DESC, OLD.DC_ITEM_NAME, OLD.UNIT_NAME,"
        + "OLD.USL_VAL, OLD.UWL_VAL, OLD.UCL_VAL, OLD.TARGET_VAL, OLD.MEAN_VAL, "
        + "OLD.LCL_VAL, OLD.LWL_VAL, OLD.LSL_VAL, "
        + "OLD.CHART_IDNTFY_1_CMT, OLD.CHART_IDNTFY_2_CMT, OLD.CHART_IDNTFY_3_CMT, "
        + "OLD.UPDATE_TIME, OLD.UPDATE_USER_NAME,"
        + "'DEL', CURRENT_TIMESTAMP)");

    // now restart a server
    stopVMNums(-2);
    restartVMNums(-2);
  }

  public void testBug46799() throws Exception {

    // Start some server VMs
    startVMs(1, 4);

    int netPort = startNetworkServer(1, null, null);
    int netPort2 = TestUtil.startNetserverAndReturnPort();
    Connection conn = TestUtil.getConnection();
    Connection conn2 = TestUtil.getNetConnection(netPort, null, null);
    Connection conn3 = TestUtil.getNetConnection(netPort2, null, null);
    Statement st = conn.createStatement();
    Statement st2 = conn2.createStatement();
    Statement st3 = conn3.createStatement();

    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null, companytype smallint, "
        + "uid CHAR(16) FOR BIT DATA, uuid char(36), companyname char(100), "
        + "companyinfo clob, note long varchar, histprice decimal(20,5), "
        + "asset bigint, logo varchar(100) for bit data, tid int, pvt blob, "
        + "constraint comp_pk primary key (symbol, exchange)) "
        + "partition by column(logo)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, "
            + "exchange, companytype, uid, uuid, companyname, companyInfo, " +
            "note, histprice, asset, logo, tid, pvt) values " +
            "(?,?,?,?,?,?,?,?,?,?,?,?,?)");
    Random rnd = PartitionedRegion.rand;
    char[] clobChars = new char[10000];
    byte[] blobBytes = new byte[20000];
    char[] chooseChars = ("abcdefghijklmnopqrstuvwxyz"
        + "ABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890_").toCharArray();
    for (int i = 0; i < clobChars.length; i++) {
      clobChars[i] = chooseChars[rnd.nextInt(chooseChars.length)];
    }
    rnd.nextBytes(blobBytes);
    Clob clob = new SerialClob(clobChars);
    Blob blob = new SerialBlob(blobBytes);
    Object[] row = new Object[] {
        "mzto109",
        "fse",
        Short.valueOf((short)8),
        new byte[] { 62, -73, -54, 72, 34, -24, 69, -63, -110, 24, 50, 105, 62,
            53, -17, -90 },
        "3eb7ca48-22e8-45c1-9218-32693e35efa6",
        "`8vCV`;=h;6s/W$e 0h0^eh",
        clob,
        "BB(BfoRJWVYh'&6dU `gb2*||Yz>=2+!:7C jz->F1}V",
        "50.29",
        8397740485201787197L,
        new byte[] { 127, -18, -85, 16, 79, 86, 50, 90, -70, 121, -38, -97,
            -114, -62, -54, -43, 109, -14, -22, 62, -70, 51, 71, -17, 83, -70,
            21, 126, -73, -113, -7, 121, 43, -24, -53, -44, 38, -93, -87, -86,
            13, -61, -7, -47, 37, 18, -51, 101, 47, 34, -14, 77, 45, 70, -123,
            7, -117, -36, 35, 73, 73, 127, 117, 17 }, 109, blob };
    int col = 1;
    for (Object o : row) {
      psComp.setObject(col, o);
      col++;
    }
    psComp.executeUpdate();

    ResultSet rs = st.executeQuery("select symbol, exchange, companytype, uid,"
        + " uuid, companyname, companyinfo, note, histPrice, asset, logo, tid,"
        + " pvt from TRADE.COMPANIES where tid >= 105 and tid < 110");
    Bugs3Test.checkLobs(rs, clobChars, blobBytes);

    rs = st2.executeQuery("select symbol, exchange, companytype, uid,"
        + " uuid, companyname, companyinfo, note, histPrice, asset, logo, tid,"
        + " pvt from TRADE.COMPANIES where tid >= 105 and tid < 110");
    Bugs3Test.checkLobs(rs, clobChars, blobBytes);

    rs = st3.executeQuery("select symbol, exchange, companytype, uid,"
        + " uuid, companyname, companyinfo, note, histPrice, asset, logo, tid,"
        + " pvt from TRADE.COMPANIES where tid >= 105 and tid < 110");
    Bugs3Test.checkLobs(rs, clobChars, blobBytes);
  }

  public void testBug47943() throws Exception {
    startVMs(1, 4);

    try {
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
      st.execute("create schema trade");

      // Create tables
      st.execute("create table trade.customer "
          + "(c_balance int not null, c_first int not null, c_middle varchar(10), c_next varchar(10),"
          + "c_id int  primary key, c_tid int not null) "
          + "partition by column (c_first)");

      st.execute("create table trade.customerrep "
          + "(c_balance int not null, c_first int not null, c_middle varchar(10), c_next varchar(10), "
          + "c_id int  primary key, c_tid int not null) " + "replicate");

      { // insert
        String[] securities = { "IBM", "MOT", "INTC", "TEK", "AMD", "CSCO",
            "DELL", "HP", "SMALL1", "SMALL2" };
        PreparedStatement psInsert = conn
            .prepareStatement("insert into trade.customer values (?, ?, ?, ?, ?, ?)");
        PreparedStatement psInsert2 = conn
            .prepareStatement("insert into trade.customerrep values (?, ?, ?, ?, ?, ?)");
        for (int i = 0; i < 30; i++) {
          psInsert.setInt(1, i);
          psInsert2.setInt(1, i);

          psInsert.setInt(2, i * 2);
          psInsert2.setInt(2, i * 2);

          psInsert.setString(3, securities[i % 9]);
          psInsert2.setString(3, securities[i % 9]);

          psInsert.setString(4, securities[(2 * i) % 9]);
          psInsert2.setString(4, securities[(2 * i) % 9]);

          psInsert.setInt(5, i * 4);
          psInsert2.setInt(5, i * 4);

          psInsert.setInt(6, i % 10);
          psInsert2.setInt(6, i % 10);

          psInsert.executeUpdate();
          psInsert2.executeUpdate();
        }
      }

      {
        String query = "SELECT * FROM trade.customer order by c_first offset 3 row fetch first row only";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i=0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          while (r.next()) {
            assertEquals(3, r.getInt(1));
            count++;
          }
          assertEquals(1, count);
          r.close();
        }
      }

      {
        String query = "SELECT * FROM trade.customerrep order by c_first offset 3 row fetch first row only";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i=0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          while (r.next()) {
            assertEquals(3, r.getInt(1));
            count++;
          }
          assertEquals(1, count);
          r.close();
        }
      }

      {
        String query = "SELECT * FROM trade.customer order by c_first offset 3 row fetch next 4 row only";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i=0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 3;
          while (r.next()) {
            assertEquals(count, r.getInt(1));
            count++;
          }
          assertEquals(7, count);
          r.close();
        }
      }

      {
        String query = "SELECT * FROM trade.customerrep order by c_first offset 3 row fetch next 4 row only";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i=0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 3;
          while (r.next()) {
            assertEquals(count, r.getInt(1));
            count++;
          }
          assertEquals(7, count);
          r.close();
        }
      }

      {
        String query = "SELECT * FROM trade.customer offset 25 row";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          while (r.next()) {
            count++;
          }
          assertEquals(5, count);
          r.close();
        }
      }

      {
        String query = "SELECT * FROM trade.customerrep offset 25 row";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          while (r.next()) {
            count++;
          }
          assertEquals(5, count);
          r.close();
        }
      }

      {
        String query = "select c_tid, max(c_id * c_first) as largest_order from trade.customer where c_balance > ?  GROUP BY c_tid HAVING max(c_id*c_first) > ? ORDER BY max(c_id*c_first) DESC";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          st1.setInt(1, 20);
          st1.setInt(2, 20);
          ResultSet r = st1.executeQuery();
          int count = 9;
          while (r.next()) {
            assertEquals(count, r.getInt(1));
            count--;
          }
          assertEquals(0, count);
          r.close();
        }
      }

      {
        String query = "select c_tid, max(c_id * c_first) as largest_order from trade.customerrep where c_balance > ?  GROUP BY c_tid HAVING max(c_id*c_first) > ? ORDER BY max(c_id*c_first) DESC";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          st1.setInt(1, 20);
          st1.setInt(2, 20);
          ResultSet r = st1.executeQuery();
          int count = 9;
          while (r.next()) {
            assertEquals(count, r.getInt(1));
            count--;
          }
          assertEquals(0, count);
          r.close();
        }
      }

      {
        String query = "select c_tid, max(c_id * c_first) as largest_order from trade.customer where c_balance > ?  GROUP BY c_tid HAVING max(c_id*c_first) > ? ORDER BY max(c_id*c_first) DESC OFFSET 3 ROWS FETCH NEXT 2 ROWS ONLY";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          st1.setInt(1, 20);
          st1.setInt(2, 20);
          ResultSet r = st1.executeQuery();
          int count = 6;
          while (r.next()) {
            assertEquals(count, r.getInt(1));
            count--;
          }
          assertEquals(4, count);
          r.close();
        }
      }

      {
        String query = "select c_tid, max(c_id * c_first) as largest_order from trade.customerrep where c_balance > ?  GROUP BY c_tid HAVING max(c_id*c_first) > ? ORDER BY max(c_id*c_first) DESC OFFSET 3 ROWS FETCH NEXT 2 ROWS ONLY";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          st1.setInt(1, 20);
          st1.setInt(2, 20);
          ResultSet r = st1.executeQuery();
          int count = 6;
          while (r.next()) {
            assertEquals(count, r.getInt(1));
            count--;
          }
          assertEquals(4, count);
          r.close();
        }
      }

      {
        String query = "SELECT (CASE WHEN c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END), count(*) B FROM trade.customer GROUP BY case when c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END ORDER BY B DESC";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          int sum = 0;
          while (r.next()) {
            sum = sum + r.getInt(2);
            count++;
          }
          assertEquals(8, count);
          assertEquals(30, sum);
          r.close();
        }
      }

      {
        String query = "SELECT (CASE WHEN c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END), count(*) B FROM trade.customerrep GROUP BY case when c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END ORDER BY B DESC";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          int sum = 0;
          while (r.next()) {
            sum = sum + r.getInt(2);
            count++;
          }
          assertEquals(8, count);
          assertEquals(30, sum);
          r.close();
        }
      }

      {
        String query = "SELECT (CASE WHEN c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END), count(*) B FROM trade.customer GROUP BY case when c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END ORDER BY B DESC FETCH FIRST 5 ROWS ONLY";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          int sum = 0;
          while (r.next()) {
            sum = sum + r.getInt(2);
            count++;
          }
          assertEquals(5, count);
          assertEquals(21, sum);
          r.close();
        }
      }

      {
        String query = "SELECT (CASE WHEN c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END), count(*) B FROM trade.customerrep GROUP BY case when c_middle < c_next THEN c_middle||c_next ELSE c_next||c_middle END ORDER BY B DESC FETCH FIRST 5 ROWS ONLY";
        PreparedStatement st1 = conn.prepareStatement(query);
        for (int i = 0; i < 2; i++) {
          ResultSet r = st1.executeQuery();
          int count = 0;
          int sum = 0;
          while (r.next()) {
            sum = sum + r.getInt(2);
            count++;
          }
          assertEquals(5, count);
          assertEquals(21, sum);
          r.close();
        }
      }

      {
        Connection conn1 = TestUtil.getConnection();
        Statement st1 = conn1.createStatement();
        st1.execute("drop table trade.customer");
        st1.execute("drop table trade.customerrep");
        st1.execute("drop schema trade restrict");
      }
    } finally {
      TestUtil.shutDown();
    }
  }

  public void testBug48212_48355() throws Exception {
    // check for #48212 by bringing down the last server, then
    // bringing up a new server not having access to the disk store files
    // of the last server; then firing DDLs from new node should cause
    // DDL IDs to overlap as per #48212
    int locPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    startLocatorVM("localhost", locPort, null, null);

    Properties props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + locPort
        + "]");
    startVMs(1, 1, 0, null, props);

    serverSQLExecute(2, "CREATE TABLE t.t_1(name varchar(16) primary key, "
        + "remark varchar(20)) partition by primary key "
        + "persistent redundancy 1");

    startVMs(0, 1, 0, null, props);
    serverSQLExecute(3, "CREATE TABLE t.t_2(name varchar(16) primary key, "
        + "remark varchar(20)) partition by primary key "
        + "persistent redundancy 1");
    stopVMNums(1, -2, -3);
    stopVMNums(-1);

    // now delete the default diskstore files
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        String currentDir = new File(".").getAbsolutePath();
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setIncludes(new String[] { "*"
            + GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME + "*.*" });
        scanner.setBasedir(currentDir);
        scanner.setCaseSensitive(false);
        scanner.scan();
        for (String file : scanner.getIncludedFiles()) {
          new File(currentDir, file).delete();
        }
      }
    });

    restartLocatorVM(1, "localhost", locPort, null, null);
    //(1, "localhost", locPort, null, null);
    restartVMNums(new int[] { 1, -2 }, 0, null, props);
    startVMs(0, 1, 0, null, props);
    serverSQLExecute(4, "CREATE TABLE t.t_3(name varchar(16) primary key, "
        + "remark varchar(20)) partition by primary key "
        + "persistent redundancy 1");

    restartVMNums(new int[] { -3 }, 0, null, props);

    serverSQLExecute(3, "insert into t.t_1 values ('n1', 'r1')");
    serverSQLExecute(3, "insert into t.t_2 values ('n1', 'r1')");
    serverSQLExecute(3, "insert into t.t_3 values ('n1', 'r1')");
    serverSQLExecute(4, "insert into t.t_1 values ('n2', 'r2')");
    serverSQLExecute(4, "insert into t.t_2 values ('n2', 'r2')");
    serverSQLExecute(3, "insert into t.t_3 values ('n2', 'r2')");

    Statement stmt = TestUtil.getConnection().createStatement();
    ResultSet rs = stmt.executeQuery("select * from t.t_1 order by name");
    assertTrue(rs.next());
    assertEquals("n1", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("n2", rs.getString(1));
    assertFalse(rs.next());

    rs = stmt.executeQuery("select * from t.t_2 order by name");
    assertTrue(rs.next());
    assertEquals("n1", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("n2", rs.getString(1));
    assertFalse(rs.next());

    rs = stmt.executeQuery("select * from t.t_3 order by name");
    assertTrue(rs.next());
    assertEquals("n1", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("n2", rs.getString(1));
    assertFalse(rs.next());

    clientSQLExecute(1, "CREATE TABLE UNIQUE_SEGMENT_II_TEMP("
        + "UNIQUE_ID VARCHAR(50), CUSTOMER_TYPE VARCHAR(20),"
        + "UNIQUE_SEG_RANK INTEGER) REPLICATE");

    stmt.execute("insert into UNIQUE_SEGMENT_II_TEMP values ('U2', 'T2', 4)");
    stmt.execute("insert into UNIQUE_SEGMENT_II_TEMP values ('U1', 'T1', 1)");
    stmt.execute("insert into UNIQUE_SEGMENT_II_TEMP values ('U2', 'T2', 3)");
    stmt.execute("insert into UNIQUE_SEGMENT_II_TEMP values ('U1', 'T1', 2)");

    rs = stmt.executeQuery("SELECT UNIQUE_ID, CUSTOMER_TYPE, "
        + "UNIQUE_SEG_RANK FROM UNIQUE_SEGMENT_II_TEMP"
        + " ORDER BY UNIQUE_SEG_RANK");
    Object[][] expected = new Object[][] { { "U1", "T1", 1 },
        { "U1", "T1", 2 }, { "U2", "T2", 3 }, { "U2", "T2", 4 } };
    JDBC.assertFullResultSet(rs, expected, false);
  }

  public void testIdentityColumns() throws Throwable {

    // Bug #51832.
    if (isTransactional) {
      return;
    }

    reduceLogLevelForTest("config");

    Properties props = new Properties();
    props.setProperty("conserve-sockets",
        PartitionedRegion.rand.nextBoolean() ? "true" : "false");
    startVMs(1, 1, 0, null, props);

    serverSQLExecute(1, "CREATE TABLE t.t_1("
        + "id int generated by default as identity primary key, "
        + "remark varchar(20)) partition by primary key "
        + "redundancy 2 persistent");
    serverSQLExecute(1, "CREATE TABLE t.t_2("
        + "remark varchar(20), id2 bigint generated by default as identity "
        + "(start with 2, increment by 5) primary key) "
        + "partition by primary key redundancy 2 persistent");

    final int numInserts = 1000;
    final int numThreads = 100;
    final Thread[] threads = new Thread[numThreads];
    final Throwable[] failure = new Throwable[1];

    // warmup
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    PreparedStatement pstmt = conn
        .prepareStatement("insert into t.t_1(remark) values (?)");
    PreparedStatement pstmt2 = conn
        .prepareStatement("insert into t.t_2(remark) values (?)");
    for (int i = 1; i <= numInserts; i++) {
      pstmt.setString(1, "REM" + i);
      pstmt.executeUpdate();
      pstmt2.setString(1, "REM" + i);
      pstmt2.executeUpdate();
    }
    stmt.execute("drop table t.t_1");
    stmt.execute("CREATE TABLE t.t_1("
        + "id int generated by default as identity primary key, "
        + "remark varchar(20)) partition by primary key "
        + "persistent redundancy 2");
    stmt.execute("drop table t.t_2");
    stmt.execute("CREATE TABLE t.t_2("
        + "remark varchar(20), id2 bigint generated by default as identity "
        + "(start with 2, increment by 5) primary key) "
        + "partition by primary key redundancy 2 persistent");
    for (int i = 1; i <= numInserts; i++) {
      pstmt.setString(1, "REM" + i);
      pstmt.executeUpdate();
      pstmt2.setString(1, "REM" + i);
      pstmt2.executeUpdate();
    }
    stmt.execute("truncate table T.T_1");
    stmt.execute("truncate table T.T_2");

    // start more servers
    startVMs(0, 3, 0, null, props);
    stmt.execute("call sys.create_all_buckets('T.T_1')");
    stmt.execute("call sys.create_all_buckets('T.T_2')");

    // warmups for the new servers
    for (int i = 1; i <= numInserts; i++) {
      pstmt.setString(1, "REM" + i);
      pstmt.executeUpdate();
      pstmt2.setString(1, "REM" + i);
      pstmt2.executeUpdate();
    }
    stmt.execute("truncate table T.T_1");
    stmt.execute("truncate table T.T_2");
    stmt.execute("call sys.create_all_buckets('T.T_1')");
    stmt.execute("call sys.create_all_buckets('T.T_2')");

    Runnable doInserts1 = new Runnable() {
      @Override
      public void run() {
        try {
          Connection conn = TestUtil.getConnection();
          PreparedStatement pstmt = conn
              .prepareStatement("insert into t.t_1(remark) values (?)");
          for (int i = 1; i <= numInserts; i++) {
            pstmt.setString(1, "REM" + i);
            assertEquals(1, executeUpdate(pstmt, null));
          }
        } catch (Throwable t) {
          failure[0] = t;
        }
      }
    };
    Runnable doInserts2 = new Runnable() {
      @Override
      public void run() {
        try {
          Connection conn = TestUtil.getConnection();
          PreparedStatement pstmt = conn
              .prepareStatement("insert into t.t_2(remark) values (?)");
          for (int i = 1; i <= numInserts; i++) {
            pstmt.setString(1, "REM" + i);
            assertEquals(1, executeUpdate(pstmt, null));
          }
        } catch (Throwable t) {
          failure[0] = t;
        }
      }
    };

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(doInserts1);
    }
    long start = System.currentTimeMillis();
    for (int i = 0; i < numThreads; i++) {
      threads[i].start();
    }
    for (int i = 0; i < numThreads; i++) {
      threads[i].join();
    }
    getLogWriter().info(
        "Time taken " + (System.currentTimeMillis() - start) + "ms");
    if (failure[0] != null) {
      throw failure[0];
    }

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(doInserts2);
    }
    start = System.currentTimeMillis();
    for (int i = 0; i < numThreads; i++) {
      threads[i].start();
    }
    for (int i = 0; i < numThreads; i++) {
      threads[i].join();
    }
    getLogWriter().info(
        "Time taken " + (System.currentTimeMillis() - start) + "ms");
    if (failure[0] != null) {
      throw failure[0];
    }

    checkIdentityData(conn, numInserts * numThreads, 0, 0);

    // check after stopping server having identity column in the middle of ops

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(doInserts1);
    }
    for (int i = 0; i < numThreads; i++) {
      threads[i].start();
    }
    Thread.sleep(500);
    addExpectedException(null, new int[] { 1, 2 }, new Object[] {
        CacheClosedException.class, RegionDestroyedException.class,
        ForceReattemptException.class, BucketMovedException.class,
        PrimaryBucketException.class });
    stopVMNums(-1);
    for (int i = 0; i < numThreads; i++) {
      threads[i].join();
    }
    if (failure[0] != null) {
      throw failure[0];
    }
    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(doInserts2);
    }
    for (int i = 0; i < numThreads; i++) {
      threads[i].start();
    }
    Thread.sleep(500);
    stopVMNums(-2);
    for (int i = 0; i < numThreads; i++) {
      threads[i].join();
    }
    if (failure[0] != null) {
      throw failure[0];
    }

    checkIdentityData(conn, numInserts * numThreads, numInserts * numThreads,
        numThreads * 2);

    // now check after nodes hosting the keys are restarted
    stopVMNums(-3, -4);
    restartVMNums(-1, -2, -3, -4);

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(doInserts1);
    }
    start = System.currentTimeMillis();
    for (int i = 0; i < numThreads; i++) {
      threads[i].start();
    }
    for (int i = 0; i < numThreads; i++) {
      threads[i].join();
    }
    getLogWriter().info(
        "Time taken " + (System.currentTimeMillis() - start) + "ms");
    if (failure[0] != null) {
      throw failure[0];
    }

    for (int i = 0; i < numThreads; i++) {
      threads[i] = new Thread(doInserts2);
    }
    start = System.currentTimeMillis();
    for (int i = 0; i < numThreads; i++) {
      threads[i].start();
    }
    for (int i = 0; i < numThreads; i++) {
      threads[i].join();
    }
    getLogWriter().info(
        "Time taken " + (System.currentTimeMillis() - start) + "ms");
    if (failure[0] != null) {
      throw failure[0];
    }

    checkIdentityData(conn, numInserts * numThreads,
        numInserts * numThreads * 2, numThreads * 2);
  }

  private void checkIdentityData(Connection conn, int numInserts, int start,
      int possibleError) throws Exception {
    Statement stmt = conn.createStatement();
    final BitSetSet found = new BitSetSet(numInserts + start + possibleError);
    ResultSet rs = stmt.executeQuery("select * from t.t_1");
    int id;
    while (rs.next()) {
      id = rs.getInt(1);
      if (id > (numInserts + start + possibleError)) {
        fail("unexpected id=" + id);
      }
      if (!found.addInt(id - 1)) {
        fail("duplicate for " + id);
      }
    }
    int size = found.size();
    if (possibleError == 0) {
      assertEquals(numInserts + start, size);
    }
    else {
      assertTrue("unexpected size=" + size + " expected="
          + (numInserts + start) + " with error=" + possibleError,
          size >= (numInserts + start)
              && size <= (numInserts + start + possibleError));
    }

    final BitSetSet found2 = new BitSetSet(numInserts + start + possibleError);
    long id2, index2;
    rs = stmt.executeQuery("select * from t.t_2");
    while (rs.next()) {
      id2 = rs.getLong(2);
      index2 = (id2 - 2) / 5;
      if (index2 >= (numInserts + start + possibleError)) {
        fail("unexpected id=" + id2);
      }
      if (!found2.addInt((int)index2)) {
        fail("duplicate in id2 for " + id2);
      }
    }
    size = found2.size();
    if (possibleError == 0) {
      if (size != (numInserts + start)) {
        TIntArrayList missing = new TIntArrayList();
        // search for missing values
        for (int i = 0; i < numInserts + start; i++) {
          if (!found2.containsInt(i)) {
            missing.add(i);
          }
        }
        fail("unexpected size=" + size + " expected=" + (numInserts + start)
            + " with error=" + possibleError + ", missing=" + missing);
      }
    }
    else {
      assertTrue("unexpected size=" + size + " expected="
          + (numInserts + start) + " with error=" + possibleError,
          size >= (numInserts + start)
              && size <= (numInserts + start + possibleError));
    }
  }

  private String getSymbol(int minLength, int maxLength) {
    int aVal = 'a';
    Random rand = new Random();
    int symbolLength = rand.nextInt(maxLength - minLength + 1) + minLength;
    char[] charArray = new char[symbolLength];
    for (int j = 0; j < symbolLength; j++) {
      charArray[j] = (char)(rand.nextInt(26) + aVal); // one of the char in
                                                      // a-z
    }

    return new String(charArray);
  }

  private void checkMergeResults(CallableStatement cs) throws SQLException {
    ResultSet rs = cs.getResultSet();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    assertEquals(3, rs.getInt(2));
    assertFalse(rs.next());
  }


  public void test48808() throws Exception {
    reduceLogLevelForTest("config");
    startVMs(1, 3);
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TYPE trade.UDTPrice "
        + "EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    stmt.execute("create function trade.getLowPrice(DP1 trade.UDTPrice) "
        + "RETURNS NUMERIC PARAMETER STYLE JAVA LANGUAGE JAVA NO SQL "
        + "EXTERNAL NAME 'udtexamples.UDTPrice.getLowPrice'");
    stmt.execute("create function trade.createPrice(low NUMERIC(6,2), "
        + "high NUMERIC(6,2)) RETURNS trade.UDTPrice PARAMETER STYLE JAVA "
        + "LANGUAGE JAVA NO SQL EXTERNAL NAME 'udtexamples.UDTPrice.setPrice'");
    stmt.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null, companytype smallint, "
        + "companyname char(100), companyinfo clob, "
        + "note long varchar, histprice trade.udtprice, tid int,"
        + "constraint comp_pk primary key (companyname)) "
        + "partition by column(histprice)");
    PreparedStatement pstmt = conn.prepareStatement("insert into " +
                "trade.companies values (?,?,?,?,?,?,?,?)");
    final UDTPrice udt = new UDTPrice(new BigDecimal("27.58"), new BigDecimal(
        "37.58"));
    for (int i = 1; i < 20; i++) {
      pstmt.setString(1, "sym" + i);
      pstmt.setString(2, "e" + i);
      pstmt.setInt(3, i);
      pstmt.setString(4, "c" + i);
      pstmt.setString(5, "ci" + i);
      pstmt.setString(6, "Q" + i + "\"6>$?3-D=T");
      pstmt.setObject(7, udt);
      pstmt.setInt(8, i * 2);
      assertEquals(1, pstmt.executeUpdate());
    }
    stmt.execute("call sys.rebalance_all_buckets()");

    pstmt = conn.prepareStatement("update trade.companies set tid=? "
        + "where histprice=? and symbol=?");
    for (int i = 1; i < 20; i++) {
      pstmt.setInt(1, i);
      pstmt.setObject(2, udt);
      pstmt.setString(3, "sym" + i);
      assertEquals("failed for i=" + i, 1, pstmt.executeUpdate());
    }
    pstmt = conn.prepareStatement("delete from "
        + "trade.companies where tid=? and trade.getLowPrice(histPrice) <=? "
        + "and note like ? and companyType = ?");
    for (int i = 1; i < 20; i++) {
      pstmt.setInt(1, i);
      pstmt.setBigDecimal(2, new BigDecimal("27.58"));
      pstmt.setString(3, "%" + i + "\"%");
      pstmt.setInt(4, i);
      assertEquals("failed for i=" + i, 1, pstmt.executeUpdate());
    }
    ResultSet rs = stmt.executeQuery("select * from trade.companies");
    JDBC.assertEmpty(rs);
  }


  public void test47662() throws Exception {
    //reduceLogLevelForTest("config");
    startVMs(0, 2);
    int netPort = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();
    TestUtil.deletePersistentFiles = true;
    Connection conn;
    final Properties connProps = new Properties();
    connProps.setProperty("load-balance", "false");
    String url = TestUtil.getNetProtocol("localhost", netPort);
    conn = DriverManager.getConnection(url, connProps);

    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.txhistory (cid int, sid int, tid int)  "
        + "partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000)  "
        + "REDUNDANCY 1");

    PreparedStatement ps = conn.prepareStatement("insert into trade.txhistory values (?,?,?)");
    for(int i = 0; i < 10; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setInt(3, i);
      int effected = ps.executeUpdate();
      assertEquals(1, effected);
    }

    ps.close();
    conn.close();

    stopVMNums(-1);
    restartVMNums(-1);
    netPort = startNetworkServer(1, null, null);

    stopVMNums(-2);
    restartVMNums(-2);
    startNetworkServer(2, null, null);

    url = TestUtil.getNetProtocol("localhost", netPort);
    conn = DriverManager.getConnection(url, connProps);
    ps = conn.prepareStatement("insert into trade.txhistory values (?,?,?)");

    for(int i = 0; i < 10; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setInt(3, i);
      int effected = ps.executeUpdate();
      assertEquals(1, effected);
    }
  }

  /**
   *    * Test for verification of transaction is still active exception
   *       * when the statement is prepared but not executed and connection is closed.
   *          *
   *             * @throws Exception
   *                */
   public void testBug49621() throws Exception {

     startVMs(0, 1);

    // Start a network server
    final int netPort = startNetworkServer(1, null, null);

    // Use this VM as the network client
    final Connection conn = TestUtil.getNetConnection(netPort, null, null);

    // Create a table
    Statement stmt = conn.createStatement();
    stmt.execute("create table testBug49621(id int)");

    // get another connection
    final Connection conn2 = TestUtil.getNetConnection(netPort, null, null);

    //create prepared statement and close the connection
    PreparedStatement ps = conn2.prepareStatement("insert into testBug49621 values(?)");
    ps.close();
    conn2.close();

    // drop the table and close the connection
    stmt.execute("drop table testBug49621");
    stmt.close();
    conn.close();
   }

  public void testBug51249() throws Exception {
    final Properties authProp = new Properties();
    authProp.setProperty(Property.GFXD_AUTH_PROVIDER,
        com.pivotal.gemfirexd.Constants.AUTHENTICATION_PROVIDER_BUILTIN);
    authProp.setProperty(com.pivotal.gemfirexd.Attribute.TABLE_DEFAULT_PARTITIONED, "false");
    final String sysUser = "USECASE11_SYSTEM_USER";
    final String sysPwd = "USECASE11_SYSTEM_USER_PWD";
    try {
      authProp.setProperty(Property.USER_PROPERTY_PREFIX + sysUser,
          AuthenticationServiceBase.encryptPassword(sysUser, sysPwd));
      authProp.setProperty("user", sysUser);
      authProp.setProperty("password", sysPwd);
    } catch (StandardException se) {
      throw new IllegalArgumentException(se);
    }

    invokeInEveryVM(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        TestUtil.bootUserName = sysUser;
        TestUtil.bootUserPassword = sysPwd;
        return null;
      }
    });

    int locPort1 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    startLocatorVM("localhost", locPort1, null, authProp);

    int locPort2 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    startLocatorVM("localhost", locPort2, null, authProp);

    Properties props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + locPort1
        + "]");
    props.putAll(authProp);
    props.setProperty("log-level", "info");
    startVMs(1, 2, 0, null, props);

    // Start a network server
    final int netPort1 = startNetworkServer(1, null, props);
    final int netPort2 = startNetworkServer(2, null, props);
    final int netPort3 = startNetworkServer(3, null, props);
    final int netPort4 = startNetworkServer(4, null, props);

    // Use this VM as the network client
    final Connection conn = TestUtil.getNetConnection(netPort1, null, props);

    // Create a table
    final String basePath = TestUtil.getResourcesDir()
        + "/lib/useCase11/51249/";
    final String ddls = basePath + "/ddl";

    final File ddlFiles = new File(ddls);

    assertTrue(ddlFiles.exists() && ddlFiles.isDirectory());

    final File[] files = ddlFiles.listFiles();
    Arrays.sort(files, new Comparator<File>() {

      @Override
      public int compare(File o1, File o2) {
        return o1.getName().compareTo(o2.getName());
      }
    });

    final String[] scripts = new String[files.length];
    int i = 0;
    for (File ddl : files) {
      getLogWriter().info("Picking up " + ddl.getAbsolutePath());
      scripts[i++] = ddl.getAbsolutePath();
    }

    GemFireXDUtils.executeSQLScripts(conn, scripts, false, getLogWriter(),
        null, null, true);

    getLogWriter().info("About to import data that takes around one minute.");
    final ByteArrayOutputStream output = new ByteArrayOutputStream(20 * 1024);
    try {
      MiscTools.outputStream = new PrintStream(output);
      MiscTools.main(new String[] { "run", "-path=" + basePath + "/data/",
          "-file=" + basePath + "/queries/import.sql",
          "-client-port=" + netPort1, "-client-bind-address=localhost",
          "-user=" + sysUser, "-password=" + sysPwd });
      getLogWriter().info(output.toString());
    } finally {
      MiscTools.outputStream = null;
    }

    GemFireXDUtils.executeSQLScripts(conn, new String[] {basePath + "/queries/queryOk_1.sql"}, false, getLogWriter(),
        null, null, true);
    GemFireXDUtils.executeSQLScripts(conn, new String[] {basePath + "/queries/queryOk_2.sql"}, false, getLogWriter(),
        null, null, true);
    GemFireXDUtils.executeSQLScripts(conn, new String[] {basePath + "/queries/queryOk_3.sql"}, false, getLogWriter(),
        null, null, true);

    GemFireXDUtils.executeSQLScripts(conn, new String[] {basePath + "/queries/queryFail.sql"}, false, getLogWriter(),
        null, null, true);

  }

  public void testBugGEMXD_1() throws Exception {
    reduceLogLevelForTest("config");
    // Start a client and some server VMs
    startVMs(1, 3);

    // create a table with primary key, then a diskstore,
    // then a table without primary key in that disk store
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("CREATE TABLE t1 (c1 int primary key, c2 int not null)" +
        " partition by primary key persistent asynchronous");
    stmt.execute("CREATE DISKSTORE ds1");
    stmt.execute("CREATE TABLE t2 (c1 int not null, c2 int not null)" +
        " replicate persistent 'ds1'");

    // some inserts
    for (int i = 1; i <= 5; i++) {
      stmt.execute("insert into t1 values (" + i + ", " + (i + 1) + ')');
      stmt.execute("insert into t2 values (" + i + ", " + (i + 1) + ')');
    }

    Object[][] expected = new Object[][] {
        new Object[] { 1, 2 },
        new Object[] { 2, 3 },
        new Object[] { 3, 4 },
        new Object[] { 4, 5 },
        new Object[] { 5, 6 },
    };

    ResultSet rs;
    rs = stmt.executeQuery("select * from t1");
    JDBC.assertUnorderedResultSet(rs, expected, false);
    rs = stmt.executeQuery("select * from t2");
    JDBC.assertUnorderedResultSet(rs, expected, false);

    // now restart to check recovery with diskstore creation between regions
    stopVMNums(-1, -2, -3);
    // recover with values sync to force using crf
    invokeInEveryVM(new SerializableRunnable("set recoverValuesSync") {
      @Override
      public void run() {
        System.setProperty(DiskStoreImpl.RECOVER_VALUES_SYNC_PROPERTY_NAME,
            "true");
      }
    });
    try {
      restartVMNums(-1, -2, -3);

      // check again
      rs = stmt.executeQuery("select * from t1");
      JDBC.assertUnorderedResultSet(rs, expected, false);
      rs = stmt.executeQuery("select * from t2");
      JDBC.assertUnorderedResultSet(rs, expected, false);

      conn.close();
    } finally {
      invokeInEveryVM(new SerializableRunnable("clear recoverValuesSync") {
        @Override
        public void run() {
          System
              .clearProperty(DiskStoreImpl.RECOVER_VALUES_SYNC_PROPERTY_NAME);
        }
      });
    }
  }

  public void test45995() throws Exception {
//    reduceLogLevelForTest("fine");
    startVMs(1, 2);
    VM serverVM1 = serverVMs.get(0);
    VM serverVM2 = serverVMs.get(1);
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("CREATE TABLE T1(COL1 INT, COL2 INT)");

    PreparedStatement ps = conn.prepareStatement("INSERT INTO T1 VALUES (?, ?)");
    for (int i = 1; i <= 100; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.execute();
    }

    try {
      serverVM1.invoke(this.getClass(), "raiseCriticalUp");
      serverVM2.invoke(this.getClass(), "raiseCriticalUp");
      stmt.execute("delete from t1 where col1 > 0");
    } finally {
      serverVM1.invoke(this.getClass(), "resetResourceManager");
      serverVM2.invoke(this.getClass(), "resetResourceManager");
    }
    //ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM T1");
    //assertTrue(rs.next());
    //assertEquals(0, rs.getInt(1));
    conn.close();
  }

  public static void raiseCriticalUp() {
    InternalResourceManager resMgr = Misc.getGemFireCache().getResourceManager();
//    HeapMemoryMonitor.setTestDisableMemoryUpdates(true);
    resMgr.getHeapMonitor().setTestMaxMemoryBytes(100);
    HeapMemoryMonitor.setTestBytesUsedForThresholdSet(92);
    resMgr.setCriticalHeapPercentage(90F);
    resMgr.getHeapMonitor().updateStateAndSendEvent(92);
  }

  public static void resetResourceManager() {
    FabricService fs = FabricServiceImpl.getInstance();
    if (fs != null) {
      GemFireCacheImpl gfCache = Misc.getGemFireCacheNoThrow();
      if (gfCache != null) {
//        HeapMemoryMonitor.setTestDisableMemoryUpdates(false);
        InternalResourceManager resMgr = gfCache.getResourceManager();
        resMgr.getHeapMonitor().setTestMaxMemoryBytes(0);
        resMgr.getHeapMonitor().updateStateAndSendEvent(10);
      }
    }

  }

  public void test51906() throws Exception {
    startVMs(1, 1);
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("CREATE TABLE T1 (COL1 INT NOT NULL PRIMARY KEY, " +
        "COL2 VARCHAR(10), COL3 CHAR(10), COL4 INT NOT NULL)");
    stmt.execute("INSERT INTO T1 VALUES(1, '1', '11', 1)");
    stmt.execute("UPDATE T1 SET COL2 = COL3 WHERE COL1 = 1");
    ResultSet rs = stmt.executeQuery("SELECT COL2 FROM T1");
    assertTrue(rs.next());
    assertEquals("11", rs.getString(1).trim());
    conn.close();
  }

  class Inserter implements Runnable {
    final private int baseval;
    final private int numRecords;
    final String[] cities;
    final String[] states;
    final Exception[] exceptions;

    public Inserter(int base, int numR, String[] cities, String[] states, Exception[] exceptions) {
      this.baseval = base;
      this.numRecords = numR;
      this.cities = cities;
      this.states = states;
      this.exceptions = exceptions;
    }

    @Override
    public void run() {
      Connection conn = null;
      try {
        conn = TestUtil.getConnection();
        insertNRecords(conn, this.numRecords, cities, states, this.baseval);
      } catch (SQLException e) {
        exceptions[0] = e;
      } finally {
        try {
          conn.close();
        } catch (SQLException e) {
          // ignore
        }
      }
    }
  }

  class ServerDowner implements Runnable {
    final private int vmNum;
    final Exception[] exceptions;

    public ServerDowner(int vmn, Exception[] exceptions) {
      this.vmNum = vmn;
      this.exceptions = exceptions;
    }
    @Override
    public void run() {
      sleepForMs(2000);
      try {
        BugsDUnit.this.stopVMNums(new int[]{-this.vmNum});
      } catch (Exception e) {
        exceptions[0] = e;
      }
    }
  }

  public static class TombstoneServiceConfigKeeper {
    final long oldServerTimeout = TombstoneService.REPLICATED_TOMBSTONE_TIMEOUT;
    final long oldClientTimeout = TombstoneService.CLIENT_TOMBSTONE_TIMEOUT;
    final long oldExpiredTombstoneLimit = TombstoneService.EXPIRED_TOMBSTONE_LIMIT;
    final boolean oldIdleExpiration = TombstoneService.IDLE_EXPIRATION;

    private static TombstoneServiceConfigKeeper _instance = null;
    // Thread safety is not required for this singleton
    public static TombstoneServiceConfigKeeper getInstance() {
      if (_instance == null) {
        _instance = new TombstoneServiceConfigKeeper();
      }
      return _instance;
    }

    public void setTestValues(long rtt, long ctt, long etl, boolean ie ) {
      TombstoneService.REPLICATED_TOMBSTONE_TIMEOUT = rtt;
      TombstoneService.CLIENT_TOMBSTONE_TIMEOUT = ctt;
      TombstoneService.EXPIRED_TOMBSTONE_LIMIT = etl;
      TombstoneService.IDLE_EXPIRATION = ie;
    }

    public void resetOldVAlues() {
      TombstoneService.REPLICATED_TOMBSTONE_TIMEOUT = oldServerTimeout;
      TombstoneService.CLIENT_TOMBSTONE_TIMEOUT = oldClientTimeout;
      TombstoneService.EXPIRED_TOMBSTONE_LIMIT = oldExpiredTombstoneLimit;
      TombstoneService.IDLE_EXPIRATION = oldIdleExpiration;
    }
  }

  public static void setNewTombstoneConfigs(long rtt, long ctt, long etl, boolean ie) {
    TombstoneServiceConfigKeeper tsck = TombstoneServiceConfigKeeper.getInstance();
    tsck.setTestValues(rtt, ctt, etl, ie);
  }

  public static void resetTombstoneConfigs() {
    TombstoneServiceConfigKeeper tsck = TombstoneServiceConfigKeeper.getInstance();
    tsck.resetOldVAlues();
  }

  public static void skipIndexCheck(boolean flag) {
    FabricDatabase.skipIndexCheck = flag;
  }

  final String[] cities = {"New York", "Baltimore", "Kentucky", "Las Vegas", "Los Angeles", "Detroit", "denver", " New Jersey"};
  final String[] states = {"State 1", "sTate2", "state3", "state4", "state5"};
  String addrtab = "Create table ODS.POSTAL_ADDRESS(" +
      "  cntc_id bigint NOT NULL," +
      "  pstl_addr_id bigint GENERATED BY DEFAULT AS IDENTITY  NOT NULL," +
      "  ver bigint NOT NULL," +
      "  client_id bigint NOT NULL," +
      "  str_ln1 varchar(100)," +
      "  str_ln2 varchar(100)," +
      "  str_ln3 varchar(100)," +
      "  cty varchar(75)," +
      "  cnty varchar(50)," +
      "  st varchar(50)," +
      "  pstl_cd varchar(20)," +
      "  cntry varchar(100)," +
      "  vldtd SMALLINT," +
      "  vldtn_dt DATE," +
      "  vld_frm_dt TIMESTAMP NOT NULL," +
      "  vld_to_dt TIMESTAMP," +
      "  src_sys_ref_id varchar(10) NOT NULL," +
      "  src_sys_rec_id varchar(150)," +
      "  PRIMARY KEY (client_id,cntc_id,pstl_addr_id)" +
      "  )" +
      "  PARTITION BY COLUMN (cntc_id)" +
      "  REDUNDANCY 1" +
      "  BUCKETS 1" +
      "  EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW PERSISTENT";

  final String idx1 =
      "CREATE INDEX IX_POSTAL_ADDRESS_01 ON ODS.POSTAL_ADDRESS ( CNTC_ID, CLIENT_ID )";
  final String idx2 =
      "CREATE INDEX IX_POSTAL_ADDRESS_02 ON ODS.POSTAL_ADDRESS (CTY, CLIENT_ID) " +
          "-- GEMFIREXD-PROPERTIES caseSensitive=false";
  final String idx3 =
      "CREATE INDEX IX_POSTAL_ADDRESS_03 ON ODS.POSTAL_ADDRESS (ST, CLIENT_ID) " +
          "-- GEMFIREXD-PROPERTIES caseSensitive=false";

  final String diag1primaries = "select count(*), dsid() from sys.members m " +
      "--GEMFIREXD-PROPERTIES withSecondaries=false\n , ods.postal_address where dsid() = m.id group by dsid()";
  final String diag2withSecondaries = "select count(*), dsid() from sys.members m " +
      "--GEMFIREXD-PROPERTIES withSecondaries=true\n , ods.postal_address where dsid() = m.id group by dsid()";
  final String diag3idx1 = "select count(*), dsid() from sys.members m , ods.postal_address " +
      "--GEMFIREXD-PROPERTIES index=IX_POSTAL_ADDRESS_01\n where dsid() = m.id group by dsid()";
  final String diag4idx2 = "select count(*), dsid() from sys.members m , ods.postal_address " +
      "--GEMFIREXD-PROPERTIES index=IX_POSTAL_ADDRESS_02\n where dsid() = m.id group by dsid()";
  final String diag5idx3 = "select count(*), dsid() from sys.members m , ods.postal_address " +
      "--GEMFIREXD-PROPERTIES index=IX_POSTAL_ADDRESS_03\n where dsid() = m.id group by dsid()";


  public void testLongTimeOfflineIndexCorruption() throws Exception {
    // Start 2 vms so that 1 bucket is each on primary and secondary
    try {
      invokeInEveryVM(BugsDUnit.class, "setNewTombstoneConfigs", new Object[] { 5000L, 7000L, 1L, Boolean.TRUE});
      invokeInEveryVM(BugsDUnit.class, "skipIndexCheck", new Object[] { Boolean.TRUE});
      startVMs(1, 2);

      Connection conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();

      stmt.execute("create schema ODS");
      stmt.execute(addrtab);
      stmt.execute(idx1);
      stmt.execute(idx2);
      stmt.execute(idx3);

      final int baseVal = 2000000;
      final int numRecordsPerThread = 10;

      final Exception[] exceptions = new Exception[1];
      final Exception[] exceptions2 = new Exception[1];

      int numThreads = 1;
      int totRecords = numRecordsPerThread * numThreads;
      new Inserter(baseVal, numRecordsPerThread, cities, states, exceptions).run();

      stmt.execute("select * from ODS.POSTAL_ADDRESS where cnty = 'eight1' or cnty = 'eight2' or cnty = 'eight3'");

      ResultSet rs = stmt.getResultSet();
      Object[][] delrows = new Object[3][5];
      // collect values of index columns ... CNTC_ID, CLIENT_ID, CTY and ST .. for rows which are going to be deleted
      // ( CNTC_ID, CLIENT_ID )";
      // (CTY, CLIENT_ID) -- GEMFIREXD-PROPERTIES caseSensitive=false";
      // (ST, CLIENT_ID) -- GEMFIREXD-PROPERTIES caseSensitive=false";
      int row = 0;
      while (rs.next()) {
        delrows[row][0] = rs.getObject(1);
        delrows[row][1] = rs.getObject(2);
        delrows[row][2] = rs.getObject(4);
        delrows[row][3] = rs.getObject(8);
        delrows[row][4] = rs.getObject(10);
        row++;
        getLogWriter().info("going to be deleted: " + rs.getObject(1) + ", " + rs.getObject(2) +
            ", " + rs.getObject(4) + ", " + rs.getObject(8) + ", " + rs.getObject(10) + ", ");
      }

      // bring one server down
      new ServerDowner(2, exceptions2).run();

      // Now bring one new server up
      startServerVMs(1, 0, null, null);

      // sleep for  sometime
      // sleepForMs(30000);
      GfxdSystemProcedures.REBALANCE_ALL_BUCKETS();
      // check if the redundancy of PR is satisfied
      PartitionedRegion pr = (PartitionedRegion)Misc.getRegionByPath("/ODS/POSTAL_ADDRESS");
      assertNotNull(pr);
      pr.getRegionAdvisor().getBucketAdvisor(0).waitForRedundancy(1);

      // Now delete few entries 3 to be precise
      int numDeletes = stmt.executeUpdate("delete from ODS.POSTAL_ADDRESS where " +
          "cnty = 'eight1' or cnty = 'eight2' or cnty = 'eight3'");
      assertEquals(3, numDeletes);
      // Now bring back the downed server after a long time
      sleepForMs(30000);
      AsyncVM asyncVM = BugsDUnit.this.restartServerVMAsync(2, 0, (String)null, null);
      BugsDUnit.this.joinVM(true, asyncVM);

      // Now bring down the first server
      new ServerDowner(1, exceptions2).run();

      // wait for recovery to complete on restarted server 2
      GfxdSystemProcedures.REBALANCE_ALL_BUCKETS();

      // At this point a bucket should each be on the first new server and the restarted server

      stmt.execute("select count(*) from ODS.POSTAL_ADDRESS");

      rs = stmt.getResultSet();
      assertTrue(rs.next());
      int cntres = rs.getInt(1);
      int totLeftRecords = totRecords - numDeletes;

      stmt.execute("select * from ODS.POSTAL_ADDRESS");
      rs = stmt.getResultSet();
      while(rs.next()) {
        getLogWriter().info("After delete Remaining: " + rs.getObject(1) +
            ", " + rs.getObject(2) + ", " + rs.getObject(4) + ", " + rs.getObject(8) + ", " + rs.getObject(10) + ", ");
      }
      assertEquals(totLeftRecords, cntres);

      invokeInEveryVM(new SerializableRunnable() {
        @Override
        public void run() {
          if (GemFireCacheImpl.getInstance() != null &&
              ServerGroupUtils.isDataStore()) {
            String regionPath = "/ODS/POSTAL_ADDRESS";
            Region r = Misc.getRegionByPath(regionPath);
            PartitionedRegion pr = (PartitionedRegion)r;
            pr.dumpAllBuckets(false, Misc.getI18NLogWriter());
            GfxdIndexManager idxmgr = (GfxdIndexManager)pr.getIndexUpdater();
            idxmgr.dumpAllIndexes();
          }
        }
      });

      // Check sanity of indexes and primary secondaries
      stmt.execute(diag1primaries);
      rs = stmt.getResultSet();
      int numRecordsTot = 0;
      while(rs.next()) {
        numRecordsTot += rs.getInt(1);
      }

      stmt.execute(diag2withSecondaries);
      rs = stmt.getResultSet();
      int numRecordsTotIncludingSec = 0;
      while(rs.next()) {
        numRecordsTotIncludingSec += rs.getInt(1);
      }

      stmt.execute(diag3idx1);
      rs = stmt.getResultSet();
      int numRecordsTotIncludingSec_idx1 = 0;
      while(rs.next()) {
        numRecordsTotIncludingSec_idx1 += rs.getInt(1);
      }

      stmt.execute(diag4idx2);
      rs = stmt.getResultSet();
      int numRecordsTotIncludingSec_idx2 = 0;
      while(rs.next()) {
        numRecordsTotIncludingSec_idx2 += rs.getInt(1);
      }

      stmt.execute(diag5idx3);
      rs = stmt.getResultSet();
      int numRecordsTotIncludingSec_idx3 = 0;
      while(rs.next()) {
        numRecordsTotIncludingSec_idx3 += rs.getInt(1);
      }

      getLogWriter().info("Total number of records inserted = " + totRecords);
      getLogWriter().info("Total number of records left = " + totLeftRecords);
      getLogWriter().info("Total number of records from diag1 primaries " + numRecordsTot);
      getLogWriter().info("Total number of records from diag2 including secondaries " + numRecordsTotIncludingSec);
      getLogWriter().info("Total number of records from diag3 index1 " + numRecordsTotIncludingSec_idx1);
      getLogWriter().info("Total number of records from diag4 index2 " + numRecordsTotIncludingSec_idx2);
      getLogWriter().info("Total number of records from diag5 index3 " + numRecordsTotIncludingSec_idx3);

      // switch on after bug fix and remove the multiplication line
      if (totLeftRecords*2 != numRecordsTotIncludingSec) {
        fail("totLeftRecords*2 != numRecordsTotIncludingSec");
      }

      // Switch ON after bug fix

      if ((numRecordsTotIncludingSec_idx1 != numRecordsTotIncludingSec)
          || (numRecordsTotIncludingSec != numRecordsTotIncludingSec_idx2)
          || ( numRecordsTotIncludingSec != numRecordsTotIncludingSec_idx3)) {
        fail("totRecords*2 != numRecords in the indexes");
      }
      

      if ((numRecordsTotIncludingSec_idx1 != numRecordsTotIncludingSec_idx2)
          || ( numRecordsTotIncludingSec_idx2 != numRecordsTotIncludingSec_idx3)) {
        fail("different index counts");
      }

      new ServerDowner(3, exceptions2).run();
      // The only node to which the count query can go now is the restarted faulty node
      // Lets get the count specific to the records deleted
      // delete from ODS.POSTAL_ADDRESS WHERE CNTC_ID=? and PSTL_ADDR_ID=? and CLIENT_ID=?;
      getLogWriter().info("selecting the deleted record again for cntc_id = "
          + delrows[0][0] + ", PSTL_ADDR_ID = " + delrows[0][1] + ", client_id = " + delrows[0][2]);

      PreparedStatement psSelect = conn.prepareStatement
          ("select count(*) from ODS.POSTAL_ADDRESS WHERE CNTC_ID=? and CLIENT_ID=?");
      psSelect.setObject(1, delrows[0][0]);
      psSelect.setObject(2, delrows[0][2]);
      psSelect.execute();
      //
      rs = psSelect.getResultSet();
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));

      // stop the newly created vm and start a new vm again so that the restarted becomes the primary bucket
      //new ServerDowner(3, exceptions2).run();

      asyncVM = BugsDUnit.this.restartServerVMAsync(1, 0, (String)null, null);
      BugsDUnit.this.joinVM(true, asyncVM);

      // fire the delete on a non existent record but which should be there in the bad vm
      PreparedStatement psDel = conn.prepareStatement
          ("delete from ODS.POSTAL_ADDRESS WHERE CNTC_ID=? and PSTL_ADDR_ID=?");
      psDel.setObject(1, delrows[0][0]);
      psDel.setObject(2, delrows[0][1]);
      //psDel.setObject(3, delrows[0][2]);
      int del = psDel.executeUpdate();
      assertEquals(0, del);
      //
      // get all the possible combination of CNTC_ID, PSTL_ADDR_ID and CLIENT_ID
      // Use select * so that no indexes are used
      String qry = "select * from ODS.POSTAL_ADDRESS";
      long[] cntcs  = new long[totLeftRecords];
      long[] paids  = new long[totLeftRecords];
      long[] clntds = new long[totLeftRecords];

      stmt.execute(qry);
      rs = stmt.getResultSet();
      int i=0;
      while(rs.next()) {
        cntcs[i] = rs.getLong(1);
        paids[i] = rs.getLong(2);
        clntds[i] = rs.getLong(4);
        i++;
      }

      String deleteStmnt = "delete from ODS.POSTAL_ADDRESS WHERE CNTC_ID=? and PSTL_ADDR_ID=? and CLIENT_ID=?";
      PreparedStatement dps = conn.prepareCall(deleteStmnt);
      for (i=0; i<totLeftRecords; i++) {
        dps.setLong(1, cntcs[i]);
        dps.setLong(2, paids[i]);
        dps.setLong(3, clntds[i]);
        dps.executeUpdate();
      }
      stmt.execute("select count(*) from ODS.POSTAL_ADDRESS");
      rs = stmt.getResultSet();
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 0);
      conn.close();
    } finally {
      // reset tombstone config values
      invokeInEveryVM(BugsDUnit.class, "resetTombstoneConfigs");
      invokeInEveryVM(BugsDUnit.class, "skipIndexCheck", new Object[] { Boolean.FALSE});
    }
  }

  private static void insertNRecords(Connection conn, int n,
      String[] cities, String[] states, int client_id_base_val) throws SQLException {
    long randStart = client_id_base_val;
    long randEnd = client_id_base_val + 2 * n;
    Random rand = new Random();
    int citylen = cities.length;
    int statelen = states.length;
    PreparedStatement ps = conn.prepareStatement("insert into ODS.POSTAL_ADDRESS " +
        "(cntc_id ,ver ,client_id ,str_ln1,str_ln2,str_ln3, cty ,cnty , st, pstl_cd, " +
        "cntry, vldtd, vldtn_dt, vld_frm_dt, vld_to_dt, src_sys_ref_id, src_sys_rec_id)" +
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
    for (int i=0; i<n ; i++) {
      int ctyidx = rand.nextInt(citylen);
      int stateidx = rand.nextInt(statelen);
      int offset = rand.nextInt(10);
      //ps.setLong(1, client_id_base_val+offset );
      ps.setLong(1, client_id_base_val+i );
      ps.setLong(2, offset+2);
      ps.setLong(3, offset+3);
      ps.setString(4, "four"+i);
      ps.setString(5, "five"+i);
      ps.setString(6, "six"+i);
      String city = cities[ctyidx];
      if ( i%10 == 0) {
        city = city.toUpperCase();
      }
      if ( i%15 == 0) {
        city = " " + city;
      }
      if ( i%100 == 0) {
        city = city + " ";
      }
      if (i%31 == 0) {
        city = null;
      }
      if (city == null) {
        ps.setNull(7, Types.VARCHAR);
      }
      else {
        ps.setString(7, city); // city
      }

      ps.setString(8, "eight"+i);
      String state = states[stateidx];
      if ( i%11 == 0) {
        state = state.toUpperCase();
      }
      if ( i%21 == 0) {
        state = " " + state;
      }
      if ( i%50 == 0) {
        state = state + "  ";
      }
      ps.setString(9, state); // st
      ps.setString(10, "ten"+i);
      ps.setString(11, "eleven"+i);
      ps.setShort(12, (short)i);
      ps.setString(13, "2016-05-18");
      ps.setString(14, "2016-05-18 22:11:10");
      ps.setString(15, "2016-05-18 22:11:10");
      ps.setString(16, "sixteen");
      ps.setString(17, "seventeen");
      ps.executeUpdate();
    }
  }
}
