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
   
package parReg.tx;

import java.util.Iterator;
import java.util.List;

import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import hydra.*;
import tx.CollectionsTest;
import tx.OpList;
import tx.TxBB;
import tx.TxPrms;
import tx.ViewTest;
import util.CacheUtil;
import util.TestException;
import util.TestHelper;
import util.TxHelper;

/**
 * A test to validate what different threads/VMs see in terms of
 * transactional state (when a tx is in progress) as well as non-tx
 * operations.
 *
 * This version is used in client/server distIntegrity tests with 
 * partitioned regions in the bridgeServers.  Variants exist to kill
 * the tx edge client, the delegate or a dataHost server.
 */

public class ParRegBridgeView extends ParRegViewTest {

  public synchronized static void HydraTask_initialize() {
    if (viewTest == null) {
      viewTest = new ParRegBridgeView();
      viewTest.initialize();
      ((ParRegBridgeView)viewTest).initialize(ConfigPrms.getRegionConfig());

      // post port <=> DM mapping in BB (used for targeting dataHost vs. delegate in kill)
      CacheServer bridge = BridgeHelper.getBridgeServer();
      if (bridge != null) {
        String host = RemoteTestModule.getMyHost();
        int dot = host.indexOf(".");
        if (dot > 0) {
          host = host.substring(0, dot);
        }
        int port = bridge.getPort();
        DistributedMember dm = DistributedSystemHelper.getDistributedSystem().getDistributedMember();
        String mappingKey = host + ":" + port;
        Log.getLogWriter().info("Posting port to dm mapping to BB: " + mappingKey + ":" + dm);
        TxBB.getBB().getSharedMap().put(mappingKey, dm);
      }
    }
  }

/**
 *  Hydra CLOSETASK to ensure that we can access the same entries
 *  that were accessed in the original transaction (to ensure all
 *  locking resources were released).
 */
public static void HydraCloseTask_verifyResourcesReleased() {
  Log.getLogWriter().info("HydraCloseTask_verifyResourcesReleased(TX_NUMBER = " + TxBB.getBB().getSharedCounters().read(TxBB.TX_NUMBER) + ")" );
  if (viewTest == null) {
    viewTest = new ParRegBridgeView();
  }
  ((ParRegBridgeView)viewTest).verifyResourcesReleased();
}

/**
 *  Hydra CLOSETASK to verify that resources (e.g. locks) have been
 *  released after a client which was committing a transaction is killed.
 *  This cannot be invoked in the case of the distributed commit succeeding
 *  as we likely can't execute the exact operation again.  Either check this
 *  case, or provide functions to randomly create these operations with a 
 *  given Region & Key.
 */
protected void verifyResourcesReleased() {
  String opListKey = (String)TxBB.getBB().getSharedMap().get(TxBB.TX_OPLIST_KEY);
  OpList opList = (OpList)TxBB.getBB().getSharedMap().get(opListKey);
  OpList txOpList = opList.collapse();

  Log.getLogWriter().info("Entered verifyResourcesReleased, reusing operations from " + txOpList.toString());

  boolean isVisible = updatesVisible();

  CollectionsTest validator = new CollectionsTest();
  for (int i = 0; i < txOpList.numOps(); i++) {
    tx.Operation op = txOpList.getOperation(i);
    Log.getLogWriter().info("Executing operation " + op);

    // beginTx
    TxHelper.begin();

    // If isVisible, everyone should have received & updated so we can't
    // execute the same opList.  
    //
    // To simplify actions on the same keyset,
    // Modify any entry-destroys => create; change everything else to an update. 
    if (isVisible) {
      if (op.isEntryDestroy()) {
        op.setOpName(tx.Operation.ENTRY_CREATE);
      } else if (op.isEntryInvalidate()) {
        op.setOpName(tx.Operation.ENTRY_UPDATE);
      } else {
        op.setOpName(tx.Operation.ENTRY_UPDATE);
      }
      op.setNewValue(new Integer(0));  // Integer modVal
    }

    // op = entryDestroy => entryCreate
    // else do an update
    validator.executeOperation(op);

    try {
      TxHelper.commit();
    } catch (ConflictException e) {
      throw new TestException("Unexpected ConflictException " + TestHelper.getStackTrace(e));
    }

  }
}

/**
 *  Hydra CLOSETASK to verify that either all or no VMs received 
 *  the distribution of a transaction (even though the tx vm was 
 *  killed mid-way through the operation.  Either everyone has all
 *  the updates or no one does!
 */
public static void HydraCloseTask_validateTxConsistency() {
  Log.getLogWriter().info("HydraCloseTask_validateTxConsistency(TX_NUMBER = " + TxBB.getBB().getSharedCounters().read(TxBB.TX_NUMBER) + ")" );
  // Hold off and allow distributions to occur
  if (viewTest == null) {
    viewTest = new ParRegBridgeView();
  }
  ((ParRegBridgeView)viewTest).validateTxConsistency();
}

