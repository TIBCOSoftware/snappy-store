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

import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLClob;
import com.pivotal.gemfirexd.internal.impl.sql.compile.Token;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericResultDescription;
import java.util.List;

public class PrepStatementSnappyActivation extends GemFireSelectDistributionActivation {

  volatile AbstractGemFireResultSet currentRS = null;
  private String sql;
  boolean returnRows;

  public PrepStatementSnappyActivation(ExecPreparedStatement eps,
      LanguageConnectionContext _lcc,
      DMLQueryInfo qi,
      boolean returnRows) throws StandardException {
    super(eps, _lcc, qi);
    sql = eps.getSource();
    this.returnRows = returnRows;
    this.connectionID = lcc.getConnectionId();
  }

  @Override
  protected SnappySelectResultSet createResultSet(int resultsetNumber)
      throws StandardException {
    return new SnappySelectResultSet(this, this.returnRows);
  }

  @Override
  protected void executeWithResultSet(AbstractGemFireResultSet rs)
      throws StandardException {
    boolean enableStreaming = this.lcc.streamingEnabled();
    GfxdResultCollector<Object> rc = null;
    rc = getResultCollector(enableStreaming, rs);

    if (this.pvs != null) {
      if (GemFireXDUtils.TraceQuery) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "PrepStatementSnappyActivation.executeWithResultSet: " +
                "No dynamic parameters for sql=" + this.sql);
      }
      SnappyActivation.executeOnLeadNode((SnappySelectResultSet)rs, rc,
          this.sql, enableStreaming, this.getConnectionID(), lcc.getCurrentSchemaName(),
          this.pvs, true);
    } else {
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION,
          "Not a prepared statement. Sql=" + this.sql + " ,isPrepStmt=" +
              this.getIsPrepStmntQuery() + " ,pvs=" + this.pvs);
    }
  }

  @Override
  public ResultDescription getResultDescription() {
    this.resultDescription = SnappyActivation.makeResultDescription(this.resultSet);
    return this.resultDescription;
  }

  public void setResultDescription(GenericResultDescription resultDescription) {
    this.resultDescription = resultDescription;
  }
}
