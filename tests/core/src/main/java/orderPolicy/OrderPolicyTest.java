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

package orderPolicy;

import java.util.List;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.ClientHelper;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.util.GatewayHub;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import hct.HctPrms;
import hydra.*;
import hydra.blackboard.Blackboard;
import util.NameFactory;
import util.RandomValues;
import util.TestException;
import util.TestHelper;
import util.TestHelperPrms;
import util.ValueHolder;
import wan.CacheClientPrms;
import wan.CacheServerPrms;
import wan.WANBlackboard;
import wan.WANClient;

/**
 * Extends wan.OrderPolicyTest for Gateway.OrderPolicy and Gateway.ConcurrencyLevel
 *
 * @author Lynn Hughes-Godfrey
 * @since 6.6.2
 */
public class OrderPolicyTest extends WANClient {

  // single instance of random values, used as the value for puts (for OrderPolicy tests)
  static protected RandomValues randomValues = new RandomValues();

  // single instance of this class in this VM (for OrderPolicy tests)
  static protected OrderPolicyTest testInstance;

  public HydraThreadLocal uniqueKeyIndex = new HydraThreadLocal();

  // control how long the TASK runs (needs to be run from the same native thread)
  // not a hydra logical thread id.
  protected long minTaskGranularitySec; // the task granularity in seconds
  protected long minTaskGranularityMS; // the task granularity in milliseconds

  //============================================================================
  // INITTASKS (overrides)
  //============================================================================

 /** 
  *  Cache initialization for peer gateways
  */
  public static synchronized void initPeerForOrderPolicyTest() {
    if (testInstance == null) {
      String cacheConfig = TestConfig.tasktab().stringAt(CacheClientPrms.cacheConfig, TestConfig.tab().stringAt(CacheClientPrms.cacheConfig, null));
      String regionConfig = TestConfig.tasktab().stringAt(CacheClientPrms.regionConfig, TestConfig.tab().stringAt(CacheClientPrms.regionConfig, null));
      
      testInstance = new OrderPolicyTest();
      Log.getLogWriter().info("testInstance = " + testInstance);
      testInstance.createCache(cacheConfig);
      testInstance.createGatewaySender();
      testInstance.createRegion(REGION_NAME, regionConfig);
      testInstance.createGatewayHub();

      // single randomValues for this VM
      randomValues = new RandomValues();
      testInstance.initTaskTime();
    } 
  }

  /**
   * Initializes a server cache based on the {@link CacheServerPrms}.
   */
  public static synchronized void initServerForOrderPolicyTest() {
    if (testInstance == null) {
      String cacheConfig = TestConfig.tasktab().stringAt(CacheServerPrms.cacheConfig, TestConfig.tab().stringAt(CacheServerPrms.cacheConfig, null));
      String regionConfig = TestConfig.tasktab().stringAt(CacheServerPrms.regionConfig, TestConfig.tab().stringAt(CacheServerPrms.regionConfig, null));
      String bridgeConfig = TestConfig.tasktab().stringAt(CacheServerPrms.bridgeConfig, TestConfig.tab().stringAt(CacheServerPrms.bridgeConfig, null));
  
      testInstance = new OrderPolicyTest();
      testInstance.createCache(cacheConfig);
      testInstance.createGatewaySender();
      testInstance.createRegion(REGION_NAME, regionConfig);
      initPdxDiskStore();
      testInstance.startBridgeServer(bridgeConfig);
      testInstance.createGatewayHub();

      // single randomValues for this VM
      randomValues = new RandomValues();
      testInstance.initTaskTime();
    }
  }

  /**
   * Initializes a edge cache based on the {@link CacheClientPrms}.
   */
  public static synchronized void initEdgeForOrderPolicyTest() {
    if (testInstance == null) {
      String cacheConfig = TestConfig.tab().stringAt(CacheClientPrms.cacheConfig);
      String regionConfig = TestConfig.tab().stringAt(CacheClientPrms.regionConfig);

      testInstance = new OrderPolicyTest();
      Cache cache = testInstance.createCache(cacheConfig);
      Region region = testInstance.createRegion(REGION_NAME, regionConfig);

      PoolImpl mybw = ClientHelper.getPool(region);
      ServerLocation primaryEndpoint = (ServerLocation )mybw.getPrimary();
      Log.getLogWriter().info("The primary server endpoint is " + primaryEndpoint);

      region.registerInterest("ALL_KEYS", CacheClientPrms.getInterestPolicy());
      Log.getLogWriter().info(region.getFullPath() + ": Registered interest in ALL_KEYS");

      // single randomValues for this VM
      randomValues = new RandomValues();
      testInstance.initTaskTime();
    }
  }



