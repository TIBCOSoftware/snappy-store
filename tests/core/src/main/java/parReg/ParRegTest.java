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
package parReg;

import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.ToDataException;
import com.gemstone.gemfire.admin.AdminDistributedSystem;
import com.gemstone.gemfire.admin.RegionNotFoundException;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.client.ServerOperationException;
import com.gemstone.gemfire.cache.control.RebalanceFactory;
import com.gemstone.gemfire.cache.control.RebalanceOperation;
import com.gemstone.gemfire.cache.control.RebalanceResults;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.gemstone.gemfire.cache.query.*;
import com.gemstone.gemfire.cache.util.BridgeWriterException;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.LockServiceDestroyedException;
import com.gemstone.gemfire.distributed.PoolCancelledException;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.gemstone.gemfire.pdx.WritablePdxInstance;
import delta.DeltaValueHolder;
import hdfs.HDFSUtil;
import hydra.*;
import hydra.CachePrms;
import memscale.MemScaleBB;
import memscale.OffHeapHelper;
import pdx.PdxTest;
import pdx.PdxTestVersionHelper;
import rebalance.RebalanceUtil;
import util.*;

/** Test class for serial and concurrent partitioned region tests with
 *  verification.
 */
public class ParRegTest {
    
/* The singleton instance of ParRegTest in this VM */
static public ParRegTest testInstance;
    
// operations
static protected final int ENTRY_ADD_OPERATION = 1;
static protected final int ENTRY_DESTROY_OPERATION = 2;
static protected final int ENTRY_INVALIDATE_OPERATION = 3;
static protected final int ENTRY_LOCAL_DESTROY_OPERATION = 4;
static protected final int ENTRY_LOCAL_INVALIDATE_OPERATION = 5;
static protected final int ENTRY_UPDATE_OPERATION = 6;
static protected final int ENTRY_GET_OPERATION = 7;
static protected final int ENTRY_GET_NEW_OPERATION = 8;
static protected final int ENTRY_PUTALL_OPERATION = 9;
static protected final int ENTRY_DESTROY_PERCENT_OPERATION = 10;
static protected final int ENTRY_LOCAL_DESTROY_PERCENT_OPERATION = 11;
static protected final int ENTRY_PUT_IF_ABSENT_OPERATION = 12;          // unrestrictued putIfAbsent
static protected final int ENTRY_PUT_IF_ABSENT_AS_GET_OPERATION = 13;      
static protected final int ENTRY_PUT_IF_ABSENT_AS_CREATE_OPERATION = 14;
static protected final int ENTRY_REMOVE_OPERATION = 15;                 // unrestricted remove
static protected final int ENTRY_REMOVE_AS_NOOP_OPERATION = 16;
static protected final int ENTRY_REPLACE_OPERATION = 17;                // unrestricted replace
static protected final int ENTRY_REPLACE_AS_UPDATE_OPERATION = 18;
static protected final int ENTRY_REPLACE_AS_NOOP_OPERATION = 19;
static protected final int ENTRY_REPLACE_NO_INVAL_OPERATION = 20;
static protected final int ENTRY_REPLACE_OLD_OPERATION = 21;            // unrestricted replaceAll
static protected final int ENTRY_REPLACE_OLD_AS_UPDATE_OPERATION = 22;
static protected final int ENTRY_REPLACE_OLD_AS_NOOP_OPERATION = 23;
static protected final int ENTRY_REPLACE_OLD_NO_INVAL_OPERATION = 24;
static protected final int QUERY_OPERATION = 25;
static protected final int CREATE_INDEX_OPERATION = 26;
static protected final int REMOVE_INDEX_OPERATION = 27;
    
// instance fields
protected long minTaskGranularitySec;       // the task granularity in seconds
protected long minTaskGranularityMS;        // the task granularity in milliseconds
protected int numOpsPerTask;                // the number of operations to execute per task
public boolean isSerialExecution;        // true if this test is serial, false otherwise
protected int numThreadsInClients;          // the total number of working client threads in this test 
protected RandomValues randomValues =       // instance of random values, used as the value for puts
          new RandomValues();               
public Region aRegion;                   // the accessor for the partitioned region in this client
                                            //    or a local scoped region in a bridge configuration
protected int upperThreshold;               // value of ParRegPrms.upperThreshold
protected int lowerThreshold;               // value of ParRegPrms.lowerThreshold
protected int numVMsToStop;                 // value of ParRegPrms.numVMsToStop
protected RegionDefinition regDef;          // the region definition used by this VM
protected CacheDefinition cacheDef;         // the cache definition used by this VM
public boolean highAvailability;         // value of ParRegPrms.highAvailability
protected boolean lockOperations;           // value of ParRegPrms.lockOperations
protected boolean regionLocallyDestroyed;   // true if this VM has locally destroyed the PR, false otherwise
protected boolean cacheIsClosed;            // true if this VM has closed the cache, false otherwise
protected boolean disconnected;             // true if this VM is disconnected from the distributed system, false otherwise
protected int secondsToRun;                 // number of seconds to allow tasks
protected int numThreadsInThisVM = 0;       // the number of threads in this VM
public boolean hasPRCacheLoader;         // true if the PR has a cache loader
public int redundantCopies;              // the number of redundantCopies configured for this test
protected int currentValidateKeyIndex = 1; // used for interval validation
protected int currentIntervalKeyIndex = 1; // used for interval validation
protected boolean isEmptyClient = false;  // true if this is a bridge client with empty dataPolicy
protected boolean isThinClient = false; // true if this is a bridge client with eviction to keep it small

// instance fields used to verify the contents of a partitioned region in serial tests
protected Map regionSnapshot;               // a "picture" of the partitioned region
protected Set destroyedKeys;                // a set of destroyed keys

protected Map txRegionSnapshot;             // a backup copy of the regionSnapshot (see saveRegionSnapshot)
protected Set txDestroyedKeys;              // a backup copy of the destroyedKeys (see saveRegionSnapshot)

protected DistributedLockService distLockService; // the distributed lock service for this VM
public boolean isBridgeConfiguration;    // true if this test is being run in a bridge configuration, false otherwise
public boolean isBridgeClient;           // true if this vm is a bridge client, false otherwise
public boolean uniqueHostsOn;           // true if GemFirePrms-enforceUniqueHost is set true
public int exceptionCount;          // used to determine which threads in the current vm have received exceptions
public boolean thisVmRunningHAController = false;
protected boolean hasLRUEntryEviction = false;
protected int LRUEntryMax = -1;
protected List<ClientVmInfo> cycleVMs_targetVMs = null;
protected List<ClientVmInfo> cycleVMs_notChosenVMs = null;

// fields to workaround bug 35662
protected String bridgeOrderingWorkaround;  // how to workaround bug 35662
public HydraThreadLocal uniqueKeyIndex = new HydraThreadLocal();
protected boolean uniqueKeys = false;
public boolean isDataStore = true;      // true if this vm is a datastore, false otherwise
public boolean isClientCache = false;   // true if this vm's cache was created via ClientCacheFactory, false otherwise

// lock names
protected static String LOCK_SERVICE_NAME = "MyLockService";
protected static String LOCK_NAME = "MyLock";
    
// String prefixes for event callback object
protected static final String getCallbackPrefix = "Get originated in pid ";
protected static final String createCallbackPrefix = "Create event originated in pid ";
protected static final String updateCallbackPrefix = "Update event originated in pid ";
protected static final String invalidateCallbackPrefix = "Invalidate event originated in pid ";
protected static final String destroyCallbackPrefix = "Destroy event originated in pid ";
protected static final String regionInvalidateCallbackPrefix = "Region invalidate event originated in pid ";
protected static final String regionDestroyCallbackPrefix = "Region destroy event originated in pid ";
    
// when the test desires a recently used key, use this number of the most recently used keys
static protected final int RECENT_HISTORY = 20;
    
// key for blackboard shared map
protected static final String VmIDStr = "VmId_";
protected static final String isDataStoreKey = "isDataStore for vmID ";
protected static final String shutDownAllInProgressKey = "shutDownAllInProgress";
protected static final String expectOfflineExceptionKey = "expectOfflineException";

// fields to coordinate running a method once per VM
protected static volatile MethodCoordinator concVerifyCoordinator = null;
protected static volatile MethodCoordinator registerInterestCoordinator = null;

// list of ways to cause failover
protected static final int CLOSE_CACHE = 0;
protected static final int DISCONNECT = 1;
protected static final int LOCAL_DESTROY = 2;
protected static final int CLOSE_REGION = 3;

// for peer cases, cause failover with all choices
protected static final int MAX_CHOICES = 4;

// for client/server cases, do not use localDestroy or close (see bug 36812)
protected static final int MAX_CHOICES_FOR_CLIENT_SERVER = 2;

final static String objectType = TestConfig.tab().stringAt(
    ValueHolderPrms.objectType, "util.ValueHolder");

protected static boolean diskFilesRecorded = false;

public int offHeapVerifyTargetCount = -1;

// ========================================================================
// initialization methods
    
/** Creates and initializes the singleton instance of ParRegTest in this VM.
 */
public synchronized static void HydraTask_initialize() {
   if (testInstance == null) {
      // initialize
      String regionConfigName = getClientRegionConfigName();
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.initializeRegion(regionConfigName, getCachePrmsName());
      testInstance.initPdxDiskStore();
      testInstance.initializeInstance();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = true;
         testInstance.isDataStore = getIsDataStore();
         testInstance.isEmptyClient = regionConfigName.equals("emptyClientRegion");
         testInstance.isThinClient = regionConfigName.equals("thinClientRegion");
         ParRegUtil.registerInterest(testInstance.aRegion);
      }
   }
   setUniqueKeyIndex(); 
}

/** Creates and initializes the singleton instance of ParRegTest in this VM.
 *  Using new ClientCache/ClientRegion methods (6.5 ease of use APIs).
 */
public synchronized static void HydraTask_initializeWithClientCache() {
   if (testInstance == null) {
      // initialize
      String regionConfigName = getClientRegionConfigName();
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.initializeClientRegion("clientCache", regionConfigName);
      testInstance.initializeInstance();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = true;
         testInstance.isDataStore = getIsDataStore();
         testInstance.isClientCache = true;
         testInstance.isEmptyClient = regionConfigName.equals("emptyClientRegion");
         testInstance.isThinClient = regionConfigName.equals("thinClientRegion");
         ParRegUtil.registerInterest(testInstance.aRegion);
      }
   }
   setUniqueKeyIndex(); 
}
    
/** Creates and initializes the singleton instance of ParRegTest in this VM.
 */
public synchronized static void HydraTask_initializeWithRegDef() {
   if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.initializeWithRegDef();
      testInstance.initializeInstance();
   }
   setUniqueKeyIndex();
}
    
/** Creates and initializes a data store PR in a bridge server.
 */
public synchronized static void HydraTask_initializeBridgeServer() {
   if (testInstance == null) {
      String regionConfigName = getRegionConfigName();
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.initializeRegion(regionConfigName, "cache1");
      testInstance.initPdxDiskStore();
      testInstance.initializeInstance();
      BridgeHelper.startBridgeServer("bridge");
      testInstance.isBridgeClient = false;
      testInstance.isDataStore = getIsDataStore();
   }
   setUniqueKeyIndex(); 
}

/** Creates and initializes an accessor PR in a bridge server.
 */
public synchronized static void HydraTask_initializeBridgeServerAccessor() {
   if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.initializeRegion("PrAccessor", getCachePrmsName());
      testInstance.initializeInstance();
      testInstance.initPdxDiskStore();
      BridgeHelper.startBridgeServer("bridge");
      testInstance.isBridgeClient = false;
      testInstance.isDataStore = getIsDataStore();
   }
   setUniqueKeyIndex();
}
  
/** Creates and initializes the singleton instance of ParRegTest in this VM
 *  for HA testing with a PR accessor.
 */
public synchronized static void HydraTask_HA_initializeAccessor() {
   if (testInstance == null) {
      String regionConfigName = getClientRegionConfigName();
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.initializeRegion(regionConfigName, getCachePrmsName());
      testInstance.initPdxDiskStore();
      testInstance.initializeInstance();
      testInstance.isDataStore = getIsDataStore();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = true;
         testInstance.isEmptyClient = regionConfigName.equals("emptyClientRegion");
         testInstance.isThinClient = regionConfigName.equals("thinClientRegion");
         ParRegUtil.registerInterest(testInstance.aRegion);
      }
   }
   setUniqueKeyIndex(); 
}
    
/** Creates and initializes the singleton instance of ParRegTest in this VM
 *  for HA testing with a PR data store.
 */
public synchronized static void HydraTask_HA_initializeDataStore() {
   if (testInstance == null) {
      String regionConfigName = getRegionConfigName();
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.initializeRegion(regionConfigName, "cache1");
      testInstance.initPdxDiskStore();
      testInstance.initializeInstance();
      testInstance.isDataStore = getIsDataStore();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = false;
         BridgeHelper.startBridgeServer("bridge");
      }
   }
   setUniqueKeyIndex(); 
}
    
/** Creates and initializes the singleton instance of ParRegTest in this VM
 *  for HA testing.
 */
public synchronized static void HydraTask_HA_reinitializeWithClientCache() {
   if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.HA_reinitializeClientRegion();
      testInstance.initializeInstance();
      testInstance.isDataStore = getIsDataStore();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isEmptyClient = testInstance.aRegion.getAttributes().getDataPolicy().isEmpty();
         testInstance.isThinClient = !(testInstance.aRegion.getAttributes().getEvictionAttributes().getAlgorithm().isNone());
         testInstance.isBridgeClient = true;
         boolean repeatUntilSuccess = (testInstance.numVMsToStop > testInstance.redundantCopies); // only happens with persistence
         ParRegUtil.registerInterest(testInstance.aRegion, repeatUntilSuccess);
      }
   }
   setUniqueKeyIndex();
}

/** Creates and initializes the singleton instance of ParRegTest in this VM
 *  for HA testing.
 */
public synchronized static void HydraTask_HA_reinitializeAccessor() {
   if (testInstance == null) {
      PRObserver.installObserverHook();
      testInstance = new ParRegTest();
      testInstance.HA_reinitializeRegion(getCachePrmsName());
      testInstance.initPdxDiskStore();
      testInstance.initializeInstance();
      testInstance.isDataStore = getIsDataStore();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isEmptyClient = testInstance.aRegion.getAttributes().getDataPolicy().isEmpty();
         testInstance.isThinClient = !(testInstance.aRegion.getAttributes().getEvictionAttributes().getAlgorithm().isNone());
         testInstance.isBridgeClient = true;
         boolean repeatUntilSuccess = (testInstance.numVMsToStop > testInstance.redundantCopies); // only happens with persistence
         ParRegUtil.registerInterest(testInstance.aRegion, repeatUntilSuccess);
      }
   }
   setUniqueKeyIndex();
}

/** Set the uniqueKey index for this thread
 * 
 */
protected static void setUniqueKeyIndex() {
  Integer uniqueKeyIndexFromBB = (Integer)ParRegBB.getBB().getSharedMap().get("Thread_" +
       RemoteTestModule.getCurrentThread().getThreadId());
   if (uniqueKeyIndexFromBB == null) {
   testInstance.uniqueKeyIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
   } else {
     testInstance.uniqueKeyIndex.set(uniqueKeyIndexFromBB);
   }
}
    
/** Creates and initializes the singleton instance of ParRegTest in this VM
 *  for HA testing.
 */
public synchronized static void HydraTask_HA_reinitializeDataStore() {
   if (testInstance == null) {
      PRObserver.installObserverHook();
      PRObserver.initialize(RemoteTestModule.getMyVmid());
      testInstance = new ParRegTest();
      testInstance.HA_reinitializeRegion("cache1");
      testInstance.initPdxDiskStore();
      testInstance.initializeInstance();
      testInstance.isDataStore = getIsDataStore();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = false;
         BridgeHelper.startBridgeServer("bridge");
      }
   }
   setUniqueKeyIndex(); 
}

/**
 *  Create a region with the given region description name.
 *
 *  @param regDescriptName The name of a region description.
 */
public void initializeRegion(String regDescriptName) {
  initializeRegion(regDescriptName, "cache1");
}
 
/**
 *  Create a region with the given region description name.
 *
 *  @param regDescriptName The name of a region description.
 */
public void initializeRegion(String regDescriptName, String cachePrmsName) {
   CacheHelper.createCache(cachePrmsName);;
   String key = VmIDStr + RemoteTestModule.getMyVmid();
   String xmlFile = key + ".xml";
   try {
     PoolDescription poolDescr = RegionHelper.getRegionDescription(regDescriptName).getPoolDescription();
     DiskStoreDescription desc = RegionHelper.getRegionDescription(regDescriptName).getDiskStoreDescription();
     String diskStoreName = null;
     if (desc != null) {
         diskStoreName = desc.getName();
     }
     Log.getLogWriter().info("About to generate xml, diskStoreName is " + diskStoreName);
     if(poolDescr != null) {
       CacheHelper.generateCacheXmlFile(cachePrmsName, null, regDescriptName, null, null, poolDescr.getName(), diskStoreName, null, xmlFile);
     }
     else {
       CacheHelper.generateCacheXmlFile(cachePrmsName, null, regDescriptName, null, null, null, diskStoreName, null, xmlFile);
     }
   } catch (HydraRuntimeException e) {
      String errStr = e.toString();
      if ((errStr.indexOf("Cache XML file was already created") >= 0) ||
          (errStr.indexOf("Cache XML file already exists") >= 0)) {
         // ok; we use this to reinitialize returning VMs, so the xml file is already there
      } else {
         throw e;
      }
   }   
   
   if (ConfigPrms.getHadoopConfig() != null) {
      ParRegUtil.createDiskStoreIfNecessary(regDescriptName);
      aRegion = RegionHelper.createRegion(regDescriptName);
   } else {
      aRegion = ParRegUtil.createRegion(cachePrmsName, regDescriptName, xmlFile); 
   }
   ParRegBB.getBB().getSharedMap().put(key, regDescriptName);
}

/**
 *  Create a client region with the given region description name.
 *
 *  @param regionConfig The name of a client region description.
 */
public void initializeClientRegion(String cacheConfig, String regionConfig) {
   ClientCacheHelper.createCache(cacheConfig);

   String key = VmIDStr + RemoteTestModule.getMyVmid();
   String xmlFile = key + ".xml";
   try {
     ClientCacheHelper.generateCacheXmlFile(cacheConfig, regionConfig, xmlFile);
   } catch (HydraRuntimeException e) {
      String errStr = e.toString();
      if ((errStr.indexOf("Cache XML file was already created") >= 0) ||
          (errStr.indexOf("Cache XML file already exists") >= 0)) {
         // ok; we use this to reinitialize returning VMs, so the xml file is already there
      } else {
         throw e;
      }
   }

   aRegion = ParRegUtil.createClientRegion("clientCache", regionConfig, xmlFile);
   ParRegBB.getBB().getSharedMap().put(key, regionConfig);
}
    
/**
 *  Create a region with the given regionDefinition spec name.
 *
 *  @param specName The name of a RegionDefinition spec.
 */
public void initializeWithRegDef() {
   cacheDef = CacheDefinition.createCacheDefinition(CacheDefPrms.cacheSpecs, "cache1");
   regDef = RegionDefinition.createRegionDefinition();
   String key = VmIDStr + RemoteTestModule.getMyVmid();
   String xmlFile = key + ".xml";
   DeclarativeGenerator.createDeclarativeXml(key + ".xml", cacheDef, regDef, true);
   aRegion = CacheUtil.createRegion(cacheDef, regDef, xmlFile);
}

/**
 *  Initialize this test instance
 */
public void initializeInstance() {
   minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, Long.MAX_VALUE);
   if (minTaskGranularitySec == Long.MAX_VALUE)
      minTaskGranularityMS = Long.MAX_VALUE;
   else 
      minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
   numOpsPerTask = TestConfig.tab().intAt(ParRegPrms.numOpsPerTask, Integer.MAX_VALUE);
   isSerialExecution = TestConfig.tab().booleanAt(hydra.Prms.serialExecution);
   upperThreshold = TestConfig.tab().intAt(ParRegPrms.upperThreshold, Integer.MAX_VALUE);
   lowerThreshold = TestConfig.tab().intAt(ParRegPrms.lowerThreshold, -1);

   // choose one bridgeOrderingWorkaround for the entire test (this allows ONEOFs)
   bridgeOrderingWorkaround = TestConfig.tab().stringAt(ParRegPrms.bridgeOrderingWorkaround, "");
   ParRegBB.getBB().getSharedMap().replace("bridgeOrderingWorkaround", null, bridgeOrderingWorkaround);
   bridgeOrderingWorkaround = (String)(ParRegBB.getBB().getSharedMap().get("bridgeOrderingWorkaround"));
   uniqueKeys = bridgeOrderingWorkaround.equalsIgnoreCase("uniqueKeys");
      
   numVMsToStop = TestConfig.tab().intAt(ParRegPrms.numVMsToStop, 1);
   secondsToRun = TestConfig.tab().intAt(ParRegPrms.secondsToRun, 1800);
   cacheIsClosed = false;
   disconnected = false;
   regionLocallyDestroyed = false;
   Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);
   isBridgeConfiguration = bridgeNames != null;
   Integer anInt = Integer.getInteger("numThreads");
   if (anInt != null) {
      numThreadsInThisVM = anInt.intValue();
   }
   isBridgeClient = false;
   redundantCopies = -1;
   RegionAttributes attr = aRegion.getAttributes();
   PartitionAttributes prAttr = attr.getPartitionAttributes();
   if (prAttr != null) { 
      redundantCopies = prAttr.getRedundantCopies();
      long recoveryDelay = prAttr.getRecoveryDelay();
      long startupRecoveryDelay = prAttr.getStartupRecoveryDelay();
      ParRegBB.getBB().getSharedMap().put("recoveryDelay", new Long(recoveryDelay));
      ParRegBB.getBB().getSharedMap().put("startupRecoveryDelay", new Long(startupRecoveryDelay));
   }
   ParRegBB.getBB().getSharedCounters().setIfLarger(ParRegBB.MaxRC, redundantCopies);
   hasPRCacheLoader = (attr.getCacheLoader() != null);
   highAvailability = TestConfig.tab().booleanAt(ParRegPrms.highAvailability, false);
   lockOperations = TestConfig.tab().booleanAt(ParRegPrms.lockOperations, false);
   if (lockOperations) {
      Log.getLogWriter().info("Creating lock service " + LOCK_SERVICE_NAME);
      distLockService = DistributedLockService.create(LOCK_SERVICE_NAME, DistributedSystemHelper.getDistributedSystem());
      Log.getLogWriter().info("Created lock service " + LOCK_SERVICE_NAME);
   }
   
   // set expectOfflineException
   boolean expectOfflineException = ParRegPrms.getUseShutDownAllMembers();
   try {
      Vector<String> aVec = TestConfig.tab().vecAt(ParRegPrms.numVMsToStop);
      int maxNumToStop = 0;
      for (String numToStop: aVec) {
        maxNumToStop = Math.max(maxNumToStop, Integer.valueOf(numToStop));
      }
      if ((!isSerialExecution) && (numVMsToStop > redundantCopies)) {
        expectOfflineException = true;
      }
   } catch (HydraConfigException e) {
     // no vms to stop
   }
   ParRegBB.getBB().getSharedMap().put(expectOfflineExceptionKey, new Boolean(expectOfflineException));

   uniqueHostsOn = DistributedSystemHelper.getGemFireDescription().getEnforceUniqueHost();
   hasLRUEntryEviction = aRegion.getAttributes().getEvictionAttributes() != null;
   if (hasLRUEntryEviction) {
     hasLRUEntryEviction = aRegion.getAttributes().getEvictionAttributes().getAlgorithm().isLRUEntry();
     if (hasLRUEntryEviction) {
        LRUEntryMax = aRegion.getAttributes().getEvictionAttributes().getMaximum();
     }
   }
   if (OffHeapHelper.isOffHeapMemoryConfigured()) {
     MemScaleBB.getBB().getSharedCounters().increment(MemScaleBB.membersWithOffHeap);
   }
   offHeapVerifyTargetCount = TestHelper.getNumThreads();
   
   Log.getLogWriter().info("minTaskGranularitySec " + minTaskGranularitySec + ", " +
                           "minTaskGranularityMS " + minTaskGranularityMS + ", " +
                           "numOpsPerTask " + numOpsPerTask + ", " +
                           "isSerialExecution " + isSerialExecution + ", " +
                           "lockOperations " + lockOperations + ", " +
                           "upperThreshold " + upperThreshold + ", " +
                           "lowerThreshold " + lowerThreshold + ", " +
                           "numThreadsInThisVM " + numThreadsInThisVM + ", " +
                           "numVMsToStop " + numVMsToStop + ", " +
                           "isBridgeConfiguration " + isBridgeConfiguration + ", " +
                           "isDataStore " + isDataStore + ", " +
                           "isClientCache " + isClientCache + ", " +
                           "bridgeOrderingWorkaround " + bridgeOrderingWorkaround + ", " +
                           "uniqueKeys " + uniqueKeys + "," +
                           "hasPRCacheLoader " + hasPRCacheLoader + "," +
                           "uniqueHostsOn " + uniqueHostsOn);
   if (isSerialExecution) { // initialize for verification
      regionSnapshot = new HashMap();
      destroyedKeys = new HashSet();
   }
}

/**
 *  Re-Initialize a VM which has restarted by creating the appropriate region.
 */
public String HA_reinitializeClientRegion() {
   isClientCache = true;
   return HA_reinitializeRegion();
}

public String HA_reinitializeRegion() {
  return HA_reinitializeRegion("cache1");
}

/**
 *  Re-Initialize a VM which has restarted by creating the appropriate region.
 */
public String HA_reinitializeRegion(String cachePrmsName) {
   String key = VmIDStr + RemoteTestModule.getMyVmid();
   String regDescriptName = (String)(ParRegBB.getBB().getSharedMap().get(key));
   String xmlFile = key + ".xml";
   if (isClientCache) {
      aRegion = ParRegUtil.createClientRegion("clientCache", regDescriptName, xmlFile);
   } else if (ConfigPrms.getHadoopConfig() != null) {
      CacheHelper.createCache(cachePrmsName);
      aRegion = RegionHelper.createRegion(regDescriptName);
   } else {
      aRegion = CacheUtil.createRegion(cachePrmsName, regDescriptName, xmlFile);
   }
   initPdxDiskStore();
   Log.getLogWriter().info("After recreating " + aRegion.getFullPath() + ", size is " + aRegion.size());

   // re-initialize the TransactionManager (for the newly created cache) 
   if (getInitialImage.InitImagePrms.useTransactions()) {
      TxHelper.setTransactionManager();
   }

   // init the lockService
   if (lockOperations) {
      Log.getLogWriter().info("Creating lock service " + LOCK_SERVICE_NAME);
      try {
         distLockService = DistributedLockService.create(LOCK_SERVICE_NAME, DistributedSystemHelper.getDistributedSystem());
         Log.getLogWriter().info("Created lock service " + LOCK_SERVICE_NAME);
      } catch (IllegalArgumentException e) {
         // this can happen if we are reinitializing because we closed the cache
         String exceptStr = e.toString();
         if (exceptStr.indexOf("Service named " + LOCK_SERVICE_NAME + " already created") >= 0) {
            Log.getLogWriter().info("Caught " + e + "; continuing test because lock service already exists"); 
         } else {
            throw new TestException(TestHelper.getStackTrace(e));
         }
      }
   }
   return regDescriptName;
}

