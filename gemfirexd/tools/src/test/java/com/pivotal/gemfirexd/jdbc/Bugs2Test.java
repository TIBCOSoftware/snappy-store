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
package com.pivotal.gemfirexd.jdbc;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.gemstone.gemfire.cache.util.CacheWriterAdapter;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.MultipleInsertsLeveragingPutAllDUnit;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.procedure.ProcedureExecutionContext;

public class Bugs2Test extends JdbcTestBase {

  private volatile boolean exceptionOccured = false;

  public Bugs2Test(String name) {
    super(name);
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    this.exceptionOccured = false;
  }

  static void validateForBug42099(ResultSet rs, boolean expectCommittedData)
      throws SQLException {
    rs.next();
    if (expectCommittedData) {
      assertEquals(1, rs.getInt(1));
      assertEquals(1, rs.getInt(2));
      assertEquals(1, rs.getInt(3));
      assertEquals(1, rs.getInt(4));
      assertEquals(1, rs.getInt(5));
    } else {
      assertEquals(1, rs.getInt(1));
      assertEquals(2, rs.getInt(2));
      assertEquals(3, rs.getInt(3));
      assertEquals(4, rs.getInt(4));
      assertEquals(5, rs.getInt(5));
    }
  }

  public void testPrepStatementBatchUpdate() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    long initialvalueofmaxbatchsize = GemFireXDUtils.DML_MAX_CHUNK_SIZE;
    try {
      for (int txntxitr = 0; txntxitr < 2; txntxitr++) {
        GemFireXDUtils.DML_MAX_CHUNK_SIZE = 50;
        System.setProperty("gemfirexd.dml-max-chunk-size", "50");
        Connection conn = TestUtil.getConnection(props1);
        if (txntxitr > 0) {
          conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        Statement s = conn.createStatement();
        s.execute("create schema emp");

        MultipleInsertsLeveragingPutAllDUnit.BatchInsertObserver bos = new MultipleInsertsLeveragingPutAllDUnit.BatchInsertObserver();
        GemFireXDQueryObserverHolder.setInstance(bos);

        for (int type = 0; type < 6; type++) {
          String tableType;
          if (type % 3 == 0) {
            tableType = "partition by primary key";
          } else if (type % 3 == 1) {
            tableType = "partition by column (firstname)";
          } else {
            tableType = "replicate";
          }
          s.execute("create table emp.EMPLOYEE(lastname varchar(30) "
              + "primary key, depId int, firstname varchar(30)) " + tableType);
          PreparedStatement pstmnt = conn
              .prepareStatement("INSERT INTO emp.employee VALUES (?, ?, ?)");
          pstmnt.setString(1, "Jones");
          pstmnt.setInt(2, 33);
          pstmnt.setString(3, "Brendon");
          pstmnt.addBatch();

          pstmnt.setString(1, "Rafferty");
          pstmnt.setInt(2, 31);
          pstmnt.setString(3, "Bill");
          pstmnt.addBatch();

          pstmnt.setString(1, "Robinson");
          pstmnt.setInt(2, 34);
          pstmnt.setString(3, "Ken");
          pstmnt.addBatch();

          pstmnt.setString(1, "Steinberg");
          pstmnt.setInt(2, 33);
          pstmnt.setString(3, "Richard");
          pstmnt.addBatch();

          pstmnt.setString(1, "Smith");
          pstmnt.setInt(2, 34);
          pstmnt.setString(3, "Robin");
          pstmnt.addBatch();

          pstmnt.setString(1, "John");
          pstmnt.setNull(2, Types.INTEGER);
          pstmnt.setString(3, "Wright");
          pstmnt.addBatch();

          int[] status = pstmnt.executeBatch();
          bos.clear();

          PreparedStatement pstmnt2 = conn
              .prepareStatement("update emp.employee set depId = ? where lastname = ?");
          pstmnt2.setInt(1, 100);
          pstmnt2.setString(2, "Jones");
          pstmnt2.addBatch();

          pstmnt2.setInt(1, 100);
          pstmnt2.setString(2, "Rafferty");
          pstmnt2.addBatch();

          pstmnt2.setInt(1, 100);
          if (type < 3) {
            pstmnt2.setString(2, "Robinson");
          } else {
            pstmnt2.setString(2, "Armstrong");
          }
          pstmnt2.addBatch();

          assertEquals(0, bos.getBatchSize());
          status = pstmnt2.executeBatch();

          assertTrue(bos.getBatchSize() >= 1);
          bos.clear();
          ResultSet rs = s
              .executeQuery("select * from emp.employee where depId = 100");
          int cnt = 0;
          boolean JonesDone = false;
          boolean RaffertyDone = false;
          boolean RobinsonDone = false;
          while (rs.next()) {
            cnt++;
            String lastName = rs.getString(1);
            if (lastName.equalsIgnoreCase("Jones")) {
              assertFalse(JonesDone);
              JonesDone = true;
            } else if (lastName.equalsIgnoreCase("Rafferty")) {
              assertFalse(RaffertyDone);
              RaffertyDone = true;
            } else if (lastName.equalsIgnoreCase("Robinson")) {
              assertFalse(RobinsonDone);
              RobinsonDone = true;
            } else {
              fail("unexpected lastName: " + lastName);
            }
          }
          if (type < 3) {
            assertEquals(3, cnt);
          } else {
            assertEquals(2, cnt);
          }
          conn.commit();
          s.execute("drop table emp.employee");
        }
        s.execute("drop schema emp restrict");
        conn.close();
      }
    } finally {
      GemFireXDUtils.DML_MAX_CHUNK_SIZE = initialvalueofmaxbatchsize;
    }
  }

  public void testBug46046() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    st.execute("create table chartab (tsn char(10))");
    st.execute("insert into chartab values ('2011')");
    st.execute("insert into chartab values ('2014')");
    st.execute("insert into chartab values ('2017')");

    st.execute("select * from chartab where tsn='2011'");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2011"));

