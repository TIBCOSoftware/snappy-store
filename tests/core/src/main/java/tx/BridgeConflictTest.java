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
package tx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import hydra.BridgeHelper;
import hydra.ClientDescription;
import hydra.GsRandom;
import hydra.HydraThreadLocal;
import hydra.Log;
import hydra.ProcessMgr;
import hydra.TestConfig;
import util.BaseValueHolder;
import util.CacheUtil;
import util.NameFactory;
import util.TestException;
import util.TestHelper;
import util.TxHelper;
import util.ValueHolder;

/**
 * A class to test conflicts in transactions.
 */
public class BridgeConflictTest {

// single instance of this test class
static public BridgeConflictTest testInstance = null;

// instance fields
private HydraThreadLocal txState = new HydraThreadLocal();
protected int testConfiguration;       // For conflict tests, two choices:
                                       //    1: 1 thread per multi-vms
                                       //    2: multi-threads per 1 VM

// static fields
protected final static String NOT_IN_TRANS = "Not in transaction";
protected final static int ONE_THREAD_PER_MULTI_VMS = 1;
protected final static int MULTI_THREADS_PER_ONE_VM = 2;
protected final static int UNDEFINED = 3;

// ======================================================================== 
// hydra tasks

/** Hydra task to create a forest of region hierarchies. This task
 *  can be called from more than one thread in the same VM.
 */
public synchronized static void HydraTask_initializeConcTest() {
   if (testInstance == null) {
      testInstance = new BridgeConflictTest();
      testInstance.testConfiguration = UNDEFINED;
      testInstance.initialize();
   }
}

/** Hydra task to create a forest of region hierarchies. This task
 *  can be called from more than one thread in the same VM.
 */
public synchronized static void HydraTask_initializeSerialTest() {
   if (testInstance == null) {
      testInstance = new BridgeConflictTest();
      testInstance.testConfiguration = UNDEFINED;
      int numVMs = getNumVMs();
      if (numVMs == 1) { 
         // test has 1 VM and multi-threads (at least 2, test cannot work with 1 VM and 1 thread)
         if (getNumThreads() == 1)
            throw new TestException("Test cannot run with 1 VM and 1 thread");
         testInstance.testConfiguration = MULTI_THREADS_PER_ONE_VM;
      } else { // test has > 1 VM
         int numThreads = getNumThreads();
         if (getNumThreads() != 1) 
            throw new TestException("Test cannot run with multi-VMs (" + numVMs + 
                                    ") having > 1 threads (" + numThreads + ")");
         testInstance.testConfiguration = ONE_THREAD_PER_MULTI_VMS;
      }
      testInstance.initialize();
   }
}

/* getNumVMs() - get the number of edgeClient VMs
 *    For client/server tests, this will be the number of edgeClients.
 */
protected static int getNumVMs() {
    
    int numVMs = TestHelper.getNumVMs();  
    int bridgeVMs = BridgeHelper.getEndpoints().size();
    if (bridgeVMs > 0)  {
      // reset this for client/server tests (edge clients are the members doing ops)
      numVMs = numVMs - bridgeVMs;
    }
    return numVMs;
}

/* getNumThreads() returns the number of threads in the VM invoking this method.
 *
 */
protected static int getNumThreads() {
   String clientName = System.getProperty( "clientName" );
   ClientDescription cd = TestConfig.getInstance().getClientDescription( clientName );
   int numThreads = cd.getVmThreads();
   return numThreads; 
}

protected void initialize() {
   Log.getLogWriter().info("Test instance is " + this);
}

/** Hydra task for serial conflict test with transaction sometimes */
public static void HydraTask_serialConflictTest() {
   testInstance.serialConflictTest();
}

/** Hydra task for serial conflict test with transactions */
public static void HydraTask_serialTxOnlyConflictTest() {
   testInstance.serialTxOnlyConflictTest();
}

/** Hydra task for serial conflict test with repeatable read ops */
public static void HydraTask_serialTxOnlyRepeatableRead() {
   TxUtil.txUtilInstance.suspendResume = true;
   testInstance.serialTxOnlyRepeatableRead();
}

/** Hydra task for serial conflict test */
public static void HydraTask_endTask() {
   hydra.blackboard.SharedCounters sc = TxBB.getBB().getSharedCounters();
   long commitSuccess = sc.read(TxBB.TX_SUCCESS);
   long commitFailure = sc.read(TxBB.TX_FAILURE);
   long rollBack = sc.read(TxBB.TX_ROLLBACK);
   long notInTrans = sc.read(TxBB.NOT_IN_TRANS);
   Log.getLogWriter().info("CommitSuccess count:   " + commitSuccess +
                           "CommitFailure count:   " + commitFailure +
                           "RollBack count     :   " + rollBack +
                           "Tasks not in tx count: " + notInTrans);
}

/** Hydra task for serial conflict test */
public static void HydraTask_concConflictTest() {
   testInstance.concConflictTest();
}

// ======================================================================== 
// methods that implement the test tasks

protected void concConflictTest() {
   GsRandom rand = TestConfig.tab().getRandGen(); 
   int tasksInTxPercentage = TxPrms.getTasksInTxPercentage();
   if (rand.nextInt(1, 100) <= tasksInTxPercentage) { // run this task inside a transaction
      TxHelper.begin();
   } else { // run outside a transaction
      TxBB.inc(TxBB.NOT_IN_TRANS);
   }

   // do random operations
   TxUtil.doOperations();  // does numOps operations

   // if in a transaction; decide whether to commit or rollback
   if (TxHelper.exists()) {
      int commitPercentage = TxPrms.getCommitPercentage();
      if (rand.nextInt(1, 100) <= commitPercentage) { // do a commit
         try {
            TxHelper.commit();
            TxBB.inc(TxBB.TX_SUCCESS);
         } catch (ConflictException e) {
            TxBB.inc(TxBB.TX_FAILURE);
            Log.getLogWriter().info("Caught " + e + "; continuing test");
         }
      } else { // do a rollback
         TxHelper.rollback();
         TxBB.inc(TxBB.TX_ROLLBACK);
      }
   }

   TxUtil.txUtilInstance.createAllDestroyedRegions(true); // creates the original hier
}

/** Do the serial conflict task for transactional threads and transactional
 *  operations only (operations can be rolled back).  This configuration is 
 *  for two VMs with 1 thread each, or a single VM with two threads. 
 *  First round:
 *     The first thread in the round does random operations in a transaction.
 *     All other threads in the round either do operations to create a conflict
 *         or not create a conflict, always in a transaction.
 *  Second round:
 *     The first thread expects a successful commit.
 *     All other threads will either commit successfully or fail depending
 *         on whether they created conflicts or not.
 */
protected void serialTxOnlyConflictTest() {
   boolean firstInRound = TxUtil.logRoundRobinNumber();
   TxUtil.logExecutionNumber();
   long rrNumber = TxUtil.getRoundRobinNumber();
   long whichRound = rrNumber % 3;
   if (whichRound == 1) { // round to open transactions
      Log.getLogWriter().info("In round " + rrNumber + " to open transactions, firstInRound is " + firstInRound);
      TxHelper.begin();

      if (firstInRound) { // do random operations and write them to BB
         TxBB.getBB().getSharedMap().put("Op1PID", "" + ProcessMgr.getProcessId());
         TxUtil.txUtilInstance.summarizeRegionHier();
         OpList originalOps = TxUtil.doOperations();

         // write the ops to the blackboard
         OpList opListToWrite = new OpList();
         for (int i = 0; i < originalOps.numOps(); i++) {
            Operation op = originalOps.getOperation(i);
            OpList tmpOpList = new OpList();
            tmpOpList.add(op);
            recordNewOps(opListToWrite, tmpOpList);
         } 
         TxBB.getBB().getSharedMap().put(TxBB.OpListKey, opListToWrite);
         txState.set(TxHelper.internalSuspend()); 
      } else { // get prior operations and use them for creating or not creating conflicts
         OpList priorOps = (OpList)(TxBB.getBB().getSharedMap().get(TxBB.OpListKey));
         boolean useSameRegionOrKey = (TestConfig.tab().getRandGen().nextInt(1, 100) <= 75);
         OpList newOps = doOps(priorOps, 1, useSameRegionOrKey);
         boolean commitStatus = recordCommitStatusTxOnly(priorOps, newOps);
         if (commitStatus) { 
            recordNewOps(priorOps, newOps);
         }
         txState.set(TxHelper.internalSuspend()); 
      }
   } else if (whichRound == 2) { // close transactions
      TxHelper.internalResume((TXStateProxy)txState.get()); 
      Log.getLogWriter().info("In round " + rrNumber + " to close transactions, firstInRound is " + firstInRound);
      if (firstInRound) {
         TxHelper.commitExpectSuccess();
         TxBB.inc(TxBB.TX_SUCCESS);
      } else {
         boolean commitSuccess = TxBB.getCommitStatus(); // expected commit success/failure for this thread
         Log.getLogWriter().info("Attempting a commit with expected commit success: " + commitSuccess);
         if (commitSuccess) {
            TxHelper.commitExpectSuccess();
            TxBB.inc(TxBB.TX_SUCCESS);
         } else {
            TxHelper.commitExpectFailure();
            TxBB.inc(TxBB.TX_FAILURE);
         }
      }
   } else { // round to replace destroyed regions
      Log.getLogWriter().info("In round " + rrNumber + " to replace destroyed regions, firstInRound is " + firstInRound);
      TxUtil.txUtilInstance.createAllDestroyedRegions(true);
   }
}

/** Do the serial conflict task for transactional threads and transactional
 *  operations only (operations can be rolled back).  This configuration is 
 *  for two VMs with 1 thread each, or a single VM with two threads. 
 *  This method is to test conflicts caused by a fix for feature 36688,
 *  where gets now note the committed state whereas they used to be noops. 
 *  This fix also introduces repeatable reads.
 *  First round:
 *     The first thread in the round does a begin
 *     The other thread in the round does a begin and either a repeatable read
 *         or a nonrepeatable read.
 *  Second round:
 *     The first thread does ops and commits (expects a successful commit).
 *     The other thread does ops that either conflict or not and will
 *         attempt a commit, expecting either success or failure 
 *         depending on whether the ops created a conflict or not.
 */
protected void serialTxOnlyRepeatableRead() {
   boolean firstInRound = TxUtil.logRoundRobinNumber();
   TxUtil.logExecutionNumber();
   long rrNumber = TxUtil.getRoundRobinNumber();
   long whichRound = rrNumber % 3;
   if (whichRound == 1) { // round to open transactions
      // the first thread in the round only begins transactions, the second
      // thread in the round begins a transaction and does operation(s) that
      // support repeatable reads 
      Log.getLogWriter().info("In round " + rrNumber + " to open transactions, firstInRound is " + firstInRound);
      TxHelper.begin();

      if (!firstInRound) { // do operations that have repeatable reads
         TxBB.getBB().getSharedMap().put("Op1PID", "" + ProcessMgr.getProcessId());
         OpList readOps = null;
         if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 80) {
            readOps = TxUtil.txUtilInstance.doRepeatableReadOperations(1);
            TxBB.getBB().getSharedMap().put(TxBB.RepeatableRead, new Boolean(true));
         } else {
            readOps = TxUtil.txUtilInstance.doNonrepeatableReadOperations(1);
            TxBB.getBB().getSharedMap().put(TxBB.RepeatableRead, new Boolean(false));
         }
         TxBB.getBB().getSharedMap().put(TxBB.OpListKey, readOps);
      } 
      logTxSet();
      txState.set(TxHelper.internalSuspend()); 
   } else if (whichRound == 2) { // the first thread does ops and commits (success)
                                 // the second thread does ops and commits (either success or failure)
      TxHelper.internalResume((TXStateProxy)txState.get()); 
      Log.getLogWriter().info("In round " + rrNumber + ", firstInRound is " + firstInRound);
      OpList readOps = (OpList)(TxBB.getBB().getSharedMap().get(TxBB.OpListKey));
      boolean readOpsContainRR = ((Boolean)(TxBB.getBB().getSharedMap().get(TxBB.RepeatableRead))).booleanValue();
      if (firstInRound) {
         OpList newOps = doOps(readOps, 1, true);
         TxBB.getBB().getSharedMap().put(TxBB.OpListKey2, newOps);
         logTxSet();
         TxHelper.commitExpectSuccess();
         TxBB.inc(TxBB.TX_SUCCESS);
      } else { // do ops, then commit
         OpList t1_ops = (OpList)(TxBB.getBB().getSharedMap().get(TxBB.OpListKey2));
         boolean useSameRegionOrKey = true;
         if (readOpsContainRR) {
            useSameRegionOrKey = (TestConfig.tab().getRandGen().nextInt(1, 100) <= 75);
         } 
         OpList newOps = doOps(readOps, 1, useSameRegionOrKey);
         boolean commitStatus = getCommitStatusRR(readOps, t1_ops, newOps, readOpsContainRR);
         logTxSet();
         Log.getLogWriter().info("Determining commit status,\nReadOps: " + readOps + "\n" +
             "first thread ops (committed): " + t1_ops + "\nsecond thread ops: " + newOps +
             "\nCommit status for second thread is " + commitStatus);
         verifyRepeatableReads(readOps, t1_ops, newOps, readOpsContainRR);
         Log.getLogWriter().info("Attempting a commit with expected commit success: " + commitStatus);
         if (commitStatus) {
            TxHelper.commitExpectSuccess();
            TxBB.inc(TxBB.TX_SUCCESS);
         } else {
            TxHelper.commitExpectFailure();
            TxBB.inc(TxBB.TX_FAILURE);
         }
      }
   }
}

