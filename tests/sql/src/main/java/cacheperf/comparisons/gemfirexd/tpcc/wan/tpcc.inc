include $JTESTS/hydraconfig/hydraparams1.inc;
include $JTESTS/hydraconfig/gemfirexd/performance.inc;

//------------------------------------------------------------------------------
// General
//------------------------------------------------------------------------------

hydra.Prms-testTopology = "There are 3 sites. Each site has a locator, two senders, a receiver, and a thin client. There are ${senderThreadsPerVM} threads per sender, ${receiverThreadsPerVM} threads per receiver, and ${clientThreadsPerVM} threads per client. Each sender is either primary or secondary for both remote sites. All senders and receivers host all data and are responsible for data loading in the INITTASKs. Each logical hydra thread inserts a mutually exclusive subset of the rows for each table. The rows loaded in each datahost are the ones corresponding to its (precreated) primary buckets. After all data is loaded and the gateway sender queues drain, the thin clients do the TPC-C main workload for a specified amount of time, then wait for the gateway sender queues to drain. Performance is measured for the main workload from the time all client threads are warmed up until all queues are empty. The test uses transaction isolation level ${txIsolation}. The tables are configured using tables${tableFileNum}.${api}.txt, indexes${indexFileNum}.${api}.txt, diskstores${diskStoreFileNum}.${api}.txt in cacheperf/comparisons/gemfirexd/tpcc/ddl.";

hydra.Prms-totalTaskTimeSec = 3600000; // time-based workload
hydra.Prms-maxResultWaitSec = 1800;

hydra.Prms-manageLocatorAgents = false;

hydra.HostPrms-names =
  locator_1_1             locator_2_1             locator_3_1
  client_1_1              client_2_1              client_3_1
  sender_1_1              sender_2_1              sender_3_1
  receiver_1_1            receiver_2_1            receiver_3_1
  ;
hydra.VmPrms-names =
  locator_1_1             locator_2_1             locator_3_1
  client_1_1              client_2_1              client_3_1
  sender_1_1              sender_2_1              sender_3_1
  receiver_1_1            receiver_2_1            receiver_3_1
  ;
hydra.VmPrms-hostNames =
  locator_1_1             locator_2_1             locator_3_1
  client_1_1              client_2_1              client_3_1
  sender_1_1              sender_2_1              sender_3_1
  receiver_1_1            receiver_2_1            receiver_3_1
  ;
hydra.VmPrms-extraVMArgs =
  fcn "hydra.TestConfigFcns.duplicate(\"-Xms256m -Xmx256m\", 3, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"-Xms${clientHeapMB}m -Xmx${clientHeapMB}m\", 3, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"-Xms${serverHeapMB}m -Xmx${serverHeapMB}m\", 3 * 2, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"-Xms${serverHeapMB}m -Xmx${serverHeapMB}m\", 3, true)" ncf
  ;
hydra.VmPrms-extraVMArgs +=
  fcn "hydra.TestConfigFcns.duplicate(\"-XX:+DisableExplicitGC\", 3, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"-XX:+DisableExplicitGC\", 3, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicateString(\"-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:+DisableExplicitGC -XX:CMSInitiatingOccupancyFraction=50\", 3 * 2, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicateString(\"-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:+DisableExplicitGC -XX:CMSInitiatingOccupancyFraction=50\", 3, true)" ncf
  ;

//------------------------------------------------------------------------------
// GFXD
//------------------------------------------------------------------------------

hydra.ClientPrms-names =
  locator_1_1             locator_2_1             locator_3_1
  client_1_1              client_2_1              client_3_1
  sender_1_1              sender_2_1              sender_3_1
  receiver_1_1            receiver_2_1            receiver_3_1
  ;
hydra.ClientPrms-vmNames =
  locator_1_1             locator_2_1             locator_3_1
  client_1_1              client_2_1              client_3_1
  sender_1_1              sender_2_1              sender_3_1
  receiver_1_1            receiver_2_1            receiver_3_1
  ;
hydra.ClientPrms-vmQuantities =
  1                       1                       1
  1                       1                       1
  2                       2                       2
  1                       1                       1
  ;
hydra.ClientPrms-vmThreads =
  1                       1                       1
  ${clientThreadsPerVM}   ${clientThreadsPerVM}   ${clientThreadsPerVM}
  ${senderThreadsPerVM}   ${senderThreadsPerVM}   ${senderThreadsPerVM}
  ${receiverThreadsPerVM} ${receiverThreadsPerVM} ${receiverThreadsPerVM}
  ;

// GIVE LONER SAME LOGICAL NAME AS CLIENT AS SEEN IN STATSPECS
hydra.gemfirexd.LonerPrms-names = client;
hydra.gemfirexd.LonerPrms-clientNames =
  client_1_1 client_2_1 client_3_1
  ;

hydra.gemfirexd.ThinClientPrms-names = client;
hydra.gemfirexd.ThinClientPrms-clientNames =
  client_1_1  client_2_1  client_3_1
  ;

hydra.gemfirexd.FabricServerPrms-names =
  locator_1_1             locator_2_1             locator_3_1
  sender_1_1              sender_2_1              sender_3_1
  receiver_1_1            receiver_2_1            receiver_3_1
  ;
hydra.gemfirexd.FabricServerPrms-clientNames =
  locator_1_1,            locator_2_1,            locator_3_1,
  sender_1_1,             sender_2_1,             sender_3_1,
  receiver_1_1,           receiver_2_1,           receiver_3_1
  ;
hydra.gemfirexd.FabricServerPrms-conserveSockets = false;
hydra.gemfirexd.FabricServerPrms-distributedSystem  =
  ds_1                     ds_2                    ds_3
  ds_1                     ds_2                    ds_3
  ds_1                     ds_2                    ds_3
  ;
