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

import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

import java.io.*;
import java.util.*;

import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.SystemFailure;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.gemstone.gemfire.distributed.internal.deadlock.MessageDependencyMonitor;
import com.gemstone.gemfire.distributed.internal.membership.*;
import com.gemstone.gemfire.internal.sequencelog.MessageLogger;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.tcp.Connection;
import com.gemstone.gemfire.internal.tcp.ConnectionTable;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gemfire.internal.util.Breadcrumbs;

/**
 * <P>A <code>DistributionMessage</code> carries some piece of
 * information to a distribution manager.  </P>
 *
 * <P>Messages that don't have strict ordering requirements should extend
 * {@link com.gemstone.gemfire.distributed.internal.PooledDistributionMessage}.
 * Messages that must be processed serially in the order they were received
 * can extend
 * {@link com.gemstone.gemfire.distributed.internal.SerialDistributionMessage}.
 * To customize the sequentialness/thread requirements of a message, extend
 * DistributionMessage and implement getExecutor().</P>
 *
 * @author David Whitlock
 *
 */
public abstract class DistributionMessage
  implements DataSerializableFixedID, Cloneable {
  
  /** Indicates that a distribution message should be sent to all
   * other distribution managers. */
  public static final InternalDistributedMember ALL_RECIPIENTS = null;

  /** Static for zero recipients for the message */
  protected static final InternalDistributedMember[] ZERO_RECIPIENTS =
      new InternalDistributedMember[0];

  // common bitmask flags for different messages, in particular
  // PartitionMessages
  // other message types can reuse flags other than common ones
  // HAS_PROCESSOR_ID, HAS_TX_ID, ENABLE_TIMESTATS
  /**
   * Keep this compatible with the other GFE layer PROCESSOR_ID flags. In case
   * DSFID is not found, code relies on this to determine processorId to send
   * back the exception.
   */
  protected static final short HAS_PROCESSOR_ID =
      ReplyMessage.PROCESSOR_ID_FLAG;
  /** Flag set when this message carries a non-default {@link LockingPolicy}. */
  protected static final short HAS_LOCK_POLICY = (HAS_PROCESSOR_ID << 1);
  /** Flag set when this message carries a transactional context. */
  protected static final short HAS_TX_ID = (HAS_LOCK_POLICY << 1);
  /** Flag set when this message is a possible duplicate. */
  protected static final short POS_DUP = (HAS_TX_ID << 1);
  /** Indicate time statistics capturing as part of this message processing */
  protected static final short ENABLE_TIMESTATS = (POS_DUP << 1);
  /** If message sender has set the processor type to be used explicitly. */
  protected static final short HAS_PROCESSOR_TYPE = (ENABLE_TIMESTATS << 1);

  ///**
  // * Flag set when this message carries a transactional member in context.
  // * Note this is only used by old TX implementation while new distributed
  // * TX implementation uses the bitmask for {@link #HAS_LOCK_POLICY}.
  // */
  //private static final short HAS_TX_MEMBERID = 0x2;

  /** the unreserved flags start for child classes */
  protected static final short UNRESERVED_FLAGS_START =
    (HAS_PROCESSOR_TYPE << 1);

  ////////////////////  Instance Fields  ////////////////////

  /** The sender of this message */
  protected transient InternalDistributedMember sender;

  /** A set of recipients for this message, not serialized*/
  private transient InternalDistributedMember[] recipients = null;

  /** every AbstractOperationMessage tracking whether time statistics enabled or not. */
  protected transient boolean timeStatsEnabled;

  /** A timestamp, in nanos, associated with this message. Not serialized. */
  protected transient long timeStamp;

  /** The number of bytes used to read this message,  for statistics only */
  private transient int bytesRead = 0;

  /** true if message should be multicast; ignores recipients */
  private transient boolean multicast = false;

  /**
   * non-null if messageBeingReceived stats need decrementing when done with msg
   */
  private transient Connection messageReceiver;

  /**
   * True if this message has incremented (or will increment) the
   * messagesReceived count on the Connection set in {@link #messageReceiver},
   * and false if the caller should do that. Normally the counter is incremented
   * by the message itself if the message is not processed inline.
   */
  private transient boolean handleMessageReceived;

  /**
   * This field will be set if we can send a direct ack for
   * this message.
   */
  protected transient ReplySender acker = null;
  
  //////////////////////  Constructors  //////////////////////

  protected DistributionMessage() {
    this.timeStatsEnabled = DistributionStats.enableClockStats;
    if(this.timeStatsEnabled) {
      this.timeStamp = DistributionStats.getStatTimeNoCheck();
    }
  }

  //////////////////////  Static Helper Methods  //////////////////////

  /**
   * Get the next bit mask position while checking that the value should not
   * exceed maximum byte value.
   */
  protected static int getNextByteMask(final int mask) {
    return getNextBitMask(mask, (Byte.MAX_VALUE) + 1);
  }

  /**
   * Get the next bit mask position while checking that the value should not
   * exceed given maximum value.
   */
  protected static final int getNextBitMask(int mask, final int maxValue) {
    mask <<= 1;
    if (mask > maxValue) {
      Assert.fail("exhausted bit flags with all available bits: 0x"
          + Integer.toHexString(mask) + ", max: 0x"
          + Integer.toHexString(maxValue));
    }
    return mask;
  }

  public static final byte getNumBits(final int maxValue) {
    byte numBits = 1;
    while ((1 << numBits) <= maxValue) {
      numBits++;
    }
    return numBits;
  }

  //////////////////////  Instance Methods  //////////////////////

  public final void setHandleReceiverStats(Connection conn) {
    this.messageReceiver = conn;
  }

  public final boolean handleMessageReceived() {
    return this.handleMessageReceived;
  }

  public final void setReplySender(ReplySender acker) {
    this.acker = acker;
  }

  public final ReplySender getReplySender(DM dm) {
    if(acker != null) {
      return acker;
    } else {
      return dm;
    }
  } 
  
  public final boolean isDirectAck() {
    return acker != null;
  }

  /**
   * If true then this message most be sent on an ordered channel.
   * If false then it can be unordered.
   * @since 5.5 
   */
  public boolean orderedDelivery(boolean threadOwnsResources) {
    final int processorType = getProcessorType();
    switch (processorType) {
      case DistributionManager.SERIAL_EXECUTOR:
      case DistributionManager.PARTITIONED_REGION_EXECUTOR:
        return true;
      // need to use ordered connections for StateFlush when
      // constainsRegionContentChange is true
      case DistributionManager.REGION_FUNCTION_EXECUTION_EXECUTOR:
        // allow nested distributed functions to be executed from within the
        // execution of a function; this is required particularly for GemFireXD
        // TODO: this can later be adjusted to use a separate property
        // always make it ordered with threadOwnsResources else it uses
        // shared resources for unordered connections
        // return containsRegionContentChange();
      default:
        return threadOwnsResources || containsRegionContentChange();
    }
  }

  /**
   * Sets the intended recipient of the message.  If recipient is
   * {@link #ALL_RECIPIENTS} then the message will be sent to all
   * distribution managers.
   */
  public void setRecipient(InternalDistributedMember recipient) {
    if (this.recipients != null) {
       throw new IllegalStateException(LocalizedStrings.DistributionMessage_RECIPIENTS_CAN_ONLY_BE_SET_ONCE.toLocalizedString());
    }
    this.recipients = new InternalDistributedMember[] {recipient};
  }

  /**
   * Causes this message to be send using multicast if v is true.
   * @since 5.0
   */
  public void setMulticast(boolean v) {
    this.multicast = v;
  }
  /**
   * Return true if this message should be sent using multicast.
   * @since 5.0
   */
  public boolean getMulticast() {
    return this.multicast;
  }
  /**
   * Return true of this message should be sent through JGroups instead of the
   * direct-channel.  This is typically only done for messages that are
   * broadcast to the full membership set.
   */
  public boolean sendViaJGroups() {
    return false;
  }
  /**
   * Sets the intended recipient of the message.  If recipient set contains
   * {@link #ALL_RECIPIENTS} then the message will be sent to all
   * distribution managers.
   */
  public void setRecipients(Collection recipients) {
    if (this.recipients != null) {
       throw new IllegalStateException(LocalizedStrings.DistributionMessage_RECIPIENTS_CAN_ONLY_BE_SET_ONCE.toLocalizedString());
    }
    if (recipients.size() > 0) {
      this.recipients = (InternalDistributedMember[])recipients
          .toArray(new InternalDistributedMember[recipients.size()]);
    }
    else {
      this.recipients = ZERO_RECIPIENTS;
    }
  }

  public void resetRecipients() {
    this.recipients = null;
    this.multicast = false;
  }

  public Set getSuccessfulRecipients() {
    // note we can't use getRecipients() for plannedRecipients because it will
    // return ALL_RECIPIENTS if multicast
    // [sumedh] Changed to use THashSet below. Below does not look to be
    // returning successful recipients only. Method should be renamed to
    // getPlannedRecipients()?
    InternalDistributedMember[] plannedRecipients = this.recipients;
    if (plannedRecipients.length > 0) {
      THashSet successfulRecipients = new THashSet(plannedRecipients.length);
      for (int index = 0; index < plannedRecipients.length; index++) {
        successfulRecipients.add(plannedRecipients[index]);
      }
      return successfulRecipients;
    }
    else {
      return Collections.emptySet();
    }
  }

  /**
   * Returns the intended recipient(s) of this message.  If the message
   * is intended to delivered to all distribution managers, then
   * the array will contain ALL_RECIPIENTS.
   * If the recipients have not been set null is returned.
   */
  public InternalDistributedMember[] getRecipients() {
    if (this.multicast) {
      return new InternalDistributedMember[] {ALL_RECIPIENTS};
    }else if (this.recipients != null) {
      return this.recipients;
    } else {
      return new InternalDistributedMember[] {ALL_RECIPIENTS};
    }
  }
  /**
   * Returns true if message will be sent to everyone.
   */
  public boolean forAll() {
    return (this.recipients == null)
      || (this.multicast)
      || ((this.recipients.length > 0)
          && (this.recipients[0] == ALL_RECIPIENTS));
  }

  public String getRecipientsDescription() {
    if (this.recipients == null) {
      return "<recipients: ALL>";
    }
    else if (this.multicast) {
      return "<recipients: multcast>";
    } else if (this.recipients.length > 0 && this.recipients[0] == ALL_RECIPIENTS) {
      return "<recipients: ALL>";
    } else {
      StringBuilder sb = new StringBuilder(100);
      sb.append("<recipients: ");
      for (int i=0; i < this.recipients.length; i++) {
        if (i != 0) {
          sb.append(", ");
        }
        sb.append(this.recipients[i]);
      }
      sb.append(">");
      return sb.toString();
    }
  }
  /**
   * Returns the sender of this message.  Note that this value is not
   * set until this message is received by a distribution manager.
   */
  public InternalDistributedMember getSender() {
    return this.sender;
  }

  /**
   * Sets the sender of this message.  This method is only invoked
   * when the message is <B>received</B> by a
   * <code>DistributionManager</code>.
   */
  public void setSender(InternalDistributedMember _sender) {
    this.sender = _sender;
  }

  /**
   * Return the Executor in which to process this message.
   */
  protected Executor getExecutor(DistributionManager dm) {
    return dm.getExecutor(getProcessorType(), sender);
  }
  
//  private Executor getExecutor(DistributionManager dm, Class clazz) {
//    return dm.getExecutor(getProcessorType());
//  }

  public abstract int getProcessorType();

  public void setProcessorType(boolean isReaderThread) {
  }

  /**
   * Processes this message.  This method is invoked by the receiver
   * of the message.
   * @param dm the distribution manager that is processing the message.
   */
  protected abstract void process(DistributionManager dm);
  
  /**
   * Scheduled action to take when on this message when we are ready
   * to process it.
   */
  protected final void scheduleAction(final DistributionManager dm) {
    if (DistributionManager.VERBOSE || dm.logger.fineEnabled()) {
      dm.getLoggerI18n().info(
          LocalizedStrings.DEBUG,
          "Processing {" + this + "}");
    }
    String reason = dm.getCancelCriterion().cancelInProgress();
    if (reason != null) {
      // throw new ShutdownException(reason);
      if (DistributionManager.VERBOSE || dm.logger.fineEnabled()) {
        dm.logger.fine("scheduleAction: cancel in progress (" + reason 
            + "); skipping <" + this + ">");
      }
      return;
    }
    if(MessageLogger.isEnabled()) {
      MessageLogger.logMessage(this, getSender(),  dm.getDistributionManagerId());
    }
    MessageDependencyMonitor.processingMessage(this);
    long time = 0;
    if (this.timeStatsEnabled) {
      time = DistributionStats.getStatTimeNoCheck();
      dm.getStats().incMessageProcessingScheduleTime(time-getTimestamp());
    }
    setBreadcrumbsInReceiver();
    try {
      
      DistributionMessageObserver observer = DistributionMessageObserver.getInstance();
      if(observer != null) {
        observer.beforeProcessMessage(dm, this);
      }
      process(dm);
      if(observer != null) { 
        observer.afterProcessMessage(dm, this);
      }
    }
    catch (CancelException e) {
      if (dm.logger.fineEnabled()) {
        dm.logger.fine("Cancelled caught processing "
                       + this + ": "
                       + e.getMessage());
      }
    }
    catch (Throwable t) {
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
      dm.logger.severe(LocalizedStrings.DistributionMessage_UNCAUGHT_EXCEPTION_PROCESSING__0, this, t);
    }
    finally {
      if (this.messageReceiver != null) {
        dm.getStats().decMessagesBeingReceived(this.bytesRead);
      }
      dm.getStats().incProcessedMessages(1L);
      if (this.timeStatsEnabled) {
        dm.getStats().incProcessedMessagesTime(time);
      }
      Breadcrumbs.clearBreadcrumb();
      MessageDependencyMonitor.doneProcessing(this);
    }
  }

  /**
   * Schedule this message's process() method in a thread determined
   * by getExecutor()
   */
  public final void schedule(final DistributionManager dm) {
    boolean inlineProcess = DistributionManager.INLINE_PROCESS
      && getProcessorType() == DistributionManager.SERIAL_EXECUTOR
      && !isPreciousThread();

    inlineProcess |= this.getInlineProcess();
    inlineProcess |= ConnectionTable.isDominoThread();
    inlineProcess |= this.acker != null;

    if (inlineProcess) {
      dm.getStats().incNumSerialThreads(1);
      try {
        scheduleAction(dm);
      } finally {
        dm.getStats().incNumSerialThreads(-1);
      }
    } else { // not inline
      final Connection receiver = this.messageReceiver;
      this.handleMessageReceived = (receiver != null);
      try {
//        if (dm.logger.fineEnabled()) {
//          dm.logger.fine("adding message to executor " + getProcessorType());
//        }
        getExecutor(dm).execute(new SizeableRunnable(this.getBytesRead()) {
          public void run() {
            try {
              scheduleAction(dm);
            } finally {
              if (receiver != null && containsRegionContentChange()) {
                receiver.incMessagesReceived();
              }
            }
          }

          @Override
          public String toString() {
            return "Processing {" + DistributionMessage.this.toString() + "}";
          }
        });
      }
      catch (RejectedExecutionException ex) {
        if (receiver != null && containsRegionContentChange()) {
          receiver.incMessagesReceived();
        }
        if (!dm.shutdownInProgress()) { // fix for bug 32395
          dm.logger.warning(LocalizedStrings.DistributionMessage_0__SCHEDULE_REJECTED, this.toString(), ex);
        }
      }
      catch (Throwable t) {
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
        dm.logger.severe(LocalizedStrings.DistributionMessage_UNCAUGHT_EXCEPTION_PROCESSING__0, this, t);
        // I don't believe this ever happens (DJP May 2007)
        throw new InternalGemFireException(LocalizedStrings.DistributionMessage_UNEXPECTED_ERROR_SCHEDULING_MESSAGE.toLocalizedString(), t);
      }
    } // not inline
  }

  /**
   * returns true if the current thread should not be used for inline
   * processing.  i.e., it is a "precious" resource
   */
  public static boolean isPreciousThread() {
    String thrname = Thread.currentThread().getName();
    return thrname.startsWith("UDP");
  }


  /** most messages should not force in-line processing */
  public boolean getInlineProcess() {
    return false;
  }
  
  /**
   * sets the breadcrumbs for this message into the current thread's name
   */
  public void setBreadcrumbsInReceiver() {
    if (Breadcrumbs.ENABLED) { 
      String sender = null;
      String procId = "";
      long pid = getProcessorId();
      if (pid != 0) {
        procId = " processorId=" + pid;
      }
      if (Thread.currentThread().getName().startsWith("P2P Message Reader")) {
        sender = procId;
      }
      else {
        sender = "sender=" + getSender() + procId;
      }
      if (sender.length() > 0) {
        Breadcrumbs.setReceiveSide(sender);
      }
      Object evID = getEventID();
      if (evID != null) {
        Breadcrumbs.setEventId(evID);
      }
    }
  }
  
  /**
   * sets breadcrumbs in a thread that is sending a message to another member
   */
  public void setBreadcrumbsInSender() {
    if (Breadcrumbs.ENABLED) {
      String procId = "";
      long pid = getProcessorId();
      if (pid != 0) {
        procId = "processorId=" + pid;
      }
      if (this.recipients != null && this.recipients.length <= 10) { // set a limit on recipients
        Breadcrumbs.setSendSide(procId 
            + " recipients="+Arrays.toString(this.recipients));
      }
      else {
        if (procId.length() > 0) {
          Breadcrumbs.setSendSide(procId);
        }
      }
      Object evID = getEventID();
      if (evID != null) {
        Breadcrumbs.setEventId(evID);
      }
    }
  }
  
  public EventID getEventID() {
    return null;
  }
  
  /**
   * This method resets the state of this message, usually releasing
   * objects and resources it was using.  It is invoked after the
   * message has been sent.  Note that classes that override this
   * method should always invoke the inherited method
   * (<code>super.reset()</code>).
   */
  public void reset() {
    resetRecipients();
    this.sender = null;
  }

  /**
   * Writes the contents of this <code>DistributionMessage</code> to
   * the given output.
   * Note that classes that
   * override this method should always invoke the inherited method
   * (<code>super.toData()</code>).
   */
  public void toData(DataOutput out) throws IOException {
//     DataSerializer.writeObject(this.recipients, out); // no need to serialize; filled in later
    //((IpAddress)this.sender).toData(out); // no need to serialize; filled in later
    //out.writeLong(this.timeStamp);
  }

  /**
   * Reads the contents of this <code>DistributionMessage</code> from
   * the given input.
   * Note that classes that override this
   * method should always invoke the inherited method
   * (<code>super.fromData()</code>).
   */
  public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {

//     this.recipients = (Set)DataSerializer.readObject(in); // no to deserialize; filled in later
    // this.sender = DataSerializer.readIpAddress(in); // no to deserialize; filled in later
    // this.timeStamp = (long)in.readLong();
  }

  /**
   * Returns a timestamp, in nanos, associated with this message.
   */
  public long getTimestamp() {
    return timeStamp;
  }

  /**
   * Sets the timestamp of this message to the current time (in nanos).
   * @return the number of elapsed nanos since this message's last timestamp
   */
  public long resetTimestamp() {
    if (this.timeStatsEnabled) {
      long now = DistributionStats.getStatTimeNoCheck();
      long result = now - this.timeStamp;
      this.timeStamp = now;
      return result;
    } else {
      return 0;
    }
  }

  public void setBytesRead(int bytesRead)
  {
    this.bytesRead = bytesRead;
  }

  public int getBytesRead()
  {
    return bytesRead;
  }

  /**
   * 
   * @return null if message is not conflatable. Otherwise return
   * a key that can be used to identify the entry to conflate.
   * @since 4.2.2
   */
  public ConflationKey getConflationKey() {
    return null; // by default conflate nothing; override in subclasses
  }

  /**
   * @return the ID of the reply processor for this message, or zero if none
   * @since 5.7
   */
  public int getProcessorId() {
    return 0;
  }
  
  /**
   * Severe alert processing enables suspect processing at the ack-wait-threshold
   * and issuing of a severe alert at the end of the ack-severe-alert-threshold.
   * Some messages should not support this type of processing
   * (e.g., GII, or DLockRequests)
   * @return whether severe-alert processing may be performed on behalf
   * of this message
   */
  public boolean isSevereAlertCompatible() {
    return false;
  }
  
  /**
   * Returns true if the message is for internal-use such as a meta-data region.
   * 
   * @return true if the message is for internal-use such as a meta-data region
   * @since 7.0
   */
  public boolean isInternal() {
    return false;
  }
  
  /**
   * does this message carry state that will alter the content of
   * one or more cache regions?  This is used to track the
   * flight of content changes through communication channels
   */
  public boolean containsRegionContentChange() {
    return false;
  }

  /** returns the class name w/o package information. useful in logging */
  public String getShortClassName() {
    final Class<?> c = getClass();
    final String cname = c.getName();
    return cname.substring(c.getPackage().getName().length() + 1);
  }

  @Override
  public String toString() {
    String cname = getClass().getName().substring(
        getClass().getPackage().getName().length() + 1);
    final StringBuilder sb = new StringBuilder(cname);
    sb.append('@').append(Integer.toHexString(System.identityHashCode(this)));
    sb.append(" processorId=").append(getProcessorId());
    sb.append(" sender=").append(getSender());
    return sb.toString();
  }

  public Version[] getSerializationVersions() {
    return null;
  }
}
