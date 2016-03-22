//------------------------------------------------------------------------------
// transaction throughput and response times
//------------------------------------------------------------------------------

statspec selectUpdateTxCompletedPerSecond *client* UseCase6Stats * selectUpdateTxCompleted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec totalselectUpdateTxCompleted *client* UseCase6Stats * selectUpdateTxCompleted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec totalselectUpdateTxTime *client* UseCase6Stats * selectUpdateTxTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr selectUpdateTxCompletedResponseTime = totalselectUpdateTxTime / totalselectUpdateTxCompleted ops=max-min?
;
