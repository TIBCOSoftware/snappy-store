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
package nbsTests;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Vector;

import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.TransactionInDoubtException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import durableClients.DurableClientsBB;
import getInitialImage.InitImageBB;
import getInitialImage.InitImagePrms;
import getInitialImage.InitImageTest;
import hct.BBoard;
import hct.HctPrms;
import hct.InterestPolicyTest;
import hydra.*;
import hydra.blackboard.SharedCounters;
import parReg.ParRegBB;
import util.KeyIntervals;
import util.TestException;
import util.TestHelper;
import util.TxHelper;

public class NBSTest extends InterestPolicyTest{
  
  public static final String VM_REG_INTEREST = "Vm Register Interest";
  public static final String REG_INT_ALLKEYS = "Register Interest All Keys";
  public static final String REG_INT_LIST = "Register Interest List";
  public static final String REG_INT_SINGLE_KEY = "Register Interest Single Key";
  public static final String REG_INT_REGEX = "Register Interest Regex";

  private boolean useTransactions = false;
  
  public static void StartTask_initialize() {
    InterestPolicyTest.StartTask_initialize();
  }
  
  /** Initialize the single instance of this test class but not a region. If this VM has 
   *  already initialized its instance, then skip reinitializing.
   */
  public synchronized static void HydraTask_initialize() {
     if (testInstance == null) {
        testInstance = new NBSTest();
        ((NBSTest)testInstance).initInstance();
        ((NBSTest)testInstance).readyForEvents();
     }
  }

  /** durable clients must signal readiness for events.  
   *  This used to be part of InterestPolicyTest.initInstance() but it was moved to registerInterest 
   *  so durableClient/dynamicInterestPolicy tests did not get unwanted events (before InterestResultPolicy was known).
   */
  protected void readyForEvents() {
   // create cache and region
   // checking for durable clients
   String VmDurableId = ((InternalDistributedSystem)InternalDistributedSystem.getAnyInstance()).getConfig().getDurableClientId();

   if (!VmDurableId.equals("")) {
     CacheHelper.getCache().readyForEvents();
   }
  }
  
  /**
   * Validation task to verify the false overriden client's events received
   */
  public synchronized static void HydraTask_validateClientEventsForOnClients() {
    ((NBSTest)testInstance).validateClientEventsForOnClients();
 }
  
  /**
   * Validation task to verify the true overriden client's events received
   */
  public synchronized static void HydraTask_validateClientEventsForOffClients() {
    ((NBSTest)testInstance).validateClientEventsForOffClients();
 }
  
  /**
   * Task to initialize bridge server
   */
  public static void initBridgeServer() {
    if (testInstance == null) {
      testInstance = new NBSTest();
    }
    InterestPolicyTest.initBridgeServer();
 }
  
