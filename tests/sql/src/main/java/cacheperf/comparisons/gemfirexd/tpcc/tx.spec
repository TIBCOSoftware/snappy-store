//------------------------------------------------------------------------------
// transaction throughput and response times
//------------------------------------------------------------------------------

statspec deliveryTxCompletedPerSecond *client* TPCCStats * deliveryTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec totalDeliveryTxCompleted *client* TPCCStats * deliveryTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalDeliveryTxTime *client* TPCCStats * deliveryTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr deliveryTxCompletedResponseTime = totalDeliveryTxTime / totalDeliveryTxCompleted ops=max-min?
;
statspec totalDeliveryTxCommitTime *client* TPCCStats * deliveryTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr deliveryTxCommitResponseTime = totalDeliveryTxCommitTime / totalDeliveryTxCompleted ops=max-min?
;

statspec newOrderTxCompletedPerSecond *client* TPCCStats * newOrderTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec totalNewOrderTxCompleted *client* TPCCStats * newOrderTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalNewOrderTxTime *client* TPCCStats * newOrderTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr newOrderTxCompletedResponseTime = totalNewOrderTxTime / totalNewOrderTxCompleted ops=max-min?
;
statspec totalNewOrderTxCommitTime *client* TPCCStats * newOrderTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr newOrderTxCommitResponseTime = totalNewOrderTxCommitTime / totalNewOrderTxCompleted ops=max-min?
;

statspec orderStatusTxCompletedPerSecond *client* TPCCStats * orderStatusTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec totalOrderStatusTxCompleted *client* TPCCStats * orderStatusTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalOrderStatusTxTime *client* TPCCStats * orderStatusTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr orderStatusTxCompletedResponseTime = totalOrderStatusTxTime / totalOrderStatusTxCompleted ops=max-min?
;

statspec paymentTxCompletedPerSecond *client* TPCCStats * paymentTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec totalPaymentTxCompleted *client* TPCCStats * paymentTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalPaymentTxTime *client* TPCCStats * paymentTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr paymentTxCompletedResponseTime = totalPaymentTxTime / totalPaymentTxCompleted ops=max-min?
;
statspec totalPaymentTxCommitTime *client* TPCCStats * paymentTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr paymentTxCommitResponseTime = totalPaymentTxCommitTime / totalPaymentTxCompleted ops=max-min?
;

statspec stockLevelTxCompletedPerSecond *client* TPCCStats * stockLevelTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
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
expr deliveryTxAbortedRatio = totalDeliveryTxAborted / totalDeliveryTxCompleted ops=max-min?
;

statspec totalNewOrderTxAborted *client* TPCCStats * newOrderTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalNewOrderTxAbortTime *client* TPCCStats * newOrderTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr newOrderTxAbortResponseTime = totalNewOrderTxAbortTime / totalNewOrderTxAborted ops=max-min?
;
expr newOrderTxAbortRatio = totalNewOrderTxAborted / totalNewOrderTxCompleted ops=max-min?
;

statspec totalPaymentTxAborted *client* TPCCStats * paymentTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalPaymentTxAbortTime *client* TPCCStats * paymentTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr paymentTxAbortResponseTime = totalPaymentTxAbortTime / totalPaymentTxAborted ops=max-min?
;
expr paymentTxAbortRatio = totalPaymentTxAborted / totalPaymentTxCompleted ops=max-min?
;

statspec totalDeliveryTxRolledBack *client* TPCCStats * deliveryTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalDeliveryTxRollbackTime *client* TPCCStats * deliveryTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr deliveryTxRollbackResponseTime = totalDeliveryTxRollbackTime / totalDeliveryTxRolledBack ops=max-min?
;
expr deliveryTxRollbackRatio = totalDeliveryTxRolledBack / totalDeliveryTxCompleted ops=max-min?
;

statspec totalNewOrderTxRolledBack *client* TPCCStats * newOrderTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalNewOrderTxRollbackTime *client* TPCCStats * newOrderTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr newOrderTxRollbackResponseTime = totalNewOrderTxRollbackTime / totalNewOrderTxRolledBack ops=max-min?
;
expr newOrderTxRollbackRatio = totalNewOrderTxRolledBack / totalNewOrderTxCompleted ops=max-min?
;

statspec totalPaymentTxRolledBack *client* TPCCStats * paymentTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalPaymentTxRollbackTime *client* TPCCStats * paymentTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr paymentTxRollbackResponseTime = totalPaymentTxRollbackTime / totalPaymentTxRolledBack ops=max-min?
;
expr paymentTxRollbackRatio = totalPaymentTxRolledBack / totalPaymentTxCompleted ops=max-min?
;

statspec totalNewOrderTxRestarted *client* TPCCStats * newOrderTxRestarted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalNewOrderTxRestartTime *client* TPCCStats * newOrderTxRestartTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr newOrderTxRestartResponseTime = totalNewOrderTxRestartTime / totalNewOrderTxRestarted ops=max-min?
;
expr newOrderTxRestartRatio = totalNewOrderTxRestarted / totalNewOrderTxCompleted ops=max-min?
;

statspec totalOrderStatusTxNotFound *client* TPCCStats * orderStatusTxNotFound
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalOrderStatusTxNotFoundTime *client* TPCCStats * orderStatusTxNotFoundTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr orderStatusTxNotFoundResponseTime = totalOrderStatusTxNotFoundTime / totalOrderStatusTxNotFound ops=max-min?
;
expr orderStatusTxNotFoundRatio = totalOrderStatusTxNotFound / totalOrderStatusTxCompleted ops=max-min?
;