/** Do the serial conflict task for transactional and/or non-transactional
 *  threads, and transactional and non-transactional operations (operations
 *  that occur immediately and cannot be rolled back).
 *  This configuration is for 2 threads only (either 2 VMs with 1 thread
 *  each, or 1 VM with 2 threads.
 *  First round:
 *     The first thread in the round does random operations, always
 *        in a transaction
 *     The second thread randomly chooses to be in a transaction or
 *        not, and randomly chooses both transactional and non-transactional
 *        operations.
 *  Second round:
 *     The first thread commits; it can succeed for fail based on what
 *        the second thread did.
 *     If the first thread failed its commit, this commit (if in a 
 *        transaction) will succeed. If the first thread suceeded in its
 *        commit, then this thread will fail if it caused conflicts, and
 *        succeed if it did not.
 */
protected void serialConflictTest() {
   boolean firstInRound = TxUtil.logRoundRobinNumber();
   TxUtil.logExecutionNumber();
   long rrNumber = TxUtil.getRoundRobinNumber();
   long whichRound = rrNumber % 3;
   if (whichRound == 1) { // round to open transactions
      Log.getLogWriter().info("In round " + rrNumber + " to open transactions, firstInRound is " + firstInRound);
      if (firstInRound) { // do random operations and write them to BB
         TxBB.getBB().getSharedMap().put("Op1PID", "" + ProcessMgr.getProcessId());
         TxUtil.txUtilInstance.summarizeRegionHier();
         TxHelper.begin();
         OpList originalOps = TxUtil.doOperations();
         TxBB.getBB().getSharedMap().put(TxBB.OpListKey, originalOps);
         txState.set(TxHelper.internalSuspend()); 
      } else { // second in round; get prior operations and use them for creating or not creating conflicts
         boolean inTrans = executeInTx();
         if (inTrans) { // randomly begin a transaction or not
            TxHelper.begin();
            inTrans = true;
         } else {
            Log.getLogWriter().info("Running outside a transaction...");
         }

         // do ops to create a conflict or not
         OpList priorOps = (OpList)(TxBB.getBB().getSharedMap().get(TxBB.OpListKey));
         boolean useSameRegionOrKey = (TestConfig.tab().getRandGen().nextInt(1, 100) <= 75);
         OpList newOps = doOps(priorOps, 1, useSameRegionOrKey);
         recordCommitStatus(inTrans, priorOps, newOps);
         txState.set(TxHelper.internalSuspend()); 
      }
   } else if (whichRound == 2) { // close transactions
      TxHelper.internalResume((TXStateProxy)txState.get()); 
      Log.getLogWriter().info("In round " + rrNumber + " to close transactions, firstInRound is " + firstInRound);
      if (firstInRound) {
         boolean expectedCommit = ((Boolean)(TxBB.getBB().getSharedMap().get(TxBB.FirstInRoundCommitStatus))).booleanValue();
         if (expectedCommit) {
            Log.getLogWriter().info("Attempting a commit; expect success");
            TxHelper.commitExpectSuccess();
            TxBB.inc(TxBB.TX_SUCCESS);
         } else {
            Log.getLogWriter().info("Attempting a commit; expect failure");
            TxHelper.commitExpectFailure();
            TxBB.inc(TxBB.TX_FAILURE);
         }
      } else {
         Object expectedCommit = TxBB.getBB().getSharedMap().get(TxBB.SecondInRoundCommitStatus);
         if (expectedCommit.equals(NOT_IN_TRANS)) {
            if (TxHelper.exists())
               throw new TestException("Transaction exists is true, but expected to not be in a tx");
            Log.getLogWriter().info("Not in transaction; not committing");
            TxBB.inc(TxBB.NOT_IN_TRANS);
         } else {
            if (expectedCommit.equals(new Boolean(true))) {
               Log.getLogWriter().info("Attempting a commit; expect success");
               TxHelper.commitExpectSuccess();
               TxBB.inc(TxBB.TX_SUCCESS);
            } else {
               Log.getLogWriter().info("Attempting a commit; expect failure");
               TxHelper.commitExpectFailure();
               TxBB.inc(TxBB.TX_FAILURE);
            }
         }
      }
   } else { // round to replace any destroyed regions 
      Log.getLogWriter().info("In round " + rrNumber + " to replace destroyed transactions, firstInRound is " + firstInRound);
      TxUtil.txUtilInstance.createAllDestroyedRegions(true);
   }
}

