/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdQueryResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdQueryStreamingResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.SnappyResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.message.LeadNodeExecutorMsg;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.TableQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TemporaryRowHolder;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameter;
import com.pivotal.gemfirexd.internal.impl.sql.GenericResultDescription;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.snappy.LeadNodeExecutionContext;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;

import static java.sql.Types.*;

/**
 * Activation implementation for getting results from lead node.
 */
public class SnappyActivation extends BaseActivation {

  private String sql;
  boolean returnRows;
  boolean isPrepStmt;

  public SnappyActivation(LanguageConnectionContext lcc, ExecPreparedStatement eps, 
      boolean returnRows,  boolean isPrepStmt) {
    super(lcc);
    sql = eps.getSource();
    this.preStmt = eps;
    this.returnRows = returnRows;
    this.connectionID = lcc.getConnectionId();
    this.isPrepStmt = isPrepStmt;
  }

  public void initialize_pvs() throws StandardException {
    // Also see ParamConstants.getSQLType()
    // Index 0: Number of parameter
    // Index 1 - 1st parameter: DataTYpe
    // Index 2 - 1st parameter: precision
    // Index 3 - 1st parameter: scale
    // Index 4 - 1st parameter: nullable
    // ...and so on
    int[] preparedResult = prepare();
    assert preparedResult != null;
    assert preparedResult.length > 0;

    int numberOfParameters = preparedResult[0];
    DataTypeDescriptor[] types = new DataTypeDescriptor[numberOfParameters];
    for (int i = 0; i < numberOfParameters; i++) {
      int index = i * 4 + 1;
      SnappyResultHolder.getNewNullDVD(preparedResult[index], i, types,
          preparedResult[index + 1], preparedResult[index + 2], preparedResult[index + 2] == 1);
    }

    boolean hasReturnParameter = false;
    pvs = (GenericParameterValueSet)lcc
        .getLanguageFactory()
        .newParameterValueSet(
            lcc.getLanguageConnectionFactory().getClassFactory()
                .getClassInspector(), numberOfParameters, hasReturnParameter);
    pvs.initialize(types);
    if (preStmt instanceof GenericPreparedStatement) {
      GenericPreparedStatement gps = (GenericPreparedStatement)preStmt;
      gps.setParameterTypes(types);
    }
  }

  @Override
  public void setupActivation(final ExecPreparedStatement ps,
      final boolean scrollable, final String stmt_text) throws StandardException {
  }


  @Override
  public final void checkStatementValidity() throws StandardException {

  }

