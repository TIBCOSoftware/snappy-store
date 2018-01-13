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

package com.pivotal.gemfirexd.internal.engine.ddl;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.DistributionMessage;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.MessageWithReply;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdReplyMessageProcessor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdWaitingReplyProcessor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GfxdDDLReplayInProgressException;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockService;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.collection.LongObjectHashMap;

/**
 * {@link DistributionMessage} for sending GemFireXD DDL statements to members
 * of DistributedSystem.
 * 
 * Note: Earlier approach of using listeners causes deadlocks unless region
 * creation happens in a separate non-blocking thread, and the originating node
 * then has to keep track of completed nodes separately. Also, it is a problem
 * to have SYS tables as replicated and then rely on listeners for the SYS
 * tables only. Firstly, each DDL statement can lead to additions/changes in
 * many SYS tables e.g. "CREATE TABLE" can cause modification of SYSTABLE,
 * SYSCOLUMN (for column and other constraints) which have to be interlinked
 * i.e. SYSTABLE listener has to keep track of corresponding changes in
 * SYSCOLUMN to get region attributes. Secondly, Derby stores all this
 * information and more in memory so the top down execution is required in any
 * case. Here we opt for execution of the whole DDL on each of the nodes.
 * However, this approach implies that the internal conglomerate IDs will be
 * different in different VMs.
 * 
 * @author Sumedh Wale
 * @since 6.0
 */
