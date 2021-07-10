#!/usr/bin/env bash
#set -xv
usage(){
  echo "Usage: sample-runbt.sh <result-directory-path> <snappydata-base-directory-path> [-l <local-conf-file-path> -r <num-to-run-test> -m <mailAddresses>] <list-of-bts>" 1>&2
  echo " result-directory-path         Location to put the test results " 1>&2
  echo " snappydata-base-directory-path    checkout path of snappy-data " 1>&2
  echo " list-of-bts                   name of bts to run " 1>&2
  echo " Optionally any of:" 1>&2
  echo " -l <local-conf-file-path> --   path to local conf file " 1>&2
  echo " -r <n>  -- run test suite n number of times, the default is 1" 1>&2
  echo " -d <boolean>  -- whether to delete passed test run logs, the default value is true" 1>&2
  echo " -m mail_address -- email address to send results of run to" 1>&2
  echo " (e.g. sh sample-runbt.sh /home/snappy/gemxdRegression /home/snappy/project/snappydata sql/sql.bt sql/sqlDisk/sqlDisk.bt )" 1>&2
  echo " or"
  echo " (e.g. sh sample-runbt.sh /home/snappy/gemxdRegression /home/snappy/project/snappydata -l /home/snappy/gemxdRegression/local.conf -r 3  -d false sql/sql.bt sql/sqlDisk/sqlDisk.bt )" 1>&2
  exit 1
}
resultDir=
if [ $# -lt 3 ]; then
  usage
else
  resultDir=$1
  mkdir -p $resultDir
  shift
fi
SNAPPYDATADIR=$1
shift

OSTYPE=`uname -a | sed -e 's/ .*//' -e 's/CYG.*/Windows_NT/' -e 's/SunOS/Solaris/'`
OS=linux
if [ ${OSTYPE} = "Darwin" ]; then
  OS="osx"
fi

export JTESTS=$SNAPPYDATADIR/store/tests/sql/build-artifacts/${OS}/classes/java/main
export PATH=$JAVA_HOME:$PATH:$JTESTS
export GEMFIRE=$SNAPPYDATADIR/build-artifacts/scala-2.11/store
export OUTPUT_DIR=$resultDir
export myOS=`uname | tr "cyglinsu" "CYGLINSU" | cut -b1-3`

runIters=1
localconfpath=$JTESTS/sql/snappy.local.conf
bts=
TEST_JVM=
mailAddrs=
deleteTestLogs=true
processArgs() {
  local scnt=0
  local OPTIND=1

  while getopts ":l:r:d:" op
  do
    case $op in
      ( "l" ) ((scnt++)) ; localconfpath=$OPTARG ;;
      ( "r" ) ((scnt++))
        case $OPTARG in
          ( [0-9]* ) runIters=$OPTARG ;;
          ( * ) ;;
        esac ;;
      ( "d" ) ((scnt++)) ; deleteTestLogs="$OPTARG" ;;
      ( "m" ) ((scnt++)) ; mailAddrs="${mailAddrs} $OPTARG" ;;
      ( * ) echo "Unknown argument -$OPTARG provided: see usage " ; usage ;;
    esac
    ((scnt++))
  done

  while [ ${scnt:-0} -gt 0 ]
  do
    shift
    ((scnt--))
  done

  # rest arguments are bts
  bts="$bts $*"

  # If JAVA_HOME is not already set in system then set JAVA_HOME using TEST_JVM
  if [ "x$JAVA_HOME" = "x" ]; then
    TEST_JVM=/usr
  else
    TEST_JVM=$JAVA_HOME
  fi
}

processArgs $*

#list=`ls $GEMFIRE/lib/gemfirexd-client-*`
list=`ls $JTESTS/../../../libs/snappydata-store-hydra-tests-*`
filename=`tr "/" "\n" <<< $list | tail -1`

export releaseVersion=`echo "${filename%*.*}"| cut -d'-' -f5-6`

snappyTestsJarName=`ls $SNAPPYDATADIR/dtests/build-artifacts/scala-2.11/libs/snappydata-store-scala-tests*`
snappyTestsJarFilename=`tr "/" "\n" <<< $snappyTestsJarName | tail -1`
export snappyTestsJarVersion=`echo "${snappyTestsJarFilename%*.*}"| cut -d'-' -f5-6`

CLASSPATH=$JTESTS:$EXTRA_JTESTS:$JTESTS/../../../libs/snappydata-store-hydra-tests-${releaseVersion}-all.jar:$SNAPPYDATADIR/dtests/build-artifacts/scala-2.11/libs/snappydata-store-scala-tests-${snappyTestsJarVersion}-tests.jar
LIB=$SNAPPYDATADIR/build-artifacts/scala-2.11/snappy/jars
for i in $LIB/*.jar; do CLASSPATH=$CLASSPATH:$i; done
export CLASSPATH=$CLASSPATH
export EXTRA_JTESTS=$SNAPPYDATADIR/store/tests/core/build-artifacts/${OS}/classes/java/main
#export JTESTS_RESOURCES=$SNAPPYDATADIR/store/tests/core/src/main/java

# This is the command to run the test, make sure the correct release version of jar used or change the jar path to use correctly. Also change the jar name in sql/snappy.local.conf if incorrect

echo $SNAPPYDATADIR/store/tests/core/src/main/java/bin/run-snappy-store-bt.sh --osbuild $GEMFIRE $OUTPUT_DIR -Dproduct=snappystore -DtestJVM=$TEST_JVM/bin/java -DEXTRA_JTESTS=$EXTRA_JTESTS  -Dbt.grepLogs=true -DremovePassedTest=$deleteTestLogs -DnumTimesToRun=$runIters -DlocalConf=$localconfpath ${bts}
$SNAPPYDATADIR/store/tests/core/src/main/java/bin/run-snappy-store-bt.sh --osbuild $GEMFIRE $OUTPUT_DIR -Dproduct=snappystore -DtestJVM=$TEST_JVM/bin/java -DEXTRA_JTESTS=$EXTRA_JTESTS -Dbt.mergeLogFiles=true -Dbt.grepLogs=true -DremovePassedTest=$deleteTestLogs -DnumTimesToRun=$runIters -DlocalConf=$localconfpath ${bts} 
