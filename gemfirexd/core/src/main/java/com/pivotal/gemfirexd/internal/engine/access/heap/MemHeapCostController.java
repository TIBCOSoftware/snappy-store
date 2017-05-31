
/*

 Derived from source files from the Derby project.

 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to you under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

/*
 This file was based on the MemStore patch written by Knut Magne, published
 under the Derby issue DERBY-2798 and released under the same license,
 ASF, as described above. The MemStore patch was in turn based on Derby source
 files.
*/
package com.pivotal.gemfirexd.internal.engine.access.heap;

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CostEstimate;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ReferencedKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.StoreCostController;
import com.pivotal.gemfirexd.internal.iapi.store.access.StoreCostResult;
import com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

/**
 * DOCUMENT ME!
 *
 * @author  $author$
 */
public final class MemHeapCostController extends MemHeapScanController
    implements StoreCostController {

  /** Only lookup these estimates from raw store once. */
  long row_size;

  /**
   * Initialize the cost controller.
   *
   * <p>Let super.init() do it's work and then get the initial stats about the
   * table from raw store.
   *
   * @exception  StandardException  Standard exception policy.
   */
  @Override
  public void init(GemFireTransaction tran, MemConglomerate conglomerate,
      int openMode, int lockLevel, LockingPolicy locking)
      throws StandardException {
    super.init(tran, conglomerate, openMode, lockLevel, locking);
    
    boolean queryHDFS = false;
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc != null) {
      queryHDFS = lcc.getQueryHDFS();
      CompilerContext cc = (CompilerContext) (lcc.getContextManager().getContext(CompilerContext.CONTEXT_ID));
      final LocalRegion region = this.gfContainer.getRegion();
      if (region != null && region instanceof PartitionedRegion) {
        if (cc.getHasQueryHDFS()) {
          ((PartitionedRegion) region).setQueryHDFS(cc.getQueryHDFS());
        }
        else {
          ((PartitionedRegion) region).setQueryHDFS(queryHDFS);
        }
      }
    }
    this.numRows = this.gfContainer.getNumRows();
    this.row_size = this.gfContainer.getRowSize();
    if (this.numRows < 1) {
      this.numRows = 1;
    }
    if (this.row_size < 1) {
      this.row_size = 1;
    }
  }

  @Override
  public double getFetchFromRowLocationCost(FormatableBitSet validColumns,
                                            int access_type)
  throws StandardException {
   /* double ret_cost;
    ret_cost = row_size;

    return (ret_cost);*/
    return (this.row_size * BASE_ROW_PER_BYTECOST) + BASE_CACHED_ROW_FETCH_COST;
  }

  /**
   * Calculates the cost of a scan.
   */
  @Override
  public void getScanCost(int scan_type,
                          long row_count,
                          int group_size,
                          boolean forUpdate,
                          FormatableBitSet scanColumnList,
                          DataValueDescriptor[] template,
                          DataValueDescriptor[] startKeyValue,
                          int startSearchOperator,
                          DataValueDescriptor[] stopKeyValue,
                          int stopSearchOperator,
                          boolean reopen_scan,
                          int access_type,
                          
                          StoreCostResult cost_result)
  throws StandardException {

    if (SanityManager.DEBUG) {
      SanityManager.ASSERT((scan_type ==
                            StoreCostController.STORECOST_SCAN_NORMAL) ||
                           (scan_type ==
                            StoreCostController.STORECOST_SCAN_SET) ||
                           (scan_type ==
                            StoreCostController.STORECOST_REMOTE_FETCH_ROWS));
    }

    long estimated_row_count = ((row_count < 0) ? this.numRows : row_count);
    double cost = (estimated_row_count * this.row_size) * BASE_ROW_PER_BYTECOST;

    if (scan_type == StoreCostController.STORECOST_SCAN_SET) {
      cost += (estimated_row_count * BASE_HASHSCAN_ROW_FETCH_COST);
    }
    else if (scan_type == StoreCostController.STORECOST_REMOTE_FETCH_ROWS) {

      double remote_cost_multiplier = 0;
      if (scanColumnList != null) {
        // && scanColumnList is GetAll convertible
        final TableDescriptor td = gfContainer.getTableDescriptor();
        assert td != null: "table descriptor is found null for " + gfContainer;
        final ReferencedKeyConstraintDescriptor pk = td.getPrimaryKey();
        if (pk != null) {
          final int[] scanCols = new int[scanColumnList.getNumBitsSet()];
          for (int pos = scanColumnList.anySetBit(), j = 0; pos != -1; pos = scanColumnList
              .anySetBit(pos), ++j) {
            scanCols[j] = pos;
          }
          final boolean isPrimaryKeyGetAll = pk.columnIntersects(scanCols);

          if (isPrimaryKeyGetAll) {
            remote_cost_multiplier += SINGLE_REMOTE_TABLE_GETALL_COST;
            final GfxdPartitionResolver resolver = td
                .getGfxdPartitionResolver(null);
            assert resolver != null: "Remote fetch shouldn't happen for non-partitioned table"
                + td;
            if (resolver.requiresGlobalIndex()) {
              remote_cost_multiplier += SINGLE_GLOBAL_INDEX_LOOKUP_COST;
            }
          }
        }
      } // scanColumnList;

      // consider a remote table scan because of above conditions not satisfied.
      if (remote_cost_multiplier == 0) {
        remote_cost_multiplier = SINGLE_REMOTE_TABLE_SCAN_COST;
      }

      cost += (estimated_row_count * remote_cost_multiplier);
    } // STORECOST_REMOTE_FETCH_ROWS
    else {
      cost += (estimated_row_count * BASE_GROUPSCAN_ROW_COST);
    }
    GemFireXDQueryObserver sqo = GemFireXDQueryObserverHolder.getInstance();
    if (sqo != null) {
      cost = sqo.overrideDerbyOptimizerCostForMemHeapScan(this.gfContainer,
          cost);
    }
    cost_result.setEstimatedCost(cost);
    cost_result.setEstimatedRowCount(estimated_row_count + 1);
  }

  @Override
  public double getFetchFromFullKeyCost(FormatableBitSet validColumns,
                                        int access_type, CostEstimate costEstimate)
  throws StandardException {
    //return row_size * num_rows;
    //this should be support?
    //return (double)this.row_size;
    throw new UnsupportedOperationException("unexpected invocation for "
        + toString());
  }
}
