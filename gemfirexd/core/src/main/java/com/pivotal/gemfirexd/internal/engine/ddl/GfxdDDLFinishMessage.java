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
import java.util.Set;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Message sent to indicate the commit or rollback of a DDL statement previously
 * executed using the {@link GfxdDDLMessage}.
 * 
 * @author swale
 */
public final class GfxdDDLFinishMessage extends GfxdMessage {

  /** The DDL statement to be committed or rolled back. */
  private DDLConflatable ddlStatement;

  /** A unique ID for the connection on the originator node. */
  private long connId;

  /** A unique ID (across all VMs) for the DDL statement. */
  private long id;

  /** The sequence number of the DDL in the origin VM's DDL queue. */
  private long sequenceId;

  /** if true then indicates DDL is to be committed else rolled back */
  private boolean doCommit;

  /**
   * Sends an {@link GfxdDDLFinishMessage} for given DDL statement to the given
   * members of the distributed system. If the members parameter is null then
   * the message is sent to all members of the distributed system.
   */
  public static void send(InternalDistributedSystem system,
      Set<DistributedMember> members, DDLConflatable ddl, long connId,
      long ddlId, long sequenceId, boolean commitOrRollback)
      throws StandardException, SQLException {
    final GfxdDDLFinishMessage msg = new GfxdDDLFinishMessage();
    msg.ddlStatement = ddl;
    msg.connId = connId;
    msg.id = ddlId;
    msg.sequenceId = sequenceId;
    msg.doCommit = commitOrRollback;
    msg.send(system, members);
  }

