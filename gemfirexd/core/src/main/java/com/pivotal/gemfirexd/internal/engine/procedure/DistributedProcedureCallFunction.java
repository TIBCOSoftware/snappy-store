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

package com.pivotal.gemfirexd.internal.engine.procedure;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.GemFireCache;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.RegionFunctionContext;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionInvocationTargetException;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.procedure.cohort.ProcedureSender;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;

/***
 * 
 * @author yjing
 *
 */
@SuppressWarnings("serial")
public class DistributedProcedureCallFunction implements Function, Declarable {

  public final static String FUNCTIONID = "gfxd-DistributedProcedureCallFunction";

  /**
   * Added for tests that use XML for comparison of region attributes.
   * 
   * @see Declarable#init(Properties)
   */
  @Override
  public void init(Properties props) {
    // nothing required for this function
  }

  public void execute(FunctionContext context) {

    final DistributedProcedureCallFunctionArgs arguments =
      (DistributedProcedureCallFunctionArgs)context.getArguments();
    String sqlText = "<COHORT> " + arguments.sqlText;
    ProcedureSender sender = null;
    long connectionId = arguments.connectionId;
    long timeOutMillis = arguments.timeOutMillis;
    long stmtId = arguments.stmtId;
    Properties props = arguments.props;
    EmbedConnection conn = null;
    GfxdConnectionWrapper wrapper = null;
    int syncVersion = -1;
    String whereClause = arguments.getWhereClause();
    String tableName = arguments.getTableName();
    boolean isPossibleDuplicate = false;
    if (context instanceof RegionFunctionContext) {
      isPossibleDuplicate = ((RegionFunctionContext)context)
          .isPossibleDuplicate();
    }

    /*
     * The following code (and closing connection) still cannot solve the risk
     * between the thread running the procedure and the cache down. It just a
     * temporary solution to alleviate this problem. Need to figure out a
     * correct solution to solve this problem.
     * 
     * [sumedh] This is enough since function execution will check for cache
     * closing at the end in any case.
     */
    RuntimeException thr = null;
    GemFireTransaction tc = null;
    TXStateInterface tx = null;
    Activation act = null;
    try {
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "DistributedProcedureCallFunction: executing procedure "
                + "with arguments [" + arguments + "] posDup="
                + isPossibleDuplicate);
      }
      final String defaultSchema = arguments.defaultSchema;
      // use the connection from context on local node
      /*
       * GemFire functions are executed in separate thread so never use
       * connection from local context which may be coming from a previous
       * execution on this thread (#46480)
      final ContextManager currentCM = ContextService.getFactory()
          .getCurrentContextManager();
      if (currentCM == null || (conn = EmbedConnectionContext
          .getEmbedConnection(currentCM)) == null) {
        tx = TXManagerImpl.getCurrentTXState();
        wrapper = StatementQueryExecutor.getOrCreateWrapper(defaultSchema,
            connectionId);
        conn = wrapper.getConnectionForSynchronization();
      }
      */
      tx = TXManagerImpl.getCurrentTXState();
      wrapper = GfxdConnectionHolder.getOrCreateWrapper(defaultSchema,
          connectionId, false, props);
      conn = wrapper.getConnectionForSynchronization();
      synchronized (conn.getConnectionSynchronization()) {
        syncVersion = wrapper.convertToHardReference(conn);
        tc = (GemFireTransaction)conn.getLanguageConnection()
            .getTransactionExecute();
        GfxdConnectionWrapper.checkForTransaction(conn, tc, tx);

        // TODO: SW: PERF: use an unprepared statement if there are no
        // parameters else this is sure to thrash the statement cache
//        Statement statement = conn.prepareCall(sqlText);
        Statement statement = wrapper.getStatement(sqlText, stmtId, true,
            false, false, false, null, true, 0, 0);
        statement.setQueryTimeout((int) (timeOutMillis / 1000)); //convert to seconds
        EmbedPreparedStatement ps = (EmbedPreparedStatement)statement;

        sender = new ProcedureSender(context.getResultSender(), conn);
        sender.initialize();

        // set result sender
        act = ps.getActivation();
        GenericParameterValueSet pvs = (GenericParameterValueSet)ps.getParms();

        // [sumedh] cannot use GPVS for dynamic values like sender/posDup since
        // it is shared for zero args
        act.setProcedureSender(sender);
        act.setPossibleDuplicate(isPossibleDuplicate);
        pvs.setWhereClause(whereClause);
        pvs.setTableName(tableName);
        // prepare values set
        DataValueDescriptor[] parameters = arguments.parameterValues;
        int[][] parameterInfo = arguments.getParameterInfo();

        int parametersNum = pvs.getParameterCount();
        for (int i = 0; i < parametersNum; ++i) {

          short mode = pvs.getParameterMode(i + 1);
          if (mode == JDBC30Translation.PARAMETER_MODE_IN_OUT
              || mode == JDBC30Translation.PARAMETER_MODE_OUT) {
            pvs.registerOutParameter(i, parameterInfo[i][0],
                parameterInfo[i][1]);
          }
          if (mode == JDBC30Translation.PARAMETER_MODE_IN_OUT
              || mode == JDBC30Translation.PARAMETER_MODE_IN)
            pvs.getParameterForSet(i).setValue(parameters[i]);
        }

        ps.execute();
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
            .getInstance();
        do {
          if (act.isQueryCancelled()) {
            act.checkCancellationFlag();
          }
          ResultSet rs = statement.getResultSet();
          if (rs == null) {
            break;
          }
          EmbedResultSet embedRS = (EmbedResultSet)rs;
          // for the outgoing result set, it is supposed to transmit the data
          // during adding rows.
          if (embedRS.isOutgoingResultSet()) {
            continue;
          }
          if (observer != null) {
            if (observer.beforeProcedureResultSetSend(sender, embedRS)) {
              sender.sendResultSet(embedRS);
            }
          }
          else {
            sender.sendResultSet(embedRS);
          }
        } while (statement.getMoreResults());
        if (observer != null) {
          if (observer.beforeProcedureOutParamsSend(sender, pvs)) {
            sender.sendOutParameters(pvs);
          }
        }
        else {
          sender.sendOutParameters(pvs);
        }
      }
    } catch (Throwable ex) {
      Error err;
      if (ex instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)ex)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "DistributedProcedureCallFunction: exception for procedure: "
                + sqlText, ex);
      }
      if (GemFireXDUtils.retryToBeDone(ex)) {
        thr = new InternalFunctionInvocationTargetException(ex);
      }
      else {
        String cancelInProgress = null;
        GemFireCache cache = null;
        cache = Misc.getGemFireCacheNoThrow();
        if (cache != null) {
          cancelInProgress = cache.getCancelCriterion().cancelInProgress();
          if (cancelInProgress != null) {
            thr = new CacheClosedException(cancelInProgress, ex);
          }
          else if (ex instanceof FunctionException) {
            thr = (FunctionException)ex;
          }
          else {
            thr = new FunctionException(ex);
          }
        }
        else {
          thr = new CacheClosedException();
        }
      }
    } finally {
      // must kill the thread for the outgoing result set.
      try {
        if (sender != null && thr == null) {
          sender.endProcedureCall();
          if (wrapper != null && !wrapper.isClosed()) {
            try {
              wrapper.closeStatement(stmtId);
            } catch (SQLException e) {
              if (GemFireXDUtils.TraceQuery) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                    "DistributedProcedureCallFunction: Ignored exception " +
                    "while closing statement: "
                        + sqlText, e);
              }
            }
          }
        }
      } finally {

        if (act != null) {
          act.setProcedureSender(null);
          act.setPossibleDuplicate(false);
        }
        // reset cached TXState
        if (tc != null) {
          tc.resetTXState();
        }

        if (wrapper != null && !wrapper.isClosed()) {
          if (wrapper.convertToSoftReference(syncVersion)) {
            // this should be *outside* of sync(conn) block and always the last
            // thing to be done related to connection set/reset
            wrapper.markUnused();
          }
        }
      }

      if (thr != null) {
        throw thr;
      }
    }
  }

  public static DistributedProcedureCallFunctionArgs newDistributedProcedureCallFunctionArgs(
      String sqlText, String whereClause, String tableName,
      String defaultSchema, long connId, DataValueDescriptor[] parameterValues,
      int[][] parameterInfo, Properties props, long timeOutMillis, long stmtId) {
    return (new DistributedProcedureCallFunctionArgs(sqlText, whereClause,
        tableName, defaultSchema, connId, parameterValues, parameterInfo,
        props, timeOutMillis, stmtId));
  }

  public String getId() {
    return FUNCTIONID;
  }

  public boolean hasResult() {
    return true;
  }

  public boolean isHA() {
    return true;
  }

  public boolean optimizeForWrite() {
    return true;
    //return false;
  }

  public final static class DistributedProcedureCallFunctionArgs extends
      GfxdDataSerializable implements Serializable {

    String sqlText;

    String whereClause;

    String tableName;
    
    String defaultSchema;

    DataValueDescriptor[] parameterValues;

    int[][] parameterInfo;

    private long connectionId;
    
    private long timeOutMillis;
    
    private long stmtId;
    
    private Properties props;
    
    private final static Version[] serializationVersions = new Version[] {Version.GFXD_101};

    public DistributedProcedureCallFunctionArgs() {
    }

    DistributedProcedureCallFunctionArgs(String sqlText, String whereClause,
        String tableName, String defaultSchema, long connId,
        DataValueDescriptor[] parameterValues, int[][] parameterInfo,
        Properties props, long timeOutMillis, long stmtId) {
      this.sqlText = sqlText;
      this.whereClause = whereClause;
      this.tableName = tableName;
      this.defaultSchema = defaultSchema;
      // pre-initialize any fields into final shape else things may go awry due
      // to concurrent serialization and local execution (which happens in a
      // separate function execution thread) -- see #46480
      if (parameterValues != null) {
        for (DataValueDescriptor dvd : parameterValues) {
          // region is not required for DVD initialization
          dvd.setRegionContext(null);
        }
      }
      this.parameterValues = parameterValues;
      this.parameterInfo = parameterInfo;
      this.connectionId = connId;
      this.timeOutMillis = timeOutMillis;
      this.stmtId = stmtId;
      this.props = props;
    }
    
    public Version[] getSerializationVersions() {
      return serializationVersions;
    }

    public String getSqlText() {
      return this.sqlText;
    }

    public String getWhereClause() {
      return this.whereClause;
    }

    public String getTableName() {
      return this.tableName;  
    }
    
    public DataValueDescriptor[] getParameterValues() {
      return this.parameterValues;
    }

    public int[][] getParameterInfo() {
      return this.parameterInfo;
    }

    @Override
    public byte getGfxdID() {
      return DISTRIBUTED_PROCEDURE_ARGS;
    }
    
    public void fromDataPre_GFXD_1_0_1_0(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      this.sqlText = DataSerializer.readString(in);
      this.whereClause = DataSerializer.readString(in);
      this.tableName = DataSerializer.readString(in);
      this.defaultSchema = DataSerializer.readString(in);
      this.connectionId = GemFireXDUtils.readCompressedHighLow(in);
      this.props = DataSerializer.readObject(in);

      int parameterNumber = in.readInt();
      this.parameterValues = new DataValueDescriptor[parameterNumber];
      for (int i = 0; i < parameterNumber; ++i) {
        this.parameterValues[i] = DataType.readDVD(in);
      }
      this.parameterInfo = new int[parameterNumber][3];
      for (int i = 0; i < parameterNumber; ++i) {
        this.parameterInfo[i][0] = in.readInt();
        this.parameterInfo[i][1] = in.readInt();
        this.parameterInfo[i][2] = in.readInt();
      }
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      fromDataPre_GFXD_1_0_1_0(in);
      this.timeOutMillis = GemFireXDUtils.readCompressedHighLow(in);
      this.stmtId = GemFireXDUtils.readCompressedHighLow(in);
    }
    
    public void toDataPre_GFXD_1_0_1_0(final DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.sqlText, out);
      DataSerializer.writeString(this.whereClause, out);
      DataSerializer.writeString(this.tableName, out);
      DataSerializer.writeString(this.defaultSchema, out);
      GemFireXDUtils.writeCompressedHighLow(out, this.connectionId);
      DataSerializer.writeObject(this.props, out);
      int parameterNumber = this.parameterValues == null ? 0
          : this.parameterValues.length;

      out.writeInt(parameterNumber);
      if (parameterNumber > 0) {
        for (DataValueDescriptor dvd : this.parameterValues) {
          InternalDataSerializer.invokeToData(dvd, out);
//          dvd.toData(out);
        }

        for (int i = 0; i < this.parameterInfo.length; ++i) {
          out.writeInt(this.parameterInfo[i][0]);
          out.writeInt(this.parameterInfo[i][1]);
          out.writeInt(this.parameterInfo[i][2]);
        }
      }
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      toDataPre_GFXD_1_0_1_0(out);
      GemFireXDUtils.writeCompressedHighLow(out, this.timeOutMillis);
      GemFireXDUtils.writeCompressedHighLow(out, this.stmtId);
    }

    @Override
    public String toString() {
      return "DistributedProcedureCallFunctionArgs sqlText '" + this.sqlText
          + "' whereClause '" + this.whereClause + "' tableName '"
          + this.tableName + "' defaultSchema=" + this.defaultSchema
          + ", connectionId=" + this.connectionId + " parameters: "
          + ArrayUtils.objectString(this.parameterValues) + " timeOutMillis:"
          + this.timeOutMillis + " statementId:" + this.stmtId;
    }
  }
}
