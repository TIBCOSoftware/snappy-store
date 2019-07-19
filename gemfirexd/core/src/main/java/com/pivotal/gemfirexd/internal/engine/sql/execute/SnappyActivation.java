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

package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Vector;

import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdQueryResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdQueryStreamingResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.SnappyResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.message.LeadNodeExecutorMsg;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TemporaryRowHolder;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericResultDescription;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext;

/**
 * Activation implementation for getting results from lead node.
 */
public class SnappyActivation extends BaseActivation {

  private String sql;
  private boolean returnRows;
  private boolean isPrepStmt;
  private boolean isUpdateOrDeleteOrPut;

  public SnappyActivation(LanguageConnectionContext lcc, ExecPreparedStatement eps, 
      boolean returnRows,  boolean isPrepStmt, boolean isUpdateOrDeleteOrPut) {
    super(lcc);
    sql = eps.getSource();
    this.preStmt = eps;
    this.returnRows = returnRows;
    this.connectionID = lcc.getConnectionId();
    this.isPrepStmt = isPrepStmt;
    this.isUpdateOrDeleteOrPut = isUpdateOrDeleteOrPut;
  }

  public void initialize_pvs() throws StandardException {
    SnappyPrepareResultSet preparedResult = prepare();

    try {
      // Also see ParamConstants.getSQLType()
      // Index 0: Number of parameter
      // Index 1 - 1st parameter: DataTYpe
      // Index 2 - 1st parameter: precision
      // Index 3 - 1st parameter: scale
      // Index 4 - 1st parameter: nullable
      // ...and so on
      int[] typeNames = preparedResult.makePrepareResult();
      assert typeNames != null;
      assert typeNames.length > 0;
      int numberOfParameters = typeNames[0];
      DataTypeDescriptor[] types = new DataTypeDescriptor[numberOfParameters];
      for (int i = 0; i < numberOfParameters; i++) {
        int index = i * 4 + 1;
        SnappyResultHolder.getNewNullDVD(typeNames[index], i, types,
            typeNames[index + 1], typeNames[index + 2], typeNames[index + 2] == 1);
      }

      pvs = lcc.getLanguageFactory().newParameterValueSet(
          lcc.getLanguageConnectionFactory().getClassFactory().getClassInspector(),
          numberOfParameters, false);
      pvs.initialize(types);
      if (preStmt instanceof GenericPreparedStatement) {
        GenericPreparedStatement gps = (GenericPreparedStatement)preStmt;
        gps.setParameterTypes(types);
        final String statementType;
        if (isUpdateOrDeleteOrPut) {
          statementType = "INSERT"; // Default
        } else {
          statementType = "SELECT";
        }
        gps.setResultDescription(preparedResult.makeResultDescription(statementType));
      }
    }  catch (IOException ioex) {
      throw StandardException.newException(ioex.getMessage(), ioex);
    }
  }

  @Override
  public void setupActivation(final ExecPreparedStatement ps,
      final boolean scrollable, final String stmt_text) throws StandardException {
  }


  @Override
  public final void checkStatementValidity() throws StandardException {

  }

