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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.Execution;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;

/**
 * In this example of peer-to-peer function execution, one peer sends a request
 * for function execution to another peer. FunctionExecutionPeer2 creates a region,
 * populates the region and sends a function execution request to FunctionExecutionPeer1
 * while simultaneously executing the function on its own region.
 * It collects the result from its own execution as well as from FunctionExecutionPeer1.
 * Please refer to the quickstart guide for instructions on how to run this
 * example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.0
 */
public class FunctionExecutionPeer2 {

  public static final String EXAMPLE_REGION_NAME = "exampleRegion";

  private final BufferedReader stdinReader;

  public FunctionExecutionPeer2() {
    this.stdinReader = new BufferedReader(new InputStreamReader(System.in));
  }

  public static void main(String[] args) throws Exception {
    new FunctionExecutionPeer2().run();
  }

  public void run() throws Exception {

    writeToStdout("Peer sending function Execution request to other peer as well as executing function on its own region");

    writeToStdout("Connecting to the distributed system and creating the cache... ");

    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "FunctionExecutionPeer2")
        .set("cache-xml-file", "xml/FunctionExecutionPeer.xml")
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion(EXAMPLE_REGION_NAME);
    writeToStdout("Example region \"" + exampleRegion.getFullPath() + "\" created in cache.");

    // Populate the region
    for (int i = 0; i < 20; i++) {
      exampleRegion.put("KEY_" + i, "VALUE_" + i);
    }
    writeToStdout("Example region \"" + exampleRegion.getFullPath() + "\" is populated.");
    
    writeToStdout("Press Enter to continue.");
    stdinReader.readLine();

    writeToStdout("Executing Function : MultiGetFunction on region \""
        + exampleRegion.getFullPath()
        + "\" with filter size " + 3 + " and with MyArrayListResultCollector.");
    MultiGetFunction function = new MultiGetFunction();
    FunctionService.registerFunction(function);
    
    writeToStdout("Press Enter to continue.");
    stdinReader.readLine();
    
    Set<String> keysForGet = new HashSet<String>();
    keysForGet.add("KEY_4");
    keysForGet.add("KEY_9");
    keysForGet.add("KEY_7");
    Execution execution = FunctionService.onRegion(exampleRegion)
        .withFilter(keysForGet)
        .withArgs(Boolean.TRUE)
        .withCollector(new MyArrayListResultCollector());
    
    try { // Our function can throw a FunctionException
      ResultCollector<?, ?> rc = execution.execute(function);

      writeToStdout("Function executed successfully. Now getting the result");
    
      List<?> result = (List<?>)rc.getResult();
      writeToStdout("Got result with size " + result.size() + ".");
    } 
    catch(FunctionException fe) {
      System.err.println("Error in function execution: " + fe.getMessage());
      fe.printStackTrace();
    }

    writeToStdout("Press Enter to continue.");
    stdinReader.readLine();
    
    System.out.println("Closing the cache and disconnecting.");
    cache.close();
  }

  private static void writeToStdout(String msg) {
    System.out.println(msg);
  }
}
