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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.ServerConnectivityException;
import com.gemstone.gemfire.cache.execute.Execution;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionInvocationTargetException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import hydra.*;
import hydra.blackboard.Blackboard;
import hydra.blackboard.SharedMap;
import tx.TxPrms;
import util.*;

/**
 * A class to test resumeable transactions.
 *
 * @author Lynn Hughes-Godfrey
 * @since 6.6
 */
public class ResumeTxTest {

// single instance of this test class
static public ResumeTxTest testInstance = null;

private boolean isBridgeClient = false;
private boolean isHA = false;

protected long minTaskGranularitySec; // the task granularity in seconds
protected long minTaskGranularityMS;  // the task granularity in milliseconds

// Tx actions (for concurrent resumable transactions with function execution)
static protected final int BEGIN_TX          = 1001;
static protected final int EXECUTE_TX_OPS    = 1002;
static protected final int EXECUTE_NONTX_OPS = 1003;
static protected final int COMMIT            = 1004;
static protected final int ROLLBACK          = 1005;

// ======================================================================== 
// hydra tasks
// ======================================================================== 

public static void StartTask_initialize() {
   RegionDescription rd = RegionHelper.getRegionDescription(ConfigPrms.getRegionConfig());
   ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.TX_HOST_DATAPOLICY, rd.getDataPolicy());
}

public synchronized static void HydraTask_initialize() {
   if (testInstance == null) {
      testInstance = new ResumeTxTest();
      testInstance.initialize();
   }
}

protected void initialize() {
     isHA = ResumeTxPrms.getHighAvailability();
     minTaskGranularitySec = TestConfig.tab().longAt(TestHelperPrms.minTaskGranularitySec, 10);
     minTaskGranularityMS = minTaskGranularitySec * TestHelper.SEC_MILLI_FACTOR;
     ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.ACTIVE_TXNS, new HashMap());
     Log.getLogWriter().info("testInstance = " + testInstance.toString());
}

/** Hydratask for concurrent resumeable transaction with function execution tests */
public static void HydraTask_concTxWithFE() throws Exception {
   testInstance.concTxWithFE();
}

protected void concTxWithFE() throws Exception {
   long startTime = System.currentTimeMillis();
   RtxUtil.logExecutionNumber();

   do {
      // check for any posted Exceptions
      String errStr = (String)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ERRSTR);
      if (errStr != null && errStr.length() > 0) {
         throw new TestException(errStr + " " + TestHelper.getStackTrace());
      }

      try {
         doTransactions();
      } catch (ServerConnectivityException e) {
         if (!isHA) {
            throw new TestException(e + TestHelper.getStackTrace(e));
         } else {
            Log.getLogWriter().info("Caught " + e + " while executing txOps.  Expected with HA, continuing test");
         }
      } catch (FunctionException e) {
         Throwable causedBy = e.getCause();
         Log.getLogWriter().info("causedBy = " + causedBy);
         Throwable lastCause = TestHelper.getLastCausedBy(e);
         Log.getLogWriter().info("lastCause = " + lastCause);
         if (causedBy instanceof ConflictException) {
            Log.getLogWriter().info("Caught " + e + ", expected with concurrent execution, continuing test");
         } else if ((causedBy instanceof TransactionDataRebalancedException) ||
                    (causedBy instanceof TransactionDataNodeHasDepartedException) ||
                    (causedBy instanceof TransactionInDoubtException) || 
                    (causedBy instanceof ServerConnectivityException)) {
            if (isHA) {
               Log.getLogWriter().info("Caught " + e + ", expected with HA, continuing test");
            } else {
               throw new TestException("Caught " + e + " " + TestHelper.getStackTrace(e));
            }
         } else if ((e instanceof FunctionInvocationTargetException) ||
                   (causedBy instanceof FunctionInvocationTargetException) ||
                   (causedBy instanceof TransactionDataRebalancedException) ||
                   (lastCause instanceof FunctionInvocationTargetException) ||
                   (lastCause instanceof TransactionException)) {
            if (!isHA) {
               throw new TestException(e + TestHelper.getStackTrace(e));
            } else {
               Log.getLogWriter().info("Caught " + e + " while executing txOps.  Expected with HA, continuing test");
            }
         } else {  // TestExceptions and other Unexpected Exceptions
            throw new TestException(e + TestHelper.getStackTrace(e));
         }
      }  // catch FunctionException block
   } while (System.currentTimeMillis() - startTime < minTaskGranularityMS);
}

/**
 *  Based on ResumeTxBB ACTIVE_TXNs map, execute being, tx ops or commit.
 *  Map consists of the key (txId) + the number of times we resumed this tx (EXECUTE_TX_OPS).
 *  Entries are removed when the transaction is committed or rolled back.
 */
