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
package hct;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import cq.CQUtil;
import hydra.*;
import util.*;

public class RandInterestPolicyTest {
    
/* The singleton instance of RandInterestPolicyTest in this VM */
static protected RandInterestPolicyTest testInstance;
    
// operations
static protected final int ENTRY_ADD_OPERATION = 1;
static protected final int ENTRY_DESTROY_OPERATION = 2;
static protected final int ENTRY_INVALIDATE_OPERATION = 3;
static protected final int ENTRY_LOCAL_DESTROY_OPERATION = 4;
static protected final int ENTRY_LOCAL_INVALIDATE_OPERATION = 5;
static protected final int ENTRY_UPDATE_OPERATION = 6;
static protected final int ENTRY_GET_OPERATION = 7;
static protected final int ENTRY_GET_NEW_OPERATION = 8;
static protected final int PUT_IF_ABSENT_OPERATION = 9;
static protected final int REMOVE_OPERATION = 10;
static protected final int REPLACE_OPERATION = 11;
    
static protected final String REGION_NAME = "InterestPolicyRegion";

// instance fields
protected long minTaskGranularitySec;       // the task granularity in seconds
protected long minTaskGranularityMS;        // the task granularity in milliseconds
protected int numOpsPerTask;                // the number of operations to execute per task
protected boolean isSerialExecution;        // true if this test is serial, false otherwise
protected int totalNumThreadsInTest;        // the total number of client threads in this test (across all VMs)
protected RandomValues randomValues = new RandomValues();        // instance of random values, used as the value for puts
protected Region aRegion;                   // the region for this client
protected int upperThreshold;               // value of HctPrms.upperThreshold
protected int lowerThreshold;               // value of HctPrms.lowerThreshold
protected int concurrentLeaderTid;          // the thread id of the concurrent leader
protected int secondsToRun;                 // number of seconds to allow tasks
protected volatile long taskStartTime;      // the starting time for tasks, used with secondsToRun

// instance fields used to verify the contents of a region 
protected Map regionSnapshot;               // a "picture" of the region
protected Set destroyedKeys;                // a set of destroyed keys
protected boolean useOwnKeys;               // if true, each thread uses a unique set of keys so as not
                                            // to do random ops that conflict with other threads, false
                                            // otherwise; we can do different validation if each thread
                                            // is using a unique set of keys
protected static volatile MethodCoordinator registerInterestCoordinator = null;
public HydraThreadLocal ownKeysIndex = new HydraThreadLocal();

// String prefixes for event callback object
protected static final String getCallbackPrefix = "Get originated in pid ";
protected static final String createCallbackPrefix = "Create event originated in pid ";
protected static final String updateCallbackPrefix = "Update event originated in pid ";
protected static final String invalidateCallbackPrefix = "Invalidate event originated in pid ";
protected static final String destroyCallbackPrefix = "Destroy event originated in pid ";
protected static final String regionInvalidateCallbackPrefix = "Region invalidate event originated in pid ";
protected static final String regionDestroyCallbackPrefix = "Region destroy event originated in pid ";
    
// used for comparing objects in verification methods
// exact means objects must be .equal()
// equivalent, which applies only to ValueHolders, means that the myValue field
//    in a ValueHolder can be the equals after doing toString(), but not necessarily equals()
public static int EQUAL = 1;
public static int EQUIVALENT = 2;

// ========================================================================
// initialization methods
    
/** Creates and initializes the singleton instance of RandInterestPolicyTest 
 *  in this VM.
 */
public synchronized static void HydraTask_initialize() {
   if (testInstance == null) {
      testInstance = new RandInterestPolicyTest();
      testInstance.initializeInstance();
   }
   testInstance.ownKeysIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}
    
/**
 * Initializes the test region in the cache server VM
 */
public static synchronized void HydraTask_initBridgeServer() {
   if (testInstance == null) {
      CacheHelper.createCache(ConfigPrms.getCacheConfig());
      RegionHelper.createRegion(REGION_NAME, ConfigPrms.getRegionConfig());
      BridgeHelper.startBridgeServer(ConfigPrms.getBridgeConfig());
      testInstance = new RandInterestPolicyTest();
   }
   testInstance.ownKeysIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}

/**
 *  Initialize this test instance
 */
protected void initializeInstance() {
   // create cache and region
   CacheHelper.createCache(ConfigPrms.getCacheConfig());
   createRegion();

   // create CQ, if needed (CQUtil will only create if specified by CQUtilPrms)
   CQUtil.initialize();
   CQUtil.initializeCQService();
   CQUtil.registerCQ(RegionHelper.getRegion(REGION_NAME));

   // initialize parameters
   minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, Long.MAX_VALUE);
   if (minTaskGranularitySec == Long.MAX_VALUE) {
      minTaskGranularityMS = Long.MAX_VALUE;
   } else {
      minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
   }
   numOpsPerTask = TestConfig.tab().intAt(HctPrms.numOpsPerTask, Integer.MAX_VALUE);
   isSerialExecution = TestConfig.tab().booleanAt(hydra.Prms.serialExecution);
   totalNumThreadsInTest = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   upperThreshold = TestConfig.tab().intAt(HctPrms.upperThreshold, Integer.MAX_VALUE);
   lowerThreshold = TestConfig.tab().intAt(HctPrms.lowerThreshold, -1);
   secondsToRun = TestConfig.tab().intAt(HctPrms.secondsToRun, 1800);
   useOwnKeys = TestConfig.tab().booleanAt(HctPrms.useOwnKeys);
   concurrentLeaderTid = -1;
   Log.getLogWriter().info("minTaskGranularitySec " + minTaskGranularitySec + ", " +
                           "minTaskGranularityMS " + minTaskGranularityMS + ", " +
                           "numOpsPerTask " + numOpsPerTask + ", " +
                           "useOwnKeys " + useOwnKeys + ", " +
                           "secondsToRun " + secondsToRun + ", " +
                           "isSerialExecution " + isSerialExecution + ", " +
                           "upperThreshold " + upperThreshold + ", " +
                           "lowerThreshold " + lowerThreshold + ", " +
                           "totalNumThreadsInTest " + totalNumThreadsInTest);
   regionSnapshot = new HashMap();
   destroyedKeys = new HashSet();
}

