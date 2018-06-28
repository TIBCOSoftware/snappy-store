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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.LowMemoryException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionInvocationTargetException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.ReplySender;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.DirectReplyMessage;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.THashMapWithCreate;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.TransactionMessage;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionInvocationTargetException;
import com.gemstone.gemfire.internal.cache.execute.InternalResultSender;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.tcp.DirectReplySender;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCountDownLatch;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.distributed.ResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdReplyMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdReplyMessageProcessor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResponseCode;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollectorHelper;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdWaitingReplyProcessorBase;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.jdbc.GfxdDDLReplayInProgressException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.XPLAINDistPropsDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Base abstract class for GemFireXD function messages. This is not directly
 * usable. Implementations should extend either {@link RegionExecutorMessage} or
 * {@link MemberExecutorMessage} classes for an implementation of a function
 * message.
 * 
 * @author swale
 */
public abstract class GfxdFunctionMessage<T> extends
    GfxdMessage implements InternalResultSender {

  // transient members set during execution

  protected transient ResultCollector<Object, T> userCollector;

  protected transient GfxdResultCollector<T> gfxdCollector;

  protected transient GfxdFunctionReplyMessageProcessor<T> processor;

  protected transient boolean orderedReplies;

  protected transient Set<DistributedMember> failedNodes;

  protected transient boolean abortOnLowMemory = true;

  /**
   * The DistributionManager being used for processing. Will be null if message
   * is being processed locally.
   */
  protected transient DistributionManager dm;

  private transient int replySequenceId;

  private transient TXStateProxy replyTX;

  /** indicates that lastResult() has been invoked */
  private static final int TERMINAL_REPLY_SEQID = -1;

  // statistics data
  // ----------------
  protected transient short messageRetryCount = 0;

  // originating node.
  // ----------------
  // recorded begin scattering time in root message
  protected transient Timestamp begin_scatter_time = null;

  /**
   * recorded only for root message sending time. individual cloned messages
   * captures this in process_time. root_msg process_time indicates total
   * execution time including member_mapping_time, process_time of individual
   * messages, retries etc.
   */
  protected transient long root_msg_send_time;
  
  /**
   * This is net time taken for self execution that is to be excluded
   * while computing AbstractGemFireResultSet timing. DISTRIBUTION_END in
   * queryplan otherwise shows inclusive of self time which is further elaborated
   * as Local Plan:
   */
  protected transient long self_execution_time;

  // will have multiple entries in sender
  // this is not guarded as messages are sent one at time by the primary
  // message. @see RegionExecutorMessage#executeFunction.
  protected transient ArrayList<GfxdFunctionMessage<T>> membersMsgsSent;

  // destination node.
  // ----------------
  // recorded by node collecting this message.

  // reply messages at the receiving end.
  protected transient final List<GfxdFunctionReplyMessage> replyReceivedMsgs;
  
  // reply messages sent at the sender end.
  protected transient List<GfxdFunctionReplyMessage> replySentMsgs;
  // end of statistics data
  // -----------------------

  /** Empty constructor for deserialization. Not to be invoked directly. */
  protected GfxdFunctionMessage(boolean ignored) {
    this.userCollector = null;
    this.gfxdCollector = null;
    this.replyReceivedMsgs = null;
    // to be initialized by fromData.
    this.construct_time = null;
    this.replySentMsgs = null;
  }

  /** Constructor that should be invoked by child classes. */
  protected GfxdFunctionMessage(ResultCollector<Object, T> collector,
      final TXStateInterface tx, boolean timeStatsEnabled,
      boolean abortOnLowMemory) {
    super(tx, timeStatsEnabled);
    this.abortOnLowMemory = abortOnLowMemory;
    if (this.timeStatsEnabled) {
      replyReceivedMsgs = Collections
          .synchronizedList(new ArrayList<GfxdFunctionReplyMessage>());
      replySentMsgs = Collections
          .synchronizedList(new ArrayList<GfxdFunctionReplyMessage>());
    }
    else {
      replyReceivedMsgs = null;
      replySentMsgs = null;
    }
    assert collector != null: "unexpected null ResultCollector";

    this.userCollector = collector;
    this.gfxdCollector = getGfxdResultCollector(collector);
  }

  /** Copy constructor for child classes. */
  protected GfxdFunctionMessage(final GfxdFunctionMessage<T> other) {
    super(other);
    this.processor = other.processor;

    assert other.userCollector != null: "unexpected null ResultCollector";
    this.userCollector = other.userCollector;
    this.gfxdCollector = other.gfxdCollector;
    this.abortOnLowMemory = other.abortOnLowMemory;
    // pass the reference
    if (this.timeStatsEnabled) {
      this.membersMsgsSent = other.membersMsgsSent;
      this.replyReceivedMsgs = other.replyReceivedMsgs;
      this.replySentMsgs = other.replySentMsgs;
    }
    else {
      this.replyReceivedMsgs = null;
      this.replySentMsgs = null;
    }
  }

  public static final TXStateInterface getCurrentTXState(
      final LanguageConnectionContext lcc) {
    if (lcc != null) {
      final GemFireTransaction tc = (GemFireTransaction)lcc
          .getTransactionExecute();
      if (tc != null) {
        return tc.getActiveTXState();
      }
    }
    return TXManagerImpl.getCurrentTXState();
  }

  protected static final boolean getTimeStatsSettings(
      final LanguageConnectionContext lcc) {
    return lcc != null && (lcc.statsEnabled() || lcc.getStatisticsTiming());
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    if(timeStatsEnabled) {
      this.replySentMsgs = Collections
          .synchronizedList(new ArrayList<GfxdFunctionReplyMessage>());
    }
  }

  protected static final <T> GfxdResultCollector<T> getGfxdResultCollector(
      final ResultCollector<Object, T> collector) {
    if (collector instanceof GfxdResultCollector<?>) {
      return (GfxdResultCollector<T>)collector;
    }
    return null;
  }

  public final T executeFunction() throws StandardException, SQLException {
    return executeFunction(false, false, null, false, true);
  }

  public final T executeFunction(final boolean enableStreaming,
      boolean isPossibleDuplicate, final AbstractGemFireResultSet rs,
      final boolean orderedReplies) throws StandardException, SQLException {
    return executeFunction(enableStreaming, isPossibleDuplicate, rs,
        orderedReplies, true);
  }

  public T executeFunction(final boolean enableStreaming,
      boolean isPossibleDuplicate, final AbstractGemFireResultSet rs,
      final boolean orderedReplies, boolean getResult)
      throws StandardException, SQLException {
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(process_time == 0 ? process_time = -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    final short retryCnt = GfxdConstants.HA_NUM_RETRIES;

    this.orderedReplies = orderedReplies;
    if (requiresTXFlushBeforeExecution()) {
      // flush any pending ops in TX
      final TXStateInterface tx = getTXState();
      if (tx != null) {
        // flush any pending TXStateProxy ops
        tx.flushPendingOps(null);
      }
    }
    // start execution on this, but clone for retries
    GfxdFunctionMessage<T> msg = this;
    for (;;) {
      ResultCollector<Object, T> collector = null;
      long viewVersion = -1;
      DistributionAdvisor advisor = null;

      msg.setPossibleDuplicate(isPossibleDuplicate);
      try {
        if (msg.containsRegionContentChange()
            && (advisor = msg.getDistributionAdvisor()) != null) {
          viewVersion = advisor.startOperation();
          if (TXStateProxy.LOG_VERSIONS) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                ArrayUtils.objectRefString(msg)
                    + " dispatching operation in view version " + viewVersion);
          }
        }
        msg.executeFunction(enableStreaming);
        // message dispatch is done at this point
        if (viewVersion != -1) {
          advisor.endOperation(viewVersion);
          if (TXStateProxy.LOG_VERSIONS) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                ArrayUtils.objectRefString(msg)
                    + " done dispatching operation in view version "
                    + viewVersion);
          }
          viewVersion = -1;
        }
        // assume a non-null user ResultCollector for streaming
        collector = ((enableStreaming || msg.processor == null)
            ? msg.userCollector : msg.processor);
        if (getResult) {
          T result = collector.getResult();
          if (rs != null) {
            rs.setup(result, msg.getNumRecipients());
          }
          process_time = this.timeStatsEnabled ? XPLAINUtil
              .recordTiming(begintime) : 0;
          return result;
        }
        else {
          process_time = this.timeStatsEnabled ? XPLAINUtil
              .recordTiming(begintime) : 0;
          return null;
        }
      } catch (RuntimeException | StandardException | SQLException e) {
        // in case of any exception during execute, clear the ResultCollector
        // to also invoke GfxdLockSet#rcEnd() if required (see bug #41661)
        if (collector == null) {
          collector = ((enableStreaming || msg.processor == null)
              ? msg.userCollector : msg.processor);
        }
        collector.clearResults();
        // first check this VM itself for shutdown
        Misc.checkIfCacheClosing(e);
        // also check for GFXD layer shutdown in progress
        if (Monitor.inShutdown()) {
          throw StandardException
              .newException(SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN);
        }
        // check whether this query needs to be cancelled due to 
        // timeout or low memory
        if (rs != null) {
          rs.checkCancellationFlag();
        }
        final boolean retry = msg.isHA();
        if (retry && retryCnt > 0
            && GemFireXDUtils.retryToBeDone(e, messageRetryCount)) {
          // TODO: TX: currently cannot retry in absence of a mechanism to
          // restore TX state to previous condition (checkpoint)
          if (msg.optimizeForWrite() && msg.isTransactional()) {
            throw StandardException.newException(
                SQLState.DATA_CONTAINER_CLOSED, e,
                StandardException.getSenderFromExceptionOrSelf(e), "");
          }
          if (messageRetryCount++ >= retryCnt) {
            SanityManager.DEBUG_PRINT(
                "info:" + GfxdConstants.TRACE_FUNCTION_EX,
                "executeFunctionMessage: retry cnt: " + retryCnt
                    + " exhausted and throwing exception: ", e);
            throw e;
          }
          GemFireXDUtils.sleepForRetry(messageRetryCount);
          final GfxdFunctionReplyMessageProcessor<T> processor = msg.processor;
          if (processor != null) {
            processor.addToFailedNodes(msg);
          }
          // keep the failed nodes list before cloning the message
          final Set<DistributedMember> failedNodes = msg.failedNodes;
          // clone the message to discard any transient fields
          msg = msg.clone();
          // give up the processor before retry since it is unusable
          msg.processor = null;
          // clone the collector if possible
          if (msg.gfxdCollector != null) {
            msg.userCollector = msg.gfxdCollector = msg.gfxdCollector
                .cloneCollector();
            if (GemFireXDUtils.TraceRSIter || GemFireXDUtils.TraceQuery
                || GemFireXDUtils.TraceNCJ) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "#executeFunction: recreated collector: "
                  + msg.gfxdCollector);
            }
          }
          // cleanup any remaining artifacts in case of retries
          if (rs != null) {
            rs.reset(msg.gfxdCollector);
          }
          msg.reset();
          // set the failed nodes list back into the message
          msg.failedNodes = failedNodes;
          isPossibleDuplicate = true;
        }
        else {
          if (GemFireXDUtils.TraceFunctionException) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
                "executeFunctionMessage: retry cnt: " + retryCnt
                  + " retry is false: " + e, (e instanceof RuntimeException)
                  ? ((e instanceof FunctionExecutionException
                      || e instanceof FunctionInvocationTargetException)
                      ? e.getCause() : e) : null);
          }
          throw e;
        }
      } finally {
        if (viewVersion != -1) {
          advisor.endOperation(viewVersion);
          if (TXStateProxy.LOG_VERSIONS) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                ArrayUtils.objectRefString(msg)
                    + " done dispatching operation in view version "
                    + viewVersion);
          }
          viewVersion = -1;
        }
      }
    }
  }

  public int getNumRecipients() {
    if (!forAll()) {
      final InternalDistributedMember[] recipients = getRecipients();
      return recipients != null ? recipients.length : 0;
    }
    return -1; // indicates that message sent to all members
  }

  /**
   * Any message that returns {@link #containsRegionContentChange()} as true
   * should override this method to return the corresponding DistributionAdvisor
   */
  public DistributionAdvisor getDistributionAdvisor() {
    return null;
  }

  protected abstract void executeFunction(boolean enableStreaming)
      throws StandardException, SQLException;

  public abstract boolean isHA();

  public abstract boolean optimizeForWrite();

  /**
   * Returns true if this node is a "secondary" copy that does not need to send
   * back a result.
   */
  public boolean isSecondaryCopy() {
    return false;
  }

  protected GemFireCacheImpl getGemFireCache() {
    return GemFireCacheImpl.getExisting();
  }

  @Override
  public void reset() {
    super.reset();
    this.replySequenceId = 0;
    this.dm = null;
    this.replyTX = null;
  }

  protected final boolean requiresSync() {
    return (this.gfxdCollector == null);
  }

  public final GfxdResultCollector<T> getGfxdResultCollector() {
    return this.gfxdCollector;
  }

  protected boolean allowExecutionOnAdminMembers() {
    return false;
  }

  protected final void executeOnMembers(InternalDistributedSystem sys, DM dm,
      Set<DistributedMember> members, boolean enableStreaming)
      throws StandardException, SQLException {
    // clear previously failed nodes since those may become available in next
    // round of retry
    this.failedNodes = null;
    int numRecipients = members.size();
    if (numRecipients == 0) {
      this.userCollector.endResults();
      return;
    }
    // validate TX and heap critical members
    validateExecution(members);
    // send to others first
    final boolean toSelf;
    final InternalDistributedMember myId = dm.getDistributionManagerId();
    if (numRecipients == 1) {
      if ((toSelf = members.contains(myId))) {
        if (this.processor == null) {
          setProcessor(createReplyProcessor(dm, myId));
        }
        setRecipients(Collections.emptySet());
        numRecipients = 0;
      }
      else {
        final InternalDistributedMember member =
            (InternalDistributedMember)members.iterator().next();
        if (this.processor == null) {
          setProcessor(createReplyProcessor(dm, member));
        }
        setRecipient(member);
      }
    }
    else {
      if (this.processor == null) {
        setProcessor(createReplyProcessor(dm, members));
      }
      toSelf = members.remove(myId);
      if ((numRecipients = members.size()) > 1) {
        setRecipients(members);
      }
      else {
        setRecipient((InternalDistributedMember)members.iterator().next());
      }
    }
    if (numRecipients > 0) {
      this.processor.registerProcessor();
    }
    this.send(sys, dm, this.processor, false, toSelf);
  }

  protected final void executeOnMember(InternalDistributedSystem sys, DM dm,
      InternalDistributedMember member, boolean toSelf, boolean enableStreaming)
      throws StandardException, SQLException {
    // validate TX and heap critical members
    validateExecution(member);
    // send to others first
    if (this.processor == null) {
      // clear previously failed nodes since those may become available in next
      // round of retry
      this.failedNodes = null;
      setProcessor(createReplyProcessor(dm, member));
    }
    if (!toSelf) {
      setRecipient(member);
      this.processor.registerProcessor();
    }
    else {
      // processing in self will happen in the same thread in
      // beforeWaitForReplies
      setRecipients(Collections.emptySet());
    }
    this.send(sys, dm, this.processor, false, toSelf);
  }

  /**
   * Validates whether this function message should execute in presence of
   * transaction and HeapCritical members. If the function is the first
   * operation in a transaction, bootstraps the function.
   * 
   * @param members
   *          the set of members the function will be executed on
   * @throws TransactionException
   *           if more than one nodes are targeted within a transaction
   * @throws LowMemoryException
   *           if the set contains a heap critical member
   */
  protected final void validateExecution(Set<DistributedMember> members) {
    final GemFireCacheImpl cache = getGemFireCache();
    final HeapMemoryMonitor hmm = cache.getResourceManager().getHeapMonitor();
    @SuppressWarnings({ "unchecked", "rawtypes" })
    final Set<InternalDistributedMember> tgtMembers = (Set)members;
    if (optimizeForWrite() && abortOnLowMemory &&
        hmm.containsHeapCriticalMembers(tgtMembers)
        && !MemoryThresholds.isLowMemoryExceptionDisabled()) {
      final Set<InternalDistributedMember> hcm = cache.getResourceAdvisor()
          .adviseCriticalMembers();
      @SuppressWarnings("unchecked")
      final Set<DistributedMember> sm = new THashSet(4);
      GemFireXDUtils.setIntersect(hcm, tgtMembers, sm);
      throw new LowMemoryException(LocalizedStrings
          .ResourceManager_LOW_MEMORY_FOR_0_FUNCEXEC_MEMBERS_1
              .toLocalizedString(new Object[] { this.getClass().getName(),
                  sm }), sm);
    }
  }

  /**
   * Validates whether this function message should execute in presence of
   * transaction and HeapCritical members. If the function is the first
   * operation in a transaction, bootstraps the function.
   * 
   * @param m
   *          the member the function will be executed on
   * @throws TransactionException
   *           if more than one nodes are targeted within a transaction
   * @throws LowMemoryException
   *           if the set contains a heap critical member
   */
  protected final void validateExecution(InternalDistributedMember m) {
    HeapMemoryMonitor hmm = getGemFireCache().getResourceManager()
        .getHeapMonitor();
    if (optimizeForWrite() && !MemoryThresholds.isLowMemoryExceptionDisabled()
        && abortOnLowMemory && hmm.isMemberHeapCritical(m)) {
      throw new LowMemoryException(LocalizedStrings
          .ResourceManager_LOW_MEMORY_FOR_0_FUNCEXEC_MEMBERS_1
              .toLocalizedString(new Object[] {this.getClass().getName(),
                  m }), Collections.<DistributedMember> singleton(m));
    }
  }

  protected GfxdFunctionReplyMessageProcessor<T> createReplyProcessor(DM dm,
      Set<DistributedMember> members) {
    return orderedReplies ? new GfxdFunctionOrderedReplyMessageProcessor<T>(dm,
        members, this) : new GfxdFunctionReplyMessageProcessor<T>(dm, members,
        this);
  }

  protected GfxdFunctionReplyMessageProcessor<T> createReplyProcessor(DM dm,
      InternalDistributedMember member) {
    return orderedReplies ? new GfxdFunctionOrderedReplyMessageProcessor<T>(dm,
        member, this) : new GfxdFunctionReplyMessageProcessor<T>(dm, member,
        this);
  }

  @Override
  protected final void beforeWaitForReplies(
      GfxdReplyMessageProcessor processor, boolean toSelf)
      throws ReplyException {
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(process_time == 0 ? process_time = -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    assert processor == this.processor;
    if (toSelf) { // process on self before waiting for other replies
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "GfxdFunctionMessage#beforeWaitForReplies: executing "
                + "message in this JVM: " + toString());
      }
      setSender(Misc.getMyId());
      this.replySequenceId = 1;
      process(null);
      /* don't throw exception from here; higher layer will throw if required
       * reading from ReplyException anyways
      // throw ReplyException if execution on self failed so as to handle
      // in a consistent manner in higher layers
      final ReplyException replyEx;
      if ((replyEx = processor.getReplyException()) != null) {
        throw replyEx;
      }
      */
    }

    self_execution_time = this.timeStatsEnabled ? XPLAINUtil.recordTiming(begintime) : 0;
  }

  protected final void setProcessor(
      GfxdFunctionReplyMessageProcessor<T> processor) {
    if (this.gfxdCollector != null) {
      // need to store a reference to the processor to avoid it getting GCed
      this.gfxdCollector.setProcessor(processor);
    }
    this.processor = processor;
  }

  @Override
  public final GfxdFunctionReplyMessageProcessor<T> getReplyProcessor() {
    return this.processor;
  }

  @Override
  protected final void handleReplyException(String exPrefix, ReplyException ex,
      GfxdReplyMessageProcessor processor) throws SQLException,
      StandardException {
    ((GfxdFunctionReplyMessageProcessor<?>)processor).handleReplyException(
        exPrefix, ex, this);
  }

  @Override
  protected final void handleProcessorReplyException(String exPrefix,
      ReplyException replyEx) throws SQLException, StandardException {
    handleProcessorReplyException(exPrefix, replyEx.getCause());
  }

  static final void handleProcessorReplyException(String exPrefix,
      Throwable cause) throws SQLException, StandardException {
    GemFireXDRuntimeException.throwSQLOrRuntimeException(exPrefix
        + ": unexpected exception", cause);
  }

  @Override
  protected final void preProcessMessage(final DistributionManager dm) {
    this.replySequenceId = 1;
    this.replyTX = null;
    if (dm != null) {
      this.dm = dm;
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, toString()
            + ": sender created for source " + getSender());
      }
    }
    else {
      this.dm = null;
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, toString()
            + ": sender created for local node.");
      }
    }
  }

  @Override
  protected void processMessage(final DistributionManager dm)
      throws GemFireCheckedException {
    final TXStateProxy txProxy;
    // don't set TX in ResultSender for messages that don't require to
    // flush state; also avoid it for executions on coordinator itself
    if ((txProxy = getTXProxy()) != null
        && !txProxy.skipBatchFlushOnCoordinator()
        && requiresTXFlushAfterExecution()) {
      setReplyTXState(txProxy);
    }
    if (isHA() && !Misc.initialDDLReplayDone()
        && dm != null /*ignore for self execution*/) {
      throw new GfxdDDLReplayInProgressException(
          "Node is in a transient state while executing " + toString());
    }
    try {
      execute();
    } catch (GemFireCheckedException ex) {
      throw ex;
    } catch (Exception ex) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, toString()
            + ".processMessage: exception caught", ex);
      }
      // first check if this cache is going down
      Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(ex);
      if (GemFireXDUtils.retryToBeDone(ex)) {
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "StatementQueryExecutorFunction: wrapping exception: " + ex
                  + " into InternalFunctionInvocationTargetException "
                  + "so that retry happens");
        }
        // avoid double wrapping
        final Throwable cause = ex.getCause();
        if ((cause instanceof SQLException || cause instanceof StandardException)
            && cause.getCause() instanceof FunctionInvocationTargetException) {
          throw (FunctionInvocationTargetException)cause.getCause();
        }
        throw new InternalFunctionInvocationTargetException(ex);
      }
      if (ex instanceof GemFireException) {
        throw (GemFireException)ex;
      }
      throw new FunctionExecutionException(ex);
    }
  }

  @Override
  protected final void sendReply(ReplyException ex, DistributionManager dm) {
    if (ex != null) {
      sendException(ex, this);
    }
  }

  /**
   * Allow starting transactions; child classes can still avoid transaction by
   * passing the constructor parameter "initializeTX" to false.
   */
  @Override
  public boolean canStartRemoteTransaction() {
    return true;
  }

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  @Override
  public boolean useTransactionProxy() {
    return optimizeForWrite();
  }

  /**
   * Returns true if a flush for any pending transactional operations (when
   * batching is on) before execution of the function. Normally GemFireXD
   * distributed functions that do the updates will not require this flag since
   * local coordinator node will be preferred for any transaction updates for
   * which the node is a replica.
   * 
   * TODO: TX: Ensure the above and use this flag to skip flush. Right now we
   * always go to primaries for updates.
   */
  protected boolean requiresTXFlushBeforeExecution() {
    return optimizeForWrite();
  }

  /**
   * Returns true if a flush for any pending transactional operations (when
   * batching is on) after execution of the function on the execution node
   * before sending lastResult.
   */
  protected boolean requiresTXFlushAfterExecution() {
    // normally we always require a flush if message is using transaction proxy
    // and thus has some operations that are batched
    return optimizeForWrite();
  }

  @Override
  public int getMessageProcessorType() {
    return DistributionManager.REGION_FUNCTION_EXECUTION_EXECUTOR;
  }

  @Override
  protected boolean waitForNodeInitialization() {
    // we don't wait for node initialization rather for retry by throwing
    // GfxdDDLReplayInProgressException
    return false;
  }

  /** invoked on the receiving node to process this message */
  protected abstract void execute() throws Exception;

  @Override
  protected abstract GfxdFunctionMessage<T> clone();

  public final short getNumRetryDone() {
    return messageRetryCount;
  }

  // ------------------------------------------
  //       InternalResultSender methods
  // ------------------------------------------

  protected final void setReplyTXState(final TXStateProxy txProxy) {
    // even for local execution we need the TX to flush pending ops since this
    // may be a non-coordinator node in nested function execution; also since
    // nested function execution may read that data potentially from another
    // node, we cannot just batch up even on coordinator; the only optimization
    // that looks possible is to somehow detect first level of function exec
    if (txProxy != null) {
      this.replyTX = txProxy;
      if (TXStateProxy.LOG_FINE) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
            "GfxdFunctionMessage: set reply " + txProxy);
      }
    }
  }

  public final void sendResult(Object oneResult) {
    if (GemFireXDUtils.TraceRSIter | SanityManager.isFinerEnabled) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "GfxdFunctionMessage: sending result to recipient "
              + getSenderForReply() + ": " + oneResult);
    }
    GfxdFunctionReplyMessage replyMsg = new GfxdFunctionReplyMessage(oneResult,
        this.replySequenceId, false, this.processorId, this, false, false);
    putReply(replyMsg);
  }

  public final void sendException(Throwable t) {
    sendException(new ReplyException(t), this);
  }

  public final void setException(Throwable t) {
    sendException(new ReplyException(t), this);
  }

  final void sendException(final ReplyException replyEx,
      final GfxdFunctionMessage<?> fnMsg) {
    if (GemFireXDUtils.TraceRSIter | SanityManager.isFinerEnabled) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "GfxdFunctionMessage: sending exception to recipient "
              + getSenderForReply() + ": " + replyEx);
    }
    GfxdFunctionReplyMessage replyMsg = new GfxdFunctionReplyMessage(fnMsg);
    replyMsg.setException(replyEx);
    replyMsg.setProcessorId(this.processorId);
    putReply(replyMsg);
  }

  public final void lastResult(final Object oneResult) {
    lastResult(oneResult, true, !this.isSecondaryCopy(), true);
  }

  public final void lastResult(final Object oneResult,
      final boolean doTXFlush, final boolean sendTXChanges,
      final boolean finishTXRead) {
    final TXStateProxy txProxy;
    if (doTXFlush && (txProxy = this.replyTX) != null) {
      // flush all batched TXStateProxy ops before sending lastResult
      txProxy.flushPendingOps(this.dm);
    }
    else if (this.txProxy == null) {
      // check if there was a TX started within function body then wait for
      // commit before returning
      final TXManagerImpl.TXContext txContext = TXManagerImpl
          .currentTXContext();
      if (txContext != null) {
        txContext.waitForPendingCommit();
      }
    }
    if (GemFireXDUtils.TraceRSIter | SanityManager.isFinerEnabled) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "GfxdFuntionMessage: sending last result to recipient "
              + getSenderForReply() + " with " + getTXState() + ": "
              + oneResult);
    }
    // before sending lastResult, check if any bucket has moved
    checkAllBucketsHosted();
    GfxdFunctionReplyMessage replyMsg = new GfxdFunctionReplyMessage(oneResult,
        this.replySequenceId, true, this.processorId, this, sendTXChanges,
        finishTXRead);
    putReply(replyMsg);
    this.replySequenceId = TERMINAL_REPLY_SEQID;
  }

  /**
   * Check if all buckets required for the function are still hosted at the end
   * of execution before sending lastResult.
   * 
   * @throws BucketMovedException
   *           if a bucket used by function was moved during execution
   */
  public abstract void checkAllBucketsHosted() throws BucketMovedException;

  public final void enableOrderedResultStreaming(boolean enable) {
    Assert.fail("ordering expected to be invoked on sender and not receiver");
  }

  private void putReply(GfxdFunctionReplyMessage replyMsg) {
    if (this.dm != null) {
      replyMsg.setRecipient(getSenderForReply());
      ReplySender sender = getReplySender(this.dm);
      // don't send anything after lastResult with DirectReplySender
      if (this.replySequenceId != TERMINAL_REPLY_SEQID
          || !(sender instanceof DirectReplySender)) {
        sender.putOutgoing(replyMsg);
      }
    }
    // process for self in place
    else {
      replyMsg.setSender(Misc.getMyId());
      this.processor.process(replyMsg);
    }
    // can happen if an exception is sent after lastResult invocation
    if (this.replySequenceId != TERMINAL_REPLY_SEQID) {
      this.replySequenceId++;
    }
  }

  public final InternalDistributedMember getSenderForReply() {
    return this.dm != null ? this.sender : Misc.getMyId();
  }

  @Override
  public final boolean isLocallyExecuted() {
    return (this.dm == null);
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";isLocallyExecuted=" + isLocallyExecuted());
  }
  
  // This method is added in InternalResultSender as a fix for bug#41325, in
  // which we are having a check whether the last result is sent or not. If not
  // then we have to throw a Function Exception saying
  // "Function did not sent last result". Not used by GemFireXD yet.
  public final boolean isLastResultReceived() {
    return (this.replySequenceId == TERMINAL_REPLY_SEQID);
  }

  // ------------------------------------------
  //       End InternalResultSender methods
  // ------------------------------------------

  /**
   * Replies for {@link GfxdFunctionMessage}s.
   */
  public static final class GfxdFunctionReplyMessage extends GfxdReplyMessage {

    Object singleResult;

    GfxdResponseCode responseCode;

    // statistics data
    // ----------------
    /**
     * difference between request message on origin node & reply message
     * construct_time on far node minus ser-deser-time of msg on both ends with
     * give reply n/w transmission time.
     * 
     * comparing two consecutive construct_time of reply messages on request
     * origin node will give rate of replies by the far nodes.
     */
    protected Timestamp construct_time;

    /** captures reply serialization/deserialization time*/
    protected long ser_deser_time;

    /**
     * a. captures originating node result iteration time.
     * b. receiving node captures RC.addResult time.
     */
    protected long process_time;
    
    protected long[] single_result_statistics; 
    // end of statistics data

    /** for deserialization */
    public GfxdFunctionReplyMessage() {
      this(null);
    }

    public GfxdFunctionReplyMessage(GfxdFunctionMessage<?> fnMsg) {
      super(fnMsg, true, true,
          false /* all function messages handle their own flush */);
      if (this.timeStatsEnabled) {
        this.construct_time = XPLAINUtil.currentTimeStamp();
      }
      else {
        this.construct_time = null;
      }

      this.singleResult = null;
      this.responseCode = GfxdResponseCode.EXCEPTION; // default
    }

    GfxdFunctionReplyMessage(Object result, int sequenceId,
        boolean isLast, int processorId, GfxdFunctionMessage<?> fnMsg,
        boolean sendTXChanges, boolean finishTXRead) {
      super(fnMsg, sendTXChanges, finishTXRead,
          false /* all function messages handle their own flush */);
      if (this.timeStatsEnabled) {
        this.construct_time = XPLAINUtil.currentTimeStamp();
        if (!isLast && result instanceof ResultHolder) {
          single_result_statistics = ((ResultHolder)result).snapshotStatistics();
        }
        fnMsg.replySentMsgs.add(this);
      }
      else {
        this.construct_time = null;
      }

      this.singleResult = result;
      
      if (isLast) {
        this.responseCode = GfxdResponseCode.GRANT(sequenceId);
      }
      else {
        this.responseCode = GfxdResponseCode.WAITING(sequenceId);
      }
      this.processorId = processorId;
    }

    @Override
    public GfxdResponseCode getResponseCode() {
      return this.responseCode;
    }

    @Override
    public void setException(ReplyException ex) {
      super.setException(ex);
      this.responseCode = GfxdResponseCode.EXCEPTION;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      if (!this.timeStatsEnabled) {
        this.responseCode.toData(out);
        DataSerializer.writeObject(this.singleResult, out);
      }
      else {
        final long begintime = XPLAINUtil.recordTiming(-1);
        this.responseCode.toData(out);
        DataSerializer.writeObject(this.singleResult, out);
        ser_deser_time = XPLAINUtil.recordTiming(begintime);
      }
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      if (!this.timeStatsEnabled) {
        this.responseCode = GfxdResponseCode.fromData(in);
        this.singleResult = DataSerializer.readObject(in);
      }
      else {
        this.construct_time = XPLAINUtil.currentTimeStamp();
        this.responseCode = GfxdResponseCode.fromData(in);
        this.singleResult = DataSerializer.readObject(in);
        this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
      }
    }

    @Override
    public byte getGfxdID() {
      return FUNCTION_REPLY_MESSAGE;
    }

    @Override
    protected StringBuilder getStringBuilder() {
      return super.getStringBuilder().append(" with responseCode=").append(
          this.responseCode).append(" singleResult=").append(this.singleResult);
    }

    /* statistics collection methods */
    public final Timestamp getConstructTime() {
      return construct_time;
    }

    public final long getSerializeDeSerializeTime() {
      return ser_deser_time;
    }

    public final long getProcessTime() {
      return process_time;
    }

    public final Object getSingleResult() {
      return singleResult;
    }
    
    public long[] getSingleResultStatistics() {
      return single_result_statistics;
    }
    /* end statistics collection methods */

  }

  /**
   * Handles replies for {@link GfxdFunctionMessage}s by invoking
   * {@link ResultCollector#addResult(DistributedMember, Object)} if
   * appropriate. Also ends the {@link ResultCollector} by invoking its
   * endResults() method in the postFinish() method. If a member departs without
   * sending back a reply then this raises a {@link CacheClosedException} so
   * that function message can be retried from the higher level.
   * 
   * @author swale
   */
  protected static class GfxdFunctionReplyMessageProcessor<T> extends
      GfxdWaitingReplyProcessorBase implements GfxdResultCollector<T> {

    protected ResultCollector<Object, T> userCollector;

    protected final boolean recordStats;

    protected final boolean allowExecutionOnAdminMembers;

    protected final List<GfxdFunctionReplyMessage> replyReceivedMsgs;

    protected volatile Set<DistributedMember> failedNodes;

    protected final boolean isDirectReplyMessage;

    protected final StoppableCountDownLatch latch;

    protected final GfxdResultCollectorHelper helper;

    public GfxdFunctionReplyMessageProcessor(DM dm,
        Set<DistributedMember> members, GfxdFunctionMessage<T> msg) {
      super(dm, members, false);
      this.userCollector = msg.userCollector;
      this.recordStats = msg.timeStatsEnabled;
      this.replyReceivedMsgs = msg.replyReceivedMsgs;
      this.allowExecutionOnAdminMembers = msg.allowExecutionOnAdminMembers();
      this.isDirectReplyMessage = (msg instanceof DirectReplyMessage
          && ((DirectReplyMessage)msg).supportsDirectAck());
      if (msg.requiresSync()) {
        this.latch = new StoppableCountDownLatch(dm.getCancelCriterion(), 1);
      }
      else {
        this.latch = null;
      }
      this.helper = new GfxdResultCollectorHelper();
    }

    public GfxdFunctionReplyMessageProcessor(DM dm,
        InternalDistributedMember member, GfxdFunctionMessage<T> msg) {
      super(dm, member, false);
      this.userCollector = msg.userCollector;
      this.recordStats = msg.timeStatsEnabled;
      this.replyReceivedMsgs = msg.replyReceivedMsgs;
      this.allowExecutionOnAdminMembers = msg.allowExecutionOnAdminMembers();
      this.isDirectReplyMessage = (msg instanceof DirectReplyMessage
          && ((DirectReplyMessage)msg).supportsDirectAck());
      if (msg.requiresSync()) {
        this.latch = new StoppableCountDownLatch(dm.getCancelCriterion(), 1);
      }
      else {
        this.latch = null;
      }
      this.helper = new GfxdResultCollectorHelper();
    }

    @Override
    public void process(final DistributionMessage msg) {
      boolean waiting = false;
      try {
        if (msg instanceof GfxdFunctionReplyMessage) {
          final long begintime = this.recordStats ? XPLAINUtil
              .recordTiming(-1) : 0;

          GfxdFunctionReplyMessage replyMsg = (GfxdFunctionReplyMessage)msg;
          final GfxdResponseCode responseCode = replyMsg.getResponseCode();
          waiting = addResult(replyMsg, responseCode);

          if (begintime != 0) {
            this.replyReceivedMsgs.add(replyMsg);
            replyMsg.process_time = XPLAINUtil.recordTiming(begintime);
          }
        }
        else if (msg instanceof ReplyMessage) {
          // exception thrown during deserialization etc. before process
          // will come as ReplyMessage from lower P2P Connection layer
          final ReplyMessage replyMsg = (ReplyMessage)msg;
          // we can only handle exceptions at this point
          if (replyMsg.getException() != null && this.latch == null) {
            addResult(replyMsg.getSender(), replyMsg.getException());
          }
        }
      } finally {
        // don't remove from member list if more replies are expected
        if (!waiting) {
          super.process(msg);
        }
      }
    }

    /**
     * Add a reply message from a member having given {@link GfxdResponseCode}.
     * 
     * @return true if more results are expected from the member else false
     */
    protected boolean addResult(final GfxdFunctionReplyMessage replyMsg,
        final GfxdResponseCode responseCode) {
      if (responseCode.isGrant() || responseCode.isWaiting()) {
        addResult(replyMsg.getSender(), replyMsg.singleResult);
      }
      else {
        if (!responseCode.isException()) {
          Assert.fail("GfxdFunctionReplyMessageProcessor: "
              + "unexpected responseCode=" + responseCode);
        }
        if (this.latch == null) {
          addResult(replyMsg.getSender(), replyMsg.getException());
        }
      }
      return  processResponseCode(replyMsg, responseCode);
    }

    @Override
    protected final boolean allowReplyFromSender() {
      return true;
    }

    public final void registerProcessor() {
      // don't register the processor if this is for a DirectReplyMessage
      if (this.processorId == 0 && !this.isDirectReplyMessage) {
        super.register();
      }
    }

    @Override
    protected boolean isDirectReplyProcessor() {
      return this.isDirectReplyMessage;
    }

    @Override
    public final boolean isExpectingDirectReply() {
      return this.isDirectReplyMessage && this.processorId == 0;
    }

    @Override
    public void memberDeparted(final InternalDistributedMember member,
        final boolean crashed) {
      if (member != null && waitingOnMember(member)) {
        final ReplyException replyEx = new ReplyException(
            new CacheClosedException(LocalizedStrings
                .MemberMessage_MEMBERRESPONSE_GOT_MEMBERDEPARTED_EVENT_FOR_0_CRASHED_1
                    .toLocalizedString(new Object[] { member,
                        String.valueOf(crashed) })));
        // for GemFireXD streaming result collector also add the exception
        // as result so that it can be thrown during getResult() or iteration
        if (this.latch == null) {
          addResult(member, replyEx);
        }
        // add a CacheClosedException for the member for retries
        processExceptionFromMember(member, replyEx);
      }
      super.memberDeparted(member, crashed);
    }

    @Override
    protected void addGrantedMember(DistributedMember member) {
      // nothing to be done
    }

    @Override
    protected final void postFinish() {
      if (!isExpectingDirectReply()) {
        // for direct replies, invoke endResults in getResult
        endResults();
      }
      // called at last after results end
      super.postFinish();
    }

    @Override
    protected final Set<DistributedMember> virtualReset() {
      return null;
    }

    // GfxdResultCollector methods

    public final void addResult(DistributedMember memberId,
        Object resultOfSingleExecution) {
      final ResultCollector<Object, T> userCollector = this.userCollector;
      if (this.latch == null) {
        userCollector.addResult(memberId, resultOfSingleExecution);
      }
      else {
        synchronized (userCollector) {
          if (!(resultOfSingleExecution instanceof Throwable)) {
            this.helper.addResultMember(memberId);
          }
          userCollector.addResult(memberId, resultOfSingleExecution);
        }
      }
    }

    public final T getResult() throws FunctionException {
      waitForResult();
      if (this.latch == null) {
        return this.userCollector.getResult();
      }
      else {
        // synchronized here is not really required and serves no useful purpose
        // but looks like a JDK bug that causes a memory barrier to be not
        // honoured correctly that later results in a very rare
        // ConcurrentModification during results iteration by the caller
        synchronized (this.userCollector) {
          return this.userCollector.getResult();
        }
      }
    }

    public final T getResult(long timeout, TimeUnit unit)
        throws FunctionException, InterruptedException {
      waitForResult();
      return this.userCollector.getResult(timeout, unit);
    }

    public final void endResults() {
      final ResultCollector<Object, T> userCollector = this.userCollector;
      if (this.latch != null) {
        synchronized (userCollector) {
          userCollector.endResults();
        }
        this.latch.countDown();
      }
      else {
        userCollector.endResults();
      }
    }

    public final void clearResults() {
      //Make sure this reply processor is cleaned up if it is waiting.
      if (this.startedWaiting) {
        endWait();
      }
      final ResultCollector<Object, T> userCollector = this.userCollector;
      if (this.latch != null) {
        synchronized (userCollector) {
          userCollector.clearResults();
        }
      }
      else {
        userCollector.clearResults();
      }
      this.failedNodes = null;
    }

    protected final void waitForResult() throws FunctionException {
      try {
        if (isExpectingDirectReply()) {
          // for direct replies, end the results at this point
          endResults();
        }
        else {
          waitForReplies();
          if (this.latch != null) {
            this.latch.await();
          }
        }
        final ReplyException replyEx = this.exception;
        if (replyEx != null) {
          throw replyEx;
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        getDistributionManager().getCancelCriterion().checkCancelInProgress(ie);
      } catch (ReplyException ex) {
        try {
          handleReplyException(toString(), ex, null);
        } catch (StandardException se) {
          throw new FunctionException(se);
        } catch (SQLException se) {
          throw new FunctionException(se);
        }
      } finally {
        // it is possible that postWait() has still not been invoked for the
        // case when only self-execution has happened (#47198)
        if (this.startedWaiting) {
          postWait();
        }
      }
    }

    @SuppressWarnings("unchecked")
    protected synchronized void handleReplyException(String exPrefix,
        ReplyException ex, GfxdFunctionMessage<?> fnMsg) throws SQLException,
        StandardException {
      final Map<DistributedMember, ReplyException> exceptions =
          getReplyExceptions();
      // first check for failed nodes without failing in middle
      if (exceptions != null) {
        ReplyException failureException = null;
        for (Map.Entry<DistributedMember, ReplyException> entry : exceptions
            .entrySet()) {
          final ReplyException replyEx = entry.getValue();
          if (GemFireXDUtils.retryToBeDone(replyEx.getCause())) {
            // check if node failed
            if (GemFireXDUtils.nodeFailureException(replyEx.getCause())) {
              if (this.failedNodes == null) {
                this.failedNodes = new THashSet(5);
              }
              this.failedNodes.add(entry.getKey());
            }
            failureException = replyEx;
          }
        }
        addToFailedNodes(fnMsg);
        // If there was a failure then throw that one
        if (failureException != null) {
          handleProcessorReplyException(exPrefix, failureException.getCause());
        }
        // There may be more than one exception, so check all of them
        for (ReplyException replyEx : exceptions.values()) {
          handleProcessorReplyException(exPrefix, replyEx.getCause());
        }
      }
      // if somehow nothing was done for any exception try for the passed one
      handleProcessorReplyException(exPrefix, ex.getCause());
    }

    @SuppressWarnings("unchecked")
    protected synchronized final void addToFailedNodes(
        final GfxdFunctionMessage<?> fnMsg) {
      if (fnMsg != null && this.failedNodes != null) {
        if (fnMsg.failedNodes == null) {
          fnMsg.failedNodes = new THashSet();
        }
        fnMsg.failedNodes.addAll(this.failedNodes);
      }
    }

    @Override
    protected final Set<?> addListenerAndGetMembers() {
      if (!this.allowExecutionOnAdminMembers) {
        return getDistributionManager()
            .addMembershipListenerAndGetDistributionManagerIds(this);
      }
      else {
        // add to all membership listener to enable reading replies from
        // admin members for GfxdConfigMessage for example
        return getDistributionManager().addAllMembershipListenerAndGetAllIds(
            this);
      }
    }

    @Override
    protected final void removeListener() {
      try {
        if (!this.allowExecutionOnAdminMembers) {
          getDistributionManager().removeMembershipListener(this);
        }
        else {
          getDistributionManager().removeAllMembershipListener(this);
        }
      } catch (DistributedSystemDisconnectedException e) {
        // ignore
      }
    }

    public final void setProcessor(ReplyProcessor21 processor) {
    }

    public final ReplyProcessor21 getProcessor() {
      return this;
    }

    public final void setResultMembers(Set<DistributedMember> members) {
      this.helper.setResultMembers(members);
    }

    public final Set<DistributedMember> getResultMembers() {
      return this.helper.getResultMembers();
    }

    public final boolean setupContainersToClose(
        Collection<GemFireContainer> containers, GemFireTransaction tran)
        throws StandardException {
      final GfxdResultCollector<?> gfxdCollector;
      if (this.latch == null && (gfxdCollector = getGfxdResultCollector(
          this.userCollector)) != null) {
        return gfxdCollector.setupContainersToClose(containers, tran);
      }
      else {
        return false;
      }
    }

    public final void setNumRecipients(int n) {
    }

    public final GfxdResultCollectorHelper getStreamingHelper() {
      return null;
    }

    public final void setException(Throwable exception) {
      throw new GemFireXDRuntimeException(
          "not expected to be invoked in GemFireXD");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GfxdResultCollector<T> cloneCollector() {
      if (this.userCollector instanceof GfxdResultCollector<?>) {
        this.userCollector = ((GfxdResultCollector<T>)this.userCollector)
            .cloneCollector();
      }
      return this;
    }
  }

  /**
   * Extension to {@link GfxdFunctionReplyMessageProcessor} that reorders the
   * replies in the order sent from each node for cases where higher layers
   * expect replies in order (e.g. in ORDER BY).
   * 
   * @author swale
   */
  protected static class GfxdFunctionOrderedReplyMessageProcessor<T> extends
      GfxdFunctionReplyMessageProcessor<T> {

    /**
     * Holds the out of order list of replies, if any, from a single member
     * while also tracking the expected replies and the last sequential reply
     * that was flushed.
     */
    protected static final class ListOfReplies {

      protected int expectedReplies;

      protected int lastFlushedId;

      private static final Object[] zeroLenArray = new Object[0];

      /**
       * The array buffer into which the elements of the list are stored.
       */
      private Object[] elementData;

      private int size;

      public ListOfReplies(int capacity) {
        this.elementData = capacity == 0 ? zeroLenArray
            : new Object[capacity];
      }

      /**
       * Add a new result to the list of pending replies, or flush to the
       * processor if ordered subsequence is found.
       * 
       * @return true if more results are expected from the member else false
       */
      public boolean add(final GfxdFunctionReplyMessageProcessor<?> processor,
          final DistributedMember memberId,
          final Object resultOfSingleExecution, final int sequenceId,
          final boolean isLastResult) {
        assert sequenceId > 0;

        if (isLastResult) {
          this.expectedReplies = sequenceId;
        }
        final int size = this.size;
        if (sequenceId == (this.lastFlushedId + 1)) { // in order
          processor.addResult(memberId, resultOfSingleExecution);
          this.lastFlushedId++;
          if (size > 0) {
            int pos = 0;
            for (final Object result : this.elementData) {
              if (result != null) {
                if (GemFireXDUtils.TraceQuery | SanityManager.isFineEnabled
                    | GemFireXDUtils.TraceNCJ) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                      "ListOfReplies for " + processor.getProcessorId()
                          + ": processing previously unordered message "
                          + "with sequence " + (this.lastFlushedId + 1)
                          + ", expectedReplies=" + this.expectedReplies);
                }
                processor.addResult(memberId, result);
                pos++;
                this.lastFlushedId++;
              }
              else if (pos == 0) { // first element flushed above
                pos++;
              }
              else {
                break;
              }
            }
            // remove the flushed ones
            if (pos > 0) {
              removeTo(pos);
            }
          }
          // check if last reply has been received
          assert expectedReplies == 0 || lastFlushedId <= expectedReplies:
              "lastFlushedId=" + lastFlushedId + ", expectedReplies="
              + expectedReplies;
          return (this.lastFlushedId != this.expectedReplies);
        }
        else { // out of order
          final int insertIndex = sequenceId - this.lastFlushedId - 1;
          if (insertIndex >= size) {
            final int newSize = insertIndex + 1;
            ensureCapacity(newSize);
            this.size = newSize;
          }
          if (GemFireXDUtils.TraceQuery | SanityManager.isFineEnabled) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "ListOfReplies for " + processor.getProcessorId()
                    + ": queueing unordered message with sequence "
                    + sequenceId + " (lastFlushedId=" + this.lastFlushedId
                    + ") at index " + insertIndex);
          }
          this.elementData[insertIndex] = resultOfSingleExecution;
          return true;
        }
      }

      private void removeTo(int toIndex) {
        final int newSize = this.size - toIndex;
        if (newSize > 0) {
          System.arraycopy(this.elementData, toIndex, this.elementData,
              0, newSize);
        }
        for (int index = newSize; index < this.size; index++) {
          this.elementData[index] = null;
        }
        this.size = newSize;
      }

      private void ensureCapacity(int minCapacity) {
        final int oldCapacity = this.elementData.length;
        if (oldCapacity == 0) {
          int newCapacity = 4;
          if (newCapacity < minCapacity) {
            newCapacity = minCapacity;
          }
          this.elementData = new Object[newCapacity];
        }
        else if (minCapacity > oldCapacity) {
          int newCapacity = (oldCapacity * 3) / 2 + 1;
          if (newCapacity < minCapacity) {
            newCapacity = minCapacity;
          }
          this.elementData = Arrays.copyOf(this.elementData, newCapacity);
        }
      }
    }

    /**
     * Map of a DistributedMember to its pending {@link ListOfReplies}.
     */
    protected final THashMapWithCreate pendingReplies;

    static final THashMapWithCreate.ValueCreator pendingListCreator =
        new THashMapWithCreate.ValueCreator() {
      @Override
      public final Object create(Object key, Object params) {
        // create with size zero optimizing for the case when no out of order
        // replies are received
        return new ListOfReplies(0);
      }
    };

    public GfxdFunctionOrderedReplyMessageProcessor(DM dm,
        Set<DistributedMember> members, GfxdFunctionMessage<T> msg) {
      super(dm, members, msg);
      this.pendingReplies = new THashMapWithCreate();
    }

    public GfxdFunctionOrderedReplyMessageProcessor(DM dm,
        InternalDistributedMember member, GfxdFunctionMessage<T> msg) {
      super(dm, member, msg);
      this.pendingReplies = new THashMapWithCreate();
    }

    /**
     * @see GfxdFunctionReplyMessageProcessor#addResult(
     *      GfxdFunctionReplyMessage, GfxdResponseCode)
     */
    @Override
    protected synchronized boolean addResult(
        final GfxdFunctionReplyMessage replyMsg,
        final GfxdResponseCode responseCode) {
      final InternalDistributedMember sender = replyMsg.getSender();
      final boolean isLastResult = responseCode.isGrant();
      if (isLastResult || responseCode.isWaiting()) {
        final Object replies = this.pendingReplies.create(sender,
            pendingListCreator, null);
        if (replies != Token.DESTROYED) {
          return ((ListOfReplies)replies).add(this, sender, replyMsg
              .singleResult, isLastResult ? responseCode.grantedSequenceId()
                  : responseCode.waitingSequenceId(), isLastResult);
        }
        else {
          // already received an exception from the member, so skip this
          return true;
        }
      }
      else {
        if (!responseCode.isException()) {
          Assert.fail("GfxdFunctionOrderedReplyMessageProcessor: "
              + "unexpected responseCode=" + responseCode);
        }
        this.pendingReplies.put(sender, Token.DESTROYED);
        if (this.latch == null) {
          addResult(sender, replyMsg.getException());
        }
        return false;
      }
    }
  }

  /* statistics collection methods */
  public void setDistributionStatistics(XPLAINDistPropsDescriptor distdesc,
      boolean processReplySend) {

    distdesc.locallyExecuted = isLocallyExecuted();
    distdesc.setDistObjectName(this.getClass().getName());
    distdesc.setQueryRetryCount(this.messageRetryCount);

    distdesc.setMemberMappingRetryCount(mapping_retry_count);

    distdesc.setBeginScatterTime(begin_scatter_time);
    distdesc.setMemberMappingTime(member_mapping_time);

    // process_time encompassing member_mapping_time, scatter_time & rest of the
    // distribution time to all members.
    distdesc.setSerDeSerTime(ser_deser_time);
    distdesc.setProcessTime(process_time);
    distdesc.setThrottleTime(0);

    distdesc.processMemberSentMessages(membersMsgsSent, this);

    // once individual sent messages are processed, record the pruning list
    // before processing reply messages.
    distdesc.setPrunedMembers(distdesc.getRecipients(this, true));

    // okay this is special case of self distribution.
    if (replySentMsgs != null && replyReceivedMsgs != null) {
      if (processReplySend) {
        distdesc.processMemberReplyMessages(replySentMsgs);
      }
      else {
        distdesc.processMemberReplyMessages(replyReceivedMsgs);
      }
    }
    // okay in remote server node or in query node.
    else {
      distdesc.processMemberReplyMessages(replySentMsgs);
      distdesc.processMemberReplyMessages(replyReceivedMsgs);
    }
  }

  public final Timestamp getConstructTime() {
    return construct_time;
  }

  public final long getSerializeDeSerializeTime() {
    return ser_deser_time;
  }

  public final long getProcessTime() {
    return process_time;
  }

  public final long getRootMessageSendTime() {
    return root_msg_send_time;
  }

  /* end statistics collection methods */

  // below method is now only used for VTI executions that require the
  // execution to be on all copies of data to gather stats etc.
  public void setSendToAllReplicates(boolean includeAdmin) {
  }
}