  /**
   * For clients to register interest to true with notify by subscription over ridden to true
   * @throws Exception
   */
  public static void HydraTask_registerInterestWithTrue() throws Exception {
    final int NUM_CASES = 4;
    int testCase = (int)(BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.ExecutionNumber));
    //Vector registerInterestOptions = TestConfig.tab().vecAt(HctPrms.registerInterestOptions);
    testCase = (testCase % NUM_CASES) + 1;
    registerInterest(testCase, true);
  }
  
  /**
   * For clients to register interest to true with notify by subscription over ridden to false
   * @throws Exception
   */
  public static void HydraTask_registerInterestWithFalse() throws Exception {
    final int NUM_CASES = 4;
    int testCase = (int)(BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.ExecutionNumber));
    //Vector registerInterestOptions = TestConfig.tab().vecAt(HctPrms.registerInterestOptions);
    testCase = (testCase % NUM_CASES) + 1;
    registerInterest(testCase, false);
  }
  
  public static void registerInterest(int testCase, boolean receiveValues) throws Exception{
    switch (testCase) {
      case 1:
        hydra.Log.getLogWriter().info("Calling register interest : ALL_KEYS with receiveValues "+receiveValues);
        HydraTask_registerInterestAllKeys(receiveValues);
        InitImageBB.getBB().getSharedMap().put(VM_REG_INTEREST, REG_INT_ALLKEYS);
        break;
        
      case 2:
        hydra.Log.getLogWriter().info("Calling register interest : LIST with receiveValues "+receiveValues);
        HydraTask_registerInterestList(receiveValues);
        InitImageBB.getBB().getSharedMap().put(VM_REG_INTEREST, REG_INT_LIST);
        break;
        
      case 3:
        hydra.Log.getLogWriter().info("Calling register interest : SINGLE_KEY with receiveValues "+receiveValues);
        HydraTask_registerInterestSingle(receiveValues);
        InitImageBB.getBB().getSharedMap().put(VM_REG_INTEREST, REG_INT_SINGLE_KEY);
        break;
  
      case 4:
        hydra.Log.getLogWriter().info("Calling register interest : REG_EX with receiveValues "+receiveValues);
        HydraTask_registerInterestRegex(receiveValues);
        InitImageBB.getBB().getSharedMap().put(VM_REG_INTEREST, REG_INT_REGEX);
        break;

      default:
        break;
    }
  }
  
  /**
   * Task to register interest with an interest policy with repeated calls with a
   * single key. No verification occurs after each registerInterest call.
   */
  public static void HydraTask_registerInterestSingle(boolean receiveValues) throws Exception {
     NBSTest test = (NBSTest)testInstance;
     int testCase = (int)(BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.testCase));
     Vector resultPolicyVec = TestConfig.tab().vecAt(HctPrms.resultPolicy);
     policy = TestHelper.getResultPolicy(
             (String)(resultPolicyVec.get(testCase % resultPolicyVec.size()))
     );
     test.registerInterestSingle(false /*isPartial*/, receiveValues);
  }

  /** 
   *  Task to register interest with an interest policy with a list.
   */
  public static void HydraTask_registerInterestList(boolean receiveValues) {
     ((NBSTest)testInstance).registerInterest(keyList, receiveValues);
  }
   
  /** 
   *  Task to register interest with an interest policy with ALL_KEYS.
   */
  public static void HydraTask_registerInterestAllKeys(boolean receiveValues) {
     ((NBSTest)testInstance).registerInterest("ALL_KEYS", receiveValues);
  }

  /** 
   *  Task to register interest with an interest policy.
   */
  public static void HydraTask_registerInterestRegex(boolean receiveValues) {
     ((NBSTest)testInstance).registerInterestRegex(receiveValues);
  }
  
  /** Hydra task to execution ops, then stop scheduling.
   */
  public static void HydraTask_doOps() {
     BitSet availableOps = new BitSet(operations.length);
     availableOps.flip(FIRST_OP, LAST_OP+1);
     testInstance.doOps(availableOps);
     if (availableOps.cardinality() == 0) {
        ParRegBB.getBB().getSharedCounters().increment(ParRegBB.TimeToStop);
        throw new StopSchedulingTaskOnClientOrder("Finished with ops");
     }
  }

  /** Do operations on the REGION_NAME's keys using keyIntervals to specify
   *  which keys get which operations. This will return when all operations
   *  in all intervals have completed.
   *
   *  @param availableOps - Bits which are true correspond to the operations
   *                        that should be executed.
   *
   *  Overrides getInitialImage.InitImageTest implementation so that we can 
   *  (temporarily) handle TransactionDataNodeDeparted Exceptions (without 
   *  affecting non-HA tests.
   */
  public void doOps(BitSet availableOps) {
  //   long startTime = System.currentTimeMillis();
  
     // this task gets its useTransactions from the tasktab
     boolean useTransactions = InitImagePrms.useTransactions();
  
     while (availableOps.cardinality() != 0) {
        int whichOp = getOp(availableOps, operations.length);
        boolean doneWithOps = false;
        boolean rolledback = false;
  
        if (useTransactions) {
          TxHelper.begin();
        }
  
        try {
          switch (whichOp) {
             case ADD_NEW_KEY: 
               doneWithOps = addNewKey();
                break;
             case PUTALL_NEW_KEY: 
                 // todo@lhughes -- initially, tx putAll not supported in 6.5
                 // re-enable once supported
                 if (useTransactions) {
                   doneWithOps = true;
                 } else {
                   doneWithOps = putAllNewKey();
                 }
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
                 if (useTransactions) {  // local ops not supported in tx (for edgeClients)
                   doneWithOps = true;
                 } else {
                   doneWithOps = localInvalidate();
                 }
                break;
             case LOCAL_DESTROY:
                 if (useTransactions) { // local ops not supported in tx (for edgeClients)
                   doneWithOps = true;
                 } else {
                   doneWithOps = localDestroy();
                 }
                break;
             default: {
                throw new TestException("Unknown operation " + whichOp);
             }
           }
        } catch (TransactionDataNodeHasDepartedException e) {
          if (!useTransactions) {
            throw new TestException("Unexpected Exception " + e + " " + TestHelper.getStackTrace(e));
          } else {
            Log.getLogWriter().info("Caught Exception " + e + " Expected with 6.5 PR Tx behavior, continuing test.");
            incrementFailedOpCounter(whichOp);
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
            throw new TestException("Unexpected Exception " + e + " " + TestHelper.getStackTrace(e));
          } else {
            Log.getLogWriter().info("Caught Exception " + e + " Expected with 6.5 PR Tx behavior, continuing test.");
            incrementFailedOpCounter(whichOp);
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
              // todo@lhughes -- for 6.5 we accept this behavior
              // for 6.6, we should failover silently to another server
              if (!useTransactions) {
                throw new TestException("Unexpected Exception " + e + " " + TestHelper.getStackTrace(e));
              } else {
                Log.getLogWriter().info("Caught " + e + " Expected with 6.5 PR Tx behavior, continuing test.");
                incrementFailedOpCounter(whichOp);
              }
           } catch (TransactionDataRebalancedException e) {
              // todo@lhughes -- for 6.5 we accept this behavior
              // for 6.6, we should failover silently to another server
              if (!useTransactions) {
                throw new TestException("Caught unexpected Exception " + e + " " + TestHelper.getStackTrace(e));
              } else {
                Log.getLogWriter().info("Caught " + e + " Expected with 6.5 PR Tx behavior, continuing test.");
                incrementFailedOpCounter(whichOp);
              }
           } catch (TransactionInDoubtException e) {
              // todo@lhughes -- for 6.5 we accept this behavior
              // for 6.6, we should failover silently to another server
              if (!useTransactions) {
                throw new TestException("Unexpected Exception " + e + " " +  TestHelper.getStackTrace(e));
              } else {
                Log.getLogWriter().info("Caught " + e + " Expected with 6.5 PR Tx behavior, continuing test.");
                incrementFailedOpCounter(whichOp);
              }
          } catch (ConflictException e) {
            // currently not expecting any conflicts ... only one op per tx
            throw new TestException("Unexpected ConflictException " + TestHelper.getStackTrace(e));
          }
        }
  
        if (doneWithOps) {
           Log.getLogWriter().info("Done with operation " + whichOp);
           availableOps.clear(whichOp);
        }
     }
     Log.getLogWriter().info("Done in doOps");
     
     if (sleepMSAfterOps > 0) {
        // sleep to allow data to be distributed (replication)
        try {
           Log.getLogWriter().info("Sleeping for " + sleepMSAfterOps + " millis to allow ops to be distributed");
           Thread.sleep(sleepMSAfterOps);
        } catch (InterruptedException e) {
           throw new TestException(TestHelper.getStackTrace(e));
        }
     }
  }

  /**
   *  increment NBSTestBB counters for failed tx ops (based on operation)
   */
  private void incrementFailedOpCounter(int op) {

    StringBuffer aStr = new StringBuffer();
    aStr.append("incrementFailedOpCounter: ");

    SharedCounters sc = NBSTestBB.getBB().getSharedCounters();
    int whichCounter = 0;
    switch(op) {
      case InitImageTest.ADD_NEW_KEY:      // intentional fall-thru to PUTALL_NEW_KEY
      case InitImageTest.PUTALL_NEW_KEY:
        whichCounter = NBSTestBB.FAILED_CREATES;
        aStr.append("ADD_NEW_KEY/PUTALL_NEW_KEY failed, incrementing NBSTestBB.FAILED_CREATES, ");
        break;
      case InitImageTest.LOCAL_INVALIDATE: // intentional fall-thru to INVALIDATE case
      case InitImageTest.INVALIDATE:
        whichCounter = NBSTestBB.FAILED_INVALIDATES;
        aStr.append("LOCAL_INVALIDATE/INVALIDATE failed, incrementing NBSTestBB.FAILED_INVALIDATES, ");
        break;
      case InitImageTest.LOCAL_DESTROY:    // intentional fall-thru to DESTROY case
      case InitImageTest.DESTROY:
        whichCounter = NBSTestBB.FAILED_DESTROYS;
        aStr.append("LOCAL_DESTROY/DESTROY failed, incrementing NBSTestBB.FAILED_DESTROYS, ");
        break;
      case InitImageTest.UPDATE_EXISTING_KEY:
        whichCounter = NBSTestBB.FAILED_UPDATES;
        aStr.append("UPDATE_EXISTING_KEY failed, incrementing NBSTestBB.FAILED_UPDATES, ");
        break;
      case InitImageTest.GET:              // intentional fall-thru to default case
      default:
        // do nothing (defaults to 0)
        break;
    }
  
    if (whichCounter > 0) {
       long counter = NBSTestBB.getBB().getSharedCounters().incrementAndRead(whichCounter);
       aStr.append("New value = " + counter);
    }
    Log.getLogWriter().info(aStr.toString()); 
  }
  
  /**
   * Recycle the servers
   * 
   */

  public static void HydraTask_recycleVm() {
    try {
      hydra.MasterController.sleepForMs(5000);
      ClientVmInfo info = ClientVmMgr.stop("Killing the VM",
          ClientVmMgr.MEAN_KILL, ClientVmMgr.IMMEDIATE);
    }
    catch (ClientVmNotFoundException e) {
      Log.getLogWriter().warning(" Exception while killing client ", e);
    }
  }
  
  /** Use the given keys of interest, along with an interest policy to call 
   *  registerInterest, and then check the contents of the region. Calling 
   *  registerInterest is like doing a getInitialImage.
   */
  protected void registerInterest(Object keysOfInterest, boolean receiveValues) {
     final int NUM_CASES = 3;
     int size = aRegion.keys().size();
     Log.getLogWriter().info("Before calling register interest, region size is " + size);
     if (size != 0)
        throw new TestException("Expected region to be size 0, but it is " + size);
     int testCase = (int)(BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.testCase));
     testCase = (testCase % NUM_CASES) + 1;
     Vector resultPolicyVec = TestConfig.tab().vecAt(HctPrms.resultPolicy);
     policy = TestHelper.getResultPolicy(
             (String)(resultPolicyVec.get(testCase % resultPolicyVec.size()))
     );
     
     String VmDurableId = ((InternalDistributedSystem)InternalDistributedSystem
          .getAnyInstance()).getConfig().getDurableClientId();

      if (!VmDurableId.equals("")) {
        BBoard.getInstance().getSharedMap().put(VmDurableId, policy);
        Log.getLogWriter().info("Policy is " + policy.toString());
        HashMap map = (HashMap)DurableClientsBB.getBB().getSharedMap().get(
            VmDurableId);
        map.put("Policy", policy);
        DurableClientsBB.getBB().getSharedMap().put(VmDurableId, map);
        
      }

     int sleepBeforeRegisterInterest = TestConfig.tab().intAt(HctPrms.sleepBeforeRegisterInterest, 0);
     Log.getLogWriter().info("Sleeping for " + sleepBeforeRegisterInterest + " millis");
     MasterController.sleepForMs(sleepBeforeRegisterInterest);
     try {
        Log.getLogWriter().info("Calling registerInterest with keys of interest: " + 
                          TestHelper.toString(keysOfInterest) +
                          ", policy: " + policy);
        long start = System.currentTimeMillis();
        if (!VmDurableId.equals("")) {
          Log.getLogWriter().info("Doing durable register interest");
          aRegion.registerInterest(keysOfInterest, policy, true, receiveValues);
        }
        else {
          Log.getLogWriter().info("Doing non-durable register interest");
          aRegion.registerInterest(keysOfInterest, policy, false, receiveValues);
        }
        long end = System.currentTimeMillis();
        Log.getLogWriter().info("Done calling registerInterest with keys of interest: " + 
                          TestHelper.toString(keysOfInterest) +
                          ", policy: " + policy + ", time to register interest was " +
                          (end - start) + " millis");
     } catch (CacheWriterException e) {
        throw new TestException(TestHelper.getStackTrace(e));
     }
  }

  /** Use a regular expression for keys of interest, along with an interest
   *  policy to call registerInterest, and then check the
   *  contents of the region. Calling registerInterest is like doing
   *  a getInitialImage.
   */
  protected void registerInterestRegex(boolean receiveValues) {
     final int NUM_CASES = 3;
     int size = aRegion.keys().size();
     Log.getLogWriter().info("Before calling register interest, region size is " + size);
     if (size != 0)
        throw new TestException("Expected region to be size 0, but it is " + size);
     int testCase = (int)(BBoard.getInstance().getSharedCounters().incrementAndRead(BBoard.testCase));
     testCase = (testCase % NUM_CASES) + 1;
     Vector resultPolicyVec = TestConfig.tab().vecAt(HctPrms.resultPolicy);
     policy = TestHelper.getResultPolicy(
             (String)(resultPolicyVec.get(testCase % resultPolicyVec.size()))
     );
     
    String VmDurableId = ((InternalDistributedSystem)InternalDistributedSystem
          .getAnyInstance()).getConfig().getDurableClientId();

      if (!VmDurableId.equals("")) {
        BBoard.getInstance().getSharedMap().put(VmDurableId, policy);
        Log.getLogWriter().info("Policy is " + policy.toString());
        HashMap map = (HashMap)DurableClientsBB.getBB().getSharedMap().get(
            VmDurableId);
        map.put("Policy", policy);
        DurableClientsBB.getBB().getSharedMap().put(VmDurableId, map);
      }

     int sleepBeforeRegisterInterest = TestConfig.tab().intAt(HctPrms.sleepBeforeRegisterInterest, 0);
     Log.getLogWriter().info("Sleeping for " + sleepBeforeRegisterInterest + " millis");
     MasterController.sleepForMs(sleepBeforeRegisterInterest);
     Object keysOfInterest = getRegex();
     try {
        Log.getLogWriter().info("Calling registerInterestRegex with keys of interest: " + 
                                TestHelper.toString(keysOfInterest) +
                                ", policy: " + policy);
        long start = System.currentTimeMillis();
        if (!VmDurableId.equals("")) {
          Log.getLogWriter().info("Doing durable register interest");
          aRegion.registerInterestRegex((String)keysOfInterest, policy, true, receiveValues);
        }
        else {
          Log.getLogWriter().info("Doing non-durable register interest");
          aRegion.registerInterestRegex((String)keysOfInterest, policy, false, receiveValues);
        }
        
        long end = System.currentTimeMillis();
        Log.getLogWriter().info("Calling registerInterestRegex with keys of interest: " + 
                                TestHelper.toString(keysOfInterest) +
                                ", policy: " + policy + ", time to register interest was " +
                                (end - start) + " millis");
        
     } catch (CacheWriterException e) {
        throw new TestException(TestHelper.getStackTrace(e));
     }
  }
  
