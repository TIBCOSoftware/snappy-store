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

package com.gemstone.gemfire.distributed.internal;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.AbstractOperationMessage;
import com.gemstone.gemfire.internal.cache.TXChanges;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * A message that acknowledges that an operation completed
 * successfully, or threw a CacheException.  Note that even though
 * this message has a <code>processorId</code>, it is not a {@link
 * MessageWithReply} because it is sent in <b>reply</b> to another
 * message. 
 *
 * @author ericz
 *
 */
public class ReplyMessage extends HighPriorityDistributionMessage  {

  /** The shared obj id of the ReplyProcessor */
  protected int processorId;

  private transient final Throwable tranEx;

  protected boolean ignored = false;

  protected boolean closed = false;

  protected TXChanges txChanges;

  private boolean returnValueIsException;

  private Object returnValue;

  private transient boolean sendViaJGroups;

  protected transient boolean internal;

  protected transient short flags;

  public ReplyMessage() {
    this.tranEx = null;
  }

  /**
   * @param srcMessage
   *          the source message received from the sender; used for TXState
   *          adjustments as required before sending the reply
   * @param sendTXChanges
   *          true if the region changes and other flags in the TXState have to
   *          be sent back to the sender and false otherwise (e.g. if this is a
   *          secondary copy for the case of put in multiple nodes for a key)
   * @param finishTXRead
   *          if set to true then read on TXState will be marked as done and any
   *          necessary cleanup performed
   */
  protected ReplyMessage(final AbstractOperationMessage srcMessage,
      final boolean sendTXChanges, final boolean finishTXRead) {
    // those using proxy will handle their own flush, but for other cases
    // flush the ops explicitly on remote nodes
    this(srcMessage, sendTXChanges, finishTXRead, sendTXChanges
        && srcMessage != null && !srcMessage.useTransactionProxy());
  }

  /**
   * @param srcMessage
   *          the source message received from the sender; used for TXState
   *          adjustments as required before sending the reply
   * @param sendTXChanges
   *          true if the region changes and other flags in the TXState have to
   *          be sent back to the sender and false otherwise (e.g. if this is a
   *          secondary copy for the case of put in multiple nodes for a key)
   * @param finishTXRead
   *          if set to true then read on TXState will be marked as done and any
   *          necessary cleanup performed
   * @param flushPendingOps
   *          if set to true then any batched ops in current TX context will be
   *          flushed to other recipients before sending the reply
   */
  protected ReplyMessage(final AbstractOperationMessage srcMessage,
      final boolean sendTXChanges, final boolean finishTXRead,
      final boolean flushPendingOps) {
    this.timeStatsEnabled = srcMessage != null ? srcMessage.timeStatsEnabled
        : DistributionStats.enableClockStats;
    Throwable ex = null;
    TXStateProxy proxy = null;
    try {
      proxy = finishTX(srcMessage, sendTXChanges, finishTXRead);
      if (proxy != null && flushPendingOps
          && !proxy.skipBatchFlushOnCoordinator()) {
        proxy.flushPendingOps(null);
      }
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
      ex = t;
      this.returnValue = new ReplyException(t);
      this.returnValueIsException = true;
      // set in TXChanges
      if (this.txChanges != null && proxy != null
          && this.txChanges.getTXException() == null) {
        this.txChanges.setTXException(proxy.getTXInconsistent());
      }
    }
    this.tranEx = ex;
  }

  protected final TXStateProxy finishTX(
      final AbstractOperationMessage srcMessage, final boolean sendTXChanges,
      final boolean finishTXRead) {
    TXStateProxy txProxy = null;
    if (srcMessage != null && (txProxy = srcMessage.getTXProxy()) != null) {
      if (sendTXChanges) {
        this.txChanges = TXChanges.fromMessage(srcMessage, txProxy);
      }
      if (finishTXRead && srcMessage.finishTXProxyRead() && !txProxy.isSnapshot()) {
        // in case there is nothing in the TXStateProxy then get rid of it
        // so that commit/rollback will not be required for this node;
        // this is now a requirement since commit/rollback targets only the
        // nodes that host one of the affected regions only, so if nothing
        // was affected on this node then no commit/rollback may be received
        txProxy.removeSelfFromHostedIfEmpty(null);
      }
    }
    return txProxy;
  }

