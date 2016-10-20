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
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

import java.io.DataOutput;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.DSFIDFactory.GfxdDSFID;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.ContainsUniqueKeyExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.PersistIdentityStart;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLFinishMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader.GetRowFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegion.RegionValue;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.messages.GfxdAddListenerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.messages.GfxdRemoveListenerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.messages.GfxdRemoveWriterMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.callbacks.messages.GfxdSetWriterMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.BulkDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.CacheLoadedDBSynchronizerMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.GfxdCBArgForSynchPrms;
import com.pivotal.gemfirexd.internal.engine.distributed.QueryCancelFunction.QueryCancelFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.distributed.ReferencedKeyCheckerMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.StatementCloseExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDumpLocalResultMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.
    ReferencedKeyCheckerMessage.ReferencedKeyReplyMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GetExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.PrepStatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.ProjectionRow;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdConfigMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage.GfxdFunctionReplyMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockReleaseProcessor.GfxdDRWLockReleaseMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockReleaseProcessor.GfxdDRWLockReleaseReplyMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockRequestProcessor.GfxdDRWLockDumpMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockRequestProcessor.GfxdDRWLockRequestMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdDRWLockRequestProcessor.GfxdDRWLockResponseMessage;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.procedure.ProcedureChunkMessage;
import com.pivotal.gemfirexd.internal.engine.procedure.
    DistributedProcedureCallFunction.DistributedProcedureCallFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.sql.execute.IdentityValueManager;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.CompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactExecRowWithLobs;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.SerializableDelta;
import com.pivotal.gemfirexd.internal.impl.store.raw.data.GfxdJarMessage;
import com.pivotal.gemfirexd.tools.planexporter.ExecutionPlanMessage;

/**
 * GFXD specific interface that extends {@link DataSerializableFixedID}. The
 * approach for GFXD serializable types is to use a common ID namely
 * {@link DataSerializableFixedID#GFXD_TYPE}, while using an extra byte for the
 * sub-type of each GFXD type. This is to avoid registering every new GFXD type
 * with {@link DataSerializableFixedID}.
 * 
 * All IDs should be in this class in ascending order to ensure uniqueness.
 * 
 * To add a new {@link GfxdSerializable} implementation, add the classId for it
 * in {@link GfxdSerializable}, return that ID as the result of {
 * {@link #getGfxdID()} and add its registration in
 * {@link GfxdDataSerializable#initTypes()}. As far as possible extend
 * {@link GfxdDataSerializable} instead of directly implementing
 * {@link GfxdSerializable} and implement the
 * {@link GfxdDataSerializable#fromData(java.io.DataInput)} and
 * {@link GfxdDataSerializable#toData(DataOutput)}. When required to
 * implement {@link GfxdSerializable} directly then return
 * {@link DataSerializableFixedID#GFXD_TYPE} in {@link #getDSFID()}.
 * 
 * @author swale
 */
public interface GfxdSerializable extends GfxdDSFID {

  // Add all classIds for implementations of GfxdMessage below

  /** classId for {@link GfxdDDLMessage}s */
  public final static byte DDL_MESSAGE = 1;

  /** classId for {@link GfxdSystemProcedureMessage}s */
  public final static byte GFXD_SYSTEM_PROCEDURE_MSG = 2;

  /** classId for {@link GfxdDDLFinishMessage}s */
  public final static byte DDL_FINISH_MESSAGE = 3;

  /** classId for {@link GfxdDRWLockRequestMessage}s */
  public final static byte DRWLOCK_REQUEST_MESSAGE = 4;

  /** classId for {@link GfxdDRWLockResponseMessage}s */
  public final static byte DRWLOCK_RESPONSE_MESSAGE = 5;

  /** classId for {@link GfxdDRWLockReleaseMessage}s */
  public final static byte DRWLOCK_RELEASE_MESSAGE = 6;

  /** classId for {@link GfxdDRWLockReleaseReplyMessage}s */
  public final static byte DRWLOCK_RELEASE_REPLY_MESSAGE = 7;

  /** classId for {@link GfxdDRWLockDumpMessage}s */
  public final static byte DRWLOCK_DUMP_MESSAGE = 8;

  /** classId for {@link PersistIdentityStart}s. */
  public final static byte PERSIST_IDENTITY_START = 9;

  // unused: 10 to 18

  /** classId for {@link GfxdDumpLocalResultMessage}s */
  public final static byte DUMP_LOCAL_RESULT = 19;

  /** classId for {@link ProcedureChunkMessage}s */
  public final static byte PROCEDURE_CHUNK_MSG = 20;

  /** class for {@link GfxdIndexManager.ContainsKeyExecutorMessage} */
  public final static byte CONTAINSKEY_EXECUTOR_MSG = 21;

  /** class for {@link ReferencedKeyReplyMessage} */
  public final static byte REFERENCED_KEY_REPLY_MSG = 22;

  /** classId for {@link GfxdAddListenerMessage}s */
  public final static byte ADD_LISTENER_MSG = 23;

