//------------------------------------------------------------------------------
// transaction throughput and response times
//------------------------------------------------------------------------------

statspec deliveryTxCompletedPerSecond_b *client* TPCCStats * deliveryTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_b
;
statspec totalDeliveryTxCompleted_b *client* TPCCStats * deliveryTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalDeliveryTxTime_b *client* TPCCStats * deliveryTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr deliveryTxCompletedResponseTime_b = totalDeliveryTxTime_b / totalDeliveryTxCompleted_b ops=max-min?
;
statspec totalDeliveryTxCommitTime_b *client* TPCCStats * deliveryTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr deliveryTxCommitResponseTime_b = totalDeliveryTxCommitTime_b / totalDeliveryTxCompleted_b ops=max-min?
;

statspec newOrderTxCompletedPerSecond_b *client* TPCCStats * newOrderTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_b
;
statspec totalNewOrderTxCompleted_b *client* TPCCStats * newOrderTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalNewOrderTxTime_b *client* TPCCStats * newOrderTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr newOrderTxCompletedResponseTime_b = totalNewOrderTxTime_b / totalNewOrderTxCompleted_b ops=max-min?
;
statspec totalNewOrderTxCommitTime_b *client* TPCCStats * newOrderTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr newOrderTxCommitResponseTime_b = totalNewOrderTxCommitTime_b / totalNewOrderTxCompleted_b ops=max-min?
;

statspec orderStatusTxCompletedPerSecond_b *client* TPCCStats * orderStatusTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_b
;
statspec totalOrderStatusTxCompleted_b *client* TPCCStats * orderStatusTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalOrderStatusTxTime_b *client* TPCCStats * orderStatusTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr orderStatusTxCompletedResponseTime_b = totalOrderStatusTxTime_b / totalOrderStatusTxCompleted_b ops=max-min?
;

statspec paymentTxCompletedPerSecond_b *client* TPCCStats * paymentTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_b
;
statspec totalPaymentTxCompleted_b *client* TPCCStats * paymentTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalPaymentTxTime_b *client* TPCCStats * paymentTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr paymentTxCompletedResponseTime_b = totalPaymentTxTime_b / totalPaymentTxCompleted_b ops=max-min?
;
statspec totalPaymentTxCommitTime_b *client* TPCCStats * paymentTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr paymentTxCommitResponseTime_b = totalPaymentTxCommitTime_b / totalPaymentTxCompleted_b ops=max-min?
;

statspec stockLevelTxCompletedPerSecond_b *client* TPCCStats * stockLevelTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_b
;
statspec totalStockLevelTxCompleted_b *client* TPCCStats * stockLevelTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalStockLevelTxTime_b *client* TPCCStats * stockLevelTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr stockLevelTxCompletedResponseTime_b = totalStockLevelTxTime_b / totalStockLevelTxCompleted_b ops=max-min?
;

//------------------------------------------------------------------------------
// transaction rollback, abort, restart, and not found
//------------------------------------------------------------------------------

statspec totalDeliveryTxAborted_b *client* TPCCStats * deliveryTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalDeliveryTxAbortTime_b *client* TPCCStats * deliveryTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr deliveryTxAbortResponseTime_b = totalDeliveryTxAbortTime_b / totalDeliveryTxAborted_b ops=max-min?
;
expr deliveryTxAbortedRatio_b = totalDeliveryTxAborted_b / totalDeliveryTxCompleted_b ops=max-min?
;

statspec totalNewOrderTxAborted_b *client* TPCCStats * newOrderTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalNewOrderTxAbortTime_b *client* TPCCStats * newOrderTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr newOrderTxAbortResponseTime_b = totalNewOrderTxAbortTime_b / totalNewOrderTxAborted_b ops=max-min?
;
expr newOrderTxAbortRatio_b = totalNewOrderTxAborted_b / totalNewOrderTxCompleted_b ops=max-min?
;

statspec totalPaymentTxAborted_b *client* TPCCStats * paymentTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalPaymentTxAbortTime_b *client* TPCCStats * paymentTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr paymentTxAbortResponseTime_b = totalPaymentTxAbortTime_b / totalPaymentTxAborted_b ops=max-min?
;
expr paymentTxAbortRatio_b = totalPaymentTxAborted_b / totalPaymentTxCompleted_b ops=max-min?
;

statspec totalDeliveryTxRolledBack_b *client* TPCCStats * deliveryTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalDeliveryTxRollbackTime_b *client* TPCCStats * deliveryTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr deliveryTxRollbackResponseTime_b = totalDeliveryTxRollbackTime_b / totalDeliveryTxRolledBack_b ops=max-min?
;
expr deliveryTxRollbackRatio_b = totalDeliveryTxRolledBack_b / totalDeliveryTxCompleted_b ops=max-min?
;

statspec totalNewOrderTxRolledBack_b *client* TPCCStats * newOrderTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalNewOrderTxRollbackTime_b *client* TPCCStats * newOrderTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr newOrderTxRollbackResponseTime_b = totalNewOrderTxRollbackTime_b / totalNewOrderTxRolledBack_b ops=max-min?
;
expr newOrderTxRollbackRatio_b = totalNewOrderTxRolledBack_b / totalNewOrderTxCompleted_b ops=max-min?
;

statspec totalPaymentTxRolledBack_b *client* TPCCStats * paymentTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalPaymentTxRollbackTime_b *client* TPCCStats * paymentTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr paymentTxRollbackResponseTime_b = totalPaymentTxRollbackTime_b / totalPaymentTxRolledBack_b ops=max-min?
;
expr paymentTxRollbackRatio_b = totalPaymentTxRolledBack_b / totalPaymentTxCompleted_b ops=max-min?
;

statspec totalNewOrderTxRestarted_b *client* TPCCStats * newOrderTxRestarted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalNewOrderTxRestartTime_b *client* TPCCStats * newOrderTxRestartTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr newOrderTxRestartResponseTime_b = totalNewOrderTxRestartTime_b / totalNewOrderTxRestarted_b ops=max-min?
;
expr newOrderTxRestartRatio_b = totalNewOrderTxRestarted_b / totalNewOrderTxCompleted_b ops=max-min?
;

statspec totalOrderStatusTxNotFound_b *client* TPCCStats * orderStatusTxNotFound
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
statspec totalOrderStatusTxNotFoundTime_b *client* TPCCStats * orderStatusTxNotFoundTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_b
;
expr orderStatusTxNotFoundResponseTime_b = totalOrderStatusTxNotFoundTime_b / totalOrderStatusTxNotFound_b ops=max-min?
;
expr orderStatusTxNotFoundRatio_b = totalOrderStatusTxNotFound_b / totalOrderStatusTxCompleted_b ops=max-min?
;
