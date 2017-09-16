//------------------------------------------------------------------------------
// individual statements for StatementStats
// set hydra.gemfirexd.FabricServerPrms-enableStatsGlobally=true
// and hydra.gemfirexd.FabricServerPrms-enableTimeStatsGlobally=true
// include stmt.spec for expressions
// only applies to peer clients
//------------------------------------------------------------------------------

statspec delivDeleteNewOrderTotalStmtStatsExecuted *client* StatementStats delete_no_w_id* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivDeleteNewOrderTotalStmtStatsTime *client* StatementStats delete_no_w_id* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivDeleteNewOrderStmtStatsResponseVerify = delivDeleteNewOrderTotalStmtTime / delivDeleteNewOrderTotalStmtStatsTime ops=max-min?
;
expr delivDeleteNewOrderStmtStatsThroughputVerify = delivDeleteNewOrderTotalStmtsExecuted / delivDeleteNewOrderTotalStmtStatsExecuted ops=max-min?
;

statspec delivGetCustIdTotalStmtStatsExecuted *client* StatementStats delivGetCustId QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivGetCustIdTotalStmtStatsTime *client* StatementStats delivGetCustId QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivGetCustIdStmtStatsResponseVerify = delivGetCustIdTotalStmtTime / delivGetCustIdTotalStmtStatsTime ops=max-min?
;
expr delivGetCustIdStmtStatsThroughputVerify = delivGetCustIdTotalStmtsExecuted / delivGetCustIdTotalStmtStatsExecuted ops=max-min?
;

statspec delivGetOrderIdTotalStmtStatsExecuted *client* StatementStats delivGetOrderId QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivGetOrderIdTotalStmtStatsTime *client* StatementStats delivGetOrderId QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivGetOrderIdStmtStatsResponseVerify = delivGetOrderIdTotalStmtTime / delivGetOrderIdTotalStmtStatsTime ops=max-min?
;
expr delivGetOrderIdStmtStatsThroughputVerify = delivGetOrderIdTotalStmtsExecuted / delivGetOrderIdTotalStmtStatsExecuted ops=max-min?
;

statspec delivSumOrderAmountTotalStmtStatsExecuted *client* StatementStats delivSumOrderAmount QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivSumOrderAmountTotalStmtStatsTime *client* StatementStats delivSumOrderAmount QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivSumOrderAmountStmtStatsResponseVerify = delivSumOrderAmountTotalStmtTime / delivSumOrderAmountTotalStmtStatsTime ops=max-min?
;
expr delivSumOrderAmountStmtStatsThroughputVerify = delivSumOrderAmountTotalStmtsExecuted / delivSumOrderAmountTotalStmtStatsExecuted ops=max-min?
;

statspec delivUpdateCarrierIdTotalStmtStatsExecuted *client* StatementStats update_o_w_id* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivUpdateCarrierIdTotalStmtStatsTime *client* StatementStats update_o_w_id* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivUpdateCarrierIdStmtStatsResponseVerify = delivUpdateCarrierIdTotalStmtTime / delivUpdateCarrierIdTotalStmtStatsTime ops=max-min?
;
expr delivUpdateCarrierIdStmtStatsThroughputVerify = delivUpdateCarrierIdTotalStmtsExecuted / delivUpdateCarrierIdTotalStmtStatsExecuted ops=max-min?
;

statspec delivUpdateCustBalDelivCntTotalStmtStatsExecuted *client* StatementStats "update_c_balance,c_delivery_cnt*" QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivUpdateCustBalDelivCntTotalStmtStatsTime *client* StatementStats "update_c_balance,c_delivery_cnt*" QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivUpdateCustBalDelivCntStmtStatsResponseVerify = delivUpdateCustBalDelivCntTotalStmtTime / delivUpdateCustBalDelivCntTotalStmtStatsTime ops=max-min?
;
expr delivUpdateCustBalDelivCntStmtStatsThroughputVerify = delivUpdateCustBalDelivCntTotalStmtsExecuted / delivUpdateCustBalDelivCntTotalStmtStatsExecuted ops=max-min?
;

