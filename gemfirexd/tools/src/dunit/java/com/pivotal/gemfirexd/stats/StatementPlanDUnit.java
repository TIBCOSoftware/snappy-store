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
package com.pivotal.gemfirexd.stats;

import java.io.BufferedReader;
import java.io.Serializable;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Pattern;

import com.gemstone.gemfire.internal.SharedLibrary;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.XPLAINDistPropsDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINResultSetDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINResultSetTimingsDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINScanPropsDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINSortPropsDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINStatementDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.tools.planexporter.AccessDistributedSystem;
import com.pivotal.gemfirexd.tools.planexporter.StatisticsCollectionObserver;
import com.pivotal.gemfirexd.tools.planexporter.TreeNode;
import com.pivotal.gemfirexd.tools.utils.ExecutionPlanUtils;
import io.snappydata.test.dunit.SerializableRunnable;
import org.apache.log4j.Logger;
import org.w3c.dom.Element;

/**
 * @author soubhikc
 * 
 */
@SuppressWarnings("serial")
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.JVM)
public class StatementPlanDUnit extends DistributedSQLTestBase {

  public StatementPlanDUnit(String name) {
    super(name);
  }
  
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty(SharedLibrary.LOADLIBRARY_DEBUG_PROPERTY, "true");
  }
  
  @Override
  public void tearDown2() throws Exception {
    StatisticsCollectionObserver.setInstance(null);
    super.tearDown2();
    System.clearProperty(SharedLibrary.LOADLIBRARY_DEBUG_PROPERTY);
  }

  public void setNativeNanoTimer() throws SQLException {
  }

  final static boolean isLocalnode[] = new boolean[] { false };

  final static long TotalRows = 100;

  static class PlanStatisticsCollectionObserver extends StatisticsCollectionObserver {
    private transient boolean isRootEntryFound = false;

    private transient TreeNode cn;

    static class PlanArtifacts {
      
      List<XPLAINResultSetDescriptor> orsets = new ArrayList<XPLAINResultSetDescriptor>();

      List<XPLAINResultSetTimingsDescriptor> orsetsTimings = new ArrayList<XPLAINResultSetTimingsDescriptor>();

      List<XPLAINScanPropsDescriptor> oscanProps = new ArrayList<XPLAINScanPropsDescriptor>();

      List<XPLAINSortPropsDescriptor> osortProps = new ArrayList<XPLAINSortPropsDescriptor>();

      List<XPLAINDistPropsDescriptor> odistProps = new ArrayList<XPLAINDistPropsDescriptor>();
      
      public void reset() {
        orsets.clear();
        orsetsTimings.clear();
        oscanProps.clear();
        osortProps.clear();
        odistProps.clear();
      }
    }
    
    transient static ThreadLocal<PlanArtifacts> plans = new ThreadLocal<PlanArtifacts>();

    @Override
    public void reset() {
      plans.get().reset();
    }

    @Override
    public <T extends Serializable> void processSelectMessage(
        XPLAINStatementDescriptor stmt, ArrayList<XPLAINResultSetDescriptor> rsets,
        List<XPLAINResultSetTimingsDescriptor> rsetsTimings,
        List<XPLAINScanPropsDescriptor> scanProps,
        List<XPLAINSortPropsDescriptor> sortProps,
        List<XPLAINDistPropsDescriptor> distProps) {
      assertTrue(stmt != null);

      PlanArtifacts a = plans.get();
      if(a == null) {
        a = new PlanArtifacts();
        plans.set(a);
      }
      
      assertTrue(a.orsets.isEmpty());
      a.orsets.addAll(rsets);
      a.orsetsTimings.addAll(rsetsTimings);
      a.oscanProps.addAll(scanProps);
      a.osortProps.addAll(sortProps);
      a.odistProps.addAll(distProps);
    }

    @Override
    public void processedResultSetDescriptor(
        final XPLAINResultSetDescriptor rdesc) {
      assertTrue(plans.get().orsets.remove(rdesc));
    }

    @Override
    public void processedResultSetTimingDescriptor(
        final XPLAINResultSetTimingsDescriptor rdescT) {

      assertTrue(plans.get().orsetsTimings.remove(rdescT));
    }

    @Override
    public void processedScanPropsDescriptor(
        final XPLAINScanPropsDescriptor scanP) {

      assertTrue(plans.get().oscanProps.remove(scanP));
    }

    @Override
    public void processedSortPropsDescriptor(
        final XPLAINSortPropsDescriptor sortP) {
      assertTrue(plans.get().osortProps.remove(sortP));
    }

    @Override
    public void processedDistPropsDescriptor(
        final XPLAINDistPropsDescriptor distP) {
      assertTrue(plans.get().odistProps.remove(distP));
    }

    @Override
    public void end() {
      PlanArtifacts a = plans.get();
      if(a == null) {
        return;
      }
      assertTrue(a.orsets.toString(), a.orsets.isEmpty());
      assertTrue(a.orsetsTimings.toString(), a.orsetsTimings.isEmpty());
      assertTrue(a.oscanProps.isEmpty());
      assertTrue(a.osortProps.isEmpty());
      assertTrue(a.odistProps.isEmpty());
    }

    @Override
    public void observeXMLData(TreeNode[] dataArr) {

      String text;
      String n;
      assertTrue("Atleast few entries should be found on the datanode.",
          dataArr.length > 0);
      for (TreeNode data : dataArr) {

        cn = data;
        getGlobalLogger().info("checking " + cn);

        text = data.getId();
        assertTrue(text != null);
        String id = text.split("\"")[1];
        assertTrue(id.split("-").length == 5);

        text = data.getParent();
        if (text == null) {
          assertTrue(emsg("duplicate root entry"), !isRootEntryFound);
          isRootEntryFound = true;
        }

        text = data.getNodeType();
        assertTrue(emsg("node_type is null"), text != null);
        n = text.split("\"")[1];

        // remote won't have complete picture, so just check > 0
        if (!isLocalnode[0]
            && (XPLAINUtil.OP_RESULT_HOLDER.equals(n) || !AccessDistributedSystem
                .isMessagingEntry(n))) {
          text = data.getReturnedRows();
          assertTrue(emsg("returned_rows is null"), text != null);
          final int ret_rows = Integer.valueOf(text.split("\"")[1]).intValue();
          assertTrue(emsg(ret_rows + " <= 0 "), ret_rows > 0);
        }
        // remote node ResultHolder will have this.
        else if (isLocalnode[0] && !AccessDistributedSystem.isMessagingEntry(n)) {
          text = data.getReturnedRows();
          assertTrue(emsg("returned_rows is null"), text != null);
          final int ret_rows = Integer.valueOf(text.split("\"")[1]).intValue();
          assertTrue(emsg(ret_rows + " != " + TotalRows), ret_rows == TotalRows);
        }

        text = data.getExecTime();
        assertTrue(text != null);

        // Execute time has the format
        //  execute_time="xxx.xxx ms" and is in milliseconds
        // Split by quotes, spaces and alphanumerics
        String[] split = text.split("[\"\\s]");
        if (split.length < 2) {
          fail("got exec_time text=" + text + " with split="
              + java.util.Arrays.toString(split) + " unexpected len="
              + split.length + " < 2 for " + data);
        }
        final double exec_time = Double.valueOf(split[1]).doubleValue();
        assertTrue(emsg("exec_time=" + exec_time), exec_time > 0);

        if (AccessDistributedSystem.isMessagingEntry(n)) {
          // remote node resultHolder won't have member_node.
          if (isLocalnode[0] || !XPLAINUtil.OP_RESULT_HOLDER.equals(n)) {
            text = data.getMemberNode();
            assertTrue(text != null);
            assertTrue(emsg("member_node is null"), text.split("\"")[1]
                .length() > 0);
          }
        }
      }

    }

    private String emsg(String a) {
      return a + " for [" + cn + "] in "
          + Misc.getDistributedSystem().getMemberId();
    }
  };

  final StatisticsCollectionObserver checker = new PlanStatisticsCollectionObserver();
  
  /**
   * TODO: Enable once #51271 is fixed.
   */
  public void DISABLED_51271_testSingleNodePlanGeneration() throws Exception {
    Logger log = getLogWriter();
    startVMs(0, 4, 0, null, null);
    startVMs(1, 0, 0, null, null);

    setNativeNanoTimer();
    final Properties p = new Properties();
    p.setProperty("log-level", getLogLevel());
    final Thread[] parallelEx = new Thread[10];
    try {
      {
        final int netPort = startNetworkServer(1, null, p);
        
        checkLoadLib(getTestName());

        Connection conn = TestUtil.getNetConnection(netPort, null, p);
        
        final ResultSet rs = conn.getMetaData().getTables(null, null,
            "course".toUpperCase(), new String[] { "ROW TABLE" });
        final boolean found = rs.next();
        rs.close();

        Statement st = conn.createStatement();

        if (found) {
          st.execute("drop table if exists course ");
        }
        st.execute("create table course ("
            + "course_id int, course_name varchar(2048), "
            + " primary key(course_id)" + ") ");

        String sql = "insert into course values( ";
        StringBuilder ins = new StringBuilder(sql);
        for (int i = 0; i < TotalRows; i++) {
          ins.append(i).append(", '");
          ins.append(TestUtil.numstr(i)).append("' )");
          if ((i + 1) % 100 == 0) {
            st.execute(ins.toString());
            log.info((i + 1) + "th insert done .. ");
            ins = new StringBuilder(sql);
          }
          else {
            ins.append(", ( ");
          }
        }
        st.close();

        log.info("configuring plan checker ");

        isLocalnode[0] = true;
        invokeInEveryVM(new SerializableRunnable("setting plan checker") {
          @Override
          public void run() {
            StatisticsCollectionObserver.setInstance(checker);
          }
        });

        log.info("preparing for plan generation ");

        for (int ti = parallelEx.length - 1; ti >= 0; ti--) {

          parallelEx[ti] = new Thread(new Runnable() {

            @Override
            public void run() {
              Statement st;
              PreparedStatement ps;
              try {
                final Connection conn = TestUtil.getNetConnection(netPort, null, p);

                st = conn.createStatement();
                ps = conn
                    .prepareStatement("select * from course where course_name like '%' ");
                st.execute("call sys.SET_TRACE_FLAG('TracePlanGeneration', 'true')");
                st.execute("call syscs_util.set_statistics_timing(1)");
                st.execute("call syscs_util.set_explain_connection(1)");
                ResultSet r = ps.executeQuery();
                while (r.next())
                  ;
                r.close();
                st.execute("call syscs_util.set_explain_connection(0)");
                st.execute("call syscs_util.set_statistics_timing(0)");
                st.execute("call sys.SET_TRACE_FLAG('TracePlanGeneration', 'false')");
              } catch (SQLException e) {
                fail("UnExpected Exception " + e, e);
              }
            }
          }, " Execution Thread - " + ti);
        }

        for (int ti = parallelEx.length - 1; ti >= 0; ti--) {
          parallelEx[ti].start();
        }

        for (int ti = parallelEx.length - 1; ti >= 0; ti--) {
          parallelEx[ti].join();
        }
        
        conn.close();
      }

      {
        // start the controller in Embedded mode now.
        Connection eConn = TestUtil.getConnection(p);
        Statement stmt = eConn.createStatement();
        ArrayList<Integer> expectedPlans = new ArrayList<Integer>();
        expectedPlans.add(38);

        ResultSet r = stmt
            .executeQuery("select STMT_ID, STMT_TEXT from SYS.STATEMENTPLANS");
        log.info("Extracting query plans");
        int numQueryPlans = 0;
        while (r.next()) {
          ++numQueryPlans;
          final String stmt_id = r.getString("STMT_ID");
          String stmtInfo = "stmt_id = " + stmt_id + " statement = "
              + r.getString("STMT_TEXT");

          ExecutionPlanUtils plan = new ExecutionPlanUtils(eConn, stmt_id, null, true);

          Vector<String> v = new Vector<String>();
          StringTokenizer stz = new StringTokenizer(new String(plan
              .getPlanAsText(null)), "\n");
          while (stz.hasMoreTokens()) {
            v.addElement(stz.nextToken());
          }
          int l = v.size();
          log.info("Received query plan with " + l + " lines ");
          for (int i = 0; i < l; i++)
            log.info(v.elementAt(i));

          for (Element e : plan.getPlanAsXML()) {
            log.info(e != null ? e.toString() : "null");
          }

          log.info("Query plan...\n" + stmtInfo + "\n"
              + String.valueOf(plan.getPlanAsText(null)).trim());
          assertEquals(38, l);
        }
        assertEquals(parallelEx.length, numQueryPlans);
        log.info("Total query plans: " + numQueryPlans);
      }

    } finally {
      invokeInEveryVM(new SerializableRunnable("clearing plan checker") {
        @Override
        public void run() {
          StatisticsCollectionObserver.setInstance(null);
        }
      });
      StatisticsCollectionObserver.setInstance(null);
    }
  }

  public void testExecutionPlanGeneration() throws Exception {
    Properties p = new Properties();
    p.setProperty("log-level", getLogLevel());

    startServerVMs(4, 0, null, p);

    startClientVMs(1, 0, null, p);

    setNativeNanoTimer();
    Connection conn = TestUtil.getConnection(p);
    
    checkLoadLib(getTestName());

    final ResultSet rs = conn.getMetaData().getTables((String)null, null,
        "course".toUpperCase(), new String[] { "TABLE" });
    final boolean found = rs.next();
    rs.close();

    Statement st = conn.createStatement();

    if (found) {
      st.execute("drop table if exists course");
    }
    st.execute("create table course ("
        + "course_id int, course_name varchar(2048), "
        + " primary key(course_id)" + ") ");

    String sql = "insert into course values( ";
    StringBuilder ins = new StringBuilder(sql);
    Logger log = getLogWriter();
    for (int i = 0; i < TotalRows; i++) {
      ins.append(i).append(", '");
      ins.append(TestUtil.numstr(i)).append("' )");
      if ((i + 1) % 100 == 0) {
        st.execute(ins.toString());
        log.info((i + 1) + "th insert done .. ");
        ins = new StringBuilder(sql);
      }
      else {
        ins.append(", ( ");
      }
    }

    log.info("preparing for plan generation ");

    conn.createStatement().execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
    String q = "select * from course where course_name like '%' ";

    log.info("configuring plan checker ");

    invokeInEveryVM(new SerializableRunnable("setting plan checker") {
      @Override
      public void run() {
        StatisticsCollectionObserver.setInstance(checker);
      }
    });

    isLocalnode[0] = true;
    StatisticsCollectionObserver.setInstance(checker);

    try {
      log.info("about to access plan ");

      ExecutionPlanUtils plan = new ExecutionPlanUtils(conn, q, null, true);

      Vector<String> v = new Vector<String>();
      StringTokenizer stz = new StringTokenizer(new String(plan
          .getPlanAsText(null)), "\n");
      while (stz.hasMoreTokens()) {
        v.addElement(stz.nextToken());
      }
      int l = v.size();
      log.info("Received query plan with " + l + " lines ");
      for (int i = 0; i < l; i++)
        log.info(v.elementAt(i));

      for (Element e : plan.getPlanAsXML()) {
        log.info(e != null ? e.toString() : "null");
      }

      assertTrue(l == 42 || l == 43);

    } finally {
      invokeInEveryVM(new SerializableRunnable("clearing plan checker") {
        @Override
        public void run() {
          StatisticsCollectionObserver.setInstance(null);
        }
      });
      StatisticsCollectionObserver.setInstance(null);
    }

  }

  private boolean allExecutionsDone = false;

  public void testExecutionPlanGenerationAcrossConnections()
      throws Exception {
    final Properties p = new Properties();
    p.setProperty("log-level", getLogLevel());

    startServerVMs(4, 0, null, p);

    startClientVMs(1, 0, null, p);

    setNativeNanoTimer();
    checkLoadLib(getTestName());
    
    Connection conn = TestUtil.getConnection(p);
    final ResultSet rs = conn.getMetaData().getTables((String)null, null,
        "course".toUpperCase(), new String[] { "TABLE" });
    final boolean found = rs.next();
    rs.close();

    Statement st = conn.createStatement();

    if (found) {
      st.execute("drop table if exists course ");
    }
    st.execute("create table course ("
        + "course_id int, course_name varchar(2048), "
        + " primary key(course_id)" + ") ");

    String sql = "insert into course values( ";
    StringBuilder ins = new StringBuilder(sql);
    Logger log = getLogWriter();
    final long TotalRows = 100;
    for (int i = 0; i < TotalRows; i++) {
      ins.append(i).append(", '");
      ins.append(TestUtil.numstr(i)).append("' )");
      if ((i + 1) % 100 == 0) {
        st.execute(ins.toString());
        log.info((i + 1) + "th insert done .. ");
        ins = new StringBuilder(sql);
      }
      else {
        ins.append(", ( ");
      }
    }

    log.info("preparing for plan generation");
    {
      SerializableRunnable c = new SerializableRunnable("acquire connection") {

        @Override
        public void run() {
          try {
            st_conn = TestUtil.getConnection(p);
            assertTrue(st_conn != null);
            st_conn.createStatement().execute(
                "call syscs_util.set_explain_connection(1)");
            assertTrue(GemFireXDQueryObserverHolder.getInstance() != null);

            st_conn.createStatement().execute(
                "call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
            StatisticsCollectionObserver.setInstance(checker);
          } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Exception is acquiring connection ", e);
          }
        }
      };

      invokeInEveryVM(c);

      isLocalnode[0] = true;
      c.run();
    }

    log.info("Executing query ");
    {
      SerializableRunnable r = new SerializableRunnable("execute query 1") {

        @Override
        public void run() {
          try {
            assertTrue(st_conn != null);
            assertTrue(((EmbedConnection)st_conn).getLanguageConnection()
                .getRunTimeStatisticsMode());
            assertTrue(((EmbedConnection)st_conn).getLanguageConnection()
                .explainConnection());
            assertTrue(GemFireXDQueryObserverHolder.getInstance() != null);
            ResultSet rs = st_conn
                .createStatement()
                .executeQuery(
                    "select course_name , course_id from course group by course_name, course_id order by course_id ");
            while (rs.next()) {
              // getLogWriter().info(
              // "id=" + rs.getInt(2) + " name=" + rs.getString(1));
            }
            rs.close();
            getLogWriter().info(
                "query plan captured for "
                    + ((EmbedResultSet)rs).getSourceResultSet()
                        .getExecutionPlanID().toString());
          } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Exception is running queries ", e);
          } finally {
            try {
              if (!st_conn.isClosed())
                st_conn.createStatement().execute(
                    "call syscs_util.set_explain_connection(0)");
            } catch (SQLException e) {
              throw new RuntimeException(
                  "Exception is switching off explain connection ", e);
            } finally {
              try {
                st_conn.close();
              } catch (SQLException e) {
                throw new RuntimeException("Connection close exception ", e);
              }
              st_conn = null;
            }
          }

        }
      };

      invokeInEveryVM(r);

      r.run();
    }

    log.info("Extracting plan ");
    {
      SerializableRunnable r2 = new SerializableRunnable() {

        @Override
        public void run() {
          try {
            Connection conn = TestUtil.getConnection(p);
            ResultSet st_id = conn.createStatement().executeQuery(
                "select STMT_ID, ORIGIN_MEMBER_ID from sys.statementplans ");

            while (st_id.next()) {
              getLogWriter().info(
                  "Finding query plan for " + st_id.getString(1)
                      + " originated in " + st_id.getString(2));
              ExecutionPlanUtils plan = new ExecutionPlanUtils(conn, st_id
                  .getString(1), null, true);
              Vector<String> v = new Vector<String>();
              StringTokenizer stz = new StringTokenizer(new String(plan
                  .getPlanAsText(null)), "\n");
              while (stz.hasMoreTokens()) {
                v.addElement(stz.nextToken());
              }
              int l = v.size();
              getLogWriter().info("Received query plan with " + l + " lines ");
              for (int i = 0; i < l; i++)
                getLogWriter().info(v.elementAt(i));

              for (Element e : plan.getPlanAsXML()) {
                getLogWriter().info(e != null ? e.toString() : "null");
              }
            }
          } catch (SQLException e) {
            e.printStackTrace();
          } finally {
            StatisticsCollectionObserver.setInstance(null);
            allExecutionsDone = true;
          }
        }
      };

      invokeInEveryVM(r2);
      r2.run();
    }

    log.info("Checking all activity completion ");
    {
      SerializableRunnable checkFinish = new SerializableRunnable() {

        @Override
        public void run() {
          while (allExecutionsDone == false) {
            getLogWriter().info("Waiting for this VM to finish its work ... ");
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
        }

      };
      invokeInEveryVM(checkFinish);
      checkFinish.run();
    }
  }

  public void testBug43228() throws Exception {
    startVMs(1, 3);
    checkLoadLib(getTestName());
    Connection conn = null;
    Statement st = null;
    try {
      Properties cp = new Properties();
      cp.setProperty("log-level", getLogLevel());
      setNativeNanoTimer();
      conn = TestUtil.getConnection(cp);
      st = conn.createStatement();

      if (GemFireXDUtils.hasTable(conn, "test_globalindex_plan_check")) {
        st.execute("drop table test_globalindex_plan_check");
      }

      st.execute("create table test_globalindex_plan_check "
          + "(col1 int primary key, col2 varchar(100), col3 int ) "
          + "partition by column (col2) ");

      st
          .execute("insert into test_globalindex_plan_check values (1, 'one',  0), (2 , 'two',  0), (3, 'three',  0) ");
      st
          .execute("insert into test_globalindex_plan_check values (11, 'one', 0), (12 , 'two', 0), (13, 'three', 0) ");
      st
          .execute("insert into test_globalindex_plan_check values (21, 'one', 0), (22 , 'two', 0), (23, 'three', 0) ");
      st
          .execute("insert into test_globalindex_plan_check values (31, 'one', 0), (32 , 'two', 0), (33, 'three', 0) ");

      String query = "update test_globalindex_plan_check set col3 = 1 where col1 in (11, 21, 31) ";
      ExecutionPlanUtils planner = new ExecutionPlanUtils(conn, query, null, true);
      String p = new String(planner.getPlanAsText(null));
      getLogWriter().info("plan = " + p);
    } finally {
      if (st != null && GemFireXDUtils.hasTable(
          conn, "test_globalindex_plan_check")) {
        st.execute("drop table test_globalindex_plan_check");
        st.close();
        conn.close();
      }
    }
  }

  public void testBug43219_VTITablesRouting() throws Exception {
    startVMs(1, 3);
    checkLoadLib(getTestName());
    setNativeNanoTimer();
    Properties cp = new Properties();
    cp.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(cp);

    Statement st = conn.createStatement();

    if (GemFireXDUtils.hasTable(conn, "countries")) {
      st.execute("drop table countries");
    }

    st
        .execute("CREATE TABLE COUNTRIES ( COUNTRY VARCHAR(26) NOT NULL, COUNTRY_ISO_CODE CHAR(2) NOT NULL , REGION VARCHAR(26) ) REPLICATE");

    String query = "select t.tablename, t.datapolicy, m.hostdata, m.id from sys.systables t , sys.members m "
        + " where t.tablename = 'COUNTRIES'";

    ExecutionPlanUtils planner = new ExecutionPlanUtils(conn, query, null, true);

    getLogWriter().info(
        "received plan : " + new String(planner.getPlanAsText(null)));
  }

  public void testSecondaryBucketsVTIQuery() throws Exception {
    startVMs(1, 2);
    checkLoadLib(getTestName());
    setNativeNanoTimer();
    
    try {

      Properties cp = new Properties();
      cp.setProperty("log-level", getLogLevel());
      Connection conn = TestUtil.getConnection(cp);

      Statement st = conn.createStatement();

      st.execute("create table course (" + "course_id int, i int, "
          + " primary key(course_id)"
          + ") partition by column (i) redundancy 1");

      st.execute("insert into course values (1, 1), (2, 2), "
          + "(3, 1), (4, 3), (5, 1)");

      ResultSet r = st.executeQuery("select dsid() , count(i) from course, "
          + "sys.members m --gemfirexd-properties withSecondaries = true \n "
          + "group by dsid()");

      assertTrue(r.next());
      assertEquals(5, r.getInt(2));
      assertTrue(r.next());
      assertEquals(5, r.getInt(2));
      assertFalse(r.next());

      r = st.executeQuery("select dsid(), count(*) from course, "
          + "sys.members m --gemfirexd-properties withSecondaries = true \n "
          + "group by dsid()");

      assertTrue(r.next());
      assertEquals(5, r.getInt(2));
      assertTrue(r.next());
      assertEquals(5, r.getInt(2));
      assertFalse(r.next());

      r = st.executeQuery("select count(*), dsid() from course, "
          + "sys.members m --gemfirexd-properties withSecondaries = true \n "
          + "group by dsid()");

      assertTrue(r.next());
      assertEquals(5, r.getInt(1));
      assertTrue(r.next());
      assertEquals(5, r.getInt(1));
      assertFalse(r.next());

      r = st.executeQuery("select dsid(), count(i) from course");

      assertTrue(r.next());
      assertEquals(5, r.getInt(2));
      assertFalse(r.next());

      r = st.executeQuery("select dsid(), count(*) from course");

      assertTrue(r.next());
      assertEquals(5, r.getInt(2));
      assertFalse(r.next());

      r = st.executeQuery("select count(*), dsid() from course");

      assertTrue(r.next());
      assertEquals(5, r.getInt(1));
      assertFalse(r.next());

      r = st.executeQuery("select dsid() , count(i) from course, "
          + "sys.members m --gemfirexd-properties withSecondaries = false \n "
          + "group by dsid()");

      int totalrows = 0;
      assertTrue(r.next());
      totalrows += r.getInt(2);
      assertTrue(r.next());
      totalrows += r.getInt(2);
      assertFalse(r.next());

      assertEquals(5, totalrows);

      r = st.executeQuery("select count(i), dsid() from course, "
          + "sys.members m --gemfirexd-properties withSecondaries = false \n "
          + "group by dsid()");

      totalrows = 0;
      assertTrue(r.next());
      totalrows += r.getInt(1);
      assertTrue(r.next());
      totalrows += r.getInt(1);
      assertFalse(r.next());

      assertEquals(5, totalrows);

      r = st.executeQuery("select count(*), dsid() from course, "
          + "sys.members m --gemfirexd-properties withSecondaries = false \n "
          + "group by dsid()");

      totalrows = 0;
      assertTrue(r.next());
      totalrows += r.getInt(1);
      assertTrue(r.next());
      totalrows += r.getInt(1);
      assertFalse(r.next());

      assertEquals(5, totalrows);

      r = st.executeQuery("select count(*) from course, "
          + "sys.members m --gemfirexd-properties withSecondaries = false \n "
          + "group by dsid()");

      totalrows = 0;
      assertTrue(r.next());
      totalrows += r.getInt(1);
      assertTrue(r.next());
      totalrows += r.getInt(1);
      assertFalse(r.next());

      assertEquals(5, totalrows);

    } finally {
    }
  }

  public void testPerConnectionPlanGeneration() throws Exception {
    startVMs(1, 2);
    checkLoadLib(getTestName());

    setNativeNanoTimer();
    Properties cp = new Properties();
    cp.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(cp);

    Statement st = conn.createStatement();

    st.execute("create table course (" + "course_id int, i int, "
        + " primary key(course_id)" + ") partition by column (i)");

    st.execute("insert into course values (1, 1), (2, 2), "
        + "(3, 1), (4, 3), (5, 1)");

    conn.createStatement().execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
    st.execute("call syscs_util.set_explain_connection(1)");

    ResultSet rs = conn.createStatement().executeQuery(
        "select * from course where course_id > 1");
    int row = 0;
    while (rs.next()) {
      getLogWriter().info("row = " + ++row);
    }
    rs.close();

    ResultSet qp = conn.createStatement().executeQuery(
        "select stmt_id, stmt_text from sys.statementplans");

    assertTrue(qp.next());
    String uuid = qp.getString(1);
    getLogWriter().info("Extracting plan for " + uuid);
    final ExecutionPlanUtils plan = new ExecutionPlanUtils(conn, uuid, null, true);
    getLogWriter().info("plan : " + new String(plan.getPlanAsText(null)));
    assertEquals("Three nodes should respond with its own query plans ", 3,
        plan.getPlanAsXML().size());
    assertFalse(qp.next());
  }

  public void test43863() throws Exception {
    startVMs(0, 3);
    checkLoadLib(getTestName());
    setNativeNanoTimer();
    Properties cp = new Properties();
    cp.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(cp);

    Statement st = conn.createStatement();

    st.execute("create table course (" + "course_id int, i int, "
        + " primary key(course_id)" + ") partition by column (i) redundancy 1");

    st
        .execute("insert into course values (1, 1), (2, 2), (3, 1), (4, 3), (5, 1)");

    conn.createStatement().execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
    st.execute("call syscs_util.set_explain_connection(1)");

    final StatisticsCollectionObserver checker = new StatisticsCollectionObserver() {

      @Override
      public void processedDistPropsDescriptor(
          final XPLAINDistPropsDescriptor distP) {

        for (XPLAINDistPropsDescriptor msg : distP.memberSentMappedDesc) {
          assertTrue(msg.getTargetMember() != null);
          if (msg.locallyExecuted) {
            assertTrue(msg.getTargetMember().equals(msg.getOriginator()));
          }
        }
      }
    };
    StatisticsCollectionObserver.setInstance(checker);

    ResultSet rs = conn.createStatement().executeQuery(
        "select sum(i) from course where course_id > 1");
    int row = 0;
    while (rs.next()) {
      getLogWriter().info("row = " + rs.getString(1));
      assertEquals(7, rs.getInt(1));
    }
    rs.close();

    ResultSet qp = conn.createStatement().executeQuery(
        "select stmt_id, stmt_text from sys.statementplans");

    assertTrue(qp.next());
    
    String uuid = qp.getString(1);
    getLogWriter().info("Extracting plan for " + uuid);
    final ExecutionPlanUtils plan = new ExecutionPlanUtils(conn, uuid, null, true);
    String queryPlan = new String(plan.getPlanAsText(null));
    getLogWriter().info("plan : " + queryPlan);
    
    assertTrue(queryPlan.startsWith("stmt_id"));
    int firstProjection = queryPlan.indexOf("PROJECTION");
    assertTrue( firstProjection > 0);
    assertTrue(queryPlan.indexOf("AGGREGATION", firstProjection) > 0);
    
    int secondProjection = queryPlan.indexOf("PROJECTION", firstProjection+1);
    assertTrue( secondProjection > 0);
    assertTrue(queryPlan.indexOf("TABLESCAN", secondProjection+1) > 0);
    
//      stmt_id 00000001-ffff-ffff-ffff-000000000013 SQL_stmt select sum(i) from course where course_id > <?> begin_execution 2013-09-06 18:17:59.359 end_execution 2013-09-06 18:17:59.363
//      PROJECTION  execute_time 1.0E-6 ms returned_rows 1 no_opens 1 node_details SELECT :1
//        AGGREGATION  execute_time 1.0E-6 ms input_rows 4 returned_rows 1 no_opens 1 node_details SUM
//          PROJECTION  execute_time 1.0E-6 ms returned_rows 4 no_opens 1 node_details SELECT :SUM
//            TABLESCAN (99.99%) execute_time 4.00025 ms returned_rows 4 no_opens 1 scan_qualifiers Column[0][0] Id: COURSE_ID  Operator: > 1 Ordered nulls: false Unknown return value: true  scanned_object BXLEQICBV.COURSE scan_type HEAP

    assertFalse(qp.next());
  }

  // disabled due to failure (#48671)
  public void tmp_SB_testExplainKeyword() throws Exception {
    startVMs(0, 3);
    startVMs(1, 0);
    checkLoadLib(getTestName());

    Properties cp = new Properties();
    cp.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(cp);

    ResultSet rs;
    String currentPlanLine = null;

    Statement st = conn.createStatement();

    st.execute("create table course (" + "course_id int, i int, "
        + " primary key(course_id)" + ") partition by primary key");

    st.execute("insert into course values (1, 1), (2, 2), "
        + "(3, 1), (4, 3), (5, 1)");

    //FIXME 
    // Some of the time, there is no slowest/fastest plan
    // Other times, it is printed out correctly
    // Disable this portion of test for now
    /*
    ResultSet rs = conn.createStatement().executeQuery(
        "explain select * from course where course_id > 1");

    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));

    currentPlanLine = originator(rs.getString(1));
    currentPlanLine = distribution(currentPlanLine, 3);
    currentPlanLine = sequential(currentPlanLine, 4);

    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));
    currentPlanLine = slowestMemberPlan(rs.getString(1));
    currentPlanLine = planMember(currentPlanLine);
    currentPlanLine = queryReceiveResultSend(currentPlanLine);
    currentPlanLine = resultHolder(currentPlanLine, "[0-2]", 1);
    currentPlanLine = tablescan(
        currentPlanLine,
        "[0-2]",
        1,
        "Column\\[0\\]\\[0\\] Id: 0 Operator: <= 1 Ordered nulls: false Unknown return value: true Negate comparison result: true",
        "APP.COURSE", "HEAP", 1);
    assertNull(currentPlanLine);
        
    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));
    currentPlanLine = fastestMemberPlan(rs.getString(1));
    currentPlanLine = planMember(currentPlanLine);
    currentPlanLine = queryReceiveResultSend(currentPlanLine);
    currentPlanLine = resultHolder(currentPlanLine, "[0-2]", 1);
    currentPlanLine = tablescan(
        currentPlanLine,
        "[0-2]",
        1,
        "Column\\[0\\]\\[0\\] Id: 0 Operator: <= 1 Ordered nulls: false Unknown return value: true Negate comparison result: true",
        "APP.COURSE", "HEAP", 1);
    assertNull(currentPlanLine);

    assertFalse(rs.next());

    rs.close();

    */
    rs = conn
        .createStatement()
        .executeQuery(
            "explain select * from course c join course d on c.course_id = d.course_id where c.course_id > 1 or d.course_id <= 1 order by c.i desc");

    assertTrue(rs.next());
    String queryPlan = rs.getString(1);
    getLogWriter().info(queryPlan);
    currentPlanLine = null;

    currentPlanLine = statement(queryPlan);
    currentPlanLine = distribution(currentPlanLine, 3);
    currentPlanLine = roundrobin(currentPlanLine, 5);
    currentPlanLine = ordered(currentPlanLine, 5);

    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));
    checkSlowestPlan(rs.getString(1));

    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));

    assertFalse(rs.next());
    rs.close();

  }
  
  public void tmp_SB_test47380() throws Exception {
    startVMs(1, 3);
    checkLoadLib(getTestName());

    Properties cp = new Properties();
    cp.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(cp);
    Statement st = conn.createStatement();

    st.execute("create schema trade");
    st.execute("create table trade.t1 ( id int primary key, name varchar(10), type int) partition by primary key");

    st.execute("Insert into  trade.t1 values(1,'a',21)");
    st.execute("Insert into  trade.t1 values(2,'b',22)");
    st.execute("Insert into  trade.t1 values(3,'c',23)");

    ResultSet rs = conn.createStatement().executeQuery(
        "explain select id, type from trade.t1 A where id in (2,1)");

    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));

    String currentPlanLine = originator(rs.getString(1));
    currentPlanLine = regiongetall(currentPlanLine, 2);

    assertFalse(rs.next());
    rs.close();
  } 

  // disabled due to failure (#48671)
  public void tmp_SB_testSimpleStats() throws Exception {
    Properties p = new Properties();

    // p.setProperty(Attribute.ENABLE_STATS, "true");
    // p.setProperty(Attribute.ENABLE_TIMESTATS, "true");

    // start a network server
    int netPort = startNetworkServer(1, null, p);
    checkLoadLib(getTestName());

    final Connection conn = TestUtil.getNetConnection(netPort, null, null);
    boolean secondServerStarted = false;
    try {
      
      Statement s = conn.createStatement();
      s.execute("Create Table TEST_TABLE(idx numeric(12),"
          + "AccountID varchar(10)," + "OrderNo varchar(20),"
          + "primary key(idx)" + ")"
          + "PARTITION BY COLUMN ( AccountID )");

      s.execute("CREATE INDEX idx_AccountID ON test_Table (AccountID ASC)");
      PreparedStatement insps = conn
          .prepareStatement("insert into test_table values(?,?,?)");
      int base = 1;
      for (int i = 0; i < 1000; i++) {
        insps.setInt(1, base + i);
        insps.setString(2, String.valueOf(i % 9));
        insps.setString(3, String.valueOf(i));
        insps.executeUpdate();
      }
      
      {
        ResultSet r = conn.createStatement().executeQuery(
            "explain select * from test_table where accountid > '" + 7
                + "' order by idx desc ");

        assertTrue(r.next());
        java.sql.Clob c = r.getClob(1);
        BufferedReader reader = new BufferedReader(c.getCharacterStream());
        int sz = (int)c.length();
        char[] charArray = new char[sz];
        reader.read(charArray, 0, sz);
        final String pl = new String(charArray);
        getLogWriter().info("Plan: " + pl);

        String currentPlanLine = null;

        currentPlanLine = originator(pl);
        currentPlanLine = distribution(currentPlanLine, 1);
        currentPlanLine = sequential(currentPlanLine, 111);

        currentPlanLine = localPlan(currentPlanLine);
        currentPlanLine = planMember(currentPlanLine);
        currentPlanLine = sort(currentPlanLine, "111", 1);
        currentPlanLine = rowidscan(currentPlanLine, "111", "1");
        currentPlanLine = indexscan(currentPlanLine, 111, 1, "None",
            "IDX_ACCOUNTID");
        r.close();
      }
      
      // start another server and the plan should change.
      {
        startNetworkServer(2, null, p);
        secondServerStarted = true;
        
        conn.createStatement().execute("call sys.REBALANCE_ALL_BUCKETS()");

        ResultSet r = conn.createStatement().executeQuery(
            "explain select * from test_table where accountid like '" + 8
                + "' order by idx desc ");

        assertTrue(r.next());
        java.sql.Clob c = r.getClob(1);
        BufferedReader reader = new BufferedReader(c.getCharacterStream());
        int sz = (int)c.length();
        char[] charArray = new char[sz];
        reader.read(charArray, 0, sz);
        final String pl = new String(charArray);
        getLogWriter().info("Plan: " + pl);

        String currentPlanLine = null;

        currentPlanLine = originator(pl);
        currentPlanLine = distribution(currentPlanLine, 1);
        currentPlanLine = sequential(currentPlanLine, 111);
//        currentPlanLine = roundrobin(currentPlanLine, 111);
//        currentPlanLine = ordered(currentPlanLine, 111);

        currentPlanLine = localPlan(currentPlanLine);
        currentPlanLine = planMember(currentPlanLine);
        currentPlanLine = sort(currentPlanLine, "111", 1);
        currentPlanLine = filter2(currentPlanLine, "111", "1", "SELECT :IDX ACCOUNTID ORDERNO WHERE :ACCOUNTID LIKE CONSTANT:8");
        currentPlanLine = rowidscan(currentPlanLine, "111", "1");
        currentPlanLine = indexscan(currentPlanLine, 111, 1, "None",
            "IDX_ACCOUNTID");
//        currentPlanLine = sort(currentPlanLine, "111", 1);
//        currentPlanLine = filter(currentPlanLine, "111", "1");
//        currentPlanLine = tablescan(currentPlanLine, "556", 1, "None",
//            "APP.TEST_TABLE", "HEAP", 1);
//        assertNull(currentPlanLine);       
        r.close();
      }

      {
        s.execute("Create Table TEST_TABLE_1(idx numeric(12),"
            + "AccountID varchar(10)," + "OrderNo varchar(20),"
            + "primary key(idx)" + ")" + "REPLICATE ");
        insps = conn.prepareStatement("insert into test_table_1 values(?,?,?)");
        base = 1;
        for (int i = 0; i < 1000; i++) {
          insps.setInt(1, base + i);
          insps.setString(2, String.valueOf(i % 9));
          insps.setString(3, String.valueOf(i));
          insps.executeUpdate();
        }

        ResultSet r = conn.createStatement().executeQuery(
            "explain select * from test_table_1 ");
        
        assertTrue(r.next());
        java.sql.Clob c = r.getClob(1);
        BufferedReader reader = new BufferedReader(c.getCharacterStream());
        int sz = (int)c.length();
        char[] charArray = new char[sz];
        reader.read(charArray, 0, sz);
        final String pl = new String(charArray);
        getLogWriter().info("Result:\n " + pl);
        
        String currentPlanLine = null;

        currentPlanLine = localPlan(pl);
        currentPlanLine = planMember(currentPlanLine);
        currentPlanLine = tablescan(currentPlanLine, "1000", 1, "None",
            "APP.TEST_TABLE_1", "HEAP", 1);
        assertNull(currentPlanLine);
        
        r.close();
      }
      
    } finally {
      if(secondServerStarted) {
        stopNetworkServer(2);
      }
      stopNetworkServer(1);
    }
  }

  public void tmp_SB_test44550() throws Exception {
    
    Properties p = new Properties();

    // p.setProperty(Attribute.ENABLE_STATS, "true");
    // p.setProperty(Attribute.ENABLE_TIMESTATS, "true");
    
    Connection systemconn = TestUtil.getConnection();
    try {
      CallableStatement cusr = systemconn
          .prepareCall("call SYSCS_UTIL.SET_DATABASE_PROPERTY(?,?)");
      cusr.setString(1, "gemfirexd.enable-getall-local-index-embed-gfe");
      cusr.setString(2, "true");
      cusr.execute();
      cusr.close();
      
      // start a network server
      int netPort = startNetworkServer(1, null, p);
      startNetworkServer(2, null, p);
      checkLoadLib(getTestName());

      final Connection conn = TestUtil.getNetConnection(netPort, null, null);

      Statement s = conn.createStatement();
      s.execute("Create Table TEST_TABLE(idx numeric(12),"
          + "AccountID varchar(10)," + "OrderNo varchar(20),"
          + "primary key(idx)" + ")" + "PARTITION BY COLUMN ( AccountID )");

      s.execute("CREATE INDEX idx_AccountID ON test_Table (AccountID ASC)");
      PreparedStatement insps = conn
          .prepareStatement("insert into test_table values(?,?,?)");
      int base = 1;
      for (int i = 0; i < 1000; i++) {
        insps.setInt(1, base + i);
        insps.setString(2, String.valueOf(i % 9));
        insps.setString(3, String.valueOf(i));
        insps.executeUpdate();
      }

      {
        ResultSet r = conn.createStatement().executeQuery(
            "explain select * from test_table where accountid = '" + 8
                + "' order by idx desc ");

        assertTrue(r.next());
        java.sql.Clob c = r.getClob(1);
        BufferedReader reader = new BufferedReader(c.getCharacterStream());
        int sz = (int)c.length();
        char[] charArray = new char[sz];
        reader.read(charArray, 0, sz);
        final String pl = new String(charArray);
        getLogWriter().info("Plan: " + pl);

        String currentPlanLine = null;

        currentPlanLine = localPlan(pl);
        currentPlanLine = planMember(currentPlanLine);
        currentPlanLine = sort(currentPlanLine, "111", 1);
        currentPlanLine = localindexgetall(currentPlanLine, "111", 1);
        r.close();
      }
    } finally {
      if (systemconn != null) {
        systemconn.close();
      }
      
      stopNetworkServer(2);
      stopNetworkServer(1);
    }
  }
  
  // disabled due to failure (#48671)
  public void tmp_SB_test47798() throws Exception {
    startVMs(1, 3);
    checkLoadLib(getTestName());

    Properties cp = new Properties();
    cp.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(cp);
    Statement st = conn.createStatement();

    st.execute("create schema trade");
    st.execute("create table trade.customers (cid int not null, cust_name varchar(100), since date, addr varchar(100), tid int, primary key (cid)) partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17))");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) replicate");
    st.execute("create table trade.portfolio (cid int not null, sid int not null, qty int not null, availQty int not null, subTotal decimal(30,20), tid int, constraint portf_pk primary key (cid, sid), constraint cust_fk foreign key (cid) references trade.customers (cid) on delete restrict, constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty)) replicate");
    
    st.execute("insert into trade.customers values(1, 'vivek', null, 'pune', 1)");
    st.execute("insert into trade.securities values(9, 'vb', null, 'lse', 1)");
    st.execute("insert into trade.portfolio values(1, 1, 10, 1, null, 1)");

    st.execute("call SYSCS_UTIL.SET_EXPLAIN_CONNECTION(1)");
    st.execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
    
    String query = "Select sec_id, symbol, s.tid, cid, cust_name, c.tid from trade.securities s, trade.customers c where c.cid = (select f.cid from trade.portfolio f where c.cid = f.cid and f.tid = 1 group by f.cid having count(*) < 5) and sec_id in (select sid from trade.portfolio f where availQty > 0 and availQty < 927)";

    ResultSet rs = conn.createStatement().executeQuery("explain " + query);
    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));

    String currentPlanLine = originator(rs.getString(1));
    currentPlanLine = distribution(currentPlanLine, 1);
    currentPlanLine = sequential(currentPlanLine, 0);

    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));

    assertTrue(rs.next());
    getLogWriter().info(rs.getString(1));

    assertFalse(rs.next());
    rs.close();
  } 

  private final static String timeStampPattern = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(\\.\\d+)?"; 
      //"[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9](\\.\\d+)?";

  // Member ids no longer have two ports after the virtual member id number
  //  ?????<v??>:????
  private final static String memberIdPattern = ".*\\<v[0-9]([0-9]*)?\\>:[0-9]([0-9]*)?";

  // Pattern for "xx.xxx ms" for millisecond timing
  private final static String executeMillis = "\\d+(\\.\\d+)?(E.\\d*)?\\s*ms";
  
  // Pattern for an optional percentage string for timings i.e. (10.44%)
  //   which may or may not follow on from operators
  private final static String optionalPercentage = "\\s+(\\(.*?\\))?";
  
  private final static String optionalRank = "(\\(\\d\\))?";
  
  private final static String uuid = "[0-9[a-f]]{8}-[a-f]{4}-[a-f]{4}-[a-f]{4}-[0-9[a-f]]{12}";
  
  private final static String pipes = "(\\|\\s*)*";
  private final static   String plus = "\\s+\\+\\s+";
  private final static String construct = "\\(construct=" + executeMillis + plus + "open="
      + executeMillis + plus + "next=" + executeMillis + plus + "close="
      + executeMillis + "\\)";
  private final static String serialize = "\\(serialize/deserialize=" + executeMillis + plus
      + "process=" + executeMillis + plus + "throttle=" + executeMillis
      + "\\)";
  private final static String opitonalExpansion = "("+construct+"|"+serialize+")?";
  
  public final static String statement(String input) {
    // stmt_id 00000001-ffff-ffff-ffff-00000000000b

    Pattern planMatcher = Pattern.compile("stmt_id\\s+" + uuid,
        Pattern.MULTILINE);

    String[] stmnt_id = planMatcher.split(input);
    TestUtil.getLogger().info(java.util.Arrays.toString(stmnt_id));
    assertEquals(2, stmnt_id.length);

    // include SQL_stmt as well.
    planMatcher = Pattern.compile("SQL_stmt.*", Pattern.MULTILINE);
    stmnt_id = planMatcher.split(stmnt_id[1]);
    TestUtil.getLogger().info(java.util.Arrays.toString(stmnt_id));
    assertEquals(2, stmnt_id.length);
    
    planMatcher = Pattern.compile("begin_execution\\s+"
        + timeStampPattern
        + "\\s+end_execution\\s+"
        + timeStampPattern
        + "\\s+\\(\\d+ .*seconds elapsed\\)");
    stmnt_id = planMatcher.split(stmnt_id[1]);
    TestUtil.getLogger().info(java.util.Arrays.toString(stmnt_id));
    assertEquals(2, stmnt_id.length);
    
    return stmnt_id[1];
  }

  public final static String originator(String input) {
    StringBuilder patternString = new StringBuilder();
    // ORIGINATOR pc27(24739)<v3>:45000/47964 BEGIN TIME 2012-02-17 19:39:49.88
    // END TIME 2012-02-17 19:39:50.062
    patternString.append("ORIGINATOR ").append(memberIdPattern).append(
        " BEGIN\\sTIME\\s").append(timeStampPattern).append(".*END\\sTIME\\s")
        .append(timeStampPattern);

    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    final String[] originatorDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(originatorDone));
    assertEquals(2, originatorDone.length);
    return originatorDone[1];
  }

  public final static String distribution(String input, int numMembers) {
    StringBuilder patternString = new StringBuilder();
    // DISTRIBUTION to 3 members took 3210 microseconds ( message sending
    // min/max/avg time 535/952/2036 microseconds and receiving min/max/avg time
    // 171/1067/1444 microseconds )
    patternString = new StringBuilder();
    patternString
        .append("\\s*DISTRIBUTION to\\s*"
            + numMembers
            + "\\s*members took [0-9]([0-9]*)? microseconds\\s*\\( "
            + "message sending min/max/avg time [0-9]([0-9]*)?/[0-9]([0-9]*)?/[0-9]([0-9]*)? microseconds and "
            + "receiving min/max/avg time [0-9]([0-9]*)?/[0-9]([0-9]*)?/[0-9]([0-9]*)? microseconds \\)");

    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    final String[] distributionDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(distributionDone));
    assertEquals(2, distributionDone.length);
    return distributionDone[1];
  }

  public final static String sequential(String input, int numRows) {
    StringBuilder patternString = new StringBuilder();
    // SEQUENTIAL-ITERATION of 4 rows took 8757 microseconds
    patternString = new StringBuilder();
    patternString.append("\\s*SEQUENTIAL-ITERATION of\\s*" + numRows
        + "\\s*rows took [0-9]([0-9]*)? microseconds");
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    final String[] sequentialDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(sequentialDone));
    assertEquals(2, sequentialDone.length);
    return sequentialDone[1];
  }

  public final static String roundrobin(String input, int numRows) {
    StringBuilder patternString = new StringBuilder();
    // ROUNDROBIN-ITERATION of 5 rows took 88738 microseconds
    patternString = new StringBuilder();
    patternString.append("\\s*ROUNDROBIN-ITERATION of\\s*" + numRows
        + "\\s*rows took [0-9]([0-9]*)? microseconds");
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    final String[] roundRobinDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(roundRobinDone));
    assertEquals(2, roundRobinDone.length);
    return roundRobinDone[1];
  }

  public final static String ordered(String input, int numRows) {
    StringBuilder patternString = new StringBuilder();
    // ORDERED-ITERATION of 5 rows with 5 input rows took 89897 microseconds
    patternString = new StringBuilder();
    patternString.append("ORDERED-ITERATION of\\s*" + numRows
        + "\\s*rows with\\s*" + numRows
        + "\\s*input rows took [0-9]([0-9]*)? microseconds");
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    final String[] orderedDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(orderedDone));
    assertEquals(2, orderedDone.length);
    return orderedDone[1];
  }
  
  public final static String regiongetall(String input, int numRows) {
    StringBuilder patternString = new StringBuilder();
    // REGION-GETALL of 2 rows took 2315 microseconds
    patternString = new StringBuilder();
    patternString.append("\\s*REGION-GETALL of\\s*" + numRows
        + "\\s*rows took [0-9]([0-9]*)? ");
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    final String[] getallDone = planMatcher.split(input);
    getGlobalLogger().info("regiongetall:" + java.util.Arrays.toString(getallDone));
    assertEquals("microseconds", getallDone[1].trim());
    return getallDone[1];
  }
  
  public final static String localindexgetall(String input, String rows, int numOpens) {
    StringBuilder patternString = new StringBuilder();
    // LOCAL-INDEX-GETALL (72.92%) execute_time 19.302379 ms returned_rows 111 no_opens 1
    patternString = new StringBuilder();
    patternString.append("LOCAL-INDEX-GETALL ").append(optionalPercentage).
      append(" execute_time\\s*").append(executeMillis).append("\\s*returned_rows\\s*" + rows
              + "\\s*no_opens\\s*" + numOpens);

    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    final String[] getallDone = planMatcher.split(input);
    getGlobalLogger().info(
        "localindexgetall:" + java.util.Arrays.toString(getallDone));
    assertEquals(2, getallDone.length);
    return getallDone[1];
  }

  public final static String slowestMemberPlan(String input) {
    // Slowest Member Plan:
    Pattern planMatcher = Pattern.compile("Slowest Member Plan:",
        Pattern.MULTILINE);
    String[] introLineDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(introLineDone));
    assertEquals(2, introLineDone.length);
    return introLineDone[1];
  }

  public final static String fastestMemberPlan(String input) {
    // Fastest Member Plan:
    Pattern planMatcher = Pattern.compile("Fastest Member Plan:",
        Pattern.MULTILINE);
    String[] introLineDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(introLineDone));
    assertEquals(2, introLineDone.length);
    return introLineDone[1];
  }

  public final static String localPlan(String input) {
    // "Local plan:"+
    StringBuilder patternString = new StringBuilder();
    patternString.append("Local plan:");
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] localPlanDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(localPlanDone));
    assertEquals(2, localPlanDone.length);
    return localPlanDone[1];
  }

  public final static String planMember(String input) {
    // "member   pc27(29343)<v1>:34264/56401 begin_execution  2012-03-31 01:51:35.703 end_execution  2012-03-31 01:51:35.708"+
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("\\s*member\\s*").append(memberIdPattern).append(
        "\\s*begin_execution\\s*").append(timeStampPattern).append(
        "\\s*end_execution\\s*").append(timeStampPattern);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] planMemberDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(planMemberDone));
    assertEquals(2, planMemberDone.length);
    return planMemberDone[1];
  }

  public final static String queryReceiveResultSend(String input) {
    // QUERY-RECEIVE execute_time 81.895568 ms member_node
    // pc27(24739)<v3>:45000/47964
    // RESULT-SEND execute_time 156.958 ms member_node pc27(24739)<v3>:45000/47964
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString
        .append("\\s*QUERY-RECEIVE\\s*execute_time\\s*").append(executeMillis).append("\\s*member_node\\s*")
        .append(memberIdPattern).append("\\s*RESULT-SEND\\s*execute_time\\s*")
        .append(executeMillis).append(" member_node ").append(memberIdPattern);

    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    String[] queryRecRespSendDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(queryRecRespSendDone));
    assert queryRecRespSendDone.length == 2;
    return queryRecRespSendDone[1];
  }

  public final static String queryReceive(String input) {
    // QUERY-RECEIVE execute_time 10.533396 ms member_node
    // oman(gemfire_1_1_oman_29245:29245)<v1>:8078
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("\\s*QUERY-RECEIVE\\s*execute_time\\s*").append(
        executeMillis).append("\\s*member_node\\s*").append(memberIdPattern);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] queryReceiveDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(queryReceiveDone));
    assert queryReceiveDone.length == 2;
    return queryReceiveDone[1];
  }

  public final static String querySend(String input) {
    // QUERY-SEND execute_time 0.421584 ms member_node
    // oman(gemfire_1_1_oman_29249:29249)<v2>:40332
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("\\s*QUERY-SEND\\s*execute_time\\s*").append(
        executeMillis).append("\\s*member_node\\s*").append(memberIdPattern);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] querySendDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(querySendDone));
    assert querySendDone.length == 2;
    return querySendDone[1];
  }

  public final static String resultSend(String input) {
    // RESULT-SEND execute_time 0.009838 ms member_node
    // oman(gemfire_1_1_oman_29245:29245)<v1>:8078
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("\\s*RESULT-SEND\\s*execute_time ").append(
        executeMillis).append("\\s*member_node\\s*").append(memberIdPattern);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] resultSendDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(resultSendDone));
    assertEquals(2, resultSendDone.length);
    return resultSendDone[1];
  }
  
  public final static String resultHolder(String input, String rows,
      int numOpens) {
    // RESULT-HOLDER execute_time 20.5449 ms returned_rows 2 no_opens 1
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("\\s*RESULT-HOLDER\\s*execute_time ").append(executeMillis).
            append(" returned_rows\\s*" + rows + "\\s*no_opens\\s*"
            + numOpens);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);

    String[] resultHolderDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(resultHolderDone));
    assertEquals(2, resultHolderDone.length);
    return resultHolderDone[1];
  }
  
  public final static String resultHolder2(String input, String rows,
      int numOpens) {
    // RESULT-HOLDER execute_time 20.5449 ms returned_rows 2 no_opens 1
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("\\s*RESULT-HOLDER\\s*execute_time ").append(
        executeMillis).append(
        " returned_rows\\s*" + rows + "\\s*no_opens\\s*" + numOpens);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] resultHolderDone = planMatcher.split(input, 2);
    getGlobalLogger().info(java.util.Arrays.toString(resultHolderDone));
    assertEquals(2, resultHolderDone.length);
    return resultHolderDone[1];
  }

  public final static String sort(String input, String rows, int numOpens) {
    // "SORT (0.00%) execute_time 67.218 ms input_rows 56 returned_rows 56 no_opens 1 sort_type IN sorter_output 56"+
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("SORT ").append(optionalPercentage).
        append(" execute_time\\s*").append(executeMillis).append("\\s*input_rows " + rows + "\\s*returned_rows\\s*" + rows
            + "\\s*no_opens\\s*" + numOpens + " sort_type IN sorter_output\\s*" + rows);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] sortDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(sortDone));
    assertEquals(2, sortDone.length);
    return sortDone[1];
  }

  public final static String rowidscan(String input, String rows,
      String numOpens) {
    // "  ROWIDSCAN (0.00%) execute time 25.820 ms returned_rows 56 no_opens 1"+
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("ROWIDSCAN ").append(optionalPercentage).
        append(" execute_time\\s*").append(executeMillis).
        append("\\s*returned_rows\\s*" + rows + "\\s*no_opens\\s*" + numOpens);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] rowIDScanDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(rowIDScanDone));
    assertEquals(2, rowIDScanDone.length);
    return rowIDScanDone[1];
  }

  public final static String tablescan(String input, String rows, int numOpens,
      String scanQualifier, String scannedObject, String scanType, int expected) {
    //"     |   |(1)TABLESCAN (100.00%) execute_time 82.029623ms (serialize/deserialize=0.043367ms + process=0.333363ms + throttle=0.0ms) returned_rows 25 no_opens 1 visited_rows 25 scan_qualifiers 42Z48.U : [0] 0, [1] 0: COURSE_NAME  42Z43.U : : = d 42Z44.U : : false 42Z45.U : : false  next_qualifiers NULL scanned_object APP.COURSE scan_type HEAP\n"
    Pattern planMatcher = Pattern.compile("\\s+" + pipes + optionalRank
        + "TABLESCAN" + optionalPercentage + "\\s+execute_time\\s+"
        + executeMillis + "\\s+" + opitonalExpansion + "\\s*returned_rows\\s*"
        + rows + "\\s*no_opens\\s*" + numOpens + "\\s*visited_rows\\s*" + rows
        + "\\s*scan_qualifiers\\s*" + ".*" + scanQualifier + ".*"
        + "\\s*scanned_object\\s*" + ".*" + scannedObject + ".*"
        + "\\s*scan_type\\s*" + scanType + "\\s*");
    
    String[] tableScanDone = planMatcher.split(input);
    TestUtil.getLogger().info(java.util.Arrays.toString(tableScanDone));
    assertEquals(expected, tableScanDone.length);
    if (expected == 0) {
      return null;
    }
    return tableScanDone[1];
  }

  private String filter(String input, String rows, String numOpens) {
    //     FILTER (12.64%) execute_time 0.834726 ms returned_rows 111 no_opens 1
    StringBuilder patternString = new StringBuilder();
    patternString.append("FILTER ").append(optionalPercentage).
        append(" execute_time\\s*").append(executeMillis).
        append(" returned_rows "+rows+" no_opens "+numOpens);
    Pattern planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] filterDone = planMatcher.split(input);
    getLogWriter().info(java.util.Arrays.toString(filterDone));
    assertEquals(2, filterDone.length);
    return filterDone[1];
  }

  private String filter2(String input, String rows, String numOpens,
      String nodeDetails) {
    // FILTER (3.34%) execute_time 0.033044 ms returned_rows 0 no_opens 1
    // node_details SELECT :IDX ACCOUNTID ORDERNO WHERE :ACCOUNTID LIKE
    // CONSTANT:8
    StringBuilder patternString = new StringBuilder();
    patternString.append("FILTER ").append(optionalPercentage).append(
        " execute_time\\s*").append(executeMillis).append(
        " returned_rows " + rows + " no_opens " + numOpens).append(
        " node_details ").append(nodeDetails);
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] filterDone = planMatcher.split(input);
    getLogWriter().info(java.util.Arrays.toString(filterDone));
    assertEquals(2, filterDone.length);
    return filterDone[1];
  }
  
  public final static String indexscan(String input, int rows, int numOpens,
      String scanQualifier, String scannedObject) {
    // "    INDEXSCAN (0.00%) execute_time 71.8083 ms returned_rows 56 no_opens 1 scan_qualifiers None scanned_object IDX_ACCOUNTID scan_type      ";
    StringBuilder patternString = new StringBuilder();
    patternString = new StringBuilder();
    patternString.append("INDEXSCAN ").append(optionalPercentage).
        append(" execute_time\\s*").append(executeMillis).
        append(" returned_rows\\s*" + rows + "\\s*no_opens\\s*" + numOpens
            + "\\s*scan_qualifiers\\s*" + scanQualifier + "\\s*scanned_object\\s*"
            + scannedObject + "\\s*scan_type");
    Pattern planMatcher = Pattern.compile(patternString.toString(),
        Pattern.MULTILINE);
    String[] indexScanDone = planMatcher.split(input);
    getGlobalLogger().info(java.util.Arrays.toString(indexScanDone));
    assertEquals(2, indexScanDone.length);
    return indexScanDone[1];
  }

  private void checkSlowestPlan(String planOutput) {
    StringBuilder patternString = new StringBuilder();

    String slowestMemberDone = slowestMemberPlan(planOutput);

    String planMemberDone = planMember(slowestMemberDone);

    String queryRecRespSendDone = queryReceiveResultSend(planMemberDone);

    String resultHolderDone = resultHolder(queryRecRespSendDone, "[1-2]", 1);

    String sortDone = sort(resultHolderDone, "[1-2]", 1);

    //           NLJOIN (0.59%) execute_time 0.062863 ms returned_rows 2 no_opens 1
    patternString = new StringBuilder();
    patternString.append("NLJOIN ").append(optionalPercentage).
        append(" execute_time\\s*").append(executeMillis).append(" returned_rows [1-2] no_opens 1");
    Pattern planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] nlJoinDone = planMatcher.split(sortDone);
    getLogWriter().info(java.util.Arrays.toString(nlJoinDone));
    assert nlJoinDone.length == 2;

    String tableScanDone = tablescan(nlJoinDone[1], "[1-2]", 1, "None",
        "COURSE", "HEAP", 2);

    String filterDone = filter(tableScanDone, "[1-2]", "[1-2]");

    String rowIDScanDone = rowidscan(filterDone, "[1-2]", "[1-2]");

    //   CONSTRAINTSCAN (90.39%) execute_time 9.541994 ms returned_rows 2 no_opens 2 scan_qualifiers None scanned_object SQL121204152227820 scan_type 
    patternString = new StringBuilder();
    patternString
        .append("CONSTRAINTSCAN\\s*").append(optionalPercentage).
        append("\\s*execute_time\\s*").append(executeMillis).
        append(" returned_rows [1-2] no_opens [1-2]\\s*scan_qualifiers None scanned_object SQL[0-9]([0-9]*)?\\s*scan_type\\s*");
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] constraintScanDone = planMatcher.split(rowIDScanDone);
    getLogWriter().info(java.util.Arrays.toString(constraintScanDone));
    assert constraintScanDone.length == 1: constraintScanDone.length;

  }

  private void checkLoadLib(final String testName) {
    invokeInEveryVM(new SerializableRunnable("verifying for " + testName) {
      
      @Override
      public void run() {
        
        GemFireStore store = Misc.getMemStoreBootingNoThrow();
        Logger log = getLogWriter();
        
        if (store == null) {
          log.info("SB: server not booted");
          return;
        }
        
        if (!store.getMyVMKind().isAccessorOrStore()) {
          log.info("SB: not a server or accessor.");
          return;
        }

        TestUtil.assertTimerLibraryLoaded();
      }
    });
  }

  
  private static Connection st_conn = null;
}
