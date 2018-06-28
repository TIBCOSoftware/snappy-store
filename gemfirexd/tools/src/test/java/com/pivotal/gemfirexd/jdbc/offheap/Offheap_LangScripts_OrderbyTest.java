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
package com.pivotal.gemfirexd.jdbc.offheap;

import java.lang.reflect.Field;
import java.sql.SQLWarning;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXState;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRegionEntryUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TargetResultSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.SortResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils.Context;
import com.pivotal.gemfirexd.jdbc.LangScripts_OrderbyTest;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class Offheap_LangScripts_OrderbyTest extends LangScripts_OrderbyTest {
  public static void main(String[] args) {
    TestRunner.run(new TestSuite(Offheap_LangScripts_OrderbyTest.class));
  }
  
  
  public Offheap_LangScripts_OrderbyTest(String name) {
    super(name); 
  }
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"true");
  }
  
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
  }
  
  @Override
  public void testLangScript_OrderbyTestNoPartitioning() throws Exception {
    final Map<Long,byte[]> addressAndBytes = new HashMap<Long, byte[]>();
    final Set<LocalRegion> listOfRegions = new HashSet<LocalRegion>();
    GemFireXDQueryObserverHolder
    .setInstance(new GemFireXDQueryObserverAdapter() {
      @Override
      public void  beforeEmbedResultSetClose(EmbedResultSet rs, String query) {
        for(LocalRegion lr:listOfRegions) {
          if(lr.getEnableOffHeapMemory()) {
            
           Collection<RegionEntry> entries =lr.getRegionMap().regionEntries();
           for(RegionEntry re:entries) {
             OffHeapRegionEntry ohre = (OffHeapRegionEntry)re;
             long address = ohre.getAddress();
             if(addressAndBytes.containsKey(address)) {
               // we need to replace the address with the new bytes
               byte[] bytes = addressAndBytes.get(address);
               Object o = OffHeapRegionEntryUtils.prepareValueForCreate(null, bytes, false);
               if(o instanceof OffHeapByteSource) {
                 ohre.setAddress(address,((OffHeapByteSource)o).getMemoryAddress());
               }
             }
           }
          }
        }
        addressAndBytes.clear();
        listOfRegions.clear();        
      }
      
      @Override
      public void onSortResultSetOpen(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
        try {
          SortResultSet srs = (SortResultSet) resultSet;
          NoPutResultSet source = srs.getSource();
          source = conditionSourceResultSet(source, addressAndBytes);
          Class<?> clazz = SortResultSet.class;
          Field field = clazz.getDeclaredField("source");
          field.setAccessible(true);
          field.set(srs, source);
          
          GemFireCacheImpl gc = Misc.getGemFireCacheNoThrow();
          Set<LocalRegion> appRegions = gc.getApplicationRegions();
          listOfRegions.addAll(appRegions);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }

      }
    });
    super.testLangScript_OrderbyTestNoPartitioning();
  }
  
  @Override
  public void testLangScript_OrderbyWithPartitioning() throws Exception {
    final Map<Long,byte[]> addressAndBytes = new HashMap<Long, byte[]>();
    final Set<LocalRegion> listOfRegions = new HashSet<LocalRegion>();
    GemFireXDQueryObserverHolder
    .setInstance(new GemFireXDQueryObserverAdapter() {
      @Override
      public void  beforeEmbedResultSetClose(EmbedResultSet rs, String query) {
        for(LocalRegion lr:listOfRegions) {
          if(lr.getEnableOffHeapMemory()) {
            if(lr instanceof PartitionedRegion) {
              PartitionedRegion pr = (PartitionedRegion)lr;
              PartitionedRegionDataStore prs = pr.getDataStore();
              if (prs != null) {
                Set<BucketRegion> brs = prs.getAllLocalBucketRegions();
                if (brs != null) {
                  for (BucketRegion br : brs) {
                    if (br != null) {
                      this.checkEntries(br);
                    }
                  }
                }
              }  
            }else {
              checkEntries(lr);
            }
          }
        }
        addressAndBytes.clear();
        listOfRegions.clear();
        
      }
      private void checkEntries(LocalRegion lr) {
        Collection<RegionEntry> entries =lr.getRegionMap().regionEntries();
        for(RegionEntry re:entries) {
          OffHeapRegionEntry ohre = (OffHeapRegionEntry)re;
          long address = ohre.getAddress();
          if(addressAndBytes.containsKey(address)) {
            // we need to replace the address with the new bytes
            byte[] bytes = addressAndBytes.get(address);
            Object o = OffHeapRegionEntryUtils.prepareValueForCreate(null, bytes, false);
            if(o instanceof OffHeapByteSource) {
              ohre.setAddress(address,((OffHeapByteSource)o).getMemoryAddress());
            }
          }
        }
       
      }
      @Override
      public void onSortResultSetOpen(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
        try {
          SortResultSet srs = (SortResultSet) resultSet;
          NoPutResultSet source = srs.getSource();
          source = conditionSourceResultSet(source, addressAndBytes);
          Class<?> clazz = SortResultSet.class;
          Field field = clazz.getDeclaredField("source");
          field.setAccessible(true);
          field.set(srs, source);
          
          GemFireCacheImpl gc = Misc.getGemFireCacheNoThrow();
          Set<LocalRegion> appRegions = gc.getApplicationRegions();
          listOfRegions.addAll(appRegions);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }

      }
    });
    super.testLangScript_OrderbyWithPartitioning();
  }
  
  private NoPutResultSet conditionSourceResultSet(
      final NoPutResultSet noputResultSet, final Map<Long, byte[]> addressAndBytes) {

    NoPutResultSet testResultSetPassThru = new NoPutResultSet() {

      @Override
      public void deleteRowDirectly() throws StandardException {
        noputResultSet.deleteRowDirectly();
      }

      @Override
      public boolean canUpdateInPlace() {
        return noputResultSet.canUpdateInPlace();
      }

      @Override
      public boolean needsToClone() {
        return noputResultSet.needsToClone();
      }

      @Override
      public FormatableBitSet getValidColumns() {
        return noputResultSet.getValidColumns();
      }

      @Override
      public ExecRow getNextRowFromRowSource() throws StandardException {
        return noputResultSet.getNextRowFromRowSource();
      }

      @Override
      public void closeRowSource() {
        noputResultSet.closeRowSource();

      }

      @Override
      public void rowLocation(RowLocation rl) throws StandardException {
        noputResultSet.rowLocation(rl);

      }

      @Override
      public boolean needsRowLocation() {
        return noputResultSet.needsRowLocation();
      }

      @Override
      public ExecRow setBeforeFirstRow() throws StandardException {
        return noputResultSet.setBeforeFirstRow();
      }

      @Override
      public ExecRow setAfterLastRow() throws StandardException {
        return noputResultSet.setAfterLastRow();
      }

      @Override
      public boolean returnsRows() {
        return noputResultSet.returnsRows();
      }

      @Override
      public void resetStatistics() {
        noputResultSet.resetStatistics();
      }

      @Override
      public boolean releaseLocks(GemFireTransaction tran) {
        return noputResultSet.releaseLocks(tran);
      }

      @Override
      public void open() throws StandardException {
        noputResultSet.open();
      }

      @Override
      public int modifiedRowCount() {
        // TODO Auto-generated method stub
        return noputResultSet.modifiedRowCount();
      }

      @Override
      public void markLocallyExecuted() {
        noputResultSet.markLocallyExecuted();

      }

      @Override
      public boolean isDistributedResultSet() {
        return noputResultSet.isDistributedResultSet();

      }

      @Override
      public boolean isClosed() {
        return noputResultSet.isClosed();
      }

      @Override
      public boolean hasAutoGeneratedKeysResultSet() {
        return noputResultSet.hasAutoGeneratedKeysResultSet();
      }

      @Override
      public SQLWarning getWarnings() {
        return noputResultSet.getWarnings();
      }

      @Override
      public long getTimeSpent(int type, int timeType) {
        return noputResultSet.getTimeSpent(type, timeType);
      }

      @Override
      public NoPutResultSet[] getSubqueryTrackingArray(int numSubqueries) {
        return noputResultSet.getSubqueryTrackingArray(numSubqueries);
      }

      @Override
      public int getRowNumber() {
        // TODO Auto-generated method stub
        return noputResultSet.getRowNumber();
      }

      @Override
      public ExecRow getRelativeRow(int row) throws StandardException {
        return noputResultSet.getRelativeRow(row);
      }

      @Override
      public ExecRow getPreviousRow() throws StandardException {
        return noputResultSet.getPreviousRow();
      }

      @Override
      public ExecRow getNextRow() throws StandardException {
        return noputResultSet.getNextRow();
      }

      @Override
      public ExecRow getLastRow() throws StandardException {
        return noputResultSet.getLastRow();
      }

      @Override
      public ExecRow getFirstRow() throws StandardException {
        return noputResultSet.getFirstRow();
      }

      @Override
      public com.pivotal.gemfirexd.internal.catalog.UUID getExecutionPlanID() {
        return noputResultSet.getExecutionPlanID();
      }

      @Override
      public long getExecuteTime() {
        return noputResultSet.getExecuteTime();
      }

      @Override
      public Timestamp getEndExecutionTimestamp() {
        // TODO Auto-generated method stub
        return noputResultSet.getEndExecutionTimestamp();
      }

      @Override
      public String getCursorName() {
        // TODO Auto-generated method stub
        return noputResultSet.getCursorName();
      }

      @Override
      public Timestamp getBeginExecutionTimestamp() {
        // TODO Auto-generated method stub
        return noputResultSet.getBeginExecutionTimestamp();
      }

      @Override
      public com.pivotal.gemfirexd.internal.iapi.sql.ResultSet getAutoGeneratedKeysResultset() {
        // TODO Auto-generated method stub
        return noputResultSet.getAutoGeneratedKeysResultset();
      }

      @Override
      public Activation getActivation() {
        // TODO Auto-generated method stub
        return noputResultSet.getActivation();
      }

      @Override
      public ExecRow getAbsoluteRow(int row) throws StandardException {
        // TODO Auto-generated method stub
        return noputResultSet.getAbsoluteRow(row);
      }

      @Override
      public void flushBatch() throws StandardException {
        // TODO Auto-generated method stub
        noputResultSet.flushBatch();

      }

      @Override
      public void finish() throws StandardException {
        // TODO Auto-generated method stub
        noputResultSet.finish();
      }

      @Override
      public void closeBatch() throws StandardException {
        noputResultSet.closeBatch();

      }

      @Override
      public void close(boolean cleanupOnError) throws StandardException {
        noputResultSet.close(cleanupOnError);
      }

      @Override
      public void clearCurrentRow() {
        noputResultSet.clearCurrentRow();
      }

      @Override
      public void cleanUp(boolean cleanupOnError) throws StandardException {
        noputResultSet.cleanUp(cleanupOnError);
      }

      @Override
      public boolean checkRowPosition(int isType) throws StandardException {
        return noputResultSet.checkRowPosition(isType);
      }

      @Override
      public void checkCancellationFlag() throws StandardException {
        noputResultSet.checkCancellationFlag();
      }

      @Override
      public boolean addLockReference(GemFireTransaction tran) {
        // TODO Auto-generated method stub
        return noputResultSet.addLockReference(tran);
      }

      @Override
      public void accept(ResultSetStatisticsVisitor visitor) {
        noputResultSet.accept(visitor);

      }

      @Override
      public void upgradeReadLockToWrite(RowLocation rl,
          GemFireContainer container) throws StandardException {
        noputResultSet.upgradeReadLockToWrite(rl, container);

      }

      @Override
      public void updateRowLocationPostRead() throws StandardException {
        noputResultSet.updateRowLocationPostRead();
      }

      @Override
      public void updateRow(ExecRow row) throws StandardException {
        noputResultSet.updateRow(row);
      }

      @Override
      public boolean supportsMoveToNextKey() {
        return noputResultSet.supportsMoveToNextKey();
      }

      @Override
      public void setTargetResultSet(TargetResultSet trs) {
        noputResultSet.setTargetResultSet(trs);
      }

      @Override
      public void setNeedsRowLocation(boolean needsRowLocation) {
        noputResultSet.setNeedsRowLocation(needsRowLocation);
      }

      @Override
      public void setGfKeysForNCJoin(ArrayList<DataValueDescriptor> keys)
          throws StandardException {
        noputResultSet.setGfKeysForNCJoin(keys);

      }

      @Override
      public void setCurrentRow(ExecRow row) {
        noputResultSet.setCurrentRow(row);
      }

      @Override
      public int resultSetNumber() {
        // TODO Auto-generated method stub
        return noputResultSet.resultSetNumber();
      }

      @Override
      public boolean requiresRelocking() {
        // TODO Auto-generated method stub
        return noputResultSet.requiresRelocking();
      }

      @Override
      public void reopenCore() throws StandardException {
        noputResultSet.reopenCore();
      }

      @Override
      public void releasePreviousByteSource() {
        noputResultSet.releasePreviousByteSource();
      }

      @Override
      public void setMaxSortingLimit(long limit) {
        noputResultSet.setMaxSortingLimit(limit);
      }

      @Override
      public void positionScanAtRowLocation(RowLocation rLoc)
          throws StandardException {
        noputResultSet.positionScanAtRowLocation(rLoc);

      }

      @Override
      public void openCore() throws StandardException {
        noputResultSet.openCore();

      }

      @Override
      public void markRowAsDeleted() throws StandardException {
        noputResultSet.markRowAsDeleted();
      }

      @Override
      public void markAsTopResultSet() {
        noputResultSet.markAsTopResultSet();
      }

      @Override
      public boolean isForUpdate() {
        // TODO Auto-generated method stub
        return noputResultSet.isForUpdate();
      }

      @Override
      public TXState initLocalTXState() {
        // TODO Auto-generated method stub
        return noputResultSet.initLocalTXState();
      }

      @Override
      public int getScanKeyGroupID() {
        // TODO Auto-generated method stub
        return noputResultSet.getScanKeyGroupID();
      }

      @Override
      public int getScanIsolationLevel() {
        // TODO Auto-generated method stub
        return noputResultSet.getScanIsolationLevel();
      }

      @Override
      public int getPointOfAttachment() {
        // TODO Auto-generated method stub
        return noputResultSet.getPointOfAttachment();
      }

      private List<RowLocation> rowsScanned = new ArrayList<RowLocation>();

      @Override
      public ExecRow getNextRowCore() throws StandardException {

        ExecRow row = noputResultSet.getNextRowCore();
        if (row != null) {
          if (row instanceof AbstractCompactExecRow) {
            Object bs = ((AbstractCompactExecRow) row).getByteSource();            
            if (bs instanceof OffHeapByteSource) {
              OffHeapByteSource obs = (OffHeapByteSource)bs;
              byte[] bytes = obs.getRowBytes();
              addressAndBytes.put(obs.getMemoryAddress(), bytes);
              ((OffHeapByteSource) bs).release();
            }
          }
        }
        return row;
      }

      @Override
      public Context getNewPlanContext() {
        // TODO Auto-generated method stub
        return noputResultSet.getNewPlanContext();
      }

      @Override
      public double getEstimatedRowCount() {
        // TODO Auto-generated method stub
        return noputResultSet.getEstimatedRowCount();
      }

      @Override
      public void filteredRowLocationPostRead(TXState localTXState)
          throws StandardException {
        noputResultSet.filteredRowLocationPostRead(localTXState);

      }

      @Override
      public RowLocation fetch(RowLocation loc, ExecRow destRow,
          FormatableBitSet validColumns, boolean faultIn,
          GemFireContainer container) throws StandardException {
        // TODO Auto-generated method stub
        return noputResultSet.fetch(loc, destRow, validColumns, faultIn,
            container);
      }

      @Override
      public StringBuilder buildQueryPlan(StringBuilder builder, Context context) {
        // TODO Auto-generated method stub
        return noputResultSet.buildQueryPlan(builder, context);
      }
      
      @Override
      public void forceReOpenCore() throws StandardException { 
        noputResultSet.forceReOpenCore();
      }
    };
    return testResultSetPassThru;
  }
  
  @Override
  protected String getOffHeapSuffix() {
    return " offheap ";
  }
}
