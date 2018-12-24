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
package resumeTx;

import java.util.*;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheTransactionManager;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionId;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import hydra.*;
import hydra.blackboard.SharedCounters;
import parReg.ParRegUtil;
import tx.TxUtil;
import util.NameFactory;
import util.SummaryLogListener;
import util.TestException;
import util.TestHelper;
import util.TestHelperPrms;
import util.TxHelper;

public class ConcResumableTxTest {

  static ConcResumableTxTest testInstance = null;

  // instance fields to hold information about this test run
  private boolean isBridgeConfiguration;
  private boolean isBridgeClient;
  private TxUtil txUtilInstance = new TxUtil();
  private int numVMsInTest = 0;

  // parallel lists that account for active (resumable) transactions
  private List<TransactionId> txIdList = null;  // list of active transactions; remains here until committed or rolled back
  private List<TransactionId> resumed = null;   // used to verify that only one thread at a time is able to resume a tx
  private List<Integer> resumeCount = null;     // the number of times the transaction has been resumed
  private List<Set> txKeySetList = null;        // the set of keys in use by this transaction

  private final static int REGION_SIZE = 100; // the desired number of entries in a region
  private final static int NUM_OPS = 10;      //number of puts (create or updates) to do in a region at a time
  private final static int MIN_RESUME_COUNT = 50;
  private final static int MAX_RESUME_COUNT = 100;
  private final static String snapshotKey = "snapshot";


  /** Creates and initializes an edge client.
   */
  public synchronized static void HydraTask_initializeClient() {
    if (testInstance == null) {
      testInstance = new ConcResumableTxTest();
      testInstance.initializeInstance();
      testInstance.createRegions(true);
      if (testInstance.isBridgeConfiguration) {
        testInstance.isBridgeClient = true;
        registerInterest();
      }
    }
  }

  /** Creates and initializes a server or peer. 
   */
  public synchronized static void HydraTask_initialize() {
    if (testInstance == null) {
      testInstance = new ConcResumableTxTest();
      testInstance.initializeInstance();
      testInstance.createRegions(false);
      if (testInstance.isBridgeConfiguration) {
        testInstance.isBridgeClient = false;
        BridgeHelper.startBridgeServer("bridge");
      }
    }
  }

  public static void HydraTask_concSuspendResumeTx() {
    testInstance.concSuspendResumeTx();
  }

  public synchronized static void HydraTask_beginTxns() {
    if (testInstance.txIdList == null) {
      testInstance.txIdList = Collections.synchronizedList(new ArrayList());
      testInstance.resumed = Collections.synchronizedList(new ArrayList());
      testInstance.resumeCount = Collections.synchronizedList(new ArrayList());
      testInstance.txKeySetList = Collections.synchronizedList(new ArrayList());
      CacheTransactionManager ctm = CacheHelper.getCache().getCacheTransactionManager();
      int NUM_TXNS = 10;
      for (int i = 1; i <= NUM_TXNS; i++) {
        TxHelper.begin();
        TransactionId beginId = CacheHelper.getCache().getCacheTransactionManager().getTransactionId();
        ResumeTxBB.getBB().getSharedCounters().increment(ResumeTxBB.numBegins);
        if (!ctm.exists()) {
          throw new TestException("Began " + beginId + " but exists() returned false");
        }
        TransactionId suspendId = TxHelper.suspend();
        Log.getLogWriter().info("Suspended " + suspendId);
        if (!beginId.equals(suspendId)) {
          throw new TestException("beginId " + beginId + " does not equal suspendId " + suspendId);
        }
        testInstance.txIdList.add(suspendId);
        if (ctm.exists()) {
          throw new TestException("Suspended " + beginId + " but exists() returned true");
        }
      }
      for (int i = 0; i < testInstance.txIdList.size(); i++) {
        testInstance.resumed.add(null);
        testInstance.resumeCount.add(0);
        testInstance.txKeySetList.add(new HashSet());
      }
    }
  }

  /** Add new entries to all root regions until the region has a size of at
   *  least REGION_SIZE.
   */
  public static void HydraTask_populateRegions() {
    for (Region aRegion: CacheHelper.getCache().rootRegions()) {
      while (aRegion.size() < REGION_SIZE) {
        Object key = NameFactory.getNextPositiveObjectName();
        Object value = NameFactory.getCounterForName(key);
        aRegion.put(key, value);
      }
    }
    logRegionSizes();
  }
  