  public void setProcessorId(int id) {
    this.processorId = id;
  }

  @Override
  public boolean sendViaJGroups() {
    return this.sendViaJGroups;
  }
  
  public void setException(ReplyException ex) {
    if (this.tranEx == null) {
      this.returnValue = ex;
      this.returnValueIsException = ex != null;
    }
    else {
      // don't overwrite an existing transaction exception raised during flush
      if (ex != null && !(this.tranEx instanceof TransactionException)) {
        this.returnValue = ex;
        this.returnValueIsException = true;
      }
    }
  }

  public void setReturnValue(Object o) {
    this.returnValue = o;
    this.returnValueIsException = false;
  }
  
  /** ReplyMessages are always processed in-line, though subclasses are not */
  protected boolean getMessageInlineProcess() {
    return this.getClass().equals(ReplyMessage.class);
  }

  @Override  
  public boolean getInlineProcess() {
    // if TXChanges is present then don't process inline else it may deadlock
    return this.txChanges == null ? getMessageInlineProcess() : false;
  }

  /** Send an ack for given source message. */
  public static void send(InternalDistributedMember recipient, int processorId,
      ReplyException exception, ReplySender dm,
      AbstractOperationMessage sourceMessage) {
    send(recipient, processorId, exception, dm, sourceMessage, false);
  }

  /** Send an ack for given source message. */
  public static void send(InternalDistributedMember recipient, int processorId, 
                          ReplyException exception,
                          ReplySender dm,
                          AbstractOperationMessage sourceMessage,
                          boolean internal) {
    // those using proxy will handle their own flush, but for other cases
    // flush the ops explicitly on remote nodes
    send(recipient, processorId, exception, dm, sourceMessage, internal,
        sourceMessage != null && !sourceMessage.useTransactionProxy());
  }

  /** Send an ack */
  public static void send(InternalDistributedMember recipient, int processorId, 
                          ReplyException exception,
                          ReplySender dm,
                          AbstractOperationMessage sourceMessage,
                          boolean internal,
                          boolean flushPendingOps) {
    Assert.assertTrue(recipient != null, "Sending a ReplyMessage to ALL");
    ReplyMessage m = new ReplyMessage(sourceMessage, true, true,
        flushPendingOps);

    m.processorId = processorId;
    m.setException(exception);
    if (exception != null && dm.getLoggerI18n().fineEnabled()) {
      if (exception.getCause() != null && (exception.getCause() instanceof EntryNotFoundException)) {
        dm.getLoggerI18n().fine("Replying with entry-not-found: " + exception.getCause().getMessage());
      } else if (exception.getCause() != null && (exception.getCause() instanceof ConcurrentCacheModificationException)) {
        dm.getLoggerI18n().fine("Replying with concurrent-modification-exception");
      } else {
        dm.getLoggerI18n().fine("Replying with exception: " + m, exception);
      }
    }
    m.setRecipient(recipient);
    dm.putOutgoing(m);
  }

  /** Send an ack */
  public static void send(InternalDistributedMember recipient, int processorId, 
                          Object returnValue,
                          ReplySender dm,
                          AbstractOperationMessage sourceMessage) {
    Assert.assertTrue(recipient != null, "Sending a ReplyMessage to ALL");
    ReplyMessage m = new ReplyMessage(sourceMessage, true, true);

    m.processorId = processorId;
    if (returnValue != null) {
      m.returnValue = returnValue;
      m.returnValueIsException = false;
    }
    m.setRecipient(recipient);
    dm.putOutgoing(m);
  }

  public static void send(InternalDistributedMember recipient, int processorId, 
                          ReplyException exception,
                          ReplySender dm,
                          boolean ignored,
                          boolean closed,
                          AbstractOperationMessage sourceMessage,
                          boolean sendViaJGroups) {
    send(recipient, processorId, exception, dm, ignored, false,
        sourceMessage, sendViaJGroups, false);
  }

