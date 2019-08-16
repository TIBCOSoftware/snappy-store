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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.GemFireTerminateError;
import com.gemstone.gemfire.internal.cache.LocalDataSet;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2Index;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireSelectActivation;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.iapi.types.UserType;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.procedure.ProcedureExecutionContext;
import com.pivotal.gemfirexd.tools.internal.JarTools;
import com.pivotal.gemfirexd.tools.utils.ExecutionPlanUtils;
import org.apache.derbyTesting.junit.JDBC;
import udtexamples.UDTPrice;

public class Bugs3Test extends JdbcTestBase {

  protected boolean callbackInvoked;

  public Bugs3Test(String name) {
    super(name);
  }

  public static int myhash(int value) {
    return value;
  }

  public static void proc3(ProcedureExecutionContext ctx)
      throws SQLException, InterruptedException {
    Connection c = ctx.getConnection();
    Statement s = c.createStatement();
    s.execute("insert into mytable values(2)");
    s.close();
    c.close();
  }

  public void testMaxBug_43418() throws Exception {
    Properties props = new Properties();
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    java.sql.Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create table sale (timestamp timestamp, "
        + "store_id int, id int,  PRIMARY KEY(id)) "
        + "partition by column (store_id)");
    PreparedStatement ps = conn
        .prepareStatement("insert into sale "
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

    st.execute("select max(timestamp) from sale");
    ResultSet rs = st.getResultSet();
    int cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals("2005-01-31 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);

    st.execute("select min(timestamp) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals("2005-01-01 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);

    st.execute("create index tdxMult on sale(id, timestamp)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);

    st.execute("drop index tdxMult");

    st.execute("create index tdx2 on sale(id asc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);
    st.execute("drop index tdx2");
    st.execute("drop index tdx");

    st.execute("create index tdxMult on sale(id asc, timestamp desc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);

    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(id desc, timestamp asc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);

    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(timestamp desc, id asc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);

    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(timestamp asc, id desc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);

    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(timestamp desc, id desc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while (rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);
  }

  /*
   * This is disabled at this point as it needs to be found what should be the behaviour of SQLChar
   * when value is retrieved. Should it be padded with blank space or not and that should like
   * predicate ignore trailing white spaces or not
   */
  public void __testBug42783_2() throws Exception {

    java.sql.Connection conn = getConnection();
    Statement st = conn.createStatement();
    try {
      st.executeUpdate("create table test(id char(10), "
          + "c10 char(10), vc10 varchar(10)) replicate");
      PreparedStatement insert = getConnection().prepareStatement(
          "insert into test values (?,?,?)");
      String[][] values = new String[][] {
          { "6", "efg", "hij" },
          { "7", "abcdefg", "hijklm" },

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
      // insert.executeUpdate();
      PreparedStatement ps = conn
          .prepareStatement("select id from test where c10 like ?");
      ResultSet rs = null;
      int count = 0;
      ps.setObject(1, "%fg");
      rs = ps.executeQuery();
      count = 0;
      while (rs.next()) {
        ++count;
      }
      assertEquals(count, 2);


      rs.close();
    } finally {
      st.execute("drop table test");
    }

  }

  public void testBug42828() throws Exception {

    java.sql.Connection conn = getConnection();
    Statement s = conn.createStatement();
    try {
      s
          .execute("create table t (i int, s smallint, r real, f float, d date, t time, ts timestamp, c char(10), v varchar(20))");
      s.execute("delete from t");
      // defaults are null, get two null rows using defaults
      s.execute("insert into t (i) values (null)");
      s.execute("insert into t (i) values (null)");
      ResultSet rs = s.executeQuery("select * from t where c = (select distinct v from t)");
      int numRows = 0;
      while (rs.next()) {
        ++numRows;
      }
      assertEquals(0, numRows);

    } finally {
      s.execute("drop table t");
    }
  }

  public void testBug43735() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), "
        + "loanlimit int, availloan decimal (30, 20),  tid int, "
        + "constraint netw_pk primary key (cid), constraint "
        + "cash_ch check (cash>=0), constraint sec_ch check (securities >=0), "
        + "constraint availloan_ck check (loanlimit>=availloan and availloan >=0))"
        + "partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), "
        + "VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17))");

    stmt.execute("insert into trade.networth values (859, 37099.0, "
        + "0.0, 5000, 5000.0, 43)");
    stmt.execute("insert into trade.networth values (4797, 16287.0, "
        + "308331.30, 20000, 10000.0, 43)");
    stmt.execute("create index trade.nw_al on trade.networth(availloan)");
    GemFireContainer container = (GemFireContainer)Misc.getRegion(
        "/TRADE/NETWORTH", true, false).getUserAttribute();

    byte[] expectedVbytes = new byte[] { 2, 0, 0, 18, -67, 20, 1, 1, 88, -28,
        1, 0, -87, -111, 118, -16, 0, 0, 20, 1, 25, -127, 43, 83, -105, 59,
        -19, 69, 104, 0, 0, 0, 0, 78, 32, 20, 1, -45, -62, 27, -50, -52, -19,
        -95, 0, 0, 0, 0, 0, 0, 43, 5, 18, 31, 35, 47 };
    byte[] actualVbytes = (byte[])container.getRegion().get(
        new CompactCompositeRegionKey(new SQLInteger(4797), container
            .getExtraTableInfo()));
    assertTrue(Arrays.equals(expectedVbytes, actualVbytes));

    byte[] expectedVbytes1 = new byte[] { 2, 0, 0, 3, 91, 20, 1, 3, 17, -102,
        20, -4, -5, 98, -17, -80, 0, 0, 20, 0, 0, 0, 19, -120, 20, 1, 105, -31,
        13, -25, 102, 118, -48, -128, 0, 0, 0, 0, 0, 43, 5, 18, 20, 24, 36 };
    byte[] actualVbytes1 = (byte[])container.getRegion().get(
        new CompactCompositeRegionKey(new SQLInteger(859), container
            .getExtraTableInfo()));
    assertTrue(Arrays.equals(expectedVbytes1, actualVbytes1));

    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    stmt.execute("update trade.networth set securities=565707.9 "
        + "where securities=0.0");
    stmt.execute("update trade.networth set loanlimit=50000 where "
        + "availloan > 1000.0");
    conn.commit();

    // now check the rows
    ResultSet rs = stmt.executeQuery("select cid, loanlimit, securities "
        + "from trade.networth");
    int numRows = 0;
    while (rs.next()) {
      if (rs.getInt(1) == 859) {
        assertEquals("565707.90000000000000000000", rs.getString(3));
        assertEquals(50000, rs.getInt(2));
      } else {
        assertEquals(4797, rs.getInt(1));
        assertEquals("308331.30000000000000000000", rs.getString(3));
        assertEquals(50000, rs.getInt(2));
      }
      numRows++;
    }
    assertEquals(2, numRows);
  }

  public void testDistinctWithBigInt_Bug42821() throws SQLException {

    java.sql.Connection conn = getConnection();
    Statement s = conn.createStatement();
    try {
      s.execute("create table li (l bigint, i int)");

      s.execute("insert into li values(1, 1)");
      s.execute("insert into li values(1, 1)");
      s.execute("insert into li values(9223372036854775807, 2147483647)");

    } finally {
      s.execute("drop table li");
      s.close();
    }

  }

  public void testUpdDelOnSysVTIs() throws SQLException {

    // Try update and delete to system VTI SYS.MEMBERS
    // Should fail with sqlstate 42Y25
    // Bugs 45901 and 45902
    java.sql.Connection conn = getConnection();
    Statement s = conn.createStatement();
    try {
      s.execute("delete from sys.members");
    } catch (SQLException ex) {
      assertTrue(ex.getSQLState().equalsIgnoreCase("42Y25"));
    }
    try {
      s.execute("update sys.indexes set indextype='X'");
    } catch (SQLException ex) {
      assertTrue(ex.getSQLState().equalsIgnoreCase("42Y25"));
    }
  }

  public void test43510_UDFAndInsertBatchUpdates() throws SQLException {

    Connection conn = getConnection();
    Statement st = conn.createStatement();

    st.execute("create function myhash (value Integer) "
        + "returns integer "
        + "language java "
        + "external name 'com.pivotal.gemfirexd.jdbc.Bugs3Test.myhash' "
        + "parameter style java " + "no sql " + "returns null on null input");

    st.execute("create table customer ("
        + "c_w_id         integer        not null,"
        + "c_id           integer        not null"
        + ") partition by (myhash(c_w_id)) redundancy 1");

    st.execute("create table history ("
        + "h_c_id   integer,"
        + "h_c_w_id integer"
        + ") partition by (myhash(h_c_w_id)) redundancy 1");

    PreparedStatement cps = conn.prepareStatement("insert into customer values (?,?) ");
    PreparedStatement hps = conn.prepareStatement("insert into history values (?,?) ");
    hps.setInt(1, 1);
    hps.setInt(2, 1);
    hps.addBatch();
    hps.setInt(1, 1);
    hps.setInt(2, 1);
    hps.addBatch();
    hps.setInt(1, 1);
    hps.setInt(2, 1);
    hps.addBatch();

    hps.executeBatch();
    hps.clearBatch();

    cps.setInt(1, 1);
    cps.setInt(2, 1);
    cps.addBatch();
    cps.setInt(1, 1);
    cps.setInt(2, 1);
    cps.addBatch();
    cps.setInt(1, 1);
    cps.setInt(2, 1);
    cps.addBatch();
    cps.executeBatch();
    cps.clearBatch();

  }

  @SuppressWarnings("serial")
  public void test43897() throws SQLException {
    reduceLogLevelForTest("config");

    Properties props = new Properties();
    props.setProperty("log-level", "config");
    props.setProperty("host-data", "true");
    props.setProperty("mcast-port", Integer.toString(AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET)));

    assertTrue("must self distributed", Integer.parseInt(props
        .getProperty("mcast-port")) != 0);
    Connection conn = getConnection(props);
    Statement st = conn.createStatement();

    st.execute("create schema hi_fi");

    st.execute("create table customer ("
        + "c_w_id         integer        not null,"
        + "c_id           integer        not null"
        + ") replicate ");

    st.execute("create table history ("
        + "h_c_id   integer,"
        + "h_c_w_id integer"
        + ") partition by column (h_c_w_id) ");

    PreparedStatement cps = conn.prepareStatement("insert into customer values (?,?) ");
    PreparedStatement hps = conn.prepareStatement("insert into history values (?,?) ");

    for (int i = 1; i < 3; i++) {
      hps.setInt(1, 1);
      hps.setInt(2, 1);
      hps.addBatch();
    }
    hps.executeBatch();
    hps.clearBatch();

    for (int i = 1; i < 3; i++) {
      cps.setInt(1, 1);
      cps.setInt(2, 1);
      cps.addBatch();
    }
    cps.executeBatch();
    cps.clearBatch();

    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public void onGetNextRowCore(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
        throw new NullPointerException();
      }
    };

    GemFireXDQueryObserverHolder.setInstance(observer);
    PreparedStatement sps = conn.prepareStatement("select * from customer, history");
    try {
      ResultSet rs = sps.executeQuery();
      while (rs.next()) ;
    } catch (SQLException sqle) {
      assertTrue("XJ001".equals(sqle.getSQLState()));
      Throwable t = sqle.getCause();
      do {
        if (t instanceof NullPointerException) {
          break;
        }
      }
      while ((t = t.getCause()) != null);

      if (t == null || !(t instanceof NullPointerException)) {
        fail("must have found NullPointerException propagated outside, instead found ", sqle);
      }
    }
  }

  public void test43309() throws SQLException {

    Properties cp = new Properties();
    cp.setProperty("host-data", "true");
    cp.setProperty("mcast-port", "0");
    //cp.setProperty("log-level", "fine");
    cp.setProperty("gemfire.enable-time-statistics", "true");
    cp.setProperty("statistic-sample-rate", "100");
    cp.setProperty("statistic-sampling-enabled", "true");
    cp.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_STATS, "true");
    cp.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_TIMESTATS, "true");
    cp.setProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME, "loner-" + 1 + ".gfs");

    cp.put(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, "Soubhik");
    cp.put(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "Soubhik");
    Connection conn = TestUtil.getConnection(cp);
    Statement st = conn.createStatement();

    st.execute("create table CHEESE (CHEESE_CODE VARCHAR(5), "
        + "CHEESE_NAME VARCHAR(20), CHEESE_COST DECIMAL(7,4))");

    st.execute("create index cheese_index on CHEESE "
        + "(CHEESE_CODE DESC, CHEESE_NAME DESC, CHEESE_COST DESC)");

    st.execute("INSERT INTO CHEESE (CHEESE_CODE, CHEESE_NAME, CHEESE_COST) "
        + "VALUES ('00000', 'GOUDA', 001.1234), ('00000', 'EDAM', "
        + "002.1111), ('54321', 'EDAM', 008.5646), ('12345', "
        + "'GORGONZOLA', 888.2309), ('AAAAA', 'EDAM', 999.8888), "
        + "('54321', 'MUENSTER', 077.9545)");

    ResultSet rs = st.executeQuery("SELECT * FROM CHEESE C1, CHEESE C2 "
        + "WHERE C1.CHEESE_NAME = C2.CHEESE_NAME AND "
        + "(C2.CHEESE_CODE='00000' OR C2.CHEESE_CODE='54321') "
        + "AND C1.CHEESE_NAME='EDAM' ORDER BY 4 DESC, 5 DESC, 6 DESC");