public void doTransactions() throws Exception {
   GsRandom rand = TestConfig.tab().getRandGen(); 
   int minExecutions = ResumeTxPrms.getMinExecutions();

   TxInfo txInfo = null;
   TransactionId txId = null;
   int action = 0;

   // coordinate access among VMs
   Blackboard bb = ResumeTxBB.getBB();
   SharedMap bbMap = bb.getSharedMap();
   bb.getSharedLock().lock();

   // map of active transactions with key = txId, value = numExecutions
   Map activeTxnsWrapped = (Map)bbMap.get(ResumeTxBB.ACTIVE_TXNS);
   Map activeTxns = RtxUtilVersionHelper.convertWrapperMapToActiveTxMap(activeTxnsWrapped);
   Object[] txIds = activeTxns.keySet().toArray();
   Log.getLogWriter().fine("before update " + activeTxnsToString(activeTxns));

   // keep all threads with this task actively doing transactions
   int numThreads = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   int numActiveTxns = activeTxns.size();
   // determine action and txId, release BB lock as soon as possible (to allow more concurrency)
   if (numActiveTxns < numThreads) {
        action = BEGIN_TX;
   } else {
      for (int i = 0; i < txIds.length; i++) {
         txId = (TransactionId)txIds[i];
         txInfo = (TxInfo)activeTxns.get(txId);
         int numExecutions = txInfo.getNumExecutions();
         if (numExecutions > minExecutions) {
            // todo@lhughes - add some percentage of rollbacks here
            if (TestConfig.tab().getRandGen().nextBoolean()) {
               action = COMMIT;
               Log.getLogWriter().info("doTransactions: commit(" + txId + ")");
            } else {
               action = ROLLBACK;
               Log.getLogWriter().info("doTransactions: rollback(" + txId + ")");
            }
            break;
         } 
      }
      // if we haven't set anything, select a tx for adding more txOps
      if (action == 0) {
         action = EXECUTE_TX_OPS;
         int randInt = TestConfig.tab().getRandGen().nextInt(0, txIds.length-1);
         txId = (TransactionId)txIds[randInt];
         txInfo = (TxInfo)activeTxns.get(txId);
         Log.getLogWriter().info("doTransactions: executeTxOps(" + txId + ")");
      }
   }
   bb.getSharedLock().unlock();

   // no locks held during actual resumeable tx execution 
   boolean success = true;
   try {
      switch (action) {
         case (BEGIN_TX):
            Log.getLogWriter().info("doTransactions: begin()");
            txInfo = begin();      
            break;
         case (COMMIT):
            success = commit(txInfo);
            break;
         case (ROLLBACK):
            success = rollback(txInfo);
            break;
         case (EXECUTE_TX_OPS):
            success = executeTxOps(txInfo);
            break;
         default:  
            throw new TestException("Unrecognized action " + action);
      }
   } catch (Exception e) {
      throw e;
   }

   if (success) {
      txId = txInfo.getTxId();

      bb.getSharedLock().lock();
      //activeTxns = (Map)bbMap.get(ResumeTxBB.ACTIVE_TXNS);
      activeTxnsWrapped = (Map)bbMap.get(ResumeTxBB.ACTIVE_TXNS);
      activeTxns = RtxUtilVersionHelper.convertWrapperMapToActiveTxMap(activeTxnsWrapped);
      switch (action) {
         case (BEGIN_TX):
            activeTxns.put(txId, txInfo);
            break;
         case (COMMIT):  // deliberate fallthru to ROLLBACK
         case (ROLLBACK):  
            activeTxns.remove(txId);     
            break;
         case (EXECUTE_TX_OPS):
            txInfo = (TxInfo)activeTxns.get(txId);
            if (txInfo != null) {  // could happen if tx committed, entry removed
               txInfo.incrementNumExecutions();
               activeTxns.put(txId, txInfo);
            }
            break;
         default:  
            throw new TestException("Unrecognized action " + action);
      }
      bbMap.put(ResumeTxBB.ACTIVE_TXNS, RtxUtilVersionHelper.convertActiveTxMapToWrapperMap(activeTxns));
      Log.getLogWriter().fine("after update " + activeTxnsToString(activeTxns));
      bb.getSharedLock().unlock();
   }
}

public static void CloseTask_finishAllActiveTx() throws Exception{
   testInstance.finishAllActiveTx();
}

protected void finishAllActiveTx() throws Exception{
   boolean isSerialExecution = TestConfig.tab().booleanAt(hydra.Prms.serialExecution);
   // coordinate access among VMs
   Blackboard bb = ResumeTxBB.getBB();
   SharedMap bbMap = bb.getSharedMap();
   bb.getSharedLock().lock();

   // map of active transactions with key = txId, value = numExecutions
  // Map activeTxns = (Map)bbMap.get(ResumeTxBB.ACTIVE_TXNS);
   Map activeTxnsWrapped = (Map)bbMap.get(ResumeTxBB.ACTIVE_TXNS);
   Map activeTxns = RtxUtilVersionHelper.convertWrapperMapToActiveTxMap(activeTxnsWrapped);
   Object[] txIds = activeTxns.keySet().toArray();

   for (int i=0; i < txIds.length; i++) {
      TransactionId txId = (TransactionId)txIds[i];
      TxInfo txInfo = (TxInfo)activeTxns.get(txId);
      try {
         commit(txInfo);
      } catch (FunctionException e) {
         Throwable causedBy = e.getCause();
         if (causedBy instanceof ConflictException) {
            if (isSerialExecution) {
               throw new TestException("Caught " + e + " " + TestHelper.getStackTrace(e));
            } else {
               Log.getLogWriter().info("Caught " + e + ", expected with concurrentExecution, continuing test");
            }
         } else if ((causedBy instanceof TransactionDataRebalancedException) ||
                    (causedBy instanceof TransactionDataNodeHasDepartedException) ||
                    (causedBy instanceof TransactionInDoubtException) ||
                    (causedBy instanceof FunctionInvocationTargetException)) {
            if (isHA) {
               Log.getLogWriter().info("Caught " + e + ", expected with HA, continuing test");
            } else {
               throw new TestException("Caught " + e + " " + TestHelper.getStackTrace(e));
            }
         } else {
            throw new TestException("Caught " + e + " " + TestHelper.getStackTrace(e));
         } 
      } finally {
         activeTxns.remove(txId);
      }
   }
   bbMap.put(ResumeTxBB.ACTIVE_TXNS, RtxUtilVersionHelper.convertActiveTxMapToWrapperMap(activeTxns));
   bb.getSharedLock().unlock();
   Log.getLogWriter().fine("after update, ActiveTxns = " + activeTxns);
}

