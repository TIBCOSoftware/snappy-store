statspec payUpdateWhseStmtsPerSecond *client* TPCCStats * payUpdateWhseStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payUpdateWhseTotalStmtsExecuted *client* TPCCStats * payUpdateWhseStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateWhseTotalStmtTime *client* TPCCStats * payUpdateWhseStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateWhseStmtResponseTime = payUpdateWhseTotalStmtTime / payUpdateWhseTotalStmtsExecuted ops=max-min?
;