hydra.gemfirexd.FabricServerPrms-hostData  =
  false                    false                   false
  true                     true                    true
  true                     true                    true
  ;
hydra.gemfirexd.FabricServerPrms-persistQueues = true;
hydra.gemfirexd.FabricServerPrms-remoteDistributedSystems =
  ds_2 ds_3,               ds_1 ds_3,              ds_1 ds_2,
  none,                    none,                   none,
  none,                    none,                   none
  ;
hydra.gemfirexd.FabricServerPrms-serverGroups = 
  none,                   none,                   none,
  sender_1,               sender_2,               sender_3,
  receiver_1,             receiver_2,             receiver_3
  ;

hydra.gemfirexd.GatewayReceiverPrms-names =
  receiver_1    receiver_2    receiver_3
  ;
hydra.gemfirexd.GatewayReceiverPrms-distributedSystem =
  ds_1          ds_2          ds_3
  ;
hydra.gemfirexd.GatewayReceiverPrms-serverGroups =
  receiver_1,   receiver_2,   receiver_3
  ;

hydra.gemfirexd.GatewaySenderPrms-names =
  sender_1     sender_2     sender_3
  ;
hydra.gemfirexd.GatewaySenderPrms-distributedSystem =
  ds_1         ds_2         ds_3
  ;
hydra.gemfirexd.GatewaySenderPrms-remoteDistributedSystems =
  ds_2 ds_3,   ds_1 ds_3,   ds_1 ds_2
  ;
hydra.gemfirexd.GatewaySenderPrms-serverGroups =
  sender_1,    sender_2,    sender_3
  ;
hydra.gemfirexd.GatewaySenderPrms-diskStoreName = WAN;
hydra.gemfirexd.GatewaySenderPrms-manualStart = true;
hydra.gemfirexd.GatewaySenderPrms-persistenceEnabled = false;

hydra.gemfirexd.NetworkServerPrms-names = network;

hydra.gemfirexd.GfxdConfigPrms-networkServerConfig = network;

THREADGROUP locator
  totalThreads = 3
  clientNames  = locator_1_1, locator_2_1, locator_3_1
  ;
THREADGROUP client
  totalThreads = fcn 3 * ${clientThreadsPerVM} ncf
  clientNames  = client_1_1, client_2_1, client_3_1
  ;
THREADGROUP sender
  totalThreads = fcn 3 * 2 * ${senderThreadsPerVM} ncf
  clientNames  = sender_1_1, sender_2_1, sender_3_1
  ;
THREADGROUP receiver
  totalThreads = fcn 3 * ${receiverThreadsPerVM} ncf
  clientNames  = receiver_1_1, receiver_2_1, receiver_3_1
  ;

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
          threadGroups = sender, receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = setEvictionHeapPercentageTask
          threadGroups = sender, receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = startNetworkServerTask
          threadGroups = sender, receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = connectPeerClientTask
          threadGroups = sender, receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createDiskStoresTask
          threadGroups = sender
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createGatewaySendersTask
          threadGroups = sender
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = connectThinWanClientTask
          threadGroups = client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = openStatisticsTask
          threadGroups = locator, sender, receiver, client
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = dropTablesTask
          threadGroups = sender
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createWanTablesTask
          threadGroups = sender
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createIndexesOnTablesTask
          threadGroups = sender
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = createGatewayReceiversTask
          threadGroups = receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = createBucketsTask
          threadGroups = sender
          ;
//INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
//          taskMethod = rebalanceBucketsTask
//          threadGroups = sender, receiver
//          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = printBucketListTask
          threadGroups = sender, receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = setPrimaryBucketListTask
          threadGroups = sender, receiver
          ;
//INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
//          taskMethod = dumpBucketsTask
//          threadGroups = sender, receiver
//          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = checkBucketsTask
          threadGroups = sender, receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = startGatewaySendersTask
          threadGroups = sender
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadWarehouseDataTask
          threadGroups = sender, receiver
          cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-throttleMs = ${warehouseThrottleMs}
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadItemDataTask
          threadGroups = sender, receiver
          cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-throttleMs = ${itemThrottleMs}
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadStockDataTask
          threadGroups = sender, receiver
          cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-throttleMs = ${stockThrottleMs}
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadDistrictDataTask
          threadGroups = sender, receiver
          cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-throttleMs = ${districtThrottleMs}
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadCustomerDataTask
          threadGroups = sender, receiver
          cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-throttleMs = ${customerThrottleMs}
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = loadOrderDataTask
          threadGroups = sender, receiver
          cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-throttleMs = ${orderThrottleMs}
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = checkDataLoadTask
          threadGroups = sender, receiver
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = waitForQueuesToDrainTask
          threadGroups = sender
          ;
INITTASK  taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = configureDebuggingTask
          threadGroups = sender, receiver
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = waitForQueuesToDrainTask
          threadGroups = sender
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = reportTrimIntervalsTask
          threadGroups = client, sender, receiver
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = dumpQueryPlansTask
          threadGroups = sender
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = checkDataLoadTask
          threadGroups = sender, receiver
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = dropTablesTask
          threadGroups = sender
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.QueryPerfClient
          taskMethod = closeConnectionTask
          threadGroups = sender, receiver, client
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = closeStatisticsTask
          threadGroups = client
          ;
CLOSETASK taskClass = cacheperf.comparisons.gemfirexd.tpcc.TPCCClient
          taskMethod = closeStatisticsTask
          threadGroups = sender, receiver
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
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-diskStoreFileNum  = ${diskStoreFileNum};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-tableFileNum  = ${tableFileNum};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-indexFileNum  = ${indexFileNum};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-createIndexes = ${createIndexes};
cacheperf.comparisons.gemfirexd.tpcc.TPCCPrms-timeStmts = true;
