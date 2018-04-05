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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.DVDIOUtil;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.StatementQueryExecutor;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.NcjHashMapWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * A {@link RegionExecutorMessage} that executes a {@link PreparedStatement}
 * using the given routing objects for the given region.
 * 
 * @author swale
 */
public final class PrepStatementExecutorMessage<T> extends
    StatementExecutorMessage<T> {

  /** Stores the original source that will be restored for retries etc. */
  private final String origSource;

  // transient members set during deserialization and used in execute

  private transient byte[] pvsData;

  private transient GfxdConnectionWrapper wrapper;

  private transient EmbedPreparedStatement pstmt;

  /** Empty constructor for deserialization. Not to be invoked directly. */
  public PrepStatementExecutorMessage() {
    this.origSource = null;
  }

  public PrepStatementExecutorMessage(ResultCollector<Object, T> collector,
      String userName, long connectionId, long statementId, int executionId,
      long rootID, int statementLevel, String source, boolean isSelect,
      boolean optimizeForWrite, boolean withSecondaries,
      boolean isSpecialCaseOuterJoin, boolean needGfxdSubActivation,
      boolean flattenSubquery, boolean needKeysForSelectForUpdate,
      ParameterValueSet pvs, LocalRegion region, Set<Object> routingObjects,
      boolean insertAsSubselect, LanguageConnectionContext lcc,
      long timeOutMillis, boolean abortOnLowMemory) {
    super(collector, userName, connectionId, statementId, executionId, rootID,
        statementLevel, source, isSelect, optimizeForWrite, withSecondaries,
        isSpecialCaseOuterJoin, needGfxdSubActivation, flattenSubquery,
        needKeysForSelectForUpdate, pvs, region, routingObjects,
        insertAsSubselect, lcc, timeOutMillis, abortOnLowMemory);
    this.origSource = source;
  }

  private PrepStatementExecutorMessage(
      final PrepStatementExecutorMessage<T> other) {
    super(other);
    this.origSource = other.origSource;
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
    StatementQueryExecutor.executePrepStatement(this.defaultSchema,
        this.connectionId, this.statementId, this.source, getActivationFlags(),
        statsEnabled(), timeStatsEnabled(), this, argsStr, getTXState(), this);
  }

  public GfxdConnectionWrapper getAndClearWrapper(final String userName,
      final long connId) throws Exception {
    if (this.pvsData != null) {
      ByteArrayDataInput dis = new ByteArrayDataInput();
      dis.initialize(this.pvsData, null);
      readStatementPVS(dis);
    }
    if (this.pstmt == null) {
      // this is the case of execution in same VM where fromData has not
      // been invoked
      this.pstmt = getStatement();
      ParameterValueSet pvs = this.pvs;
      if (pvs != null && pvs.getParameterCount() > 0) {
        // TODO: PERF: do we need to make a copy in every execution?
        // probably only for local execution
        pvs.transferDataValues(this.pstmt.getParms());
      }
    }
    final GfxdConnectionWrapper wrapper = this.wrapper;
    this.wrapper = null;
    return wrapper;
  }

  public EmbedPreparedStatement getAndClearPreparedStatement() {
    final EmbedPreparedStatement eps = this.pstmt;
    this.pstmt = null;
    return eps;
  }

  @Override
  protected void setArgsForMember(final DistributedMember member,
      final Set<DistributedMember> messageAwareMembers) {
    if ((!(this.defaultSchema != null
        && this.defaultSchema.equalsIgnoreCase(SystemProperties.SNAPPY_HIVE_METASTORE))
        && messageAwareMembers != null)) {
      synchronized (messageAwareMembers) {
        if (messageAwareMembers.contains(member)) {
          if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "PrepStatementExecutorMessage: setting query string to null "
                    + "for member " + member + "; aware members: "
                    + messageAwareMembers);
          }
          this.source = null;
          return;
        }
      }
    }
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      if (messageAwareMembers != null) {
        synchronized (messageAwareMembers) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "PrepStatementExecutorMessage: setting query string to value '"
                  + this.origSource + "' for member " + member
                  + "; aware members: " + messageAwareMembers);
        }
      }
    }
    this.source = this.origSource;
  }

  @Override
  protected final PrepStatementExecutorMessage<T> clone() {
    return new PrepStatementExecutorMessage<T>(this);
  }

  @Override
  public byte getGfxdID() {
    return PREP_STMNT_EXECUTOR_FUNCTION;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    super.toData(out);

    int paramCount = this.pvs != null ? this.pvs.getParameterCount() : 0;
    final int numEightColGroups = BitSetSet.udiv8(paramCount);
    final int numPartialCols = BitSetSet.umod8(paramCount);
    try {
      final HeapDataOutputStream hdos;
      if (this.source != null) {
        if (paramCount > 0) {
          hdos = new HeapDataOutputStream();
          DVDIOUtil.writeParameterValueSet(this.pvs, numEightColGroups,
              numPartialCols, hdos);
          InternalDataSerializer.writeArrayLength(hdos.size(), out);
          hdos.sendTo(out);
        }
        else {
          InternalDataSerializer.writeArrayLength(-1, out);
        }
      }
      else {
        if (numEightColGroups > 0 || numPartialCols > 0) {
          hdos = new HeapDataOutputStream();
          DVDIOUtil.writeParameterValueSet(this.pvs, numEightColGroups,
              numPartialCols, hdos);
          InternalDataSerializer.writeArrayLength(hdos.size(), out);
          hdos.sendTo(out);
        }
        else {
          InternalDataSerializer.writeArrayLength(-1, out);
        }
      }
    } catch (StandardException ex) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception in writing parameters", ex);
    } finally {
      if (begintime != 0) {
        this.ser_deser_time = XPLAINUtil.recordTiming(begintime);
      }
    }
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    ser_deser_time = this.timeStatsEnabled ? (ser_deser_time == 0 ? -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    super.fromData(in);
    try {
      // for source as not-null take the serialized byte array to avoid taking
      // long time in preparing the statement and thus potentially blocking
      // message reader threads
      this.pvsData = DataSerializer.readByteArray(in);
    } catch (RuntimeException ex) {
      throw ex;
    } finally {
      // recording end of de-serialization here instead of AbstractOperationMessage.
      if (this.timeStatsEnabled && ser_deser_time == -1) {
        this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
      }
    }
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";pvs=").append(this.pvs).append(";pvsData=")
        .append(Arrays.toString(this.pvsData)).append(";origSource=")
        .append(this.origSource);
  }

  @Override
  public void reset() {
    super.reset();
    this.pvsData = null;
    this.wrapper = null;
    final EmbedPreparedStatement eps = this.pstmt;
    if (eps != null) {
      try {
        if (!eps.isClosed()) {
          eps.close();
        }
      } catch (SQLException sqle) {
        // ignored
      }
      this.pstmt = null;
    }
  }

  private EmbedPreparedStatement getStatement() throws SQLException {
    Properties props = new Properties();
    props.setProperty(Attribute.QUERY_HDFS, Boolean.toString(getQueryHDFS()));
    this.wrapper = GfxdConnectionHolder.getOrCreateWrapper(this.defaultSchema,
        this.connectionId, false, props);
    final EmbedConnection conn = this.wrapper.getConnectionForSynchronization();
    synchronized (conn.getConnectionSynchronization()) {
      conn.setDefaultSchema(this.defaultSchema);
      this.wrapper.convertToHardReference(conn);
      conn.getLanguageConnection().setStatsEnabled(statsEnabled(),
          timeStatsEnabled(), explainConnectionEnabled());
      conn.getLanguageConnection().setQueryHDFS(getQueryHDFS());
      if (this.getNCJMetaDataOnRemote() != null) {
        conn.getLanguageConnection().setNcjBatchSize(
            NcjHashMapWrapper.getBatchSize(this.getNCJMetaDataOnRemote()));
        conn.getLanguageConnection().setNcjCacheSize(
            NcjHashMapWrapper.getCacheSize(this.getNCJMetaDataOnRemote()));
      }
      this.wrapperForMarkUnused = this.wrapper;
      return (EmbedPreparedStatement)this.wrapper.getStatement(this.source,
          this.statementId, true /* is prep stmnt */, needGfxdSubActivation(),
          allowSubqueryFlattening(), allTablesAreReplicatedOnRemote(),
          this.getNCJMetaDataOnRemote(), false, this.rootId,
          this.statementLevel);
    }
  }

  private void readStatementPVS(final ByteArrayDataInput in)
      throws IOException, SQLException, ClassNotFoundException,
      StandardException {
    this.pstmt = getStatement();
    this.pvs = this.pstmt.getParms();

    final int paramCount = this.pvs.getParameterCount();
    final int numEightColGroups = BitSetSet.udiv8(paramCount);
    final int numPartialCols = BitSetSet.umod8(paramCount);
    DVDIOUtil.readParameterValueSet(this.pvs, in, numEightColGroups,
        numPartialCols);
  }
}