  /** classId for {@link GfxdSetWriterMessage}s */
  public final static byte SET_WRITER_MSG = 24;

  /** classId for {@link GfxdRemoveListenerMessage}s */
  public final static byte REMOVE_LISTENER_MSG = 25;

  /** classId for {@link GfxdRemoveWriterMessage}s */
  public final static byte REMOVE_WRITER_MSG = 26;

  /** classId for {@link GfxdFunctionReplyMessage}s */
  public final static byte FUNCTION_REPLY_MESSAGE = 27;

  /** classId for {@link StatementExecutorMessage}s */
  public final static byte STMNT_EXECUTOR_FUNCTION = 28;

  /** classId for {@link PrepStatementExecutorMessage}s */
  public final static byte PREP_STMNT_EXECUTOR_FUNCTION = 29;

  /** classId for {@link GfxdConfigMessage}s */
  public final static byte GFXD_CONFIG_MSG = 30;

  /** classId for {@link GfxdSetLoaderMessage}s */
  public final static byte SET_LOADER_MSG = 31;

  /** classId for {@link GfxdRemoveLoaderMessage}s */
  public final static byte REMOVE_LOADER_MSG = 32;

  /** classId for {@link ExecutionPlanMessage}s */
  public final static byte EXECUTION_PLAN_MSG = 33;

  /** classId for {@link ExecutionPlanMessage.ExecutionPlanReplyMessage}s */
  public final static byte EXECUTION_PLAN_REPLY_MSG = 34;

  /** classId for {@link CacheLoadedDBSynchronizerMessage} */
  public final static byte CACHE_LOADED_DB_SYNCH_MSG = 35;

  /** classId for {@link GemFireRegionSizeResultSet.RegionSizeMessage}s */
  public final static byte GET_REGION_SIZE_MSG = 36;

  /** classId for {@link GetExecutorMessage}s */
  public final static byte GET_EXECUTOR_MSG = 37;

  /** class for {@link ContainsUniqueKeyExecutorMessage} */
  public final static byte CONTAINS_UNIQUE_KEY_EXECUTOR_MSG = 38;

  /** classId for {@link GfxdJarMessage}s */
  public final static byte JAR_MSG = 39;

  /** classId for {@link GfxdGatewaySenderStartMessage}s */
  public final static byte GATEWAY_SENDER_START_MSG = 40;

  /** classId for {@link GfxdGatewaySenderStopMessage}s */
  public final static byte GATEWAY_SENDER_STOP_MSG = 41;

  /** classId for {@link StatementCloseExecutorMessage}s */
  public final static byte STATEMENT_CLOSE_MSG = 42;

  /** classId for {@link ReferencedKeyCheckerMessage}s */
  public final static byte REFERENCED_KEY_CHECK_MSG = 43;

  /** classId for {@link GfxdShutdownAllRequest}s */
  public final static byte GFXD_SHUTDOWN_ALL_MESSAGE = 44;

  /** classId for {@link GetAllExecutorMessage}s */
  public final static byte GET_ALL_EXECUTOR_MSG = 45;

  /** classId for {@link IdentityValueManager.GetIdentityValueMessage}s */
  public final static byte GET_IDENTITY_MSG = 46;

  /** classId for {@link IdentityValueManager.GetRetrievedIdentityValues}s */
  public final static byte GET_RETRIEVED_IDENTITY_MSG = 47;

  /** classId for {@link GetAllLocalIndexExecutorMessage}s */
  public final static byte GET_ALL_LOCAL_INDEX_EXECUTOR_MSG = 48;

  /** classId for {@link GfxdSetGatewayConflictResolverMessage}s */
  public final static byte SET_GATEWAY_CONFLICT_RESOLVER_MSG = 49;
  
  /** classId for {@link GfxdRemoveGatewayConflictResolverMessage}s */
  public final static byte REMOVE_GATEWAY_CONFLICT_RESOLVER_MSG = 50;
  
  /** classId for {@link GfxdSetGatewayEventErrorHandlerMessage}s */
  public final static byte SET_GATEWAY_EVENT_ERROR_HANDLER_MSG = 51;

  /** classId for {@link GfxdRemoveGatewayEventErrorHandlerMessage}s */
  public final static byte REMOVE_GATEWAY_EVENT_ERROR_HANDLER_MSG = 52;
  
  /** classId for {@link ContainsKeyBulkExecutorMessage}s */
  public final static byte CONTAINSKEY_BULK_EXECUTOR_MSG = 53;
  
  /** classId for {@link ContainsUniqueKeyBulkExecutorMessage}s */
  public final static byte CONTAINS_UNIQUEKEY_BULK_EXECUTOR_MSG = 54;
    
  /**
   * Marker to indicate that tests can use an ID >= this. Note whenever adding a
   * new message increment this to be greater than the last one.
   */
  public final static byte LAST_MSG_ID = 75;

