statspec ordStatGetNewestOrdStmtsPerSecond *client* TPCCStats * ordStatGetNewestOrdStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec ordStatGetNewestOrdTotalStmtsExecuted *client* TPCCStats * ordStatGetNewestOrdStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetNewestOrdTotalStmtTime *client* TPCCStats * ordStatGetNewestOrdStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetNewestOrdStmtResponseTime = ordStatGetNewestOrdTotalStmtTime / ordStatGetNewestOrdTotalStmtsExecuted ops=max-min?
;