    st.execute("select * from chartab where tsn>'2012' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2014"));
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2017"));

    st.execute("select * from chartab where tsn<'2020' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2011"));
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2014"));
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2017"));

    st.execute("create table varchartab (tsn varchar(10))");
    st.execute("insert into varchartab values ('2012')");
    st.execute("insert into varchartab values ('2014')");
    st.execute("insert into varchartab values ('2016')");

    st.execute("select * from varchartab where tsn='2012'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2012"));

    st.execute("select * from varchartab where tsn>'2010' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2012"));
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2014"));
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2016"));

    st.execute("select * from varchartab where tsn<'2015' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2012"));
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2014"));

    st.execute("create table pchartab (tsn char(10)) partition by column(tsn)");
    st.execute("insert into pchartab values ('2013')");
    st.execute("insert into pchartab values ('2013')");
    st.execute("insert into pchartab values ('2015')");
    st.execute("insert into pchartab values ('2017')");
    st.execute("select * from pchartab where tsn='2013'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2013"));
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2013"));

    st.execute("insert into pchartab values ('')");
    st.execute("select * from pchartab where tsn=''");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals(""));

    st.execute("select * from pchartab where tsn>'2013' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2015"));
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2017"));

    st.execute("select * from pchartab where tsn<'2014' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals(""));
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2013"));
    assertTrue(rs.next());
    assertEquals(10, rs.getString(1).length());
    assertTrue(rs.getString(1).trim().equals("2013"));

    st.execute("create table pvarchartab (tsn varchar(10)) partition by column(tsn)");
    st.execute("insert into pvarchartab values ('2014')");
    st.execute("insert into pvarchartab values ('2014')");
    st.execute("select * from pvarchartab where tsn='2014'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2014"));
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2014"));

    st.execute("insert into pvarchartab values ('')");
    st.execute("select * from pvarchartab where tsn=''");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(0, rs.getString(1).length());
    assertTrue(rs.getString(1).equals(""));

    st.execute("select * from pvarchartab where tsn>='' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(0, rs.getString(1).length());
    assertTrue(rs.getString(1).equals(""));
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2014"));
    assertTrue(rs.next());
    assertEquals(4, rs.getString(1).length());
    assertTrue(rs.getString(1).equals("2014"));

    byte[] expectedBytes = { 65, 66, 67, 32, 32, 32, 32, 32, 32, 32 };
    byte[] spaceBytes = { 32, 32, 32, 32, 32, 32, 32, 32, 32, 32 };
    byte[] varcharBytes = { 65, 66, 67 };
    st.execute("create table charfbd (tsn char(10) for bit data)");
    st.execute("insert into charfbd values (x'414243')");
    st.execute("insert into charfbd values (x'414244')");
    st.execute("select * from charfbd where tsn=x'414243'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    assertTrue(Arrays.equals(expectedBytes, rs.getBytes(1)));
    st.execute("insert into charfbd values (x'')");
    st.execute("select * from charfbd where tsn=x''");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    assertTrue(Arrays.equals(spaceBytes, rs.getBytes(1)));

    st.execute("select * from charfbd where tsn>x'41' and tsn<x'414245' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    assertTrue(Arrays.equals(expectedBytes, rs.getBytes(1)));
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    byte[] expected = { 65, 66, 68, 32, 32, 32, 32, 32, 32, 32 };
    assertTrue(Arrays.equals(expected, rs.getBytes(1)));


    st.execute("create table pcharfbd (tsn char(10) for bit data) partition by column(tsn);");
    st.execute("insert into pcharfbd values (x'414243')");
    st.execute("insert into pcharfbd values (x'414244')");
    st.execute("select * from pcharfbd where tsn=x'414243'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    assertTrue(Arrays.equals(expectedBytes, rs.getBytes(1)));
    st.execute("insert into pcharfbd values (x'')");
    st.execute("select * from pcharfbd where tsn=x''");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    assertTrue(Arrays.equals(spaceBytes, rs.getBytes(1)));
    st.execute("select * from pcharfbd where tsn>x'41' and tsn<x'414245' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    assertTrue(Arrays.equals(expectedBytes, rs.getBytes(1)));
    assertTrue(rs.next());
    assertEquals(10, rs.getBytes(1).length);
    assertTrue(Arrays.equals(expected, rs.getBytes(1)));

    st.execute("create table varcharfbd (tsn varchar(10) for bit data)");
    st.execute("insert into varcharfbd values (x'414243')");
    st.execute("insert into varcharfbd values (x'414244')");
    st.execute("select * from varcharfbd where tsn=x'414243'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(3, rs.getBytes(1).length);
    assertTrue(Arrays.equals(varcharBytes, rs.getBytes(1)));
    st.execute("insert into varcharfbd values (x'')");
    st.execute("select * from varcharfbd where tsn=x''");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(0, rs.getBytes(1).length);
    st.execute("select * from varcharfbd where tsn>x'41' and tsn<x'414245' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(3, rs.getBytes(1).length);
    assertTrue(Arrays.equals(varcharBytes, rs.getBytes(1)));
    rs.next();
    assertEquals(3, rs.getBytes(1).length);
    byte[] expectedVarchar = { 65, 66, 68 };
    assertTrue(Arrays.equals(expectedVarchar, rs.getBytes(1)));

    st.execute("create table pvarcharfbd (tsn varchar(10) for bit data) partition by column(tsn);");
    st.execute("insert into pvarcharfbd values (x'414243')");
    st.execute("insert into pvarcharfbd values (x'414244')");
    st.execute("select * from pvarcharfbd where tsn=x'414243'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(3, rs.getBytes(1).length);
    assertTrue(Arrays.equals(varcharBytes, rs.getBytes(1)));
    st.execute("insert into pvarcharfbd values (x'')");
    st.execute("select * from pvarcharfbd where tsn=x''");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(0, rs.getBytes(1).length);
    st.execute("select * from varcharfbd where tsn>x'41' and tsn<x'414245' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(3, rs.getBytes(1).length);
    assertTrue(Arrays.equals(varcharBytes, rs.getBytes(1)));
    assertTrue(rs.next());
    assertEquals(3, rs.getBytes(1).length);
    assertTrue(Arrays.equals(expectedVarchar, rs.getBytes(1)));
    st.execute("select * from varcharfbd where tsn<x'414245' order by tsn");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(0, rs.getBytes(1).length);
    assertTrue(rs.next());
    assertEquals(3, rs.getBytes(1).length);
    assertTrue(Arrays.equals(varcharBytes, rs.getBytes(1)));
    assertTrue(rs.next());
    assertEquals(3, rs.getBytes(1).length);
    assertTrue(Arrays.equals(expectedVarchar, rs.getBytes(1)));
  }

  private static final String TRIGGER_DEPENDS_ON_TYPE = "X0Y24";

  public void testBug49193_2() throws Exception {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("create table customers (cid int not null, "
          + "cust_name varchar(100), col1 int , col2 int not null unique ,"
          + "tid int, primary key (cid)) " + " partition by range(tid)"
          + "   ( values between 0  and 10"
          + "    ,values between 10  and 100)");

      stmt.execute("create index i1 on customers(col1)");
      PreparedStatement psInsert = conn
          .prepareStatement("insert into customers values (?,?,?,?,?)");
      for (int i = 1; i < 2; ++i) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setInt(3, i % 4);
        psInsert.setInt(4, i);
        psInsert.setInt(5, 1);
        psInsert.executeUpdate();
      }

      conn = TestUtil.getConnection();
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGlobalIndexDelete() {
              throw new PartitionOfflineException(null, "test ignore");
            }

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimizerEvalutatedCost) {
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimizerEvalutatedCost) {
              return Double.MIN_VALUE;
            }

          });

      addExpectedException(PartitionOfflineException.class);
      // Test bulk operations
      try {
        stmt.executeUpdate("delete from customers where cid > 0");
        fail("Test should fail due to problem in global index maintenance");
      } catch (Exception ignore) {
        removeExpectedException(PartitionOfflineException.class);
      }
      GemFireXDQueryObserverHolder.clearInstance();
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimizerEvalutatedCost) {
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimizerEvalutatedCost) {
              return Double.MIN_VALUE;
            }

          });

      stmt.executeUpdate("delete from customers where cid > 0");
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  /**
   * There is also a dunit for #46584 in BugsDUnit.java
   * Because client driver and embedded driver
   * might behave differently for the same
   * user input string (varchar/char)
   */
  public void testBug46584() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, exchange varchar(10) not null, constraint sec_pk primary key (sec_id), constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) replicate");
    st.execute("create view trade.securities_vw (sec_id, symbol, exchange) as select sec_id, symbol, exchange from trade.securities");
    st.execute("insert into trade.securities values (1, 'VMW', 'nye')");
    st.execute("select length(symbol) from trade.securities_vw where symbol = 'VMW'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));

    st.execute("insert into trade.securities values (2, 'EMC ', 'nye')");
    st.execute("select length(symbol) from trade.securities_vw where symbol = 'EMC'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(4, rs.getInt(1));
  }

  public void testBug46568() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table chartab(tsn char(10) primary key)");
    st.execute("insert into chartab values('1')");
    st.execute("select * from chartab where tsn = '1'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("select * from chartab where tsn = '1   '");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("select * from chartab where tsn in ('1')");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("select * from chartab where tsn in ('1   ')");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("create table chartab2(tsn char(10))");
    st.execute("insert into chartab2 values('1')");
    st.execute("select * from chartab2 where tsn = '1'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("select * from chartab2 where tsn = '1   '");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("select * from chartab2 where tsn in ('1')");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("select * from chartab2 where tsn in ('1   ')");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("1         ", rs.getString(1));

    st.execute("create table x9 (col1 char(10) for bit data primary key)");
    st.execute("insert into x9 values (x'102030')");
    st.execute("select * from x9 where col1=x'102030'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    byte[] expectedBytes = { 16, 32, 48, 32, 32, 32, 32, 32, 32, 32 };
    assertTrue(Arrays.equals(expectedBytes, rs.getBytes(1)));

    st.execute("create table x10 (col1 char(10) for bit data)");
    st.execute("insert into x10 values (x'102030')");
    st.execute("select * from x10 where col1=x'102030'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertTrue(Arrays.equals(expectedBytes, rs.getBytes(1)));

    st.execute("create table x11 (col1 varchar(10) for bit data primary key)");
    st.execute("insert into x11 values (x'102030')");
    st.execute("select * from x11 where col1=x'102030'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    byte[] expectedBytes2 = { 16, 32, 48 };
    assertTrue(Arrays.equals(expectedBytes2, rs.getBytes(1)));

    st.execute("create table x12 (col1 varchar(10) for bit data)");
    st.execute("insert into x12 values (x'102030')");
    st.execute("select * from x12 where col1=x'102030'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertTrue(Arrays.equals(expectedBytes2, rs.getBytes(1)));

    st.execute("create table pvarcharfbd (tsn varchar(10) for bit data) partition by column(tsn);");
    st.execute("insert into pvarcharfbd values (x'414243')");
    st.execute("insert into pvarcharfbd values (x'414244')");
    st.execute("select * from pvarcharfbd where tsn=x'414243'");
    rs = st.getResultSet();
    rs.next();
    assertEquals(3, rs.getBytes(1).length);

  }

  public void testBug46490() throws Exception {

    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table chartab(tsn char(5))");
    st.execute("insert into chartab values('test1')");
    st.execute("select * from chartab where tsn = 'test1aaa'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table varchartab(tsn varchar(5))");
    st.execute("insert into varchartab values('test1')");
    st.execute("select * from varchartab where tsn = 'test1aaa'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table charfbdtab(tsn char(5) for bit data)");
    st.execute("insert into charfbdtab values(x'1011121314')");
    st.execute("select * from charfbdtab where tsn = x'1011121314151617'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table varcharfbdtab(tsn varchar(5) for bit data)");
    st.execute("insert into varcharfbdtab values(x'1011121314')");
    st.execute("select * from varcharfbdtab where tsn = x'1011121314151617'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table chartab2(tsn char(5) primary key)");
    st.execute("insert into chartab2 values('test1')");
    st.execute("select * from chartab2 where tsn = 'test1aaa'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table varchartab2(tsn varchar(5) primary key)");
    st.execute("insert into varchartab2 values('test1')");
    st.execute("select * from varchartab2 where tsn = 'test1aaa'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table charfbdtab2(tsn char(5) for bit data primary key)");
    st.execute("insert into charfbdtab2 values(x'1011121314')");
    st.execute("select * from charfbdtab2 where tsn = x'1011121314151617'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table varcharfbdtab2(tsn varchar(5) for bit data primary key)");
    st.execute("insert into varcharfbdtab2 values(x'1011121314')");
    st.execute("select * from varcharfbdtab2 where tsn = x'1011121314151617'");
    rs = st.getResultSet();
    assertFalse(rs.next());

    st.execute("create table vt1 (col1 varchar(10) primary key, col2 char(5))");
    st.execute("insert into vt1 values ('b', 'abc')");
    st.execute("select * from vt1 where col1 = 'b '");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("b", rs.getString(1));
    assertEquals("abc  ", rs.getString(2));

    st.execute("create table vt2 (col1 varchar(10) , col2 char(5), primary key(col1, col2))");
    st.execute("insert into vt2 values ('b', 'abc')");
    st.execute("select * from vt2 where col1 = 'b   ' and col2 = 'abc'");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("b", rs.getString(1));
    assertEquals("abc  ", rs.getString(2));
  }

  public void testBug46508() throws Exception {
    Properties prop = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    prop.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(prop);

    // create and populate tables
    Statement s = conn.createStatement();
    ResultSet rs = null;

    // test char
    try {
      s.execute("create table ct (c1 char(1), c2 char(5) not null, c3 char(30) default null)");
      s.execute("insert into ct values ('1', '11111', '111111111111111111111111111111')");
      s.execute("insert into ct values ('44', '4', '4')");
    } catch (SQLException se) {
      assertEquals("22001", se.getSQLState());
    }

    // test varchar
    try {
      s.execute("create table vct (c1 varchar(1), c2 varchar(5) not null, c3 varchar(30) default null)");
      s.execute("insert into vct values ('1', '11111', '111111111111111111111111111111')");
      s.execute("insert into vct values ('44', '4', '4')");
    } catch (SQLException se) {
      assertEquals("22001", se.getSQLState());
    }

    // test char for bit data
    try {
      s.execute("create table cfbdt (c1 char(1) for bit data)");
      s.execute("insert into cfbdt values (x'10')");
      s.execute("insert into cfbdt values (x'1010')");
    } catch (SQLException se) {
      assertEquals("22001", se.getSQLState());
    }

    // test varchar for bit data
    try {
      s.execute("create table vcfbdt (c1 varchar(1) for bit data)");
      s.execute("insert into vcfbdt values (x'10')");
      s.execute("insert into vcfbdt values (x'1010')");
    } catch (SQLException se) {
      assertEquals("22001", se.getSQLState());
    }

  }

  public void testBug46444() throws Exception {
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    Properties props = new Properties();
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    st.execute("CREATE TABLE ap.account_balances ("
        + "account_id           BIGINT NOT NULL, "
        + "balance_type         SMALLINT NOT NULL, "
        + "account_balance          BIGINT NOT NULL, "
        + "locked_by                INT NOT NULL DEFAULT -1, "
        + "PRIMARY KEY (account_id, balance_type)) "
        + "PARTITION BY COLUMN (account_id)");

    st.execute("insert into ap.account_balances values(744, 0, 99992000, -1)");

    st.execute("CREATE TABLE ap.betting_user_accounts("
        + "account_id           BIGINT NOT NULL, "
        + "account_number       CHAR(50) NOT NULL, "
        + "PRIMARY KEY (account_id)) " + "PARTITION BY COLUMN (account_id)");

    st.execute("insert into ap.betting_user_accounts values(744, '2e7')");

    st.execute("CREATE TABLE ap.account_allowed_bet_type("
        + "account_id           BIGINT NOT NULL, "
        + "allow_bet_type       SMALLINT NOT NULL, "
        + "FOREIGN KEY (account_id) REFERENCES ap.betting_user_accounts (account_id)) "
        + "PARTITION BY COLUMN (account_id) COLOCATE WITH (ap.betting_user_accounts)");

    st.execute("CREATE UNIQUE INDEX betting_user_accounts_udx01 ON ap.betting_user_accounts(account_number)");
    st.execute("insert into ap.account_allowed_bet_type values(744, 0)");

    st.execute("UPDATE ap.account_balances SET locked_by = 1 "
        + "WHERE ap.account_balances.balance_type=0 AND ap.account_balances.account_balance >= 1000 AND ap.account_balances.account_id IN "
        + "(SELECT b.account_id FROM ap.betting_user_accounts b, ap.account_allowed_bet_type t "
        + "WHERE b.account_id = t.account_id AND b.account_number='2e7' AND t.allow_bet_type=0 )");
  }

  public void test_08_triggerDependencies() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t_08_a( a int )");
    s.execute("create table t_08_b( a int )");

    String createTypeStatement;
    String dropTypeStatement;
    String createObjectStatement;
    String dropObjectStatement;
    String badDropSQLState;

    byte[] jarBytes = GfxdJarInstallationTest
        .getJarBytes(GfxdJarInstallationTest.pricejar);
    String sql = "call sqlj.install_jar_bytes(?, ?)";
    PreparedStatement ps = conn.prepareStatement(sql);
    ps.setBytes(1, jarBytes);
    ps.setString(2, "APP.udtjar");
    ps.executeUpdate();

    // trigger that mentions a udt
    createTypeStatement = "create type price_08_a external name 'org.apache."
        + "derbyTesting.functionTests.tests.lang.Price' language java";
    dropTypeStatement = "drop type price_08_a restrict\n";
    createObjectStatement = "create trigger trig_08_a after insert on t_08_a\n"
        + " for each row "
        + "  insert into t_08_b( a ) select ( a ) from t_08_a where ( "
        + "cast( null as price_08_a ) ) is not null\n";
    dropObjectStatement = "drop trigger trig_08_a";
    badDropSQLState = TRIGGER_DEPENDS_ON_TYPE;

    s.execute(createTypeStatement);
    s.execute(createObjectStatement);
    try {
      s.execute(dropTypeStatement);
      fail("expected DROP TYPE to fail with " + TRIGGER_DEPENDS_ON_TYPE);
    } catch (SQLException ex) {
      assertTrue(badDropSQLState.equalsIgnoreCase(TRIGGER_DEPENDS_ON_TYPE));
    }

    s.execute(dropObjectStatement);
    s.execute(dropTypeStatement);
  }

  // Copy of XMLTypeAndOpsTest.testTriggerSetXML derbu junit test
  // and modified a little. Also see bug #45982
  public void testTriggerSetXML() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1(c1 int primary key, x int not null)");
    s.execute("create table t2(c1 int primary key, x int not null)");
    // This should fail because type of x is not xml and also trigger defined on same table
    // Please see test XMLTypeAndOpsTest.testTriggerSetXML of derby
    try {
      s.execute("create trigger tr2 after insert on t1 for each row "
          + "mode db2sql update t1 set x = 'hmm'");
      fail("The test should have failed");
    } catch (SQLException ex) {
      // ignore expected failure
      System.out.println(ex.getSQLState());
    }
    // This should also fail and we should get not implemented exception.
    try {
      s.executeUpdate("create trigger tr1 after insert on t1 for each row "
          + "mode db2sql update t1 set x = null");
      fail("should not have succeeded as the table and the target table are same");
    } catch (SQLException ex) {
      assertTrue(ex.getSQLState().equalsIgnoreCase("0A000"));
    }
    // This should succeed because now trigger target table is t2
    s.executeUpdate("create trigger tr1 after insert on t1 for each row "
        + "mode db2sql update t2 set x = null");
    s.close();
  }

  public void testBug45664() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    try {
      s.execute("create table z (col1 integer) replicate partition by column(col1)");
      fail("the above create should have failed");
    } catch (SQLException ex) {
      assertTrue(ex.getSQLState().equalsIgnoreCase("X0Y90"));
    }
  }

  public void testBug42613() throws Exception {
    Properties prop = new Properties();

    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    prop.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(prop);

    // create and populate tables
    Statement s = conn.createStatement();
    s.execute("create table portfolio (cid int, sid int, tid int)");
    s.execute("insert into portfolio values (11, 12, 77)");
    s.execute("insert into portfolio values (22, 12, 77)");
    s.execute("insert into portfolio values (33, 34, 77)");
    s.execute("insert into portfolio values (44, 34, 77)");
    s.execute("insert into portfolio values (55, 56, 88)");
    s.execute("create table customers (cid int, sid int, tid int)");
    s.execute("insert into customers values (66, 67, 99)");
    s.execute("insert into customers values (16, 17, 77)");
    s.execute("insert into customers values (38, 40, 77)");

    // result should be (16, 17, 77) and (38, 40, 77)
    // non-PreparedStatement
    ResultSet rs = s
        .executeQuery("select * from customers where tid = 77 and cid IN "
            + "(select avg(cid) from portfolio where tid = 77 and sid > 0 "
            + "group by sid )");
    while (rs.next()) {
      System.out.println(rs.getInt(1) + "\t" + rs.getInt(2) + "\t"
          + rs.getInt(3));
    }

    // PreparedStatement
    String query = "select * from customers where tid = ? and cid IN "
        + "(select avg(cid) from portfolio where tid = ? and sid > ? "
        + "group by sid )";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setInt(1, 77);
    ps.setInt(2, 77);
    ps.setInt(3, 0);
    rs = ps.executeQuery();
    while (rs.next()) {
      System.out.println(rs.getInt(1) + "\t" + rs.getInt(2) + "\t"
          + rs.getInt(3));
    }
  }

  public void testDataMissing() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    //try {

    String ddl = "create table customer " +
        "( c_id int not null,c_d_id int not null,c_w_id smallint not null," +
        "c_first varchar(16),c_middle char(2),c_last varchar(16),c_street_1 varchar(20)," +
        "c_street_2 varchar(20),c_city varchar(20),c_state char(2)," +
        "c_zip char(9),c_phone char(16),c_since timestamp,c_credit char(2)," +
        "c_credit_lim bigint,c_discount decimal(4,2),c_balance decimal(12,2)," +
        "c_ytd_payment decimal(12,2),c_payment_cnt smallint,c_delivery_cnt smallint," +
        "c_data clob(4096),PRIMARY KEY(c_w_id, c_d_id, c_id) ) replicate"; // persistent asynchronous
    stmt.execute(ddl);
    String dml = "INSERT INTO customer " +
        "(c_id, c_d_id, c_w_id, c_first, c_middle, c_last, " +
        "c_street_1, c_street_2, c_city, c_state, c_zip, c_phone, " +
        "c_since, c_credit, c_credit_lim, c_discount, c_balance, c_ytd_payment, " +
        "c_payment_cnt, c_delivery_cnt, c_data) " +
        "VALUES (641,1,14,'V5RLQ5YkhxfpzZ','OE','ANTIPRESBAR','kpajbLlJYM0UWeYQ8drT'," +
        "'uzmauRNuyocoMMNf5','DPegtHEJPq9qs1pebj3','b0','424455309','8159960776939583'," +
        "'2012-05-01 15:15:52','GC',50000,0.18,-10.00,10.00,1,0," +
        "'9bCvhi2QHqLFHw4wkD3LUxpnhug61V6A6J3l16CHuXW9Sb6Co9XG4K2jFgpEBwPGDQzCUBTPhy" +
        "Y9XcJLjqZo9bXMHLaRFxfSOeesq6FV4mcbOvu8lTus3PEKkpjzkOP832zr8CMCyynLtfTc8LT9k" +
        "hUTUBQDaqLbsISyVmARivmZaD9gYcqHIIikR6x1wHcmZ2k2osSUlE3LSD0ynoD54vqV2nVw27ha" +
        "8PcuI4P3HQNhbLfP9rUBmIgm4Bh6HOgNlHH1Je3a5QHgjv3smY1WohHsryx8KdV3sk5CP8kSY06FvA5fg6BSnQLcOIk')";
    stmt.execute(dml);

    stmt.execute("select c_first, c_last, c_w_id  from customer where c_id = 641 and c_d_id = 1 and c_w_id = 14");

    ResultSet rs = stmt.getResultSet();
    int cnt = 0;
    while (rs.next()) {
      cnt++;
    }
    assertEquals(1, cnt);
//	    }
//	    catch (Exception ex) {
//	    	stmt.execute("drop table customer");
//	    	// ignore ....
//	    }
  }

  public void testBug44613() throws Exception {

    Properties props = new Properties();
    //props.put("mcast-port", "23344");
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);

    Statement s = conn.createStatement();
    s.execute("create schema trade");
    s.execute("create table trade.portfolio " +
        "(cid int not null, " +
        "sid int not null, " +
        "tid int)");

    s.execute("create table trade.networth " +
        "(cid int not null, " +
        "securities int, " +
        "tid int, " +
        "constraint netw_pk primary key (cid))" +
        "partition by range (securities)  " +
        "( VALUES BETWEEN 1 AND 30, " +
        "VALUES BETWEEN 40 AND 60, " +
        "VALUES BETWEEN 70 AND 100)");

    PreparedStatement ps1 = conn
        .prepareStatement("insert into trade.portfolio values (?, ?, ?)");
    for (int i = 1; i < 10; ++i) {
      ps1.setInt(1, i * 11); //cid
      ps1.setInt(2, i * 11 + 1);  //sid
      ps1.setInt(3, i * 11 + 2); //tid
      ps1.executeUpdate();
    }

    PreparedStatement ps2 = conn
        .prepareStatement("insert into trade.networth values (?, ?, ?)");
    for (int i = 1; i < 10; ++i) {
      ps2.setInt(1, i * 11); //cid
      ps2.setInt(2, i * 11 + 1); //securities
      ps2.setInt(3, i * 11 + 2); //tid
      ps2.executeUpdate();
    }

    PreparedStatement ps4 = conn
        .prepareStatement("select cid from trade.networth n where tid = ? and n.cid IN "
            + "(select cid from trade.portfolio where tid =? and sid >? and sid < ? )");
    ps4.setInt(1, 46);
    ps4.setInt(2, 46);
    ps4.setInt(3, 33);
    ps4.setInt(4, 77);
    ResultSet rs4 = ps4.executeQuery();
    while (rs4.next()) {
      System.out.println(rs4.getInt(1));
    }

  }

  public void testRecursiveTriggersDisallowed() throws SQLException {
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table flights(flight_id int not null,"
        + " segment_number int not null, aircraft varchar(20) not null) replicate");

    stmt.execute("create table flights_history(flight_id int not null,"
        + " aircraft varchar(20) not null, status varchar(50) not null)");

    String triggerStmnt = "CREATE TRIGGER trig1 " + "AFTER INSERT ON flights "
        + "REFERENCING NEW AS NEWROW " + "FOR EACH ROW MODE DB2SQL "
        + "INSERT INTO flights_history VALUES "
        + "(NEWROW.FLIGHT_ID, NEWROW.AIRCRAFT, " + "'INSERTED FROM trig1')";
    stmt.execute(triggerStmnt);

    triggerStmnt = "CREATE TRIGGER trig2 " + "AFTER UPDATE ON flights_history "
        + "REFERENCING NEW AS NEWROW " + "FOR EACH ROW MODE DB2SQL "
        + "DELETE FROM flights where segment_number = NEWROW.flight_id";
    try {
      stmt.execute(triggerStmnt);
      fail("recursive trigger declaration should fail");
    } catch (SQLException ex) {
      assertTrue(ex.getSQLState().equalsIgnoreCase("0A000"));
    }
  }

  public void testBug44678() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement stmnt = conn.createStatement();
    stmnt.execute("create schema trade");
    stmnt.execute("create table trade.sellorders (oid int not null constraint orders_pk primary key, " +
        "cid int, sid int, qty int, ask decimal (30, 20), order_time timestamp, " +
        "status varchar(10) default 'open', tid int, " +
        "constraint status_ch check (status in ('cancelled', 'open', 'filled')))");
    stmnt.execute("create table trade.sellordersdup as select * from trade.sellorders with no data");
  }

  public void testForumBugFromPgibb_RR_PR() throws Exception {
    Properties props = new Properties();
    //props.put("mcast-port", "23343");
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
//    props.put("log-file", "/home/kneeraj/tmplog.log");
//    props.put("log-level", "fine");
//    System.setProperty("gemfirexd.debug.true", "TraceOuterJoinMerge");
    Connection conn = TestUtil.getConnection(props);
    Statement stmnt = conn.createStatement();
    stmnt
        .execute("create table Customers(CustomerId integer not null generated always as identity, "
            + "CustomerCode varchar(8) not null, constraint PKCustomers primary key (CustomerId), "
            + "constraint UQCustomers unique (CustomerCode)) " + " replicate");

    stmnt.execute("insert into Customers (CustomerCode) values ('CC1')");
    stmnt.execute("insert into Customers (CustomerCode) values ('CC2')");
    stmnt.execute("insert into Customers (CustomerCode) values ('CC3')");
    stmnt.execute("insert into Customers (CustomerCode) values ('CC4')");

    stmnt
        .execute("create table Devices(DeviceId integer not null generated always as identity, "
            + "CustomerId integer not null, MACAddress varchar(12) not null, "
            + "constraint PKDevices primary key (DeviceId), "
            + "constraint UQDevices unique (CustomerId, MACAddress),"
            + " constraint FKDevicesCustomers foreign key (CustomerId) references Customers (CustomerId))"
            + " partition by column (CustomerId) redundancy 1");

    stmnt
        .execute("insert into Devices (CustomerId, MACAddress) values (1, '000000000001')");
    stmnt
        .execute("insert into Devices (CustomerId, MACAddress) values (1, '000000000002')");
    stmnt
        .execute("insert into Devices (CustomerId, MACAddress) values (2, '000000000001')");
    stmnt
        .execute("insert into Devices (CustomerId, MACAddress) values (2, '000000000002')");

    String[] queries = new String[] {
        "select c.CustomerCode, count(d.DeviceId) from Customers c left outer join Devices d on c.CustomerId = d.CustomerId group by c.CustomerCode",// };
        "select c.*, d.DeviceId from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by c.CustomerId", //};
        "select c.*, d.DeviceId from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by d.DeviceId",
        "select c.*, d.MACAddress from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by c.CustomerId", // } ;//,
        "select c.*, d.MACAddress from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by d.DeviceId" };

    int[] numRows = new int[] { 4, 6, 6, 6, 6 };

    for (int i = 0; i < queries.length; i++) {
      System.out.println("executing query(" + i + "): " + queries[i]);
      stmnt.execute(queries[i]);

      java.sql.ResultSet rs = stmnt.getResultSet();
      int numcols = rs.getMetaData().getColumnCount();
      System.out.println("--------------------------------------");
      int cnt = 0;
      while (rs.next()) {
        cnt++;
        String result = "";
        for (int j = 0; j < numcols; j++) {
          if (j == numcols - 1) {
            result += rs.getObject(j + 1);
          } else {
            result += rs.getObject(j + 1) + ", ";
          }
        }
        System.out.println(result);
      }
      assertEquals(numRows[i], cnt);
      System.out.println("--------------------------------------\n\n");
    }
  }

  public void testMultipleInsert() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table account "
        + "(id int not null primary key, name varchar(20) not null, "
        + "addr varchar(20) not null, balance2 int not null)");
    s.execute("insert into account values (1, 'name1', 'addr1', 100), (2, 'name2', 'addr2', 200),"
        + "(3, 'name3', 'addr3', 300), (4, 'name4', 'addr4', 400) ");
    s.execute("create table backup as select * from account with no data");
    int actual = s.executeUpdate("insert into backup select * from account");
    assertEquals(4, actual);
    s.execute("drop table account");
    s.execute("drop table backup");

    s.execute("create table account "
        + "(id int not null primary key, name varchar(20) not null, "
        + "addr varchar(20) not null, balance2 int not null) replicate");
    s.execute("insert into account values (1, 'name1', 'addr1', 100), (2, 'name2', 'addr2', 200),"
        + "(3, 'name3', 'addr3', 300), (4, 'name4', 'addr4', 400) ");
    s.execute("create table backup as select * from account with no data replicate");
    actual = s.executeUpdate("insert into backup select * from account");
    assertEquals(4, actual);
    s.execute("drop table account");
    s.execute("drop table backup");

    s.execute("create table account "
        + "(id int not null primary key, name varchar(20) not null, "
        + "addr varchar(20) not null, balance2 int not null) replicate");
    s.execute("insert into account values (1, 'name1', 'addr1', 100), (2, 'name2', 'addr2', 200),"
        + "(3, 'name3', 'addr3', 300), (4, 'name4', 'addr4', 400) ");
    s.execute("create table backup as select * from account with no data");
    actual = s.executeUpdate("insert into backup select * from account");
    assertEquals(4, actual);
    s.execute("drop table account");
    s.execute("drop table backup");

    s.execute("create table account "
        + "(id int not null primary key, name varchar(20) not null, "
        + "addr varchar(20) not null, balance2 int not null)");
    s.execute("insert into account values (1, 'name1', 'addr1', 100), (2, 'name2', 'addr2', 200),"
        + "(3, 'name3', 'addr3', 300), (4, 'name4', 'addr4', 400) ");
    s.execute("create table backup as select * from account with no data replicate");
    actual = s.executeUpdate("insert into backup select * from account");
    assertEquals(4, actual);
    s.execute("drop table account");
    s.execute("drop table backup");
  }

  public void testMultipleInsertWithWhereClause() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table account "
        + "(id int not null primary key, name varchar(20) not null, "
        + "addr varchar(20) not null, balance int not null) " +
        "partition by range(balance) (values between 100 and 350, values between 350 and 450)");
    s.execute("insert into account values (1, 'name1', 'addr1', 100), (2, 'name2', 'addr2', 200),"
        + "(3, 'name3', 'addr3', 300), (4, 'name4', 'addr4', 400) ");
    s.execute("create table backup as select * from account with no data");
    int actual = s.executeUpdate("insert into backup select * from account where balance >= 100 and balance <= 300");
    assertEquals(3, actual);
  }

  public void testMultipleInsert_prepStmnt() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table account "
        + "(id int not null primary key, name varchar(20) not null, "
        + "addr varchar(20) not null, balance2 int not null)");
    s.execute("insert into account values (1, 'name1', 'addr1', 100), (2, 'name2', 'addr2', 200),"
        + "(3, 'name3', 'addr3', 300), (4, 'name4', 'addr4', 400) ");
    s.execute("create table backup as select * from account with no data");
    PreparedStatement ps = conn.prepareStatement("insert into backup select * from account where id > ?");
    ps.setInt(1, 0);
    int actual = ps.executeUpdate();
    assertEquals(4, actual);
  }

  private static String proc = "CREATE PROCEDURE RETRIEVE_DYNAMIC_RESULTS(number INT) "
      + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
      + Bugs2Test.class.getName() + ".retrieveDynamicResults' "
      + "DYNAMIC RESULT SETS 2";

  public static void retrieveDynamicResults(int arg, ResultSet[] rs1, ResultSet[] rs2, ProcedureExecutionContext ctx) {
    Connection conn = ctx.getConnection();
    try {
      PreparedStatement ps1 = conn.prepareStatement("<local> select * from t1 where balance2 > ?");
      ps1.setInt(1, arg);
      rs1[0] = ps1.executeQuery();

      PreparedStatement ps2 = conn.prepareStatement("<local> select * from t2 where balance2 > ?");
      ps2.setInt(1, arg);
      GemFireXDQueryObserverHolder.setInstance(new ReprepareObserver());
      rs2[0] = ps2.executeQuery();
    } catch (SQLException e) {
      System.out.println("KN: got exception: " + e);
    }
  }

  public void testDAPNPE() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 (balance int not null, balance2 int not null)");
    s.execute("create table t2 (balance int not null, balance2 int not null)");
    s.execute("create index idx on t1(balance2)");
    s.execute("create index idx2 on t2(balance2)");
    s.execute("insert into t1 values(1, 2), (3, 4)");
    s.execute("insert into t2 values(1, 2), (3, 4)");
    s.execute(proc);