protected TxInfo begin() {
   DistributedSystem ds = CacheHelper.getCache().getDistributedSystem();
   Pool pool = PoolManager.find("brloader");
   if (pool != null) {
      isBridgeClient = true;
   }

   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());

   TxInfo txInfo = new TxInfo();
   TransactionId txId;
   Execution e;

   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
   if (txHostDataPolicy.withPartitioning()) {
      // pick a random region/key pair to target a specific dataStore
      Region aRegion = (Region)CacheHelper.getCache().rootRegions().iterator().next();
      Object[] keySet = aRegion.keySet().toArray();
      Object aKey = null;
      if (keySet.length > 0) {
         int randInt = TestConfig.tab().getRandGen().nextInt(0, keySet.length-1);
         aKey = keySet[randInt];
      } else {  // create a new key
         aKey = NameFactory.getNextPositiveObjectName();
         aRegion.put(aKey, RtxUtil.txUtilInstance.getNewValue(aKey));
      }

      Set filterSet = new HashSet();
      filterSet.add(aKey);
      e = FunctionService.onRegion(aRegion).withArgs(aList).withFilter(filterSet);
      Log.getLogWriter().info("executing function BeginTx on region " + aRegion.getName() + " withArgs " + aList + " and filterSet " + filterSet);

      txInfo.setRegionName(aRegion.getName());
      txInfo.setKey(aKey);
      txInfo.setRoutingObject(new ModRoutingObject(aKey));
   } else { // replicated
      if (isBridgeClient) {  // invoke on a single server
         e = FunctionService.onServer(pool).withArgs(aList);
         Log.getLogWriter().info("executing function BeginTx onServer( " + pool.getName() +  ") withArgs " + aList);
      } else {  // peer test, target a random member
         Object[] members = DistributedSystemHelper.getMembers().toArray();
         int randInt = TestConfig.tab().getRandGen().nextInt(0, members.length-1);
         DistributedMember targetDM = (DistributedMember)members[randInt];
         e = FunctionService.onMember(ds, targetDM).withArgs(aList);
         Log.getLogWriter().info("executing function BeginTx on member(" + targetDM + ") withArgs " + aList);
      }
   }

   ResultCollector rc = e.execute(new BeginTx());
   List resultList = (List)rc.getResult();
   txId = (TransactionId)resultList.get(0);

   txInfo.setTxId(txId);

   // for serial tests ...
   ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.TX_ACTIVE, Boolean.TRUE);
   ResumeTxBB.clearOpList();

   Log.getLogWriter().info("begin returning " + txInfo);
   return txInfo;
}

protected boolean executeTxOps(TxInfo txInfo) {

   Log.getLogWriter().fine("in executeTxOps with txInfo = " + txInfo.toString());
   boolean isSerialExecution = TestConfig.tab().booleanAt(hydra.Prms.serialExecution);
   TransactionId txId = txInfo.getTxId();

   // build args ArrayList (clientIdString, optionally add the txId)
   Pool pool = PoolManager.find("brloader");
   if (pool != null) {
      isBridgeClient = true;
   }

   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());
   aList.add(txId);

   Execution e;
   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
   if (txHostDataPolicy.withPartitioning()) {
      String regionName = txInfo.getRegionName();
      Region aRegion = RegionHelper.getRegion(regionName);
      Object aKey = txInfo.getKey();
      Set filterSet = new HashSet();
      filterSet.add(aKey);

      // target the primary using filterSet (region, key)
      e = FunctionService.onRegion(aRegion).withArgs(aList).withFilter(filterSet);
      Log.getLogWriter().info("executing function ExecuteTx on region " + aRegion.getName() + " with aList " + aList + " and filterSet " + filterSet);
   } else { // replicated
      // for peers, target the tx host via the DistributedMember from TxId
      // for servers, target all servers ... will need to allow multiple responses from ResultCollector
      // TODO: TX: redo for new TX impl
      //DistributedMember txHost = ((TXId)txId).getMemberId();
      if (isBridgeClient) {
         e = FunctionService.onServers(pool).withArgs(aList);
         Log.getLogWriter().info("executing function ExecuteTx onServers(" + pool.getName() + ") withArgs " + aList);
      } else {
        e = FunctionService.onMembers(DistributedSystemHelper.getDistributedSystem()).withArgs(aList);
        Log.getLogWriter().info("executing function ExecuteTx on all members withArgs " + aList);
        /*
         e = FunctionService.onMember(DistributedSystemHelper.getDistributedSystem(), txHost).withArgs(aList);
         Log.getLogWriter().info("executing function ExecuteTx onMember(" + txHost + ") withArgs " + aList);
         */
      }
   }

   // Our tx could fail due to rebalancing, servers being recycled, etc
   // It could also fail if none of the servers executes the function (because the
   // transaction no longer exists (if the TransactionDataHost departs))
   boolean executedOps = false;
   ResultCollector rc = e.execute(new ExecuteTx());
   List resultList = (List)rc.getResult();
   for (int i = 0; i < resultList.size(); i++) {
      boolean result = ((Boolean)resultList.get(i)).booleanValue();
      if (result) {
         if (executedOps == true && result == true) {
            throw new TestException("More than one member returned true (executedOps) from Commit Function for " + txId);
         } else {
            executedOps = true;
         }
      }
   }
   Log.getLogWriter().info("ExecuteTx executedOps = " + executedOps);
   if (!executedOps) {
      if (isSerialExecution) {
         throw new TestException(txId + " not suspended in any member, orphaned tx?");
      }
   }
   return executedOps;
}

