#!/bin/bash

runtest() {
   export SNAPPYDATADIR=$1
   export GEMFIRE=$SNAPPYDATADIR/build-artifacts/scala-2.10/store
   export LD_LIBRARY_PATH=$GEMFIRE/lib
   export EXTRA_JTESTS=$SNAPPYDATADIR/store/tests/sql/build-artifacts/linux/classes/java/main
   export JTESTS=$SNAPPYDATADIR/store/tests/core/build-artifacts/linux/classes/java/main
   export CLASSPATH=$JTESTS:$EXTRA_JTESTS:$GEMFIRE/lib/snappydata-store-1.5.0-SNAPSHOT.jar:$GEMFIRE/lib/snappydata-store-client-1.5.0-SNAPSHOT.jar:JTESTS/../../../libs/snappydata-store-hydra-tests-1.5.0-SNAPSHOT-all.jar:$GEMFIRE/lib/snappydata-store-tools-1.5.0-SNAPSHOT.jar:$SNAPPYDATADIR/snappy-dtests/build-artifacts/scala-2.10/libs/snappydata-store-scala-tests-0.1.0-SNAPSHOT.jar

    echo "Running $JTESTS/smoketest/scale/scale.bt with $JTESTS/smoketest/scale/local.conf..."
    echo ""
   $JAVA_HOME/bin/java -server \
    -classpath $CLASSPATH -DGEMFIRE=$GEMFIRE -DJTESTS=$JTESTS \
    -DprovideRegressionSummary=false -DnukeHungTest=true -DmoveRemoteDirs=true \
    -DtestFileName=$JTESTS/smoketest/scale/scale.bt \
    -DlocalConf=$JTESTS/smoketest/scale/local.conf \
    batterytest.BatteryTest
}

#-------------------------------------------------------------------------------

trap 'exit 1' 2 #traps Ctrl-C (signal 2)

if [ -z "$1" ]
then
  echo "No gemfire build was specified."
  exit 0
fi

runtest $1