/** Create the local scope region for bridge clients.
 */
protected void createRegion() {
  aRegion = RegionHelper.createRegion(REGION_NAME, ConfigPrms.getRegionConfig());
  Log.getLogWriter().info("After creating region, region size is " + aRegion.keys().size());
}

// ========================================================================
// hydra task methods

/** Hydra task method for serial round robin tests.
 */
public static void HydraTask_doRROpsAndVerify() {
   testInstance.doRROpsAndVerify();
}

/** Hydra task method for concurrent tests with verification.
 *  Register interest occurs on a non-empty region.
 */
public static void HydraTask_doConcOpsAndVerify() {
   boolean registerWithEmptyRegion = TestConfig.tab().booleanAt(HctPrms.registerWithEmptyRegion);
   testInstance.doConcOpsAndVerify(registerWithEmptyRegion);
}

/** Hydra task method to do random entry operations.
 */
public static void HydraTask_doEntryOps() {
   testInstance.doEntryOperations(testInstance.aRegion);
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
 *  To summarize, the first thread in the round does this:
 *     execute ops
 *     record region state to blackboard
 *     close and reopen region
 *  The middle threads in the round do this:
 *     (these threads start from a region of size 0, no interest registered)
 *     register interest in all keys
 *     verify the state of the region against the blackboard
 *     close and reopen region 
 *  The last thread in the round does this:
 *     (this thread start from a region of size 0, no interest registered)
 *     register interest in all keys
 *     verify the state of the region against the blackboard
 *     execute ops
 *     record region state to blackboard
 *     close and reopen region
 */
protected void doRROpsAndVerify() {
   logExecutionNumber();
   long roundPosition = BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.RoundPosition);
   Log.getLogWriter().info("In doRROpsAndVerify, roundPosition is " + roundPosition);
   if (roundPosition == totalNumThreadsInTest) { // this is the last in the round
      Log.getLogWriter().info("In doRROpsAndVerify, last in round");
      verifySize(aRegion, 0);
      registerInterest();
      verifyFromSnapshot();

      // now become the first in the round 
      BBoard.getInstance().getSharedCounters().zero(BBoard.RoundPosition);
      roundPosition = BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.RoundPosition);
   }

   if (roundPosition == 1) { // first in round, do random ops
      long roundNumber = BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.RoundNumber);
      Log.getLogWriter().info("In doRROpsAndVerify, first in round, round number " + roundNumber);
      doEntryOperations(aRegion);

      // write the expected region state to the blackboard
      Log.getLogWriter().info("Writing regionSnapshot to blackboard, snapshot size is " + regionSnapshot.size() + ": " + regionSnapshot);
      BBoard.getInstance().getSharedMap().put(BBoard.RegionSnapshot, regionSnapshot); 
      Log.getLogWriter().info("Writing destroyedKeys to blackboard: " + destroyedKeys);
      BBoard.getInstance().getSharedMap().put(BBoard.DestroyedKeys, destroyedKeys); 
      SilenceListener.waitForSilence(30, 1000);
   } else if (roundPosition != totalNumThreadsInTest) { // neither first nor last
      Log.getLogWriter().info("In doRROpsAndVerify, neither first not last");
      verifySize(aRegion, 0);
      registerInterest();
      verifyFromSnapshot();
   }
   closeAndOpenRegion();
}

/** Do random operations and verification for concurrent tests.
 * 
 */
