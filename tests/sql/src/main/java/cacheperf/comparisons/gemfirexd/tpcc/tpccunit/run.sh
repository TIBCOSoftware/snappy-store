#!/bin/sh
/export/gcm/where/jdk/1.6.0_26/x86_64.linux/bin/java -server \
  -DGEMFIRE=$GEMFIRE -DJTESTS=$JTESTS -DtestFileName=tpccunit.bt \
  -DremovePassedTest=false -DterminateOnFailure=true -DnumTimesToRun=1 \
  -DprovideRegressionSummary=false -DnukeHungTest=true -DmoveRemoteDirs=true \
  batterytest.BatteryTest
