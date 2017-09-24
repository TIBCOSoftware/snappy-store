include $JTESTS/hydraconfig/hydraparams1.inc;
include $JTESTS/hydraconfig/gemfirexd/topology_2_locator.inc;
include $JTESTS/hydraconfig/gemfirexd/performance.inc;

//hydra.Prms-clientShutdownHook = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
//                                dumpBucketsHook;

hydra.Prms-totalTaskTimeSec = 3600000; // time-based workload
hydra.Prms-maxResultWaitSec = 1800;

hydra.gemfirexd.FabricServerPrms-conserveSockets = false;
hydra.gemfirexd.FabricServerPrms-distributedSystem = ds;
hydra.gemfirexd.FabricServerPrms-hostData = false false true;

hydra.VmPrms-extraVMArgs =
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms256m -Xmx256m\", ${locatorHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms${clientHeapMB}m -Xmx${clientHeapMB}m\",
                                    ${clientHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms${serverHeapMB}m -Xmx${serverHeapMB}m\",
                                   ${serverHosts}, true)" ncf
  ;
hydra.VmPrms-extraVMArgs +=
  fcn "hydra.TestConfigFcns.duplicate
       (\"-XX:+DisableExplicitGC\", ${locatorHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:+DisableExplicitGC\", ${clientHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicateString
       (\"-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:+DisableExplicitGC -XX:CMSInitiatingOccupancyFraction=50\", ${serverHosts}, true)" ncf
  ;

THREADGROUP locator
  totalThreads = fcn ${locatorHosts} * ${locatorVMsPerHost} * ${locatorThreadsPerVM}
                 ncf
  clientNames  = fcn "hydra.TestConfigFcns.generateNames (\"locator\", ${locatorHosts}, true)"
                 ncf;
THREADGROUP client
  totalThreads = fcn ${clientHosts} * ${clientVMsPerHost} * ${clientThreadsPerVM}
                 ncf
  clientNames  = fcn "hydra.TestConfigFcns.generateNames(\"client\", ${clientHosts}, true)"
                 ncf;
THREADGROUP server
  totalThreads = fcn ${serverHosts} * ${serverVMsPerHost} * ${serverThreadsPerVM}
                 ncf
  clientNames  = fcn "hydra.TestConfigFcns.generateNames(\"server\", ${serverHosts}, true)"
                 ncf;

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
          threadGroups = server, client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = connectPeerClientTask
          threadGroups = server, client
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
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = rebalanceBucketsTask
          threadGroups = server
          ;
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
//INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
//          taskMethod = checkBucketsTask
//          threadGroups = server
//          ;
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
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = configureDebuggingTask
          threadGroups = server
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = reportTrimIntervalsTask
          threadGroups = client, server
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = dumpQueryPlansTask
          threadGroups = server
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

cacheperf.CachePerfPrms-batchSeconds              = 300;
cacheperf.CachePerfPrms-batchTerminatorMethod     = terminateOnBatchSeconds;
cacheperf.CachePerfPrms-trimSeconds               = ${trimSeconds};
cacheperf.CachePerfPrms-warmupTerminatorMethod    = terminateOnTrimSeconds;
cacheperf.CachePerfPrms-warmupTerminatorFrequency = 10 seconds;
cacheperf.CachePerfPrms-workSeconds               = ${workSeconds};
cacheperf.CachePerfPrms-taskTerminatorMethod      = terminateOnTotalSeconds;
cacheperf.CachePerfPrms-taskTerminatorFrequency   = 10 seconds;

cacheperf.CachePerfPrms-maxKeys = 1; // not used

cacheperf.comparisons.gemfirexd.QueryPerfPrms-txIsolation = ${txIsolation};

objects.query.QueryPrms-api = ${api};
objects.query.QueryPrms-logQueries = ${logQueries};

cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-numWarehouses = ${numWarehouses};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-numItems = ${numItems};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-numDistrictsPerWarehouse = ${numDistrictsPerWarehouse};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-numCustomersPerDistrict = ${numCustomersPerDistrict};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-tableFileNum  = ${tableFileNum};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-indexFileNum  = ${indexFileNum};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-createIndexes = ${createIndexes};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-timeStmts = true;
