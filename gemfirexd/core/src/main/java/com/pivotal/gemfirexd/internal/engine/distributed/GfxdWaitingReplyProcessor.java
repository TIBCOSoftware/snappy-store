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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.util.Set;

import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.
    InternalDistributedMember;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCountDownLatch;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This class provides a {@link ReplyProcessor21} that keeps track of members
 * that have successfully executed the message, members that may still be in a
 * "waiting" state i.e. are waiting for processing to complete.
 * 
 * @author swale
 */
public final class GfxdWaitingReplyProcessor extends
    GfxdWaitingReplyProcessorBase {

  private final THashSet grantedMembers;

  private final boolean ignoreNodeDown;

  private final StoppableCountDownLatch waitLatch;

  public GfxdWaitingReplyProcessor(DM dm, Set<DistributedMember> members,
      boolean ignoreNodeDown, boolean useLatchForWaiters) {
    super(dm, members, true);
    this.grantedMembers = new THashSet();
    this.ignoreNodeDown = ignoreNodeDown;
    this.waitLatch = useLatchForWaiters ? new StoppableCountDownLatch(
        getDistributionManager().getCancelCriterion(), 1) : null;
  }

  @SuppressWarnings("unchecked")
  @Override
  protected Set<DistributedMember> virtualReset() {
    assert Thread.holdsLock(this);

    this.waitLatch.countDown();
    final THashSet members = new THashSet(this.grantedMembers.size());
    members.addAll(this.grantedMembers);
    this.grantedMembers.clear();
    return members;
  }

  @SuppressWarnings("unchecked")
  public final synchronized Set<DistributedMember> getGrantedMembers() {
    final THashSet members = new THashSet(this.grantedMembers.size());
    members.addAll(this.grantedMembers);
    return members;
  }

  public final synchronized boolean hasGrantedMembers() {
    return this.grantedMembers.size() > 0;
  }

  @Override
  protected final void addGrantedMember(final DistributedMember member) {
    assert Thread.holdsLock(this);

    this.grantedMembers.add(member);
  }

  public final StoppableCountDownLatch getWaitersLatch() {
    return this.waitLatch;
  }

  protected final void checkWaiters() {
    if (this.waitLatch == null) {
      return;
    }
    final int numMembers = numMembers();
    synchronized (this) {
      int numWaitingMembers = 0;
      if (this.waiters != null) {
        numWaitingMembers = this.waiters.size();
      }
      if (SanityManager.isFinerEnabled) {
        SanityManager.DEBUG_PRINT("finer:TRACE", this.toString()
            + "#checkWaiters: waitingMembers: " + numWaitingMembers
            + ", total members: " + numMembers);
      }
      if (numWaitingMembers >= numMembers) {
        this.waitLatch.countDown();
      }
    }
  }

  @Override
  public void process(final DistributionMessage msg) {
    boolean superProcess = true;
    boolean waiting = false;
    try {
      if (msg instanceof GfxdReplyMessage) {
        final GfxdReplyMessage reply = (GfxdReplyMessage)msg;
        GfxdResponseCode responseCode = reply.getResponseCode();
        waiting = processResponseCode(reply, responseCode);
        // do not remove from super.members for WAITING
        superProcess = !waiting;
        if (!responseCode.isGrant()) {
          // check for node down case
          final ReplyException replyEx;
          if (this.ignoreNodeDown && (replyEx = reply.getException()) != null
              && GemFireXDUtils.retryToBeDone(replyEx)) {
            final InternalDistributedMember member = reply.getSender();
            removeMember(member, true);
            if (GemFireXDUtils.TraceFunctionException) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
                  "GfxdWaitingReplyProcessor: ignoring node down for "
                      + member, replyEx);
            }
            superProcess = false;
            return;
          }
          setResponseCode(responseCode, reply.getSender());
          if (waiting) {
            checkWaiters();
          }
        }
      }
      else {
        final ReplyMessage reply = (ReplyMessage)msg;
        if (reply.getException() == null) {
          addGrantedMember(reply.getSender(), 1);
        }
      }
    } finally {
      if (superProcess) {
        super.process(msg);
      }
      if (DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery
          | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "Finished processing " + msg);
      }
    }
  }

  @Override
  protected void removeMemberFromLists(final InternalDistributedMember member,
      final boolean departed) {
    synchronized (this) {
      if (this.waiters != null) {
        this.waiters.remove(member);
      }
      if (departed) {
        this.grantedMembers.remove(member);
      }
    }
    checkWaiters();
  }

  @Override
  public String toString() {
    return super.toString() + " responseCode=" + getResponseCode() +
        " from " + getResponseMember();
  }
}
