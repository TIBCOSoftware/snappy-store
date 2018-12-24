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

package wan;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.internal.cache.GatewayStats;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.gemstone.gemfire.security.AuthenticationFailedException;
import cq.CQUtil;
import cq.CQUtilBB;
import diskReg.DiskRegUtil;
import durableClients.DurableClientsBB;
import hct.BBoard;
import hct.HctPrms;
import hydra.*;
import hydra.BridgeHelper.Endpoint;
import hydra.blackboard.SharedCounters;
import hydra.blackboard.SharedMap;
import objects.ObjectHelper;
import objects.PSTObject;
import pdx.PdxTest;
import security.SecurityClientsPrms;
import util.BaseValueHolder;
import util.NameFactory;
import util.RandomValues;
import util.SilenceListener;
import util.TestException;
import util.TestHelper;
import wan.ml.GemFireQuoteFeeder;
import wan.ml.GemFireTradeBurstFeeder;
import wan.ml.GemFireTradeFeeder;
import wan.ml.MLPrms;

/**
 * Supports example tests for WAN distribution.
 *
 * @author Lise Storc
 * @since WAN
 */
public class WANClient {

  protected static final String REGION_NAME 
            = NameFactory.REGION_NAME_PREFIX + "GlobalVillage";
  protected static final String TRADES_REGION_NAME 
            = NameFactory.REGION_NAME_PREFIX + "TRADES";
  protected static final String QUOTES_REGION_NAME 
            = NameFactory.REGION_NAME_PREFIX + "QUOTES";
  protected static final int ITERATIONS = CacheClientPrms.getIterations();

  public static boolean serverAlive = true;

  static ConfigHashtable conftab = TestConfig.tab();
  static LogWriter logger = Log.getLogWriter();

  static GsRandom rand = new GsRandom();
  static RandomValues rv = new RandomValues();
  
  // boolean for key prefixes for the security tests

  static boolean validPrefix = false;

  static boolean invalidPrefix = false;
  
  // ArrayList to store the keys

  static ArrayList keyList = new ArrayList();
  
  public static long lastEventReceivedTime = 0; 

  //============================================================================
  // INITTASKS
  //============================================================================

  /**
   * Creates a (disconnected) locator.
   */
  public static void createLocatorTask() {
    DistributedSystemHelper.createLocator();
  }

  /**
   * Connects a locator to its (admin-only) distributed system.
   */
  public static void startAndConnectLocatorTask() {
    DistributedSystemHelper.startLocatorAndAdminDS();
  }

  /**
   * Stops a locator.
   */
  public static void stopLocatorTask() {
    DistributedSystemHelper.stopLocator();
  }

  /**
   * Initializes a peer cache based on the {@link CacheClientPrms}.
   */
  public static void initPeerCacheTask() {
    String cacheConfig = TestConfig.tasktab().stringAt(CacheClientPrms.cacheConfig, conftab.stringAt(CacheClientPrms.cacheConfig, null));
    String regionConfig = TestConfig.tasktab().stringAt(CacheClientPrms.regionConfig, conftab.stringAt(CacheClientPrms.regionConfig, null));

    WANClient client = new WANClient();
    client.createCache(cacheConfig);
    client.createRegion(REGION_NAME, regionConfig);
    client.createGatewayHub();
    initPdxDiskStore();
  }
  
  
  /**
   * Initializes a peer cache based on the {@link CacheClientPrms}. The task is
   * same as initPeerCacheTask but it doesnot create GateWayHub
   */
  public static void initSecurityPeerCacheTask() {
    boolean expectedFail = SecurityClientsPrms.isExpectedException();
    try {
      String cacheConfig = TestConfig.tasktab().stringAt(CacheClientPrms.cacheConfig, conftab.stringAt(CacheClientPrms.cacheConfig, null));
      String regionConfig = TestConfig.tasktab().stringAt(CacheClientPrms.regionConfig, conftab.stringAt(CacheClientPrms.regionConfig, null));

      WANClient client = new WANClient();
      client.createCache(cacheConfig);
      client.createRegion(REGION_NAME, regionConfig);

      if (expectedFail) {
        throw new TestException("Expected this to throw AuthFailException");
      }
    }
    catch (AuthenticationFailedException e) {
      if (expectedFail) {
        Log.getLogWriter().info(
            "Got expected AuthenticationFailedException: " + e.getMessage());
      }
      else {
        throw new TestException(
            "AuthenticationFailedException while openCacheTask :"
                + e.getMessage());
      }
    }
    catch (Exception e) {
      throw new TestException("Exception while openCacheTask :"
          + e.getMessage());
    }

  }

  /**
   * Initializes a server cache based on the {@link CacheServerPrms}.
   */
  public static void initServerCacheTask() {
    String cacheConfig = TestConfig.tasktab().stringAt(CacheServerPrms.cacheConfig, conftab.stringAt(CacheServerPrms.cacheConfig, null));
    String regionConfig = TestConfig.tasktab().stringAt(CacheServerPrms.regionConfig, conftab.stringAt(CacheServerPrms.regionConfig, null));
    String bridgeConfig = TestConfig.tasktab().stringAt(CacheServerPrms.bridgeConfig, conftab.stringAt(CacheServerPrms.bridgeConfig, null));

    WANClient client = new WANClient();
    client.createCache(cacheConfig);
    client.createRegion(REGION_NAME, regionConfig);
    initPdxDiskStore();
    client.startBridgeServer(bridgeConfig);
    client.createGatewayHub();
  }
  
  /**
   * Initializes a server cache based on the {@link CacheServerPrms}. Looks for
   * Security credentials if required
   */
  public static void initSecurityServerCacheTask() {
    boolean expectedFail = SecurityClientsPrms.isExpectedException();
    try {
      String cacheConfig = TestConfig.tasktab().stringAt(CacheServerPrms.cacheConfig, conftab.stringAt(CacheServerPrms.cacheConfig, null));
      String regionConfig = TestConfig.tasktab().stringAt(CacheServerPrms.regionConfig, conftab.stringAt(CacheServerPrms.regionConfig, null));
      String bridgeConfig = TestConfig.tasktab().stringAt(CacheServerPrms.bridgeConfig, conftab.stringAt(CacheServerPrms.bridgeConfig, null));

      WANClient client = new WANClient();
      client.createCache(cacheConfig);
      client.createRegion(REGION_NAME, regionConfig);
      client.startBridgeServer(bridgeConfig);

      if (expectedFail) {
        throw new TestException("Expected this to throw AuthFailException");
      }
    }
    catch (AuthenticationFailedException e) {
      if (expectedFail) {
        Log.getLogWriter().info(
            "Got expected AuthenticationFailedException: " + e.getMessage());
      }
      else {
        throw new TestException(
            "AuthenticationFailedException while openCacheTask :"
                + e.getMessage());
      }
    }
    catch (Exception e) {
      throw new TestException("Exception while openCacheTask :"
          + e.getMessage());
    }

  }

  /**
   * Initializes an ML server cache based on the {@link CacheServerPrms}.
   */
  public static void initMLServerCacheTask() {
    String cacheConfig = TestConfig.tasktab().stringAt(CacheServerPrms.cacheConfig, conftab.stringAt(CacheServerPrms.cacheConfig, null));
    String regionConfig = TestConfig.tasktab().stringAt(CacheServerPrms.regionConfig, conftab.stringAt(CacheServerPrms.regionConfig, null));
    String bridgeConfig = TestConfig.tasktab().stringAt(CacheServerPrms.bridgeConfig, conftab.stringAt(CacheServerPrms.bridgeConfig, null));

    WANClient client = new WANClient();
    client.createCache(cacheConfig);
    client.createRegion(TRADES_REGION_NAME, regionConfig);
    client.createRegion(QUOTES_REGION_NAME, regionConfig);
    client.startBridgeServer(bridgeConfig);
    client.createGatewayHub();
  }

  
  /**
   * Starts a gateway hub in a VM that previously created one, after creating
   * gateways configured based on {@link CacheServerPrms}.
   */
  public static void startGatewayHubTask() {
    String gatewayConfig = TestConfig.tasktab().stringAt(CacheServerPrms.gatewayConfig, conftab.stringAt(CacheServerPrms.gatewayConfig, null));

    WANClient client = new WANClient();
    client.startGatewayHub(gatewayConfig);
    client.startQueueMonitor();
  }

  /**
   * Starts a gateway hub in a VM that previously created one, after creating
   * gateways configured based on {@link CacheServerPrms}. This is for Security
   * Test and this creates and starts the gateway hub
   */
  public static void createAndStartGatewayHubTask() {
    boolean expectedFail = SecurityClientsPrms.isExpectedException();
    Log.getLogWriter().info("isExpectedException " + expectedFail);
    try {
      String gatewayConfig = TestConfig.tasktab().stringAt(CacheServerPrms.gatewayConfig, conftab.stringAt(CacheServerPrms.gatewayConfig, null));

      WANClient client = new WANClient();
      client.createGatewayHub();
      MasterController.sleepForMs(30000);
      client.startGatewayHub(gatewayConfig);
      client.startQueueMonitor();

      if (expectedFail) {
        throw new TestException("Expected this to throw AuthFailException");
      }
    }
    catch (AuthenticationFailedException e) {
      if (expectedFail) {
        Log.getLogWriter().info(
            "Got expected AuthenticationFailedException: " + e.getMessage());
      }
      else {
        throw new TestException(
            "AuthenticationFailedException while openCacheTask :"
                + e.getMessage());
      }
    }
  }

