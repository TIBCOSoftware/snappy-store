#!/bin/bash
#set -xv
umask 002

# Runs a battery test using the latest sanctioned build

function usage() {
  echo ""
  echo "** $*"
  echo ""
  echo "usage: runBT.sh outputDir [-Doption]+ [batteryTest]+"
  echo "  Runs batterytest using the latest sanctioned build."
  echo ""
  echo "--propfile Search the build<platform>.properties file for osbuild.dir"
  echo "--osbuild [arg]  Set osbuild.dir to [arg] "
  echo ""
  echo "  outputDir    Location to put the test results"
  echo "  addressee    Recipient of emailed test results"
  echo "  option       option passed through to batterytest, beginning with -D"
  echo "  batteryTest  Test(s) to run"
  echo ""

}

#intitialize OVERRIDE_PROPFILE
OVERRIDE_PROPFILE=0

#loop to parse -- style arguments
while ( echo "$1" | grep -- '--'  > /dev/null 2>&1 ); do
  if [ "x$1" = "x--propfile" ]; then
    if [ "x$OVERRIDE_OSBUILD" != "x" ]; then
      echo "ERROR: cannot use --propfile and --osbuild options together"
      exit 1
    fi
    OVERRIDE_PROPFILE=1
  elif [ "x$1" = "x--osbuild" ]; then
    if [ $OVERRIDE_PROPFILE -ne 0 ]; then
      echo "ERROR: cannot use --propfile and --osbuild options together"
      exit 1
    fi
    OVERRIDE_OSBUILD=$2
    shift 
  else
    echo "ERROR: unknown -- option $1"
    exit 1
  fi
  shift
done 

#parse -DtestJVM style arguments
if ( echo "$@" | grep -- '-DtestJVM'  > /dev/null 2>&1 ); then
  alt_jvm=`echo "$@" | sed 's/.*-DtestJVM=\([^ ]*\) .*/\1/' | xargs dirname | xargs dirname`
  if [ -d $alt_jvm/bin ]; then
    export ALT_JAVA_HOME=$alt_jvm
  fi
fi