  public static void send(InternalDistributedMember recipient, int processorId, 
                          ReplyException exception,
                          ReplySender dm,
                          boolean ignored,
                          boolean closed, 
                          AbstractOperationMessage sourceMessage,
                          boolean sendViaJGroups,
                          boolean internal) {
    Assert.assertTrue(recipient != null, "Sending a ReplyMessage to ALL");
    ReplyMessage m = new ReplyMessage(sourceMessage, true, true);

    m.processorId = processorId;
    m.ignored = ignored;
    m.setException(exception);
    m.closed = closed;
    m.sendViaJGroups = sendViaJGroups;
    if (dm.getLoggerI18n().fineEnabled()) {
      if (exception != null && ignored) {
        if (exception.getCause() instanceof InvalidDeltaException) {
          dm.getLoggerI18n().fine("Replying with invalid-delta: " + exception.getCause().getMessage());
        } else {
          dm.getLoggerI18n().fine("Replying with ignored=true and exception: " + m, exception);
        }
      }
      else if (exception != null) {
        if (exception.getCause() != null && (exception.getCause() instanceof EntryNotFoundException)) {
          dm.getLoggerI18n().fine("Replying with entry-not-found: " + exception.getCause().getMessage());
        } else if (exception.getCause() != null && (exception.getCause() instanceof ConcurrentCacheModificationException)) {
          dm.getLoggerI18n().fine("Replying with concurrent-modification-exception");
        } else {
          dm.getLoggerI18n().fine("Replying with exception: " + m, exception);
        }
      }
      else if (ignored) {
       dm.getLoggerI18n().fine("Replying with ignored=true: " + m);
      }
    }
    m.setRecipient(recipient);
    dm.putOutgoing(m);
  }


  /**
   * Processes this message.  This method is invoked by the receiver
   * of the message if the message is not direct ack. If the message
   * is a direct ack, the process(dm, ReplyProcessor) method is invoked instead.
   * @param dm the distribution manager that is processing the message.
   */
  @Override  
  protected final void process(final DistributionManager dm) {
    dmProcess(dm);
  }

  public final void dmProcess(final DM dm) {
    // TODO: because startTime uses getTimeStamp replyMessageTime
    // ends up measuring both messageProcessingScheduleTime and
    // processedMessagesTime. I'm not sure this was intended.
    // I've added this info to the stat description so update it
    // if the startTime changes.
    final long startTime = getTimestamp();
    ReplyProcessor21 processor = ReplyProcessor21.getProcessor(processorId);
    try {
      this.process(dm, processor);

      if (DistributionManager.VERBOSE) {
        LogWriterI18n logger = dm.getLoggerI18n();
        logger.info(LocalizedStrings.ReplyMessage_0__PROCESSED__1,
            new Object[] {processor, this});
      }
      if (this.timeStatsEnabled) {
        dm.getStats().incReplyMessageTime(
            DistributionStats.getStatTimeNoCheck() - startTime);
      }
    } catch (RuntimeException ex) {
      if (processor != null) {
        processor.cancel(getSender(), ex);
      }
      throw ex;
    }
  }

  /**
   * @param dm 
   * @param processor
   */
  public void process(final DM dm, ReplyProcessor21 processor) {
//    if (dm.getLoggerI18n().fineEnabled()) {
//      dm.getLoggerI18n().fine("processing " + this + " processor=" + processor);
//    }
    if (processor == null) return;
    processor.process(ReplyMessage.this);
  }
  
  public Object getReturnValue() {
    if (!this.returnValueIsException) {
      return this.returnValue;
    } else {
      return null;
    }
  }

  public final ReplyException getException() {
    if (this.returnValueIsException) {
      ReplyException exception = (ReplyException)this.returnValue;
      if (exception != null) {
        InternalDistributedMember sendr = getSender();
        if (sendr != null) {
          exception.setSenderIfNull(sendr);
        }
      }
      return exception;
    } else {
      return null;
    }
  }

  public final void processRemoteTXChanges() {
    if (this.txChanges != null) {
      this.txChanges.applyLocally(getSender());
    }
  }

  public boolean getIgnored() {
    return this.ignored;
  }
  
  public boolean getClosed() {
    return this.closed;
  }

  //////////////////////  Utility Methods  //////////////////////

  public int getDSFID() {
    return REPLY_MESSAGE;
  }

  // 16 bits in a short