//======================================================================== 
//register interest tasks

/** Repeatedly call registerInterest with a single key, along with 
*  an interest policy. Calling registerInterest is like doing a 
*  getInitialImage.
*
*  @param isPartial If true, we only registered a partial set of keys,
*         rather than all keys used by the test.
*         (Used only if verify is true)
*/
protected void registerInterestSingle(boolean isPartial, boolean receiveValues) throws Exception {
  final int NUM_CASES = 3;
  int size = aRegion.keys().size();
  Log.getLogWriter().info("Before calling register interest, region size is " + size);
  if (size != 0)
     throw new TestException("Expected region to be size 0, but it is " + size);
  int sleepBeforeRegisterInterest = TestConfig.tab().intAt(HctPrms.sleepBeforeRegisterInterest, 0);
  Log.getLogWriter().info("Sleeping for " + sleepBeforeRegisterInterest + " millis");
  MasterController.sleepForMs(sleepBeforeRegisterInterest);
  StringBuffer errStr = new StringBuffer();
  for (int i = 0; i < keyList.size(); i++) {
     Object key = keyList.get(i);
     try {
        Log.getLogWriter().info("Calling registerInterest with single key of interest: " + 
                                TestHelper.toString(key) +
                                ", policy: " + policy);
        long start = System.currentTimeMillis();
        aRegion.registerInterest(key, policy, false, receiveValues);
        long end = System.currentTimeMillis();
//        Log.getLogWriter().info("Done calling registerInterest with single key of interest: " + 
//                                TestHelper.toString(key) +
//                                ", policy: " + policy +
//                                " " + (end - start) + " millis");
     } catch (CacheWriterException e) {
        throw new TestException(TestHelper.getStackTrace(e));
     }
  }
}


