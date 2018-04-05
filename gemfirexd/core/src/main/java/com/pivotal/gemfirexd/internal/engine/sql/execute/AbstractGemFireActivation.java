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

package com.pivotal.gemfirexd.internal.engine.sql.execute;

import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TemporaryRowHolder;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ScrollInsensitiveResultSet;

import java.util.Vector;

/**
 * @author Asif
 *
 */
public abstract class AbstractGemFireActivation extends BaseActivation {

  final DMLQueryInfo qInfo;

  final GemFireXDQueryObserver observer;

  volatile AbstractGemFireResultSet currentRS = null;

  protected boolean isPreparedBatch;
  
  // statistics data
  // ----------------
  /** gets initialized on closure from thread local */
  protected Object[] observerStatistics;
  
  protected long constructorTime;

  public AbstractGemFireActivation(ExecPreparedStatement st,
      LanguageConnectionContext _lcc, DMLQueryInfo qi) throws StandardException {
    super(_lcc);
    observer = GemFireXDQueryObserverHolder.getInstance();

    if (observer != null) {
      observer.beforeGemFireActivationCreate(this);
    }
    
    this.preStmt = st;
    this.qInfo = qi;
    
    this.row = new ExecRow[1];
  }

  @Override
  public void setupActivation(final ExecPreparedStatement ps,
      final boolean scrollable, final String stmt_text)
      throws StandardException {
    super.setupActivation(ps, scrollable, stmt_text);
    //[sb] #42482 skip anything while unlinking of activation during
    // rePrepare. see GenericAcitvationHolder#execute() ac.setupActivation(null, false, null);
    if (ps == null) {
      return;
    }
    if(scrollable) {
      this.row = new ExecRow[2];
    }
    // check authorization
    lcc.getAuthorizer().authorize(this, ps, null, Authorizer.SQL_SKIP_OP + (this.qInfo
        .isSelect() ? Authorizer.SQL_SELECT_OP : Authorizer.SQL_WRITE_OP));

    final int paramCnt = this.qInfo.getParameterCount();
    // [sb] don't bother about pvs if paramCnt is zero, as
    // it should be initialized with constants from
    // BaseActivation.setupActivation().
    if (preStmt != null && !preStmt.isSubqueryPrepStatement() && paramCnt > 0
        && this.qInfo.isPreparedStatementQuery()) {
      this.pvs = lcc.getLanguageFactory().newParameterValueSet(
          lcc.getLanguageConnectionFactory().getClassFactory()
              .getClassInspector(), paramCnt, false /*
                                                     * has return parameter
                                                     * harcoded as false
                                                     */);

      // resultDescription = ps.getResultDescription();
      // this.scrollable = scrollable;

      // Initialize the parameter set to have allocated
      // DataValueDescriptor objects for each parameter.
      this.pvs.initialize(preStmt.getParameterTypes());
    }
    this.invokeAfterSetupCallback();
    // If sub query prep statement , the PVS will be initialized later
  }

  void invokeAfterSetupCallback() {
    if (observer != null) {
      observer.afterGemFireActivationCreate(this);
    }
  }
  
  @Override
  public final void checkStatementValidity() throws StandardException {
    if (this.preStmt == null || this.preStmt.upToDate()) {
      // Initializing isPreparedBatch
      this.isPreparedBatch = this.isPreparedBatch();
      return;
    }
    StandardException se = StandardException
        .newException(SQLState.LANG_STATEMENT_NEEDS_RECOMPILE);
    se.setReport(StandardException.REPORT_NEVER);
    throw se;
  }

