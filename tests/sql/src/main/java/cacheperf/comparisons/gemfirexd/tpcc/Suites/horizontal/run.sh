#!/bin/bash

trap 'exit 1' 2 #traps Ctrl-C (signal 2)

#-------------------------------------------------------------------------------

if [ -z "$1" ]
then
  echo "No gemfire build was specified."
  exit 0
fi

cd /export/w1-gst-dev20a/users/lises/tpcc.scale/SCALE.S/
./run.sh $1
cd /export/w1-gst-dev20a/users/lises/tpcc.scale/SCALE.M/
./run.sh $1
cd /export/w1-gst-dev20a/users/lises/tpcc.scale/SCALE.L/
./run.sh $1
