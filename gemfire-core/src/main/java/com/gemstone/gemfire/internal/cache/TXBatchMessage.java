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

package com.gemstone.gemfire.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.ReplySender;
import com.gemstone.gemfire.distributed.internal.membership.
    InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.util.ArrayUtils;

/**
 * Send a batch message to nodes when flushing a batch of cached operations on
 * the coordinator. This message should not be used for processing on self
 * especially when providing a list of piggy-backed messages.
 * 
 * @author swale
 * @since 7.0
 */
public final class TXBatchMessage extends TXMessage {

  List<Object> pendingOps;
  private transient int initOffset;
  private transient int offset;
  private transient int chunkSize;

  private ArrayList<LocalRegion> pendingOpsRegions;
  private LocalRegion pendingOpsRegion;

  private List<AbstractOperationMessage> piggyBackedMessages;

  /**
   * set to true when SH/EX_SH locks should conflict with EX (see comments in
   * #44743)
   */
  boolean conflictWithEX;

  private transient ArrayList<DistributionMessage> piggyBackedReplies;

  private static final short HAS_PIGGYBACKED_MESSAGES = UNRESERVED_FLAGS_START;
  private static final short CONFLICT_WITH_EX = (HAS_PIGGYBACKED_MESSAGES << 1);
  private static final short HAS_COMMON_REGION = (CONFLICT_WITH_EX << 1);

  private static final byte ENTRY_BULK_OP_FLAG = 0x40;

  private static final byte CLEAR_ENTRY_BULK_OP_FLAG = ~ENTRY_BULK_OP_FLAG;

  /** for serialization */
  public TXBatchMessage() {
    this.pendingOps = Collections.emptyList();
  }

  TXBatchMessage(final TXStateInterface tx,
      final ReplyProcessor21 replyProcessor,
      final ArrayList<Object> pendingOps,
      final ArrayList<LocalRegion> pendingOpsRegions,
      final LocalRegion pendingOpsRegion,
      final List<AbstractOperationMessage> msgs,
      final boolean conflictWithEX) {
    super(tx, replyProcessor);
    init(pendingOps, 0, 0, pendingOpsRegions, pendingOpsRegion, msgs,
        conflictWithEX);
  }

  void init(final List<Object> pendingOps,
      final int offset, final int chunkSize,
      final ArrayList<LocalRegion> pendingOpsRegions,
      final LocalRegion pendingOpsRegion,
      final List<AbstractOperationMessage> msgs,
      final boolean conflictWithEX) {
    this.pendingOps = pendingOps;
    this.initOffset = offset;
    this.offset = 0;
    this.chunkSize = chunkSize;
    this.pendingOpsRegions = pendingOpsRegions;
    this.pendingOpsRegion = pendingOpsRegion;
    this.piggyBackedMessages = msgs;
    this.conflictWithEX = conflictWithEX;
  }

  @Override
  protected boolean sendPendingTXId() {
    return false;
  }

  /**
   * @see TXMessage#operateOnTX(TXStateProxy, DistributionManager)
   */
  @Override
  protected final boolean operateOnTX(final TXStateProxy tx,
      final DistributionManager dm) {
    apply(tx);
    return true;
  }

