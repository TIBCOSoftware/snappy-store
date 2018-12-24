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
package newWan;

import java.util.*;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import hydra.*;
import util.*;

public class WANOperationsClient extends OperationsClient {

  public static String SNAPSHOT_FOR_UNIQUE_KEY_PREFIX = "SnapshotForUniqueKey_";
  public static String SNAPSHOT_FOR_REGION_PREFIX = "SnapshotForRegion_";
  public static String SNAPSHOT_VM_FOR_REGION = "SnapshotVMForRegion_";
  
  public static enum SenderState {SENDER_RUNNING, SENDER_STOPPED, SENDER_PAUSED};
  public static enum SenderOperation {START, STOP, PAUSE, RESUME};
  
  protected boolean useUniqueKeyPerThread; // whether each thread should use
                                           // unique keys or not
                                           // todo: termination should happen based on newWan.WANOperationsClientPrms-taskTerminationMethod instead of useUniqueKeyPerThread
  protected int maxKeys;
  protected int keyAllocation;
  protected int wanSiteId;
  protected int numWanSites;

  public WANOperationsClientBB bb;
  static LogWriter logger = Log.getLogWriter();
  private Object senderLock = new Object();  //sync sender operations 

  public WANOperationsClient(){
    initialize();
  }
  
  public void initialize() {
    super.initializeOperationsClient();
    useUniqueKeyPerThread = TestConfig.tab().booleanAt(
        WANOperationsClientPrms.useUniqueKeyPerThread, false);

    this.maxKeys = WANOperationsClientPrms.getMaxKeys();
    this.keyAllocation = WANOperationsClientPrms.getKeyAllocation();
    this.wanSiteId = getWanId();
    
    String sWanSites = TestConfig.getInstance().getSystemProperty("wanSites");
    this.numWanSites = (sWanSites != null)? Integer.parseInt(sWanSites) : 0;
    
    this.bb = WANOperationsClientBB.getBB();
    logger.info("maxKeys=" + this.maxKeys 
                + ", keyAllocation=" + WANOperationsClientPrms.keyAllocationToString(this.keyAllocation)
                + ", numWanSites=" + this.numWanSites
                + ", wanSiteId=" + this.wanSiteId
                + ", useUniqueKeyPerThread=" + useUniqueKeyPerThread);
  }