protected void doConcOpsAndVerify(boolean registerWithEmptyRegion) {
   // wait for all threads to be ready to do this task, then do random ops
   registerInterestCoordinator = new MethodCoordinator(RandInterestPolicyTest.class.getName(), "registerInterest");
   BBoard.getInstance().getSharedCounters().increment(BBoard.ReadyToBegin);
   TestHelper.waitForCounter(BBoard.getInstance(), 
                             "BBoard.ReadyToBegin", 
                             BBoard.ReadyToBegin, 
                             totalNumThreadsInTest, 
                             true, 
                             -1,
                             1000);
   checkForLastIteration();

   // Log this task is starting
   if (BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.ConcurrentLeader) == 1) {
      logExecutionNumber();
      concurrentLeaderTid = RemoteTestModule.getCurrentThread().getThreadId();
   }
   Log.getLogWriter().info("In doConcOpsAndVerify, concurrentLeaderTid is " + concurrentLeaderTid);

   Log.getLogWriter().info("Zeroing ShapshotWritten");
   BBoard.getInstance().getSharedCounters().zero(BBoard.SnapshotWritten);
   BBoard.getInstance().getSharedCounters().zero(BBoard.ReadyToVerify);

   if (registerWithEmptyRegion) { 
      // we want an empty region when registerInterest occurs; don't do ops
      // the region is empty becaue we close and reopen the region before this task ends
      // so just verify that it is empty
      verifySize(aRegion, 0);
      Log.getLogWriter().info("Verified that region has 0 entries");
      registerInterestCoordinator.executeOnce(this, new Object[0]);
      if (!registerInterestCoordinator.methodWasExecuted()) {
         throw new TestException("Test problem: RegisterInterest did not execute");
      }
      BBoard.getInstance().getSharedCounters().increment(BBoard.DoneRegisterInterest);
      TestHelper.waitForCounter(BBoard.getInstance(), 
                                "BBoard.DoneRegisterInterest", 
                                BBoard.DoneRegisterInterest, 
                                totalNumThreadsInTest, 
                                true, 
                                -1,
                                1000);
   } else { // we don't want an empty region when registerInterest occurs; do some ops
      doEntryOperations(aRegion);
      registerInterestCoordinator.executeOnce(this, new Object[0]);
      if (!registerInterestCoordinator.methodWasExecuted()) {
         throw new TestException("Test problem: RegisterInterest did not execute");
      }
   }
   doEntryOperations(aRegion);

   // wait for all threads to pause, then do the verify
   Log.getLogWriter().info("Zeroing FinishedVerify");
   BBoard.getInstance().getSharedCounters().zero(BBoard.FinishedVerify);
   BBoard.getInstance().getSharedCounters().increment(BBoard.Pausing);
   TestHelper.waitForCounter(BBoard.getInstance(), 
                             "BBoard.Pausing", 
                             BBoard.Pausing, 
                             totalNumThreadsInTest, 
                             true, 
                             -1,
                             5000);
   Log.getLogWriter().info("Zeroing ReadyToBegin, ConcurrentLeader");
   BBoard.getInstance().getSharedCounters().zero(BBoard.ReadyToBegin);
   BBoard.getInstance().getSharedCounters().zero(BBoard.ConcurrentLeader);

   BBoard.getInstance().getSharedCounters().increment(BBoard.ReadyToVerify);
   TestHelper.waitForCounter(BBoard.getInstance(), 
                             "BBoard.ReadyToVerify", 
                             BBoard.ReadyToVerify, 
                             totalNumThreadsInTest, 
                             true, 
                             -1,
                             1000);
   BBoard.getInstance().getSharedCounters().zero(BBoard.DoneRegisterInterest);
   SilenceListener.waitForSilence(30, 1000);
   concVerify();   

   // wait for everybody to finish verify, then exit
   BBoard.getInstance().getSharedCounters().increment(BBoard.FinishedVerify);
   TestHelper.waitForCounter(BBoard.getInstance(), 
                             "BBoard.FinishedVerify", 
                             BBoard.FinishedVerify, 
                             totalNumThreadsInTest, 
                             true, 
                             -1,
                             1000);
   closeAndOpenRegion();
   Log.getLogWriter().info("Zeroing concurrentLeaderTid, Pausing");
   concurrentLeaderTid = -1;
   BBoard.getInstance().getSharedCounters().zero(BBoard.Pausing);

   long counter = BBoard.getInstance().getSharedCounters().read(BBoard.TimeToStop);
   if (counter >= 1)
      throw new StopSchedulingOrder("Num executions is " + 
            BBoard.getInstance().getSharedCounters().read(BBoard.ExecutionNumber));
}

/** Do random entry operations on the given region ending either with
 *  minTaskGranularityMS or numOpsPerTask.
 *  Uses HctPrms.entryOperations to determine the operations to execute.
 */
protected void doEntryOperations(Region aRegion) {
   Log.getLogWriter().info("In doEntryOperations with " + aRegion.getFullPath());
   long startTime = System.currentTimeMillis();
   int numOps = 0;

   // useTransactions() defaults to false
   boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();
   boolean rolledback;

   do {
      int whichOp = getOperation(HctPrms.entryOperations);
      int size = aRegion.keys().size();
      Log.getLogWriter().info("Selecting operation, region size is " + size);
      if (size >= upperThreshold) {
         whichOp = getOperation(HctPrms.upperThresholdOperations);
      } else if (size <= lowerThreshold) {
         whichOp = getOperation(HctPrms.lowerThresholdOperations);
      }

      rolledback = false;
      if (useTransactions) {
        TxHelper.begin();
      }

      try {

         switch (whichOp) {
            case ENTRY_ADD_OPERATION:
               addEntry(aRegion);
               break;
            case ENTRY_INVALIDATE_OPERATION:
               invalidateEntry(aRegion, false);
               break;
            case ENTRY_DESTROY_OPERATION:
               destroyEntry(aRegion, false);
               break;
            case ENTRY_UPDATE_OPERATION:
               updateEntry(aRegion);
               break;
            case ENTRY_GET_OPERATION:
               getKey(aRegion);
               break;
            case ENTRY_GET_NEW_OPERATION:
               getNewKey(aRegion);
               break;
            case ENTRY_LOCAL_INVALIDATE_OPERATION:
               invalidateEntry(aRegion, true);
               break;
            case ENTRY_LOCAL_DESTROY_OPERATION:
               destroyEntry(aRegion, true);
               break;
            case PUT_IF_ABSENT_OPERATION:
               putIfAbsent(aRegion);
               break;
            case REMOVE_OPERATION:
               remove(aRegion);
               break;
            case REPLACE_OPERATION:
               replace(aRegion);
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
          throw new TestException("Unexpected Exception " + e + ". " + TestHelper.getStackTrace(e));
        } else {
          Log.getLogWriter().info("Caught Exception " + e + " .  Expected with concurrent execution, continuing test.");
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
      Log.getLogWriter().info("Completed op " + numOps + " for this task");
   } while ((System.currentTimeMillis() - startTime < minTaskGranularityMS) &&
            (numOps < numOpsPerTask));
}
        
/** Add a new entry to the given region.
 *
 *  @param aRegion The region to use for adding a new entry.
 *
 *  @returns The key that was added.
 */
protected Object addEntry(Region aRegion) {
   Object key = getNewKey();
   BaseValueHolder anObj = getValueForKey(key);
   String callback = createCallbackPrefix + ProcessMgr.getProcessId();
   int beforeSize = aRegion.keys().size();
   if (TestConfig.tab().getRandGen().nextBoolean()) { // use a create call
      if (TestConfig.tab().getRandGen().nextBoolean()) { // use a create call with cacheWriter arg
         try {
            Log.getLogWriter().info("addEntry: calling create for key " + key + ", object " +
               TestHelper.toString(anObj) + " cacheWriterParam is " + callback + ", region is " + 
               aRegion.getFullPath());
            aRegion.create(key, anObj, callback);
            Log.getLogWriter().info("addEntry: done creating key " + key);
         } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         } catch (CacheWriterException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         //} catch (CapacityControllerException e) {
         //   throw new TestException(TestHelper.getStackTrace(e));
         } catch (EntryExistsException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         }
      } else { // use create with no cacheWriter arg
         try {
            Log.getLogWriter().info("addEntry: calling create for key " + key + ", object " +
               TestHelper.toString(anObj) + ", region is " + aRegion.getFullPath());
            aRegion.create(key, anObj);
            Log.getLogWriter().info("addEntry: done creating key " + key);
         } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         } catch (CacheWriterException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         //} catch (CapacityControllerException e) {
         //   throw new TestException(TestHelper.getStackTrace(e));
         } catch (EntryExistsException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         }
      }
   } else { // use a put call
      if (TestConfig.tab().getRandGen().nextBoolean()) { // use a put call with callback arg
         Log.getLogWriter().info("addEntry: calling put for key " + key + ", object " +
               TestHelper.toString(anObj) + " callback is " + callback + ", region is " + aRegion.getFullPath());
         try {
            aRegion.put(key, anObj, callback);
         } catch (CacheWriterException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         //} catch (CapacityControllerException e) {
         //   throw new TestException(TestHelper.getStackTrace(e));
         } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         }
         Log.getLogWriter().info("addEntry: done putting key " + key);
      } else {
         Log.getLogWriter().info("addEntry: calling put for key " + key + ", object " +
               TestHelper.toString(anObj) + ", region is " + aRegion.getFullPath());
         try {
            aRegion.put(key, anObj);
         } catch (CacheWriterException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         //} catch (CapacityControllerException e) {
         //   throw new TestException(TestHelper.getStackTrace(e));
         } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         }
         Log.getLogWriter().info("addEntry: done putting key " + key);
      }
   }
   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, true);
      verifyContainsValueForKey(aRegion, key, true);
      verifySize(aRegion, beforeSize+1);
      regionSnapshot.put(key, anObj.myValue);
      destroyedKeys.remove(key);
   }
   return key;
}
    
