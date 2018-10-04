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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.AbstractOperationMessage;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.StatementQueryExecutor;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.NcjHashMapWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.XPLAINDistPropsDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.ConstantValueSetImpl;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * A {@link RegionExecutorMessage} that executes a {@link Statement} using the
 * given routing objects for the given region.
 * 
 * @author swale
 */
public class StatementExecutorMessage<T> extends RegionExecutorMessage<T> {

  protected String defaultSchema;

  public long connectionId;

  protected long statementId;

  protected int executionId;
  
  protected long rootId;
  
  protected int statementLevel;
  
  protected String source;
  
  protected ParameterValueSet pvs;

  protected int queryFlags;

  protected short extraFlags;
  
  protected short HDFSFlags = 0;
  
  private long timeOutMillis = 0L;

  // bitmasks used in AbstractOperationMessage.computeCompressedShort

  public transient GfxdConnectionWrapper wrapperForMarkUnused;

  /** Set when the source string for SQL statements is being sent. */
  protected static final short SOURCE_STRING_SET = UNRESERVED_FLAGS_START;
  // flags stored in queryFlags below
  protected static final short ISSELECT = (UNRESERVED_FLAGS_START << 1);
  protected static final short OPTIMIZE_FOR_WRITE = (ISSELECT << 1);
  protected static final short ISOUTERJOINRR = (OPTIMIZE_FOR_WRITE << 1);
  protected static final short HASAUTHID = (ISOUTERJOINRR << 1);
  protected static final short NEED_GFXD_SUB_ACTIVATION = (HASAUTHID << 1);
  protected static final short ENABLE_STATS = (NEED_GFXD_SUB_ACTIVATION << 1);
  protected static final short SELECT_FOR_UPDATE_NEED_KEY = (ENABLE_STATS << 1);
  protected static final int WITH_SECONDARIES = SELECT_FOR_UPDATE_NEED_KEY << 1;

  protected static final int QUERY_FLAGS_MASK = ~((SOURCE_STRING_SET << 1) - 1);
  protected static final short HAS_ACTIVATION_FLAGS =
    (ISOUTERJOINRR | SELECT_FOR_UPDATE_NEED_KEY);

  // extraFlags below

  protected static final byte ENABLE_EXPLAIN_CONNECTION = 0x01;
  protected static final byte SKIP_LISTENERS = 0x02;
  protected static final byte INSERT_SUBSELECT = 0x04;
  // Handle union, intersect or except operators
  protected static final byte HAS_SET_OPERATOR_NODE = 0x08;
  protected static final byte IGNORE_REPLICATE_IF_SET_OPERATOR = 0x10;
  protected static final byte NEED_REGION_KEY_FOR_SET_OPERATOR = 0x20;
  protected static final byte ALL_TABLES_REPLICATED_ON_REMOTE = 0x40;
  protected static final short DISALLOW_SUBQUERY_FLATTENING = (short)0x80;
  private static final short IS_NCJ_QUERY_ON_REMOTE = (short)0x100;
  
  //HDFSFlags
  protected static final short QUERY_HDFS = 0x01;
  protected static final short SKIP_CONSTRAINT_CHECKS = 0x02;
  
  /*
   * any new flags added above should also be captured in distribution props at
   * @{link this#setDistributionStatistics}
   */
  static {
    if ((QUERY_FLAGS_MASK | (ISSELECT - 1)) != 0xffffffff) {
      Assert.fail("unexpected value for QUERY_FLAGS_MASK 0x"
          + Integer.toHexString(QUERY_FLAGS_MASK) + " SOURCE_STRING_SET=0x"
          + Integer.toHexString(SOURCE_STRING_SET));
    }
  }

  /** Empty constructor for deserialization. Not to be invoked directly. */
  public StatementExecutorMessage() {
    super(true);
  }

