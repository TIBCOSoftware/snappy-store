/*=========================================================================
 * Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *========================================================================
 */

package cacheperf.comparisons.gemfirexd.tpcc;

import cacheperf.comparisons.gemfirexd.QueryPerfException;
import com.gemstone.gemfire.StatisticDescriptor;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.internal.NanoTimer;
import perffmwk.HistogramStats;
import perffmwk.PerformanceStatistics;

/**
 * Implements statistics related to TPCC.
 */
public class TPCCStats extends PerformanceStatistics implements TxTypes {

  public static enum Stmt {
    delivDeleteNewOrder, delivGetCustId, delivGetOrderId, delivSumOrderAmount,
    delivUpdateCarrierId, delivUpdateCustBalDelivCnt, delivUpdateDeliveryDate,
    ordStatCountCust, ordStatGetCust, ordStatGetCustBal, ordStatGetNewestOrd,
    ordStatGetOrder, ordStatGetOrderLines,
    payCountCust, payCursorCustByName, payGetCust, payGetCustCdata, payGetDist,
    payGetWhse, payInsertHist, payUpdateCustBal, payUpdateCustBalCdata,
    payUpdateDist, payUpdateWhse,
    stmtGetCustWhse, stmtGetDist, stmtGetItem, stmtGetStock, stmtInsertNewOrder,
    stmtInsertOOrder, stmtInsertOrderLine, stmtUpdateDist, stmtUpdateStock,
    stockGetCountStock, stockGetDistOrderId;
  }

  /** <code>TPCCStats</code> are maintained on a per-thread basis */
  private static final int SCOPE = THREAD_SCOPE;

  public static final String VM_COUNT = "vmCount";

  protected static final String CUSTOMERS       = "customers";
  protected static final String CUSTOMER_TIME   = "customerTime";

  protected static final String DISTRICTS       = "districts";
  protected static final String DISTRICT_TIME   = "districtTime";

  protected static final String ITEMS           = "items";
  protected static final String ITEM_TIME       = "itemTime";

  protected static final String ORDERS          = "orders";
  protected static final String NEW_ORDERS      = "newOrders";
  protected static final String ORDER_LINES     = "orderLines";
  protected static final String ORDER_TIME      = "orderTime";

  protected static final String STOCKS          = "stocks";
  protected static final String STOCK_TIME      = "stockTime";

  protected static final String WAREHOUSES      = "warehouses";
  protected static final String WAREHOUSE_TIME  = "warehouseTime";

  protected static final String LOAD_COMMITS     = "loadCommits";
  protected static final String LOAD_COMMIT_TIME = "loadCommitTime";

  protected static final String TX_CARD_IN_PROGRESS = "txCardInProgress";
  protected static final String TX_TYPE_IN_PROGRESS = "txTypeInProgress";

  protected static final String DELIVERY_TX_IN_PROGRESS = "deliveryTxInProgress";
  protected static final String DELIVERY_TX_COMPLETED = "deliveryTxCompleted";
  protected static final String DELIVERY_TX_TIME = "deliveryTxTime";
  protected static final String DELIVERY_TX_COMMIT_TIME = "deliveryTxCommitTime";
  protected static final String DELIVERY_TX_ABORTED = "deliveryTxAborted";
  protected static final String DELIVERY_TX_ABORT_TIME = "deliveryTxAbortTime";
  protected static final String DELIVERY_TX_ROLLED_BACK = "deliveryTxRolledBack";
  protected static final String DELIVERY_TX_ROLLBACK_TIME = "deliveryTxRollbackTime";

  protected static final String NEW_ORDER_TX_IN_PROGRESS = "newOrderTxInProgress";
  protected static final String NEW_ORDER_TX_COMPLETED = "newOrderTxCompleted";
  protected static final String NEW_ORDER_TX_TIME = "newOrderTxTime";
  protected static final String NEW_ORDER_TX_COMMIT_TIME = "newOrderTxCommitTime";
  protected static final String NEW_ORDER_TX_RESTARTED = "newOrderTxRestarted";
  protected static final String NEW_ORDER_TX_RESTART_TIME = "newOrderTxRestartTime";
  protected static final String NEW_ORDER_TX_ABORTED = "newOrderTxAborted";
  protected static final String NEW_ORDER_TX_ABORT_TIME = "newOrderTxAbortTime";
  protected static final String NEW_ORDER_TX_ROLLED_BACK = "newOrderTxRolledBack";
  protected static final String NEW_ORDER_TX_ROLLBACK_TIME = "newOrderTxRollbackTime";

  protected static final String ORDER_STATUS_TX_IN_PROGRESS = "orderStatusTxInProgress";
  protected static final String ORDER_STATUS_TX_COMPLETED = "orderStatusTxCompleted";
  protected static final String ORDER_STATUS_TX_TIME = "orderStatusTxTime";
  protected static final String ORDER_STATUS_TX_NOT_FOUND = "orderStatusTxNotFound";
  protected static final String ORDER_STATUS_TX_NOT_FOUND_TIME = "orderStatusTxNotFoundTime";

  protected static final String PAYMENT_TX_IN_PROGRESS = "paymentTxInProgress";
  protected static final String PAYMENT_TX_COMPLETED = "paymentTxCompleted";
  protected static final String PAYMENT_TX_TIME = "paymentTxTime";
  protected static final String PAYMENT_TX_COMMIT_TIME = "paymentTxCommitTime";
  protected static final String PAYMENT_TX_ABORTED = "paymentTxAborted";
  protected static final String PAYMENT_TX_ABORT_TIME = "paymentTxAbortTime";
  protected static final String PAYMENT_TX_ROLLED_BACK = "paymentTxRolledBack";
  protected static final String PAYMENT_TX_ROLLBACK_TIME = "paymentTxRollbackTime";

  protected static final String STOCK_LEVEL_TX_IN_PROGRESS = "stockLevelTxInProgress";
  protected static final String STOCK_LEVEL_TX_COMPLETED = "stockLevelTxCompleted";
  protected static final String STOCK_LEVEL_TX_TIME = "stockLevelTxTime";

