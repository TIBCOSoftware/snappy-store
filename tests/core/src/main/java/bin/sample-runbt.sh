#!/usr/bin/env bash
#set -x
usage="Usage: sample-runbt.sh <result-directory-path> <snappy-data-directory-path> <list-of-bts>"
resultDir=
if [ $# -lt 3 ]; then
  echo "Usage: sample-runbt.sh <result-directory-path> <snappy-data-directory-path> <list-of-bts>" 1>&2
  echo " result-directory-path          Location to put the test results " 1>&2
  echo " snappy-data-directory-path    checkout path of snappy-data " 1>&2
  echo " list-of-bts                    name of bts to run " 1>&2
  echo " (e.g. sh sample-runbt.sh /home/rajesh/gemxdRegression /home/rajesh/project/snappydata sql/sql.bt sql/sqlDisk/sqlDisk.bt )" 1>&2
  exit 1
else
  resultDir=$1
  mkdir -p $resultDir
  shift
fi
SNAPPYDATADIR=$1
shift
bts=
while [ $# -gt 0 ]; do
  bts=${bts}' '$1
  shift
done
# If JAVA_HOME is not already set in system then set JAVA_HOME using TEST_JVM
TEST_JVM=
export JTESTS=$SNAPPYDATADIR/snappy-store/tests/sql/build-artifacts/linux/classes/main
export PATH=$JAVA_HOME:$PATH:$JTESTS
export GEMFIRE=$SNAPPYDATADIR/build-artifacts/scala-2.10/store
export OUTPUT_DIR=$resultDir
if [ "x$JAVA_HOME" = "x" ]; then
  TEST_JVM=/usr
else
  TEST_JVM=$JAVA_HOME
fi

export EXTRA_JTESTS=$SNAPPYDATADIR/snappy-store/tests/core/build-artifacts/linux/classes/main
#export JTESTS_RESOURCES=$SNAPPYDATADIR/snappy-store/tests/core/src/main/java
export CLASSPATH=$JTESTS:$EXTRA_JTESTS:$GEMFIRE/lib/gemfirexd-1.5.0-BETA.jar:$GEMFIRE/lib/gemfirexd-client-1.5.0-BETA.jar:$JTESTS/../../libs/gemfirexd-hydra-tests-1.5.0-BETA-all.jar:$GEMFIRE/lib/gemfirexd-tools-1.5.0-BETA.jar
#/home/rajesh/extraJars/bsh.jar:

# This is the command to run the test, make sure the correct release version of jar used or change the jar path to use correctly. Also change the jar name in sql/snappy.local.conf if incorrect

echo $SNAPPYDATADIR/snappy-store/tests/core/src/main/java/bin/run-snappy-store-bt.sh --osbuild $GEMFIRE $OUTPUT_DIR -Dproduct=snappystore -DtestJVM=$TEST_JVM/bin/java -DEXTRA_JTESTS=$EXTRA_JTESTS  -Dbt.grepLogs=true -DremovePassedTest=true -DnumTimesToRun=1 -DlocalConf=$JTESTS/sql/snappy.local.conf ${bts}
$SNAPPYDATADIR/snappy-store/tests/core/src/main/java/bin/run-snappy-store-bt.sh --osbuild $GEMFIRE $OUTPUT_DIR -Dproduct=snappystore -DtestJVM=$TEST_JVM/bin/java -DEXTRA_JTESTS=$EXTRA_JTESTS  -Dbt.grepLogs=true -DremovePassedTest=true -DnumTimesToRun=1 -DlocalConf=$JTESTS/sql/snappy.local.conf ${bts}
