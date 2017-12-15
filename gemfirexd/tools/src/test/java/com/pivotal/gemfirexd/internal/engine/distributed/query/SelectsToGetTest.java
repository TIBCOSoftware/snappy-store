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
package com.pivotal.gemfirexd.internal.engine.distributed.query;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Pattern;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.compile.ParseException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Parser;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.tools.GfxdDistributionLocator;
import com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher;
import com.pivotal.gemfirexd.tools.planexporter.CreateXML;
import com.pivotal.gemfirexd.tools.utils.ExecutionPlanUtils;
import io.snappydata.jdbc.ClientXADataSource;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Soubhikc
 * 
 */
public class SelectsToGetTest extends JdbcTestBase {

  private static String available_port;

  public SelectsToGetTest(String name) {
    super(name);
    available_port = String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS));
    // System.setProperty("gemfirexd.authentication.required", "true");
    // System.setProperty("gemfirexd.authentication.provider", "BUILTIN");
    // System.setProperty("gemfirexd.debug.true", "AuthenticationTrace");
    // System.setProperty("gemfirexd.language.logQueryPlan", "true");
//    System.setProperty("gemfire.CacheServerLauncher.dontExitAfterLaunch", "true");
  }

  public static void main(String[] args) {
    available_port = String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS));
    TestRunner.run(new TestSuite(SelectsToGetTest.class));
  }

  public void __testDJ() throws Exception {
    System.setProperty("gemfirexd.optimizer.trace", "true");
    System.setProperty("gemfirexd.debug.true", "TraceNCJ");
    System.getProperty("optimize-ncj", "true");
    Properties p = new Properties();
    p.setProperty("mcast-port", available_port);
    p.setProperty("SKIP_SPS_PRECOMPILE", "true");
    setupConnection(p);
    
    Connection conn = getConnection();
    Statement st = conn.createStatement();
   /* st.execute("create table t1 (c1 int, c2 int) partition by column(c1)");
    st.execute("create table t2 (c1 int, c2 int) partition by column(c1) colocate with (t1)");
    st.execute("insert into t1 values (null, null), (1,1), (null, null), (2,1), (3,1), (10,10)");
    st.execute("insert into t2 values (null, null), (1,1), (null, null), (2,1), (3,1), (10,10)");
    
    ResultSet rs = null;
    try {
      rs = st
          .executeQuery("select max(t1.c1), max(t2.c2) from t1, t2 where t1.c1 = t2.c1");
      while (rs.next()) {
        System.out.println(rs.getInt(1) + " " + rs.getInt(2));
      }
    } finally {
      System.out.println(((EmbedConnection)conn).getLanguageConnection()
          .getOptimizerTraceOutput());
      if (rs != null) rs.close();
    }
    if (true) return;*/
    
    st.execute("create table Customer (cust_id varchar(32), cust_type char(1), primary key(cust_id, cust_type)) partition by primary key");
    st.execute("create table Product (p_id varchar(32), p_type varchar(32), primary key(p_id, p_type) ) partition by column (p_type, p_id)");
    st.execute("create table OOrder (o_id varchar(32) primary key, "
        + "cust_id varchar(32), "
        + "cust_type char(1), "
        + "p_id varchar(32), "
        + "p_type varchar(32), "
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
        + " ('p-19', 'typ1'), ('p-20', 'typ2') "
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
        + "('o-19' , 'c-1','a', 'p-1', 'typ1'), ('o-20', 'c-1','a', 'p-2', 'typ2') "
        );
    
    try {
      ResultSet r  = st.executeQuery("select * from OOrder o , Product p "
          + ", Customer c where o.p_id = p.p_id and o.p_type = p.p_type and o.cust_id = c.cust_id and o.cust_type = c.cust_type " );
      
      //2 predicates
      //"select * from OOrder o join Product p on (o.p_id = p.p_id and o.p_type = p.p_type) "
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type"
      //"select * from OOrder o join Product p on o.p_id = p.p_id and o.p_type = p.p_type "
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type"
      
      //1 predicate
      //select * from OOrder o join Product p on o.p_id = p.p_id and o.p_type = p.p_type or (o.p_id is null and p.p_id is null) "
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type
      //select * from OOrder o join Product p on o.p_id = p.p_id and o.p_type = p.p_type or o.p_id is null and p.p_id is null "
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type
      //select * from OOrder o join Product p on o.p_id = p.p_id or o.p_id is null and p.p_id is null and o.p_type = p.p_type "
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type
      //"select * from OOrder o join Product p on (o.p_id = p.p_id or (o.p_id is null and p.p_id is null)) and o.p_type = p.p_type "
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type"
      //select * from OOrder o join Product p on (o.p_id = p.p_id or o.p_id is null or p.p_id is null) and o.p_type = p.p_type "
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type
      //"select * from OOrder o join Product p on (o.p_id = p.p_id and o.p_type = p.p_type) or o.pid is null or p.p_id is null or o.p_type is null or p.p_type is null " 
      //+ "join Customer c on o.cust_id = c.cust_id and o.cust_type = c.cust_type"
      while(r.next()) {
        System.out.println(r.getString(1));
      }
    }
    finally {
      System.out.println(((EmbedConnection)conn).getLanguageConnection().getOptimizerTraceOutput());
    }
  }
  
  public void testSessionVTI() throws Exception {
    
    try {

      Properties p = new Properties();
      p.setProperty(Attribute.TABLE_DEFAULT_PARTITIONED, "false");
      setupConnection(p);
      
//      int port = TestUtil.startNetserverAndReturnPort();
//      Connection conn1 = TestUtil.getNetConnection(port, null, null);

      Connection conn = getConnection();
      Statement st = conn.createStatement();

      st.execute("CREATE TABLE hydra_test (" + "id integer NOT NULL,"
          + "conf clob(5M)," + "full_test_spec clob(5M),"
          + "hydra_testsuite_id integer NOT NULL" + ")");
      
      PreparedStatement ps = conn.prepareStatement("insert into hydra_test(id, hydra_testsuite_id) values (?, ?)");

      st.execute("CREATE TABLE hydra_testsuite (" + "id integer NOT NULL,"
          + "name varchar(256) NOT NULL" + ")");

      ps = conn.prepareStatement("insert into hydra_testsuite(id, name) values (?, ?)");
      
      st.execute("CREATE TABLE hydra_testsuite_detail ("
          + "id integer NOT NULL," 
          + "date timestamp,"
          + "elapsed_time varchar(256)," 
          + "disk_usage varchar(256),"
          + "passcount integer," 
          + "failcount integer," 
          + "hangcount integer,"
          + "local_conf clob(5M)," 
          + "hydra_testsuite_id integer NOT NULL,"
          + "hydra_run_id integer," 
          + "host_id integer,"
          + "comment long varchar" + ")");
      
      ps = conn.prepareStatement("insert into hydra_testsuite_detail(id, hydra_testsuite_id, elapsed_time) values (?, ?, ?)");
     
      ResultSet r = st.executeQuery("SELECT HYDRA_TESTSUITE_ID, NAME, cnt, mx FROM HYDRA_TESTSUITE, " +
      "(SELECT HYDRA_TESTSUITE_ID, COUNT(*) as cnt FROM HYDRA_TEST GROUP BY HYDRA_TESTSUITE_ID) AS FOO, " +
      "(SELECT HYDRA_TESTSUITE_ID AS HTID, MAX(ELAPSED_TIME) as mx FROM HYDRA_TESTSUITE_DETAIL GROUP BY HYDRA_TESTSUITE_ID) AS POO " +
       "WHERE HYDRA_TESTSUITE_ID = ID AND ID = HTID ORDER BY cnt; " );
      
      while(r.next()) {
        System.out.println(r.getString(1) + " " + r.getInt(2) + " " + r.getInt(3));
      }
      
    } finally {
      TestUtil.shutDown();
      shutdownSecondaryServers();
    }
  }

  public void testDummy() throws Exception {
    Pattern p = Pattern.compile(":\\s*(?!\\s*rgb).*\\(.*\\)");
    
    System.out.println(p.matcher(": rgb (22)").replaceAll(":"));
    System.out.println(p.matcher(": xxrgb (22)").replaceAll(":"));
    System.out.println(p.matcher(": rgbxx (22)").replaceAll(":"));
    System.out.println(p.matcher(": rggb (22)").replaceAll(":"));
    System.out.println(p.matcher(": rrrgb (22)").replaceAll(":"));
    System.out.println(p.matcher(": rg (22)").replaceAll(":"));
    System.out.println(p.matcher(": expression (22)").replaceAll(":"));
  }

  public void __testXATest() throws Exception {

    try {
      LAUNCH_PEER_SERVER = true;
      debugNumSecondaryServer = 0;
      secServerCount = 2; // inclusive of locator
      launchIndividualLocator = true;
      runNetServer = true;
      launchSecondaryServers();
      {
        Properties cp = new Properties();
        makeClient(cp);
        // cp.setProperty("mcast-port", available_port);

//        Connection conn = TestUtil.getConnection(cp);
//        conn.createStatement().execute(
//            "create table XATT2 (i int, text char(10))");
//        conn.close();
      }

      // int netport = startNetserverAndReturnPort();

      ClientXADataSource xaDataSource = new ClientXADataSource();
      // (ClientXADataSource)TestUtil.getXADataSource(TestUtil.NetClientXADsClassName);
      xaDataSource.setServerName("localhost");
      xaDataSource.setPortNumber(33210);

      xaDataSource.setDatabaseName("GemFireXD");
      xaDataSource.setConnectionAttributes("logConnections=true;traceDirectory=/soubhik/builds/gfxd.1/logs/;");
      // get the stuff required to execute the global transaction
      xaDataSource.setSecurityMechanism(ClientXADataSource.CLEAR_TEXT_PASSWORD_SECURITY);
      //XAConnection xaConn = xaDataSource.getXAConnection("app", "app");
      //Connection xconn = xaConn.getConnection();

      // start the transaction with that xid
      byte[] gid = new byte[64];
      byte[] bid = new byte[64];
      for (int i = 0; i < 64; i++) {
        gid[i] = (byte)i;
        bid[i] = (byte)(64 - i);
      }
      //Xid xid = new ClientXid(0x1234, gid, bid);

      // XAResource xaRes = xaConn.getXAResource();
      // xaRes.start(xid, XAResource.TMNOFLAGS);

//      xconn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
//      // do some work
//      Statement stm = xconn.createStatement();
//      stm.execute("create table XATT2 (i int, text char(10))");
//      xconn.commit();
//      stm.close();
//      stm = xconn.createStatement();
//      stm.execute("insert into XATT2 values (1234, 'Test_Entry')");
//      stm.close();
//      xconn.commit();
//
//      stm = getConnection().createStatement();
//      stm.execute("select * from XATT2");
//      ResultSet rs = stm.getResultSet();
//      assertFalse(rs.next());
//      // end the work on the transaction branch
//      // xaRes.end(xid, XAResource.TMSUCCESS);
//      // xaRes.prepare(xid);
//      // xaRes.commit(xid, false);
//      stm.execute("select * from XATT2");
//      rs = stm.getResultSet();
//      assertTrue(rs.next());
    } finally {
      TestUtil.shutDown();
      shutdownSecondaryServers();
    }
  }

