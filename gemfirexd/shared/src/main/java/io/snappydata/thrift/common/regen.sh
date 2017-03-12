#!/bin/sh

if [ -z "$THRIFT_VERSION" ]; then
  THRIFT_VERSION=0.9.3
fi
if [ -z "$SOFTWARE_PREFIX" ]; then
  SOFTWARE_PREFIX=/export/shared/software
fi
PATH=$PATH:$SOFTWARE_PREFIX/thrift-${THRIFT_VERSION}/lin64/bin
LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$SOFTWARE_PREFIX/thrift-${THRIFT_VERSION}/lin64/lib

export PATH LD_LIBRARY_PATH

rm -f ../*.java
thrift --gen "java:skip_async=true" snappydata.thrift && \
mv gen-java/io/snappydata/thrift/*.java ../. && \
rm -rf gen-java && { \
  cp ColumnValue.java.tmpl ../ColumnValue.java;
  cp BlobChunk.java.tmpl ../BlobChunk.java;
  cp HostAddress.java.tmpl ../HostAddress.java;
  cp Row.java.tmpl ../Row.java;
  cp ServerType.java.tmpl ../ServerType.java;
}