  /**
   * Initializes a edge cache based on the {@link CacheClientPrms}.
   */
  public static void initEdgeClientCacheTask() {
    String cacheConfig = conftab.stringAt(CacheClientPrms.cacheConfig);
    String regionConfig = conftab.stringAt(CacheClientPrms.regionConfig);

    WANClient client = new WANClient();
    Cache cache = client.createCache(cacheConfig);
    Region region = client.createRegion(REGION_NAME, regionConfig);
    String VmDurableId = ((InternalDistributedSystem)InternalDistributedSystem
        .getAnyInstance()).getConfig().getDurableClientId();

    if (!VmDurableId.equals("")) {
      Log.getLogWriter().info(" VM Durable Client Id is " + VmDurableId);
      if (!DurableClientsBB.getBB().getSharedMap().containsKey(VmDurableId)) {
        HashMap map = new HashMap();
        DurableClientsBB.getBB().getSharedMap().put(VmDurableId, map);
      }
      cache.readyForEvents();
    }
    
    PoolImpl mybw = ClientHelper.getPool(region);

    ServerLocation primaryEndpoint = (ServerLocation )mybw.getPrimary();
    Log.getLogWriter()
        .info("The primary server endpoint is " + primaryEndpoint);
    client.registerInterest(region);
  }

  /**
   * Initializes an edge CQ client based on the {@link CacheClientPrms}.
   */
  public static void initCQClientTask() {
    String cacheConfig = conftab.stringAt(CacheClientPrms.cacheConfig);
    String regionConfig = conftab.stringAt(CacheClientPrms.regionConfig);

    WANClient client = new WANClient();
    client.createCache(cacheConfig);
    Region region = client.createRegion(REGION_NAME, regionConfig);

    // initialize CQService, if needed
    CQUtil.initialize();
    CQUtil.initializeCQService();
    CQUtil.registerCQ(region);

  }

  /**
   * Initializes an ML edge cache based on the {@link CacheClientPrms}.
   */
  public static void initMLEdgeClientCacheTask() {
    String cacheConfig = conftab.stringAt(CacheClientPrms.cacheConfig);
    String regionConfig = conftab.stringAt(CacheClientPrms.regionConfig);

    WANClient client = new WANClient();
    client.createCache(cacheConfig);
    Region tradesRegion = client.createRegion(TRADES_REGION_NAME, regionConfig);
    client.registerInterest(tradesRegion);
    Region quotesRegion = client.createRegion(QUOTES_REGION_NAME, regionConfig);
    client.registerInterest(quotesRegion);
  }

  /** Creates a cache. */
  protected Cache createCache(String cacheConfig) {
    return CacheHelper.createCache(cacheConfig);
  }

  /** Creates a region */
  protected Region createRegion(String regionName, String regionConfig) {
    return RegionHelper.createRegion(regionName, regionConfig);
  }

  /**
   * Registers interest in all keys using the client interest policy.
   */
  private void registerInterest(Region region) {
    InterestResultPolicy interestPolicy = CacheClientPrms.getInterestPolicy();
    LocalRegion localRegion = (LocalRegion)region;
   String VmDurableId = ((InternalDistributedSystem)InternalDistributedSystem
        .getAnyInstance()).getConfig().getDurableClientId();

    if (!VmDurableId.equals("")) {
      Log.getLogWriter().info("Doing durable register interest");
      localRegion.registerInterest("ALL_KEYS", interestPolicy, true);
    }
    else {
      localRegion.registerInterest("ALL_KEYS", interestPolicy);
    }
    Log.getLogWriter().info("Initialized region " + region
       + "\nRegistered interest in ALL_KEYS with InterestResultPolicy = "
       + interestPolicy);
  }
  
  /** Starts a bridge server. */
  protected CacheServer startBridgeServer(String bridgeConfig) {
    return BridgeHelper.startBridgeServer(bridgeConfig);
  }

  /**
   * Starts a thread to monitor queues on the cache.  Should only be
   * invoked from gateway hubs.
   */
  protected void startQueueMonitor() {
    final StatisticsFactory statFactory =
          DistributedSystemHelper.getDistributedSystem();
    final String key = "EVENT_QUEUE_SIZE: for vm_" + RemoteTestModule.getMyVmid();
    Log.getLogWriter().info("Started event queue monitor with key: " + key);
    final SharedMap bb = WANBlackboard.getInstance().getSharedMap();
    Thread queueMonitor = new Thread(new Runnable() {

      public void run() {
        Statistics[] gStats = statFactory.findStatisticsByType(statFactory.findType(GatewayStats.typeName));
        boolean running = true;
        long lastQSize = -1;
        while(serverAlive) {
          SystemFailure.checkFailure(); // GemStoneAddition
          long qSize = 0;
          for(int i=0;i<gStats.length;i++) {
            long gQSize = gStats[i].getInt(GatewayStats.getEventQueueSizeId());
            qSize+=gQSize;
          }

          try {
            bb.put(key, new Long(qSize));
            //if (qSize != lastQSize) {
            //  logger.severe("updating ["+key+"]="+qSize);
            //  lastQSize = qSize;
            //}
          } catch(Exception e) {
            e.printStackTrace();
          }
          if(serverAlive) {
            try {
              Thread.sleep(1000);
            } catch(Exception e) {
              e.printStackTrace();
            }
          }
        }
      }

      protected void finalize() throws Throwable {
        // TODO Auto-generated method stub
        Object o = bb.remove(key);
        logger.severe("REMOVING BBKEY2["+key+"] value was:"+o);
        super.finalize();
      }

    }, "Gateway Queue Monitor");
    queueMonitor.setDaemon(true);
    queueMonitor.start();
  }

  /**
   * Creates a gateway hub using the {@link CacheServerPrms}.
   */
  protected void createGatewayHub() {
    String gatewayHubConfig = TestConfig.tasktab().stringAt(CacheServerPrms.gatewayHubConfig, conftab.stringAt(CacheServerPrms.gatewayHubConfig, null));
    if (gatewayHubConfig != null) {
      GatewayHubHelper.createGatewayHub(gatewayHubConfig);
    }
  }

  /**
   * Starts a gateway hub in a VM that previously created one, after creating
   * gateways.
   */
  protected void startGatewayHub(String gatewayConfig) {
    GatewayHubHelper.addGateways(gatewayConfig);
    GatewayHubHelper.startGatewayHub();
  }

  //============================================================================
  // TASKS
  //============================================================================

  /**
   * Puts objects into the cache.  Each logical hydra client puts an object
   * over and over at its own key.  The key is the hydra threadgroup id, which
   * runs from 0 to the size of the threadgroup, and is unique for each thread.
   * Sleeps a configurable amount of time before each operation.
   */
  public static void putTask() {
    PdxTest.initClassLoader();
    startNoKillZone();
    WANClient client = new WANClient();
    client.put();
    endNoKillZone();
  }

  /** Puts objects into the cache. */
  private void put() {
    Region region = RegionHelper.getRegion(REGION_NAME);
    int tid = RemoteTestModule.getCurrentThread().getThreadGroupId();
    Integer key = new Integer(tid);
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    String objectType = CacheClientPrms.getObjectType();
    for (int i = 1; i <= ITERATIONS; i++) {
      MasterController.sleepForMs(sleepMs);
      Object value = null;
      if (objectType == null) {
        value = new Integer(i);
      } else if ((objectType.equals("util.PdxVersionedValueHolder")) ||
                 (objectType.equals("util.VersionedValueHolder"))) {
        value = PdxTest.getVersionedValueHolder(objectType, new Integer(i), rv);
      } else {
        throw new TestException("Unknown objectType: " + objectType);
      }
      WANClientVersionHelper.updateEntry(region, key, value);
    }
    WANBlackboard.getInstance().getSharedCounters()
    .setIfLarger(WANBlackboard.MaxKeys, tid + 1);
  }

  /** 
   * Creates new objects in the cache with a site-globally increasing shared
   * counter as the key, using putAll.  Each site creates the same keys.
   */
  public static void createSequentialKeysUsingPutAllTask()
  throws Exception {
    try {
      startNoKillZone();
      Region region = RegionHelper.getRegion(REGION_NAME);    
      int batchSize = CacheClientPrms.getBatchSize();
      int i = 0;
      while (i < batchSize) {
        i += createSequentialKeyUsingPutAll(region);
      }
    } finally {
      endNoKillZone();
    }
  }

  /**
   * Creates new objects in the cache with a site-globally increasing shared
   * counter as the key using putAll.  Each site creates the same keys.
   * Returns the number of entries created.
   */
  private static int createSequentialKeyUsingPutAll(Region region)
  throws Exception{
    int numEntries = CacheClientPrms.getNumEntries();
    int mapSize = CacheClientPrms.getNumPutAllEntries();
    if (Log.getLogWriter().fineEnabled()) {
      Log.getLogWriter().fine("PutAll with map size of " + mapSize);
    }

    // compute the current entry count for this wan site
    long ec = -1;
    SharedCounters sc = WANBlackboard.getInstance().getSharedCounters();
    int wanSite = EdgeHelper.toWanSite(RemoteTestModule.getMyClientName());
    switch (wanSite) {
      case 1:  ec = sc.add(WANBlackboard.currentEntry1, mapSize); break;
      case 2:  ec = sc.add(WANBlackboard.currentEntry2, mapSize); break;
      case 3:  ec = sc.add(WANBlackboard.currentEntry3, mapSize); break;
      case 4:  ec = sc.add(WANBlackboard.currentEntry4, mapSize); break;
      case 5:  ec = sc.add(WANBlackboard.currentEntry5, mapSize); break;
      case 6:  ec = sc.add(WANBlackboard.currentEntry6, mapSize); break;
      default: String s = "Too many WAN sites: " + wanSite;
               throw new UnsupportedOperationException(s);
    }
    int entryCounter = (int)ec;

    HashMap aMap = new HashMap();
    String objectType = CacheClientPrms.getObjectType();
    for (int i = entryCounter - mapSize + 1; i <= entryCounter; i++) {
      if (i > numEntries) {
        String s = "Created at least " + numEntries + " entries";
        throw new StopSchedulingTaskOnClientOrder(s);
      }
      Object val = null;
      if (objectType == null) {
        val = Integer.valueOf(i);
      } else {
        val = ObjectHelper.createObject(objectType, (int)i);
      }
      String key = String.valueOf(i);
      aMap.put(key, val);
    }
    region.putAll(aMap);
    return aMap.size();
  }