  final void apply(final TXStateProxy tx) {
    // check for closing cache before returning but this should be done
    // only after entire message has been read (SNAP-1488)
    GemFireCacheImpl.getExisting().getCancelCriterion()
        .checkCancelInProgress(null);
    if (tx != null) {
      final TXState txState = tx.getTXStateForWrite();
      // reusable EntryEvent
      final EntryEventImpl eventTemplate = EntryEventImpl.create(null,
          Operation.UPDATE, null, null, null, true, null);
      eventTemplate.setTXState(txState);
      // apply as PUT DML so duplicate entry inserts etc. will go through fine
      eventTemplate.setPutDML(true);
      Object entry;
      TXRegionState txrs;
      LocalRegion region, baseRegion;
      final int lockFlags = this.conflictWithEX
          ? ExclusiveSharedSynchronizer.CONFLICT_WITH_EX : 0;
      final LocalRegion pendingOpsRegion = this.pendingOpsRegion;
      try {
        if (pendingOpsRegion != null) {
          region = pendingOpsRegion;
          baseRegion = pendingOpsRegion.isUsedForPartitionedRegionBucket()
              ? pendingOpsRegion.getPartitionedRegion() : pendingOpsRegion;
        }
        else {
          region = baseRegion = null;
        }
        final int numOps = this.pendingOps.size();
        // take pendingTXRegionStates lock first so that
        // GII thread doesn't block on TXRegionState.
        //ArrayList<LocalRegion> lockedRegions = new ArrayList<>();
        /*for (LocalRegion r : pendingOpsRegions) {
          if (!r.isInitialized() && r.getImageState().lockPendingTXRegionStates(true, false)) {
            lockedRegions.add(r);
          }
        }*/
        boolean lockedPendingTXregionState = false;
        for (int index = 0; index < numOps; index++) {
          entry = this.pendingOps.get(index);
          if (pendingOpsRegion == null) {
            region = this.pendingOpsRegions.get(index);
            if (region.isUsedForPartitionedRegionBucket()) {
              baseRegion = region.getPartitionedRegion();
            } else {
              baseRegion = region;
            }
          }
          if (txState.isCoordinator()) {
            region.waitForData();
          }
          if (!region.isInitialized() &&
              region.getImageState().lockPendingTXRegionStates(true, false)) {
            //lockedRegions.add(region);
            lockedPendingTXregionState = true;
          }
          try {
            txrs = txState.writeRegion(region);
            if (txrs != null) {
              txState.applyPendingOperation(entry, lockFlags, txrs, region,
                  baseRegion, eventTemplate, true, Boolean.TRUE, this);
            }
          } finally {
            //for (LocalRegion r : lockedRegions) {
            if (lockedPendingTXregionState) {
              region.getImageState().unlockPendingTXRegionStates(true);
              lockedPendingTXregionState = false;
            }
          }
        }
      } finally {
        eventTemplate.release();
      }
    }
  }

  @Override
  protected final void postOperateOnTX(TXStateProxy tx,
      final DistributionManager dm) {
    // now execute the post operation messages, if any
    final int size;
    if (this.piggyBackedMessages != null
        && (size = this.piggyBackedMessages.size()) > 0) {
      this.piggyBackedReplies = new ArrayList<DistributionMessage>(size);
      final TXBatchOtherReplySender replySender = new TXBatchOtherReplySender(
          dm.getLoggerI18n());
      AbstractOperationMessage msg;
      for (int index = 0; index < size; index++) {
        msg = this.piggyBackedMessages.get(index);
        msg.setSender(getSender());
        msg.setReplySender(replySender);
        // setting reply sender above causes inline processing of message below
        msg.schedule(dm);
      }
    }
  }

  @Override
  protected void sendReply(InternalDistributedMember recipient,
      int processorId, DistributionManager dm, ReplyException rex, TXStateProxy proxy) {
    if (processorId != 0) {
      TXBatchReply.send(recipient, processorId, rex, dm, this);
    }
  }

  public static TXBatchResponse send(final DM dm,
      final InternalDistributedMember recipient,
      final TXStateInterface tx,
      final TXManagerImpl.TXContext context,
      final ArrayList<Object> pendingOps,
      final ArrayList<LocalRegion> pendingOpsRegions,
      final List<AbstractOperationMessage> postMessages,
      final boolean conflictWithEX) {
    if (postMessages != null) {
      // all replies will also be batched up with this message's reply, so
      // should not have registered ReplyProcessors
      AbstractOperationMessage msg;
      for (int index = 0; index < postMessages.size(); index++) {
        msg = postMessages.get(index);
        Assert.assertTrue(msg.processorId == 0, "unexpected non-zero "
            + "processor ID in TXBatchMessage for piggy-backed message " + msg
            + " for " + tx);
      }
    }
    final TXBatchResponse response = new TXBatchResponse(dm, recipient,
        postMessages);
    final TXBatchMessage msg = new TXBatchMessage(tx, response, pendingOps,
        pendingOpsRegions, null, postMessages, conflictWithEX);
    msg.initPendingTXId(context, tx);
    msg.setRecipient(recipient);
    dm.putOutgoing(msg);
    return response;
  }

  @Override
  public boolean containsRegionContentChange() {
    // for TX state flush
    return true;
  }

  @Override
  public final boolean canStartRemoteTransaction() {
    return true;
  }

  @Override
  public boolean useTransactionProxy() {
    // though not directly used, it allows affected regions to be added to the
    // list in TXStateProxy so that those can be shipped back in TXGetChanges
    // correctly if required
    return true;
  }

  /**
   * @see com.gemstone.gemfire.internal.DataSerializableFixedID#getDSFID()
   */
  public int getDSFID() {
    return TX_BATCH_MESSAGE;
  }