protected boolean executeNonTxOps() {
   boolean result = true;
   Log.getLogWriter().info("executeNonTxOps() - TBD");
   Log.getLogWriter().info("executeNonTxOps returning " + result);
   return result;
}

protected boolean commit(TxInfo txInfo) {
   TransactionId txId = txInfo.getTxId();

   boolean txCompleted = true;
   // TODO: TX: redo for new impl
   /*
   // build args ArrayList (clientIdString, optionally add the txId)
   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());
   aList.add(txId);

   boolean txCompleted = false;

   Execution e;
   ResultCollector rc;

   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
   if (txHostDataPolicy.withPartitioning()) {
      String regionName = txInfo.getRegionName();
      Region aRegion = RegionHelper.getRegion(regionName);
      Object aKey = txInfo.getKey();
      Set filterSet = new HashSet();
      filterSet.add(aKey);
  
      if (isHA) {
         e = TransactionFunctionService.onTransaction(txId);
         Log.getLogWriter().info("executing function internal.cache.execute.util.CommitFunction onTransaction " + txId);
         rc = e.execute(new CommitFunction());
      } else {
         e = FunctionService.onRegion(aRegion).withArgs(aList).withFilter(filterSet);
         Log.getLogWriter().info("executing function CommitTx on region " + aRegion.getName() + " withArgs " + aList + " and filterSet " + filterSet);
         rc = e.execute(new CommitTx());
      }
   } else { // replicated
      e = TransactionFunctionService.onTransaction(txId);
      Log.getLogWriter().info("executing function internal.cache.execute.util.CommitFunction onTransaction " + txId);
      rc = e.execute(new CommitFunction());
   } 
   List resultList = (List)rc.getResult();
   for (int i = 0; i < resultList.size(); i++) {
      boolean result = ((Boolean)resultList.get(i)).booleanValue();
      if (result) {
         if (txCompleted == true && result == true) {
            throw new TestException("More than one member returned true (txCompleted) from Commit Function for " + txId);
         } else {
            txCompleted = true;
         }
      }
   }
   */
   Log.getLogWriter().info("Commit function returned (txCompleted) " + txCompleted);
   return txCompleted;
}

protected boolean rollback(TxInfo txInfo) {
   TransactionId txId = txInfo.getTxId();

   boolean txCompleted = true;
   // TODO: TX: redo for new impl
   /*
   // build args ArrayList (clientIdString, optionally add the txId)
   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());
   aList.add(txId);

   boolean txCompleted = false;

   Execution e;
   ResultCollector rc;

   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
   if (txHostDataPolicy.withPartitioning()) {
      String regionName = txInfo.getRegionName();
      Region aRegion = RegionHelper.getRegion(regionName);
      Object aKey = txInfo.getKey();
      Set filterSet = new HashSet();
      filterSet.add(aKey);
  
      if (isHA) {
         e = TransactionFunctionService.onTransaction(txId).withArgs(aList);
         Log.getLogWriter().info("executing function RollbackTx onTransaction " + txId);
         rc = e.execute(new RollbackTx());
      } else {
         e = FunctionService.onRegion(aRegion).withArgs(aList).withFilter(filterSet);
         Log.getLogWriter().info("executing function RollbackTx on region " + aRegion.getName() + " withArgs " + aList + " and filterSet " + filterSet);
         rc = e.execute(new RollbackTx());
      }
   } else { // replicated
      e = TransactionFunctionService.onTransaction(txId).withArgs(aList);
      Log.getLogWriter().info("executing function RollbackTx onTransaction " + txId);
      rc = e.execute(new RollbackTx());
   } 
   List resultList = (List)rc.getResult();
   for (int i = 0; i < resultList.size(); i++) {
      boolean result = ((Boolean)resultList.get(i)).booleanValue();
      if (result) {
         if (txCompleted == true && result == true) {
            throw new TestException("More than one member returned true (txCompleted) from Rollback Function for " + txId);
         } else {
            txCompleted = true;
         }
      }
   }
   */
   Log.getLogWriter().info("Rollback function returned (txCompleted) " + txCompleted);
   return txCompleted;
}

/** Hydratask for serial round robin test  */
public static void HydraTask_serialRRTxWithFE() throws Exception{
   testInstance.serialRRTxWithFE();
}

// ======================================================================== 
// methods that implement the test tasks

