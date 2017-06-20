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
import com.gemstone.gemfire.cache.execute.FunctionService;

/**
 * This is the peer to which FunctionExecutionPeer2 connects for function execution.
 * This peer executes the function execution request and returns the results to
 * the requesting peer.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.0
 */
public class FunctionExecutionPeer1 {

  public static final String EXAMPLE_REGION_NAME = "exampleRegion";

  private final BufferedReader stdinReader;

  public FunctionExecutionPeer1() {
    this.stdinReader = new BufferedReader(new InputStreamReader(System.in));
  }

  public static void main(String[] args) throws Exception {
    new FunctionExecutionPeer1().run();
  }

  public void run() throws Exception {

    writeToStdout("Peer to which other peer sends request for function Execution");
    writeToStdout("Connecting to the distributed system and creating the cache... ");

    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "FunctionExecutionPeer1")
        .set("cache-xml-file", "xml/FunctionExecutionPeer.xml")
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion(EXAMPLE_REGION_NAME);
    writeToStdout("Example region \"" + exampleRegion.getFullPath() + "\" created in cache.");

    writeToStdout("Registering the function MultiGetFunction on Peer");
    MultiGetFunction function = new MultiGetFunction();
    FunctionService.registerFunction(function);

    writeToStdout("Please start Other Peer And Then Press Enter to continue.");
    stdinReader.readLine();
    
    System.out.println("Closing the cache and disconnecting.");
    cache.close();
  }

  private static void writeToStdout(String msg) {
    System.out.println(msg);
  }
}
