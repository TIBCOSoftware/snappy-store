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
//
//  ViewTest.java
//
//  Created by Eric Zoerner on 2009-10-08.

package com.pivotal.gemfirexd.jdbc;

import java.sql.*;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.pivotal.gemfirexd.ToursDBUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;

import org.apache.derbyTesting.junit.JDBC;


public class ViewTest extends JdbcTestBase {
  
  public static void main(String[] args) {
    TestRunner.run(new TestSuite(ViewTest.class));
  }
  
  public ViewTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  public void testCreateView() throws SQLException {
    Connection conn = getConnection();
    ToursDBUtil.createAndLoadToursDB(conn);
    
    Statement s = conn.createStatement();
    s.executeUpdate("CREATE VIEW ese "
                    + "AS SELECT city_name, country, language "
                    + "FROM cities WHERE language LIKE '%ese'");
    
    ResultSet rs = s.executeQuery("SELECT * FROM ese");
    assertEquals(7, JDBC.assertDrainResults(rs));
    
    conn.close();
  }

  public void testDropTableWithView_43720() throws SQLException {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    stmt.execute("create table temp (x int) partition by column (x)");
    assertEquals(3, stmt.executeUpdate("insert into temp values "
        + "(1), (2), (3)"));
    stmt.execute("create view temp2 as select * from temp where x > 2");
    assertEquals(1, stmt.executeUpdate("insert into temp values (4)"));
    try {
      stmt.execute("drop table temp");
      fail("expected an exception");
    } catch (SQLException sqle) {
      if (!"X0Y23".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    assertEquals(1, stmt.executeUpdate("insert into temp values (5)"));
  }

  public void testJoins() throws Exception {
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();
    // create tables and views
    stmt.execute("create table t1 (i1 int, j1 int)");
    stmt.execute("create table t2 (i2 int, j2 int)");
    stmt.execute("create table t3 (a3 int, b3 int)");
    stmt.execute("create table t4 (a4 int, b4 int)");
    stmt.execute("create view V1 as select distinct T1.i1 i, T2.j2 j "
        + "from T1, T2 where T1.i1 = T2.i2");
    stmt.execute("create view V2 as select distinct T3.a3 a, T4.b4 b "
        + "from T3, T4 where T3.a3 = T4.a4");
    stmt.execute("create view V3 as select T1.i1 i, T2.j2 j "
        + "from T1, T2 where T1.i1 = T2.i2");
    stmt.execute("create view V4 as select T3.a3 a, T4.b4 b "
        + "from T3, T4 where T3.a3 = T4.a4");

    // some inserts
    for (int i = 1; i <= 5; i++) {
      stmt.execute("insert into t1 values (" + i + ", " + i + ')');
      stmt.execute("insert into t2 values (" + i + ", " + i + ')');
      if (i % 2 == 1) {
        stmt.execute("insert into t3 values (" + i + ", " + i + ')');
        stmt.execute("insert into t4 values (" + i + ", " + i + ')');
      }
    }

    // first no joins
    ResultSet rs = stmt.executeQuery("select * from V3 where "
        + "V3.i in (1,2,3,4,5) order by V3.i");
    for (int i = 1; i <= 5; i++) {
      assertTrue(rs.next());
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
    }
    assertFalse(rs.next());

    // joins among views
    rs = stmt.executeQuery("select * from V3, V4 where "
        + "V3.j = V4.b and V3.i in (1,2,3,4,5) order by V3.i");
    for (int i = 1; i <= 5; i += 2) {
      assertTrue(rs.next());
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      assertEquals(i, rs.getInt(3));
      assertEquals(i, rs.getInt(4));
    }
    assertFalse(rs.next());
    rs = stmt.executeQuery("select * from V1, V2 where "
        + "V1.j = V2.b and V1.i in (1,2,3,4,5) order by V1.i");
    for (int i = 1; i <= 5; i += 2) {
      assertTrue(rs.next());
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      assertEquals(i, rs.getInt(3));
      assertEquals(i, rs.getInt(4));
    }
    assertFalse(rs.next());

    // now check for the case reported on forums
    // (http://communities.vmware.com/message/1890239)
    final String baseDir = getResourcesDir() + "/lib/views/";
    GemFireXDUtils.executeSQLScripts(conn, new String[] {
        baseDir + "create-objects.sql",
        baseDir + "program-unit-xwalk-load.sql",
        baseDir + "selected-programs-load.sql" }, false, getLogger(), null, null, false);
    rs = stmt.executeQuery("select count(*) from ERASV2.VW_APPLICANT_PROGRAM");
    assertTrue(rs.next());
    assertEquals(967, rs.getInt(1));
    assertFalse(rs.next());
    rs = stmt.executeQuery("SELECT * FROM ERASV2.VW_APPLICANT_PROGRAM "
        + "WHERE UNIT_ID = '014035214850'");
    for (int i = 1; i <= 967; i++) {
      assertTrue(rs.next());
      assertEquals("014035214850", rs.getString(2));
      assertEquals(22882, rs.getInt(3));
    }
    assertFalse(rs.next());
  }

  public void testColocatedJoins_44616() throws SQLException {
    // force creation of GFE prepared statements
    DMLQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    final Connection conn = getConnection();
    final Statement stmt = conn.createStatement();

    // create the tables and view
    stmt.execute("create table p_test1 (idval int, dataval1 varchar(20)) "
        + "partition by column (idval)");
    stmt.execute("create table p_test2 (idval int, dataval2 varchar(20)) "
        + "partition by column (idval) colocate with (p_test1)");
    stmt.execute("create table p_test3 (idval int, dataval3 varchar(20)) "
        + "partition by column (idval) colocate with (p_test1)");
    stmt.execute("create view p_view1_2 (idval, dataval1, dataval2) as "
        + "select t1.idval, t1.dataval1, t2.dataval2 from "
        + "p_test1 t1, p_test2 t2 where t1.idval = t2.idval");

    // insert some data
    for (int id = 1; id <= 20; id++) {
      stmt.execute("insert into p_test1 values (" + id + ", 'd1_" + id + "')");
      stmt.execute("insert into p_test2 values (" + id + ", 'd2_" + id + "')");
      stmt.execute("insert into p_test3 values (" + id + ", 'd3_" + id + "')");
    }

    ResultSet rs = stmt.executeQuery("select v1.idval, v1.dataval1, "
        + "v1.dataval2, t3.dataval3 from p_view1_2 v1, p_test3 t3 "
        + "where v1.idval = t3.idval order by v1.idval");
    for (int id = 1; id <= 20; id++) {
      assertTrue(rs.next());
      assertEquals(id, rs.getInt(1));
    }
    assertFalse(rs.next());
  }

  public void testBug_50173() throws SQLException {
    try {
      System.setProperty(GfxdConstants.GFXD_DISABLE_COLUMN_ELIMINATION, "true");
      final Connection conn = getConnection();
      final Statement st = conn.createStatement();

      // create the tables and view
      st.execute("create schema trade");

      // Create table
      st.execute("create table trade.A "
          + "( subscriber_id varchar(64) not null, " + "cell_id varchar(30), "
          + "time_from timestamp not null, " + "did int) " + "replicate");
      st.execute("create table trade.B " + "( cell_id varchar(8) not null, "
          + "valid_until timestamp null, " + "nl varchar(30) null ) "
          + "replicate");

      st.execute("create unique index trade.idx_a_pk on trade.a ( subscriber_id, cell_id, time_from )");
      st.execute("create index trade.idx_a_subscriber_id on trade.a ( subscriber_id )");
      st.execute("create index trade.idx_a_cell_id on trade.a ( cell_id )");
      st.execute("create unique index trade.idx_b_cell_id on trade.b ( cell_id, valid_until )");

      String[] securities = { "IBM", "MOT", "INTC", "TEK", "AMD", "CSCO",
          "DELL", "HP", "SMALL1", "SMALL2", "IBM2", "MOT2", "INTC2", "TEK2",
          "AMD2", "CSCO2", "DELL2", "HP2", "SMALL12", "SMALL22", "IBM3",
          "MOT3", "INTC3", "TEK3", "AMD3", "CSCO3", "DELL3", "HP3", "SMALL13",
          "SMALL23" };
      { // insert -1
        PreparedStatement psInsert = conn
            .prepareStatement("insert into trade.A values (?, ?, ?, ?)");
        for (int i = 0; i < 30; i++) {
          psInsert.setString(1, securities[i % 30]);
          psInsert.setString(2, securities[i % 30]);
          psInsert.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
          psInsert.setInt(4, i + 1);
          psInsert.executeUpdate();
        }
      }

      { // insert -2
        PreparedStatement psInsert = conn
            .prepareStatement("insert into trade.B values (?, ?, ?)");
        for (int i = 0; i < 30; i++) {
          psInsert.setString(1, securities[i % 30]);
          psInsert.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
          psInsert.setString(3, securities[i % 30]);
          psInsert.executeUpdate();
        }
      }

      {
        String query = "select innerQuery.nl from " + "(select b.valid_until, "
            + "b.nl from trade.A a " + "LEFT JOIN trade.B b "
            + "ON a.cell_id=b.cell_id) "
            + "innerQuery group by innerQuery.nl; ";
        PreparedStatement pst = conn.prepareStatement(query);
        {
          ResultSet r = pst.executeQuery();
          int count = 0;
          while (r.next()) {
            count++;
          }
          assertEquals(30, count);
          r.close();
        }
      }
    } finally {
      System.clearProperty(GfxdConstants.GFXD_DISABLE_COLUMN_ELIMINATION);
    }
  }
}
