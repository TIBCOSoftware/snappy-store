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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
import com.pivotal.gemfirexd.tools.planexporter.ExecutionPlanMessage;

/**
 * This acts as base class for GFXD specific DSFID types and also handles
 * registration of those types. It should normally be extended for use by GFXD
 * serializable types except when there is no choice (e.g. GfxdMessage that
 * needs to extends DistributionMessage).
 *
 * @author swale
 * @see GfxdSerializable
 */
@SuppressWarnings("Convert2MethodRef")
public abstract class GfxdDataSerializable implements GfxdSerializable {

  private static boolean typesRegistered;

  /**
   * register the IDs for implementations
   */
  public static synchronized boolean initTypes() {
    if (typesRegistered) {
      return false;
    }

    // Register IDs for function args objects
    DSFIDFactory.registerGemFireXDClass(GETROW_ARGS,
        () -> new GetRowFunctionArgs());

    // Register IDs for other GemFireXD DSFID types below
    DSFIDFactory.registerGemFireXDClass(DDL_REGION_VALUE,
        () -> new RegionValue());
    DSFIDFactory.registerGemFireXDClass(SERIALIZABLE_DELTA,
        () -> new SerializableDelta());
    DSFIDFactory.registerGemFireXDClass(DDL_CONFLATABLE,
        () -> new DDLConflatable());
    DSFIDFactory.registerGemFireXDClass(RESULT_HOLDER,
        () -> new ResultHolder());

    // Register any IDs for GemFireXD messages below.
    DSFIDFactory.registerGemFireXDClass(DDL_MESSAGE,
        () -> new GfxdDDLMessage());
    DSFIDFactory.registerGemFireXDClass(DDL_MESSAGE_ARGS,
        () -> new GfxdDDLMessage.DDLArgs());
    DSFIDFactory.registerGemFireXDClass(DDL_FINISH_MESSAGE,
        () -> new GfxdDDLFinishMessage());
    DSFIDFactory.registerGemFireXDClass(DRWLOCK_REQUEST_MESSAGE,
        () -> new GfxdDRWLockRequestMessage());
    DSFIDFactory.registerGemFireXDClass(DRWLOCK_RESPONSE_MESSAGE,
        () -> new GfxdDRWLockResponseMessage());
    DSFIDFactory.registerGemFireXDClass(DRWLOCK_RELEASE_MESSAGE,
        () -> new GfxdDRWLockReleaseMessage());
    DSFIDFactory.registerGemFireXDClass(DRWLOCK_RELEASE_REPLY_MESSAGE,
        () -> new GfxdDRWLockReleaseReplyMessage());
    DSFIDFactory.registerGemFireXDClass(DRWLOCK_DUMP_MESSAGE,
        () -> new GfxdDRWLockDumpMessage());
    DSFIDFactory.registerGemFireXDClass(DUMP_LOCAL_RESULT,
        () -> new GfxdDumpLocalResultMessage());
    DSFIDFactory.registerGemFireXDClass(PROCEDURE_CHUNK_MSG,
        () -> new ProcedureChunkMessage());
    DSFIDFactory.registerGemFireXDClass(ADD_LISTENER_MSG,
        () -> new GfxdAddListenerMessage());
    DSFIDFactory.registerGemFireXDClass(REMOVE_LISTENER_MSG,
        () -> new GfxdRemoveListenerMessage());
    DSFIDFactory.registerGemFireXDClass(REMOVE_WRITER_MSG,
        () -> new GfxdRemoveWriterMessage());
    DSFIDFactory.registerGemFireXDClass(SET_WRITER_MSG,
        () -> new GfxdSetWriterMessage());
    DSFIDFactory.registerGemFireXDClass(SET_LOADER_MSG,
        () -> new GfxdSetLoaderMessage());
    DSFIDFactory.registerGemFireXDClass(SET_GATEWAY_CONFLICT_RESOLVER_MSG,
        () -> new GfxdSetGatewayConflictResolverMessage());
    DSFIDFactory.registerGemFireXDClass(REMOVE_GATEWAY_CONFLICT_RESOLVER_MSG,
        () -> new GfxdRemoveGatewayConflictResolverMessage());
    DSFIDFactory.registerGemFireXDClass(SET_GATEWAY_EVENT_ERROR_HANDLER_MSG,
        () -> new GfxdSetGatewayEventErrorHandlerMessage());
    DSFIDFactory.registerGemFireXDClass(REMOVE_GATEWAY_EVENT_ERROR_HANDLER_MSG,
        () -> new GfxdRemoveGatewayEventErrorHandlerMessage());
    DSFIDFactory.registerGemFireXDClass(REMOVE_LOADER_MSG,
        () -> new GfxdRemoveLoaderMessage());
    DSFIDFactory.registerGemFireXDClass(FUNCTION_REPLY_MESSAGE,
        () -> new GfxdFunctionMessage.GfxdFunctionReplyMessage());
    DSFIDFactory.registerGemFireXDClass(STMNT_EXECUTOR_FUNCTION,
        () -> new StatementExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(PREP_STMNT_EXECUTOR_FUNCTION,
        () -> new PrepStatementExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(GFXD_CONFIG_MSG,
        () -> new GfxdConfigMessage());
    DSFIDFactory.registerGemFireXDClass(BULK_DB_SYNCH_MESSG,
        () -> new BulkDBSynchronizerMessage());
    DSFIDFactory.registerGemFireXDClass(CACHE_LOADED_DB_SYNCH_MSG,
        () -> new CacheLoadedDBSynchronizerMessage());
    DSFIDFactory.registerGemFireXDClass(GFXD_CB_ARG_DB_SYNCH,
        () -> new GfxdCBArgForSynchPrms());
    DSFIDFactory.registerGemFireXDClass(GFXD_REP_TABLE_KEYS,
        () -> new RegionAndKey());
    DSFIDFactory.registerGemFireXDClass(DISTRIBUTED_PROCEDURE_ARGS,
        () -> new DistributedProcedureCallFunctionArgs());
    DSFIDFactory.registerGemFireXDClass(GFXD_SYSTEM_PROCEDURE_MSG,
        () -> new GfxdSystemProcedureMessage());
    DSFIDFactory.registerGemFireXDClass(GFXD_PROFILE,
        () -> new GfxdDistributionAdvisor.GfxdProfile());
    DSFIDFactory.registerGemFireXDClass(COMPOSITE_REGION_KEY,
        () -> new CompactCompositeRegionKey());
    DSFIDFactory.registerGemFireXDClass(GFXD_CALLBACK_ARGUMENT,
        () -> GfxdCallbackArgument.getFixedInstance());
    DSFIDFactory.registerGemFireXDClass(GFXD_CALLBACK_ARGUMENT_NO_PK_BASED,
        () -> GfxdCallbackArgument.getFixedInstanceNoPkBased());
    DSFIDFactory.registerGemFireXDClass(GFXD_CALLBACK_ARGUMENT_CACHE_LOADED,
        () -> GfxdCallbackArgument.getFixedInstanceCacheLoaded());
    DSFIDFactory.registerGemFireXDClass(GFXD_CALLBACK_ARGUMENT_CL_SKIP_LISTENERS,
        () -> GfxdCallbackArgument.getFixedInstanceCacheLoadedSkipListeners());
    DSFIDFactory.registerGemFireXDClass(GFXD_CALLBACK_ARGUMENT_TRANSACTIONAL,
        () -> GfxdCallbackArgument.getFixedInstanceTransactional());
    DSFIDFactory.registerGemFireXDClass(
        GFXD_CALLBACK_ARGUMENT_TRANSACTIONAL_NO_PK_BASED,
        () -> GfxdCallbackArgument.getFixedInstanceTransactionalNoPkBased());
    DSFIDFactory.registerGemFireXDClass(GFXD_CALLBACK_ARGUMENT_W_INFO,
        () -> new GfxdCallbackArgument.WithInfoFieldsType());
    DSFIDFactory.registerGemFireXDClass(DISTRIBUTED_LOCK_OWNER,
        () -> new GfxdLocalLockService.DistributedLockOwner());
    DSFIDFactory.registerGemFireXDClass(DISTRIBUTED_TX_LOCK_OWNER,
        () -> new GemFireTransaction.DistributedTXLockOwner());
    DSFIDFactory.registerGemFireXDClass(EXECUTION_PLAN_MSG,
        () -> new ExecutionPlanMessage());
    DSFIDFactory.registerGemFireXDClass(EXECUTION_PLAN_REPLY_MSG,
        () -> new ExecutionPlanMessage.ExecutionPlanReplyMessage());
    DSFIDFactory.registerGemFireXDClass(GET_REGION_SIZE_MSG,
        () -> new GemFireRegionSizeResultSet.RegionSizeMessage());
    DSFIDFactory.registerGemFireXDClass(GET_EXECUTOR_MSG,
        () -> new GetExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(GET_ALL_EXECUTOR_MSG,
        () -> new GetAllExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(GET_ALL_LOCAL_INDEX_EXECUTOR_MSG,
        () -> new GetAllLocalIndexExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(COMPACT_EXECROW,
        () -> new CompactExecRow());
    DSFIDFactory.registerGemFireXDClass(COMPACT_EXECROW_WITH_LOBS,
        () -> new CompactExecRowWithLobs());
    DSFIDFactory.registerGemFireXDClass(CONTAINSKEY_EXECUTOR_MSG,
        () -> new GfxdIndexManager.ContainsKeyExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(CONTAINS_UNIQUE_KEY_EXECUTOR_MSG,
        () -> new ContainsUniqueKeyExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(JAR_MSG, () -> new GfxdJarMessage());
    DSFIDFactory.registerGemFireXDClass(REFERENCED_KEY_CHECK_MSG,
        () -> new ReferencedKeyCheckerMessage());
    DSFIDFactory.registerGemFireXDClass(REFERENCED_KEY_REPLY_MSG,
        () -> new ReferencedKeyCheckerMessage.ReferencedKeyReplyMessage());
    DSFIDFactory.registerGemFireXDClass(STATEMENT_CLOSE_MSG,
        () -> new StatementCloseExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(GATEWAY_SENDER_START_MSG,
        () -> new GfxdGatewaySenderStartMessage());
    DSFIDFactory.registerGemFireXDClass(GATEWAY_SENDER_STOP_MSG,
        () -> new GfxdGatewaySenderStopMessage());
    DSFIDFactory.registerGemFireXDClass(PERSIST_IDENTITY_START,
        () -> new PersistIdentityStart());
    DSFIDFactory.registerGemFireXDClass(GFXD_SHUTDOWN_ALL_MESSAGE,
        () -> new GfxdShutdownAllRequest());
    DSFIDFactory.registerGemFireXDClass(GET_IDENTITY_MSG,
        () -> new IdentityValueManager.GetIdentityValueMessage());
    DSFIDFactory.registerGemFireXDClass(GET_RETRIEVED_IDENTITY_MSG,
        () -> new IdentityValueManager.GetRetrievedIdentityValues());
    DSFIDFactory.registerGemFireXDClass(CONTAINSKEY_BULK_EXECUTOR_MSG,
        () -> new ContainsKeyBulkExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(GFXD_BULK_KEY_LOOKUP_RESULT,
        () -> new BulkKeyLookupResult());
    DSFIDFactory.registerGemFireXDClass(CONTAINS_UNIQUEKEY_BULK_EXECUTOR_MSG,
        () -> new ContainsUniqueKeyBulkExecutorMessage());
    DSFIDFactory.registerGemFireXDClass(GFXD_QUERY_CANCEL_FUNCTION_ARGS,
        () -> new QueryCancelFunctionArgs());
    DSFIDFactory.registerGemFireXDClass(LEAD_NODE_EXN_CTX,
        () -> new LeadNodeExecutionContext());
    DSFIDFactory.registerGemFireXDClass(LEAD_NODE_EXN_MSG,
        () -> new LeadNodeExecutorMsg());
    DSFIDFactory.registerGemFireXDClass(SNAPPY_RESULT_HOLDER,
        () -> new SnappyResultHolder());
    DSFIDFactory.registerGemFireXDClass(SNAPPY_REGION_STATS_RESULT,
        () -> new SnappyRegionStatsCollectorResult());
    DSFIDFactory.registerGemFireXDClass(MEMBER_STATISTICS_MESSAGE,
        () -> new MemberStatisticsMessage());
    DSFIDFactory.registerGemFireXDClass(MEMBER_LOGS_MESSAGE,
        () -> new MemberLogsMessage());
    DSFIDFactory.registerGemFireXDClass(LEAD_NODE_GET_STATS,
        () -> new LeadNodeGetStatsMessage());
    DSFIDFactory.registerGemFireXDClass(PROJECTION_ROW,
        () -> new ProjectionRow());
    DSFIDFactory.registerGemFireXDClass(LEAD_NODE_DATA_MSG,
        () -> new GetLeadNodeInfoAsStringMessage());

    // register SnappyData specific types
    CallbackFactoryProvider.getStoreCallbacks().registerTypes();

    typesRegistered = true;
    return true;
  }

  public static synchronized void clearTypes() {
    DSFIDFactory.clearGemFireXDClasses();
    typesRegistered = false;
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
