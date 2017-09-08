INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = initializeBlackboardTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = readBlackboardTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = createLocatorTask
          threadGroups = locator
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = startLocatorTask
          threadGroups = locator
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = startFabricServerTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = setEvictionHeapPercentageTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = startNetworkServerTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = connectPeerClientTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = connectThinClientTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = openStatisticsTask
          threadGroups = locator, server, client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = dropTablesTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createTablesTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createIndexesOnTablesTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = createBucketsTask
          threadGroups = server
          ;
//INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
//          taskMethod = rebalanceBucketsTask
//          threadGroups = server
//          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = printBucketListTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = setPrimaryBucketListTask
          threadGroups = server
          ;
//INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
//          taskMethod = dumpBucketsTask
//          threadGroups = sender, receiver
//          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = checkBucketsTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadWarehouseDataTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadItemDataTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadStockDataTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadDistrictDataTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadCustomerDataTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadOrderDataTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = checkDataLoadTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = closeConnectionTask
          threadGroups = server
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = startScriptTask
          threadGroups = server, client, locator
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = configureDebuggingTask
          threadGroups = server
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = stopScriptTask
          threadGroups = server, client, locator
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = reportTrimIntervalsTask
          threadGroups = client, server
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = dumpQueryPlansTask
          threadGroups = server
          ;
//CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
//          taskMethod = checkDataLoadTask
//          threadGroups = server
//          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = dropTablesTask
          threadGroups = client
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = closeConnectionTask
          threadGroups = client
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = closeStatisticsTask
          threadGroups = client
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = closeStatisticsTask
          threadGroups = server
          ;