//    DumpClassFile
    CallableStatement cs = conn.prepareCall("call RETRIEVE_DYNAMIC_RESULTS(?) ON TABLE t1");
    cs.setInt(1, 0);

    cs.execute();
    do {
      ResultSet rs = cs.getResultSet();

      while (rs.next()) {
      }
    } while (cs.getMoreResults());
  }

  public static class ReprepareObserver extends GemFireXDQueryObserverAdapter {

    @Override
    public void afterResultSetOpen(GenericPreparedStatement stmt, LanguageConnectionContext lcc, com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {

    }

    @Override
    public void afterQueryExecution(GenericPreparedStatement stmt,
        Activation activation) throws StandardException {
      stmt.makeInvalid(DependencyManager.INTERNAL_RECOMPILE_REQUEST, activation
          .getLanguageConnectionContext());
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    }
  }

  public void testDAPNPE2() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 (balance int not null, balance2 int not null)");
    s.execute("create table t2 (balance int not null, balance2 int not null)");
    s.execute("create index idx on t1(balance2)");
    s.execute("create index idx2 on t2(balance2)");
    s.execute("insert into t1 values(1, 2), (3, 4)");
    s.execute("insert into t2 values(1, 2), (3, 4)");

    ReprepareObserver rpos = new ReprepareObserver();

    try {
      Connection conn_nest = ((EmbedConnection)conn).getLocalDriver().getNewNestedConnection((EmbedConnection)conn);

      PreparedStatement ps1 = conn_nest.prepareStatement("select * from t1 where balance2 > ?");
      ps1.setInt(1, 0);
      GemFireXDQueryObserverHolder.setInstance(rpos);
      ResultSet rs1 = ps1.executeQuery();
      while (rs1.next()) {
        System.out.println(rs1.getInt(1) + " : " + rs1.getInt(2));
      }

//      PreparedStatement ps2 = conn.prepareStatement("select * from t2 where balance2 > ?");
//      ps2.setInt(1, 0);
//
//      ps2.executeQuery();
    } catch (SQLException e) {
      System.out.println("KN: got exception: " + e);
      throw e;
    }


  }

  public void testExecBatchFromNetClient() throws Exception {
    setupConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table emp.EMPLOYEE(lastname varchar(30) primary key, depId int) " +
        "partition by (depId)");

    PreparedStatement pstmnt = conn.prepareStatement("INSERT INTO emp.employee VALUES (?, ?)");
    pstmnt.setString(1, "Jones");
    pstmnt.setInt(2, 33);
    pstmnt.addBatch();

    pstmnt.setString(1, "Rafferty");
    pstmnt.setInt(2, 31);
    pstmnt.addBatch();

    pstmnt.setString(1, "Robinson");
    pstmnt.setInt(2, 34);
    pstmnt.addBatch();

    pstmnt.setString(1, "Steinberg");
    pstmnt.setInt(2, 33);
    pstmnt.addBatch();

    pstmnt.setString(1, "Smith");
    pstmnt.setInt(2, 34);
    pstmnt.addBatch();

    pstmnt.setString(1, "John");
    pstmnt.setNull(2, Types.INTEGER);
    pstmnt.addBatch();

    pstmnt.executeBatch();


    //s.execute("insert into emp.EMPLOYEE values('Jones', 33), ('Rafferty', 31), ('Smith', 34)");
  }

  public void testDecimalPrecision_43290() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 (balance decimal(12,2))");
    s.execute("insert into t1 values(0.0)");
    PreparedStatement ps = conn.prepareStatement("update t1 set balance = ?");
    ps.setFloat(1, 5559.09f);
    ps.executeUpdate();

    PreparedStatement ps2 = conn.prepareStatement("update t1 set balance = balance + ?");
    ps2.setFloat(1, 1596.15f);
    ps2.executeUpdate();

    ResultSet rs = null;
    s.execute("select balance from t1");
    rs = s.getResultSet();
    assertTrue(rs.next());

    boolean notequal = false;
    if (!rs.getString(1).equalsIgnoreCase("7155.24")) {
      notequal = true;
    }
    assertTrue(notequal);

    s.execute("delete from t1");

    s.execute("insert into t1 values(0.0)");
    ps2 = conn.prepareStatement("update t1 set balance = balance + ?");
    ps2.setString(1, "5559.09");
    ps2.executeUpdate();

    ps2 = conn.prepareStatement("update t1 set balance = balance + ?");
    ps2.setString(1, "1596.15");
    ps2.executeUpdate();
    s.execute("select balance from t1");
    rs = s.getResultSet();
    assertTrue(rs.next());
    assertEquals("7155.24", rs.getString(1));
  }

  public void testBug_40504_updateCase() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 (col1 int, col2 int not null, col3 varchar(20) not null)");

    s.execute("insert into t1 values(1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three'), (4, 4, 'four')");

    PreparedStatement ps = conn
        .prepareStatement("update t1 set col1 = ?, col2 = ?, col3 = ? where col1 = 1");

    ps.setInt(2, 2);
    ps.setString(3, "modified");

    try {
      ps.executeUpdate();
      fail("should not proceed");
    } catch (SQLException e) {
      assertTrue("07000".equalsIgnoreCase(e.getSQLState()));
    }
  }

  public void testBug_40504_procedureCase() throws Exception {
    String showGfxdPortfolio = "create procedure trade.showGfxdPortfolio(IN DP1 Integer, "
        + "IN DP2 Integer, IN DP3 Integer, IN DP4 Integer, OUT DP5 Integer) "
        + "PARAMETER STYLE JAVA "
        + "LANGUAGE JAVA "
        + "READS SQL DATA "
        + "DYNAMIC RESULT SETS 3 "
        + "EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.Procedure2Test.selectInAsWellASOut'";

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();

    s.execute("create table trade.portfolio "
        + "(cid int not null, tid int not null)");

    s.execute(showGfxdPortfolio);

    CallableStatement cs = conn
        .prepareCall(" call trade.showGfxdPortfolio(?, ?, ?, ?, ?) "
            + "ON Table trade.portfolio where cid > 258 and cid< 1113 and tid=2");
    cs.setInt(1, 1);
    cs.setInt(2, 2);
    cs.setInt(4, 4);
    cs.registerOutParameter(5, Types.INTEGER);
    try {
      cs.execute();
    } catch (SQLException e) {
      assertTrue("07000".equalsIgnoreCase(e.getSQLState()));
    }
  }

  // Version.java return Configuration.getProductVersionHolder().getMajorVersion(); line 47 throwing NPE
  public void testUseCase8Ticket_6024_1() throws Exception {
    Statement stmt = null;

    Connection conn = null;
    // boot up the DB first
    setupConnection();
    try {
      conn = TestUtil.startNetserverAndGetLocalNetConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      stmt = conn.createStatement();

      // We create a table...

      stmt
          .execute(" create table sample (id varchar(16) primary key, num integer ) ");

      stmt
          .execute("CREATE PROCEDURE PROC( IN account varchar(8) )"
              + "MODIFIES SQL DATA  DYNAMIC RESULT SETS 1  PARAMETER STYLE JAVA  "
              + "LANGUAGE JAVA EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.Bugs2Test.proc'");
      stmt.execute("insert into sample values('a', 1)");
      stmt.execute("insert into sample values('b', 2)");
      stmt.execute("insert into sample values('c', 3)");

      CallableStatement cs = conn.prepareCall("call PROC(?)");
      cs.setString(1, "b");
      cs.execute();
      ResultSet rs = cs.getResultSet();
      while (rs.next()) {
        System.out.println(rs.getString(1));
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail("Test failed because of exception " + e);
    } finally {
      if (conn != null) {
        TestUtil.stopNetServer();
      }
    }
  }

  public void testUseCase8Ticket_6024_2() {
    Statement stmt = null;

    Connection conn = null;
    try {

      conn = TestUtil.getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      stmt = conn.createStatement();

      // We create a table...

      stmt
          .execute(" create table sample (id varchar(16) primary key, num integer ) ");

      stmt
          .execute("CREATE PROCEDURE PROC( IN account varchar(8) )"
              + "MODIFIES SQL DATA  DYNAMIC RESULT SETS 1  PARAMETER STYLE JAVA  "
              + "LANGUAGE JAVA EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.Bugs2Test.proc'");
      stmt.execute("insert into sample values('a', 1)");
      stmt.execute("insert into sample values('b', 2)");
      stmt.execute("insert into sample values('c', 3)");

      CallableStatement cs = conn.prepareCall("call PROC(?)");
      cs.setString(1, "b");
      cs.execute();
      ResultSet rs = cs.getResultSet();
      while (rs.next()) {
        System.out.println(rs.getString(1));
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail("Test failed because of exception " + e);
    }

  }

  public static void proc(String param1, ResultSet[] outResults)
      throws SQLException {
    String queryString = "<local>" + "SELECT * FROM SAMPLE WHERE id = ?";
    // String queryString = LOCAL + "SELECT * FROM SAMPLE WHERE num = ?";

    Connection conn = DriverManager.getConnection("jdbc:default:connection");
    PreparedStatement ps = conn.prepareStatement(queryString);
    ps.setString(1, param1);
    ResultSet rs = ps.executeQuery();
    outResults[0] = rs;
  }

  public static void proc1(String param1, ResultSet[] outResults)
      throws SQLException {
    String queryString = "<local>" + "SELECT * FROM SAMPLE WHERE id = ?";

    Connection conn = null;
    conn = DriverManager.getConnection("jdbc:default:gemfirexd:connection");
    PreparedStatement ps = conn.prepareStatement(queryString);
    ps.setString(1, param1);
    ResultSet rs = ps.executeQuery();
    outResults[0] = rs;
  }

  // This test does not seem to be required since #41272 has a much more
  // comprehensive test in r43587. However, looking at this test it is not clear
  // how it is testing #41272 in the first place.
  public void __testBug41272() throws SQLException {

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    ArrayList statements = new ArrayList(); // list of Statements,
    // PreparedStatements
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = null;
    try {
      conn = getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      s = conn.createStatement();
      statements.add(s);

      // We create a table...

      s
          .execute(" create table trade.securities (sec_id int not null, "
              + "symbol varchar(10) not null, price decimal (30, 20), "
              + "exchange varchar(10) not null, tid int, constraint sec_pk "
              + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
              + " constraint exc_ch "
              + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))");
      s
          .execute(" create table trade.customers (cid int not null, cust_name varchar(100),"
              + " since date, addr varchar(100), tid int, primary key (cid))");
      s
          .execute(" create table trade.portfolio (cid int not null, sid int not null, "
              + "qty int not null, availQty int not null, subTotal decimal(30,20), tid int,"
              + " constraint portf_pk primary key (cid, sid), "
              + "constraint cust_fk foreign key (cid) references"
              + " trade.customers (cid) on delete restrict, "
              + "constraint sec_fk foreign key (sid) references trade.securities (sec_id),"
              + " constraint qty_ck check (qty>=0),"
              + " constraint avail_ch check (availQty>=0 and availQty<=qty))");
      s.execute(" create table trade.sellorders (oid int not null constraint "
          + "orders_pk primary key, cid int, sid int, qty int, tid int,"
          + " constraint portf_fk foreign key (cid, sid) references "
          + "trade.portfolio (cid, sid) on delete restrict )");
      // and add a few rows...
      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");
      statements.add(psInsert4);
      for (int i = -2; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, "lse");
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into trade.customers values (?, ?,?,?,?)");
      statements.add(psInsert1);
      for (int i = 1; i < 2; ++i) {
        psInsert1.setInt(1, i);
        psInsert1.setString(2, "name" + i);
        psInsert1.setDate(3, new java.sql.Date(2004, 12, 1));
        psInsert1.setString(4, "address" + i);
        psInsert1.setInt(5, 2);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into trade.portfolio values (?, ?,?,?,?,?)");
      statements.add(psInsert2);

      for (int i = 1; i < 2; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(3, i + 10000);
        psInsert2.setInt(4, i + 1000);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setInt(6, 2);
        for (int j = -2; j < 0; ++j) {
          psInsert2.setInt(2, j);
          assertEquals(1, psInsert2.executeUpdate());
        }

      }

      psInsert3 = conn
          .prepareStatement("insert into trade.sellorders values (?, ?,?,?,?)");
      statements.add(psInsert3);
      int j = -1;
      for (int i = 1; i < 2; ++i) {
        psInsert3.setInt(1, i);
        psInsert3.setInt(2, i);
        psInsert3.setInt(3, j--);
        if (j == -3) {
          j = -1;
        }
        psInsert3.setInt(4, i + 100);
        psInsert3.setInt(5, 2);

        assertEquals(1, psInsert3.executeUpdate());

        // assertEquals(1,psInsert3.executeUpdate());
      } //
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
                OpenMemIndex memIndex, double optimzerEvalutatedCost) {
              /* if(memIndex.getConglomerate() instanceof Hash1Index && memIndex.getBaseContainer().getRegion().getName().toLowerCase().indexOf("customers") != -1) {
                 return 10000;
               }*/
              return Double.MAX_VALUE;
              // return optimzerEvalutatedCost;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimzerEvalutatedCost) {
              /*if(gfContainer.getRegion().getName().toLowerCase().indexOf("sellorder") != -1) {
                return 100000;
              }else */
              if (gfContainer.getRegion().getName().toLowerCase()
                  .indexOf("customer") != -1) {
                return 200;
              }
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimzerEvalutatedCost) {
              if (memIndex.getBaseContainer().getRegion().getName()
                  .toLowerCase().indexOf("portfolio") != -1) {
                if (memIndex.getGemFireContainer().getQualifiedTableName()
                    .toLowerCase().indexOf("__portfolio__cid:") != -1) {
                  return 200;
                }
              } else if (memIndex.getBaseContainer().getRegion().getName()
                  .toLowerCase().indexOf("sellorders") != -1) {
                return 10;// optimzerEvalutatedCost;
              }
              return Double.MAX_VALUE;
            }

          });

      rs = s.executeQuery(" select c.cid, f.sid,so.sid from trade.customers "
          + "c, trade.portfolio f, trade.sellorders so where "
          + "c.cid = f.cid and c.cid = so.cid and f.tid = so.tid "
          + "and c.tid=2 order by f.sid desc");
      ResultSetMetaData rsmd = rs.getMetaData();

      assertTrue(rs.next());
      assertEquals(1, ((Integer)rs.getObject(rsmd.getColumnName(1))).intValue());
      int f_sid = ((Integer)rs.getObject(rsmd.getColumnName(2))).intValue();
      int so_sid = ((Integer)rs.getObject(rsmd.getColumnName(2))).intValue();
      assertEquals(so_sid, f_sid);
      assertEquals(f_sid, -1);

      assertTrue(rs.next());
      assertEquals(1, ((Integer)rs.getObject(rsmd.getColumnName(1))).intValue());
      f_sid = ((Integer)rs.getObject(rsmd.getColumnName(2))).intValue();
      so_sid = ((Integer)rs.getObject(rsmd.getColumnName(2))).intValue();
      assertTrue(so_sid != f_sid);
      assertEquals(f_sid, -2);
      assertEquals(so_sid, -1);

      /*
      while(rs.next()) {
        int n= rsmd.getColumnCount();
        System.out.println( "++++++++++++++++++++++++");
        for(int i =1; i < (n+1);++i) {

         System.out.print( rsmd.getColumnName(i) + " = " + rs.getObject(rsmd.getColumnName(i)) + "; ");
        }
        System.out.println( "--------------------------------");
      }*/

      conn.commit();

    } finally {
      // release all open resources to avoid unnecessary memory usage

      // ResultSet
      if (rs != null) {
        rs.close();
        rs = null;
      }

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

      // Connection
      if (conn != null) {
        conn.close();
        conn = null;
      }
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }

  public void testBug41262() throws Exception {

    final Connection conn = getConnection();
    final DatabaseMetaData metadata = conn.getMetaData();
    ResultSet rs = metadata.getTypeInfo();
    assertTrue(rs.next());
    // consume all results to properly release the locks
    while (rs.next())
      ;
    Runnable run = new Runnable() {
      public void run() {
        try {
          Statement stmt = conn.createStatement();
          stmt
              .execute("create table ctstable1 (TYPE_ID int, TYPE_DESC varchar(32) ,"
                  + "primary key(TYPE_ID))");
          stmt
              .execute("create table ctstable2 (KEY_ID int, COF_NAME varchar(32), "
                  + "PRICE float, TYPE_ID int, primary key(KEY_ID), "
                  + "foreign key(TYPE_ID) references ctstable1)");
        } catch (Exception ex) {
          exceptionOccured = true;
          throw GemFireXDRuntimeException.newRuntimeException(null, ex);
        }
      }
    };

    Thread thr = new Thread(run);
    thr.start();
    thr.join();
    assertFalse(exceptionOccured);
    rs = metadata.getSchemas();
    assertTrue(rs.next());
    // consume all results to properly release the locks
    while (rs.next())
      ;
    run = new Runnable() {
      public void run() {
        try {
          final Statement stmt = conn.createStatement();
          stmt.execute("Insert into ctstable1 values(1,'addr')");
        } catch (Exception ex) {
          exceptionOccured = true;
          throw GemFireXDRuntimeException.newRuntimeException(null, ex);
        }
      }
    };

    thr = new Thread(run);
    thr.start();
    thr.join();
    assertFalse(exceptionOccured);
    rs = metadata.getTableTypes();
    assertTrue(rs.next());
    // consume all results to properly release the locks
    while (rs.next())
      ;
    run = new Runnable() {
      public void run() {
        try {
          Statement stmt = conn.createStatement();
          assertEquals(
              1,
              stmt
                  .executeUpdate("update ctstable1 set type_desc ='addr1' where type_id =1"));
        } catch (Exception ex) {
          exceptionOccured = true;
          throw GemFireXDRuntimeException.newRuntimeException(null, ex);
        }
      }
    };

    thr = new Thread(run);
    thr.start();
    thr.join();
    assertFalse(exceptionOccured);

    rs = metadata.getPrimaryKeys(null, null, "CTSTABLE1");
    assertTrue(rs.next());
    // consume all results to properly release the locks
    while (rs.next())
      ;
    run = new Runnable() {
      public void run() {
        try {
          PreparedStatement ps = conn
              .prepareStatement("select * from ctstable1 where type_id = ?");
          ps.setInt(1, 1);
          ResultSet rs1 = ps.executeQuery();
          assertTrue(rs1.next());
          assertFalse(rs1.next());
        } catch (Exception ex) {
          exceptionOccured = true;
          throw GemFireXDRuntimeException.newRuntimeException(null, ex);
        }
      }
    };

    thr = new Thread(run);
    thr.start();
    thr.join();
    assertFalse(exceptionOccured);

    rs = metadata.getPrimaryKeys(null, null, "CTSTABLE1");
    assertTrue(rs.next());
    // consume all results to properly release the locks
    while (rs.next())
      ;
    run = new Runnable() {
      public void run() {
        try {
          Statement stmt = conn.createStatement();
          try {
            stmt
                .execute("create table ctstable3 (TYPE_ID int, TYPE_ID varchar(32) ,"
                    + "primary key(TYPE_ID))");
            throw new AssertionError("table creation should  have failed");
          } catch (SQLException expected) {

          }
          stmt
              .execute("create table ctstable3 (KEY_ID int, COF_NAME varchar(32), "
                  + "PRICE float, TYPE_ID int, primary key(KEY_ID), "
                  + "foreign key(TYPE_ID) references ctstable1)");
        } catch (Throwable ex) {
          exceptionOccured = true;
          throw GemFireXDRuntimeException.newRuntimeException(null, ex);
        }
      }
    };

    thr = new Thread(run);
    thr.start();
    thr.join();
    assertFalse(exceptionOccured);
  }

  public void testBug41642() throws Exception {

    Statement stmt = null;

    Connection conn = null;
    Statement derbyStmt = null;
    try {

      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);

      derbyStmt = derbyConn.createStatement();
      derbyStmt
          .execute(" create table derby_sample (id varchar(16) primary key, num integer ) ");
      Statement derbyStmt1 = derbyConn.createStatement();
      derbyStmt1.executeUpdate("insert into derby_sample values('asif',1)");
      conn = TestUtil.getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      stmt = conn.createStatement();

      // We create a table...

      stmt
          .execute(" create table sample (id varchar(16) primary key, num integer ) ");

      stmt
          .execute("CREATE PROCEDURE PROC1( IN account varchar(8) )"
              + "MODIFIES SQL DATA  DYNAMIC RESULT SETS 1  PARAMETER STYLE JAVA  "
              + "LANGUAGE JAVA EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.Bugs2Test.proc1'");
      stmt.execute("insert into sample values('a', 1)");
      stmt.execute("insert into sample values('b', 2)");
      stmt.execute("insert into sample values('c', 3)");

      CallableStatement cs = conn.prepareCall("call PROC1(?)");
      cs.setString(1, "b");
      cs.execute();
      ResultSet rs = cs.getResultSet();
      while (rs.next()) {
        System.out.println(rs.getString(1));
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail("Test failed because of exception " + e);
    } finally {
      if (derbyStmt != null) {
        try {
          derbyStmt.execute("drop table derby_sample");
        } catch (Exception e) {
          // ignore intentionally
        }
        try {
          derbyStmt.execute("drop procedure PROC1");
        } catch (Exception e) {
          // ignore intentionally
        }
      }
    }
  }

  private void waitForDeleteEvent(final boolean[] ok)
      throws InterruptedException {
    int maxWait = 60000;
    synchronized (this) {
      while (!ok[0] && maxWait > 0) {
        this.wait(100);
        maxWait -= 100;
      }
      if (!ok[0]) {
        fail("failed to get AFTER_DELETE event");
      }
    }
  }

  public void testBug41027() throws Exception {
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;
    Properties props = new Properties();
    props.setProperty("server-groups", "SG1");

    Connection conn = null;
    String derbyDbUrl = "jdbc:derby:newDB;create=true;";
    conn = getConnection(props);
    final int isolation = conn.getTransactionIsolation();
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    Statement derbyStmt = derbyConn.createStatement();
    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(10) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))";
      String tab2 = " create table trade.customers (cid int not null, cust_name varchar(100),"
          + " since date, addr varchar(100), tid int, primary key (cid))";

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_cust_fk foreign key (cid) references trade.customers (cid),"
          + " constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)"
          + " on delete restrict, constraint bo_qty_ck check (qty>=0))  ";

      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      derbyStmt.execute(tab1);
      derbyStmt.execute(tab2);
      derbyStmt.execute(tab3);
      JdbcTestBase.addAsyncEventListener("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          new Integer(2), null, null, null, null, Boolean.FALSE, Boolean.FALSE, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl);
      JdbcTestBase.startAsyncEventListener("WBCL1");

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1 + "AsyncEventListener(WBCL1)");
      s.execute(tab2 + "AsyncEventListener(WBCL1)");
      s.execute(tab3
          + " partition by range (bid) (VALUES BETWEEN 0.0 AND 25.0,  "
          + "VALUES BETWEEN 25.0  AND 35.0 , VALUES BETWEEN 35.0  AND 49.0,"
          + " VALUES BETWEEN 49.0  AND 69.0 , VALUES BETWEEN 69.0 AND 100.0) "
          + "AsyncEventListener(WBCL1)");
      final boolean[] ok = new boolean[] { false };
      // and add a few rows...
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {

            @Override
            public void afterBulkOpDBSynchExecution(Event.Type type,
                int numRowsModified, Statement ps, String dml) {
              if (isolation != Connection.TRANSACTION_NONE
                  && dml.startsWith("delete from ")) {
                synchronized (Bugs2Test.this) {
                  ok[0] = true;
                  Bugs2Test.this.notify();
                }
              }
            }

            @Override
            public void afterPKBasedDBSynchExecution(Event.Type type,
                int numRowsModified, Statement ps) {
              if (isolation == Connection.TRANSACTION_NONE
                  && type.equals(Event.Type.AFTER_DELETE)) {

                synchronized (Bugs2Test.this) {
                  ok[0] = true;
                  Bugs2Test.this.notify();
                }
              }
            }
          });

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -30; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into trade.customers values (?, ?,?,?,?)");

      for (int i = 1; i < 30; ++i) {
        psInsert1.setInt(1, i);
        psInsert1.setString(2, "name" + i);
        psInsert1.setDate(3, new java.sql.Date(2004, 12, 1));
        psInsert1.setString(4, "address" + i);
        psInsert1.setInt(5, 2);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 20; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "open");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }

      String updateStr = "update trade.buyorders set status = 'cancelled' , cid = 1 +cid "
          + " where cid >? and cid <?";
      psInsert3 = conn.prepareStatement(updateStr);
      psInsert3.setInt(1, 5);
      psInsert3.setInt(2, 28);
      psInsert3.executeUpdate();
      PreparedStatement ps = conn
          .prepareStatement("delete from trade.buyorders where oid = 1 ");
      ps.executeUpdate();

      waitForDeleteEvent(ok);

      if (derbyStmt != null) {
        try {
          derbyStmt.execute("drop table trade.buyorders");
        } catch (Exception e) {
        }
        try {
          derbyStmt.execute("drop table trade.customers");
        } catch (Exception e) {
        }
        try {
          derbyStmt.execute("drop table trade.securities");
        } catch (Exception e) {
        }
      }
      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        s.execute("drop table if exists trade.customers");
        s.execute("drop table if exists trade.securities");
      }

    } finally {
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testBug41620_1() throws Exception {
    Connection conn = null;
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      //Sets the server group as property
      Properties info = new Properties();
      info.setProperty("server-groups", "SG1");
      conn = getConnection(info);
      conn = TestUtil.startNetserverAndGetLocalNetConnection();
      JdbcTestBase.addAsyncEventListenerWithConn("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          new Integer(2), null, null, null, null, null, null, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl, conn);
      JdbcTestBase.startAsyncEventListener("WBCL1");
      AsyncEventQueueImpl queue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("WBCL1");
      assertNotNull(queue);
      assertTrue(queue.isRunning());
    } finally {
      TestUtil.stopNetServer();
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testBug41620_2() throws Exception {
    Connection conn = null;
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      Properties info = new Properties();
      info.setProperty("server-groups", "SG1");
      conn = getConnection(info);
      conn = TestUtil.startNetserverAndGetLocalNetConnection();
      JdbcTestBase.addAsyncEventListenerWithConn("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          new Integer(2), null, Boolean.TRUE, null, null, null, null, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl, conn);
      JdbcTestBase.startAsyncEventListener("WBCL1");
      AsyncEventQueueImpl queue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("WBCL1");
      assertNotNull(queue);
      assertTrue(queue.getSender().getCancelCriterion()
          .cancelInProgress() == null);
    } finally {
      TestUtil.stopNetServer();
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testDataPersistenceOfPR() throws Exception {
    Properties props = new Properties();

    this.deleteDirs = new String[] { "datadictionary", "test_dir" };
    Connection conn = TestUtil.getConnection(props);
    TestUtil.deletePersistentFiles = false;
    // Creating a statement object that we can use for running various
    // SQL statements commands against the database.
    Statement stmt = conn.createStatement();
    // We create a table...
    stmt.execute("create schema trade");
    stmt.execute("create diskstore teststore './test_dir'");
    stmt
        .execute(" create table trade.customers (cid int not null, cust_name varchar(100), tid int, " +
            "primary key (cid))   partition by range (cid) ( VALUES BETWEEN 0 AND 10," +
            " VALUES BETWEEN 10 AND 20, VALUES BETWEEN 20 AND 30 )  PERSISTENT 'teststore'");
    /*stmt
    .execute(" create table trade.customers (cid int not null, cust_name varchar(100), tid int, " +
    "primary key (cid))  replicate  PERSISTENT diskdir ('."+
    '/' + "test_dir1')");*/
    // Create a schema


    PreparedStatement ps = conn.prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 31; ++i) {
      ps.setInt(1, i);
      ps.setString(2, "name" + i);
      ps.setInt(3, i);
      ps.executeUpdate();
    }
    TestUtil.shutDown();
    TestUtil.deletePersistentFiles = true;
    TestUtil.setupConnection();
    conn = TestUtil.getConnection();
    stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("select * from trade.customers");
    int expected = 465;
    int actual = 0;
    while (rs.next()) {
      int val = rs.getInt(1);
      actual += val;
    }
    assertEquals(expected, actual);
  }

  public void testBug41804() throws Exception {

    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    ArrayList statements = new ArrayList(); // list of Statements,
    // PreparedStatements
    PreparedStatement psInsert1, psInsert2, psInsert4 = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = null;
    try {
      conn = getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      s = conn.createStatement();
      statements.add(s);

      // We create a table...

      s
          .execute(" create table trade.securities (sec_id int not null, "
              + "symbol varchar(10) not null, price decimal (30, 20), "
              + "exchange varchar(10) not null, tid int, constraint sec_pk "
              + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
              + " constraint exc_ch "
              + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))");
      s
          .execute(" create table trade.customers (cid int not null, cust_name varchar(100),"
              + " since date, addr varchar(100), tid int, primary key (cid))");
      new File("persistPortf");
      s.execute("create diskstore teststore 'persistPortf'");
      s
          .execute("create table trade.portfolio (cid int not null, sid int not null," +
              " qty int not null, availQty int not null, subTotal decimal(30,20), tid int," +
              " constraint portf_pk primary key (cid, sid), constraint cust_fk foreign key (cid)" +
              " references trade.customers (cid) on delete restrict," +
              " constraint sec_fk foreign key (sid) references trade.securities (sec_id) on delete restrict," +
              " constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty)) " +
              " partition by column (cid, sid) PERSISTENT SYNCHRONOUS 'teststore'");

      // and add a few rows...
      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");
      statements.add(psInsert4);
      for (int i = -2; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, "lse");
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into trade.customers values (?, ?,?,?,?)");
      statements.add(psInsert1);
      for (int i = 1; i < 2; ++i) {
        psInsert1.setInt(1, i);
        psInsert1.setString(2, "name" + i);
        psInsert1.setDate(3, new java.sql.Date(2004, 12, 1));
        psInsert1.setString(4, "address" + i);
        psInsert1.setInt(5, 2);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into trade.portfolio values (?, ?,?,?,?,?)");
      statements.add(psInsert2);

      for (int i = 1; i < 2; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(3, i + 10000);
        psInsert2.setInt(4, i + 1000);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setInt(6, 2);
        for (int j = -2; j < 0; ++j) {
          psInsert2.setInt(2, j);
          assertEquals(1, psInsert2.executeUpdate());
        }

      }
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
                OpenMemIndex memIndex, double optimzerEvalutatedCost) {

              return Double.MAX_VALUE;
              // return optimzerEvalutatedCost;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimzerEvalutatedCost) {
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimzerEvalutatedCost) {
              if (memIndex.getBaseContainer().getRegion().getName()
                  .toLowerCase().indexOf("portfolio") != -1) {
                return 0;
              }

              return Double.MAX_VALUE;
            }

          });
      // assertEquals(1,psInsert3.executeUpdate());
      //
      rs = s.executeQuery(" SELECT *  FROM TRADE.PORTFOLIO WHERE CID > 0 AND SID >-3 AND TID = 2");
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));

      conn.commit();

    } finally {
      // release all open resources to avoid unnecessary memory usage

      // ResultSet
      if (rs != null) {
        rs.close();
        rs = null;
      }

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
      deleteDir(new File("persistPortf"));
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }

  public void testBug41926() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table numeric_family (id int primary key, type_int int)");
    s.execute("insert into numeric_family values (1, 2), (2, 4), (3, 6), (4, 8)");

    try {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
            }
          });

      String query = "select id from numeric_family where id <= 4 order by id asc fetch " +
          "first 1 rows only";
      s.execute(query);


    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }

  public void testBug41936() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table numeric_family (id int primary key, type_int int)");
    String query = "select * from numeric_family where ? <> ALL (values(1))";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setInt(1, 1);
    ps.executeQuery();
  }

  public void testBug41873_1() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null, c4 int not null, "
        + "primary key(c1)) replicate");
    conn.commit();

    st.execute("insert into t1 values (1, 1,1,1)");
    conn.commit();
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    conn.commit();
    ResultSet rs = st.executeQuery("Select * from t1");
    rs.next();
    assertEquals(1, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertEquals(3, rs.getInt(3));
    assertEquals(4, rs.getInt(4));
  }

  public void testBug41873_3() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null, c4 int not null, "
        + "primary key(c1)) ");
    conn.commit();

    st.execute("insert into t1 values (1, 1,1,1)");
    conn.commit();
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    conn.commit();
    ResultSet rs = st.executeQuery("Select * from t1");
    rs.next();
    assertEquals(1, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertEquals(3, rs.getInt(3));
    assertEquals(4, rs.getInt(4));
  }

  public void testBug41873_2() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null, c4 int not null, "
        + "primary key(c1)) replicate");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1)");
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    conn.commit();
    ResultSet rs = st.executeQuery("Select * from t1");
    rs.next();
    assertEquals(1, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertEquals(3, rs.getInt(3));
    assertEquals(4, rs.getInt(4));
  }

  public void testBug41873_4() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null, c4 int not null, "
        + "primary key(c1)) ");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1)");
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    conn.commit();
    ResultSet rs = st.executeQuery("Select * from t1");
    rs.next();
    assertEquals(1, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertEquals(3, rs.getInt(3));
    assertEquals(4, rs.getInt(4));
  }

  //select via table scan. With Bug 42099 opne, the selects expect
  // commited data , so let the test validate that behaviour . once it is fixed
  // the test should be modified to validate uncommitted data behaviour

  public void testBug42099_1() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
        " c4 int not null, c5 int not null, primary key(c1)) ");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1,1)");
    conn.commit();
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    st.execute("update t1 set c5 =5 where c5 =1");
    ResultSet rs = st.executeQuery("Select * from t1");
    validateForBug42099(rs, false /* expect committed+TX data*/);
    conn.commit();
  }

  //select via PK based look up
  public void testBug42099_2() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
        " c4 int not null, c5 int not null, primary key(c1)) ");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1,1)");
    conn.commit();
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    st.execute("update t1 set c5 =5 where c5 =1");
    ResultSet rs = st.executeQuery("Select * from t1 where c1 =1");
    validateForBug42099(rs, false /* expect committed+TX data*/);
    conn.commit();
  }

  //select via local index lookup
  public void testBug42099_3() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
        " c4 int not null , c5 int not null,c6 int not null, " +
        " primary key(c1)) ");
    st.execute("create index i1 on t1 (c6)");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1,1,1)");
    conn.commit();
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    st.execute("update t1 set c5 =5 where c5 =1");
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost) {
        return Double.MAX_VALUE;
      }

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
    };
    GemFireXDQueryObserverHolder.setInstance(observer);
    try {
      ResultSet rs = st.executeQuery("Select * from t1 where c6 =1");
      validateForBug42099(rs, false /* expect TX data*/);
      conn.commit();
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testBug42430_1() throws Exception {
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    final int isolation = conn.getTransactionIsolation();
    Statement s = conn.createStatement();
    Statement derbyStmt = null;
    final int[] numInserts = new int[] { 0 };
    final int[] numPKDeletes = new int[] { 0 };
    final int[] numBulkOp = new int[] { 0 };
    final boolean[] ok = new boolean[] { false };
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt
          .execute("create table testtable (id int primary key, type_int int) ");

      JdbcTestBase.addAsyncEventListener("SG1", currentTest,
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer", new Integer(
              10), null, null, null, null, Boolean.FALSE, Boolean.FALSE, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl);
      JdbcTestBase.startAsyncEventListener(currentTest);

      s.execute("create table testtable (id int primary key, type_int int) "
          + "AsyncEventListener (" + currentTest + ')');
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void afterBulkOpDBSynchExecution(Event.Type type,
                int numRowsModified, Statement ps, String dml) {
              numBulkOp[0] += 1;
              if (dml.startsWith("insert into ")) {
                numInserts[0] += numRowsModified;
              } else if (isolation != Connection.TRANSACTION_NONE
                  && dml.startsWith("delete from ")) {
                numPKDeletes[0] += numRowsModified;
                synchronized (Bugs2Test.this) {
                  ok[0] = true;
                  Bugs2Test.this.notify();
                }
              }
            }

            @Override
            public void afterPKBasedDBSynchExecution(Event.Type type,
                int numRowsModified, Statement ps) {
              if (isolation == Connection.TRANSACTION_NONE
                  && type.equals(Event.Type.AFTER_DELETE)) {
                numPKDeletes[0] += numRowsModified;
                synchronized (Bugs2Test.this) {
                  Bugs2Test.this.notify();
                  ok[0] = true;
                }
              } else if (type.equals(Event.Type.AFTER_INSERT)) {
                numInserts[0] += numRowsModified;
              }
            }
          });
      String schema = TestUtil.getCurrentDefaultSchemaName();
      String path = Misc.getRegionPath(schema, "TESTTABLE", null);
      LocalRegion rgn = (LocalRegion)Misc.getRegion(path, true, false);
      final boolean[] callbackExecuted = new boolean[] { false };
      rgn.getAttributesMutator().setCacheWriter(new CacheWriterAdapter() {
        @Override
        public void beforeCreate(EntryEvent event) throws CacheWriterException {
          callbackExecuted[0] = true;
          Object callbackArg = event.getCallbackArgument();
          assert callbackArg != null
              && callbackArg instanceof GfxdCallbackArgument;
        }
      });
      s.execute("insert into TESTTABLE values (1, 2), (2, 4), (3, 6), (4, 8)");
      assertTrue(callbackExecuted[0]);
      // PK based Delete
      assertEquals(1, s.executeUpdate("delete from testtable where ID = 1"));

      waitForDeleteEvent(ok);

      assertEquals(4, numInserts[0]);
      assertEquals(1, numPKDeletes[0]);
      JdbcTestBase.stopAsyncEventListener(currentTest);
      validateResults(derbyStmt, s, "select * from testtable", false);
    } finally {
      if (derbyStmt != null) {
        derbyStmt.execute("drop table TESTTABLE");
      }
      if (s != null) {
        s.execute("drop table TESTTABLE");
      }
      /*
       * try { DriverManager.getConnection("jdbc:derby:;shutdown=true");
       * }catch(SQLException sqle) { if(sqle.getMessage().indexOf("shutdown") ==
       * -1) { sqle.printStackTrace(); throw sqle; } }
       */
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testBug42430_2() throws Exception {
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    final int isolation = conn.getTransactionIsolation();
    Statement s = conn.createStatement();
    Statement derbyStmt = null;
    final int[] numInserts = new int[] { 0 };
    final int[] numPKDeletes = new int[] { 0 };
    final int[] numBulkOp = new int[] { 0 };
    final boolean[] ok = new boolean[] { false };
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt
          .execute("create table testtable (id int primary key, type_int int) ");

      JdbcTestBase.addAsyncEventListener("SG1", currentTest,
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer", new Integer(
              10), null, null, null, null, Boolean.FALSE, Boolean.FALSE, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl);
      JdbcTestBase.startAsyncEventListener(currentTest);

      s.execute("create table testtable (id int primary key, type_int int) replicate "
          + " AsyncEventListener (" + currentTest + ')');
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void afterBulkOpDBSynchExecution(Event.Type type,
                int numRowsModified, Statement ps, String dml) {
              numBulkOp[0] += 1;
              if (dml.startsWith("insert into ")) {
                numInserts[0] += numRowsModified;
              } else if (isolation != Connection.TRANSACTION_NONE
                  && dml.startsWith("delete from ")) {
                numPKDeletes[0] += numRowsModified;
                synchronized (Bugs2Test.this) {
                  ok[0] = true;
                  Bugs2Test.this.notify();
                }
              }
            }

            @Override
            public void afterPKBasedDBSynchExecution(Event.Type type,
                int numRowsModified, Statement ps) {
              if (isolation == Connection.TRANSACTION_NONE
                  && type.equals(Event.Type.AFTER_DELETE)) {
                numPKDeletes[0] += numRowsModified;
                synchronized (Bugs2Test.this) {
                  ok[0] = true;
                  Bugs2Test.this.notify();
                }
              } else if (type.equals(Event.Type.AFTER_INSERT)) {
                numInserts[0] += numRowsModified;
              }
            }
          });
      String schema = TestUtil.getCurrentDefaultSchemaName();
      String path = Misc.getRegionPath(schema, "TESTTABLE", null);
      LocalRegion rgn = (LocalRegion)Misc.getRegion(path, true, false);
      final boolean[] callbackExecuted = new boolean[] { false };
      rgn.getAttributesMutator().setCacheWriter(new CacheWriterAdapter() {
        @Override
        public void beforeCreate(EntryEvent event) throws CacheWriterException {
          callbackExecuted[0] = true;
          Object callbackArg = event.getCallbackArgument();
          assert callbackArg != null
              && callbackArg instanceof GfxdCallbackArgument : callbackArg;
        }
      });
      s.execute("insert into TESTTABLE values (1, 2), (2, 4), (3, 6), (4, 8)");
      assertTrue(callbackExecuted[0]);
      // PK based Delete
      s.execute("delete from testtable where ID = 1");

      waitForDeleteEvent(ok);

      assertEquals(4, numInserts[0]);
      assertEquals(1, numPKDeletes[0]);
      JdbcTestBase.stopAsyncEventListener(currentTest);
      validateResults(derbyStmt, s, "select * from testtable", false);
    } finally {
      if (derbyStmt != null) {
        derbyStmt.execute("drop table TESTTABLE");
      }
      if (s != null && !conn.isClosed()) {
        s.execute("drop table TESTTABLE");
      }
      /*
       * try { DriverManager.getConnection("jdbc:derby:;shutdown=true");
       * }catch(SQLException sqle) { if(sqle.getMessage().indexOf("shutdown") ==
       * -1) { sqle.printStackTrace(); throw sqle; } }
       */
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testBug42100() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
        " c4 int not null , c5 int not null ," +
        " primary key(c1)) ");
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1,1)");
    conn.commit();
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost) {
        return Double.MAX_VALUE;
      }

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
    };
    GemFireXDQueryObserverHolder.setInstance(observer);
    st.execute("update t1 set c2 =2 where c2 =1");
    st.execute("update t1 set c3 =3 where c3 =1");
    st.execute("update t1 set c4 =4 where c4 =1");
    st.execute("update t1 set c5 =5 where c5 =1");

    try {
      ResultSet rs = st.executeQuery("Select * from t1 where c1 > 0");
      // Index scan works correctly now since it does not suspend TX.
      validateForBug42099(rs, false /* expect committed data*/);
      rs = st.executeQuery("Select * from t1 where c1 =1");
      // get convertible queries should not suspend TX in GemFireResultSet
      validateForBug42099(rs, false /* expect committed+TX data*/);
      conn.commit();
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testBug42100_1() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
        " c4 int not null , c5 int not null ," +
        " primary key(c1)) ");
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1,1)");
    st.execute("insert into t1 values (2, 2,2,2,2)");
    st.execute("insert into t1 values (3, 3,3,3,3)");
    st.execute("insert into t1 values (4, 4,4,4,4)");
    conn.commit();

    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost) {
        return Double.MAX_VALUE;
      }

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
    };
    GemFireXDQueryObserverHolder.setInstance(observer);
    assertEquals(1, st.executeUpdate("update t1 set c5 =5 where c5 =1"));
    assertEquals(1, st.executeUpdate("update t1 set c5 =6 where c5 = 5"));
    conn.commit();
    try {
      ResultSet rs = st.executeQuery("Select c5 from t1 where c1 =1");
      assertTrue(rs.next());
      assertEquals(6, rs.getInt(1));
      conn.commit();
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  /**
   * Bug discovered during debugging of 42323
   * <p>
   * RENAME not yet supported.
   *
   * @throws Exception
   */
  public void DISABLED_testNewBug_42323_1() throws Exception {
    java.sql.Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema trade");
    st
        .execute("Create table trade.t1 (c1 int not null , c2 int not null, c3 int not null,"
            + " c4 int not null , c5 int not null ," + " primary key(c1)) ");
    st.execute("create index trade.index7_4 on trade.t1 ( c5 )");
    st.execute("rename index trade.index7_4 to index7_4a ");
    ResultSet rs = conn.getMetaData().getIndexInfo(null, "TRADE", "T1", false,
        true);
    boolean foundOld = false;
    boolean foundNew = false;
    // ResultSetMetaData rsmd = rs.getMetaData();
    while (rs.next()) {
      String indexName = rs.getString(6);
      if (indexName.equals("INDEX7_4A")) {
        foundNew = true;
      } else if (indexName.equals("INDEX7_4")) {
        foundOld = true;
      }
    }

    assertTrue(foundNew);
    assertFalse(foundOld);
    rs.close();
    conn.commit();
    foundNew = false;
    foundOld = false;
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    rs = conn.getMetaData().getIndexInfo(null, "TRADE", "T1", false, true);
    // rsmd = rs.getMetaData();
    while (rs.next()) {
      String indexName = rs.getString(6);
      if (indexName.equals("INDEX7_4A")) {
        foundNew = true;
      } else if (indexName.equals("INDEX7_4")) {
        foundOld = true;
      }
    }
    assertTrue(foundNew);
    assertFalse(foundOld);
  }

  /**
   * RENAME not yet supported.
   */
  public void DISABLED_testNewBug_42323_2() throws Exception {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st.execute("create schema trade");
    st
        .execute("Create table trade.t1 (c1 int not null , c2 int not null, c3 int not null,"
            + " c4 int not null , c5 int not null ," + " primary key(c1)) ");
    st.execute("create index trade.index7_4 on trade.t1 ( c5 )");
    st.execute("rename index trade.index7_4 to index7_4a ");
    ResultSet rs = conn.getMetaData().getIndexInfo(null, "TRADE", "T1", false,
        true);
    boolean foundOld = false;
    boolean foundNew = false;
    //ResultSetMetaData rsmd = rs.getMetaData();
    while (rs.next()) {
      String indexName = rs.getString(6);
      if (indexName.equals("INDEX7_4A")) {
        foundNew = true;
      } else if (indexName.equals("INDEX7_4")) {
        foundOld = true;
      }
    }

    assertTrue(foundNew);
    assertFalse(foundOld);
    rs.close();
    conn.commit();
  }

  public void testBug42783_1() throws Exception {

    java.sql.Connection conn = getConnection();
    Statement st = conn.createStatement();
    try {
      st.executeUpdate("create table test(id char(10), "
          + "c10 char(10), vc10 varchar(10)) replicate");
      PreparedStatement insert = getConnection().prepareStatement(
          "insert into test values (?,?,?)");
      String[][] values = new String[][] {
          { "1", "abc", "def" },
          { "2", "ghi", "jkl" },
          { "3", "mno", "pqr" },
          { "4", "stu", "vwx" },
          { "5", "yza", "bcd" },
          { "8", "abc dkp", "hij" },
          { "9", "ab", "hij" },
          { "9", "abcd efg", "hij" },
      };
      for (int i = 0; i < values.length; i++) {
        String elements[] = values[i];
        for (int j = 0; j <= 2; j++) {
          insert.setString(j + 1, elements[j]);
        }
        insert.executeUpdate();
      }
      insert.setString(1, "V-NULL");
      insert.setString(2, null);
      insert.setString(3, null);
      insert.executeUpdate();
      insert.setString(1, "MAX_CHAR");
      insert.setString(2, "\uFA2D");
      insert.setString(3, "\uFA2D");
      insert.executeUpdate();
      PreparedStatement ps1 = conn
          .prepareStatement("select id from test where c10 like ?");
      ResultSet rs = null;
      int count = 0;
      ps1.setObject(1, "");
      rs = ps1.executeQuery();
      rs.next();
      ps1.setObject(1, "%");
      rs = ps1.executeQuery();
      count = 0;
      while (rs.next()) {
        ++count;
      }
      assertEquals(count, 9);

      ps1.setObject(1, "ab%");
      rs = ps1.executeQuery();
      count = 0;
      while (rs.next()) {
        ++count;
      }
      assertEquals(count, 4);
      rs.close();
      PreparedStatement ps2 = conn.prepareStatement("select id from test where vc10 like ?");
      count = 0;
      ps2.setObject(1, "%");
      rs = ps2.executeQuery();
      while (rs.next()) {
        ++count;
      }
      assertEquals(count, 9);

      rs.close();
    } finally {
      st.execute("drop table test");
    }

  }

  public void testBug42977() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    java.sql.Connection conn = getConnection();
    Statement st = conn.createStatement();
    String schema = TestUtil.getCurrentDefaultSchemaName();
    try {
      st.executeUpdate(" create table customers (cid int not null, cust_name varchar(100), " +
          " addr varchar(100), tid int, primary key (cid))  partition by list (tid) " +
          "(VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17))");
      String path = Misc.getRegionPath(schema, "CUSTOMERS", null);
      PartitionedRegion rgn = (PartitionedRegion)Misc.getRegion(path, true, false);
      GfxdPartitionResolver spr = (GfxdPartitionResolver)rgn.getPartitionResolver();
      ((Integer)spr.getRoutingKeyForColumn(new SQLInteger(55))).intValue();
      PreparedStatement insert = getConnection().prepareStatement(
          "insert into customers values (?,?,?,?)");
      insert.setInt(1, 97);
      insert.setString(2, "name_97");
      insert.setString(3, "addr_97");
      insert.setInt(4, 55);
      insert.executeUpdate();
      ResultSet rs = st.executeQuery("select  addr, cust_name from customers where  tid = 55");
      assertTrue(rs.next());
    } finally {
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
      st.execute("drop table customers");
    }
  }

  public void testBug43006_1() throws Exception {
    Properties props = new Properties();
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    java.sql.Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    TestUtil.getCurrentDefaultSchemaName();
    try {
      st.executeUpdate(" CREATE TABLE owners(id INTEGER NOT NULL, "
          + "first_name VARCHAR(30), last_name VARCHAR(30), "
          + "address VARCHAR(255), city VARCHAR(80), "
          + " telephone VARCHAR(20),   PRIMARY KEY (id))");
      st.executeUpdate("CREATE INDEX last_name ON owners (last_name)");

      st.executeUpdate(" CREATE TABLE pets(id INTEGER, name VARCHAR(30),"
          + " birth_date DATE, type_id INTEGER, owner_id INTEGER , "
          + "PRIMARY KEY (id))");

      st.executeUpdate("CREATE INDEX owner_id ON pets (owner_id)");

      st.executeUpdate(" ALTER TABLE pets  ADD CONSTRAINT pets_ibfk_1 "
          + "FOREIGN KEY (owner_id) REFERENCES owners (id) ");

    } catch (SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
    } finally {
      st.execute("drop table pets");
      st.execute("drop table owners");
    }
  }

  public void testBug43006_2() throws Exception {
    Properties props = new Properties();
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    java.sql.Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    TestUtil.getCurrentDefaultSchemaName();
    try {
      st.executeUpdate(" CREATE TABLE owners(id INTEGER NOT NULL, "
          + "first_name VARCHAR(30), last_name VARCHAR(30), "
          + "address VARCHAR(255), city VARCHAR(80), "
          + " telephone VARCHAR(20),   PRIMARY KEY (id)) ");
      st.executeUpdate("CREATE INDEX last_name ON owners (last_name)");

      st.executeUpdate(" CREATE TABLE pets(id INTEGER, name VARCHAR(30),"
          + " birth_date DATE, type_id INTEGER, owner_id INTEGER , "
          + "PRIMARY KEY (id))");
      st.executeUpdate("CREATE INDEX owner_id ON pets (owner_id)");
      st.executeUpdate(" ALTER TABLE pets add CONSTRAINT unique_constr1 "
          + "UNIQUE (owner_id)");
    } catch (SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
    } finally {
      st.execute("drop table pets");
      st.execute("drop table owners");
    }
  }

  public void testBug43003_1() throws Exception {
    Properties props = new Properties();
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    java.sql.Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    TestUtil.getCurrentDefaultSchemaName();
    try {
      st.executeUpdate(" CREATE TABLE \"owners\"(\"id\" INTEGER NOT NULL,\"first_name\" VARCHAR(30)," +
          " \"last_name\" VARCHAR(30), \"address\" VARCHAR(255),    \"city\" VARCHAR(80), " +
          " \"telephone\" VARCHAR(20),   PRIMARY KEY (\"id\")) ");


      st.executeUpdate(" CREATE TABLE \"pets\"(    \"id\" INTEGER NOT NULL,    \"name\" VARCHAR(30)," +
          " \"birth_date\" DATE,    \"type_id\" INTEGER NOT NULL,    \"owner_id\" INTEGER NOT NULL,    " +
          "PRIMARY KEY (\"id\"))");


      st.executeUpdate(" ALTER TABLE \"pets\"  ADD CONSTRAINT \"pets_ibfk_1\" " +
          "FOREIGN KEY (\"owner_id\") REFERENCES \"owners\" (\"id\") ");
    } catch (SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
    } finally {
      st.execute("drop table \"pets\"");
      st.execute("drop table \"owners\"");
    }
  }

  public void testBug43003_2() throws Exception {
    Properties props = new Properties();
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    java.sql.Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    TestUtil.getCurrentDefaultSchemaName();
    try {
      st.executeUpdate(" CREATE TABLE \"owners\"(\"id\" INTEGER NOT NULL,\"first_name\" VARCHAR(30)," +
          " \"last_name\" VARCHAR(30), \"address\" VARCHAR(255),    \"city\" VARCHAR(80), " +
          " \"telephone\" VARCHAR(20),   PRIMARY KEY (\"id\")) ");


      st.executeUpdate(" CREATE TABLE \"pets\"(    \"id\" INTEGER NOT NULL,    \"name\" VARCHAR(30)," +
          " \"birth_date\" DATE,    \"type_id\" INTEGER NOT NULL,    \"owner_id\" INTEGER NOT NULL,    " +
          "PRIMARY KEY (\"id\"))");


      st.executeUpdate(" ALTER TABLE \"pets\"  ADD CONSTRAINT \"pets_ibfk_1\" " +
          "FOREIGN KEY (\"owner_id\") REFERENCES \"owners\" (\"id\") ");

      st.executeQuery("select * from \"pets\"");
    } catch (SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;

    } finally {
      st.execute("drop table \"pets\"");
      st.execute("drop table \"owners\"");
    }
  }

  public void testSynonym() throws Exception {
    System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    //   System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING,"true");

    Properties props = new Properties();
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    java.sql.Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create synonym synForSale for sale");
    st.execute("create table sale (timestamp timestamp, "
        + "store_id int, id int,  PRIMARY KEY(id)) "
        + "partition by column (store_id)");
    PreparedStatement ps = conn
        .prepareStatement("insert into synForSale "
            + "(id, timestamp, store_id) values (?, ?, ?)");
    String timestamp = "2005-01-";
    String time = " 00:00:00";
    st.execute("create index tdx on sale(timestamp)");
    for (int i = 1; i < 32; i++) {
      ps.setInt(1, i);
      if (i > 9) {
        ps.setTimestamp(2, Timestamp.valueOf(timestamp + i + time));
      } else {
        ps.setTimestamp(2, Timestamp.valueOf(timestamp + "0" + i + time));
      }
      ps.setInt(3, i % 2);
      int cnt = ps.executeUpdate();
      assertEquals(1, cnt);
    }
    st.execute("create synonym syn2ForSale for sale");
    st.execute("select max(timestamp) from synForSale");
    ResultSet rs = st.getResultSet();
    int cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals("2005-01-31 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);
    st.execute("update syn2ForSale set timestamp = '2006-01-31 00:00:00' where id = 1");

    st.execute("select max(timestamp) from synForSale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals("2006-01-31 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);

    st.execute("delete from synForSale");

    st.execute("select * from syn2ForSale");
    rs = st.getResultSet();
    while (rs.next()) {
      fail("no result should come");
    }
  }
}