/** Invalidate an entry in the given region.
 *
 *  @param aRegion The region to use for invalidating an entry.
 *  @param isLocalInvalidate True if the invalidate should be local, false otherwise.
 */
protected void invalidateEntry(Region aRegion, boolean isLocalInvalidate) {
   int beforeSize = aRegion.keys().size();
   Object key = getExistingKey(aRegion);
   if (key == null) {
      Log.getLogWriter().info("invalidateEntry: No keys in region");
      return;
   }
   boolean containsKey = aRegion.containsKey(key);
   boolean containsValueForKey = aRegion.containsValueForKey(key);
   Log.getLogWriter().info("containsKey for " + key + ": " + containsKey);
   Log.getLogWriter().info("containsValueForKey for " + key + ": " + containsValueForKey);
   try {
      String callback = invalidateCallbackPrefix + ProcessMgr.getProcessId();
      if (isLocalInvalidate) { // do a local invalidate
         if (TestConfig.tab().getRandGen().nextBoolean()) { // local invalidate with callback
            Log.getLogWriter().info("invalidateEntry: local invalidate for " + key + " callback is " + callback);
            aRegion.localInvalidate(key, callback);
            Log.getLogWriter().info("invalidateEntry: done with local invalidate for " + key);
         } else { // local invalidate without callback
            Log.getLogWriter().info("invalidateEntry: local invalidate for " + key);
            aRegion.localInvalidate(key);
            Log.getLogWriter().info("invalidateEntry: done with local invalidate for " + key);
         }
      } else { // do a distributed invalidate
         if (TestConfig.tab().getRandGen().nextBoolean()) { // invalidate with callback
            Log.getLogWriter().info("invalidateEntry: invalidating key " + key + " callback is " + callback);
            aRegion.invalidate(key, callback);
            Log.getLogWriter().info("invalidateEntry: done invalidating key " + key);
         } else { // invalidate without callback
            Log.getLogWriter().info("invalidateEntry: invalidating key " + key);
            aRegion.invalidate(key);
            Log.getLogWriter().info("invalidateEntry: done invalidating key " + key);
         }
      }
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      if (isSerialExecution) {
         throw new TestException(TestHelper.getStackTrace(e));
      } else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         return;
      }
   }
   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, true);
      verifyContainsValueForKey(aRegion, key, false);
      verifySize(aRegion, beforeSize);
      if (!isLocalInvalidate) {
         regionSnapshot.put(key, null);
         destroyedKeys.remove(key);
      }
   }
}
    
/** Destroy an entry in the given region.
 *
 *  @param aRegion The region to use for destroying an entry.
 *  @param isLocalDestroy True if the destroy should be local, false otherwise.
 */
