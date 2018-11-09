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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

/**
 * Tests for disk store creation and persist-dd configuration
 * 
 * @author asif
 * @author Yogesh
 * @since 6.0
 */
@SuppressWarnings("serial")
public class CreateDiskStoreDUnit extends DistributedSQLTestBase {

  public CreateDiskStoreDUnit(String name) {
    super(name);
  }

  @SuppressWarnings("unchecked")
  public void testDistributionOfDiskStoreCreate() throws Exception {
    // Start one client a two servers
    startVMs(1, 2);
    // check DiskStoreIDs VTI for default diskstores
    Statement stmt = TestUtil.getStatement();
    checkDiskStores(stmt, 2, new GemFireXDUtils.Pair[0]);

    clientSQLExecute(1, "create diskstore TEST");
    // Test the DiskStore presence on servers
    sqlExecuteVerify(null, new int[] { 1, 2 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist1");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml", "empty");
    clientExecute(1, verifyNoDiskStoreExistence("TEST"));
    SerializableRunnable verifier = verifyDiskStoreExistence("TEST");
    for (int i = 1; i < 3; ++i) {
      serverExecute(i, verifier);
    }
    // check DiskStoreIDs VTI for default and added diskstore
    GemFireXDUtils.Pair<String, String> testDS = new GemFireXDUtils.Pair<>(
        "TEST", null);
    checkDiskStores(stmt, 2, new GemFireXDUtils.Pair[] { testDS });

    startVMs(0, 1);
    sqlExecuteVerify(null, new int[] { 3 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist1");
    serverExecute(3, verifier);
    // check DiskStoreIDs VTI on the new server
    checkDiskStores(stmt, 3, new GemFireXDUtils.Pair[] { testDS });

    sqlExecuteVerify(null, new int[] { 1, 2, 3 },
        "select NAME from SYS.SYSDISKSTORES  ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist2");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml", "empty");

    // check invalid oplog size with disk dir size (#51632)
    try {
      serverSQLExecute(1,
          "CREATE DISKSTORE teststore1 MAXLOGSIZE 100 ('testdir1' 50)");
      fail("exception expected when creating with oplog size > diskdir size");
    } catch (RMIException re) {
      if (!(re.getCause() instanceof SQLException)
          || !"X0Z33".equals(((SQLException)re.getCause()).getSQLState())) {
        throw re;
      }
    }
    try {
      clientSQLExecute(1,
          "CREATE DISKSTORE teststore1 MAXLOGSIZE 100 ('testdir1' 50)");
      fail("exception expected when creating with oplog size > diskdir size");
    } catch (SQLException sqle) {
      if (!"X0Z33".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    checkDiskStores(stmt, 3, new GemFireXDUtils.Pair[] { testDS });

    // now valid sizes
    clientSQLExecute(1,
        "CREATE DISKSTORE teststore1 MAXLOGSIZE 100 ('testdir1' 150)");
    serverSQLExecute(3,
        "CREATE TABLE gfxdtest.cfg_config ("
            + "id bigint NOT NULL, "
            + "name varchar(255) NOT NULL, "
            + "config blob NOT NULL, "
            + "PRIMARY KEY (id)) "
            + "EVICTION BY LRUCOUNT 1 EVICTACTION OVERFLOW "
            + "PERSISTENT 'teststore1' ASYNCHRONOUS");
    serverSQLExecute(3,
        "CREATE TABLE gfxdtest.cfg_config2 ("
            + "id bigint NOT NULL, "
            + "name varchar(255) NOT NULL, "
            + "config blob NOT NULL, "
            + "PRIMARY KEY (id)) PARTITION BY PRIMARY KEY "
            + "EVICTION BY LRUCOUNT 1 EVICTACTION OVERFLOW "
            + "PERSISTENT 'teststore1' ASYNCHRONOUS");

    // Test the DiskStore presence on servers
    sqlExecuteVerify(null, new int[] { 1, 2, 3 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TESTSTORE1' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist3");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TESTSTORE1' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml", "empty");
    clientExecute(1, verifyNoDiskStoreExistence("TESTSTORE1"));
    verifier = verifyDiskStoreExistence("TESTSTORE1");
    for (int i = 1; i <= 3; ++i) {
      serverExecute(i, verifier);
    }
    // check DiskStoreIDs VTI for the new diskstore and directory
    GemFireXDUtils.Pair<String, String> testStore1DS = new GemFireXDUtils.Pair<>(
        "TESTSTORE1", "testdir1");
    checkDiskStores(stmt, 3, new GemFireXDUtils.Pair[] { testDS, testStore1DS });

    startVMs(0, 1);
    sqlExecuteVerify(null, new int[] { 4 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist1");
    sqlExecuteVerify(null, new int[] { 4 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TESTSTORE1' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist3");
    serverExecute(4, verifier);

    sqlExecuteVerify(null, new int[] { 1, 2, 3, 4 },
        "select NAME from SYS.SYSDISKSTORES",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist4");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TESTSTORE1'",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml", "empty");
    // check DiskStoreIDs VTI for the new server
    checkDiskStores(stmt, 4, new GemFireXDUtils.Pair[] { testDS, testStore1DS });

    // some inserts into the table and verify
    for (int i = 1; i <= 4; i++) {
      clientSQLExecute(1, "insert into gfxdtest.cfg_config values (" + i
          + ", 'name" + i + "', X'D" + i + "')");
      clientSQLExecute(1, "insert into gfxdtest.cfg_config2 values (" + i
          + ", 'name" + i + "', X'D" + i + "')");
    }
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select * from gfxdtest.cfg_config",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist5");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select * from gfxdtest.cfg_config2",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist5");
    checkDiskStores(stmt, 4, new GemFireXDUtils.Pair[] { testDS, testStore1DS });

    clientSQLExecute(1, "drop diskstore test");
    checkDiskStores(stmt, 4, new GemFireXDUtils.Pair[] { testStore1DS });
    clientSQLExecute(1, "drop table gfxdtest.cfg_config");
    clientSQLExecute(1, "drop table gfxdtest.cfg_config2");
    checkDiskStores(stmt, 4, new GemFireXDUtils.Pair[] { testStore1DS });
    clientSQLExecute(1, "drop diskstore teststore1");
    checkDiskStores(stmt, 4, new GemFireXDUtils.Pair[0]);
    stmt.close();

    for (int serverNum : new int[] { 1, 2, 3, 4 }) {
      serverExecute(serverNum, new SerializableRunnable() {
        @Override
        public void run() {
          String sysDirName = getSysDirName();
          assertTrue(new File(sysDirName, "testdir1").delete());
        }
      });
    }
  }

  @SuppressWarnings("unchecked")
  public void testDistributionOfDiskStoreDrop() throws Exception {
    // Start one client and two servers
    startVMs(1, 2);
    // check DiskStoreIDs VTI for default diskstores
    Statement stmt = TestUtil.getStatement();
    checkDiskStores(stmt, 2, new GemFireXDUtils.Pair[0]);

    clientSQLExecute(1, "create diskstore TEST");
    // Test the DiskStore presence on servers
    sqlExecuteVerify(null, new int[] { 1, 2 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "ddl-dist1");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml", "empty");
    clientExecute(1, verifyNoDiskStoreExistence("TEST"));
    serverExecute(1, verifyDiskStoreExistence("TEST"));
    serverExecute(2, verifyDiskStoreExistence("TEST"));
    // check DiskStoreIDs VTI for default and added diskstore
    GemFireXDUtils.Pair<String, String> testDS = new GemFireXDUtils.Pair<>(
        "TEST", null);
    checkDiskStores(stmt, 2, new GemFireXDUtils.Pair[] { testDS });
    clientSQLExecute(1, "drop diskstore TEST");
    serverExecute(1, verifyNoDiskStoreExistence("TEST"));
    serverExecute(2, verifyNoDiskStoreExistence("TEST"));
    checkDiskStores(stmt, 2, new GemFireXDUtils.Pair[0]);
    startVMs(0, 1);
    sqlExecuteVerify(null, new int[] { 3 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "empty");
    serverExecute(3, verifyNoDiskStoreExistence("TEST"));

    sqlExecuteVerify(null, new int[] { 1, 2, 3 },
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST'",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml",
        "empty");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSDISKSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkDiskStore.xml", "empty");

    // check DiskStoreIDs VTI for the new server
    checkDiskStores(stmt, 3, new GemFireXDUtils.Pair[0]);
  }
  
  public void testPersistDDonRecoveredClient() throws Exception {
    // Start one server
    Properties props = new Properties();
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    AsyncVM async1 = invokeStartServerVM(1, 0, null, props);
    // Start a second server with DD persistence
    AsyncVM async2 = invokeStartServerVM(2, 0, null, props);

    // Start a client
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    // add expected exception
    String expectedEx = "persist-dd property should be false for clients";
    props.put(TestUtil.EXPECTED_STARTUP_EXCEPTIONS, new Object[] {
        "Failed to start database", expectedEx });
    try {
      startVMs(1, 0, 0, null, props);
      fail("Test should have failed with SQLException saying persist-dd "
          + "property should be false for clients");
    } catch (SQLException e) {
      if (!e.getCause().getMessage().startsWith(expectedEx)) {
        fail("Got unexpected exception :", e);
      }
    }
    props.remove(TestUtil.EXPECTED_STARTUP_EXCEPTIONS);
    props.setProperty(Attribute.GFXD_PERSIST_DD, "false");
    startVMs(1, 0, 0, null, props);
    // wait for servers to start
    joinVMs(true, async1, async2);

    // Create a table
    clientSQLExecute(
        1,
        "create table TESTTABLE (ID int not null primary key,DESCRIPTION varchar(1024))");
    clientSQLExecute(1, "insert into TESTTABLE values (1, 'ok')");

    // Restart everything and recheck
    stopVMNums(1);
    stopVMNums(-2, -1);

    // verify that nothing is running
    checkVMsDown(this.clientVMs.get(0), this.serverVMs.get(0),
        this.serverVMs.get(1));

    restartVMNums(-2, -1); //restart with default persist-dd
    restartVMNums(1);//restart with default persist-dd

    joinVMs(false, async1, async2);
    // make sure that client receives DDL from the DataDictionary
    clientSQLExecute(1, "insert into TESTTABLE values (2, 'still_ok')");
    clientSQLExecute(1, "drop table TESTTABLE");
  }
  
  public void testPersistDDOnNotConnectedClients() throws Exception {
    // Start one server
    AsyncVM async1 = invokeStartServerVM(1, 0, null, null);
    // Start a second server with DD persistence
    AsyncVM async2 = invokeStartServerVM(2, 0, null, null);

    startVMs(1, 0, 0, null, null);
    // wait for servers to start
    joinVMs(true, async1, async2);

    // Create a table
    clientSQLExecute(
        1,
        "create table TESTTABLE (ID int not null primary key,DESCRIPTION varchar(1024))");
    clientSQLExecute(1, "insert into TESTTABLE values (1, 'ok')");

    stopVMNums(-2, -1);

    // verify that nothing is running
    checkVMsDown(this.serverVMs.get(0), this.serverVMs.get(1));

    // make sure that client receives DDL from the DataDictionary
    try {
      clientSQLExecute(1, "drop table TESTTABLE");// should throw an exception
      fail("Test should have failed with SQLException saying DDL can not be "
          + "executed since no servers are available");
    } catch (SQLException sqle) {
      if (!"X0Z08".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    try {
      // make sure the last DDL was not executed even on client
      clientSQLExecute(1, "insert into TESTTABLE values (1, 'ok')");
      fail("Test should have failed due to lack of data stores");
    } catch (SQLException sqle) {
      assertEquals(sqle.getSQLState(), "X0Z08");
    }

    restartVMNums(-2, -1);
    joinVMs(false, async1, async2);
    
    clientSQLExecute(1, "drop table TESTTABLE");// should succeed
    // how to ensure that table is dropped ?
  }

  public void testCompatiblePersistDDOnServers() throws Exception {
    Properties props = new Properties();
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    startVMs(0, 1, 0, null, props);
    startVMs(0, 1, 0, null, props);
    props.setProperty(Attribute.GFXD_PERSIST_DD, "false");
    startVMs(1, 0, 0, null, props);
    props.setProperty(Attribute.GFXD_PERSIST_DD, "false");
    // add expected exception
    final String expectedExStr = "persist-dd should be same on all the servers";
    props.put(TestUtil.EXPECTED_STARTUP_EXCEPTIONS,
        new Object[] { "Failed to start database", expectedExStr });
    try {
      startVMs(0, 1, 0, null, props);
      fail("Test should have failed ");
    } catch (RMIException e) {
      if (!e.getCause().getCause().getMessage().startsWith(expectedExStr)) {
        fail("Got unexpected exception :", e);
      }
    }
    props.remove(TestUtil.EXPECTED_STARTUP_EXCEPTIONS);
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    startVMs(0, 1, 0, null, props);
  }

  public static SerializableRunnable createDiskStoreOnServer()
      throws Exception {
    SerializableRunnable createDiskStoreOnServer = new SerializableRunnable(
        "createDiskStoreOnServer") {
      @Override
      public void run() throws CacheException {
        File file1 = new File(fileSeparator + "a" + fileSeparator + "b"
            + fileSeparator + "c" + fileSeparator);
        File file2 = new File(fileSeparator + "a" + fileSeparator + "b"
            + fileSeparator + "d" + fileSeparator);
        file1.mkdirs();
        file2.mkdirs();
      }
    };
    return createDiskStoreOnServer;
  }
  public void ym_testPersistentAttributesForAccessor() throws Exception {
    Properties props = new Properties();
    props.put("host-data", "true");
    startVMs(0, 1, 0, null, props);
    props.put("host-data", "false");
    startVMs(1, 0, 0, null, props);
    Connection conn = TestUtil.getConnection(props);
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    serverExecute(1, createDiskStoreOnServer());
    File file1 = new File(fileSeparator + "a" + fileSeparator + "b"
        + fileSeparator + "c" + fileSeparator);
    File file2 = new File(fileSeparator + "a" + fileSeparator + "b"
        + fileSeparator + "d" + fileSeparator);
    file1.mkdirs();
    file2.mkdirs();
    File[] expectedDirs = new File[] { file1,file2 };
    s.execute("create diskstore teststore ('" + file1.getPath() +"','"+file2.getPath()+"')" );

    String persistentSuffix = "PERSISTENT 'teststore' "; 
    // Table is PR and range partition
    s .execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))   "
            + "partition by range (cid) (VALUES BETWEEN 0.0 AND 99.0)  "
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil.deleteDir(file);
      }
      
    }
    //Table is PR with default partition
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))   "            
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil.deleteDir(file);
      }
    }
  //Table is PR with partition by PK
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid)) partition by primary key   "            
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn,  null , DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil.deleteDir(file);
      }
    }
  //Table is PR with partition by column
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" +
            " partition by column (cust_name)  " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil.deleteDir(file);
      }
    }
    
  //Table is PR with partition by List
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY LIST ( tid ) ( VALUES (10, 20 )," 
            + " VALUES (50, 60), VALUES (12, 34, 45)) " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil. deleteDir(file);
      }
    }
    
