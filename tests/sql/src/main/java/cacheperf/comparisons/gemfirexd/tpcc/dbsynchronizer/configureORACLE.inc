hydra.VmPrms-extraClassPaths =
  fcn "hydra.TestConfigFcns.duplicate(\"none\", ${locatorHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"none\", ${clientHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"/export/${databaseServerHome}/users/oracle/app/oracle/product/11.2.0/dbhome_1/jdbc/lib/ojdbc6.jar\", ${dbsyncHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"none\", ${prdataHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"/export/${databaseServerHome}/users/oracle/app/oracle/product/11.2.0/dbhome_1/jdbc/lib/ojdbc6.jar\", ${dbHosts}, true)" ncf
  ;
