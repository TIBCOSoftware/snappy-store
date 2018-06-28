statspec ordStatGetCustStmtsPerSecond *client* TPCCStats * ordStatGetCustStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec ordStatGetCustTotalStmtsExecuted *client* TPCCStats * ordStatGetCustStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetCustTotalStmtTime *client* TPCCStats * ordStatGetCustStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetCustStmtResponseTime = ordStatGetCustTotalStmtTime / ordStatGetCustTotalStmtsExecuted ops=max-min?
;
