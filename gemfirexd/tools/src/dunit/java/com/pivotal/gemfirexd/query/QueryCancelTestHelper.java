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
package com.pivotal.gemfirexd.query;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import io.snappydata.test.dunit.standalone.DUnitBB;
import org.junit.Ignore;

/**
 * This class does not contain any tests. 
 * See the child classes for tests
 * @author shirishd
 *
 */
@Ignore
public abstract class QueryCancelTestHelper extends DistributedSQLTestBase {

  protected enum StatementType {
    STATEMENT, PREPARED_STATEMENT, BATCH_STATEMENT
  }

  /**
   * Checks the value in {@link DUnitBB} for the given key (test).
   * If it equals expectedNumOfServers, return true. In this test, this is
   * basically used to check whether required data/query nodes are at a point
   * where query can be cancelled. All servers increment the value in DUnitBB map
   * once to indicate that they are ready for cancellation
   * 
   * @param key
   *          name of the test
   * @param expectedNumOfServers
   *          value against the key that is the number of servers expected to
   *          increment the value
   */
  public static boolean serversReadyForCancellation(String key,
      Integer expectedNumOfServers) {
    Integer value = (Integer)DUnitBB.getBB().get(key);
    getGlobalLogger().info(
        " serversReadyForCancellation: value in BB map for key: " + key
            + " value is " + value);
    if ((value != null) && value.equals(expectedNumOfServers)) {
      return true;
    }
    return false;
  }

  /**
   * Increments the value in {@link DUnitBB} for a given key
   * (test). Do nothing, if it already equals expectedMaxValue.</br>
   * 
   * </p>In this test, data or query nodes increment this value in some observer
   * callback. This value is checked in the test so that all nodes reach a
   * particular point before further processing.
   */
  public static synchronized void incrementValueInBBMap(String key,
      int expectedMaxValue) {
    DUnitBB bb = DUnitBB.getBB();
    try {
      bb.acquireSharedLock();
      Integer v = (Integer)bb.get(key);
      if (v == null) {
        getGlobalLogger().info(
            "incrementValueInBBMap: putting value=" + 1 + " for key=" + key);
        bb.put(key, 1);
      } else if (v.equals(expectedMaxValue)) {
        return;
      } else {
        getGlobalLogger().info(
            "incrementValueInBBMap: putting value=" + (v + 1) + " for key="
                + key);
        bb.put(key, v + 1);
      }
    } finally {
      bb.releaseSharedLock();
    }
  }

  /**
   * Put the statement UUID in BB shared map. Used to cancel a query using
   * stored procedure
   */
  public static void putStatementUUIDinBBMap(String testKey, long connId,
      long stmtId, long execId) {
    DUnitBB bb = DUnitBB.getBB();
    bb.put(testKey + "_stmtUUID", connId + "-" + stmtId + "-" + execId);
  }

  public static String getStatementUUIDfromBBMap(String testKey) {
    return (String)DUnitBB.getBB().get(testKey + "_stmtUUID");
  }

  /**
   * Waits for {@link #serversReadyForCancellation(String, Integer)} to return
   * true
   * 
   * @param testKey
   * @param numNodesToBeWaited
   */
  private static void waitForCancellationSignal(final String testKey,
      final int numNodesToBeWaited) {
    waitForCriterion(new WaitCriterion() {
      @Override
      public boolean done() {
        return serversReadyForCancellation(testKey, numNodesToBeWaited);
      }

      @Override
      public String description() {
        return "waiting for signal from data nodes to cancel the query";
      }
    }, 20000, 500, true);
  }

  public QueryCancelTestHelper(String name) {
    super(name);
  }

