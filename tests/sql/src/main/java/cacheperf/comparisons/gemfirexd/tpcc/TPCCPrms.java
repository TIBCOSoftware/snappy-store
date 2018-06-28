/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package cacheperf.comparisons.gemfirexd.tpcc;

import hydra.BasePrms;
import hydra.HydraConfigException;

public class TPCCPrms extends BasePrms {

  static {
    setValues(TPCCPrms.class);
  }

  public static final int DEFAULT_NUM_WAREHOUSES = 10;
  public static final int DEFAULT_NUM_DISTRICTS_PER_WAREHOUSE = 10;
  public static final int DEFAULT_NUM_ITEMS = 100000;
  public static final int DEFAULT_NUM_CUSTOMERS_PER_DISTRICT = 3000;

  /**
   * (int)
   * Number of warehouses (the default of {@link #DEFAULT_NUM_WAREHOUSES} yields
   * about 1 GB data).
   */
  public static Long numWarehouses;
  public static int getNumWarehouses() {
    Long key = numWarehouses;
    return tab().intAt(key, DEFAULT_NUM_WAREHOUSES);
  }

  /**
   * (int)
   * Number of items.  Defaults to {@link #DEFAULT_NUM_ITEMS} (tpc-c standard).
   * Must be greater than {@link #itemBase}.
   */
  public static Long numItems;
  public static int getNumItems() {
    Long key = numItems;
    return tab().intAt(key, DEFAULT_NUM_ITEMS);
  }

  /**
   * (int)
   * Number of districts per warehouse.  Defaults to {@link
   * #DEFAULT_NUM_DISTRICTS_PER_WAREHOUSE} tpc-c standard).
   */
  public static Long numDistrictsPerWarehouse;
  public static int getNumDistrictsPerWarehouse() {
    Long key = numDistrictsPerWarehouse;
    int val = tab().intAt(key, DEFAULT_NUM_DISTRICTS_PER_WAREHOUSE);
    if (val != DEFAULT_NUM_DISTRICTS_PER_WAREHOUSE) {
      String s = BasePrms.nameForKey(key) + " is fixed at "
               + DEFAULT_NUM_DISTRICTS_PER_WAREHOUSE
               + " and cannot be set to " + val
               + " without modifying the Stock table and test code.";
      throw new HydraConfigException(s);
    }
    return DEFAULT_NUM_DISTRICTS_PER_WAREHOUSE;
  }

  /**
   * (int)
   * Number of customers per district.  Defaults to 3000 (tpc-c standard).
   * Must be greater than {@link #customerBase}.
   */
  public static Long numCustomersPerDistrict;
  public static int getNumCustomersPerDistrict() {
    Long key = numCustomersPerDistrict;
    return tab().intAt(key, DEFAULT_NUM_CUSTOMERS_PER_DISTRICT);
  }

  /**
   * (int)
   * Item base for non-uniform random number generation.  Defaults to 8191
   * (tpc-c standard).
   */
  public static Long itemBase;
  public static int getItemBase() {
    Long key = itemBase;
    return tab().intAt(key, 8191);
  }

  /**
   * (int)
   * Customer base for non-uniform random number generation.  Defaults to 1023
   * (tpc-c standard).
   */
  public static Long customerBase;
  public static int getCustomerBase() {
    Long key = customerBase;
    return tab().intAt(key, 1023);
  }

  /**
   * (int)
   * New order transaction weight.  Defaults to 20.
   */
  public static Long newOrderWeight;
  public static int getNewOrderWeight() {
    Long key = newOrderWeight;
    return tab().intAt(key, 20);
  }

  /**
   * (int)
   * Payment transaction weight.  Defaults to 20.
   */
  public static Long paymentWeight;
  public static int getPaymentWeight() {
    Long key = paymentWeight;
    return tab().intAt(key, 20);
  }

  /**
   * (int)
   * Stock level transaction weight.  Defaults to 2.
   */
  public static Long stockLevelWeight;
  public static int getStockLevelWeight() {
    Long key = stockLevelWeight;
    return tab().intAt(key, 2);
  }

  /**
   * (int)
   * Order status transaction weight.  Defaults to 2.
   */
  public static Long orderStatusWeight;
  public static int getOrderStatusWeight() {
    Long key = orderStatusWeight;
    return tab().intAt(key, 2);
  }

  /**
   * (int)
   * Delivery transaction weight.  Defaults to 2.
   */
  public static Long deliveryWeight;
  public static int getDeliveryWeight() {
    Long key = deliveryWeight;
    return tab().intAt(key, 2);
  }

  /**
   * (int)
   * Disk store file number to use.  Optional.
   */
  public static Long diskStoreFileNum;
  public static Integer getDiskStoreFileNum() {
    Long key = diskStoreFileNum;
    int val = tasktab().intAt(key, tab().intAt(key, -1));
    return (val == -1) ? null : val;
  }

  /**
   * (int)
   * Table file number to use.
   */
  public static Long tableFileNum;
  public static int getTableFileNum() {
    Long key = tableFileNum;
    return tasktab().intAt(key, tab().intAt(key));
  }

  /**
   * (int)
   * Index file number to use.
   */
  public static Long indexFileNum;
  public static int getIndexFileNum() {
    Long key = indexFileNum;
    return tasktab().intAt(key, tab().intAt(key));
  }