/** Get the hydra region config name to use to create a region. For
 *  bridge/edge test, ParRegPrms.numEmptyClients and ParRegPrms.numThinClients
 *  are consulted (these will default to 0 for non bridge/edge tests).
 *  So, this is only called for initializing edge clients or peer accessors.
 * @return The hydra region config name.
 */
protected static String getClientRegionConfigName() {
  // determine which region config name to use
  String regionConfigName = "clientRegion";
  int desiredNumEmptyClients = ParRegPrms.getNumEmptyClients();
  long numEmptyClients = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.numEmptyClients);
  if (numEmptyClients <= desiredNumEmptyClients) {
    regionConfigName = "emptyClientRegion";
  } else { // check for thin clients
    int desiredNumThinClients = ParRegPrms.getNumThinClients();
    long numThinClients = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.numThinClients);
    if (numThinClients <= desiredNumThinClients) {
      regionConfigName = "thinClientRegion";
    }
  }
  return regionConfigName;
}

/** Get the hydra region config name to use to create a region. For
 *  bridge/edge test, ParRegPrms.numPrAccessors is consulted. 
 *  So, this is only called for initializing peer or bridge PR datastores
 *  or pr accessors.
 * @return The hydra region config name.
 */
protected static String getRegionConfigName() {
  // determine which region config name to use
  String regionConfigName = "dataStoreRegion";
  int desiredNumAccessors = ParRegPrms.getNumberOfAccessors();
  long numAccessors = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.numOfAccessors);
  if (numAccessors <= desiredNumAccessors) {
    regionConfigName = "accessorRegion";
  } 
  return regionConfigName;
}

/** Disconnect from the ds, in preparation of calling offline disk validation
 *  and compaction
 */
public static void HydraTask_disconnect() {
   DistributedSystemHelper.disconnect();
   testInstance = null; 
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.DiskRecoveryCounter);
   PRObserver.initialize();
}

/** Initialize blackboard counters in preparation of disk recovery in all vms
 *  in end task.
 */
public static void HydraTask_prepareForRecovery() {
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.numOfAccessors);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.numEmptyClients);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.numThinClients);
}

/** Get the appropriate hydra cache param name.
 * 
 */
public static String getCachePrmsName() {
  Vector cacheNames = TestConfig.tab().vecAt(CachePrms.names);
  String cachePrmsName = "cache1";
  if (cacheNames.contains("accessorCache")) { // prefer accessor cache if defined
    cachePrmsName = "accessorCache";
  }
  return cachePrmsName;
}

// ========================================================================
// hydra task methods

/** Hydra task method for serial round robin tests.
 */
public static void HydraTask_doRROpsAndVerify() {
   testInstance.doRROpsAndVerify();
}

/** Hydra task method for concurrent tests with no verification.
 */
public static void HydraTask_doConcOps() {
   PdxTest.initClassLoader();
   testInstance.doConcOps();
}

/** Do random operations until the PR contains roughly upperThreshold
 *  entries.
 */
public static void HydraTask_loadToUpperThreshold() {
  long startTime = System.currentTimeMillis();
  long lastLogTime = System.currentTimeMillis();
  do {
    Object key = testInstance.getNewKey();
    BaseValueHolder anObj = testInstance.getValueForKey(key);
    testInstance.aRegion.put(key, anObj);
    long counter = NameFactory.getCounterForName(key);
    if (counter >= testInstance.upperThreshold) {
      String s = "NameFactory nameCounter has reached " + counter;
      throw new StopSchedulingTaskOnClientOrder(s);
    }
    if (System.currentTimeMillis() - lastLogTime >= 10000) {
      Log.getLogWriter().info("Current nameCounter is " + counter);
      lastLogTime = System.currentTimeMillis();
    }
  } while (System.currentTimeMillis() - startTime < testInstance.minTaskGranularityMS);
}

/** Do operations on the region. The operations are destroy, update and create.
 *  Using ParRegPrms.opPercentage, determine the number of operations for each
 *  of destroy, update and create.
 */
public static void HydraTask_doIntervalOps() {
  testInstance.doIntervalOps();
}

/** Validate operations done by HydraTask_doIntervalOps
 */
public static void HydraTask_validateIntervalOps() {
  testInstance.validateIntervalOps();
}

/** Hydra task method for concurrent tests with verification.
 */
public static void HydraTask_doConcOpsAndVerify() {
   PdxTest.initClassLoader();
   testInstance.doConcOpsAndVerify();
}

/** Hydra task method to do random entry operations with expected
 *  data loss.
 */
public static void HydraTask_doEntryOpsDataLoss() {
   testInstance.doEntryOperations(testInstance.aRegion);
}

/** Hydra task method to do random entry operations.
 */
public static void HydraTask_doEntryOps() {
   testInstance.doEntryOperations(testInstance.aRegion);
}

//private static HydraThreadLocal eventState = new HydraThreadLocal();

/** Hydra task method to do random entry operations.
 */
public static void HydraTask_HADoEntryOps() {
//   Object threadID = eventState.get();
//   if (threadID != null) {
//      EventID.setThreadLocalDataForHydra(threadID);
//   }
   PdxTest.initClassLoader();
   testInstance.HADoEntryOps();
//   if (threadID == null) {
//      eventState.set(EventID.getThreadLocalDataForHydra());
//   }
}

/** Hydra task method to do random entry operations undering shutDownAllMembers.
 */
public static void HydraTask_shutDownAllHADoEntryOps() {
   testInstance.shutDownAllHADoEntryOps();
}

/** Hydra task method to control VMs going down/up one at a time.
 */
public static void HydraTask_HAController() {
   testInstance.HAController();
}

/** Hydra task method to control VMs going down with shutDownAllMembers
 */
public static void HydraTask_shutDownAllHAController() {
   testInstance.shutDownAllHAController();
}

/** Hydra task method to control all VMs going down/up.
 */
public static void HydraTask_recoveryController() {
   testInstance.recoveryController();
}

/** Hydra task method to coordinate with recovery controller.
 */
public static void HydraTask_recoveryEntryOps() {
   testInstance.recoveryEntryOps();
}

/**
 * Randomly stop and restart vms.
*/
public static void HydraTask_stopStartVMs() {
   int numVMsToStop = TestConfig.tab().intAt(StopStartPrms.numVMsToStop);  
   Object[] tmpArr = StopStartVMs.getOtherVMsWithExclude(numVMsToStop, "locator");
   List vms = (List)(tmpArr[0]);
   List stopModes = (List)(tmpArr[1]);
   Log.getLogWriter().info("VMs to stop and restart: " + vms); 
   for (int i = 0; i < vms.size(); i++) {
      ClientVmInfo info = (ClientVmInfo)(vms.get(i));
      PRObserver.initialize(info.getVmid());
   }
   StopStartVMs.stopStartVMs(vms, stopModes);
}
    
/** Log the local size of the PR data store
 */
public synchronized static void HydraTask_logLocalSize() {
   Log.getLogWriter().info("Number of entries in this data store: " + 
      ParRegUtil.getLocalSize(testInstance.aRegion));
}

/** If this vm has a startup delay, wait for it.
 */
public static void HydraTask_waitForStartupRecovery() {
  if (testInstance.redundantCopies == 0) {
    Log.getLogWriter().info("RedundantCopies is 0, so no startup recovery will occur");
    return;
  }
   List startupVMs = new ArrayList(StopStartBB.getBB().getSharedMap().getMap().values());
   List vmsExpectingRecovery = StopStartVMs.getMatchVMs(startupVMs, "dataStore");
   vmsExpectingRecovery.addAll(StopStartVMs.getMatchVMs(startupVMs, "bridge"));
   if (vmsExpectingRecovery.size() == 0) {
      throw new TestException("No startup vms to wait for");
   }
   long startupRecoveryDelay = ((Long)(ParRegBB.getBB().getSharedMap().get("startupRecoveryDelay"))).longValue();
   if (startupRecoveryDelay >= 0) {
      int numPRs = (ConfigPrms.getHadoopConfig() != null) ? 2 : 1;
      PRObserver.waitForRebalRecov(vmsExpectingRecovery, 1, numPRs, null, null, false);
   }
}

/** Waits for this vm to complete redundancy recovery.
 */
public static void HydraTask_waitForMyStartupRecovery() {
  if (testInstance.redundantCopies == 0) {
    Log.getLogWriter().info("RedundantCopies is 0, so no startup recovery will occur");
    return;
  } else if (!testInstance.isDataStore) { // is an accessor or edge client
    Log.getLogWriter().info("This is an accessor or a client, so no startup recovery will occur");
    return;
  }  
  int myVmID = RemoteTestModule.getMyVmid();
  long startupRecoveryDelay = ((Long)(ParRegBB.getBB().getSharedMap().get("startupRecoveryDelay"))).longValue();
  if (startupRecoveryDelay >= 0) {
    int numPRs = (ConfigPrms.getHadoopConfig() != null) ? 2 : 1;
    PRObserver.waitForRebalRecov(myVmID, 1, numPRs, null, null, false);
  }
}

/** Hydra task to write the existing diskDirs to the blackboard, if any are set.
 * 
 */
public synchronized static void HydraTask_writeDiskDirsToBB() {
  if (!diskFilesRecorded) {
    ParRegUtil.writeDiskDirsToBB(testInstance.aRegion);
    diskFilesRecorded = true;
  }
}

/** Hydra task to verify the contents of the region against the blackboard snapshot.
 * 
 */
public static void HydraTask_validateRegionContents() {
   PdxTest.initClassLoader();
   testInstance.verifyFromSnapshotOnly();
}

/** Hydra task to verify the contents of the region against the blackboard snapshot.
 * 
 */
public static void HydraTask_validateInternalPRState() {
   PdxTest.initClassLoader();
   testInstance.verifyInternalPRState();
}

/** Hydra task to validate the PR
 * 
 */
public static void HydraTask_validatePR() {
  PdxTest.initClassLoader();
  Log.getLogWriter().info(testInstance.aRegion.getFullPath() + " size is " + testInstance.aRegion.size() + " isDataStore " + testInstance.isDataStore);
  if (testInstance.isDataStore && (testInstance.aRegion.size() == 0)) { // this ensures that recovery did recover something
     // with HDFS tests we cannot guarantee that we haven't evicted the entire PR
     if (!testInstance.aRegion.getAttributes().getDataPolicy().withHDFS()) {
       throw new TestException("Expected " + testInstance.aRegion.getFullPath() + " to have a size > 0, but it has size " + testInstance.aRegion.size());
     }
  }
  testInstance.verifyFromSnapshot();
}

/** Hydra task to write the current state of the PR to the blackboard
 * 
 */
public static void HydraTask_prepareForValidation() {
  if (testInstance.isEmptyClient || testInstance.isThinClient) { // empty/thin clients cannot write snapshot to blackboard
    // because they do not have all entries
    Log.getLogWriter().info("This vm will not write a snapshot to the blackboard because it is empty or thin");
    return;
  }

  PdxTest.initClassLoader();
  long counter = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.RecoverySnapshotLeader);
  if (counter == 1) {
    // HDFS support (no region.size(), region.keySet(), region.containsKey(), etc
    boolean withEviction = (testInstance.aRegion.getAttributes().getEvictionAttributes().getAlgorithm().equals(EvictionAlgorithm.NONE)) ? false : true;
    if (ConfigPrms.getHadoopConfig() != null) {
      testInstance.writeHDFSRegionSnapshotToBB();
      return;
    }

    Map regionSnapshot = new HashMap();
    Set keySet = testInstance.aRegion.keySet();
    Iterator it = keySet.iterator();
    while (it.hasNext()) {
      Object key = it.next();
      Object value = null;
      if (testInstance.aRegion.containsValueForKey(key)) { // won't invoke a loader (if any)
        value = testInstance.aRegion.get(key);
      }
      if (value instanceof BaseValueHolder || value instanceof PdxInstance)
        regionSnapshot.put(key, (PdxTest.toValueHolder(value)).myValue);
      else
        regionSnapshot.put(key, value);
    }
    Log.getLogWriter().info("Writing regionSnapshot to blackboard, snapshot size is " + 
        regionSnapshot.size() + ": " + regionSnapshot);
    ParRegBB.getBB().getSharedMap().put(ParRegBB.RegionSnapshot, regionSnapshot); 
    ParRegBB.getBB().getSharedMap().put(ParRegBB.DestroyedKeys, new HashSet()); 
  }
}

  /** Verify all regions from the snapshot and wait for all threads to complete verify
   *
   */
  public static void HydraTask_verifyFromSnapshotAndSync() {
    testInstance.verifyFromSnapshot();
    ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);
    TestHelper.waitForCounter(ParRegBB.getBB(), "ParRegBB.FinishedVerify",
        ParRegBB.FinishedVerify,  RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads(),
        true, -1, 2000);
  }

  /** Verify all regions from the snapshot and wait for recovery threads (meaning
   *  those threads not in the controller vm) to complete verify
   *
   */
  public static void HydraTask_verifyRecoveredVMsFromSnapshotAndSync() {
    testInstance.numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
    testInstance.verifyFromSnapshot();
    ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);
    TestHelper.waitForCounter(ParRegBB.getBB(), "ParRegBB.FinishedVerify",
        ParRegBB.FinishedVerify,  
        testInstance.numThreadsInClients - testInstance.numThreadsInThisVM,
        true, -1, 2000);
  }
  
  /**
   * Task to add a new vm and rebalance. This task uses the blackboard and
   * StopSchedulingOnClientOrder to make sure that only 1 vm is running this at
   * a time, and that it inits/rebalances only once.
   * 
   */
  public static void HydraTask_startVmAndRebalance() {
    long rebalance = ParRegBB.getBB().getSharedCounters().incrementAndRead(
        ParRegBB.rebalance);
    if (rebalance == 1) { // this vm is the only one doing init/rebalance
      HydraTask_initializeBridgeServer();
      HydraTask_writeDiskDirsToBB();
      ResourceManager resMan = CacheHelper.getCache().getResourceManager();
      RebalanceFactory factory = resMan.createRebalanceFactory();
      Log.getLogWriter().info("Starting rebalancing");
      RebalanceOperation rebalanceOp = factory.start();
      RebalanceResults rebalanceResults;
      try {
        rebalanceResults = rebalanceOp.getResults();
        Log.getLogWriter().info(
            RebalanceUtil.RebalanceResultsToString(rebalanceResults,
                "Rebalance"));
        // now allow another vm to rebalance
        ParRegBB.getBB().getSharedCounters().zero(ParRegBB.rebalance);
        throw new StopSchedulingTaskOnClientOrder(
            "This vm has completed rebalancing");
      } catch (CancellationException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      } catch (InterruptedException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }
    } else {
      Log.getLogWriter().info("Sleeping for 5 seconds (it's not this vm's turn to init/rebalance");
      MasterController.sleepForMs(5000);
    }
  }
  
  public static void HydraTask_assignBucketsToPartitions() {
    Log.getLogWriter().info("Calling PartitionRegionHelper.assignBucketsToPartitions...");
    PartitionRegionHelper.assignBucketsToPartitions(testInstance.aRegion);
    Log.getLogWriter().info("Done calling PartitionRegionHelper.assignBucketsToPartitions");
  }
  
  /** Hydra task to restore each backup that occurred during the run.
   */
  public static void HydraTask_restoreBackups() {
    PdxTest.initClassLoader();
    int numVMs = 0;
    if (CliHelperPrms.getUseCli()) { // the cli test adds extra members for cli jvm(s) and user-managed locator(s)
      Vector<String> aVec = TestConfig.tab().vecAt(ClientPrms.names);
      for (String name: aVec) {
        if (name.contains("peer")) {
          numVMs++;
        }
      }
    } else {
      numVMs = TestHelper.getNumVMs();
    }
    Log.getLogWriter().info("numVMs is " + numVMs);
    long counter = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.verifyBackups);
    if (counter == 1) { // this is the restore leader
      Log.getLogWriter().info("This thread is the restore leader");
      HydraTask_prepareForRecovery();
      ParRegBB.getBB().getSharedCounters().zero(ParRegBB.backupRestored);
      ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Reinitialized);
      ParRegBB.getBB().getSharedCounters().zero(ParRegBB.FinishedVerify);

      // get a list of the backup directories
      List<File> backupDirList = new ArrayList();
      String currDirName = System.getProperty("user.dir");
      File currDir = new File(currDirName);
      File[] contents = currDir.listFiles();
      for (File aDir: contents) {
        if (aDir.getName().startsWith("backup_")) {
          backupDirList.add(aDir);
        }
      }
      Log.getLogWriter().info("Backup dir list is " + backupDirList);
      
      // run the restore script from each backup directory; expect this to fail
      // because there are existing disk directories
      Log.getLogWriter().info("Restoring backups, expect to fail because disk dirs already exist");
      for (File backupDir: backupDirList) {
        runRestoreScript(backupDir, false);
      }
      Log.getLogWriter().info("Done restoring failed backups");
      
      // remove the existing disk directories and restore each backup
      // successfully 
      for (int i = 0; i < backupDirList.size(); i++) {
        // restore from a backup
        File backupDir = backupDirList.get(i);
        String backupDirName = backupDir.getName();
        long backupNum = Long.valueOf(backupDirName.substring(backupDirName.indexOf("_")+1, backupDirName.length()));
        ParRegBB.getBB().getSharedCounters().zero(ParRegBB.onlineBackupNumber);
        ParRegBB.getBB().getSharedCounters().setIfLarger(ParRegBB.onlineBackupNumber, backupNum);
        PRObserver.initialize();
        Log.getLogWriter().info("Backup number is " + backupNum);
        Log.getLogWriter().info("Restoring from " + backupDir.getName());
        deleteExistingDiskDirs();
        runRestoreScript(backupDir, true);
        Log.getLogWriter().info("Done restoring from " + backupDir.getName());
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.backupRestored);
        
        // initialize and wait for recovery
        Log.getLogWriter().info("Initializing...");
        HydraTask_initialize();
        Log.getLogWriter().info("Done initializing");
        HydraTask_waitForMyStartupRecovery();
        
        // wait for everybody else to finish initializing and recover redundancy
        TestHelper.waitForCounter(ParRegBB.getBB(), 
            "ParRegBB.Reinitialized", 
            ParRegBB.Reinitialized, 
            numVMs-1, 
            true, 
            -1);
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Reinitialized);
        ParRegBB.getBB().getSharedCounters().zero(ParRegBB.ReadyToBegin);

        // verify
        testInstance.validateAfterRestore(backupNum);
        
        // wait for everybody else to be done validating
        TestHelper.waitForCounter(ParRegBB.getBB(), 
            "ParRegBB.FinishedVerify", 
            ParRegBB.FinishedVerify, 
            numVMs-1, 
            true, 
            -1);
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);

        // At this point, all other vms are done with this restore; disconnect
        // and get ready for the next restore 
        Log.getLogWriter().info("Preparing for next restore...");
        HydraTask_prepareForRecovery();
        HydraTask_disconnect();
        ParRegBB.getBB().getSharedCounters().zero(ParRegBB.backupRestored);
        ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Reinitialized);
        if (i == backupDirList.size()-1) { // just did the last backup
          ParRegBB.getBB().getSharedCounters().increment(ParRegBB.backupsDone);
        }
        
        // wait for everybody else to be disconnected
        TestHelper.waitForCounter(ParRegBB.getBB(), 
            "ParRegBB.ReadyToBegin", 
            ParRegBB.ReadyToBegin, 
            numVMs-1, 
            true, 
            -1);
        ParRegBB.getBB().getSharedCounters().zero(ParRegBB.FinishedVerify);
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.ReadyToBegin);
      }
    } else { // not the restore leader
      while (true) {
        // wait for the vm that is restoring files to tell this vm to initialize
        TestHelper.waitForCounter(ParRegBB.getBB(), 
                                  "ParRegBB.backupRestored", 
                                  ParRegBB.backupRestored, 
                                  1, 
                                  true, 
                                  -1);
        
        // initialize
        Log.getLogWriter().info("Initializing...");
        HydraTask_initialize();
        Log.getLogWriter().info("Done initializing");
        HydraTask_waitForMyStartupRecovery();
        
        // wait for everybody to initialize and wait for recovery
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Reinitialized);
        TestHelper.waitForCounter(ParRegBB.getBB(), 
            "ParRegBB.Reinitialized", 
            ParRegBB.Reinitialized, 
            numVMs, 
            true, 
            -1);

        // verify
        long backupNumber = ParRegBB.getBB().getSharedCounters().read(ParRegBB.onlineBackupNumber);
        testInstance.validateAfterRestore(backupNumber);
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);
        TestHelper.waitForCounter(ParRegBB.getBB(), 
            "ParRegBB.FinishedVerify", 
            ParRegBB.FinishedVerify, 
            numVMs, 
            true, 
            -1);
        
        // now it's safe to disconnect and prepare for next restore
        Log.getLogWriter().info("Preparing for next restore...");
        HydraTask_prepareForRecovery();
        HydraTask_disconnect();
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.ReadyToBegin);
        TestHelper.waitForCounter(ParRegBB.getBB(), 
            "ParRegBB.ReadyToBegin", 
            ParRegBB.ReadyToBegin, 
            numVMs, 
            true, 
            -1);
        if (ParRegBB.getBB().getSharedCounters().read(ParRegBB.backupsDone) > 0) {
          Log.getLogWriter().info("All backups have been restored");
          break;
        }
      }
    }
  }
  
  /** After a restore from a backup, validate the PR.
   * 
   * @param backupNum The backup number we restored from (backups are created
   *        in directories called backup_<backupNum>
   */
  protected void validateAfterRestore(long backupNum) {
    int regionSize = aRegion.size();
    String aStr = "After restoring from backup number " + backupNum + " region size is " + regionSize;
    Log.getLogWriter().info(aStr);
    if (isSerialExecution) { // there is a serialized snapshot to compare to
      String snapFileName = "snapshotForBackup_" + backupNum + ".ser";
      String destroyedFileName = "destroyKeysForBackup_" + backupNum + ".ser";

      try {
        FileInputStream fis = new FileInputStream(snapFileName);
        ObjectInputStream ois = new ObjectInputStream(fis);
        regionSnapshot = (Map)(ois.readObject());

        fis = new FileInputStream(destroyedFileName);
        ois = new ObjectInputStream(fis);
        destroyedKeys = (Set)(ois.readObject());

      } catch (FileNotFoundException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      } catch (IOException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      } catch (ClassNotFoundException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }

      // this is a bit inefficient to write to the bb, but
      // verifyFromSnapshot expects to read the regionSnapshot from the blackboard
      ParRegBB.getBB().getSharedMap().put(ParRegBB.RegionSnapshot, regionSnapshot); 
      ParRegBB.getBB().getSharedMap().put(ParRegBB.DestroyedKeys, destroyedKeys); 
      verifyFromSnapshot();
    } else { // not serial; backup was taken during concurrent ops, so we
      // don't know exactly which keys/values should be there; cannot
      // compare to snapshot
      // check what we can without using a snapshot

      // check the size against the keySet size
      Set prKeySet = new HashSet(keySetWithoutCreates(aRegion)); // must be a HashSet to support removeAll below
      if (prKeySet.size() != regionSize) {
        ((LocalRegion)aRegion).dumpBackingMap();
        throw new TestException("Size for " + aRegion.getFullPath() + " is " + regionSize +
            ", but its keySet size is " + prKeySet.size());
      }
      // verify PR metadata 
      ParRegUtil.verifyPRMetaData(aRegion);

      // verify primaries
      if (highAvailability) { 
        ParRegUtil.verifyPrimariesWithWait(aRegion, redundantCopies);
      } else {
        ParRegUtil.verifyPrimaries(aRegion, redundantCopies);
      }

      // verify PR data
      ParRegUtil.verifyBucketCopies(aRegion, redundantCopies);

      // uniqueHosts
      if (uniqueHostsOn) {
        ParRegUtil.verifyBucketsOnUniqueHosts(aRegion);
      }

      if (isBridgeClient) {
        verifyServerKeysFromSnapshot();
      }
    }
  }
  
  /** Delete existing disk directories if they contain disk files.
   * 
   * 
   */
  protected static void deleteExistingDiskDirs() {
    String currDirName = System.getProperty("user.dir");
    File currDir = new File(currDirName);
    File[] contents = currDir.listFiles();
    for (File aDir: contents) {
      if (aDir.isDirectory() && (aDir.getName().indexOf("_disk_") >= 0)) {
        // this is the check for an empty directory; if it's empty don't
        // delete it. Essentially, this does not delete disk directories
        // that were created based on an endTask vmID. When hydra utilities
        // are called to create a diskStore, it automatically generates disk
        // directories based on the current vmID. In an endTask, this VM ID
        // is different that the vmIDs used by the regular tasks. So hydra
        // creates these directories because the product requires the 
        // directories to exist when setDiskDirs is called on the DiskStoreFactory.
        // So, in order to get passed this check, we leave the endTask disk
        // directories there, even though they are empty and never used to
        // hold disk files
        if (aDir.list().length != 0) {
          deleteDir(aDir);
        }
      }
    }
  }
  
  /** Recursively delete a directory and its contents
   * 
   * @param aDir The directory to delete. 
   */
  protected static void deleteDir(File aDir) {
    try {
      File[] contents = aDir.listFiles();
      for (File aFile: contents) {
        if (aFile.isDirectory()) {
          deleteDir(aFile);
        } else {
          if (!aFile.delete()) {
            throw new TestException("Could not delete " + aFile.getCanonicalPath());
          }
        }
      }
      if (aDir.delete()) {
        Log.getLogWriter().info("Successfully deleted " + aDir.getCanonicalPath());
      } else {
        throw new TestException("Could not delete " + aDir.getCanonicalPath());
      }
    } catch (IOException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    }
  }
  
  /** Find restore scripts for any online backups that were done during
   *  the run and execute the scripts. 
   *  
   * @param backupDir The directory containing a backup.
   * @param expectSuccess If true then the restore scripts should work 
   *        successfully, if false then they should fail because there
   *        are already disk files present. 
   */
  protected static void runRestoreScript(File backupDir,
      boolean expectSuccess) {
    // restore script is (for example) /backup_1/2010-10-14-11-10/bilbo_21339_v1_16273_51816/restore.sh
    // where backup_1 is the argument backupDir
    File[] backupContents = backupDir.listFiles();
    if (backupContents.length != 1) {
      throw new TestException("Expecting backup directory to contain 1 directory, but it contains " + backupContents.length);
    }
    File dateDir = backupContents[0];
    File[] dateDirContents = dateDir.listFiles();
    for (File hostAndPidDir: dateDirContents) {
      File[] hostAndPidContents = hostAndPidDir.listFiles();
      for (File aFile: hostAndPidContents) {
        if (aFile.getName().equals("restore.sh") || aFile.getName().equals("restore.bat")) { // run the restore script
          try {
            String cmd = null;
            if (HostHelper.isWindows()) {
              cmd = "cmd /c set GF_JAVA=" + System.getProperty("java.home") + "/bin/java.exe && cmd /c ";
            } else {
              cmd = "/bin/bash ";
            }
            cmd = cmd + aFile.getCanonicalPath();
            try {
              Log.getLogWriter().info("Running restore scripts");
              String cmdResult = ProcessMgr.fgexec(cmd, 0);
              Log.getLogWriter().info("Result is " + cmdResult);
              if (expectSuccess) {
                Log.getLogWriter().info("Restore script executed successfully");
              } else {
                throw new TestException("Expected restore script to fail, but it succeeded");
              }
            } catch (HydraRuntimeException e) {
              if (expectSuccess) {
                throw e;
              } else {
                String errStr = e.getCause().toString();
                if (errStr.indexOf("Backup not restored. Refusing to overwrite") >= 0) {
                  Log.getLogWriter().info("restore script got expected exception " + e +
                      " " + e.getCause());
                } else {
                  throw e;
                }
              }
            }
          } catch (IOException e) {
            throw new TestException(TestHelper.getStackTrace(e));
          }
          break; // ran the restore script in this directory
        }
      }
    }
  }

