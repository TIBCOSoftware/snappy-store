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
package parReg.colocation;

import java.io.File;
import java.util.*;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.internal.cache.BucketDump;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import getInitialImage.InitImageBB;
import getInitialImage.InitImagePrms;
import hydra.*;
import hydratest.grid.GridPrms;
import parReg.ParRegBB;
import parReg.ParRegPrms;
import parReg.ParRegUtil;
import parReg.execute.UpdateBBPartitionListener;
import pdx.PdxTest;
import util.*;


public class ParRegColocation {

  // the one instance of ParRegColocation
  static protected ParRegColocation testInstance;

  protected static List regionDescriptNames = new ArrayList();

  protected static List bridgeRegionDescriptNames = new ArrayList();

  protected static int destroyThreshold;

  private static int maxRegions;

  protected static final int ENTRIES_TO_PUT = 50;

  protected static Cache theCache;

  protected static boolean isBridgeConfiguration = false;

  protected static boolean isBridgeClient = true;
  
  protected static boolean isGatewaySenderConfiguration = false;

  private static boolean highAvailability;

  // following are for the PR execute
  private static ArrayList bucketsInTheDataStore;

  private static String bucketReferenceRegion;

  private static ArrayList primaryBucketsInTheDataStore;

  private static String primaryBucketReferenceRegion;

  protected hydra.blackboard.SharedCounters sc;

  // protected fields used by the test to do its work
  protected RandomValues randomValues = null;

  // used for getting random values for the region
  protected int numNewKeys;

  // the number of new keys to add to the region
  protected KeyIntervals keyIntervals;

  // the key intervals used for this test; test test does a different
  // operation on each interval, so it can be validated at the end
  protected int totalNumKeys;

  // the shared counters for this tests's blackboard
  protected int sleepMSAfterOps = -1;

  // MS to sleep after all ops are done to allow mirrors to receive data

  // operations to do on the region
  protected static final int INVALIDATE = 1;

  protected static final int DESTROY = 2;

  protected static final int UPDATE_EXISTING_KEY = 3;

  protected static final int GET = 4;

  protected static final int ADD_NEW_KEY = 5;

  protected static final int QUERY = 6;

  protected static final int LOCAL_INVALIDATE = 7;

  protected static final int LOCAL_DESTROY = 8;

  protected static final int FIRST_OP = 1;

  protected static final int LAST_OP = 6;

  protected static final int operations[] = new int[] { INVALIDATE, DESTROY,
      UPDATE_EXISTING_KEY, GET, ADD_NEW_KEY, QUERY, LOCAL_INVALIDATE,
      LOCAL_DESTROY };

  protected final long LOG_INTERVAL_MILLIS = 10000;

  // used for checking bucket copies
  protected static ParRegUtil parRegUtilInstance = new ParRegUtil();

  protected static String DataStoreVmStr = "DataStoreVM_";

  protected static final String KEY_LIST = "Key List";

  protected static final String PR_TOTAL_BUCKETS = "PR Total Buckets";

  protected static final String PR_TOTAL_DATASTORES = "PR Total DataStores";

  protected static final String VmIDStr = "VmId_";

  /**
   * Initialize the known keys for this test
   */
  public static void StartTask_initialize() {
    Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);
    boolean isBridgeConfiguration = bridgeNames != null;

    Vector gatewayNames = TestConfig.tab().vecAt(GatewayPrms.names, null);
    Vector senderNames = TestConfig.tab().vecAt(GatewaySenderPrms.names, null);
    boolean isWanConfiguration = (gatewayNames == null) ? (senderNames != null) : true;
    
     // initialize keyIntervals
    int numKeys = TestConfig.tab().intAt(InitImagePrms.numKeys);

    // lynn for now we cannot use localInvalidate or localDestroy; they are not
    // supported for Congo
    KeyIntervals intervals = null;
    if (isWanConfiguration || (ConfigPrms.getHadoopConfig() != null)) {
      intervals = new KeyIntervals(new int[] {KeyIntervals.NONE, 
                                             KeyIntervals.DESTROY, KeyIntervals.UPDATE_EXISTING_KEY,
                                             KeyIntervals.GET}, numKeys);
    } else {
      intervals = new KeyIntervals(new int[] {KeyIntervals.NONE, KeyIntervals.INVALIDATE,
                                             KeyIntervals.DESTROY, KeyIntervals.UPDATE_EXISTING_KEY,
                                             KeyIntervals.GET}, numKeys);
    }
    InitImageBB.getBB().getSharedMap()
        .put(InitImageBB.KEY_INTERVALS, intervals);
    Log.getLogWriter().info("Created keyIntervals: " + intervals);

    // Set the counters for the next keys to use for each operation
    // lynn- add localInval and localDestroy later when supported in product
    hydra.blackboard.SharedCounters sc = InitImageBB.getBB()
        .getSharedCounters();
    sc.setIfLarger(InitImageBB.LASTKEY_INVALIDATE, intervals
        .getFirstKey(KeyIntervals.INVALIDATE) - 1);
    sc.setIfLarger(InitImageBB.LASTKEY_DESTROY, intervals
        .getFirstKey(KeyIntervals.DESTROY) - 1);
    sc.setIfLarger(InitImageBB.LASTKEY_UPDATE_EXISTING_KEY, intervals
        .getFirstKey(KeyIntervals.UPDATE_EXISTING_KEY) - 1);
    sc.setIfLarger(InitImageBB.LASTKEY_GET, intervals
        .getFirstKey(KeyIntervals.GET) - 1);
    sc.setIfLarger(InitImageBB.LAST_QUERY, intervals
        .getFirstKey(KeyIntervals.GET) - 1); // Same as get key

