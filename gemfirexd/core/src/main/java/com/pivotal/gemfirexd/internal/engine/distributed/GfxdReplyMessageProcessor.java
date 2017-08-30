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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Implements the generic handling of replies for GFXD messages. This handles
 * exceptions from other nodes, waits for all replies even after receiving
 * exceptions and also tracks members that have thrown fatal exceptions.
 * 
 * @author swale
 */
public abstract class GfxdReplyMessageProcessor extends DirectReplyProcessor {

  private GfxdResponseCode responseCode;
  private DistributedMember responseMember;

  private HashMap<DistributedMember, ReplyException> exceptions;

  /** Temporary storage for first {@link ReplyProcessor21#exception}. */
  private volatile ReplyException firstReplyException;

  // -------------------------------------------------------------------------
  // Constructor
  // -------------------------------------------------------------------------
  public GfxdReplyMessageProcessor(final DM dm,
      final Set<DistributedMember> members, boolean register) {
    super(dm, dm.getSystem(), members, null);
    this.responseCode = GfxdResponseCode.GRANT(1);
    if (register) {
      super.register();
    }
  }

  public GfxdReplyMessageProcessor(final DM dm,
      final InternalDistributedMember member, boolean register) {
    super(dm, member);
    this.responseCode = GfxdResponseCode.GRANT(1);
    if (register) {
      super.register();
    }
  }

  /**
   * Return the set of granted members for the message and clear the processor
   * for further use if required.
   */
  public synchronized Set<DistributedMember> reset() {
    this.responseCode = GfxdResponseCode.GRANT(1);
    this.responseMember = null;
    this.exception = null;
    this.exceptions = null;
    return virtualReset();
  }

  protected abstract Set<DistributedMember> virtualReset();

  public final synchronized void setResponseCode(GfxdResponseCode code,
      DistributedMember member) {
    if (!this.responseCode.isException()) {
      if (code.isException() || code.isTimeout()) {
        this.responseCode = code;
        this.responseMember = member;
      }
      else if (code.isWaiting() && this.responseCode.isGrant()) {
        this.responseCode = code;
        this.responseMember = member;
      }
    }
  }

  public final GfxdResponseCode getResponseCode() {
    return this.responseCode;
  }

  public final DistributedMember getResponseMember() {
    return this.responseMember;
  }

  public final ReplyException getReplyException() {
    return this.exception;
  }

  private void setReplyException() {
    if (this.exception == null) {
      this.exception = this.firstReplyException;
    }
  }

  public final synchronized Map<DistributedMember, ReplyException>
      getReplyExceptions() {
    if (this.exceptions != null) {
      return new HashMap<DistributedMember, ReplyException>(this.exceptions);
    }
    return null;
  }

  protected synchronized void processExceptionFromMember(
      InternalDistributedMember member, ReplyException ex) {
    if (DistributionManager.VERBOSE | GemFireXDUtils.TraceFunctionException) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
          this.toString() + " processExceptionFromMember: for member " + member
              + " received exception " + ex);
    }
    // don't store the exception just yet before end of all processing else
    // super.process() will end the processing too soon before endResults is
    // invoked; we will set the exception back in postFinish
    final ReplyException replyEx = this.firstReplyException;
    if (replyEx == null // keep first exception in member
        // overwrite any old retry exception since it is not end-user exception
        || GemFireXDUtils.retryToBeDone(replyEx.getCause())) {
      this.firstReplyException = ex;
    }
    // set immediately for direct replies since those will be terminated
    // whenever an exception is encountered
    if (isExpectingDirectReply()) {
      setReplyException();
    }
    if (member != null) {
      // store all exceptions in the map
      if (this.exceptions == null) {
        this.exceptions = new HashMap<DistributedMember, ReplyException>();
      }
      this.exceptions.put(member, ex);
    }
  }

  @Override
  protected void postFinish() {
    super.postFinish();
    setReplyException();
  }

  /**
   * This is to avoid stopping the processing of messages if an exception is
   * received.
   */
  @Override
  protected final void processException(DistributionMessage msg,
      ReplyException ex) {
    processExceptionFromMember(msg.getSender(), ex);
  }

  /**
   * This is to avoid stopping the processing of messages if an exception is
   * received.
   */
  @Override
  protected final void processException(ReplyException ex) {
    processExceptionFromMember(ex.getSender(), ex);
  }

  /**
   * Control of reply processor waiting behavior in the face of exceptions.
   * 
   * @return true to stop waiting when exceptions are present
   */
  @Override
  protected boolean stopBecauseOfExceptions() {
    // for GFXD do not stop because of exceptions since we still need to
    // wait for all nodes to complete processing
    return false;
  }

  @Override
  protected final boolean stillWaiting() {
    if (isExpectingDirectReply()) {
      return false;
    }
    if (this.shutdown) {
      // Create the exception here, so that the call stack reflects the
      // failed computation. If you set the exception in onShutdown,
      // the resulting stack is not of interest.
      ReplyException re = new ReplyException(
          new DistributedSystemDisconnectedException(
              LocalizedStrings.ReplyProcessor21_ABORTED_DUE_TO_SHUTDOWN
                  .toLocalizedString()));
      this.exception = re;
      return false;
    }

    // All else is good, keep waiting if we have members to wait on.
    return numMembers() > 0;
  }

  @Override
  protected void checkIfDone() {
    // need to call finished in every case for ResultCollectors to proceed
    if (!stillWaiting()) {
      finished();
    }
  }
}
