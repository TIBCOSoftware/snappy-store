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
package com.gemstone.gemfire.internal.admin.remote;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalLocator;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.admin.remote.ShutdownAllGatewayHubsRequest.ShutDownAllGatewayHubsReplyProcessor;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.tcp.ConnectionTable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * An instruction to all members with cache that their PR should gracefully
 * close and disconnect DS
 * @author xzhou
 *
 */
public class ShutdownAllRequest extends AdminRequest {
  
  static final long SLEEP_TIME_BEFORE_DISCONNECT_DS = Long.getLong("gemfire.sleep-before-disconnect-ds", 1000).longValue();

  public ShutdownAllRequest() {
  }

  /**
   * Sends a shutdownAll request to all other members and performs local
   * shutdownAll processing in the waitingThreadPool.
   */
  public static Set<?> send(final DM dm, long timeout) {
    Set<?> recipients = dm.getOtherNormalDistributionManagerIds();
    recipients.remove(dm.getDistributionManagerId());

    return new ShutdownAllRequest().send(dm, recipients, timeout);
  }

  public Set<?> send(final DM dm, Set<?> recipients, long timeout) {
    boolean hadCache = hasCache();
    boolean interrupted = false;

    DistributionManager dism = (dm instanceof DistributionManager) ? (DistributionManager)dm : null;
    InternalDistributedMember myId = dm.getDistributionManagerId();

    // send msg to close gatewayhubs first
    ShutdownAllGatewayHubsRequest prep = new ShutdownAllGatewayHubsRequest();
    prep.setRecipients(recipients);
    ShutDownAllGatewayHubsReplyProcessor rp = new ShutDownAllGatewayHubsReplyProcessor(dm, recipients);
    prep.msgId = rp.getProcessorId();
    dm.putOutgoing(prep);

    //If this member is a locator, do not perform a local shutdown)
    if (!isDedicatedLocator()) {
      
      if (hadCache && dism != null) {
        AdminResponse response = null;
        try {
          prep.setSender(myId);
          prep.process(dism);
          response = prep.createResponse(dism);
        } catch (Exception ex) {
          if (dm.getLoggerI18n().fineEnabled()) {
            dm.getLoggerI18n().fine("caught exception while processing shutdownAll locally", ex);
          }
          response = AdminFailureResponse.create(dism, myId, ex); 
        }
        response.setSender(myId);
        rp.process(response);
      }
    }
    // if something wrong in closing gatewayhubs, we will keep shutdownall
    try {
      rp.waitForReplies(timeout);
    } catch (ReplyException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    // now do shutdownall
    // recipients = dm.getOtherNormalDistributionManagerIds();
    setRecipients(recipients);

    ShutDownAllReplyProcessor replyProcessor = new ShutDownAllReplyProcessor(dm, recipients);
    this.msgId = replyProcessor.getProcessorId();
    dm.putOutgoing(this);
    
    if (!isDedicatedLocator()) {
      if (hadCache && dism != null) {
        AdminResponse response;
        try {
          setSender(myId);
          // self-shutdown will happen at the end
          response = createResponse(dism, false);
        } catch (Exception ex) {
          if (dm.getLoggerI18n().fineEnabled()) {
            dm.getLoggerI18n().fine("caught exception while processing shutdownAll locally", ex);
          }
          response = AdminFailureResponse.create(dism, myId, ex); 
        }
        response.setSender(myId);
        replyProcessor.process(response);
      }
    }
    
    try {
      if(!replyProcessor.waitForReplies(timeout)) {
        return null;
      }
    } catch (ReplyException e) {
      if(!(e.getCause() instanceof CancelException)) {
        e.handleAsUnexpected();
      }
    } catch (InterruptedException e) {
      interrupted = true;
    }

    // shut down self in the end
    try {
      shutdownSelf(dm, hadCache);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    if (interrupted) {
      Thread.currentThread().interrupt();
    }

    try {
      Thread.sleep(3*SLEEP_TIME_BEFORE_DISCONNECT_DS);
    } catch (InterruptedException e) {
    }
    return replyProcessor.getResults();
  }

  protected void shutdownSelf(DM dm, boolean hadCache) throws Exception {
    // wait until all the recipients send response, shut down itself (if not a
    // locator)
    if (hadCache) {
      // at this point,GemFireCacheImpl.getInstance() might return null, 
      // because the cache is closed at GemFireCacheImpl.getInstance().shutDownAll() 
      if (!isDedicatedLocator()) {
        InternalDistributedSystem ids = dm.getSystem();
        if (ids.isConnected()) {
          ids.disconnect();
        }
      }
    }
  }

  /**
   * Returns true if this node is a dedicated locator. Overridden in GemFireXD
   * since it has different path for locators.
   */
  protected boolean isDedicatedLocator() {
    return InternalLocator.isDedicatedLocator();
  }

  @Override
  public boolean sendViaJGroups() {
    return true;
  }

  @Override
  protected void process(DistributionManager dm) {
    boolean isToShutdown = hasCache();
    super.process(dm);

    if (isToShutdown) {
      // Do the disconnect in an async thread. The thread we are running
      // in is one in the dm threadPool so we do not want to call disconnect
      // from this thread because it prevents dm from cleaning up all its threads
      // and causes a 20 second delay.
      final InternalDistributedSystem ids = dm.getSystem();
      if (ids.isConnected()) {
        Thread t = new Thread(new Runnable() {
          public void run() {
            try {
              Thread.sleep(SLEEP_TIME_BEFORE_DISCONNECT_DS);
            } catch (InterruptedException e) {
            }
            ConnectionTable.threadWantsSharedResources();
            if (ids.isConnected()) {
              ids.disconnect();
            }
          }
        });
        t.start();
      }
    }
  }

  protected static boolean hasCache() {
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if(cache != null && !cache.isClosed()) {
      return true;
    } else {
      return false;
    }
  }

  protected void processShutDown(DistributionManager dm) throws Exception{
    GemFireCacheImpl.getInstance().shutDownAll();
  }

  @Override
  protected AdminResponse createResponse(DistributionManager dm) {
    return createResponse(dm, true);
  }

  private AdminResponse createResponse(DistributionManager dm,
      boolean shutdownNow) {
    boolean isToShutdown = hasCache() && shutdownNow;
    boolean isSuccess = false;
    if (isToShutdown) {
      try {
//        Iterator itor = GemFireCacheImpl.getInstance().getCacheServers().iterator();
//        while (itor.hasNext()) {
//          CacheServer cs = (CacheServer)itor.next();
//          if (!cs.isRunning()) {
//            dm.getSystem().getLogWriter().info("ShutdownAll found cache server not running. Disconnecting from distributed system.");
//            return null;
//          }
//        }
        
        processShutDown(dm);
        isSuccess = true;
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
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
        
        if (t instanceof InternalGemFireError) {
          dm.getSystem().getLogWriter().severe("DistributedSystem is closed due to InternalGemFireError", t);
        } else {
          dm.getSystem().getLogWriter().severe("DistributedSystem is closed due to unexpected exception", t);
        }
      } finally {
        if (!isSuccess) {
          InternalDistributedMember me = dm.getDistributionManagerId();
          InternalDistributedSystem ids = dm.getSystem();
          if (!this.getSender().equals(me)) {  
            if (ids.isConnected()) {
              ids.getLogWriter().severe("ShutdownAllRequest: disconnect distributed without response.");
              ids.disconnect();
            }
          }
        }
      }
    }

    return new ShutdownAllResponse(this.getSender(), isToShutdown);
  }

  public int getDSFID() {
    return SHUTDOWN_ALL_REQUEST;
  }
  
  @Override
  public void fromData(DataInput in) throws IOException,ClassNotFoundException {
    super.fromData(in);
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
  }

  @Override  
  public String toString() {
    return "ShutdownAllRequest sent to " + Arrays.toString(this.getRecipients()) +
      " from " + this.getSender();
  }

  protected static class ShutDownAllReplyProcessor extends AdminMultipleReplyProcessor {
    Set results = Collections.synchronizedSet(new TreeSet());

    public ShutDownAllReplyProcessor(DM dm, Collection initMembers) {
      super(dm, initMembers);
    }

    @Override
    protected boolean stopBecauseOfExceptions() {
      return false;
    }

    /* 
     * If response arrives, we will save into results and keep wait for member's 
     * departure. If the member is departed before sent response, no wait
     * for its response
     * @see com.gemstone.gemfire.distributed.internal.ReplyProcessor21#process(com.gemstone.gemfire.distributed.internal.DistributionMessage)
     */
    @Override
    public void process(DistributionMessage msg) {
      LogWriterI18n log = InternalDistributedSystem.getLoggerI18n();
      if (log != null && log.fineEnabled()) {
        log.fine("shutdownAll reply processor is processing " + msg);
      }
      if(msg instanceof ShutdownAllResponse) {
        if (((ShutdownAllResponse)msg).isToShutDown()) {
          results.add(msg.getSender());
        }
        else {
          // for member without cache, we will not wait for its result
          // so no need to wait its DS to close either
          removeMember(msg.getSender(), false);
        }
        
        if (msg.getSender().equals(this.dmgr.getDistributionManagerId())) {
          // mark myself as done since my response has been sent and my DS
          // will be closed later anyway
          removeMember(msg.getSender(), false);
        }
      }
      
      if (msg instanceof ReplyMessage) {
        ReplyException ex = ((ReplyMessage)msg).getException();
        if (ex != null) {
          processException(msg, ex);
        }
      }

      checkIfDone();
    }

    public Set getResults() {
      return results;
    }
  }
}
