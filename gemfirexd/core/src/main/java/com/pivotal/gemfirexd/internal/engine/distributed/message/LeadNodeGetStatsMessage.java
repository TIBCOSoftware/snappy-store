/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

public class LeadNodeGetStatsMessage extends MemberExecutorMessage<Object> {

  public LeadNodeGetStatsMessage(final ResultCollector<Object, Object> rc) {
    super(rc, null, false, true);
  }

  public LeadNodeGetStatsMessage() {
    super(true);
  }

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
          "LeadNodeGetStatsMessage.execute: ");
    }
    try {
      Object stats = CallbackFactoryProvider.getStoreCallbacks().getSnappyTableStats();
      lastResult(stats, false, false, true);
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
  protected LeadNodeGetStatsMessage clone() {
    final LeadNodeGetStatsMessage msg = new LeadNodeGetStatsMessage(this.userCollector);
    return msg;
  }

  @Override
  public byte getGfxdID() {
    return LEAD_NODE_GET_STATS;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
  }

  public void appendFields(final StringBuilder sb) {

  }

}