  /** Log and check for consistency in BB shared counters
   * 
   */
  public static void HydraTask_checkCounters() {
    logRegionSizes();
    SharedCounters sc = ResumeTxBB.getBB().getSharedCounters();
    long numBegins = sc.read(ResumeTxBB.numBegins);
    long numCompletedTxns = sc.read(ResumeTxBB.numCompletedTxns);
    long numCommits = sc.read(ResumeTxBB.numCommits);
    long numFailedCommits = sc.read(ResumeTxBB.numFailedCommits);
    long numSuccessfulCommits = sc.read(ResumeTxBB.numSuccessfulCommits);
    long numRollbacks = sc.read(ResumeTxBB.numRollbacks);
    long numResumes = sc.read(ResumeTxBB.numResumes);
    long numResumesAtCompletion = sc.read(ResumeTxBB.numResumesAtCompletion);
    long numFailedTries = sc.read(ResumeTxBB.numFailedTries);
    double avgResumesPerTx = (double)numResumesAtCompletion / (double)numCompletedTxns;
    StringBuffer aStr = new StringBuffer();
    aStr.append("numBegins: " + numBegins + "\n" +
                "numCompletedTxns: " + numCompletedTxns + "\n" +
                "numCommits: " + numCommits + "\n" +
                "numFailedCommits: " + numFailedCommits + "\n" +
                "numSuccessfulCommits: " + numSuccessfulCommits + "\n" +
                "numRollbacks: " + numRollbacks + "\n" +
                "numResumes: " + numResumes + "\n" +
                "numResumesAtCompletion: " + numResumesAtCompletion + "\n" +
                "numFailedTries: " + numFailedTries + "\n" +
                "average resumes per tx: " + avgResumesPerTx);
    Log.getLogWriter().info(aStr.toString());
    
    long total = numCommits + numRollbacks;
    if (total != numBegins) {
      throw new TestException("numCommits " + numCommits + " + numRollbacks " + numRollbacks +
          " should be numBegins " + numBegins + " but it is " + total);
    }
    if (numBegins != numCompletedTxns) {
      throw new TestException("numBegins " + numBegins + " should be equal to numCompletedTxns " + numCompletedTxns);
    }
    if (numCompletedTxns == 0) {
      throw new TestException("Test did not complete any transactions");
    }
    
  }
  
  /** For all open transactions, commit them
   * 
   */
  public static void HydraTask_completeTxns() {
    CacheTransactionManager ctm = CacheHelper.getCache().getCacheTransactionManager();
    SharedCounters sc = ResumeTxBB.getBB().getSharedCounters();
    for (int txIdIndex = 0; txIdIndex < testInstance.txIdList.size(); txIdIndex++) {
      TransactionId txId = testInstance.txIdList.get(txIdIndex);
      boolean result = ctm.tryResume(txId);
      if (result) {
        try {
          TxHelper.commit();
          sc.increment(ResumeTxBB.numSuccessfulCommits);
        } catch (ConflictException e) {
          sc.increment(ResumeTxBB.numFailedCommits);
        }
        sc.increment(ResumeTxBB.numCommits);
        sc.increment(ResumeTxBB.numCompletedTxns);
        sc.add(ResumeTxBB.numResumesAtCompletion, testInstance.resumeCount.get(txIdIndex)+1);
      }
    }
    for (TransactionId txId: testInstance.txIdList) {
      if (ctm.isSuspended(txId)) {
        throw new TestException("After completing all transactions, " + txId + " is suspended");
      }
    }
  }
  
  /** Write a snapshot of all regions to the blackboard
   * 
   */
  public static void HydraTask_takeSnapshot() {
    Map<String, Map> masterMap = new HashMap<String, Map>();
    for (Region aRegion: CacheHelper.getCache().rootRegions()) {
      Map snapshot = new HashMap();
      for (Object key: aRegion.keySet()) {
        snapshot.put(key, aRegion.get(key));
      }
      masterMap.put(aRegion.getFullPath(), snapshot);
      Log.getLogWriter().info("Created snapshot for " + aRegion.getFullPath() + " of size " + snapshot.size());
    }
    ResumeTxBB.getBB().getSharedMap().put(snapshotKey, masterMap);
    Log.getLogWriter().info("Snapshots for " + masterMap.keySet() + " have been written to the blackboard");
  }
  
  /** Verifies all regions from the snapshot
   * 
   */
  public static void HydraTask_verifyFromSnapshot() {
    Map<String, Map> masterMap = (Map<String, Map>)(ResumeTxBB.getBB().getSharedMap().get(snapshotKey));
    for (String regionName: masterMap.keySet()) {
      Region aRegion = CacheHelper.getCache().getRegion(regionName);
      if (aRegion == null) {
        throw new TestException("Could not get region with name " + regionName);
      }
      verifyFromSnapshot(aRegion, (masterMap.get(regionName)));
    }
  }
  
