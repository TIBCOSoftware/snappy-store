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
package delta;

import java.io.EOFException;
import java.io.IOException;
import java.util.*;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.Delta;
import com.gemstone.gemfire.DeltaSerializationException;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.client.ServerConnectivityException;
import com.gemstone.gemfire.cache.client.ServerOperationException;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.util.BridgeWriterException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.CachedDeserializable;
import com.gemstone.gemfire.internal.cache.CachedDeserializableFactory;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.Token;
import diskReg.DiskRegUtil;
import hydra.*;
import parReg.ParRegUtil;
import util.*;

public class DeltaTest {
    
/* The singleton instance of DeltaTest in this VM */
static public DeltaTest testInstance;
    
/* blackboard sharedMap keys */
protected static final String redundantCopiesKey      = "RedundantCopies";
protected static final String toDeltaKey              = "toDeltaCallsForVmID_";
protected static final String fromDeltaKey            = "fromDeltaCallsForVmID_";
protected static final String hasDeltaKey             = "hasDeltaCallsForVmID_";
protected static final String updatedKeysKey          = "updatedKeysVmID_";
protected static final String updatedKeysWithDeltaKey = "updatedKeysWithDeltaVmID_";
protected static final String updatedKeysNotDistKey   = "updatedKeysNotDistVmID_";
protected static final String regionSnapshotKey       = "regionSnapshotVmID";

/* instance fields */
protected long minTaskGranularitySec;       // the task granularity in seconds
protected long minTaskGranularityMS;        // the task granularity in milliseconds
protected int numOpsPerTask;                // the number of operations to execute per task
public boolean isBridgeConfiguration;       // true if this test is being run in a bridge 
                                            //    configuration, false otherwise
public boolean isBridgeClient;              // true if this vm is a bridge client, false otherwise
protected int numThreadsInClients;          // the total number of client threads in this test 
public Region aRegion;                      // the region under test in this VM
public HydraThreadLocal uniqueKeyIndex = new HydraThreadLocal();
protected boolean isSerialExecution;        // true if this test is serial, false otherwise
protected boolean highAvailability;         // value of DeltaPropagationPrms.highAvailability
protected int upperThreshold;               // value of DeltaPropagationPrms.upperThreshold
protected int lowerThreshold;               // value of DeltaPropagationPrms.lowerThreshold
protected boolean uniqueKeys = false;       // whether each thread should use unique keys or not
protected int numQueriesPerClientVM;        // value of DeltaPropagationPrms.numQueriesPerClientVM
protected int secondsToRun;                 // number of seconds to allow tasks
protected boolean cacheIsClosed;            // true if this VM has closed the cache, false otherwise
protected boolean disconnected;             // true if this VM is disconnected from the distributed 
                                            // system, false otherwise
protected int numThreadsInThisVM = 0;       // the number of threads in this VM
protected int numFieldsToModify = 0;        // number of fields in DeltaObject to modify
protected List<Region> multiRegionList = new ArrayList(); // for tests with more than 1 region

// used for serial tests
protected List updatedKeys = new ArrayList();          // a list of keys whose value was updated (with either delta or full distribution)
protected List updatedKeysWithDelta = new ArrayList(); // a list of keys whose value was updated with delta distributions
protected List updatedKeysNotDistributed = new ArrayList(); 
                                                  // a list of keys whose value was updated AND 
                                                  //   does not require distribution
protected int numberOfCycles = 0;                 // number of cycles (one cycle is 2 rounds)  until ending 
                                                  //   this workload based test
protected int redundantCopies = 0;                // for a PR, the number of redundantCopies

// instance fields used to verify the contents of a region in serial tests
protected Map regionSnapshot;               // a "picture" of the expected region

// instance fields for eviction tests
protected long before_stat_entriesInVM = -1;
protected long before_stat_numOverflowOnDisk = -1;
protected long before_numBytesInThisVM = -1;
protected long before_stat_lruEvictions = 0;
protected Map<String, Integer> beforeMap = new HashMap();
protected Set<String> before_inVMSet = new HashSet(); // set of keys whose values are on the vm
protected Set<String> before_onDiskSet = new HashSet(); // set of keys whose values are on disk
protected String opKey = "operationKey"; // bb key
protected String currentKeyListKey = "keyList";
protected List<Long> overageHistory = new ArrayList();

// operations
static protected final int ENTRY_ADD_OPERATION = 1;
static protected final int ENTRY_DESTROY_OPERATION = 2;
static protected final int ENTRY_INVALIDATE_OPERATION = 3;
static protected final int ENTRY_LOCAL_DESTROY_OPERATION = 4;
static protected final int ENTRY_LOCAL_INVALIDATE_OPERATION = 5;
static protected final int ENTRY_UPDATE_OPERATION = 6;
static protected final int ENTRY_GET_OPERATION = 7;
static protected final int ENTRY_GET_NEW_OPERATION = 8;
static protected final int ENTRY_MODIFY_VALUE = 9;
    
// String prefixes for event callback object
protected static final String getCallbackPrefix = "Get originated in pid ";
protected static final String createCallbackPrefix = "Create event originated in pid ";
protected static final String updateCallbackPrefix = "Update event originated in pid ";
protected static final String invalidateCallbackPrefix = "Invalidate event originated in pid ";
protected static final String destroyCallbackPrefix = "Destroy event originated in pid ";
protected static final String regionInvalidateCallbackPrefix = "Region invalidate event originated in pid ";
protected static final String regionDestroyCallbackPrefix = "Region destroy event originated in pid ";
protected static final String fullDistribution = "Full distribution";

// fields to coordinate running a method once per VM
protected static volatile MethodCoordinator concVerifyCoordinator = 
          new MethodCoordinator(DeltaTest.class.getName(), "concVerify");

// ========================================================================
// initialization methods
    
/** Creates and initializes the singleton instance of DeltaTest in a client.
 */
public synchronized static void HydraTask_initializeClient() {
   if (testInstance == null) {
      testInstance = new DeltaTest();
      testInstance.aRegion = testInstance.initializeRegion(DeltaPropagationPrms.getRegionPrmsName());
      testInstance.initializeInstance();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = true;
         DeltaTest.registerInterest(testInstance.aRegion);
      }
   }
   testInstance.uniqueKeyIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}
    
/** Creates and initializes a bridge server.
 */
public synchronized static void HydraTask_initializeBridgeServer() {
   if (testInstance == null) {
      testInstance = new DeltaTest();
      testInstance.aRegion = testInstance.initializeRegion(DeltaPropagationPrms.getRegionPrmsName());
      testInstance.initializeInstance();
      BridgeHelper.startBridgeServer("bridge");
      testInstance.isBridgeClient = false;
   }
   testInstance.uniqueKeyIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}

/**
 *  Create a region with the given region description name.
 *
 *  @param regDescriptName The name of a region description.
 */
public Region initializeRegion(String regDescriptName) {
   CacheHelper.createCache("cache1");
   AttributesFactory factory = RegionHelper.getAttributesFactory(regDescriptName);
   RegionAttributes attr = RegionHelper.getRegionAttributes(regDescriptName);
   PartitionAttributes prAttr = attr.getPartitionAttributes();
   if (prAttr != null) {
      PartitionAttributesFactory prFactory = new PartitionAttributesFactory(prAttr);
      redundantCopies = ((Integer)(DeltaPropagationBB.getBB().getSharedMap().get(redundantCopiesKey))).intValue();
      prFactory.setRedundantCopies(redundantCopies);
      factory.setPartitionAttributes(prFactory.create());
   }
   String regionName = TestConfig.tab().stringAt(RegionPrms.regionName);
   Region aReg = RegionHelper.createRegion(regionName, factory);
   Log.getLogWriter().info("After creating " + aReg.getFullPath() + ", region is size " + aReg.size());

   // re-initialize the TransactionManager (for the newly created cache)
   if (getInitialImage.InitImagePrms.useTransactions()) {
      TxHelper.setTransactionManager();
   }

   return aReg;
}

/** Initialize instance fields of this test class */
public void initializeInstance() {
   Integer anInt = Integer.getInteger("numThreads");
   if (anInt != null) {
      numThreadsInThisVM = anInt.intValue();
   }
   numThreadsInClients = TestHelper.getNumThreads();
   isSerialExecution = TestConfig.tab().booleanAt(hydra.Prms.serialExecution);
   highAvailability = TestConfig.tab().booleanAt(DeltaPropagationPrms.highAvailability, false);
   numberOfCycles = TestConfig.tab().intAt(DeltaPropagationPrms.numberOfCycles, 10);
   upperThreshold = TestConfig.tab().intAt(DeltaPropagationPrms.upperThreshold, Integer.MAX_VALUE);
   lowerThreshold = TestConfig.tab().intAt(DeltaPropagationPrms.lowerThreshold, -1);
   numOpsPerTask = TestConfig.tab().intAt(DeltaPropagationPrms.numOpsPerTask, Integer.MAX_VALUE);
   uniqueKeys = TestConfig.tab().booleanAt(DeltaPropagationPrms.useUniqueKeys, false);
   secondsToRun = TestConfig.tab().intAt(DeltaPropagationPrms.secondsToRun, 1800);
   minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, Long.MAX_VALUE);
   if (minTaskGranularitySec == Long.MAX_VALUE)
      minTaskGranularityMS = Long.MAX_VALUE;
   else 
      minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
   Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);
   isBridgeConfiguration = bridgeNames != null;
   regionSnapshot = Collections.synchronizedMap(new HashMap());
   DeltaPropagationBB.getBB().getSharedMap().put(DeltaPropagationBB.RegionSnapshot, regionSnapshot); 
   DeltaPropagationBB.getBB().getSharedMap().put(BadDeltaObject.testCaseKey, new Integer(0)); 
   if (aRegion != null) {
     DeltaObject.cloningEnabled = (aRegion.getAttributes().getCloningEnabled());
   }
   DeltaValueHolder.logCalls = true;
}

/** Create several root regions with various data policies.
 *  Used in test configurations that don't support delta to make sure delta is not called.
 */
public synchronized static void HydraTask_initMultiRegions() {
   if (testInstance == null) {
      testInstance = new DeltaTest();
      CacheHelper.createCache("cache1");
      // See if this test is turning off delta with the delta flag; the flag is on by default.
      // If deltaPropagation is on, then this is a test that turns off delta in some way other
      // than this flag. For now the only other way is to make the scope noAck as noAck is not 
      // supported in delta, but you cannot make PRs noAck; the only way to turn off delta for
      // PR is to turn delta off with the flag, so if delta is turned on, then don't create
      // PR regions (only make regions whose scope can be noack).
      boolean deltaPropagation = TestConfig.tab().booleanAt(GemFirePrms.deltaPropagation, true);
      testInstance.multiRegionList.add(RegionHelper.createRegion("replicateReg"));
      if (DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.ConcurrentLeader) == 1) {
         if (!deltaPropagation) {
            testInstance.multiRegionList.add(RegionHelper.createRegion("partitionedRegAccessor"));
         }
         testInstance.multiRegionList.add(RegionHelper.createRegion("emptyReg"));
      } else {
         if (!deltaPropagation) {
            testInstance.multiRegionList.add(RegionHelper.createRegion("partitionedRegDataStore"));
         }
         testInstance.multiRegionList.add(RegionHelper.createRegion("normalReg"));
      }
      testInstance.multiRegionList.add(RegionHelper.createRegion("persistentRepReg"));
      testInstance.multiRegionList.add(RegionHelper.createRegion("preloadedReg"));
      testInstance.initializeInstance();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = true;
         DeltaTest.registerInterest(testInstance.aRegion);
      }
   }
   testInstance.uniqueKeyIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}
    
/** Create several root regions for clients in a bridge configuration.
 */
public synchronized static void HydraTask_initMultiRegionsForClients() {
   if (testInstance == null) {
      testInstance = new DeltaTest();
      CacheHelper.createCache("cache1");
      testInstance.multiRegionList.add(RegionHelper.createRegion("client_replicateReg"));
      testInstance.multiRegionList.add(RegionHelper.createRegion("client_normalReg"));
      testInstance.multiRegionList.add(RegionHelper.createRegion("client_partitionedReg"));
      testInstance.multiRegionList.add(RegionHelper.createRegion("client_persistentRepReg"));
      testInstance.multiRegionList.add(RegionHelper.createRegion("client_preloadedReg"));
      testInstance.initializeInstance();
      if (testInstance.isBridgeConfiguration) {
         testInstance.isBridgeClient = true;
         DeltaTest.registerInterest(testInstance.aRegion);
      }
   }
   testInstance.uniqueKeyIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}
    
// ========================================================================
// hydra task methods
    
/** Hydra task method for serial round robin tests.
 */
public static void HydraTask_doRROpsAndVerify() {
   if (testInstance.numThreadsInThisVM != 1) {
      throw new TestException("Test configuration problem; serial test should have 1 thread per vm");
   }
   try {
      testInstance.doRROpsAndVerify();
   } finally {
      if (testInstance.isBridgeClient) {
         ClientHelper.release(testInstance.aRegion);
      }
   }
   checkForError();
}

/** Hydra task method for concurrent tests with verification.
 */
public static void HydraTask_doConcOpsAndVerify() {
   testInstance.doConcOpsAndVerify();
   checkForError();
}

/** Hydra task method for concurrent tests with verification.
 */
public static void HydraTask_doConcOps() {
   testInstance.doEntryOperations(testInstance.aRegion);
   checkForError();
}

/** Hydra task method for concurrent tests with verification.
 */
public static void HydraTask_doConcOpsWithMultiRegions() {
   for (int i = 0; i < testInstance.multiRegionList.size(); i++) {
      Log.getLogWriter().info("Beginning operations for " + testInstance.multiRegionList.get(i).getFullPath());
      testInstance.doEntryOperations(testInstance.multiRegionList.get(i));
   }
   checkForError();
}

/** Hydra task method to do random entry operations.
 */
public static void HydraTask_HADoEntryOps() {
   testInstance.HADoEntryOps();
   checkForError();
}

/** Hydra task method to control VMs going down/up one at a time.
 */
public static void HydraTask_HAController() {
   testInstance.HAController();
   checkForError();
}

/** Hydra task method to load the region with N entries, specified by
 *  DeltaPropagationPrms.upperThreshold.
 */
public static void HydraTask_loadBadDeltaToUpperThreshold()  {
   int logIntervalMS = 10000;
   long lastLogTime = System.currentTimeMillis();
   int upperThreshold = TestConfig.tab().intAt(DeltaPropagationPrms.upperThreshold);
   Log.getLogWriter().info("Loading region to size " + upperThreshold + 
          ", current region size is " + testInstance.aRegion.size());
   while (testInstance.aRegion.size() < upperThreshold) {
      if (System.currentTimeMillis() - lastLogTime >= logIntervalMS) {
         Log.getLogWriter().info("Loading region to size " + upperThreshold + 
             ", current region size is " + testInstance.aRegion.size());
         lastLogTime = System.currentTimeMillis();
      }
      Object key = testInstance.getNewKey();
      BadDeltaObject anObj = new BadDeltaObject(
          NameFactory.getCounterForName(key), DeltaObject.EQUAL_VALUES, 0, 1);
      anObj.extra = key;
      testInstance.aRegion.create(key, anObj);
      logPRMembers(testInstance.aRegion, key);
   }
   String aStr = "Finished loadToUpperThreshold, " + testInstance.aRegion.getFullPath() +
          " is size " + testInstance.aRegion.size();
   Log.getLogWriter().info(aStr);
}

