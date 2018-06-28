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

package com.pivotal.gemfirexd;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.sql.*;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;
import org.junit.Assert;

/**
 * Checks for backward compatibility amongst various GemFireXD minor versions.
 * 
 * Currently checks for:
 * 
 * a) persisted DataDictionary files,
 * 
 * b) persisted data files
 * 
 * c) clients working against newer servers
 * 
 * @author swale
 */
@SuppressWarnings("serial")
public class BackwardCompatabilityDUnit extends BackwardCompatabilityTestBase {

  private static final String[] DDLS = new String[] {
      "create table t.t1 (id int, addr varchar(100)) persistent",
      "create index t.idx1 on t.t1 (id)",
      "create table t.t2 (id int primary key, addr varchar(100))",
      "create table t.t3 (id int primary key, addr varchar(100)) persistent "
          + "partition by primary key",
      "create table t.t4 (id int, addr varchar(100)) replicate",
      "drop table t.t4",
      "create table t.t4 (id int primary key generated always as identity, "
          + "addr varchar(100)) persistent",
      "create index t.idx on t.t1 (addr)",
      "drop index t.idx",
      "create index t.idx on t.t2 (addr)",
      "drop index t.idx",
      "create index t.idx on t.t3 (addr)",
      "drop index t.idx",
      "create index t.idx on t.t4 (addr)"
  };

  private static final String[] DMLS = new String[] {
    "insert into t.t1 values (?, ?)",
    "insert into t.t2 values (?, ?)",
    "insert into t.t3 values (?, ?)",
    "insert into t.t4 (addr) values (?)",
  };

  private static final Object[][] TABLES = new Object[][] {
    { "t.t1", true, 0 },
    { "t.t2", false, 0 },
    { "t.t3", true, 0 },
    { "t.t4", true, 10 }
  };

  private static final String[] INDEXES = new String[] { "t.idx", "t.idx1" };

  private static final String[] DDL_STMTS = ProductClient.DDL_STMTS;

  private static final String[] DDL_STMTS2 = new String[]{
    "create schema upgradetest",
    // No pk, no persistence
    "create table upgradetest.t1(id int, addr varchar(100))",
    "drop table upgradetest.t1",
    // No pk. Persistence.
    "create table upgradetest.t1(id int, addr varchar(100)) persistent",
    "create index upgradetest.idx_id on upgradetest.t1(id)",
    // Single col pk, no persistence
    "create table upgradetest.t2(id int primary key, addr varchar(100))",
    // Single col pk. Persistence.
    "create table upgradetest.t3(id int primary key, addr varchar(100)) persistent",
    "create index idx_t3_id on upgradetest.t3(id)",
    // Two col pk, persistence
    "create table upgradetest.t4(id1 int, id2 int, addr varchar(100), constraint t4_pk primary key(id1, id2)) persistent",
    "create index idx_t4_id1 on upgradetest.t4(id1)",
    "create index idx_t4_id2 on upgradetest.t4(id2)",
    //"create table test.t5(id int primary key generated always as identity, addr varchar(100)) persistent",
    "create table upgradetest.t5(id int primary key, addr varchar(100)) persistent",
    "create table upgradetest.t6(id int primary key, addr varchar(100)) persistent partition by primary key",
    // Partition column different from primary key 
    "create table upgradetest.t7(id1 int primary key, id2 int, addr varchar(100)) persistent partition by column(id2)",
    "create table upgradetest.t9(id int, clobcol clob(300K), blobcol blob(3K)) persistent",
    // Non-primary unique integer column 
    "create table upgradetest.t10(id int primary key, id2 int not null unique, addr varchar(100)) persistent partition by column(addr)",
    // Non-primary unique char column
    "create table upgradetest.t11(id int, id2 int, addr varchar(100) not null unique) persistent partition by column(id2)",
    // Non-primary unique clob column
    "create table upgradetest.t12(id int primary key generated by default as identity, " +
    "clobcol clob(300K) not null, blobcol blob(3K)) persistent partition by column(clobcol)"
  };
  private static final String[] DDL_STMTS_CUSTOM_DISKSTORE = ProductClient.DDL_STMTS_CUSTOM_DISKSTORE;
  
  private static final String[] DML_STMTS = ProductClient.DML_STMTS;
  private static final String[] DML_STMTS_CUSTOM_DISKSTORE = ProductClient.DDL_STMTS_CUSTOM_DISKSTORE;
  
  private static final String[] TABLE_NAMES = ProductClient.TABLE_NAMES;
  private static final String[] TABLE_NAMES_CUSTOM_DISKSTORE = ProductClient.TABLE_NAMES_CUSTOM_DISKSTORE;
  private static final String[] TABLE_NAMES_BUG50794 = ProductClient.TABLE_NAMES_BUG50794;
  
  
  private static final String[] ALTER_STMTS = new String[]{
    "alter table upgradetest.t1 add column newcol varchar(100)",
    "alter table upgradetest.t1 drop column newcol",
    "alter table upgradetest.t3 add constraint addr_uk unique(addr)",
    "alter table upgradetest.t3 drop constraint addr_uk",
    "alter table upgradetest.t9 add column newcol1 clob(300K)",
    "alter table upgradetest.t9 add column newcol2 blob(3K)",
    "alter table upgradetest.t9 drop column newcol1",
    "alter table upgradetest.t9 drop column newcol2    ",
  };

  public BackwardCompatabilityDUnit(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    // these tests generate lots of logs, so reducing them
    return "config";
  }

  private boolean alterTableWithDataSupported(String version){
    
    int firstOrdinal = new Integer(version.substring(0, version.indexOf("."))).intValue();
    int lastOrdinal = new Integer(version.substring(version.lastIndexOf(".") + 1)).intValue();
    if(version.indexOf(".") == version.lastIndexOf(".")){
      // 1.1 or higher
      return firstOrdinal >=1 && lastOrdinal >=1;
    }
    else{
      // 1.0.3 or higher
      /* [sjigyasu] TODO: Uncomment this when the latest patch release of 1.0.3 is available on /gcm/where/gemfirexd
       * For the time being, we test against 1.0.99
      return firstOrdinal >=1 && lastOrdinal >=3;
      */
      return firstOrdinal >=1 && lastOrdinal >=99;
    }
  }

  public static void runAddData(String[] ddls, String[] dmls)
      throws SQLException {
    addData(TestUtil.getConnection(), ddls, dmls);
  }
  public static void addData(Connection conn, String[] DDLs, String[] DMLs)
      throws SQLException {
    ProductClient.addData(conn, DDLs, DMLs);
  }
  public static void runDDLs(Connection conn, String[] DDLs)
      throws SQLException {
    ProductClient.runDDLs(conn, DDLs);
  }

  public static void runDMLs(Connection conn, String[] DMLs)
      throws SQLException {
    runDMLs(conn, DMLs, 1);
  }

  public static void runDMLs(Connection conn, String[] DMLs, int startId)
      throws SQLException {
    ProductClient.runDMLs(conn, DMLs, startId);
  }
  private void setAuthProps(Properties props){
    props.put("gemfirexd.auth-provider", "BUILTIN");
    props.put("gemfirexd.sql-authorization","TRUE");
    props.put("gemfirexd.user.SYSADMIN", "SA");
    props.put("user", "SYSADMIN");
    props.put("password", "SA");
  }