  /** Do random entry operations on the given region ending either with
   *  minTaskGranularityMS or numOpsPerTask.
   */
   protected void doEntryOperations(Region aRegion) {
     Log.getLogWriter().info("In doEntryOperations with " + aRegion.getFullPath());

     long startTime = System.currentTimeMillis();
     int numOps = 0;
     boolean rolledback = false;
     
     // In transaction tests, package the operations into a single transaction
     if (useTransactions) {
       TxHelper.begin();
     }

     do {
        // check for max operations
        long opsCounter = WANBlackboard.getInstance().getSharedCounters().read(WANBlackboard.operation_counter);
        if(opsCounter >= WANTestPrms.getMaxOperations()){
          //stop scheduling task
          if (useTransactions && !rolledback) {
            logger.info("Time to stop as max number of operations reached. Operations=" 
                + opsCounter + ". Finishing the transaction.");
            finishTransaction();
          }
          //update blackboard so that other running task can check this
          WANOperationsClientBB.getBB().getSharedMap().put(WANOperationsClientBB.IS_TASK_SCHEDULING_STOPPED, new Boolean(true));
          throw new StopSchedulingOrder("Time to stop as max number of operations reached. Operations=" + opsCounter);         
        }
        
        opsCounter = WANBlackboard.getInstance().getSharedCounters().incrementAndRead(WANBlackboard.operation_counter);
        logger.info("Working on operation counter =" + opsCounter);
        

        String lockName = null;
        boolean thisVMReceivedNiceKill = StopStartVMs.niceKillInProgress();
        boolean gotTheLock = false;
        if (lockOperations) {
           lockName = LOCK_NAME + TestConfig.tab().getRandGen().nextInt(1, 20);
           Log.getLogWriter().info("Trying to get distributed lock " + lockName + "...");
           gotTheLock = distLockService.lock(lockName, -1, -1);
           if (!gotTheLock) {
              throw new TestException("Did not get lock " + lockName);
           }
           Log.getLogWriter().info("Got distributed lock " + lockName + ": " + gotTheLock);
        }

        try {
          int whichOp = getOperation(OperationsClientPrms.entryOperations);
          int size = aRegion.size();
          if (size >= upperThreshold) {
             whichOp = getOperation(OperationsClientPrms.upperThresholdOperations);
          } else if (size <= lowerThreshold) {
             whichOp = getOperation(OperationsClientPrms.lowerThresholdOperations);
          }  
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
                  putIfAbsent(aRegion, true);
                  break;
              case REMOVE_OPERATION:
                  remove(aRegion);
                  break;
              case REPLACE_OPERATION:
                  replace(aRegion);
                  break;
              case ENTRY_PUTALL_OPERATION:
                putAll(aRegion);
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
              rolledback = true;
              TxHelper.rollback();
              Log.getLogWriter().info("Done Rolling back Transaction");
            } catch (TransactionException te) {
              Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching TransactionDataNodeHasDeparted during tx ops.  Expected, continuing test.");
            }
          }
        } catch (TransactionDataRebalancedException e) {
          if (!useTransactions) {
            throw new TestException("Unexpected Exception " + e + ". " + TestHelper.getStackTrace(e));
          } else {
            Log.getLogWriter().info("Caught Exception " + e + ".  Expected with concurrent execution, continuing test.");
            Log.getLogWriter().info("Rolling back transaction.");
            try{
              rolledback = true;            
              TxHelper.rollback();
              Log.getLogWriter().info("Done Rolling back Transaction");
            }catch (TransactionException te){
              Log.getLogWriter().info("Caught exception " + te + " on rollback() after catching TransactionDataRebalancedException during tx ops.  Expected, continuing test.");
            }                        
          }
       } catch (CacheClosedException except) {   
	       if (StopStartVMs.niceKillInProgress()) {
	           // a thread in this VM closed the cache or disconnected from the dist system
	           // or we are undergoing a nice_kill; all is OK
	        } else { // no reason to get this error
	           throw new TestException(TestHelper.getStackTrace(except));
	        }
	    } finally {
          if (gotTheLock) {
             gotTheLock = false;
             distLockService.unlock(lockName);
             Log.getLogWriter().info("Released distributed lock " + lockName);
          }
       }
       numOps++;
       Log.getLogWriter().info("Completed op " + numOps + " for this task");
    } while ((System.currentTimeMillis() - startTime < minTaskGranularityMS) &&
             (numOps < numOpsPerTask));

     // finish transactions (commit or rollback)
     if (useTransactions && !rolledback) {
       finishTransaction();
     }
     Log.getLogWriter().info("Done with doEntryOperations on " + aRegion.getFullPath() + ", operations done=" + numOps);
   }
   
  private void finishTransaction() {
    int n = 0;
    int commitPercentage = OperationsClientPrms.getCommitPercentage();
    n = TestConfig.tab().getRandGen().nextInt(1, 100);

    if (n <= commitPercentage) {
      try {
        TxHelper.commit();
      }
      catch (TransactionDataNodeHasDepartedException e) {
        Log.getLogWriter().info(
            "Caught TransactionDataNodeHasDepartedException.  "
                + "Expected with concurrent execution, continuing test.");
      }
      catch (TransactionInDoubtException e) {
        Log.getLogWriter().info(
            "Caught TransactionInDoubtException.  "
                + "Expected with concurrent execution, continuing test.");
      }
      catch (ConflictException e) {
        Log.getLogWriter().info(
            "ConflictException " + e + " expected, continuing test");
      }
      catch (TransactionDataRebalancedException e) {
        Log.getLogWriter().info(
            "TransactionDataRebalancedException " + e + " expected, continuing test");
      }
    }
    else {
      TxHelper.rollback();
    }
  }
   
  /**
   * Add a new entry to the given region.
   * 
   * @param aRegion
   *          The region to use for adding a new entry.
   * 
   */
  protected void addEntry(Region aRegion) {
    Object key = getNewKey();
    BaseValueHolder anObj = getValueForKey(key);
    String callback = createCallbackPrefix + ProcessMgr.getProcessId();
    Object val = aRegion.get(key);
    if (val == null) { // use a create call
      if (TestConfig.tab().getRandGen().nextBoolean()) { // use a create call
        // with cacheWriter arg
        Log.getLogWriter().info(
            "addEntry: calling create for key " + key + ", object "
                + TestHelper.toString(anObj) + " cacheWriterParam is "
                + callback + ", region is " + aRegion.getFullPath());
        aRegion.create(key, anObj, callback);
        Log.getLogWriter().info("addEntry: done creating key " + key);
      }
      else { // use create with no cacheWriter arg
        Log.getLogWriter().info(
            "addEntry: calling create for key " + key + ", object "
                + TestHelper.toString(anObj) + ", region is "
                + aRegion.getFullPath());
        aRegion.create(key, anObj);
        Log.getLogWriter().info("addEntry: done creating key " + key);
      }
    }
    else { // use a put call
      if (TestConfig.tab().getRandGen().nextBoolean()) { // use a put call with
        // callback arg
        Log.getLogWriter().info(
            "addEntry: calling put for key " + key + ", object "
                + TestHelper.toString(anObj) + " callback is " + callback
                + ", region is " + aRegion.getFullPath());
        aRegion.put(key, anObj, callback);
        Log.getLogWriter().info("addEntry: done putting key " + key);
      }
      else {
        Log.getLogWriter().info(
            "addEntry: calling put for key " + key + ", object "
                + TestHelper.toString(anObj) + ", region is "
                + aRegion.getFullPath());
        aRegion.put(key, anObj);
        Log.getLogWriter().info("addEntry: done putting key " + key);
      }
    }

    if (useUniqueKeyPerThread) {
      updateBlackboardSnapshot(aRegion, key, anObj, false);
    }
  }

  /**
   * ConcurrentMap API testing
   */
  protected void putIfAbsent(Region aRegion, boolean logAddition) {
    Object key = null;

    // Expect success most of the time (put a new entry into the cache)
    int randInt = TestConfig.tab().getRandGen().nextInt(1, 100);
    if (randInt <= 25) {
      key = getExistingKey(aRegion);
    }

    if (key == null) {
      key = getNewKey();
    }
    Object anObj = getValueForKey(key);

    if (logAddition) {
      Log.getLogWriter().info(
          "putIfAbsent: calling putIfAbsent for key " + key + ", object "
              + TestHelper.toString(anObj) + ", region is "
              + aRegion.getFullPath() + ".");
    }

    Object prevVal = null;
    prevVal = aRegion.putIfAbsent(key, anObj);

    if (prevVal==null && useUniqueKeyPerThread) {
      updateBlackboardSnapshot(aRegion, key, anObj, false);
    }
    
    if (logAddition) {
      Log.getLogWriter().info("putIfAbsent: done putIfAbsent for key " + key + " on region "+ aRegion.getName()+ ", success=" + (prevVal==null));
    }
  }

  /**
   * putall a map to the given region.
   *
   * @param r The region to use for putall a map.
   */
  protected void putAll(Region r) {
    // determine the number of new keys to put in the putAll
    int beforeSize = r.size();
    String numPutAllNewKeys = TestConfig.tab().stringAt(
        WANOperationsClientPrms.numPutAllNewKeys, "1");
    int numNewKeysToPut = 0;
    if (numPutAllNewKeys.equalsIgnoreCase("useThreshold")) {
      numNewKeysToPut = upperThreshold - beforeSize;
      if (numNewKeysToPut <= 0) {
        numNewKeysToPut = 1;
      }
      else {
        int max = TestConfig.tab().intAt(
            WANOperationsClientPrms.numPutAllMaxNewKeys, numNewKeysToPut);
        max = Math.min(numNewKeysToPut, max);
        int min = TestConfig.tab().intAt(
            WANOperationsClientPrms.numPutAllMinNewKeys, 1);
        min = Math.min(min, max);
        numNewKeysToPut = TestConfig.tab().getRandGen().nextInt(min, max);
      }
    }
    else {
      numNewKeysToPut = Integer.valueOf(numPutAllNewKeys).intValue();
    }

    // get a map to put
    Map mapToPut = null;
    int randInt = TestConfig.tab().getRandGen().nextInt(1, 100);
    if (randInt <= 25) {
      mapToPut = new HashMap();
    }
    else if (randInt <= 50) {
      mapToPut = new Hashtable();
    }
    else if (randInt <= 75) {
      mapToPut = new TreeMap();
    }
    else {
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
    int numPutAllExistingKeys = TestConfig.tab().intAt(
        WANOperationsClientPrms.numPutAllExistingKeys, 1);
    List keyList = getExistingKeys(r, numPutAllExistingKeys);
    StringBuffer existingKeys = new StringBuffer();
    if (keyList.size() != 0) { // no existing keys could be found
      for (int i = 0; i < keyList.size(); i++) { // put existing keys
        Object key = keyList.get(i);
        BaseValueHolder anObj = getUpdateObject(r, key);
        mapToPut.put(key, anObj);
        existingKeys.append(key + " ");
        if (((i + 1) % 10) == 0) {
          existingKeys.append("\n");
        }
      }
    }
    Log.getLogWriter().info(
        "Region size is " + r.size() + ", map to use as argument to putAll is "
            + mapToPut.getClass().getName() + " containing " + numNewKeysToPut
            + " new keys and " + keyList.size()
            + " existing keys (updates); total map size is " + mapToPut.size()
            + "\nnew keys are: " + newKeys + "\n" + "existing keys are: "
            + existingKeys);
    for (Object key : mapToPut.keySet()) {
      Log.getLogWriter().info(
          "putAll map key " + key + ", value "
              + TestHelper.toString(mapToPut.get(key)));
    }

    // do the putAll
    Log.getLogWriter().info(
        "putAll: calling putAll with map of " + mapToPut.size() + " entries");
    r.putAll(mapToPut);
    Log.getLogWriter().info(
        "putAll: done calling putAll with map of " + mapToPut.size()
            + " entries");

    if (useUniqueKeyPerThread) {
      for (Object entry : mapToPut.entrySet()) {
        Map.Entry e = (Map.Entry)entry;
        updateBlackboardSnapshot(r, e.getKey(), e.getValue(), false);        
      }
    }
  }

  /**
   * Destroy an entry in the given region.
   * 
   * @param aRegion
   *          The region to use for destroying an entry.
   * @param isLocalDestroy
   *          True if the destroy should be local, false otherwise.
   */
  protected void destroyEntry(Region aRegion, boolean isLocalDestroy) {
    Object key = getExistingKey(aRegion);
    if (key == null) {
      int size = aRegion.size();
      return;
    }
    try {
      String callback = destroyCallbackPrefix + ProcessMgr.getProcessId();
      if (isLocalDestroy) { // do a local destroy
        if (TestConfig.tab().getRandGen().nextBoolean()) { // local destroy with
          // callback
          Log.getLogWriter().info(
              "destroyEntry: local destroy for " + key + " callback is "
                  + callback);
          aRegion.localDestroy(key, callback);
          Log.getLogWriter().info(
              "destroyEntry: done with local destroy for " + key);
        }
        else { // local destroy without callback
          Log.getLogWriter().info("destroyEntry: local destroy for " + key);
          aRegion.localDestroy(key);
          Log.getLogWriter().info(
              "destroyEntry: done with local destroy for " + key);
        }
      }
      else { // do a distributed destroy
        if (TestConfig.tab().getRandGen().nextBoolean()) { // destroy with
          // callback
          Log.getLogWriter().info(
              "destroyEntry: destroying key " + key + " callback is "
                  + callback);
          aRegion.destroy(key, callback);
          Log.getLogWriter().info("destroyEntry: done destroying key " + key);
        }
        else { // destroy without callback
          Log.getLogWriter().info("destroyEntry: destroying key " + key);
          aRegion.destroy(key);
          Log.getLogWriter().info("destroyEntry: done destroying key " + key);
        }
        // unique key validation does not support local destroy
        if (useUniqueKeyPerThread) {
          updateBlackboardSnapshot(aRegion, key, null, true);
        }
      }
    }
    catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
      Log.getLogWriter().info(
          "Caught " + e
              + " (expected with concurrent execution); continuing with test");
      return;
    }
  }

  /**
   * ConcurrentMap API testing
   **/
  protected void remove(Region aRegion) {
    boolean removed = false;
    Object key = getExistingKey(aRegion);
    if (key == null) {
      Log.getLogWriter().info(
          "remove: No key in region " + aRegion.getFullPath());
      return;
    }
    else {
      try {
        Object oldVal = aRegion.get(key);        
        Log.getLogWriter().info("remove: removing key " + key + " with previous value " + oldVal);
        // Force the condition to not be met (small percentage of the time)        
        int randInt = TestConfig.tab().getRandGen().nextInt(1, 100);
        if (randInt <= 25) {
          oldVal = getUpdateObject(aRegion, key);
          Log.getLogWriter().info("remove: modifying the value for key " + key + " with value=" + oldVal);
        }
        
        removed = aRegion.remove(key, oldVal);
        Log.getLogWriter().info("remove: Done remove key " + key + ", success=" + removed + " oldValue=" + oldVal);
      }
      catch (NoSuchElementException e) {
        throw new TestException("Bug 30171 detected: "
            + TestHelper.getStackTrace(e));
      }
      catch (com.gemstone.gemfire.cache.EntryNotFoundException e) {
        Log.getLogWriter().info("Caught " + e + " (expected with concurrent execution); continuing with test");
        return;
     }
    }

    if (removed && useUniqueKeyPerThread) {
      updateBlackboardSnapshot(aRegion, key, null, true);
    }
  }

  /**
   * Update an existing entry in the given region. If there are no available
   * keys in the region, then this is a noop.
   * 
   * @param aRegion
   *          The region to use for updating an entry.
   */
  protected void updateEntry(Region aRegion) {
    Object key = getExistingKey(aRegion);
    if (key == null) {
      int size = aRegion.size();
      return;
    }
    BaseValueHolder anObj = getUpdateObject(aRegion, key);
    String callback = updateCallbackPrefix + ProcessMgr.getProcessId();
    if (TestConfig.tab().getRandGen().nextBoolean()) { // do a put with callback
      // arg
      Log.getLogWriter().info(
          "updateEntry: replacing key " + key + " with "
              + TestHelper.toString(anObj) + ", callback is " + callback);
      aRegion.put(key, anObj, callback);
      Log.getLogWriter().info("Done with call to put (update) for key " + key);
    }
    else { // do a put without callback
      Log.getLogWriter().info(
          "updateEntry: replacing key " + key + " with "
              + TestHelper.toString(anObj));
      aRegion.put(key, anObj);
      Log.getLogWriter().info("Done with call to put (update) for key " + key);
    }

    if (useUniqueKeyPerThread) {
      updateBlackboardSnapshot(aRegion, key, anObj, false);
    }
  }

  /**
   * Updates the "first" entry in a given region
   */
  protected void replace(Region aRegion) {
    Object key = getExistingKey(aRegion);      
    if (key == null) {
        Log.getLogWriter().info("replace: No names in region");
        return;
    }    
    replace(aRegion, key);
  }
  
  /**
   * Updates the entry with the given key (<code>name</code>) in the given
   * region.
   */
  protected void replace(Region aRegion, Object name) {
    Object anObj = null;
    Object prevVal = null;
    boolean replaced = false;
    try {
      anObj = aRegion.get(name);
    }
    catch (CacheLoaderException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    }
    catch (TimeoutException e) {
      throw new TestException(TestHelper.getStackTrace(e));
    }

    Object newObj = getUpdateObject(aRegion, name);
    boolean isOldObjectUpdated = false;
    // 1/2 of the time use oldVal => newVal method
    if (TestConfig.tab().getRandGen().nextBoolean()) {
      if (TestConfig.tab().getRandGen().nextBoolean()) {
        // Force the condition to not be met
        // get a new oldVal to cause this
        anObj = getUpdateObject(aRegion, name);
        isOldObjectUpdated = true;
      }

      Log.getLogWriter().info(
          "replace: replacing key " + name + " with "
              + TestHelper.toString(newObj) + "; old value is "
              + TestHelper.toString(anObj) + ", isOldObjectUpdated=" + isOldObjectUpdated);
      replaced = aRegion.replace(name, anObj, newObj);
    }
    else {
      boolean isNewKey = false;
      if (TestConfig.tab().getRandGen().nextBoolean()) {
        // Force the condition to not be met
        // use a new key for this
        name = getNewKey();
        isNewKey = true;
      }
      Log.getLogWriter().info(
          "replace: replacing key " + name + " (isNewKey=" + isNewKey + ") with "
              + TestHelper.toString(newObj) + ".");
      prevVal = aRegion.replace(name, newObj);

      if (prevVal != null)
        replaced = true;
    }

    if (replaced && useUniqueKeyPerThread) {
      updateBlackboardSnapshot(aRegion, name, newObj, false);
    }

    Log.getLogWriter().info("Done with call to replace for key " + name + ", success=" + replaced);
  }

  /**
   * Return a new key, never before used in the test.
   */
  protected Object getNewKey() {
    if (useUniqueKeyPerThread) {
      return NameFactory.getObjectNameForCounter(getNextUniqueKeyCounter(-1));
    } else if (this.keyAllocation == WANOperationsClientPrms.OWN_KEYS){
      return NameFactory.getObjectNameForCounter(getNextUniqueKeyCounter(-1));
    } else if (this.keyAllocation == WANOperationsClientPrms.OWN_KEYS_WRAP){
      return NameFactory.getObjectNameForCounter(getNextUniqueKeyCounter(this.maxKeys));
    } else if(this.keyAllocation == WANOperationsClientPrms.WAN_KEYS_WRAP){
      String mapKey = "WanId_" + this.wanSiteId;
      Object currentCnt  = bb.getSharedMap().get(mapKey);
      
      int nextKeyCounter = (currentCnt == null) ? this.wanSiteId 
                            : ((Integer)currentCnt).intValue() + this.numWanSites;
      if(nextKeyCounter > this.maxKeys){
        nextKeyCounter = this.wanSiteId;
      }
      bb.getSharedMap().put(mapKey, new Integer(nextKeyCounter));      
      return NameFactory.getObjectNameForCounter(nextKeyCounter);
    } else if (this.keyAllocation == WANOperationsClientPrms.NEXT_KEYS){
      return NameFactory.getNextPositiveObjectName();
    } else if (this.keyAllocation == WANOperationsClientPrms.NEXT_KEYS_WRAP){
      return NameFactory.getNextPositiveObjectNameInLimit(this.maxKeys);
    }
    else {
      return NameFactory.getNextPositiveObjectName();
    }
  }

  /**
   * Return a random key currently in the given region.
   * 
   * @param aRegion
   *          The region to use for getting an existing key (may or may not be a
   *          partitioned region).
   * 
   * @returns A key in the region.
   */
  protected Object getExistingKey(Region aRegion) {
    List keyList = new ArrayList();
    keyList.addAll(aRegion.keySet());
    if (keyList.size() == 0) {
      return null;
    }
    GsRandom rand = TestConfig.tab().getRandGen();
    int myTid = RemoteTestModule.getCurrentThread().getThreadId();
    while (keyList.size() != 0) {
      int randInt = rand.nextInt(0, keyList.size() - 1);
      Object key = keyList.get(randInt);
      long keyIndex = NameFactory.getCounterForName(key);
      if (useUniqueKeyPerThread) {
        if ((keyIndex % TestHelper.getNumThreads()) == myTid) {
          return key;
        }
      }else if(this.keyAllocation == WANOperationsClientPrms.OWN_KEYS 
          || this.keyAllocation == WANOperationsClientPrms.OWN_KEYS_WRAP){
        if ((keyIndex % TestHelper.getNumThreads()) == myTid) {
          return key;
        }
      } else if(this.keyAllocation == WANOperationsClientPrms.WAN_KEYS_WRAP){
        if (keyIndex % this.numWanSites == this.wanSiteId){          
          return key;
        }
      } else {
        return key;
      }
      keyList.remove(randInt);
    }
    return null;
  }

  protected List getExistingKeys(Region aRegion, int numKeysToGet) {
    Log.getLogWriter().info(
        "Trying to get " + numKeysToGet + " existing keys from region "
            + aRegion.getFullPath());
    List keyListToReturn = new ArrayList();
    List keyList = new ArrayList();
    keyList.addAll(aRegion.keySet());
    if (keyList.size() == 0) {
      return keyListToReturn;
    }
    GsRandom rand = TestConfig.tab().getRandGen();
    int myTid = RemoteTestModule.getCurrentThread().getThreadId();
    while (keyList.size() != 0 && keyListToReturn.size() >= numKeysToGet) {
      int randInt = rand.nextInt(0, keyList.size() - 1);
      Object key = keyList.get(randInt);
      long keyIndex = NameFactory.getCounterForName(key);
      if (useUniqueKeyPerThread) {
        if ((keyIndex % TestHelper.getNumThreads()) == myTid) {
          keyListToReturn.add(key);
        }
      } else if(this.keyAllocation == WANOperationsClientPrms.OWN_KEYS 
          || this.keyAllocation == WANOperationsClientPrms.OWN_KEYS_WRAP){
        if ((keyIndex % TestHelper.getNumThreads()) == myTid) {
          keyListToReturn.add(key);
        }
      }else if(this.keyAllocation == WANOperationsClientPrms.WAN_KEYS_WRAP){
        if (keyIndex % this.numWanSites == this.wanSiteId){
          keyListToReturn.add(key);
        }
      } else {
        keyListToReturn.add(key);
      }
      keyList.remove(randInt);
    }
    return keyListToReturn;
  }

  /**
   * Return unique key counter for current thread.
   * 
   * @return unique key counter for current thread
   */
  protected int getNextUniqueKeyCounter(int limit) {
    WANOperationsClientBB bb = WANOperationsClientBB.getBB();
    int tid = RemoteTestModule.getCurrentThread().getThreadId();
    int numThreads = TestHelper.getNumThreads();
    String key = "UniqueKeyNum_" + tid;
         
    Integer uniqueIndex = (Integer)bb.getSharedMap().get(key); 
    if (uniqueIndex == null) { // first time set unique key to current thread
      uniqueIndex = new Integer(tid);      
      bb.getSharedCounters().zero(WANOperationsClientBB.NumKeys);      
    } else {
      uniqueIndex = new Integer(uniqueIndex.intValue() + numThreads );
      long keycount = bb.getSharedCounters().read(WANOperationsClientBB.NumKeys);
      if(limit != -1 && keycount >= limit ){
        uniqueIndex = new Integer(tid);
        bb.getSharedCounters().zero(WANOperationsClientBB.NumKeys);        
      }      
    }
    bb.getSharedMap().put(key, uniqueIndex);    
    bb.getSharedCounters().increment(WANOperationsClientBB.NumKeys);
    return uniqueIndex.intValue();
  }

  /**
   * Return aRegion snapshot map from blackboard. This is used only when
   * util.OperationsClientPrms#useUniqueKeyPerThread=true
   * 
   * @return Map of region snapshot from bb
   */
  public synchronized Map getBBSnapshotForUniqueKeyMap(Region aRegion) {
    String snapshotKey = SNAPSHOT_FOR_UNIQUE_KEY_PREFIX + aRegion.getFullPath();
    Map snapshotMap = (Map)OperationCountersBB.getBB().getSharedMap().get(
        snapshotKey);
    if (snapshotMap == null) {
      snapshotMap = new HashMap();
      OperationCountersBB.getBB().getSharedMap().put(snapshotKey, snapshotMap);
    }
    return snapshotMap;
  }

  /**
   * Return aRegion snapshot map from blackboard.
   * @return Map of region snapshot from bb
   */
  public synchronized Map getBBSnapshot(Region aRegion) {
    String snapshotKey = SNAPSHOT_FOR_REGION_PREFIX + aRegion.getFullPath();
    Map snapshotMap = (Map)OperationCountersBB.getBB().getSharedMap().get(
        snapshotKey);
    if (snapshotMap == null) {
      snapshotMap = new HashMap();
      OperationCountersBB.getBB().getSharedMap().put(snapshotKey, snapshotMap);
    }
    return snapshotMap;
  }

  public synchronized void updateBlackboardSnapshot(Region aRegion, Object key,
      Object value, boolean isRemove) {
    OperationCountersBB.getBB().getSharedLock().lock();
    Map smap = getBBSnapshotForUniqueKeyMap(aRegion);
    if (isRemove) {
      smap.remove(key);
    }
    else {
      smap.put(key, value);
    }
    String snapshotKey = SNAPSHOT_FOR_UNIQUE_KEY_PREFIX + aRegion.getFullPath();
    OperationCountersBB.getBB().getSharedMap().put(snapshotKey, smap);
    OperationCountersBB.getBB().getSharedLock().unlock();
  }
  
  public void writeRegionSnapshotToBB() {
    int pid = RemoteTestModule.getMyPid();
    String clientName = RemoteTestModule.getMyClientName() + "(pid=" + RemoteTestModule.getMyPid() + ")";
    Region aRegion = null;
    Set rootRegions = CacheHelper.getCache().rootRegions();
    String snapshotKey = null;
    for (Iterator rit = rootRegions.iterator(); rit.hasNext();) {
      aRegion = (Region)rit.next();
      snapshotKey = SNAPSHOT_FOR_REGION_PREFIX + aRegion.getFullPath();
      String snapshotVmKey = SNAPSHOT_VM_FOR_REGION + aRegion.getFullPath();
      
      Map smap = new HashMap();
      Iterator it = aRegion.keySet().iterator();
     // smap.putAll(aRegion);
      while (it.hasNext()) {
        Object key = it.next();
        smap.put(key, aRegion.get(key));
      }
      OperationCountersBB.getBB().getSharedLock().lock();
      Log.getLogWriter().info("Writing snapshot to BB : " + snapshotKey);
      OperationCountersBB.getBB().getSharedMap().put(snapshotKey, smap);
      OperationCountersBB.getBB().getSharedMap().put(snapshotVmKey, clientName);
      Log.getLogWriter().info("Finished writing region snapshot to BB.");
      OperationCountersBB.getBB().getSharedLock().unlock();
    }
  }

  /**
   * Verify the state of the region and the given expected snapshot.
   */
  public void verifyRegionContents(Region aRegion, Map expected) {
    String snapshotVmKey = SNAPSHOT_VM_FOR_REGION + aRegion.getFullPath();
    Object snapshotvm = OperationCountersBB.getBB().getSharedMap().get(snapshotVmKey);
    String snapshotVmStr = (snapshotvm != null) ? "Snapshot written by " + snapshotvm + ". " : "";
    
    Log.getLogWriter().info(
        "Verifying contents of " + aRegion.getFullPath() + ", size is "
            + aRegion.size() + ", " + snapshotVmStr);
    // find unexpected and missing results    
    Set regionKeySet = new HashSet(aRegion.keySet());
    int regionSize = aRegion.size();
    int regionKeySetSize = regionKeySet.size();
    Log.getLogWriter().info(
        "regionSize is " + regionSize + " regionKeySetSize is "
            + regionKeySetSize);
    if (regionSize != regionKeySetSize) {
      throw new TestException("Inconsistent sizes: regionSize is " + regionSize
          + " " + "regionKeySetSize is " + regionKeySetSize);
    }
    Set snapshotKeySet = new HashSet(expected.keySet());
    Set unexpectedInRegion = new HashSet(regionKeySet);
    Set missingInRegion = new HashSet(snapshotKeySet);
    unexpectedInRegion.removeAll(snapshotKeySet);
    missingInRegion.removeAll(regionKeySet);
    Log.getLogWriter().info(
        "Found " + unexpectedInRegion.size() + " unexpected entries and "
            + missingInRegion.size() + " missing entries");

    // prepare an error string
    StringBuffer aStr = new StringBuffer();
    if (aRegion.size() != expected.size()) {
      aStr.append("Expected " + aRegion.getFullPath() + " to be size "
          + expected.size() + ", but it is size " + aRegion.size());
      Log.getLogWriter().info(aStr.toString());
    }
    if (unexpectedInRegion.size() > 0) {
      String tmpStr = "Found the following " + unexpectedInRegion.size()
          + " unexpected keys in " + aRegion.getFullPath() + ": "
          + unexpectedInRegion;
      Log.getLogWriter().info(tmpStr.toString());
      if (aStr.length() > 0) {
        aStr.append("\n");
      }
      aStr.append(tmpStr);
    }
    if (missingInRegion.size() > 0) {
      String tmpStr = "The following " + missingInRegion.size()
          + " keys were missing from " + aRegion.getFullPath() + ": "
          + missingInRegion;
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
      ValueHolder objInRegion = (ValueHolder)(aRegion.get(key));
      ValueHolder objInSnapshot = (ValueHolder)(expected.get(key));
      if (objInRegion == null) {
        if (objInSnapshot != null) {
          aStr.append("For key " + key + " expected " + objInSnapshot
              + ", found " + objInRegion + "\n");
        }
      }
      else {
        if (!objInRegion.equals(objInSnapshot)) {
          aStr.append("For key " + key + ", expected " + objInSnapshot
              + ", found " + objInRegion + "\n");
        }
      }
    }

    if (aStr.length() > 0) {
      throw new TestException(snapshotVmStr + aStr.toString());
    }
    Log.getLogWriter().info(
        "Done verifying contents of " + aRegion.getFullPath() + ", size is "
            + aRegion.size());
  }
  
  // ============================================================================
  // Gateway Sender operations
  // ============================================================================
  public void doHASenderOperationsAndVerify() {
    Log.getLogWriter().info("In doHASenderOperationsAndVerify");
    long cycleCounter = WANOperationsClientBB.getBB().getSharedCounters()
        .read(WANOperationsClientBB.NumCycle);
    int numThreadsForTask = RemoteTestModule.getCurrentThread()
        .getCurrentTask().getTotalThreads();
    long numDoingOps = WANOperationsClientBB.getBB().getSharedCounters().read(
        WANOperationsClientBB.NumStartedDoingOps);
    if (numDoingOps >= numThreadsForTask - 1) {
      Log.getLogWriter().info(
          "Returning from doHASenderOperationsAndVerify "
              + "with noops as WANOperationsClientBB.NumStartedDoingOps reached to "
              + numDoingOps);
      MasterController.sleepForMs(10000);
      return;
    }
    WANOperationsClientBB.getBB().getSharedCounters()
    .increment(WANOperationsClientBB.NumStartedDoingOps);    
    logger.info("doHASenderOperationsAndVerify: started doing operations counter is " 
        + numDoingOps + " in current cycle, cycleCounter=" + cycleCounter);

    doSenderOperations();
    verifySenderOperations();

    // increment numDoingOps counter
    long numDoneOps = WANOperationsClientBB.getBB().getSharedCounters()
        .incrementAndRead(WANOperationsClientBB.NumFinishedDoingOps);
    logger.info("doHASenderOperationsAndVerify: finished doing operation counter is " 
        + numDoneOps + " in current cycle, cycleCounter=" + cycleCounter);
    Log.getLogWriter().info("Done doHASenderOperationsAndVerify");
  }
  
  public void doSenderOperations() {
    Log.getLogWriter().info("Starting task doSenderOperations.");

    long startTime = System.currentTimeMillis();
    int numOps = 0;

    do {
      Set<GatewaySender> senders = GatewaySenderHelper.getGatewaySenders();
      for (GatewaySender sender : senders) {
        doSenderOperation(sender);
        numOps++;
      }
      Log.getLogWriter().info(
          "Completed op " + numOps + " for doSenderOperations task");
      
      MasterController.sleepForMs(5 * 1000); //Sleep for some time 

    } while ((System.currentTimeMillis() - startTime < minTaskGranularityMS)
        && (numOps < numOpsPerTask));
  }
    
  public void doSenderOperation(GatewaySender sender) {
    logger.info("In doSenderOperation on sender " + sender.getId());
    SenderOperation ss = getSenderOperation(sender);
    synchronized (senderLock) {
      switch (ss) {
        case START:
          try {
            logger.info("doSenderOperation: starting sender " + sender.getId());
            sender.start();
            logger.info("doSenderOperation: started sender " + sender.getId());
            updateSenderStateToBB(sender);
          }
          catch (Exception e) {
            String s = "Problem starting gateway sender"
                + GatewaySenderHelper.gatewaySenderToString(sender);
            throw new HydraRuntimeException(s, e);
          }
          break;

        case STOP:
          logger.info("doSenderOperation: stopping sender " + sender.getId());
          sender.stop();
          logger.info("doSenderOperation: stopped sender " + sender.getId());
          updateSenderStateToBB(sender);
          break;

        case PAUSE:
          logger.info("doSenderOperation: pausing sender " + sender.getId());
          sender.pause();
          logger.info("doSenderOperation: paused sender " + sender.getId());
          updateSenderStateToBB(sender);
          break;

        case RESUME:
          logger.info("doSenderOperation: resuming sender " + sender.getId());
          sender.resume();
          logger.info("doSenderOperation: resumed sender " + sender.getId());
          updateSenderStateToBB(sender);
          break;

        default:
          throw new TestException(
              "Possible test issue: invalid state for sender " + sender.getId()
                  + " status is " + ss);
      }
    }
  }
  
  public SenderOperation getSenderOperation(GatewaySender sender){    
    SenderOperation op = null;
    boolean foundOp = false;
    do{
       op = getSenderOperation(WANOperationsClientPrms.senderOperations);
      if(op.equals(SenderOperation.STOP)){
        foundOp = isStopPossible(sender);
      }else{
        foundOp = true;
      }
    }while(!foundOp);    
    return op;
  }
  
  /** Get a random sender operation using the given hydra parameter.
  *
  *  @param whichPrm A hydra parameter which specifies random sender operations.
  *
  *  @returns A random operation.
  */
 protected SenderOperation getSenderOperation(Long whichPrm) {
   SenderOperation op = null;
    String operation = TestConfig.tab().stringAt(whichPrm);
    if (operation.equals("start"))
       op = SenderOperation.START;
    else if (operation.equals("stop"))
       op = SenderOperation.STOP;
    else if (operation.equals("pause"))
       op = SenderOperation.PAUSE;
    else if (operation.equals("resume"))
       op = SenderOperation.RESUME;
    else
       throw new TestException("Unknown sender operation: " + operation + ". Allowed operations are 'start', 'stop', 'pause' and 'resume'");
    return op;
 }
 
 public void updateSenderStateToBB(GatewaySender sender){
   WANBlackboard bb =WANBlackboard.getInstance();    
   String senderId = getSenderLocalVMId(sender);
   if (sender.isRunning()){
     if(sender.isPaused()){
       // paused
       bb.getSharedMap().put(senderId, SenderState.SENDER_PAUSED);       
     }else{
       // running
       bb.getSharedMap().put(senderId, SenderState.SENDER_RUNNING);
     }
   }else{
     //stopped
     bb.getSharedMap().put(senderId, SenderState.SENDER_STOPPED);        
   }    
 }
   
  protected boolean isStopPossible(GatewaySender sender){
    // We don't want to stop all the senders in a site,
    // so check if at least a sender other than this is in running or paused  
    String sid = sender.getId();
    String localSid = getSenderLocalVMId(sender);
    boolean isStopOk = false;
    WANBlackboard bb = WANBlackboard.getInstance();
    Set<String> mapKeys = bb.getSharedMap().getMap().keySet();
    for (String key: mapKeys){
      if(!key.equals(localSid) && key.contains(sid)){ 
        Object senderState = bb.getSharedMap().get(key); 
        if (senderState instanceof SenderState &&  !((SenderState)senderState).equals(SenderState.SENDER_STOPPED)){
          isStopOk = true;
        }
      }
    }
    return isStopOk;
  }
  
  protected String getSenderLocalVMId(GatewaySender sender){
    return "SenderKey_vm_" + RemoteTestModule.getMyVmid() + "_" + sender.getId();
  }
  
  public void verifySenderOperations(){
    Set<GatewaySender> senders = GatewaySenderHelper.getGatewaySenders();
    logger.info("In verify sender operation on senders " + senders);
    for (GatewaySender sender: senders){
      verifySenderOperation(sender);
    }
    logger.info("Done verify sender operation on senders " + senders);
  }

  public void verifySenderOperation(GatewaySender sender){
    logger.info("verifySenderOperation on sender " + sender.getId());
    boolean verifyOps = true;
//    if (sender instanceof SerialGatewaySenderImpl ) {
//      SerialGatewaySenderImpl s = (SerialGatewaySenderImpl)sender;
//      if (s.isPrimary()){ //verify for serial sender only when it is primary
//        verifyOps = true;
//      }else{
//        logger.info("verifySenderOperation: skipping verify sender " + sender.getId() + " as isPrimary=false");
//      }
//    }else{ //verify for parallel gateway sender
//      verifyOps = true;
//    }
    synchronized (senderLock) {
    SenderState state = getSenderState(sender);
//    if(verifyOps){
      logger.info("verifySenderOperation on sender " + sender.getId() + " found state " + state);
      switch (state){
        case SENDER_RUNNING: {          
          if (sender.isRunning()) {
            if (sender.isPaused()) {
              throw new TestException(
                  "Expected sender status to be running and not paused, found running but paused for sender "
                      + GatewaySenderHelper.gatewaySenderToString(sender));
            }
          }
          else {
            throw new TestException(
                "Expected sender status to be running, found not-running for sender " + GatewaySenderHelper.gatewaySenderToString(sender));
          }
          break;
        }
        case SENDER_PAUSED: {
          if (!sender.isRunning() || !sender.isPaused()){
            throw new TestException(
                "Expected sender status to be running and paused, found isRunning=" + sender.isRunning() + ", isPaused=" + sender.isPaused() + " for sender "
                    + GatewaySenderHelper.gatewaySenderToString(sender));
          }
          break;
        }
        case SENDER_STOPPED:{
          if (sender.isRunning()){
            throw new TestException(
                "Expected sender status to be stopped, but found running as isRunning=" + sender.isRunning() + ", isPaused=" + sender.isPaused() + " for sender "
                    + GatewaySenderHelper.gatewaySenderToString(sender));
          }
          break;
        }
        default:
          throw new TestException("Possible test issue: invalid state for sender " + sender.getId() + ", status is " + state);          
      }
//    }
    }
  }
  
  protected SenderState getSenderState(GatewaySender sender){
    WANBlackboard bb = WANBlackboard.getInstance();    
    Object s = bb.getSharedMap().get(getSenderLocalVMId(sender));
    if (s == null){
      throw new TestException("Possible test issue. Expected sender state in blackboard for sender " + sender.getId() + ", found state as " + s);
    }
    return (SenderState)s;
  }
  
  //----------------------------------------------------------------------------
  //  Miscellaneous convenience methods
  //----------------------------------------------------------------------------

  /**
   *  Gets the client's hydra thread id.
   */
  protected int tid() {
    return RemoteTestModule.getCurrentThread().getThreadId();
  }
  /**
   *  Gets the client's hydra threadgroup id.
   */
  protected int tgid() {
    return RemoteTestModule.getCurrentThread().getThreadGroupId();
  }
  /**
   *  Gets the client's hydra threadgroup id for the current task.
   */
  protected int ttgid() {
    TestTask task = RemoteTestModule.getCurrentThread().getCurrentTask();
    String tgname = RemoteTestModule.getCurrentThread().getThreadGroupName();
    int id = task.getTaskThreadGroupId( tgname, tgid() );    
    return id;
  }
  /**
   *  Gets the total number of threads eligible to run the current task.
   */
  protected int numThreads() {
    TestTask task = RemoteTestModule.getCurrentThread().getCurrentTask();
    return task.getTotalThreads();
  }

  /**
   * Get the bridge's distributed id (wan id)
   */
  protected int getWanId() {
    String clientName = RemoteTestModule.getMyClientName();
    int lIndex = clientName.lastIndexOf("_");
    int fIndex = clientName.substring(0, lIndex).lastIndexOf("_");
    String site = clientName.substring(fIndex + 1, lIndex);    
    try {
      return Integer.parseInt(site);
    }
    catch (NumberFormatException e) {
      String s = clientName + " is not in the form <name>_<wanSiteNumber>_<itemNumber>";
      throw new HydraRuntimeException(s, e);
    }
  }
  /**
   * Check for termication condition
   */
  public void checkForTermicationCondition(){
    int termMethod = WANOperationsClientPrms.getTaskTerminationMethod();
    int termthreshold = WANOperationsClientPrms.getTaskTerminatorThreshold();
    if(termMethod == WANOperationsClientPrms.NUM_EVENT_RESOLVED){
      long numEventResolved = WANOperationsClientBB.getBB().getSharedCounters().read(WANOperationsClientBB.WanEventResolved);
      if(numEventResolved >= termthreshold){
        //update blackboard so that other running task can check this
        WANOperationsClientBB.getBB().getSharedMap().put(WANOperationsClientBB.IS_TASK_SCHEDULING_STOPPED, new Boolean(true));
        throw new StopSchedulingOrder("Time to stop as min number of WAN event resolved reached. WanEventResolved=" + numEventResolved); 
      }
    }else if (termMethod == WANOperationsClientPrms.NUM_OPERATIONS){
      long opsCounter = WANBlackboard.getInstance().getSharedCounters().read(WANBlackboard.operation_counter);
      if(opsCounter >= termthreshold){
        //update blackboard so that other running task can check this
        WANOperationsClientBB.getBB().getSharedMap().put(WANOperationsClientBB.IS_TASK_SCHEDULING_STOPPED, new Boolean(true));
        throw new StopSchedulingOrder("Time to stop as max number of operations reached. Operations=" + opsCounter);         
      }
    }
  }
  
}
