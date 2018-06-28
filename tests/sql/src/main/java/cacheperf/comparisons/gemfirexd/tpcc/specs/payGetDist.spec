statspec payGetDistStmtsPerSecond *client* TPCCStats * payGetDistStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payGetDistTotalStmtsExecuted *client* TPCCStats * payGetDistStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetDistTotalStmtTime *client* TPCCStats * payGetDistStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetDistStmtResponseTime = payGetDistTotalStmtTime / payGetDistTotalStmtsExecuted ops=max-min?
;