  /**
   * Task to {@link #putSequentialKeysTask} then {@link #waitForQueuesToDrain}.
   */
  public static void putSequentialKeysWithDrainTask() throws Exception {
    putSequentialKeysTask();
    waitForQueuesToDrain();
  }

  /** Puts integers into the cache with a globally increasing shared counter as the key. Does 1000 updates on each key. */
  public static void putSequentialKeysTask() throws Exception {
     WANClient client = new WANClient();
     WANClientVersionHelper.putSequentialKeysTask(client);
  }
  
  public static void putSequentialKeyUsingPutAll(Region region, int sleepMs) throws Exception{
    int minMapSize = 1;
    int maxMapSize = 5;

    // while tx ops are restricted to colocated entries, force mapSize to 1
    boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
    if (useTransactions) {
      maxMapSize = 1;
    }

    int mapSize = rand.nextInt(maxMapSize-minMapSize) + minMapSize;
    Log.getLogWriter().info("PutAll with the map size of " + mapSize);
    
    HashMap aMap = new HashMap();
    long entryCounter =  WANBlackboard.getInstance().getSharedCounters().add(WANBlackboard.currentEntry, mapSize);
    String objectType = CacheClientPrms.getObjectType();
    for (int i =1; i<= ITERATIONS; i++) {
      MasterController.sleepForMs(sleepMs);
      for (long j= entryCounter-mapSize + 1; j <= entryCounter; j++) {
        Object key = ""+j;
        if ((objectType != null) && (objectType.equals("util.PdxVersionedValueHolder") ||
                   (objectType.equals("util.VersionedValueHolder")))) {
          BaseValueHolder vh = PdxTest.getVersionedValueHolder(objectType, new Integer(i), rv);
          aMap.put(key, vh);
        } else {
          aMap.put(key, new Integer(i));
        }
      }
      region.putAll(aMap);
    }
  }
  
  /**
   * Same as the putSequentialKeysTask() but with the keys prefixed with "valid"
   */
  public static void putSequentialKeysTaskForValid() throws Exception {
    startNoKillZone();
    Region region = RegionHelper.getRegion(REGION_NAME);
    String key = "valid_"
        + WANBlackboard.getInstance().getSharedCounters().incrementAndRead(
            WANBlackboard.currentEntry_valid);
    Log.getLogWriter().info("The vm will be operating on the key : " + key);
   
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    for (int i = 1; i <= ITERATIONS; i++) {
      MasterController.sleepForMs(sleepMs);
      WANClientVersionHelper.updateEntry(region, key, new Integer(i));
    }
    endNoKillZone();
  }

  /**
   * Same as the putSequentialKeysTask() but with the keys prefixed with
   * "invalid"
   */
  public static void putSequentialKeysTaskForInValid() throws Exception {
    startNoKillZone();
    Region region = RegionHelper.getRegion(REGION_NAME);
    String key = "invalid_"
        + WANBlackboard.getInstance().getSharedCounters().incrementAndRead(
            WANBlackboard.currentEntry_invalid);
    Log.getLogWriter().info("The vm will be operating on the key : " + key);

    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    for (int i = 1; i <= ITERATIONS; i++) {
      MasterController.sleepForMs(sleepMs);
      WANClientVersionHelper.updateEntry(region, key, new Integer(i));
    }
    endNoKillZone();
  }
  
  /**
   * Same as the putSequentialKeysTask() but with the keys prefixed with
   * "reader" Adds the key to the ArrayList
   */
  public static void putSequentialKeysTaskForReader() throws Exception {
    startNoKillZone();
    Region region = RegionHelper.getRegion(REGION_NAME);
    String key = "reader_"
        + WANBlackboard.getInstance().getSharedCounters().incrementAndRead(
            WANBlackboard.currentEntry_reader);
    Log.getLogWriter().info("The vm will be operating on the key : " + key);
    keyList.add(key);

    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    for (int i = 1; i <= ITERATIONS; i++) {
      MasterController.sleepForMs(sleepMs);
      WANClientVersionHelper.updateEntry(region, key, new Integer(i));
    }
    endNoKillZone();
  }

  /**
   * Same as the putSequentialKeysTask() but with the keys prefixed with
   * "writer" Adds the key to the ArrayList
   */
  public static void putSequentialKeysTaskForWriter() throws Exception {
    startNoKillZone();
    Region region = RegionHelper.getRegion(REGION_NAME);
    String key = "writer_"
        + WANBlackboard.getInstance().getSharedCounters().incrementAndRead(
            WANBlackboard.currentEntry_writer);
    Log.getLogWriter().info("The vm will be operating on the key : " + key);
    keyList.add(key);

    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    for (int i = 1; i <= ITERATIONS; i++) {
      MasterController.sleepForMs(sleepMs);
      WANClientVersionHelper.updateEntry(region, key, new Integer(i));
    }
    endNoKillZone();
  }
  
  private static Endpoint getEndpoint(ServerLocation location) {
    List endpoints = BridgeHelper.getEndpoints();
    
    InetSocketAddress ia = null;
    for(Iterator itr = endpoints.iterator(); itr.hasNext(); ) {
      Endpoint next = (Endpoint) itr.next();
      if(next.getPort() == location.getPort()) {
        if(next.getHost().equals(location.getHostName())) {
          return next;
        } else {
          try {
            ia = new InetSocketAddress(location.getHostName(),location.getPort());
            if(ia.getAddress().getHostAddress().equals(next.getAddress())) {
              return next;
            }
          } catch(Exception e) {
            // continue
            e.printStackTrace();
          }
        }
      } 
    }
    return null;
  }
 
  /**
   * Clients doing a putSequentialTask after checking whether the primary is a
   * valid or invalid server
   * 
   */

  public static void clientPutSequentialKeysTask() throws Exception {
    Region region = RegionHelper.getRegion(REGION_NAME);
    PoolImpl mybw = ClientHelper.getPool(region);
    ServerLocation primary = mybw.getPrimary();
    if(primary == null) {
      throw new InternalGemFireException("Primary is null" + primary);
    }
    
    Endpoint primaryEndpoint = getEndpoint(primary);

    if(primaryEndpoint == null) {
      throw new InternalGemFireException("Unable to find endpoint for primary " + primary);
    }
    Log.getLogWriter().info("Primary name is " + primaryEndpoint.getName());
    if (primaryEndpoint.getName().indexOf("_validbridge_") != -1) {
      putSequentialKeysTaskForValid();
    }
    else if (primaryEndpoint.getName().indexOf("_invalidbridge_") != -1) {
      putSequentialKeysTaskForInValid();
    }
  }

  /** Puts byte[] into the cache with a globally increasing shared counter as the key. Does 1000 updates on each key. The byte array size reflects which update it is. */
  public static void putSequentialKeysWithBytesTask() throws Exception {
    startNoKillZone();
    Region region = RegionHelper.getRegion(REGION_NAME);
    String key = ""+WANBlackboard.getInstance().getSharedCounters().incrementAndRead(WANBlackboard.currentEntry);
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    for (int i = 1; i <= ITERATIONS; i++) {
      MasterController.sleepForMs(sleepMs);
      WANClientVersionHelper.updateEntry(region, key, new byte[i]);
    }
    endNoKillZone();
  }

  /** Populates REGION_NAME with numEntries objects */
  public static void populateRegionTask() {
    Region region = RegionHelper.getRegion(REGION_NAME);
    int numEntries = CacheClientPrms.getNumEntries();
    for (int i = 0; i < numEntries; i++) {
      Object key = NameFactory.getNextPositiveObjectName();
      if (TestConfig.tab().getRandGen().nextBoolean()) {
      region.put( key, new Long( NameFactory.getCounterForName( key ) ) );
      } else {
        region.putIfAbsent( key, new Long( NameFactory.getCounterForName( key ) ) );
    }
    }
    Log.getLogWriter().info("populated cache with " + NameFactory.getPositiveNameCounter() + " keys");
    // Allow distribution of updates
    MasterController.sleepForMs( 60000 );
  }

  /** Destroys numEntries randomKeys in REGION_NAME */
  public static void destroyRandomEntriesTask() {
    Region region = RegionHelper.getRegion(REGION_NAME);
    Set aSet = region.keys();

    if (aSet.size() == 0) {
       Log.getLogWriter().info("destroyRandomEntryTask: No keys in region");
       return;
    }

    int numEntries = CacheClientPrms.getNumEntries();
    for (int i = 0; i < numEntries; i++) {
      aSet = region.keys();
      if (aSet.size() == 0) {
        Log.getLogWriter().info("destroyRandomEntriesTask: No keys remain to destroy");
        return;
      }

      Object[] keyList = aSet.toArray();
      int index = rand.nextInt(aSet.size() - 1);
      Log.getLogWriter().info("Number of keys in region = " + aSet.size());
      try {
         Object key = keyList[index];
         Log.getLogWriter().info("Destroying key " + key + " from region " + region.getName());
         if (TestConfig.tab().getRandGen().nextBoolean()) {
         region.destroy(key);
         } else {
           boolean removed = region.remove(key, region.get(key));
           if (!removed) { // might happen with concurrent execution, force destroy
             region.destroy(key);
           }
         }
         Log.getLogWriter().info("Done destroying key " + key + " from region " + region.getName());
      } catch (Exception e) {
         throw new TestException("destroyRandomEntryTask throws " + e + " " + TestHelper.getStackTrace(e));
      }
    }

    // allow time for distribution
    int sleepMs = CacheClientPrms.getSleepSec() * 1000;
    MasterController.sleepForMs( sleepMs );
  }

