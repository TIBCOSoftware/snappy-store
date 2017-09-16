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

/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.internal.DSFIDFactory;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.ContainsUniqueKeyExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader.GetRowFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLFinishMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegion.RegionValue;
import com.pivotal.gemfirexd.internal.engine.ddl.PersistIdentityStart;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.messages.*;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.BulkDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.CacheLoadedDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.GfxdCBArgForSynchPrms;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.GfxdGatewaySenderStartMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.GfxdGatewaySenderStopMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDumpLocalResultMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.QueryCancelFunction.QueryCancelFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.distributed.ReferencedKeyCheckerMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.ResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.SnappyResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.StatementCloseExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.*;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockReleaseProcessor.GfxdDRWLockReleaseMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockReleaseProcessor.GfxdDRWLockReleaseReplyMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockRequestProcessor.GfxdDRWLockDumpMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockRequestProcessor.GfxdDRWLockRequestMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockRequestProcessor.GfxdDRWLockResponseMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.procedure.DistributedProcedureCallFunction.DistributedProcedureCallFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.procedure.ProcedureChunkMessage;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireRegionSizeResultSet;
import com.pivotal.gemfirexd.internal.engine.sql.execute.IdentityValueManager;
import com.pivotal.gemfirexd.internal.engine.sql.execute.MemberLogsMessage;
import com.pivotal.gemfirexd.internal.engine.sql.execute.MemberStatisticsMessage;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.CompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactExecRowWithLobs;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.BulkKeyLookupResult;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.SerializableDelta;
import com.pivotal.gemfirexd.internal.engine.ui.SnappyRegionStatsCollectorResult;
import com.pivotal.gemfirexd.internal.impl.store.raw.data.GfxdJarMessage;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeSmartConnectorOpContext;
import com.pivotal.gemfirexd.tools.planexporter.ExecutionPlanMessage;

/**
 * This acts as base class for GFXD specific DSFID types and also handles
 * registration of those types. It should normally be extended for use by GFXD
 * serializable types except when there is no choice (e.g. GfxdMessage that
 * needs to extends DistributionMessage).
 * 
 * @see GfxdSerializable
 * 
 * @author swale
 */
public abstract class GfxdDataSerializable implements GfxdSerializable {

  private static boolean typesRegistered;