protected void destroyEntry(Region aRegion, boolean isLocalDestroy) {
   Object key = getExistingKey(aRegion);
   if (key == null) {
      Log.getLogWriter().info("destroyEntry: No keys in region");
      return;
   }
   int beforeSize = aRegion.keys().size();
   try {
      String callback = destroyCallbackPrefix + ProcessMgr.getProcessId();
      if (isLocalDestroy) { // do a local destroy
         if (TestConfig.tab().getRandGen().nextBoolean()) { // local destroy with callback
            Log.getLogWriter().info("destroyEntry: local destroy for " + key + " callback is " + callback);
            aRegion.localDestroy(key, callback);
            Log.getLogWriter().info("destroyEntry: done with local destroy for " + key);
         } else { // local destroy without callback
            Log.getLogWriter().info("destroyEntry: local destroy for " + key);
            aRegion.localDestroy(key);
            Log.getLogWriter().info("destroyEntry: done with local destroy for " + key);
         }
      } else { // do a distributed destroy
         if (TestConfig.tab().getRandGen().nextBoolean()) { // destroy with callback
            Log.getLogWriter().info("destroyEntry: destroying key " + key + " callback is " + callback);
            aRegion.destroy(key, callback);
            Log.getLogWriter().info("destroyEntry: done destroying key " + key);
         } else { // destroy without callback
            Log.getLogWriter().info("destroyEntry: destroying key " + key);
            aRegion.destroy(key);
            Log.getLogWriter().info("destroyEntry: done destroying key " + key);
         }
      }
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (CacheWriterException e) {
      if (isSerialExecution || useOwnKeys) {
         throw new TestException(TestHelper.getStackTrace(e));
      }
      if (e instanceof com.gemstone.gemfire.cache.util.BridgeWriterException) {
         if (e.toString().indexOf(EntryNotFoundException.class.getName()) >= 0) {
            Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
            return;
         } else {
            throw new TestException(TestHelper.getStackTrace(e));
         }
      }
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      if (isSerialExecution || useOwnKeys) {
         throw new TestException(TestHelper.getStackTrace(e));
      } else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         return;
      }
   }
   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, false);
      verifyContainsValueForKey(aRegion, key, false);
      verifySize(aRegion, beforeSize-1);
      if (!isLocalDestroy) {
         regionSnapshot.remove(key);
         destroyedKeys.add(key);
      }
   }
}

/** Add a new entry to the given region via putIfAbsent
 *
 *  @param aRegion The region to use for adding a new entry.
 *
 *  @returns The key that was added.
 */
protected Object putIfAbsent(Region aRegion) {
   Object key = getNewKey(); // guaranteed to be a new key in the test
   BaseValueHolder anObj = getValueForKey(key);
   int beforeSize = aRegion.keys().size();

   try {
      Log.getLogWriter().info("putIfAbsent: creating key " + key + ", object " + TestHelper.toString(anObj) + ", region is " + aRegion.getFullPath());
      Object retVal = aRegion.putIfAbsent(key, anObj);
      Log.getLogWriter().info("putIfAbsent: done creating key " + key);

      if (retVal != null) {
        Log.getLogWriter().info("putIfAbsent for key " + key + " expected successful operation (with return value of null), but entry already exists with value " + retVal);
        throw new TestException(TestHelper.getStackTrace());
      }
   } catch (Exception e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } 

   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, true);
      verifyContainsValueForKey(aRegion, key, true);
      verifySize(aRegion, beforeSize+1);
      regionSnapshot.put(key, anObj.myValue);
      destroyedKeys.remove(key);
   }
   return key;
}

/** Replace an existing entry in the given region. If there are
 *  no available keys in the region, then this is a noop.
 *
 *  @param aRegion The region to use for updating an entry.
 */
protected void replace(Region aRegion) {
   Object key = getExistingKey(aRegion);
   if (key == null) {
      Log.getLogWriter().info("replace: No keys in region");
      return;
   }
   int beforeSize = aRegion.keys().size();
   BaseValueHolder anObj = getUpdateObject(aRegion, (String)key);
      Log.getLogWriter().info("Replacing key " + key);
      Object retVal = aRegion.replace(key, anObj);
      Log.getLogWriter().info("Done with call to replace " + key + " with return value " + retVal);

   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, true);
      verifyContainsValueForKey(aRegion, key, true);
      verifySize(aRegion, beforeSize);
      regionSnapshot.put(key, anObj.myValue);
      destroyedKeys.remove(key);
   }
}

/** Remove an entry from the given region.
 *
 *  @param aRegion The region to use for removing an entry.
 */
protected void remove(Region aRegion) {
   Object key = getExistingKey(aRegion);
   if (key == null) {
      Log.getLogWriter().info("remove: No keys in region");
      return;
   }
   int beforeSize = aRegion.keys().size();
   try {
      Log.getLogWriter().info("remove: removing key " + key);
      aRegion.remove(key, aRegion.get(key));
      Log.getLogWriter().info("remove: done removing key " + key);
   } catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      if (isSerialExecution || useOwnKeys) {
         throw new TestException(TestHelper.getStackTrace(e));
      } else {
         Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
         return;
      }
   }

   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, false);
      verifyContainsValueForKey(aRegion, key, false);
      verifySize(aRegion, beforeSize-1);
      regionSnapshot.remove(key);
      destroyedKeys.add(key);
   }
}

/** Update an existing entry in the given region. If there are
 *  no available keys in the region, then this is a noop.
 *
 *  @param aRegion The region to use for updating an entry.
 */
protected void updateEntry(Region aRegion) {
   Object key = getExistingKey(aRegion);
   if (key == null) {
      Log.getLogWriter().info("updateEntry: No keys in region");
      return;
   }
   int beforeSize = aRegion.keys().size();
   BaseValueHolder anObj = getUpdateObject(aRegion, (String)key);
   String callback = updateCallbackPrefix + ProcessMgr.getProcessId();
   if (TestConfig.tab().getRandGen().nextBoolean()) { // do a put with callback arg
      Log.getLogWriter().info("updateEntry: replacing key " + key + " with " +
         TestHelper.toString(anObj) + ", callback is " + callback);
      try {
         aRegion.put(key, anObj, callback);
      } catch (CacheWriterException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      //} catch (CapacityControllerException e) {
      //   throw new TestException(TestHelper.getStackTrace(e));
      } catch (TimeoutException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      }
      Log.getLogWriter().info("Done with call to put (update)");
   } else { // do a put without callback
      Log.getLogWriter().info("updateEntry: replacing key " + key + " with " + TestHelper.toString(anObj));
      try {
         aRegion.put(key, anObj);
      //} catch (CapacityControllerException e) {
      //   throw new TestException(TestHelper.getStackTrace(e));
      } catch (CacheWriterException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      } catch (TimeoutException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      }
      Log.getLogWriter().info("Done with call to put (update)");
   }
   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, true);
      verifyContainsValueForKey(aRegion, key, true);
      verifySize(aRegion, beforeSize);
      regionSnapshot.put(key, anObj.myValue);
      destroyedKeys.remove(key);
   }
}
    
