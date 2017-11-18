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
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EventID;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.ReplayableConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * 
 * @author Asif
 * 
 */
@SuppressWarnings("serial")
public abstract class AbstractGfxdReplayableMessage extends GfxdMessage
    implements ReplayableConflatable {

  protected long replayKey;

  protected boolean skipInLocalExecution;

  protected boolean executing;

  protected AbstractGfxdReplayableMessage() {
  }

  protected AbstractGfxdReplayableMessage(boolean createMsg) {
    GemFireStore store = GemFireStore.getBootingInstance();
    GfxdDDLRegionQueue ddlQ;
    this.skipInLocalExecution = store != null
        && (ddlQ = store.getDDLQueueNoThrow()) != null
        && !(ddlQ.isInitialized() && (store.initialDDLReplayInProgress()
            || store.initialDDLReplayDone()));
  }

  public void setReplayKey(long replayKey) {
    this.replayKey = replayKey;
  }

  @Override
  public boolean skipInLocalExecution() {
    return this.skipInLocalExecution;
  }

  @Override
  public void markExecuting() {
    this.executing = true;
  }

  @Override
  public boolean isExecuting() {
    return this.executing;
  }

  @Override
  protected void processMessage(DistributionManager dm) {

    if (!GemFireXDUtils.getMyVMKind().isAccessorOrStore()) {
      if (GemFireXDUtils.TraceDDLQueue) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE,
            toString() + ": Skipping execution of system procedure on "
                + GemFireXDUtils.getMyVMKind() + " JVM");
      }
      return;
    }

    if (isOKToSkip(this.replayKey)) {
      if (this.processorId > 0) {
        ReplyMessage.send(getSender(), this.processorId, null, dm, this);
      }
      return;
    }
    LogWriter logger = dm.getLoggerI18n().convertToLogWriter();
    if (logger.infoEnabled()) {
      logger.info("AbstractGfxdReplayableMessage: Executing with fields as: "
          + this.toString());
    }

    try {
      this.execute();
      if (logger.infoEnabled()) {
        logger.info("AbstractGfxdReplayableMessage: Successfully executed "
            + "message with fields: " + this.toString());
      }
    } catch (StandardException ex) {
      // Log a severe log in case of an exception
      if (logger.severeEnabled()) {
        logger.severe("AbstractGfxdReplayableMessage: SQL exception in "
            + "executing message with fields as " + this.toString(), ex);
      }
      if (this.processorId > 0) {
        throw new ReplyException("Unexpected SQLException on member "
            + dm.getDistributionManagerId(), ex);
      }
    }
  }

  @Override
  protected final void sendReply(ReplyException ex, DistributionManager dm) {
    ReplyMessage.send(getSender(), this.processorId, ex, dm, this);
  }

  @Override
  protected boolean waitForNodeInitialization() {
    return false;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writePrimitiveLong(this.replayKey, out);
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.replayKey = DataSerializer.readPrimitiveLong(in);
  }

  @Override
  public void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append("; replayKey=").append(this.replayKey);
  }

  public void setLatestValue(Object value) {
    throw new AssertionError(getShortClassName() + "#setLatestValue: "
        + "not expected to be invoked");
  }

  public EventID getEventId() {
    throw new AssertionError(getShortClassName() + "#getEventId: "
        + "not expected to be invoked");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean shouldBeMerged() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean merge(Conflatable existing) {
    throw new AssertionError("not expected to be invoked");
  }

  /**
   * Execute this message on a remote store locally.
   * 
   * @throws StandardException
   *           on error
   */
  public abstract void execute() throws StandardException;

  /**
   * Get the equivalent SQL statement for this message.
   * 
   * @return the equivalent SQL statement for this message
   * 
   * @throws StandardException
   *           on error
   */
  public abstract String getSQLStatement() throws StandardException;

  /** get the schema that will be used for this message */
  public String getSchemaName() {
    return null;
  }
}
