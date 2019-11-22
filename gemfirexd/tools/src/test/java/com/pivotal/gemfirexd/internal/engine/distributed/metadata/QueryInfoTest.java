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
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DynamicKey;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.JunctionQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.*;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

/**
 * @author Asif
 * 
 */
@SuppressWarnings("serial")
public class QueryInfoTest extends JdbcTestBase {

  private boolean callbackInvoked = false;

  public QueryInfoTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(QueryInfoTest.class));
  }

  public void testConvertibleToGet_1() throws SQLException, StandardException {
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);

    String[] queries = new String[] {
        "Select * from orders where id =8",
        "Select id, cust_name, vol, security_id, num, addr from orders where id =8",
        "Select  id, cust_name, vol, security_id, num, addr  from orders where id > 8",
        "Select id as id, cust_name as cust_name, vol, security_id, num, addr from orders where id =8",

    /* "Select * from orders where id = 8 and cust_name = 'asif'" */};
    final boolean[] getConvertibles = new boolean[] { true, true, false, true /* false */};
    RegionKey gfk = getGemFireKey(8,
        Misc.getRegionForTable(getCurrentDefaultSchemaName() + ".ORDERS", true));

    final Object[] primaryKeys = new Object[] { gfk, gfk,
        null, gfk /* false */};
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;
            private DynamicKey dk = null;
            private QueryInfo qi;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              QueryInfoTest.this.callbackInvoked = true;
              try {
                assertTrue(qInfo instanceof SelectQueryInfo);
                assertTrue(qInfo.isPrimaryKeyBased() == getConvertibles[index]);
                try {
                  assertTrue(qInfo.createGFEActivation()== qInfo.isPrimaryKeyBased());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
                dk = (DynamicKey)qInfo.getPrimaryKey();
                assertNotNull(qInfo.getRegion());
                qi = qInfo;
              }
              catch (StandardException se) {
                fail("Test failed becaus eof exception=" + se);
              }
            }
            
            @Override
            public void beforeQueryExecution(
                EmbedStatement stmt, Activation activation) {
                try {
                  if(primaryKeys[index] == null) {
                    assertTrue(dk == null);
                  }
                  else {
                  validatePrimaryKey(dk.getEvaluatedPrimaryKey(activation,
                      ((GemFireContainer)qi.getRegion().getUserAttribute()),
                      false), primaryKeys[index]);
                  }
                  ++index;
                } catch (StandardException e) {
                  fail("Unexpected exception while validating key " + e);
                }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement s = conn.createStatement();
      for (int i = 0; i < queries.length; ++i) {
        try {
          s.executeQuery(queries[i]);
        } catch (SQLException e) {
          throw new SQLException(e.toString()
              + " Exception in executing query = " + queries[i], e);
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

  public void testPreparedStatementConvertibleToGet_1() throws SQLException,
      StandardException {
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);
    final boolean[] usedGemFireXDActivation = new boolean[] { false, false,
        false, false };
    String[] queries = new String[] {
        "Select * from orders where id =?",
        "Select id, cust_name, vol, security_id, num, addr from orders where id =?",
        "Select  id, cust_name, vol, security_id, num, addr  from orders where id > ?",
        "Select id as id, cust_name as cust_name, vol, security_id, num, addr from orders where id =?",

    /* "Select * from orders where id = 8 and cust_name = 'asif'" */};
    final boolean[] getConvertibles = new boolean[] { true, true, false, true /* false */};
    // Rahul: Changes after single dvd key was implemented for region key.
    //RegionKey gfk = new CompositeRegionKey( new DataValueDescriptor[]{new SQLInteger(8)});
    RegionKey gfk = getGemFireKey(8,
        Misc.getRegionForTable(getCurrentDefaultSchemaName() + ".ORDERS", true));

    final Object[] primaryKeys = new Object[] { gfk, gfk,
        null, gfk /* false */};
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            private DynamicKey dk = null;
            private QueryInfo qi;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              QueryInfoTest.this.callbackInvoked = true;
              try {
                assertTrue(qInfo instanceof SelectQueryInfo);
                assertTrue(qInfo.isPrimaryKeyBased() == getConvertibles[index]);
                if (getConvertibles[index]) {
                  assertTrue(qInfo.getPrimaryKey() instanceof DynamicKey);
                  dk = (DynamicKey)qInfo.getPrimaryKey();
                }
                else {
                  dk = null;
                }
                assertNotNull(qInfo.getRegion());
                ++index;
                qi = qInfo;
              }
              catch (StandardException se) {
                fail("Test failed becaus eof exception=" + se);
              }
            }

            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation) {
              usedGemFireXDActivation[index - 1] = true;
              try {
                assertEquals(primaryKeys[index - 1], dk.getEvaluatedPrimaryKey(
                    activation, ((GemFireContainer)qi.getRegion()
                        .getUserAttribute()), false));
              }
              catch (StandardException se) {
                fail("Test failed because of exception=" + se);
              }
            }

          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      for (int i = 0; i < queries.length; ++i) {
        PreparedStatement s = conn.prepareStatement(queries[i]);
        try {
          s.setInt(1, 8);
          s.executeQuery();
        }
        catch (SQLException e) {
          throw new SQLException(e.toString()
              + " Exception in executing query = " + queries[i], e
              .getSQLState());
        }
      }

      for (int i = 0; i < queries.length; ++i) {
        assertTrue(usedGemFireXDActivation[i] == getConvertibles[i]);
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }

  }

  public void testConvertibleToGet_2() throws SQLException, StandardException {
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);

    String[] queries = new String[] {
        "Select id as id1, cust_name , vol, security_id, num, addr from orders where id =8",
        "Select id as id, id ,id, id, id, id from orders where id =8",
        "Select id , vol, cust_name , security_id, num, addr from orders where id =8"
    /* "Select * from orders where id = 8 and cust_name = 'asif'" */};
    final boolean[] getConvertibles = new boolean[] { true, true, true };
    RegionKey gfk = getGemFireKey(8,
        Misc.getRegionForTable(getCurrentDefaultSchemaName() + ".ORDERS", true));

    final Object[] primaryKeys = new Object[] { gfk, gfk,
        gfk /* false */};
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;
            private DynamicKey dk = null;
            private QueryInfo qi;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              QueryInfoTest.this.callbackInvoked = true;
              try {
                assertTrue(qInfo instanceof SelectQueryInfo);
                assertTrue(qInfo.isPrimaryKeyBased() == getConvertibles[index]);
                try {
                  assertTrue(qInfo.createGFEActivation()== qInfo.isPrimaryKeyBased());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
                dk = (DynamicKey)qInfo.getPrimaryKey();
                assertNotNull(qInfo.getRegion());
                qi = qInfo;
              }
              catch (StandardException se) {
                fail("Test failed becaus eof exception=" + se);
              }
            }

            @Override
            public void beforeQueryExecution(
                EmbedStatement stmt, Activation activation) {
                try {
                  if(primaryKeys[index] == null) {
                    assertTrue(dk == null);
                  }
                  else {
                    validatePrimaryKey(dk.getEvaluatedPrimaryKey(activation,
                        (GemFireContainer)qi.getRegion().getUserAttribute(),
                        false), primaryKeys[index]);
                  }
                  ++index;
                } catch (StandardException e) {
                  fail("Unexpected exception while validating key " + e);
                }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement s = conn.createStatement();
      for (int i = 0; i < queries.length; ++i) {
        try {
          s.executeQuery(queries[i]);
        }
        catch (SQLException e) {
          throw new SQLException(e.toString()
              + " Exception in executing query = " + queries[i], e
              .getSQLState());
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

  public void testPreparedStatementConvertibleToGetForCompositeKeys()
      throws SQLException, IOException, StandardException {
    Connection conn = getConnection();
    createTableWithCompositeKey(conn);
    final boolean[] usedGemFireXDActivation = new boolean[] { false, true };

    String[] queries = new String[] { "Select * from orders where id =?",
    /*
     * "Select id, cust_name, vol, security_id, num, addr from orders where id
     * =8", "Select id, cust_name, vol, security_id, num, addr from orders where
     * id > 8", "Select id as id, cust_name as cust_name, vol, security_id, num,
     * addr from orders where id =8",
     */
    "Select * from orders where id =? and cust_name= ?"     
    /* "Select * from orders where id = 8 and cust_name = 'asif'" */};

    Region<?, ?> tableRegion = Misc.getRegionForTable(
        getCurrentDefaultSchemaName() + ".orders".toUpperCase(), true);
    GemFireContainer container = (GemFireContainer)tableRegion
        .getUserAttribute();
    RegionKey gfk = new CompactCompositeRegionKey(new DataValueDescriptor[] {
        new SQLInteger(8), new SQLVarchar("asif") },
        container.getExtraTableInfo());

    final boolean[] getConvertibles = new boolean[] { false, true };
    final Object[] primaryKeys = new Object[] { null, gfk };
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            private DynamicKey dk = null;
            private QueryInfo qi;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
                GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              QueryInfoTest.this.callbackInvoked = true;
              try {
                assertTrue(qInfo instanceof SelectQueryInfo);
                assertTrue(qInfo.isPrimaryKeyBased() == getConvertibles[index]);
                if (getConvertibles[index]) {
                  assertTrue(qInfo.getPrimaryKey() instanceof DynamicKey);
                  dk = (DynamicKey)qInfo.getPrimaryKey();
                }
                else {
                  dk = null;
                }
                assertNotNull(qInfo.getRegion());
                ++index;
                qi = qInfo;
              }
              catch (StandardException se) {
                fail("Test failed becaus eof exception=" + se);
              }
            }

            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation) {
              usedGemFireXDActivation[index - 1] = true;
              try {
                validatePrimaryKey(primaryKeys[index - 1], dk
                    .getEvaluatedPrimaryKey(activation, (GemFireContainer)qi
                        .getRegion().getUserAttribute(), false));
              }
              catch (StandardException se) {
                fail("Test failed because of exception=" + se);
              }
            }

          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      for (int i = 0; i < queries.length; ++i) {
        PreparedStatement s = conn.prepareStatement(queries[i]);
        s.setInt(1, 8);
        if (i == 1) {
          s.setString(2, "asif");
        }
        s.executeQuery();
      }

      for (int i = 0; i < queries.length; ++i) {
        assertTrue(usedGemFireXDActivation[i] == getConvertibles[i]);
      }
      assertTrue(this.callbackInvoked);
    }
    finally {
      if (old != null) {
        GemFireXDQueryObserverHolder.setInstance(old);
      }
    }

  }

  public void testConvertibleToGetForCompositeKeys() throws SQLException,
      IOException, StandardException {
    Connection conn = getConnection();
    createTableWithCompositeKey(conn);

    String[] queries = new String[] { "Select * from orders where id =8",
    /*
     * "Select id, cust_name, vol, security_id, num, addr from orders where id
     * =8", "Select id, cust_name, vol, security_id, num, addr from orders where
     * id > 8", "Select id as id, cust_name as cust_name, vol, security_id, num,
     * addr from orders where id =8",
     */
    "Select * from orders where id =8 and cust_name='asif'"

    /* "Select * from orders where id = 8 and cust_name = 'asif'" */};
    final boolean[] getConvertibles = new boolean[] { false, true };
    //Remember to captilize the name.
    final Region<?, ?> tableRegion = Misc.getRegionForTable(
        getCurrentDefaultSchemaName() + ".orders".toUpperCase(), true);
    RegionKey gfk = new CompactCompositeRegionKey(new DataValueDescriptor[] {
        new SQLInteger(8), new SQLVarchar("asif") },
        ((GemFireContainer)tableRegion.getUserAttribute()).getExtraTableInfo());
    final Object[] primaryKeys = new Object[] {
        null, gfk};
    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;
            private DynamicKey dk = null;
            private QueryInfo qi;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              QueryInfoTest.this.callbackInvoked = true;
              try {
                assertTrue(qInfo instanceof SelectQueryInfo);
                assertTrue(qInfo.isPrimaryKeyBased() == getConvertibles[index]);
                try {
                  assertTrue(qInfo.createGFEActivation()== qInfo.isPrimaryKeyBased());
                }catch(Exception e) {
                  e.printStackTrace();
                  fail(e.toString());
                }
                dk = (DynamicKey)qInfo.getPrimaryKey();
                assertNotNull(qInfo.getRegion());
                qi = qInfo;
              }
              catch (StandardException se) {
                fail("Test failed becaus eof exception=" + se);
              }
            }

            @Override
            public void beforeQueryExecution(
                EmbedStatement stmt, Activation activation) {
                try {
                  if( primaryKeys[index] == null ) {
                    assertTrue(dk == null);
                  }
                  else {
                    validatePrimaryKey(dk.getEvaluatedPrimaryKey(activation,
                        (GemFireContainer)qi.getRegion().getUserAttribute(),
                        false), primaryKeys[index]);
                  }
                  ++index;
                } catch (StandardException e) {
                  fail("Unexpected exception while validating key " + e);
                }
            }
          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement s = conn.createStatement();
      for (int i = 0; i < queries.length; ++i) {
        try {
          s.executeQuery(queries[i]);
        }
        catch (SQLException e) {
          throw new SQLException(e.toString()
              + " Exception in executing query = " + queries[i], e
              .getSQLState());
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

  public void testInconvertibleToGetQuery() throws Exception {
    Connection conn = getConnection();
    createTableWithPrimaryKey(conn);

    String[] queries = new String[] { "Select * from orders where id > 8"
    /* "Select * from orders where id = 8 and cust_name = 'asif'" */};

    GemFireXDQueryObserver old = null;
    try {
      old = GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            private int index = 0;

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc) {
              QueryInfoTest.this.callbackInvoked = true;
              assertNotNull(qInfo.getRegion());
              ++index;
            }

          });

      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      Statement s = conn.createStatement();
      for (int i = 0; i < queries.length; ++i) {
        try {
          s.executeQuery(queries[i]);
        }
        catch (SQLException e) {
          throw new SQLException(e.toString()
              + " Exception in executing query = " + queries[i], e
              .getSQLState());
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

  private void validatePrimaryKey(Object actual, Object expected) {

    if (expected instanceof byte[]) {
      assertTrue(actual instanceof byte[]);
      byte[] act = (byte[])actual;
      byte[] expct = (byte[])expected;
      assertTrue(act.length == expct.length);
      for (int i = 0; i < act.length; ++i) {
        assertEquals(act[i], expct[i]);
      }
    }
    else {
      assertEquals("got: " + actual, actual, expected);
    }
  }

  public void createTableWithPrimaryKey(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    s.execute("drop table if exists orders");
    // We create a table...
    s.execute("create table orders"
        + "(id int PRIMARY KEY, cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100))");
    s.close();
  }

  public void createTableWithCompositeKey(Connection conn) throws SQLException {
    Statement s = conn.createStatement();
    s.execute("drop table if exists orders");
    // We create a table...
    s.execute("create table orders"
        + "(id int , cust_name varchar(200), vol int, "
        + "security_id varchar(10), num int, addr varchar(100),"
        + " Primary Key (id, cust_name))");
    s.close();

  }

  /**
   * This is to test the sorting logic used in JunctionQueryInfo. The code is
   * copy pasted from the function
   * 
   * @see JunctionQueryInfo.sortOperandInIncreasingColumnPosition Need to find a
   *      better way to test it out . Creating dummy JunctionQueryInfo and its
   *      other artifacts is a bit painful
   * 
   */
  public void testSortLogic() {
    int test1[] = new int[] { 0, 1, 2, 3 };
    this.sort(test1);
    for (int i = 0; i < 4; ++i) {
      assertEquals(i, test1[i]);
    }

    int test2[] = new int[] { 0 };
    this.sort(test2);
    for (int i = 0; i < 1; ++i) {
      assertEquals(i, test2[i]);
    }

    int test3[] = new int[] { 0, 1 };
    this.sort(test3);
    for (int i = 0; i < 2; ++i) {
      assertEquals(i, test3[i]);
    }

    int test4[] = new int[] { 1, 0 };
    this.sort(test4);
    for (int i = 0; i < 2; ++i) {
      assertEquals(i, test4[i]);
    }

    int test5[] = new int[] { 1, 2, 0 };
    this.sort(test5);
    for (int i = 0; i < 3; ++i) {
      assertEquals(i, test5[i]);
    }

    int test6[] = new int[] { 1, 2, 0, 5, 4, 3, 9, 6, 7, 8 };
    this.sort(test6);
    for (int i = 0; i < 10; ++i) {
      assertEquals(i, test6[i]);
    }
  }

  /**
   * This is to test the sorting logic used in JunctionQueryInfo. The code is
   * copy pasted from the function
   * 
   * @see JunctionQueryInfo.sortOperandInIncreasingColumnPosition Need to find a
   *      better way to test it out . Creating dummy JunctionQueryInfo and its
   *      other artifacts is a bit painful
   * 
   */
  private void sort(int[] test) {
    // The checks before this function is invoked
    // have ensured that all the operands are of type ComparisonQueryInfo
    // and of the form var = constant. Also need for sorting will not arise
    // if there are only two operands

    int len = test.length;
    outer: for (int j = 0; j < len; ++j) {
      int toSort = test[j];
      inner: for (int i = j - 1; i > -1; --i) {
        int currSorted = test[i];
        if (currSorted < toSort) {
          // Found the position
          // Pick the next to sort
          if (i + 1 != j) {
            test[i + 1] = toSort;
          }
          break inner;
        }
        else {
          // Advance the current sorted to next & create an hole
          test[i + 1] = currSorted;
          if (i == 0) {
            //Reached the end just set the toSort at 0
            test[0] = toSort;
          }
        }
      }
    }
  }

  @Override
  public void tearDown() throws Exception {
    this.callbackInvoked = false;
    super.tearDown();
  }
}
