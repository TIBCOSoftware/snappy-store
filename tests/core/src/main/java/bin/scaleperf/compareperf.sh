#!/bin/bash
#
# compare the performance of some number of runs
#

trap 'exit 1' 2 #traps Ctrl-C (signal 2)

USAGE="Usage: $0 dir1 dir2 dir3 ... dirN"

if [ "$#" == "0" ]; then
  echo "$USAGE"
  exit 1
fi

for mode in ratio raw
do
  echo "Preparing scaleperf $mode report..."
  java -cp $GEMFIRE/lib/gemfirexd-1.5.0-BETA.jar:$JTESTS -Xmx1024m -DomitFailedTests=true \
        -DcompReportFile=scaleperf.$mode.txt -Dmode=$mode -DcompareByKey=true \
        -DJTESTS=$JTESTS -Dgemfire.home=$GEMFIRE -DaddTestKey=true \
        perffmwk.PerfComparer $@
  echo "...done"
done

chmod uog+rw scaleperf.ratio.txt scaleperf.raw.txt
