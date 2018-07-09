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

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.gemstone.gemfire.cache.util.CacheWriterAdapter;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.GemFireTerminateError;
import com.gemstone.gemfire.internal.cache.LocalDataSet;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2Index;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.MultipleInsertsLeveragingPutAllDUnit.BatchInsertObserver;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireSelectActivation;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.DerbySQLException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.iapi.types.UserType;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.procedure.ProcedureExecutionContext;
import com.pivotal.gemfirexd.tools.internal.JarTools;
import com.pivotal.gemfirexd.tools.utils.ExecutionPlanUtils;
import com.sun.jna.Platform;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;
import udtexamples.UDTPrice;

/**
 * 
 * @author Asif
 * 
 */
public class BugsTest extends JdbcTestBase {

  private volatile boolean exceptionOccured = false;
  protected boolean callbackInvoked;

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(SimpleAppTest.class));
  }

  public BugsTest(String name) {
    super(name);
  }

  public void testGEMXD_3_IndexRecoveryNPE() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("Create table ODS.POSTAL_ADDRESS("
        + "cntc_id bigint NOT NULL,"
        + "pstl_addr_id bigint GENERATED BY DEFAULT AS IDENTITY  NOT NULL,"
        + "ver bigint NOT NULL,"
        + "client_id bigint NOT NULL,"
        + "str_ln1 varchar(100),"
        + "str_ln2 varchar(100),"
        + "str_ln3 varchar(100),"
        + "cnty varchar(50),"
        + "pstl_cd varchar(20),"
        + "cntry varchar(100),"
        + "vldtd SMALLINT,"
        + "vldtn_dt DATE,"
        + "vld_frm_dt TIMESTAMP NOT NULL,"
        + "vld_to_dt TIMESTAMP,"
        + "src_sys_ref_id varchar(10) NOT NULL,"
        + "src_sys_rec_id varchar(150),"
        + "PRIMARY KEY (client_id,cntc_id,pstl_addr_id)) "
        + "PARTITION BY COLUMN (cntc_id) "
        + "REDUNDANCY 1 EVICTION BY LRUHEAPPERCENT EVICTACTION "
        + "OVERFLOW PERSISTENT ASYNCHRONOUS");

    for (int i = 200; i < 250; i++) {
      st.execute("insert into ODS.POSTAL_ADDRESS(cntc_id, ver, client_id, "
          + "str_ln2, cnty, vld_frm_dt, src_sys_ref_id) values (" + (i * 74)
          + ", " + (i + 14) + ", " + (i + 20) + ", 'STR_LN2', 'CNTY"
          + (i * 42) + "', CURRENT_TIMESTAMP, 'REFID" + i + "')");
    }

    st.execute("ALTER TABLE ODS.POSTAL_ADDRESS ADD COLUMN cty varchar(75)");
    st.execute("CREATE INDEX IX_POSTAL_ADDRESS_02 ON ODS.POSTAL_ADDRESS "
        + "(CTY, CLIENT_ID) -- GEMFIREXD-PROPERTIES caseSensitive=false");
    for (int i = 150; i < 200; i++) {
      st.execute("insert into ODS.POSTAL_ADDRESS(cntc_id, ver, client_id, "
          + "str_ln2, cty, cnty, vld_frm_dt, src_sys_ref_id) values ("
          + (i * 74) + ", " + (i + 14) + ", " + (i + 20) + ", 'STR_LN2', 'CTY"
          + (i + 20) + "', 'CNTY" + (i * 42) + "', CURRENT_TIMESTAMP, 'REFID"
          + i + "')");
    }

    st.execute("ALTER TABLE ODS.POSTAL_ADDRESS ADD COLUMN st varchar(50) default ''");
    st.execute("CREATE INDEX IX_POSTAL_ADDRESS_03 ON ODS.POSTAL_ADDRESS "
        + "(ST, CLIENT_ID) -- GEMFIREXD-PROPERTIES caseSensitive=false");
    for (int i = 100; i < 150; i++) {
      st.execute("insert into ODS.POSTAL_ADDRESS(cntc_id, ver, client_id, "
          + "str_ln2, cnty, st, vld_frm_dt, src_sys_ref_id) values ("
          + (i * 74) + ", " + (i + 14) + ", " + (i + 20)
          + ", 'STR_LN2', 'CNTY" + (i * 42) + "', 'ST" + (i + 20) + "', "
          + "CURRENT_TIMESTAMP, 'REFID" + i + "')");
    }
    for (int i = 0; i < 100; i++) {
      st.execute("insert into ODS.POSTAL_ADDRESS(cntc_id, ver, client_id, "
          + "str_ln2, cty, cnty, st, vld_frm_dt, src_sys_ref_id) values ("
          + (i * 74) + ", " + (i + 14) + ", " + (i + 20) + ", 'STR_LN2', 'CTY"
          + (i + 20) + "', 'CNTY" + (i * 42) + "', 'ST" + (i + 20) + "', "
          + "CURRENT_TIMESTAMP, 'REFID" + i + "')");
    }
    // shutdown and restart for index recovery
    shutDown();

    setupConnection();
    conn = TestUtil.getConnection();
    st = conn.createStatement();
    ResultSet rs;
    for (int i = 0; i < 250; i++) {
      logger.info("Testing index 02 query for i = " + i);
      rs = st.executeQuery("select st, cty, client_id from ODS.POSTAL_ADDRESS "
          + "where CTY='CTY" + (i + 20) + "' AND CLIENT_ID=" + (i + 20));
      if ((i >= 200 && i < 250) || (i >= 100 && i < 150)) {
        assertFalse(rs.next());
      } else {
        assertTrue(rs.next());
        if (i >= 0 && i < 150) {
          assertEquals("ST" + (i + 20), rs.getString(1));
        } else {
          assertEquals("", rs.getString(1));
        }
        assertEquals("CTY" + (i + 20), rs.getString(2));
      }
      rs.close();
    }
    for (int i = 0; i < 250; i++) {
      logger.info("Testing index 03 query for i = " + i);
      rs = st.executeQuery("select st, cty, client_id from ODS.POSTAL_ADDRESS "
          + "where ST='ST" + (i + 20) + "' AND CLIENT_ID=" + (i + 20));
      if (i >= 0 && i < 150) {
        assertTrue("failed to find result for " + i, rs.next());
        assertEquals("ST" + (i + 20), rs.getString(1));
        if (i < 100) {
          assertEquals("CTY" + (i + 20), rs.getString(2));
        } else {
          assertNull(rs.getString(2));
        }
      } else {
        assertFalse(rs.next());
      }
      rs.close();
    }
  }

  public void testBug51188() throws Exception {
    DMLQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    GemFireXDQueryObserverAdapter observer = new GemFireXDQueryObserverAdapter() {
      public void afterResultSetOpen(GenericPreparedStatement stmt,
          LanguageConnectionContext lcc,
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
        if (resultSet != null) {
          assertTrue(resultSet.getClass().getSimpleName().compareTo(
              "HashJoinResultSet") == 0
              || resultSet instanceof GemFireDistributedResultSet);
        }
      }
    };

    st.execute("CREATE TABLE t1 (c1 BIGINT NOT NULL) PARTITION BY COLUMN (c1)");
    st.execute("CREATE TABLE t2 (c1 BIGINT NOT NULL) PARTITION BY COLUMN (c1) COLOCATE WITH (t1)");
    
    PreparedStatement ps1 = conn.prepareStatement("insert into t1 values (?)");
    int i = 0;
    for (i = 0; i < 100000; i++) {
      ps1.setLong(1, i);
      ps1.addBatch();
      if (i % 1000 == 0)
        ps1.executeBatch();
    }
    if (i % 1000 == 0)
      ps1.executeBatch();

    PreparedStatement ps2 = conn.prepareStatement("insert into t2 values (?)");
    for (i = 0; i < 100000; i++) {
      ps2.setLong(1, i);
      ps2.addBatch();
      if (i % 1000 == 0)
        ps2.executeBatch();
    }
    if (i % 1000 == 0)
      ps2.executeBatch();

    st.execute("select count(*) from t1");
    rs = st.getResultSet();
    rs.next();
    assertTrue(rs.getLong(1) == 100000);
    st.execute("select count(*) from t2");
    rs = st.getResultSet();
    rs.next();
    assertTrue(rs.getLong(1) == 100000);

    GemFireXDQueryObserverHolder.setInstance(observer);
    st.execute("SELECT t1.c1, t2.c1 FROM t1 JOIN t2 ON t1.c1 = t2.c1");
    GemFireXDQueryObserverHolder.clearInstance();

    rs = st.getResultSet();
    long count = 0;
    while (rs.next()) {
      count++;
    }
    assertTrue(count == 100000);
  }

  public void testBug51284() throws Exception {
    // reducing batch DML size to force putAll be broken into 1-2 rows
    System.setProperty(GfxdConstants.DML_MAX_CHUNK_SIZE_PROP, "7");
    try {
      Connection conn = getConnection();
      conn.setAutoCommit(true);
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Statement st = conn.createStatement();

      st.execute("create table isuspectabug(id int primary key, "
          + "name varchar(20)) partition by primary key");
      try {
        st.execute("insert into isuspectabug values(1, 'one'), (3, 'three'), "
            + "(3, 'three')");
        fail("expected a primary key constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }

      // all inserts should be transactional so there should have been no
      // successful inserts
      ResultSet rs = st.executeQuery("select * from isuspectabug");
      assertFalse(rs.next());
      rs = st.executeQuery("select count(*) from isuspectabug");
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());

      // also from another connection
      Connection conn2 = getConnection();
      Statement st2 = conn2.createStatement();
      rs = st2.executeQuery("select * from isuspectabug");
      assertFalse(rs.next());
      rs = st2.executeQuery("select count(*) from isuspectabug");
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());

      // also check with autocommit==false
      conn.setAutoCommit(false);
      st.execute("create table isuspectabug2(id int primary key, "
          + "name varchar(20)) partition by primary key");
      try {
        st.execute("insert into isuspectabug2 values(1, 'one'), (3, 'three'), "
            + "(3, 'three')");
        fail("expected a primary key constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }

      conn.rollback();

      // all inserts should be transactional so there should have been no
      // successful inserts
      rs = st.executeQuery("select * from isuspectabug2");
      assertFalse(rs.next());
      rs = st.executeQuery("select count(*) from isuspectabug2");
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());

      // also from another connection
      rs = st2.executeQuery("select * from isuspectabug2");
      assertFalse(rs.next());
      rs = st2.executeQuery("select count(*) from isuspectabug2");
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());
    } finally {
      System.clearProperty(GfxdConstants.DML_MAX_CHUNK_SIZE_PROP);
    }
  }

  public void testBug51463() throws Exception {
    Connection conn = getConnection();
    Statement st = conn.createStatement();

    try {
      st.execute("create table t1 (col1 int primary key, col2 varchar(10), col3 varchar(10), "
              + "constraint t1_uq unique (col2, col3)) partition by primary key");
      st.execute("put into t1 values (202, 'a0', 'lse')");
      st.execute("put into t1 values (303, 'a0', 'lse')");
      fail("Expect unique constraint violation");
    } catch (SQLException sqle) {
      if (sqle.getSQLState().compareTo("23505") != 0) {
        throw sqle;
      }
    }
  }
  
  public void testBug51230() throws Exception {
    Connection conn = getConnection();   
    Statement st = conn.createStatement();    
    st.execute("CREATE TABLE t1 (c1 VARCHAR(2048))");
    st.execute("CREATE TABLE t2 AS SELECT * FROM t1 WITH NO DATA");
    st.execute("ALTER TABLE t2 ADD COLUMN c2 VARCHAR(1)");    
    conn.close();
  }

  public void testBug51064() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("CREATE TABLE APP.PERSON ("
        + "CLNT_OBJ_ID VARCHAR(100) NOT NULL, "
        + "PERS_OBJ_ID VARCHAR(100) NOT NULL) "
        + "PARTITION BY COLUMN (CLNT_OBJ_ID, PERS_OBJ_ID) BUCKETS 163");

    st.execute("CREATE TABLE APP.EARN_ROLLUP_PERSON ("
        + "CLNT_OBJ_ID VARCHAR(100) NOT NULL,"
        + "PERS_OBJ_ID VARCHAR(100) NOT NULL," + "PAYRL_EARN_KY INT NOT NULL,"
        + "EARN_CD VARCHAR(200) NOT NULL," + "EARN_DSC VARCHAR(200) NOT NULL,"
        + "PAY_GRP_CD VARCHAR(200) NOT NULL,"
        + "WORK_LOC_SHRT_DSC VARCHAR(200) NOT NULL,"
        + "EARNING DECIMAL(20,5) NOT NULL," + "MONTH VARCHAR(100) NOT NULL,"
        + "QTR VARCHAR(100) NOT NULL," + "YEAR_NR INT NOT NULL) "
        + "PARTITION BY COLUMN (CLNT_OBJ_ID,PERS_OBJ_ID) "
        + "COLOCATE WITH (APP.PERSON) " + "BUCKETS 163");

    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('APP', 'PERSON', '"
        + getResourcesDir() + "/lib/useCase9/person_data.tbl', ',', "
        + "null, null, 0, 0 , 6 , 0 , null , null )");

    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('APP', 'EARN_ROLLUP_PERSON', '"
            + getResourcesDir()
            + "/lib/useCase9/earn_rollup_data.tbl', ',', "
            + "null, null, 0, 0 , 6 , 0 , null , null )");

    st.execute("SELECT  t1.YEAR_nr, "
            + "sum(t1.EARNING)/cast((count(DISTINCT t2.pers_obj_id)) as decimal) as avg_earnings ,"
            + "earn_cd ," + "earn_dsc "
            + "FROM app.EARN_ROLLUP_PERSON t1 ,app.person t2 "
            + "WHERE t1.clnt_obj_id = t2.clnt_obj_id AND "
            + "t1.pers_obj_id = t2.pers_obj_id AND "
            + "t1.CLNT_OBJ_ID = 'G3H02MPK5NQRXJ9N_0041' AND "
            + "work_loc_shrt_dsc in ('AZ') AND " + "pay_grp_cd = 'LXG' AND "
            + "YEAR_nr = 2012 " + "GROUP BY earn_cd, earn_dsc,YEAR_nr "
            + "ORDER BY year_nr,earn_cd, earn_dsc");
    
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(13, count);
  }

  public void testBug50428() throws Exception {
    reduceLogLevelForTest("config");

     Properties props = new Properties();
     int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
     props.put("mcast-port", String.valueOf(mcastPort));
     Connection conn = TestUtil.getConnection(props);    
    Statement st = conn.createStatement();
    ResultSet rs = null;
    
    st.execute("CREATE TABLE ORDERS  ( O_ORDERKEY INTEGER NOT NULL, "
        + "O_CUSTKEY INTEGER NOT NULL, " + "O_ORDERSTATUS CHAR(1) NOT NULL, "
        + "O_TOTALPRICE DECIMAL(15,2) NOT NULL, "
        + "O_ORDERDATE DATE NOT NULL, " + "O_ORDERPRIORITY CHAR(15) NOT NULL, "
        + "O_CLERK CHAR(15) NOT NULL, " + "O_SHIPPRIORITY INTEGER NOT NULL, "
        + "O_COMMENT VARCHAR(79) NOT NULL) "
        + "PARTITION BY COLUMN (O_ORDERKEY)");
    
    st.execute("ALTER TABLE ORDERS ADD PRIMARY KEY (O_ORDERKEY)");
        
    //Use more than one thread to see if null schema works
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX (null, 'ORDERS', '" +
        getResourcesDir() + "/lib/tpch/orders.tbl" +
        "', '|', null, null, 0, 0, 2, 0, null, null)");
  }

  public void testOplogPreBlow() throws Exception {
    reduceLogLevelForTest("config");

    if (Platform.isLinux()) {
      this.deleteDirs = new String[2];
      Connection conn = TestUtil.getConnection();
      NativeCalls.TEST_CHK_FALLOC_DIRS = new HashSet<String>();
      Statement st = conn.createStatement();
      // Test local directory
      long currTime = System.nanoTime();
      String locDirName = "/tmp/" + System.getenv().get("USER") + "-"
          + currTime;
      File locDir = new File(locDirName);
      assertTrue(locDir.mkdir());
      this.deleteDirs[0] = locDir.getAbsolutePath();
      String remDirName;
      boolean hasExportDir;
      String userName = System.getenv("USER");
      String exportDir = "/srv/users/" + userName;
      File exportFile = new File(exportDir);
      if (exportFile.exists() && exportFile.isDirectory()) {
        hasExportDir = true;
        remDirName = exportDir + "/preBlow-" + currTime;
      } else {
        exportDir = "/export/shared/users/" + userName;
        exportFile = new File(exportDir);
        if (exportFile.mkdirs() ||
            (exportFile.exists() && exportFile.isDirectory())) {
          hasExportDir = true;
          remDirName = exportDir + "/preBlow-" + currTime;
        } else {
          hasExportDir = false;
          remDirName = "/home/" + System.getenv().get("USER") + "/preBlow-"
              + currTime;
        }
      }
      File remDir = new File(remDirName);
      getLogger().info("Using remote directory " + remDir.getAbsolutePath());
      assertTrue(remDir.mkdir());
      this.deleteDirs[1] = remDir.getAbsolutePath();
      st.execute("create diskstore teststore_loc ('" + locDirName + "') MAXLOGSIZE 2");
      StringBuilder str1k = new StringBuilder();
      for (int i = 0; i < 1024; i++) {
        str1k.append('a');
      }
      st.execute("create table emp(id int not null primary key, name varchar(20) not null, "
          + "addr varchar(2000) not null) persistent 'teststore_loc'");
      PreparedStatement ps = conn
          .prepareStatement("insert into emp values(?, ?, ?)");
      for (int i = 0; i < 5000; i++) {
        ps.setInt(1, i);
        ps.setString(2, "name" + i);
        ps.setString(3, str1k.toString());
        int j = ps.executeUpdate();
        assertEquals(1, j);
      }
      st.execute("select count(*) from emp");
      ResultSet rs = st.getResultSet();
      rs.next();
      assertEquals(5000, rs.getInt(1));

      HashSet<String> preBlowDoneDirs = new HashSet<>();
      preBlowDoneDirs.addAll(NativeCalls.TEST_CHK_FALLOC_DIRS);
      NativeCalls.TEST_CHK_FALLOC_DIRS.clear();
      // Test remote now
      NativeCalls.TEST_NO_FALLOC_DIRS = new HashSet<>();
      st.execute("create diskstore teststore_rem ('" + remDirName + "') MAXLOGSIZE 2");
      st.execute("create table emp2(id int not null primary key, name varchar(20) not null, "
          + "addr varchar(2000) not null) persistent 'teststore_rem'");
      ps = conn.prepareStatement("insert into emp2 values(?, ?, ?)");
      for (int i = 0; i < 5000; i++) {
        ps.setInt(1, i);
        ps.setString(2, "name" + i);
        ps.setString(3, str1k.toString());
        int j = ps.executeUpdate();
        assertEquals(1, j);
      }
      st.execute("select count(*) from emp2");
      rs = st.getResultSet();
      rs.next();
      assertEquals(5000, rs.getInt(1));
      HashSet<String> preBlowNotDoneDirs = new HashSet<>();
      preBlowNotDoneDirs.addAll(NativeCalls.TEST_NO_FALLOC_DIRS);
      if (hasExportDir) {
        assertTrue(NativeCalls.TEST_CHK_FALLOC_DIRS.isEmpty());
        assertEquals(preBlowDoneDirs.size(), preBlowNotDoneDirs.size());
      } else {
        assertFalse(NativeCalls.TEST_CHK_FALLOC_DIRS.isEmpty());
        assertEquals(0, preBlowNotDoneDirs.size());
      }
      NativeCalls.TEST_CHK_FALLOC_DIRS = null;
      NativeCalls.TEST_NO_FALLOC_DIRS.clear();
      NativeCalls.TEST_NO_FALLOC_DIRS = null;
      assertTrue(preBlowDoneDirs.size() > 0);
    }
  }
  
  public void testBug50100() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    String sql = "CREATE TABLE app.test "
        + "( id bigint not null generated by default as identity (start with 1, increment by 1), "
        + "batch_id bigint, constraint app_test_pk primary key (id) ) PARTITION BY PRIMARY KEY";
    st.execute(sql);
    st.execute("CREATE UNIQUE INDEX idx_app_test_id ON app.test ( id )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    
    st.execute("select * from app.test where id in (1,2,3,4,5) or batch_id < 4");
    ResultSet rs = st.getResultSet();
    int cnt = 0;
    while(rs.next()) cnt++;
    
    assertEquals(5, cnt);
    
    st.execute("CREATE INDEX idx_app_test_batch_id ON app.test ( batch_id )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    st.execute("insert into app.test(batch_id) values( null )");
    
    st.execute("select * from app.test where id in (6,7,8,9,10) or batch_id < 4");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) cnt++;
    
    assertEquals(5, cnt);
  }
  
  public void testBug50100_1() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    DMLQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();

    String sql = "CREATE table trade.customers (cid int not null, cust_name varchar(100), "
        + "since date, addr varchar(100), tid int, primary key (cid))  "
        + "partition by range (since) (VALUES BETWEEN CAST('1998-01-01'  AS DATE) AND CAST('2000-08-11' AS DATE),  "
        + "VALUES BETWEEN CAST('2003-09-01' AS DATE) AND  CAST('2007-12-31' AS DATE) )";
    st.execute(sql);
    st.execute("create  index index_2 on trade.customers ( SINCE desc  )");
    st.execute("create  index index_96 on trade.customers ( SINCE )");
    st.execute("create  index index_16 on trade.customers ( CUST_NAME   )");
    st.execute("insert into trade.customers values (1, 'name1', '1998-04-18', 'a', 1)");
    st.execute("insert into trade.customers values (2, 'name2', '2006-05-25', 'a', 1), " +
    		                                  "(3, 'name3', '2007-06-12', 'a', 1), " +
    		                                  "(4, 'name4', '2003-06-12', 'a', 1)");
    conn.commit();
    
    st.execute("select cid, since, cust_name from trade.customers where cust_name = 'name2' or since = '2003-06-12'");
    ResultSet rs = st.getResultSet();
    int cnt = 0;
    while(rs.next()) {
      cnt++;
    }
    assertEquals(2, cnt);
    
    st.execute("select cid, since, cust_name from trade.customers where cust_name = 'name2' or since in ('2003-06-12', '2007-06-12', '2005-01-02')");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
    }
    assertEquals(3, cnt);
    
    st.execute("select cid, since, cust_name from trade.customers where since in ('2003-06-12', '2007-06-12', '2005-01-02') or cust_name = 'name2'");
    
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
    }
    assertEquals(3, cnt);
  }
  
  //enable it after ensuring data files are available
  public void testBug46851() throws Exception {

    // Bug #51839
    if (isTransactional) {
      return;
    }

    reduceLogLevelForTest("config");

    try {
    File file = new File("perf.txt");
    //PrintWriter bw =new PrintWriter(new OutputStreamWriter(new FileOutputStream(file)));
    PrintStream bw = System.out;

    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    //SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    props.setProperty("log-level", "config");
    props.setProperty("table-default-partitioned", "false");
    Connection conn = getConnection(props);

    Statement st = conn.createStatement();

    // create the schema
    String resourcesDir = getResourcesDir();

    String schemaScript = resourcesDir
        + "/lib/tpch/create_tpch_tables_no_constraints.sql";
    String alterTableScript = resourcesDir
        + "/lib/tpch/alter_table_add_constraints.sql";

    String indexScript = resourcesDir + "/lib/tpch/create_indexes.sql";

    GemFireXDUtils.executeSQLScripts(conn, new String[] {
            schemaScript,alterTableScript,indexScript }, false,
        getLogger(), null, null, false);
    st.executeUpdate("SET SCHEMA TPCHGFXD");

    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'REGION', '"+
    resourcesDir + "/lib/tpch/region.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'NATION', '"+
    resourcesDir + "/lib/tpch/nation.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'PART', '" +
    resourcesDir + "/lib/tpch/part.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'SUPPLIER', '" +
    resourcesDir + "/lib/tpch/supplier.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'PARTSUPP', '" +
    resourcesDir + "/lib/tpch/partsupp.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'CUSTOMER', '" +
    resourcesDir + "/lib/tpch/customer.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'ORDERS', '" +
    resourcesDir + "/lib/tpch/orders.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    st.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX ('TPCHGFXD', 'LINEITEM', '" +
    resourcesDir + "/lib/tpch/lineitem.tbl', '|', null, null, 0, 0, 4, 0, null, null)");
    
   String query9 = "select nation, o_year, sum(amount) as sum_profit " +
   		" from (  select n_name as nation, year(o_orderdate) as o_year, " +
   		" l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount" +
   		"  from    part, supplier " +
   	//	"-- GEMFIREXD-PROPERTIES joinStrategy=HASH \n" +
   		",lineitem, partsupp, orders, nation  where " +
   		" s_suppkey = l_suppkey and ps_suppkey = l_suppkey and " +
   		" ps_partkey = l_partkey and p_partkey = l_partkey and o_orderkey = l_orderkey" +
   		"   and s_nationkey = n_nationkey and p_name like '%green%'  ) as profit" +
   		" group by  nation, o_year order by   nation, o_year desc";
   long start = System.currentTimeMillis();
   ResultSet rs = st.executeQuery(query9);
   
   while(rs.next()) {
     rs.getObject(1);
   }
   long end = System.currentTimeMillis();
   bw.println("query 9 time="+ (end -start) + "milliseconds");
   
   String query5 = "select n_name, sum(l_extendedprice * (1 - l_discount)) as revenue " +
   		"from   customer, orders" +
   		//"  -- GEMFIREXD-PROPERTIES joinStrategy=HASH \n"+
   		"  , lineitem, supplier" +
   		//"  -- GEMFIREXD-PROPERTIES joinStrategy=HASH \n" +
   		"  , nation, region where  c_custkey = o_custkey and l_orderkey = o_orderkey " +
   		" and l_suppkey = s_suppkey and c_nationkey = s_nationkey" +
   		"   and s_nationkey = n_nationkey and n_regionkey = r_regionkey" +
   		" and r_name = 'ASIA'   and o_orderdate >= '1994-01-01' " +
   		"  and o_orderdate <" +
   		" cast({fn timestampadd(SQL_TSI_YEAR, 1, timestamp('1994-01-01 23:59:59'))} as DATE)" +
   		" group by   n_name order by   revenue desc";
   start = System.currentTimeMillis();
   rs = st.executeQuery(query5);
   
   while(rs.next()) {
     rs.getObject(1);
   }
   end = System.currentTimeMillis();
   bw.println("query 5 time="+ (end -start) + "milliseconds");
   
   st.executeUpdate("drop table lineitem");
   st.executeUpdate("drop table  orders");
   st.executeUpdate("drop table partsupp");
   st.executeUpdate("drop table supplier");
   st.executeUpdate("drop table part");
   
   /*
   String queryX = "select ss_customer_sk ,sum(act_sales) sumsales " +
   		" from (select ss_item_sk ,ss_ticket_number ,ss_customer_sk ," +
   		" case when sr_return_quantity is not null " +
   		" then (ss_quantity-sr_return_quantity)*ss_sales_price" +
   		"    else (ss_quantity*ss_sales_price) end act_sales  from store_sales" +
   		" left outer join store_returns " +
   		"--SQLFIRE-PROPERTIES joinStrategy=hash \n" +
   		" on (sr_item_sk = ss_item_sk  " +
   		" and sr_ticket_number = ss_ticket_number) ,reason " +
   		"where sr_reason_sk = r_reason_sk   and r_reason_desc = 'reason 28') t" +
   		"   group by ss_customer_sk   order by sumsales, ss_customer_sk";
   
   start = System.currentTimeMillis();
   rs = st.executeQuery(queryX);
   
   while(rs.next()) {
     rs.getObject(1);
   }
   end = System.currentTimeMillis();
   bw.println("query X time="+ (end -start) + "milliseconds");*/
   bw.flush();
   bw.close();
    }finally {
      System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
      System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
    }
   
  }
  
  public void testBug50729() throws Exception {
    reduceLogLevelForTest("config");

    try {
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    props.setProperty("log-level", "config");
    Connection conn = getConnection(props);
    
    Statement st = conn.createStatement();
    st.execute("create table networthv1 (cid bigint not null, " +
        "cash decimal (30, 1), securities decimal (30, 1), loanlimit int, " +
        "availloan decimal (30, 1),  tid int," +
        " constraint cash_ch_v1 check (cash>=0), constraint sec_ch_v1 check (securities >=0) " +
        ", constraint availloan_ck_v1 check (loanlimit>=availloan and availloan >=0)"+
        ")  offheap ");
    String query ="update networthv1 set cash=cash-?, round = ?  " +
    		"where cid in (select min(cid) from networthv1 " +
    		"where (securities>? and cash > ? and cid >=?)and round < ?)";
    //;pvs=;value=46.8,type=0;value=1,type=0;value=205796.1,type=0;value=2,type=0;value=266,type=0;value=1,type=0
    PreparedStatement insert = conn.prepareStatement("insert into networthv1 values(?,?,?,?,?,?)");
    String[] data = {"struct(CID:92,CASH:null,SECURITIES:null,LOANLIMIT:10000,AVAILLOAN:10000.0,TID:13,ROUND:0,C_CID:92)",
        "struct(CID:431,CASH:null,SECURITIES:null,LOANLIMIT:1000,AVAILLOAN:1000.0,TID:5,ROUND:0,C_CID:431)"
     };
   
    for( String line:data) {
    
     line = line.substring(7,line.length()-1);
     StringTokenizer stz = new StringTokenizer(line,":,");
     stz.nextToken();
     int cid = Integer.parseInt(stz.nextToken());
     insert.setInt(1, cid);
     stz.nextToken();
     String cashStr = stz.nextToken();
     if(cashStr.equalsIgnoreCase("null")) {
       insert.setNull(2, Types.DECIMAL);
     }else {
       insert.setFloat(2, Float.parseFloat(cashStr));
     }
     
     stz.nextToken();
     String secStr = stz.nextToken();
     if(secStr.equalsIgnoreCase("null")) {
       insert.setNull(3, Types.DECIMAL);
     }else {
       insert.setFloat(3, Float.parseFloat(secStr));
     }
     
     stz.nextToken();
     String llStr = stz.nextToken();
     if(llStr.equalsIgnoreCase("null")) {
       insert.setNull(4, Types.INTEGER);
     }else {
       insert.setInt(4, Integer.parseInt(llStr));
     }
     
     stz.nextToken();
     String avlStr = stz.nextToken();
     if(avlStr.equalsIgnoreCase("null")) {
       insert.setNull(5, Types.FLOAT);
     }else {
       insert.setFloat(5, Float.parseFloat(avlStr));
     }
     
     stz.nextToken();
     String tidStr = stz.nextToken();
     if(tidStr.equalsIgnoreCase("null")) {
       insert.setNull(6, Types.INTEGER);
     }else {
       insert.setInt(6, Integer.parseInt(tidStr));
     }
     /*
     stz.nextToken();
     String roundStr = stz.nextToken();
     if(roundStr.equalsIgnoreCase("null")) {
       insert.setNull(7, Types.INTEGER);
     }else {
       insert.setInt(7, Integer.parseInt(roundStr));
     }
     
     stz.nextToken();
     String ccidStr = stz.nextToken();
     if(ccidStr.equalsIgnoreCase("null")) {
       insert.setNull(8, Types.BIGINT);
     }else {
       insert.setLong(8, Integer.parseInt(ccidStr));
     }*/
     
     insert.executeUpdate();

    }
    
    st.execute("alter table networthv1 add column round int  DEFAULT 0");
    st.execute("alter table networthv1 add column c_cid bigint DEFAULT 1");
    st.execute("update networthv1 set c_cid = cid"); 
    PreparedStatement psQuery = conn.prepareStatement(query);
    
    psQuery.setFloat(1,46.8f);
    psQuery.setInt(2,1);
    psQuery.setFloat(3,205796.1f);
    
    psQuery.setInt(4,2);
    psQuery.setInt(5,266);
    
    psQuery.setInt(6,1);
    
    psQuery.executeUpdate();
    }finally {
      System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
      System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
    }
   
  }
  
  public void testFlavourOf50729_1() throws Exception{
     // Create a schema
      Connection conn = getConnection();
      PreparedStatement prepStmt = conn.prepareStatement("create schema trade");
      prepStmt.execute();
      Statement stmt = conn.createStatement();
      stmt.execute("create table trade.customers "
          + "(cust_name varchar(100), tid int)");
      stmt.execute("alter table trade.customers add column cid int not null default 1");
      stmt.execute("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
      
      stmt.executeUpdate("insert into trade.customers (cid, cust_name, tid) values(1,'name1',1)");
      int n = stmt.executeUpdate("delete from trade.customers where cid = 1");
      assertEquals(1,n);
    
  }
  
  public void testFlavourOf50729_2() throws Exception{
    // Create a schema
     Connection conn = getConnection();
     PreparedStatement prepStmt = conn.prepareStatement("create schema trade");
     prepStmt.execute();
     Statement stmt = conn.createStatement();
     stmt.execute("create table trade.customers "
         + "(cid int not null default 1,cust_name varchar(100), tid int)");
     stmt.execute("alter table trade.customers "
       + "add constraint cust_pk primary key (cid)");
     stmt.execute("create table trade.portfolio "
     + "(cid int not null, qty int not null, "
     + " tid int, "
     + "constraint portf_pk primary key (cid))");
     stmt.execute("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict");
     stmt.executeUpdate("insert into trade.customers (cid, cust_name, tid) values(1,'name1',1)");
     stmt.executeUpdate("insert into trade.portfolio (cid, qty, tid) values(1,1,1)"); 
 }
  
  public void testBug50756() throws Exception {
    reduceLogLevelForTest("config");

    try {
      System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
      System.setProperty("gemfire."
          + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
      Properties props = new Properties();
      props.setProperty("log-level", "config");
      Connection conn = getConnection(props);

      Statement st = conn.createStatement();
      st.execute("create table customersv1 (cid bigint not null, cust_name varchar(100), "
          + "since date, addr varchar(100), tid int, primary key (cid))  "
          + "partition by column (cust_name)   offheap ");
      st.execute("create index indexcustomerv1tid on customersv1 (tid)");
      PreparedStatement insertCustomer = conn
          .prepareStatement("insert into customersv1 " + "values(?,?,?,?,?)");
      
      String dataFile = getResourcesDir() + "/lib/bug50756/customers.dat";
      BufferedReader br = new BufferedReader(new InputStreamReader(
          new FileInputStream(dataFile)));
      String line;
      while ((line = br.readLine()) != null && !line.trim().equals("")) {
        line = line.trim();
        line = line.substring(7, line.length() - 1);
        StringTokenizer stz = new StringTokenizer(line, ":,");
        stz.nextToken();
        long cid = Long.parseLong(stz.nextToken());
        insertCustomer.setLong(1, cid);
        stz.nextToken();
        String cust_nameStr = stz.nextToken();
        if (cust_nameStr.equalsIgnoreCase("null")) {
          insertCustomer.setNull(2, Types.VARCHAR);
        } else {
          insertCustomer.setString(2, cust_nameStr);
        }

        stz.nextToken();
        String sinceStr = stz.nextToken();
        if (sinceStr.equalsIgnoreCase("null")) {
          insertCustomer.setNull(3, Types.DATE);
        } else {
          insertCustomer.setDate(3, java.sql.Date.valueOf(sinceStr));
        }

        stz.nextToken();
        String addStr = stz.nextToken();
        if (addStr.equalsIgnoreCase("null")) {
          insertCustomer.setNull(4, Types.VARCHAR);
        } else {
          insertCustomer.setString(4, addStr);
        }

        stz.nextToken();
        String tidStr = stz.nextToken();
        if (tidStr.equalsIgnoreCase("null")) {
          insertCustomer.setNull(5, Types.INTEGER);
        } else {
          insertCustomer.setInt(5, Integer.parseInt(tidStr));
        }
        insertCustomer.executeUpdate();
      }

      st.execute("alter table customersv1 add column round int DEFAULT 0");
      st.execute("create table networthv1 (cid bigint not null, cash decimal (30, 1), "
          + "securities decimal (30, 1), loanlimit int, availloan decimal (30, 1),  "
          + "tid int, constraint netw_pk_v1 primary key (cid), "
          + "constraint cust_newt_fk_v1 foreign key (cid) references customersv1 (cid) on delete restrict,"
          + " constraint cash_ch_v1 check (cash>=0), constraint sec_ch_v1 check (securities >=0), "
          + "constraint availloan_ck_v1 check (loanlimit>=availloan and availloan >=0)) "
          + " partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102,"
          + " VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577,"
          + " VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000)   offheap ");

      st.execute("alter table networthv1 drop CHECK availloan_ck_v1");
      PreparedStatement insert = conn
          .prepareStatement("insert into networthv1 values(?,?,?,?,?,?)");
      dataFile = getResourcesDir() + "/lib/bug50756/networth.dat";
      br.close();
      br = new BufferedReader(new InputStreamReader(new FileInputStream(
          dataFile)));

      while ((line = br.readLine()) != null && !line.trim().equals("")) {
        line = line.trim();
        line = line.substring(7, line.length() - 1);
        StringTokenizer stz = new StringTokenizer(line, ":,");
        stz.nextToken();
        long cid = Long.parseLong(stz.nextToken());
        insert.setLong(1, cid);
        stz.nextToken();
        String cashStr = stz.nextToken();
        if (cashStr.equalsIgnoreCase("null")) {
          insert.setNull(2, Types.DECIMAL);
        } else {
          insert.setFloat(2, Float.parseFloat(cashStr));
        }

        stz.nextToken();
        String secStr = stz.nextToken();
        if (secStr.equalsIgnoreCase("null")) {
          insert.setNull(3, Types.DECIMAL);
        } else {
          insert.setFloat(3, Float.parseFloat(secStr));
        }

        stz.nextToken();
        String llStr = stz.nextToken();
        if (llStr.equalsIgnoreCase("null")) {
          insert.setNull(4, Types.INTEGER);
        } else {
          insert.setInt(4, Integer.parseInt(llStr));
        }

        stz.nextToken();
        String avlStr = stz.nextToken();
        if (avlStr.equalsIgnoreCase("null")) {
          insert.setNull(5, Types.FLOAT);
        } else {
          insert.setFloat(5, Float.parseFloat(avlStr));
        }

        stz.nextToken();
        String tidStr = stz.nextToken();
        if (tidStr.equalsIgnoreCase("null")) {
          insert.setNull(6, Types.INTEGER);
        } else {
          insert.setInt(6, Integer.parseInt(tidStr));
        }

        insert.executeUpdate();

      }
      st.execute("alter table networthv1 add column round int DEFAULT 0");
      st.execute("alter table networthv1 add column c_cid bigint with DEFAULT 1");
      st.executeUpdate("update networthv1 set c_cid = cid");
      st.execute("alter table networthv1 add constraint cust_newt_fk_v1_2 FOREIGN KEY (c_cid) "
          + "references customersv1 (cid) on delete restrict");
      st.execute("alter table networthv1 drop constraint cust_newt_fk_v1");
      st.execute("alter table customersv1 ALTER COLUMN cid SET GENERATED ALWAYS AS "
          + "IDENTITY");
      // update networthv1
      PreparedStatement updtNtw = conn
          .prepareStatement("update networthv1 set cash=cash-?, "
              + "round = ?  where cid in (select min(cid) from networthv1 "
              + "where (securities>? and cash > ? and cid >=?)and round < ?)");
      updtNtw.setFloat(1, 505.2f);
      updtNtw.setInt(2, 1);
      updtNtw.setFloat(3, 144574.8f);
      updtNtw.setFloat(4, 9);
      updtNtw.setLong(5, 61);
      updtNtw.setInt(6, 1);
      updtNtw.executeUpdate();
      // update networthv1
      updtNtw.setFloat(1, 39.7f);
      updtNtw.setInt(2, 1);
      updtNtw.setFloat(3, 17494.6f);
      updtNtw.setFloat(4, 9);
      updtNtw.setLong(5, 59);
      updtNtw.setInt(6, 1);
      updtNtw.executeUpdate();

      // update customersv1
      PreparedStatement updtCust = conn
          .prepareStatement(" update customersv1 set addr = ?, "
              + "round = ? where (cust_name=? or since = ? ) and round < ?");
      updtCust.setString(1, "address is name16");
      updtCust.setInt(2, 1);
      updtCust.setString(3, "name16");
      updtCust.setDate(4, java.sql.Date.valueOf("2005-09-14"));
      updtCust.setInt(5, 1);
      updtCust.executeUpdate();

      // update networthv1
      updtNtw.setFloat(1, 505.2f);
      updtNtw.setInt(2, 1);
      updtNtw.setFloat(3, 144574.8f);
      updtNtw.setFloat(4, 9);
      updtNtw.setLong(5, 61);
      updtNtw.setInt(6, 1);
      updtNtw.executeUpdate();
      //
      ResultSet rs = st
          .executeQuery("select count(*) from networthv1 where c_cid = 45 or c_cid=44");
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      // delete from customers
      PreparedStatement deleteCust = conn
          .prepareStatement(" delete from customersv1 where cid in (? , ?)");
      deleteCust.setLong(1, 45);
      deleteCust.setLong(2, 44);
      try {
        deleteCust.executeUpdate();
        fail("delete should fail due to FK constraint violation");
      } catch (SQLException sqle) {
        if (sqle.getSQLState().indexOf("23503") == -1) {
          throw sqle;
        }

      }
    } finally {
      System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
      System.clearProperty("gemfire."
          + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
    }

  }

  public void testBug49995() throws Exception {
    try {
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
      Properties props1 = new Properties();
      int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
      props1.put("mcast-port", String.valueOf(mport));
      setupConnection(props1);
      Connection conn = TestUtil.getConnection(props1);
      Properties props = new Properties();
      props.setProperty("single-hop-enabled", "true");
      props.setProperty("single-hop-max-connections", "5");
      Connection connClient = startNetserverAndGetLocalNetConnection(props);
      Statement st = conn.createStatement();
      st.execute("drop table if exists history");
      st.execute("create table history(house_id integer, household_id integer, "
          + "plug_id integer, time_slice smallint, load_total float(23), "
          + "load_count integer) partition by column (house_id)");
      st.execute("create index history_idx on history (house_id, household_id, plug_id, time_slice)");
      st.execute("insert into history (house_id, household_id, plug_id, time_slice, load_total, load_count) "
          + "values(0, 8, 9, 190, 44.868, 1), (0, 8, 9, 191, 44.868, 1), (0, 8, 9, 192, 44.868, 1), "
          + "(0, 8, 9, 193, 44.868, 1), (0, 8, 9, 194, 44.868, 1)");
      Statement stclient = connClient.createStatement();
      stclient
          .execute("select * from history where house_id = 0 and household_id = 8 and plug_id = 9 and time_slice = 193");
      stclient
          .execute("explain select * from history where house_id = 0 and household_id = 8 and plug_id = 9");
      stclient
          .execute("select * from history where house_id = 0 and household_id = 8 and plug_id = 9  and time_slice = 193");
    } finally {
      System
          .setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "false");
    }
  }
  
  public void testBug48976() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create table trade.portfolio (cid int not null, sid int not null, "
        + "qty int not null, availQty int not null, subTotal decimal(30,20), "
        + "tid int, constraint portf_pk primary key (cid, sid), "
        + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty)) "
        + " partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), "
        + "VALUES (12, 13, 14, 15, 16, 17))  REDUNDANCY 2 PERSISTENT SYNCHRONOUS");
    
    st.execute("insert into trade.portfolio values(100, 100, 100, 100, 10.2, 100)");
    st.execute("create table trade.sellorders "
        + "(oid int not null constraint orders_pk primary key, "
        + "cid int, sid int, qty int, ask decimal (30, 20), order_time timestamp, status varchar(10), "
        + "tid int, constraint portf_fk foreign key (cid, sid) references trade.portfolio (cid, sid) on delete restrict, "
        + "constraint status_ch check (status in ('cancelled', 'open', 'filled')))  "
        + "partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), "
        + "VALUES (12, 13, 14, 15, 16, 17))  REDUNDANCY 2 PERSISTENT SYNCHRONOUS");
    st.execute("insert into trade.sellorders values(100, 100, 100, 100, 10.2, '2013-12-13 10:10:10', 'open', 100)");
    st.execute("select * from TRADE.SELLORDERS where OID >= 10  or CID >= 100");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(100, rs.getInt(1));
    assertFalse(rs.next());
  }
  
  public void testBug47329_more() throws Exception {

    Properties props = new Properties();

    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);

    props.put("mcast-port", String.valueOf(mcastPort));

    Connection conn = TestUtil.getConnection(props);

    // Connection conn = TestUtil.getConnection();

    Statement st = conn.createStatement();

    ResultSet rs = null;

    st.execute("create table t1 (c1 int primary key, c2 long varchar, c3 int)");
    st.execute("create index idx on t1(c2)");
    // st.execute("create index idx on t1(c2)");

    st.executeUpdate("insert into t1 values(0,'',3)");
    st.executeUpdate("insert into t1 values(1,' ',3)");
    st.executeUpdate("insert into t1 values(2,'aaaa',4)");
    st.executeUpdate("insert into t1 values(3,'aa aa',5)");
    st.executeUpdate("insert into t1 values(4,'aa  aa',6)");
    st.executeUpdate("insert into t1 values(5,'aa  ab',7)");
    st.executeUpdate("insert into t1 values(6,'aa  bb',8)");
    st.executeUpdate("insert into t1 values(7,'aa ab',9)");
    st.executeUpdate("insert into t1 values(8,'aa a~',9)");
    st.executeUpdate("insert into t1 values(9,'`a a@',9)");
    st.executeUpdate("insert into t1 values(10,'  aa  ',9)");
    int totalRows = 11;

    st.executeQuery("select * from t1 where c2 like '%'");
    rs = st.getResultSet();
    assertSize(rs, totalRows);

    st.executeQuery("select * from t1 where c2 like '%%'");
    rs = st.getResultSet();
    assertSize(rs, totalRows);

    PreparedStatement ps = conn.prepareStatement("select * from t1 where c2 like ?");
    ps.setString(1, "%a%");
    rs = ps.executeQuery();
    assertSize(rs, totalRows - 2);
    
    st.executeQuery("select * from t1 where c2 like '%a%'");
    rs = st.getResultSet();
    assertSize(rs, totalRows - 2);

    st.executeQuery("select * from t1 where c2 like '%aa%'");
    rs = st.getResultSet();
    assertSize(rs, totalRows - 3);
    
    st.executeQuery("select * from t1 where c2 like '%a a%'");
    rs = st.getResultSet();
    assertSize(rs, 4);

    st.executeQuery("select * from t1 where c2 like '%a  a%'");
    rs = st.getResultSet();
    assertSize(rs, 2);

    st.executeQuery("select * from t1 where c2 like '% %'");
    rs = st.getResultSet();
    assertSize(rs, 9);

    st.executeQuery("select * from t1 where c2 like '%  %'");
    rs = st.getResultSet();
    assertSize(rs, 4);

    st.executeQuery("select * from t1 where c2 like '  aa  '");
    rs = st.getResultSet();
    assertSize(rs, 1);

    st.executeQuery("select * from t1 where c2 like ' '");
    rs = st.getResultSet();
    assertSize(rs, 1);

    st.executeQuery("select * from t1 where c2 like '%  aa  '");
    rs = st.getResultSet();
    assertSize(rs, 1);
  }

  public void assertSize(ResultSet resultSet, int size) throws SQLException{
    int count = 0;
    for (;resultSet.next(); count++);
    assertEquals("ResultSet size mismatch", size, count);
  }



  public void _testBug46792() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Statement s = conn.createStatement();
    s.execute("create table t1 (c1 int, c2 int) partition by column(c1)");
    s.execute("create table empty (c1 int, c2 int) partition by column(c1)");
    s.execute("insert into t1 values (null,null),(1,1),(null,null),(2,1),(3,1),(10,10)");
    s.execute("select max((select c1 from empty)) from t1");
  }
  
  public void testBug48533() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    st.execute("create schema emp");
    st.execute("create table emp.EMPLOYEE(lastname varchar(30) primary key, depId int) "
        + "partition by (depId) redundancy 0");
    GemFireXDQueryObserverAdapter observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public boolean throwPutAllPartialException() {
        return true;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);
    PreparedStatement pstmnt = conn
        .prepareStatement("INSERT INTO emp.employee VALUES (?, ?)");
    for (int i = 0; i < 10; i++) {
      pstmnt.setString(1, "Jones"+i);
      pstmnt.setInt(2, i);
      pstmnt.addBatch();
    }
    try {
      pstmnt.executeBatch();
      fail("Test should not reach here");
    } catch (SQLException se) {
      System.out.println("state is: " + se.getSQLState());
      assertTrue("X0Z09".equals(se.getSQLState()));
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }
  
  public void testDeleteInPersistentRegion() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, subTotal decimal(30,20), tid int not null unique, constraint portf_pk primary key (cid, sid), "
        + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
        + " PERSISTENT SYNCHRONOUS");
    st.execute("insert into trade.portfolio values(938, 329, 756, 756, 49956.48000000000000000000, 1)");
    st.execute("delete from trade.portfolio where cid = 938 and sid = 329 and tid = 1");
    st.execute("insert into trade.portfolio values(938, 329, 756, 756, 49956.48000000000000000000, 1)");
  }
  
  public void testBug47329() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table t1 (c1 int primary key, c2  varchar(10))");
    st.execute("create index idx on  t1(c2)");
    
    PreparedStatement pstmt = conn.prepareStatement("insert into t1 values(?,?)");
    pstmt.setInt(1, 111);
    pstmt.setString(2, "aaaaa");
    pstmt.executeUpdate();
    
    pstmt.setInt(1, 222);
    pstmt.setString(2, "");
    pstmt.executeUpdate();

    st.execute("select c1 from t1 where c2 like '%a%'");
    rs = st.getResultSet();
    System.out.println("rs=" + rs);
    assertEquals(true, rs.next());
    assertEquals(111, rs.getInt(1));
  }

  public void testBug46979() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();

    st.execute("create table app.customers (ccid int) replicate");
    st.execute("create table app.networth (ncid int) partition by column (ncid)");

    st.execute("insert into app.customers values (11)");
    st.execute("insert into app.customers values (22)");
    st.execute("insert into app.customers values (33)");
    st.execute("insert into app.customers values (44)");
    st.execute("insert into app.customers values (55)");

    st.execute("insert into app.networth values (11)");
    st.execute("insert into app.networth values (22)");
    st.execute("insert into app.networth values (44)");
    st.execute("insert into app.networth values (55)");

    try {
      st.execute("select * from app.customers c left outer join "
          + "app.networth n on c.ccid = n.ncid where n.ncid is null");
      fail("Left outer join with RR and PR and IS NULL is not supported. "
          + "Expect exception 0A000.");
    } catch (SQLException sqle) {
      if (sqle instanceof DerbySQLException) {
        if (!((DerbySQLException)sqle).getMessageId().equals("0A000.S")) {
          throw sqle;
        }
      }
      else if (!"0A000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }
  
  public void testBug47464_NPE() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    
    st.execute("create table t1 (col1 int, col2 clob)");
    st.execute("insert into t1 values (123, cast('abc' as clob))");
    st.execute("create index idx on t1(col1)");
    st.execute("update t1 set col1 = 789 where col1 = 123");
  }
  
  public void test48074_TSMC() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    conn.createStatement().execute(
        "CREATE TABLE COMSPC.COM_SPC_SYS_MGMT_HIST" + "  ("
        + "   SYS_NAME VARCHAR(12 ) NOT NULL,"
        + "   OPER_TIME DATE DEFAULT CURRENT_DATE,"
        + "   FAB_NAME VARCHAR(12 ),"
        + "  PRIMARY KEY (SYS_NAME,OPER_TIME)"
        + ")PARTITION BY (cast( MONTH(OPER_TIME) AS CHAR(3))||SYS_NAME)"
        + "  REDUNDANCY 1" + "  PERSISTENT ASYNCHRONOUS");

    conn.createStatement()
      .execute(
          "insert into COMSPC.COM_SPC_SYS_MGMT_HIST (SYS_NAME, FAB_NAME) values ('1', '1')");
    conn.createStatement()
      .execute(
          "insert into COMSPC.COM_SPC_SYS_MGMT_HIST (SYS_NAME, FAB_NAME) values ('2', '2'), ('3', '3'), ('4', '4') ");

    ResultSet rs = conn
      .createStatement()
      .executeQuery(
          "select * from COMSPC.COM_SPC_SYS_MGMT_HIST where ( cast(MONTH(OPER_TIME) as char(3)) || SYS_NAME ) = (cast(MONTH(CURRENT_DATE) as char(3)) || '3') ");
    while (rs.next()) {
      assertEquals("3", rs.getString(1));
      assertEquals("3", rs.getString(3));
    }
    conn.close();
  }

  public void testVMRestart_Char() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("create table chartab (col1 char(5) primary key) persistent");
    st.execute("insert into chartab values ('one ')");
    shutDown();
    conn = TestUtil.getConnection();
    st = conn.createStatement();
    st.execute("select * from chartab where col1 = 'one'");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("one  ", rs.getString(1));
  }

  public void testVMRestart_Varchar() throws Exception {    
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("create table varchartab (col1 varchar(5) primary key) persistent");
    st.execute("insert into varchartab values ('one ')");
    shutDown();
    conn = TestUtil.getConnection();
    st = conn.createStatement();
    st.execute("select * from varchartab where col1 = 'one'");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("one ", rs.getString(1));
    rs.close();
    st.execute("select * from varchartab where col1 = 'one '");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("one ", rs.getString(1));
  }

  public void test48278_51000() throws Exception {
    reduceLogLevelForTest("config");

    Properties props = new Properties();
    props.setProperty("mcast-port", String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS)));
    Connection conn = getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create table t1 (col1 bigint)");
    final int numRows = 500001;
    PreparedStatement ps = conn.prepareStatement("insert into t1 values(?)");
    for (int col1 = 0; col1 < numRows; col1++) {
      ps.setLong(1, col1);
      ps.addBatch();
      if (col1 % 1000 == 0) {
        ps.executeBatch();
        conn.commit();
      }
    }
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, "Creating index idx");
    st.execute("create index idx on t1(col1)");
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, "Created index idx");
    ResultSet rs = st.executeQuery("select count(*) from t1 where col1 > 1");
    assertTrue(rs.next());
    assertEquals(numRows - 2, rs.getInt(1));
    assertFalse(rs.next());

    // check the index
    PreparedStatement pstmt = conn
        .prepareStatement("select col1 from t1 where col1=?");
    for (int col1 = 0; col1 < numRows; col1++) {
      pstmt.setLong(1, col1);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(col1, rs.getLong(1));
      assertFalse(rs.next());
      rs.close();
    }
  }

  public void testBug47671() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    try {
      st.execute("create table customers (id varchar(10) primary key, name char(40))");
      st.execute("insert into customers values ('', 'abc')");
      st.execute("insert into customers values ('', 'def')");
      fail("java.sql.SQLException(23505): primary key constraint violation is expected.");
    } catch (SQLException sqle) {
      if (!sqle.getSQLState().equals("23505")) {
        throw sqle;
      }
    }
  }
  
  public void testBug46933() throws Exception {
    //System.setProperty("gemfirexd.debug.true", "QueryDistribution");
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    st.execute("create table customers (id int primary key, name char(40))");
    st.execute("insert into customers values (123, 'abc')");
    st.execute("update customers set name = 'def' where id = 123");

    st.execute("select name from customers where id = 123");

    rs = st.getResultSet();
    while (rs.next()) {
      assertEquals(40, rs.getString(1).length());
    }
    
    PreparedStatement ps = conn.prepareStatement("update customers set name = ? where id = 123");
    ps.setString(1, null);
    ps.executeUpdate();
    st.execute("select name from customers where id = 123");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertNull(rs.getString(1));
  }
  
  public void testBug47148() throws Exception {   
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Connection conn2 = startNetserverAndGetLocalNetConnection();

    Statement stmt = conn.createStatement();
    ResultSet rs = null;

    stmt.execute("Create Table TEST_TABLE(idx numeric(12),"
        + "AccountID varchar(10)," + "OrderNo varchar(20),"
        + "primary key(idx)" + ")" + "PARTITION BY COLUMN ( AccountID )");

    stmt.execute("CREATE INDEX idx_AccountID ON test_Table (AccountID ASC)");
    stmt.execute("insert into test_table values(9, '8', '8')");

    String query = "select accountid from test_table where accountid like '8'";
    
    // check with client connection first
    Statement stmt2 = conn2.createStatement();
    rs = stmt2.executeQuery(query);
    assertTrue(rs.next());
    assertEquals("8", rs.getString(1));
    assertFalse(rs.next());

    // and with embedded driver
    rs = stmt.executeQuery(query);
    assertTrue(rs.next());
    assertEquals("8", rs.getString(1));
    assertFalse(rs.next());

    rs.close();
    stmt.close();
    stmt2.close();
    conn.close();
    conn2.close();
  }
  
  public void testBug49193_1() throws Exception {
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
      for (int i = 1; i < 20; ++i) {
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
    byte[] expectedBytes = {16, 32, 48, 32, 32, 32, 32, 32, 32, 32};
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
    byte[] expectedBytes2 = {16, 32, 48};
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

        BatchInsertObserver bos = new BatchInsertObserver();
        GemFireXDQueryObserverHolder.setInstance(bos);

        for (int type = 0; type < 6; type++) {
          String tableType;
          if (type % 3 == 0) {
            tableType = "partition by primary key";
          }
          else if (type % 3 == 1) {
            tableType = "partition by column (firstname)";
          }
          else {
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
          }
          else {
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
            }
            else if (lastName.equalsIgnoreCase("Rafferty")) {
              assertFalse(RaffertyDone);
              RaffertyDone = true;
            }
            else if (lastName.equalsIgnoreCase("Robinson")) {
              assertFalse(RobinsonDone);
              RobinsonDone = true;
            }
            else {
              fail("unexpected lastName: " + lastName);
            }
          }
          if (type < 3) {
            assertEquals(3, cnt);
          }
          else {
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

    byte[] expectedBytes = {65, 66, 67, 32, 32, 32, 32, 32, 32, 32};
    byte[] spaceBytes = {32, 32, 32, 32, 32, 32, 32, 32, 32, 32};
    byte[] varcharBytes = {65, 66, 67};
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
    byte[] expected = {65, 66, 68, 32, 32, 32, 32, 32, 32, 32};
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
    byte[] expectedVarchar = {65, 66, 68};
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
	    while(rs.next()) {
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
	    for (int i = 1; i < 10 ; ++i) {
	      ps1.setInt(1, i*11); //cid
	      ps1.setInt(2, i*11+1);  //sid
	      ps1.setInt(3, i*11+2); //tid
	      ps1.executeUpdate();
	    }
	    
	    PreparedStatement ps2 = conn
	        .prepareStatement("insert into trade.networth values (?, ?, ?)");
	    for (int i = 1; i < 10; ++i) {
	      ps2.setInt(1, i*11); //cid
	      ps2.setInt(2, i*11+1); //securities
	      ps2.setInt(3, i*11+2); //tid
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

    int[] numRows = new int[] {4, 6, 6, 6, 6 };
    
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
          }
          else {
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
          + BugsTest.class.getName() + ".retrieveDynamicResults' "
          + "DYNAMIC RESULT SETS 2";
  
  public static void retrieveDynamicResults(int arg, ResultSet [] rs1, ResultSet [] rs2, ProcedureExecutionContext ctx) {
    Connection conn = ctx.getConnection();
    try {
      PreparedStatement ps1 = conn.prepareStatement("<local> select * from t1 where balance2 > ?");
      ps1.setInt(1, arg);
      rs1[0] = ps1.executeQuery();
      
      PreparedStatement ps2 = conn.prepareStatement("<local> select * from t2 where balance2 > ?");
      ps2.setInt(1, arg);
      GemFireXDQueryObserverHolder.setInstance(new ReprepareObserver());
      rs2[0] = ps2.executeQuery();
    }
    catch(SQLException e) {
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
      while ( rs1.next() ) {
        System.out.println(rs1.getInt(1) + " : " + rs1.getInt(2));
      }
      
//      PreparedStatement ps2 = conn.prepareStatement("select * from t2 where balance2 > ?");
//      ps2.setInt(1, 0);
//
//      ps2.executeQuery();
    }
    catch(SQLException e) {
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
    }
    catch(SQLException e) {
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
              + "LANGUAGE JAVA EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.BugsTest.proc'");
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
              + "LANGUAGE JAVA EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.BugsTest.proc'");
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
              }else */if (gfContainer.getRegion().getName().toLowerCase()
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
              }
              else if (memIndex.getBaseContainer().getRegion().getName()
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
              + "LANGUAGE JAVA EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.BugsTest.proc1'");
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
          new Integer(2), null, null, null, null, Boolean.FALSE,  Boolean.FALSE, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl);
      JdbcTestBase.startAsyncEventListener("WBCL1");

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1+ "AsyncEventListener(WBCL1)");
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
            synchronized (BugsTest.this) {
              ok[0] = true;
              BugsTest.this.notify();
            }
          }
        }

        @Override
        public void afterPKBasedDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps) {
          if (isolation == Connection.TRANSACTION_NONE
              && type.equals(Event.Type.AFTER_DELETE)) {

            synchronized (BugsTest.this) {
              ok[0] = true;
              BugsTest.this.notify();
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
          new Integer(2), null, null, null, null,null, null, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl,conn);
      JdbcTestBase.startAsyncEventListener("WBCL1");
      AsyncEventQueueImpl queue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("WBCL1");
      assertNotNull(queue);
      assertTrue(queue.isRunning());
    }
    finally {
      TestUtil.stopNetServer();
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
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
          new Integer(2), null, Boolean.TRUE, null, null,null, null, null, 
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl,conn);
      JdbcTestBase.startAsyncEventListener("WBCL1");
      AsyncEventQueueImpl queue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("WBCL1");
      assertNotNull(queue);
      assertTrue(queue.getSender().getCancelCriterion()
          .cancelInProgress() == null);
    }
    finally {
      TestUtil.stopNetServer();
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
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
    for( int i = 1;i <31 ; ++i) {
      ps.setInt(1, i);
      ps.setString(2, "name"+i);
      ps.setInt(3, i);
      ps.executeUpdate();
    }    
    TestUtil.shutDown();   
    TestUtil.deletePersistentFiles = true;
    TestUtil.setupConnection();
    conn = TestUtil.getConnection();
    stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("select * from trade.customers");   
    int expected = 465 ;
    int actual = 0;
    while(rs.next()) {
      int val = rs.getInt(1);
      actual +=val;
    }
    assertEquals(expected,actual);
  }

  public void testBug41804() throws Exception{

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
      assertEquals(1,rs.getInt(1));
      
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
    s .execute("create table numeric_family (id int primary key, type_int int)");
    s .execute("insert into numeric_family values (1, 2), (2, 4), (3, 6), (4, 8)");
    
    try {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            { 
            }              
          });
    
      String query = "select id from numeric_family where id <= 4 order by id asc fetch " +
      		"first 1 rows only";
      s.execute(query);
    
      
    }    
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }     
  }
  
  public void testBug41936() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s .execute("create table numeric_family (id int primary key, type_int int)");
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
    assertEquals(1,rs.getInt(1));
    assertEquals(2,rs.getInt(2));  
    assertEquals(3,rs.getInt(3));  
    assertEquals(4,rs.getInt(4));  
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
    assertEquals(1,rs.getInt(1));
    assertEquals(2,rs.getInt(2));  
    assertEquals(3,rs.getInt(3));  
    assertEquals(4,rs.getInt(4));  
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
    assertEquals(1,rs.getInt(1));
    assertEquals(2,rs.getInt(2));  
    assertEquals(3,rs.getInt(3));  
    assertEquals(4,rs.getInt(4));  
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
    assertEquals(1,rs.getInt(1));
    assertEquals(2,rs.getInt(2));  
    assertEquals(3,rs.getInt(3));  
    assertEquals(4,rs.getInt(4));  
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
  public void testBug42099_3() throws Exception
  {
    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
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
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return 1;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);
    try {
      ResultSet rs = st.executeQuery("Select * from t1 where c6 =1");     
      validateForBug42099(rs, false /* expect TX data*/);
      conn.commit();
    }
    finally {
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
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",  new Integer(
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
          }
          else if (isolation != Connection.TRANSACTION_NONE
              && dml.startsWith("delete from ")) {
            numPKDeletes[0] += numRowsModified;
            synchronized (BugsTest.this) {
              ok[0] = true;
              BugsTest.this.notify();
            }
          }
        }

        @Override
        public void afterPKBasedDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps) {
          if (isolation == Connection.TRANSACTION_NONE
              && type.equals(Event.Type.AFTER_DELETE)) {
            numPKDeletes[0] += numRowsModified;
            synchronized (BugsTest.this) {
              BugsTest.this.notify();
              ok[0] = true;
            }
          }
          else if (type.equals(Event.Type.AFTER_INSERT)) {
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
        public void beforeCreate(EntryEvent event) throws CacheWriterException
        {
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
    }
    finally {
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
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",  new Integer(
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
          }
          else if (isolation != Connection.TRANSACTION_NONE
              && dml.startsWith("delete from ")) {
            numPKDeletes[0] += numRowsModified;
            synchronized (BugsTest.this) {
              ok[0] = true;
              BugsTest.this.notify();
            }
          }
        }

        @Override
        public void afterPKBasedDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps) {
          if (isolation == Connection.TRANSACTION_NONE
              && type.equals(Event.Type.AFTER_DELETE)) {
            numPKDeletes[0] += numRowsModified;
            synchronized (BugsTest.this) {
              ok[0] = true;
              BugsTest.this.notify();
            }
          }
          else if (type.equals(Event.Type.AFTER_INSERT)) {
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
        public void beforeCreate(EntryEvent event) throws CacheWriterException
        {
          callbackExecuted[0] = true;
          Object callbackArg = event.getCallbackArgument();
          assert callbackArg != null
              && callbackArg instanceof GfxdCallbackArgument: callbackArg;
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
    }
    finally {
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
  
  private void validateForBug42099(ResultSet rs, boolean expectCommittedData)
      throws SQLException
  {
    rs.next();
    if (expectCommittedData) {
      assertEquals(1, rs.getInt(1));
      assertEquals(1, rs.getInt(2));
      assertEquals(1, rs.getInt(3));
      assertEquals(1, rs.getInt(4));
      assertEquals(1, rs.getInt(5));
    }
    else {
      assertEquals(1, rs.getInt(1));
      assertEquals(2, rs.getInt(2));
      assertEquals(3, rs.getInt(3));
      assertEquals(4, rs.getInt(4));
      assertEquals(5, rs.getInt(5));
    }
  }
  
  public void testBug42100() throws Exception {

    java.sql.Connection conn = getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement st = conn.createStatement();
    st .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
                " c4 int not null , c5 int not null ," +
                " primary key(c1)) ");
    st.execute("create index i1 on t1 (c5)");
    conn.commit();
    st.execute("insert into t1 values (1, 1,1,1,1)");
    conn.commit();
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
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
    st .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null," +
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
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerCostForMemHeapScan(
          GemFireContainer gfContainer, double optimzerEvalutatedCost)
      {
        return Double.MAX_VALUE;
      }

      @Override
      public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
          OpenMemIndex memIndex, double optimzerEvalutatedCost)
      {
        return 1;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);   
    assertEquals(1,st.executeUpdate("update t1 set c5 =5 where c5 =1"));
    assertEquals(1,st.executeUpdate("update t1 set c5 =6 where c5 = 5"));
    conn.commit();
    try {
      ResultSet rs = st.executeQuery("Select c5 from t1 where c1 =1");     
      assertTrue(rs.next());
      assertEquals(6,rs.getInt(1));
      conn.commit();
    }
    finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  
  }

  /**
   * Bug discovered during debugging of 42323
   * 
   * RENAME not yet supported.
   * 
   * @throws Exception
   */
  public void DISABLED_testNewBug_42323_1() throws Exception
  {
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
      }
      else if (indexName.equals("INDEX7_4")) {
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
      }
      else if (indexName.equals("INDEX7_4")) {
        foundOld = true;
      }
    }
    assertTrue(foundNew);
    assertFalse(foundOld);
  }

  /**
   * RENAME not yet supported.
   */
  public void DISABLED_testNewBug_42323_2() throws Exception
  {
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
      }
      else if (indexName.equals("INDEX7_4")) {
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
    String[][] values = new String[][]{  
        {"1","abc","def"},
        {"2","ghi","jkl"},
        {"3","mno","pqr"},
        {"4","stu","vwx"},
        {"5","yza","bcd"},        
        {"8","abc dkp","hij"},
        {"9","ab","hij"},
        {"9","abcd efg","hij"},
    };
    for (int i = 0; i < values.length; i++) {
      String elements[] = values[i];
      for (int j = 0; j <= 2; j++) {
        insert.setString(j+1, elements[j]);
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
    ResultSet rs  = null;
    int count =0;
    ps1.setObject(1, "");
    rs = ps1.executeQuery();
    rs.next();
    ps1.setObject(1, "%");
    rs = ps1.executeQuery();
    count=0;
    while(rs.next()) {
      ++count;  
    }
    assertEquals(count,9);
    
    ps1.setObject(1, "ab%");
    rs = ps1.executeQuery();
    count=0;
    while(rs.next()) {
      ++count;  
    }
    assertEquals(count,4);
    rs.close();
    PreparedStatement ps2 = conn.prepareStatement("select id from test where vc10 like ?"); 
    count =0;
    ps2.setObject(1, "%");
    rs = ps2.executeQuery();
    while(rs.next()) {
      ++count;  
    }
    assertEquals(count,9);
    
    rs.close();
    }finally {
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
     ((Integer) spr.getRoutingKeyForColumn(new SQLInteger(55))).intValue();
     PreparedStatement insert = getConnection().prepareStatement(
     "insert into customers values (?,?,?,?)");
     insert.setInt(1, 97);
     insert.setString(2, "name_97");
     insert.setString(3, "addr_97");
     insert.setInt(4, 55);
     insert.executeUpdate();
     ResultSet rs = st.executeQuery("select  addr, cust_name from customers where  tid = 55");
     assertTrue(rs.next());
    }finally {
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
     st.executeUpdate(" CREATE TABLE \"owners\"(\"id\" INTEGER NOT NULL,\"first_name\" VARCHAR(30),"+
    " \"last_name\" VARCHAR(30), \"address\" VARCHAR(255),    \"city\" VARCHAR(80), " +
    " \"telephone\" VARCHAR(20),   PRIMARY KEY (\"id\")) ");
   
     
     st.executeUpdate(" CREATE TABLE \"pets\"(    \"id\" INTEGER NOT NULL,    \"name\" VARCHAR(30),"+
    " \"birth_date\" DATE,    \"type_id\" INTEGER NOT NULL,    \"owner_id\" INTEGER NOT NULL,    " +
    "PRIMARY KEY (\"id\"))");
     
     
     st.executeUpdate(" ALTER TABLE \"pets\"  ADD CONSTRAINT \"pets_ibfk_1\" " +
                "FOREIGN KEY (\"owner_id\") REFERENCES \"owners\" (\"id\") ");
    }catch(SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
    }finally {
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
     st.executeUpdate(" CREATE TABLE \"owners\"(\"id\" INTEGER NOT NULL,\"first_name\" VARCHAR(30),"+
    " \"last_name\" VARCHAR(30), \"address\" VARCHAR(255),    \"city\" VARCHAR(80), " +
    " \"telephone\" VARCHAR(20),   PRIMARY KEY (\"id\")) ");
   
     
     st.executeUpdate(" CREATE TABLE \"pets\"(    \"id\" INTEGER NOT NULL,    \"name\" VARCHAR(30),"+
    " \"birth_date\" DATE,    \"type_id\" INTEGER NOT NULL,    \"owner_id\" INTEGER NOT NULL,    " +
    "PRIMARY KEY (\"id\"))");
     
     
     st.executeUpdate(" ALTER TABLE \"pets\"  ADD CONSTRAINT \"pets_ibfk_1\" " +
                "FOREIGN KEY (\"owner_id\") REFERENCES \"owners\" (\"id\") ");
     
     st.executeQuery("select * from \"pets\"");
    }catch(SQLException sqle) {
      sqle.printStackTrace();
      throw sqle;
 
    }finally {
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
        ps.setTimestamp(2, Timestamp.valueOf(timestamp + i +time));
      }
      else {
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
    while(rs.next()) {
      cnt++;
      assertEquals("2005-01-31 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);
    st.execute("update syn2ForSale set timestamp = '2006-01-31 00:00:00' where id = 1");
    
    st.execute("select max(timestamp) from synForSale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals("2006-01-31 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);
    
    st.execute("delete from synForSale");
    
    st.execute("select * from syn2ForSale");
    rs = st.getResultSet();
    while(rs.next()) {
      fail("no result should come");
    }
  }

  public void testMaxBugcshanklin_43418() throws Exception {
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
        ps.setTimestamp(2, Timestamp.valueOf(timestamp + i +time));
      }
      else {
        ps.setTimestamp(2, Timestamp.valueOf(timestamp + "0" + i + time));
      }
      ps.setInt(3, i % 2);
      int cnt = ps.executeUpdate();
      assertEquals(1, cnt);
    }
    
    st.execute("select max(timestamp) from sale");
    ResultSet rs = st.getResultSet();
    int cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals("2005-01-31 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);
    
    st.execute("select min(timestamp) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals("2005-01-01 00:00:00.0", rs.getTimestamp(1).toString());
    }
    assertEquals(1, cnt);
    
    st.execute("create index tdxMult on sale(id, timestamp)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);
    
    st.execute("drop index tdxMult");
    
    st.execute("create index tdx2 on sale(id asc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
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
    while(rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);
    
    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(id desc, timestamp asc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);
    
    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(timestamp desc, id asc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);
    
    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(timestamp asc, id desc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
      cnt++;
      assertEquals(31, rs.getInt(1));
    }
    assertEquals(1, cnt);
    
    st.execute("drop index tdxMult");
    st.execute("create index tdxMult on sale(timestamp desc, id desc)");
    st.execute("select max(id) from sale");
    rs = st.getResultSet();
    cnt = 0;
    while(rs.next()) {
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
    String[][] values = new String[][]{  
        {"6","efg","hij"},
        {"7","abcdefg","hijklm"},
      
    };
    for (int i = 0; i < values.length; i++) {
      String elements[] = values[i];
      for (int j = 0; j <= 2; j++) {
        insert.setString(j+1, elements[j]);
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
    ResultSet rs  = null;
    int count =0;
    ps.setObject(1, "%fg");
    rs = ps.executeQuery();
    count=0;
    while(rs.next()) {
      ++count;  
    }
    assertEquals(count,2);
    
    
    rs.close();
    }finally {
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
      }
      else {
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
        + "external name 'com.pivotal.gemfirexd.jdbc.BugsTest.myhash' "
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
        while(rs.next()) ;
      }
      catch(SQLException sqle) {
        assertTrue("XJ001".equals(sqle.getSQLState()));
        Throwable t = sqle.getCause();
        do {
          if(t instanceof NullPointerException) {
            break;
          }
        }
        while( (t = t.getCause()) != null);
        
        if(t == null || !(t instanceof NullPointerException)) {
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
        while(rs.next()) ;
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
        while(r.next()) ;
        r.close();
        s.execute("call syscs_util.set_explain_connection(0)");
        
        ResultSet rp = s.executeQuery("select stmt_id from sys.statementplans");
        while(rp.next()) {
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
      }
      else if (c == 2) {
        stmt.execute("create index idxprice on trade.securities(PRICE desc)");
      }
      else if (c == 3) {
        stmt.execute("create index idxprice on trade.securities(PRICE asc)");
        stmt.execute("create index idxprice2 on trade.securities(PRICE desc)");
      }
      else {
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

        if (cidIncrementor % 5 ==0) {
          
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
      if( rsDerby != null)
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
        psInsert4.setInt(6, i*3);
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
    String index3= "create index txhistory_sid on txhistory(sid)";
    String index4= "create index txhistory_oid on txhistory(oid)";
    String index5= "create index txhistory_tid on txhistory(tid)";
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
      stmt.execute(table );
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
    String exchanges[] = {"nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse"};
    ResultSet rs = null;
    st.execute("create table trade.securities (sec_id int not null, exchange varchar(10) not null,  tid int, constraint sec_pk primary key (sec_id), "
        + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate");
    
    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?)");
    final int sec_limit = 200;
    for ( int i =0 ; i < sec_limit; ++i) {
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
    for ( int i =1 ; i < cust_limit; ++i) {
      psCust.setInt(1, i);
      int sec = random.nextInt(sec_limit);
      pfMap.put(i, sec);
      psCust.setInt(2, sec);
      psCust.setString(3, ""+i);
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
    

    for (Map.Entry<Integer, Integer> entry:pfMap.entrySet()) {
      psPF.setInt(1, entry.getKey());
      psPF.setInt(2, entry.getValue());
      psPF.setInt(3, 1000);
      psPF.setInt(4, 1);
      psPF.executeUpdate();
    }
    final boolean indexUsed[] =new boolean []{false};
    
    GemFireXDQueryObserverHolder.setInstance( new GemFireXDQueryObserverAdapter (){
      
      
      @Override
      public void scanControllerOpened(Object sc, Conglomerate conglom) {
        GemFireContainer container;
        if( conglom instanceof  SortedMap2Index   ) {
          SortedMap2Index sortedIndex  = (SortedMap2Index) conglom;
           container = sortedIndex.getGemFireContainer();
           if(container.isLocalIndex() && container.getName().equals("TRADE.6__PORTFOLIO__CID:base-table:TRADE.PORTFOLIO")) {
             indexUsed[0] = true;
           }
        }
      }
    });
     rs = st.executeQuery(query);
     int count = 0;
     while(rs.next()) {
       rs.getInt(1);
       ++count;
     }
     assertTrue(indexUsed[0]);
     assertTrue(count > 5);
    }finally {
      
      
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
    assertTrue(lds.get(1)==null);
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
    }catch(SQLException sqle) {
      if(sqle.getSQLState().equals("0A000")) {
        //Ok
      }else {
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
    }catch(SQLException sqle) {
      if(sqle.getSQLState().equals("0A000")) {
        //Ok
      }else {
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
    }catch(SQLException sqle) {
      if(sqle.getSQLState().equals("0A000")) {
        //Ok
      }else {
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
    String  indexScript= resourcesDir
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
    long start, end ,timeTakenInSec; 
    int numRows =0;
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
        + ".procSecuritasBug' "     ;
    
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
        + ".procSecuritasBug_2' DYNAMIC RESULT SETS 1"     ;
    
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
    while(rs.next()) {
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
        psInsert4.setFloat(3, i*131.46f);
        psInsert4.setString(4, exchange[(-1 * -5) % 7]);
        psInsert4.setInt(5, 2);
        psInsert4.setDate(6, new Date(System.currentTimeMillis()));
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
          if(memIndex.getGemFireContainer().getTableName().toLowerCase().indexOf("sec_price") != -1) {
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
      while(rs.next()) {
       rs.getInt(1);
       
        ++numRows;
      }
      assertEquals(30,numRows);
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
        psInsert4.setFloat(3, i*131.46f);
        psInsert4.setString(4, exchange[(-1 * -5) % 7]);
        psInsert4.setInt(5, i*-2);
        psInsert4.setDate(6, new Date(System.currentTimeMillis()));
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
      while(rs.next()) {
       rs.getInt(1);
       
        ++numRows;
      }
      assertEquals(2,numRows);
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
    }
    finally {
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

  public static int myhash(int value) {
    return value;
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

  public static Integer maxFunc(Integer val1, Integer val2) throws SQLException {
    return val1 > val2 ? val1 : val2;
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    this.exceptionOccured = false;
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
        }
        else {
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

          }
          else if (rand.nextInt(10) == 0) {
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

  public static void proc3(ProcedureExecutionContext ctx)
      throws SQLException, InterruptedException {
    Connection c = ctx.getConnection();
    Statement s = c.createStatement();
    s.execute("insert into mytable values(2)");
    s.close();
    c.close();
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
        + BugsTest.class.getName() + ".proc3'");
    
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
    while(rs.next()) {
      String v = rs.getString(1);
      assertTrue(v.equals("type 1") || v.equals("type 2") || v.equals("long string"));
    }
    rs.close();
    
    rs = st.executeQuery("select decode (p_type, 'typ1', 'TYPE ONE', 'typ2', 'TYPE ID TWO', 'typ3', 'TYPE3') "
        + " from product");
    int typ1 = 0, typ2 = 0, typ3 = 0, others = 0;
    while(rs.next()) {
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
    for(int i = 0; i < 100; i++) {
      ins.setInt(1, i);
      ins.setString(2, (i%2==0 ? "ATMOS": (i%3==0? "CENTERA": "VENTURA CAP")));
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
    while(rs.next()) {
      final String fmly = rs.getString(2);
      if ("ATMOS".equals(fmly) || "CENTERA".equals(fmly)) {
        foundRows++;
        assertTrue("FOUND".equals(rs.getString(1)));
      }
      else {
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
    String query = "SELECT "+
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
  while(rs.next());
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
    while(rs.next()) {
      int count = rs.getInt(1);
      assertEquals(0, count);
      System.out.println("count1 " +  rs.getInt(1));
    }
    
    st.execute("select count(*) from rti.test_table tt");
    rs = st.getResultSet();
    while(rs.next()) {
      int count = rs.getInt(1);
      assertEquals(2, count);
      System.out.println("count2 " +  rs.getInt(1));
    }
    
    st.execute("select count(*) from rti.test_table tt where tt.identifier is not null");
    rs = st.getResultSet();
    while(rs.next()) {
      int count = rs.getInt(1);
      assertEquals(2, count);
    }
    
    st.execute("select * from rti.test_table tt where tt.identifier is null");
    rs = st.getResultSet();
    int c = 0;
    while(rs.next()) {
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