  /** perform updates */
  public static void updateEntries() {
    Region region = RegionHelper.getRegion(REGION_NAME);
    Set aSet = region.keys();
    if (aSet.size() == 0) {
       Log.getLogWriter().info("updateEntries: No keys in region");
       return;
    }
    Object[] keyList = aSet.toArray();
    int index = rand.nextInt(aSet.size() - 1);
    Log.getLogWriter().info("Number of keys in region = " + aSet.size());
    try {
       Object key = keyList[index];
       Log.getLogWriter().info("Updating " + key + " in region " + region.getName());
       Long val = (Long)region.get(key);
       WANClientVersionHelper.updateEntry(region, key, new Long(val.intValue()+1));
       Log.getLogWriter().info("Done updating key " + key + " in region " + region.getName());
    } catch (Exception e) {
       throw new TestException("updateEntries throws " + e + " " + TestHelper.getStackTrace(e));
    }
  }

  public static void tradeFeederTask() {
    int tid = RemoteTestModule.getCurrentThread().getThreadGroupId();
    int tradesToProcess = MLPrms.getTradesToProcess();
    int tradeStartId = (tradesToProcess*tid) + 1;
    int tradeFileStartLine = tradeStartId;
    int tradeFileEndLine = (tid+1) * tradesToProcess;
    System.out.println("Starting trade feeder");
    System.out.println("tradesToProcess: " + MLPrms.getTradesToProcess());
    System.out.println("tradeStartId: " + tradeStartId);
    System.out.println("tradeFileStartLine: " + tradeFileStartLine);
    System.out.println("tradeFileEndLine: " + tradeFileEndLine);
    String tradeStartIdStr = String.valueOf(tradeStartId);
    String tradeFileStartLineStr = String.valueOf(tradeFileStartLine);
    String tradeFileEndLineStr = String.valueOf(tradeFileEndLine);
    String tradesPerSecondStr = String.valueOf(MLPrms.getTradesPerSecond());
    String tradeFile = MLPrms.getTradesDataFile();
    GemFireTradeFeeder.main(new String[]
    {
      tradeStartIdStr, tradeFileStartLineStr, tradeFileEndLineStr, tradesPerSecondStr, tradeFile
    });
  }

  public static void tradeBurstFeederTask() {
    // The number of trade feeders is 5. Is there a way to determine this in code?
    int tradeFeeders = 5;
    int tradesToProcess = MLPrms.getTradesToProcess();
    int tradeStartId = (tradesToProcess*tradeFeeders) + 1;
    int tradeFileStartLine = tradeStartId;
    // Make 5000 trades available to the burster so there are plenty of trades to burst
    int tradeFileEndLine = tradeStartId + 5000;
    System.out.println("Starting trade burst feeder");
    System.out.println("tradesToProcess: " + MLPrms.getTradesToProcess());
    System.out.println("tradeStartId: " + tradeStartId);
    System.out.println("tradeFileStartLine: " + tradeFileStartLine);
    System.out.println("tradeFileEndLine: " + tradeFileEndLine);
    String tradeStartIdStr = String.valueOf(tradeStartId);
    String tradeFileStartLineStr = String.valueOf(tradeFileStartLine);
    String tradeFileEndLineStr = String.valueOf(tradeFileEndLine);
    String burstTradesPerSecondStr = String.valueOf(MLPrms.getBurstTradesPerSecond());
    String burstSleepIntervalStr = String.valueOf(MLPrms.getBurstSleepInterval());
    String burstTimeStr = String.valueOf(MLPrms.getBurstTime());
    String tradeFile = MLPrms.getTradesDataFile();
    GemFireTradeBurstFeeder.main(new String[]
    {
      tradeStartIdStr, tradeFileStartLineStr, tradeFileEndLineStr, burstTradesPerSecondStr, tradeFile, burstSleepIntervalStr, burstTimeStr
    });
  }

  public static void quoteFeederTask() {
    String quotesToProcessStr = String.valueOf(MLPrms.getQuotesToProcess());
    String quotesPerSecondStr = String.valueOf(MLPrms.getQuotesPerSecond());
    String quoteFile = MLPrms.getQuotesDataFile();
    GemFireQuoteFeeder.main(new String[] {quotesToProcessStr, quotesPerSecondStr, quoteFile});
  }

  //============================================================================
  // CLOSETASKS
  //============================================================================

  /**
   * Prints the contents of the ML client cache as-is.
   */
  public static void printMLTask() {
    // sleep to allow all callbacks to complete
    MasterController.sleepForMs(5000);
    WANClient client = new WANClient();
    client.print(TRADES_REGION_NAME);
    client.print(QUOTES_REGION_NAME);
  }

  /**
   * Prints the contents of the client cache as-is.
   */
  public static void printTask() {
    PdxTest.initClassLoader();
    // sleep to allow all callbacks to complete
    MasterController.sleepForMs(5000);
    WANClient client = new WANClient();
    client.print(REGION_NAME);
  }

  /** Prints the contents of the local cache. */
  protected void print(String regionName) {
    // get the number of cache entries (one for each thread in the group)
    long size = WANBlackboard.getInstance().getSharedCounters()
                             .read(WANBlackboard.MaxKeys);

    // print the value of each entry in the local cache
    StringBuffer buffer = new StringBuffer();
    Region region = RegionHelper.getRegion(regionName);
    buffer.append("Contents of region");
    buffer.append(region.getFullPath());
    buffer.append(":");
    for (int i = 0; i < size; i++) {
      Integer key = new Integer(i);
      // use hook to do local get (in clients)
      Object val = DiskRegUtil.getValueInVM(region, key);
      // special case w/partitionedRegions (servers)
      if (region.getAttributes().getPartitionAttributes() != null) {
        val = region.get(key);
      }
      if (val != null) {
        buffer.append("\n\tENTRY ");
        buffer.append(key);
        buffer.append(":");
        buffer.append(val);
      }
    }
    Log.getLogWriter().info(buffer.toString());
  }


  /** Prints the contents of the local cache. */
  public static void printSequentialKeysTask() throws Exception {
    PdxTest.initClassLoader();
    // get the number of cache entries (one for each thread in the group)
    long size = WANBlackboard.getInstance().getSharedCounters()
                             .read(WANBlackboard.MaxKeys);

    Region region = RegionHelper.getRegion(REGION_NAME);
    // print the value of each entry in the local cache
    StringBuffer buffer = new StringBuffer();
    buffer.append("Contents of region");
    buffer.append(region.getFullPath());
    buffer.append(":");

    Set keys = region.keys();
    Iterator kI = keys.iterator();
    while(kI.hasNext()) {
      Object key = kI.next();
      Object val = DiskRegUtil.getValueInVM(region, key);
        if (val != null) {
          buffer.append("\n\tENTRY ");
          buffer.append(key);
          buffer.append(":");
          buffer.append(val);
        }
    }
    Log.getLogWriter().info(buffer.toString());
  }
  
  /**
   * Prints the keyList (ArrayList
   */
  public static void printKeyListTask() {
    Iterator Itr = keyList.iterator();
    while (Itr.hasNext()) {
      Log.getLogWriter().info((String)Itr.next());
    }
  }

  /**
   * Checks that all client caches see the same final result, being careful to
   * only look at the cache as-is.
   * <p>
   * Assumes no two writers ever write the same key concurrently; all writers
   * are in the same thread group, and write only to the key with their hydra
   * threadgroup id; this task is executed by the same threadgroup that did the
   * writing; and all clients are fully mirrored.
   */
  public static void validateTask() throws Exception {
    PdxTest.initClassLoader();
    WANClient client = new WANClient();
    int minutesToWait = 5; // for gateway queues to drain
    for (int i = 1; i <= minutesToWait; i++) {
      try {
        Thread.interrupted();
        MasterController.sleepForMs(60000); // one minute
        client.validate(REGION_NAME);
        return; // success
      } catch (TestException e) {
        if (e.getMessage().startsWith("Wrong value")) {
          // gateway queues could still be draining
          if (i == minutesToWait) {
            throw e; // tired of waiting for queues to drain
          } else { // give queues more time to drain
            logger.info("Giving gateway queues more time to drain: " + e.getMessage());
          }
        } else {
          throw e; // failure
        }
      }
    }
  }

  public static void validateMLTask() throws Exception {
    WANClient client = new WANClient();
    client.validate(TRADES_REGION_NAME);
    client.validate(QUOTES_REGION_NAME);
  }

  /**
   * Closes an edge cache.
   */
  public static synchronized void closeEdgeClientCacheTask() {
    Cache cache = CacheHelper.getCache();
    if (cache != null ) {  // another thread has closed the cache
      Region region = RegionHelper.getRegion(REGION_NAME);
      if (region != null) {
        Set keySet = region.keys();
        int numEntries = keySet.size();
        Log.getLogWriter().info("Client's region " + REGION_NAME + " contains " + numEntries + " entries");

        // Don't close the cache if we're using partitionedRegions, we need to allow
        // all servers to complete validation prior to cache close so we don't
        // lose any portion of the PR
        if (region.getAttributes().getPartitionAttributes() == null) {
           CacheHelper.closeCache();
        }
      }
    }
  }

  /**
   * Writes the regionSize to the sharedMap in the BB (distinguished by
   * clientName).  An ENDTASK is required to validate the regionSizes
   * at test end.
   *
   * see WANBlackboard.REGION_SIZE
   */
  public static void HydraCloseTask_regionSizeToBB() {
    String clientName = System.getProperty(ClientPrms.CLIENT_NAME_PROPERTY);
    Cache cache = CacheHelper.getCache();
    if (cache != null ) {  // another thread has closed the cache
      Region region = RegionHelper.getRegion(REGION_NAME);
      Set myKeys = region.keys();
      Log.getLogWriter().info("Region " + region.getName() + " contains " + myKeys.size() + " entries");

      // write this to the BB for the ENDTASK to use for validation
      Object mapKey = clientName + "_" + WANBlackboard.REGION_SIZE;
      WANBlackboard.getInstance().getSharedMap().put(mapKey, new Integer(myKeys.size()));
      CacheHelper.closeCache();
    }
  }

