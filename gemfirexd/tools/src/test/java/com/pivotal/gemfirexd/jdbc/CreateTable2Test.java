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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
package com.pivotal.gemfirexd.jdbc;

import java.sql.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionAttributesImpl;
import com.gemstone.gnu.trove.TLongHashSet;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdRangePartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GfxdObjectSizer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import io.snappydata.test.dunit.VM;
import org.apache.derbyTesting.functionTests.tests.derbynet.SqlExceptionTest;

public class CreateTable2Test extends JdbcTestBase {

  public CreateTable2Test(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  public void testCreateTableWithCascadeDeleteRule() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table trade.customers (cid int not null, cust_name varchar(100), " +
        "since date, addr varchar(100)," +
        "tid int, primary key (cid))");
    try {

      Monitor.getStream().println(
          "<ExpectedException action=add>"
              + "java.sql.SQLFeatureNotSupportedException"
              + "</ExpectedException>");
      Monitor.getStream().flush();

      Monitor.getStream().println("<ExpectedException action=add>"
          + "java.sql.SQLException"
          + "</ExpectedException>");
      Monitor.getStream().flush();
      s.execute("create table trade.networth (cid int not null, cash decimal (30, 20), " +
          "securities decimal (30, 20), loanlimit int, " +
          "availloan decimal (30, 20),  tid int," +
          "constraint netw_pk primary key (cid), " +
          "constraint cust_newt_fk foreign key (cid) references trade.customers (cid) on delete CASCADE, " +
          "constraint cash_ch check (cash>=0), " +
          "constraint sec_ch check (securities >=0)," +
          "constraint availloan_ck check (loanlimit>=availloan and availloan >=0))");
      fail("Exception is expected!");
    } catch (Exception e) {
      Monitor.getStream().println("<ExpectedException action=remove>"
          + "java.sql.SQLException"
          + "</ExpectedException>");
      Monitor.getStream().flush();

      Monitor.getStream().println(
          "<ExpectedException action=remove>"
              + "java.sql.SQLFeatureNotSupportedException"
              + "</ExpectedException>");
      Monitor.getStream().flush();

    }
    s.execute("create table trade.networth (cid int not null, cash decimal (30, 20), " +
        "securities decimal (30, 20), loanlimit int, " +
        "availloan decimal (30, 20),  tid int," +
        "constraint netw_pk primary key (cid), " +
        "constraint cust_newt_fk foreign key (cid) references trade.customers (cid) on delete RESTRICT, " +
        "constraint cash_ch check (cash>=0), " +
        "constraint sec_ch check (securities >=0)," +
        "constraint availloan_ck check (loanlimit>=availloan and availloan >=0))");

  }

