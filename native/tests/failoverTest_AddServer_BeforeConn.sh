#!/bin/sh

SNAPPY_HOME_DIR="$1"
_testDir="$2"
_snappyNativeDir="$(dirname "$_testDir")"

distDir=${_snappyNativeDir}/dist
THRIFT_VERSION=1.0.0-2
BOOST_VERSION=1.65.1

thrftLibPath=${distDir}/thrift-${THRIFT_VERSION}/lin64/lib
boostLibPath=${distDir}/boost-${BOOST_VERSION}/lin64/lib
snappClientLibPath=${_snappyNativeDir}/build-artifacts/lin/libs/snappyclient/static/release

LD_LIBRARY_PATH=$LD_LIBRARY_PATH:${thrftLibPath}:${boostLibPath}:${snappClientLibPath}

export LD_LIBRARY_PATH

headerLoc=${_snappyNativeDir}/src/snappyclient/headers
boostHeadeLoc=${_snappyNativeDir}/dist/boost-${BOOST_VERSION}/include
thriftHeaderLoc=${distDir}/thrift-${THRIFT_VERSION}/include

g++ -std=c++11 -I${headerLoc} -I${boostHeadeLoc} -I${thriftHeaderLoc} -O0 -g3 -Wall -c ${_testDir}/failoverTest_AddServer_BeforeConn.cpp -o ${_testDir}/failoverTest_AddServer_BeforeConn.o

chmod 777 ${_testDir}/failoverTest_AddServer_BeforeConn.o

g++ -L${boostLibPath} -L${thrftLibPath} -L${snappClientLibPath} -o ${_testDir}/failoverTest_AddServer_BeforeConn  ${_testDir}/failoverTest_AddServer_BeforeConn.o  -lsnappyclient -lcrypto -lodbc -lpthread -lssl -lboost_chrono -lboost_date_time -lboost_filesystem -lboost_log -lboost_log_setup -lboost_system -lboost_thread -lthrift -lpthread -lrt -lgmp

chmod 777 ${_testDir}/failoverTest_AddServer_BeforeConn
cd ${_testDir}
./failoverTest_AddServer_BeforeConn $SNAPPY_HOME_DIR

rm failoverTest_AddServer_BeforeConn failoverTest_AddServer_BeforeConn.o