  public StatementExecutorMessage(ResultCollector<Object, T> collector,
      String defaultSchema, long connectionId, long statementId,
      int executionId, long rootId, int statementLevel, String source,
      boolean isSelect, boolean optimizeForWrite, boolean withSecondaries,
      boolean isSpecialCaseOuterJoin, boolean needGfxdSubActivation,
      boolean flattenSubquery, boolean needKeysForSelectForUpdateCase,
      ParameterValueSet pvs, LocalRegion region, Set<Object> routingObjects,
      final boolean insertAsSubSelect, LanguageConnectionContext lcc,
      long timeOutMillis, boolean abortOnLowMemory) {
    super(collector, region, routingObjects, getCurrentTXState(lcc),
        getTimeStatsSettings(lcc), abortOnLowMemory);
    // skip setting defaultSchema when it is for APP system user
    if (source != null && !(Property.DEFAULT_USER_NAME.equals(defaultSchema) &&
        Property.DEFAULT_USER_NAME.equals(lcc.getDataDictionary()
            .getAuthorizationDatabaseOwner()))) {
      this.defaultSchema = defaultSchema;
      this.queryFlags = GemFireXDUtils.set(this.queryFlags, HASAUTHID);
    }
    this.connectionId = connectionId;
    this.statementId = statementId;
    this.executionId = executionId;
    this.rootId = rootId;
    this.statementLevel = statementLevel;
    this.source = source;
    this.timeOutMillis = timeOutMillis;
    if (isSelect) {
      this.queryFlags = GemFireXDUtils.set(this.queryFlags, ISSELECT);
    }
    if (optimizeForWrite) {
      this.queryFlags = GemFireXDUtils.set(this.queryFlags, OPTIMIZE_FOR_WRITE);
    }
    if (withSecondaries) {
      this.queryFlags = GemFireXDUtils.set(this.queryFlags, WITH_SECONDARIES);
    }
    if (isSpecialCaseOuterJoin) {
      this.queryFlags = GemFireXDUtils.set(this.queryFlags, ISOUTERJOINRR);
    }
    if (needGfxdSubActivation) {
      this.queryFlags = GemFireXDUtils.set(this.queryFlags,
          NEED_GFXD_SUB_ACTIVATION);
    }
    if (lcc.statsEnabled()) {
      this.queryFlags = GemFireXDUtils.set(this.queryFlags, ENABLE_STATS);
    }
    if (lcc.explainConnection()) {
      this.extraFlags = GemFireXDUtils.set(this.extraFlags,
          ENABLE_EXPLAIN_CONNECTION);
    }
    if (lcc.isSkipListeners()) {
      this.extraFlags = GemFireXDUtils.set(this.extraFlags, SKIP_LISTENERS);
    }
    if (insertAsSubSelect) {
      this.extraFlags = GemFireXDUtils.set(this.extraFlags, INSERT_SUBSELECT);
    }
    if (!flattenSubquery) {
      this.extraFlags = GemFireXDUtils.set(this.extraFlags,
          StatementExecutorMessage.DISALLOW_SUBQUERY_FLATTENING);
    }
    if (needKeysForSelectForUpdateCase) {
      this.queryFlags = GemFireXDUtils.set(this.queryFlags,
          StatementExecutorMessage.SELECT_FOR_UPDATE_NEED_KEY);
    }
    this.pvs = pvs;    
    if (lcc.getQueryHDFS()) {
      this.HDFSFlags = GemFireXDUtils.set(this.HDFSFlags, QUERY_HDFS);
    }
    if (lcc.isSkipConstraintChecks()) {
      this.HDFSFlags = GemFireXDUtils.set(this.HDFSFlags, SKIP_CONSTRAINT_CHECKS);
    }
  }

  /** copy constructor */
  protected StatementExecutorMessage(final StatementExecutorMessage<T> other) {
    super(other);
    this.defaultSchema = other.defaultSchema;
    this.connectionId = other.connectionId;
    this.statementId = other.statementId;
    this.executionId = other.executionId;
    this.rootId = other.rootId;
    this.statementLevel = other.statementLevel;
    this.source = other.source;
    this.timeOutMillis = other.timeOutMillis;
    this.queryFlags = other.queryFlags;
    this.pvs = other.pvs;
    this.extraFlags = other.extraFlags;
    this.HDFSFlags = other.HDFSFlags;
  }

