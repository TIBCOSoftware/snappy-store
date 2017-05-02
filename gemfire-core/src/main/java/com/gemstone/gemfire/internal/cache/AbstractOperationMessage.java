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
import java.io.NotSerializableException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedLockObject;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.shared.Version;

/**
 * Base class for {@link DistributionMessage}s that need to be processed on
 * multiple nodes while honouring the {@link LockingPolicy} to acquire relevant
 * local locks.
 * <p>
 * All transactional messages now extend this class to transport the
 * {@link LockingPolicy} (which in turn also encapsulates the
 * {@link IsolationLevel}) and apply the same individually on the nodes.
 * 
 * @see DistributionMessage
 * @see LockingPolicy
 * @see ExclusiveSharedLockObject
 * 
 * @author swale
 * @since 7.0
 */
public abstract class AbstractOperationMessage extends DistributionMessage
    implements TransactionMessage, Checkpoint {

  /** The processor ID for this message. */
  protected int processorId;

  /** Any message specific processor type set from sender side. */
  protected int processorType;

  /** The globally unique transaction Id. */
  protected TXId txId;

  /**
   * Indicates that there is a previous transaction that is pending commit so
   * remote processing must wait for the commit to complete.
   */
  protected TXId pendingTXId;

  /**
   * Indicates that this message is the result of the op number currExecnSeq in
   * the transaction. Used for tracking savepoints
   */
  protected long currExecSeq;

  // Instead of using another flag (short is exhausted for some messages) to
  // indicate a pending TXId we write a byte to indicate whether there is a
  // pending TX or not
  private static final int ONLY_TX = 0;
  private static final int ONLY_PENDING = 1;
  private static final int BOTH_TX_AND_PENDING = 2;
  private static final int INVALID_PENDING_FLAG = -1;

  protected transient short flags;

  /**
   * Transient field to store the current TX state determined on the remote
   * node. This avoids unnecessary ThreadLocal lookups in the context of remote
   * execution.
   */
  private transient TXStateInterface txState;

  /**
   * The {@link TXStateProxy} for the {@link #txState}.
   */
  protected transient TXStateProxy txProxy;

  /**
   * {@link Checkpoint} for the list of regions affected by TX.
   */
  transient int txRegionCheckpoint = -1;
  transient int txFlags;

  /**
   * Flag set to true if the message processing read the TXStateProxy increasing
   * its reference count.
   */
  private transient final AtomicBoolean txProxyRead = new AtomicBoolean(false);

  /**
   * The {@link LockingPolicy} to use for the current operation.
   */
  private LockingPolicy lockPolicy = LockingPolicy.NONE;

  static {
    // check that the processor ID set flag is consistent with ReplyMessage
    if (HAS_PROCESSOR_ID != ReplyMessage.PROCESSOR_ID_FLAG) {
      Assert.fail("unexpected HAS_PROCESSOR_ID=0x"
          + Integer.toHexString(HAS_PROCESSOR_ID)
          + ", ReplyMessage's PROCESSOR_ID_FLAG="
          + Integer.toHexString(ReplyMessage.PROCESSOR_ID_FLAG));
    }
    // check that size of LockingPolicy enums should not exceed a byte
    if (LockingPolicy.size() > Byte.MAX_VALUE) {
      Assert.fail("too many LockingPolicy enums: " + LockingPolicy.size());
    }
  }

  /** for deserialization */
  public AbstractOperationMessage() {
  }

  public AbstractOperationMessage(final TXStateInterface tx) {
    if (tx != null) {
      initTXState(tx);
    }
    // check for a previous TX pending commit, if any
    if (sendPendingTXId()) {
      initPendingTXId(TXManagerImpl.currentTXContext(), tx);
    }
  }

  /** Copy constructor for child classes. */
  public AbstractOperationMessage(final AbstractOperationMessage other) {
    this.txId = other.txId;
    this.pendingTXId = other.pendingTXId;
    this.txState = other.txState;
    this.txProxy = other.txProxy;
    this.lockPolicy = other.lockPolicy;
    this.timeStatsEnabled = other.timeStatsEnabled;
  }

  protected final void initPendingTXId(final TXManagerImpl.TXContext context,
      final TXStateInterface tx) {
    if (context != null) {
      this.pendingTXId = context.getPendingTXId();
    }
    // assert that passed TX should match that in thread context except for
    // TXMessages (commit/rollback) that clear the thread context
    assert tx == null || this instanceof TXMessage
        || (context != null && context.getTXState() == tx):
          "unexpected mismatch of context's TXState " + context
          + ", and TX passed to message " + tx;
  }

  public ReplyProcessor21 getReplyProcessor() {
    return null;
  }

  /**
   * Processes this message. This method is invoked by the receiver of the
   * message.
   * 
   * @param dm
   *          the distribution manager that is processing the message.
   */
  @Override
  protected final void process(final DistributionManager dm) {
    // if there is a previous TX from same coordinator that still may not have
    // committed then wait for it to finish first
    if (this.pendingTXId != null) {
      final GemFireCacheImpl cache;
      final TXManagerImpl txMgr;
      final TXStateProxy proxy;
      if ((cache = GemFireCacheImpl.getInstance()) != null
          && (txMgr = cache.getCacheTransactionManager()) != null
          && (proxy = txMgr.getHostedTXState(this.pendingTXId)) != null) {
        proxy.waitForLocalTXCommit(this.txId, 0);
      }
    }
    if (this.txId != null) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null) {
        LogWriterI18n logger = cache.getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine(" The operation tx id " + this.txId +
              " locking policy " + getLockingPolicy() + " txState " + getTXState());
        }
      }
      try {
        basicProcess(dm);
      } finally {
        if (requireFinishTX() && finishTXProxyRead() && this.lockPolicy != LockingPolicy.SNAPSHOT) {
          // in case there is nothing in the TXStateProxy then get rid of it
          // so that commit/rollback will not be required for this node;
          // this is now a requirement since commit/rollback targets only the
          // nodes that host one of the affected regions only, so if nothing
          // was affected on this node then no commit/rollback may be received
          this.txProxy.removeSelfFromHostedIfEmpty(null);
        }
      }
    }
    else {
      basicProcess(dm);
    }
  }

  protected abstract void basicProcess(final DistributionManager dm);

  /**
   * @return the {@link ReplyProcessor21}id associated with the message, null if
   *         no acknowlegement is required.
   */
  @Override
  public final int getProcessorId() {
    return this.processorId;
  }

  public final boolean isTransactional() {
    return this.txId != null;
  }

  /**
   * @see TransactionMessage#getTXId()
   */
  public final TXId getTXId() {
    return this.txId;
  }

  final TXId getPendingTXId() {
    return this.pendingTXId;
  }

  public final void initTXState(TXStateInterface tx) {
    this.lockPolicy = tx.getLockingPolicy();
    Assert.assertTrue(this.lockPolicy != null,
        "setLockingPolicy: unexpected null LockingPolicy");
    this.txId = tx.getTransactionId();
    this.txState = tx;
    this.txProxy = tx.getProxy();
    this.currExecSeq = tx.getExecutionSequence();
  }

  /**
   * @see TransactionMessage#getTXState(LocalRegion)
   */
  public final TXStateInterface getTXState(final LocalRegion region) {
    if (this.txState != null) {
      if (region.supportsTransaction()) {
        return this.txState;
      }
    }
    return null;
  }

  /**
   * @see TransactionMessage#getTXState()
   */
  public final TXStateInterface getTXState() {
    return this.txState;
  }

  /**
   * @see TransactionMessage#setTXState(TXStateInterface)
   */
  public final void setTXState(TXStateInterface tx) {
    initTXState(tx);
    updateToEnd();
  }

  /**
   * @see TransactionMessage#canStartRemoteTransaction()
   */
  public boolean canStartRemoteTransaction() {
    // by default don't start transactions
    return false;
  }

  /**
   * Get the {@link LockingPolicy} to be used for execution of this message.
   */
  public final LockingPolicy getLockingPolicy() {
    return this.lockPolicy;
  }

  /**
   * Set the {@link LockingPolicy} to be used for execution of this message.
   */
  protected final void setLockingPolicy(final LockingPolicy lockPolicy) {
    this.lockPolicy = lockPolicy;
  }

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  public boolean useTransactionProxy() {
    return false;
  }

  /**
   * Return true if this is an operation signalling end of Transaction i.e.
   * commit or rollback, and false otherwise.
   */
  protected boolean isTXEnd() {
    return false;
  }

  public final TXStateProxy getTXProxy() {
    return this.txProxy;
  }

  /**
   * Return true if previous pending TXId (
   * {@link TXManagerImpl.TXContext#getPendingTXId()}) should be sent in the
   * message.
   */
  protected boolean sendPendingTXId() {
    return true;
  }

  public final void startTXProxyRead() {
    this.txProxyRead.set(true);
  }

  public final boolean finishTXProxyRead() {
    if (this.txProxyRead.compareAndSet(true, false)) {
      this.txProxy.decRefCount();
      return true;
    }
    return false;
  }

  /**
   * Return true if finish TX actions should be invoked in the process of
   * message itself (else it is handled by ReplyMessage).
   */
  protected boolean requireFinishTX() {
    return false;
  }

  public final Checkpoint getTXRegionCheckpoint() {
    return this.txRegionCheckpoint >= 0 ? this : null;
  }

  /**
   * @see Checkpoint#attemptLock(long)
   */
  public final boolean attemptLock(long msecs) {
    return this.txProxy.attemptReadLock(msecs);
  }

  /**
   * @see Checkpoint#releaseLock()
   */
  public final void releaseLock() {
    this.txProxy.releaseReadLock();
  }

  /**
   * @see Checkpoint#elementAt(int)
   */
  public final Object elementAt(final int index) {
    assert this.txRegionCheckpoint >= 0;
    if (index >= 0) {
      return this.txProxy.regions.elementAt(this.txRegionCheckpoint + index);
    }
    else {
      // -ve index is indication to check whether txFlags has changed
      int flags = getTXFlags(this.txProxy);
      if (flags != this.txFlags) {
        return Boolean.TRUE;
      }
      else {
        return null;
      }
    }
  }

  /**
   * @see Checkpoint#elementState(int)
   */
  public final ObjectState elementState(int index) {
    return ObjectState.ADDED;
  }

  /**
   * @see Checkpoint#numChanged()
   */
  public final int numChanged() {
    assert this.txRegionCheckpoint >= 0;
    return this.txProxy.regions.size() - this.txRegionCheckpoint;
  }

  /**
   * @see Checkpoint#removeAt(int)
   */
  public final Object removeAt(int index) {
    assert this.txRegionCheckpoint >= 0;
    if (index >= 0) {
      return this.txProxy.regions.removeAt(this.txRegionCheckpoint + index);
    }
    else {
      // reset txFlags
      this.txFlags = 0;
      return null;
    }
  }

  /**
   * @see Checkpoint#removeAllChanged()
   */
  public final void removeAllChanged() {
    throw new UnsupportedOperationException(
        "unexpected call to removeAllChanged for " + getClass().getName());
  }

  private static int getTXFlags(final TXStateProxy txp) {
    return (txp.isDirty() ? 1 : 0) + (txp.hasReadOps() ? 1 : 0);
  }

  /**
   * @see Checkpoint#updateToEnd()
   */
  public final void updateToEnd() {
    final TXStateProxy txp = this.txProxy;

    assert txp != null;
    if (!txp.isCoordinator() && canStartRemoteTransaction()) {
      this.txRegionCheckpoint = txp.numAffectedRegions();
      this.txFlags = getTXFlags(txp);
    }
  }

  /**
   * Get a string representation for the conflict entry used to construct
   * conflict exception strings.
   * 
   * The default implementation returns a string suitable only for region
   * operations assuming the context object to be a region.
   */
  public String getConflictObjectString(final ExclusiveSharedLockObject sync,
      final LockMode lockMode, final Object context, final Object forOwner) {
    return sync + ", with owner: "
        + LockingPolicy.getLockOwnerForConflicts(sync, lockMode, context,
            forOwner) + "; for owner: " + forOwner + ", in region: " + context;
  }

  // Serialization related methods below

  @Override
  public void toData(final DataOutput out) throws IOException {
    //if (this instanceof DestroyRegionOperation.DestroyRegionMessage)
      //GemFireCacheImpl.getInstance().getLogger().fine("Sending bytes on stream " + out.getClass() + " : " + out, new Exception());
    // any pre-processing required before actual serialization
    beforeToData(out);
    // first compute and write the flags
    short flags = 0;
    if (this.processorId != 0) flags |= HAS_PROCESSOR_ID;
    if (this.processorType != 0) flags |= HAS_PROCESSOR_TYPE;
    if (this.lockPolicy != LockingPolicy.NONE) flags |= HAS_LOCK_POLICY;
    if (this.timeStatsEnabled) flags |= ENABLE_TIMESTATS;

    int pendingFlag = INVALID_PENDING_FLAG;
    if (this.txId != null) {
      if (this.pendingTXId != null) {
        pendingFlag = BOTH_TX_AND_PENDING;
      }
      else {
        pendingFlag = ONLY_TX;
      }
      if (canStartRemoteTransaction()) {
        this.txProxy.hasCohorts = true;
      }
      flags |= HAS_TX_ID;
    }
    else if (this.pendingTXId != null) {
      pendingFlag = ONLY_PENDING;
      flags |= HAS_TX_ID;
    }
    // any other additions to the flags should be done here
    flags = computeCompressedShort(flags);
    out.writeShort(flags);
    // write the processorId to enable reading successfully even if rest of
    // deserialization throws an exception
    if (this.processorId != 0) {
      out.writeInt(this.processorId);
    }
    if (this.processorType != 0) {
      out.writeByte(this.processorType);
    }
    if (this.lockPolicy != LockingPolicy.NONE) {
      out.writeByte(this.lockPolicy.ordinal());
    }
    switch (pendingFlag) {
      case ONLY_TX:
        out.writeByte(ONLY_TX);
        this.txId.toData(out);
        break;
      case ONLY_PENDING:
        out.writeByte(ONLY_PENDING);
        this.pendingTXId.toData(out);
        break;
      case BOTH_TX_AND_PENDING:
        out.writeByte(BOTH_TX_AND_PENDING);
        this.txId.toData(out);
        this.pendingTXId.toData(out);
        break;
    }
    if (this.txId != null) {
      writeCurrentSequenceNumber(out);
    }
  }

  protected final void writeCurrentSequenceNumber(final DataOutput out)
      throws IOException {
    if (Version.GFXD_20.compareTo(InternalDataSerializer
        .getVersionForDataStream(out)) <= 0) {
      InternalDataSerializer.writeSignedVL(this.currExecSeq, out);
    }
  }

  protected final void readCurrentSequenceNumber(final DataInput in)
      throws IOException {
    if (Version.GFXD_20.compareTo(InternalDataSerializer
        .getVersionForDataStream(in)) <= 0) {
      this.currExecSeq = InternalDataSerializer.readSignedVL(in);
    }
  }

  @Override
  public void fromData(final DataInput in) throws IOException,
      ClassNotFoundException {
    // check for cache going down in case of exceptions during deserialization
    try {
      this.flags = baseFromData(in);
    } catch (Throwable t) {
      handleFromDataException(t);
    }
  }

  protected final short baseFromData(final DataInput in) throws IOException,
      ClassNotFoundException {
    final short flags = in.readShort();
    // read the processorId if > 0 and set in thread-local to send back reply
    // successfully in case of any deserialization errors
    if ((flags & HAS_PROCESSOR_ID) != 0) {
      this.processorId = in.readInt();
      // NIO/IO readers reset the RPId before and after reading, so no need
      // to set to zero if there is no processorId
      ReplyProcessor21.setMessageRPId(this.processorId);
    }
    if ((flags & HAS_PROCESSOR_TYPE) != 0) {
      this.processorType = in.readByte();
    }
    if ((flags & HAS_LOCK_POLICY) != 0) {
      this.lockPolicy = LockingPolicy.fromOrdinal(in.readByte());
    }
    else {
      this.lockPolicy = LockingPolicy.NONE;
    }
    if ((flags & ENABLE_TIMESTATS) != 0 && !this.timeStatsEnabled) {
      this.timeStatsEnabled = true;
      this.timeStamp = DistributionStats.getStatTimeNoCheck();
    }
    if ((flags & HAS_TX_ID) != 0) {
      final int pendingFlag = in.readByte();
      switch (pendingFlag) {
        case ONLY_TX:
          this.txId = TXId.createFromData(in);
          break;
        case ONLY_PENDING:
          this.pendingTXId = TXId.createFromData(in);
          break;
        default:
          assert pendingFlag == BOTH_TX_AND_PENDING;
          this.txId = TXId.createFromData(in);
          this.pendingTXId = TXId.createFromData(in);
          break;
      }
      if (this.txId != null) {
        readCurrentSequenceNumber(in);
      }
    }
    return flags;
  }

  protected void handleFromDataException(Throwable t)
      throws ClassNotFoundException, IOException {
    Error err = null;
    if (t instanceof Error && SystemFailure.isJVMFailureError(err = (Error)t)) {
      SystemFailure.initiateFailure(err);
      // If this ever returns, rethrow the error. We're poisoned
      // now, so don't let this thread continue.
      throw err;
    }
    // Whenever you catch Error or Throwable, you must also
    // check for fatal JVM error (see above). However, there is
    // _still_ a possibility that you are dealing with a cascading
    // error condition, so you also need to check to see if the JVM
    // is still usable.
    SystemFailure.checkFailure();
    // check if system is closing, else rethrow the exception
    handleFromDataExceptionCheckCancellation(t);
    // If the above returns then send back error since this may be assertion
    // or some other internal code bug.
    if (err != null) {
      throw err;
    }
    // instanceof checks below to cast for rethrow
    if (t instanceof RuntimeException) {
      throw (RuntimeException)t;
    }
    else if (t instanceof IOException) {
      throw (IOException)t;
    }
    else if (t instanceof ClassNotFoundException) {
      throw (ClassNotFoundException)t;
    }
    else { // anything else remains?
      final NotSerializableException ioe = new NotSerializableException();
      ioe.initCause(t);
      throw ioe;
    }
  }

  protected void handleFromDataExceptionCheckCancellation(Throwable t)
      throws CancelException {
    // check if system is closing, else rethrow the exception
    final InternalDistributedSystem ds = InternalDistributedSystem
        .getAnyInstance();
    if (ds == null) {
      throw InternalDistributedSystem.newDisconnectedException(t);
    }
    else {
      ds.getCancelCriterion().checkCancelInProgress(t);
    }
  }

  protected abstract short computeCompressedShort(short flags);

  protected void beforeToData(final DataOutput out) throws IOException {
  }

  // toString and helpers

  @Override
  public final String toString() {
    final StringBuilder sb = new StringBuilder(getShortClassName());
    sb.append('@').append(Integer.toHexString(System.identityHashCode(this)));
    sb.append("(processorId=").append(this.processorId);
    sb.append("; processorType=").append(getProcessorType());
    if (this.lockPolicy != LockingPolicy.NONE) {
      sb.append("; lockingPolicy=").append(this.lockPolicy);
    }
    if (this.txId != null) {
      sb.append("; txId=").append(this.txId);
    }
    if (this.pendingTXId != null) {
      sb.append("; pendingTXId=").append(this.pendingTXId);
    }
    appendFields(sb);
    return sb.append(')').toString();
  }

  protected abstract void appendFields(final StringBuilder sb);
}
