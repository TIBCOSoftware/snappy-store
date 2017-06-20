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
 * DurableServer.java has the server to which the DurableClient connects. See
 * the DurableClient or the quickstart guide for run instructions. To stop the
 * server, type "Exit" in server console.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 */
public class DurableServer {

  public static void main(String[] args) throws Exception {
    writeToStdout("This example demonstrates durable caching. This program is a server,");
    writeToStdout("listening on a port for client requests. The client program connects and");
    writeToStdout("requests data. The client in this example is also configured to forward");
    writeToStdout("information on data destroys and updates.");

    BufferedReader stdinReader = new BufferedReader(new InputStreamReader(System.in));

    writeToStdout();
    writeToStdout("Connecting to the distributed system and creating the cache...");

    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "DurableServer")
        .set("cache-xml-file", "xml/DurableServer.xml")
        .set("mcast-port", "0")
        .create();
    
    writeToStdout("Connected to the distributed system.");
    writeToStdout("Created the cache.");

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    writeToStdout("Example region \"" + exampleRegion.getFullPath() + "\" has been created in the cache.");

    writeToStdout();
    writeToStdout("Please start the DurableClient now...");
    stdinReader.readLine();

    writeToStdout("Initializing the cache:");
    writeToStdout("Putting key1 => value1");
    exampleRegion.put("key1", "value1");
    writeToStdout("Putting key2 => value2");
    exampleRegion.put("key2", "value2");
    writeToStdout("Putting key3 => value3");
    exampleRegion.put("key3", "value3");
    writeToStdout("Putting key4 => value4");
    exampleRegion.put("key4", "value4");

    for (;;) {
      writeToStdout();
      writeToStdout("Press Enter in the server window to update the values in the cache, or 'Exit' to shut down.");
      String input = stdinReader.readLine();
      if (input == null || input.equalsIgnoreCase("Exit")) {
        break;
      }

      writeToStdout("Before updating, the values are:");
      writeToStdout("key1 => " + exampleRegion.get("key1"));
      writeToStdout("key2 => " + exampleRegion.get("key2"));
      writeToStdout("key3 => " + exampleRegion.get("key3"));
      writeToStdout("key4 => " + exampleRegion.get("key4"));

      exampleRegion.put("key1", exampleRegion.get("key1") + "1");
      exampleRegion.put("key2", exampleRegion.get("key2") + "2");
      exampleRegion.put("key3", exampleRegion.get("key3") + "3");
      exampleRegion.put("key4", exampleRegion.get("key4") + "4");

      writeToStdout("The values have been updated in the server cache.");
      writeToStdout("Press Enter in the client window to verify the Updates.");
      writeToStdout();
      writeToStdout("After updating the values, new values in server cache are:");
      writeToStdout("key1 => " + exampleRegion.get("key1"));
      writeToStdout("key2 => " + exampleRegion.get("key2"));
      writeToStdout("key3 => " + exampleRegion.get("key3"));
      writeToStdout("key4 => " + exampleRegion.get("key4"));
    }

    writeToStdout();
    writeToStdout("Closing the cache and disconnecting...");
    cache.close();

    writeToStdout("Finished disconnecting from the distributed system. Exiting...");
  }
  
  private static void writeToStdout(String msg) {
    System.out.print("[DurableServer] ");
    System.out.println(msg);
  }

  private static void writeToStdout() {
    System.out.println("[DurableServer]");
  }
}
