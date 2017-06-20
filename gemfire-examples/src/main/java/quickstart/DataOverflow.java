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
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.Region;

/**
 * This example shows cached data overflow to disk. Overflow is used to keep a
 * region size in check without completely destroying the data. It is specified
 * as the eviction action of a Least Recently Used (LRU) eviction controller
 * installed on the exampleRegion. Please refer to the quickstart guide for
 * instructions on how to run this example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 4.1.1
 */
public class DataOverflow {

  private final BufferedReader inputReader;

  private String overflowDirString;
  private Cache cache;
  private Region<String, byte[]> exampleRegion;

  public static void main(String[] args) throws Exception {

    DataOverflow dataOverflowExample = new DataOverflow();

    dataOverflowExample.createOverflowDirectory();
    dataOverflowExample.initialize();
    dataOverflowExample.causeOverflow();
    dataOverflowExample.causeCompaction();
    dataOverflowExample.cleanup();

    System.exit(0);
  }

  public DataOverflow() {
    this.inputReader = new BufferedReader(new InputStreamReader(System.in));
  }

  private void createOverflowDirectory() {
    System.out.println("This example uses disk to extend a region's capacity. The region is");
    System.out.println("configured with an eviction controller that overflows data to disk when");
    System.out.println("the region reaches a specified capacity.");

    File dir = new File("overflowData1");
    dir.mkdir();
  }

  private void initialize() throws IOException {
    System.out.println();
    System.out.println("Connecting to the distributed system and creating the cache.");

    // Create the cache. This causes the cache-xml-file to be parsed.
    this.cache = new CacheFactory()
        .set("name", "DataOverflow")
        .set("cache-xml-file", "xml/DataOverflow.xml")
        .create();

    // Get the exampleRegion
    this.exampleRegion = cache.getRegion("exampleRegion");

    System.out.println();
    System.out.println("Example region \"" + exampleRegion.getFullPath() + "\" created in cache. ");

    // Get overflowDir from attributes of exampleRegion.
    String diskStoreName = exampleRegion.getAttributes().getDiskStoreName();
    DiskStore ds = this.cache.findDiskStore(diskStoreName);
    if (ds == null) {
      return;
    }
    File[] overflowDirs = ds.getDiskDirs();
    overflowDirString = "";
    for (int i = 0; i < overflowDirs.length; i++) {
      if (i > 0) {
        overflowDirString += ", ";
      }
      overflowDirString += overflowDirs[i];
    }
  }

  private void causeOverflow() throws IOException {

    System.out.println();
    System.out.println("Putting 250 cache entries of 10 kilobytes each into the cache.");
    System.out.println("When the configured limit of 1 megabyte capacity is reached, the data");
    System.out.println("will overflow to files in " + overflowDirString + ". Note the number of");
    System.out.println("overflow files created.");

    for (long i = 0; i < 250; i++) {
      byte[] array = new byte[10 * 1024]; // size approximately 10 KB
      exampleRegion.put("key" + i, array);
    }

    System.out.println();
    System.out.println("Finished putting entries.");
    System.out.println();
    System.out.println("Use another shell to see the overflow files in " + overflowDirString + ".");
    System.out.println("The disk is used to extend available memory and these files are");
    System.out.println("treated as part of the local cache.");
    System.out.println();

    pressEnterToContinue();
  }

  private void causeCompaction() throws IOException {
    System.out.println();
    System.out.println("Destroying some entries to allow compaction of overflow files...");
    for (long i = 0; i < 120; i++) {
      exampleRegion.destroy("key" + i);
    }

    System.out.println();
    System.out.println("Please look again in " + overflowDirString + ". The data in overflow files is");
    System.out.println("compacted and the two files are merged into one.");
    System.out.println();

    pressEnterToContinue();
  }

  private void cleanup() {
    System.out.println();
    System.out.println("Closing the cache and disconnecting.");

    cache.close();
  }

  private void pressEnterToContinue() throws IOException {
    System.out.println("Press Enter in this shell to continue.");
    inputReader.readLine();
  }
}