/** Hydra task method to load the region with N entries, specified by
 *  DeltaPropagationPrms.upperThreshold.
 */
public static void HydraTask_loadToUpperThreshold()  {
   testInstance.loadToUpperThreshold(testInstance.aRegion);
}

/** Hydra task method to load the region with N entries, specified by
 *  DeltaPropagationPrms.upperThreshold.
 */
public static void HydraTask_evictionTestLoad()  {
   testInstance.evictionTestLoad(testInstance.aRegion);
}

/** Hydra task method to load the regions with N entries, specified by
 *  DeltaPropagationPrms.upperThreshold.
 */
public static void HydraTask_loadMultiRegToUpperThreshold()  {
   int doneCount = 0;
   for (int i = 0; i < testInstance.multiRegionList.size(); i++) {
      try {
         testInstance.loadToUpperThreshold(testInstance.multiRegionList.get(i));
      } catch (StopSchedulingOrder order) {
         Log.getLogWriter().info("Done filling " + testInstance.multiRegionList.get(i).getFullPath());
         doneCount++;
      }
   }
   if (doneCount == testInstance.multiRegionList.size()) {
      throw new StopSchedulingOrder("All regions have been filled");
   }
}

/** Hydra task method to follow HydraTask_loadToUpperThreshold to
 *  get the current state of the region recorded to the BB for
 *  serial tests.
 */
public static void HydraTask_recordLoadToBB()  {
   testInstance.recordLoadToBB();
}

/** Write the number of redundant copies for the pr to the blackboard
 *  by using the hydra param DeltaPropagationPrms.redundantCopies, which can be:
 *     "zero" - Write 0 to the blackboard.
 *     "nonZero" - Randomly choose 1, 2 or 3 and write to the blackboard.
 */
public static void HydraTask_initRedundantCopies() {
   String copies = TestConfig.tab().stringAt(DeltaPropagationPrms.redundantCopies);
   if (copies.equalsIgnoreCase("zero")) {
      DeltaPropagationBB.getBB().getSharedMap().put(redundantCopiesKey, new Integer(0));
   } else if (copies.equalsIgnoreCase("nonZero")) {
      int value = TestConfig.tab().getRandGen().nextInt(1, 3);
      DeltaPropagationBB.getBB().getSharedMap().put(redundantCopiesKey, new Integer(value));
   } else {
      try {
         int value = Integer.valueOf(copies);
         DeltaPropagationBB.getBB().getSharedMap().put(redundantCopiesKey, new Integer(value));
      } catch (NumberFormatException e) {
         throw new TestException("Unknown RecovDelayPrms.redundantCopies setting: " + copies);
      }
   }
}

/** Verify that no delta calls have occurred
 */
public static void HydraTask_verifyNoDeltaCalls() {
   int thisVmId = RemoteTestModule.getMyVmid();
   verifyDeltaCalls(thisVmId, DeltaObserver.getToDeltaKeys(), "to", new ArrayList());
   verifyDeltaCalls(thisVmId, DeltaObserver.getFromDeltaKeys(), "from", new ArrayList());
   verifyDeltaCalls(thisVmId, DeltaObserver.getHasDeltaKeys(), "has", new ArrayList());
}

/** Hydra task method for serial tests with bad delta implementations.
 */
public static void HydraTask_badDeltaController() {
   logExecutionNumber();
   long exeNum = DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.ExecutionNumber);
   int specificTestCase = TestConfig.tab().intAt(DeltaPropagationPrms.testCase, -1);
   int firstTestCase = 0;
   int lastTestCase = 0;
   if (specificTestCase != -1) {
      firstTestCase = specificTestCase;
      lastTestCase = specificTestCase;
   } else {
      firstTestCase = 1;
      lastTestCase = BadDeltaObject.numberOfBadDeltaTestCases;
   } 
   int currentTestCase = 0;
   if (exeNum == 1) {
      DeltaPropagationBB.getBB().getSharedMap().put("BadDeltaStartVmID", RemoteTestModule.getMyVmid());
      currentTestCase = firstTestCase;
   } else {
      currentTestCase = ((Integer)(DeltaPropagationBB.getBB().getSharedMap().get(BadDeltaObject.testCaseKey))).intValue();
      int startingVmId = ((Integer)DeltaPropagationBB.getBB().getSharedMap().get("BadDeltaStartVmID")).intValue();
      if (startingVmId == RemoteTestModule.getMyVmid()) { // test has gone all the way around
         currentTestCase++;
         if (currentTestCase > lastTestCase) {
            throw new StopSchedulingOrder("Test has completed");
         }
      }
   }
   if (currentTestCase == BadDeltaObject.fromDeltaThrowsException) {
     if ((!testInstance.isBridgeConfiguration) || (testInstance.isBridgeConfiguration && !testInstance.isBridgeClient)) {
       // March 2013, do not execute the validation; this is to avoid bug 40837 which has been determined to
       // be a bug we will not fix
       return;
     }
   }
   testInstance.badDeltaController(currentTestCase);
}

public static void HydraTask_doMixedTest() {
  Iterator it = testInstance.aRegion.keySet().iterator();
  while (it.hasNext()) {
    Object key = it.next();
    Object value = testInstance.aRegion.get(key);
    if (value instanceof Delta) {
      BaseValueHolder nonDeltaObject = new ValueHolder();
      nonDeltaObject.myValue = "non-delta object for key " + key;
      Log.getLogWriter().info("Putting key " + key + ", value is " + nonDeltaObject);
      testInstance.aRegion.put(key, nonDeltaObject);
    } else {// previous object is not a Delta object; put a delta object then send a delta
      DeltaObject anObj = new DeltaObject(
          NameFactory.getCounterForName(key), DeltaObject.EQUAL_VALUES, 0, 1);
      anObj.extra = key;
      testInstance.modifyDeltaObject(anObj);
      Log.getLogWriter().info("Putting key " + key + ", value is " + anObj.toStringFull());
      try {
        testInstance.aRegion.put(key, anObj);
      } catch (ClassCastException e) {
        Log.getLogWriter().info("Caught expected " + TestHelper.getStackTrace(e) + " for key " + key);
      } catch (ServerOperationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ClassCastException) {
          Log.getLogWriter().info("Caught expected " + TestHelper.getStackTrace(e) + " + for key " + key);
        } else {
          Log.getLogWriter().info("Throwing...");
          throw e;
        }
      }
    }
  }
}

  /** Hydra task method for eviction tests with delta.
   */
  public static void HydraTask_doSerialRREvictionTest() {
    testInstance.doSerialRREvictionTest();
  }
  
  /** Hydra task method to check the memLRU levels in a vm.
   */
  public static void HydraTask_checkMemLRULevelForPR() {
    testInstance.checkMemLRULevelForPR();
  }

  /** Hydra task method to check the overages found because of
   *  bug 41952.
   */
  public static void HydraTask_checkOverageHistory() {
    testInstance.checkOverageHistory();
  }

// ========================================================================
// test methods

/** Do random operations and verification for serial round robin test.
 *  A test cycle takes to round robin rounds to complete.
 *  First round of cycle:
 *    - If this is the first task in the round, then do random operations
 *      and record region state and delta information to the blackboard. 
 *    - If this is not the first in the round, then write the delta 
 *      information from this vm collected during the ops phase to the
 *      blackboard.
 *  Second round of cycle:
 *    - verify region contents and delta behavior
 *    - If last in this round, become first in the round for the next 
 *      cycle and do ops, etc.
 */
protected void doRROpsAndVerify()  {
   logExecutionNumber();
   long roundPosition = DeltaPropagationBB.getBB().getSharedCounters().
      incrementAndRead(DeltaPropagationBB.RoundPosition);
   Log.getLogWriter().info("In doRROpsAndVerify, roundPosition is " + roundPosition);
   regionSnapshot = (Map)(DeltaPropagationBB.getBB().getSharedMap().get(
      DeltaPropagationBB.RegionSnapshot));
   long roundNumber = DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.RoundNumber);
   if (roundPosition == numThreadsInClients) { // this is the last in the round
      if ((roundNumber % 2) == 1) { // round to do ops and write to bb
         Log.getLogWriter().info("In doRROpsAndVerify, last in round, round to write to bb");
         writeDeltaInfoToBlackboard();
         DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.RoundPosition);
      } else {
         Log.getLogWriter().info("In doRROpsAndVerify, last in round, round to verify (then become first in round and do ops)");
         verifyRegionContents(regionSnapshot);
         verifyDeltaBehavior();   

         // now become the first in the round 
         DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.RoundPosition);
         roundPosition = DeltaPropagationBB.getBB().getSharedCounters().
            incrementAndRead(DeltaPropagationBB.RoundPosition);
      }
   }

   if (roundPosition == 1) { 
      roundNumber = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.RoundNumber);
      Log.getLogWriter().info("In doRROpsAndVerify, first in round, round number " + roundNumber);
      if ((roundNumber % 2) == 1) { // round to do ops and write to bb
         int cycleNumber = (int)((roundNumber+1) / 2); // this is the cycle number we are just starting
         Log.getLogWriter().info("Starting cycle number: " + cycleNumber);
         if (cycleNumber > numberOfCycles) { 
            throw new StopSchedulingOrder("Completed " + (cycleNumber-1) + " cycles");
         }
         Log.getLogWriter().info("Round to do ops and write to blackboard");

         // init for ops
         clear();

         // ops
         doEntryOperations(aRegion);
         if (isBridgeConfiguration) { 
            // wait for 30 seconds of client silence to allow everything to be pushed to clients
            util.SilenceListener.waitForSilence(30, 5000);
         }

         // write the expected region state to the blackboard
         Log.getLogWriter().info("Writing regionSnapshot to blackboard, snapshot size is " + 
             regionSnapshot.size() + ": " + regionSnapshot);
         DeltaPropagationBB.getBB().getSharedMap().put(DeltaPropagationBB.RegionSnapshot, regionSnapshot); 
         writeDeltaInfoToBlackboard();
      } else {
         Log.getLogWriter().info("Round to verify");
         verifyRegionContents(regionSnapshot);
         verifyDeltaBehavior();
         clear();
      }
   } else if (roundPosition != numThreadsInClients) { // neither first nor last
      if ((roundNumber % 2) == 1) { // round write to bb
         Log.getLogWriter().info("In doRROpsAndVerify, neither first nor last, round to write to bb");
         writeDeltaInfoToBlackboard();
      } else { // round to verify
         Log.getLogWriter().info("In doRROpsAndVerify, neither first nor last, round to verify");
         regionSnapshot = (Map)(DeltaPropagationBB.getBB().getSharedMap().get(
                          DeltaPropagationBB.RegionSnapshot));
         verifyRegionContents(regionSnapshot);
         verifyDeltaBehavior();
         clear();
      }
   }
}

/** Do random operations and verification for concurrent tests.
 *  The task starts up and all threads concurrently do random
 *  operations. The operations run for maxTaskGranularitySec or
 *  numOpsPerTask, depending the on the defined hydra parameters, 
 *  then all threads will pause. During the pause, one thread goes
 *  first and writes the regionSnapshot to the blackboard. Then
 *  all other client threads read the blackboard and verify.
 *  After all threads are done with verification, the task ends.
 */
protected void doConcOpsAndVerify()  {
   concVerifyCoordinator = new MethodCoordinator(DeltaTest.class.getName(), "concVerify");
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.ConcurrentLeader);
   clear();
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.SyncUp);
   // wait for all threads to be ready to do this task, then do random ops
   long counter = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.ReadyToBegin);
   if (counter == 1) {
      logExecutionNumber();
   }
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                             "DeltaPropagationBB.ReadyToBegin", 
                             DeltaPropagationBB.ReadyToBegin, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             1000);
   checkForLastIteration();

   Log.getLogWriter().info("Zeroing ShapshotWritten");
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.SnapshotWritten);

   // do random operations 
   doEntryOperations(aRegion);

   // wait for all threads to pause, then do the verify
   Log.getLogWriter().info("Zeroing FinishedVerify");
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.FinishedVerify);
   DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.Pausing);
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                             "DeltaPropagationBB.Pausing", 
                             DeltaPropagationBB.Pausing, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   Log.getLogWriter().info("Zeroing ReadyToBegin");
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.ReadyToBegin);

   // in a bridge configuration, wait for the message queues to finish pushing
   // to the clients
   if (isBridgeConfiguration) {
      // wait for 30 seconds of client silence
      SilenceListener.waitForSilence(30, 5000);
   }

   // write delta information to blackboard
   writeDeltaInfoToBlackboard();
   synchronized (this) {
      DeltaPropagationBB.getBB().getSharedMap().put(regionSnapshotKey + 
         RemoteTestModule.getMyVmid(), regionSnapshot);
   }
   DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.SyncUp);
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                             "DeltaPropagationBB.SyncUp", 
                             DeltaPropagationBB.SyncUp, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);

   // do verification
   concVerifyCoordinator.executeOnce(this, new Object[0]);
   if (!concVerifyCoordinator.methodWasExecuted()) {
      throw new TestException("Test problem: concVerify did not execute");
   }
   DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.FinishedVerify);

   // wait for everybody to finish verify, then exit
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                             "DeltaPropagationBB.FinishedVerify", 
                             DeltaPropagationBB.FinishedVerify, 
                             numThreadsInClients,
                             true, 
                             -1,
                             5000);
   Log.getLogWriter().info("Zeroing Pausing");
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.Pausing);

   counter = DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.TimeToStop);
   if (counter >= 1)
      throw new StopSchedulingOrder("Num executions is " + 
            DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.ExecutionNumber));
}

/** While other threads are doing ops, stop or close the region of servers 
 *  (not clients), reinstate the servers/regions, pause and verify. This 
 *  method coordinates the use of blackboard counters with HADoEntryOps 
 *  for pausing.
 */