protected void validateClientEventsForOnClients(){
  if(aRegion == null){
    aRegion = RegionHelper.getRegion(REGION_NAME);
  }
  EventCounterListener listener = (EventCounterListener) aRegion.getAttributes().getCacheListeners()[0];

  long numOfKeys = TestConfig.tab().longAt(getInitialImage.InitImagePrms.numKeys);
  long numNewKeys = TestConfig.tab().longAt(getInitialImage.InitImagePrms.numNewKeys);
  
  long expectedCreateEvents = 0;
  long expectedInvalidateEvents = 0;
  long expectedUpdateEvents = 0;
  long expectedDestroyEvents = 0;
  
    expectedCreateEvents = numNewKeys;
    expectedInvalidateEvents = keyIntervals.getNumKeys(KeyIntervals.INVALIDATE); //No invalidate events
    expectedUpdateEvents = keyIntervals.getNumKeys(KeyIntervals.UPDATE_EXISTING_KEY);
    expectedDestroyEvents = keyIntervals.getNumKeys(KeyIntervals.DESTROY);

    // If we're using transactions, allow for any failed ops (due to recycleVm/servers)
    // which result in TransactionDataNodeDepartedExceptions
    if (InitImagePrms.useTransactions()) {
       SharedCounters sc = NBSTestBB.getBB().getSharedCounters();

       Log.getLogWriter().info("Adjusting expected event counts based on failed creates (" + sc.read(NBSTestBB.FAILED_CREATES) + "), failed updates (" + sc.read(NBSTestBB.FAILED_UPDATES) + ") and failed destroys (" + sc.read(NBSTestBB.FAILED_DESTROYS) + ")");
       expectedCreateEvents = expectedCreateEvents - sc.read(NBSTestBB.FAILED_CREATES);
       expectedUpdateEvents = expectedUpdateEvents - sc.read(NBSTestBB.FAILED_UPDATES);
       expectedDestroyEvents = expectedDestroyEvents - sc.read(NBSTestBB.FAILED_DESTROYS);
    }
  
  if (expectedCreateEvents != listener.getAfterCreateEvents() || expectedDestroyEvents != listener.getAfterDestroyEvents() || expectedInvalidateEvents != listener.getAfterInvalidateEvents() || expectedUpdateEvents != listener.getAfterUpdateEvents()){
    throw new TestException("Expected events are: "
        + " afterCreateEvents=" + expectedCreateEvents + ", "
        + " afterDestroyEvents=" + expectedDestroyEvents + ", "
        + " afterUpdateEvents=" + expectedUpdateEvents + ", "
        + " afterInvalidateEvents=" + expectedInvalidateEvents + ", "
        + " but received: "+ listener.getEventCountersInfo());
  } else{
    Log.getLogWriter().info("Got the expected events: "+ listener.getEventCountersInfo());
  }
  
}