  /**
   * (boolean)
   * Whether to really create indexes in the index task.  Defaults to true.
   */
  public static Long createIndexes;
  public static boolean createIndexes() {
    Long key = createIndexes;
    return tasktab().booleanAt(key, tab().booleanAt(key, true));
  }

  /**
   * (boolean)
   * Whether to use PUT DML to do inserts. Defaults to false.
   */
  public static Long usePutDML;
  public static boolean usePutDML() {
    Long key = usePutDML;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (int)
   * Number of records in commit batch when loading data.  Defaults to 1000.
   */
  public static Long commitCount;
  public static int getCommitCount() {
    Long key = commitCount;
    int val = tab().intAt(key, 1000);
    if (val <= 0) {
      String s = BasePrms.nameForKey(commitCount)
               + " has an illegal value: " + val;
      throw new HydraConfigException(s);
    }
    return val;
  }

  /**
   * (double)
   * Percentage of orders that are pre-delivered as part of data loading.
   * Orders beyond this point are considered undelivered new orders.
   * Defaults to 0.7 (tpc-c standard).
   */
  public static Long deliveredOrderCutoff;
  public static double getDeliveredOrderCutoff() {
    Long key = deliveredOrderCutoff;
    return tab().doubleAt(key, 0.7);
  }

  /**
   * (String)
   * Group by for delivSumOrderAmount statement.  Default to none.
   * Valid values are "warehouse" and "district".
   */
  public static Long delivSumOrderAmountGroupBy;
  public static String getDelivSumOrderAmountGroupBy() {
    Long key = delivSumOrderAmountGroupBy;
    String val = tab().stringAt(key, "none").toLowerCase();
    if (!val.equals("none") && !val.equals("warehouse") && !val.equals("district")) {
      String s = "Illegal value for " + BasePrms.nameForKey(delivSumOrderAmountGroupBy);
      throw new HydraConfigException(s);
    }
    return val;
  }

  /**
   * (boolean)
   * Whether to use scrollable result sets in queries that support them.
   * Defaults to false.
   */
  public static Long useScrollableResultSets;
  public static boolean useScrollableResultSets() {
    Long key = useScrollableResultSets;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to record statement statistics.  Defaults to false.
   */
  public static Long timeStmts;
  public static boolean timeStmts() {
    Long key = timeStmts;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to omit the payUpdateDist statement.  Defaults to false.
   */
  public static Long omitPayUpdateDist;
  public static boolean omitPayUpdateDist() {
    Long key = omitPayUpdateDist;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to omit the payUpdateWhse statement.  Defaults to false.
   */
  public static Long omitPayUpdateWhse;
  public static boolean omitPayUpdateWhse() {
    Long key = omitPayUpdateWhse;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to optimize the delivGetOrderId statement.  Defaults to false.
   */
  public static Long optimizeDelivGetOrderId;
  public static boolean optimizeDelivGetOrderId() {
    Long key = optimizeDelivGetOrderId;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to read all results in the delivGetOrderId statement.  Defaults to false.
   */
  public static Long readAllDelivGetOrderId;
  public static boolean readAllDelivGetOrderId() {
    Long key = readAllDelivGetOrderId;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to allow remote suppliers in the new order transaction.
   * Defaults to false.
   * <p>
   * When true, the new order transaction is a distributed transaction about
   * 10% of the time.  That is, 1% of the suppliers in order lines are remote
   * warehouses, and there are an average of 10 order lines per transaction.
   */
  public static Long allowRemoteSuppliers;
  public static boolean allowRemoteSuppliers() {
    Long key = allowRemoteSuppliers;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (int)
   * Number of milliseconds to sleep between transactions.  Defaults to 0.
   */
  public static Long throttleMs;
  public static int getThrottleMs() {
    Long key = throttleMs;
    return tasktab().intAt(key, tab().intAt(key, 0));
  }

  /**
   * (String)
   * Name of TRANSACTIONS trim interval to use.
   */
  public static Long txTrimInterval;
  public static String getTxTrimInterval() {
    Long key = txTrimInterval;
    return tasktab().stringAt(key, tab().stringAt(key, null));
  }

  /**
   * (String)
   * Script to start/stop in script tasks.  Defaults to null.
   * Only one script is allowed per test run.
   */
  public static Long script;
  public static String getScript() {
    Long key = script;
    return tab().stringAt(key, null);
  }

  /**
   * (boolean)
   * Whether to use off heap memory for partitioned tables. Defaults to false.
   */
  public static Long useOffHeapMemoryPR;
  public static boolean useOffHeapMemoryPR() {
    Long key = useOffHeapMemoryPR;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to use HDFS stores for partitioned tables. Defaults to false.
   */
  public static Long useHDFSStorePR;
  public static boolean useHDFSStorePR() {
    Long key = useHDFSStorePR;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to use HDFS stores for write-only data. Defaults to false.
   */
  public static Long useHDFSWriteOnly;
  public static boolean useHDFSWriteOnly() {
    Long key = useHDFSWriteOnly;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * (boolean)
   * Whether to query HDFS stores for HDFS_PARTITIONED tables. Defaults to false.
   */
  public static Long queryHDFS;
  public static boolean queryHDFS() {
    Long key = queryHDFS;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**
   * Whether to flush HDFS queues and run compaction in tasks that check this
   * parameter. Defaults to false.
   */
  public static Long hdfsFlushAndCompact;
  public static boolean getHDFSFlushAndCompact() {
    Long key = hdfsFlushAndCompact;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }
}
