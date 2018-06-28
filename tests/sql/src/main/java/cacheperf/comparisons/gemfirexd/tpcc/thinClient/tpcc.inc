include $JTESTS/hydraconfig/hydraparams1.inc;
include $JTESTS/hydraconfig/gemfirexd/topology_2_locator.inc;
include $JTESTS/hydraconfig/gemfirexd/performance.inc;
include $JTESTS/cacheperf/comparisons/gemfirexd/tpcc/thinClient/configure${api}.inc;
include $JTESTS/cacheperf/comparisons/gemfirexd/tpcc/thinClient/tpcc${api}.inc;

hydra.Prms-totalTaskTimeSec = 3600000; // time-based workload
hydra.Prms-maxResultWaitSec = 1800;

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