protected void serialRRTxWithFE() throws Exception {
   GsRandom rand = TestConfig.tab().getRandGen(); 
   boolean firstInRound = RtxUtil.logRoundRobinNumber();
   RtxUtil.logExecutionNumber();
   long rrNumber = RtxUtil.getRoundRobinNumber();
   long whichRound = rrNumber % 2;
   boolean isHA = ResumeTxPrms.getHighAvailability();

   TxInfo txInfo; 
   TransactionId txId = null;

   // build args ArrayList (clientIdString, optionally add the txId)
   Pool pool = PoolManager.find("brloader");
   if (pool != null) {
      isBridgeClient = true;
   }

   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());
   
   // FunctionService invocation is based on the dataPolicy of the transactional data host
   // For PartitionedRegions, we can use onRegion + withFilter
   // For ReplicatedRegions, we must use onMembers
   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);

   if (whichRound == 1) { // round to open transactions
      Log.getLogWriter().info("In round " + rrNumber + " to begin tx, firstInRound is " + firstInRound);

      if (firstInRound) { 
         Log.getLogWriter().info("This thread is first in the round, beginning the tx");

         txInfo = begin();    // filterInfo (region, key, routing object are set here for PRs)
       
         // update activeTxns map and store in blackboard
         //Map activeTxns = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
         Map activeTxnsWrapped = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
         Map activeTxns = RtxUtilVersionHelper.convertWrapperMapToActiveTxMap(activeTxnsWrapped);
         
         txId = txInfo.getTxId();
         activeTxns.put(txId, txInfo);
         ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.ACTIVE_TXNS, RtxUtilVersionHelper.convertActiveTxMapToWrapperMap(activeTxns));
         ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.TX_ACTIVE, Boolean.TRUE);
         ResumeTxBB.clearOpList();
      } else { 
         boolean txActive = ((Boolean)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_ACTIVE)).booleanValue();

         if (txActive) {
            //Map activeTxns = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
           Map activeTxnsWrapped = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
           Map activeTxns = RtxUtilVersionHelper.convertWrapperMapToActiveTxMap(activeTxnsWrapped);
            Object[] txIds = activeTxns.keySet().toArray();
            
            // For serial execution tests, there is only the one active transaction
            txInfo = (TxInfo)activeTxns.get(txIds[0]);
            txId = txInfo.getTxId();
            aList.add(txId);

            boolean executedOps;
            try {
               executedOps = executeTxOps(txInfo);
            } catch (Exception ex) {
               Log.getLogWriter().info("Caught " + ex + " while executing tx ops");
               executedOps = false;
               Throwable causedBy = ex.getCause();
               Throwable lastCause = TestHelper.getLastCausedBy(ex);
               Log.getLogWriter().info("lastCause = " + lastCause);
               if ((ex instanceof FunctionInvocationTargetException) ||
                   (causedBy instanceof FunctionInvocationTargetException) ||
                   (causedBy instanceof TransactionDataRebalancedException) ||
                   (lastCause instanceof FunctionInvocationTargetException) ||
                   (lastCause instanceof TransactionException)) {
                  if (!isHA) {
                     throw new TestException(ex + TestHelper.getStackTrace(ex));
                  } else {
                     Log.getLogWriter().info("Caught " + ex + " while executing txOps.  Expected with HA, continuing test");
                  }
               } else {  // TestExceptions and other Unexpected Exceptions
                  throw new TestException(ex + TestHelper.getStackTrace(ex));
               }
            }
            // update number of executions, update activeTxns map and store in blackboard
            txInfo.incrementNumExecutions();
            activeTxns.put(txId, txInfo);
            ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.ACTIVE_TXNS, RtxUtilVersionHelper.convertActiveTxMapToWrapperMap(activeTxns));
            ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.TX_ACTIVE, executedOps);
         }
      }
   } else { // close transactions
      boolean txActive = ((Boolean)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_ACTIVE)).booleanValue();

      if (firstInRound) {

         //Map activeTxns = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
        Map activeTxnsWrapped = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
        Map activeTxns = RtxUtilVersionHelper.convertWrapperMapToActiveTxMap(activeTxnsWrapped);
         Object[] txIds = activeTxns.keySet().toArray();
         // For serial execution tests, there is only the one active transaction
         txInfo = (TxInfo)activeTxns.get(txIds[0]);
         txId = txInfo.getTxId();
         Log.getLogWriter().info("In round " + rrNumber + " to close " + txId + ", firstInRound is " + firstInRound);
         boolean txCompleted = false;

         // It's possible that the Tx was rolled back due to Exceptions (caused by HA)
         if (txActive) {

            // recycle dataStores (no waiting)
            if (isHA) {
               stopStartDataStores();
            }

            int commitPercentage = TxPrms.getCommitPercentage();
            int n = TestConfig.tab().getRandGen().nextInt(0, 100);
            try {
               /* TODO: TX: redo for new TX impl
               if (n <= commitPercentage) {
                  txCompleted = commit(txInfo);
               } else {
                  rollback(txInfo);
                  txCompleted = false;  // rolled back, do not validate tx keysValues
               }
               */
            } catch (ServerConnectivityException e) {
               if (!isHA) {
                  throw new TestException(e + TestHelper.getStackTrace(e));
               } else {
                  Log.getLogWriter().info("Caught " + e + " while executing commit.  Expected with HA, continuing test");
               }
            } catch (FunctionException ex) {
               Log.getLogWriter().info("Caught " + ex + " while executing commit");
               Throwable causedBy = ex.getCause();
               Throwable lastCause = TestHelper.getLastCausedBy(ex);
               Log.getLogWriter().info("lastCause = " + lastCause);
               if ((ex instanceof FunctionInvocationTargetException) ||
                   (causedBy instanceof FunctionInvocationTargetException) ||
                   (causedBy instanceof TransactionDataRebalancedException) ||
                   (causedBy instanceof TransactionDataNodeHasDepartedException) ||
                   (lastCause instanceof FunctionInvocationTargetException) ||
                   (lastCause instanceof TransactionException)) {
                  if (!isHA) {
                     throw new TestException(ex + TestHelper.getStackTrace(ex));
                  } else {
                     Log.getLogWriter().info("Caught " + ex + " while executing txOps.  Expected with HA, continuing test");
                  }
               } else {  // TestException and other unexpected Exceptions
                  throw new TestException(ex + TestHelper.getStackTrace(ex));
               }
            }

            // wait for updates to complete
            SilenceListener.waitForSilence(30, 5000);
            if (txCompleted) {
               verifyTx(txInfo);
            }
         } else {
            if (isHA) {
               Log.getLogWriter().info("Tx Failed/RolledBack during execute round, skipping commit");
            } else {
               throw new TestException("Unexpected failure during execute round prevents commit of tx " + txId);
            }
         }
         // remove this transaction from the list of active txns, update on blackboard
         activeTxns.remove(txId);
         ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.ACTIVE_TXNS, RtxUtilVersionHelper.convertActiveTxMapToWrapperMap(activeTxns));
         ResumeTxBB.getBB().getSharedMap().put(ResumeTxBB.TX_COMPLETED, new Boolean(txCompleted));
      } else {  // for clientServer tests, edgeClients need to validate against the expectedKeysValues and destroyed keys on the BB
         if (isBridgeClient) {
            Log.getLogWriter().info("In round " + rrNumber + " validate edge client keysValues");
            boolean verify = ((Boolean)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_COMPLETED)).booleanValue();
            if (verify) {
               edgeClientValidation(txId);
            }
         }
      }
   } 
}

