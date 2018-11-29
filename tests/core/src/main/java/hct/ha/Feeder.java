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
package hct.ha;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Region;
import hct.HctPrms;
import hydra.CacheHelper;
import hydra.ConfigPrms;
import hydra.Log;
import hydra.ProcessMgr;
import hydra.RegionHelper;
import hydra.TestConfig;
import util.TestException;
import util.TestHelper;
import util.TxHelper;


/**
 * A Feeder class for ClientQueue HA testing. It creates a configurable ( passed
 * through bt/conf) number of threads for doing puts and each thread does a put
 * on fixed set of keys with range specified by configuration.
 * 
 * @author Suyog Bhokare
 * @author Dinesh Patel
 * @author Girish Thombare
 * 
 */
public class Feeder
{
  /** Total number of perpetual put threads to be created */
  final static int TOTAL_PUT_THREADS = TestConfig.tab().intAt(
      HAClientQueuePrms.numPutThreads);

  /** range of keys to be used by each thread */
  final static int PUT_KEY_RANGE = TestConfig.tab().intAt(
      HAClientQueuePrms.numKeyRangePerThread);

  /** A static array containing the reference to the put threads */
  static Thread[] putThreads = new Thread[TOTAL_PUT_THREADS];

  
  /** A regionName to which index is attached to create multiple regions */  
  final static String regionName = TestConfig.tab().stringAt(HctPrms.regionName);

  /**
   * A list containing the counter for each thread. The counter value specifies
   * the number of times a put thread would operate on its range. The hydra task
   * thread will increment this value in each scheduling and the corresponding
   * put thread will decrement this value each time it operates on its keyset
   */
  static List threadList = new ArrayList(TOTAL_PUT_THREADS);

  /** A boolean to stop all the put threads. This will be toggled via CLOSETASK */
  static volatile boolean stop = false;

 /** A boolean to keep track if any exception occured in the put threads. */
  static volatile boolean exceptionOccured = false;

  /**
   * A stringbuffer to store messages if any exception that occured in the put
   * threads.
   */
  static StringBuffer exceptionMsg = new StringBuffer();
  /**
   * A Map which contains a thread-id as key and a map as value. This value map
   * contains latest values for the key-set of that particular thread.
   */
  protected static final Map latestValues = new HashMap();

  // test region name
  static protected final String REGION_NAME = TestConfig.tab().stringAt(
      HctPrms.regionName);

  /** The opCode for create operation */
  static private final int CREATE_OPERATION = 1;

  /** The opCode for update operation */
  static private final int UPDATE_OPERATION = 2;

  /** The opCode for invalidate operation */
  static private final int INVALIDATE_OPERATION = 3;

  /** The opCode for destroy operation */
  static private final int DESTROY_OPERATION = 4;

  /** The opCode for registerInterest operation */
  static private final int REG_INTEREST_OPERATION = 5;

  /** The opCode for unregisterInterest operation */
  static private final int UNREG_INTEREST_OPERATION = 6;
  
  /** The opCode for unregisterInterest operation */
  static private final int KILL_CLIENT = 7;

  /** Total number of regions in the tests. Default is one */
  static int numOfRegion = 1;

  /**
   * The last key to be put by feeder. This is used to signal for proceeding
   * with the validation in some tests
   */
  public static final String LAST_KEY = "last_key";

  /**  concurrentMap ops (putIfAbsent, replace and remove) are not supported on pre-6.5 clients */
  static protected boolean allowConcurrentMapOps = true;   
  
  /**
   * Create all the test entries in the given region.
   * 
   * @param region -
   *          The region in which entries will be created
   * @param threadIdKeyPrefix -
   *          The string prefix used to generate keys
   */
  private static void createEntries(Region region, String threadIdKeyPrefix)
  {
    // put data in region
    for (int j = 0; j < TOTAL_PUT_THREADS; j++) {
      String threadIdkey = threadIdKeyPrefix + j;
      Map values = new HashMap();
      Long initValue = new Long(0);
      for (int k = 0; k < PUT_KEY_RANGE; k++) {
        String key = region.getFullPath() + threadIdkey + "_" + k;
        try {
          if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
             Log.getLogWriter().fine("putIfAbsent(" + key + ", " + initValue + ")");
             region.putIfAbsent(key, initValue);
          } else {
             Log.getLogWriter().fine("create(" + key + ", " + initValue + ")");
          region.create(key, initValue);
        }
        }
        catch (Exception e) {
          Log.getLogWriter().info(
              TOTAL_PUT_THREADS + "Exception in creating entry for starting with "
                  + j + " for key = " + key);
          exceptionMsg.append(e.getMessage());
          exceptionOccured = true;
        }
        values.put(key, initValue);
      }
      latestValues.put(threadIdkey, values);
      
      try {
      HAClientQueueBB.getBB().getSharedCounters().add(
          HAClientQueueBB.NUM_CREATE, PUT_KEY_RANGE);
      }
      catch (Exception e) {
          Log.getLogWriter().info(
               "Exception occured while updating SharedCounter "
                  );
          exceptionMsg.append(e.getMessage());
          exceptionOccured = true;
        }
    }
    