protected void HAController() {
   logExecutionNumber();
   concVerifyCoordinator = new MethodCoordinator(DeltaTest.class.getName(), "concVerify");
   checkForLastIteration();
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.ExceptionCounter);
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.ConcurrentLeader);
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.Reinitialized);

   // if this vm is a client, then stop/start some servers
   // if this vm is a server, then either stop/start other servers OR
   //    either close the cache or disconnect
   boolean stopServers = isBridgeClient ||
                         ((!isBridgeClient) && TestConfig.tab().getRandGen().nextInt(1,100) <= 60);
   if (stopServers) {  
      int numVMsToStop = TestConfig.tab().intAt(StopStartPrms.numVMsToStop);
      Object[] tmpArr = StopStartVMs.getOtherVMs(numVMsToStop, "bridge");
      List vmList = (List)(tmpArr[0]);
      List stopModeList = (List)(tmpArr[1]);
      StopStartVMs.stopStartVMs(vmList, stopModeList);
   } else { // either close the cache or disconnect
      if (TestConfig.tab().getRandGen().nextBoolean()) { // close the cache for this VM
         Log.getLogWriter().info("Closing the cache...");
         cacheIsClosed = true;
         CacheHelper.closeCache();
      } else { // disconnect
         Log.getLogWriter().info("Disconnecting from the distributed system...");
         disconnected = true;
         DistributedSystemHelper.disconnect();
      }
      // wait for all threads in this VM to get the appropriate exception
      TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                                "DeltaPropagationBB.ExceptionCounter", 
                                DeltaPropagationBB.ExceptionCounter, 
                                numThreadsInThisVM-1, 
                                true, 
                                -1,
                                1000);
      Log.getLogWriter().info("Recreating the region...");
      testInstance.aRegion = testInstance.initializeRegion("serverRegion");
      Log.getLogWriter().info("Done recreating the region...");
      BridgeHelper.startBridgeServer("bridge");
      DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.Reinitialized);
   }
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.SnapshotWritten);
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.FinishedVerify);
   cacheIsClosed = false;
   disconnected = false;

   // now get all vms to pause for verification
   DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.Pausing);
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                             "DeltaPropagationBB.Pausing", 
                             DeltaPropagationBB.Pausing, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.SyncUp);

   if (isBridgeConfiguration) { 
      // wait for 30 seconds of client silence to allow everything to be pushed to clients
      SilenceListener.waitForSilence(30, 5000);
   }
   concVerifyCoordinator.executeOnce(this, new Object[0]);
   if (!concVerifyCoordinator.methodWasExecuted()) {
      throw new TestException("Test problem: concVerify did not execute");
   }

   // wait for everybody to finish verify
   DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.FinishedVerify);
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                             "DeltaPropagationBB.FinishedVerify", 
                             DeltaPropagationBB.FinishedVerify, 
                             numThreadsInClients, 
                             true, 
                             -1,
                             5000);
   DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.Pausing);

   // sync up with the HADoEntryOps threads; this will avoid a new
   // HAController task getting scheduled before all the HADoEntryOPs
   // threads have zeroed any counters after the verify
   DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.SyncUp);
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(),
                             "DeltaPropagationBB.SyncUp",
                             DeltaPropagationBB.SyncUp,
                             numThreadsInClients,
                             true,
                             -1,
                             1000);

   // see if it's time to stop the test
   long counter = DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.TimeToStop);
   if (counter >= 1)
      throw new StopSchedulingOrder("Num HAController executions is " + 
            DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.ExecutionNumber));
}

/** Do entry ops and handle disconnects or cache closes. This coordinates counters
 *  with HAController to allow for verification.
 */
protected void HADoEntryOps() {
   checkForLastIteration();
   // disconnect can cause cacheClosedException if the thread is accessing the cache
   try {
      testInstance.doEntryOperations(testInstance.aRegion);
      if (DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.Pausing) > 0) { // we are pausing
         DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.Pausing);
         TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                                   "DeltaPropagationBB.Pausing", 
                                   DeltaPropagationBB.Pausing, 
                                   numThreadsInClients, 
                                   true, 
                                   -1,
                                   5000);
         if (isBridgeConfiguration) { 
            // wait for 30 seconds of client silence to allow everything to be pushed to clients
            SilenceListener.waitForSilence(30, 5000);
         }
         concVerifyCoordinator.executeOnce(this, new Object[0]);
         if (!concVerifyCoordinator.methodWasExecuted()) {
            throw new TestException("Test problem: concVerify did not execute");
         }

         // wait for everybody to finish verify
         DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.FinishedVerify);
         TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                                   "DeltaPropagationBB.FinishedVerify", 
                                   DeltaPropagationBB.FinishedVerify, 
                                   numThreadsInClients, 
                                   true, 
                                   -1,
                                   5000);
         DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.Pausing);
         concVerifyCoordinator = new MethodCoordinator(DeltaTest.class.getName(), "concVerify");

         // sync up with the HAController thread; this will ensure that
         // the above zeroing of counters doesn't happen before a new
         // invocation of HAController
         DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.SyncUp);
         TestHelper.waitForCounter(DeltaPropagationBB.getBB(),
                                   "DeltaPropagationBB.SyncUp",
                                   DeltaPropagationBB.SyncUp,
                                   numThreadsInClients,
                                   true,
                                   -1,
                                   1000);
      }
   } catch (Exception e) {
      handleException(e);
   }
}

/** Do random entry operations on the given region ending either with
 *  minTaskGranularityMS or numOpsPerTask.
 *  Uses DeltaPropagationPrms.entryOperations to determine the operations to execute.
 */
protected void doEntryOperations(Region aReg)  {
   Log.getLogWriter().info("In doEntryOperations with " + aReg.getFullPath());
   long startTime = System.currentTimeMillis();
   int numOps = 0;

   // useTransactions() defaults 
   boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
   boolean rolledback;

   do {
      int whichOp = 0;
      if (isBridgeConfiguration) {
         if (isBridgeClient) {
            whichOp = getOperation(DeltaPropagationPrms.clientEntryOperations);
         } else {
            whichOp = getOperation(DeltaPropagationPrms.serverEntryOperations);
         }
      } else { // for peer tests
         whichOp = getOperation(DeltaPropagationPrms.entryOperations);
      }
      int size = aReg.size();
      if (size >= upperThreshold) {
         whichOp = getOperation(DeltaPropagationPrms.upperThresholdOperations);
      } else if (size <= lowerThreshold) {
         whichOp = getOperation(DeltaPropagationPrms.lowerThresholdOperations);
      }

      rolledback = false;
      if (useTransactions) {
        TxHelper.begin();
      }

      try {
         switch (whichOp) {
            case ENTRY_ADD_OPERATION:
               addEntry(aReg);
               break;
            case ENTRY_INVALIDATE_OPERATION:
               invalidateEntry(aReg, false);
               break;
            case ENTRY_DESTROY_OPERATION:
               destroyEntry(aReg, false);
               break;
            case ENTRY_UPDATE_OPERATION:
               updateEntry(aReg);
               break;
            case ENTRY_GET_OPERATION:
                  getKey(aReg);
               break;
            case ENTRY_GET_NEW_OPERATION:
               getNewKey(aReg);
               break;
            case ENTRY_LOCAL_INVALIDATE_OPERATION:
               invalidateEntry(aReg, true);
               break;
            case ENTRY_LOCAL_DESTROY_OPERATION:
               destroyEntry(aReg, true);
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
          throw new TestException("Unexpected Exception " + e + ".  " + TestHelper.getStackTrace(e));
        } else {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
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
          Log.getLogWriter().info("Caught TransactionDataNodeHasDepartedException.  Expected with concurrent execution, continuing test.");
        } catch (TransactionDataRebalancedException e) {
          Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
        } catch (TransactionInDoubtException e) {
          Log.getLogWriter().info("Caught TransactionInDoubtException.  Expected with concurrent execution, continuing test.");
        } catch (ConflictException e) {
          // can occur with concurrent execution
          Log.getLogWriter().info("Caught ConflictException. Expected with concurrent execution, continuing test.");
        }
      }

      numOps++;
      Log.getLogWriter().info("Completed op " + numOps + " for this task, region size is " + aReg.size());
      checkForError();
   } while ((System.currentTimeMillis() - startTime < minTaskGranularityMS) &&
            (numOps < numOpsPerTask));
   Log.getLogWriter().info("Done in doEntryOperations with " + aReg.getFullPath() + ", completed " +
       numOps + " ops in " + (System.currentTimeMillis() - startTime) + " millis");
}
        
/** Load the region with N entries, specified by DeltaPropagationPrms.upperThreshold.
 *
 */
protected void loadToUpperThreshold(Region aReg)  {
   int logIntervalMS = 10000;
   long startTime = System.currentTimeMillis();
   long lastLogTime = System.currentTimeMillis();
   Log.getLogWriter().info("Loading region to size " + upperThreshold + 
          ", current region size is " + aReg.size());
   while (aReg.size() < upperThreshold) {
      if (System.currentTimeMillis() - lastLogTime >= logIntervalMS) {
         Log.getLogWriter().info("Loading region to size " + upperThreshold + 
             ", current region size is " + aReg.size());
         lastLogTime = System.currentTimeMillis();
      }
      addEntry(aReg);
      long numUpdates = DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.NUM_UPDATE);
      if (numUpdates > 0) {
         throw new TestException("After loading with creates, test detected " + numUpdates +
             " update events, but expected only create events");
      }
      if (System.currentTimeMillis() - startTime >= minTaskGranularityMS) {
         break;
      }
   }
   
   HydraTask_verifyNoDeltaCalls(); // only creates have occured, no updates, so no delta calls
   if (aReg.size() >= upperThreshold) {
      String aStr = "Finished loadToUpperThreshold, " + aReg.getFullPath() +
          " is size " + aReg.size();
      Log.getLogWriter().info(aStr);
      throw new StopSchedulingTaskOnClientOrder(aStr);
   }
}

/** Load the region with N entries, specified by DeltaPropagationPrms.upperThreshold.
 *
 */
protected void evictionTestLoad(Region aReg)  {
   int logIntervalMS = 10000;
   long startTime = System.currentTimeMillis();
   long lastLogTime = System.currentTimeMillis();
   Log.getLogWriter().info("Loading region to size " + upperThreshold + 
          ", current region size is " + aReg.size());
   while (aReg.size() < upperThreshold) {
      if (System.currentTimeMillis() - lastLogTime >= logIntervalMS) {
         Log.getLogWriter().info("Loading region to size " + upperThreshold + 
             ", current region size is " + aReg.size());
         lastLogTime = System.currentTimeMillis();
      }
      addNewValue();
      if (System.currentTimeMillis() - startTime >= minTaskGranularityMS) {
         break;
      }
   }
   if (aReg.size() >= upperThreshold) {
      String aStr = "Finished loadToUpperThreshold, " + aReg.getFullPath() +
          " is size " + aReg.size();
      Log.getLogWriter().info(aStr);
      throw new StopSchedulingTaskOnClientOrder(aStr);
   }
}

/** Record the state of the region to the blackbloard for serial tests.
 *
 */
protected void recordLoadToBB()  {
   long concurrentLeader = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.ConcurrentLeader);
   if (concurrentLeader == 1) {
      if (isBridgeConfiguration) { 
         // wait for 30 seconds of client silence to allow everything to be pushed to clients
         util.SilenceListener.waitForSilence(30, 5000);
      }
      Log.getLogWriter().info("Recording region to blackbloard...");
      regionSnapshot = new HashMap();
      Iterator it = aRegion.keys().iterator();
      while (it.hasNext()) {
         Object key = it.next();
         DeltaObject value = (DeltaObject)(aRegion.get(key));
         regionSnapshot.put(key, value.copy());      
      }
      Log.getLogWriter().info("Writing regionSnapshot to blackboard, snapshot size is " + regionSnapshot.size() + ": " + regionSnapshot);
      DeltaPropagationBB.getBB().getSharedMap().put(DeltaPropagationBB.RegionSnapshot, regionSnapshot); 
   }
}

/** Add a new entry to the given region.
 *
 *  @param aReg The region to use for adding a new entry.
 *
 *  @returns The key that was added.
 */
protected Object addEntry(Region aReg)  {
   Object key = getNewKey();
   DeltaObject anObj = new DeltaObject(
       NameFactory.getCounterForName(key), DeltaObject.EQUAL_VALUES, 0, 1);
   anObj.extra = key;
   String callback = createCallbackPrefix + ProcessMgr.getProcessId();
   try {
      Log.getLogWriter().info("operation for " + key + ", addEntry: calling create for key " + key + ", object " +
         anObj.toStringFull() + " cacheWriterParam is " + callback + ", region is " + 
         aReg.getFullPath());
      aReg.create(key, anObj, callback);
      logPRMembers(aReg, key);
      Log.getLogWriter().info("operation for " + key + ", addEntry: done creating key " + key);
   } catch (EntryExistsException e) {
      if (isSerialExecution || uniqueKeys) { 
         // cannot get this exception; nobody else can have this key
         throw new TestException(TestHelper.getStackTrace(e));
      } else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         // in concurrent execution, somebody could have updated this key causing it to exist
      }
   }

   // validation
   if (isSerialExecution || uniqueKeys) {
      // record the current state
      regionSnapshot.put(key, anObj.copy());
   }
   return key;
}
    
/** Invalidate an entry in the given region.
 *
 *  @param aReg The region to use for invalidating an entry.
 *  @param isLocalInvalidate True if the invalidate should be local, false otherwise.
 */
protected void invalidateEntry(Region aReg, boolean isLocalInvalidate) {
   int beforeSize = aReg.size();
   Object key = getExistingKey(aReg, uniqueKeys, numThreadsInClients);
   if (key == null) {
      if (isSerialExecution && (beforeSize != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + beforeSize);
      Log.getLogWriter().info("invalidateEntry: No keys in region");
      return;
   }
   boolean containsKey = aReg.containsKey(key);
   boolean containsValueForKey = aReg.containsValueForKey(key);
   Log.getLogWriter().info("containsKey for " + key + ": " + containsKey);
   Log.getLogWriter().info("containsValueForKey for " + key + ": " + containsValueForKey);
   try {
      String callback = invalidateCallbackPrefix + ProcessMgr.getProcessId();
      if (isLocalInvalidate) { // do a local invalidate
         if (TestConfig.tab().getRandGen().nextBoolean()) { // local invalidate with callback
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: local invalidate for " + key + " callback is " + callback);
            aReg.localInvalidate(key, callback);
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: done with local invalidate for " + key);
         } else { // local invalidate without callback
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: local invalidate for " + key);
            aReg.localInvalidate(key);
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: done with local invalidate for " + key);
         }
      } else { // do a distributed invalidate
         if (TestConfig.tab().getRandGen().nextBoolean()) { // invalidate with callback
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: invalidating key " + key + " callback is " + callback);
            aReg.invalidate(key, callback);
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: done invalidating key " + key);
         } else { // invalidate without callback
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: invalidating key " + key);
            aReg.invalidate(key);
            Log.getLogWriter().info("operation for " + key + ", invalidateEntry: done invalidating key " + key);
         }
      }

      // validation
      if (isSerialExecution || uniqueKeys) {
         // record the current state
         regionSnapshot.put(key, null);
      }
   } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      if (isSerialExecution || uniqueKeys)
         throw new TestException(TestHelper.getStackTrace(e));
      else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         return;
      }
   }
}
    
/** Destroy an entry in the given region.
 *
 *  @param aReg The region to use for destroying an entry.
 *  @param isLocalDestroy True if the destroy should be local, false otherwise.
 */
