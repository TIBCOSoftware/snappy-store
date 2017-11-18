/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
/*
 * Copyright (C) 2004-2006, Denis Lussier
 *
 * LoadData - Load Sample Data directly into database tables or create CSV files
 * for each table that can then be bulk loaded (again & again & again ...)  :-)
 *
 * Modified for hydra by lises@gemstone.com.
 *
 */
package cacheperf.comparisons.gemfirexd.tpcc;

import cacheperf.comparisons.gemfirexd.QueryPerfException;

import hydra.GsRandom;
import hydra.HydraRuntimeException;
import hydra.Log;
import hydra.RemoteTestModule;

import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import objects.query.QueryPrms;
import objects.query.tpcc.Customer;
import objects.query.tpcc.District;
import objects.query.tpcc.History;
import objects.query.tpcc.Item;
import objects.query.tpcc.NewOrder;
import objects.query.tpcc.Oorder;
import objects.query.tpcc.OrderLine;
import objects.query.tpcc.Stock;
import objects.query.tpcc.Warehouse;

public class LoadData extends TPCCClient {

  private static final String CUSTOMER_REGION = "/APP/CUSTOMER";

  private Timestamp sysdate = null;
  private List<Integer> primaries;
  private int startValW, stopValW, incValW;
  private int startValI, stopValI, incValI;
  private int startValC, stopValC, incValC;

  public LoadData() {
  }

  protected void init() {
    // get this site's share of the primary buckets for this jvm
    primaries = getPrimariesToLoad();
    if (primaries == null) { // loading from clients (includes mysql/oracle)
      // for each warehouse, load only the items for my ttgid
      startValW = 1;
      stopValW = this.numWarehouses + 1;
      incValW = 1;
      startValI = this.ttgid + 1;
      stopValI = this.numItems + 1;
      incValI = this.numThreads;
      startValC = this.ttgid + 1;
      stopValC = this.numCustomersPerDistrict + 1;
      incValC = this.numThreads;
    } else { // loading from servers
      // for each warehouse corresponding to a jvm-unique primary bucket,
      // load only the items for my jid
      startValW = 0;
      stopValW = primaries.size();
      incValW = 1;
      startValI = this.jid + 1;
      stopValI = this.numItems + 1;
      incValI = RemoteTestModule.getMyNumThreads();
      startValC = this.jid + 1;
      stopValC = this.numCustomersPerDistrict + 1;
      incValC = RemoteTestModule.getMyNumThreads();
    }
  }

  // converts a loop variable into a warehouse id
  private int getWID(int index) {
    if (primaries == null) {
      return index; // already a warehouse id
    } else {
      // look up the primary bucket id which is the warehouse id
      int bid = primaries.get(index);
      // except for bucket id 0 which needs to be wrapped around
      return bid == 0 ? this.numWarehouses : bid;
    }
  }

  public void loadWarehouseData() throws InterruptedException, SQLException {
    loadWarehouse(this.numWarehouses, this.rng);
  }

  public void loadItemData() throws InterruptedException, SQLException {
    loadItem(this.numItems, this.rng);
  }

  public void loadStockData() throws InterruptedException, SQLException {
    loadStock(this.numWarehouses, this.numItems, this.rng);
  }

  public void loadDistrictData() throws InterruptedException, SQLException {
    loadDistrict(this.numWarehouses, this.numDistrictsPerWarehouse, this.rng);
  }

  public void loadCustomerData() throws InterruptedException, SQLException {
    loadCustomer(this.numWarehouses, this.numDistrictsPerWarehouse,
                 this.numCustomersPerDistrict, this.rng);
  }

  public void loadOrderData() throws InterruptedException, SQLException {
    loadOrder(this.numWarehouses, this.numDistrictsPerWarehouse,
              this.numCustomersPerDistrict, this.rng);
  }

