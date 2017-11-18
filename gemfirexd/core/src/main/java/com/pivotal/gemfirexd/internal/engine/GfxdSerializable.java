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

import java.io.DataOutput;

import com.gemstone.gemfire.internal.DSFIDFactory.GfxdDSFID;
import com.gemstone.gemfire.internal.DataSerializableFixedID;

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

  /** classId for <code>GfxdDDLMessage</code>s */
  byte DDL_MESSAGE = 1;

  /** classId for <code>GfxdSystemProcedureMessage</code>s */
  byte GFXD_SYSTEM_PROCEDURE_MSG = 2;

  /** classId for <code>GfxdDDLFinishMessage</code>s */
  byte DDL_FINISH_MESSAGE = 3;

  /** classId for <code>GfxdDRWLockRequestMessage</code>s */
  byte DRWLOCK_REQUEST_MESSAGE = 4;

  /** classId for <code>GfxdDRWLockResponseMessage</code>s */
  byte DRWLOCK_RESPONSE_MESSAGE = 5;

  /** classId for <code>GfxdDRWLockReleaseMessage</code>s */
  byte DRWLOCK_RELEASE_MESSAGE = 6;

  /** classId for <code>GfxdDRWLockReleaseReplyMessage</code>s */
  byte DRWLOCK_RELEASE_REPLY_MESSAGE = 7;

  /** classId for <code>GfxdDRWLockDumpMessage</code>s */
  byte DRWLOCK_DUMP_MESSAGE = 8;

  /** classId for <code>PersistIdentityStart</code>s. */
  byte PERSIST_IDENTITY_START = 9;

  // unused: 10 to 18

  /** classId for <code>GfxdDumpLocalResultMessage</code>s */
  byte DUMP_LOCAL_RESULT = 19;

  /** classId for <code>ProcedureChunkMessage</code>s */
  byte PROCEDURE_CHUNK_MSG = 20;

  /** class for <code>GfxdIndexManager.ContainsKeyExecutorMessage</code> */
  byte CONTAINSKEY_EXECUTOR_MSG = 21;

  /** class for <code>ReferencedKeyReplyMessage</code> */
  byte REFERENCED_KEY_REPLY_MSG = 22;

  /** classId for <code>GfxdAddListenerMessage</code>s */
  byte ADD_LISTENER_MSG = 23;

  /** classId for <code>GfxdSetWriterMessage</code>s */
  byte SET_WRITER_MSG = 24;

  /** classId for <code>GfxdRemoveListenerMessage</code>s */
  byte REMOVE_LISTENER_MSG = 25;

  /** classId for <code>GfxdRemoveWriterMessage</code>s */
  byte REMOVE_WRITER_MSG = 26;

  /** classId for <code>GfxdFunctionReplyMessage</code>s */
  byte FUNCTION_REPLY_MESSAGE = 27;

  /** classId for <code>StatementExecutorMessage</code>s */
  byte STMNT_EXECUTOR_FUNCTION = 28;

  /** classId for <code>PrepStatementExecutorMessage</code>s */
  byte PREP_STMNT_EXECUTOR_FUNCTION = 29;

  /** classId for <code>GfxdConfigMessage</code>s */
  byte GFXD_CONFIG_MSG = 30;

  /** classId for <code>GfxdSetLoaderMessage</code>s */
  byte SET_LOADER_MSG = 31;

  /** classId for <code>GfxdRemoveLoaderMessage</code>s */
  byte REMOVE_LOADER_MSG = 32;

  /** classId for <code>ExecutionPlanMessage</code>s */
  byte EXECUTION_PLAN_MSG = 33;

  /** classId for <code>ExecutionPlanMessage.ExecutionPlanReplyMessage</code>s */
  byte EXECUTION_PLAN_REPLY_MSG = 34;

  /** classId for <code>CacheLoadedDBSynchronizerMessage</code> */
  byte CACHE_LOADED_DB_SYNCH_MSG = 35;

  /** classId for <code>GemFireRegionSizeResultSet.RegionSizeMessage</code>s */
  byte GET_REGION_SIZE_MSG = 36;

  /** classId for <code>GetExecutorMessage</code>s */
  byte GET_EXECUTOR_MSG = 37;

  /** class for <code>ContainsUniqueKeyExecutorMessage</code> */
  byte CONTAINS_UNIQUE_KEY_EXECUTOR_MSG = 38;

  /** classId for <code>GfxdJarMessage</code>s */
  byte JAR_MSG = 39;

  /** classId for <code>GfxdGatewaySenderStartMessage</code>s */
  byte GATEWAY_SENDER_START_MSG = 40;

  /** classId for <code>GfxdGatewaySenderStopMessage</code>s */
  byte GATEWAY_SENDER_STOP_MSG = 41;

  /** classId for <code>StatementCloseExecutorMessage</code>s */
  byte STATEMENT_CLOSE_MSG = 42;

  /** classId for <code>ReferencedKeyCheckerMessage</code>s */
  byte REFERENCED_KEY_CHECK_MSG = 43;

  /** classId for <code>GfxdShutdownAllRequest</code>s */
  byte GFXD_SHUTDOWN_ALL_MESSAGE = 44;

  /** classId for <code>GetAllExecutorMessage</code>s */
  byte GET_ALL_EXECUTOR_MSG = 45;

  /** classId for <code>IdentityValueManager.GetIdentityValueMessage</code>s */
  byte GET_IDENTITY_MSG = 46;

  /** classId for <code>IdentityValueManager.GetRetrievedIdentityValues</code>s */
  byte GET_RETRIEVED_IDENTITY_MSG = 47;

  /** classId for <code>GetAllLocalIndexExecutorMessage</code>s */
  byte GET_ALL_LOCAL_INDEX_EXECUTOR_MSG = 48;

  /** classId for <code>GfxdSetGatewayConflictResolverMessage</code>s */
  byte SET_GATEWAY_CONFLICT_RESOLVER_MSG = 49;
  
  /** classId for <code>GfxdRemoveGatewayConflictResolverMessage</code>s */
  byte REMOVE_GATEWAY_CONFLICT_RESOLVER_MSG = 50;
  
  /** classId for <code>GfxdSetGatewayEventErrorHandlerMessage</code>s */
  byte SET_GATEWAY_EVENT_ERROR_HANDLER_MSG = 51;

  /** classId for <code>GfxdRemoveGatewayEventErrorHandlerMessage</code>s */
  byte REMOVE_GATEWAY_EVENT_ERROR_HANDLER_MSG = 52;
  
  /** classId for <code>ContainsKeyBulkExecutorMessage</code>s */
  byte CONTAINSKEY_BULK_EXECUTOR_MSG = 53;
  
  /** classId for <code>ContainsUniqueKeyBulkExecutorMessage</code>s */
  byte CONTAINS_UNIQUEKEY_BULK_EXECUTOR_MSG = 54;

  byte MEMBER_STATISTICS_MESSAGE = 55;

  byte LEAD_NODE_EXN_MSG = 56;

  byte LEAD_NODE_CONN_OP_MSG = 57;

  byte LEAD_NODE_GET_STATS = 58;

  byte MEMBER_LOGS_MESSAGE = 59;

  /**
   * Marker to indicate that tests can use an ID >= this. Note whenever adding a
   * new message increment this to be greater than the last one.
   */
  byte LAST_MSG_ID = 75;

  // NOTE: IDs less than 75 and >= zero are reserved for P2P message types only
  // i.e. classes that extend GfxdMessage or GfxdReplyMessage.
  // If this assumption changes then change it in
  // DSFIDFactory#readGfxdMessage that attempts to handle cases when
  // deserialization fails.
  // WARNING: Do not add non P2P message types before this else handling of
  // exceptions during deserialization by pure GFE VMs will be incorrect.

  // Other non-message DSFIDs used in GemFireXD layer.
  // Add P2P message types before this and others below this.

  /** classId for <code>RegionValue</code>s */
  byte DDL_REGION_VALUE = 80;

  /** classId for <code>SerializableDelta</code>s */
  byte SERIALIZABLE_DELTA = 81;

  /** classId for <code>DDLConflatable</code>s */
  byte DDL_CONFLATABLE = 82;

  /** class for <code>ProjectionRow</code> */
  byte PROJECTION_ROW = 83;

  /** classId for <code>GfxdCallbackArgument.NoPkBased</code> */
  byte GFXD_CALLBACK_ARGUMENT_NO_PK_BASED = 84;

  /** classId for <code>GemFireTransaction.DistributedTXLockOwner</code> */
  byte DISTRIBUTED_TX_LOCK_OWNER = 85;

  /** classId for <code>CompactExecRow</code> */
  byte COMPACT_EXECROW = 86;

  /** class for <code>CompactExecRowWithLobs</code> */
  byte COMPACT_EXECROW_WITH_LOBS = 87;

  /** classId for <code>GfxdLocalLockService.DistributedLockOwner</code> */
  byte DISTRIBUTED_LOCK_OWNER = 88;

  /** classId for <code>GetRowFunctionArgs</code> function args */
  byte GETROW_ARGS = 89;

  /** classId for <code>ResultHolder</code> */
  byte RESULT_HOLDER = 90;

  /** classId for <code>GfxdCallbackArgument</code> */
  byte GFXD_CALLBACK_ARGUMENT = 91;

  /** classId for <code>BulkDBSynchronizerMessage</code> */
  byte BULK_DB_SYNCH_MESSG = 92;

  /** classId for <code>GfxdCBArgForSynchPrms</code> */
  byte GFXD_CB_ARG_DB_SYNCH = 93;

  /** classId for <code>ReplicatedTableKeysForOuterJoins</code> */
  byte GFXD_REP_TABLE_KEYS = 94;

  /** classId for <code>DistributedProcedureCallFunctionArgs</code>s */
  byte DISTRIBUTED_PROCEDURE_ARGS = 95;

  /** classId for <code>GfxdDDLMessage.DDLArgs</code>s */
  byte DDL_MESSAGE_ARGS = 96;

  /** classId for <code>GfxdDistributionAdvisor.GfxdProfile</code>s */
  byte GFXD_PROFILE = 97;

  /** classId for <code>CompactCompositeRegionKey</code> */
  byte COMPOSITE_REGION_KEY = 98;

  /** classId for <code>GfxdCallbackArgument.WithInfoFieldsType</code> */
  byte GFXD_CALLBACK_ARGUMENT_W_INFO = 99;

  /** classId for <code>GfxdCallbackArgument.CacheLoaded</code> */
  byte GFXD_CALLBACK_ARGUMENT_CACHE_LOADED = 100;

  /** classId for <code>GfxdCallbackArgument.CacheLoadedSkipListeners</code> */
  byte GFXD_CALLBACK_ARGUMENT_CL_SKIP_LISTENERS = 101;

  /** classId for <code>GfxdCallbackArgument.Transactional</code> */
  byte GFXD_CALLBACK_ARGUMENT_TRANSACTIONAL = 102;

  /** classId for <code>GfxdCallbackArgument.TransactionalNoPkBased</code> */
  byte GFXD_CALLBACK_ARGUMENT_TRANSACTIONAL_NO_PK_BASED = 103;
  
  /** classId for <code>GemFireContainer.BulkKeyLookupResult</code> */
  byte GFXD_BULK_KEY_LOOKUP_RESULT = 104;
  
  /** classId for <code>QueryCancelFunctionArgs</code>s */
  byte GFXD_QUERY_CANCEL_FUNCTION_ARGS = 105;
  
  //Spark Module related bytes
  byte GFXD_SPARK_PROFILE = 106;

  byte GFXD_SPARK_TASK_SUBMIT = 107;
  
  byte GFXD_SPARK_TASK_RESULT = 108;

  /** classId for <code>LeadNodeExecutionContext</code> */
  byte LEAD_NODE_EXN_CTX = 109;

  byte SNAPPY_RESULT_HOLDER = 110;

  byte SNAPPY_REGION_STATS_RESULT = 111;

  byte LEAD_NODE_CONN_OP_CTX = 112;

  byte COLUMN_FORMAT_KEY = 113;

  byte COLUMN_FORMAT_VALUE = 114;

  byte COLUMN_FORMAT_DELTA = 115;

  byte COLUMN_DELETE_DELTA = 116;
}