// ======================================================================== 
// methods that do operations to write specific regions/keys or not write
// specific regions/key

/** Method that will execute operation(s) that either intentionaly write
 *  or not write to regions and/or keys in opList.
 *
 *  @param opList A list of operations we want to consider for potential
 *         to intentionally write or not write to same regions/keys.
 *  @param numOps The number of operations this method should execute.
 *  @param targetSame True if the operations done by this method should
 *         do operations that use the same regions/keys as opList, false
 *         otherwise.
 *
 *  @returns The operations done by this method, or null if no appropriate
 *           operations could be done.
 */
protected OpList doOps(OpList opList, int numOps, boolean targetSame) {
   // log what we are doing
   String aStr = "Attempting " + numOps + " operations to ";
   if (!targetSame)
      aStr = aStr + "not ";
   aStr = aStr + "target same regions/keys as " + opList;
   Log.getLogWriter().info(aStr);

   // loop until numOps have been completed
   OpList resultOpList = new OpList();
   int currentOpIndex = TestConfig.tab().getRandGen().nextInt(0, opList.numOps()-1); // index into opList
   int lastSuccessfulIndex = -1; // index into opList
   while (resultOpList.numOps() < numOps) {
      // get an operation to target
      currentOpIndex++;
      if (currentOpIndex >= opList.numOps()) { // circle through the operations
         currentOpIndex = 0;
      }
      Operation operation = opList.getOperation(currentOpIndex);

      // see if we have looped through all operations in opList without
      // finding an appropriate operation
      if (lastSuccessfulIndex == -1) { // this is the first time through the while
         lastSuccessfulIndex = currentOpIndex;
      } else if (lastSuccessfulIndex == currentOpIndex) {
         // we cycled through all operations in opList and cannot find
         // enough (or any) appropriate operations
//         Log.getLogWriter().info("Unable to find an appropriate operation"); 
//         return null;
         currentOpIndex = 0; // start over
      }

      // get an operation that will target same region/keys or not
      Operation executedOp = null;
      if (targetSame) {
         executedOp = doOpWithSame(operation);
      } else {
         executedOp = doOpWithDifferent(opList, operation);
      } 

      int opCount = resultOpList.numOps();
      resultOpList.add(executedOp);
      if (resultOpList.numOps() != opCount) {
         // opList changed size on add, so op was different than chosen previously
         lastSuccessfulIndex = currentOpIndex;
      } 
   }
   aStr = "Executed the following operations: " + resultOpList + "\nthat ";
   if (!targetSame)
      aStr = aStr + "do not ";
   aStr = aStr + "operate on same region and/or key as\n" + opList;
   Log.getLogWriter().info(aStr);
   return resultOpList;
}

/** Given an operation, execute an operation that involves that same 
 *  key and/or region.
 *
 *  @param operation - the operation to base the new operation on.
 *
 *  @return The operation that was executed.
 */
protected Operation doOpWithSame(Operation operation) {
   // initialize
   Operation executedOp = null;
   String regionName = operation.getRegionName();
   Region aRegion = CacheUtil.getCache().getRegion(regionName);
   Object key = operation.getKey();
   Object keyToUse = key;
   boolean containsKey = (key == null) ? (false) : (TxUtil.txUtilInstance.containsKey(aRegion, key));

   GsRandom rand = TestConfig.tab().getRandGen();
   int randInt = rand.nextInt(1, 100);

   // consider the case where the region in operation does not exist in this VM
   if (aRegion == null) { // region does not exist; create it AND put an entry in it
      // because a create itself is not considered a write for conflict purposes
      Log.getLogWriter().info("In doOpWithSame: " + regionName + " does not existing in this VM; creating it and putting");
      aRegion = (Region)((TxUtil.txUtilInstance.createRegionWithPath(regionName, false))[0]);
      executedOp = putNewOrPreviousKey(aRegion);
      return executedOp;
   } 

   // no key specified in operation or aRegion does not contain the key
   if ((key == null) || (!containsKey)) {
      keyToUse = TxUtil.txUtilInstance.getRandomKey(aRegion); // see if there are other keys in aRegion
      Log.getLogWriter().info("For " + regionName + ", key is " + key + ", containsKey is " + 
                         containsKey + ", another key in region is " + keyToUse);
   }

   if (keyToUse == null) { // aRegion is empty; create a new entry 
      randInt = rand.nextInt(1, 100);
      if (randInt <= 50) { // create a new entry
         Log.getLogWriter().info("In doOpWithSame: " + regionName + " is empty, creating new entry");
         executedOp = putNewOrPreviousKey(aRegion);
      } else { // do a get
         randInt = rand.nextInt(1, 100);
         if (randInt <= 50) { // use an existing key
            long keysUsed = NameFactory.getPositiveNameCounter();
            keyToUse = NameFactory.getObjectNameForCounter(TestConfig.tab().getRandGen().nextInt(1, (int)keysUsed));
            Log.getLogWriter().info("In doOpWithSame: " + regionName + " is empty, getting with previous key");
            executedOp = TxUtil.txUtilInstance.getEntry(aRegion, keyToUse, Operation.ENTRY_GET_PREV_KEY);
          } else { // use a new key
            keyToUse = NameFactory.getNextPositiveObjectName();
            Log.getLogWriter().info("In doOpWithSame: " + regionName + " is empty, getting with new key");
            executedOp = TxUtil.txUtilInstance.getEntry(aRegion, keyToUse, Operation.ENTRY_GET_NEW_KEY);
          }
      }
      return executedOp;
   }

   // to get here either a key was specified in the operation argument (and it
   // exists in the region), or the key in the operation argument was null and we 
   // chose another random key from aRegion
   Log.getLogWriter().info("In doOpWithSame: choosing an entry operation");
   randInt = rand.nextInt(1, 100);
   if (randInt <= 20) { // create an entry
      executedOp = putNewOrPreviousKey(aRegion);
   } else if (randInt <= 40) { // destroy an entry
      executedOp = TxUtil.txUtilInstance.destroyEntry(false, aRegion, keyToUse, false);
   } else if (randInt <= 60 && !operation.isPreviouslyInvalid()) { // invalidate an entry
      executedOp = TxUtil.txUtilInstance.invalEntry(false, aRegion, keyToUse, false);
   } else if (randInt <= 80) { // update the entry
      executedOp = TxUtil.txUtilInstance.updateEntry(aRegion, keyToUse);
   } else { // do a get
      executedOp = TxUtil.txUtilInstance.getEntry(aRegion, keyToUse, Operation.ENTRY_GET_EXIST_KEY);
   }
   Log.getLogWriter().info("Executed operation\n" + executedOp + 
                           "\nto use same region and/or key as \n" + operation);
   if (executedOp == null)
      throw new TestException("Problem in test configuration, executedOp is " + executedOp);
   return executedOp;
}

/** Execute an operation that does not operation on the same region/key
 *  as any operation in opList. 
 *
 *  @param opList The list of operations to consider (to avoid
 *         their regions/keys).
 *  @param operation The operation to consider for not generating
 *         a specific region/key write. This is so we can scrutinize 
 *         one particular operation (this should be an operation in 
 *         the opList) so the resulting operation can target specific 
 *         regions/keys.
 *
 *  @return The operation that was executed.
 */
