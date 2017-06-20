#!/bin/sh
set -e

# Please note that there is new optimized code for thrift::Row and some other
# classes. Those hand optimized files overwrite the generated files. If thrift
# version is updated then that code may also need to be updated correspondingly.

if [ -z ${THRIFT_VERSION} ]; then
  THRIFT_VERSION=0.10.0
fi
if [ -z ${SOFTWARE_PREFIX} ]; then
  SOFTWARE_PREFIX=/export/shared/software
fi
PATH=$PATH:${SOFTWARE_PREFIX}/thrift-${THRIFT_VERSION}/lin64/bin
LD_LIBRARY_PATH=$LD_LIBRARY_PATH:${SOFTWARE_PREFIX}/thrift-${THRIFT_VERSION}/lin64/lib

export PATH LD_LIBRARY_PATH

CPP_HEADERS="SnappyDataService.h LocatorService.h snappydata_struct_SnappyException.h"

thrift --gen "cpp:struct_separate_files=true,moveable_types=true,no_concurrent_client=true,no_recursion_limit=true" ../../../gemfirexd/shared/src/main/java/io/snappydata/thrift/common/snappydata.thrift && rm -rf cpp/thrift/ && rm -rf headers/snappydata_* && mv gen-cpp cpp/thrift && rm cpp/thrift/*skele* && mv cpp/thrift/*.h headers/.

# copy all files from overrides
for tname in overrides/*cpp; do
  if [ -f "${tname}" ]; then
    fname="`basename "$tname"`"
    rm -f "cpp/thrift/${fname}"
    cp "${tname}" "cpp/thrift/${fname}"
  fi
done
for tname in overrides/*h; do
  if [ -f "${tname}" ]; then
    fname="`basename "$tname"`"
    rm -f "headers/${fname}"
    cp "${tname}" "headers/${fname}"
  fi
done

# move back non-public headers
for f in ${CPP_HEADERS}; do
  mv "headers/${f}" cpp/thrift/.
done
