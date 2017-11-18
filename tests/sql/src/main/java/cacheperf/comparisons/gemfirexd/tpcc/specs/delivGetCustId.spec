statspec delivGetCustIdStmtsPerSecond *client* TPCCStats * delivGetCustIdStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivGetCustIdTotalStmtsExecuted *client* TPCCStats * delivGetCustIdStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivGetCustIdTotalStmtTime *client* TPCCStats * delivGetCustIdStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivGetCustIdStmtResponseTime = delivGetCustIdTotalStmtTime / delivGetCustIdTotalStmtsExecuted ops=max-min?
;
