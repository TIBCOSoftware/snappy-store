//------------------------------------------------------------------------------
// transaction throughput and response times
//------------------------------------------------------------------------------

statspec deliveryTxCompletedPerSecond_a *client* TPCCStats * deliveryTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_a
;
statspec totalDeliveryTxCompleted_a *client* TPCCStats * deliveryTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalDeliveryTxTime_a *client* TPCCStats * deliveryTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr deliveryTxCompletedResponseTime_a = totalDeliveryTxTime_a / totalDeliveryTxCompleted_a ops=max-min?
;
statspec totalDeliveryTxCommitTime_a *client* TPCCStats * deliveryTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr deliveryTxCommitResponseTime_a = totalDeliveryTxCommitTime_a / totalDeliveryTxCompleted_a ops=max-min?
;

statspec newOrderTxCompletedPerSecond_a *client* TPCCStats * newOrderTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_a
;
statspec totalNewOrderTxCompleted_a *client* TPCCStats * newOrderTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalNewOrderTxTime_a *client* TPCCStats * newOrderTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr newOrderTxCompletedResponseTime_a = totalNewOrderTxTime_a / totalNewOrderTxCompleted_a ops=max-min?
;
statspec totalNewOrderTxCommitTime_a *client* TPCCStats * newOrderTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr newOrderTxCommitResponseTime_a = totalNewOrderTxCommitTime_a / totalNewOrderTxCompleted_a ops=max-min?
;

statspec orderStatusTxCompletedPerSecond_a *client* TPCCStats * orderStatusTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_a
;
statspec totalOrderStatusTxCompleted_a *client* TPCCStats * orderStatusTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalOrderStatusTxTime_a *client* TPCCStats * orderStatusTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr orderStatusTxCompletedResponseTime_a = totalOrderStatusTxTime_a / totalOrderStatusTxCompleted_a ops=max-min?
;

statspec paymentTxCompletedPerSecond_a *client* TPCCStats * paymentTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_a
;
statspec totalPaymentTxCompleted_a *client* TPCCStats * paymentTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalPaymentTxTime_a *client* TPCCStats * paymentTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr paymentTxCompletedResponseTime_a = totalPaymentTxTime_a / totalPaymentTxCompleted_a ops=max-min?
;
statspec totalPaymentTxCommitTime_a *client* TPCCStats * paymentTxCommitTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr paymentTxCommitResponseTime_a = totalPaymentTxCommitTime_a / totalPaymentTxCompleted_a ops=max-min?
;

statspec stockLevelTxCompletedPerSecond_a *client* TPCCStats * stockLevelTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions_a
;
statspec totalStockLevelTxCompleted_a *client* TPCCStats * stockLevelTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalStockLevelTxTime_a *client* TPCCStats * stockLevelTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr stockLevelTxCompletedResponseTime_a = totalStockLevelTxTime_a / totalStockLevelTxCompleted_a ops=max-min?
;

//------------------------------------------------------------------------------
// transaction rollback, abort, restart, and not found
//------------------------------------------------------------------------------

statspec totalDeliveryTxAborted_a *client* TPCCStats * deliveryTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalDeliveryTxAbortTime_a *client* TPCCStats * deliveryTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr deliveryTxAbortResponseTime_a = totalDeliveryTxAbortTime_a / totalDeliveryTxAborted_a ops=max-min?
;
expr deliveryTxAbortedRatio_a = totalDeliveryTxAborted_a / totalDeliveryTxCompleted_a ops=max-min?
;

statspec totalNewOrderTxAborted_a *client* TPCCStats * newOrderTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalNewOrderTxAbortTime_a *client* TPCCStats * newOrderTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr newOrderTxAbortResponseTime_a = totalNewOrderTxAbortTime_a / totalNewOrderTxAborted_a ops=max-min?
;
expr newOrderTxAbortRatio_a = totalNewOrderTxAborted_a / totalNewOrderTxCompleted_a ops=max-min?
;

statspec totalPaymentTxAborted_a *client* TPCCStats * paymentTxAborted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalPaymentTxAbortTime_a *client* TPCCStats * paymentTxAbortTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr paymentTxAbortResponseTime_a = totalPaymentTxAbortTime_a / totalPaymentTxAborted_a ops=max-min?
;
expr paymentTxAbortRatio_a = totalPaymentTxAborted_a / totalPaymentTxCompleted_a ops=max-min?
;

statspec totalDeliveryTxRolledBack_a *client* TPCCStats * deliveryTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalDeliveryTxRollbackTime_a *client* TPCCStats * deliveryTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr deliveryTxRollbackResponseTime_a = totalDeliveryTxRollbackTime_a / totalDeliveryTxRolledBack_a ops=max-min?
;
expr deliveryTxRollbackRatio_a = totalDeliveryTxRolledBack_a / totalDeliveryTxCompleted_a ops=max-min?
;

statspec totalNewOrderTxRolledBack_a *client* TPCCStats * newOrderTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalNewOrderTxRollbackTime_a *client* TPCCStats * newOrderTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr newOrderTxRollbackResponseTime_a = totalNewOrderTxRollbackTime_a / totalNewOrderTxRolledBack_a ops=max-min?
;
expr newOrderTxRollbackRatio_a = totalNewOrderTxRolledBack_a / totalNewOrderTxCompleted_a ops=max-min?
;

statspec totalPaymentTxRolledBack_a *client* TPCCStats * paymentTxRolledBack
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalPaymentTxRollbackTime_a *client* TPCCStats * paymentTxRollbackTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr paymentTxRollbackResponseTime_a = totalPaymentTxRollbackTime_a / totalPaymentTxRolledBack_a ops=max-min?
;
expr paymentTxRollbackRatio_a = totalPaymentTxRolledBack_a / totalPaymentTxCompleted_a ops=max-min?
;

statspec totalNewOrderTxRestarted_a *client* TPCCStats * newOrderTxRestarted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalNewOrderTxRestartTime_a *client* TPCCStats * newOrderTxRestartTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr newOrderTxRestartResponseTime_a = totalNewOrderTxRestartTime_a / totalNewOrderTxRestarted_a ops=max-min?
;
expr newOrderTxRestartRatio_a = totalNewOrderTxRestarted_a / totalNewOrderTxCompleted_a ops=max-min?
;

statspec totalOrderStatusTxNotFound_a *client* TPCCStats * orderStatusTxNotFound
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
statspec totalOrderStatusTxNotFoundTime_a *client* TPCCStats * orderStatusTxNotFoundTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions_a
;
expr orderStatusTxNotFoundResponseTime_a = totalOrderStatusTxNotFoundTime_a / totalOrderStatusTxNotFound_a ops=max-min?
;
expr orderStatusTxNotFoundRatio_a = totalOrderStatusTxNotFound_a / totalOrderStatusTxCompleted_a ops=max-min?
;