/** 
 *  verifyTx executes a function on each server.  This function verifies that the 
 *  server regions contain the expected keysValues (see ResumeTxBB.computeExpectedKeysValues()).
 *  This snapshot is based on the BB OpList which is updated after each client returns from executing the
 *  ExecuteTx function.  
 *
 *  Throws a TestException is validation is not successful (see ResumeTxBB.ERRSTR).
 */
protected void verifyTx(TxInfo txInfo) {

   DistributedSystem ds = CacheHelper.getCache().getDistributedSystem();
   ResumeTxBB.computeExpectedKeysValues();  // writes to BB for use by function

   // build args ArrayList (clientIdString, optionally add the txId)
   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());

   // add txId to args for use by function
   TransactionId txId = txInfo.getTxId();
   aList.add(txId);

   Pool pool = PoolManager.find("brloader");
   Execution e = null;
   if (pool != null) {
      Log.getLogWriter().info("executing function VerifyTx onServers on pool " + pool.getName() + " withArgs " + aList);
      e = FunctionService.onServers(pool).withArgs(aList);
   } else {  // peers
      DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
      if (txHostDataPolicy.withPartitioning()) {
         String regionName = txInfo.getRegionName();
         Region aRegion = RegionHelper.getRegion(regionName);
         Log.getLogWriter().info("executing function VerifyTx on region " + aRegion.getName() + " withArgs " + aList);
         e = FunctionService.onRegion(aRegion).withArgs(aList);
      } else {
         Log.getLogWriter().info("executing function VerifyTx onMembers(ds) withArgs " + aList);
         e = FunctionService.onMembers(ds).withArgs(aList);
      }
   }
   ResultCollector rc = e.execute(new VerifyTx());
   List resultList = (List)rc.getResult();
   for (Iterator it = resultList.iterator(); it.hasNext(); ) {
      boolean success = ((Boolean)it.next()).booleanValue();
      if (!success) {
         throw new TestException("Failed validation of expected key/values\n" + ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ERRSTR));
      }
   }
   Log.getLogWriter().info("Successful validation of expected keys and values");
}

/** 
 * edgeClientValidation - invoked in the edgeClients after the initiator commits and validates the servers.
 * Does the same validation (expected keysValues and destroyedKeys) based on BB OpList maintained during
 * the sequence of ExecuteTx.  The servers are validation through execution of the VerifyTx function; 
 * edgeClients are verified inline.
 *
 * Throws a TestException with details if validation fails.
 */
private void edgeClientValidation(TransactionId txId) {

    Log.getLogWriter().info("Verifying expected keys/values in edge client based on " + txId);
    StringBuffer errStr = new StringBuffer();
    boolean success = true;

    // verify destroyed entries
    Map destroyedEntries = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.DESTROYED_ENTRIES);
    Log.getLogWriter().fine("destroyedEntries = " + destroyedEntries);

    for (Iterator it = destroyedEntries.keySet().iterator(); it.hasNext(); ) {
       String regionName = (String)it.next();
       Region aRegion = RegionHelper.getRegion(regionName);
       List keys = (List)destroyedEntries.get(regionName);
       for (int i=0; i < keys.size(); i++) {
          Object key = keys.get(i);
          if (aRegion.containsKey(key)) {
             Log.getLogWriter().info("Expected containsKey to be false, for " + regionName + ":" + key + ", but it was true (key was destroyed in " + txId + ")");
             errStr.append("Expected containsKey to be false, for " + regionName + ":" + key + ", but it was true (key was destroyed in " + txId + ") \n");
          }
       }
    }

    // verify creates, updates, invalidates
    Map expectedKeysValues = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.EXPECTED_KEYS_VALUES);
    Log.getLogWriter().fine("expectedKeysValues = " + expectedKeysValues);

    for (Iterator it = expectedKeysValues.keySet().iterator(); it.hasNext(); ) {
       String regionName = (String)it.next();
       Region aRegion = RegionHelper.getRegion(regionName);
       Map keyValuePair = (Map)expectedKeysValues.get(regionName);
       for (Iterator kvi = keyValuePair.keySet().iterator(); kvi.hasNext(); ) {
          Object key = kvi.next();
          Object value = keyValuePair.get(key);
          
          //verify against region data
          if (!aRegion.containsKey(key)) {
             Log.getLogWriter().info("Expected containsKey() to be true for " + regionName + ":" + key + ", but it was false.  txId = " + txId);
             errStr.append("Expected containsKey() to be true for " + regionName + ":" + key + ", but it was false, txId = " + txId + "\n");
          }
          if (value == null && aRegion.containsValueForKey(key)) {
             Log.getLogWriter().info("Expected containsValueForKey to be false, for " + regionName + ":" + key + ", but it was true, txId = " + txId);
             errStr.append("Expected containsValueForKey to be false, for " + regionName + ":" + key + ", but it was true, txId = " + txId + "\n");
          }

          if (value != null && !aRegion.containsValueForKey(key)) {
             Log.getLogWriter().info("Expected containsValueForKey() to be true for " + regionName + ":" + key + ", but it was false, txId = " + txId);
             errStr.append("Expected containsValueForKey() to be true for " + regionName + ":" + key + ", but it was false, txId = " + txId + "\n");
          }

          Object actualValue = null;
          // Avoid doing a load 
          Region.Entry entry = aRegion.getEntry(key);
          if (entry != null) {
             actualValue = entry.getValue();
          }

          // test really works off ValueHolder.modVal ...
          if (actualValue instanceof BaseValueHolder) {
            actualValue = ((BaseValueHolder)actualValue).modVal;
            Log.getLogWriter().fine("actualValue for " + regionName + ":" + key + " is an Integer " + actualValue);
            if (!actualValue.equals(value)) {
               Log.getLogWriter().info("Expected value for " + regionName + ":" + key + " to be " + value + ", but found " + actualValue + " txId = " + txId);
               errStr.append("Expected value for " + regionName + ":" + key + " to be " + value + ", but found " + actualValue + " " + TestHelper.getStackTrace() + " txId = " + txId + "\n");
            }
          } else {
            Log.getLogWriter().info("WARNING: actual value retrieved from cache is not a ValueHolder, is " + actualValue);
          }
       }
    }
    if (errStr.length() > 0) {
       throw new TestException("edge client validation failed for " + txId + "\n" + errStr.toString());
    } else {
       Log.getLogWriter().info("Done executing VerifyTx - validation successful");
    }
  }