  /**
   * Compares the regionSizes written to the sharedMap for the specified
   * servers.
   *
   * @see CacheClientPrms-clientsToCompare
   */
  public static void HydraEndTask_regionSizesEqual() {
    Integer targetRegionSize = null;
    Vector clientsToCompare = TestConfig.tab().vecAt( CacheClientPrms.clientsToCompare );
    for (int i = 0; i < clientsToCompare.size(); i++) {
      String clientName = (String)clientsToCompare.elementAt(i);
      Object mapKey = clientName + "_" + WANBlackboard.REGION_SIZE;
      Integer regionSize = (Integer)WANBlackboard.getInstance().getSharedMap().get(mapKey);
      Log.getLogWriter().info("RegionSize for " + clientName + " = " + regionSize);
      if (i==0) {
        targetRegionSize = regionSize;
      } else {
        if (!regionSize.equals(targetRegionSize)) {
          throw new TestException("Expected regionSize " + targetRegionSize + " but " + clientName + " has a region size of " + regionSize);
        }
      }
    }
  }

  /**
   * Validates and closes a server cache.
   */
  public static void updateBBandCloseServerCacheTask() throws Exception {
    if (CacheHelper.getCache() == null) {  // closed by another thread
      return;
    }

    // sleep to allow all callbacks to complete
    //MasterController.sleepForMs(30000);
    long serverNumber = WANBlackboard.getInstance().getSharedCounters().incrementAndRead(WANBlackboard.bridgeCloserNumber);
    Log.getLogWriter().info("BridgeCloserNumber " + serverNumber + ": Updating BB and Closing server cache");

    Region region = RegionHelper.getRegion(REGION_NAME);
    Set myKeys = region.keys();
    ArrayList aList = new ArrayList( myKeys );
    Log.getLogWriter().info("Region " + region.getName() + " contains " + myKeys.size() + " entries");

    SharedMap map = WANBlackboard.getInstance().getSharedMap();
    SharedCounters sc = WANBlackboard.getInstance().getSharedCounters();
    if (serverNumber == 1) {  // I'm the first one ... push my region info to the BB
      map.put(WANBlackboard.REGION_SIZE, new Integer(myKeys.size()));
      map.put(WANBlackboard.KEY_LIST, aList);
      sc.increment(WANBlackboard.keyListReady);
    } else {   // validate my region info against server #1's
      while (sc.read(WANBlackboard.keyListReady) != 1) {
         MasterController.sleepForMs(250);
      }
      Integer expectedRegionSize = (Integer)map.get(WANBlackboard.REGION_SIZE);
      if (myKeys.size() != expectedRegionSize.intValue()) {
        List expectedKeySet = (List)map.get(WANBlackboard.KEY_LIST);
        throw new TestException("Expected " + expectedRegionSize + " keys, but found " + myKeys.size() + " entries");
      }

      // check for correct keys
      List expectedKeys = (List)map.get(WANBlackboard.KEY_LIST);
      if (!myKeys.containsAll(expectedKeys)) {
        for (Iterator iter = myKeys.iterator(); iter.hasNext();) {
          Object key = iter.next();
          if (!expectedKeys.contains(key)) {
            Log.getLogWriter().info("myKeys = " + myKeys.toString());
            Log.getLogWriter().info("expectedKeys = " + expectedKeys.toString());
            throw new TestException("This VM's region contains the key " + key + " but it was not in the keyList from server publishing to the BB");
          }
        }
      }

      if (!expectedKeys.containsAll(myKeys)) {
        for (Iterator iter = expectedKeys.iterator(); iter.hasNext();) {
          Object key = iter.next();
          if (!myKeys.contains(key)) {
            Log.getLogWriter().info("myKeys = " + myKeys.toString());
            Log.getLogWriter().info("expectedKeys = " + expectedKeys.toString());
            throw new TestException("The server publishing to the BB has key " + key + " but it was not in the keyList for this VM's region");
          }
        }
      }
    }

    WANBlackboard.getInstance().printSharedMap();
 
    WANClient client = new WANClient();
    client.print(REGION_NAME);
    client.closeCache();
  }

  /**
   * Validates and closes a peer cache.
   */
  public static void closePeerCacheTask() throws Exception {
    closeEdgeClientCacheTask(); // this does the trick
  }

  /**
   * Validates and closes a peer cache.
   */
  public static void closePeerCacheRandomKeysTask() throws Exception {
    WANClient client = new WANClient();
    client.closeCache();
  }

  /**
   * Validates and closes a server cache.
   */
  public static void closeServerCacheTask() throws Exception {
    // sleep to allow all callbacks to complete
    //MasterController.sleepForMs(30000);
    Log.getLogWriter().info("Closing server cache");

    WANClient client = new WANClient();
    client.print(REGION_NAME);
    client.validate(REGION_NAME);
    client.closeCache();
  }

  public static void closeMLServerCacheTask() throws Exception {
    // sleep to allow all callbacks to complete
    MasterController.sleepForMs(30000);
    Log.getLogWriter().info("Closing server cache");

    WANClient client = new WANClient();
    client.print(TRADES_REGION_NAME);
    client.print(QUOTES_REGION_NAME);
    client.validate(TRADES_REGION_NAME);
    client.validate(QUOTES_REGION_NAME);
    client.closeCache();
  }

  /**
   * Validates and closes a server cache.
   */
  public static void closeServerCacheRandomKeysTask() throws Exception {
    printSequentialKeysTask();
    Log.getLogWriter().info("Closing server cache");
    validateSequentialKeysTask();
    WANClient client = new WANClient();
    client.closeCache();
  }


  /** Validates the contents of the local cache. */
  private void validate(String regionName) throws CacheException {
    // get the number of cache entries (one for each thread in the group)
    long size = WANBlackboard.getInstance().getSharedCounters().read(WANBlackboard.MaxKeys);

    Log.getLogWriter().info("Validating cache, maxKeys = " + size);

    // check the value of each entry in the local cache
    Region region = RegionHelper.getRegion(regionName);
    if (region == null) {
      throw new TestException("Region " + regionName + " is null, perhaps cache is closed");
    }
    for (int i=0; i < size; i++) {
      Integer key = new Integer(i);
      // use hook to do local get
      Object val = region.get(key);//DiskRegUtil.getValueInVM(region, key);
      if (val == null) {
        String s = "No value in cache at " + key;
        throw new TestException(s);
      } else if (val instanceof Integer) {
        int ival = ((Integer)val).intValue();
        if (ival != ITERATIONS) {
          String s = "Wrong value in cache at " + key +", expected "
                   + ITERATIONS + " but got " + ival;
          throw new TestException(s);
        }
      } else if ((val instanceof BaseValueHolder) ||
                 (val instanceof PdxInstance)) {
        val = PdxTest.toValueHolder(val);
        BaseValueHolder vh = (BaseValueHolder)val;
        int ival = (Integer)(vh.myValue);
        if (ival != ITERATIONS) {
          String s = "Wrong value in cache at " + key +", expected "
                   + ITERATIONS + " but got " + ival;
          throw new TestException(s);
        }
        try {
          vh.verifyMyFields((Integer)vh.myValue);
        } catch (TestException e) {
          throw new TestException("While checking key " + key + ": " + e.getMessage());
        }
      } else {
        String s = "Wrong type in cache at " + key +", expected Integer "
                 + "but got " + val + " of type " + val.getClass().getName();
        throw new TestException(s);
      }
    }
    Log.getLogWriter().info("Validated cache");
  }
  
  /** validation for the valid wan clients with the right prefix * */
  public static void validateValidSequentialKeysTask() throws Exception {
    validPrefix = true;
    invalidPrefix = false; // just reconfirming
    validateSequentialKeysTask();
  }

  /** validation for the valid wan clients with the right prefix * */
  public static void validateInvalidSequentialKeysTask() throws Exception {
    validPrefix = false; // just reconfirming
    invalidPrefix = true;
    validateSequentialKeysTask();
  }

  /**
   * validation of the edge clients for the sequential keys after checking for
   * valid and invalid primary servers
   */
  public static void clientValidateSequentialKeysTask() throws Exception {
    Region region = RegionHelper.getRegion(REGION_NAME);
    PoolImpl mybw = ClientHelper.getPool(region);
    ServerLocation primary = mybw.getPrimary();
    if(primary == null) {
      throw new InternalGemFireException("Primary is null" + primary);
    }
    
    Endpoint primaryEndpoint = getEndpoint(primary);

    if(primaryEndpoint == null) {
      throw new InternalGemFireException("Unable to find endpoint for primary " + primary);
    }
    Log.getLogWriter().info("Primary name is " + primaryEndpoint.getName());
    if (primaryEndpoint.getName().indexOf("_validbridge_") != -1) {
      validateValidSequentialKeysTask();
    }
    else if (primaryEndpoint.getName().indexOf("_invalidbridge_") != -1) {
      validateInvalidSequentialKeysTask();
    }
  }
  