/** Get an existing key in the given region if one is available,
 *  otherwise get a new key. 
 *
 *  @param aRegion The region to use for getting an entry.
 */
protected void getKey(Region aRegion) {
   Object key = getExistingKey(aRegion);
   if (key == null) { // no existing keys; get a new key then
      getNewKey(aRegion);
      return;
   }
   String callback = getCallbackPrefix + ProcessMgr.getProcessId();
   int beforeSize = aRegion.keys().size();
   boolean beforeContainsValueForKey = aRegion.containsValueForKey(key);
   Object anObj;
   try {
      if (TestConfig.tab().getRandGen().nextBoolean()) { // get with callback
         Log.getLogWriter().info("getKey: getting key " + key + ", callback is " + callback);
         anObj = aRegion.get(key, callback);
         Log.getLogWriter().info("getKey: got value for key " + key + ": " + TestHelper.toString(anObj));
      } else { // get without callback
         Log.getLogWriter().info("getKey: getting key " + key);
         anObj = aRegion.get(key);
         Log.getLogWriter().info("getKey: got value for key " + key + ": " + TestHelper.toString(anObj));
      }
   } catch (CacheLoaderException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, true);
      verifyContainsValueForKey(aRegion, key, (anObj != null));

      // check the expected value of the get
      Object expectedValue = regionSnapshot.get(key);
      verifyMyValue(aRegion, key, expectedValue, anObj, EQUAL);

      // record the current state
      if (anObj == null) {
         regionSnapshot.put(key, null);
      } else {
         regionSnapshot.put(key, ((BaseValueHolder)anObj).myValue);
      }
      destroyedKeys.remove(key);
   }
}
    
/** Get a new key int the given region.
 *
 *  @param aRegion The region to use for getting an entry.
 */
