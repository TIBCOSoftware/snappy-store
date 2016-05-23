#!/bin/sh

PATH=$PATH:/export/shared/software/thrift-0.9.3/linux64/bin
LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/export/shared/software/thrift-0.9.3/linux64/lib

export PATH LD_LIBRARY_PATH

rm -f ../*.java
thrift --gen "java:skip_async=true" snappydata.thrift && \
mv gen-java/io/snappydata/thrift/*.java ../. && \
rm -rf gen-java && { \
  cp ColumnValue.java.tmpl ../ColumnValue.java;
  cp HostAddress.java.tmpl ../HostAddress.java;
  cp Row.java.tmpl ../Row.java;
  cp ServerType.java.tmpl ../ServerType.java;
}
