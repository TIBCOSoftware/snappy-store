//------------------------------------------------------------------------------
// individual statements
// set cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-timeStmts = true
//------------------------------------------------------------------------------

statspec delivDeleteNewOrderStmtsPerSecond *client* TPCCStats * delivDeleteNewOrderStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivDeleteNewOrderTotalStmtsExecuted *client* TPCCStats * delivDeleteNewOrderStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivDeleteNewOrderTotalStmtTime *client* TPCCStats * delivDeleteNewOrderStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivDeleteNewOrderStmtResponseTime = delivDeleteNewOrderTotalStmtTime / delivDeleteNewOrderTotalStmtsExecuted ops=max-min?
;

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

statspec delivGetOrderIdStmtsPerSecond *client* TPCCStats * delivGetOrderIdStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivGetOrderIdTotalStmtsExecuted *client* TPCCStats * delivGetOrderIdStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivGetOrderIdTotalStmtTime *client* TPCCStats * delivGetOrderIdStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivGetOrderIdStmtResponseTime = delivGetOrderIdTotalStmtTime / delivGetOrderIdTotalStmtsExecuted ops=max-min?
;

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

statspec delivUpdateCarrierIdStmtsPerSecond *client* TPCCStats * delivUpdateCarrierIdStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivUpdateCarrierIdTotalStmtsExecuted *client* TPCCStats * delivUpdateCarrierIdStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivUpdateCarrierIdTotalStmtTime *client* TPCCStats * delivUpdateCarrierIdStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivUpdateCarrierIdStmtResponseTime = delivUpdateCarrierIdTotalStmtTime / delivUpdateCarrierIdTotalStmtsExecuted ops=max-min?
;

statspec delivUpdateCustBalDelivCntStmtsPerSecond *client* TPCCStats * delivUpdateCustBalDelivCntStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivUpdateCustBalDelivCntTotalStmtsExecuted *client* TPCCStats * delivUpdateCustBalDelivCntStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivUpdateCustBalDelivCntTotalStmtTime *client* TPCCStats * delivUpdateCustBalDelivCntStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivUpdateCustBalDelivCntStmtResponseTime = delivUpdateCustBalDelivCntTotalStmtTime / delivUpdateCustBalDelivCntTotalStmtsExecuted ops=max-min?
;

statspec delivUpdateDeliveryDateStmtsPerSecond *client* TPCCStats * delivUpdateDeliveryDateStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec delivUpdateDeliveryDateTotalStmtsExecuted *client* TPCCStats * delivUpdateDeliveryDateStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec delivUpdateDeliveryDateTotalStmtTime *client* TPCCStats * delivUpdateDeliveryDateStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr delivUpdateDeliveryDateStmtResponseTime = delivUpdateDeliveryDateTotalStmtTime / delivUpdateDeliveryDateTotalStmtsExecuted ops=max-min?
;

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

statspec ordStatGetCustBalStmtsPerSecond *client* TPCCStats * ordStatGetCustBalStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec ordStatGetCustBalTotalStmtsExecuted *client* TPCCStats * ordStatGetCustBalStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetCustBalTotalStmtTime *client* TPCCStats * ordStatGetCustBalStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetCustBalStmtResponseTime = ordStatGetCustBalTotalStmtTime / ordStatGetCustBalTotalStmtsExecuted ops=max-min?
;

statspec ordStatGetNewestOrdStmtsPerSecond *client* TPCCStats * ordStatGetNewestOrdStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec ordStatGetNewestOrdTotalStmtsExecuted *client* TPCCStats * ordStatGetNewestOrdStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetNewestOrdTotalStmtTime *client* TPCCStats * ordStatGetNewestOrdStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetNewestOrdStmtResponseTime = ordStatGetNewestOrdTotalStmtTime / ordStatGetNewestOrdTotalStmtsExecuted ops=max-min?
;

statspec ordStatGetOrderStmtsPerSecond *client* TPCCStats * ordStatGetOrderStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec ordStatGetOrderTotalStmtsExecuted *client* TPCCStats * ordStatGetOrderStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetOrderTotalStmtTime *client* TPCCStats * ordStatGetOrderStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetOrderStmtResponseTime = ordStatGetOrderTotalStmtTime / ordStatGetOrderTotalStmtsExecuted ops=max-min?
;

