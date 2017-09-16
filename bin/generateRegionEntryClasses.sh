#!/bin/bash
# This script should only be run from the top level build directory (i.e. the one that contains build.xml).
# It reads LeafRegionEntry.cpp, preprocesses it and generates all the leaf classes that subclass AbstractRegionEntry.
# It executes cpp. It has been tested with gnu's cpp on linux and the mac.

BASEDIR=gemfire-core/src/main/java
PKGDIR=com/gemstone/gemfire/internal/cache
PKG=com.gemstone.gemfire.internal.cache
SRCDIR=${BASEDIR}/${PKGDIR}
SRCFILE=$SRCDIR/LeafRegionEntry.cpp

for VERTYPE in VM Versioned
do
  for RETYPE in Thin Stats ThinLRU StatsLRU ThinDisk StatsDisk ThinDiskLRU StatsDiskLRU
  do
    for MEMTYPE in Heap OffHeap
    do
      PARENT=VM${RETYPE}RegionEntry
      BASE=${VERTYPE}${RETYPE}RegionEntry
      OUT=${BASE}${MEMTYPE}
      HEAP_CLASS=${BASE}Heap
      VERSIONED_CLASS=${PARENT}${MEMTYPE}
      WP_ARGS=-Wp,-P,-DPARENT_CLASS=$PARENT,-DLEAF_CLASS=$OUT,-DHEAP_CLASS=${HEAP_CLASS},-DVERSIONED_CLASS=${VERSIONED_CLASS},-DPKG=${PKG}
      if [ "$VERTYPE" = "Versioned" ]; then
        WP_ARGS=${WP_ARGS},-DVERSIONED
      fi
      if [[ "$RETYPE" = *Stats* ]]; then
        WP_ARGS=${WP_ARGS},-DSTATS
      fi
      if [[ "$RETYPE" = *Disk* ]]; then
        WP_ARGS=${WP_ARGS},-DDISK
      fi
      if [[ "$RETYPE" = *LRU* ]]; then
        WP_ARGS=${WP_ARGS},-DLRU
      fi
      if [[ "$MEMTYPE" = "OffHeap" ]]; then
        WP_ARGS=${WP_ARGS},-DOFFHEAP
      fi
      (
        echo '/*'
        echo ' * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.'
        echo ' *'
        echo ' * Licensed under the Apache License, Version 2.0 (the "License"); you'
        echo ' * may not use this file except in compliance with the License. You'
        echo ' * may obtain a copy of the License at'
        echo ' *'
        echo ' * http://www.apache.org/licenses/LICENSE-2.0'
        echo ' *'
        echo ' * Unless required by applicable law or agreed to in writing, software'
        echo ' * distributed under the License is distributed on an "AS IS" BASIS,'
        echo ' * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or'
        echo ' * implied. See the License for the specific language governing'
        echo ' * permissions and limitations under the License. See accompanying'
        echo ' * LICENSE file.'
        echo ' */'
        echo
        echo '/**'
        echo ' * Do not modify this class. It was generated.'
        echo ' * Instead modify LeafRegionEntry.cpp and then run'
        echo ' * bin/generateRegionEntryClasses.sh from the directory'
        echo ' * that contains your build.xml.'
        echo ' */'
      ) > $SRCDIR/$OUT.java
      cpp -E $WP_ARGS $SRCFILE >> $SRCDIR/$OUT.java
      #echo VERTYPE=$VERTYPE RETYPE=$RETYPE $MEMTYPE args=$WP_ARGS
    done
  done
done