  public final ResultSet execute() throws StandardException {
    this.currentRS = null;
    ScrollInsensitiveResultSet wrapperResultSetToAidScroll = null;
    AbstractGemFireResultSet rs = null;
    // we do the ResultSet#open() before execute to acquire table locks at
    // this point rather than after as would happen in the call to open from
    // Statement#execute()
    try {
      if (!this.lcc.isConnectionForRemote() && this.getScrollable()
          && this.qInfo.isSelect()) {
        rs = createResultSet(1);
        int sourceRowWidth = ((SelectQueryInfo)this.qInfo)
            .getProjectionColumnQueryInfo().length;
        wrapperResultSetToAidScroll = new ScrollInsensitiveResultSet(
            (NoPutResultSet)rs, this, 0, sourceRowWidth, 0.0, 0.0);
        wrapperResultSetToAidScroll.markAsTopResultSet();
        wrapperResultSetToAidScroll.open();
      }
      else {
        rs = createResultSet(0);
        rs.open();
      }
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "AbstractGemFireActivation::execute:post open of resultset =" + rs);
      }
      this.resultSet = rs;
      executeWithResultSet(rs);
      this.currentRS = rs;
    } finally {
      if (this.currentRS == null) {
        if (wrapperResultSetToAidScroll != null) {
          wrapperResultSetToAidScroll.close(false);
        }
        else {
          rs.close(false);
        }
      }
    }
    if (wrapperResultSetToAidScroll != null) {
      return wrapperResultSetToAidScroll;
    }
    return rs;
  }

  protected abstract AbstractGemFireResultSet createResultSet(int resultsetNumber)
      throws StandardException;

  protected abstract void executeWithResultSet(AbstractGemFireResultSet rs)
      throws StandardException;
  
  @Override
  public boolean checkIfThisActivationHasHoldCursor(String tableName) {    
    return false;
  }

 

  public ExecRow getProjectionExecRow() throws StandardException {
    throw new UnsupportedOperationException("AbstractGemFireActivation::"
        + "getProjectionExecRow: needs implementation in child class");
  }

  public void resetProjectionExecRow() throws StandardException {
    throw new UnsupportedOperationException("AbstractGemFireActivation::"
        + "getProjectionExecRow: needs implementation in child class");
  }

  @Override
  public Vector getParentResultSet(String resultSetId) {
    throw new UnsupportedOperationException("AbstractGemFireActivation::"
        + "getParentResultSet: needs implementation");
  }

  @Override
  public ResultDescription getResultDescription() {

    return this.qInfo.getResultDescription();
  }

  @Override
  public final long estimateMemoryUsage() throws StandardException {
    final AbstractGemFireResultSet rs = currentRS;
    if(observer != null) {
      observer.estimatingMemoryUsage(this.preStmt.getUserQueryString(this.getLanguageConnectionContext()), rs);
    }
    if (rs != null) {
      return rs.estimateMemoryUsage();
    }
    else {
      if (GemFireXDUtils.TraceHeapThresh) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
            "AbstractGemFireActivation: currentRS is null ");
      }
    }
    return -1;
  }

 @Override
  public void informOfRowCount(NoPutResultSet resultSet, long rowCount)
      throws StandardException {
   throw new UnsupportedOperationException("AbstractGemFireActivation::informOfRowCount: needs implementation");

  }

  
/*
 @Override
  public void setParameters(ParameterValueSet parameterValues,
      DataTypeDescriptor[] parameterTypes) throws StandardException {
    //jing: this is a temporary solution to avoid to distinguish the distributed and non-
    //distributed activation for the delete statement. 
    //The complete solution refer to the the same function in the BaseActivation.
    
    assert parameterValues!=null;
    parameterValues.transferDataValues(this.pvs);    

  }*/

  @Override
  public void setParentResultSet(TemporaryRowHolder rs, String resultSetId) {
    throw new UnsupportedOperationException("AbstractGemFireActivation::setParentResultSet: needs implementation");

  }

  @Override
  protected int getExecutionCount()
  {
    throw new UnsupportedOperationException("AbstractGemFireActivation::getExecutionCount: needs implementation");
  }

  @Override
  protected Vector getRowCountCheckVector()
  {
    throw new UnsupportedOperationException("AbstractGemFireActivation::getRowCountCheckVector: needs implementation");
  }

  @Override
  protected int getStalePlanCheckInterval()
  {
    throw new UnsupportedOperationException("AbstractGemFireActivation::getStalePlanCheckInterval: needs implementation");
  }

  @Override
  protected void setExecutionCount(int newValue)
  {
    throw new UnsupportedOperationException("AbstractGemFireActivation::setExecutionCount: needs implementation");
    
  }

  @Override
  protected void setRowCountCheckVector(Vector newValue)
  {
    throw new UnsupportedOperationException("AbstractGemFireActivation::setRowCountCheckVector: needs implementation");
    
  }



  @Override
  protected void setStalePlanCheckInterval(int newValue)
  {
    throw new UnsupportedOperationException("AbstractGemFireActivation::setStalePlanCheckInterval: needs implementation");
    
  }

  public void postConstructor() throws StandardException
  {
    throw new UnsupportedOperationException("AbstractGemFireActivation::postConstructor: needs implementation");
    
  }  
  
}
