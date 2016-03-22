#!/bin/bash
#set -vx
trap 'exit 1' 2 #traps Ctrl-C (signal 2)

#-------------------------------------------------------------------------------

runtest() {
 usage="Usage: run.sh < result-dir> <snappy-data-directory-path> "
 resultDir=
 if [ $# -lt 2 ]; then
   echo "Usage: run.sh < result-dir> <snappy-data-directory-path>" 1>&2
   echo " snappy-data-directory-path    checkout path of snappy-data " 1>&2
   echo " (e.g. sh run.sh /home/rajesh/snappystoreResult /home/rajesh/project/snappydata)" 1>&2
   exit 1
 else
     resultDir=$1
     mkdir -p $resultDir
     shift
 fi
 SNAPPYDATADIR=$1
 shift

 # If JAVA_HOME is not already set in system then set JAVA_HOME using TEST_JVM
 TEST_JVM=
 export JTESTS=$SNAPPYDATADIR/snappy-store/tests/sql/build-artifacts/linux/classes/main
 export PATH=$JAVA_HOME:$PATH:$SNAPPYDATADIR/snappy-store/tests/sql/build-artifacts/linux/classes/main:$JTESTS
 export GEMFIRE=$SNAPPYDATADIR/build-artifacts/scala-2.10/store
 export LD_LIBRARY_PATH=$GEMFIRE/lib
 export OUTPUT_DIR=$resultDir
 if [ "x$JAVA_HOME" = "x" ]; then
   TEST_JVM=/usr
 else
   TEST_JVM=$JAVA_HOME
 fi

 export EXTRA_JTESTS=$SNAPPYDATADIR/snappy-store/tests/core/build-artifacts/linux/classes/main
 #export JTESTS_RESOURCES=$SNAPPYDATADIR/snappy-store/tests/core/src/main/java
 export CLASSPATH=$JTESTS:$EXTRA_JTESTS:$GEMFIRE/lib/gemfirexd-1.5.0-BETA.jar:$GEMFIRE/lib/gemfirexd-client-1.5.0-BETA.jar:$JTESTS/../../libs/gemfirexd-hydra-tests-1.5.0-BETA-all.jar:$GEMFIRE/lib/gemfirexd-tools-1.5.0-BETA.jar:$SNAPPYDATADIR/snappy-dtests/build-artifacts/scala-2.10/libs/gemfirexd-scala-tests-0.1.0-SNAPSHOT.jar
 echo "Running useCase6.bt using useCase6.local.conf..."

  $JAVA_HOME/bin/java -server \
    -classpath $CLASSPATH -DGEMFIRE=$GEMFIRE -DJTESTS=$JTESTS -DresultDir=${resultDir}\
    -DprovideRegressionSummary=false -DnukeHungTest=true -DmoveRemoteDirs=true \
    -DnumTimesToRun=1 -DtestFileName=useCase6.bt -DlocalConf=useCase6.local.conf \
    batterytest.BatteryTest
}

#-------------------------------------------------------------------------------

runtest $1 $2