  // keeping this consistent with HAS_PROCESSOR_ID in PartitionMessage etc
  public static final short PROCESSOR_ID_FLAG = 0x01;
  public static final short IGNORED_FLAG = (PROCESSOR_ID_FLAG << 1);
  public static final short EXCEPTION_FLAG = (IGNORED_FLAG << 1);
  public static final short CLOSED_FLAG = (EXCEPTION_FLAG << 1);
  public static final short HAS_TX_CHANGES = (CLOSED_FLAG << 1);
  public static final short TIME_STATS_SET = (HAS_TX_CHANGES << 1);
  public static final short OBJECT_FLAG = (TIME_STATS_SET << 1);
  public static final short INTERNAL_FLAG = (OBJECT_FLAG << 1);

  protected static final boolean testFlag(short status, short flag) {
    return (status & flag) != 0;
  }

  @Override  
  public void toData(DataOutput out) throws IOException {
    super.toData(out);

    short status = 0;
    if (this.ignored) {
      status |= IGNORED_FLAG;
    }
    if (this.returnValueIsException) {
      status |= EXCEPTION_FLAG;
    }
    else if (this.returnValue != null) {
      status |= OBJECT_FLAG;
    }
    if (this.processorId != 0) {
      status |= PROCESSOR_ID_FLAG;
    }
    if (this.closed) {
      status |= CLOSED_FLAG;
    }
    if (this.txChanges != null) {
      status |= HAS_TX_CHANGES;
    }
    if (this.timeStatsEnabled) {
      status |= TIME_STATS_SET;
    }
    if (this.internal) {
      status |= INTERNAL_FLAG;
    }
    status = computeCompressedShort(status);
    out.writeShort(this.flags = status);

    if (this.processorId != 0) {
      out.writeInt(processorId);
    }
    if (this.returnValueIsException || this.returnValue != null) {
      DataSerializer.writeObject(this.returnValue, out);
    }
    if (this.txChanges != null) {
      this.txChanges.toData(out);
    }
  }

  /** override to add additional flags */
  protected short computeCompressedShort(short flags) {
    return flags;
  }

  @Override
  public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
    super.fromData(in);
    short status = in.readShort();
    this.timeStatsEnabled = testFlag(status,TIME_STATS_SET);
    if(timeStatsEnabled) {
      timeStamp = DistributionStats.getStatTimeNoCheck();
    }
    this.ignored = testFlag(status,IGNORED_FLAG);
    this.closed = testFlag(status,CLOSED_FLAG);
    if (testFlag(status,PROCESSOR_ID_FLAG)) {
      this.processorId = in.readInt();
      // set the processor ID so that any deserialization exceptions can be
      // sent back to the source
      // NIO/IO readers reset the RPId before and after reading, so no need
      // to set to zero if there is no processorId
      ReplyProcessor21.setMessageRPId(this.processorId);
    }
    if (testFlag(status, EXCEPTION_FLAG)) {
      this.returnValue = DataSerializer.readObject(in);
      this.returnValueIsException = true;
    }
    else if (testFlag(status, OBJECT_FLAG)) {
      this.returnValue = DataSerializer.readObject(in);
    }
    if (testFlag(status, HAS_TX_CHANGES)) {
      this.txChanges = TXChanges.fromData(in);
    }
    this.internal = testFlag(status, INTERNAL_FLAG);
    this.flags = status;
  }

  protected StringBuilder getStringBuilder() {
    StringBuilder sb = new StringBuilder();
    sb.append(getShortClassName());
    sb.append(" processorId=");
    sb.append(this.processorId);
    if (this.txChanges != null) {
      sb.append(' ');
      this.txChanges.toString(sb);
    }
    if (this.flags != 0) {
      sb.append(";flags=0x").append(Integer.toHexString(this.flags));
    }
    sb.append(" from ");
    sb.append(this.getSender());
    ReplyException ex = getException();
    if (ex != null) {
      if (ex.getCause() != null && ex.getCause() instanceof InvalidDeltaException) {
        sb.append(" with request for full value");
      } else {
      sb.append(" with exception ");
      sb.append(ex);
    }
    }
    return sb;
  }
  
  @Override
  public boolean isInternal() {
    return this.internal;
  }

  @Override  
  public String toString() {
    return getStringBuilder().toString();
  }
}
