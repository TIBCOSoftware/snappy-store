statspec ordStatCountCustStmtsPerSecond *client* TPCCStats * ordStatCountCustStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec ordStatCountCustTotalStmtsExecuted *client* TPCCStats * ordStatCountCustStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatCountCustTotalStmtTime *client* TPCCStats * ordStatCountCustStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatCountCustStmtResponseTime = ordStatCountCustTotalStmtTime / ordStatCountCustTotalStmtsExecuted ops=max-min?
;
