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

import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.InsertQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.TableQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.reflect.GemFireActivationClass;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionContext;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;

import java.util.List;

/**
 * @author Asif
 * 
 */
public class GemFireActivationFactory {

  public static BaseActivation get(ExecPreparedStatement st,
      LanguageConnectionContext _lcc, boolean addToLCC,
      GemFireActivationClass gc) throws StandardException {
    final BaseActivation actvn;
    
    final DMLQueryInfo qi = (DMLQueryInfo)gc.getQueryInfo();
    if (qi.isDriverTableInitialized()) {
      if (qi.getRegion() != null
          && qi.getRegion().getAttributes().getDataPolicy().withPartitioning()) {
        PartitionedRegion prgn = (PartitionedRegion)qi.getRegion();
        if (qi.isSelect() && _lcc != null && st != null
            && st instanceof GenericPreparedStatement) {
          if (((GenericPreparedStatement)st).hasQueryHDFS()) {
            prgn.setQueryHDFS(((GenericPreparedStatement)st).getQueryHDFS());
          }
          else {
            prgn.setQueryHDFS(_lcc.getQueryHDFS());
          }
        }
        else {
          prgn.setQueryHDFS(prgn.isHDFSRegion());
        }
      }
    }

    if (qi.isPrimaryKeyBased()) {
      if (qi.isSelect() || qi.isSelectForUpdateQuery()) {
        actvn = new GemFireSelectActivation(st, _lcc, qi, gc,
            qi.isSelectForUpdateQuery());
      }
      else if (qi.isUpdate()) {
        if (qi.isCustomEvictionEnabled() && _lcc.getCurrentIsolationLevel()
            != ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL) {
          actvn = new GemFireUpdateDistributionActivation(st, _lcc, qi);
        }
        else {
          actvn = new GemFireUpdateActivation(st, _lcc, qi, gc);
        }

      }
      else if (qi.isDelete()) {
        if (qi.isDeleteWithReferencedKeys()
            || (qi.isCustomEvictionEnabled() && _lcc.getCurrentIsolationLevel()
                != ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL)) {
          // for referenced key checking use function instead of PK delete
          actvn = new GemFireDeleteDistributionActivation(st, _lcc, qi);
        }
        else {
          actvn = new GemFireDeleteActivation(st, _lcc, qi, gc);
        }
      }
      else {
        throw new IllegalStateException("QueryInfo type not supported");
      }
    }
    else if (qi.isGetAllOnLocalIndex() && qi.isSelect()
        && !qi.isSelectForUpdateQuery()) {
      /* Note:
       * Handle GetAll on Local Index, only if
       * a. Its Select
       * b. but not Select For Update
       * c. and not Insert Select
       */
      actvn = new GemFireSelectActivation(st, _lcc, qi, gc, false);
    }
    else {
      final AbstractGemFireDistributionActivation distActivation;
      if (qi.isSelect()) {
        distActivation = new GemFireSelectDistributionActivation(st, _lcc, qi);
      }
      else if (qi.isUpdate()) {
        distActivation = new GemFireUpdateDistributionActivation(st, _lcc, qi);
      }
      else if (qi.isDelete()) {
        distActivation = new GemFireDeleteDistributionActivation(st, _lcc, qi);
      }
      else if (qi.isInsertAsSubSelect()) {
        SelectQueryInfo si = ((InsertQueryInfo)qi).getSubSelectQueryInfo();
        distActivation = new GemFireSelectDistributionActivation(st, _lcc, si);
      }
      else {
        throw new IllegalStateException("QueryInfo type not supported for "
            + qi);
      }
      actvn = distActivation;
    }
    actvn.initFromContext(_lcc, addToLCC, st);
    return actvn;
  }
}