protected Operation doOpWithDifferent(OpList opList, Operation operation) {
   Operation executedOp = null;
   String regionName = operation.getRegionName();
   Region aRegion = CacheUtil.getCache().getRegion(regionName);
   Object key = operation.getKey();

   // region does not exist; create it, which is not a "write"
   if (aRegion == null) { 
      Log.getLogWriter().info("In doOpWithDifferent: " + regionName + " does not exist; creating it");
      aRegion = (Region)((TxUtil.txUtilInstance.createRegionWithPath(regionName, false))[0]);
      executedOp = new Operation(regionName, null, Operation.REGION_CREATE, null, null);
   } else if (key != null) { // region exists and have an operation with a key (entry op)
      //    options for are:
      //    1) any operation with this key in another region
      //    2) any operation with another key in this region 
      //    3) get with this region and a new key
   
      // see which options work; try for option 1)
      ArrayList options = new ArrayList(); // this holds options that can work
      Region newRegion = TxUtil.txUtilInstance.getRandomRegion(true, aRegion, TxUtil.NO_REPLICATION_RESTRICTION);
      if (newRegion != null) { // found a region other than this one
         if (!entryOpConflictsWith(opList, newRegion.getFullPath(), key)) {
            options.add(new Integer(1));
         }
      }
      // try for option 2)
      Object newKey = TxUtil.txUtilInstance.getRandomKey(aRegion, key);
      if (newKey != null) {
         if (!entryOpConflictsWith(opList, regionName, newKey)) {
            options.add(new Integer(2));
         }
      } 
      // option 3 will always work as gets never cause a conflict
      options.add(new Integer(3));
   
      // choose an option that will work
      int randInt = TestConfig.tab().getRandGen().nextInt(0, options.size()-1);
      int choice = ((Integer)options.get(randInt)).intValue();
      Log.getLogWriter().info("In doOpWithDifferent: choosing option " + choice); 
      if (choice == 1) {
         executedOp = doAnyEntryOperation(newRegion, key);
      } else if (choice == 2) {
         executedOp = doAnyEntryOperation(aRegion, newKey);
      } else { // option 3 
         executedOp = TxUtil.txUtilInstance.getEntry(aRegion, NameFactory.getNextPositiveObjectName(), 
                                                     Operation.ENTRY_GET_EXIST_KEY);
      }
   } else { // operation does not have a key specified; must be a region operation
      if (!operation.isRegionOperation())
         throw new TestException("Unknown operation " + operation);
      Log.getLogWriter().info("In doOpWithDifferent: doing a get");
      executedOp = getWithAnyKey(aRegion);
   } 

   Log.getLogWriter().info("Executed operation\n" + executedOp + 
                           "\nto not write same region and/or key as \n" + opList);
   if (executedOp == null)
      throw new TestException("Problem in test configuration, executedOp is " + executedOp);
   return executedOp;
}

/** Execute any operation with the given region and key. 
 *
 *  @param aRegion The region to do the operation on.
 *  @param key The key to do the operation with.
 *
 *  @return An instance of Operation indicating the executed operation.
 */
private Operation doAnyEntryOperation(Region aRegion, Object key) { 
   Operation executedOp = null;
   boolean containsKey = TxUtil.txUtilInstance.containsKey(aRegion, key);
   if (containsKey) {  
      // have a key and maybe a value; can do update, destroy, invalidate, get
      int randInt = TestConfig.tab().getRandGen().nextInt(1, 100);
      if (randInt <= 25) { // do an update
         executedOp = TxUtil.txUtilInstance.updateEntry(aRegion, key);
      } else if (randInt <= 50) { // do a destroy
         executedOp = TxUtil.txUtilInstance.destroyEntry(false, aRegion, key, false);         
/* todo@lhughes -- resolve invalidate issue
      } else if (randInt <= 75) { // do an invalidate
         executedOp = TxUtil.txUtilInstance.invalEntry(false, aRegion, key, false);         
*/
      } else { // do a get
         executedOp = TxUtil.txUtilInstance.getEntry(aRegion, key, Operation.ENTRY_GET_EXIST_KEY);
      }
   } else { // we don't have a key; can do a create or a get
      if (TestConfig.tab().getRandGen().nextBoolean()) {
         BaseValueHolder vh = new ValueHolder(key, TxUtil.txUtilInstance.randomValues, 
                                         new Integer(TxUtil.txUtilInstance.modValInitializer++));
         executedOp = TxUtil.txUtilInstance.putEntry(aRegion, key, vh, Operation.ENTRY_CREATE);
      } else {
         executedOp = TxUtil.txUtilInstance.getEntry(aRegion, key, Operation.ENTRY_GET_PREV_KEY);
      }
   }
   return executedOp;
}

/** Determine if an entry operation with the given region and key will conflict
 *  with any operation in the operation list.
 *
 *  @param opList Operations to used to determine conflicts.
 *  @param regionName The name of a region to consider for conflicts with opList.
 *  @param key The key for regionName to considert for conflicts with opList.
 *
 *  @returns true if an entry operation using regionName and key would conflict
 *           with any operation in opList, false otherwise.
 */
protected boolean entryOpConflictsWith(OpList opList, String regionName, Object key) {
   for (int i = 0; i < opList.numOps(); i++) {
      Operation op = opList.getOperation(i);
      if (op.getOpName().equals(Operation.REGION_CREATE)) {
         // cannot conflict with region creation
      } else if (op.isGetWithLoad()) {
         // loads can now cause conflicts
         return true;
      } else {
         // fakeOp is for calling affectedBy
         Operation fakeOp = new Operation(regionName, key, Operation.ENTRY_UPDATE, null, null);
         if (op.affectedBy(fakeOp))
            return true;
      }
   } 
   return false;
}

/** Given a region, create a new entry by randomly choosing either a new
 *  or previously used key. 
 *
 *  @param aRegion Put into this region.
 *
 *  @returns The Operation for the put that was executed (ENTRY_CREATE).
 */
protected Operation putNewOrPreviousKey(Region aRegion) {
   Object key = null;
   if (TestConfig.tab().getRandGen().nextBoolean()) { // use a previous key
      long keysUsed = NameFactory.getPositiveNameCounter();
      key = NameFactory.getObjectNameForCounter(TestConfig.tab().getRandGen().nextInt(1, (int)keysUsed));
      BaseValueHolder vh = new ValueHolder(key, TxUtil.txUtilInstance.randomValues, 
                                       new Integer(TxUtil.txUtilInstance.modValInitializer++));
      return TxUtil.txUtilInstance.putEntry(aRegion, key, vh, 
                                           (aRegion.containsKey(key) ? Operation.ENTRY_UPDATE
                                                                     : Operation.ENTRY_CREATE));
   } else { // use a new key
      key = NameFactory.getNextPositiveObjectName();
      BaseValueHolder vh = new ValueHolder(key, TxUtil.txUtilInstance.randomValues, 
                                       new Integer(TxUtil.txUtilInstance.modValInitializer++));
      return TxUtil.txUtilInstance.putEntry(aRegion, key, vh, Operation.ENTRY_CREATE);
   }
}

/** Do a get with any key (existing, previous or new).
 *
 *  @param aRegion The region to do the get on.
 *
 *  @returns An operation describing the get.
 */
protected Operation getWithAnyKey(Region aRegion) {
   int randInt = TestConfig.tab().getRandGen().nextInt(1, 100);
   if (randInt <= 33) { // try an existing key
      Object key = TxUtil.txUtilInstance.getRandomKey(aRegion);
      if (key != null) {
         return TxUtil.txUtilInstance.getEntry(aRegion, key, Operation.ENTRY_GET_EXIST_KEY);
      }
   }

   // if we get here then either we did not want to try an existing key, or 
   // an existing key was not available
   if (TestConfig.tab().getRandGen().nextBoolean()) { // use a previous key
      long keysUsed = NameFactory.getPositiveNameCounter();
      Object key = NameFactory.getObjectNameForCounter(TestConfig.tab().getRandGen().nextInt(1, (int)keysUsed));
      if (TxUtil.txUtilInstance.containsKey(aRegion, key)) {
         return TxUtil.txUtilInstance.getEntry(aRegion, key, Operation.ENTRY_GET_EXIST_KEY);
      } else {
         return TxUtil.txUtilInstance.getEntry(aRegion, key, Operation.ENTRY_GET_PREV_KEY);
      }
   } else { // use a new key 
      Object key = NameFactory.getNextPositiveObjectName();
      return TxUtil.txUtilInstance.getEntry(aRegion, key, Operation.ENTRY_GET_NEW_KEY);
   }
}