  public final int[] prepare() throws StandardException {
    try {
      SnappyPrepareResultSet rs = createPreapreResultSet(0);
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SnappyActivation.prepare: Created SnappySelectResultSet: " + rs);
      }
      prepareWithResultSet(rs);
      int[] typeNames = rs.makePrepareResult();
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "SnappyActivation.prepare: Done." +
                " Prepared-result: " + Arrays.toString(typeNames));
      }
      return typeNames;
    } catch (GemFireXDRuntimeException gfxdrtex) {
      StandardException cause = getCause(gfxdrtex);
      if (cause != null) {
        throw cause;
      }
      throw gfxdrtex;
    } catch (IOException ioex) {
      throw StandardException.newException(ioex.getMessage(), (Throwable)ioex);
    }
  }

  public final ResultSet execute() throws StandardException {
    try {
      SnappySelectResultSet rs = createResultSet(0);
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

  protected SnappySelectResultSet createResultSet(int resultsetNumber)
      throws StandardException {
    return new SnappySelectResultSet(this, this.returnRows);
  }

  protected SnappyPrepareResultSet createPreapreResultSet(int resultsetNumber)
      throws StandardException {
    return new SnappyPrepareResultSet(this);
  }

  protected void executeWithResultSet(SnappySelectResultSet rs)
      throws StandardException {
    boolean enableStreaming = this.lcc.streamingEnabled();
    GfxdResultCollector<Object> rc = null;
    rc = getResultCollector(enableStreaming, rs);
    executeOnLeadNode(rs, rc, this.sql, enableStreaming, this.getConnectionID(), this.lcc
        .getCurrentSchemaName(), this.pvs, this.isPrepStmt);
  }

  protected void prepareWithResultSet(SnappyPrepareResultSet rs)
      throws StandardException {
    boolean enableStreaming = this.lcc.streamingEnabled();
    GfxdResultCollector<Object> rc = null;
    rc = getPrepareResultCollector(rs);
    prepareOnLeadNode(rs, rc, this.sql, enableStreaming, this.getConnectionID(), this.lcc
        .getCurrentSchemaName(), this.pvs);
  }

  protected GfxdResultCollector<Object> getResultCollector(
      final boolean enableStreaming, final SnappySelectResultSet rs)
      throws StandardException {
    final GfxdResultCollector<Object> rc;
    if (enableStreaming) {
      rc = new GfxdQueryStreamingResultCollector();
    } else {
      rc = new GfxdQueryResultCollector();
    }
    rs.setupRC(rc);
    return rc;
  }

  protected GfxdResultCollector<Object> getPrepareResultCollector(
      final SnappyPrepareResultSet rs)
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

  public static void executeOnLeadNode(SnappySelectResultSet rs, GfxdResultCollector<Object> rc, String sql,
      boolean enableStreaming, long connId, String schema, ParameterValueSet pvs,
      boolean isPreparedStatement)
      throws StandardException {
    // TODO: KN probably username, statement id and connId to be sent in
    // execution and of course tx id when transaction will be supported.
    LeadNodeExecutionContext ctx = new LeadNodeExecutionContext(connId);
    LeadNodeExecutorMsg msg = new LeadNodeExecutorMsg(sql, schema, ctx, rc, pvs,
        isPreparedStatement, false);
    try {
      msg.executeFunction(enableStreaming, false, rs, true);
    } catch (SQLException se) {
      throw Misc.processFunctionException(
          "SnappyActivation::execute", se, null, null);
    }
  }

  public static void prepareOnLeadNode(SnappyPrepareResultSet rs, GfxdResultCollector<Object> rc, String sql,
      boolean enableStreaming, long connId, String schema, ParameterValueSet pvs)
      throws StandardException {
    // TODO: KN probably username, statement id and connId to be sent in
    // execution and of course tx id when transaction will be supported.
    LeadNodeExecutionContext ctx = new LeadNodeExecutionContext(connId);
    LeadNodeExecutorMsg msg = new LeadNodeExecutorMsg(sql, schema, ctx, rc, pvs,
        true, true);
    try {
      msg.executeFunction(enableStreaming, false, rs, true);
    } catch (SQLException se) {
      throw Misc.processFunctionException(
          "SnappyActivation::prepareOnLeadNode", se, null, null);
    }
  }

  public static boolean isColumnTable(DMLQueryInfo dmlQueryInfo, boolean skipLocks) {
    if (dmlQueryInfo != null) {
      ExternalCatalog extCatalog = Misc.getMemStore().getExternalCatalog();
      if (extCatalog != null) {
        List<TableQueryInfo> allTables = dmlQueryInfo.getTableQueryInfoList();
        if (allTables != null) {
          for (TableQueryInfo t : allTables) {
            if (null != t) {
              LocalRegion region = t.getRegion();
              // don't try to query hive for system tables etc
              if (region != null && region.getScope().isLocal()) {
                continue;
              }
              String tabName = t.getFullTableName();
              boolean isColumnTable = extCatalog.isColumnTable(t.getSchemaName(), t.getTableName(), skipLocks);
              if (GemFireXDUtils.TraceQuery) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                    "SnappyActivation.isColumnTable: table-name=" + tabName +
                        " ,isColumnTable=" + isColumnTable);
              }
              if (isColumnTable) {
                return isColumnTable;
              }
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
