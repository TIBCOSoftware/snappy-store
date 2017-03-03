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
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.sql.CallableStatement;
import java.util.ArrayList;
import java.util.Properties;

import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;

/**
 * A function to cancel a given statement <i>asynchronously</i> on all nodes. This will
 * just set the cancellation flag in Activation by calling
 * {@link Activation#cancelOnUserRequest()}. The query will be actually
 * cancelled when {@link Activation#checkCancellationFlag()} is called during
 * result set processing.
 * 
 * @author shirishd
 */
@SuppressWarnings("serial")
public final class QueryCancelFunction implements Function, Declarable {
  
  public final static String ID = "gfxd-QueryCancelFunction";

  /**
   * Added for tests that use XML for comparison of region attributes.
   * 
   * @see Declarable#init(Properties)
   */
  @Override
  public void init(Properties props) {
    // nothing required for this function
  }

  @Override
  public boolean hasResult() {
    return false;
  }

  @Override
  public void execute(FunctionContext context) {
    QueryCancelFunctionArgs args = (QueryCancelFunctionArgs)context
        .getArguments();
    GfxdConnectionWrapper wrapper = GfxdConnectionHolder.getHolder()
        .getExistingWrapper(args.connectionId);

    EmbedStatement stmt = null;
    LanguageConnectionContext lcc = null;
    if (wrapper != null) {
      EmbedConnection conn = wrapper.getConnectionOrNull();
      stmt = wrapper.getStatementForCancellation(args.statementId,
          args.executionId);
      if (conn != null) {
        lcc = conn.getLanguageConnection();
      }
    }
    if (lcc == null) {
      lcc = getLccFromContextService(args.connectionId);
    }
    
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
          " QueryCancelFunction#execute wrapper=" + wrapper + " statement="
              + stmt + " lcc=" + lcc + " connectionId=" + args.connectionId
              + " statementId=" + args.statementId + " executionId="
              + args.executionId);
    }

    // CallableStatement does not get added to lcc
    if (stmt instanceof CallableStatement) {
      Activation activationToBeCancelled = stmt.getActivation();
      if (activationToBeCancelled != null
          && !activationToBeCancelled.isClosed()
          // executionID is matched only if it is non-zero
          && (activationToBeCancelled.getExecutionID() == args.executionId 
          || args.executionId == 0)) {
        activationToBeCancelled.cancelOnUserRequest();
      }
      return;
    }
    
    if (lcc != null) {
      ArrayList<?> activationList = lcc.getAllActivations();
      Activation activation = null;
      for(int index = activationList.size() - 1; index >= 0; index-- ) {
        //refer GenericLanguageConnectionContext#closeUnusedActivations()
        if (index >= activationList.size()) {
          continue;
        }
        activation = (Activation)activationList.get(index);
        
        if (needToCancelThisActivation(activation, args)) {
          activation.cancelOnUserRequest();
        }
      }
    }
  }

  /**
   * Get the lcc for the passed in connectionId 
   */
  private LanguageConnectionContext getLccFromContextService(
      final long connectionId) {
    final LanguageConnectionContext[] result = new LanguageConnectionContext[1];
    final GemFireXDUtils.Visitor<LanguageConnectionContext> getLcc =
        new GemFireXDUtils.Visitor<LanguageConnectionContext>() {
          @Override
          public boolean visit(LanguageConnectionContext lcc) {
            if (lcc.getConnectionId() == connectionId) {
              result[0] = lcc;
              return false;
            }
            return true;
          }
        };
    GemFireXDUtils.forAllContexts(getLcc);
    return result[0];
  }

  // is this the activation we want to cancel?
  // match statementId and executionID 
  private boolean needToCancelThisActivation(Activation activation,
      QueryCancelFunctionArgs args) {
    return activation != null
        && !activation.isClosed()
        && (activation.getStatementID() == args.statementId || activation.getRootID() == args.statementId)  
        // executionID is matched only if it is non-zero
        && (activation.getExecutionID() == args.executionId || args.executionId == 0);
  }

  @Override
  public String getId() {
    return QueryCancelFunction.ID;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  public boolean isHA() {
    return false;
  }

  public static QueryCancelFunctionArgs newQueryCancelFunctionArgs(
      long statementId, long connectionId) {
    return new QueryCancelFunctionArgs(statementId, 0, connectionId);
  }

  public static QueryCancelFunctionArgs newQueryCancelFunctionArgs(
      long statementId, long executionId, long connectionId) {
    return (new QueryCancelFunctionArgs(statementId, executionId, connectionId));
  }

  /**
   * Arguments for the QueryCancelFunction
   * @author shirishd
   */
  public final static class QueryCancelFunctionArgs extends
  GfxdDataSerializable implements Serializable {
    private long statementId;
    private long executionId;
    private long connectionId;
    
    public QueryCancelFunctionArgs() {
    }
    
    public QueryCancelFunctionArgs(long statementId, long executionId,
        long connectionId) {
      this.statementId = statementId;
      this.executionId = executionId;
      this.connectionId = connectionId;
    }

    @Override
    public byte getGfxdID() {
      return GFXD_QUERY_CANCEL_FUNCTION_ARGS;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      GemFireXDUtils.writeCompressedHighLow(out, this.statementId);
      GemFireXDUtils.writeCompressedHighLow(out, this.executionId);
      GemFireXDUtils.writeCompressedHighLow(out, this.connectionId);
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      this.statementId = GemFireXDUtils.readCompressedHighLow(in);
      this.executionId = GemFireXDUtils.readCompressedHighLow(in);
      this.connectionId = GemFireXDUtils.readCompressedHighLow(in);
    }
  }
}