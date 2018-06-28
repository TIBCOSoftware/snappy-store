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

package com.pivotal.gemfirexd.internal.engine.fabricservice;

import java.sql.SQLException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;

/**
 * Implementation of {@link FabricServer} API. Future product versions may
 * extend this class to alter its behaviour.
 *
 * @author soubhikc
 */
public class FabricServerImpl extends FabricServiceImpl implements FabricServer {

  private final Object initializationNotification = new Object();
  private String regionPathToWaitFor = "";
  private boolean notified;
  private boolean initializedOrWait;

  @Override
  public boolean isServer() {
    return true;
  }

  /**
   * @see FabricServer#start(Properties)
   */
  @Override
  public void start(Properties bootProperties) throws SQLException {
    start(bootProperties, false);
  }

  /**
   * @see FabricServer#start(Properties, boolean)
   */
  @Override
  public void start(Properties bootProperties,
      boolean ignoreIfStarted) throws SQLException {
    // take locks acquired by IDS.tryReconnect first to avoid deadlock
    // between start and reconnect thread
    synchronized (CacheFactory.class) {
      synchronized (this) {
        try {
          startImpl(bootProperties, ignoreIfStarted, false);
        } catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
            FabricServiceUtils.clearSystemProperties(monitorlite, sysProps);
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          // Whenever you catch Error or Throwable, you must also
          // check for fatal JVM error (see above).  However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          SystemFailure.checkFailure();
          handleThrowable(t);
        }
      }
    }
  }

  /**
   * This method invoked from GemFire to notify waiting for another JVM to
   * initialize for disk GII.
   * <p>
   * NOTE: It is deliberately not synchronized since it can be invoked by a
   * thread other than the booting thread itself which may be stuck waiting for
   * disk region initialization.
   */
  public void notifyWaiting(String regionPath,
      Set<PersistentMemberID> membersToWaitFor, Set<Integer> missingBuckets,
      PersistentMemberID myId, String message) {
    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("Accepting WAITING notification" +
          (message != null ? ": " + message : ""));
    }
    if (missingBuckets != null && missingBuckets.isEmpty() && membersToWaitFor.isEmpty()) {
      // only notify the FabricDataBase.postCreateDDLReplay
      notifyTableWait(regionPath);
    } else {
      if (this.serverstatus != State.WAITING) {
        this.previousServerStatus = this.serverstatus;
      }
      this.serverstatus = State.WAITING;
      notifyTableWait(regionPath);
      notifyWaitingInLauncher(regionPath, membersToWaitFor, missingBuckets, myId,
          message);
    }
  }

  public void notifyTableInitialized(boolean initialized, String regionPath) {
    synchronized (initializationNotification) {
      if(regionPath.equals(regionPathToWaitFor)) {
        notified = true;
        this.initializedOrWait = initialized;
        initializationNotification.notifyAll();
        regionPathToWaitFor = "";
      }
    }
  }

  private void notifyTableWait(String regionPath) {
    synchronized (initializationNotification) {
      if(regionPath.equals(regionPathToWaitFor)) {
        notified = true;
        this.initializedOrWait = true;
        initializationNotification.notifyAll();
        regionPathToWaitFor = "";
      }
    }
  }

  public void waitTableInitialized(Future<?> waitFor, String regionPath) throws InterruptedException {
    synchronized (initializationNotification) {
      this.regionPathToWaitFor = regionPath;
      while (this.regionPathToWaitFor.equals(regionPath) && !notified && !waitFor.isDone()) {
        initializationNotification.wait(500);
      }
      notified = false;
    }
  }

  public boolean isInitializedOrWait() {
    return initializedOrWait;
  }

  public boolean isNotified() {
    return notified;
  }
}