// driver location: /gcm/where/jdbcdriver/oracle/jdbc/lib/classes12.jar
//
//  public static Connection getOraConnection() throws Exception {
//    Class.forName("oracle.jdbc.driver.OracleDriver");
//    String url = "jdbc:oracle:thin:@trout.pune.gemstone.com:1521:tr10";
//    Connection connection = DriverManager
//        .getConnection(url, "SYSMAN", "oracle");
//    return connection;
//  }
  
  public void testQueryPlan() throws Exception {
    
    try {

      LAUNCH_PEER_SERVER = false;
      debugNumSecondaryServer = 2;
      secServerCount = 2;
      launchIndividualLocator = false;
      launchSecondaryServers();

      Properties cp = new Properties();
      makeClient(cp);

//      cp.setProperty("mcast-port", available_port);
//      cp.setProperty("gemfirexd.auth-provider", "BUILTIN");
//      cp.setProperty("gemfirexd.user.Soubhik", "Soubhik");
      
      Connection conn = TestUtil.getConnection(cp);
//      Connection conn = startNetserverAndGetLocalNetConnection(cp); 
//      Connection conn = TestUtil.getNetConnection("pc27", 33314, null, null);
      DatabaseMetaData dbm = conn.getMetaData();
      System.out.println(dbm.getDriverName());
      
      Statement st = conn.createStatement();
      ResultSet rs = dbm.getTables((String)null, null,
          "course".toUpperCase(), new String[] { "TABLE" });
      boolean found = rs.next();
      rs.close();

      if (found) {
        st.execute("drop table course ");
      }
      
      rs = dbm.getTables((String)null, null,
          "games".toUpperCase(), new String[] { "TABLE" });
      found = rs.next();
      rs.close();

      if (found) {
        st.execute("drop table games ");
      }
      
      st.execute("CREATE TABLE GAMES ( " +
          "PLAYER_ID CHAR(8) NOT NULL," +
          "YEAR_NO      BIGINT NOT NULL," +
          "TEAM      CHAR(3) NOT NULL," +
          "WEEK      BIGINT NOT NULL," +
          "OPPONENT  CHAR(3) ," +
          "COMPLETES BIGINT ," +
          "ATTEMPTS  BIGINT ," +
          "PASSING_YARDS BIGINT ," +
          "PASSING_TD    BIGINT ," +
          "INTERCEPTIONS BIGINT ," +
          "RUSHES BIGINT ," +
          "RUSH_YARDS BIGINT ," +
          "RECEPTIONS BIGINT ," +
          "RECEPTIONS_YARDS BIGINT ," +
          "TOTAL_TD BIGINT" +
       ") " );
      
      ResultSet colM = conn.getMetaData().getColumns(null, null, "GAMES", "YEAR_NO");
      while(colM.next()) {
        String cname = colM.getString("COLUMN_NAME");
        int ctype = colM.getInt("DATA_TYPE");
        System.out.println(cname + " of type " + ctype );
      }
      
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
//      st
//      .execute("update course c set i = (select i from course d where d.i < c.i group by i fetch first row only ) where c.course_id in (1,2,3) ");
      
//      st.execute("call syscs_util.set_explain_connection(1)");
//      st.execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
//      
      for (int i = 0; i < 1; i++) {
        rs = st
            .executeQuery(
                //"explain update course c set i = (select i from course d where d.i < c.i group by i fetch first row only ) where c.course_id in (1,2,3) ");
            //"explain select * from course c join course d on c.course_id = d.course_id where c.course_id > 1 or d.course_id <= 1 order by c.i desc ");
//            "select * from course c join course d on c.course_id = d.course_id where c.course_id > 1 or d.course_id <= 1 order by c.i desc ");
                "explain select * from course where course_name = ? parameter values ('d') ");
        int row = 0;
        while (rs.next()) {
          System.out.println(rs.getString(1));
        }
        rs.close();
      }
//      st.execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(0)");
//      st.execute("call syscs_util.set_explain_connection(0)");
      
      Connection eConn = TestUtil.getConnection(cp);
      ResultSet qp = eConn.createStatement().executeQuery("select stmt_id, stmt_text from sys.statementplans");

      boolean atleastOnePlan = false;
      while(qp.next()) {
        String uuid = qp.getString(1);
        System.out.println("Extracting plan for " + uuid);
        final ExecutionPlanUtils plan = new ExecutionPlanUtils(eConn, uuid, null, true);
        System.out.println("plan : " + new String(plan.getPlanAsText(null)));
        atleastOnePlan = true;
      }
      
      assertTrue(atleastOnePlan);

    } finally {
      TestUtil.shutDown();
      shutdownSecondaryServers();
    }

  }
  
  public void __testGrant() throws Exception {
    System.setProperty(com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION, "true" );
    Connection conn = getConnection();
    Statement st = conn.createStatement();
    
    conn.createStatement().execute(" create table student ( " + "st_id int, c_id int," +
        "primary key(st_id, c_id)" + ") replicate ");
    
    st.execute("grant select (st_id,c_id) on student to public");
  }

  public void __testPrimayKeyGets() throws Exception {
    final Connection conn = getConnection();
    int totalRows = 20;
    prepareTableWithOneKey(totalRows, conn);
    prepareTableWithTwoKey(totalRows, conn);

    Object queries[][][] = {
        { { conn.prepareStatement("select * from Account where id = ?") },
            { new Integer(Types.INTEGER) } },
        {
            { conn.prepareStatement("select * from finOrder where id = ? "
                + "and account = ?") },
            // test auto sqlType promotion by derby
            { new Integer(Types.INTEGER) },
            { new Integer(Types.VARCHAR), new Integer(1), "Account " } },
        {
            { conn.prepareStatement("select * from finOrder where account = ? "
                + "and id = ?") },
            { new Integer(Types.VARCHAR), new Integer(1), "Account " },
            // test auto sqlType promotion by derby
            { new Integer(Types.INTEGER) } } };

    int totqry = queries.length;
    for (int qryRow = 0; qryRow < totqry; qryRow++) {

      for (int pkey = 1; pkey <= totalRows; pkey++) {
        ResultSet rs = null;
        PreparedStatement ps = (PreparedStatement)queries[qryRow][0][0];
        for (int prm_1 = 1; prm_1 < queries[qryRow].length; prm_1++) {
          int paramType = ((Integer)queries[qryRow][prm_1][0]).intValue();
          switch (paramType) {
            case Types.VARCHAR:
              // if additional string is a prefix
              if (((Integer)queries[qryRow][prm_1][1]).intValue() == 1) {
                ps.setObject(prm_1, String.valueOf(queries[qryRow][prm_1][2])
                    + String.valueOf(pkey), Types.VARCHAR);
              }
              break;
            default:
              // 1 based parameter
              ps.setObject(prm_1, new Integer(pkey),
                  ((Integer)queries[qryRow][prm_1][0]).intValue());
              break;
          }
        }

        rs = ps.executeQuery();
        assertTrue("Got no result for query "
            + ((EmbedPreparedStatement)ps).getSQLText(),
            rs != null && rs.next());

        assertEquals(rs.getObject("id").toString(), String.valueOf(pkey));
        assertFalse(rs.next());

        rs.close();
      }
    }
  }

  public void __testForumIssue() throws Exception {
  {
//        Connection oracon = getOraConnection();
        Connection oracon = null;
        Statement st = oracon.createStatement();
        
        st.execute("drop table course ");
        st.execute("create table course (" +
        "course_id int, course_name varchar(20), " +
        " primary key(course_id)" +
        ") " );
        
        st.execute("insert into course values(1, 'ONE')");
        st.execute("insert into course values(2, 'TWO')");
        st.execute("insert into course values(3, 'THREE')");
        st.execute("insert into course values(4, 'FOUR')");
        st.execute("insert into course values(5, 'FIVE')");
        
        st.close();
        oracon.close();
    }

    
    Connection conn = getConnection();
    Statement st = conn.createStatement();
  
//    conn.createStatement().execute(" create table course ( " + "course_id int, course_name varchar(20)," +
//     "primary key(course_id)" + ") replicate " +
//     "LOADER (" + JDBCRowLoader.class.getName() + ".create 'mystring', 1)");
    
    conn.createStatement().execute(" create table student ( " + "st_id int, c_id int," +
        "primary key(st_id, c_id)" + ") replicate ");
    
    st.execute("insert into student values(1, 1)");
    st.execute("insert into student values(1, 2)");
    st.execute("insert into student values(1, 3)");
    st.execute("insert into student values(2, 1)");
    st.execute("insert into student values(2, 4)");

    
    ResultSet st_st = st.executeQuery("select c_id from student where st_id = 1 and c_id not in (select distinct course_id from course) ");
    
    PreparedStatement c_ps = conn.prepareStatement("select * from course where course_id = ? ") ;
    
    while(st_st.next()) {
      int i = st_st.getInt(1);
      c_ps.setInt(1, i);
      ResultSet r = c_ps.executeQuery();
      while(r.next()) {
        System.out.println(r.getLong("course_id"));
        System.out.println(r.getString("course_name"));
      }
    }

    
    st_st = st.executeQuery("select c_id from student where st_id = 2 and c_id not in (select distinct course_id from course) ");
    
    while(st_st.next()) {
      c_ps.setInt(1, st_st.getInt(1));
      ResultSet r = c_ps.executeQuery();
      while(r.next()) {
        System.out.println(r.getLong("course_id"));
        System.out.println(r.getString("course_name"));
      }
    }
    
  }
  
  public void __testSQLMatcher() throws ParseException, StandardException, SQLException {
    EmbedConnection conn = (EmbedConnection)getConnection();
    StatementContext stmtctx = null ;
    CompilerContext cc = null ;
    LanguageConnectionContext lcc = null;
    try {
      String sql = 
"select * from tab where ID = 'ePp@cXB8187@aEwuVo587w~HLjD&3618uqY!$qE053^@^@^@^@^@ ^@^@^@^@' ";

      lcc = conn.getLanguageConnection();
      stmtctx = lcc
          .pushStatementContext(true, true, sql, null, false, 0L);
      cc = lcc.pushCompilerContext();

//          SQLMatcher p = new SQLMatcher(new UCode_CharStream(
//              new java.io.StringReader(sql), 1, 1, 128));
//
//      p.setCompilerContext(cc);
//
////      StatementNode st =
//        p.Statement(sql, new Object[] {});
//      
////      st.treePrint();
      Parser p = cc.getMatcher();
      
//    new SQLMatcher(new UCode_CharStream(new java.io.StringReader(
//        this.statementText), 1, 1, 128));
//
//    p.setCompilerContext(cc);
    
//    if (!lcc.getRunTimeStatisticsMode()) {
//      p.disable_tracing();
//    }

      p.matchStatement(sql, null);

      
      StringBuilder generalizedStmtStr = new StringBuilder(sql.length());
      ArrayList<com.pivotal.gemfirexd.internal.engine.sql.compile.Token> constList = cc.getConstantTokenLists();

      if(constList.size() == 0) {
        //if there is no constants, no need to store generalized version.
        SanityManager.DEBUG_PRINT(
            GfxdConstants.TRACE_STATEMENT_MATCHING,
            "NO Constants found ");
      }
  
       //now that we know there are constants ... lets skip them and
      // build the generic version of the statement
      int srcPos = 0;
      for(com.pivotal.gemfirexd.internal.engine.sql.compile.Token v : constList) {
        final int beginC = v.beginOffset;
        assert beginC > srcPos;
        final int cLen = beginC - srcPos;
        final char[] c = new char[cLen];
        sql.getChars(srcPos, beginC, c, 0);
        srcPos = v.endOffset;
          
        SanityManager.DEBUG_PRINT(
            GfxdConstants.TRACE_STATEMENT_MATCHING,
            sql + " skipping constant: " + v);
        generalizedStmtStr.append(c).append("<?>"); //v.valueImage.getTypeName());
      }
      
      final int cLen = sql.length();
      if(srcPos < cLen) {
        final char[] c = new char[cLen - srcPos];
        sql.getChars(srcPos, cLen, c, 0);
        generalizedStmtStr.append(c);
      }
      
      SanityManager.DEBUG_PRINT("sb", "Generic Stmt "
              + generalizedStmtStr.toString());
      
    } finally {

       if(stmtctx != null) {
         lcc.popStatementContext(stmtctx, null);
       }
       
       if( cc != null) {
         lcc.popCompilerContext(cc);
       }
    }
  }
  

  /**
   * This method is for debugging purpose only. Real test is in DUnit and will
   * get tested.
   * 
   */
  /*
   * select R1/13 from (values(27+81+9+18+36+45+63+99+117+54+72+90+108 )) as T(R1)
   * 
   * "select * from Account where id between CURRENT_TIMESTAMP and {fn TIMESTAMPADD(SQL_TSI_DAY,29, CURRENT_TIMESTAMP)}"
   * select id, sum(id) from testtable group by id having avg(id) < (select avg(discount) from testtable)
   * select * from (select sum(discount) as aSUM from testtable) X order by aSum/2 -NPE
   * select distinct src+src2 from testtable order by quantity+discount - wrong results
   * 
   * multitable joins
   * select src, max(s.price) * min(t.discount), s.id from testtable t, source s where s.id = src group by src, s.id having count(src) > 2
   * 
   */
  private final boolean withIndex = false;
  
    public void testGroupBy() throws Exception {
        try {
    
          LAUNCH_PEER_SERVER = false;
          debugNumSecondaryServer = 1;
          secServerCount = 1;
          launchIndividualLocator = false;
          launchSecondaryServers();
    
          Properties cp = new Properties();
          makeClient(cp);
    
    //      cp.setProperty("mcast-port", available_port);
          
          Connection conn = TestUtil.getConnection(cp);
    ////      Connection conn = startNetserverAndGetLocalNetConnection(cp); 
    //      Connection conn = TestUtil.getNetConnection("localhost", 33213, null, null);
    //      DatabaseMetaData dbm = conn.getMetaData();
    //      System.out.println(dbm.getDriverName());
          
          Statement st = conn.createStatement();
          
          st.execute("drop table if exists course ");
          
          st.execute("create table course ("
              + "course_id int, i int, course_name varchar(10) "
              + ", primary key(course_id)" + ") partition by primary key");
          
          st.execute("create index c_name on course(course_name) ");
          
          st.execute("create view course_view as select course_name, i, course_id from course");
          
          st.execute("insert into course values (1, 11, 'one'), (2, 22, 'two'), (3,33, 'three')");
          
          ResultSet r = st.executeQuery("select count(course_name) from (select course_name, i, course_id from course) T group by course_name ");
          while(r.next()) {
            System.out.println(r.getObject(1));
//            System.out.println(r.getObject(2));
          }
    
        } finally {
          TestUtil.shutDown();
          shutdownSecondaryServers();
        }
    
      }

  
  
  public void __testOverflow() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 (c1 varchar(10), c2 int, c3 char(20), primary key (c1, c2) ) "); //+" eviction by lrucount 1 evictaction overflow synchronous ");

    s.execute("insert into t1 (c1, c2, c3) values ('10', 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values ('20', 20, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values ('30', 30, 'YYYY')");

    s.execute("create index i1 on t1 (c2,c3)");
    
    s.execute("delete  from t1 where c1='10'");

    s.executeQuery("select * from t1 where c1 < '30' order by c1");
    
    s.execute("update t1 set c3 = 'xxx'");
  }
  
  public void __testDebugAnyQuery() throws SQLException {

    try {
      Properties info = new Properties();
      
      if (LAUNCH_PEER_SERVER) {
        
        // client node
        info.setProperty("host-data", "false");
        
        info.setProperty("gemfire.mcast-port", "0");
      }
      else {
        info.setProperty("mcast-port", "0");
        // info.setProperty("gemfire.start-locator", "localhost[" +
        // available_port
        // + "]");
      }
      info.setProperty("log-level", "fine");
      if (!info.containsKey("host-data")) {
        // default is too server...
        info.setProperty("host-data", "true");
      }
//      info.setProperty("locators", "pc27[" + available_port + "]");
      info.put(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, "Soubhik");
      info.put(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "Soubhik");
      
//      info.put("gemfire.security-log-level","finest");
      Connection conn = TestUtil.getConnection(info);
      Statement st = conn.createStatement();

//      st.execute("CALL SYSCS_UTIL.SET_RUNTIMESTATISTICS(1)");
      st.execute("CALL SYSCS_UTIL.SET_STATEMENT_STATISTICS(1)");
      st.execute("CALL SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
//      st.execute("values cast(B'1' as blob(10))");

      try {
      st.execute("drop table flights");
      } catch (Exception ignore) {      }
      st.execute("CREATE TABLE FLIGHTS" + "(" + "FLIGHT_ID int NOT NULL, DUM_ID int not null, upd_id int,  primary key(flight_id) ) "); //, address varchar(1024) default 'xxx',  primary key (flight_id) ) partition by list (flight_id) (values(1,2), values(3,4))");
//      st.execute("alter TABLE FLIGHTS add column sid int not null default 5");
      conn.commit();
      
//      PreparedStatement tpst = conn.prepareStatement("-- \\*****\\ \n" +
//      		" create trigger flt_1 after update on FLIGHTS " +
//      		" referencing old as oldRow for each row " +
//      		" insert into flights values(101)");
//
//      tpst.execute();
      st
          .execute("insert into flights(flight_id, dum_id, upd_id) values"
              + "(2,1,1) "
              + ",(4, 1, 3)" 
              + ",(3, 4, 2)");
//              + "('1', 1, 'boeing 747',900,59,1) "
//              + ",('2', 2, null,200, 1, 59)");
//              + ",('3', 3, 'airbus A300',300, 21, 1),"
//              + "('4', 4, null,200, 1, 21),"
//              + "('5', 5, 'canadair RJ200',798, 32, 4),"
//              + "('6', 6, 'embraer 145',864, 5, 31),"
//              + "('7', 7, null,339, 99, 1),"
//              + "('8', 8, 'boeing 747',585, 33, 3),"
//              + "('9', 9, null,392, 19, 3),"
//              + "('10', 10, null,4028, 1, 99),"
//              + "('11', 11, null,7649, 66, 66),"
//              + "('12', 12, 'boeing 747',88432, 66, 66 ),"
//              + "('13', 13, 'embraer 145',9947, 7, 2),"
//              + "('14', 14, '',99632, 2, 7)");

////      st.execute("update flights set flight_id = 500");
//      PreparedStatement ins = conn
//      .prepareStatement("insert into flights(flight_id, dum_id, upd_id) values (?,?,?)");
//      ins.setInt(1, 4);
//      ins.setInt(2, 4);
//      ins.setInt(3, 4);
//      ins.execute();
//      
      st.execute("update flights set upd_id = 3 where flight_id = 4");

//      ResultSet mem = st.executeQuery("select * from flights where flight_id =101 ");
//      while (mem.next()) {
//        System.out.println( mem.getObject(1).toString());
//      }
//
//      PreparedStatement ps_t = conn.prepareStatement("select * from flights where flight_id = ?");
//      ps_t.setInt(1, 3);
//      mem = ps_t.executeQuery();
//      while (mem.next()) {
//        System.out.println( mem.getObject(1).toString());
//      }
//      
      ResultSet mem = st.executeQuery("select afght.flight_id as fla_id, afght.*, bfght.flight_id as flb_id from flights afght join flights bfght on afght.flight_id = bfght.flight_id ");
      while (mem.next()) {
        System.out.println( mem.getObject(1).toString());
      }
      
      conn.close();
      if(true)
        return;

      ResultSet rs = conn
          .createStatement()
          .executeQuery(
              "select * from flights f where f.flight_id > '1' order by aircraft ASC OFFSET 10 ROWS ");
//      // .executeQuery("select O.id, (select ah.type from AccHolder AS ah --GEMFIREXD-PROPERTIES index=acc_type\n join Account ac on ah.accid = ac.id where ac.id = O.id fetch first row only) "
//      // +
//      // "  from OuterTable O");
//      // .executeQuery("SELECT * FROM (" +
//      // "SELECT ROW_NUMBER() OVER() as rownum, ah.type from accholder ah --GEMFIREXD-PROPERTIES index=acc_type\n join account ac on ac.id = ah.id"
//      // +
//      // ") AS TAB_ACC where rownum = 1");
//      while (rs.next()) {
//        int num1 = rs.getInt(1);
//        System.out.println("id=" + num1);
//      }
//      rs.close();
//      System.err.println("SUCCESS");
//      if (true)
//        return;

      // prepareTableWithOneKey(conn, 10, totalRows);
      // Statement s = conn.createStatement();
      // String createFunction="CREATE FUNCTION times " +
      // "(value INTEGER) " +
      // "RETURNS INTEGER "+
      // "LANGUAGE JAVA " +
      // "EXTERNAL NAME 'com.pivotal.gemfirexd.functions.TestFunctions.times' "
      // +
      // "PARAMETER STYLE JAVA " +
      // "NO SQL " +
      // "RETURNS NULL ON NULL INPUT ";
      //      
      // conn.createStatement().execute(createFunction);
      // s.execute("create table orders"
      // + "(id int not null , cust_name varchar(200), vol int, "
      // + "security_id varchar(10), num int, addr varchar(100))");
      /*
      PreparedStatement ps = conn.prepareStatement("select sum( (id/?)+(discount-?) ) from testtable " +
             " having avg(id)+?-count(id)/? <= avg(discount)*?/count(id)-cast(? as float) and max(id) not in (?,?,?,?) ");
      for(int it=0; it < 1; it++) {
        ps.setInt(1, 10);
        ps.setInt(2, 210);
        
        ps.setInt(3, 1);
        ps.setInt(4, 100);
        ps.setInt(5, 20);
        ps.setFloat(6, 0.1F);
        ps.setInt(7, 11);
        ps.setInt(8, 12);
        ps.setInt(9, 14);
        ps.setInt(10, 15);
       */

      PreparedStatement ps = conn
          .prepareStatement("select count(src2) from testtable "
          // + " group by substr(description, 1, length(description)-1 ) "
              + "  having sum(distinct src) = ? ");

      for (int it = 0; it < 1; it++) {
        ps.setInt(1, 15);
        // ps.setInt(2, 210);
        int rw = 0;
        ResultSet rs__1 = ps.executeQuery();// es.executeUpdate();
        String col1 = new String(), col2 = new String(), col3 = new String(), col4 = new String(), col5 = new String(), type = new String();
        while (rs.next()) {
          rw++;
          // if(amt == -1)
          // amt = rs.getInt(1);
          // else
          // assert amt == rs.getInt(1): "failed to match the sum";
          col1 += String.valueOf(rs.getObject(1)) + ",";
          // col2 += String.valueOf(rs.getObject(2)) + ",";
          // col3 += String.valueOf(rs.getObject(3)) + ",";
          // col4 += String.valueOf(rs.getObject(4)) + ",";
          // col5 += String.valueOf(rs.getObject(5)) + ",";
          if (rw == 1) {
            type += String.valueOf(rs.getObject(1).getClass())
            // + " " + String.valueOf(rs.getObject(2).getClass())
            // + " " + String.valueOf(rs.getObject(3).getClass())
            // + " " + String.valueOf(rs.getObject(4).getClass())
            // + " " + String.valueOf(rs.getObject(5).getClass())
            ;
          }
        }
        System.err.println(type);
        System.err.println(col1);
        System.err.println(col2);
        System.err.println(col3);
        System.err.println(col4);
        System.err.println(col5);
        System.err.println("total = " + rw);
        //        
        if (rs.getWarnings() != null)
          System.err.println(rs.getWarnings().toString());

        rs.close();
      }
    } catch (Exception ignore) {
      System.out.println("exception in SelectsToGetTest: " + ignore);
    } finally {
      TestUtil.shutDown();
    }

  }

  public void __testExecuteStatement_1PrimaryKey() throws SQLException,
      InstantiationException, IllegalAccessException {
    Connection conn = getConnection();
    int totalRows = 20;
    prepareTableWithOneKey(conn, 1, totalRows);

    Random rand = new Random(System.currentTimeMillis());
    // PreparedStatement ps =
    // conn.prepareStatement("select * from Account where id between CURRENT_TIMESTAMP and {fn TIMESTAMPADD(SQL_TSI_DAY,29, CURRENT_TIMESTAMP)}");
    PreparedStatement ps = conn
        .prepareStatement("select * from Account where id  = ?");

    for (int i = 1; i <= 10; i++) {
      int pkey = rand.nextInt(totalRows) + 1;

      ps.setString(1, String.valueOf(pkey));
      ResultSet rs = ps.executeQuery();
      assertTrue(rs.next());

      assertTrue(Integer.valueOf((String)rs.getObject("id")).intValue() == pkey);
      System.out.println("lookup " + pkey + ": (" + rs.getObject("id") + ","
          + rs.getObject("name") + "," + rs.getObject("type") + ")");
      assertFalse(rs.next());

      rs.close();
    }

  }

  public void _testExecuteStatement_2PrimaryKey() throws SQLException {
    Connection conn = getConnection();
    int totalRows = 20;
    prepareTableWithTwoKey(conn, totalRows);

    Random rand = new Random(System.currentTimeMillis());
    PreparedStatement ps = conn
        .prepareStatement("select name, account, type, id  from finOrder where id = ? and account = ?");

    for (int i = 1; i <= 10; i++) {
      int pkey = rand.nextInt(totalRows) + 1;

      ps.setInt(1, pkey);
      ps.setString(2, "Account " + String.valueOf(pkey));
      ResultSet rs = ps.executeQuery();
      rs.next();

      assertTrue(((Long)rs.getObject("id")).longValue() == pkey);
      assertEquals(rs.getString(2), ("Account " + String.valueOf(pkey)));
      assertTrue(rs.next() == false);
    }
  }

  
  public void __testQueryPlanPattern() {
    String samplePlan = 
//    "ORIGINATOR pc27(24739)<v3>:45000/47964 BEGIN TIME 2012-02-17 19:39:49.88 END TIME 2012-02-17 19:39:50.062 " +
//    "DISTRIBUTION to 3 members took 3210 microseconds  ( message sending min/max/avg time 535/952/2036 microseconds and receiving min/max/avg time 171/1067/1444 microseconds )" + 
//    "ROUNDROBIN-ITERATION of 5 rows took 88738 microseconds" +
//    "ORDERED-ITERATION of 5 rows with 5 input rows took 89897 microseconds" + 
//    "" +
    "Slowest Member Plan:" + 
    "member   pc27(24827)<v1>:40987/57389 begin_execution  2012-02-17 19:39:49.888 end_execution  2012-02-17 19:39:50.061" +
    "QUERY-RECEIVE execute_time 172454281 member_node pc27(24739)<v3>:45000/47964" +
    " RESULT-SEND execute_time 178437 member_node pc27(24739)<v3>:45000/47964" +
    "  RESULT-HOLDER returned_rows 2 no_opens 1 execute_time 2054449" +
    "  SORT input_rows 2 returned_rows 2 no_opens 1 execute_time 8505857 sort_type IN sorter_output 2" +
    "   NLJOIN returned_rows 2 no_opens 1 execute_time 155661" +
    "TABLESCAN returned_rows 2 no_opens 1 execute_time 68144194 scan_qualifiers 42Z37.U :  scanned_object COURSE scan_type HEAP" +
    "FILTER returned_rows 2 no_opens 2 execute_time 174608" +
    "ROWIDSCAN returned_rows 2 no_opens 2 execute_time 210261" +
    "CONSTRAINTSCAN returned_rows 2 no_opens 2 execute_time 33192353 scan_qualifiers 42Z37.U :  scanned_object SQL120217193949320 scan_type"; 
    
/*    "" +
    "Fastest Member Plan:" + 
    "member   pc27(24902)<v2>:15874/56135 begin_execution  2012-02-17 19:39:49.888 end_execution  2012-02-17 19:39:49.972" +
    "QUERY-RECEIVE execute_time 81895568 member_node pc27(24739)<v3>:45000/47964" +
    "RESULT-SEND execute_time 156958 member_node pc27(24739)<v3>:45000/47964" +
    "RESULT-HOLDER returned_rows 1 no_opens 1 execute_time 1915697" +
    "SORT input_rows 1 returned_rows 1 no_opens 1 execute_time 959844 sort_type IN sorter_output 1" +
    "NLJOIN returned_rows 1 no_opens 1 execute_time 125717" +
    "TABLESCAN returned_rows 1 no_opens 1 execute_time 6082807 scan_qualifiers 42Z37.U :  scanned_object COURSE scan_type HEAP" +
    "FILTER returned_rows 1 no_opens 1 execute_time 124568" +
    "ROWIDSCAN returned_rows 1 no_opens 1 execute_time 161989" +
    "CONSTRAINTSCAN returned_rows 1 no_opens 1 execute_time 21959104 scan_qualifiers 42Z37.U :  scanned_object SQL120217193949320 scan_type";
*/    
    StringBuilder patternString = new StringBuilder();
    String timeStampPattern = "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9](\\.[0-9][0-9][0-9])?";
    String memberIdPattern = ".*\\<v[0-9]([0-9]*)?\\>:[0-9]([0-9]*)?/[0-9]([0-9]*)?";
    String executeNanos = "[0-9]([0-9]*)?";
    
    // Slowest Member Plan:member   pc27(24827)<v1>:40987/57389 begin_execution  2012-02-17 19:39:49.888 end_execution  2012-02-17 19:39:50.061
    patternString.append("Slowest Member Plan:\\s*member\\s*").append(memberIdPattern).append("\\s*begin_execution\\s*").append(timeStampPattern).append("\\s*end_execution\\s*").append(timeStampPattern);
    
    Pattern planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    
    String[] planMemberDone = planMatcher.split(samplePlan);
    System.out.println(java.util.Arrays.toString(planMemberDone));
    assert planMemberDone.length == 2;
    
    // QUERY-RECEIVE execute_time 81895568 member_node pc27(24739)<v3>:45000/47964
    // RESULT-SEND execute_time 156958 member_node pc27(24739)<v3>:45000/47964
    patternString = new StringBuilder();
    patternString
        .append("\\s*QUERY-RECEIVE\\s*execute_time\\s*[0-9]([0-9]*)?\\s*member_node\\s*").append(memberIdPattern).append("\\s*RESULT-SEND execute_time ").append(executeNanos).append(" member_node ").append(memberIdPattern);

    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    
    String[] queryRecRespSendDone = planMatcher.split(planMemberDone[1]);
    System.out.println(java.util.Arrays.toString(queryRecRespSendDone));
    assert queryRecRespSendDone.length == 2;
    
    //RESULT-HOLDER returned_rows 2 no_opens 1 execute_time 2054449
    patternString = new StringBuilder();
    patternString
    .append("\\s*RESULT-HOLDER returned_rows 2 no_opens 1 execute_time ").append(executeNanos);
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    
    String[] resultHolderDone = planMatcher.split(queryRecRespSendDone[1]);
    System.out.println(java.util.Arrays.toString(resultHolderDone));
    assert resultHolderDone.length == 2;
    
    //SORT input_rows 1 returned_rows 1 no_opens 1 execute_time 959844 sort_type IN sorter_output 1
    patternString = new StringBuilder();
    patternString
    .append("SORT input_rows 2 returned_rows 2 no_opens 1 execute_time ").append(executeNanos).append(" sort_type IN sorter_output 2");
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    
    String[] sortDone = planMatcher.split(resultHolderDone[1]);
    System.out.println(java.util.Arrays.toString(sortDone));
    assert sortDone.length == 2;

    patternString = new StringBuilder();
    patternString
    .append("NLJOIN returned_rows 2 no_opens 1 execute_time ").append(executeNanos);
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] nlJoinDone = planMatcher.split(sortDone[1]);
    System.out.println(java.util.Arrays.toString(nlJoinDone));
    assert nlJoinDone.length == 2;

    patternString = new StringBuilder();
    patternString
    .append("TABLESCAN returned_rows 2 no_opens 1 execute_time ").append(executeNanos).append(" scan_qualifiers 42Z37.U :  scanned_object COURSE scan_type HEAP");
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] tableScanDone = planMatcher.split(nlJoinDone[1]);
    System.out.println(java.util.Arrays.toString(tableScanDone));
    assert tableScanDone.length == 2;

    patternString = new StringBuilder();
    patternString
    .append("FILTER returned_rows 2 no_opens 2 execute_time ").append(executeNanos);
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] filterDone = planMatcher.split(tableScanDone[1]);
    System.out.println(java.util.Arrays.toString(filterDone));
    assert filterDone.length == 2;

    patternString = new StringBuilder();
    patternString
    .append("ROWIDSCAN returned_rows 2 no_opens 2 execute_time ").append(executeNanos);
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] rowIDScanDone = planMatcher.split(filterDone[1]);
    System.out.println(java.util.Arrays.toString(rowIDScanDone));
    assert rowIDScanDone.length == 2;
    
    patternString = new StringBuilder();
    patternString
    .append("CONSTRAINTSCAN returned_rows 2 no_opens 2 execute_time ").append(executeNanos).append(" scan_qualifiers 42Z37.U :  scanned_object SQL120217193949320 scan_type");
    planMatcher = Pattern.compile(patternString.toString(), Pattern.MULTILINE);
    String[] constraintScanDone = planMatcher.split(rowIDScanDone[1]);
    System.out.println(java.util.Arrays.toString(constraintScanDone));
    assert constraintScanDone.length == 0;
