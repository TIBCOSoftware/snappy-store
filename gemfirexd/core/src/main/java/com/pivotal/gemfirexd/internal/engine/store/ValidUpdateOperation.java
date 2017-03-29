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

package com.pivotal.gemfirexd.internal.engine.store;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Region;
import com.pivotal.gemfirexd.internal.catalog.types.RoutineAliasInfo;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;

/**
 * This class does the validation if an update statement is pk plus some conditions.
 * Right now we only support AND conditions. In future might add more.
 */
public class ValidUpdateOperation {
  public static boolean isValid(Region<?, ?> region, String predicateString,
      DataValueDescriptor[] otherKeyValues) throws
      StandardException {


    ResultSet r = null;
    EmbedConnection conn = null;
    StatementContext statementContext = null;
    boolean contextSet = false;
    boolean popContext = false;
    Throwable t = null;
    // setup LCC and get the ResultSet
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    try {

      if (lcc == null) {
        //a PK based insert is converted into
        // region.put since it bypasses GemFireXD layer, the LCC can be null.
        conn = GemFireXDUtils.getTSSConnection(true, true, false);
        conn.getTR().setupContextStack();
        lcc = conn.getLanguageConnectionContext();


        // lcc can be null if the node has started to go down.
        if (lcc == null) {
          Misc.getGemFireCache().getCancelCriterion()
              .checkCancelInProgress(null);
        }
      }

      final GemFireContainer container = (GemFireContainer)region
          .getUserAttribute();
      String sql = "SELECT * FROM " + container.getQualifiedTableName() + " WHERE " +
          predicateString;

      PreparedStatement queryStatement = lcc.prepareInternalStatement(sql,
          (short)0);

      if (lcc != null) {
        lcc.pushMe();
        popContext = true;
        assert ContextService
            .getContextOrNull(LanguageConnectionContext.CONTEXT_ID) != null;
        statementContext = lcc.pushStatementContext(false, false,
            predicateString, null, false, 0L, true);
        statementContext.setSQLAllowed(RoutineAliasInfo.READS_SQL_DATA,
            true);
        Activation childActivation = queryStatement.getActivation(lcc, false, predicateString);

        GenericParameterValueSet gpvs = (GenericParameterValueSet)childActivation
            .getParameterValueSet();

        if (gpvs != null && gpvs.getParameterCount() > 0) {
          childActivation.setParameters(gpvs, queryStatement.getParameterTypes());
        }

        ((GenericPreparedStatement)queryStatement).setFlags(true, true);


        if (otherKeyValues != null && gpvs.getParameterCount() > 0 ) {
          for (int i = 0; i < otherKeyValues.length; ++i) {
            DataValueDescriptor paramValue = otherKeyValues[i];
            if (paramValue != null) {
              Object value = paramValue.getObject();
              gpvs.setParameterAsObject(i, value, false);
            }
          }
        }

        r = queryStatement.execute(childActivation, true, 0L, true, true);

        if (r.getNextRow() != null) {
          return true;
        }
      }

    } catch (Exception e) {
      e.printStackTrace();
      t = e;
      LogWriter logger = Misc.getCacheLogWriterNoThrow();
      if (logger != null) {
        logger.warning("ValidUpdateOperation: Error while checking validity", e);
      }
    } finally {
      if (r != null) {
        r.close(true);
      }
      if (statementContext != null) {
        lcc.popStatementContext(statementContext, t);
      }
      if (lcc != null && popContext) {
        lcc.popMe();
      }
      if (contextSet) {
        conn.getTR().restoreContextStack();
      }
    }
    return false;
  }

}