    // show the blackboard
    InitImageBB.getBB().printSharedMap();
    InitImageBB.getBB().printSharedCounters();
  }

  /**
   * Initialize the single instance of this test class but not a region. If this
   * VM has already initialized its instance, then skip reinitializing.
   */
  public synchronized static void HydraTask_initialize() {
    if (testInstance == null) {
      testInstance = new ParRegColocation();
      ((ParRegColocation)testInstance).initialize();
    }
  }

  /**
   * Hydra task for initializing a data store
   */
  public synchronized static void HydraTask_HA_dataStoreInitialize() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new ParRegColocation();
      isBridgeClient = false;
      ((ParRegColocation)testInstance).initialize();
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      if (isBridgeConfiguration
          && !TestConfig.tab().booleanAt(util.CachePrms.useDeclarativeXmlFile,
              false)) {
        BridgeHelper.startBridgeServer("bridge");
      }
      // re-initialize the TransactionManager (for the newly created cache) 
      if (getInitialImage.InitImagePrms.useTransactions()) {
         TxHelper.setTransactionManager();
      }
    }
  }

  /**
   * Hydra task for initializing a data store
   */
  public synchronized static void HydraTask_HA_initDataStoreAndServerGroups() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new ParRegColocation();
      isBridgeClient = false;
      ((ParRegColocation)testInstance).initialize();
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
      if (isBridgeConfiguration
          && !TestConfig.tab().booleanAt(util.CachePrms.useDeclarativeXmlFile,
              false)) {
        int vmId = RemoteTestModule.getMyVmid();
        if (vmId % 2 == 0) {
          BridgeHelper.startBridgeServer("bridge");
        }
        else {
          BridgeHelper.startBridgeServer("groups");
        }
      }
    }
  }

  /**
   * Hydra task for initializing a data store in peer to peer configuration
   */
  public synchronized static void HydraTask_p2p_dataStoreInitialize() {
    if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new ParRegColocation();
      isBridgeClient = false;
      ((ParRegColocation)testInstance).initialize("dataStore");
      ParRegBB.getBB().getSharedMap().put(
          DataStoreVmStr + RemoteTestModule.getMyVmid(),
          new Integer(RemoteTestModule.getMyVmid()));
    }
  }

  /**
   * Hydra task for initializing an accessor in peer to peer configuration
   */
  public synchronized static void HydraTask_p2p_accessorInitialize() {
    if (testInstance == null) {
      testInstance = new ParRegColocation();
      isBridgeClient = false;
      ((ParRegColocation)testInstance).initialize("accessor");
    }
  }

  /**
   * Hydra task for initializing an accessor
   */
  public synchronized static void HydraTask_HA_accessorInitialize() {
    if (testInstance == null) {
      testInstance = new ParRegColocation();
      ((ParRegColocation)testInstance).initialize();
    }
  }

  /**
   * Log the local size of the PR data store
   */
  public synchronized static void HydraTask_logLocalSize() {
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        Log.getLogWriter().info(
            "Number of entries in this data store: "
                + ParRegUtil.getLocalSize(aRegion));
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        Log.getLogWriter().info(
            "Number of entries in this data store: "
                + ParRegUtil.getLocalSize(aRegion));
      }

  }

  /**
   * Task for clients to register interest in all regions
   */
  public synchronized static void HydraTask_registerInterest() {
    for (int j = 0; j < regionDescriptNames.size(); j++) {
      String regionDescriptName = (String)(regionDescriptNames.get(j));
      String regionName = RegionHelper.getRegionDescription(regionDescriptName)
          .getRegionName();
      Region aRegion = theCache.getRegion(regionName);
      ParRegUtil.registerInterest(aRegion);
      Log.getLogWriter().info(
          "registered interest for the region " + regionName);
    }
  }

  /**
   * Initialize this test instance
   */
  public void initialize() {
    numNewKeys = TestConfig.tab().intAt(InitImagePrms.numNewKeys, -1);
    keyIntervals = (KeyIntervals)(InitImageBB.getBB().getSharedMap()
        .get(InitImageBB.KEY_INTERVALS));
    Log.getLogWriter().info(
        "initInstance, keyIntervals read from blackboard = "
            + keyIntervals.toString());
    int numDestroyed = keyIntervals.getNumKeys(KeyIntervals.DESTROY);
    int numKeyIntervals = keyIntervals.getNumKeys();
    totalNumKeys = numKeyIntervals + numNewKeys - numDestroyed;
    sc = InitImageBB.getBB().getSharedCounters();
    randomValues = new RandomValues();
    Log.getLogWriter().info("numKeyIntervals is " + numKeyIntervals);
    Log.getLogWriter().info("numNewKeys is " + numNewKeys);
    Log.getLogWriter().info("numDestroyed is " + numDestroyed);
    Log.getLogWriter().info("totalNumKeys is " + totalNumKeys);

    Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);
    isBridgeConfiguration = bridgeNames != null;
    isGatewaySenderConfiguration = (TestConfig.tab().stringAt(GatewaySenderPrms.names, null) != null);
    Vector regionNames = TestConfig.tab().vecAt(RegionPrms.names, null);
    for (int i = 0; i < regionNames.size(); i++) {
      String regionDescriptName = (String)(regionNames.get(i));
      if (regionDescriptName.startsWith("bridge")) { // this is a server region
        // description
        bridgeRegionDescriptNames.add(regionDescriptName);
      }
      else { // is either a bridge client or a peer
        regionDescriptNames.add(regionDescriptName);
      }
    }
    if (isBridgeConfiguration) {
      if (bridgeRegionDescriptNames.size() != regionDescriptNames.size()) {
        throw new TestException(
            "Error in test configuration; need equal number of region descriptions for bridge servers and bridge clients");
      }
    }
    Log.getLogWriter().info(
        "bridgeRegionDescriptNames is " + bridgeRegionDescriptNames);
    Log.getLogWriter().info("regionDescriptNames is " + regionDescriptNames);
    maxRegions = regionDescriptNames.size();
    destroyThreshold = (int)(regionDescriptNames.size() * 0.8);
    Log.getLogWriter().info("destroyThreshold is " + destroyThreshold);
    Log.getLogWriter().info("ENTRIES_TO_PUT is " + ENTRIES_TO_PUT);
    if (TestConfig.tab().booleanAt(util.CachePrms.useDeclarativeXmlFile, false)) {
      createRegionsWithXml();
    }
    else {
      theCache = CacheHelper.createCache("cache1");
      
      //create gateway sender before regions
      
      if(isGatewaySenderConfiguration){ //gateway sender configuration present
        if (!isBridgeConfiguration) { // p2p configuration
          String senderConfig = ConfigPrms.getGatewaySenderConfig();
          GatewaySenderHelper.createGatewaySenders(senderConfig);
        }
        else if (!isBridgeClient) { // hct configuration with bridge vm
          String senderConfig = ConfigPrms.getGatewaySenderConfig();
          GatewaySenderHelper.createGatewaySenders(senderConfig);
        }
      }
      
      createRegions();
    }
    TestHelper.logRegionHierarchy();
  }

  /**
   * Initialize this test instance
   */
  public void initialize(String regionDescription) {
    numNewKeys = TestConfig.tab().intAt(InitImagePrms.numNewKeys, -1);
    keyIntervals = (KeyIntervals)(InitImageBB.getBB().getSharedMap()
        .get(InitImageBB.KEY_INTERVALS));
    Log.getLogWriter().info(
        "initInstance, keyIntervals read from blackboard = "
            + keyIntervals.toString());
    int numDestroyed = keyIntervals.getNumKeys(KeyIntervals.DESTROY);
    int numKeyIntervals = keyIntervals.getNumKeys();
    totalNumKeys = numKeyIntervals + numNewKeys - numDestroyed;
    sc = InitImageBB.getBB().getSharedCounters();
    randomValues = new RandomValues();
    Log.getLogWriter().info("numKeyIntervals is " + numKeyIntervals);
    Log.getLogWriter().info("numNewKeys is " + numNewKeys);
    Log.getLogWriter().info("numDestroyed is " + numDestroyed);
    Log.getLogWriter().info("totalNumKeys is " + totalNumKeys);

    Vector regionNames = TestConfig.tab().vecAt(RegionPrms.names, null);
    for (int i = 0; i < regionNames.size(); i++) {
      String regionDescriptName = (String)(regionNames.get(i));
      if (regionDescriptName.startsWith("dataStore")
          && regionDescription.startsWith("dataStore")) { // this is a data
        // store
        regionDescriptNames.add(regionDescriptName);
      }
      else if (regionDescriptName.startsWith("accessor")
          && regionDescription.startsWith("accessor")) { // this is an accessor
        regionDescriptNames.add(regionDescriptName);
      }
    }

    Log.getLogWriter().info("regionDescriptNames is " + regionDescriptNames);
    maxRegions = regionDescriptNames.size();
    theCache = CacheHelper.createCache("cache1");
    destroyThreshold = (int)(regionDescriptNames.size() * 0.8);
    Log.getLogWriter().info("destroyThreshold is " + destroyThreshold);
    Log.getLogWriter().info("ENTRIES_TO_PUT is " + ENTRIES_TO_PUT);
    createRegions();
  }

  /**
   * Task to create cache and regions using xml. (Also does function
   * registeration if applicable)
   */
  protected void createRegionsWithXml() {
    String key = VmIDStr + RemoteTestModule.getMyVmid();
    String cacheXmlFile = key + ".xml";
    File aFile = new File(cacheXmlFile);

   List diskStoreName = TestConfig.tab().vecAt(DiskStorePrms.names, null);
    if (!aFile.exists()) {
      if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
        CacheHelper.generateCacheXmlFileNoCheck("cache1", null,
            bridgeRegionDescriptNames, null, "bridge", null, diskStoreName, GridPrms
                .getFunctions(), cacheXmlFile);
        theCache = CacheHelper.createCacheFromXml(cacheXmlFile);
      }
      else if (isBridgeConfiguration && isBridgeClient) {// this is a bridge
        // client
        Vector poolNames = TestConfig.tab().vecAt(PoolPrms.names, null);
        DistributedSystemHelper.connectWithXml(cacheXmlFile);
        Log.getLogWriter().info("Pool in the list " + poolNames);
        CacheHelper.generateCacheXmlFileNoCheck("cache1", null,
            regionDescriptNames, null, null, poolNames, diskStoreName,
            GridPrms.getFunctions(), cacheXmlFile);
        theCache = CacheHelper.createCacheFromXml(cacheXmlFile);
      }
      else {// for p2p members
        CacheHelper.generateCacheXmlFileNoCheck("cache1", null,
            regionDescriptNames, null, null, null, diskStoreName, GridPrms.getFunctions(),
            cacheXmlFile);
        theCache = CacheHelper.createCacheFromXml(cacheXmlFile);
      }
    } else {
      theCache = CacheHelper.createCacheFromXml(cacheXmlFile);
    }
  }

  /**
   * Task to create regions one by one
   */
  protected void createRegions() {
    Set rootRegions = theCache.rootRegions();
    Region aRegion = null;

    // create a partitioned region
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        try {
          aRegion = RegionHelper.createRegion(regionName, RegionHelper
              .getRegionAttributes(regionDescriptName));
          Log.getLogWriter().info(
              "Created partitioned region " + regionName
                  + " with region descript name " + regionDescriptName);
        }
        catch (RegionExistsException e) {
          // region already exists; ok
          Log.getLogWriter().info(
              "Using existing partitioned region " + regionName);
          aRegion = e.getRegion();
          if (aRegion == null) {
            throw new TestException(
                "RegionExistsException.getRegion returned null");
          }
        }
      }
    }
    else {
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        RegionAttributes attributes = RegionHelper
            .getRegionAttributes(regionDescriptName);
        String poolName = attributes.getPoolName();
        if (poolName != null) {
          PoolHelper.createPool(poolName);
        }
        try {
           aRegion = RegionHelper.createRegion(regionName, RegionHelper.getRegionAttributes(regionDescriptName));
           Log.getLogWriter().info("Created partitioned region " + regionName + " with region descript name " + regionDescriptName);
        } catch (RegionExistsException e) {
          // region already exists; ok
          Log.getLogWriter().info(
              "Using existing partitioned region " + regionName);
          aRegion = e.getRegion();
          if (aRegion == null) {
            throw new TestException(
                "RegionExistsException.getRegion returned null");
          }
        }
      }

    }
  }

  /**
   * Hydra task to initialize a region and load it according to hydra param
   * settings.
   */
  public static void HydraTask_loadRegions() {
    PdxTest.initClassLoader();
    testInstance.loadRegions();
  }

  protected void loadRegions() {
    final long LOG_INTERVAL_MILLIS = 10000;
    int numKeysToCreate = keyIntervals.getNumKeys();
    long lastLogTime = System.currentTimeMillis();
    long minTaskGranularitySec = TestConfig.tab().longAt(
        TestHelperPrms.minTaskGranularitySec, -1);
    long minTaskGranularityMS = -1;
    if (minTaskGranularitySec != -1)
      minTaskGranularityMS = minTaskGranularitySec
          * TestHelper.SEC_MILLI_FACTOR;
    long startTime = System.currentTimeMillis();
    do {
      long shouldAddCount = sc.incrementAndRead(InitImageBB.SHOULD_ADD_COUNT);
      if (shouldAddCount > numKeysToCreate) {
        String aStr = "In loadRegion, for Region shouldAddCount is "
            + shouldAddCount + ", numOriginalKeysCreated is "
            + sc.read(InitImageBB.NUM_ORIGINAL_KEYS_CREATED)
            + ", numKeysToCreate is " + numKeysToCreate;
        Log.getLogWriter().info(aStr);
        NameBB.getBB().printSharedCounters();
        throw new StopSchedulingTaskOnClientOrder(aStr);
      }
      Object key = NameFactory.getNextPositiveObjectName();
      try {

        Object value = getValueToAdd(key);
        if (TestConfig.tab().booleanAt(parReg.ParRegPrms.isWithRoutingResolver,
            false)) {
          Month callBackArg = Month.months[((int)(shouldAddCount % 12))];
          Log.getLogWriter().info(
              "Callback arg is " + callBackArg.toString()
                  + " which has hashCode " + callBackArg.hashCode());
          InitImageBB.getBB().getSharedMap().put(key, callBackArg);
          String regionDescriptName = null;
          if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge
            // server
            for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
              regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
              String regionName = RegionHelper.getRegionDescription(
                  regionDescriptName).getRegionName();
              Region aRegion = theCache.getRegion(regionName);
              Log.getLogWriter().info(
                  "Doing put in region " + aRegion.getName() + "for key value "
                      + key.toString() + " " + value.toString());
              aRegion.put(key, value);
              Log.getLogWriter().info(
                  "The region size is " + aRegion.size() + " region is "
                      + regionName);
            }
          }
          else
            for (int j = 0; j < regionDescriptNames.size(); j++) {
              regionDescriptName = (String)(regionDescriptNames.get(j));
              String regionName = RegionHelper.getRegionDescription(
                  regionDescriptName).getRegionName();
              Region aRegion = theCache.getRegion(regionName);
              Log.getLogWriter().info(
                  "Doing put in region " + aRegion.getName() + "for key value "
                      + key.toString() + " " + value.toString());
              aRegion.put(key, value);
              Log.getLogWriter().info(
                  "Did put in region " + aRegion.getName() + "for key value "
                      + key.toString() + " " + value.toString());
              Log.getLogWriter().info(
                  "The region size is " + aRegion.size() + " region is "
                      + regionName);
            }
        }
        // Log.getLogWriter().info("Loading with put, key " + key + ", value " +
        // TestHelper.toString(value));
        sc.increment(InitImageBB.NUM_ORIGINAL_KEYS_CREATED);
      }
      catch (TimeoutException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }
      catch (CacheWriterException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }
      catch (CacheLoaderException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }
      if (System.currentTimeMillis() - lastLogTime > LOG_INTERVAL_MILLIS) {
        // Log.getLogWriter().info("Added " +
        // sc.read(InitImageBB.NUM_ORIGINAL_KEYS_CREATED) + " out of " +
        // numKeysToCreate +
        // " entries into " + TestHelper.regionToString(aRegion, false));
        Log.getLogWriter().info(
            "Added " + sc.read(InitImageBB.NUM_ORIGINAL_KEYS_CREATED)
                + " out of " + numKeysToCreate);
        lastLogTime = System.currentTimeMillis();
      }
    } while ((minTaskGranularitySec == -1)
        || (System.currentTimeMillis() - startTime < minTaskGranularityMS));
  }

  /**
   * Return a value to add to a region for the given key
   */
  protected Object getValueToAdd(Object key) {
    String objectType = TestConfig.tab().stringAt(ValueHolderPrms.objectType, ValueHolder.class.getName());
    if (objectType.equals(ValueHolder.class.getName())) {
      return new ValueHolder((String)key, randomValues);
    } else if ((objectType.equals("util.PdxVersionedValueHolder")) ||
        (objectType.equals("util.VersionedValueHolder"))) {
      return PdxTest.getVersionedValueHolder(objectType, (String)key, randomValues);
    } else {
      throw new TestException("Unknown objectType " + objectType);
    }
  }

  /**
   * Hydra task to verify metadata
   */
  public static void HydraTask_verifyPRMetaData() {
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        if (aRegion instanceof PartitionedRegion) {
          verifyPRMetaData(aRegion);
          Log.getLogWriter().info(
              "verified Meta Data for the region " + regionName);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify metadata");
        }
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        verifyPRMetaData(aRegion);
        if (aRegion instanceof PartitionedRegion) {
          verifyPRMetaData(aRegion);
          Log.getLogWriter().info(
              "verified Meta Data for the region " + regionName);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify metadata");
        }
      }
  }

  /**
   * Task to verify metadata
   */
  public static void verifyPRMetaData(Region aRegion) {
    try {
      ParRegUtil.verifyPRMetaData(aRegion);
    }
    catch (Exception e) {
      ParRegUtil.dumpAllDataStores(aRegion);
      throw new TestException(TestHelper.getStackTrace(e));
    }
  }

  /**
   * Hydra task to verify the size of the region after the load.
   */
  public static void HydraTask_verifyRegionSize() {
    testInstance.Task_verifyRegionSize();
  }

  /**
   * Hydra task to verify the size of the region after the load.
   */
  public void Task_verifyRegionSize() {
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        verifyRegionSize(aRegion);
        Log.getLogWriter().info(
            "verified Region size for the region " + regionName);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        verifyRegionSize(aRegion);
        Log.getLogWriter().info(
            "verified Region size for the region " + regionName);
      }
  }

  /**
   * Task that does the verification of region size
   * 
   * @param aRegion
   *                Region whose size is to be verified
   */
  protected void verifyRegionSize(Region aRegion) {
    // we already completed this check once; we can't do it again without
    // reinitializing the
    // verify state variables

    int size = aRegion.size();
    long numOriginalKeysCreated = InitImageBB.getBB().getSharedCounters().read(
        InitImageBB.NUM_ORIGINAL_KEYS_CREATED);
    int numKeysToCreate = keyIntervals.getNumKeys();
    long nameCounter = NameFactory.getPositiveNameCounter();
    Log.getLogWriter().info(
        "In HydraTask_verifyRegionSize, region size is " + size
            + ", numKeysToCreate is " + numKeysToCreate
            + ", numOriginalKeysCreated is " + numOriginalKeysCreated
            + ", nameCounter is " + nameCounter);

    // make sure the test agrees with itself
    if ((numOriginalKeysCreated != numKeysToCreate)
        || (numOriginalKeysCreated != nameCounter)) {
      throw new TestException("Error in test, numOriginalKeysCreated "
          + numOriginalKeysCreated + ", numKeysToCreate " + numKeysToCreate
          + ", nameCounter " + nameCounter);
    }

    int verifyRegionSizeIndex = 0;
    while (verifyRegionSizeIndex < numKeysToCreate) {
      verifyRegionSizeIndex++;
      Object key = NameFactory.getObjectNameForCounter(verifyRegionSizeIndex);
      Log.getLogWriter().info(
          "For region : " + aRegion.getName()
              + " : Key : " + key);
      if (!aRegion.containsKey(key))
        throw new TestException("Key " + key + " not found in the region "
            + aRegion.getName());
      if (!aRegion.containsValueForKey(key))
        throw new TestException("For the key " + key
            + " value is not found in the region " + aRegion.getName());
    }

    if (size != numKeysToCreate) {
      throw new TestException("Unexpected region size " + size + "; expected "
          + numKeysToCreate);
    }
    String aStr = "In HydraTask_verifyRegionSize, verified "
        + verifyRegionSizeIndex + " keys and values";
    Log.getLogWriter().info(aStr);
  }

  /**
   * Hydra task to do region operations, then stop scheduling.
   */
  public static void HydraTask_doOps() {
    BitSet availableOps = new BitSet(operations.length);
    availableOps.flip(FIRST_OP, LAST_OP + 1);
    testInstance.doOps(availableOps);
    Log.getLogWriter().info("Cardinality is " + availableOps.cardinality());
    if (availableOps.cardinality() == 0) {
      ParRegBB.getBB().getSharedCounters().increment(ParRegBB.TimeToStop);
      throw new StopSchedulingTaskOnClientOrder("Finished with ops");
    }
  }

  public static void HydraTask_disconnect() {
    DistributedSystemHelper.disconnect();
    testInstance = null;     
    primaryBucketsInTheDataStore = null;
    PRObserver.installObserverHook();
    PRObserver.initialize();
    UpdateBBPartitionListener.setBucketIds(new ArrayList());
    UpdateBBPartitionListener.setRecreatedBuckets(new HashSet());
  }
  
  /**
   * Do operations on the REGION_NAME's keys using keyIntervals to specify which
   * keys get which operations. This will return when all operations in all
   * intervals have completed.
   * 
   * @param availableOps -
   *                Bits which are true correspond to the operations that should
   *                be executed.
   */
  public void doOps(BitSet availableOps) {
    long minTaskGranularitySec = TestConfig.tab().longAt(
        TestHelperPrms.minTaskGranularitySec);
    long minTaskGranularityMS = minTaskGranularitySec
        * TestHelper.SEC_MILLI_FACTOR;
    long startTime = System.currentTimeMillis();

    // useTransactions() defaults to false
    boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
    if (useTransactions) {
       // TODO: TX: redo for new TX impl
       //TxHelper.recordClientTXOperations();
    }

    boolean rolledback;

    while ((availableOps.cardinality() != 0)
        && (System.currentTimeMillis() - startTime < minTaskGranularityMS)) {
      int whichOp = getOp(availableOps, operations.length);
      boolean doneWithOps = false;
      boolean gotTheLock = false;
     
      rolledback = false;
      if (useTransactions) {
        TxHelper.begin();
      }

      try {
        switch (whichOp) {
          case ADD_NEW_KEY:
            doneWithOps = addNewKey();
            break;
          case INVALIDATE:
            doneWithOps = invalidate();
            break;
          case DESTROY:
            doneWithOps = destroy();
            break;
          case UPDATE_EXISTING_KEY:
            doneWithOps = updateExistingKey();
            break;
          case GET:
            doneWithOps = get();
            break;
          case QUERY:
            doneWithOps = true; // Not tested currently(taken care in
            // parRegQuery)
            break;
          case LOCAL_INVALIDATE:
            doneWithOps = localInvalidate();
            break;
          case LOCAL_DESTROY:
            doneWithOps = localDestroy();
            break;
          default: {
            throw new TestException("Unknown operation " + whichOp);
          }
        }
      } catch (TransactionDataNodeHasDepartedException e) {
        if (!useTransactions) {
          throw new TestException("Unexpected TransactionDataNodeHasDepartedException " + TestHelper.getStackTrace(e));
        } else {
          Log.getLogWriter().info("Caught TransactionDataNodeHasDepartedException.  Expected with concurrent execution, continuing test.");
          recordFailedOps(ParRegBB.FAILED_TXOPS);
          Log.getLogWriter().info("Rolling back transaction.");
          try {
            TxHelper.rollback();
            Log.getLogWriter().info("Done Rolling back Transaction");
          } catch (TransactionException te) {
            Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching TransactionDataNodeHasDeparted during tx ops.  Expected, continuing test.");
          }
          rolledback = true;
        }
      } catch (TransactionDataRebalancedException e) {
        if (!useTransactions) {
          throw new TestException("Unexpected Exception " + e + ". " + TestHelper.getStackTrace(e));
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
          // Not expected as there is only one op per tx
          throw new TestException("Unexpected ConflictException " + TestHelper.getStackTrace(e));
        }
      }

      if (doneWithOps) {
        Log.getLogWriter().info("Done with operation " + whichOp);
        availableOps.clear(whichOp);
      }
    }
  }

  /** Use TxHelper.getClientTXOperations() to get the List of TransactionalOperations which failed due to 
   *  TransactionDataNodeHasDeparted and TransactionDataRebalancedExceptions (HA tests).
   *  Add the keys for these operations to the given set (either failedTxOps or inDoubtTxOps)
   *
   */
  protected void recordFailedOps(String sharedMapKey) {
     // TODO: TX: redo for new TX impl
     /*
     List opList = TxHelper.getClientTXOperations();
     Iterator it = opList.iterator();
     while (it.hasNext()) {
       TransactionalOperation op = (TransactionalOperation)it.next();
       ParRegBB.getBB().addFailedOp(sharedMapKey, op.getKey());
     }
     */
  }

  protected int getOp(BitSet bs, int bsSize) {
    int randInt;
    do {
      randInt = TestConfig.tab().getRandGen().nextInt(1, bsSize);
    } while (!bs.get(randInt));
    return randInt;
  }

  /**
   * Check that containsValueForKey() called on the tests' region has the
   * expected result. Throw an error if any problems.
   * 
   * @param aRegion
   *                Region where the check is to be done
   * @param key
   *                The key to check.
   * @param expected
   *                The expected result of containsValueForKey
   * @param logStr
   *                Used if throwing an error due to an unexpected value.
   */
  protected void checkContainsValueForKey(Region aRegion, Object key,
      boolean expected, String logStr) {
    if (aRegion.getAttributes().getDataPolicy() == DataPolicy.EMPTY) {
      // NO check for EMPTY region
      return;
    }
 
    // Check for NON EMPTY regions
    boolean containsValue = aRegion.containsValueForKey(key);    
    if (containsValue != expected)
      throw new TestException("Expected containsValueForKey(" + key
          + ") to be " + expected + ", but it was " + containsValue + ": "
          + logStr);
  }

  /**
   * Check that containsKey() called on the tests' region has the expected
   * result. Throw an error if any problems.
   * 
   * @param aRegion
   *                The Region in which the validation has to be done
   * @param key
   *                The key to check.
   * @param expected
   *                The expected result of containsKey
   * @param logStr
   *                Used if throwing an error due to an unexpected value.
   */
  protected void checkContainsKey(Region aRegion, Object key, boolean expected,
      String logStr) {
    boolean containsKey = aRegion.containsKey(key);
    if (containsKey != expected)
      throw new TestException("Expected containsKey(" + key + ") to be "
          + expected + ", but it was " + containsKey + ": " + logStr);
  }

  /**
   * Check that the value of the given key is expected for this test. Throw an
   * error if any problems.
   * 
   * @param key
   *                The key to check.
   * @param value
   *                The value for the key.
   */
  protected void checkValue(Object key, Object value) {
    if (value instanceof BaseValueHolder) {
      BaseValueHolder vh = (BaseValueHolder)value;
      long keyCounter = NameFactory.getCounterForName(key);
      if (vh.myValue instanceof Long) {
        Long aLong = (Long)vh.myValue;
        long longValue = aLong.longValue();
        if (keyCounter != longValue)
          throw new TestException("Inconsistent ValueHolder.myValue for key "
              + key + ":" + TestHelper.toString(vh));
      }
      else {
        throw new TestException("Expected ValueHolder.myValue for key " + key
            + " to be a Long for " + TestHelper.toString(vh));
      }
    }
    else {
      throw new TestException("For key " + key + ", expected value "
          + TestHelper.toString(value) + " to be a ValueHolder");
    }
  }

  /**
   * Check that the value of the given key is expected as an updated value.
   * Throw an error if any problems.
   * 
   * @param key
   *                The key to check.
   * @param value
   *                The value for the key.
   */
  protected void checkUpdatedValue(Object key, Object value) {
    if (value instanceof BaseValueHolder) {
      BaseValueHolder vh = (BaseValueHolder)value;
      long keyCounter = NameFactory.getCounterForName(key);
      if (vh.myValue instanceof String) {
        String aStr = (String)vh.myValue;
        String expectedStr = "updated_" + keyCounter;
        if (!aStr.equals(expectedStr))
          throw new TestException("Inconsistent ValueHolder.myValue for key "
              + key + ":" + TestHelper.toString(vh));
      }
      else {
        throw new TestException(
            "Expected ValueHolder.myValue for key "
                + key
                + " to be a String indicating it was updated, but the value for this key is "
                + TestHelper.toString(vh));
      }
    }
    else {
      throw new TestException("Expected value " + TestHelper.toString(value)
          + " to be a ValueHolder");
    }
  }

  /**
   * Add a new key to REGION_NAME.
   * 
   * @return true if all new keys have been added (specified by
   *         InitImagePrms.numNewKeys)
   */
  protected boolean addNewKey() {

    long numNewKeysCreated = sc
        .incrementAndRead(InitImageBB.NUM_NEW_KEYS_CREATED);

    if (numNewKeysCreated > numNewKeys) {
      Log.getLogWriter().info(
          "All new keys created; returning from addNewKey()");
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
        addNewKey(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        addNewKey(aRegion, key);

      }
    return (numNewKeysCreated >= numNewKeys);
  }

  protected void addNewKey(Region aRegion, Object key) {
    Log.getLogWriter().info(
        "In addNewKey " + key + "in the region " + aRegion.getName());
    checkContainsValueForKey(aRegion, key, false, "before addNewKey");
    Object value = new ValueHolder((String)key, randomValues);
    if (TestConfig.tab().booleanAt(parReg.ParRegPrms.isWithRoutingResolver,
        false)) {
      Month callBackArg = (Month)InitImageBB.getBB().getSharedMap().get(key);
      aRegion.put(key, value, callBackArg);
    }
  }

  /**
   * Invalidate a key in region REGION_NAME. The keys to invalidate are
   * specified in keyIntervals.
   * 
   * @return true if all keys to be invalidated have been completed.
   */
  protected boolean invalidate() {

    long nextKey = sc.incrementAndRead(InitImageBB.LASTKEY_INVALIDATE);
    if (!keyIntervals.keyInRange(KeyIntervals.INVALIDATE, nextKey)) {
      Log.getLogWriter().info(
          "All existing keys invalidated; returning from invalidate operation");
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
        invalidate(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        invalidate(aRegion, key);

      }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.INVALIDATE));

  }

  public void invalidate(Region aRegion, Object key) {
    Log.getLogWriter().info(
        "Invalidating " + key + "in the region " + aRegion.getName());
    checkContainsValueForKey(aRegion, key, true, "before invalidate");
    aRegion.invalidate(key);
  }

  /**
   * Locally invalidate a key in region REGION_NAME. The keys to locally
   * invalidate are specified in keyIntervals.
   * 
   * @return true if all keys to be locally invalidated have been completed.
   */
  protected boolean localInvalidate() {
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
        localInvalidate(aRegion, key);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        localInvalidate(aRegion, key);
      }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_INVALIDATE));

  }

  public void localInvalidate(Region aRegion, Object key) {
    checkContainsValueForKey(aRegion, key, true, "before local invalidate");
    aRegion.localInvalidate(key);
  }

  /**
   * Destroy a key in region REGION_NAME. The keys to destroy are specified in
   * keyIntervals.
   * 
   * @return true if all keys to be destroyed have been completed.
   */
  protected boolean destroy() {

    Log.getLogWriter().info("Inside destroy()");

    Cache cache = CacheHelper.getCache();

    if (cache == null) {
      throw new TestException("Cache returned null");
    }
    else {
      Log.getLogWriter().info("Cache not null");
    }

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
        destroy(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        destroy(aRegion, key);

      }
    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.DESTROY));
  }

  public void destroy(Region aRegion, Object key) {
    Log.getLogWriter().info(
        "Destroying " + key + "in the region " + aRegion.getName());
    checkContainsValueForKey(aRegion, key, true, "before destroy");
    aRegion.destroy(key);
  }

  /**
   * Locally destroy a key in region REGION_NAME. The keys to locally destroy
   * are specified in keyIntervals.
   * 
   * @return true if all keys to be locally destroyed have been completed.
   */
  protected boolean localDestroy() {
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
        localDestroy(aRegion, key);
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        localDestroy(aRegion, key);

      }
    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_DESTROY));
  }

  public void localDestroy(Region aRegion, Object key) {
    Log.getLogWriter().info("Locally destroying " + key);
    checkContainsValueForKey(aRegion, key, true, "before local destroy");
    aRegion.localDestroy(key);
  }

  /**
   * Update an existing key in region REGION_NAME. The keys to update are
   * specified in keyIntervals.
   * 
   * @return true if all keys to be updated have been completed.
   */
  protected boolean updateExistingKey() {
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
        updateExistingKey(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        updateExistingKey(aRegion, key);

      }

    return (nextKey >= keyIntervals
        .getLastKey(KeyIntervals.UPDATE_EXISTING_KEY));
  }

  public void updateExistingKey(Region aRegion, Object key) {
    Log.getLogWriter().info(
        "Update existing key " + key + "in the region " + aRegion.getName());
    checkContainsValueForKey(aRegion, key, true, "before update");
    BaseValueHolder existingValue = (BaseValueHolder)aRegion.get(key);
    BaseValueHolder newValue = new ValueHolder((String)key, randomValues);
    if (existingValue == null)
      throw new TestException("Get of key " + key + " returned unexpected "
          + existingValue);
    if (existingValue.myValue instanceof String)
      throw new TestException(
          "Trying to update a key which was already updated: "
              + existingValue.myValue);
    newValue.myValue = "updated_" + NameFactory.getCounterForName(key);
    if (TestConfig.tab().booleanAt(parReg.ParRegPrms.isWithRoutingResolver,
        false)) {
      Month callBackArg = (Month)InitImageBB.getBB().getSharedMap().get(key);
      aRegion.put(key, newValue, callBackArg);
      Log.getLogWriter().info("Inside update(), did update with callback");
    }
  }

  /**
   * get the value of key in REGION_NAME. The method uses PR execute function
   * for getting the value of the Key.
   * 
   */
  protected boolean get() {
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
        get(aRegion, key);

      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        get(aRegion, key);

      }

    return (nextKey >= keyIntervals.getLastKey(KeyIntervals.GET));
  }

  public void get(Region aRegion, Object key) {
    checkContainsValueForKey(aRegion, key, true, "before get");
    Object existingValue = null;
    if (TestConfig.tab().booleanAt(parReg.ParRegPrms.isWithRoutingResolver,
        false)) {
      Month callBackArg = (Month)InitImageBB.getBB().getSharedMap().get(key);
      existingValue = aRegion.get(key, callBackArg);
      Log.getLogWriter().info("Inside get(), did get with callback");
    }
    if (existingValue == null)
      throw new TestException("Get of key " + key + " returned unexpected "
          + existingValue);
  }

  /**
   * Hydra task to verify primaries
   */
  public static void HydraTask_verifyPrimaries() {
    String regionDescriptName = null;
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        if (aRegion instanceof PartitionedRegion) {
          verifyPrimaries(aRegion);
          Log.getLogWriter().info(
              "verified primaries for the region " + regionName);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify primaries");
        }
      }
    }
    else
      for (int j = 0; j < regionDescriptNames.size(); j++) {
        regionDescriptName = (String)(regionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        if (aRegion instanceof PartitionedRegion) {
          verifyPrimaries(aRegion);
          Log.getLogWriter().info(
              "verified primaries for the region " + regionName);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify primaries");
        }
      }
  }

  /**
   * Task that checks the primaries for the region
   * 
   * @param aRegion
   *                Region whose primaries has to be checked
   */
  public static void verifyPrimaries(Region aRegion) {
    try {
      RegionAttributes attr = aRegion.getAttributes();
      PartitionAttributes prAttr = attr.getPartitionAttributes();
      int redundantCopies = 0;
      if (prAttr != null) {
        redundantCopies = prAttr.getRedundantCopies();
      }
      if (highAvailability) { // with HA, wait for things to settle down
        ParRegUtil.verifyPrimariesWithWait(aRegion, redundantCopies);
      }
      else {
        ParRegUtil.verifyPrimaries(aRegion, redundantCopies);
      }
    }
    catch (Exception e) {
      ParRegUtil.dumpAllDataStores(aRegion);
      throw new TestException(TestHelper.getStackTrace(e));
    }

  }

  /**
   * Hydra task that verifies custom partitioning and PR co-location
   */
  public static void HydraTask_verifyColocatedRegions() {

    testInstance.HydraTask_verifyCustomPartitioning();
    testInstance.HydraTask_verifyCoLocation();
  }

  /**
   * Task to verify colocation
   */
  protected void HydraTask_verifyCoLocation() {
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        String regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        if (aRegion instanceof PartitionedRegion) {
          PartitionedRegion pr = (PartitionedRegion)aRegion;
          verifyBucketCoLocation(pr);
          verifyPrimaryBucketCoLocation(pr);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify colocation");
        }
      }
    }
    else
      for (int i = 0; i < regionDescriptNames.size(); i++) {
        String regionDescriptName = (String)(regionDescriptNames.get(i));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        if (aRegion instanceof PartitionedRegion) {
          PartitionedRegion pr = (PartitionedRegion)aRegion;
          verifyBucketCoLocation(pr);
          verifyPrimaryBucketCoLocation(pr);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify colocation");
        }
      }
  }

  /**
   * Task to verify the co-location of primary buckets
   * 
   * @param aRegion
   *                Region whose co-location of primaries has to be checked
   */
  protected void verifyPrimaryBucketCoLocation(PartitionedRegion aRegion) {
    String regionName = aRegion.getName();
    ArrayList primaryBucketList = (ArrayList)aRegion
        .getLocalPrimaryBucketsListTestOnly();
    Log.getLogWriter().info(
        "The primary buckets in this data Store for the Partioned Region "
            + regionName);
    if (primaryBucketList == null) {
      RegionAttributes ra = aRegion.getAttributes();
      PartitionAttributes pra = ra.getPartitionAttributes();
      int localMaxMemory = pra.getLocalMaxMemory();
      if (localMaxMemory == 0) {
        Log.getLogWriter().info(
            "This is an accessor, no requirement for verifying colocation");
        return;
      }
      else {
        throw new TestException("Bucket List cannot be null for a dataStore");
      }
    }
    Log.getLogWriter().info(
        "Primary Buckets of " + aRegion.getName() + " "
            + primaryBucketList.toString());
    if (primaryBucketsInTheDataStore == null) {
      Log
          .getLogWriter()
          .info(
              " Setting the reference primary buckets in the Data Store for this vm with the Partitioned Region "
                  + aRegion.getName());
      primaryBucketsInTheDataStore = primaryBucketList;
      primaryBucketReferenceRegion = regionName;
    }
    else {
      Log
          .getLogWriter()
          .info(
              "Reference primary buckets in the Data Store for this vm already set");
      Log.getLogWriter().info(" Verifying for the region " + regionName);

      Iterator iterator = primaryBucketList.iterator();

      while (iterator.hasNext()) {
        Integer currentPrimaryBucket = (Integer)iterator.next();
        if (primaryBucketsInTheDataStore.contains(currentPrimaryBucket)) {
          Log.getLogWriter().info(
              "Both the Regions " + primaryBucketReferenceRegion + " and "
                  + regionName + " have the bucket " + currentPrimaryBucket
                  + " in this node");
        }
        else {
          throw new TestException("Region " + regionName
              + " does not have its bucket " + currentPrimaryBucket
              + " colocated with " + primaryBucketReferenceRegion);
        }
      }

      Log.getLogWriter().info("Looking for missed buckets");

      iterator = primaryBucketsInTheDataStore.iterator();

      while (iterator.hasNext()) {
        Integer referenceRegionBucket = (Integer)iterator.next();
        if (primaryBucketList.contains(referenceRegionBucket)) {
          Log.getLogWriter().info(
              "Both the Regions " + bucketReferenceRegion + " and "
                  + regionName + " have the bucket " + referenceRegionBucket
                  + " in this node");
        }
        else {
          throw new TestException("Region " + regionName
              + " does not have its bucket " + referenceRegionBucket
              + " not colocated");
        }
      }
    }

  }

  /**
   * Task to verify the colocation of the buckets
   * 
   * @param aRegion
   *                Region whose bucket co-location to be checked with the
   *                reference PR
   */
  protected void verifyBucketCoLocation(PartitionedRegion aRegion) {
    String regionName = aRegion.getName();
    ArrayList bucketList = (ArrayList)aRegion.getLocalBucketsListTestOnly();

    if (bucketList == null) {
      RegionAttributes ra = aRegion.getAttributes();
      PartitionAttributes pra = ra.getPartitionAttributes();
      int localMaxMemory = pra.getLocalMaxMemory();
      if (localMaxMemory == 0) {
        Log.getLogWriter().info(
            "This is an accessor, no requirement for verifying colocation");
        return;
      }
      else {
        throw new TestException("Bucket List cannot be null for a dataStore");
      }
    }
    Log.getLogWriter()
        .info(
            "The buckets in this data Store for the Partioned Region "
                + regionName);
    Log.getLogWriter().info(
        "Buckets of " + aRegion.getName() + " " + bucketList.toString());
    if (bucketsInTheDataStore == null) {
      Log
          .getLogWriter()
          .info(
              " Setting the reference buckets in the Data Store for this vm with the Partitioned Region "
                  + aRegion.getName());
      bucketsInTheDataStore = bucketList;
      bucketReferenceRegion = regionName;
    }
    else {
      Log
          .getLogWriter()
          .info(
              "Reference primary buckets in the Data Store for this vm already set");
      Log.getLogWriter().info(" Verifying for the region " + regionName);

      Iterator iterator = bucketList.iterator();

      while (iterator.hasNext()) {
        Integer currentRegionBucket = (Integer)iterator.next();
        if (bucketsInTheDataStore.contains(currentRegionBucket)) {
          Log.getLogWriter().info(
              "Both the Regions " + bucketReferenceRegion + " and "
                  + regionName + " have the bucket " + currentRegionBucket
                  + " in this node");
        }
        else {
          throw new TestException("Region " + regionName
              + " does not have its bucket " + currentRegionBucket
              + " not colocated");
        }
      }

      Log.getLogWriter().info("Looking for missed buckets");

      iterator = bucketsInTheDataStore.iterator();

      while (iterator.hasNext()) {
        Integer referenceRegionBucket = (Integer)iterator.next();
        if (bucketList.contains(referenceRegionBucket)) {
          Log.getLogWriter().info(
              "Both the Regions " + bucketReferenceRegion + " and "
                  + regionName + " have the bucket " + referenceRegionBucket
                  + " in this node");
        }
        else {
          throw new TestException("Region " + regionName
              + " does not have its bucket " + referenceRegionBucket
              + " not colocated");
        }
      }
    }
  }

  /**
   * Task to verify custom partitioning.
   */
  public synchronized void HydraTask_verifyCustomPartitioning() {
    for (int i = 0; i < regionDescriptNames.size(); i++) {
      String regionDescriptName = (String)(regionDescriptNames.get(i));
      String regionName = RegionHelper.getRegionDescription(regionDescriptName)
          .getRegionName();
      Region aRegion = theCache.getRegion(regionName);
      if (aRegion instanceof PartitionedRegion) {
        PartitionedRegion pr = (PartitionedRegion)aRegion;
        verifyCustomPartitioning(pr);
      }
      else {
        Log
            .getLogWriter()
            .info(
                "The region "
                    + regionName
                    + " is not partitioned region and hence need not verify custom partitioning");
      }
    }
  }

  /**
   * The actual task which tests the custom partitioning
   * 
   * @param aRegion
   *                Region whose custom partitioning of buckets to be verified
   */
  protected void verifyCustomPartitioning(PartitionedRegion aRegion) {

    PartitionedRegion pr = (PartitionedRegion)aRegion;
    int totalBuckets = pr.getTotalNumberOfBuckets();
    RegionAttributes attr = aRegion.getAttributes();
    PartitionAttributes prAttr = attr.getPartitionAttributes();
    int redundantCopies = prAttr.getRedundantCopies();
    int expectedNumCopies = redundantCopies + 1;
    int verifyBucketCopiesBucketId = 0;

    while (true) {

      if (verifyBucketCopiesBucketId >= totalBuckets) {
        break; // we have verified all buckets
      }

      Log.getLogWriter().info(
          "Verifying data for bucket id " + verifyBucketCopiesBucketId
              + " out of " + totalBuckets + " buckets");
      List<BucketDump> listOfMaps = null;
      try {
        listOfMaps = pr.getAllBucketEntries(verifyBucketCopiesBucketId);
      }
      catch (ForceReattemptException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }

      // check that we have the correct number of copies of each bucket
      // listOfMaps could be size 0; this means we have no entries in this
      // particular bucket
      int size = listOfMaps.size();
      if (size == 0) {
        Log.getLogWriter().info(
            "Bucket " + verifyBucketCopiesBucketId + " is empty");
        verifyBucketCopiesBucketId++;
        continue;
      }

      if (size != expectedNumCopies) {
        throw new TestException("For bucketId " + verifyBucketCopiesBucketId
            + ", expected " + expectedNumCopies + " bucket copies, but have "
            + listOfMaps.size());
      }
      else {
        Log.getLogWriter().info(
            "For bucketId " + verifyBucketCopiesBucketId + ", expected "
                + expectedNumCopies + " bucket copies, and have "
                + listOfMaps.size());
      }

      Log.getLogWriter().info(
          "Validating co-location for all the redundant copies of the bucket with Id : "
              + verifyBucketCopiesBucketId);
      // Check that all copies of the buckets have the same data
      for (int i = 0; i < listOfMaps.size(); i++) {
        BucketDump dump = listOfMaps.get(i);
        Map map = dump.getValues();
        if (map.size() != 0) { // bucket not empty
           verifyCustomPartition(map, verifyBucketCopiesBucketId);
           verifyUniqueBucketForCustomPartioning(verifyBucketCopiesBucketId);
        }
      }

      verifyBucketCopiesBucketId++;
    }

  }

  protected void verifyCustomPartition(Map map, int bucketid) {

    Iterator iterator = map.entrySet().iterator();
    Map.Entry entry = null;
    Object key = null;

    while (iterator.hasNext()) {
      entry = (Map.Entry)iterator.next();
      key = entry.getKey();

      if (ParRegBB.getBB().getSharedMap().get(
          "RoutingObjectForBucketid:" + bucketid) == null) {
        Log.getLogWriter().info(
            "RoutingObject for the bucket id to be set in the BB");
        ParRegBB.getBB().getSharedMap().put(
            "RoutingObjectForBucketid:" + bucketid,
            InitImageBB.getBB().getSharedMap().get(key));
        ParRegBB.getBB().getSharedMap().put(
            "RoutingObjectKeyBucketid:" + bucketid, key);
        Log.getLogWriter().info(
            "BB value set to "
                + InitImageBB.getBB().getSharedMap().get(key).toString());
      }
      else {
        Log.getLogWriter().info("Checking the value for the routing object ");
        String blackBoardRoutingObject = ParRegBB.getBB().getSharedMap().get(
            "RoutingObjectForBucketid:" + bucketid).toString();
        String keyRoutingObject = InitImageBB.getBB().getSharedMap().get(key)
            .toString();
        if (!keyRoutingObject.equalsIgnoreCase(blackBoardRoutingObject)) {
          throw new TestException(
              "Expected same routing objects for the entries in this bucket id "
                  + bucketid + "but got different values "
                  + blackBoardRoutingObject + " and " + keyRoutingObject);
        }
        else {
          Log.getLogWriter().info(
              "Got the expected values "
                  + blackBoardRoutingObject
                  + " and "
                  + keyRoutingObject
                  + " for the keys "
                  + ParRegBB.getBB().getSharedMap().get(
                      "RoutingObjectKeyBucketid:" + bucketid) + " and " + key);
        }
      }
    }
  }

  /**
   * Task to verify that there is only a single bucket id for a routing Object
   * 
   */
  protected void verifyUniqueBucketForCustomPartioning(int bucketId) {

    if (bucketId == 0) {
      Log
          .getLogWriter()
          .info(
              "This is the first bucket, so no validation required as there is no bucket to be compared");
      return;
    }
    else {
      for (int i = 0; i < bucketId; i++) {
        if (!(ParRegBB.getBB().getSharedMap().get(
            "RoutingObjectForBucketid:" + i) == null)) {
          String referenceValue = ParRegBB.getBB().getSharedMap().get(
              "RoutingObjectForBucketid:" + i).toString();
          String currentValue = ParRegBB.getBB().getSharedMap().get(
              "RoutingObjectForBucketid:" + bucketId).toString();
          Log.getLogWriter().info("currentValue: " + currentValue);
          Log.getLogWriter().info("referenceValue: " + referenceValue);

          if (currentValue.equalsIgnoreCase(referenceValue)) {
            throw new TestException("Two buckets with the id " + i + " and "
                + bucketId + " have the same routing Object " + referenceValue);
          }
          else {
            Log.getLogWriter().info(
                "As expected the bucket with ids " + i + " and " + bucketId
                    + " have the different routing Object " + currentValue
                    + " and " + referenceValue);
          }

        }

      }
    }

  }

  /**
   * Hydra task to verify the contents of the region after all ops.
   * 
   */
  public static void HydraTask_verifyRegionContents() {
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        String regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        testInstance.verifyRegionContents(aRegion);
      }
    }
    else
      for (int i = 0; i < regionDescriptNames.size(); i++) {
        String regionDescriptName = (String)(regionDescriptNames.get(i));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        testInstance.verifyRegionContents(aRegion);
      }
  }

  /**
   * Verify the contents of the region, taking into account the keys that were
   * destroyed, invalidted, etc (as specified in keyIntervals) Throw an error of
   * any problems are detected.
   */
  public void verifyRegionContents(Region aRegion) {
    InitImageBB.getBB().printSharedCounters();
    NameBB.getBB().printSharedCounters();
    StringBuffer sizeErrStr = new StringBuffer();
    StringBuffer errStr = new StringBuffer();

    // check region size
    long numKeys = aRegion.size();
    if (totalNumKeys != numKeys) {
      String tmpStr = "Expected " + totalNumKeys + " keys, but there are "
          + numKeys;
      Log.getLogWriter().info(tmpStr);
      sizeErrStr.append(tmpStr + "\n");
    }
    Log.getLogWriter().info(
        "In verifyRegionContents, region has " + numKeys + " keys");

    // iterate keys
    long lastLogTime = System.currentTimeMillis();
    int numKeysToCheck = keyIntervals.getNumKeys() + numNewKeys;
    Log.getLogWriter().info("In verifyRegionContents, iterating through " + numKeysToCheck + " key names");
    for (int i = 1; i <= numKeysToCheck; i++) {
      Object key = NameFactory.getObjectNameForCounter(i);
      //Log.getLogWriter().info("checking key " + key);
      try {
        if (((i >= keyIntervals.getFirstKey(KeyIntervals.NONE)) && (i <= keyIntervals
            .getLastKey(KeyIntervals.NONE)))
            || ((i >= keyIntervals.getFirstKey(KeyIntervals.GET)) && (i <= keyIntervals
                .getLastKey(KeyIntervals.GET)))) {
          // this key was untouched after its creation
          checkContainsKey(aRegion, key, true, "key was untouched");
          checkContainsValueForKey(aRegion, key, true, "key was untouched");
          Object value = PdxTest.toValueHolder(aRegion.get(key));
          checkValue(key, value);
        }
        else if ((i >= keyIntervals.getFirstKey(KeyIntervals.INVALIDATE))
            && (i <= keyIntervals.getLastKey(KeyIntervals.INVALIDATE))) {
          checkContainsKey(aRegion, key, true, "key was invalidated");
          checkContainsValueForKey(aRegion, key, false, "key was invalidated");
        }
        else if ((i >= keyIntervals.getFirstKey(KeyIntervals.LOCAL_INVALIDATE))
            && (i <= keyIntervals.getLastKey(KeyIntervals.LOCAL_INVALIDATE))) {
          // this key was locally invalidated
          checkContainsKey(aRegion, key, true, "key was locally invalidated");
          checkContainsValueForKey(aRegion, key, true,
              "key was locally invalidated");
          Object value = PdxTest.toValueHolder(aRegion.get(key));
          checkValue(key, value);
        }
        else if ((i >= keyIntervals.getFirstKey(KeyIntervals.DESTROY))
            && (i <= keyIntervals.getLastKey(KeyIntervals.DESTROY))) {
          // this key was destroyed
          checkContainsKey(aRegion, key, false, "key was destroyed");
          checkContainsValueForKey(aRegion, key, false, "key was destroyed");
        }
        else if ((i >= keyIntervals.getFirstKey(KeyIntervals.LOCAL_DESTROY))
            && (i <= keyIntervals.getLastKey(KeyIntervals.LOCAL_DESTROY))) {
          // this key was locally destroyed
          checkContainsKey(aRegion, key, true, "key was locally destroyed");
          checkContainsValueForKey(aRegion, key, true,
              "key was locally destroyed");
          Object value = PdxTest.toValueHolder(aRegion.get(key));
          checkValue(key, value);
        }
        else if ((i >= keyIntervals
            .getFirstKey(KeyIntervals.UPDATE_EXISTING_KEY))
            && (i <= keyIntervals.getLastKey(KeyIntervals.UPDATE_EXISTING_KEY))) {
          // this key was updated
          checkContainsKey(aRegion, key, true, "key was updated");
          checkContainsValueForKey(aRegion, key, true, "key was updated");
          Object value = PdxTest.toValueHolder(aRegion.get(key));
          checkUpdatedValue(key, value);
        }
        else if (i > keyIntervals.getNumKeys()) {
          // key was newly added
          checkContainsKey(aRegion, key, true, "key was new");
          checkContainsValueForKey(aRegion, key, true, "key was new");
          Object value = PdxTest.toValueHolder(aRegion.get(key));
          checkValue(key, value);
        }
      }
      catch (TestException e) {
        Log.getLogWriter().info(TestHelper.getStackTrace(e));

         Set failedOps = ParRegBB.getBB().getFailedOps(ParRegBB.FAILED_TXOPS);
         Set inDoubtOps = ParRegBB.getBB().getFailedOps(ParRegBB.INDOUBT_TXOPS);
         if (!failedOps.contains(key) && !inDoubtOps.contains(key)) {
            errStr.append(e.getMessage() + "\n");
         }
      }

      if (System.currentTimeMillis() - lastLogTime > LOG_INTERVAL_MILLIS) {
        Log.getLogWriter()
            .info("Verified key " + i + " out of " + totalNumKeys);
        lastLogTime = System.currentTimeMillis();
      }
    }

    Set failedOps = ParRegBB.getBB().getFailedOps(ParRegBB.FAILED_TXOPS);
    StringBuffer error = new StringBuffer();
    if (failedOps.size() > 0) {
      if (errStr.length() > 0) {
         error.append("Test detected BUG 43428 (" + failedOps.size() + " failed transactions) with additional issues reported\n");
         error.append(sizeErrStr);
         error.append(errStr); 
         throw new TestException(error.toString());
      } else {
/* The behavior reported in BUG 43428 is a design issue which will not be addressed in the near future.  Don't report a failure if it is only BUG 43428 ... log a message though to help folks understand why the inconsistencies don't cause an exception to be thrown. */
         error.append("Test detected BUG 43428 (" + failedOps.size() + " failed transactions) with no additional issues reported\n");
         error.append(sizeErrStr);
         error.append(errStr); 
         //throw new TestException(error.toString());
         Log.getLogWriter().info("Inconsistencies are related to Transaction HA behavior (not currently supported): " + error.toString());
      } 
    } else if (ParRegBB.getBB().getFailedOps(ParRegBB.INDOUBT_TXOPS).size() > 0) {
      failedOps = ParRegBB.getBB().getFailedOps(ParRegBB.INDOUBT_TXOPS);
      if (errStr.length() > 0) {
         error.append("Test reported " + failedOps.size() + " TransactionInDoubtExceptions with additional issues reported\n");
         error.append(sizeErrStr);
         error.append(errStr); 
         throw new TestException(error.toString());
      } else {
/* The behavior reported in BUG 43428 is a design issue which will not be addressed in the near future.  Don't report a failure if it is only BUG 43428 ... log a message though to help folks understand why the inconsistencies don't cause an exception to be thrown. */
         error.append("Test reported " + failedOps.size() + " TransactionInDoubtExceptions with no additional issues reported\n");
         error.append(sizeErrStr);
         error.append(errStr); 
         //throw new TestException(error.toString());
         Log.getLogWriter().info("Inconsistencies are related to Transaction HA behavior (not currently supported): " + error.toString());
      }
    } else if (errStr.length() > 0) {
       // shutdownHook will cause all members to dump partitioned region info
       sizeErrStr.append(errStr);
       throw new TestException(sizeErrStr.toString());
    }
  }

  /**
   * Hydra task to verify bucket copies.
   */
  public static void HydraTask_verifyBucketCopies() {
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        String regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        testInstance.verifyBucketCopies(aRegion);
      }
    }
    else
      for (int i = 0; i < regionDescriptNames.size(); i++) {
        String regionDescriptName = (String)(regionDescriptNames.get(i));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        testInstance.verifyBucketCopies(aRegion);
      }
  }

  /**
   * Hydra task to verify bucket copies. This must be called repeatedly by the
   * same thread until StopSchedulingTaskOnClientOrder is thrown.
   */
  public void verifyBucketCopies(Region aRegion) {
    try {
      RegionAttributes attr = aRegion.getAttributes();
      PartitionAttributes prAttr = attr.getPartitionAttributes();
      int redundantCopies = 0;
      if (prAttr != null) {
        redundantCopies = prAttr.getRedundantCopies();
      }
      // the following call throws StopSchedulingTaskOnClientOrder when
      // completed
      ParRegUtil.verifyBucketCopies(aRegion, redundantCopies);
    }
    catch (TestException e) {
      ParRegUtil.dumpAllDataStores(aRegion);
      throw new TestException(TestHelper.getStackTrace(e));
    }
  }

  /**
   * Task to put the PR key set in the BB so they can be used by the client vms
   */
  public static void HydraTask_putKeySetInBB() {
    for (int i = 0; i < regionDescriptNames.size(); i++) {
      String regionDescriptName = (String)(regionDescriptNames.get(i));
      String regionName = RegionHelper.getRegionDescription(regionDescriptName)
          .getRegionName();
      Region aRegion = theCache.getRegion(regionName);
      boolean isKeySetInBB = testInstance.putKeySetInBB(aRegion);
      if (isKeySetInBB) {
        return;
      }
    }
  }

  /**
   * Task to put the key set in BB
   */
  public boolean putKeySetInBB(Region aRegion) {    
    int totalNumOfBuckets = ((PartitionedRegion)aRegion)
        .getTotalNumberOfBuckets();
    int totalDataStoreNodes = ((PartitionedRegion)aRegion).getAllNodes().size();
    ArrayList keyList = new ArrayList();
    Set keySet = ((PartitionedRegion)aRegion).keys();
    keyList.addAll(keySet);
    if (parReg.ParRegBB.getBB().getSharedMap().get(KEY_LIST) == null) {
      parReg.ParRegBB.getBB().getSharedMap().put(KEY_LIST, keyList);
      parReg.ParRegBB.getBB().getSharedMap().put(PR_TOTAL_BUCKETS,
          new Integer(totalNumOfBuckets));
      parReg.ParRegBB.getBB().getSharedMap().put(PR_TOTAL_DATASTORES,
          new Integer(totalDataStoreNodes));
      return true;
    }
    else {
      Log.getLogWriter().info("Key set already kept in BB");
      return true;
    }
  }

  /**
   * Task to allow for additional dataStores coming online (after putKeySetInBB)
   * See rebalance/randomFunctionExecutionWithRebalance.conf.
   */
  public synchronized static void HydraTask_incPR_TOTAL_DATASTORES() {
    hydra.blackboard.SharedMap sMap = parReg.ParRegBB.getBB().getSharedMap();
    int totalDataStoreNodes = ((Integer)sMap.get(PR_TOTAL_DATASTORES))
        .intValue();
    sMap.put(PR_TOTAL_DATASTORES, new Integer(++totalDataStoreNodes));
  }

  /**
   * Dummy task for Failover tests. - TBD
   */
  public static void HydraTask_killVms() {
    hydra.MasterController.sleepForMs(5000);
    try {
      hydra.MasterController.sleepForMs(5000);
      ClientVmInfo info = ClientVmMgr.stop("Killing the VM",
          ClientVmMgr.MEAN_KILL, ClientVmMgr.IMMEDIATE);
    }
    catch (ClientVmNotFoundException e) {
      Log.getLogWriter().warning(" Exception while killing client ", e);
    }
  }

  public static void HydraTask_waitForEventsReceival() {
    if (testInstance == null) {
      testInstance = new ParRegColocation();
    }
    testInstance.waitForEventsReceival();
  }

  public void waitForEventsReceival() {
    // wait for 50 seconds of client silence to allow everything to be
    // pushed to clients
    SilenceListener.waitForSilence(50, 5000);
  }

  /**
   * Hydra task to stop/start numVmsToStop at a time.
   */
  public static void HydraTask_stopStartVms() {
    long minTaskGranularitySec = TestConfig.tab().longAt(
        TestHelperPrms.minTaskGranularitySec);
    long minTaskGranularityMS = minTaskGranularitySec
        * TestHelper.SEC_MILLI_FACTOR;
    long startTime = System.currentTimeMillis();
    do {
      ((ParRegColocation)testInstance).stopStartVms();
      long timeToStop = ParRegBB.getBB().getSharedCounters().read(
          ParRegBB.TimeToStop);
      if (timeToStop > 0) { // ops have ended
        throw new StopSchedulingTaskOnClientOrder("Ops have completed");
      }
    } while (System.currentTimeMillis() - startTime < minTaskGranularityMS);
  }

  /**
   * Stop and start ParRegPrms.numVMsToStop. Do not return until the VMs have
   * restarted and reinitialized
   */
  protected void stopStartVms() {
    int numRegions = CacheHelper.getCache().rootRegions().size();
    PRObserver.initialize();
    int numVMsToStop = TestConfig.tab().intAt(ParRegPrms.numVMsToStop);
    Log.getLogWriter().info(
        "In stopStartVms, choosing " + numVMsToStop + " vm(s) to stop...");
    List vmList = getDataStoreVms();
    List targetVms = new ArrayList();
    List stopModes = new ArrayList();
    for (int i = 1; i <= numVMsToStop; i++) {
      int randInt = TestConfig.tab().getRandGen().nextInt(0, vmList.size() - 1);
      targetVms.add(vmList.get(randInt));
      vmList.remove(randInt);
      stopModes.add(TestConfig.tab().stringAt(ParRegPrms.stopModes));
    }
    StopStartVMs.stopStartVMs(targetVms, stopModes);
    if (ConfigPrms.getHadoopConfig() != null) {  // recover the PRs + 1 HDFS AEQ for all colocated regions
       numRegions++;
    }
    PRObserver.waitForRebalRecov(targetVms, 1, numRegions, null, null, false);
    Log.getLogWriter().info("Done in ParRegColocation.stopStartVms()");
  }

  /**
   * Return a list of the ClientVmInfo for all data store VMs. These are
   * obtained from the blackboard map.
   */
  protected List getDataStoreVms() {
    List aList = new ArrayList();
    Map aMap = ParRegBB.getBB().getSharedMap().getMap();
    Iterator it = aMap.keySet().iterator();
    while (it.hasNext()) {
      Object key = it.next();
      if (key instanceof String) {
        if (((String)key).startsWith(DataStoreVmStr)) {
          aList.add(new ClientVmInfo((Integer)(aMap.get(key)), null, null));
        }
      }
    }
    return aList;
  }
  public static void HydraTask_verifyPRListenerInvocation() {
    testInstance.verifyPRListenerInvocation();
  }


  /**
   * Task to verify listener invocation
   */
  protected void verifyPRListenerInvocation() {
    for (int i = 0; i < regionDescriptNames.size(); i++) {
      String regionDescriptName = (String)(regionDescriptNames.get(i));
      String regionName = RegionHelper.getRegionDescription(regionDescriptName)
          .getRegionName();
      Region aRegion = theCache.getRegion(regionName);
      if (aRegion instanceof PartitionedRegion) {
        PartitionedRegion pr = (PartitionedRegion)aRegion;
        if (pr.getPartitionListeners() != null && pr.getPartitionListeners().length != 0) {
          hydra.Log.getLogWriter().info(
              "Verifying the listener invocation for the region "
                  + aRegion.getName() + " because the region has PR listener");
          verifyPRListenerInvocation(pr);
        }
      }
    }
  }

  protected void verifyPRListenerInvocation(PartitionedRegion aRegion) {
    UpdateBBPartitionListener listener = (UpdateBBPartitionListener)aRegion
        .getPartitionListeners()[1];
    ArrayList listenerInvocationIdsForNode = (ArrayList)listener
        .getBucketIdsList();
    ArrayList primaryBucketListForNode = (ArrayList)aRegion
        .getLocalPrimaryBucketsListTestOnly();
    if (listenerInvocationIdsForNode.size() != primaryBucketListForNode.size()) {
      throw new TestException("PRListener invocation for the region "
          + aRegion.getName() + " was for bucket ids "
          + listenerInvocationIdsForNode + " on this node "
          + " but primary buckets on this node are" + primaryBucketListForNode);
    }

    if (!listenerInvocationIdsForNode.containsAll(primaryBucketListForNode)) {
      throw new TestException("PRListener invocation for the region "
          + aRegion.getName() + " was for bucket ids "
          + listenerInvocationIdsForNode + " on this node "
          + " but primary buckets on this node are" + primaryBucketListForNode);
    }

  }

  /**
   * Hydra task that verifies custom partitioning and PR co-location
   */
  public static void HydraTask_verifyPrimaryColocation() {
    testInstance.verifyPrimaryColocation();
  } 
  /**
   * Task to verify colocation
   */
  protected void verifyPrimaryColocation() {
    if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      for (int j = 0; j < bridgeRegionDescriptNames.size(); j++) {
        String regionDescriptName = (String)(bridgeRegionDescriptNames.get(j));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        if (aRegion instanceof PartitionedRegion) {
          PartitionedRegion pr = (PartitionedRegion)aRegion;
          verifyPrimaryBucketCoLocation(pr);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify colocation");
        }
      }
    }
    else
      for (int i = 0; i < regionDescriptNames.size(); i++) {
        String regionDescriptName = (String)(regionDescriptNames.get(i));
        String regionName = RegionHelper.getRegionDescription(
            regionDescriptName).getRegionName();
        Region aRegion = theCache.getRegion(regionName);
        if (aRegion instanceof PartitionedRegion) {
          PartitionedRegion pr = (PartitionedRegion)aRegion;
          verifyPrimaryBucketCoLocation(pr);
        }
        else {
          Log
              .getLogWriter()
              .info(
                  "The region "
                      + regionName
                      + " is not partitioned region and hence need not verify colocation");
        }
      }
  }
}
