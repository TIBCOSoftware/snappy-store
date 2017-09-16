statspec delivSumOrderAmountStmtsPerSecond *client* TPCCStats * delivSumOrderAmountStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivSumOrderAmountTotalStmtsExecuted *client* TPCCStats * delivSumOrderAmountStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivSumOrderAmountTotalStmtTime *client* TPCCStats * delivSumOrderAmountStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivSumOrderAmountStmtResponseTime = delivSumOrderAmountTotalStmtTime / delivSumOrderAmountTotalStmtsExecuted ops=max-min?
;
