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

package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;

import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.NetView;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.admin.remote.ShutdownAllRequest;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;

/**
 * An instruction to recipient GFXD members to gracefully shutdown fabric service,
 * shutdown network server, close cache.
 * @author sjigyasu
 *
 */
public class GfxdShutdownAllRequest extends ShutdownAllRequest implements
    GfxdSerializable {

  /*
  public static Set<?> sendShutdownAll(DM dm, Set<?> recipients, long timeout)
      throws SQLException {

    GfxdShutdownAllRequest request = new GfxdShutdownAllRequest();
    request.setRecipients(recipients);
    
    ShutDownAllReplyProcessor replyProcessor = new ShutDownAllReplyProcessor(dm, recipients);
    request.msgId = replyProcessor.getProcessorId();
    dm.putOutgoing(request);
    try {
      if(!replyProcessor.waitForReplies(timeout)) {
        return null;
      }
    } catch (ReplyException e) {
      if(!(e.getCause() instanceof CancelException)) {
        e.handleAsUnexpected();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    // wait until all the recipients send response, shut down itself
    
    // Stop fabric service.
    request.processShutDown(null);
    // Disconnect internal distributed system.  The disconnect was skipped using the keepDS flag
    // while calling cache.close() from cache.shutdownAll() because the IDS needs to be connected to
    // be able to send back a response. Now we can disconnect the IDS.
    InternalDistributedSystem ids = dm.getSystem();
    if (ids.isConnected()) {
      ids.disconnect();
    }

    return replyProcessor.getResults();
  }
  */

  @Override
  protected void shutdownSelf(DM dm, boolean hadCache) throws SQLException {
    // Stop fabric service.
    processShutDown(null);
    // Disconnect internal distributed system. The disconnect was skipped using
    // the keepDS flag while calling cache.close() from cache.shutdownAll()
    // because the IDS needs to be connected to be able to send back a response.
    // Now we can disconnect the IDS.
    InternalDistributedSystem ids = dm.getSystem();
    if (ids != null && ids.isConnected()) {
      ids.disconnect();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean isDedicatedLocator() {
    return super.isDedicatedLocator()
        || GemFireXDUtils.getMyVMKind() == GemFireStore.VMKind.LOCATOR;
  }

  @Override
  protected void processShutDown(DistributionManager dm) throws SQLException {

    FabricServiceImpl fabricService = (FabricServiceImpl)FabricServiceManager
        .currentFabricServiceInstance();
    if (fabricService != null) {
      // for locators, wait for sometime for members below this in the VIEW to
      // shutdown first; always prefer locators to be shutdown last
      final boolean isLocator = (GemFireXDUtils.getMyVMKind() ==
          GemFireStore.VMKind.LOCATOR);
      if (isLocator) {
        final long waitMillis = 30000L;
        final long start = System.currentTimeMillis();
        final InternalDistributedMember myId = dm.getDistributionManagerId();
        while ((System.currentTimeMillis() - start) < waitMillis) {
          NetView view = dm.getMembershipManager().getView();
          if (view != null) {
            boolean hasNewerMembers = false;
            boolean foundMyId = false;
            boolean hasNonLocatorMembers = false;
            GemFireStore.VMKind vmKind;
            for (Object m : view) {
              if (foundMyId && !hasNewerMembers && GemFireXDUtils.getVMKind(
                  (DistributedMember)m) == GemFireStore.VMKind.LOCATOR) {
                hasNewerMembers = true;
              }
              else if (!foundMyId && myId.equals(m)) {
                foundMyId = true;
              }
              else if ((vmKind = GemFireXDUtils.getVMKind(
                  (DistributedMember)m)) != null && vmKind.isAccessorOrStore()) {
                hasNonLocatorMembers = true;
              }
            }
            if (hasNewerMembers || hasNonLocatorMembers) {
              try {
                Thread.sleep(100L);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
            }
            else {
              break;
            }
          }
          else {
            break;
          }
        }
      }
      // spawn a new thread to shut down the JVM else this thread can also
      // get blocked if the startup thread is blocked
      final SQLException[] failure = new SQLException[1];
      Thread stopThr = new Thread(new Runnable() {
        @Override
        public void run() {
          FabricServiceImpl fabricService = (FabricServiceImpl)
              FabricServiceManager.currentFabricServiceInstance();
          if (fabricService != null) {
            try {
              synchronized (fabricService) {
                fabricService.setShutdownAllIdentifier();
                fabricService.stop(null);
              }
            } catch (SQLException sqle) {
              failure[0] = sqle;
            }
          }
        }
      });
      GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      try {
        final FabricService.State status = fabricService.status();
        final int waitTime = (status != null
            && status == FabricService.State.RUNNING ? 15000 : 5000);
        stopThr.start();
        stopThr.join(waitTime);
        if (stopThr.isAlive()) {
          // stop the cache directly and try again
          if (cache != null) {
            cache.shutDownAll();
          }
          stopThr.join();
        }
        if (failure[0] != null) {
          throw failure[0];
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public final int getDSFID() {
    return DataSerializableFixedID.GFXD_TYPE;
  }

  public byte getGfxdID() {
    return GFXD_SHUTDOWN_ALL_MESSAGE;
  }

  @Override
  public final void toData(DataOutput out) throws IOException {
    //GfxdDataSerializable.writeGfxdHeader(this, out);
    super.toData(out);
  }

  @Override
  public final void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
  }

  public static void dummy() {
  }
}
