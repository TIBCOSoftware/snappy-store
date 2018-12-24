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
package parReg.execute;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.TransactionInDoubtException;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.internal.ClientMetadataService;
import com.gemstone.gemfire.cache.client.internal.ClientPartitionAdvisor;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.execute.*;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.BucketServerLocation66;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionService;
import com.gemstone.gemfire.internal.cache.execute.PartitionedRegionFunctionExecutor;
import com.gemstone.gemfire.internal.cache.execute.ServerRegionFunctionExecutor;
import com.gemstone.gemfire.pdx.PdxInstance;
import getInitialImage.InitImageBB;
import hydra.*;
import hydra.BridgeHelper.Endpoint;
import parReg.ParRegBB;
import parReg.ParRegUtil;
import parReg.colocation.Month;
import parReg.colocation.ParRegColocation;
import pdx.PdxTest;
import perffmwk.PerfStatMgr;
import perffmwk.PerfStatValue;
import perffmwk.StatSpecTokens;
import util.*;

public class FunctionServiceTest extends ParRegColocation {

  protected static final int FUNC_HA_FIRST_OP = 1;

  protected static final int FUNC_HA_LAST_OP = 5;

  protected static final int FUNC_SINGLEKEY = 1;

  protected static final int FUNC_MULTIPLEKEY = 2;

  protected static final int FUNC_ALLNODES = 3;

  protected static final int funcKeys[] = new int[] { FUNC_SINGLEKEY,
      FUNC_MULTIPLEKEY, FUNC_ALLNODES };

  protected static final int KEYS_FIRST_OP = 1;

  protected static final int KEYS_LAST_OP = 3;

  // protected static final int FUNC_WITH_FILTER = 1;
  //  
  // protected static final int FUNC_WITHOUT_FILTER = 2;
  //  
  // protected static final int FILTER_FIRST_OP = 1;
  //
  // protected static final int FILTER_LAST_OP = 2;
  //  
  // protected static final int funcFilter[] = new int[] { FUNC_WITH_FILTER,
  // FUNC_WITHOUT_FILTER
  // };

  protected static final int FUNC_WITHOUT_ARGS = 1;

  protected static final int FUNC_WITH_ARGS = 2;

  protected static final int ARGS_FIRST_OP = 1;

  protected static final int ARGS_LAST_OP = 2;

  protected static final int funcArgs[] = new int[] { FUNC_WITHOUT_ARGS,
      FUNC_WITH_ARGS };

  protected static final int FUNC_WITHOUT_COLLECTOR = 1;

  protected static final int FUNC_WITH_CUSTOM_COLLECTOR = 2;

  // protected static final int FUNC_WITH_INDIVIDUAL_COLLECTOR = 3; //Currently
  // not required to be tested after discussion with team

  protected static final int COLLECTOR_FIRST_OP = 1;

  protected static final int COLLECTOR_LAST_OP = 2;

  protected static final int funcResultCollectors[] = new int[] {
      FUNC_WITHOUT_COLLECTOR, FUNC_WITH_CUSTOM_COLLECTOR };

  // FUNC_WITH_INDIVIDUAL_COLLECTOR };

  protected static final int FUNC_WITH_SINGLE_DATASET = 1;

  protected static final int FUNC_WITH_MULTIPLE_DATASET = 2;

  protected static final int DATASET_FIRST_OP = 1;

  protected static final int DATASET_LAST_OP = 2;

  protected static final int funcDataSets[] = new int[] {
      FUNC_WITH_SINGLE_DATASET, FUNC_WITH_MULTIPLE_DATASET };

  protected static final int FUNC_SYNCH = 1;

  protected static final int FUNC_ASYNCH = 2;

  protected static final int BLOCKING_FIRST_OP = 1;

  protected static final int BLOCKING_LAST_OP = 2;

  protected static final int funcSynch[] = new int[] { FUNC_SYNCH, FUNC_ASYNCH };

  static FirstRandomFunction prFunction1;

  static FirstRandomFunction prFunction2;

  static SecondRandomFunction regionCheckFunction1;

  static SecondRandomFunction regionCheckFunction2;

  /**
   * Initialize the single instance of this test class but not a region. If this
   * VM has already initialized its instance, then skip reinitializing.
   */
  public synchronized static void HydraTask_initialize() {
    if (testInstance == null) {
      testInstance = new FunctionServiceTest();
      testInstance.initialize();
    }
  }