protected void destroyEntry(Region aReg, boolean isLocalDestroy) {
   Object key = getExistingKey(aReg, uniqueKeys, numThreadsInClients);
   if (key == null) {
      int size = aReg.size();
      if (isSerialExecution && (size != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + size);
      Log.getLogWriter().info("destroyEntry: No keys in region");
      return;
   }
   try {
      String callback = destroyCallbackPrefix + ProcessMgr.getProcessId();
      if (isLocalDestroy) { // do a local destroy
         if (TestConfig.tab().getRandGen().nextBoolean()) { // local destroy with callback
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: local destroy for " + key + " callback is " + callback);
            aReg.localDestroy(key, callback);
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: done with local destroy for " + key);
         } else { // local destroy without callback
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: local destroy for " + key);
            aReg.localDestroy(key);
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: done with local destroy for " + key);
         }
      } else { // do a distributed destroy
         if (TestConfig.tab().getRandGen().nextBoolean()) { // destroy with callback
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: destroying key " + key + " callback is " + callback);
            aReg.destroy(key, callback);
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: done destroying key " + key);
         } else { // destroy without callback
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: destroying key " + key);
            aReg.destroy(key);
            Log.getLogWriter().info("operation for " + key + ", destroyEntry: done destroying key " + key);
         }
      }

      // validation
      if (isSerialExecution || uniqueKeys) {
         // record the current state
         regionSnapshot.remove(key);
      }
   } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      if (isSerialExecution || uniqueKeys)
         throw new TestException(TestHelper.getStackTrace(e));
      else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         return;
      }
   }
}
    
/** Update an existing entry in the given region. If there are
 *  no available keys in the region, then this is a noop.
 *
 *  @param aReg The region to use for updating an entry.
 */
protected void updateEntry(Region aReg)  {
   Object key = getExistingKey(aReg, uniqueKeys, numThreadsInClients);
   if (key == null) {
      int size = aReg.size();
      if (isSerialExecution && (size != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + size);
      Log.getLogWriter().info("updateEntry: No keys in region");
      return;
   }
   DeltaObject previousObj = (DeltaObject)(aReg.get(key));
   DeltaObject anObj = previousObj;
   String callback = updateCallbackPrefix + ProcessMgr.getProcessId();
   if (previousObj == null) { // entry was previously invalidated
      Log.getLogWriter().info("Unable to modify value for key " + key + " because it was previously invalidated");
      anObj = new DeltaObject(
         NameFactory.getCounterForName(key), DeltaObject.EQUAL_VALUES, 0, 1);
      anObj.extra = key; // embed key into the object for later validation
      callback = fullDistribution;
      trackUpdate(aReg, key, false);
   } else { // modify the previousObj
      boolean deltaBooleansSet = modifyDeltaObject(anObj);
      trackUpdate(aReg, key, deltaBooleansSet);
      if (!deltaBooleansSet) {
         callback = fullDistribution;
      }
   }
   Log.getLogWriter().info("operation for " + key + ", updateEntry: replacing key " + key + " with " +
      anObj.toStringFull() + ", callback is " + callback);
   Object returnVal = aReg.put(key, anObj, callback);
   Log.getLogWriter().info("operation for " + key + ", updateEntry: Done with call to put (update), returnVal is " + returnVal);

   // validation
   if (isSerialExecution || uniqueKeys) {
      // record the current state
      regionSnapshot.put(key, anObj.copy());
   }
}
    
/** Get an existing key in the given region if one is available,
 *  otherwise get a new key. 
 *
 *  @param aReg The region to use for getting an entry.
 */
protected void getKey(Region aReg)  {
   Object key = getExistingKey(aReg, uniqueKeys, numThreadsInClients);
   if (key == null) { // no existing keys; get a new key then
      int size = aReg.size();
      if (isSerialExecution && (size != 0))
         throw new TestException("getExistingKey returned " + key + ", but region size is " + size);
      getNewKey(aReg);
      return;
   }
   String callback = getCallbackPrefix + ProcessMgr.getProcessId();
   DeltaObject anObj;
   try {
      if (TestConfig.tab().getRandGen().nextBoolean()) { // get with callback
         Log.getLogWriter().info("operation for " + key + ", getKey: getting key " + key + ", callback is " + callback);
         anObj = (DeltaObject)(aReg.get(key, callback));
         Log.getLogWriter().info("operation for " + key + ", getKey: got value for key " + key + ": " + ((anObj == null) ? "null" : anObj.toStringFull()));
      } else { // get without callback
         Log.getLogWriter().info("operation for " + key + ", getKey: getting key " + key);
         anObj = (DeltaObject)(aReg.get(key));
         Log.getLogWriter().info("operation for " + key + ", getKey: got value for key " + key + ": " + ((anObj == null) ? "null" : anObj.toStringFull()));
      }

      // validation 
      if (isSerialExecution || uniqueKeys) { 
         // record the current state
         // in case the get works like a put because there is a cacheLoader
         if (anObj == null)
            regionSnapshot.put(key, null);
         else
            regionSnapshot.put(key, anObj.copy());

      }
   } catch (CacheLoaderException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
}
    
/** Get a new key int the given region.
 *
 *  @param aReg The region to use for getting an entry.
 */
protected void getNewKey(Region aReg)  {
   Object key = getNewKey();
   String callback = getCallbackPrefix + ProcessMgr.getProcessId();
   DeltaObject anObj;
   try {
      if (TestConfig.tab().getRandGen().nextBoolean()) { // get with callback
         Log.getLogWriter().info("operation for " + key + ", getNewKey: getting new key " + key + ", callback is " + callback);
         anObj = (DeltaObject)(aReg.get(key, callback));
      } else { // get without callback
         Log.getLogWriter().info("operation for " + key + ", getNewKey: getting new key " + key);
         anObj = (DeltaObject)(aReg.get(key));
      }
      if (anObj == null) {
         Log.getLogWriter().info("operation for " + key + ", getNewKey: done getting value for new key " + key + ": " + anObj);
      } else {
         Log.getLogWriter().info("operation for " + key + ", getNewKey: done getting value for new key " + key + ": " + anObj.toStringFull());
      }

      // validation 
      if (isSerialExecution || uniqueKeys) { 
         // record the current state in case the get works like a put because there is a cacheLoader
         if (anObj == null)
            regionSnapshot.put(key, null);
         else
            regionSnapshot.put(key, anObj.copy());
      }
   } catch (CacheLoaderException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
}
    
/** Return a new key, never before used in the test.
 */
protected Object getNewKey() {
   if (uniqueKeys) {
      int anInt = ((Integer)(uniqueKeyIndex.get())).intValue(); 
      anInt += numThreadsInClients;
      uniqueKeyIndex.set(new Integer(anInt));
      return NameFactory.getObjectNameForCounter(anInt);
   } else {
      return NameFactory.getNextPositiveObjectName();
   }
}
    
/** Log the execution number of this serial task.
 */
static protected void logExecutionNumber() {
   long exeNum = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.ExecutionNumber);
   Log.getLogWriter().info("Beginning task with execution number " + exeNum);
}
    
/** Register interest with ALL_KEYS, and InterestPolicyResult = KEYS_VALUES
 *  which is equivalent to a full GII.
 *
 *  @param aRegion The region to register interest on.
 */
protected static void registerInterest(Region aRegion) {
   Log.getLogWriter().info("Calling registerInterest for all keys, result interest policy KEYS_VALUES");
   aRegion.registerInterest("ALL_KEYS", InterestResultPolicy.KEYS_VALUES);
   Log.getLogWriter().info("Done calling registerInterest for all keys, " +
       "result interest policy KEYS_VALUES, " + aRegion.getFullPath() +
       " size is " + aRegion.size());
}

/** Test the given bad delta test case.
 */
protected void badDeltaController(int testCase) {
   Object key = getKeyForBadDeltaTest();
   DeltaPropagationBB.getBB().getSharedMap().put(BadDeltaObject.testCaseKey, new Integer(testCase));
   Log.getLogWriter().info("Test intends to cause bad delta: " + BadDeltaObject.testCaseToString(testCase));

   BadDeltaObject originalObj = (BadDeltaObject)(aRegion.get(key));
   Log.getLogWriter().info("For key " + key + ", get returned " + originalObj.toStringFull());
   BadDeltaObject anObj = (BadDeltaObject)(originalObj.copy());
   Log.getLogWriter().info("Modifying BadDeltaObject " + anObj.toStringFull());
   anObj.reset();
   anObj.aPrimitiveLong = anObj.aPrimitiveLong+1;   
   anObj.aPrimitiveLongChanged = true;
   Log.getLogWriter().info("Putting " + key + " with modified BadDeltaObject, BadDeltaObject.aPrimitiveLong modified, object is now " + anObj.toStringFull());
   switch (testCase) {
   
      // toDelta/fromDelta throws IOException 
      case BadDeltaObject.toDeltaThrowsIOException: 
      case BadDeltaObject.fromDeltaThrowsIOException: {
         try {
            testInstance.aRegion.put(key, anObj);
            handleBadDeltaSuccessfulUpdate(testCase, IOException.class, key, anObj);
            verifyValue(key, anObj);
         } catch (Throwable error) { 
            handleBadDeltaException(IOException.class, error, (testCase == BadDeltaObject.fromDeltaThrowsIOException));
            verifyValue(key, originalObj);
         }
         MasterController.sleepForMs(3500); // give a little time for any unexpected distribution of events
         verifyNoUpdates();
      }
      break;
   
      // hasDelta/toDelta/fromDelta throws Exception (specifically ArrayIndexOutOfBoundsException)
      case BadDeltaObject.hasDeltaThrowsException: 
      case BadDeltaObject.toDeltaThrowsException: 
      case BadDeltaObject.fromDeltaThrowsException: {
         try {
            testInstance.aRegion.put(key, anObj);
            handleBadDeltaSuccessfulUpdate(testCase, ArrayIndexOutOfBoundsException.class, key, anObj);
            verifyValue(key, anObj);
         } catch (Throwable error) { 
            handleBadDeltaException(ArrayIndexOutOfBoundsException.class, error, (testCase == BadDeltaObject.fromDeltaThrowsException));
            verifyValue(key, originalObj);
         }
         MasterController.sleepForMs(3500); // give a little time for any unexpected distribution of events
         verifyNoUpdates();
      }
      break;

      // hasDelta/toDelta/fromDelta throws Error (specifically AssertionError)
      case BadDeltaObject.hasDeltaThrowsError: 
      case BadDeltaObject.toDeltaThrowsError:
      case BadDeltaObject.fromDeltaThrowsError: {
         try {
            testInstance.aRegion.put(key, anObj);
            handleBadDeltaSuccessfulUpdate(testCase, AssertionError.class, key, anObj);
            verifyValue(key, anObj);
         } catch (Throwable error) { 
            handleBadDeltaException(AssertionError.class, error, (testCase == BadDeltaObject.fromDeltaThrowsError));
            String errStr = TestHelper.getStackTrace(error);
            if ((errStr.indexOf("Causing an error in hasDelta") >= 0) ||
                (errStr.indexOf("Causing an error in toDelta") >= 0) ||
                (errStr.indexOf("Causing an error in fromDelta") >= 0)) { // expected
               // OK
            } else {
               throw new TestException("Got " + error + " but expected AssertionError thrown from a delta call; " +
                         TestHelper.getStackTrace(error));
            }
            verifyValue(key, originalObj);
         }
         MasterController.sleepForMs(3500); // give a little time for any unexpected distribution of events
         verifyNoUpdates();
      }
      break;

      // fromDelta gets errors while trying to read
      case BadDeltaObject.fromDeltaReadTooMuch:
      case BadDeltaObject.toDeltaWriteNothing: {
         try {
            testInstance.aRegion.put(key, anObj);
            handleBadDeltaSuccessfulUpdate(testCase, EOFException.class, key, anObj);
            verifyValue(key, anObj);
         } catch (Throwable error) {
            handleBadDeltaException(EOFException.class, error, true);
            verifyValue(key, originalObj);
         }
         MasterController.sleepForMs(3500); // give a little time for any unexpected distribution of events
         verifyNoUpdates();
      }
      break;

      // fromDelta throws InvalidDeltaException, full distribution occurs
      case BadDeltaObject.fromDeltaThrowsInvDelExc: {
         testInstance.aRegion.put(key, anObj);
         int numVMs = TestHelper.getNumVMs();
         // check to see if one of the vms is an admin vm by looking for an admin alert listener in this test
         if ((TestConfig.tab().stringAt(util.AdminHelperPrms.alertListener, null)) != null) { // we have an admin vm
            numVMs = numVMs - 1;
         }
         // wait for the listeners in each vm to indicate full distribution
         TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                                   "DeltaPropagationBB.NUM_UPDATE", 
                                   DeltaPropagationBB.NUM_UPDATE, 
                                   numVMs,
                                   true, 
                                   -1,
                                   2000);
         DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.NUM_UPDATE);
         verifyValue(key, anObj);
      }
      break;

      default: {
         throw new TestException("Unknown test case " + testCase);
      }
   }
   checkForError();
}

/** For bad delta tests:
 *  Get a key that will be distributed for an update. This eliminates keys hosted in this
 *  vm by a PR with no redundantCopies in a peer configuration, since if we update in
 *  this situation there's nobody to distribute to. 
 */
protected Object getKeyForBadDeltaTest() {
   if (aRegion.getAttributes().getDataPolicy().withPartitioning()) {
      if (aRegion.getAttributes().getPartitionAttributes().getRedundantCopies() == 0) {
         if (!isBridgeConfiguration) {
            // look for a suitable key
            DistributedMember thisMember = CacheHelper.getCache().getDistributedSystem().getDistributedMember();
            List aList = new ArrayList(aRegion.keySet());
            while (aList.size() > 0) {
               int index = TestConfig.tab().getRandGen().nextInt(0, aList.size()-1);
               Object key = aList.get(index);
               DistributedMember member = PartitionRegionHelper.getPrimaryMemberForKey(aRegion, key);
               if (!member.equals(thisMember)) { // this key is OK
                  return key;
               }
               aList.remove(index);
            }
            throw new TestException("Test error; could not find a suitable key for testing bad delta");
         }
      }
   }
   List aList = new ArrayList(aRegion.keySet());
   return aList.get(TestConfig.tab().getRandGen().nextInt(0, aList.size()-1));
}

/** Look for expected exception returned from remote vm.
 *
 *  @param expected The class of the expected exception.
 *  @param got The exception the test caught.
 *  @param expectedIsFromRemoteVM true if the expected exception was expected to be returned
 *         from a remote vm, false if the expected exception was expected to be generated
 *         from the current vm. 
 */
protected void handleBadDeltaException(Class expected, Throwable got, boolean expectedIsFromRemoteVM) {
   if (got instanceof TestException) {
      throw ((TestException)got);
   }
   if (expectedIsFromRemoteVM) {
      if (isBridgeClient) { // this vm is a bridge client
         if ((got.getClass() == ServerOperationException.class) || 
             (got.getClass() == ServerConnectivityException.class)) {
            Throwable cause = got.getCause();
            if (cause.getClass() != expected
              && !(cause.getClass() == DeltaSerializationException.class
                  && cause.getCause() != null && cause.getCause().getClass() == expected)) {
            throw new TestException("Expected " + got.getClass().getName()
                + " with cause " + expected.getName() + " but got "
                + TestHelper.getStackTrace(got));
          }
            Log.getLogWriter().info("Test caught expected " + TestHelper.getStackTrace(got) + "; continuing test");
            return;
         } else { // did not get ServerOperationException
            throw new TestException("Expected " + ServerOperationException.class.getName() + " with cause " +
                  expected.getName() + " but got " + TestHelper.getStackTrace(got));
         }
      } else {
        if (got instanceof DeltaSerializationException && got.getCause().getClass() == expected) { 
          Log.getLogWriter().info("Test caught expected " + TestHelper.getStackTrace(got) + "; continuing test");
          return;
        }
      }
   }

   if (got.getClass() != expected && (got.getClass() != DeltaSerializationException.class
       || got.getCause() == null || got.getCause().getClass() != expected)) {
      throw new TestException("Expected " + expected.getName() + " but got " + TestHelper.getStackTrace(got));
   } else { // got exception that was not expected
      Log.getLogWriter().info("Test caught expected " + TestHelper.getStackTrace(got) + "; continuing test");
   }
}

