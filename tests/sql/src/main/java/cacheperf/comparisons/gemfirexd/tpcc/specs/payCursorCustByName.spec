statspec payCursorCustByNameStmtsPerSecond *client* TPCCStats * payCursorCustByNameStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payCursorCustByNameTotalStmtsExecuted *client* TPCCStats * payCursorCustByNameStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payCursorCustByNameTotalStmtTime *client* TPCCStats * payCursorCustByNameStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payCursorCustByNameStmtResponseTime = payCursorCustByNameTotalStmtTime / payCursorCustByNameTotalStmtsExecuted ops=max-min?
;