if [ $# -lt 2 ]; then
  usage "Missing arguments"
  exit 1

else
  outputDir=$1
  shift
fi
OSTYPE=`uname -a | sed -e 's/ .*//' -e 's/CYG.*/Windows_NT/' -e 's/SunOS/Solaris/'`
OS=linux
if [ ${OSTYPE} = "Darwin" ]; then
  OS="osx"
fi
 
TESTBIN=`dirname $0`
# provieded osbuild dir
OSBUILD=${OVERRIDE_OSBUILD}
SOURCES=$TESTBIN/../../../../../..
  
export NO_BUILD_LOG=true
export JPROBE_HOME=/do/not/use/JPROBE
options=''
TESTSPATH=${SOURCES}/tests/sql/build-artifacts/${OS}/classes/java/main
export CLASSPATH=${TESTSPATH}:$CLASSPATH
while [ $# -gt 0 ]; do

  echo $1 | egrep "^\-D" > /dev/null
  if [ $? = 0 ]; then
    options=${options}' '$1
    shift

  else

    testname=`basename $1 .bt`

    # Clean up old results. This attempts to get the result directory
    # and not just the testname. 
    dirlist=`ls -1d ${outputDir}/${testname}/*`
    for dir in $dirlist; do
      find $dir -type d -mtime +5 -exec rm -rf {} \; >/dev/null
    done
   
    if [ "x$ALT_JAVA_HOME" != "x" ]; then
      _JAVA=$ALT_JAVA_HOME/bin/java 
    else  
      _JAVA=/usr/bin/java
    fi
    JAVAVERSION="`${_JAVA} -version 2>&1 | awk '/version/ {print $3}' | egrep -o '[^\"]*'`"
    latestdirectory=`date +%Y-%m-%d-%H-%M-%S`
    regressionExtraPath=/${testname}/$latestdirectory
    testOutputDir=${outputDir}${regressionExtraPath}
    mkdir -p ${testOutputDir}
  
    cd ${testOutputDir}
  
    # Relative path
    BT_FILE=${TESTSPATH}/$1
  
    if [ ! -f ${BT_FILE} ]; then
      # Use the absolute path
      BT_FILE=$1
    fi
  
    # If the build fails (returns non-zero), the script stops
    echo "Using java $_JAVA with version $JAVAVERSION"
    LINK=`which ln 2>/dev/null`
    rm -f ${testOutputDir}/../latest
    $LINK -s ${latestdirectory} ${testOutputDir}/../latest

    ($_JAVA -classpath $CLASSPATH -Dosbuild.dir=${OSBUILD} \
      -DGEMFIRE=${OSBUILD} -DJTESTS=${TESTSPATH} \
      -DresultDir=${testOutputDir} \
      -DprovideBugReportTemplate=true -DprovideRegressionSummary=true -DnukeHungTest=true \
      ${options} -DtestFileName=${BT_FILE} \
      batterytest.BatteryTest 2>&1 || true)

    cd -

    find $testOutputDir -name core -exec rm -f {} \; >/dev/null

    # Create the resultReport.txt file
        echo "Running BT file:" > ${testOutputDir}/resultReport.txt
        echo "  $1" >> ${testOutputDir}/resultReport.txt
        if [ "$options" != "" ]; then
          echo "Using options:" >> ${testOutputDir}/resultReport.txt
          echo "  ${options}" >> ${testOutputDir}/resultReport.txt
        fi
        echo "Output directory: " >> ${testOutputDir}/resultReport.txt
        echo "  ${testOutputDir}" >> ${testOutputDir}/resultReport.txt


        # Check for oneliner.txt file existing. The intention is to catch
        # critical fatal errors and send that in the email instead of
        # non-existent results

        if [ -f ${testOutputDir}/oneliner.txt ]; then

          # Collect the detailed results into resultReport.txt file
          #${SOURCES}/release/build/chkbt.sh ${testOutputDir} >> ${testOutputDir}/resultReport.txt
          # don't send full report, make a result only report to the mail file
          #cat ${testOutputDir}/resultReport.txt >> ${testOutputDir}/resultReport.txt
          SUBJECT="${OSTYPE} snappy-store Battery Test results for ${testname} from `hostname` "
          echo $SUBJECT >> ${testOutputDir}/resultReport.txt
          if [ -f ${testOutputDir}/batterytest.log ]; then
             #echo "" >> ${testOutputDir}/resultReport.txt
             #echo "Full result report available at:" >> ${testOutputDir}/resultReport.txt
             #echo "${testOutputDir}/resultReport.txt" >> ${testOutputDir}/resultReport.txt
             echo "" >> ${testOutputDir}/resultReport.txt
             echo "==== STATUS REPORT ====" >> ${testOutputDir}/resultReport.txt
             grep "==== STATUS REPORT ====" ${testOutputDir}/batterytest.log | cut -f 9 -d "=" >> ${testOutputDir}/resultReport.txt

             # See if there are any failures
             grep " F " ${testOutputDir}/oneliner.txt >/dev/null 2>&1
             if [ $? -eq 0 ]; then
               echo "" >> ${testOutputDir}/resultReport.txt
               echo "Tests that failed:" >> ${testOutputDir}/resultReport.txt
               grep " F " ${testOutputDir}/oneliner.txt | cut -f 1 -d " " >> ${testOutputDir}/resultReport.txt
             fi

             # See if there are any hangs
             grep " H " ${testOutputDir}/oneliner.txt >/dev/null 2>&1
             if [ $? -eq 0 ]; then
               echo "" >> ${testOutputDir}/resultReport.txt
               echo "Tests that hung:" >> ${testOutputDir}/resultReport.txt
               grep " H "  ${testOutputDir}/oneliner.txt | cut -f 1 -d " " >> ${testOutputDir}/resultReport.txt
             fi

          fi

        else

          SUBJECT="FATAL ERRORS on `hostname` running Battery Test ${testname}"
          echo "" >> ${testOutputDir}/resultReport.txt
          echo "Unable to locate oneliner.txt indicating hydra never got started." >> ${testOutputDir}/resultReport.txt
          echo "" >> ${testOutputDir}/resultReport.txt
          echo "Contents of batterytest.log:" >> ${testOutputDir}/resultReport.txt
          cat ${testOutputDir}/batterytest.log >> ${testOutputDir}/resultReport.txt
          echo "" >> ${testOutputDir}/resultReport.txt

        fi
    shift

  fi

done
echo "--- Test completed ---"