// ======================================================================== 
// methods to consider whether conflicts should occur or not

/** This writes expected commit status to the blackboard for tests that 
 *  run only in transactions and use only transactional (can be rolled back)
 *  operations. This test is configured for multi VMs and threads.
 *  As a result, a key in the blackboard is written for this thread's
 *  expected commit success/failure status.
 *
 *  @param priorOps The list of operations done by the first thread.
 *  @param newOps The list of operations done by the second thread (has
 *                only one op).
 *
 *  @returns True if the commit is expected to succeed for this thread,
 *           false otherwise.
 */
protected boolean recordCommitStatusTxOnly(OpList priorOps, OpList newOps) {
   hydra.blackboard.SharedMap sm = TxBB.getBB().getSharedMap();

   // no ops were done
   if (newOps == null) { 
      Log.getLogWriter().info("recordCommitStatusTxOnly: No ops were done");
      TxBB.recordCommitStatus(true);
      return true;
   }

   boolean thisThreadHasConflicts = thread2HasConflict(priorOps, newOps);
   boolean expectedCommitStatus = !thisThreadHasConflicts;
   TxBB.recordCommitStatus(expectedCommitStatus);
   return expectedCommitStatus;
}

/** This writes expected commit status to the blackboard for tests that 
 *  are testing repeatable read transactions. 
 *  As a result, a key in the blackboard is written for this thread's
 *  expected commit success/failure status.
 *  First round:
 *     The first thread in the round does a begin
 *     The other thread in the round does a begin and either a repeatable read
 *         or a nonrepeatable read.
 *  Second round:
 *     The first thread does ops and commits (expects a successful commit).
 *     The other thread does ops that either conflict or not and will
 *         attempt a commit, expecting either success or failure 
 *         depending on whether the ops created a conflict or not.
 *
 *  @param readOps The list of operations done by round 1, thread 2.
 *  @param t1_ops The List of operations done by round 2, thread 1.
 *  @param newOps The list of operations done by round 2, thread 2.
 *  @param readOpsContainsRR True if the readOps contained a
 *         repeatable read, false otherwise. 
 *
 *  @returns True if the commit is expected to succeed for this thread,
 *           false otherwise.
 */
protected boolean getCommitStatusRR(OpList readOps, OpList t1_ops, OpList newOps, boolean readOpsContainsRR) {
   // if the new ops are writes and they conflict with a t1_op that is a write
   // AND it uses the same region and key as a readOp and the readOp was repeatable
   // then the commit will fail.
   for (int newOpIndex = 0; newOpIndex < newOps.numOps(); newOpIndex++) {
      Operation newOp = newOps.getOperation(newOpIndex);
      for (int t1_opIndex = 0; t1_opIndex < newOps.numOps(); t1_opIndex++) {
         Operation t1_op = t1_ops.getOperation(t1_opIndex);
         for (int readOpIndex = 0; readOpIndex < readOps.numOps(); readOpIndex++) {
            Operation readOp = readOps.getOperation(readOpIndex);
            boolean newOpIsWrite = newOp.isWriteOperation();
            boolean t1_opIsWrite = t1_op.isWriteOperation();
            boolean readOpIsRepeatable = readOp.isRepeatableRead();
            boolean newOpSameKeyAsT1 = newOp.usesSameRegionAndKey(t1_op);
            boolean newOpSameKeyAsRead = newOp.usesSameRegionAndKey(readOp);
            if (!newOpSameKeyAsRead) { // the readOp could be an op that iterates over the region
               if ((newOp.usesSameRegion(readOp)) && (readOp.isRepeatableRead()) && (readOp.isRegionSetOperation())) {
                  newOpSameKeyAsRead = true;
               }
            } 
            boolean t1_opIsDoubleInvalidate = t1_op.isDoubleInvalidate();
            boolean t1_opIsEntryCreate = t1_op.isEntryCreate();
            Log.getLogWriter().info("newOpIsWrite: " + newOpIsWrite + ", t1_opIsWrite: " + t1_opIsWrite +
                ", newOpSameKeyAsT1: " + newOpSameKeyAsT1 + ", newOpSameKeyAsRead: " + newOpSameKeyAsRead +
                ", readOpIsRepeatable: " + readOpIsRepeatable + ", t1_opIsDoubleInvalidate: " + t1_opIsDoubleInvalidate +
                ", t1_opIsEntryCreate: " + t1_opIsEntryCreate);

            // if repeatableReadOp was a get/load and t1 is a write op using same region/key
            // we could have a conflict (depending on configuration)
            if (t1_opIsWrite && readOp.isGetWithLoad() && readOp.usesSameRegionAndKey(t1_op)) {
               if (t1_opIsDoubleInvalidate) {
                  // no conflict because this was a noop
               } else {
                  return false;     // expect conflict
               }
            }

            if (newOpIsWrite && t1_opIsWrite && newOpSameKeyAsT1 && newOpSameKeyAsRead &&
               readOpIsRepeatable) {
               if (t1_opIsDoubleInvalidate) { 
                  // no conflict because this was a noop 
               } else if (t1_opIsEntryCreate) { 
                  if (testConfiguration == ONE_THREAD_PER_MULTI_VMS) {
                     if (readOp.getOldValue() != null) {
                        // t1 created the key in its own vm, but the key existed
                        // for the readOp in this vm, so this will cause a conflict
                        return false;
                     } else {
                        // t1 is a create but it didn't exist in the readop vm either
                        // no conflict
                     }
                  } else {
                     // no conflict because this did not previously exist; if the
                     // read op was something like keys() that iterated the entire
                     // region contents, this key did not exist then, so no conflict later
                  }
               } else {
                  return false;
               }
            }
         }
      }
   }
   return true;
}

/** Determines if reads see the correct value.
 *  First round:
 *     The first thread in the round does a begin
 *     The other thread in the round does a begin and either a repeatable read
 *         or a nonrepeatable read.
 *  Second round:
 *     The first thread does ops and commites (expects a successful commit).
 *     The other thread does ops that either conflict or not and will
 *         attempt a commit, expecting either success or failure 
 *         depending on whether the ops created a conflict or not.
 *
 *  @param readOps The list of operations done by round 1, thread 2.
 *  @param t1_ops The List of operations done by round 2, thread 1.
 *  @param newOps The list of operations done by round 2, thread 2.
 *  @param readOpsContainsRR True if the readOps contained a
 *         repeatable read, false otherwise. 
 *
 *  @returns True if the commit is expected to succeed for this thread,
 *           false otherwise.
 */
protected void verifyRepeatableReads(OpList readOps, 
                                     OpList t1_ops, 
                                     OpList newOps, 
                                     boolean readOpsContainsRR) {
   for (int newOpIndex = 0; newOpIndex < newOps.numOps(); newOpIndex++) {
      Operation newOp = newOps.getOperation(newOpIndex);
      for (int readOpIndex = 0; readOpIndex < readOps.numOps(); readOpIndex++) {
         Operation readOp = readOps.getOperation(readOpIndex);
         if (readOp.usesSameRegionAndKey(readOp)) {
            if (newOp.isRepeatableRead()) {
               if (newOp.getNewValue().equals(readOp.getNewValue())) {
                  // ok
               } else {
                  throw new TestException("Read op " + readOp + " and newOp " + newOp +
                        " should have read the same value for repeatable reads, but " + 
                        "readOp value is " + readOp.getNewValue() + " and newOp value is " +
                        newOp.getNewValue());
               }
            }
         }
      }
   }
}

/** This writes expected commit status to the blackboard for tests that 
 *  run either in or out of a transaction, and use both transactional and
 *  non-transactional operations. This test is configured for only two threads.
 *  As a result, two keys in the blackboard shared map are written to 
 *  indicated the expected commit success/failure of each of the two thread:
 *  TxBB.FirstInRoundCommitStatus and TxBB.SecondInRoundCommitStatus.
 *
 *  @param inTrans True if the second thread is in a transaction, false
 *                 otherwise. The first thread is always in a transaction.
 *  @param priorOps The list of operations done by the first thread.
 *  @param newOps The list of operations done by the second thread (has
 *                only one op).
 */