  protected static final String delivDeleteNewOrder_stmt = "delivDeleteNewOrderStmtsExecuted";
  protected static final String delivDeleteNewOrder_stmt_time = "delivDeleteNewOrderStmtTime";
  protected static final String delivGetCustId_stmt = "delivGetCustIdStmtsExecuted";
  protected static final String delivGetCustId_stmt_time = "delivGetCustIdStmtTime";
  protected static final String delivGetOrderId_stmt = "delivGetOrderIdStmtsExecuted";
  protected static final String delivGetOrderId_stmt_time = "delivGetOrderIdStmtTime";
  protected static final String delivGetOrderId_stmt_not_found = "delivGetOrderIdStmtNotFound";
  protected static final String delivGetOrderId_stmt_not_found_time = "delivGetOrderIdStmtNotFoundTime";
  protected static final String delivSumOrderAmount_stmt = "delivSumOrderAmountStmtsExecuted";
  protected static final String delivSumOrderAmount_stmt_time = "delivSumOrderAmountStmtTime";
  protected static final String delivSumOrderAmount_stmt_not_found = "delivSumOrderAmountStmtNotFound";
  protected static final String delivSumOrderAmount_stmt_not_found_time = "delivSumOrderAmountStmtNotFoundTime";
  protected static final String delivUpdateCarrierId_stmt = "delivUpdateCarrierIdStmtsExecuted";
  protected static final String delivUpdateCarrierId_stmt_time = "delivUpdateCarrierIdStmtTime";
  protected static final String delivUpdateCustBalDelivCnt_stmt = "delivUpdateCustBalDelivCntStmtsExecuted";
  protected static final String delivUpdateCustBalDelivCnt_stmt_time = "delivUpdateCustBalDelivCntStmtTime";
  protected static final String delivUpdateDeliveryDate_stmt = "delivUpdateDeliveryDateStmtsExecuted";
  protected static final String delivUpdateDeliveryDate_stmt_time = "delivUpdateDeliveryDateStmtTime";
  protected static final String ordStatCountCust_stmt = "ordStatCountCustStmtsExecuted";
  protected static final String ordStatCountCust_stmt_time = "ordStatCountCustStmtTime";
  protected static final String ordStatCountCust_stmt_not_found = "ordStatCountCustStmtNotFound";
  protected static final String ordStatCountCust_stmt_not_found_time = "ordStatCountCustStmtNotFoundTime";
  protected static final String ordStatGetCust_stmt = "ordStatGetCustStmtsExecuted";
  protected static final String ordStatGetCust_stmt_time = "ordStatGetCustStmtTime";
  protected static final String ordStatGetCust_stmt_not_found = "ordStatGetCustStmtNotFound";
  protected static final String ordStatGetCust_stmt_not_found_time = "ordStatGetCustStmtNotFoundTime";
  protected static final String ordStatGetCustBal_stmt = "ordStatGetCustBalStmtsExecuted";
  protected static final String ordStatGetCustBal_stmt_time = "ordStatGetCustBalStmtTime";
  protected static final String ordStatGetNewestOrd_stmt = "ordStatGetNewestOrdStmtsExecuted";
  protected static final String ordStatGetNewestOrd_stmt_time = "ordStatGetNewestOrdStmtTime";
  protected static final String ordStatGetNewestOrd_stmt_not_found = "ordStatGetNewestOrdStmtsNotFound";
  protected static final String ordStatGetNewestOrd_stmt_not_found_time = "ordStatGetNewestOrdStmtNotFoundTime";
  protected static final String ordStatGetOrder_stmt = "ordStatGetOrderStmtsExecuted";
  protected static final String ordStatGetOrder_stmt_time = "ordStatGetOrderStmtTime";
  protected static final String ordStatGetOrderLines_stmt = "ordStatGetOrderLinesStmtsExecuted";
  protected static final String ordStatGetOrderLines_stmt_time = "ordStatGetOrderLinesStmtTime";
  protected static final String payCountCust_stmt = "payCountCustStmtsExecuted";
  protected static final String payCountCust_stmt_time = "payCountCustStmtTime";
  protected static final String payCursorCustByName_stmt = "payCursorCustByNameStmtsExecuted";
  protected static final String payCursorCustByName_stmt_time = "payCursorCustByNameStmtTime";
  protected static final String payCursorCustByName_stmt_not_found = "payCursorCustByNameStmtNotFound";
  protected static final String payCursorCustByName_stmt_not_found_time = "payCursorCustByNameStmtNotFoundTime";
  protected static final String payGetCust_stmt = "payGetCustStmtsExecuted";
  protected static final String payGetCust_stmt_time = "payGetCustStmtTime";
  protected static final String payGetCustCdata_stmt = "payGetCustCdataStmtsExecuted";
  protected static final String payGetCustCdata_stmt_time = "payGetCustCdataStmtTime";
  protected static final String payGetDist_stmt = "payGetDistStmtsExecuted";
  protected static final String payGetDist_stmt_time = "payGetDistStmtTime";
  protected static final String payGetWhse_stmt = "payGetWhseStmtsExecuted";
  protected static final String payGetWhse_stmt_time = "payGetWhseStmtTime";
  protected static final String payInsertHist_stmt = "payInsertHistStmtsExecuted";
  protected static final String payInsertHist_stmt_time = "payInsertHistStmtTime";
  protected static final String payUpdateCustBal_stmt = "payUpdateCustBalStmtsExecuted";
  protected static final String payUpdateCustBal_stmt_time = "payUpdateCustBalStmtTime";
  protected static final String payUpdateCustBalCdata_stmt = "payUpdateCustBalCdataStmtsExecuted";
  protected static final String payUpdateCustBalCdata_stmt_time = "payUpdateCustBalCdataStmtTime";
  protected static final String payUpdateDist_stmt = "payUpdateDistStmtsExecuted";
  protected static final String payUpdateDist_stmt_time = "payUpdateDistStmtTime";
  protected static final String payUpdateWhse_stmt = "payUpdateWhseStmtsExecuted";
  protected static final String payUpdateWhse_stmt_time = "payUpdateWhseStmtTime";
  protected static final String stmtGetCustWhse_stmt = "stmtGetCustWhseStmtsExecuted";
  protected static final String stmtGetCustWhse_stmt_time = "stmtGetCustWhseStmtTime";
  protected static final String stmtGetDist_stmt = "stmtGetDistStmtsExecuted";
  protected static final String stmtGetDist_stmt_time = "stmtGetDistStmtTime";
  protected static final String stmtGetItem_stmt = "stmtGetItemStmtsExecuted";
  protected static final String stmtGetItem_stmt_time = "stmtGetItemStmtTime";
  protected static final String stmtGetStock_stmt = "stmtGetStockStmtsExecuted";
  protected static final String stmtGetStock_stmt_time = "stmtGetStockStmtTime";
  protected static final String stmtInsertNewOrder_stmt = "stmtInsertNewOrderStmtsExecuted";
  protected static final String stmtInsertNewOrder_stmt_time = "stmtInsertNewOrderStmtTime";
  protected static final String stmtInsertOOrder_stmt = "stmtInsertOOrderStmtsExecuted";
  protected static final String stmtInsertOOrder_stmt_time = "stmtInsertOOrderStmtTime";
  protected static final String stmtInsertOrderLine_stmt = "stmtInsertOrderLineStmtsExecuted";
  protected static final String stmtInsertOrderLine_stmt_time = "stmtInsertOrderLineStmtTime";
  protected static final String stmtUpdateDist_stmt = "stmtUpdateDistStmtsExecuted";
  protected static final String stmtUpdateDist_stmt_time = "stmtUpdateDistStmtTime";
  protected static final String stmtUpdateStock_stmt = "stmtUpdateStockStmtsExecuted";
  protected static final String stmtUpdateStock_stmt_time = "stmtUpdateStockStmtTime";
  protected static final String stockGetCountStock_stmt = "stockGetCountStockStmtsExecuted";
  protected static final String stockGetCountStock_stmt_time = "stockGetCountStockStmtTime";
  protected static final String stockGetDistOrderId_stmt = "stockGetDistOrderIdStmtsExecuted";
  protected static final String stockGetDistOrderId_stmt_time = "stockGetDistOrderIdStmtTime";