/** Handle the case where a bad delta was invoked, but the delta update (the put)
 *  succeeded.
 *
 *  @param expectedExceptionClass The class of the expected exception for the update
 *         to be used IF this method determines that an exception should have been
 *         returned to the caller of the put.
 *         There is only 1 case where a failed delta call logs a warning and lets the
 *         update occur: the update occurs in a server and there are no server peers
 *         to distribute to (redundantCopies = 0 or no other servers), and the only
 *         member to distribute to is a client---in this case the server put succeeds
 *         and the client logs a warning.
 *         
 */
protected void handleBadDeltaSuccessfulUpdate(int badDeltaTestCase, Class expectedExceptionClass, Object key, BadDeltaObject value) {
   boolean isBadDeltaWarningCase = isBadDeltaWarningCase(badDeltaTestCase);
   if (isBadDeltaWarningCase) {
      Log.getLogWriter().info("This is a bad delta warning case");
      DeltaTestAlertListener.waitForWarning();
      DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.NUM_UPDATE);
      DeltaTestAlertListener.clearAlerts();
      // see that the update was applied here in the server
      BadDeltaObject got = (BadDeltaObject)(aRegion.get(key));
      if (!value.equals(got)) {
         throw new TestException("Expected value in region " + got.toStringFull() + " to be equal to updated value " +
            value.toStringFull());
      }
   } else { 
      throw new TestException("Bug 40721 or 40716; Expected " + expectedExceptionClass.getName() + ", but put completed without an exception");
   }
}

// ========================================================================
// verification methods

/**  Create a region snapshot from one vm's point of view, while any other
 *   threads in this or the same the vm also executing this method will 
 *   compare to that snapshot. 
 */
protected void concVerify()  {
   if (DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.ConcurrentLeader) == 1) {
      Log.getLogWriter().info("In concVerify, this thread is the concurrent leader");
      // this is the first thread to verify; all other threads will wait for this thread to
      // collect a final region snapshot from the blackboard from each vm and write it 
      // the complete snapshot to the blackboard; all other threads will read it and match it
      Map snapshot = new HashMap();
      Log.getLogWriter().info("This thread is the concurrentLeader, creating region snapshot..."); 
      Map sharedMap = DeltaPropagationBB.getBB().getSharedMap().getMap();
      Iterator it = sharedMap.keySet().iterator();
      while (it.hasNext()) {
         String key = (String)(it.next());
         if (key.startsWith(regionSnapshotKey)) {
            Map value = (Map)(sharedMap.get(key));
            snapshot.putAll(value);
         }
      }
      Log.getLogWriter().info("Done creating region snapshot with " + snapshot.size() + 
          " entries; " + DeltaObject.toStringAbbreviated(snapshot));
      DeltaPropagationBB.getBB().getSharedMap().put(DeltaPropagationBB.RegionSnapshot, snapshot);
      long snapshotWritten = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.SnapshotWritten);
      Log.getLogWriter().info("Incremented SnapshotWritten, now is " + snapshotWritten);
   } else { 
      Log.getLogWriter().info("In concVerify, this thread is waiting for the concurrent leader to write the snapshot");
      // this thread is not the first to verify; it will wait until the first thread has
      // written its state to the blackboard, then it will read it and verify that its state matches
      TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                                "DeltaPropagationBB.SnapshotWritten", 
                                DeltaPropagationBB.SnapshotWritten, 
                                1, 
                                true, 
                                -1,
                                2000);
      Map snapshot = (Map)(DeltaPropagationBB.getBB().getSharedMap().get(DeltaPropagationBB.RegionSnapshot));
      verifyRegionContents(snapshot);
   }
   verifyDeltaBehavior();
}

/** Given a List of expected objects, and a list to check, return
 *  unexpected and missing elements from the list to check.
 *  Note that the lists can contain duplicates and these need to
 *  be considered.
 *
 *  @param expected A List of objects expected to be in actual.
 *  @param actual The List to check against expected.
 *  @returns [0] a List of objects that were unexpected in actual
 *           [1] a List of objects that were missing in actual
 */
static private List[] getInconsistencies(List expected, List actual) {
   // find unexpected and missing results; do NOT use removeAll because
   // it removes all occurrences and we want to consider duplicates

   // find unexpected
   List unexpected = new ArrayList(actual); 
   for (Object element : expected) {
      unexpected.remove(element);
   }

   // find missing
   List missing = new ArrayList(expected); 
   for (Object element : actual) {
      missing.remove(element);
   }

   return new List[] {unexpected, missing};
}

/** Verify the state of the region and the given expected snapshot.
 */
protected void verifyRegionContents(Map expected) {
   Log.getLogWriter().info("Verifying contents of " + aRegion.getFullPath() + ", size is " + aRegion.size());
   // find unexpected and missing results
   Set regionKeySet = aRegion.keySet();
   int regionSize = aRegion.size();
   int regionKeySetSize = regionKeySet.size();
   Log.getLogWriter().info("regionSize is " + regionSize + " " +
                           "regionKeySetSize is " + regionKeySetSize);
   if (regionSize != regionKeySetSize) {
       throw new TestException("Inconsistent sizes: regionSize is " + regionSize + " " +
                           "regionKeySetSize is " + regionKeySetSize);
   }
   Set snapshotKeySet = expected.keySet();
   Set unexpectedInRegion = new HashSet(regionKeySet); 
   Set missingInRegion = new HashSet(snapshotKeySet); 
   unexpectedInRegion.removeAll(snapshotKeySet);
   missingInRegion.removeAll(regionKeySet);
   Log.getLogWriter().info("Found " + unexpectedInRegion.size() + " unexpected entries and " +
       missingInRegion.size() + " missing entries");

   // prepare an error string
   StringBuffer aStr = new StringBuffer();
   if (aRegion.size() != expected.size()) {
       aStr.append("Expected " + aRegion.getFullPath() + " to be size " + expected.size() +
                   ", but it is size " + aRegion.size());
       Log.getLogWriter().info(aStr.toString());
   }
   if (unexpectedInRegion.size() > 0) {
      String tmpStr = "Found the following " + unexpectedInRegion.size() + 
         " unexpected keys in " + aRegion.getFullPath() + ": " + unexpectedInRegion;
      Log.getLogWriter().info(tmpStr.toString());
      if (aStr.length() > 0) {
         aStr.append("\n");
      }
      aStr.append(tmpStr);
   }
   if (missingInRegion.size() > 0) {
      String tmpStr = "The following " + missingInRegion.size() + 
         " keys were missing from " + aRegion.getFullPath() + ": " + missingInRegion;
      Log.getLogWriter().info(tmpStr);
      if (aStr.length() > 0) {
         aStr.append("\n");
      }
      aStr.append(tmpStr);
   }

   // iterate the contents of the region
   Iterator it = aRegion.keySet().iterator();
   while (it.hasNext()) { 
      Object key = it.next();
      DeltaObject deltaObjInRegion = (DeltaObject)(aRegion.get(key));
      DeltaObject deltaObjInSnapshot = (DeltaObject)(expected.get(key));
      if (deltaObjInRegion == null) {
         if (deltaObjInSnapshot != null) {
            aStr.append("For key " + key + ", expected " + deltaObjInRegion + " to be " +
                 deltaObjInSnapshot.toStringFull() + "\n");
         }
      } else {
         if (!deltaObjInRegion.equals(deltaObjInSnapshot)) {
            aStr.append("For key " + key + ", expected " + deltaObjInRegion.toStringFull() + 
               " to be equal to snapshot value " + 
               ((deltaObjInSnapshot == null) ? "null" : (deltaObjInSnapshot.toStringFull())) + "\n");
         }
      }
   }  

   if (aStr.length() > 0) {
      throw new TestException(aStr.toString());
   }
   Log.getLogWriter().info("Done verifying contents of " + aRegion.getFullPath() + ", size is " + aRegion.size());
}

/** Write this vm's delta and cloning information to the blackboard
 *  to be used later for verification.
 */
protected void writeDeltaInfoToBlackboard() {
   int myVmID = RemoteTestModule.getMyVmid();

   Object key = toDeltaKey + myVmID;
   List value = DeltaObserver.getToDeltaKeys();
   DeltaPropagationBB.getBB().getSharedMap().put(key, value);
   Log.getLogWriter().info("Wrote to bb, key " + key + ", value " + keysToString(value));

   key = fromDeltaKey + myVmID;
   value = DeltaObserver.getFromDeltaKeys();
   DeltaPropagationBB.getBB().getSharedMap().put(key, value);
   Log.getLogWriter().info("Wrote to bb, key " + key + ", value " + keysToString(value));

   key = hasDeltaKey + myVmID;
   value = DeltaObserver.getHasDeltaKeys();
   DeltaPropagationBB.getBB().getSharedMap().put(key, value);
   Log.getLogWriter().info("Wrote to bb, key " + key + ", value " + keysToString(value));

   key = updatedKeysKey + myVmID;
   value = updatedKeys;
   DeltaPropagationBB.getBB().getSharedMap().put(key, value);
   Log.getLogWriter().info("Wrote to bb, key " + key + ", value " + keysToString(value));

   key = updatedKeysWithDeltaKey + myVmID;
   value = updatedKeysWithDelta;
   DeltaPropagationBB.getBB().getSharedMap().put(key, value);
   Log.getLogWriter().info("Wrote to bb, key " + key + ", value " + keysToString(value));

   key = updatedKeysNotDistKey + myVmID;
   value = updatedKeysNotDistributed;
   DeltaPropagationBB.getBB().getSharedMap().put(key, value);
   Log.getLogWriter().info("Wrote to bb, key " + key + ", value " + keysToString(value));
}

/** Clear variables that save up information for validation.
 */
protected void clear() {
   Log.getLogWriter().info("Calling clear for DeltaTest tracking...");
   DeltaObserver.clear();
   updatedKeys = new ArrayList();
   updatedKeysWithDelta = new ArrayList();
   updatedKeysNotDistributed = new ArrayList();
}

/** Verify that the delta behavior was correct (toDelta/fromDelta was called 
 *  and NOT called when appropriate, and cloning was handled correctly).
 *  This verifies the current vm, using information in the blackboard written
 *  by all other vms.
 *  Specifically this verifies:
 *  - the appropriate toDelta calls were made (and not made) in this vm
 *  - the appropriate fromDelta calls were made (and not made) in this vm
 *  - if the region in this vm is a PR
 *    - check PR metadata
 *    - check PR primaries
 *    - check data is consistent in all bucket copies
 */
protected void verifyDeltaBehavior() {
   int thisVmID = RemoteTestModule.getMyVmid();
   Map bbMap = DeltaPropagationBB.getBB().getSharedMap().getMap();
   //List updatedKeysForThisVM = (List)(bbMap.get(updatedKeysKey + thisVmID));         
   //List updatedKeysWithDeltaForThisVM = (List)(bbMap.get(updatedKeysWithDeltaKey + thisVmID));         
   //List updatedKeysNotDistributedInThisVM = (List)(bbMap.get(updatedKeysNotDistKey + thisVmID));         
   List updatedDistributedKeys = new ArrayList(updatedKeysWithDelta);
   for (Object element : updatedKeysNotDistributed) {
      updatedDistributedKeys.remove(element);
   }
   List toDeltaKeys = (List)(bbMap.get(toDeltaKey + thisVmID));         
   List fromDeltaKeys = (List)(bbMap.get(fromDeltaKey + thisVmID));         
   //List hasDeltaKeys = (List)(bbMap.get(hasDeltaKey + thisVmID));         
   
   // verify toDelta
   verifyDeltaCalls(thisVmID, toDeltaKeys, "to", updatedDistributedKeys);

   // gather up updated keys from all other vms
   Iterator it = bbMap.keySet().iterator();
   List union = new ArrayList();  // from all vms other than this one
   while (it.hasNext()) {
      String key = (String)(it.next());
      if (key.startsWith(updatedKeysWithDeltaKey)) { // found a key indicating verification should be done
         int vmID = (Integer.valueOf(key.substring(key.indexOf("_") + 1, key.length()))).intValue();
         if (vmID != thisVmID) {
            List otherUpdatedKeysWithDelta = (List)(bbMap.get(key));         
            //List otherUpdatedKeysNotDistributed = (List)(bbMap.get(updatedKeysNotDistKey + vmID));         
            List otherUpdatedDistributedKeys = new ArrayList(otherUpdatedKeysWithDelta);
            for (Object element : updatedKeysNotDistributed) {
               otherUpdatedDistributedKeys.remove(element);
            }
            union.addAll(otherUpdatedDistributedKeys);
         }
      }
   }
   Log.getLogWriter().info("Gathered updated (and distributed) keys from all vms other than this one: "
      + keysToString(union));

   // verify fromDelta and cloning
   if (aRegion.getAttributes().getDataPolicy().withPartitioning()) { // test uses prs (peers or servers)
      if (aRegion.getAttributes().getPartitionAttributes().getLocalMaxMemory() == 0) { // this is an accessor
         // accessors should never have fromDelta calls
         verifyDeltaCalls(thisVmID, fromDeltaKeys, "from", new ArrayList());
         // no fromDelta calls implies no cloning
      } else { // is a dataStore
         DistributedMember thisMember = CacheHelper.getCache().getDistributedSystem().getDistributedMember();
         List updatedKeysRedundant = new ArrayList();
         if (updatedKeysWithDelta.size() != 0) { // this vm updated keys
            for (int i = 0; i < updatedKeysWithDelta.size(); i++) {  // collect updated keys where this vm hosts a redundant copy
               Object key = updatedKeysWithDelta.get(i);
               Set<DistributedMember> redundantMembers = PartitionRegionHelper.getRedundantMembersForKey(aRegion, key);
               if (redundantMembers.contains(thisMember)) {
                  updatedKeysRedundant.add(key);
               }
            }
            if (isSerialExecution) {
              // only puts from this vm where this vm hosts the redundantCopy should get fromDelta calls;
              // this is because a put from here sends the first delta to the vm hosting the primary bucket
              // then the delta is sent from there to here (where the redundant bucket is)
              verifyDeltaCalls(thisVmID, fromDeltaKeys, "from", updatedKeysRedundant);
            }
         } 
         List keysInThisVM = new ArrayList();
         if (union.size() != 0) { // other vms updated keys
             for (int i = 0; i < union.size(); i++) {
               Object key = union.get(i);
               Set<DistributedMember> aSet = PartitionRegionHelper.getAllMembersForKey(aRegion, key);
               if (aSet.contains(thisMember)) {
                  keysInThisVM.add(key);
               }
            }
            if (isSerialExecution) {
              // whether this vm contains the primary or redundant bucket, we should get a 
              // fromDelta call because the put originated in a vm other than this one and that vm
              // should send a delta to the vm hosting the primary bucket, then that vm sends a delta
              // to the vms hosting the redundant buckets
              verifyDeltaCalls(thisVmID, fromDeltaKeys, "from", keysInThisVM);
            }
         }
         if (!isSerialExecution) {
           List expectedFromDeltaKeys = new ArrayList();
           expectedFromDeltaKeys.addAll(updatedKeysRedundant);
           expectedFromDeltaKeys.addAll(keysInThisVM);
           verifyDeltaCalls(thisVmID, fromDeltaKeys, "from", expectedFromDeltaKeys);
         }
      }
      ParRegUtil.verifyPRMetaData(aRegion);
      ParRegUtil.verifyPrimaries(aRegion, redundantCopies);
      ParRegUtil.verifyBucketCopies(aRegion, redundantCopies);
   } else { // not partitioned
      verifyDeltaCalls(thisVmID, fromDeltaKeys, "from", union);
   }
}

