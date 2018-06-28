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
package com.gemstone.gemfire.internal.cache.execute;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.LowMemoryException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.execute.Execution;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.NoMemberFoundException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.SetUtils;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import io.snappydata.collection.OpenHashSet;

/**
 * 
 * @author ymahajan
 *
 */
public class MemberFunctionExecutor extends AbstractExecution {

  protected InternalDistributedSystem ds;

  protected Set members;

  protected LogWriterI18n logger;

  private ServerToClientFunctionResultSender sender;

  public MemberFunctionExecutor(DistributedSystem s) {
    this.ds = (InternalDistributedSystem)s;
    logger = this.ds.getLogWriterI18n();
    this.members = this.ds.getDistributionManager().getNormalDistributionManagerIds();
  }

  public MemberFunctionExecutor(DistributedSystem s, DistributedMember m) {
    this.ds = (InternalDistributedSystem)s;
    logger = this.ds.getLogWriterI18n();
    this.members = Collections.singleton(m);
  }

  public MemberFunctionExecutor(DistributedSystem s, Set m) {
    this.ds = (InternalDistributedSystem)s;
    logger = this.ds.getLogWriterI18n();
    this.members = m;
  }

  public MemberFunctionExecutor(DistributedSystem s, Set m, ServerToClientFunctionResultSender sender) {
    this(s, m);
    this.sender = sender;
  }

  private MemberFunctionExecutor(MemberFunctionExecutor memFunctionExecutor) {
    super(memFunctionExecutor);
    this.ds = memFunctionExecutor.ds;
    this.logger = memFunctionExecutor.logger;
    this.members = new HashSet();
    this.members.addAll(memFunctionExecutor.members);
    this.sender = memFunctionExecutor.sender;
  }

  private MemberFunctionExecutor(MemberFunctionExecutor memberFunctionExecutor,
      MemberMappedArgument argument) {
    this(memberFunctionExecutor);    
    
    this.memberMappedArg = argument;
    this.isMemberMappedArgument = true;
  }

  private MemberFunctionExecutor(MemberFunctionExecutor memberFunctionExecutor,
      ResultCollector rs) {
    this(memberFunctionExecutor);    

    this.rc = rs;
  }

  private MemberFunctionExecutor(MemberFunctionExecutor memberFunctionExecutor,
      Object arguments) {
    this(memberFunctionExecutor);

    this.args = arguments;
  }

  @SuppressWarnings("unchecked")
  private ResultCollector executeFunction(final Function function,
      ResultCollector resultCollector) {
    final DM dm = this.ds.getDistributionManager();
    final Set<InternalDistributedMember> dest = new OpenHashSet<>(this.members);
    if (dest.isEmpty()) {
      throw new NoMemberFoundException(LocalizedStrings
          .MemberFunctionExecutor_NO_MEMBER_FOUND_FOR_EXECUTING_FUNCTION_0
              .toLocalizedString(function.getId()));
    } 
    validateExecution(function, dest);
    setExecutionNodes(dest);

    final InternalDistributedMember localVM = this.ds.getDistributionManager()
        .getDistributionManagerId();
    final LocalResultCollector<?, ?> localRC = getLocalResultCollector(
        function, resultCollector); 
    final boolean remoteOnly = !dest.remove(localVM);
    final boolean localOnly = !remoteOnly && dest.isEmpty();
    final TXStateInterface tx = flushTXPendingOps(dm);
    final MemberFunctionResultSender resultSender = new MemberFunctionResultSender(
        dm, tx, localRC, function, localOnly, remoteOnly, sender);
    if (!remoteOnly) {
      // if member is local VM
      final FunctionContext context = new FunctionContextImpl(function.getId(),
          getArgumentsForMember(localVM.getId()), resultSender);
      executeFunctionOnLocalNode(function, context, resultSender, dm, tx);
    }

    if (!dest.isEmpty()) {
      HashMap<InternalDistributedMember, Object> memberArgs = new HashMap<InternalDistributedMember, Object>();
      Iterator<InternalDistributedMember> iter = dest.iterator();
      while (iter.hasNext()) {
        InternalDistributedMember recip = iter.next();
        memberArgs.put(recip, getArgumentsForMember(recip.getId()));
      }
      Assert.assertTrue(memberArgs.size() == dest.size());
      MemberFunctionResultWaiter resultReciever = new MemberFunctionResultWaiter(
          this.ds, localRC, function, memberArgs, dest,resultSender);

      ResultCollector reply = resultReciever.getFunctionResultFrom(dest,
          function, this);
      return reply;
    }
    return localRC;
  }

  protected TXStateInterface flushTXPendingOps(final DM dm) {
    final TXStateInterface tx = TXManagerImpl.getCurrentTXState();
    if (tx != null) {
      // flush any pending TXStateProxy ops
      tx.flushPendingOps(dm);
    }
    return tx;
  }

