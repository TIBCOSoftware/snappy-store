/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeSmartConnectorOpContext;

/**
 * This message is used in Smart Connector mode that uses thin client. This message is
 * sent to lead node for DDL operations on external cluster (connector). This is sent from
 * a system proc invoked by the connector.
 */
public final class LeadNodeSmartConnectorOpMsg extends MemberExecutorMessage<Object> {

  private LeadNodeSmartConnectorOpContext ctx;

  public LeadNodeSmartConnectorOpMsg(LeadNodeSmartConnectorOpContext ctx,
      final ResultCollector<Object, Object> rc) {
    super(rc, null, false, true);
    this.ctx = ctx;
  }

  public LeadNodeSmartConnectorOpMsg() {super(true);}

  @Override
  public Set<DistributedMember> getMembers() {
    return Misc.getLeadNode();
  }

  @Override
  public void postExecutionCallback() {
  }

  @Override
  public boolean isHA() {
    return true;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  protected void execute() throws Exception {
    if (GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "LeadNodeSmartConnectorOpMsg.execute: ");
    }
    try {
      CallbackFactoryProvider.getStoreCallbacks().performConnectorOp(this.ctx);
      lastResult(Boolean.TRUE, false, false, true);
    } catch (Exception ex) {
      throw LeadNodeExecutorMsg.getExceptionToSendToServer(ex);
    }
  }

  @Override
  protected void executeFunction(boolean enableStreaming)
      throws StandardException, SQLException {
    try {
      super.executeFunction(enableStreaming);
    } catch (RuntimeException re) {
      throw LeadNodeExecutorMsg.handleLeadNodeRuntimeException(re);
    }
  }

  @Override
  protected LeadNodeSmartConnectorOpMsg clone() {
    return new LeadNodeSmartConnectorOpMsg(this.ctx, this.userCollector);
  }

  @Override
  public byte getGfxdID() {
    return LEAD_NODE_CONN_OP_MSG;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.ctx = DataSerializer.readObject(in);
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeObject(ctx, out);
  }

  public void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";opType=").append(ctx.getType())
        .append(";table=").append(ctx.getTableIdentifier())
        .append(";ifExists=").append(ctx.getIfExists())
        .append(";isBuiltIn=").append(ctx.getIsBuiltIn());
    if (ctx.getProvider() != null) {
      sb.append(";provider=").append(ctx.getProvider());
    }
    if (ctx.getSchemaDDL() != null) {
      sb.append(";schemaDDL=").append(ctx.getSchemaDDL());
    }
  }
}
