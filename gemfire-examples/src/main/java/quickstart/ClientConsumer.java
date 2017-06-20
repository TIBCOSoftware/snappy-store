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

import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.Region;

/**
 * In this example of client/server caching, the server listens on a port for
 * client requests and updates. A clientworker forwards data requests to the
 * server and sends data updates to the server.
 * <p>
 * This client is a consumer. It registers interest in events on the server and
 * the server sends automatic updates for the events.
 * <p>
 * Please refer to the quickstart guide for instructions on how to run this
 * example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 5.8
 */
public class ClientConsumer {

  public static final String USAGE = "Usage: java ClientConsumer <register-interest-type>\n"
      + "  register-interest-type may be one of the following:\n"
      + "    all-keys    Register interest in all keys on the server\n"
      + "    keyset      Register interest in a set of keys on the server\n"
      + "    regex       Register interest in keys on the server matching a regular expression\n";

  public static final String EXAMPLE_REGION_NAME = "exampleRegion";

  private static enum RegisterInterestType {
    ALL_KEYS, KEYSET, REGEX
  }

  public static void main(String[] args) throws Exception {

    if (args.length != 1) {
      System.out.println(USAGE);
      System.exit(1);
    }

    RegisterInterestType registerInterestType;
    if (args[0].equals("all-keys")) {
      registerInterestType = RegisterInterestType.ALL_KEYS;
    } 
    else if (args[0].equals("keyset")) {
      registerInterestType = RegisterInterestType.KEYSET;
    } 
    else if (args[0].equals("regex")) {
      registerInterestType = RegisterInterestType.REGEX;
    } 
    else {
      registerInterestType = null;
      System.out.println(USAGE);
      System.exit(2);
    }

    // Subscribe to the indicated key set
    System.out.println("Connecting to the distributed system and creating the cache.");
    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "ClientConsumer")
        .set("cache-xml-file", "xml/Client.xml")
        .create();

    // Get the exampleRegion which is a subregion of /root
    Region<String, ?> exampleRegion = cache.getRegion(EXAMPLE_REGION_NAME);
    System.out.println("Example region \"" + exampleRegion.getFullPath() + "\" created in cache. ");

    switch (registerInterestType) {
    case ALL_KEYS:
      System.out.println("Asking the server to send me all data additions, updates, and destroys. ");
      exampleRegion.registerInterest("ALL_KEYS");
      break;
    case KEYSET:
      System.out.println("Asking the server to send me events for data with these keys: 'key0', 'key1'");
      exampleRegion.registerInterest("key0");
      exampleRegion.registerInterest("key1");
      break;
    case REGEX:
      System.out.println("Asking the server to register interest in keys matching this");
      System.out.println("regular expression: 'k.*2'");
      exampleRegion.registerInterestRegex("k.*2");
      break;
    default:
      // Can't happen
      throw new RuntimeException();
    }

    System.out.println("The data region has a listener that reports all changes to standard out.");
    System.out.println();
    System.out.println("Please run the worker client in another session. It will update the");
    System.out.println("cache and the server will forward the updates to me. Note the listener");
    System.out.println("output in this session.");
    System.out.println();
    System.out.println("When the other client finishes, hit Enter to exit this program.");

    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();

    System.out.println("Closing the cache and disconnecting.");
    cache.close();
  }
}
