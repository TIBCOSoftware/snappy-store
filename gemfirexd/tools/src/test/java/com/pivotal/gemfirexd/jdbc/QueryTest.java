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
package com.pivotal.gemfirexd.jdbc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.catalog.SystemProcedures;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CursorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable;
import com.pivotal.gemfirexd.internal.impl.sql.compile.GroupByNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ProjectRestrictNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ResultSetNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ScrollInsensitiveResultSetNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import com.pivotal.gemfirexd.stats.StatementPlanDUnit;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;

public class QueryTest extends JdbcTestBase {

  public QueryTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    // these tests generate lots of logs, so reducing them
    return "config";
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(QueryTest.class));
  }

  public void tearDown() throws Exception {
    super.tearDown();
    SystemProcedures.TEST_FAILURE_MODE = false;
  }
  
  public void test48488() throws Exception {

    Properties props = new Properties();
    props.setProperty("mcast-port", Integer.toString(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    
    setupConnection(props);
    TestUtil.assertTimerLibraryLoaded();
    final int netPort = startNetserverAndReturnPort();

    // use this VM as network client and also peer client
    final Connection netConn = TestUtil.getNetConnection(netPort, null, null);
    final Statement stmt = netConn.createStatement();
    
    stmt.execute("CREATE TABLE C_BO_PRTY" +
        "(" +
        "  ROWID_OBJECT                CHAR(14)     NOT NULL," +
        "  CREATE_DATE                 DATE         DEFAULT CURRENT_DATE," +
        "  LAST_UPDATE_DATE            DATE," +
        "  HUB_STATE_IND               INTEGER      DEFAULT 1 NOT NULL," +
        "  PRTY_CUST_ROLE_FL           CHAR(1)      NOT NULL," +
        "  PRTY_PERS_ROLE_FL           CHAR(1)      NOT NULL," +
        "  PRTY_FST_NM                 VARCHAR(40)," +
        "  PRTY_MID_NM                 VARCHAR(40)," +
        "  PRTY_LAST_NM                VARCHAR(40)," +
        "  PRTY_FULL_NM                VARCHAR(120)," +
        "  PRTY_CUST_INFRML_NM         VARCHAR(40)," +
        "  PRTY_PERS_PREF_NM           VARCHAR(40)," +
        "  PRTY_TITL_SFX_CD            VARCHAR(40)," +
        "  PRTY_TKN_SSN_NUM            VARCHAR(32)," +
        "  PRTY_BIRTH_DT               DATE," +
        "  PRTY_GNDR_CD                VARCHAR(3)," +
        "  PRTY_GNDR_DSC               VARCHAR(240)," +
        "  PRTY_TRUVUE_PIN_ID          VARCHAR(17)," +
        "  PRTY_ORIG_FST_NM            VARCHAR(40)," +
        "  PRTY_ORIG_MID_NM            VARCHAR(40)," +
        "  PRTY_ORIG_LST_NM            VARCHAR(40)," +
        "  PRTY_ORIG_TITL_SFX_CD       VARCHAR(40)," +
        "  PRTY_NA_SKEY                BIGINT," +
        "  NAME_PFX_CD_FK              VARCHAR(32)," +
        "  NM_UPDT_RSN_ID              CHAR(14)," +
        "  NM_SFX_CD_FK                VARCHAR(32)," +
        "  PRTY_CNTCT_METH_TYP_CD      VARCHAR(32)," +
        "  PRTY_HIDE_SRC_TXT           VARCHAR(240)," +
        "  PRTY_RLNSHP_OKTOEMAIL_FL    CHAR(1)" +
        ") REDUNDANCY 0 PERSISTENT PARTITION BY COLUMN (ROWID_OBJECT);" );
    
    stmt.execute("CREATE TABLE C_BO_POSTAL_ADDR" +
        "(" +
        "  ROWID_OBJECT           CHAR(14)      NOT NULL," +
        "  CREATE_DATE            DATE          DEFAULT CURRENT_DATE," +
        "  LAST_UPDATE_DATE       DATE," +
        "  HUB_STATE_IND          INTEGER       DEFAULT 1 NOT NULL," +
        "  PSTL_LN1_TXT           VARCHAR(100)," +
        "  PSTL_LN2_TXT           VARCHAR(100)," +
        "  PSTL_LN3_TXT           VARCHAR(100)," +
        "  PSTL_LN4_TXT           VARCHAR(100)," +
        "  PSTL_CITY_NM           VARCHAR(40)," +
        "  PSTL_CD                VARCHAR(32)," +
        "  PSTL_ORIG_LN1_TXT      VARCHAR(100)," +
        "  PSTL_ORIG_LN2_TXT      VARCHAR(100)," +
        "  PSTL_ORIG_LN3_TXT      VARCHAR(100)," +
        "  PSTL_ORIG_LN4_TXT      VARCHAR(100)," +
        "  PSTL_ORIG_CITY_NM      VARCHAR(36)," +
        "  PSTL_ORIGIN_REGN_NM    VARCHAR(40)," +
        "  PSTL_ORIG_CD           VARCHAR(32)," +
        "  PRTY_ID                CHAR(14)      NOT NULL," +
        "  CUST_ADDR_DNU_RSN_ID   CHAR(14)," +
        "  ST_ID                  CHAR(14)," +
        "  CNTRY_ID               CHAR(14)," +
        "  REGN_ID                CHAR(14)," +
        "  ADDR_TYP_ID            CHAR(14)," +
        "  ADDR_RSDNCY_TYP_ID     CHAR(14)" +
        ") REDUNDANCY 0 PERSISTENT PARTITION BY COLUMN (PRTY_ID) COLOCATE WITH (C_BO_PRTY);");

    stmt.execute("CREATE VIEW FINDCUST as " +
        "SELECT  B.HUB_STATE_IND," +
        "            C.PSTL_LN1_TXT," +
        "            C.PSTL_LN2_TXT," +
        "            C.PSTL_LN3_TXT," +
        "            C.PSTL_LN4_TXT," +
        "            C.PSTL_CITY_NM," +
        "            C.PSTL_CD," +
        "            C.ST_ID," +
        "            B.ROWID_OBJECT AS CUST_ID," +
        "            B.PRTY_FST_NM," +
        "            B.PRTY_LAST_NM," +
        "            B.PRTY_CUST_INFRML_NM," +
        "            B.PRTY_TITL_SFX_CD," +
        "            B.NAME_PFX_CD_FK," +
        "            B.NM_SFX_CD_FK," +
        "            B.PRTY_HIDE_SRC_TXT," +
        "            B.PRTY_CNTCT_METH_TYP_CD," +
        "            C.PSTL_ORIG_LN1_TXT," +
        "            C.PSTL_ORIG_LN2_TXT," +
        "            C.PSTL_ORIG_LN3_TXT," +
        "            C.PSTL_ORIG_LN4_TXT," +
        "            B.PRTY_MID_NM," +
        "            C.PSTL_ORIG_CITY_NM," +
        "            C.PSTL_ORIG_CD," +
        "            B.PRTY_ORIG_FST_NM," +
        "            B.PRTY_ORIG_LST_NM," +
        "            C.CUST_ADDR_DNU_RSN_ID," +
        "            C.REGN_ID," +
        "            C.LAST_UPDATE_DATE," +
        "            C.CNTRY_ID," +
        "            B.PRTY_ORIG_MID_NM" +
        "       FROM         C_BO_POSTAL_ADDR C" +
        "       INNER JOIN   C_BO_PRTY B ON B.ROWID_OBJECT = C.PRTY_ID" );
    
    ResultSet r = stmt.executeQuery("select count(*) from findcust");
    assertTrue(r.next());
    assertEquals(0, r.getInt(1));
    assertFalse(r.next());

    PreparedStatement boPrty = netConn.prepareStatement("insert into C_BO_PRTY (ROWID_OBJECT, PRTY_CUST_ROLE_FL, PRTY_PERS_ROLE_FL) values (?, ? , ?)");
    
    PreparedStatement postAddrs = netConn.prepareStatement("insert into C_BO_POSTAL_ADDR (ROWID_OBJECT, PRTY_ID) values (?, ?)");
    
    for ( int i = 1; i <= 100; i++) {
      boPrty.setString(1, "   Str " + i);
      postAddrs.setString(2, "   Str " + i);

      boPrty.setString(2, "Y");
      boPrty.setString(3, "N");
      postAddrs.setString(1, "ROWIDOBJ___" + i);
      boPrty.addBatch();
      postAddrs.addBatch();
    }
    
    boPrty.executeBatch();
    boPrty.clearBatch();
    postAddrs.executeBatch();
    postAddrs.clearBatch();

    stmt.execute("call syscs_util.set_explain_connection(1)");
    
    PreparedStatement ps = netConn.prepareStatement("select count(*) from findcust where CUST_ID like ? ");
    ps.setString(1, "%");
    r = ps.executeQuery();
    
    //r = stmt.executeQuery("select count(*) from findcust ");
    assertTrue(r.next());
    assertEquals(100, r.getInt(1));
    assertFalse(r.next());
    r.close();
    
    stmt.execute("call syscs_util.set_explain_connection(0)");
    
    ResultSet qp = stmt.executeQuery("select stmt_id, stmt_text from sys.statementplans");
    assertTrue(qp.next());
    do {
      String uuid = qp.getString(1);
      System.out.println("Extracting plan for " + uuid);
      ResultSet plan = netConn.createStatement().executeQuery("explain " + uuid);
      assertTrue(plan.next());
      String thePlan = plan.getString(1);
      getLogger().info("plan : " + thePlan);
      StringTokenizer t = new StringTokenizer(thePlan, "\r\n");
      assertEquals(23, t.countTokens());
      assertFalse(plan.next());
    } while(qp.next());
    
  }
  
  public void test48488_2() throws SQLException {
    
    // Bug #51844
    if (isTransactional) {
      return;
    }
    
    Connection conn = getConnection();
    TestUtil.assertTimerLibraryLoaded();
    Statement stmt = conn.createStatement();
    ResultSet r = null;
    final Properties props = new Properties();
    props.setProperty("mcast-port", Integer.toString(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    
    {
      stmt.execute("create table t1 (i int, s smallint, d double precision, r real, c10 char(10),c30 char(30), vc10 varchar(10), vc30 varchar(30))");
      stmt.execute("create table t2 (i int, s smallint, d double precision, r real, c10 char(10),     c30 char(30), vc10 varchar(10), vc30 varchar(30))");
      stmt.execute("insert into t1 values (null, null, null, null, null, null, null, null)");
      stmt.execute("insert into t1 values (1, 1, 1e1, 1e1, '11111', '11111     11', '11111','11111      11')");
      stmt.execute("insert into t1 values (2, 2, 2e1, 2e1, '22222', '22222     22', '22222',  '22222      22')");
      stmt.execute("insert into t2 values (null, null, null, null, null, null, null, null)");
      stmt.execute("insert into t2 values (3, 3, 3e1, 3e1, '33333', '33333     33', '33333','33333      33')");
      stmt.execute("insert into t2 values (4, 4, 4e1, 4e1, '44444', '44444     44', '44444',  '44444      44')");

      THashSet values = new THashSet();
      values.add("13");
      values.add("14");
      values.add("10");
      values.add("23");
      values.add("24");
      values.add("20");
      values.add("31");
      values.add("32");
      values.add("30");
      values.add("41");
      values.add("42");
      values.add("40");
      values.add("910");
      values.add("01");
      values.add("02");
      values.add("03");
      values.add("04");
      values.add("00");

      r = stmt
          .executeQuery("values (9, 10) union select a.i, b.i from t1 a, t2 b union select b.i, a.i from t1 a, t2 b");
      while (r.next()) {
        // System.out.println("values.add(\""+r.getInt(1) + "" + r.getInt(2) +
        // "\");");
        assertTrue(values.remove(r.getInt(1) + "" + r.getInt(2)));
      }

      assertFalse(r.next());
      assertTrue(values.isEmpty());
      r.close();
      stmt.execute("drop table t1");
      stmt.execute("drop table t2");
      stmt.close();
      conn.close();
      values.clear();
    }

    {
      conn = getConnection(props);
      stmt = conn.createStatement();

      stmt.execute("create table b1 (c0 int)");
      stmt.execute("create table xx (c1 int, c2 int)");
      stmt.execute("create table b2 (c3 int, c4 int)");
      stmt.execute("insert into b1 values 1");
      stmt.execute("insert into xx values (0, 1)");
      stmt.execute("insert into b2 values (0, 2)");
      r = stmt
          .executeQuery("select b1.* from b1 JOIN (select * from xx) VW(c1,c2) on (b1.c0 = vw.c2) JOIN b2 on (vw.c1 = b2.c3)");

      assertTrue(r.next());
      assertEquals(1, r.getInt(1));
      assertFalse(r.next());
      r.close();
      stmt.execute("drop table b2");
      stmt.execute("drop table xx");

      stmt.execute("create table b (c1 int, c2 int, c3 char(1), c4 int, c5 int, c6 int) replicate");
      stmt.execute("create table b2 (c1 int, c2 int, c3 char(1), c4 int, c5 int, c6 int) replicate");
      stmt.execute("create table b4 (c7 int, c4 int, c6 int) replicate");
      stmt.execute("create table b3 (c8 int, c9 int, c5 int, c6 int) replicate ");
      stmt.execute("create view bvw (c5, c1 ,c2 ,c3 ,c4) as select c5, c1 ,c2 ,c3 ,c4 from b2 union select c5, c1 ,c2 ,c3 ,c4 from b");
      stmt.execute("insert into b4 (c7,c4,c6) values (4, 42, 31)");
      stmt.execute("insert into b2 (c5,c1,c3,c4,c6) values (3,4, 'F',43,23)");
      stmt.execute("insert into b3 (c5,c8,c9,c6) values (2,3,19,28)");
      
      r = stmt
          .executeQuery("select b3.* from b3 join bvw on (b3.c8 = bvw.c5) join b4 on (bvw.c1 = b4.c7) where b4.c4 = 42");

      assertTrue(r.next());
      assertEquals("319228", ""+r.getInt(1)+r.getInt(2)+r.getInt(3)+r.getInt(4));
      assertFalse(r.next());
      r.close();
      stmt.execute("drop table b1");
      stmt.execute("drop view bvw");
      stmt.execute("drop table b2");
      stmt.execute("drop table b");
      stmt.close();
      conn.close();
    }
    
    {
      conn = getConnection();
      stmt = conn.createStatement();
      
      stmt.execute("create table b1 (c0 int) REPLICATE");
      stmt.execute("create table xx (c1 int, c2 int)");
      stmt.execute("create table b2 (c3 int, c4 int) REPLICATE");
      stmt.execute("insert into b1 values 1");
      stmt.execute("insert into xx values (0, 1)");
      stmt.execute("insert into b2 values (0, 2)");
      
      r = stmt.executeQuery("select b1.* from b1 JOIN (select * from xx) VW(ccx1,ccx2) on (b1.c0 = vw.ccx2) JOIN b2 on (vw.ccx1 = b2.c3)");
      assertTrue(r.next());
      assertEquals(1, r.getInt(1));
      assertFalse(r.next());
      r.close();
      stmt.close();
      conn.close();
    }
    
    {
      conn = getConnection(props);
      stmt = conn.createStatement();

      stmt.execute("CREATE TABLE APP.T1 (I INTEGER, J INTEGER)");
      stmt.execute("insert into t1 values (1, 2), (2, 4), (3, 6), (4, 8), (5, 10)");
      stmt.execute("CREATE TABLE APP.T2 (I INTEGER, J INTEGER)");
      stmt.execute("insert into t2 values (1, 2), (2, -4), (3, 6), (4, -8), (5, 10)");
      stmt.execute("CREATE TABLE APP.T3 (A INTEGER, B INTEGER)");
      stmt.execute("insert into T3 values (1,1), (2,2), (3,3), (4,4), (6, 24),(7, 28), (8, 32), (9, 36), (10, 40)");
      stmt.execute("insert into t3 (a) values 11, 12, 13, 14, 15, 16, 17, 18, 19, 20");
      stmt.execute("update t3 set b = 2 * a where a > 10");
      stmt.execute("CREATE TABLE APP.T4 (A INTEGER, B INTEGER)");
      stmt.execute("insert into t4 values (3, 12), (4, 16)");
      stmt.execute("insert into t4 (a) values 11, 12, 13, 14, 15, 16, 17, 18, 19, 20");
      stmt.execute("update t4 set b = 2 * a where a > 10");

      
      stmt.execute("insert into t3 (a) values 21, 22, 23, 24, 25, 26, 27, 28, 29, 30");
      stmt.execute("insert into t3 (a) values 31, 32, 33, 34, 35, 36, 37, 38, 39, 40");
      stmt.execute("insert into t3 (a) values 41, 42, 43, 44, 45, 46, 47, 48, 49, 50");
      stmt.execute("insert into t3 (a) values 51, 52, 53, 54, 55, 56, 57, 58, 59, 60");
      stmt.execute("insert into t3 (a) values 61, 62, 63, 64, 65, 66, 67, 68, 69, 70");
      stmt.execute("insert into t3 (a) values 71, 72, 73, 74, 75, 76, 77, 78, 79, 80");
      stmt.execute("insert into t3 (a) values 81, 82, 83, 84, 85, 86, 87, 88, 89, 90");
      stmt.execute("insert into t3 (a) values 91, 92, 93, 94, 95, 96, 97, 98, 99, 100");
      stmt.execute("update t3 set b = 2 * a where a > 20");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t3 (a, b) (select a,b from t4 where a > 20)");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t3 (a, b) (select a,b from t4 where a > 20)");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t3 (a, b) (select a,b from t4 where a > 20)");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t3 (a, b) (select a,b from t4 where a > 20)");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t3 (a, b) (select a,b from t4 where a > 20)");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t3 (a, b) (select a,b from t4 where a > 20)");
      stmt.execute("insert into t4 (a, b) (select a,b from t3 where a > 20)");
      stmt.execute("insert into t3 (a, b) (select a,b from t4 where a > 60)");

      
      stmt.execute("create view V1 as select i, j from T1 union select i,j from T2");
      stmt.execute("create view V2 as select a,b from T3 union select a,b from T4");

      
      r = stmt.executeQuery("select count(*) from V1, V2 where V1.j > 0");
      assertTrue(r.next());
      assertEquals(505, r.getInt(1));
      
      r.close();
      stmt.close();
      conn.close();
    }
    
  }
  
  public void test45937() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("create table twenty (x int)");

    stmt.execute("create table hundred (x int generated always as identity, dc int)");

    stmt.execute("create table t1 (id int generated always as identity, two int, twenty int, hundred varchar(3))");

    stmt.execute("insert into t1 (hundred, twenty, two) select cast(char(hundred.x) as varchar(3)), twenty.x, twenty.x from hundred, twenty");
  }
  
  public void testSelectFromPrimaryKeyAndIndex() throws SQLException {
    int numCust = 200;
    int numOrder = 100;
    int interval = 50;
    int numQueries = 1;
    int expectedRows = interval * numQueries;
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);
    createIndex(conn);
    loadSampleData(conn, numCust, numOrder);
    assertEquals("Row count did not match", expectedRows, selectFromTable(conn,
        interval, numQueries));
    dropTable(conn);
  }

  public void testSelectFrom() throws Exception {
    int numCust = 200;
    int numOrder = 100;
    int interval = 50;
    int numQueries = 1;
    int expectedRows = interval * numQueries;
    setupConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    createTable(conn);
    loadSampleData(conn, numCust, numOrder);

    /*
    int sz =  numCust * numOrder;
    
    int countFromRegion1 = selectFromRegion(conn, interval, sz);
     */

    int countFromTable = selectFromTable(conn, interval, numQueries);

    /*
    // if we get the wrong number of rows, then run the query directly
    // against the region we can determine whether the problem has to do
    // with mutating rows in place.
    
    if (expectedRows != countFromTable) {
      System.out.println("Results from JDBC query; expected:<" + expectedRows +
                         ">, actual:<" + countFromTable + ">.");
      System.out.println("Rerunning query directly on region.");
      int countFromRegion2 = selectFromRegion(conn, interval, sz);
      
      fail("Got <" + countFromTable + "> rows from table, and <" +
           countFromRegion1 + "> rows beforehand from region, and <" +
           countFromRegion2 + "> after, expected <" + expectedRows +
           ">.");
    }
     */

    assertEquals("Row count from table did not match", expectedRows,
        countFromTable);
    dropTable(conn);
  }

  public void testSelectFromPrimaryKey() throws SQLException {
    int numCust = 200;
    int numOrder = 100;
    int interval = 50;
    int numQueries = 1;
    int expectedRows = interval * numQueries;
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);
    loadSampleData(conn, numCust, numOrder);
    assertEquals("Row count did not match", expectedRows, selectFromTable(conn,
        interval, numQueries));
    dropTable(conn);
  }

  public void testSelectFromIndex() throws SQLException {
    int numCust = 200;
    int numOrder = 100;
    int interval = 50;
    int numQueries = 1;
    int expectedRows = interval * numQueries;
    Connection conn = getConnection();
    createTable(conn);
    createIndex(conn);
    loadSampleData(conn, numCust, numOrder);
    int rowsRead = selectFromTable(conn, interval, numQueries);
    assertEquals("Row count did not match", expectedRows, rowsRead);
    dropTable(conn);
    // !!!:ezoerner:20080618
    // closing the connection here throws an error:
    // java.sql.SQLException: Cannot close a connection while a transaction is
    // still active.
    // Not sure if this is expected behavior or not.
    // for now, commenting out the close.
    /* conn.close();
     */
  }

  public void test42809() throws SQLException {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    final ResultSet rs = conn.getMetaData().getTables(null, null,
        "course".toUpperCase(), new String[] { "ROW TABLE" });
    final boolean found = rs.next();
    rs.close();

    if (found)
      st.execute("drop table course ");
    st.execute("create table course ("
        + "course_id int, course_name varchar(2048), "
        + " primary key(course_id)" + ") ");

    String sql = "insert into course values( ";
    StringBuilder ins = new StringBuilder(sql);
    for (int i = 0; i < 1000; i++) {
      ins.append(i).append(", '");
      ins.append(TestUtil.numstr(i)).append("' )");
      if (i % 100 == 0) {
        st.execute(ins.toString());
        ins = new StringBuilder(sql);
      }
      else {
        ins.append(", ( ");
      }
    }
  }

  /**
   * picked from GroupByExpressionTest derby suite.
   * 
   * To run that individually. java -classpath
   * $CLASSPATH:/soubhikc1/builds/v2gemfirexddev/build-artifacts/
   * linux/gemfirexd/jars/sane/derbyTesting.jar -Dderby.tests.trace=true
   * -Dderby.tests.debug=true junit.textui.TestRunner
   * org.apache.derbyTesting.functionTests.tests.lang.GroupByExpressionTest
   * 
   * related to #42854 (see next test).
   * 
   * @throws SQLException
   */
  public void testLocateInGroupBy() throws SQLException {
    Connection conn = TestUtil.getConnection();

    Statement st = conn.createStatement();
    st.execute("create table t2 (c1 varchar(10))");
    st.execute("insert into t2 values '123 ', 'abc ', '123', 'abc'");

    PreparedStatement ps = conn
        .prepareStatement("select locate(c1, 'abc') from t2 group by locate(c1, 'abc')");
    ResultSet r = ps.executeQuery();
    Set<Integer> results = new HashSet<Integer>();
    results.add(0);
    results.add(1);
    assertTrue(r.next());
    assertTrue(results.remove(r.getObject(1)));
    assertTrue(r.next());
    assertTrue(results.remove(r.getObject(1)));
    assertFalse(r.next());
    r.close();

    ps = conn
        .prepareStatement("select locate(c1, 'abc', 2) from t2 group by locate(c1, 'abc',2)");
    r = ps.executeQuery();
    assertTrue(r.next());
    assertEquals(r.getObject(1), 0);
    assertFalse(r.next());
    r.close();

  }

  public void test42854() throws SQLException {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    final ResultSet rs = conn.getMetaData().getTables((String)null, null,
        "course".toUpperCase(), new String[] { "TABLE" });
    final boolean found = rs.next();
    rs.close();

    if (found)
      st.execute("drop table course ");
    st
        .execute("create table course ("
            + "course_id int, course_name varchar(2048), course_type varchar(1024), "
            + " primary key(course_id)" + ") partition by column(course_id) ");

    String sql = "insert into course values( ";
    StringBuilder ins = new StringBuilder(sql);

    for (int i = 0; i < 1000; i++) {
      ins.append(i).append(", '");
      ins.append(TestUtil.numstr(i % 10)).append("', '");
      ins.append(TestUtil.numstr(i % 10)).append("' )");
      st.execute(ins.toString());
      ins = new StringBuilder(sql);
    }

    conn.createStatement().execute(
        "create function r() returns double external name "
            + "'java.lang.Math.random' language java parameter style java");

    try {
      conn.createStatement().executeQuery(
          "select r(), count(*) from course group by r()");
      fail("Should have failed with exception");
    } catch (SQLException sqe) {
      if (!sqe.getSQLState().equals("42Y30")) {
        throw sqe;
      }
    }

    ResultSet r = conn.createStatement().executeQuery(
        "select count(1) from course group by pi()");
    assertTrue(r.next());
    assertEquals(1000, r.getInt(1));
    assertFalse(r.next());

    r = conn.createStatement().executeQuery(
        "select count(1) from course group by dsid()");
    assertTrue(r.next());
    assertEquals(1000, r.getInt(1));
    assertFalse(r.next());

    String[] query = new String[] {
        "select count(1) from course group by ltrim(course_name)",
        "select count(1) from course group by substr(course_name, 2, 3)", };

    for (String q : query) {
      assertResult(q, conn, true);
      assertResult(q, conn, false);
    }

  }


  public void test42682() throws SQLException {
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);

    loadSampleData(conn, 2, 10);

    try {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private static final long serialVersionUID = 1L;

            @Override
            public void afterOptimizedParsedTree(String query,
                StatementNode qt, LanguageConnectionContext lcc) {

              assertTrue(qt instanceof CursorNode);
              assertTrue(((CursorNode)qt).getResultSetNode() instanceof ScrollInsensitiveResultSetNode);
              ResultSetNode rn = ((ScrollInsensitiveResultSetNode)((CursorNode)qt)
                  .getResultSetNode()).getChildResult();
              assertTrue(rn instanceof ProjectRestrictNode);
              if(!lcc.isConnectionForRemote()) {
                assertTrue(((ProjectRestrictNode)rn).getChildResult() instanceof GroupByNode);
              }
              else {
                assertTrue(((ProjectRestrictNode)rn).getChildResult() instanceof FromBaseTable);
                FromBaseTable fbt = (FromBaseTable)((ProjectRestrictNode)rn)
                    .getChildResult();
                assertTrue(fbt.isSpecialRegionSize());
              }
            }

            @Override
            public void beforeQueryExecution(EmbedStatement stmt,
                Activation activation) throws SQLException {

            }
          });

      ResultSet r = conn.createStatement().executeQuery(
          "select count(*) from orders");
      r.next();
      assertEquals(20, r.getInt(1));

    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverHolder());
      dropTable(conn);
    }
  }

  public void test42917() throws SQLException {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("CREATE TABLE D3764A (A1 INTEGER, A2 VARCHAR(10))");
    st.execute("INSERT INTO D3764A (A1) VALUES (1), (2), (3), (4), "
        + "(5), (6), (5), (3), (1)");

    ResultSet r = st.executeQuery("SELECT A1 FROM D3764A "
        + "UNION SELECT COUNT(A1) FROM D3764A");
    while (r.next()) {
      System.out.println(r.getInt(1));
    }
    r.close();

    // check for coalesce and nvl
    r = st.executeQuery("select distinct coalesce(a2, char(a1), 'X'), "
        + "coalesce(a2, 'X', a2), nvl(a2, 'X'), nvl(a2, char(a1)) "
        + "from D3764A");
    int a1 = 1;
    while (r.next()) {
      assertEquals(a1, r.getInt(1));
      assertEquals("X", r.getString(2));
      assertEquals("X", r.getString(3));
      assertEquals(a1, r.getInt(4));
      a1++;
    }
    assertEquals(7, a1);

    // checks for decode
    r = st.executeQuery("select a1, decode(a1, " +
    		"1, 'one'," +
    		"2, 'two'," +
    		"3, 'three'," +
    		"   'other') from D3764A where a1 < 6 order by a1");
    final int[] a1s = new int[] { 1, 1, 2, 3, 3, 4, 5, 5 };
    for (int a1v : a1s) {
      assertTrue(r.next());
      String res;
      switch (a1v) {
        case 1: res = "one"; break;
        case 2: res = "two"; break;
        case 3: res = "three"; break;
        default: res = "other"; break;
      }
      assertEquals(a1v, r.getInt(1));
      assertEquals(res, r.getString(2));
    }
    assertFalse(r.next());

    r = st.executeQuery("select a1, decode(a1, " +
        "1, 'one'," +
        "3, 'three'," +
        "   'other') from D3764A where a1 < 6 order by a1");
    for (int a1v : a1s) {
      assertTrue(r.next());
      String res;
      switch (a1v) {
        case 1: res = "one"; break;
        case 3: res = "three"; break;
        default: res = "other"; break;
      }
      assertEquals(a1v, r.getInt(1));
      assertEquals(res, r.getString(2));
    }
    assertFalse(r.next());

    r = st.executeQuery("select a1, decode(a1, " +
        "3, 'three'," +
        "   'other') from D3764A where a1 < 6 order by a1");
    for (int a1v : a1s) {
      assertTrue(r.next());
      String res;
      switch (a1v) {
        case 3: res = "three"; break;
        default: res = "other"; break;
      }
      assertEquals(a1v, r.getInt(1));
      assertEquals(res, r.getString(2));
    }
    assertFalse(r.next());

    r = st.executeQuery("select a1, decode(a1, " +
        "1, 'one'," +
        "2, 'two'," +
        "3, 'three') from D3764A where a1 < 6 order by a1");
    for (int a1v : a1s) {
      assertTrue(r.next());
      String res;
      switch (a1v) {
        case 1: res = "one"; break;
        case 2: res = "two"; break;
        case 3: res = "three"; break;
        default: res = null; break;
      }
      assertEquals(a1v, r.getInt(1));
      assertEquals(res, r.getString(2));
    }
    assertFalse(r.next());

    r = st.executeQuery("select a1, decode(a1, " +
        "1, 'one') from D3764A where a1 < 6 order by a1");
    for (int a1v : a1s) {
      assertTrue(r.next());
      String res;
      switch (a1v) {
        case 1: res = "one"; break;
        default: res = null; break;
      }
      assertEquals(a1v, r.getInt(1));
      assertEquals(res, r.getString(2));
    }
    assertFalse(r.next());

    try {
      r = st.executeQuery("select a1, decode(a1, 'other') from D3764A "
          + "where a1 < 6 order by a1");
      fail("expected a syntax error");
    } catch (SQLException sqle) {
      if (!"42X01".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    st.execute("drop table D3764A");

    st.executeUpdate("create table t (i int, s smallint, l bigint, c "
        + "char(10), v varchar(50), lvc long varchar, d double "
        + "precision, r real, dt date, t time, ts timestamp, b "
        + "char(2) for bit data, bv varchar(2) for bit data, "
        + "lbv long varchar for bit data)");

    st.executeUpdate("insert into t (i) values (null)");
    st.executeUpdate("insert into t (i) values (null)");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (1, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 200, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 2000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'goodbye', "
        + "'everyone is here', 'adios, muchachos', 200.0e0, "
        + "200.0e0, date('1992-01-01'), time('12:30:30'), "
        + "timestamp('1992-01-01 12:30:30'), X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'noone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "100.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 100.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-09-09'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:55:55'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:55:55'), "
        + "X'12af', X'0f0f', X'ABCD')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'ffff', X'0f0f', X'1234')");

    st.executeUpdate(" insert into t values (0, 100, 1000000, 'hello', "
        + "'everyone is here', 'what the heck do we care?', "
        + "200.0e0, 200.0e0, date('1992-01-01'), "
        + "time('12:30:30'), timestamp('1992-01-01 12:30:30'), "
        + "X'12af', X'ffff', X'ABCD')");

    r = st.executeQuery(" select ts from t group by ts order by ts");
    String[] expRS = new String[] { "1992-01-01 12:30:30.0",
        "1992-01-01 12:55:55.0", null };

    int index = 0;
    while (r.next()) {
      if (expRS[index] == null) {
        assertEquals(null, r.getTimestamp(1));
      }
      else {
        assertEquals(expRS[index], r.getTimestamp(1).toString());
      }
      index++;
    }

    r.close();
    st.executeUpdate("drop table t");
  }

  public void test42993() throws Exception {
    // Bug #51844
    if (isTransactional) {
      return;
    }

    Connection conn = getConnection();
    conn.createStatement().execute(
        "declare global temporary table session.ztemp "
            + "( orderID varchar( 50 ) ) not logged");

    Statement s = conn.createStatement();
    s.execute("insert into session.ztemp values ('1'), ('2'), ('3')");
    ResultSet rs = s
        .executeQuery("select * from session.ztemp order by orderID ");
    assertTrue(rs.next());
    assertEquals(String.valueOf('1'), rs.getString(1));
    assertTrue(rs.next());
    assertEquals(String.valueOf('2'), rs.getString(1));
    assertTrue(rs.next());
    assertEquals(String.valueOf('3'), rs.getString(1));
    assertFalse(rs.next());
    
    s.execute("set schema session");
    s.execute("update ztemp set orderID = 'hi' ");
    s.execute("delete from ztemp");
  }
  
  public void testMultiConnectionTempTable() throws Exception {
    // Bug #51844
    if (isTransactional) {
      return;
    }

    
    Connection conn2 = getConnection();
    {
      Connection conn1 = getConnection();
      conn1.createStatement().execute(
          "declare global temporary table temp_check "
              + "( orderID varchar( 50 ) ) not logged");
      Statement s = conn1.createStatement();
      s.execute("insert into session.temp_check values ('1'), ('2'), ('3')");

      try {
        conn2.createStatement().execute(
            "declare global temporary table temp_check "
                + "( orderID varchar( 50 ) ) not logged");
        fail("expected duplicate name creation... " +
        		"if we started to support duplicate name across connections, " +
        		"its okay to fix this");
      } catch (SQLException sqle) {
        if (!"X0Y32".equals(sqle.getSQLState())) {
          throw sqle;
        }
        // okay we dictate unique table name.
      }
      conn1.close();
    }
    
    {
      // now that previous connection vanished, we should be able to create from
      // another conn.
      conn2.createStatement().execute(
          "declare global temporary table temp_check "
              + "( orderID varchar( 50 ) ) not logged");
    }
  }
  
  public void testTemporaryTableAndTransaction() throws Exception {
    // Bug #51844
    if (isTransactional) {
      return;
    }


    // temporary table schema rollback.
    {
      Connection conn = getConnection();

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.createStatement().execute(
          "declare global temporary table temp_check "
              + "( orderID varchar( 50 ) ) not logged");
      Statement s = conn.createStatement();
      s.execute("insert into session.temp_check values ('1'), ('2'), ('3')");

      ResultSet r = s.executeQuery("select * from session.temp_check");
      assertTrue(r.next());
      assertTrue(r.next());
      assertTrue(r.next());
      assertFalse(r.next());

      TableDescriptor t = ((EmbedConnection)conn)
          .getLanguageConnectionContext()
          .getTableDescriptorForDeclaredGlobalTempTable(
              "temp_check".toUpperCase());
      assertTrue(t != null
          && t.getDescriptorName().equalsIgnoreCase("temp_check"));

      conn.rollback();

      TableDescriptor tn = ((EmbedConnection)conn)
          .getLanguageConnectionContext()
          .getTableDescriptorForDeclaredGlobalTempTable(
              "temp_check".toUpperCase());
      assertTrue(tn == null);
      
      conn.close();
    }
    
    // temporary table data rolled back
    {
      Connection conn = getConnection();

      conn.createStatement().execute(
          "declare global temporary table temp_check "
              + "( orderID varchar( 50 ) ) not logged");
      
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Statement s = conn.createStatement();
      s.execute("insert into session.temp_check values ('1'), ('2'), ('3')");

      ResultSet r = s.executeQuery("select * from session.temp_check");
      assertTrue(r.next());
      assertTrue(r.next());
      assertTrue(r.next());
      assertFalse(r.next());

      TableDescriptor t = ((EmbedConnection)conn)
          .getLanguageConnectionContext()
          .getTableDescriptorForDeclaredGlobalTempTable(
              "temp_check".toUpperCase());
      assertTrue(t != null
          && t.getDescriptorName().equalsIgnoreCase("temp_check"));

      conn.rollback();
      conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      
      ResultSet ch = conn.createStatement().executeQuery("select * from session.temp_check");
      assertFalse(ch.next());

      conn.close();
    }
    
    // temporary table schema resurrection.
    {
      Connection conn = getConnection();
      conn.createStatement().execute(
          "declare global temporary table temp_check "
              + "( orderID varchar( 50 ) ) not logged");

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Statement s = conn.createStatement();
      s.execute("insert into session.temp_check values ('1'), ('2'), ('3')");

      ResultSet r = s.executeQuery("select * from session.temp_check");
      assertTrue(r.next());
      assertTrue(r.next());
      assertTrue(r.next());
      assertFalse(r.next());

      conn.createStatement().execute("drop table session.temp_check");

      conn.rollback();
      conn.setTransactionIsolation(Connection.TRANSACTION_NONE);

      ResultSet rnone = s.executeQuery("select * from session.temp_check");
      assertFalse(rnone.next());
      
      conn.close();
    }
    
  }

  public void testTemporaryTableAndTransactionWithOperations() throws Exception {
    // Bug #51844
    if (isTransactional) {
      return;
    }


    // temporary table schema rollback.
    {
      Connection conn = getConnection();

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      // some operations in the transaction to ensure that temporary table
      // can be created and used in the middle of tx without committing
      Statement s = conn.createStatement();
      s.execute("create table txtable (id int primary key, addr varchar(20))");

      // some inserts in the table
      for (int id = 1; id <= 100; id++) {
        s.execute("insert into txtable values (" + id + ", 'addr" + id + "')");
      }

      s.execute("declare global temporary table temp_check "
          + "( orderID varchar( 50 ) ) not logged");
      s.execute("insert into session.temp_check values ('1'), ('2'), ('3')");

      // check that the inserts in normal table are visible
      ResultSet r = s.executeQuery("select count(*) from txtable");
      assertTrue(r.next());
      assertEquals(100, r.getInt(1));
      assertFalse(r.next());

      r = s.executeQuery("select * from session.temp_check");
      assertTrue(r.next());
      assertTrue(r.next());
      assertTrue(r.next());
      assertFalse(r.next());

      TableDescriptor td = ((EmbedConnection)conn)
          .getLanguageConnectionContext()
          .getTableDescriptorForDeclaredGlobalTempTable(
              "temp_check".toUpperCase());
      assertTrue(td != null
          && td.getDescriptorName().equalsIgnoreCase("temp_check"));

      conn.rollback();

      td = ((EmbedConnection)conn).getLanguageConnectionContext()
          .getTableDescriptorForDeclaredGlobalTempTable(
              "temp_check".toUpperCase());
      assertNull(td);

      // also check that the inserts in normal table were rolled back
      r = s.executeQuery("select count(*) from txtable");
      assertTrue(r.next());
      assertEquals(0, r.getInt(1));
      assertFalse(r.next());

      conn.commit();
      conn.close();
    }

    // temporary table data committed
    {
      Connection conn = getConnection();

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      // some operations in the transaction to ensure that temporary table
      // can be created and used in the middle of tx without committing
      Statement s = conn.createStatement();

      // some inserts in the table
      for (int id = 1; id <= 100; id++) {
        s.execute("insert into txtable values (" + id + ",'s" + id + "')");
      }

      s.execute("declare global temporary table temp_check "
          + "( orderID varchar( 50 ) ) not logged");
      s.execute("insert into session.temp_check values ('1'), ('2'), ('3')");

      // check that the inserts in normal table are visible
      ResultSet r = s.executeQuery("select count(*) from txtable");
      assertTrue(r.next());
      assertEquals(100, r.getInt(1));
      assertFalse(r.next());

      r = s.executeQuery("select * from session.temp_check");
      assertTrue(r.next());
      assertTrue(r.next());
      assertTrue(r.next());
      assertFalse(r.next());

      TableDescriptor td = ((EmbedConnection)conn)
          .getLanguageConnectionContext()
          .getTableDescriptorForDeclaredGlobalTempTable(
              "temp_check".toUpperCase());
      assertTrue(td != null
          && td.getDescriptorName().equalsIgnoreCase("temp_check"));

      conn.commit();

      td = ((EmbedConnection)conn).getLanguageConnectionContext()
          .getTableDescriptorForDeclaredGlobalTempTable(
              "temp_check".toUpperCase());
      assertTrue(td != null
          && td.getDescriptorName().equalsIgnoreCase("temp_check"));

      // check that the inserts in normal table were committed
      r = s.executeQuery("select count(*) from txtable");
      assertTrue(r.next());
      assertEquals(100, r.getInt(1));
      assertFalse(r.next());

      conn.commit();
      conn.close();
    }
  }

  public void testDateTime() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table datetime_family (id int"
        + " primary key, type_date date, type_time time,"
        + " type_datetime timestamp)");
    stmt.execute("insert into datetime_family values"
        + " (1, '2004-10-10', '10:10:10', '2004-10-10 10:10:10'),"
        + " (2, '2005-10-10', NULL, '2005-10-10 10:10:10')");

    // select date, time and timestamp fields

    // first TIMESTAMP
    PreparedStatement pstmt = conn.prepareStatement("select * from "
        + "datetime_family where type_datetime=?");
    pstmt.setTimestamp(1, Timestamp.valueOf("2005-10-10 10:10:10"));
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertFalse(rs.next());
    rs.close();

    // now check for DATE
    pstmt = conn.prepareStatement("select * from "
        + "datetime_family where type_date=?");
    pstmt.setTimestamp(1, Timestamp.valueOf("2005-10-10 10:10:10"));
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertFalse(rs.next());
    rs.close();
    // check with string parameter
    pstmt.setString(1, "2004-10-10 10:10:10");
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertFalse(rs.next());
    rs.close();

    // lastly for TIME
    pstmt = conn.prepareStatement("select * from "
        + "datetime_family where type_time=?");
    pstmt.setTimestamp(1, Timestamp.valueOf("2005-10-10 10:10:10"));
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertFalse(rs.next());
    rs.close();

    stmt.execute("drop table datetime_family");
    conn.close();
  }

  public void test43206() throws Exception {

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table pizza (i int) ");

    ResultSet rs = conn.createStatement().executeQuery(
        "select m.ID, m.hostdata from sys.systables t, sys.members m where "
            + "t.tableschemaname='APP' and t.tablename='PIZZA' "
            + "and m.hostdata = 'true'");

    assertTrue(rs.next());
    assertFalse(rs.next());

    rs = conn.createStatement().executeQuery(
        "select m.ID, m.hostdata from sys.systables t, sys.members m where "
            + "t.tableschemaname='APP' and t.tablename='PIZZA' "
            + "and m.hostdata = 1");

    assertTrue(rs.next());
    assertFalse(rs.next());

    rs = conn.createStatement().executeQuery(
        "select m.ID, m.hostdata from sys.systables t, sys.members m where "
            + "t.tableschemaname='APP' and t.tablename='PIZZA' "
            + "and m.hostdata = 1");

    assertTrue(rs.next());
    assertFalse(rs.next());
  }

  public void testSemiColonTermination() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table pizza (i int primary key, j varchar(10), "
        + "x char(10) ) ;");
    s.execute("drop table pizza; ");
    s.execute("create table pizza (i int primary key, j int, "
        + "x char(10) ) partition by (j);");
    s.execute("drop table pizza; ");

    String createFunction = "CREATE FUNCTION times " + "(value INTEGER )"
        + "RETURNS INTEGER " + "LANGUAGE JAVA "
        + "EXTERNAL NAME 'com.pivotal.gemfirexd.functions.TestFunctions.times' "
        + "PARAMETER STYLE JAVA NO SQL RETURNS NULL ON NULL INPUT";

    conn.createStatement().execute(createFunction);
    s.execute("drop function times; ");

    s.execute("create table pizza (i int primary key, j int, x char(10) ) "
        + "partition by range (j) (values between 1 and 10) redundancy 1;");

    s.execute("insert into pizza values (1, 2, 'x');");
    s.execute("update pizza set x='y';");
    ResultSet rs = s.executeQuery("select count(*) from pizza group by "
        + "substr(x, 1, 1);");
    while (rs.next())
      ;

    rs = s.executeQuery("select dsid(), count(i) from pizza, sys.members "
        + "--gemfirexd-properties withSecondaries=true \n;");
    while (rs.next())
      ;

    rs = s.executeQuery("values current schema;");
    while (rs.next())
      ;

    s.execute("delete from pizza;");

    try {
      s.execute("insert into pizza values (2, 3, 'u'),(;");
    } catch (SQLException e) {
      if (!"42x01".equalsIgnoreCase(e.getSQLState())) {
        throw e;
      }
    }
  }

  public void testDeleteWithSubquery() throws SQLException {

    Connection conn = getConnection();
    Statement st = conn.createStatement();

    st.execute("create table Image (id int primary key, imageId int) ");
    st.execute("insert into Image values (1, 1) , (2, 1), (3, 1), (4, 2) ");

    st.execute("create table JournalArticleImage "
        + "(articleImageId int primary key, tempImage int )");
    st.execute("insert into JournalArticleImage values (1, 1), (3, 1), (4, 2)");

    st.execute("delete from Image where imageId IN (SELECT articleImageId "
        + "FROM JournalArticleImage where tempImage = 1)");
  }

  public void test41047() throws SQLException {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create table tt1 (CLICOL01 smallint not null,"
        + "clicol02 smallint, clicol51 blob(1G))");
    conn.commit();

    PreparedStatement pSt = conn.

    prepareStatement("insert into tt1 values (?,?,?)");

    pSt.setShort(1, (short)500);
    pSt.setShort(2, (short)501);
    pSt.setBytes(3, "404 bit".getBytes());
    pSt.execute();

    ResultSet rs = st.executeQuery("select * from tt1");
    assertTrue(rs.next());
    assertEquals(500, rs.getShort(1));
    assertEquals("Second int is supposed to be 501 not 500", 501,
        rs.getShort(2));
    assertFalse(rs.next());

    pSt.close();
    conn.commit();
  }

  /*
   * Non-Distributed scenario: mcast-port=0
   * Test Util force partition-attribute to Partitioned  
   */
  public void test42856_42918_DerbyPartioned() throws SQLException {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, "
        + "ADDRESS1 varchar(1024) not null ) ");
    conn.commit();

    PreparedStatement pSt = conn
        .prepareStatement("Insert into  TESTTABLE1 values(?,?,?)");
    for (int i = 1; i < 4; ++i) {
      pSt.setInt(1, i);
      pSt.setString(2, "desc1_" + i);
      pSt.setString(3, "add1_" + i);
      pSt.execute();
    }

    ResultSet rs42856 = st
        .executeQuery("select ID1, DESCRIPTION1 from TESTTABLE1 "
            + "where DESCRIPTION1 = ( select DESCRIPTION1 from TESTTABLE1 "
            + "where DESCRIPTION1 > 'desc1_1' intersect "
            + "select DESCRIPTION1 from TESTTABLE1 "
            + "where DESCRIPTION1 < 'desc1_3')");
    assertTrue(rs42856.next());
    assertEquals(2, rs42856.getInt(1));
    assertFalse(rs42856.next());

    ResultSet rs42918 = st
        .executeQuery("SELECT COUNT(ID1) FROM TESTTABLE1 UNION SELECT ID1 "
            + "FROM TESTTABLE1 where ID1 = 0");
    assertTrue(rs42918.next());
    assertEquals(3, rs42918.getInt(1));
    assertFalse(rs42918.next());

    pSt.close();
    conn.commit();
  }

  /*
   * Non-Distributed scenario: mcast-port=0
   * Override TestUtil's behaviour to change partition attribute
   * Set partition-attribute to (default)Partitioned  
   */
  public void test42856_42918_DerbyReplicated() throws SQLException {
    Connection conn = null;
    {
      // override the flag set in TestUtil
      skipDefaultPartitioned = true;
      conn = getConnection();
      // set the flag to default in TestUtil
      skipDefaultPartitioned = false;
    }
    Statement st = conn.createStatement();
    st.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) "
        + "not null)");
    conn.commit();

    PreparedStatement pSt = conn
        .prepareStatement("Insert into  TESTTABLE1 values(?,?,?)");
    for (int i = 1; i < 4; ++i) {
      pSt.setInt(1, i);
      pSt.setString(2, "desc1_" + i);
      pSt.setString(3, "add1_" + i);
      pSt.execute();
    }

    ResultSet rs42856 = st
        .executeQuery("select ID1, DESCRIPTION1 from TESTTABLE1 "
            + "where DESCRIPTION1 = ( select DESCRIPTION1 from TESTTABLE1 "
            + "where DESCRIPTION1 > 'desc1_1' intersect "
            + "select DESCRIPTION1 from TESTTABLE1 "
            + "where DESCRIPTION1 < 'desc1_3')");
    assertTrue(rs42856.next());
    assertEquals(2, rs42856.getInt(1));
    assertFalse(rs42856.next());

    ResultSet rs42918 = st
        .executeQuery("SELECT COUNT(ID1) FROM TESTTABLE1 UNION SELECT ID1 "
            + "FROM TESTTABLE1 where ID1 = 0");
    assertTrue(rs42918.next());
    assertEquals(3, rs42918.getInt(1));
    assertFalse(rs42918.next());

    pSt.close();
    conn.commit();
  }

  /*
   * Distributed scenario: mcast-port is Non-zero
   * TestUtil has set partition attribute to Partitioned
   */
  public void test42856_42918_singleDistributedNode() throws SQLException {
    Properties props = new Properties();
    String available_port = String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS));
    props.setProperty("mcast-port", available_port);
    Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) "
        + "not null ) ");
    conn.commit();

    PreparedStatement pSt = conn
        .prepareStatement("Insert into  TESTTABLE1 values(?,?,?)");
    for (int i = 1; i < 4; ++i) {
      pSt.setInt(1, i);
      pSt.setString(2, "desc1_" + i);
      pSt.setString(3, "add1_" + i);
      pSt.execute();
    }

    try {
      // SqlF in distribution should fail
      ResultSet rs42856 = st
          .executeQuery("select ID1, DESCRIPTION1 from TESTTABLE1 "
              + "where DESCRIPTION1 = ( select DESCRIPTION1 from TESTTABLE1 "
              + "where DESCRIPTION1 > 'desc1_1' intersect "
              + "select DESCRIPTION1 from TESTTABLE1 "
              + "where DESCRIPTION1 < 'desc1_3')");
      rs42856.next();
      fail("Test should fail with feature not supported exception");
    } catch (SQLException sqle) {
      assertEquals("0A000", sqle.getSQLState());
    }

    try {
      ResultSet rs42918 = st
          .executeQuery("SELECT COUNT(ID1) FROM TESTTABLE1 UNION SELECT ID1 "
              + "FROM TESTTABLE1 where ID1 = 0");
      assertTrue(rs42918.next());
      fail("Test should fail with feature not supported exception");
    } catch (SQLException sqle) {
      assertEquals("0A000", sqle.getSQLState());
    }

    pSt.close();
    conn.commit();
  }
  
  /*
   * Distributed scenario: mcast-port is Zero
   * TestUtil has set partition attribute to Partitioned
   */
  public void test42856_42918_46899_lonerVmNode() throws SQLException {
    Properties props = new Properties();
    props.setProperty("mcast-port", String.valueOf(0));
    Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) "
        + "not null ) ");
    conn.commit();

    PreparedStatement pSt = conn
        .prepareStatement("Insert into  TESTTABLE1 values(?,?,?)");
    for (int i = 1; i < 4; ++i) {
      pSt.setInt(1, i);
      pSt.setString(2, "desc1_" + i);
      pSt.setString(3, "add1_" + i);
      pSt.execute();
    }

    ResultSet rs42918 = st
        .executeQuery("SELECT COUNT(ID1) FROM TESTTABLE1 UNION SELECT ID1 "
            + "FROM TESTTABLE1 where ID1 = 0");
    assertTrue(rs42918.next());
    assertEquals(3, rs42918.getInt(1));
    assertFalse(rs42918.next());

    pSt.close();
    conn.commit();
  }

  public void testDecimalDEFAULT() throws Exception {
    // start a server with a network server
    setupConnection();
    final int netPort = startNetserverAndReturnPort();

    // use this VM as network client and also peer client
    final Connection netConn = TestUtil.getNetConnection(netPort, null, null);
    final Statement stmt = netConn.createStatement();

    // create table with SQL decimal field
    // another table to check for DEFAULT value inserts
    final int[] precisions = new int[] { 30, 31, 35, 36, 37, 44, 45, 46, 53,
        54, 55, 125, 126, 127 };
    for (int prec : precisions) {
      sqlExecute("create table s.defaulttest(id int primary key, "
          + " price decimal(" + prec
          + ", 20) default null, qty int default 0, "
          + "name varchar(10) default 'none')", true);
      String insertStr = "insert into s.defaulttest (id, price, qty, name) "
          + "values(?, ?, DEFAULT, DEFAULT)";
      PreparedStatement insertStmt = netConn.prepareStatement(insertStr);
      final StringBuilder decP = new StringBuilder();
      for (int i = 1; i <= (prec - 20) / 2; i++) {
        decP.append("20");
      }
      final String decPrefix = decP.append('.').toString();
      for (int id = 1; id < 5; id++) {
        insertStmt.setInt(1, id);
        if (id == 1) {
          insertStmt.setBigDecimal(2, BigDecimal.ZERO);
        }
        else if (id == 2) {
          insertStmt.setBigDecimal(2, new BigDecimal("0.000"));
        }
        else {
          insertStmt.setBigDecimal(2, new BigDecimal(decPrefix + id));
        }
        assertEquals(1, insertStmt.executeUpdate());
      }
      for (int id = 5; id < 10; id++) {
        assertEquals(1,
            stmt.executeUpdate("insert into s.defaulttest "
                + "(id, price, qty, name) values(" + id + ", "
                + new BigDecimal(decPrefix + id) + ", DEFAULT, DEFAULT)"));
      }
      insertStmt = netConn.prepareStatement("insert into s.defaulttest "
          + "values(?, DEFAULT, DEFAULT, ?)");
      for (int id = 10; id < 15; id++) {
        insertStmt.setInt(1, id);
        insertStmt.setString(2, "name_" + id);
        assertEquals(1, insertStmt.executeUpdate());
      }
      for (int id = 15; id < 20; id++) {
        assertEquals(1,
            stmt.executeUpdate("insert into s.defaulttest " + "values(" + id
                + ", DEFAULT, DEFAULT, 'name_" + id + "')"));
      }
      ResultSet rs = stmt
          .executeQuery("select * from s.defaulttest order by id");
      for (int id = 1; id < 20; id++) {
        assertTrue(rs.next());
        assertEquals(id, rs.getInt(1));
        if (id == 1) {
          assertEquals(BigDecimal.ZERO.setScale(20), rs.getBigDecimal(2));
          assertEquals("none", rs.getString(4));
        }
        else if (id == 2) {
          assertEquals(new BigDecimal("0.000").setScale(20),
              rs.getBigDecimal(2));
          assertEquals("none", rs.getString(4));
        }
        else if (id < 10) {
          assertEquals(new BigDecimal(decPrefix + id).setScale(20),
              rs.getBigDecimal(2));
          assertEquals("none", rs.getString(4));
        }
        else {
          assertNull(rs.getBigDecimal(2));
          assertNull(rs.getString(2));
          assertEquals("name_" + id, rs.getString(4));
        }
        assertEquals(0, rs.getInt(3));
      }
      assertFalse(rs.next());

      rs = stmt.executeQuery("select distinct avg(price) from s.defaulttest");
      assertTrue(rs.next());
      BigDecimal sum = BigDecimal.ZERO;
      for (int id = 3; id < 10; id++) {
        sum = sum.add(new BigDecimal(decPrefix + id));
      }
      assertEquals(sum.divide(new BigDecimal(9), 20, RoundingMode.DOWN),
          rs.getBigDecimal(1));
      assertFalse(rs.next());

      stmt.execute("drop table s.defaulttest");
    }
  }

	public void testGroupingOptimizationForGroupByClause_1() throws Exception {

		SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
		Statement stmt = null;
		final String query = "select sid, count(*) from txhistory  where cid > ? and sid < ?  GROUP BY sid HAVING count(*) >=1";
		Connection conn = null;
		Statement derbyStmt = null;
		String table = "create table txhistory(cid int,  sid int, qty int)";
		String index = "create index txhistory_sid on txhistory(sid)";
		final List<Throwable> throwables = new ArrayList<Throwable>();
		GemFireXDQueryObserver old = null;

		try {

			String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
			if (currentUserName != null) {
				tempDerbyUrl += ("user=" + currentUserName + ";password="
						+ currentUserPassword + ';');
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
			stmt.execute(table + " replicate");
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

			PreparedStatement derbyQueryStmt = derbyConn
					.prepareStatement(query);

			// Creating a statement object that we can use for
			// running various
			// SQL statements commands against the database.
			PreparedStatement stmtQuery = conn.prepareStatement(query);
			old = GemFireXDQueryObserverHolder
					.setInstance(new GemFireXDQueryObserverAdapter() {
						private static final long serialVersionUID = 1L;

						@Override
						public void onEmbedResultSetMovePosition(
								EmbedResultSet rs,
								ExecRow newRow,
								com.pivotal.gemfirexd.internal.iapi.sql.ResultSet theResults) {
							NoPutResultSet noputrs = (NoPutResultSet) theResults;
							assertTrue(noputrs.supportsMoveToNextKey());
						}

					});

			int cid = 10;
			int sid = 100;
			stmtQuery.setInt(1, cid);
			stmtQuery.setInt(2, sid);

			derbyQueryStmt.setInt(1, cid);
			derbyQueryStmt.setInt(2, sid);

      validateResults(derbyQueryStmt, stmtQuery, query, false);

			ps_insert_derby.close();
			derbyQueryStmt.close();
		} catch (Exception e) {
			e.printStackTrace();
			fail("Test failed because of exception " + e);
		} finally {
			if (derbyStmt != null) {
				try {
					//derbyStmt.execute("drop index txhistory.txhistory_sid");
					derbyStmt.execute("drop table txhistory");
				} catch (Exception e) {
					e.printStackTrace();
					// ignore intentionally
				}
			}
			if(stmt != null) {
				try {
					//stmt.execute("drop index txhistory.txhistory_sid");
					stmt.execute("drop table txhistory");
				} catch (Exception e) {
					// ignore intentionally
				}
			}
			if (old != null) {
				GemFireXDQueryObserverHolder.setInstance(old);
			}
		}

	}

	public void testGroupingOptimizationForGroupByClause_2() throws Exception {

		SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
		Statement stmt = null;
		final String query = "select sid, count(*) from txhistory  where cid > ? and sid < ?  GROUP BY sid HAVING count(*) >=1";
		Connection conn = null;
		Statement derbyStmt = null;
		String table = "create table txhistory(cid int,  sid int, qty int)";
		String index = "create index txhistory_sid_cid on txhistory(sid, cid)";
		final List<Throwable> throwables = new ArrayList<Throwable>();
		GemFireXDQueryObserver old = null;

		try {

			String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
			if (currentUserName != null) {
				tempDerbyUrl += ("user=" + currentUserName + ";password="
						+ currentUserPassword + ';');
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
			stmt.execute(table + " replicate");
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

			PreparedStatement derbyQueryStmt = derbyConn
					.prepareStatement(query);

			// Creating a statement object that we can use for
			// running various
			// SQL statements commands against the database.
			PreparedStatement stmtQuery = conn.prepareStatement(query);
			old = GemFireXDQueryObserverHolder
					.setInstance(new GemFireXDQueryObserverAdapter() {
						private static final long serialVersionUID = 1L;

						@Override
						public void onEmbedResultSetMovePosition(
								EmbedResultSet rs,
								ExecRow newRow,
								com.pivotal.gemfirexd.internal.iapi.sql.ResultSet theResults) {
							NoPutResultSet noputrs = (NoPutResultSet) theResults;
							assertFalse(noputrs.supportsMoveToNextKey());
						}

					});

			int cid = 10;
			int sid = 100;
			stmtQuery.setInt(1, cid);
			stmtQuery.setInt(2, sid);

			derbyQueryStmt.setInt(1, cid);
			derbyQueryStmt.setInt(2, sid);

      validateResults(derbyQueryStmt, stmtQuery, query, false);

			ps_insert_derby.close();
			derbyQueryStmt.close();
			

		} catch (Exception e) {
			e.printStackTrace();
			fail("Test failed because of exception " + e);
		} finally {
			if (derbyStmt != null) {
				try {
					//derbyStmt.execute("drop index txhistory.txhistory_sid_cid");
					derbyStmt.execute("drop table txhistory");
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			if(stmt != null) {
				try {
					//stmt.execute("drop index txhistory.txhistory_sid_cid ");
					stmt.execute("drop table txhistory");
				} catch (Exception e) {
					// ignore intentionally
				}
			}			if (old != null) {
				GemFireXDQueryObserverHolder.setInstance(old);
			}
		}

	}

	public void testGroupingOptimizationForGroupByClause_3() throws Exception {

		SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
		Statement stmt = null;
		final String query = "select sid, count(*) from txhistory  where cid > ? and sid < ?  GROUP BY sid HAVING count(*) >=1";
		Connection conn = null;
		Statement derbyStmt = null;
		String table = "create table txhistory(cid int,  sid int, qty int)";
		final List<Throwable> throwables = new ArrayList<Throwable>();
		GemFireXDQueryObserver old = null;

		try {

			String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
			if (currentUserName != null) {
				tempDerbyUrl += ("user=" + currentUserName + ";password="
						+ currentUserPassword + ';');
			}
			Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
			final String derbyDbUrl = tempDerbyUrl;
			Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
			derbyStmt = derbyConn.createStatement();
			derbyStmt.execute(table);
			Statement derbyStmt1 = derbyConn.createStatement();
			conn = TestUtil.getConnection();

			// Creating a statement object that we can use for running various
			// SQL statements commands against the database.
			stmt = conn.createStatement();
			stmt.execute(table + " replicate");

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

			PreparedStatement derbyQueryStmt = derbyConn
					.prepareStatement(query);

			// Creating a statement object that we can use for
			// running various
			// SQL statements commands against the database.
			PreparedStatement stmtQuery = conn.prepareStatement(query);
			old = GemFireXDQueryObserverHolder
					.setInstance(new GemFireXDQueryObserverAdapter() {
						private static final long serialVersionUID = 1L;

						@Override
						public void onEmbedResultSetMovePosition(
								EmbedResultSet rs,
								ExecRow newRow,
								com.pivotal.gemfirexd.internal.iapi.sql.ResultSet theResults) {
							NoPutResultSet noputrs = (NoPutResultSet) theResults;
							assertFalse(noputrs.supportsMoveToNextKey());
						}

					});

			int cid = 10;
			int sid = 100;
			stmtQuery.setInt(1, cid);
			stmtQuery.setInt(2, sid);

			derbyQueryStmt.setInt(1, cid);
			derbyQueryStmt.setInt(2, sid);

      validateResults(derbyQueryStmt, stmtQuery, query, false);

			ps_insert_derby.close();
			derbyQueryStmt.close();
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
			if(stmt != null) {
				try {
					stmt.execute("drop table txhistory");
				} catch (Exception e) {
					// ignore intentionally
				}
			}
			if (old != null) {
				GemFireXDQueryObserverHolder.setInstance(old);
			}
		}

	}

	public void testGroupingOptimizationForGroupByClause_4() throws Exception {

		SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
		Statement stmt = null;
		final String query = "select sid,cid, count(*) from txhistory  where cid > ? and sid < ?  GROUP BY sid , cid HAVING count(*) >=1";
		Connection conn = null;
		Statement derbyStmt = null;
		String table = "create table txhistory(cid int,  sid int, qty int)";
		String index = "create index txhistory_sid_cid on txhistory(sid, cid)";
		final List<Throwable> throwables = new ArrayList<Throwable>();
		GemFireXDQueryObserver old = null;

		try {

			String tempDerbyUrl = "jdbc:derby:newDB;create=true;";
			if (currentUserName != null) {
				tempDerbyUrl += ("user=" + currentUserName + ";password="
						+ currentUserPassword + ';');
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
			stmt.execute(table + " replicate");
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

			PreparedStatement derbyQueryStmt = derbyConn
					.prepareStatement(query);

			// Creating a statement object that we can use for
			// running various
			// SQL statements commands against the database.
			PreparedStatement stmtQuery = conn.prepareStatement(query);
			old = GemFireXDQueryObserverHolder
					.setInstance(new GemFireXDQueryObserverAdapter() {
						private static final long serialVersionUID = 1L;

						@Override
						public void onEmbedResultSetMovePosition(
								EmbedResultSet rs,
								ExecRow newRow,
								com.pivotal.gemfirexd.internal.iapi.sql.ResultSet theResults) {
							NoPutResultSet noputrs = (NoPutResultSet) theResults;
							assertTrue(noputrs.supportsMoveToNextKey());
						}

					});

			int cid = 10;
			int sid = 100;
			stmtQuery.setInt(1, cid);
			stmtQuery.setInt(2, sid);

			derbyQueryStmt.setInt(1, cid);
			derbyQueryStmt.setInt(2, sid);

      validateResults(derbyQueryStmt, stmtQuery, query, false);

			ps_insert_derby.close();
			derbyQueryStmt.close();
		} catch (Exception e) {
			e.printStackTrace();
			fail("Test failed because of exception " + e);
		} finally {
			if (derbyStmt != null) {
				try {
					//derbyStmt.execute("drop index txhistory.txhistory_sid_cid ");
					derbyStmt.execute("drop table txhistory");
				} catch (Exception e) {
					// ignore intentionally
				}
			}
			if(stmt != null) {
				try {
					//stmt.execute("drop index txhistory.txhistory_sid_cid ");
					stmt.execute("drop table txhistory");
				} catch (Exception e) {
					// ignore intentionally
				}
			}			if (old != null) {
				GemFireXDQueryObserverHolder.setInstance(old);
			}
		}

	}

  // Enable after fixing bug #45041 
  public void DISABLE_testOrList_45041() throws Exception {
    setupConnection();

    final Connection conn = jdbcConn;
    final Statement stmt = conn.createStatement();

    stmt.execute("create table test1 (id int primary key, "
        + "name varchar(100) not null)");
    stmt.execute("create index idx1 on test1(name)");

    for (int id = 1; id < 100; id++) {
      stmt.execute("insert into test1 values (" + id + ", 'name" + id + "')");
    }

    // OR queries which should use a MultiColumnTableScanRS
    ResultSet rs;
    int i;

    // Set the observer to check that proper scans are being opened
    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    rs = stmt.executeQuery("select * from test1 where name < 'name5'"
        + " or name > 'name90' order by id");
    i = 1;
    while (rs.next()) {
      if (i == 5) {
        i = 10; // name10 < name5
      }
      else if (i == 50) {
        i = 91;
      }
      assertEquals(i, rs.getInt(1));
      assertEquals("name" + i, rs.getString(2));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select id from test1 where name < 'name5'"
        + " or name > 'name90' order by name");
    i = 1;
    while (rs.next()) {
      if (i > 1) {
        if (i <= 5) {
          i = (i - 1) * 10;
        }
        else if ((i % 10 == 0) && i < 50) {
          i = i / 10;
        }
        else if (i == 50) {
          i = 91;
        }
      }
      assertEquals(i, rs.getInt(1));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name from test1 where name < 'name5'"
        + " or name > 'name90' order by name");
    i = 1;
    while (rs.next()) {
      if (i > 1) {
        if (i <= 5) {
          i = (i - 1) * 10;
        }
        else if ((i % 10 == 0) && i < 50) {
          i = i / 10;
        }
        else if (i == 50) {
          i = 91;
        }
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where name < 'name5'"
        + " or name > 'name90' order by name");
    i = 1;
    while (rs.next()) {
      if (i > 1) {
        if (i <= 5) {
          i = (i - 1) * 10;
        }
        else if ((i % 10 == 0) && i < 50) {
          i = i / 10;
        }
        else if (i == 50) {
          i = 91;
        }
      }
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name from test1 where name < 'name5'"
        + " or name > 'name90' order by id");
    i = 1;
    while (rs.next()) {
      if (i == 5) {
        i = 10; // name10 < name5
      }
      else if (i == 50) {
        i = 91;
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where name < 'name5'"
        + " or name > 'name90' order by id");
    i = 1;
    while (rs.next()) {
      if (i == 5) {
        i = 10; // name10 < name5
      }
      else if (i == 50) {
        i = 91;
      }
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(100, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    // with overlapping ranges
    Object[][] expectedRows = new Object[][] {
        new Object[] { 7, "name7" },
        new Object[] { 8, "name8" },
        new Object[] { 9, "name9" },
        new Object[] { 61, "name61" },
        new Object[] { 62, "name62" },
        new Object[] { 63, "name63" },
        new Object[] { 64, "name64" },
        new Object[] { 65, "name65" },
        new Object[] { 66, "name66" },
        new Object[] { 67, "name67" },
        new Object[] { 68, "name68" },
        new Object[] { 69, "name69" },
        new Object[] { 70, "name70" },
        new Object[] { 71, "name71" },
        new Object[] { 72, "name72" },
        new Object[] { 73, "name73" },
        new Object[] { 74, "name74" },
        new Object[] { 75, "name75" },
        new Object[] { 76, "name76" },
        new Object[] { 77, "name77" },
        new Object[] { 78, "name78" },
        new Object[] { 79, "name79" },
        new Object[] { 80, "name80" },
        new Object[] { 81, "name81" },
        new Object[] { 82, "name82" },
        new Object[] { 83, "name83" },
        new Object[] { 84, "name84" },
        new Object[] { 85, "name85" },
        new Object[] { 86, "name86" },
        new Object[] { 87, "name87" },
        new Object[] { 88, "name88" },
        new Object[] { 89, "name89" },
    };

    rs = stmt.executeQuery("select id from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90') " +
        		"order by id");
    i = 7;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals(i, rs.getInt(1));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90') " +
                        "order by id");
    i = 7;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90') " +
                        "order by id");
    i = 7;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select id, name from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90')");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select * from test1 where (name < 'name80'"
        + " and name > 'name60') or (name > 'name70' and name < 'name90')");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    // and multiple columns
    rs = stmt.executeQuery("select name from test1 where (name < 'name80'"
        + " and id > 60) or (name > 'name70' and id < 90) " +
                        "order by id");
    i = 8;
    while (rs.next()) {
      if (i == 10) {
        i = 61;
      }
      assertEquals("name" + i, rs.getString(1));
      i++;
    }
    assertEquals(90, i);

    // TODO: PERF: currently above query will use table scan instead of index
    // scan due to inablity to pass table level qualifiers to index scans

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select name, id from test1 where (name < 'name80'"
        + " and id > 60) or (id > 70 and name < 'name90') " +
                        "order by id");
    i = 61;
    while (rs.next()) {
      assertEquals("name" + i, rs.getString(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertEquals(90, i);

    // Check the scan types opened
    observer.addExpectedScanType(
        getCurrentDefaultSchemaName() + ".TEST1", ScanType.TABLE);
    observer.checkAndClear();
  }
  
  public void test45509() throws Exception {
    Properties cp = new Properties();
    //cp.setProperty("log-level", "fine");
    cp.setProperty("mcast-port", Integer.toString(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    Connection conn = TestUtil.getConnection(cp);

    Statement st = conn.createStatement();

    st.execute("create table course (" + "course_id int, i int, "
        + " primary key(course_id)"
        + ") partition by column (i) redundancy 1");

    st.execute("insert into course values (1, 1), (2, 2), "
        + "(3, 1), (4, 3), (5, 1)");
    
    String myId = Misc.getGemFireCache().getMyId().toString();
    
    GemFireXDQueryObserverAdapter observer = new GemFireXDQueryObserverAdapter() {
      private static final long serialVersionUID = 1L;

      @Override
      public void regionSizeOptimizationTriggered(FromBaseTable fbt,
          com.pivotal.gemfirexd.internal.impl.sql.compile.SelectNode selectNode) {
        assertTrue(selectNode.convertCountToRegionSize());
        // assure we aren't selecting all the base table columns.
        // instead just a dummy column selection happening for count(*).
        assertTrue(fbt.getResultColumns().size() <= 1);
      }
    };
    
    GemFireXDQueryObserverHolder.setInstance(observer);

    ResultSet r = st.executeQuery("select count(*) from course -- GEMFIREXD-PROPERTIES withSecondaries=true\n");
    assertTrue(r.next());
    assertEquals(5, r.getInt(1));
    assertFalse(r.next());
    
    r = st.executeQuery("select dsid(), count(*) from course");
    assertTrue(r.next());
    assertEquals(myId, r.getString(1));
    assertEquals(5, r.getInt(2));
    assertFalse(r.next());
    
    r = st.executeQuery("select dsid(), count(*) from course group by dsid()");
    assertTrue(r.next());
    assertEquals(myId, r.getString(1));
    assertEquals(5, r.getInt(2));
    assertFalse(r.next());
    
    r = st.executeQuery("select count(*), dsid(), count(*) from course group by dsid() having count(*) > 1");
    assertTrue(r.next());
    assertEquals(5, r.getInt(1));
    assertEquals(myId, r.getString(2));
    assertEquals(5, r.getInt(3));
    assertFalse(r.next());

    r = st.executeQuery("select count(*) from course group by dsid() having count(*) > 1");
    assertTrue(r.next());
    assertEquals(5, r.getInt(1));
    assertFalse(r.next());

    r = st.executeQuery("select count(*) from course group by dsid() having count(*) <= 0 or count(*) > 5");
    assertFalse(r.next());
    
    r = st.executeQuery("select count(*) from course group by dsid()");
    assertTrue(r.next());
    assertEquals(5, r.getInt(1));
    assertFalse(r.next());
  }


  public void test49829() throws SQLException {
    
    Properties cp = new Properties();
    //cp.setProperty("log-level", "fine");
    cp.setProperty("mcast-port", Integer.toString(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    Connection conn = TestUtil.getConnection(cp);

    Statement st = conn.createStatement();

    st.execute("create table FDC_NRT_CNTXT_HIST (EQP_ID VARCHAR(40) NOT NULL, CNTXT_ID INTEGER NOT NULL, STOP_DT TIMESTAMP NOT NULL, "
        + " primary key(eqp_id, cntxt_id, stop_dt) "
        + ") partition by column (eqp_id, cntxt_id, stop_dt)");

    st.execute("insert into FDC_NRT_CNTXT_HIST values "
        + " ('1', 1, '2014-01-24 18:48:00')"
        + ",('2', 1, '2014-01-24 18:48:00')"
        + ",('3', 1, '2014-01-24 18:48:00')"
        + ",('4', 1, '2014-01-24 18:48:00')"
        + ",('5', 1, '2014-01-24 18:48:00')"
        + ",('6', 1, '2014-01-24 18:48:00')"
        + ",('7', 1, '2014-01-24 18:48:00')"
        + ",('8', 1, '2014-01-24 18:48:00')"
        + ",('9', 1, '2014-01-24 18:48:00')"
        + ",('10', 1, '2014-01-24 18:48:00')"
        + ",('11', 1, '2014-01-24 18:48:00')"
        + ",('12', 1, '2014-01-24 18:48:00')"
        + ",('13', 1, '2014-01-24 18:48:00')"
    );
    
    st.execute("create table FDC_NRT_TCHART_HIST (EQP_ID VARCHAR(40) NOT NULL, CNTXT_ID INTEGER NOT NULL, STOP_DT TIMESTAMP NOT NULL, SVID_NAME VARCHAR(64) NOT NULL, "
        + " primary key(eqp_id, cntxt_id, stop_dt, svid_name) "
        + ") partition by column (eqp_id, cntxt_id, stop_dt)"
        + " COLOCATE WITH (FDC_NRT_CNTXT_HIST)");

    st.execute("insert into FDC_NRT_TCHART_HIST values "
        + " ('1', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('2', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('3', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('4', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('5', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('6', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('7', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('8', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('9', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('10', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('11', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('12', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
        + ",('13', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59')"
    );
    
    st.execute("create table FDC_NRT_PUMPER_HIST_LOG ( EQP_ID VARCHAR(40) NOT NULL, CNTXT_ID INTEGER NOT NULL, STOP_DT TIMESTAMP NOT NULL, UPDATE_DT TIMESTAMP NOT NULL, "
        + "EXEC_TIME DOUBLE, primary key (eqp_id, cntxt_id, stop_dt, update_dt) "
        + ") partition by primary key");
    
    st.execute("insert into FDC_NRT_PUMPER_HIST_LOG values "
        + " ('1', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('2', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('3', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('4', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('5', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('6', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('7', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('8', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('9', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('10', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('11', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('12', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
        + ",('13', 1, '2014-01-24 18:48:00', '2014-01-24 18:47:59', 41)"
    );
    
    GemFireXDQueryObserverAdapter observer = new GemFireXDQueryObserverAdapter() {
      private static final long serialVersionUID = 1L;

      @Override
      public void regionSizeOptimizationTriggered(FromBaseTable fbt,
          com.pivotal.gemfirexd.internal.impl.sql.compile.SelectNode selectNode) {
      }
    };
    
    GemFireXDQueryObserverHolder.setInstance(observer);
    
    ResultSet r = st.executeQuery("explain select a.eqp_id, a.cntxt_id, a.stop_dt, dsid() as datanode_id " 
    + "from FDC_NRT_CNTXT_HIST a "
    + "left join FDC_NRT_TCHART_HIST b "
    + "on (a.eqp_id =b.eqp_id and a.cntxt_id=b.cntxt_id and a.stop_dt=b.stop_dt) " 
    + "where a.eqp_id||cast(a.cntxt_id as char(100)) " 
    + "in "
    + "( "
    + "select eqp_id||cast(t.cntxt_id as char(100)) "
    + "from FDC_NRT_PUMPER_HIST_LOG t where 1=1 " 
    + "and exec_time > 40 "
    + "and stop_dt > '2014-01-24 18:47:59' " 
    + "and stop_dt < '2014-01-24 18:49:59'" 
    + ")");
    
    while(r.next()) {
      System.out.println(r.getString(1));
    }
    
  }
  
  private void assertResult(String query, Connection conn, boolean prepare)
      throws SQLException {

    ResultSet r;
    if (prepare) {
      PreparedStatement ps = conn.prepareStatement(query);
      r = ps.executeQuery();
    }
    else {
      r = conn.createStatement().executeQuery(query);
    }

    int numrows = 0;
    while (r.next()) {
      assertEquals(100, r.getInt(1));
      System.out.println(r.getInt(1));
      numrows++;
    }
    assertEquals(10, numrows);
    r.close();
  }

  public int selectFromTable(Connection conn, int retrievalSize, int numQueries)
      throws SQLException {
    Random rand = new Random(System.currentTimeMillis());
    int count = 0;
    int startLoc = rand.nextInt(5000);
    PreparedStatement q = conn
        .prepareStatement("Select * from Orders where vol > ? and vol < ?");

    for (int i = startLoc; i < startLoc + numQueries; i++) {
      count += selectFromTable(conn, q, i, retrievalSize);
    }
    return count;
  }

  private int selectFromTable(Connection conn, PreparedStatement q, int from,
      int retrievalSize) throws SQLException {
    ResultSet rs;
    int count = 0;
    q.setInt(1, from);
    q.setInt(2, from + retrievalSize + 1);
    rs = q.executeQuery();
    while (rs.next()) {
      count++;
    }
    assertEquals("Row count for single query did not match;", retrievalSize,
        count);
    return count;
  }

  /**
   * Retrieve rows "manually" from the Region host. Runs one query
   */
  public int selectFromRegion(int retrievalSize, int expectedRegionSize)
      throws Exception {
    System.out.println("Running query directly on region...");
    Random rand = new Random(System.currentTimeMillis());
    int count = 0;
    int startLoc = rand.nextInt(5000);
    final Region<?, ?> region = Misc.getRegionForTable(
        getCurrentDefaultSchemaName() + ".ORDERS", true);
    assertEquals("region size;", expectedRegionSize, region.size());

    // Using reflection to run against the region is pretty slow.
    // Only run one query here instead of 100

    /* List seenVols = new ArrayList(expectedRegionSize); */
    int i = startLoc;
    int iCount = 0; // num of results in single query

    /* seenVols.clear(); */

    // the "query" itself
    for (Iterator itr = region.values().iterator(); itr.hasNext();) {
      // get vol field (index 2 in row)
      int vol = getIntFromDataValue(itr.next(), 2);
      /* seenVols.add(new Integer(vol)); */
      int from = i;
      int to = i + retrievalSize + 1;
      if (vol > from && vol < to) {
        count++;
        iCount++;
      }
    }

    /*
    if (retrievalSize != iCount) {
      Integer[] sorted = (Integer[])seenVols.toArray(new Integer[seenVols.size()]);
      Arrays.sort(sorted);
      fail("Row count for single query did not match; expected:<" +
           retrievalSize + "> but was:<" + iCount + ">; saw <" +
           sorted.length + "> vols, ranging from <" +
           sorted[0].intValue() + "> to <" +
           sorted[sorted.length - 1].intValue() + ">.");
    }
     */
    assertEquals("Row count for single query did not match. ", retrievalSize,
        iCount);
    return count;
  }

  public void clearTable(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    s.execute("delete from orders");
    s.close();
  }

  public void dropTable(Connection conn) throws SQLException {
    if(conn.isClosed()) {
      return;
    }
    Statement s = conn.createStatement();
    try {
      s.execute("drop table orders");
    } catch (SQLException sqle) {
    }
    s.close();
  }

  public void createTable(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    DatabaseMetaData dbmd = conn.getMetaData();
    final ResultSet rs = dbmd.getTables(null, null,
        "orders".toUpperCase(), new String[] { "ROW TABLE" });
    // also check for a few flags that are failing over network connection
    assertTrue(dbmd.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
    assertTrue(dbmd.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
    final boolean found = rs.next();
    rs.close();

    if (found)
      s.execute("drop table orders ");

    // We create a table...
    s.execute("create table orders"
        + "(id int not null , cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100))");
    s.close();
  }

  public void createTableWithPrimaryKey(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    // We create a table...
    s.execute("create table orders"
        + "(id int PRIMARY KEY, cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100))");
    s.close();

  }

  public void createIndex(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    // create an index on 'volume'
    s.execute("create index volumeIdx on orders(vol)");
    s.close();
  }

  public void loadSampleData(Connection conn, int numOfCustomers,
      int numOrdersPerCustomer) throws SQLException {
    System.out.println("Loading data (" + numOfCustomers * numOrdersPerCustomer
        + " rows)...");
    String[] securities = { "IBM", "INTC", "MOT", "TEK", "AMD", "CSCO", "DELL",
        "HP", "SMALL1", "SMALL2" };

    Random rand = new Random(System.currentTimeMillis());
    PreparedStatement psInsert = conn
        .prepareStatement("insert into orders values (?, ?, ?, ?, ?, ?)");
    int volNum = 0;
    for (int i = 0; i < numOfCustomers; i++) {
      int baseId = i * numOrdersPerCustomer;
      String custName = "CustomerWithaLongName" + i;
      for (int j = 0; j < numOrdersPerCustomer; j++) {
        psInsert.setInt(1, baseId + j);
        psInsert.setString(2, custName);
        psInsert.setInt(3, volNum++); // max volume is 500
        psInsert.setString(4, securities[rand.nextInt(10)]);
        psInsert.setInt(5, 0);
        String queryString = "Emperors club for desperate men, "
            + "Washington DC, District of Columbia";
        psInsert.setString(6, queryString);
        psInsert.executeUpdate();
      }
    }
  }

  public void test49291() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    st.execute("create schema trade");

    // Create table
    st.execute("create table trade.test ( id int , name varchar(10))");
    st.execute("insert into trade.test values (1, 'tina')");
    st.execute("insert into trade.test values (2, 'mina')");
    st.execute("insert into trade.test values (null, 'jhk')");
    st.execute("insert into trade.test values (null, 'jhiok')");
    st.execute("insert into trade.test values (3, null)");
    st.execute("insert into trade.test values (4, null)");
    st.execute("insert into trade.test values (5, null)");

    st.execute("select * from trade.test where name = nvl('dada' , 'abc')");
    st.execute("select * from trade.test where name = nvl('' , 'abc')");
    st.execute("delete from trade.test where nvl('' , 'tina') = nvl(name, 'abc')");
    st.execute("delete from trade.test where nvl(name , 'abc') = nvl('', 'mina')");
    String query = "Select id, name from trade.test";
    ResultSet rs = st.executeQuery(query);
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(7, count);
    st.execute("delete from trade.test where nvl('tina', '') = nvl(name, 'abc')");
    st.execute("delete from trade.test where nvl(name , 'abc') = nvl('mina', '')");
    rs = st.executeQuery(query);
    count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(5, count);

    st.execute("drop table trade.test");
    st.execute("drop schema trade restrict");
  }
  
  public void test49292() throws Exception {
    Connection conn = getConnection();
    //Connection conn = startNetserverAndGetLocalNetConnection();
    Statement st = conn.createStatement();
    st.execute("drop table if exists course ");
    st.execute("create table course (" + "course_id int, i int, course_name varchar(10), "
        + " primary key(course_id)"
        + ") partition by primary key ");

    st
        .execute("insert into course values (1, 1, 'd'), (2, 2, 'd'), (3, 1, 'd'), (4, 3, 'd'), (5, 1, 'd')");
    
    st
    .execute("insert into course values (11, 1, 'd'), (12, 2, 'd'), (13, 1, 'd'), (14, 3, 'd'), (15, 1, 'd')");
    st
    .execute("insert into course values (21, 1, 'd'), (22, 2, 'd'), (23, 1, 'd'), (24, 3, 'd'), (25, 1, 'd')");
    st
    .execute("insert into course values (31, 1, 'd'), (32, 3, 'd'), (33, 1, 'd'), (34, 3, 'd'), (35, 1, 'd')");
    st
    .execute("insert into course values (41, 1, 'd'), (42, 4, 'd'), (43, 1, 'd'), (44, 3, 'd'), (45, 1, 'd')");

    ResultSet rs = null;
    String plan = null;
    
    {
      rs = st.executeQuery("explain select * from course --GEMFIREXD-PROPERTIES withSecondaries=false\n where course_name = ? parameter values ('d')");
      assertTrue(rs.next());
      plan = rs.getString(1);
      getLogger().info("Plan : " + plan);
      plan = StatementPlanDUnit.statement(plan);
      plan = StatementPlanDUnit.tablescan(plan, "25", 1, "COURSE_NAME",
          "APP.COURSE", "HEAP", 0);
      assertTrue(plan == null);
      assertFalse(rs.next());
      rs.close();
    }
    
    {
      PreparedStatement ps = conn.prepareStatement("explain select * from course --GEMFIREXD-PROPERTIES withSecondaries=false\n where course_name = ?");
      ps.setString(1, "d");
      rs = ps.executeQuery();
      assertTrue(rs.next());
      plan = rs.getString(1);
      getLogger().info("Plan : " + plan);
      plan = StatementPlanDUnit.statement(plan);
      plan = StatementPlanDUnit.tablescan(plan, "25", 1, "COURSE_NAME",
          "APP.COURSE", "HEAP", 0);
      assertTrue(plan == null);
      assertFalse(rs.next());
      rs.close();
    }

    {
      rs = st
          .executeQuery("explain as xml select * from course --GEMFIREXD-PROPERTIES withSecondaries=false\n where course_name = ? parameter values ('d') ");
      assertTrue(rs.next());
      plan = rs.getString(1);
      int lines = 0;
      StringTokenizer stz = new StringTokenizer(plan, "\n");
      String tok = null;
      while (stz.hasMoreTokens()) {
        tok = stz.nextToken();
        lines++;
        if (lines == 3) {
          assertEquals("<root>", tok);
        }
        else if (lines == 4) {
          assertEquals("<plan>", tok);
        }
      }
      assertEquals("</root>", tok);
      getLogger().info(lines + " lines of plan : " + plan);
      assertFalse(rs.next());
      assertEquals(20, lines);
      rs.close();
    }

    {
      rs = st
          .executeQuery("explain as xmlfragments select * from course --GEMFIREXD-PROPERTIES withSecondaries=false\n where course_name = ? parameter values ('d')");
      assertTrue(rs.next());
      plan = rs.getString(1);
      int lines = 0;
      StringTokenizer stz = new StringTokenizer(plan, "\n");
      String tok = null;
      while (stz.hasMoreTokens()) {
        tok = stz.nextToken();
        lines++;
        if (lines == 0) {
          assertEquals("<plan>", tok);
        }
      }
      assertEquals("</plan>", tok);
      getLogger().info(lines + " lines of plan : " + plan);
      assertFalse(rs.next());
      assertEquals(16, lines);
      rs.close();
    }

    try {
      st.executeQuery("explain as xmlfragments embed 'vanilla.xsl' select * from course --GEMFIREXD-PROPERTIES withSecondaries=false\n where course_name = ? parameter values ('d') ");
      fail("expected parsing exception");
    }
    catch(SQLException sqle) {
      assertEquals("42X01", sqle.getSQLState());
    }

    {
      rs = st
          .executeQuery("explain as xml embed 'vanilla.xsl' select * from course --GEMFIREXD-PROPERTIES withSecondaries=false\n where course_name = ? parameter values ('d') ");
      assertTrue(rs.next());
      plan = rs.getString(1);
      int lines = 0;
      StringTokenizer stz = new StringTokenizer(plan, "\n");
      String tok = null;
      while (stz.hasMoreTokens()) {
        tok = stz.nextToken();
        lines++;
        if (lines == 1) {
          assertTrue(tok.contains("xml version"));
          assertTrue(tok.contains("encoding="));
          assertTrue(tok.contains("UTF-8"));
        }
        else if (lines == 2) {
          assertTrue(tok.contains("xml-stylesheet"));
          assertTrue(tok.contains("vanilla.xsl"));
        }
        else if (lines == 4) {
          assertEquals("<root>", tok);
        }
        else if (lines == 5) {
          assertEquals("<plan>", tok);
        }
      }
      assertEquals("</root>", tok);
      getLogger().info(lines + " lines of plan : " + plan);
      assertFalse(rs.next());
      assertEquals(21, lines);
      rs.close();
    }
    
  }

  // specifically checks exception unwind condition
  // whereby statementContext cleanup was resulting 
  // into nested exception due to authorization.
  public void test49400_1() throws Exception {
    
    // gets cleaned up in tearDown.
    SystemProcedures.TEST_FAILURE_MODE = true;
    {
      Properties p = new Properties();
      p.setProperty("auth-provider", "builtin");
      p.setProperty("gemfirexd.user.sysONE", "3b6005b975a13d93cb2accec0167d2c12039ff451dee");
      p.setProperty("user", "sysONE");
      p.setProperty("password", "pwdSys");
      Connection eConn = getConnection(p);
      Statement st = eConn.createStatement();
      st.execute("call sys.create_user('DBUSER', 'PWDUSER') ");
      st.execute("create table test_1 (i int)");
      st.execute("insert into test_1 values 1,2,3,4,5,6");
      st.execute("grant select on test_1 to public");
    }
    
    {
      Properties cProps = new Properties();
      cProps.setProperty("user", "DBUSER");
      cProps.setProperty("password", "PWDUSER");
      int netPort = startNetserverAndReturnPort(null);
      Connection nConn = getConnection(cProps);
      //Connection nConn = getNetConnection(netPort, null, cProps);
      Statement st = nConn.createStatement();
      try {
        ResultSet rs = st.executeQuery("explain select * from sysone.test_1");
        fail("expected authorization failed exception....");
      }
      catch(SQLException e) {
        Throwable t = e;
        SQLException sqle;
        do {
          if ((t instanceof SQLException)
              && (sqle = (SQLException)t) != null
              && "42500".equals(sqle.getSQLState())) {
            assertTrue(
                sqle.getMessage(),
                sqle.getMessage()
                    .matches(
                        ".*DBUSER.*does not have INSERT.*permission on table.*SYSSTAT.*SYSXPLAIN.*"));
             break;
          }
        } while ( (t=t.getCause()) != null);
      }
      finally {
        st.close();
      }
    }
  }
  
  public void test49400_2() throws Exception {
    assertFalse(SystemProcedures.TEST_FAILURE_MODE);
    {
      Properties p = new Properties();
      p.setProperty("auth-provider", "builtin");
      p.setProperty("gemfirexd.user.sysONE", "3b6005b975a13d93cb2accec0167d2c12039ff451dee");
      p.setProperty("user", "sysONE");
      p.setProperty("password", "pwdSys");
      Connection eConn = getConnection(p);
      Statement st = eConn.createStatement();
      st.execute("call sys.create_user('DBUSER', 'PWDUSER') ");
      st.execute("create table test_1 (i int)");
      st.execute("insert into test_1 values 1,2,3,4,5,6");
      st.execute("grant select on test_1 to public");
    }
    {
      Properties cProps = new Properties();
      cProps.setProperty("user", "DBUSER");
      cProps.setProperty("password", "PWDUSER");
      Connection eConn = getConnection(cProps);
      int netPort = startNetserverAndReturnPort(null);
      Connection nConn = getNetConnection(netPort, null, cProps);
      for (Connection conn : new Connection[] { nConn, eConn }) {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("explain select * from sysone.test_1");
        assertTrue(rs.next());
        final String plan = rs.getString(1);
        int lines = 0;
        StringTokenizer stz = new StringTokenizer(plan, "\n");
        String tok = null;
        while (stz.hasMoreTokens()) {
          tok = stz.nextToken();
          lines++;
        }
        getLogger().info(lines + " lines of plan : " + plan);
        assertFalse(rs.next());
        assertEquals(4, lines);
        rs.close();
        conn.close();
      }
    }
  }
  
  public void test51502() throws Exception {
    Properties p = new Properties();
    p.setProperty("mcast-port", Integer.toString(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    getConnection(p);
    Connection conn = startNetserverAndGetLocalNetConnection();
    Statement st = conn.createStatement();
    st.execute("drop table if exists course ");
    st.execute("create table course (" + "course_id int, i int, course_name varchar(10), "
        + " primary key(course_id)"
        + ") partition by primary key ");

    st
        .execute("insert into course values (1, 1, 'd'), (2, 2, 'd'), (3, 1, 'd'), (4, 3, 'd'), (5, 1, 'd')");
    
    st
    .execute("insert into course values (11, 1, 'd'), (12, 2, 'd'), (13, 1, 'd'), (14, 3, 'd'), (15, 1, 'd')");
    st
    .execute("insert into course values (21, 1, 'd'), (22, 2, 'd'), (23, 1, 'd'), (24, 3, 'd'), (25, 1, 'd')");
    st
    .execute("insert into course values (31, 1, 'd'), (32, 3, 'd'), (33, 1, 'd'), (34, 3, 'd'), (35, 1, 'd')");
    st
    .execute("insert into course values (41, 1, 'd'), (42, 4, 'd'), (43, 1, 'd'), (44, 3, 'd'), (45, 1, 'd')");
    
    st.execute("call sys.set_global_statement_statistics('true','true')");
    st.execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
    st.execute("call SYSCS_UTIL.SET_EXPLAIN_CONNECTION(1)");
    
    final boolean[] queryPlanGenerationStatus = new boolean[1]; 
    GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter() {
          private static final long serialVersionUID = 1L;
          @Override
          public void afterQueryPlanGeneration() {
            queryPlanGenerationStatus[0] = true;
          }
          
        });
    
    final ResultSet r = st.executeQuery("select count(1), course_name from course group by course_name");
    while(r.next()) {
      assertEquals(25, r.getInt(1));
      assertEquals("d", r.getString(2));
    }
    
    r.close();
    assertTrue("Query Plan Generation should have been successfull",
        queryPlanGenerationStatus[0]);
  }
  
  public void test51355()  throws Exception {
    getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table t1 (col1 int, col2 int, col3 varchar(100))");
    stmt.execute("insert into t1 values (2, 2, 'abcdefghijklmnopqrstABCDEFGH100')");
    ResultSet r = stmt.executeQuery("select col1, col2, cast (substr(substr(col3, 10), 20) as int) from t1");
    while(r.next()) {
      assertEquals(100, r.getInt(3));
    }
    stmt.execute("delete from t1");
    stmt.execute("insert into t1 values (1, 1, 'abcdefghijklmnopqrstuv')");
    try {
      r = stmt.executeQuery("select col1, col2, cast (substr(substr(col3, 10), 5) as int) from t1");
      r.next();
      fail("supposed to fail with NumberFormatException");
    } catch(SQLException e) {
      if (!"22018".equals(e.getSQLState())) {
        throw e;
      }
    }
  }
  
  public void test51194() throws Exception {
    getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    final Statement stmt = conn.createStatement();
    
    stmt.execute("create table \"ro ot\".\"base Table\"( \"my id\" varchar(100) primary key)" );
    
    stmt.execute("create synonym \"synschema1\".\"first synonym\" for \"ro ot\".\"base Table\" " );
    stmt.execute("create synonym \"syn Schema2\".\"second synonym\" for \"ro ot\".\"base Table\" " );

    //create a synonym to a fake table. This won't list via getColumns
    stmt.execute("create synonym \"fake table\".\"but real synonym\" for \"ro ot\".\"base table\" " );
    
    final DatabaseMetaData dmd = conn.getMetaData();
    
    ResultSet colM = dmd.getColumns(null, "ro ot", "base Table", null);
    assertTrue(colM.next());
    assertEquals("ro ot", colM.getString(2));
    assertEquals("base Table", colM.getString(3));
    assertEquals("my id", colM.getString(4));
    assertFalse(colM.next());
    
    colM = dmd.getColumns(null, "synschema1", "first synonym", null);
    assertTrue(colM.next());
    assertEquals("synschema1", colM.getString(2));
    assertEquals("first synonym", colM.getString(3));
    assertEquals("my id", colM.getString(4));
    assertFalse(colM.next());
    
    colM = dmd.getColumns(null, "syn Schema2", "second synonym", null);
    assertTrue(colM.next());
    assertEquals("syn Schema2", colM.getString(2));
    assertEquals("second synonym", colM.getString(3));
    assertEquals("my id", colM.getString(4));
    assertFalse(colM.next());
    
    colM = dmd.getColumns(null, "fake table", "but real synonym", null);
    assertFalse(colM.next());
  }
}