  @Override
  protected void execute() throws Exception {
    String argsStr = null;
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      final StringBuilder sb = new StringBuilder();
      appendFields(sb);
      argsStr = sb.toString();
    }
    this.wrapperForMarkUnused = null;
    StatementQueryExecutor.executeStatement(this.defaultSchema,
        this.connectionId, this.statementId, this.rootId, this.statementLevel,
        this.source, isSelect(), getActivationFlags(), needGfxdSubActivation(),
        statsEnabled(), timeStatsEnabled(), this, argsStr, getTXState(),
        this.pvs, allowSubqueryFlattening(), isInsertAsSubSelect(), this);
  }

  @Override
  public void endMessage() {
    if (this.wrapperForMarkUnused != null) {
      this.wrapperForMarkUnused.markUnused();
      this.wrapperForMarkUnused = null;
    }
  }
  
  public long getTimeOutMillis() {
    return this.timeOutMillis;
  }

  protected final int getActivationFlags() {
    int activationFlags = 0;
    if ((this.queryFlags & HAS_ACTIVATION_FLAGS) != 0) {
      if (isSpecialCaseOuterJoin()) {
        activationFlags |= Activation.SPECIAL_OUTER_JOIN;
      }
      else if (isSelectForUpdateAndNeedKeys()) {
        activationFlags |= Activation.NEED_KEY_FOR_SELECT_FOR_UPDATE;
      }
    }
    else if (needKeysForSetOperatorOnRemote()) {
      activationFlags |= Activation.NEED_KEY_FOR_SET_OPERATORS;
    }
    return activationFlags;
  }

  @Override
  protected void setArgsForMember(DistributedMember member,
      Set<DistributedMember> messageAwareMembers) {
  }

  @Override
  public final boolean optimizeForWrite() {
    return GemFireXDUtils.isSet(this.queryFlags, OPTIMIZE_FOR_WRITE);
  }
  
  @Override
  public boolean withSecondaries() {
    return GemFireXDUtils.isSet(this.queryFlags, WITH_SECONDARIES);
  }

  @Override
  protected final boolean requiresTXFlushBeforeExecution() {
    // for sub-query with second-level distribution right now it is not certain
    // if the target node will have the data, so flush for that case
    // flush for distributed updates is handled by doTXFlushBeforeExecution
    return needGfxdSubActivation();
  }

  @Override
  protected void doTXFlushBeforeExecution(final Set<DistributedMember> members,
      final DistributedMember member, final DistributedMember self,
      final TXStateInterface tx) {
    // TODO: PERF: this can be optimized by piggy-backing with this message;
    // if batch includes other members, then also write to those nodes which
    // will then ack back to the targets directly so that if there is an
    // operation on target nodes that needs further distribution then flush
    // is complete; other option includes sending ack from this node to the
    // target nodes when batch flush is complete; also review all other
    // batch flush calls and optimize where possible
    // for distributed update we can skip the flush for batched TX operations
    // only if the only member selected is the current node itself
    if (optimizeForWrite()) {
      if (members != null) {
        if (members.size() > 1 || !members.contains(self)) {
          tx.flushPendingOps(null);
        }
      }
      else if (self != member) {
        tx.flushPendingOps(null);
      }
    }
  }

  @Override
  protected boolean requireFinishTX() {
    return true;
  }

  @Override
  protected StatementExecutorMessage<T> clone() {
    return new StatementExecutorMessage<T>(this);
  }

  @Override
  public final boolean isHA() {
    return true;
  }

 

  @Override
  public byte getGfxdID() {
    return STMNT_EXECUTOR_FUNCTION;
  }

  protected final boolean isSelect() {
    return GemFireXDUtils.isSet(this.queryFlags, ISSELECT);
  }

  protected final boolean allowSubqueryFlattening() {
    return !GemFireXDUtils.isSet(this.extraFlags, DISALLOW_SUBQUERY_FLATTENING);
  }

  protected final boolean isSpecialCaseOuterJoin() {
    return GemFireXDUtils.isSet(this.queryFlags, ISOUTERJOINRR);
  }

  protected final boolean isSelectForUpdateAndNeedKeys() {
    return GemFireXDUtils.isSet(this.queryFlags, SELECT_FOR_UPDATE_NEED_KEY);
  }

  protected final boolean hasAuthId() {
    return GemFireXDUtils.isSet(this.queryFlags, HASAUTHID);
  }

  protected final boolean needGfxdSubActivation() {
    return GemFireXDUtils.isSet(this.queryFlags, NEED_GFXD_SUB_ACTIVATION);
  }

  public final boolean statsEnabled() {
    return GemFireXDUtils.isSet(this.queryFlags, ENABLE_STATS);
  }

  public final boolean getQueryHDFS() {
    return GemFireXDUtils.isSet(this.HDFSFlags, QUERY_HDFS);
  }  

  public final boolean isSkipConstraintChecks() {
    return GemFireXDUtils.isSet(this.HDFSFlags, SKIP_CONSTRAINT_CHECKS);
  }

  public final boolean timeStatsEnabled() {
    //return GemFireXDUtils.isSet(this.queryFlags, ENABLE_TIMESTATS);
    return this.timeStatsEnabled;
  }
  
  public final boolean explainConnectionEnabled() {
    return GemFireXDUtils.isSet(this.extraFlags, ENABLE_EXPLAIN_CONNECTION);
  }
  
  public final boolean isSkipListeners() {
    return GemFireXDUtils.isSet(this.extraFlags, SKIP_LISTENERS);
  }

  protected final boolean isInsertAsSubSelect() {
    return GemFireXDUtils.isSet(this.extraFlags, INSERT_SUBSELECT);
  }
  
  @Override
  public final boolean hasSetOperatorNodeOnRemote() {
    return GemFireXDUtils.isSet(this.extraFlags, HAS_SET_OPERATOR_NODE);
  }
  
  @Override
  public boolean needKeysForSetOperatorOnRemote() {
    return GemFireXDUtils.isSet(this.extraFlags, NEED_REGION_KEY_FOR_SET_OPERATOR);
  }
  
  @Override
  public final boolean doIgnoreReplicatesIfSetOperatorsOnRemote() {
    return GemFireXDUtils
        .isSet(this.extraFlags, IGNORE_REPLICATE_IF_SET_OPERATOR);
  }

  @Override
  public boolean allTablesAreReplicatedOnRemote() {
    return GemFireXDUtils.isSet(this.extraFlags, ALL_TABLES_REPLICATED_ON_REMOTE);
  }
  
  @Override
  public void setNCJoinOnQN(THashMap ncjMetaInfo, LanguageConnectionContext lcc) {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN, this
            .getClass().getSimpleName()
            + "::setNCJoinOnQN: set ncjMetaData "
            + "for NonCollocatedJoin: "
            + ncjMetaInfo
            + " .Enum info="
            + NcjHashMapWrapper.getEnumValues()
            + " .Given Batch Size: "
            + (lcc != null ? lcc.getNcjBatchSize() : "lcc is null")
            + " and Cache Size: "
            + (lcc != null ? lcc.getNcjCacheSize() + " MB" : "lcc is null"));
      }
    }

    this.extraFlags = GemFireXDUtils.set(this.extraFlags,
        IS_NCJ_QUERY_ON_REMOTE);
    if (lcc != null) {
      if (lcc.getNcjBatchSize() > 0) {
        NcjHashMapWrapper.setBatchSize(ncjMetaInfo, lcc.getNcjBatchSize());
      }
      if (lcc.getNcjCacheSize() > 0) {
        NcjHashMapWrapper.setCacheSize(ncjMetaInfo, lcc.getNcjCacheSize());
      }
    }
    super.setNcjMetaData(ncjMetaInfo);
  }

  @Override
  public void setHasSetOperatorNode(boolean hasSetOp, boolean needKeysFromRemote) {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, this
            .getClass().getSimpleName()
            + "setHasSetOperatorNode: set extraFlag "
            + "for union, intersect or except operators: "
            + hasSetOperatorNode
            + " to " + hasSetOp + " and want keys = " + needKeysFromRemote);
      }
    }
    this.hasSetOperatorNode = hasSetOp;
    if (hasSetOp) {
      this.extraFlags = GemFireXDUtils
          .set(this.extraFlags, HAS_SET_OPERATOR_NODE);
      if (needKeysFromRemote) {
        this.extraFlags = GemFireXDUtils.set(this.extraFlags,
            NEED_REGION_KEY_FOR_SET_OPERATOR);
      }
    }
  }

  @Override
  protected void setIgnoreReplicateIfSetOperators(boolean ignoreReplicate) {
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, 
          this.getClass().getSimpleName()
          + " with set operator = " + this.hasSetOperatorNode  
          + " want to set extraFlag for ignoreReplicatesIfSetOperators - " 
          + ignoreReplicate);
    }
    if (ignoreReplicate && this.hasSetOperatorNode) {
      this.extraFlags = GemFireXDUtils.set(this.extraFlags,
          IGNORE_REPLICATE_IF_SET_OPERATOR);
    }
  }

  @Override
  public void setAllTablesAreReplicatedOnRemote(boolean allTablesReplicated) {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager
            .DEBUG_PRINT(
                GfxdConstants.TRACE_QUERYDISTRIB,
                this.getClass().getSimpleName()
                    + " setAllTablesAreReplicated: set extraFlag ALL_TABLES_REPLICATED_ON_REMOTE"
                    + " to " + allTablesReplicated);
      }
    }

    if (allTablesReplicated) {
      this.extraFlags = GemFireXDUtils.set(this.extraFlags,
          ALL_TABLES_REPLICATED_ON_REMOTE);
    }
  }

  /**
   * @see AbstractOperationMessage#computeCompressedShort(short)
   */
  @Override
  protected final short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    if (this.source != null) {
      flags |= SOURCE_STRING_SET;
    }
    flags |= this.queryFlags;
    return flags;
  }

  @Override
  public void toData(DataOutput out)
      throws IOException {
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    super.toData(out);
    final boolean oldVersion = InternalDataSerializer.getVersionForDataStream(
        out).compareTo(Version.GFXD_10) < 0;
    if (oldVersion) {
      out.writeByte((byte)this.extraFlags);
    }
    else {
      out.writeShort(this.extraFlags);
    }
    if (oldVersion) {
      out.writeByte((byte)this.HDFSFlags);
    }
    else {
      out.writeShort(this.HDFSFlags);
    }
    if (hasAuthId()) {
      DataSerializer.writeString(this.defaultSchema, out);
    }
    // no advantage of compressing these longs as such since these span both
    // ints so splitting into two ints and compressing each
    GemFireXDUtils.writeCompressedHighLow(out, this.connectionId);
    GemFireXDUtils.writeCompressedHighLow(out, this.statementId);
    if (explainConnectionEnabled()) {
      InternalDataSerializer.writeSignedVL(this.executionId, out);
    }
    if (this.source != null) {
      DataSerializer.writeString(this.source, out);
    }
    GemFireXDUtils.writeCompressedHighLow(out, this.timeOutMillis);

    //[sb] if statement, treat pvs differently than prep statement.
    if (StatementExecutorMessage.class == this.getClass()) {
      ConstantValueSetImpl.writeBytes(out, this.pvs);
    }
    
    if (GemFireXDUtils.isSet(this.extraFlags, IS_NCJ_QUERY_ON_REMOTE)) {
      if (!oldVersion) {
        DataSerializer.writeTHashMap(getNcjMetaData(), out);
        GemFireXDUtils.writeCompressedHighLow(out, this.rootId);
        // should be handled by byte
        out.writeByte((byte)this.statementLevel);
      }
      else {
        SanityManager
            .THROWASSERT("Non Collocated Join: May not work in Rolling Upgarde or Mixed Version Scenario");
      }
    }

    if (begintime != 0) {
      this.ser_deser_time = XPLAINUtil.recordTiming(begintime);
    }
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    ser_deser_time = this.timeStatsEnabled ? (ser_deser_time == 0 ? -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    super.fromData(in);
    this.queryFlags = (flags & QUERY_FLAGS_MASK);
    final boolean oldVersion = InternalDataSerializer.getVersionForDataStream(
        in).compareTo(Version.GFXD_10) < 0;
    if (oldVersion) {
      this.extraFlags = (short)in.readByte();
    }
    else {
      this.extraFlags = in.readShort();
    }
    if (oldVersion) {
      this.HDFSFlags = (short)in.readByte();
    }
    else {
      this.HDFSFlags = in.readShort();
    }
    if (hasAuthId()) {
      this.defaultSchema = DataSerializer.readString(in);
    }
    this.connectionId = GemFireXDUtils.readCompressedHighLow(in);
    this.statementId = GemFireXDUtils.readCompressedHighLow(in);
    if (explainConnectionEnabled()) {
      this.executionId = (int)InternalDataSerializer.readSignedVL(in);
    }
    if ((flags & SOURCE_STRING_SET) != 0) {
      this.source = DataSerializer.readString(in);
    }
    this.timeOutMillis = GemFireXDUtils.readCompressedHighLow(in);

    //[sb] if statement, treat pvs differently than prep statement.
    if (StatementExecutorMessage.class == this.getClass()) {
      this.pvs = ConstantValueSetImpl.readBytes(in);
    }
    
    if (GemFireXDUtils.isSet(this.extraFlags, IS_NCJ_QUERY_ON_REMOTE)) {
      setNcjMetaData(DataSerializer.readTHashMap(in));
      this.rootId = GemFireXDUtils.readCompressedHighLow(in);
      this.statementLevel = (int)in.readByte();
    }
    
    // recording end of de-serialization here instead of AbstractOperationMessage.
    if (this.timeStatsEnabled && ser_deser_time == -1) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";connectionId=").append(this.connectionId)
        .append(";statementId=").append(this.statementId)
        .append(";executionId=").append(this.executionId).append(";rootId=")
        .append(this.rootId).append(";statementLevel=")
        .append(this.statementLevel).append(";source=").append(this.source)
        .append(";defaultSchema=").append(this.defaultSchema)
        .append(";optimizeForWrite=").append(optimizeForWrite())
        .append(";queryFlags=0x").append(Integer.toHexString(this.queryFlags))
        .append(";extraFlags=0x").append(Integer.toHexString(this.extraFlags))
        .append(";HDFSFlags=0x").append(Integer.toHexString(this.HDFSFlags))
        .append(";pvs=" + this.pvs)
        .append(";timeOutMillis=" + this.timeOutMillis);
  }

  public final void recBeginProcessTime() {
    process_time = XPLAINUtil.recordTiming(-1);
  }

  /* statistics collection methods */

  /**
   * This is not true end process time on the data node, because after
   * this stack unwinding happens doing various closure activities including
   * exception handling. Therefore, current timestamp is what closest we
   * have got before StatementExecutor#execute*() returns.
   * 
   * @return current time stamp.
   */
  public final Timestamp getEndProcessTime() {
    return XPLAINUtil.currentTimeStamp();
  }

  public final String source() {
    return this.source;
  }

  @Override
  public void setDistributionStatistics(XPLAINDistPropsDescriptor distdesc,
      boolean processReplySend) {

    super.setDistributionStatistics(distdesc, processReplySend);

    // derive queryFlags
    {
      StringBuilder sb = new StringBuilder();
      if (isSelect())
        sb.append("is_select,");

      if (GemFireXDUtils.isSet(this.queryFlags, OPTIMIZE_FOR_WRITE))
        sb.append("optimizedForWrite,");

      if (allowSubqueryFlattening())
        sb.append("disallow_subquery_flattening,");

      if (isSpecialCaseOuterJoin())
        sb.append("is_outer_join_rr,");

      if (isSelectForUpdateAndNeedKeys())
        sb.append("select_for_update_need_key,");

      if (hasAuthId())
        sb.append("has_auth_id:").append(defaultSchema).append(",");

      if (needGfxdSubActivation())
        sb.append("need_gfxd_sub_activation,");

      if (statsEnabled())
        sb.append("enable_stats,");

      if (timeStatsEnabled())
        sb.append("enable_timestats,");

      if (explainConnectionEnabled())
        sb.append("explain_connection_mode,");

      if (isSkipListeners())
        sb.append("skip_listeners,");

      final TXStateInterface tx = getTXState();
      if (tx != null) {
        switch (tx.getIsolationLevel().getJdbcIsolationLevel()) {
          case Connection.TRANSACTION_NONE:
            sb.append("none,");
            break;
          case Connection.TRANSACTION_READ_COMMITTED:
            sb.append(XPLAINUtil.ISOLATION_READ_COMMIT).append(",");
            break;
          case Connection.TRANSACTION_READ_UNCOMMITTED:
            sb.append(XPLAINUtil.ISOLATION_READ_UNCOMMITED).append(",");
            break;
          case Connection.TRANSACTION_REPEATABLE_READ:
            sb.append(XPLAINUtil.ISOLATION_REPEAT_READ).append(",");
            break;
          case Connection.TRANSACTION_SERIALIZABLE:
            sb.append(XPLAINUtil.ISOLATION_SERIALIZABLE).append(",");
            break;
        }
      }
      distdesc.setMessageFlags(sb.toString());
    } // end of queryFlags
  }

  public int getExecutionId() {
    return executionId;
  }

  /* end statistics collection methods */
}
