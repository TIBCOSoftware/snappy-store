statspec stockGetCountStockStmtsPerSecond *client* TPCCStats * stockGetCountStockStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stockGetCountStockTotalStmtsExecuted *client* TPCCStats * stockGetCountStockStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stockGetCountStockTotalStmtTime *client* TPCCStats * stockGetCountStockStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stockGetCountStockStmtResponseTime = stockGetCountStockTotalStmtTime / stockGetCountStockTotalStmtsExecuted ops=max-min?
;