  /**
   * Cancel the query either using JDBC {@link Statement#cancel()} method or by
   * using {@link GfxdSystemProcedures#CANCEL_STATEMENT(String)} system
   * procedure. </br> If system procedure is used, will also make sure that the
   * given statement is present in sessions VTI.
   */
  private void cancelQuery(final Statement stmt, final String testKey,
      boolean useCancelProc) throws SQLException {
    if (!useCancelProc) {
      // cancel using JDBC Statement#cancel
      stmt.cancel();
    } else {
      // cancel using system procedure
      String stmtUUID = getStatementUUIDfromBBMap(testKey);
      // execution ID may be different for remote connection vs query
      // connection entry in SESSIONS table
      int lastDash = stmtUUID.lastIndexOf('-');
      if (lastDash > 0) {
        stmtUUID = stmtUUID.substring(0, lastDash);
      }
      getLogWriter().info("UUID for " + testKey + "=" + stmtUUID);
      Connection c = TestUtil.getConnection();
      Statement s = c.createStatement();

      // make sure that the sessions VTI contains the statement to be cancelled
      ResultSet rs = s
          .executeQuery("select current_statement_UUID, CURRENT_STATEMENT from sys.sessions");
      String foundUUID = null;
      while (rs.next()) {
        if (rs.getString(1).startsWith(stmtUUID)) {
          foundUUID = rs.getString(1);
        } else {
          getLogWriter().info("UUID in the sessions is " + rs.getString(1)
              + " and statement text is: " + rs.getString(2));
        }
      }
      assertNotNull("Statement UUID " + stmtUUID
          + " is not present in sessions VTI", foundUUID);
      rs.close();

      String cancelStmt = "CALL SYS.CANCEL_STATEMENT(?)";
      CallableStatement cs = c.prepareCall(cancelStmt);
      cs.setString(1, foundUUID);
      cs.execute();
    }
  }

  /**
   * Executes the given statement in a new thread. Then, in the
   * {@linkplain #waitForCancellationSignal(String, int)}, will wait for the
   * given number of servers (numNodesToBeWaited) to signal that they are ready
   * for the query to be cancelled. Once the servers are ready, cancel the
   * statement and verify whether the statement was indeed cancelled.
   */
  public void executeAndCancelQuery(final Statement stmt,
      final StatementType stmtType, final int numNodesToBeWaited,
      final String testKey, final String query, boolean useCancelProc)
      throws Throwable {
    final Throwable[] failure = new Throwable[1];

    final Runnable doExecute = new Runnable() {
      @Override
      public void run() {
        addExpectedException(new int[] { 1 }, new int[] { 1, 2 },
            SQLException.class);
        try {
          // execute the statement
          boolean hasResult = false;
          switch (stmtType) {
          case STATEMENT:
            hasResult = stmt.execute(query);
            if (hasResult) {
              ResultSet rs = stmt.getResultSet();
              while (rs.next()) {
              }
            }
            break;

          case PREPARED_STATEMENT:
            hasResult = ((PreparedStatement) stmt).execute();
            int count = 0;
            if (hasResult) {
              ResultSet rs = stmt.getResultSet();
              while (rs.next()) {
                count++;
              }
            }
            getLogWriter().info("Num rows retrieved=" + count);
            break;

          case BATCH_STATEMENT:
            stmt.executeBatch();
            break;
          }
          fail("This test should have thrown exception "
              + "due to query cancellation (exception state XCL56)");
        } catch (Throwable t) {
          getLogWriter().info("1. received failure: " + t);
          failure[0] = t;
        } finally {
          removeExpectedException(new int[] { 1 }, new int[] { 1, 2 },
              SQLException.class);
        }
      }
    };
    Thread t1 = new Thread(doExecute);
    t1.start();

    // wait for a signal to cancel the above executed query
    if (numNodesToBeWaited > 0) {
      waitForCancellationSignal(testKey, numNodesToBeWaited);
    }

    // now cancel the query
    cancelQuery(stmt, testKey, useCancelProc);

    t1.join();
    getLogWriter().info("2. received failure: " + failure[0]);
    if (failure[0] != null) {
      if (failure[0] instanceof SQLException) {
        SQLException se = (SQLException) failure[0];
        if (!se.getSQLState().equals("XCL56")) {
          getLogWriter().info(se);
          throw se;
        }
      } else {
        getLogWriter().info(failure[0]);
        throw failure[0];
      }
    }
  }

  public Connection _getConnection(boolean useThinClient) throws Throwable {
    Connection cxn = null;
    if (useThinClient) {
      Properties props = new Properties();
      // props.setProperty("log-level", "fine");
      int clientPort = startNetworkServer(1, null, null);
      cxn = TestUtil.getNetConnection(clientPort, null, props);
    } else {
      Properties props = new Properties();
//      props.setProperty("gemfirexd.debug.true", "QueryDistribution,TraceExecution");
      cxn = TestUtil.getConnection(props);
    }
    return cxn;
  }

}