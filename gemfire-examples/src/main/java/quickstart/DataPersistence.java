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

import java.io.File;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.Region;

/**
 * This example shows the persistence of cached data to disk. Persisted data
 * provides a backup of the cached data and can be used to initialize a data
 * region at creation time. Please refer to the quickstart guide for
 * instructions on how to run this example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 4.1.1
 */
public class DataPersistence {

  public static void main(String[] args) throws Exception {

    System.out.println("This example shows persistence of region data to disk.");

    // create the directory where data is going to be stored
    File dir = new File("persistData1");
    dir.mkdir();

    System.out.println();
    System.out.println("Connecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "DataPersistence")
        .set("cache-xml-file", "xml/DataPersistence.xml")
        .set("enable-network-partition-detection", "true")
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    System.out.println();
    System.out.println("Example region \"" + exampleRegion.getFullPath() + "\" created in cache. ");

    // Get persistDir from attributes of exampleRegion
    String diskStoreName = exampleRegion.getAttributes().getDiskStoreName();
    DiskStore ds1 = cache.findDiskStore(diskStoreName);
    if (ds1 == null) {
      return;
    }
    File[] persistDir = ds1.getDiskDirs();
    String persistDirString = "";
    for (int i = 0; i < persistDir.length; i++) {
      if (i > 0) {
        persistDirString += ", ";
      }
      persistDirString += persistDir[i];
    }

    System.out.println();
    System.out.println("Look in " + persistDirString + " to see the files used for region ");
    System.out.println("persistence.");

    // Try to obtain the value for the "key"
    String key = "key1";
    System.out.println();
    System.out.println("Getting value for " + key);
    String value = exampleRegion.get(key);

    if (value == null) {
      System.out.println("No value found for key " + key + ". Get operation returned null.");
      // Create initial value to put...
      value = "value1";
    } 
    else {
      System.out.println("Get returned value: " + value);
      // Increment value to update...
      value = "value" + (Integer.parseInt(value.substring(5)) + 1);
    }

    System.out.println();
    System.out.println("Putting entry: " + key + ", " + value);
    exampleRegion.put(key, value);

    // Close the cache and disconnect from GemFire distributed system
    System.out.println();
    System.out.println("Closing the cache and disconnecting.");
    cache.close();

    System.out.println();
    System.out.println("Each time you run this program, if the disk files are available, they");
    System.out.println("will be used to initialize the region.");
  }
}