statspec delivUpdateDeliveryDateTotalStmtStatsExecuted *client* StatementStats update_ol_delivery_d* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivUpdateDeliveryDateTotalStmtStatsTime *client* StatementStats update_ol_delivery_d* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivUpdateDeliveryDateStmtStatsResponseVerify = delivUpdateDeliveryDateTotalStmtTime / delivUpdateDeliveryDateTotalStmtStatsTime ops=max-min?
;
expr delivUpdateDeliveryDateStmtStatsThroughputVerify = delivUpdateDeliveryDateTotalStmtsExecuted / delivUpdateDeliveryDateTotalStmtStatsExecuted ops=max-min?
;

statspec ordStatCountCustTotalStmtStatsExecuted *client* StatementStats ordStatCountCust QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatCountCustTotalStmtStatsTime *client* StatementStats ordStatCountCust QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatCountCustStmtStatsResponseVerify = ordStatCountCustTotalStmtTime / ordStatCountCustTotalStmtStatsTime ops=max-min?
;
expr ordStatCountCustStmtStatsThroughputVerify = ordStatCountCustTotalStmtsExecuted / ordStatCountCustTotalStmtStatsExecuted ops=max-min?
;

statspec ordStatGetCustTotalStmtStatsExecuted *client* StatementStats ordStatGetCust QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetCustTotalStmtStatsTime *client* StatementStats ordStatGetCust QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetCustStmtStatsResponseVerify = ordStatGetCustTotalStmtTime / ordStatGetCustTotalStmtStatsTime ops=max-min?
;
expr ordStatGetCustStmtStatsThroughputVerify = ordStatGetCustTotalStmtsExecuted / ordStatGetCustTotalStmtStatsExecuted ops=max-min?
;

statspec ordStatGetCustBalTotalStmtStatsExecuted *client* StatementStats ordStatGetCustBal QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetCustBalTotalStmtStatsTime *client* StatementStats ordStatGetCustBal QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetCustBalStmtStatsResponseVerify = ordStatGetCustBalTotalStmtTime / ordStatGetCustBalTotalStmtStatsTime ops=max-min?
;
expr ordStatGetCustBalStmtStatsThroughputVerify = ordStatGetCustBalTotalStmtsExecuted / ordStatGetCustBalTotalStmtStatsExecuted ops=max-min?
;

statspec ordStatGetNewestOrdTotalStmtStatsExecuted *client* StatementStats ordStatGetNewestOrd QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetNewestOrdTotalStmtStatsTime *client* StatementStats ordStatGetNewestOrd QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetNewestOrdStmtStatsResponseVerify = ordStatGetNewestOrdTotalStmtTime / ordStatGetNewestOrdTotalStmtStatsTime ops=max-min?
;
expr ordStatGetNewestOrdStmtStatsThroughputVerify = ordStatGetNewestOrdTotalStmtsExecuted / ordStatGetNewestOrdTotalStmtStatsExecuted ops=max-min?
;

statspec ordStatGetOrderTotalStmtStatsExecuted *client* StatementStats ordStatGetOrder QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetOrderTotalStmtStatsTime *client* StatementStats ordStatGetOrder QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetOrderStmtStatsResponseVerify = ordStatGetOrderTotalStmtTime / ordStatGetOrderTotalStmtStatsTime ops=max-min?
;
expr ordStatGetOrderStmtStatsThroughputVerify = ordStatGetOrderTotalStmtsExecuted / ordStatGetOrderTotalStmtStatsExecuted ops=max-min?
;

statspec ordStatGetOrderLinesTotalStmtStatsExecuted *client* StatementStats ordStatGetOrderLines QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetOrderLinesTotalStmtStatsTime *client* StatementStats ordStatGetOrderLines QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetOrderLinesStmtStatsResponseVerify = ordStatGetOrderLinesTotalStmtTime / ordStatGetOrderLinesTotalStmtStatsTime ops=max-min?
;
expr ordStatGetOrderLinesStmtStatsThroughputVerify = ordStatGetOrderLinesTotalStmtsExecuted / ordStatGetOrderLinesTotalStmtStatsExecuted ops=max-min?
;