  /** Validates the contents of the local cache. */
  public static void validateSequentialKeysTask() throws Exception {
    PdxTest.initClassLoader();
    int minWaitSec = 30;
    Log.getLogWriter().info("Validating cache after sleeping " + minWaitSec + " seconds");
    MasterController.sleepForMs(minWaitSec*1000);

    waitForQueuesToDrain();

    // For client/server tests (with SilenceListener), ensure that 
    // the events are delivered to the clients before validating.
    if (RegionHelper.getRegion(REGION_NAME).getAttributes().getPoolName() != null)
    {
     SilenceListener.waitForSilence(30 /*seconds*/, 1000);
    }

    // get the number of cache entries (one for each thread in the group)
    long size = WANBlackboard.getInstance().getSharedCounters()
                             .read(WANBlackboard.MaxKeys);

    // check the value of each entry in the local cache
    Region region = RegionHelper.getRegion(REGION_NAME);
    Set keys = region.keys();
    /*
    if(keys.size()!=requiredSize) {
      throw new TestException("The region is supposed to have "+requiredSize+" keys, but it has "+keys.size());
    }
    */
    
    long requiredSize = 0;
    String keyPrefix = "";

    if ((validPrefix == false) && ((invalidPrefix == false))) {
      requiredSize = WANBlackboard.getInstance().getSharedCounters().read(
          WANBlackboard.currentEntry);
      keyPrefix = "";
      logger.info("SUPPOSED TO HAVE:" + requiredSize + " DOES HAVE:"
          + keys.size());
    }
    else if ((validPrefix == true) && ((invalidPrefix == false))) {
      requiredSize = WANBlackboard.getInstance().getSharedCounters().read(
          WANBlackboard.currentEntry_valid);
      keyPrefix = "valid_";
      logger.info("SUPPOSED TO HAVE:" + requiredSize + " DOES HAVE:"
          + keys.size());
      checkInvalidKeys(keyPrefix);
    }
    else if ((validPrefix == false) && ((invalidPrefix == true))) {
      requiredSize = WANBlackboard.getInstance().getSharedCounters().read(
          WANBlackboard.currentEntry_invalid);
      keyPrefix = "invalid_";
      // SUPPOSED TO HAVE message is misleading for invalid wan sites because
      // they are may not be connected to each other
      checkInvalidKeys(keyPrefix);
    }

    Log.getLogWriter().info("Prefix is " + keyPrefix);
    
    if (keyPrefix != "invalid_") {
      Log.getLogWriter().info("Entering the validation loop ");
    
    Iterator kI = keys.iterator();
    for(int i=1;i<requiredSize;i++) {
      
      
      Object key = keyPrefix + i;
      Object val = region.get(key);

        if (val == null) {
          String s = "No value in cache at " + key;
          throw new TestException(s);
        } else if (val instanceof Integer) {
          int ival = ((Integer)val).intValue();
          if (ival != ITERATIONS) {
            String s = "Wrong value in cache at " + key +", expected "
                     + ITERATIONS + " but got " + ival;
            throw new TestException(s);
          }
        } else if ((val instanceof BaseValueHolder) ||
                   (val instanceof PdxInstance)) {
          val = PdxTest.toValueHolder(val);
          BaseValueHolder vh = (BaseValueHolder)val;
          int ival = (Integer)(vh.myValue);
          if (ival != ITERATIONS) {
            String s = "Wrong value in cache at " + key +", expected "
                     + ITERATIONS + " but got " + ival;
            throw new TestException(s);
          }
          try {
            vh.verifyMyFields((Integer)vh.myValue);
          } catch (TestException e) {
            throw new TestException("While checking key " + key + ": " + e.getMessage());
          }
        } else if(val instanceof byte[]) {
          if(((byte[])val).length != ITERATIONS) {
            String s = "Wrong value in cache at " + key +", expected byte[] of length "
                + ITERATIONS + " but got length " + ((byte[])val).length;
            throw new TestException(s);
          }
        } else if(val instanceof PSTObject) {
          if(((PSTObject)val).getIndex() != ITERATIONS) {
            String s = "Wrong value in cache at " + key +", expected PSTObject with index "
                + ITERATIONS + " but got index " + ((PSTObject)val).getIndex();
            throw new TestException(s);
          }
        } else {
          String s = "Wrong type in cache at " + key +", expected Integer "
                   + "but got " + val + " of type " + val.getClass().getName();
          throw new TestException(s);
        }
      }
    }
    Log.getLogWriter().info("Validated cache");
  }

  /** Validates the CQEvent counters and SelectResults against expected contents of server region */
  public static void validateSequentialKeysCQTask() throws Exception {
    int minWaitSec = 30;
    Log.getLogWriter().info("Validating cache after sleeping " + minWaitSec + " seconds");
    MasterController.sleepForMs(minWaitSec*1000);

    // get number of registeredCQs (do this before we get SelectResults as this creates another CQ)
    long numCqs = (long)util.TestHelper.getNumCQsCreated();

    waitForQueuesToDrain();

    // Validate EventCounters collected by CQTestListener
    // Expect WanBB.currentEntry * numCQsCreated CQ Create Events
    // Expect (ITERATIONS-1) * WanBB.currentEntry * numCQsCreated CQ Update Events
    // 0 invalidates, destroys and onError invocations
    SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
    long requiredSize = WANBlackboard.getInstance().getSharedCounters().read(WANBlackboard.currentEntry);
    StringBuffer errMsg = new StringBuffer();

    long numCQCreateEvents = sc.read(CQUtilBB.NUM_CREATE);
    TestHelper.waitForCounter(CQUtilBB.getBB(), "NUM_CREATE", CQUtilBB.NUM_CREATE, (requiredSize*numCqs), true, 300000);

    long numCQUpdateEvents = sc.read(CQUtilBB.NUM_UPDATE);
    try {
       TestHelper.waitForCounter(CQUtilBB.getBB(), "NUM_UPDATE", CQUtilBB.NUM_UPDATE, ((ITERATIONS-1)*(requiredSize*numCqs)), true, 180000);
    } catch (TestException e) {
       errMsg.append(e.toString() + "\n");
    }

    // Our CQSequentialValuesListener tracks the missing events
    // The first of which will be included in the TestException;
    // all will be logged in the system logs.  It also tracks
    // the number of skipped updates.
    hydra.blackboard.SharedMap aMap = CQUtilBB.getBB().getSharedMap();
    String exceptionStr = (String)aMap.get(TestHelper.EVENT_ERROR_KEY);
    if (exceptionStr != null) {
       errMsg.append("\nFirst Exception encountered by listener: \n");
       errMsg.append("\t" + exceptionStr + "\n");
       // Log the number of missing and late updates from the listeners
       long missing = sc.read(CQUtilBB.MISSING_UPDATES);
       long late = sc.read(CQUtilBB.LATE_UPDATES);
       errMsg.append("\tTotal count of missingUpdates = " + missing + "\n");
       errMsg.append("\tTotal count of late (OutOfOrder) Updates = " + late + "\n");
    }

    long numCQInvalidateEvents = sc.read(CQUtilBB.NUM_INVALIDATE);
    if (numCQInvalidateEvents != 0) {
       errMsg.append("Expected 0 CQ Invalidate Events, but found " + numCQInvalidateEvents + "\n");
    }

    long numCQDestroyEvents = sc.read(CQUtilBB.NUM_DESTROY);
    if (numCQDestroyEvents != 0) {
       errMsg.append("Expected 0 CQ Destroy Events, but found " + numCQDestroyEvents + "\n");
    }

    long numOnErrorInvocations = sc.read(CQUtilBB.NUM_ERRORS); 
    if (numOnErrorInvocations != 0) {
       errMsg.append("Expected 0 onError() invocations, but found " + numOnErrorInvocations + "\n");
    }

    if (errMsg.length() > 0) {
       String errHdr = "Failures found while verifying CQEvents\n";
       throw new TestException(errHdr + errMsg.toString() + util.TestHelper.getStackTrace());
    }
  
    Log.getLogWriter().info("Successfully validated CQEvents in client");
    StringBuffer aStr = new StringBuffer("CQEvent Summary\n");
    aStr.append("   numCQCreateEvents = " + numCQCreateEvents + "\n");
    aStr.append("   numCQUpdateEvents = " + numCQUpdateEvents + "\n");
    aStr.append("   numCQDestroyEvents = " + numCQDestroyEvents + "\n");
    aStr.append("   numCQInvalidateEvents = " + numCQInvalidateEvents + "\n");
    aStr.append("   numOnErrorInvocations = " + numOnErrorInvocations + "\n");
    Log.getLogWriter().info( aStr.toString() );

    // Now that we've finished processing events, it is safe to get the SelectResults (otherwise, we stop the CQ in order to get the latest SelectResults)
    // check the value of each entry in the SelectResults
    SelectResults results = CQUtil.getCQResults();
    logger.info("SelectResults SUPPOSED TO HAVE:"+requiredSize+" DOES HAVE:"+results.size()+" numCqs = " + numCqs);

    if (results.size() != requiredSize) {
       throw new TestException("Expected SelectResults to be size " + requiredSize + " but found " + results.size() + " elements");
    }

    // traverse Select Results to validate region values are at ITERATOR
    Iterator it = results.iterator();
    while (it.hasNext()) {

        Object val = it.next();
        if (val == null) {
          String s = "Unexpected value " + val + " in SelectResults";
          throw new TestException(s);
        } else if (val instanceof Integer) {
          int ival = ((Integer)val).intValue();
          if (ival != ITERATIONS) {
            String s = "Wrong value in SelectResults, expected "
                     + ITERATIONS + " but got " + ival;
            throw new TestException(s);
          }
        } else if(val instanceof byte[]) {
          if(((byte[])val).length != ITERATIONS) {
            String s = "Wrong value in SelectResults, expected byte[] of length "
                + ITERATIONS + " but got length " + ((byte[])val).length;
            throw new TestException(s);
          }
        } else {
          String s = "Wrong type in SelectResults, expected Integer "
                   + "but got " + val + " of type " + val.getClass().getName();
          throw new TestException(s);
        }
    }
    Log.getLogWriter().info("Successful validation of SelectResults (" + results.size() + "): " + results.toString());

  }

