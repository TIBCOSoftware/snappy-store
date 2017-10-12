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
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.WanProcedures;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.CreateAsyncEventListenerConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.execute.CreateGatewaySenderConstantAction;

/**
 * 
 * @author ymahajan
 *
 */
public class GfxdGatewaySenderStartMessage extends
    AbstractGfxdReplayableMessage {

  private static final long serialVersionUID = 3396292910027181410L;

  private String id;
  private boolean isAsyncQueue;

  protected static final short IS_ASYNC_QUEUE = UNRESERVED_FLAGS_START;

  public GfxdGatewaySenderStartMessage() {
  }

  public GfxdGatewaySenderStartMessage(String id, boolean isAsyncQueue) {
    this.id = id;
    this.isAsyncQueue = isAsyncQueue;
  }

  @Override
  public void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";GatewaySender/AsyncQueue ID=");
    sb.append(this.id);
    sb.append(";isAsyncQueue=").append(this.isAsyncQueue);
  }

  @Override
  public byte getGfxdID() {
    return GfxdSerializable.GATEWAY_SENDER_START_MSG;
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.id = DataSerializer.readString(in);
    this.isAsyncQueue = (flags & IS_ASYNC_QUEUE) != 0;
  }

  @Override
  protected short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    if (this.isAsyncQueue) flags |= IS_ASYNC_QUEUE;
    return flags;
  }

  @Override
  public void toData(DataOutput out)
      throws IOException {
    super.toData(out);
    DataSerializer.writeString(this.id, out);
  }

  @Override
  public void execute() throws StandardException {
    LogWriter logger = Misc.getGemFireCache().getLoggerI18n()
        .convertToLogWriter();

    if (this.isAsyncQueue) {
      if (logger.infoEnabled()) {
        logger.info("Starting AsyncEventListener : " + this.id);
      }
      try {
        WanProcedures.startAsyncQueueLocally(this.id);
      } catch (Exception ex) {
        throw StandardException.newException(
            SQLState.UNEXPECTED_EXCEPTION_FOR_ASYNC_LISTENER, ex, id,
            ex.toString());
      }
    }
    else {
      try {
        if (logger.infoEnabled()) {
          logger.info("Starting Gateway Sender : " + this.id);
        }
        WanProcedures.startGatewaySenderLocally(this.id);
      } catch (Exception ex) {
        throw StandardException.newException(
            SQLState.UNEXPECTED_EXCEPTION_FOR_ASYNC_LISTENER, ex, id,
            ex.toString());
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean shouldBeConflated() {
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getRegionToConflate() {
    // match the region name with those returned by Create/Drop statements
    // so that these will be removed from queue after DROP
    return SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME + '.' + (this.isAsyncQueue
        ? CreateAsyncEventListenerConstantAction.REGION_PREFIX_FOR_CONFLATION
        : CreateGatewaySenderConstantAction.REGION_PREFIX_FOR_CONFLATION) + id;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getKeyToConflate() {
    // use some fixed key so that DROP statement can remove this too in addition
    // to the STOP message
    return "START_STOP";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getValueToConflate() {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSQLStatement() {
    final StringBuilder sb = new StringBuilder();
    if (this.isAsyncQueue) {
      sb.append("CALL SYS.START_ASYNC_EVENT_LISTENER('");
    }
    else {
      sb.append("CALL SYS.START_GATEWAYSENDER('");
    }
    return sb.append(this.id).append("')").toString();
  }
}