  protected static final String numRemoteSuppliers = "numRemoteSuppliers";
  protected static final String numDistributedTx = "numDistributedTx";

  protected static final String SERVER_STARTUPS = "serverStartups";
  protected static final String SERVER_STARTUP_TIME = "serverStartupTime";

  ////////////////////////  Static Methods  ////////////////////////

  /**
   * Returns the statistic descriptors for <code>TPCCStats</code>.
   */
  public static StatisticDescriptor[] getStatisticDescriptors() {
    boolean largerIsBetter = true;
    return new StatisticDescriptor[] {
      factory().createIntGauge
      (
        VM_COUNT,
        "When aggregated, the number of VMs using this statistics object.",
        "VMs"
      ),
      factory().createIntCounter
      (
        CUSTOMERS,
        "Number of customers created.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        CUSTOMER_TIME,
        "Total time spent creating customers.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        DISTRICTS,
        "Number of districts created.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        DISTRICT_TIME,
        "Total time spent creating districts.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ITEMS,
        "Number of items created.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ITEM_TIME,
        "Total time spent creating items.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ORDERS,
        "Number of orders created.",
        "operations",
        largerIsBetter
      ),
      factory().createIntCounter
      (
        NEW_ORDERS,
        "Number of new orders created.",
        "operations",
        largerIsBetter
      ),
      factory().createIntCounter
      (
        ORDER_LINES,
        "Number of order lines created.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ORDER_TIME,
        "Total time spent creating orders.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        STOCKS,
        "Number of stocks created.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        STOCK_TIME,
        "Total time spent creating stocks.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        WAREHOUSES,
        "Number of warehouses created.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        WAREHOUSE_TIME,
        "Total time spent creating warehouses.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntGauge
      (
        TX_CARD_IN_PROGRESS,
        "The number of the transaction card in progress.",
        "card number"
      ),
      factory().createIntGauge
      (
        TX_TYPE_IN_PROGRESS,
        "The type of transaction in progress: 1=PAYMENT, 2=STOCK_LEVEL, 3=ORDER_STATUS, 4=DELIVERY, 5=NEW_ORDER.",
        "transaction type"
      ),
      factory().createIntGauge
      (
        DELIVERY_TX_IN_PROGRESS,
        "The number of delivery transactions in progress.",
        "operations"
      ),
      factory().createIntCounter
      (
        DELIVERY_TX_COMPLETED,
        "Number of delivery transactions completed (committed).",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        DELIVERY_TX_TIME,
        "Total time spent on delivery transactions that were completed (committed).",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        DELIVERY_TX_COMMIT_TIME,
        "Total time spent committing delivery transactions.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        DELIVERY_TX_ABORTED,
        "Number of delivery transactions aborted.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        DELIVERY_TX_ABORT_TIME,
        "Total time spent on delivery transactions that were aborted.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        DELIVERY_TX_ROLLED_BACK,
        "Number of delivery transactions rolled back.",
        "operations",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        DELIVERY_TX_ROLLBACK_TIME,
        "Total time spent on delivery transactions that were rolled back.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntGauge
      (
        NEW_ORDER_TX_IN_PROGRESS,
        "The number of new order transactions in progress.",
        "operations"
      ),
      factory().createIntCounter
      (
        NEW_ORDER_TX_COMPLETED,
        "Number of new order transactions completed (committed).",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        NEW_ORDER_TX_TIME,
        "Total time spent on new order transactions that were completed (committed).",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        NEW_ORDER_TX_COMMIT_TIME,
        "Total time spent committing new order transactions.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        NEW_ORDER_TX_RESTARTED,
        "Number of new order transactions restarted due to a duplicate order id.",
        "operations",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        NEW_ORDER_TX_RESTART_TIME,
        "Total time spent on new order transactions that were restarted due to a duplicate order id.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        NEW_ORDER_TX_ABORTED,
        "Number of new order transactions aborted.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        NEW_ORDER_TX_ABORT_TIME,
        "Total time spent on new order transactions that were aborted.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        NEW_ORDER_TX_ROLLED_BACK,
        "Number of new order transactions rolled back.",
        "operations",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        NEW_ORDER_TX_ROLLBACK_TIME,
        "Total time spent on new order transactions that were rolled back.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntGauge
      (
        ORDER_STATUS_TX_IN_PROGRESS,
        "The number of order status transactions in progress.",
        "operations"
      ),
      factory().createIntCounter
      (
        ORDER_STATUS_TX_COMPLETED,
        "Number of order status transactions completed.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ORDER_STATUS_TX_TIME,
        "Total time spent on order status transactions that were completed.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ORDER_STATUS_TX_NOT_FOUND,
        "Number of order status transactions not found.",
        "operations",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        ORDER_STATUS_TX_NOT_FOUND_TIME,
        "Total time spent on order status transactions that were not found.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntGauge
      (
        PAYMENT_TX_IN_PROGRESS,
        "The number of payment transactions in progress.",
        "operations"
      ),
      factory().createIntCounter
      (
        PAYMENT_TX_COMPLETED,
        "Number of payment transactions completed (committed).",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        PAYMENT_TX_TIME,
        "Total time spent on payment transactions that were completed (committed).",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        PAYMENT_TX_COMMIT_TIME,
        "Total time spent committing payment transactions.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        PAYMENT_TX_ABORTED,
        "Number of payment transactions aborted.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        PAYMENT_TX_ABORT_TIME,
        "Total time spent on payment transactions that were aborted.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        PAYMENT_TX_ROLLED_BACK,
        "Number of payment transactions rolled back.",
        "operations",
        !largerIsBetter
      ),
      factory().createLongCounter
      (
        PAYMENT_TX_ROLLBACK_TIME,
        "Total time spent on payment transactions that were rolled back.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntGauge
      (
        STOCK_LEVEL_TX_IN_PROGRESS,
        "The number of stock level transactions in progress.",
        "operations"
      ),
      factory().createIntCounter
      (
        STOCK_LEVEL_TX_COMPLETED,
        "Number of stock level transactions completed.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        STOCK_LEVEL_TX_TIME,
        "Total time spent on stock level transactions that were completed.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        LOAD_COMMITS,
        "Number of statements committed during loading.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        LOAD_COMMIT_TIME,
        "Total time spent committing during loading.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivDeleteNewOrder_stmt,
        "Number of delivDeleteNewOrder statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivDeleteNewOrder_stmt_time,
        "Total time spent executing delivDeleteNewOrder statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivGetCustId_stmt,
        "Number of delivGetCustId statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivGetCustId_stmt_time,
        "Total time spent executing delivGetCustId statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivGetOrderId_stmt,
        "Number of delivGetOrderId statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivGetOrderId_stmt_time,
        "Total time spent executing delivGetOrderId statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivGetOrderId_stmt_not_found,
        "Number of delivGetOrderId statements with data not found.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivGetOrderId_stmt_not_found_time,
        "Total time spent executing delivGetOrderId statements with data not found.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivSumOrderAmount_stmt_not_found,
        "Number of delivSumOrderAmount statements with data not found.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivSumOrderAmount_stmt_not_found_time,
        "Total time spent executing delivSumOrderAmount statements with data not found.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivSumOrderAmount_stmt,
        "Number of delivSumOrderAmount statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivSumOrderAmount_stmt_time,
        "Total time spent executing delivSumOrderAmount statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivUpdateCarrierId_stmt,
        "Number of delivUpdateCarrierId statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivUpdateCarrierId_stmt_time,
        "Total time spent executing delivUpdateCarrierId statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivUpdateCustBalDelivCnt_stmt,
        "Number of delivUpdateCustBalDelivCnt statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivUpdateCustBalDelivCnt_stmt_time,
        "Total time spent executing delivUpdateCustBalDelivCnt statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        delivUpdateDeliveryDate_stmt,
        "Number of delivUpdateDeliveryDate statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        delivUpdateDeliveryDate_stmt_time,
        "Total time spent executing delivUpdateDeliveryDate statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatCountCust_stmt,
        "Number of ordStatCountCust statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatCountCust_stmt_time,
        "Total time spent executing ordStatCountCust statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatCountCust_stmt_not_found,
        "Number of ordStatCountCust statements with data not found.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatCountCust_stmt_not_found_time,
        "Total time spent executing ordStatCountCust statements with data not found.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatGetCust_stmt,
        "Number of ordStatGetCust statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatGetCust_stmt_time,
        "Total time spent executing ordStatGetCust statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatGetCust_stmt_not_found,
        "Number of ordStatGetCust statements with data not found.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatGetCust_stmt_not_found_time,
        "Total time spent executing ordStatGetCust statements with data not found.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatGetCustBal_stmt,
        "Number of ordStatGetCustBal statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatGetCustBal_stmt_time,
        "Total time spent executing ordStatGetCustBal statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatGetNewestOrd_stmt,
        "Number of ordStatGetNewestOrd statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatGetNewestOrd_stmt_time,
        "Total time spent executing ordStatGetNewestOrd statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatGetNewestOrd_stmt_not_found,
        "Number of ordStatGetNewestOrd statements with data not found.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatGetNewestOrd_stmt_not_found_time,
        "Total time spent executing ordStatGetNewestOrd statements with data not found.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatGetOrder_stmt,
        "Number of ordStatGetOrder statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatGetOrder_stmt_time,
        "Total time spent executing ordStatGetOrder statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        ordStatGetOrderLines_stmt,
        "Number of ordStatGetOrderLines statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        ordStatGetOrderLines_stmt_time,
        "Total time spent executing ordStatGetOrderLines statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payCountCust_stmt,
        "Number of payCountCust statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payCountCust_stmt_time,
        "Total time spent executing payCountCust statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payCursorCustByName_stmt,
        "Number of payCursorCustByName statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payCursorCustByName_stmt_time,
        "Total time spent executing payCursorCustByName statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payCursorCustByName_stmt_not_found,
        "Number of payCursorCustByName statements with data not found.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payCursorCustByName_stmt_not_found_time,
        "Total time spent executing payCursorCustByName statements with data not found.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payGetCust_stmt,
        "Number of payGetCust statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payGetCust_stmt_time,
        "Total time spent executing payGetCust statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payGetCustCdata_stmt,
        "Number of payGetCustCdata statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payGetCustCdata_stmt_time,
        "Total time spent executing payGetCustCdata statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payGetDist_stmt,
        "Number of payGetDist statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payGetDist_stmt_time,
        "Total time spent executing payGetDist statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payGetWhse_stmt,
        "Number of payGetWhse statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payGetWhse_stmt_time,
        "Total time spent executing payGetWhse statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payInsertHist_stmt,
        "Number of payInsertHist statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payInsertHist_stmt_time,
        "Total time spent executing payInsertHist statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payUpdateCustBal_stmt,
        "Number of payUpdateCustBal statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payUpdateCustBal_stmt_time,
        "Total time spent executing payUpdateCustBal statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payUpdateCustBalCdata_stmt,
        "Number of payUpdateCustBalCdata statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payUpdateCustBalCdata_stmt_time,
        "Total time spent executing payUpdateCustBalCdata statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payUpdateDist_stmt,
        "Number of payUpdateDist statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payUpdateDist_stmt_time,
        "Total time spent executing payUpdateDist statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        payUpdateWhse_stmt,
        "Number of payUpdateWhse statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        payUpdateWhse_stmt_time,
        "Total time spent executing payUpdateWhse statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtGetCustWhse_stmt,
        "Number of stmtGetCustWhse statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtGetCustWhse_stmt_time,
        "Total time spent executing stmtGetCustWhse statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtGetDist_stmt,
        "Number of stmtGetDist statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtGetDist_stmt_time,
        "Total time spent executing stmtGetDist statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtGetItem_stmt,
        "Number of stmtGetItem statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtGetItem_stmt_time,
        "Total time spent executing stmtGetItem statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtGetStock_stmt,
        "Number of stmtGetStock statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtGetStock_stmt_time,
        "Total time spent executing stmtGetStock statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtInsertNewOrder_stmt,
        "Number of stmtInsertNewOrder statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtInsertNewOrder_stmt_time,
        "Total time spent executing stmtInsertNewOrder statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtInsertOOrder_stmt,
        "Number of stmtInsertOOrder statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtInsertOOrder_stmt_time,
        "Total time spent executing stmtInsertOOrder statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtInsertOrderLine_stmt,
        "Number of stmtInsertOrderLine statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtInsertOrderLine_stmt_time,
        "Total time spent executing stmtInsertOrderLine statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtUpdateDist_stmt,
        "Number of stmtUpdateDist statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtUpdateDist_stmt_time,
        "Total time spent executing stmtUpdateDist statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stmtUpdateStock_stmt,
        "Number of stmtUpdateStock statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stmtUpdateStock_stmt_time,
        "Total time spent executing stmtUpdateStock statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stockGetCountStock_stmt,
        "Number of stockGetCountStock statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stockGetCountStock_stmt_time,
        "Total time spent executing stockGetCountStock statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        stockGetDistOrderId_stmt,
        "Number of stockGetDistOrderId statements executed.",
        "executions",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        stockGetDistOrderId_stmt_time,
        "Total time spent executing stockGetDistOrderId statements.",
        "nanoseconds",
        !largerIsBetter
      ),
      factory().createIntCounter
      (
        numRemoteSuppliers,
        "Number of remote suppliers in order lines for new order transactions.",
        "number",
        largerIsBetter
      ),
      factory().createIntCounter
      (
        numDistributedTx,
        "Number of distributed new order transactions committed.",
        "number",
        largerIsBetter
      ),
      factory().createIntCounter
      (
        SERVER_STARTUPS,
        "Number of fabric server startups.",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        SERVER_STARTUP_TIME,
        "Total time spent starting up fabric servers.",
        "nanoseconds",
        !largerIsBetter
      )
    };
  }

  public static TPCCStats getInstance() {
    TPCCStats tps =
         (TPCCStats)getInstance(TPCCStats.class, SCOPE);
    tps.incVMCount();
    return tps;
  }
  public static TPCCStats getInstance(String name) {
    TPCCStats tps =
         (TPCCStats)getInstance(TPCCStats.class, SCOPE, name);
    tps.incVMCount();
    return tps;
  }
  public static TPCCStats getInstance( String name, String trimspecName ) {
    TPCCStats tps =
         (TPCCStats)getInstance(TPCCStats.class, SCOPE, name, trimspecName);
    tps.incVMCount();
    return tps;
  }

/////////////////// Construction / initialization ////////////////

  public TPCCStats( Class cls, StatisticsType type, int scope,
                    String instanceName, String trimspecName ) {
    super( cls, type, scope, instanceName, trimspecName );
  }

/////////////////// Updating stats /////////////////////////

// vm count --------------------------------------------------------------------

  /**
   * increase the count on the vmCount
   */
  private synchronized void incVMCount() {
    if (!VMCounted) {
      statistics().incInt(VM_COUNT, 1);
      VMCounted = true;
    }
  }
  private static boolean VMCounted = false;

// histogram -------------------------------------------------------------------

  /**
   * increase the time on the optional histogram by the supplied amount
   */
  public void incHistogram(HistogramStats histogram, long amount) {
    if (histogram != null) {
      histogram.incBin(amount);
    }
  }

// customers -------------------------------------------------------------------

  public long startCustomer() {
    return NanoTimer.getTime();
  }
  public void endCustomer(long start, int amount) {
    if (amount != 0) {
      long elapsed = NanoTimer.getTime() - start;
      statistics().incInt(CUSTOMERS, amount);
      statistics().incLong(CUSTOMER_TIME, elapsed);
    }
  }

// districts -------------------------------------------------------------------

  public long startDistrict() {
    return NanoTimer.getTime();
  }
  public void endDistrict(long start, int amount) {
    if (amount != 0) {
      long elapsed = NanoTimer.getTime() - start;
      statistics().incInt(DISTRICTS, amount);
      statistics().incLong(DISTRICT_TIME, elapsed);
    }
  }

// items -----------------------------------------------------------------------

  public long startItem() {
    return NanoTimer.getTime();
  }
  public void endItem(long start, int amount) {
    if (amount != 0) {
      long elapsed = NanoTimer.getTime() - start;
      statistics().incInt(ITEMS, amount);
      statistics().incLong(ITEM_TIME, elapsed);
    }
  }

// orders ----------------------------------------------------------------------

  public long startOrder() {
    return NanoTimer.getTime();
  }
  public void endOrder(long start, int orders, int newOrders, int orderLines) {
    if (orders != 0 || newOrders != 0 || orderLines != 0) {
      long elapsed = NanoTimer.getTime() - start;
      if (orders != 0) statistics().incInt(ORDERS, orders);
      if (newOrders != 0) statistics().incInt(NEW_ORDERS, newOrders);
      if (orderLines != 0) statistics().incInt(ORDER_LINES, orderLines);
      statistics().incLong(ORDER_TIME, elapsed);
    }
  }

// stocks ----------------------------------------------------------------------

  public long startStock() {
    return NanoTimer.getTime();
  }
  public void endStock(long start, int amount) {
    if (amount != 0) {
      long elapsed = NanoTimer.getTime() - start;
      statistics().incInt(STOCKS, amount);
      statistics().incLong(STOCK_TIME, elapsed);
    }
  }

// warehouses ------------------------------------------------------------------

  public long startWarehouse() {
    return NanoTimer.getTime();
  }
  public void endWarehouse(long start, int amount) {
    if (amount != 0) {
      long elapsed = NanoTimer.getTime() - start;
      statistics().incInt(WAREHOUSES, amount);
      statistics().incLong(WAREHOUSE_TIME, elapsed);
    }
  }

// load commits ----------------------------------------------------------------

  public long startLoadCommit() {
    return NanoTimer.getTime();
  }
  public void endLoadCommit(long start) {
    long elapsed = NanoTimer.getTime() - start;
    statistics().incInt(LOAD_COMMITS, 1);
    statistics().incLong(LOAD_COMMIT_TIME, elapsed);
  }

// transactions ----------------------------------------------------------------

  public void setTxCardInProgress(int i) {
    statistics().setInt(TX_CARD_IN_PROGRESS, i);
  }

  public void setTxTypeInProgress(int i) {
    statistics().setInt(TX_TYPE_IN_PROGRESS, i);
  }

  public long startCommit() {
    return NanoTimer.getTime();
  }

  public long startTransaction(int txType) {
    switch (txType) {
      case PAYMENT:
        statistics().incInt(PAYMENT_TX_IN_PROGRESS, 1);
        break;
      case STOCK_LEVEL:
        statistics().incInt(STOCK_LEVEL_TX_IN_PROGRESS, 1);
        break;
      case ORDER_STATUS:
        statistics().incInt(ORDER_STATUS_TX_IN_PROGRESS, 1);
        break;
      case DELIVERY:
        statistics().incInt(DELIVERY_TX_IN_PROGRESS, 1);
        break;
      case NEW_ORDER:
        statistics().incInt(NEW_ORDER_TX_IN_PROGRESS, 1);
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
    return NanoTimer.getTime();
  }

  public void endTransaction(int txType, long start, long commitStart) {
    long end = NanoTimer.getTime();
    long elapsed = end - start;
    switch (txType) {
      case PAYMENT:
        statistics().incInt(PAYMENT_TX_IN_PROGRESS, -1);
        statistics().incInt(PAYMENT_TX_COMPLETED, 1);
        statistics().incLong(PAYMENT_TX_TIME, elapsed);
        statistics().incLong(PAYMENT_TX_COMMIT_TIME, end - commitStart);
        break;
      case STOCK_LEVEL:
        statistics().incInt(STOCK_LEVEL_TX_IN_PROGRESS, -1);
        statistics().incInt(STOCK_LEVEL_TX_COMPLETED, 1);
        statistics().incLong(STOCK_LEVEL_TX_TIME, elapsed);
        break;
      case ORDER_STATUS:
        statistics().incInt(ORDER_STATUS_TX_IN_PROGRESS, -1);
        statistics().incInt(ORDER_STATUS_TX_COMPLETED, 1);
        statistics().incLong(ORDER_STATUS_TX_TIME, elapsed);
        break;
      case DELIVERY:
        statistics().incInt(DELIVERY_TX_IN_PROGRESS, -1);
        statistics().incInt(DELIVERY_TX_COMPLETED, 1);
        statistics().incLong(DELIVERY_TX_TIME, elapsed);
        statistics().incLong(DELIVERY_TX_COMMIT_TIME, end - commitStart);
        break;
      case NEW_ORDER:
        statistics().incInt(NEW_ORDER_TX_IN_PROGRESS, -1);
        statistics().incInt(NEW_ORDER_TX_COMPLETED, 1);
        statistics().incLong(NEW_ORDER_TX_TIME, elapsed);
        statistics().incLong(NEW_ORDER_TX_COMMIT_TIME, end - commitStart);
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
  }

  public void endTransactionNotFound(int txType, long start) {
    long elapsed = NanoTimer.getTime() - start;
    switch (txType) {
      case ORDER_STATUS:
        statistics().incInt(ORDER_STATUS_TX_IN_PROGRESS, -1);
        statistics().incInt(ORDER_STATUS_TX_NOT_FOUND, 1);
        statistics().incLong(ORDER_STATUS_TX_NOT_FOUND_TIME, elapsed);
        break;
      case DELIVERY:
        statistics().incInt(DELIVERY_TX_IN_PROGRESS, -1);
        statistics().incInt(DELIVERY_TX_ROLLED_BACK, 1);
        statistics().incLong(DELIVERY_TX_ROLLBACK_TIME, elapsed);
        break;
      case NEW_ORDER:
        statistics().incInt(NEW_ORDER_TX_IN_PROGRESS, -1);
        statistics().incInt(NEW_ORDER_TX_ROLLED_BACK, 1);
        statistics().incLong(NEW_ORDER_TX_ROLLBACK_TIME, elapsed);
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
  }

  public void endTransactionRestart(int txType, long start) {
    long elapsed = NanoTimer.getTime() - start;
    switch (txType) {
      case NEW_ORDER:
        statistics().incInt(NEW_ORDER_TX_IN_PROGRESS, -1);
        statistics().incInt(NEW_ORDER_TX_RESTARTED, 1);
        statistics().incLong(NEW_ORDER_TX_RESTART_TIME, elapsed);
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
  }

  public void endTransactionAbort(int txType, long start) {
    long elapsed = NanoTimer.getTime() - start;
    switch (txType) {
      case PAYMENT:
        statistics().incInt(PAYMENT_TX_IN_PROGRESS, -1);
        statistics().incInt(PAYMENT_TX_ABORTED, 1);
        statistics().incLong(PAYMENT_TX_ABORT_TIME, elapsed);
        break;
      case DELIVERY:
        statistics().incInt(DELIVERY_TX_IN_PROGRESS, -1);
        statistics().incInt(DELIVERY_TX_ABORTED, 1);
        statistics().incLong(DELIVERY_TX_ABORT_TIME, elapsed);
        break;
      case NEW_ORDER:
        statistics().incInt(NEW_ORDER_TX_IN_PROGRESS, -1);
        statistics().incInt(NEW_ORDER_TX_ABORTED, 1);
        statistics().incLong(NEW_ORDER_TX_ABORT_TIME, elapsed);
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
  }

  public void endTransactionRollback(int txType, long start) {
    long elapsed = NanoTimer.getTime() - start;
    switch (txType) {
      case PAYMENT:
        statistics().incInt(PAYMENT_TX_IN_PROGRESS, -1);
        statistics().incInt(PAYMENT_TX_ROLLED_BACK, 1);
        statistics().incLong(PAYMENT_TX_ROLLBACK_TIME, elapsed);
        break;
      case DELIVERY:
        statistics().incInt(DELIVERY_TX_IN_PROGRESS, -1);
        statistics().incInt(DELIVERY_TX_ROLLED_BACK, 1);
        statistics().incLong(DELIVERY_TX_ROLLBACK_TIME, elapsed);
        break;
      case NEW_ORDER:
        statistics().incInt(NEW_ORDER_TX_IN_PROGRESS, -1);
        statistics().incInt(NEW_ORDER_TX_ROLLED_BACK, 1);
        statistics().incLong(NEW_ORDER_TX_ROLLBACK_TIME, elapsed);
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
  }

// statements ------------------------------------------------------------------

  public long startStmt(Stmt stmt) {
    return NanoTimer.getTime();
  }

  public void endStmt(Stmt stmt, long start) {
    endStmt(stmt, start, 1, null);
  }

  public void endStmt(Stmt stmt, long start, HistogramStats histogram) {
    endStmt(stmt, start, 1, histogram);
  }

  public void endStmt(Stmt stmt, long start, int amount, HistogramStats histogram) {
    long elapsed = NanoTimer.getTime() - start;
    switch (stmt) {
      case delivDeleteNewOrder:
        statistics().incInt(delivDeleteNewOrder_stmt, amount);
        statistics().incLong(delivDeleteNewOrder_stmt_time, elapsed);
        break;
      case delivGetCustId:
        statistics().incInt(delivGetCustId_stmt, amount);
        statistics().incLong(delivGetCustId_stmt_time, elapsed);
        break;
      case delivGetOrderId:
        statistics().incInt(delivGetOrderId_stmt, amount);
        statistics().incLong(delivGetOrderId_stmt_time, elapsed);
        break;
      case delivSumOrderAmount:
        statistics().incInt(delivSumOrderAmount_stmt, amount);
        statistics().incLong(delivSumOrderAmount_stmt_time, elapsed);
        break;
      case delivUpdateCarrierId:
        statistics().incInt(delivUpdateCarrierId_stmt, amount);
        statistics().incLong(delivUpdateCarrierId_stmt_time, elapsed);
        break;
      case delivUpdateCustBalDelivCnt:
        statistics().incInt(delivUpdateCustBalDelivCnt_stmt, amount);
        statistics().incLong(delivUpdateCustBalDelivCnt_stmt_time, elapsed);
        break;
      case delivUpdateDeliveryDate:
        statistics().incInt(delivUpdateDeliveryDate_stmt, amount);
        statistics().incLong(delivUpdateDeliveryDate_stmt_time, elapsed);
        break;
      case ordStatCountCust:
        statistics().incInt(ordStatCountCust_stmt, amount);
        statistics().incLong(ordStatCountCust_stmt_time, elapsed);
        break;
      case ordStatGetCust:
        statistics().incInt(ordStatGetCust_stmt, amount);
        statistics().incLong(ordStatGetCust_stmt_time, elapsed);
        break;
      case ordStatGetCustBal:
        statistics().incInt(ordStatGetCustBal_stmt, amount);
        statistics().incLong(ordStatGetCustBal_stmt_time, elapsed);
        break;
      case ordStatGetNewestOrd:
        statistics().incInt(ordStatGetNewestOrd_stmt, amount);
        statistics().incLong(ordStatGetNewestOrd_stmt_time, elapsed);
        break;
      case ordStatGetOrder:
        statistics().incInt(ordStatGetOrder_stmt, amount);
        statistics().incLong(ordStatGetOrder_stmt_time, elapsed);
        break;
      case ordStatGetOrderLines:
        statistics().incInt(ordStatGetOrderLines_stmt, amount);
        statistics().incLong(ordStatGetOrderLines_stmt_time, elapsed);
        break;
      case payCountCust:
        statistics().incInt(payCountCust_stmt, amount);
        statistics().incLong(payCountCust_stmt_time, elapsed);
        break;
      case payCursorCustByName:
        statistics().incInt(payCursorCustByName_stmt, amount);
        statistics().incLong(payCursorCustByName_stmt_time, elapsed);
        break;
      case payGetCust:
        statistics().incInt(payGetCust_stmt, amount);
        statistics().incLong(payGetCust_stmt_time, elapsed);
        break;
      case payGetCustCdata:
        statistics().incInt(payGetCustCdata_stmt, amount);
        statistics().incLong(payGetCustCdata_stmt_time, elapsed);
        break;
      case payGetDist:
        statistics().incInt(payGetDist_stmt, amount);
        statistics().incLong(payGetDist_stmt_time, elapsed);
        break;
      case payGetWhse:
        statistics().incInt(payGetWhse_stmt, amount);
        statistics().incLong(payGetWhse_stmt_time, elapsed);
        break;
      case payInsertHist:
        statistics().incInt(payInsertHist_stmt, amount);
        statistics().incLong(payInsertHist_stmt_time, elapsed);
        break;
      case payUpdateCustBal:
        statistics().incInt(payUpdateCustBal_stmt, amount);
        statistics().incLong(payUpdateCustBal_stmt_time, elapsed);
        break;
      case payUpdateCustBalCdata:
        statistics().incInt(payUpdateCustBalCdata_stmt, amount);
        statistics().incLong(payUpdateCustBalCdata_stmt_time, elapsed);
        break;
      case payUpdateDist:
        statistics().incInt(payUpdateDist_stmt, amount);
        statistics().incLong(payUpdateDist_stmt_time, elapsed);
        break;
      case payUpdateWhse:
        statistics().incInt(payUpdateWhse_stmt, amount);
        statistics().incLong(payUpdateWhse_stmt_time, elapsed);
        break;
      case stmtGetCustWhse:
        statistics().incInt(stmtGetCustWhse_stmt, amount);
        statistics().incLong(stmtGetCustWhse_stmt_time, elapsed);
        break;
      case stmtGetDist:
        statistics().incInt(stmtGetDist_stmt, amount);
        statistics().incLong(stmtGetDist_stmt_time, elapsed);
        break;
      case stmtGetItem:
        statistics().incInt(stmtGetItem_stmt, amount);
        statistics().incLong(stmtGetItem_stmt_time, elapsed);
        break;
      case stmtGetStock:
        statistics().incInt(stmtGetStock_stmt, amount);
        statistics().incLong(stmtGetStock_stmt_time, elapsed);
        break;
      case stmtInsertNewOrder:
        statistics().incInt(stmtInsertNewOrder_stmt, amount);
        statistics().incLong(stmtInsertNewOrder_stmt_time, elapsed);
        break;
      case stmtInsertOOrder:
        statistics().incInt(stmtInsertOOrder_stmt, amount);
        statistics().incLong(stmtInsertOOrder_stmt_time, elapsed);
        break;
      case stmtInsertOrderLine:
        statistics().incInt(stmtInsertOrderLine_stmt, amount);
        statistics().incLong(stmtInsertOrderLine_stmt_time, elapsed);
        break;
      case stmtUpdateDist:
        statistics().incInt(stmtUpdateDist_stmt, amount);
        statistics().incLong(stmtUpdateDist_stmt_time, elapsed);
        break;
      case stmtUpdateStock:
        statistics().incInt(stmtUpdateStock_stmt, amount);
        statistics().incLong(stmtUpdateStock_stmt_time, elapsed);
        break;
      case stockGetCountStock:
        statistics().incInt(stockGetCountStock_stmt, amount);
        statistics().incLong(stockGetCountStock_stmt_time, elapsed);
        break;
      case stockGetDistOrderId:
        statistics().incInt(stockGetDistOrderId_stmt, amount);
        statistics().incLong(stockGetDistOrderId_stmt_time, elapsed);
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
    incHistogram(histogram, elapsed);
  }

  public void endStmtNotFound(Stmt stmt, long start) {
    long elapsed = NanoTimer.getTime() - start;
    switch (stmt) {
      case delivGetOrderId:
        statistics().incInt(delivGetOrderId_stmt_not_found, 1);
        statistics().incLong(delivGetOrderId_stmt_not_found_time, elapsed);
        break;
      case delivSumOrderAmount:
        statistics().incInt(delivSumOrderAmount_stmt_not_found, 1);
        statistics().incLong(delivSumOrderAmount_stmt_not_found_time, elapsed);
        break;
      case ordStatCountCust:
        statistics().incInt(ordStatCountCust_stmt_not_found, 1);
        statistics().incLong(ordStatCountCust_stmt_not_found_time, elapsed);
        break;
      case ordStatGetCust:
        statistics().incInt(ordStatGetCust_stmt_not_found, 1);
        statistics().incLong(ordStatGetCust_stmt_not_found_time, elapsed);
        break;
      case ordStatGetNewestOrd:
        statistics().incInt(ordStatGetNewestOrd_stmt_not_found, 1);
        statistics().incLong(ordStatGetNewestOrd_stmt_not_found_time, elapsed);
        break;
      case payCursorCustByName:
        statistics().incInt(payCursorCustByName_stmt_not_found, 1);
        statistics().incLong(payCursorCustByName_stmt_not_found_time, elapsed);
        break;
      default:
        String s = "Not implemented for statement type " + stmt;
        throw new UnsupportedOperationException(s);
    }
  }

// remote suppliers ------------------------------------------------------------

  /**
   * increment the number of remote suppliers in new order transactions
   */
  public void incRemoteSuppliers() {
    statistics().incInt(numRemoteSuppliers, 1);
  }

// distributed tx --------------------------------------------------------------

  /**
   * increment the number of distributed transactions committed in new order
   * transactions
   */
  public void incDistributedTx() {
    statistics().incInt(numDistributedTx, 1);
  }

// startups --------------------------------------------------------------------

  public long startServerStartup() {
    return NanoTimer.getTime();
  }
  public void endServerStartup(long start) {
    long elapsed = NanoTimer.getTime() - start;
    statistics().incInt(SERVER_STARTUPS, 1);
    statistics().incLong(SERVER_STARTUP_TIME, elapsed);
  }
}
