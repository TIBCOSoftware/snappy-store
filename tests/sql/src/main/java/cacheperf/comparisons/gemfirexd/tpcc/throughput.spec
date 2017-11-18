//------------------------------------------------------------------------------
// transaction throughput
//------------------------------------------------------------------------------

statspec deliveryTxCompletedPerSecond *client* TPCCStats * deliveryTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec newOrderTxCompletedPerSecond *client* TPCCStats * newOrderTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec orderStatusTxCompletedPerSecond *client* TPCCStats * orderStatusTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec paymentTxCompletedPerSecond *client* TPCCStats * paymentTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stockLevelTxCompletedPerSecond *client* TPCCStats * stockLevelTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