protected void recordCommitStatus(boolean inTrans, OpList priorOps, OpList newOps) {
   hydra.blackboard.SharedMap sm = TxBB.getBB().getSharedMap();

   // check for configurations that don't ever cause conflicts 
   if (newOps == null) { // no ops were done
      sm.put(TxBB.FirstInRoundCommitStatus, new Boolean(true));
      if (inTrans)
         sm.put(TxBB.SecondInRoundCommitStatus, new Boolean(true));
      else
         sm.put(TxBB.SecondInRoundCommitStatus, NOT_IN_TRANS);
      Log.getLogWriter().info("No new ops, inTrans is " + inTrans +
         ", TxBB.FirstInRoundCommitStatus = " + sm.get(TxBB.FirstInRoundCommitStatus) +
         ", TxBB.SecondInRoundCommitStatus = " + sm.get(TxBB.SecondInRoundCommitStatus));
      return;
   } 

   boolean thread1Conflicts = thread1HasConflict(priorOps, newOps, inTrans);
   if (thread1Conflicts) { // thread 1 fails; thread2 will always succeed if in a transaction
      sm.put(TxBB.FirstInRoundCommitStatus, new Boolean(false));
      if (inTrans) // thread2 is in a transaction
         sm.put(TxBB.SecondInRoundCommitStatus, new Boolean(true));
      else // thread 2 not in a transaction
         sm.put(TxBB.SecondInRoundCommitStatus, NOT_IN_TRANS);
   } else { // thread1 will succeed; check thread2
      sm.put(TxBB.FirstInRoundCommitStatus, new Boolean(true));
      if (inTrans) { // thread2 is in a transaction; see if it can succeed
         boolean thread2Conflicts = thread2HasConflict(priorOps, newOps);
         if (thread2Conflicts) {
            sm.put(TxBB.SecondInRoundCommitStatus, new Boolean(false));
         } else {
            sm.put(TxBB.SecondInRoundCommitStatus, new Boolean(true));
         }
      } else { // thread2 not in a transaction
         sm.put(TxBB.SecondInRoundCommitStatus, NOT_IN_TRANS);
      }
   }
   Log.getLogWriter().info("inTrans is " + inTrans +
       ", TxBB.FirstInRoundCommitStatus = " + sm.get(TxBB.FirstInRoundCommitStatus) +
       ", TxBB.SecondInRoundCommitStatus = " + sm.get(TxBB.SecondInRoundCommitStatus));
}

/** Assuming that 
 *     1) thread1 did the operations in opList1 (always in a transaction)
 *     2) thread2 did the operations in opList2
 *  determine if thread1 would have a conflict if it tried to commit first.
 *
 *  @param opList1 The list of operations done by thread1.
 *  @param opList2 The list of operations done by thread2.
 *  @param inTrans True if opList2 was done in a transaction, false otherwise.
 *
 *  @return True if thread1 will have a conflict (because of opList2) if it 
 *          tries to commit, false otherwise.
 */
protected boolean thread1HasConflict(OpList opList1, OpList opList2, boolean inTrans) {
   StringBuffer logStr = new StringBuffer();
   logStr.append("In thread1HasConflict: test configuration is " + testConfigurationToString() +
          "\nopList from thread1: " + opList1 + 
          "\nopList from thread2, inTrans is " + inTrans + ": " + opList2);
   for (int list1Index = 0; list1Index < opList1.numOps(); list1Index++) {
      Operation op1 = opList1.getOperation(list1Index);
      for (int list2Index = 0; list2Index < opList2.numOps(); list2Index++) {
         Operation op2 = opList2.getOperation(list2Index);
         boolean hasConflict = op1HasConflict(op1, op2, inTrans, logStr);
         if (hasConflict) {
            Log.getLogWriter().info(logStr + "\nThread1 has conflict");
            return true;
         }
      }
   }
   Log.getLogWriter().info(logStr + "\nThread1 does NOT have conflict");
   return false;
}

/** Assuming that 
 *     1) thread1 did op1 (always in a transaction)
 *     2) thread2 did op2
 *  determine if thread1 would have a conflict if it tried to commit first.
 *
 *  @param op1 The operation done by thread1.
 *  @param op2 The operation done by thread2.
 *  @param op2InTrans True if op2 was done in a transaction, false otherwise.
 *  @param logStr A buffer to append any logging to.
 *
 *  @return True if thread1 will have a conflict (because of op2) if it 
 *          tries to commit, false otherwise.
 */
protected boolean op1HasConflict(Operation op1, Operation op2, boolean op2InTrans, StringBuffer logStr) {
   String op1Name = op1.getOpName();
   String op2Name = op2.getOpName();

   // see if the two ops use same regions and keys
   if (!op1.affectedBy(op2)) {
      logStr.append("\n  op2 (" + op2Name + ") does not affect op1 (" + op1Name + ")");
      return false;
   }

   // see if ops are a noop (double invalidate)
   if (op1.isDoubleInvalidate()) {
      logStr.append("\n  op1 (" + op1Name + ") is a double invalidate and does not affect op2 (" + op2Name + ")");
      return false;
   }
   if (op2.isDoubleInvalidate()) {
      logStr.append("\n  op2 (" + op2Name + ") is a double invalidate and does not affect op1 (" + op1Name + ")");
      return false;
   }

   // 1st phase; consider op2
   boolean op2HasEffectOnOp1 = false; 
   switch (testConfiguration) {
      case ONE_THREAD_PER_MULTI_VMS: {
         if (op2InTrans) { // op2 in a transaction
            op2HasEffectOnOp1 = op2Name.equals(Operation.REGION_DESTROY) ||
                                op2Name.equals(Operation.REGION_INVAL);
         } else { // op2 not in transaction
            op2HasEffectOnOp1 = op2Name.equals(Operation.ENTRY_UPDATE) ||
                                op2Name.equals(Operation.ENTRY_DESTROY) ||
                                op2Name.equals(Operation.ENTRY_INVAL) ||
                                op2Name.equals(Operation.REGION_DESTROY) ||
                                op2Name.equals(Operation.REGION_INVAL) ||
                                ( (op2Name.equals(Operation.ENTRY_CREATE) || op2.isGetWithLoad()) && 
                                  op1.usesSameRegionAndKey(op2) &&
                                  op1.isWriteOperation() 
                                  // a create (by op2) when the first thread has the same 
                                  // entry defined acts like a put in that the created value 
                                  // is distributed to the first thread
                                ) ||
                                ( (op2Name.equals(Operation.ENTRY_CREATE) || op2.isGetWithLoad()) && 
                                  (op1.getRegionName() != null)
                                  // op2 is a create and region from op1 is replicated
                                );
         }
         break;
      }

      case MULTI_THREADS_PER_ONE_VM: {
         if (op2InTrans) { // thread2 in a transaction
            op2HasEffectOnOp1 = op2Name.equals(Operation.REGION_DESTROY)       ||
                                op2Name.equals(Operation.REGION_LOCAL_DESTROY) ||
                                op2Name.equals(Operation.REGION_INVAL)         ||
                                op2Name.equals(Operation.REGION_LOCAL_INVAL);
         } else { // op2 not in transaction
            op2HasEffectOnOp1 = !op2Name.equals(Operation.REGION_CREATE) &&
                                (op2.isWriteOperation() && op2.usesSameRegionAndKey(op1));
         }
         break;
      }

      default: {
         throw new TestException("Unknown testConfiguration: " + testConfiguration);
      }
   }
   logStr.append("\n  op2 (" + op2Name + ") has immediate effect on op1 (" + op1Name + "): " + op2HasEffectOnOp1);

   // 2nd phase; consider op1
   boolean causesConflict = op2HasEffectOnOp1;
   if (op2HasEffectOnOp1) { // t1 might fail if committed
      if (op1.isEntryCreate() && op2.isEntryCreate()) {
         // check for special case of both operations creating the same entry
         boolean createdSameEntry = op1.getRegionName().equals(op2.getRegionName()) &&
                                    op1.getKey().equals(op2.getKey());
         if (!op1.usesSameRegionAndKey(op2))
            causesConflict = false;
      } else if (op2.isDoubleInvalidate()) { // op2 is a noop
         causesConflict = false;
      } else if (op2.isInvalidate() && (op1.isEntryCreate() ||
                                        op1.isPreviouslyInvalid())) { // not affected by op2 invalidate
         causesConflict = false;
      } else if (op1.isGetOperation()) { 
         // gets which result in a load can now cause conflicts (except w/Proxy Regions)
         if (op1.isGetWithLoad()) {
            causesConflict = true;
         } else {
            causesConflict = false;
         }
      } else if (op1.isRegionOperation()) { // is immediate region op; no conflict
         causesConflict = false;
      }
      // if op1 (txVm) is an empty region, we cannot detect a conflict (no local storage/identity)
      if (clientIsEmpty("client1")) {
         causesConflict = false;
      }
   } 
   logStr.append("\n    thread1HasConflict (if it commits first): " + causesConflict);
   return causesConflict;
}

