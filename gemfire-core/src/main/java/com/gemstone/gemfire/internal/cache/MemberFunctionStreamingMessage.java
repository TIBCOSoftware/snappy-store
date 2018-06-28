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
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.ResultSender;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.MessageWithReply;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.execute.FunctionContextImpl;
import com.gemstone.gemfire.internal.cache.execute.FunctionStats;
import com.gemstone.gemfire.internal.cache.execute.MemberFunctionResultSender;
import com.gemstone.gemfire.internal.cache.execute.MultiRegionFunctionContextImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;

/**
 * 
 * @author ymahajan
 * 
 */
public final class MemberFunctionStreamingMessage extends
    AbstractOperationMessage implements MessageWithReply {

  transient int replyMsgNum = 0;

  transient boolean replyLastMsg;

  private Function functionObject;

  private String functionName;

  Object args;

  private boolean isFnSerializationReqd;

  private Set<String> regionPathSet;

  private boolean isReExecute;

  //private final Object lastResultLock = new Object();

  private static final short IS_REEXECUTE = UNRESERVED_FLAGS_START;

  public MemberFunctionStreamingMessage() {
  }

  public MemberFunctionStreamingMessage(Function function, int procId,
      Object ar, boolean isFnSerializationReqd, boolean isReExecute,
      TXStateInterface tx) {
    super(tx);
    this.functionObject = function;
    this.processorId = procId;
    this.args = ar;
    this.isFnSerializationReqd = isFnSerializationReqd;
    this.isReExecute = isReExecute;
    // check for TX match with that in thread-local
    final TXStateInterface currentTX;
    assert tx == (currentTX = TXManagerImpl.getCurrentTXState()):
        "unexpected mismatch of current TX " + currentTX
            + ", and TX passed to message " + tx;
  }

  // For Multi region function execution
  public MemberFunctionStreamingMessage(Function function, int procId,
      Object ar, boolean isFnSerializationReqd, Set<String> regions,
      boolean isReExecute, TXStateInterface tx) {
    super(tx);
    this.functionObject = function;
    this.processorId = procId;
    this.args = ar;
    this.isFnSerializationReqd = isFnSerializationReqd;
    this.regionPathSet = regions;
    this.isReExecute = isReExecute;
    // check for TX match with that in thread-local
    final TXStateInterface currentTX;
    assert tx == (currentTX = TXManagerImpl.getCurrentTXState()):
        "unexpected mismatch of current TX " + currentTX
            + ", and TX passed to message " + tx;
  }

  public MemberFunctionStreamingMessage(DataInput in) throws IOException,
      ClassNotFoundException {
    fromData(in);
  }

  private TXManagerImpl.TXContext prepForTransaction() {
    if (getTXId() == null) {
      return null;
    }
    else {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache == null) {
        // ignore and return, we are shutting down!
        return null;
      }
      TXManagerImpl mgr = cache.getTxManager();
      return mgr.masqueradeAs(this, false, true);
    }
  }

  private void cleanupTransaction(final TXManagerImpl.TXContext context,
      final DistributionManager dm) {
    if (getTXId() != null) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache == null) {
        // ignore and return, we are shutting down!
        return;
      }
      TXManagerImpl mgr = cache.getTxManager();
      mgr.unmasquerade(context, true);
    }
  }

  @Override
  protected final void basicProcess(final DistributionManager dm) {
    Throwable thr = null;
    ReplyException rex = null;
    if (this.functionObject == null) {
      rex = new ReplyException(new FunctionException(
          LocalizedStrings.ExecuteFunction_FUNCTION_NAMED_0_IS_NOT_REGISTERED
              .toLocalizedString(this.functionName)));

      replyWithException(dm, rex);
      return;
    }

    FunctionStats stats = FunctionStats.getFunctionStats(this.functionObject
        .getId(), dm.getSystem());
    LogWriterI18n logger = dm.getLoggerI18n();
    TXManagerImpl.TXContext txContext = null;
    try {
      txContext = prepForTransaction();
      ResultSender<?> resultSender = new MemberFunctionResultSender(dm, this,
          this.functionObject);
      @SuppressWarnings("rawtypes")
      Set<Region> regions = new HashSet<Region>();
      if (this.regionPathSet != null) {
        GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        for (String regionPath : this.regionPathSet) {
          if (checkCacheClosing(dm) || checkDSClosing(dm)) {
            thr = new CacheClosedException(LocalizedStrings.PartitionMessage_REMOTE_CACHE_IS_CLOSED_0.toLocalizedString(dm.getId()));
            return;
          }
          regions.add(cache.getRegion(regionPath));
        }
      }
      FunctionContextImpl context = new MultiRegionFunctionContextImpl(
          this.functionObject.getId(), this.args, resultSender, regions,
          isReExecute);

      long start = stats.startTime();
      stats.startFunctionExecution(this.functionObject.hasResult());
      if (logger.fineEnabled()) {
        logger.fine("Executing Function: " + this.functionObject.getId()
            + " on remote member with context: " + context.toString());
      }
      this.functionObject.execute(context);
      if (!this.replyLastMsg && this.functionObject.hasResult()) {
        throw new FunctionException(
            LocalizedStrings.ExecuteFunction_THE_FUNCTION_0_DID_NOT_SENT_LAST_RESULT
                .toString(functionObject.getId()));
      }
      stats.endFunctionExecution(start, this.functionObject.hasResult());
    }
    catch (FunctionException functionException) {
      if (logger.fineEnabled()) {
        logger.fine(
            "FunctionException occured on remote member while executing Function: "
                + this.functionObject.getId(), functionException);
      }
      stats.endFunctionExecutionWithException(this.functionObject.hasResult());
      rex = new ReplyException(functionException);
      replyWithException(dm, rex);
      // thr = functionException.getCause();
    }
    catch (CancelException exception) {
      // bug 37026: this is too noisy...
      // throw new CacheClosedException("remote system shutting down");
      // thr = se; cache is closed, no point trying to send a reply
      thr = null;
      if (logger.fineEnabled()) {
        logger.fine("shutdown caught, abandoning message: " + exception);
      }
    }
    catch (Exception exception) {
      if (logger.fineEnabled()) {
        logger.fine(
            "Exception occured on remote member while executing Function: "
                + this.functionObject.getId(), exception);
      }
      stats.endFunctionExecutionWithException(this.functionObject.hasResult());
      rex = new ReplyException(exception);
      replyWithException(dm, rex);
      // thr = e.getCause();
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
      // check for fatal JVM error (see above). However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
      thr = t;
    }
    finally {
      cleanupTransaction(txContext, dm);
      if (thr != null) {
        rex = new ReplyException(thr);
        replyWithException(dm, rex);
      }
    }
  }

  private void replyWithException(DistributionManager dm, ReplyException rex) {
    ReplyMessage.send(getSender(), this.processorId, rex, dm, this);
  }

  public int getDSFID() {
    return MEMBER_FUNCTION_STREAMING_MESSAGE;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);

    this.processorId = in.readInt();
    ReplyProcessor21.setMessageRPId(this.processorId);
    Object object = DataSerializer.readObject(in);
    if (object instanceof String) {
      this.isFnSerializationReqd = false;
      this.functionObject = FunctionService.getFunction((String) object);
      if (this.functionObject == null) {
        this.functionName = (String) object;
      }
    } else {
      this.functionObject = (Function) object;
      this.isFnSerializationReqd = true;
    }
    this.args = DataSerializer.readObject(in);
    this.regionPathSet = DataSerializer.readObject(in);
    this.isReExecute = (flags & IS_REEXECUTE) != 0;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);

    out.writeInt(this.processorId);
    if (this.isFnSerializationReqd) {
      DataSerializer.writeObject(this.functionObject, out);
    }
    else {
      DataSerializer.writeObject(functionObject.getId(), out);
    }
    DataSerializer.writeObject(this.args, out);
    DataSerializer.writeObject(this.regionPathSet, out);
  }

  @Override
  protected final short computeCompressedShort(short flags) {
    if (this.isReExecute) flags |= IS_REEXECUTE;
    return flags;
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    sb.append("; function=").append(this.functionObject.getId());
    sb.append("; args=");
    ArrayUtils.objectStringNonRecursive(this.args, sb);
    sb.append("; regionPaths=").append(this.regionPathSet);
  }

  public synchronized boolean sendReplyForOneResult(DM dm, Object oneResult,
      boolean lastResult, boolean sendResultsInOrder, boolean sendTXChanges)
      throws CacheException, QueryException, ForceReattemptException,
      InterruptedException {

    if(this.replyLastMsg) {
      return false;
    }
    
    if (Thread.interrupted())
      throw new InterruptedException();
    LogWriterI18n logger = dm.getLoggerI18n();
    int msgNum = this.replyMsgNum;
    this.replyLastMsg = lastResult;

    sendReply(getSender(), this.processorId, dm, oneResult, msgNum,
        lastResult, sendResultsInOrder, sendTXChanges);

    if (logger.fineEnabled()) {
      logger.fine("Sending reply message count: " + replyMsgNum
          + " to co-ordinating node");
    }
    this.replyMsgNum++;
    return false;
  }

  protected void sendReply(InternalDistributedMember member, int procId, DM dm,
      Object oneResult, int msgNum, boolean lastResult,
      boolean sendResultsInOrder, boolean sendTXChanges) {
    if (sendResultsInOrder) {
      FunctionStreamingOrderedReplyMessage.send(member, procId, null, dm, oneResult,
          msgNum, lastResult, this, sendTXChanges);
    }
    else {
      FunctionStreamingReplyMessage.send(member, procId, null, dm, oneResult,
          msgNum, lastResult, this, sendTXChanges);
    }
  }

  @Override
  public final int getProcessorType() {
    return this.processorType == 0
        ? DistributionManager.REGION_FUNCTION_EXECUTION_EXECUTOR
        : this.processorType;
  }

  @Override
  public void setProcessorType(boolean isReaderThread) {
    if (isReaderThread) {
      this.processorType = DistributionManager.WAITING_POOL_EXECUTOR;
    }
  }

  /**
   * check to see if the cache is closing
   */
  final public boolean checkCacheClosing(DistributionManager dm) {
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    return (cache == null || cache.getCancelCriterion().cancelInProgress() != null);
  }

  /**
   * check to see if the distributed system is closing
   * 
   * @return true if the distributed system is closing
   */
  final public boolean checkDSClosing(DistributionManager dm) {
    InternalDistributedSystem ds = dm.getSystem();
    return (ds == null || ds.isDisconnecting());
  }

  /* (non-Javadoc)
   * @see com.gemstone.gemfire.internal.cache.TransactionMessage#canStartRemoteTransaction()
   */
  @Override
  public final boolean canStartRemoteTransaction() {
    return true;
  }

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  @Override
  public final boolean useTransactionProxy() {
    return true;
  }
}
