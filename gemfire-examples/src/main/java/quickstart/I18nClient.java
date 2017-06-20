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

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.query.SelectResults;

import java.util.List;

/**
 * In this example a server is loaded with an xml encoded in utf-8 with 
 * Japanese Strings, the client then requests a list of all keys, and fetches 
 * the contents of the server. The primary purpose is to demonstrate
 * GemFire's I18n awareness and help the user understand how to configure their
 * JVM correctly.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.0 
 */
public class I18nClient {
  
  public static void main(String [] args) throws Exception {
    System.out.println("Connecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "I18nClient")
        .set("cache-xml-file", "xml/I18nClient.xml")
        .create();
    
    // Get the exampleRegion
    Region<String, String> region = cache.getRegion("家具店");
    System.out.println("Example region, " + region.getFullPath() + ", created in cache.");

    System.out.println();
    System.out.println("Getting values from the server...");
    String query = "SELECT DISTINCT * FROM " + region.getFullPath() + ".keys";
    SelectResults<String> results = region.query(query);
    List<String> keys = results.asList();
    for (String key : keys) {
      String value = region.get(key);
      System.out.println("item: " + key + " price: " + value);
    }
    System.out.println();
    System.out.println("Closing the cache and disconnecting.");

    cache.close();
  }
}
