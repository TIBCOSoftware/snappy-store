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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
 * This class is used to distribute PreparedStatement ID of PreparedStatement
 * which has been closed, to the other nodes so that the PreparedStatement if
 * present in the GfxdConnectionWrapper, could be closed.
 * 
 * [sumedh] Changed to use GfxdFunctionMessage for better control on TX
 * behaviour etc.
 * 
 * @author Asif
 * @author swale
 */
public final class StatementCloseExecutorMessage extends
    MemberExecutorMessage<Object> {

  private final transient Set<DistributedMember> targetMembers;

  private long connectionId;

  private long statementId;

  /** Default constructor for deserialization. Not to be invoked directly. */
  public StatementCloseExecutorMessage() {
    super(true);
    this.targetMembers = null;
  }

  public StatementCloseExecutorMessage(
      final ResultCollector<Object, Object> rc,
      final Set<DistributedMember> members, final long connectionId,
      final long statementId) {
    super(rc, null, DistributionStats.enableClockStats, false);
    this.targetMembers = members;
    this.connectionId = connectionId;
    this.statementId = statementId;
  }

  protected StatementCloseExecutorMessage(
      final StatementCloseExecutorMessage other) {
    super(other);
    this.targetMembers = other.targetMembers;
    this.connectionId = other.connectionId;
    this.statementId = other.statementId;
  }

  @Override
  protected void execute() throws SQLException {
    final Long connId = Long.valueOf(this.connectionId);
    // Check if the connection is present in the holder
    GfxdConnectionHolder connHolder = GfxdConnectionHolder.getHolder();
    GfxdConnectionWrapper wrapper = connHolder.getExistingWrapper(connId);
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "StatementCloseExecutorMessage: closing statement "
              + "in Connection wrapper for statementId=" + this.statementId
              + " connectionId=" + connId + " : " + wrapper);
    }
    if (wrapper != null) {
      wrapper.closeStatement(this.statementId);
    }
    lastResult(Boolean.TRUE, false, false, true);
  }

  @Override
  public Set<DistributedMember> getMembers() {
    // no HA so don't need to dynamically generate the set of members
    return this.targetMembers;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  public boolean isHA() {
    return false;
  }

  @Override
  public boolean canStartRemoteTransaction() {
    return false;
  }

  @Override
  protected boolean requiresTXFlushBeforeExecution() {
    return false;
  }

  @Override
  protected boolean requiresTXFlushAfterExecution() {
    return false;
  }

  @Override
  public void postExecutionCallback() {
  }

  @Override
  protected GfxdFunctionMessage<Object> clone() {
    return new StatementCloseExecutorMessage(this);
  }

  @Override
  public byte getGfxdID() {
    return STATEMENT_CLOSE_MSG;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    GemFireXDUtils.writeCompressedHighLow(out, this.connectionId);
    GemFireXDUtils.writeCompressedHighLow(out, this.statementId);
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.connectionId = GemFireXDUtils.readCompressedHighLow(in);
    this.statementId = GemFireXDUtils.readCompressedHighLow(in);
  }
}