  /**
   *  Initialize the instance variables for task timeout
   */
  protected void initTaskTime() {
    minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec);
    minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
  }

  /**
   * Starts a gateway hub in a VM that previously created one, after creating
   * gateways configured based on {@link CacheServerPrms}.
   */
  public static void startGatewayHubTask() {
    String gatewayConfig = TestConfig.tab().stringAt(CacheServerPrms.gatewayConfig);

    testInstance.startGatewayHub(gatewayConfig);
    testInstance.startQueueMonitor();
  }

  protected void createGatewaySender(){
    String senderConfig = ConfigPrms.getGatewaySenderConfig();
    if(senderConfig != null){
      GatewaySenderHelper.createGatewaySenders(senderConfig);  
    }
  }
  
  //============================================================================
  // TASKS (overrides)
  //============================================================================

  /**
   *  Initialize threadLocals for OrderPolicy tests
   *  needs to be invoked by each thread doing OrderPolicy related puts
   */
  public static void initializeInstanceTask() {
    Log.getLogWriter().info("testInstance = " + testInstance);
    testInstance.initializeInstance();
  }

  protected void initializeInstance() {
    // maintain the operation number for each thread on the BB (survives VM recycling)
    Blackboard bb = OrderPolicyBB.getBB();
    int vmId = RemoteTestModule.getMyVmid();
    int tid = RemoteTestModule.getCurrentThread().getThreadId();
    String mapKey = "vm_" + vmId + "_thr_" + tid;
    if (bb.getSharedMap().get(mapKey) == null) {  // initialize to 0 on first access only
       bb.getSharedMap().put(mapKey, 0);
    }

    // set threadlocal (so we'll restrict keyset to a single writer) in each thread
    uniqueKeyIndex.set(new Integer(tid));
  }

  /** 
   *  TBD
   *  
   *  
   */
  public static void putsForThreadOrderPolicyTask() throws Exception {
    testInstance.putsForThreadOrderPolicy();
  }

  protected void putsForThreadOrderPolicy() {
    Blackboard bb = OrderPolicyBB.getBB();
    Region region = RegionHelper.getRegion(REGION_NAME);    
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;

    int vmId = RemoteTestModule.getMyVmid();
    int tid = RemoteTestModule.getCurrentThread().getThreadId();
    String currentOpsStr = "vm_" + vmId + "_thr_" + tid;
    
    long mytid = Thread.currentThread().getId();
    String callbackArg = "vm_" + vmId + "_thr_" + mytid;
    
    int opNum = ((Integer)(bb.getSharedMap().get(currentOpsStr))).intValue();
    int keyCounter = ((Integer)uniqueKeyIndex.get()).intValue();
    int numWorkingThreads = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();

    long startTime = System.currentTimeMillis();

    do {
      // check to see if we've had any skips in value sequence in listeners (posted to BB)
      TestHelper.checkForEventError(WANBlackboard.getInstance());

      startNoKillZone();
      for (long i = 1; i <= ITERATIONS; i++) {
        String key = NameFactory.getObjectNameForCounter(keyCounter);
        ValueHolder vh = new ValueHolder(key, randomValues, new Integer(opNum));
        Log.getLogWriter().info("putting: " + key + ":" + vh.toString() + ":" + callbackArg);
        region.put( key, vh, callbackArg );
        // todo@lhughes -- we should be able to work on a range of keys 
        // but to make this easier initially, let's stick with ownKey keyAllocation
        //keyCounter += numWorkingThreads;
        opNum++;
      } 
      endNoKillZone();
      bb.getSharedMap().put(currentOpsStr, new Integer(opNum));
    } while (System.currentTimeMillis() - startTime < minTaskGranularityMS);
  }

  /** 
   *  Method to write incrementally update the value an entry (specific to logical thread id).
   */
  public static void putsForKeyOrderPolicyTask() throws Exception {
    testInstance.putsForKeyOrderPolicy();
  }

  protected void putsForKeyOrderPolicy() {
    Blackboard bb = OrderPolicyBB.getBB();
    Region region = RegionHelper.getRegion(REGION_NAME);    
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;

    int vmId = RemoteTestModule.getMyVmid();
    int tid = RemoteTestModule.getCurrentThread().getThreadId();
    String callbackArg = "vm_" + vmId + "_thr_" + tid;
    int opNum = ((Integer)(bb.getSharedMap().get(callbackArg))).intValue();
    int keyCounter = ((Integer)uniqueKeyIndex.get()).intValue();

    long startTime = System.currentTimeMillis();

    do {
      // check to see if we've had any skips in value sequence in listeners (posted to BB)
      TestHelper.checkForEventError(WANBlackboard.getInstance());

      startNoKillZone();
      for (long i = 1; i <= ITERATIONS; i++) {
        String key = NameFactory.getObjectNameForCounter(keyCounter);
        ValueHolder vh = new ValueHolder(key, randomValues, new Integer(opNum));
        Log.getLogWriter().info("putting: " + key + ":" + vh.toString() + ":" + callbackArg);
        region.put( key, vh, callbackArg );
        opNum++;
      } 
      endNoKillZone();
      bb.getSharedMap().put(callbackArg, new Integer(opNum));
    } while (System.currentTimeMillis() - startTime < minTaskGranularityMS);
  }

  /**
   * A Hydra TASK that chooses a random gateway hub and pauses/resumes it.
   */
  public synchronized static void pauseGatewayHub() { 
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    Log.getLogWriter().info( "Sleeping for " + sleepMs + "ms" );
    MasterController.sleepForMs( sleepMs );
 
    // pause gateway
    GatewayHub hub = null;
    List hubs = CacheHelper.getCache().getGatewayHubs();
    if (hubs.size() > 0) {
        hub = CacheHelper.getCache().getGatewayHubs().get(0);
    }

    // pause the gateway 
    int restartWaitSec = TestConfig.tab().intAt(HctPrms.restartWaitSec);
    if (hub != null) {
       Log.getLogWriter().info("Pausing GatewayHub " + hub.toString() + " resuming in " + restartWaitSec + " seconds");
       hub.pauseGateways();
    }

    MasterController.sleepForMs( restartWaitSec * 1000 );
    Log.getLogWriter().info("Resuming GatewayHub " + hub.toString());
    hub.resumeGateways();
    Log.getLogWriter().info("Resumed GatewayHub " + hub.toString());
  }

  /**
   * A Hydra TASK that chooses a random gateway hub and stops/starts it.
   */
  public synchronized static void stopGatewayHub() { 
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    Log.getLogWriter().info( "Sleeping for " + sleepMs + "ms" );
    MasterController.sleepForMs( sleepMs );
 
    // stop gateway
    GatewayHub hub = null;
    List hubs = CacheHelper.getCache().getGatewayHubs();
    if (hubs.size() > 0) {
        hub = CacheHelper.getCache().getGatewayHubs().get(0);
    }

    // stop the gateway 
    int restartWaitSec = TestConfig.tab().intAt(HctPrms.restartWaitSec);
    if (hub != null) {
       Log.getLogWriter().info("Stopping GatewayHub " + hub.toString() + " restarting in " + restartWaitSec + " seconds");
       // todo@lhughes -- why does this NOT work (no failover, hang?, lost events)
       //hub.stopGateways();
       hub.stop();
    }
    MasterController.sleepForMs( restartWaitSec * 1000 );
    try {
       Log.getLogWriter().info("Starting GatewayHub " + hub.toString());
       hub.startGateways();
       Log.getLogWriter().info("Started GatewayHub " + hub.toString());
    } catch (java.io.IOException ioe) {
       throw new TestException("startGateways caught unexpected Exception " + ioe + "\n" + TestHelper.getStackTrace(ioe));
    }
  }

  /**
   * Check that no Listener Exceptions were posted to the BB 
   */
  public static void checkForEventErrors() {
    // process ALL events
    waitForQueuesToDrain();

    // check to see if we've had any skips in value sequence in listeners (posted to BB)
    TestHelper.checkForEventError(WANBlackboard.getInstance());
  }
}