/** Verify that the given deltaKeys are expected.
 *
 *  @param vmID The vmID this is doing validation for.
 *  @param deltaKeys The list of keys whose values had a delta call.
 *  @param whichDelta "to" if this is validating toDelta calls, 
 *         "from" if this is validating fromDelta calls, "has" if this 
 *         is validating hasDelta calls.
 *  @param expectedKeys The List of keys whose values are expected to 
 *         have had toDelta/fromDelta/hasDelta called on them. 
 *         
 */
protected static void verifyDeltaCalls(int vmID, List deltaKeys, String whichDelta, List expectedKeys) {
   Log.getLogWriter().info("In verifyDeltaCalls for " + whichDelta + "Delta for vmID " + vmID + 
       ", expected keys: " + keysToString(expectedKeys) + "\n" +
       ", " + whichDelta + "Delta keys: " + keysToString(deltaKeys));

   Collection[] tmp = getInconsistencies(expectedKeys, deltaKeys);
   Collection unexpectedDeltaKeys = tmp[0];
   Collection missingDeltaKeys = tmp[1];

   StringBuffer aStr = new StringBuffer();
   if (unexpectedDeltaKeys.size() != 0) {
      aStr.append("Delta." + whichDelta + "Delta was unexpectedly called in vm id " + vmID +
                  " on the values of the following updated keys: " + keysToString(unexpectedDeltaKeys));
   } else {
      Log.getLogWriter().info("Found no unexpected " + whichDelta + "Delta calls in vm " + vmID);
   }
   if (missingDeltaKeys.size() != 0) {
      aStr.append("Delta." + whichDelta + "Delta was not called in vm id " + vmID +
           " on the values of the following updated keys: " + keysToString(missingDeltaKeys));
   } else {
      Log.getLogWriter().info("Found no missing " + whichDelta + "Delta calls in vm " + vmID);
   }
   if (aStr.length() > 0) {
      throw new TestException(aStr);
   }
}

/** Verify that no updates have occured.
 */
protected void verifyNoUpdates() {
   long counter = DeltaPropagationBB.getBB().getSharedCounters().read(DeltaPropagationBB.NUM_UPDATE);
   Log.getLogWriter().info("NUM_UPDATES is " + counter);
   if (counter != 0) {
      throw new TestException("Bug 40747 Expected failed update, but afterUpdate was invoked, NUM_UPDATE is " + counter);
   }
}

/** Serial test for eviction with careful validation
 *
 */
public void doSerialRREvictionTest() {
  logExecutionNumber();
  long numThreads = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
  long roundPosition = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.RoundPosition);
  Log.getLogWriter().info("In doSerialRREvictionTest, roundPosition is " + roundPosition);
  if (roundPosition == numThreads) { // this is the last in the round
    Log.getLogWriter().info("In doSerialRREvictionTest, last in round");
    verifyEviction();
    // now become the first in the round 
    DeltaPropagationBB.getBB().getSharedCounters().zero(DeltaPropagationBB.RoundPosition);
    roundPosition = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.RoundPosition);
  }

  if (roundPosition == 1) { // first in round
    long roundNumber = DeltaPropagationBB.getBB().getSharedCounters().incrementAndRead(DeltaPropagationBB.RoundNumber);
    Log.getLogWriter().info("In doSerialRREvictionTest, first in round, round number " + roundNumber);
    doEvictionTestOps();
    verifyEviction();
  } else if (roundPosition != numThreads) { // neither first nor last
    Log.getLogWriter().info("In doSerialRREvictionTest, neither first nor last");
    verifyEviction();
  }
}

/** Do operations for eviction tests
 */
protected void doEvictionTestOps() {
  List currentKeys = (List)DeltaPropagationBB.getBB().getSharedMap().get(currentKeyListKey);
  if (currentKeys == null) {
    currentKeys = new ArrayList();
  }
  List keysAvailableThisTask = new ArrayList(currentKeys); // used to ensure this task only does one op for any one key
  int numOpsPerTask = TestConfig.tab().intAt(DeltaPropagationPrms.numOpsPerTask); 

  for (int i = 1; i <= numOpsPerTask; i++) {
    int size = aRegion.size();
    String operation = null;
    if (size <= lowerThreshold) {
      operation = TestConfig.tab().stringAt(DeltaPropagationPrms.lowerThresholdOperations);
    } else if (size > upperThreshold) {
      operation = TestConfig.tab().stringAt(DeltaPropagationPrms.upperThresholdOperations);
    } else {
      operation = TestConfig.tab().stringAt(DeltaPropagationPrms.entryOperations);
    }
    Log.getLogWriter().info(aRegion.getFullPath() + " is size " + size + 
        ", operation is " + operation);
    if (operation.equals("add")) {
      String key = addNewValue();
      DeltaPropagationBB.getBB().getSharedMap().put(opKey, new Object[] {"add", key});
      currentKeys.add(key);
    } else if (operation.equals("update")) {
      Object[] tmp = updateDeltaValueHolder(keysAvailableThisTask);
      String key = (String)tmp[0];
      Integer previousPayloadSize = (Integer)tmp[1];
      DeltaPropagationBB.getBB().getSharedMap().put(opKey, new Object[] {"update", key, previousPayloadSize});
      keysAvailableThisTask.remove(key);
    } else if (operation.equals("destroy")) {
      int index = TestConfig.tab().getRandGen().nextInt(0, keysAvailableThisTask.size()-1);
      String key = (String)(keysAvailableThisTask.get(index));
      Log.getLogWriter().info("Destroying key " + key);
      aRegion.destroy(key);
      Log.getLogWriter().info("Done destroying key " + key);
      currentKeys.remove(key);
      keysAvailableThisTask.remove(key);
    } else if (operation.equals("getNew")) {
      String key = (String)getNewKey();
      Log.getLogWriter().info("Getting new key " + key);
      Object value = aRegion.get(key);
      Log.getLogWriter().info("Done getting new key " + key + " value is " + TestHelper.toString(value));
      currentKeys.add(key);
    } else if (operation.equals("invalidate")) {
      int index = TestConfig.tab().getRandGen().nextInt(0, keysAvailableThisTask.size()-1);
      String key = (String)(keysAvailableThisTask.get(index));
      Log.getLogWriter().info("Invalidating " + key);
      aRegion.invalidate(key);
      Log.getLogWriter().info("Done invalidating " + key);
      keysAvailableThisTask.remove(key);
    } else {
      throw new TestException("Test does not support operation " + operation);
    }
  }
  Log.getLogWriter().info("Completed " + numOpsPerTask + " operations this task");
  DeltaPropagationBB.getBB().getSharedMap().put(currentKeyListKey, currentKeys);
}

/** Add a new entry for eviction tests
 */
