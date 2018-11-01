#!/bin/sh

##################
# helper functions
##################
is_file_generated(){
  file_path=$1
  if [ -e "$file_path" ];then
    file_name=`basename $file_path`
    echo "SUCCESS: file $file_name generated"
  else
    echo "ERROR: file_path $file_path not generated"
    exit 1
  fi
}

rm_if_exists(){
  file_path=$1
  if [ -e "$file_path" ];then
    echo "WARNING: deleting $file_path"
    rm $file_path
  fi
}

function absPath() {
  perl -MCwd -le 'print Cwd::abs_path(shift)' "$1"
}

if [ "$JAVA_HOME" == "" ]; then
  echo "ERROR: JAVA_HOME is not set"
  exit 1;
fi

CURR_DIR=`pwd`
script_real_path=`absPath $0`
# script home is assumed to be
# <SNAPPY_HOME>/store/gemfirexd/core/src/main/java/com/pivotal/gemfirexd/internal/engine/
script_home=`dirname $script_real_path`
snappy_store_home=$script_home/../../../../../../../../../..
gemfire_core_java=$snappy_store_home/gemfire-core/src/main/java
gemfirexd_core_java=$snappy_store_home/gemfirexd/core/src/main/java
gemfirexd_core_lib=$snappy_store_home/gemfirexd/core/lib
# modify below variables as per requirement
gemfire_version="\"7.5.Beta\""
gemfire_buildid="\"1234\""
gemfire_builddate="\"2018-10-12\""
gemfire_buildos="\"linux\""
echo "WARNING: verify below values that are being used"
echo "gemfire_version=$gemfire_version"
echo "gemfire_buildid=$gemfire_buildid"
echo "gemfire_builddate=$gemfire_builddate"
echo "gemfire_buildos=$gemfire_buildos"

# generate and fetch ./com_gemstone_gemfire_internal_NanoTimer.h
cd $gemfire_core_java
javah -d $gemfirexd_core_lib/ com.gemstone.gemfire.internal.NanoTimer
is_file_generated $gemfirexd_core_lib/com_gemstone_gemfire_internal_NanoTimer.h


# generate and fetch com_pivotal_gemfirexd_internal_GemFireXDVersion.h
cd $gemfirexd_core_java
javah -d $gemfirexd_core_lib/ com.pivotal.gemfirexd.internal.GemFireXDVersion
is_file_generated $gemfirexd_core_lib/com_pivotal_gemfirexd_internal_GemFireXDVersion.h

# generate so files
cd $script_home

case "$(uname -s)" in
  Darwin)
    os_name="darwin"
    rm_if_exists $gemfirexd_core_lib/libgemfirexd.dylib
    rm_if_exists $gemfirexd_core_lib/libgemfirexd64.dylib
    clang -DGEMFIRE_VERSION=$gemfire_version -DGEMFIRE_BUILDID=$gemfire_buildid -DGEMFIRE_BUILDDATE=$gemfire_builddate -DGEMFIRE_BUILDOS=$gemfire_buildos -m64 -O3 -fPIC -DPIC -D_REENTRANT -I $JAVA_HOME/include -I $JAVA_HOME/include/$os_name -I $gemfirexd_core_lib/ -dynamiclib utils.c jvmkill.c -o $gemfirexd_core_lib/libgemfirexd64.dylib
    is_file_generated $gemfirexd_core_lib/libgemfirexd64.dylib

    clang -DGEMFIRE_VERSION=$gemfire_version -DGEMFIRE_BUILDID=$gemfire_buildid -DGEMFIRE_BUILDDATE=$gemfire_builddate -DGEMFIRE_BUILDOS=$gemfire_buildos -m32 -O3 -fPIC -DPIC -D_REENTRANT -I $JAVA_HOME/include -I $JAVA_HOME/include/$os_name -I $gemfirexd_core_lib/ -dynamiclib utils.c jvmkill.c -o $gemfirexd_core_lib/libgemfirexd.dylib
    is_file_generated $gemfirexd_core_lib/libgemfirexd.dylib

    chmod -x $gemfirexd_core_lib/*.dylib
  ;;
  Linux)
    os_name="linux"
    rm_if_exists $gemfirexd_core_lib/libgemfirexd.so
    rm_if_exists $gemfirexd_core_lib/libgemfirexd64.so
    gcc -DGEMFIRE_VERSION=$gemfire_version -DGEMFIRE_BUILDID=$gemfire_buildid -DGEMFIRE_BUILDDATE=$gemfire_builddate -DGEMFIRE_BUILDOS=$gemfire_buildos -m32 -O3 -fPIC -DPIC -D_REENTRANT -I $JAVA_HOME/include -I $JAVA_HOME/include/$os_name -I $gemfirexd_core_lib/ -shared utils.c jvmkill.c -o $gemfirexd_core_lib/libgemfirexd.so
     is_file_generated $gemfirexd_core_lib/libgemfirexd.so

    gcc -DGEMFIRE_VERSION=$gemfire_version -DGEMFIRE_BUILDID=$gemfire_buildid -DGEMFIRE_BUILDDATE=$gemfire_builddate -DGEMFIRE_BUILDOS=$gemfire_buildos  -m64 -O3 -fPIC -DPIC -D_REENTRANT -I $JAVA_HOME/include -I $JAVA_HOME/include/$os_name -I $gemfirexd_core_lib/ -shared utils.c jvmkill.c -o $gemfirexd_core_lib/libgemfirexd64.so
    is_file_generated $gemfirexd_core_lib/libgemfirexd64.so

    chmod -x $gemfirexd_core_lib/*.so
  ;;
  *)
    echo "ERROR: kernel not recognized"
    exit 1
  ;;
esac

# go back to initial working dir
cd $CURR_DIR
