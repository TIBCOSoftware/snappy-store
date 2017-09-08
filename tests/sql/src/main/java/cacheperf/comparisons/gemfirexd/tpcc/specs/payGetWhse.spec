statspec payGetWhseStmtsPerSecond *client* TPCCStats * payGetWhseStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payGetWhseTotalStmtsExecuted *client* TPCCStats * payGetWhseStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetWhseTotalStmtTime *client* TPCCStats * payGetWhseStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetWhseStmtResponseTime = payGetWhseTotalStmtTime / payGetWhseTotalStmtsExecuted ops=max-min?
;
