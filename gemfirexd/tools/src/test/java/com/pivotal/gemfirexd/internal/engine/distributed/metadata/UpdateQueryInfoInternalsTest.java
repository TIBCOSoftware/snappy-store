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
/**
 * 
 */
package com.pivotal.gemfirexd.internal.engine.distributed.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireUpdateActivation;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfoContext;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.UpdateQueryInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

/**
 * @author Asif
 *
 */
public class UpdateQueryInfoInternalsTest extends JdbcTestBase{
  private boolean callbackInvoked = false;
  public UpdateQueryInfoInternalsTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(UpdateQueryInfoInternalsTest.class));
  }  
  
  /**
   * Tests if the where clause containing multiple fields with no primary key
   * defined behaves correctly ,using Statement
   * 
   */
  public void testBasicUpdate() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Update  TESTTABLE set  ADDRESS = 'your foot', DESCRIPTION = 'myfoot'  where ID = 1 ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {              
                callbackInvoked = true;
              }

            }

          });      
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      Statement s2 = conn.createStatement();
      try {
        s2.executeUpdate(query);
        ResultSet rs = s2.executeQuery("Select * from TESTTABLE where ID = 1");
        assertTrue(rs.next());
        assertEquals("myfoot", rs.getString(2));
        assertEquals("your foot", rs.getString(3));
        
      }
      catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the update works correctly if it has parameterized where clause with
   * region.put convertible case
   */
  public void testParameterizedRegionPutConvertibleUpdate_Bug39652() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), type int)");
    String query = "Update  TESTTABLE set  type = ?  where ID = 1 ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {              
                callbackInvoked = true;
              }
            }
          });      
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604',1)");
      PreparedStatement ps = conn.prepareStatement(query);
      ps.setInt(1, 2);            
      try {
        int n = ps.executeUpdate();
        assertEquals(n,1);
      }
      catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  
  
  /**
   * Tests if the where clause containing multiple fields with no primary key
   * defined behaves correctly ,using Statement
   * 
   */
  public void testUpdateHavingParameterizedExpression() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), type int)");
    String query = "Update  TESTTABLE set  type = type +?  where ID = ? ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {              
                callbackInvoked = true;
              }

            }

          });      
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604',1)");
      PreparedStatement ps = conn.prepareStatement(query);
      ps.setInt(1, 1);
      ps.setInt(2, 1);      
      try {
        int n = ps.executeUpdate();
        assertEquals(n,1);
      }
      catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  /**
   * Tests if the where clause containing multiple fields with no primary key
   * defined behaves correctly ,using Statement
   * 
   */
  public void testUpdateWithExpressionIsNotPrimaryKeyBased() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), type int)");
    String query = "Update  TESTTABLE set  type = type + 1  where ID = 5 ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {              
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertFalse(uqi.isPrimaryKeyBased());
                assertTrue(uqi.isDynamic());
                assertFalse(uqi.isPreparedStatementQuery());
              }

            }

          });      
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604',1)");
      s.executeUpdate(query);
      
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  
  public void testBug39921Behaviour() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int  , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), type int)");
    String query = "Update  TESTTABLE set  type = type + 1  where ID = 5 ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {              
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                PartitionedRegion rgn = (PartitionedRegion)uqi.getRegion();
                GfxdPartitionByExpressionResolver defRslvr = (GfxdPartitionByExpressionResolver)rgn
                    .getPartitionResolver();
                assertFalse(defRslvr.isUsedInPartitioning("nocol"));
              }
            }
          });      
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604',1)");
      s.executeUpdate(query);
      
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug39973() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table INSTRUMENTS (id varchar(20) not null primary key,"
        + " sector_id int, subsector_id int) PARTITION BY COLUMN (sector_id)");
    String query = "update INSTRUMENTS set subsector_id = ? where id = ?";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setInt(1, 1);
    ps.setString(2, "ONE");
    int n = ps.executeUpdate();
    assertEquals(0, n);
  }

  public void testBug39974() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table INSTRUMENTS (id varchar(20) not null primary key, sector_id int, subsector_id int) PARTITION BY RANGE " +
    		"(sector_id) ( VALUES BETWEEN 20 and 40, VALUES BETWEEN 40 and 60, "  + 
    		"VALUES BETWEEN 60 and 80, VALUES BETWEEN 90 and 100 )");
    String query = "update INSTRUMENTS set subsector_id = ? where id = ?";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setInt(1, 1); 
    ps.setString(2, "ONE");
    int n = ps.executeUpdate();
    assertEquals(0, n);
  }
  
  public void testBug39975() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table INSTRUMENTS (id varchar(20) not null primary key, sector_id int not null, subsector_id int) PARTITION BY LIST " +
                "(sector_id) ( VALUES (20, 40), VALUES (50, 60) )");
    String query = "update INSTRUMENTS set subsector_id = ? where id = ?";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setInt(1, 1); 
    ps.setString(2, "ONE");
    int n = ps.executeUpdate();
    assertEquals(0, n);
  }
  
  public void testBug39976() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table INSTRUMENTS (id varchar(20) not null primary key, sector_id int, subsector_id int)");
    String query = "update INSTRUMENTS set subsector_id = ? where id = ?";
    PreparedStatement ps = conn.prepareStatement(query);
    ps.setInt(1, 1); 
    ps.setString(2, "ONE");
    int n = ps.executeUpdate();
    assertEquals(0, n);
  }

  public void testBug39982() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table INSTRUMENTS (id varchar(20) not null primary key, sector_id int, subsector_id int) PARTITION BY COLUMN (id) ");
    String query = "update INSTRUMENTS set subsector_id = ? where id = ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation) {
              callbackInvoked = true;
            }
          });      
      
      PreparedStatement ps = conn.prepareStatement(query);
      ps.setInt(1, 1); 
      ps.setString(2, "ONE");
      int n = ps.executeUpdate();
      assertTrue(this.callbackInvoked);
      assertEquals(n,0);
      this.callbackInvoked = false;     
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with no primary key
   * defined behaves correctly ,using Statement
   * 
   */
  public void testUpdateHavingParameterizedExpression_Bug39646_1() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), type int)");
    String query = "Update TESTTABLE set type = type + ? where ID >= ? AND ID < ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {              
                callbackInvoked = true;
                QueryInfoContext qic = ((UpdateQueryInfo)qInfo).qic;
                assertEquals(3,qic.getParamerCount());
              }

            }

          });      
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604',1)");
      PreparedStatement ps = conn.prepareStatement(query);
      ps.setInt(1, 1);
      ps.setInt(2, 1);
      ps.setInt(3, 2); 
      try {
        int n = ps.executeUpdate();
        assertEquals(n,1);
      }
      catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  public void testBug4002() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    s.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, "
        + "primary key (cid))");
    s.execute("insert into trade.customers values (1, 'XXXX1', "
        + "1, 'BEAV1', 1)");
    String query = "update trade.customers set since=2 where cid=1";
    Statement s1 = conn.createStatement();
    
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation) {
              callbackInvoked = true;
            }
          });      
      
      s1.executeUpdate(query);
      ResultSet rs =  s.executeQuery("select cust_name,cid,since,addr,tid from trade.customers where cid=1 ");
      assertTrue(rs.next());
      assertEquals("XXXX1",rs.getString(1));
      assertEquals(1,rs.getInt(2));
      assertEquals(2,rs.getInt(3));
      assertEquals("BEAV1",rs.getString(4));
      assertEquals(1,rs.getInt(5));
      assertTrue(this.callbackInvoked);
      this.callbackInvoked = false;     
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  public void testRangeQueryInfoWithStatementCaching() throws Exception
  {
    try {
    String createTable = "create table TESTTABLE (ID bigint primary key, "
      + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024)) ";
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute(createTable);
    for( int i =0 ; i < 10;++i) {
      s.execute("insert into TESTTABLE values(" + i + ", 'First', 'J 604')" );
    }   
    
    s.executeUpdate("update TestTable set Description = 'Third' where ID > 5 ");
    s.executeUpdate("update TestTable set Description = 'Third' where ID > 4 ");
    }finally {
      try {
        Connection conn = getConnection();
        Statement s = conn.createStatement();
        s.execute("drop table TESTTABLE");
      }catch(Exception e) {
        
      }
      
    }  
  }
  
  public void testBug43700ForUpdate() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    final String subquery = "select cid from trade.portfolio where tid =? and sid >? and sid < ?";
    String dml = "update trade.networth set loanlimit = loanlimit*100 where tid = ? and cid IN "
        + "(" + subquery + ")";
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table trade.networth (cid int not null,  "
        + "  loanlimit int,  tid int, constraint netw_pk primary key (cid))"
        + "  partition by (tid) ");

    s
        .execute("create table trade.portfolio (cid int not null, sid int not null, "
            + "qty int not null, tid int, constraint portf_pk primary key (cid, sid))  "
            + "partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11),"
            + " VALUES (12, 13, 14, 15, 16, 17))");

    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {

              UpdateQueryInfoInternalsTest.this.callbackInvoked = true;
              assertTrue(qInfo instanceof UpdateQueryInfo);
              UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
              assertFalse(uqi.hasCorrelatedSubQuery());
              assertEquals(uqi.getSubqueryInfoList().size(), 1);
              SubQueryInfo subQi = uqi.getSubqueryInfoList().iterator().next();
              assertEquals(subQi.getSubqueryString(), subquery);
              assertTrue(uqi.isRemoteGfxdSubActivationNeeded());
              assertFalse(uqi.isSubqueryFlatteningAllowed());

            }

          });

      PreparedStatement ps = conn.prepareStatement(dml);

      assertTrue(callbackInvoked);

    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
    }

  }
  
  
  
  public void testBug42428ForUpdate() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    GemFireXDQueryObserver old = null;
    final String subquery = "select ID2 from TESTTABLE2";
    String dml = "Update TESTTABLE1 set DESCRIPTION1 = 'no description' where ID1 IN (" + subquery + ") ";
    try {

      String table1 = "create table TESTTABLE1 (ID1 int not null, "
          + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null ) ";

      String table2 = "create table TESTTABLE2 (ID int not null,ID2 int not null, "
          + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, primary key (ID) ) ";
      s.execute(table1 + " replicate");
      s.execute(table2);

      String insert = "Insert into  TESTTABLE1 values(1,'desc1_1','add1_1')";
      s.execute(insert);

      for (int i = 1; i < 3; ++i) {
        insert = "Insert into  TESTTABLE2 values(" + i + ",1,'desc2_"
            + "', 'add2_')";
        s.execute(insert);

      }
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (!qInfo.isSelect()) {
                UpdateQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof UpdateQueryInfo);
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertFalse(uqi.hasCorrelatedSubQuery());
                assertEquals(uqi.getSubqueryInfoList().size(), 1);
                SubQueryInfo subQi = uqi.getSubqueryInfoList().iterator()
                    .next();
                assertEquals(subQi.getSubqueryString(), subquery);
                assertTrue(uqi.isRemoteGfxdSubActivationNeeded());
                assertFalse(uqi.isSubqueryFlatteningAllowed());
              }

            }

          });
      s.executeUpdate(dml);
      assertTrue(callbackInvoked);

    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
    }

  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=? where col2=? and col1=? and col3=?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert activation instanceof GemFireUpdateActivation;
            }


          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {
        PreparedStatement
            pStmt = conn.prepareStatement(query);
        pStmt.setInt(1, 7);
        pStmt.setInt(2, 1);
        pStmt.setString(3, "y");
        pStmt.setInt(4, 11);
        int updatedCount = pStmt.executeUpdate();
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds_SimpleStatement() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=7 where col2=1 and col1='y' and col3=11";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert activation instanceof GemFireUpdateActivation;
            }

          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        int updatedCount = conn.createStatement().executeUpdate(query);
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testUpdatePutConvertibleExtraAnds_MultipleUpdates() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
            "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=7, col4=33 where col2=1 and col1='y' and col3=11";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
                  if (qInfo instanceof UpdateQueryInfo) {
                    callbackInvoked = true;
                    UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                    assertTrue(uqi.isPrimaryKeyBased());
                  }

                }

                @Override
                public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
                  assert activation instanceof GemFireUpdateActivation;
                }

              });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        int updatedCount = conn.createStatement().executeUpdate(query);
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
                + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }


  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds_InvalidUpdate() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=7 where col2=5 and col1='y' and col3=11";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert activation instanceof GemFireUpdateActivation;
            }
          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        int updatedCount = conn.createStatement().executeUpdate(query);
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(11, rs.getInt(1));
        assertEquals(0, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds_OnePK() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1)) partition by column (col1)");
    String query = "update src_tab set col3=7 where col2=1 and col1='y' and col3=11";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
                         @Override
                         public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
                           if (qInfo instanceof UpdateQueryInfo) {
                             callbackInvoked = true;
                             UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                             assertTrue(uqi.isPrimaryKeyBased());
                           }

                         }

                         @Override
                         public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
                           assert activation instanceof GemFireUpdateActivation;
                         }


                       }
          );
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        int updatedCount = conn.createStatement().executeUpdate(query);
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds_InClause() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=7 where col2=1 and col1='y' and col3 in (11, 12)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert activation instanceof GemFireUpdateActivation;
            }

          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        int updatedCount = conn.createStatement().executeUpdate(query);
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds_InClausePreparedStmt() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=? where col2=? and col1=? and col3 in (?, ?)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert activation instanceof GemFireUpdateActivation;
            }

          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        PreparedStatement
            pStmt = conn.prepareStatement(query);
        pStmt.setInt(1, 7);
        pStmt.setInt(2, 1);
        pStmt.setString(3, "y");
        pStmt.setInt(4, 11);
        pStmt.setInt(5, 12);
        int updatedCount = pStmt.executeUpdate();
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds_InClausePartialStatement() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=7 where col2=1 and col1=? and col3 in (?, ?)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert activation instanceof GemFireUpdateActivation;
            }

          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        PreparedStatement
            pStmt = conn.prepareStatement(query);
        pStmt.setString(1, "y");
        pStmt.setInt(2, 11);
        pStmt.setInt(3, 12);
        int updatedCount = pStmt.executeUpdate();
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleOnlyPKKeys() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=7 where col2=1 and col1=?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert activation instanceof GemFireUpdateActivation;
            }

          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        PreparedStatement
            pStmt = conn.prepareStatement(query);
        pStmt.setString(1, "y");
        int updatedCount = pStmt.executeUpdate();
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with primary keys and extra
   * conditions
   */
  public void testUpdatePutConvertibleExtraAnds_DisableCASUpdate() throws Exception {
    System.setProperty("snappy.store.disableCASUpdate", "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table src_tab (col1 varchar(10), col2 int , " +
        "col3 int, col4 int, PRIMARY KEY (col1, col2)) partition by column (col1)");
    String query = "update src_tab set col3=7 where col2=1 and col1='y' and col3=11";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof UpdateQueryInfo) {
                callbackInvoked = true;
                UpdateQueryInfo uqi = (UpdateQueryInfo)qInfo;
                assertTrue(!uqi.isPrimaryKeyBased());
              }

            }

            @Override
            public void afterGemFireActivationCreate(AbstractGemFireActivation activation) {
              assert !(activation instanceof GemFireUpdateActivation);
            }

          });
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into src_tab values('y', 1, 11, 10)");
      Statement s2 = conn.createStatement();
      try {

        int updatedCount = conn.createStatement().executeUpdate(query);
        ResultSet rs = s2.executeQuery("select col3 from src_tab");
        assertTrue(rs.next());
        assertEquals(7, rs.getInt(1));
        assertEquals(1, updatedCount);

      } catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty("snappy.store.disableCASUpdate");
    }
  }



  public void createTableWithPrimaryKey(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    // We create a table...
    s.execute("create table orders"
        + "(id int PRIMARY KEY, cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100))");
    s.close();
  }
  
  public void createTableWithCompositeKey(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    // We create a table...
    s.execute("create table orders"
        + "(id int , cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100)," +
                        " Primary Key (id, cust_name))");
    s.close();

  }
  
  public void createTable(Connection conn) throws SQLException {
    Statement s = conn.createStatement();    
    // We create a table...
    s.execute("create table orders"
        + "(id int , cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100))");
  /*  String types[] = new String[1];
    types[0] = "TABLE";    

    conn.getMetaData().getTables(null, null, null, types);*/
    s.close();

  }
  
  public void createTableWithThreeCompositeKey(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    // We create a table...
    s.execute("create table orders"
        + "(id int , cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100)," +
                        " Primary Key (id, cust_name,vol))");
  
    s.close();

  }

  @Override
  public void tearDown() throws Exception {
    this.callbackInvoked = false;
    super.tearDown();
  }
}