// ========================================================================
// tryResumeWithWait methods
// ========================================================================
/** 
 *  Assigned to only one thread at a time, this thread executes a resumeable transaction 
 *  via Function Execution, but does not suspend between begin, doOps and commit.
 *  All other threads will be in a tryResume(with wait) and should return immediately
 *  after this thread commits the transaction.
 */
public static void HydraTask_TXController() {
   testInstance.TXController();
}

/**
 *  Launch TXController function (begin, execute transactional ops, commit without suspending)
 */
protected void TXController() {
   DistributedSystem ds = CacheHelper.getCache().getDistributedSystem();
   int numThreads = RemoteTestModule.getCurrentThread().getCurrentTask().getTotalThreads();
   Log.getLogWriter().info("numThreads executing tasks = " + numThreads);
   RtxUtil.logExecutionNumber();

   // build args ArrayList (clientIdString, numThreads in this threadGroup)
   Pool pool = PoolManager.find("brloader");
   if (pool != null) {
      isBridgeClient = true;
   }

   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());
   aList.add(new Integer(numThreads));

   Execution e;
   TxInfo txInfo = new TxInfo();
   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
   if (txHostDataPolicy.withPartitioning()) {
      // pick a random region/key pair to target a specific dataStore
      Region aRegion = (Region)CacheHelper.getCache().rootRegions().iterator().next();
      Object[] keySet = aRegion.keySet().toArray();
      Object aKey = null;
      if (keySet.length > 0) {
         int randInt = TestConfig.tab().getRandGen().nextInt(0, keySet.length-1);
         aKey = keySet[randInt];
      } else {  // create a new key
         aKey = NameFactory.getNextPositiveObjectName();
         aRegion.put(aKey, RtxUtil.txUtilInstance.getNewValue(aKey));
      }

      Set filterSet = new HashSet();
      filterSet.add(aKey);
      e = FunctionService.onRegion(aRegion).withArgs(aList).withFilter(filterSet);
      Log.getLogWriter().info("executing function TXController on region " + aRegion.getName() + " withArgs " + aList + " and filterSet " + filterSet);

      txInfo.setRegionName(aRegion.getName());
      txInfo.setKey(aKey);
      txInfo.setRoutingObject(new ModRoutingObject(aKey));
   } else { // replicated
      if (isBridgeClient) {  // invoke on a single server
         e = FunctionService.onServer(pool).withArgs(aList);
         Log.getLogWriter().info("executing function TXController onServer( " + pool.getName() +  ") withArgs " + aList);
      } else {  // peer test, target a random member
         Object[] members = DistributedSystemHelper.getMembers().toArray();
         int randInt = TestConfig.tab().getRandGen().nextInt(0, members.length-1);
         DistributedMember targetDM = (DistributedMember)members[randInt];
         e = FunctionService.onMember(ds, targetDM).withArgs(aList);
         Log.getLogWriter().info("executing function TXController on member(" + targetDM + ") withArgs " + aList);
      }
   }
   aList.add(txInfo);
   ResultCollector rc = e.execute(new TXController());
   List resultList = (List)rc.getResult();
   boolean success = ((Boolean)resultList.get(0)).booleanValue();
   if (!success) {
      throw new TestException("TXController function returned " + success + ", " + TestHelper.getStackTrace());
   }
}

/** 
 * Threads in this task will obtain an active transaction from the ResumeTxBB (blackboard)
 * and use function execution to enter a tryResume(with wait).  Since the TXController thread
 * does not suspend during its transaction, any threads in tryResume should exit immmediately
 * when the TXController commits.
 */
public static void HydraTask_tryResumeWithWait() throws Exception{
   testInstance.tryResumeWithWait();
}

