hydra.VmPrms-extraClassPaths =
  fcn "hydra.TestConfigFcns.duplicate(\"none\", ${locatorHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"none\", ${clientHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"/export/gcm/where/java/mysql/mysql-connector-java-5.1.8-bin.jar\", ${dbsyncHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"none\", ${prdataHosts}, true)" ncf,
  fcn "hydra.TestConfigFcns.duplicate(\"/export/gcm/where/java/mysql/mysql-connector-java-5.1.8-bin.jar\", ${dbHosts}, true)" ncf
  ;