statspec ordStatGetOrderLinesStmtsPerSecond *client* TPCCStats * ordStatGetOrderLinesStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec ordStatGetOrderLinesTotalStmtsExecuted *client* TPCCStats * ordStatGetOrderLinesStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec ordStatGetOrderLinesTotalStmtTime *client* TPCCStats * ordStatGetOrderLinesStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr ordStatGetOrderLinesStmtResponseTime = ordStatGetOrderLinesTotalStmtTime / ordStatGetOrderLinesTotalStmtsExecuted ops=max-min?
;

statspec payCountCustStmtsPerSecond *client* TPCCStats * payCountCustStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payCountCustTotalStmtsExecuted *client* TPCCStats * payCountCustStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payCountCustTotalStmtTime *client* TPCCStats * payCountCustStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payCountCustStmtResponseTime = payCountCustTotalStmtTime / payCountCustTotalStmtsExecuted ops=max-min?
;

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

statspec payGetCustStmtsPerSecond *client* TPCCStats * payGetCustStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payGetCustTotalStmtsExecuted *client* TPCCStats * payGetCustStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetCustTotalStmtTime *client* TPCCStats * payGetCustStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetCustStmtResponseTime = payGetCustTotalStmtTime / payGetCustTotalStmtsExecuted ops=max-min?
;

statspec payGetCustCdataStmtsPerSecond *client* TPCCStats * payGetCustCdataStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payGetCustCdataTotalStmtsExecuted *client* TPCCStats * payGetCustCdataStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetCustCdataTotalStmtTime *client* TPCCStats * payGetCustCdataStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetCustCdataStmtResponseTime = payGetCustCdataTotalStmtTime / payGetCustCdataTotalStmtsExecuted ops=max-min?
;

statspec payGetDistStmtsPerSecond *client* TPCCStats * payGetDistStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payGetDistTotalStmtsExecuted *client* TPCCStats * payGetDistStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payGetDistTotalStmtTime *client* TPCCStats * payGetDistStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payGetDistStmtResponseTime = payGetDistTotalStmtTime / payGetDistTotalStmtsExecuted ops=max-min?
;

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

statspec payInsertHistStmtsPerSecond *client* TPCCStats * payInsertHistStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payInsertHistTotalStmtsExecuted *client* TPCCStats * payInsertHistStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payInsertHistTotalStmtTime *client* TPCCStats * payInsertHistStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payInsertHistStmtResponseTime = payInsertHistTotalStmtTime / payInsertHistTotalStmtsExecuted ops=max-min?
;

statspec payUpdateCustBalStmtsPerSecond *client* TPCCStats * payUpdateCustBalStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payUpdateCustBalTotalStmtsExecuted *client* TPCCStats * payUpdateCustBalStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateCustBalTotalStmtTime *client* TPCCStats * payUpdateCustBalStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateCustBalStmtResponseTime = payUpdateCustBalTotalStmtTime / payUpdateCustBalTotalStmtsExecuted ops=max-min?
;

statspec payUpdateCustBalCdataStmtsPerSecond *client* TPCCStats * payUpdateCustBalCdataStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payUpdateCustBalCdataTotalStmtsExecuted *client* TPCCStats * payUpdateCustBalCdataStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateCustBalCdataTotalStmtTime *client* TPCCStats * payUpdateCustBalCdataStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateCustBalCdataStmtResponseTime = payUpdateCustBalCdataTotalStmtTime / payUpdateCustBalCdataTotalStmtsExecuted ops=max-min?
;

statspec payUpdateDistStmtsPerSecond *client* TPCCStats * payUpdateDistStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec payUpdateDistTotalStmtsExecuted *client* TPCCStats * payUpdateDistStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec payUpdateDistTotalStmtTime *client* TPCCStats * payUpdateDistStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr payUpdateDistStmtResponseTime = payUpdateDistTotalStmtTime / payUpdateDistTotalStmtsExecuted ops=max-min?
;

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

