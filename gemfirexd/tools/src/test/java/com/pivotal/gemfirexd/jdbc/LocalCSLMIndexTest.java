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

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.internal.cache.AbstractRegionEntry;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.MemIndexScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2IndexScanController;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;

/**
 * junit tests for the CNM2Index
 * 
 * @todo investigate the problem that the result set is closed after
 *        accessing the run time statistics. 
 * @author yjing
 *
 */
public class LocalCSLMIndexTest extends JdbcTestBase {

  public LocalCSLMIndexTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(LocalCSLMIndexTest.class));
  }

  protected void cleanUp() throws Exception {
    Connection conn = jdbcConn;
    if (conn == null) conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("drop index if exists i2");
    stmt.execute("drop table if exists t1");
    stmt.execute("drop table if exists trade.securities");
    stmt.execute("drop table if exists table_data");
  }

  @Override
  protected void setUp() throws Exception {
    GemFireXDQueryObserverHolder.clearInstance();
    super.setUp();
    cleanUp();
  }

  @Override
  protected void tearDown() throws Exception {
    GemFireXDQueryObserverHolder.clearInstance();
    cleanUp();
    super.tearDown();
  }

  /**
   * create an local index after the table is populated with data.
   * 
   * @throws SQLException
   */

  public void testLoadBulkDataFromBaseTable() throws SQLException {

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))" +getSuffix());
    s.execute("insert into t1 (c1, c2, c3) values (10, 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values (20, 20, 'YYYY')");
    s.execute("create index i1 on t1 (c2, c3)");
    s.execute("insert into t1 (c1, c2, c3) values (30, 10, 'AAAA')");
    s.execute("insert into t1 (c1, c2, c3) values (40, 20, 'AAAA')");
    
    

 

    String[][] expectedRows = {{ "30", "10", "AAAA" }, { "10", "10", "YYYY" }, };

    // s.execute("call SYSCS_UTIL.SET_RUNTIMESTATISTICS(1)");
    ResultSet rs = s.executeQuery("select * from t1 where t1.c2=10 ");
    //
    // RuntimeStatisticsParser rsp=SQLUtilities.getRuntimeStatisticsParser(s);
    // RuntimeStatisticsParser
    // rsp=SQLUtilities.executeAndGetRuntimeStatistics(conn, "select * from t1
    // where t1.c2=10");
    // assertTrue("Using the Index to locate row!",rsp.usedIndexScan());
    JDBC.assertFullResultSet(rs, expectedRows);
    conn.close();
  }

  /**
   * test the index containing items with the same key value.
   * 
   * @throws SQLException
   */

  public void testIndexWithDuplicateKey() throws SQLException {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }


    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());

    s.execute("insert into t1 (c1, c2, c3) values (10, 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values (20, 10, 'YYYY')");

    s.execute("create index i1 on t1 (c2,c3)");

    String[][] expectedRows = { { "10", "YYYY" }, { "10", "YYYY" } };

    // s.execute("call SYSCS_UTIL.SET_RUNTIMESTATISTICS(1)");
    ResultSet rs = s
        .executeQuery("select c2, c3 from t1 where t1.c2=10 and t1.c3='YYYY' for update");
    //
    // RuntimeStatisticsParser rsp=SQLUtilities.getRuntimeStatisticsParser(s);
    // RuntimeStatisticsParser
    // rsp=SQLUtilities.executeAndGetRuntimeStatistics(conn, "select * from t1
    // where t1.c2=10");
    // assertTrue("Using the Index to locate row!",rsp.usedIndexScan());
    JDBC.assertFullResultSet(rs, expectedRows);
    conn.close();
  }
  
  /**
   * test the index containing items with the same key value.
   * 
   * @throws SQLException
   */

  public void testDuplicateIndexes() throws SQLException {

    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());
    s.execute("create index i1 on t1 (c2,c3)");
    
    s.execute("create index i2 on t1 (c2,c3)");
    
    s.execute("insert into t1 (c1, c2, c3) values (10, 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values (20, 20, 'YYYY')");

    String[][] expectedRows = { {"10", "10", "YYYY" }};

  
    ResultSet rs = s
        .executeQuery("select * from t1 where t1.c2=10 and t1.c3='YYYY' for update");
    
    
    JDBC.assertFullResultSet(rs, expectedRows);
    try {
      Monitor.getStream().println(
          "<ExpectedException action=add>"
              + "java.sql.SQLSyntaxErrorException"
              + "</ExpectedException>");
       Monitor.getStream().flush();
       
       s.execute("DROP INDEX i2");
    
    }catch (Exception e) {
    
    }
    finally {
      Monitor.getStream().println("<ExpectedException action=remove>"
          + "java.sql.SQLSyntaxErrorException"
          + "</ExpectedException>");
      Monitor.getStream().flush();  
    }
    conn.close();
    
   //  s.execute("call SYSCS_UTIL.SET_RUNTIMESTATISTICS(1)");
    // RuntimeStatisticsParser rsp=SQLUtilities.getRuntimeStatisticsParser(s);
   //  RuntimeStatisticsParser
   //  rsp=SQLUtilities.executeAndGetRuntimeStatistics(conn, "select * from t1 where t1.c2=10 and t1.c3='YYYY' for update");
   //  assertTrue("Using the Index to locate row!",rsp.usedIndexScan());    
   
  }

  /**
   * test the index for partial key search
   * 
   * @throws SQLException
   */

  public void testIndexWithPartialKeyMatch() throws SQLException {

    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }
    
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());

    s.execute("insert into t1 (c1, c2, c3) values (10, 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values (20, 10, 'AAAA')");

    s.execute("create index i1 on t1 (c2,c3)");

    // s.execute("call SYSCS_UTIL.SET_RUNTIMESTATISTICS(1)");
    ResultSet rs = s.executeQuery("select * from t1 where t1.c2=10 for update");
    //
    // RuntimeStatisticsParser rsp=SQLUtilities.getRuntimeStatisticsParser(s);
    // RuntimeStatisticsParser
    // rsp=SQLUtilities.executeAndGetRuntimeStatistics(conn, "select * from t1
    // where t1.c2=10");
    // assertTrue("Using the Index to locate row!",rsp.usedIndexScan());
    JDBC.assertDrainResults(rs, 2);

  }

  /**
   * Test the index delete of two rows with the duplicate key value.
   * 
   * @throws SQLException
   */

  public void testIndexDeleteWithAllDuplicateKey() throws SQLException {
    
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());

    s.execute("insert into t1 (c1, c2, c3) values (10, 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values (20, 10, 'YYYY')");

    s.execute("create index i1 on t1 (c2,c3)");
    s.execute("delete from t1 where t1.c2=10");

    // s.execute("call SYSCS_UTIL.SET_RUNTIMESTATISTICS(1)");
    ResultSet rs = s.executeQuery("select * from t1 where t1.c2=10 for update");
    //
    // RuntimeStatisticsParser rsp=SQLUtilities.getRuntimeStatisticsParser(s);
    // RuntimeStatisticsParser
    // rsp=SQLUtilities.executeAndGetRuntimeStatistics(conn, "select * from t1
    // where t1.c2=10");
    // assertTrue("Using the Index to locate row!",rsp.usedIndexScan());
    JDBC.assertDrainResults(rs, 0);

  }

  /**
   * Test the index delete of two rows with the duplicate key value.
   * 
   * @throws SQLException
   */

 public void testIndexDeleteWithOneDuplicateKey() throws SQLException {
   
   // Bug #51838
   if (isTransactional && getSuffix().contains("offheap")) {
     return;
   }


    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());

    s.execute("insert into t1 (c1, c2, c3) values (10, 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values (20, 10, 'YYYY')");

    s.execute("create index i1 on t1 (c2,c3)");

    String[][] expectedRows = { { "10", "10", "YYYY" },

    };

    s.execute("delete  from t1 where t1.c1=20");
    // s.execute("call SYSCS_UTIL.SET_RUNTIMESTATISTICS(1)");
    ResultSet rs = s.executeQuery("Select * from t1 where t1.c2=10");
    //
    // RuntimeStatisticsParser rsp=SQLUtilities.getRuntimeStatisticsParser(s);
    // RuntimeStatisticsParser
    // rsp=SQLUtilities.executeAndGetRuntimeStatistics(conn, "select * from t1
    // where t1.c2=10");
    // assertTrue("Using the Index to locate row!",rsp.usedIndexScan());
    JDBC.assertFullResultSet(rs, expectedRows);

  }

  public void testDeleteEntryFromIndex() throws Exception {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());

    s.execute("insert into t1 (c1, c2, c3) values (10, 10, 'YYYY')");
    s.execute("insert into t1 (c1, c2, c3) values (20, 20, 'YYYY')");

    s.execute("create index i1 on t1 (c2,c3)");
    s.execute("delete  from t1 where c1=10");
    this.doOffHeapValidations();
    String[][] expectedRows = { { "20", "20", "YYYY" }};
    ResultSet rs = s.executeQuery("select * from t1 where t1.c2=20");
    JDBC.assertFullResultSet(rs, expectedRows);
    // int num=0;
    // while(rs.next())
    // ++num;
    // assertTrue("No rows in ResultSet", num==1);

  }
  
  /*s.execute("Create table brokers (id int not null, name varchar(200))");
  s.execute("insert into brokers values( 12,'sdakfsdkfl')");
  s.execute("insert into brokers values( 13,'sdakfsdkfl1')");
  s.execute("insert into brokers values( 14,'sdakfsdkfl2')");
  s.execute("insert into brokers values( 15,'sdakfsdkfl3')");
  s.execute("Create Index idi on brokers(id)");
  ResultSet rs=s.executeQuery("Select id from brokers");
  JDBC.assertDrainResults(rs, 4);      */
  
  public void testSingularIndex() throws SQLException {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

   Connection conn = getConnection();
  
   Statement s = conn.createStatement();
   s.execute("Create table t1 (c1 int primary key, " +
   		"c2 SMALLINT, " +
   		"c3 BIGINT," +
   		"c4 INTEGER,"+
   		"c5 REAL," +
   		"c6 DOUBLE,"+
   		"c7 FLOAT,"+
   		"c8 DEC(5,2),"+
   		"c9 CHAR(8), "+
   		"c10 VARCHAR(10), "+
   		"c11 DATE, "+
   		"c12 TIMESTAMP)"+getSuffix());
  
   
   s.execute("insert into t1 VALUES ( 1,"+
       "148,"+
       "1024,"+
       "23456,"+
       "1.234,"+
       "23.45678,"+
       "12345.78,"+
       "34.45,"+
       "'abcd',"+
       "'jdhfjfjff',"+
       "'2008-08-06',"+
       "'2008-08-06 10:49:24')");
   s.execute("insert into t1 VALUES ( 2,"+
       "148,"+
       "1024,"+
       "23456,"+
       "1.234,"+
       "23.45678,"+
       "12345.78,"+
       "34.45,"+
       "'abcd',"+
       "'jdhfjfjff',"+
       "'2008-08-06',"+
       "'2008-08-06 10:49:24')");
   s.execute("insert into t1 VALUES ( 3,"+
       "149,"+
       "1025,"+
       "28456,"+
       "1.236,"+
       "23.4567,"+
       "1234.78,"+
       "37.45,"+
       "'abefg',"+
       "'jdhf',"+
       "'2008-08-07',"+
       "'2008-08-07 10:49:24')");
   
    s.execute("CREATE INDEX i2 on t1 (c2)");
    s.execute("CREATE INDEX i3 on t1 (c3)");
    s.execute("CREATE INDEX i4 on t1 (c4)");
    s.execute("CREATE INDEX i5 on t1 (c5)");
    s.execute("CREATE INDEX i6 on t1 (c6)");
    s.execute("CREATE INDEX i7 on t1 (c7)");
    s.execute("CREATE INDEX i8 on t1 (c8)");
    s.execute("CREATE INDEX i9 on t1 (c9)");
    s.execute("CREATE INDEX i10 on t1 (c10)");
    s.execute("CREATE INDEX i11 on t1 (c11)");
    s.execute("CREATE INDEX i12 on t1 (c12)");
    
    ResultSet rs = s.executeQuery("select * from t1 where t1.c2=148");
    JDBC.assertDrainResults(rs, 2);
    
    rs = s.executeQuery("select * from t1 where t1.c3=1025");
    JDBC.assertDrainResults(rs, 1);
    
    rs = s.executeQuery("select * from t1 where t1.c4=23456");
    JDBC.assertDrainResults(rs, 2);
    
    rs = s.executeQuery("select * from t1 where t1.c5=1.234");
    JDBC.assertDrainResults(rs, 2);
    
    rs = s.executeQuery("select * from t1 where t1.c6=23.45678");
    JDBC.assertDrainResults(rs, 2);
    
    rs = s.executeQuery("select * from t1 where t1.c7=12345.78");
    JDBC.assertDrainResults(rs, 2);
    
    rs = s.executeQuery("select * from t1 where t1.c8=37.45");
    JDBC.assertDrainResults(rs, 1);
    
    rs = s.executeQuery("select * from t1 where t1.c9='abcd'");
    JDBC.assertDrainResults(rs, 2);
    
    rs = s.executeQuery("select * from t1 where t1.c10='jdhf'");
    JDBC.assertDrainResults(rs, 1);
    
    rs = s.executeQuery("select * from t1 where t1.c11='2008-08-07'");
    JDBC.assertDrainResults(rs, 1);
    
    rs = s.executeQuery("select * from t1 where t1.c12='2008-08-06 10:49:24'");
    JDBC.assertDrainResults(rs, 2);
    
  }
  
  public void testIndexesWithDes() throws SQLException {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }


    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());
    s.execute("create index i1 on t1 (c2 DESC)");
    
    s.execute("create index i2 on t1 (c3)");
    
    PreparedStatement ps=conn.prepareStatement("insert into t1 (c1, c2, c3) values (?, ?, ?)");
    
    for(int i=1; i<100; ++i) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setString(3, "xx"+i);
      ps.execute();
    }
    
    String[][] expectedRows = {
        {"14", "14", "xx14" },
        {"13", "13", "xx13" },
        {"12", "12", "xx12" },
        {"11", "11", "xx11" }};

  
    ResultSet rs = s
        .executeQuery("select * from t1 where c2>10 and c2<15");
   
    JDBC.assertFullResultSet(rs, expectedRows); 
    
    String[][] expectedRows1 = {        
        {"11", "11", "xx11" },
        {"12", "12", "xx12" },
        {"13", "13", "xx13" },
        {"14", "14", "xx14" }};
    
    rs = s
    .executeQuery("select * from t1 where c3>'xx10' and c3<'xx15'");
    JDBC.assertFullResultSet(rs, expectedRows1);
    
  }
  
  public void testMultiColumnsIndexesWithDesAsc() throws SQLException {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }


    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());
    s.execute("create index i1 on t1 (c2 DESC, c3)");
    
    
    
    PreparedStatement ps=conn.prepareStatement("insert into t1 (c1, c2, c3) values (?, ?, ?)");
    
    for(int i=1; i<100; ++i) {
      ps.setInt(1, i);     
      ps.setInt(2, i);
      ps.setString(3, "xx"+i);
      ps.execute();
    }
    
    ps.setInt(1, 101);     
    ps.setInt(2, 11);
    ps.setString(3, "xx10");
    ps.execute();
    
    ps.setInt(1, 102);     
    ps.setInt(2, 12);
    ps.setString(3, "xx13");
    ps.execute();
    
    ps.setInt(1, 103);     
    ps.setInt(2, 13);
    ps.setString(3, "xx12");
    ps.execute();
    
    ps.setInt(1, 104);     
    ps.setInt(2, 14);
    ps.setString(3, "xx13");
    ps.execute();
    
    String[][] expectedRows = {
        
        {"104", "14", "xx13" },
        {"14", "14", "xx14" },        
        {"103", "13", "xx12" },
        {"13", "13", "xx13" },       
        {"12", "12", "xx12" },
        {"102", "12", "xx13" },
        {"101", "11", "xx10"},
        {"11", "11", "xx11" }};

  
    ResultSet rs = s
        .executeQuery("select * from t1 where c2>10 and c2<15");
   
    JDBC.assertFullResultSet(rs, expectedRows); 
          
  }

  public void testMultiColumnsIndexesWithDesDes() throws SQLException {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }


    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());
    s.execute("create index i1 on t1 (c2 DESC, c3 DESC)");
    
    
    
    PreparedStatement ps=conn.prepareStatement("insert into t1 (c1, c2, c3) values (?, ?, ?)");
    
    for(int i=1; i<100; ++i) {
      ps.setInt(1, i);     
      ps.setInt(2, i);
      ps.setString(3, "xx"+i);
      ps.execute();
    }
    
    ps.setInt(1, 101);     
    ps.setInt(2, 11);
    ps.setString(3, "xx10");
    ps.execute();
    
    ps.setInt(1, 102);     
    ps.setInt(2, 12);
    ps.setString(3, "xx13");
    ps.execute();
    
    ps.setInt(1, 103);     
    ps.setInt(2, 13);
    ps.setString(3, "xx12");
    ps.execute();
    
    ps.setInt(1, 104);     
    ps.setInt(2, 14);
    ps.setString(3, "xx13");
    ps.execute();
    
    String[][] expectedRows = {
       
        {"14", "14", "xx14" },
        {"104", "14", "xx13" },
        {"13", "13", "xx13" },        
        {"103", "13", "xx12" },
        {"102", "12", "xx13" },        
        {"12", "12", "xx12" },
        {"11", "11", "xx11" },
        {"101", "11", "xx10"}
        };

  
    ResultSet rs = s
        .executeQuery("select * from t1 where c2>10 and c2<15");
   
    JDBC.assertFullResultSet(rs, expectedRows); 
          
  }

  public void testBug43981_sortAvoidance() throws Exception {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    //System.setProperty("gemfirexd.optimizer.trace", "true");
    setupConnection();
    final Connection conn = jdbcConn;
    final Statement stmt = conn.createStatement();

    stmt.execute("create table new_order ("
        + "no_w_id  integer   not null,"
        + "no_d_id  integer   not null,"
        + "no_o_id  integer   not null"
        + ") partition by (no_w_id) redundancy 1 buckets 6");
    stmt.execute("alter table new_order add constraint pk_new_order "
        + "primary key (no_w_id, no_d_id, no_o_id)");
    stmt.execute("create index ndx_neworder_w_id_d_id "
        + "on new_order (no_w_id, no_d_id)");
    stmt.execute("create index ndx_neworder_w_id_d_id_o_id "
        + "on new_order (no_w_id, no_d_id, no_o_id)");

    PreparedStatement pstmt = conn
        .prepareStatement("insert into new_order values (?, ?, ?)");
    for (int w_id = 1; w_id <= 10; w_id++) {
      for (int d_id = 1; d_id <= 10; d_id++) {
        for (int o_id = 1; o_id <= 10; o_id++) {
          pstmt.setInt(1, w_id);
          pstmt.setInt(2, d_id);
          pstmt.setInt(3, o_id);
          assertEquals(1, pstmt.executeUpdate());
        }
      }
    }

    final LanguageConnectionContext lcc = ((EmbedConnection)conn)
        .getLanguageConnection();
    // non-optimized query with constants
    ResultSet rs = stmt.executeQuery("SELECT no_o_id FROM new_order "
        + "WHERE no_d_id = 5 AND no_w_id = 5 ORDER BY no_o_id ASC");
    TransactionController tc = lcc.getTransactionExecute();
    int numRows = 0;
    while (rs.next()) {
      numRows++;
      assertEquals(numRows, rs.getInt(1));
      // check that we should not have any sorters opened
      assertEquals(0, tc.countOpens(TransactionController.OPEN_CREATED_SORTS));
      assertEquals(0, tc.countOpens(TransactionController.OPEN_SORT));
    }
    assertEquals(10, numRows);

    // optimized query with constants
    rs = stmt.executeQuery("SELECT no_o_id FROM new_order "
        + "WHERE no_d_id = 5 AND no_w_id = 5 ORDER BY no_o_id ASC "
        + "FETCH FIRST ROW ONLY");
    tc = lcc.getTransactionExecute();
    assertTrue(rs.next());
    // check that we should not have any sorters opened
    assertEquals(0, tc.countOpens(TransactionController.OPEN_CREATED_SORTS));
    assertEquals(0, tc.countOpens(TransactionController.OPEN_SORT));
    assertEquals(1, rs.getInt(1));
    assertFalse(rs.next());

    // now check queries with parameters
    // non-optimized query
    PreparedStatement ps = conn.prepareStatement("SELECT no_o_id FROM "
        + "new_order WHERE no_d_id = ? AND no_w_id = ? ORDER BY no_o_id ASC");
    ps.setInt(1, 5);
    ps.setInt(2, 5);
    rs = ps.executeQuery();
    tc = lcc.getTransactionExecute();
    numRows = 0;
    while (rs.next()) {
      numRows++;
      assertEquals(numRows, rs.getInt(1));
      // check that we should not have any sorters opened
      assertEquals(0, tc.countOpens(TransactionController.OPEN_CREATED_SORTS));
      assertEquals(0, tc.countOpens(TransactionController.OPEN_SORT));
    }
    assertEquals(10, numRows);

    // optimized query
    ps = conn.prepareStatement("SELECT no_o_id FROM "
        + "new_order WHERE no_d_id = ? AND no_w_id = ? ORDER BY no_o_id ASC "
        + "FETCH FIRST ROW ONLY");
    ps.setInt(1, 5);
    ps.setInt(2, 5);
    rs = ps.executeQuery();
    tc = lcc.getTransactionExecute();
    assertTrue(rs.next());
    // check that we should not have any sorters opened
    assertEquals(0, tc.countOpens(TransactionController.OPEN_CREATED_SORTS));
    assertEquals(0, tc.countOpens(TransactionController.OPEN_SORT));
    assertEquals(1, rs.getInt(1));
    assertFalse(rs.next());
  }

  public void testIndexSelectivity() throws SQLException {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    //System.setProperty("gemfirexd.optimizer.trace", "true");
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    ResultSet rs;

    stmt.execute("create table ctstable1 (TYPE_ID int, KEY_ID int, "
        + "TYPE_DESC varchar(32), primary key(TYPE_ID, KEY_ID))");
    stmt.execute("create table ctstable2 (KEY_ID int, COF_NAME varchar(32), "
        + "PRICE float, TYPE_ID int, primary key(KEY_ID), "
        + "foreign key(TYPE_ID, KEY_ID) references ctstable1)");
    stmt.execute("create table ctstable3 (KEY_ID int, COF_NAME varchar(32), "
        + "PRICE float, TYPE_ID int, primary key(KEY_ID), "
        + "foreign key(TYPE_ID, KEY_ID) references ctstable1) replicate");

    // check the index types and colocation
    final String schemaName = getCurrentDefaultSchemaName();
    AlterTableTest.checkDefaultPartitioning(schemaName + ".ctstable1",
        "TYPE_ID", "KEY_ID");
    AlterTableTest.checkDefaultPartitioning(schemaName + ".CTSTABLE2",
        "TYPE_ID", "KEY_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable1", "LOCALHASH1",
        "TYPE_ID", "KEY_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable2", "GLOBALHASH",
        "KEY_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable2", "LOCALSORTEDMAP",
        "TYPE_ID", "KEY_ID");
    AlterTableTest.checkColocation(schemaName + ".ctstable2", schemaName,
        "ctstable1");
    AlterTableTest.checkIndexType(schemaName, "ctstable3", "LOCALHASH1",
        "KEY_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable3", "LOCALSORTEDMAP",
        "TYPE_ID", "KEY_ID");

    final int numRows = PartitionedRegion.rand.nextInt(50) + 50;
    int rows = 0;
    for (int v = 1; v <= numRows; v++) {
      stmt.execute(String.format(
          "insert into ctstable1 values (%s, %s, 'Type%s')", v * 5, v * 10, v));
      stmt.execute(String.format(
          "insert into ctstable2 values (%s, 'COF%s', %s.0, %s)", v * 10, v,
          v * 20, v * 5));
      stmt.execute(String.format(
          "insert into ctstable3 values (%s, 'COF%s', %s.0, %s)", v * 10, v,
          v * 20, v * 5));
    }

    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    rs = stmt.executeQuery("select * from ctstable1 order by TYPE_ID");
    for (int v = 1; v <= numRows; v++) {
      assertTrue(rs.next());
      assertEquals(v * 5, rs.getInt("TYPE_ID"));
      assertEquals(Integer.valueOf(v * 5), rs.getObject(1));
    }
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select TYPE_ID from ctstable2 order by TYPE_ID");
    for (int v = 1; v <= numRows; v++) {
      assertTrue(rs.next());
      assertEquals(Integer.valueOf(v * 5), rs.getObject("TYPE_ID"));
      assertEquals(Integer.valueOf(v * 5), rs.getObject(1));
    }
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select * from ctstable2 order by TYPE_ID");
    for (int v = 1; v <= numRows; v++) {
      assertTrue(rs.next());
      assertEquals(Integer.valueOf(v * 5), rs.getObject("TYPE_ID"));
      assertEquals(Integer.valueOf(v * 10), rs.getObject(1));
    }
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.TYPE_ID = t2.TYPE_ID");
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.TYPE_ID = t2.TYPE_ID "
        + "and t2.TYPE_ID > 2");
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.KEY_ID = t2.KEY_ID "
        + "and t2.TYPE_ID > 2");
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.KEY_ID = t2.KEY_ID "
        + "and t2.KEY_ID > 2");
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.KEY_ID = t2.KEY_ID "
        + "and t1.KEY_ID > 2");
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.KEY_ID = t2.KEY_ID "
        + "and t1.KEY_ID = 10 and t1.TYPE_ID = 5");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t1.TYPE_ID");
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID");
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    // best plan in this case is t2 join t1 join t3
    // only one row in t2 will match =10 criteria due to PK,
    // then for that scan t1 and for each hash lookup into t3
    // otoh t1 join t3 join t2 will require lookup into t3, then into
    // t2 for each row of t1
    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID "
        + "and t2.KEY_ID = 10");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.KEY_ID = t3.KEY_ID "
        + "and t2.TYPE_ID = 5");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID "
        + "and t2.TYPE_ID = 5");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    String inStr = "(";
    for (int v = 1; v <= numRows; v++) {
      if (v > 1) {
        inStr += ',';
      }
      inStr += Integer.toString(v * 10);
    }
    inStr += ')';
    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID "
        + "and t3.KEY_ID in " + inStr);
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID "
        + "and t2.KEY_ID in " + inStr);
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID "
        + "and t1.KEY_ID <= 10");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID "
        + "and t1.KEY_ID = 10 and t1.TYPE_ID = 5");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t2.TYPE_ID = t3.TYPE_ID "
        + "and t1.KEY_ID in " + inStr);
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t1.TYPE_ID = t3.TYPE_ID "
        + "and t1.TYPE_ID = t2.TYPE_ID and t1.KEY_ID <= 10");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t1.TYPE_ID = t3.TYPE_ID "
        + "and t3.TYPE_ID = t2.TYPE_ID and t1.TYPE_ID = 5 and t1.KEY_ID = 10");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t1.TYPE_ID = t3.TYPE_ID "
        + "and t3.KEY_ID = t2.KEY_ID and t1.TYPE_ID = 5 and t1.KEY_ID = 10");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t3.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2, ctstable3 t3 where "
        + "t1.KEY_ID = t3.KEY_ID and t1.TYPE_ID = t3.TYPE_ID "
        + "and t2.TYPE_ID = t3.TYPE_ID and t1.KEY_ID in " + inStr);
    rows = 0;
    while (rs.next()) {
      rows++;
      int type = rs.getInt(1);
      double price = (type << 2);
      assertEquals(price, rs.getDouble("PRICE"));
      assertEquals(Double.valueOf(price), rs.getObject(3));
    }
    assertEquals(numRows, rows);

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer
        .addExpectedScanType(schemaName + ".ctstable3", ScanType.HASH1INDEX);
    observer.checkAndClear();

    // selectivity for subqueries
    stmt.execute("create index i2 on ctstable2(KEY_ID, COF_NAME)");
    observer.clear();

    rs = stmt.executeQuery("select * from ctstable1 o where exists (select * "
        + "from ctstable2 i where o.KEY_ID = i.KEY_ID and i.COF_NAME = 'COF2')");
    assertTrue(rs.next());
    assertEquals(10, rs.getInt(1));
    assertEquals(20, rs.getInt(2));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.addExpectedScanType(schemaName + ".ctstable2", schemaName + ".i2",
        ScanType.SORTEDMAPINDEX);
    observer.addExpectedScanType(schemaName + ".ctstable3", ScanType.NONE);
    observer.checkAndClear();

    // check for LIKE predicate
    stmt.execute("create index i0 on ctstable1(type_desc desc)");
    observer.clear();

    PreparedStatement pstmt = conn
        .prepareStatement("select type_desc from ctstable1 where "
            + "type_desc LIKE ?");
    pstmt.setString(1, "T%");
    rs = pstmt.executeQuery();
    int numRes = 0;
    while (rs.next()) {
      numRes++;
    }
    assertEquals(numRows, numRes);
    observer.addExpectedScanType(schemaName + ".ctstable1",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    pstmt = conn
        .prepareStatement("select type_desc from ctstable1 where "
            + "type_desc LIKE 'T%'");
    rs = pstmt.executeQuery();
    numRes = 0;
    while (rs.next()) {
      numRes++;
    }
    assertEquals(numRows, numRes);
    observer.addExpectedScanType(schemaName + ".ctstable1",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select type_desc from ctstable1 where "
        + "type_desc LIKE 'T%'");
    numRes = 0;
    while (rs.next()) {
      numRes++;
    }
    assertEquals(numRows, numRes);
    observer.addExpectedScanType(schemaName + ".ctstable1",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    // check for index with sort avoidance for GROUP BY;
    // also there should be no distinct hashtable scans which gets checked
    // by the full version of addExpectedScanType that includes expected
    // conglomerate name (#46726)
    rs = stmt.executeQuery("select type_desc from ctstable1 "
        + "where type_desc='Type1' group by type_desc");
    numRes = 0;
    while (rs.next()) {
      assertEquals("Type1", rs.getString(1));
      numRes++;
    }
    assertEquals(1, numRes);
    assertEquals(0, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".ctstable1", schemaName + ".i0",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select type_desc from ctstable1 "
        + "where type_desc > 'Type1' group by type_desc");
    numRes = 0;
    while (rs.next()) {
      numRes++;
    }
    assertEquals(numRows - 1, numRes);
    assertEquals(0, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".ctstable1", schemaName + ".i0",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

   
  
    rs = stmt
        .executeQuery("select type_desc from ctstable1 group by type_desc");
    numRes = 0;
    while (rs.next()) {
      numRes++;
    }
    assertEquals(numRows, numRes);
    assertEquals(0, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".ctstable1", schemaName + ".i0",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select type_desc from ctstable1 "
        + "-- GEMFIREXD-PROPERTIES index=I0\n group by type_desc");
    numRes = 0;
    while (rs.next()) {
      numRes++;
    }
    assertEquals(numRows, numRes);
    assertEquals(0, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".ctstable1", schemaName + ".i0",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select type_desc, count(*) from ctstable1 "
        + "group by type_desc");
    numRes = 0;
    while (rs.next()) {
      assertEquals(
          "unexpected count=" + rs.getInt(2) + " for " + rs.getString(1), 1,
          rs.getInt(2));
      numRes++;
    }
    assertEquals(numRows, numRes);
    assertEquals(0, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".ctstable1", schemaName + ".i0",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select count(*) from ctstable1 "
        + "group by type_desc");
    numRes = 0;
    while (rs.next()) {
      assertEquals(1, rs.getInt(1));
      numRes++;
    }
    assertEquals(numRows, numRes);
    assertEquals(0, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".ctstable1", schemaName + ".i0",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    rs = stmt.executeQuery("select count(*) from ctstable1 "
        + "where type_desc > 'Type0'");
    numRes = 0;
    while (rs.next()) {
      assertEquals(numRows, rs.getInt(1));
      numRes++;
    }
    assertEquals(1, numRes);
    assertEquals(0, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".ctstable1", schemaName + ".i0",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();

    stmt.execute("create table testtable (id int, address varchar(100))");
    pstmt = conn.prepareStatement("insert into testtable values (?, ?)");
    for (int i = 0; i < 10; i++) {
      pstmt.setInt(1, i);
      if ((i % 2) == 0) {
        pstmt.setString(2, "addr" + i);
      }
      else {
        pstmt.setNull(2, Types.VARCHAR);
      }
      pstmt.execute();
    }
    rs = stmt.executeQuery("select case when address is null then "
        + "'NULL ADDR' else address end from testtable "
        + "group by address having address is null");
    assertTrue(rs.next());
    assertEquals("NULL ADDR", rs.getString(1));
    assertFalse(rs.next());
    assertEquals(1, observer.getSorters().size());
    observer.addExpectedScanType(schemaName + ".testtable", ScanType.TABLE);
    observer.checkAndClear();

    GemFireXDQueryObserverHolder.clearInstance();
  }

  public void testBug44160_47196() throws Exception {
    reduceLogLevelForTest("config");

    // System.setProperty("gemfirexd.optimizer.trace", "true");
    // reduceLogLevelForTest("config");
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    ResultSet rs;

    stmt.execute("create table oorder ("
        + "o_w_id       integer      not null,"
        + "o_d_id       integer      not null,"
        + "o_id         integer      not null," + "o_c_id       integer,"
        + "o_carrier_id integer," + "o_ol_cnt     decimal(2,0),"
        + "o_all_local  decimal(1,0)," + "o_entry_d    timestamp"
        + ") partition by (o_w_id)");
    stmt.execute("alter table oorder add constraint pk_oorder"
        + " primary key (o_w_id, o_d_id, o_id)");
    stmt.execute("create unique index ndx_oorder_carrier"
        + " on oorder (o_w_id, o_d_id, o_carrier_id, o_id)");
    stmt.execute("create index ndx_oorder_w_id_d_id_c_id"
        + " on oorder (o_w_id, o_d_id, o_c_id)");

    PreparedStatement pstmt = conn.prepareStatement("insert into oorder "
        + "(o_w_id, o_d_id, o_id, o_c_id) values (?, ?, ?, ?)");
    for (int i = 1; i <= 3; i++) {
      for (int j = 1; j <= 10; j++) {
        for (int k = 1; k <= 1667; k++) {
          pstmt.setInt(1, i);
          pstmt.setInt(2, j);
          pstmt.setInt(3, k);
          pstmt.setInt(4, j);
          pstmt.execute();
        }
      }
    }

    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);
    final String defaultSchema = getCurrentDefaultSchemaName();

    {
      String query = "SELECT MAX(o_id) AS maxorderid FROM oorder"
          + " WHERE o_w_id = 1 AND o_d_id = 5 AND o_c_id = 5";
      System.out.println(query);
      System.out.println("Results: ");

      Statement s = conn.createStatement();
      ResultSet rs1 = s.executeQuery(query);
      assertTrue(rs1.next());
      assertEquals(1667, rs1.getInt(1));
      assertFalse(rs1.next());
    }
    observer.addExpectedScanType(defaultSchema + ".oorder", defaultSchema
        + ".ndx_oorder_w_id_d_id_c_id", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();
  }
  
  public void testBug47211() throws Exception {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    setupConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    Statement s = conn.createStatement();
    
    s.execute("CREATE TABLE TABLE_DATA(ID VARCHAR (36), F1 VARCHAR (100)," +
        " F2 VARCHAR (100), F3 VARCHAR (100), F4 VARCHAR (100)) partition" +
        " by (id)"); 
    
    s.execute("CREATE INDEX IDX_F2 on TABLE_DATA(F2) " +
        "-- gemfirexd-properties caseSensitive=false");
    s.execute("CREATE INDEX IDX_F2_F3_F4 on TABLE_DATA(F2, F3, F4) " +
        "-- gemfirexd-properties caseSensitive=false");
   
    
    s.execute("insert into table_data values ('1', 'TestF1Value1110001', " +
        "'TestF2Value1110001', 'TestF2Value1110001', 'TestF4VAlue1')");
    s.execute("insert into table_data values ('2', 'TestF1Value1110001', " +
        "'TestF2Value1110001', 'TestF2Value1110001', 'TestF4VAlue2')");
    
    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);
    final String defaultSchema = getCurrentDefaultSchemaName();    

    ResultSet r = s.executeQuery("select F1, F2, F3, F4 from " +
        "table_data where f2 = 'TESTF2VALUE1110001' and " +
        "f3 = 'TestF2ValuE1110001' and " +
        "(UPPER(f4) = 'TESTF4VALUE1' or UPPER(f4) = 'TESTF4VALUE2')");
    
    for (int i = 0; i < 2; i++) {
      assertTrue(r.next());
      assertEquals("TestF1Value1110001", r.getString(1));
      assertEquals("TestF2Value1110001", r.getString(2));
      assertEquals("TestF2Value1110001", r.getString(3));
      if (i == 0) {
        assertEquals("TestF4VAlue1", r.getString(4));
      } else {
        assertEquals("TestF4VAlue2", r.getString(4));
      }
    }
    
    observer.addExpectedScanType(defaultSchema + ".TABLE_DATA", defaultSchema
        + ".IDX_F2_F3_F4", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();
  }
  
  

  
  public void testIndexModificationAndQueryTest() throws Exception {


    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("create table t1 (c1 int primary key, c2 int, c3 char(20))"+getSuffix());
    PreparedStatement ps = conn.prepareStatement("insert into t1 values(?,?,?)");
    for(int i = 0; i < 200; ++i) {
      ps.setInt(1, i);
      ps.setInt(2, 5);
      ps.setString(3, ""+i);
      assertEquals(1, ps.executeUpdate());
    }
    s.execute("create index i1 on t1 (c2)");
    
    

    // check the index types and colocation
    final String schemaName = getCurrentDefaultSchemaName();
   
    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    ResultSet rs = s.executeQuery("select * from t1 where c2 = 5");
    for (int v = 1; v <= 200; v++) {
      assertTrue(rs.next());    
    }
    assertFalse(rs.next());
    
    observer.addExpectedScanType(schemaName + ".t1", ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();
   
    final boolean [] callbackInvoked  = new boolean[]{false};
    GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter() {
      private int numInvocations;     
      
      @Override
      public void keyAndContainerAfterLocalIndexDelete(Object key,
          Object rowLocation, GemFireContainer container) {
        ConcurrentSkipListMap<Object, Object> csMap = container
            .getSkipListMap();

        ++numInvocations;
        if(numInvocations < 200) {
          assertEquals(1,csMap.size());
        }else if(numInvocations == 200) {
          assertEquals(0, csMap.size() );
          callbackInvoked[0] = true;
        }else {
          fail("More than expected number of index deletions. the number is " +  numInvocations);
        }       
      }        
    });
    s.executeUpdate("delete from t1");
    assertTrue(callbackInvoked[0]);
    conn.close();    
  }
  
  public void _testConcurrentIndexModificationAndQueryTest() throws Exception {
    // create a table with a field having sorted index. Populate the table to
    // have atleast 100 rows
    // Then delete all rows . check if the index skip list has zero entries
    final Exception[] exceptions = new Exception[3];
    final Connection conn = getConnection();
    final Statement s = conn.createStatement();
    final boolean[] keepGoing = new boolean[] { true };
    s.execute("create table t1 (c1 int , c2 int, c3 char(20))"
        + getSuffix());
    s.execute("create index i1 on t1 (c2)");
    Runnable inserter = new Runnable() {
      public void run() {
        while (keepGoing[0]) {
          try {
            Thread.sleep(1000);
            PreparedStatement ps = conn
                .prepareStatement("insert into t1 values(?,?,?)");
            for (int i = 0; i < 500; ++i) {
              ps.setInt(1, i);
              ps.setInt(2, 5);
              ps.setString(3, "" + i);
              assertEquals(1, ps.executeUpdate());
            }
          } catch (Exception e) {
            e.printStackTrace();
            exceptions[0] = e;
          }
        }

      }
    };

    Runnable deleter = new Runnable() {
      public void run() {
        while (keepGoing[0]) {
          try {
            Thread.sleep(1000);
            s.executeUpdate("delete from t1");
          } catch (Exception e) {
            e.printStackTrace();
            exceptions[1] = e;
          }
        }

      }
    };

    Runnable query = new Runnable() {
      public void run() {
        while (keepGoing[0]) {
          try {
            Thread.sleep(1000);
            ResultSet rs = s.executeQuery("select * from t1 where c2 = 5");
            while (rs.next()) {
              rs.getInt(1);
            }

          } catch (Exception e) {
            e.printStackTrace();
            exceptions[2] = e;
          }
        }

      }
    };
    ExecutorService es = Executors.newCachedThreadPool();

    es.submit(inserter);
    es.submit(deleter);
    es.submit(query);

    Thread.sleep(10000);
    keepGoing[0] = false;
    es.shutdown();
    es.awaitTermination(60, TimeUnit.SECONDS);
    conn.close();
    for(Exception e : exceptions) {
      if(e != null) {
        fail("Test failed due to exception",e);
      }
    }
  }

  public String getSuffix() {
    return "";
  }
  
  public void testBug48010() throws Exception {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    // SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
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
        if (memIndex.getGemFireContainer().getTableName().toLowerCase()
            .indexOf("sec_price") != -1) {
          return Double.MAX_VALUE;
        }
        return Double.MIN_VALUE;// optimzerEvalutatedCost;
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
    // SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
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
      psInsert4.setDate(6, new Date(System.currentTimeMillis()));
      assertEquals(1, psInsert4.executeUpdate());
    }

    conn.commit();

    s.execute("create index sec_price on trade.securities(price)");
    s.execute("create index sec_id on trade.securities(sec_id)");
    s.execute("create index sec_tid on trade.securities(tid)");
    s.execute("create index sec_exchange on trade.securities(exchange)");

    // GemFireXDQueryObserverHolder.setInstance(observer);

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
  
  public void testNoMemoryLeakBug50496() throws Exception {
    // Bug #51838
    if (isTransactional && getSuffix().contains("offheap")) {
      return;
    }

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    String ddl = "create table TMP.T1"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) "
        + " replicate persistent"  + getSuffix();
           
        stmt.execute("create schema TMP");
        
        stmt.execute(ddl);
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        
                final List<CompactCompositeIndexKey> cciks = new ArrayList<CompactCompositeIndexKey>();
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
              @Override
              public void scanControllerOpened(Object sc, Conglomerate conglom) {
                try {
                  // ignore calls from management layer
                  if (sc instanceof SortedMap2IndexScanController) {
                    SortedMap2IndexScanController indexScan = (SortedMap2IndexScanController) sc;
                    Class<?> memIndexSCClass = MemIndexScanController.class;
                    Field openConglomField = memIndexSCClass
                        .getDeclaredField("openConglom");
                    openConglomField.setAccessible(true);
                    OpenMemIndex mi = (OpenMemIndex) openConglomField.get(sc);
                    ConcurrentSkipListMap<Object, Object> skipListMap = mi
                        .getGemFireContainer().getSkipListMap();

                    for (Object key : skipListMap.keySet()) {
                      if (key instanceof CompactCompositeIndexKey) {
                        cciks.add((CompactCompositeIndexKey) key);
                      }
                    }

                  }
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
            });

        // Collect all the byte[] present in region entries
        LocalRegion region = Misc.getRegionByPath("/TMP/T1");
        assertNotNull(region);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt.getResultSet();
        int cnt = 0;
        while (rs.next()) {
          Integer i = (Integer) rs.getObject(1);
          if ((i == 3) || (i == 4)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }
        HashSet<Object> bytesStoredInRegion = new HashSet<Object>();
        Set<Object> keys = region.keys();
        for (Object key : keys) {
          AbstractRegionEntry are = (AbstractRegionEntry) region
              .basicGetEntry(key);
          Object val = are.getValueInVM(region);
          bytesStoredInRegion.add(val);
        }
        assertFalse(cciks.isEmpty());
        for (CompactCompositeIndexKey ccik : cciks) {
          Object valBytes = ccik.getValueByteSource();
          if (valBytes != null) {
            assertTrue(bytesStoredInRegion.contains(valBytes));
          }
          ccik.releaseValueByteSource(valBytes);
        }
        for(Object valBytes: bytesStoredInRegion) {
          if(valBytes instanceof OffHeapByteSource) {
            ((OffHeapByteSource)valBytes).release();
          }
        }
    
  }
}