//    ""; 
    
//    System.out.println(planMatcher.matcher(planMemberDone[1]).find());
  }
  
  public void __testXML() {
    String s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<!-- GemFireXD Query Plan -->"
        + "<plan>"
        + "            <member>pc27(21012)&lt;v0&gt;:11477/51156</member>        <member>pc27(21012)&lt;v0&gt;:11477/51156</member>        <statement> select * from course c join course d on c.course_id = d.course_id where c.course_id &gt; 1 or d.course_id &lt;= 1 order by c.i desc </statement>"
        + "            <time>2012-02-17 17:45:16.623</time> "
        + "            <begin_exe_time>2012-02-17 17:45:16.544</begin_exe_time> "
        + "    <end_exe_time>2012-02-17 17:45:16.623</end_exe_time> "
        + "             <details> "
        + "                <node name=\"QUERY-RECEIVE\" execute_time=\"76666204\" member_node=\"pc27(20977)&lt;v1&gt;:31945/42892\"  depth=\"0\"> "
        + "                    <node name=\"RESULT-SEND\" execute_time=\"183604\" member_node=\"pc27(20977)&lt;v1&gt;:31945/42892\"  depth=\"1\"> "
        + "                        <node name=\"RESULT-HOLDER\" returned_rows=\"5\" no_opens=\"1\" execute_time=\"2127292\"  depth=\"2\"> "
        + "                            <node name=\"SORT\" input_rows=\"5\" returned_rows=\"5\" no_opens=\"1\" sort_type=\"IN\" sorter_output=\"5\" execute_time=\"1054252\"  depth=\"3\"> "
        + "                                <node name=\"NLJOIN\" returned_rows=\"5\" no_opens=\"1\" execute_time=\"170409\"  depth=\"4\"> "
        + "                                    <node name=\"TABLESCAN\" returned_rows=\"5\" no_opens=\"1\" scan_qualifiers=\"42Z37.U : \" scanned_object=\"COURSE\" scan_type=\"HEAP\" execute_time=\"6837633\"  depth=\"5\"> "
        + "                                    </node> "
        + "                                    <node name=\"FILTER\" returned_rows=\"5\" no_opens=\"5\" execute_time=\"187241\"  depth=\"5\"> "
        + "                                    </node> "
        + "                                </node> "
        + "                            </node> "
        + "                        </node> " + "                    </node> "
        + "                </node> " + "            </details> "
        + "    </plan> ";

    Element e = CreateXML.transformToXML(s.toCharArray());
    NodeList nl = e.getChildNodes();
    java.sql.Timestamp begin = null, end = null;
    for (int i = 0; i < nl.getLength(); i++) {
      Node n = nl.item(i);
      if (n == null) {
        continue;
      }
      else if (n.getNodeName().equalsIgnoreCase("begin_exe_time")) {
        begin = java.sql.Timestamp.valueOf(n.getTextContent());
      }
      else if (n.getNodeName().equalsIgnoreCase("end_exe_time")) {
        end = java.sql.Timestamp.valueOf(n.getTextContent());
      }
      else {
        continue;
      }
    }

    System.out.println(" time taken " + (end.getTime() - begin.getTime())
        + " millis ");
  }  

  private void prepareTableWithOneKey(Connection conn, int mode, int rows)
      throws SQLException, InstantiationException, IllegalAccessException {
    Statement s = conn.createStatement();

    switch (mode) {
      case 1: /*primary key in create statement*/
      {
        s.execute("create table Account ("
            + " id varchar(10) primary key, name varchar(100), type int )");
        s.execute("create index acc_idx1 on Account(id)");
        conn.commit();
        PreparedStatement ps = conn
            .prepareStatement("insert into Account values(?,?,?)");
        int baseChar = 65;
        int numRow = rows;
        while (numRow > 0) {
          try {
            String id = String.valueOf(numRow);
            String name = String
                .valueOf(new char[] { (char)(baseChar + (numRow % 5)) })
                + "First";
            int type = rows - numRow + 1;
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setInt(3, type);
            System.out.println("inserting (" + id + "," + name + "," + type
                + ")");
            ps.executeUpdate();
          } catch (SQLException e) {
            System.out.println(e.getMessage());
          }
          numRow--;
        }
        return;
      }
      case 2: /*primary key with range definition*/
        s.execute("create table Account ("
            + " id varchar(10) primary key, name varchar(100), type int )"
            + " partition by range(id)" + "   ( values between 'A'  and 'B'"
            + "    ,values between 'C'  and 'D'"
            + "    ,values between 'E'  and 'F'"
            + "    ,values between 'G'  and 'Z'"
            + "    ,values between '1'  and '5'"
            + "    ,values between '5'  and '15'"
            + "    ,values between '15' and '20'" + "   )");
        break;
      // s.execute("alter table Account add constraint acc_pk primary key(id)");
      case 3: /*primary key with list definition*/
        s.execute("create table Account ("
            + " id varchar(10) primary key, name varchar(100), type int )"
            + " partition by list(id) (" + "   values ('00','01') "
            + "  ,values ('02','03') " + "  ,values ('04','05') "
            + "  ,values ('06','07','08','09') "
            + "  ,values ('10','11','12','13') " + "  ,values ('14','15') "
            + "  ,values ('16','17') " + "   )");
        break;
      case 4: /*primary key with range definition for Like operation*/
        s.execute("create table Account ("
            + " id varchar(10) primary key, name varchar(100), type int )"
            + " partition by range(id)" + "   ( values between 'AA'  and 'AC'"
            + "    ,values between 'AC'  and 'AE'"
            + "    ,values between 'AE'  and 'AF'"
            + "    ,values between 'AF'  and 'AG'"
            + "    ,values between 'AG'  and 'AZ'"
            + "    ,values between 'AZ'  and 'BA'"
            + "    ,values between 'BA'  and 'BG'"
            + "    ,values between 'BGA'  and 'BGF'"
            + "    ,values between 'BGF'  and 'BGX'"
            + "    ,values between 'BGX'  and 'BZ'"
            + "    ,values between 'BZ'  and 'Z'"
            + "    ,values between 'Z'  and 'a'"
            + "    ,values between 'a'  and 'z'"
            + "    ,values between '1'  and '5'"
            + "    ,values between '5'  and '15'"
            + "    ,values between '15' and '20'" + "   )");
        break;
      case 5: /*simple table def to evaluate Asif's tests.*/
        s.execute("create table Account (ID int not null, "
            + " NAME varchar(1024), ADDRESS varchar(1024), primary key (ID))"
            + "PARTITION BY RANGE ( ID )"
            + " ( VALUES BETWEEN 1 and 4, VALUES BETWEEN  4 and 8 )");
        break;
      case 6:
        s
            .execute("create table TESTTABLE (ID int not null, "
                + " DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, NAME varchar(1024) not null, primary key (ID, DESCRIPTION,NAME))"
                + "PARTITION BY COLUMN ( DESCRIPTION,NAME )");
        break;

      case 7: // Testtable with PARTITION by PK
      {
        s
            .execute("create table TESTTABLE (ID int primary key, "
                + " DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, NAME varchar(1024) not null,"
                + " quantity int , price int)" + "");
        conn.commit();

        PreparedStatement ps = conn
            .prepareStatement("insert into TestTable values(?,?,?,?,?,?)");
        int baseChar = 65;
        int numRow = rows;
        while (numRow > 0) {
          try {
            // ps.setTimestamp(1, new
            // java.sql.Timestamp(System.currentTimeMillis()) );
            // ps.setString(1, String.valueOf(rows));
            ps.setInt(1, numRow);
            ps.setString(2, String
                .valueOf(new char[] { (char)(baseChar + (numRow % 5)) })
                + "First");
            ps.setString(3, (numRow % 7 == 0 ? "J 604"
                : (numRow % 5 == 0 ? "A 604" : "B 604")));
            ps.setString(4, (numRow % 5 == 0 ? "INDIA"
                : (numRow % 3 == 0 ? "US" : "ROWLD")));
            try {
              ps.setInt(5, Integer.MAX_VALUE);
              ps.setInt(6, Random.class.newInstance().nextInt(700));
            } catch (InstantiationException e) {
              throw GemFireXDRuntimeException.newRuntimeException(null, e);
            } catch (IllegalAccessException e) {
              throw GemFireXDRuntimeException.newRuntimeException(null, e);
            }
            ps.executeUpdate();
          } catch (SQLException e) {
            System.out.println(e.getMessage());
          }
          numRow--;
        }
      }
        return;
      case 8: // tables for equi joins.
      {
        s
            .execute("create table Source (ID varchar(20) constraint src_pk_id primary key,"
                + " DESCRIPTION varchar(1024) not null, "
                + " ADDRESS varchar(1024) not null"
                + "  )"
                + " PARTITION BY COLUMN (ID)");

        s
            .execute("create table Depend1 (ID int constraint Depend_pkID primary key,"
                + " DESCRIPTION varchar(1024) not null, ID1 varchar(20),"
                + " ADDRESS varchar(1024) not null, amount real, quantity int, discount int, price double"
                + ", constraint id1_fk foreign key (ID1) references source(id) )"
                + " PARTITION BY COLUMN (ID1) COLOCATE WITH (Source)");
        s.execute("create index depend1_idx on Depend1(ID1) ");
        s.execute("create index depend1_desc on Depend1(DESCRIPTION) ");
        s.execute("create index depend1_add on Depend1(ADDRESS) ");
        conn.commit();
        PreparedStatement ps = conn
            .prepareStatement("insert into Source values(?,?,?)");
        int numRow = rows;
        int baseChar = 65;
        while (numRow >= 0) {
          try {
            ps.setString(1, String.valueOf(numRow) + String.valueOf(numRow));
            ps.setString(2, String
                .valueOf(new char[] { (char)(baseChar + (numRow % 5)) })
                + "First");
            ps.setString(3, "J 604");
            // ps.setString(4, (numRow%5==0? "INDIA" : (numRow%3==0? "US" :
            // "ROWLD") ));
            ps.executeUpdate();
          } catch (SQLException e) {
            System.out.println(e.getMessage());
          }
          numRow--;
        }

        ps = conn
            .prepareStatement("insert into Depend1 values(?,?,?,?,?,?,?,?)");
        numRow = rows;
        baseChar = 65;
        while (numRow >= 0) {
          try {
            ps.setInt(1, numRow);
            ps.setString(2, (numRow % 2 == 0 ? "third"
                : (numRow % 5 <= 2 ? "First" : "second")));
            ps.setString(3, String.valueOf(numRow % 5)
                + String.valueOf(numRow % 5));
            ps.setString(4, (numRow % 3 == 0 ? "J 604"
                : (numRow % 5 == 0 ? "J 605" : "J 600")));
            ps.setFloat(5, (numRow % 5) * 7.98F);
            ps.setLong(6, Integer.MAX_VALUE);
            ps.setInt(7, (numRow * 10 - numRow));
            ps.setDouble(8, Random.class.newInstance().nextDouble());
            ps.executeUpdate();
          } catch (SQLException e) {
            System.out.println(e.getMessage());
          }
          numRow--;
        }
      }
        return;
      case 9: // for global index
        s.execute("create table Source (ID int primary key,"
            + " DESCRIPTION varchar(1024) not null, "
            + " ADDRESS varchar(1024) )" + " REPLICATE ");
        // s.execute("Create Index SIdx1 on Source(ID)");
        conn.commit();
        s.execute("Insert into SOURCE (ID, DESCRIPTION) values "
            + " (1, 'ONE')" + ",(2, 'TWO')" + ",(3, 'THREE')" + ",(4, 'FOUR')"
            + ",(5, 'FIVE')");

        break;
      case 10: {
        s
            .execute("create table TESTTABLE (ID int not null, "
                + " DESCRIPTION varchar(1024) , ADDRESS varchar(1024), COUNTRY varchar(1024), "
                + " QUANTITY bigint, PRICE double, DISCOUNT int, AMOUNT real, MULTIPLY double, INT_OVERFLOW int, "
                + " SRC int, SRC2 int," + " primary key (ID) )"
                + " PARTITION BY RANGE ( SRC )"
                + "  ( VALUES BETWEEN 1 and 4, VALUES BETWEEN  4 and 8 )"
                + " redundancy 3");

        s.execute("create table Source (ID int primary key,"
            + " DESCRIPTION varchar(1024) , "
            + " ADDRESS varchar(1024) not null," + " price real, yoy int)"
            + " Partition By Range (ID) "
            + "  ( VALUES BETWEEN 1 and 4, VALUES BETWEEN  4 and 8 ) "
            + " COLOCATE WITH (TESTTABLE) " + " redundancy 3");
        s.execute("Create Index SIdx1 on Source(ID)");

        if (withIndex) {
          s.execute("create index testtable_address on TESTTABLE(ADDRESS) ");
          s.execute("create index testtable_country on TESTTABLE(COUNTRY) ");
        }

        conn.commit();

        s.execute("Insert into SOURCE values " + " (1, null, 'US', 10.938, 5)"
            + ",(2, 'First1', 'ROWLD',20.8, 5)"
            + ",(3, 'First1', 'US',30.7, 4)"
            + ",(4, 'First11', 'US',40.938, 6)"
            + ",(5, 'First12', 'INDIA',1.38, 3)"
            + ",(6, null, 'INDIA',1000.938, 34)"
            + ",(7, 'First12', 'INDIA',53.558, 7)"
            + ",(8, 'First13', 'INDIA',45210.938, 5)"
            + ",(9, 'First13', 'INDIA',1321.938, 2)"
            + ",(10, 'First1', 'US',13240.938, 4)"
            + ",(11, 'First1', 'ROWLD',10.938, 6)"
            + ",(12, 'First13', 'INDIA',30.7, 7)"
            + ",(13, 'First1', 'ROWLD',40.938, 5)");

        Double dblPrice = Random.class.newInstance().nextDouble();
        for (int i = 1; i <= 13; ++i) {
          conn.createStatement().execute(
              "insert into TESTTABLE values ("
                  + (i)
                  + (i % 2 == 0 ? ", null" : ", 'First" + (i) + "'")
                  + ","
                  + (i % 2 == 0 && i > 4 ? "null" : (i % 3 == 0 ? "'J 604'"
                      : "'J 987'"))
                  + (i % 5 == 0 ? ",'INDIA'" : (i % 3 == 0 ? ",'US'"
                      : ",'ROWLD'")) + "," + (i * 100) + "," + dblPrice + ","
                  + (i * 10 - i) + "," + (i * 3.45F) + "," + (i + 0.98746D)
                  + "," + Integer.MAX_VALUE + "," + (i % 5 + 1) + ","
                  + (i % 2 + 1) + ")");
        }
      }
        return;
      default:
        break;
    }

    conn.commit();

    PreparedStatement ps = conn
        .prepareStatement("insert into Source values(?,?,?)");
    int numRow = rows;
    int baseChar = 65;
    while (numRow > 0) {
      try {
        ps.setInt(1, numRow);
        ps.setString(2, String
            .valueOf(new char[] { (char)(baseChar + (numRow % 5)) })
            + "First");
        ps.setString(3, "J 604");
        ps.executeUpdate();
      } catch (SQLException e) {
        System.out.println(e.getMessage());
      }
      numRow--;
    }

  }

  private void prepareTableWithTwoKey(Connection conn, int rows)
      throws SQLException {
    Statement s = conn.createStatement();

    s.execute("create table finOrder ("
        + " id bigint, name varchar(100), type int, account varchar(100), "
        + " constraint order_pk primary key(account, id ) )");

    PreparedStatement ps = conn
        .prepareStatement("insert into finOrder values(?,?,?,?)");
    while (rows > 0) {
      ps.setLong(1, rows);
      ps.setString(2, "Dummy Order " + String.valueOf(rows));
      ps.setInt(3, rows % 4);
      ps.setString(4, "Account " + String.valueOf(rows));
      ps.executeUpdate();
      rows--;
    }

  }

  private static void prepareTableWithOneKey(int rows, Connection conn)
      throws SQLException {

    Statement s = conn.createStatement();
    getLogger().info(" creating tables ");

    s.execute("create table Account ("
        + " id varchar(10) primary key, name varchar(100), type int )");
    conn.commit();

    getLogger().info(" populating values ");
    PreparedStatement ps = conn
        .prepareStatement("insert into Account values(?,?,?)");
    while (rows > 0) {
      ps.setString(1, String.valueOf(rows));
      ps.setString(2, "Dummy Account " + String.valueOf(rows));
      ps.setInt(3, rows % 2);
      ps.executeUpdate();
      rows--;
    }

  }

  private static void prepareTableWithTwoKey(int rows, Connection conn)
      throws SQLException {
    Statement s = conn.createStatement();

    s.execute("create table finOrder ("
        + " id bigint, name varchar(100), type int, account varchar(100), "
        + " constraint order_pk primary key(account, id ) )");

    conn.commit();
    getLogger().info(" populating values ");
    PreparedStatement ps = conn
        .prepareStatement("insert into finOrder values(?,?,?,?)");
    while (rows > 0) {
      ps.setLong(1, rows);
      ps.setString(2, "Dummy Order " + String.valueOf(rows));
      ps.setInt(3, rows % 4);
      ps.setString(4, "Account " + String.valueOf(rows));
      ps.executeUpdate();
      rows--;
    }
  }

  // -- for debugging purposes --- 
  public static void launchSecondaryServers() throws Exception {

    available_port = String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS));
    
    boolean before = LAUNCH_PEER_SERVER;
    
    System.setProperty("gemfire.CacheServerLauncher.dontExitAfterLaunch",
        Boolean.toString(LAUNCH_PEER_SERVER));

    LAUNCH_PEER_SERVER = Boolean
        .getBoolean("gemfire.CacheServerLauncher.dontExitAfterLaunch");
    
    assert LAUNCH_PEER_SERVER == before;

    final String hostname = "localhost";
    if (LAUNCH_PEER_SERVER) {
      boolean locatorConfigured = false;
      int debugPort = 1044;
      int clientPort = 33314;
      int currentServerCount = 0;

      if(launchIndividualLocator) { 
         GfxdDistributionLocator.main(new String[] {
             "start",
             "-peer-discovery-port=" + available_port,
             "-mcast-port=0",
             "-dir=" + baseLaunchPath + "loc",
             "-log-level=fine",
             "-host-data=false",
             runNetServer ? "-client-port=" + clientPort : "-run-netserver=false",
             runNetServer ? "-client-bind-address=" + hostname : "",
             "-gemfire.enable-time-statistics=true",
             "-statistic-sample-rate=100",
             "-statistic-sampling-enabled=true",
//             "-enable-stats=true",
//             "-enable-timestats=true",
             "-statistic-archive-file=server-" + "loc" + ".gfs",
             "-J-Dp2p.discoveryTimeout=1000",
             "-J-Dp2p.joinTimeout=2000",
             "-J-Dp2p.leaveTimeout=1000",
             "-J-Dp2p.socket_timeout=4000",
             "-J-Dp2p.disconnectDelay=500",
             "-J-Dp2p.handshakeTimeoutMs=2000",
             "-J-Dp2p.lingerTime=500",
             "-J-Dp2p.listenerCloseTimeout=4000"
             
           // "-J-Dgemfirexd.authentication.required=true",
//              ,"-J-Dgemfirexd.auth-provider=BUILTIN",
              ,"-J-Dgemfirexd.user.Soubhik=Soubhik",
              "-security-log-level=finest",
             // "-J-Dderby.language.logQueryPlan=true"
             // ,"-J-Dgemfire.DistributionAdvisor.VERBOSE=true"} );
             debugNumSecondaryServer > 0 ? "-J-Xdebug" : null,
             debugNumSecondaryServer > 0 ? "-J-Xrunjdwp:transport=dt_socket,suspend=y,server=y,address=localhost:" + debugPort : null,
             debugNumSecondaryServer > 0 ? "-J-Xms1g" : null, 
             debugNumSecondaryServer > 0 ? "-J-Xmx1g" : null
         // "-J-Xrunhprof:interval=1,depth=10,thread=y,verbose=n,heap=dump,format=b,file=server.hprof"
         });
         locatorConfigured = true;
         currentServerCount++;
         clientPort++;
      }
      
      for (; currentServerCount <= secServerCount; currentServerCount++) {

        GfxdServerLauncher
            .main(new String[] {
                "start",
                !locatorConfigured ? "-start-locator=localhost["
                    + available_port + "]" : null,
                "-locators=localhost[" + available_port + "]",
                "-mcast-port=0",
                "-dir=" + baseLaunchPath + currentServerCount,
                "-log-level=fine",
                "-host-data=true",
                runNetServer ? "-client-port=" + clientPort
                    : "-run-netserver=false",
                runNetServer ? "-client-bind-address=" + hostname : "",
                "-gemfire.enable-time-statistics=true",
                "-statistic-sample-rate=100",
                "-statistic-sampling-enabled=true",
                // "-enable-stats=true",
                // "-enable-timestats=true",
                "-statistic-archive-file=server-" + currentServerCount + ".gfs",
                // "-J-Dgemfirexd.authentication.required=true",
//                 "-J-Dgemfirexd.auth-provider=BUILTIN",
                 "-J-Dgemfirexd.user.Soubhik=" + AuthenticationServiceBase
                     .encryptPassword("Soubhik", "Soubhik"),
                 "-security-log-level=finest",
                 "-user=Soubhik",
                 "-password=Soubhik",
                // "-J-Dderby.language.logQueryPlan=true"
                // ,"-J-Dgemfire.DistributionAdvisor.VERBOSE=true"} );
                debugNumSecondaryServer > 0 ? "-J-Xdebug" : null,
                debugNumSecondaryServer > 0 ? "-J-Xrunjdwp:transport=dt_socket,suspend=y,server=y,address=localhost:"
                    + debugPort
                    : null, debugNumSecondaryServer > 0 ? "-J-Xms1g" : null,
                debugNumSecondaryServer > 0 ? "-J-Xmx1g" : null
                , "-J-javaagent:" + baseLaunchPath + "../build-artifacts/linux/hidden/lib/gemfirexd_unsealed.jar"
            // "-J-Xrunhprof:interval=1,depth=10,thread=y,verbose=n,heap=dump,format=b,file=server.hprof"
            }); // , "-J-Dgemfirexd.optimizer.trace=true",

        locatorConfigured = true;
        debugNumSecondaryServer--;
        debugPort++;
        clientPort++;
      }
      
      // "-J-Dgemfirexd.debug.true=DumpOptimizedTree"});
    }
  }
  
  public static void shutdownSecondaryServers() {
    if (LAUNCH_PEER_SERVER) {
      try {
        int serverCount = launchIndividualLocator ? 1 : 0; // skip last server which is actually a locator.
        
        for (int currentServerCount = secServerCount + serverCount; currentServerCount > serverCount; currentServerCount--) {
          GfxdServerLauncher.main(new String[] { "stop",
              "-dir=" + baseLaunchPath + (currentServerCount-serverCount) });
        }
        
        if(launchIndividualLocator) {
          GfxdDistributionLocator.main(new String[] { "stop",
              "-dir=" + baseLaunchPath + "loc" });
        }
//        GfxdServerLauncher.main(new String[] { "stop",
//            "-dir=/soubhikc1/builds/v3gemfirexddev/logs/2" });

//        GfxdServerLauncher.main(new String[] { "stop",
//            "-dir=/soubhikc1/builds/v3gemfirexddev/logs/1" });
        // GfxdServerLauncher.main( new String[]
        // {"stop","-dir=/soubhikc1/builds/v2gemfirexddev/logs/3"} );
        // GfxdServerLauncher.main( new String[]
        // {"stop","-dir=/soubhikc1/builds/v2gemfirexddev/logs/4"} );
      } finally {
        // GfxdUtilLauncher.main(new String[] { "stop-locator",
        // "-port=" + available_port,
        // "-dir=/soubhikc1/builds/v3gemfirexddev/logs/locator" });
        // try {
        // Runtime.getRuntime().exec("find /soubhikc1/builds/v3gemfirexddev/logs/ -exec rm {} \\;");
        // } catch (IOException e) {
        // e.printStackTrace();
        // }
      }

    }

  }
  
  public static void makeClient(Properties p) {
    
    if (LAUNCH_PEER_SERVER) {
      // client node
      p.setProperty("host-data", "false");
      
      p.setProperty("gemfire.mcast-port", "0");
      
      p.setProperty("locators", "localhost[" + available_port + "]");
    }
    else {
      // default is too server...
      p.setProperty("host-data", "true");
      
      p.setProperty("mcast-port", "0");
      // info.setProperty("gemfire.start-locator", "localhost[" +
      // available_port
      // + "]");
    }
    p.setProperty("log-level", "fine");
    p.setProperty("gemfire.enable-time-statistics", "false");
    p.setProperty("statistic-sample-rate", "1000");
    p.setProperty("statistic-sampling-enabled", "false");
    p.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_STATS, "false");
    p.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_TIMESTATS, "false");
    p.setProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME, "client-" + 1 + ".gfs");
    
    p.put(PartitionedRegion.rand.nextBoolean()
        ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
        : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, "Soubhik");
    p.put(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "Soubhik");
    
  }
  
  public static boolean LAUNCH_PEER_SERVER;

  public static int debugNumSecondaryServer = 0;
  
  //windows private final String baseLaunchPath = "c:\\gemfirexd\\builds\\gfxdabric_dev_nov10\\logs\\";
  public static final String baseLaunchPath = "/soubhikc1/builds/gemfirexd110X_maint/logs/";
  
  public static int secServerCount = 1;
  
  public static boolean launchIndividualLocator = false;
  
  public static boolean runNetServer = true;
  
}


