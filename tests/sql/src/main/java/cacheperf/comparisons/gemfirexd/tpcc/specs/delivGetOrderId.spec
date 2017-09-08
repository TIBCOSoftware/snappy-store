statspec delivGetOrderIdStmtsPerSecond *client* TPCCStats * delivGetOrderIdStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivGetOrderIdTotalStmtsExecuted *client* TPCCStats * delivGetOrderIdStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivGetOrderIdTotalStmtTime *client* TPCCStats * delivGetOrderIdStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivGetOrderIdStmtResponseTime = delivGetOrderIdTotalStmtTime / delivGetOrderIdTotalStmtsExecuted ops=max-min?
;
