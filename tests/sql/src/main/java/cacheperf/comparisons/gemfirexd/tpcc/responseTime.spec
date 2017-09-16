//------------------------------------------------------------------------------
// transaction response times
//------------------------------------------------------------------------------

statspec totalDeliveryTxCompleted *client* TPCCStats * deliveryTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalDeliveryTxTime *client* TPCCStats * deliveryTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr deliveryTxCompletedResponseTime = totalDeliveryTxTime / totalDeliveryTxCompleted ops=max-min?
;

statspec totalNewOrderTxCompleted *client* TPCCStats * newOrderTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalNewOrderTxTime *client* TPCCStats * newOrderTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr newOrderTxCompletedResponseTime = totalNewOrderTxTime / totalNewOrderTxCompleted ops=max-min?
;

statspec totalOrderStatusTxCompleted *client* TPCCStats * orderStatusTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalOrderStatusTxTime *client* TPCCStats * orderStatusTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr orderStatusTxCompletedResponseTime = totalOrderStatusTxTime / totalOrderStatusTxCompleted ops=max-min?
;

statspec totalPaymentTxCompleted *client* TPCCStats * paymentTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalPaymentTxTime *client* TPCCStats * paymentTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr paymentTxCompletedResponseTime = totalPaymentTxTime / totalPaymentTxCompleted ops=max-min?
;

statspec totalStockLevelTxCompleted *client* TPCCStats * stockLevelTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalStockLevelTxTime *client* TPCCStats * stockLevelTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stockLevelTxCompletedResponseTime = totalStockLevelTxTime / totalStockLevelTxCompleted ops=max-min?
;

//------------------------------------------------------------------------------
// transaction rollback, abort, restart, and not found
//------------------------------------------------------------------------------

statspec totalDeliveryTxAborted *client* TPCCStats * deliveryTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalDeliveryTxAbortTime *client* TPCCStats * deliveryTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr deliveryTxAbortResponseTime = totalDeliveryTxAbortTime / totalDeliveryTxAborted ops=max-min?
;

statspec totalNewOrderTxAborted *client* TPCCStats * newOrderTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalNewOrderTxAbortTime *client* TPCCStats * newOrderTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr newOrderTxAbortResponseTime = totalNewOrderTxAbortTime / totalNewOrderTxAborted ops=max-min?
;

statspec totalPaymentTxAborted *client* TPCCStats * paymentTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalPaymentTxAbortTime *client* TPCCStats * paymentTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr paymentTxAbortResponseTime = totalPaymentTxAbortTime / totalPaymentTxAborted ops=max-min?
;