  public final int getCount() {
    return this.offset == 0 ? (this.pendingOps.size() - this.initOffset)
        : (this.offset - this.initOffset);
  }

  public final int getOffset() {
    return this.offset;
  }

  @Override
  public void toData(DataOutput out)
          throws IOException {
    super.toData(out);
    // first write the piggybacked extra messages being carried, if any
    if (this.piggyBackedMessages != null) {
      final int size = this.piggyBackedMessages.size();
      AbstractOperationMessage msg;
      InternalDataSerializer.writeArrayLength(size, out);
      for (int index = 0; index < this.piggyBackedMessages.size(); index++) {
        msg = this.piggyBackedMessages.get(index);
        DataSerializer.writeObject(msg, out);
      }
    }

    LocalRegion dataRegion;
    byte destroyAndBulkOp;
    final int size = this.pendingOps.size();
    Object entry;
    AbstractRegionEntry re;
    TXEntryState txes;

    // write the probable number of ops first (can be different for chunking
    // case so terminating OP_FLAG_EOF should be used as the actual termination)
    InternalDataSerializer.writeArrayLength(size - this.initOffset, out);

    final LogWriterI18n logger = GemFireCacheImpl.getExisting().getLoggerI18n();
    final boolean finerEnabled = logger.finerEnabled();
    final LocalRegion pendingOpsRegion = this.pendingOpsRegion;
    final RegionInfoShip regionInfo = new RegionInfoShip();
    if (pendingOpsRegion != null) {
      // serialize single region once
      regionInfo.init(pendingOpsRegion);
      InternalDataSerializer.invokeToData(regionInfo, out);
      dataRegion = pendingOpsRegion;
    }
    else {
      // region will be written per entry
      dataRegion = null;
    }

    HeapDataOutputStream hdos = null;
    if (this.chunkSize > 0 && out instanceof HeapDataOutputStream) {
      hdos = (HeapDataOutputStream)out;
    }

    // the ArrayList is in reverse order from TXStateProxy
    this.offset = this.initOffset;
    while (this.offset < size) {
      final int index = this.offset;
      this.offset++;
      entry = this.pendingOps.get(index);
      if (entry instanceof TXEntryState) {
        txes = (TXEntryState)entry;
        out.writeByte(txes.op);

        if (pendingOpsRegion == null) {
          dataRegion = this.pendingOpsRegions.get(index);
          regionInfo.init(dataRegion);
          InternalDataSerializer.invokeToData(regionInfo, out);
        }

        destroyAndBulkOp = txes.destroy;
        if (txes.bulkOp) {
          destroyAndBulkOp |= ENTRY_BULK_OP_FLAG;
        }
        out.writeByte(destroyAndBulkOp);
        DataSerializer.writeObject(txes.regionKey, out);
        if (finerEnabled) {
          logger.finer("TXBatchMessage#toData: wasCreatedByTX="
              + txes.wasCreatedByTX() + " dataRegion="
              + dataRegion.getFullPath() + ", entry: " + txes.toString());
        }
        if (txes.isDirty()) {
          if (txes.isPendingFullValueForBatch()) {
            DataSerializer.writeObject(txes.getPendingValue(), out);
          }
          else {
            DataSerializer.writeObject(txes.pendingDelta, out);
          }
        }
        else {
          DataSerializer.writeObject(null, out);
        }
        DataSerializer.writeObject(txes.getCallbackArgument(), out);
      }
      else { // read locked entries
        re = (AbstractRegionEntry)entry;
        out.writeByte(TXEntryState.OP_FLAG_FOR_READ);

        if (pendingOpsRegion == null) {
          dataRegion = this.pendingOpsRegions.get(index);
          regionInfo.init(dataRegion);
          InternalDataSerializer.invokeToData(regionInfo, out);
        }

        DataSerializer.writeObject(re.getKeyCopy(), out);
        if (finerEnabled) {
          logger.finer("TXBatchMessage#toData: dataRegion="
              + dataRegion.getFullPath() + ", read locked entry: "
              + re.toString());
        }
      }
      // check for chunkSize
      if (hdos != null && hdos.size() >= this.chunkSize) {
        break;
      }
    }
    // terminate with a flag
    out.writeByte(TXEntryState.OP_FLAG_EOF);
  }

  @Override
  protected short computeCompressedShort(short flags) {
    if (this.conflictWithEX) {
      flags |= CONFLICT_WITH_EX;
    }
    if (this.piggyBackedMessages != null) {
      flags |= HAS_PIGGYBACKED_MESSAGES;
    }
    if (this.pendingOpsRegion != null) {
      flags |= HAS_COMMON_REGION;
    }
    return flags;
  }