  /**
   * @param function
   * @param dest
   */
  @Override
  public void validateExecution(final Function function, final Set dest) {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    /*
    if (cache != null && cache.getTxManager().getTXState() != null) {
      if (dest.size() > 1) {
        throw new TransactionException(LocalizedStrings.PartitionedRegion_TX_FUNCTION_ON_MORE_THAN_ONE_NODE.toLocalizedString());
      } else {
        assert dest.size() == 1;
        DistributedMember funcTarget = (DistributedMember)dest.iterator().next();
        DistributedMember target = cache.getTxManager().getTXState().getTarget();
        if (target == null) {
          cache.getTxManager().getTXState().setTarget(funcTarget);
        } else if (!target.equals(funcTarget)) {
          throw new TransactionDataNotColocatedException(LocalizedStrings.PartitionedRegion_TX_FUNCTION_EXECUTION_NOT_COLOCATED
              .toLocalizedString());
        }
      }
    }
    */
    if (!function.hasResult() && cache != null
        // [sjigyasu] A quick and dirty fix for QueryCancelDUnit failures with
        // default isolation RC. This condition should be removed when the
        // correct fix is made.
        // The correct fix is that QueryCancelFunction should return success or
        // failure in result collector and its hasResult() method should return true.
        && function.getId() != "gfxd-QueryCancelFunction" 
        && cache.getTxManager().getTXState() != null) {
      throw new TransactionException(LocalizedStrings
          .TXState_FUNCTION_WITH_NO_RESULT_NOT_SUPPORTED_IN_A_TRANSACTION
              .toLocalizedString());
    }
    if (function.optimizeForWrite() && cache!= null && cache.
        getResourceManager().getHeapMonitor().containsHeapCriticalMembers(dest) &&
        !MemoryThresholds.isLowMemoryExceptionDisabled()) {
      Set<InternalDistributedMember> hcm  = cache.getResourceAdvisor().adviseCriticalMembers();
      Set<DistributedMember> sm = SetUtils.intersection(hcm, dest);
      throw new LowMemoryException(LocalizedStrings.ResourceManager_LOW_MEMORY_FOR_0_FUNCEXEC_MEMBERS_1.toLocalizedString(
              new Object[] {function.getId(), sm}), sm);
    }
  }

  @Override
  protected ResultCollector executeFunction(Function function) {
    if (function.hasResult()) {
      ResultCollector rc = this.rc;
      if (rc == null) {
        rc = new DefaultResultCollector();
      }
      return executeFunction(function, rc);
    }
    else {
      executeFunction(function, null);
      return new NoResult();
    }
  }

  // Changing the object!!
  public Execution withArgs(Object arguments) {
    if (arguments == null) {
      throw new IllegalArgumentException(
          LocalizedStrings.ExecuteRegionFunction_THE_INPUT_0_FOR_THE_EXECUTE_FUNCTION_REQUEST_IS_NULL
              .toLocalizedString("args"));
    }
    return new MemberFunctionExecutor(this,arguments);
  }
  //Changing the object!!
  public Execution withCollector(ResultCollector rs) {
    if (rs == null) {
      throw new IllegalArgumentException(
          LocalizedStrings.ExecuteRegionFunction_THE_INPUT_0_FOR_THE_EXECUTE_FUNCTION_REQUEST_IS_NULL
              .toLocalizedString("Result Collector"));
    }
    return new MemberFunctionExecutor(this,rs);
  }

  public Execution withFilter(Set filter) {
    throw new FunctionException(
        LocalizedStrings.ExecuteFunction_CANNOT_SPECIFY_0_FOR_DATA_INDEPENDENT_FUNCTIONS
            .toLocalizedString("filter"));
  }

  @Override
  public AbstractExecution withRoutingObjects(Set<Object> routingObjects) {
    throw new FunctionException(
        LocalizedStrings.ExecuteFunction_CANNOT_SPECIFY_0_FOR_DATA_INDEPENDENT_FUNCTIONS
            .toLocalizedString("routing objects"));
  }

  public InternalExecution withMemberMappedArgument(
      MemberMappedArgument argument) {
    if(argument == null){
      throw new IllegalArgumentException(
          LocalizedStrings.ExecuteRegionFunction_THE_INPUT_0_FOR_THE_EXECUTE_FUNCTION_REQUEST_IS_NULL
              .toLocalizedString("MemberMappedArgs"));
    }
    return new MemberFunctionExecutor(this, argument);
  }

  @Override
  public boolean isMemberMappedArgument() {
    return this.isMemberMappedArgument;
  }

  @Override
  public Object getArgumentsForMember(String memberId) {
    if (!isMemberMappedArgument) {
      return this.args;
    }
    else {
      return this.memberMappedArg.getArgumentsForMember(memberId);
    }
  }

  @Override
  public MemberMappedArgument getMemberMappedArgument() {
    return this.memberMappedArg;
  }
  
  public ServerToClientFunctionResultSender getServerResultSender() {
    return this.sender;
  }
}