// ========================================================================
// methods to do the work of the hydra tasks

/** Do random operations and verification for serial round robin test.
 *  If this is the first task in the round, then do a random operation
 *  and record it to the blackboard. If this is not the first in the 
 *  round, then verify this client's view of the operation done by the
 *  first thread in the round. If this is the last thread in the round,
 *  then do the verification and become the new first thread in the round
 *  by doing a random operation. Thus, a different thread in each round
 *  will do the random entry opertion.
 *
 */
public void doRROpsAndVerify() {
   ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
   ClassLoader newClassLoader = PdxTest.initClassLoader();
   logExecutionNumber();
   numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreadsInClients = " + numThreadsInClients);
   long roundPosition = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.RoundPosition);
   Log.getLogWriter().info("In doRROpsAndVerify, roundPosition is " + roundPosition);
   if (roundPosition == numThreadsInClients) { // this is the last in the round
      Log.getLogWriter().info("In doRROpsAndVerify, last in round");
      verifyFromSnapshot();

      // now become the first in the round 
      ParRegBB.getBB().getSharedCounters().zero(ParRegBB.RoundPosition);
      roundPosition = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.RoundPosition);
   }

   if (roundPosition == 1) { // first in round, do random ops
      long roundNumber = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.RoundNumber);
      Log.getLogWriter().info("In doRROpsAndVerify, first in round, round number " + roundNumber);
      doEntryOperations(aRegion);
      if (isBridgeConfiguration) { 
         // wait for 30 seconds of client silence to allow everything to be pushed to clients
         SilenceListener.waitForSilence(30, 5000);
      }

      // write the expected region state to the blackboard
      Log.getLogWriter().info("Writing regionSnapshot to blackboard, snapshot size is " + regionSnapshot.size() + ": " + regionSnapshot);
      ParRegBB.getBB().getSharedMap().put(ParRegBB.RegionSnapshot, regionSnapshot); 
      Log.getLogWriter().info("Writing destroyedKeys to blackboard: " + destroyedKeys);
      ParRegBB.getBB().getSharedMap().put(ParRegBB.DestroyedKeys, destroyedKeys); 
      if (!isThinClient && !isEmptyClient) {
        ParRegUtil.verifySize(aRegion, regionSnapshot.size());
      }
      
      // do an online backup and save the region snapshot to disk to be used
      // after the backup files are restored
      DataPolicy dataPolicy = aRegion.getAttributes().getDataPolicy();
      if (ParRegPrms.getDoOnlineBackup() && (dataPolicy.withPersistence() && dataPolicy.withPartitioning())) {
        // Full backups only please
        PersistenceUtil.doOnlineBackup(false);
      } else if(ParRegPrms.getDoIncrementalBackup() && (dataPolicy.withPersistence() && dataPolicy.withPartitioning())) {
        // Incremental backups only please (after the first)
        long backupNumber = ParRegBB.getBB().getSharedCounters().read(ParRegBB.onlineBackupNumber);
        if(backupNumber == 0) {
          Log.getLogWriter().fine("Incremental backup parameter set.  Performing full backup for number " + backupNumber);
          PersistenceUtil.doOnlineBackup(false);
        } else {
          Log.getLogWriter().fine("Incremental backup parameter set.  Performing incremental backup for number " + backupNumber);
          PersistenceUtil.doOnlineBackup(true);
        }
      }
      
      try {
        long backupNum = ParRegBB.getBB().getSharedCounters().read(ParRegBB.onlineBackupNumber);
        String snapFileName = "snapshotForBackup_" + backupNum + ".ser";
        String destroyedFileName = "destroyKeysForBackup_" + backupNum + ".ser";

        FileOutputStream fos = new FileOutputStream(snapFileName);
        ObjectOutputStream oos = new ObjectOutputStream(fos);
        oos.writeObject(regionSnapshot);
        
        fos = new FileOutputStream(destroyedFileName);
        oos = new ObjectOutputStream(fos);
        oos.writeObject(destroyedKeys);
      } catch (FileNotFoundException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      } catch (IOException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }

      if (highAvailability) {
         // the following is used for debugging only to take a snapshot of the disk files
         //createDiskDirBackup("backupNumber_" + ParRegBB.getBB().getSharedCounters().read(ParRegBB.ExecutionNumber));
          
         // if high availability test; stop and restart each VM other than this one
         cycleVMsNoWait();
         verifyFromSnapshotOnly();
         waitForRecoveryAfterCycleVMs();
         verifyInternalPRState();
         loseAndRecreatePR(originalClassLoader, newClassLoader);
      }
   } else if (roundPosition != numThreadsInClients) { // neither first nor last
      Log.getLogWriter().info("In doRROpsAndVerify, neither first nor last");
      verifyFromSnapshot();
   }
}

/** Lose the partitioned region by doing each of the following in no
 *  particular order:
 *     1) close the cache
 *     2) disconnect from the distributed system
 *     3) locally destroy the PR
          (this is skipped if a bridge server; see bug 36812)
 *     4) close the PR
          (this is skipped if a bridge server; see bug 36812)
 *  Then recreate the PR after each, verifying the PR state.
 */
protected void loseAndRecreatePR(ClassLoader originalClassLoader, ClassLoader newClassLoader) {
   Object[] choices = new Object[] {new Integer(CLOSE_CACHE), new Integer(DISCONNECT), 
                                    new Integer(LOCAL_DESTROY), new Integer(CLOSE_REGION)};
   if (isBridgeConfiguration && !isBridgeClient) { // this is a bridge server
      // don't do a local destroy or close region; see bug 36812
      choices = new Object[] {new Integer(CLOSE_CACHE), new Integer(DISCONNECT)};
   } else {
     DataPolicy dataPolicy = aRegion.getAttributes().getDataPolicy();
     if ((isDataStore && (redundantCopies == 0)) &&
         (dataPolicy.withPersistence() && dataPolicy.withPartitioning())) {
           // the region is persistent AND has no redundantCopies; we cannot do a local destroy
           // because this would cause data loss as a local destroy would remove the data
           // hosted in this vm from the disk files; closeRegion would not have data loss
           // because it retains the data in the disk files
          choices = new Object[] {new Integer(CLOSE_CACHE), new Integer(DISCONNECT), 
              new Integer(CLOSE_REGION)};
         }
   }
   List aList = Arrays.asList(choices);
   List otherVMs = new ArrayList(ClientVmMgr.getOtherClientVmids());

   // mix up the choices so this runs in a different order each time
   for (int i = 0; i < aList.size(); i++) {
      Collections.swap(aList, i, TestConfig.tab().getRandGen().nextInt(0, aList.size()-1));
   }

   // iterate through all the choices
   for (int i = 0; i < aList.size(); i++) {
      PRObserver.initialize(RemoteTestModule.getMyVmid());
      int choice = ((Integer)(aList.get(i))).intValue();
      // close the cache
      if (choice == CLOSE_CACHE) {
         Log.getLogWriter().info("Losing PR by closing the cache...");
         if (isClientCache) {
           ClientCacheHelper.closeCache();
         } else {
           CacheHelper.closeCache();
         }
      } else if (choice == DISCONNECT) {
         Log.getLogWriter().info("Losing PR by disconnecting from the distributed system...");
         DistributedSystemHelper.disconnect();
      } else if (choice == LOCAL_DESTROY) { // 
         Log.getLogWriter().info("Losing PR by locally destroying the partitioned region...");
         aRegion.localDestroyRegion();
      } else if (choice == CLOSE_REGION) {
         Log.getLogWriter().info("Losing PR by closing the partitioned region...");
         aRegion.close();
      }
      Log.getLogWriter().info("Recreating the partitioned region...");
      
      String cachePrmsName = isDataStore ? "cache1" : getCachePrmsName();
      if (newClassLoader != null) {
        // the newClassLoader has a pdx domain class, but we don't want product
        // thread to inherit it when they are spawed during reinitialization so
        // revert the classLoader to the original (that doesn't contain the 
        // pdx domain classes) for the reinit step, then put the domain class
        // loader back in place
        Log.getLogWriter().info("Setting class loader to original " + originalClassLoader + " for reinit step");
        Thread.currentThread().setContextClassLoader(originalClassLoader);
      }
      HA_reinitializeRegion(cachePrmsName);
      Log.getLogWriter().info("Done recreating the partitioned region...");
      if (newClassLoader != null) { // put the domain class loader back in place
        Log.getLogWriter().info("Resetting the class loader after reinit step");
        Thread.currentThread().setContextClassLoader(newClassLoader);
      }
      if (isBridgeConfiguration) {
         if (testInstance.isBridgeClient) {
            ParRegUtil.registerInterest(testInstance.aRegion);
         } else {
            BridgeHelper.startBridgeServer("bridge");
         }
      }
      verifyFromSnapshotOnly();
      if (isDataStore) {
         RegionAttributes attr = aRegion.getAttributes();
         PartitionAttributes prAttr = attr.getPartitionAttributes();
         if (prAttr.getRedundantCopies() != 0) {
            int numPRs = (ConfigPrms.getHadoopConfig() != null) ? 2 : 1;
            PRObserver.waitForRecovery(prAttr.getRecoveryDelay(), prAttr.getStartupRecoveryDelay(), 
                    new Integer(RemoteTestModule.getMyVmid()), otherVMs, 1, numPRs, null, null);
         } else {
            Log.getLogWriter().info("Redundant copies is 0 so no redundancy recovery to wait for");
         }
      }
      verifyInternalPRState();
   }
}

/** Do random operations for concurrent tests until the upperThreshold is met.
 *  The task starts up and all threads concurrently do random
 *  operations. The operations run for maxTaskGranularitySec or
 *  numOpsPerTask, depending the on the defined hydra parameters.
 */
protected void doConcOps() {
   // do random operations 
   doEntryOperations(aRegion);
   int entries = aRegion.size();
   if (entries >= upperThreshold - numThreadsInClients) {
     String s = "Done adding " + entries + " entries";
     throw new StopSchedulingTaskOnClientOrder(s);
   }
}

/** Do random operations and verification for concurrent tests.
 *  The task starts up and all threads concurrently do random
 *  operations. The operations run for maxTaskGranularitySec or
 *  numOpsPerTask, depending the on the defined hydra parameters, 
 *  then all threads will pause. During the pause, one thread goes
 *  first and writes all known keys/values to the blackboard. Then
 *  all other threads read the blackboard and verify they have the
 *  same view. After all threads are done with verification, the
 *  task ends.
 */
protected void doConcOpsAndVerify() {
   // wait for all threads to be ready to do this task, then do random ops
   long counter = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.ReadyToBegin);
   if (counter == 1) {
      logExecutionNumber();
   }
   offHeapVerifyTargetCount = getOffHeapVerifyTargetCount();
   registerInterestCoordinator = new MethodCoordinator(ParRegTest.class.getName(), "registerInterest");
   concVerifyCoordinator = new MethodCoordinator(ParRegTest.class.getName(), "concVerify");
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.ConcurrentLeader);
   numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreadsInClients = " + numThreadsInClients);
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.ReadyToBegin", 
                             ParRegBB.ReadyToBegin, 
                             numThreadsInClients,
                             true, 
                             -1,
                             1000);
   checkForLastIteration();

   Log.getLogWriter().info("Zeroing ShapshotWritten and finishedMemCheck");
   MemScaleBB.getBB().getSharedCounters().zero(MemScaleBB.finishedMemCheck);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.SnapshotWritten);

   // do random operations 
   boolean doOnlineBackup = false;
   if (ParRegPrms.getDoOnlineBackup() || ParRegPrms.getDoIncrementalBackup()) {
     if (!isBridgeClient) {
       DataPolicy dataPolicy = aRegion.getAttributes().getDataPolicy();
       if (dataPolicy.withPersistence() && dataPolicy.withPartitioning()) {
         counter = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.onlineBackup);
         doOnlineBackup = (counter == 1); // product does not allow more than one backup to run at a time;
         // more than one results in an IllegalStateException
       }
     }
   }

   if (doOnlineBackup && ParRegPrms.getDoOnlineBackup()) {
     PersistenceUtil.doOnlineBackup(false);
     // if this is a cli test, do an online compaction
     if (CliHelperPrms.getUseCli()) {
       // give compaction something to do by destroy lots of keys
       destroyPercent(aRegion, false, 50);
       Set<String> diskStores = PersistenceUtil.getDiskStores();
       for (String diskStoreName: diskStores) {
         String command = "compact disk-store --name=" + diskStoreName;
         String result = CliHelper.execCommandOnRemoteCli(command, true)[1];
         boolean compactionResult = ((LocalRegion)aRegion).getDiskStore().forceCompaction();
         if (result.contains("Compaction was attempted but nothing to compact")) {
           if (compactionResult) {
             throw new TestException("The result of running command " + command + " returned " + result +
                " but forcing a compaction returned " + compactionResult + " indicating it found work to do");
           }
         }
       }
     }
   } else if(doOnlineBackup && ParRegPrms.getDoIncrementalBackup()) {
     boolean incremental = (ParRegBB.getBB().getSharedCounters().read(ParRegBB.onlineBackupNumber) > 0);
     PersistenceUtil.doOnlineBackup(incremental);
   } else {
     doEntryOperations(aRegion);
   }
   GemFireCache cache = (isClientCache) ? ClientCacheHelper.getCache() : CacheHelper.getCache();
   RebalanceFactory factory = cache.getResourceManager().createRebalanceFactory();
   RebalanceOperation rop = factory.start();
   RebalanceResults results = null;
   try {
      results = rop.getResults();
   } catch (InterruptedException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }

   Log.getLogWriter().info(RebalanceUtil.RebalanceResultsToString(results, "rebalance"));

   // wait for all threads to pause, then do the verify
   Log.getLogWriter().info("Zeroing FinishedVerify");
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.FinishedVerify);
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Pausing);
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.Pausing", 
                             ParRegBB.Pausing, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   Log.getLogWriter().info("Zeroing ReadyToBegin");
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.ReadyToBegin);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.onlineBackup);


   // in a bridge configuration, wait for the message queues to finish pushing
   // to the clients
   if (isBridgeConfiguration) {
      // wait for 30 seconds of client silence
      SilenceListener.waitForSilence(30, 5000);

      if (isBridgeClient && (bridgeOrderingWorkaround.equalsIgnoreCase("registerInterest"))) {
         // lynn - Workaround for bug 35662, ordering problem in bridge clients.
         // The workaround is to re-registerInterest doing a full GII; this causes the
         // clients to become aligned with the servers.
         registerInterestCoordinator.executeOnce(this, new Object[0]);
         if (!registerInterestCoordinator.methodWasExecuted()) {
            throw new TestException("Test problem: RegisterInterest did not execute");
         }
      }
   }

   // do verification
   concVerifyCoordinator.executeOnce(this, new Object[0]);
   if (!concVerifyCoordinator.methodWasExecuted()) {
      throw new TestException("Test problem: concVerify did not execute");
   }
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);

   // wait for everybody to finish verify, then exit 
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.FinishedVerify", 
                             ParRegBB.FinishedVerify, 
                             numThreadsInClients,
                             true, 
                             -1,
                             5000);
   Log.getLogWriter().info("Zeroing Pausing");
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Pausing);

   counter = ParRegBB.getBB().getSharedCounters().read(ParRegBB.TimeToStop);
   if (counter >= 1)
      throw new StopSchedulingOrder("Num executions is " + 
            ParRegBB.getBB().getSharedCounters().read(ParRegBB.ExecutionNumber));

   // free up any client connections (as they are not re-used across hydra
   // task invocation boundaries)
   if (isBridgeClient) {
      ClientHelper.release(aRegion);
   }
}

/** Return the number of threads across the entire test that call off-heap memory validation.
 *  This number is the target counter value to wait for to proceed after off-heap memory validation.
 * @return The number of threads across the entire test that call off-heap memory validation.
 */
public int getOffHeapVerifyTargetCount() {
  return TestHelper.getNumVMs();
}

/** Cycle through all VMs, killing one or more at a time, then bring them back.
 */
protected void HAController() {
   logExecutionNumber();
   ParRegBB.getBB().getSharedMap().put(shutDownAllInProgressKey, new Boolean(false));
   thisVmRunningHAController = true;
   registerInterestCoordinator = new MethodCoordinator(ParRegTest.class.getName(), "registerInterest");
   checkForLastIteration();
   Log.getLogWriter().info("setting exceptionCount to 0");
   exceptionCount = 0;
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.ConcurrentLeader);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Reinitialized);
   cycleVms();
   numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreadsInClients = " + numThreadsInClients);

   PRObserver.initialize(RemoteTestModule.getMyVmid());   
   List thisVmAsList = new ArrayList();
   thisVmAsList.add(new ClientVmInfo(RemoteTestModule.getMyVmid(),
         RemoteTestModule.getMyClientName(), null));
   long maxChoices = MAX_CHOICES;
   if (isBridgeConfiguration) { // this is a bridge server or edge
      // don't do a local destroy or close region on a server; see bug 36812
      // don't do a local destroy or close region on an edge client; see
      // Bruce's bugmail for 36930 from November 2008
      maxChoices = MAX_CHOICES_FOR_CLIENT_SERVER;
   }
   List<ClientVmInfo> me = Collections.singletonList(new ClientVmInfo(new Integer(RemoteTestModule.getMyVmid()), 
       RemoteTestModule.getMyClientName(), RemoteTestModule.getMyLogicalHost()));
   clearBBCriticalState(me);

   long choice = (ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.choice) % maxChoices);
   if (choice == CLOSE_CACHE) { // close the cache for this VM
      Log.getLogWriter().info("Closing the cache...");
      cacheIsClosed = true;
      if (isClientCache) {
        ClientCacheHelper.closeCache();
      } else {
        CacheHelper.closeCache();
      }
   } else if (choice == DISCONNECT) { // disconnect
      Log.getLogWriter().info("Disconnecting from the distributed system...");
      disconnected = true;
      DistributedSystemHelper.disconnect();
   } else if (choice == LOCAL_DESTROY) { // locally destroy the region
      Log.getLogWriter().info("Locally destroying the partitioned region...");
      regionLocallyDestroyed = true;
      aRegion.localDestroyRegion();
   } else if (choice == CLOSE_REGION) {
      Log.getLogWriter().info("Closing the partitioned region...");
      regionLocallyDestroyed = true; // close is the same as a local destroy
      aRegion.close();
   } else {
      throw new TestException("Test problem; Unknown choice " + choice);
   }

   // wait for all threads in this VM to get the appropriate exception
   while (exceptionCount != (numThreadsInThisVM-1)) {
      Log.getLogWriter().info("Waiting for exceptionCount " + exceptionCount + 
         " to become " + (numThreadsInThisVM-1));
      MasterController.sleepForMs(1000);
   }
   Log.getLogWriter().info("Exception count is " + exceptionCount + " expected count to be " + (numThreadsInThisVM-1));
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.SnapshotWritten);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.FinishedVerify);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.rebalanceCompleted);
   MemScaleBB.getBB().getSharedCounters().zero(MemScaleBB.finishedMemCheck);

   Log.getLogWriter().info("Recreating the partitioned region...");
   String cachePrmsName = isDataStore ? "cache1" : getCachePrmsName();
   HA_reinitializeRegion(cachePrmsName);
   Log.getLogWriter().info("Done recreating the partitioned region...");
   PdxTest.initClassLoader();
   if (isBridgeConfiguration) {
      if (isBridgeClient) {
         ParRegUtil.registerInterest(aRegion);
      } else {
         BridgeHelper.startBridgeServer("bridge");
      }
   }
   cacheIsClosed = false;
   disconnected = false;
   regionLocallyDestroyed = false;
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Reinitialized);
   if (isDataStore && (redundantCopies > 0)) {
     waitForSelfRecovery();     
   } else {
      Log.getLogWriter().info("No redundancy recovery because this is not a data store with redundantCopies > 0");
   }

   // now get all vms to pause for verification
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Pausing);
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.Pausing", 
                             ParRegBB.Pausing, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   if (isBridgeConfiguration) { 
      // wait for 30 seconds of client silence to allow everything to be pushed to clients
      SilenceListener.waitForSilence(30, 5000);
   }
   if (isBridgeClient && (bridgeOrderingWorkaround.equalsIgnoreCase("registerInterest"))) {
      // lynn - Workaround for bug 35662, ordering problem in bridge clients.
      // The workaround is to re-registerInterest doing a full GII; this causes the
      // clients to become aligned with the servers.
      registerInterestCoordinator.executeOnce(this, new Object[0]);
      if (!registerInterestCoordinator.methodWasExecuted()) {
         throw new TestException("Test problem: RegisterInterest did not execute");
      }
   } 

   // verify
   Log.getLogWriter().info("Starting concVerify");
   concVerify();
   Log.getLogWriter().info("Done with concVerify");

   // wait for everybody to finish verify
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.FinishedVerify", 
                             ParRegBB.FinishedVerify, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Pausing);
   
   // rebalance and verify again
   Log.getLogWriter().info("Starting rebalancing");
   ParRegUtil.doRebalance();
   Log.getLogWriter().info("Done with rebalancing");
   verifyFromSnapshot();
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.rebalanceCompleted);
   thisVmRunningHAController = false;

   // see if it's time to stop the test
   long counter = ParRegBB.getBB().getSharedCounters().read(ParRegBB.TimeToStop);
   if (counter >= 1)
      throw new StopSchedulingOrder("Num HAController executions is " + 
            ParRegBB.getBB().getSharedCounters().read(ParRegBB.ExecutionNumber));

   // free up any client connections (as they are not re-used across hydra
   // task invocation boundaries)
   if (isBridgeClient) {
      ClientHelper.release(aRegion);
   }
}


/** Do entry ops and handle disconnects or cache closes. This coordinates counters
 *  with HAController to allow for verification.
 */
protected void HADoEntryOps() {
   checkForLastIteration();
   registerInterestCoordinator = new MethodCoordinator(ParRegTest.class.getName(), "registerInterest");
   numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreadsInClients = " + numThreadsInClients);
   // disconnect can cause cacheClosedException if the thread is accessing the cache
   // but with partitioned regions, it can also cause IllegalStateExceptions if the
   // partitioned region needed to consult the distributed system for something, such
   // as size()
   try {
      testInstance.doEntryOperations(testInstance.aRegion);
      if (ParRegBB.getBB().getSharedCounters().read(ParRegBB.Pausing) > 0) { // we are pausing
         ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Pausing);
         TestHelper.waitForCounter(ParRegBB.getBB(), 
                                   "ParRegBB.Pausing", 
                                   ParRegBB.Pausing, 
                                   numThreadsInClients, 
                                   true, 
                                   -1,
                                   5000);
         if (isBridgeConfiguration) { 
            // wait for 30 seconds of client silence to allow everything to be pushed to clients
            SilenceListener.waitForSilence(30, 5000);
         }
         if (isBridgeClient && (bridgeOrderingWorkaround.equalsIgnoreCase("registerInterest"))) {
            // lynn - Workaround for bug 35662, ordering problem in bridge clients.
            // The workaround is to re-registerInterest doing a full GII; this causes the
            // clients to become aligned with the servers.
            registerInterestCoordinator.executeOnce(this, new Object[0]);
            if (!registerInterestCoordinator.methodWasExecuted()) {
               throw new TestException("Test problem: RegisterInterest did not execute");
            }
         } 
         concVerify();

         // wait for everybody to finish verify
         ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);
         TestHelper.waitForCounter(ParRegBB.getBB(), 
                                   "ParRegBB.FinishedVerify", 
                                   ParRegBB.FinishedVerify, 
                                   numThreadsInClients, 
                                   true, 
                                   -1,
                                   5000);

         // wait for the HAController to finish rebalancing
         TestHelper.waitForCounter(ParRegBB.getBB(), 
                                   "ParRegBB.rebalanceCompleted", 
                                   ParRegBB.rebalanceCompleted, 
                                   1, 
                                   true, 
                                   -1,
                                   5000);

         ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Pausing);
      }
   } catch (Exception e) {
      handleException(e);
   }

   // free up any client connections (as they are not re-used across hydra
   // task invocation boundaries)
   if (isBridgeClient) {
      ClientHelper.release(testInstance.aRegion);
   }
}

/** Test controller for tests that do shutDownAllMembers
 */
protected void shutDownAllHAController() {
   logExecutionNumber();
   thisVmRunningHAController = true;
   registerInterestCoordinator = new MethodCoordinator(ParRegTest.class.getName(), "registerInterest");
   checkForLastIteration();
   Log.getLogWriter().info("setting exceptionCount to 0");
   exceptionCount = 0;
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.ConcurrentLeader);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Reinitialized);
   MemScaleBB.getBB().getSharedCounters().zero(MemScaleBB.finishedMemCheck);
   Log.getLogWriter().info("Sleeping for 15 seconds to allow threads to do ops...");
   MasterController.sleepForMs(15000);
   doShutDownAllMembers();
   numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreadsInClients = " + numThreadsInClients);

   PRObserver.initialize(RemoteTestModule.getMyVmid());
   List otherVMs = new ArrayList(ClientVmMgr.getOtherClientVmids());
   List thisVmAsList = new ArrayList();
   thisVmAsList.add(new ClientVmInfo(RemoteTestModule.getMyVmid(),
         RemoteTestModule.getMyClientName(), null));
   List<ClientVmInfo> me = Collections.singletonList(new ClientVmInfo(new Integer(RemoteTestModule.getMyVmid()), 
       RemoteTestModule.getMyClientName(), RemoteTestModule.getMyLogicalHost()));
   clearBBCriticalState(me);

   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.SnapshotWritten);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.FinishedVerify);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.rebalanceCompleted);
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Reinitialized);

   // now get all vms to pause for verification
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Pausing);
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.Pausing", 
                             ParRegBB.Pausing, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   if (isBridgeConfiguration) { 
      // wait for 30 seconds of client silence to allow everything to be pushed to clients
      SilenceListener.waitForSilence(30, 5000);
   }
   if (isBridgeClient && (bridgeOrderingWorkaround.equalsIgnoreCase("registerInterest"))) {
      // lynn - Workaround for bug 35662, ordering problem in bridge clients.
      // The workaround is to re-registerInterest doing a full GII; this causes the
      // clients to become aligned with the servers.
      registerInterestCoordinator.executeOnce(this, new Object[0]);
      if (!registerInterestCoordinator.methodWasExecuted()) {
         throw new TestException("Test problem: RegisterInterest did not execute");
      }
   } 

   // verify
   Log.getLogWriter().info("Starting concVerify");
   concVerify();
   Log.getLogWriter().info("Done with concVerify");

   // wait for everybody to finish verify
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.FinishedVerify", 
                             ParRegBB.FinishedVerify, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Pausing);
   
   // rebalance and verify again
   Log.getLogWriter().info("Starting rebalancing");
   ParRegUtil.doRebalance();
   Log.getLogWriter().info("Done with rebalancing");
   verifyFromSnapshot();
   ParRegBB.getBB().getSharedCounters().increment(ParRegBB.rebalanceCompleted);
   thisVmRunningHAController = false;

   // see if it's time to stop the test
   long counter = ParRegBB.getBB().getSharedCounters().read(ParRegBB.TimeToStop);
   if (counter >= 1)
      throw new StopSchedulingOrder("Num HAController executions is " + 
            ParRegBB.getBB().getSharedCounters().read(ParRegBB.ExecutionNumber));

   // free up any client connections (as they are not re-used across hydra
   // task invocation boundaries)
   if (isBridgeClient) {
      ClientHelper.release(aRegion);
   }
}