public final class GfxdDDLMessage extends GfxdMessage implements
    MessageWithReply {

  /**
   * The arguments for the DDL message including DDL string and connection ID.
   */
  DDLArgs args;

  /**
   * The map of DDL statement IDs to the GfxdDDLMessage for the DDLs having
   * pending commit/rollback messages.
   */
  private static final LongObjectHashMap<GfxdDDLMessage> pendingDDLMessages =
      LongObjectHashMap.withExpectedSize(4);

  /**
   * MembershipListener to commit/rollback DDLs if node fails before sending
   * commit/rollback. This is not registred with the DistributionManager rather
   * given to {@link GfxdDRWLockService} so that write lock release is done
   * after the commit/rollback is complete.
   */
  private static final DDLListener departedLister = new DDLListener();

  public static void send(InternalDistributedSystem system,
      GfxdWaitingReplyProcessor processor,
      Set<DistributedMember> members, DDLConflatable ddl,
      long connId, long ddlId, LanguageConnectionContext lcc) 
          throws StandardException, SQLException {
    send(system, processor, members, ddl, connId, ddlId, lcc, false);
  }
  /**
   * Sends an {@link GfxdDDLMessage} for given DDL statement to all members of
   * the distributed system.
   */
  public static void send(InternalDistributedSystem system,
      GfxdReplyMessageProcessor processor, Set<DistributedMember> members,
      DDLConflatable ddl, long connId, long ddlId, LanguageConnectionContext lcc,
      boolean persistOnHDFS)
      throws StandardException, SQLException {
    final GfxdDDLMessage msg = new GfxdDDLMessage();
    msg.args = new DDLArgs(ddl, connId, ddlId, lcc.statsEnabled(),
        lcc.timeStatsEnabled(), persistOnHDFS);
    msg.setRecipients(members);
    msg.send(system, system.getDistributionManager(), processor, true, false);
  }

  /**
   * Get the MembershipListener to cleanup any pending DDL commit/rollback when
   * a node departs from DistributedSystem.
   */
  public static MembershipListener getMemberDepartedListener() {
    return departedLister;
  }

  @Override
  protected void processMessage(final DistributionManager dm)
      throws GemFireCheckedException {
    final GemFireStore memStore = Misc.getMemStore();
    final LogWriter logger = dm.getLoggerI18n().convertToLogWriter();
    final DDLConflatable ddl = this.args.getDDL();
    final long ddlId = this.args.id;
    // Wait for initial DDL replay to complete in case last round in progress
    // If we were going to skip this execution then check for special cases of
    // CREATE + DROP/ALTER combos first and see if CREATE has already been
    // completed or is in the process of being executed

    // With the new logic of not allowing new ddls to be fired when replay is
    // going on the skipping logic is removed and instead an assertion is put
    // that ddl replay should not be in progress when ddl is received.
    if (!memStore.initialDDLReplayDone()) {
      throw new GfxdDDLReplayInProgressException("Received ddl " + ddl +
          " from sender " + getSender() + " while ddl replay is still in progress");
    }
    // Commenting ou the below code because with the simplified ddl replay logic
    // during restart we are taking dd read lock so on the fly ddl won't even reach here.
    /*
    if (isOKToSkip(ddlId)) {
      boolean skipDDL = true;
      // check if this is a conflatable then in that case try conflation and if
      // not possible due to execution in progress, then wait for DDL replay to
      // be complete
      final boolean conflate = ddl.shouldBeConflated();
      if (conflate || ddl.shouldDelayRegionInitialization()) {
        // try to conflate items from queue and wait for any executing conflated
        // entries; if any of the entries to be conflated has been executed and
        // this DDL has not been executed, then wait for entire DDL replay to
        // be complete
        final GfxdDDLRegionQueue ddlQ = memStore.getDDLQueueNoThrow();
        if (ddlQ != null) {
          if (conflate) {
            ArrayList<QueueValue> conflatedItems = new ArrayList<QueueValue>(5);
            memStore.acquireDDLReplayLock(true);
            try {
              if (ddlQ.conflate(ddl, ddl, false, conflatedItems)) {
                for (QueueValue qValue : conflatedItems) {
                  final DDLConflatable confVal = (DDLConflatable)qValue
                      .getValue();
                  if (confVal.isExecuting()) {
                    // now we have to wait for entire DDL replay to complete
                    // to maintain the order of DDLs
                    skipDDL = false;
                    break;
                  }
                }
              }
              // if we do not need to wait for DDL replay, then conflate the
              // queue and add this to processed IDs set
              if (skipDDL && ddlQ.conflate(ddl, ddl, true, null)) {
                final TLongHashSet processedIds = memStore.getProcessedDDLIDs();
                synchronized (processedIds) {
                  processedIds.add(ddlId);
                }
              }
            } finally {
              memStore.releaseDDLReplayLock(true);
            }
          }
          else {
            // for ALTER TABLE we have to always wait for DDL replay to complete
            // since we cannot pre-conflate the queue if CREATE is not done like
            // for DROP
            skipDDL = false;
          }
          if (!skipDDL) {
            // ok, we do need to wait for DDL replay to complete;
            // if DDL replay has reached the last iteration and is waiting
            // for DD read lock, then skip wait on DD read lock by the node
            // replay thread, if any, otherwise it can lead to a deadlock
            // (#47873); this DDL message execution means that DD write lock is
            // already held by the source node and the purpose of not allowing
            // any more DDLs to come in by the DD read lock in replay thread is
            // already served
            memStore.setInitialDDLReplayWaiting(true);
            try {
              GemFireXDUtils.waitForNodeInitialization();
            } finally {
              memStore.setInitialDDLReplayWaiting(false);
            }
            // its possible that this DDL has been executed in replay
            // by this time, so check again
            final TLongHashSet processedIds = memStore.getProcessedDDLIDs();
            synchronized (processedIds) {
              skipDDL = processedIds.contains(ddlId);
            }
          }
        }
      }
      if (skipDDL) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY, toString()
            + " Skipping execution of " + ddl.getValueToConflate()
            + " due to replay in progress");
        // Initial replay still in progress so just skip processing this message
        // which will be later handled by replay; this breaks the behaviour of
        // GfxdDDLMessage somewhat since a successful reply is sent without the
        // DDL still having being executed but should have no improper behaviour
        // as such; if we block, then DDLs on all processes will block till the
        // initial replay of this VM is complete which is not desirable.

        // store in pending message list in any case since put in DDL region
        // will need to be done if origin node goes down before finish message
        synchronized (pendingDDLMessages) {
          // indicates that this node should not execute anything in finish
          // message or if origin node goes down before finish message
          this.args.connId = EmbedConnection.UNINITIALIZED;
          pendingDDLMessages.put(ddlId, this);
        }
        throw new GfxdDDLReplayInProgressException(
            "skipping DDL execution due to replay in progress");
      }
    }
*/
    // skip DDL execution on locators/agents/admins
    if (!GemFireXDUtils.getMyVMKind().isAccessorOrStore()) {
      if (GemFireXDUtils.TraceDDLQueue) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
            + " Skipping execution of DDL on " + GemFireXDUtils.getMyVMKind()
            + " JVM");
      }
      // store in pending message list in any case since put in DDL region
      // will need to be done if origin node goes down before finish message
      synchronized (pendingDDLMessages) {
        // indicates that this node should not execute anything in finish
        // message or if origin node goes down before finish message
        this.args.connId = EmbedConnection.UNINITIALIZED;
        pendingDDLMessages.put(ddlId, this);
      }
      return;
    }
    if (!Misc.isSnappyHiveMetaTable(ddl.getCurrentSchema())) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY, this.toString()
          + " Starting execution");
    }
    GfxdConnectionWrapper wrapper = null;
    final String ddlStatement = ddl.getValueToConflate();
    boolean success = false;
    LanguageConnectionContext lcc = null;
    int oldFlags = 0;
    Object oldContext = null;
    try {
      wrapper = GfxdConnectionHolder.getOrCreateWrapper(ddl.getCurrentSchema(),
          this.args.connId, true, null);
      final EmbedConnection conn = wrapper.getConnectionForSynchronization();
      synchronized (conn.getConnectionSynchronization()) {
        wrapper.convertToHardReference(conn);
        lcc = wrapper.getLanguageConnectionContext();
        oldFlags = lcc.getFlags();
        wrapper.setLccFlags(lcc, false, this.args.statsEnabled(),
            this.args.timeStatsEnabled(), null);
        // remove any GFE TXState
        GemFireTransaction tran = ((GemFireTransaction)lcc
            .getTransactionExecute());
        tran.clearActiveTXState(true, false);
        // set the context object from additional args, if any
        oldContext = lcc.getContextObject();
        lcc.setContextObject(ddl.getAdditionalArgs());
        lcc.setDefaultPersistent(ddl.defaultPersistent());
        lcc.setPersistMetaStoreInDataDictionary(
            ddl.persistMetaStoreInDataDictionary());
        lcc.setQueryRoutingFlag(false);
        // also the DDL ID
        tran.setDDLId(ddlId);
        wrapper.enableOpLogger();
        final Statement stmt = wrapper.createStatement();
        try {
          stmt.execute(ddlStatement);
          if (stmt.getWarnings() != null) {
            logWarnings(stmt, ddlStatement, this.toString()
                + " SQL warning in executing DDL: ", logger);
          }
          if (this.args.persistOnHDFS()) {
            if (GemFireXDUtils.TraceDDLReplay) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY, 
                "GfxdDDLMessage: Persisting statement on HDFS: " + ddl.getValueToConflate());
            }
            // Find out all the HDFS stores where this ddl belongs
            ArrayList<HDFSStoreImpl> destinationhdfsStores = EmbedStatement.getDestinationHDFSStoreForDDL(ddl, lcc);
            if (destinationhdfsStores != null && destinationhdfsStores.size() != 0)
              EmbedStatement.persistOnHDFS(ddl, lcc, destinationhdfsStores);
          }
          success = true;
        } finally {
          try {
            stmt.close();
          } catch (Exception ex) {
            // Log a severe log in case of an unexpected exception
            if (logger.severeEnabled()) {
              logger.severe(this.toString() + " Unexpected exception in "
                  + "closing DDL", ex);
            }
          }
          if (!success) {
            // need to explicitly rollback since autocommit is false by default
            // and we do not want to come back to this VM again to rollback
            if (GemFireXDUtils.TraceDDLReplay) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
                  toString() + " rolling back");
            }
            try {
              if (wrapper != null) {
                wrapper.rollback();
              }
            } catch (final SQLException ex) {
              if (logger.severeEnabled()) {
                logger.severe(this.toString()
                    + " SQL exception in rolling back DDL", ex);
              }
            }
          }
        }
        // add self as the pending message to handle sender failure before
        // DDL commit/rollback
        synchronized (pendingDDLMessages) {
          pendingDDLMessages.put(ddlId, this);
        }
      }
      if (GemFireXDUtils.TraceDDLReplay) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY, toString()
            + " Successfully executed");
      }
    }catch (StandardException ex) {
      // Log a warning log in case of an SQLException
      if (logger.warningEnabled()) {
        logger.warning(this.toString() + " Standard exception in executing DDL", ex);
      }
      throw new ReplyException("Unexpected StandardException on member "
          + dm.getDistributionManagerId(), ex);
    }  catch (SQLException ex) {
      // Log a warning log in case of an SQLException
      if (logger.warningEnabled()) {
        logger.warning(this.toString() + " SQL exception in executing DDL", ex);
      }
      throw new ReplyException("Unexpected SQLException on member "
          + dm.getDistributionManagerId(), ex);
    } finally {
      if (lcc != null) {
        lcc.setFlags(oldFlags);
        lcc.setPersistMetaStoreInDataDictionary(true);
        lcc.setContextObject(oldContext);
      }
      if (!success) {
        if (wrapper != null) {
          wrapper.markUnused();
        }
      }
    }
  }

  @Override
  protected void sendReply(ReplyException ex, DistributionManager dm) {
    ReplyMessage.send(getSender(), this.processorId, ex, dm, this);
  }

  @Override
  protected boolean waitForNodeInitialization() {
    return false;
  }

  @Override
  protected void handleReplyException(String exPrefix, ReplyException re,
      GfxdReplyMessageProcessor processor) throws StandardException {
    try {
      super.handleReplyException(exPrefix, re, processor);

      // check if the DDL failed on all datastores and this node is not a store
      GemFireStore.VMKind myKind = GemFireXDUtils.getMyVMKind();
      if (myKind == null || !myKind.isStore()) {
        int numStores = 0;
        InternalDistributedMember[] recipients = getRecipients();
        GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
        Map<DistributedMember, ReplyException> exceptions = processor
            .getReplyExceptions();
        for (InternalDistributedMember m : recipients) {
          GfxdDistributionAdvisor.GfxdProfile profile = advisor.getProfile(m);
          if (profile != null && profile.getVMKind().isStore()) {
            if (exceptions != null && !exceptions.containsKey(m)) {
              // found a member that was successful
              return;
            }
            numStores++;
          }
        }
        // all stores failed if exceptions was not null since that is already
        // checked above in the loop, else assume only one exception was thrown
        if (exceptions != null || numStores <= 1) {
          handleUnexpectedReplyException(exPrefix, re);
        }
      }
    } catch (SQLException sqle) {
      throw Misc.wrapRemoteSQLException(sqle, re, re.getSender());
    }
  }

  @Override
  protected void handleProcessorReplyException(String exPrefix,
      ReplyException replyEx) throws SQLException, StandardException {
    final Throwable t = replyEx.getCause();
    // only ignore node failures
    if (!GemFireXDUtils.nodeFailureException(t)) {
      handleUnexpectedReplyException(exPrefix, replyEx);
    }
  }

  static GfxdDDLMessage removePendingDDLMessage(final long ddlId) {
    synchronized (pendingDDLMessages) {
      return pendingDDLMessages.remove(ddlId);
    }
  }

  @Override
  public byte getGfxdID() {
    return GfxdSerializable.DDL_MESSAGE;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeObject(this.args, out);
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.args = DataSerializer.readObject(in);
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append("; connectionID=").append(this.args.connId)
        .append("; statementID=").append(this.args.id).append("; ddl=")
        .append(this.args.ddl);
  }

  /**
   * Inner class to hold the arguments for the DDL statement.
   * 
   * @author swale
   */
  public static final class DDLArgs extends GfxdDataSerializable {

    /** The DDL statement to be executed. */
    private DDLConflatable ddl;

    /** A unique ID for the connection on the originator node. */
    private long connId;

    /** A unique ID (across all VMs) for the DDL statement. */
    private long id;

    /** A set of execution flags. */
    private byte flags;

    private static final byte ENABLE_STATS = 0x01;

    private static final byte ENABLE_TIMESTATS = 0x02;

    private static final byte PERSIST_ON_HDFS = 0x04;

    /** for deserialization */
    public DDLArgs() {
    }

    DDLArgs(DDLConflatable ddl, long connId, long id,
        boolean enableStats, boolean enableTimeStats,
        boolean persistOnHDFS) {
      this.ddl = ddl;
      this.connId = connId;
      this.id = id;
      if (enableStats) {
        this.flags = GemFireXDUtils.set(this.flags, ENABLE_STATS);
        if (enableTimeStats) {
          this.flags = GemFireXDUtils.set(this.flags, ENABLE_TIMESTATS);
        }
      }
      if (persistOnHDFS) {
        this.flags = GemFireXDUtils.set(this.flags, PERSIST_ON_HDFS);
      }
    }

    @Override
    public byte getGfxdID() {
      return GfxdSerializable.DDL_MESSAGE_ARGS;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writePrimitiveByte(this.flags, out);
      out.writeLong(this.connId);
      out.writeLong(this.id);
      InternalDataSerializer.invokeToData(this.ddl, out);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      this.flags = DataSerializer.readPrimitiveByte(in);
      this.connId = in.readLong();
      this.id = in.readLong();
      this.ddl = new DDLConflatable();
      InternalDataSerializer.invokeFromData(this.ddl, in);
    }

    public long getDDLUniqueId() {
      return this.id;
    }

    public long getUniqueConnId() {
      return this.connId;
    }

    public DDLConflatable getDDL() {
      return this.ddl;
    }

    public boolean statsEnabled() {
      return GemFireXDUtils.isSet(this.flags, ENABLE_STATS);
    }

    public boolean timeStatsEnabled() {
      return GemFireXDUtils.isSet(this.flags, ENABLE_TIMESTATS);
    }

    public boolean persistOnHDFS() {
      return GemFireXDUtils.isSet(this.flags, PERSIST_ON_HDFS);
    }
  }

  private static final class DDLListener implements MembershipListener {

    @Override
    public void memberDeparted(final InternalDistributedMember member,
        boolean crashed) {
      // iterate over all the entries to check for any pending ones from the
      // departed member; first only get the pending ones from the global map
      // to keep the sync block short
      final ArrayList<GfxdDDLMessage> memberPendingMessages =
        new ArrayList<GfxdDDLMessage>(4);
      synchronized (pendingDDLMessages) {
        pendingDDLMessages.forEachWhile((ddlId, pendingMessage) -> {
          if (member.equals(pendingMessage.getSender())) {
            memberPendingMessages.add(pendingMessage);
            pendingDDLMessages.remove(ddlId);
          }
          return true;
        });
      }

      // now commit/rollback the pending messages
      final InternalDistributedSystem sys = Misc.getDistributedSystem();
      for (GfxdDDLMessage pendingMessage : memberPendingMessages) {
        // TODO: TX: need to handle the case when some nodes receive
        // commit but others don't like in TX; also need proper transactional
        // semantics for the persisted DataDictionary; for now using the policy
        // of committing drop statements and rolling back others
        final boolean doCommit = pendingMessage.args.ddl.isDropStatement();
        final String actionStr;
        if (doCommit) {
          actionStr = "committed";
          GfxdDDLFinishMessage.doPutInDDLRegion(
              Long.valueOf(pendingMessage.args.id), pendingMessage.args.ddl,
              -1, "MembershipListener for DDLs:");
        }
        else {
          actionStr = "rolled back";
        }
        // no processing required when explicitly connId set to UNINITIALIZED
        if (pendingMessage.args.connId == EmbedConnection.UNINITIALIZED) {
          continue;
        }
        final GfxdConnectionWrapper wrapper = GfxdConnectionHolder.getHolder()
            .removeWrapper(Long.valueOf(pendingMessage.args.connId));
        final boolean[] markUnused = new boolean[] { false };
        try {
          GfxdDDLFinishMessage.doCommitOrRollback(wrapper, doCommit,
              sys.getDistributionManager(), pendingMessage.args.id,
              "MembershipListener for DDLs: successfully " + actionStr + " ["
                  + pendingMessage + "] for failed origin node " + member,
              markUnused);
        } catch (SQLException sqle) {
          final LogWriter logger = sys.getLogWriter();
          if (logger.warningEnabled()) {
            logger.warning("Membership listener for " + toString()
                + ": Failure in rolling back for failed origin node " + member);
          }
        } finally {
          if (wrapper != null && markUnused[0]) {
            wrapper.markUnused();
          }
        }
      }
    }

    @Override
    public void memberJoined(InternalDistributedMember member) {
      // nothing to be done for this
    }

    @Override
    public void memberSuspect(InternalDistributedMember member,
        InternalDistributedMember whoSuspected) {
      // nothing to be done for this
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void quorumLost(Set<InternalDistributedMember> failures,
        List<InternalDistributedMember> remaining) {
      // nothing to be done for this
    }
  }
}