statspec payCountCustTotalStmtStatsExecuted *client* StatementStats payCountCust QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payCountCustTotalStmtStatsTime *client* StatementStats payCountCust QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payCountCustStmtStatsResponseVerify = payCountCustTotalStmtTime / payCountCustTotalStmtStatsTime ops=max-min?
;
expr payCountCustStmtStatsThroughputVerify = payCountCustTotalStmtsExecuted / payCountCustTotalStmtStatsExecuted ops=max-min?
;

statspec payCursorCustByNameTotalStmtStatsExecuted *client* StatementStats payCursorCustByName QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payCursorCustByNameTotalStmtStatsTime *client* StatementStats payCursorCustByName QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payCursorCustByNameStmtStatsResponseVerify = payCursorCustByNameTotalStmtTime / payCursorCustByNameTotalStmtStatsTime ops=max-min?
;
expr payCursorCustByNameStmtStatsThroughputVerify = payCursorCustByNameTotalStmtsExecuted / payCursorCustByNameTotalStmtStatsExecuted ops=max-min?
;

statspec payGetCustTotalStmtStatsExecuted *client* StatementStats payGetCust QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetCustTotalStmtStatsTime *client* StatementStats payGetCust QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetCustStmtStatsResponseVerify = payGetCustTotalStmtTime / payGetCustTotalStmtStatsTime ops=max-min?
;
expr payGetCustStmtStatsThroughputVerify = payGetCustTotalStmtsExecuted / payGetCustTotalStmtStatsExecuted ops=max-min?
;

statspec payGetCustCdataTotalStmtStatsExecuted *client* StatementStats payGetCustCdata QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetCustCdataTotalStmtStatsTime *client* StatementStats payGetCustCdata QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetCustCdataStmtStatsResponseVerify = payGetCustCdataTotalStmtTime / payGetCustCdataTotalStmtStatsTime ops=max-min?
;
expr payGetCustCdataStmtStatsThroughputVerify = payGetCustCdataTotalStmtsExecuted / payGetCustCdataTotalStmtStatsExecuted ops=max-min?
;

statspec payGetDistTotalStmtStatsExecuted *client* StatementStats payGetDist QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetDistTotalStmtStatsTime *client* StatementStats payGetDist QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetDistStmtStatsResponseVerify = payGetDistTotalStmtTime / payGetDistTotalStmtStatsTime ops=max-min?
;
expr payGetDistStmtStatsThroughputVerify = payGetDistTotalStmtsExecuted / payGetDistTotalStmtStatsExecuted ops=max-min?
;

statspec payGetWhseTotalStmtStatsExecuted *client* StatementStats payGetWhse QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetWhseTotalStmtStatsTime *client* StatementStats payGetWhse QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetWhseStmtStatsResponseVerify = payGetWhseTotalStmtTime / payGetWhseTotalStmtStatsTime ops=max-min?
;
expr payGetWhseStmtStatsThroughputVerify = payGetWhseTotalStmtsExecuted / payGetWhseTotalStmtStatsExecuted ops=max-min?
;

statspec payInsertHistTotalStmtStatsExecuted *client* StatementStats insert_h_c_id* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payInsertHistTotalStmtStatsTime *client* StatementStats insert_h_c_id* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payInsertHistStmtStatsResponseVerify = payInsertHistTotalStmtTime / payInsertHistTotalStmtStatsTime ops=max-min?
;
expr payInsertHistStmtStatsThroughputVerify = payInsertHistTotalStmtsExecuted / payInsertHistTotalStmtStatsExecuted ops=max-min?
;

statspec payUpdateCustBalTotalStmtStatsExecuted *client* StatementStats "update_c_balance,c_balance_CUSTOMER*" QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateCustBalTotalStmtStatsTime *client* StatementStats "update_c_balance,c_balance_CUSTOMER*" QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateCustBalStmtStatsResponseVerify = payUpdateCustBalTotalStmtTime / payUpdateCustBalTotalStmtStatsTime ops=max-min?
;
expr payUpdateCustBalStmtStatsThroughputVerify = payUpdateCustBalTotalStmtsExecuted / payUpdateCustBalTotalStmtStatsExecuted ops=max-min?
;