  public static void HydraTask_waitForSilence() {
    SummaryLogListener.waitForSilence(30, 5000);
  }

  /** Verify a region against the given expected map
   * 
   * @param aRegion The region to verify.
   * @param expectedMap The expected contents of the region.
   */
  private static void verifyFromSnapshot(Region aRegion, Map expectedMap) {
    Log.getLogWriter().info("Verifying " + aRegion.getFullPath() + " against snapshot of size " + expectedMap.size());
    StringBuffer aStr = new StringBuffer();
    int regionSize = aRegion.size();
    int expectedSize = expectedMap.size();
    if (regionSize != expectedSize) {
      aStr.append(aRegion.getFullPath() + " size is " + regionSize + " but expected it to be " + expectedSize + "\n");
    }
    Set expectedKeys = new HashSet(expectedMap.keySet());
    Set actualKeys = new HashSet(aRegion.keySet());
    Set missingKeys = new HashSet(expectedKeys);
    missingKeys.removeAll(actualKeys);
    Set extraKeys = new HashSet(actualKeys);
    extraKeys.removeAll(expectedKeys);
    if (missingKeys.size() > 0) {
      aStr.append("The following " + missingKeys.size() + " expected keys were missing from " + aRegion.getFullPath() + ": " + missingKeys + "\n");
    }
    if (extraKeys.size() > 0) {
      aStr.append("The following " + extraKeys.size() + " extra keys were found in " + aRegion.getFullPath() + ": " + extraKeys + "\n");
    }
    
    // now for those keys that exist in aRegion, verify their values
    for (Object key: aRegion.keySet()) {
      Object value = aRegion.get(key);
      if (expectedMap.containsKey(key)) {
        Object expectedValue = expectedMap.get(key);
        if ((value == null) || (expectedValue == null)) {
          aStr.append("For key " + key + ", expectedValue is " + expectedValue + " and value in region is " +
              value + ", but did not expect null values\n");
        } else {
          if (!value.equals(expectedValue)) {
            aStr.append("Expected value for key " + key + " to be " + expectedValue + " but it is " + value + "\n");
          }
        }
      }
    }
    
    if (aStr.length() > 0) {
      throw new TestException(aStr.toString());
    }
  }

  /** Log the size of each root region
   * 
   */
  private static void logRegionSizes() {
    for (Region aRegion: CacheHelper.getCache().rootRegions()) {
      Log.getLogWriter().info("Region " + aRegion.getFullPath() + " is size " + aRegion.size());
    }
  }

  /** Initialize an instance of this test class, called once per vm.
   * 
   */
  private void initializeInstance() {
    isBridgeConfiguration = TestConfig.tab().vecAt(BridgePrms.names, null) != null;
    numVMsInTest = TestHelper.getNumVMs();
  }

  /** Create regions specified in RegionPrms.names
   * 
   * @param edgeRegions If true, create regions with region config names that
   *                    begin with "edge", if false create regions with region config
   *                    names that do not begin with "edge". This divides server
   *                    regions from edge client regions.
   * 
   */
  private void createRegions(boolean edgeRegions) {
    CacheHelper.createCache("cache1");
    Vector<String> aVec = TestConfig.tab().vecAt(RegionPrms.names);
    for (String regionConfigName: aVec) {
      if (regionConfigName.startsWith("edge") == edgeRegions) {
        Log.getLogWriter().info("Creating region " + regionConfigName);
        RegionHelper.createRegion(regionConfigName);
      }
    }
  }

