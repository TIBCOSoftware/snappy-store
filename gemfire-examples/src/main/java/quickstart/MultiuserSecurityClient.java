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
/**
 * 
 */
package quickstart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionService;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ServerOperationException;
import com.gemstone.gemfire.cache.execute.Execution;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.cache.query.CqAttributes;
import com.gemstone.gemfire.cache.query.CqAttributesFactory;
import com.gemstone.gemfire.cache.query.CqListener;
import com.gemstone.gemfire.cache.query.CqQuery;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.security.NotAuthorizedException;

/**
 * In this example of secure client, the server listens on a port for client
 * requests and updates. The client does put and get on the server on behalf of
 * multiple users with different credentials. This client uses the valid
 * username and password. Please refer to the quickstart guide for instructions
 * on how to run this example.
 * <p>
 * Add $GEMFIRE/lib/gfSecurityImpl.jar to your CLASSPATH before running this
 * example.
 * <p>
 * 
 * @author GemStone Systems, Inc
 * @since 6.5
 */
public class MultiuserSecurityClient {

  public static void main(String[] args) throws Exception {
    if (args.length > 0) {
      System.err.println("\n   Usage: java quickstart.MultiuserSecurityClient");
      System.exit(1);
    }

    System.out.println("Please select your option.");
    System.out.println(" Enter 1 for put and get operations.");
    System.out.println(" Enter 2 for executing functions.");
    System.out.println(" Enter 3 for executing Continuous Query (CQs).");
    System.out.println(" Your selection: ");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));

    int operation = 0;
    String input = "";
    try {
      input = bufferedReader.readLine();
      operation = Integer.parseInt(input);
    } 
    catch (NumberFormatException nfe) {
      System.out.println("Invalid selection: " + input);
      System.exit(1);
    }
    
    if (operation < 1 || operation > 3) {
      System.out.println("Invalid selection: " + operation);
      System.exit(1);
    }

    System.out.println("-----------------------------------------------------");
    System.out.println("Setting security properties for client...");

    // Create and configure the client cache
    ClientCache cache = new ClientCacheFactory()
        .set("name", "SecurityClient")
        .set("cache-xml-file", "xml/MultiuserSecurityClient.xml")
        .set("security-client-auth-init", "templates.security.UserPasswordAuthInit.create")
        .create();

    String[] usernames;
    String[] passwords;
 
    switch (operation) {
    case 1:
      // writer0 is not authorized to do a GET operation but a PUT operation.
      // reader1 is not authorized to do a PUT operation but a GET operation.
      System.out.println("-----------------------------------------------------");
      System.out.println("User 'writer0' is authorized to do a PUT operation, but not a GET operation.");
      System.out.println("User 'reader1' is authorized to do a GET operation, but not a PUT operation.");
      System.out.println("-----------------------------------------------------");
      pressEnterToContinue();
      usernames = new String[] {"writer0", "reader1"};
      passwords = new String[] {"writer0", "reader1"};
      for (int i = 0; i < usernames.length; i++) {
        final String username = usernames[i];
        final String password = passwords[i];
        doOps(cache, username, password);
        pressEnterToContinue();
      }
      break;
    case 2:
      // writer0 is not authorized to do a EXECUTE_FUNCTION operation.
      // reader1 is authorized to do a EXECUTE_FUCTION operation.
      System.out.println("-----------------------------------------------------");
      System.out.println("User 'writer0' is not authorized to perform EXECUTE_FUNCTION operations.");
      System.out.println("User 'reader1' is authorized to do EXECUTE_FUNCTION operations.");
      System.out.println("-----------------------------------------------------");
      pressEnterToContinue();
      usernames = new String[] {"writer0", "reader1"};
      passwords = new String[] {"writer0", "reader1"};

      for (int i = 0; i < usernames.length; i++) {
        final String username = usernames[i];
        final String password = passwords[i];
        doFunctionExecute(cache, username, password);
        pressEnterToContinue();
      }
      break;
    case 3:
      doCq(cache);
      break;
    default:
      System.out.println("Invalid option: " + operation);
      break;
    }

    System.out.println("\nMultiuserSecurityClient closed.");
    System.out.println("-----------------------------------------------------");
  }

  private static void doOps(ClientCache cache, String username, String password) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("security-username", username);
    properties.setProperty("security-password", password);

    RegionService regionService = cache.createAuthenticatedView(properties);
    Region<String, String> userRegion = regionService.getRegion("exampleRegion");
    if (userRegion == null) {
      System.out.println("[" + username + "] "
          + "The region exampleRegion could not be created in the cache.");
      return;
    }
    
    System.out.println("\n[" + username + "] Performing put and get operations...");
    System.out.println("[" + username + "] Putting values in the cache...");
    Thread.sleep(500);
    String key = null;
    String value = null;
    try {
      for (int j = 1; j < 3; j++) {
        key = "key" + j;
        value = "value" + j;
        System.out.println("[" + username + "] Putting entry: " + key + ", " + value);
        Thread.sleep(500);
        userRegion.put(key, value);
      }
    } catch (ServerOperationException soe) {
      NotAuthorizedException nae = (NotAuthorizedException)soe.getCause();
      System.out.println("[" + username + "] Got expected NotAuthorizedException: " + nae.getMessage());
    }

    try {
      System.out.println("[" + username + "] Getting entry: key1");
      value = userRegion.get("key1");
      Thread.sleep(500);
      System.out.println("[" + username + "] Got value for key1: " + value);
    } 
    catch (ServerOperationException soe) {
      NotAuthorizedException nae = (NotAuthorizedException)soe.getCause();
      System.out.println("[" + username + "] Got expected NotAuthorizedException: " + nae.getMessage());
    }
    
    regionService.close();
    System.out.println("[" + username + "] Closed the cache for this user.");
    Thread.sleep(500);
  }

  private static void doFunctionExecute(ClientCache cache, String username, String password) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("security-username", username);
    properties.setProperty("security-password", password);
    
    RegionService regionService = cache.createAuthenticatedView(properties);
    Region<String, String> userRegion = regionService.getRegion("functionServiceExampleRegion");
    if (userRegion == null) {
      System.out.println("[" + username + "] The region functionServiceExampleRegion "
          + "could not be created in the cache.");
      return;
    }
    try {
      MultiGetFunction function = new MultiGetFunction();
      FunctionService.registerFunction(function);
      Set<String> keysForGet = new HashSet<String>();
      keysForGet.add("KEY_4");
      keysForGet.add("KEY_9");
      keysForGet.add("KEY_7");

      Execution execution = FunctionService.onRegion(userRegion)
          .withFilter(keysForGet)
          .withArgs(Boolean.FALSE)
          .withCollector(new MyArrayListResultCollector());
      
      System.out.println("\n[" + username + "] Executing function on region...");
      ResultCollector<?, ?> rcRegion = execution.execute(function);
      
      System.out.println("[" + username + "] Function executed, now getting the result...");
      Thread.sleep(500);
      
      List<?> resultRegion = (List<?>)rcRegion.getResult();
      System.out.println("[" + username + "] Got result with size " + resultRegion.size() + ".");
    }
    catch (FunctionException ex) {
      if ((ex.getCause() instanceof ServerOperationException 
          && (ex.getCause()).getCause() instanceof NotAuthorizedException)) {
        System.out.println("[" + username + "] Got expected NotAuthorizedException: "
            + ex.getCause().getCause());
      }
    }
    try {
      ServerFreeMemFunction function = new ServerFreeMemFunction();
      FunctionService.registerFunction(function);
      Execution execution = FunctionService.onServer(regionService)
          .withCollector(new MyArrayListResultCollector());
      
      System.out.println("[" + username + "] Executing function on server...");
      ResultCollector<?, ?> rcServer = execution.execute(function);
      
      System.out.println("[" + username + "] Function executed, now getting the result...");
      Thread.sleep(500);
      
      List<?> resultServer = (List<?>)rcServer.getResult();
      System.out.println("[" + username + "] Got result with size " + resultServer.size() + ".");

      Thread.sleep(500);
    }
    catch (ServerOperationException soe) {
      if (soe.getCause() instanceof NotAuthorizedException) {
        System.out.println("[" + username + "] Got expected NotAuthorizedException: " + soe.getCause());
      }
    }
    
    regionService.close();
    System.out.println("[" + username + "] Closed the cache for this user.");
    Thread.sleep(500);
  }

  public static void doCq(ClientCache cache) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("security-username", "root");
    properties.setProperty("security-password", "root");
    RegionService regionService1 = cache.createAuthenticatedView(properties);

    properties = new Properties();
    properties.setProperty("security-username", "root");
    properties.setProperty("security-password", "root");
    RegionService regionService2 = cache.createAuthenticatedView(properties);

    properties = new Properties();
    properties.setProperty("security-username", "reader1");
    properties.setProperty("security-password", "reader1");
    RegionService regionService3 = cache.createAuthenticatedView(properties);

    QueryService queryService1 = regionService1.getQueryService();
    QueryService queryService2 = regionService2.getQueryService();
    QueryService queryService3 = regionService3.getQueryService();

    Region<String, String> exampleRegion = regionService1.getRegion("exampleRegion");

    // Create CQ Attributes.
    CqAttributesFactory cqAf = new CqAttributesFactory();

    // Construct a new CQ for each user.
    String userOneCqName = "CQ_1";
    String userOneQuery = "SELECT * FROM /exampleRegion e where e='VALUE_1' OR e='VALUE_3'";
    System.out.println("User 1 executing CQ \"" + userOneCqName + "\" with query ");
    System.out.println("    \"" + userOneQuery + "\"");
    
    // Initialize and set CqListener for first user.
    CqListener[] cqListeners = {new SimpleCqListener("User 1")};
    cqAf.initCqListeners(cqListeners);
    CqAttributes cqa = cqAf.create();
    
    // Execute the Cq for first user. This registers the cq on the server.
    CqQuery cq1 = queryService1.newCq(userOneCqName, userOneQuery, cqa);
    cq1.execute();

    String userTwoCqName = "CQ_2";
    String userTwoQuery = "SELECT * FROM /exampleRegion e "
        + "where e='VALUE_2' OR e='VALUE_3'";
    System.out.println("User 2 executing CQ \"" + userTwoCqName
        + "\" with query ");
    System.out.println("    \"" + userTwoQuery + "\"");
    
    // Initialize and set CqListener for second user.
    cqListeners[0] = new SimpleCqListener("User 2");
    cqAf.initCqListeners(cqListeners);
    cqa = cqAf.create();
    
    // Execute the Cq for second user. This registers the cq on the server.
    CqQuery cq2 = queryService2.newCq(userTwoCqName, userTwoQuery, cqa);
    cq2.execute();

    String userThreeCqName = "CQ_3";
    String userThreeQuery = "SELECT * FROM /exampleRegion";
    
    // The User 3 is not authorized to receive CQ events from server.
    System.out.println("User 3 executing CQ \"" + userThreeCqName + "\" with query ");
    System.out.println("    \"" + userThreeQuery + "\"");
    
    // Initialize and set CqListener for third user.
    cqListeners[0] = new SimpleCqListener("User 3");
    cqAf.initCqListeners(cqListeners);
    cqa = cqAf.create();
    
    // Execute the Cq for third user. This registers the cq on the server.
    CqQuery cq3 = queryService3.newCq(userThreeCqName, userThreeQuery, cqa);
    cq3.execute();

    System.out.println("-----------------------------------------------------");
    System.out.println("'User 1' and 'User 2' are authorized to receive CQ events.");
    System.out.println("'User 3' is not authorized receive CQ events.");
    System.out.println("-----------------------------------------------------");

    System.out.println("\nThis client will update the server cache and its CQ "
        + "listeners will get events\nfor any changes to the CQ result set. "
        + "CQ events provide the base operation (change\nin the server's "
        + "cache), and the query operation (change in the CQ's result set).");
    pressEnterToContinue();

    // Create a new value in the cache
    System.out.println("_____________________________________________________");
    System.out.println("Putting key1 with value 'VALUE_1'\n"
        + "This satisfies the query for User 1, so its CqListener "
        + "will report a query create\n" + "event from the server cache.");
    exampleRegion.put("key1", "VALUE_1");

    // Wait for the events to come through so the screen output makes sense
    Thread.sleep(2000);
    pressEnterToContinue();

    // Update value.
    System.out.println("_____________________________________________________");
    System.out.println("Updating key1 with value 'VALUE_2'\n"
        + "This satisfies the query for User 2, so its CqListener "
        + "will report a query create\n"
        + "event from the server cache. The CqListener for User 1 "
        + "will report a query destroy event.");
    exampleRegion.put("key1", "VALUE_2");
    Thread.sleep(2000);
    pressEnterToContinue();

    // Update value.
    System.out.println("_____________________________________________________");
    System.out.println("Updating key1 with value 'VALUE_3'\n"
        + "This adds key1 back into the CQ result set for User 1, "
        + "so its CqListener will\n"
        + "report a query create event. The CqListener for User 2 will "
        + "report a query update event.");
    exampleRegion.put("key1", "VALUE_3");
    Thread.sleep(2000);
    pressEnterToContinue();

    // Close cqs for User 1
    System.out.println("_____________________________________________________");
    System.out.println("Closing CQ for User 1.");
    queryService1.closeCqs();
    System.out.println("Now on, CqListener for User 1 will not be invoked.");
    pressEnterToContinue();

    // Destroy value.
    System.out.println("_____________________________________________________");
    System.out.println("Destroying key1.\n"
        + "This removes key1 from the CQ result set, "
        + "so the CqListener will report a query destroy event.");
    exampleRegion.destroy("key1");
    Thread.sleep(2000);
    pressEnterToContinue();

    // Close the cache and disconnect from GemFire distributed system
    System.out.println("Closing the cache for all users...");
    regionService1.close();
    regionService2.close();
    regionService3.close();
  }
  
  public static void pressEnterToContinue() throws IOException {
    System.out.println("Press Enter to continue.");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();
  }
}