/** 
 *  Determines if given client has overridden dataPolicy (using TxPrms-viewDataPolicies) with 
 *  DataPolicy.EMPTY.
 *
 *  @param clientName (for proxySerialConflict tests, 'client1' or 'client2')
 *  @returns true if the client with the clientName provided has a DataPolicy of EMPTY,
 *           false otherwise.
 *
 */
protected boolean clientIsEmpty(String clientName) {
   // View tests & proxySerialConflict tests require overwriting the
   // dataPolicy to allow specific combinations betweeen clients
   String mapKey = TxBB.DataPolicyPrefix + clientName;
   String dataPolicy = (String)(TxBB.getBB().getSharedMap().get(mapKey));
   boolean isEmpty = false;
   if (dataPolicy != null) {
      isEmpty = dataPolicy.equalsIgnoreCase("empty");
   }
   String s = (isEmpty) ? " " : " not ";
   Log.getLogWriter().info(clientName + " dataPolicy is" + s + "empty");
   return isEmpty;
}

/** Assuming that 
 *     1) thread1 did the operations in opList1 (always in a transaction)
 *     2) thread2 did the operations in opList2 and is in a transaction
 *  determine if thread2 would have a conflict if it tried to commit,
 *  assuming that thread1 successfully committed first.
 *
 *  @param opList1 The list of operations done by thread1.
 *  @param opList2 The list of operations done by thread2.
 *
 *  @return True if thread2 will have a conflict (because of the operations in
 *          opList1) if it tries to commit after thread1 successfuly commits, 
 *          false otherwise.
 */
protected boolean thread2HasConflict(OpList opList1, OpList opList2) {
   StringBuffer logStr = new StringBuffer();
   logStr.append("In thread2HasConflict: test configuration is " + testConfigurationToString() +
          "\nopList from thread1: " + opList1 + 
          "\nopList from thread2: " + opList2);
   for (int list1Index = 0; list1Index < opList1.numOps(); list1Index++) {
      Operation op1 = opList1.getOperation(list1Index);
      for (int list2Index = 0; list2Index < opList2.numOps(); list2Index++) {
         Operation op2 = opList2.getOperation(list2Index);
         boolean hasConflict = op2HasConflict(op1, op2, logStr);
         if (hasConflict) {
            Log.getLogWriter().info(logStr + "\nThread2 has conflict");
            return true;
         }
      }
   }
   Log.getLogWriter().info(logStr + "\nThread2 does NOT have conflict");
   return false;
}

/** Assuming that 
 *     1) thread1 did op1 
 *     2) thread2 did op2 
 *  determine if thread2 would have a conflict if it tried to commit,
 *  assuming that thread1 successfully committed first.
 *
 *  @param op1 The operation done by thread1.
 *  @param op2 The operation done by thread2.
 *  @param logStr A buffer to append any logging to.
 *
 *  @return True if thread2 will have a conflict (because of the op1)
 *          if it tries to commit after thread1 successfuly commits, 
 *          false otherwise.
 */
protected boolean op2HasConflict(Operation op1, Operation op2, StringBuffer logStr) {
   String op1Name = op1.getOpName();
   String op2Name = op2.getOpName();

   // see if the two ops use same regions and keys
   if (!op2.affectedBy(op1)) {
      logStr.append("\n  op1 (" + op1Name + ") does not affect op2 (" + op2Name + ")");
      return false;
   }

   // see if op1 or op2 are a noops (double invalidates)
   if (op1.isDoubleInvalidate()) {
      logStr.append("\n  op1 (" + op1Name + ") is a double invalidate and does not affect op2 (" + op2Name + ")");
      return false;
   }

/* todo@lhughes -- resolve this question
 * I think this is incorrect ... 
 *   if op commits and op2 was attempting to invalidate, we'll have a conflict
   if (op2.isDoubleInvalidate()) {
      logStr.append("\n  op2 (" + op2Name + ") is a double invalidate and does not affect op1 (" + op1Name + ")");
      return false;
   }
*/

   // 1st phase; consider op1
   boolean op1HasEffectOnOp2 = false; 
   switch (testConfiguration) {
      case ONE_THREAD_PER_MULTI_VMS: {

         op1HasEffectOnOp2 = ( op1Name.equals(Operation.ENTRY_CREATE) &&
                               op1.usesSameRegionAndKey(op2) &&
                               !op2.isGetOperation() &&
                               !op2Name.equals(Operation.ENTRY_CREATE)
                                  // a create (by op1) when the second thread has the same 
                                  // entry defined acts like a put in that the created value 
                                  // is distributed to the second thread
                             ) ||
                             ( op1.isGetWithLoad() &&
                               op1.usesSameRegionAndKey(op2) &&
                               op2.isWriteOperation() && 
                               !op2Name.equals(Operation.ENTRY_CREATE)
                                  // loads can now cause conflicts 
                             ) ||
                             ( ( op1Name.equals(Operation.ENTRY_UPDATE)  ||
                                 op1Name.equals(Operation.ENTRY_DESTROY) ||
                                 op1Name.equals(Operation.ENTRY_INVAL) 
                                ) && 
                                (!op2Name.equals(Operation.ENTRY_CREATE))
                                // op1 will affect op2 if it distributes; if op2 is a create
                                // it means the key was not defined for op2's VM and since
                                // there is no replication, the distributed op1 will not be
                                // distributed to op2's VM
                             ) ||
                             (  ((op1Name.equals(Operation.ENTRY_CREATE) || op1.isGetWithLoad()) &&
                                  op2.isWriteOperation())
                                // op1 is a create (or getWithLoad) and region is distributed
                             );
         break;
      }

      case MULTI_THREADS_PER_ONE_VM: {
         op1HasEffectOnOp2 = op1.isWriteOperation();
         break;
      }

      default: {
         throw new TestException("Unknown testConfiguration: " + testConfiguration);
      }
   }
   logStr.append("\n  op1 (" + op1Name + ") has an effect on op2 (" + op2Name + "): " + op1HasEffectOnOp2);

   // 2nd phase; consider op2
   boolean causesConflict = op1HasEffectOnOp2;
   if (op1HasEffectOnOp2) { // t2 might fail if committed
      if (op1.isEntryCreate() && op2.isEntryCreate()) {
         // check for special case of both operations creating the same entry
         if (!op1.usesSameRegionAndKey(op2))
            causesConflict = false;
      } else if (op1.isDoubleInvalidate()) { // op1 is a noop
         causesConflict = false;
      } else if (op1.isInvalidate() && op2.isEntryCreate()) { // create not affected by invalidate
         causesConflict = false;
      } else if (op2.isGetOperation()) { // gets can always commit
         if (op2.isGetWithLoad()) {
            causesConflict = true;
         } else {
            causesConflict = false;
         }
      } else if (op2.isRegionOperation()) { // is immediate region op; no conflict
         causesConflict = false;
      } else if (testConfiguration == ONE_THREAD_PER_MULTI_VMS) {
         // check for a tricky case of double invalidate; this can only happen when the threads
         // are in different VMs. If op1 did a distributed invalidate and op2 did something on 
         // the same entry but the previous value was already invalidated, then when op1 was
         // committed and distributed, it was like a noop for t2 (because it was already INVALID,
         // not LOCAL_INVALID). If the old value for op2 is LOCAL_INVALID, then we do have a conflict
         // which is new behavior for Congo (the release after 4.1.2)
         if (op1.isEntryInvalidate() && op2.isEntryOperation()) { // both are entry operations
            if ((op1.usesSameRegionAndKey(op2)) && (op2.isPreviouslyInvalid()) &&
                (op2.getOldValue().toString().equals("INVALID"))) { 
               // when t2 received the distributed invalidate, it was a noop
               causesConflict = false;
            }
         }
      } else if (
                   ((testConfiguration == MULTI_THREADS_PER_ONE_VM) && (op1.isEntryDestroy()))
                    // all threads in one VM and any destroy
                ||
                   ((testConfiguration == ONE_THREAD_PER_MULTI_VMS) && 
                    (op1.getOpName().equals(Operation.ENTRY_DESTROY)))
                    // one thread in many VMs and distributed destroy
                 ) {
         if (op2.getOpName().equals(Operation.ENTRY_CREATE)) { 
             // result of op1 is key destroyed (key not there); op2 began with
             // the key not there (because op2 is a create); when op2 did the 
             // commit, it saw that op1 did not change anything (key not there
             // after op1 committed)
            causesConflict = false;
         }
      }
      // if client2 has empty data policy (overridden by TxPrms.viewDataPolicies)
      // we cannot detect a conflict (because there is no local storage/identity)
      if (clientIsEmpty("client2")) {
         causesConflict = false;
      } 
   }
   logStr.append("\n    thread2HasConflict (if it commits after thread1): " + causesConflict);
   return causesConflict;
}