protected void validateClientEventsForOffClients(){
  if(aRegion == null){
    aRegion = RegionHelper.getRegion(REGION_NAME);
  }
  EventCounterListener listener = (EventCounterListener) aRegion.getAttributes().getCacheListeners()[0];

  long numOfKeys = TestConfig.tab().longAt(getInitialImage.InitImagePrms.numKeys);
  long numNewKeys = TestConfig.tab().longAt(getInitialImage.InitImagePrms.numNewKeys);
  
  long expectedCreateEvents = 0;
  long expectedInvalidateEvents = 0;
  long expectedUpdateEvents = 0;
  long expectedDestroyEvents = 0;

    expectedInvalidateEvents = keyIntervals.getNumKeys(KeyIntervals.INVALIDATE) + keyIntervals.getNumKeys(KeyIntervals.UPDATE_EXISTING_KEY);
    expectedDestroyEvents = keyIntervals.getNumKeys(KeyIntervals.DESTROY);

    // If we're using transactions, allow for any failed ops (due to recycleVm/servers)
    // which result in TransactionDataNodeDepartedExceptions
    if (InitImagePrms.useTransactions()) {
       SharedCounters sc = NBSTestBB.getBB().getSharedCounters();

       Log.getLogWriter().info("Adjusting expected event counts based on failed updates (" + sc.read(NBSTestBB.FAILED_UPDATES) + ") and failed destroys (" + sc.read(NBSTestBB.FAILED_DESTROYS) + ")");
       expectedInvalidateEvents = expectedInvalidateEvents - sc.read(NBSTestBB.FAILED_UPDATES);
       expectedDestroyEvents = expectedDestroyEvents - sc.read(NBSTestBB.FAILED_DESTROYS);
    }

  if (expectedCreateEvents != listener.getAfterCreateEvents() || expectedDestroyEvents != listener.getAfterDestroyEvents() || expectedInvalidateEvents != listener.getAfterInvalidateEvents() || expectedUpdateEvents != listener.getAfterUpdateEvents()){
    throw new TestException("Expected events are "+expectedCreateEvents+ " afterCreateEvents" + expectedInvalidateEvents+ " afterInvalidateEvents "+ expectedUpdateEvents + " afterUpdateEvents "+ expectedDestroyEvents+ " afterDestroyEvents but received "+ listener.getEventCountersInfo());
  } else{
    Log.getLogWriter().info("Got the expected events: "+ listener.getEventCountersInfo());
  }
  
}
  
}
