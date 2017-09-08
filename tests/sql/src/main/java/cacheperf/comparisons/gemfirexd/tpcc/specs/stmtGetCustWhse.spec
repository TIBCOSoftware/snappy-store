statspec stmtGetCustWhseStmtsPerSecond *client* TPCCStats * stmtGetCustWhseStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtGetCustWhseTotalStmtsExecuted *client* TPCCStats * stmtGetCustWhseStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetCustWhseTotalStmtTime *client* TPCCStats * stmtGetCustWhseStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetCustWhseStmtResponseTime = stmtGetCustWhseTotalStmtTime / stmtGetCustWhseTotalStmtsExecuted ops=max-min?
;