statspec payUpdateCustBalCdataTotalStmtStatsExecuted *client* StatementStats "update_c_balance,c_data*" QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateCustBalCdataTotalStmtStatsTime *client* StatementStats "update_c_balance,c_data*" QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateCustBalCdataStmtStatsResponseVerify = payUpdateCustBalCdataTotalStmtTime / payUpdateCustBalCdataTotalStmtStatsTime ops=max-min?
;
expr payUpdateCustBalCdataStmtStatsThroughputVerify = payUpdateCustBalCdataTotalStmtsExecuted / payUpdateCustBalCdataTotalStmtStatsExecuted ops=max-min?
;

statspec payUpdateDistTotalStmtStatsExecuted *client* StatementStats update_d_ytd* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateDistTotalStmtStatsTime *client* StatementStats update_d_ytd* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateDistStmtStatsResponseVerify = payUpdateDistTotalStmtTime / payUpdateDistTotalStmtStatsTime ops=max-min?
;
expr payUpdateDistStmtStatsThroughputVerify = payUpdateDistTotalStmtsExecuted / payUpdateDistTotalStmtStatsExecuted ops=max-min?
;

statspec payUpdateWhseTotalStmtStatsExecuted *client* StatementStats update_w_ytd* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateWhseTotalStmtStatsTime *client* StatementStats update_w_ytd* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateWhseStmtStatsResponseVerify = payUpdateWhseTotalStmtTime / payUpdateWhseTotalStmtStatsTime ops=max-min?
;
expr payUpdateWhseStmtStatsThroughputVerify = payUpdateWhseTotalStmtsExecuted / payUpdateWhseTotalStmtStatsExecuted ops=max-min?
;

statspec stmtGetCustWhseTotalStmtStatsExecuted *client* StatementStats stmtGetCustWhse QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetCustWhseTotalStmtStatsTime *client* StatementStats stmtGetCustWhse QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetCustWhseStmtStatsResponseVerify = stmtGetCustWhseTotalStmtTime / stmtGetCustWhseTotalStmtStatsTime ops=max-min?
;
expr stmtGetCustWhseStmtStatsThroughputVerify = stmtGetCustWhseTotalStmtsExecuted / stmtGetCustWhseTotalStmtStatsExecuted ops=max-min?
;

statspec stmtGetDistTotalStmtStatsExecuted *client* StatementStats stmtGetDist QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetDistTotalStmtStatsTime *client* StatementStats stmtGetDist QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetDistStmtStatsResponseVerify = stmtGetDistTotalStmtTime / stmtGetDistTotalStmtStatsTime ops=max-min?
;
expr stmtGetDistStmtStatsThroughputVerify = stmtGetDistTotalStmtsExecuted / stmtGetDistTotalStmtStatsExecuted ops=max-min?
;

statspec stmtGetItemTotalStmtStatsExecuted *client* StatementStats stmtGetItem QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetItemTotalStmtStatsTime *client* StatementStats stmtGetItem QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetItemStmtStatsResponseVerify = stmtGetItemTotalStmtTime / stmtGetItemTotalStmtStatsTime ops=max-min?
;
expr stmtGetItemStmtStatsThroughputVerify = stmtGetItemTotalStmtsExecuted / stmtGetItemTotalStmtStatsExecuted ops=max-min?
;

statspec stmtGetStockTotalStmtStatsExecuted *client* StatementStats stmtGetStock QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetStockTotalStmtStatsTime *client* StatementStats stmtGetStock QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetStockStmtStatsResponseVerify = stmtGetStockTotalStmtTime / stmtGetStockTotalStmtStatsTime ops=max-min?
;
expr stmtGetStockStmtStatsThroughputVerify = stmtGetStockTotalStmtsExecuted / stmtGetStockTotalStmtStatsExecuted ops=max-min?
;