    String[][] expRS3 = new String[][] { { "54321", "EDAM", "8.5646" },
        { "54321", "EDAM", "8.5646" }, { "54321", "EDAM", "8.5646" },
        { "00000", "EDAM", "2.1111" }, { "00000", "EDAM", "2.1111" },
        { "00000", "EDAM", "2.1111" } };
    int i = 0;
    while (rs.next()) {
      assertEquals(expRS3[i][0], rs.getString(4));
      i++;
    }
  }

  public void test43818() throws Exception {
    Connection eConn = TestUtil.getConnection();

    Connection conn = TestUtil.startNetserverAndGetLocalNetConnection();
    Statement st = conn.createStatement();

    ResultSet rs = st
        .executeQuery("select count(*) from sys.sysaliases where javaclassname like 'com.pivotal%'");
    while (rs.next())
      ;

    rs.close();
    conn.close();
    eConn.close();
  }

  public void testStatistics() throws SQLException {
    reduceLogLevelForTest("config");

    Properties props = new Properties();
    props.setProperty("statistic-sampling-enabled", "true");
    props.setProperty("log-level", "config");
    props.setProperty("host-data", "true");
    props.setProperty("mcast-port", Integer.toString(AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET)));
    props.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_STATS, "true");
    props.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_TIMESTATS, "true");

    Connection conn = getConnection(props);
    Statement st = conn.createStatement();

    st.execute("create schema hi_fi");

    st.execute("create table customer ("
        + "c_w_id         integer        not null,"
        + "c_id           integer        not null"
        + ") replicate ");

    st.execute("create table history ("
        + "h_c_id   integer,"
        + "h_c_w_id integer"
        + ") partition by column (h_c_w_id) ");

    PreparedStatement cps = conn.prepareStatement("insert into customer values (?,?) ");
    PreparedStatement hps = conn.prepareStatement("insert into history values (?,?) ");

    hps.setInt(1, 1);
    hps.setInt(2, 1);
    hps.addBatch();
    hps.setInt(1, 1);
    hps.setInt(2, 1);
    hps.addBatch();
    hps.setInt(1, 1);
    hps.setInt(2, 1);
    hps.addBatch();

    hps.executeBatch();
    hps.clearBatch();

    cps.setInt(1, 1);
    cps.setInt(2, 1);
    cps.addBatch();
    cps.setInt(1, 1);
    cps.setInt(2, 1);
    cps.addBatch();
    cps.setInt(1, 1);
    cps.setInt(2, 1);
    cps.addBatch();
    cps.executeBatch();
    cps.clearBatch();

    Statement s = conn.createStatement();
