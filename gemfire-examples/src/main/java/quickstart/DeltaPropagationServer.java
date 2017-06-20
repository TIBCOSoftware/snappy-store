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

/**
 * DeltaPropagationServer.java has the server to which the
 * DeltaPropagationClient connects. See the DeltaPropagationClient or the
 * quickstart guide for run instructions. To stop the server, type "Exit" in
 * server console.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 */
public class DeltaPropagationServer {

  private static void writeToStdout(String msg) {
    System.out.println(msg);
  }

  public static void main(String[] args) throws Exception {
    writeToStdout("This example demonstrates delta propagation. This program is a server,");
    writeToStdout("listening on a port for client requests. The client program connects and");
    writeToStdout("requests data. The client in this example is also configured to produce/consume");
    writeToStdout("information on data destroys and updates.");

    writeToStdout("To stop the program, press Ctrl c in console.");

    writeToStdout("Connecting to the distributed system and creating the cache...");
    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "DeltaPropagationServer")
        .set("cache-xml-file", "xml/DeltaServer.xml")
        .set("mcast-port", "38485")
        .create();
    
    writeToStdout("Connected to the distributed system.");
    writeToStdout("Created the cache.");

    writeToStdout("Please press Enter to stop the server.");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();
    cache.close();
  }
}
