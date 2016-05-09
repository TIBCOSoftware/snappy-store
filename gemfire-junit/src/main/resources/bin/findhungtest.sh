#!/usr/bin/env bash

# This script will help to find which exact set of tests are hung while
# running store precheckin tests ( dunit and junit )
# cp this script to the dunit run directory where progress.txt is created
# and just do ./findhungtest.sh

PROGRESS_FILE="progress.txt"

start_regex='.+Starting test .+'
while  read -r line || [[ -n "$line" ]]; do
  if [[ "$line" =~ $start_regex ]] ; then
    #echo "LINE = ${line} MATCHED"
    tests=`echo $line | cut -d' ' -f6-`
    #echo "test = ${tests}"
    completed_line="Completed test ${tests}"
    #echo "completed line = ${completed_line}"
    ending_exists=$(grep "${completed_line}" $PROGRESS_FILE 2>/dev/null)
    #echo "ending exists = ${ending_exists}"
    if [ -z "$ending_exists" ]; then
      echo "${tests} did not complete"
    fi
  fi
done < $PROGRESS_FILE