statspec stmtInsertNewOrderTotalStmtStatsExecuted *client* StatementStats insert_no_w_id* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtInsertNewOrderTotalStmtStatsTime *client* StatementStats insert_no_w_id* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtInsertNewOrderStmtStatsResponseVerify = stmtInsertNewOrderTotalStmtTime / stmtInsertNewOrderTotalStmtStatsTime ops=max-min?
;
expr stmtInsertNewOrderStmtStatsThroughputVerify = stmtInsertNewOrderTotalStmtsExecuted / stmtInsertNewOrderTotalStmtStatsExecuted ops=max-min?
;

statspec stmtInsertOOrderTotalStmtStatsExecuted *client* StatementStats insert_o_w_id* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtInsertOOrderTotalStmtStatsTime *client* StatementStats insert_o_w_id* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtInsertOOrderStmtStatsResponseVerify = stmtInsertOOrderTotalStmtTime / stmtInsertOOrderTotalStmtStatsTime ops=max-min?
;
expr stmtInsertOOrderStmtStatsThroughputVerify = stmtInsertOOrderTotalStmtsExecuted / stmtInsertOOrderTotalStmtStatsExecuted ops=max-min?
;

statspec stmtInsertOrderLineTotalStmtStatsExecuted *client* StatementStats insert_ol_w_id* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtInsertOrderLineTotalStmtStatsTime *client* StatementStats insert_ol_w_id* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtInsertOrderLineStmtStatsResponseVerify = stmtInsertOrderLineTotalStmtTime / stmtInsertOrderLineTotalStmtStatsTime ops=max-min?
;
expr stmtInsertOrderLineStmtStatsThroughputVerify = stmtInsertOrderLineTotalStmtsExecuted / stmtInsertOrderLineTotalStmtStatsExecuted ops=max-min?
;

//statspec stmtUpdateDistTotalStmtStatsExecuted *client* StatementStats stmtUpdateDist* QNNumExecutions
//filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
//;
//statspec stmtUpdateDistTotalStmtStatsTime *client* StatementStats stmtUpdateDist* QNTotalExecutionTime
//filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
//;
//expr stmtUpdateDistStmtStatsResponseVerify = stmtUpdateDistTotalStmtTime / stmtUpdateDistTotalStmtStatsTime ops=max-min?
//;
//expr stmtUpdateDistStmtStatsThroughputVerify = stmtUpdateDistTotalStmtsExecuted / stmtUpdateDistTotalStmtStatsExecuted ops=max-min?
//;

statspec stmtUpdateStockTotalStmtStatsExecuted *client* StatementStats update_s_quantity* QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtUpdateStockTotalStmtStatsTime *client* StatementStats update_s_quantity* QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtUpdateStockStmtStatsResponseVerify = stmtUpdateStockTotalStmtTime / stmtUpdateStockTotalStmtStatsTime ops=max-min?
;
expr stmtUpdateStockStmtStatsThroughputVerify = stmtUpdateStockTotalStmtsExecuted / stmtUpdateStockTotalStmtStatsExecuted ops=max-min?
;

statspec stockGetCountStockTotalStmtStatsExecuted *client* StatementStats stockGetCountStock QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stockGetCountStockTotalStmtStatsTime *client* StatementStats stockGetCountStock QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stockGetCountStockStmtStatsResponseVerify = stockGetCountStockTotalStmtTime / stockGetCountStockTotalStmtStatsTime ops=max-min?
;
expr stockGetCountStockStmtStatsThroughputVerify = stockGetCountStockTotalStmtsExecuted / stockGetCountStockTotalStmtStatsExecuted ops=max-min?
;

statspec stockGetDistOrderIdTotalStmtStatsExecuted *client* StatementStats stockGetDistOrderId QNNumExecutions
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stockGetDistOrderIdTotalStmtStatsTime *client* StatementStats stockGetDistOrderId QNTotalExecutionTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stockGetDistOrderIdStmtStatsResponseVerify = stockGetDistOrderIdTotalStmtTime / stockGetDistOrderIdTotalStmtStatsTime ops=max-min?
;
expr stockGetDistOrderIdStmtStatsThroughputVerify = stockGetDistOrderIdTotalStmtsExecuted / stockGetDistOrderIdTotalStmtStatsExecuted ops=max-min?
;
