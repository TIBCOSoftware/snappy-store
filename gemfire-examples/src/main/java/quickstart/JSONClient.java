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

import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.pdx.JSONFormatter;
import com.gemstone.gemfire.pdx.PdxInstance;

/**
 * In this example of client/server caching, the server listens on a port for 
 * client requests and updates. This client first adds JSON Document in gemfire 
 * cache. Then it retrieves that document from cache. Then it shows how to 
 * query that document.
 * <p>
 *
 * @author GemStone Systems, Inc.
 * @since 5.8
 */
public class JSONClient {

  public static final String EXAMPLE_REGION_NAME = "exampleRegion";

  public static void main(String[] args) throws Exception {

    System.out.println("Connecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "JSONClient")
        .set("cache-xml-file", "xml/JsonClient.xml")
        .create();

    // Get the exampleRegion
    Region<String, PdxInstance> exampleRegion = cache.getRegion(EXAMPLE_REGION_NAME);
    System.out.println("Example region \"" + exampleRegion.getFullPath() + "\" created in cache.");
    System.out.println();
    System.out.println("Putting JSON documents.");
    
    String jsonCustomer = "{"
        + "\"firstName\": \"John\","
        + "\"lastName\": \"Smith\","
        + " \"age\": 25,"
        + "\"address\":"
        + "{"
        + "\"streetAddress\": \"21 2nd Street\","
        + "\"city\": \"New York\","
        + "\"state\": \"NY\","
        + "\"postalCode\": \"10021\""
        + "},"
        + "\"phoneNumber\":"
        + "["
        + "{"
        + " \"type\": \"home\","
        + "\"number\": \"212 555-1234\""
        + "},"
        + "{"
        + " \"type\": \"fax\","
        + "\"number\": \"646 555-4567\""
        + "}"
        + "]"
        + "}";
     
    System.out.println("JSON documents added into Cache: " + jsonCustomer);
    System.out.println();
    exampleRegion.put("jsondoc1", JSONFormatter.fromJSON(jsonCustomer));
   
    String getJsonCustomer = JSONFormatter.toJSON(exampleRegion.get("jsondoc1"));
    
    System.out.println("Got JSON documents from Cache: " + getJsonCustomer);
    System.out.println();
    System.out.println("Executed query on JSON doc with predicate \" age = 25 \"");
    System.out.println();
    SelectResults<PdxInstance> sr = exampleRegion.query("age = 25");
    
    System.out.println("got expected result = " + sr.size());
    System.out.println();
    System.out.println("got expected result value = " + JSONFormatter.toJSON(sr.iterator().next()));
    System.out.println();
   
    // Close the cache and disconnect from GemFire distributed system
    System.out.println("Closing the cache and disconnecting.");
    cache.close();

    System.out.println("In the other session, please hit Enter in the JSON client");
    System.out.println("and then stop the cacheserver with 'gfsh stop server --dir=server_json'.");
  }
}