  /**
   * Hydra task for initializing a data store
   */
  public synchronized static void HydraTask_HA_dataStoreInitialize() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new FunctionServiceTest();
      isBridgeClient = false;
      testInstance.initialize();
      ((FunctionServiceTest)testInstance).registerFunctions();
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      if (isBridgeConfiguration
          && !TestConfig.tab().booleanAt(util.CachePrms.useDeclarativeXmlFile,
              false)) {
        BridgeHelper.startBridgeServer("bridge");
      }
    }
  }

  /**
   * Hydra task for initializing a data store
   */
  public synchronized static void HydraTask_HA_initDataStoreAndServerGroups() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new FunctionServiceTest();
      isBridgeClient = false;
      testInstance.initialize();
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      if (isBridgeConfiguration
          && !TestConfig.tab().booleanAt(util.CachePrms.useDeclarativeXmlFile,
              false)) {                    
        int vmId = RemoteTestModule.getMyVmid();
        String clientName = RemoteTestModule.getMyClientName();
        //Get bridge config name
        Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);
        String bridgeName = (String)bridgeNames.get(vmId % bridgeNames.size());        
        CacheServer cs = BridgeHelper.startBridgeServer(bridgeName); 
      }
    }
  }

  /**
   * Hydra task for initializing a data store based on hydra.BridgePrms-groups
   */
  public synchronized static void HydraTask_HA_initDataStoreWithServerGroups() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new FunctionServiceTest();
      isBridgeClient = false;
      testInstance.initialize();
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      if (isBridgeConfiguration
          && !TestConfig.tab().booleanAt(util.CachePrms.useDeclarativeXmlFile,
              false)) {
        
        Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);            
        int vmId = RemoteTestModule.getMyVmid();
        String clientName = RemoteTestModule.getMyClientName();
        //Get bridge config name
        String bridgeName = (String)bridgeNames.get(vmId % bridgeNames.size());        
        CacheServer cs = BridgeHelper.startBridgeServer(bridgeName);
      }
    }
  }
  
  /**
   * Hydra task for initializing a data store in peer to peer configuration
   */
  public synchronized static void HydraTask_p2p_dataStoreInitialize() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new FunctionServiceTest();
      isBridgeClient = false;
      testInstance.initialize("dataStore");
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      ((FunctionServiceTest)testInstance).registerFunctions();
    }
  }

  /**
   * Hydra task for initializing an accessor in peer to peer configuration
   */
  public synchronized static void HydraTask_p2p_accessorInitialize() {
    if (testInstance == null) {
      testInstance = new FunctionServiceTest();
      isBridgeClient = false;
      testInstance.initialize("accessor");
      ((FunctionServiceTest)testInstance).registerFunctions();
    }
  }

  /**
   * Hydra task for initializing an accessor
   */
  public synchronized static void HydraTask_HA_accessorInitialize() {
    if (testInstance == null) {
      testInstance = new FunctionServiceTest();
      testInstance.initialize();
      ((FunctionServiceTest)testInstance).registerFunctions();
    }
  }

  /**
   * Task for the data stores to register functions
   */
  public synchronized static void HydraTask_registerFunctions() {
    ((FunctionServiceTest)testInstance).registerFunctions();
  }

  /**
   * INITTASK registering function for random function execution test
   */

  public static void HydraTask_initRegisterFunction() {
    prFunction1 = new FirstRandomFunction();
    prFunction2 = new FirstRandomFunction();
    regionCheckFunction1 = new SecondRandomFunction();
    regionCheckFunction2 = new SecondRandomFunction();

    FunctionService.registerFunction(prFunction1);
    FunctionService.registerFunction(prFunction2);
    FunctionService.registerFunction(regionCheckFunction1);
    FunctionService.registerFunction(regionCheckFunction2);
    FunctionService.registerFunction(new OnRegionsFunction());
  }

  /**
   * Hydra task to execute region functions, then stop scheduling.
   */
  public static void HydraTask_doFunctionExecution() {
    PdxTest.initClassLoader();
    BitSet availableOps = new BitSet(operations.length);
    availableOps.flip(FIRST_OP, LAST_OP + 1);
    ((FunctionServiceTest)testInstance).doFunctionExecution(availableOps);
    Log.getLogWriter().info("Cardinality is " + availableOps.cardinality());
    if (availableOps.cardinality() == 0) {
      ParRegBB.getBB().getSharedCounters().increment(ParRegBB.TimeToStop);
      throw new StopSchedulingTaskOnClientOrder("Finished with ops");
    }
  }

  /**
   * Hydra task to execute region functions, then stop scheduling (For HA).
   */
  public static void HydraTask_doFunctionExecution_HA() {
    BitSet availableOps = new BitSet(operations.length);
    availableOps.flip(FUNC_HA_FIRST_OP, FUNC_HA_LAST_OP + 1);
    ((FunctionServiceTest)testInstance).doFunctionExecution(availableOps);
    Log.getLogWriter().info("Cardinality is " + availableOps.cardinality());
    if (availableOps.cardinality() == 0) {
      ParRegBB.getBB().getSharedCounters().increment(ParRegBB.TimeToStop);
      throw new StopSchedulingTaskOnClientOrder("Finished with ops");
    }
  }

  /**
   * Hydra task to execute fire and forget inline functions
   */
  public static void HydraTask_doFireAndForgetExecutions() {
    doFireAndForgetRegionExecutions();
    if (isBridgeConfiguration) {
      doFireAndForgetServerExecutions();
    }
    else {
      doFireAndForgetMemberExecutions();
    }
  }

  /**
   * Hydra task to execute onRegions execution
   */
  public static void HydraTask_doOnRegionsExecutions() {
    ((FunctionServiceTest)testInstance).doOnRegionsExecutions();
  }
  
  /**
   * Hydra task to execute onRegions execution
   */
  public static void HydraTask_doOnRegionsExecutionsHA() {
    ((FunctionServiceTest)testInstance).doOnRegionsExecutionsHA();
  }

  /**
   * Hydra task to execute member functions (on server group as well)
   */
  public static void HydraTask_doRandomMemberFunctionExecutionsWithServerGroup() {
    if (isBridgeConfiguration) {
      serverExecutionArgsCollector();
      serverExecutionWOArgsCollector();
      serverExecutionArgsWOCollector();
      serverExecutionWOArgsWOCollector();
      serverExecutionOnServerGroup();
    }
    else {
      memberExecutionArgsCollector();
      memberExecutionWOArgsCollector();
      memberExecutionArgsWOCollector();
      memberExecutionWOArgsWOCollector();
    }
  }

  /**
   * Hydra task to execute member functions (not on server group)
   */
  public static void HydraTask_doRandomMemberFunctionExecutions() {
    if (isBridgeConfiguration) {
      serverExecutionArgsCollector();
      serverExecutionWOArgsCollector();
      serverExecutionArgsWOCollector();
      serverExecutionWOArgsWOCollector();
    }
    else {
      memberExecutionArgsCollector();
      memberExecutionWOArgsCollector();
      memberExecutionArgsWOCollector();
      memberExecutionWOArgsWOCollector();
    }
  }

  /**
   * Hydra task to execute Random region Functions
   */
  public static void HydraTask_doRandomFunctionExecutions() {
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        doRandomFunctionExecutions(aRegion);
        Log.getLogWriter().info(
            "Random function execution on the region " + regionName);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        doRandomFunctionExecutions(aRegion);
        Log.getLogWriter().info(
            "Random function execution on the region " + regionName);
      }
  }

  /**
   * Hydra task for executing functions on all keys
   */
  public static void HydraTask_executeFunctionAllKeys() {

    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        String regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        ((FunctionServiceTest)testInstance).executeFunctionAllKeys(aRegion);
        ((FunctionServiceTest)testInstance).executeFunctionAllBuckets(aRegion);
        ((FunctionServiceTest)testInstance)
            .executeFunctionMultipleNodes(aRegion);
      }
    }
    else {
      PdxTest.initClassLoader();
      for (int i = 0; i < regionDescriptNames.size(); i++) {
        String regionDescriptName = (String)(regionDescriptNames.get(i));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        ((FunctionServiceTest)testInstance).executeFunctionAllKeys(aRegion);
        ((FunctionServiceTest)testInstance).executeFunctionAllBuckets(aRegion);
        ((FunctionServiceTest)testInstance)
            .executeFunctionMultipleNodes(aRegion);
        ((FunctionServiceTest)testInstance)
            .executeFunctionFilterWithLocalDataSet(aRegion);
      }
    }
  }

  /**
   * Task to execute Random region Functions
   */
  public static void doRandomFunctionExecutions(Region aRegion) {
    Set prKeys = getSomeKeys(aRegion);
    BitSet funcKey = new BitSet(funcKeys.length);
    funcKey.flip(KEYS_FIRST_OP, KEYS_LAST_OP + 1);
    HashSet keySet = ((FunctionServiceTest)testInstance).getKeySet(funcKey,
        prKeys);
    BitSet funcArg = new BitSet(funcArgs.length);
    funcArg.flip(ARGS_FIRST_OP, ARGS_LAST_OP + 1);
    String Args = ((FunctionServiceTest)testInstance).getArgs(funcArg);
    BitSet funcResultCollector = new BitSet(funcResultCollectors.length);
    funcResultCollector.flip(COLLECTOR_FIRST_OP, COLLECTOR_LAST_OP + 1);
    ResultCollector[] resultCollectors = ((FunctionServiceTest)testInstance)
        .getResultCollectors(funcResultCollector);
    BitSet funcDataSet = new BitSet(funcDataSets.length);
    funcDataSet.flip(DATASET_FIRST_OP, DATASET_LAST_OP + 1);
    Execution[] dataSets = ((FunctionServiceTest)testInstance).getDataSets(
        funcDataSet, aRegion);
    doRandomFunctions(keySet, Args, resultCollectors, dataSets);
  }

  /**
   * Task to get the keys from the region that is to be used for the function execution 
   * on the region
   */
  public static Set getSomeKeys(Region aRegion) {
    Set keySet = new HashSet();
    if (aRegion instanceof PartitionedRegion) {
      try {
        do {
          Random rand = new Random();
          keySet.addAll(((PartitionedRegion)aRegion).getSomeKeys(rand));
          keySet.addAll(((PartitionedRegion)aRegion).getSomeKeys(rand));
          keySet.addAll(((PartitionedRegion)aRegion).getSomeKeys(rand));
          Log.getLogWriter().info(
              "In ParRegColocation: Key set size is " + keySet.size());
        } while (keySet.size() == 0);
        return keySet;
      }
      catch (Exception e) {
        throw new TestException("Test Issue - Got the exception ", e);
      }
    }
    else {
      keySet.addAll(aRegion.keySet());
      return keySet;
    }

  }

  /**
   * Task that registers functions
   */
  public void registerFunctions() {
    if (TestConfig.tab().booleanAt(util.CachePrms.useDeclarativeXmlFile, false)) {
      Log.getLogWriter().info("The function registering is through cache.xml");
      return;
    }
    else {
      FunctionService.registerFunction(new RegionOperationsFunction());
      FunctionService.registerFunction(new KeysOperationsFunction());
      FunctionService
          .registerFunction(new LocalDataSetFunctionPrimaryExecute());
      FunctionService
          .registerFunction(new LocalDataSetFunctionPrimaryExecuteFalse());
    }
  }

  /**
   * 
   * Task that does random function execution with variable keyset,
   * args, result collectors and data sets
   */
  public static void doRandomFunctions(HashSet keySet, String args,
      ResultCollector[] resultCollectors, Execution[] dataSets) {

    int keySize = 0;
    if (keySet != null) {
      keySize = keySet.size();
    }

    int resultCollectorsSize = 0;
    if (resultCollectors != null) {
      resultCollectorsSize = resultCollectors.length;
    }

    Log.getLogWriter().info(
        "Executing functions with key set size " + keySize + " args " + args
            + " resultCollectors number " + resultCollectorsSize
            + " and dataSets size " + dataSets.length);

    // Function execution when resultCollector, args and key list are not null
    if (keySet != null & resultCollectors != null & args != null) {
      Log
          .getLogWriter()
          .info(
              "Inside :  keyset - not null ,args - not null and result collector - not null");
      for (int rc = 0; rc < resultCollectors.length; rc++) {
        for (int ds = 0; ds < dataSets.length; ds++) {
          Execution dataSet = dataSets[ds].withFilter(keySet).withArgs(args);
          ArrayList aList = executeAndReturnResults(dataSet,
              resultCollectors[rc]);
          HashMap dataSetAndResult = new HashMap();
          dataSetAndResult.put(dataSet
              .withCollector(new ArrayListResultCollector()), aList);
          validateRandomFunctions(dataSetAndResult);
          resultCollectors[rc] = new ArrayListResultCollector();
        }
      }
    }
    else {
      if (keySet == null) {
        if (args == null) {
          if (resultCollectors == null) {// When keyset,args & resultcollectors
            // are null
            Log
                .getLogWriter()
                .info(
                    "Inside :  keyset - null, args - null, and result collector - null");
            for (int ds = 0; ds < dataSets.length; ds++) {
              Execution dataSet = dataSets[ds]; // Testing by passing null
              ArrayList aList = executeAndReturnResults(dataSet, null);
              HashMap dataSetAndResult = new HashMap();
              dataSetAndResult.put(dataSet, aList);
              validateRandomFunctions(dataSetAndResult);
            }
          }
          else { // When keyset, args are null, resultcollector not null
            Log
                .getLogWriter()
                .info(
                    "Inside : keyset - null ,args - null  and result collector - not null");
            for (int rc = 0; rc < resultCollectors.length; rc++) {
              for (int ds = 0; ds < dataSets.length; ds++) {
                Execution dataSet = dataSets[ds]; // Testing by passing
                // null
                ArrayList aList = executeAndReturnResults(dataSet,
                    resultCollectors[rc]);
                HashMap dataSetAndResult = new HashMap();
                dataSetAndResult.put(dataSet
                    .withCollector(new ArrayListResultCollector()), aList);
                validateRandomFunctions(dataSetAndResult);
                resultCollectors[rc] = new ArrayListResultCollector();
              }
            }
          }
        }
        else {
          if (resultCollectors == null) {// When keyset, resultcollectors are
            // null but args not null
            Log
                .getLogWriter()
                .info(
                    "Inside : keyset - null, args - not null, resultcollector - null");
            for (int ds = 0; ds < dataSets.length; ds++) {
              Execution dataSet = dataSets[ds].withArgs(args); // Testing by
              // passing null
              ArrayList aList = executeAndReturnResults(dataSet, null);
              HashMap dataSetAndResult = new HashMap();
              dataSetAndResult.put(dataSet, aList);
              validateRandomFunctions(dataSetAndResult);
            }
          }
          else { // When keyset are null, resultcollector, args not null
            Log
                .getLogWriter()
                .info(
                    "Inside : keyset - null, args - not null , result collector - not null");
            for (int rc = 0; rc < resultCollectors.length; rc++) {
              for (int ds = 0; ds < dataSets.length; ds++) {
                Execution dataSet = dataSets[ds].withArgs(args); // Testing by
                // passing null
                ArrayList aList = executeAndReturnResults(dataSet,
                    resultCollectors[rc]);
                HashMap dataSetAndResult = new HashMap();
                dataSetAndResult.put(dataSet
                    .withCollector(new ArrayListResultCollector()), aList);
                validateRandomFunctions(dataSetAndResult);
                resultCollectors[rc] = new ArrayListResultCollector();
              }
            }
          }

        }
      }
      else {
        if (args == null) {
          if (resultCollectors == null) {// When keyset is not null ,args &
            // resultcollectors are null
            Log
                .getLogWriter()
                .info(
                    "Inside : keyset - not null and args - null , result collector - null");
            for (int ds = 0; ds < dataSets.length; ds++) {
              Execution dataSet = dataSets[ds].withFilter(keySet); // Testing
              // by
              // passing
              // null
              ArrayList aList = executeAndReturnResults(dataSet, null);
              HashMap dataSetAndResult = new HashMap();
              dataSetAndResult.put(dataSet, aList);
              validateRandomFunctions(dataSetAndResult);
            }
          }
          else { // When keyset, args are null, resultcollector not null
            Log
                .getLogWriter()
                .info(
                    "Inside : keyset - not null , args - null, and result collector - not null ");
            for (int rc = 0; rc < resultCollectors.length; rc++) {
              for (int ds = 0; ds < dataSets.length; ds++) {
                Execution dataSet = dataSets[ds].withFilter(keySet); // Testing
                // by
                // passing
                // null
                ArrayList aList = executeAndReturnResults(dataSet,
                    resultCollectors[rc]);
                HashMap dataSetAndResult = new HashMap();
                dataSetAndResult.put(dataSet
                    .withCollector(new ArrayListResultCollector()), aList); //temporary fix to take care of 
                validateRandomFunctions(dataSetAndResult);
                resultCollectors[rc] = new ArrayListResultCollector();
              }
            }
          }
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "Inside : keyset - not null , args - not null and result collector - null");
          if (resultCollectors == null) {// When resultcollectors are null but
            // args, keyset not null
            for (int ds = 0; ds < dataSets.length; ds++) {
              Execution dataSet = dataSets[ds].withFilter(keySet)
                  .withArgs(args); // Testing
              // by
              // passing
              // null
              HashMap dataSetAndResult = new HashMap();
              ArrayList aList = executeAndReturnResults(dataSet, null);
              dataSetAndResult.put(dataSet, aList);
              validateRandomFunctions(dataSetAndResult);
            }
          }
          else { // Unknown case
            throw new TestException("Test Issue - Unknown configuration");
          }

        }

      }
    }

  }

  /**
   * Task to execute function on member(peer to peer) without args and without result collector.
   */
  public static void memberExecutionWOArgsWOCollector() {

    DistributedSystem ds = theCache.getDistributedSystem();
    Set allMemberSet = new HashSet(((InternalDistributedSystem)ds)
        .getDistributionManager().getNormalDistributionManagerIds());
    InternalDistributedMember localVM = ((InternalDistributedSystem)ds)
        .getDistributionManager().getDistributionManagerId();

    ArrayList list;
    Execution dsExecution = FunctionService.onMembers(ds);

    String scenario = "onMembers(ds) function execution without arguments and using the default result collector \n";

    try {
      list = (ArrayList)dsExecution.execute(prFunction1.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException("Caught the exception during execute " , e);
    }

    if (list.size() != allMemberSet.size() * 2) {
      throw new TestException(
          "For the scenario "
              + scenario
              + " Expected the result of onMembers(ds) execute to be of size of number of members (2 entries/node) "
              + allMemberSet.size() * 2 + " but got the size of " + list.size());
    }
    else {
      Log.getLogWriter().info(
          "Received the expected result of onMembers(ds) execute size "
              + allMemberSet.size());
    }

    list.clear();
    
    Iterator iterator = allMemberSet.iterator();

    scenario = "onMember(ds, member) function execution without arguments and using the default result collector \n";

    while (iterator.hasNext()) {
      InternalDistributedMember member = (InternalDistributedMember)iterator
          .next();
      dsExecution = FunctionService.onMember(ds, member);

      try {
        list = (ArrayList)dsExecution.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {

            Log.getLogWriter().info("Invoking execute in Inline function");

            ArrayList list = new ArrayList();
            if (context.getArguments() == null) {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
              list.add(dc.getName());
            }
            else {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
            }
            context.getResultSender().lastResult(list);
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("For the scenario " + scenario
            + " caught the exception during execute " , e);
      }

      if (list.size() != 1) {
        throw new TestException(
            "For the scenario "
                + scenario
                + " Expected the result of onMember(ds,member) execute to be 1 (1 entries/node) but got the size of "
                + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onMember(ds,member) execute size " + 1);
      }
      list.clear();
    }

    list.clear();
    HashSet remoteVms = new HashSet(allMemberSet);
    remoteVms.remove(localVM);

    dsExecution = FunctionService.onMembers(ds, remoteVms);

    scenario = "onMembers(ds, memberSet) function execution without arguments and using the default result collector \n";

    try {
      list = (ArrayList)dsExecution.execute(new FunctionAdapter() {

        public void execute(FunctionContext context) {

          Log.getLogWriter().info("Invoking execute in Inline function");

          ArrayList list = new ArrayList();
          if (context.getArguments() == null) {
            DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getConfig();
            list.add(dc.getCacheXmlFile());
            list.add(dc.getName());
          }
          else {
            DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getConfig();
            list.add(dc.getCacheXmlFile());
          }
          context.getResultSender().lastResult(list);
        }

        public String getId() {
          return "" + hashCode();
        }

        public boolean hasResult() {
          return true;
        }

      }).getResult();
    }
    catch (Exception e) {
      throw new TestException("During the scenario " + scenario
          + "Caught the exception during execute " , e);
    }

    if (list.size() != remoteVms.size()) {
      throw new TestException(
          "For the scenario "
              + scenario
              + " Expected the result of onMembers(ds,set) execute to be of size of number of members (2 entries/node) "
              + remoteVms.size() + " but got the size of " + list.size());
    }
    else {
      Log
          .getLogWriter()
          .info(
              "Received the expected result of onMembers(ds,set) execute size (2 entries/node)"
                  + remoteVms.size());
    }
  }

  /**
   * Task to execute function on member(peer to peer) with args but without result collector.
   */
  public static void memberExecutionArgsWOCollector() {

    DistributedSystem ds = theCache.getDistributedSystem();
    Set allMemberSet = new HashSet(((InternalDistributedSystem)ds)
        .getDistributionManager().getNormalDistributionManagerIds());
    InternalDistributedMember localVM = ((InternalDistributedSystem)ds)
        .getDistributionManager().getDistributionManagerId();

    ArrayList list;
    Execution dsExecution = FunctionService.onMembers(ds).withArgs("Args");

    String scenario = "onMembers(ds) function execution passing args and with default result collector \n";

    try {
      list = (ArrayList)dsExecution.execute(prFunction1.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException("For the scenario " + scenario
          + " Caught the exception during execute ", e);
    }

    if (list.size() != allMemberSet.size()) {
      throw new TestException(
          "For the scenario "
              + scenario
              + " Expected the result of onMembers(ds) execute to be of size of number of members (1 entries/node) "
              + allMemberSet.size() + " but got the size of " + list.size());
    }
    else {
      Log.getLogWriter().info(
          "Received the expected result of onMembers(ds) execute size "
              + allMemberSet.size());
    }

    list.clear();

    Iterator iterator = allMemberSet.iterator();

    while (iterator.hasNext()) {
      InternalDistributedMember member = (InternalDistributedMember)iterator
          .next();
      dsExecution = FunctionService.onMember(ds, member).withArgs("Args");

      scenario = "onMember(ds, member) function execution passing args and with default result collector \n";

      try {
        list = (ArrayList)dsExecution.execute(prFunction1.getId()).getResult();
      }
      catch (Exception e) {
        throw new TestException("For the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      if (list.size() != 1) {
        throw new TestException(
            "Expected the result of onMember(ds,member) execute to be 1 (1 entries/node) 1  but got the size of "
                + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onMember(ds,member) execute size " + 1);
      }
      list.clear();
    }

    list.clear();
    HashSet remoteVms = new HashSet(allMemberSet);
    remoteVms.remove(localVM);

    dsExecution = FunctionService.onMembers(ds, remoteVms).withArgs("Args");

    scenario = "onMembers(ds, memberSet) function execution passing args and with default result collector \n";

    try {
      list = (ArrayList)dsExecution.execute(prFunction1.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException("During the scenario " + scenario
          + " Caught the exception during execute ", e);
    }

    if (list.size() != remoteVms.size()) {
      throw new TestException(
          "For the scenario "
              + scenario
              + " Expected the result of onMembers(ds,set) execute to be of size of number of members (1 entries/node) "
              + remoteVms.size() + " but got the size of " + list.size());
    }
    else {
      Log.getLogWriter().info(
          "Received the expected result of onMembers(ds,set) execute size "
              + remoteVms.size());
    }
  }

  /**
   * Task to execute function on member(peer to peer) without args but with result collector.
   */
  public static void memberExecutionWOArgsCollector() {

    DistributedSystem ds = theCache.getDistributedSystem();
    Set allMemberSet = new HashSet(((InternalDistributedSystem)ds)
        .getDistributionManager().getNormalDistributionManagerIds());
    InternalDistributedMember localVM = ((InternalDistributedSystem)ds)
        .getDistributionManager().getDistributionManagerId();

    ArrayList list;
    Execution dsExecution = FunctionService.onMembers(ds).withCollector(
        new ArrayListResultCollector());

    String scenario = "onMembers(ds) function execution without passing args and with custom result collector (ArrayListResultCollector) \n";

    try {
      list = (ArrayList)dsExecution.execute(new FunctionAdapter() {

        public void execute(FunctionContext context) {

          Log.getLogWriter().info("Invoking execute in Inline function");

          if (context.getArguments() == null) {
            DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getConfig();
            ArrayList list = new ArrayList();
            list.add(dc.getCacheXmlFile());
            context.getResultSender().sendResult(list);
            list.clear();
            list.add(dc.getName());
            context.getResultSender().lastResult(list);
          }
          else {
            DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getConfig();
            ArrayList list = new ArrayList();
            list.add(dc.getCacheXmlFile());
            context.getResultSender().lastResult(list);
          }
        }

        public String getId() {
          return "" + hashCode();
        }

        public boolean hasResult() {
          return true;
        }

      }).getResult();
    }
    catch (Exception e) {
      throw new TestException("During the scenario " + scenario
          + " Caught the exception during execute " , e);
    }

    if (list.size() != 2 * allMemberSet.size()) {
      throw new TestException(
          "For the scenario "
              + scenario
              + " the function sends two results for every node it was executed. "
              + "Expected the result of onMembers(ds) execute to be of size of number of members (2 entries/node) "
              + 2 * allMemberSet.size() + " but got the size of " + list.size());
    }
    else {
      Log.getLogWriter().info(
          "Received the expected result of onMembers(ds) execute size (2 entries/node)"
              + 2 * allMemberSet.size());
    }

    list.clear();

    Iterator iterator = allMemberSet.iterator();

    while (iterator.hasNext()) {
      InternalDistributedMember member = (InternalDistributedMember)iterator
          .next();
      dsExecution = FunctionService.onMember(ds, member).withCollector(
          new ArrayListResultCollector());

      scenario = "onMember(ds, member) function execution without passing args and with custom result collector (ArrayListResultCollector) \n";

      try {
        list = (ArrayList)dsExecution.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {

            Log.getLogWriter().info("Invoking execute in Inline function");

            ArrayList list = new ArrayList();
            if (context.getArguments() == null) {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
              list.add(dc.getName());
            }
            else {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
            }
            context.getResultSender().lastResult(list);
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute " , e);
      }

      if (list.size() != 2) {
        throw new TestException(
            "For the scenario "
                + scenario
                + " the function sends two results for every node it was executed. "
                + "Expected the result of onMember(ds,member) execute to be 2 (2 entries/node)  but got the size of "
                + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onMember(ds,member) execute size (2 entries/node) " + 2);
      }
      list.clear();
    }

    list.clear();
    HashSet remoteVms = new HashSet(allMemberSet);
    remoteVms.remove(localVM);

    dsExecution = FunctionService.onMembers(ds, remoteVms).withCollector(
        new ArrayListResultCollector());

    scenario = "onMembers(ds, memberSet) function execution without passing args and with custom result collector (ArrayListResultCollector) \n";

    try {
      list = (ArrayList)dsExecution.execute(new FunctionAdapter() {

        public void execute(FunctionContext context) {

          Log.getLogWriter().info("Invoking execute in Inline function");

          ArrayList list = new ArrayList();
          if (context.getArguments() == null) {
            DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getConfig();
            list.add(dc.getCacheXmlFile());
            list.add(dc.getName());
          }
          else {
            DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getConfig();
            list.add(dc.getCacheXmlFile());
          }
          context.getResultSender().lastResult(list);
        }

        public String getId() {
          return "" + hashCode();
        }

        public boolean hasResult() {
          return true;
        }

      }).getResult();
    }
    catch (Exception e) {
      throw new TestException("During the scenario " + scenario
          + " Caught the exception during execute " , e);
    }

    if (list.size() != 2 * remoteVms.size()) {
      throw new TestException(
          "For the scenario "
              + scenario
              + " the function sends two results for every node it was executed. "
              + "Expected the result of onMembers(ds,set) execute to be of size of number of members (2 entries/node) "
              + 2 * remoteVms.size() + " but got the size of " + list.size());
    }
    else {
      Log
          .getLogWriter()
          .info(
              "Received the expected result of onMembers(ds,set) execute size (2 entries/node)"
                  + 2 * remoteVms.size());
    }
  }

  /**
   * Task to execute function on member(peer to peer) with args and with result collector.
   */
  public static void memberExecutionArgsCollector() {
    DistributedSystem ds = theCache.getDistributedSystem();
    Set allMemberSet = new HashSet(((InternalDistributedSystem)ds)
        .getDistributionManager().getNormalDistributionManagerIds());
    InternalDistributedMember localVM = ((InternalDistributedSystem)ds)
        .getDistributionManager().getDistributionManagerId();

    ArrayList list;
    HashMap map;
    Execution dsExecution = FunctionService.onMembers(ds).withArgs("Args")
        .withCollector(new MemberResultsCollector());

    String scenario = "onMembers(ds) function execution passing args and with custom result collector (ArrayListResultCollector) \n";

    try {
      map = (HashMap)dsExecution.execute(prFunction1.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException("During the scenario " + scenario
          + " Caught the exception during execute " , e);
    }

    for (Object member : allMemberSet) {
      String memberID = ((InternalDistributedMember)member).getId();
      if (!map.containsKey(memberID)) {
        throw new TestException(
            "The function execution result onMembers() does not get result from the node (which is in the ds mentioned)"
                + memberID);
      }
      else {
        int sizeOfResultForMember = ((List)(map.get(memberID))).size();
        if (sizeOfResultForMember != 1) {
          throw new TestException(
              "For the scenario "
                  + scenario
                  + " the function FirstRandomFunction sends one result from all the executed node. "
                  + "After function execution expected results per node is 1 after on Members() execution"
                  + "but got the size of " + sizeOfResultForMember
                  + " for the node " + memberID);
        }
      }
    }

    map.clear();

    Iterator iterator = allMemberSet.iterator();
    scenario = "onMember(ds, member) function execution passing args and with custom result collector (ArrayListResultCollector) \n";

    while (iterator.hasNext()) {
      InternalDistributedMember member = (InternalDistributedMember)iterator
          .next();
      dsExecution = FunctionService.onMember(ds, member).withArgs("Args")
          .withCollector(new MemberResultsCollector());

      try {
        map = (HashMap)dsExecution.execute(prFunction1.getId()).getResult();
      }
      catch (Exception e) {
        throw new TestException("For the scenario " + scenario
            + " Caught the exception during execute " , e);
      }

      String memberID = member.getId();

      if (!map.containsKey(memberID)) {
        throw new TestException(
            "The function execution result onMembers() does not get result from the node "
                + memberID);
      }
      else {
        int sizeOfResultForMember = ((List)(map.get(memberID))).size();
        if (sizeOfResultForMember != 1) {
          throw new TestException(
              "For the scenario "
                  + scenario
                  + "After function execution expected results per node is 1 after on Members() execution"
                  + "but got the size of " + sizeOfResultForMember
                  + " for the node " + memberID);
        }
        map.clear();
      }
    }

    HashSet remoteVms = new HashSet(allMemberSet);
    remoteVms.remove(localVM);

    scenario = "onMembers(ds, memberSet)) function execution passing args and with custom result collector (ArrayListResultCollector) \n";

    dsExecution = FunctionService.onMembers(ds, remoteVms).withArgs("Args")
        .withCollector(new ArrayListResultCollector());

    try {
      list = (ArrayList)dsExecution.execute(prFunction1.getId()).getResult();
    }
    catch (Exception e) {
      throw new TestException("For the scenario " + scenario
          + "Caught the exception during execute " , e);
    }

    if (list.size() != remoteVms.size()) {
      throw new TestException(
          "For the scenario "
              + scenario
              + " the function sends one result for every node it was executed. "
              + "Expected the result of onMembers(ds,set) execute to be of size of number of members (1 entries/node) "
              + remoteVms.size() + " but got the size of " + list.size());
    }
    else {
      Log.getLogWriter().info(
          "Received the expected result of onMembers(ds,set) execute size "
              + remoteVms.size());
    }

  }

  /**
   * Task to execute function on server(by client) without args and without result collector.
   */
  public static void serverExecutionArgsCollector() {
    Execution ds = null;
    if (isBridgeConfiguration) {
      Pool pool = PoolHelper.getPool("edgeDescript");
      if (pool == null) {
        Log.getLogWriter().info("Pool is null");
        pool = PoolHelper.createPool("edgeDescript");
      }
      ds = FunctionService.onServer(pool).withArgs("Args").withCollector(
          new MemberResultsCollector());
      HashMap map;
      ArrayList list;

      String scenario = "onServer(pool) function execution passing args and with custom result collector (MemberResultsCollector) \n";

      try {
        map = (HashMap)ds.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {
            Log.getLogWriter().info("Invoking execute in Inline function");
            String memberID = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getMemberId();
            ArrayList list = new ArrayList();
            list.add(memberID);
            context.getResultSender().sendResult(list);
            list.clear();
            context.getResultSender().lastResult(list);// Send an empty list
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      if (map.size() != 1) {
        throw new TestException("For the scenario " + scenario
            + " expected function to execute on one node but got executed on "
            + +map.size() + " nodes");
      }
      else {
        Iterator iterator = map.entrySet().iterator();
        Map.Entry entry = null;
        String key = null;
        String value = null;

        while (iterator.hasNext()) {
          entry = (Map.Entry)iterator.next();
          key = (String)entry.getKey();
          value = (String)((ArrayList)entry.getValue()).get(0);
          if (!key.equals(value)) {
            throw new TestException("For the scenario " + scenario
                + " For the memberID " + key
                + " the result received mentions the executed node ID as "
                + value);
          }
        }
      }

      ds = FunctionService.onServers(pool).withArgs("Args").withCollector(
          new MemberResultsCollector());

      scenario = "onServers(pool) function execution passing args and with custom result collector (MemberResultsCollector) \n";

      map.clear();

      try {
        map = (HashMap)ds.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {
            Log.getLogWriter().info("Invoking execute in Inline function");
            String memberID = ((InternalDistributedSystem)InternalDistributedSystem
                .getAnyInstance()).getMemberId();
            ArrayList list = new ArrayList();
            list.add(memberID);
            context.getResultSender().sendResult(list);
            list.clear();
            context.getResultSender().lastResult(list);// Send an empty list
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("For the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      int numOfServers = TestConfig.tab().intAt(
          parReg.ParRegPrms.numberOfDataStore);

      if (map.size() != numOfServers) {
        throw new TestException("For the scenario " + scenario
            + " the function is expected to get executed on " + numOfServers
            + " (all the servers) but got the executed on " + map.size()
            + " nodes");
      }
      else {
        Iterator iterator = map.entrySet().iterator();
        Map.Entry entry = null;
        String key = null;
        String value = null;

        while (iterator.hasNext()) {
          entry = (Map.Entry)iterator.next();
          key = (String)entry.getKey();
          value = (String)((ArrayList)entry.getValue()).get(0);
          if (!key.equals(value)) {
            throw new TestException("For the scenario " + scenario
                + "For the memberID " + key
                + " the result received mentions the executed node ID as "
                + value);
          }
        }

      }
    }
  }

  /**
   * Task to execute function on server(by client) without args but with result
   * collector.
   */
  public static void serverExecutionWOArgsCollector() {
    Execution ds1 = null;
    Execution ds2 = null;
    if (isBridgeConfiguration) {
      Pool pool = PoolHelper.getPool("edgeDescript");
      if (pool == null) {
        Log.getLogWriter().info("Pool is null");
        pool = PoolHelper.createPool("edgeDescript");
      }
      ds1 = FunctionService.onServer(pool).withCollector(
          new ArrayListResultCollector());
      ArrayList list;
      String scenario = "onServer(pool) function execution without args and with custom result collector (MemberResultsCollector) \n";

      try {
        list = (ArrayList)ds1.execute(prFunction1.getId()).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      if (list.size() != 2) {
        throw new TestException(
            "For the scenario "
                + scenario
                + " the function FirstRandomFunction sends two results from each executed node."
                + "Expected the result of onServer() execute to be of size 2 (2 entries/node) but got the size of "
                + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onServer() execute size 2 (2 entries/node)");
      }

      ds2 = FunctionService.onServers(pool).withCollector(
          new ArrayListResultCollector());
      list.clear();

      scenario = "onServers(pool) function execution without args and with custom result collector (MemberResultsCollector) \n";

      try {
        list = (ArrayList)ds2.execute(prFunction1.getId()).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      Log.getLogWriter().info("List is " + list);

      int numOfServers = TestConfig.tab().intAt(
          parReg.ParRegPrms.numberOfDataStore);

      if (list.size() != 2 * numOfServers) {
        throw new TestException(
            "For the scenario "
                + scenario
                + " the function FirstRandomFunction sends two results from each executed node."
                + "Expected the result of onServer() execute to be of size 2 (2 entries/node) * servers "
                + 2 * numOfServers + " but got the size of " + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onServer() execute size 2 (1 entries/node) * servers "
                    + 2 * numOfServers);
      }

    }
  }

  /**
   * Task to execute function on server(by client) with args but without result
   * collector.
   */
  public static void serverExecutionArgsWOCollector() {

    Execution ds = null;
    if (isBridgeConfiguration) {
      Pool pool = PoolHelper.getPool("edgeDescript");
      if (pool == null) {
        Log.getLogWriter().info("Pool is null");
        pool = PoolHelper.createPool("edgeDescript");
      }
      ds = FunctionService.onServer(pool).withArgs("Args");

      String scenario = "onServer(pool) function execution with args and with default result collector \n";

      ArrayList list;

      try {
        list = (ArrayList)ds.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {

            Log.getLogWriter().info("Invoking execute in Inline function");

            ArrayList list = new ArrayList();
            if (context.getArguments() == null) {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
              list.add(dc.getName());
            }
            else {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
            }
            context.getResultSender().lastResult(list);
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      if (list.size() != 1) {
        throw new TestException("For the scenario "+scenario+
            " Expected the result of onServer() execute to be of size 1 (1 entries/node) but got the size of "
                + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onServer() execute size 1 (1 entries/node)");
      }

      ds = FunctionService.onServers(pool).withArgs("Args");
      list.clear();

      scenario = "onServers(pool) function execution with args and with default result collector \n";

      try {
        list = (ArrayList)ds.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {

            Log.getLogWriter().info("Invoking execute in Inline function");

            ArrayList list = new ArrayList();
            if (context.getArguments() == null) {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
              list.add(dc.getName());
            }
            else {
              DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
                  .getAnyInstance()).getConfig();
              list.add(dc.getCacheXmlFile());
            }
            context.getResultSender().lastResult(list);
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("Caught the exception during execute ", e);
      }

      int numOfServers = TestConfig.tab().intAt(
          parReg.ParRegPrms.numberOfDataStore);

      if (list.size() != numOfServers) {
        throw new TestException("For the scenario "+scenario+
            " Expected the result of onServer() execute to be of number servers "
                + numOfServers
                + " but got the size of " + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onServer() execute size to be number of  servers "
                    + ((PoolImpl)pool).getCurrentServerNames().size());
      }

    }

  }

  /**
   * Task to execute function on server(by client) without args and without result collector.
   */
  public static void serverExecutionWOArgsWOCollector() {

    Execution ds1 = null;
    Execution ds2 = null;
    if (isBridgeConfiguration) {
      Pool pool = PoolHelper.getPool("edgeDescript");
      if (pool == null) {
        Log.getLogWriter().info("Pool is null");
        pool = PoolHelper.createPool("edgeDescript");
      }
      ds1 = FunctionService.onServer(pool);
      ArrayList list;

      String scenario = "onServer(pool) function execution without args and with default result collector \n";

      try {
        list = (ArrayList)ds1.execute(prFunction1.getId()).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      if (list.size() != 2) {
        throw new TestException(
            "For the scenario "
                + scenario
                + " Expected the result of onServer() execute to be of size 2 (1 node, 2 entries/node) but got the size of "
                + list.size());
      }
      else {
        Log.getLogWriter().info(
            "Received the expected result of onServer() execute size 1");
      }

      ds2 = FunctionService.onServers(pool);
      list.clear();

      scenario = "onServers(pool) function execution without args and with default result collector \n";

      try {
        list = (ArrayList)ds2.execute(prFunction1.getId()).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      int numOfServers = TestConfig.tab().intAt(
          parReg.ParRegPrms.numberOfDataStore);

      if (list.size() != 2 * numOfServers) {
        throw new TestException(
            "For the scenario "
                + scenario
                + " Expected the result of onServer() execute to be of size of number of servers "
                + numOfServers * 2 + " but got the size of " + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onServer() execute size to be the number of servers "
                    + ((PoolImpl)pool).getCurrentServerNames().size() * 2);
      }
    }

  }

  /**
   * Task to execute function on a specific server group (by client).
   */
  public static void serverExecutionOnServerGroup() {

    Execution ds1 = null;
    Execution ds2 = null;
    if (isBridgeConfiguration) {
      Pool pool = PoolHelper.getPool("groupDescript");
      if (pool == null) {
        Log.getLogWriter().info("Pool is null");
        pool = PoolHelper.createPool("groupDescript");
      }
      ds1 = FunctionService.onServer(pool).withCollector(
          new ArrayListResultCollector());

      String scenario = "onServer(pool) function execution without args and with default result collector \n";

      ArrayList list;

      try {
        list = (ArrayList)ds1.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {
            ArrayList list = new ArrayList();
            list.add(RemoteTestModule.getMyVmid());
            context.getResultSender().lastResult(list);
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("For the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      if (list.size() != 1) {
        throw new TestException(
            "For the scenario "
                + scenario
                + "Expected the execution to happen only one node (Function sends one result from each executed node) but happened on nodes "
                + list);
      }
      else {
        Log.getLogWriter().info(
            "Received the expected result of onServer() execute size 1");
      }

      for (Object nodeId : list) {
        if ((((Integer)nodeId).intValue() % 2) == 0) {
          throw new TestException(
              "Execution with server group named groups should not have executed on the server vm_"
                  + nodeId + " which is not in that server group");
        }
      }

      ds2 = FunctionService.onServers(pool).withCollector(
          new ArrayListResultCollector());

      scenario = "onServers(pool) function execution without args and with default result collector \n";

      list.clear();

      try {
        list = (ArrayList)ds2.execute(new FunctionAdapter() {

          public void execute(FunctionContext context) {
            ArrayList list = new ArrayList();
            list.add(RemoteTestModule.getMyVmid());
            context.getResultSender().lastResult(list);
          }

          public String getId() {
            return "" + hashCode();
          }

          public boolean hasResult() {
            return true;
          }

        }).getResult();
      }
      catch (Exception e) {
        throw new TestException("During the scenario " + scenario
            + " Caught the exception during execute ", e);
      }

      Log.getLogWriter().info("List is " + list);

      int numOfServers = TestConfig.tab().intAt(
          parReg.ParRegPrms.numberOfDataStore) / 2; // Half of them is on group

      if (list.size() != numOfServers) {
        throw new TestException(
            "For the scenario "
                + scenario
                + " Expected the result of onServer() execute to be of size of number of servers of group "
                + numOfServers + " but got the size of " + list.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected result of onServer() execute size to be the number of servers of group "
                    + numOfServers);
      }

      for (Object nodeId : list) {
        if ((((Integer)nodeId).intValue() % 2) == 0) {
          throw new TestException(
              "Execution with server group named groups should not have executed on the server vm_"
                  + nodeId + " which is not present in the group");
        }
      }

    }

  }

  /**
   * Task used to validate the results of the random function results
   * passed as result collectors through the map
   * 
   */
  public static void validateRandomFunctions(HashMap map) {
    if (map == null) {
      throw new TestException("Map cannot be null");
    }

    Iterator iterator = map.entrySet().iterator();
    Map.Entry entry = null;
    Execution dataSet = null;
    ArrayList list = null;

    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      dataSet = (Execution)entry.getKey();
      list = (ArrayList)entry.getValue();
      validateResults(dataSet, list);
    }
  }

  /**
   * Task that validates the results of each data set function results
   * passed through the list of result collectors.
   * 
   */
  public static void validateResults(Execution dataSet, ArrayList list) {
    if (list.size() != 4) {
      throw new TestException("List size != 4");
    }
    else {
      Log.getLogWriter().info("Received the expected size for the list " + 4);
    }

    validateFunctionResults(dataSet, (ResultCollector)list.get(0), "prFunction");
    validateFunctionResults(dataSet, (ResultCollector)list.get(1), "prFunction");
    validateFunctionResults(dataSet, (ResultCollector)list.get(2),
        "regionCheckFunction");
    validateFunctionResults(dataSet, (ResultCollector)list.get(3),
        "regionCheckFunction");
  }

  /**
   * Task that validates the results of each result collector result for
   * the particular function
   * 
   */
  public static void validateFunctionResults(Execution dataSet,
      ResultCollector rc, String function) {
    if (function.equalsIgnoreCase("prFunction")) {
      validatePrFunction(dataSet, rc);
    }
    else if (function.equalsIgnoreCase("regionCheckFunction")) {
      validateRegionCheckFunction(dataSet, rc);
    }
  }

  public static void validatePrFunction(Execution dataSet, ResultCollector rc) {
    Log.getLogWriter().info("Inside validation: prFunction");

    String scenario;
    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() != null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter and arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validatePrFuncWithFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() != null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter, but using Custom ResultCollector (ArrayListResultCollector) and args \n";
        validatePrFuncNoFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() == null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter and any arguments, but using Custom ResultCollector (ArrayListResultCollector) \n";
        validatePrFuncNoFilterNoArgsWithRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() != null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter but passing arguments and using default result collector. \n";
        validatePrFuncNoFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() == null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter & arguments and using default result collector. \n";
        validatePrFuncNoFilterNoArgsNoRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() == null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter but with no arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validatePrFuncWithFilterNoArgsWithRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() != null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter and arguments but using default result collector \n";
        validatePrFuncWithFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() == null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter but using no arguments and using default result collector \n";
        validatePrFuncWithFilterNoArgsNoRC(dataSet, rc, scenario);
      }
    }
    else if (dataSet instanceof ServerRegionFunctionExecutor) {

      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() != null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter and arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validatePrFuncWithFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() != null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter, but using Custom ResultCollector (ArrayListResultCollector) and args \n";
        validatePrFuncNoFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() == null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter and any arguments, but using Custom ResultCollector (ArrayListResultCollector) \n";
        validatePrFuncNoFilterNoArgsWithRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() != null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter but passing arguments and using default result collector. \n";
        validatePrFuncNoFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() == null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter & arguments and using default result collector. \n";
        validatePrFuncNoFilterNoArgsNoRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() == null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter but with no arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validatePrFuncWithFilterNoArgsWithRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() != null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter and arguments but using default result collector \n";
        validatePrFuncWithFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() == null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter but using no arguments and using default result collector \n";
        validatePrFuncWithFilterNoArgsNoRC(dataSet, rc, scenario);
      }

    }
  }

  public static void validatePrFuncNoFilterWithArgsWithRC(Execution dataSet,
      ResultCollector rc, String scenario) {

    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }

    int listSize = list.size();

    int numOfNodes = ((Integer)parReg.ParRegBB.getBB().getSharedMap().get(
        PR_TOTAL_DATASTORES)).intValue();

    int nodesExecuted = listSize / 2;

    if (!(nodesExecuted <= numOfNodes && nodesExecuted > 0)) {
      throw new TestException(
          " For the scenario "
              + scenario
              + " Function should have executed atleast on one node and max on "
              + numOfNodes
              + " nodes (max no of datastores)"
              + numOfNodes
              + "but executed on "
              + nodesExecuted
              + " (the function FirstRandomFunction sends two results per executed node for the scenario).");

    }

    Iterator listItr = list.iterator();
    while (listItr.hasNext()) {
      Log.getLogWriter().info(listItr.next().toString());
    }

  }

  public static void validatePrFuncNoFilterNoArgsWithRC(Execution dataSet,
      ResultCollector rc, String scenario) {
    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }
    int listSize = list.size();

    int numOfNodes = ((Integer)parReg.ParRegBB.getBB().getSharedMap().get(
        PR_TOTAL_DATASTORES)).intValue();

    int nodesExecuted = listSize / 4;

    if (!(nodesExecuted <= numOfNodes && nodesExecuted > 0)) {
      throw new TestException(
          " For the scenario "
              + scenario
              + " Function should have executed atleast on one node and max on "
              + numOfNodes
              + " nodes (max no of datastores)"
              + numOfNodes
              + "but executed on "
              + nodesExecuted
              + " (the function FirstRandomFunction sends two results per executed node for the scenario).");

    }

    Iterator listItr = list.iterator();
    while (listItr.hasNext()) {
      Log.getLogWriter().info(listItr.next().toString());
    }
  }

  public static void validatePrFuncWithFilterNoArgsWithRC(Execution dataSet,
      ResultCollector rc, String scenario) {

    Set keySet;

    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      keySet = regionDataSet.getFilter();
    }
    else {
      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      keySet = serverDataSet.getFilter();
    }

    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }
    int listSize = list.size();

    HashSet keySetHashCodes = new HashSet();
    Iterator keySetItr = keySet.iterator();
    while (keySetItr.hasNext()) {
      Object key = keySetItr.next();
      keySetHashCodes.add(((Month)InitImageBB.getBB().getSharedMap().get(key))
          .toString());
    }
    Log.getLogWriter().info(
        "Partition resolvers for the keySet : " + keySetHashCodes.toString());

    int minExpectedListSize = 4;
    int maxExpectedListSize = 4 * keySetHashCodes.size();
    if (listSize >= minExpectedListSize && listSize <= maxExpectedListSize) {
      Log.getLogWriter().info(
          "Got the expected list size as " + listSize
              + " which is between minSize " + minExpectedListSize
              + " and maxSize " + maxExpectedListSize);
    }
    else {
      throw new TestException(
          " For the scenario "
              + scenario
              + " the function FirstRandomFunction sends 4 results per executed node, hence "
              + " expected the list size to be between " + minExpectedListSize
              + " and " + maxExpectedListSize + " but got " + listSize);
    }
    Iterator listItr = list.iterator();
    while (listItr.hasNext()) {
      Log.getLogWriter().info(listItr.next().toString());
    }
  }

  public static void validatePrFuncWithFilterWithArgsWithRC(Execution dataSet,
      ResultCollector rc, String scenario) {

    Set keySet;

    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      keySet = regionDataSet.getFilter();
    }
    else {
      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      keySet = serverDataSet.getFilter();
    }
    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }
    int listSize = list.size();

    HashSet keySetHashCodes = new HashSet();
    Iterator keySetItr = keySet.iterator();
    while (keySetItr.hasNext()) {
      Object key = keySetItr.next();
      keySetHashCodes.add(((Month)InitImageBB.getBB().getSharedMap().get(key))
          .toString());
    }
    Log.getLogWriter().info(
        "Partition resolvers for the keySet : " + keySetHashCodes.toString());

    int minExpectedListSize = 2;
    int maxExpectedListSize = 2 * keySetHashCodes.size();
    if (listSize >= minExpectedListSize && listSize <= maxExpectedListSize) {
      Log.getLogWriter().info(
          "Got the expected list size as " + listSize
              + " which is between minSize " + minExpectedListSize
              + " and maxSize " + maxExpectedListSize);
    }
    else {
      throw new TestException(
          "During the scenario "
              + scenario
              + " the function FirstRandomFunction sends two results per node where the function got executed, hence "
              + " Expected the list size to be between " + minExpectedListSize
              + "( 2 * min of 1 node) and " + maxExpectedListSize
              + " (2 * max num of buckets - worst scenario) but got "
              + listSize);
    }
    Iterator listItr = list.iterator();
    while (listItr.hasNext()) {
      Log.getLogWriter().info(listItr.next().toString());
    }
  }

  public static void validatePrFuncNoFilterWithArgsNoRC(Execution dataSet,
      ResultCollector rc, String scenario) {
    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }

    int listSize = list.size();
    Log.getLogWriter().info(
        "The number of nodes where this function was executed = " + listSize);

    int numOfNodes = ((Integer)parReg.ParRegBB.getBB().getSharedMap().get(
        PR_TOTAL_DATASTORES)).intValue();

    int nodesExecuted = listSize;

    if (!(nodesExecuted <= numOfNodes && nodesExecuted > 0)) {
      throw new TestException("During the scenario " + scenario
          + "Function should have executed atleast on one node and max on "
          + numOfNodes + " nodes ( max no. of datastores)" + numOfNodes
          + "but executed on " + nodesExecuted);

    }

    Iterator itrRcList = list.iterator();
    while (itrRcList.hasNext()) {
      Object listContent = itrRcList.next();
      if (!(listContent instanceof List)) {
        throw new TestException(
            "Expected DefaultResultCollector to return list of lists, but it is not the case");
      }

      ArrayList nodeList = (ArrayList)listContent;

      if (nodeList.size() != 2) {
        throw new TestException(
            "Expected the DefaultResultCollector list of lists, to have a size of 2, but received "
                + nodeList.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected list of lists of DefaultResultCollector : 2");
      }

      Iterator nodeListIterator = nodeList.iterator();

      while (nodeListIterator.hasNext()) {
        Log.getLogWriter().info(nodeListIterator.next().toString());
      }
    }
  }

  public static void validatePrFuncWithFilterWithArgsNoRC(Execution dataSet,
      ResultCollector rc, String scenario) {
    Set keySet;

    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      keySet = regionDataSet.getFilter();
    }
    else {
      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      keySet = serverDataSet.getFilter();
    }

    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }
    int listSize = list.size();
    Log.getLogWriter().info(
        "The number of nodes where this function was executed = " + listSize);

    HashSet keySetHashCodes = new HashSet();
    Iterator keySetItr = keySet.iterator();
    while (keySetItr.hasNext()) {
      Object key = keySetItr.next();
      keySetHashCodes.add(((Month)InitImageBB.getBB().getSharedMap().get(key))
          .toString());
    }
    Log.getLogWriter().info(
        "Partition resolvers for the keySet : " + keySetHashCodes.toString());

    int minExpectedListSize = 1;
    int maxExpectedListSize = keySetHashCodes.size();
    if (listSize >= minExpectedListSize && listSize <= maxExpectedListSize) {
      Log.getLogWriter().info(
          "Got the expected list size as " + listSize
              + " which is between minSize " + minExpectedListSize
              + " and maxSize " + maxExpectedListSize);
    }
    else {
      throw new TestException("Expected the list size to be between "
          + minExpectedListSize + " and " + maxExpectedListSize + " but got "
          + listSize);
    }

    Iterator itrRcList = list.iterator();
    while (itrRcList.hasNext()) {
      Object listContent = itrRcList.next();
      if (!(listContent instanceof List)) {
        throw new TestException(
            "Expected DefaultResultCollector to return list of lists, but it is not the case");
      }

      ArrayList nodeList = (ArrayList)listContent;

      if (nodeList.size() != 2) {
        throw new TestException(
            "Expected the DefaultResultCollector list of lists, to have a size of 2, but received "
                + nodeList.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected list of lists of DefaultResultCollector : 2");
      }

      Iterator nodeListIterator = nodeList.iterator();

      while (nodeListIterator.hasNext()) {
        Log.getLogWriter().info(nodeListIterator.next().toString());
      }
    }
  }

  public static void validatePrFuncNoFilterNoArgsNoRC(Execution dataSet,
      ResultCollector rc, String scenario) {
    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }
    int listSize = list.size();
    Log.getLogWriter().info(
        "The number of buckets where this function was executed = " + listSize);

    int numOfNodes = ((Integer)parReg.ParRegBB.getBB().getSharedMap().get(
        PR_TOTAL_DATASTORES)).intValue();

    int nodesExecuted = listSize;

    if (!(nodesExecuted <= numOfNodes && nodesExecuted > 0)) {
      throw new TestException(" For the scenario " + scenario
          + "Function should have executed atleast on one node and max on "
          + numOfNodes + " nodes (max no of datastores)" + numOfNodes
          + "but executed on " + nodesExecuted);

    }

    Iterator itrRcList = list.iterator();
    while (itrRcList.hasNext()) {
      Object listContent = itrRcList.next();
      if (!(listContent instanceof List)) {
        throw new TestException(
            "Expected DefaultResultCollector to return list of lists, but it is not the case");
      }

      ArrayList nodeList = (ArrayList)listContent;

      if (nodeList.size() != 4) {
        throw new TestException(
            "Expected the DefaultResultCollector list of lists, to have a size of 4, but received "
                + nodeList.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected list of lists of DefaultResultCollector : 4");
      }

      Iterator nodeListIterator = nodeList.iterator();

      while (nodeListIterator.hasNext()) {
        Log.getLogWriter().info(nodeListIterator.next().toString());
      }
    }
  }

  public static void validatePrFuncWithFilterNoArgsNoRC(Execution dataSet,
      ResultCollector rc, String scenario) {

    Set keySet;

    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      keySet = regionDataSet.getFilter();
    }
    else {
      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      keySet = serverDataSet.getFilter();
    }

    ArrayList list = null;
    try {
      list = (ArrayList)(rc).getResult(120, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("Caught exception ", e);
    }
    int listSize = list.size();
    Log.getLogWriter().info(
        "The number of nodes where this function was executed = " + listSize);
    HashSet keySetHashCodes = new HashSet();
    Iterator keySetItr = keySet.iterator();
    while (keySetItr.hasNext()) {
      Object key = keySetItr.next();
      keySetHashCodes.add(((Month)InitImageBB.getBB().getSharedMap().get(key))
          .toString());
    }
    Log.getLogWriter().info(
        "Partition resolvers for the keySet : " + keySetHashCodes.toString());

    int minExpectedListSize = 1;
    int maxExpectedListSize = keySetHashCodes.size();
    if (listSize >= minExpectedListSize && listSize <= maxExpectedListSize) {
      Log.getLogWriter().info(
          "Got the expected list size as " + listSize
              + " which is between minSize " + minExpectedListSize
              + " and maxSize " + maxExpectedListSize);
    }
    else {
      throw new TestException("Expected the list size to be between "
          + minExpectedListSize + " and " + maxExpectedListSize + " but got "
          + listSize);
    }

    Iterator itrRcList = list.iterator();
    while (itrRcList.hasNext()) {
      Object listContent = itrRcList.next();
      if (!(listContent instanceof List)) {
        throw new TestException(
            "Expected DefaultResultCollector to return list of lists, but it is not the case");
      }

      ArrayList nodeList = (ArrayList)listContent;

      if (nodeList.size() != 4) {
        throw new TestException(
            "Expected the DefaultResultCollector list of lists, to have a size of 4, but received "
                + nodeList.size());
      }
      else {
        Log
            .getLogWriter()
            .info(
                "Received the expected list of lists of DefaultResultCollector : 4");
      }

      Iterator nodeListIterator = nodeList.iterator();

      while (nodeListIterator.hasNext()) {
        Log.getLogWriter().info(nodeListIterator.next().toString());
      }
    }
  }

  public static void validateRegionCheckFunction(Execution dataSet,
      ResultCollector rc) {
    Log.getLogWriter().info("Inside validation: regionCheckFunction");
    String scenario;
    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() != null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter and arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validateRegWithFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() != null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter, but using Custom ResultCollector (ArrayListResultCollector) and args \n";
        validateRegNoFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() == null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter and any arguments, but using Custom ResultCollector (ArrayListResultCollector) \n";
        validateRegNoFilterWithArgsWithRC(dataSet, rc, scenario); // Validation mechanism
        // With args can be used
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() != null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter but passing arguments and using default result collector. \n";
        validateRegNoFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() == 0
          && regionDataSet.getArguments() == null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter & arguments and using default result collector. \n";
        validateRegNoFilterWithArgsNoRC(dataSet, rc, scenario); // Validation mechanism
        // With args can be used
      }
      else if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() == null
          && regionDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter but with no arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validateRegWithFilterWithArgsWithRC(dataSet, rc, scenario); // Validation
        // mechanism With args
        // can be used
      }
      else if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() != null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter and arguments but using default result collector \n";
        validateRegWithFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (regionDataSet.getFilter().size() > 0
          && regionDataSet.getArguments() == null
          && !(regionDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter but using no arguments and using default result collector \n";
        validateRegWithFilterWithArgsNoRC(dataSet, rc, scenario); // Validation mechanism
        // With args can be used
      }
    }
    else if (dataSet instanceof ServerRegionFunctionExecutor) {

      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() != null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter and arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validateRegWithFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() != null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter, but using Custom ResultCollector (ArrayListResultCollector) and args \n";
        validateRegNoFilterWithArgsWithRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() == null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution without passing filter and any arguments, but using Custom ResultCollector (ArrayListResultCollector) \n";
        validateRegNoFilterWithArgsWithRC(dataSet, rc, scenario); // Validation mechanism
        // With args can be used
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() != null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter but passing arguments and using default result collector. \n";
        validateRegNoFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() == 0
          && serverDataSet.getArguments() == null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution without passing filter & arguments and using default result collector. \n";
        validateRegNoFilterWithArgsNoRC(dataSet, rc, scenario); // Validation mechanism
        // With args can be used
      }
      else if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() == null
          && serverDataSet.getResultCollector() instanceof ArrayListResultCollector) {
        scenario = "onRegion function execution by passing filter but with no arguments and also using Custom ResultCollector (ArrayListResultCollector) \n";
        validateRegWithFilterWithArgsWithRC(dataSet, rc, scenario); // Validation
        // mechanism With args
        // can be used
      }
      else if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() != null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter and arguments but using default result collector \n";
        validateRegWithFilterWithArgsNoRC(dataSet, rc, scenario);
      }
      else if (serverDataSet.getFilter().size() > 0
          && serverDataSet.getArguments() == null
          && !(serverDataSet.getResultCollector() instanceof ArrayListResultCollector)) {
        scenario = "onRegion function execution by passing filter but using no arguments and using default result collector \n";
        validateRegWithFilterWithArgsNoRC(dataSet, rc, scenario);// Validation mechanism
        // With args can be used
      }
    }
  }

  public static void validateRegNoFilterWithArgsNoRC(Execution dataSet,
      ResultCollector rc, String scenario) {
    ArrayList list = (ArrayList)(rc).getResult();
    int listSize = list.size();
    Log.getLogWriter().info(
        "The number of nodes where this function was executed = " + listSize);
    int numOfNodes = ((Integer)parReg.ParRegBB.getBB().getSharedMap().get(
        PR_TOTAL_DATASTORES)).intValue();

    int nodesExecuted = listSize;

    if (!(nodesExecuted <= numOfNodes && nodesExecuted > 0)) {
      throw new TestException(
          " For the scenario "
              + scenario
              + " Function (SecondRandomFunction) should have executed atleast on one node and max on "
              + numOfNodes + " nodes (datastore)" + numOfNodes
              + "but executed on " + nodesExecuted);

    }

    int contentListSize = 0;
    Iterator itrRcList = list.iterator();
    while (itrRcList.hasNext()) {
      Object listContent = itrRcList.next();
      if (!(listContent instanceof List)) {
        throw new TestException(
            "Expected DefaultResultCollector to return list of lists, but it is not the case");
      }
      contentListSize += ((List)listContent).size();
    }

    int expectedSize = 10 * nodesExecuted;

    if (contentListSize == expectedSize) {
      Log.getLogWriter().info(
          "Got the expected size for the list " + contentListSize);
    }
    else {
      throw new TestException(
          " For the scenario "
              + scenario
              + "the function (SecondRandomFunction) returns 10 results per node where the function got executed."
              + " Expected size of list " + expectedSize + " but received "
              + contentListSize);
    }
  }

  public static void validateRegWithFilterWithArgsNoRC(Execution dataSet,
      ResultCollector rc, String scenario) {

    Set keySet;
    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      keySet = regionDataSet.getFilter();
    }
    else {
      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      keySet = serverDataSet.getFilter();
    }

    ArrayList list = (ArrayList)(rc).getResult();
    int listSize = list.size();
    Log.getLogWriter().info(
        "The number of nodes where this function was executed = " + listSize);

    HashSet keySetHashCodes = new HashSet();
    Iterator keySetItr = keySet.iterator();
    while (keySetItr.hasNext()) {
      Object key = keySetItr.next();
      keySetHashCodes.add(((Month)InitImageBB.getBB().getSharedMap().get(key))
          .toString());
    }
    Log.getLogWriter().info(
        "Partition resolvers for the keySet : " + keySetHashCodes.toString());

    int minExpectedListSize = 1;
    int maxExpectedListSize = keySetHashCodes.size();
    if (listSize >= minExpectedListSize && listSize <= maxExpectedListSize) {
      Log.getLogWriter().info(
          "Got the expected list size as " + listSize
              + " which is between minSize " + minExpectedListSize
              + " and maxSize " + maxExpectedListSize);
    }
    else {
      throw new TestException(
          "During the scenario "
              + scenario
              + " the function (SecondRandomFunction) returns single result for every node that executed. Hence "
              + "Expected the list size to be between " + minExpectedListSize
              + " and " + maxExpectedListSize + " but got " + listSize);
    }

    int contentListSize = 0;
    Iterator itrRcList = list.iterator();
    while (itrRcList.hasNext()) {
      Object listContent = itrRcList.next();
      if (!(listContent instanceof List)) {
        throw new TestException(
            "Expected DefaultResultCollector to return list of lists, but it is not the case");
      }
      contentListSize += ((List)listContent).size();
    }

    if (contentListSize == keySet.size()) {
      Log.getLogWriter().info(
          "Got the expected size for the list " + contentListSize);
    }
    else {
      throw new TestException(
          "During the scenario "
              + scenario
              + " the function (SecondRandomFunction) returns single result for every item in the filter. "
              + " Expected size of list " + keySet.size() + " but received "
              + contentListSize);
    }

  }

  public static void validateRegWithFilterWithArgsWithRC(Execution dataSet,
      ResultCollector rc, String scenario) {

    Set keySet;
    if (dataSet instanceof PartitionedRegionFunctionExecutor) {
      PartitionedRegionFunctionExecutor regionDataSet = (PartitionedRegionFunctionExecutor)dataSet;
      keySet = regionDataSet.getFilter();
    }
    else {
      ServerRegionFunctionExecutor serverDataSet = (ServerRegionFunctionExecutor)dataSet;
      keySet = serverDataSet.getFilter();
    }

    ArrayList list = (ArrayList)(rc).getResult();
    int listSize = list.size();

    if (keySet.size() == listSize) {
      Log.getLogWriter().info(
          "Got the list Size to be the same as the size of the keySet size "
              + listSize);
    }
    else {
      throw new TestException(
          "During the scenario "
              + scenario
              + " the function (SecondRandomFunction) returns single result for every item in the filter. "
              + " Expected size of the list to be " + keySet.size()
              + " (filter size) but it is " + listSize);
    }

    Iterator listItr = list.iterator();
    while (listItr.hasNext()) {
      if (!(listItr.next() instanceof Boolean)) {
        throw new TestException(
            "During the scenario "
                + scenario
                + " the function (SecondRandomFunction) returns boolean results, hence "
                + "expected the objects in the list to be Boolean but it is not!");
      }
    }
  }

  public static void validateRegNoFilterWithArgsWithRC(Execution dataSet,
      ResultCollector rc, String scenario) {

    ArrayList list = (ArrayList)(rc).getResult();
    int listSize = list.size();
    int numOfNodes = ((Integer)parReg.ParRegBB.getBB().getSharedMap().get(
        PR_TOTAL_DATASTORES)).intValue();

    int nodesExecuted = listSize / 10;

    if (!(nodesExecuted <= numOfNodes && nodesExecuted > 0)) {
      throw new TestException(
          "During the scenario "
              + scenario
              + " Function (SecondRandomFunction) should have executed atleast on one node and max on "
              + numOfNodes + " nodes (number of datastores)" + numOfNodes
              + " but executed on " + nodesExecuted + " and result size is " + listSize);

    }

    Iterator listItr = list.iterator();
    while (listItr.hasNext()) {
      if (!(listItr.next() instanceof Boolean)) {
        throw new TestException(
            "During the scenario "
                + scenario
                + " the function (SecondRandomFunction) returns boolean results, hence "
                + "expected the objects in the result list to be Boolean but it is not!");
      }
    }
  }

  /**
   * 
   * Task used to execute the different functions on the dataset with the
   * result collector.
   */
  public static ArrayList executeAndReturnResults(Execution dataSet,
      ResultCollector rc) {
    Log.getLogWriter().info("Called execute function");
    ArrayList aList = new ArrayList();

    if (rc != null) {
      Log.getLogWriter().info("Executing on prFunction1");
      ResultCollector drc1 = randomFunctionExecution(dataSet
          .withCollector(new ArrayListResultCollector()),
          new FirstRandomFunction());
      aList.add(drc1);
      Log.getLogWriter().info("Executing on prFunction2");
      ResultCollector drc2 = randomFunctionExecution(dataSet
          .withCollector(new ArrayListResultCollector()),
          new FirstRandomFunction());
      aList.add(drc2);
      Log.getLogWriter().info("Executing on regionCheckFunction1");
      ResultCollector drc3 = randomFunctionExecution(dataSet
          .withCollector(new ArrayListResultCollector()),
          new SecondRandomFunction());
      aList.add(drc3);
      Log.getLogWriter().info("Executing on regionCheckFunction2");
      ResultCollector drc4 = randomFunctionExecution(dataSet
          .withCollector(new ArrayListResultCollector()),
          new SecondRandomFunction());
      aList.add(drc4);
    }
    else {
      Log.getLogWriter().info("Executing on prFunction1");
      ResultCollector drc1 = randomFunctionExecution(dataSet,
          new FirstRandomFunction());
      aList.add(drc1);
      Log.getLogWriter().info("Executing on prFunction2");
      ResultCollector drc2 = randomFunctionExecution(dataSet,
          new FirstRandomFunction());
      aList.add(drc2);
      Log.getLogWriter().info("Executing on regionCheckFunction1");
      ResultCollector drc3 = randomFunctionExecution(dataSet,
          new SecondRandomFunction());
      aList.add(drc3);
      Log.getLogWriter().info("Executing on regionCheckFunction2");
      ResultCollector drc4 = randomFunctionExecution(dataSet,
          new SecondRandomFunction());
      aList.add(drc4);

    }
    return aList;
  }

  /**
   * 
   * Task to execute functions
   */
  public static ResultCollector randomFunctionExecution(Execution dataSet,
      FunctionAdapter function) {
    try {
      Log.getLogWriter().info("Called final execute function");
      ResultCollector drc = dataSet.execute(function);
      // drc.getResult();
      return drc;
    }
    catch (Exception e) {
      throw new TestException("Got this exception during execute operation ", e);
    }
  }

  /**
   * 
   * Method to get different data sets for the random function execution
   */
  public Execution[] getDataSets(BitSet dataSetOptions, Region aRegion) {
    int whichOp = getOp(dataSetOptions, funcResultCollectors.length);

    Execution[] ds = null;

    if (aRegion instanceof PartitionedRegion) {
      try {
        switch (whichOp) {
          case FUNC_WITH_SINGLE_DATASET:
            ds = new Execution[1];
            Execution ds1 = FunctionService.onRegion(aRegion);
            ds[0] = ds1;
            break;
          case FUNC_WITH_MULTIPLE_DATASET:
            ds = new Execution[4];
            Execution ads1 = FunctionService.onRegion(aRegion);
            Execution ads2 = FunctionService.onRegion(aRegion);
            Execution ads3 = FunctionService.onRegion(aRegion);
            Execution ads4 = FunctionService.onRegion(aRegion);
            ds[0] = ads1;
            ds[1] = ads2;
            ds[2] = ads3;
            ds[3] = ads4;
            break;
          default: {
            throw new TestException("Test issue - Unknown operation " + whichOp);
          }
        }
      }
      catch (Exception e) {
        throw new TestException("Test issue - Caught Exception ", e);
      }
    }
    else {
      try {
        switch (whichOp) {
          case FUNC_WITH_SINGLE_DATASET:
            ds = new Execution[1];
            Execution ds1 = FunctionService.onRegion(aRegion);
            ds[0] = ds1;
            break;
          case FUNC_WITH_MULTIPLE_DATASET:
            ds = new Execution[4];
            Execution ads1 = FunctionService.onRegion(aRegion);
            Execution ads2 = FunctionService.onRegion(aRegion);
            Execution ads3 = FunctionService.onRegion(aRegion);
            Execution ads4 = FunctionService.onRegion(aRegion);
            ds[0] = ads1;
            ds[1] = ads2;
            ds[2] = ads3;
            ds[3] = ads4;
            break;
          default: {
            throw new TestException("Test issue - Unknown operation " + whichOp);
          }
        }
      }
      catch (Exception e) {
        throw new TestException("Test issue - Caught Exception ", e);
      }
    }
    return ds;
  }

  /**
   * Method to get different result collectors for the random function execution
   *
   */
  public ResultCollector[] getResultCollectors(BitSet resultCollectorOptions) {
    int whichOp = getOp(resultCollectorOptions, funcResultCollectors.length);
    ResultCollector[] rc = null;

    try {
      switch (whichOp) {
        case FUNC_WITHOUT_COLLECTOR:
          break;
        case FUNC_WITH_CUSTOM_COLLECTOR:
          rc = new ResultCollector[1];
          ArrayListResultCollector arc = new ArrayListResultCollector();
          rc[0] = arc;
          break;
        // case FUNC_WITH_INDIVIDUAL_COLLECTOR:
        // rc = new ResultCollector[4];
        // for (int i = 0; i < rc.length; i++) {
        // rc[i] = new ArrayListResultCollector();
        // }
        // break;
        default: {
          throw new TestException("Test issue - Unknown operation " + whichOp);
        }
      }
    }
    catch (Exception e) {
      throw new TestException("Test issue - Caught Exception ", e);
    }
    return rc;
  }

  public String getArgs(BitSet argsOptions) {
    int whichOp = getOp(argsOptions, funcArgs.length);
    String args = null;

    try {
      switch (whichOp) {
        case FUNC_WITHOUT_ARGS:
          break;
        case FUNC_WITH_ARGS:
          args = "With Args";
          break;
        default: {
          throw new TestException("Test issue - Unknown operation " + whichOp);
        }
      }
    }
    catch (Exception e) {
      throw new TestException("Test issue - Caught Exception ", e);
    }
    return args;
  }

  /**
   * 
   * Method to get random key set for the random function execution
   * tests
   */
  public HashSet getKeySet(BitSet keyOptions, Set keys) {
    HashSet keySet = null;
    Object[] keyArray = keys.toArray();
    int whichOp = getOp(keyOptions, funcKeys.length);
    int length = 0;
    int randInt = 0;
    try {
      switch (whichOp) {
        case FUNC_SINGLEKEY:
          length = keyArray.length;
          randInt = TestConfig.tab().getRandGen().nextInt(0, length - 1);
          keySet = new HashSet();
          keySet.add(keyArray[randInt]);
          break;
        case FUNC_MULTIPLEKEY:
          length = keyArray.length;
          keySet = new HashSet();
          keySet.addAll(keys);
          break;
        case FUNC_ALLNODES:
          break;
        default: {
          throw new TestException("Test issue - Unknown operation " + whichOp);
        }
      }
    }
    catch (Exception e) {
      throw new TestException("Test Issue - Caught Exception ", e);
    }
    return keySet;
  }

  /**
   * Task to do Fire and forget member execution
   */
  public static void doFireAndForgetMemberExecutions() {

    DistributedSystem ds = theCache.getDistributedSystem();

    InternalDistributedMember localVM = ((InternalDistributedSystem)ds)
        .getDistributionManager().getDistributionManagerId();

    Execution dsExecution1 = FunctionService.onMembers(ds).withArgs("Args")
        .withCollector(new ArrayListResultCollector());

    ResultCollector resultCollector;

    try {
      resultCollector = dsExecution1.execute(new FunctionAdapter() {
        public void execute(FunctionContext context) {
          Log.getLogWriter().info(
              "Invoking fire and forget onMembers() function");
          ArrayList list = new ArrayList();
          DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
              .getAnyInstance()).getConfig();
          list.add(dc.getCacheXmlFile());
          Log.getLogWriter().info("Updating the BB list");
          InitImageBB.getBB().getSharedMap().put("List", list);
        }

        public String getId() {
          return "" + hashCode();
        }

        public boolean hasResult() {
          return false;
        }

        public boolean isHA() {
          return false;
        }
      });
    }
    catch (Exception e) {
      throw new TestException(
          "Caught the exception during fire and forget onMembers function execution ",
          e);
    }

    // Verifying that resultCollector.getResult() throws exception when
    // has result is false
    try {
      resultCollector.getResult();
      throw new TestException(
          "ResultCollector.getResult() did not throw FunctionException when Function.hasResult() is false");
    }
    catch (FunctionException e) {
      String errorMessage = e.getMessage();

      if (!errorMessage.contains("result as the Function#hasResult() is false")) {
        throw new TestException("Caught exception ", e);
      }
    }
    catch (Exception e) {
      throw new TestException(
          "ResultCollector.getResult() expected to throw FunctionException as Function#hasResult() is false");
    }

    Execution dsExecution2 = FunctionService.onMember(ds, localVM);

    try {
      dsExecution2.execute(new FunctionAdapter() {

        public void execute(FunctionContext context) {

          Log.getLogWriter().info(
              "Invoking fire and forget onMember() function");
          ArrayList list = new ArrayList();
          DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
              .getAnyInstance()).getConfig();
          list.add(dc.getCacheXmlFile());
          Log.getLogWriter().info("Updating the BB list");
          InitImageBB.getBB().getSharedMap().put("List", list);
        }

        public String getId() {
          return "" + hashCode();
        }

        public boolean hasResult() {
          return false;
        }
        public boolean isHA() {
          return false;
        }
      });
    }
    catch (Exception e) {
      throw new TestException(
          "Caught the exception during fire and forget onMember execute " , e);
    }

  }

  /**
   * Task to do fire and forget server executions by client
   */
  public static void doFireAndForgetServerExecutions() {
    Pool pool = PoolHelper.getPool("edgeDescript");
    if (pool == null) {
      Log.getLogWriter().info("Pool is null");
      pool = PoolHelper.createPool("edgeDescript");
    }
    Execution ds = FunctionService.onServer(pool).withArgs("Args")
        .withCollector(new ArrayListResultCollector());

    ResultCollector resultCollector;

    try {
      resultCollector = ds.execute(new FunctionAdapter() {
        public void execute(FunctionContext context) {
          Log.getLogWriter().info(
              "Invoking fire and forget onServer() function");
          ArrayList list = new ArrayList();
          DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
              .getAnyInstance()).getConfig();
          list.add(dc.getCacheXmlFile());
          Log.getLogWriter().info("Updating the BB list");
          InitImageBB.getBB().getSharedMap().put("List", list);
        }

        public String getId() {
          return "" + hashCode();
        }

        public boolean hasResult() {
          return false;
        }
        public boolean isHA() {
          return false;
        }
      });
    }
    catch (Exception e) {
      throw new TestException(
          "Caught the exception during fire and forget onServer execute " , e);
    }

    // Verifying that resultCollector.getResult() throws exception when
    // has result is false
    try {
      resultCollector.getResult();
      throw new TestException(
          "ResultCollector.getResult() did not throw FunctionException when Function.hasResult() is false");
    }
    catch (FunctionException e) {
      String errorMessage = e.getMessage();

      if (!errorMessage.contains("result as the Function#hasResult() is false")) {
        throw new TestException("Caught exception ", e);
      }
    }
    catch (Exception e) {
      throw new TestException(
          "ResultCollector.getResult() expected to throw FunctionException as Function.hasResult() is false");
    }

    Execution ds1 = FunctionService.onServers(pool);

    try {
      ds1.execute(new FunctionAdapter() {

        public void execute(FunctionContext context) {

          Log.getLogWriter().info(
              "Invoking fire and forget onServers() function");
          ArrayList list = new ArrayList();
          DistributionConfig dc = ((InternalDistributedSystem)InternalDistributedSystem
              .getAnyInstance()).getConfig();
          list.add(dc.getCacheXmlFile());
          Log.getLogWriter().info("Updating the BB list");
          InitImageBB.getBB().getSharedMap().put("List", list);
        }

        public String getId() {
          return "" + hashCode();
        }

        public boolean hasResult() {
          return false;
        }
        public boolean isHA() {
          return false;
        }
      });
    }
    catch (Exception e) {
      throw new TestException(
          "Caught the exception during fire and forget onServers function execution ",
          e);
    }
  }

  /**
   * Task to do fire and forget region function executions.
   */
  public static void doFireAndForgetRegionExecutions() {
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        Execution dataSet = FunctionService.onRegion(aRegion);
        Object key = NameFactory.getNextPositiveObjectName();
        Month callBackArg = Month.months[TestConfig.tab().getRandGen().nextInt(
            11)];
        Log.getLogWriter().info(
            "Callback arg is" + callBackArg.toString() + " which has hashCode "
                + callBackArg.hashCode());
        InitImageBB.getBB().getSharedMap().put(key, callBackArg);
        final Set keySet = new HashSet();
        keySet.add(key);

        try {
          dataSet.withFilter(keySet).execute(new FunctionAdapter() {
            public void execute(FunctionContext context) {
              if (context instanceof RegionFunctionContext) {
                RegionFunctionContext prContext = (RegionFunctionContext)context;
                Log
                    .getLogWriter()
                    .info(
                        "Inside FireAndForget PartitionedRegionFunctionContext execute");
                Set keySet = prContext.getFilter();
                Log.getLogWriter().info(
                    "got the filer set " + keySet.toString());
                PartitionedRegion pr = (PartitionedRegion)prContext
                    .getDataSet();

                Iterator iterator = keySet.iterator();
                while (iterator.hasNext()) {
                  Object key = iterator.next();
                  RandomValues randomValues = new RandomValues();
                  Object value = new ValueHolder((String)key, randomValues);
                  pr.put(key, value);
                  Log.getLogWriter().info("Did put using execute..");
                }
              }
            }

            public String getId() {
              return "" + hashCode();
            }

            public boolean hasResult() {
              return false;
            }
            public boolean isHA() {
              return false;
            }
            public boolean optimizeForWrite() {
              return true;
            }

          });

        }
        catch (Exception e) {
          throw new TestException(
              "Caught the exception during fire and forget onRegion function execution ",
              e);
        }

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);

        Execution dataSet = FunctionService.onRegion(aRegion);
        Object key1 = NameFactory.getNextPositiveObjectName();
        Object key2 = NameFactory.getNextPositiveObjectName();
        Object key3 = NameFactory.getNextPositiveObjectName();
        Object key4 = NameFactory.getNextPositiveObjectName();

        Month callBackArg1 = Month.months[TestConfig.tab().getRandGen()
            .nextInt(11)];
        Month callBackArg2 = Month.months[TestConfig.tab().getRandGen()
            .nextInt(11)];
        Month callBackArg3 = Month.months[TestConfig.tab().getRandGen()
            .nextInt(11)];
        Month callBackArg4 = Month.months[TestConfig.tab().getRandGen()
            .nextInt(11)];

        InitImageBB.getBB().getSharedMap().put(key1, callBackArg1);
        InitImageBB.getBB().getSharedMap().put(key2, callBackArg2);
        InitImageBB.getBB().getSharedMap().put(key3, callBackArg3);
        InitImageBB.getBB().getSharedMap().put(key4, callBackArg4);

        final Set keySet = new HashSet();
        keySet.add(key1);
        keySet.add(key2);
        keySet.add(key3);
        keySet.add(key4);

        try {
          ResultCollector rc = dataSet.withFilter(keySet).withArgs("Args")
              .withCollector(new ArrayListResultCollector()).execute(
                  new FunctionAdapter() {
                    public void execute(FunctionContext context) {
                      Log.getLogWriter().info("Inside FireNForget Execute...");
                      if (context instanceof RegionFunctionContext) {
                        RegionFunctionContext prContext = (RegionFunctionContext)context;
                        Log
                            .getLogWriter()
                            .info(
                                "Inside FireAndForget PartitionedRegionFunctionContext execute");
                        Set keySet = prContext.getFilter();
                        Log.getLogWriter().info(
                            "got the filer set " + keySet.toString());
                        Region pr = prContext.getDataSet();

                        Iterator iterator = keySet.iterator();
                        while (iterator.hasNext()) {
                          Object key = iterator.next();
                          RandomValues randomValues = new RandomValues();
                          Object value = new ValueHolder((String)key,
                              randomValues);
                          pr.put(key, value);
                          Log.getLogWriter().info("Did put using execute..");
                        }
                      }
                    }

                    public String getId() {
                      return "" + hashCode();
                    }

                    public boolean hasResult() {
                      return false;
                    }

                    public boolean optimizeForWrite() {
                      return true;
                    }

                    public boolean isHA() {
                      return false;
                    }

                  });

        }
        catch (Exception e) {
          throw new TestException(
              "Caught the exception during fire and forget onRegion function execution ",
              e);
        }

      }
  }

  /**
   * Task for doing onRegions() executions
   */
  public void doOnRegionsExecutions() {
    String regionDescriptName = null;
    HashSet regionSet = new HashSet();
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        regionSet.add(aRegion);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        regionSet.add(aRegion);
      }
    doOnRegionsExecutions(regionSet);
  }
  
  /**
   * Task for doing onRegions() executions with HA scenario
   */
  public void doOnRegionsExecutionsHA() {
    String regionDescriptName = null;
    HashSet regionSet = new HashSet();
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        regionSet.add(aRegion);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        regionSet.add(aRegion);
      }
    doOnRegionsExecutionsHA(regionSet);
  }

  /**
   * Do function execution to do operations on the REGION_NAME's keys using
   * keyIntervals to specify which keys get which operations. This will return
   * when all operations in all intervals have completed.
   * 
   * @param availableOps -
   *                Bits which are true correspond to the operations that should
   *                be executed.
   */
  public void doFunctionExecution(BitSet availableOps) {
    long minTaskGranularitySec = TestConfig.tab().longAt(
        TestHelperPrms.minTaskGranularitySec);
    long minTaskGranularityMS = minTaskGranularitySec
        * TestHelper.SEC_MILLI_FACTOR;
    long startTime = System.currentTimeMillis();
    // useTransactions() defaults to false
    boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
    if (useTransactions) {
       // TODO: TX: need to redo with new TX impl
       //TxHelper.recordClientTXOperations();
    }

    boolean rolledback;

    while ((availableOps.cardinality() != 0)
        && (System.currentTimeMillis() - startTime < minTaskGranularityMS)) {
      int whichOp = getOp(availableOps, operations.length);
      boolean doneWithOps = false;

      rolledback = false;
      if (useTransactions) {
        TxHelper.begin();
      }

      try {
        switch (whichOp) {
          case ADD_NEW_KEY:
            doneWithOps = addNewKeyFunction();
            break;
          case INVALIDATE:
            doneWithOps = invalidateFunction();
            break;
          case DESTROY:
            doneWithOps = destroyFunction();
            break;
          case UPDATE_EXISTING_KEY:
            doneWithOps = updateExistingKeyFunction();
            break;
          case GET:
            doneWithOps = getFunction();
            break;
          case QUERY:
            doneWithOps = queryFunction();
            break;
          case LOCAL_INVALIDATE:
            doneWithOps = localInvalidateFunction();
            break;
          case LOCAL_DESTROY:
            doneWithOps = localDestroyFunction();
            break;
          default: {
            throw new TestException("Unknown operation " + whichOp);
          }
        }
      } catch (TransactionDataNodeHasDepartedException e) {
         if (!useTransactions) {
            throw new TestException("Unexpected Exception " + e + ".  " + TestHelper.getStackTrace(e));
         } else {
            Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
            recordFailedOps(ParRegBB.FAILED_TXOPS);
            Log.getLogWriter().info("Rolling back transaction.");
            try {
               TxHelper.rollback();
               Log.getLogWriter().info("Done Rolling back Transaction");
            } catch (TransactionException te) {
               Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching Exception " + e + " during tx ops.  Expected, continuing test.");
          }
          rolledback = true;
        }
      } catch (TransactionDataRebalancedException e) {
         if (!useTransactions) {
            throw new TestException("Unexpected Exception " + e + ".  " + TestHelper.getStackTrace(e));
         } else {
            Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
            recordFailedOps(ParRegBB.FAILED_TXOPS);
            Log.getLogWriter().info("Rolling back transaction.");
            try {
               TxHelper.rollback();
               Log.getLogWriter().info("Done Rolling back Transaction");
            } catch (TransactionException te) {
               Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching Exception " + e + " during tx ops.  Expected, continuing test.");
          }
          rolledback = true;
        }
      } catch (FunctionException e) {
         // Execeptions can come back wrapped as FunctionExceptions
         Throwable causedBy = e.getCause();

         if ((causedBy instanceof TransactionDataNodeHasDepartedException) ||
             (causedBy instanceof TransactionDataRebalancedException)) {
             if (!useTransactions) {
                throw new TestException("Unexpected Exception " + e + ".  " + TestHelper.getStackTrace(e));
             } else {
                Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
                recordFailedOps(ParRegBB.FAILED_TXOPS);
                Log.getLogWriter().info("Rolling back transaction.");
                try {
                   TxHelper.rollback();
                   Log.getLogWriter().info("Done Rolling back Transaction");
                } catch (TransactionException te) {
                   Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching Exception " + e + " during tx ops.  Expected, continuing test.");
              }
              rolledback = true;
            }
         } else {
            // with one op per tx, we should not expect TransactionDataNotColocatedExcpetions
            // or anything else for that matter
            throw new TestException("Unexpected " + e + " " + TestHelper.getStackTrace(e));
         } 
      } catch (Exception e) {
         // with one op per tx, we should not expect TransactionDataNotColocatedExcpetions
         // or anything else for that matter
         throw new TestException("Unexpected " + e + " " + TestHelper.getStackTrace(e));
      } 

      if (useTransactions && !rolledback) {
        try {
          TxHelper.commit();
        } catch (TransactionDataNodeHasDepartedException e) {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.FAILED_TXOPS);
        } catch (TransactionDataRebalancedException e) {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.FAILED_TXOPS);
        } catch (TransactionInDoubtException e) {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.INDOUBT_TXOPS);
        } catch (ConflictException e) {
          // can occur with concurrent execution
          Log.getLogWriter().info("Caught ConflictException. Expected with concurrent execution, continuing test.");
        }
      }

      if (doneWithOps) {
        Log.getLogWriter().info("Done with operation " + whichOp);
        availableOps.clear(whichOp);
      }
    }
  }

  /**
   * Add a new key to REGION_NAME. The method uses PR execute function for
   * addNewKey
   * 
   */
  protected boolean addNewKeyFunction() {
    Log.getLogWriter().info("Inside addNewKeyFunction()");

    long numNewKeysCreated = sc
        .incrementAndRead(InitImageBB.NUM_NEW_KEYS_CREATED);

    if (numNewKeysCreated > numNewKeys) {
      Log.getLogWriter().info(
          "All new keys created; returning from addNewKeyFunction()");
      return true;
    }

    Object key = NameFactory.getNextPositiveObjectName();

    if (TestConfig.tab().booleanAt(parReg.ParRegPrms.isWithRoutingResolver,
        false)) {
      Month callBackArg = Month.months[TestConfig.tab().getRandGen()
          .nextInt(11)];
      InitImageBB.getBB().getSharedMap().put(key, callBackArg);
    }

    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        addNewKeyFunction(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        addNewKeyFunction(aRegion, key);

      }
    return (numNewKeysCreated >= numNewKeys);
  }

  protected void addNewKeyFunction(Region aRegion, Object key) {

    checkContainsValueForKey(aRegion, key, false, "before addNewKey");

    Function addKeyFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(addKeyFunction); //Already registered in
    // init task
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      aList.add("putIfAbsent");
    } else {
      aList.add("addKey");
    }
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    Log.getLogWriter().info("Going to do " + aList.get(0) + " execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(aList)
        .execute(addKeyFunction.getId());// TODO: Kishor replaced async
    drc.getResult();
  }

  /**
   * To invalidate a key to REGION_NAME. The method uses PR execute function for
   * invalidate
   * 
   */
  protected boolean invalidateFunction() {
    Log.getLogWriter().info("Inside invalidateFunction()");

    Cache cache = CacheHelper.getCache();

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_INVALIDATE);
    if (!keyIntervals.keyInRange(KeyIntervals.INVALIDATE, nextKey)) {
      Log
          .getLogWriter()
          .info(
              "All existing keys invalidated; returning from invalidate Function execution");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);

    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        invalidateFunction(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        invalidateFunction(aRegion, key);

      }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.INVALIDATE));

  }

  public void invalidateFunction(Region aRegion, Object key) {
    checkContainsValueForKey(aRegion, key, true, "before invalidate");

    Function invalidateFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(invalidateFunction);
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("invalidate");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    Log.getLogWriter().info("Going to do invalidate execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(aList)
          .execute(invalidateFunction);// TODO: Kishor replced async
    drc.getResult();
  }

  /**
   * To locally invalidate a key to REGION_NAME. The method uses PR execute
   * function for local invalidate
   * 
   */
  protected boolean localInvalidateFunction() {
    Log.getLogWriter().info("Inside localInvalidateFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_LOCAL_INVALIDATE);
    if (!keyIntervals.keyInRange(KeyIntervals.LOCAL_INVALIDATE, nextKey)) {
      Log.getLogWriter().info(
          "All local invalidates completed; returning from localInvalidate");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);

    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        localInvalidateFunction(aRegion, key);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        localInvalidateFunction(aRegion, key);
      }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_INVALIDATE));

  }

  public void localInvalidateFunction(Region aRegion, Object key) {
    checkContainsValueForKey(aRegion, key, true, "before invalidate");

    Function invalidateFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(invalidateFunction);
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("localinvalidate");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    Log.getLogWriter().info("Going to do locally invalidate execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(
        aList).execute(invalidateFunction);// TODO: Kishor
    // replaced async
    drc.getResult();
  }

  /**
   * Destroy key to REGION_NAME. The method uses PR execute function for destroy
   * Key
   * 
   */
  protected boolean destroyFunction() {

    Log.getLogWriter().info("Inside destroyFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_DESTROY);
    if (!keyIntervals.keyInRange(KeyIntervals.DESTROY, nextKey)) {
      Log.getLogWriter().info("All destroys completed; returning from destroy");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        destroyFunction(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        destroyFunction(aRegion, key);

      }
    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.DESTROY));
  }

  public void destroyFunction(Region aRegion, Object key) {
    Log.getLogWriter().info("Destroying " + key);
    checkContainsValueForKey(aRegion, key, true, "before destroy");

    Function destroyFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(destroyFunction);
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);

    ArrayList aList = new ArrayList();
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      aList.add("remove");
    } else {
      aList.add("destroy");
    }
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    Log.getLogWriter().info("Going to do " + aList.get(0) + " execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(aList)
        .execute(destroyFunction.getId());
    drc.getResult();
  }

  /**
   * Locally destroy key to REGION_NAME. The method uses PR execute function for
   * destroy Key
   * 
   */
  protected boolean localDestroyFunction() {

    Log.getLogWriter().info("Inside localDestroyFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_LOCAL_DESTROY);
    if (!keyIntervals.keyInRange(KeyIntervals.LOCAL_DESTROY, nextKey)) {
      Log.getLogWriter().info(
          "All local destroys completed; returning from localDestroy");
      return true;
    }

    Object key = NameFactory.getObjectNameForCounter(nextKey);
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        localDestroyFunction(aRegion, key);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        localDestroyFunction(aRegion, key);

      }
    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_DESTROY));
  }

  public void localDestroyFunction(Region aRegion, Object key) {
    Log.getLogWriter().info("Locally destroying " + key);
    checkContainsValueForKey(aRegion, key, true, "before local destroy");

    Function destroyFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(destroyFunction);
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("localdestroy");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());

    Log.getLogWriter().info("Going to do local destroy execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(aList)
          .execute(destroyFunction.getId());
    drc.getResult();
  }

  /**
   * Update existing key in REGION_NAME. The method uses PR execute function for
   * updating the value of the Key.
   * 
   */
  protected boolean updateExistingKeyFunction() {

    Log.getLogWriter().info("Inside update Function()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_UPDATE_EXISTING_KEY);
    if (!keyIntervals.keyInRange(KeyIntervals.UPDATE_EXISTING_KEY, nextKey)) {
      Log.getLogWriter().info(
          "All existing keys updated; returning from updateExistingKey");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        updateFunction(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        updateFunction(aRegion, key);

      }

    return (nextKey >= keyIntervals
        .getLastKey(KeyIntervals.UPDATE_EXISTING_KEY));
  }

  public void updateFunction(Region aRegion, Object key) {
    checkContainsValueForKey(aRegion, key, true, "before update");
    Function updateFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(updateFunction);
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);

    ArrayList aList = new ArrayList();
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      aList.add("replace");
    } else {
      aList.add("update");
    }
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    Log.getLogWriter().info("Going to do " + aList.get(0) + " execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(aList)
          .execute(updateFunction.getId());
    drc.getResult();
  }

  /**
   * get the value of key in REGION_NAME. The method uses PR execute function
   * for getting the value of the Key.
   * 
   */
  protected boolean getFunction() {
    Log.getLogWriter().info("Inside getFunction()");

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_GET);
    if (!keyIntervals.keyInRange(KeyIntervals.GET, nextKey)) {
      Log.getLogWriter().info("All gets completed; returning from get");
      return true;
    }
    Object key = NameFactory.getObjectNameForCounter(nextKey);

    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        getFunction(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        getFunction(aRegion, key);

      }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.GET));
  }

  public void getFunction(Region aRegion, Object key) {

    checkContainsValueForKey(aRegion, key, true, "before update");
    Function getFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(getFunction);
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList= new ArrayList();
    aList.add("get");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    Log.getLogWriter().info("Going to do get execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(aList).execute(
          getFunction.getId());// TODO Kishor replaced this async
    drc.getResult();
  }

  /**
   * Task is doing a query on the key (executed on the same keys of get ops)
   * 
   * @return
   */
  protected boolean queryFunction() {

    Log.getLogWriter().info("queryFunction");

    long nextKey = sc.incrementAndRead(InitImageBB.LAST_QUERY);

    if (!keyIntervals.keyInRange(KeyIntervals.GET, nextKey)) {
      Log
          .getLogWriter()
          .info(
              "All query completed (done on same keys of gets) ; returning from query");
      return true;
    }

    Object key = NameFactory.getObjectNameForCounter(nextKey);

    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        queryFunction(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        queryFunction(aRegion, key);

      }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.GET));

  }

  public void queryFunction(Region aRegion, Object key) {

    Function queryFunction = new RegionOperationsFunction();
    // FunctionService.registerFunction(getFunction);
    Execution dataSet;

    if (aRegion instanceof PartitionedRegion) {
      Log.getLogWriter().info("Inside p2p function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }
    else {
      Log.getLogWriter().info("Inside client server function execution");
      dataSet = FunctionService.onRegion(aRegion);
    }

    final Set keySet = new HashSet();
    keySet.add(key);
    ArrayList aList = new ArrayList();
    aList.add("query");
    aList.add(RemoteTestModule.getCurrentThread().getThreadId());
    Log.getLogWriter().info("Going to do query execute");
    ResultCollector drc = dataSet.withFilter(keySet).withArgs(aList)
          .execute(queryFunction.getId());
    drc.getResult();
  }

  /**
   * Task to execute functions on all keys
   */
  public void executeFunctionAllKeys(Region aRegion) {

    Log.getLogWriter().info("executeFunctionAllKeys()");

    Function getAllKeysFunction = new KeysOperationsFunction();
    // FunctionService.registerFunction(getAllKeysFunction);
    HashMapResultCollector resultCollectorMap = new HashMapResultCollector();
    Execution dataSet;
    Set keySet = new HashSet();

    String scenario = "Function Execution done on multiple buckets (all the keys of the PR passed as filter) by using HashMapResultCollector \n"
        + " isHA scenario : false \n";

    if (aRegion instanceof PartitionedRegion) {
      keySet.addAll(((PartitionedRegion)aRegion).keys());
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap);
      scenario += " Topology : peer to peer \n";
    }
    else {
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      keySet.addAll(keyList);
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap);
      SilenceListener.waitForSilence(30, 5000);
      scenario += " Topology : client server \n";
    }

    Map myMap = null;

    try {
      Log.getLogWriter().info("Fetching all keys/values");
      myMap = (HashMap)dataSet.withFilter(keySet).execute(getAllKeysFunction)
          .getResult();
    }
    catch (Exception e) {
      throw new TestException("During " + scenario + " Got this exception "
          , e);
    }

    if (!(keySet.size() == myMap.size())) {
      throw new TestException("During " + scenario
          + "Expected result collector returned map size to be "
          + keySet.size() + " (the size of the filter) but got the value "
          + myMap.size());
    }
    else {
      Log.getLogWriter().info(
          "Got the expected size for the map " + myMap.size());
    }

    Iterator iterator = myMap.entrySet().iterator();
    Map.Entry entry = null;
    Object key = null;
    Object value = null;
    Object referenceValue = null;
    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      key = entry.getKey();
      value = entry.getValue();
      if (value instanceof PdxInstance) {
        value = ((PdxInstance)value).getObject();
      }

      if (aRegion instanceof PartitionedRegion) {
        referenceValue = ((PartitionedRegion)aRegion).get(key);
      }
      else {
        referenceValue = aRegion.get(key);
      }
      if (referenceValue instanceof PdxInstance) {
        referenceValue = ((PdxInstance)referenceValue).getObject();
      }

      if (!(value == null || referenceValue == null)) {
        if (!value.equals(referenceValue)) {
          throw new TestException("During " + scenario + " : For the key "
              + key + " the values found in result map is " + value
              + " but that in region is " + referenceValue);
        }
        else {
          Log.getLogWriter().info(
              "For the key " + key
                  + " the values found the expected values in map and region "
                  + value);
        }
      }
      else {
        if ((value == null && referenceValue == null)) {
          Log.getLogWriter().info("Got expected null for the key " + key);
        }
        else {
          throw new TestException("During " + scenario
              + " : Expected value for the key " + key + " to be "
              + referenceValue + " but got the value " + value
              + "in the function result");
        }
      }

    }

  }

  /**
   * Task to execute functions on all keys on multiple nodes and validates that
   * the execution happens only on one node
   */
  public void executeFunctionMultipleNodes(Region aRegion) {
    Log.getLogWriter().info("executeFunctionMultipleNodes()");

    Function getAllKeysFunction = new KeysOperationsFunction();
    // FunctionService.registerFunction(getAllKeysFunction);
    ArrayListResultCollector resultCollectorList = new ArrayListResultCollector();
    Execution dataSet;
    Set keySet = new HashSet();;

    String scenario = "Function Execution done on multiple buckets (all the keys of the PR passed as filter) by using ArrayListResultCollector and args \n"
        + " isHA scenario : false \n";

    if (aRegion instanceof PartitionedRegion) {
      keySet.addAll(((PartitionedRegion)aRegion).keys());
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorList).withArgs("MultiNode");
      scenario += " Topology : peer to peer \n";
    }
    else {
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      keySet.addAll(keyList);
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorList).withArgs("MultiNode");
      scenario += " Topology : client server \n";
    }

    ArrayList myList = null;

    try {
      Log.getLogWriter().info("Fetching all keys/values");
      ResultCollector rc = dataSet.withFilter(keySet).execute(
          getAllKeysFunction); // TODO Kishor replaced this async
      myList = (ArrayList)rc.getResult();
    }
    catch (Exception e) {
      throw new TestException("During " + scenario + " Got this exception ", e);
    }

    if (!(keySet.size() == myList.size())) {
      throw new TestException("During " + scenario
          + "Expected result collector returned map size to be "
          + keySet.size() + " (size of the filter passed) but got the value "
          + myList.size());
    }
    else {
      Log.getLogWriter().info(
          "Got the expected size for the map " + myList.size());
    }
  }

  /**
   * Task to execute functions on all keys on all buckets
   */
  public void executeFunctionAllBuckets(Region aRegion) {
    Log.getLogWriter().info("executeFunctionAllBuckets()");

    Function getAllKeysFunction = new KeysOperationsFunction();
    // FunctionService.registerFunction(getAllKeysFunction);
    HashMapResultCollector resultCollectorMap = new HashMapResultCollector();
    Execution dataSet;

    String scenario = "Function Execution done on all buckets (without passing any filter) by using HashMapResultCollector \n"
        + " isHA scenario : false \n";

    Set keySet = new HashSet();

    if (aRegion instanceof PartitionedRegion) {
      keySet.addAll(((PartitionedRegion)aRegion).keys());
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap);
    }
    else {
      ArrayList keyList = (ArrayList)parReg.ParRegBB.getBB().getSharedMap()
          .get(KEY_LIST);
      keySet.addAll(keyList);
      dataSet = FunctionService.onRegion(aRegion).withCollector(
          resultCollectorMap);
    }

    Map myMap = null;

    try {
      Log.getLogWriter().info("Fetching all keys/values");
      ResultCollector rc = dataSet.execute(getAllKeysFunction);// TODO
      // Kishor
      // replaced
      // async
      myMap = (HashMap)rc.getResult();
    }
    catch (Exception e) {
      throw new TestException("During " + scenario + " Got this exception ", e);
    }

    int expectedSize = (keySet.size());

    if (myMap.size() != expectedSize) {
      throw new TestException("During " + scenario
          + " Expected size of the map to be " + expectedSize
          + "(number of keys) but it is " + myMap.size());
    }
    else {
      Log.getLogWriter().info(
          "Got the expected size for the map " + expectedSize);
    }
  }

  /**
   * Task to execute functions where it is tested whether the function execution
   * happen on only those buckets for which filter is passed(on local data set).
   * Also checking for the co-located local data set.
   */
  public void executeFunctionFilterWithLocalDataSet(Region aRegion) {
    Log.getLogWriter().info("executeFunctionFilterWithLocalDataSet()");

    Execution dataSet_1; // To execute with filter and optimizefor write true
    Execution dataSet_2; // To execute with filter and optimizefor write false
    Execution dataSet_3; // To execute without filter and optimizefor write
    // true
    Execution dataSet_4; // To execute without filter and optimizefor write
    // false

    Set keySet = new HashSet();;
    Set expectedBucketSet = new HashSet();
    ;

    if (!(aRegion instanceof PartitionedRegion)) {
      Log.getLogWriter().info(
          "This test need not be done in a client server mode");
      return;
    }

    int totalNumOfBuckets = ((PartitionedRegion)aRegion)
        .getTotalNumberOfBuckets();
    int redundantCopies = ((PartitionedRegion)aRegion).getRedundantCopies();

    // Getting the keySet for passing as filter
    try {
      for (int i = 0; i < 3; i++) {
        int randInt = TestConfig.tab().getRandGen().nextInt(11);
        keySet.addAll(((PartitionedRegion)aRegion).getBucketKeys(randInt));
      }
      if (keySet.size() == 0) {
        throw new TestException("Filter set is zero. Test needs tuning");
      }
    }
    catch (Exception e) {
      throw new TestException("Caught unexpected exception ", e);
    }

    // Finding out the targeted buckets
    Iterator keySetItr = keySet.iterator();
    while (keySetItr.hasNext()) {
      Object key = keySetItr.next();
      Month routingObject = (Month)InitImageBB.getBB().getSharedMap().get(key);
      expectedBucketSet.add(routingObject.hashCode() % totalNumOfBuckets);
    }

    // Initiating the Execution
    dataSet_1 = FunctionService.onRegion(aRegion).withFilter(keySet).withArgs(
        "FilterWithLocalDataSet").withCollector(new ArrayListResultCollector());
    dataSet_2 = FunctionService.onRegion(aRegion).withFilter(keySet).withArgs(
        "FilterWithLocalDataSet").withCollector(new ArrayListResultCollector());
    dataSet_3 = FunctionService.onRegion(aRegion).withArgs(
        "FilterWithLocalDataSet").withCollector(new ArrayListResultCollector());
    dataSet_4 = FunctionService.onRegion(aRegion).withArgs(
        "FilterWithLocalDataSet").withCollector(new ArrayListResultCollector());

    ArrayList list1, list2, list3, list4;
    try {
      list1 = (ArrayList)dataSet_1.execute(
          new LocalDataSetFunctionPrimaryExecute()).getResult();
      list2 = (ArrayList)dataSet_2.execute(
          new LocalDataSetFunctionPrimaryExecuteFalse()).getResult();
      list3 = (ArrayList)dataSet_3.execute(
          new LocalDataSetFunctionPrimaryExecute()).getResult();
      list4 = (ArrayList)dataSet_4.execute(
          new LocalDataSetFunctionPrimaryExecuteFalse()).getResult();
    }
    catch (Exception e) {
      throw new TestException("Got this exception " + e.getMessage(), e);
    }

    // Should give only one copy either primary or secondary
    if (list1.size() != expectedBucketSet.size()) {
      throw new TestException(
          "Expected size for the function result (with filter and optimizefor write true) to be "
              + expectedBucketSet.size() + " but received " + list1.size());
    }
    else {
      Log.getLogWriter().info("Received expected list1 size" + list1.size());
    }

    // Should give only one copy either primary or secondary
    if (list2.size() != expectedBucketSet.size()) {
      throw new TestException(
          "Expected size for the function result (with filter and optimizefor write false) to be "
              + expectedBucketSet.size()
              * (redundantCopies + 1)
              + " but received " + list2.size());
    }
    else {
      Log.getLogWriter().info("Received expected list2 size" + list2.size());
    }

    // Should give only one copy either primary or secondary (but no filter
    // passes, so all buckets)
    if (list3.size() != totalNumOfBuckets) {
      throw new TestException(
          "Expected size for the function result (without filter and optimizefor write true) to be "
              + totalNumOfBuckets + " but received " + list3.size());
    }
    else {
      Log.getLogWriter().info("Received expected list3 size" + list3.size());
    }

    // All buckets with redundantCopies as no filter passed and execute on
    // primary false
    if (list4.size() != totalNumOfBuckets) {
      throw new TestException(
          "Expected size for the function result (without filter and optimizefor write false) to be "
              + totalNumOfBuckets + " but received " + list4.size());
    }
    else {
      Log.getLogWriter().info("Received expected list4 size" + list4.size());
    }

  }

  /**
   * Task to execute onRegions functions
   * @param regionSet
   */
  protected void doOnRegionsExecutions(HashSet regionSet) {
    hydra.Log.getLogWriter().info("Passing the region set " + regionSet);
    Execution dataSet = InternalFunctionService.onRegions(regionSet).withArgs(
        "invalidateEntries");

    try {
      dataSet.execute(new OnRegionsFunction()).getResult();
    }
    catch (Exception e) {
      throw new TestException("OnRegions function execution caught exception ",
          e);
    }

    for (Object aRegion : regionSet) {
      Set keySet = ((Region)aRegion).keySet();
      for (Object key : keySet) {
        Object value = ((Region)aRegion).get(key);
        if (value != null) {
          throw new TestException(
              "Values supposed to be invalidated after clear Region (done by onRegions function execution), but key "
                  + key + " has value " + value);
        }
      }

    }
  }
  
  /**
   * Task to execute onRegions functions
   * @param regionSet
   */
  protected void doOnRegionsExecutionsHA(HashSet regionSet) {
    hydra.Log.getLogWriter().info("Passing the region set " + regionSet);
    Execution dataSet = InternalFunctionService.onRegions(regionSet).withArgs(
        "regionSize");

    try {
      dataSet.execute(new OnRegionsFunction()).getResult(150, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      throw new TestException("OnRegions function execution caught exception ",
          e);
    }

    try {
      dataSet.execute(new OnRegionsFunction()).getResult();
    }
    catch (Exception e) {
      throw new TestException("OnRegions function execution caught exception ",
          e);
    }
  }
  
  /**
   * Hydra task to verify metaDataRefreshCount.
   */
  public static void HydraTask_varifyMetaDataRefreshCount() {
    FunctionServiceTest fst = (FunctionServiceTest)testInstance;
    long numOfOps = fst.keyIntervals.getNumKeys()
        + fst.keyIntervals.getNumKeys(KeyIntervals.DESTROY)
        + fst.keyIntervals.getNumKeys(KeyIntervals.GET)
        + fst.keyIntervals.getNumKeys(KeyIntervals.UPDATE_EXISTING_KEY)
        + fst.numNewKeys;
    double metaDataRefreshCount = fst.getCachePerfStats_metaDataRefreshCount();
    double refreshPerc = metaDataRefreshCount / numOfOps * 100;
    // Refresh percentage should not be more than 20% of total operations
    // performed.
    if (refreshPerc > 10) {
      throw new TestException("Observed " + refreshPerc + " percent meta data "
          + "refreshed due to hopping, for total operations " + numOfOps);
    }
    else {
      hydra.Log.getLogWriter().info(
          "Observed " + refreshPerc + " percent meta data "
              + "refreshed due to hopping, for total operations " + numOfOps);
    }
  }
  
  private double getCachePerfStats_metaDataRefreshCount() {
    String spec = util.TestHelper.getGemFireName()
        + " " // search only this archive
        + "CachePerfStats "
        + "cachePerfStats " // match all instances
        + "metaDataRefreshCount " + StatSpecTokens.FILTER_TYPE + "="
        + StatSpecTokens.FILTER_NONE + " " + StatSpecTokens.COMBINE_TYPE + "="
        + StatSpecTokens.RAW + " " + StatSpecTokens.OP_TYPES + "="
        + StatSpecTokens.MAX;

    List aList = null;

    try {
      aList = PerfStatMgr.getInstance().readStatistics(spec);
    }
    catch (NullPointerException npe) {
      Log.getLogWriter().info(
          "readStatistics( " + spec + " ) returned " + npe.getStackTrace());
    }

    if (aList == null) {
      Log.getLogWriter().info(
          "Getting stats for spec " + spec + " returned null");
      return 0;
    }

    double metaDataRefreshCount = 0;
    PerfStatValue stat = (PerfStatValue)aList.get(0);
    metaDataRefreshCount = stat.getMax();

    Log.getLogWriter().info(
        "CachePerfStats metaDataRefreshCount = " + metaDataRefreshCount);
    return (metaDataRefreshCount);
  }
  
  /**
   * Hydra task to verify client PR meta data
   */
  public static void HydraTask_verifyClientPRMetadata() {
    // This will be processed only by client vm

    if (isBridgeClient || !isBridgeConfiguration) { // this is client
      // verify number region size from region meta data 
      int numRegions = regionDescriptNames.size();
      int numRegionFromClientMetaData = ParRegUtil.getRegionCountFromClientMetaData();
      if (numRegionFromClientMetaData != numRegions) {
        throw new TestException("Expected number of region to be" + numRegions 
            + ", but client meta data has " + numRegionFromClientMetaData);
      }
      
      //For each bucket, verify the number of locations in server group   
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        // region instance
        String regionDescriptName = (String)(regionDescriptNames.get(j));
        RegionDescription regionDesc = RegionHelper
            .getRegionDescription(regionDescriptName);
        String regionName = regionDesc.getRegionName();
        Region region = theCache.getRegion(regionName);

        // Server group from pool
        String poolName = region.getAttributes().getPoolName();
        Pool pool = PoolHelper.getPool(poolName);
        boolean isServerGroup = false;
        List<Endpoint> groupEndpoints;
        if (pool.getServerGroup() == null || pool.getServerGroup().equals("")) {          
          Log.getLogWriter().info(
              "No server group is defined for region " + regionName);
          groupEndpoints = BridgeHelper.getEndpoints();
        }
        else {
          isServerGroup = true;
          String serverDsName = (String)InitImageBB.getBB().getSharedMap().get("serverds");
          groupEndpoints = BridgeHelper.getEndpointsInGroup(
              serverDsName, pool.getServerGroup());
          Log.getLogWriter().info(
              "For region " + regionName + " poolname=" + poolName
                  + ", server group=" + pool.getServerGroup()
                  + ", endpoint list=" + groupEndpoints);
        }

        ClientMetadataService cms = ((GemFireCacheImpl)theCache)
            .getClientMetadataService();
        Map<String, ClientPartitionAdvisor> regionMetaData = cms
            .getClientPRMetadata_TEST_ONLY();
        ClientPartitionAdvisor prMetaData = regionMetaData.get(region
            .getFullPath());
        if (prMetaData == null) {
          throw new TestException("Client does not contains region "
              + regionName);
        }
        else {
          Log.getLogWriter().info(
              "For region " + regionName + " client meta data size is "
                  + prMetaData.getBucketServerLocationsMap_TEST_ONLY().size());
          for (Entry<Integer, List<BucketServerLocation66>> e : prMetaData
              .getBucketServerLocationsMap_TEST_ONLY().entrySet()) {
            List<BucketServerLocation66> serverLocs = e.getValue();
            Log.getLogWriter().info(
                "For bucket id " + e.getKey()
                    + " the server locations are " + serverLocs);
            isServersContainsBucketLocation(groupEndpoints, serverLocs, pool
                .getServerGroup());
          }
        }
      }
      Log.getLogWriter().info("Verified client mata data");
    }
  }

  private static boolean isServersContainsBucketLocation(
      List<Endpoint> groupEndpoints,
      List<BucketServerLocation66> bucketServerLocs, String group) {
    List<String> serverList = new ArrayList();
    for (Endpoint e : groupEndpoints) {
      String hostname = (e.getHost().contains(".")) ? e.getHost().substring(0,
          e.getHost().indexOf(".")) : e.getHost();
      serverList.add(hostname + ":" + e.getPort());
    }

    List<String> bucketServerList = new ArrayList();
    for (BucketServerLocation66 bsl : bucketServerLocs) {      
      String hostname = (bsl.getHostName().contains(".")) ? bsl.getHostName()
          .substring(0, bsl.getHostName().indexOf(".")) : bsl.getHostName();
      String loc = hostname + ":" + bsl.getPort();
      boolean containGroup = false;
      String[] glist = bsl.getServerGroups();
      for (int i=0; i<glist.length ; i++){
        if (glist[i].equals(group)){
          containGroup = true;
        }
      }
      if (containGroup && !serverList.contains(loc)) {
        throw new TestException("Bucket " + bsl.getBucketId()
            + " is available in non group member " + loc
            + ", group \"" + group +"\" server list from blackboard is " + serverList);
      }
    }
    return true;
  }

  /**
   * Store server distributed system name in blackboard for client's future
   * reference.
   */
  public static void HydraTask_storeServerDsToBB() {
    InitImageBB.getBB().getSharedMap().put("serverds",
        DistributedSystemHelper.getDistributedSystemName());
  }
}
