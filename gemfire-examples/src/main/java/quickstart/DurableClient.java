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

import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.Region;

/**
 * DurableClient.java has the client which connects to the DurableServer.java.
 * <p>
 * Ensure that before running this class the Server is up.
 * <p>
 * Prerequisites: Build the QuickStart(./build.sh compile-quickstart) and set 
 * the CLASSPATH to include $GEMFIRE/quickstart/classes
 * <p>
 * Following are the steps to test the Durable Functionality.
 * <p>
 * <ol>
 *   <li>Start DurableServer - Here initializing keys key1,key2,key3,key4 with values K1,K2,K3,K4</li>
 *   <li>Start DurableClient</li>
 *   <li>Press Enter in the Server Window to update
 *     <ul>
 *       <li>The values in the Server Cache are updated.</li>
 *       <li>Note the updates being passed onto client for all four keys (2 durable(key3,key4)and 2 non-durable(key1,key2))</li>
 *     </ul>
 *   </li>
 *   <li>Press Enter in Client Window to see the values in client Cache.
 *     <ul>
 *       <li>We find all the four Keys being updated.</li>
 *       <li>Program exits</li>
 *     </ul>
 *   </li>
 *   <li>Restart the Client by running DurableClient.java</li>
 *   <li>Do update on the server by pressing enter in the server window.
 *     <ul>
 *       <li>Note that only for key3,key4 (for which registerKeys was done as Durable) update is received in client window</li>
 *     </ul>
 *   </li>
 *   <li>Press Enter in Client to get the Values
 *     <ul>
 *       <li>we get values for key1,key2(non-durable) as null - for key3,key4 we get the updated Values.</li>
 *     </ul>
 *   </li>
 * </ol>
 * <p>
 * To stop the server, type "Exit" in server console.
 * <p>
 *
 * @author GemStone Systems, Inc.
 */
public class DurableClient {

  public static void main(String[] args) throws Exception {

    writeToStdout("Connecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "DurableClient")
        .set("cache-xml-file", "xml/DurableClient.xml")
        .set("durable-client-id", "DurableClientId")
        .set("durable-client-timeout", "" + 300)
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    if (exampleRegion == null) {
      writeToStdout("Region /exampleRegion does not exist, exiting...");
      return;
    }

    writeToStdout("Registering non-durable interest in keys key1 & key2.");
    exampleRegion.registerInterest("key1", false);
    exampleRegion.registerInterest("key2", false);

    writeToStdout("Registering durable interest in keys key3 & key4.");
    exampleRegion.registerInterest("key3", true);
    exampleRegion.registerInterest("key4", true);

    writeToStdout("Sending Client Ready...");
    cache.readyForEvents();

    writeToStdout();
    writeToStdout("Press Enter in the server window to do an update on the server.");
    writeToStdout("Then press Enter in the client window to continue.");
    String inputString = new BufferedReader(new InputStreamReader(System.in)).readLine();

    writeToStdout();
    writeToStdout("After the update on the server, the region contains:");
    writeToStdout("key1 => " + exampleRegion.get("key1"));
    writeToStdout("key2 => " + exampleRegion.get("key2"));
    writeToStdout("key3 => " + exampleRegion.get("key3"));
    writeToStdout("key4 => " + exampleRegion.get("key4"));

    writeToStdout();
    writeToStdout("Closing the cache and disconnecting from the distributed system...");
    if (inputString.equalsIgnoreCase("CloseCache")) {
      cache.close(false);
    } 
    else {
      cache.close(true);
    }

    writeToStdout("Finished disconnecting from the distributed system. Exiting...");
  }

  private static void writeToStdout() {
    System.out.println("[DurableClient]");
  }

  private static void writeToStdout(String msg) {
    System.out.print("[DurableClient] ");
    System.out.println(msg);
  }
}