    if (TestConfig.tab().booleanAt(
        hct.ha.HAClientQueuePrms.precreateLastKeyAtClient, false)) {
      region.put(Feeder.LAST_KEY, "DUMMY_VALUE");
      HAClientQueueBB.getBB().getSharedCounters().add(
          HAClientQueueBB.NUM_CREATE, 1);
      Log.getLogWriter().info("createEntries  : created last_key for "+region.getFullPath());
    }
    Log.getLogWriter().info(
        "Created entries on region : " + region.getFullPath());
  }
  
  /**
   * This method is an INITTASK and does the following : <br>
   * 1)Create cache adn region <br>
   * 2)Set the counters for all threads to 1 ( to run them once in INITTASK)<br>
   * 3)Initialize and start the put threads
   * 
   */
  public static void initTask()
  {

    allowConcurrentMapOps = TestConfig.tab().booleanAt(hct.ha.HAClientQueuePrms.allowConcurrentMapOps, true);
    CacheHelper.createCache(ConfigPrms.getCacheConfig());

    numOfRegion = TestConfig.tab().intAt(
        hct.ha.HAClientQueuePrms.numberOfRegions, 1);
    for (int i = 0; i < numOfRegion; i++) {
      Region region = RegionHelper.createRegion(regionName + i, ConfigPrms.getRegionConfig());
      
       if (region == null){ 
          exceptionMsg.append("Region created is null ");
          exceptionOccured = true;
       }
      String threadIdkeyPrefix = "Thread_";
      createEntries(region, threadIdkeyPrefix);
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      threadList.add(new Integer(0));
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      final int threadId = i;
      putThreads[i] = new Thread() {

        /**
         * Each thread checks the value of its correponding counter from
         * <code>threadList</code>. If the value is zero, then goes into
         * wait(). If the value is greater than zero,than decrements it by one
         * and performs put/invalidate/destroy operation on its fixed set of
         * keys and updates the blackboard for total number of
         * creates/puts/invalidates/destroys done. If in wait(), this will be
         * notified via the hydra TASK.
         */
        @Override
        public void run()
        {
          while (true) {
            synchronized (threadList) {
              if ((((Integer)threadList.get(threadId)).intValue()) == 0 && !stop) {
                try {
                  threadList.wait();
                }
                catch (InterruptedException e) {
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;
                }
              }
              //It may possible that the stop became true during the FeederTask. So check is added here.
              if(stop)
            	  break;
              threadList.set(threadId, new Integer(((Integer)threadList
                  .get(threadId)).intValue() - 1));
            }

            String threadIdkey = "Thread_" + threadId;

            // if entries not created yet, throw exception.
            if (latestValues.get(threadIdkey) == null) {
              TestException ex = new TestException(
                  "Entries were not created in INITTASK for keys containing threadIdkey = "
                      + threadIdkey);
              exceptionMsg.append(TestHelper.getStackTrace(ex));
              exceptionOccured = true;              
            }
            else {
              // entries already created once, do random operations
              int numCreate = 0;
              int numUpdate = 0;
              int numInvalidate = 0;
              int numDestroy = 0;
             
              // Select the region randomly and perform operations on it 
              int numRegion = TestConfig.tab().intAt(
                  HAClientQueuePrms.regionRange, 1) - 1;
              Region region = RegionHelper.getRegion(regionName + numRegion);
              for (int j = 0; j < PUT_KEY_RANGE; j++) {
                try {
                  String key = region.getFullPath() + threadIdkey + "_" + j; 
                  String operation = TestConfig.tab().stringAt(
                      HAClientQueuePrms.entryOperations, "put");

                  if (operation.equals("put")) {
                    Long newVal = null;
                    if (region.containsKey(key)) {
                      Long oldVal = (Long)region.get(key);
                      if (oldVal == null) {
                        newVal = getLatestValue(key, threadIdkey);
                        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                           Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                           region.replace(key, newVal);
                        } else {
                           Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      }
                      else {
                        newVal = new Long(oldVal.longValue() + 1);
                        Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      numUpdate++;
                    }
                    else {
                      newVal = getLatestValue(key, threadIdkey);
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                         Log.getLogWriter().fine("putIfAbsent(" + key + ", " + newVal + ")");
                         region.putIfAbsent(key, newVal);
                      } else {
                         Log.getLogWriter().fine("create(" + key + ", " + newVal + ")");
                      region.create(key, newVal);
                      }
                      numCreate++;
                    }
                  }

                  else if (operation.equals("invalidate")) {
                    if (updateLatestValueMap(region , key, threadIdkey, false)) {
                      region.invalidate(key);
                      numInvalidate++;
                    }
                  }
                  else if (operation.equals("destroy")) {
                    if (updateLatestValueMap(region , key, threadIdkey, true)) {
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                         Log.getLogWriter().fine("remove(" + key + ")");
                         region.remove(key, region.get(key));
                      } else {
                         Log.getLogWriter().fine("destroy(" + key + ")");
                      region.destroy(key);
                      }
                      numDestroy++;
                    }
                  }
                  else {
                    throw new TestException("Unknown entry operation: "
                        + operation);
                  }
                }
                catch (Exception e) {
                  Log.getLogWriter().info(
                      TOTAL_PUT_THREADS
                          + "exception in put thread starting with " + threadId
                          + " for key = " + j);
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;

                }
              }

              // update the blackboard
              
              try {
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_CREATE, numCreate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_UPDATE, numUpdate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_INVALIDATE, numInvalidate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_DESTROY, numDestroy);
              }
              catch (Exception e) {
                  Log.getLogWriter().info(
                       "Exception occured while updating SharedCounter "
                          );
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;
                }

              Log.getLogWriter().info(
                  "Task completed for thread id = " + threadId);
            }
            if (stop)
              break;
          }
        }
      };
      putThreads[i].start();
    }
  }
  
  /**
   * This method is an INITTASK and does the following : <br>
   * 1)Create cache adn region <br>
   * 2)Set the counters for all threads to 1 ( to run them once in INITTASK)<br>   *  
   * 3)Initialize and start the put threads
   * 4)Begin transaction
   * 5)Does random operations
   * 6)Commit transactions   
   * 
   */
  public static void initCSTransactionsTask()
  {
    final Cache cache =  CacheHelper.createCache(ConfigPrms.getCacheConfig());

    numOfRegion = TestConfig.tab().intAt(
        hct.ha.HAClientQueuePrms.numberOfRegions, 1);
    for (int i = 0; i < numOfRegion; i++) {
      Region region = RegionHelper.createRegion(regionName + i, ConfigPrms.getRegionConfig());
    if (region == null){
        exceptionMsg.append("Region created is null ");
        exceptionOccured = true;
    }
      String threadIdkeyPrefix = "VM_" + ProcessMgr.getProcessId() + "_Thread_";
      createEntries(region, threadIdkeyPrefix);
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      threadList.add(new Integer(0));
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      final int threadId = i;
      putThreads[i] = new Thread() {

        /**
         * Each thread checks the value of its correponding counter from
         * <code>threadList</code>. If the value is zero, then goes into
         * wait(). If the value is greater than zero,than decrements it by one
         * and performs put/invalidate/destroy operation on its fixed set of
         * keys and updates the blackboard for total number of
         * creates/puts/invalidates/destroys done. If in wait(), this will be
         * notified via the hydra TASK.
         */
        @Override
        public void run()
        {
          while (true) {
            synchronized (threadList) {
              if ((((Integer)threadList.get(threadId)).intValue()) == 0 && !stop) {
                try {
                  threadList.wait();
                }
                catch (InterruptedException e) {
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;  
                }
              }
              threadList.set(threadId, new Integer(((Integer)threadList
                  .get(threadId)).intValue() - 1));
            }
            // generate VM + thread specific key 
            String threadIdkey = "VM_"+ProcessMgr.getProcessId() + "_Thread_" + threadId;

            // if entries not created yet, throw exception.
            if (latestValues.get(threadIdkey) == null) {
              TestException ex = new TestException(
                  "Entries were not created in INITTASK for keys containing threadIdkey = "
                      + threadIdkey);
              exceptionMsg.append(TestHelper.getStackTrace(ex));
              exceptionOccured = true;
            }
            else {
              // entries already created once, do random operations
              int numCreate = 0;
              int numUpdate = 0;
              int numInvalidate = 0;
              int numDestroy = 0;
             
              // Select the region randomly and perform operations on it 
              int numRegion = TestConfig.tab().intAt(
                  HAClientQueuePrms.regionRange, 1) - 1;
              Region region = RegionHelper.getRegion(regionName + numRegion);
              
                cache.getCacheTransactionManager().begin();
                for (int j = 0; j < PUT_KEY_RANGE; j++) {                
                  try {
                  String key = region.getFullPath() + threadIdkey + "_" + j; 
                  String operation = TestConfig.tab().stringAt(
                      HAClientQueuePrms.entryOperations, "put");

                  if (operation.equals("put")) {
                    Long newVal = null;
                    if (region.containsKey(key)) {
                      Long oldVal = (Long)region.get(key);
                      if (oldVal == null) {
                        newVal = getLatestValue(key, threadIdkey);
                        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                           Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                           region.replace(key, newVal);
                        } else {
                           Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      }
                      else {
                        newVal = new Long(oldVal.longValue() + 1);
                        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                          Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                          region.replace(key, newVal);
                        } else {
                          Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      }
                      numUpdate++;
                    }
                    else {
                      newVal = getLatestValue(key, threadIdkey);
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                        Log.getLogWriter().fine("putIfAbsent(" + key + ", " + newVal + ")");
                        region.putIfAbsent(key, newVal);
                      } else {
                        Log.getLogWriter().fine("create(" + key + ", " + newVal + ")");
                      region.create(key, newVal);
                      }
                      numCreate++;
                    }
                  }

                  else if (operation.equals("invalidate")) {
                    if (updateLatestValueMap(region , key, threadIdkey, false)) {
                      region.invalidate(key);
                      numInvalidate++;
                    }
                  }
                  else if (operation.equals("destroy")) {
                    if (updateLatestValueMap(region , key, threadIdkey, true)) {
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                        Log.getLogWriter().fine("remove(" + key + ")");
                        region.remove(key, region.get(key));
                      } else {
                        Log.getLogWriter().fine("destroy(" + key + ")");
                      region.destroy(key);
                      }
                      numDestroy++;
                    }
                  }
                  else {
                    throw new TestException("Unknown entry operation: "
                        + operation);
                  }
                }catch (Exception e) {
                  Log.getLogWriter().info(
                      TOTAL_PUT_THREADS
                          + "exception in put thread starting with " + threadId
                          + " for key = " + j);
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true; 
                 }
                
                }
                try {
                  cache.getCacheTransactionManager().commit();
                }
                catch (ConflictException e){
                 // revert the counters in case of ConflictException
                 numCreate = 0 ;
                 numDestroy = 0 ; 
                 numInvalidate = 0 ;
                 numUpdate = 0 ; 
               }

              // update the blackboard
               try {
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_CREATE, numCreate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_UPDATE, numUpdate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_INVALIDATE, numInvalidate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_DESTROY, numDestroy);
               }
               catch (Exception e) {
                   Log.getLogWriter().info(
                        "Exception occured while updating SharedCounter "
                           );
                   exceptionMsg.append(e.getMessage());
                   exceptionOccured = true;
                 }
               

              Log.getLogWriter().info(
                  "Task completed thread for id = " + threadId);
            }
            if (stop)
              break;
          }
        }
      };
      putThreads[i].start();
    }
  }

  /**
   * This method is an INITTASK and does the following : <br>
   * 1)Create cache adn region <br>
   * 2)Set the counters for all threads to 1 ( to run them once in INITTASK)<br>   *  
   * 3)Initialize and start the put threads
   * 4)Begin transaction
   * 5)Does random operations
   * 6)Commit transactions   
   * 
   */
  public static void initCSTransactionsOneOpPerTxTask()
  {
    final Cache cache =  CacheHelper.createCache(ConfigPrms.getCacheConfig());

    numOfRegion = TestConfig.tab().intAt(
        hct.ha.HAClientQueuePrms.numberOfRegions, 1);
    for (int i = 0; i < numOfRegion; i++) {
      Region region = RegionHelper.createRegion(regionName + i, ConfigPrms.getRegionConfig());
    if (region == null){
        exceptionMsg.append("Region created is null ");
        exceptionOccured = true;
    }
      String threadIdkeyPrefix = "VM_" + ProcessMgr.getProcessId() + "_Thread_";
      createEntries(region, threadIdkeyPrefix);
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      threadList.add(new Integer(0));
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      final int threadId = i;
      putThreads[i] = new Thread() {

        /**
         * Each thread checks the value of its correponding counter from
         * <code>threadList</code>. If the value is zero, then goes into
         * wait(). If the value is greater than zero,than decrements it by one
         * and performs put/invalidate/destroy operation on its fixed set of
         * keys and updates the blackboard for total number of
         * creates/puts/invalidates/destroys done. If in wait(), this will be
         * notified via the hydra TASK.
         */
        public void run()
        {
          while (true) {
            synchronized (threadList) {
              if ((((Integer)threadList.get(threadId)).intValue()) == 0 && !stop) {
                try {
                  threadList.wait();
                }
                catch (InterruptedException e) {
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;  
                }
              }
              threadList.set(threadId, new Integer(((Integer)threadList
                  .get(threadId)).intValue() - 1));
            }
            // generate VM + thread specific key 
            String threadIdkey = "VM_"+ProcessMgr.getProcessId() + "_Thread_" + threadId;

            // if entries not created yet, throw exception.
            if (latestValues.get(threadIdkey) == null) {
              TestException ex = new TestException(
                  "Entries were not created in INITTASK for keys containing threadIdkey = "
                      + threadIdkey);
              exceptionMsg.append(TestHelper.getStackTrace(ex));
              exceptionOccured = true;
            }
            else {
              // entries already created once, do random operations
              int numCreate = 0;
              int numUpdate = 0;
              int numInvalidate = 0;
              int numDestroy = 0;
             
              // Select the region randomly and perform operations on it 
              int numRegion = TestConfig.tab().intAt(
                  HAClientQueuePrms.regionRange, 1) - 1;
              Region region = RegionHelper.getRegion(regionName + numRegion);
              
                for (int j = 0; j < PUT_KEY_RANGE; j++) {                
                  try {
                  String key = region.getFullPath() + threadIdkey + "_" + j; 
                  String operation = TestConfig.tab().stringAt(
                      HAClientQueuePrms.entryOperations, "put");
 
                  TxHelper.begin();

                  if (operation.equals("put")) {
                    Long newVal = null;
                    if (region.containsKey(key)) {
                      Long oldVal = (Long)region.get(key);
                      if (oldVal == null) {
                        newVal = getLatestValue(key, threadIdkey);
                        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                           Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                           region.replace(key, newVal);
                        } else {
                           Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      }
                      else {
                        newVal = new Long(oldVal.longValue() + 1);
                        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                          Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                          region.replace(key, newVal);
                        } else {
                          Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      }
                      numUpdate++;
                    }
                    else {
                      newVal = getLatestValue(key, threadIdkey);
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                        Log.getLogWriter().fine("putIfAbsent(" + key + ", " + newVal + ")");
                        region.putIfAbsent(key, newVal);
                      } else {
                        Log.getLogWriter().fine("create(" + key + ", " + newVal + ")");
                      region.create(key, newVal);
                      }
                      numCreate++;
                    }
                  }

                  else if (operation.equals("invalidate")) {
                    if (updateLatestValueMap(region , key, threadIdkey, false)) {
                      region.invalidate(key);
                      numInvalidate++;
                    }
                  }
                  else if (operation.equals("destroy")) {
                    if (updateLatestValueMap(region , key, threadIdkey, true)) {
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                        Log.getLogWriter().fine("remove(" + key + ")");
                        region.remove(key, region.get(key));
                      } else {
                        Log.getLogWriter().fine("destroy(" + key + ")");
                      region.destroy(key);
                      }
                      numDestroy++;
                    }
                  }
                  else {
                    throw new TestException("Unknown entry operation: "
                        + operation);
                  }
                }catch (Exception e) {
                  Log.getLogWriter().info(
                      TOTAL_PUT_THREADS
                          + "exception in put thread starting with " + threadId
                          + " for key = " + j);
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true; 
                 }
                 try {
                   TxHelper.commit();
                 }
                 catch (ConflictException e){
                  // revert the counters in case of ConflictException
                  numCreate = 0 ;
                  numDestroy = 0 ; 
                  numInvalidate = 0 ;
                  numUpdate = 0 ; 
                }
              }

              // update the blackboard
               try {
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_CREATE, numCreate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_UPDATE, numUpdate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_INVALIDATE, numInvalidate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_DESTROY, numDestroy);
               }
               catch (Exception e) {
                   Log.getLogWriter().info(
                        "Exception occured while updating SharedCounter "
                           );
                   exceptionMsg.append(e.getMessage());
                   exceptionOccured = true;
                 }
               

              Log.getLogWriter().info(
                  "Task completed thread for id = " + threadId);
            }
            if (stop)
              break;
          }
        }
      };
      putThreads[i].start();
    }
  }

  

  
  /**
   * This method is an INITTASK and does the following : <br>
   * 1)Creates a cache client adn region and creates entries (with value 0) on it<br>
   * 2)Set the counters for all threads to 1 ( to run them once in INITTASK)<br>
   * 3)Initialize and start the put threads
   * 
   */
  public static void initTask2()
  {
    HAClientQueue.initCacheClient();  
    String regionName = TestConfig.tab().stringAt(HctPrms.regionName);
    final Region region = RegionHelper.getRegion(Region.SEPARATOR+regionName+0);

    if (region == null){ 
      exceptionMsg.append("Region created is null ");
      exceptionOccured = true;
    }
    
    String threadIdkeyPrefix = "Thread_";
    createEntries(region, threadIdkeyPrefix);
	
    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      threadList.add(new Integer(0));
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      final int threadId = i;
      putThreads[i] = new Thread() {

        /**
         * Each thread checks the value of its correponding counter from
         * <code>threadList</code>. If the value is zero, then goes into
         * wait(). If the value is greater than zero,than decrements it by one
         * and performs put/invalidate/destroy operation on its fixed set of
         * keys and updates the blackboard for total number of
         * creates/puts/invalidates/destroys done. If in wait(), this will be
         * notified via the hydra TASK.
         */
        @Override
        public void run()
        {
          while (true) {
            synchronized (threadList) {
              if ((((Integer)threadList.get(threadId)).intValue()) == 0 && !stop) {
                try {
                  threadList.wait();
                }
                catch (InterruptedException e) {
                  exceptionMsg.append(TestHelper.getStackTrace(e));
                  exceptionOccured = true;
                }
              }
              threadList.set(threadId, new Integer(((Integer)threadList
                  .get(threadId)).intValue() - 1));
            }

            String threadIdkey = "Thread_" + threadId;

            // if entries not created yet, create them and update latestvalue
            // map
            if (latestValues.get(threadIdkey) == null) {
              TestException ex = new TestException(
                "Entries were not created in INITTASK for keys containing threadIdkey = "
                  + threadIdkey);
                exceptionMsg.append(TestHelper.getStackTrace(ex));
                exceptionOccured = true;
            }
            else {
              // entries already created once, do random operations
              int numCreate = 0;
              int numUpdate = 0;
              int numInvalidate = 0;
              int numDestroy = 0;

              for (int j = 0; j < PUT_KEY_RANGE; j++) {
                try {

                  String key = region.getFullPath() + threadIdkey + "_" + j;
                  String operation = TestConfig.tab().stringAt(
                      HAClientQueuePrms.entryOperations, "put");

                  if (operation.equals("put")) {
                    Long newVal = null;
                    if (region.containsKey(key)) {
                      Long oldVal = (Long)region.get(key);
                      if (oldVal == null) {
                        newVal = getLatestValue(key, threadIdkey);
                        region.getCache().getLogger().info(" going to put key "+key+ " and value "+newVal);
                        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                          Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                          region.replace(key, newVal);
                        } else {
                          Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      }
                      else {
                        newVal = new Long(oldVal.longValue() + 1);
                        region.getCache().getLogger().info(" going to put key "+key+ " and value "+newVal);
                        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                          Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                          region.replace(key, newVal);
                        } else {
                          Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                        region.put(key, newVal);
                      }
                      }
                      numUpdate++;
                    }
                    else {
                      newVal = getLatestValue(key, threadIdkey);
                      region.getCache().getLogger().info(" going to put key "+key+ " and value "+newVal);
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                        Log.getLogWriter().fine("putIfAbsent(" + key + ", " + newVal + ")");
                        region.putIfAbsent(key, newVal);
                      } else {
                        Log.getLogWriter().fine("create(" + key + ", " + newVal + ")");
                      region.create(key, newVal);
                      }
                      numCreate++;
                    }
                  }

                  else if (operation.equals("invalidate")) {
                    if (updateLatestValueMap(region, key, threadIdkey, false)) {
                      region.invalidate(key);
                      numInvalidate++;
                    }
                  }
                  else if (operation.equals("destroy")) {
                    if (updateLatestValueMap(region , key, threadIdkey, true)) {
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                        Log.getLogWriter().fine("remove(" + key + ")");
                        region.remove(key, region.get(key));
                      } else {
                        Log.getLogWriter().fine("destroy(" + key + ")");
                      region.destroy(key);
                      }
                      numDestroy++;
                    }
                  }
                  else {
                    throw new TestException("Unknown entry operation: "
                        + operation);
                  }
                }
                catch (Exception e) {
                  Log.getLogWriter().info(
                      TOTAL_PUT_THREADS
                          + "exception in put thread starting with " + threadId
                          + " for key = " + j);
                  exceptionMsg.append(TestHelper.getStackTrace(e));
                  exceptionOccured = true; 

                }
              }

              // update the blackboard
              try {
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_CREATE, numCreate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_UPDATE, numUpdate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_INVALIDATE, numInvalidate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_DESTROY, numDestroy);
              }
              catch (Exception e) {
                  Log.getLogWriter().info(
                       "Exception occured while updating SharedCounter "
                          );
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;
                }

              Log.getLogWriter().info(
                  "Task completed thread for id = " + threadId);
            }
            if (stop)
              break;
          }
        }
      };
      putThreads[i].start();
    }    
  }
  
  
  
  /**
   * This method is an INITTASK and does the following : <br>
   * 1)Creates a cache client and region <br>
   * 2)Set the counters for all threads to 1 ( to run them once in INITTASK)<br>
   * 3)Initialize and start the put threads. The put-threads here do random
   * register-unregister interest and entry operations. This is used in
   * <code>allOperationsWithFailover.conf</code> test
   */
  public static void initTaskForCacheClientsDoingRandomOperations()
  {
    HAClientQueue.initCacheClient();
    //final Region region = RegionHelper.getRegion(Region.SEPARATOR + REGION_NAME);
    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      threadList.add(new Integer(1));
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      final int threadId = i;
      putThreads[i] = new Thread() {

        /**
         * Each thread checks the value of its correponding counter from
         * <code>threadList</code>. If the value is zero, then goes into
         * wait(). If the value is greater than zero,than decrements it by one
         * and performs one of the create/ put/ invalidate/ destroy/
         * registerInterest/ unregisterInterest operation on any one of the keys
         * of its PUT_KEY_RANGE. If in wait(), this will be notified via the
         * hydra TASK.
         */
        @Override
        public void run()
        {
          while (true) {
            synchronized (threadList) {
              if ((((Integer)threadList.get(threadId)).intValue()) == 0 && !stop) {
                try {
                  threadList.wait();
                }
                catch (InterruptedException e) {
                  exceptionMsg.append(TestHelper.getStackTrace(e));
                  exceptionOccured = true;                  
                }
              }
              threadList.set(threadId, new Integer(((Integer)threadList
                  .get(threadId)).intValue() - 1));
            }

            // BEGIN actual task

            Region region = RegionHelper.getRegion(Region.SEPARATOR + REGION_NAME+0);

            int opCode = TestConfig.tab().intAt(HAClientQueuePrms.opCode);
            Log.getLogWriter().info("OPCODE = " + opCode);

            switch (opCode) {

            case CREATE_OPERATION:
              createEntry(region);
              break;
            case INVALIDATE_OPERATION:
              invalidateEntry(region);
              break;
            case DESTROY_OPERATION:
              destroyEntry(region);
              break;
            case UPDATE_OPERATION:
              updateEntry(region);
              break;
            case REG_INTEREST_OPERATION:
              registerInterest(region);
              break;
            case UNREG_INTEREST_OPERATION:
              unregisterInterest(region);
              break;
            case KILL_CLIENT:
              HACache.killClient();
              break;              
            default: {
              throw new TestException("Unknown operation " + opCode);
            }
            }

            // END actual task

            if (stop)
              break;
          }
        }
      };
      putThreads[i].start();
    }
  }

  /**
   * Randomly pick a key within the PUT_KEY_RANGE for doing operation.
   * 
   * @return string key
   */
  private static String getKeyForOperation()
  {
    // random number between 0 AND PUT_KEY_RANGE
    Random r = new Random();
    int randint = r.nextInt(PUT_KEY_RANGE);

    return ("KEY-" + randint);
  }

  /**
   * Perform create operation for a random key within PUT_KEY_RANGE
   * 
   * @param region -
   *          the test region
   */
  protected static void createEntry(Region region)
  {
    String key = getKeyForOperation();
    try {
      if (!region.containsKey(key)) {
        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
           Log.getLogWriter().fine("putIfAbsent(" + key + "_VALUE)");
           region.putIfAbsent(key, key + "_VALUE");
        } else {
           Log.getLogWriter().fine("create(" + key + "_VALUE)");
        region.create(key, key + "_VALUE");
      }
    }
    }
    catch (EntryExistsException e) {
      Log.getLogWriter().warning(
          "EntryExistsException occured while creating entry. "
              + TestHelper.getStackTrace(e));
    }
    catch (Exception e) {
      exceptionMsg.append("Exception occured while creating entry. "
          + TestHelper.getStackTrace(e));
      exceptionOccured = true;
    }
  }

  /**
   * Perform put operation for a random key within PUT_KEY_RANGE
   * 
   * @param region -
   *          the test region
   */
  protected static void updateEntry(Region region)
  {
    String key = getKeyForOperation();
    try {
      if (region.containsKey(key)) {
        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
           Log.getLogWriter().fine("replace(" + key + "_VALUE)");
           region.replace(key, key + "_VALUE");
        } else {
           Log.getLogWriter().fine("put(" + key + "_VALUE)");
        region.put(key, key + "_VALUE");
      }
    }
    }
    catch (Exception e) {
      exceptionMsg.append("Exception occured while updating entry. "
          + TestHelper.getStackTrace(e));
      exceptionOccured = true;
    }
  }

  /**
   * Perform invalidate operation for a random key within PUT_KEY_RANGE
   * 
   * @param region -
   *          the test region
   */
  protected static void invalidateEntry(Region region)
  {
    String key = getKeyForOperation();
    try {
      if (region.containsKey(key) && (region.get(key) != null)) {
        region.invalidate(key);
      }
    }
    catch (EntryNotFoundException e) {
      Log.getLogWriter().warning(
          "EntryNotFoundException occured while invalidating entry. "
              + TestHelper.getStackTrace(e));
    }
    catch (Exception e) {
      exceptionMsg.append("Exception occured while invalidating entry. "
          + TestHelper.getStackTrace(e));
      exceptionOccured = true;
    }
  }

  /**
   * Perform destroy operation for a random key within PUT_KEY_RANGE
   * 
   * @param region -
   *          the test region
   */
  protected static void destroyEntry(Region region)
  {
    String key = getKeyForOperation();
    try {
      if (region.containsKey(key)) {
        if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
           Log.getLogWriter().fine("remove(" + key + "_VALUE)");
           region.remove(key, key + "_VALUE");
        } else {
           Log.getLogWriter().fine("destroy(" + key + "_VALUE)");
        region.destroy(key);
      }
    }
    }
    catch (EntryNotFoundException e) {
      Log.getLogWriter().warning(
          "EntryNotFoundException occured while invalidating entry. "
              + TestHelper.getStackTrace(e));
    }
    catch (Exception e) {
      exceptionMsg.append("Exception occured while invalidating entry. "
          + TestHelper.getStackTrace(e));
      exceptionOccured = true; 
    }
  }

  /**
   * Perform registerInterest operation for a random key within PUT_KEY_RANGE
   * 
   * @param region -
   *          the test region
   */
  protected static void registerInterest(Region region)
  {
    String key = getKeyForOperation();
    long start = System.currentTimeMillis();
    long total = 0;
    try {

      region.registerInterest(key);
      total = System.currentTimeMillis() - start;
    }
    catch (Exception e) {
      exceptionMsg.append("Exception occured while registerInterest. "
          + TestHelper.getStackTrace(e));
      exceptionOccured = true;
    }
    if (total > 0) {
      Log.getLogWriter().info("time taken for registerInterest : " + total);
    }
  }

  /**
   * Perform unregisterInterest operation for a random key within PUT_KEY_RANGE
   * 
   * @param region -
   *          the test region
   */
  protected static void unregisterInterest(Region region)
  {
    String key = getKeyForOperation();
    long start = System.currentTimeMillis();
    long total = 0;
    try {
      region.unregisterInterest(key);
      total = System.currentTimeMillis() - start;
    }
    catch (Exception e) {
      exceptionMsg.append("Exception occured while unregisterInterest. "
          + TestHelper.getStackTrace(e));
      exceptionOccured = true;
    }
    if (total > 0) {
      Log.getLogWriter().info("time taken for unregisterInterest : " + total);
    }
  }
  
  /**
   * Does {@link #feederTask} for {@link HAClientQueuePrms#feederTaskTimeSec}.
   */
  public static void feederTimedTask() {

    long end = System.currentTimeMillis()
         + TestConfig.tab().longAt(HAClientQueuePrms.feederTaskTimeSec) * 1000;
    do {
      feederTask();
    } while (System.currentTimeMillis() < end);
  }

  /**
   * Increments the value of counters in <code>threadList</code> for each
   * thread and notifies all the put threads int wait().
   * 
   */
  public static void feederTask()
  {
    synchronized (threadList) {
      String exceptionReason = Validator.checkBlackBoardForException();
      // This check is for exception occued during previous feeder operation.
      if(exceptionOccured)
    	  throw new TestException(exceptionMsg.toString());
      // This check is for the exception occred in the edges.
      if(exceptionReason != null){
   		  stop = true;
   		  threadList.notifyAll();
   		  for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
    		try {
				putThreads[i].join(120000);
			} catch (InterruptedException e) {
				throw new TestException(e.getMessage()
						+ TestHelper.getStackTrace(e));
			}
   		  }
   		  throw new TestException(exceptionReason); 
      }
      //If there is no exception then we can start new Feeder Task
      else
      {
    	  for(int i = 0; i < TOTAL_PUT_THREADS; i++) {
				int newCounter = ((Integer) threadList.get(i)).intValue() + 1;
				threadList.set(i, new Integer(newCounter));
    	  }
    	  threadList.notifyAll();
	  }
    }
  }

  private static int counter = 0;

  public static void populateSharedMapWithRegionData()
  {
    try {
      numOfRegion = TestConfig.tab().intAt(
          hct.ha.HAClientQueuePrms.numberOfRegions, 1);
      for (int i = 0; i < numOfRegion; i++) {
        Region region = RegionHelper.getRegion(regionName + i);
    
        if (region == null){
           throw new TestException("Region created is null " + TestHelper.getStackTrace() );
        }

         if (region.isEmpty()){
           throw new TestException(" Region has no entries to copy to the SharedMap " + TestHelper.getStackTrace());
         }


        Iterator iterator = region.entrySet(false).iterator();
        Region.Entry entry = null;
        Object key;
        Object value;
        while (iterator.hasNext()) {
          entry = (Region.Entry)iterator.next();
          key = entry.getKey();
          value = entry.getValue();
          if (value != null) {
            HAClientQueueBB.getBB().getSharedMap().put(key, value);
          }
        }
      }
    }
    catch (Exception e) {
      e.printStackTrace();
      throw new TestException(e.getMessage() + TestHelper.getStackTrace(e));
    }
  }

  /**
   * Signals for stopping of the put threads and notifies all. Waits till all
   * the put threads are complete and decrements the shared counter for
   * TOTAL_UPDATES by <code>TOTAL_PUT_THREADS * PUT_KEY_RANGE</code>. This is
   * done because the first set of PUTs by each thread will correspond to CREATE
   * operations and UPDATES afterwards.
   */
  public static void closeTask()
  {

    waitForFeederThreadsToComplete();
    Region region= RegionHelper.getRegion(regionName + 0);
  
    if (region == null){
        throw new TestException("Region created is null " + TestHelper.getStackTrace());
    }

    if (TestConfig.tab().booleanAt(hct.ha.HAClientQueuePrms.putLastKey, false)) {

      try {
        Thread.sleep(15000);
      }
      catch (InterruptedException e1) {

        e1.printStackTrace();
      }
     
      try {
        Long last_value=new Long(0);
        region.put(LAST_KEY, last_value);
        Log.getLogWriter().info("Putting the last key in the region: " + region.getName());
        if (TestConfig.tab().booleanAt(
            hct.ha.HAClientQueuePrms.precreateLastKeyAtClient, false)) {
          HAClientQueueBB.getBB().getSharedCounters().add(
              HAClientQueueBB.NUM_UPDATE, 1);
        }
        else {
          HAClientQueueBB.getBB().getSharedCounters().add(
              HAClientQueueBB.NUM_CREATE, 1);
        }
      }
      catch (Exception e) {
        throw new TestException(
            "Exception while performing put of the last_key" + TestHelper.getStackTrace());
      }
    }
    
    Log.getLogWriter().info(
        "No of puts done by feeder : "
            + HAClientQueueBB.getBB().getSharedCounters().read(
                HAClientQueueBB.NUM_UPDATE));
    
    Log.getLogWriter().info("Close Task Complete...");
  }

  /**
   * Signals for stopping of the put threads and notifies all. Waits till all
   * the put threads are complete.  Signals to other VMs that the feed cyle is
   * over.
   */
  public static void waitForFeederThreadsToComplete()
  {
    synchronized (threadList) {
      stop = true;
      threadList.notifyAll();
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      try {
        putThreads[i].join(120000);
      }
      catch (InterruptedException e) {
        throw new TestException(e.getMessage() + TestHelper.getStackTrace(e));
      }
    }

    // fail the test if exception occured in any of the put threads
    if (exceptionOccured) {
      throw new TestException(exceptionMsg.toString());
    }

    // signal other VMs by making the feed signal counter non-zero
    Log.getLogWriter().info("Setting feed signal...");
    try {
    HAClientQueueBB.getBB().getSharedCounters()
                   .increment(HAClientQueueBB.feedSignal);
    }
    catch (Exception e) {
        Log.getLogWriter().info(
             "Exception occured while updating SharedCounter "
                );
        exceptionMsg.append(e.getMessage());
        exceptionOccured = true;
      }
    
    Validator.checkBlackBoardForException();
  }

  /**
   * Gets the latest value for the given key from the latestValues map and
   * returns the incremented value for doing entry operations.
   * 
   * @param key -
   *          the event key
   * @param threadIdKey -
   *          thread-id to which this key belongs
   * @return - the latest incremented value for this key
   */
  protected static Long getLatestValue(String key, String threadIdKey)
  {
    Map latestValueForThread = (Map)latestValues.get(threadIdKey);
    Long oldVal = (Long)latestValueForThread.remove(key);
    if (oldVal == null) {
      exceptionMsg.append("oldVal cannot be null ");
      exceptionOccured = true;
      throw new TestException("oldVal cannot be null");
    }
    return new Long(oldVal.longValue() + 1);
  }

  /**
   * Updates the local <code>latestValues</code> map on a invalidate or
   * destroy call.
   * 
   * @param key -
   *          the event key
   * @param threadId -
   *          thread-id to which this key belongs
   * @param isDestroyCall -
   *          true if this method was called for doing destroy operation, false
   *          if invalidate operation
   * @return - whether to proceed with the calling invalidate or destroy
   *         operation or not.
   * @throws Exception -
   *           thrown if any exception occurs while fetching the old value from
   *           the region
   */
  protected static boolean updateLatestValueMap(Region region, String key, String threadId,
      boolean isDestroyCall) throws Exception
  {
    Object oldVal = null;
    if (region.containsKey(key)) {
      if ((oldVal = region.get(key)) == null) {
        if (isDestroyCall) {
          return true;
        }
        else {
          return false;
        }
      }
      else {
        Map latestValueForThread = (Map)latestValues.get(threadId);
        latestValueForThread.put(key, oldVal);
        return true;
      }
    }
    else {
      return false;
    }
  }
  
  /**
   * This method is an INITTASK and does the following : <br>
   * 1)Create cache adn region <br>
   * 2)Set the counters for all threads to 1 ( to run them once in INITTASK)<br>
   * 3)Initialize and start the put threads
   * 4)Put object of bulky size
   */
  public static void initTaskWithBulkyObject()
  {
    final int OBJECT_SIZE_VAL = 128; // used for generating bulky object
    CacheHelper.createCache(ConfigPrms.getCacheConfig());

    numOfRegion = TestConfig.tab().intAt(
        hct.ha.HAClientQueuePrms.numberOfRegions, 1);
    for (int i = 0; i < numOfRegion; i++) {
      Region region = RegionHelper.createRegion(regionName + i, ConfigPrms.getRegionConfig());
      
       if (region == null){ 
          exceptionMsg.append("Region created is null ");
          exceptionOccured = true;
       }
      String threadIdkeyPrefix = "Thread_";
      createEntries(region, threadIdkeyPrefix);
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      threadList.add(new Integer(0));
    }

    for (int i = 0; i < TOTAL_PUT_THREADS; i++) {
      final int threadId = i;
      putThreads[i] = new Thread() {

        /**
         * Each thread checks the value of its correponding counter from
         * <code>threadList</code>. If the value is zero, then goes into
         * wait(). If the value is greater than zero,than decrements it by one
         * and performs put/invalidate/destroy operation on its fixed set of
         * keys and updates the blackboard for total number of
         * creates/puts/invalidates/destroys done. If in wait(), this will be
         * notified via the hydra TASK.
         */
        @Override
        public void run()
        {
          while (true) {
            synchronized (threadList) {
              if ((((Integer)threadList.get(threadId)).intValue()) == 0 && !stop) {
                try {
                  threadList.wait();
                }
                catch (InterruptedException e) {
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;
                }
              }
              //It may possible that the stop became true during the FeederTask. So check is added here.
              if(stop)
                  break;
              threadList.set(threadId, new Integer(((Integer)threadList
                  .get(threadId)).intValue() - 1));
            }

            String threadIdkey = "Thread_" + threadId;

            // if entries not created yet, throw exception.
            if (latestValues.get(threadIdkey) == null) {
              TestException ex = new TestException(
                  "Entries were not created in INITTASK for keys containing threadIdkey = "
                      + threadIdkey);
              exceptionMsg.append(TestHelper.getStackTrace(ex));
              exceptionOccured = true;              
            }
            else {
              // entries already created once, do random operations
              int numCreate = 0;
              int numUpdate = 0;
              int numInvalidate = 0;
              int numDestroy = 0;
             
              // Select the region randomly and perform operations on it 
              int numRegion = TestConfig.tab().intAt(
                  HAClientQueuePrms.regionRange, 1) - 1;
              Region region = RegionHelper.getRegion(regionName + numRegion);
              for (int j = 0; j < PUT_KEY_RANGE; j++) {
                try {
                  String key = region.getFullPath() + threadIdkey + "_" + j; 
                  String operation = TestConfig.tab().stringAt(
                      HAClientQueuePrms.entryOperations, "put");

                  if (operation.equals("put")) {
                    byte[] newVal = new byte[OBJECT_SIZE_VAL
                                             * OBJECT_SIZE_VAL]; // 0.0625 MB object
                    if (region.containsKey(key)) {
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                         Log.getLogWriter().fine("replace(" + key + ", " + newVal + ")");
                         region.replace(key, newVal);
                      } else {
                         Log.getLogWriter().fine("put(" + key + ", " + newVal + ")");
                      region.put(key, newVal);
                      }
                      numUpdate++;
                    }
                    else {
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                         Log.getLogWriter().fine("putIfAbsent(" + key + ", " + newVal + ")");
                         region.putIfAbsent(key, newVal);
                      } else {
                         Log.getLogWriter().fine("create(" + key + ", " + newVal + ")");
                      region.create(key, newVal);
                      }
                      numCreate++;
                    }
                  }

                  else if (operation.equals("invalidate")) {
                    if (updateLatestValueMap(region , key, threadIdkey, false)) {
                      region.invalidate(key);
                      numInvalidate++;
                    }
                  }
                  else if (operation.equals("destroy")) {
                    if (updateLatestValueMap(region , key, threadIdkey, true)) {
                      if (allowConcurrentMapOps && TestConfig.tab().getRandGen().nextBoolean()) {
                         Log.getLogWriter().fine("remove(" + key + ")");
                         region.remove(key, region.get(key));
                      } else {
                         Log.getLogWriter().fine("destroy(" + key + ")");
                      region.destroy(key);
                      }
                      numDestroy++;
                    }
                  }
                  else {
                    throw new TestException("Unknown entry operation: "
                        + operation);
                  }
                }
                catch (Exception e) {
                  Log.getLogWriter().info(
                      TOTAL_PUT_THREADS
                          + "exception in put thread starting with " + threadId
                          + " for key = " + j);
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;

                }
              }

              // update the blackboard
              
              try {
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_CREATE, numCreate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_UPDATE, numUpdate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_INVALIDATE, numInvalidate);
              HAClientQueueBB.getBB().getSharedCounters().add(
                  HAClientQueueBB.NUM_DESTROY, numDestroy);
              }
              catch (Exception e) {
                  Log.getLogWriter().info(
                       "Exception occured while updating SharedCounter "
                          );
                  exceptionMsg.append(e.getMessage());
                  exceptionOccured = true;
                }

              Log.getLogWriter().info(
                  "Task completed for thread id = " + threadId);
            }
            if (stop)
              break;
          }
        }
      };
      putThreads[i].start();
    }
  }
}