  @Override
  public void fromData(DataInput in)
          throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.conflictWithEX = ((flags & CONFLICT_WITH_EX) != 0);
    // first read the piggybacked extra messages being carried, if any
    if ((flags & HAS_PIGGYBACKED_MESSAGES) != 0) {
      final int size = InternalDataSerializer.readArrayLength(in);
      final CountingDataInputStream cin = new CountingDataInputStream(in, 0L);
      this.piggyBackedMessages = new ArrayList<AbstractOperationMessage>(size);
      AbstractOperationMessage msg;
      int count, previousCount = 0;
      for (int index = 0; index < size; index++) {
        msg = DataSerializer.readObject(cin);
        count = (int)cin.getCount();
        msg.resetTimestamp();
        msg.setBytesRead(count - previousCount);
        previousCount = count;
        this.piggyBackedMessages.add(msg);
      }
    }

    final int numProbableOps = InternalDataSerializer.readArrayLength(in);

    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    final RegionInfoShip regionInfo = new RegionInfoShip();
    final LocalRegion pendingOpsRegion;
    this.pendingOps = new ArrayList<Object>(numProbableOps);
    if ((flags & HAS_COMMON_REGION) != 0) {
      InternalDataSerializer.invokeFromData(regionInfo, in);
      pendingOpsRegion = this.pendingOpsRegion = regionInfo.lookupRegion(cache);
      this.pendingOpsRegions = null;
    }
    else {
      pendingOpsRegion = this.pendingOpsRegion = null;
      this.pendingOpsRegions = new ArrayList<LocalRegion>(numProbableOps);
    }