statspec stmtGetDistStmtsPerSecond *client* TPCCStats * stmtGetDistStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtGetDistTotalStmtsExecuted *client* TPCCStats * stmtGetDistStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetDistTotalStmtTime *client* TPCCStats * stmtGetDistStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetDistStmtResponseTime = stmtGetDistTotalStmtTime / stmtGetDistTotalStmtsExecuted ops=max-min?
;

statspec stmtGetItemStmtsPerSecond *client* TPCCStats * stmtGetItemStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtGetItemTotalStmtsExecuted *client* TPCCStats * stmtGetItemStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetItemTotalStmtTime *client* TPCCStats * stmtGetItemStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetItemStmtResponseTime = stmtGetItemTotalStmtTime / stmtGetItemTotalStmtsExecuted ops=max-min?
;

statspec stmtGetStockStmtsPerSecond *client* TPCCStats * stmtGetStockStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtGetStockTotalStmtsExecuted *client* TPCCStats * stmtGetStockStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtGetStockTotalStmtTime *client* TPCCStats * stmtGetStockStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtGetStockStmtResponseTime = stmtGetStockTotalStmtTime / stmtGetStockTotalStmtsExecuted ops=max-min?
;

statspec stmtInsertNewOrderStmtsPerSecond *client* TPCCStats * stmtInsertNewOrderStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtInsertNewOrderTotalStmtsExecuted *client* TPCCStats * stmtInsertNewOrderStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtInsertNewOrderTotalStmtTime *client* TPCCStats * stmtInsertNewOrderStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtInsertNewOrderStmtResponseTime = stmtInsertNewOrderTotalStmtTime / stmtInsertNewOrderTotalStmtsExecuted ops=max-min?
;

statspec stmtInsertOOrderStmtsPerSecond *client* TPCCStats * stmtInsertOOrderStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtInsertOOrderTotalStmtsExecuted *client* TPCCStats * stmtInsertOOrderStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtInsertOOrderTotalStmtTime *client* TPCCStats * stmtInsertOOrderStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtInsertOOrderStmtResponseTime = stmtInsertOOrderTotalStmtTime / stmtInsertOOrderTotalStmtsExecuted ops=max-min?
;

statspec stmtInsertOrderLineStmtsPerSecond *client* TPCCStats * stmtInsertOrderLineStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtInsertOrderLineTotalStmtsExecuted *client* TPCCStats * stmtInsertOrderLineStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtInsertOrderLineTotalStmtTime *client* TPCCStats * stmtInsertOrderLineStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtInsertOrderLineStmtResponseTime = stmtInsertOrderLineTotalStmtTime / stmtInsertOrderLineTotalStmtsExecuted ops=max-min?
;

statspec stmtUpdateDistStmtsPerSecond *client* TPCCStats * stmtUpdateDistStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtUpdateDistTotalStmtsExecuted *client* TPCCStats * stmtUpdateDistStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtUpdateDistTotalStmtTime *client* TPCCStats * stmtUpdateDistStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtUpdateDistStmtResponseTime = stmtUpdateDistTotalStmtTime / stmtUpdateDistTotalStmtsExecuted ops=max-min?
;

statspec stmtUpdateStockStmtsPerSecond *client* TPCCStats * stmtUpdateStockStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stmtUpdateStockTotalStmtsExecuted *client* TPCCStats * stmtUpdateStockStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stmtUpdateStockTotalStmtTime *client* TPCCStats * stmtUpdateStockStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stmtUpdateStockStmtResponseTime = stmtUpdateStockTotalStmtTime / stmtUpdateStockTotalStmtsExecuted ops=max-min?
;

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

statspec stockGetDistOrderIdStmtsPerSecond *client* TPCCStats * stockGetDistOrderIdStmtsExecuted
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=transactions
;
statspec stockGetDistOrderIdTotalStmtsExecuted *client* TPCCStats * stockGetDistOrderIdStmtsExecuted
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec stockGetDistOrderIdTotalStmtTime *client* TPCCStats * stockGetDistOrderIdStmtTime
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
expr stockGetDistOrderIdStmtResponseTime = stockGetDistOrderIdTotalStmtTime / stockGetDistOrderIdTotalStmtsExecuted ops=max-min?
;