protected void tryResumeWithWait() throws Exception {

   // wait for TXController to be ready (TXController starts the tx and writes entry to BB (activeTxns)
   // if we don't reach this counter, it is probably because hydra stopped assigning tasks ... 
   try {
      TestHelper.waitForCounter(ResumeTxBB.getBB(), 
                                "ResumeTxBB.readyToTryResume",
                                ResumeTxBB.readyToTryResume,
                                1,
                                true,
                                5000);
   } catch (TestException e) {
      Log.getLogWriter().info("Caught " + e + ": hydra probably stopped scheduling TXController, ignoring");
   }

   //Map activeTxns = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
   Map activeTxnsWrapped = (Map)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.ACTIVE_TXNS);
   Map activeTxns = RtxUtilVersionHelper.convertWrapperMapToActiveTxMap(activeTxnsWrapped);
   Object[] txIds = activeTxns.keySet().toArray();
   TransactionId txId = (TransactionId)txIds[0];
   TxInfo txInfo = (TxInfo)activeTxns.get(txId);

   tryResume(txInfo);
   ResumeTxBB.getBB().getSharedCounters().decrement(ResumeTxBB.inTryResume);
}

protected boolean tryResume(TxInfo txInfo) {
   TransactionId txId = txInfo.getTxId();

   // build args ArrayList (clientIdString, optionally add the txId)
   Pool pool = PoolManager.find("brloader");
   if (pool != null) {
      isBridgeClient = true;
   }

   ArrayList aList = new ArrayList();
   aList.add(getClientIdString());
   aList.add(txId);

   Execution e;
   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
   if (txHostDataPolicy.withPartitioning()) {
      String regionName = txInfo.getRegionName();
      Region aRegion = RegionHelper.getRegion(regionName);
      Object aKey = txInfo.getKey();
      Set filterSet = new HashSet();
      filterSet.add(aKey);

      // target the primary using filterSet (region, key)
      e = FunctionService.onRegion(aRegion).withArgs(aList).withFilter(filterSet);
      Log.getLogWriter().info("executing function TryResume on region " + aRegion.getName() + " with aList " + aList + " and filterSet " + filterSet);
   } else { // replicated
      // for peers, target the tx host via the DistributedMember from TxId
      // for servers, target all servers ... will need to allow multiple responses from ResultCollector
      // TODO: TX: redo for new TX impl
      //DistributedMember txHost = ((TXId)txId).getMemberId();
      if (isBridgeClient) {
         e = FunctionService.onServers(pool).withArgs(aList);
         Log.getLogWriter().info("executing function TryResume onServers(" + pool.getName() + ") withArgs " + aList);
      } else {
         e = FunctionService.onMembers(DistributedSystemHelper.getDistributedSystem()).withArgs(aList);
         Log.getLogWriter().info("executing function TryResume on all members withArgs " + aList);
         /*
         e = FunctionService.onMember(DistributedSystemHelper.getDistributedSystem(), txHost).withArgs(aList);
         Log.getLogWriter().info("executing function TryResume onMember(" + txHost + ") withArgs " + aList);
         */
      }
   }

   // In the case of onServers() - replicated regions in client/server topology, we 
   // will get multiple responses.  We need to know if any return true.
   boolean status = false;
   ResultCollector rc = e.execute(new TryResume());
   List resultList = (List)rc.getResult();
   for (int i = 0; i < resultList.size(); i++) {
      boolean result = ((Boolean)resultList.get(i)).booleanValue();
      if (result) {
         status = true;
      }
   }
   Log.getLogWriter().info("TryResume returning  " + status);
   return status;
}

// ========================================================================
// Utility Methods
// ========================================================================
/**
 * Randomly stop and restart dataStore vms.   
 * Tests which invoke this must also call util.StopStartVMs.StopStart_init as a 
 * INITTASK (to write clientNames to the Blackboard).
 * For peer tests with PRs, dataStore vm names must include "dataStore"; for client/server tests
 * the server/dataStore names must contain "bridge".  (See matchStr below).
 * For peer tests with RRs (replicated regions), vm names must include "client"; for client/server
 * tests, the server names must contain "bridge".  
 */
public static void HydraTask_stopStartDataStores() {
   testInstance.stopStartDataStores();
}

protected void stopStartDataStores() {

   DataPolicy txHostDataPolicy = (DataPolicy)ResumeTxBB.getBB().getSharedMap().get(ResumeTxBB.TX_HOST_DATAPOLICY);
   int numVMsToStop = TestConfig.tab().intAt(StopStartPrms.numVMsToStop);
   int randInt =  TestConfig.tab().getRandGen().nextInt(1, numVMsToStop);
   String matchStr = "client";
   if (txHostDataPolicy.withPartitioning()) {
     matchStr = "dataStore";
   }
   if (BridgeHelper.getEndpoints().size() > 0) {
      matchStr = "bridge";
   }
   Object[] objArr = StopStartVMs.getOtherVMs(randInt, matchStr);
   List clientVmInfoList = (List)(objArr[0]);
   List stopModeList = (List)(objArr[1]);
   StopStartVMs.stopStartVMs(clientVmInfoList, stopModeList);
}

public static String activeTxnsToString(Map activeTxns) {
   StringBuffer txStr = new StringBuffer();
   txStr.append("ActiveTxns has " + activeTxns.size() + " entries\n");
   Object[] txIds = activeTxns.keySet().toArray();
   for (int i = 0; i < txIds.length; i++) {
      TransactionId txId = (TransactionId)txIds[i];
      TxInfo txInfo = (TxInfo)activeTxns.get(txId);
      txStr.append("ActiveTxns(" + i + ") = {" + txId + ":" + txInfo.toString() + "}\n");
   }
   return txStr.toString();
}

  /** Return a String identifying this client jvm and thread to be used to send to a function
   *  useful for analyzing failed runs.
   *
   * @return
   */
  private static String getClientIdString() {
    return "vm_" + RemoteTestModule.getMyVmid() + "_thr_" + RemoteTestModule.getCurrentThread().getThreadId();
  }
}