  // NOTE: IDs less than 75 and >= zero are reserved for P2P message types only
  // i.e. classes that extend GfxdMessage or GfxdReplyMessage.
  // If this assumption changes then change it in
  // DSFIDFactory#readGfxdMessage that attempts to handle cases when
  // deserialization fails.
  // WARNING: Do not add non P2P message types before this else handling of
  // exceptions during deserialization by pure GFE VMs will be incorrect.

  // Other non-message DSFIDs used in GemFireXD layer.
  // Add P2P message types before this and others below this.

  /** classId for {@link RegionValue}s */
  public final static byte DDL_REGION_VALUE = 80;

  /** classId for {@link SerializableDelta}s */
  public final static byte SERIALIZABLE_DELTA = 81;

  /** classId for {@link DDLConflatable}s */
  public final static byte DDL_CONFLATABLE = 82;

  /** class for {@link ProjectionRow} */
  public final static byte PROJECTION_ROW = 83;

  /** classId for {@link GfxdCallbackArgument.NoPkBased} */
  public final static byte GFXD_CALLBACK_ARGUMENT_NO_PK_BASED = 84;

  /** classId for {@link GemFireTransaction.DistributedTXLockOwner} */
  public final static byte DISTRIBUTED_TX_LOCK_OWNER = 85;

  /** classId for {@link CompactExecRow} */
  public final static byte COMPACT_EXECROW = 86;

  /** class for {@link CompactExecRowWithLobs} */
  public final static byte COMPACT_EXECROW_WITH_LOBS = 87;

  /** classId for {@link GfxdLocalLockService.DistributedLockOwner} */
  public final static byte DISTRIBUTED_LOCK_OWNER = 88;

  /** classId for {@link GetRowFunctionArgs} function args */
  public final static byte GETROW_ARGS = 89;

  /** classId for {@link ResultHolder} */
  public final static byte RESULT_HOLDER = 90;

  /** classId for {@link GfxdCallbackArgument} */
  public final static byte GFXD_CALLBACK_ARGUMENT = 91;

  /** classId for {@link BulkDBSynchronizerMessage} */
  public final static byte BULK_DB_SYNCH_MESSG = 92;

  /** classId for {@link GfxdCBArgForSynchPrms} */
  public final static byte GFXD_CB_ARG_DB_SYNCH = 93;

  /** classId for {@link ReplicatedTableKeysForOuterJoins  */
  public final static byte GFXD_REP_TABLE_KEYS = 94;

  /** classId for {@link DistributedProcedureCallFunctionArgs}s */
  public final static byte DISTRIBUTED_PROCEDURE_ARGS = 95;

  /** classId for {@link GfxdDDLMessage.DDLArgs}s */
  public final static byte DDL_MESSAGE_ARGS = 96;

  /** classId for {@link GfxdDistributionAdvisor.GfxdProfile}s */
  public final static byte GFXD_PROFILE = 97;

  /** classId for {@link CompactCompositeRegionKey} */
  public final static byte COMPOSITE_REGION_KEY = 98;

  /** classId for {@link GfxdCallbackArgument.WithInfoFieldsType} */
  public final static byte GFXD_CALLBACK_ARGUMENT_W_INFO = 99;

  /** classId for {@link GfxdCallbackArgument.CacheLoaded} */
  public final static byte GFXD_CALLBACK_ARGUMENT_CACHE_LOADED = 100;

  /** classId for {@link GfxdCallbackArgument.CacheLoadedSkipListeners} */
  public final static byte GFXD_CALLBACK_ARGUMENT_CL_SKIP_LISTENERS = 101;

  /** classId for {@link GfxdCallbackArgument.Transactional} */
  public final static byte GFXD_CALLBACK_ARGUMENT_TRANSACTIONAL = 102;

  /** classId for {@link GfxdCallbackArgument.TransactionalNoPkBased} */
  public final static byte GFXD_CALLBACK_ARGUMENT_TRANSACTIONAL_NO_PK_BASED = 103;
  
  /** classId for {@link GemFireContainer.BulkKeyLookupResult} */
  public final static byte GFXD_BULK_KEY_LOOKUP_RESULT = 104;
  
  /** classId for {@link QueryCancelFunctionArgs}s */
  public final static byte GFXD_QUERY_CANCEL_FUNCTION_ARGS = 105;
  
  //Spark Module related bytes
  public final static byte GFXD_SPARK_PROFILE = 106;

  public final static byte GFXD_SPARK_TASK_SUBMIT = 107;
  
  public final static byte GFXD_SPARK_TASK_RESULT = 108;

  /** classId for {@link com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext}*/
  public final static byte LEAD_NODE_EXN_CTX = 109;

  public final static byte LEAD_NODE_EXN_MSG = 119;

  public final static byte SNAPPY_RESULT_HOLDER = 120;

  public final static byte SNAPPY_REMOVE_CACHED_OBJECTS_ARGS = 121;

  public final static byte SNAPPY_REGION_STATS_RESULT = 122;
}