  /**
   * Waits {@link wan.CacheClientPrms#secToWaitForQueue} for queues to drain.
   * Suitable for use as a helper method or hydra task.
   */
  public static void waitForQueuesToDrain() {
    final SharedMap bb = WANBlackboard.getInstance().getSharedMap();
    // Lets check to make sure the queues are empty
    long startTime = System.currentTimeMillis();
    long maxWait = CacheClientPrms.getSecToWaitForQueue();
    long entriesLeft = 0;
    while((System.currentTimeMillis()-startTime)<(maxWait*1000)) {
      Set keySet = bb.getMap().keySet();
      Iterator kIt = keySet.iterator();
      boolean pass = true;
      while(kIt.hasNext()) {
        String k = (String)kIt.next();
        if(k.startsWith("EVENT_QUEUE")) {
          logger.info("Checking event queue key: " + k);
          Long value = (Long)bb.get(k);
          if(value.longValue()>0) {
            pass = false;
            entriesLeft = value.longValue();
            logger.warning("STILL WAITING CUZ QUEUE HAS "+value+" entries in it. ["+k+"]="+value);
            break;
          }
        }
      }
      if(pass) {
        entriesLeft = 0;
        break;
      } else {
        try {
          Thread.sleep(1000);
        } catch(Exception e) {
          e.printStackTrace();
        }
      }
    }
    if(entriesLeft>0) {
      throw new TestException("TIMED OUT waiting for queue to drain. EntriesLeft:"+entriesLeft);
    } else {
      logger.info("QUEUES ARE DRAINED");
    }
  }

  /** Closes a cache. */
  protected void closeCache() {
    // Don't close the cache if we're using partitionedRegions, we need to allow
    // all servers to complete validation prior to cache close so we don't
    // lose any portion of the PR; cache will be closed as the VM is stopped
    Region region = RegionHelper.getRegion(REGION_NAME);
    PartitionAttributes prAttrs = null;
    if (region != null) {
      prAttrs = region.getAttributes().getPartitionAttributes();
    }
    if (prAttrs == null) {
       CacheHelper.closeCache();
    }
  }

  /**
   * A Hydra TASK that stops a specified server
   * see CacheClientPrms.bridgeKillTarget
   */
  public synchronized static void killServer()
  throws ClientVmNotFoundException {
   // kill the specified server
   String server = CacheClientPrms.getBridgeKillTarget();
   if (server == null) {
     throw new TestException("killServer requires bridgeKillTarget (server) to be specified in conf file");
   }
   BridgeHelper.Endpoint endpoint = getBridgeEndpoint(server);
   killComponent("cacheserver", endpoint);

   return;
  }
  
  /**
   * Kill the clients
   */
  public static void killClient() {
    try {
      hydra.MasterController.sleepForMs(1000);
      ClientVmInfo info = ClientVmMgr.stop("Killing the VM",
          ClientVmMgr.MEAN_KILL, ClientVmMgr.IMMEDIATE);
    }
    catch (ClientVmNotFoundException e) {
      Log.getLogWriter().warning(" Exception while killing client ", e);
    }
  }
  
  /**
   * A Hydra TASK that re-starts a specified server
   * see CacheClientPrms.bridgeKillTarget
   */
  public synchronized static void restartServer()
  throws ClientVmNotFoundException {
   String server = CacheClientPrms.getBridgeKillTarget();
   if (server == null) {
     throw new TestException("restartServer requires bridgeKillTarget (server) to be specified in conf file");
   }
   BridgeHelper.Endpoint endpoint = getBridgeEndpoint(server);
   restartComponent("cacheserver", endpoint);
   return;
  }

  /**
   * A Hydra TASK that stops/starts a specified server
   * see CacheClientPrms.bridgeKillTarget
   */
  public synchronized static void recycleServer()
  throws ClientVmNotFoundException {
   // kill the specified server
   String server = CacheClientPrms.getBridgeKillTarget();
   if (server == null) {
     throw new TestException("recycleServer requires bridgeKillTarget (server) to be specified in conf file");
   }
   // bounce the server, sleeping between kill and restart
   int restartWaitSec = TestConfig.tab().intAt( HctPrms.restartWaitSec );
   BridgeHelper.Endpoint endpoint = getBridgeEndpoint(server);
   ClientVmInfo target = new ClientVmInfo(endpoint);
   ClientVmMgr.stop("recycleServer: " + endpoint,
                     ClientVmMgr.MEAN_KILL, ClientVmMgr.ON_DEMAND, target);
   MasterController.sleepForMs(restartWaitSec * 1000);
   ClientVmMgr.start("recycleServer: " + endpoint, target);
  }

  /**
   * A Hydra TASK that chooses a random gateway hub and kills it.
   */
public synchronized static void killGatewayHub()
throws ClientVmNotFoundException {
   BBoard bb = BBoard.getInstance();
   long killInterval = conftab.longAt(HctPrms.killInterval);

   long now = System.currentTimeMillis();
   Long lastKill = (Long) bb.getSharedMap().get("lastKillTime");
   long diff =  0l;

   if (lastKill!=null) {
     diff = now - lastKill.longValue();
     if( diff < killInterval) {
         logger.info ("No kill executed. "+(killInterval-diff)+"ms remaining before next kill.");
         return;
     }

   } else {
     int sleepMs = CacheClientPrms.getSleepSec() * 1000;
     logger.info( "Sleeping for " + sleepMs + "ms" );
     MasterController.sleepForMs( sleepMs );
     bb.getSharedMap().put("lastKillTime", new Long(now));
   }

   // kill self
   startKillZone();
   int restartWaitSec = TestConfig.tab().intAt(HctPrms.restartWaitSec);
   String reason = "Killing self, restarting after " + restartWaitSec + " seconds";
   ClientVmMgr.stop(reason, ClientVmMgr.MEAN_KILL, restartWaitSec*1000);
}

/**
 * Client task for clients that way indefinitely
 *
 * see MasterController.sleepForMs()
 */
public static void waitForEvents() {
   int sleepMs = CacheClientPrms.getSleepSec() * 1000;
   logger.info( "Sleeping for " + sleepMs + "ms" );
   MasterController.sleepForMs( sleepMs );
}

/**
 * Kills a test component: a cache server.
 */
private static void killComponent(String comp, BridgeHelper.Endpoint endpoint)
throws ClientVmNotFoundException {
 if (comp.equals("cacheserver")) {
    ClientVmInfo target = new ClientVmInfo(endpoint);
    ClientVmMgr.stop("Killing cache server",
                ClientVmMgr.MEAN_KILL, ClientVmMgr.ON_DEMAND, target);
    return;
 }
 logger.info("ERROR in killComponent - unknown argument: " + comp);
 throw new HydraRuntimeException("error in killComponent");
}


public static void restartComponent(String comp, BridgeHelper.Endpoint endpoint)
throws ClientVmNotFoundException {
 if (comp.equals("cacheserver")) {
    ClientVmInfo target = new ClientVmInfo(endpoint);
    ClientVmMgr.start("Restarting cache server", target);
    return;
 }
 logger.info("ERROR in restartComponent - unknown argument: " + comp);
 throw new HydraRuntimeException("error in restartComponent");
}

  /**
   * A Hydra TASK that makes the caller increment WANBlackboard.farEndReady.
   * Used for a single thread to synchronize with one other thread in a
   * different VM.
   */
  public static void notifyFarEnd() {
    long val = WANBlackboard.getInstance().getSharedCounters()
                            .incrementAndRead(WANBlackboard.nearEndReady);
    if (val != 1) {
      throw new HydraRuntimeException("Unintended use");
    }
  }

  /**
   * A Hydra TASK that makes the caller increment WANBlackboard.nearEndReady.
   * Used for a single thread to synchronize with one other thread in a
   * different VM.
   */
  public static void notifyNearEnd() {
    long val = WANBlackboard.getInstance().getSharedCounters()
                            .incrementAndRead(WANBlackboard.farEndReady);
    if (val != 1) {
      throw new HydraRuntimeException("Unintended use");
    }
  }

  /**
   * A Hydra TASK that makes the caller wait for WANBlackboard.farEndReady.
   * Used for a single thread to synchronize with one other thread in a
   * different VM.  Polls every second and waits forever.
   */
  public static void waitForFarEnd() {
    SharedCounters counters = WANBlackboard.getInstance().getSharedCounters();
    while (true) {
      MasterController.sleepForMs(1000);
      if (counters.read(WANBlackboard.farEndReady) == 1) {
        long val = counters.decrementAndRead(WANBlackboard.farEndReady);
        if (val != 0) {
          throw new HydraRuntimeException("Unintended use");
        }
        return;
      }
    }
  }
  
  /**
   * Task to wait for the listener silence before doing validation tasks.
   */
  public static void waitForListenerSilence() {
    Log.getLogWriter().info("Waiting for 50 seconds ...");
    hydra.MasterController.sleepForMs(50000);
    long currentTime = System.currentTimeMillis();
    long timeElapsedSinceLastEvent = currentTime - lastEventReceivedTime;
    if (timeElapsedSinceLastEvent > 50000) {
      throw new StopSchedulingTaskOnClientOrder(
          "Stopping the task schedule as the listener was silent for last 50 secs");
    }
    else {
      Log.getLogWriter().info(
          "Task to be rescheduled as listner is still not silent!");
    }
  }
  
  /**
   * Mention the reference vm in the blackboard
   */

  public static void mentionReferenceInBlackboard() {

    String VmDurableId = ((InternalDistributedSystem)InternalDistributedSystem
        .getAnyInstance()).getConfig().getDurableClientId();
    Log.getLogWriter().info("Reference vm is : " + VmDurableId);

    DurableClientsBB.getBB().getSharedMap().put("REFERENCE VM:", VmDurableId);
  }

