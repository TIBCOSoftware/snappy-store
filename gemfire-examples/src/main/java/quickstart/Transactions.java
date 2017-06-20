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
import java.util.Set;
import java.util.TreeSet;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.CacheTransactionManager;
import com.gemstone.gemfire.cache.Region;

/**
 * You can execute transactions against the data in the distributed cache as 
 * you would in a database. GemFire transactions are compatible with Java 
 * Transaction API (JTA) transactions. Please refer to the quickstart guide 
 * for instructions on how to run this example. 
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 4.1.1
 */
public class Transactions {
	
  public static void main(String[] args) throws Exception {
    System.out.println("\nThis example demonstrates transactions on a GemFire cache.");
    System.out.println("\nConnecting to the distributed system and creating the cache.");
    
    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "Transactions")
        .set("cache-xml-file", "xml/Transactions.xml")
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    System.out.println("Example region, " + exampleRegion.getFullPath() + ", created in cache. ");

    // Get JNDI(Java Naming and Directory interface) context
    CacheTransactionManager tx = cache.getCacheTransactionManager();
    
    // Print the data initial data in the region.
    printRegionData(exampleRegion);
    
    // Begin transaction that I will commit...
    System.out.println("\nBefore the first transaction, the cache is empty.\n");
    
    tx.begin();
    for (int count = 0; count < 3; count++) {
      String key = "key" + count;
      String value =  "CommitValue" + count;
      System.out.println("Putting entry: " + key + ", " + value);
      exampleRegion.put(key, value);
    }
    tx.commit();
    
    System.out.println("\nAfter committing the first transaction, the cache contains:");
    printRegionData(exampleRegion);

    System.out.println("\nPress Enter to continue to next transaction...");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();
    
    // Begin a transaction which I will rollback...
    System.out.println("Before the second transaction, the cache contains:");
    printRegionData(exampleRegion);
    System.out.println("");
    
    tx.begin();
    for (int count = 0; count < 3; count++) {
      String key = "key" + count;
      String value =  "RollbackValue" + count;
      System.out.println("Putting entry: " + key + ", " + value);
      exampleRegion.put(key, value);
    }
    tx.rollback();
    
    System.out.println("\nAfter rolling back the second transaction, the cache contains:");
    printRegionData(exampleRegion);
    
    // Close the cache and disconnect from GemFire distributed system
    System.out.println("\nClosing the cache and disconnecting.");
    cache.close();
  }
  
  private static void printRegionData(Region<String, String> exampleRegion) {
    Set<String> keySet = new TreeSet<String>(exampleRegion.keySet());
    for (String entryKey: keySet) {
      Region.Entry<String, String> entry = exampleRegion.getEntry(entryKey);
      String entryValue = entry.getValue();
      System.out.println("        entry: " + entryKey + ", " + entryValue);
    }
  }
}
