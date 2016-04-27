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

import java.sql.SQLException;
import java.util.Set;

import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.sql.execute.FunctionUtils.
    GetFunctionMembers;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;

/**
 * All function messages that need to execute on a set of members of the
 * DistributedSystem should extend this class.
 * 
 * @author swale
 */
public abstract class MemberExecutorMessage<T> extends GfxdFunctionMessage<T>
    implements GetFunctionMembers {

  /** Empty constructor for deserialization. Not to be invoked directly. */
  protected MemberExecutorMessage(boolean ignored) {
    super(true);
  }

  /** Constructor that should be invoked by child classes. */
  protected MemberExecutorMessage(ResultCollector<Object, T> collector,
      final TXStateInterface tx, boolean timeStatsEnabled,
      boolean abortOnLowMemory) {
    super(collector, tx, timeStatsEnabled, abortOnLowMemory);
  }

  /** Copy constructor to be invoked by child classes. */
  protected MemberExecutorMessage(final MemberExecutorMessage<T> other) {
    super(other);
  }

  @Override
  protected final void executeFunction(boolean enableStreaming)
      throws StandardException, SQLException {
    Set<DistributedMember> members = getMembers();
    if (members == null) {
      members = getAllGfxdServers();
    }
    final InternalDistributedSystem sys = Misc.getDistributedSystem();
    // Misc will return a DS with non-null DM
    final DM dm = sys.getDM();
    // remove all the failed nodes
    if (this.failedNodes != null) {
      members.removeAll(this.failedNodes);
    }
    executeOnMembers(sys, dm, members, enableStreaming);
    postExecutionCallback();
  }

  @Override
  public void checkAllBucketsHosted() {
  }

  public abstract Set<DistributedMember> getMembers();

  @Override
  public Set<String> getServerGroups() {
    // no server groups
    return null;
  }
}
