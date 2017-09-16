//------------------------------------------------------------------------------
// General
//------------------------------------------------------------------------------

hydra.VmPrms-extraClassPaths =
  fcn "hydra.TestConfigFcns.duplicate
   (\"none\", ${locatorHosts}, true)"
  ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
   (\"/gcm/where/jdbcdriver/postgres/postgresql-9.2-1002.jdbc4.jar\", ${clientHosts}, true)"
  ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
   (\"/gcm/where/jdbcdriver/postgres/postgresql-9.2-1002.jdbc4.jar\", ${serverHosts}, true)"
  ncf
  ;

hydra.VmPrms-extraVMArgs =
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms256m -Xmx256m\", ${locatorHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms${clientHeapMB}m -Xmx${clientHeapMB}m -XX:+DisableExplicitGC\",
                                    ${clientHosts}, true)" ncf
  ,
  fcn "hydra.TestConfigFcns.duplicate
       (\"-Xms${serverHeapMB}m -Xmx${serverHeapMB}m\",
                                   ${serverHosts}, true)" ncf
  ;

hydra.VmPrms-extraVMArgs += "-Djava.security.egd=file:///dev/urandom";

//------------------------------------------------------------------------------
// ORACLE
//------------------------------------------------------------------------------

// GIVE LONER SAME LOGICAL NAME AS CLIENT AS SEEN IN STATSPECS
hydra.gemfirexd.LonerPrms-names = client;
hydra.gemfirexd.LonerPrms-clientNames =
  fcn "hydra.TestConfigFcns.generateNames(\"locator\", ${locatorHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"client\", ${clientHosts})" ncf
  fcn "hydra.TestConfigFcns.generateNames(\"server\", ${serverHosts})" ncf
  ;
