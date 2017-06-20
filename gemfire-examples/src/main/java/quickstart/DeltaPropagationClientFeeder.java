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
 * DeltaPropagationClient.java has the client which connects to the
 * DeltaPropagationServer.java.
 * <p>
 * Ensure that before running this class the Server is up.
 * <p>
 * Prerequisites: Build the QuickStart(./build.sh compile-quickstart) and set
 * the CLASSPATH to include $GEMFIRE/quickstart/classes
 * <p>
 * Following are the steps to test the Delta Propagation Functionality.
 * <p>
 * <ol>
 * <li>Start DeltaPropagationServer</li>
 * <li>Start DeltaPropagationClient Receiver</li>
 * <li>Start DeltaPropagationClient Feeder</li>
 * <li>Press Enter in the Producer Window to feed
 * <ul>
 * <li>The values passed to Server Cache.</li>
 * </ul>
 * </ol>
 * <p>
 * Press Enter in Client Window to see the values and time taken in client
 * Cache.
 * <p>
 * To stop the program, press "Ctrl-C" in console.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 */
public class DeltaPropagationClientFeeder {
  
  private static long firstCreate;
  private static final int FEED_CYCLES = 5;
  private static final int PUT_KEY_RANGE = 2;
  
  public static void main(String[] args) throws Exception {
    writeToStdout("Connecting to the distributed system and creating the cache.");

    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "DeltaPropagationClientFeeder")
        .set("cache-xml-file", "xml/DeltaClient1.xml")
        .create();
    
    Region<Object, Object> reg = cache.getRegion("exampleRegion");
    /*int valueSize = 10;*/
    /*int deltaPercent = 2;*/
    
    writeToStdout("Delta is 50%.");
    writeToStdout("Please press Enter to start the feeder.");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();
    
    for (int i = 0; i < FEED_CYCLES; i++) {
      for (int j = 0; j < PUT_KEY_RANGE; j++) {
        DeltaObj value = new DeltaObj();
        value.setObj(i);
        if (firstCreate == 0) {
          firstCreate = System.currentTimeMillis();
        }
        reg.put(j, value);
      }
    }
    
    reg.put("LAST_KEY", firstCreate);
    cache.close();
  }

  private static void writeToStdout(String msg) {
    System.out.println(msg);
  }
}