//        s.execute("call SYSCS_UTIL.SET_STATEMENT_STATISTICS(1)");
//        s.execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
    PreparedStatement ps = conn.prepareStatement("insert into customer values (?, ?) ");

    ps.setInt(1, 44);
    ps.setInt(2, 43);

    int updateCount = ps.executeUpdate();
    System.out.println(updateCount);

    PreparedStatement sps = conn.prepareStatement("select * from customer, history");
    ResultSet rs = sps.executeQuery();
    while (rs.next()) ;
    rs.close();

    for (int i = 0; i < 1; i++) {
      ResultSet r = s.executeQuery("select * from customer, history");
      while (r.next()) {
        ;
      }
      r.close();
    }
    s.execute("call syscs_util.set_explain_connection(1)");
    ResultSet r = s.executeQuery("select * from customer, history");
    while (r.next()) ;
    r.close();
    s.execute("call syscs_util.set_explain_connection(0)");

    ResultSet rp = s.executeQuery("select stmt_id from sys.statementplans");
    while (rp.next()) {
      String stmt_id = rp.getString(1);
      ExecutionPlanUtils ex = new ExecutionPlanUtils(conn, stmt_id, null, true);
      System.out.println(ex.getPlanAsText(null));
    }

    s.execute("call SYSCS_UTIL.SET_STATEMENT_STATISTICS(0)");
  }

  // Disabled due to bug 50100 (now 40541). Enable when the bug is fixed.
  public void _test45655_45666() throws SQLException {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;

    Statement stmt = conn.createStatement();
    PreparedStatement pstmt;
    ResultSet rs;

    stmt.execute("create table trade.portfolio (cid int not null, sid int,"
        + " qty int not null, availQty int not null, subTotal decimal(30,20), "
        + "tid int, constraint portf_pk primary key (cid, sid), "
        + "constraint qty_ck check (qty>=0), constraint avail_ch check "
        + "(availQty>=0 and availQty<=qty)) "
        + " partition by column (cid, sid)");
    stmt.execute("create index idx_tot on trade.portfolio(subtotal, tid)");

    // and add a few rows...
    pstmt = conn
        .prepareStatement("insert into trade.portfolio values (?,?,?,?,?,?)");

    for (int i = 1; i <= 20; i++) {
      pstmt.setInt(1, i);
      pstmt.setInt(2, i + 10);
      pstmt.setInt(3, i + 10000);
      pstmt.setInt(4, i + 1000);
      pstmt.setBigDecimal(5, new BigDecimal(String.valueOf(i * 5) + '.'
          + String.valueOf(i * 2)));
      pstmt.setInt(6, 2);
      assertEquals(1, pstmt.executeUpdate());
    }

    pstmt = conn.prepareStatement("select distinct cid from trade.portfolio "
        + "where subTotal < ? or subTotal >= ?");
    pstmt.setBigDecimal(1, new BigDecimal("15"));
    pstmt.setBigDecimal(2, new BigDecimal("80"));
    rs = pstmt.executeQuery();
    int cid = 1;
    while (rs.next()) {
      if (cid == 3) {
        cid = 16;
      }
      assertEquals(cid, rs.getInt(1));
      cid++;
    }
    assertEquals(21, cid);

    final String[] exchanges = new String[] { "nasdaq", "nye", "amex", "lse",
        "fse", "hkse", "tse" };
    stmt.execute("create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
        + "'lse', 'fse', 'hkse', 'tse'))) partition by primary key");

    pstmt = conn.prepareStatement("insert into trade.securities values "
        + "(?, ?, ?, ?, ?)");
    for (int i = 1; i <= 20; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, "sym" + i);
      pstmt.setBigDecimal(3, new BigDecimal(String.valueOf(i * 5) + '.'
          + String.valueOf(i * 2)));
      pstmt.setString(4, exchanges[i % exchanges.length]);
      pstmt.setInt(5, i + 10);
      assertEquals(1, pstmt.executeUpdate());
    }

    for (int c = 1; c <= 4; c++) {
      if (c == 1) {
        stmt.execute("create index idxprice on trade.securities(PRICE asc)");
      } else if (c == 2) {
        stmt.execute("create index idxprice on trade.securities(PRICE desc)");
      } else if (c == 3) {
        stmt.execute("create index idxprice on trade.securities(PRICE asc)");
        stmt.execute("create index idxprice2 on trade.securities(PRICE desc)");
      } else {
        stmt.execute("create index idxprice on trade.securities(PRICE desc)");
        stmt.execute("create index idxprice2 on trade.securities(PRICE asc)");
      }

      pstmt = conn.prepareStatement("select price, symbol, exchange from "
          + "trade.securities where (price<? or price >=?)");
      pstmt.setBigDecimal(1, new BigDecimal("15"));
      pstmt.setBigDecimal(2, new BigDecimal("80"));
      THashSet prices = new THashSet();
      int sid = 1;
      while (sid <= 20) {
        if (sid == 3) {
          sid = 16;
        }
        prices.add(new BigDecimal(String.valueOf(sid * 5) + '.'
            + String.valueOf(sid * 2)).setScale(20));
        sid++;
      }

      final ScanTypeQueryObserver scanObserver = new ScanTypeQueryObserver();
      GemFireXDQueryObserverHolder.setInstance(scanObserver);

      rs = pstmt.executeQuery();
      while (rs.next()) {
        if (!prices.remove(rs.getObject(1))) {
          fail("unexpected price: " + rs.getObject(1));
        }
      }
      rs.close();
      assertEquals(0, prices.size());

      scanObserver.addExpectedScanType("TRADE.SECURITIES",
          ScanType.SORTEDMAPINDEX);
      scanObserver.checkAndClear();

      stmt.execute("drop index trade.idxprice");
      if (c > 2) {
        stmt.execute("drop index trade.idxprice2");
      }
    }

    GemFireXDQueryObserverHolder.clearInstance();
  }

  public void test45924() throws Exception {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Connection conn2 = startNetserverAndGetLocalNetConnection();

    Statement stmt = conn.createStatement();
    ResultSet rs;

    stmt.execute("create table works (empnum char(3) not null, pnum char(3) "
        + "not null, hours decimal(5), unique (empnum,pnum)) replicate");
    stmt.execute("insert into works values ('E1','P1',40)");

    // check with client connection first
    Statement stmt2 = conn2.createStatement();
    rs = stmt2.executeQuery("select empnum from works where empnum='E1'");
    assertTrue(rs.next());
    assertEquals("E1 ", rs.getObject(1));
    assertEquals("E1 ", rs.getString(1));
    assertFalse(rs.next());

    // and with embedded driver
    rs = stmt.executeQuery("select empnum from works where empnum='E1'");
    assertTrue(rs.next());
    assertEquals("E1 ", rs.getObject(1));
    assertEquals("E1 ", rs.getString(1));
    assertFalse(rs.next());
  }

  public void test47018() throws Exception {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Connection conn2 = startNetserverAndGetLocalNetConnection();

    Statement stmt = conn.createStatement();
    ResultSet rs;

    stmt.execute("create table t1 (col1 char(100), col2 char(1), col3 int," +
        " col4 int) partition by (col2)");
    stmt.execute("insert into t1 values (NULL, 'a', 1, 2)");
    stmt.execute("create index index_1 on t1 (col1 asc)");

    // check with client connection first
    Statement stmt2 = conn2.createStatement();
    rs = stmt2.executeQuery("select col1, col2, col3, col4 from t1 where " +
        "col1 is null and col3 = 1");
    assertTrue(rs.next());
    rs.getString(1);
    assertTrue(rs.wasNull());
    assertEquals("a", rs.getString(2));
    assertEquals(1, rs.getInt(3));
    assertEquals(2, rs.getInt(4));
    assertFalse(rs.next());

    // and with embedded driver
    rs = stmt.executeQuery("select col1, col2, col3, col4 from t1 where " +
        "col1 is null and col3 = 1");
    assertTrue(rs.next());
    rs.getString(1);
    assertTrue(rs.wasNull());
    assertEquals("a", rs.getString(2));
    assertEquals(1, rs.getInt(3));
    assertEquals(2, rs.getInt(4));
    assertFalse(rs.next());
  }

  public void testBug46803_1() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
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

      s.execute(" create table securities (sec_id int not null, symbol varchar(10) not null,"
          + "exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id),"
          + " constraint sec_uq unique (symbol, exchange), constraint"
          + " exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) ");
      s.execute(" create table companies (symbol varchar(10) not null, "
          + " exchange varchar(10) not null,  companyname char(100), "
          + " tid int, constraint comp_pk primary key (symbol, exchange) ,"
          + "constraint comp_fk foreign key (symbol, exchange) "
          + "references securities (symbol, exchange) on delete restrict)");

      // and add a few rows...
      psInsert4 = conn
          .prepareStatement("insert into securities values (?, ?, ?,?)");
      statements.add(psInsert4);
      for (int i = 1; i < 4; ++i) {
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

      int n = s.executeUpdate("update securities set tid=7  where sec_id = 1");
      assertEquals(1, n);
      try {
        s.executeUpdate("update securities set symbol = 'random1' , exchange = 'amex' where sec_id = 1");
        fail("Should have thrown constraint violation exception");
      } catch (SQLException expected) {
        // expected.printStackTrace();
        if (!expected.getSQLState().equals("23503")) {
          expected.printStackTrace();
          fail("unexpected sql state for sql exception =" + expected);
        }
      }

      try {
        s.executeUpdate("update securities set exchange = 'amex'  where sec_id = 1");
        fail("Should have thrown constraint violation exception");
      } catch (SQLException expected) {
        // expected.printStackTrace();
        if (!expected.getSQLState().equals("23503")) {
          expected.printStackTrace();
          fail("unexpected sql state for sql exception =" + expected);
        }
      }

      try {
        s.executeUpdate("update securities set symbol = 'random1'  where sec_id = 1");
        fail("Should have thrown constraint violation exception");
      } catch (SQLException expected) {
        // expected.printStackTrace();
        if (!expected.getSQLState().equals("23503")) {
          expected.printStackTrace();
          fail("unexpected sql state for sql exception =" + expected);
        }
      }

      n = s
          .executeUpdate("update securities set symbol = 'symbol1'  where sec_id = 1");
      assertEquals(1, n);
      /*
       * try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(baos);
      checkMsg.toData(dos, (short)0x00);
      ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
      ReferencedKeyCheckerMessage mssg = new ReferencedKeyCheckerMessage();
      mssg.fromData(new DataInputStream(bais), (short)0x00);
      } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
      } catch (ClassNotFoundException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
      }
       */

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

  public void testBug46803_2() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
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

      s.execute(" create table securities (sec_id int not null, symbol varchar(10) not null,"
          + "exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id),"
          + " constraint sec_uq unique (symbol, exchange), constraint"
          + " exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) ");
      s.execute(" create table companies (symbol varchar(10) not null, "
          + " exchange varchar(10) not null,  companyname char(100), "
          + " tid int, constraint comp_pk primary key (symbol, exchange) ,"
          + "constraint comp_fk foreign key (symbol, exchange) "
          + "references securities (symbol, exchange) on delete restrict)");

      s.execute("create table buyorders(oid int not null constraint buyorders_pk primary key,"
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

      n = s
          .executeUpdate("update securities set exchange = 'amex' where sec_id = 9");
      // No violation expected.
      assertEquals(1, n);

      n = s
          .executeUpdate("update securities set exchange = 'hkse', symbol ='random_n'  where sec_id = 9 and tid = 1");
      // No violation expected.
      assertEquals(1, n);

      PreparedStatement psUpdate = conn
          .prepareStatement("update securities set exchange = ?, symbol =?  where sec_id = ? and tid = ?");
      psUpdate.setString(1, "fse");
      psUpdate.setString(2, "random_2");
      psUpdate.setInt(3, 9);
      psUpdate.setInt(4, 1);
      n = psUpdate.executeUpdate();

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

  public void testBug47465() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    // This ArrayList usage may cause a warning when compiling this class
    // with a compiler for J2SE 5.0 or newer. We are not using generics
    // because we want the source to support J2SE 1.4.2 environments.
    ArrayList statements = new ArrayList(); // list of Statements,
    // PreparedStatements
    PreparedStatement psInsert, psInsertDerby = null;
    Statement s = null;
    ResultSet rs = null;
    Connection conn = null;

    conn = getConnection();

    // Creating a statement object that we can use for running various
    // SQL statements commands against the database.
    s = conn.createStatement();
    statements.add(s);

    // We create a table...
    String tableBuyOrders = " create table buyorders(oid int not null constraint buyorders_pk primary key,"
        + " cid int, qty int, bid decimal (30, 20), status varchar (10) , constraint bo_qty_ck check (qty>=0))  ";

    String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      tempDerbyUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    final String derbyDbUrl = tempDerbyUrl;
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    Statement derbyStmt = derbyConn.createStatement();

    String query = "select cid,  avg(qty*bid)  as amount from buyorders  where status =?"
        + "  GROUP BY cid ORDER BY amount";

    ResultSet rsDerby = null;
    try {
      s.execute(tableBuyOrders);
      derbyStmt.execute(tableBuyOrders);
      String index1 = "create index buyorders_cid on buyorders(cid)";
      s.execute(index1);
      derbyStmt.execute(index1);
      // and add a few rows...
      psInsert = conn
          .prepareStatement("insert into buyorders values (?, ?, ?,?,?)");
      psInsertDerby = derbyConn
          .prepareStatement("insert into buyorders values (?, ?, ?,?,?)");
      statements.add(psInsert);
      int cid = 1;
      int cidIncrementor = 0;
      int constant = 1;
      for (int i = 1; i < 500; ++i) {

        if (cidIncrementor % 5 == 0) {

          ++cid;
        }
        ++cidIncrementor;
        psInsert.setInt(1, i);
        psInsert.setInt(2, cid);
        psInsert.setInt(3, i * 23);
        psInsert.setFloat(4, i * cid * 73.5f);
        psInsert.setString(5, "open");

        psInsertDerby.setInt(1, i);
        psInsertDerby.setInt(2, cid);
        psInsertDerby.setInt(3, i * 23);
        psInsertDerby.setFloat(4, i * cid * 73.5f);
        psInsertDerby.setString(5, "open");

        assertEquals(1, psInsert.executeUpdate());
        assertEquals(1, psInsertDerby.executeUpdate());
      }

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

      PreparedStatement derbyQuery = derbyConn.prepareStatement(query);
      derbyQuery.setString(1, "open");
      PreparedStatement gfxdQuery = conn.prepareStatement(query);
      gfxdQuery.setString(1, "open");
      //ResultSet rsGfxd = gfxdQuery.executeQuery();

      //rsGfxd = gfxdQuery.executeQuery();
      //rsDerby = derbyQuery.executeQuery();

      //rsGfxd = gfxdQuery.executeQuery();
      //rsDerby = derbyQuery.executeQuery();

      validateResults(derbyQuery, gfxdQuery, query, false);
    } catch (Exception e) {
      e.printStackTrace();
      fail("Test failed because of exception " + e);
    } finally {
      if (rsDerby != null)
        rsDerby.close();
      if (derbyStmt != null) {
        try {
          derbyStmt.execute("drop table buyorders");
        } catch (Exception e) {
          e.printStackTrace();
          // ignore intentionally
        }

      }

      if (s != null) {
        try {
          s.execute("drop table buyorders");
        } catch (Exception e) {
          // ignore intentionally
        }

      }

      TestUtil.shutDown();
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());

    }

  }

  public void testBug46803_3() throws Exception {


    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
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
          + " 'lse', 'fse', 'hkse', 'tse'))) ");


      s.execute("create table buyorders(oid int not null"
          + " constraint buyorders_pk primary key,"
          + " sid int,  tid int, sec_id2 int,"
          + " constraint bo_sec_fk foreign key (sid) references securities (sec_id) "
          + " on delete restrict," +
          "   constraint bo_sec_fk2 foreign key (sec_id2) references securities (sec_id2) "
          + " on delete restrict" +
          ")");

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
        psInsert4.setInt(6, i * 3);
        assertEquals(1, psInsert4.executeUpdate());
      }


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


  public void testBug46843_3() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    final List<Throwable> exceptions = new ArrayList<Throwable>();
    Statement stmt = null;
    final String query = "select sid, count(*) from txhistory  where cid>? and sid<? and tid =? " +
        " GROUP BY sid HAVING count(*) >=1";
    Connection conn = null;
    Statement derbyStmt = null;
    String table = "create table txhistory(oid int ,cid int,  sid int, qty int, type varchar(10), tid int)";
    String index1 = "create index txhistory_type on txhistory(type)";
    String index2 = "create index txhistory_cid on txhistory(cid)";
    String index3 = "create index txhistory_sid on txhistory(sid)";
    String index4 = "create index txhistory_oid on txhistory(oid)";
    String index5 = "create index txhistory_tid on txhistory(tid)";
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
      //   derbyStmt.execute(index1);
      derbyStmt.execute(index5);
      // derbyStmt.execute(index3);
      // derbyStmt.execute(index4);
      Statement derbyStmt1 = derbyConn.createStatement();
      conn = TestUtil.getConnection();

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      stmt = conn.createStatement();
      stmt.execute(table);
      // stmt.execute(index1);
      stmt.execute(index5);
      // stmt.execute(index3);
      // stmt.execute(index4);
      // We create a table...
      PreparedStatement ps_insert_derby = derbyConn
          .prepareStatement("insert into txhistory  values(?,?,?,?,?,?)");
      PreparedStatement ps_insert_gfxd = conn
          .prepareStatement("insert into txhistory values(?,?,?,?,?,?)");
      ps_insert_derby.setInt(1, 1);
      ps_insert_derby.setInt(2, 1);
      ps_insert_derby.setInt(3, 1);
      ps_insert_derby.setInt(4, 1 * 1 * 100);
      ps_insert_derby.setNull(5, java.sql.Types.VARCHAR);
      ps_insert_derby.setInt(6, 10);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 1);
      ps_insert_gfxd.setInt(2, 1);
      ps_insert_gfxd.setInt(3, 1);
      ps_insert_gfxd.setInt(4, 1 * 1 * 100);
      ps_insert_gfxd.setNull(5, java.sql.Types.VARCHAR);
      ps_insert_gfxd.setInt(6, 10);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 2);
      ps_insert_derby.setInt(2, 11);
      ps_insert_derby.setInt(3, 3);
      ps_insert_derby.setInt(4, 1 * 3 * 100);
      ps_insert_derby.setString(5, "sell");
      ps_insert_derby.setInt(6, 10);

      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 2);
      ps_insert_gfxd.setInt(2, 11);
      ps_insert_gfxd.setInt(3, 3);
      ps_insert_gfxd.setInt(4, 1 * 3 * 100);
      ps_insert_gfxd.setString(5, "sell");
      ps_insert_gfxd.setInt(6, 10);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 3);
      ps_insert_derby.setInt(2, 11);
      ps_insert_derby.setInt(3, 3);
      ps_insert_derby.setInt(4, 1 * 3 * 100);
      ps_insert_derby.setString(5, "buy");
      ps_insert_derby.setInt(6, 10);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 3);
      ps_insert_gfxd.setInt(2, 11);
      ps_insert_gfxd.setInt(3, 3);
      ps_insert_gfxd.setInt(4, 1 * 3 * 100);
      ps_insert_gfxd.setString(5, "buy");
      ps_insert_gfxd.setInt(6, 10);
      ps_insert_gfxd.executeUpdate();


      ps_insert_derby.setInt(1, 7);
      ps_insert_derby.setInt(2, 6);
      ps_insert_derby.setInt(3, 4);
      ps_insert_derby.setInt(4, 6 * 4 * 100);
      ps_insert_derby.setNull(5, java.sql.Types.VARCHAR);
      ps_insert_derby.setInt(6, 10);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 7);
      ps_insert_gfxd.setInt(2, 6);
      ps_insert_gfxd.setInt(3, 4);
      ps_insert_gfxd.setInt(4, 6 * 4 * 100);
      ps_insert_gfxd.setNull(5, java.sql.Types.VARCHAR);
      ps_insert_gfxd.setInt(6, 10);
      ps_insert_gfxd.executeUpdate();

      ps_insert_derby.setInt(1, 8);
      ps_insert_derby.setInt(2, 12);
      ps_insert_derby.setInt(3, 4);
      ps_insert_derby.setInt(4, 12 * 4 * 100);
      ps_insert_derby.setNull(5, java.sql.Types.VARCHAR);
      ps_insert_derby.setInt(6, 10);
      ps_insert_derby.executeUpdate();

      ps_insert_gfxd.setInt(1, 8);
      ps_insert_gfxd.setInt(2, 12);
      ps_insert_gfxd.setInt(3, 4);
      ps_insert_gfxd.setInt(4, 12 * 4 * 100);
      ps_insert_gfxd.setNull(5, java.sql.Types.VARCHAR);
      ps_insert_gfxd.setInt(6, 10);
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
            Random random = new Random();
            // while (true) {
            int cid = 10;
            int sid = 100;
            stmt.setInt(1, cid);
            stmt.setInt(2, sid);
            stmt.setInt(3, 10);

            derbyStmt.setInt(1, cid);
            derbyStmt.setInt(2, sid);
            derbyStmt.setInt(3, 10);

            validateResults(derbyStmt, stmt, query, false);
            String query2 = "select cid, sum(qty) as amount from txhistory  where sid = ? " +
                " GROUP BY cid";

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


            stmt = gfxdConn.prepareStatement(query2);
            derbyStmt = derbyConn.prepareStatement(query2);
            stmt.setInt(1, 3);
            derbyStmt.setInt(1, 3);

            validateResults(derbyStmt, stmt, query, false);
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

  public void testBug47114() throws Exception {
    reduceLogLevelForTest("config");

    String query = "select sec_id, exchange, s.tid, cid, cust_name, c.tid from trade.securities s," +
        " trade.customers c where c.cid = (select f.cid from trade.portfolio f  where c.cid = f.cid " +
        "and f.tid = 1 group by f.cid having count(*) >= 1) and sec_id in (select sid from trade.portfolio f" +
        " where availQty > 0 and availQty < 10000) ";
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);

    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    try {
      String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
      ResultSet rs = null;
      st.execute("create table trade.securities (sec_id int not null, exchange varchar(10) not null,  tid int, constraint sec_pk primary key (sec_id), "
          + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate");

      PreparedStatement psSec = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?)");
      final int sec_limit = 200;
      for (int i = 0; i < sec_limit; ++i) {
        psSec.setInt(1, i);
        psSec.setString(2, exchanges[i % exchanges.length]);
        psSec.setInt(3, 1);
        psSec.executeUpdate();
      }
      Random random = new Random(sec_limit);

      st.execute("create table trade.customers (cid int primary key, sid int, cust_name varchar(100), tid int)");
      PreparedStatement psCust = conn
          .prepareStatement("insert into trade.customers values (?, ?, ?,?)");
      final int cust_limit = 200;
      Map<Integer, Integer> pfMap = new HashMap<Integer, Integer>();
      for (int i = 1; i < cust_limit; ++i) {
        psCust.setInt(1, i);
        int sec = random.nextInt(sec_limit);
        pfMap.put(i, sec);
        psCust.setInt(2, sec);
        psCust.setString(3, "" + i);
        psCust.setInt(4, 1);
        psCust.executeUpdate();
      }


      st
          .execute(" create table trade.portfolio (cid int not null, sid int not null, "
              + " availQty int not null, tid int,"
              + " constraint portf_pk primary key (cid, sid), "
              + "constraint cust_fk foreign key (cid) references"
              + " trade.customers (cid) on delete restrict, "
              + "constraint sec_fk foreign key (sid) references trade.securities (sec_id) )");

      st.execute(" create index portfolio_cid on trade.portfolio(cid)");
      PreparedStatement psPF = conn
          .prepareStatement("insert into trade.portfolio values (?, ?,?,?)");


      for (Map.Entry<Integer, Integer> entry : pfMap.entrySet()) {
        psPF.setInt(1, entry.getKey());
        psPF.setInt(2, entry.getValue());
        psPF.setInt(3, 1000);
        psPF.setInt(4, 1);
        psPF.executeUpdate();
      }
      final boolean indexUsed[] = new boolean[] { false };

      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter() {


        @Override
        public void scanControllerOpened(Object sc, Conglomerate conglom) {
          GemFireContainer container;
          if (conglom instanceof SortedMap2Index) {
            SortedMap2Index sortedIndex = (SortedMap2Index)conglom;
            container = sortedIndex.getGemFireContainer();
            if (container.isLocalIndex() && container.getName().equals("TRADE.6__PORTFOLIO__CID:base-table:TRADE.PORTFOLIO")) {
              indexUsed[0] = true;
            }
          }
        }
      });
      rs = st.executeQuery(query);
      int count = 0;
      while (rs.next()) {
        rs.getInt(1);
        ++count;
      }
      assertTrue(indexUsed[0]);
      assertTrue(count > 5);
    } finally {


      TestUtil.shutDown();
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());

    }
  }

  public void testBug45998() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    String subquery = "SELECT b.account_id FROM ap.betting_user_accounts b, ap.account_allowed_bet_type t "
        + "WHERE b.account_id = t.account_id AND b.account_number='2e7' AND t.allow_bet_type=0 and b.account_id = 744";
    final String dml = "UPDATE ap.account_balances SET locked_by = 1 "
        + "WHERE ap.account_balances.balance_type=0 AND ap.account_balances.account_balance >= 1000 AND ap.account_balances.account_id IN"
        + "(" + subquery + ")";
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

    final LanguageConnectionContext lcc = ((EmbedConnection)jdbcConn)
        .getLanguageConnection();
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo
                  && ((SelectQueryInfo)qInfo).isSubQuery()) {
                callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                if (qInfo.getParameterCount() == 0) {
                  SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                  Set<Object> actualRoutingKeys = new HashSet<Object>();
                  actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
                  try {
                    Activation act = new GemFireSelectActivation(gps, lcc,
                        (DMLQueryInfo)qInfo, null, false);

                    ((BaseActivation)act).initFromContext(lcc, true, gps);
                    sqi.computeNodes(actualRoutingKeys, act, false);
                    assertFalse(actualRoutingKeys.isEmpty());
                    assertTrue(actualRoutingKeys.contains(Integer.valueOf(744)));

                  } catch (Exception e) {
                    e.printStackTrace();
                    fail(e.toString());
                  }
                }
              }

            }

          });
      try {
        st.execute(dml);
      } catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + dml, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      this.callbackInvoked = false;
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }

  }

  public void testBug48245() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create table app.test1(col1 int, col2 int, constraint p1_pk "
        + "primary key (col1))");
    st.execute("insert into app.test1 values (1, 1)");

    PreparedStatement pst = conn.prepareStatement("update app.test1 set"
        + " col2 = ? where col1 = ?");
    for (int i = 2; i <= 5; i++) {
      pst.setInt(1, i);
      pst.setInt(2, 1);
      pst.addBatch();
    }
    pst.executeBatch();
    conn.commit();

    ResultSet rs = st.executeQuery("select col2 from app.test1 where col1 = 1");
    assertTrue(rs.next());
    assertEquals(5, rs.getInt(1));
    assertFalse(rs.next());
  }

  public void test46976() throws Exception {
    getConnection(new Properties());
    Cache cache = Misc.getGemFireCache();
    AttributesFactory fact = new AttributesFactory();
    fact.setDataPolicy(DataPolicy.PARTITION);
    PartitionedRegion pr = (PartitionedRegion)cache.createRegion("myPR",
        fact.create());
    //PartitionedRegion pr = (PartitionedRegion)cache.createRegionFactory(
    //                    RegionShortcut.PARTITION).create("myPR");
    pr.put(1, "one");
    pr.invalidate(1);
    Set<Integer> buckets = new HashSet<Integer>();
    buckets.add(1);
    LocalDataSet lds = new LocalDataSet(pr, buckets, null/*txState*/);
    assertTrue(lds.get(1) == null);
  }

  public void testBug47289_1() throws Exception {
    Properties props = new Properties();

    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange))");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    rs = st.executeQuery("select * from trade.securities");
    int k = 0;
    while (rs.next()) {
      String symbol = rs.getString(2);
      String exchange = rs.getString(4);
      dg.insertIntoCompanies(psComp, symbol, exchange);
      ++k;
    }
    assertTrue(k >= 1);

    st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange) "
        + "references trade.securities (symbol, exchange) on delete restrict");

  }

  public void testBug47289_2() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) partition by column (symbol) ");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange))");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    rs = st.executeQuery("select * from trade.securities");
    int k = 0;
    while (rs.next()) {
      String symbol = rs.getString(2);
      String exchange = rs.getString(4);
      dg.insertIntoCompanies(psComp, symbol, exchange);
      ++k;
    }
    assertTrue(k >= 1);

    try {
      st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange) "
          + "references trade.securities (symbol, exchange) on delete restrict");
      fail("FK constraint addition should fail");
    } catch (SQLException sqle) {
      if (sqle.getSQLState().equals("0A000")) {
        //Ok
      } else {
        throw sqle;
      }
    }

  }

  public void testBug47289_4() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) partition by column (symbol) ");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange))");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");


    st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange) "
        + "references trade.securities (symbol, exchange) on delete restrict");

  }


  public void testBug47289_5() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) partition by column (symbol) ");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, symbol1 varchar(10) not null, " +
        "tid int, constraint comp_pk primary key (symbol, exchange)) partition by column ( symbol) colocate with (trade.securities)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, symbol1 , tid) values (?,?,?,?)");
    rs = st.executeQuery("select * from trade.securities");
    int k = 0;
    while (rs.next()) {
      String symbol = rs.getString(2);
      String exchange = rs.getString(4);
      psComp.setString(1, symbol);
      psComp.setString(2, exchange);
      psComp.setString(3, symbol);
      psComp.setInt(4, 10);
      psComp.executeUpdate();

      ++k;
    }
    assertTrue(k >= 1);

    try {
      st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol1, exchange) "
          + "references trade.securities (symbol, exchange) on delete restrict");
      fail("FK constraint addition should fail");
    } catch (SQLException sqle) {
      if (sqle.getSQLState().equals("0A000")) {
        //Ok
      } else {
        throw sqle;
      }
    }

  }

  public void testBug47289_6() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) partition by column (symbol, exchange) ");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, symbol1 varchar(10) not null, " +
        "tid int, constraint comp_pk primary key (symbol, exchange)) partition by column ( symbol, exchange) colocate with (trade.securities)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, symbol1 , tid) values (?,?,?,?)");
    rs = st.executeQuery("select * from trade.securities");
    int k = 0;
    while (rs.next()) {
      String symbol = rs.getString(2);
      String exchange = rs.getString(4);
      psComp.setString(1, symbol);
      psComp.setString(2, exchange);
      psComp.setString(3, symbol);
      psComp.setInt(4, 10);
      psComp.executeUpdate();

      ++k;
    }
    assertTrue(k >= 1);

    try {
      st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol1, exchange) "
          + "references trade.securities (symbol, exchange) on delete restrict");
      fail("FK constraint addition should fail");
    } catch (SQLException sqle) {
      if (sqle.getSQLState().equals("0A000")) {
        //Ok
      } else {
        throw sqle;
      }
    }

  }

  public void testBug47289_3() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) partition by column (exchange) ");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, " +
        "logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange)) " +
        "partition by column(exchange) colocate with ( trade.securities)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    rs = st.executeQuery("select * from trade.securities");
    int k = 0;
    while (rs.next()) {
      String symbol = rs.getString(2);
      String exchange = rs.getString(4);
      dg.insertIntoCompanies(psComp, symbol, exchange);
      ++k;
    }
    assertTrue(k >= 1);


    st.execute("alter table trade.companies add constraint comp_fk foreign key (symbol, exchange) "
        + "references trade.securities (symbol, exchange) on delete restrict");
  }

  public void testBug47426() throws Exception {
    // test implicit table creation when initialization of table is delayed in
    // restart
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table trade.customers (cid int not null GENERATED BY "
        + "DEFAULT AS IDENTITY (INCREMENT BY 1 ), cust_name varchar(100), "
        + "since date, addr varchar(100), tid int, primary key (cid))   REDUNDANCY 1");
    stmt.execute("alter table trade.customers ALTER column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");
    stmt.execute("create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', "
        + "'fse', 'hkse', 'tse')))  partition by range (price) (VALUES BETWEEN "
        + "0.0 AND 25.0,  VALUES BETWEEN 25.0  AND 35.0 , VALUES BETWEEN 35.0 "
        + " AND 49.0, VALUES BETWEEN 49.0  AND 69.0 , VALUES BETWEEN 69.0 AND 100.0) "
        + " REDUNDANCY 1");
    stmt.execute("create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk "
        + "primary key (cid, sid), constraint cust_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint sec_fk foreign key (sid) references trade.securities "
        + "(sec_id) on delete restrict, constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty))   REDUNDANCY 1");
    // stop the VM and restart to replay DDLs
    shutDown();
    conn = getConnection();
    // check that implicit indexes should be listed in GfxdIndexManager
    GfxdIndexManager indexManager = (GfxdIndexManager)((LocalRegion)Misc
        .getRegion("/TRADE/PORTFOLIO", true, false)).getIndexUpdater();
    List<GemFireContainer> indexes = indexManager.getAllIndexes();
    assertEquals(2, indexes.size());
    // expect an index on CID and SID
    boolean foundCID = false, foundSID = false;
    for (GemFireContainer c : indexes) {
      if (!foundCID) {
        foundCID = c.getQualifiedTableName().contains("__PORTFOLIO__CID");
      }
      if (!foundSID) {
        foundSID = c.getQualifiedTableName().contains("__PORTFOLIO__SID");
      }
    }
    assertTrue(foundCID);
    assertTrue(foundSID);
  }

  public void testBugs47389() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null, companytype smallint, "
        + "companyname varchar(100), constraint comp_pk primary key "
        + "(symbol, exchange))");

    st.execute("insert into trade.companies values('vmw', 'nasdaq', 0, 'vmware')");

    PreparedStatement ps2 = conn.prepareStatement("update trade.companies "
        + "set companytype = ? where symbol = ? and exchange in "
        + "('tse', 'nasdaq')");
    ps2.setInt(1, 1);
    ps2.setString(2, "vmw");

    int count = ps2.executeUpdate();
    assertEquals(1, count);
    st.execute("select * from trade.companies");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(3));
    assertFalse(rs.next());
  }

  public void testUseCase10IndexUsage() throws Exception {
    // Bug #51839
    if (isTransactional) {
      return;
    }

    reduceLogLevelForTest("config");

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
    Properties props = new Properties();
    props.setProperty("log-level", "config");
    props.setProperty("table-default-partitioned", "false");
    Connection conn = getConnection(props);

    // create the schema
    String resourcesDir = getResourcesDir();
    String indexScript = resourcesDir
        + "/lib/useCase10/04_indices_gemfirexd.sql";
    String schemaScript = resourcesDir
        + "/lib/useCase10/02_ddl_gemfirexd_2.sql";
    GemFireXDUtils.executeSQLScripts(conn, new String[] { schemaScript }, false,
        getLogger(), null, null, false);


    Statement stmt = conn.createStatement();
    String query = "SELECT     d.NOM_MES,tp.NOM_TIPO_PRODUTO,SUM(VLR_ITEM_NF) as SUM_VLR_NF FROM" +
        "    APP.ITEM_NOTA_FISCAL n INNER JOIN  APP.TIPO_PRODUTO tp " +
        "ON (tp.COD_TIPO_PRODUTO = n.COD_TIPO_PRODUTO) INNER JOIN  APP.PRODUTO p " +
        "ON (n.COD_PRODUTO = p.COD_PRODUTO AND n.COD_TIPO_PRODUTO = p.COD_TIPO_PRODUTO )" +
        "INNER JOIN    APP.TEMPO d ON (n.DAT_DIA = d.DAT_DIA)  GROUP BY    d.NOM_MES, tp.NOM_TIPO_PRODUTO;";


    stmt.execute("TRUNCATE TABLE APP.ITEM_NOTA_FISCAL");
    stmt.execute("TRUNCATE TABLE APP.NOTA_FISCAL");
    stmt.execute("TRUNCATE TABLE APP.TEMPO");
    stmt.execute("TRUNCATE TABLE APP.VENDEDOR");
    stmt.execute("TRUNCATE TABLE APP.REVENDA");
    stmt.execute("TRUNCATE TABLE APP.PRODUTO");
    stmt.execute("TRUNCATE TABLE APP.TIPO_PRODUTO");
    stmt.execute("TRUNCATE TABLE APP.CLIENTE");
    long start, end, timeTakenInSec;
    int numRows = 0;
    ResultSet rs;
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','CLIENTE','" +
        resourcesDir + "/lib/useCase10/dm_cliente.dat', ';', null, null, 0)");
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','TIPO_PRODUTO', '" +
        resourcesDir + "/lib/useCase10/dm_tipo_prod.dat', ';', null, null, 0)");
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','PRODUTO', '" +
        resourcesDir + "/lib/useCase10/dm_prod.dat', ';', null, null, 0)");
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','REVENDA', '" +
        resourcesDir + "/lib/useCase10/dm_revenda.dat', ';', null, null, 0)");
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','VENDEDOR', '" +
        resourcesDir + "/lib/useCase10/dm_vendedor.dat', ';', null, null, 0)");
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','TEMPO', '" +
        resourcesDir + "/lib/useCase10/dm_tempo.dat', ';', null, null, 0)");
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','NOTA_FISCAL', '" +
        resourcesDir + "/lib/useCase10/dm_nf.dat', ';', null, null, 0)");
    stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE ( 'APP','ITEM_NOTA_FISCAL', '" +
        resourcesDir + "/lib/useCase10/dm_item_nf.dat', ';', null, null, 0)");
    start = System.currentTimeMillis();
    rs = stmt.executeQuery(query);

    numRows = 0;
    while (rs.next()) {
      ++numRows;
    }
    end = System.currentTimeMillis();
    assertTrue(numRows > 0);
    timeTakenInSec = (end - start) / 1000;
    getLogger().info(
        "time taken to execute query=" + timeTakenInSec + " seconds.");
    assertTrue(timeTakenInSec < 10);
  }

  public void testMultipleRowsUpdate() throws Exception {
    // check with embedded connection first
    Connection conn = getConnection();
    runTestMultipleRowsUpdate(conn);
    conn.close();
    // check with thinclient connection next
    Connection netConn = startNetserverAndGetLocalNetConnection();
    runTestMultipleRowsUpdate(netConn);
    netConn.close();
  }

  public void testBug46799() throws Exception {
    Connection conn = TestUtil.getConnection();
    int netPort = TestUtil.startNetserverAndReturnPort();
    Connection conn2 = TestUtil.getNetConnection(netPort, null, null);
    Statement st = conn.createStatement();
    Statement st2 = conn2.createStatement();

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
    checkLobs(rs, clobChars, blobBytes);

    rs = st2.executeQuery("select symbol, exchange, companytype, uid,"
        + " uuid, companyname, companyinfo, note, histPrice, asset, logo, tid,"
        + " pvt from TRADE.COMPANIES where tid >= 105 and tid < 110");
    checkLobs(rs, clobChars, blobBytes);
  }

  public void testSecuritasException_1() throws Exception {

    TestUtil.getConnection();
    int netPort = TestUtil.startNetserverAndReturnPort();
    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    Statement st = conn.createStatement();

    st.execute("create table test (id int, time1 timestamp, time2 timestamp)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into test  values " +
            "(?,?,?)");
    String createProcUpdateSQLStr_WITH_MODIFIES = "CREATE PROCEDURE PROC_UPDATE_SQL_WITH_MODIFIES(time Timestamp) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA MODIFIES SQL DATA EXTERNAL NAME '"
        + ProcedureTest.class.getName()
        + ".procSecuritasBug' ";

    st.execute(createProcUpdateSQLStr_WITH_MODIFIES);
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
   /* for (Object o : row) {
      psComp.setObject(col, o);
      col++;
    }*/
    psComp.setInt(1, 1);
    psComp.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
    psComp.setNull(3, Types.TIMESTAMP);
    psComp.executeUpdate();


    CallableStatement update = conn.prepareCall("call PROC_UPDATE_SQL_WITH_MODIFIES(?)");
    update.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
    update.execute();

  }

  public void _testSecuritasException_2() throws Exception {

    Connection embedded = TestUtil.getConnection();
    int netPort = TestUtil.startNetserverAndReturnPort();
    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    Statement st = conn.createStatement();
    Statement embeddedStmt = embedded.createStatement();
    embeddedStmt.execute("create table test (id int, time1 timestamp, time2 timestamp default null, text  clob)");
    PreparedStatement psComp = embedded
        .prepareStatement("insert into test(id,time1, time2, text)  values " +
            "(?,?,?,?)");
    String createProcSQLStr = "CREATE PROCEDURE PROC_SQL() "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
        + ProcedureTest.class.getName()
        + ".procSecuritasBug_2' DYNAMIC RESULT SETS 1";

    st.execute(createProcSQLStr);

    int col = 1;

    psComp.setInt(1, 1);
    psComp.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
    psComp.setNull(3, Types.TIMESTAMP);
    psComp.setNull(4, Types.CLOB);

    psComp.executeUpdate();

  /*  String update = "update test set time2 = ?";
    PreparedStatement psUpdate = conn.prepareStatement(update);
    psUpdate.setString(1, "1962-09-23 03:23:34.234777");
    psUpdate.executeUpdate();*/

    CallableStatement sql = conn.prepareCall("call PROC_SQL()");
    sql.execute();
    ResultSet rs = sql.getResultSet();
    while (rs.next()) {
      rs.getTimestamp(1);
    }
  }

  public void testBug48010_1() throws Exception {


    //SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;
    Properties props = new Properties();
    props.setProperty("server-groups", "SG1");

    Connection conn = null;
    conn = getConnection(props);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    s = conn.createStatement();

    // Creating a statement object that we can use for running various
    // SQL statements commands against the database.
    String tab1 = " create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, time timestamp, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
        + " constraint exc_ch "
        + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))";

    String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
        "hkse", "tse" };
    // We create a table...
    s.execute(tab1);
    psInsert4 = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?,?,?)");

    for (int i = -30; i < 0; ++i) {
      psInsert4.setInt(1, i);
      psInsert4.setString(2, "symbol" + i * -1);
      psInsert4.setFloat(3, i * 131.46f);
      psInsert4.setString(4, exchange[(-1 * -5) % 7]);
      psInsert4.setInt(5, 2);
      psInsert4.setDate(6, new java.sql.Date(System.currentTimeMillis()));
      assertEquals(1, psInsert4.executeUpdate());
    }
    conn.commit();
    s.execute("create index sec_price on trade.securities(price)");
    s.execute("create index sec_id on trade.securities(sec_id)");
    s.execute("create index sec_tid on trade.securities(tid)");
    s.execute("create index sec_exchange on trade.securities(exchange)");
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost) {
        if (memIndex.getGemFireContainer().getTableName().toLowerCase().indexOf("sec_price") != -1) {
          return Double.MAX_VALUE;
        }
        return Double.MIN_VALUE;//optimzerEvalutatedCost;
      }
    };

    GemFireXDQueryObserverHolder.setInstance(observer);

    String query = "select tid, exchange from trade.securities where (price<? or price >=?)";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setFloat(1, 50f);
    ps.setFloat(2, 60f);
    ResultSet rs = ps.executeQuery();
    int numRows = 0;
    while (rs.next()) {
      rs.getInt(1);

      ++numRows;
    }
    assertEquals(30, numRows);
  }

  public void testBug48010_2() throws Exception {


    //SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;
    Properties props = new Properties();
    props.setProperty("server-groups", "SG1");

    Connection conn = null;
    conn = getConnection(props);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    s = conn.createStatement();

    // Creating a statement object that we can use for running various
    // SQL statements commands against the database.
    String tab1 = " create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, time timestamp, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
        + " constraint exc_ch "
        + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))";

    String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
        "hkse", "tse" };
    // We create a table...
    s.execute(tab1);
    psInsert4 = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?,?,?)");

    for (int i = -30; i < 0; ++i) {
      psInsert4.setInt(1, i);
      psInsert4.setString(2, "symbol" + i * -1);
      psInsert4.setFloat(3, i * 131.46f);
      psInsert4.setString(4, exchange[(-1 * -5) % 7]);
      psInsert4.setInt(5, i * -2);
      psInsert4.setDate(6, new java.sql.Date(System.currentTimeMillis()));
      assertEquals(1, psInsert4.executeUpdate());
    }
    conn.commit();
    s.execute("create index sec_price on trade.securities(price)");
    s.execute("create index sec_id on trade.securities(sec_id)");
    s.execute("create index sec_tid on trade.securities(tid)");
    s.execute("create index sec_exchange on trade.securities(exchange)");


    //GemFireXDQueryObserverHolder.setInstance(observer);

    String query = "select tid, exchange from trade.securities where (price >? or tid >=?)";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setFloat(1, -132f);
    ps.setFloat(2, 59);
    ResultSet rs = ps.executeQuery();
    int numRows = 0;
    while (rs.next()) {
      rs.getInt(1);

      ++numRows;
    }
    assertEquals(2, numRows);
  }

  private final String myjar = getResourcesDir() + "/lib/myjar.jar";
  private final String booksJar = getResourcesDir() + "/lib/Books.jar";

  public void testSysRoutinePermissions_48279() throws Throwable {
    System.setProperty("gemfire.off-heap-memory-size", "500m");
    Properties props = new Properties();
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("gemfirexd.user.admin", "pass");
    props.setProperty("user", "admin");
    props.setProperty("password", "pass");
    setupConnection(props);

    final int netPort = startNetserverAndReturnPort();

    props.clear();
    props.setProperty("user", "admin");
    props.setProperty("password", "pass");
    final Connection conn = TestUtil.getNetConnection(netPort, null, props);
    final Statement stmt = conn.createStatement();
    stmt.execute("call sys.create_user('scott', 'pass')");

    props.clear();
    props.setProperty("user", "scott");
    props.setProperty("password", "pass");
    final Connection conn2 = TestUtil.getNetConnection(netPort, null, props);
    final Statement stmt2 = conn2.createStatement();
    // scott should not be able to create users by default
    try {
      stmt2.execute("call sys.create_user('ramesh', 'pass')");
      fail("expected create_user to fail");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    props.clear();
    props.setProperty("user", "ramesh");
    props.setProperty("password", "pass");
    // connection should fail
    try {
      TestUtil.getConnection(props);
      fail("expected connection to fail with auth exception");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // create_user should work for SCOTT after admin grants the rights
    stmt.execute("GRANT execute on procedure sys.create_user to scott");
    stmt2.execute("call sys.create_user('ramesh', 'pass')");
    // creation of same user with different case should fail (#48377)
    try {
      stmt.execute("call sys.create_user('RaMesh', 'pASS')");
      fail("expected create_user to fail with user already exists");
    } catch (SQLException sqle) {
      if (!"28504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    Connection conn3 = TestUtil.getConnection(props);
    Statement stmt3 = conn3.createStatement();
    try {
      stmt3.execute("call sys.create_user('chen', 'pass2')");
      fail("expected create_user to fail");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // but SCOTT cannot GRANT privileges
    try {
      stmt2.execute("GRANT execute on procedure sys.create_user to ramesh");
      fail("expected grant to fail");
    } catch (SQLException sqle) {
      if (!"42506".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // create_user should work for RAMESH after admin grants the rights
    stmt.execute("GRANT execute on procedure sys.create_user to Ramesh");
    stmt3.execute("call sys.create_user('chen', 'pass2')");

    props.clear();
    props.setProperty("user", "chen");
    props.setProperty("password", "pass2");
    TestUtil.getConnection(props);

    // should fail again after revoke by admin
    stmt.execute(
        "revoke execute on procedure sys.create_user from ramesh restrict");
    try {
      stmt3.execute("call sys.create_user('chen2', 'pass2')");
      fail("expected create_user to fail");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt2.execute("call sys.create_user('chen2', 'pass2')");

    // check for other GemFireXD SYS procedures

    // CHANGE_PASSWORD
    try {
      stmt2.execute("call sys.change_password('ramesh', 'pass', 'pass2')");
      fail("expected authz exception");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("GRANT execute on procedure sys.change_password to scott");
    stmt2.execute("call sys.change_password('ramesh', 'pass', 'pass2')");

    props.clear();
    props.setProperty("user", "RAMESH");
    props.setProperty("password", "pass");
    try {
      TestUtil.getConnection(props);
      fail("expected auth exception");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    props.setProperty("password", "pass2");
    conn3.close();
    conn3 = TestUtil.getConnection(props);
    stmt3 = conn3.createStatement();

    // CHANGE_PASSWORD should be allowed for user himself (#48372)
    // should fail if oldPassword does not match
    try {
      stmt3.execute("call sys.change_password('ramesh', 'pass', 'pass3')");
      fail("expected exception due to old password mismatch");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt3.execute("call sys.change_password('Ramesh', 'pass2', 'pass3')");
    conn3.close();
    try {
      TestUtil.getConnection(props);
      fail("expected auth exception");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    props.setProperty("password", "pass3");
    TestUtil.getConnection(props);

    // CHANGE_PASSWORD should be allowed without old_password to admin users who
    // have been granted explicit permissions
    try {
      stmt2.execute("call sys.change_password('ramesh', 'p', 'pass4')");
      fail("expected exception due to old password mismatch");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    TestUtil.getConnection(props);

    stmt2.execute("call sys.change_password('ramesh', null, 'pass4')");
    try {
      TestUtil.getConnection(props);
      fail("expected auth exception");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    props.setProperty("password", "pass4");
    TestUtil.getConnection(props);

    try {
      stmt.execute("call sys.change_password('ramesh', 'p', 'pass5')");
      fail("expected auth exception");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    TestUtil.getConnection(props);

    stmt.execute("call sys.change_password('raMesh', null, 'pass5')");
    try {
      TestUtil.getConnection(props);
      fail("expected auth exception");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    props.setProperty("password", "pass5");
    TestUtil.getConnection(props);

    // DROP_USER
    try {
      stmt2.execute("call sys.drop_user('chen')");
      fail("expected authz exception");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("GRANT execute on procedure sys.drop_user to scott");

    props.clear();
    props.setProperty("user", "chen");
    props.setProperty("password", "pass2");
    TestUtil.getConnection(props);
    stmt2.execute("call sys.drop_user('chen')");
    try {
      TestUtil.getNetConnection(netPort, null, props);
      fail("expected auth exception");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // SET/GET_CRITICAL_HEAP_PERCENTAGE
    try {
      stmt2.execute("call SYS.SET_CRITICAL_HEAP_PERCENTAGE(92.0)");
      fail("expected authz exception");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute(
        "GRANT execute on procedure sys.set_critical_heap_percentage to scott");
    stmt2.execute("call SYS.SET_CRITICAL_HEAP_PERCENTAGE(92.0)");
    ResultSet rs = stmt2
        .executeQuery("values SYS.GET_CRITICAL_HEAP_PERCENTAGE()");
    assertTrue(rs.next());
    assertEquals(92, rs.getInt(1));
    assertFalse(rs.next());

    // SET/GET_EVICTION_HEAP_PERCENTAGE
    try {
      stmt2.execute("call SYS.SET_EVICTION_HEAP_PERCENTAGE(70.0)");
      fail("expected authz exception");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute(
        "GRANT execute on procedure sys.set_eviction_heap_percentage to scott");
    stmt2.execute("call SYS.SET_EVICTION_HEAP_PERCENTAGE(70.0)");
    rs = stmt2.executeQuery("values SYS.GET_EVICTION_HEAP_PERCENTAGE()");
    assertTrue(rs.next());
    assertEquals(70, rs.getInt(1));
    assertFalse(rs.next());

    // SET/GET_CRITICAL_OFFHEAP_PERCENTAGE
    try {
      stmt2.execute("call SYS.SET_CRITICAL_OFFHEAP_PERCENTAGE(92.0)");
      fail("expected authz exception");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute(
        "GRANT execute on procedure sys.set_critical_offheap_percentage to scott");
    stmt2.execute("call SYS.SET_CRITICAL_OFFHEAP_PERCENTAGE(92.0)");
    rs = stmt2
        .executeQuery("values SYS.GET_CRITICAL_OFFHEAP_PERCENTAGE()");
    assertTrue(rs.next());
    assertEquals(92, rs.getInt(1));
    assertFalse(rs.next());

    // SET/GET_EVICTION_OFFHEAP_PERCENTAGE
    try {
      stmt2.execute("call SYS.SET_EVICTION_OFFHEAP_PERCENTAGE(70.0)");
      fail("expected authz exception");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute(
        "GRANT execute on procedure sys.set_eviction_offheap_percentage to scott");
    stmt2.execute("call SYS.SET_EVICTION_OFFHEAP_PERCENTAGE(70.0)");
    rs = stmt2.executeQuery("values SYS.GET_EVICTION_OFFHEAP_PERCENTAGE()");
    assertTrue(rs.next());
    assertEquals(70, rs.getInt(1));
    assertFalse(rs.next());

    // REBALANCE_ALL_BUCKETS
    try {
      stmt2.execute("call SYS.REBALANCE_ALL_BUCKETS()");
      fail("expected authz exception");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("GRANT execute on procedure sys.REBALANCE_ALL_BUCKETS to scott");
    stmt2.execute("call SYS.REBALANCE_ALL_BUCKETS()");

    Class<?>[] expectedexceptionlist = new Class[] { SQLNonTransientConnectionException.class };
    // INSTALL/REPLACE/REMOVE_JAR
    final String localHostName = "localhost";
    try {
      TestUtil.addExpectedException(expectedexceptionlist);
      JarTools.main(new String[] { "install-jar", "-file=" + myjar,
          "-name=app.sample2", "-client-port=" + netPort,
          "-client-bind-address=" + localHostName });
      fail("expected auth exception");
    } catch (GemFireTerminateError err) {
      Throwable sqle = err.getCause();
      if (!(sqle instanceof SQLException && "08004".equals(((SQLException)sqle)
          .getSQLState()))) {
        throw sqle;
      }
    } finally {
      TestUtil.removeExpectedException(expectedexceptionlist);
    }

    expectedexceptionlist = new Class[] { java.sql.SQLSyntaxErrorException.class };
    try {
      TestUtil.addExpectedException(expectedexceptionlist);
      JarTools.main(new String[] { "install-jar", "-file=" + myjar,
          "-name=app.sample2", "-client-port=" + netPort,
          "-client-bind-address=" + localHostName, "-user=scott",
          "-password=pass" });
      fail("expected authz exception");
    } catch (GemFireTerminateError err) {
      Throwable sqle = err.getCause();
      if (!(sqle instanceof SQLException && "42504".equals(((SQLException)sqle)
          .getSQLState()))) {
        throw sqle;
      }
    } finally {
      TestUtil.removeExpectedException(expectedexceptionlist);
    }
    stmt.execute("GRANT execute on procedure sqlj.install_jar_bytes to scott");
    JarTools.main(new String[] { "install-jar", "-file=" + myjar,
        "-name=app.sample2", "-client-port=" + netPort,
        "-client-bind-address=" + localHostName, "-user=scott",
        "-password=pass" });

    try {
      TestUtil.addExpectedException(expectedexceptionlist);
      JarTools.main(new String[] { "replace-jar", "-file=" + booksJar,
          "-name=app.sample2", "-client-port=" + netPort,
          "-client-bind-address=" + localHostName, "-user=scott",
          "-password=pass" });
      fail("expected authz exception");
    } catch (GemFireTerminateError err) {
      Throwable sqle = err.getCause();
      if (!(sqle instanceof SQLException && "42504".equals(((SQLException)sqle)
          .getSQLState()))) {
        throw sqle;
      }
    } finally {
      TestUtil.removeExpectedException(expectedexceptionlist);
    }
    stmt.execute("GRANT execute on procedure sqlj.replace_jar_bytes to scott");
    JarTools.main(new String[] { "replace-jar", "-file=" + booksJar,
        "-name=app.sample2", "-client-port=" + netPort,
        "-client-bind-address=" + localHostName, "-user=scott",
        "-password=pass" });

    try {
      TestUtil.addExpectedException(expectedexceptionlist);
      JarTools.main(new String[] { "remove-jar", "-name=app.sample2",
          "-client-port=" + netPort, "-client-bind-address=" + localHostName,
          "-user=scott", "-password=pass" });
      fail("expected authz exception");
    } catch (GemFireTerminateError err) {
      Throwable sqle = err.getCause();
      if (!(sqle instanceof SQLException && "42504".equals(((SQLException)sqle)
          .getSQLState()))) {
        throw sqle;
      }
    } finally {
      TestUtil.removeExpectedException(expectedexceptionlist);
    }
    stmt.execute("GRANT execute on procedure sqlj.remove_jar to scott");
    JarTools.main(new String[] { "remove-jar", "-name=app.sample2",
        "-client-port=" + netPort, "-client-bind-address=" + localHostName,
        "-user=scott", "-password=pass" });
  }

  public void testAdminChangePassword_47917() throws Throwable {
    Properties props = new Properties();
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("gemfirexd.sql-authorization", "false");
    props.setProperty("gemfirexd.user.admin", "pass");
    props.setProperty("user", "admin");
    props.setProperty("password", "pass");
    setupConnection(props);

    final int netPort = startNetserverAndReturnPort();

    props.clear();
    props.setProperty("user", "admin");
    props.setProperty("password", "pass");
    final Connection conn = TestUtil.getNetConnection(netPort, null, props);
    final Statement stmt = conn.createStatement();
    stmt.execute("call sys.create_user('scott', 'pass')");
    stmt.execute("call sys.create_user('ramesh', 'pass')");

    props.clear();
    props.setProperty("user", "scott");
    props.setProperty("password", "pass");
    final Connection conn2 = TestUtil.getNetConnection(netPort, null, props);
    final Statement stmt2 = conn2.createStatement();
    // change password should work for self
    stmt2.execute("call sys.change_password('scott', 'pass', 'pass2')");
    // change password should fail for others
    try {
      stmt2.execute("call sys.change_password('ramesh', '', 'pass2')");
      fail("expected failure in changing ramesh's password");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // but should be allowed for admin user
    stmt.execute("call sys.change_password('scott', '', 'pass2')");
    stmt.execute("call sys.change_password('scott', null, 'pass3')");
    stmt.execute("call sys.change_password('ramesh', '', 'pass2')");
    stmt.execute("call sys.change_password('ramesh', null, 'pass3')");
    // but still fail if an incorrect old password is provided
    try {
      stmt.execute("call sys.change_password('scott', 'pass', 'pass4')");
      fail("expected failure in changing password");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    Connection conn3;
    try {
      conn3 = TestUtil.getNetConnection(netPort, null, props);
      fail("expected auth failure");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    props.clear();
    props.setProperty("user", "scott");
    props.setProperty("password", "pass3");
    conn3 = TestUtil.getNetConnection(netPort, null, props);
  }

  public static void checkLobs(ResultSet rs, char[] clobChars, byte[] blobBytes)
      throws Exception {
    assertTrue(rs.next());
    Clob clob = rs.getClob(7);
    Blob blob = rs.getBlob("PVT");

    BufferedReader reader = new BufferedReader(clob.getCharacterStream());
    int cLen = (int)clob.length();
    char[] charArray = new char[cLen];
    int offset = 0;
    int read;
    while ((read = reader.read(charArray, offset, cLen - offset)) != -1) {
      offset += read;
      if (offset >= cLen) {
        break;
      }
    }
    if (reader.read() != -1) {
      fail("extra characters in CLOB after its length " + cLen);
    }
    reader.close();

    InputStream is = blob.getBinaryStream();
    int bLen = (int)blob.length();
    byte[] byteArray = new byte[bLen];
    offset = 0;
    while ((read = is.read(byteArray, offset, bLen - offset)) != -1) {
      offset += read;
      if (offset >= bLen) {
        break;
      }
    }
    if (is.read() != -1) {
      fail("extra characters in BLOB after its length " + bLen);
    }
    is.close();

    assertEquals(new String(clobChars), new String(charArray));
    assertTrue(Arrays.equals(blobBytes, byteArray));
    assertFalse(rs.next());
  }

  public void runTestMultipleRowsUpdate(Connection conn) throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("create table testlob (id int primary key, text clob, "
        + "text2 varchar(10), text3 long varchar, bin blob, "
        + "bin2 long varchar for bit data) "
        + "partition by primary key persistent");
    stmt.execute("insert into testlob values (1, 'one', '1', 'ONE', "
        + "X'a0b0', X'0a')");
    stmt.execute("insert into testlob values (2, 'two', '2', 'TWO', "
        + "X'a1b1', X'1a')");

    PreparedStatement pstmt;
    Reader reader;
    byte[] buf;
    InputStream is;
    Clob clob;
    Blob blob;
    ResultSet rs;

    // check batch inserts on LOBs
    pstmt = conn.prepareStatement("insert into testlob values (?,?,?,?,?,?)");
    pstmt.setInt(1, 3);
    clob = conn.createClob();
    clob.setCharacterStream(1).write("three");
    pstmt.setClob(2, clob);
    reader = new CharArrayReader("3".toCharArray());
    pstmt.setCharacterStream(3, reader);
    reader = new CharArrayReader("THREE".toCharArray());
    pstmt.setCharacterStream(4, reader);
    buf = new byte[] { 1, 2, 3 };
    blob = conn.createBlob();
    blob.setBinaryStream(1).write(buf);
    pstmt.setBlob(5, blob);
    buf = new byte[] { 4, 5, 6 };
    is = new ByteArrayInputStream(buf);
    pstmt.setBinaryStream(6, is);
    pstmt.addBatch();

    pstmt.setInt(1, 4);
    reader = new CharArrayReader("four".toCharArray());
    pstmt.setCharacterStream(2, reader);
    pstmt.setString(3, "4");
    reader = new CharArrayReader("FOUR".toCharArray());
    pstmt.setCharacterStream(4, reader);
    buf = new byte[] { 4, 5, 6 };
    is = new ByteArrayInputStream(buf);
    pstmt.setBinaryStream(5, is);
    buf = new byte[] { 7, 8, 9 };
    is = new ByteArrayInputStream(buf);
    pstmt.setBinaryStream(6, is);
    pstmt.addBatch();
    pstmt.executeBatch();

    rs = stmt.executeQuery("select * from testlob order by id");
    checkLobColumns(rs, 1, "one", "1", "ONE", new byte[] { (byte)0xa0,
        (byte)0xb0 }, new byte[] { 0x0a });
    checkLobColumns(rs, 2, "two", "2", "TWO", new byte[] { (byte)0xa1,
        (byte)0xb1 }, new byte[] { 0x1a });
    checkLobColumns(rs, 3, "three", "3", "THREE", new byte[] { 1, 2, 3 },
        new byte[] { 4, 5, 6 });
    checkLobColumns(rs, 4, "four", "4", "FOUR", new byte[] { 4, 5, 6 },
        new byte[] { 7, 8, 9 });
    assertFalse(rs.next());
    rs.close();

    // check bulk DMLs on CLOBs
    pstmt = conn.prepareStatement("update testlob set text=?");
    clob = conn.createClob();
    clob.setCharacterStream(1).write("text");
    pstmt.setClob(1, clob);
    assertEquals(4, pstmt.executeUpdate());
    conn.commit();
    rs = stmt.executeQuery("select text from testlob");
    assertTrue(rs.next());
    assertEquals("text", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text", rs.getString(1));
    assertFalse(rs.next());

    pstmt = conn.prepareStatement("update testlob set text=?");
    reader = new CharArrayReader("text2".toCharArray());
    pstmt.setCharacterStream(1, reader);
    assertEquals(4, pstmt.executeUpdate());
    conn.commit();
    rs = stmt.executeQuery("select text from testlob");
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertFalse(rs.next());

    pstmt = conn.prepareStatement("update testlob set text2=?");
    reader = new CharArrayReader("text3".toCharArray());
    pstmt.setCharacterStream(1, reader);
    assertEquals(4, pstmt.executeUpdate());
    conn.commit();
    rs = stmt.executeQuery("select text2 from testlob");
    assertTrue(rs.next());
    assertEquals("text3", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text3", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text3", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text3", rs.getString(1));
    assertFalse(rs.next());

    // check bulk DMLs on BLOBs
    pstmt = conn.prepareStatement("update testlob set bin=?");
    buf = new byte[] { 10, 20, 30 };
    blob = conn.createBlob();
    blob.setBinaryStream(1).write(buf);
    pstmt.setBlob(1, blob);
    assertEquals(4, pstmt.executeUpdate());
    conn.commit();
    rs = stmt.executeQuery("select bin from testlob");
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertFalse(rs.next());

    pstmt = conn.prepareStatement("update testlob set bin=?");
    buf = new byte[] { 30, 40, 50 };
    is = new ByteArrayInputStream(buf);
    pstmt.setBinaryStream(1, is);
    assertEquals(4, pstmt.executeUpdate());
    conn.commit();
    rs = stmt.executeQuery("select bin from testlob");
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertFalse(rs.next());

    pstmt = conn.prepareStatement("update testlob set bin2=?");
    buf = new byte[] { 5, 6, 7, 8, 9 };
    is = new ByteArrayInputStream(buf);
    pstmt.setBinaryStream(1, is);
    assertEquals(4, pstmt.executeUpdate());
    conn.commit();
    rs = stmt.executeQuery("select bin2 from testlob");
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(buf, rs.getBytes(1)));
    assertFalse(rs.next());

    // also check for batch statements
    pstmt = conn.prepareStatement("update testlob set text2=? where id=?");
    for (int i = 1; i <= 2; i++) {
      pstmt.setString(1, "text" + i);
      pstmt.setInt(2, i);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
    conn.commit();
    rs = stmt.executeQuery("select text2 from testlob order by id");
    assertTrue(rs.next());
    assertEquals("text1", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text3", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text3", rs.getString(1));
    assertFalse(rs.next());

    pstmt = conn.prepareStatement("update testlob set text=? where id=?");
    for (int i = 1; i <= 2; i++) {
      reader = new CharArrayReader(("text" + i).toCharArray());
      pstmt.setCharacterStream(1, reader);
      pstmt.setInt(2, i);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
    conn.commit();
    rs = stmt.executeQuery("select text from testlob order by id");
    assertTrue(rs.next());
    assertEquals("text1", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("text2", rs.getString(1));
    assertFalse(rs.next());

    pstmt = conn.prepareStatement("update testlob set bin=? where id=?");
    for (int i = 1; i <= 2; i++) {
      blob = conn.createBlob();
      blob.setBinaryStream(1).write(
          new byte[] { (byte)(30 + i), (byte)(31 + i), (byte)(32 + i),
              (byte)(33 + i) });
      pstmt.setBlob(1, blob);
      pstmt.setInt(2, i);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
    conn.commit();
    rs = stmt.executeQuery("select bin from testlob order by id");
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { (byte)(30 + 1),
        (byte)(31 + 1), (byte)(32 + 1), (byte)(33 + 1) }, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { (byte)(30 + 2),
        (byte)(31 + 2), (byte)(32 + 2), (byte)(33 + 2) }, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { 30, 40, 50 }, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { 30, 40, 50 }, rs.getBytes(1)));
    assertFalse(rs.next());

    pstmt = conn.prepareStatement("update testlob set bin2=? where id=?");
    for (int i = 1; i <= 2; i++) {
      is = new ByteArrayInputStream(new byte[] { (byte)(40 + i),
          (byte)(41 + i), (byte)(42 + i), (byte)(43 + i) });
      pstmt.setBinaryStream(1, is);
      pstmt.setInt(2, i);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
    conn.commit();
    rs = stmt.executeQuery("select bin2 from testlob order by id");
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { (byte)(40 + 1),
        (byte)(41 + 1), (byte)(42 + 1), (byte)(43 + 1) }, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { (byte)(40 + 2),
        (byte)(41 + 2), (byte)(42 + 2), (byte)(43 + 2) }, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { 5, 6, 7, 8, 9 }, rs.getBytes(1)));
    assertTrue(rs.next());
    assertTrue(Arrays.equals(new byte[] { 5, 6, 7, 8, 9 }, rs.getBytes(1)));
    assertFalse(rs.next());

    stmt.execute("drop table testlob");
  }

  private static void checkLobColumns(ResultSet rs, int col1, String col2,
      String col3, String col4, byte[] col5, byte[] col6) throws Exception {
    Clob clob;
    Reader reader;
    Blob blob;
    InputStream is;

    assertTrue(rs.next());
    assertEquals(col1, rs.getInt(1));
    assertEquals(col2, rs.getString(2));
    clob = rs.getClob(2);
    assertEquals(col2, clob.getSubString(1, (int)clob.length()));
    clob = rs.getClob(2);
    reader = clob.getCharacterStream();
    for (int i = 0; i < col2.length(); i++) {
      assertEquals(col2.charAt(i), reader.read());
    }
    assertEquals(-1, reader.read());
    reader = rs.getCharacterStream(2);
    for (int i = 0; i < col2.length(); i++) {
      assertEquals(col2.charAt(i), reader.read());
    }
    assertEquals(-1, reader.read());
    assertEquals(col3, rs.getString(3));
    assertEquals(col4, rs.getString(4));
    reader = rs.getCharacterStream(4);
    for (int i = 0; i < col4.length(); i++) {
      assertEquals(col4.charAt(i), reader.read());
    }
    assertEquals(-1, reader.read());
    blob = rs.getBlob(5);
    is = blob.getBinaryStream();
    for (int i = 0; i < col5.length; i++) {
      assertEquals(col5[i], (byte)is.read());
    }
    assertEquals(-1, is.read());
    is = rs.getBinaryStream(5);
    for (int i = 0; i < col5.length; i++) {
      assertEquals(col5[i], (byte)is.read());
    }
    assertEquals(-1, is.read());
    is = rs.getBinaryStream(6);
    for (int i = 0; i < col6.length; i++) {
      assertEquals(col6[i], (byte)is.read());
    }
    assertEquals(-1, is.read());
  }

  public static class DataGenerator {
    protected static String[] exchanges = { "nasdaq", "nye", "amex", "lse",
        "fse", "hkse", "tse" /*, "notAllowed"*/
    };

    private Random rand = new Random();

    public void insertIntoSecurities(PreparedStatement ps, int sec_id)
        throws SQLException {
      ps.setInt(1, sec_id);
      ps.setString(2, getSymbol(1, 8));
      ps.setBigDecimal(3, getPrice());
      ps.setString(4, getExchange());
      ps.setInt(5, 50);
      try {
        ps.executeUpdate();
      } catch (SQLException sqle) {
        if (sqle.getSQLState().equals("23505")) {
          // ok;
        } else {
          throw sqle;
        }
      }
    }

    public void insertIntoCompanies(PreparedStatement ps, String symbol,
        String exchange) throws SQLException {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;
      String companyName = "+=uqGd1w]bR6z[!j]|Chc>WTdf";
      Clob[] companyInfo = getClob(100);
      String note = getString(31000, 32700);
      UDTPrice price = getRandomPrice();
      long asset = rand.nextLong();
      byte[] logo = getByteArray(100);
      ps.setString(1, symbol);
      ps.setString(2, exchange);
      ps.setShort(3, companyType);
      ps.setBytes(4, getUidBytes(uid));
      ps.setObject(5, uid);
      ps.setString(6, companyName);
      if (companyInfo == null)
        ps.setNull(7, Types.CLOB);
      else
        ps.setClob(7, companyInfo[0]);
      ps.setString(8, note);
      ps.setObject(9, price);

      ps.setLong(10, asset);
      ps.setBytes(11, logo);
      ps.setInt(12, 10);
      ps.executeUpdate();

    }

    protected BigDecimal getPrice() {
      // if (!reproduce39418)
      return new BigDecimal(Double.toString((rand.nextInt(10000) + 1) * .01));
      // else
      // return new BigDecimal (((rand.nextInt(10000)+1) * .01)); //to reproduce
      // bug 39418
    }

    // get an exchange
    protected String getExchange() {
      return exchanges[rand.nextInt(exchanges.length)]; // get a random exchange
    }

    public static byte[] getUidBytes(UUID uuid) {
      /*
      byte[] bytes = new byte[uidLength];
      rand.nextBytes(bytes);
      return bytes;
      */
      if (uuid == null)
        return null;
      long[] longArray = new long[2];
      longArray[0] = uuid.getMostSignificantBits();
      longArray[1] = uuid.getLeastSignificantBits();

      byte[] bytes = new byte[16];
      ByteBuffer bb = ByteBuffer.wrap(bytes);
      bb.putLong(longArray[0]);
      bb.putLong(longArray[1]);
      return bytes;
    }

    protected byte[] getByteArray(int maxLength) {
      int arrayLength = rand.nextInt(maxLength) + 1;
      byte[] byteArray = new byte[arrayLength];
      for (int j = 0; j < arrayLength; j++) {
        byteArray[j] = (byte)(rand.nextInt(256));
      }
      return byteArray;
    }

    protected UDTPrice getRandomPrice() {
      BigDecimal low = new BigDecimal(
          Double.toString((rand.nextInt(3000) + 1) * .01)).add(new BigDecimal(
          "20"));
      BigDecimal high = new BigDecimal("10").add(low);
      return new UDTPrice(low, high);
    }

    protected String getString(int minLength, int maxLength) {
      int length = rand.nextInt(maxLength - minLength + 1) + minLength;
      return getRandPrintableVarChar(length);
    }

    protected String getSymbol(int minLength, int maxLength) {
      int aVal = 'a';
      int symbolLength = rand.nextInt(maxLength - minLength + 1) + minLength;
      char[] charArray = new char[symbolLength];
      for (int j = 0; j < symbolLength; j++) {
        charArray[j] = (char)(rand.nextInt(26) + aVal); // one of the char in
        // a-z
      }

      return new String(charArray);
    }

    protected String getRandPrintableVarChar(int length) {
      if (length == 0) {
        return "";
      }

      int sp = ' ';
      int tilde = '~';
      char[] charArray = new char[length];
      for (int j = 0; j < length; j++) {
        charArray[j] = (char)(rand.nextInt(tilde - sp) + sp);
      }
      return new String(charArray);
    }

    protected char[] getCharArray(int length) {
      /*
      int cp1 = 56558;
      char c1 = (char)cp1;
      char ch[];
      ch = Character.toChars(cp1);

      c1 = '\u01DB';
      char c2 = '\u0908';

      Log.getLogWriter().info("c1's UTF-16 representation is is " + c1);
      Log.getLogWriter().info("c2's UTF-16 representation is is " + c2);
      Log.getLogWriter().info(cp1 + "'s UTF-16 representation is is " + ch[0]);
      */
      int arrayLength = rand.nextInt(length) + 1;
      char[] charArray = new char[arrayLength];
      for (int j = 0; j < arrayLength; j++) {
        charArray[j] = getValidChar();
      }
      return charArray;
    }

    protected char getValidChar() {

      // TODO, to add other valid unicode characters
      return (char)(rand.nextInt('\u0527'));
    }

    protected char[] getAsciiCharArray(int length) {
      int arrayLength = rand.nextInt(length) + 1;
      char[] charArray = new char[arrayLength];
      for (int j = 0; j < arrayLength; j++) {
        charArray[j] = (char)(rand.nextInt(128));
      }
      return charArray;
    }

    protected Clob[] getClob(int size) {
      Clob[] profile = new Clob[size];
      int maxClobSize = 1000000;

      try {
        for (int i = 0; i < size; i++) {
          if (rand.nextBoolean()) {
            char[] chars = (rand.nextInt(10) != 0) ? getAsciiCharArray(rand
                .nextInt(maxClobSize) + 1) : getCharArray(rand
                .nextInt(maxClobSize) + 1);
            profile[i] = new SerialClob(chars);

          } else if (rand.nextInt(10) == 0) {
            char[] chars = new char[0];
            profile[i] = new SerialClob(chars);
          } // remaining are null profile
        }
      } catch (SQLException se) {
        fail("unexpected SQLException: " + se, se);
      }
      return profile;
    }
  }

  public void testBug47066() throws Exception {
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    Properties props = new Properties();
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema trade");
    st.execute("create table trade.customerrep " + "(c_next varchar(40),"
        + "c_id int  primary key) " + "replicate");
    { // insert
      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.customerrep values (?, ?)");
      psInsert.setString(1, "aaa'sss");
      psInsert.setInt(2, 1);
      psInsert.executeUpdate();
      psInsert.setString(1, "bbb''sss");
      psInsert.setInt(2, 2);
      psInsert.executeUpdate();
      psInsert.setString(1, "ccc'''sss");
      psInsert.setInt(2, 3);
      psInsert.executeUpdate();
      psInsert.setString(1, "ddd" + "'" + "sss");
      psInsert.setInt(2, 4);
      psInsert.executeUpdate();
    }
    { // insert
      Statement ins = conn.createStatement();
      ins.execute("insert into trade.customerrep values ('xxx''sss', 22)");
      ins.execute("insert into trade.customerrep values ('xxx''sss''ds ddd''', 23)");
    }
    { // verify
      PreparedStatement ps1 = conn
          .prepareStatement("Select c_next from trade.customerrep");
      ResultSet rs = ps1.executeQuery();
      Set<String> hashs = new HashSet<String>();
      hashs.add("aaa'sss");
      hashs.add("bbb''sss");
      hashs.add("ddd'sss");
      hashs.add("xxx'sss");
      hashs.add("xxx'sss'ds ddd'");
      hashs.add("ccc'''sss");
      while (rs.next()) {
        String val = rs.getString(1);
        assertTrue("Removed failed for " + val, hashs.remove(val));
      }
      assertTrue(hashs.isEmpty());
      rs.close();
    }
  }

  public void test48808() throws Exception {
    Connection conn = getConnection();
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

    // bucketIds of all rows should be equal
    PartitionedRegion pr = (PartitionedRegion)Misc.getRegionForTable(
        "TRADE.COMPANIES", true);
    int expectedBucketId = PartitionedRegionHelper.getHashKey(pr, Integer
        .valueOf(new UserType(udt).computeHashCode(-1, 0)));
    Iterator<?> iter = pr.getAppropriateLocalEntriesIterator(null, true, false,
        true, null, false);
    int numEntries = 19;
    while (iter.hasNext()) {
      RowLocation rl = (RowLocation)iter.next();
      assertEquals(expectedBucketId, rl.getBucketID());
      numEntries--;
    }
    assertEquals(0, numEntries);

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

  public void testBug50840() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection cxn = TestUtil.getConnection(props);
    Statement stmt = cxn.createStatement();

    // create a procedure
    stmt.execute("CREATE PROCEDURE Proc3 " + "()"
        + "LANGUAGE JAVA PARAMETER STYLE JAVA " + "MODIFIES SQL DATA "
        + "DYNAMIC RESULT SETS 0 " + "EXTERNAL NAME '"
        + Bugs3Test.class.getName() + ".proc3'");

    stmt.execute("CREATE TABLE MYTABLE(COL1 INT)");

    cxn.createStatement().execute("call proc3()");

    stmt.execute("select * from mytable");
    ResultSet rs = stmt.getResultSet();
    assertTrue(rs.next());
  }

  public void testSELECT_CASE_Bug51286() throws Exception {
    Properties p = new Properties();
    p.setProperty("mcast-port", String.valueOf(AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS)));
    setupConnection(p);
    final int port = TestUtil.startNetserverAndReturnPort();

    Connection conn = TestUtil.getNetConnection(port, null, null);
    //Connection conn = getConnection();

    Statement st = conn.createStatement();

    st.execute("create table Customer (cust_id varchar(32), cust_type char(1), primary key(cust_id, cust_type)) partition by primary key");
    st.execute("create table Product (p_id varchar(32), p_type varchar(60), primary key(p_id, p_type) ) partition by column (p_type, p_id)");
    st.execute("create table OOrder (o_id varchar(32) primary key, "
        + "cust_id varchar(32), "
        + "cust_type char(1), "
        + "p_id varchar(32), "
        + "p_type varchar(60), "
        + "o_dt date default CURRENT_DATE, "
        + "foreign key (cust_id, cust_type) references customer(cust_id, cust_type), "
        + "foreign key (p_id, p_type) references product(p_id, p_type)) "
        + "partition by column (o_dt) ");

    st.execute("insert into customer values ('c-1', 'a'), ('c-2', 'a'), ('c-3', 'b'), ('c-4', 'b')");
    st.execute("insert into product values ('p-1', 'typ1'), ('p-2', 'typ2'), "
        + " ('p-3', 'typ1'), ('p-4', 'typ2'), "
        + " ('p-5', 'typ1'), ('p-6', 'typ2'), "
        + " ('p-7', 'typ1'), ('p-8', 'typ2'), "
        + " ('p-9', 'typ1'), ('p-10', 'typ2'), "
        + " ('p-11', 'typ1'), ('p-12', 'typ2'), "
        + " ('p-13', 'typ1'), ('p-14', 'typ2'), "
        + " ('p-15', 'typ1'), ('p-16', 'typ2'), "
        + " ('p-17', 'typ1'), ('p-18', 'typ2'), "
        + " ('p-19', 'typ1'), ('p-20', 'typ2'),"
        + " ('p-21', 'typ3'), ('p-22', 'typ3'), "
        + " ('p-23', 'typ4'), ('p-24', 'typ4') "
    );


    st.execute("insert into oorder (o_id, cust_id, cust_type, p_id, p_type) values "
        + "('o-1' , 'c-1', 'a', 'p-1', 'typ1'), ('o-2', 'c-1', 'a', 'p-2', 'typ2'), "
        + "('o-3' , 'c-1', 'a', 'p-1', 'typ1'), ('o-4', 'c-1', 'a', 'p-2', 'typ2'), "
        + "('o-5' , 'c-1', 'a', 'p-1', 'typ1'), ('o-6', 'c-1', 'a', 'p-2', 'typ2'), "
        + "('o-7' , 'c-1', 'a', 'p-1', 'typ1'), ('o-8', 'c-1', 'a', 'p-2', 'typ2'), "
        + "('o-9' , 'c-1', 'a', 'p-1', 'typ1'), ('o-10', 'c-1','a', 'p-2', 'typ2'), "
        + "('o-11' , 'c-1','a', 'p-1', 'typ1'), ('o-12', 'c-1','a', 'p-2', 'typ2'), "
        + "('o-13' , 'c-1','a', 'p-1', 'typ1'), ('o-14', 'c-1','a', 'p-2', 'typ2'), "
        + "('o-15' , 'c-1','a', 'p-1', 'typ1'), ('o-16', 'c-1','a', 'p-2', 'typ2'), "
        + "('o-17' , 'c-1','a', 'p-1', 'typ1'), ('o-18', 'c-1','a', 'p-2', 'typ2'), "
        + "('o-19' , 'c-1','a', 'p-1', 'typ1'), ('o-20', 'c-1','a', 'p-2', 'typ2'), "
        + "('o-21' , 'c-1','a', 'p-21', 'typ3'), ('o-22', 'c-1','a', 'p-21', 'typ3'), "
        + "('o-23' , 'c-1','a', 'p-23', 'typ4'), ('o-24', 'c-1','a', 'p-23', 'typ4') "
    );

    PreparedStatement ps = conn.prepareStatement("select case "
        + " when p_type = 'typ1' then 'type 1' "
        + " when p_type = 'typ2' then 'type 2' "
        + " else 'long string' "
        + "end "
        + " from product fetch first 10 rows only");

    ResultSet rs = ps.executeQuery();
    while (rs.next()) {
      String v = rs.getString(1);
      assertTrue(v.equals("type 1") || v.equals("type 2") || v.equals("long string"));
    }
    rs.close();

    rs = st.executeQuery("select decode (p_type, 'typ1', 'TYPE ONE', 'typ2', 'TYPE ID TWO', 'typ3', 'TYPE3') "
        + " from product");
    int typ1 = 0, typ2 = 0, typ3 = 0, others = 0;
    while (rs.next()) {
      String val = rs.getString(1);
      if ("TYPE ONE".equals(val)) {
        typ1++;
      } else if ("TYPE ID TWO".equals(val)) {
        typ2++;
      } else if ("TYPE3".equals(val)) {
        typ3++;
      } else {
        others++;
      }
    }
    assertEquals(10, typ1);
    assertEquals(10, typ2);
    assertEquals(2, typ3);
    assertEquals(2, others);
  }

  public void testCASE_IN_Bug_51249() throws Exception {
    Properties p = new Properties();
    p.setProperty("mcast-port", String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    setupConnection(p);
    int port = TestUtil.startNetserverAndReturnPort();

    Connection conn = TestUtil.getNetConnection(port, null, null);
    //Connection conn = getConnection();

    Statement st = conn.createStatement();

    st.execute("CREATE TABLE PRODUCT_CUR (" +
        "ROW_WID bigint NOT NULL," +
        "ITEM_11I_ID bigint," +
        "FMLY varchar(60)," +
        "FORECAST_CATEGORY varchar(40)," +
        "PROFIT_CTR_CD varchar(30)," +
        "PROD_LN varchar(60)," +
        "RPTG_PROD_TYPE varchar(180)," +
        "FORECAST_CATEGORY_GROUP varchar(40)" +
        ") "
    );

    PreparedStatement ins = conn.prepareStatement("insert into product_cur (ROW_WID, FMLY) values (?, ?)");
    for (int i = 0; i < 100; i++) {
      ins.setInt(1, i);
      ins.setString(2, (i % 2 == 0 ? "ATMOS" : (i % 3 == 0 ? "CENTERA" : "VENTURA CAP")));
      ins.addBatch();
    }
    ins.executeBatch();
    ins.clearBatch();

    PreparedStatement ps = conn.prepareStatement("SELECT CASE " +
        "WHEN PRODUCT_CUR.FMLY IN ('ATMOS', 'CENTERA') THEN 'FOUND' " +
        "ELSE 'NOT FOUND' END , PRODUCT_CUR.FMLY " +
        "from PRODUCT_CUR " +
        "FETCH FIRST 10 ROWS ONLY ");

    final ResultSet rs = ps.executeQuery();
    int foundRows = 0, notFoundRows = 0;
    while (rs.next()) {
      final String fmly = rs.getString(2);
      if ("ATMOS".equals(fmly) || "CENTERA".equals(fmly)) {
        foundRows++;
        assertTrue("FOUND".equals(rs.getString(1)));
      } else {
        notFoundRows++;
        assertTrue("NOT FOUND".equals(rs.getString(1)));
      }
    }
    assertEquals(7, foundRows);
    assertEquals(3, notFoundRows);
  }

  public void createTables51718(Connection conn, Statement st) throws Exception {
    String extra_jtests = getResourcesDir();
    GemFireXDUtils.executeSQLScripts(conn, new String[] {
            extra_jtests + "/lib/rtrddl.sql",
            extra_jtests + "/lib/sales_credits_ddl.sql",
            extra_jtests + "/lib/003_create_gdw_table_indexes.sql"
        }, false,
        getLogger(), null, null, false);
  }

  public void importData51718(Statement st) throws Exception {
    String extra_jtests = getResourcesDir();
    String importFile1 = extra_jtests + "/lib/rtr.dat";
    String importFile2 = extra_jtests + "/lib/SALES_CREDITS20000.dat";

    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX('RTRADM', "
        + "'RTR_REPORT_DATA', '" + importFile1 + "', "
        + "NULL, NULL, NULL, 0, 0, 4, 0, null, null)");

    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX('RTRADM', "
        + "'SALES_CREDITS', '" + importFile2 + "', "
        + "NULL, NULL, NULL, 0, 0, 6, 0, null, null)");
  }

  public void testBug51718() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
//    props.setProperty("gemfirexd.debug.true", "QueryDistribution");
    props.setProperty("table-default-partitioned", "false");
    props.put("mcast-port", String.valueOf(mcastPort));
    props.setProperty("user", "RTRADM");
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    createTables51718(conn, st);
    importData51718(st);
//    st.execute("call sys.SET_TRACE_FLAG('QueryDistribution', 1)");
//    st.execute("call sys.SET_TRACE_FLAG('TraceExecution', 1)");
//    System.setProperty("gemfirexd.optimizer.trace", "true");
    String query = "SELECT " +
        "RTRADM.RTR_REPORT_DATA.ORD_NUM, " +
        "SUM(RTRADM.RTR_REPORT_DATA.REVENUE*RTRADM.SALES_CREDITS.PERCENT/100) " +
        "FROM " +
        "RTRADM.RTR_REPORT_DATA, " +
        "RTRADM.SALES_CREDITS " +
        "WHERE " +
        "( RTRADM.SALES_CREDITS.SO_NUMBER=RTRADM.RTR_REPORT_DATA.ORD_NUM ) " +
        "AND ( RTRADM.RTR_REPORT_DATA.RECORD_STATUS= '  ' ) " +
        "AND ( RTRADM.SALES_CREDITS.EFFECTIVE_END_DATE IS NULL ) " +
        "GROUP BY " +
        "RTRADM.RTR_REPORT_DATA.ORD_NUM";

    ResultSet rs = st.executeQuery(query);
    // results not verified. test just checks whether the query throws assert
    // failure due to -ve optimizer cost
    while (rs.next()) ;
  }

  public void testBug51958() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
//    props.setProperty("gemfirexd.debug.true", "QueryDistribution");
    props.setProperty("table-default-partitioned", "false");
    props.put("mcast-port", String.valueOf(mcastPort));
    props.setProperty("user", "RTI");
    Connection conn = TestUtil.getConnection(props);

    Statement st = conn.createStatement();
    st.execute("CREATE TABLE rti.test_table " +
        "(identifier varchar (500), PRIMARY KEY(identifier)) " +
        "PERSISTENT ASYNCHRONOUS");

    st.execute("insert into rti.test_table values ('1')");
    st.execute("insert into rti.test_table values ('2')");

    st.execute("select count(*) from rti.test_table tt where " +
        "tt.identifier is null");
    ResultSet rs = st.getResultSet();
    while (rs.next()) {
      int count = rs.getInt(1);
      assertEquals(0, count);
      System.out.println("count1 " + rs.getInt(1));
    }

    st.execute("select count(*) from rti.test_table tt");
    rs = st.getResultSet();
    while (rs.next()) {
      int count = rs.getInt(1);
      assertEquals(2, count);
      System.out.println("count2 " + rs.getInt(1));
    }

    st.execute("select count(*) from rti.test_table tt where tt.identifier is not null");
    rs = st.getResultSet();
    while (rs.next()) {
      int count = rs.getInt(1);
      assertEquals(2, count);
    }

    st.execute("select * from rti.test_table tt where tt.identifier is null");
    rs = st.getResultSet();
    int c = 0;
    while (rs.next()) {
      c++;
    }
    assertEquals(0, c);

  }

  public void helperBug52352(Properties props, String in, String out)
      throws Exception {
    String firstSchema = "TRADE";
    String firstTable = firstSchema + "." + "orders";
    {
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("create schema " + firstSchema);
      // Create table
      st.execute("create table " + firstTable
          + " (OVID varchar(10)) replicate");
    }

    {
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();

      st.execute("insert into " + firstTable + " values (" + in + ")");

    }

    {
      String query = "Select A.OVID from " + firstTable + " A";
      Connection conn = TestUtil.getConnection(props);
      PreparedStatement st = conn.prepareStatement(query);
      {
        ResultSet r = st.executeQuery();
        int count = 0;
        while (r.next()) {
          assertEquals(out, r.getString(1));
          count++;
        }
        assertEquals(1, count);
        r.close();
      }
    }

    {
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("drop table " + firstTable);
      st.execute("drop schema trade restrict");
    }
  }

  public void helper2Bug52352(Properties props, String in, String out)
      throws Exception {
    String firstSchema = "TRADE";
    String firstTable = firstSchema + "." + "orders";
    {
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("create schema " + firstSchema);
      // Create table
      st.execute("create table " + firstTable
          + " (OVID varchar(10)) replicate");
    }

    {
      String query = "insert into " + firstTable + " values (?)";
      Connection conn = TestUtil.getConnection(props);
      PreparedStatement st = conn.prepareStatement(query);
      st.setString(1, in);
      st.executeUpdate();
    }

    {
      String query = "Select A.OVID from " + firstTable + " A";
      Connection conn = TestUtil.getConnection(props);
      PreparedStatement st = conn.prepareStatement(query);
      {
        ResultSet r = st.executeQuery();
        int count = 0;
        while (r.next()) {
          assertEquals(out, r.getString(1));
          count++;
        }
        assertEquals(1, count);
        r.close();
      }
    }

    {
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("drop table " + firstTable);
      st.execute("drop schema trade restrict");
    }
  }

  public void testBug52352() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    try {
      helperBug52352(props, "'''test'''", "'test'");
      helperBug52352(props, "'test'", "test");
      helper2Bug52352(props, "''test''", "''test''");
      helper2Bug52352(props, "test", "test");
    } finally {
      TestUtil.shutDown();
    }
  }

  public void testSNAP_2202() {
    String[] regions = new String[] {
        "/" + GfxdConstants.IDENTITY_REGION_NAME,
        "/SCHEMA",
        "/_SCHEMA",
        "/__SCHEMA",
        "/__SCHEMA_",
        "/__SCH_EMA_",
        "/_SCH__EMA_",
        "/__SCH__EMA__",
        "/SCHEMA/TEST",
        "/_SCHEMA/TEST",
        "/__SCHEMA/_TEST",
        "/__SCHE_MA/TEST",
        "/__SCHEMA/_TE__ST",
        // the pattern "_/_" is unsupported
        // "/__SC__HEMA_/_TE_ST__"
        "/__SCHEMA/__TE__ST__"
    };
    int[] bucketIds = new int[] { 0, 1, 23, 101, 1001 };
    for (String region : regions) {
      for (int bucketId : bucketIds) {
        // below is same as ProxyBucketRegion.fullPath initialization
        String fullPath = Region.SEPARATOR +
            PartitionedRegionHelper.PR_ROOT_REGION_NAME + Region.SEPARATOR +
            PartitionedRegionHelper.getBucketName(region, bucketId);
        String bucketName = PartitionedRegionHelper.getBucketName(fullPath);
        assertEquals(region, PartitionedRegionHelper.getPRPath(bucketName));
        assertEquals(bucketId, PartitionedRegionHelper.getBucketId(bucketName));
      }
    }
  }
}
