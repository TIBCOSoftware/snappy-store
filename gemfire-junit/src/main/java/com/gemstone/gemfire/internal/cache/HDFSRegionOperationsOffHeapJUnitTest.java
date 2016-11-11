/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.gemstone.gemfire.internal.cache;

import java.util.Iterator;
import java.util.Properties;

import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.RegionShortcut;

public class HDFSRegionOperationsOffHeapJUnitTest extends HDFSRegionOperationsJUnitTest {
  static {
    System.setProperty("gemfire.trackOffHeapRefCounts", "true");
    System.setProperty("gemfire.trackOffHeapFreedRefCounts", "true");
  }

  @Override
  protected void clearBackingCHM(Region<Integer, String> r) {
    PartitionedRegion pr = (PartitionedRegion)r;
    for (BucketRegion br : pr.getDataStore().getAllLocalBucketRegions()) {
      assertTrue(br.getRegionMap() instanceof HDFSRegionMap);
      ConcurrentRegionEntryMap chm = ((AbstractRegionMap)br.getRegionMap())._getMap();
      Iterator it = chm.iterator();
      while (it.hasNext()) {
        Object key = it.next();
        OffHeapRegionEntry re = (OffHeapRegionEntry)chm.removeKey(key);
        assert re != null;
        re.release();
      }
      // wait here to make sure that the queue has been flushed
    }
    sleep(pr.getFullPath());
  }

  @Override
  public void tearDown() throws Exception {
    
    OffHeapTestUtil.checkOrphans();
    super.tearDown();
  }
  @Override
  protected Region<Integer, String> createRegion(String regionName) {
    RegionFactory<Integer, String> rf = cache.createRegionFactory(RegionShortcut.PARTITION_HDFS);
    PartitionAttributes prAttr = new PartitionAttributesFactory().setTotalNumBuckets(10).create();
    rf.setPartitionAttributes(prAttr);
    rf.setEnableOffHeapMemory(true);
    rf.setHDFSStoreName(hdfsStore.getName());
    Region<Integer, String> r = rf.create(regionName);
//    addListener(r);
    
    ((PartitionedRegion) r).setQueryHDFS(true);
    return r;
  }
  @Override
  protected Properties getDSProps() {
    Properties props = super.getDSProps();
    props.setProperty("off-heap-memory-size", "50m");
    return props;
  }
  
  

}