protected String addNewValue() {
  String key = (String)getNewKey();
  String valueClass = DeltaPropagationPrms.getValueClass();
  try {
    Class cls = Class.forName(valueClass);
    BaseValueHolder value = (BaseValueHolder)cls.newInstance();
    value.myValue = DeltaPropagationPrms.getPretendSize();
    value.extraObject = new byte[DeltaPropagationPrms.getPayloadSize()];
    Log.getLogWriter().info("Adding new key " + key + ", value is " + TestHelper.toString(value));
    aRegion.put(key, value);
    Log.getLogWriter().info("Done adding new key " + key + ", value is " + TestHelper.toString(value));
    return key;
  } catch (ClassNotFoundException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (InstantiationException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  } catch (IllegalAccessException e) {
    throw new TestException(TestHelper.getStackTrace(e));
  }
}

/** Perform an update on an existing key. 
 * @return [0] (String) The key that was updated
 *         [1] (Integer) The size of the byte[] payload previously associated
 *                       with this key.
 */
protected Object[] updateDeltaValueHolder(List currentKeyList) {
  int index = TestConfig.tab().getRandGen().nextInt(0, currentKeyList.size()-1);
  String key = (String)(currentKeyList.get(index));
  Object value = aRegion.get(key);
  if (value instanceof BaseValueHolder) {
    BaseValueHolder dvh = (BaseValueHolder)value;
    int previousPayloadSize = ((byte[])(dvh.extraObject)).length;
    Log.getLogWriter().info("Updating " + key + " with previous value " + TestHelper.toString(dvh));
    dvh.myValue = DeltaPropagationPrms.getPretendSize();
    dvh.extraObject = new byte[DeltaPropagationPrms.getPayloadSize()];
    aRegion.put(key, dvh);
    Log.getLogWriter().info("Done updating " + key + ", value is now " + TestHelper.toString(dvh));
    return new Object[] {key, previousPayloadSize};
  } else if (value == null) { // we invalidated it
    String valueClass = DeltaPropagationPrms.getValueClass();
    try {
      Class cls = Class.forName(valueClass);
      BaseValueHolder vh = (BaseValueHolder)cls.newInstance();
      vh.myValue = DeltaPropagationPrms.getPretendSize();
      vh.extraObject = new byte[DeltaPropagationPrms.getPayloadSize()];
      Log.getLogWriter().info("Adding new key " + key + ", value is " + TestHelper.toString(vh));
      aRegion.put(key, vh);
      Log.getLogWriter().info("Done adding new key " + key + ", value is " + TestHelper.toString(vh));
      return new Object[] {key, null};
    } catch (ClassNotFoundException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    } catch (InstantiationException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    } catch (IllegalAccessException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    }
  } else {
    throw new TestException("Value for key " + key + " is " + value + " but expected a DeltaValueHolder");
  }
}

/** Verify eviction (both for all entries in the cache and for the last operation
 */
protected void verifyEviction() {
  if (aRegion.getAttributes().getEvictionAttributes().getAlgorithm().isLRUMemory()) {
    if (aRegion.getAttributes().getDataPolicy().withPartitioning()) { // have memLRU eviction with PR
      long numBytesInVM = checkMemLRULevelForPR();
      //xxxcheckEviction();  add in once Darrel gets product changes checked in
      saveEvictionStats(numBytesInVM);
    } else {
      throw new TestException("Test validation not supported for " +
          aRegion.getAttributes().getDataPolicy());
    }
  } else {
    throw new TestException("Test validation not supported for " +
        aRegion.getAttributes().getEvictionAttributes().getAlgorithm());
  }
}

/** Make the current values the before values
 */
protected void saveEvictionStats(long numBytesInVM) {
  // reset the before values
  before_stat_entriesInVM = ((PartitionedRegion)aRegion).getDiskRegionStats().getNumEntriesInVM();
  before_stat_numOverflowOnDisk = ((PartitionedRegion)aRegion).getDiskRegionStats().getNumOverflowOnDisk();
  before_numBytesInThisVM = numBytesInVM;
  before_stat_lruEvictions = ((LocalRegion)aRegion).getEvictionController().getLRUHelper().getStats().getEvictions();
  Log.getLogWriter().info("Set before values to:\n" +
      "before_stat_entriesInVM: " + before_stat_entriesInVM + "\n" +
      "before_stat_numOverflowOnDisk: " + before_stat_numOverflowOnDisk + "\n" +
      "before_numBytesInThisVM: " + before_numBytesInThisVM + "\n" +
      "before_lruEvictions: " + before_stat_lruEvictions);
}

/** Check eviction in this vm for a particular key (the last operation). 
 */
protected void checkEviction() {
  Object[] operationArr = (Object[])(DeltaPropagationBB.getBB().getSharedMap().get(opKey));
  int memLRULimitMB = aRegion.getAttributes().getEvictionAttributes().getMaximum();
  int memLRULimitBytes = memLRULimitMB * 1024 * 1024;
  String operation = (String)operationArr[0];
  String key = (String)operationArr[1];
  boolean keyHostedInThisVM = thisVMHostsKey(aRegion, key);
  Object valueInVM = DiskRegUtil.getValueInVM(aRegion, key); // always returns full object (not serialized)
  Object actualValueInVM;
  try {
    actualValueInVM = ((LocalRegion)aRegion).getValueInVM(key);
  } catch (EntryNotFoundException e) {
    actualValueInVM = null;
  }
  Object valueOnDisk = DiskRegUtil.getValueOnDiskOrBuffer(aRegion, key);
  long stat_entriesInVM = ((PartitionedRegion)aRegion).getDiskRegionStats().getNumEntriesInVM();
  long stat_numOverflowOnDisk = ((PartitionedRegion)aRegion).getDiskRegionStats().getNumOverflowOnDisk();
  long stat_lruEvictions = ((LocalRegion)aRegion).getEvictionController().getLRUHelper().getStats().getEvictions();
  PretendSizer sizer = new PretendSizer();
  Log.getLogWriter().info("Checking eviction for operation " + operation + " and key " + key + "\n" +
      "valueInVM (real object): " + TestHelper.toString(valueInVM) + "\n" +
      "valueInVM (actual): " + ((actualValueInVM == null) ? "null" : actualValueInVM.getClass().getName()) + "\n" +
      "valueOnDisk: " + TestHelper.toString(valueOnDisk) + "\n" +
      "key is hosted in this VM: " + keyHostedInThisVM + "\n" +
      "before entriesInVM: " + before_stat_entriesInVM + "\n" +
      "before numOverflowOnDisk: " + before_stat_numOverflowOnDisk + "\n" +
      "before lruEvictions: " + before_stat_lruEvictions + "\n" +
      "now entriesInVM: " + stat_entriesInVM + "\n" +
      "now numOverflowOnDisk: " + stat_numOverflowOnDisk + "\n" +
      "now lruEvictions: " + stat_lruEvictions + "\n" +
      "memLRULimit (mb): " + memLRULimitMB + "\n" +
      "memLRULimitBytes: " + memLRULimitBytes);
  if (!keyHostedInThisVM) { // no eviction should occur; everything should be the same as before
    if ((valueInVM != null) || (valueOnDisk != null)) {
      throw new TestException("Key " + key + " is not hosted in this vm " +
          "according to PartitionedRegionHelper, but valueInVM is " + TestHelper.toString(valueInVM) +
          ", and value on disk is " + TestHelper.toString(valueOnDisk));
    }
    if ((before_stat_lruEvictions != stat_lruEvictions)) {
      throw new TestException("Key " + key + " is not hosted in this vm so expected no eviction " +
          " but stats after the operation have changed: before entriesInVM is " + before_stat_entriesInVM +
          ", before numOverflowOnDisk is " + before_stat_numOverflowOnDisk + ", after entriesInVM is " +
          stat_entriesInVM + ", after numOverflowOnDisk " + stat_numOverflowOnDisk);
    }
    return;
  }

  // we know this vm hosts this key; determine the net effect of the operation on the bytes in this vm
  int nowSizeOfObject = 0 ;
  if (actualValueInVM instanceof BaseValueHolder) {
    nowSizeOfObject = sizer.getSize(actualValueInVM);
  } else if (actualValueInVM instanceof CachedDeserializable) {
    ((CachedDeserializable)actualValueInVM).getSizeInBytes();
  } else {
    throw new TestException("Test does not currently handle " + actualValueInVM.getClass().getName());
  }
  int beforeSizeOfObject = beforeMap.get(key);
  beforeMap.put(key, new Integer(nowSizeOfObject));
  int netSizeChange = nowSizeOfObject - beforeSizeOfObject;
  long newVMSize = before_numBytesInThisVM + netSizeChange;
  Log.getLogWriter().info("Before, value for key " + key + " had size " + beforeSizeOfObject + 
      ", now size is " + nowSizeOfObject + ", net change is " + netSizeChange +
      ", new vm size in bytes is " + newVMSize);
      
//  if (operation.equals("add")) { // we increased the bytes in this vm by the payload size
//  } else if (operation.equals("update")) {
//  } else {
//    throw new TestException("Test validation does not support operation " + operation);
//  }

  // validate whether eviction occurred or not
  if (newVMSize >= memLRULimitBytes) { // expect eviction
    // we might need to evict more than 1 entry to make room for the new entry
    // so just make sure that numOverflowOnDisk increased
    if (stat_lruEvictions > before_stat_lruEvictions) {
      throw new TestException("Eviction should have occurred but didn't");
    }
    Log.getLogWriter().info("Eviction occurred as expected");
  } else { // expect no eviction
    if (stat_lruEvictions != before_stat_lruEvictions) {
      throw new TestException("No eviction was expected " +
          "but stats after the operation have changed: before lruEvictions was " + 
          before_stat_lruEvictions + ", now lruEvictions is " + stat_lruEvictions);
    }
    Log.getLogWriter().info("No eviction occurred as expected");
  }
}

/** Iterates over all the entries hosted in this vm and calculates their bytes.
 *  Determines whether we are over eviction or not. This throws an exception
 *  if we are over the limit. 
 * @return The calculated number of bytes in this vm. 
 */
protected long checkMemLRULevelForPR() {
  if (aRegion.getAttributes().getDataPolicy().withPartitioning()) {
    if (aRegion.getAttributes().getPartitionAttributes().getLocalMaxMemory() == 0) {
      Log.getLogWriter().info("Not checking memLRU levels, this vm is an accessor");
      return 0;
    }
  }
  // read info from stats and region attributes
  int memLRULimitMB = aRegion.getAttributes().getEvictionAttributes().getMaximum();
  int memLRULimitBytes = memLRULimitMB * 1024 * 1024;
  long stat_entriesInVM = ((PartitionedRegion)aRegion).getDiskRegionStats().getNumEntriesInVM();
  long stat_numOverflowOnDisk = ((PartitionedRegion)aRegion).getDiskRegionStats().getNumOverflowOnDisk();
  long stat_totalEntriesFromStats = stat_entriesInVM + stat_numOverflowOnDisk;
  long stat_lruEvictions = ((LocalRegion)aRegion).getEvictionController().getLRUHelper().getStats().getEvictions();
  long stat_byteCount = ((LocalRegion)aRegion).getEvictionController().getLRUHelper().getStats().getCounter();
  Log.getLogWriter().info("Stat entriesInVM: " + stat_entriesInVM + "\n" +
      "stat numOverflowOnDisk: " + stat_numOverflowOnDisk + "\n" +
      "stat totalEntriesFromStats: " + stat_totalEntriesFromStats + "\n" +
      "stat lruEvictions: " + stat_lruEvictions + "\n" +
      "stat byteCount: " + stat_byteCount + "\n" +
      "memLRU limit (mb): " + memLRULimitMB + "\n" +
      "memLRU limit (bytes): " + memLRULimitBytes);

  // count entries and analyze the current state of things
  PretendSizer sizer = new PretendSizer();
  long highestKeyIndex = NameFactory.getPositiveNameCounter();
  long totalBytesInThisVM = 0;
  int count_numEntriesInVM = 0;
  int count_numEntriesOnDisk = 0;
  Set<String> onDiskSet = new HashSet();
  Set<String> inVMSet = new HashSet();
  int perEntryOverhead = ((PartitionedRegion)aRegion).getPerEntryLRUOverhead();
  int cdOverhead = CachedDeserializableFactory.overhead();
  Log.getLogWriter().info("perEntryOverhead is " + perEntryOverhead);
  Log.getLogWriter().info("Analyzing " + highestKeyIndex + " entries...");
  for (int i = 1; i <= highestKeyIndex; i++) {
    String key = NameFactory.getObjectNameForCounter(i);
    Object valueInVM = DiskRegUtil.getValueInVM(aRegion, key); // always returns full object (not serialized)
    Object actualValueInVM = null;
    Object cdForm = null;
    try {
      actualValueInVM = ((LocalRegion)aRegion).getValueInVM(key);
      if (actualValueInVM instanceof CachedDeserializable) {
        cdForm = ((CachedDeserializable)actualValueInVM).getValue();
      }
    } catch (EntryNotFoundException e) {
      actualValueInVM = null;
    }
    Object valueOnDisk = DiskRegUtil.getValueOnDiskOrBuffer(aRegion, key);
    Log.getLogWriter().info("For key " + key + ", valueInVM is " + TestHelper.toString(valueInVM) +
        ", actualValueInVM is " + TestHelper.toString(actualValueInVM) + 
        ", CachedDeserializable form is " + TestHelper.toString(cdForm) + "\n" +
        ", valueOnDisk is " + TestHelper.toString(valueOnDisk));
    // both valueInVM and valueOnDisk can be null if this vm does not host this key for a PR
    if ((valueInVM != null) && (valueOnDisk != null)) { // value cannot be both in vm and on disk for overflow
      boolean valuesEqual = valueInVM.equals(valueOnDisk);
      throw new TestException("For key " + key + ", value in VM is " +
          TestHelper.toString(valueInVM) + " and value on disk is " + TestHelper.toString(valueOnDisk) +
          "; do not expect value to be both in vm and on disk; actualValueInVM is " +
          actualValueInVM.getClass().getName() + " valueInVM and valueOnDisk are equal: " + valuesEqual);
    }
    if (valueInVM != null) { // value is in the vm
      inVMSet.add(key);
      count_numEntriesInVM++;
      int sizeOfObject = 0;
      String sizeParts = null;
      if (actualValueInVM instanceof BaseValueHolder) {
        sizeOfObject = sizer.getSize(actualValueInVM);
        sizeParts = "full value, size from sizer: " + 10;
      } else if (actualValueInVM instanceof CachedDeserializable) {
        if (cdForm instanceof byte[]) {
           sizeOfObject = ((CachedDeserializable)actualValueInVM).getSizeInBytes();
           sizeParts = "CachedDeserializable in byte form, getSizeInBytes() " + sizeOfObject;
        } else if (cdForm instanceof BaseValueHolder) {
           int sizerValue = sizer.getSize(cdForm);
           sizeOfObject = sizerValue + cdOverhead;
           sizeParts = "CachedDeserializable in full object form, sizer " + sizerValue + 
               ", CachedDeserializableFactory overhead " + cdOverhead;
        } else {
          throw new TestException("Test currently does not handle CachedDeserializable.getValue() " +
              TestHelper.toString(cdForm));
        }
      } else if (actualValueInVM instanceof Token.Invalid) {
        sizeParts = "Token.invalid, size 0";
      } else {
        throw new TestException("Test does not currently handle " + actualValueInVM.getClass().getName());
      }
      int keySize = TestHelper.calculateStringSize(key);
      int totalSize = sizeOfObject + keySize + perEntryOverhead;
      Log.getLogWriter().info(key + " total size " + totalSize + " (" + sizeParts + 
          " keySize " + keySize + ", entry overhead " + perEntryOverhead);
      totalBytesInThisVM += totalSize;
    } else if (valueOnDisk != null) { // value is on disk but we still need to count the key
      onDiskSet.add(key);
      count_numEntriesOnDisk++;
      int keySize = TestHelper.calculateStringSize(key);
      Log.getLogWriter().info(key + " size is " + keySize + ", value is on disk, entry overhead " +
          perEntryOverhead);
      totalBytesInThisVM += (keySize + perEntryOverhead);
    }
  }
  // find how it changed from before
  Set newInVM = new HashSet(inVMSet);
  newInVM.removeAll(before_inVMSet);
  Set newOnDisk = new HashSet(onDiskSet);
  newOnDisk.removeAll(before_onDiskSet);
  int count_totalEntries = count_numEntriesInVM + count_numEntriesOnDisk;
  Log.getLogWriter().info("Counted " + count_numEntriesInVM + " entries in vm\n" +
      "Counted " + count_numEntriesOnDisk + " entries on disk\n" +
      "Total entries: " + count_totalEntries + "\n" +
      "Bytes in vm: " + totalBytesInThisVM + "\n" +
      "New in vm: " + newInVM + "\n" +
      "New on disk: " + newOnDisk);
  before_onDiskSet = onDiskSet;
  before_inVMSet = inVMSet;

  // validate memLRU levels and stats
  if (stat_entriesInVM != count_numEntriesInVM) {
    throw new TestException("Number of entries in vm from stats is " + stat_entriesInVM +
        " and number of entries determined by test is " + count_numEntriesInVM);
  }
  if (stat_numOverflowOnDisk != count_numEntriesOnDisk) {
    throw new TestException("Number of entries on disk from stats is " + stat_numOverflowOnDisk +
        " and number of entries determined by test is " + count_numEntriesOnDisk);
  }
  if (stat_byteCount != totalBytesInThisVM) {
    throw new TestException("Test determined there are " + totalBytesInThisVM + 
        " bytes in this vm, but stat byteCount is " + stat_byteCount);
  }
  // enable the following 2 lines for bug 41952 workaround
  long overage = totalBytesInThisVM - memLRULimitBytes;
  overageHistory.add(overage);
  if (totalBytesInThisVM > memLRULimitBytes) {
    //throw new TestException("Test determined there are " + totalBytesInThisVM + 
    //    " bytes in this vm but max bytes allowed by memLRU is " + memLRULimitBytes);
    // the above throw statement is disabled to provide a workaround for 41952
    // enable the following and disable the above to workaround bug 41952
    Log.getLogWriter().info("Possibly bug 41952, total bytes in vm " + totalBytesInThisVM + 
       " exceeded memLRU limit of " + memLRULimitBytes + 
       "; vm is over the limit by " + overage + " bytes"); 
  } else {
     Log.getLogWriter().info("Bytes in vm " + totalBytesInThisVM + " are less than the memLRU limit of " +
        memLRULimitBytes + "; under by " + (memLRULimitBytes - totalBytesInThisVM) + " bytes");
  }
  return totalBytesInThisVM;
}

/** This is called from a close task to allow the test to hit bug 41952
 *  and report its occurrence in a close task. This way the test can
 *  keep running and we can see if the overages are a trend or get 
 *  corrected by other evictions later on.
 */
protected void checkOverageHistory() {
   StringBuffer aStr = new StringBuffer();
   aStr.append("Number of bytes over or under limit for this vm's history:\n");
   int count = 0;
   long maxOverage = Long.MIN_VALUE;
   for (long overage: overageHistory) {
      if (overage > 0) {
         aStr.append(" OVER " + overage + ";");
      } else {
         aStr.append(" under " + overage + ";");
      }
      count++;
      if (count == 8) {
         aStr.append("\n");
         count = 0;
      }
      maxOverage = Math.max(maxOverage, overage);
   }
   Log.getLogWriter().info(aStr.toString());

   // determine a threshold for which we will tolerate overages due to bug
   // 41952. Since 41952 is all about not accouting for keys and keys are
   // relatively small in this test, choose a small-ish limit
   long threshold = 5000;
   if (maxOverage > threshold) {
      throw new TestException("Over the memLRU limit by " + maxOverage + " bytes");
   }
}

/** Return true if the region hosts the key in this vm.
 * 
 * @param aReg A partitioned region.
 * @pararm key The key to check
 * @return
 */
protected boolean thisVMHostsKey(Region aReg, Object key) {
  Set<DistributedMember> redundantMembers = PartitionRegionHelper.getRedundantMembersForKey(aReg, key);
  DistributedMember primaryMember = PartitionRegionHelper.getPrimaryMemberForKey(aRegion, key);
  Log.getLogWriter().info("For key " + key + " in region " + aReg.getFullPath() +
      " primaryMember: " + primaryMember +
      " redundantMembers: " + redundantMembers);
  for (DistributedMember dm: redundantMembers) {
    if (dm.getProcessId() == RemoteTestModule.MyPid) {
      return true;
    }
  }
  return primaryMember.getProcessId() == RemoteTestModule.MyPid;
}

// ========================================================================
// other methods

/** Verify that the given key has the given value.
*
*  @param key The key to check.
*  @param expectedValue The expected value of key.
*/
protected void verifyValue(Object key, BadDeltaObject expectedValue) {
  BadDeltaObject valueInVM = null;
  if (aRegion.getAttributes().getDataPolicy().withPartitioning()) {
    if (aRegion.getAttributes().getPartitionAttributes().getLocalMaxMemory() != 0) {
       Set aSet = PartitionRegionHelper.getAllMembersForKey(aRegion, key);
       if (aSet.contains(CacheHelper.getCache().getDistributedSystem().getDistributedMember())) {
         valueInVM = (BadDeltaObject)(diskReg.DiskRegUtil.getValueInVM(aRegion, key));
       }
    }
  } else {
    valueInVM = (BadDeltaObject)(diskReg.DiskRegUtil.getValueInVM(aRegion, key));
  }
  BadDeltaObject getValue = (BadDeltaObject)(aRegion.get(key));
  Log.getLogWriter().info("valueInVM for key " + key + " is " +
          ((valueInVM == null) ? (valueInVM) : (valueInVM.toStringFull())));
  Log.getLogWriter().info("get value for key " + key + " is " +
      ((getValue == null) ? (getValue) : (getValue.toStringFull())));
  if (valueInVM != null) { // value was retrieved locally; it should be same as get
     if (!valueInVM.equals(getValue)) {
       throw new TestException("ValueInVM " + valueInVM.toStringFull() + " is not equal to value from get" +
           ((getValue == null) ? (getValue) : (getValue.toStringFull())));
     }
  }
  if (!expectedValue.equals(getValue)) {
     throw new TestException("Expected value for key " + key + " to be " + expectedValue.toStringFull() +
           ", but it is " + ((getValue == null) ? (getValue) : (getValue.toStringFull())));
  }
}

/** Get a random operation using the given hydra parameter.
 *
 *  @param whichPrm A hydra parameter which specifies random operations.
 *
 *  @returns A random operation.
 */
protected int getOperation(Long whichPrm) {
   int op = 0;
   String operation = TestConfig.tab().stringAt(whichPrm);
   if (operation.equals("add"))
      op = ENTRY_ADD_OPERATION;
   else if (operation.equals("update"))
      op = ENTRY_UPDATE_OPERATION;
   else if (operation.equals("invalidate"))
      op = ENTRY_INVALIDATE_OPERATION;
   else if (operation.equals("destroy"))
      op = ENTRY_DESTROY_OPERATION;
   else if (operation.equals("get"))
      op = ENTRY_GET_OPERATION;
   else if (operation.equals("getNew"))
      op = ENTRY_GET_NEW_OPERATION;
   else if (operation.equals("localInvalidate"))
      op = ENTRY_LOCAL_INVALIDATE_OPERATION;
   else if (operation.equals("localDestroy"))
      op = ENTRY_LOCAL_DESTROY_OPERATION;
   else
      throw new TestException("Unknown entry operation: " + operation);
   return op;
}

/** Return a random key currently in the given region.
 *
 *  @param aRegion The region to use for getting an existing key.
 *  @param uniqueKeys True if each thread operates on a unique set of keys.
 *  @param numThreads Used if uniqueKeys is true. This is the number of
 *         threads using uniqueKeys.
 *
 *  @returns A key in the region.
 */
public static Object getExistingKey(Region aRegion, boolean uniqueKeys, int numThreads) {
   List keyList = new ArrayList();
   keyList.addAll(aRegion.keySet());
   if (keyList.size() == 0) {
      return null;
   }
   GsRandom rand = TestConfig.tab().getRandGen();
   int myTid = RemoteTestModule.getCurrentThread().getThreadId();
   while (keyList.size() != 0) {
      int randInt = rand.nextInt(0, keyList.size()-1);
      Object key = keyList.get(randInt);
      if (uniqueKeys) {
         long keyIndex = NameFactory.getCounterForName(key);
         if ((keyIndex % numThreads) == myTid) {
            return key;
         }
      } else {
         return key;
      }
      keyList.remove(randInt);
   }
   return null;
}

/** Check if we have run for the desired length of time. We cannot use 
 *  hydra's taskTimeSec parameter because of a small window of opportunity 
 *  for the test to hang due to the test's "concurrent round robin" type 
 *  of strategy. Here we set a blackboard counter if time is up and this
 *  is the last concurrent round.
 */
protected void checkForLastIteration() {
   // determine if this is the last iteration
   long taskStartTime = 0;
   final String bbKey = "TaskStartTime";
   Object anObj = DeltaPropagationBB.getBB().getSharedMap().get(bbKey);
   if (anObj == null) {
      taskStartTime = System.currentTimeMillis();
      DeltaPropagationBB.getBB().getSharedMap().put(bbKey, new Long(taskStartTime));
      Log.getLogWriter().info("Initialized taskStartTime to " + taskStartTime);
   } else {
      taskStartTime = ((Long)anObj).longValue();
   }
   if (System.currentTimeMillis() - taskStartTime >= secondsToRun * 1000) {
      Log.getLogWriter().info("This is the last iteration of this task");
      DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.TimeToStop);
   } else {
      Log.getLogWriter().info("Running for " + secondsToRun + " seconds; time remaining is " +
         (secondsToRun - ((System.currentTimeMillis() - taskStartTime) / 1000)) + " seconds");
   }
}

