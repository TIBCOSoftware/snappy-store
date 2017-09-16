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

package com.pivotal.gemfirexd.internal.engine.ddl.wan.messages;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.DSFIDFactory;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisee;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EnumListenerEvent;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
/**
 * @author Asif Shahid
 * @author Yogesh Mahajan
 *
 */
public abstract class AbstractDBSynchronizerMessage extends GfxdMessage {

  private transient EntryEventImpl event = null;

  // final private transient boolean remoteDistribution;
  final transient LocalRegion rgn;

  AbstractDBSynchronizerMessage() {
    this.rgn = null;
  }

  AbstractDBSynchronizerMessage(LocalRegion rgn) {
    final GemFireCacheImpl cache = rgn.getCache();
    final EventID eventId = new EventID(cache.getDistributedSystem());
    final DistributedMember member = cache.getMyId();
    this.initializeEvent(rgn, eventId, member);
    // this.remoteDistribution = remoteDistribution;
    this.rgn = rgn;
  }

  final void initializeEvent(LocalRegion rgn, EventID eventId,
      DistributedMember member) {
    this.event = EntryEventImpl.create(rgn, getOperation(), null, null, null,
        true, member);
    this.event.setEventId(eventId);
    // OFFHEAP: callers make sure event only has on heap values.
  }

  /** 
   * Suranjan: This needs to be changed to do the distribute in parallel.
   */
  final void addToLocalDBSynchronizerConditionally(boolean isQueryNode) {
    final LocalRegion rgn = this.event.getRegion();
    final List<Integer> remoteDsIds = rgn.getRemoteDsIds(rgn
        .getAllGatewaySenderIds());
    boolean didLocalPublish = false;
    if (remoteDsIds != null && remoteDsIds.size() > 0) {
      // TODO: PERF: the distribute should be done in parallel
      // also same issue in GFE layer
      final Set<String> asyncQueueIds;
      if (!this.skipListeners()
          && (asyncQueueIds = rgn.getAsyncEventQueueIds()).size() > 0) {
        for (final Object q : rgn.getCache().getAsyncEventQueues()) {
          final AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)q;
          if (!asyncQueue.isParallel()
              && asyncQueueIds.contains(asyncQueue.getId())) {
            if (GemFireXDUtils.TraceDBSynchronizer) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
                  "BaseActivation:addToLocalDBSynchronizerConditionally: "
                      + "adding EntryEventImpl to local AsyncEventQueue="
                      + asyncQueue.getId() + ": " + event);
            }
            asyncQueue.getSender().distribute(getListenerEvent(), event,
                remoteDsIds);
            didLocalPublish = true;
          }
        }
      }
      final Set<String> senderIds = rgn.getGatewaySenderIds();
      if (senderIds.size() > 0) {
        for (final Object s : rgn.getCache().getAllGatewaySenders()) {
          final AbstractGatewaySender sender = (AbstractGatewaySender)s;
          if (!sender.isForInternalUse() && !sender.isParallel()
              && senderIds.contains(sender.getId())) {
            if (GemFireXDUtils.TraceDBSynchronizer) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
                  "BaseActivation:addToLocalDBSynchronizerConditionally: "
                      + "adding EntryEventImpl to local GatewaySender="
                      + sender.getId() + ": " + event);
            }
            sender.distribute(getListenerEvent(), event, remoteDsIds);
            didLocalPublish = true;
          }
        }
      }
    }
    if (!didLocalPublish && !isQueryNode && !skipListeners()) {
      throw new IllegalStateException("Unexpected AbstractDBSynchronizerMessage "
          + "received that was not applied anywhere " + toString());
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    try {
      // Write EventID
      DataSerializer.writeObject(this.event.getEventId(), out);
      ((InternalDistributedMember)event.getDistributedMember()).toData(out);
      // Write Region name
      DataSerializer.writeString(this.event.getRegion().getFullPath(), out);

    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  public final EntryEventImpl getEntryEventImpl() {
    return this.event;
  }

  @Override
  protected void processMessage(DistributionManager dm) {
    if (this.event != null) {
      final LocalRegion rgn = this.event.getRegion();
      if (rgn != null) {
        rgn.waitOnInitialization();
      } else {
        Misc.checkIfCacheClosing(null);
      }
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "DBSynchronizerMessage: Executing with fields as: "
                + this.toString());
      }
      try {
        this.addToLocalDBSynchronizerConditionally(false);
        if (GemFireXDUtils.TraceDBSynchronizer) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
              "DBSynchronizerMessage: Successfully executed "
                  + "message with fields: " + this.toString());
        }
      } catch (Exception ex) {
        // Log a severe log in case of an exception
        final LogWriter logger = this.event.getRegion().getCache().getLogger();
        if (logger.severeEnabled()) {
          logger.severe("DBSynchronizerMessage: SQL exception in "
              + "executing message with fields as " + this.toString(), ex);
        }
        if (dm == null) {
          throw new ReplyException(
              "Unexpected SQLException on member with no DM (going down?)", ex);
        } else if (this.processorId > 0) {
          throw new ReplyException("Unexpected SQLException on member "
              + dm.getDistributionManagerId(), ex);
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
    return true;
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append("; entryEvent=").append(this.event);
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    // Read EventID
    EventID eventID = DataSerializer.readObject(in);
    DistributedMember member = DSFIDFactory.readInternalDistributedMember(in);
    String regionName = DataSerializer.readString(in);
    final GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    final LocalRegion rgn = cache != null
        ? cache.getRegionByPathForProcessing(regionName) : null;
    // initialize event even if Region is null so that child classes can
    // proceed with their fromData
    this.initializeEvent(rgn, eventID, member);
  }

  public final void applyOperation() throws StandardException {
    // Distribute the message only in case of PR
    try {
      @SuppressWarnings("unchecked")
      Set<DistributedMember> members = ((CacheDistributionAdvisee)rgn)
          .getCacheDistributionAdvisor()
          .adviseSerialAsyncEventQueueOrGatewaySender();
      if (GemFireXDUtils.TraceDBSynchronizer) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DB_SYNCHRONIZER,
            "BaseActivation: distributing bulk DML to remote nodes. "
                + "Message={" + this + "} to members=" + members);
      }
      this.send(this.rgn.getSystem(), members);

    } catch (SQLException sqle) {
      throw Misc.wrapSQLException(sqle, sqle);
    }
    addToLocalDBSynchronizerConditionally(true);
  }

  @Override
  protected void handleProcessorReplyException(String exPrefix,
      ReplyException replyEx) throws SQLException, StandardException {
    final Throwable t = replyEx.getCause();
    if (!GemFireXDUtils.retryToBeDone(t)) {
      if (GemFireXDUtils.TraceFunctionException) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX, exPrefix
            + ": unexpected exception", replyEx);
      }
      try {
        GemFireXDRuntimeException.throwSQLOrRuntimeException(toString()
            + ": unexpected exception", t);
      } catch (SQLException sqle) {
        throw Misc.wrapRemoteSQLException(sqle, replyEx, replyEx.getSender());
      }
    }
  }

  abstract Operation getOperation();

  abstract EnumListenerEvent getListenerEvent();

  abstract boolean skipListeners();
}
