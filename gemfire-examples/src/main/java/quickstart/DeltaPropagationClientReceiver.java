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
 * Client that registers interest to receives all delta updates.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 */
public class DeltaPropagationClientReceiver {
  
  public static void main(String[] args) throws Exception {
    writeToStdout("Connecting to the distributed system and creating the cache.");
    
    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "DeltaPropagationClientReceiver")
        .set("cache-xml-file", "xml/DeltaClient2.xml")
        .create();
    
    Region<Object, Object> reg = cache.getRegion("exampleRegion");
    reg.registerInterest("ALL_KEYS");
    
    writeToStdout("Please press Enter to stop the receiver.");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();
    cache.close();
  }

  private static void writeToStdout(String msg) {
    System.out.println(msg);
  }
}