  protected void validateTxConsistency() {
    Log.getLogWriter().info("Entered validateTxConsistency(TX_NUMBER = " + TxBB.getBB().getSharedCounters().read(TxBB.TX_NUMBER) + ")" );
  
    try {
      validateTxOps(updatesVisible());
    } catch (TestException e) {
      Log.getLogWriter().info("Listing current values of each entry involved in the commit");
      String opListKey = (String)TxBB.getBB().getSharedMap().get(TxBB.TX_OPLIST_KEY);
      OpList txOpList = (OpList)TxBB.getBB().getSharedMap().get(opListKey);
      for (int i = 0; i < txOpList.numOps(); i++) {
        tx.Operation op = txOpList.getOperation(i);
        String regionName = op.getRegionName();
        Region aRegion = CacheUtil.getCache().getRegion(regionName);
        String key = (String)op.getKey();
        Log.getLogWriter().info("Operation(" + i + ") = " + op.toString());
        Log.getLogWriter().info("Current value of <" + regionName + "><" + key + "> = <" + getValueInVM(aRegion, key) + ">");
      }
      throw e;
    }
  }

  /** Depending on the commitStateTrigger (the point at which the committing
   *  VM was killed), should we be able to see the tx updates?
   *
   *  @returns - true => updates should be visible
   *
   *  Note that this implementation covers replicated regions.
   *  ParRegViewTest overrides this for PR specific behavior.
   */
  protected boolean updatesVisible() {
    boolean isVisible = false;
    Integer mapValue = (Integer)TxBB.getBB().getSharedMap().get(TxBB.CommitStateTrigger);
    int commitStateTrigger = mapValue.intValue();
    Boolean listenerProcessedCommit = (Boolean)TxBB.getBB().getSharedMap().get(TxBB.afterCommitProcessed);

    if (((commitStateTrigger == TxPrms.CommitState_afterSend) ||
        (commitStateTrigger == TxPrms.CommitState_afterReleaseLocalLocks) ||
        (commitStateTrigger == TxPrms.CommitState_afterIndividualCommit)) ||
        listenerProcessedCommit) {
      isVisible = true;
    }
    Log.getLogWriter().info("updatesVisible(" + TxPrms.toStringForCommitStateTrigger(commitStateTrigger) + ") returning " + isVisible);
    return isVisible;
  }

/* Install commitTestCallbacks in the local tx vm.  
 * The killCommittor, killDelegate or killNonDelgate thread will kill the killTarget VM
 * which is determined in this method and written to the BB.
 */
public void setupCommitTestCallbacks(String commitStatePrm) {

  // Local class used to provide a Runnable to the product via
  // internal apis.  
  class CommitTestCallback implements Runnable {
    public void run() {
      synchronized(ViewTest.killSyncObject) {
        Log.getLogWriter().info("product invoked CommitTestCallback, killSyncObject.notify() ...");
        ViewTest.killSyncObject.notify();
      }
      // prevent the commit process from continuing until the VM stops
      // We're going to do a MEAN_KILL, so it shouldn't take long
      // In the case of distIntegrityFD (splitBrain), we need to wait for
      // FD Detection (memberTimeout(15) + ackSevereAlertThreshold (10))
      MasterController.sleepForMs(30000);
    }
  }

  // select the commitStatus to trigger the VM kill
  int commitStateTrigger = TxPrms.getCommitStateTrigger(commitStatePrm);
  // Store on BB for CloseTasks to use (determines isVisible setting)
  TxBB.getBB().getSharedMap().put(TxBB.CommitStateTrigger, new Integer(commitStateTrigger));
  Log.getLogWriter().info("TxPrms.commitStateTrigger = " + commitStatePrm + "(" + commitStateTrigger + ")");

  // callbacks are setup via the product TXState class
  /* TODO: merge: below code for client TX
  TXStateProxy txStateProxy = TxHelper.getTxState();
  ClientTXStateStub txState = (ClientTXStateStub)((TXStateProxyImpl)txStateProxy).getRealDeal(null,null);

  postClientVmInfoToBB();

  switch (commitStateTrigger) {
    // kill local tx thread when targeting remote entries (TXStateStub) on commit
    case TxPrms.CommitState_afterSendCommit:
      txState.setAfterSendCommit(new CommitTestCallback());
      break;

    // kill local tx thread when targeting remote entries (TXStateStub) on rollback
    case TxPrms.CommitState_afterSendRollback:
      txState.setAfterSendRollback(new CommitTestCallback());
      break;

    default:
      throw new TestException("Unexpected commitStateTrigger " +  TxPrms.toStringForCommitStateTrigger(commitStateTrigger));
  } // end switch 
  */
}

/**
 *  Write ClientVmInfo for delegate and another (nonDelegate) host to BB for future use
 *  with kill methods above.
 */
  protected void postClientVmInfoToBB() {
    // use test hooks to determine Endpoint of delegate
    PoolImpl pool = (PoolImpl)PoolManager.find("brloader");
    if (pool != null) {
      ServerLocation delegate  = pool.getServerAffinityLocation();
      int delegatePort = delegate.getPort();
      String delegateHost = delegate.getHostName();
      // be careful of fully qualified hostnames (xxx.gemstone.com)
      int dot = delegateHost.indexOf(".");
      if (dot != -1) {
        delegateHost = delegateHost.substring(0, dot);
      }
      Log.getLogWriter().info("Delegate is " + delegateHost + ":" + delegatePort);
  
      // use this information to get the Endpoint based ClientVmInfo and post to BB
      ClientVmInfo delegateVmInfo = null;
      ClientVmInfo nonDelegateVmInfo = null;
  
      List endpoints = BridgeHelper.getEndpoints();
      for (Iterator i = endpoints.iterator(); i.hasNext();) {
        BridgeHelper.Endpoint endpoint = (BridgeHelper.Endpoint)i.next();
        if ((endpoint.getHost().equalsIgnoreCase(delegateHost)) && (endpoint.getPort() == delegatePort)) {
          delegateVmInfo = new ClientVmInfo(endpoint);
        } else {
          nonDelegateVmInfo = new ClientVmInfo(endpoint);
        }
      }
      TxBB.getBB().getSharedMap().put(TxBB.delegate, delegateVmInfo);
      TxBB.getBB().getSharedMap().put(TxBB.nonDelegateServer, nonDelegateVmInfo);
  
      Log.getLogWriter().info("Writing delegateVmInfo to BB: " + delegateVmInfo);
      Log.getLogWriter().info("Writing nonDelegateVmInfo to BB: " + nonDelegateVmInfo);
    }
  }

/** 
 *  kill the VM if we are the transactional thread and a commit
 *  is in Progress (see TxBB.commitInProgress)
 */
public static void HydraTask_killCommittor() throws ClientVmNotFoundException {
   Log.getLogWriter().info("In killCommittor ... killing tx edge client vm");

   synchronized(ViewTest.killSyncObject) {
     try {
       ViewTest.killSyncObject.wait();
     } catch (InterruptedException e) {
       Log.getLogWriter().info("killCommittor interrupted, did not kill VM");
     }
   }

   Boolean timeToStop = (Boolean)TxBB.getBB().getSharedMap().get(TxBB.timeToStop);
   int stopMode = ClientVmMgr.MEAN_KILL;

   // BUG 43538 
   // - afterIndividualCommit trigger needs to use a NICE_KILL (to allow socket buffer flush)
   String commitStatePrm = TestConfig.tab().stringAt(TxPrms.commitStateTrigger, null);
   if (commitStatePrm != null) {
      if (TxPrms.getCommitStateTrigger(commitStatePrm) == TxPrms.CommitState_afterIndividualCommit) {
         stopMode = ClientVmMgr.NICE_KILL;
      }
   }

   if (timeToStop.equals(Boolean.FALSE)) {
     // this is the VM we intend to kill via the callback
     ClientVmMgr.stop(
       "product invoked CommitTestCallback, killing committor",
       stopMode,  
       ClientVmMgr.NEVER       // never allow this VM to restart
     );
   } else {
     Log.getLogWriter().info("killCommittor invoked, signalled by waitForDist to allow test to continue normally.  Not killing this VM");
   }
}

/** 
 *  kill the delegate (killTarget on BB) if we are the transactional thread and a commit
 *  is in Progress (see TxBB.commitInProgress)
 */
public static void HydraTask_killDelegate() throws ClientVmNotFoundException {
   Log.getLogWriter().info("In killDelegate ... ");

   synchronized(ViewTest.killSyncObject) {
     try {
       ViewTest.killSyncObject.wait();
     } catch (InterruptedException e) {
       Log.getLogWriter().info("killDelegate interrupted, did not kill VM");
     }
   }
   
   Boolean timeToStop = (Boolean)TxBB.getBB().getSharedMap().get(TxBB.timeToStop);
   if (timeToStop.equals(Boolean.FALSE)) {
     // this is the VM we intend to kill via the callback
     ClientVmMgr.stop(
       "product invoked CommitTestCallback, killing delegate",
       ClientVmMgr.MEAN_KILL,  // kill -TERM
       ClientVmMgr.NEVER,      // never allow this VM to restart
       (ClientVmInfo)TxBB.getBB().getSharedMap().get(TxBB.delegate)
     );
   } else {
     Log.getLogWriter().info("killCommittor invoked, signalled by waitForDist to allow test to continue normally.  Not killing this VM");
   }
}

/** 
 *  kill a DataHost server if the VM if we are the transactional thread and a commit
 *  is in Progress (see TxBB.commitInProgress)
 */
public static void HydraTask_killDataHost() throws ClientVmNotFoundException {
   Log.getLogWriter().info("In killDataHost ...");

   synchronized(ViewTest.killSyncObject) {
     try {
       ViewTest.killSyncObject.wait();
     } catch (InterruptedException e) {
       Log.getLogWriter().info("killDataHost interrupted, did not kill VM");
     }
   }
   
   Boolean timeToStop = (Boolean)TxBB.getBB().getSharedMap().get(TxBB.timeToStop);
   if (timeToStop.equals(Boolean.FALSE)) {
     // this is the VM we intend to kill via the callback
     ClientVmMgr.stop(
       "product invoked CommitTestCallback, killing data host",
       ClientVmMgr.MEAN_KILL,  // kill -TERM
       ClientVmMgr.NEVER,      // never allow this VM to restart
       (ClientVmInfo)TxBB.getBB().getSharedMap().get(TxBB.nonDelegateServer)
     );
   } else {
     Log.getLogWriter().info("killCommittor invoked, signalled by waitForDist to allow test to continue normally.  Not killing this VM");
   }
}
}