  /** Randomly tries to resume a transaction, do put ops, then either suspend, commit or rollback.
   * 
   */
  private void concSuspendResumeTx() {
    Cache theCache = CacheHelper.getCache();
    CacheTransactionManager ctm = theCache.getCacheTransactionManager();
    List<Region> regions = new ArrayList<Region>(theCache.rootRegions());
    GsRandom rand = TestConfig.tab().getRandGen();
    long minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, 60);
    long minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
    long startTime = System.currentTimeMillis();
    SharedCounters sc = ResumeTxBB.getBB().getSharedCounters();
    do {
      int txIdIndex = rand.nextInt(0, txIdList.size()-1);
      TransactionId txId = txIdList.get(txIdIndex);
      boolean successfulResume = ctm.tryResume(txId);
      if (successfulResume) {
        Log.getLogWriter().info("Successfully resumed " + txId);
        TransactionId currentlyResumed = resumed.get(txIdIndex);
        if (currentlyResumed != null) {
          throw new TestException("Test was able to resume txId " + txId + ", but it was already resumed by " + currentlyResumed);
        }
        if (ctm.isSuspended(txId)) {
          throw new TestException("Just resumed tx " + txId + ", but isSuspended returned true");
        }
        if (!ctm.exists()) {
          throw new TestException("Just resumed tx " + txId + ", but exists() returned false");
        }
        sc.increment(ResumeTxBB.numResumes);
        int count = resumeCount.get(txIdIndex) + 1;
        resumeCount.set(txIdIndex, count);
        Log.getLogWriter().info(txId + " has been resumed " + count + " times");
        Log.getLogWriter().info("Putting into regions after resuming " + txId);
        doOpsInRandomRegions(regions, rand, true);

        // end the tx by suspend, commit or rollback
        resumed.set(txIdIndex, null); // about to not be in a resumed state
        int desiredResumeCount = rand.nextInt(MIN_RESUME_COUNT, MAX_RESUME_COUNT);
        Log.getLogWriter().info("Desired resume count is " + desiredResumeCount + " actual resume count is " + count);
        if (count < desiredResumeCount) { // just suspend
          TxHelper.suspend();
        } else {
          int randInt = rand.nextInt(1, 100);
          if (randInt <= 75) { // commit
            try {
              TxHelper.commit();
              sc.increment(ResumeTxBB.numSuccessfulCommits);
            } catch (ConflictException e) {
              sc.increment(ResumeTxBB.numFailedCommits);
            }
            sc.increment(ResumeTxBB.numCommits);
          } else {
            TxHelper.rollback();
            sc.increment(ResumeTxBB.numRollbacks);
          }
          sc.increment(ResumeTxBB.numCompletedTxns);
          // sleep to allow some threads to grab and try to resume this tx which is permanently done (it was committed
          // or rolled back)
          MasterController.sleepForMs(5000);
          TxHelper.begin();
          sc.increment(ResumeTxBB.numBegins);
          sc.add(ResumeTxBB.numResumesAtCompletion, count);
          Log.getLogWriter().info("Successfully began new transaction " + ctm.getTransactionId());
          resumeCount.set(txIdIndex, 0);
          synchronized (txKeySetList) {
            txKeySetList.set(txIdIndex, new HashSet());
            txIdList.set(txIdIndex, ctm.suspend()); // now other threads can get to it
          }
        }
      } else {
        Log.getLogWriter().info("tryResume returned false, this thread was unable to resume " + txId);
        sc.increment(ResumeTxBB.numFailedTries);
      }
    } while (System.currentTimeMillis() - startTime < minTaskGranularityMS);
    logRegionSizes();
  }

  /** Execute operations in random regions
   * 
   * @param regions The List of regions to choose from.
   * @param rand A random number generator to use to choose ops.
   * @param useLocalKeys If true, use only keys that will be hosted in this vm.
   */
  private void doOpsInRandomRegions(List<Region> regions, GsRandom rand, boolean useLocalKeys) {
    Log.getLogWriter().info("Doing " + NUM_OPS + " operations with useLocalKeys " + useLocalKeys);
    TransactionId txId = CacheHelper.getCache().getCacheTransactionManager().getTransactionId();
    for (int j = 1; j <= NUM_OPS; j++) {
      Region<Object, Object> aRegion = regions.get(rand.nextInt(0, regions.size()-1));
      if (aRegion.size() >= REGION_SIZE) { // do a destroy or update to control the region size
        // first get an existing key
        Object key = getExistingKey(aRegion, useLocalKeys);
        if (key == null) {
          continue;
        }
        if (TestConfig.tab().getRandGen().nextBoolean()) { // update
          Object value = ResumeTxBB.getBB().getSharedCounters().incrementAndRead(ResumeTxBB.valueCounter);
          Log.getLogWriter().info("Operation for key " + key + ", updating in region " + aRegion.getFullPath() + ", transactionID " + txId +
              ", value is " + TestHelper.toString(value));
          aRegion.put(key, value);
        } else {
          Log.getLogWriter().info("Operation for key " + key + ", destroying in region " + aRegion.getFullPath() + ", transactionID " + txId);
          try {
            aRegion.destroy(key);
          } catch (EntryNotFoundException e) {
            if (txId != null) { // we are inside a tx and we do not expect this
              throw e;
            } else {
              Log.getLogWriter().info("Caught expected " + e + " while outside a tx");
            }
          }
        }
      } else { // create
        Object key = getNewKey(aRegion, useLocalKeys);
        Object value = ResumeTxBB.getBB().getSharedCounters().incrementAndRead(ResumeTxBB.valueCounter);
        Log.getLogWriter().info("Operation for key " + key + ", creating new entry in region " + aRegion.getFullPath() + 
            ", transactionId " + txId + ", value is " + TestHelper.toString(value));
        aRegion.put(key, value);
      }
    }
  }
  
  /** Execute operations in random regions
   * 
   * @param regions The List of regions to choose from.
   * @param rand A random number generator to use to choose ops.
   * @param useLocalKeys If true, use only keys that will be hosted in this vm.
   */
  private void doNewKeyPutsInRandomRegions(List<Region> regions, GsRandom rand, boolean useLocalKeys) {
    Log.getLogWriter().info("Doing " + NUM_OPS + " new key puts with useLocalKeys " + useLocalKeys);
    TransactionId txId = CacheHelper.getCache().getCacheTransactionManager().getTransactionId();
    for (int j = 1; j <= NUM_OPS; j++) {
      Region<Object, Object> aRegion = regions.get(rand.nextInt(0, regions.size()-1));
      String key = (String) getNewKey(aRegion, useLocalKeys);
      Object value = ResumeTxBB.getBB().getSharedCounters().incrementAndRead(ResumeTxBB.valueCounter);
      Log.getLogWriter().info("Operation for key " + key + ", creating new entry in region " + aRegion.getFullPath() + 
          ", transactionId " + txId + ", value is " + TestHelper.toString(value));
      aRegion.put(key, value);
    }
  }
  
  /** Get a new key for the region that is unique according to open transactions (ie
   *  use a key unique to this transaction). Open transactions are those 
   *  in txIdList (transactions that have not ended by either commit or rollback)
   *  
   * @param aRegion The region to get a new key for.
   * @param useLocalKeys If true, then the key returned should be hosted locally, if false
   *                     the the key does not need to be hosted locally.
   * @return A new key for aRegion.
   */
  private Object getNewKey(Region<Object, Object> aRegion, boolean useLocalKeys) {
    TransactionId txId = CacheHelper.getCache().getCacheTransactionManager().getTransactionId();
    Object key;
    if (useLocalKeys) { // get new key that will be hosted locally
      synchronized (txKeySetList) {  // make sure the new key becomes registered for this txID to avoid conflicts
        key = txUtilInstance.getNewKey(aRegion);
        int index = txIdList.indexOf(txId);
        txKeySetList.get(index).add(key);
      }
    } else {
      key = NameFactory.getNextPositiveObjectName();
    }
    return key;
  }

  /** Get an existing key for the region that is unique according to open transactions (ie
   *  don't use a key currently in a different tx than ours). Open transactions are those 
   *  in txIdList (transactions that have not ended by either commit or rollback)
   *  
   * @param aRegion The region to get an existing key for.
   * @param useLocalKeys If true, then the key returned should be hosted locally, if false
   *                     the the key does not need to be hosted locally.
   * @return A key from aRegion or null if none were available.
   */
  private Object getExistingKey(Region<Object, Object> aRegion, boolean useLocalKeys) {
    CacheTransactionManager ctm = CacheHelper.getCache().getCacheTransactionManager();
    TransactionId txId = ctm.getTransactionId();
    Set aSet = null;
    if (useLocalKeys) {
      if (PartitionRegionHelper.isPartitionedRegion(aRegion)) {
        aSet = PartitionRegionHelper.getLocalPrimaryData(aRegion).keySet();
      } else {
        aSet = new HashSet(aRegion.keySet());
      }
    } else {
      aSet = new HashSet(aRegion.keySet());
      try {
        return aSet.iterator().next();
      } catch (NoSuchElementException e) {
        return null;
      }
    }
    synchronized (txKeySetList) {
      int myVmId = RemoteTestModule.getMyVmid();
      Set masterSet = new HashSet();
      int index = txIdList.indexOf(txId);
      for (int i = 0; i < txKeySetList.size(); i++) {
        if (i != index) {
          masterSet.addAll(txKeySetList.get(i));
        }
      }
      //Log.getLogWriter().info("txId is " + txId + " txIdList is " + txIdList + " index is " + index + " masterset is " + masterSet);
      for (Object key: aSet) {
        if (!masterSet.contains(key)) { // not in use by another tx in this vm
          long keyIndex = NameFactory.getCounterForName(key);
          if ((keyIndex % numVMsInTest) == myVmId) {
            txKeySetList.get(index).add(key);
            return key;
          }
        }
      }
    }
    return null;
  }

  /** Register interest in all regions
   * 
   */
  private static void registerInterest() {
    for (Region aRegion: CacheHelper.getCache().rootRegions()) {
      ParRegUtil.registerInterest(aRegion);
    }
  }
  
}
