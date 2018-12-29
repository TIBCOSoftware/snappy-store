#!/bin/sh

#runtest() {
#  export GEMFIRE_BUILD=$1
#  export GEMFIRE=$GEMFIRE_BUILD/product
#  export LD_LIBRARY_PATH=$GEMFIRE/../hidden/lib:$GEMFIRE/lib
#  export JTESTS=$GEMFIRE/../tests/classes
#  export CLASSPATH=$GEMFIRE/../product-gfxd/lib/gemfirexd.jar:$GEMFIRE/lib/gemfire.jar:$JTESTS
#  export JAVA_HOME=/export/gcm/where/jdk/1.6.0_24/x86_64.linux
#  echo "Running $2 with $3..."
#  echo ""
#  $JAVA_HOME/bin/java -server \
#    -classpath $CLASSPATH -DGEMFIRE=$GEMFIRE -DJTESTS=$JTESTS \
#    -DprovideRegressionSummary=false -DnukeHungTest=true -DmoveRemoteDirs=true \
#    -DnumTimesToRun=1 -DtestFileName=$2 -DlocalConf=$3 \
#    batterytest.BatteryTest
#  echo "Saving test results to $4..."
#  echo ""
#  mkdir $4
#  /bin/mv *-*-* $4
#  /bin/mv batterytest.log batterytest.bt oneliner.txt $4
#  /bin/cp my.cnf $4
#  /bin/cp $2 $3 $4
#}

runtest() {
  ## Set known paths 
  export SNAPPYDATADIR=$1
  export GEMFIRE=$SNAPPYDATADIR/build-artifacts/scala-2.11/store
  export LD_LIBRARY_PATH=$GEMFIRE/lib
  export JTESTS=$SNAPPYDATADIR/store/tests/sql/build-artifacts/linux/classes/java/main
  export EXTRA_JTESTS=$SNAPPYDATADIR/store/tests/core/build-artifacts/linux/classes/java/main
  
  ## find out the release version
  list=`ls $JTESTS/../../../libs/snappydata-store-hydra-tests-*`
  filename=`tr "/" "\n" <<< $list | tail -1`
  export releaseVersion=`echo "${filename%*.*}"| cut -d'-' -f5-6`

  ## find out snappy test jar version
  snappyTestsJarName=`ls $SNAPPYDATADIR/dtests/build-artifacts/scala-2.11/libs/snappydata-store-scala-tests*`
  snappyTestsJarFilename=`tr "/" "\n" <<< $snappyTestsJarName | tail -1`
  export snappyTestsJarVersion=`echo "${snappyTestsJarFilename%*.*}"| cut -d'-' -f5-6`
  
  ##set classpath
  CLASSPATH=$JTESTS:$EXTRA_JTESTS:$JTESTS/../../../libs/snappydata-store-hydra-tests-${releaseVersion}-all.jar:$SNAPPYDATADIR/dtests/build-artifacts/scala-2.11/libs/snappydata-store-scala-tests-${snappyTestsJarVersion}-tests.jar
  LIB=$SNAPPYDATADIR/build-artifacts/scala-2.11/snappy/jars
  for i in $LIB/*.jar; do CLASSPATH=$CLASSPATH:$i; done
  export CLASSPATH=$CLASSPATH
   
  export JAVA_HOME=/vol1/SNAPPY/jdk1.8.0_131
  echo "Running $2 with $3..."
  echo ""
  $JAVA_HOME/bin/java -server \
    -classpath $CLASSPATH -DGEMFIRE=$GEMFIRE -DJTESTS=$JTESTS \
    -DprovideRegressionSummary=false -DnukeHungTest=true -DmoveRemoteDirs=true \
    -DnumTimesToRun=1 -DtestFileName=$2 -DlocalConf=$3 \
    batterytest.BatteryTest
  echo "Saving test results to $4..."
  echo ""
  mkdir $4
  /bin/mv *-*-* $4
  /bin/mv batterytest.log batterytest.bt oneliner.txt $4
  /bin/cp my.cnf $4
  /bin/cp $2 $3 $4
}
#-------------------------------------------------------------------------------

if [ -z "$1" ]
then
  echo "No gemfire build was specified."
  exit 0
fi

runtest $1 tpccPeer.gfxd.bt local.conf peerClient.gfxd
#runtest $1 tpccThin.gfxd.bt local.conf thinClient.gfxd

