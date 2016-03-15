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
 * This example shows a producer/consumer system with the consumer configured 
 * as a replicate of the producer (push model, complete data replication). 
 * Please refer to the quickstart guide for instructions on how to run this 
 * example. 
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 4.1.1
 */
public class PushProducer {

  public static void main(String[] args) throws Exception {
    System.out.println("\nConnecting to the distributed system and creating the cache.");
    
    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "PushProducer")
        .set("cache-xml-file", "xml/PushProducer.xml")
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    System.out.println("Example region, " + exampleRegion.getFullPath() + ", created in cache. ");

    // Create 5 entries and then update those entries
    for (int iter = 0; iter < 2; iter++) {
      for (int count = 0; count < 5; count++) {
        String key = "key" + count;
        String value =  "value" + (count + 100*iter);
        System.out.println("Putting entry: " + key + ", " + value);
        exampleRegion.put(key, value);
      }
    }
    
    // Close the cache and disconnect from GemFire distributed system
    System.out.println("\nClosing the cache and disconnecting.");
    cache.close();
    
    System.out.println("\nPlease press Enter in the PushConsumer.");
  }
}
