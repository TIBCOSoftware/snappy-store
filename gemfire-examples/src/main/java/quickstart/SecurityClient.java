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
import com.gemstone.gemfire.security.NotAuthorizedException;
import com.gemstone.gemfire.cache.client.*;

/**
 * In this example of secure client, the server listens on a port for client
 * requests and updates. The client does put and get on the server with valid
 * PUT credentials. This client uses the valid LDAP username and password.
 * Please refer to the quickstart guide for instructions on how to run this
 * example.
 * <p>
 * Add $GEMFIRE/lib/gfSecurityImpl.jar to your CLASSPATH before running this 
 * example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 5.5
 */
public class SecurityClient {
  
  public static void main(String[] args) throws Exception {
    /*
     * User has to provide the username and password inputs through the command line argument.
     * args[0] and args[1] are the values of username and password respectively.
     * 
     * example: SecurityClient gemfire6 gemfire6
     */
    if (args.length != 2) {
      System.err.println("Usage: java  quickstart.SecurityClient <username> <password>");
      System.exit(1);
    }
    System.out.println("Setting security properties for client");
    
    String username = args[0];
    String password = args[1];
    
    System.out.println("\nConnecting to the distributed system and creating the cache.");

    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "SecurityClient")
        .set("cache-xml-file", "xml/SecurityClient.xml")
        .set("security-client-auth-init", "templates.security.UserPasswordAuthInit.create")
        .set("security-username", username)
        .set("security-password", password)
        .create();

    // Get the exampleRegion
    Region<String, String> exampleRegion = cache.getRegion("exampleRegion");
    if (exampleRegion == null) {
      System.out.println("The Region got is Null");
      return;
    }
    System.out.println("Example region, " + exampleRegion.getFullPath() + ", created in cache.");

    System.out.println("\nPutting three values in the cache...");
    String key = null;
    String value = null;
    for (int i = 1; i < 4; i++) {
      key = "key" + i;
      value = "value" + i;
      System.out.println("Putting entry: " + key + ", " + value);
      exampleRegion.put(key, value);
    }

    try {
      System.out.println("Getting entry: key1");
      exampleRegion.get("key1");
    }
    catch (ServerOperationException ex) {
      NotAuthorizedException naex = (NotAuthorizedException) ex.getCause();
      System.out.println("Get operation generated expected NotAuthorizedException: " + naex.getMessage());
    }
    
    // Close the cache and disconnect from GemFire distributed system
    cache.close();
    System.out.println("SecurityClient closed");
  }
}