  public final SnappyPrepareResultSet prepare() throws StandardException {
    try {
      SnappyPrepareResultSet rs = createPrepareResultSet();
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SnappyActivation.prepare: Created SnappySelectResultSet: " + rs);
      }
      prepareWithResultSet(rs);
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SnappyActivation.prepare: Done.");
      }
      return rs;
    } catch (GemFireXDRuntimeException gfxdrtex) {
      StandardException cause = getCause(gfxdrtex);
      if (cause != null) {
        throw cause;
      }
      throw gfxdrtex;
    }
  }

  public final ResultSet execute() throws StandardException {
    try {
      SnappySelectResultSet rs = createResultSet();
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SnappyActivation.execute: Created SnappySelectResultSet: " + rs);
      }
      rs.open();
      this.resultSet = rs;
      executeWithResultSet(rs);
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SnappyActivation.execute: Done");
      }
      return rs;
    } catch (GemFireXDRuntimeException gfxdrtex) {
      StandardException cause = getCause(gfxdrtex);
      if (cause != null) {
        throw cause;
      }
      throw gfxdrtex;
    }
  }

  private StandardException getCause(GemFireXDRuntimeException gfxe) {
    Throwable cause = gfxe.getCause();
    while (cause != null) {
      if (cause instanceof StandardException) {
        return (StandardException)cause;
      }
      cause = cause.getCause();
    }
    return null;
  }

  private SnappySelectResultSet createResultSet()
      throws StandardException {
    if (isUpdateOrDeleteOrPut) {
      return new SnappyUpdateDeletePutResultSet(this, this.returnRows);
    } else {
      return new SnappySelectResultSet(this, this.returnRows);
    }
  }

  private SnappyPrepareResultSet createPrepareResultSet()
      throws StandardException {
    return new SnappyPrepareResultSet(this);
  }

  private void executeWithResultSet(SnappySelectResultSet rs)
      throws StandardException {
    boolean enableStreaming = this.lcc.streamingEnabled();
    GfxdResultCollector<Object> rc = getResultCollector(enableStreaming, rs);
    executeOnLeadNode(rs, rc, this.sql, enableStreaming, this.getConnectionID(), this.lcc
        .getCurrentSchemaName(), this.pvs, this.isPrepStmt, this.isUpdateOrDeleteOrPut, this.lcc);
  }

  private void prepareWithResultSet(SnappyPrepareResultSet rs)
      throws StandardException {
    GfxdResultCollector<Object> rc = getPrepareResultCollector(rs);
    prepareOnLeadNode(rs, rc, this.sql, this.getConnectionID(), this.lcc
        .getCurrentSchemaName(), this.pvs, this.isUpdateOrDeleteOrPut, this.lcc);
  }

  private GfxdResultCollector<Object> getResultCollector(final boolean enableStreaming,
      final SnappySelectResultSet rs) throws StandardException {
    final GfxdResultCollector<Object> rc;
    if (enableStreaming) {
      rc = new GfxdQueryStreamingResultCollector();
    } else {
      rc = new GfxdQueryResultCollector();
    }
    rs.setupRC(rc);
    return rc;
  }

  private GfxdResultCollector<Object> getPrepareResultCollector(final SnappyPrepareResultSet rs)
      throws StandardException {
    final GfxdResultCollector<Object> rc = new GfxdQueryResultCollector();
    rs.setupRC(rc);
    return rc;
  }

  @Override
  public boolean checkIfThisActivationHasHoldCursor(String tableName) {
    return false;
  }

  @Override
  public Vector getParentResultSet(String resultSetId) {
    throw new UnsupportedOperationException("SnappyActivation::"
        + "getParentResultSet: not implemented");
  }

  @Override
  public ResultDescription getResultDescription() {
    if (this.resultDescription == null) {
      this.resultDescription = makeResultDescription(this.resultSet);
    }
    return this.resultDescription;
  }

  public static ResultDescription makeResultDescription(ResultSet resultSet) {
    assert resultSet != null : "expected non noll result set";
    assert resultSet instanceof SnappySelectResultSet : "expected SnappySelectResultSet type result set";
    SnappySelectResultSet srs = (SnappySelectResultSet)resultSet;
    return srs.makeResultDescription();
  }

  @Override
  public final long estimateMemoryUsage() throws StandardException {
    return -1;
  }

  @Override
  public void informOfRowCount(NoPutResultSet resultSet, long rowCount)
      throws StandardException {
    throw new UnsupportedOperationException("SnappyActivation::informOfRowCount: not implemented");

  }

  @Override
  public void setParentResultSet(TemporaryRowHolder rs, String resultSetId) {
    throw new UnsupportedOperationException("SnappyActivation::setParentResultSet: not implemented");

  }

  @Override
  protected int getExecutionCount() {
    throw new UnsupportedOperationException("SnappyActivation::getExecutionCount: not implemented");
  }

  @Override
  protected Vector getRowCountCheckVector() {
    throw new UnsupportedOperationException("SnappyActivation::getRowCountCheckVector: not implemented");
  }

  @Override
  protected int getStalePlanCheckInterval() {
    throw new UnsupportedOperationException("SnappyActivation::getStalePlanCheckInterval: not implemented");
  }

  @Override
  protected void setExecutionCount(int newValue) {
    throw new UnsupportedOperationException("SnappyActivation::setExecutionCount: not implemented");

  }

  @Override
  protected void setRowCountCheckVector(Vector newValue) {
    throw new UnsupportedOperationException("SnappyActivation::setRowCountCheckVector: not implemented");

  }


  @Override
  protected void setStalePlanCheckInterval(int newValue) {
    throw new UnsupportedOperationException("SnappyActivation::setStalePlanCheckInterval: not implemented");

  }

  public void postConstructor() throws StandardException {
    throw new UnsupportedOperationException("SnappyActivation::postConstructor: not implemented");
  }

  public void setResultDescription(GenericResultDescription resultDescription) {
    this.resultDescription = resultDescription;
  }

  private static void executeOnLeadNode(SnappySelectResultSet rs, GfxdResultCollector<Object> rc,
      String sql, boolean enableStreaming, long connId, String schema, ParameterValueSet pvs,
      boolean isPreparedStatement, boolean isUpdateOrDeleteOrPut, LanguageConnectionContext lcc)
      throws StandardException {
    // TODO: KN probably username, statement id and connId to be sent in
    // execution and of course tx id when transaction will be supported.
    LeadNodeExecutionContext ctx = new LeadNodeExecutionContext(connId);
    LeadNodeExecutorMsg msg = new LeadNodeExecutorMsg(sql, schema, ctx, rc, pvs,
        isPreparedStatement, false, isUpdateOrDeleteOrPut);
    // release all locks before sending the message else it can lead to deadlocks
    if (lcc != null) {
      lcc.getTransactionExecute().releaseAllLocks(true, true);
    }
    // wait for any pending transactions before routing new operations to lead
    final TXManagerImpl.TXContext txContext = TXManagerImpl
        .currentTXContext();
    if (txContext != null) {
      txContext.waitForPendingCommit();
    }
    try {
      msg.executeFunction(enableStreaming, false, rs, true);
    } catch (RuntimeException | SQLException ex) {
      Exception e = LeadNodeExecutorMsg.handleLeadNodeException(ex, sql);
      throw Misc.processFunctionException(
          "SnappyActivation::executeOnLeadNode", e, null, null);
    }
  }

  private static void prepareOnLeadNode(SnappyPrepareResultSet rs, GfxdResultCollector<Object> rc,
      String sql, long connId, String schema, ParameterValueSet pvs,
      boolean isUpdateOrDeleteOrPut, LanguageConnectionContext lcc) throws StandardException {
    // TODO: KN probably username, statement id and connId to be sent in
    // execution and of course tx id when transaction will be supported.
    LeadNodeExecutionContext ctx = new LeadNodeExecutionContext(connId);
    LeadNodeExecutorMsg msg = new LeadNodeExecutorMsg(sql, schema, ctx, rc, pvs,
        true, true, isUpdateOrDeleteOrPut);
    if (lcc != null) {
      lcc.getTransactionExecute().releaseAllLocks(true, true);
    }
    try {
      msg.executeFunction(false, false, rs, true);
    } catch (RuntimeException | SQLException ex) {
      Exception e = LeadNodeExecutorMsg.handleLeadNodeException(ex, sql);
      throw Misc.processFunctionException(
          "SnappyActivation::prepareOnLeadNode", e, null, null);
    }
  }

  public static boolean isColumnTable(DMLQueryInfo dmlQueryInfo) {
    if (dmlQueryInfo != null) {
      List<GemFireContainer> allContainers = dmlQueryInfo.getContainerList();
      boolean isColumnTable;
      if (allContainers != null) {
        for (GemFireContainer container : allContainers) {
          if (null != container) {
            isColumnTable = container.isRowBuffer();
            if (GemFireXDUtils.TraceQuery) {
              String tabName = container.getSchemaName() + container.getTableName();
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                  "SnappyActivation.isColumnTable: table-name=" + tabName +
                      " ,isColumnTable=" + isColumnTable);
            }
            if (isColumnTable) {
              return true;
            }
          }
        }
      }
    }
    if (GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "SnappyActivation.isColumnTable: return false");
    }
    return false;
  }
}
