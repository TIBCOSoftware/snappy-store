/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package cacheperf.comparisons.gemfirexd.tpcc;

import cacheperf.CachePerfPrms;
import cacheperf.comparisons.gemfirexd.QueryPerfClient;
import cacheperf.comparisons.gemfirexd.QueryPerfException;
import cacheperf.comparisons.gemfirexd.QueryPerfPrms;
import cacheperf.comparisons.gemfirexd.QueryUtil;
import cacheperf.comparisons.gemfirexd.tpcc.TPCCStats.Stmt;
import hydra.Log;
import hydra.blackboard.SharedMap;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import objects.query.QueryPrms;
import objects.query.tpcc.*;
import sql.SQLHelper;
import sql.sqlutil.ResultSetHelper;

public class TPCCTaskClient extends TPCCClient {

//------------------------------------------------------------------------------
// delivGetCustId
//------------------------------------------------------------------------------

  public static void delivGetCustIdTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.delivGetCustId();
  }

  private void delivGetCustId() throws SQLException {
    int w_id, d_id, o_id;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      o_id = this.rng.nextInt(1, getCurrentKey(w_id, d_id));
      delivGetCustId(w_id, d_id, o_id);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void delivGetCustId(int w_id, int d_id, int o_id)
  throws SQLException {
    if (delivGetCustId == null) {
      String stmt = "SELECT o_c_id FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=delivGetCustId \n";
      }
      stmt += " oorder WHERE o_id = ? AND o_d_id = ? AND o_w_id = ?";
      delivGetCustId = this.connection.prepareStatement(stmt);
    }
    delivGetCustId.setInt(1, o_id);
    delivGetCustId.setInt(2, d_id);
    delivGetCustId.setInt(3, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT o_c_id FROM oorder" +
                " WHERE o_id = " + o_id +
                " AND o_d_id = " + d_id +
                " AND o_w_id = " + w_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.delivGetCustId);
    rs = delivGetCustId.executeQuery();
    if (!rs.next()) {
      String s = "o_id=" + o_id + " o_d_id=" + d_id
               + " o_w_id=" + w_id;
      throw new DataNotFoundException(s);
    }
    int c_id = rs.getInt("o_c_id");
    this.tpccstats.endStmt(Stmt.delivGetCustId, stmt_start, this.histogram);
    if (this.logQueryResults) {
      Log.getLogWriter().info("o_c_id=" + c_id);
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// delivGetOrderId
//------------------------------------------------------------------------------

  public static void delivGetOrderIdTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.delivGetOrderId();
  }

  private void delivGetOrderId() throws SQLException {
    int w_id, d_id;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
  
      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      delivGetOrderId(w_id, d_id);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void delivGetOrderId(int w_id, int d_id)
  throws SQLException {
    String stmt = null;
    if (delivGetOrderId == null) {
      if (this.queryAPI == QueryPrms.ORACLE && optimizeDelivGetOrderId) {
        stmt = "SELECT no_o_id FROM (SELECT no_o_id FROM new_order " +
               "WHERE no_d_id = ? AND no_w_id = ? " +
               "ORDER BY no_o_id ASC) WHERE ROWNUM = 1";
      } else {
        stmt = "SELECT no_o_id FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=delivGetOrderId \n";
        }
        stmt += " new_order WHERE no_d_id = ? AND no_w_id = ?" +
                " ORDER BY no_o_id ASC";
        if (optimizeDelivGetOrderId) { // oracle case is covered above
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " FETCH FIRST ROW ONLY";
          } else {
            stmt += " LIMIT 1";
          }
        }
      }
      delivGetOrderId = this.connection.prepareStatement(stmt);
    }
    delivGetOrderId.setInt(1, d_id);
    delivGetOrderId.setInt(2, w_id);
    if (this.logQueries) {
      if (optimizeDelivGetOrderId) { 
        if (this.queryAPI == QueryPrms.GFXD) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT no_o_id FROM new_order" +
                  " WHERE no_d_id = " + d_id +
                  " AND no_w_id = " + w_id +
                  " ORDER BY no_o_id ASC FETCH FIRST ROW ONLY");
        } else if (this.queryAPI == QueryPrms.ORACLE) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT no_o_id FROM (SELECT no_o_id FROM new_order" +
                  " WHERE no_d_id = " + d_id +
                  " AND no_w_id = " + w_id +
                  " ORDER BY no_o_id ASC) WHERE ROWNUM = 1");
        } else {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT no_o_id FROM new_order" +
                  " WHERE no_d_id = " + d_id +
                  " AND no_w_id = " + w_id +
                  " ORDER BY no_o_id ASC LIMIT 1");
        }
      } else {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT no_o_id FROM new_order" +
                " WHERE no_d_id = " + d_id +
                " AND no_w_id = " + w_id +
                " ORDER BY no_o_id ASC");
      }
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.delivGetOrderId);
    rs = delivGetOrderId.executeQuery();
    if (readAllDelivGetOrderId) {
      int rscount = 0;
      while (rs.next()) {
        int no_o_id = rs.getInt("no_o_id");
        ++rscount;
        if (this.logQueryResults) {
          Log.getLogWriter().info("no_o_id=" + no_o_id);
        }
      }
      this.tpccstats.endStmt(Stmt.delivGetOrderId, stmt_start, rscount, this.histogram);
    } else if (rs.next()) {
      int no_o_id = rs.getInt("no_o_id");
      if (this.logQueryResults) {
        Log.getLogWriter().info("no_o_id=" + no_o_id);
      }
      this.tpccstats.endStmt(Stmt.delivGetOrderId, stmt_start, 1, this.histogram);
    } else {
      this.tpccstats.endStmtNotFound(Stmt.delivGetOrderId, stmt_start);
      if (this.logQueryResults) {
        String s = "no_d_id=" + d_id + " no_w_id=" + w_id;
        throw new DataNotFoundException("NEW ORDERS NOT FOUND: " + s);
      }
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// delivSumOrderAmount
//------------------------------------------------------------------------------

  public static void delivSumOrderAmountTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.delivSumOrderAmount();
  }

  private void delivSumOrderAmount() throws SQLException {
    String groupBy = TPCCPrms.getDelivSumOrderAmountGroupBy();
    int w_id, d_id, no_o_id;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
 
      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      no_o_id = this.rng.nextInt(1, getCurrentKey(w_id, d_id));
      delivSumOrderAmount(w_id, d_id, no_o_id, groupBy);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void delivSumOrderAmount(int w_id, int d_id, int no_o_id, String groupBy)
  throws SQLException {
    if (groupBy.equals("none")) {
      delivSumOrderAmount(w_id, d_id, no_o_id);
    } else if (groupBy.equals("warehouse")) {
      delivSumOrderAmountGroupByWarehouse(d_id);
    } else if (groupBy.equals("district")) {
      delivSumOrderAmountGroupByDistrict(w_id);
    } else {
      throw new UnsupportedOperationException("Should not happen");
    }
  }

  private void delivSumOrderAmount(int w_id, int d_id, int no_o_id)
  throws SQLException {
    if (delivSumOrderAmount == null) {
      String stmt = "SELECT SUM(ol_amount) AS ol_total FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=delivSumOrderAmount \n";
      }
      stmt += " order_line WHERE ol_o_id = ? AND ol_d_id = ? AND ol_w_id = ?";
      delivSumOrderAmount = this.connection.prepareStatement(stmt);
    }
    delivSumOrderAmount.setInt(1, no_o_id);
    delivSumOrderAmount.setInt(2, d_id);
    delivSumOrderAmount.setInt(3, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT SUM(ol_amount) AS ol_total FROM order_line" +
                " WHERE ol_o_id = " + no_o_id +
                " AND ol_d_id = " + d_id +
                " AND ol_w_id = " + w_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.delivSumOrderAmount);
    rs = delivSumOrderAmount.executeQuery();
    if (rs.next()) {
      float ol_total = rs.getFloat("ol_total");
      this.tpccstats.endStmt(Stmt.delivSumOrderAmount, stmt_start, this.histogram);
      if (this.logQueryResults) {
        Log.getLogWriter().info("ol_total=" + ol_total);
      }
    } else {
      this.tpccstats.endStmtNotFound(Stmt.delivSumOrderAmount, stmt_start);
      if (this.logQueryResults) {
        String s = "ol_o_id=" + no_o_id + " ol_d_id=" + d_id
                 + " ol_w_id=" + w_id;
        Log.getLogWriter().info("NEW ORDER NOT FOUND: " + s);
      }
    }
    rs.close();
    rs = null;
  }

  private void delivSumOrderAmountGroupByWarehouse(int d_id)
  throws SQLException {
    if (delivSumOrderAmount == null) {
      String stmt = "SELECT ol_w_id, SUM(ol_amount) AS ol_total FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=delivSumOrderAmount \n";
      }
      stmt += " order_line WHERE ol_d_id = ? GROUP BY ol_w_id";
      delivSumOrderAmount = this.connection.prepareStatement(stmt);
    }
    delivSumOrderAmount.setInt(1, d_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT SUM(ol_amount) AS ol_total FROM order_line" +
                " WHERE ol_d_id = " + d_id +
                " GROUP BY ol_w_id");
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.delivSumOrderAmount);
    rs = delivSumOrderAmount.executeQuery();
    int wcount = 0;
    while (rs.next()) {
      ++wcount;
      float ol_total = rs.getFloat("ol_total");
      int ol_w_id = rs.getInt("ol_w_id");
      if (this.logQueryResults) {
        Log.getLogWriter().info("ol_w_id=" + ol_w_id + " ol_total=" + ol_total);
      }
    }

    if (wcount == 0) {
      this.tpccstats.endStmtNotFound(Stmt.delivSumOrderAmount, stmt_start);
      if (this.logQueryResults) {
        String s = "ol_d_id=" + d_id;
        Log.getLogWriter().info("NEW ORDER NOT FOUND: " + s);
      }
    } else if (wcount != this.numWarehouses) {
      String s = "Found orders for district " + d_id
               + " in only " + wcount + " warehouses";
      throw new DataNotFoundException(s);
    } else {
      this.tpccstats.endStmt(Stmt.delivSumOrderAmount, stmt_start, this.histogram);
    }
    rs.close();
    rs = null;
  }

  private void delivSumOrderAmountGroupByDistrict(int w_id)
  throws SQLException {
    if (delivSumOrderAmount == null) {
      String stmt = "SELECT ol_d_id, SUM(ol_amount) AS ol_total FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=delivSumOrderAmount \n";
      }
      stmt += " order_line WHERE ol_w_id = ? GROUP BY ol_d_id";
      delivSumOrderAmount = this.connection.prepareStatement(stmt);
    }
    delivSumOrderAmount.setInt(1, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT SUM(ol_amount) AS ol_total FROM order_line" +
                " WHERE ol_w_id = " + w_id +
                " GROUP BY ol_d_id");
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.delivSumOrderAmount);
    rs = delivSumOrderAmount.executeQuery();
    int dcount = 0;
    while (rs.next()) {
      ++dcount;
      float ol_total = rs.getFloat("ol_total");
      int ol_d_id = rs.getInt("ol_d_id");
      if (this.logQueryResults) {
        Log.getLogWriter().info("ol_d_id=" + ol_d_id + " ol_total=" + ol_total);
      }
    }
    if (dcount == 0) {
      this.tpccstats.endStmtNotFound(Stmt.delivSumOrderAmount, stmt_start);
      if (this.logQueryResults) {
        String s = "ol_w_id=" + w_id;
        Log.getLogWriter().info("NEW ORDER NOT FOUND: " + s);
      }
    } else if (dcount != this.numDistrictsPerWarehouse) {
      String s = "Found orders for warehouse " + w_id
               + " in only " + dcount + " districts";
      throw new DataNotFoundException(s);
    } else {
      this.tpccstats.endStmt(Stmt.delivSumOrderAmount, stmt_start, this.histogram);
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// ordStatCountCust
//------------------------------------------------------------------------------

  public static void ordStatCountCustTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.ordStatCountCust();
  }

  private void ordStatCountCust() throws SQLException {
    int w_id, d_id;
    String c_last;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      c_last = jTPCCUtil.getLastName(this.rng);
      ordStatCountCust(w_id, d_id, c_last);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void ordStatCountCust(int w_id, int d_id, String c_last)
  throws SQLException {
    if (ordStatCountCust == null) {
      String stmt = "SELECT count(*) AS namecnt FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=ordStatCountCust \n";
      }
      stmt += " customer WHERE c_last = ? AND c_d_id = ? AND c_w_id = ?";
      ordStatCountCust = this.connection.prepareStatement(stmt);
    }
    ordStatCountCust.setString(1, c_last);
    ordStatCountCust.setInt(2, d_id);
    ordStatCountCust.setInt(3, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT count(*) AS namecnt FROM customer" +
              " WHERE c_last = " + c_last +
              " AND c_d_id = " + d_id + " AND c_w_id = " + w_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.ordStatCountCust);
    rs = ordStatCountCust.executeQuery();
    if (rs.next()) {
      int namecnt = rs.getInt("namecnt");
      this.tpccstats.endStmt(Stmt.ordStatCountCust, stmt_start, this.histogram);
      if (this.logQueryResults) {
        Log.getLogWriter().info("namecnt=" + namecnt);
      }
    } else {
      this.tpccstats.endStmtNotFound(Stmt.ordStatCountCust, stmt_start);
      if (this.logQueryResults) {
        String s = "c_last=" + c_last + " c_d_id=" + d_id + " c_w_id=" + w_id;
        Log.getLogWriter().info("CUSTOMER NOT FOUND: " + s);
      }
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// ordStatGetCust
//------------------------------------------------------------------------------

  public static void ordStatGetCustTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.ordStatGetCust();
  }

  private void ordStatGetCust() throws SQLException {
    int w_id, d_id;
    String c_last;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      c_last = jTPCCUtil.getLastName(this.rng);
      ordStatGetCust(w_id, d_id, c_last);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void ordStatGetCust(int w_id, int d_id, String c_last)
  throws SQLException {
    if (ordStatGetCust == null) {
      String stmt = "SELECT c_balance, c_first, c_middle, c_id FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=ordStatGetCust \n";
      }
      stmt += " customer WHERE c_last = ? AND c_d_id = ? AND c_w_id = ?" +
              " ORDER BY c_w_id, c_d_id, c_last, c_first";
      ordStatGetCust = this.connection.prepareStatement(stmt);
    }
    ordStatGetCust.setString(1, c_last);
    ordStatGetCust.setInt(2, d_id);
    ordStatGetCust.setInt(3, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT c_balance, c_first, c_middle, c_id FROM customer" +
              " WHERE c_last = " + c_last + " AND c_d_id = " + d_id +
              " AND c_w_id = " + w_id +
              " ORDER BY c_w_id, c_d_id, c_last, c_first");
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.ordStatGetCust);
    rs = ordStatGetCust.executeQuery();
    int namecnt = 0;
    while (rs.next()) {
      ++namecnt;
      int c_id = rs.getInt("c_id");
      String c_first = rs.getString("c_first");
      String c_middle = rs.getString("c_middle");
      float c_balance = rs.getFloat("c_balance");
      if (this.logQueryResults) Log.getLogWriter().info("c_id=" + c_id
        + "c_first=" + c_first + "c_middle=" + c_middle
        + "c_balance=" + c_balance);
    }
    if (namecnt == 0) {
      this.tpccstats.endStmtNotFound(Stmt.ordStatGetCust, stmt_start);
      if (this.logQueryResults) {
        String s = "c_last=" + c_last + " c_d_id=" + d_id + " c_w_id=" + w_id;
        Log.getLogWriter().info("CUSTOMER NOT FOUND: " + s);
      }
    } else {
      this.tpccstats.endStmt(Stmt.ordStatGetCust, stmt_start, this.histogram);
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// ordStatGetNewestOrd
//------------------------------------------------------------------------------

  public static void ordStatGetNewestOrdTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.ordStatGetNewestOrd();
  }

  private void ordStatGetNewestOrd() throws SQLException {
    int w_id, d_id, c_id;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      c_id = jTPCCUtil.getCustomerID(this.rng, this.numCustomersPerDistrict);
      ordStatGetNewestOrd(w_id, d_id, c_id);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void ordStatGetNewestOrd(int w_id, int d_id, int c_id)
  throws SQLException {
    if (ordStatGetNewestOrd == null) {
      String stmt = "SELECT MAX(o_id) AS maxorderid FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=ordStatGetNewestOrd \n";
      }
      stmt += " oorder WHERE o_w_id = ? AND o_d_id = ? AND o_c_id = ?";
      ordStatGetNewestOrd = this.connection.prepareStatement(stmt);
    }
    ordStatGetNewestOrd.setInt(1, w_id);
    ordStatGetNewestOrd.setInt(2, d_id);
    ordStatGetNewestOrd.setInt(3, c_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT MAX(o_id) AS maxorderid FROM oorder" +
              " WHERE o_w_id = " + w_id +
              " AND o_d_id = " + d_id +
              " AND o_c_id = " + c_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.ordStatGetNewestOrd);
    rs = ordStatGetNewestOrd.executeQuery();
    if (rs.next()) {
      int o_id = rs.getInt("maxorderid");
      this.tpccstats.endStmt(Stmt.ordStatGetNewestOrd, stmt_start, this.histogram);
      if (this.logQueryResults) {
        Log.getLogWriter().info("maxorderid=" + o_id);
      }
    } else {
      this.tpccstats.endStmtNotFound(Stmt.ordStatGetNewestOrd, stmt_start);
      if (this.logQueryResults) {
        String s = "o_c_id=" + c_id + " o_d_id=" + d_id
                 + " o_w_id=" + w_id;
        Log.getLogWriter().info("OORDER NOT FOUND: " + s);
      }
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// payCursorCustByName
//------------------------------------------------------------------------------

  public static void payCursorCustByNameTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.payCursorCustByName();
  }

  private void payCursorCustByName() throws SQLException {
    int c_w_id, c_d_id;
    String c_last;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      c_w_id = this.rng.nextInt(1, this.numWarehouses);
      c_d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      c_last = jTPCCUtil.getLastName(this.rng);
      payCursorCustByName(c_w_id, c_d_id, c_last);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void payCursorCustByName(int c_w_id, int c_d_id, String c_last)
  throws SQLException {
    if (payCursorCustByName == null) {
      String stmt = "SELECT c_first, c_middle, c_id, c_street_1, c_street_2," +
          " c_city, c_state, c_zip, c_phone, c_credit, c_credit_lim," +
          " c_discount, c_balance, c_since FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=payCursorCustByName \n";
      }
      stmt += " customer WHERE c_w_id = ? AND c_d_id = ? AND c_last = ?" +
              " ORDER BY c_w_id, c_d_id, c_last, c_first";
      if (this.useScrollableResultSets) {
        payCursorCustByName = this.connection.prepareStatement(stmt,
          ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
      } else {
        payCursorCustByName = this.connection.prepareStatement(stmt);
      }
    }
    payCursorCustByName.setInt(1, c_w_id);
    payCursorCustByName.setInt(2, c_d_id);
    payCursorCustByName.setString(3, c_last);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT c_first, c_middle, c_id, c_street_1, c_street_2, c_city," +
        " c_state, c_zip, c_phone, c_credit, c_credit_lim, c_discount," +
        " c_balance, c_since " +
        "  FROM customer WHERE c_w_id = " + c_w_id +
        " AND c_d_id = " + c_d_id + " AND c_last = " + c_last +
        " ORDER BY c_w_id, c_d_id, c_last, c_first ");
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.payCursorCustByName);
    rs = payCursorCustByName.executeQuery();
    boolean found = false;
    int rownum = 0;
    if (useScrollableResultSets) {
      found = rs.last();
      if (found) rownum = rs.getRow();
    } else {
      found = rs.next();
      if (found) rownum = 1;
    }
    if (found) {
      int c_id = rs.getInt("c_id");
      String c_first = rs.getString("c_first");
      String c_middle = rs.getString("c_middle");
      String c_street_1 = rs.getString("c_street_1");
      String c_street_2 = rs.getString("c_street_2");
      String c_city = rs.getString("c_city");
      String c_state = rs.getString("c_state");
      String c_zip = rs.getString("c_zip");
      String c_phone = rs.getString("c_phone");
      String c_credit = rs.getString("c_credit");
      float c_credit_lim = rs.getFloat("c_credit_lim");
      float c_discount = rs.getFloat("c_discount");
      float c_balance = rs.getFloat("c_balance");
      Date c_since = rs.getDate("c_since");

      this.tpccstats.endStmt(Stmt.payCursorCustByName, stmt_start, this.histogram);
      if (this.logQueryResults) {
        Log.getLogWriter().info(rownum + ": c_credit_lim=" + c_credit_lim
          + "c_id=" + c_id
          + "c_first=" + c_first + "c_middle=" + c_middle + "c_last" + c_last
          + "c_street_1=" + c_street_1 + "c_street_2=" + c_street_2
          + "c_city=" + c_city + "c_state=" + c_state + "c_zip=" + c_zip
          + "c_phone=" + c_phone + "c_credit=" + c_credit
          + "c_discount=" + c_discount + "c_balance=" + c_balance
          + "c_since=" + c_since);
      }
    } else {
      this.tpccstats.endStmtNotFound(Stmt.payCursorCustByName, stmt_start);
      if (this.logQueryResults) {
        String s = "c_last=" + c_last + " c_d_id=" + c_d_id
                 + " c_w_id=" + c_w_id;
        Log.getLogWriter().info("CUSTOMER NOT FOUND: " + s);
      }
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// payGetDist
//------------------------------------------------------------------------------

  public static void payGetDistTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.payGetDist();
  }

  private void payGetDist() throws SQLException {
    int w_id, d_id;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      payGetDist(w_id, d_id);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void payGetDist(int w_id, int d_id)
  throws SQLException {
    if (payGetDist == null) {
      String stmt = "SELECT d_street_1, d_street_2, d_city, d_state, d_zip," +
                    " d_name FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=payGetDist \n";
      }
      stmt += " district WHERE d_w_id = ? AND d_id = ?";
      payGetDist = this.connection.prepareStatement(stmt);
    }
    payGetDist.setInt(1, w_id);
    payGetDist.setInt(2, d_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT d_street_1, d_street_2, d_city, d_state, d_zip, d_name" +
              " FROM district WHERE d_w_id = " + w_id +
              " AND d_id = " + d_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.payGetDist);
    rs = payGetDist.executeQuery();
    if (!rs.next()) {
      String s = "d_id=" + d_id + " d_w_id=" + w_id;
      throw new DataNotFoundException(s);
    }
    String d_street_1 = rs.getString("d_street_1");
    String d_street_2 = rs.getString("d_street_2");
    String d_city = rs.getString("d_city");
    String d_state = rs.getString("d_state");
    String d_zip = rs.getString("d_zip");
    String d_name = rs.getString("d_name");
    this.tpccstats.endStmt(Stmt.payGetDist, stmt_start, this.histogram);
    if (this.logQueryResults) {
      Log.getLogWriter().info("d_street_1=" + d_street_1
         + "d_street_2=" + d_street_2 + "d_city=" + d_city
         + "d_state=" + d_state + "d_zip=" + d_zip + "d_name=" + d_name);
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// payGetWhse
//------------------------------------------------------------------------------

  public static void payGetWhseTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.payGetWhse();
  }

  private void payGetWhse() throws SQLException {
    int w_id;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      payGetWhse(w_id);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void payGetWhse(int w_id)
  throws SQLException {
    if (payGetWhse == null) {
      String stmt = "SELECT w_street_1, w_street_2, w_city, w_state, w_zip," +
                    " w_name FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=payGetWhse \n";
      }
      stmt += " warehouse WHERE w_id = ?";
      payGetWhse = this.connection.prepareStatement(stmt);
    }
    payGetWhse.setInt(1, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT w_street_1, w_street_2, w_city, w_state, w_zip, w_name" +
              " FROM warehouse WHERE w_id = " + w_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.payGetWhse);
    rs = payGetWhse.executeQuery();
    if (!rs.next()) {
      String s = "w_id=" + w_id;
      throw new DataNotFoundException(s);
    }
    String w_street_1 = rs.getString("w_street_1");
    String w_street_2 = rs.getString("w_street_2");
    String w_city = rs.getString("w_city");
    String w_state = rs.getString("w_state");
    String w_zip = rs.getString("w_zip");
    String w_name = rs.getString("w_name");
    this.tpccstats.endStmt(Stmt.payGetWhse, stmt_start, this.histogram);
    if (this.logQueryResults) {
      Log.getLogWriter().info("w_street_1=" + w_street_1
         + "w_street_2=" + w_street_2 + "w_city=" + w_city
         + "w_state=" + w_state + "w_zip=" + w_zip + "w_name=" + w_name);
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// payUpdateWhse
//------------------------------------------------------------------------------

  public static void payUpdateWhseTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.payUpdateWhse();
  }

  private void payUpdateWhse() throws SQLException {
    int w_id;
    float h_amount;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      h_amount = (float)(this.rng.nextInt(100, 500000)/100.0);
      payUpdateWhse(w_id, h_amount);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void payUpdateWhse(int w_id, float h_amount)
  throws SQLException {
    long start = this.tpccstats.startTransaction(PAYMENT);
    if (payUpdateWhse == null) {
      String stmt = "UPDATE warehouse SET w_ytd = w_ytd + ?";
      stmt += " WHERE w_id = ?";
      payUpdateWhse = this.connection.prepareStatement(stmt);
    }
    payUpdateWhse.setFloat(1,h_amount);
    payUpdateWhse.setInt(2,w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "UPDATE warehouse SET w_ytd = w_ytd + " + h_amount +
              " WHERE w_id = " + w_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.payUpdateWhse);
    int result = payUpdateWhse.executeUpdate();
    if (this.timeStmts) {
      this.tpccstats.endStmt(Stmt.payUpdateWhse, stmt_start, this.histogram);
    }
    if (result == 0) {
      String s = "w_id=" + w_id;
      throw new DataNotFoundException(s);
    }
    commit(PAYMENT, start);
  }

//------------------------------------------------------------------------------
// stmtGetCustWhse
//------------------------------------------------------------------------------

  public static void stmtGetCustWhseTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.stmtGetCustWhse();
  }

  private void stmtGetCustWhse() throws SQLException {
    int w_id, d_id, c_id;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      c_id = jTPCCUtil.getCustomerID(this.rng, this.numCustomersPerDistrict);
      stmtGetCustWhse(w_id, d_id, c_id);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void stmtGetCustWhse(int w_id, int d_id, int c_id)
  throws SQLException {
    if (stmtGetCustWhse == null) {
      String stmt = "SELECT c_discount, c_last, c_credit, w_tax FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=stmtGetCustWhse \n";
      }
      stmt += " customer, warehouse WHERE" +
              " w_id = ? AND w_id = c_w_id AND c_d_id = ? AND c_id = ?";
      stmtGetCustWhse = this.connection.prepareStatement(stmt);
    }
    stmtGetCustWhse.setInt(1, w_id);
    stmtGetCustWhse.setInt(2, d_id);
    stmtGetCustWhse.setInt(3, c_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT c_discount, c_last, c_credit, w_tax" +
              " FROM customer, warehouse" +
               " WHERE w_id = " + w_id +
               " AND w_id = c_w_id AND c_d_id = " + d_id +
               " AND c_id = " + c_id);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.stmtGetCustWhse);
    rs = stmtGetCustWhse.executeQuery();
    if (!rs.next()) {
      String s = "w_id=" + w_id + " c_d_id=" + d_id + " c_id=" + c_id;
      throw new DataNotFoundException(s);
    }
    float c_discount = rs.getFloat("c_discount");
    String c_last = rs.getString("c_last");
    String c_credit = rs.getString("c_credit");
    float w_tax = rs.getFloat("w_tax");
    this.tpccstats.endStmt(Stmt.stmtGetCustWhse, stmt_start, this.histogram);
    if (this.logQueryResults) {
      Log.getLogWriter().info("c_discount=" + c_discount
         + "c_last=" + c_last + "c_credit=" + c_credit + "w_tax=" + w_tax);
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// stockGetCountStock
//------------------------------------------------------------------------------

  public static void stockGetCountStockTask() throws SQLException {
    TPCCTaskClient c = new TPCCTaskClient();
    c.initialize(TRANSACTIONS);
    c.stockGetCountStock();
  }

  private void stockGetCountStock() throws SQLException {
    int w_id, d_id, o_id;
    int threshold;
    do {
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();

      w_id = this.rng.nextInt(1, this.numWarehouses);
      d_id = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      o_id = this.rng.nextInt(1, getCurrentKey(w_id, d_id));
      threshold = this.rng.nextInt(10, 20);
      stockGetCountStock(w_id, d_id, o_id, threshold);

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator());
  }

  private void stockGetCountStock(int w_id, int d_id, int o_id, int threshold)
  throws SQLException {
    if (stockGetCountStock == null) {
      String stmt = "SELECT COUNT(DISTINCT (s_i_id)) AS stock_count FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=stockGetCountStock \n";
      }
      stmt += " order_line, stock" +
              " WHERE ol_w_id = ? AND ol_d_id = ? AND ol_o_id < ?" +
              " AND ol_o_id >= ? - 20 AND s_w_id = ol_w_id" +
              " AND s_i_id = ol_i_id AND s_quantity < ?";
      stockGetCountStock = this.connection.prepareStatement(stmt);
    }
    stockGetCountStock.setInt(1, w_id);
    stockGetCountStock.setInt(2, d_id);
    stockGetCountStock.setInt(3, o_id);
    stockGetCountStock.setInt(4, o_id);
    stockGetCountStock.setInt(5, threshold);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT COUNT(DISTINCT (s_i_id)) AS stock_count" +
              " FROM order_line, stock" +
              " WHERE ol_w_id = " + w_id +
              " AND ol_d_id = " + d_id +
              " AND ol_o_id < " + o_id +
              " AND ol_o_id >= " + o_id + " - 20" +
              " AND s_w_id = ol_w_id" +
              " AND s_i_id = ol_i_id" +
              " AND s_quantity < " + threshold);
    }
    long stmt_start = this.tpccstats.startStmt(Stmt.stockGetCountStock);
    rs = stockGetCountStock.executeQuery();
    if (!rs.next()) {
      String s = "ol_w_id=" + w_id + " ol_d_id=" + d_id + " ol_o_id="
               + o_id + " (...)";
      throw new DataNotFoundException(s);
    }
    int stock_count = rs.getInt("stock_count");
    this.tpccstats.endStmt(Stmt.stockGetCountStock, stmt_start, this.histogram);
    if (this.logQueryResults) {
      Log.getLogWriter().info("stock_count=" + stock_count);
    }
    rs.close();
    rs = null;
  }
}
