hydra.Prms-testTopology = "Generic topology logically divided into a locator subgroup and four named subgroups.  Subgroup locator has ${locatorHosts} hosts, ${locatorVMsPerHost} VMs per host, and ${locatorThreadsPerVM} threads per VM.  Subgroup client has ${clientHosts} hosts, ${clientVMsPerHost} VMs per host, and ${clientThreadsPerVM} threads per VM.  Subgroup dbsync has ${dbsyncHosts} hosts, ${dbsyncVMsPerHost} VMs per host, and ${dbsyncThreadsPerVM} threads per VM.  Subgroup prdata has ${prdataHosts} hosts, ${prdataVMsPerHost} VMs per host, and ${prdataThreadsPerVM} threads per VM.  Subgroup db has ${dbHosts} hosts, ${dbVMsPerHost} VMs per host, and ${dbThreadsPerVM} threads per VM.";

hydra.Prms-manageLocatorAgents = false; // turn off master-managed locators

hydra.HostPrms-names =
  fcn "hydra.TestConfigFcns.generateNames(\"locatorhost\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"clienthost\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbsynchost\", ${dbsyncHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"prdatahost\", ${prdataHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbhost\", ${dbHosts})" ncf
  ;
hydra.VmPrms-names =
  fcn "hydra.TestConfigFcns.generateNames(\"locatorvm\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"clientvm\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbsyncvm\", ${dbsyncHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"prdatavm\", ${prdataHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbvm\", ${dbHosts})" ncf
  ;
hydra.VmPrms-hostNames =
  fcn "hydra.TestConfigFcns.generateNames(\"locatorhost\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"clienthost\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbsynchost\", ${dbsyncHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"prdatahost\", ${prdataHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbhost\", ${dbHosts})" ncf
  ;
hydra.VmPrms-extraVMArgs =
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms256m -Xmx256m\", ${locatorHosts}, true)"
  ncf,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms${clientHeapMB}m -Xmx${clientHeapMB}m\", ${clientHosts}, true)"
  ncf,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms${dbsyncHeapMB}m -Xmx${dbsyncHeapMB}m -XX:+UseConcMarkSweepGC\", ${dbsyncHosts}, true)"
  ncf,
  fcn "hydra.TestConfigFcns.duplicateString
       (\"-Xms${prdataHeapMB}m -Xmx${prdataHeapMB}m -XX:+UseConcMarkSweepGC -XX:CMSInitiatingOccupancyFraction=50 -XX:+UseCMSInitiatingOccupancyOnly\", ${prdataHosts}, true)"
  ncf,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms256m -Xmx256m\", ${dbHosts}, true)"
  ncf
  ;
hydra.VmPrms-extraVMArgs += -XX:+DisableExplicitGC;
hydra.ClientPrms-names =
  fcn "hydra.TestConfigFcns.generateNames(\"locator\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"client\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbsync\", ${dbsyncHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"prdata\", ${prdataHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"db\", ${dbHosts})" ncf
  ;
hydra.ClientPrms-vmNames =
  fcn "hydra.TestConfigFcns.generateNames(\"locatorvm\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"clientvm\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbsyncvm\", ${dbsyncHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"prdatavm\", ${prdataHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"dbvm\", ${dbHosts})" ncf;
hydra.ClientPrms-vmQuantities =
  fcn "hydra.TestConfigFcns.duplicate
       (\"${locatorVMsPerHost}\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${clientVMsPerHost}\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${dbsyncVMsPerHost}\", ${dbsyncHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${prdataVMsPerHost}\", ${prdataHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${dbVMsPerHost}\", ${dbHosts})" ncf
   ;
hydra.ClientPrms-vmThreads =
  fcn "hydra.TestConfigFcns.duplicate
       (\"${locatorThreadsPerVM}\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${clientThreadsPerVM}\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${dbsyncThreadsPerVM}\", ${dbsyncHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${prdataThreadsPerVM}\", ${prdataHosts})" ncf
  fcn "hydra.TestConfigFcns.duplicate
       (\"${dbThreadsPerVM}\", ${dbHosts})" ncf
  ;

hydra.gemfirexd.GfxdConfigPrms-networkServerConfig = network;
hydra.gemfirexd.NetworkServerPrms-names = network;

hydra.gemfirexd.FabricServerPrms-names = locator dbsync prdata;
hydra.gemfirexd.FabricServerPrms-clientNames =
  fcn "hydra.TestConfigFcns.generateNames(\"locator\", ${locatorHosts})" ncf,
  fcn "hydra.TestConfigFcns.generateNames(\"dbsync\", ${dbsyncHosts})" ncf,
  fcn "hydra.TestConfigFcns.generateNames(\"prdata\", ${prdataHosts})" ncf
  ;
hydra.gemfirexd.FabricServerPrms-conserveSockets = false;
hydra.gemfirexd.FabricServerPrms-distributedSystem = ds;
hydra.gemfirexd.FabricServerPrms-hostData = false true true;
hydra.gemfirexd.FabricServerPrms-persistQueues = false true true;
hydra.gemfirexd.FabricServerPrms-serverGroups = locator, dbsync, prdata;

hydra.gemfirexd.ThinClientPrms-names = client;
hydra.gemfirexd.ThinClientPrms-clientNames =
  fcn "hydra.TestConfigFcns.generateNames(\"client\", ${clientHosts})" ncf
  ;

// GIVE LONER SAME LOGICAL NAME AS CLIENT AS SEEN IN STATSPECS
hydra.gemfirexd.LonerPrms-names = client db;
hydra.gemfirexd.LonerPrms-clientNames =
  fcn "hydra.TestConfigFcns.generateNames(\"client\", ${clientHosts})" ncf,
  fcn "hydra.TestConfigFcns.generateNames(\"db\", ${dbHosts})" ncf
  ;

THREADGROUP locator
    totalThreads = fcn ${locatorHosts} * ${locatorVMsPerHost}
                                       * ${locatorThreadsPerVM} ncf
    clientNames  = fcn "hydra.TestConfigFcns.generateNames
                        (\"locator\", ${locatorHosts}, true)" ncf;
THREADGROUP client
    totalThreads = fcn ${clientHosts} * ${clientVMsPerHost}
                                    * ${clientThreadsPerVM} ncf
    clientNames  = fcn "hydra.TestConfigFcns.generateNames
                        (\"client\", ${clientHosts}, true)" ncf;
THREADGROUP dbsync
    totalThreads = fcn ${dbsyncHosts} * ${dbsyncVMsPerHost}
                                    * ${dbsyncThreadsPerVM} ncf
    clientNames  = fcn "hydra.TestConfigFcns.generateNames
                        (\"dbsync\", ${dbsyncHosts}, true)" ncf;
THREADGROUP prdata
    totalThreads = fcn ${prdataHosts} * ${prdataVMsPerHost}
                                    * ${prdataThreadsPerVM} ncf
    clientNames  = fcn "hydra.TestConfigFcns.generateNames
                        (\"prdata\", ${prdataHosts}, true)" ncf;
THREADGROUP db
    totalThreads = fcn ${dbHosts} * ${dbVMsPerHost}
                                    * ${dbThreadsPerVM} ncf
    clientNames  = fcn "hydra.TestConfigFcns.generateNames
                        (\"db\", ${dbHosts}, true)" ncf;