// ======================================================================== 
// other methods

/** Given a list of prior ops (done in previous and guaranteed to commit tx)
 *  and a list of ops done in the current transactions, determine if the
 *  new ops override any previous ops (in a last-one-wins fashion), and
 *  modify the priorOps with either replacing a priorOp with a new op
 *  or just adding the new op.
 *
 *  @param priorOps The operations done (and guaranteed to commit) by
 *         previous transactions.
 *  @param newOps The operations done by the current tx and may or may not
 *         successfully commit.
 */
protected void recordNewOps(OpList priorOps, OpList newOps) {
   for (int newOpsIndex = 0; newOpsIndex < newOps.numOps(); newOpsIndex++) {
      Operation newOp = newOps.getOperation(newOpsIndex);
      boolean replaced = false;
      for (int priorOpsIndex = 0; priorOpsIndex < priorOps.numOps(); priorOpsIndex++) {
         Operation priorOp = priorOps.getOperation(priorOpsIndex);
         boolean isAffectedBy = priorOp.affectedBy(newOp);
         boolean isSameRegAndKey = newOp.usesSameRegionAndKey(priorOp);
         if (isAffectedBy && isSameRegAndKey) {
            boolean needToOverride = true;
            if (newOp.isDoubleInvalidate()) {
               needToOverride = false;
            } else if (newOp.isGetOperation()) {
               boolean loaderWasInvoked = (newOp.getOldValue() == null) || (newOp.isPreviouslyInvalid());
               if (loaderWasInvoked) {
                  // if a loader was invoked, it acts like a put in that the value is distributed to any
                  // other VM that has the key defined; if the prior op is a destroy, then don't override
                  // since when the destroy is distributed, the get cannot "erase" it because the get will
                  // not be distributed to VMs that have no key (it was destroyed)
                  if (priorOp.isEntryDestroy() && (testConfiguration == ONE_THREAD_PER_MULTI_VMS)) {
                     needToOverride = false;
                  }
               } else { // loader was NOT invoked
                  // if the loader was not invoked (ie a value was already there so the get did not 
                  // need to do any work), then don't override
                  needToOverride = false;
               }
            } 

            if (needToOverride) {
               // newOp "overrides" the priorOp in a last-one-wins situation
               // replace the priorOp with the newOp
               Object previousOp = priorOps.set(priorOpsIndex, newOp);
               replaced = true;
               Log.getLogWriter().info("Replacing priorOp " + priorOp + " with new op " + newOp);
               break;
            }
         }
      }
      if (!replaced) {
         Log.getLogWriter().info("Adding new op " + newOp);
         priorOps.add(newOp);
      }
   }
   Log.getLogWriter().info("Recording ops to blackboard: " + priorOps);
   TxBB.getBB().getSharedMap().put(TxBB.OpListKey, priorOps);
}

/** Check that the current value of the tx set for this thread (the set of
 *  keys that have been read or written during this tx) matches the set of
 *  keys specified in the oplist.
 */
protected void checkTxSet(OpList ops) {
   // get a set of all regions in the test
   Set rootRegions = CacheUtil.getCache().rootRegions();
   Set allRegions = new HashSet();
   Iterator it = rootRegions.iterator();
   while (it.hasNext()) {
      Region aRegion = (Region)(it.next());
      allRegions.add(aRegion);
      allRegions.addAll(aRegion.subregions(true));
   }
      
   // look for problems in the tx set for each region
   StringBuffer aStr = new StringBuffer();
   it = allRegions.iterator();
   while (it.hasNext()) {
      Region aRegion = (Region)(it.next());
      // create a new HashSet so the remove operation will work
      Set txSet = new HashSet(((LocalRegion)aRegion).testHookTXKeys());
      Log.getLogWriter().info("Checking txSet for region " + aRegion.getFullPath() +
          ": " + txSet + ", with opList: " + ops);
      Set opListKeySet = new HashSet();
      for (int i = 0; i < ops.numOps(); i++) {
         Operation op = ops.getOperation(i);
         if (op.getRegionName().equals(aRegion.getFullPath())) {
            opListKeySet.add(op.getKey());
         }
      }
      Set missingInTxSet = new HashSet(opListKeySet);
      Set extraInTxSet = new HashSet(txSet);
      missingInTxSet.removeAll(txSet);
      extraInTxSet.removeAll(opListKeySet);
      
      if (missingInTxSet.size() != 0) {
         aStr.append("The following keys " + missingInTxSet + " were missing from the tx set for " + 
                     aRegion.getFullPath() + ", but were found in the OpList: " + ops);
      }
      if (extraInTxSet.size() != 0) {
         aStr.append("The following keys " + extraInTxSet + " were extra in the tx set for " + 
                     aRegion.getFullPath() + ", but were NOT found in the OpList: " + ops);
      }
   }
   if (aStr.length() != 0) {
      throw new TestException(aStr.toString());
   }
}

/** Log the tx set for this thread (the set of keys that have been read or 
 *  written during this tx) matches the set of keys specified in the oplist.
 */
protected void logTxSet() {
   // get a set of all regions in the test
   Set rootRegions = CacheUtil.getCache().rootRegions();
   Set allRegions = new HashSet();
   Iterator it = rootRegions.iterator();
   while (it.hasNext()) {
      Region aRegion = (Region)(it.next());
      allRegions.add(aRegion);
      allRegions.addAll(aRegion.subregions(true));
   }
      
   StringBuffer aStr = new StringBuffer();
   it = allRegions.iterator();
   while (it.hasNext()) {
      Region aRegion = (Region)(it.next());
      Set txSet = new HashSet(((LocalRegion)aRegion).testHookTXKeys());
      if (txSet.size() != 0) {
         aStr.append("For " + aRegion.getFullPath() + " tx set is " + txSet + "\n");
      }
   }
   if (aStr.length() == 0) {
      Log.getLogWriter().info("All regions have an empty tx set");
   } else {
      Log.getLogWriter().info(aStr.toString());
   }
}

/** Return a deciscion on whether or not this configuration supports the 2nd
 *  (conflicting thread) running in a tx.  This method just uses the nextBoolean
 *  to determine, but this can be method can be overridden to provide different
 *  behavior.
 *  
 *  For example, with PartitionedRegions, we cannot target a thread in a remote VM, 
 *  so there is no way to create a conflict if both threads are transactional (because
 *  their keySets won't intersect).  (So, we'll override this method in PRConflictTest.java.)
 */
 protected boolean executeInTx() {
   boolean useTx = false;
   if (TestConfig.tab().getRandGen().nextBoolean()) {
     useTx = true;
   }
   return useTx;
}

// ======================================================================== 
// string methods

/** Return a string representation of the test configuration */
public String toString() {
   return "testConfiguration: " + testConfigurationToString();
}

/** Return a string representation of the test configuration */
protected String testConfigurationToString() {
   switch (testConfiguration) {
      case ONE_THREAD_PER_MULTI_VMS:   return "OneThreadPerMultiVMs";
      case MULTI_THREADS_PER_ONE_VM:   return "MultiThreadsPerOneVM";
      case UNDEFINED:                  return "Undefined";
      default: {
         throw new TestException("Unknown testConfiguration: " + testConfiguration);
      }
   }
}

}
