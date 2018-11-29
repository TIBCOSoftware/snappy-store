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
package cq;

import java.util.*;

import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.query.*;
import hydra.*;
import hydra.blackboard.SharedCounters;
import util.*;

public class CQKnownKeysTest {

// the one instance of CQKnownKeysTest
static protected CQKnownKeysTest testInstance;

// region name
protected static final String REGION_NAME = "TestRegion";

// protected fields used by the test to do its work
protected int numNewKeys;            // the number of new keys to add to the region
protected KeyIntervals keyIntervals; // the key intervals used for this test; test 
                                     // does a different operation on each interval, 
                                     // so it can be validated at the end
protected int totalNumKeys;          // the total number of keys expected in the region 
                                     // after all operations are done; includes those in 
                                     // keyIntervals and numNewKeys
protected Region aRegion;            // the single region used in this test 
protected long minTaskGranularitySec;// the task granularity in seconds
protected long minTaskGranularityMS; // the task granularity in milliseconds
protected boolean isBridgeConfiguration;    // true if this test is being run in a bridge configuration, false otherwise
protected boolean isBridgeClient;           // true if this vm is a bridge client, false otherwise
protected CQTest CQTestInstance;     // an instance of the CQTest test object
protected HydraThreadLocal uniqueKeyIndex = new HydraThreadLocal();
protected Map queryMap;              // the random queries used by this VM; keys are query names
                                     // (String) and values are queries (String)
protected int queryDepth;            // value of CQUtilPrms.queryDepth
protected QueryService qService;            // the QueryService in this VM
protected static final String VmIDStr = "VmId_";
protected static boolean CQsOn = true;
protected boolean sleepBetweenOps = false;

protected final int SLEEP_BETWEEN_OPS_MILLIS = 50;

// Used for checking the region contents in a batched task
// These variables are used to track the state of checking large regions
// for repeated batched calls. They must be reinitialized if a second check is
// called in this vm within a single test.
protected static int verifyRegionContentsIndex = 0;
protected static StringBuffer verifyRegionContentsErrStr = new StringBuffer();
protected static boolean verifyRegionContentsCompleted = false;

// Used for verifying queries in a batched task.
// These variables are used to track the state of checking a large number
// of queries in repeated batched calls. They must be reinitialized if a second 
// check is called in this vm within a single test.
protected static Iterator verifyQueryIterator = null;
protected static int verifyQueryCount = 0;

// operations to do on the region
protected static final int INVALIDATE             = 1;
protected static final int LOCAL_INVALIDATE       = 2;
protected static final int DESTROY                = 3;
protected static final int LOCAL_DESTROY          = 4;
protected static final int UPDATE_EXISTING_KEY    = 5;
protected static final int GET                    = 6;
protected static final int ADD_NEW_KEY            = 7;
protected static final int FIRST_OP               = 1;
protected static final int LAST_OP                = 7;
protected static final int operations[] = new int[] {
   INVALIDATE, LOCAL_INVALIDATE, DESTROY, LOCAL_DESTROY, UPDATE_EXISTING_KEY, GET, ADD_NEW_KEY};

// ======================================================================== 
// initialization methods 

/** Creates and initializes the singleton instance of CQTest in a client
 *  and start the CQs running.
 */
public synchronized static void HydraTask_initializeClient() {
   if (testInstance == null) {
      testInstance = new CQKnownKeysTest();
      testInstance.initializeClient(true);
   }
   testInstance.uniqueKeyIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}
    
/** Creates and initializes the singleton instance of CQTest in a client
 *  without starting the CQs running.
 */
public synchronized static void HydraTask_initializeClientWithoutStartingCQs() {
   if (testInstance == null) {
      testInstance = new CQKnownKeysTest();
      testInstance.initializeClient(false);
   }
   testInstance.uniqueKeyIndex.set(new Integer(RemoteTestModule.getCurrentThread().getThreadId())); 
}
    
public static void HydraTask_initCombineTest() {
   testInstance.sleepBetweenOps = true;
}

/** Initialize a client vm.
 *  
 *  @param startCQsRunning true if the initialize should start the CQs running
 *                         false otherwise
 */
private void initializeClient(boolean startCQsRunning) {
   initializeRegion("clientRegion");
   initializeInstance();
   if (isBridgeConfiguration) {
      isBridgeClient = true;
      registerInterest(testInstance.aRegion);
      if (CQsOn) {
         CQTestInstance.initializeQueryService();
         queryMap = testInstance.CQTestInstance.generateQueries(queryDepth);
         CQTestInstance.queryMap = queryMap;
         CQTestInstance.aRegion = aRegion;
         if (startCQsRunning) {
            CQTestInstance.initializeCQs();
         }
      } else {
         Log.getLogWriter().info("Not creating CQs because CQUtilPrms.CQsOn is " + CQsOn);
      }
   }
}

/** Creates and initializes a data store PR in a bridge server.
 */
public synchronized static void HydraTask_initializeBridgeServer() {
   if (testInstance == null) {
      testInstance = new CQKnownKeysTest();
      testInstance.initializeRegion("serverRegion");
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
protected void initializeRegion(String regDescriptName) {
   CacheHelper.createCache("cache1");
   String key = VmIDStr + RemoteTestModule.getMyVmid();
   String xmlFile = key + ".xml";
   try {
      CacheHelper.generateCacheXmlFile("cache1", regDescriptName, xmlFile);
   } catch (HydraRuntimeException e) {
      if (e.toString().indexOf("Cache XML file was already created") >= 0) {
         // this can occur when reinitializing after a stop-start because
         // the cache xml file is written during the first init tasks
      } else {
         throw new TestException(TestHelper.getStackTrace(e));
      }
   }
   aRegion = RegionHelper.createRegion(regDescriptName);
}

/** Initialize fields for this instance
 */
public void initializeInstance() {
   numNewKeys = TestConfig.tab().intAt(CQUtilPrms.numNewKeys, -1);
   keyIntervals = (KeyIntervals)(CQUtilBB.getBB().getSharedMap().get(CQUtilBB.KeyIntervals));
   Log.getLogWriter().info("initInstance, keyIntervals read from blackboard = " + keyIntervals.toString());
   int numDestroyed = keyIntervals.getNumKeys(KeyIntervals.DESTROY);
   int numKeyIntervals = keyIntervals.getNumKeys();
   totalNumKeys = numKeyIntervals + numNewKeys - numDestroyed;
   minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, -1);
   minTaskGranularityMS = -1;
   if (minTaskGranularitySec != -1) {
      minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
   }
   queryDepth = TestConfig.tab().intAt(CQUtilPrms.queryDepth, 1);
   Vector bridgeNames = TestConfig.tab().vecAt(BridgePrms.names, null);
   isBridgeConfiguration = bridgeNames != null;
   CQsOn = TestConfig.tab().booleanAt(CQUtilPrms.CQsOn, true);
   CQTestInstance = new CQTest();
   CQTestInstance.initializeInstance();
   Log.getLogWriter().info("numKeyIntervals is " + numKeyIntervals);
   Log.getLogWriter().info("numNewKeys is " + numNewKeys);
   Log.getLogWriter().info("numDestroyed is " + numDestroyed);
   Log.getLogWriter().info("totalNumKeys is " + totalNumKeys);
}

/** Hydra start task to initialize key intervals, which are ranges of
 *  keys which are to have an operation done on them (invalidate, destroy, etc)
 */
public synchronized static void StartTask_initialize() {
   int numKeys = TestConfig.tab().intAt(CQUtilPrms.numKeys);
   KeyIntervals intervals = 
      new KeyIntervals(new int[] {KeyIntervals.NONE, KeyIntervals.INVALIDATE,
                                  KeyIntervals.DESTROY, KeyIntervals.UPDATE_EXISTING_KEY,
                                  KeyIntervals.GET}, 
                                  numKeys);
   CQUtilBB.getBB().getSharedMap().put(CQUtilBB.KeyIntervals, intervals);
   Log.getLogWriter().info("Created keyIntervals: " + intervals);

   // Set the counters for the next keys to use for each operation
   hydra.blackboard.SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   sc.setIfLarger(CQUtilBB.LASTKEY_INVALIDATE, intervals.getFirstKey(KeyIntervals.INVALIDATE)-1);
   sc.setIfLarger(CQUtilBB.LASTKEY_LOCAL_INVALIDATE, intervals.getFirstKey(KeyIntervals.LOCAL_INVALIDATE)-1);
   sc.setIfLarger(CQUtilBB.LASTKEY_DESTROY, intervals.getFirstKey(KeyIntervals.DESTROY)-1);
   sc.setIfLarger(CQUtilBB.LASTKEY_LOCAL_DESTROY, intervals.getFirstKey(KeyIntervals.LOCAL_DESTROY)-1);
   sc.setIfLarger(CQUtilBB.LASTKEY_UPDATE_EXISTING_KEY, intervals.getFirstKey(KeyIntervals.UPDATE_EXISTING_KEY)-1);
   sc.setIfLarger(CQUtilBB.LASTKEY_GET, intervals.getFirstKey(KeyIntervals.GET)-1);

   // show the blackboard
   CQUtilBB.getBB().printSharedMap();
   CQUtilBB.getBB().printSharedCounters();
}

// ======================================================================== 
// hydra task methods

/** Hydra task to initialize a region and load it according to hydra param settings. 
 */
public static void HydraTask_loadRegion() {
   testInstance.loadRegion();
}

/** Hydra task to execution ops, then stop scheduling.
 */
public static void HydraTask_doOps() {
   BitSet availableOps = new BitSet(operations.length);
   availableOps.flip(FIRST_OP, LAST_OP+1);
   // don't do local ops in bridge configuration
   availableOps.clear(LOCAL_INVALIDATE);
   availableOps.clear(LOCAL_DESTROY);
   testInstance.doOps(availableOps);
   if (availableOps.cardinality() == 0) {
      CQUtilBB.getBB().getSharedCounters().increment(CQUtilBB.TimeToStop);
      throw new StopSchedulingTaskOnClientOrder("Finished with ops");
   }
}  

/** Hydra task to wait for a period of silence (30 seconds) to allow
 *  the server queues to drain to the clients.
 */
public static void HydraTask_waitForSilence() {
   SilenceListener.waitForSilence(30 /*seconds*/, 1000);
   CQGatherListener.waitForSilence(30 /* seconds */, 1000);
}

/** Hydra task to start the CQs running for this VM.
 */
public static void HydraTask_startCQsRunning() {
   testInstance.startCQsWithHistory();
   throw new StopSchedulingTaskOnClientOrder("CQs are running");
}

/** Hydra task to verify the contents of the region after all ops.
 *  This MUST be called as a batched task, and will throw
 *  StopSchedulingTaskOnClientOrder when completed. It is necessary
 *  to reinitialize the verify state variables if this is called
 *  a second time in this VM, however an error is thrown if a second
 *  attempt it made without resetting the state variables.
 */
public static void HydraTask_verifyRegionContents() {
   CQUtilBB.getBB().printSharedCounters();
   NameBB.getBB().printSharedCounters();
   testInstance.verifyRegionContents();
}

/** Hydra task to verify queries on the region after all ops.
 *  This MUST be called as a batched task, and will throw
 *  StopSchedulingTaskOnClientOrder when completed. It is necessary
 *  to reinitialize the verify state variables if this is called
 *  a second time in this VM, however an error is thrown if a second
 *  attempt it made without resetting the state variables.
 */
public static void HydraTask_verifyQueries() {
   if (CQsOn) {
      // the region contents verify task comes first, so if we got to this
      // task, then we know the the region is a perfect snapshot of what
      // we expect; the CQTestInstance relies on a snapshot
      if (verifyQueryIterator == null) {
         testInstance.CQTestInstance.regionSnapshot = new HashMap(testInstance.aRegion);
         verifyQueryIterator = testInstance.queryMap.keySet().iterator();
      }
      if (verifyQueryIterator.hasNext()) {
         verifyQueryCount++;
         String cqName = (String)(verifyQueryIterator.next());
         Log.getLogWriter().info("Verifying query " + verifyQueryCount + " out of " + testInstance.queryMap.size() + 
             " with name " + cqName);
         testInstance.CQTestInstance.verifyQuery(cqName);
      } else {
         String aStr = "Done verifying " + verifyQueryCount + " queries";
         Log.getLogWriter().info(aStr);
         // set for next time; if this is an init task, then we will be set for the close task
         verifyQueryIterator = null;
         verifyQueryCount = 0;
         throw new StopSchedulingTaskOnClientOrder(aStr);
      }
   } else {
      String aStr = "Skipping verification of queries because CQUtilPrms.CQsOn is " + CQsOn;
      Log.getLogWriter().info(aStr);
      throw new StopSchedulingTaskOnClientOrder(aStr);
   }
}

/** Hydra task to verify queries on the region after all ops for
 *  a test where executeWithInitialResults occurs concurrently with
 *  ops. We must combine the results of executeWithInitialResults and
 *  the cq events that come after
 */
public static void HydraTask_verifyQueriesCombine() {
   testInstance.verifyQueriesCombine();
}

/** Load a region with keys and values. The number of keys and values is specified
 *  by the total number of keys in keyIntervals. This can be invoked by several threads
 *  to accomplish the work.
 */
public void loadRegion() {
   final long LOG_INTERVAL_MILLIS = 10000;
   int numKeysToCreate = keyIntervals.getNumKeys();
   long lastLogTime = System.currentTimeMillis();
   long startTime = System.currentTimeMillis();
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   do {
      long shouldAddCount = CQUtilBB.getBB().getSharedCounters().incrementAndRead(CQUtilBB.SHOULD_ADD_COUNT);
      if (shouldAddCount > numKeysToCreate) {
         String aStr = "In loadRegion, shouldAddCount is " + shouldAddCount +
                       ", numOriginalKeysCreated is " + sc.read(CQUtilBB.NUM_ORIGINAL_KEYS_CREATED) +
                       ", numKeysToCreate is " + numKeysToCreate + ", region size is " + aRegion.size();
         Log.getLogWriter().info(aStr);
         NameBB.getBB().printSharedCounters();
         throw new StopSchedulingTaskOnClientOrder(aStr);
      }
      Object key = NameFactory.getNextPositiveObjectName();
      QueryObject value = getValueToAdd(key);
      value.extra = key;
      Log.getLogWriter().info("Creating with put, key " + key + ", value " + value.toStringFull());
      aRegion.put(key, value);
      sc.increment(CQUtilBB.NUM_ORIGINAL_KEYS_CREATED);
      if (System.currentTimeMillis() - lastLogTime > LOG_INTERVAL_MILLIS) {
         Log.getLogWriter().info("Added " + NameFactory.getPositiveNameCounter() + " out of " + 
             numKeysToCreate + " entries into " + TestHelper.regionToString(aRegion, false));
         lastLogTime = System.currentTimeMillis();
      }
   } while ((minTaskGranularitySec == -1) ||
            (System.currentTimeMillis() - startTime < minTaskGranularityMS));
}

/** Do operations on the REGION_NAME's keys using keyIntervals to specify
 *  which keys get which operations. This will return when all operations
 *  in all intervals have completed.
 *
 *  @param availableOps - Bits which are true correspond to the operations
 *                        that should be executed.
 */
public void doOps(BitSet availableOps) {

   boolean useTransactions = getInitialImage.InitImagePrms.useTransactions();

   while (availableOps.cardinality() != 0) {
      int whichOp = getOp(availableOps, operations.length);
      boolean doneWithOps = false;

      if (useTransactions) {
        TxHelper.begin();
      }

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

      if (useTransactions) {
        try {
          TxHelper.commit();
        } catch (ConflictException e) {
          // currently not expecting any conflicts ...
          throw new TestException("Unexpected ConflictException " + TestHelper.getStackTrace(e));
        }
      }

      if (doneWithOps) {
         Log.getLogWriter().info("Done with operation " + whichOp);
         availableOps.clear(whichOp);
      }
      if (sleepBetweenOps) {
         Log.getLogWriter().info("Sleeping between ops for " + SLEEP_BETWEEN_OPS_MILLIS + " millis");
         MasterController.sleepForMs(SLEEP_BETWEEN_OPS_MILLIS);
      }
   }
}

/** Add a new key to REGION_NAME.
 *
 *  @return true if all new keys have been added (specified by CQUtilPrms.numNewKeys)
 */
protected boolean addNewKey() {
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   long numNewKeysCreated = sc.incrementAndRead(CQUtilBB.NUM_NEW_KEYS_CREATED);
   if (numNewKeysCreated > numNewKeys) {
      Log.getLogWriter().info("All new keys created; returning from addNewKey");
      return true;
   }
   Object key = NameFactory.getNextPositiveObjectName();
   checkContainsValueForKey(key, false, "before addNewKey");
   QueryObject value = new QueryObject(NameFactory.getCounterForName(key), QueryObject.EQUAL_VALUES, -1, queryDepth);
   value.extra = key; // encode the key in the value for later validation
   Log.getLogWriter().info("Adding new key " + key + " with put");
   aRegion.put(key, value);
   Log.getLogWriter().info("Done adding new key " + key + " with put, " +
       "num remaining: " + (numNewKeys - numNewKeysCreated));
   return (numNewKeysCreated >= numNewKeys);
}

/** Update an existing key in region REGION_NAME. The keys to update are specified
 *  in keyIntervals.
 *
 *  @return true if all keys to be updated have been completed.
 */
protected boolean updateExistingKey() {
   long nextKey = CQUtilBB.getBB().getSharedCounters().incrementAndRead(CQUtilBB.LASTKEY_UPDATE_EXISTING_KEY);
   if (!keyIntervals.keyInRange(KeyIntervals.UPDATE_EXISTING_KEY, nextKey)) {
      Log.getLogWriter().info("All existing keys updated; returning from updateExistingKey");
      return true;
   }
   Object key = NameFactory.getObjectNameForCounter(nextKey);
   QueryObject existingValue = (QueryObject)aRegion.get(key);
   if (existingValue == null)
      throw new TestException("Get of key " + key + " returned unexpected " + existingValue);
   QueryObject newValue = existingValue.modifyWithNewInstance(QueryObject.NEGATE, 0, true);
   newValue.extra = key; // encode the key in the object for later validation
   if (existingValue.aPrimitiveLong < 0)
      throw new TestException("Trying to update a key which was already updated: " + existingValue.toStringFull());
   Log.getLogWriter().info("Updating existing key " + key + " with value " + TestHelper.toString(newValue));
   aRegion.put(key, newValue);
   Log.getLogWriter().info("Done updating existing key " + key + " with value " + 
       TestHelper.toString(newValue) + ", num remaining: " + 
       (keyIntervals.getLastKey(KeyIntervals.UPDATE_EXISTING_KEY) - nextKey));
   return (nextKey >= keyIntervals.getLastKey(KeyIntervals.UPDATE_EXISTING_KEY));
}

/** Locally invalidate a key in region REGION_NAME. The keys to locally invalidate
 *  are specified in keyIntervals.
 *
 *  @return true if all keys to be locally invalidated have been completed.
 */
protected boolean localInvalidate() {
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   long nextKey = sc.incrementAndRead(CQUtilBB.LASTKEY_LOCAL_INVALIDATE);
   if (!keyIntervals.keyInRange(KeyIntervals.LOCAL_INVALIDATE, nextKey)) {
      Log.getLogWriter().info("All local invalidates completed; returning from localInvalidate");
      return true;
   }
   Object key = NameFactory.getObjectNameForCounter(nextKey);
   Log.getLogWriter().info("Locally invalidating " + key);
   try {
      checkContainsValueForKey(key, true, "before localInvalidate");
      aRegion.localInvalidate(key);
      Log.getLogWriter().info("Done locally invalidating " + key + 
          ", num remaining: " + (keyIntervals.getLastKey(KeyIntervals.LOCAL_INVALIDATE) - nextKey));
   } catch (EntryNotFoundException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
   return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_INVALIDATE));
}
   
/** Invalidate a key in region REGION_NAME. The keys to invalidate
 *  are specified in keyIntervals.
 *
 *  @return true if all keys to be invalidated have been completed.
 */
protected boolean invalidate() {
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   long nextKey = sc.incrementAndRead(CQUtilBB.LASTKEY_INVALIDATE);
   if (!keyIntervals.keyInRange(KeyIntervals.INVALIDATE, nextKey)) {
      Log.getLogWriter().info("All existing keys invalidated; returning from invalidate");
      return true;
   }
   Object key = NameFactory.getObjectNameForCounter(nextKey);
   Log.getLogWriter().info("Invalidating " + key);
   checkContainsValueForKey(key, true, "before invalidate");
   try {
      aRegion.invalidate(key);
      Log.getLogWriter().info("Done invalidating " + key + 
          ", num remaining: " + (keyIntervals.getLastKey(KeyIntervals.INVALIDATE) - nextKey));
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (EntryNotFoundException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
   return (nextKey >= keyIntervals.getLastKey(KeyIntervals.INVALIDATE));
}
   
/** Locally destroy a key in region REGION_NAME. The keys to locally destroy
 *  are specified in keyIntervals.
 *
 *  @return true if all keys to be locally destroyed have been completed.
 */
protected boolean localDestroy() {
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   long nextKey = sc.incrementAndRead(CQUtilBB.LASTKEY_LOCAL_DESTROY);
   if (!keyIntervals.keyInRange(KeyIntervals.LOCAL_DESTROY, nextKey)) {
      Log.getLogWriter().info("All local destroys completed; returning from localDestroy");
      return true;
   }
   Object key = NameFactory.getObjectNameForCounter(nextKey);
   Log.getLogWriter().info("Locally destroying " + key);
   checkContainsValueForKey(key, true, "before localDestroy");
   try {
      aRegion.localDestroy(key);
      Log.getLogWriter().info("Done locally destroying " + key + 
          ", num remaining: " + (keyIntervals.getLastKey(KeyIntervals.LOCAL_DESTROY) - nextKey));
   } catch (EntryNotFoundException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
   return (nextKey >= keyIntervals.getLastKey(KeyIntervals.LOCAL_DESTROY));
}
   
/** Destroy a key in region REGION_NAME. The keys to destroy
 *  are specified in keyIntervals.
 *
 *  @return true if all keys to be destroyed have been completed.
 */
protected boolean destroy() {
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   long nextKey = sc.incrementAndRead(CQUtilBB.LASTKEY_DESTROY);
   if (!keyIntervals.keyInRange(KeyIntervals.DESTROY, nextKey)) {
      Log.getLogWriter().info("All destroys completed; returning from destroy");
      return true;
   }
   Object key = NameFactory.getObjectNameForCounter(nextKey);
   Log.getLogWriter().info("Destroying " + key);
   checkContainsValueForKey(key, true, "before destroy");
   try {
      aRegion.destroy(key);
      Log.getLogWriter().info("Done Destroying " + key + 
          ", num remaining: " + (keyIntervals.getLastKey(KeyIntervals.DESTROY) - nextKey));
   } catch (CacheWriterException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (EntryNotFoundException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
   return (nextKey >= keyIntervals.getLastKey(KeyIntervals.DESTROY));
}

/** Do a get on a key in region REGION_NAME. Keys to get are specified
 *  in keyIntervals.
 *
 *  @return true if all keys to have get performaed have been completed.
 */
protected boolean get() {
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   long nextKey = sc.incrementAndRead(CQUtilBB.LASTKEY_GET);
   if (!keyIntervals.keyInRange(KeyIntervals.GET, nextKey)) {
      Log.getLogWriter().info("All gets completed; returning from get");
      return true;
   }
   Object key = NameFactory.getObjectNameForCounter(nextKey);
   Log.getLogWriter().info("Getting " + key);
   try {
      Object existingValue = aRegion.get(key);
      Log.getLogWriter().info("Done getting " + key + 
          ", num remaining: " + (keyIntervals.getLastKey(KeyIntervals.GET) - nextKey));
      if (existingValue == null)
         throw new TestException("Get of key " + key + " returned unexpected " + existingValue);
   } catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   } catch (CacheLoaderException e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
   return (nextKey >= keyIntervals.getLastKey(KeyIntervals.GET));
}

// ======================================================================== 
// verification methods 

/** Verify the contents of the region, taking into account the keys
 *  that were destroyed, invalidated, etc (as specified in keyIntervals)
 *  Throw an error of any problems are detected.
 *  This must be called repeatedly by the same thread until 
 *  StopSchedulingTaskOnClientOrder is thrown.
 */ 
public void verifyRegionContents() {
   final long LOG_INTERVAL_MILLIS = 10000;
   // we already completed this check once; we can't do it again without reinitializing the 
   // verify state variables
   if (verifyRegionContentsCompleted) {
      throw new TestException("Test configuration problem; already verified region contents, " +
          "cannot call this task again without resetting batch variables");
   }

   // iterate keys
   long lastLogTime = System.currentTimeMillis();
   long minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec);
   long minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
   long startTime = System.currentTimeMillis();
   long size = aRegion.size();
   boolean first = true;
   int numKeysToCheck = keyIntervals.getNumKeys() + numNewKeys;
   while (verifyRegionContentsIndex < numKeysToCheck) {
      verifyRegionContentsIndex++;
      if (first) {
         Log.getLogWriter().info("In verifyRegionContents, region has " + size + 
            " keys; starting verify at verifyRegionContentsIndex " + verifyRegionContentsIndex +
            "; verifying key names with indexes through (and including) " + numKeysToCheck);
         first = false;
      }

      // check region size the first time through the loop to avoid it being called
      // multiple times when this is batched
      if (verifyRegionContentsIndex == 1) {
         if (totalNumKeys != size) {
            String tmpStr = "Expected region size to be " + totalNumKeys + ", but it is size " + size;
            Log.getLogWriter().info(tmpStr);
            verifyRegionContentsErrStr.append(tmpStr + "\n");
         }
      }

      Object key = NameFactory.getObjectNameForCounter(verifyRegionContentsIndex);
      try {
         if (((verifyRegionContentsIndex >= keyIntervals.getFirstKey(KeyIntervals.NONE)) &&
              (verifyRegionContentsIndex <= keyIntervals.getLastKey(KeyIntervals.NONE)))    ||
             ((verifyRegionContentsIndex >= keyIntervals.getFirstKey(KeyIntervals.GET)) &&
             (verifyRegionContentsIndex <= keyIntervals.getLastKey(KeyIntervals.GET)))) {
            // this key was untouched after its creation
            checkContainsKey(key, true, "key was untouched");
            checkContainsValueForKey(key, true, "key was untouched");
            Object value = aRegion.get(key);
            checkValue(key, value);
         } else if ((verifyRegionContentsIndex >= keyIntervals.getFirstKey(KeyIntervals.INVALIDATE)) &&
                    (verifyRegionContentsIndex <= keyIntervals.getLastKey(KeyIntervals.INVALIDATE))) {
            checkContainsKey(key, true, "key was invalidated");
            checkContainsValueForKey(key, false, "key was invalidated");
         } else if ((verifyRegionContentsIndex >= keyIntervals.getFirstKey(KeyIntervals.LOCAL_INVALIDATE)) &&
                    (verifyRegionContentsIndex <= keyIntervals.getLastKey(KeyIntervals.LOCAL_INVALIDATE))) {
            // this key was locally invalidated
            checkContainsKey(key, true, "key was locally invalidated");
            checkContainsValueForKey(key, true, "key was locally invalidated");
            Object value = aRegion.get(key);
            checkValue(key, value);
         } else if ((verifyRegionContentsIndex >= keyIntervals.getFirstKey(KeyIntervals.DESTROY)) &&
                    (verifyRegionContentsIndex <= keyIntervals.getLastKey(KeyIntervals.DESTROY))) {
            // this key was destroyed
            checkContainsKey(key, false, "key was destroyed");
            checkContainsValueForKey(key, false, "key was destroyed");
         } else if ((verifyRegionContentsIndex >= keyIntervals.getFirstKey(KeyIntervals.LOCAL_DESTROY)) &&
                    (verifyRegionContentsIndex <= keyIntervals.getLastKey(KeyIntervals.LOCAL_DESTROY))) {
            // this key was locally destroyed
            checkContainsKey(key, true, "key was locally destroyed");
            checkContainsValueForKey(key, true, "key was locally destroyed");
            Object value = aRegion.get(key);
            checkValue(key, value);
         } else if ((verifyRegionContentsIndex >= keyIntervals.getFirstKey(KeyIntervals.UPDATE_EXISTING_KEY)) &&
                    (verifyRegionContentsIndex <= keyIntervals.getLastKey(KeyIntervals.UPDATE_EXISTING_KEY))) {
            // this key was updated
            checkContainsKey(key, true, "key was updated");
            checkContainsValueForKey(key, true, "key was updated");
            Object value = aRegion.get(key);
            checkUpdatedValue(key, value);
         } else if (verifyRegionContentsIndex > keyIntervals.getNumKeys()) {
            // key was newly added
            checkContainsKey(key, true, "key was new");
            checkContainsValueForKey(key, true, "key was new");
            Object value = aRegion.get(key);
            checkValue(key, value);
         }
      } catch (TestException e) {
         Log.getLogWriter().info(TestHelper.getStackTrace(e));
         verifyRegionContentsErrStr.append(e.getMessage() + "\n");
      }

      if (System.currentTimeMillis() - lastLogTime > LOG_INTERVAL_MILLIS) {
         Log.getLogWriter().info("Verified key " + verifyRegionContentsIndex + " out of " + totalNumKeys);
         lastLogTime = System.currentTimeMillis();
      }

      if (System.currentTimeMillis() - startTime >= minTaskGranularityMS) {
         Log.getLogWriter().info("In HydraTask_verifyRegionContents, returning before completing verify " +
             "because of task granularity (this task must be batched to complete); last key verified is " + 
             key);
         return;  // task is batched; we are done with this batch
      }
   }
   verifyRegionContentsCompleted = true;
   if (verifyRegionContentsErrStr.length() > 0) {
      throw new TestException(verifyRegionContentsErrStr.toString());
   }
   String aStr = "In HydraTask_verifyRegionContents, verified " + verifyRegionContentsIndex + " keys/values";
   Log.getLogWriter().info(aStr);
   throw new StopSchedulingTaskOnClientOrder(aStr);
}

/** Check that containsKey() called on the tests' region has the expected result.
 *  Throw an error if any problems.
 *  
 *  @param key The key to check.
 *  @param expected The expected result of containsKey
 *  @param logStr Used if throwing an error due to an unexpected value.
 */
protected void checkContainsKey(Object key, boolean expected, String logStr) {
   boolean containsKey = aRegion.containsKey(key);
   if (containsKey != expected)
      throw new TestException("Expected containsKey(" + key + ") to be " + expected + 
                ", but it was " + containsKey + ": " + logStr);
}

/** Check that containsValueForKey() called on the tests' region has the expected result.
 *  Throw an error if any problems.
 *  
 *  @param key The key to check.
 *  @param expected The expected result of containsValueForKey
 *  @param logStr Used if throwing an error due to an unexpected value.
 */
protected void checkContainsValueForKey(Object key, boolean expected, String logStr) {
   boolean containsValue = aRegion.containsValueForKey(key);
   if (containsValue != expected)
      throw new TestException("Expected containsValueForKey(" + key + ") to be " + expected + 
                ", but it was " + containsValue + ": " + logStr);
}

/** Check that the value of the given key is expected for this test.
 *  Throw an error if any problems.
 *  
 *  @param key The key to check.
 *  @param value The value for the key.
 */
protected void checkValue(Object key, Object value) {
   if (value instanceof QueryObject) {
      QueryObject qo = (QueryObject)value;
      long keyCounter = NameFactory.getCounterForName(key);
      if (keyCounter != qo.aPrimitiveLong) {
         // just pick one field from the QueryObject to test; use aPrimitiveLong
         throw new TestException("Inconsistent QueryObject for key " + key + ":" + qo.toStringFull());
      }
   } else {
      throw new TestException("For key " + key + ", expected value " + TestHelper.toString(value) + " to be a QueryObject");
   }
}
      
/** Check that the value of the given key is expected as an updated value.
 *  Throw an error if any problems.
 *  
 *  @param key The key to check.
 *  @param value The value for the key.
 */
protected void checkUpdatedValue(Object key, Object value) {
   if (value instanceof QueryObject) {
      QueryObject qo = (QueryObject)value;
      long keyCounter = NameFactory.getCounterForName(key);
      if (qo.aPrimitiveLong > 0) { // this value has not been updated; updates are negative
         throw new TestException("Expected QueryObject for key " + key + 
            " to contain negative values (indicating it was updated), but the value for this key is " + 
            qo.toStringFull());
      }
   } else {
      throw new TestException("Expected value " + TestHelper.toString(value) + " to be a QueryObject");
   }
}
      
/** Verify all queries by combining the results of executeWithInitialResults
 *  and subsequent cqEvents.
 */
private void verifyQueriesCombine() {
   if (CQsOn) {
      // the region contents verify task comes first, so if we got to this
      // task, then we know the the region is a perfect snapshot of what
      // we expect; the CQTestInstance relies on a snapshot
      testInstance.CQTestInstance.regionSnapshot = new HashMap(testInstance.aRegion);
      verifyQueryResultsCombine();
   } else {
      Log.getLogWriter().info("Skipping verification of queries because CQUtilPrms.CQsOn is " + CQsOn);
   }
}

/** Verify the result of the CQs registered for this VM.
 *  Query results from executeWithInitialResults are combined with
 *  subsequent cq events to form the final result. 
 */
protected void verifyQueryResultsCombine() {
   Log.getLogWriter().info("In verifyQueryResultsCombine");
   Iterator it = queryMap.keySet().iterator();
   int count = 0;
   while (it.hasNext()) {
      count++;
      String cqName = (String)(it.next());
      Log.getLogWriter().info("Verifying query " + count + " out of " + queryMap.size() + " with name " + cqName);
      CqQuery cq = qService.getCq(cqName);
      String queryStr = cq.getQueryString();
      String readableQueryStr = CQTest.getReadableQueryString(queryStr);

      // combine the initial selectResults with history of events
      CQHistory history = CQHistoryListener.getCQHistory(cqName);
      Map combinedMap = history.getCombinedResults();
      List combinedList = new ArrayList(combinedMap.values());
      List expectedResults = CQTestInstance.getExpectedResults(queryStr);
      List missingInCombined = new ArrayList(expectedResults);
      List unexpectedInCombined = new ArrayList(combinedList);
      unexpectedInCombined.removeAll(expectedResults);
      missingInCombined.removeAll(combinedList);

      // prepare error Strings
      StringBuffer aStr = new StringBuffer();
      if (unexpectedInCombined.size() > 0) {
         String tmpStr = getLocationString(unexpectedInCombined, expectedResults, history) + "\n" +
             "Found the following " + unexpectedInCombined.size() + 
             " unexpected elements in combined results for cq " + cqName + ", " +
             readableQueryStr + ": " + QueryObject.toStringFull(unexpectedInCombined);
         Log.getLogWriter().info(tmpStr);
         aStr.append(tmpStr);
      }
      if (missingInCombined.size() > 0) {
         String tmpStr = getLocationString(missingInCombined, expectedResults, history) + "\n" +
                "The following " + missingInCombined.size() + 
                " elements were missing from combined results for cq " + cqName + ", " +
                readableQueryStr + ": " + QueryObject.toStringFull(missingInCombined);
         Log.getLogWriter().info(tmpStr);
         aStr.append(tmpStr);
      }

      if (aStr.length() > 0) {
         throw new TestException("Probably bug 38065: For cq " + cqName + ", " + readableQueryStr + "\n" + aStr.toString());
      }
   }
   Log.getLogWriter().info("Done verifying " + count + " queries");
}

// ======================================================================== 
// internal methods to provide support to task methods

/** Given a List of QueryObjects known to be inconsistent as determined by validation,
 *  log where the suspect objects are found by checking for it in 
 *      1) the expected List
 *      2) the CQ history of events
 *      3) the select results
 *      4) the localRegion
 *
 *  @param inconsistencies A List of suspect QueryObjects to check in each location.
 *  @param expected The expected List of objects for a query.
 *  @param history The CQHistory for the query
 *
 *  @returns 
 */
private String getLocationString(List inconsistencies, 
                                 List expected, 
                                 CQHistory history) {
   StringBuffer aStr = new StringBuffer();
   for (int i = 0; i < inconsistencies.size(); i++) {
      QueryObject suspect = (QueryObject)(inconsistencies.get(i));
      
      // check the local region
      boolean found = false;
      Iterator it = aRegion.keySet().iterator();
      while (it.hasNext()) {
         Object key = it.next();
         Region.Entry entry = aRegion.getEntry(key);
         QueryObject qo = (QueryObject)(entry.getValue());
         if ((qo != null) && (qo.equals(suspect))) {
            found = true;
            aStr.append(qo.toStringAbbreviated() + " was found in " + aRegion.getFullPath() + " at key " + key + "\n");
         }
      } 
      if (!found) {
         aStr.append(suspect.toStringAbbreviated() + " was NOT found in " + aRegion.getFullPath() + "\n");
      }

      // seach for all occurrences in expected list
      found = false;
      it = expected.iterator();
      while (it.hasNext()) {
         QueryObject qo = (QueryObject)(it.next());
         if (qo.equals(suspect)) {
            found = true;
            aStr.append(qo.toStringAbbreviated() + " was found in expected results\n");
         }
      } 
      if (!found) {
         aStr.append(suspect.toStringAbbreviated() + " was NOT found in expected results\n");
      }

      // seach for all occurrences in selectResults
      SelectResults selResults = history.getSelectResults();
      found = false;
      it = selResults.iterator();
      while (it.hasNext()) {
         QueryObject qo = (QueryObject)(it.next());
         if (qo.equals(suspect)) {
            found = true;
            aStr.append(qo.toStringAbbreviated() + " was found in SelectResults\n");
         }
      } 
      if (!found) {
         aStr.append(suspect.toStringAbbreviated() + " was NOT found in SelectResults\n");
      }

      // seach for all occurrences in history
      found = false;
      List eventList = history.getEvents();
      for (int j = 0; j < eventList.size(); j++) {
         CqEvent event = (CqEvent)(eventList.get(j));
         QueryObject qo = (QueryObject)(event.getNewValue());
         if ((qo != null) && (qo.equals(suspect))) {
            found = true;
            aStr.append(qo.toStringAbbreviated() + " was found in event history as new value " + event + "\n");
         }
      } 
      if (!found) {
         aStr.append(suspect.toStringAbbreviated() + " was NOT found in CqEvent history\n");
      }
   }
   return aStr.toString();
}

/** Get a random operation to perform. The set of operations available are
 *  in the BitSet. These bits correspond to the operations defined in this
 *  class.
 *
 *  @param bs True bits correspond to operations available to be chosen.
 *  @param bsSize The number of bits to consider.
 *
 *  @return An operation as defined in this class.
 */
protected int getOp(BitSet bs, int bsSize) {
   int randInt;
   do {
      randInt = TestConfig.tab().getRandGen().nextInt(1, bsSize);
   } while (!bs.get(randInt));
   return randInt;
}

/** Return a value to add to a region for the given key
 */
protected QueryObject getValueToAdd(Object key) {
   return new QueryObject(NameFactory.getCounterForName(key), QueryObject.EQUAL_VALUES, -1, queryDepth);
}

protected Set getRegionKeySet(Region aRegion) {
   return aRegion.keys();
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

protected void initializeQueryService() {
   try {
     String usingPool = TestConfig.tab().stringAt(CQUtilPrms.QueryServiceUsingPool, "false");
     boolean queryServiceUsingPool = Boolean.valueOf(usingPool).booleanValue();
     if (queryServiceUsingPool){
       Pool pool = PoolHelper.createPool(CQUtilPrms.getQueryServicePoolName());
       qService = pool.getQueryService();
       Log.getLogWriter().info("Initializing QueryService using Pool. PoolName: " + pool.getName());
     } else {
       qService = CacheHelper.getCache().getQueryService();
       Log.getLogWriter().info("Initializing QueryService using Cache.");
     }
   } catch (Exception e) {
      throw new TestException(TestHelper.getStackTrace(e));
   }
}
    
/** Log the number of ops that have completed.
 */
protected void logNumOps() {
   SharedCounters sc = CQUtilBB.getBB().getSharedCounters();
   int totalOps = keyIntervals.getNumKeys() - keyIntervals.getNumKeys(KeyIntervals.NONE) + numNewKeys;
   long numOpsCompleted = 
          sc.read(CQUtilBB.LASTKEY_INVALIDATE) - keyIntervals.getFirstKey(KeyIntervals.INVALIDATE) +
          sc.read(CQUtilBB.LASTKEY_DESTROY) - keyIntervals.getFirstKey(KeyIntervals.DESTROY) +
          sc.read(CQUtilBB.LASTKEY_UPDATE_EXISTING_KEY) - keyIntervals.getFirstKey(KeyIntervals.UPDATE_EXISTING_KEY) +
          sc.read(CQUtilBB.LASTKEY_GET) - keyIntervals.getFirstKey(KeyIntervals.GET) +
          sc.read(CQUtilBB.NUM_NEW_KEYS_CREATED);
   Log.getLogWriter().info("Total ops is " + totalOps + ", current number of ops completed is " + numOpsCompleted);
}

/** Start all cqs running for this VM, and create a CQHistory instance
 *  for each CQ.
 */
private void startCQsWithHistory() {
   initializeQueryService();
   CqAttributesFactory cqFac = new CqAttributesFactory();
   cqFac.addCqListener(new CQHistoryListener());
   cqFac.addCqListener(new CQGatherListener());
   CqAttributes cqAttr = cqFac.create();
   Iterator it = queryMap.keySet().iterator();
   while (it.hasNext()) {
      String queryName = (String)(it.next());
      String query = (String)(queryMap.get(queryName));
      try {
         CqQuery cq = qService.newCq(queryName, query, cqAttr);
         CQHistory history = new CQHistory(cq.getName()); 
         CQHistoryListener.recordHistory(history);
         Log.getLogWriter().info("Creating CQ with name " + queryName + ": " + query + 
             ", cq attributes: " + cqAttr);
         Log.getLogWriter().info("Calling executeWithInitialResults on " + cq);
         CqResults rs = cq.executeWithInitialResults();
         SelectResults sr = CQUtil.getSelectResults(rs);
         if (sr == null) {
            throw new TestException("For cq " + cq + " with name " + cq.getName() + 
                  " executeWithInitialResults returned " + sr);
         }
         Log.getLogWriter().info("Done calling executeWithInitializResults on " + cq + " with name " +
                                 queryName + ", select results size is " + sr.size());
         history.setSelectResults(sr);
         logNumOps();

         // log the select results
         List srList = sr.asList();
         StringBuffer aStr = new StringBuffer();
         aStr.append("SelectResults returned from " + queryName + " is\n");
         for (int i = 0; i < srList.size(); i++) {
            aStr.append(srList.get(i) + "\n");
         }
         Log.getLogWriter().info(aStr.toString());
      } catch (CqExistsException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      } catch (RegionNotFoundException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      } catch (CqException e) {
         throw new TestException(TestHelper.getStackTrace(e));
      }
   }
}

}