/** Handle an exception thrown by doing general operations during HA.
 */
protected void handleException(Exception anExcept) {
   boolean thisVMReceivedNiceKill = StopStartVMs.niceKillInProgress();
   String errStr = anExcept.toString();
   Throwable causedBy = anExcept.getCause();
   if (causedBy != null) {
      errStr = errStr + causedBy.toString();
   }
   boolean disconnectError = 
           (errStr.indexOf(
              "This connection to a distributed system has been disconnected") >= 0) ||
           (errStr.indexOf(
              "System is disconnecting") >= 0);

   if (anExcept instanceof CancelException) {
      if ( thisVMReceivedNiceKill || cacheIsClosed || disconnected) {
         // a thread in this VM closed the cache or disconnected from the dist system
         // or we are undergoing a nice_kill; all is OK
      } else { // no reason to get this error
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
      if (isBridgeConfiguration && thisVMReceivedNiceKill) {
         if (anExcept.toString().indexOf("The BridgeLoader has been closed") >= 0) {
            // we got a BridgeLoader exception while undergoing a nice_kill; all is ok
   } else {
            throw new TestException(TestHelper.getStackTrace(anExcept));
         }
      } else { // got exception, but it's not a bridge loader being shutdown
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if (anExcept instanceof BridgeWriterException) {
      if (isBridgeConfiguration && thisVMReceivedNiceKill) {
         if (anExcept.toString().indexOf("The BridgeWriter has been closed") >= 0) {
            // we got a BridgeWriter exception while undergoing a nice_kill; all is ok
         } else {
            throw new TestException(TestHelper.getStackTrace(anExcept));
         }
      } else { // got exception, but it's not a bridge writer being shutdown
         throw new TestException(TestHelper.getStackTrace(anExcept));
      }
   } else if (anExcept instanceof NullPointerException) {
      // This has happened when a vm is undergoing a shutdown and the util.DeltaObject
      // initializer tries to access the blackboard, but the blackboard is not available.
      // Only accept an NPE if it is from the DeltaObject initializer
      String stackStr = TestHelper.getStackTrace(anExcept);
      if (stackStr.indexOf("DeltaObject.<init>") >= 0) {
         // allow it
      } else {
         throw new TestException(stackStr);
      }
   } else {
      throw new TestException("Got unexpected exception " + TestHelper.getStackTrace(anExcept));
   }
   Log.getLogWriter().info("Caught " + anExcept + "; expected, continuing test");
   DeltaPropagationBB.getBB().getSharedCounters().increment(DeltaPropagationBB.ExceptionCounter);
   TestHelper.waitForCounter(DeltaPropagationBB.getBB(), 
                             "DeltaPropagationBB.Reinitialized", 
                             DeltaPropagationBB.Reinitialized, 
                             1, 
                             true, 
                             -1,
                             1000);
}

/** Modify 1 or more primitive fields of the given DeltaObject. Randomly choose
 *  to set the delta booleans (so the subsequent put will do a delta distribution)
 *  or not (so the subsequent put will do a full distribution). 
 *
 *  @param anObj The DeltaObject to modify 
 *  @returns true if delta booleans were set for the changes, false otherwise
 *           (which means hasDelta will return false and we should do a full
 *           distribution).
 */
protected boolean modifyDeltaObject(DeltaObject anObj) {
   List fieldsToModify = new ArrayList();
      fieldsToModify.add("aPrimitiveLong");
      fieldsToModify.add("aPrimitiveInt");
      fieldsToModify.add("aPrimitiveShort");
      fieldsToModify.add("aPrimitiveFloat");
      fieldsToModify.add("aPrimitiveDouble");
      fieldsToModify.add("aPrimitiveByte");
      fieldsToModify.add("aPrimitiveChar");
      fieldsToModify.add("aPrimitiveBoolean");
      fieldsToModify.add("aByteArray");
      fieldsToModify.add("aString");
   numFieldsToModify = (numFieldsToModify % fieldsToModify.size()) + 1;
   while (fieldsToModify.size() > numFieldsToModify) {
      int randInt = TestConfig.tab().getRandGen().nextInt(0, fieldsToModify.size()-1);
      fieldsToModify.remove(randInt);
   }
   int value = TestConfig.tab().getRandGen().nextInt(1, 100);
   boolean setDeltaBooleans = (value <= 80);
   Log.getLogWriter().info("Modifying the following " + numFieldsToModify + " fields: " + 
       fieldsToModify + " in " + anObj.toStringFull() + "; setting delta booleans: " +
       setDeltaBooleans);

   // modify fields
   synchronized (anObj) {
      if (fieldsToModify.contains("aPrimitiveLong")) {
         anObj.aPrimitiveLong = anObj.aPrimitiveLong+1;   
         if (setDeltaBooleans) {
            anObj.aPrimitiveLongChanged = true;
         }
      }
      if (fieldsToModify.contains("aPrimitiveInt")) {
         anObj.aPrimitiveInt = anObj.aPrimitiveInt+1;   
         if (setDeltaBooleans) {
            anObj.aPrimitiveIntChanged = true;
         }
      }
      if (fieldsToModify.contains("aPrimitiveShort")) {
         anObj.aPrimitiveShort = (short)(anObj.aPrimitiveShort + (short)1);   
         if (setDeltaBooleans) {
            anObj.aPrimitiveShortChanged = true;
         }
      }
      if (fieldsToModify.contains("aPrimitiveFloat")) {
         anObj.aPrimitiveFloat = anObj.aPrimitiveFloat+1;   
         if (setDeltaBooleans) {
            anObj.aPrimitiveFloatChanged = true;
         }
      }
      if (fieldsToModify.contains("aPrimitiveDouble")) {
         anObj.aPrimitiveDouble = anObj.aPrimitiveDouble+1;   
         if (setDeltaBooleans) {
            anObj.aPrimitiveDoubleChanged = true;
         }
      }
      if (fieldsToModify.contains("aPrimitiveByte")) {
         anObj.aPrimitiveByte = (byte)(anObj.aPrimitiveByte + (byte)1);   
         if (setDeltaBooleans) {
            anObj.aPrimitiveByteChanged = true;
         }
      }
      if (fieldsToModify.contains("aPrimitiveChar")) {
         anObj.aPrimitiveChar = (char)(((byte)anObj.aPrimitiveChar)+1);   
         if (setDeltaBooleans) {
            anObj.aPrimitiveCharChanged = true;
         }
      }
      if (fieldsToModify.contains("aPrimitiveBoolean")) {
         anObj.aPrimitiveBoolean = !anObj.aPrimitiveBoolean;
         if (setDeltaBooleans) {
            anObj.aPrimitiveBooleanChanged = true;
         }
      }
      if (fieldsToModify.contains("aByteArray")) {
         anObj.aByteArray = new byte[anObj.aByteArray.length + 1];
         if (setDeltaBooleans) {
            anObj.aByteArrayChanged = true;
         }
      }
      if (fieldsToModify.contains("aString")) {
         anObj.aString = "" + ((Integer.valueOf(anObj.aString)).intValue() + 1);
         if (setDeltaBooleans) {
            anObj.aStringChanged = true;
         }
      }
   }
   Log.getLogWriter().info("Done modifying, DeltaObject is now " + anObj.toStringFull());
   return setDeltaBooleans;
}

/** Update tracking of updated keys to be used for later validation
 */
protected void trackUpdate(Region aReg, Object key, boolean isDeltaDistribution) {
   // save information for later validation
   synchronized (updatedKeys) {
      updatedKeys.add(key);
      Log.getLogWriter().info("Added " + key + " to list of keys being updated");
   }
   if (isDeltaDistribution) { // fields are changing and delta booleans will be set; this put will be a delta distribution
      synchronized (updatedKeysWithDelta) {
         updatedKeysWithDelta.add(key);
         Log.getLogWriter().info("Added " + key + " to list of keys being updated with delta distribution");
      }
      if (isSerialExecution || uniqueKeys) {
         if (aReg.getAttributes().getDataPolicy().withPartitioning()) {
            Set<DistributedMember> aSet = PartitionRegionHelper.getAllMembersForKey(aReg, key);
            if (aSet.contains(CacheHelper.getCache().getDistributedSystem().getDistributedMember())) {
               if (redundantCopies == 0) {
                  if (!isBridgeConfiguration) {
                     synchronized (updatedKeysNotDistributed) {
                        // a modified key is not distributed if this is a PR and the key is hosted in this
                        // vm and there are no redundant copies and this is not a server (a server must
                        // distribute to a client)
                        updatedKeysNotDistributed.add(key);
                        Log.getLogWriter().info("Added " + key + " to list of updated keys not distributed");
                     }
                  }
               }
            }
         }
      }
   } 
}

/** Check for any errors written to the bb
 */
protected static void checkForError() {
   Object error = DeltaPropagationBB.getBB().getSharedMap().get(DeltaPropagationBB.ErrorKey);
   if (error != null) {
      throw new TestException(error.toString());
   }
}

/** Logs a Collection of keys, inserting line breaks for large key lists.
 */
protected static String keysToString(Collection listOfKeys) {
   final int keysPerLine = 10; 
   StringBuffer aStr = new StringBuffer();
   int currNumber = 0;
   for (Object key: listOfKeys) {
      aStr.append(key);
      currNumber++;
      if (currNumber == keysPerLine) {
         aStr.append("\n");
         currNumber = 0;
      } else {
         aStr.append(" ");
      }
   }
   return aStr.toString();
}

/** Return true if a delta put in this vm is the case where a warning is logged.
 *  That case is if fromDelta gets an exception and the put originated in a server
 *  and the server had only clients to distribute to (no peers). In other words,
 *  a server to client distribution. If there are peers to distribute to, then
 *  the exception will be returned to the putter.
 */
protected boolean isBadDeltaWarningCase(int testCase) {
   boolean badDeltaCausesRemoteException = (testCase == BadDeltaObject.fromDeltaThrowsException) ||
      (testCase == BadDeltaObject.fromDeltaThrowsError) || (testCase == BadDeltaObject.fromDeltaThrowsIOException) ||
      (testCase == BadDeltaObject.fromDeltaReadTooMuch) || (testCase == BadDeltaObject.toDeltaWriteNothing);
   if (!badDeltaCausesRemoteException) {
      return false;
   }
   if (isBridgeConfiguration && !isBridgeClient) { // this is a server
      if (aRegion.getAttributes().getDataPolicy().withPartitioning()) { // is PR
         int rc = aRegion.getAttributes().getPartitionAttributes().getRedundantCopies();
         if (rc == 0) {
            return true; // we only have clients to distribute to
         } else {
            return false; // we have redundantCopies to distribute to
         }
      } else { // is replicate
         int numServers = TestConfig.tab().intAt(DeltaPropagationPrms.numServers);
         if (numServers == 1) {
            return true; // we only have clients to distribute to
         } else {
            return false; // we have other replicated regions to distribute to
         }
      }
   } else { // either is peer configuration or is bridge server configuration and this is a client
      return false;
   }
}

/** Log primary and secondary members for the key and value for this event
 */
public static void logPRMembers(Region reg, Object key) {
   if (reg.getAttributes().getDataPolicy().withPartitioning()) {
      Log.getLogWriter().info("For key " + key + " in region " + reg.getFullPath() +
         " primaryMember: " + PartitionRegionHelper.getPrimaryMemberForKey(reg, key) + 
         " redundantMembers: " + PartitionRegionHelper.getRedundantMembersForKey(reg, key));
   }
}

}
