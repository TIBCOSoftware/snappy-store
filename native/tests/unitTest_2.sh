#!/bin/sh

_cwd="$2"
_snappyNativeDir="$(dirname "$_cwd")"
_testDir=${_snappyNativeDir}/tests/
#echo "${_snappyNativeDir}"
distDir=${_snappyNativeDir}/dist
#echo "${distDir}"
SNAPPY_HOME_DIR="$1"
if [ -z ${THRIFT_VERSION} ]; then
  THRIFT_VERSION=1.0.0-2
fi
if [ -z ${BOOST_VERSION} ]; then
  BOOST_VERSION=1.65.1
fi

thrftLibPath=${distDir}/thrift-${THRIFT_VERSION}/lin64/lib
bostLibPath=${distDir}/boost-${BOOST_VERSION}/lin64/lib
snappClientLibPath=${_snappyNativeDir}/build-artifacts/lin/snappyclient/lin64/lib/debug

LD_LIBRARY_PATH=$LD_LIBRARY_PATH:${thrftLibPath}:${bostLibPath}:${snappClientLibPath}:${_snappyNativeDir}/Debug#!/bin/sh

#echo "$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH

#echo "${_cwd}"
headerLoc=${_snappyNativeDir}/src/snappyclient/headers
boostHeadeLoc=${_snappyNativeDir}/dist/boost-${BOOST_VERSION}/include

g++ -std=c++11 -I"${headerLoc}" -I"${boostHeadeLoc}" -O0 -g3 -Wall -c -fmessage-length=0 -MMD -MP -MF"${_testDir}/unitTest_2.d" -MT"${_testDir}unitTest_2.o" -o "${_testDir}unitTest_2.o" "${_testDir}unitTest_2.cpp"

g++ -L${bostLibPath} -L${thrftLibPath} -L${snappClientLibPath} -L${_snappyNativeDir}/Debug -rdynamic -o "${_testDir}unitTest_2"  ${_testDir}unitTest_2.o -lcrypto -lodbc -lpthread -lssl -lsnappydata-native -lboost_chrono -lboost_date_time -lboost_filesystem -lboost_log -lboost_log_setup -lboost_system -lboost_thread -lthrift -lpthread -lrt -lgmp

chmod 777 ${_testDir}unitTest_2 ${_testDir}unitTest_2.d
cd ${_testDir}
./unitTest_2 $SNAPPY_HOME_DIR

rm unitTest_2 unitTest_2.d unitTest_2.o
