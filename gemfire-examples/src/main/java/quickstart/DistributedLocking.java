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
import com.gemstone.gemfire.distributed.DistributedLockService;

/**
 * DistributedLocking.java demonstrates the user of distributed locking to
 * manage concurrent access to resources. In this case, the resource used is a
 * distributed, non-global data region, but it could be a file or any other
 * resource whose use is contended. The program creates a named lock service and
 * then tries 5 times to get a lock from it. Each time it succeeds, it updates a
 * key in the region and reports the update to the screen. This program is
 * intended to be run as two concurrent runs, so there is contention created for
 * the lock. See the quickstart guide for run instructions.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 */
public class DistributedLocking {

  public static void main(String[] args) throws Exception {
    System.out.println("This program demonstrates how the distributed locking service can be");
    System.out.println("used to avoid access conflict on a resource. In this case, the resource");
    System.out.println("used is a distributed, non-global data region.");
    System.out.println("Another common use case for this is access to a common file.");

    System.out.println();
    System.out.println("Connecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "DistributedLocking" + System.currentTimeMillis())
        .set("cache-xml-file", "xml/DistributedLocking.xml")
        .create();

    // Get a unique ID for this process to be used in the values this process
    // puts into the cache
    String memberPID = "memberPID";

    // Create a distributed named lock service
    DistributedLockService dLS = DistributedLockService.create("distLockService", cache.getDistributedSystem());

    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");

    String key1Get, key1Put;
    boolean exampleRegionLock = false;
    for (int i = 1; i < 5; ++i) {
      try {
        exampleRegionLock = dLS.lock("exampleRegionLock", 3000, 60000);
        if (!exampleRegionLock) {
          System.out.println("Failed to get the lock on attempt #" + i);
          System.out.println();
        } 
        else {
          // Got the lock, do the update
          System.out.println("I have obtained the lock on attempt #" + i);
          key1Get = exampleRegion.get("key1");
          System.out.println("Got key1 whose cached value is " + key1Get);
          key1Put = memberPID + "_" + i;
          exampleRegion.put("key1", key1Put);
          System.out.println("Put key1 with value " + key1Put);
          // Sleep w/lock for a bit - might block the other app's lock request
          Thread.sleep(3000);
        }
      } 
      finally {
        // Check that lock is held before unlocking - might not have
        // gotten it or it might have expired already
        if (exampleRegionLock) {
          System.out.println("Releasing lock ...");
          System.out.println();
          dLS.unlock("exampleRegionLock");
        }
      }
    }

    System.out.println("Closing the cache and disconnecting...");
    cache.close();
    System.out.println("Closed the cache");
  }
}
