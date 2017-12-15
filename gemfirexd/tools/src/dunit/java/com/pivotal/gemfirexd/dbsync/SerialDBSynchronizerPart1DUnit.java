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
package com.pivotal.gemfirexd.dbsync;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisee;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.tools.GfxdSystemAdmin;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derby.shared.common.error.ShutdownException;

@SuppressWarnings("serial")
public class SerialDBSynchronizerPart1DUnit extends DBSynchronizerTestBase {

   public SerialDBSynchronizerPart1DUnit(String name) {
    super(name);
  }
 
  public void testDBSynchronizerPRWithSynchronizerOnNonRedundantDataStoreAndDMLOnAccessor()
      throws Exception {
    Statement derbyStmt = null;
    Connection derbyConn = null;
    NetworkServerControl server = null;
    try {
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(3, -1, "SG1");
      // create table
      clientSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key, "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int) server groups(SG1) redundancy 1");
             // + "AsyncEventListener (WBCL1)");

      clientSQLExecute(1, "alter table TESTTABLE set AsyncEventListener (WBCL1)");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl + ",app,app", true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000, "", false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      pause(5000);
      // Do an insert in sql fabric
      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      clientSQLExecute(1, "Insert into TESTTABLE values(2,'desc2','Add2',2)");
      clientSQLExecute(1, "Insert into TESTTABLE values(3,'desc3','Add3',3)");
      clientSQLExecute(1, "Insert into TESTTABLE values(4,'desc4','Add4',4)");
      clientSQLExecute(1, "Insert into TESTTABLE values(5,'desc5','Add5',5)");
      clientSQLExecute(1, "Insert into TESTTABLE values(6,'desc6','Add6',6)");
      // Bulk Update
      clientSQLExecute(1, "update TESTTABLE set ID1 = ID1 +1 ");

      // Bulk Delete
      clientSQLExecute(1, "delete from testtable where ADDRESS = 'Add5'");
      clientSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);
      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      Thread.sleep(5000);
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    }
    finally {
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testDBSynchronizerRemovalBehaviour() throws Exception {
    Statement derbyStmt = null;
    Connection derbyConn = null;
    NetworkServerControl server = null;
    try {
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      server = startNetworkServer();
      addExpectedDerbyException(".*derby\\.daemons\\] \\(DATABASE = newDB\\), \\(DRDAID.*");
      createDerbyValidationArtefacts();
      derbyConn = DriverManager.getConnection(derbyDbUrl);

      derbyDbUrl = "jdbc:derby://localhost:" + this.netPort + "/newDB;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group

      startClientVMs(1, 0, null);
      startServerVMs(3, -1, "SG1");
      // create table
      clientSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key, "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int) "
              + "AsyncEventListener (WBCL1)");

      Runnable createConfig = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000, "", false);
      createConfig.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      // stop network server so that events cannot be dispatched
      server.shutdown();
      addExpectedException(null, new int[] { 1, 2, 3 },
          java.sql.SQLNonTransientConnectionException.class);

      addExpectedException(null, new int[] { 1, 2, 3 },
          com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException.class);
      // Do an insert in sql fabric
      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      clientSQLExecute(1, "Insert into TESTTABLE values(2,'desc2','Add2',2)");
      clientSQLExecute(1, "Insert into TESTTABLE values(3,'desc3','Add3',3)");
      clientSQLExecute(1, "Insert into TESTTABLE values(4,'desc4','Add4',4)");
      clientSQLExecute(1, "Insert into TESTTABLE values(5,'desc5','Add5',5)");
      clientSQLExecute(1, "Insert into TESTTABLE values(6,'desc6','Add6',6)");
      // Bulk Update
      stopAsyncEventListener("WBCL1").run();
      Thread.sleep(5000);
      clientSQLExecute(1, "alter table testtable set AsyncEventListener ()");
      // Now drop WBCL.
      Runnable remove = dropAsyncEventListener("WBCL1");
      remove.run();
      clientSQLExecute(1, "alter table testtable set AsyncEventListener (WBCL1)");
      // Recreate the configuration
      server = startNetworkServer();
      createConfig.run();

      // check that queue is empty.
      Callable<?> checkQueueEmpty = getExecutorToCheckQueueEmpty("WBCL1");
      assertTrue(((Boolean)serverExecute(1, checkQueueEmpty)).booleanValue());
      assertTrue(((Boolean)serverExecute(2, checkQueueEmpty)).booleanValue());
      assertTrue(((Boolean)serverExecute(3, checkQueueEmpty)).booleanValue());

    }
    finally {

      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        try {
          server.shutdown();
        }
        catch (Exception ex) {
          getLogWriter().error("unexpected exception", ex);
        }
      }
    }
  }

  public void testDBSynchronizerPRWithSynchronizerOnRedundantDataStoreAndDMLOnAccessor()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(1, -1, "SG0");
      // create table
      clientSQLExecute(
          1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) AsyncEventListener (WBCL1) redundancy 1");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000, "", false);
      try {
        runnable.run();
        fail("expected exception when starting WBCL1 without available store");
      } catch (RuntimeException re) {
        if (!(re.getCause() instanceof SQLException)) {
          throw re;
        }
        SQLException sqle = (SQLException)re.getCause();
        if (!"X0Z08".equals(sqle.getSQLState())) {
          throw re;
        }
      }

      // Do an insert in sql fabric. This will create a primary bucket on the
      // lone server VM
      // with bucket ID =1
      clientSQLExecute(1,
          "Insert into TESTTABLE values(114,'desc114','Add114',114)");
      // Now start another server vm with server group SG1
      startServerVMs(1, -1, "SG1");

      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      // PR may send operation to a bucket that is initializing twice via both
      // bucket operation as well as adjunct messaging (see
      // BucketAdvisor#adviseRequiresTwoMessages) that will cause PK violation
      // in derby
      addExpectedException(null, new int[] { 2 },
          "java.sql.SQLIntegrityConstraintViolationException");
      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      clientSQLExecute(1,
          "Insert into TESTTABLE values(227,'desc227','Add227',227)");
      clientSQLExecute(1,
          "Insert into TESTTABLE values(340,'desc340','Add340',340)");
      // Bulk Update
      clientSQLExecute(1, "update TESTTABLE set ID1 = ID1 +1 ");
      // PK delete
      clientSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);
      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      removeExpectedException(null, new int[] { 2 },
          "java.sql.SQLIntegrityConstraintViolationException");
      validateResults(derbyStmt, "select * from testtable where ID <> 114",
          this.netPort, true);
    }
    finally {
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testDBSynchronizerPRWithSynchronizerOnNonRedundantNonPrimaryDataStoreAndDMLOnAccessor()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(1, -1, "SG0");
      // create table
      clientSQLExecute(
          1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) AsyncEventListener (WBCL1) ");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000, "", false);
      try {
        runnable.run();
        fail("expected exception when starting WBCL1 without available store");
      } catch (RuntimeException re) {
        if (!(re.getCause() instanceof SQLException)) {
          throw re;
        }
        SQLException sqle = (SQLException)re.getCause();
        if (!"X0Z08".equals(sqle.getSQLState())) {
          throw re;
        }
      }

      // Do an insert in sql fabric. This will create a primary bucket on the
      // lone server VM
      // with bucket ID =1
      clientSQLExecute(1,
          "Insert into TESTTABLE values(114,'desc114','Add114',114)");
      // Now start another server vm with server group SG1
      startServerVMs(1, -1, "SG1");

      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);

      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      clientSQLExecute(1,
          "Insert into TESTTABLE values(227,'desc227','Add227',227)");
      clientSQLExecute(1,
          "Insert into TESTTABLE values(340,'desc340','Add340',340)");
      // Bulk Update
      clientSQLExecute(1, "update TESTTABLE set ID1 = ID1 +1 ");
      // PK delete
      clientSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);
      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      validateResults(derbyStmt, "select * from testtable where ID <> 114",
          this.netPort, true);
    }
    finally {
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testDBSynchronizerPRWithSynchronizerOnNonRedundantNonPrimaryDataStoreAndDMLOnPrimaryDataStore()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      // startClientVMs(1);
      startServerVMs(1, -1, "SG0");
      // create table
      serverSQLExecute(
          1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) AsyncEventListener (WBCL1) ");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000, "", false);
      try {
        serverExecute(1, runnable);
        fail("expected exception when starting WBCL1 without available store");
      } catch (RMIException rmie) {
        if (!(rmie.getCause() instanceof RuntimeException)) {
          throw rmie;
        }
        if (!(rmie.getCause().getCause() instanceof SQLException)) {
          throw rmie;
        }
        SQLException sqle = (SQLException)rmie.getCause().getCause();
        if (!"X0Z08".equals(sqle.getSQLState())) {
          throw rmie;
        }
      }

      // Do an insert in sql fabric. This will create a primary bucket on the
      // lone server VM
      // with bucket ID =1
      serverSQLExecute(1,
          "Insert into TESTTABLE values(114,'desc114','Add114',114)");
      // Now start another server vm with server group SG1
      startServerVMs(1, -1, "SG1");

      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      serverExecute(1, startWBCL);

      serverSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      serverSQLExecute(1,
          "Insert into TESTTABLE values(227,'desc227','Add227',227)");
      serverSQLExecute(1,
          "Insert into TESTTABLE values(340,'desc340','Add340',340)");
      // Bulk Update
      serverSQLExecute(1, "update TESTTABLE set ID1 = ID1 +1 ");
      // PK delete
      serverSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);
      blockForValidation();
      serverExecute(1, stopAsyncEventListener("WBCL1"));
      // now create a client VM on controller node to allow us test validation.
      startClientVMs(1, 0, null);
      validateResults(derbyStmt, "select * from testtable where ID <> 114",
          this.netPort, true);
    }
    finally {
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testBug41585_BulkOpsOnPRWithResolver() throws Exception {
    this.common_1(" partition by list (ID) ( VALUES (0, 5) , VALUES (10, 15),"
        + "VALUES (20, 23), VALUES(25, 30), VALUES(31, 100))", false);
  }

  public void testDBSynchronizerOnDataStoreAndDMLOnAccessorPRWithPrms()
      throws Exception {
    this.common_1("", false);
  }

  public void testBug41593() throws Exception {
    this.common_1("", false);
  }

  private void common_1(String extnClause, boolean useGfxdNetworkClient)
      throws Exception {

    // Start the client server VMs which will load the embedded sql driver ,
    // initialize the connection,
    // set the user name /password which will be subsequently used by derby url
    // & gfxd net driver
    startClientVMs(1, 0, null);
    startServerVMs(1, -1, "SG1");

    Statement derbyStmt = null;
    NetworkServerControl server = null;

    server = startNetworkServer();
    createDerbyValidationArtefacts();
    String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    // create schema
    derbyStmt = derbyConn.createStatement();
    String tableDef = "create table TESTTABLE1 (ID int not null primary key,"
        + " test_bigint BIGINT, test_boolean smallint, test_bit smallint,"
        + " test_date DATE, test_numeric NUMERIC, test_decimal DECIMAL,"
        + " test_double DOUBLE, test_float FLOAT, test_real REAL,"
        + " test_smallint SMALLINT, test_tinyint smallint, test_varchar "
        + "varchar(1024), test_char char(40), test_longvarchar long varchar, "
        + "test_time TIME, test_timestamp Timestamp )";
    derbyStmt.execute(tableDef);
    boolean tablesCreated = false;
    try {
      // group
      if (useGfxdNetworkClient) {
        java.sql.Connection conn = TestUtil
            .startNetserverAndGetLocalNetConnection();
        // Assign it to be NetworkConnection for controller VM
        TestUtil.jdbcConn = conn;
      }
      // create table
      clientSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024), ADDRESS varchar(1024), ID1 int) "
              + "AsyncEventListener (WBCL2) ");
      clientSQLExecute(1, tableDef + " AsyncEventListener(WBCL2) " + extnClause);
      tablesCreated = true;
      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
      clientExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("wbcl2");
      clientExecute(1, startWBCL);

      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      Connection conn = TestUtil.jdbcConn;
      PreparedStatement insertData = conn
          .prepareStatement("insert into TESTTABLE1 values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
      for (int i = 1; i < 51; ++i) {
        insertData.setInt(1, i);
        insertData.setLong(2, Long.MIN_VALUE + i);
        insertData.setInt(3, 1);
        insertData.setInt(4, 0);
        insertData.setDate(5, new Date(System.currentTimeMillis() + i));
        insertData.setBigDecimal(6, new BigDecimal(Double.MIN_VALUE + i));
        insertData.setBigDecimal(7, new BigDecimal(Double.MIN_VALUE + i));
        insertData.setDouble(8, Double.MIN_VALUE + i);
        insertData.setFloat(9, Float.MIN_VALUE + i);
        insertData.setFloat(10, Float.MIN_VALUE + i);
        insertData.setInt(11, 127);
        insertData.setInt(12, 128);
        insertData.setString(13, "test" + i);
        insertData.setString(14, "test" + i);
        insertData.setString(15, "test" + i);
        insertData.setTime(16, new Time(System.currentTimeMillis() + i));
        insertData.setTimestamp(17, new Timestamp(System.currentTimeMillis()
            + i));
        assertEquals(insertData.executeUpdate(), 1);

      }
      PreparedStatement updateData = conn
          .prepareStatement("Update TESTTABLE1 set test_bigint = ?, test_boolean = ?, test_bit  = ?,"
              + " test_date = ?, test_numeric = ?, test_decimal = ? , test_double = ?, test_float = ?, test_real = ?, "
              + " test_smallint =  ?, test_tinyint = ?, test_varchar = ? , "
              + " test_char = ?, test_longvarchar = ?, test_time = ?, test_timestamp = ? where test_varchar =?");

      updateData.setLong(1, Long.MAX_VALUE);
      updateData.setInt(2, Byte.MAX_VALUE);
      updateData.setInt(3, Byte.MAX_VALUE);
      updateData.setDate(4, new Date(Integer.MAX_VALUE));
      updateData.setBigDecimal(5, new BigDecimal(Short.MAX_VALUE));
      updateData.setBigDecimal(6, new BigDecimal(Short.MAX_VALUE));
      updateData.setDouble(7, DB2_LARGEST_DOUBLE);
      updateData.setFloat(8, DB2_LARGEST_REAL);
      updateData.setFloat(9, DB2_LARGEST_REAL);
      updateData.setInt(10, Byte.MAX_VALUE);
      updateData.setInt(11, Byte.MAX_VALUE);
      updateData.setString(12, "test01");
      updateData.setString(13, "test01");
      updateData.setString(14, "test01");
      updateData.setTime(15, new Time(Integer.MAX_VALUE));
      updateData.setTimestamp(16, new Timestamp(Integer.MAX_VALUE));
      updateData.setString(17, "test10");
      assertEquals(1, updateData.executeUpdate());
      // Bulk delete
      PreparedStatement deleteData = conn
          .prepareStatement("delete from testtable1 where  ID > ?");
      deleteData.setInt(1, 46);
      assertEquals(4, deleteData.executeUpdate());
      // PK delete
      clientSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);
      blockForValidation();
      validateResults(derbyStmt, "select * from testtable1", this.netPort, true);
      Connection conn1 = TestUtil.jdbcConn;
      Statement stmt1 = conn1.createStatement();
      ResultSet rs = stmt1
          .executeQuery("select * from testtable1 where ID = 10");
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 10);
      assertEquals(rs.getLong(2), Long.MAX_VALUE);
      assertEquals(rs.getDate(5).toString(),
          new Date(Integer.MAX_VALUE).toString());
      assertEquals(rs.getDouble(8), DB2_LARGEST_DOUBLE);
      assertEquals(rs.getString(13), "test01");
      assertFalse(rs.next());
    }
    finally {
      ok = false;
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE", "TESTTABLE1" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      addExpectedDerbyException("java.sql.SQLNonTransientException");
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE1");
        clientSQLExecute(1, "drop table TESTTABLE");
      }
      if (useGfxdNetworkClient) {
        TestUtil.stopNetServer();
      }
      if (server != null) {
        server.shutdown();
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }
  }

  public void testDBSynchronizerBehaviourWithNetworkClient() throws Exception {
    this.common_1("", true);
  }

  /* yogesh : this test needs to be investigated why it is failing after the merge*/
  public void testDBSynchronizerWithPrmsRedundancyBehaviour() throws Exception {
    
    // Disabling until #51603 is fixed. Don't return from this point when the bug is fixed.
    if (isTransactional) {
      return;
    }
    
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      String tableDef = " create table TESTTABLE1 ( ID int not null primary key , test_bigint BIGINT, "
          + " test_boolean smallint, test_bit smallint, test_date DATE,  test_numeric NUMERIC, "
          + " test_decimal DECIMAL, test_double DOUBLE, test_float FLOAT, test_real REAL, "
          + " test_smallint SMALLINT, test_tinyint smallint, test_varchar varchar(1024) , "
          + " test_char char(40), test_longvarchar long varchar, test_time TIME, test_timestamp Timestamp )";
      derbyStmt.execute(tableDef);
      // group
      startClientVMs(1, 0, null);
      startServerVMs(1, -1, "SG1");
      // create table
      clientSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2) ");
      clientSQLExecute(1, tableDef + " AsyncEventListener(WBCL2) ");
      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
      clientExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      clientExecute(1, startWBCL);
      // Ensure that DBSynchronizer on Server1 is active & running
      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      // PK delete
      clientSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);
      blockForValidation();

      // set observer in server1 , to hold the processer thread.
      serverVMs.get(0).invoke(new SerializableRunnable() {
        public void run() {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void afterBulkOpDBSynchExecution(Event.Type type,
                    int numRowsMod, Statement ps, String dml) {

                    try {
                      while (true) {
                        AsyncEventQueue asyncQueue = Misc.getGemFireCache()
                            .getAsyncEventQueue("WBCL2");
                        if(asyncQueue != null && !asyncQueue.isRunning()) {
                          break;
                        }
                        Thread.sleep(100);
                      }
                    }
                    catch (InterruptedException e) {
                      AsyncEventQueue asyncQueue = Misc.getGemFireCache()
                          .getAsyncEventQueue("WBCL2");
                      if (!asyncQueue.isRunning()) {
                        throw GemFireXDRuntimeException
                            .newRuntimeException(
                                "IGNORE_EXCEPTION_test :Asynch DB Synchronizer thread encountered exception",
                                e);
                      }
                      else {
                        throw GemFireXDRuntimeException
                            .newRuntimeException(
                                "Asynch DB Synchronizer thread encountered exception",
                                e);
                      }
                    }
                }
              });
        }
      });

      Connection conn = TestUtil.jdbcConn;
      PreparedStatement insertData = conn
          .prepareStatement("insert into TESTTABLE1 values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
      for (int i = 1; i < 51; ++i) {
        insertData.setInt(1, i);
        insertData.setLong(2, Long.MIN_VALUE + i);
        insertData.setInt(3, 1);
        insertData.setInt(4, 0);
        insertData.setDate(5, new Date(System.currentTimeMillis() + i));
        insertData.setBigDecimal(6, new BigDecimal(Double.MIN_VALUE + i));
        insertData.setBigDecimal(7, new BigDecimal(Double.MIN_VALUE + i));
        insertData.setDouble(8, Double.MIN_VALUE + i);
        insertData.setFloat(9, Float.MIN_VALUE + i);
        insertData.setFloat(10, Float.MIN_VALUE + i);
        insertData.setInt(11, 127);
        insertData.setInt(12, 128);
        insertData.setString(13, "test" + i);
        insertData.setString(14, "test" + i);
        insertData.setString(15, "test" + i);
        insertData.setTime(16, new Time(System.currentTimeMillis() + i));
        insertData.setTimestamp(17, new Timestamp(System.currentTimeMillis()
            + i));
        assertEquals(insertData.executeUpdate(), 1);

      }
      PreparedStatement updateData1 = conn
          .prepareStatement("Update TESTTABLE1 set  test_boolean = ? where test_varchar = ?");
      updateData1.setInt(1, 11);
      updateData1.setString(2, "test8");
      assertEquals(1, updateData1.executeUpdate());
      //Thread.sleep(5000);
      PreparedStatement updateData = conn
          .prepareStatement("Update TESTTABLE1 set test_bigint = ?, test_boolean = ?, test_bit  = ?,"
              + " test_date = ?, test_numeric = ?, test_decimal = ? , test_double = ?, test_float = ?, test_real = ?, "
              + " test_smallint =  ?, test_tinyint = ?, test_varchar = ? , "
              + " test_char = ?, test_longvarchar = ?, test_time = ?, test_timestamp = ? where test_varchar =?");
      updateData.setLong(1, Long.MAX_VALUE);
      updateData.setInt(2, Byte.MAX_VALUE);
      updateData.setInt(3, Byte.MAX_VALUE);
      updateData.setDate(4, new Date(Integer.MAX_VALUE));
      updateData.setBigDecimal(5, new BigDecimal(Short.MAX_VALUE));
      updateData.setBigDecimal(6, new BigDecimal(Short.MAX_VALUE));
      updateData.setDouble(7, DB2_LARGEST_DOUBLE);
      updateData.setFloat(8, DB2_LARGEST_REAL);
      updateData.setFloat(9, DB2_LARGEST_REAL);
      updateData.setInt(10, Byte.MAX_VALUE);
      updateData.setInt(11, Byte.MAX_VALUE);
      updateData.setString(12, "test01");
      updateData.setString(13, "test01");
      updateData.setString(14, "test01");
      updateData.setTime(15, new Time(Integer.MAX_VALUE));
      updateData.setTimestamp(16, new Timestamp(Integer.MAX_VALUE));
      updateData.setString(17, "test10");
      assertEquals(updateData.executeUpdate(), 1);
      // Bulk delete
      PreparedStatement deleteData = conn
          .prepareStatement("delete from testtable1 where  ID > ?");
      deleteData.setInt(1, 46);
      assertEquals(4, deleteData.executeUpdate());
      //Thread.sleep(5000);
      startServerVMs(1, -1, "SG1");

      // Verify that the update has not reached derby

      ResultSet rs = derbyStmt
          .executeQuery("select * from testtable1 where ID = 10");
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 10);
      assertTrue(rs.getLong(2) != Long.MAX_VALUE);
      assertTrue(!rs.getString(13).equals("test01"));
      assertFalse(rs.next());
      // Now start another server VM which is part of the server group of asynch
      // db synchronizer
      startServerVMs(1, -1, "SG1");

      // now stop the hub forcefully on the first server
      serverExecute(1, new SerializableRunnable() {
        public void run() {
          Misc.getGemFireCache().getAsyncEventQueue("WBCL2").stop();
        }
      });
      createDerbyValidationArtefacts();
      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      // PK delete
      clientSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);
      blockForValidation();
      validateResults(derbyStmt, "select * from testtable1", this.netPort, true);
      Connection conn1 = TestUtil.jdbcConn;
      Statement stmt1 = conn1.createStatement();
      rs = stmt1.executeQuery("select * from testtable1 where ID = 10");
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 10);
      assertEquals(rs.getLong(2), Long.MAX_VALUE);
      assertEquals(rs.getDate(5).toString(),
          new Date(Integer.MAX_VALUE).toString());
      assertEquals(rs.getDouble(8), DB2_LARGEST_DOUBLE);
      assertEquals(rs.getString(13), "test01");
      assertFalse(rs.next());

    }
    finally {
      ok = false;
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE", "TESTTABLE1" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE1");
        clientSQLExecute(1, "drop table TESTTABLE");
      }
      if (server != null) {
        server.shutdown();
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }
  }

  public void testDBSynchronizerOnReplicatedRegionWithDMLOnDataStoreAndHubOnDataStore()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      String tableDef = " create table TESTTABLE1 ( ID int not null primary key , test_bigint BIGINT, "
          + " test_boolean smallint, test_bit smallint, test_date DATE,  test_numeric NUMERIC, "
          + " test_decimal DECIMAL, test_double DOUBLE, test_float FLOAT, test_real REAL, "
          + " test_smallint SMALLINT, test_tinyint smallint, test_varchar varchar(1024) , "
          + " test_char char(40), test_longvarchar long varchar, test_time TIME, test_timestamp Timestamp )";
      derbyStmt.execute(tableDef);

      startClientVMs(1, 0, null);
      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2) ");
      serverSQLExecute(1, tableDef + " REPLICATE  AsyncEventListener(WBCL2) ");
      tablesCreated = true;
      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      SerializableRunnable sr = new SerializableRunnable() {
        public void run() {
          try {
            Connection conn = TestUtil.jdbcConn;
            Statement stmt = conn.createStatement();
            assertEquals(
                1,
                stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)"));
            PreparedStatement insertData = conn
                .prepareStatement("insert into TESTTABLE1 values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            for (int i = 1; i < 51; ++i) {
              insertData.setInt(1, i);
              insertData.setLong(2, Long.MIN_VALUE + i);
              insertData.setInt(3, 1);
              insertData.setInt(4, 0);
              insertData.setDate(5, new Date(System.currentTimeMillis() + i));
              insertData.setBigDecimal(6, new BigDecimal(Double.MIN_VALUE + i));
              insertData.setBigDecimal(7, new BigDecimal(Double.MIN_VALUE + i));
              insertData.setDouble(8, Double.MIN_VALUE + i);
              insertData.setFloat(9, Float.MIN_VALUE + i);
              insertData.setFloat(10, Float.MIN_VALUE + i);
              insertData.setInt(11, 127);
              insertData.setInt(12, 128);
              insertData.setString(13, "test" + i);
              insertData.setString(14, "test" + i);
              insertData.setString(15, "test" + i);
              insertData.setTime(16, new Time(System.currentTimeMillis() + i));
              insertData.setTimestamp(17,
                  new Timestamp(System.currentTimeMillis() + i));
              assertEquals(insertData.executeUpdate(), 1);

            }
            PreparedStatement updateData = conn
                .prepareStatement("Update TESTTABLE1 set test_bigint = ?, test_boolean = ?, test_bit  = ?,"
                    + " test_date = ?, test_numeric = ?, test_decimal = ? , test_double = ?, test_float = ?, test_real = ?, "
                    + " test_smallint =  ?, test_tinyint = ?, test_varchar = ? , "
                    + " test_char = ?, test_longvarchar = ?, test_time = ?, test_timestamp = ? where test_varchar =?");

            updateData.setLong(1, Long.MAX_VALUE);
            updateData.setInt(2, Byte.MAX_VALUE);
            updateData.setInt(3, Byte.MAX_VALUE);
            updateData.setDate(4, new Date(Integer.MAX_VALUE));
            updateData.setBigDecimal(5, new BigDecimal(Short.MAX_VALUE));
            updateData.setBigDecimal(6, new BigDecimal(Short.MAX_VALUE));
            updateData.setDouble(7, DB2_LARGEST_DOUBLE);
            updateData.setFloat(8, DB2_LARGEST_REAL);
            updateData.setFloat(9, DB2_LARGEST_REAL);
            updateData.setInt(10, Byte.MAX_VALUE);
            updateData.setInt(11, Byte.MAX_VALUE);
            updateData.setString(12, "test01");
            updateData.setString(13, "test01");
            updateData.setString(14, "test01");
            updateData.setTime(15, new Time(Integer.MAX_VALUE));
            updateData.setTimestamp(16, new Timestamp(Integer.MAX_VALUE));
            updateData.setString(17, "test10");
            assertEquals(updateData.executeUpdate(), 1);
            // Bulk delete
            PreparedStatement deleteData = conn
                .prepareStatement("delete from testtable1 where  ID > ?");
            deleteData.setInt(1, 46);
            assertEquals(4, deleteData.executeUpdate());
            // PK delete
            assertEquals(
                1,
                stmt.executeUpdate("delete from TESTTABLE where ID = "
                    + DELETED_KEY));
          }
          catch (Exception e) {
            throw GemFireXDRuntimeException.newRuntimeException(
                "Exception in the test", e);
          }
        }
      };
      serverExecute(1, sr);
      blockForValidation();
      // Now create a dumy client to allow validation
      startClientVMs(1, -1, "SG");
      validateResults(derbyStmt, "select * from testtable1", this.netPort, true);
      Connection conn1 = TestUtil.jdbcConn;
      Statement stmt1 = conn1.createStatement();
      ResultSet rs = stmt1
          .executeQuery("select * from testtable1 where ID = 10");
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 10);
      assertEquals(rs.getLong(2), Long.MAX_VALUE);
      assertEquals(rs.getDate(5).toString(),
          new Date(Integer.MAX_VALUE).toString());
      assertEquals(rs.getDouble(8), DB2_LARGEST_DOUBLE);
      assertEquals(rs.getString(13), "test01");
      assertFalse(rs.next());

    }
    finally {
      ok = false;
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE", "TESTTABLE1" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE1");
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }

  }

  public void testDBSynchronizerOnReplicatedRegionWithDMLOnAccesoreAndHubOnDataStore()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    Statement stmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      String tableDef = "create table TESTTABLE1 (ID int not null primary key,"
          + " test_bigint BIGINT, test_boolean smallint, test_bit smallint,"
          + " test_date DATE, test_numeric NUMERIC, test_decimal DECIMAL,"
          + " test_double DOUBLE, test_float FLOAT, test_real REAL,"
          + " test_smallint SMALLINT, test_tinyint smallint,"
          + " test_varchar varchar(1024), test_char char(40),"
          + " test_longvarchar long varchar, test_time TIME,"
          + " test_timestamp Timestamp)";
      derbyStmt.execute(tableDef);

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2) ");
      serverSQLExecute(1, tableDef + " REPLICATE  AsyncEventListener(WBCL2) ");
      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      stmt = conn.createStatement();
      assertEquals(
          1,
          stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)"));
      PreparedStatement insertData = conn
          .prepareStatement("insert into TESTTABLE1 values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
      for (int i = 1; i < 51; ++i) {
        insertData.setInt(1, i);
        insertData.setLong(2, Long.MIN_VALUE + i);
        insertData.setInt(3, 1);
        insertData.setInt(4, 0);
        insertData.setDate(5, new Date(System.currentTimeMillis() + i));
        insertData.setBigDecimal(6, new BigDecimal(Double.MIN_VALUE + i));
        insertData.setBigDecimal(7, new BigDecimal(Double.MIN_VALUE + i));
        insertData.setDouble(8, Double.MIN_VALUE + i);
        insertData.setFloat(9, Float.MIN_VALUE + i);
        insertData.setFloat(10, Float.MIN_VALUE + i);
        insertData.setInt(11, 127);
        insertData.setInt(12, 128);
        insertData.setString(13, "test" + i);
        insertData.setString(14, "test" + i);
        insertData.setString(15, "test" + i);
        insertData.setTime(16, new Time(System.currentTimeMillis() + i));
        insertData.setTimestamp(17, new Timestamp(System.currentTimeMillis()
            + i));
        assertEquals(insertData.executeUpdate(), 1);

      }
      PreparedStatement updateData = conn
          .prepareStatement("Update TESTTABLE1 set test_bigint = ?, test_boolean = ?, test_bit  = ?,"
              + " test_date = ?, test_numeric = ?, test_decimal = ? , test_double = ?, test_float = ?, test_real = ?, "
              + " test_smallint =  ?, test_tinyint = ?, test_varchar = ? , "
              + " test_char = ?, test_longvarchar = ?, test_time = ?, test_timestamp = ? where test_varchar =?");

      updateData.setLong(1, Long.MAX_VALUE);
      updateData.setInt(2, Byte.MAX_VALUE);
      updateData.setInt(3, Byte.MAX_VALUE);
      updateData.setDate(4, new Date(Integer.MAX_VALUE));
      updateData.setBigDecimal(5, new BigDecimal(Short.MAX_VALUE));
      updateData.setBigDecimal(6, new BigDecimal(Short.MAX_VALUE));
      updateData.setDouble(7, DB2_LARGEST_DOUBLE);
      updateData.setFloat(8, DB2_LARGEST_REAL);
      updateData.setFloat(9, DB2_LARGEST_REAL);
      updateData.setInt(10, Byte.MAX_VALUE);
      updateData.setInt(11, Byte.MAX_VALUE);
      updateData.setString(12, "test01");
      updateData.setString(13, "test01");
      updateData.setString(14, "test01");
      updateData.setTime(15, new Time(Integer.MAX_VALUE));
      updateData.setTimestamp(16, new Timestamp(Integer.MAX_VALUE));
      updateData.setString(17, "test10");
      assertEquals(updateData.executeUpdate(), 1);
      // Bulk delete
      PreparedStatement deleteData = conn
          .prepareStatement("delete from testtable1 where  ID > ?");
      deleteData.setInt(1, 46);
      assertEquals(4, deleteData.executeUpdate());
      // PK delete
      assertEquals(1,
          stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY));

      blockForValidation();
      // Now create a dumy client to allow validation
      startClientVMs(1, -1, "SG");
      validateResults(derbyStmt, "select * from testtable1", this.netPort, true);
      Connection conn1 = TestUtil.jdbcConn;
      Statement stmt1 = conn1.createStatement();
      ResultSet rs = stmt1
          .executeQuery("select * from testtable1 where ID = 10");
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 10);
      assertEquals(rs.getLong(2), Long.MAX_VALUE);
      assertEquals(rs.getDate(5).toString(),
          new Date(Integer.MAX_VALUE).toString());
      assertEquals(rs.getDouble(8), DB2_LARGEST_DOUBLE);
      assertEquals(rs.getString(13), "test01");
      assertFalse(rs.next());

    }
    finally {
      ok = false;
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE", "TESTTABLE1" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE1");
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testBug42430_1() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    Statement stmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2) "
              + " partition by range (ID) (VALUES BETWEEN 0 AND 5,  "
              + "VALUES BETWEEN 5  AND 10 , VALUES BETWEEN 10  AND 20 )");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      stmt = conn.createStatement();
      String str = "insert into TESTTABLE values";
      for (int i = 0; i < 20; ++i) {
        str += "(" + i + ", 'First', 'J 604'," + i + "),";
      }
      str = str.substring(0, str.length() - 1);
      stmt.executeUpdate(str);
      assertEquals(1,
          stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY));
      blockForValidation();
      // Now create a dumy client to allow validation
      startClientVMs(1, -1, "SG");
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    }
    finally {
      ok = false;
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testBug42430_2() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    Statement stmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2)  replicate ");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      stmt = conn.createStatement();
      String str = "insert into TESTTABLE values";
      for (int i = 0; i < 20; ++i) {
        str += "(" + i + ", 'First', 'J 604'," + i + "),";
      }
      str = str.substring(0, str.length() - 1);
      stmt.executeUpdate(str);
      assertEquals(1,
          stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY));
      blockForValidation();
      // Now create a dumy client to allow validation
      startClientVMs(1, -1, "SG");
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    }
    finally {
      ok = false;
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testMemberSetForBulkOpDistributionForPRAndReplicatedTables()
      throws Exception {
    // create three server VMs. Define Gateway Hub on only one server VM.
    // The advise member set should return only one member & should not include
    // self.
    startServerVMs(2, -1, "SG0");
    startServerVMs(1, -1, "SG1");
    startClientVMs(1, 0, null);
    NetworkServerControl nsc = null;
    boolean tablesCreated = false;
    try {
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      nsc = startNetworkServer();
      String tableDef = " create table TESTTABLE1 ( ID int not null primary key , test_bigint BIGINT, "
          + " test_boolean smallint )";
      clientSQLExecute(1, tableDef + "  AsyncEventListener (WBCL2) ");
      tablesCreated = true;
      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      startWBCL.run();
      final String schemaName = getCurrentDefaultSchemaName();
      validate(schemaName + ".TESTTABLE1");
      // Create a replicated table
      tableDef = " create table TESTTABLE2 ( ID int not null primary key , test_bigint BIGINT, "
          + " test_boolean smallint )";
      clientSQLExecute(1, tableDef + "  replicate AsyncEventListener (WBCL2) ");
      validate(schemaName + ".TESTTABLE2");
    }
    finally {
      if (nsc != null) {
        nsc.shutdown();
      }
      if (tablesCreated) {
        clientSQLExecute(1, "drop table if exists TESTTABLE1");
        clientSQLExecute(1, "drop table if exists TESTTABLE2");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }
  }

  private void validate(final String tablename) throws Exception {
    // Get the reference of the partitioned region for table in controller VM.
    Region<?, ?> rgn = Misc.getRegionForTable(tablename, true);
    @SuppressWarnings("unchecked")
    final Set<InternalDistributedMember> synchronizerMmbrs = ((CacheDistributionAdvisee)rgn)
        .getCacheDistributionAdvisor().adviseSerialAsyncEventQueueOrGatewaySender();
    assertEquals(1, synchronizerMmbrs.size());
    InternalDistributedMember dm = synchronizerMmbrs.iterator().next();
    VM vm = serverVMs.get(2);
    Iterator<Map.Entry<DistributedMember, VM>> itr = members.entrySet()
        .iterator();
    boolean foundMember = false;
    while (itr.hasNext()) {
      Map.Entry<DistributedMember, VM> entry = itr.next();
      VM temp = entry.getValue();
      if (temp != null && temp.equals(vm)) {
        assertEquals(dm, entry.getKey());
        foundMember = true;
      }
    }
    assertTrue(foundMember);
    // Executionon server3 should exclude self
    SerializableRunnable sr = new SerializableRunnable() {
      public void run() {

        Region<?, ?> rgn = Misc.getRegionForTable(tablename, true);
        Set<?> synchronizerMmbrs = ((CacheDistributionAdvisee)rgn)
            .getCacheDistributionAdvisor()
            .adviseSerialAsyncEventQueueOrGatewaySender();
        // assertTrue(synchronizerMmbrs.isEmpty());
        assertEquals(0, synchronizerMmbrs.size());
      }
    };
    serverExecute(3, sr);
  }

  /*
   * yogesh:20dec11 this test starts a gateway sender which requires locator
   * started in remote ds so this test needs more than five vms and needs to be
   * re written in wan dunit configuration which has 8 vms
   */
  public void disabled_testGfxdMemberAdviseAPIForPartitionedTable_1() throws Exception {

    startNetworkServer();
    createDerbyValidationArtefacts();
    // Create a configuration with a system where each VM is part of a unique
    // server group.

    // Only gemfirexd gateway hub
    AsyncVM async1 = invokeStartServerVM(1, -1, "SG1", null);

    // only asynch event listener
    AsyncVM async2 = invokeStartServerVM(2, -1, "SG2", null);

    // only asynch db synchronizer
    AsyncVM async3 = invokeStartServerVM(3, -1, "SG3", null);

    // only asynch db synchronizer
    AsyncVM async4 = invokeStartServerVM(4, -1, "SG4", null);

    // All
    startClientVMs(1, -1, "SG0");

    joinVMs(true, async1, async2, async3, async4);

    Runnable gtwayHubCreator = getHubCreatorExecutor("SG1, SG0", "MyHub", null,
        128, null, null, null);
    gtwayHubCreator.run();

    String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Runnable asynchDBSynch = getExecutorForWBCLConfiguration("SG3, SG4, SG0",
        "MyAsyncDBSynchro", "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        "org.apache.derby.jdbc.ClientDriver", derbyDbUrl + ",app,app", true,
        Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);
    asynchDBSynch.run();
    // create table
    try {
      serverSQLExecute(
          1,
          "create table TESTTABLE1 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) gatewaysender (MyHub) ");
      // create table
      serverSQLExecute(1,
          "create table TESTTABLE2 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (MYASYNCEVENTLISTENER) ");
      // create table
      serverSQLExecute(
          1,
          "create table TESTTABLE3 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) AsyncEventListener"
              + " (MyAsyncDBSynchro) ");

      final String schemaName = getCurrentDefaultSchemaName();
      PartitionedRegion pr1 = (PartitionedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE1", null), true, false);
      Set<?> membs = pr1.getRegionAdvisor()
          .adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(1, membs.size());

      membs = pr1.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(0, membs.size());

      PartitionedRegion pr2 = (PartitionedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE2", null), true, false);

      membs = pr2.getRegionAdvisor().adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(0, membs.size());

      membs = pr2.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(0, membs.size());

      Runnable asyncEventConfig = getExecutorForWBCLConfiguration(
          "SG2, SG0",
          "MYASYNCEVENTLISTENER",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$TestNewGatewayEventListener",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "init-param-str", false);
      asyncEventConfig.run();

      membs = pr2.getRegionAdvisor().adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(0, membs.size());

      membs = pr2.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(1, membs.size());
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 1);
      }

      PartitionedRegion pr3 = (PartitionedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE3", null), true, false);

      membs = pr3.getRegionAdvisor().adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 2);
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }

      membs = pr3.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(membs.size(), 2);
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }
    }
    finally {
      clientSQLExecute(1, "drop table TESTTABLE1");
      clientSQLExecute(1, "drop table TESTTABLE2");
      clientSQLExecute(1, "drop table TESTTABLE3");
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      Statement derbyStmt = derbyConn.createStatement();
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }

  }

  // Hubs created post table creation
  /*
   * yogesh:20dec11 this test starts a gateway sender which requires locator
   * started in remote ds so this test needs more than five vms and needs to be
   * re written in wan dunit configuration which has 8 vms
   */
  public void disable_testGfxdMemberAdviseAPIForPartitionedTable_2() throws Exception {

    startNetworkServer();
    createDerbyValidationArtefacts();
    // Create a configuration with a system where each VM is part of a unique
    // server group.

    // Only gemfirexd gateway hub
    AsyncVM async1 = invokeStartServerVM(1, -1, "SG1", null);

    // only asynch event listener
    AsyncVM async2 = invokeStartServerVM(2, -1, "SG2", null);

    // only asynch db synchronizer
    AsyncVM async3 = invokeStartServerVM(3, -1, "SG3", null);

    // only asynch db synchronizer
    AsyncVM async4 = invokeStartServerVM(4, -1, "SG4", null);

    // All
    startClientVMs(1, -1, "SG0");

    joinVMs(true, async1, async2, async3, async4);

    // final String gatewayID = "MY_GATEWAY_";
    Runnable gtwayHubCreator = getHubCreatorExecutor("SG1, SG0", "MYSENDER",
        null, 128, null, null, null);

    String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Runnable asynchDBSynch = getExecutorForWBCLConfiguration("SG3, SG4, SG0",
        "MYASYNCDBSYNCHRO", "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
        Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);

    // create table
    try {
      serverSQLExecute(
          1,
          "create table TESTTABLE1 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) gatewaysender (MYSENDER) ");
      // create table
      serverSQLExecute(1,
          "create table TESTTABLE2 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (MYASYNCEVENTLISTENER) ");
      // create table
      serverSQLExecute(
          1,
          "create table TESTTABLE3 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) AsyncEventListener"
              + " (MYASYNCDBSYNCHRO) ");

      asynchDBSynch.run();
      gtwayHubCreator.run();
      final String schemaName = getCurrentDefaultSchemaName();
      PartitionedRegion pr1 = (PartitionedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE1", null), true, false);
      Set<?> membs = pr1.getRegionAdvisor()
          .adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(1, membs.size());

      membs = pr1.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(membs.size(), 0);

      PartitionedRegion pr2 = (PartitionedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE2", null), true, false);

      membs = pr2.getRegionAdvisor().adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 0);

      membs = pr2.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(membs.size(), 0);

      Runnable asyncEventConfig = getExecutorForWBCLConfiguration(
          "SG2, SG0",
          "MYASYNCEVENTLISTENER",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$TestNewGatewayEventListener",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "init-param-str", false);
      asyncEventConfig.run();

      membs = pr2.getRegionAdvisor().adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 0);

      membs = pr2.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(membs.size(), 1);
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 1);
      }

      PartitionedRegion pr3 = (PartitionedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE3", null), true, false);

      membs = pr3.getRegionAdvisor().adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 2);
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }

      membs = pr3.getRegionAdvisor().adviseAsyncEventQueue();
      assertEquals(membs.size(), 2);
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }
    }
    finally {
      clientSQLExecute(1, "drop table TESTTABLE1");
      clientSQLExecute(1, "drop table TESTTABLE2");
      clientSQLExecute(1, "drop table TESTTABLE3");
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      Statement derbyStmt = derbyConn.createStatement();
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }

  }
  /*
   * yogesh:20dec11 this test starts a gateway sender which requires locator
   * started in remote ds so this test needs more than five vms and needs to be
   * re written in wan dunit configuration which has 8 vms
   */
  public void disable_testGfxdMemberAdviseAPIForReplicatedTable_1_Bug42758()
      throws Exception {

    startNetworkServer();
    createDerbyValidationArtefacts();
    // Create a configuration with a system where each VM is part of a unique
    // server group.

    // Only gemfirexd gateway hub
    AsyncVM async1 = invokeStartServerVM(1, -1, "SG1", null);

    // only asynch event listener
    AsyncVM async2 = invokeStartServerVM(2, -1, "SG2", null);

    // only asynch db synchronizer
    AsyncVM async3 = invokeStartServerVM(3, -1, "SG3", null);

    // only asynch db synchronizer
    AsyncVM async4 = invokeStartServerVM(4, -1, "SG4", null);

    // All
    startClientVMs(1, -1, "SG0");

    joinVMs(true, async1, async2, async3, async4);

    Runnable gtwayHubCreator = getHubCreatorExecutor("SG1, SG0", "MYSENDER",
        null, 128, null, null, null);

    String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Runnable asynchDBSynch = getExecutorForWBCLConfiguration("SG3, SG4, SG0",
        "MYASYNCDBSYNCRO", "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        "org.apache.derby.jdbc.ClientDriver", derbyDbUrl + ",app", true,
        Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
        "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);

    // create table
    try {
      serverSQLExecute(
          1,
          "create table TESTTABLE1 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) replicate gatewaysender (MYSENDER) ");
      // create table
      serverSQLExecute(
          1,
          "create table TESTTABLE2 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) replicate "
              + "AsyncEventListener (MYASYNCEVENTLISTENER) ");
      // create table
      serverSQLExecute(
          1,
          "create table TESTTABLE3 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) replicate  AsyncEventListener"
              + " (MYASYNCDBSYNCRO) ");

      gtwayHubCreator.run();
      asynchDBSynch.run();

      final String schemaName = getCurrentDefaultSchemaName();
      DistributedRegion dr1 = (DistributedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE1", null), true, false);
      CacheDistributionAdvisor advisor = dr1.getCacheDistributionAdvisor();
      Set<?> membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(1, membs.size());

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(membs.size(), 0);

      DistributedRegion dr2 = (DistributedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE2", null), true, false);
      advisor = dr2.getCacheDistributionAdvisor();

      membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 0);

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(membs.size(), 0);

      Runnable asyncEventConfig = getExecutorForWBCLConfiguration(
          "SG2, SG0",
          "MYASYNCEVENTLISTENER",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$TestNewGatewayEventListener",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "init-param-str", false);
      asyncEventConfig.run();

      membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(0, membs.size());

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(1, membs.size());
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 1);
      }

      DistributedRegion dr3 = (DistributedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE3", null), true, false);
      advisor = dr3.getCacheDistributionAdvisor();
      membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 2);
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(membs.size(), 2);
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }
    }
    finally {
      clientSQLExecute(1, "drop table TESTTABLE1");
      clientSQLExecute(1, "drop table TESTTABLE2");
      clientSQLExecute(1, "drop table TESTTABLE3");
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      Statement derbyStmt = derbyConn.createStatement();
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }

  }

  // Hubs created before region creation
  /*
   * yogesh:20dec11 this test starts a gateway sender which requires locator
   * started in remote ds so this test needs more than five vms and needs to be
   * re written in wan dunit configuration which has 8 vms
   */
  public void disable_testGfxdMemberAdviseAPIForReplicatedTable_2() throws Exception {

    startNetworkServer();
    createDerbyValidationArtefacts();
    // Create a configuration with a system where each VM is part of a unique
    // server group.

    // Only gemfirexd gateway hub
    AsyncVM async1 = invokeStartServerVM(1, -1, "SG1", null);

    // only asynch event listener
    AsyncVM async2 = invokeStartServerVM(2, -1, "SG2", null);

    // only asynch db synchronizer
    AsyncVM async3 = invokeStartServerVM(3, -1, "SG3", null);

    // only asynch db synchronizer
    AsyncVM async4 = invokeStartServerVM(4, -1, "SG4", null);

    // All
    startClientVMs(1, -1, "SG0");

    joinVMs(true, async1, async2, async3, async4);

    // final String gatewayID = "MY_GATEWAY_";
    Runnable gtwayHubCreator = getHubCreatorExecutor("SG1, SG0", "MYSENDER",
        null, 128, null, null, null);

    gtwayHubCreator.run();
    String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Runnable asynchDBSynch = getExecutorForWBCLConfiguration("SG3, SG4, SG0",
        "MYASYNCDBSYNCHRO", "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        "org.apache.derby.jdbc.ClientDriver", derbyDbUrl + ",app,app", true,
        Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
        "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
    asynchDBSynch.run();
    // create table
    try {
      serverSQLExecute(
          1,
          "create table TESTTABLE1 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) replicate gatewaysender (MYSENDER) ");
      // create table
      serverSQLExecute(
          1,
          "create table TESTTABLE2 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) replicate "
              + "AsyncEventListener (MYASYNCEVENTLISTENER) ");
      // create table
      serverSQLExecute(
          1,
          "create table TESTTABLE3 (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) replicate  AsyncEventListener"
              + " (MYASYNCDBSYNCHRO) ");

      final String schemaName = getCurrentDefaultSchemaName();
      DistributedRegion dr1 = (DistributedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE1", null), true, false);
      CacheDistributionAdvisor advisor = dr1.getDistributionAdvisor();
      Set<?> membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(1, membs.size());

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(membs.size(), 0);

      DistributedRegion dr2 = (DistributedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE2", null), true, false);
      advisor = dr2.getCacheDistributionAdvisor();

      membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 0);

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(membs.size(), 0);

      Runnable asyncEventConfig = getExecutorForWBCLConfiguration(
          "SG2, SG0",
          "MYASYNCEVENTLISTENER",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$TestNewGatewayEventListener",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "init-param-str", false);
      asyncEventConfig.run();

      membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(0, membs.size());

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(1, membs.size());
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 1);
      }

      DistributedRegion dr3 = (DistributedRegion)Misc.getRegion(Misc
          .getRegionPath(schemaName, "TESTTABLE3", null), true, false);
      advisor = dr3.getCacheDistributionAdvisor();
      membs = advisor.adviseSerialAsyncEventQueueOrGatewaySender();
      assertEquals(membs.size(), 2);
      // Get VMs for the members
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }

      membs = advisor.adviseAsyncEventQueue();
      assertEquals(membs.size(), 2);
      for (Object mem : membs) {
        VM vm = members.get(mem);
        int vmIndex = serverVMs.indexOf(vm);
        assertTrue(vmIndex == 2 || vmIndex == 3);
      }
    }
    finally {
      clientSQLExecute(1, "drop table TESTTABLE1");
      clientSQLExecute(1, "drop table TESTTABLE2");
      clientSQLExecute(1, "drop table TESTTABLE3");
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      Statement derbyStmt = derbyConn.createStatement();
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }

  }

  public void DISABLED_testOracle_UseCase1() throws Throwable {
    startVMs(1, 2, 0, "CHANNELDATAGRP", null);
    int netPort = startNetworkServer(1, null, null);
    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE gemfire.SECL_BO_DATA_STATUS_HIST ("
        + "       BO_TXN_ID VARCHAR (36) NOT NULL ,"
        + "       CLIENT_ID VARCHAR (100) ,"
        + "       CLIENT_NAME VARCHAR (100) ,"
        + "       CLIENT_ACCOUNT VARCHAR (100) ,"
        + "       COMPANY_ID VARCHAR (100) ,"
        + "       CLIENT_REF_NO VARCHAR (100) ,"
        + "       VALUE_DATE TIMESTAMP ,"
        + "       AMOUNT DECIMAL (16,2) ,"
        + "       CURRENCY VARCHAR (20) ,"
        + "       ORIG_BANK_ID VARCHAR (100) ,"
        + "       BACKOFFICE_CODE VARCHAR (100) NOT NULL ,"
        + "       BENE_ACCNT_NO VARCHAR (100) ,"
        + "       BENE_NAME VARCHAR (100) ,"
        + "       BENE_ADDR VARCHAR (256) ,"
        + "       BENE_BANK_ID VARCHAR (100) ,"
        + "       BENE_BANK_NAME VARCHAR (100) ,"
        + "       BENE_BANK_ADDR VARCHAR (256) ,"
        + "       INSTR_CREATED_TIME TIMESTAMP ,"
        + "       INSTR_CREATED_BY VARCHAR (100) ,"
        + "       DATA_LIFE_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_DATE TIMESTAMP ,"
        + "       MATCH_CATEG_ID INTEGER ,"
        + "       MATCHING_TIME INTEGER WITH DEFAULT -1 ,"
        + "       MANUAL_MATCH CHAR (1) WITH DEFAULT 'N' ,"
        + "       MATCHING_REASON VARCHAR (128) ,"
        + "       SCREENING_TIME INTEGER NOT NULL ,"
        + "       IS_MANUAL CHAR (1) WITH DEFAULT 'N' ,"
        + "       IS_RESENT CHAR (1) WITH DEFAULT 'N' ,"
        + "       CHANNEL_NAME VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "       TXN_TYPE VARCHAR (30) WITH DEFAULT 'UNKNOWN' ,"
        + "       OFAC_MSG_ID VARCHAR (64) NOT NULL ,"
        + "       HIT_STATUS SMALLINT ,"
        + "       FILE_TYPE VARCHAR (36) WITH DEFAULT 'NA',"
        + "       ACTUAL_VALUE_DATE TIMESTAMP ,"
        + "       LAST_UPDATE_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP )"
        + "      PARTITION BY COLUMN (BO_TXN_ID)"
        + "      REDUNDANCY 1 "
        + "       EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW"
        + "      PERSISTENT ASYNCHRONOUS");

    /**
     * check the new password encryption capability which is DS specific using
     * "secret=" argument or via the new external properties file in
     * DBSynchronizer
     */
    final String oraUser = "gemfire";
    final String oraPasswd = "lu5Pheko";
    final String oraUrl = "jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=60)"
        + "(ADDRESS=(PROTOCOL=TCP)(HOST=oracle.gemstone.com)"
        + "(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=XE)))";
    final String transformation = "Blowfish/CBC/NOPADDING";
    final int keySize = 64;
    final String oraFile = "ora.props";
    final String encryptedPassword = new GfxdSystemAdmin().encryptForExternal(
        "encrypt-password", Arrays.asList(new String[] { "-locators="
            + getDUnitLocatorString(), "-transformation=" + transformation,
            "-keysize=" + keySize }), oraUser, oraPasswd);
    // create an external properties file
    Properties oraProps = new Properties();
    oraProps.setProperty("user", oraUser);
    oraProps.setProperty("Secret", encryptedPassword);
    oraProps.setProperty("URL", oraUrl);
    oraProps.setProperty("driver", "oracle.jdbc.driver.OracleDriver");
    oraProps.setProperty("transformation", transformation);
    oraProps.setProperty("KeySize", Integer.toString(keySize));
    FileOutputStream out = new FileOutputStream(oraFile);
    oraProps.store(out, "Generated file -- do not change manually");
    out.flush();
    out.close();

    final Throwable[] failure = new Throwable[1];
    Thread t = new Thread(new Runnable() {
      public void run() {
        // try a few times in a loop
        ExpectedException expectedEx = addExpectedException(
            "java.sql.SQLRecoverableException");
        for (int tries = 1; tries <= 5; tries++) {
          try {
            Connection conn = TestUtil.getConnection();
            Statement stmt = conn.createStatement();
            stmt.execute("create asynceventlistener "
                + "SECL_BO_DATA_STATUS_HIST_SYNC (listenerclass "
                + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'file=" + oraFile + "' ENABLEPERSISTENCE true "
                + "MANUALSTART false ALERTTHRESHOLD 2000) "
                + "SERVER GROUPS(CHANNELDATAGRP)");
            failure[0] = null;
            break;
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
        expectedEx.remove();
      }
    });

    t.start();
    Connection oraConn = createOraConnection(oraUrl, oraUser, oraPasswd);
    Statement oraStmt = oraConn.createStatement();
    t.join();
    if (failure[0] != null) {
      throw failure[0];
    }
    try {
      // create table if not already created
      oraStmt.execute("CREATE TABLE gemfire.SECL_BO_DATA_STATUS_HIST("
          + "    BO_TXN_ID VARCHAR2(36) NOT NULL,"
          + "    CLIENT_ID VARCHAR2(100),"
          + "    CLIENT_NAME VARCHAR2(100),"
          + "    CLIENT_ACCOUNT VARCHAR2(100),"
          + "    COMPANY_ID VARCHAR2(100),"
          + "    CLIENT_REF_NO VARCHAR2(100),"
          + "    VALUE_DATE TIMESTAMP,"
          + "    AMOUNT NUMBER(16,2),"
          + "    CURRENCY VARCHAR2(20),"
          + "    ORIG_BANK_ID VARCHAR2(100),"
          + "    BACKOFFICE_CODE VARCHAR2(100) NOT NULL,"
          + "    BENE_ACCNT_NO VARCHAR2(100),"
          + "    BENE_NAME VARCHAR2(100),"
          + "    BENE_ADDR VARCHAR2(256),"
          + "    BENE_BANK_ID VARCHAR2(100),"
          + "    BENE_BANK_NAME VARCHAR2(100),"
          + "    BENE_BANK_ADDR VARCHAR2(256),"
          + "    INSTR_CREATED_TIME TIMESTAMP,"
          + "    INSTR_CREATED_BY VARCHAR2(100),"
          + "    DATA_LIFE_STATUS NUMBER(5) DEFAULT 0,"
          + "    MATCH_STATUS NUMBER(5) DEFAULT 0,"
          + "    MATCH_DATE TIMESTAMP,"
          + "    MATCH_CATEG_ID INTEGER,"
          + "    MATCHING_TIME INTEGER DEFAULT -1,"
          + "    MANUAL_MATCH CHAR(1) DEFAULT 'N',"
          + "    MATCHING_REASON VARCHAR2(128),"
          + "    SCREENING_TIME INTEGER NOT NULL,"
          + "    IS_MANUAL CHAR(1) DEFAULT 'N',"
          + "    IS_RESENT CHAR(1) DEFAULT 'N',"
          + "    CHANNEL_NAME VARCHAR2(100) DEFAULT 'UNKNOWN',"
          + "    TXN_TYPE VARCHAR2(30) DEFAULT 'UNKNOWN',"
          + "    OFAC_MSG_ID VARCHAR2(64) NOT NULL,"
          + "    HIT_STATUS NUMBER(5),"
          + "    FILE_TYPE VARCHAR2(36) DEFAULT 'NA',"
          + "    ACTUAL_VALUE_DATE TIMESTAMP,"
          + "    LAST_UPDATE_TIME TIMESTAMP"
          + ")");
    } catch (SQLException sqle) {
      // ignore exception
    }

    // first acquire a lock row to protect against concurrent dunit runs
    // just "lock table" will not do since we want to lock across connections
    // i.e. for the DBSynchronizer conn too, till end of the test
    oraConn.setAutoCommit(false);
    final String lockKey = "DBSP_LOCK";
    boolean locked = false;
    try {
      int numTries = 0;
      while (!locked) {
        oraStmt.execute("lock table gemfire.SECL_BO_DATA_STATUS_HIST "
            + "in exclusive mode");
        ResultSet rs = oraStmt.executeQuery("select count(*) from gemfire."
            + "SECL_BO_DATA_STATUS_HIST where BO_TXN_ID='" + lockKey + "'");
        rs.next();
        if (rs.getInt(1) == 0 || ++numTries > 100) {
          // clear the table
          oraStmt.execute("truncate table gemfire.SECL_BO_DATA_STATUS_HIST");
          oraStmt.execute("INSERT INTO gemfire.SECL_BO_DATA_STATUS_HIST "
              + "(BO_TXN_ID, BACKOFFICE_CODE, SCREENING_TIME, OFAC_MSG_ID) "
              + "values ('" + lockKey + "', 'NA', 0, 'NA')");
          locked = true;
        }
        else {
          Thread.sleep(5000);
        }
        oraConn.commit();
      }

      stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
          + "SET  asynceventlistener(SECL_BO_DATA_STATUS_HIST_SYNC)");
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      PreparedStatement pstmt = conn.prepareStatement("INSERT INTO "
          + "  gemfire.SECL_BO_DATA_STATUS_HIST (    "
          + "  BO_TXN_ID,   CLIENT_ID,    CLIENT_NAME,  CLIENT_ACCOUNT, "
          + "      COMPANY_ID,  CLIENT_REF_NO,    VALUE_DATE, AMOUNT,  "
          + "       CURRENCY,       ORIG_BANK_ID,   BACKOFFICE_CODE,    "
          + " BENE_ACCNT_NO,  BENE_NAME,    BENE_ADDR,   BENE_BANK_ID, "
          + "  BENE_BANK_NAME, BENE_BANK_ADDR, INSTR_CREATED_TIME,    "
          + " INSTR_CREATED_BY,  DATA_LIFE_STATUS,       MATCH_STATUS,  "
          + " MATCH_DATE, MATCH_CATEG_ID, MATCHING_TIME,  MANUAL_MATCH,  "
          + " MATCHING_REASON,   SCREENING_TIME, IS_MANUAL,    IS_RESENT, "
          + "     CHANNEL_NAME,   TXN_TYPE,   OFAC_MSG_ID, HIT_STATUS,  "
          + " ACTUAL_VALUE_DATE, LAST_UPDATE_TIME) values(?,?,?,?,?,?,?,?,"
          + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
          + "CURRENT_TIMESTAMP)");
      for (int i = 1; i <= 200; i++) {
        pstmt.setObject(1,
            "09824f04-26ef-49b0-95b2-955d3742" + String.format("%04d", i));
        pstmt.setObject(2, "party name");
        pstmt.setObject(3, null);
        pstmt.setObject(4, "12345678");
        pstmt.setObject(5, "1874563");
        pstmt.setObject(6, "PB130482");
        pstmt.setTimestamp(7, Timestamp.valueOf("2012-07-18 00:00:00.0"));
        pstmt.setObject(8, "158.26");
        pstmt.setObject(9, "CAD");
        pstmt.setObject(10, "CHASGB2LXXX");
        pstmt.setObject(11, "IPAY");
        pstmt.setObject(12, null);
        pstmt.setObject(13, null);
        pstmt.setObject(14, null);
        pstmt.setObject(15, null);
        pstmt.setObject(16, null);
        pstmt.setObject(17, null);
        pstmt.setObject(18, Timestamp.valueOf("2013-03-13 14:20:04.05"));
        pstmt.setObject(19, null);
        pstmt.setObject(20, 1);
        pstmt.setObject(21, 1);
        pstmt.setObject(22, Timestamp.valueOf("2013-03-13 14:20:04.28"));
        pstmt.setObject(23, 2);
        pstmt.setObject(24, 0);
        pstmt.setObject(25, "N");
        pstmt.setObject(26, null);
        pstmt.setObject(27, -1);
        pstmt.setObject(28, "N");
        pstmt.setObject(29, "N");
        pstmt.setObject(30, "PYS");
        pstmt.setObject(31, "UNKNOWN");
        pstmt.setObject(32,
            "MITHUN0621                                                      ");
        pstmt.setObject(33, null);
        pstmt.setObject(34, Timestamp.valueOf("2010-03-19 00:00:00.0"));
        // pstmt.addBatch();
        // pstmt.executeBatch();
        assertEquals(1, pstmt.executeUpdate());
        conn.commit();
      }

      // check updated in Oracle
      stmt.execute("call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH("
          + "'SECL_BO_DATA_STATUS_HIST_SYNC', 1, 0)");
      conn.commit();

      ResultSet rs = oraStmt.executeQuery("select * from "
          + "gemfire.SECL_BO_DATA_STATUS_HIST where BO_TXN_ID <> '" + lockKey
          + "' order by BO_TXN_ID");
      for (int i = 1; i <= 200; i++) {
        assertTrue(rs.next());
        assertEquals(
            "09824f04-26ef-49b0-95b2-955d3742" + String.format("%04d", i),
            rs.getString(1));
      }
      assertFalse(rs.next());
      rs.close();

    } finally {
      try {
        oraConn.commit();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      if (locked) {
        // try hard to release the lock
        for (;;) {
          try {
            oraStmt.execute("delete from gemfire.SECL_BO_DATA_STATUS_HIST "
                + "where BO_TXN_ID='" + lockKey + "'");
            break;
          } catch (SQLException sqle) {
            // retry in case of exception
            try {
              oraStmt.close();
            } catch (SQLException e) {
            }
            try {
              oraConn.commit();
            } catch (SQLException e) {
            }
            try {
              oraConn.close();
            } catch (SQLException e) {
            }
            oraConn = createOraConnection(oraUrl, oraUser, oraPasswd);
            oraStmt = oraConn.createStatement();
          }
        }
      }
      try {
        oraStmt.execute("truncate table gemfire.SECL_BO_DATA_STATUS_HIST");
        oraConn.commit();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      try {
        oraConn.close();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      new File(oraFile).delete();
    }
    conn.commit();
    stmt.close();
    conn.close();
  }
}