/** Do entry ops and handle vms undergoing a shutDownAllMembers.
 */
protected void shutDownAllHADoEntryOps() {
   checkForLastIteration();
   registerInterestCoordinator = new MethodCoordinator(ParRegTest.class.getName(), "registerInterest");
   numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreadsInClients = " + numThreadsInClients);
   // disconnect can cause cacheClosedException if the thread is accessing the cache
   // but with partitioned regions, it can also cause IllegalStateExceptions if the
   // partitioned region needed to consult the distributed system for something, such
   // as size()
   try {
      testInstance.doEntryOperations(testInstance.aRegion);
      if (ParRegBB.getBB().getSharedCounters().read(ParRegBB.Pausing) > 0) { // we are pausing
         ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Pausing);
         TestHelper.waitForCounter(ParRegBB.getBB(), 
                                   "ParRegBB.Pausing", 
                                   ParRegBB.Pausing, 
                                   numThreadsInClients, 
                                   true, 
                                   -1,
                                   5000);
         if (isBridgeConfiguration) { 
            // wait for 30 seconds of client silence to allow everything to be pushed to clients
            SilenceListener.waitForSilence(30, 5000);
         }
         if (isBridgeClient && (bridgeOrderingWorkaround.equalsIgnoreCase("registerInterest"))) {
            // lynn - Workaround for bug 35662, ordering problem in bridge clients.
            // The workaround is to re-registerInterest doing a full GII; this causes the
            // clients to become aligned with the servers.
            registerInterestCoordinator.executeOnce(this, new Object[0]);
            if (!registerInterestCoordinator.methodWasExecuted()) {
               throw new TestException("Test problem: RegisterInterest did not execute");
            }
         } 
         concVerify();

         // wait for everybody to finish verify
         ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);
         TestHelper.waitForCounter(ParRegBB.getBB(), 
                                   "ParRegBB.FinishedVerify", 
                                   ParRegBB.FinishedVerify, 
                                   numThreadsInClients, 
                                   true, 
                                   -1,
                                   5000);

         // wait for the HAController to finish rebalancing
         TestHelper.waitForCounter(ParRegBB.getBB(), 
                                   "ParRegBB.rebalanceCompleted", 
                                   ParRegBB.rebalanceCompleted, 
                                   1, 
                                   true, 
                                   -1,
                                   5000);

         ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Pausing);
      }
   } catch (Exception e) {
      handleException(e);
   }

   // free up any client connections (as they are not re-used across hydra
   // task invocation boundaries)
   if (isBridgeClient) {
      ClientHelper.release(testInstance.aRegion);
   }
}

/** Handle an exception thrown by doing general operations during HA.
 */