  //Table is PR  Partition by column colocated with another table
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid int , cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY column ( cid )  " + persistentSuffix);
    s.execute("create table trade.orders (oid decimal(30, 20), amount int, "
        + " tid int, cid int, primary key (oid))" 
        + "PARTITION BY column ( cid )  colocate with ( trade.customers) " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
      rgn = Misc.getRegionForTable("TRADE.ORDERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.orders");
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil.deleteDir(file);
      }
    }
    
  //Table is PR  Partition by column colocated with another table
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid int , cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY column ( cid )  " + persistentSuffix);
    s.execute("create table trade.orders (oid decimal(30, 20), amount int, "
        + " tid int, cid int, primary key (oid))" 
        + "PARTITION BY column ( cid )  colocate with ( trade.customers) ");
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
      rgn = Misc.getRegionForTable("TRADE.ORDERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.orders");
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil.deleteDir(file);
      }
    }
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "replicate " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, null, DataPolicy.EMPTY);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        TestUtil.deleteDir(file);
      }
    }
  }
  
  private void verify(Region rgn, File [] expectedDirs,
      DataPolicy expectedDP) throws IOException {
    RegionAttributes ra = rgn.getAttributes();
    assertEquals(expectedDP,ra.getDataPolicy());
    if(expectedDirs == null) {
      assertNull(ra.getDiskStoreName());
      return ;
    }else {
    File actualDirs[] = Misc.getGemFireCache().findDiskStore("TESTSTORE").getDiskDirs();
    assertEquals(actualDirs.length, expectedDirs.length);
    Set<String> expected = new HashSet(expectedDirs.length);
    for(File file :expectedDirs) {
      expected.add(file.getCanonicalPath());
    }   
    for(File file: actualDirs) {
      assertTrue(expected.remove(file.getCanonicalPath()));
    }    
    assertTrue(expected.isEmpty());
    }
  }
  
  private void checkVMsDown(VM... vms) {
    SerializableRunnable noGFE = new SerializableRunnable("GFE down") {
      @Override
      public void run() throws CacheException {
        try {
          CacheFactory.getAnyInstance();
          fail("expected the cache to be closed");
        } catch (CacheClosedException ex) {
          // expected
        }
        DistributedSystem sys = InternalDistributedSystem
            .getConnectedInstance();
        assertNull("expected the distributed system to be down", sys);
      }
    };
    for (VM vm : vms) {
      if (vm == null) {
        noGFE.run();
      }
      else {
        vm.invoke(noGFE);
      }
    }
  }

  private void checkDiskStores(Statement stmt, final int numServers,
      GemFireXDUtils.Pair<String, String>[] extraStores) throws SQLException {
    ResultSet rs = stmt.executeQuery("select * from sys.diskstoreIds");
    // expect 2 from each server for datadictionary and default diskstores
    // apart from the extraStores
    HashMap<String, HashMap<String, String>> diskStores = new HashMap<>();
    HashSet<GemFireXDUtils.Pair<String, String>> memberDiskStoreIds =
        new HashSet<>();
    while (rs.next()) {
      String member = rs.getString(1);
      String name = rs.getString(2);
      String id = rs.getString(3);
      String dirs = rs.getString(4);

      HashMap<String, String> memberMap = diskStores.get(name);
      if (memberMap == null) {
        memberMap = new HashMap<>(4);
        diskStores.put(name, memberMap);
      }
      assertNull(memberMap.put(member, dirs));
      if (name.equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        assertTrue(dirs.endsWith("datadictionary"));
      } else if (name.equals(GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE)) {
        assertTrue(dirs.endsWith(GfxdConstants.SNAPPY_DELTA_SUBDIR));
      }
      assertTrue(memberDiskStoreIds.add(new GemFireXDUtils.Pair<>(member, id)));
    }
    assertEquals(3 + extraStores.length, diskStores.size());
    assertTrue(diskStores.containsKey(GfxdConstants.GFXD_DD_DISKSTORE_NAME));
    assertTrue(
        diskStores.containsKey(GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME));
    assertTrue(diskStores.containsKey(GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE));
    for (GemFireXDUtils.Pair<String, String> p : extraStores) {
      HashMap<String, String> memberMap = diskStores.get(p.getKey());
      assertNotNull(memberMap);
      String dir = p.getValue();
      if (dir != null && dir.length() > 0) {
        for (String d : memberMap.values()) {
          assertTrue(d.endsWith(dir));
        }
      }
    }
    // also check that all servers are present for every diskstore
    for (Map.Entry<String, HashMap<String, String>> e : diskStores.entrySet()) {
      String name = e.getKey();
      assertEquals(name.equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)
          ? numServers + 1 : (name.equals(GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME)
              ? numServers + 2 : numServers), e.getValue().size());
    }
  }

  private SerializableRunnable verifyDiskStoreExistence(final String name) {
    return new SerializableRunnable() {
      @Override
      public void run() {
        assertNotNull(Misc.getGemFireCache().findDiskStore(name));
      }
    };
  }

  private SerializableRunnable verifyNoDiskStoreExistence(final String name) {
    return new SerializableRunnable() {
      @Override
      public void run() {
        assertNull(Misc.getGemFireCache().findDiskStore(name));
      }
    };
  }
}