  public void DISABLED_testIncrementalProductVersionUpgrade() throws Exception {
    // Create a locator working dir.
    String rollingVersionLocatorPath = getSysDirName()
        + "/rollingVersionLocatorPath";
    getLogWriter().info(
        "Creating locator dir for base version: " + rollingVersionLocatorPath);
    File rollingVersionLocatorDir = new File(rollingVersionLocatorPath);
    assertTrue(rollingVersionLocatorDir.mkdir());

    ProcessStart rollingVersionLocator = startVersionedLocator(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir,
        rollingVersionLocatorPath, false);
    int rollingVersionLocatorPort = rollingVersionLocator.port;
    ProcessStart versionedServer1 = startVersionedServer(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm1WorkingDir,
        0, rollingVersionLocatorPort, false);
    int clientPort1 = versionedServer1.port;
    ProcessStart versionedServer2 = startVersionedServer(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm2WorkingDir, 0,
        rollingVersionLocatorPort, false);
    ProcessStart versionedServer3 = startVersionedServer(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm3WorkingDir, 0,
        rollingVersionLocatorPort, false);
    ProcessStart versionedServer4 = startVersionedServer(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm4WorkingDir, 0,
        rollingVersionLocatorPort, false);
    waitForProcesses(rollingVersionLocator, versionedServer1, versionedServer2,
        versionedServer3, versionedServer4);

    getLogWriter().info("Adding data");

    Properties props1 = new Properties();
    props1.setProperty("disable-cancel", "true");
    Connection conn = TestUtil.getNetConnection("localhost", clientPort1, null, props1);
    addData(conn, DDL_STMTS2, DML_STMTS);
    addData(conn, DDL_STMTS_CUSTOM_DISKSTORE, DML_STMTS_CUSTOM_DISKSTORE);
    
    if(alterTableWithDataSupported(rollingUpgradeBaseVersion)){
      getLogWriter().info("Running alter statements on server version " + rollingUpgradeBaseVersion);
      runDDLs(conn, ALTER_STMTS);  
    }
    conn.close();
    
    getLogWriter().info("Stopping first "+ rollingUpgradeBaseVersion + "server" );
    stopVersionedServer(rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm1WorkingDir);

    Properties props = new Properties();
    props.setProperty(Attribute.TABLE_DEFAULT_PARTITIONED, "false");
    props.setProperty("locators", "localhost[" + rollingVersionLocatorPort + ']');

    getLogWriter().info("Starting current version server" );
    startServerVMs(1, 0, null, props);

    getLogWriter().info("Stopping second "+ rollingUpgradeBaseVersion +" server" );
    stopVersionedServer(rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm2WorkingDir);

    getLogWriter().info("Starting current version server" );
    startServerVMs(1, 0, null, props);

    getLogWriter().info("Stopping third "+rollingUpgradeBaseVersion+" server" );
    stopVersionedServer(rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm3WorkingDir);

    getLogWriter().info("Starting current version server" );
    startServerVMs(1, 0, null, props);
    
    getLogWriter().info("Stopping fourth "+rollingUpgradeBaseVersion+"server" );
    stopVersionedServer(rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm4WorkingDir);

    getLogWriter().info("Starting current version server" );
    startServerVMs(1, 0, null, props);

    getLogWriter().info("Verifying data" );
    VM vm1 = Host.getHost(0).getVM(0);
    vm1.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES});
    vm1.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES_CUSTOM_DISKSTORE});
    
    // Stop everything 
    //stopVMNums(-1, -2, -3, -4);
    stopAllVMs();

    // Restart everything with current build
    restartVMNums(new int[]{-1, -2, -3, -4}, 0, null, props);
    
    getLogWriter().info("Verifying data" );
    vm1.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES});
    vm1.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES_CUSTOM_DISKSTORE});
    
    // TODO: Add more data
    
    // TODO: Verify data again
    
    
    // Stop everything
    stopVMNums(-1, -2, -3, -4);
    stopVersionedLocator(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, rollingVersionLocatorPath);

    String[] customStoreDirs = new String[]{vm1WorkingDir + "/dir1",
        vm2WorkingDir + "/dir1",
        vm3WorkingDir + "/dir1",
        vm4WorkingDir + "/dir1"};
    cleanUpCustomStoreFiles(customStoreDirs);
    String[] defaultStoreDirs = new String[]{vm1WorkingDir, 
        vm2WorkingDir, 
        vm3WorkingDir, 
        vm4WorkingDir
        };
    cleanUp(defaultStoreDirs);
  }
  /**
   * Similar to testDiskStoreUpgradeMultipleServers but with one less server started
   * which is added later on.  This test is likely to fail if the servers don't start
   * in the correct directories.
   * Test: 
   * - Start 4 servers with older version, load data, stop.
   * - Upgrade disk stores using current build.
   * - Start 4 servers in same VMs with current build and verify data.
   * @throws Exception
   */
  public void DISABLED_MERGE_testUpgradeDiskStoreWithMultipleServers() throws Exception{
    final int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);;
    
    boolean restartVMs = false;
    for (int listIdx = 0; listIdx < compatibleVersionLists.length; listIdx++) {
      String[] compatibleVersions = compatibleVersionLists[listIdx];
      for (int verIdx = 0; verIdx < compatibleVersions.length; verIdx++) {

        String version = compatibleVersionLists[listIdx][verIdx];
        String versionDir = compatibleVersionProductDirs[listIdx][verIdx];
        
        getLogWriter().info("Starting earlier version servers" );

        ProcessStart versionedServer1 = startVersionedServer(version,
            versionDir, vm1WorkingDir, mcastPort, 0, false);
        int clientPort1 = versionedServer1.port;
        ProcessStart versionedServer2 = startVersionedServer(version,
            versionDir, vm2WorkingDir, mcastPort, 0, false);
        ProcessStart versionedServer3 = startVersionedServer(version,
            versionDir, vm3WorkingDir, mcastPort, 0, false);
        ProcessStart versionedServer4 = startVersionedServer(version,
            versionDir, vm4WorkingDir, mcastPort, 0, false);
        waitForProcesses(versionedServer1, versionedServer2, versionedServer3,
            versionedServer4);

        getLogWriter().info("Executing DDLs" );
        Properties props1 = new Properties();
        props1.setProperty("disable-cancel", "true");
        Connection conn = TestUtil.getNetConnection("localhost", clientPort1, null, props1);
        addData(conn, DDL_STMTS, DML_STMTS);
        addData(conn, DDL_STMTS_CUSTOM_DISKSTORE, DML_STMTS_CUSTOM_DISKSTORE);
        
        if(alterTableWithDataSupported(version)){
          getLogWriter().info("Running alter statements on server version " + version);
          runDDLs(conn, ALTER_STMTS);  
        }
        
        conn.close();
        
        getLogWriter().info("Stopping earlier version servers" );
        stopVersionedServer(listIdx, verIdx, vm1WorkingDir);
        stopVersionedServer(listIdx, verIdx, vm2WorkingDir);
        stopVersionedServer(listIdx, verIdx, vm3WorkingDir);
        stopVersionedServer(listIdx, verIdx, vm4WorkingDir);

        getLogWriter().info("VM WORKING DIRS:" + vm1WorkingDir 
            + "," + vm2WorkingDir
            + "," + vm3WorkingDir 
            + "," + vm4WorkingDir);

        String[] defaultStoreDirs = new String[]{vm1WorkingDir, 
                                    vm2WorkingDir, 
                                    vm3WorkingDir, 
                                    vm4WorkingDir
                                    };
        String[] ddDirs = new String[]{vm1WorkingDir + "/datadictionary", 
            vm2WorkingDir + "/datadictionary", 
            vm3WorkingDir + "/datadictionary", 
            vm4WorkingDir + "/datadictionary"};
        String[] customStoreDirs = new String[]{vm1WorkingDir + "/dir1",
                                                vm2WorkingDir + "/dir1",
                                                vm3WorkingDir + "/dir1",
                                                vm4WorkingDir + "/dir1"};
        
        getLogWriter().info("Upgrading disk stores" );
        runUpgradeDiskStore("GFXD-DEFAULT-DISKSTORE",defaultStoreDirs);
        runUpgradeDiskStore("GFXD-DD-DISKSTORE", ddDirs);
        runUpgradeDiskStore("TESTSTORE", customStoreDirs);
        
        getLogWriter().info("Starting current version servers" );
        if(restartVMs){
          restartVMNums(new int[]{-1, -2, -3, -4}, mcastPort, null, null);
        }
        else{
          startServerVMs(4, mcastPort, null);
          restartVMs = true;
        }

        getLogWriter().info("Verifying data" );

        VM vm1 = Host.getHost(0).getVM(0);
        
        // Run the alter statements.
        for(String sql: ALTER_STMTS){
          serverSQLExecute(1, sql);  
        }
        
        vm1.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES});
        vm1.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES_CUSTOM_DISKSTORE});
        stopVMNums(-1,-2,-3,-4);

        cleanUpCustomStoreFiles(customStoreDirs);
        cleanUp(defaultStoreDirs);
        
      }
    }
  }

  public void testDiskStoreUpgradeAndClientCompatibility_SQLF_or_GFXD_Versions_To_CurrentGemFireXD()
      throws Exception {

    String[] products = { 
        //PRODUCT_SQLFIRE,
        //PRODUCT_GEMFIREXD,
        //PRODUCT_GEMFIREXD,
        PRODUCT_GEMFIREXD
    };
    String[] versions = { 
        //"1.1.2",
        //"1.0",
        //"1.3",
        "1.3.1"
    };
    final boolean isWindows = NativeCalls.getInstance().getOSType().isWindows();
    String[] versionDirs = {
        //"sqlfire/releases/SQLFire1.1.2-all",
        // GFXD 1.0.X was not released for Windows
        //isWindows ? null : "gemfireXD/releases/GemFireXD1.0.0-all",
        //isWindows ? "gemfireXD/releases/GemFireXD1.3.0-all/Windows_NT"
        //    : "gemfireXD/releases/GemFireXD1.3.0-all/Linux",
        isWindows ? "gemfireXD/releases/GemFireXD1.3.1-all/Windows_NT"
            : "gemfireXD/releases/GemFireXD1.3.1-all/Linux"
    };

    for(int i = 0; i < versions.length; i++) {
      String version = versions[i];
      String versionDir = versionDirs[i];
      if (versionDir == null) {
        continue;
      }
      String prevProduct = products[i];
      
      getLogWriter().info("Testing disk store upgrade from " + prevProduct + "-" + version + " to current GemFireXD build version");
      
      String currentDir = getSysDirName();
      String locatorDir = currentDir + "/locatorDir";
      String serverOneDir = currentDir + "/serverOneDir";
      String serverTwoDir = currentDir + "/serverTwoDir";
      new File(locatorDir).mkdirs();
      new File(serverOneDir).mkdirs();
      new File(serverTwoDir).mkdirs();
      String[] defaultStoreDirs = new String[] { locatorDir, serverOneDir,
          serverTwoDir};
      String[] ddDirs = new String[] { serverOneDir + "/datadictionary", serverTwoDir + "/datadictionary", locatorDir + "/datadictionary"};
      String[] customStoreDirs = new String[] { serverOneDir + "/dir1",
          serverTwoDir + "/dir1"};
      try {
        try {
          // Set the product.  This will be used to generate util launcher string
          product = prevProduct;
          getLogWriter().info(
              "Starting locator version: " + version + " with working dir: "
                  + locatorDir);
          ProcessStart versionedLocator = startVersionedLocator(version,
              versionDir, locatorDir, false);
          waitForProcesses(versionedLocator);
          int baseVersionLocatorPort = versionedLocator.port;

          getLogWriter().info("Starting earlier version servers");

          ProcessStart versionedServer1 = startVersionedServer(version,
              versionDir, serverOneDir, -1, baseVersionLocatorPort, false);
          int clientPort1 = versionedServer1.port;
          ProcessStart versionedServer2 = startVersionedServer(version,
              versionDir, serverTwoDir, -1, baseVersionLocatorPort, false);
          waitForProcesses(versionedServer1, versionedServer2);

          // Add data with the client for that version
          doWithVersionedClient(prevProduct, version, versionDir, clientPort1, "addDataForClient");

        } catch (Exception e) {
          getLogWriter().info("EXCEPTION:" + e);
          Assert.fail("EXCEPTION:"+e);
        } finally {
          getLogWriter().info("Stopping earlier version servers");
          stopVersionedServer(version, versionDir, serverOneDir);
          stopVersionedServer(version, versionDir, serverTwoDir);

          getLogWriter().info("Stopping earlier version locator");
          try {
            stopVersionedLocator(version, versionDir, locatorDir);  
          } catch (Exception e) {
            getLogWriter().info("EXCEPTION stopping locator:" + e.getMessage());
          }
          
        }      
        getLogWriter().info(
            "VM WORKING DIRS:" + serverOneDir + "," + serverTwoDir + "," + locatorDir);

        // Skip explicit use of upgrade-disk-store since it is not required for GemFireXD.
        // Start current version nodes directly. 
        try {
          getLogWriter().info("Starting current version locator");
          // Current product is always GemFireXD
          product = PRODUCT_GEMFIREXD;
          ProcessStart currentLocator = startCurrentVersionLocator(locatorDir);
          // wait for current locator to start
          waitForProcesses(currentLocator);
          int currentVersionLocatorPort = currentLocator.port;

          ProcessStart currentServer1 = startCurrentVersionServer(serverOneDir,
              currentVersionLocatorPort);
          int clientPort = currentServer1.port;
          ProcessStart currentServer2 = startCurrentVersionServer(serverTwoDir,
              currentVersionLocatorPort);
          waitForProcesses(currentServer1, currentServer2);

          getLogWriter().info("Verifying data using current GEMFIREXD DRIVER and JDBC URL jdbc:gemfirexd://");
          Connection connection = this.getNetConnection("localhost", clientPort, new Properties());
          verifyData(connection, TABLE_NAMES);
          verifyData(connection, TABLE_NAMES_CUSTOM_DISKSTORE);

          /* old jdbc:sqlfire:// scheme is no longer supported
          getLogWriter().info("Verifying data using GEMFIREXD DRIVER and older JDBC URL jdbc:sqlfire://");
          Connection oldUrlConn = this.getNetConnection("localhost", clientPort, new Properties(), true);
          verifyData(oldUrlConn, TABLE_NAMES);
          verifyData(oldUrlConn, TABLE_NAMES_CUSTOM_DISKSTORE);
          */

          if (prevProduct.equals(PRODUCT_SQLFIRE)) {
            getLogWriter().info("Verifying client connectivity using " + prevProduct + "-" + version + " DRIVER and older JDBC URL jdbc:sqlfire://");
            doWithVersionedClient(prevProduct, version, versionDir, clientPort, "verifyDataForClient");
          } else {
            // GemFireXD 1.0 did not support use of older JDBC URL prefix (jdbc:sqlfire://).  This was supported 1.0.2 onwards. 
            if (!version.equals("1.0")) {
              getLogWriter().info("Verifying client connectivity using " + prevProduct + "-" + version + " DRIVER and older JDBC URL jdbc:sqlfire://");
              doWithVersionedClient(prevProduct, version, versionDir, clientPort, "verifyDataForClient", true);
            }
            getLogWriter().info("Verifying client connectivity using " + prevProduct + "-" + version + " DRIVER and newer JDBC URL jdbc:gemfirexd://");
            doWithVersionedClient(prevProduct, version, versionDir, clientPort, "verifyDataForClient");
          }
        } catch (Exception e) {
          getLogWriter().info(e);
          Assert.fail("EXCEPTION:"+e);
        } finally {
          stopCurrentVersionServer(serverOneDir);
          stopCurrentVersionServer(serverTwoDir);
          stopCurrentVersionLocator(locatorDir);
        }
      } finally {
        getLogWriter().info("Cleaning up");
        cleanUpCustomStoreFiles(customStoreDirs);
        cleanUp(defaultStoreDirs);
        product = null;
      }
    }
  }

  public void DISABLED_testBug50794()
      throws Exception {
    String[] versions = {"1.1.2"};
    String[] versionDirs = {"sqlfire/releases/SQLFire1.1.2-all"};
    
    for(int i = 0; i < versions.length; i++) {
      String version = versions[i];
      String versionDir = versionDirs[i];
      
      getLogWriter().info("Testing disk store upgrade from SQLFire-" + version + " to current GemFireXD build version");
      
      String currentDir = getSysDirName();
      String locatorDir = currentDir + "/locatorDir";
      String serverOneDir = currentDir + "/serverOneDir";
      String serverTwoDir = currentDir + "/serverTwoDir";
      new File(locatorDir).mkdirs();
      new File(serverOneDir).mkdirs();
      new File(serverTwoDir).mkdirs();
      String[] defaultStoreDirs = new String[] { locatorDir, serverOneDir,
          serverTwoDir};
      String[] ddDirs = new String[] { serverOneDir + "/datadictionary", serverTwoDir + "/datadictionary", locatorDir + "/datadictionary"};
      String[] customStoreDirs = new String[] { serverOneDir + "/dir1",
          serverTwoDir + "/dir1"};
      try {
        try {
          product = PRODUCT_SQLFIRE;
          getLogWriter().info(
              "Starting locator version: " + version + " with working dir: "
                  + locatorDir);
          ProcessStart versionedLocator = startVersionedLocator(version,
              versionDir, locatorDir, false);
          int baseVersionLocatorPort = versionedLocator.port;

          getLogWriter().info("Starting earlier version servers");

          ProcessStart versionedServer1 = startVersionedServer(version,
              versionDir, serverOneDir, -1, baseVersionLocatorPort, false);
          int clientPort1 = versionedServer1.port;
          ProcessStart versionedServer2 = startVersionedServer(version,
              versionDir, serverTwoDir, -1, baseVersionLocatorPort, false);
          waitForProcesses(versionedLocator, versionedServer1, versionedServer2);

          // Add data with the client for that version
          doWithVersionedClient(PRODUCT_SQLFIRE, version, versionDir, clientPort1, "addDataForBug50794");

        } catch (Exception e) {
          getLogWriter().info(e);
          Assert.fail("EXCEPTION:"+e);
        } finally {
          getLogWriter().info("Stopping earlier version servers");
          stopVersionedServer(version, versionDir, serverOneDir);
          stopVersionedServer(version, versionDir, serverTwoDir);

          getLogWriter().info("Stopping earlier version locator");
          stopVersionedLocator(version, versionDir, locatorDir);
        }      
        getLogWriter().info(
            "VM WORKING DIRS:" + serverOneDir + "," + serverTwoDir + "," + locatorDir);
        
        getLogWriter().info("Upgrading disk stores");
        
        try {
        runUpgradeDiskStore("SQLF-DEFAULT-DISKSTORE", defaultStoreDirs);
        runUpgradeDiskStore("SQLF-DD-DISKSTORE", ddDirs);
        //runUpgradeDiskStore("TESTSTORE", customStoreDirs);
        } catch (Throwable e) {
          getLogWriter().info(e);
          Assert.fail("Exception during upgrade:" + e);
        }

        try {
          getLogWriter().info("Starting current version locator");
          product = PRODUCT_GEMFIREXD;
          ProcessStart currentLocator = startCurrentVersionLocator(locatorDir);
          int currentVersionLocatorPort = currentLocator.port;

          ProcessStart currentServer1 = startCurrentVersionServer(serverOneDir,
              currentVersionLocatorPort);
          int clientPort = currentServer1.port;
          ProcessStart currentServer2 = startCurrentVersionServer(serverTwoDir,
              currentVersionLocatorPort);
          waitForProcesses(currentLocator, currentServer1, currentServer2);

          Connection connection = this.getNetConnection("localhost", clientPort, new Properties());
          connection.createStatement().execute("insert into upgradetest.pencil_default values(100, 'abc', 'def')");
          connection.createStatement().execute("insert into upgradetest.pencil_default values(200, 'ghi', 'jkl')");
          verifyData(connection, TABLE_NAMES_BUG50794);
          //verifyData(connection, TABLE_NAMES_CUSTOM_DISKSTORE);
          
          getLogWriter().info("Verifying client connectivity using SQLFIRE-" + version + " DRIVER and older JDBC URL jdbc:sqlfire://");
          // Connect to current version servers using earlier SQLFire driver and verify the table data
          doWithVersionedClient(PRODUCT_SQLFIRE, version, versionDir, clientPort, "verifyDataForClientBug50794");
          
          getLogWriter().info("Verifying client connectivity using GEMFIREXD DRIVER and older JDBC URL jdbc:sqlfire://");
          Connection oldUrlConn = this.getNetConnection("localhost", clientPort, new Properties());
          verifyData(oldUrlConn, TABLE_NAMES_BUG50794);
          //verifyData(oldUrlConn, TABLE_NAMES_CUSTOM_DISKSTORE);
          
        } catch (Exception e) {
          getLogWriter().info(e);
          Assert.fail("EXCEPTION:"+e);
        } finally {
          stopCurrentVersionServer(serverOneDir);
          stopCurrentVersionServer(serverTwoDir);
          stopCurrentVersionLocator(locatorDir);
        }
      } finally {
        getLogWriter().info("Cleaning up");
        cleanUpCustomStoreFiles(customStoreDirs);
        cleanUp(defaultStoreDirs);
        product = null;
      }
      
    }
  }

  //[todo] in progress - to be enabled later
  public void __testBug_50165() throws Exception {
    String[] versions = {
        "1.1.2"
        };
    String[] versionDirs = {
        "sqlfire/releases/SQLFire1.1.2-all"
        };
    
    for(int i = 0; i < versions.length; i++) {
      String version = versions[i];
      String versionDir = versionDirs[i];
      
      getLogWriter().info("Testing with SQLFire-" + version);
      
      String currentDir = getSysDirName();
      String locatorDir = currentDir + "/locatorDir";
      String serverOneDir = currentDir + "/serverOneDir";
      String serverTwoDir = currentDir + "/serverTwoDir";
      String serverThreeDir = currentDir + "/serverThreeDir";
      String serverFourDir = currentDir + "/serverFourDir";
      new File(locatorDir).mkdirs();
      new File(serverOneDir).mkdirs();
      new File(serverTwoDir).mkdirs();
      new File(serverThreeDir).mkdirs();
      new File(serverFourDir).mkdirs();

      try {
        product = PRODUCT_SQLFIRE;
        getLogWriter().info(
            "Starting locator version: " + version + " with working dir: "
                + locatorDir);
        ProcessStart versionedLocator = startVersionedLocator(version,
            versionDir, locatorDir, false);
        int baseVersionLocatorPort = versionedLocator.port;

        getLogWriter().info("Starting earlier version servers");

        ProcessStart versionedServer1 = startVersionedServer(version,
            versionDir, serverOneDir, -1, baseVersionLocatorPort, false);
        int clientPort1 = versionedServer1.port;
        ProcessStart versionedServer2 = startVersionedServer(version,
            versionDir, serverTwoDir, -1, baseVersionLocatorPort, false);
        ProcessStart versionedServer3 = startVersionedServer(version,
            versionDir, serverThreeDir, -1, baseVersionLocatorPort, false);
        ProcessStart versionedServer4 = startVersionedServer(version,
            versionDir, serverFourDir, -1, baseVersionLocatorPort, false);
        waitForProcesses(versionedLocator, versionedServer1, versionedServer2,
            versionedServer3, versionedServer4);

        // Add data with the client for that version
        doWithVersionedClient(PRODUCT_SQLFIRE, version, versionDir, clientPort1, "createDataForClient");

      } finally {
        getLogWriter().info("Stopping earlier version servers");
        stopVersionedServer(version, versionDir, serverOneDir);
        stopVersionedServer(version, versionDir, serverTwoDir);
        stopVersionedServer(version, versionDir, serverThreeDir);
        stopVersionedServer(version, versionDir, serverFourDir);

        getLogWriter().info("Stopping earlier version locator");    
        stopVersionedLocator(version, versionDir, locatorDir);
      }
      
      getLogWriter().info(
          "VM WORKING DIRS:" + serverOneDir + "," + serverTwoDir + "," + serverThreeDir + "," + serverFourDir + "," + locatorDir);

      String[] storeDirs = new String[] { locatorDir, serverOneDir,
          serverTwoDir, serverThreeDir, serverFourDir };
      String[] customDSDirs = new String[]{serverOneDir, serverTwoDir, serverThreeDir, serverFourDir };
      String[] ddDirs = new String[] { locatorDir + "/datadictionary",
          serverOneDir + "/datadictionary", 
          serverTwoDir + "/datadictionary",
          serverThreeDir + "/datadictionary",
          serverFourDir + "/datadictionary"
          };
/*      
      getLogWriter().info("Upgrading disk stores");
      runUpgradeDiskStore("SQLF-DEFAULT-DISKSTORE", storeDirs);
      runUpgradeDiskStore("CUSTOMDS", customDSDirs);
      runUpgradeDiskStore("SQLF-DD-DISKSTORE", ddDirs);
*/
      int currentVersionLocatorPort = -1;
      try {
        getLogWriter().info("Starting current version locator");
        product = PRODUCT_GEMFIREXD;
        ProcessStart currentLocator = startCurrentVersionLocator(locatorDir);
        currentVersionLocatorPort = currentLocator.port;

        ProcessStart currentServer1 = startCurrentVersionServer(serverOneDir,
            currentVersionLocatorPort);
        int clientPort = currentServer1.port;
        ProcessStart currentServer2 = startCurrentVersionServer(serverTwoDir,
            currentVersionLocatorPort);
        ProcessStart currentServer3 = startCurrentVersionServer(serverThreeDir,
            currentVersionLocatorPort);
        ProcessStart currentServer4 = startCurrentVersionServer(serverFourDir,
            currentVersionLocatorPort);
        waitForProcesses(currentLocator, currentServer1, currentServer2,
            currentServer3, currentServer4);

        //Properties props = new Properties();
        //props.put("load-balance", "false");
        Connection connection = this.getNetConnection("localhost", clientPort, new Properties());
        
        Statement gfxdSt = connection.createStatement();
        
        getLogWriter().info("Creating tables");
        // Fire more than 4 DDLs to reproduce the record interval problem
        gfxdSt.execute("create table t1_temp(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
            + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");
        gfxdSt.execute("create table t2_temp(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
            + " partition by column(id1, id2, ts) colocate with (t1_temp) redundancy 1 persistent 'customds'");
        gfxdSt.execute("create table t3_temp(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
            + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");
        gfxdSt.execute("create table t4_temp(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
            + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");
        gfxdSt.execute("create table t5_temp(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
            + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");

        try {
          gfxdSt.execute("create index idx1_t1_temp on t1_temp(id2, id1)");
          gfxdSt.execute("create index idx2_t1_temp on t1_temp(id1, ts)");
        } catch (Exception e) {
          getLogWriter().info("EXCEPTION=" + e);
          Assert.fail("Exception while creating index:" + e);
        }
        
        getLogWriter().info("Inserting data");
        PreparedStatement gfxdPs = connection.prepareStatement("insert into t1_temp values(?,?,?,?)");
        for(int j=0; j < 5; j++) {
          int id = j * 100;
          gfxdPs.setString(1, String.valueOf(id));
          gfxdPs.setInt(2, id);
          gfxdPs.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
          gfxdPs.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
          gfxdPs.execute();
        }
        gfxdPs.close();
        PreparedStatement gfxdPs2 = connection.prepareStatement("insert into t2_temp values(?,?,?,?)");
        for(int k=0; k < 5; k++) {
          int id = k * 100;
          gfxdPs2.setString(1, String.valueOf(id));
          gfxdPs2.setInt(2, id);
          gfxdPs2.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
          gfxdPs2.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
          gfxdPs2.execute();
        }
        gfxdPs2.close();

        gfxdSt.close();
        connection.close();
        
      } catch (Exception e) {
        getLogWriter().info("EXCEPTION:" + e);
      }
      finally {
        getLogWriter().info("Shutdown current version cluster");
        if(currentVersionLocatorPort != -1) {
          shutdownAllCurrent(currentVersionLocatorPort);
        }
        product = null;
      }
      cleanUpCustomStoreFiles(storeDirs);
    }
  }

  /**
   * Tests a mix of 1.1.1 and current version locators and servers
   * with authentication.  Valid only from 1.1.1 onwards.
   * Test config is as follows:
   * 1.1.1 locator -- controller-directory/baselocator
   * vm3 -- 1.1.1 server
   * vm4 -- 1.1.1 server
   * vm2 -- current locator
   * vm1 -- current server 
   */
  public void DISABLED_testMixedVersionsWithAuthentication() throws Exception{

    // Create a locator working dir.
    String baseLocatorPath = getSysDirName() + "/baseVersionLocator";
    getLogWriter().info("Creating locator dir for base version: "+ baseLocatorPath);
    File baseVersionLocatorDir = new File(baseLocatorPath) ;
    assertTrue(baseVersionLocatorDir.mkdir());
    
    // Start a locator and two servers with authentication
    // For the two servers, use VM working dirs of the third and fourth VM
    getLogWriter().info(
        "Starting locator version: " + rollingUpgradeBaseVersion
            + " with working dir: " + baseLocatorPath);
    ProcessStart versionedLocator = startVersionedLocator(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir,
        baseLocatorPath, true);
    int baseVersionLocatorPort = versionedLocator.port;

    getLogWriter().info(
        "Starting server version: " + rollingUpgradeBaseVersion
            + " with working dir: " + vm3WorkingDir);
    ProcessStart versionedServer1 = startVersionedServer(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm3WorkingDir,
        0, baseVersionLocatorPort, true);
    int clientPort1 = versionedServer1.port;
    getLogWriter().info(
        "Starting server version: " + rollingUpgradeBaseVersion
            + " with working dir: " + vm4WorkingDir);
    ProcessStart versionedServer2 = startVersionedServer(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm4WorkingDir,
        0, baseVersionLocatorPort, true);
    waitForProcesses(versionedLocator, versionedServer1, versionedServer2);

    getLogWriter().info("Adding data, connecting to server at working dir " + vm3WorkingDir);
    Properties p = new Properties();
    p.put("user", "SYSADMIN");
    p.put("password", "SA");
    p.put("disable-cancel", "true");
    Connection conn = TestUtil.getNetConnection("localhost", clientPort1, null, p);
    addData(conn, DDL_STMTS2, DML_STMTS);
    
    Properties props = new Properties();
    setAuthProps(props);
    props.put("locators", "localhost:"+baseVersionLocatorPort);
    props.setProperty( Attribute.TABLE_DEFAULT_PARTITIONED, "false");
    // Start current version locator in the second VM (since we will use the first VM for starting a server
    // and the working dirs of 3rd and 4th are already being used above).
    
    getLogWriter().info("Starting locator with current version in the second VM of this dunit test");
    int currentVersionLocatorPort = this.startLocator(Host.getHost(0).getVM(1), props);
    
    props.remove("locators");
    props.put("locators", "localhost:" + currentVersionLocatorPort);
    
    getLogWriter().info("Starting server with current version in the first VM");
    // This will use the first VM I think.
    startServerVMs(1, 0, null, props);

    final VM newVM = getServerVM(1);
    // insert more data from new server
    newVM.invoke(this.getClass(), "runAddData",
        new Object[] { null, DML_STMTS });

    getLogWriter().info("Verifying data by connecting to this last server");
    // Verify data
    newVM
        .invoke(this.getClass(), "verifyData", new Object[] { TABLE_NAMES, p });

    getLogWriter().info("Stopping the server and the locator running in test VMs");
    stopAllVMs();
    props.remove("locators");
    // Stop the locator explicitly, just in case
    this.stopLocator(Host.getHost(0).getVM(1), props);
    
    getLogWriter().info("Stopping versioned servers");
    stopVersionedServer(rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm3WorkingDir);
    stopVersionedServer(rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm4WorkingDir);
    getLogWriter().info("Stopping versioned locator");
    stopVersionedLocator(rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, baseLocatorPath);
    
    getLogWriter().info("Cleaning up");
    // Cleanup locator dirs
    TestUtil.deleteDir(new File(baseLocatorPath));
  }
  
  /**
   * Test: 
   * - Start 3 servers with older version, load data, stop
   * - Upgrade disk stores using current build.
   * - Start 3 servers in same VMs with current build and verify data.
   * - Add a new server without existing data to the ds
   * - Verify data again connecting to the fourth server.
   */
  public void DISABLED_MERGE_testUpgradeDiskStoreAddNewServerWithNoData() throws Exception{
    final int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);;
    boolean restartVMs = false;
    boolean restartFourthVM = false;
    for (int listIdx = 0; listIdx < compatibleVersionLists.length; listIdx++) {
      String[] compatibleVersions = compatibleVersionLists[listIdx];
      for (int verIdx = 0; verIdx < compatibleVersions.length; verIdx++) {

        String version = compatibleVersionLists[listIdx][verIdx];
        String versionDir = compatibleVersionProductDirs[listIdx][verIdx];
        
        // Start 3 servers 
        getLogWriter().info("Starting earlier version servers");

        ProcessStart versionedServer1 = startVersionedServer(version,
            versionDir, vm1WorkingDir, mcastPort, 0, false);
        int clientPort1 = versionedServer1.port;
        ProcessStart versionedServer2 = startVersionedServer(version,
            versionDir, vm2WorkingDir, mcastPort, 0, false);
        ProcessStart versionedServer3 = startVersionedServer(version,
            versionDir, vm3WorkingDir, mcastPort, 0, false);
        waitForProcesses(versionedServer1, versionedServer2, versionedServer3);

        getLogWriter().info("Executing DDLs");

        Properties props1 = new Properties();
        props1.setProperty("disable-cancel", "true");
        Connection conn = TestUtil.getNetConnection("localhost", clientPort1,
            null, props1);
        addData(conn, DDL_STMTS, DML_STMTS);
        conn.close();

        getLogWriter().info("Stopping earlier version servers");
        stopVersionedServer(listIdx, verIdx, vm1WorkingDir);
        stopVersionedServer(listIdx, verIdx, vm2WorkingDir);
        stopVersionedServer(listIdx, verIdx, vm3WorkingDir);

        getLogWriter().info("VM WORKING DIRS:" + vm1WorkingDir 
            + "," + vm2WorkingDir
            + "," + vm3WorkingDir );

        //String[] dirs = new String[]{vm1WorkingDir, vm2WorkingDir, vm3WorkingDir};
        String[] defaultStoreDirs = new String[]{vm1WorkingDir, 
            vm2WorkingDir, 
            vm3WorkingDir
            };
        String[] ddDirs = new String[]{vm1WorkingDir + "/datadictionary", 
                  vm2WorkingDir + "/datadictionary", 
                  vm3WorkingDir + "/datadictionary"};
        
        getLogWriter().info("Upgrading disk stores" );
        runUpgradeDiskStore("GFXD-DEFAULT-DISKSTORE",defaultStoreDirs);
        runUpgradeDiskStore("GFXD-DD-DISKSTORE", ddDirs);
        
        getLogWriter().info("Starting current version servers" );
        if(restartVMs){
          restartVMNums(new int[]{-1, -2, -3}, mcastPort, null, null);
        }
        else{
          startServerVMs(3, mcastPort, null);
          restartVMs = true;
        }

        getLogWriter().info("Verifying data" );

        VM vm1 = Host.getHost(0).getVM(0);
        vm1.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES});
        
        // Now add the fourth server
        if(restartFourthVM){
          restartVMNums(new int[]{-4}, mcastPort, null, null);
        }
        else{
          startServerVMs(1, mcastPort, null);
          restartFourthVM = true;
        }

        VM vm4 = Host.getHost(0).getVM(3);
        vm4.invoke(this.getClass(), "verifyData", new Object[]{TABLE_NAMES});
        
        stopVMNums(-1,-2,-3,-4);
        
        cleanUp(new String[]{vm1WorkingDir,vm2WorkingDir, vm3WorkingDir, vm4WorkingDir});
      }
    }
  }

  private int startLocator(VM locatorVM, Properties props) throws IOException {

    final int locatorPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties pr = props;
    final File locatorLogFile = new File("locator-" + locatorPort + ".log");
    locatorVM.invoke(new SerializableCallable() {
      public Object call() throws IOException {
        try {
          Properties p = new Properties();
          p.setProperty("jmx-manager", "false");
          p.putAll(pr);
          p = getAllDistributedSystemProperties(p);
          //Locator.startLocatorAndDS(locatorPort, locatorLogFile, p);
          FabricLocator locator = FabricServiceManager.getFabricLocatorInstance();
          locator.start("localhost", locatorPort, p);
        } catch (SQLException e) {
          e.printStackTrace();
        }
        return null;
      }
    });
    return locatorPort;
  }

  private void stopLocator(VM locatorVM, Properties props) throws Exception {
    final Properties p = props;
    locatorVM.invoke(new SerializableCallable() {
      public Object call() throws IOException {
        try {
          FabricService locator = FabricServiceManager.currentFabricServiceInstance();
          locator.stop(p);
        } catch (SQLException e) {
          e.printStackTrace();
        }
        return null;
      }
    });
  }

  /**
   * This is based on the _old_testDataDictionaryAndDataRecovery.  
   * This tests disk store upgrade from earlier versions against the current build.
   * 
   * @throws Exception
   */
  public void DISABLED_MERGE_testUpgradeDiskStoreSingleServer() throws Exception {
    final String workingDir = getSysDirName();
    boolean thisVMStarted = false;
    
    // loop through all version lists
    for (int listIdx = 0; listIdx < compatibleVersionLists.length; listIdx++) {
      String[] compatibleVersions = compatibleVersionLists[listIdx];

      // loop through all versions; a version appearing earlier in the list
      // must be compatible with a version appearing later
      for (int verIdx = 0; verIdx < compatibleVersions.length; verIdx++) {
        // start server with this version
        
        getLogWriter().info("WORKING DIR=" + workingDir);
        
        int clientPort = startLonerVersionedServer(listIdx, verIdx, -1, workingDir);
        this.currentListIdx = listIdx;
        this.currentVersIdx = verIdx;

        // get a client connection and then execute the DDLs and load data
        Properties props1 = new Properties();
        props1.setProperty("disable-cancel", "true");
        Connection conn = TestUtil.getNetConnection("localhost", clientPort,
            null, props1);
        Statement stmt = conn.createStatement();

        int id = 1;
        int numInserts = 10;
        for (String ddl : DDLS) {
          if (ddl.startsWith("drop")) {
            // execute DMLs before the drop
            runDMLs(id, numInserts, conn);
            id += numInserts;
          }
          stmt.execute(ddl);
        }
        // execute batch DMLs at the end
        runDMLsAsBatch(id, numInserts, conn);
        id += numInserts;

        conn.close();
        stopVersionedServer(listIdx, verIdx, workingDir);
        this.currentListIdx = -1;
        this.currentVersIdx = -1;

        String dirs[] = new String[] {workingDir};
        String ddDirs[] = new String[]{workingDir + "/datadictionary"};
        runUpgradeDiskStore("GFXD-DEFAULT-DISKSTORE",dirs);
        runUpgradeDiskStore("GFXD-DD-DISKSTORE", ddDirs);

        getLogWriter().info(
              // lastly check against current build
            "testing server version " + compatibleVersions[verIdx]
                + " against current build");

        Properties props = new Properties();
        props.setProperty("mcast-port", "0");
        props.setProperty("host-data", "true");
        props.setProperty("table-default-partitioned", "false");
        if (thisVMStarted) {
          restartClientVMNums(new int[] { 1 },
              1 /* dummy value to avoid locator setting */, null, props);
        }
        else {
          startClientVMs(1, 1, // dummy value to avoid locator setting 
              null, props);
          thisVMStarted = true;
        }
        clientPort = TestUtil.startNetserverAndReturnPort();

        // get a client connection and then verify the loaded data
        conn = TestUtil.getNetConnection(clientPort, null, null);
        verifyData(conn, id, true);

        // drop all tables and indexes for next round
        dropDatabaseObjects(null, clientPort);
        conn.close();

        stopVMNum(1);

        // cleanup all persisted data for next list of version checks
        deleteDataDictionaryDir();
        deleteDefaultDiskStoreFiles(workingDir);
      }
    }
  }
  public void _old_testDataDictionaryAndDataRecovery() throws Exception {
    final String workingDir = getSysDirName();
    boolean thisVMStarted = false;
    // loop through all version lists
    for (int listIdx = 0; listIdx < compatibleVersionLists.length; listIdx++) {
      String[] compatibleVersions = compatibleVersionLists[listIdx];

      // loop through all versions; a version appearing earlier in the list
      // must be compatible with a version appearing later
      for (int verIdx = 0; verIdx < compatibleVersions.length; verIdx++) {
        // start server with this version
        int clientPort = startLonerVersionedServer(listIdx, verIdx, -1, workingDir);
        this.currentListIdx = listIdx;
        this.currentVersIdx = verIdx;

        // get a client connection and then execute the DDLs and load data
        Connection conn = TestUtil.getNetConnection("localhost", clientPort,
            null, null);
        Statement stmt = conn.createStatement();

        int id = 1;
        int numInserts = 10;
        for (String ddl : DDLS) {
          if (ddl.startsWith("drop")) {
            // execute DMLs before the drop
            runDMLs(id, numInserts, conn);
            id += numInserts;
          }
          stmt.execute(ddl);
        }
        // execute batch DMLs at the end
        runDMLsAsBatch(id, numInserts, conn);
        id += numInserts;

        conn.close();
        stopVersionedServer(listIdx, verIdx, workingDir);
        this.currentListIdx = -1;
        this.currentVersIdx = -1;

        // no use checking all since previous versions would already be verified
        // when they were released, so just pick the last released version
        /*
        for (int higherIdx = verIdx; higherIdx < compatibleVersions.length;
            higherIdx++) {
        */
        {
          int higherIdx = compatibleVersions.length - 1;
          final String testVersion = compatibleVersions[higherIdx];
          // skip for bug #44988 affected version
          if (higherIdx != verIdx
              && testVersion.equals(bug44988AffectedVersion)) {
            getLogWriter().info(
                "skipping test of server version " + compatibleVersions[verIdx]
                    + " against " + testVersion + " due to bug 44988");
            continue;
          }
          // now test against higherIdx
          getLogWriter().info(
              "testing server version " + compatibleVersions[verIdx]
                  + " against " + testVersion);

          // start server with this version
          clientPort = startLonerVersionedServer(listIdx, higherIdx, verIdx,
              workingDir);
          this.currentListIdx = listIdx;
          this.currentVersIdx = higherIdx;

          // get a client connection and then verify the loaded data
          conn = TestUtil.getNetConnection("localhost", clientPort, null, null);
          verifyData(conn, id, true);
          conn.close();

          stopVersionedServer(listIdx, verIdx, workingDir);
          this.currentListIdx = -1;
          this.currentVersIdx = -1;
        }

        // lastly check against current build
        getLogWriter().info(
            "testing server version " + compatibleVersions[verIdx]
                + " against current build");

        Properties props = new Properties();
        props.setProperty("mcast-port", "0");
        props.setProperty("host-data", "true");
        props.setProperty("table-default-partitioned", "false");
        if (thisVMStarted) {
          restartClientVMNums(new int[] { 1 },
              1 /* dummy value to avoid locator setting */, null, props);
        }
        else {
          startClientVMs(1, 1 /* dummy value to avoid locator setting */,
              null, props);
          thisVMStarted = true;
        }
        clientPort = TestUtil.startNetserverAndReturnPort();

        // get a client connection and then verify the loaded data
        conn = TestUtil.getNetConnection(clientPort, null, null);
        verifyData(conn, id, true);

        // drop all tables and indexes for next round
        dropDatabaseObjects(null, clientPort);
        conn.close();

        stopVMNum(1);
      }

      // cleanup all persisted data for next list of version checks
      deleteDataDictionaryDir();
      deleteDefaultDiskStoreFiles(workingDir);
    }
  }

  public void DISABLED_MERGE_testClientCompatibility() throws Exception {
    final String workingDir = getSysDirName();
    boolean thisVMStarted = false;
    // loop through all version lists
    for (int listIdx = 0; listIdx < compatibleVersionLists.length; listIdx++) {
      String[] compatibleVersions = compatibleVersionLists[listIdx];

      // loop through all versions; a version appearing earlier in the list
      // must be compatible with a version appearing later
      for (int verIdx = 0; verIdx < compatibleVersions.length; verIdx++) {
        int clientPort;

        int higherIdx = compatibleVersions.length - 1;
        final String testVersion = compatibleVersions[higherIdx];
        
        getLogWriter().info(
              "testing client version " + compatibleVersions[verIdx]
                  + " against " + testVersion);

        // start server with this version
        clientPort = startLonerVersionedServer(listIdx, higherIdx, verIdx,
              workingDir);
        this.currentListIdx = listIdx;
        this.currentVersIdx = higherIdx;
          
        // execute a client from "verIdx" against the above server
        runVersionedClient(listIdx, verIdx, "localhost", clientPort, 10, 10000);

        // drop all tables and indexes for next server
        //dropDatabaseObjects("localhost", clientPort);

        stopVersionedServer(listIdx, verIdx, workingDir);
        this.currentListIdx = -1;
        this.currentVersIdx = -1;

        getLogWriter().info("Upgrading disk stores" );
        runUpgradeDiskStore("GFXD-DEFAULT-DISKSTORE",new String[]{workingDir});
        runUpgradeDiskStore("GFXD-DD-DISKSTORE", new String[]{workingDir + "/datadictionary"});
        //runUpgradeDiskStore("TESTSTORE", new String[]{workingDir});
        
        
        // lastly check against current build
        getLogWriter().info(
            "testing client version " + compatibleVersions[verIdx]
                + " against current build");

        Properties props = new Properties();
        props.setProperty("mcast-port", "0");
        props.setProperty("host-data", "true");
        props.setProperty("table-default-partitioned", "false");
        if (thisVMStarted) {
          restartClientVMNums(new int[] { 1 },
              1 /* dummy value to avoid locator setting */, null, props);
        }
        else {
          startClientVMs(1, 1 /* dummy value to avoid locator setting */,
              null, props);
          thisVMStarted = true;
        }
        
        clientPort = TestUtil.startNetserverAndReturnPort();

        // since 1.0.2 does not destroy persisted UUIDs etc on region destroy,
        // the current server ends up picking up the old values for the UUIDs;
        // the create/drop of tables here is a workaround for that else there
        // is a mismatch in expected values of generated IDs and the actuals
        Connection conn = TestUtil.getNetConnection(clientPort, null, null);
        /*
        Statement stmt = conn.createStatement();
        for (String ddl : DDLS) {
          stmt.execute(ddl);
        }
        */
        dropDatabaseObjects(null, clientPort);

        // execute a client from "verIdx" against the above server
        runVersionedClient(listIdx, verIdx, "localhost", clientPort, 10, 10000);

        // drop all tables and indexes for next server
        dropDatabaseObjects(null, clientPort);

        stopVMNum(1);
        
        // cleanup all persisted data for next list of version checks
        deleteDataDictionaryDir();
        deleteDefaultDiskStoreFiles(workingDir);
      }
    }
  }

  // Similar to ClientRun but a bit more generic
  public static class ProductClient {
    
    private static final String[] DDL_STMTS = new String[]{
      "create schema upgradetest",
      // No pk, no persistence
      "create table upgradetest.t1(id int, addr varchar(100))",
      "drop table upgradetest.t1",
      // No pk. Persistence.
      "create table upgradetest.t1(id int, addr varchar(100)) persistent",
      "create index upgradetest.idx_id on upgradetest.t1(id)",
      // Single col pk, no persistence
      "create table upgradetest.t2(id int primary key, addr varchar(100))",
      // Single col pk. Persistence.
      "create table upgradetest.t3(id int primary key, addr varchar(100)) persistent",
      "create index idx_t3_id on upgradetest.t3(id)",
      // Two col pk, persistence
      "create table upgradetest.t4(id1 int, id2 int, addr varchar(100), constraint t4_pk primary key(id1, id2)) persistent",
      "create index idx_t4_id1 on upgradetest.t4(id1)",
      "create index idx_t4_id2 on upgradetest.t4(id2)",
      //"create table test.t5(id int primary key generated always as identity, addr varchar(100)) persistent",
      "create table upgradetest.t5(id int primary key, addr varchar(100)) persistent",
      "create table upgradetest.t6(id int primary key, addr varchar(100)) persistent partition by primary key",
      // Partition column different from primary key 
      "create table upgradetest.t7(id1 int primary key, id2 int, addr varchar(100)) persistent partition by column(id2)",
      "create table upgradetest.t9(id int, clobcol clob(300K), blobcol blob(3K)) persistent",
      // Non-primary unique integer column 
      "create table upgradetest.t10(id int primary key, id2 int not null unique, addr varchar(100)) persistent partition by column(addr)",
      // Non-primary unique char column
      "create table upgradetest.t11(id int, id2 int, addr varchar(100) not null unique) persistent partition by column(id2)",
      // Non-primary unique clob column
      "create table upgradetest.t12(id int primary key generated always as identity, " +
      "clobcol clob(300K) not null, blobcol blob(3K)) persistent partition by column(clobcol)"
    };
    private static final String[] DML_STMTS = new String[]{
      "insert into upgradetest.t1 values(?,?)",
      "insert into upgradetest.t2 values(?,?)",
      "insert into upgradetest.t3 values(?,?)",
      "insert into upgradetest.t4 values(?,?,?)",
      "insert into upgradetest.t5 values(?,?)",
      "insert into upgradetest.t6 values(?,?)",
      "insert into upgradetest.t7 values(?,?,?)",
      "insert into upgradetest.t9 values(?,?,?)",
      "insert into upgradetest.t10 values(?,?,?)",
      "insert into upgradetest.t11 values(?,?,?)",
      "insert into upgradetest.t12(clobcol,blobcol) values(?,?)"
    };
    private static final String[] DDL_STMTS_CUSTOM_DISKSTORE = new String[]{
      "create DiskStore TESTSTORE " +
          "maxlogsize 1024 " +
          "autocompact true " +
          "allowforcecompaction false " +
          "compactionthreshold 80 " +
          "TimeInterval 223344 " +
          "writebuffersize 19292393 " +
          "queuesize 17374  ('dir1')",
      "create table upgradetest.t8(id1 int primary key, id2 int, addr varchar(100)) partition by column(id2) persistent 'TESTSTORE'"
    };
    private static final String[] DML_STMTS_CUSTOM_DISKSTORE = new String[]{
      "insert into upgradetest.t8 values(?,?,?)"
    };
    
    private static final String[] DDL_STMTS_BUG50794 = new String[] {
      /*
      "CREATE TABLE upgradetest.STORE (" +
      "ENTERPRISE_ID VARCHAR(8)," +
      "ID VARCHAR(15)," +
      "CONSTRAINT STORE_PK PRIMARY KEY(ID)" +
      ")" +
      "PERSISTENT ASYNCHRONOUS " +
      "EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW " +
      "REDUNDANCY 1 " +
      "RECOVERYDELAY 5000 " +
      "PARTITION BY PRIMARY KEY",
*/
      "CREATE TABLE upgradetest.PENCIL_DEFAULT (" +
        "ID INTEGER " + //"GENERATED ALWAYS AS IDENTITY" + 
          "," +
        "HEADER_TEXT VARCHAR(255)," +
        "STORE_ID VARCHAR(15) NOT NULL," +
        "CONSTRAINT PENCIL_DEFAULT_PK PRIMARY KEY(ID)" +
        ")" +
        "PERSISTENT ASYNCHRONOUS " +
        //"EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW " +
        "EVICTION BY LRUCOUNT 2 EVICTACTION OVERFLOW " +
        //"EVICTION BY LRUMEMSIZE 5 EVICTACTION OVERFLOW " +
        "REDUNDANCY 1 " +
        "RECOVERYDELAY 5000 " +
        "PARTITION BY COLUMN (STORE_ID) "
        //"PARTITION BY PRIMARY KEY"
        //+ "COLOCATE WITH (upgradetest.STORE)"
    };
    private static final String[] DML_STMTS_BUG50794 = new String[] {
      //"insert into upgradetest.store values(?,?)",
      "insert into upgradetest.pencil_default values(?,?,?)"
    };
    
    private static final String[] TABLE_NAMES = new String[]{"t1", "t2", "t3", "t4", "t5", "t6", "t7", "t9", "t10", "t11", "t12"};
    private static final String[] TABLE_NAMES_CUSTOM_DISKSTORE = new String[]{"t8"};
    private static final String[] TABLE_NAMES_BUG50794 = new String[]{//"STORE", 
                                                                          "PENCIL_DEFAULT"};
    
    /**
     * Arguments: 
     * a) product: one of "SQLFIRE" or "GEMFIREXD"
     * b) host where the server is running
     * c) port where the server is listening
     * d) complete path with filename to the client jar file
     * e) Name of static method in ProductClient class to use for 
     * adding data to the server using the client.  The method should be static
     * and must take only one argument of type java.sql.Connection.
     */
    public static void main(String[] args) {
      System.setProperty("gemfirexd.thrift-default", "false");
      try {
        String product = args[0];
        String host = args[1];
        int clientPort = Integer.parseInt(args[2]);
        File clientJar = new File(args[3]).getCanonicalFile();
        boolean useSQLFireURL = Boolean.parseBoolean(args[4]);
        String urlPrefix;
        // verify that this is really running the expected version
        switch (product) {
          case PRODUCT_SQLFIRE: {
            Class<?> clazz = Class.forName("com.vmware.sqlfire.jdbc.ClientDriver");
            URI classJarURI = clazz.getProtectionDomain().getCodeSource()
                .getLocation().toURI();
            if (!clientJar.toURI().equals(classJarURI)) {
              throw new AssertionError("Expected URI=" + clientJar.toURI() +
                  " classURI=" + classJarURI);
            }
            urlPrefix = "jdbc:sqlfire://";
            break;
          }
          case PRODUCT_GEMFIREXD: {
            Class<?> clazz = Class.forName("com.pivotal.gemfirexd.jdbc.ClientDriver");
            URI classJarURI = clazz.getProtectionDomain().getCodeSource()
                .getLocation().toURI();
            if (!clientJar.toURI().equals(classJarURI)) {
              throw new AssertionError("Expected URI=" + clientJar.toURI() +
                  " classURI=" + classJarURI);
            }
            if (useSQLFireURL) {
              urlPrefix = "jdbc:sqlfire://";
            } else {
              urlPrefix = "jdbc:gemfirexd://";
            }
            break;
          }
          default:
            throw new Exception("No PRODUCT set");
        }
        String connectString = urlPrefix + host + ':' + clientPort;
        System.out.println("Creating a client connection with connect string:" + connectString);
        Connection conn;
        int attempts = 1;
        // Try hard to get a connection
        while (true) {
          try {
            System.out.println("getConnection:attempt-" + attempts);
            Properties props = new Properties();
            props.put("load-balance", "false");
            conn = DriverManager.getConnection(connectString, props);
            if (product.equals(PRODUCT_SQLFIRE)
                || conn.getTransactionIsolation() != Connection.TRANSACTION_NONE) {
              // If we are using a SQLFire client driver, then set autocommit to
              // true explicitly.
              // GemFireXD now has default isolation level as READ_COMMITTED
              // which is piggybacked to the client
              // driver through DRDA but autocommit is not.
              conn.setAutoCommit(true);
            }
            System.out.println("Client connection created with "
                + "isolation level=" + conn.getTransactionIsolation()
                + " and autocommit=" + conn.getAutoCommit());
            break;
          } catch (SQLException e) {
            if (attempts++ < 10 && e.getSQLState().startsWith("08")) {
              Thread.sleep(1000);
            } else {
              throw e;
            }
          }
        }
        String doMethod = args[5];
        Method method = ProductClient.class.getMethod(doMethod,
            Connection.class);
        method.invoke(null, conn);
        conn.close();
      } catch (Throwable t) {
        System.out.println("ERROR: unexpected exception: " + t);
        t.printStackTrace(System.out);
        System.exit(1);
      }
    }

    public static void createDataForClient(Connection conn) throws Exception {
      System.out.println("Creating tables");
      Statement st = conn.createStatement();
      // Fire more than 4 DDLs to reproduce the record interval problem
      st.execute("create diskstore customds");
      st.execute("create table t1(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
          + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");
      st.execute("create table t2(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
          + " partition by column(id1, id2, ts) colocate with (t1) redundancy 1 persistent 'customds'");
      st.execute("create table t3(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
          + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");
      st.execute("create table t4(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
          + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");
      st.execute("create table t5(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
          + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");
      st.execute("create table t6(id1 varchar(40) not null, id2 integer not null, ts timestamp not null, other_ts timestamp, primary key(id1, id2, ts))"
          + " partition by column(id1, id2, ts) redundancy 1 persistent 'customds'");

      System.out.println("Creating indices");
      st.execute("create index idx1_t1 on t1(id2, id1)");
      st.execute("create index idx2_t1 on t2(id1, ts)");
      st.close();
      
      System.out.println("Inserting data");
      PreparedStatement ps = conn.prepareStatement("insert into t1 values(?,?,?,?)");
      for(int j=0; j < 5; j++) {
        int id = j * 100;
        ps.setString(1, String.valueOf(id));
        ps.setInt(2, id);
        ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
        ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
        ps.execute();
      }
      ps.close();
      PreparedStatement ps2 = conn.prepareStatement("insert into t2 values(?,?,?,?)");
      for(int k=0; k < 5; k++) {
        int id = k * 100;
        ps2.setString(1, String.valueOf(id));
        ps2.setInt(2, id);
        ps2.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
        ps2.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
        ps2.execute();
      }
      ps2.close();
    }
    public static void addDataForClient(Connection conn) throws SQLException {
      ProductClient.addData(conn, ProductClient.DDL_STMTS, ProductClient.DML_STMTS);
      ProductClient.addData(conn, ProductClient.DDL_STMTS_CUSTOM_DISKSTORE, ProductClient.DML_STMTS_CUSTOM_DISKSTORE);
    }
    
    public static void addDataForBug50794(Connection conn) throws SQLException {
      ProductClient.addData(conn, ProductClient.DDL_STMTS_BUG50794, ProductClient.DML_STMTS_BUG50794);
    }
    
    public static void addData(Connection conn, String[] DDLs, String[] DMLs)
        throws SQLException {
      boolean hasDDLs = (DDLs != null);
      if (hasDDLs) {
        ProductClient.runDDLs(conn, DDLs);
      }
      if (DMLs != null) {
        ProductClient.runDMLs(conn, DMLs, hasDDLs ? 1 : 11 /* more data in existing tables */);
      }
    }
    public static void runDDLs(Connection conn, String[] DDLs)
        throws SQLException {
      for (String stmt : DDLs) {
        System.out.println("DDL:" + stmt);
        conn.createStatement().execute(stmt);
      }
      System.out.println("Done executing DDLs");
    }
    public static void runDMLs(Connection conn, String[] DMLs, int startId)
        throws SQLException {
      for(String st : DMLs){
        System.out.println("DML:" + st);
        PreparedStatement s = conn.prepareStatement(st);
        java.sql.ParameterMetaData metaData = s.getParameterMetaData();
        int paramCount = metaData.getParameterCount();
        for(int id = startId; id <= (9 + startId); id++){
          
          for(int i = 1; i <= paramCount; i++)
          {
            int type = metaData.getParameterType(i);
            if(type == java.sql.Types.INTEGER){
              s.setInt(i, id);
            }
            else if(java.sql.Types.CLOB == type){
              s.setString(i, "clob"+id);
            }
            else if(java.sql.Types.VARCHAR == type){
              s.setString(i, "addr"+id);
            }
            else if(java.sql.Types.BLOB == type){
              s.setBytes(i, ("blob"+ id).getBytes());
            }
          }
          s.execute();
        }
        s.close();
      }
      System.out.println("Done executing DMLs");
    }
    public static void verifyDataForClient(Connection conn) throws SQLException {
      ProductClient.verifyData(conn, ProductClient.TABLE_NAMES);
      ProductClient.verifyData(conn, ProductClient.TABLE_NAMES_CUSTOM_DISKSTORE);
    }
    public static void verifyDataForClientBug50794(Connection conn) throws SQLException {
      ProductClient.verifyData(conn, ProductClient.TABLE_NAMES_BUG50794);
    }
    public static void verifyData(Connection conn, String[] tables)
        throws SQLException {
      SQLException exception = null;
      for (String table : tables) {
        try {
          Set<Integer> resultIds = new HashSet<Integer>();
          System.out.println("Verifying table " + table);
          ResultSet rs = conn.createStatement().executeQuery(
              "select * from upgradetest." + table);

          ResultSetMetaData rsm = rs.getMetaData();
          int colCount = rsm.getColumnCount();
          while (rs.next()) {
            int resId = rs.getInt(1);
            if (resultIds.contains(resId)) {
              throw new AssertionError("double result for " + resId);
            }
            if (resId <= 0) {
              throw new AssertionError("unexpected ID " + resId);
            }
            resultIds.add(resId);

            for (int i = 2; i <= colCount; i++) {
              int colType = rsm.getColumnType(i);
              if (colType == java.sql.Types.VARCHAR
                  || colType == java.sql.Types.CHAR
                  || colType == java.sql.Types.CLOB) {
                String value = rs.getString(i);
                if (value == null) {
                  throw new AssertionError("unexpected null value");
                }
              }
              else if (colType == java.sql.Types.BLOB) {
                byte[] value = rs.getBytes(i);
                if (value == null) {
                  throw new AssertionError("unexpected null value");
                }
              }
              else if (colType == java.sql.Types.INTEGER) {
                int value = rs.getInt(i);
                if (value <= 0) {
                  throw new AssertionError("unexpected value=" + value);
                }
              }
            }
          }
          rs.close();
        } catch (SQLException e) {
          exception = e;
        }
      }
      if (exception != null) {
        // Rethrow the last exception
        throw exception;
      }
    }
  }

  /**
   * Class containing the "main" method to be run on each of the older version
   * client jars. We do this way rather than just running sql scripts is to
   * enable testing batch inserts etc.
   */
  
  public static class ClientRun {

    /**
     * Expects four arguments: a) host where server is running, b) port where
     * server is listening, c) number of inserts in one batch, d) expected
     * gemfirexd-client.jar path that will be used
     */
    public static void main(String[] args) {

      try {
        String host = args[0];
        int clientPort = Integer.parseInt(args[1]);
        int numInserts = Integer.parseInt(args[2]);

        // verify that this is really running the expected version
        File gemfirexdclientJar = new File(args[3]).getCanonicalFile();
        Class<?> clazz = Class.forName("com.pivotal.gemfirexd.jdbc.ClientDriver");
        assertEquals(gemfirexdclientJar.toURI(), clazz.getProtectionDomain()
            .getCodeSource().getLocation().toURI());

        // get a client connection and then execute the DDLs and load data
        Connection conn = DriverManager.getConnection("jdbc:gemfirexd://" + host
            + ':' + clientPort);
        Statement stmt = conn.createStatement();

        int id = 1;
        for (String ddl : DDLS) {
          if (ddl.startsWith("drop")) {
            // execute DMLs before the drop
            runDMLs(id, numInserts, conn);
            id += numInserts;
          }
          stmt.execute(ddl);
        }
        // execute batch DMLs at the end
        runDMLsAsBatch(id, numInserts, conn);
        id += numInserts;

        conn.close();

        // query back the data just inserted
        conn = DriverManager.getConnection("jdbc:gemfirexd://" + host + ':'
            + clientPort);
        stmt = conn.createStatement();

        verifyData(conn, id, false);

        conn.close();
      } catch (Throwable t) {
        System.out.println("ERROR: unexpected exception: " + t);
        t.printStackTrace(System.out);
        System.exit(1);
      }
    }
  }

  private int startLonerVersionedServer(int listIdx, int verIdx,
      int checkIdx, String workingDir) throws Exception {
    String version = compatibleVersionLists[listIdx][verIdx];
    String versionDir = compatibleVersionProductDirs[listIdx][verIdx];

    ProcessStart versionedServer = startVersionedServer(version, versionDir,
        workingDir, 0, 0, false);
    waitForProcesses(versionedServer);
    return versionedServer.port;
  }

  private static void runDMLs(final int start, final int numInserts,
      final Connection conn) throws SQLException {
    final int last = start + numInserts;
    PreparedStatement pstmt;
    int numParams;
    for (String dml : DMLS) {
      pstmt = conn.prepareStatement(dml);
      numParams = pstmt.getParameterMetaData().getParameterCount();
      for (int id = start; id < last; id++) {
        if (numParams == 1) {
          pstmt.setString(1, "addr" + id);
        }
        else {
          pstmt.setInt(1, id);
          pstmt.setString(2, "addr" + id);
        }
        pstmt.executeUpdate();
      }
    }
  }

  private static void runDMLsAsBatch(final int start, final int numInserts,
      final Connection conn) throws SQLException {
    final int last = start + numInserts;
    PreparedStatement pstmt;
    int numParams;
    for (String dml : DMLS) {
      pstmt = conn.prepareStatement(dml);
      numParams = pstmt.getParameterMetaData().getParameterCount();
      for (int id = start; id < last; id++) {
        if (numParams == 1) {
          pstmt.setString(1, "addr" + id);
        }
        else {
          pstmt.setInt(1, id);
          pstmt.setString(2, "addr" + id);
        }
        pstmt.addBatch();
      }
      pstmt.executeBatch();
    }
  }
  public static void verifyData(String[] tables, Properties props) throws SQLException{
    Connection conn = TestUtil.getConnection(props);
    verifyData(conn, tables);
  }

  public static void verifyData(Connection conn, String[] tables)
      throws SQLException {
    ProductClient.verifyData(conn, tables);
  }
  
  public static void verifyData(String[] tables) throws SQLException{
    verifyData(tables, null);
  }
  private static void verifyData(final Connection conn, final int limit,
      final boolean checkRecovery) throws SQLException {

    for (Object[] tableObj : TABLES) {
      Set<Integer> resultIds = new HashSet<Integer>();
      final String table = (String)tableObj[0];
      final int offset = (Integer)tableObj[2];
      getGlobalLogger().info("checking data for table " + table);
      ResultSet rs = conn.createStatement().executeQuery(
          "select * from " + table);
      while (rs.next()) {
        int resId = rs.getInt(1);
        if (resultIds.contains(resId)) {
          fail("double result for " + resId);
        }
        assertTrue("unexpected ID " + resId, resId > 0);
        assertTrue("unexpected ID " + resId + " offset " + offset
            + " with limit " + limit, (resId + offset) < limit);
        assertEquals("addr" + (resId + offset), rs.getString(2));
        resultIds.add(resId);
      }
      rs.close();
      if (!checkRecovery || (Boolean)tableObj[1]) {
        assertEquals(limit - 1 - offset, resultIds.size());
      }
      else {
        assertEquals(0, resultIds.size());
      }
    }
  }

  private void dropDatabaseObjects(String host, int clientPort)
      throws SQLException {
    Connection conn = TestUtil.getNetConnection(host, clientPort, null, null);
    Statement stmt = conn.createStatement();
    for (String index : INDEXES) {
      getLogWriter().info("dropping index " + index);
      stmt.execute("drop index " + index);
    }
    for (Object[] tableObj : TABLES) {
      getLogWriter().info("dropping table " + tableObj[0]);
      stmt.execute("drop table " + tableObj[0]);
    }
    conn.close();
  }

  // Note: the return value has client-port
  private ProcessStart startVersionedLocator(String version, String versionDir,
      String workingDir, boolean withAuth, int locatorPort1, int locatorPort2)
      throws Exception {

    String gfxdDir = GCM_WHERE + '/' + versionDir;
    String utilLauncher = getUtilLauncher(gfxdDir);
    final int clientPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    getLogWriter().info(
        "Starting " +product +" locator version " + version + " from " + gfxdDir + " on port "
            + locatorPort1);

    final String logFile = getTestLogNamePrefix() + "-locator" + version + ".log";
    
    String[] startOps = null; 
        
    if(withAuth){
      startOps = new String[] { utilLauncher, "locator", "start",
          "-dir=" + workingDir, 
          "-log-file=" + logFile, "-peer-discovery-port=" + locatorPort1,
          "-heap-size=512m", "-auth-provider=BUILTIN",
          "-gemfirexd.sql-authorization=TRUE",
          "-gemfirexd.user.SYSADMIN=SA",
          "-user=SYSADMIN",
          "-password=SA",
          "-locators=localhost:" + locatorPort2,
          "-J-Dgemfirexd.thrift-default=false",
          "-client-port=" + clientPort};
    }
    else{
      startOps = new String[] { utilLauncher, "locator", "start",
          "-dir=" + workingDir, 
          "-log-file=" + logFile, "-peer-discovery-port=" + locatorPort1,
          "-heap-size=512m", "-locators=localhost:" + locatorPort2,
          "-J-Dgemfirexd.thrift-default=false",
          "-client-port=" + clientPort};
    }

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncLocatorStart(serverProc, locatorPort1);
    return new ProcessStart(serverProc, clientPort, true, version, logFile);
  }

  private void shutdownAllCurrent(int locatorPort) throws Exception {
    String utilLauncher = getCurrentUtilLauncher();

    String logFile = getTestLogNamePrefix() + "-shutdownall-current.log";
    final String[] startOps = new String[] { utilLauncher, "shut-down-all",
        "-locators=localhost[" + locatorPort + "]",
        "-log-file="+logFile,
        "-log-level=fine"};

    final Process serverProc = new ProcessBuilder(startOps).start();
    final int exitValue = serverProc.waitFor();
    if (exitValue != 0) {
      throw new TestException("Unexpected exit value " + exitValue
          + " during shut-down-all for current version ");
    }
  }
  
  private void shutdownAll(String version, String versionDir, int locatorPort) throws Exception{

    String gfxdDir = GCM_WHERE + '/' + versionDir;
    String utilLauncher = getUtilLauncher(gfxdDir);

    final String[] startOps = new String[] { utilLauncher, "shut-down-all",
        "-locators=localhost[" + locatorPort + "]" };

    final Process serverProc = new ProcessBuilder(startOps).start();
    final int exitValue = serverProc.waitFor();
    if (exitValue != 0) {
      throw new TestException("Unexpected exit value " + exitValue
          + " during shut-down-all for version " + version + '.');
    }
  }
  
  public void DISABLED_testBug47476() throws Exception{

    final int locatorPort1 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    
    final int locatorPort2 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);

    getLogWriter().info(
        "Starting locator1 version: " + rollingUpgradeBaseVersion
            + " with working dir: " + vm1WorkingDir);
    ProcessStart versionedLocator1 = startVersionedLocator(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm1WorkingDir,
        false, locatorPort1, locatorPort2);

    getLogWriter().info(
        "Starting locator2 version: " + rollingUpgradeBaseVersion
            + " with working dir: " + vm2WorkingDir);
    ProcessStart versionedLocator2 = startVersionedLocator(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm2WorkingDir,
        false, locatorPort2, locatorPort1);

    getLogWriter().info(
        "Starting server1 version: " + rollingUpgradeBaseVersion
            + " with working dir: " + vm3WorkingDir);
    ProcessStart versionedServer1 = startVersionedServer(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm3WorkingDir,
        0, locatorPort1, false);
    int clientPort1 = versionedServer1.port;

    getLogWriter().info(
        "Starting server2 version: " + rollingUpgradeBaseVersion
            + " with working dir: " + vm4WorkingDir);
    ProcessStart versionedServer2 = startVersionedServer(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm4WorkingDir, 0, locatorPort1, false);

    waitForProcesses(versionedLocator1, versionedLocator2, versionedServer1,
        versionedServer2);

    //starting rolling upgrade
    String newVersion = "1.1.0";
    
    String newVersionDir = "gemfirexd/releases/GemFireXD1.1.0-all";

    stopVersionedLocator(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm1WorkingDir);

    waitForProcesses(startVersionedLocator(newVersion, newVersionDir,
        vm1WorkingDir, false, locatorPort1, locatorPort2));

    stopVersionedLocator(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm2WorkingDir);

    waitForProcesses(startVersionedLocator(newVersion, newVersionDir,
        vm2WorkingDir, false, locatorPort2, locatorPort1));

    stopVersionedServer(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm3WorkingDir);

    waitForProcesses(startVersionedServer(newVersion, newVersionDir,
        vm3WorkingDir, 0, locatorPort1, false));

    stopVersionedServer(rollingUpgradeBaseVersion,
        rollingUpgradeBaseVersionDir, vm4WorkingDir);

    waitForProcesses(startVersionedServer(newVersion, newVersionDir,
        vm4WorkingDir, 0, locatorPort1, false));

    shutdownAll(newVersion, newVersionDir, locatorPort1);

    stopVersionedLocator(newVersion, newVersionDir, vm1WorkingDir);

    stopVersionedLocator(newVersion, newVersionDir, vm2WorkingDir);

    // Stop everything 

    stopAllVMs();

    String[] defaultStoreDirs = new String[]{vm1WorkingDir, 
        vm2WorkingDir, 
        vm3WorkingDir, 
        vm4WorkingDir
        };
    cleanUp(defaultStoreDirs);
  }
  
  // Use with THin Client Test
  static boolean[] remoteCallbackInvoked = new boolean[] { false, false, false,
      false, false };

  void createTable_TestBug47753(Connection conn, String tag) throws Exception {
    Statement s = conn.createStatement();
    s.execute("create schema trade" + tag);
    s.execute("create table trade" + tag
        + ".t1 ( id int, name varchar(10), type int primary key) "
        + "partition by primary key");
    s.execute("create INDEX trade" + tag + ".t1index on t1(id)");
  }

  void insertData_TestBug47753(Connection conn, String tag) throws Exception {
    Statement s = conn.createStatement();
    s.execute("Insert into  trade" + tag + ".t1 values(1,'vivek',1)");
    s.execute("Insert into  trade" + tag + ".t1 values(2,'vivek',2)");
    s.execute("Insert into  trade" + tag + ".t1 values(3,'vivek',3)");
    s.execute("Insert into  trade" + tag + ".t1 values(4,'vivek',4)");
    s.execute("Insert into  trade" + tag + ".t1 values(5,'vivek',5)");
  }

  void dropTable_TestBug47753(Connection conn, String tag) throws Exception {
    Statement s = conn.createStatement();
    s.execute("drop index if exists trade" + tag + ".t1index ");
    s.execute("drop table if exists trade" + tag + ".t1 ");
    s.execute("drop schema if exists trade" + tag + " restrict");
  }

  String getQuery_TestBug47753(String tag) {
    return "select type, id, name from trade" + tag + ".t1 where id IN (?,?,?)";
  }

  void verifyData_TestBug47753(Connection conn, String query) throws Exception {
    PreparedStatement ps1 = conn.prepareStatement(query);
    ps1.setInt(1, 1);
    ps1.setInt(2, 2);
    ps1.setInt(3, 3);
    ResultSet rs = ps1.executeQuery();
    Set<Integer> hashi = new HashSet<Integer>();
    hashi.add(1);
    hashi.add(2);
    hashi.add(3);
    while (rs.next()) {
      assertTrue(hashi.remove(rs.getInt(1)));
    }
    assertTrue(hashi.isEmpty());
  }

  public void DISABLED_testBug47753() throws Exception {
    final GemFireXDQueryObserver oldGetAllObserver = new GemFireXDQueryObserverAdapter() {
      @Override
      public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
          GenericPreparedStatement gps, LanguageConnectionContext lcc) {
        if (qInfo instanceof SelectQueryInfo) {
          remoteCallbackInvoked[0] = true;
          assertTrue(qInfo instanceof SelectQueryInfo);
          SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
          assertFalse(sqi.isPrimaryKeyBased());
          assertFalse(sqi.isGetAllOnLocalIndex());
          assertTrue(sqi.isDynamic());
          assertEquals(3, sqi.getParameterCount());
          Object[] pks = (Object[])sqi.getPrimaryKey();
          assertNull(pks);
          try {
            assertTrue(sqi.createGFEActivation());
          } catch (Exception e) {
            e.printStackTrace();
            fail(e.toString());
          }
        }
      }

      @Override
      public void createdGemFireXDResultSet(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
        if (rs instanceof GemFireDistributedResultSet) {
          remoteCallbackInvoked[1] = true;
        }
      }

      @Override
      public void getAllInvoked(int numElements) {
        remoteCallbackInvoked[3] = true;
      }

      @Override
      public void getAllLocalIndexExecuted() {
        remoteCallbackInvoked[4] = true;
      }

      @Override
      public void getAllLocalIndexInvoked(int numElements) {
        remoteCallbackInvoked[2] = true;
        assertEquals(0, numElements);
      }
    };

    final GemFireXDQueryObserver newGetAllObserver = new GemFireXDQueryObserverAdapter() {
      @Override
      public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
          GenericPreparedStatement gps, LanguageConnectionContext lcc) {
        if (qInfo instanceof SelectQueryInfo) {
          remoteCallbackInvoked[0] = true;
          assertTrue(qInfo instanceof SelectQueryInfo);
          SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
          assertFalse(sqi.isPrimaryKeyBased());
          assertTrue(sqi.isGetAllOnLocalIndex());
          assertTrue(sqi.isDynamic());
          assertEquals(3, sqi.getParameterCount());
          Object[] pks = (Object[])sqi.getPrimaryKey();
          assertNull(pks);
          try {
            assertTrue(sqi.createGFEActivation());
          } catch (Exception e) {
            e.printStackTrace();
            fail(e.toString());
          }
        }
      }

      @Override
      public void createdGemFireXDResultSet(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
        if (rs instanceof GemFireResultSet) {
          remoteCallbackInvoked[1] = true;
        }
      }

      @Override
      public void getAllInvoked(int numElements) {
        remoteCallbackInvoked[3] = true;
      }

      @Override
      public void getAllLocalIndexExecuted() {
        remoteCallbackInvoked[4] = true;
      }

      @Override
      public void getAllLocalIndexInvoked(int numElements) {
        remoteCallbackInvoked[2] = true;
        assertEquals(3, numElements);
      }
    };

    SerializableRunnable getAllSetOld = new SerializableRunnable(
        "Set GetAll Observer for Old") {
      @Override
      public void run() throws CacheException {
        remoteCallbackInvoked[0] = false;
        remoteCallbackInvoked[1] = false;
        remoteCallbackInvoked[2] = false;
        remoteCallbackInvoked[3] = false;
        remoteCallbackInvoked[4] = false;
        GemFireXDQueryObserverHolder.setInstance(oldGetAllObserver);
      }
    };

    SerializableRunnable getAllSetNew = new SerializableRunnable(
        "Set GetAll Observer for New") {
      @Override
      public void run() throws CacheException {
        remoteCallbackInvoked[0] = false;
        remoteCallbackInvoked[1] = false;
        remoteCallbackInvoked[2] = false;
        remoteCallbackInvoked[3] = false;
        remoteCallbackInvoked[4] = false;
        GemFireXDQueryObserverHolder.setInstance(newGetAllObserver);
      }
    };

    SerializableRunnable getAllObsReset = new SerializableRunnable(
        "Reset GetAll Observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
            });
      }
    };

    SerializableRunnable getAllObsVerify_oldVersion = new SerializableRunnable(
        "Verify GetAll Observer For older Version") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[0]);
        assertTrue(remoteCallbackInvoked[1]);
        assertFalse(remoteCallbackInvoked[2]);
        assertFalse(remoteCallbackInvoked[3]);
        assertFalse(remoteCallbackInvoked[4]);
      }
    };

    SerializableRunnable getAllObsVerify_newVersion = new SerializableRunnable(
        "Verify GetAll Observer For new Version") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[0]);
        assertTrue(remoteCallbackInvoked[1]);
        assertTrue(remoteCallbackInvoked[2]);
        assertFalse(remoteCallbackInvoked[3]);
        assertTrue(remoteCallbackInvoked[4]);
      }
    };

    SerializableRunnable clearStmtCache = new SerializableRunnable(
        "Clear Stmt Cache") {
      @Override
      public void run() throws CacheException {
        try {
          TestUtil.clearStatementCache();
          remoteCallbackInvoked[3] = true;
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }
    };

    SerializableRunnable verifyStmtCache = new SerializableRunnable(
        "Verify Stmt Cache") {
      @Override
      public void run() throws CacheException {
        assertTrue(remoteCallbackInvoked[3]);
      }
    };

    /**********************************************************************/
    // for some defect make it false
    String persistDD = "false";

    // Locator ports
    final int rollingVersionLocatorPort1 = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final int rollingVersionLocatorPort2 = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    SanityManager
        .ASSERT(rollingVersionLocatorPort1 != rollingVersionLocatorPort2);
    String locators = "localhost[" + rollingVersionLocatorPort1 + "]" + ","
        + "localhost[" + rollingVersionLocatorPort2 + "]";
    Properties props = new Properties();
    props.setProperty(DistributionConfig.LOCATORS_NAME, locators);
    props.setProperty("persist-dd", persistDD);
    props.setProperty("log-level", getLogLevel());

    // 1st Locator
    ProcessStart versionedLocator1 = startVersionedLocator(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm1WorkingDir,
        rollingVersionLocatorPort1, locators, false, persistDD);

    // 2nd locator
    ProcessStart versionedLocator2 = startVersionedLocator(
        rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir, vm2WorkingDir,
        rollingVersionLocatorPort2, locators, false, persistDD);

    final Properties connClientProps = new Properties();
    connClientProps.setProperty("single-hop-enabled", "false");
    connClientProps.setProperty("single-hop-max-connections", "5");
    if (getLogLevel().startsWith("fine")) {
      connClientProps.setProperty("gemfirexd.debug.true", "TraceSingleHop");
    }

    int oldVersionClientPort = 0;
    { // Start servers, Create table and verify data
      ProcessStart versionedServer1 = startVersionedServer(
          rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir,
          vm3WorkingDir, 0, locators, false, persistDD);
      int clientPort = versionedServer1.port;
      ProcessStart versionedServer2 = startVersionedServer(
          rollingUpgradeBaseVersion, rollingUpgradeBaseVersionDir,
          vm4WorkingDir, 0, locators, false, persistDD);
      oldVersionClientPort = versionedServer2.port;

      waitForProcesses(versionedLocator1, versionedLocator2, versionedServer1,
          versionedServer2);

      Connection conn = TestUtil.getNetConnection("localhost", clientPort,
          null, connClientProps);
      createTable_TestBug47753(conn, "1");
      insertData_TestBug47753(conn, "1");
      verifyData_TestBug47753(conn, getQuery_TestBug47753("1"));
      dropTable_TestBug47753(conn, "1");
    }

    // locators vm numbers are as server
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.put(DistributionConfig.MAX_WAIT_TIME_FOR_RECONNECT_NAME, "15000");

    {// locator stat stop // vm - 1
      getLogWriter().info(
          "Stopping first " + rollingUpgradeBaseVersion + "locator");
      stopVersionedLocator(rollingUpgradeBaseVersion,
          rollingUpgradeBaseVersionDir, vm1WorkingDir);

      getLogWriter().info("Starting first locator");
      startLocatorVM("localhost", rollingVersionLocatorPort1, null, props);
    }

    {// locator start stop // vm -2
      getLogWriter().info(
          "Stopping Second " + rollingUpgradeBaseVersion + "locator");
      stopVersionedLocator(rollingUpgradeBaseVersion,
          rollingUpgradeBaseVersionDir, vm2WorkingDir);

      getLogWriter().info("Starting second locator");
      startLocatorVM("localhost", rollingVersionLocatorPort2, null, props);
    }

    try { // start stop servers vm: 3 and Verify Data
      getLogWriter().info(
          "Stopping first " + rollingUpgradeBaseVersion + "server");
      stopVersionedServer(rollingUpgradeBaseVersion,
          rollingUpgradeBaseVersionDir, vm3WorkingDir);

      getLogWriter().info("Starting current version server");
      // TODO - start network server on it. with clientPort1
      startServerVMs(1, 0, null, props);

      // Start network server on the VMs
      final int netPort = startNetworkServer(3, null, null);

      // Use this VM as the network client
      TestUtil.loadNetDriver();
      Connection oldConn = TestUtil.getNetConnection("localhost",
          oldVersionClientPort, null, connClientProps);
      createTable_TestBug47753(oldConn, "2");
      insertData_TestBug47753(oldConn, "2");
      verifyData_TestBug47753(oldConn, getQuery_TestBug47753("2"));

      String url = TestUtil.getNetProtocol("localhost", netPort);
      Connection newConn = DriverManager.getConnection(url,
          TestUtil.getNetProperties(connClientProps));
      serverExecute(3, clearStmtCache);
      serverExecute(3, verifyStmtCache);
      serverExecute(3, getAllSetOld);
      try {
        verifyData_TestBug47753(newConn, getQuery_TestBug47753("2"));
      } catch (Throwable t) {
        // Do nothing
        // TODO - need to be resolved by #47753
      }
      dropTable_TestBug47753(oldConn, "2");
      try {
        serverExecute(3, getAllObsVerify_oldVersion);
      } catch (Throwable t) {
        // Do nothing
        // TODO - need to be resolved by #47753
      }
      {
        /**
         * TODO: Should not Pass. Remove this block. Code just added to prove
         * Backward compatibility is broken in cheetah.
         */
        serverExecute(3, getAllObsReset);
        serverExecute(3, getAllSetNew);
        createTable_TestBug47753(newConn, "4");
        insertData_TestBug47753(newConn, "4");
        verifyData_TestBug47753(newConn, getQuery_TestBug47753("4"));
        dropTable_TestBug47753(newConn, "4");
        serverExecute(3, getAllObsVerify_newVersion);
      }
    } finally {
      serverExecute(3, getAllObsReset);
    }

    try { // start stop servers vm: 4 and Verify Data
      getLogWriter().info(
          "Stopping second " + rollingUpgradeBaseVersion + " server");
      stopVersionedServer(rollingUpgradeBaseVersion,
          rollingUpgradeBaseVersionDir, vm4WorkingDir);

      getLogWriter().info("Starting current version server");
      startServerVMs(1, 0, null, props);

      // Start network server on the VMs
      final int netPort = startNetworkServer(4, null, null);

      TestUtil.loadNetDriver();
      String url = TestUtil.getNetProtocol("localhost", netPort);
      Connection conn = DriverManager.getConnection(url,
          TestUtil.getNetProperties(connClientProps));
      serverExecute(4, clearStmtCache);
      serverExecute(4, verifyStmtCache);
      serverExecute(4, getAllSetNew);
      createTable_TestBug47753(conn, "3");
      insertData_TestBug47753(conn, "3");
      verifyData_TestBug47753(conn, getQuery_TestBug47753("3"));
      dropTable_TestBug47753(conn, "3");
      serverExecute(4, getAllObsVerify_newVersion);
    } finally {
      serverExecute(4, getAllObsReset);
    }

    // Stop everything
    stopVMNums(-1, -2, -3, -4);

    String[] customStoreDirs = new String[] { vm1WorkingDir + "/dir1",
        vm2WorkingDir + "/dir1", vm3WorkingDir + "/dir1",
        vm4WorkingDir + "/dir1" };
    cleanUpCustomStoreFiles(customStoreDirs);
    cleanUp(new String[] { vm1WorkingDir, vm2WorkingDir, vm3WorkingDir,
        vm4WorkingDir });
  }
}
