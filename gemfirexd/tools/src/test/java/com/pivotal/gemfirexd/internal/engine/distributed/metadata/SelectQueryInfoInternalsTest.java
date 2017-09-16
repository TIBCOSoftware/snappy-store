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

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.AbstractConditionQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.AndJunctionQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ColumnQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ComparisonQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ConstantConditionsWrapperQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ConstantQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.InQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.JunctionQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.OrJunctionQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ParameterQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ParameterizedConditionsWrapperQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.PrimaryDynamicKey;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfoConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RangeQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ValueQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.reflect.GemFireActivationClass;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireSelectActivation;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.iapi.error.DerbySQLException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedDatabaseMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.compile.RelationalOperator;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.jdbc.GfxdCallbacksTest;

import java.sql.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.TestSuite;
import junit.textui.TestRunner;
/**
 * @author Asif
 *
 */
@SuppressWarnings("serial")
public class SelectQueryInfoInternalsTest extends JdbcTestBase{
  private boolean callbackInvoked = false;
  public SelectQueryInfoInternalsTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(SelectQueryInfoInternalsTest.class));
  }
  
  public void testWhereClauseWithNoIndex() throws Exception {
    Connection conn = getConnection();
    createTable(conn);
    this.whereClauseTest();
  }
  
  public void testWhereClauseWithPrimaryKey() throws Exception {
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);
    this.whereClauseTest();
  }
  
  public void testWhereClauseWithCompositeKey() throws Exception {
    Connection conn = getConnection();
    createTableWithCompositeKey(conn);
    this.whereClauseTest();  
  }
  
  public void testComplexProjectionAttributes() throws Exception {
    Connection conn = getConnection();
    createTable(conn);
    String[] queries = new String[] { "select max(id), (select id from orders t2) from orders" };
    //String[] queries = new String[] { "select max(id)  from orders t2" };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              SelectQueryInfoInternalsTest.this.callbackInvoked = true;              
              ++index;              
            }

          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement s = conn.createStatement();
      for (int i = 0; i < queries.length; ++i) {
        try {
          s.executeQuery(queries[i]);
        } catch (SQLException e) {
          throw new Exception(" Exception in executing query = " + queries[i],
                              e);
        }
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  
  private void whereClauseTest() throws Exception {    
    Connection conn = getConnection();
    String[] queries = new String[] { "Select * from orders where id =8", 
        "Select * from orders where id =8 and cust_name = 'asif'",
        "Select * from orders where id =8 and cust_name = 'asif' and  vol > 9 " };
    final boolean [] isJunctionTypeWhereClause = new boolean [] {false,true, true};
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              SelectQueryInfoInternalsTest.this.callbackInvoked = true;
              
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                if(isJunctionTypeWhereClause[index]) {
                  assertTrue(acqi instanceof JunctionQueryInfo);
                  assertEquals(((JunctionQueryInfo)acqi).getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                  assertTrue(acqi instanceof AndJunctionQueryInfo);
                  JunctionQueryInfo jqi = (JunctionQueryInfo) acqi;
                  assertTrue(validateJunctionQueryInfo(jqi,index));
                }else {
                  assertTrue(acqi instanceof ComparisonQueryInfo);
                  ComparisonQueryInfo cqi = (ComparisonQueryInfo) acqi;
                  assertTrue(validateComparisonQueryInfo(cqi,index));
                }
                ++index;              
            }

          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement s = conn.createStatement();
      for (int i = 0; i < queries.length; ++i) {
        s.executeQuery(queries[i]);
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  public void testWhereClauseWith3CompositeKey() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    createTableWithThreeCompositeKey(conn);    
    String[] queries = new String[] { "Select * from orders where vol = 9  and id = 8 and  cust_name = 'asif' "};
    final boolean[] isGetConvertible = new boolean[] {true};
    final boolean[] usedGemfireActivation = new boolean[] {false};

    //Remember to captilize the name.
    final Region<?, ?> tableRegion = Misc.getRegionForTable(
        getCurrentDefaultSchemaName() + ".orders".toUpperCase(), true);
    
    final Object[] pks = new Object[] { new CompactCompositeRegionKey(
        new DataValueDescriptor[] { new SQLInteger(8), new SQLVarchar("asif"),
            new SQLInteger(9) }, ((GemFireContainer)tableRegion.getUserAttribute()).getExtraTableInfo()) };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation) {
              usedGemfireActivation[0] = true;
            }

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              SelectQueryInfoInternalsTest.this.callbackInvoked = true;              
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
               // assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo) acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                try {
                  assertTrue(qInfo.isPrimaryKeyBased() == isGetConvertible[index]);
                  if(qInfo.isPrimaryKeyBased()) {
                    
                    RegionKey  actual = (RegionKey)qInfo.getPrimaryKey();
                    RegionKey expected= (RegionKey)pks[index];
                    assertEquals(expected,actual);
                    /*byte[] actual = (byte[])qInfo.getPrimaryKey();
                    byte[] expected= (byte[])pks[index];
                    assertEquals(actual.length,expected.length);
                    for(int i = 0 ; i < actual.length; ++i) {
                      assertEquals(actual[i],expected[i]);
                    }*/
                    
                  }
                  
                }
                catch (StandardException e) {
                 fail("Test failed because of exception="+e);
                }
                
               
                ++index;              
            }

          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement s = conn.createStatement();
      for (int i = 0; i < queries.length; ++i) {
        try {
          s.executeQuery(queries[i]);
          assertTrue(usedGemfireActivation[i]);
        }catch (SQLException e) {
          throw new SQLException(e.toString() + " Exception in executing query = "+queries[i],e.getSQLState());
        }
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
    
  }
  
  /**
   * Tests if the where clause containing multiple fields with no primary key
   * defined behaves correctly ,using Statement
   * 
   */
  public void testANDWhereClauseMultipleFieldsNoPrimaryKey() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null  , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query1 = "Select * from TESTTABLE where ID = 1 and DESCRIPTION = 'asif' and ADDRESS = 'asif'";
    String query2 = "Select * from TESTTABLE where ID = 1 and ADDRESS = 'asif' and DESCRIPTION = 'asif'";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 3);
              }

            }

          });

      try {
        s.executeQuery(query1);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query1, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      Statement s2 = conn.createStatement();
      try {
        s2.executeQuery(query2);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query2, e.getSQLState());
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
   * defined behaves correctly ,using PreparedStatement
   * 
   */
  public void testANDWhereClauseMultipleFieldsNoPrimaryKeyWithPrepStmnt()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query1 = "Select * from TESTTABLE where ID = ? and DESCRIPTION = ? and ADDRESS = ?";
    String query2 = "Select * from TESTTABLE where ID = ? and ADDRESS = ? and DESCRIPTION = ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getOperands().size(), 3);
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                assertEquals(sqi.getParameterCount(), 3);
              }

            }

          });

      conn.prepareStatement(query1);
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      conn.prepareStatement(query2);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause containing multiple fields with single primary
   * key defined behaves correctly using statement
   * 
   */
  public void testANDWhereClauseMultipleFieldsSinglePrimaryKey()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query1 = "Select * from TESTTABLE where ID = 1 and DESCRIPTION = 'asif' and ADDRESS = 'asif'";
    String query2 = "Select * from TESTTABLE where ADDRESS = 'asif' and DESCRIPTION = 'asif' and ID = 1";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 3);
              }

            }

          });

      try {
        s.executeQuery(query1);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query1, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      Statement s2 = conn.createStatement();
      try {
        s2.executeQuery(query2);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query2, e.getSQLState());
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
   * ests if the where clause containing multiple fields with single primary key
   * defined behaves correctly using prepared statement
   * 
   */
  public void testANDWhereClauseMultipleFieldsSinglePrimaryKeyUsingPrepStmnt()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query1 = "Select * from TESTTABLE where ID = ? and DESCRIPTION = ? and ADDRESS = ?";
    String query2 = "Select * from TESTTABLE where ID = ? and ADDRESS = ? and DESCRIPTION = ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 3);
                assertEquals(sqi.getParameterCount(), 3);
              }

            }

          });

      conn.prepareStatement(query1);
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      conn.prepareStatement(query2);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Test if where clause containing multiple fields with one field indexed
   * behaves correctly using statement
   * 
   */
  public void testANDWhereClauseMultipleFieldsWithOneFieldIndexed()
      throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    s.execute("create index indx on TESTTABLE (ID)");
    String query1 = "Select * from TESTTABLE where ID = 1 and DESCRIPTION = 'asif' and ADDRESS = 'asif'";
    String query2 = "Select * from TESTTABLE where ID = 1 and ADDRESS = 'asif' and DESCRIPTION = 'asif'";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 3);
              }

            }

          });

      s.executeQuery(query1);

      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      Statement s2 = conn.createStatement();
      s2.executeQuery(query2);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Test if where clause containing multiple fields with one field indexed
   * behaves correctly using prepared statement
   * 
   */
  public void testANDWhereClauseMultipleFieldsWithOneFieldIndexedUsingPrepStmnt()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    s.execute("create index indx on TESTTABLE (ID)");
    String query1 = "Select * from TESTTABLE where ID = ? and DESCRIPTION = ? and ADDRESS = ?";
    String query2 = "Select * from TESTTABLE where ID = ? and ADDRESS = ? and DESCRIPTION = ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 3);
                assertEquals(sqi.getParameterCount(), 3);
              }

            }

          });

      conn.prepareStatement(query1);
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      conn.prepareStatement(query2);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  public void testBug() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
     Statement st = conn.createStatement();
    // create the schema
    
    String  schemaScript= TestUtil.getResourcesDir()
        + "/lib/amexSchema.sql";    
   
    GemFireXDUtils.executeSQLScripts(conn, new String[] { schemaScript }, false,
        getLogger(), null, null, false);
    
    String query = "SELECT * FROM FLAT_FILE as FLAT_FILE_AND_MR WHERE (" +
    		" ('' = '')   AND (FLAT_FILE_AND_MR.ACTIVE = 1.00)   AND ('Table' = 'Table')" +
    		"  AND (1 = 1)  " +
    		" AND (CAST(FLAT_FILE_AND_MR.MR_ACTIVE_IND AS CHAR(23)) = '1.00')" +
    		"   AND (((FLAT_FILE_AND_MR.MR_REDEMPTION_REDEEMED_PTS >= 0) " +
    		" AND (FLAT_FILE_AND_MR.MR_REDEMPTION_REDEEMED_PTS <= 52172000)) " +
    		"OR (FLAT_FILE_AND_MR.MR_REDEMPTION_REDEEMED_PTS IS NULL)) " +
    		")";
    
    st.executeQuery(query);
    
  }
  
  /**
   * Tests if the where clause containing multiple fields with no primary key
   * defined behaves correctly ,using Statement
   * 
   */
  public void testORWhereClauseMultipleFieldsNoPrimaryKey() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null  , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query1 = "Select * from TESTTABLE where ID = 1 OR DESCRIPTION = 'asif' OR ADDRESS = 'asif'";
    String query2 = "Select * from TESTTABLE where ID = 1 OR ADDRESS = 'asif' OR DESCRIPTION = 'asif'";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.OR_JUNCTION);
                assertTrue(jqi instanceof OrJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 3);
              }

            }

          });

      try {
        s.executeQuery(query1);
      }
      catch (SQLException e) {
        e.printStackTrace();
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query1, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      s.executeUpdate("insert into TESTTABLE values (1, 'First', 'J 604')");
      Statement s2 = conn.createStatement();
      try {
        s2.executeQuery(query2);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query2, e.getSQLState());
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
  public void testORWhereClauseMultipleFieldsNoPrimaryKeyAsPreparedStatement() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID = ?  OR DESCRIPTION = ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.OR_JUNCTION);
                assertTrue(jqi instanceof OrJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 2);
              }

            }

          });

      conn.prepareStatement(query);
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  public void testBug39326() throws Exception {

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    s
        .execute("create table trade.customers (tid int not null  , cid int , since date , addr varchar(1024) , cust_name varchar(1024) )");

    String query = "select since, addr, cust_name from trade.customers where (tid<? or cid <=?) and since >?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(),
                    QueryInfoConstants.AND_JUNCTION);
                assertTrue(jqi instanceof AndJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 1);
                ComparisonQueryInfo cqi = (ComparisonQueryInfo)jqi
                    .getOperands().get(0);
                QueryInfo ops[] = cqi.getOperands();
                ColumnQueryInfo colQI;
                if (ops[0] instanceof ColumnQueryInfo) {
                  colQI = (ColumnQueryInfo)ops[0];
                }
                else {
                  colQI = (ColumnQueryInfo)ops[1];
                }
                assertEquals(colQI.getExposedName().toLowerCase(), "since");
                JunctionQueryInfo jqi1 = jqi.children[0];
                assertEquals(jqi1.getJunctionType(),
                    QueryInfoConstants.OR_JUNCTION);
                assertTrue(jqi1 instanceof OrJunctionQueryInfo);
                assertEquals(jqi1.getOperands().size(), 2);
                for (int i = 0; i < 2; ++i) {
                  ComparisonQueryInfo cqi1 = (ComparisonQueryInfo)jqi1
                      .getOperands().get(i);
                  QueryInfo operands[] = cqi1.getOperands();
                  ColumnQueryInfo colQI1;
                  if (operands[0] instanceof ColumnQueryInfo) {
                    colQI1 = (ColumnQueryInfo)operands[0];
                  }
                  else {
                    colQI1 = (ColumnQueryInfo)operands[1];
                  }
                  assertTrue(colQI1.getExposedName().toLowerCase()
                      .equals("tid")
                      || colQI1.getExposedName().toLowerCase().equals("cid"));

                }

              }

            }

          });

      conn.prepareStatement(query);
      assertTrue(this.callbackInvoked);
      // Now insert some data
      this.callbackInvoked = false;
      query = "select addr, since, cust_name from trade.customers where cid >? or since <?";

      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof JunctionQueryInfo);
                JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(),
                    QueryInfoConstants.OR_JUNCTION);
                assertTrue(jqi instanceof OrJunctionQueryInfo);
                assertEquals(jqi.getOperands().size(), 2);
                for (int i = 0; i < 2; ++i) {
                  ComparisonQueryInfo cqi1 = (ComparisonQueryInfo)jqi
                      .getOperands().get(i);
                  QueryInfo operands[] = cqi1.getOperands();
                  ColumnQueryInfo colQI1;
                  if (operands[0] instanceof ColumnQueryInfo) {
                    colQI1 = (ColumnQueryInfo)operands[0];
                  }
                  else {
                    colQI1 = (ColumnQueryInfo)operands[1];
                  }
                  assertTrue(colQI1.getExposedName().toLowerCase()
                      .equals("cid")
                      || colQI1.getExposedName().toLowerCase().equals("since"));

                }

              }

            }

          });
      conn.prepareStatement(query);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
   
  public void testBug39290() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int primary key, name varchar(10), type int)");
    String query = "select type, id, name from t1 where id = ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                ColumnQueryInfo[] cqis = sqi.getProjectionColumnQueryInfo();
                assertEquals(cqis[0].getActualColumnPosition(),3);
                assertEquals(cqis[1].getActualColumnPosition(),1);
                assertEquals(cqis[2].getActualColumnPosition(),2);
                assertTrue(sqi.isPrimaryKeyBased());
                try {
                  assertTrue(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      conn.prepareStatement(query);
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }  
  
  /**
   * Test the IN operator. If the query contains a single parameter/constant &
   * the lhs operand happens to be the primary key, then the query should be get
   * convertible. *
   * 
   */
  public void testINWithPrimaryKeyAsSingleParamter() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table t1 ( id int primary key, name varchar(10), type int)");
    s.execute("Insert into  t1 values(1,'asif',2)");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where id IN (?)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isDynamic());
                assertEquals(sqi.getParameterCount(), 1);
                assertFalse(sqi.getWhereClause() instanceof InQueryInfo);
                assertTrue(sqi.getPrimaryKey() instanceof PrimaryDynamicKey);
                try {
                  assertTrue(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }

              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ResultSet rs = ps1.executeQuery();
      assertTrue(rs.next());
      assertEquals(rs.getInt(2), 1);
      assertEquals(rs.getInt(1), 2);
      assertEquals(rs.getString(3), "asif");
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Test the IN operator. If the query contains a single constant & the lhs
   * operand happens to be the primary key, then the query should be get
   * convertible. *
   * 
   */
  public void testINWithPrimaryKeyAsSingleFieldConstant() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table t1 ( id int primary key, name varchar(10), type int)");
    s.execute("Insert into  t1 values(1,'asif',2)");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where id IN (1)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertFalse(sqi.isDynamic());
                assertEquals(sqi.getParameterCount(), 0);
                assertFalse(sqi.getWhereClause() instanceof InQueryInfo);
                try {
                  assertEquals(sqi.getPrimaryKey(),
                      getGemFireKey(1, sqi.getRegion()));
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
     
      ResultSet rs = ps1.executeQuery();
      assertTrue(rs.next());
      assertEquals(rs.getInt(2), 1);
      assertEquals(rs.getInt(1), 2);
      assertEquals(rs.getString(3), "asif");
      // ps1.setInt(2,2);
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Test the IN operator. If the query contains more than 1 parameters & the
   * lhs operand happens to be the primary key, still the query should be NOT be
   * get convertible.
   * 
   */
  public void testINWithMultipleParameters() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int primary key, "
        + "name varchar(10), type int)");
    s.execute("Insert into  t1 values(1,'asif',2)");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where id IN (?,?,?)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isDynamic());
                assertEquals(sqi.getParameterCount(), 3);
                assertTrue(sqi.getWhereClause() instanceof InQueryInfo);
                InQueryInfo iqi = (InQueryInfo)sqi.getWhereClause();
                QueryInfo[] operands = iqi.getOperands();
                assertEquals(operands.length, 4);
                assertTrue(operands[0] instanceof ColumnQueryInfo);
                for (int i = 1; i < 4; ++i) {
                  assertTrue(operands[i] instanceof ParameterQueryInfo);
                }
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertEquals(pks.length,3);
                for(int i = 0; i < pks.length; ++i) {
                  assertTrue(pks[i] instanceof PrimaryDynamicKey);
                }
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ps1.setInt(3, 3);
      ResultSet rs = ps1.executeQuery();
      assertTrue(rs.next());
      assertEquals(rs.getInt(2), 1);
      assertEquals(rs.getInt(1), 2);
      assertEquals(rs.getString(3), "asif");
      // ps1.setInt(2,2);
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Test the IN operator. If the query contains more than 1 parameters & also
   * contains constants , the lhs operand happens to be the primary key, still
   * the query should be NOT be get convertible.
   * 
   */
  public void testINWithConstantAndParameterizedFields() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int primary key, "
        + "name varchar(10), type int)");
    s.execute("Insert into  t1 values(1,'asif',2)");

    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where id IN (?,?,3,4)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isDynamic());
                assertEquals(sqi.getParameterCount(), 2);
                assertTrue(sqi.getWhereClause() instanceof InQueryInfo);
                InQueryInfo iqi = (InQueryInfo)sqi.getWhereClause();
                QueryInfo[] operands = iqi.getOperands();
                assertEquals(operands.length, 5);
                assertTrue(operands[0] instanceof ColumnQueryInfo);
                for (int i = 1; i < 3; ++i) {
                  assertTrue(operands[i] instanceof ParameterQueryInfo);
                }
                for (int i = 3; i < 5; ++i) {
                  assertTrue(operands[i] instanceof ConstantQueryInfo);
                }
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertEquals(pks.length, 4);
                for (int i = 0; i < pks.length; ++i) {
                  if (i < 2) {
                    assertTrue(pks[i] instanceof PrimaryDynamicKey);
                  }
                  else {
                    assertTrue(pks[i] instanceof RegionKey);
                  }
                }
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ResultSet rs = ps1.executeQuery();
      assertTrue(rs.next());
      assertEquals(rs.getInt(2), 1);
      assertEquals(rs.getInt(1), 2);
      assertEquals(rs.getString(3), "asif");
      // ps1.setInt(2,2);
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Test the IN operator. If the query contains more than one constants & the
   * lhs operand happens to be the primary key, then the query should not be get
   * convertible. *
   * 
   */
  public void testINWithMultipleConstantFields() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int primary key, "
        + "name varchar(10))");
    s.execute("Insert into  t1 values(1, 'asif')");
    // check get based query when there is a loader
    GfxdCallbacksTest.addLoader(null, "t1",
        "com.pivotal.gemfirexd.jdbc.TestRowLoader", "");

    String query = "select id, name from t1 "
        + "where id IN (1, 2, 1000, 2000)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertFalse(sqi.isDynamic());
                assertEquals(sqi.getParameterCount(), 0);
                assertTrue(sqi.getWhereClause() instanceof InQueryInfo);
                InQueryInfo iqi = (InQueryInfo)sqi.getWhereClause();
                QueryInfo[] operands = iqi.getOperands();
                assertEquals(5, operands.length);
                assertTrue(operands[0] instanceof ColumnQueryInfo);
                for (int i = 1; i < 5; ++i) {
                  assertTrue(operands[i] instanceof ConstantQueryInfo);
                }
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertEquals(4, pks.length);
                for (int i = 0; i < pks.length; ++i) {
                  assertTrue(pks[i] instanceof RegionKey);
                }
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);    
      ResultSet rs = ps1.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("asif", rs.getString(2));
      assertTrue(rs.next());
      assertEquals(1000, rs.getInt(1));
      assertEquals("Mark Black", rs.getString(2));
      assertFalse(rs.next());
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testRangeQueryInfoGeneration_1() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1  AND ID < 8";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                RangeQueryInfo rqi = (RangeQueryInfo)jqi.getOperands().get(0);
                assertEquals(rqi.getLowerBound(), new SQLInteger(1));
                assertEquals(rqi.getUpperBound(), new SQLInteger(8));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_THAN_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_THAN_RELOP);
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  
  

  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testRangeQueryInfoGeneration_2() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1  AND ID < 8 AND ID >=4";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                RangeQueryInfo rqi = (RangeQueryInfo)jqi.getOperands().get(0);
                assertEquals(rqi.getLowerBound(), new SQLInteger(4));
                assertEquals(rqi.getUpperBound(), new SQLInteger(8));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_THAN_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testRangeQueryInfoGeneration_3() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null  , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1  AND ID < 8 AND ID >=4  AND  ID <=7";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                RangeQueryInfo rqi = (RangeQueryInfo)jqi.getOperands().get(0);
                assertEquals(rqi.getLowerBound(), new SQLInteger(4));
                assertEquals(rqi.getUpperBound(), new SQLInteger(7));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_EQUALS_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testRangeQueryInfoGeneration_4() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1  AND ID < 8 AND ID >=4  AND  ID <=7 AND ID >12";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ConstantConditionsWrapperQueryInfo ccwqi= (ConstantConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List operands = ccwqi.getOperands();
                assertEquals(operands.size(), 2);
                Iterator itr = operands.iterator();
                AbstractConditionQueryInfo condition1 =(AbstractConditionQueryInfo) itr.next();
                AbstractConditionQueryInfo condition2 =(AbstractConditionQueryInfo) itr.next();
                ComparisonQueryInfo cqi =   condition1 instanceof ComparisonQueryInfo? (ComparisonQueryInfo)condition1: (ComparisonQueryInfo)condition2;
                RangeQueryInfo rqi =   condition1 instanceof RangeQueryInfo? (RangeQueryInfo)condition1: (RangeQueryInfo)condition2;
                assertEquals(rqi.getLowerBound(), new SQLInteger(4));
                assertEquals(rqi.getUpperBound(), new SQLInteger(7));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_EQUALS_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
                try {
                  assertEquals( ((ValueQueryInfo)cqi.rightOperand).evaluateToGetDataValueDescriptor(null), new SQLInteger(12));
                }catch (StandardException se) {
                  fail("test  failed because of exception="+se );
                }                
             
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testRangeQueryInfoGeneration_5() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1  AND ID < 8 AND ID >=4  AND  ID <=7 AND ID < -2 AND ID > 12";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ConstantConditionsWrapperQueryInfo ccwqi = (ConstantConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List operands  = ccwqi.getOperands();
                assertEquals(operands.size(), 3);
                Iterator itr = operands.iterator();
                AbstractConditionQueryInfo condition1 =(AbstractConditionQueryInfo) itr.next();
                AbstractConditionQueryInfo condition2 =(AbstractConditionQueryInfo) itr.next();
                AbstractConditionQueryInfo condition3 =(AbstractConditionQueryInfo) itr.next();
                //ComparisonQueryInfo cqi =   condition1 instanceof ComparisonQueryInfo? (ComparisonQueryInfo)condition1: (ComparisonQueryInfo)condition2;
                RangeQueryInfo rqi  = null;
                ComparisonQueryInfo cqi1 = null;
                ComparisonQueryInfo cqi2 = null;
                if( condition1 instanceof RangeQueryInfo ) {                  
                  rqi = (RangeQueryInfo)condition1;
                  cqi1 = (ComparisonQueryInfo)condition2;
                  cqi2 = (ComparisonQueryInfo)condition3;
                }
                if( condition2 instanceof RangeQueryInfo ) {
                  rqi = (RangeQueryInfo)condition2;
                  cqi1 = (ComparisonQueryInfo)condition1;
                  cqi2 = (ComparisonQueryInfo)condition3;
                }
                if( condition3 instanceof RangeQueryInfo ) {
                  rqi = (RangeQueryInfo)condition3;
                  cqi1 = (ComparisonQueryInfo)condition1;
                  cqi2 = (ComparisonQueryInfo)condition2;
                }                
                assertEquals(rqi.getLowerBound(), new SQLInteger(4));
                assertEquals(rqi.getUpperBound(), new SQLInteger(7));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_EQUALS_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
                try {
                  assertTrue( ((ValueQueryInfo)cqi1.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(-2)) || ((ValueQueryInfo)cqi1.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(12)) );
                  assertTrue( ((ValueQueryInfo)cqi2.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(-2)) || ((ValueQueryInfo)cqi2.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(12)) );
                }catch (StandardException se) {
                  fail("test  failed because of exception="+se );
                }
                
               /* assertEquals(rqi.getUpperBound(), new SQLInteger(7));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_EQUALS_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);*/
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testMultipleRangeQueryInfoGeneration_1() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null  , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID >= 1  AND ID >=9  AND  ID <= 11 AND ID <= 8";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ConstantConditionsWrapperQueryInfo ccwqi = (ConstantConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List operands  = ccwqi.getOperands();
                assertEquals(operands.size(), 2);
                Iterator itr = operands.iterator();
                AbstractConditionQueryInfo condition1 =(AbstractConditionQueryInfo) itr.next();
                AbstractConditionQueryInfo condition2 =(AbstractConditionQueryInfo) itr.next();
                ComparisonQueryInfo cqi =   condition1 instanceof ComparisonQueryInfo? (ComparisonQueryInfo)condition1: (ComparisonQueryInfo)condition2;
                RangeQueryInfo rqi =   condition1 instanceof RangeQueryInfo? (RangeQueryInfo)condition1: (RangeQueryInfo)condition2;
                            
                assertTrue(rqi.getLowerBound().equals( new SQLInteger(9)));           
                assertTrue(rqi.getUpperBound().equals( new SQLInteger(11) ));
                assertTrue(rqi.getUpperBoundOperator()== RelationalOperator.LESS_EQUALS_RELOP);                
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
                try {
                  assertTrue(((ValueQueryInfo)cqi.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(8)) );
                 
                }catch (StandardException se) {
                  fail("test  failed because of exception="+se );
                }

               
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testMultipleRangeQueryInfoGeneration_2() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID >= 1  AND ID <=9  AND  ID >= 11 AND ID <= 13";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ConstantConditionsWrapperQueryInfo ccwqi = (ConstantConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List operands = ccwqi.getOperands();
                assertEquals(operands.size(), 2);
                Iterator itr = operands.iterator();
                RangeQueryInfo rqi1 =(RangeQueryInfo) itr.next();
                RangeQueryInfo rqi2 =(RangeQueryInfo) itr.next();                            
                assertTrue(rqi1.getLowerBound().equals( new SQLInteger(1)) || rqi1.getLowerBound().equals( new SQLInteger(11)));           
                assertTrue(rqi2.getLowerBound().equals( new SQLInteger(1)) || rqi2.getLowerBound().equals( new SQLInteger(11)));
                assertTrue(rqi1.getUpperBound().equals( new SQLInteger(9) ) || rqi1.getUpperBound().equals( new SQLInteger(13) ));
                assertTrue(rqi2.getUpperBound().equals( new SQLInteger(9) ) || rqi2.getUpperBound().equals( new SQLInteger(13) ));
                assertTrue(rqi1.getUpperBoundOperator()== RelationalOperator.LESS_EQUALS_RELOP);                
                assertTrue(rqi2.getUpperBoundOperator()== RelationalOperator.LESS_EQUALS_RELOP);
                assertEquals(rqi1.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
                assertEquals(rqi2.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);             
               
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testMultipleRangeQueryInfoGeneration_3() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID >= 1 AND  ID =7  AND ID <=9  ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 2);
               // ConstantConditionsWrapperQueryInfo ccwqi = (ConstantConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List operands = jqi.getOperands();
                //assertEquals(operands.size(), 2);
                Iterator itr = operands.iterator();
                AbstractConditionQueryInfo condition1 =(AbstractConditionQueryInfo) itr.next();
                AbstractConditionQueryInfo condition2 =(AbstractConditionQueryInfo) itr.next();
                ComparisonQueryInfo cqi =   condition1 instanceof ComparisonQueryInfo? (ComparisonQueryInfo)condition1: (ComparisonQueryInfo)condition2;
                RangeQueryInfo rqi =   condition1 instanceof RangeQueryInfo? (RangeQueryInfo)condition1: (RangeQueryInfo)condition2;
                            
                assertTrue(rqi.getLowerBound().equals( new SQLInteger(1)));           
                assertTrue(rqi.getUpperBound().equals( new SQLInteger(9) ));
                assertTrue(rqi.getUpperBoundOperator()== RelationalOperator.LESS_EQUALS_RELOP);                
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
                try {
                  assertTrue(((ValueQueryInfo)cqi.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(7)) );
                 
                }catch (StandardException se) {
                  fail("test  failed because of exception="+se );
                }                     
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testMultipleNonRangeConditions() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID <= -10 AND ID >= 1  AND ID <= 9  ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ConstantConditionsWrapperQueryInfo ccwqi = (ConstantConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List operands  = ccwqi.getOperands();
                assertEquals(operands.size(), 2);
                Iterator itr = operands.iterator();
                ComparisonQueryInfo cqi1 =(ComparisonQueryInfo) itr.next();
                ComparisonQueryInfo cqi2 =(ComparisonQueryInfo) itr.next();
                try {
                  assertTrue(((ValueQueryInfo)cqi1.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(-10))||((ValueQueryInfo)cqi1.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(1)) );
                  assertTrue(((ValueQueryInfo)cqi2.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(-10))||((ValueQueryInfo)cqi2.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(1)) );

                 
                }catch (StandardException se) {
                  fail("test  failed because of exception="+se );
                }                     
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  /**
   * Tests the formation of right data strcutures if 
   * parameters are present
   */
  public void testParameterizedConditionsWrapper_1() throws Exception{
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1  AND ID < ?";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ParameterizedConditionsWrapperQueryInfo pcwqi = (ParameterizedConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List paramOps = pcwqi.getParameterizedOperands();
                ComparisonQueryInfo constandOp = (ComparisonQueryInfo)pcwqi.getConstantOperand();                
                assertTrue(constandOp instanceof ComparisonQueryInfo);
                assertEquals(paramOps.size(), 1);
                
                ComparisonQueryInfo cqi2 = (ComparisonQueryInfo)paramOps.get(0);                
                try {
                  assertTrue( ((ValueQueryInfo)constandOp.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(1)));                 
                  assertTrue( cqi2.rightOperand instanceof ParameterQueryInfo);
                }catch (StandardException se) {
                  fail("test  failed because of exception="+se );
                } 
              }

            }

          });

      conn.prepareStatement(query);     
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  /**
   * Tests the formation of right data strcutures if 
   * parameters are present
   */
  public void testParameterizedConditionsWrapper_2() throws Exception{
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1  AND ID < ? AND ID <10";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ParameterizedConditionsWrapperQueryInfo pcw = (ParameterizedConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                List paramOps = pcw.getParameterizedOperands();
                RangeQueryInfo rqi= (RangeQueryInfo)pcw.getConstantOperand();                
                assertEquals(paramOps.size(), 1);                
                ComparisonQueryInfo cqi2 = (ComparisonQueryInfo)paramOps.get(0);                
                assertTrue( cqi2.rightOperand instanceof ParameterQueryInfo);
                assertTrue(rqi.getLowerBound().equals( new SQLInteger(1)));           
                assertTrue(rqi.getUpperBound().equals( new SQLInteger(10) ));
                assertTrue(rqi.getUpperBoundOperator()== RelationalOperator.LESS_THAN_RELOP);                
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_THAN_RELOP);
             
                 
              }

            }

          });

      conn.prepareStatement(query);     
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testANDJunctionWithInClause() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID IN (1,2,3)  AND ADDRESS = 'first'";
    //String query = "Select * from TESTTABLE where ID = 1   AND ADDRESS = 'first'";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 2);
                if( jqi.getOperands().get(0) instanceof InQueryInfo) {
                  assertTrue(jqi.getOperands().get(1) instanceof ComparisonQueryInfo && !(jqi.getOperands().get(1) instanceof InQueryInfo) );
                }else {
                  assertTrue(jqi.getOperands().get(1) instanceof InQueryInfo);
                  assertTrue(jqi.getOperands().get(0) instanceof ComparisonQueryInfo && !(jqi.getOperands().get(0) instanceof InQueryInfo));
                }
              }

            }

          });

      try {
        s.executeQuery(query);
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
  
  public void testEqualityConditions_1() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID = 1   AND ADDRESS = 'first' AND  DESCRIPTION = 'xxx'";
    //String query = "Select * from TESTTABLE where ID = 1   AND ADDRESS = 'first'";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 3);
                Map eqMap = jqi.getEqualityConditionsMap();
                assertEquals(eqMap.size(),1);
                Collection operands = ((Map)eqMap.values().iterator().next()).values();
                assertEquals(operands.size(),3);
                Iterator opItr = operands.iterator();
                Set colNames = new HashSet();
                colNames.add("ID");
                colNames.add("ADDRESS");
                colNames.add("DESCRIPTION");
                while( opItr.hasNext()) {
                  ComparisonQueryInfo cqi = (ComparisonQueryInfo) opItr.next();                
                  assertTrue(colNames.remove(((ColumnQueryInfo)cqi.leftOperand).getActualColumnName()));
                }
                assertTrue(colNames.isEmpty());
              }
            }
          });

      try {
        s.executeQuery(query);
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
  
  public void testEqualityConditions_2() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID = 1   AND ADDRESS = 'first' AND  DESCRIPTION = 'xxx' AND ID > -1";
    //String query = "Select * from TESTTABLE where ID = 1   AND ADDRESS = 'first'";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 4);
                Map eqMap = jqi.getEqualityConditionsMap();
                assertEquals(eqMap.size(),1);
                Collection operands = ((Map)eqMap.values().iterator().next()).values();
                assertEquals(operands.size(),3);
                Iterator opItr = operands.iterator();
                Set colNames = new HashSet();
                colNames.add("ID");
                colNames.add("ADDRESS");
                colNames.add("DESCRIPTION");
                while( opItr.hasNext()) {
                  ComparisonQueryInfo cqi = (ComparisonQueryInfo) opItr.next();                
                  assertTrue(colNames.remove(((ColumnQueryInfo)cqi.leftOperand).getActualColumnName()));
                }
                assertTrue(colNames.isEmpty());
                Map inequalityOp = jqi.getInEqualityConditionsMap();
                assertEquals(inequalityOp.size(),1);
                ComparisonQueryInfo cqi = (ComparisonQueryInfo) inequalityOp.values().iterator().next();
                assertEquals(cqi.getRelationalOperator(),RelationalOperator.GREATER_THAN_RELOP);
                assertEquals(((ColumnQueryInfo)cqi.leftOperand).getActualColumnName(),"ID");
              }
            }
          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
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
   * Tests the IN clause on multi column primary key which should be executed as 
   * multiple region.get operations
   *
   * [sumedh] currently this should go as a statement rather than getAll due
   * to non-optimized impl of latter in GFE (#44210)
   */
  public void testBulkGetConvertibleInClauseWithMultiColumnPrimaryKey_1()  throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int , name varchar(10), type int, address varchar(50), Primary Key (id, name ))");
    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");    
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where id IN (?,?,?) AND name  IN (?,?,?)";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isDynamic());
                assertEquals(sqi.getParameterCount(), 6);
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 2);
                assertTrue(jqi.getOperands().get(0) instanceof InQueryInfo);
                assertTrue(jqi.getOperands().get(1) instanceof InQueryInfo);
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertEquals(pks.length,9);
                for(int i = 0; i < pks.length; ++i) {
                  assertTrue(pks[i] instanceof CompositeDynamicKey);
                }
                try {
                  assertTrue(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ps1.setInt(3, 3);
      ps1.setString(4, "j 604");
      ps1.setString(5, "j 604");
      ps1.setString(6, "j 604");
      ps1.executeQuery();     
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  /**
   * Tests the IN clause on multi column primary key which should be executed as 
   * multiple region.get operations
   *
   * [sumedh] currently this should go as a statement rather than getAll due
   * to non-optimized impl of latter in GFE (#44210)
   */
  public void testBulkGetConvertibleInClauseWithMultiColumnPrimaryKey_2()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table t1 ( id int , name varchar(10), type int, address varchar(50), Primary Key (id, name ))");
    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where id IN (1,2,3) AND name  IN ('a','b','c')";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertFalse(sqi.isDynamic());
                assertEquals(sqi.getParameterCount(), 0);
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(),
                    QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 2);
                assertTrue(jqi.getOperands().get(0) instanceof InQueryInfo);
                assertTrue(jqi.getOperands().get(1) instanceof InQueryInfo);
                Object[] pks = (Object[])sqi.getPrimaryKey();
                assertEquals(pks.length, 9);
                Set keys = new HashSet();
                for (int i = 1; i < 4; ++i) {
                  for (int j = 'a'; j < 'd'; ++j) {
                    keys.add(i + "" + (char)j);
                  }
                }
                try {
                  for (int i = 0; i < pks.length; ++i) {
                    assertTrue(pks[i] instanceof RegionKey);
                    RegionKey gfk = (RegionKey)pks[i];
                    DataValueDescriptor[] dvdArr =
                      new DataValueDescriptor[gfk.nCols()];
                    gfk.getKeyColumns(dvdArr);
                    assertEquals(dvdArr.length, 2);
                    String temp = dvdArr[0].getInt() + ""
                        + dvdArr[1].getString();
                    assertTrue(keys.remove(temp));
                  }
                }
                catch (StandardException se) {
                  se.printStackTrace();
                  fail(se.toString());
                }
                assertTrue(keys.isEmpty());
                try {
                  assertTrue(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.executeQuery();
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  /**
   * Tests if the where clause contains multiple conditions on same column forming
   * a close range, a range query info object is created
   * 
   */
  public void testANDJunctionConditionNotSupportingRange() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where (ID > 1 AND ID < 10)  AND ID < -1";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                ConstantConditionsWrapperQueryInfo ccwqi = (ConstantConditionsWrapperQueryInfo)jqi.getOperands().get(0);
                assertEquals(ccwqi.getOperands().size() ,2);
               
                if(ccwqi.getOperands().get(0) instanceof RangeQueryInfo || ccwqi.getOperands().get(1) instanceof RangeQueryInfo) {
                  RangeQueryInfo rqi ;
                  ComparisonQueryInfo cqi;
                  if(ccwqi.getOperands().get(0) instanceof RangeQueryInfo ) {                    
                    rqi = (RangeQueryInfo)ccwqi.getOperands().get(0);
                    cqi = (ComparisonQueryInfo)ccwqi.getOperands().get(1);
                  } else {
                    rqi = (RangeQueryInfo)ccwqi.getOperands().get(1);
                    cqi = (ComparisonQueryInfo)ccwqi.getOperands().get(0);  
                  }
                  try {
                    assertEquals(1, rqi.getLowerBound().getInt());
                    assertEquals(RelationalOperator.GREATER_THAN_RELOP, rqi
                        .getLowerBoundOperator());
                    assertEquals(10, rqi.getUpperBound().getInt());
                    assertEquals(RelationalOperator.LESS_THAN_RELOP, rqi
                        .getUpperBoundOperator());

                    assertEquals(((ValueQueryInfo)cqi.rightOperand)
                        .evaluateToGetDataValueDescriptor(null),
                        new SQLInteger(-1));
                  }catch(StandardException se) {
                    se.printStackTrace();
                    fail("Test failed because of .Exception= "+se);
                  }                 
                  
                }else {
                  ComparisonQueryInfo cqi1 = (ComparisonQueryInfo)ccwqi.getOperands().get(0);
                  ComparisonQueryInfo cqi2 = (ComparisonQueryInfo)ccwqi.getOperands().get(1);
                  try {
                    assertTrue( ((ValueQueryInfo)cqi1.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(-1)) || ((ValueQueryInfo)cqi1.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(1)));  
                    assertTrue( ((ValueQueryInfo)cqi2.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(-1)) || ((ValueQueryInfo)cqi2.rightOperand).evaluateToGetDataValueDescriptor(null).equals(new SQLInteger(1)));
                  }catch(StandardException se) {
                    se.printStackTrace();
                    fail("Test failed because of .Exception= "+se);
                  }
                  
                }
                
                              
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  
  public void testBug39530_1() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "Select * from TESTTABLE where ID > 1 AND ID >= 4   AND ID < 8 AND ID <= 4";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                RangeQueryInfo rqi = (RangeQueryInfo)jqi.getOperands().get(0);
                assertEquals(rqi.getLowerBound(), new SQLInteger(4));
                assertEquals(rqi.getUpperBound(), new SQLInteger(4));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_EQUALS_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  public void testBug39530_2() throws Exception {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))");
    String query = "select ID, DESCRIPTION from TESTTABLE where ID  >= 1 And ID <= 5  AND ID <= 7 AND ID >= 5";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo jqi = (AndJunctionQueryInfo)acqi;
                assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
                assertEquals(jqi.getOperands().size(), 1);
                RangeQueryInfo rqi = (RangeQueryInfo)jqi.getOperands().get(0);
                assertEquals(rqi.getLowerBound(), new SQLInteger(5));
                assertEquals(rqi.getUpperBound(), new SQLInteger(5));
                assertEquals(rqi.getUpperBoundOperator(), RelationalOperator.LESS_EQUALS_RELOP);
                assertEquals(rqi.getLowerBoundOperator(), RelationalOperator.GREATER_EQUALS_RELOP);
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
        throw new SQLException(e.toString()
            + " Exception in executing query = " + query, e.getSQLState());
      }
      assertTrue(this.callbackInvoked);      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
 
  public void testMergeTwoAndJunctionsToFormORJunction() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024) , TYPE int)");
    String query = "select ID, DESCRIPTION from TESTTABLE where ((DESCRIPTION  = ? and ADDRESS < ?) OR (DESCRIPTION =? and ADDRESS >?))  and ID IN (?, ?, ?, ?, ?) and TYPE = ? ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi.isStaticallyNotGetConvertible());
                assertTrue(acqi.isWhereClauseDynamic());
                assertTrue(acqi instanceof AndJunctionQueryInfo);
                AndJunctionQueryInfo root = (AndJunctionQueryInfo)acqi;
                assertEquals(root.getJunctionType(),
                    QueryInfoConstants.AND_JUNCTION);
                assertEquals(root.getOperands().size(), 2);

                List Ops = root.getOperands();

                Iterator prmItr = Ops.iterator();
                while (prmItr.hasNext()) {
                  AbstractConditionQueryInfo temp = (AbstractConditionQueryInfo)prmItr
                      .next();
                  assertTrue(temp instanceof InQueryInfo
                      || temp instanceof ComparisonQueryInfo);
                }
                OrJunctionQueryInfo ojqi = (OrJunctionQueryInfo)root.children[0];
                assertTrue(ojqi.isStaticallyNotGetConvertible());
                assertTrue(ojqi.isWhereClauseDynamic());
                assertTrue(ojqi.getOperands().isEmpty());
                assertEquals(ojqi.children.length, 2);
                assertTrue(ojqi.children[0] instanceof AndJunctionQueryInfo);
                assertTrue(ojqi.children[0].isStaticallyNotGetConvertible());
                assertEquals(ojqi.children[0].getOperands().size(),2);
                assertTrue(ojqi.children[1] instanceof AndJunctionQueryInfo);
                assertTrue(ojqi.children[1].isStaticallyNotGetConvertible());
                assertEquals(ojqi.children[1].getOperands().size(),2);

              }

            }

          });

      conn.prepareStatement(query);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  public void testMergeTwoORJunctionsToFormANDJunction() throws Exception
  {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024) , TYPE int)");
    String query = "select ID, DESCRIPTION from TESTTABLE where ((DESCRIPTION  = 'a' OR ADDRESS < 'c') AND (DESCRIPTION ='d' OR ADDRESS > 'b'))  OR ID IN (1, 2, 3, 4, 5) OR TYPE = 3 ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof OrJunctionQueryInfo);
                OrJunctionQueryInfo root = (OrJunctionQueryInfo)acqi;
                assertEquals(root.getJunctionType(),
                    QueryInfoConstants.OR_JUNCTION);
                assertTrue(root.isStaticallyNotGetConvertible());
                assertFalse(root.isWhereClauseDynamic());
                assertEquals(root.getOperands().size(), 2);
                List Ops = root.getOperands();
                Iterator prmItr = Ops.iterator();
                while (prmItr.hasNext()) {
                  AbstractConditionQueryInfo temp = (AbstractConditionQueryInfo)prmItr
                      .next();
                  assertTrue(temp instanceof InQueryInfo
                      || temp instanceof ComparisonQueryInfo);
                }
                AndJunctionQueryInfo ajqi = (AndJunctionQueryInfo)root.children[0];
                assertTrue(ajqi.getOperands().isEmpty());
                assertEquals(ajqi.children.length, 2);
                assertTrue(ajqi.children[0] instanceof OrJunctionQueryInfo);
                assertEquals(ajqi.children[0].getOperands().size(),2);
                assertTrue(ajqi.children[1] instanceof OrJunctionQueryInfo);
                assertEquals(ajqi.children[1].getOperands().size(),2);

              }

            }

          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement stmnt = conn.createStatement();
      stmnt.executeQuery(query);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  public void testMergeTwoORJunctionsToFormANDJunctionWithParameters() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024) , TYPE int)");
    String query = "select ID, DESCRIPTION from TESTTABLE " + 
    "where ((DESCRIPTION  = 'a' OR ADDRESS < 'c') AND (DESCRIPTION =? OR ADDRESS > ? OR ID > 3))  OR ID IN (1, 2, 3, 4, 5) OR TYPE = 3 ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof OrJunctionQueryInfo);
                OrJunctionQueryInfo root = (OrJunctionQueryInfo)acqi;
                assertEquals(root.getJunctionType(),
                    QueryInfoConstants.OR_JUNCTION);
                assertTrue(root.isStaticallyNotGetConvertible());
                assertTrue(root.isWhereClauseDynamic());
                assertEquals(root.getOperands().size(), 2);
                List Ops = root.getOperands();
                Iterator prmItr = Ops.iterator();
                while (prmItr.hasNext()) {
                  AbstractConditionQueryInfo temp = (AbstractConditionQueryInfo)prmItr
                      .next();
                  assertTrue(temp instanceof InQueryInfo
                      || temp instanceof ComparisonQueryInfo);
                  if(temp instanceof InQueryInfo) {
                    assertFalse(temp.isStaticallyNotGetConvertible());                    
                  }
                }
                AndJunctionQueryInfo ajqi = (AndJunctionQueryInfo)root.children[0];
                assertTrue(ajqi.getOperands().isEmpty());
                assertTrue(ajqi.isWhereClauseDynamic());
                assertTrue(ajqi.isStaticallyNotGetConvertible());
                assertEquals(ajqi.children.length, 2);
                assertTrue(ajqi.children[0] instanceof OrJunctionQueryInfo);                
                assertTrue(ajqi.children[1] instanceof OrJunctionQueryInfo);
                if(ajqi.children[0].getOperands().size() == 3) {
                  assertTrue(ajqi.children[0].isWhereClauseDynamic());
                  assertTrue(ajqi.children[0].isStaticallyNotGetConvertible());
                }
                if(ajqi.children[1].getOperands().size() == 2) {
                  assertFalse(ajqi.children[1].isWhereClauseDynamic());
                  assertTrue(ajqi.children[1].isStaticallyNotGetConvertible());
                }

              }

            }

          });

      conn.prepareStatement(query);      
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  public void testMergeTwoANDJunctionsToFormANDJunctionWithParameters() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + " TYPE int, ADDRESS varchar(1024),DESCRIPTION varchar(1024)  )");
    String query = "select ID, DESCRIPTION from TESTTABLE " + 
    "where ((DESCRIPTION  = 'a' AND ADDRESS < 'c') AND (TYPE =? AND ADDRESS > ? AND ID > 3))  OR ID IN (1, 2, 3, 4, 5) OR TYPE = 3 ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                AbstractConditionQueryInfo acqi = sqi.getWhereClause();
                assertTrue(acqi instanceof OrJunctionQueryInfo);
                OrJunctionQueryInfo root = (OrJunctionQueryInfo)acqi;
                assertEquals(root.getJunctionType(),
                    QueryInfoConstants.OR_JUNCTION);
                assertTrue(root.isStaticallyNotGetConvertible());
                assertTrue(root.isWhereClauseDynamic());
                assertEquals(root.getOperands().size(), 2);
                List Ops = root.getOperands();
                Iterator prmItr = Ops.iterator();
                while (prmItr.hasNext()) {
                  AbstractConditionQueryInfo temp = (AbstractConditionQueryInfo)prmItr
                      .next();
                  assertTrue(temp instanceof InQueryInfo
                      || temp instanceof ComparisonQueryInfo);
                  if(temp instanceof InQueryInfo) {
                    assertFalse(temp.isStaticallyNotGetConvertible());                    
                  }
                }
                AndJunctionQueryInfo ajqi = (AndJunctionQueryInfo)root.children[0];
                assertEquals(ajqi.getOperands().size(),4);
                assertTrue(ajqi.isWhereClauseDynamic());
                assertTrue(ajqi.isStaticallyNotGetConvertible());                
                assertTrue(ajqi.children ==null);
                Iterator iter = ajqi.getOperands().iterator();
                //The first two operands should be equality conditions
                ComparisonQueryInfo cqi = (ComparisonQueryInfo)iter.next();
                assertEquals( ((ColumnQueryInfo)cqi.leftOperand).getActualColumnName(),"TYPE");
                cqi = (ComparisonQueryInfo)iter.next();
                assertEquals( ((ColumnQueryInfo)cqi.leftOperand).getActualColumnName(),"DESCRIPTION");
                AbstractConditionQueryInfo third, fourth;
                third = (AbstractConditionQueryInfo)iter.next();
                fourth= (AbstractConditionQueryInfo)iter.next();
                if(third instanceof ParameterizedConditionsWrapperQueryInfo) {
                  assertTrue(fourth instanceof ComparisonQueryInfo);
                  assertTrue(((ParameterizedConditionsWrapperQueryInfo)third).getConstantOperand() instanceof ComparisonQueryInfo);
                  assertEquals(((ParameterizedConditionsWrapperQueryInfo)third).getParameterizedOperands().size(),1 );
                }else {                  
                  assertTrue(third instanceof ComparisonQueryInfo);                  
                  assertTrue(fourth instanceof ParameterizedConditionsWrapperQueryInfo);
                  assertTrue(((ParameterizedConditionsWrapperQueryInfo)fourth).getConstantOperand() instanceof ComparisonQueryInfo);
                  assertEquals(((ParameterizedConditionsWrapperQueryInfo)fourth).getParameterizedOperands().size(),1 );
                }
              }

            }

          });

      conn.prepareStatement(query);      
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  public void testBug39680() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024),primary key (ID))");
    String query = "select * from TESTTABLE where DESCRIPTION  = 'asif' And ID = 5";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                GfxdPartitionResolver resolver = (GfxdPartitionResolver)((PartitionedRegion) sqi.getRegion()).getPartitionResolver();
                assertNotNull(resolver);
              }

            }

          });

      try {
        s.executeQuery(query);
      }
      catch (SQLException e) {
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

  public void testEquijoinQueryForPRAndReplicatedRegion() throws Exception {
    setupConnection();
    sqlExecute("create table TESTTABLE1 (ID1 int not null, DESCRIPTION1 "
        + "varchar(1024) not null, ADDRESS1 varchar(1024) not null, "
        + "primary key (ID1)) PARTITION BY Column ( ID1 )", false);

    sqlExecute("create table TESTTABLE2 (ID2 int not null, DESCRIPTION2 "
        + "varchar(1024) not null, ADDRESS2 varchar(1024) not null, "
        + "primary key (ID2)) REPLICATE", true);

    String query = "select * from TESTTABLE1, TESTTABLE2 where ID1 = ID2 "
        + "AND ID2 = 1";

    final LanguageConnectionContext lcc = ((EmbedConnection)jdbcConn)
        .getLanguageConnection();
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                Set<Object> actualRoutingKeys = new HashSet<Object>();
                actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
                try {
                  Activation act = new GemFireSelectActivation(gps, lcc,
                      (DMLQueryInfo)qInfo, null, false);
                  sqi.computeNodes(actualRoutingKeys, act, false);
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }

            }

          });
      try {
        sqlExecute(query, false);
      } catch (SQLException e) {
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

  public void testEquijoinQueryForTwoReplicatedRegions() throws Exception {
    setupConnection();
    sqlExecute("create table TESTTABLE1 (ID1 int not null, DESCRIPTION1 "
        + "varchar(1024) not null, ADDRESS1 varchar(1024) not null, "
        + "primary key (ID1)) REPLICATE", true);

    sqlExecute("create table TESTTABLE2 (ID2 int not null, DESCRIPTION2 "
        + "varchar(1024) not null, ADDRESS2 varchar(1024) not null, "
        + "primary key (ID2)) REPLICATE", false);

    sqlExecute("insert into TESTTABLE1 values (1, 'First1', 'Addr11')", true);
    sqlExecute("insert into TESTTABLE1 values (2, 'Second1', 'Addr12')", true);
    sqlExecute("insert into TESTTABLE1 values (3, 'Third1', 'Addr13')", true);
    sqlExecute("insert into TESTTABLE2 values (1, 'First2', 'Addr21')", true);
    sqlExecute("insert into TESTTABLE2 values (2, 'Second2', 'Addr22')", true);
    sqlExecute("insert into TESTTABLE2 values (3, 'Third2', 'Addr23')", true);

    sqlExecuteVerifyText(
        "select * from TESTTABLE1, TESTTABLE2 where ID1 = ID2 "
            + "AND ID2 = 1", TestUtil.getResourcesDir()
            + "/lib/checkEquiJoinQuery.xml", "query_repl", false, false);
  }
  
  public void testMultiColumnResolverNodesPruningForMultipleINClause()
      throws Exception
  {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table TESTTABLE (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, ID_1 int not null, primary key (ID)) Partition by Column(ID, ID_1)");

    String query = "select ID, DESCRIPTION from TESTTABLE where ID_1  IN (5003,9837,1000101) AND ID IN (76542,138371,91332783)";
    // Insert values 1 to 7
    for (int i = 0; i < 7; ++i) {
      s.execute("insert into TESTTABLE values (" + (i + 1) + ", 'First"
          + (i + 1) + "'," + (i + 1) + ")");
    }
    final LanguageConnectionContext lcc = ((EmbedConnection)conn)
        .getLanguageConnection();
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                Set<Object> actualRoutingKeys = new HashSet<Object>();
                actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
                try {
                  Activation act = new GemFireSelectActivation(gps, lcc,
                      (DMLQueryInfo)qInfo, null, false);
                  sqi.computeNodes(actualRoutingKeys, act, false);
                  //Externally compute the routing objects.
                  Set <Object> externallyCmputed = new HashSet<Object>();
                  GfxdPartitionResolver spr = (GfxdPartitionResolver)((PartitionedRegion)sqi
                      .getRegion()).getPartitionResolver();
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(76542), new SQLInteger(5003) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(138371), new SQLInteger(5003) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(91332783), new SQLInteger(5003) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(76542), new SQLInteger(9837) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(138371), new SQLInteger(9837) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(91332783), new SQLInteger(9837) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(76542), new SQLInteger(1000101) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(138371), new SQLInteger(1000101) }));
                  externallyCmputed
                      .add(spr
                          .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
                              new SQLInteger(91332783), new SQLInteger(1000101) }));
                  assertFalse(actualRoutingKeys
                      .contains(ResolverUtils.TOK_ALL_NODES));
                  assertEquals(externallyCmputed.size(),actualRoutingKeys.size());
                  assertTrue(externallyCmputed.removeAll(actualRoutingKeys));
                  assertTrue(externallyCmputed.isEmpty());
                }
                catch (Exception e) {
                  e.printStackTrace();
                  fail("test failed because of exception=" + e.toString());
                }
              }

            }

          });

      s.executeQuery(query);
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }

  public void testBug41299() throws Exception {

  
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      s
          .execute("create table TESTTABLE (ID int not null, "
              + " DESCRIPTION varchar(1024) not null, ID_1 int not null, ID_2 int not null, ID_3 int not null, primary key (ID)) Partition by ( ((ID + ID_1)*ID_2)/ID_3 )");
      s
      .execute("create table TESTTABLE1 (ID int not null, "
          + " DESCRIPTION varchar(1024) not null, ID_1 int not null, ID_2 int not null, ID_3 int not null, primary key (ID)) Partition by ( (ID + ID_1)*ID_2 )");
              
  
  }
  public void testNodesPruningUsingPartitionByExpression_Bug40857_2()
      throws Exception
  {
    try {
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      s
          .execute("create table TESTTABLE (a int not null, "
              + " DESCRIPTION varchar(1024) not null, b int not null, c int not null,d int not null, primary key (a)) Partition by ( ((c  + b)*a)/d )");

      String queries[] = new String[] {
          "select * from TESTTABLE where   ((c  + b)*a )/d = 5",
          "select * from TESTTABLE where a = 7 and  b = 8 and  c = 1 and d = 3",
          "select * from TESTTABLE where  ((c  + b)*a)/d = 5 and Description = 'abc'" ,
          "select * from TESTTABLE where  (c  + b)*a/d = 5 and Description = 'abc'" ,
          "select * from TESTTABLE where  (c  + b)*a/d = 5 and a = 7 and b =8  and c=1 and d =3  and Description = 'abc'" ,
          "select * from TESTTABLE where  (c  + b)*a/d = 5 OR ( a = 7 and b =8  and c=1 and d =3 ) and Description = 'abc'" ,
          "select * from TESTTABLE x where  (x.c  + x.b)*x.a/d = 5 OR ( a = 7 and b =8  and c=1 and d =3 ) and Description = 'abc'",
          "select * from TESTTABLE x where  (x.c  + x.b)*x.a/d = 5 OR ( a IN (7,5) and b IN (8,7)  and c=1 and d =3 ) and Description = 'abc'" /*,
          "select * from TESTTABLE where  (b  + c)*a/d = 5 and Description = 'abc'"*/
          };
      Object expectedRoutingKeys[] = new Object[] {  new Integer(5), new Integer(21),
          new Integer(5) ,new Integer(5) /*,new Integer(5)*/, new Object[]{/*new Integer(5),*/ new Integer(21)},
          new Object[]{new Integer(5), new Integer(21)},new Object[]{new Integer(5), new Integer(21)},new Object[]{new Integer(5), new Integer(21),new Integer(15),new Integer(13),new Integer(18)}};
      final LanguageConnectionContext lcc = ((EmbedConnection)conn)
          .getLanguageConnection();
      for (int i = 0; i < queries.length; ++i) {

        final Object expectedRoutingKey = expectedRoutingKeys[i];
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
              @Override
              public void queryInfoObjectAfterPreparedStatementCompletion(
                  QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
                if (qInfo instanceof SelectQueryInfo) {
                  SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                  assertTrue(qInfo instanceof SelectQueryInfo);
                  SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                  Set<Object> actualRoutingKeys = new HashSet<Object>();
                  actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
                  try {
                    Activation act = new GemFireSelectActivation(gps, lcc,
                        (DMLQueryInfo)qInfo, null, false);
                    ((BaseActivation)act).initFromContext(lcc, true, gps);
                    sqi.computeNodes(actualRoutingKeys, act, false);
                    if (expectedRoutingKey instanceof Object[]) {
                      Object[] keys = (Object[])expectedRoutingKey;
                      for (Object key : keys) {
                        assertTrue(actualRoutingKeys.remove(key));
                      }
                      assertTrue(actualRoutingKeys.isEmpty());
                    }
                    else {
                      assertTrue(actualRoutingKeys.remove(expectedRoutingKey));
                      assertTrue(actualRoutingKeys.isEmpty());
                    }

                  } catch (Exception e) {
                    e.printStackTrace();
                    fail("test failed because of exception=" + e.toString());
                  }
                }

              }

            });

        s.executeQuery(queries[i]);
        assertTrue(this.callbackInvoked);
        this.callbackInvoked = false;
      }
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  public void testNodesPruningUsingPartitionByExpression_Bug40857_1()
      throws Exception
  {
    try {
      System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      s
          .execute("create table TESTTABLE (a int not null, "
              + " DESCRIPTION varchar(1024) not null, b int not null, c int not null,d int not null, primary key (a)) Partition by  (c * a + b)");

      String queries[] = new String[] {
          "select * from TESTTABLE where c * a = 3" ,
          "select * from TESTTABLE where  (c * a + b) = 5",
          "select * from TESTTABLE where a = 7 and  b = 8 and  c = 1 and d = 3" ,          
          "select * from TESTTABLE where  (c *a + b) = 5 and Description = 'abc'"
        };
      Object expectedRoutingKeys[] = new Object[] {
          ResolverUtils.TOK_ALL_NODES, new Integer(5), new Integer(15),
          new Integer(5) };
      final LanguageConnectionContext lcc = ((EmbedConnection)conn)
          .getLanguageConnection();
      for (int i = 0; i < queries.length; ++i) {

        final Object expectedRoutingKey = expectedRoutingKeys[i];
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
              @Override
              public void queryInfoObjectAfterPreparedStatementCompletion(
                  QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
                if (qInfo instanceof SelectQueryInfo) {
                  SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                  assertTrue(qInfo instanceof SelectQueryInfo);
                  SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                  Set<Object> actualRoutingKeys = new HashSet<Object>();
                  actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
                  try {
                    Activation act = new GemFireSelectActivation(gps, lcc,
                        (DMLQueryInfo)qInfo, null, false);
                    ((BaseActivation)act).initFromContext(lcc, true, gps);
                    sqi.computeNodes(actualRoutingKeys, act, false);
                    if (expectedRoutingKey instanceof Object[]) {
                      Object[] keys = (Object[])expectedRoutingKey;
                      for (Object key : keys) {
                        assertTrue(actualRoutingKeys.remove(key));
                      }
                      assertTrue(actualRoutingKeys.isEmpty());
                    }
                    else {
                      assertTrue(actualRoutingKeys.remove(expectedRoutingKey));
                      assertTrue(actualRoutingKeys.isEmpty());
                    }

                  }
                  catch (Exception e) {
                    e.printStackTrace();
                    fail("test failed because of exception=" + e.toString());
                  }
                }

              }

            });

        s.executeQuery(queries[i]);
        assertTrue(this.callbackInvoked);
        this.callbackInvoked = false;
      }
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }
  
  public void testBug40329() throws Exception {
    setupConnection();
    sqlExecute("create table TESTTABLE1 (ID1 int not null, DESCRIPTION1 "
        + "varchar(1024) not null, ADDRESS1 varchar(1024) not null, PRICE1 int not null, "
        + "primary key (ID1)) ", true);

    sqlExecute("create table TESTTABLE2 (ID2 int not null, DESCRIPTION2 "
        + "varchar(1024) not null, ADDRESS2 varchar(1024) not null, PRICE2 int not null, "
        + "primary key (ID2)) replicate ", false);
    sqlExecute("create table TESTTABLE3 (ID3 int not null, DESCRIPTION3 "
        + "varchar(1024) not null, ADDRESS3 varchar(1024) not null,  PRICE3 int not null, SUBTOTAL3 int not null,"
        + "primary key (ID3)) ", false);
    sqlExecute("create table TESTTABLE4 (ID4 int not null, DESCRIPTION4 "
        + "varchar(1024) not null, ADDRESS4 varchar(1024) not null, "
        + "primary key (ID4)) ", false);
//    String query = select * from trade.customers c, trade.securities s, trade.portfolio f where c.cid= f.cid and sec_id = f.sid and c.cid >? and (subtotal >10000 or (price >= ? and price <= ?));
        String query = " select * from TESTTABLE1 c, TESTTABLE2 s, TESTTABLE3 f where c.ID1= f.ID3 and s.PRICE2 = f.PRICE3 and c.ID1 > ? and (f.SUBTOTAL3 >10000 or (s.PRICE2 >= ? and s.PRICE2 <= ?))";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
              }

            }

          });

      try {
        Connection conn = getConnection();
        conn.prepareStatement(query);
      }
      catch (SQLException e) {
        e.getCause().printStackTrace();
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
  
  
  public void testBug40209() throws Exception
  {
    Connection conn = getConnection();
    conn.getMetaData().getIndexInfo(null,null, "SYS", true, true); 
  }
  
  public void testBug40220() throws Exception
  {
    Connection conn = getConnection();
    ((EmbedDatabaseMetaData) conn.getMetaData()).getClientInfoProperties(); 
  }
  
  public void testBug40503_1() throws Exception {

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int , name varchar(10), type int, address varchar(50), Primary Key (id, name ))");
    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',2, 'J 604')");    
    s.execute("Insert into  t1 values(3,'shoubhik',2, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where ( id = ? and name = ? ) OR (id = ? and name = ? ) ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isDynamic());
                try {
                  assertTrue(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setString(2,"asif" );
      ps1.setInt(3, 2);
      ps1.setString(4, "neeraj");
      assertTrue(this.callbackInvoked);
      ResultSet rs = ps1.executeQuery();    
      Set hash = new HashSet();
      hash.add(new Integer(1));
      hash.add(new Integer(2));
      while(rs.next()) {
        assertTrue(hash.remove(new Integer(rs.getInt(2))));
      }
      assertTrue("hashset is non empty", hash.isEmpty());
      
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }   
  }
  
  public void testBug40503_2() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table t1 ( id int , name varchar(10), type int, address varchar(50), Primary Key (id, name ))");
    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',2, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',2, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where ( id = 1 and name = 'asif' ) OR (id = 2 and name = 'neeraj' ) ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isDynamic()); // bcoz of statement matching
                assertFalse(sqi.isPreparedStatementQuery());
                try {
                  assertTrue(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      Set hash = new HashSet();
      hash.add(new Integer(1));
      hash.add(new Integer(2));
      while (rs.next()) {
        assertTrue(hash.remove(new Integer(rs.getInt(2))));
      }
      assertTrue(hash.isEmpty());

    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  
  public void testBug40503_3() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table t1 ( id int , name varchar(10), type int, address varchar(50), Primary Key (id, name ))");

    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',2, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',2, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type, id, name from t1 where ( id = ? OR id = ? ) AND (name = ? OR name = ? ) ";
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                assertTrue(sqi.isDynamic());
                try {
                  assertTrue(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      PreparedStatement ps1 = conn.prepareStatement(query);
      ps1.setInt(1, 1);
      ps1.setInt(2, 2);
      ps1.setString(3, "asif");
      ps1.setString(4, "neeraj");

      ps1.executeQuery();
      assertTrue(this.callbackInvoked);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  //For this query the optimizer removes the Group By Node ( possibly because of presence of primary key
  // so this query would produce Gemfire Activation. Keeping this disabled test for tracking such derby optmizer behaviours
  public void _testDebugBug40413() throws Exception {

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create function TestUDF(str varchar(100), startIndex integer, endIndex integer) returns varchar(100) parameter style java no sql language java external name 'com.pivotal.gemfirexd.functions.TestFunctions.substring'");

    s
        .execute("create table t1 ( id int , name varchar(10), type int, address varchar(50), Primary Key (id ))");
    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',2, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',2, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select substr(name,1,2) from t1 where  id = 1 ";
    //String query = "select name as xyz from t1 where  id = 1 order by name";
    GemFireXDQueryObserver old = null;
    final boolean [] createdGFEResultSet = new boolean[]{false}; 
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());  
                try {
                  assertFalse(sqi.createGFEActivation());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }             
              }
            }
            @Override
            public void createdGemFireXDResultSet(com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              createdGFEResultSet[0] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      Statement stmt = conn.createStatement();      
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
      
      query = "select  id  from t1 where  id IN (1,3)  order by id desc ";
      stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
      
      query = "select  address, id  from t1 where  id IN (1,3)  group by id, address  ";
       stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
    }

    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  
  }

  public void testDefaultOrderByBehaviourForGFEActivation() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");

    s.execute("create table t1 ( id int , name varchar(10), type int, "
        + "address varchar(50), Primary Key (id ))");
    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',2, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',2, 'J 604')");
    s.execute("Insert into  t1 values(4,'eric',2, 'J 604')");
    GemFireXDQueryObserver old = null;
    final boolean[] createdGFEResultSet = new boolean[] { false };
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                try {
                  assertTrue(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              createdGFEResultSet[0] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      Statement stmt = conn.createStatement();
//    here even though it is order by, still the order by node will be 
      // removed by the optmizer because it assumes data to be in sorted order when
      // using index to get index keys in the FromBaseTable 
      //TODO - DAN
      //The above comment is wrong. This was showing up in order because we
      //of the hash code we were using on the region key. We build a hashmap
      //of the results. However, with the new hashcode, that's not true.
      String query = "select  id  from t1 where  id IN (3,1,4,2) ";
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      HashSet<Integer> actual = new HashSet<Integer>();
      HashSet<Integer> expected= new HashSet<Integer>();
      for(int i =1; i<5;++i) {
        rs.next();
        actual.add(rs.getInt(1));
        expected.add(i);
      }
      assertEquals(actual, expected);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug40413_1() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");

    s.execute("create table t1 ( id int , name varchar(10), type int, "
        + "address varchar(50), Primary Key (id ))");
    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',2, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',2, 'J 604')");
    s.execute("Insert into  t1 values(4,'eric',2, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select substr(name,1,2) from t1 where  id IN (1,1)";
    //String query = "select name as xyz from t1 where  id = 1 order by name";
    GemFireXDQueryObserver old = null;
    final boolean [] createdGFEResultSet = new boolean[]{false}; 
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              createdGFEResultSet[0] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      Set hash = new HashSet();
      hash.add("as");
      while (rs.next()) {
        assertTrue(hash.remove(rs.getString(1)));
      }
      assertTrue(hash.isEmpty());
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
      query = "select distinct TestUDF(address,2,5)  from t1 "
          + "where id IN (1, 3)";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      assertTrue(rs.next());
      assertEquals(rs.getString(1), "604");
      assertFalse(rs.next());
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select distinct address, id  from t1 where  id IN (1,3) "
          + "order by id  asc , address desc";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select distinct address, id  from t1 where  id IN (1,3) "
          + "group by id, address   order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  distinct type, AVG(type)  from t1 where id IN (1,3) "
          + "group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  distinct type, AVG(type), MAX(type)  from t1 "
          + "where id IN (1,3) group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  address, id  from t1 where  id IN (1,3) "
          + "group by id, address  order by id";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select distinct address  from t1 where  id IN (1,3) ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  address  from t1 where  id IN (1,3)  group by address ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      // here even though it is order by, still the order by node will be
      // removed by the optmizer because it assumes data to be in sorted order
      // when using index to get index keys in the FromBaseTable
      query = "select  id  from t1 where  id IN (3,1,4,2)  order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      for (int i = 1; i < 5; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  id  from t1 where  id IN (1,3,4,2)  order by id desc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      for (int i = 4; i > 0; --i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  id  from t1 where  id IN (3,1,4,2)  order by id asc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      for (int i = 1; i < 5; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug40413_2() throws Exception {
    //System.setProperty("gemfirexd.optimizer.trace", "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");

    s.execute("create table t1 ( id int , name varchar(10), type int, "
        + "address varchar(50), Primary Key (id ))");
    // check get based query when there is a loader
    GfxdCallbacksTest.addLoader(null, "t1",
        "com.pivotal.gemfirexd.jdbc.TestRowLoader", "");

    s.execute("Insert into  t1 values(1,'asif',2, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',2, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',2, 'J 604')");
    s.execute("Insert into  t1 values(4,'eric',2, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select substr(name,1,2) from t1 where  id = 1";
    //String query = "select name as xyz from t1 where  id = 1 order by name";
    GemFireXDQueryObserver old = null;
    final boolean [] createdGFEResultSet = new boolean[]{false}; 
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              createdGFEResultSet[0] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      Set hash = new HashSet();
      hash.add("as");
      while (rs.next()) {
        assertTrue(hash.remove(rs.getString(1)));
      }
      assertTrue(hash.isEmpty());
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
      query = "select distinct TestUDF(address,2,5)  from t1 "
          + "where id IN (1, 3)";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      assertTrue(rs.next());
      assertEquals(rs.getString(1), "604");
      assertFalse(rs.next());
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select distinct address, id  from t1 where  id IN (1,3) "
          + "order by id  asc , address desc";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select distinct address, id  from t1 where  id IN (1,3) "
          + "group by id, address   order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  distinct type, AVG(type)  from t1 where id IN (1,3) "
          + "group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  distinct type, AVG(type), MAX(type)  from t1 "
          + "where id IN (1,3) group by type order by AVG(type) desc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  address, id  from t1 where  id IN (1,3) "
          + "group by id, address  order by id";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select distinct address  from t1 where  id IN (1,3) ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  address  from t1 where  id IN (1,3)  group by address ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      // here even though it is order by, still the order by node will be
      // removed by the optmizer because it assumes data to be in sorted order
      // when using index to get index keys in the FromBaseTable
      query = "select  id  from t1 where  id IN (3,1,4,2)  order by id ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      for (int i = 1; i < 5; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  id  from t1 where  id IN (1,3,4,2)  order by id desc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      for (int i = 4; i > 0; --i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;

      query = "select  id  from t1 where  id IN (3,1,4,2)  order by id asc ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      for (int i = 1; i < 5; ++i) {
        rs.next();
        assertEquals(rs.getInt(1), i);
      }

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug40413_3() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int , name varchar(10), type int, "
        + "address varchar(50), Primary Key (id ))");
    s.execute("Insert into  t1 values(1,'asif',8, 'J 601')");
    s.execute("Insert into  t1 values(2,'neeraj',9, 'J 602')");
    s.execute("Insert into  t1 values(4,'sumedh',11, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',10, 'J 603')");

    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select substr(address,4,5),substr(name,1,2) from t1 where "
        + " id  IN (1,3,4) order by name desc";
    final boolean[] createdGFEResultSet = new boolean[] { false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              createdGFEResultSet[0] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(rs.next());
      assertEquals(rs.getString(1), "04");
      assertEquals(rs.getString(2), "su");
      assertTrue(rs.next());
      assertEquals(rs.getString(1), "03");
      assertEquals(rs.getString(2), "sh");
      assertTrue(rs.next());
      assertEquals(rs.getString(1), "01");
      assertEquals(rs.getString(2), "as");
      assertFalse(rs.next());
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug40413_4() throws Exception {
    Connection conn = getConnection();
    Statement s1 = conn.createStatement();
    s1.execute("create function TestUDF(str varchar(100), startIndex integer, "
        + "endIndex integer) returns varchar(100) "
        + "parameter style java no sql language java external name "
        + "'com.pivotal.gemfirexd.functions.TestFunctions.substring'");
    Statement s = conn.createStatement();
    s.execute("create table t1 ( id int , name varchar(10), type int, "
        + "address varchar(50), Primary Key (id ))");
    s.execute("create index i1 on t1 (type)");
    s.execute("Insert into  t1 values(1,'asif',3, 'J 604')");
    s.execute("Insert into  t1 values(2,'neeraj',4, 'J 604')");
    s.execute("Insert into  t1 values(3,'shoubhik',5, 'J 604')");
    // String query = "select type, id, name from t1 where id IN (1,?,2, ?)";
    String query = "select type,TestUDF(name,1,4) from t1 where id IN (1,3) "
        + "order by name desc";
    final boolean[] createdGFEResultSet = new boolean[] { false };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.isPrimaryKeyBased());
                try {
                  assertFalse(sqi.createGFEActivation());
                } catch (Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
              }
            }

            @Override
            public void createdGemFireXDResultSet(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet rs) {
              createdGFEResultSet[0] = true;
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database. */
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      assertTrue(createdGFEResultSet[0]);
      rs.next();
      assertEquals(rs.getInt(1), 5);
      assertEquals(rs.getString(2), "hou");
      rs.next();
      assertEquals(rs.getInt(1), 3);
      assertEquals(rs.getString(2), "sif");
      assertFalse(rs.next());
      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
      query = "select distinct address from t1 where  id IN (1,3) ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      rs.next();
      assertEquals(rs.getString(1), "J 604");
      assertTrue(createdGFEResultSet[0]);
      assertFalse(rs.next());

      this.callbackInvoked = false;
      createdGFEResultSet[0] = false;
      query = "select distinct TestUDF(address,2,5)  from t1 "
          + "where id IN (1,3) ";
      rs = stmt.executeQuery(query);
      assertTrue(this.callbackInvoked);
      rs.next();
      assertEquals(rs.getString(1), "604");
      assertTrue(createdGFEResultSet[0]);
      assertFalse(rs.next());
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testBug41041() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE (ID int primary key, "
        + " TYPE int, ADDRESS varchar(1024),DESCRIPTION varchar(1024))");
    GemFireXDQueryObserver old = null;
    try {
      ResultSet rs = conn.getMetaData().getColumnPrivileges(null,
          getCurrentDefaultSchemaName(), "TESTTABLE", null);
      ResultSetMetaData rsmd = rs.getMetaData();
      int numCols = rsmd.getColumnCount();
      System.out.println("Column Names are:");
      for (int i = 1; i <= numCols; ++i) {
        System.out.print(rsmd.getColumnName(i) + "   ");
      }
      System.out.println("Data is");
      while (rs.next()) {
        for (int j = 1; j <= numCols; ++j) {
          System.out.print(rs.getObject(j) + "   ");
        }
        System.out.println();
        System.out.println();
      }
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }

  public void testCorrelatedSubQueryOnPR_PR_UNSUPPORTED() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    String derbyDbUrl = "jdbc:derby:newDB;create=true;";
    if (currentUserName != null) {
      derbyDbUrl += ("user=" + currentUserName + ";password="
          + currentUserPassword + ';');
    }
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    final Connection derbyConn = DriverManager.getConnection(derbyDbUrl);

    final Statement stmt = conn.createStatement();
    final Statement derbyStmt = derbyConn.createStatement();
    stmt.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, " +
        "ADDRESS1 varchar(1024) not null,"
        + " primary key (ID1)) PARTITION BY COLUMN ( ID1 )");
    stmt.execute("create table TESTTABLE2 (ID2 int not null,"
        + " DESCRIPTION2 varchar(1024) not null,"
        + " ADDRESS2 varchar(1024) not null, primary key (ID2))");
    derbyStmt.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, " +
        "ADDRESS1 varchar(1024) not null, primary key (ID1))");
    derbyStmt.execute("create table TESTTABLE2 (ID2 int not null,"
        + " DESCRIPTION2 varchar(1024) not null,"
        + " ADDRESS2 varchar(1024) not null, primary key (ID2))");

    final String query = "select ID1, DESCRIPTION1 from TESTTABLE1"
        + " where ID1 IN ( Select AVG(ID2) from Testtable2"
        + "   where description2 > description1)";
    try {
      try {
        stmt.execute(query);
        fail("The query should not get executed");
      } catch (SQLException snse) {
        assertEquals("0A000", snse.getSQLState());
        assertEquals("0A000.S.9", ((DerbySQLException)snse).getMessageId());
      } catch (Exception e) {
        System.out.println(e);
        fail(e.getMessage(), e);
      }

      // also check for prepared statements
      try {
        conn.prepareStatement(query);
        fail("The query should not get executed");
      } catch (SQLException snse) {
        assertEquals("0A000", snse.getSQLState());
        assertEquals("0A000.S.9", ((DerbySQLException)snse).getMessageId());
      } catch (Exception e) {
        System.out.println(e);
        fail(e.getMessage(), e);
      }

      // use the observer to execute the query against Derby and get the results
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public PreparedStatement afterQueryPrepareFailure(Connection conn,
            String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability, int autoGeneratedKeys,
            int[] columnIndexes, String[] columnNames, SQLException sqle)
            throws SQLException {
          if (sqle != null && "0A000".equals(sqle.getSQLState())) {
            return derbyConn.prepareStatement(query);
          }
          return null;
        }

        @Override
        public boolean afterQueryExecution(CallbackStatement stmt,
            SQLException sqle) throws SQLException {
          if (sqle != null && "0A000".equals(sqle.getSQLState())) {
            stmt.setResultSet(derbyStmt.executeQuery(query));
            return true;
          }
          return false;
        }
      });
      derbyStmt.execute("insert into TESTTABLE1 values (1, 'ONE', 'ADDR1')");
      derbyStmt.execute("insert into TESTTABLE1 values (2, 'TWO', 'ADDR2')");
      derbyStmt.execute("insert into TESTTABLE1 values (3, 'THREE', 'ADDR3')");
      derbyStmt.execute("insert into TESTTABLE2 values (1, 'ZONE', 'ADDR1')");
      derbyStmt.execute("insert into TESTTABLE2 values (2, 'ATWO', 'ADDR2')");
      derbyStmt.execute("insert into TESTTABLE2 values (3, 'ATHREE', 'ADDR3')");
      ResultSet rs = stmt.executeQuery(query);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("ONE", rs.getString(2));
      assertFalse(rs.next());
      rs.close();

      PreparedStatement ps = conn.prepareStatement(query);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("ONE", rs.getString(2));
      assertFalse(rs.next());
      rs.close();
      ps.close();
    } finally {
      derbyStmt.execute("drop table TESTTABLE1");
      derbyStmt.execute("drop table TESTTABLE2");
      stmt.execute("drop table TESTTABLE1");
      stmt.execute("drop table TESTTABLE2");
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }

  public void testCorrelatedSubQueryOnPR_RRSupport_SameServerGroup()
      throws Exception
  {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null," +
      " primary key (ID1)) PARTITION BY COLUMN ( ID1 )");
    s.execute("create table TESTTABLE2 (ID2 int not null,  DESCRIPTION2 varchar(1024) not null," +
    		" ADDRESS2 varchar(1024) not null, primary key (ID2)) replicate ");
    try {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              if (qInfo instanceof SelectQueryInfo) {
                SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                assertTrue(qInfo instanceof SelectQueryInfo);
                SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                assertTrue(sqi.hasCorrelatedSubQuery());
                          
              }
            }              
          });
    
      String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN " +
      		"( Select AVG(ID2) from Testtable2 where description2 > description1)";
      s.execute(query);
      assertTrue(callbackInvoked);
      
    }    
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  public void testDerbySubactivation_DerbyResultset()
  throws Exception
  {
    final String subqueryStr = "Select SUM(ID2) from Testtable2  where description2 = ? and  address2 = ?";
    final String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ("+
    subqueryStr + ")";
    
    try {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void subqueryNodeProcessedData(SelectQueryInfo sqi,
                GenericPreparedStatement gps, String subquery,
                List<Integer> paramPositions)
            {
              SelectQueryInfoInternalsTest.this.callbackInvoked = true;
              assertFalse(sqi.isPrimaryKeyBased());
              assertEquals(subquery.trim(), subqueryStr);
              assertEquals(paramPositions.size(),2);
              assertEquals(paramPositions.get(0).intValue(),0);
              assertEquals(paramPositions.get(1).intValue(),1);
              
              try {
                assertFalse(sqi.createGFEActivation());

                assertFalse(gps.getActivationClass() instanceof GemFireActivationClass);

              }
              catch (StandardException e) {
                e.printStackTrace();
                throw new GemFireXDRuntimeException(e.toString());
              }

            }
          });

      Connection conn = getConnection();
      Statement s = conn.createStatement();
      s
          .execute("create table TESTTABLE1 (ID1 int not null, "
              + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
              + "PARTITION BY COLUMN ( ID1 )");
      for (int i = 35; i < 56; ++i) {
        s.execute("Insert into  TESTTABLE1 values(" + i + ",'desc1_" + i
            + "', 'add1_" + i + "')");
      }

      s
          .execute("create table TESTTABLE2 (ID2 int not null, "
              + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null," +
              		" primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
      for (int i = 1; i < 11; ++i) {
        s.execute("Insert into  TESTTABLE2 values(" + i + ",'desc2_"
            + "', 'add2_')");
      }

     EmbedPreparedStatement eps = (EmbedPreparedStatement) ((EmbedConnection)conn).
     prepareStatementByPassQueryInfo(-1, query, true, true, false, null, 0, 0);
     eps.setString(1, "desc2_");
     eps.setString(2, "add2_");
     ResultSet rs = eps.executeQuery();
      
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 55);
      assertEquals(rs.getString(2), "desc1_55");
      assertFalse(rs.next());
      assertTrue(this.callbackInvoked);

    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  
  public void testSubqueryParametersMapping()
  throws Exception
  {
    final String subqueryStr = "Select SUM(ID2) from Testtable2  where description2 = ? and  address2 = ?";
    String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where " +
    "description1 = ? and address1 = ?  and  ID1 IN ( "+subqueryStr+" ) "; 
    try {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void subqueryNodeProcessedData(SelectQueryInfo sqi,
                GenericPreparedStatement gps, String subquery,
                List<Integer> paramPositions)
            {
              SelectQueryInfoInternalsTest.this.callbackInvoked = true;
              assertFalse(sqi.isPrimaryKeyBased());
              assertEquals(subquery.trim(),
                  subqueryStr);
             
              try {
                assertFalse(sqi.createGFEActivation());
                assertFalse(gps.getActivationClass() instanceof GemFireActivationClass);
              }
              catch (StandardException e) {
                throw new GemFireXDRuntimeException(e.toString());
              }
              assertEquals(paramPositions.size(),2);
              assertEquals(paramPositions.get(0).intValue(),2);
              assertEquals(paramPositions.get(1).intValue(),3);

            }
          });

      Connection conn = getConnection();
      Statement s = conn.createStatement();
      s
          .execute("create table TESTTABLE1 (ID1 int not null, "
              + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
              + "PARTITION BY COLUMN ( ID1 )");
      for (int i = 35; i < 56; ++i) {
        s.execute("Insert into  TESTTABLE1 values(" + i + ",'desc1_" + i
            + "', 'add1_" + i + "')");
      }

      s
          .execute("create table TESTTABLE2 (ID2 int not null, "
              + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
          "primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
      for (int i = 1; i < 11; ++i) {
        s.execute("Insert into  TESTTABLE2 values(" + i + ",'desc2_"
            + "', 'add2_')");
      }

     
     EmbedPreparedStatement eps = (EmbedPreparedStatement)((EmbedConnection)conn).
     prepareStatementByPassQueryInfo(1, query, true, true, false, null, 0, 0);
     eps.setString(3, "desc2_");
     eps.setString(4, "add2_");
     eps.setString(1, "desc1_55");
     eps.setString(2, "add1_55");
      ResultSet rs = eps.executeQuery();
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), 55);
      assertEquals(rs.getString(2), "desc1_55");
      assertFalse(rs.next());
      assertTrue(this.callbackInvoked);

    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  
  public void testSubqueryDerbyActivationDerbyResultset() throws Exception  
  {
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    final String subquery = "Select SUM(ID2) from Testtable2 where description2 = 'desc2_'";
    String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ("
      + subquery+ ")";
    
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )");
    for(int i = 35 ;i <56 ; ++i) {
      s.execute("Insert into  TESTTABLE1 values("+i+",'desc1_"+i+"', 'ADD_1"+ i+"')");
    }
    
    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
                        "primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
    for(int i = 1;i <11; ++i) {
      s.execute("Insert into  TESTTABLE2 values("+i+",'desc2_"+"', 'ADD_2"+ i+"')");
    }
    final boolean secondaryCallback[] = new boolean[] { false };
    try {
      GemFireXDQueryObserverHolder
      .setInstance(new GemFireXDQueryObserverAdapter() {  
        @Override
        public void independentSubqueryResultsetFetched(Activation activation,
            com.pivotal.gemfirexd.internal.iapi.sql.ResultSet results) {
          assertFalse(activation instanceof AbstractGemFireActivation);    
         callbackInvoked = true;
        }
        
        @Override
        public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
            GenericPreparedStatement gps,  String subqry,
            List<Integer> paramPositions)
        {
          assertEquals(subquery,subqry);
          secondaryCallback[0] = true;
        }
      });

      ResultSet rs = ((EmbedStatement)s).executeQueryByPassQueryInfo(query,
          true, true, 0, null);
      assertTrue(rs.next());
      assertEquals(rs.getInt(1),55);
      assertEquals(rs.getString(2),"desc1_55");
      assertFalse(rs.next());
      assertTrue(this.callbackInvoked);
      assertTrue(secondaryCallback[0]);
      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);      
    }
  }
  
  public void testReuseOfSubqueryResultset() throws Exception  
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    final String subquery = "Select ID2 from Testtable2 where description2 = 'desc2_'"; 
    
    String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ("
      +  subquery +" ) OR DESCRIPTION1 = 'desc1_20'";
    
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )");
    
    for (int i = 1; i < 11; ++i) {
      s.execute("Insert into  TESTTABLE1 values(" + i + ",'desc1_" + i
          + "', 'ADD_1" + i + "')");
    }

    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, "
            + "primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
    
    for (int i = 4; i < 9; ++i) {
      s.execute("Insert into  TESTTABLE2 values(" + i + ",'desc2_"
          + "', 'ADD_2" + i + "')");
    }
    final com.pivotal.gemfirexd.internal.iapi.sql.ResultSet[] rsArray = new com.pivotal.gemfirexd.internal.iapi.sql.ResultSet[1];

    try {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void independentSubqueryResultsetFetched(
                Activation activation,
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet results)
            {
              if (rsArray[0] == null) {
                rsArray[0] = results;
              }
              else {
                assertTrue(rsArray[0] == results);
              }
              callbackInvoked = true;
            }
          });

     
      EmbedResultSet rs = (EmbedResultSet) ((EmbedStatement)s).executeQueryByPassQueryInfo(query,
          true, true, 0, null);
     // ResultSet rs = ((EmbedStatement)s).executeQuery(query);
      Set<Integer> set = new HashSet<Integer>();
      for (int i = 4; i < 9; ++i) {
        set.add(Integer.valueOf(i));
      }
      while (rs.lightWeightNext()) {
        int val = rs.getInt(1);
        set.remove(Integer.valueOf(val));
        assertEquals(rs.getString(2), "desc1_" + val);
      }
      rs.lightWeightClose();
      assertTrue(set.isEmpty());
      assertTrue(this.callbackInvoked);

    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  
  public void testNonCorrelatedPR_PR_subquerySelectQueryInfoBehaviour()
  throws Exception
  {
     SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    final String subquery_c = "Select SUM(ID2) from Testtable2 where description2 like 'desc2_%'"; 
    final String subquery_p = "Select SUM(ID2) from Testtable2 where description2 like <?>";
    String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ("
      +subquery_c +")  or DESCRIPTION1 = 'desc1_55'";
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )");
    for(int i = 35 ;i <56 ; ++i) {
      s.execute("Insert into  TESTTABLE1 values("+i+",'desc1_"+i+"', 'ADD_1"+ i+"')");
    }
    
    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
            		"primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
    for(int i = 1;i <11; ++i) {
      s.execute("Insert into  TESTTABLE2 values("+i+",'desc2_"+"', 'ADD_2"+ i+"')");
    }
    try {
      GemFireXDQueryObserverHolder
      .setInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
        {

          if (qInfo instanceof SelectQueryInfo && !((DMLQueryInfo)qInfo ).isSubQuery()) {
            SelectQueryInfoInternalsTest.this.callbackInvoked = true;
            assertTrue(qInfo instanceof SelectQueryInfo);
            SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
            assertFalse(sqi.hasCorrelatedSubQuery());       
            assertEquals(sqi.getSubqueryInfoList().size(),1);
            SubQueryInfo subQi = sqi.getSubqueryInfoList().iterator().next();
            assertEquals(subQi.getSubqueryString(),subquery_p);
          }
        }
       
      });
      
      		
      ResultSet rs= ((EmbedStatement)s).executeQuery(query);
      assertTrue(rs.next());
      assertEquals(rs.getInt(1),55);
      assertEquals(rs.getString(2),"desc1_55");
      assertFalse(rs.next());
      assertTrue(this.callbackInvoked);
      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  public void testNonCorrelatedPR_PR_subqueryWithMultipleRows()
  throws Exception
  {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    final String subquery = "Select ID2 from Testtable2 where description2 = 'desc2_'";
    String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ("
      + subquery + ") or DESCRIPTION1 like 'desc1_%'";
    final boolean[] secondaryCallback = new boolean[]{false};
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )");
    for(int i = 4 ;i <9 ; ++i) {
      s.execute("Insert into  TESTTABLE1 values("+i+",'desc1_"+i+"', 'ADD_1"+ i+"')");
    }
    
    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
                        "primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
    for(int i = 1;i <11; ++i) {
      s.execute("Insert into  TESTTABLE2 values("+i+",'desc2_"+"', 'ADD_2"+ i+"')");
    }
    try {
      GemFireXDQueryObserverHolder
      .setInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void independentSubqueryResultsetFetched(Activation activation,
            com.pivotal.gemfirexd.internal.iapi.sql.ResultSet results) {
          assertFalse(activation instanceof AbstractGemFireActivation);    
         callbackInvoked = true;
        }
        
        @Override
        public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
            GenericPreparedStatement gps,  String subqry,
            List<Integer> paramPositions)
        {
          assertEquals(subquery,subqry);
          secondaryCallback[0] = true;
        }          
      });

      EmbedResultSet rs = (EmbedResultSet)((EmbedStatement)s).executeQueryByPassQueryInfo(query,
          true, true, 0, null);
      Set<Integer> set = new HashSet<Integer>();
      for(int i =4 ; i < 9;++i) {
        set.add(Integer.valueOf(i));  
      }
      while(rs.lightWeightNext()) {
        int val = rs.getInt(1);
        set.remove(Integer.valueOf(val));
        assertEquals(rs.getString(2),"desc1_"+val);
      }
      rs.lightWeightClose();
      assertTrue(set.isEmpty());
      assertTrue(this.callbackInvoked);
      assertTrue(secondaryCallback[0]);
      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  
  //Gets converted into equi join. what to do !!
  public void testNonCorrelatedPR_PR_subqueryWithMultipleRowsAnyClause()
  throws Exception
  {
     
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    final String subquery = "Select  TEMP2 from Testtable2 where description2 = 'desc2_'";
    String query ="select TEMP1, DESCRIPTION1 from TESTTABLE1 where TEMP1  >= ANY (" +
     subquery + ") OR ADDRESS1 like 'MDD_1%'";
    final boolean[] secondaryCallback = new boolean[]{false};
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, " +
            		"TEMP1 int , primary key (ID1))PARTITION BY COLUMN ( ID1 )");
    for(int i = -500; i <11 ; ++i) {
      s.execute("Insert into  TESTTABLE1 values("+i+",'desc1_"+i+"', 'ADD_1"+ i+"',"+i+")");
    }
    
    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
            		"TEMP2 int, primary key (ID2)) partition by column( ID2) " +
            		" colocate with (TESTTABLE1) ");
    String createFunction="CREATE FUNCTION max1 " +
    "( v1 integer, v2 integer) " +
    "RETURNS int "+
    "LANGUAGE JAVA " +
    "EXTERNAL NAME " +
    "'com.pivotal.gemfirexd.jdbc.BugsTest.maxFunc(java.lang.Integer,java.lang.Integer)'" +
    " PARAMETER STYLE JAVA " +
    "NO SQL " ;
    s.execute(createFunction);
    for(int i = 10;i <20; ++i) {
      s.execute("Insert into  TESTTABLE2 values("+i+",'desc2_"+"', 'ADD_2"+ i+"',"+i+")");
    }
    try {
      GemFireXDQueryObserverHolder
      .setInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void independentSubqueryResultsetFetched(Activation activation,
            com.pivotal.gemfirexd.internal.iapi.sql.ResultSet results) {
          assertFalse(activation instanceof AbstractGemFireActivation);    
         callbackInvoked = true;
        }
        
        @Override
        public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
            GenericPreparedStatement gps,  String subqry,
            List<Integer> paramPositions)
        {
          assertEquals(subquery,subqry);
          secondaryCallback[0] = true;
        }            
      });

      EmbedResultSet rs = (EmbedResultSet) ((EmbedStatement)s).executeQueryByPassQueryInfo(query,
          true, true, 0, null);
      assertTrue(rs.lightWeightNext());
      int val = rs.getInt(1);
      assertEquals(val,10);      
      assertEquals(rs.getString(2),"desc1_"+val);      
      assertFalse(rs.lightWeightNext());
      rs.lightWeightClose();
      assertTrue(this.callbackInvoked);
      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  public void testNonCorrelatedPR_PR_subqueryWithMultipleRowsExistsClause()
  throws Exception
  {
    final String subquery = "Select  ID2 from Testtable2 where description2 = 'desc2_'";
    String query ="select ID1, DESCRIPTION1 from TESTTABLE1 where exists (" + subquery +
    " ) OR  ADDRESS1 like 'ADD_1%'";
    final boolean[] secondaryCallback = new boolean[]{false};
     
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, " +
                        "TEMP1 int , primary key (ID1))PARTITION BY COLUMN ( ID1 )");
    for(int i = 1; i <11 ; ++i) {
      s.execute("Insert into  TESTTABLE1 values("+i+",'desc1_"+i+"', 'ADD_1"+ i+"',"+i+")");
    }
    
    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
                        "TEMP2 int, primary key (ID2)) partition by column( ID2) " +
                        " colocate with (TESTTABLE1) ");
    String createFunction="CREATE FUNCTION max1 " +
    "( v1 integer, v2 integer) " +
    "RETURNS int "+
    "LANGUAGE JAVA " +
    "EXTERNAL NAME " +
    "'com.pivotal.gemfirexd.jdbc.BugsTest.maxFunc(java.lang.Integer,java.lang.Integer)'" +
    " PARAMETER STYLE JAVA " +
    "NO SQL " ;
    s.execute(createFunction);
    for(int i = 10;i <20; ++i) {
      s.execute("Insert into  TESTTABLE2 values("+i+",'desc2_"+"', 'ADD_2"+ i+"',"+i+")");
    }
    try {
      GemFireXDQueryObserverHolder
      .setInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void independentSubqueryResultsetFetched(Activation activation,
            com.pivotal.gemfirexd.internal.iapi.sql.ResultSet results) {
          assertFalse(activation instanceof AbstractGemFireActivation);    
         callbackInvoked = true;
        }
        
        @Override
        public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
            GenericPreparedStatement gps,  String subqry,
            List<Integer> paramPositions)
        {
          assertEquals(subquery,subqry);
          secondaryCallback[0] = true;
        }               
      });

      //ResultSet rs = ((EmbedStatement)s).executeQueryByPassQueryInfo(query,
      //    true, true, 0, null);
      EmbedResultSet rs = (EmbedResultSet)((EmbedStatement)s)
    		            .executeQueryByPassQueryInfo(query, true, true, 0, null);
      //ResultSet rs= ((EmbedStatement)s).executeQuery(query);
      Set<Integer> set = new HashSet<Integer>();
      for(int i = 1; i < 11;++i) {
        set.add(Integer.valueOf(i));  
      }
      while(rs.lightWeightNext()) {
        int val = rs.getInt(1);
        set.remove(Integer.valueOf(val));
        assertEquals(rs.getString(2),"desc1_"+val);
      }
      
      assertTrue(set.isEmpty());
      assertFalse(rs.lightWeightNext());
      assertTrue(this.callbackInvoked);
      rs.lightWeightClose();
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }  
 
  public void _testNonCorrelatedPR_PR_subquery()
  throws Exception
  {

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )");
    for(int i = 35 ;i <56 ; ++i) {
      s.execute("Insert into  TESTTABLE1 values("+i+",'desc1_"+i+"', 'ADD_1"+ i+"')");
    }
    
    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
                        "primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
    for(int i = 1;i <11; ++i) {
      s.execute("Insert into  TESTTABLE2 values("+i+",'desc2_"+"', 'ADD_2"+ i+"')");
    }
    
    try {

      String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN "
          + "( Select SUM(ID2) from Testtable2 where description2 = 'desc2_')";
      ResultSet rs = ((EmbedStatement)s).executeQueryByPassQueryInfo(query,
          true, true, 0, null);
      assertTrue(rs.next());
      assertEquals(rs.getInt(1),55);
      assertEquals(rs.getString(2),"desc1_55");
      assertFalse(rs.next());
      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  
  public void testCorrelatedSubQueryDetection() throws Exception
  {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, " +
            		"primary key (ID1)) PARTITION BY COLUMN ( ID1 )");
    s
        .execute("create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null," +
           " primary key (ID2)) partition by column( ID2)  colocate with (TESTTABLE1) ");
    
      try {
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
              @Override
              public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
              {
                if (qInfo instanceof SelectQueryInfo) {
                  SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                  assertTrue(qInfo instanceof SelectQueryInfo);
                  SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                  assertTrue(sqi.hasCorrelatedSubQuery());
                            
                }
              }              
            });
      String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 " +
      		"IN ( Select AVG(ID2) from Testtable2 where ID2 > ID1)";
      try {
        conn.prepareStatement(query);
        fail("query not supported , should have  thrown exception");
      } catch (SQLException snse) {
        assertEquals(snse.getSQLState(), "0A000");
        assertEquals(((DerbySQLException)snse).getMessageId(), "0A000.S.10");
      }
    }    
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }
  }
  
  public void testBug41617_1_single_column_global_index_pruning_IN() throws Exception {   
        System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
        Connection conn = getConnection();
        Statement s = conn.createStatement();
        s.execute("create table sellorders (oid int not null constraint orders_pk primary key" +
       	", cid int, sid int, tid int) partition by column (cid )");

        String query = "select * from  sellorders where oid IN (1, 2, 3, 4, 5) and tid < 41 ";
        String insertquery = "insert into sellorders values(?,?,?,?)";
        PreparedStatement ps = conn.prepareStatement(insertquery);
        // Insert values 1 to 7
        for (int i = 1; i < 11; ++i) {
          ps.setInt(1, i);
          ps.setInt(2, 2*i);
          ps.setInt(3, 3*i);
          ps.setInt(4, 4*i);
          ps.executeUpdate();
          
        }
        final LanguageConnectionContext lcc = ((EmbedConnection)conn)
            .getLanguageConnection();
        GemFireXDQueryObserver old = null;
        try {
          old = GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
                {
                  if (qInfo instanceof SelectQueryInfo) {
                    SelectQueryInfoInternalsTest.this.callbackInvoked = true;
                    assertTrue(qInfo instanceof SelectQueryInfo);
                    SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
                    Set<Object> actualRoutingKeys = new HashSet<Object>();
                    actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
                    try {
                      Activation act = new GemFireSelectActivation(gps, lcc,
                          (DMLQueryInfo)qInfo, null, false);
                      sqi.computeNodes(actualRoutingKeys, act, false);
                      //Externally compute the routing objects.
                      Set <Object> externallyCmputed = new HashSet<Object>();
                      
                      GfxdPartitionResolver spr = (GfxdPartitionResolver)((PartitionedRegion)sqi
                          .getRegion()).getPartitionResolver();
                      Object [] routingObjs = spr.
                      getRoutingObjectsForList(new DataValueDescriptor[] {
                          new SQLInteger(2) , new SQLInteger(4), new SQLInteger(6),
                          new SQLInteger(8),new SQLInteger(10)});
                      
                      for(Object obj :routingObjs) {
                        externallyCmputed.add(obj);
                      }
                                               
                      assertFalse(actualRoutingKeys
                          .contains(ResolverUtils.TOK_ALL_NODES));
                      assertEquals(externallyCmputed.size(),actualRoutingKeys.size());
                      assertTrue(externallyCmputed.removeAll(actualRoutingKeys));
                      assertTrue(externallyCmputed.isEmpty());
                    }
                    catch (Exception e) {
                      e.printStackTrace();
                      fail("test failed because of exception=" + e.toString());
                    }
                  }

                }

              });

          s.executeQuery(query);
          assertTrue(this.callbackInvoked);
        }
        finally {
          if (old != null) {
            GemFireXDQueryObserverHolder.setInstance(old);
          }
          System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
        }
      
  }

  public void testNonCorrelated_PR_PR_44206() throws Exception {

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, "
        + "ADDRESS1 varchar(1024) not null, primary key (ID1))"
        + "PARTITION BY COLUMN ( ID1 )");
    s.execute("create table TESTTABLE2 (ID2 int not null, "
        + " DESCRIPTION2 varchar(1024) not null, "
        + "ADDRESS2 varchar(1024) not null, primary key (ID2)) "
        + "partition by column( ID2)  colocate with (TESTTABLE1)");
    s.execute("insert into TESTTABLE1 values (1, 'Desc1', 'Addr1')");
    s.execute("insert into TESTTABLE1 values (2, 'Desc2', 'Addr2')");
    s.execute("insert into TESTTABLE2 values (2, 'Desc22', 'Addr22')");

    try {
      String prepQuery = "select ID1, DESCRIPTION1 from TESTTABLE1 alias1 "
          + "where alias1.ID1 IN ( Select AVG(alias2.ID2) from "
          + "Testtable2 alias2 where alias2.ID2 > ? ) AND 5 = ?";
      PreparedStatement ps = conn.prepareStatement(prepQuery);
      ps.setInt(1, 1);
      ps.setInt(2, 7);
      ResultSet rs = ps.executeQuery();
      assertFalse(rs.next());
      ps.setInt(2, 5);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertFalse(rs.next());

      String query = "select ID1, DESCRIPTION1 from TESTTABLE1 alias1 "
          + "where alias1.ID1 IN ( Select AVG(alias2.ID2) from "
          + "Testtable2 alias2 where alias2.ID2 > 222 ) AND 5 = 7";
      rs = s.executeQuery(query);
      assertFalse(rs.next());

      query = "select ID1, DESCRIPTION1 from TESTTABLE1 alias1 "
          + "where alias1.ID1 > 0 AND alias1.ID1 IN ( Select AVG(alias2.ID2) "
          + "from Testtable2 alias2 where alias2.ID2 > 1 ) AND 5 = 5";
      rs = s.executeQuery(query);
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertFalse(rs.next());

      ps = conn.prepareStatement(query);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertFalse(rs.next());
    } finally {
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    }
  }

  public void testBug43663() throws Exception {

    String query=" SELECT *   FROM   T1 LEFT JOIN T2 ON (T2.ID2 = 10024) AND " +
    "(T2.ID1 = T1.ID1)  INNER JOIN T3 ON (T3.ID2 = ?)   WHERE  " +
    "(T3.ID1 = T1.ID1)    AND  (T1.ID2 = ?) AND   (T1.ID3 != ?) AND" +
    " ( ((lower(T1.name) LIKE ? OR ? IS NULL)) AND   (((lower(T2.street1) LIKE ? OR ? IS NULL)) OR" +
    "((lower(T2.street2) LIKE ? OR ? IS NULL)) OR" +
    "((lower(T2.street3) LIKE ? OR ? IS NULL)) ) AND" +
    "((lower(T2.city) LIKE ? OR ? IS NULL)) AND" +
    "((lower(T2.zip) LIKE ? OR ? IS NULL))  )       ORDER BY   T1.ID1 ASC fetch first 10 rows only";

    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table T1 (ID1 int not null, ID2 int not null, "
            + " name varchar(1024) not null, ID3 int not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )");    
    s
        .execute("create table T2 (ID1 int not null, ID2 int not null, "
            + " name varchar(1024) not null, street1 varchar(1024) not null, street2 varchar(1024) not null," +
            " street3 varchar(1024) not null, city varchar(1024) not null," +
            "zip varchar(1024) not null,primary key (ID1)) partition by column( ID1) " +
            " colocate with (T1) ");
    
    s
    .execute("create table T3 (ID1 int not null, ID2 int not null ," +
    		"primary key (ID1)) partition by column( ID1) " +
        " colocate with (T1) ");
    try {      
      conn.prepareStatement(query);
            
    }
    catch (SQLException sqle) {
      sqle.printStackTrace();
      fail(sqle.toString());

    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
    }

  }
  
  public void testBug43666_1() throws Exception {
    String query = "SELECT COUNT(DISTINCT articleId) AS COUNT_VALUE FROM JournalArticle WHERE "
        + "(companyId = ?) AND (groupId = ?) AND ( ((articleId LIKE ? OR ? IS NULL)) AND  "
        + "((lower(title) LIKE ? OR ? IS NULL)) AND ((description LIKE ? OR ? IS NULL)) "
        + "AND ((content LIKE ? OR ? IS NULL)) )";
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table JournalArticle (companyId int not null, groupId int not null, "
            + " articleId varchar(1024) not null, title varchar(1024) not null, description varchar(1024) not null ,"
            + "content varchar(1024) not null, type_ varchar(1024) ,structureId int not null,"
            + "templateId int not null, displayDate varchar(1024) not null,status varchar(1024) not null,"
            + " reviewDate varchar(1024) not null,primary key (companyId))");
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private final int index = 0;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              SelectQueryInfoInternalsTest.this.callbackInvoked = true;

              assertTrue(qInfo instanceof SelectQueryInfo);
              SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
              AbstractConditionQueryInfo acqi = sqi.getWhereClause();
              assertTrue(acqi instanceof JunctionQueryInfo);
              assertEquals(((JunctionQueryInfo)acqi).getJunctionType(),
                  QueryInfoConstants.AND_JUNCTION);
              assertTrue(acqi instanceof AndJunctionQueryInfo);
              JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;

              List ops = jqi.getOperands();
              assertEquals(ops.size(), 2);
              AbstractConditionQueryInfo acqi1 = (AbstractConditionQueryInfo)ops
                  .get(0);
              AbstractConditionQueryInfo acqi2 = (AbstractConditionQueryInfo)ops
                  .get(1);
              assertTrue(acqi1 instanceof ComparisonQueryInfo);
              assertTrue(acqi2 instanceof ComparisonQueryInfo);
            }

          });
      conn.prepareStatement(query);
      assertTrue(SelectQueryInfoInternalsTest.this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }
  }

  public void testBug43666_2() throws Exception {
    String query = "SELECT COUNT(DISTINCT articleId) AS COUNT_VALUE FROM JournalArticle WHERE "
        + "(companyId = ?) AND (groupId = ?) AND ( ((articleId > ? OR groupId > ?)) AND  "
        + "((title < ? OR title != ?)) AND ((description =? OR description = ?)) "
        + "AND ((content = ? OR content = ?)) )";
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table JournalArticle (companyId int not null, groupId int not null, "
            + " articleId varchar(1024) not null, title varchar(1024) not null, description varchar(1024) not null ,"
            + "content varchar(1024) not null, type_ varchar(1024) ,structureId int not null,"
            + "templateId int not null, displayDate varchar(1024) not null,status varchar(1024) not null,"
            + " reviewDate varchar(1024) not null,primary key (companyId))");
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private final int index = 0;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              SelectQueryInfoInternalsTest.this.callbackInvoked = true;

              assertTrue(qInfo instanceof SelectQueryInfo);
              SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
              AbstractConditionQueryInfo acqi = sqi.getWhereClause();
              assertTrue(acqi instanceof JunctionQueryInfo);
              assertEquals(((JunctionQueryInfo)acqi).getJunctionType(),
                  QueryInfoConstants.AND_JUNCTION);
              assertTrue(acqi instanceof AndJunctionQueryInfo);
              JunctionQueryInfo jqi = (JunctionQueryInfo)acqi;

              List ops = jqi.getOperands();
              assertEquals(4,ops.size());
              int numCQI =0,numIQI=0;
              for(Iterator iterator = ops.iterator();iterator.hasNext();) {
                AbstractConditionQueryInfo temp = (AbstractConditionQueryInfo)iterator.next();
                if(temp instanceof InQueryInfo) {
                  ++numIQI;
                }else if( temp instanceof ComparisonQueryInfo) {
                  ++numCQI;
                }
              }
              assertEquals(2,numCQI);
              assertEquals(2,numIQI);
              assertEquals(2, jqi.children.length);
              for(JunctionQueryInfo temp:jqi.children) {
                assertEquals(QueryInfoConstants.OR_JUNCTION,temp.getJunctionType());
              }
            }

          });
      conn.prepareStatement(query);
      assertTrue(SelectQueryInfoInternalsTest.this.callbackInvoked);
    } finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
      System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
    }

  }
  
  public void testBug42623() throws Exception {

    String query = "SELECT tab_b.Id FROM tab_b JOIN tab_c ON (tab_b.OId = tab_c.PAId "
        + "OR tab_b.OId = tab_c.PBId) LEFT OUTER JOIN tab_a ON tab_b.OId = PId WHERE EXISTS"
        + "(SELECT 'X' FROM tab_d  WHERE (PAId = 141 AND PBId = tab_b.Id) OR "
        + "(PBId = 141 AND PAId = tab_b.Id) )  AND EXISTS ( SELECT 'X' FROM tab_v "
        + " WHERE OId = tab_b.OId AND UGId = 31 AND val = 'A')";
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("CREATE TABLE tab_b (Id BIGINT NOT NULL PRIMARY KEY, "
        + "OId BIGINT NOT NULL)");
    s.execute("CREATE TABLE tab_c (Id BIGINT NOT NULL PRIMARY KEY,"
        + "PAId BIGINT NOT NULL, PBId BIGINT NOT NULL) ");

    s.execute("CREATE TABLE tab_a (PId BIGINT NOT NULL) ");

    s.execute("CREATE TABLE tab_d (Id BIGINT NOT NULL PRIMARY KEY, PAId BIGINT NOT NULL,"
            + " PBId BIGINT NOT NULL)");
    s.execute("CREATE TABLE tab_v (OId BIGINT NOT NULL, UGId BIGINT NOT NULL, " +
    		"val CHAR(1) NOT NULL)");
    
    Statement st = conn.createStatement();
    st.executeQuery(query);

  }
  
  public void testQueryInfoStructureForBug42673() throws Exception
 {

    String query = " select * from securities where sec_id IN (select sid from "
        + "portfolio f where cid = (select c.cid from customers c where "
        + " c.cid = f.cid))";

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table customers (cid int not null, cust_name varchar(100), "
            + " addr varchar(100), tid int, primary key (cid))");

    s
        .execute("create table securities (sec_id int not null, symbol varchar(10) not null,"
            + " exchange varchar(10) not null, price decimal(30,20),tid int, constraint sec_pk primary key (sec_id), "
            + "constraint sec_uq unique (symbol, exchange), "
            + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
            + "  replicate");

    s
        .execute("create table portfolio (cid int not null, sid int not null,"
            + " qty int not null, availQty int not null, tid int, constraint portf_pk primary key (cid, sid),"
            + " constraint cust_fk foreign key (cid) references customers (cid) on delete restrict, "
            + "constraint sec_fk foreign key (sid) references securities (sec_id), constraint qty_ck check (qty>=0), "
            + "constraint avail_ch check (availQty>=0 and availQty<=qty)) "
            + " partition by column (cid) colocate with (customers)");

    s
        .execute("create table sellorders (oid int not null constraint orders_pk primary key,"
            + " cid int, sid int, qty int, status varchar(10) default 'open', tid int,"
            + " constraint portf_fk foreign key (cid, sid) references portfolio (cid, sid) on "
            + "delete restrict, constraint status_ch check (status in ('cancelled', 'open', 'filled'))) "
            + " partition by column (cid) colocate with (customers)");

    try {
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
            GenericPreparedStatement gps, LanguageConnectionContext lcc) {

          if (qInfo instanceof SelectQueryInfo && !((DMLQueryInfo)qInfo ).isSubQuery()) {
            SelectQueryInfoInternalsTest.this.callbackInvoked = true;
            assertTrue(qInfo instanceof SelectQueryInfo);
            SelectQueryInfo sqi = (SelectQueryInfo)qInfo;
            assertFalse(sqi.hasCorrelatedSubQuery());
            assertEquals(sqi.getSubqueryInfoList().size(), 1);
            assertTrue(sqi.isRemoteGfxdSubActivationNeeded());            

          }
        }

      });

      conn.prepareStatement(query);

      assertTrue(this.callbackInvoked);

    } finally {
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
      s.execute("drop table sellorders");
      s.execute("drop table portfolio");
      s.execute("drop table customers");
      s.execute("drop table securities");
    }

  }

  /**
   * For testing miscellaneous conditions during debugging
   * @throws Exception
   */
  public void _testDebug1() throws Exception
  {
    Connection conn = getConnection();
    /*s.execute("create table TESTTABLE (ID int primary key, "
        + " TYPE int, ADDRESS varchar(1024),DESCRIPTION varchar(1024)  )");*/
    GemFireXDQueryObserver old = null;
    try {      
      //conn.getMetaData().getIndexInfo(null,null, "SYS", true, true);
      java.sql.DatabaseMetaData dbmd =conn.getMetaData();
      ResultSet rs = dbmd.getCatalogs();
      while(rs.next()) {
        System.out.println("..");
      }
      
      rs = dbmd.getSchemas();
      while(rs.next()) {
        System.out.println("..");
      }
      
      rs = dbmd.getProcedures(null,null, null);
      while(rs.next()) {
        System.out.println("..");
      }
      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  }
  /**
   * For testing miscellaneous conditions during debugging
   * @throws Exception
   */
  public void _testDebug2() throws Exception
  {
    Connection conn = getConnection();

    String query = "EXECUTE STATEMENT SYS.\"getCrossReference\"";
    /*SELECT CAST ('' AS VARCHAR(128)) AS PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME, PKCOLUMN_NAME, CAST ('' AS VARCHAR(128)) AS FKTABLE_CAT, S2.SCHEMANAME AS FKTABLE_SCHEM, T2.TABLENAME AS FKTABLE_NAME, COLS2.COLUMNNAME AS FKCOLUMN_NAME, CAST (CONGLOMS2.DESCRIPTOR.getKeyColumnPosition( COLS2.COLUMNNUMBER) AS SMALLINT) AS KEY_SEQ, CAST ((CASE WHEN F2.UPDATERULE='S' THEN java.sql.DatabaseMetaData::importedKeyRestrict ELSE  (CASE WHEN F2.UPDATERULE='R' THEN java.sql.DatabaseMetaData::importedKeyNoAction ELSE java.sql.DatabaseMetaData::importedKeyNoAction END) END)  AS SMALLINT) AS UPDATE_RULE, CAST ((CASE WHEN F2.DELETERULE='S' THEN java.sql.DatabaseMetaData::importedKeyRestrict ELSE  (CASE WHEN F2.DELETERULE='R' THEN java.sql.DatabaseMetaData::importedKeyNoAction ELSE (CASE WHEN F2.DELETERULE='C' THEN java.sql.DatabaseMetaData::importedKeyCascade ELSE (CASE WHEN F2.DELETERULE='U' THEN java.sql.DatabaseMetaData::importedKeySetNull ELSE java.sql.DatabaseMetaData::importedKeyNoAction END)END)ENd)END)  AS SMALLINT) AS DELETE_RULE, C2.CONSTRAINTNAME AS FK_NAME, PK_NAME, CAST (java.sql.DatabaseMetaData::importedKeyNotDeferrable AS SMALLINT) AS DEFERRABILITY FROM --GEMFIREXD-PROPERTIES joinOrder=FIXED 
    (SELECT C.CONSTRAINTID AS PK_ID, CONSTRAINTNAME AS PK_NAME, PKTB_SCHEMA AS PKTABLE_SCHEM,  PKTB_NAME AS PKTABLE_NAME, COLS.COLUMNNAME AS PKCOLUMN_NAME, CONGLOMS.DESCRIPTOR.getKeyColumnPosition( COLS.COLUMNNUMBER) AS KEY_SEQ FROM --GEMFIREXD-PROPERTIES joinOrder=FIXED 
    (SELECT T.TABLEID AS PKTB_ID, S.SCHEMANAME AS PKTB_SCHEMA, T.TABLENAME AS PKTB_NAME FROM  SYS.SYSTABLES t --GEMFIREXD-PROPERTIES index = 'SYSTABLES_INDEX1' 
    , SYS.SYSSCHEMAS s --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSSCHEMAS_INDEX1' 
    WHERE ((1=1) OR ? IS NOT NULL) AND S.SCHEMANAME LIKE ? AND T.TABLENAME=? AND S.SCHEMAID = T.SCHEMAID ) AS PKTB (PKTB_ID, PKTB_SCHEMA, PKTB_NAME), SYS.SYSCONSTRAINTS C --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSCONSTRAINTS_INDEX3' 
    , SYS.SYSKEYS K --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSKEYS_INDEX1' 
    , SYS.SYSCONGLOMERATES CONGLOMS --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSCONGLOMERATES_INDEX1' 
    , SYS.SYSCOLUMNS COLS --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index =  'SYSCOLUMNS_INDEX1' 
    WHERE  PKTB.PKTB_ID = C.TABLEID AND K.CONSTRAINTID = C.CONSTRAINTID  AND PKTB.PKTB_ID = COLS.REFERENCEID AND (CASE WHEN CONGLOMS.DESCRIPTOR IS NOT NULL THEN CONGLOMS.DESCRIPTOR.getKeyColumnPosition( COLS.COLUMNNUMBER) ELSE 0 END) <> 0 AND K.CONGLOMERATEID = CONGLOMS.CONGLOMERATEID ) AS PKINFO(PK_ID, PK_NAME, PKTABLE_SCHEM, PKTABLE_NAME, PKCOLUMN_NAME, KEY_SEQ), SYS.SYSFOREIGNKEYS F2 --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSFOREIGNKEYS_INDEX1' 
    , SYS.SYSCONSTRAINTS c2 --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSCONSTRAINTS_INDEX1'  
    , SYS.SYSTABLES T2 --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSTABLES_INDEX2'  
    , SYS.SYSSCHEMAS S2 --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSSCHEMAS_INDEX2' 
    , SYS.SYSCONGLOMERATES CONGLOMS2 --GEMFIREXD-PROPERTIES joinStrategy = NESTEDLOOP, index = 'SYSCONGLOMERATES_INDEX1'  
    , SYS.SYSCOLUMNS COLS2 --GEMFIREXD-PROPERTIES joinStrategy=NESTEDLOOP, index = 'SYSCOLUMNS_INDEX1' 
    WHERE  F2.keyCONSTRAINTID = PKINFO.PK_ID AND PKINFO.KEY_SEQ = CONGLOMS2.DESCRIPTOR.getKeyColumnPosition(  COLS2.COLUMNNUMBER)  AND T2.TABLEID = C2.TABLEID AND ((1=1) OR ? IS NOT NULL) AND S2.SCHEMANAME LIKE ? AND T2.TABLENAME LIKE ? AND S2.SCHEMAID = T2.SCHEMAID  AND F2.CONSTRAINTID = C2.CONSTRAINTID  AND (CASE WHEN CONGLOMS2.DESCRIPTOR IS NOT NULL THEN CONGLOMS2.DESCRIPTOR.getKeyColumnPosition(COLS2.COLUMNNUMBER) ELSE 0 END) <> 0 AND F2.CONGLOMERATEID = CONGLOMS2.CONGLOMERATEID AND C2.TABLEID = COLS2.REFERENCEID ORDER BY FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, FK_NAME, KEY_SEQ */
    GemFireXDQueryObserver old = null;
    try {      
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String types[] = new String[]{"TABLE"};
      conn.getMetaData().getTables(null, null, null, types);
      conn.prepareStatement(query);
      
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }
  } 
  
  // Add further checks here
  private boolean validateComparisonQueryInfo(ComparisonQueryInfo cqi, int indx) {
    boolean ok = false;
    switch (indx) {
    default:
      ok = true;
    }
    return ok;
  }

  // Add further checks here
  private boolean validateJunctionQueryInfo(JunctionQueryInfo jqi, int indx) {
    boolean ok = false;
    switch (indx) {
    case 2:
      assertEquals(jqi.getJunctionType(), QueryInfoConstants.AND_JUNCTION);
      assertTrue(jqi instanceof AndJunctionQueryInfo);
      List ops = jqi.getOperands();
      assertEquals(ops.size(), 3);
      AbstractConditionQueryInfo acqi1 = (AbstractConditionQueryInfo)ops.get(0);
      AbstractConditionQueryInfo acqi2 = (AbstractConditionQueryInfo)ops.get(1);
      AbstractConditionQueryInfo acqi3 = (AbstractConditionQueryInfo)ops.get(2);
      assertTrue(acqi1 instanceof ComparisonQueryInfo);
      assertTrue(acqi2 instanceof ComparisonQueryInfo);
      assertTrue(acqi3 instanceof ComparisonQueryInfo);
      ok = true;
      break;    
    default:
      ok = true;
    }
    return ok;
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
        + "(id int not null , cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100))");
  /*  String types[] = new String[1];
    types[0] = "TABLE";    

    conn.getMetaData().getTables(null, null, null, types);*/
    s.close();

  }
  
  public static void createTableWithThreeCompositeKey(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    // We create a table...TestUDF
    s.execute("create table orders"
        + "(id int , cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100)," +
                        " Primary Key (id, cust_name,vol))");
  
    s.close();

  }

  @Override
  public void tearDown() throws Exception {
    this.callbackInvoked = false;
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
    super.tearDown();
  }
}
