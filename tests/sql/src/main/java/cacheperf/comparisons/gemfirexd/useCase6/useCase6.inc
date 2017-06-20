include $JTESTS/hydraconfig/hydraparams1.inc;
include $JTESTS/hydraconfig/gemfirexd/topology_3_locator.inc;
include $JTESTS/hydraconfig/gemfirexd/performance.inc;

hydra.Prms-totalTaskTimeSec = 3600000   ; // time-based workload
hydra.Prms-maxResultWaitSec = 3600;

hydra.VmPrms-extraVMArgs =
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms256m -Xmx256m\", ${locatorHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms${clientHeapMB}m -Xmx${clientHeapMB}m\",
                                    ${clientHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xss256K -Xms${serverHeapMB}m -Xmx${serverHeapMB}m\",
                                   ${serverHosts}, true)" ncf
  ;
hydra.VmPrms-extraVMArgs +=
  fcn "hydra.TestConfigFcns.duplicate
       (\"-XX:+DisableExplicitGC\", ${locatorHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-XX:+DisableExplicitGC\", ${clientHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicateString
         (\"-XX:+UseParNewGC -XX:+DisableExplicitGC -XX:+UseParNewGC\", ${serverHosts}, true)" ncf
  ;

hydra.gemfirexd.FabricServerPrms-conserveSockets = false;
hydra.gemfirexd.FabricServerPrms-distributedSystem = ds;

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

THREADGROUP newstore
   totalThreads = fcn "(${${C}Hosts} * ${${C}VMsPerHost} *  ${${C}ThreadsPerVM}) "
                  ncf
   totalVMs     = fcn "(${${C}Hosts} * ${${C}VMsPerHost})" ncf
   clientNames  = fcn "hydra.TestConfigFcns.generateNames(\"${C}\",
                                ${${C}Hosts}, true)"
                  ncf;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = openStatisticsTask
          threadGroups = locator, server, client, newstore
         ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = executeDDLTask
          threadGroups = server
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = generateAndLoadDataTask
          threadGroups = client
          ;

/*
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = storeUniqueDataFromTableTask
          threadGroups = client
          ;
*/

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = selectAndUpdateTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = generateAndLoadDataTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = rebalanceBucketsTask
          threadGroups = server
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = selectAndUpdateTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = generateAndLoadDataTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = rebalanceBucketsTask
          threadGroups = server
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = selectAndUpdateTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = generateAndLoadDataTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = rebalanceBucketsTask
          threadGroups = server
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = selectAndUpdateTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = generateAndLoadDataTask
          threadGroups = client
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = rebalanceBucketsTask
          threadGroups = server
          ;

INITTASK  taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = selectAndUpdateTask
          threadGroups = client
          ;

CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = reportTrimIntervalsTask
          threadGroups = client, server, newstore
          ;

CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = closeStatisticsTask
          threadGroups = client
          ;

CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.useCase6.UseCase6Client
          taskMethod = closeStatisticsTask
          threadGroups = server, newstore
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

cacheperf.comparisons.gemfirexd.useCase6.UseCase6Prms-ddlFile  = ${ddlFile};
cacheperf.comparisons.gemfirexd.useCase6.UseCase6Prms-timeStmts = true;
cacheperf.comparisons.gemfirexd.useCase6.UseCase6Prms-mapperFile = "cacheperf/comparisons/gemfirexd/useCase6/run/useCase6Mapper.inc";
perffmwk.PerfReportPrms-statisticsSpecification = $JTESTS/cacheperf/comparisons/gemfirexd/useCase6/specs/useCase6.spec;
sql.SQLPrms-insertInBB = false;
cacheperf.comparisons.gemfirexd.useCase6.UseCase6Prms-dmlTables=
"app.OLTP_PNP_Subscriptions";
cacheperf.comparisons.gemfirexd.useCase6.UseCase6Prms-initialRowCount  = ${initialRowCount};
