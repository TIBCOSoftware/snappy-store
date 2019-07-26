#!/bin/sh
usage="Usage: failoverTest_Retry.sh timeduration scriptPWD locator-ip-address locator-port"

timeduration="$1"
locatorIpAddress="$3"
locatorPort="$4"
scriptDir="$2"

timeduration=${timeduration#"["}
timeduration=${timeduration%"]"}
scriptDir=${scriptDir#"["}
scriptDir=${scriptDir%"]"}

_snappyNativeDir="$(dirname "$scriptDir")"
distDir=${_snappyNativeDir}/dist

THRIFT_VERSION=1.0.0-2
BOOST_VERSION=1.65.1

thrftLibPath=${distDir}/thrift-${THRIFT_VERSION}/lin64/lib
bostLibPath=${distDir}/boost-${BOOST_VERSION}/lin64/lib
snappClientLibPath=${_snappyNativeDir}/build-artifacts/lin/snappyclient/lin64/lib/debug

LD_LIBRARY_PATH=$LD_LIBRARY_PATH:${thrftLibPath}:${bostLibPath}:${snappClientLibPath}:${_snappyNativeDir}/Debug#!/bin/sh

export LD_LIBRARY_PATH

headerLoc=${_snappyNativeDir}/src/snappyclient/headers
boostHeadeLoc=${_snappyNativeDir}/dist/boost-${BOOST_VERSION}/include
cd ${scriptDir}
g++ -std=c++11 -I"${headerLoc}" -I"${boostHeadeLoc}" -O0 -g3 -Wall -c -fmessage-length=0 -MMD -MP -MFfailoverTest_Retry.d -MTfailoverTest_Retry.o -o failoverTest_Retry.o failoverTest_Retry.cpp

g++ -L${bostLibPath} -L${thrftLibPath} -L${snappClientLibPath} -L${_snappyNativeDir}/Debug -rdynamic -o failoverTest_Retry  failoverTest_Retry.o -lcrypto -lodbc -lpthread -lssl -lsnappydata-native -lboost_chrono -lboost_date_time -lboost_filesystem -lboost_log -lboost_log_setup -lboost_system -lboost_thread -lthrift -lpthread -lrt -lgmp

chmod 777 failoverTest_Retry failoverTest_Retry.d

./failoverTest_Retry $locatorIpAddress $locatorPort $timeduration

rm failoverTest_Retry failoverTest_Retry.d failoverTest_Retry.o