  /** register the IDs for implementations */
  public static synchronized boolean initTypes() {
    if (typesRegistered) {
      return false;
    }

    // Register IDs for function args objects
    registerSqlSerializable(GetRowFunctionArgs.class);

    // Register IDs for other GemFireXD DSFID types below
    registerSqlSerializable(RegionValue.class);
    registerSqlSerializable(SerializableDelta.class);
    registerSqlSerializable(DDLConflatable.class);
    registerSqlSerializable(ResultHolder.class);

    // Register any IDs for GemFireXD messages below.
    registerSqlSerializable(GfxdDDLMessage.class);
    registerSqlSerializable(GfxdDDLMessage.DDLArgs.class);
    registerSqlSerializable(GfxdDDLFinishMessage.class);
    registerSqlSerializable(GfxdDRWLockRequestMessage.class);
    registerSqlSerializable(GfxdDRWLockResponseMessage.class);
    registerSqlSerializable(GfxdDRWLockReleaseMessage.class);
    registerSqlSerializable(GfxdDRWLockReleaseReplyMessage.class);
    registerSqlSerializable(GfxdDRWLockDumpMessage.class);
    registerSqlSerializable(GfxdDumpLocalResultMessage.class);
    registerSqlSerializable(ProcedureChunkMessage.class);
    registerSqlSerializable(GfxdAddListenerMessage.class);
    registerSqlSerializable(GfxdRemoveListenerMessage.class);
    registerSqlSerializable(GfxdRemoveWriterMessage.class);
    registerSqlSerializable(GfxdSetWriterMessage.class);
    registerSqlSerializable(GfxdSetLoaderMessage.class);
    registerSqlSerializable(GfxdSetGatewayConflictResolverMessage.class);
    registerSqlSerializable(GfxdRemoveGatewayConflictResolverMessage.class);
    registerSqlSerializable(GfxdSetGatewayEventErrorHandlerMessage.class);
    registerSqlSerializable(GfxdRemoveGatewayEventErrorHandlerMessage.class);
    registerSqlSerializable(GfxdRemoveLoaderMessage.class);
    registerSqlSerializable(GfxdFunctionMessage.GfxdFunctionReplyMessage.class);
    registerSqlSerializable(StatementExecutorMessage.class);
    registerSqlSerializable(PrepStatementExecutorMessage.class);
    registerSqlSerializable(GfxdConfigMessage.class);
    registerSqlSerializable(BulkDBSynchronizerMessage.class);
    registerSqlSerializable(CacheLoadedDBSynchronizerMessage.class);
    registerSqlSerializable(GfxdCBArgForSynchPrms.class);
    registerSqlSerializable(RegionAndKey.class);
    registerSqlSerializable(DistributedProcedureCallFunctionArgs.class);
    registerSqlSerializable(GfxdSystemProcedureMessage.class);
    registerSqlSerializable(GfxdDistributionAdvisor.GfxdProfile.class);
    registerSqlSerializable(CompactCompositeRegionKey.class);
    registerSqlSerializableFixedInstance(GfxdCallbackArgument
        .getFixedInstance());
    registerSqlSerializableFixedInstance(GfxdCallbackArgument
        .getFixedInstanceNoPkBased());
    registerSqlSerializableFixedInstance(GfxdCallbackArgument
        .getFixedInstanceCacheLoaded());
    registerSqlSerializableFixedInstance(GfxdCallbackArgument
        .getFixedInstanceCacheLoadedSkipListeners());
    registerSqlSerializableFixedInstance(GfxdCallbackArgument
        .getFixedInstanceTransactional());
    registerSqlSerializableFixedInstance(GfxdCallbackArgument
        .getFixedInstanceTransactionalNoPkBased());
    registerSqlSerializable(GfxdCallbackArgument.WithInfoFieldsType.class);
    registerSqlSerializable(GfxdLocalLockService.DistributedLockOwner.class);
    registerSqlSerializable(GemFireTransaction.DistributedTXLockOwner.class);
    registerSqlSerializable(ExecutionPlanMessage.class);
    registerSqlSerializable(ExecutionPlanMessage
        .ExecutionPlanReplyMessage.class);
    registerSqlSerializable(GemFireRegionSizeResultSet.RegionSizeMessage.class);
    registerSqlSerializable(GetExecutorMessage.class);
    registerSqlSerializable(GetAllExecutorMessage.class);
    registerSqlSerializable(GetAllLocalIndexExecutorMessage.class);
    registerSqlSerializable(CompactExecRow.class);
    registerSqlSerializable(CompactExecRowWithLobs.class);
    registerSqlSerializable(GfxdIndexManager.ContainsKeyExecutorMessage.class);
    registerSqlSerializable(ContainsUniqueKeyExecutorMessage.class);
    registerSqlSerializable(GfxdJarMessage.class);
    registerSqlSerializable(ReferencedKeyCheckerMessage.class);
    registerSqlSerializable(ReferencedKeyCheckerMessage
        .ReferencedKeyReplyMessage.class);
    registerSqlSerializable(StatementCloseExecutorMessage.class);
    registerSqlSerializable(GfxdGatewaySenderStartMessage.class);
    registerSqlSerializable(GfxdGatewaySenderStopMessage.class);
    registerSqlSerializable(PersistIdentityStart.class);
    registerSqlSerializable(GfxdShutdownAllRequest.class);
    registerSqlSerializable(IdentityValueManager
        .GetIdentityValueMessage.class);
    registerSqlSerializable(IdentityValueManager
        .GetRetrievedIdentityValues.class);
    registerSqlSerializable(ContainsKeyBulkExecutorMessage.class);
    registerSqlSerializable(BulkKeyLookupResult.class);
    registerSqlSerializable(ContainsUniqueKeyBulkExecutorMessage.class);
    registerSqlSerializable(QueryCancelFunctionArgs.class);
    registerSqlSerializable(LeadNodeExecutionContext.class);
    registerSqlSerializable(LeadNodeExecutorMsg.class);
    registerSqlSerializable(SnappyResultHolder.class);
    registerSqlSerializable(SnappyRegionStatsCollectorResult.class);
    registerSqlSerializable(MemberStatisticsMessage.class);
    registerSqlSerializable(MemberLogsMessage.class);
    registerSqlSerializable(LeadNodeSmartConnectorOpContext.class);
    registerSqlSerializable(LeadNodeSmartConnectorOpMsg.class);
    registerSqlSerializable(LeadNodeGetStatsMessage.class);
    // ProjectionRow is registered without creating an instance since it
    // requires GemFireCacheImpl instance in RawValue statics
    DSFIDFactory.registerGemFireXDClass(PROJECTION_ROW, ProjectionRow.class);

    // register SnappyData specific types
    CallbackFactoryProvider.getStoreCallbacks().registerTypes();

    typesRegistered = true;
    return true;
  }

  public static synchronized void clearTypes() {
    DSFIDFactory.clearGemFireXDClasses();
    typesRegistered = false;
  }

  public static void registerSqlSerializableFixedInstance(
      GfxdDataSerializable fixedInstance) {
    byte gfxdId = fixedInstance.getGfxdID();
    DSFIDFactory.registerGemFireXDFixedInstance(gfxdId, fixedInstance);
  }

  public static void registerSqlSerializable(
      Class<? extends GfxdSerializable> c) {
    try {
      // along with getting the GfxdID for registration, this also ensures
      // that the class has a zero arg constructor required for deserialization
      byte gfxdId = c.newInstance().getGfxdID();
      DSFIDFactory.registerGemFireXDClass(gfxdId, c);
    } catch (InstantiationException | IllegalAccessException e) {
      throw GemFireXDRuntimeException.newRuntimeException(null, e);
    }
  }

  @Override
  public final int getDSFID() {
    return DataSerializableFixedID.GFXD_TYPE;
  }

  @Override
  public abstract byte getGfxdID();

  @Override
  public void toData(DataOutput out) throws IOException {
  }

  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
}