  public void testCompatibleColocations1() throws Exception {
    setupConnection();
    boolean gotException = false;
    sqlExecute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid))   partition by range (cid) "
        + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, "
        + "VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, "
        + "VALUES BETWEEN 1678 AND 10000)", false);
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by column (cid) colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by primary key colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 20 ),"
              + " VALUES (50, 60), VALUES (12, 34, 45)) colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid))   partition by range (cid) "
              + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 901, " // change here in end value
              + "VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, "
              + "VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid))   partition by range (cid) "
              + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, "
              + "VALUES BETWEEN 1255 AND 1678, " // change here -- range VALUES BETWEEN 902 AND 1255 missing
              + "VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    // Now check that a proper case is ok
    try {
      sqlExecute("create table trade.customers2 (cid int not null, "
          + "cust_name varchar(100), since date, addr varchar(100), "
          + "tid int, primary key (cid))   partition by range (cid) "
          + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, "
          + "VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, "
          + "VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    } catch (SQLException ex) {
      gotException = true;
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    assertFalse(gotException);
  }

  public void testCompatibleColocations2() throws Exception {
    setupConnection();
    boolean gotException = false;
    sqlExecute("create table trade.customers (cid int not null, "
            + "cust_name varchar(100), since date, addr varchar(100), "
            + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 20 ),"
            + " VALUES (50, 60), VALUES (12, 34, 45))",
        false);
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by column (cid) colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by primary key colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 30 )," // a bit different value in this list
              + " VALUES (50, 60), VALUES (12, 34, 45)) colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 30 ),"
              + " VALUES (50, 60), VALUES (12, 34, 45), VALUES (300, 200)) " // // one extra list of values
              + "colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid))   partition by range (cid) "
              + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, "
              + "VALUES BETWEEN 1255 AND 1678, VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    // Now check that a proper case is ok
    try {
      sqlExecute("create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 20 ),"
              + " VALUES (50, 60), VALUES (12, 34, 45)) colocate with (trade.customers)",
          false);
    } catch (SQLException ex) {
      gotException = true;
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    assertFalse(gotException);
  }

  public void testBug41004() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("create table ctstable1 (TYPE_ID int, TYPE_DESC varchar(32), "
        + "primary key(TYPE_ID))");
    stmt.execute("create table ctstable2 (KEY_ID int, COF_NAME varchar(32), "
        + "PRICE float, TYPE_ID int, primary key(KEY_ID), "
        + "foreign key(TYPE_ID) references ctstable1)");

    // check the index types and colocation
    final String schemaName = getCurrentDefaultSchemaName();
    AlterTableTest.checkDefaultPartitioning(schemaName + ".ctstable1",
        "TYPE_ID");
    AlterTableTest.checkDefaultPartitioning(schemaName + ".CTSTABLE2",
        "TYPE_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable1", "LOCALHASH1",
        "TYPE_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable2", "GLOBALHASH",
        "KEY_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable2", "LOCALSORTEDMAP",
        "TYPE_ID");
    AlterTableTest.checkColocation(schemaName + ".ctstable2", schemaName,
        "ctstable1");

    stmt.execute("insert into ctstable1 values (5, 'Type1')");
    stmt.execute("insert into ctstable2 values (10, 'COF1', 20.0, 5)");

    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    ResultSet rs = stmt.executeQuery("select * from ctstable1");
    assertTrue(rs.next());
    assertEquals(5, rs.getInt("TYPE_ID"));
    assertEquals(Integer.valueOf(5), rs.getObject(1));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select TYPE_ID from ctstable2");
    assertTrue(rs.next());
    assertEquals(Integer.valueOf(5), rs.getObject("TYPE_ID"));
    assertEquals(Integer.valueOf(5), rs.getObject(1));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.TYPE_ID = t2.TYPE_ID");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.TYPE_ID = t2.TYPE_ID "
        + "and t2.TYPE_ID > 2");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();
  }

  public void testBug40608() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema trade");
    stmt.execute("create table trade.customers (cid int not null, cash "
        + "decimal (30, 20), loanlimit int, tid int, constraint cust_pk "
        + "primary key (cid)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow synchronous");
    Region<?, ?> r1 = CacheFactory.getAnyInstance().getRegion(
        "/TRADE/CUSTOMERS");
    RegionAttributes<?, ?> rattrs1 = r1.getAttributes();
    EvictionAttributes eattrs1 = rattrs1.getEvictionAttributes();
    ObjectSizer os1 = eattrs1.getObjectSizer();

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), "
        + "loanlimit int, availloan decimal (30, 20),  tid int, "
        + "constraint netw_pk primary key (cid), "
        + "constraint cust_newt_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict, constraint cash_ch "
        + "check (cash>=0), constraint sec_ch check (securities >=0), "
        + "constraint availloan_ck check (loanlimit>=availloan and "
        + "availloan >=0)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow synchronous");

    Region<?, ?> r2 = CacheFactory.getAnyInstance()
        .getRegion("/TRADE/NETWORTH");
    RegionAttributes<?, ?> rattrs2 = r2.getAttributes();
    EvictionAttributes eattrs2 = rattrs2.getEvictionAttributes();
    assertTrue(eattrs2.getAction().isOverflowToDisk());
    assertTrue(eattrs2.getAlgorithm().isLRUHeap());
    ObjectSizer os2 = eattrs2.getObjectSizer();
    assertTrue(os2 == os1);
  }

  public void testSYSTABLES() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema trade");
    stmt.execute("create table trade.customers (cid int not null, cash "
        + "decimal (30, 20), loanlimit int, tid int, constraint cust_pk "
        + "primary key (cid)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow");
    Region<?, ?> r1 = CacheFactory.getAnyInstance().getRegion(
        "/TRADE/CUSTOMERS");
    RegionAttributes<?, ?> rattrs1 = r1.getAttributes();
    EvictionAttributes eattrs1 = rattrs1.getEvictionAttributes();
    ObjectSizer os1 = eattrs1.getObjectSizer();

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), "
        + "loanlimit int, availloan decimal (30, 20),  tid int, "
        + "constraint netw_pk primary key (cid), "
        + "constraint cust_newt_fk foreign key (tid) references "
        + "trade.customers (cid) on delete restrict, constraint cash_ch "
        + "check (cash>=0), constraint sec_ch check (securities >=0), "
        + "constraint availloan_ck check (loanlimit>=availloan and "
        + "availloan >=0)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow synchronous");

    Region<?, ?> r2 = CacheFactory.getAnyInstance()
        .getRegion("/TRADE/NETWORTH");
    RegionAttributes<?, ?> rattrs2 = r2.getAttributes();
    EvictionAttributes eattrs2 = rattrs2.getEvictionAttributes();
    assertTrue(eattrs2.getAction().isOverflowToDisk());
    assertTrue(eattrs2.getAlgorithm().isLRUHeap());
    ObjectSizer os2 = eattrs2.getObjectSizer();
    assertTrue(os2 == os1);

    ResultSet rs = stmt.executeQuery("select * from sys.systables");
    checkTableProperties(rs);
    rs = stmt.executeQuery("select * from sys.systables where "
        + "tableschemaname='" + getCurrentDefaultSchemaName() + "'");
    checkTableProperties(rs);
    rs.close();
  }

  // [sjigyasu] This test is valid only with TX isolation level NONE and autocommit off.
  // We should review this test when we support savepoints
  public void testBug41711() throws SQLException {
    Connection conn = getConnection();
    conn.setAutoCommit(false);
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    Statement stmt = conn.createStatement();
    stmt.execute("create schema trade");
    stmt.execute("create table trade.customers (cid int not null, tid int, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");

    stmt.execute("insert into trade.customers values (1, 1)");
    stmt.execute("insert into trade.customers values (2, 2)");

    final ResultSet rs = stmt.executeQuery("select tableschemaname, tablename "
        + "from sys.systables where tabletype = 'T' order by tablename");
    int id = -1;
    while (rs.next()) {
      try {
        conn.createStatement().execute(
            "insert into trade.customers values (" + id + ", " + id + ')');
        fail("expected check constraint violation");
      } catch (SQLException ex) {
        if (!"23513".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      --id;
    }
    rs.close();
  }

  // fk on unique key columns can be created now.
  public void DEBUGtestFKOnUniqueCols() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema music");
    stmt.execute("CREATE TABLE music.genre "
        + "(fakepk int not null primary key, genre_name char(40) NOT NULL, "
        + "CONSTRAINT genre_uq UNIQUE (genre_name))");
    boolean gotexception = false;
    try {
      stmt.execute(" CREATE TABLE music.tracks "
          + "(album_id int NOT NULL, GEnre_name char(40) NOT NULL, "
          + "CONSTRAINT tracks_pk PRIMARY KEY (album_id), "
          + "CONSTRAINT tracks_genre_fk FOREIGN KEY (genre_name) REFERENCES "
          + "music.genre (genre_name) ON DELETE NO ACTION ON UPDATE NO ACTION)");
    } catch (SQLException ex) {
      gotexception = true;
      assertTrue("0A000".equals(ex.getSQLState()));
    }
    assertTrue(gotexception);
  }

  //FIXME GemFireXD does not yet support START WITH/INCREMENT BY on GENERATED ALWAYS
  //public void testIdentityGeneratedAlways() throws Exception {
  public void _testIdentityGeneratedAlways() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size
    // add expected warning
    addExpectedException(SQLWarning.class);
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED ALWAYS AS IDENTITY (START WITH 8, INCREMENT BY 1), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    removeExpectedException(SQLWarning.class);
    // expect warning for the explicit INCREMENT BY specification
    SQLWarning sw = stmt.getWarnings();
    assertNotNull(sw);
    if (!"X0Z12".equals(sw.getSQLState()) || !sw.getMessage().contains("1")) {
      throw sw;
    }
    assertNull(sw.getNextWarning());

    final int numRows = 4000;
    // insertion in this table should start with 8
    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 8, 0, null, true);

    // No warnings for default start and increment by
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED ALWAYS AS IDENTITY, "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect no warning for the default start
    sw = stmt.getWarnings();
    assertNull(sw);

    // Now check for IDENTITY column with BIGINT size
    stmt.execute("drop table trade.customers");
    addExpectedException(SQLWarning.class);
    stmt.execute("create table trade.customers (cid bigint not null "
        + "GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 7), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    removeExpectedException(SQLWarning.class);
    // expect warning for the non-default increment
    sw = stmt.getWarnings();
    assertNotNull(sw);
    if (!"X0Z12".equals(sw.getSQLState()) || !sw.getMessage().contains("7")) {
      throw sw;
    }
    assertNull(sw.getNextWarning());

    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 1, 0, null, true);

    stmt.execute("drop table trade.customers");
  }

  public void testIdentityGeneratedByDefault() throws Exception {
    // reduce logs
    reduceLogLevelForTest("config");

    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED BY DEFAULT AS IDENTITY (START WITH 8, INCREMENT BY 1), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect warning for the explicit INCREMENT BY specification
    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    final int numRows = 4000;
    // insertion in this table should start with 8
    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 8, 0, null, false);

    // No warnings for default start and increment by
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED BY DEFAULT AS IDENTITY, "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect no warning for the default start
    sw = stmt.getWarnings();
    assertNull(sw);

    // Now check for IDENTITY column with BIGINT size
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (cid bigint not null "
        + "GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 7), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect warning for the non-default increment
    sw = stmt.getWarnings();
    assertNull(sw);

    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 1, 0, null, false);

    stmt.execute("drop table trade.customers");
  }

  public void testGeneratedByDefaultStartWithIncrementBy() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute(
        "create table TESTTABLE (ID int unique not null GENERATED by default AS IDENTITY (START WITH 5, increment by 2) ,"
            + " phone int not null)  ");
    // expect warning for the explicit INCREMENT BY specification
    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    conn.createStatement().execute("insert into TESTTABLE (id, phone) values (default,1)");
    conn.createStatement().execute("insert into TESTTABLE (phone) values (1)");
    conn.createStatement().execute("insert into TESTTABLE (id, phone) values (default,1)");
    conn.createStatement().execute("insert into TESTTABLE (phone) values (1)");

    ResultSet rs = conn.createStatement().executeQuery(
        "select max(id) from TESTTABLE ");
    rs.next();
    assertEquals(11, rs.getInt(1));

  }

  public void testIdentityColumnWithWAN() throws Exception {
    Connection conn = getConnection();

    Statement stmt = conn.createStatement();

    try {
      stmt.execute(
          "create table TESTTABLE (ID int unique not null GENERATED ALWAYS AS IDENTITY ,"
              + " phone int not null) gatewaysender (MYSENDER) ");
    } catch (Exception e) {
      if (!e.getMessage().contains("When GatewaySender is attached, only valid types for identity column")) {
        fail("Unexpected exception", e);
      }
    }

  }

  public void testColocationWithBuckets_42952() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0))");

    // check that the tables should be colocated
    checkValidColocation_42952(stmt);

    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");

    // check failure in default colocation when number of buckets do not match

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0)) BUCKETS 13");

    // check that the tables should not be colocated
    Region<Object, Object> custReg = Misc.getRegionForTable("TRADE.CUSTOMERS",
        true);
    Region<Object, Object> netReg = Misc.getRegionForTable("TRADE.NETWORTH",
        true);
    PartitionAttributes<?, ?> custAttrs = custReg.getAttributes()
        .getPartitionAttributes();
    PartitionAttributes<?, ?> netAttrs = netReg.getAttributes()
        .getPartitionAttributes();

    assertEquals(7, custAttrs.getTotalNumBuckets());
    assertEquals(13, netAttrs.getTotalNumBuckets());
    assertNull(netAttrs.getColocatedWith());

    // check the resolver for networth table
    PartitionResolver<?, ?> custResolver = custAttrs.getPartitionResolver();
    PartitionResolver<?, ?> netResolver = netAttrs.getPartitionResolver();
    assertEquals(GfxdRangePartitionResolver.class, custResolver.getClass());
    assertEquals(GfxdPartitionByExpressionResolver.class,
        netResolver.getClass());

    GfxdRangePartitionResolver custRangeResolver =
        (GfxdRangePartitionResolver)custResolver;
    GfxdPartitionByExpressionResolver netColResolver =
        (GfxdPartitionByExpressionResolver)netResolver;
    assertTrue(custRangeResolver.getDDLString().contains("3332"));
    assertTrue(custRangeResolver.getDDLString().contains("8330"));
    assertEquals("PARTITION BY PRIMARY KEY", netColResolver.getDDLString());

    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");

    // check failure in explicit colocation when number of buckets do not match

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");

    addExpectedException(new Object[] { "X0Y94", "X0Y95" });
    try {
      stmt.execute("create table trade.networth (cid int not null, "
          + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
          + "availloan decimal (30, 20), tid int, constraint netw_pk "
          + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
          + "references trade.customers (cid) on delete restrict, "
          + "constraint cash_ch check (cash>=0), constraint sec_ch check "
          + "(securities >=0), constraint availloan_ck check "
          + "(loanlimit>=availloan and availloan >=0)) BUCKETS 13 "
          + "partition by column (cid) colocate with (trade.customers)");
      fail("expected exception in incompatible colocation");
    } catch (SQLException sqle) {
      if (!"X0Y94".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("create table trade.networth (cid int not null, "
          + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
          + "availloan decimal (30, 20), tid int, constraint netw_pk "
          + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
          + "references trade.customers (cid) on delete restrict, "
          + "constraint cash_ch check (cash>=0), constraint sec_ch check "
          + "(securities >=0), constraint availloan_ck check "
          + "(loanlimit>=availloan and availloan >=0)) BUCKETS 13 "
          + "partition by range (cid) (VALUES BETWEEN 0 AND 1666, "
          + "VALUES BETWEEN 1666 AND 3332, VALUES BETWEEN 3332 AND 4998, "
          + "VALUES BETWEEN 4998 AND 6664, VALUES BETWEEN 6664 AND 8330, "
          + "VALUES BETWEEN 8330 AND 10000) colocate with (trade.customers)");
      fail("expected exception in incompatible colocation");
    } catch (SQLException sqle) {
      if (!"X0Y94".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("create table trade.networth (cid int not null, "
          + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
          + "availloan decimal (30, 20), tid int, constraint netw_pk "
          + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
          + "references trade.customers (cid) on delete restrict, "
          + "constraint cash_ch check (cash>=0), constraint sec_ch check "
          + "(securities >=0), constraint availloan_ck check "
          + "(loanlimit>=availloan and availloan >=0)) BUCKETS 7 "
          + "partition by column (cid) colocate with (trade.customers)");
      fail("expected exception in incompatible colocation");
    } catch (SQLException sqle) {
      if (!"X0Y95".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    removeExpectedException(new Object[] { "X0Y94", "X0Y95" });

    // finally success with explicit colocation with or without explicit
    // BUCKETS clause
    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0)) "
        + "partition by range (cid) (VALUES BETWEEN 0 AND 1666, "
        + "VALUES BETWEEN 1666 AND 3332, VALUES BETWEEN 3332 AND 4998, "
        + "VALUES BETWEEN 4998 AND 6664, VALUES BETWEEN 6664 AND 8330, "
        + "VALUES BETWEEN 8330 AND 10000) colocate with (trade.customers)");
    checkValidColocation_42952(stmt);
    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");
    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0)) BUCKETS 7 "
        + "partition by range (cid) (VALUES BETWEEN 0 AND 1666, "
        + "VALUES BETWEEN 1666 AND 3332, VALUES BETWEEN 3332 AND 4998, "
        + "VALUES BETWEEN 4998 AND 6664, VALUES BETWEEN 6664 AND 8330, "
        + "VALUES BETWEEN 8330 AND 10000) colocate with (trade.customers)");
    checkValidColocation_42952(stmt);

    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");
  }

  /**
   * From Derby's SqlExceptionTest#testSerializedException
   */
  public void testSerializedException_43160() throws Exception {
    setupConnection();
    getStatement().execute(
        "CREATE TABLE tableWithPK (c1 int primary key, c2 int)");
    for (int i = 1; i <= 2; i++) {
      final Connection conn;
      // first with embedded connection
      if (i == 1) {
        conn = getConnection();
      } else {
        // next with network connection
        conn = startNetserverAndGetLocalNetConnection();
      }
      Statement stmt = conn.createStatement();
      try {
        // generate some exception by inserting some duplicate
        // primary keys in the same batch
        // This will generate some chained / nested transactions
        // as well
        String insertData = "INSERT INTO tableWithPK values "
            + "(1, 1), (2, 2), (3, 3), (4, 4), (5, 5)";
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.executeBatch();

        // In case the statement completes successfully which is not
        // expected. May not fail because replicated region insert
        // checks for value being the same while lastModifiedTime should
        // be different to qualify as a different update, so try again.
        Thread.sleep(10);
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.executeBatch();

        fail("Unexpected: SQL statement should have failed");
      } catch (SQLException se) {
        // Verify the SQLException can be serialized (DERBY-790)
        SQLException se_ser = SqlExceptionTest.recreateSQLException(se);
        // and that the original and serialized exceptions are equals
        SqlExceptionTest.assertSQLState("Unexpected SQL State",
            se.getSQLState(), se_ser);
        SqlExceptionTest.assertSQLExceptionEquals(se, se_ser);
      } finally {
        stmt.execute("delete from tableWithPK");
        if (i == 2) {
          stopNetServer();
        }
      }
    }
    getStatement().execute("DROP TABLE tableWithPK");
  }

  public void testBug43889() throws Exception {
    setupConnection();
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    // first test for partitioned tables with PK partitioning
    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");

    stmt.execute("create table storesChild("
        + "id char(4) CONSTRAINT PK_id PRIMARY KEY, fk char(4),"
        + "constraint store_fk foreign key (fk) "
        + "references stores (stor_id) on delete restrict) "
        + "partition by primary key");

    try {
      stmt.execute("drop table stores");
      fail("expected exception in drop table");
    } catch (SQLException sqle) {
      if (!"X0Y25".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("drop table storesChild");
    stmt.execute("drop table stores");

    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");
    stmt.execute("drop table stores");

    // next test for partitioned tables with default partitioning
    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");

    stmt.execute("create table storesChild("
        + "id char(4) CONSTRAINT PK_id PRIMARY KEY, fk char(4),"
        + "constraint store_fk foreign key (fk) "
        + "references stores (stor_id) on delete restrict)");

    try {
      stmt.execute("drop table stores");
      fail("expected exception in drop table");
    } catch (SQLException sqle) {
      if (!"X0Y98".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("drop table storesChild");
    stmt.execute("drop table stores");

    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");
    stmt.execute("drop table stores");

    // lastly for replicated tables
    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL) replicate");

    stmt.execute("create table storesChild("
        + "id char(4) CONSTRAINT PK_id PRIMARY KEY, fk char(4),"
        + "constraint store_fk foreign key (fk) "
        + "references stores (stor_id) on delete restrict) replicate");

    try {
      stmt.execute("drop table stores");
      fail("expected exception in drop table");
    } catch (SQLException sqle) {
      if (!"X0Y25".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("drop table storesChild");
    stmt.execute("drop table stores");

    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL) replicate");
    stmt.execute("drop table stores");
  }

  public void testBug43720() throws Exception {
    setupConnection();
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();
    stmt.execute("create table temp (x int) partition by column (x)");
    assertEquals(1, stmt.executeUpdate("insert into temp values (1)"));
    assertEquals(1, stmt.executeUpdate("insert into temp values (2)"));
    assertEquals(1, stmt.executeUpdate("insert into temp values (3)"));
    stmt.execute("create view temp2 as select * from temp where x > 2");
    assertEquals(1, stmt.executeUpdate("insert into temp values (4)"));
    try {
      stmt.execute("drop table temp");
      fail("expected an exception in drop");
    } catch (SQLException sqle) {
      if (!"X0Y23".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    assertEquals(1, stmt.executeUpdate("insert into temp values (5)"));
  }

  public void testBug43628() throws Exception {
    setupConnection();
    // load the DDLs
    GemFireXDUtils.executeSQLScripts(jdbcConn, new String[] { getResourcesDir()
        + "/lib/bug43628.ddl" }, false, getLogger(), null, null, false);

    // now inserts into the table (problem of requiresSerializedHash not getting
    // updated by ALTER TABLE)
    PreparedStatement pstmt = jdbcConn.prepareStatement("INSERT INTO REVERSOS "
        + "(ID_TRANSACCION, FECHA_TELCEL, PUERTO) VALUES (?, ?, ?)");
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v);
      pstmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
      pstmt.setInt(3, v + 100);
      pstmt.execute();
    }

    // verify the inserts
    pstmt.close();
    pstmt = jdbcConn.prepareStatement("select id_transaccion from reversos "
        + "where puerto=?");
    ResultSet rs;
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v + 100);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(v, rs.getInt(1));
      assertFalse(rs.next());
    }
    pstmt.close();

    // now inserts into the second table (problem of parent having no PK but
    // colocated child having PK)
    pstmt = jdbcConn.prepareStatement("INSERT INTO REVERSOS2 "
        + "(ID_TRANSACCION, FECHA_TELCEL, PUERTO) VALUES (?, ?, ?)");
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v);
      pstmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
      pstmt.setInt(3, v + 100);
      pstmt.execute();
    }

    // verify the inserts
    pstmt.close();
    pstmt = jdbcConn.prepareStatement("select id_transaccion from reversos2 "
        + "where puerto=?");
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v + 100);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(v, rs.getInt(1));
      assertFalse(rs.next());
    }
    pstmt.close();
  }

  public static void runIdentityChecksForCustomersTable(final Connection conn,
      final int numRows, final int[] cidPos, final String[] cidCols,
      final int numIdentityColumns, final int startValue, final int numServers,
      final DistributedSQLTestBase dunitTest, final boolean isGeneratedAlways) throws Exception {

    Statement stmt = conn.createStatement();
    // some inserts in the table
    TLongHashSet[] allCidValues = new TLongHashSet[numIdentityColumns];
    TLongHashSet cidValues;
    for (int index = 0; index < numIdentityColumns; index++) {
      cidValues = new TLongHashSet(2 * numRows);
      allCidValues[index] = cidValues;
    }
    // check for existing rows
    ResultSet rs;
    int totalRows = numRows;
    int maxValue = -1;
    PreparedStatement pstmtSel = conn
        .prepareStatement("select * from trade.customers");
    // startValue <= 0 indicates existing rows in table while > 0 indicates
    // no existing rows with given start value for the generated identity column
    if (startValue <= 0) {
      rs = pstmtSel.executeQuery();
      while (rs.next()) {
        totalRows++;
      }
      rs.close();
      if (startValue < 0) {
        rs = stmt.executeQuery("select max(cid) from trade.customers");
        rs.next();
        maxValue = rs.getInt(1);
        assertTrue("unexpected maxValue=" + maxValue + " startValue="
            + startValue, maxValue <= (-startValue));
        rs.close();
      }
    }
    int tid;
    int cnt1 = 0;
    PreparedStatement pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)");
    for (tid = 5; tid < (numRows / 4 + 5); tid++) {
      pstmt.setInt(1, tid);
      assertEquals(1, pstmt.executeUpdate());
      cnt1++;
    }
    // check for uniqueness of the inserted values
    rs = pstmtSel.executeQuery();
    while (rs.next()) {
      int index = 0;
      long cidVal;
      for (int pos : cidPos) {
        cidValues = allCidValues[index++];
        cidVal = rs.getLong(pos);
        if (!cidValues.add(cidVal)) {
          fail("unexpected duplicate for cid(" + pos + ") " + cidVal);
        }
        if (startValue > 0) {
          assertTrue("expected cid column to start from " + startValue
              + " but was " + cidVal, cidVal >= startValue);
        }
      }
    }
    rs.close();
    if (startValue < 0) {
      // check that newly inserted values are greater than existing startValue
      rs = pstmtSel.executeQuery();
      int cnt2 = 0;
      while (rs.next()) {
        boolean inc = false;
        long cidVal;
        for (int pos : cidPos) {
          cidVal = rs.getLong(pos);
          if (cidVal > maxValue) {
            inc = true;
          }
        }
        if (inc) {
          cnt2++;
        }
      }
      rs.close();
      assertEquals(cnt1, cnt2);
    }

    // check for auto-generated results
    VM vm;
    if (numServers > 0) {
      vm = dunitTest.getServerVM(1);
      Object[] result = (Object[])vm.invoke(CreateTable2Test.class,
          "runIdentityChecks1", new Object[] { tid, numRows,
              numIdentityColumns, allCidValues });
      tid = (Integer)result[0];
      allCidValues = (TLongHashSet[])result[1];
    } else {
      Object[] result = runIdentityChecks1(tid, numRows, numIdentityColumns,
          allCidValues);
      tid = (Integer)result[0];
    }
    if (numServers > 1) {
      vm = dunitTest.getServerVM(2);
      Object[] result = (Object[])vm.invoke(CreateTable2Test.class,
          "runIdentityChecks2", new Object[] { tid, numRows,
              numIdentityColumns, cidPos, allCidValues });
      tid = (Integer)result[0];
      allCidValues = (TLongHashSet[])result[1];
    } else {
      Object[] result = runIdentityChecks2(tid, numRows, numIdentityColumns,
          cidPos, allCidValues);
      tid = (Integer)result[0];
    }
    ResultSet autoGen;
    for (; tid < numRows + 5; tid++) {
      stmt.execute("insert into trade.customers (tid) values (" + tid + ")",
          cidCols);
      autoGen = stmt.getGeneratedKeys();
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
    }

    for (int index = 0; index < numIdentityColumns; index++) {
      assertEquals(totalRows, allCidValues[index].size());
    }

    // first check failure for invalid columns
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)",
        new int[] { cidPos[0] == 2 ? 1 : 2 });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0E".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)", new int[] { 7 });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0E".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    pstmt = conn
        .prepareStatement("insert into trade.customers (tid) values (?)",
            new String[] { "none" });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0F".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)", new String[] { "tid" });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0F".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // also exception when trying to insert the always auto-generated column
    if (isGeneratedAlways) {
      try {
        stmt.execute("insert into trade.customers (cid, tid) values (1, 1)");
        fail("expected failure for non auto-generated columns");
      } catch (SQLException sqle) {
        if (!"42Z23".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }

    // now explicitly get the auto-incremented values during inserts

    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)",
        Statement.RETURN_GENERATED_KEYS);
    // check for auto-gen result set for single inserts
    for (tid = 100; tid < (numRows / 4 + 100); tid++) {
      pstmt.setInt(1, tid);
      pstmt.execute();
      autoGen = pstmt.getGeneratedKeys();
      assertNotNull(autoGen);
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
    }
    // check for auto-gen result set for batch inserts
    int numNewRows = 0;
    for (; tid < (numRows / 2 + 100); tid++, numNewRows++) {
      pstmt.setInt(1, tid);
      pstmt.addBatch();
    }
    int[] batchResults = pstmt.executeBatch();
    assertEquals(numNewRows, batchResults.length);
    for (int res : batchResults) {
      assertEquals(1, res);
    }
    autoGen = pstmt.getGeneratedKeys();
    assertNotNull(autoGen);
    while (numNewRows-- > 0) {
      assertTrue(
          "expected next element with " + numNewRows + " rows remaining",
          autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
    }
    assertFalse(autoGen.next());
    // lastly check for auto-gen result set with multiple inserts in batch
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?), (?), (?), (?)",
        Statement.RETURN_GENERATED_KEYS);
    numNewRows = 0;
    for (; tid < (numRows + 100); numNewRows++) {
      pstmt.setInt(1, tid++);
      pstmt.setInt(2, tid++);
      pstmt.setInt(3, tid++);
      pstmt.setInt(4, tid++);
      pstmt.addBatch();
    }
    batchResults = pstmt.executeBatch();
    assertEquals(numNewRows, batchResults.length);
    for (int res : batchResults) {
      assertEquals(4, res);
    }
    autoGen = pstmt.getGeneratedKeys();
    assertNotNull(autoGen);
    numNewRows <<= 2;
    while (numNewRows-- > 0) {
      assertTrue(
          "expected next element with " + numNewRows + " rows remaining",
          autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
    }
    assertFalse(autoGen.next());

    for (int index = 0; index < numIdentityColumns; index++) {
      assertEquals(totalRows + numRows, allCidValues[index].size());
    }

    // also check using select
    for (int index = 0; index < numIdentityColumns; index++) {
      cidValues = allCidValues[index];
      cidValues.clear();
    }
    rs = pstmtSel.executeQuery();
    while (rs.next()) {
      int index = 0;
      for (int pos : cidPos) {
        cidValues = allCidValues[index++];
        if (!cidValues.add(rs.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") " + rs.getLong(pos));
        }
      }
    }
    rs.close();
    for (int index = 0; index < numIdentityColumns; index++) {
      assertEquals(totalRows + numRows, allCidValues[index].size());
    }

    if (startValue < 0) {
      // check that newly inserted values are greater than existing startValue
      rs = pstmtSel.executeQuery();
      int cnt2 = 0;
      while (rs.next()) {
        boolean inc = false;
        long cidVal;
        for (int pos : cidPos) {
          cidVal = rs.getLong(pos);
          if (cidVal > maxValue) {
            inc = true;
          }
        }
        if (inc) {
          cnt2++;
        }
      }
      rs.close();
      assertEquals(numRows * 2, cnt2);
    }

    pstmt.close();
    stmt.close();
  }

  public static Object[] runIdentityChecks1(int tid, final int numRows,
      final int numIdentityColumns, final TLongHashSet[] allCidValues)
      throws Exception {
    TLongHashSet cidValues;
    ResultSet autoGen;
    final PreparedStatement pstmt = getConnection().prepareStatement(
        "insert into trade.customers (tid) values (?)",
        Statement.RETURN_GENERATED_KEYS);
    for (; tid < numRows / 2 + 5; tid++) {
      pstmt.setInt(1, tid);
      assertEquals(1, pstmt.executeUpdate());
      autoGen = pstmt.getGeneratedKeys();
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
      autoGen.close();
    }
    return new Object[] { tid, allCidValues };
  }

  public static Object[] runIdentityChecks2(int tid, final int numRows,
      final int numIdentityColumns, final int[] cidPos,
      final TLongHashSet[] allCidValues) throws Exception {
    TLongHashSet cidValues;
    ResultSet autoGen;
    final PreparedStatement pstmt = getConnection().prepareStatement(
        "insert into trade.customers (tid) values (?)",
        cidPos);
    for (; tid < (numRows * 3) / 4 + 5; tid++) {
      pstmt.setInt(1, tid);
      assertEquals(1, pstmt.executeUpdate());
      autoGen = pstmt.getGeneratedKeys();
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
      autoGen.close();
    }
    return new Object[] { tid, allCidValues };
  }

  private void checkTableProperties(ResultSet rs) throws SQLException {
    final PartitionAttributesImpl pattrs1 = new PartitionAttributesImpl();
    pattrs1.setRedundantCopies(2);
    pattrs1.setRecoveryDelay(1000);
    final PartitionAttributesImpl pattrs2 = new PartitionAttributesImpl();
    pattrs2.setRedundantCopies(2);
    pattrs2.setColocatedWith("/TRADE/CUSTOMERS");
    pattrs2.setRecoveryDelay(1000);
    final EvictionAttributes evictAttrs = EvictionAttributes
        .createLRUHeapAttributes(new GfxdObjectSizer(),
            EvictionAction.OVERFLOW_TO_DISK);
    //final Properties diskProps = new Properties();
    //diskProps.put(DiskWriteAttributesImpl.SYNCHRONOUS_PROPERTY, "true");
    //final DiskWriteAttributesImpl diskAttrs = new DiskWriteAttributesImpl(
    //  diskProps);
    String diskAttribs = "DiskStore is " + GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME
        + "; " + "Synchronous writes to disk";
    while (rs.next()) {
      if ("customers".equals(rs.getString("TABLENAME").toLowerCase())) {
        // check for DataPolicy and partition attributes
        assertEquals("PARTITION", rs.getString("DATAPOLICY"));
        assertEquals(pattrs1.getStringForGFXD(), rs.getString("PARTITIONATTRS"));
        // check for partition resolver
        assertEquals("PARTITION BY PRIMARY KEY", rs.getString("RESOLVER"));
        // check for eviction attributes
        assertEquals(evictAttrs.toString(), rs.getString("EVICTIONATTRS"));
        // check for disk attributes
        // null check for other attributes
        assertNull(rs.getString("EXPIRATIONATTRS"));
        // ??ezoerner:20100318 should this be sync or async?
        assertEquals(diskAttribs, rs.getString("DISKATTRS"));
        assertNull(rs.getString("LOADER"));
        assertNull(rs.getString("WRITER"));
        assertNull(rs.getString("LISTENERS"));
        assertNull(rs.getString("ASYNCLISTENERS"));
        assertNull(rs.getObject("ASYNCLISTENERS"));
        assertFalse(rs.getBoolean("GATEWAYENABLED"));
      } else if ("networth".equals(rs.getString("TABLENAME").toLowerCase())) {
        // check for DataPolicy and partition attributes
        assertEquals("PARTITION", rs.getString("DATAPOLICY"));
        assertEquals(pattrs2.getStringForGFXD(), rs.getString("PARTITIONATTRS"));
        // check for partition resolver
        assertEquals("PARTITION BY COLUMN (TID)", rs.getString("RESOLVER"));
        // check for eviction attributes
        assertEquals(evictAttrs.toString(), rs.getString("EVICTIONATTRS"));
        // check for disk attributes
        assertEquals(diskAttribs, rs.getString("DISKATTRS"));
        // null check for other attributes
        assertNull(rs.getString("EXPIRATIONATTRS"));
        assertNull(rs.getString("LOADER"));
        assertNull(rs.getString("WRITER"));
        assertNull(rs.getString("LISTENERS"));
        assertNull(rs.getObject("ASYNCLISTENERS"));
        assertNull(rs.getString("ASYNCLISTENERS"));
        assertFalse(rs.getBoolean("GATEWAYENABLED"));
      }
    }
  }

  private void checkValidColocation_42952(final Statement stmt)
      throws SQLException {
    Region<Object, Object> custReg = Misc.getRegionForTable("TRADE.CUSTOMERS",
        true);
    Region<Object, Object> netReg = Misc.getRegionForTable("TRADE.NETWORTH",
        true);
    PartitionAttributes<?, ?> custAttrs = custReg.getAttributes()
        .getPartitionAttributes();
    PartitionAttributes<?, ?> netAttrs = netReg.getAttributes()
        .getPartitionAttributes();

    assertEquals(7, custAttrs.getTotalNumBuckets());
    assertEquals(7, netAttrs.getTotalNumBuckets());
    assertEquals("/TRADE/CUSTOMERS", netAttrs.getColocatedWith());

    // check the resolver for networth table
    PartitionResolver<?, ?> custResolver = custAttrs.getPartitionResolver();
    PartitionResolver<?, ?> netResolver = netAttrs.getPartitionResolver();
    assertEquals(GfxdRangePartitionResolver.class, custResolver.getClass());
    assertEquals(GfxdRangePartitionResolver.class, netResolver.getClass());

    GfxdRangePartitionResolver custRangeResolver =
        (GfxdRangePartitionResolver)custResolver;
    GfxdRangePartitionResolver netRangeResolver =
        (GfxdRangePartitionResolver)netResolver;
    assertTrue(custRangeResolver.getDDLString().contains("3332"));
    assertTrue(custRangeResolver.getDDLString().contains("8330"));
    assertEquals(custRangeResolver.getDDLString(),
        netRangeResolver.getDDLString());

    // do a few inserts in parent and child
    stmt.execute("insert into trade.customers (cid, tid) values (1000, 2000)");
    stmt.execute("insert into trade.customers (cid, tid) values (2000, 4000)");
    stmt.execute("insert into trade.customers (cid, tid) values (3000, 6000)");
    stmt.execute("insert into trade.customers (cid, tid) values (10000, 20000)");
    stmt.execute("insert into trade.customers (cid, tid) values (20000, 40000)");
    stmt.execute("insert into trade.customers (cid, tid) values (30000, 60000)");

    stmt.execute("insert into trade.networth (cid, tid) values (1000, 2000)");
    stmt.execute("insert into trade.networth (cid, tid) values (2000, 4000)");
    stmt.execute("insert into trade.networth (cid, tid) values (3000, 6000)");
    stmt.execute("insert into trade.networth (cid, tid) values (10000, 20000)");
    // check for FK violation
    addExpectedException("23503");
    try {
      stmt.execute("insert into trade.networth (cid, tid) values (40000, 80000)");
      fail("expected FK violation");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("insert into trade.networth (cid, tid) values (50000, 50000)");
      fail("expected FK violation");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    removeExpectedException("23503");
    // few more valid inserts
    stmt.execute("insert into trade.networth (cid, tid) values (20000, 40000)");
    stmt.execute("insert into trade.networth (cid, tid) values (30000, 60000)");
  }

  public void testBug45803() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    // Create some partitioned tables, partitioned by range
    // But with invalid ranges (end value is less than or equal to start)
    // Each should fail with 0A000 : "Not supported : Begin range not less than end range"
    boolean gotException = false;
    try {
      // Start cannot be greater than end
      s.execute("create table BADRANGE1 (ID int not null) "
          + "PARTITION BY RANGE(ID) (VALUES BETWEEN 5 and 2)");
    } catch (SQLException e) {
      if ("0A000".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    gotException = false;

    try {
      // Start and end cannot be equal
      s.execute("create table BADRANGE1 (ID int not null) "
          + "PARTITION BY RANGE(ID) (VALUES BETWEEN 5 and 5)");
    } catch (SQLException e) {
      if ("0A000".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    gotException = false;

    try {
      // DATE values in wrong order
      s.execute("create table BADRANGE1 (ID date not null) "
          + "PARTITION BY RANGE(ID) (VALUES BETWEEN '1972-05-01' AND '1964-07-14')");
    } catch (SQLException e) {
      if ("0A000".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
  }

  public void testBug45808() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    // Create some partitioned tables, partitioned by column
    // But with invalid column lists (dups or columns that aren't in table)
    // Each should fail with 42X12 (column dup) or 42X01 (column not found)
    boolean gotException = false;
    try {
      // Duplicate column names
      s.execute("create table BADCOL1 (ID int not null, PHONENUM varchar(15)) "
          + "PARTITION BY COLUMN(PHONENUM, ID, PHONENUM)");
    } catch (SQLException e) {
      if ("42X12".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    gotException = false;

    try {
      // Column name in partition clause isn't in table
      s.execute("create table BADCOL2 (ID int not null, PHONENUM varchar(15)) "
          + "PARTITION BY COLUMN(PHONENUM, LASTNAME, ID)");
    } catch (SQLException e) {
      if ("42X01".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
  }

  public void testBug50069() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    try {
      st.execute("CREATE TABLE T.TABLE_DATA (ID VARCHAR (36) NOT NULL , CONSTRAINT DATA_PK PRIMARY KEY(ID)) "
          + "PARTITION BY PRIMARY KEY REDUNDANCY 1 PERSISTENT ASYNCHRONOUS OFFHEAP");
      fail("Expected IllegalStateException due to no off-heap memory being configured.");
    } catch (SQLException expected) {
      if (!(expected.getCause() instanceof IllegalStateException)) {
        fail("Caught unexpected exception", expected);
      }
    }
  }

  public void testMemScaleDDL() throws Exception {
    System.setProperty("gemfire.off-heap-memory-size", "500m");
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    st.execute("create table mytab(col1 int primary key, col2 varchar(10)) offheap");
    LocalRegion region = (LocalRegion)Misc.getRegion("APP/MYTAB", true, false);
    RegionAttributes<?, ?> ra = region.getAttributes();
    assertTrue(ra.getEnableOffHeapMemory());

    PreparedStatement ps = conn.prepareStatement("insert into mytab values (?, ?)");
    for (int i = 0; i < 12345; i++) {
      ps.setInt(1, i);
      ps.setString(2, "abcdefghij");
      ps.execute();
    }

    st.execute("select * from mytab where col1 = 7");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(7, rs.getInt(1));
    assertEquals("abcdefghij", rs.getString(2));
    rs.close();
  }

  class ColumnMetaData {
    int columnDisplaySize;
    String columnName;
    int columnType;
    int precision;
    int scale;
    int isNullable;

    ColumnMetaData(int columnDisplaySize, String columnName, int columnType,
        int precision, int scale, int isNullable) {
      this.columnDisplaySize = columnDisplaySize;
      this.columnName = columnName;
      this.columnType = columnType;
      this.precision = precision;
      this.scale = scale;
      this.isNullable = isNullable;
    }

    @Override
    public boolean equals(Object other) {
      ColumnMetaData o = (ColumnMetaData)other;
      return this.columnDisplaySize == o.columnDisplaySize &&
          this.columnName.equals(o.columnName) &&
          this.columnType == o.columnType &&
          this.precision == o.precision &&
          this.scale == o.scale &&
          this.isNullable == o.isNullable;

    }
  }

  ;

  public ColumnMetaData[] getColumnMetaData(Statement stmt, String query)
      throws SQLException {
    ResultSet rs1 = stmt.executeQuery(query);
    assertTrue(rs1.next());

    ResultSetMetaData rsm = rs1.getMetaData();
    int columnCount = rsm.getColumnCount();

    ColumnMetaData[] columnMetaData = new ColumnMetaData[columnCount];
    for (int i = 1; i <= columnCount; i++) {
      int columnDisplaySize = rsm.getColumnDisplaySize(i);
      String columnName = rsm.getColumnName(i);
      int columnType = rsm.getColumnType(i);
      int precision = rsm.getPrecision(i);
      int scale = rsm.getScale(i);
      int isNullable = rsm.isNullable(i);

      columnMetaData[i - 1] = new ColumnMetaData(columnDisplaySize,
          columnName, columnType, precision, scale, isNullable);
    }
    rs1.close();
    return columnMetaData;
  }

  // #50116: For 'create table t2 as select * from t1' query replace the
  // sqltext in DDLConflatable by a generated SQL text that has columns
  // definitions instead of the select clause so that even if the base table
  // (t1) is dropped, DDL replay will not fail
  public void test50116_CTAS() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();

    // base table1
    stmt.execute("create table trade.base1 "
        + "(oid int not null constraint orders_pk primary key, "
        + "cid int default 4, sid int, " + "qty int, ask decimal (30, 20), "
        + "order_time timestamp, status varchar(10), "
        + "tid int) partition by (oid) persistent");

    // base table2
    stmt.execute("create table trade.base2 "
        + "(oid int not null constraint orders_pk2 primary key, "
        + "cid int default 4, sid int, " + "qty int, ask decimal (30, 20), "
        + "order_time timestamp, status varchar(10), "
        + "tid int) partition by (oid) persistent");

    // derived tables created with 'create table <derived> as select from
    // <base>'
    stmt.execute("create table trade.derived1 as select * from "
        + "trade.base1 with no data persistent");
    stmt.execute("create table trade.derived2(a, b, c, d, e, f, g, h) as " +
        "select * from trade.base1 with no data persistent");
    stmt.execute("create table trade.derived3(a, g) as select oid, status " +
        "from trade.base1 with no data persistent");
    stmt.execute("create table trade.derived4 as select oid, status from "
        + "trade.base1 with no data persistent");
    stmt.execute("create table trade.derived5 as select trade.base1.oid, " +
        "trade.base2.status from trade.base1 join trade.base2 on " +
        "trade.base1.oid = trade.base2.oid with no data persistent");
    stmt.execute("create table trade.derived6(a, g) as select oid*2, status " +
        "from trade.base1 with no data persistent");

    //insert some data in each derived table
    stmt.execute("insert into trade.derived1 values (1, 1, 1, 1, 1.1, " +
        "'1994-01-01 23:59:59', 'STATUS', 1)");
    stmt.execute("insert into trade.derived2 values (1, 1, 1, 1, 1.1, " +
        "'1994-01-01 23:59:59', 'STATUS', 1)");
    stmt.execute("insert into trade.derived3 values (1,'STATUS')");
    stmt.execute("insert into trade.derived4 values (1,'STATUS')");
    stmt.execute("insert into trade.derived5 values (1,'STATUS')");
    stmt.execute("insert into trade.derived6 values (1,'STATUS')");

    // grab the column meta data before restart
    ColumnMetaData[] derived1_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived1");
    ColumnMetaData[] derived2_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived2");
    ColumnMetaData[] derived3_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived3");
    ColumnMetaData[] derived4_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived4");
    ColumnMetaData[] derived5_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived5");
    ColumnMetaData[] derived6_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived6");

    //drop base tables
    stmt.execute("drop table trade.base1");
    stmt.execute("drop table trade.base2");

    shutDown();
    conn = TestUtil.getConnection(props);
    stmt = conn.createStatement();

    // grab the column meta data after restart and make sure that it
    // is same as that of the one before restart
    ColumnMetaData[] derived1_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived1");
    assertTrue(Arrays.equals(derived1_meta_old, derived1_meta_after_restart));

    ColumnMetaData[] derived2_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived2");
    assertTrue(Arrays.equals(derived2_meta_old, derived2_meta_after_restart));

    ColumnMetaData[] derived3_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived3");
    assertTrue(Arrays.equals(derived3_meta_old, derived3_meta_after_restart));

    ColumnMetaData[] derived4_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived4");
    assertTrue(Arrays.equals(derived4_meta_old, derived4_meta_after_restart));

    ColumnMetaData[] derived5_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived5");
    assertTrue(Arrays.equals(derived5_meta_old, derived5_meta_after_restart));

    ColumnMetaData[] derived6_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived6");
    assertTrue(Arrays.equals(derived6_meta_old, derived6_meta_after_restart));
  }

  public void test50116_50757_CTAS2() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();

    stmt.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' " +
        "LANGUAGE JAVA");

    stmt.execute("create table trade.all_datatypes " +
        "(col1 bigint, " +
        "col2 blob, " +
        "col3 char(2), " +
        "col4 char(10) for bit data, " +
        "col5 clob, " +
        "col6 date, " +
        "col7 decimal(10, 6), " +
        "col8 double, " +
        "col9 float, " +
        "col10 integer, " +
        "col11 long varchar, " +
        "col12 long varchar for bit data, " +
        "col13 numeric(10, 6), " +
        "col14 real, " +
        "col15 smallint, " +
        "col16 time, " +
        "col17 timestamp, " +
        "col18 trade.UUID, " +
        "col19 varchar(2), " +
        "col20 varchar(10) for bit data) ");

    stmt.execute("create table trade.all_datatypes_derived as select * from "
        + "trade.all_datatypes with no data persistent");

    PreparedStatement pstmt = conn.prepareStatement("insert into " +
        "trade.all_datatypes_derived values " +
        "(1, " +
        "cast(X'0031' as blob), " +
        "'a', " +
        "x'102030', " +
        "'abc', " +
        "'1998-04-18', " +
        "1.1, " +
        "1.1, " +
        "1.1, " +
        "10, " +
        "'aa', " +
        "x'102030', " +
        "1.1, " +
        "1.1, " +
        "1, " +
        "'00:00:00', " +
        "'1994-01-01 23:59:59', " +
        "?, " +  // UUID
        "'aa', " +
        "x'102030')");

    UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
    pstmt.setObject(1, uid); //col18 UUID
    pstmt.execute();

    String query = "select * from trade.all_datatypes_derived";

    // grab the column meta data before restart
    ColumnMetaData[] derived_meta_old = getColumnMetaData(stmt,
        query);

    //drop base tables
    stmt.execute("drop table trade.all_datatypes");
    shutDown();
    conn = TestUtil.getConnection(props);
    stmt = conn.createStatement();

    // grab the column meta data after restart and make sure that it
    // is same as that of the one before restart
    ColumnMetaData[] derived_meta_after_restart = getColumnMetaData(stmt,
        query);
    assertTrue(Arrays.equals(derived_meta_old, derived_meta_after_restart));
  }
}
