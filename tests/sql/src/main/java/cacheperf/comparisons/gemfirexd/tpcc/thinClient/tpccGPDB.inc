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
          taskMethod = connectThinClientTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = openStatisticsTask
          threadGroups = locator, server, client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = dropTablesTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createTablesTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createIndexesOnTablesTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadWarehouseDataTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadItemDataTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadStockDataTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadDistrictDataTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadCustomerDataTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadOrderDataTask
          threadGroups = client
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = reportTrimIntervalsTask
          threadGroups = client, server
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = dropTablesTask
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