  /**
   * Validate the events received by the clients with the reference Vm
   */
  public static void validateEventsReceived() {
    // hydra.MasterController.sleepForMs(150000);
    DurableClientsBB.getBB().printSharedMap();
    String ReferenceVm = (String)DurableClientsBB.getBB().getSharedMap().get(
        "REFERENCE VM:");
    String VmDurableId = ((InternalDistributedSystem)InternalDistributedSystem
        .getAnyInstance()).getConfig().getDurableClientId();
    HashMap currentVmMap = (HashMap)DurableClientsBB.getBB().getSharedMap()
        .get(VmDurableId);
    HashMap referenceMap = (HashMap)DurableClientsBB.getBB().getSharedMap()
        .get(ReferenceVm);
    if (currentVmMap.isEmpty()) {
      throw new TestException(" The map of threads is empty for the Vm "
          + VmDurableId);
    }
    if (referenceMap.isEmpty()) {
      throw new TestException(
          " The map of threads is empty for the Reference Vm " + ReferenceVm);
    }
    Iterator iterator = referenceMap.entrySet().iterator();
    Map.Entry entry = null;
    Object key = null;
    Object value = null;
    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      key = entry.getKey();
      value = entry.getValue();
      HashMap referenceThreadMap = (HashMap)value;
      Iterator iterator1 = referenceThreadMap.entrySet().iterator();
      Map.Entry entry1 = null;
      Object key1 = null;
      Object value1 = null;

      while (iterator1.hasNext()) {
        entry1 = (Map.Entry)iterator1.next();
        key1 = entry1.getKey();
        value1 = entry1.getValue();
        if (((String)key1).startsWith("EVENT SR. No : ")) {
          if (currentVmMap.get(key) == null) {
            throw new TestException("Map is null");
          }
          if (((HashMap)currentVmMap.get(key)).get(key1) == null) {

            throw new TestException(
                "Event not received by the client for the key " + key
                    + " for the event no " + key1);
          }
          if (!value1.equals(((HashMap)currentVmMap.get(key)).get(key1))) {
            throw new TestException(" For the key " + key + " the event no "
                + key1 + " has different events");
          }
        }

      }
    }

  }
  
  /**
   * Checking for the security wan sites whether they have received keys not
   * valid to them
   */

  public static void checkInvalidKeys(String validKey) {
    Region region = RegionHelper.getRegion(REGION_NAME);
    Set keys = region.keySet();

    Iterator iterator = keys.iterator();
    while (iterator.hasNext()) {
      String key = (String)(iterator.next());
      if (!key.startsWith(validKey)) {
        throw new TestException("Invalid key found in the cache " + key);
      }
      else {
        Log.getLogWriter().info("Found valid key " + key);
      }
    }

  }
  
  /**
   * Validating Writer Wan Sites
   */
  public static void validateWriterWanSiteEntriesTask() {
    Log.getLogWriter().info("Sleeping for some time ..");
    hydra.MasterController.sleepForMs(100000);
    Region region = RegionHelper.getRegion(REGION_NAME);
    if (region.isEmpty()) {
      throw new TestException(" Region has no entries to validate ");
    }
    checkInvalidKeys("writer_");
    long requiredSize = WANBlackboard.getInstance().getSharedCounters().read(
        WANBlackboard.currentEntry_writer);
    checkKeyRegionEntries("writer_", requiredSize);
  }

  /**
   * Validating Reader Wan Sites
   */
  public static void validateReaderWanSiteEntriesTask() {
    Log.getLogWriter().info("Sleeping for some time ..");
    hydra.MasterController.sleepForMs(100000);
    Region region = RegionHelper.getRegion(REGION_NAME);
    if (region.isEmpty()) {
      throw new TestException(" Region has no entries to validate ");
    }
    long requiredSize = WANBlackboard.getInstance().getSharedCounters().read(
        WANBlackboard.currentEntry_writer);
    checkKeyRegionEntries("writer_", requiredSize);

    Iterator iterator = region.entrySet(false).iterator();
    Region.Entry entry = null;
    Object key = null;
    Object value = null;
    while (iterator.hasNext()) {
      entry = (Region.Entry)iterator.next();
      key = entry.getKey();
      value = entry.getValue();

      if (((String)key).startsWith("reader_")) {
        if (!keyList.contains(key)) {
          throw new TestException(
              "Found reader key that is not present in the keyList");
        }
        else if (((Integer)value).intValue() != ITERATIONS) {
          String s = "Wrong value in cache at " + key + ", expected "
              + ITERATIONS + " but got " + ((Integer)value).intValue();
          throw new TestException(s);
        }

      }

    }
  }

  /**
   * Check whether a vm has expected entries for the key prefix
   * 
   */
  protected static void checkKeyRegionEntries(String keyPrefix,
      long expectedsize) {

    Region region = RegionHelper.getRegion(REGION_NAME);
    Log.getLogWriter().info(
        "Key prefix is " + keyPrefix + " Expected size is " + expectedsize);

    for (int i = 1; i <= expectedsize; i++) {
      String key = keyPrefix + i;
      Object val = region.get(key);

      if (val == null) {
        String s = "No value in cache at " + key;
        throw new TestException(s);
      }
      else if (((Integer)val).intValue() != ITERATIONS) {
        Log.getLogWriter().info(
            "Key is :" + key + " Value found in region is "
                + ((Integer)val).intValue());
        String s = "Wrong value in cache at " + key + ", expected "
            + ITERATIONS + " but got " + ((Integer)val).intValue();
        throw new TestException(s);
      }
      else {
        Log.getLogWriter().info(
            "Key is :" + key + " Value found in region is "
                + ((Integer)val).intValue());
      }
    }

  }

  /**
   * Reader wan site destroys all the keys in its region
   */
  public static void readerDestroyAllKeysTask() {

    Region region = RegionHelper.getRegion(REGION_NAME);

    Iterator iterator = region.entrySet(false).iterator();
    Region.Entry entry = null;
    Object key = null;

    while (iterator.hasNext()) {
      entry = (Region.Entry)iterator.next();
      key = entry.getKey();

      try {
        region.destroy(key);
      }
      catch (EntryNotFoundException e) {
        Log.getLogWriter().info("Entry Not found.");
      }
    }

    if (region.isEmpty()) {
      Log.getLogWriter().info(
          "Completed the destroy operation for all the keys in the region");
    }
    else {
      throw new TestException(
          "Region is supposed to be empty but that is not the case");
    }

  }

  /**
   * Writer vms destroying only those keys that it created
   */
  public static void writerDestroyCreatedKeysTask() {
    Region region = RegionHelper.getRegion(REGION_NAME);

    Iterator iterator = keyList.iterator();
    String key = null;

    while (iterator.hasNext()) {
      try {
        key = (String)iterator.next();
        Log.getLogWriter().info(
            "Destroying key :" + key + " which is present in the keyList");
        region.destroy(key);
      }
      catch (EntryNotFoundException e) {
        Log.getLogWriter().info("Entry Not found.");
      }
      catch (EntryDestroyedException e) {
        Log.getLogWriter().info("Entry Already destroyed.");
      }
    }

  }

  /**
   * Check whether the writer sites are having empty region
   */
  public static void checkWriterRegionContentsEmpty() {

    Region region = RegionHelper.getRegion(REGION_NAME);

    if (region.isEmpty()) {
      Log.getLogWriter().info("Region is empty as expected");
    }
    else {
      throw new TestException(
          "Region content supposed to be empty but it is having size of "
              + region.size());
    }

  }
  
  
  /**
	 * A Hydra TASK that makes the caller wait for WANBlackboard.nearEndReady.
	 * Used for a single thread to synchronize with one other thread in a
	 * different VM. Polls every second and waits forever.
	 */
  public static void waitForNearEnd() {
    SharedCounters counters = WANBlackboard.getInstance().getSharedCounters();
    while (true) {
      MasterController.sleepForMs(1000);
      if (counters.read(WANBlackboard.nearEndReady) == 1) {
        long val = counters.decrementAndRead(WANBlackboard.nearEndReady);
        if (val != 0) {
          throw new HydraRuntimeException("Unintended use");
        }
        return;
      }
    }
  }

  /**
   * Returns a bridge server endpoint with the given logical name.
   */
  private static BridgeHelper.Endpoint getBridgeEndpoint(String name) {
    for (Iterator i = BridgeHelper.getEndpoints().iterator(); i.hasNext();) {
      BridgeHelper.Endpoint endpoint = (BridgeHelper.Endpoint)i.next();
      if (endpoint.getName().equals(name)) {
        return endpoint;
      }
    }
    throw new HydraRuntimeException(name + " not found in bridge endpoints");
  }

//------------------------------------------------------------------------------
// KILL ZONE MANAGEMENT
//------------------------------------------------------------------------------

  protected static boolean killing = false;
  protected static Lock workLock = new Lock();

  protected static void startKillZone() {
    killing = true;         // record intention to kill self
    workLock.waitForLock(); // wait for fellow threads to stop work
  }
  protected static void startNoKillZone() {
    workLock.lock(); // record intention to work
    if (killing) {   // change mind
      workLock.unlock();
      logger.info("Going into permanent sleep to await kill");
      MasterController.sleepForMs(Integer.MAX_VALUE);
    }
  }
  protected static void endNoKillZone() {
    workLock.unlock();
  }
  public static class Lock {
    int state;
    public Lock() {
      this.state = 0;
    }
    public synchronized void lock() {
      ++this.state;
    }
    public synchronized void unlock() {
      --this.state;
    }
    public void waitForLock() {
      while (true) {
        if (this.state == 0) {
          return;
        } else {
          MasterController.sleepForMs(2000);
        }
      }
    }
  }
  
  /** Create the pdx disk store if one was specified.
   * 
   */
  protected static void initPdxDiskStore() {
    WANClientVersionHelper.initPdxDiskStore();
  }

}
