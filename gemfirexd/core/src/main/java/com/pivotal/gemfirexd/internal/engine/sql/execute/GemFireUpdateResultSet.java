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

import java.util.Collection;
import java.util.Iterator;

import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.TriggerEvent;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

public class GemFireUpdateResultSet extends AbstractGemFireResultSet {

  protected final GemFireContainer gfContainer;
  
  private GemFireContainer targetTableContainer;

  protected int numRowsModified;

  private final String targetTableName;

  GemFireUpdateResultSet(AbstractGemFireActivation act)
      throws StandardException {
    this(act, null);
  }

  GemFireUpdateResultSet(AbstractGemFireActivation act, String targetTableName)
      throws StandardException {
    super(act);
    this.gfContainer = (GemFireContainer)act.qInfo.getRegion()
        .getUserAttribute();
    this.numRowsModified = 0;
    this.targetTableName = targetTableName;
  }

  @Override
  public void setup(Object results, int numMembers) throws StandardException {
    final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1) : 0;
     //TODO:Asif: Clean it .In the MultipleInsertsLevaraging .. apparently the 
    if(this.activation.getInsertAsSubselect()) {
        int numUpdates = 0;
        if (results != null) {
          Iterator<?> itr = ((Collection<?>)results).iterator();
          while (itr.hasNext()) {
            Object res = itr.next();
            if (res != null) {
              numUpdates += ((Integer)res).intValue();
            }
          }
        }
      this.numRowsModified = numUpdates;
    }else {
      this.numRowsModified = results != null ? ((Integer)results).intValue() : 0;
    }

    
    if (statisticsTimingOn) {
      nextTime = XPLAINUtil.recordTiming(beginTime);
    }
  }

  @Override
  public final void setNumRowsModified(int numRowsModified) {
    this.numRowsModified = numRowsModified;
  }

  @Override
  protected final void openCore() throws StandardException {
    TXStateInterface tx = this.gfContainer.getActiveTXState(this.tran);
    if (tx == null && (this.gfContainer.isRowBuffer() || Misc.getGemFireCache().snapshotEnabledForTest())) {
      // TOOD: decide on autocommit or this flag: Discuss
      this.tran.getTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
      ((GemFireTransaction)lcc.getTransactionExecute()).setActiveTXState(TXManagerImpl.getCurrentTXState(), false);
      this.tran.setImplicitSnapshotTxStarted(true);
    }
    openContainers();
    final LocalRegion reg = this.gfContainer.getRegion();
    final GfxdIndexManager sqim = (GfxdIndexManager)(reg != null ? reg
        .getIndexUpdater() : null);
    if (sqim != null) {
      final LanguageConnectionContext lcc = this.activation
          .getLanguageConnectionContext();
      final GemFireTransaction tran = (GemFireTransaction)lcc
          .getTransactionExecute();
      sqim.fireStatementTriggers(TriggerEvent.BEFORE_UPDATE, lcc, tran,
          this.gfContainer.getActiveTXState(tran));
    }
  }

  /**
   * Take lock on target table being inserted into for inserts as sub-selects.
   */
  private void takeLockOnTargetTable() throws StandardException {
    if (this.targetTableName != null) {
      if (this.targetTableContainer == null) {
        final Region<?, ?> targetRegion = Misc
            .getRegionForTable(this.targetTableName, false);
        if (targetRegion == null) {
          throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND,
              this.targetTableName);
        }
        this.targetTableContainer = (GemFireContainer)targetRegion
            .getUserAttribute();
      }
      if (this.targetTableContainer != null) {
        this.targetTableContainer
            .open(this.tran, ContainerHandle.MODE_READONLY);
      }
    }
  }

  protected void openContainers() throws StandardException {
    this.gfContainer.open(this.tran, ContainerHandle.MODE_READONLY);
    openOrCloseFKContainers(this.gfContainer, this.tran, false, true);
    // Take a read lock on the target table container here. (for inserts as
    // sub-selects)
    takeLockOnTargetTable();
  }

  @Override
  public void finishResultSet(final boolean cleanupOnError) throws StandardException {
    openOrCloseFKContainers(this.gfContainer, this.tran, true, true);
    this.gfContainer.closeForEndTransaction(this.tran, true);
    if (this.targetTableContainer != null) {
      this.targetTableContainer.closeForEndTransaction(this.tran, true);
    }
  }

  @Override
  public final void close(boolean cleanupOnError) throws StandardException {
    if (this.isClosed) {
      return;
    }
    // temporarily store the beginTime and let super.close() handle the ending.
    closeTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1) : 0;
    
    final LocalRegion reg = this.gfContainer.getRegion();
    final GfxdIndexManager sqim = (GfxdIndexManager)(reg != null ? reg
        .getIndexUpdater() : null);
    if (sqim != null) {
      final LanguageConnectionContext lcc = this.activation
          .getLanguageConnectionContext();
      final GemFireTransaction tran = (GemFireTransaction)lcc
          .getTransactionExecute();
      sqim.fireStatementTriggers(getAfterTriggerEvent(), lcc, tran,
          this.gfContainer.getActiveTXState(this.tran));
    }
    super.close(cleanupOnError); // if this isn't the last line, fix closeTime appropriately. 
  }

  @Override
  public final int modifiedRowCount() {
    return this.numRowsModified;
  }

  @Override
  public final boolean returnsRows() {
    return false;
  }
  
  int getAfterTriggerEvent() {
    return TriggerEvent.AFTER_UPDATE;
  }

  @Override
  public final void accept(ResultSetStatisticsVisitor visitor) {
    visitor.setNumberOfChildren(0);
    visitor.visit(this);
  }

  @Override
  public long estimateMemoryUsage() throws StandardException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public final void flushBatch() throws StandardException {
    if (this.activation instanceof GemFireUpdateActivation) {
      ((GemFireUpdateActivation)this.activation).flushBatch();
      this.checkCancellationFlag();
    }
  }

  public boolean hasAutoGeneratedKeysResultSet() {
    return false;
  }
}