  @Override
  protected void processMessage(DistributionManager dm) {
    final String actionStart;
    final String actionEnd;
    if (this.doCommit) {
      actionStart = " commit";
      actionEnd = " committed";
    }
    else {
      actionStart = " rollback";
      actionEnd = " rolled back";
    }
    if (GemFireXDUtils.TraceDDLReplay ||
        !Misc.isSnappyHiveMetaTable(ddlStatement.getCurrentSchema())) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY, toString()
          + " Received" + actionStart);
    }
    // No need to check in processed IDs since this will be invoked only
    // on members that did not skip GfxdDDLMessage.
    /*
    final Long ddlId = Long.valueOf(this.id);
    // wait for DDL replay if required
    if (isOKToSkip(ddlId)) {
      if (logger.infoEnabled()) {
        logger.info(this.toString() + " Skipping" + actionStart
            + " for DDL due to replay in progress");
      }
      //ReplyMessage.send(getSender(), this.processorId, null, dm);
      return;
    }
    */
    // first do a local put in the DDL region for commit
    if (this.doCommit) {
      doPutInDDLRegion(Long.valueOf(this.id), this.ddlStatement,
          this.sequenceId, "GfxdDDLFinishMessage:");
    }
    // try to remove from the pending DDL list; if not then VM never received
    // GfxdDDLMessage so skip commit/rollback
    final GfxdDDLMessage ddlMsg;
    if ((ddlMsg = GfxdDDLMessage.removePendingDDLMessage(this.id)) == null
        || ddlMsg.args.getUniqueConnId() == EmbedConnection.UNINITIALIZED) {
      if (GemFireXDUtils.TraceDDLReplay || GemFireXDUtils.TraceDDLQueue ||
          !Misc.isSnappyHiveMetaTable(ddlStatement.getCurrentSchema())) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
            + " Returning after local put since GfxdDDLMessage "
            + "was not executed on this node");
      }
      return;
    }

    GfxdConnectionWrapper wrapper = null;
    final boolean[] markUnused = new boolean[] { false };
    try {
      wrapper = getExistingWrapper(this.connId);
      doCommitOrRollback(wrapper, this.doCommit, dm, this.id, this.connId,
          this.toString() + " Successfully" + actionEnd, markUnused);
    } catch (SQLException ex) {
      final String matchStr = SQLState.SHUTDOWN_DATABASE.substring(0,
          SQLState.SHUTDOWN_DATABASE.indexOf('.'));
      if (ex.getSQLState().indexOf(matchStr) != -1) {
        return;
      }
      // Log an error in case of an SQLException
      final LogWriter logger = dm.getLoggerI18n().convertToLogWriter();
      if (logger.errorEnabled()) {
        logger.error(this.toString() + " SQL exception in" + actionStart, ex);
      }
      // create a StandardException out of an SQLException so that it can be
      // handled properly on the query node
      Throwable cause = StandardException
          .getJavaException(ex, ex.getSQLState());
      if (cause == null) {
        cause = Misc.wrapSQLException(ex, ex);
      }
      throw new ReplyException("Unexpected SQLException on member "
          + dm.getDistributionManagerId(), cause);
    } finally {
      if (wrapper != null && markUnused[0]) {
        wrapper.markUnused();
      }
    }
  }

  static void doPutInDDLRegion(final Long ddlId, final DDLConflatable ddl,
      final long sequenceId, final String logStr) {
    // first put the implicit schema DDL, if any
    DDLConflatable schemaDDL;
    if ((schemaDDL = ddl.getImplicitSchema()) != null) {
      doPutInDDLRegion(schemaDDL.getId(), schemaDDL,
          ddl.getImplicitSchemaSequenceId(), logStr);
    }
    try {
      // use the cache to get the DDL region rather than DDL queue since we
      // need to apply events received during GII
      final LocalRegion ddlRegion = Misc.getGemFireCache()
          .getRegionByPathForProcessing(GemFireStore.DDL_STMTS_REGION);
      // check to see if the region is in recovery mode
      if (ddlRegion != null && !ddlRegion.getImageState().getInRecovery()) {
        long startPut = CachePerfStats.getStatTime();
        final EntryEventImpl event = ddlRegion.newUpdateEntryEvent(ddlId,
            new GfxdDDLRegion.RegionValue(ddl, sequenceId), null);
        // explicitly set the event's originRemote as true
        event.setOriginRemote(true);
        ddlRegion.validatedPut(event, startPut);
        // TODO OFFHEAP validatedPut calls freeOffHeapResources
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
      SystemFailure.checkFailure();
      // ignore exceptions at this point but still check for VM going down
      final GemFireCacheImpl cache = Misc.getGemFireCache();
      cache.getCancelCriterion().checkCancelInProgress(t);
      // log exception as error
      final LogWriter logger = cache.getLogger();
      if (logger.errorEnabled()) {
        logger.error(logStr
            + " unexpected exception in local put on DDL region for " + ddl, t);
      }
    }
  }

  static void doCommitOrRollback(final GfxdConnectionWrapper wrapper,
      final boolean doCommit, final DM dm, final long ddlId, long connId,
      final String logString, boolean[] markUnused) throws SQLException {
    if (wrapper != null) {
      final EmbedConnection conn = wrapper.getConnectionForSynchronization();
      synchronized (conn.getConnectionSynchronization()) {
        final int syncVersion = wrapper.convertToHardReference(conn);
        final LanguageConnectionContext lcc = wrapper
            .getLanguageConnectionContext();
        final int oldFlags = lcc.getFlags();
        lcc.setPossibleDuplicate(false);
        // remove any previous GFE TXState
        ((GemFireTransaction)lcc.getTransactionExecute())
            .clearActiveTXState(true, false);
        try {
          if (doCommit) {
            conn.internalCommit();
          }
          else {
            try {
              conn.internalRollback();
            } finally {
              lcc.cleanupNestedTransactionExecute();
            }
          }
          if (!Misc.isSnappyHiveMetaTable(lcc.getCurrentSchemaName())) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY, logString);
          }
        } finally {
          wrapper.disableOpLogger();
          lcc.setFlags(oldFlags);
          markUnused[0] = wrapper.convertToSoftReference(syncVersion);
        }
      }
    } else {
      SanityManager.DEBUG_PRINT("error:" + GfxdConstants.TRACE_DDLREPLAY,
          "NULL wrapper for connId=" + connId + " ddlId=" + ddlId +
              ". Skipping DDL finish having commit=" + doCommit);
    }
  }

  @Override
  protected void sendReply(ReplyException ex, DistributionManager dm) {
    ReplyMessage.send(getSender(), this.processorId, ex, dm, this);
  }

  @Override
  protected boolean blockExecutionForLastBatchDDLReplay() {
    return true;
  }

  @Override
  protected boolean waitForNodeInitialization() {
    return false;
  }

  @Override
  public byte getGfxdID() {
    return DDL_FINISH_MESSAGE;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    InternalDataSerializer.invokeToData(this.ddlStatement, out);
    out.writeLong(this.connId);
    out.writeLong(this.id);
    out.writeLong(this.sequenceId);
    out.writeBoolean(this.doCommit);
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.ddlStatement = new DDLConflatable();
    InternalDataSerializer.invokeFromData(this.ddlStatement, in);
    this.connId = in.readLong();
    this.id = in.readLong();
    this.sequenceId = in.readLong();
    this.doCommit = in.readBoolean();
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append("; connectionID=").append(this.connId).append("; statementID=")
        .append(this.id).append("; statement=").append(this.ddlStatement)
        .append("; sequenceId=").append(this.sequenceId).append("; doCommit=")
        .append(this.doCommit);
  }
}
