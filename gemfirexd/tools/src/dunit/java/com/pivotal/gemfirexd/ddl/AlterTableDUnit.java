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

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Properties;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.jdbc.AlterTableTest;
import com.pivotal.gemfirexd.jdbc.AlterTableTest.ConstraintNumber;
import com.pivotal.gemfirexd.jdbc.CreateTable2Test;
import com.pivotal.gemfirexd.jdbc.CreateTableTest;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import org.apache.commons.io.FileUtils;

/**
 * DUnit tests for "ALTER TABLE" with replay and persistence.
 * 
 * @author swale
 */
@SuppressWarnings("serial")
public class AlterTableDUnit extends DistributedSQLTestBase {

  private final static String DISKSTORE = "TestPersistentAlterTable";

  public AlterTableDUnit(String name) {
    super(name);
  }

  private String getPersistenceSuffix() {
    return " PERSISTENT " + "'" + DISKSTORE + "'";
  }

  private String getPRSuffix() {
    return " REDUNDANCY 2";
  }

  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();

    // Delete the disk directories created during the test
    FileUtils.deleteQuietly(new File("DSYS"));
    FileUtils.deleteQuietly(new File("DSYS2"));
  }

  /**
   * Test to check for add/drop constraints using ALTER TABLE with replay and DD
   * persistence.
   */
  public void testConstraints() throws Exception {

    // start a client and server for adding constraints
    startVMs(1, 1);

    TestUtil.addExpectedException(EntryExistsException.class);

    // check that PK add is disallowed with data
    serverSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");

    String ckFile = TestUtil.getResourcesDir() + "/lib/checkQuery.xml";
    // run the query on VMs to check there is no data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1 },
        "select * from trade.customers", ckFile, "empty");

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();
    CreateTableTest.populateData(conn, false, true);
    try {
      serverSQLExecute(1, "alter table trade.customers "
          + "add constraint cust_pk primary key (cid)");
      fail("Expected unsupported exception for PK add in ALTER TABLE");
    } catch (RMIException ex) {
      if (ex.getCause() instanceof SQLException) {
        SQLException sqlEx = (SQLException)ex.getCause();
        if (!"0A000".equals(sqlEx.getSQLState())) {
          throw ex;
        }
      }
      else {
        throw ex;
      }
    }

    // check that PK add is allowed immediately after table create
    clientSQLExecute(1, "drop table trade.customers");
    serverSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
    serverSQLExecute(1, "alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");

    // populate data again and start a new server VM to check that
    // initial replay goes through even with data
    CreateTableTest.populateData(conn, false, true);
    startVMs(0, 1);
    // adding a new unique constraint should succeed even with data
    serverSQLExecute(1, "alter table trade.customers "
        + "add constraint cust_uk unique (tid)");
    // run a query on VMs just to check it does not fail
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select count(*) from trade.customers", null, "100");
    // check the primary and unique constraints
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);

    // now check the above for a server with DD persistence
    Properties props = new Properties();
    props.setProperty(Attribute.SYS_PERSISTENT_DIR, "DSYS");
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    startVMs(0, 1, 0, null, props);

    // run a query on VMs again just to check it does not fail
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.customers", null, "100");

    // drop table and recreate the constraints
    clientSQLExecute(1, "drop table trade.customers");
    serverSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
    serverSQLExecute(3, "alter table trade.customers "
        + "add constraint cust_uk unique (tid)");
    clientSQLExecute(1, "alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    CreateTableTest.populateData(conn, false, true);
    // check constraints
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);

    // shut down all VMs and restart to check that everything is setup fine
    stopVMNums(-1, 1);
    stopVMNums(-3, -2);

    // seeing possible EntryNotFoundExceptions here in PR meta-data
    addExpectedException(null, new int[] { 1, 2, 3 },
        EntryNotFoundException.class);
    AsyncVM async1 = restartServerVMAsync(1, 0, null, props);
    AsyncVM async2 = restartServerVMAsync(2, 0, null, null);
    props.setProperty(Attribute.SYS_PERSISTENT_DIR, "DSYS2");
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    // wait for DD creation to complete
    waitForDDCreation(-2);
    AsyncVM async3 = restartServerVMAsync(3, 0, null, props);
    restartVMNums(1);
    joinVMs(false, async1, async2, async3);
    removeExpectedException(null, new int[] { 1, 2, 3 },
        EntryNotFoundException.class);

    // run the query on VMs to check there is no data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers", ckFile, "empty");

    // now populate data and check again
    CreateTableTest.populateData(TestUtil.jdbcConn, false, true);
    serverSQLExecute(1, "call sys.rebalance_all_buckets()");
    VM datastore1 = this.serverVMs.get(0);
    VM datastore2 = this.serverVMs.get(1);
    VM datastore3 = this.serverVMs.get(2);
    VM client1    = this.clientVMs.get(0);
    SelectQueryInfo[] sqi = new SelectQueryInfo[1];
    setupObservers(new VM[] { datastore1, datastore2, datastore3 }, sqi);
    //with #42682. 
    SerializableRunnable setObserver = new SerializableRunnable(
        "Set GemFireXDObserver on DataStore Node") {
      @Override
      public void run() throws CacheException {
        try {
          isQueryExecutedOnNode = false;
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void createdGemFireXDResultSet(ResultSet rs) {
                  getLogWriter().info("Invoked DistributedSQLTestBase query "
                      + "observer for prepared statement query: "
                      + rs.getActivation().getPreparedStatement()
                          .getUserQueryString(rs.getActivation()
                              .getLanguageConnectionContext()));
                  isQueryExecutedOnNode = true;
                }
              });
        } catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    setObserver.run();
    sqlExecuteVerify(new int[] { 1 }, null,
        "select count(*) from trade.customers", null, "100");
    checkQueryExecution(false, datastore1, datastore2, datastore3, client1);
    GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverHolder());

    datastore2.invoke(setObserver);
    sqlExecuteVerify(null, new int[] { 2 },
        "select count(*) from trade.customers", null, "100");
    checkQueryExecution(false, datastore1, datastore2, datastore3);

    setupObservers(new VM[] { datastore2 }, sqi);
    datastore3.invoke(setObserver);
    sqlExecuteVerify(null, new int[] { 3 },
        "select count(*) from trade.customers", null, "100");
    checkQueryExecution(false, datastore1, datastore2, datastore3);

    setupObservers(new VM[] { datastore3 }, sqi);
    datastore1.invoke(setObserver);
    sqlExecuteVerify(null, new int[] { 1 },
        "select count(*) from trade.customers", null, "100");
    checkQueryExecution(false, datastore1, datastore2, datastore3);

    TestUtil.removeExpectedException(EntryExistsException.class);
  }

  /**
   * Test for adding/dropping foreign key constraint with parent table not
   * partitioned by primary key i.e. having global index for PK resulting in a
   * global index scan. Also test the same for persistent tables.
   */
  public void testConstraintsWithGlobalOrHashIndex_42514() throws Exception {
    if (isTransactional) {
      return;
    }
    Properties props = new Properties();
    props.setProperty("disable-streaming", "true");
    // start a client and servers for adding constraints
    startVMs(1, 3, 0, null, props);

    // create the diskstore for the persistent tables

    serverExecute(2, getDiskStoreCreator(DISKSTORE));

    // first create the tables

    serverSQLExecute(2, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid))" + getPRSuffix());

    clientSQLExecute(1, "create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
        + "'lse', 'fse', 'hkse', 'tse')))  partition by list (tid) "
        + "(VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), "
        + "VALUES (12, 13, 14, 15, 16, 17))" + getPRSuffix());

    serverSQLExecute(1, "create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk "
        + "primary key (cid, sid), constraint cust_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint qty_ck check (qty>=0), constraint avail_ch check "
        + "(availQty>=0 and availQty<=qty)) partition by column (cid, sid)"
        + getPRSuffix());

    serverSQLExecute(3, "create table tradep.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid))" + getPRSuffix()
        + getPersistenceSuffix());

    clientSQLExecute(1, "create table tradep.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id, tid), constraint sec_uq unique (symbol, "
        + "exchange), constraint exc_ch check (exchange in ('nasdaq', "
        + "'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  partition by list "
        + "(tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), "
        + "VALUES (12, 13, 14, 15, 16, 17))" + getPRSuffix()
        + getPersistenceSuffix());

    serverSQLExecute(2, "create table tradep.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk "
        + "primary key (cid, sid), constraint cust_fk foreign key (cid) "
        + "references tradep.customers (cid) on delete restrict, "
        + "constraint qty_ck check (qty>=0), constraint avail_ch check "
        + "(availQty>=0 and availQty<=qty)) partition by column (cid, sid)"
        + getPRSuffix() + getPersistenceSuffix());

    // run the query on VMs to check there is no data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.customers", null, "0");

    Connection conn = TestUtil.jdbcConn;
    CreateTableTest.populateData(conn, true, false);
    CreateTableTest.populateData(conn, true, false, false, false, false,
        "tradep.customers", "tradep.portfolio");
    // check data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.customers", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.portfolio", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from tradep.customers", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from tradep.portfolio", null, "100");

    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        SQLException.class, EntryExistsException.class });

    // adding FK constraint without any data in trade.securities should fail
    checkKeyViolation(1, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");
    checkKeyViolation(-1, "alter table tradep.portfolio add constraint sec_fk "
        + "foreign key (sid, tid) references tradep.securities (sec_id, tid)",
        "X0Y45", "FK violation");

    // now add some data in trade.securities
    PreparedStatement prepStmt = conn.prepareStatement("insert into trade"
        + ".securities (sec_id, symbol, exchange, tid) values (?, ?, ?, ?)");
    PreparedStatement prepStmt2 = conn.prepareStatement("insert into tradep"
        + ".securities (sec_id, symbol, exchange, tid) values (?, ?, ?, ?)");
    int index;
    for (index = 20; index < 70; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "amex");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }
    for (index = 20; index < 70; ++index) {
      prepStmt2.setInt(1, index + 2);
      prepStmt2.setString(2, "SYM" + index);
      prepStmt2.setString(3, "amex");
      prepStmt2.setInt(4, index);
      prepStmt2.execute();
    }

    // adding FK constraint should still fail
    checkKeyViolation(-2, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");
    checkKeyViolation(-3, "alter table tradep.portfolio add constraint sec_fk "
        + "foreign key (sid, tid) references tradep.securities (sec_id, tid)",
        "X0Y45", "FK violation");

    // add more data with just one miss, so it should fail on some server
    // but not on others
    for (index = 0; index < 20; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "fse");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }
    for (index = 71; index < 100; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "nasdaq");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }
    for (index = 0; index < 20; ++index) {
      prepStmt2.setInt(1, index + 2);
      prepStmt2.setString(2, "SYM" + index);
      prepStmt2.setString(3, "fse");
      prepStmt2.setInt(4, index);
      prepStmt2.execute();
    }
    for (index = 71; index < 100; ++index) {
      prepStmt2.setInt(1, index + 2);
      prepStmt2.setString(2, "SYM" + index);
      prepStmt2.setString(3, "nasdaq");
      prepStmt2.setInt(4, index);
      prepStmt2.execute();
    }

    // adding FK constraint should still fail
    checkKeyViolation(-1, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");
    checkKeyViolation(1, "alter table tradep.portfolio add constraint sec_fk "
        + "foreign key (sid, tid) references tradep.securities (sec_id, tid)",
        "X0Y45", "FK violation");

    // finally full data should pass
    index = 70;
    prepStmt.setInt(1, index + 2);
    prepStmt.setString(2, "SYM" + index);
    prepStmt.setString(3, "fse");
    prepStmt.setInt(4, index);
    prepStmt.execute();
    clientSQLExecute(1, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)");
    prepStmt2.setInt(1, index + 2);
    prepStmt2.setString(2, "SYM" + index);
    prepStmt2.setString(3, "fse");
    prepStmt2.setInt(4, index);
    prepStmt2.execute();
    serverSQLExecute(1, "alter table tradep.portfolio add constraint sec_fk "
        + "foreign key (sid, tid) references tradep.securities (sec_id, tid)");

    // more data should still not be a problem
    for (index = 120; index < 150; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "nye");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }
    serverSQLExecute(1, "alter table trade.portfolio drop constraint sec_fk");
    serverSQLExecute(3, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)");
    for (index = 120; index < 150; ++index) {
      prepStmt2.setInt(1, index + 2);
      prepStmt2.setString(2, "SYM" + index);
      prepStmt2.setString(3, "nye");
      prepStmt2.setInt(4, index);
      prepStmt2.execute();
    }
    serverSQLExecute(2, "alter table tradep.portfolio drop constraint sec_fk");
    clientSQLExecute(1, "alter table tradep.portfolio add constraint sec_fk "
        + "foreign key (sid, tid) references tradep.securities (sec_id, tid)");

    // some delete now should again cause FK violation
    serverSQLExecute(2, "alter table trade.portfolio drop constraint sec_fk");
    clientSQLExecute(1, "delete from trade.securities where sec_id=32");
    checkKeyViolation(-3, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");
    serverSQLExecute(3, "alter table tradep.portfolio drop constraint sec_fk");
    serverSQLExecute(2, "delete from tradep.securities where sec_id=62");
    checkKeyViolation(-2, "alter table tradep.portfolio add constraint sec_fk "
        + "foreign key (sid, tid) references tradep.securities (sec_id, tid)",
        "X0Y45", "FK violation");

    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { SQLException.class, EntryExistsException.class });

    // add FK constraint back to check for initial replay of alter table
    index = 30;
    prepStmt.setInt(1, index + 2);
    prepStmt.setString(2, "SYM" + index);
    prepStmt.setString(3, "hkse");
    prepStmt.setInt(4, index);
    prepStmt.execute();
    serverSQLExecute(2, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)");
    index = 60;
    prepStmt2.setInt(1, index + 2);
    prepStmt2.setString(2, "SYM" + index);
    prepStmt2.setString(3, "hkse");
    prepStmt2.setInt(4, index);
    prepStmt2.execute();
    serverSQLExecute(1, "alter table tradep.portfolio add constraint sec_fk "
        + "foreign key (sid, tid) references tradep.securities (sec_id, tid)");

    // start a new server VM to check that initial replay goes through
    startVMs(0, 1);
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.customers", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.securities", null, "130");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.portfolio", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from tradep.customers", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from tradep.securities", null, "130");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from tradep.portfolio", null, "100");

    // now check the above for a server with DD persistence
    // we might get a PartitionOfflineException during async stop since the
    // latest data of a bucket may go down first
    addExpectedException(null, new int[] { 2, 3 }, new Object[] {
        PartitionOfflineException.class, ForceReattemptException.class });
    stopVMNums(-2, -3);
    props.setProperty(Attribute.SYS_PERSISTENT_DIR, "DSYS");
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    restartServerVMNums(new int[] { 2 }, 0, null, props);
    // run a query on VMs again just to check it does not fail
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 4 },
        "select count(*) from trade.securities", null, "130");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 4 },
        "select count(*) from trade.portfolio", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 4 },
        "select count(*) from tradep.securities", null, "130");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 4 },
        "select count(*) from tradep.portfolio", null, "100");