protected void handleException(Exception anExcept) {
   Object mapValue = ParRegBB.getBB().getSharedMap().get(shutDownAllInProgressKey);
   boolean shutDownAllInProgress = false;
   if (mapValue instanceof Boolean) {
      shutDownAllInProgress = (Boolean)mapValue;
   }
   mapValue = ParRegBB.getBB().getSharedMap().get(expectOfflineExceptionKey);
   boolean expectOfflineException = false;
   if (mapValue instanceof Boolean) {
      expectOfflineException = (Boolean)mapValue;
   }
   boolean thisVMReceivedNiceKill = StopStartVMs.niceKillInProgress();
   String errStr = anExcept.toString();
   boolean disconnectError = 
           (errStr.indexOf(
              "This connection to a distributed system has been disconnected") >= 0) ||
           (errStr.indexOf(
              "System is disconnecting") >= 0);

   if (anExcept instanceof CancelException) {
      if ( thisVMReceivedNiceKill || cacheIsClosed || disconnected || shutDownAllInProgress) {
         // a thread in this VM closed the cache or disconnected from the dist system
         // or we are undergoing a nice_kill; all is OK
      } else if (anExcept instanceof PoolCancelledException && isBridgeConfiguration && 
           regionLocallyDestroyed) {
        // The bridge client was closed  while undergoing a  
        // local destroy of region; all is ok
      } else { // no reason to get this error
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if (anExcept instanceof LockServiceDestroyedException) {
      if ((lockOperations && thisVMReceivedNiceKill) ||
          (lockOperations && disconnected) ||
          (lockOperations && cacheIsClosed)) {
         // we can expect this error if we are doing locking and we are undergoing 
         // a nice_kill or we have disconnected or we have closed the cache
      } else {
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if (anExcept instanceof IllegalStateException) {
      if (disconnectError) {
         if (disconnected || thisVMReceivedNiceKill) {  
            // we got a disconnect error or we are undergoing a nice_kill; all is ok
         } else { // no reason to get this error
            throw new TestException(TestHelper.getStackTrace(anExcept));
         }
      } else if (isBridgeConfiguration && thisVMReceivedNiceKill) { 
         // got IllegalStateException, is bridge config, and we are undergoing a niceKill
         if (errStr.indexOf("Proxy not properly initialized") >= 0) {
            // OK, sockets/endpoints are shutting down during a niceKill
         } else {
            throw new TestException(TestHelper.getStackTrace(anExcept));
         }
      } else { // got IllegalStateException, but it's not a disconnect error
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if (anExcept instanceof CacheLoaderException) {
      if (isBridgeConfiguration && 
              (thisVMReceivedNiceKill || regionLocallyDestroyed || disconnected || cacheIsClosed)) {
         if (anExcept.toString().indexOf("The BridgeLoader has been closed") >= 0) {
            // we got a BridgeLoader exception while undergoing a nice_kill,
            // disconnect, close of cache, or local destroy of region; all is ok
         } else {
            throw new TestException(TestHelper.getStackTrace(anExcept));
         }
      } else { // got exception, but it's not a bridge loader being shutdown
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if (anExcept instanceof BridgeWriterException) {
      if (isBridgeConfiguration && 
              (thisVMReceivedNiceKill || regionLocallyDestroyed || disconnected || cacheIsClosed)) {
         if (anExcept.toString().indexOf("The BridgeWriter has been closed") >= 0) {
            // we got a BridgeWriter exception while undergoing a nice_kill, 
            // disconnect, close of cache, or local destroy of region; all is ok
         } else {
            throw new TestException(TestHelper.getStackTrace(anExcept));
         }
      } else { // got exception, but it's not a bridge writer being shutdown
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if (anExcept instanceof RegionDestroyedException) {
      if (regionLocallyDestroyed || shutDownAllInProgress || thisVMReceivedNiceKill) {
         // we got a RegionDestroyedException and we did destroy the region; OK
      } else { // got RegionDestroyedException, but we didn't destroy it
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if ((anExcept instanceof PartitionOfflineException) ||
                     ((anExcept instanceof ServerOperationException) && 
                      (anExcept.getCause() instanceof PartitionOfflineException))) {
      if (!expectOfflineException) {
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
      if (!thisVmRunningHAController) {
         // This is a vm not running the controller task; the vm's only job is to
         // do current ops while possible being shutdown. Since some persistence
         // tests stop more vms than we have redundant copies (but don't lose
         // data because we have disk), they could get offline exceptions while
         // shutting down. If we are shutting down with a nice exit, we don't
         // want to fall into the waitForCounter below (if we did, the vm
         // would never be able to exit with a nice exit. So if we get this
         // exception and it's expected (expectOfflineException is true) and
         // this is not the HAController, just return so we have a chance to
         // do a niceExit in case that is being asked of this vm.
         Log.getLogWriter().info("handleException got expected exception " +
             anExcept + ", returning normally to allow a possible nice exit, continuing test");
         return;
      }
   } else if (anExcept instanceof CacheClosedException) {
     if (!shutDownAllInProgress) {
       throw new TestException(TestHelper.getStackTrace(anExcept));
     }
   } else if (anExcept instanceof DistributedSystemDisconnectedException) {
     if (!shutDownAllInProgress) {
       throw new TestException(TestHelper.getStackTrace(anExcept));
     }   
   } else if (anExcept instanceof ToDataException) { // can happen with pdx tests
     if (!thisVMReceivedNiceKill && !disconnected && !cacheIsClosed) {
       throw new TestException(TestHelper.getStackTrace(anExcept));
     }
     // see if last caused by is a CacheClosedException
     Throwable lastCausedBy = TestHelper.getLastCausedBy(anExcept);
     if (lastCausedBy instanceof CacheClosedException) {
       // OK
     } else if (lastCausedBy instanceof java.io.EOFException) {
       // special case that occurred in testing once where CacheClosedException
       // was 2nd to last in PDX test; allow this one special case
       Throwable previousCausedBy = anExcept;
       Throwable currCausedBy = anExcept.getCause();
       if (currCausedBy != null) {
         while (currCausedBy.getCause() != null) {
           previousCausedBy = currCausedBy;
           currCausedBy.getCause();
         }
       }
       if (previousCausedBy instanceof CacheClosedException) {
         // ok; found the special case
       } else {
         throw new TestException(TestHelper.getStackTrace(anExcept));
       }
     }
   } else if (anExcept instanceof RegionNotFoundException) { // can happen with pdx tests
     String aStr = anExcept.toString();
     if (aStr.indexOf(" could not be found while reading a DataSerializer stream") < 0) {
       throw new TestException(TestHelper.getStackTrace(anExcept));
     }
   } else {
      throw new TestException("Got unexpected exception " + TestHelper.getStackTrace(anExcept));
   }
   Log.getLogWriter().info("Caught " + anExcept + "; expected, continuing test");
   synchronized (this) {
     exceptionCount++;
     Log.getLogWriter().info("exceptionCount is now " + exceptionCount);
   }
   TestHelper.waitForCounter(ParRegBB.getBB(), 
                             "ParRegBB.Reinitialized", 
                             ParRegBB.Reinitialized, 
                             1, 
                             true, 
                             -1,
                             1000);
}

/** Concurrent ops are running in this and other vms while we stop vms.
 *  Only datastores run this method, never accessors (because this vm is the
 *  last one standing; it needs to host all the data for verification later).
 *  Note: this test cannot be run with 0 redundantCopies because the test
 *        strategy is to make this vm the last one standing with no
 *        data loss so it can write the expected region state to the
 *        blackboard.
 *  Stop redundantCopy vms at a time, then wait for recovery
 *      (this test must be configured for recovery when members leave)
 *  until all other vms are stopped
 *  Pause the threads in this vm
 *  Write the state of the PR to the blackboard
 *  Disconnect this vm from the ds
 *  Restart all other vms while reinitializing this vm.
 *  All vms (including this one) compare to the blackboard
 */
protected void recoveryController() {
  logExecutionNumber();
  if (!isDataStore) {
     throw new TestException("Test config error; accessor vm cannot run this task");
  }
  
  // allow some time for ops
  MasterController.sleepForMs(20000);

  // get the vms to stop; divide into datastores and accessors
  thisVmRunningHAController = true;
  Log.getLogWriter().info("Stopping all vms except myself");
  List<ClientVmInfo> otherClientInfos = StopStartVMs.getAllVMs();
  List<ClientVmInfo> accessors = new ArrayList();
  List<ClientVmInfo> dataStores = new ArrayList();
  int myselfIndex = -1;
  for (int i = 0; i < otherClientInfos.size(); i++) {
    ClientVmInfo info = (otherClientInfos.get(i));
    if (info.toString().indexOf("vm_" + RemoteTestModule.getMyVmid() + "_") >= 0) {
      myselfIndex = i;
    } else if (info.toString().indexOf("dataStore") >= 0) {
      dataStores.add(info);
    } else {
      accessors.add(info);
    }
  }
  ClientVmInfo myself = otherClientInfos.remove(myselfIndex);
  Log.getLogWriter().info("List of other VMs: " + otherClientInfos);
  Log.getLogWriter().info("List of dataStores: " + dataStores);
  Log.getLogWriter().info("List of accessors: " + accessors);
  Log.getLogWriter().info("Myself: " + myself);
  List<ClientVmInfo> notChosenVMs = new ArrayList();
  for (ClientVmInfo info: otherClientInfos) {
    notChosenVMs.add(info);
  }

  // stop all vms other than this one, redundantCopies at a time and wait for recovery
  // each time (test is configured for shutdown recovery); this preserves data AND
  // tests recovery with persistence
  List<ClientVmInfo> targetVMs = new ArrayList();
  while (otherClientInfos.size() > 0) {  // stop redundantCopies vms at a time
    PRObserver.initialize();
    int numToStopThisTime = Math.min(redundantCopies, otherClientInfos.size());
    List<ClientVmInfo> vmsToStopThisTime = new ArrayList();
    List<String> stopModeList = new ArrayList();
    while (vmsToStopThisTime.size() < numToStopThisTime) {
      ClientVmInfo clientInfo = otherClientInfos.get(0);
      otherClientInfos.remove(0);
      vmsToStopThisTime.add(clientInfo);
      stopModeList.add(TestConfig.tab().stringAt(StopStartPrms.stopModes));
      notChosenVMs.remove(clientInfo);
    }
    targetVMs.addAll(vmsToStopThisTime);
    StopStartVMs.stopVMs(vmsToStopThisTime, stopModeList);
    Log.getLogWriter().info("After stopping " + vmsToStopThisTime + ", remaining is " + otherClientInfos);
    
    // wait for shutdown recovery of this stop
    if (redundantCopies != 0) {
      List dataStoresNotChosen = StopStartVMs.getMatchVMs(notChosenVMs, "dataStore");
      dataStoresNotChosen.addAll(StopStartVMs.getMatchVMs(notChosenVMs, "bridge"));
      dataStoresNotChosen.add(myself); // this vm is always a datastore
      List targetDataStores = StopStartVMs.getMatchVMs(targetVMs, "dataStore");
      targetDataStores.addAll(StopStartVMs.getMatchVMs(targetVMs, "bridge"));
      if (dataStoresNotChosen.size() > 0) {
        int numPRs = (ConfigPrms.getHadoopConfig() != null) ? 2 : 1;
        PRObserver.waitForRebalRecov(dataStoresNotChosen, vmsToStopThisTime.size(), numPRs, null, null, true);
      }
    }
  }
  
  // the only datastore left standing now is this one; let its threads run for a bit
  // then pause
  ParRegBB.getBB().getSharedCounters().zero(ParRegBB.FinishedVerify);
  MasterController.sleepForMs(10000);
  ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Pausing);
  TestHelper.waitForCounter(ParRegBB.getBB(), "ParRegBB.Pausing", 
       ParRegBB.Pausing, numThreadsInThisVM,  true, -1, 2000);

  // write the region state to the blackboard
  Map rSnapshot = new HashMap();
  Set keySet = testInstance.aRegion.keySet();
  Iterator it = keySet.iterator();
  while (it.hasNext()) {
    Object key = it.next();
    Object value = null;
    if (testInstance.aRegion.containsValueForKey(key)) { // won't invoke a loader (if any)
      value = testInstance.aRegion.get(key);
    }
    if (value instanceof BaseValueHolder || value instanceof PdxInstance)
      rSnapshot.put(key, PdxTest.toValueHolder(value).myValue);
    else
      rSnapshot.put(key, value);
  }
  Log.getLogWriter().info("Writing regionSnapshot to blackboard, snapshot size is " + 
      rSnapshot.size() + ": " + rSnapshot);
  ParRegBB.getBB().getSharedMap().put(ParRegBB.RegionSnapshot, rSnapshot); 
  ParRegBB.getBB().getSharedMap().put(ParRegBB.DestroyedKeys, new HashSet()); 
  ParRegBB.getBB().getSharedCounters().zero(ParRegBB.FinishedVerify);
  
  // disconnect myself; now no vm is a member
  disconnected = true;
  Log.getLogWriter().info("Disconnecting myself from the distributed system");
  DistributedSystemHelper.getDistributedSystem().disconnect(); // remove myself from the ds
  thisVmRunningHAController = false;
  PRObserver.initialize(RemoteTestModule.getMyVmid());

  // proceed with restart
  PRObserver.initialize();
  Log.getLogWriter().info("Starting datastores first...");
  List<Thread> threadList = StopStartVMs.startAsync(dataStores);
  Log.getLogWriter().info("Recreating the partitioned region...");
  HA_reinitializeRegion("cache1");
  disconnected = false;
  Log.getLogWriter().info("Done recreating the partitioned region...");
  Log.getLogWriter().info("Waiting for datastore to complete recovery before allowing accessors to proceed");
  TestHelper.waitForCounter(ParRegBB.getBB(), "ParRegBB.FinishedVerify",
        ParRegBB.FinishedVerify,  
        testInstance.numThreadsInThisVM * dataStores.size(),
        false, -1, 2000);
  Log.getLogWriter().info("Starting accessors next...");
  threadList.addAll(StopStartVMs.startAsync(accessors));
  for (Thread aThread: threadList) { // wait for restarted vms
    try {
      aThread.join();
    } catch (InterruptedException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    }
  }
  Log.getLogWriter().info("All vms are back; they have completed verification");

  // verify; we don't need to wait for recovery in this vm because the restarted
  // vms already did that; rebalance and verify again
  verifyFromSnapshot();
  ParRegUtil.doRebalance();
  verifyFromSnapshot();

  // wait for everybody in vms other than this one to finish verify
  // this vm has already verified (above)
  TestHelper.waitForCounter(ParRegBB.getBB(), 
      "ParRegBB.FinishedVerify", 
      ParRegBB.FinishedVerify, 
      numThreadsInClients - numThreadsInThisVM,
      true, 
      -1,
      5000);
  // at this point, all threads in vms other than this one have signalled
  // they are done verifying; they are waiting for this vm to increment
  // finishedVerify before they willl proceed to doing ops again
  ParRegBB.getBB().getSharedCounters().zero(ParRegBB.Pausing);
  ParRegBB.getBB().getSharedCounters().increment(ParRegBB.FinishedVerify);

  // see if it's time to stop the test
  long counter = ParRegBB.getBB().getSharedCounters().read(ParRegBB.TimeToStop);
  if (counter >= 1)
    throw new StopSchedulingOrder("Num HAController executions is " + 
        ParRegBB.getBB().getSharedCounters().read(ParRegBB.ExecutionNumber));

  // free up any client connections (as they are not re-used across hydra
  // task invocation boundaries)
  if (isBridgeClient) {
    ClientHelper.release(aRegion);
  }
}

/** Do entry ops and handle disconnects or cache closes. This coordinates counters
 *  with HAController to allow for verification.
 */
protected void recoveryEntryOps() {
   checkForLastIteration();
   long pausing = ParRegBB.getBB().getSharedCounters().read(ParRegBB.Pausing);
   if (pausing > 0) { // stop ops in this thread
     ParRegBB.getBB().getSharedCounters().increment(ParRegBB.Pausing);
     // wait for everybody to finish verification
     TestHelper.waitForCounter(ParRegBB.getBB(), "ParRegBB.FinishedVerify", 
         ParRegBB.FinishedVerify, numThreadsInClients - numThreadsInThisVM + 1,  true,  -1, 5000);
   }
   try {
      testInstance.doEntryOperations(testInstance.aRegion);
   } catch (Exception e) {
      handleException(e);
   }
}

/** Do random entry operations on the given region ending either with
 *  minTaskGranularityMS or numOpsPerTask.
 *  Uses ParRegPrms.entryOperations to determine the operations to execute.
 */
protected void doEntryOperations(Region r) {

   Log.getLogWriter().info("In doEntryOperations with " + r.getFullPath());
   numThreadsInClients = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreadsInClients = " + numThreadsInClients);
   long startTime = System.currentTimeMillis();
   int numOps = 0;

   // useTransactions() defaults to false
   boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
   boolean rolledback;

   // test hook to obtain operation (region, key and op) for current tx
   if (useTransactions && isBridgeClient) {
      // TODO: TX: redo for new TX impl
      //TxHelper.recordClientTXOperations();
   }

   do {
      int[] opInfo = getOperation(r);
      int whichOp = opInfo[0];

      boolean gotTheLock = false;
      if (lockOperations) {
         Log.getLogWriter().info("Trying to get distributed lock " + LOCK_NAME + "...");
         gotTheLock = distLockService.lock(LOCK_NAME, -1, -1);
         if (!gotTheLock) {
            throw new TestException("Did not get lock " + LOCK_NAME);
         }
         Log.getLogWriter().info("Got distributed lock " + LOCK_NAME + ": " + gotTheLock);
      }

      rolledback = false;
      if (useTransactions) {
        TxHelper.begin();
        if (isBridgeClient && isSerialExecution) {   
           saveRegionSnapshot();
        }
      }

      try {
         switch (whichOp) {
            case ENTRY_ADD_OPERATION:
               addEntry(r);
               break;
            case ENTRY_INVALIDATE_OPERATION:
               invalidateEntry(r, false);
               break;
            case ENTRY_DESTROY_OPERATION:
               destroyEntry(r, false);
               break;
            case ENTRY_UPDATE_OPERATION:
               updateEntry(r);
               break;
            case ENTRY_GET_OPERATION:
               getKey(r);
               break;
            case ENTRY_GET_NEW_OPERATION:
               getNewKey(r);
               break;
            case ENTRY_LOCAL_INVALIDATE_OPERATION:
               invalidateEntry(r, true);
               break;
            case ENTRY_LOCAL_DESTROY_OPERATION:
               destroyEntry(r, true);
               break;
            case ENTRY_PUTALL_OPERATION:
                putAll(r);
                break;
            case ENTRY_DESTROY_PERCENT_OPERATION:
                destroyPercent(r, false, opInfo[1]);
                break;
            case ENTRY_LOCAL_DESTROY_PERCENT_OPERATION:
                destroyPercent(r, true, opInfo[1]);
                break;
            case ENTRY_PUT_IF_ABSENT_OPERATION:
                putIfAbsent(r, true);
                break;
            case ENTRY_PUT_IF_ABSENT_AS_GET_OPERATION:
                putIfAbsentAsGet(r);
                break;
            case ENTRY_PUT_IF_ABSENT_AS_CREATE_OPERATION:
                putIfAbsentAsCreate(r);
                break;
            case ENTRY_REMOVE_OPERATION:
                remove(r);
                break;
            case ENTRY_REMOVE_AS_NOOP_OPERATION:
                removeAsNoop(r);
                break;
            case ENTRY_REPLACE_OPERATION:
                replace(r, true);
                break;
            case ENTRY_REPLACE_AS_NOOP_OPERATION:
                replaceAsNoop(r);
                break;
            case ENTRY_REPLACE_AS_UPDATE_OPERATION:
                replaceAsUpdate(r);
                break;
            case ENTRY_REPLACE_NO_INVAL_OPERATION:
                replace(r, false);
                break;
            case ENTRY_REPLACE_OLD_OPERATION:
                replaceOld(r, false);
                break;
            case ENTRY_REPLACE_OLD_AS_NOOP_OPERATION:
                replaceOldAsNoop(r);
                break;
            case ENTRY_REPLACE_OLD_AS_UPDATE_OPERATION:
                replaceOldAsUpdate(r);
                break;
            case ENTRY_REPLACE_OLD_NO_INVAL_OPERATION:
                replaceOld(r, false);
                break;
            case QUERY_OPERATION:
                query(r);
                break;
            case CREATE_INDEX_OPERATION:
                createIndex(r);
                break;
            case REMOVE_INDEX_OPERATION:
                removeIndex(r);
                break;
            default: {
               throw new TestException("Unknown operation " + whichOp);
            }
         }
      } catch (TransactionDataNodeHasDepartedException e) {
        if (!useTransactions) {
          throw new TestException("Unexpected Exception " + e + ", " + TestHelper.getStackTrace(e));
        } else {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with HA, continuing test.");  
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
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with HA, continuing test.");  
          Log.getLogWriter().info("Rolling back transaction.");
          try {
            TxHelper.rollback();
            Log.getLogWriter().info("Done Rolling back Transaction");
          } catch (TransactionException te) {
            Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching Exception " + e + " during tx ops.  Expected, continuing test.");
          } 
          rolledback = true;
        }
      } catch (ToDataException e) {
        Throwable lastCausedBy = TestHelper.getLastCausedBy(e);
        if (lastCausedBy instanceof TransactionDataNodeHasDepartedException) {
          if (!useTransactions) {
            throw new TestException("Unexpected Exception " + e + ", " + TestHelper.getStackTrace(e));
          } else {
            Log.getLogWriter().info("Caught Exception " + e + ".  Expected with HA, continuing test.");  
            Log.getLogWriter().info("Rolling back transaction.");
            try {
              TxHelper.rollback();
              Log.getLogWriter().info("Done Rolling back Transaction");
            } catch (TransactionException te) {
              Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching TransactionDataNodeHasDeparted during tx ops.  Expected, continuing test.");
            } 
            rolledback = true;
          }
        } else {
          throw e;
        }
      } finally {
         if (gotTheLock) {
            gotTheLock = false;
            distLockService.unlock(LOCK_NAME);
            Log.getLogWriter().info("Released distributed lock " + LOCK_NAME);
         }
      }

      if (useTransactions && !rolledback) {
        try {
          TxHelper.commit();
        } catch (TransactionDataNodeHasDepartedException e) {
          Log.getLogWriter().info("Caught Exception " + e + " on commit.  Expected with HA, continuing test.");  
          if (isSerialExecution && isBridgeClient) {
            restoreRegionSnapshot();
          }
        } catch (TransactionDataRebalancedException e) {
          Log.getLogWriter().info("Caught Exception " + e + " on commit.  Expected with HA, continuing test.");  
          if (isSerialExecution && isBridgeClient) {
             restoreRegionSnapshot();
          }
        } catch (TransactionInDoubtException e) {
          Log.getLogWriter().info("Caught Exception " + e + " on commit.  Expected with concurrent execution, continuing test.");
          if (isSerialExecution && isBridgeClient) {
            restoreRegionSnapshot();
          }
          // Known to cause data inconsistency, keep track of keys involved in TxInDoubt transactions on the BB
          recordFailedOps(ParRegBB.INDOUBT_TXOPS);
        } catch (ConflictException e) {
          if (!isSerialExecution) {// can occur with concurrent execution
             Log.getLogWriter().info("Caught Exception " + e + " on commit. Expected with concurrent execution, continuing test.");
          } else {
             throw new TestException("Unexpected " + e + ". " + TestHelper.getStackTrace(e));
          }
        }
      }

      numOps++;
      Log.getLogWriter().info("Completed op " + numOps + " for this task");
   } while ((System.currentTimeMillis() - startTime < minTaskGranularityMS) &&
            (numOps < numOpsPerTask));
   Log.getLogWriter().info("Done in doEntryOperations with " + r.getFullPath());
}

/** Use TxHelper.getClientTXOperations() maintain a list of keys for transactions that failed with
 *  TransactionInDoubtExceptions 
 *
 */
protected void recordFailedOps(String sharedMapKey) {
   // TODO: TX: redo for new TX impl
   /*
   List opList = TxHelper.getClientTXOperations();
   Iterator it = opList.iterator();
   while (it.hasNext()) {
     TransactionalOperation op = (TransactionalOperation)it.next();
     Log.getLogWriter().info("TranasctionalOperation = " + op.toString());
     if (op.getKey() != null) {
        ParRegBB.getBB().addFailedOp(sharedMapKey, op.getKey());
     }
   }
   */
}

/** saveRegionSnapshot() - save this VMs internal RegionSnapshot (HashMap) in case of tx failure during commit.
 *     for use in serial tx tests only (to return the internal region snapshot to its previosus state
 *     when a TransactionDataNodeHasDeparted, TransactionDataRebalanced or TransactionInDoubt Exception 
 *     encountered at commit time.
 */
protected void saveRegionSnapshot() {
  txRegionSnapshot = new HashMap(regionSnapshot);
  txDestroyedKeys = new HashSet(destroyedKeys);
}

/** restoreRegionSnapshot()  - restore this VMs internal RegionSnapshot (HashMap) after a tx failure during commit.
 *     for use in serial tx tests only (to return the internal region snapshot to its previosus state
 *     when a TransactionDataNodeHasDeparted, TransactionDataRebalanced or TransactionInDoubt Exception 
 *     encountered at commit time.
 */
protected void restoreRegionSnapshot() {
  regionSnapshot = txRegionSnapshot;
  destroyedKeys = txDestroyedKeys;

  // cleanup 
  txRegionSnapshot = null;
  txDestroyedKeys = null;
}

/** Add a new entry to the given region.
 *
 *  @param aRegion The region to use for adding a new entry.
 *
 *  @returns The key that was added.
 */
protected Object addEntry(Region r) {
   Object key = getNewKey();
   BaseValueHolder anObj = getValueForKey(key);
   String callback = createCallbackPrefix + ProcessMgr.getProcessId();
   int beforeSize = r.size();
   if (TestConfig.tab().getRandGen().nextBoolean()) { // use a create call
      if (TestConfig.tab().getRandGen().nextBoolean()) { // use a create call with cacheWriter arg
         try {
            Log.getLogWriter().info("addEntry: calling create for key " + key + ", object " +
               TestHelper.toString(anObj) + " cacheWriterParam is " + callback + ", region is " + 
               r.getFullPath());
            r.create(key, anObj, callback);
            Log.getLogWriter().info("addEntry: done creating key " + key);
         } catch (EntryExistsException e) {
            if (isSerialExecution) { 
               // cannot get this exception; nobody else can have this key
               throw new TestException(TestHelper.getStackTrace(e));
            } else {
               Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
               // in concurrent execution, somebody could have updated this key causing it to exist
            }
         }
      } else { // use create with no cacheWriter arg
         try {
            Log.getLogWriter().info("addEntry: calling create for key " + key + ", object " +
               TestHelper.toString(anObj) + ", region is " + r.getFullPath());
            r.create(key, anObj);
            Log.getLogWriter().info("addEntry: done creating key " + key);
         } catch (EntryExistsException e) {
            if (isSerialExecution) { 
               // cannot get this exception; nobody else can have this key
               throw new TestException(TestHelper.getStackTrace(e));
            } else {
               Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
               // in concurrent execution, somebody could have updated this key causing it to exist
            }
         }
      }
   } else { // use a put call
      Object returnVal = null;      
      if (TestConfig.tab().getRandGen().nextBoolean()) { // use a put call with callback arg
         Log.getLogWriter().info("addEntry: calling put for key " + key + ", object " +
               TestHelper.toString(anObj) + " callback is " + callback + ", region is " + r.getFullPath());
         returnVal = r.put(key, anObj, callback);
         Log.getLogWriter().info("addEntry: done putting key " + key + ", returnVal is " + returnVal);
      } else {
         Log.getLogWriter().info("addEntry: calling put for key " + key + ", object " +
               TestHelper.toString(anObj) + ", region is " + r.getFullPath());
         returnVal = r.put(key, anObj);
         Log.getLogWriter().info("addEntry: done putting key " + key + ", returnVal is " + returnVal);
      }
      
      if (isSerialExecution) { 
        if (isBridgeConfiguration) {
          // lynn - rework this when bug 36436 is fixed; meanwhile allow any return value for bridge
        } else { 
          // might be null or a value according to javadocs, so nothing to validate
        } 
        }
      }

   // validation
   if (isSerialExecution) {
     if (isEmptyClient) {
       if (!TxHelper.exists()) {
         ParRegUtil.verifySize(r, 0);
       }
     } else if (isThinClient) { // we have eviction
       if (!TxHelper.exists()) {
         // new entry should be in the local region
         ParRegUtil.verifyContainsKey(r, key, true);
         ParRegUtil.verifyContainsValueForKey(r, key, true);
         // ClientCache shortcut is for Heap eviction (LRUEntryMax = -1)
         if (!isClientCache) { 
            if (beforeSize < LRUEntryMax) {
              ParRegUtil.verifySize(r, beforeSize+1);
            } else {
              ParRegUtil.verifySize(r, LRUEntryMax);
            }
         }
       }
     } else { // region has all keys/values
       ParRegUtil.verifyContainsKey(r, key, true);
       ParRegUtil.verifyContainsValueForKey(r, key, true);
       ParRegUtil.verifySize(r, beforeSize+1);
     }

      // record the current state
      regionSnapshot.put(key, anObj.myValue);
      destroyedKeys.remove(key);
   }
   return key;
}

/** putall a map to the given region.
*
*  @param aRegion The region to use for putall a map.
*
*/
protected void putAll(Region r) {
   // determine the number of new keys to put in the putAll
   int beforeSize = 0;
   if (isThinClient || isEmptyClient) { // use server keys
     beforeSize = r.keySetOnServer().size();
   } else {
     beforeSize = r.size();
   }
   int localBeforeSize = r.size();
   
   // determine the number of new and existing keys for putAll
   int numNewKeysToPut = 0;
   int numPutAllExistingKeys = 0;
   boolean limitPutAllToOne = ParRegPrms.getLimitPutAllToOne(); // if true overrides all other putAll settings
   if (limitPutAllToOne) { 
     if (TestConfig.tab().getRandGen().nextBoolean()) {
       numNewKeysToPut = 1;
     } else {
       numPutAllExistingKeys = 1;
     }
   } else {
     numPutAllExistingKeys = TestConfig.tab().intAt(ParRegPrms.numPutAllExistingKeys);
     String numPutAllNewKeys = TestConfig.tab().stringAt(ParRegPrms.numPutAllNewKeys);
     if (numPutAllNewKeys.equalsIgnoreCase("useThreshold")) {
       numNewKeysToPut = upperThreshold - beforeSize; 
       if (numNewKeysToPut <= 0) {
         numNewKeysToPut = 1;
       } else {
         int max = TestConfig.tab().intAt(ParRegPrms.numPutAllMaxNewKeys,
             numNewKeysToPut);
         max = Math.min(numNewKeysToPut, max);
         int min = TestConfig.tab().intAt(ParRegPrms.numPutAllMinNewKeys, 1);
         min = Math.min(min, max);
         numNewKeysToPut = TestConfig.tab().getRandGen().nextInt(min, max);
       }
     } else {
       numNewKeysToPut = Integer.valueOf(numPutAllNewKeys).intValue();
     }
   }

   // get a map to put
   Map mapToPut = null;
   int randInt = TestConfig.tab().getRandGen().nextInt(1, 100);
   if (randInt <= 25) {
      mapToPut = new HashMap();
   } else if (randInt <= 50) {
      mapToPut = new Hashtable();
   } else if (randInt <= 75) {
      mapToPut = new TreeMap();
   } else {
      mapToPut = new LinkedHashMap();
   }

   // add new keys to the map
   StringBuffer newKeys = new StringBuffer();
   for (int i = 1; i <= numNewKeysToPut; i++) { // put new keys
      Object key = getNewKey();
      BaseValueHolder anObj = getValueForKey(key);
      mapToPut.put(key, anObj);
      newKeys.append(key + " ");
      if ((i % 10) == 0) {
         newKeys.append("\n");
      }
   }

   // add existing keys to the map
   List keyList = ParRegUtil.getExistingKeys(r, uniqueKeys, numThreadsInClients, numPutAllExistingKeys, isThinClient || isEmptyClient);
   StringBuffer existingKeys = new StringBuffer();
   if (numPutAllExistingKeys > 0) {
      if (keyList.size() != 0) { // no existing keys could be found
         for (int i = 0; i < keyList.size(); i++) { // put existing keys
            String key = (String)(keyList.get(i));
            Object anObj = getUpdateObject(r, key);
            mapToPut.put(key, anObj);
            existingKeys.append(key + " ");
            if (((i+1) % 10) == 0) {
               existingKeys.append("\n");
            }
         }
      }
   }
   Log.getLogWriter().info("PR size is " + beforeSize + ", local region size is " +
       localBeforeSize + ", map to use as argument to putAll is " + 
       mapToPut.getClass().getName() + " containing " + numNewKeysToPut + " new keys and " + 
       keyList.size() + " existing keys (updates); total map size is " + mapToPut.size() +
       "\nnew keys are: " + newKeys + "\n" + "existing keys are: " + existingKeys);
   for (Object key: mapToPut.keySet()) {
      Log.getLogWriter().info("putAll map key " + key + ", value " + TestHelper.toString(mapToPut.get(key)));
   }

   // do the putAll
   Log.getLogWriter().info("putAll: calling putAll with map of " + mapToPut.size() + " entries");
   r.putAll(mapToPut);     
   
   Log.getLogWriter().info("putAll: done calling putAll with map of " + mapToPut.size() + " entries");

   // validation
   if (isSerialExecution) {
     if (isEmptyClient) {
       if (!TxHelper.exists()) {
         ParRegUtil.verifySize(r, 0);
       }
     } else if (isThinClient) { // we have eviction
       if (!TxHelper.exists()) {
         // ClientCache shortcut is for Heap eviction (LRUEntryMax = -1)
         if (!isClientCache) { 
           if (localBeforeSize < LRUEntryMax) {
              // all new keys in the putAll will increase the local region size; the updates
              // in the putAll might increase the size (if it was already local) or not (if
              // it was already evicted
              int localAfterSize = r.size();
              int expectedMinSize = localBeforeSize + numNewKeysToPut;
              int expectedMaxSize = Math.min(localBeforeSize + mapToPut.size(), LRUEntryMax);
              if ((localAfterSize < localBeforeSize) || (localAfterSize > expectedMaxSize)) {
                 throw new TestException("Expected local region size to be between " +
                       expectedMinSize + " and " + expectedMaxSize + " inclusive, but it is " +
                       localAfterSize);
              }
           } else {
             if (!TxHelper.exists()) {
               ParRegUtil.verifySize(r, LRUEntryMax);
             }
           }
         }
       }
     } else { // we have all keys/values in the local region
      ParRegUtil.verifySize(r, beforeSize + numNewKeysToPut);
     }

     Iterator it = mapToPut.keySet().iterator();
     while (it.hasNext()) {
       Object key = it.next();
       BaseValueHolder value = PdxTest.toValueHolder(mapToPut.get(key));
       if (!isEmptyClient && !isThinClient) {
         ParRegUtil.verifyContainsKey(r, key, true);
         ParRegUtil.verifyContainsValueForKey(r, key, true);
       }

       // record the current state
       regionSnapshot.put(key, value.myValue);
       destroyedKeys.remove(key);
     }
   }
}

/** Invalidate an entry in the given region.
 *
 *  @param aRegion The region to use for invalidating an entry.
 *  @param isLocalInvalidate True if the invalidate should be local, false otherwise.
 */
protected void invalidateEntry(Region r, boolean isLocalInvalidate) {
   int beforeSize = r.size();
   Object key = ParRegUtil.getExistingKey(r, uniqueKeys, numThreadsInClients, isEmptyClient||isThinClient);
   if (key == null) {
      if (isSerialExecution && (beforeSize != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + beforeSize);
      Log.getLogWriter().info("invalidateEntry: No keys in region");
      return;
   }
   boolean containsKey = r.containsKey(key);
   boolean containsValueForKey = r.containsValueForKey(key);
   Log.getLogWriter().info("containsKey for " + key + ": " + containsKey);
   Log.getLogWriter().info("containsValueForKey for " + key + ": " + containsValueForKey);
   try {
      String callback = invalidateCallbackPrefix + ProcessMgr.getProcessId();
      if (isLocalInvalidate) { // do a local invalidate
         if (TestConfig.tab().getRandGen().nextBoolean()) { // local invalidate with callback
            Log.getLogWriter().info("invalidateEntry: local invalidate for " + key + " callback is " + callback);
            r.localInvalidate(key, callback);
            Log.getLogWriter().info("invalidateEntry: done with local invalidate for " + key);
         } else { // local invalidate without callback
            Log.getLogWriter().info("invalidateEntry: local invalidate for " + key);
            r.localInvalidate(key);
            Log.getLogWriter().info("invalidateEntry: done with local invalidate for " + key);
         }
      } else { // do a distributed invalidate
         if (TestConfig.tab().getRandGen().nextBoolean()) { // invalidate with callback
            Log.getLogWriter().info("invalidateEntry: invalidating key " + key + " callback is " + callback);
            r.invalidate(key, callback);
            Log.getLogWriter().info("invalidateEntry: done invalidating key " + key);
         } else { // invalidate without callback
            Log.getLogWriter().info("invalidateEntry: invalidating key " + key);
            r.invalidate(key);
            Log.getLogWriter().info("invalidateEntry: done invalidating key " + key);
         }
      }

      // validation
      if (isSerialExecution) {
         if (isEmptyClient) {
           if (!TxHelper.exists()) {
             ParRegUtil.verifySize(r, 0);
           }
         } else if (isThinClient) { // we have eviction
           if (!TxHelper.exists()) {
              ParRegUtil.verifySize(r, beforeSize);
           }
         } else { // we have all keys/values in local region
           ParRegUtil.verifySize(r, beforeSize);
           ParRegUtil.verifyContainsKey(r, key, true);
           ParRegUtil.verifyContainsValueForKey(r, key, false);
         }

         // record the current state
         regionSnapshot.put(key, null);
         destroyedKeys.remove(key);
      }
   } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      if (isSerialExecution)
         throw new TestException(TestHelper.getStackTrace(e));
      else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         return;
      }
   }
}
    
/** Destroy an entry in the given region.
 *
 *  @param aRegion The region to use for destroying an entry.
 *  @param isLocalDestroy True if the destroy should be local, false otherwise.
 */
protected void destroyEntry(Region r, boolean isLocalDestroy) {
   Object key = ParRegUtil.getExistingKey(r, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
   if (key == null) {
      int size = r.size();
      if (isSerialExecution && (size != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + size);
      Log.getLogWriter().info("destroyEntry: No keys in region");
      return;
   }
   int beforeSize = r.size();
   try {
      String callback = destroyCallbackPrefix + ProcessMgr.getProcessId();
      if (isLocalDestroy) { // do a local destroy
         if (TestConfig.tab().getRandGen().nextBoolean()) { // local destroy with callback
            Log.getLogWriter().info("destroyEntry: local destroy for " + key + " callback is " + callback);
            r.localDestroy(key, callback);
            Log.getLogWriter().info("destroyEntry: done with local destroy for " + key);
         } else { // local destroy without callback
            Log.getLogWriter().info("destroyEntry: local destroy for " + key);
            r.localDestroy(key);
            Log.getLogWriter().info("destroyEntry: done with local destroy for " + key);
         }
      } else { // do a distributed destroy
         if (TestConfig.tab().getRandGen().nextBoolean()) { // destroy with callback
            Log.getLogWriter().info("destroyEntry: destroying key " + key + " callback is " + callback);
            r.destroy(key, callback);
            Log.getLogWriter().info("destroyEntry: done destroying key " + key);
         } else { // destroy without callback
            Log.getLogWriter().info("destroyEntry: destroying key " + key);
            r.destroy(key);
            Log.getLogWriter().info("destroyEntry: done destroying key " + key);
         }
      }

      // validation
      if (isSerialExecution) {
         if (isEmptyClient) {
           if (!TxHelper.exists()) {
             ParRegUtil.verifySize(r, 0);
           }
         } else if (isThinClient) { // we have eviction
           if (!TxHelper.exists()) {
             ParRegUtil.verifyContainsKey(r, key, false);
             ParRegUtil.verifyContainsValueForKey(r, key, false);
             int afterSize = r.size();
             if ((afterSize != beforeSize) && (afterSize != beforeSize-1)) {
               throw new TestException("Expected region size " + afterSize + " to be either " + 
                   beforeSize + " or " + (beforeSize-1));
             }
           }
         } else { // we have all keys/values in the local region
           ParRegUtil.verifyContainsKey(r, key, false);
           ParRegUtil.verifyContainsValueForKey(r, key, false);
           ParRegUtil.verifySize(r, beforeSize-1);
         }

         // record the current state
         regionSnapshot.remove(key);
         destroyedKeys.add(key);
      }
   } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      if (isSerialExecution)
         throw new TestException(TestHelper.getStackTrace(e));
      else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         return;
      }
   }
}
    
/** Destroy a percentage of entries in the given region.
 *
 *  @param aRegion The region to use for destroying an entry.
 *  @param isLocalDestroy True if the destroy should be local, false otherwise.
 *  @param percentToDestroy True if the destroy should be local, false otherwise.
 */
protected void destroyPercent(Region r, boolean isLocalDestroy, int percentToDestroy) {
   int size = r.size();
   int numToDestroy = (int)(size * (percentToDestroy / 100.0));
   if (numToDestroy <= 0) {
      numToDestroy = 1;
   }
   List keyList = ParRegUtil.getExistingKeys(r, uniqueKeys, numThreadsInClients, numToDestroy, isThinClient||isEmptyClient);
   if (keyList.size() == 0) {
      Log.getLogWriter().info("destroyPercent: No keys in region");
      return;
   }
   Log.getLogWriter().info("destroyPercent: Destroying " + keyList.size() + " keys based on percent " + 
       percentToDestroy + " and region size " + size);
   for (int i = 0; i < keyList.size(); i++) {
      int beforeSize = r.size();
      Object key = keyList.get(i);
      try {
         String callback = destroyCallbackPrefix + ProcessMgr.getProcessId();
         if (isLocalDestroy) { // do a local destroy
            if (TestConfig.tab().getRandGen().nextBoolean()) { // local destroy with callback
               Log.getLogWriter().info("destroyPercent: local destroy for " + key + " callback is " + callback);
               r.localDestroy(key, callback);
               Log.getLogWriter().info("destroyPercent: done with local destroy for " + key);
            } else { // local destroy without callback
               Log.getLogWriter().info("destroyPercent: local destroy for " + key);
               r.localDestroy(key);
               Log.getLogWriter().info("destroyPercent: done with local destroy for " + key);
            }
         } else { // do a distributed destroy
            if (TestConfig.tab().getRandGen().nextBoolean()) { // destroy with callback
               Log.getLogWriter().info("destroyPercent: destroying key " + key + " callback is " + callback);
               r.destroy(key, callback);
               Log.getLogWriter().info("destroyPercent: done destroying key " + key);
            } else { // destroy without callback
               Log.getLogWriter().info("destroyPercent: destroying key " + key);
               r.destroy(key);
               Log.getLogWriter().info("destroyPercent: done destroying key " + key);
            }
         }
   
         // validation
         if (isSerialExecution) {
           if (isEmptyClient) {
             if (!TxHelper.exists()) {
               ParRegUtil.verifySize(r, 0);
             }
           } else if (isThinClient) { // we have eviction
             if (!TxHelper.exists()) {
               ParRegUtil.verifyContainsKey(r, key, false);
               ParRegUtil.verifyContainsValueForKey(r, key, false);
               int afterSize = r.size();
               if ((afterSize != beforeSize) && (afterSize != beforeSize-1)) {
                 throw new TestException("Expected region size " + afterSize + " to be either " + 
                     beforeSize + " or " + (beforeSize-1));
               }
             }
           } else { // we have all keys/values in the local region
             ParRegUtil.verifyContainsKey(r, key, false);
             ParRegUtil.verifyContainsValueForKey(r, key, false);
             ParRegUtil.verifySize(r, beforeSize-1); 
           }

           // record the current state
           regionSnapshot.remove(key);
           destroyedKeys.add(key);
         }
      } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
         if (isSerialExecution)
            throw new TestException(TestHelper.getStackTrace(e));
         else {
            Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
            return;
         }
      }
   }
}
       
/** Update an existing entry in the given region. If there are
 *  no available keys in the region, then this is a noop.
 *
 *  @param aRegion The region to use for updating an entry.
 */
protected void updateEntry(Region r) {
   Object key = ParRegUtil.getExistingKey(r, uniqueKeys, numThreadsInClients, isEmptyClient||isThinClient);
   if (key == null) {
      int size = r.size();
      if (isSerialExecution && (size != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + size);
      Log.getLogWriter().info("updateEntry: No keys in region");
      return;
   }
   int beforeSize = r.size();
   Object anObj = getUpdateObject(r, (String)key);
   String callback = updateCallbackPrefix + ProcessMgr.getProcessId();
   Object returnVal = null;
   if (TestConfig.tab().getRandGen().nextBoolean()) { // do a put with callback arg
      Log.getLogWriter().info("updateEntry: replacing key " + key + " with " +
         TestHelper.toString(anObj) + ", callback is " + callback);
      returnVal = r.put(key, anObj, callback);
      Log.getLogWriter().info("Done with call to put (update), returnVal is " + returnVal);
   } else { // do a put without callback
      Log.getLogWriter().info("updateEntry: replacing key " + key + " with " + TestHelper.toString(anObj));
      returnVal = r.put(key, anObj, false);
      Log.getLogWriter().info("Done with call to put (update), returnVal is " + returnVal);
   }   

   // validation
   // cannot validate return value from put due to bug 36436; in peer configurations
   // we do not make any guarantees about the return value
   if (isSerialExecution) {
      if (isEmptyClient) {
        if (!TxHelper.exists()) {
          ParRegUtil.verifySize(r, 0);
        }
      } else if (isThinClient) { // we have eviction
        if (!TxHelper.exists()) {
          ParRegUtil.verifyContainsKey(r, key, true);
          ParRegUtil.verifyContainsValueForKey(r, key, true);
          int size = r.size();
          if (size >= beforeSize) {
            // ClientCache shortcut is HeapEviction (LRUEntryMax = -1)
            if (!isClientCache) {
              if (beforeSize == LRUEntryMax) { // before the update, we were already at the max
                ParRegUtil.verifySize(r, LRUEntryMax);
              } else { // before the update we were < max; either this key was already present (no size change)
                       // or it was evicted in which case it will be brought in because of the update (increase in size)
                if ((size != beforeSize) && (size != beforeSize+1)) {
                  throw new TestException("Expected region size " + size + " to be " + beforeSize + " or " + (beforeSize+1));
                }
              }
            }
          }
        }
      } else { // we have all keys/values
        ParRegUtil.verifyContainsKey(r, key, true);
        ParRegUtil.verifyContainsValueForKey(r, key, true);
        ParRegUtil.verifySize(r, beforeSize);
      } 

      // record the current state
      regionSnapshot.put(key, getValueForBB(anObj));
      destroyedKeys.remove(key);
   }
}

/**
 * @param anObj
 * @return
 * @throws TestException
 */
protected Object getValueForBB(Object anObj) throws TestException {
  Object valueToPutInBB = null;
  if (anObj instanceof BaseValueHolder) {
    valueToPutInBB = ((BaseValueHolder)anObj).myValue;
  } else if (anObj instanceof PdxInstance) {
    valueToPutInBB = ((PdxInstance)anObj).getField("myValue");
  } else {
    throw new TestException("Unexpected value class " + anObj.getClass().getName());
  }
  return valueToPutInBB;
}
    
/** Get an existing key in the given region if one is available,
 *  otherwise get a new key. 
 *
 *  @param aRegion The region to use for getting an entry.
 */
protected void getKey(Region aRegion) {
   Object key = ParRegUtil.getExistingKey(aRegion, uniqueKeys, numThreadsInClients, isEmptyClient||isThinClient);
   if (key == null) { // no existing keys; get a new key then
      int size = aRegion.size();
      if (isSerialExecution && (size != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + size);
      getNewKey(aRegion);
      return;
   }
   String callback = getCallbackPrefix + ProcessMgr.getProcessId();
   int beforeSize = aRegion.size();
   boolean beforeContainsValueForKey = aRegion.containsValueForKey(key);
   boolean beforeContainsKey = aRegion.containsKey(key);
   Object anObj;
   if (TestConfig.tab().getRandGen().nextBoolean()) { // get with callback
      Log.getLogWriter().info("getKey: getting key " + key + ", callback is " + callback);
      anObj = aRegion.get(key, callback);
      Log.getLogWriter().info("getKey: got value for key " + key + ": " + TestHelper.toString(anObj));
   } else { // get without callback
      Log.getLogWriter().info("getKey: getting key " + key);
      anObj = aRegion.get(key);
      Log.getLogWriter().info("getKey: got value for key " + key + ": " + TestHelper.toString(anObj));
   }

   // validation 
   if (isSerialExecution) { 
      if (isEmptyClient) {
        if (!TxHelper.exists()) {
          ParRegUtil.verifySize(aRegion, 0);
        }
      } else if (isThinClient) { // we have eviction
        if (!TxHelper.exists()) {
          ParRegUtil.verifyContainsKey(aRegion, key, true);
          Object expectedValue = regionSnapshot.get(key);
          ParRegUtil.verifyContainsValueForKey(aRegion, key, (expectedValue != null) || hasPRCacheLoader);

          // check the expected value of the get
          if (hasPRCacheLoader && !beforeContainsValueForKey) { // loader should have been invoked
             expectedValue = PdxTest.toValueHolder(anObj).myValue;
          } else { // loader should not have been invoked (if we even have a loader)
                   // and we know the key previously existed
             expectedValue = regionSnapshot.get(key);
          }
          ParRegUtil.verifyMyValue(key, expectedValue, anObj, ParRegUtil.EQUAL);
          if (beforeContainsKey) { 
             ParRegUtil.verifySize(aRegion, beforeSize);
          } else { // value did not exist in this client before; it will now
             // ClientCache shortcut is HeapEviction (LRUEntryMax = -1)
             if (!isClientCache) {
               ParRegUtil.verifySize(aRegion, Math.min(LRUEntryMax, beforeSize+1));
             }
          }
        }
      } else { // we have all keys/values
        ParRegUtil.verifyContainsKey(aRegion, key, true);
        ParRegUtil.verifyContainsValueForKey(aRegion, key, (beforeContainsValueForKey || hasPRCacheLoader));

       // check the expected value of the get
       Object expectedValue = null;
       if (hasPRCacheLoader && !beforeContainsValueForKey) { // loader should have been invoked
         expectedValue = PdxTest.toValueHolder(anObj).myValue;
       } else { // loader should not have been invoked (if we even have a loader)
         // and we know the key previously existed
         expectedValue = regionSnapshot.get(key);
       }
       ParRegUtil.verifyMyValue(key, expectedValue, anObj, ParRegUtil.EQUAL);
       ParRegUtil.verifySize(aRegion, beforeSize);
     }

     // record the current state
     // in case the get works like a put because there is a cacheLoader
     if (anObj == null)
       regionSnapshot.put(key, null);
     else
       regionSnapshot.put(key, PdxTest.toValueHolder(anObj).myValue);
     destroyedKeys.remove(key);
   }
}
    
/** Get a new key int the given region.
 *
 *  @param aRegion The region to use for getting an entry.
 */
protected void getNewKey(Region r) {
   Object key = getNewKey();
   String callback = getCallbackPrefix + ProcessMgr.getProcessId();
   int beforeSize = r.size();
   boolean beforeContainsValueForKey = r.containsValueForKey(key);
   Object anObj;
   if (TestConfig.tab().getRandGen().nextBoolean()) { // get with callback
      Log.getLogWriter().info("getNewKey: getting new key " + key + ", callback is " + callback);
      anObj = r.get(key, callback);
   } else { // get without callback
      Log.getLogWriter().info("getNewKey: getting new key " + key);
      anObj = r.get(key);
   }
   Log.getLogWriter().info("getNewKey: done getting value for new key " + key + ": " + TestHelper.toString(anObj));

   // validation 
   if (isSerialExecution) { 
      ParRegUtil.verifyContainsKey(r, key, true);
      ParRegUtil.verifyContainsValueForKey(r, key, hasPRCacheLoader);

      // check the expected value of the get
      boolean containsKey = regionSnapshot.containsKey(key);
      ParRegUtil.verifyMyValue(key, PdxTest.toValueHolder(anObj).myValue, r.get(key), ParRegUtil.EQUAL);

      // record the current state in case the get works like a put because there is a cacheLoader
      if (anObj == null)
         regionSnapshot.put(key, null);
      else
         regionSnapshot.put(key, PdxTest.toValueHolder(anObj).myValue);
      destroyedKeys.remove(key);
   }
}
    
/** Do a putIfAbsent. This can randomly make this operation:
 *    - function as a get (key is present)
 *    - function as a create (key not present and value to put is non-null)
 *    - function as an create/invalidate (key not present and value to put is null)
 *  @param aReg The region to call putIfAbsent on.
 *         allowInvalidate True if this call is allowed to randomly
 *            choose to make this operation function as an invalidate,
 *            false otherwise. 
 */
protected void putIfAbsent(Region aReg, boolean allowInvalidate) {
  // get a key
  Object key = null;
  if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // get existing key
    key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient || isEmptyClient);
    if (key == null) { // could not get an existing key so get a new key
      key = getNewKey();
      Log.getLogWriter().info("In putIfAbsent, targeting new key " + key);
    } else {
      Log.getLogWriter().info("In putIfAbsent, targeting existing key " + key);
    }
  } else { // get a new key    
    key = getNewKey();
    Log.getLogWriter().info("In putIfAbsent, targeting new key " + key);
  }

  // get a value 
  Log.getLogWriter().info("Getting value for putIfAbsent");
  BaseValueHolder objToPut = null;
  if (allowInvalidate) {
    if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // get non-null value)
      objToPut = getValueForKey(key);
    }
  } else {  // do not allow invalidate
    objToPut = getValueForKey(key);
  }

  // do the operation
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) 
      : null;
  Log.getLogWriter().info("Calling putIfAbsent with key " + key + ", value " + 
      TestHelper.toString(objToPut) + ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  BaseValueHolder returnValue = PdxTest.toValueHolder(aReg.putIfAbsent(key, objToPut));
  Log.getLogWriter().info("Done calling putIfAbsent with key " + key + ", value " + 
      TestHelper.toString(objToPut) + ", return value is " + TestHelper.toString(returnValue));

  // validation
  // returnValue can only be checked for serial tests
  if (isSerialExecution) {
    // validate return value 
    Object expectedValue = regionSnapshot.get(key);
    if (((expectedValue == null) && (returnValue != null)) ||
        ((returnValue == null) && (expectedValue != null)) ||
        ((expectedValue != null) && (!expectedValue.equals(returnValue.myValue)))) {
      throw new TestException("Expected return value " + TestHelper.toString(returnValue) +
          " to be ValueHolder with myValue field " + TestHelper.toString(expectedValue));
    }

    // update the snapshot
    boolean opOccurred = isBridgeClient ? !containsKeyOnServer : !containsKey;
    if (opOccurred) { // create occurred
      if (objToPut == null) {
        regionSnapshot.put(key, null);
      } else {
        regionSnapshot.put(key, objToPut.myValue);
      }
    }
  }
}

/** Do a putIfAbsent as a get (key is present).
 *  @param aReg The region to call putIfAbsent on.
 */
protected void putIfAbsentAsGet(Region aReg) {
  // get a key
  Object key = null;
  if (isSerialExecution) {
    key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
    if (key == null) {
      Log.getLogWriter().info("Could not get an existing key for putIfAbsentAsGet");
      return;
    }
  } else {
    // we cannot guarantee that a key exists in concurrent tests; another thread
    // could have removed it before we do the putIfAbsent
    throw new TestException("test config problem; putIfAbsentAsGet not supported for concurrent tests");
  }
  
  // do the operation
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) 
                                               : null;
  Log.getLogWriter().info("Calling putIfAbsent with existing key (functions as get) " + key +
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  Object returnValue = aReg.putIfAbsent(key, "this value should not ever be put into region");
  Log.getLogWriter().info("Done calling putIfAbsent with existing key (functions as get) " + key);
  
  // validate
  // cannot verify returnValue for concurrent tests
  if (isSerialExecution) {
    // validate the return value
    Object expectedValue = regionSnapshot.get(key);
    ParRegUtil.verifyMyValue(key, expectedValue, returnValue, ParRegUtil.EQUAL);
  }
}

/** Do a putIfAbsent as a create (key is NOT present).
 *  @param aReg The region to call putIfAbsent on.
 */
protected void putIfAbsentAsCreate(Region aReg) {
  // get a key
  Object key = getNewKey();
  BaseValueHolder anObj = getValueForKey(key);
  
  // do the operation
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) 
                                               : null;
  Log.getLogWriter().info("Calling putIfAbsent with non-existing key (functions as put) " + key +
      ", value " + TestHelper.toString(anObj) + 
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  Object returnValue = aReg.putIfAbsent(key, anObj);
  Log.getLogWriter().info("Done calling putIfAbsent with non-existing key (functions as put) " + key +
      ", value " + TestHelper.toString(anObj) + ", return value is " + TestHelper.toString(returnValue));
  
  // validate the return value
  if (returnValue != null) {
    throw new TestException("Expected return value from putIfAbsent to be null but it is " + TestHelper.toString(returnValue));
  }
  if (isSerialExecution) {
    regionSnapshot.put(key, anObj.myValue);
  }
}

/** Do a remove. This can randomly make this operation:
 *    - function as a destroy (key is present, value is equal)
 *    - function as a noop (key not present OR value not equal)
 *  @param aReg The region to call remove on.
 */
protected void remove(Region aReg) {
  // get a key
  Object key = null;
  boolean expectRemovalInSerialTest;
  if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 75) { // get existing key
    key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
    if (key == null) { // could not get an existing key so get a new key
      key = getNewKey();
      Log.getLogWriter().info("In remove, targeting new key " + key);
      expectRemovalInSerialTest = false;
    } else {
      Log.getLogWriter().info("In remove, targeting existing key " + key);
      expectRemovalInSerialTest = true;
    }
  } else { // get a new key
    key = getNewKey();
    Log.getLogWriter().info("In remove, targeting new key " + key);
    expectRemovalInSerialTest = false;
  }
  
  // get a value
  Log.getLogWriter().info("Getting value for remove...");
  Object value = getValueForKey(NameFactory.getObjectNameForCounter(NameFactory.getCounterForName(key)-1)); // get unequal value
  boolean containsKey = aReg.containsKey(key);
  if (!hasPRCacheLoader && (TestConfig.tab().getRandGen().nextInt(1, 100) <= 75)) { // get equal value
    value = aReg.get(key);
    expectRemovalInSerialTest = expectRemovalInSerialTest && true;
    if (isThinClient && !containsKey) { // key was not here locally 
      // so calling get could rattle the current state for thin clients
      try {
        aReg.localDestroy(key);
      } catch (EntryNotFoundException e) {
        Log.getLogWriter().info("Caught expected " + e + "; continuing test");
      }
    }
  } else {
    expectRemovalInSerialTest = false;
  }
  
  // test the product's use of the equals method by changing the myVersion field
  // the myVersion field is not used in the equals method defined in BaseValueHolders
  // to the equals check should still return true no matter the value in myVersion
  boolean testingEquality = false;
  if (ParRegPrms.getTestMapOpsEquality()) {
    if (value instanceof BaseValueHolder) {
      testingEquality = true;
      BaseValueHolder vh = (BaseValueHolder)value;
      vh.myVersion = vh.myVersion + "_" + (new Random()).nextLong();
    }
  }

  // do the operation
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
  Log.getLogWriter().info("Calling remove with key " + key + " value " + TestHelper.toString(value) +
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  boolean returnValue = false;
  try {
    returnValue = aReg.remove(key, value);
  } catch (RuntimeException e) {
    PdxTestVersionHelper.handleException(e);
    // if we get here then we allowed the exception
    return;
  }
  Log.getLogWriter().info("Done calling remove with key " + key + " value " +
      TestHelper.toString(value) + ", return value is " + returnValue);

  // validate
  // cannot check return value for concurrent tests
  if (testingEquality && isSerialExecution) {
    if (expectRemovalInSerialTest != returnValue) {
      throw new TestException("Bug 47895 (likely) detected; expected return from remove to be " + expectRemovalInSerialTest + " but it is " + returnValue);
    }
  }
  if (isSerialExecution && returnValue) {
    if (isBridgeClient) {
      if (returnValue != containsKeyOnServer) {
        throw new TestException("Expected return value to be " + containsKey + " but it is " + returnValue);
      }
    } else {
      if (returnValue != containsKey) {
        throw new TestException("Expected return value to be " + containsKey + " but it is " + returnValue);
      }
    }
    if (expectRemovalInSerialTest) {
      regionSnapshot.remove(key);
    }
  }
}

/** Do a remove that functions as a noop either because the key is not
 *  present or because the key is present but the existing value does not
 *  equal the given value. 
 *  @param aReg The region to call remove on.
 */
protected void removeAsNoop(Region aReg) {
  // get a key
  long counter = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.genericCounter);
  Object key = null;
  Object value = "removeAsNoop: ValueDoesNotExist;" + counter;
  boolean usedExistingKey = false;
  if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // key does not exist
    key = "removeAsNoop: KeyDoesNotExist;" + counter;
  } else { // key exists
    key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
    if (key == null) { // could not get an existing key
      key = "removeAsNoop: ThisKeyDoesNotExist;" + counter;
    } else {
      usedExistingKey = true;
    }
  }
  
  // do the operation
  // now whether serial or concurrent, either key exists or not but either way
  // the value will not match so this is guaranteed to be a noop
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
  Log.getLogWriter().info("Calling remove (functions as noop) with key " + key + " value " +
      TestHelper.toString(value) + ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  boolean returnValue = false;
  try {
    returnValue = aReg.remove(key, value);
  } catch (RuntimeException e) {
    PdxTestVersionHelper.handleException(e);
    // if we get here then we allowed the exception
    return;
  }

  Log.getLogWriter().info("Done calling remove (functions as noop) with key " + key + " value " +
      TestHelper.toString(value) + ", return value is " + returnValue);
  if (returnValue) {
    throw new TestException("Expected return value to be false, but it is " + returnValue);
  }
  
  // validate
  if (isSerialExecution) {
    containsKey = aReg.containsKey(key);
    containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
    if (usedExistingKey != containsKey) {
      throw new TestException("After remove, containsKey for key " + key + " is " + containsKey);
    }
    if (usedExistingKey != containsKeyOnServer) {
      throw new TestException("After remove, containsKeyOnServer for key " + key + " is " + containsKeyOnServer);
    }
  }
}

/** Do a replace(K,V). This can randomly make this operation:
 *    - function as an update (key is present and value to put is non-null)
 *    - function as a noop (key not present)
 *    - function as an invalidate (key is present and value to put is null)
 *  @param aReg The region to call putIfAbsent on.
 *         allowInvalidate True if this call is allowed to randomly
 *            choose to make this operation function as an invalidate,
 *            false otherwise. 
 */
protected void replace(Region aReg, boolean allowInvalidate) {
  // get a key
  Object key = null;
  if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // get existing key
    key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
    if (key == null) { // could not get an existing key so get a new key
      key = getNewKey();
      Log.getLogWriter().info("In replace, targeting new key " + key);
    } else {
      Log.getLogWriter().info("In replace, targeting existing key " + key);
    }
  } else { // get a new key
    key = getNewKey();
    Log.getLogWriter().info("In replace, targeting new key " + key);
  }

  // get a value
  Object objToPut = null;
  if (allowInvalidate) {
    if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // get non-null value)
      objToPut = getUpdateObject(aReg, (String)key);
    }
  } else {  // do not allow invalidate
    objToPut = getUpdateObject(aReg, (String)key);
  }
  
  // do the operation
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
  Log.getLogWriter().info("Calling replace(K,V) with key " + key + ", value " + 
      TestHelper.toString(objToPut) + 
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  BaseValueHolder returnValue = PdxTest.toValueHolder(aReg.replace(key, objToPut));
  Log.getLogWriter().info("Done calling replace(K,V) with key " + key + ", value " + 
      TestHelper.toString(objToPut) + ", return value is " + TestHelper.toString(returnValue));

  // validate
  if (isSerialExecution) {
    Object expectedValue = regionSnapshot.get(key);
    if (((expectedValue == null) && (returnValue != null)) ||
        ((returnValue == null) && (expectedValue != null)) ||
        ((expectedValue != null) && (!expectedValue.equals(returnValue.myValue)))) {
      throw new TestException("Expected return value " + TestHelper.toString(returnValue) +
          " to be ValueHolder with myValue field " + TestHelper.toString(expectedValue));
    }
    boolean putOccurred = (isBridgeClient ? containsKeyOnServer
                                          : containsKey);
    if (putOccurred) { // should have done a put
      if (objToPut == null) {
        regionSnapshot.put(key, null);
      } else {
        regionSnapshot.put(key, getValueForBB(objToPut));
      }
    }
  }
}

/** Do a replace(K,V) that functions as a noop because the key is not present.
 *  @param aReg The region to call replace on.
 */
protected void replaceAsNoop(Region aReg) {
  // get a key and value
  long counter = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.genericCounter);
  Object key = "replace:KeyDoesNotExist;" + counter;
  Object value = "replaceAsNoop: this should never be a value;" + counter;
  
  // do the operation
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
  Log.getLogWriter().info("Calling replace(K,V) (functions as noop) with key " + key + " value " +
      TestHelper.toString(value) + 
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  Object returnValue = aReg.replace(key, value);
  Log.getLogWriter().info("Done calling replace(K,V) (functions as noop) with key " + key + " value " +
      TestHelper.toString(value) + ", return value is " + returnValue);
  
  // validate
  if (returnValue != null) {
    throw new TestException("Expected return value to be null, but it is " + TestHelper.toString(returnValue));
  }
  containsKey = aReg.containsKey(key);
  containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) 
                                       : null;
  Log.getLogWriter().info("After replace as noop, containsKey is " + containsKey +
      ", containsKeyOnServer is " + containsKeyOnServer);
  if (containsKey) {
    throw new TestException("After replace, containsKey for key " + key + " is " + containsKey);
  }
  if ((containsKeyOnServer != null) && containsKeyOnServer) {
    throw new TestException("After replace, containsKeyOnServer for key " + key + " is " + containsKeyOnServer);
  }
}

/** Do a replace(K,V) that functions as a put (update) because the key is present.
 *  NOTE: for concurrent tests, this could wind up being a noop as somebody
 *        could destroy the key before we do the replace.
 *  @param aReg The region to call replace on.
 */
protected void replaceAsUpdate(Region aReg) {
  // get a key
  Object key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
  if (key == null) {
    Log.getLogWriter().info("Could not get existing key for replaceAsUpdate");
    return;
  }
  
  // get a value
  Object value = getUpdateObject(aReg, (String)key);
  boolean containsKey = aReg.containsKey(key);
  Object oldValue = aReg.get(key);
  if (isThinClient && !containsKey) { // key was not here locally 
    // so calling get could rattle the current state for thin clients
    try {
      aReg.localDestroy(key);
    } catch (EntryNotFoundException e) {
      Log.getLogWriter().info("Caught expected " + e + "; continuing test");
    }
  }
  
  // do the operation
  containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
  Log.getLogWriter().info("Calling replace(K,V) with existing key (functions as put) " + key +
      ", value " + TestHelper.toString(value) + 
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  Object returnValue = aReg.replace(key, value);
  Log.getLogWriter().info("Done calling replace(K,V) with existing key (functions as put) " + key +
      ", value " + TestHelper.toString(value) + ", return value is " + TestHelper.toString(returnValue));
  
  // validate
  // cannot check returnValue for concurrentTests
  if (isSerialExecution) {
    ParRegUtil.verifyMyValue(key, oldValue, returnValue, ParRegUtil.EQUAL);
    regionSnapshot.put(key, getValueForBB(value));
  }
}

/** Do a replace(K,V,V). This can randomly make this operation:
 *    - function as an update (key is present, oldValue is equal, newValue is non-null)
 *    - function as a noop (key not present OR key is present and oldValue is not equal))
 *    - function as an invalidate (key is present, oldValue is equal, newValue is null)
 *  @param aReg The region to call replace on.
 *         allowInvalidate True if this call is allowed to randomly
 *            choose to make this operation function as an invalidate,
 *            false otherwise. 
 */
protected void replaceOld(Region aReg, boolean allowInvalidate) {
  // get a key
  boolean expectedReturnValue;
  boolean usedNewKey = false;
  Object key = null;
  if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 80) { // get existing key
    key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
    expectedReturnValue = true;
    if (key == null) { // could not get existing key so get a new key
      key = getNewKey();
      expectedReturnValue = false;
      usedNewKey = true;
      Log.getLogWriter().info("In replaceOld, targeting new key " + key);
    } else {
      Log.getLogWriter().info("In replaceOld, targeting existing key " + key);
    }
  } else { // get a new key
    key = getNewKey();
    expectedReturnValue = false;
    usedNewKey = true;
    Log.getLogWriter().info("In replaceOld, targeting new key " + key);
  }

  // get oldValue
  Object oldValue = "replaceOld: this value is not present in region";
  if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // get non-null value
    if (!usedNewKey) { // existing value exists
      boolean containsKey = aReg.containsKey(key);
      boolean containsValueForKey = aReg.containsValueForKey(key);
      Log.getLogWriter().info("In replaceOld, getting oldValue...");
      oldValue = aReg.get(key); 
      if (isSerialExecution && !containsValueForKey && hasPRCacheLoader) { // we invoked a loader
        regionSnapshot.put(key, (PdxTest.toValueHolder(oldValue)).myValue);
      }
      if (isThinClient && !containsKey) { // key was not here locally 
        // so calling get could rattle the current state for thin clients
        try {
          aReg.localDestroy(key);
        } catch (EntryNotFoundException e) {
          Log.getLogWriter().info("Caught expected " + e + "; continuing test");
        }
      }
    }
    expectedReturnValue = expectedReturnValue && true;
  } else {
    expectedReturnValue = false;
  }
  
  // test the product's use of the equals method by changing the myVersion field
  // the myVersion field is not used in the equals method defined in BaseValueHolders
  // to the equals check should still return true no matter the value in myVersion
  boolean testingEquality = false;
  if (ParRegPrms.getTestMapOpsEquality()) {
    if (oldValue instanceof BaseValueHolder) {
      testingEquality = true;
      BaseValueHolder vh = (BaseValueHolder)oldValue;
      vh.myVersion = vh.myVersion + "_" + (new Random()).nextLong();
    }
  }

  // get a newValue
  BaseValueHolder newValue = null;
  if (allowInvalidate) {
    if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // get non-null value
      newValue = ((oldValue == null) || (oldValue instanceof String)) 
                    ? createObject(key) 
                    : PdxTest.toValueHolder(oldValue).getAlternateValueHolder(randomValues);
    }
  } else {  // do not allow invalidate
    newValue = ((oldValue == null) || (oldValue instanceof String)) 
               ? createObject(key) 
               : (PdxTest.toValueHolder(oldValue)).getAlternateValueHolder(randomValues);
  }
  
  // do the operation
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) 
                                               : null;
  Log.getLogWriter().info("Calling replace(K,V,V) with key " + key + " old value " +
      TestHelper.toString(oldValue) + ", new value " + TestHelper.toString(newValue) +
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  boolean returnValue = false;
  try {
    returnValue = aReg.replace(key, oldValue, newValue);
  } catch (RuntimeException e) {
    PdxTestVersionHelper.handleException(e);
    // if we get here then we allowed the exception
    return;
  }
  Log.getLogWriter().info("Done calling replace(K,V,V) with key " + key + " old value " +
      TestHelper.toString(oldValue) + ", new value " + TestHelper.toString(newValue) +
      ", return value " + TestHelper.toString(returnValue));
  
  // validate
  // cannot check returnValue for concurrentTests
  if (isSerialExecution) {
    if (returnValue != expectedReturnValue) {
      String errStr = "Expected replace to return " + expectedReturnValue + " but it returned " + returnValue;
      if (testingEquality) {
        errStr = "Bug 47895 (likely) detected; " + errStr;
      }
      throw new TestException(errStr);
    }
    if (returnValue) { // did an update 
      regionSnapshot.put(key, newValue.myValue);
    }
  }
}

/** Do a replace(K,V,V) that functions as a noop either because the key is not
 *  present or because the key is present but the old value does not
 *  equal the given value. 
 * @param aReg The region to call replace on.
 */
protected void replaceOldAsNoop(Region aReg) {
  // get a key
  Object key = null;
  long counter = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.genericCounter);
  Object oldValue = "replaceOldAsNoop:ValueDoesNotExist;" + counter;
  Object newValue = "replace(K,V,V): this should never be a value;" + counter;
  boolean keyNotPresent = false;
  if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) { // key does not exist
    key = "replaceOldAsNoop:KeyDoesNotExist;" + counter;
    keyNotPresent = true;
  } else { // key exists
    key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
    if (key == null) { // could not get existing key
      key = "replaceOldAsNoop:KeyDoesNotExist;" + counter;
      keyNotPresent = true;
    }
  }
  
  // do the operation
  // now whether serial or concurrent, either key exists or not but either way
  // the value will not match so this is guaranteed to be a noop
  boolean containsKey = aReg.containsKey(key);
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
  Log.getLogWriter().info("Calling replace(K,V,V) (functions as noop) with key " + key + " old value " +
      TestHelper.toString(oldValue) + ", new value " + TestHelper.toString(newValue) +
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  boolean returnValue = false;
  try {
    returnValue = aReg.replace(key, oldValue, newValue);
  } catch (RuntimeException e) {
    PdxTestVersionHelper.handleException(e);
    // if we get here then we allowed the exception
    return;
  }
  Log.getLogWriter().info("Done calling replace(K,V,V) (functions as noop) with key " + key + " old value " +
      TestHelper.toString(oldValue) + ", new value " + TestHelper.toString(newValue) +
      ", return value is " + returnValue);
  
  // validate
  if (returnValue) {
    throw new TestException("Expected return value to be false, but it is " + returnValue);
  }
  Object afterValue = aReg.get(key);
  if (newValue.equals(afterValue)) {
    throw new TestException("After replace, value obtained from region is " + afterValue);
  }
}

/** Do a replace(K,V,V) that functions as a put because the key is present and the
 *  oldValue is equal to what is currently in the region.
 *  NOTE: for concurrent tests, this could wind up being a noop as somebody
 *        could destroy the key before we do the replace.
 * @param aReg The region to call replace on.
 */
protected void replaceOldAsUpdate(Region aReg) {
  // get a key
  Object key = ParRegUtil.getExistingKey(aReg, uniqueKeys, numThreadsInClients, isThinClient||isEmptyClient);
  if (key == null) {
    Log.getLogWriter().info("Could not get existing key for replaceOldAsUpdate");
    return;
  }
  
  // get old value, new value 
  boolean containsKey = aReg.containsKey(key);
  BaseValueHolder oldValue = PdxTest.toValueHolder(aReg.get(key));
  if (isThinClient && !containsKey) { // key was not here locally 
    // so calling get could rattle the current state for thin clients
    try {
      aReg.localDestroy(key);
    } catch (EntryNotFoundException e) {
      Log.getLogWriter().info("Caught expected " + e + "; continuing test");
    }
  }
  BaseValueHolder newValue = (oldValue == null)
                         ? createObject(key) 
                         : oldValue.getAlternateValueHolder(randomValues);

  // do the operation
  Boolean containsKeyOnServer = isBridgeClient ? aReg.containsKeyOnServer(key) : null;
  Log.getLogWriter().info("Calling replace(K,V,V) with existing key (functions as update) " + key +
      ", old value " + TestHelper.toString(oldValue) + ", new value " +
      TestHelper.toString(newValue) +
      ", containsKey " + containsKey + ", containsKeyOnServer " + containsKeyOnServer);
  boolean returnValue = false;
  try {
    returnValue = aReg.replace(key, oldValue, newValue);
  } catch (RuntimeException e) {
    PdxTestVersionHelper.handleException(e);
    // if we get here then we allowed the exception
    return;
  }
  Log.getLogWriter().info("Done calling replace(K,V,V) with existing key (functions as update) " + key +
      ", old value " + TestHelper.toString(oldValue) + ", new value " +
      TestHelper.toString(newValue));
  
  // validate
  // cannot check returnValue for concurrent tests
  if (isSerialExecution) {
    ParRegUtil.verifyMyValue(key, null, returnValue, ParRegUtil.EQUAL);
    regionSnapshot.put(key, newValue.myValue);
  }
}

/** Do a query on the region
 * 
 * @param r
 */
private void query(Region r) {
  StringBuffer queryStr = new StringBuffer();
  queryStr.append(ParRegPrms.getQuery());
  String replaceStr = "%region%";
  int index = queryStr.indexOf(replaceStr);
  if (index >= 0) {
    queryStr.replace(index, index+replaceStr.length(), r.getFullPath());
  }
  Cache theCache = CacheHelper.getCache();
  if (theCache == null) { // can happen with HA tests
    return;
  }
  Query query = theCache.getQueryService().newQuery(queryStr.toString());
  try {
    Log.getLogWriter().info("Current indexes: " + theCache.getQueryService().getIndexes());
    Log.getLogWriter().info("Executing query " + queryStr);
    Object qResults = query.execute();
    if (qResults instanceof SelectResults) {
      SelectResults sr = (SelectResults)qResults;
      StringBuffer aStr = new StringBuffer();
      aStr.append("Results from " + queryStr + " is size " + sr.size() + "\n");
      for (Object element: sr) {
        aStr.append("   " + element + "\n");
      }
      Log.getLogWriter().info(aStr.toString());
    }
  } catch (FunctionDomainException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (TypeMismatchException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (NameResolutionException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (QueryInvocationTargetException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  }
}

/** Create an index
 * 
 * @param r
 */
private void createIndex(Region r) {
  Cache theCache = CacheHelper.getCache();
  if (theCache == null) { // can happen with HA tests
    return;
  }
  QueryService qs = theCache.getQueryService();
  String indexStr = ParRegPrms.getIndex();
  String indexName = "index_" + ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.indexNameCounter);
  try {
    Log.getLogWriter().info("Creating index " + indexName + " with " + indexStr);
    qs.createIndex(indexName, indexStr, r.getFullPath());
  } catch (com.gemstone.gemfire.cache.query.RegionNotFoundException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (IndexInvalidException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (IndexNameConflictException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (IndexExistsException e) {
    Log.getLogWriter().info("Caught expected " + e + "; continuing test");
  } catch (UnsupportedOperationException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  }
}

/** Remove an index
 * 
 * @param r
 */
private void removeIndex(Region r) {
  Cache theCache = CacheHelper.getCache();
  if (theCache == null) { // can happen with HA tests
    return;
  }
  QueryService qs = theCache.getQueryService();
  Collection indexColl = qs.getIndexes();
  if (indexColl.size() >= 1) {
    Index anIndex = (Index) indexColl.iterator().next();
    Log.getLogWriter().info("Removing index " + anIndex.getName());
    qs.removeIndex(anIndex);
  }
}

// ========================================================================
// other methods to help out in doing the tasks
public void checkForLastIteration() {
  checkForLastIteration(secondsToRun);
}
/** Check if we have run for the desired length of time. We cannot use 
 *  hydra's taskTimeSec parameter because of a small window of opportunity 
 *  for the test to hang due to the test's "concurrent round robin" type 
 *  of strategy. Here we set a blackboard counter if time is up and this
 *  is the last concurrent round.
 */
public static void checkForLastIteration(int numSeconds) {
   // determine if this is the last iteration
   long taskStartTime = 0;
   final String bbKey = "TaskStartTime";
   Object anObj = ParRegBB.getBB().getSharedMap().get(bbKey);
   if (anObj == null) {
      taskStartTime = System.currentTimeMillis();
      ParRegBB.getBB().getSharedMap().put(bbKey, new Long(taskStartTime));
      Log.getLogWriter().info("Initialized taskStartTime to " + taskStartTime);
   } else {
      taskStartTime = ((Long)anObj).longValue();
   }
   if (System.currentTimeMillis() - taskStartTime >= numSeconds * 1000) {
      Log.getLogWriter().info("This is the last iteration of this task");
      ParRegBB.getBB().getSharedCounters().increment(ParRegBB.TimeToStop);
   } else {
      Log.getLogWriter().info("Running for " + numSeconds + " seconds; time remaining is " +
         (numSeconds - ((System.currentTimeMillis() - taskStartTime) / 1000)) + " seconds");
   }
}

/** Choose numVMsToStop vms, then stop and restart them.
 *  This causes a failure for partitioned regions to recover from.
 *
 */
protected void cycleVms() {
  cycleVMsNoWait();
  waitForRecoveryAfterCycleVMs();
}

/** Stop and start some number of VMs; do not wait for redundancy recovery.
 *
 */
protected void cycleVMsNoWait() {
  Log.getLogWriter().info("Cycling vms...");
  PRObserver.initialize();
  List<Integer> vmList = ClientVmMgr.getClientVmids();
  Log.getLogWriter().info("vmList is " + vmList);
  boolean stoppingAll = numVMsToStop >= vmList.size();
  cycleVMs_targetVMs = null;
  cycleVMs_notChosenVMs = new ArrayList();
  boolean isPdxTest = (objectType.equals("util.PdxVersionedValueHolder")) ||
                      (objectType.equals("util.VersionedValueHolder"));
  if (stoppingAll) { // typically to cause a disk recovery
    Log.getLogWriter().info("Stopping all except myself");
    cycleVMs_targetVMs = StopStartVMs.stopVMs(vmList.size()-1); // stop all except myself
    Log.getLogWriter().info("Disconnecting myself from the distributed system");
    DistributedSystemHelper.getDistributedSystem().disconnect(); // remove myself from the ds
    // now all are stopped, proceed with restart
    if (isPdxTest) { // it is required that pdx tests start the datastores first
      List<ClientVmInfo> accessors = new ArrayList();
      List<ClientVmInfo> dataStores = new ArrayList();
      for (ClientVmInfo info: cycleVMs_targetVMs) {
         boolean vmIsDataStore = (Boolean)ParRegBB.getBB().getSharedMap().get(isDataStoreKey + info.getVmid());
         if (vmIsDataStore) {
           dataStores.add(info);
         } else {
           accessors.add(info);
         }
      }
      
      // start the datastores first; if I am a data store, reinit myself
      Log.getLogWriter().info("This is a pdx test, starting dataStores first...");
      List<Thread> threadList = StopStartVMs.startAsync(dataStores);
      if (isDataStore) { // this vm is also a datastore
        Log.getLogWriter().info("Recreating the partitioned region...");
        HA_reinitializeRegion("cache1");
        Log.getLogWriter().info("Done recreating the partitioned region...");
        if (isBridgeConfiguration) {
          if (testInstance.isBridgeClient) {
            ParRegUtil.registerInterest(testInstance.aRegion);
          } else {
            BridgeHelper.startBridgeServer("bridge");
          }
        }
      }
      for (Thread aThread: threadList) {
        try {
          aThread.join();
        } catch (InterruptedException e) {
          throw new TestException(TestHelper.getStackTrace(e));
        }
      }
      
      // now start accessors
      Log.getLogWriter().info("Now starting accessors");
      StopStartVMs.startVMs(accessors);
      if (!isDataStore) {
        Log.getLogWriter().info("Recreating the partitioned region...");
        HA_reinitializeRegion(getCachePrmsName());
        Log.getLogWriter().info("Done recreating the partitioned region...");
        if (isBridgeConfiguration) {
          if (testInstance.isBridgeClient) {
            ParRegUtil.registerInterest(testInstance.aRegion);
          } else {
            BridgeHelper.startBridgeServer("bridge");
          }
        }
      }
    } else {
      List<Thread> threadList = StopStartVMs.startAsync(cycleVMs_targetVMs);
      Log.getLogWriter().info("Recreating the partitioned region...");
      String cachePrmsName = isDataStore ? "cache1" : getCachePrmsName();
      HA_reinitializeRegion(cachePrmsName);
      Log.getLogWriter().info("Done recreating the partitioned region...");
      if (isBridgeConfiguration) {
        if (testInstance.isBridgeClient) {
          ParRegUtil.registerInterest(testInstance.aRegion);
        } else {
          BridgeHelper.startBridgeServer("bridge");
        }
      }
      for (Thread aThread: threadList) {
        try {
          aThread.join();
        } catch (InterruptedException e) {
          throw new TestException(TestHelper.getStackTrace(e));
        }
      }
    }
  } else {
    String stopVMsMatchStr = TestConfig.tab().stringAt(ParRegPrms.stopVMsMatchStr, null);
    String stopVMsExcludeMatchStr = TestConfig.tab().stringAt(ParRegPrms.stopVMsExcludeMatchStr, null);
    Object[] anArr = null;
    if (stopVMsExcludeMatchStr == null) {
      if (stopVMsMatchStr == null) {
        anArr = StopStartVMs.getOtherVMs(numVMsToStop);
      } else {
        anArr = StopStartVMs.getOtherVMs(numVMsToStop, stopVMsMatchStr);
      }
    } else {
      anArr = StopStartVMs.getOtherVMsWithExclude(numVMsToStop, stopVMsExcludeMatchStr);
    }
    
    cycleVMs_targetVMs = (List<ClientVmInfo>)(anArr[0]);
    List stopModes = (List)(anArr[1]);
    cycleVMs_notChosenVMs = (List)(anArr[2]);

    clearBBCriticalState(cycleVMs_targetVMs);
    StopStartVMs.stopStartVMs(cycleVMs_targetVMs, stopModes);
  }
  Log.getLogWriter().info("Done cycling vms");
}

/** Waits for redundancy recovery after calling cycleVMsNoWait
 * 
 */
protected void waitForRecoveryAfterCycleVMs() {
  // Wait for recovery to complete
  if (ParRegBB.getBB().getSharedCounters().read(ParRegBB.MaxRC) > 0) {
    List dataStoresNotChosen = StopStartVMs.getMatchVMs(cycleVMs_notChosenVMs, "dataStore");
    dataStoresNotChosen.addAll(StopStartVMs.getMatchVMs(cycleVMs_notChosenVMs, "bridge"));
    List targetDataStores = StopStartVMs.getMatchVMs(cycleVMs_targetVMs, "dataStore");
    targetDataStores.addAll(StopStartVMs.getMatchVMs(cycleVMs_targetVMs, "bridge"));
    removeAccessors(targetDataStores);
    long recoveryDelay = ((Long)(ParRegBB.getBB().getSharedMap().get("recoveryDelay"))).longValue();
    long startupRecoveryDelay = ((Long)(ParRegBB.getBB().getSharedMap().get("startupRecoveryDelay"))).longValue();
    int numPRs = (ConfigPrms.getHadoopConfig() != null) ? 2 : 1;
    PRObserver.waitForRecovery(recoveryDelay, startupRecoveryDelay, 
        targetDataStores, dataStoresNotChosen, 1, numPRs, null, null);
  }
}

/**
 * Waits for recovery in self vm after reconnect
 */
protected void waitForSelfRecovery(){
  RegionAttributes attr = aRegion.getAttributes();
  PartitionAttributes prAttr = attr.getPartitionAttributes();
  if(prAttr != null){
    List otherVMs = new ArrayList(ClientVmMgr.getOtherClientVmids());
    int numPRs = (ConfigPrms.getHadoopConfig() != null) ? 2 : 1;
    PRObserver.waitForRecovery(prAttr.getRecoveryDelay(), prAttr.getStartupRecoveryDelay(), 
               new Integer(RemoteTestModule.getMyVmid()), otherVMs, 1, numPRs, null, null);  
  }
}

/** Using hydra region config names saved in the blackboard, remove
 *  accessors in aList. This is used to firgure out which vm ids we
 *  should wait for recovery for.
 */
protected static void removeAccessors(List<ClientVmInfo> aList) {
   List<String> toBeRemoved = new ArrayList();
   Iterator it = ParRegBB.getBB().getSharedMap().getMap().keySet().iterator();
    while (it.hasNext()) {
      Object anObj = it.next();
      if (anObj instanceof String) {
        String key = (String)anObj;
        if (key.startsWith(VmIDStr)) {
          String regionConfigName = (String)(ParRegBB.getBB().getSharedMap()
              .get(key));
          if (regionConfigName.indexOf("accessor") >= 0) {
            toBeRemoved.add(key);
          }
        }
      }
    }
    for (String key : toBeRemoved) {
      int vmID = Integer.valueOf(key.substring(VmIDStr.length(), key.length()));
      for (int i = 0; i < aList.size(); i++) {
         ClientVmInfo info   = (ClientVmInfo)(aList.get(i));
         if (info.getVmid().equals(vmID)) {
            aList.remove(i);
            break;
         }
      }
   }
}
 
/** Choose numVMsToStop vms, then stop and restart them.
 *  This causes a failure for partitioned regions to recover from.
 *
 */
protected void doShutDownAllMembers() {
  Log.getLogWriter().info("Cycling vms by shutting down all members...");
  PRObserver.initialize();
  List<Integer> vmList = ClientVmMgr.getClientVmids();
  Log.getLogWriter().info("vmList is " + vmList);
  List<ClientVmInfo> targetVMs = null;
  AdminDistributedSystem adminDS = AdminHelper.getAdminDistributedSystem();
  GemFireCache theCache = (isClientCache) ? ClientCacheHelper.getCache() : CacheHelper.getCache();
  if (adminDS == null) {
    throw new TestException("Test is configured to use shutDownAllMembers, but this vm must be an admin vm to use it");
  }
  List stopModes = new ArrayList();
  stopModes.add(ClientVmMgr.MeanKill);
  ParRegBB.getBB().getSharedMap().put(shutDownAllInProgressKey, new Boolean(true));
  Object[] tmp = StopStartVMs.shutDownAllMembers(adminDS, stopModes);
  targetVMs = (List<ClientVmInfo>)tmp[0];
  Set shutdownAllResults = (Set)(tmp[1]);
  if (shutdownAllResults.size() != vmList.size()) { // shutdownAll did not return the expected number of members
    throw new TestException("Expected shutDownAllMembers to return " + vmList.size() + 
       " members in its result, but it returned " + shutdownAllResults.size() + ": " + shutdownAllResults);
  }
  if (shutdownAllResults.size() > targetVMs.size()) {
    // shutdownAllResults is bigger because we shutdown this vm also, but it was not stopped
    // just disconnected
    boolean cacheClosed = theCache.isClosed();
    if (cacheClosed) {
      Log.getLogWriter().info("shutDownAllMembers disconnected this vm");
    } else {
      throw new TestException("shutDownAllMembers should have disconnected this vm, but the cache " +
          theCache + " isClosed is " + cacheClosed);
    }
  }

  // now all are stopped, proceed with restart
  // we must start the datastores first, then the accessor, otherwise the accessors could
  // start doing ops before any datastores are present
  List dataStoreVMs = StopStartVMs.getMatchVMs(targetVMs, "dataStore");
  dataStoreVMs.addAll(StopStartVMs.getMatchVMs(targetVMs, "bridge"));
  List accessorVMs = StopStartVMs.getMatchVMs(targetVMs, "accessor");
  accessorVMs.addAll(StopStartVMs.getMatchVMs(targetVMs, "edge"));
  Log.getLogWriter().info("Starting dataStores first");
  if (isDataStore) { // this vm is a dataStore; it must start now
    List<Thread> threadList = StopStartVMs.startAsync(dataStoreVMs);
    Log.getLogWriter().info("Recreating the partitioned region...");
    HA_reinitializeRegion("cache1");
    Log.getLogWriter().info("Done recreating the partitioned region...");
    for (Thread aThread: threadList) {
      try {
        aThread.join();
      } catch (InterruptedException e) {
        throw new TestException(TestHelper.getStackTrace(e));
      }
    }
    Log.getLogWriter().info("Starting accessors");
    StopStartVMs.startVMs(accessorVMs);
  } else { // this vm is an accessor; it starts after the dataStores
    StopStartVMs.startVMs(dataStoreVMs);
    Log.getLogWriter().info("Starting accessors");
    Log.getLogWriter().info("Recreating the partitioned region...");
    HA_reinitializeRegion(getCachePrmsName());
    Log.getLogWriter().info("Done recreating the partitioned region...");
    if (accessorVMs.size() != 0) {
      StopStartVMs.startVMs(accessorVMs);
    }
  } 
  ParRegBB.getBB().getSharedMap().put(shutDownAllInProgressKey, new Boolean(false));

  // Wait for recovery to complete
  if (ParRegBB.getBB().getSharedCounters().read(ParRegBB.MaxRC) > 0) {
    List targetDataStores = StopStartVMs.getMatchVMs(targetVMs, "dataStore");
    targetDataStores.addAll(StopStartVMs.getMatchVMs(targetVMs, "bridge"));
    if (isDataStore) {
      ClientVmInfo thisVM = new ClientVmInfo(RemoteTestModule.getMyVmid());
      targetDataStores.add(thisVM);
    }
    long recoveryDelay = ((Long)(ParRegBB.getBB().getSharedMap().get("recoveryDelay"))).longValue();
    long startupRecoveryDelay = ((Long)(ParRegBB.getBB().getSharedMap().get("startupRecoveryDelay"))).longValue();
    int numPRs = (ConfigPrms.getHadoopConfig() != null) ? 2 : 1;
    PRObserver.waitForRecovery(recoveryDelay, startupRecoveryDelay, 
        targetDataStores, null, 1, numPRs, null, null);
  }
  Log.getLogWriter().info("Done cycling vms by using shutDownAllMembers");
}

/**
 * Clear the critical state for the provided members
 * @param targetVMs
 */
protected void clearBBCriticalState(List<ClientVmInfo> targetVMs) {
}

/** Return a value for the given key
 */
public BaseValueHolder getValueForKey(Object key) {
   return createObject((String)key);
}

  protected BaseValueHolder createObject (Object key) {
    if (objectType.equals("delta.DeltaValueHolder")) {
      return new DeltaValueHolder((String)key, randomValues);
    } else if ((objectType.equals("util.PdxVersionedValueHolder")) ||
               (objectType.equals("util.VersionedValueHolder"))) {
      BaseValueHolder vh = PdxTest.getVersionedValueHolder(objectType, (String)key, randomValues);
      return vh;
    } else {
      return new ValueHolder((String)key, randomValues);
    }
  }
    
/** Return a new key, never before used in the test.
 */
protected Object getNewKey() {
   if (uniqueKeys) {
      int anInt = ((Integer)(uniqueKeyIndex.get())).intValue(); 
      anInt += numThreadsInClients;
      uniqueKeyIndex.set(new Integer(anInt));
      ParRegBB.getBB().getSharedMap().put("Thread_" + RemoteTestModule.getCurrentThread().getThreadId(), anInt);
      return NameFactory.getObjectNameForCounter(anInt);
   } else {
      return NameFactory.getNextPositiveObjectName();
   }
}
    
/** Return a random recently used key.
 *
 *  @param aRegion The region to use for getting a recently used key.
 *  @param recentHistory The number of most recently used keys to consider
 *         for returning.
 *
 *  @returns A recently used key, or null if none.
 */
protected Object getRecentKey(Region r, int recentHistory) {
   long maxNames = NameFactory.getPositiveNameCounter();
   if (maxNames <= 0) {
      return null;
   }
   long keyIndex = TestConfig.tab().getRandGen().nextLong(
                      Math.max(maxNames-recentHistory, 1), 
                      maxNames);
   Object key = NameFactory.getObjectNameForCounter(keyIndex);
   return key;
}

/** Return an object to be used to update the given key. If the
 *  value for the key is a ValueHolder, then get an alternate
 *  value which is similar to it's previous value (see
 *  ValueHolder.getAlternateValueHolder()).
 *
 *  @param aRegion The region which possible contains key.
 *  @param key The key to get a new value for.
 *  
 *  @returns An update to be used to update key in aRegion.
 */
protected Object getUpdateObject(Region r, String key) {
  BaseValueHolder vhObj = null;
  Object getObj = null;
  if (r.containsKey(key)) { // key is here; we want to avoid invoking a cache loader
    if (r.containsValueForKey(key)) { // safe to do a get without rattling anything
      getObj = r.get(key);
      vhObj = PdxTest.toValueHolder(getObj);
    }
  } else { // key not here
    if (isThinClient && !hasPRCacheLoader) {
      getObj = r.get(key);
      vhObj = PdxTest.toValueHolder(getObj);
      try { // get might have pulled in value; restore region to original state
        r.localDestroy(key);
      } catch (EntryNotFoundException e) {
        Log.getLogWriter().info("Caught expected " + e + "; continuing test");
      }
    } else if (isEmptyClient) {
      getObj = r.get(key);
      vhObj = PdxTest.toValueHolder(getObj);
    }
  }
  Object newObj = null;
  if (vhObj == null) {
    newObj = createObject(key);
  } else {
    BaseValueHolder vh = vhObj.getAlternateValueHolder(randomValues);
    if (getObj instanceof PdxInstance) { // we can return a PdxInstance to put
      PdxInstance pdxInst = (PdxInstance)getObj;
      WritablePdxInstance writablePdxInst = pdxInst.createWriter();
      writablePdxInst.setField("myValue", vh.myValue);
      writablePdxInst.setField("extraObject", vh.extraObject);
      newObj = writablePdxInst;
    } else {
      newObj = vh;
    }
  }
  return newObj;
}

/** Log the execution number of this serial task.
 */
static protected void logExecutionNumber() {
   long exeNum = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.ExecutionNumber);
   Log.getLogWriter().info("Beginning task with execution number " + exeNum);
}
    
/** Get an operation to perform on the region
 *  @param reg The region to get an operation for. 
 *  @returns [0] A random operation.
 *           [1] Any extra information to go along with an op.
 */
protected int[] getOperation(Region reg) {
   Long opsPrm = ParRegPrms.entryOperations;
   Long upperThresholdOpsPrm = ParRegPrms.upperThresholdOperations;
   Long lowerThresholdOpsPrm = ParRegPrms.lowerThresholdOperations;
   if (TestConfig.tab().booleanAt(ParRegPrms.designateOps, false)) { 
      // ops are designated by the type of vm, dataStore or accessor
      if (isDataStore) {
         opsPrm = ParRegPrms.dataStoreOperations; 
         upperThresholdOpsPrm = ParRegPrms.upperThresholdDataStoreOperations; 
         lowerThresholdOpsPrm = ParRegPrms.lowerThresholdDataStoreOperations; 
      } else { // is accessor
         opsPrm = ParRegPrms.accessorOperations; 
         upperThresholdOpsPrm = ParRegPrms.upperThresholdAccessorOperations; 
         lowerThresholdOpsPrm = ParRegPrms.lowerThresholdAccessorOperations; 
      }
   }
   int[] opInfo = getOperation(opsPrm);
   int size = 0;
   if (isEmptyClient || isThinClient) {
     size = reg.keySetOnServer().size();
   } else {
     size = reg.size();
   }
   if (size >= upperThreshold) {
      opInfo = getOperation(upperThresholdOpsPrm);
   } else if (size <= lowerThreshold) {
      opInfo = getOperation(lowerThresholdOpsPrm);
   }
   return opInfo;
}

/** Get a random operation using the given hydra parameter.
 *
 *  @param whichPrm A hydra parameter which specifies random operations.
 *
 *  @returns [0] A random operation.
 *           [1] Any extra information to go along with an op.
 */
protected int[] getOperation(Long whichPrm) {
   int op = 0;
   int extra = 0;
   String operation = TestConfig.tab().stringAt(whichPrm);
   if (operation.equals("add")) {
      op = ENTRY_ADD_OPERATION;
   } else if (operation.equals("update")) {
      op = ENTRY_UPDATE_OPERATION;
   } else if (operation.equals("invalidate")) {
      op = ENTRY_INVALIDATE_OPERATION;
   } else if (operation.equals("destroy")) {
      op = ENTRY_DESTROY_OPERATION;
   } else if (operation.equals("get")) {
      op = ENTRY_GET_OPERATION;
   } else if (operation.equals("getNew")) {
      op = ENTRY_GET_NEW_OPERATION;
   } else if (operation.equals("localInvalidate")) {
      op = ENTRY_LOCAL_INVALIDATE_OPERATION;
   } else if (operation.equals("localDestroy")) {
      op = ENTRY_LOCAL_DESTROY_OPERATION;
   } else if (operation.equals("putAll")) {
      op = ENTRY_PUTALL_OPERATION;
   } else if (operation.startsWith("destroyPercent")) {
      op = ENTRY_DESTROY_PERCENT_OPERATION;
      extra = Integer.valueOf(operation.substring("destroyPercent".length(), operation.length())).intValue();
   } else if (operation.equals("putIfAbsent")) {
      op = ENTRY_PUT_IF_ABSENT_OPERATION;
   } else if (operation.equals("putIfAbsentAsGet")) {
      op = ENTRY_PUT_IF_ABSENT_AS_GET_OPERATION;
   } else if (operation.equals("putIfAbsentAsCreate")) {
      op = ENTRY_PUT_IF_ABSENT_AS_CREATE_OPERATION;
   } else if (operation.equals("remove")) {
      op = ENTRY_REMOVE_OPERATION;
   } else if (operation.equals("removeAsNoop")) {
      op = ENTRY_REMOVE_AS_NOOP_OPERATION;
   } else if (operation.equals("replace")) {
      op = ENTRY_REPLACE_OPERATION;
   } else if (operation.equals("replaceAsNoop")) {
      op = ENTRY_REPLACE_AS_NOOP_OPERATION;
   } else if (operation.equals("replaceAsUpdate")) {
      op = ENTRY_REPLACE_AS_UPDATE_OPERATION;
   } else if (operation.equals("replaceNoInval")) {
     op = ENTRY_REPLACE_NO_INVAL_OPERATION;
   } else if (operation.equals("replaceOld")) {
      op = ENTRY_REPLACE_OLD_OPERATION;
   } else if (operation.equals("replaceOldAsNoop")) {
      op = ENTRY_REPLACE_OLD_AS_NOOP_OPERATION;
   } else if (operation.equals("replaceOldAsUpdate")) {
      op = ENTRY_REPLACE_OLD_AS_UPDATE_OPERATION;
   } else if (operation.equals("replaceOldNoInval")) {
     op = ENTRY_REPLACE_OLD_NO_INVAL_OPERATION;
   } else if (operation.equals("query")) {
     op = QUERY_OPERATION;
   } else if (operation.equals("createIndex")) {
     op = CREATE_INDEX_OPERATION;
   } else if (operation.equals("removeIndex")) {
     op = REMOVE_INDEX_OPERATION;
   } else {
      throw new TestException("Unknown entry operation: " + operation);
   }
   return new int[] {op, extra};
}

/** Register interest with ALL_KEYS, and InterestPolicyResult = KEYS_VALUES
 *  which is equivalent to a full GII.
 */
protected void registerInterest() {
   ParRegUtil.registerInterest(aRegion);
}

/** Do operations on the region. The operations are destroy, update and create.
 *  Using ParRegPrms.opPercentage, determine the number of operations for each
 *  of destroy, update and create.
 */
protected void doIntervalOps() {
   int opPercentage = ParRegPrms.getOpPercentage();
   int numOps = (int)(testInstance.upperThreshold * (opPercentage / 100.0));
   Log.getLogWriter().info("Each operation will occur for " + opPercentage + " percent of " + testInstance.upperThreshold + " entries");
   Log.getLogWriter().info("Number of entries that will receive each operation: " + numOps);
   int interval = testInstance.upperThreshold / numOps;

   // each time through the loop, start with currentKeyIndex and destroy that key
   // then update currentKeyIndex+1, then add a new key
   long startTimeMillis = System.currentTimeMillis();
   do {
     // get the keyIndex for this round of ops; coordinate with other threads
     long operationsCoordinator = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.operationsCoordinator);
     long currentKeyIndex = ((operationsCoordinator - 1) * interval) + 1;
     Log.getLogWriter().info("Current key index is " + currentKeyIndex);
     if (currentKeyIndex >= testInstance.upperThreshold) {
       throw new StopSchedulingTaskOnClientOrder("Ops have completed");
     }

     // destroy
     Object key = NameFactory.getObjectNameForCounter(currentKeyIndex);
     testInstance.aRegion.get(key); // first get the value in case this is a client that did not have it due to eviction
     Log.getLogWriter().info("Operation for key " + key + ": Destroying");
     testInstance.aRegion.destroy(key);
     
     // update
     key =  NameFactory.getObjectNameForCounter(currentKeyIndex+1);
     BaseValueHolder vh = PdxTest.toValueHolder(testInstance.aRegion.get(key));
     vh.myValue = (Long)(vh.myValue) + 1;
     Log.getLogWriter().info("Operation for key " + key + ": Updating to " + TestHelper.toString(vh));
     testInstance.aRegion.put(key, vh);
     
     // create
     key = NameFactory.getNextPositiveObjectName();
     vh = testInstance.getValueForKey(key);
     Log.getLogWriter().info("Operation for key " + key + ": Creating with value " + TestHelper.toString(vh));
     testInstance.aRegion.create(key, vh);
    } while (System.currentTimeMillis() - startTimeMillis < testInstance.minTaskGranularityMS);
}

/** Initialize interval validation counters
 *  This must be called prior to interval validation
 */
protected void initForIntervalValidation() {
   currentValidateKeyIndex = 1;
   currentIntervalKeyIndex = 1;
}

  /**
   * Validate the operations from doIntervalOps.
   */
  protected void validateIntervalOps() {
    int opPercentage = ParRegPrms.getOpPercentage();
    int numOps = (int)(testInstance.upperThreshold * (opPercentage / 100.0));
    int interval = testInstance.upperThreshold / numOps;

    // validate all keys in a batched task; loop for task granularity time
    Log.getLogWriter().info(
        "Beginning validation with key index " + currentValidateKeyIndex);
    long startTimeMillis = System.currentTimeMillis();
    do {
      if (currentValidateKeyIndex >= upperThreshold) { // key was created
        Object key = NameFactory
            .getObjectNameForCounter(currentValidateKeyIndex);
        BaseValueHolder value = PdxTest.toValueHolder(aRegion.get(key));
        if (value == null) {
          throw new TestException("For key " + key + " value is null");
        }
        if ((Long)(value.myValue) != (currentValidateKeyIndex)) {
          throw new TestException("For key " + key
              + ", expected myValue to be " + (currentValidateKeyIndex)
              + ", but it is " + TestHelper.toString(value));
        }
      } else if (currentValidateKeyIndex == currentIntervalKeyIndex) { 
        // key was a destroy
        currentIntervalKeyIndex += interval;
        Object key = NameFactory
            .getObjectNameForCounter(currentValidateKeyIndex);
        BaseValueHolder value = PdxTest.toValueHolder(aRegion.get(key));
        if (value != null) {
          throw new TestException("Key " + key
              + " was destroyed, but get returned "
              + TestHelper.toString(value));
        }

        // next key was an update
        currentValidateKeyIndex++;
        key = NameFactory.getObjectNameForCounter(currentValidateKeyIndex);
        value = PdxTest.toValueHolder(aRegion.get(key));
        if (value == null) {
          throw new TestException("Value for " + key + " is null");
        }
        if ((Long)(value.myValue) != (currentValidateKeyIndex + 1)) {
          throw new TestException("For key " + key
              + ", expected myValue to be " + (currentValidateKeyIndex + 1)
              + ", but it is " + TestHelper.toString(value));
        }
      } else { // this key did not receive an op
        Object key = NameFactory
            .getObjectNameForCounter(currentValidateKeyIndex);
        BaseValueHolder value = PdxTest.toValueHolder(aRegion.get(key));
        if (value == null) {
          throw new TestException("For key " + key + " value is null");
        }
        if ((Long)(value.myValue) != (currentValidateKeyIndex)) {
          throw new TestException("For key " + key
              + ", expected myValue to be " + (currentValidateKeyIndex)
              + ", but it is " + TestHelper.toString(value));
        }
      }
      currentValidateKeyIndex++;
      if (currentValidateKeyIndex > NameFactory.getPositiveNameCounter()) {
        initForIntervalValidation(); // prepare for the next validation (if any)
        throw new StopSchedulingTaskOnClientOrder("Validation has completed "
            + NameFactory.getPositiveNameCounter() + " keys");
      }
    // hydra allows the batch keyword on endtasks, but does not honor it, so 
    // we can't really batch this task
    //} while (System.currentTimeMillis() - startTimeMillis < testInstance.minTaskGranularityMS);
    } while (true);
    //Log.getLogWriter().info(
    //    "Validate has completed " + (currentValidateKeyIndex - 1)
    //        + " keys out of " + NameFactory.getPositiveNameCounter());
  }

// ========================================================================
// verification methods

  /** Verify the region contents against the blackboard AND verify the
   *  internal state of the PR.
   */
  public void verifyFromSnapshot() {
    verifyFromSnapshotOnly();
    verifyInternalPRState();
  }
  
/** Verify the region contents against the blackboard; do not verify the
 *  internal state of the PR.
 */
public void verifyFromSnapshotOnly() {

   // HDFS support 
   boolean withEviction = (aRegion.getAttributes().getEvictionAttributes().getAlgorithm().equals(EvictionAlgorithm.NONE)) ? false : true;
   if (aRegion.getAttributes().getDataPolicy().withHDFS()) {
      verifyHDFSRegionFromSnapshot();
      return;
   }

   if (isEmptyClient) {
     verifyServerKeysFromSnapshot();
     return;
   } else if (isThinClient) {
     verifyThinClientFromSnapshot();
     verifyServerKeysFromSnapshot();
     return;
   }
   StringBuffer aStr = new StringBuffer();
   regionSnapshot = (Map)(ParRegBB.getBB().getSharedMap().get(ParRegBB.RegionSnapshot));
   destroyedKeys = (Set)(ParRegBB.getBB().getSharedMap().get(ParRegBB.DestroyedKeys));

   int snapshotSize = regionSnapshot.size();
   int regionSize = aRegion.size();
   Log.getLogWriter().info("Verifying from snapshot containing " + snapshotSize + " entries...");

   if (snapshotSize != regionSize) {
      aStr.append("Expected region " + aRegion.getFullPath() + " to be size " + snapshotSize + 
           ", but it is " + regionSize + "\n");
      ((LocalRegion)aRegion).dumpBackingMap();
   }

   Set inDoubtOps = ParRegBB.getBB().getFailedOps(ParRegBB.INDOUBT_TXOPS);
   if (inDoubtOps.size() > 0) {
      Log.getLogWriter().info(inDoubtOps.size() + " TransactionInDoubtExceptions occurred on the following keys:" + inDoubtOps);
   }
   Iterator it = regionSnapshot.entrySet().iterator();
   while (it.hasNext()) { // iterating the expected keys
      Map.Entry entry = (Map.Entry)it.next();
      Object key = entry.getKey();
      Object expectedValue = entry.getValue();

      // containsKey
      boolean anyFailures = false;
      try {
         ParRegUtil.verifyContainsKey(aRegion, key, true);
      } catch (TestException e) {
         aStr.append(e.getMessage() + "\n");
//         anyFailures = true;
      }
//      if (anyFailures) {
//        ((LocalRegion)aRegion).dumpBackingMap();
//      }

      // containsValueForKey
      boolean containsValueForKey = aRegion.containsValueForKey(key);
      try {
         ParRegUtil.verifyContainsValueForKey(aRegion, key, (expectedValue != null));
      } catch (TestException e) {
         aStr.append(e.getMessage() + "\n");
      }

      // do a get on the partitioned region if a loader won't get invoked; test its value
      if (containsValueForKey || !hasPRCacheLoader) {
         // loader won't be invoked if we have a value for this key (whether or not a loader
         // is installed), or if we don't have a loader at all
         try {
            Object actualValue = aRegion.get(key);
            ParRegUtil.verifyMyValue(key, expectedValue, actualValue, ParRegUtil.EQUAL);
         } catch (TestException e) {
            aStr.append(e.getMessage() + "\n");
         }
      }
   }
 
   // check that destroyedKeys are not in the region
   it = destroyedKeys.iterator();
   while (it.hasNext()) {
      Object key = it.next();
      try {
         ParRegUtil.verifyContainsKey(aRegion, key, false);
      } catch (TestException e) {
         aStr.append(e.getMessage() + "\n");
      }
   }

   // check the size
//   ((LocalRegion)aRegion).dumpBackingMap();
   Set prKeySet = new HashSet(keySetWithoutCreates(aRegion)); // must be a HashSet to support removeAll below
   if (prKeySet.size() != regionSize) {
      aStr.append("Size for " + aRegion.getFullPath() + " is " + regionSize +
         ", but its keySet size is " + prKeySet.size() + "\n");
      
      ((LocalRegion)aRegion).dumpBackingMap();

//      try { Thread.sleep(TombstoneService.CLIENT_TOMBSTONE_TIMEOUT); } catch (InterruptedException e) { }
   }

   // check for extra keys in PR that were not in the snapshot
   Set snapshotKeys = regionSnapshot.keySet();
   prKeySet.removeAll(snapshotKeys);
   if (prKeySet.size() != 0) {
      aStr.append("Found the following unexpected keys in " + aRegion.getFullPath() + 
                  ": " + prKeySet + "\n");
   }

   if (isBridgeClient) {
     try {
       verifyServerKeysFromSnapshot();
     } catch (TestException e) {
       aStr.append(e.getMessage() + "\n");
     }
   }

   if (aStr.length() > 0) {
      // shutdownHook will cause all members to dump partitioned region info
     ((LocalRegion)aRegion).dumpBackingMap();
      throw new TestException(aStr.toString());
   }
   Log.getLogWriter().info("Done verifying from snapshot containing " + snapshotSize + " entries...");
}

/** HDFS version (cannot use region.size, containsKey, containsValueForKey)
 *  limited validation (no checks for missing/extra entries)
 */
public void verifyHDFSRegionFromSnapshot() {

   StringBuffer aStr = new StringBuffer();
   regionSnapshot = (Map)(ParRegBB.getBB().getSharedMap().get(ParRegBB.RegionSnapshot));
   destroyedKeys = (Set)(ParRegBB.getBB().getSharedMap().get(ParRegBB.DestroyedKeys));
   StringBuffer problemKeys = new StringBuffer();

   int snapshotSize = regionSnapshot.size();
   Log.getLogWriter().info("Verifying HDFS Region from snapshot containing " + snapshotSize + " entries...");

   Set inDoubtOps = ParRegBB.getBB().getFailedOps(ParRegBB.INDOUBT_TXOPS);
   if (inDoubtOps.size() > 0) {
      Log.getLogWriter().info(inDoubtOps.size() + " TransactionInDoubtExceptions occurred on the following keys:" + inDoubtOps);
   }
   Iterator it = regionSnapshot.entrySet().iterator();
   while (it.hasNext()) { // iterating the expected keys
      Map.Entry entry = (Map.Entry)it.next();
      Object key = entry.getKey();
      Object expectedValue = entry.getValue();

      try {
         Object actualValue = aRegion.get(key);
         ParRegUtil.verifyMyValue(key, expectedValue, actualValue, ParRegUtil.EQUAL);
      } catch (TestException e) {
         aStr.append(e.getMessage() + "\n");
         problemKeys.append(key + " ");
      }
   }
 
   // check that destroyedKeys are not in the region
   it = destroyedKeys.iterator();
   while (it.hasNext()) {
      Object key = it.next();
      try {
         ParRegUtil.verifyContainsKey(aRegion, key, false);
      } catch (TestException e) {
         aStr.append(e.getMessage() + "\n");
         problemKeys.append(key + " ");
      }
   }

   if (aStr.length() > 0) {
      Log.getLogWriter().info("Starting mapreduce job to help debug: " + aStr.toString());
      HDFSUtil.getAllHDFSEventsForKey(problemKeys.toString());

      // shutdownHook will cause all members to dump partitioned region info
      HDFSUtil.dumpHDFSResultRegion();
      ((LocalRegion)aRegion).dumpBackingMap();
      throw new TestException(aStr.toString());
   }
   Log.getLogWriter().info("Done verifying HDFS Region from snapshot containing " + snapshotSize + " entries...");
}

/** Verify server keys in this client which might be empty or thin.
 */
public void verifyServerKeysFromSnapshot() {
  StringBuffer aStr = new StringBuffer();
  regionSnapshot = (Map)(ParRegBB.getBB().getSharedMap().get(ParRegBB.RegionSnapshot));
  destroyedKeys = (Set)(ParRegBB.getBB().getSharedMap().get(ParRegBB.DestroyedKeys));
  Set serverKeys = aRegion.keySetOnServer();
  int snapshotSize = regionSnapshot.size();
  int numServerKeys = serverKeys.size();
  Log.getLogWriter().info("Verifying server keys from snapshot containing " + snapshotSize + " entries...");
  if (snapshotSize != numServerKeys) {
    aStr.append("Expected number of keys on server to be " + snapshotSize + ", but it is " + numServerKeys + "\n");
  }
  Iterator it = regionSnapshot.entrySet().iterator();
  while (it.hasNext()) { // iterating the expected keys
    Map.Entry entry = (Map.Entry)it.next();
    Object key = entry.getKey();
    Object expectedValue = entry.getValue();
    if (!serverKeys.contains(key)) {
      aStr.append("Expected key " + key + " to be in server keys set, but it is missing\n");
    } else {
      // only do a get if we will not invoke the silence listener on a get
      if ((!isThinClient && !isEmptyClient) ||
          (isThinClient && aRegion.containsKey(key))) { 
           Object valueOnServer = aRegion.get(key);
           try {
             ParRegUtil.verifyMyValue(key, expectedValue, valueOnServer, ParRegUtil.EQUAL);
           } catch (TestException e) {
             aStr.append(e.getMessage() + "\n");
           }
      }
    }
  }

   // check that destroyedKeys are not in the server keys
   it = destroyedKeys.iterator();
   while (it.hasNext()) {
      Object key = it.next();
      if (serverKeys.contains(key)) {
        aStr.append("Destroyed key " + key + " was returned as a server key\n");
      }
   }

   // check for extra server keys that were not in the snapshot
   Set snapshotKeys = regionSnapshot.keySet();
   serverKeys.removeAll(snapshotKeys);
   if (serverKeys.size() != 0) {
      aStr.append("Found the following unexpected keys in server keys: " + 
                  ": " + serverKeys + "\n");
   }

   if (aStr.length() > 0) {
      // shutdownHook will cause all members to dump partitioned region info
      throw new TestException(aStr.toString());
   }
   Log.getLogWriter().info("Done verifying server keys from snapshot containing " + snapshotSize + " entries...");
}

/** Verify the internal state of the PR including PR metaData, primaries
 *  and redundantCopy consistency. This can only be called when redundancy
 *  recovery is not running.
 */
public void verifyInternalPRState() {
  StringBuffer aStr = new StringBuffer();

  if (ConfigPrms.getHadoopConfig() != null) {
     Log.getLogWriter().info("ParRegTest.verifyInternalPRState(): Cannot validate PRState for HDFS regions, returning without validating PRState");
     return;
  }

  // verify PR metadata 
  try {
    ParRegUtil.verifyPRMetaData(aRegion);
  } catch (Exception e) {
    aStr.append(TestHelper.getStackTrace(e) + "\n");
  } catch (TestException e) {
    aStr.append(TestHelper.getStackTrace(e) + "\n");
  }

  // verify primaries
  try {
    if (highAvailability) { 
      ParRegUtil.verifyPrimariesWithWait(aRegion, redundantCopies);
    } else {
      ParRegUtil.verifyPrimaries(aRegion, redundantCopies);
    }
  } catch (Exception e) {
    aStr.append(e.toString() + "\n");
    aStr.append(e.toString() + "\n");
  }

  // verify PR data
  try {
    ParRegUtil.verifyBucketCopies(aRegion, redundantCopies);
  } catch (Exception e) {
    aStr.append(TestHelper.getStackTrace(e) + "\n");
  } catch (TestException e) {
    aStr.append(e.toString() + "\n");
  }

  // uniqueHosts
  if (uniqueHostsOn) {
    try {
      ParRegUtil.verifyBucketsOnUniqueHosts(aRegion);
    } catch (Exception e) {
      aStr.append(TestHelper.getStackTrace(e) + "\n");
    } catch (TestException e) {
      aStr.append(TestHelper.getStackTrace(e) + "\n");
    }
  }
  
  if (aStr.length() > 0) {
    // shutdownHook will cause all members to dump partitioned region info
    throw new TestException(aStr.toString());
  }
  Log.getLogWriter().info("Done verifying PR internal consistency");
}

/** Verify this thin client (meaning it has eviction to keep it small, so
 *  not all entries are in this client.
 *  
 */
public void verifyThinClientFromSnapshot() {
  StringBuffer aStr = new StringBuffer();
  regionSnapshot = (Map)(ParRegBB.getBB().getSharedMap().get(ParRegBB.RegionSnapshot));
  destroyedKeys = (Set)(ParRegBB.getBB().getSharedMap().get(ParRegBB.DestroyedKeys));
  int snapshotSize = regionSnapshot.size();
  int regionSize = aRegion.size();
  Log.getLogWriter().info("Verifying thin client from snapshot containing " + snapshotSize + " entries, verifying only those present");
  if (aRegion.getAttributes().getEvictionAttributes().getAlgorithm().isLRUEntry()) { // eviction is LRU entry
    int lruMax = aRegion.getAttributes().getEvictionAttributes().getMaximum();
    if (isSerialExecution) {
       if (regionSize > lruMax) {
         aStr.append("Client region with LRU entry max " + lruMax + " contains " + regionSize + " entries\n");
       }
    } else { 
       int allowable = lruMax + TestHelper.getNumThreads();
       if (regionSize > allowable) {
          aStr.append("Client region with LRU entry max " + lruMax + " contains " + regionSize + " entries, allowing " + allowable + " entries\n");
          ((LocalRegion)aRegion).dumpBackingMap();
       }
    }
  }
  Iterator it = regionSnapshot.entrySet().iterator();
  while (it.hasNext()) { // iterating the expected keys
    Map.Entry entry = (Map.Entry)it.next();
    Object key = entry.getKey();
    Object expectedValue = entry.getValue();
    if (aRegion.containsKey(key)) { // entry is here
      // containsValueForKey
      boolean containsValueForKey = aRegion.containsValueForKey(key);
      try {
        ParRegUtil.verifyContainsValueForKey(aRegion, key, (expectedValue != null));
      } catch (TestException e) {
        aStr.append(e.getMessage() + "\n");
      }

      // check the value for the key
      if (containsValueForKey || !hasPRCacheLoader) {
        // loader won't be invoked if we have a value for this key (whether or not a loader
        // is installed), or if we don't have a loader at all
        try {
          Object actualValue = aRegion.get(key);
          ParRegUtil.verifyMyValue(key, expectedValue, actualValue, ParRegUtil.EQUAL);
        } catch (TestException e) {
          aStr.append(e.getMessage() + "\n");
        }
      }
    } 
  }

  // check that destroyedKeys are not in the region
  it = destroyedKeys.iterator();
  while (it.hasNext()) {
    Object key = it.next();
    try {
      ParRegUtil.verifyContainsKey(aRegion, key, false);
    } catch (TestException e) {
      aStr.append(e.getMessage() + "\n");
    }
  }

  // check for extra keys in PR that were not in the snapshot
  Set snapshotKeys = regionSnapshot.keySet();
  Set localKeySet = new HashSet(aRegion.keySet()); // must be wrapped in HashSet to invoke removeAll below
  localKeySet.removeAll(snapshotKeys);
  if (localKeySet.size() != 0) {
    aStr.append("Found the following unexpected keys in " + aRegion.getFullPath() + 
        ": " + localKeySet + "\n");
  }

  if (aStr.length() > 0) {
    // shutdownHook will cause all members to dump partitioned region info
    throw new TestException(aStr.toString());
  }
  Log.getLogWriter().info("Done verifying thin client from snapshot containing " + snapshotSize + " entries...");
}


private static Collection keySetWithoutCreates(Region region) {
  if (region instanceof PartitionedRegion) {
    return ((PartitionedRegion) region).keysWithoutCreatesForTests();
  } else { 
    return region.keySet();
  }
}

/** 
 */
protected void concVerify() {
   boolean leader = false;
   if (!isEmptyClient && !isThinClient) { // empty/thin clients cannot be the concurrent leader since they
                                          // do not have all entries for writing the snapshot
      leader = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.ConcurrentLeader) == 1;
   }
   if (leader) {
      Log.getLogWriter().info("In concVerify, this thread is the concurrent leader");
      // this is the first thread to verify; all other threads will wait for this thread to
      // write its view of the partitioned region to the blackboard and they will read it and
      // match it

      // HDFS support (no region.size(), region.keySet(), region.containsKey(), etc
      boolean withEviction = (aRegion.getAttributes().getEvictionAttributes().getAlgorithm().equals(EvictionAlgorithm.NONE)) ? false : true;
      if ((aRegion.getAttributes().getDataPolicy().withHDFS()) && withEviction) {
        writeHDFSRegionSnapshotToBB();
        return;
      }

      regionSnapshot = new HashMap();
      destroyedKeys = new HashSet();
      Log.getLogWriter().info("This thread is the concurrentLeader, creating region snapshot..."); 
      Set keySet = aRegion.keySet();
      Iterator it = keySet.iterator();
      while (it.hasNext()) {
         Object key = it.next();
         Object value = null;
         if (aRegion.containsValueForKey(key)) { // won't invoke a loader (if any)
            value = aRegion.get(key);
         }
         if ((value instanceof BaseValueHolder) || (value instanceof PdxInstance)) {
            regionSnapshot.put(key, (PdxTest.toValueHolder(value)).myValue);
         } else {
            regionSnapshot.put(key, value);
         }
      }
      Log.getLogWriter().info("Done creating region snapshot with " + regionSnapshot.size() + " entries; " + regionSnapshot);
      ParRegBB.getBB().getSharedMap().put(ParRegBB.RegionSnapshot, regionSnapshot);
      Log.getLogWriter().info("Done creating destroyed keys with " + destroyedKeys.size() + " keys");
      ParRegBB.getBB().getSharedMap().put(ParRegBB.DestroyedKeys, destroyedKeys);
      long snapshotWritten = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.SnapshotWritten);
      Log.getLogWriter().info("Incremented SnapshotWritten, now is " + snapshotWritten);
   } else { 
      Log.getLogWriter().info("In concVerify, this thread is waiting for the concurrent leader to write the snapshot");
      // this thread is not the first to verify; it will wait until the first thread has
      // written its state to the blackboard, then it will read it and verify that its state matches
      TestHelper.waitForCounter(ParRegBB.getBB(), 
                                "ParRegBB.SnapshotWritten", 
                                ParRegBB.SnapshotWritten, 
                                1, 
                                true, 
                                -1,
                                2000);
      verifyFromSnapshot();
   }
}

/** With all invalidates and destroys removed from test we will have PositiveNameCounter 
 *  entries in the cache.  We can build the region snapshot by interating over 
 *  Object_1 ... Object_<positiveNameCounter> 
 */
protected void writeHDFSRegionSnapshotToBB() {

   regionSnapshot = new HashMap();
   destroyedKeys = new HashSet();
   Log.getLogWriter().info("This thread is the concurrentLeader, creating HDFS region snapshot..."); 

   long lastKeyCounter = NameFactory.getPositiveNameCounter();
   for (int i = 1; i <= lastKeyCounter; i++) {
      Object key = NameFactory.getObjectNameForCounter(i);
      if (aRegion.containsKey(key)) {
         Object value = aRegion.get(key);
   
         if ((value instanceof BaseValueHolder) || (value instanceof PdxInstance)) {
            regionSnapshot.put(key, (PdxTest.toValueHolder(value)).myValue);
         } else {
            regionSnapshot.put(key, value);
         }
      }
   }

   Log.getLogWriter().info("Done creating HDFS region snapshot with " + regionSnapshot.size() + " entries; " + regionSnapshot);
   ParRegBB.getBB().getSharedMap().put(ParRegBB.RegionSnapshot, regionSnapshot);
   Log.getLogWriter().info("Done creating destroyed keys with " + destroyedKeys.size() + " keys");
   ParRegBB.getBB().getSharedMap().put(ParRegBB.DestroyedKeys, destroyedKeys);
   long snapshotWritten = ParRegBB.getBB().getSharedCounters().incrementAndRead(ParRegBB.SnapshotWritten);
   Log.getLogWriter().info("Incremented SnapshotWritten, now is " + snapshotWritten);
}

  /**
   * @param uniqueBackupID A unique number used to create a directory in the
   *      current directory to be used
   */
  private static void createDiskDirBackup(String backupDirName) {
    Log.getLogWriter().info("Making a copy of all disk directories in " + backupDirName);
    File backupDir = new File(backupDirName);
    if (backupDir.exists()) {
      throw new TestException("Backup directory " + backupDirName + " already exists");
    }
    backupDir.mkdir();

    // Create a script to do the copy
    String scriptFileName = "makeBackup.sh";
    File scriptFile = new File(scriptFileName);
    PrintWriter aFile;
    try {
      aFile = new PrintWriter(new FileOutputStream(scriptFile));
    } catch (FileNotFoundException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    }
    aFile.print("#! /bin/sh\ncp -r vm*_disk_* $1\n");
    aFile.flush();
    aFile.close();

    // copy the disk directories to the backup directory
    String cmd = "/bin/bash " + scriptFileName + " " + backupDirName;
    String cmdResults = ProcessMgr.fgexec(cmd, 0);
    Log.getLogWriter().info("Results from " + cmd + " is " + cmdResults);
  }

  protected static boolean getIsDataStore() {
    if (testInstance.aRegion == null) {
      throw new TestException("aRegion is null");
    }
    PartitionAttributes prAttr = testInstance.aRegion.getAttributes().getPartitionAttributes();
    boolean result = false;
    if (prAttr == null) {
      result = false;
    } else {
      result = prAttr.getLocalMaxMemory() != 0;
    }
    ParRegBB.getBB().getSharedMap().put(isDataStoreKey + RemoteTestModule.getMyVmid(), result);
    return result;
  }
  
  /** Create the pdx disk store if one was specified.
   * 
   */
  protected void initPdxDiskStore() {
    if (CacheHelper.getCache().getPdxPersistent()) {
      String pdxDiskStoreName = TestConfig.tab().stringAt(CachePrms.pdxDiskStoreName, null);
      if (pdxDiskStoreName != null) {// pdx disk store name was specified
        if (CacheHelper.getCache().findDiskStore(pdxDiskStoreName) == null) {
          DiskStoreHelper.createDiskStore(pdxDiskStoreName);
        }
      }
    }
  }


}
