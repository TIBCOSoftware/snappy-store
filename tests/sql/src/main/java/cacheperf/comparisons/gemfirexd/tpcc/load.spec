//------------------------------------------------------------------------------
// load speeds
//------------------------------------------------------------------------------

statspec loadCustomersPerSecond * TPCCStats * customers
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=customer
;

//statspec loadDistrictsPerSecond * TPCCStats * districts
//filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=district
//;

//statspec loadItemsPerSecond * TPCCStats * items
//filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=item
//;

statspec loadOrdersPerSecond * TPCCStats * orders
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=order
;

statspec loadStocksPerSecond * TPCCStats * stocks
filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=stock
;

//statspec loadWarehousesPerSecond * TPCCStats * warehouses
//filter=perSecond combine=combineAcrossArchives ops=min,max,mean?,stddev trimspec=warehouse
//;