//    // a server without DD persistence should not start for persistent tables
//    props = new Properties();
//    props.setProperty(GfxdConstants.GFXD_PERSIST_DD, "false");
//    // will get an exception in initial replay
//    props.put(TestUtil.EXPECTED_STARTUP_EXCEPTIONS,
//        new Object[] { SQLException.class });
//    try {
//      restartServerVMNums(new int[] { 3 }, 0, null, props);
//      fail("expected the VM to fail in start");
//    } catch (dunit.RMIException ex) {
//      if (!(ex.getCause() instanceof SQLException)) {
//        throw ex;
//      }
//      SQLException sqle = (SQLException)ex.getCause();
//      if (!"XJ040".equals(sqle.getSQLState())) {
//        throw ex;
//      }
//      if (!(sqle.getCause() instanceof SQLException)) {
//        throw ex;
//      }
//      sqle = (SQLException)sqle.getCause();
//      if (!"X0Z14".equals(sqle.getSQLState())) {
//        throw ex;
//      }
//    }
//    props.remove(TestUtil.EXPECTED_STARTUP_EXCEPTIONS);

    // shut down all VMs and restart to check that everything is setup fine
    // we might get a PartitionOfflineException during async stop since the
    // latest data of a bucket may go down first
    addExpectedException(null, new int[] { 1, 2, 4 }, new Object[] {
        PartitionOfflineException.class, ForceReattemptException.class });
    stopVMNums(1, -1, -2, -4);

    // delete the old DataDictionary of server3 that was started without
    // persistence in last run and consequently has old data
    getServerVM(3).invoke(DistributedSQLTestBase.class,
        "deleteDataDictionaryDir");

    props.setProperty(Attribute.SYS_PERSISTENT_DIR, "DSYS");
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    // we may get PartitionOfflineExceptions during VM restarts due to some
    // other VMs being latest copy of some data
    Properties testProps = new Properties();
    testProps.put(TestUtil.EXPECTED_STARTUP_EXCEPTIONS,
        new Object[] { PartitionOfflineException.class.getName() });
    testProps.setProperty("disable-streaming", "true");
    props.put(TestUtil.EXPECTED_STARTUP_EXCEPTIONS,
        new Object[] { PartitionOfflineException.class.getName() });
    AsyncVM async1 = restartServerVMAsync(1, 0, null, testProps);
    AsyncVM async2 = restartServerVMAsync(2, 0, null, props);
    AsyncVM async4 = restartServerVMAsync(4, 0, null, testProps);
    // start server3 later to ensure that it does GII from others since it did
    // not have persisted DD; wait for DD initialization to complete first
    waitForDDCreation(-2);
    AsyncVM async3 = restartServerVMAsync(3, 0, null, testProps);
    restartVMNums(1);
    // now wait for all to finish
    joinVMs(false, async1, async2, async3, async4);
    serverSQLExecute(3, "call sys.rebalance_all_buckets()");
    // now check for data in persistent tables but none in others
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.customers", null, "0");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.securities", null, "0");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.portfolio", null, "0");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from tradep.customers", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from tradep.securities", null, "130");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from tradep.portfolio", null, "100");
  }

  /**
   * Test for adding/dropping foreign key constraint with parent table not
   * partitioned by primary key i.e. having global index for PK resulting in a
   * global index scan. Also test the same for persistent tables.
   */
  public void testConstraintsWithGlobalOrHashIndex_42514_2() throws Exception {
    Properties props = new Properties();
    props.setProperty(Attribute.GFXD_PERSIST_DD, "false");
    // start a client and servers for adding constraints
    startVMs(1, 3, 0, null, props);

    // create the diskstore for the persistent tables

    serverExecute(2, getDiskStoreCreator(DISKSTORE));

    // first create the tables

    serverSQLExecute(2, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid))" + getPRSuffix());

    clientSQLExecute(1, "create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
        + "'lse', 'fse', 'hkse', 'tse')))  partition by list (tid) "
        + "(VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), "
        + "VALUES (12, 13, 14, 15, 16, 17))" + getPRSuffix());

    serverSQLExecute(1, "create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk "
        + "primary key (cid, sid), constraint cust_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint qty_ck check (qty>=0), constraint avail_ch check "
        + "(availQty>=0 and availQty<=qty)) partition by column (cid, sid)"
        + getPRSuffix());

    // run the query on VMs to check there is no data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.customers", null, "0");

    Connection conn = TestUtil.jdbcConn;
    CreateTableTest.populateData(conn, true, false);
    // check data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.customers", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.portfolio", null, "100");

    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        SQLException.class, EntryExistsException.class });

    // adding FK constraint without any data in trade.securities should fail
    checkKeyViolation(1, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");

    // now add some data in trade.securities
    PreparedStatement prepStmt = conn.prepareStatement("insert into trade"
        + ".securities (sec_id, symbol, exchange, tid) values (?, ?, ?, ?)");

    int index;
    for (index = 20; index < 70; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "amex");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }
    // adding FK constraint should still fail
    checkKeyViolation(-2, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");
    // add more data with just one miss, so it should fail on some server
    // but not on others
    for (index = 0; index < 20; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "fse");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }
    for (index = 71; index < 100; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "nasdaq");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }

    // adding FK constraint should still fail
    checkKeyViolation(-1, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");
    // finally full data should pass
    index = 70;
    prepStmt.setInt(1, index + 2);
    prepStmt.setString(2, "SYM" + index);
    prepStmt.setString(3, "fse");
    prepStmt.setInt(4, index);
    prepStmt.execute();
    clientSQLExecute(1, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)");

    // more data should still not be a problem
    for (index = 120; index < 150; ++index) {
      prepStmt.setInt(1, index + 2);
      prepStmt.setString(2, "SYM" + index);
      prepStmt.setString(3, "nye");
      prepStmt.setInt(4, index);
      prepStmt.execute();
    }
    serverSQLExecute(1, "alter table trade.portfolio drop constraint sec_fk");
    serverSQLExecute(3, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)");

    // some delete now should again cause FK violation
    serverSQLExecute(2, "alter table trade.portfolio drop constraint sec_fk");
    clientSQLExecute(1, "delete from trade.securities where sec_id=32");
    checkKeyViolation(-3, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)",
        "X0Y45", "FK violation");

    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { SQLException.class, EntryExistsException.class });

    // add FK constraint back to check for initial replay of alter table
    index = 30;
    prepStmt.setInt(1, index + 2);
    prepStmt.setString(2, "SYM" + index);
    prepStmt.setString(3, "hkse");
    prepStmt.setInt(4, index);
    prepStmt.execute();
    serverSQLExecute(2, "alter table trade.portfolio add constraint "
        + "sec_fk foreign key (sid) references trade.securities (sec_id)");
    index = 60;
    
    // start a new server VM to check that initial replay goes through
    startVMs(0, 1,0, null, props);
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.customers", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.securities", null, "130");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3, 4 },
        "select count(*) from trade.portfolio", null, "100");
    // now check the above for a server with DD persistence
    // we might get a PartitionOfflineException during async stop since the
    // latest data of a bucket may go down first
    addExpectedException(null, new int[] { 2, 3 },
        PartitionOfflineException.class);
    stopVMNums(-2, -3);
    props.setProperty(Attribute.GFXD_PERSIST_DD, "false");
    restartServerVMNums(new int[] { 2 }, 0, null, props);
    // run a query on VMs again just to check it does not fail
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 4 },
        "select count(*) from trade.securities", null, "130");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 4 },
        "select count(*) from trade.portfolio", null, "100");
  }

  /**
   * Test to check for add/drop columns using ALTER TABLE with replay and DD
   * persistence.
   */
  public void testColumns() throws Exception {
    // start a client and couple of servers for adding columns
    startVMs(1, 1);

    TestUtil.addExpectedException(EntryExistsException.class);

    // check that column add is allowed with data
    serverSQLExecute(1, "create table trade.customers "
        + "(cust_name varchar(100), tid int)");

    String ckFile = TestUtil.getResourcesDir() + "/lib/checkQuery.xml";
    // run the query on VMs to check there is no data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1 },
        "select * from trade.customers", ckFile, "empty");

    clientSQLExecute(1, "insert into trade.customers (cust_name, tid) "
        + "values ('CUST1', 1)");
    Connection conn = TestUtil.jdbcConn;
    AlterTableTest.checkAddColumnWithData(conn);

    // check that column add/drop is allowed without data
    clientSQLExecute(1, "drop table trade.customers");
    serverSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
 
    clientSQLExecute(1, "alter table trade.customers drop column tid cascade");
    serverSQLExecute(1, "alter table trade.customers "
        + "add column tid int not null default 1");
    // populate data again and start a new server VM to check that
    // initial replay goes through even with data
    CreateTableTest.populateData(conn, false, true);

    startVMs(0, 1);

    // now adding new columns should still succeed with data
    clientSQLExecute(1, "alter table trade.customers drop column cid cascade");
    serverSQLExecute(1, "alter table trade.customers drop column tid cascade");
    serverSQLExecute(1, "alter table trade.customers "
        + "add column tid int not null default 1");
    PreparedStatement prepStmt = conn.prepareStatement("alter table "
        + "trade.customers add column cid int not null default 1");
    prepStmt.execute();
    final SerializableRunnable checkData = new SerializableRunnable() {
      boolean afterUpdate = false;
      @Override
      public void run() {
        try {
          AlterTableTest.verifyAddColumnWithData(TestUtil.getConnection(), 100,
              afterUpdate);
          afterUpdate = true;
        } catch (SQLException sqle) {
          getLogWriter().error("unexpected exception", sqle);
          fail("unexpected exception", sqle);
        }
      }
    };
    checkData.run();
    serverExecute(1, checkData);
    serverExecute(2, checkData);

    // now check the above for a server with DD persistence
    Properties props = new Properties();
    props.setProperty(Attribute.SYS_PERSISTENT_DIR, "DSYS");
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    startVMs(0, 1, 0, null, props);

    // run query on VMs again just to check it does not fail
    checkData.run();
    serverExecute(1, checkData);
    serverExecute(2, checkData);
    serverExecute(3, checkData);

    // recreate table, then drop and recreate the columns
    clientSQLExecute(1, "drop table trade.customers");
    serverSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");

    serverSQLExecute(3, "alter table trade.customers drop column tid cascade");
    serverSQLExecute(3, "alter table trade.customers "
        + "add column sid int not null default 1");
    clientSQLExecute(1, "alter table trade.customers "
        + "add column tid int not null default 1");
    CreateTableTest.populateData(conn, false, true);

    // shut down all VMs and restart to check that everything is setup fine
    stopVMNums(-1, 1);
    stopVMNums(-3, -2);

    AsyncVM async1 = restartServerVMAsync(1, 0, null, props);
    AsyncVM async2 = restartServerVMAsync(2, 0, null, null);
    props.setProperty(Attribute.SYS_PERSISTENT_DIR, "DSYS2");
    props.setProperty(Attribute.GFXD_PERSIST_DD, "true");
    // wait for DD creation to complete
    waitForDDCreation(-2);
    AsyncVM async3 = restartServerVMAsync(3, 0, null, props);
    restartVMNums(1);
    joinVMs(false, async1, async2, async3);

    // run the query on VMs to check there is no data
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.customers", null, "0");
    // now populate data and check again
    CreateTableTest.populateData(TestUtil.jdbcConn, false, true);
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from trade.customers", null, "100");

    // dropping columns should be possible with data
    conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);
    AlterTableTest.checkDropColumnWithData(conn);
    final SerializableRunnable verifyDrop = new SerializableRunnable() {
      @Override
      public void run() {
        try {
          AlterTableTest.verifyDropColumnWithData(TestUtil.getConnection(),
              true, false, false, false);
        } catch (SQLException sqle) {
          getLogWriter().error("unexpected exception", sqle);
          fail("unexpected exception", sqle);
        }
      }
    };
    serverExecute(1, verifyDrop);
    serverExecute(2, verifyDrop);
    serverExecute(3, verifyDrop);

    // also check with client connection
    int netPort = startNetworkServer(2, null, null);
    conn = TestUtil.getNetConnection(netPort, null, null);
    stmt.execute("drop table trade.portfolio");
    stmt = conn.createStatement();
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);
    AlterTableTest.checkDropColumnWithData(conn);
    serverExecute(1, verifyDrop);
    serverExecute(2, verifyDrop);
    serverExecute(3, verifyDrop);

    TestUtil.removeExpectedException(EntryExistsException.class);
  }

  /**
   * Test for TRUNCATE TABLE particularly that constraints etc. are recreated
   * properly since this drops and recreates the table.
   */
  public void testTruncateTable() throws Exception {

    // start a client and servers
    startVMs(1, 2);

    TestUtil.addExpectedException(EntryExistsException.class);

    // create tables and check for indices and partitioning
    Connection conn = TestUtil.jdbcConn;
    CreateTableTest.createTables(conn);
    Statement stmt = conn.createStatement();
    // add a unique key constraint to portfolio
    serverSQLExecute(1, "alter table trade.portfolio "
        + "add constraint port_uk unique (tid)");
    // populate data
    CreateTableTest.populateData(conn, true, false);
    // check for constraints
    AlterTableTest.checkConstraints(stmt, ConstraintNumber.CUST_PK,
        ConstraintNumber.CUST_UK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK, ConstraintNumber.PORT_CK1,
        ConstraintNumber.PORT_CK2);

    // truncate portfolio table and check the whole thing again
    clientSQLExecute(1, "truncate table trade.portfolio");
    serverSQLExecute(1, "delete from trade.customers where 1=1");
    CreateTableTest.populateData(conn, true, false);
    AlterTableTest.checkConstraints(stmt, ConstraintNumber.CUST_PK,
        ConstraintNumber.CUST_UK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK, ConstraintNumber.PORT_CK1,
        ConstraintNumber.PORT_CK2);

    // check that truncating customers table fails due to child table
    try {
      stmt.execute("truncate table trade.customers");
      fail("Expected exception in truncate table due to child table");
    } catch (SQLException ex) {
      if (!"XCL48".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // drop child portfolio table and check that truncating customers works
    clientSQLExecute(1, "drop table trade.portfolio");
    serverSQLExecute(1, "truncate table trade.customers");
    // populate data and check constraints
    CreateTableTest.populateData(conn, false, true);
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);

    // do this once more then cleanup
    clientSQLExecute(1, "truncate table trade.customers");
    CreateTableTest.populateData(conn, false, true);
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);

    stmt.execute("drop table trade.customers");

    TestUtil.removeExpectedException(EntryExistsException.class);
  }

  public void test44231() throws Exception {

    // start a client and servers
    startVMs(1, 2);

    final Connection conn = TestUtil.getConnection();
    String script = TestUtil.getResourcesDir() + "/lib/bug44231.sql";
    GemFireXDUtils.executeSQLScripts(conn, new String[] { script }, false,
        getLogWriter(), null, null, false);

    // restart the servers
    stopVMNums(-1, -2);
    restartVMNums(-1, -2);

    // check data after restart
    java.sql.ResultSet rs = conn.createStatement().executeQuery(
        "select empno from emp where deptno=10 order by empno");
    assertTrue(rs.next());
    assertEquals(7782, rs.getInt(1));
    assertTrue(rs.next());
    assertEquals(7839, rs.getInt(1));
    assertTrue(rs.next());
    assertEquals(7934, rs.getInt(1));
    assertFalse(rs.next());

    // also some global index lookups
    rs = conn.createStatement().executeQuery(
        "select deptno, job from emp where empno=7654");
    assertTrue(rs.next());
    assertEquals(30, rs.getInt(1));
    assertEquals("SALESMAN", rs.getString(2));
    assertFalse(rs.next());

    // check no match
    rs = conn.createStatement().executeQuery(
        "select deptno, job from emp where empno=7655");
    assertFalse(rs.next());

    rs = conn.createStatement().executeQuery(
        "select deptno, job from emp where empno=7654");
    assertTrue(rs.next());
    assertEquals(30, rs.getInt(1));
    assertEquals("SALESMAN", rs.getString(2));
    assertFalse(rs.next());
    rs = conn.createStatement().executeQuery(
        "select deptno, job from emp where empno=7876");
    assertTrue(rs.next());
    assertEquals(20, rs.getInt(1));
    assertEquals("CLERK", rs.getString(2));
    assertFalse(rs.next());

    // check no match
    rs = conn.createStatement().executeQuery(
        "select deptno, job from emp where empno=7872");
    assertFalse(rs.next());

    rs = conn.createStatement().executeQuery(
        "select deptno, job from emp where empno=7654");
    assertTrue(rs.next());
    assertEquals(30, rs.getInt(1));
    assertEquals("SALESMAN", rs.getString(2));
    assertFalse(rs.next());
    rs = conn.createStatement().executeQuery(
        "select deptno, job from emp where empno=7698");
    assertTrue(rs.next());
    assertEquals(30, rs.getInt(1));
    assertEquals("MANAGER", rs.getString(2));
    assertFalse(rs.next());

    conn.createStatement().execute("delete from emp");
    conn.createStatement().execute("delete from dept");
  }

  public void testAddGeneratedIdentityColumn() throws Exception {
    // start a client and some servers
    // starting servers first to give them lesser VMIds than the client
    startServerVMs(3, 0, null, null);
    startClientVMs(1, 0, null, null);

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size added using ALTER TABLE
    stmt.execute("create table trade.customers (tid int, cid int not null, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");
    // first some inserts with gaps
    final int maxValue = 1000;
    int stepValue = 3;
    PreparedStatement pstmt = conn
        .prepareStatement("insert into trade.customers values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(1, v * stepValue);
      pstmt.setInt(2, v);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    final int numRows = 2000;
    // insertion in this table should start with maxValue
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, -maxValue, 3, this,true);

    // Now check for the same with BIGINT size
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (tid int, cid bigint not null, "
        + "addr varchar(100), primary key (cid), "
        + "constraint cust_ck check (cid >= 0))");

    stepValue = 2;
    pstmt = conn.prepareStatement(
        "insert into trade.customers (cid, tid) values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(2, v);
      pstmt.setInt(1, v * stepValue);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    assertNull(stmt.getWarnings());

    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, -(maxValue * stepValue), 3,
        this,true);

    stmt.execute("drop table trade.customers");

    // now do the same as above but with persistence and full restart

    stmt.execute("create table trade.customers (tid int, cid int not null, "
        + "primary key (cid), constraint cust_ck check (cid >= 0)) persistent");
    pstmt = conn.prepareStatement("insert into trade.customers values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(1, v * stepValue);
      pstmt.setInt(2, v);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    stmt.execute("alter table trade.customers alter column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    sw = stmt.getWarnings();
    assertNull(sw);

    // insertion in this table should start with maxValue
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, -maxValue, 3, this,true);

    stopVMNums(1, -1, -2, -3);
    restartServerVMNums(new int[] { 1, 2, 3 }, 0, null, null);
    restartClientVMNums(new int[] { 1 }, 0, null, null);

    // further inserts should not cause any problem
    conn = TestUtil.getConnection();
    stmt = conn.createStatement();
    pstmt = conn
        .prepareStatement("insert into trade.customers (tid) values (?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(1, v * stepValue);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // all bets are off now for insertion in this table
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, 0, 3, this,true);

    // Now check for the same with BIGINT size
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (tid int, cid bigint not null, "
        + "addr varchar(100), primary key (cid), "
        + "constraint cust_ck check (cid >= 0)) persistent");

    stepValue = 2;
    pstmt = conn.prepareStatement(
        "insert into trade.customers (cid, tid) values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(2, v);
      pstmt.setInt(1, v * stepValue);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    assertNull(stmt.getWarnings());

    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, 0, 3, this,true);

    stopVMNums(1, -1, -2, -3);
    restartServerVMNums(new int[] { 1, 2, 3 }, 0, null, null);
    restartClientVMNums(new int[] { 1 }, 0, null, null);

    // further inserts should not cause any problem
    conn = TestUtil.getConnection();
    stmt = conn.createStatement();
    pstmt = conn
        .prepareStatement("insert into trade.customers (tid) values (?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(1, v * stepValue);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // all bets are off now for insertion in this table
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, 0, 3, this,true);

    stmt.execute("drop table trade.customers");
  }

  // Test that an ALTER TABLE DROP COLUMN RESTRICT
  // properly undoes the actions if a constraint is defined on the target column
  // This surfaced as bug #46703
  public void testAlterRestrictCKUndo() throws Exception{
    startVMs(1, 3);
    
    serverSQLExecute(1,"create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
        + "price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id),"
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
        + "partition by range (sec_id) ( VALUES BETWEEN 0 AND 409, VALUES BETWEEN 409 AND 1102, VALUES BETWEEN 1102 AND 1251, "
        + "VALUES BETWEEN 1251 AND 1477, VALUES BETWEEN 1477 AND 1700, VALUES BETWEEN 1700 AND 10000)");
    try {
      // DROP COLUMN should fail as SEC_UQ unique constraint is using column SYMBOL
      serverSQLExecute(2,"alter table trade.securities drop column symbol restrict");
      fail("alter table drop column restrict should have failed w/sqlstate X0Y25");
      }
    catch(RMIException ex) {
      if (ex.getCause() instanceof SQLException) {
        SQLException sqlEx = (SQLException)ex.getCause();
        if(!"X0Y25".equals(sqlEx.getSQLState())) {
          throw ex;
        }
      }
      else {
        throw ex;
      }
    }
    
    // Drop the unique constraint stopping the drop column
    serverSQLExecute(3,"alter table trade.securities drop unique SEC_UQ");
    // Now drop the column for real - this should move the check constraint column info
    serverSQLExecute(2,"alter table trade.securities drop column symbol restrict");
    // Add back the column and constraint
    serverSQLExecute(1,"alter table trade.securities add column symbol varchar(10)");
    serverSQLExecute(3,"alter table trade.securities add constraint sec_uq unique (symbol,exchange)");
    
    // Now try an UPDATE. Previously, this failed complaining that column EXCHANGE did not exist
    // because the first DROP COLUMN mangled the referencedColumns of the check constraint, and 
    // this did not get undone by the rollback of that statement, when constraint SEC_UQ was added
    // back, the referencedColumns pointed to a nonexistent column id
    serverSQLExecute(1,"update trade.securities set price = 5.0 where sec_id = 5 and tid=5");
  }

  public void testAlterTableMetaDataChangeReplay_44280() throws Exception {
    startVMs(1, 1);

    serverSQLExecute(1, "create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int) REPLICATE "
        + "EVICTION BY LRUCOUNT 20 EVICTACTION OVERFLOW");

    serverSQLExecute(1, "create table trade.customers2 "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int) REPLICATE "
        + "EVICTION BY LRUCOUNT 20 EVICTACTION OVERFLOW PERSISTENT");

    serverSQLExecute(1, "create table trade.customers3 "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int) REDUNDANCY 1 EVICTION BY "
        + "LRUCOUNT 20 EVICTACTION OVERFLOW PERSISTENT");

    serverSQLExecute(1, "create table trade.customers4 "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int) REPLICATE PERSISTENT");

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();
    clientSQLExecute(1, "alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    serverSQLExecute(1, "alter table trade.customers2 "
        + "add constraint cust_pk2 primary key (cid)");
    clientSQLExecute(1, "alter table trade.customers3 "
        + "add constraint cust_pk3 primary key (cid)");
    serverSQLExecute(1, "alter table trade.customers4 "
        + "add constraint cust_pk4 primary key (cid)");

    PreparedStatement pstmt = conn
        .prepareStatement("insert into trade.customers values (?, ?, ?, ?)");
    PreparedStatement pstmt2 = conn
        .prepareStatement("insert into trade.customers2 values (?, ?, ?, ?)");
    PreparedStatement pstmt3 = conn
        .prepareStatement("insert into trade.customers3 values (?, ?, ?, ?)");
    PreparedStatement pstmt4 = conn
        .prepareStatement("insert into trade.customers4 values (?, ?, ?, ?)");

    // start a couple of VMs while doing a bunch of inserts
    // below may fail now with XBM09 after workaround for #47405 in r40198
    // the for() loop below is to handle that case
    AsyncVM async2 = invokeStartServerVM(1, 0, null, null);
    AsyncVM async3 = invokeStartServerVM(2, 0, null, null);
    for (int i = 1; i <= 5000; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "name" + i);
      pstmt.setString(3, "addr" + i);
      pstmt.setInt(4, i + 10);
      assertEquals(1, pstmt.executeUpdate());
      pstmt2.setInt(1, i);
      pstmt2.setString(2, "name" + i);
      pstmt2.setString(3, "addr" + i);
      pstmt2.setInt(4, i + 10);
      assertEquals(1, pstmt2.executeUpdate());
      pstmt3.setInt(1, i);
      pstmt3.setString(2, "name" + i);
      pstmt3.setString(3, "addr" + i);
      pstmt3.setInt(4, i + 10);
      assertEquals(1, pstmt3.executeUpdate());
      pstmt4.setInt(1, i);
      pstmt4.setString(2, "name" + i);
      pstmt4.setString(3, "addr" + i);
      pstmt4.setInt(4, i + 10);
      assertEquals(1, pstmt4.executeUpdate());
    }
    joinVMCheckXBM09(async2);
    joinVMCheckXBM09(async3);

    // now stop the first VM and check the primary key is properly
    // established in the restarted VMs
    // rebalance buckets to ensure no loss of data
    stmt.execute("call sys.rebalance_all_buckets()");
    stopVMNum(-2);
    for (int i = 1; i <= 500; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "name" + i);
      pstmt.setString(3, "addr" + i);
      pstmt.setInt(4, i + 10);
      try {
        pstmt.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt2.setInt(1, i);
      pstmt2.setString(2, "name" + i);
      pstmt2.setString(3, "addr" + i);
      pstmt2.setInt(4, i + 10);
      try {
        pstmt2.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt3.setInt(1, i);
      pstmt3.setString(2, "name" + i);
      pstmt3.setString(3, "addr" + i);
      pstmt3.setInt(4, i + 10);
      try {
        pstmt3.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt4.setInt(1, i);
      pstmt4.setString(2, "name" + i);
      pstmt4.setString(3, "addr" + i);
      pstmt4.setInt(4, i + 10);
      try {
        pstmt4.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }

    for (int i = 4500; i <= 5000; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "name" + i);
      pstmt.setString(3, "addr" + i);
      pstmt.setInt(4, i + 10);
      try {
        pstmt.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt2.setInt(1, i);
      pstmt2.setString(2, "name" + i);
      pstmt2.setString(3, "addr" + i);
      pstmt2.setInt(4, i + 10);
      try {
        pstmt2.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt3.setInt(1, i);
      pstmt3.setString(2, "name" + i);
      pstmt3.setString(3, "addr" + i);
      pstmt3.setInt(4, i + 10);
      try {
        pstmt3.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt4.setInt(1, i);
      pstmt4.setString(2, "name" + i);
      pstmt4.setString(3, "addr" + i);
      pstmt4.setInt(4, i + 10);
      try {
        pstmt4.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }

    // start another VM and check on it again
    startServerVMs(1, 0, null, null);
    for (int i = 11000; i <= 12000; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "name" + i);
      pstmt.setString(3, "addr" + i);
      pstmt.setInt(4, i + 10);
      assertEquals(1, pstmt.executeUpdate());
      pstmt2.setInt(1, i);
      pstmt2.setString(2, "name" + i);
      pstmt2.setString(3, "addr" + i);
      pstmt2.setInt(4, i + 10);
      assertEquals(1, pstmt2.executeUpdate());
      pstmt3.setInt(1, i);
      pstmt3.setString(2, "name" + i);
      pstmt3.setString(3, "addr" + i);
      pstmt3.setInt(4, i + 10);
      assertEquals(1, pstmt3.executeUpdate());
      pstmt4.setInt(1, i);
      pstmt4.setString(2, "name" + i);
      pstmt4.setString(3, "addr" + i);
      pstmt4.setInt(4, i + 10);
      assertEquals(1, pstmt4.executeUpdate());
    }
    for (int i = 3000; i <= 3100; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "name" + i);
      pstmt.setString(3, "addr" + i);
      pstmt.setInt(4, i + 10);
      try {
        pstmt.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt2.setInt(1, i);
      pstmt2.setString(2, "name" + i);
      pstmt2.setString(3, "addr" + i);
      pstmt2.setInt(4, i + 10);
      try {
        pstmt2.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt3.setInt(1, i);
      pstmt3.setString(2, "name" + i);
      pstmt3.setString(3, "addr" + i);
      pstmt3.setInt(4, i + 10);
      try {
        pstmt3.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt4.setInt(1, i);
      pstmt4.setString(2, "name" + i);
      pstmt4.setString(3, "addr" + i);
      pstmt4.setInt(4, i + 10);
      try {
        pstmt4.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }
    for (int i = 11500; i <= 11600; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "name" + i);
      pstmt.setString(3, "addr" + i);
      pstmt.setInt(4, i + 10);
      try {
        pstmt.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt2.setInt(1, i);
      pstmt2.setString(2, "name" + i);
      pstmt2.setString(3, "addr" + i);
      pstmt2.setInt(4, i + 10);
      try {
        pstmt2.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt3.setInt(1, i);
      pstmt3.setString(2, "name" + i);
      pstmt3.setString(3, "addr" + i);
      pstmt3.setInt(4, i + 10);
      try {
        pstmt3.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      pstmt4.setInt(1, i);
      pstmt4.setString(2, "name" + i);
      pstmt4.setString(3, "addr" + i);
      pstmt4.setInt(4, i + 10);
      try {
        pstmt4.execute();
        fail("expected a PK constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }
  }

  private void joinVMCheckXBM09(AsyncVM async) throws Exception {
    try {
      joinVM(true, async);
    } catch (RMIException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof SQLException
          && "XBM09".equals(((SQLException)cause).getSQLState())) {
        // ignore and start the failed VM synchronously
        startServerVMs(1, 0, null);
      }
      else {
        throw ex;
      }
    }
  }

  public void testAlterTableDependentTables_46573() throws Exception {
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties props = new Properties();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    startVMs(0, 1, 0, null, props);
    props.clear();
    props.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 1, 0, null, props);

    serverSQLExecute(1, "CREATE TABLE TPCEGFXD.ADDRESS"
        + "(AD_ID DECIMAL(11,0) NOT NULL,"
        + "AD_LINE1 VARCHAR(80),"
        + "AD_LINE2 VARCHAR(80),"
        + "AD_ZC_CODE CHAR(12) NOT NULL,"
        + "AD_CTRY VARCHAR(80),"
        + "PRIMARY KEY (AD_ID)) PERSISTENT");
    // this one is to test default schema, colocated tables in ALTER TABLE
    serverSQLExecute(2, "CREATE TABLE ADDRESS2"
        + "(AD_ID DECIMAL(11,0) NOT NULL,"
        + "AD_LINE1 VARCHAR(80),"
        + "AD_LINE2 VARCHAR(80),"
        + "AD_ZC_CODE CHAR(12) NOT NULL,"
        + "AD_CTRY VARCHAR(80),"
        + "PRIMARY KEY (AD_ID)) PARTITION BY COLUMN (AD_ZC_CODE) PERSISTENT");
    serverSQLExecute(1, "CREATE TABLE ADDRESS3"
        + "(AD_ID DECIMAL(11,0) NOT NULL,"
        + "AD_LINE1 VARCHAR(80),"
        + "AD_LINE2 VARCHAR(80),"
        + "AD_ZC_CODE CHAR(12) NOT NULL,"
        + "AD_CTRY VARCHAR(80),"
        + "PRIMARY KEY (AD_ID)) PARTITION BY COLUMN (AD_ZC_CODE) PERSISTENT");

    serverSQLExecute(2, "CREATE INDEX ADDRESS_I_FK_ADDRESS_ZC "
        + "ON TPCEGFXD.ADDRESS (AD_ZC_CODE)");
    serverSQLExecute(1, "CREATE INDEX ADDRESS2_I_FK_ADDRESS_ZC "
        + "ON ADDRESS2 (AD_ZC_CODE)");
    serverSQLExecute(1, "CREATE INDEX ADDRESS3_I_FK_ADDRESS_ZC "
        + "ON ADDRESS3 (AD_ZC_CODE)");

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();
    PreparedStatement pstmt = conn.prepareStatement("insert into " +
    		"TPCEGFXD.ADDRESS values (?, ?, ?, ?, ?)");
    PreparedStatement pstmt2 = conn.prepareStatement("insert into " +
        "ADDRESS2 values (?, ?, ?, ?, ?)");
    PreparedStatement pstmt3 = conn.prepareStatement("insert into " +
        "ADDRESS3 values (?, ?, ?, ?, ?)");
    for (int i = 1; i <= 100; i++) {
      pstmt.setString(1, Integer.toString(i * 6));
      pstmt.setString(2, "ADDR1_" + i);
      pstmt.setString(3, "ADDR2_" + i);
      pstmt.setString(4, "ZP" + (i / 2));
      pstmt.setString(5, "CT" + i);
      pstmt.execute();
      pstmt2.setString(1, Integer.toString(i * 6));
      pstmt2.setString(2, "ADDR1_" + i);
      pstmt2.setString(3, "ADDR2_" + i);
      pstmt2.setString(4, "ZP" + (i / 2));
      pstmt2.setString(5, "CT" + i);
      pstmt2.execute();
      pstmt3.setString(1, Integer.toString(i * 6));
      pstmt3.setString(2, "ADDR1_" + i);
      pstmt3.setString(3, "ADDR2_" + i);
      pstmt3.setString(4, "ZP" + (i / 2));
      pstmt3.setString(5, "CT" + i);
      pstmt3.execute();
    }

    serverSQLExecute(2, "CREATE TABLE TPCEGFXD.ZIP_CODE"
        + "(ZC_CODE CHAR(12) NOT NULL,"
        + "ZC_TOWN VARCHAR(80) NOT NULL,"
        + "ZC_DIV VARCHAR(80) NOT NULL,"
        + "PRIMARY KEY (ZC_CODE)) PERSISTENT");
    serverSQLExecute(1, "CREATE TABLE ZIP_CODE2"
        + "(ZC_CODE CHAR(12) NOT NULL,"
        + "ZC_TOWN VARCHAR(80) NOT NULL,"
        + "ZC_DIV VARCHAR(80) NOT NULL,"
        + "PRIMARY KEY (ZC_CODE)) PARTITION BY PRIMARY KEY "
        + "PERSISTENT");
    // TODO: SW: COLOCATE WITH hangs because each VM tries to load the entire
    // index while holding lock on GFXDINDEXMANAGER; next one tests
    // COLOCATED WITH but without an FK addition
        //+ "COLOCATE WITH (ADDRESS2) PERSISTENT");
    serverSQLExecute(1, "CREATE TABLE ZIP_CODE3"
        + "(ZC_CODE CHAR(12) NOT NULL,"
        + "ZC_TOWN VARCHAR(80) NOT NULL,"
        + "ZC_DIV VARCHAR(80) NOT NULL,"
        + "PRIMARY KEY (ZC_CODE)) PARTITION BY PRIMARY KEY "
        + "COLOCATE WITH (ADDRESS3) PERSISTENT");

    PreparedStatement pstmt4 = conn.prepareStatement("insert into "
        + "TPCEGFXD.ZIP_CODE values (?, ?, ?)");
    PreparedStatement pstmt5 = conn.prepareStatement("insert into "
        + "ZIP_CODE2 values (?, ?, ?)");
    PreparedStatement pstmt6 = conn.prepareStatement("insert into "
        + "ZIP_CODE3 values (?, ?, ?)");

    for (int i = 1; i <= 50; i++) {
      pstmt4.setString(1, "ZP" + i);
      pstmt4.setString(2, "TW" + i);
      pstmt4.setString(3, "DV" + i);
      pstmt4.execute();
      pstmt5.setString(1, "ZP" + i);
      pstmt5.setString(2, "TW" + i);
      pstmt5.setString(3, "DV" + i);
      pstmt5.execute();
      pstmt6.setString(1, "ZP" + i);
      pstmt6.setString(2, "TW" + i);
      pstmt6.setString(3, "DV" + i);
      pstmt6.execute();
    }

    // first an unsatisfied FK constraint
    final String alterTable = "ALTER TABLE TPCEGFXD.ADDRESS "
        + "ADD CONSTRAINT FK_ADDRESS_ZC FOREIGN KEY (AD_ZC_CODE) "
        + "REFERENCES TPCEGFXD.ZIP_CODE (ZC_CODE) ON DELETE RESTRICT";
    final String alterTable2 = "ALTER TABLE ADDRESS2 "
        + "ADD CONSTRAINT FK_ADDRESS2_ZC FOREIGN KEY (AD_ZC_CODE) "
        + "REFERENCES ZIP_CODE2 (ZC_CODE) ON DELETE RESTRICT";
    final String alterTable3 = "ALTER TABLE ADDRESS3 "
        + "DROP COLUMN AD_CTRY CASCADE";
    final String alterTable4 = "ALTER TABLE ADDRESS3 "
        + "ADD COLUMN AD_CTRY INT";
    try {
      stmt.execute(alterTable);
      fail("expected constraint violation");
    } catch (SQLException sqle) {
      if (!"X0Y45".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute(alterTable2);
      fail("expected constraint violation");
    } catch (SQLException sqle) {
      if (!"X0Y45".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // next with proper FK constraint
    stmt.execute("delete from TPCEGFXD.ADDRESS where AD_ZC_CODE='ZP0'");
    stmt.execute("delete from ADDRESS2 where AD_ZC_CODE='ZP0'");
    stmt.execute(alterTable);
    stmt.execute(alterTable2);
    stmt.execute(alterTable3);
    stmt.execute(alterTable4);

    // next check success in replay and full restart
    startVMs(0, 1, 0, null, props);

    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from TPCEGFXD.ADDRESS", null, "99");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from TPCEGFXD.ZIP_CODE", null, "50");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ADDRESS2", null, "99");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ADDRESS3", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(AD_CTRY) from ADDRESS3", null, "0");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ZIP_CODE2", null, "50");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ZIP_CODE3", null, "50");

    // finally restart everything and try again
    stopVMNums(-1, -2, -3, 1);

    // restart non-persistent VM last
    props.clear();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    AsyncVM async1 = restartServerVMAsync(1, 0, null, props);
    Thread.sleep(3000);
    props.clear();
    props.setProperty("locators", "localhost[" + locPort + ']');
    AsyncVM async2 = restartServerVMAsync(2, 0, null, props);
    AsyncVM async3 = restartServerVMAsync(3, 0, null, props);
    joinVMs(false, async1, async2, async3);
    restartVMNums(new int[] { 1 }, 0, null, props);

    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from TPCEGFXD.ADDRESS", null, "99");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from TPCEGFXD.ZIP_CODE", null, "50");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ADDRESS2", null, "99");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ADDRESS3", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(AD_CTRY) from ADDRESS3", null, "0");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct AD_CTRY from ADDRESS3", null, "null");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ZIP_CODE2", null, "50");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ZIP_CODE3", null, "50");

    // fire an update on new column and try again
    stmt = TestUtil.getStatement();
    assertEquals(50, stmt.executeUpdate("UPDATE ADDRESS3 SET AD_CTRY=20 "
        + "WHERE AD_ID > 300"));

    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(*) from ADDRESS3", null, "100");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select count(AD_CTRY) from ADDRESS3", null, "50");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct AD_CTRY from ADDRESS3 WHERE AD_ID > 300", null, "20");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct AD_CTRY from ADDRESS3 WHERE AD_ID <= 300", null,
        "null");
  }
  
  /**
   * After alter table, shutdown 1 server and bring it up.
   * defect #49728
   */
  public void testAlterExpireEntryTimeToLive() throws Exception {
    //start 1 client and 2 server vms
    startVMs(1, 2);

    //create table with entry expire TTL
    clientSQLExecute(1, " CREATE TABLE testtable "
    	+ "(id int, firstName varchar(20), lastName varchar(20), primary key (id)) " 
    	+ "PARTITION BY PRIMARY KEY EXPIRE ENTRY WITH TIMETOLIVE 60 ACTION DESTROY");
        
    //alter table set expire entry timetolive
    clientSQLExecute(1, "ALTER TABLE testtable "
    	+ "set EXPIRE ENTRY WITH TIMETOLIVE 300 ACTION DESTROY");
    
    //shutdown server 1 
    stopVMNums(-1);
    
    //restart server 1
    restartVMNums(-1);
  }
  
  /**
   * After 'alter table', shutdown both servers and bring them up.
   */
  public void testAlterExpireEntryTimeToLive_2() throws Exception {
    //start 1 client and 2 server vms
    startVMs(1, 2);

    //create table with entry expire TTL
    clientSQLExecute(1, " CREATE TABLE testtable "
        + "(id int, firstName varchar(20), lastName varchar(20), primary key (id)) " 
        + "PARTITION BY PRIMARY KEY EXPIRE ENTRY WITH TIMETOLIVE 60 ACTION DESTROY");
        
    //alter table set expire entry timetolive
    clientSQLExecute(1, "ALTER TABLE testtable "
        + "set EXPIRE ENTRY WITH TIMETOLIVE 300 ACTION DESTROY");
    
    //shutdown server 1 & 2
    stopVMNums(-1, -2);
    
    //restart server 1 & 2
    restartVMNums(-1, -2);
  }
  
  public void testAlterEvictionMaxSize() throws Exception {
    //start 1 client and 2 server vms
    startVMs(1, 2);

    //create table with entry expire TTL
    clientSQLExecute(1, " CREATE TABLE testtable "
        + "(id int, firstName varchar(20), lastName varchar(20), primary key (id)) " 
        + "PARTITION BY PRIMARY KEY EVICTION BY LRUMEMSIZE 5 EVICTACTION OVERFLOW");
        
    //alter table set expire entry timetolive
    clientSQLExecute(1, "ALTER TABLE testtable "
        + "set EVICTION MAXSIZE 10");
    
    //shutdown server 1 
    stopVMNums(-1);
    
    restartVMNums(-1);
  }
  
  public void testAddGeneratedIdentityColumn_49851() throws Exception {
    // starting servers first to give them lesser VMIds than the client
    startServerVMs(3, 0, null, null);
    startClientVMs(1, 0, null, null);

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size added using ALTER TABLE
    stmt.execute("create table trade.customers (tid int, cid int not null, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");
    // first some inserts with gaps
    final int maxValue = 1000000;
    int stepValue = 3;
    PreparedStatement pstmt = conn
        .prepareStatement("insert into trade.customers values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(1, v * stepValue);
      pstmt.setInt(2, v);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    final int numRows = 2000;
    // insertion in this table should start with maxValue
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, -maxValue, 3, this,true);    

    stmt.execute("drop table trade.customers");
  }

  public void testBug50116() throws Exception {
  
    // start a client and server for adding constraints
    startVMs(1, 1);
  
    {
      final Properties props = new Properties();
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("create schema trade");
  
      // Create table
      st.execute("create table trade.portfolio (oid int not null, cid int, sid int, tid int, constraint portfolio_pk primary key (cid, sid)) replicate");
      st.execute("create table trade.sellorders (oid int not null constraint sellorders_pk primary key, cid int, sid int, constraint portf_fk foreign key (cid, sid) references trade.portfolio (cid, sid) on delete restrict, tid int)  replicate");
      st.execute("insert into trade.portfolio values (30283943, 1928331, 1928331, 0)");
      st.execute("insert into trade.portfolio values (11905633, 5617222, 5617222, 0)");
      st.execute("insert into trade.portfolio values (49839007, 8315666, 8315666, 0)");
      st.execute("insert into trade.portfolio values (52380433, 1261892, 1261892, 0)");
      st.execute("insert into trade.portfolio values (47088533, 7536715, 6536715, 0)");
      st.execute("insert into trade.portfolio values (77004261, 6937081, 6937081, 0)");
      st.execute("insert into trade.portfolio values (79722922, 9489519, 9489519, 0)");
      st.execute("insert into trade.portfolio values (14562576, 6404345, 6404345, 0)");
      st.execute("insert into trade.portfolio values (46568823, 2482798, 2482798, 0)");
      st.execute("insert into trade.portfolio values (29198498, 4508782, 4508782, 0)");
  
      st.execute("insert into trade.sellorders values (30283943, 1928331, 1928331, 0)");
      st.execute("insert into trade.sellorders values (11905633, 5617222, 5617222, 0)");
      st.execute("insert into trade.sellorders values (49839007, 8315666, 8315666, 0)");
      st.execute("insert into trade.sellorders values (52380433, 1261892, 1261892, 0)");
      st.execute("insert into trade.sellorders values (47088533, 7536715, 6536715, 0)");
      st.execute("insert into trade.sellorders values (77004261, 6937081, 6937081, 0)");
      st.execute("insert into trade.sellorders values (79722922, 9489519, 9489519, 0)");
      st.execute("insert into trade.sellorders values (14562576, 6404345, 6404345, 0)");
      st.execute("insert into trade.sellorders values (46568823, 2482798, 2482798, 0)");
      st.execute("insert into trade.sellorders values (29198498, 4508782, 4508782, 0)");
    }
  
    {
      final Properties props = new Properties();
      Connection conn = TestUtil.getConnection(props);
      String query = " select tid  from trade.portfolio";
      PreparedStatement st = conn.prepareStatement(query);
      java.sql.ResultSet r = st.executeQuery();
      int count = 0;
      while (r.next()) {
        assertEquals(0, r.getInt(1));
        count++;
      }
      assertEquals(10, count);
      r.close();
    }
  
    {
      final Properties props = new Properties();
      Connection conn = TestUtil.getConnection(props);
      String query = " select tid  from trade.sellorders";
      PreparedStatement st = conn.prepareStatement(query);
      java.sql.ResultSet r = st.executeQuery();
      int count = 0;
      while (r.next()) {
        assertEquals(0, r.getInt(1));
        count++;
      }
      assertEquals(10, count);
      r.close();
    }
  
    {
      final Properties props = new Properties();
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("alter table trade.sellorders drop FOREIGN KEY portf_fk");
    }
  
    startVMs(0, 1);
  
    {
      final Properties props = new Properties();
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("drop table if exists trade.portfolio");
    }
  
    try {
      final Properties props = new Properties();
      Connection conn = TestUtil.getConnection(props);
      String query = " select tid  from trade.portfolio";
      PreparedStatement st = conn.prepareStatement(query);
      java.sql.ResultSet r = st.executeQuery();
      int count = 0;
      while (r.next()) {
        assertEquals(0, r.getInt(1));
        count++;
      }
      assertEquals(10, count);
      r.close();
      fail("Test should fail with Syntax Error exception");
    } catch (SQLException sqle) {
      assertEquals("42X05", sqle.getSQLState());
    }
  
    startVMs(0, 1);
  
    {
      final Properties props = new Properties();
      Connection conn = TestUtil.getConnection(props);
      String query = " select tid  from trade.sellorders";
      PreparedStatement st = conn.prepareStatement(query);
      java.sql.ResultSet r = st.executeQuery();
      int count = 0;
      while (r.next()) {
        assertEquals(0, r.getInt(1));
        count++;
      }
      assertEquals(10, count);
      r.close();
    }
  }
}
