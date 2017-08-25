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

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.io.IOException;

import com.gemstone.gemfire.cache.query.internal.IndexUpdater;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemOperation;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraIndexInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.LimitObjectInput;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Compensation;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogInstant;

/**
 * This class enables us to delay the index conglomerate refresh in
 * GfxdIndexManager till the commit time. Also helps to avoid refreshing during
 * a DML operation, since that should really take a DD read lock which is not
 * possible. See bugs #40707, #39808 which are likely due to inability to take
 * DD read lock during refresh.
 * 
 * @author swale
 */
public final class MemIndexRefresh extends MemOperation {

  private final GfxdIndexManager indexManager;

  private boolean lockGIIDone;

  private final boolean unlockForIndexGII;

  private int dropColumnPos;

  /**
   * Creates a new instance of {@link MemIndexRefresh}.
   * 
   * @param indexManager
   *          the {@link GfxdIndexManager} of the indexes to refresh
   * @param unlockForIndexGII
   *          if set to true then {@link IndexUpdater#unlockForIndexGII()}
   *          during execution at commit
   * @param forceRefresh
   *          true if refresh has to be forced at commit else it can be
   *          conflated with other {@link MemIndexRefresh}es; currently this
   *          behaviour is not used or implemented
   */
  MemIndexRefresh(GfxdIndexManager indexManager, boolean unlockForIndexGII,
      boolean forceRefresh) {
    super(indexManager.getContainer());
    this.indexManager = indexManager;
    this.lockGIIDone = false;
    this.unlockForIndexGII = unlockForIndexGII;
  }

  void setDropColumnPosition(int pos) {
    this.dropColumnPos = pos;
  }

  @Override
  public void doMe(Transaction xact, LogInstant instant, LimitObjectInput in)
      throws StandardException, IOException {
    final GemFireTransaction tc = (GemFireTransaction)xact;
    try {
      // ignore during abort if no LCC in current context
      if (Misc.getLanguageConnectionContext() != null) {
        this.indexManager.refreshIndexListAndConstriantDesc(!this.lockGIIDone,
            false, tc);
        if (this.dropColumnPos > 0) {
          // update the column positions for indexes (#47156)
          for (GemFireContainer index : this.indexManager.getAllIndexes()) {
            GemFireXDUtils.dropColumnAdjustColumnPositions(
                index.getBaseColumnPositions(), this.dropColumnPos);
            final ExtraIndexInfo indexInfo = index.getExtraIndexInfo();
            if (indexInfo != null) {
              indexInfo.dropColumnForPrimaryKeyFormatter(this.dropColumnPos);
            }
          }
        }
      }
    } finally {
      try {
        if (this.lockGIIDone) {
          this.indexManager.unlockForGII(true, tc);
        }
      } finally {
        if (this.unlockForIndexGII) {
          this.indexManager.unlockForIndexGII(true, tc);
        }
      }
    }
  }

  void lockGII(GemFireTransaction tc) {
    if (!this.lockGIIDone) {
      this.lockGIIDone = this.indexManager.lockForGII(true, tc);
    }
  }

  @Override
  public boolean doAtCommitOrAbort() {
    return true;
  }

  @Override
  public Compensation generateUndo(Transaction xact, LimitObjectInput in)
      throws StandardException, IOException {
    // don't adjust for drop column for abort
    this.dropColumnPos = 0;
    return null;
  }
}