  private void loadWarehouse(int whseCount, GsRandom gen)
  throws InterruptedException, SQLException {
    Log.getLogWriter().info("Loading " + whseCount + " Warehouses");

    PreparedStatement whsePrepStmt = this.connection.prepareStatement
       (insertOp + " INTO warehouse " +
        "(w_id, w_ytd, w_tax, w_name, w_street_1, w_street_2, " +
         "w_city, w_state, w_zip) " +
       "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");

    Warehouse warehouse  = new Warehouse();

    long start = this.tpccstats.startWarehouse();
    int throttleMs = getThrottleMs();
    for (int w = this.ttgid + 1; w <= whseCount; w += this.numThreads) {

      warehouse.w_id       = w;
      warehouse.w_ytd      = 300000;

      // random within [0.0000 .. 0.2000]
      warehouse.w_tax = (float)((jTPCCUtil.randomNumber(0,2000,gen))/10000.0);

      warehouse.w_name     = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(6,10,gen));
      warehouse.w_street_1 = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
      warehouse.w_street_2 = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
      warehouse.w_city     = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
      warehouse.w_state    = jTPCCUtil.randomStr(3).toUpperCase();
      warehouse.w_zip      = "123456789";

      whsePrepStmt.setLong(1, warehouse.w_id);
      whsePrepStmt.setDouble(2, warehouse.w_ytd);
      whsePrepStmt.setDouble(3, warehouse.w_tax);
      whsePrepStmt.setString(4, warehouse.w_name);
      whsePrepStmt.setString(5, warehouse.w_street_1);
      whsePrepStmt.setString(6, warehouse.w_street_2);
      whsePrepStmt.setString(7, warehouse.w_city);
      whsePrepStmt.setString(8, warehouse.w_state);
      whsePrepStmt.setString(9, warehouse.w_zip);
      if (this.logQueries) {
        Log.getLogWriter().info(
           insertOp + " INTO warehouse " +
              "(w_id, etc.) " + "VALUES (" + warehouse.w_id + ", etc.)");
      }
      whsePrepStmt.executeUpdate();
      this.tpccstats.endWarehouse(start, 1);
      commit(WAREHOUSE);
      if (throttleMs != 0) {
        Thread.sleep(throttleMs);
      }
      start = this.tpccstats.startWarehouse();
    }
  } // end warehouse

  private void loadItem(int itemCount, GsRandom gen)
  throws InterruptedException, SQLException {
    Log.getLogWriter().info("Loading " + itemCount + " Items...");

    PreparedStatement itemPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO item " +
       "(i_id, i_name, i_price, i_data, i_im_id) " +
      "VALUES (?, ?, ?, ?, ?)");

    Item item = new Item();

    int k = 0;
    long start = this.tpccstats.startItem();
    int throttleMs = getThrottleMs();
    for (int i = this.ttgid + 1; i <= itemCount; i += this.numThreads) {
      item.i_id = i;
      item.i_name = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(14,24,gen));
      item.i_price = (float)(jTPCCUtil.randomNumber(100,10000,gen)/100.0);

      // i_data
      int randPct = jTPCCUtil.randomNumber(1, 100, gen);
      int len = jTPCCUtil.randomNumber(26, 50, gen);
      if (randPct > 10) {
         // 90% of time i_data is a random string of length [26 .. 50]
         item.i_data = jTPCCUtil.randomStr(len);
      } else {
        // 10% of time i_data has "ORIGINAL" crammed somewhere in middle
        int startORIGINAL = jTPCCUtil.randomNumber(2, (len - 8), gen);
        item.i_data = jTPCCUtil.randomStr(startORIGINAL - 1) + "ORIGINAL"
                    + jTPCCUtil.randomStr(len - startORIGINAL - 9);
      }
      item.i_im_id = jTPCCUtil.randomNumber(1, 10000, gen);

      itemPrepStmt.setLong(1, item.i_id);
      itemPrepStmt.setString(2, item.i_name);
      itemPrepStmt.setDouble(3, item.i_price);
      itemPrepStmt.setString(4, item.i_data);
      itemPrepStmt.setLong(5, item.i_im_id);
      itemPrepStmt.addBatch();
      k++;

      if (k >= this.commitCount) {
        itemPrepStmt.executeBatch();
        itemPrepStmt.clearBatch();
        this.tpccstats.endItem(start, k);
        k = 0;
        commit(ITEM);
        if (throttleMs != 0) {
          Thread.sleep(throttleMs);
        }
        start = this.tpccstats.startItem();
      }
    }
    if (k > 0) {
      itemPrepStmt.executeBatch();
      itemPrepStmt.clearBatch();
      this.tpccstats.endItem(start, k);
      commit(ITEM);
    }
  } // end item

  private void loadStock(int whseCount, int itemCount, GsRandom gen)
  throws InterruptedException, SQLException {
    int t = whseCount * itemCount;
    Log.getLogWriter().info("Loading Stock for " + t + " units ...");

    PreparedStatement stckPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO stock " +
       "(s_i_id, s_w_id, " +
        "s_quantity, s_ytd, s_order_cnt, s_remote_cnt, s_data, " +
        "s_dist_01, s_dist_02, s_dist_03, s_dist_04, s_dist_05, " +
        "s_dist_06, s_dist_07, s_dist_08, s_dist_09, s_dist_10) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

    Stock stock  = new Stock();

    int k = 0;
    long start = this.tpccstats.startStock();
    int throttleMs = getThrottleMs();
    for (int tmp = startValW; tmp < stopValW; tmp += incValW) {
      int w = getWID(tmp);
      for (int i = startValI; i < stopValI; i += incValI) {
        stock.s_w_id = w;
        stock.s_i_id = i;
        stock.s_quantity = jTPCCUtil.randomNumber(10, 100, gen);
        stock.s_ytd = 0;
        stock.s_order_cnt = 0;
        stock.s_remote_cnt = 0;

        // s_data
        int randPct = jTPCCUtil.randomNumber(1, 100, gen);
        int len = jTPCCUtil.randomNumber(26, 50, gen);
        if ( randPct > 10 ) {
          // 90% of time i_data isa random string of length [26 .. 50]
          stock.s_data = jTPCCUtil.randomStr(len);
        } else {
          // 10% of time i_data has "ORIGINAL" crammed somewhere in middle
          int startORIGINAL = jTPCCUtil.randomNumber(2, (len - 8), gen);
          stock.s_data = jTPCCUtil.randomStr(startORIGINAL - 1) + "ORIGINAL"
                       + jTPCCUtil.randomStr(len - startORIGINAL - 9);
        }
        stock.s_dist_01 = jTPCCUtil.randomStr(24);
        stock.s_dist_02 = jTPCCUtil.randomStr(24);
        stock.s_dist_03 = jTPCCUtil.randomStr(24);
        stock.s_dist_04 = jTPCCUtil.randomStr(24);
        stock.s_dist_05 = jTPCCUtil.randomStr(24);
        stock.s_dist_06 = jTPCCUtil.randomStr(24);
        stock.s_dist_07 = jTPCCUtil.randomStr(24);
        stock.s_dist_08 = jTPCCUtil.randomStr(24);
        stock.s_dist_09 = jTPCCUtil.randomStr(24);
        stock.s_dist_10 = jTPCCUtil.randomStr(24);

        stckPrepStmt.setLong(1, stock.s_i_id);
        stckPrepStmt.setLong(2, stock.s_w_id);
        stckPrepStmt.setDouble(3, stock.s_quantity);
        stckPrepStmt.setDouble(4, stock.s_ytd);
        stckPrepStmt.setLong(5, stock.s_order_cnt);
        stckPrepStmt.setLong(6, stock.s_remote_cnt);
        stckPrepStmt.setString(7, stock.s_data);
        stckPrepStmt.setString(8, stock.s_dist_01);
        stckPrepStmt.setString(9, stock.s_dist_02);
        stckPrepStmt.setString(10, stock.s_dist_03);
        stckPrepStmt.setString(11, stock.s_dist_04);
        stckPrepStmt.setString(12, stock.s_dist_05);
        stckPrepStmt.setString(13, stock.s_dist_06);
        stckPrepStmt.setString(14, stock.s_dist_07);
        stckPrepStmt.setString(15, stock.s_dist_08);
        stckPrepStmt.setString(16, stock.s_dist_09);
        stckPrepStmt.setString(17, stock.s_dist_10);
        if (this.logQueries) {
          Log.getLogWriter().info(
             insertOp + " INTO stock " +
                "(s_w_id, s_i_id, etc.) " + "VALUES (" +
                  stock.s_w_id + ", " +
                  stock.s_i_id   + ", etc.) [batched]");
        }
        stckPrepStmt.addBatch();
        k++;

        if (k >= this.commitCount) {
          stckPrepStmt.executeBatch();
          stckPrepStmt.clearBatch();
          this.tpccstats.endStock(start, k);
          k = 0;
          commit(STOCK);
          if (throttleMs != 0) {
            Thread.sleep(throttleMs);
          }
          start = this.tpccstats.startStock();
        }
      } // end for [i]
      if (k > 0) {
        stckPrepStmt.executeBatch();
        stckPrepStmt.clearBatch();
        this.tpccstats.endStock(start, k);
        k = 0;
        commit(STOCK);
        if (throttleMs != 0) {
          Thread.sleep(throttleMs);
        }
        start = this.tpccstats.startStock();
      }
    } // end for [w]
  } // end stock

  private void loadDistrict(int whseCount, int distWhseCount, GsRandom gen)
  throws InterruptedException, SQLException {
    int t = (whseCount * distWhseCount);
    Log.getLogWriter().info("Loading " + t + " Districts...");

    PreparedStatement distPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO district " +
       "(d_id, d_w_id, d_ytd, d_tax, d_next_o_id, d_name, " +
       "d_street_1, d_street_2, d_city, d_state, d_zip) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

    District district  = new District();

    long start = this.tpccstats.startDistrict();
    int throttleMs = getThrottleMs();
    for (int w = this.ttgid + 1; w <= whseCount; w += this.numThreads) {
      int k = 0;
      for (int d=1; d <= distWhseCount; d++) {
        district.d_id = d;
        district.d_w_id = w;
        district.d_ytd = 30000;
        // random within [0.0000 .. 0.2000]
        district.d_tax = (float)((jTPCCUtil.randomNumber(0,2000,gen))/10000.0);
        district.d_next_o_id = this.numCustomersPerDistrict + 1;
        district.d_name = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(6,10,gen));
        district.d_street_1 = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
        district.d_street_2 = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
        district.d_city = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
        district.d_state = jTPCCUtil.randomStr(3).toUpperCase();
        district.d_zip = "123456789";

        distPrepStmt.setLong(1, district.d_id);
        distPrepStmt.setLong(2, district.d_w_id);
        distPrepStmt.setDouble(3, district.d_ytd);
        distPrepStmt.setDouble(4, district.d_tax);
        distPrepStmt.setLong(5, district.d_next_o_id);
        distPrepStmt.setString(6, district.d_name);
        distPrepStmt.setString(7, district.d_street_1);
        distPrepStmt.setString(8, district.d_street_2);
        distPrepStmt.setString(9, district.d_city);
        distPrepStmt.setString(10, district.d_state);
        distPrepStmt.setString(11, district.d_zip);
        distPrepStmt.addBatch();
        k++;
      } // end for [d]
      distPrepStmt.executeBatch();
      distPrepStmt.clearBatch();
      this.tpccstats.endDistrict(start, k);
      k = 0;
      commit(DISTRICT);
      if (throttleMs != 0) {
        Thread.sleep(throttleMs);
      }
      start = this.tpccstats.startDistrict();
    } // end for [w]
  } // end district

  private void loadCustomer(int whseCount, int distWhseCount, int custDistCount,
                            GsRandom gen)
  throws InterruptedException, SQLException {
    int t = whseCount * distWhseCount * custDistCount;
    Log.getLogWriter().info("Loading " + t + " Customers and " + t + " Histories...");

    PreparedStatement custPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO customer " +
       "(c_id, c_d_id, c_w_id, " +
        "c_discount, c_credit, c_last, c_first, c_credit_lim, " +
        "c_balance, c_ytd_payment, c_payment_cnt, c_delivery_cnt, " +
        "c_street_1, c_street_2, c_city, c_state, c_zip, " +
        "c_phone, c_since, c_middle, c_data) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

    PreparedStatement histPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO history " +
       "(h_c_id, h_c_d_id, h_c_w_id, " +
        "h_d_id, h_w_id, " +
        "h_date, h_amount, h_data) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");

    Customer customer  = new Customer();
    History history = new History();

    if (primaries == null) { // loading from clients (includes mysql/oracle)
      // for each warehouse, load only the customers and histories for my ttgid
      startValW = 1;
      stopValW = whseCount + 1;
      incValW = 1;
      startValC = this.ttgid + 1;
      stopValC = custDistCount + 1;
      incValC = this.numThreads;
    } else { // loading from servers
      // for each warehouse corresponding to a jvm-unique primary bucket,
      // load only the customers and histories for my jid
      startValW = 0;
      stopValW = primaries.size();
      incValW = 1;
      startValC = this.jid + 1;
      stopValC = custDistCount + 1;
      incValC = RemoteTestModule.getMyNumThreads();
    }
    int k = 0;
    long start = this.tpccstats.startCustomer();
    int throttleMs = getThrottleMs();
    for (int tmp = startValW; tmp < stopValW; tmp += incValW) {
      int w = getWID(tmp);
      for (int c = startValC; c < stopValC; c += incValC) {
        for (int d=1; d <= distWhseCount; d++) {
          sysdate = new Timestamp(System.currentTimeMillis());

          customer.c_id = c;
          customer.c_d_id = d;
          customer.c_w_id = w;

          // discount is random between [0.0000 ... 0.5000]
          customer.c_discount =
                (float)(jTPCCUtil.randomNumber(1,5000,gen) / 10000.0);

          if (jTPCCUtil.randomNumber(1,100,gen) <= 10) {
            customer.c_credit = "BC";   // 10% Bad Credit
          } else {
            customer.c_credit = "GC";   // 90% Good Credit
          }
          customer.c_last = jTPCCUtil.getLastName(gen);
          customer.c_first = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(8,16,gen));
          customer.c_credit_lim = 50000;
          customer.c_balance = -10;
          customer.c_ytd_payment = 10;
          customer.c_payment_cnt = 1;
          customer.c_delivery_cnt = 0;
          customer.c_street_1 = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
          customer.c_street_2 = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
          customer.c_city = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,20,gen));
          customer.c_state = jTPCCUtil.randomStr(3).toUpperCase();
          customer.c_zip = "123456789";
          customer.c_phone = "(732)744-1700";
          customer.c_since = sysdate.getTime();
          customer.c_middle = "OE";
          customer.c_data = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(300,500,gen));

          history.h_c_id = c;
          history.h_c_d_id = d;
          history.h_c_w_id = w;
          history.h_d_id = d;
          history.h_w_id = w;
          history.h_date = sysdate.getTime();
          history.h_amount = 10;
          history.h_data = jTPCCUtil.randomStr(jTPCCUtil.randomNumber(10,24,gen));

          custPrepStmt.setLong(1, customer.c_id);
          custPrepStmt.setLong(2, customer.c_d_id);
          custPrepStmt.setLong(3, customer.c_w_id);
          custPrepStmt.setDouble(4, customer.c_discount);
          custPrepStmt.setString(5, customer.c_credit);
          custPrepStmt.setString(6, customer.c_last);
          custPrepStmt.setString(7, customer.c_first);
          custPrepStmt.setDouble(8, customer.c_credit_lim);
          custPrepStmt.setDouble(9, customer.c_balance);
          custPrepStmt.setDouble(10, customer.c_ytd_payment);
          custPrepStmt.setDouble(11, customer.c_payment_cnt);
          custPrepStmt.setDouble(12, customer.c_delivery_cnt);
          custPrepStmt.setString(13, customer.c_street_1);
          custPrepStmt.setString(14, customer.c_street_2);
          custPrepStmt.setString(15, customer.c_city);
          custPrepStmt.setString(16, customer.c_state);
          custPrepStmt.setString(17, customer.c_zip);
          custPrepStmt.setString(18, customer.c_phone);
          Timestamp since = new Timestamp(customer.c_since);
          custPrepStmt.setTimestamp(19, since);
          custPrepStmt.setString(20, customer.c_middle);
          custPrepStmt.setString(21, customer.c_data);
          custPrepStmt.addBatch();
          k++;
          if (this.logQueries) {
            Log.getLogWriter().info(
               insertOp + " INTO customer " +
                  "(c_id, c_d_id, c_w_id, c_last, c_balance, c_delivery_cnt, etc.) " + "VALUES (" +
                    customer.c_id   + ", " +
                    customer.c_d_id + ", " +
                    customer.c_w_id + ", " +
                    customer.c_last + ", " +
                    customer.c_balance + ", " +
                    customer.c_delivery_cnt + ", etc.) [batched]");
          }

          histPrepStmt.setInt(1, history.h_c_id);
          histPrepStmt.setInt(2, history.h_c_d_id);
          histPrepStmt.setInt(3, history.h_c_w_id);
          histPrepStmt.setInt(4, history.h_d_id);
          histPrepStmt.setInt(5, history.h_w_id);
          Timestamp hdate = new Timestamp(history.h_date);
          histPrepStmt.setTimestamp(6, hdate);
          histPrepStmt.setDouble(7, history.h_amount);
          histPrepStmt.setString(8, history.h_data);
          histPrepStmt.addBatch();
          k++;
          if (this.logQueries) {
            Log.getLogWriter().info(
               insertOp + " INTO history " +
                  "(h_c_id, etc.) " + "VALUES (" + history.h_c_id + ", etc.) [batched]");
          }

          if (k >= this.commitCount) {
            custPrepStmt.executeBatch();
            histPrepStmt.executeBatch();
            custPrepStmt.clearBatch();
            histPrepStmt.clearBatch();
            this.tpccstats.endCustomer(start, k);
            k = 0;
            commit(CUSTOMER);
            if (throttleMs != 0) {
              Thread.sleep(throttleMs);
            }
            start = this.tpccstats.startCustomer();
          }
        } // end for [d]
      } // end for [c]
      if (k > 0) {
        custPrepStmt.executeBatch();
        histPrepStmt.executeBatch();
        custPrepStmt.clearBatch();
        histPrepStmt.clearBatch();
        this.tpccstats.endCustomer(start, k);
        k = 0;
        commit(CUSTOMER);
        if (throttleMs != 0) {
          Thread.sleep(throttleMs);
        }
        start = this.tpccstats.startCustomer();
      }
    } // end for [w]
  } // end customer

  private void loadOrder(int whseCount, int distWhseCount, int custDistCount,
                         GsRandom gen)
  throws InterruptedException, SQLException {
    int t = (whseCount * distWhseCount * custDistCount);
    t = (t * 11) + (t / 3);
    Log.getLogWriter().info("Loading Orders for approx " + t  + " rows ...");

    PreparedStatement ordrPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO oorder " +
       "(o_id, o_w_id,  o_d_id, o_c_id, " +
        "o_carrier_id, o_ol_cnt, o_all_local, o_entry_d) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");

    PreparedStatement nworPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO new_order " +
       "(no_w_id, no_d_id, no_o_id) " +
      "VALUES (?, ?, ?)");

    PreparedStatement orlnPrepStmt = this.connection.prepareStatement
      (insertOp + " INTO order_line " +
       "(ol_w_id, ol_d_id, ol_o_id, " +
        "ol_number, ol_i_id, ol_delivery_d, " +
        "ol_amount, ol_supply_w_id, ol_quantity, ol_dist_info) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

    Oorder oorder  = new Oorder();
    NewOrder new_order  = new NewOrder();
    OrderLine order_line  = new OrderLine();

    double cutoff = TPCCPrms.getDeliveredOrderCutoff();
    double deliveredOrderCutoff = cutoff * custDistCount;
    Log.getLogWriter().info("Delivered order cutoff is " + deliveredOrderCutoff
       + " (" + cutoff + " * " + custDistCount + " customersPerDistrict)");

    // get this site's share of the primary buckets for this jvm
    List<Integer> primaries = getPrimariesToLoad();
    int startValW, stopValW, incValW, startValC, stopValC, incValC;
    if (primaries == null) { // loading from clients (includes mysql/oracle)
      // for each warehouse, load only orders for customers for my ttgid
      startValW = 1;
      stopValW = whseCount + 1;
      incValW = 1;
      startValC = this.ttgid + 1;
      stopValC = custDistCount + 1;
      incValC = this.numThreads;
    } else { // loading from servers
      // for each warehouse corresponding to a jvm-unique primary bucket,
      // load only the orders for customers for my jid
      startValW = 0;
      stopValW = primaries.size();
      incValW = 1;
      startValC = this.jid + 1;
      stopValC = custDistCount + 1;
      incValC = RemoteTestModule.getMyNumThreads();
    }
    int k = 0; int k1 = 0; int k2 = 0;
    long start = this.tpccstats.startOrder();
    int throttleMs = getThrottleMs();
    for (int tmp = startValW; tmp < stopValW; tmp += incValW) {
      int w = getWID(tmp);
      for (int c = startValC; c < stopValC; c += incValC) {
        for (int d=1; d <= distWhseCount; d++) {
          oorder.o_id = c;
          oorder.o_w_id = w;
          oorder.o_d_id = d;
          if (this.logQueries) {
            Log.getLogWriter().info(insertOp + " INTO oorder (w,d,c)" + w + " " + d + " " + c);
          }
          // assign this order to a randomly chosen customer in this w-d
          oorder.o_c_id = jTPCCUtil.randomNumber(1, custDistCount, gen);
          oorder.o_carrier_id = jTPCCUtil.randomNumber(1, 10, gen);
          oorder.o_ol_cnt = jTPCCUtil.randomNumber(5, 15, gen);
          oorder.o_all_local = 1;
          oorder.o_entry_d = System.currentTimeMillis();

          k++;
          insertOrder(ordrPrepStmt, oorder);

          // 900 rows in the NEW-ORDER table corresponding to the last
          // 900 rows in the ORDER table for that district (i.e., with
          // NO_O_ID between 2,101 and 3,000)

          if (c > deliveredOrderCutoff) {
            new_order.no_w_id = w;
            new_order.no_d_id = d;
            new_order.no_o_id = c;

            k1++;
            insertNewOrder(nworPrepStmt, new_order);
            if (this.logQueries) {
              Log.getLogWriter().info(insertOp + " INTO new_order (w,d,c)" + w + " " + d + " " + c);
            }
          } // end new order

          for (int l=1; l <= oorder.o_ol_cnt; l++) {

            order_line.ol_w_id = w;
            order_line.ol_d_id = d;
            order_line.ol_o_id = c;
            order_line.ol_number = l;   // ol_number
            order_line.ol_i_id = jTPCCUtil.randomNumber(1, this.numItems, gen);
            order_line.ol_delivery_d = oorder.o_entry_d;

            if (order_line.ol_o_id <= deliveredOrderCutoff) {
              order_line.ol_amount = 0;
            } else {
              // random within [0.01 .. 9,999.99]
              order_line.ol_amount =
                (float)(jTPCCUtil.randomNumber(1, 999999, gen) / 100.0);
            }
            order_line.ol_supply_w_id = jTPCCUtil.randomNumber(1, whseCount, gen);
            order_line.ol_quantity = 5;
            order_line.ol_dist_info = jTPCCUtil.randomStr(24);

            k2++;
            insertOrderLine(orlnPrepStmt, order_line);

            if (k + k1 + k2 >= this.commitCount) {
              ordrPrepStmt.executeBatch();
              nworPrepStmt.executeBatch();
              orlnPrepStmt.executeBatch();
              ordrPrepStmt.clearBatch();
              nworPrepStmt.clearBatch();
              orlnPrepStmt.clearBatch();
              this.tpccstats.endOrder(start, k, k1, k2);
              k = 0; k1 = 0; k2 = 0;
              commit(ORDER);
              if (throttleMs != 0) {
                Thread.sleep(throttleMs);
              }
              start = this.tpccstats.startOrder();
            }
          } // end for [l]
        } // end for [d]
      } // end for [c]
      if (k > 0 || k1 > 0 || k2 > 0) {
        if (k > 0) {
          ordrPrepStmt.executeBatch();
          ordrPrepStmt.clearBatch();
        }
        if (k1 > 0) {
          nworPrepStmt.executeBatch();
          nworPrepStmt.clearBatch();
        }
        if (k2 > 0) {
          orlnPrepStmt.executeBatch();
          orlnPrepStmt.clearBatch();
        }
        this.tpccstats.endOrder(start, k, k1, k2);
        k = 0; k1 = 0; k2 = 0;
        commit(ORDER);
        if (throttleMs != 0) {
          Thread.sleep(throttleMs);
        }
        start = this.tpccstats.startOrder();
      }
    } // end for [w]
  } // end order

  private void insertOrder(PreparedStatement ordrPrepStmt, Oorder oorder)
  throws SQLException {
    ordrPrepStmt.setInt(1, oorder.o_id);
    ordrPrepStmt.setInt(2, oorder.o_w_id);
    ordrPrepStmt.setInt(3, oorder.o_d_id);
    ordrPrepStmt.setInt(4, oorder.o_c_id);
    ordrPrepStmt.setInt(5, oorder.o_carrier_id);
    ordrPrepStmt.setInt(6, oorder.o_ol_cnt);
    ordrPrepStmt.setInt(7, oorder.o_all_local);
    Timestamp entry_d = new Timestamp(oorder.o_entry_d);
    ordrPrepStmt.setTimestamp(8, entry_d);

    ordrPrepStmt.addBatch();
  }

  private void insertNewOrder(PreparedStatement nworPrepStmt,
                              NewOrder new_order)
  throws SQLException {
    nworPrepStmt.setInt(1, new_order.no_w_id);
    nworPrepStmt.setInt(2, new_order.no_d_id);
    nworPrepStmt.setInt(3, new_order.no_o_id);

    nworPrepStmt.addBatch();
  }

  private void insertOrderLine(PreparedStatement orlnPrepStmt,
                               OrderLine order_line)
  throws SQLException {
    orlnPrepStmt.setInt(1, order_line.ol_w_id);
    orlnPrepStmt.setInt(2, order_line.ol_d_id);
    orlnPrepStmt.setInt(3, order_line.ol_o_id);
    orlnPrepStmt.setInt(4, order_line.ol_number);
    orlnPrepStmt.setLong(5, order_line.ol_i_id);

    Timestamp delivery_d = new Timestamp(order_line.ol_delivery_d);
    orlnPrepStmt.setTimestamp(6, delivery_d);

    orlnPrepStmt.setDouble(7, order_line.ol_amount);
    orlnPrepStmt.setLong(8, order_line.ol_supply_w_id);
    orlnPrepStmt.setDouble(9, order_line.ol_quantity);
    orlnPrepStmt.setString(10, order_line.ol_dist_info);

    orlnPrepStmt.addBatch();

    if (this.logQueries) {
      Log.getLogWriter().info("LoadData: " + insertOp + " INTO order_line (ol_o_id=" + order_line.ol_o_id + " ol_d_id=" + order_line.ol_d_id + " ol_w_id=" + order_line.ol_w_id + " ol_number=" + order_line.ol_number + " etc.)");
    }
  }

  protected void commit(int loadType) {
    if (this.logQueries) {
      Log.getLogWriter().info("Committing..." + loadType);
    }
    try {
      long start = this.tpccstats.startLoadCommit();
      this.connection.commit();
      this.tpccstats.endLoadCommit(start);
    } catch (SQLException e) {
      throw new QueryPerfException("Commit failed: " + e);
    }
  }

  /**
   * Takes the list of all primary buckets on this JVM and returns a subset
   * containing only this wan site's share of them.  Requires the number of
   * primaries to be evenly divisible by the number of wan sites to have an
   * even workload across all servers.  If this check becomes annoying, it
   * could be removed or made optional.
   */
  protected List<Integer> getPrimariesToLoad() {
    if (PrimaryBucketList == null) {
      return null; // either not GFXD or not a datahost
    }
    List<Integer> primaries = PrimaryBucketList.get("CUSTOMER");
    int numPrimaries = primaries.size();
    if (this.numWanSites == 1) {
      return primaries;
    } else {
      if (this.numWanSites > numPrimaries) {
        String s = "There are more wan sites (" + this.numWanSites
                 + " than primary buckets " + numPrimaries + " for this JVM";
        throw new HydraRuntimeException(s);
      }
      if (numPrimaries % this.numWanSites != 0) {
        String s = "The number of wan sites (" + this.numWanSites
                 + " does not evenly divide the number of primary buckets "
                 + numPrimaries + " for this JVM";
        throw new HydraRuntimeException(s);
      }
      List<Integer> subprimaries = new ArrayList();
      for (int i = toWanSite() - 1; i < numPrimaries; i += this.numWanSites) {
        subprimaries.add(primaries.get(i));
      }
      return subprimaries;
    }
  }
}
