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

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;

/**
 * This example shows cached data eviction. Use eviction to keep a region size
 * in check when you can easily get the data again from an outside source. If
 * you have data that is hard to retrieve again, you might want to use data
 * overflow. (See the DataOverflow example.) The mechanism for deciding when and
 * what to remove from memory is the same for overflow and standard eviction.
 * Standard eviction just destroys the entry instead of copying it out to disk.
 * Both use a Least Recently Used (LRU) eviction controller to decide what to
 * remove from memory. Please refer to the quickstart guide for instructions on
 * how to run this example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 5.8
 */
public class DataEviction {

  public static void main(String[] args) throws Exception {
    System.out.println("This example keeps the region size below 10 entries by destroying the ");
    System.out.println("least recently used entry when an entry addition would take the count");
    System.out.println("over 10.");
    System.out.println();
    System.out.println("You can set capacity limits based on entry count, absolute region size,");
    System.out.println("or region size as a percentage of available heap.");

    System.out.println("Connecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "DataEviction")
        .set("cache-xml-file", "xml/DataEviction.xml")
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    System.out.println("Example region, " + exampleRegion.getFullPath() + ", created in cache. ");

    System.out.println("Putting 12 cache entries into the cache. The listener will report on");
    System.out.println("the puts and on any destroys done by the eviction controller.");
    for (long i = 1; i < 13; i++) {
      exampleRegion.put("key" + i, "value" + i);
      Thread.sleep(10);
    }
    
    // Close the cache and disconnect from GemFire distributed system
    System.out.println("Closing the cache and disconnecting.");
    cache.close();
  }
}
