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
package quickstart;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;

/**
 * This example shows basic Region operations on a partitioned region, which 
 * has data partitions on two VMs. The region is configured to create one extra 
 * copy of each data entry. The copies are placed on different VMs, so when one 
 * VM shuts down, no data is lost. Operations proceed normally on the other VM.  
 * Please refer to the quickstart guide for instructions on how to run this 
 * example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 */
public class PartitionedRegionVM2 {

  public static final String regionName = "PartitionedRegion";

  public static void main(String[] args) throws Exception {
    System.out.println("\nConnecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "PartitionedRegionVM2")
        .set("cache-xml-file", "xml/PartitionedRegion.xml")
        .create();
    
    System.out.println("\nExample region, " + regionName + ", created in cache.");

    Region<String, String> pr = cache.getRegion(regionName);
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));

    System.out.println("Please press Enter in VM1.");
    bufferedReader.readLine();

    // Get three entries from the partitioned region
    final int endCount = 3;
    System.out.println("Getting " + endCount + " entries from " + regionName  + ". . .");
    for (int count = 0; count < endCount; count++) {
      String key = "key" + count;
      System.out.println("\n     Getting key " + key + ": " + pr.get(key));
    }

    System.out.println("\nPlease press Enter in VM1 again.");
    bufferedReader.readLine();

    // Get three entries from the partitioned region after destroy and invalidate
    System.out.println("Getting the same entries from " + regionName + " after destroy and invalidate. . .");
    for (int count = 0; count < endCount; count++) {
      String key = "key" + count;
      System.out.println("\n     Getting key " + key + ": " + pr.get(key));
    }

    System.out.println("\nAfter VM2 is closed, please press Enter in VM1 to read the values.");
    // Close the cache and disconnect from GemFire distributed system
    System.out.println("\nClosing the cache and disconnecting.");
    cache.close();
  }
}