    LocalRegion rgn;
    TXEntryState entry;
    byte op, destroyAndBulkOp;
    boolean bulkOp;
    Object key, pendingValue, callbackArg;
    while (true) {
      op = in.readByte();
      if (op == TXEntryState.OP_FLAG_EOF) {
        break;
      }
      if (this.pendingOpsRegions != null) {
        InternalDataSerializer.invokeFromData(regionInfo, in);
        rgn = regionInfo.lookupRegion(cache);
      }
      else {
        rgn = pendingOpsRegion;
      }
      if (op == TXEntryState.OP_FLAG_FOR_READ) {
        // read locked entries
        key = DataSerializer.readObject(in);
        if (rgn != null) { // ignore if region no longer present
          if (key instanceof KeyWithRegionContext) {
            ((KeyWithRegionContext)key).setRegionContext(rgn);
          }
          this.pendingOps.add(key);
          if (this.pendingOpsRegions != null) {
            this.pendingOpsRegions.add(rgn);
          }
        }
      }
      else {
        destroyAndBulkOp = in.readByte();
        bulkOp = ((destroyAndBulkOp & ENTRY_BULK_OP_FLAG) != 0);
        if (bulkOp) {
          destroyAndBulkOp &= CLEAR_ENTRY_BULK_OP_FLAG;
        }
        key = DataSerializer.readObject(in);
        pendingValue = DataSerializer.readObject(in);
        callbackArg = DataSerializer.readObject(in);
        if (rgn != null) { // ignore if region no longer present
          if (key instanceof KeyWithRegionContext) {
            ((KeyWithRegionContext)key).setRegionContext(rgn);
          }
          // this entry will always do delta flush since the full value must
          // already have been flushed by the source of this message
          entry = TXRegionState.createEmptyEntry(this.txId, key, null, null,
              rgn.getFullPath(), false);
          entry.op = op;
          entry.destroy = destroyAndBulkOp;
          entry.bulkOp = bulkOp;
          if (pendingValue instanceof Delta) {
            entry.pendingDelta = (Delta)pendingValue;
          }
          else {
            entry.pendingValue = pendingValue;
          }
          entry.setCallbackArgument(callbackArg);
          this.pendingOps.add(entry);
          if (this.pendingOpsRegions != null) {
            this.pendingOpsRegions.add(rgn);
          }
        }
      }
    }
  }

  @Override
  protected final void appendFields(final StringBuilder sb) {
    final int numOps = getCount();
    sb.append("; conflictWithEX=").append(this.conflictWithEX);
    sb.append("; batchSize=").append(numOps);
    if (numOps <= 50) {
      sb.append("; pendingOps=");
      final boolean useShortDump = numOps > 10;
      final LocalRegion pendingOpsRegion = this.pendingOpsRegion;
      for (int i = this.initOffset; i < this.pendingOps.size(); i++) {
        Object op = this.pendingOps.get(i);
        LocalRegion opRegion = pendingOpsRegion == null
            ? this.pendingOpsRegions.get(i) : pendingOpsRegion;
        sb.append('(');
        if (op instanceof TXEntryState) {
          sb.append(((TXEntryState)op).shortToString(true));
        }
        else if (useShortDump) {
          ArrayUtils.objectRefString(op, sb);
        }
        else {
          sb.append(op);
        }
        sb.append(";region=").append(opRegion.getFullPath());
        sb.append(") ");
      }
    }
    if (this.piggyBackedMessages != null) {
      sb.append("; piggyBackedMessages=").append(this.piggyBackedMessages);
    }
  }

  public static final class TXBatchResponse extends ReplyProcessor21 {

    final transient InternalDistributedMember recipient;

    private final List<AbstractOperationMessage> postMessages;

    TXBatchResponse(final DM dm, final InternalDistributedMember member,
        final List<AbstractOperationMessage> postMessages) {
      super(dm, member);
      this.recipient = member;
      this.postMessages = postMessages;
    }

    @Override
    public void process(final DistributionMessage msg) {
      try {
        if (msg instanceof TXBatchReply) {
          final TXBatchReply reply = (TXBatchReply)msg;
          final ArrayList<DistributionMessage> postReplies = reply.postReplies;
          DistributionMessage postReply;
          if (postReplies != null) {
            for (int index = 0; index < postReplies.size(); index++) {
              postReply = postReplies.get(index);
              postReply.setSender(reply.getSender());
              this.postMessages.get(index).getReplyProcessor()
                  .process(postReply);
            }
          }
        }
      } finally {
        super.process(msg);
      }
    }
  }

  public static final class TXBatchReply extends ReplyMessage {

    private ArrayList<DistributionMessage> postReplies;

    /** for deserialization */
    public TXBatchReply() {
    }

    /**
     * @param srcMessage
     *          the source message received from the sender; used for TXState
     *          adjustments as required before sending the reply
     * @param finishTXRead
     *          if set to true then read on TXState will be marked as done and
     *          any necessary cleanup performed
     */
    public TXBatchReply(final TXBatchMessage srcMessage,
        final boolean finishTXRead) {
      super(srcMessage, false, finishTXRead, false);
      this.postReplies = srcMessage.piggyBackedReplies;
    }

    /** Send an ack for given source message. */
    public static void send(InternalDistributedMember recipient,
        int processorId, ReplyException exception, ReplySender dm,
        TXBatchMessage sourceMessage) {
      Assert.assertTrue(recipient != null, "Sending a ReplyMessage to ALL");
      TXBatchReply m = new TXBatchReply(sourceMessage, true);

      m.processorId = processorId;
      m.setException(exception);
      if (exception != null) {
        dm.getLoggerI18n().fine("Replying with exception: " + m, exception);
      }
      m.setRecipient(recipient);
      dm.putOutgoing(m);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeObject(this.postReplies, out);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      this.postReplies = DataSerializer.readObject(in);
    }

    @Override
    public int getDSFID() {
      return TX_BATCH_REPLY_MESSAGE;
    }

    @Override
    protected StringBuilder getStringBuilder() {
      StringBuilder sb = super.getStringBuilder();
      if (this.postReplies != null) {
        sb.append(", postReplies=").append(this.postReplies);
      }
      return sb;
    }
  }

  /**
   * This reply sender will just add to a list that will be sent back by
   * {@link TXBatchReply} itself avoiding separate replies for all piggy-backed
   * messages. The piggy-backed messages are expected to be processed in order,
   * so that their order corresponds to the order of replies in
   * {@link TXBatchMessage#piggyBackedReplies}.
   * 
   * @author swale
   * @since 7.0
   */
  public final class TXBatchOtherReplySender implements ReplySender {

    private final LogWriterI18n logger;

    public TXBatchOtherReplySender(final LogWriterI18n logger) {
      this.logger = logger;
    }

    /**
     * @see ReplySender#getLoggerI18n()
     */
    public LogWriterI18n getLoggerI18n() {
      return this.logger;
    }

    /**
     * @see ReplySender#putOutgoing(DistributionMessage)
     */
    public Set<?> putOutgoing(DistributionMessage msg) {
      // just add to the list of replies; expected to be invoked in order
      piggyBackedReplies.add(msg);
      return Collections.EMPTY_SET;
    }
  }
}