protected void getNewKey(Region aRegion) {
   Object key = getNewKey();
   String callback = getCallbackPrefix + ProcessMgr.getProcessId();
   int beforeSize = aRegion.keys().size();
   boolean beforeContainsValueForKey = aRegion.containsValueForKey(key);
   Object anObj;
   try {
      if (TestConfig.tab().getRandGen().nextBoolean()) { // get with callback
         Log.getLogWriter().info("getNewKey: getting new key " + key + ", callback is " + callback);
         anObj = aRegion.get(key, callback);
         Log.getLogWriter().info("getNewKey: getting value for key " + key + ": " + TestHelper.toString(anObj));
      } else { // get without callback
         Log.getLogWriter().info("getNewKey: getting key " + key);
         anObj = aRegion.get(key);
         Log.getLogWriter().info("getNewKey: got value for key " + key + ": " + TestHelper.toString(anObj));
      }
   } catch (CacheLoaderException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
   if (isSerialExecution) {
      verifyContainsKey(aRegion, key, true);
      verifyContainsValueForKey(aRegion, key, false);
      if (anObj != null)
         throw new TestException("Expected null to be returned from get of new key " + key + ", but " + 
                                 TestHelper.toString(anObj) + " was returned");
      regionSnapshot.put(key, null);
      destroyedKeys.remove(key);
   }
}
    
// ========================================================================
// other methods to help out in doing the tasks

/** Check if we have run for the desired length of time. We cannot use 
 *  hydra's taskTimeSec parameter because of a small window of opportunity 
 *  for the test to hang due to the test's "concurrent round robin" type 
 *  of strategy. Here we set a blackboard counter if time is up and this
 *  is the last concurrent round.
 */
protected void checkForLastIteration() {
   // determine if this is the last iteration
   if (taskStartTime == 0) {
      taskStartTime = System.currentTimeMillis();
      Log.getLogWriter().info("Initialized taskStartTime to " + taskStartTime);
   }
   if (System.currentTimeMillis() - taskStartTime >= secondsToRun * 1000) {
      Log.getLogWriter().info("This is the last iteration of this task");
      BBoard.getInstance().getSharedCounters().increment(BBoard.TimeToStop);
   } else {
      Log.getLogWriter().info("Running for " + secondsToRun + " seconds; time remaining is " +
         ((System.currentTimeMillis() - taskStartTime) / 1000) + " seconds");
   }
}

/** Return a value for the given key
 */
protected BaseValueHolder getValueForKey(Object key) {
   return new ValueHolder((String)key, randomValues);
}
    
/** Return a new key
 */
protected Object getNewKey() {
   if (useOwnKeys) {
      int anInt = ((Integer)(ownKeysIndex.get())).intValue(); 
      anInt += totalNumThreadsInTest;
      ownKeysIndex.set(new Integer(anInt));
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
protected Object getRecentKey(Region aRegion, int recentHistory) {
   long maxNames = NameFactory.getPositiveNameCounter();
   if (maxNames <= 0) {
      return null;
   }
   long keyIndex = TestConfig.tab().getRandGen().nextLong(
                      Math.max(maxNames-recentHistory, (long)1), 
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
protected BaseValueHolder getUpdateObject(Region aRegion, String key) {
   try {
      Object anObj = aRegion.get(key);
      if ((anObj != null) && !(anObj instanceof BaseValueHolder)) {
         throw new TestException("Getting key " + key + " returned " + TestHelper.toString(anObj) +
                   ", but an instance of ValueHolder was expected");
      }
      BaseValueHolder vh = (BaseValueHolder)anObj;
      BaseValueHolder newObj = (vh == null) ? new ValueHolder(key, randomValues) :
                                          vh.getAlternateValueHolder(randomValues);
      return newObj;
   } catch (CacheLoaderException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
}

/** Log the execution number of this serial task.
 */
static protected void logExecutionNumber() {
   long exeNum = BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.ExecutionNumber);
   Log.getLogWriter().info("Beginning task with execution number " + exeNum);
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
   } else if (operation.equals("putIfAbsent")) {
      op = PUT_IF_ABSENT_OPERATION;
   } else if (operation.equals("remove")) {
      op = REMOVE_OPERATION;
   } else if (operation.equals("replace")) {
      op = REPLACE_OPERATION;
   } else {
      throw new TestException("Unknown entry operation: " + operation);
   }
   return op;
}

// ========================================================================
// verification methods

/** Verify this thread's view of the keys and values in the region
 *  by reading the region snapshot and destroyed keys from the blackboad.
 */
protected void verifyFromSnapshot() {
   StringBuffer aStr = new StringBuffer();
   regionSnapshot = (Map)(BBoard.getInstance().getSharedMap().get(BBoard.RegionSnapshot));
   destroyedKeys = (Set)(BBoard.getInstance().getSharedMap().get(BBoard.DestroyedKeys));
   int snapshotSize = regionSnapshot.size();
   int regionSize = aRegion.keys().size();
   if (snapshotSize != regionSize) {
      aStr.append("Expected region " + aRegion.getFullPath() + " to be size " + snapshotSize + 
           ", but it is " + regionSize + "\n");
      aStr.append("  region.size() = " + aRegion.size()
          + " regionMap.size() = " + ((LocalRegion)aRegion).getRegionMap().size()
          + " tombstones = "
          + ((LocalRegion)aRegion).getTombstoneCount() + "\n");
      ((LocalRegion)aRegion).dumpBackingMap();
      aStr.append("tombstones = " + ((LocalRegion)aRegion).getGemFireCache().getTombstoneService().toString())
        .append("\n");
   }
   Log.getLogWriter().info("Verifying from snapshot containing " + snapshotSize + " entries...");
   Iterator it = regionSnapshot.entrySet().iterator();
   while (it.hasNext()) {
      Map.Entry entry = (Map.Entry)it.next();
      Object key = entry.getKey();
      Object expectedValue = entry.getValue();

      // containsKey
      try {
         verifyContainsKey(aRegion, key, true);
      } catch (TestException e) {
         aStr.append(e.getMessage() + "\n");
      }

      // containsValueForKey
      boolean containsValueForKey = aRegion.containsValueForKey(key);
      try {
         verifyContainsValueForKey(aRegion, key, (expectedValue != null));
      } catch (TestException e) {
         aStr.append(e.getMessage() + "\n");
      }

      if (containsValueForKey) { // only call get if it's already there
                                 // otherwise we could cause an event
         try {                  
            Object actualValue = aRegion.get(key);   
            verifyMyValue(aRegion, key, expectedValue, actualValue, EQUAL);
         } catch (TimeoutException e) {
            throw new TestException(TestHelper.getStackTrace(e));
         } catch (CacheLoaderException e) {
            throw new TestException(TestHelper.getStackTrace(e));
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
         verifyContainsKey(aRegion, key, false);
      } catch (TestException e) {
         aStr.append(e.getMessage());
      }
   }

   // check for extra keys in region that were not in the snapshot
   Set snapshotKeys = regionSnapshot.keySet();
   Set regionKeys = new HashSet(aRegion.keys());
   regionKeys.removeAll(snapshotKeys);
   if (regionKeys.size() != 0) {
      aStr.append("Found the following unexpected keys in " + aRegion.getFullPath() +
                  ": " + regionKeys + "\n");
   }

   if (aStr.length() > 0)
      throw new TestException(aStr.toString());
   Log.getLogWriter().info("Done verifying from snapshot containing " + snapshotSize + " entries...");
}

/** 
 */
protected void concVerify() {
   int myTid = RemoteTestModule.getCurrentThread().getThreadId();
   Log.getLogWriter().info("In concVerify, with myTid " + myTid + ", concurrentLeaderTid is " + concurrentLeaderTid);
   if (myTid == concurrentLeaderTid) { 
      // this is the first thread to verify; all other threads will wait for this thread to
      // write its view of the region to the blackboard and they will read it and match it
      regionSnapshot = new HashMap();
      destroyedKeys = new HashSet();
      Log.getLogWriter().info("This thread is the concurrentLeader, creating region snapshot..."); 
      Set keySet = aRegion.keys();
      Iterator it = keySet.iterator();
      if (!it.hasNext()) {
         throw new TestException("Unexpected region size " + aRegion.keys().size());
      } 
      while (it.hasNext()) {
          try {
             Object key = it.next();
             Object value = aRegion.get(key);
             if (value instanceof BaseValueHolder) {
                regionSnapshot.put(key, ((BaseValueHolder)value).myValue);
             } else {
                regionSnapshot.put(key, value);
             }
          } catch (TimeoutException e) {
             throw new TestException(TestHelper.getStackTrace(e));
          } catch (CacheLoaderException e) {
             throw new TestException(TestHelper.getStackTrace(e));
          }
      }
      Log.getLogWriter().info("Done creating region snapshot with " + regionSnapshot.size() + " entries; snapshot is " + regionSnapshot);
      BBoard.getInstance().getSharedMap().put(BBoard.RegionSnapshot, regionSnapshot);
      Log.getLogWriter().info("Done creating destroyed keys with " + destroyedKeys.size() + " keys");
      BBoard.getInstance().getSharedMap().put(BBoard.DestroyedKeys, destroyedKeys);
      long snapshotWritten = BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.SnapshotWritten);
      Log.getLogWriter().info("Incremented SnapshotWritten, now is " + snapshotWritten);
   } else { 
      // this thread is not the first to verify; it will wait until the first thread has
      // written its state to the blackboard, then it will read it and verify that it's state matches
      TestHelper.waitForCounter(BBoard.getInstance(), 
                                "BBoard.SnapshotWritten", 
                                BBoard.SnapshotWritten, 
                                1, 
                                true, 
                                -1,
                                2000);
      verifyFromSnapshot();
   }
}

/** Verify that the size of the given region is expectedSize.
 *
 * @param aRegion The region to verify.
 * @param expectedSize The expected size of aRegion.
 *
 * @throws TestException if size() has the wrong value
 */
protected static void verifySize(Region aRegion, final int expectedSize) {
  int size = aRegion.keys().size();
  if (size != expectedSize) {
      throw new TestException("Expected size of " + aRegion.getFullPath() + " to be " +
         expectedSize + ", but it is " + size + ", keys are: " + aRegion.keys());
   }
}

/** Return a random key currently in the given region.
 *
 *  @param aRegion The region to use for getting an existing key.
 *
 *  @returns A key in the region.
 */
protected Object getExistingKey(Region aRegion) {
   int myTid = RemoteTestModule.getCurrentThread().getThreadId();
   Set aSet = aRegion.keys();
   if (aSet.size() == 0) {
      return null;
   }
   Iterator it = aSet.iterator();
   Object key = null;
   while (it.hasNext()) {
      key = it.next();
      if (useOwnKeys) {
         long keyIndex = NameFactory.getCounterForName(key);
         if ((keyIndex % totalNumThreadsInTest) == myTid) {
            return key;
         }
      } else {
         return key;
      }
   } 
   return null;
}

/** Verify containsKey for the given region and key.
 *
 * @param aRegion The region to verify.
 * @param key The key in aRegion to verify.
 * @param expected The expected value of containsKey()
 *
 * @throws TestException if containsKey() has the wrong value
 */
protected static void verifyContainsKey(Region aRegion, Object key, boolean expected) {
   boolean containsKey = aRegion.containsKey(key);
   if (containsKey != expected) {
      throw new TestException("Expected containsKey() for " + key + " to be " + expected + 
                " in " + aRegion.getFullPath() + ", but it is " + containsKey);
   }
}

/** Verify containsValueForKey for the given region and key.
 *
 * @param aRegion The region to verify.
 * @param key The key in aRegion to verify.
 * @param expected The expected value of containsKey()
 *
 * @throws TestException if containsValueforKey() has the wrong value
 */
protected static void verifyContainsValueForKey(Region aRegion, Object key, boolean expected) {
   boolean containsValueForKey = aRegion.containsValueForKey(key);
   if (containsValueForKey != expected) {
      throw new TestException("Expected containsValueForKey() for " + key + " to be " + expected + 
                " in " + aRegion.getFullPath() + ", but it is " + containsValueForKey);
   }
}

/** Verify that the given object is an instance of ValueHolder
 *  with expectedValue as the myValue field.
 *
 * @param aRegion The region to verify.
 * @param key The key in aRegion to verify.
 * @param expectedValue The expected myValue field of a ValueHolder in aRegion, or null
 *        if the expected value should be null.
 * @param valueToCheck This is expected to be a ValueHolder, whose myValue field compares
 *        to expectedValue, according to comparStrategy
 * @param compareStrategy Whether the compare is equals or equivalent (for ValueHolders)
 *
 * @throws TestException if the result of a get on key does not have the expected value.
 */
protected static void verifyMyValue(Region aRegion, Object key, Object expectedValue, Object valueToCheck, int compareStrategy) {
   if (valueToCheck == null) {
      if (expectedValue != null) {
         throw new TestException("For key " + key + ", expected myValue to be " + 
                   TestHelper.toString(expectedValue) + 
                   ", but it is " + TestHelper.toString(valueToCheck));
      }
   } else if (valueToCheck instanceof BaseValueHolder) {
      BaseValueHolder actualVH = (BaseValueHolder)valueToCheck;
      if (compareStrategy == EQUAL) {
         if (!actualVH.myValue.equals(expectedValue)) {
            throw new TestException("For key " + key + ", expected ValueHolder.myValue to be " + 
                   TestHelper.toString(expectedValue) + 
                   ", but it is " + TestHelper.toString(valueToCheck));
         }
      } else if (compareStrategy == EQUIVALENT) { 
         if (actualVH.myValue.toString().equals(expectedValue.toString())) {
            throw new TestException("For key " + key + ", expected ValueHolder.myValue to be " + 
                      expectedValue + ", but it is " + actualVH.myValue);
         }
      }
   } else {
      throw new TestException("Expected value for key " + key + " to be an instance of ValueHolder, but it is " +
         TestHelper.toString(valueToCheck));
   }
}

/** Register interest with ALL_KEYS, and policy KEYS_VALUES
 */
protected static void registerInterest() {
   InterestResultPolicy policy = InterestResultPolicy.KEYS_VALUES;
   Object keysOfInterest = "ALL_KEYS";
   try {
      Log.getLogWriter().info("Calling registerInterest with keys of interest: " +
                        TestHelper.toString(keysOfInterest) +
                        ", policy: " + policy);
      long start = System.currentTimeMillis();
      testInstance.aRegion.registerInterest(keysOfInterest, policy);
      long end = System.currentTimeMillis();
      int size = testInstance.aRegion.keys().size();
      Log.getLogWriter().info("Done calling registerInterest with keys of interest: " +
                        TestHelper.toString(keysOfInterest) +
                        ", policy: " + policy + ", time to register interest was " +
                        (end - start) + " millis, region size is " + size);
   } catch (CacheWriterException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
}

/** Close the region to prepare for the next registerInterest.
 *  Reopens the region so it will exist for the next registerInterest
 *  or ops calls. When this is done, we have a newly initialized region
 *  with no keys and no keys registered.
 */
static protected synchronized void closeAndOpenRegion() {
   if (!testInstance.aRegion.isDestroyed()) {
      Log.getLogWriter().info("Closing region " + testInstance.aRegion.getFullPath() + 
          " with " + testInstance.aRegion.keys().size() + " entries");
      testInstance.aRegion.close(); // prepare for the next time we register
      testInstance.createRegion();
   } else {
      Log.getLogWriter().info("Not closing region; it has been closed by another thread");
   }
}

}
