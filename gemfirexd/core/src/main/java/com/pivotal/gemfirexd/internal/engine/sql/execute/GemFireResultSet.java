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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nonnull;

import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXManagerImpl.TXContext;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import io.snappydata.collection.OpenHashSet;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalRowLocation;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader.GetRowFunction;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader.GetRowFunctionArgs;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector.ListResultCollectorValue;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdSingleResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GetAllExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GetAllLocalIndexExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GetExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.ProjectionRow;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionMultiKeyExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DynamicKey;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.compile.types.DVDSet;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TargetResultSet;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.RowUtil;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.collection.ObjectObjectHashMap;

/**
 * @author soubhikc
 * 
 * 
 */
public final class GemFireResultSet extends AbstractGemFireResultSet implements
    NoPutResultSet {

  //private final AbstractGemFireActivation act;

  private final SelectQueryInfo selectQI;

  private final GemFireContainer gfContainer;

  private final boolean isOffHeap;

  private final GemFireContainer gfIndexContainer;

  private final StatementStats stats;

  private ExecRow candidate;

  /**
   * The {@link RowFormatter} for the current projection. Note that this
   * represents the formatter for the current table schema only. If the target
   * row is of a different version then this formatter cannot be used.
   */
  private final RowFormatter projectionFormat;
  private final int[] projectionFixedColumns;
  private final int[] projectionVarColumns;
  private final int[] projectionLobColumns;
  private final int[] projectionAllColumnsWithLobs;

  //private final FormatableBitSet accessedCols;

  private final int resultsetNumber;

  private final int isolationLevel;

  private final boolean forUpdate;
  private DataValueDescriptor[] cachedChangedRow;

  //private int[] baseColumnMap;

  private Object[] gfKeys;
  private Object currentRoutingObject;

  /* 
   * Map Keys -> Routing Objects
   * Store keys and routingObjects used in getAll for Global Index Case.
   */
  private ObjectObjectHashMap<Object, Object> getAllKeysAndRoutingObjects;

  private int currPos;

  private boolean isTopResultSet;

  private boolean doGetAll;
  
  private boolean doGetAllOnLocalIndex;

  private boolean getAllDone;
  
  private boolean getAllOnGlobalIndexDone;

  private Iterator<?> getAllItr = null;
  
  private List<?> getAllResults = null;

  private Iterator<?> getAllResultsItr = null;
  
  private Iterator<?> getAllKeysItr = null;
  
  private DistributedMember currentMemberForGetAll;
  
  private RegionMultiKeyExecutorMessage getAllMsg = null;
  
  private final boolean isTheRegionReplicate;
  
  private final boolean isGlobalIndexCase;
  
  private boolean queryHDFS = false;
  
  // statistics data
  // ----------------

  public transient int rowsReturned;

  // end of statistics data
  // -----------------------
  
  /**
   * @param activation
   * @param stats
   */
  public GemFireResultSet(Activation activation,
      GeneratedMethod resultRowAllocator, int resultSetNumber,
      int formatterItem, int fixedColsItem, int varColsItem, int lobColsItem,
      int allColsItem, int allColsWithLobsItem, int isolationLevel,
      boolean forUpdate, SelectQueryInfo selectQI, StatementStats stats,
      boolean isGFEActivation, boolean queryHDFS) throws StandardException {

    super(activation);
    RowFormatter projectionFormat;
    //this.selectQI = selectQI;
    if (isGFEActivation) {
      //this.act = (AbstractGemFireActivation)activation;
      projectionFormat = selectQI.getRowFormatter();
      this.projectionFixedColumns = selectQI.getProjectionFixedColumns();
      this.projectionVarColumns = selectQI.getProjectionVarColumns();
      this.projectionLobColumns = selectQI.getProjectionLobColumns();
      //this.projectionAllColumns = selectQI.getProjectionAllColumns();
      this.projectionAllColumnsWithLobs = selectQI
          .getProjectionAllColumnsWithLobs();
      //this.candidate = null;
      //this.accessedCols = null;
    }
    else {
      //this.act = null;
      final ExecPreparedStatement ps = activation.getPreparedStatement();
      if (formatterItem != -1) {
        projectionFormat = (RowFormatter)activation.getSavedObject(formatterItem);
        if (fixedColsItem != -1) {
          this.projectionFixedColumns = (int[])activation.getSavedObject(fixedColsItem);
        }
        else {
          this.projectionFixedColumns = null;
        }
        if (varColsItem != -1) {
          this.projectionVarColumns = (int[])activation.getSavedObject(varColsItem);
        }
        else {
          this.projectionVarColumns = null;
        }
        if (lobColsItem != -1) {
          this.projectionLobColumns = (int[])activation.getSavedObject(lobColsItem);
        }
        else {
          this.projectionLobColumns = null;
        }
        /*
        if (allColsItem != -1) {
          this.projectionAllColumns = (int[])ps.getSavedObject(allColsItem);
        }
        else {
          this.projectionAllColumns = null;
        }
        */
        this.projectionAllColumnsWithLobs = (int[])activation.getSavedObject(allColsWithLobsItem);
      }
      else {
        projectionFormat = null;
        this.projectionFixedColumns = null;
        this.projectionVarColumns = null;
        this.projectionLobColumns = null;
        //this.projectionAllColumns = null;
        this.projectionAllColumnsWithLobs = null;
      }
      /* Only call row allocators once */
      //this.candidate = (ExecRow)resultRowAllocator.invoke(activation);

      //this.accessedCols = colRefItem != -1 ? (FormatableBitSet)(activation
      //    .getPreparedStatement().getSavedObject(colRefItem)) : null;
    }
    this.resultsetNumber = resultSetNumber;
    this.isolationLevel = isolationLevel;
    this.forUpdate = forUpdate;

    final LocalRegion region = selectQI.getRegion();
    this.gfContainer = (GemFireContainer)region.getUserAttribute();
    if (this.gfContainer != null) {
      if (projectionFormat == null) {
        projectionFormat = this.gfContainer.getCurrentRowFormatter();
      }
      this.isOffHeap = this.gfContainer.isOffHeap();
    }
    else {
      this.isOffHeap = false;
    }
    this.projectionFormat = projectionFormat;
    this.stats = stats;
    this.isGlobalIndexCase = GemFireXDUtils.isGlobalIndexCase(region);
    final DataPolicy policy = region.getDataPolicy();
    this.isTheRegionReplicate = policy.withReplication()
        || !policy.withStorage();
    this.selectQI = selectQI;
    // Set IndexContainer Now
    Object pk = selectQI.getPrimaryKey();
    Object fk = selectQI.getLocalIndexKey();
    assert pk != null ^ fk != null;
    if (pk != null) {
      // TODO:Asif: We need to find out a cleaner
      // way to detect bulk get / bulk put conditions
      if (pk instanceof Object[]) {
        /* Note:
         * Mark for GetAll if multiple keys and table is partitioned.
         * If query with replicated tables is making GemFireResultSet,
         * it will be executed using Get in loop @see fetchNext
         */
        if (policy.withPartitioning()) {
          this.doGetAll = true;
          this.getAllKeysAndRoutingObjects = ObjectObjectHashMap.withExpectedSize(8);
          if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
            SanityManager
                .DEBUG_PRINT(
                    GfxdConstants.TRACE_QUERYDISTRIB,
                    "GemFireResultSet: PK based Query would be executed using GetAll. isGlobalIndexCase = "
                        + this.isGlobalIndexCase);
          }
          if (this.isGlobalIndexCase) {
            /* Note:
             * @see com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo.isWhereClauseSatisfactory(TableQueryInfo)
             * returns CompactCompositeRegionKey for all cases, but in case of Global Index, 
             * incoming keys should be dvds. This assertion help in casting @see GemFireResultSet.executeGetAllOnGlobalIndex
             */
            assert !selectQI.isPrimaryKeyComposite();
          }
        }
        else {
          if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "GemFireResultSet: PK based Query would Not be executed using GetAll, "
                    + "but Get would be called in loop since table "
                    + "is not partitioned");
          }
        }
      }
      this.gfIndexContainer = null;
    }
    else if (fk != null) {
      // Handle Local Index Case
      assert selectQI.getChosenLocalIndex() != null;
      this.doGetAllOnLocalIndex = true;
      this.gfIndexContainer = Misc.getMemStore().getContainer(
          ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT, selectQI
              .getChosenLocalIndex().getConglomerateNumber()));
      if (this.gfIndexContainer == null) {
        SanityManager.THROWASSERT("unexpected null container for index: "
            + selectQI.getChosenLocalIndex());
      }
    }
    else {
      // Unreachable code but need for final variables
      this.gfIndexContainer = null;
      SanityManager.THROWASSERT("GemFireResultSet cannot handle this Query");
    }

    // Set gfKeys in @see OpenCore
    this.gfKeys = null;
    this.currPos = 0;
    if (observer != null) {
      observer.createdGemFireXDResultSet(this);
    }
    this.queryHDFS = queryHDFS;
  }

  private Object[] setGfKeysFromIndexKeys(Object localIndexKey)
      throws StandardException {
    final Object[] indxKeys;
    if (localIndexKey instanceof Object[]) {
      if (this.selectQI.isWhereClauseDynamic()) {
        Object[] pks = ((Object[])localIndexKey);
        int len = pks.length;
        indxKeys = new Object[len];
        for (int i = 0; i < len; ++i) {
          if (pks[i] instanceof DynamicKey) {
            DynamicKey dk = (DynamicKey)pks[i];
            indxKeys[i] = dk.getEvaluatedIndexKey(activation);
          }
          else {
            indxKeys[i] = pks[i];
          }
        }
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          ParameterValueSet pvs = activation.getParameterValueSet();
          SanityManager.DEBUG_PRINT(
              GfxdConstants.TRACE_QUERYDISTRIB,
              "GemFireResultSet determined index based gfKeys="
                  + RowUtil.toString(indxKeys) + " from ParameterValueSet@"
                  + Integer.toHexString(System.identityHashCode(pvs)) + pvs);
        }
      }
      else {
        indxKeys = (Object[])localIndexKey;
      }
    }
    else {
      indxKeys = new Object[1];
      if (this.selectQI.isWhereClauseDynamic()) {
        DynamicKey dk = (DynamicKey)localIndexKey;
        indxKeys[0] = dk.getEvaluatedIndexKey(activation);
      }
      else {
        indxKeys[0] = localIndexKey;
      }
    }
    SanityManager.ASSERT(indxKeys != null);
    return indxKeys;
  }
  
  /*
   * Set Keys for Non Collocated Join 
   */
  @Override
  public void setGfKeysForNCJoin(ArrayList<DataValueDescriptor> keys)
      throws StandardException {
    int len = keys.size();
    this.gfKeys = new Object[len];
    for (int i = 0; i < len; ++i) {
      this.gfKeys[i] = GemFireXDUtils.convertIntoGemfireRegionKey(keys.get(i),
          this.gfContainer, false);
    }
  }
  
  private void ncjSetGfKeysFromPrimaryKeys(Object pk) throws StandardException {
    final Object[] pKeys = setGfKeysFromIndexKeys(pk);
    SanityManager.ASSERT(pKeys.length == 1);
    if (pKeys[0] instanceof DVDSet) {
      final Object[] values = ((DVDSet)pKeys[0]).getValues();
      int len = values.length;
      this.gfKeys = new Object[len];
      for (int i = 0; i < len; ++i) {
        DataValueDescriptor dvd = (DataValueDescriptor)values[i];
        this.gfKeys[i] = GemFireXDUtils.convertIntoGemfireRegionKey(dvd,
            this.gfContainer, false);
      }
    }
    else {
      this.gfKeys = setGfKeysFromPrimaryKeys(pk);
    }
  }
  
  private void ncjSetGfKeysFromIndexKeys(Object fk) throws StandardException {
    final Object[] fKeys = setGfKeysFromIndexKeys(fk);
    SanityManager.ASSERT(fKeys.length == 1);
    if (fKeys[0] instanceof DVDSet) {
      final Object[] values = ((DVDSet)fKeys[0]).getValues();
      int len = values.length;
      this.gfKeys = new DataValueDescriptor[len];
      for (int i = 0; i < len; ++i) {
        DataValueDescriptor dvd = (DataValueDescriptor)values[i];
        this.gfKeys[i] = dvd;
      }
    }
    else {
      this.gfKeys = setGfKeysFromIndexKeys(fk);
    }
  }
    
  /* 
   * Asif: Obtain the Primary Key. If it is dynamic then we need to compute
   * now. If it is static use it as it is.
   */
  private Object[] setGfKeysFromPrimaryKeys(Object pk) throws StandardException {
    final Object[] pkKeys;
    if (pk instanceof Object[]) {
      if (selectQI.isWhereClauseDynamic()) {
        Object[] pks = ((Object[])pk);
        /*#42166 as we are sharing qInfo (dmlQueryInfo.getPrimaryKey()) above will return
         * template from AndJunctionQueryInfo#recursiveGeneration (pks object[]) which 
         * will be shared by multiple threads and therefore allocating a different array to
         * gfKeys outright and then populating it.. 
         */
        int len = pks.length;
        pkKeys = new Object[len];
        for (int i = 0; i < len; ++i) {
          if (pks[i] instanceof DynamicKey) {
            DynamicKey dk = (DynamicKey)pks[i];
            pkKeys[i] = dk.getEvaluatedPrimaryKey(activation, this.gfContainer,
                false);
          }
          else {
            pkKeys[i] = pks[i];
          }
        }
      }
      else {
        pkKeys = (Object[])pk;
      }
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        ParameterValueSet pvs = activation.getParameterValueSet();
        SanityManager.DEBUG_PRINT(
            GfxdConstants.TRACE_QUERYDISTRIB,
            "GemFireResultSet determined pk based gfKeys="
                + (pkKeys == null ? "null" : RowUtil.toString(pkKeys))
                + " from ParameterValueSet@"
                + Integer.toHexString(System.identityHashCode(pvs)) + pvs);
      }
    }
    else {
      pkKeys = new Object[1];
      if (selectQI.isWhereClauseDynamic()) {
        DynamicKey dk = (DynamicKey)pk;
        pk = dk.getEvaluatedPrimaryKey(activation, this.gfContainer, false);
      }
      pkKeys[0] = pk;
    }
    SanityManager.ASSERT(pkKeys != null);
    return pkKeys;
  }

  public static boolean exceptionCanBeIgnored(StandardException ex) {
    Throwable t = ex;
    while (t != null) {
      if (t instanceof EntryExistsException) {
        return true;
      }
      if (t instanceof StandardException
          && ((StandardException)t).getSQLState().equals(
              SQLState.LANG_DUPLICATE_KEY_CONSTRAINT)) {
        return true;
      }
      t = t.getCause();
    }
    return false;
  }

  private ExecRow getResultRowFromFetchRow(final ExecRow fetchRow)
      throws StandardException {
    /*
    if (fetchRow == null || this.act == null) {
      return fetchRow;
    }
    */
    if (this.projectionAllColumnsWithLobs != null && fetchRow != null) {
      // we will reach here only for DVD[] store i.e. SYS tables
      if (this.candidate == null) {
        this.candidate = this.gfContainer.newTemplateRow(this.projectionFormat,
            this.projectionAllColumnsWithLobs);
      }
      this.candidate.setColumns(this.projectionAllColumnsWithLobs, false,
          fetchRow);
      
    }
    else {
      this.candidate = fetchRow;
    }
    
    return this.candidate;
  }

  @Override
  public ExecRow getNextRow() throws StandardException {
    final long beginTime = statisticsTimingOn ? XPLAINUtil.recordTiming(-1) : 0;
    try {
      final ExecRow next = fetchNext();
      if (next != null) {
        this.activation.setCurrentRow(next, this.resultsetNumber);
        rowsReturned++;
        if (this.isOffHeap) {
          @Unretained Object bs = next.getByteSource();
          if (bs instanceof OffHeapByteSource) {
            this.tran.addByteSource((OffHeapByteSource)bs);
          }
          else {
            this.tran.addByteSource(null);
          }
        }
      }
      return next;
    }catch (Exception ex) {
      this.close(true);
      final Region<?, ?> region = this.gfContainer != null? 
          this.gfContainer.getRegion():null;
     
      final String method = "GemFireResultSet::getNextRow";
      GemFireXDUtils.processCancelException(method, ex, null);
      if(ex instanceof StandardException) {
        throw (StandardException)ex;
      }
      throw Misc.processFunctionException(method, ex, null, region);
    } catch (Error e) {
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
    } finally {
      if (beginTime != 0) {
        nextTime += XPLAINUtil.recordTiming(beginTime);
      }
    }    
  }

  private ExecRow fetchNext() throws StandardException {
    if (this.doGetAll || this.doGetAllOnLocalIndex) {
      if (this.getAllDone) {
        return this.doGetAll ? (ExecRow)getNextFromGetAll(false).currentRowForGetAll
            : getNextFromGetAllOnLocalIndex();
      }
      return this.doGetAll ? executeGetAll() : executeGetAllOnLocalIndex();
    }
    
    final int numKeys = this.gfKeys.length;
    if (this.currPos >= numKeys) {
      return null;
    }

    ExecRow fetchRow = null;

    final boolean isByteArrayStore = this.gfContainer.isByteArrayStore();
    final LocalRegion region = this.gfContainer.getRegion();
    final LogWriterI18n logger = region.getLogWriterI18n();
    final boolean hasLoader = this.gfContainer.getHasLoaderAnywhere();
    final TXStateInterface tx = this.gfContainer.getActiveTXState(this.tran);
    final boolean isTX = tx != null;
    
    while (this.currPos < numKeys) {
      Object retVal = null;
      boolean didGoToServer = false;
      final Object gfKey = this.gfKeys[this.currPos++];
      this.currentRoutingObject = null;
      long begin = 0;
      if (this.isGlobalIndexCase) {
        try {
          currentRoutingObject = GemFireXDUtils
              .getRoutingObjectFromGlobalIndex(region, gfKey, null);
        } catch (EntryNotFoundException ex) {
          if (hasLoader) {
            if (this.stats != null) {
              this.stats.incNumGetsStartedByte();
              begin = this.stats.getStatTime();
            }
            final Object result = loadOneRow(gfKey, region);
            if (result != null) {
              retVal = result;
            }
            else {
              continue;
            }
            didGoToServer = true;
            if (isTX) {
              TXContext context = TXManagerImpl.currentTXContext();
              if (context == null || context.getTXState() == null) {
                context = TXManagerImpl.getOrCreateTXContext();
                context.clearTXState();
                context.setTXState(tx);
              }
            }
            retVal = insertOneRow(gfKey, region, logger, tx, retVal);
          }
          else {
            // fix for bug #41271; it is possible that routing object here
            // is null but during get() it appears so that the get() gets
            // routed to a node but without callbackArg which will lead to
            // another global index lookup on the data store node
            continue;
          }
        }
      }
      if (isByteArrayStore) { // byte[] or byte[][]
        try {
          Object rawRow = null;
          if (!didGoToServer) {
            if (this.stats != null) {
              this.stats.incNumGetsStartedByte();
              begin = this.stats.getStatTime();
            }
            fetchRow = executeGetExecutorMessage(gfKey, region, logger,
                hasLoader, currentRoutingObject, tx);
            if (fetchRow != null) {
              // return the row that already has projection
              // applied
              return fetchRow;
            }
          }
          else {
            rawRow = retVal;
          }
          if (rawRow != null) {
            fetchRow = this.gfContainer.newExecRow(rawRow);
          }
        } finally {
          if (this.stats != null) {
            this.stats.incNumGetsEndedByte();
            this.stats.incGetNextCoreByteTime(begin);
          }
        }
      }
      else { // DVD[] or Object
        Object value;
        try {
          if (!didGoToServer) {
            if (this.stats != null) {
              this.stats.incNumGetsStartedDvd();
              begin = this.stats.getStatTime();
            }
            value = executeGetOnGFERegion(gfKey, region, logger, hasLoader,
                currentRoutingObject, isTX);
          }
          else {
            value = retVal;
          }
        } finally {
          if (this.stats != null) {
            this.stats.incNumGetsEndedDvd();
            this.stats.incGetNextCoreDvdTime(begin);
          }
        }
        if (value != null) {
          fetchRow = this.gfContainer.newExecRow(gfKey, value);
        }
      }
      if (fetchRow != null) {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(
              GfxdConstants.TRACE_RSITER,
              "GemFireResultSet.fetchNext: returning row ["
                  + RowUtil.toString(fetchRow) + "] for key=" + gfKey.toString());
        }
        break;
      }
    }

    return getResultRowFromFetchRow(fetchRow);
  }
  
  private Object loadOneRow(final Object gfKey, final LocalRegion region)
      throws StandardException {
    final Object result;
    final LanguageConnectionContext lcc = this.activation
        .getLanguageConnectionContext();
    GetRowFunctionArgs args = new GetRowFunctionArgs(
        this.gfContainer.getSchemaName(), this.gfContainer.getTableName(),
        gfKey);
    final DistributedSystem sys = region.getSystem();
    DistributionDescriptor dd = gfContainer.getDistributionDescriptor();
    Set<String> serverGroups = dd.getServerGroups();
    ServerGroupUtils.GetServerGroupMembers members = new ServerGroupUtils
        .GetServerGroupMembers(serverGroups, true);
    members.setSelfPreference();
    members.setTableName(args.getTableName());
    final GfxdSingleResultCollector rc = new GfxdSingleResultCollector();
    try {
      result = FunctionUtils.executeFunctionOnMembers(sys, members, args,
          GetRowFunction.ID, rc, false, lcc != null ? lcc.isPossibleDuplicate()
              : false, true, false);
      if (result == null) {
        GemFireXDUtils.checkForInsufficientDataStore(region);
      }
    } catch (GemFireException gfeex) {
      throw Misc.processGemFireException(gfeex, gfeex,
          "execution of ResultSet.next()", true);
    } catch (StandardException se) {
      if (SQLState.LANG_INVALID_MEMBER_REFERENCE.equals(se.getMessageId())) {
        throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
            se.getCause(), "execution of ResultSet.next()");
      }
      else {
        throw se;
      }
    }
    return result;
  }
  
  private Object insertOneRow(final Object gfKey, final LocalRegion region,
      final LogWriterI18n logger, final TXStateInterface tx, Object retVal)
      throws StandardException {
    final boolean isTX = tx != null;
    try {
      Object key = gfKey;
      if (gfKey instanceof DataValueDescriptor) {
        key = ((DataValueDescriptor)gfKey).getClone();
      }
      this.gfContainer
          .insert(key, retVal, true, this.tran, tx, lcc, true /* is cache loaded*/, false /*isPutDML*/, false /*wasPutDML*/);
      if (isTX) {
        // Transaction cache loaded. Add it to gateway via DBSynchronizer
        // message.
        ((BaseActivation)this.activation).distributeTxCacheLoaded(region,
            this.tran, key, retVal);
      }
    } catch (StandardException e) {
      if (logger.finerEnabled()) {
        logger.finer("Got exception in doing insert in GemFireResultSet", e);
      }
      if (!exceptionCanBeIgnored(e)) {
        throw e;
      }
    }
    return retVal;
  }

  private ExecRow processResultRow(final Object key,
      final LocalRegion region, final boolean hasLoader,
      final boolean hasProjection, final boolean isTX, Object rawRow)
      throws StandardException {
    ExecRow fetchRow = null;
    /*
    if (rawRow == null) {
      // This check is needed as though GFE throws
      // PartitionedRegionStorageException for no data stores,
      // for replicated it returns null & hence the check is needed
      GemFireXDUtils.checkForInsufficientDataStore(region);
    }
    */
    assert !(rawRow instanceof ProjectionRow) || (hasLoader && isTX):
        "unexpected rawRow " + rawRow + " with hasLoader=" + hasLoader
        + " isTX=" + isTX;
    if (hasLoader && isTX) {
      if (rawRow instanceof ProjectionRow) {
        rawRow = ((ProjectionRow)rawRow).getRawValue();
        // Transaction cache loaded. Add it to gateway via
        // DBSynchronizer message.
        ((BaseActivation)this.activation).distributeTxCacheLoaded(region,
            this.tran, key, rawRow);
        if (hasProjection) {
          // need to apply projection explicitly
          fetchRow = ProjectionRow.getCompactExecRow(rawRow, this.gfContainer,
              this.projectionFormat, this.projectionAllColumnsWithLobs,
              this.projectionLobColumns);
        }
        else {
          fetchRow = this.gfContainer.newExecRow(key, rawRow);
        }
      }
    }
    // check if projection already applied
    if (rawRow != null && fetchRow == null) {
      if (hasProjection) {
        // for projection, the format is always changed to that
        // of current schema
        AbstractCompactExecRow compactRow = (AbstractCompactExecRow)rawRow;
        compactRow.setRowFormatter(this.projectionFormat);
        fetchRow = compactRow;
      }
      else {
       fetchRow = this.gfContainer.newExecRow(key, rawRow);
      }
    }
    return fetchRow;
  }

  @SuppressWarnings("unchecked")
  public static List<?> callGetAllExecutorMessage(
      @Nonnull RegionMultiKeyExecutorMessage getAllMsg) throws StandardException {
    final LogWriterI18n logger = getAllMsg.getRegion().getLogWriterI18n();
    try {
      return (ArrayList<Object>)getAllMsg.executeFunction();
    } catch (GemFireXDRuntimeException ex) {
      if (logger.fineEnabled()) {
        logger.fine("Got GemFireXDRuntimeException exception", ex);
      }
      if (ex.getCause() instanceof StandardException) {
        if (!exceptionCanBeIgnored((StandardException)ex.getCause())) {
          throw (StandardException)ex.getCause();
        }
      }
      else {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, ex, (Object)ex.getCause());
      }
    } catch (EntryExistsException ex) {
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, ex, ex.toString());
    } catch (EmptyRegionFunctionException ignored) {
      // ignored @see ReferencedKeyCheckerMessage#referencedKeyCheck
      if (logger.fineEnabled()) {
        logger.fine("Ignored EmptyRegionFunctionException exception", ignored);
      }
    } catch (GemFireException gfeex) {
      throw Misc.processGemFireException(gfeex, gfeex,
          "execution of ResultSet.next()", true);
    } catch (StandardException ex) {
      if (getAllMsg instanceof GetAllExecutorMessage
          && ((GetAllExecutorMessage)getAllMsg).hasLoader()) {
        if (!SQLState.LANG_DUPLICATE_KEY_CONSTRAINT.equals(ex.getSQLState())
            && !SQLState.LANG_FK_VIOLATION.equals(ex.getSQLState())) {
          throw ex;
        }
      }
      else {
        throw ex;
      }
    } catch (SQLException sqle) {
      // wrap in StandardException
      throw Misc.processFunctionException("GemFireResultSet.executeGetAll",
          sqle, getAllMsg.getTarget(), getAllMsg.getRegion());
    }

    return null;
  }

  /*
   * GetAll Execution on Global Index
   * @return size of result
   */
  private ExecRow executeGetAllOnGlobalIndex() throws StandardException {
    assert this.isGlobalIndexCase;
    boolean doLog = DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery
        | GemFireXDUtils.TraceNCJ;
    final LocalRegion region = this.gfContainer.getRegion();
    final LogWriterI18n logger = region.getLogWriterI18n();
    final boolean hasLoader = this.gfContainer.getHasLoaderAnywhere();
    final TXStateInterface tx = this.gfContainer.getActiveTXState(this.tran);

    long begin = 0;
    try {
      if (this.stats != null) {
        this.stats.incNumGetsStartedByte();
        begin = this.stats.getStatTime();
      }

      if (!this.getAllOnGlobalIndexDone) {
        int giResultSize = callGetAllOnGlobalIndex(region, tx);

        if (doLog) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "GemFireResultSet.executeGetAllOnGlobalIndex: Global Index returned ["
                  + giResultSize + "] results for container="
                  + this.gfContainer);
        }

        this.getAllOnGlobalIndexDone = true;
        resetGetAll();
      }

      if (hasLoader) {
        while (this.currPos < this.gfKeys.length) {
          final Object gfKey = this.gfKeys[this.currPos++];
          if (!this.getAllKeysAndRoutingObjects.containsKey(gfKey)) {
            final Object result = loadOneRow(gfKey,
                this.gfContainer.getRegion());
            if (result == null) {
              continue;
            }
            Object retVal = insertOneRow(gfKey, region, logger, tx, result);
            if (retVal != null) {
              return this.gfContainer.newExecRow(gfKey, retVal);
            }
          }
        }
      }
    } finally {
      if (this.stats != null) {
        this.stats.incNumGetsEndedByte();
        this.stats.incGetNextCoreByteTime(begin);
      }
    }

    return null;
  }

  /*
   * GetAll Execution on Global Index
   * @return size of result
   */
  private int callGetAllOnGlobalIndex(final LocalRegion region,
      final TXStateInterface tx) throws StandardException {
    assert this.isGlobalIndexCase;
    assert !this.getAllOnGlobalIndexDone;
    boolean doLog = DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery
        | GemFireXDUtils.TraceNCJ;

    // Find global index region
    final TableDescriptor td = ((GemFireContainer)region.getUserAttribute())
        .getTableDescriptor();
    Map<String, Integer> pkMap = GemFireXDUtils
        .getPrimaryKeyColumnNamesToIndexMap(td, this.lcc);
    GemFireContainer giContainer = GfxdPartitionResolver
        .getContainerOfGlobalIndex(td, pkMap);
    LocalRegion giRegion = giContainer.getRegion();
    assert giRegion != null;

    // Also remove duplicates
    ObjectObjectHashMap<Object, CompactCompositeRegionKey> giKeys =
        ObjectObjectHashMap.withExpectedSize(this.gfKeys.length);
    for (Object gfKey : this.gfKeys) {
      // also see assertion in constructor
      assert gfKey instanceof CompactCompositeRegionKey;
      giKeys.put(((CompactCompositeRegionKey)gfKey).getKeyColumn(0),
          ((CompactCompositeRegionKey)gfKey));
    }

    int numKeys = giKeys.size();
    if (observer != null) {
      observer.getAllGlobalIndexInvoked(numKeys);
    }

    Set<Object> keys = giKeys.keySet();
    if (doLog) {
      SanityManager.DEBUG_PRINT(
          GfxdConstants.TRACE_QUERYDISTRIB,
          "GemFireResultSet.callGetAllOnGlobalIndex: Started for container="
              + giContainer + "; with Loader="
              + giContainer.getHasLoaderAnywhere() + "; for keys["
              + keys.size() + "] = " + RowUtil.toString(keys.toArray()));
    }

    this.getAllMsg = new GetAllExecutorMessage(giRegion, keys.toArray(), null,
        null, null, null, null, null, tx, this.lcc, this.forUpdate, queryHDFS);
    this.getAllResults = callGetAllExecutorMessage(this.getAllMsg);

    if (doLog) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "GemFireResultSet.callGetAllOnGlobalIndex: Done.");
    }

    if (this.getAllResults != null) {
      this.getAllItr = this.getAllResults.iterator();
      GetAllSingletonResult result = null;
      while ((result = getNextFromGetAll(true)) != null
          && result.currentRowForGetAll != null) {
        this.getAllKeysAndRoutingObjects.put(giKeys
            .get(result.currentKeyForGetAll),
            ((GlobalRowLocation)result.currentRowForGetAll)
                .getRoutingObject());
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(
              GfxdConstants.TRACE_RSITER,
              "GemFireResultSet.callGetAllOnGlobalIndex: results key ["
                  + result.currentKeyForGetAll
                  + "] and row ["
                  + ((GlobalRowLocation)result.currentRowForGetAll)
                      .getRoutingObject() + "]");
        }
      }
    }
    else {
      if (GemFireXDUtils.TraceRSIter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
            "GemFireResultSet.callGetAllOnGlobalIndex: Global Index returned null. "
                + "No results would be given");
      }
    }

    return this.getAllKeysAndRoutingObjects.size();
  }

  /*
   * GetAll Execution
   */
  private ExecRow executeGetAll() throws StandardException {
    assert this.doGetAll;
    assert !this.getAllDone;
    assert !this.doGetAllOnLocalIndex;
    assert this.gfContainer.isByteArrayStore() || this.gfContainer.isObjectStore();
    boolean doLog = DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery
        | GemFireXDUtils.TraceNCJ;

    if (this.gfKeys.length < 1) {
      // Handle Zero Rows
      if (doLog) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "GemFireResultSet.executeGetAll: No keys for container="
                + this.gfContainer);
      }
      if (observer != null) {
        observer.getAllInvoked(0);
      }
      return null;
    }

    if (doLog) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "GemFireResultSet.executeGetAll: Called with key index ["
              + this.currPos + "] for " + this.gfKeys.length
              + " keys for container=" + this.gfContainer + " ; with Loader "
              + this.gfContainer.getHasLoaderAnywhere()
              + " ; with GlobalIndex " + this.isGlobalIndexCase);
    }

    Object[] getAllKeys = null;
    Object[] getAllRoutingObjects = null;
    if (this.isGlobalIndexCase) {
      assert this.currPos <= this.gfKeys.length;
      ExecRow retVal = executeGetAllOnGlobalIndex();
      if (retVal != null) {
        return retVal;
      }
      int giResultSize = this.getAllKeysAndRoutingObjects.size();
      if (giResultSize < 1) {
        if (doLog) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "GemFireResultSet.executeGetAll: returning Null since "
                  + "Global Index returned No keys for container="
                  + this.gfContainer);
        }
        return null;
      }
      getAllKeys = new Object[giResultSize];
      getAllRoutingObjects = new Integer[giResultSize];
      int index = 0;
      for (Map.Entry<Object, Object> entry : this.getAllKeysAndRoutingObjects
          .entrySet()) {
        getAllKeys[index] = entry.getKey();
        getAllRoutingObjects[index] = entry.getValue();
        index++;
      }
      assert index == giResultSize;
    }
    else
    {
      OpenHashSet<Object> keysSet = new OpenHashSet<>(this.gfKeys.length);
      // remove duplicates
      for (Object key : this.gfKeys) {
        keysSet.add(key);
      }
      getAllKeys = keysSet.toArray();
    }

    assert getAllKeys != null;
    int numKeys = getAllKeys.length;
    assert getAllRoutingObjects == null
        || numKeys == getAllRoutingObjects.length;
    if (observer != null) {
      observer.getAllInvoked(numKeys);
    }

    if (doLog) {
      SanityManager
          .DEBUG_PRINT(
              GfxdConstants.TRACE_QUERYDISTRIB,
              "GemFireResultSet.executeGetAll: GetAllExecutorMessage "
                  + "would be called for container="
                  + this.gfContainer
                  + "; for keys["
                  + numKeys
                  + "] = "
                  + RowUtil.toString(getAllKeys)
                  + "; for routingObjects["
                  + (getAllRoutingObjects == null ? 0
                      : getAllRoutingObjects.length)
                  + "] = "
                  + (getAllRoutingObjects == null ? "null" : RowUtil
                      .toString(getAllRoutingObjects)));
    }

    long begin = 0;
    try {
      if (this.stats != null) {
        this.stats.incNumGetsStartedByte();
        begin = this.stats.getStatTime();
      }
      final TXStateInterface tx = this.tran != null ? this.gfContainer
          .getActiveTXState(this.tran) : null;
      this.getAllMsg = new GetAllExecutorMessage(this.gfContainer.getRegion(),
          getAllKeys, getAllRoutingObjects, this.projectionFormat,
          this.projectionFixedColumns, this.projectionVarColumns,
          this.projectionLobColumns, this.projectionAllColumnsWithLobs, tx,
          this.lcc, this.forUpdate, this.queryHDFS);
      this.getAllResults = callGetAllExecutorMessage(this.getAllMsg);
    } finally {
      if (this.stats != null) {
        this.stats.incNumGetsEndedByte();
        this.stats.incGetNextCoreByteTime(begin);
      }
    }

    this.getAllDone = true;

    if (doLog) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "GemFireResultSet.executeGetAll: Done.");
    }

    if (this.getAllResults != null) {
      this.getAllItr = this.getAllResults.iterator();
      return (ExecRow)getNextFromGetAll(false).currentRowForGetAll;
    }

    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "GemFireResultSet.executeGetAll: returning null (last row)");
    }

    return null;
  }
  
  /*
   * GetAll Execution on Local Index
   */
  private ExecRow executeGetAllOnLocalIndex() throws StandardException {
    assert this.doGetAllOnLocalIndex == true;
    assert this.doGetAll == false;
    assert this.getAllDone == false;
    assert this.gfContainer.isByteArrayStore() == true;
    assert this.gfKeys.length > 0;
    boolean doLog = DistributionManager.VERBOSE | GemFireXDUtils.TraceQuery
        | GemFireXDUtils.TraceNCJ;
    final LocalRegion region = this.gfContainer.getRegion();

    final Object[] getAllKeys;
    @SuppressWarnings("unchecked")
    OpenHashSet<Object> keysSet = new OpenHashSet<>(this.gfKeys.length);
    // remove duplicates
    for (Object key : this.gfKeys) {
      keysSet.add(key);
    }
    if (keysSet.size() == gfKeys.length) {
      getAllKeys = gfKeys;
    }
    else {
      getAllKeys = keysSet.toArray();
    }

    if (observer != null) {
      observer.getAllLocalIndexExecuted();
    }

    if (doLog) {
      SanityManager
          .DEBUG_PRINT(
              GfxdConstants.TRACE_QUERYDISTRIB,
              "GemFireResultSet.executeGetAllOnLocalIndex: Call GetAllLocalIndexExecutorMessage with keys ["
                  + getAllKeys.length
                  + "] = "
                  + RowUtil.toString(getAllKeys)
                  + " at key index ["
                  + this.currPos
                  + "] for original incoming list of "
                  + this.gfKeys.length
                  + " keys for container=" + this.gfContainer);
    }

    long begin = 0;
    try {
      if (this.stats != null) {
        this.stats.incNumGetsStartedByte();
        begin = this.stats.getStatTime();
      }
      final TXStateInterface tx = this.gfContainer.getActiveTXState(this.tran);
      this.getAllMsg = new GetAllLocalIndexExecutorMessage(
          this.gfIndexContainer.getQualifiedTableName(), getAllKeys, region,
          this.lcc, this.activation.getConnectionID(), tx,
          this.projectionFormat, this.projectionFixedColumns,
          this.projectionVarColumns, this.projectionLobColumns,
          this.projectionAllColumnsWithLobs);
      this.getAllResults = callGetAllExecutorMessage(this.getAllMsg);
    } finally {
      if (this.stats != null) {
        this.stats.incNumGetsEndedByte();
        this.stats.incGetNextCoreByteTime(begin);
      }
    }

    this.getAllDone = true;

    if (doLog) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "GemFireResultSet.executeGetAllOnLocalIndex: Done.");
    }

    if (this.getAllResults != null) {
      this.getAllItr = this.getAllResults.iterator();
      return getNextFromGetAllOnLocalIndex();
    }

    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "GemFireResultSet.executeGetAllOnLocalIndex: returning null (last row)");
    }

    return null;
  }

  private ExecRow executeGetExecutorMessage(final Object gfKey,
      final LocalRegion region, final LogWriterI18n logger,
      final boolean hasLoader, final Object routingObject,
      final TXStateInterface tx) throws StandardException {
    final LanguageConnectionContext lcc = this.activation
        .getLanguageConnectionContext();
    final boolean isTX = tx != null;
    ExecRow fetchRow = null;
    Object callbackArg = null;
    GetExecutorMessage getMsg = null;
    Object rawRow = null;
    try {
      Object key = gfKey;
      if (hasLoader) {
        if (!isTX) {
          // It is possible that this PK based get may load data & hence
          // do internal put so we need to wrap it in GfxdCallbackArg
          callbackArg = GemFireXDUtils.wrapCallbackArgs(routingObject, lcc, false,
              true, true /* cache loaded */, true /* isPkBased */,
              lcc.isSkipListeners(), false, lcc.isSkipConstraintChecks());
        }
        if (gfKey instanceof DataValueDescriptor) {
          key = ((DataValueDescriptor)gfKey).getClone();
        }
      }
      if (logger.fineEnabled()) {
        logger.fine("GemFireResultSet::getNextRow:Loader present " + hasLoader
            + ";callbackArg=" + callbackArg + ";container=" + this.gfContainer);
      }

      // GetExecutorMessage will apply the projection on store
      // rawRow = region.get(key, callbackArg);
      getMsg = new GetExecutorMessage(region, key, callbackArg, routingObject,
          this.projectionFormat, this.projectionFixedColumns,
          this.projectionVarColumns, this.projectionLobColumns,
          this.projectionAllColumnsWithLobs, tx, lcc, this.forUpdate,
          this.queryHDFS);
      rawRow = getMsg.executeFunction();
      fetchRow = processResultRow(key, region, hasLoader,
          getMsg.hasProjection(), isTX, rawRow);
    } catch (CacheLoaderException ex) {
      final boolean isItENFE = checkIfENFE(ex);
      if (isItENFE) {
        if (logger.fineEnabled()) {
          logger.fine("Got EntryNotFoundException wrapped "
              + "in CacheLoaderException");
        }
        rawRow = null;
      }
      else {
        throw ex;
      }
    } catch (EntryNotFoundException ex) {
      if (logger.fineEnabled()) {
        logger.fine("Got EntryNotFoundException exception", ex);
      }
      rawRow = null;
    } catch (GemFireXDRuntimeException ex) {
      if (logger.fineEnabled()) {
        logger.fine("Got GemFireXDRuntimeException exception", ex);
      }
      if (ex.getCause() instanceof StandardException) {
        if (!exceptionCanBeIgnored((StandardException)ex.getCause())) {
          throw (StandardException)ex.getCause();
        }
      }
      else {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, ex, (Object)ex.getCause());
      }
    } catch (EntryExistsException ex) {
      if (this.isTheRegionReplicate) {
        rawRow = ex.getOldValue();
      }
      else {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, ex, ex.toString());
      }
    } catch (GemFireException gfeex) {
      throw Misc.processGemFireException(gfeex, gfeex,
          "execution of ResultSet.next()", true);
    } catch (StandardException ex) {
      if (hasLoader) {
        if (!SQLState.LANG_DUPLICATE_KEY_CONSTRAINT.equals(ex.getSQLState())
            && !SQLState.LANG_FK_VIOLATION.equals(ex.getSQLState())) {
          throw ex;
        }
      }
      else {
        throw ex;
      }
    } catch (SQLException sqle) {
      // wrap in StandardException
      throw Misc.processFunctionException("GemFireResultSet#getNextRow", sqle,
          getMsg != null ? getMsg.getTarget() : null, region);
    }
    return fetchRow;
  }

  private Object executeGetOnGFERegion(final Object gfKey,
      final LocalRegion region, final LogWriterI18n logger,
      final boolean hasLoader, final Object routingObject, final boolean isTX)
      throws StandardException {
    Object key = gfKey;
    Object callbackArg = routingObject;
    Object value;
    if (hasLoader) {
      if (!isTX) {
        // It is possible that this PK based get may load data & hence
        // do internal put so we need to wrap it in GfxdCallbackArg.
        callbackArg = GemFireXDUtils.wrapCallbackArgs(callbackArg, lcc, false,
            true, true /* cache loaded */, true /* isPkBased */,
            lcc.isSkipListeners(), false, lcc.isSkipConstraintChecks());
      }
      if (gfKey instanceof DataValueDescriptor) {
        key = ((DataValueDescriptor)gfKey).getClone();
      }
    }
    try {
      value = region.get(key, callbackArg);
      if (hasLoader && isTX) {
        /* [sumedh] we really need to know if the value was indeed
         *  loaded by CacheLoader; currently throwing assertion error
         *  since loaders are not expected on stores with DVD[] store
        // Transaction cache loaded. Add it to gateway via
        // DBSynchronizer message.
        ((BaseActivation)this.activation).distributeTxCacheLoaded(
            region, this.tran, key, dvds);
        */
        Assert.fail("unexpected TX with loader in DVD[] store "
            + this.gfContainer);
      }
    } catch (CacheLoaderException ex) {
      boolean isItENFE = checkIfENFE(ex);
      if (isItENFE) {
        if (logger.fineEnabled()) {
          logger.fine("Got CacheLoaderException which "
              + "had EntryNotFoundException within");
        }
        value = null;
      }
      else {
        throw ex;
      }
    } catch (EntryNotFoundException ex) {
      if (logger.fineEnabled()) {
        logger.fine("Got EntryNotFoundException exception", ex);
      }
      value = null;
    } catch (GemFireXDRuntimeException ex) {
      if (logger.fineEnabled()) {
        logger.fine("Got GemFireXDRuntimeException exception", ex);
      }
      if (ex.getCause() instanceof StandardException) {
        throw (StandardException)ex.getCause();
      }
      else {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, ex, (Object)ex.getCause());
      }
    } catch (EntryExistsException ex) {
      if (this.isTheRegionReplicate) {
        value = ex.getOldValue();
      }
      else {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, ex, ex.toString());
      }
    }
    return value;
  }

  public ExecRow getNextRowCore() throws StandardException {
    // assert this.act == null;
    final ExecRow next = getNextRow();
    if (next != null) {
      setCurrentRow(next);
      return next;
    }
    return null;
  }

  public static boolean checkIfENFE(CacheLoaderException ex) {
    Throwable e = ex.getCause();
    while(e != null) {
      if (e instanceof EntryNotFoundException) {
        return true;
      }
      e = e.getCause();
    }
    return false;
  }

  @Override
  public void openCore() throws StandardException {
    // Reset Variables
    resetGetAll();
    resetGfeResultSet();

    // Set gfKeys
    Object pk = this.selectQI.getPrimaryKey();
    Object fk = this.selectQI.getLocalIndexKey();
    assert pk != null ^ fk != null;
    if (pk != null) {
      if (this.selectQI.isNcjLevelTwoQueryWithVarIN()) {
        ncjSetGfKeysFromPrimaryKeys(pk);
      }
      else {
        this.gfKeys = setGfKeysFromPrimaryKeys(pk);
      }
    }
    else if (fk != null) {
      // Handle Local Index Case
      if (this.selectQI.isNcjLevelTwoQueryWithVarIN()) {
        ncjSetGfKeysFromIndexKeys(fk);
      }
      else {
        this.gfKeys = setGfKeysFromIndexKeys(fk);
      }
      if (this.observer != null) {
        this.observer.getAllLocalIndexInvoked(this.gfKeys.length);
      }
    }

    // open the container for reading
    this.gfContainer.open(this.tran, ContainerHandle.MODE_READONLY);
  }

  @Override
  public void finishResultSet(final boolean cleanupOnError) throws StandardException {
    // close any subquery tracking arrays for top ResultSet
    if (this.subqueryTrackingArray != null) {
      if (this.isTopResultSet) {
        for (NoPutResultSet rs : this.subqueryTrackingArray) {
          if (rs != null && !rs.isClosed()) {
            rs.close(cleanupOnError);
          }
        }
      }
      this.subqueryTrackingArray = null;
    }
    // close the container thereby releasing any locks
    this.gfContainer.closeForEndTransaction(this.tran, false);
    /*
    if (this.act != null) {
      this.act.resetProjectionExecRow();
    }
    */
  }

  @Override
  public boolean returnsRows() {
    return true;
  }

  @Override
  public void clearCurrentRow() {
    this.activation.clearCurrentRow(this.resultsetNumber);
  }

  @Override
  public double getEstimatedRowCount() {
    return this.gfKeys.length;
  }

  @Override
  public int getPointOfAttachment() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public int getScanIsolationLevel() {
    return this.isolationLevel;
  }

  /**
   * @see UpdatableResultSet#isForUpdate()
   */
  @Override
  public final boolean isForUpdate() {
    return this.forUpdate;
  }

  /**
   * @see UpdatableResultSet#canUpdateInPlace()
   */
  @Override
  public final boolean canUpdateInPlace() {
    return this.forUpdate;
  }

  /**
   * @see UpdatableResultSet#updateRow(ExecRow)
   */
  @Override
  public final void updateRow(ExecRow updateRow) throws StandardException {
    final Object key = getKeyForUpdate();
    Object routingObject = this.getAllKeysAndRoutingObjects != null
        ? this.getAllKeysAndRoutingObjects.get(key) : this.currentRoutingObject;
    this.cachedChangedRow = GemFireDistributedResultSet.updateDirectly(key,
        routingObject, updateRow,
        ((BaseActivation)this.activation).getUpdatedColumns(),
        this.gfContainer, this.selectQI, this.tran, this.lcc,
        this.cachedChangedRow);
  }

  /**
   * @see UpdatableResultSet#deleteRowDirectly()
   */
  @Override
  public void deleteRowDirectly() throws StandardException {
    Object key = getKeyForUpdate();
    Object routingObject = this.getAllKeysAndRoutingObjects != null
        ? this.getAllKeysAndRoutingObjects.get(key) : this.currentRoutingObject;
    this.gfContainer.delete(key, routingObject, true, this.tran,
        this.gfContainer.getActiveTXState(this.tran), lcc, false);
  }

  private Object getKeyForUpdate() {
    // only support get and getAll at this point
    // support for getAllFromLocalIndex will require additions to detect the
    // keys per member mapping, or send back key for each row returned
    if (this.doGetAll) {
      return this.singletonResultGetAll.currentKeyForGetAll;
    }
    else if (!this.doGetAllOnLocalIndex) {
      final int pos = this.currPos;
      if (pos > 0) {
        return this.gfKeys[pos - 1]; // since currPos has been incremented
      }
      else {
        return null;
      }
    }
    else {
      Assert.fail("unexpected call to getAllLocalIndex for SELECT FOR UPDATE");
      // never reached
      return null;
    }
  }

  @Override
  public void markAsTopResultSet() {
    this.isTopResultSet = true;
  }

  @Override
  public TXState initLocalTXState() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void upgradeReadLockToWrite(final RowLocation rl,
      GemFireContainer container) throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void updateRowLocationPostRead() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void filteredRowLocationPostRead(TXState localTXState) throws StandardException {
    //NO OP
    /*throw new UnsupportedOperationException(
        "This method should not have been invoked");*/
     ExecRow row = this.activation.getCurrentRow(this.resultsetNumber);
     if(row != null) {      
       this.clearCurrentRow();
     }
  }

  public void markRowAsDeleted() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public void positionScanAtRowLocation(RowLocation rLoc)
      throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public void reopenCore() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public boolean requiresRelocking() {
    return false;
  }

  public int resultSetNumber() {
    return this.resultsetNumber;
  }

  public void setCurrentRow(ExecRow row) {
    activation.setCurrentRow(row, this.resultsetNumber);
  }

  public void setNeedsRowLocation(boolean needsRowLocation) {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public void setTargetResultSet(TargetResultSet trs) {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public boolean needsRowLocation() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public void rowLocation(RowLocation rl) throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");

  }

  public void closeRowSource() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public ExecRow getNextRowFromRowSource() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public FormatableBitSet getValidColumns() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  public boolean needsToClone() {
    return false;
  }

  @Override
  public void accept(ResultSetStatisticsVisitor visitor) {
    visitor.setNumberOfChildren(0);
    visitor.visit(this);
  }
  
  public String getContainer() {
    return gfContainer.toString();
  }
  
  public GemFireContainer getGFContainer() {
    return gfContainer;
  }
  
  public RowFormatter getProjectionFormat() {
    return projectionFormat;
  }

  @Override
  public long estimateMemoryUsage() throws StandardException {
    long memory = 0;
    if (candidate != null) {
      memory += candidate.estimateRowSize();
    }

    try {
      if (this.getAllMsg != null) {
        memory += this.getAllMsg.estimateMemoryUsage();
      }

      for (Object getAllVal : this.getAllResults) {
        ArrayList<?> resultList = (ArrayList<?>)((ListResultCollectorValue)
            getAllVal).resultOfSingleExecution;
        if (resultList != null) {
          for (Object v : resultList) {
            if (v != null) {
              final Class<?> cls = v.getClass();
              if (cls == byte[].class) {
                memory += ((byte[])v).length;
              }
              else if (cls == byte[][].class) {
                for (byte[] b : ((byte[][])v)) {
                  memory += (b != null ? b.length : 0);
                }
              }
              else if (v instanceof DataValueDescriptor[]) {
                for (DataValueDescriptor d : (DataValueDescriptor[])v) {
                  memory += d.estimateMemoryUsage();
                }
              }
            }
          }
        }
      }
    } catch (ConcurrentModificationException ignore) {
    } catch (NoSuchElementException ignore) {
    }
    return memory;
  }

  public final boolean isGetAllPlan() {
    return doGetAll;
  }
  
  public final boolean isGetAllLocalIndexPlan() {
    return doGetAllOnLocalIndex;
  }
  
  @Override
  public int modifiedRowCount() {
    return this.rowsReturned;
  }
  
  @Override
  public boolean isDistributedResultSet() {
    return true;
  }
  
  private GetAllSingletonResult getNextFromGetAll(boolean isGlobalIndexCase)
      throws StandardException {
    this.singletonResultGetAll.reset();
    Object fetchRow = null;
    while (fetchRow == null && (this.getAllItr.hasNext() ||
        (this.getAllResultsItr != null && this.getAllResultsItr.hasNext()))) {
      if (this.getAllResultsItr != null && this.getAllResultsItr.hasNext()) {
        assert this.currentMemberForGetAll != null;
        fetchRow = singleResultGetAll(isGlobalIndexCase).currentRowForGetAll;
      }
      else {
        assert this.getAllItr.hasNext();
        ListResultCollectorValue currVal = (ListResultCollectorValue)this.getAllItr
            .next();
        assert currVal.memberID != null;
        this.currentMemberForGetAll = currVal.memberID;

        List<Object> currKeyList = this.getAllMsg
            .getKeysPerMember(this.currentMemberForGetAll);
        this.getAllKeysItr = currKeyList.iterator();

        final ArrayList<?> results = (ArrayList<?>)currVal.resultOfSingleExecution;
        if (SanityManager.DEBUG) {
          SanityManager.ASSERT(currKeyList.size() == results.size(),
              "Number of values returned [" + results.size()
                  + "] are not equal to number of keys [" + currKeyList.size()
                  + "] for member= " + this.currentMemberForGetAll);
        }
        
        /* Note:
         * We already take care of null resultOfSingleExecution 
         * @see GfxdListResultCollector.addResult
         */
        this.getAllResultsItr = results.iterator();
        fetchRow = singleResultGetAll(isGlobalIndexCase).currentRowForGetAll;
      }
    }

    return this.singletonResultGetAll;
  }
  
  private GetAllSingletonResult singleResultGetAll(boolean isGlobalIndexCase)
      throws StandardException {
    assert this.getAllResultsItr != null;
    assert this.getAllKeysItr != null;
    this.singletonResultGetAll.reset();
    Object rawRow = null;
    while (rawRow == null && this.getAllResultsItr.hasNext()) {
      Object key = this.getAllKeysItr.next();
      rawRow = this.getAllResultsItr.next();
      if (rawRow != null) {
        if (isGlobalIndexCase) {
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "GemFireResultSet.singleResultGetAll: GetAll returning row ["
                    + rawRow.toString() + "] for key=" + key.toString()
                    + "; isGlobalIndexCase=" + isGlobalIndexCase);
          }
          this.singletonResultGetAll.set(rawRow, key);
        }
        else {
          ExecRow fetchRow = processResultRow(key,
              this.gfContainer.getRegion(),
              this.gfContainer.getHasLoaderAnywhere(),
              this.getAllMsg.hasProjection(),
              this.gfContainer.getActiveTXState(this.tran) != null, rawRow);
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(
                GfxdConstants.TRACE_RSITER,
                "GemFireResultSet.singleResultGetAll: GetAll returning row ["
                    + RowUtil.toString(fetchRow) + "] for key="
                    + key.toString() + "; isGlobalIndexCase="
                    + isGlobalIndexCase);
          }
          this.singletonResultGetAll.set(fetchRow, key);
        }
        return this.singletonResultGetAll;
      }
      else {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager
              .DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "GemFireResultSet.singleResultGetAll: GetAll returning row [null] for key="
                      + key.toString() + "; isGlobalIndexCase="
                      + isGlobalIndexCase);
        }
      }
    }

    return this.singletonResultGetAll;
  }
  
  private ExecRow getNextFromGetAllOnLocalIndex() throws StandardException {
    ExecRow fetchRow = null;
    while (fetchRow == null
        && ( (this.getAllItr != null && this.getAllItr.hasNext()) || (this.getAllResultsItr != null && this.getAllResultsItr
            .hasNext()))) {
      if (!(this.getAllResultsItr != null && this.getAllResultsItr.hasNext())) {
        assert this.getAllItr.hasNext();
        ArrayList<?> currList = (ArrayList<?>)this.getAllItr.next();
        assert currList != null;
        this.getAllResultsItr = currList.iterator();
      }
      fetchRow = singleResultGetAllOnLocalIndex();
    }
    
    return fetchRow;
  }

  private ExecRow singleResultGetAllOnLocalIndex() {
    assert this.getAllResultsItr != null;
    ExecRow fetchRow = null;
    Object rawRow = null;
    while (rawRow == null && this.getAllResultsItr.hasNext()) {
      rawRow = this.getAllResultsItr.next();
      if (GemFireXDUtils.TraceRSIter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
            "GemFireResultSet.singleResultGetAllOnLocalIndex: raw row: class="
                +  rawRow.getClass());     
      }
      if (rawRow != null) {
        // @see GemFireResultSet#processResultRow
        if (this.getAllMsg.hasProjection()) {
          AbstractCompactExecRow compactRow = (AbstractCompactExecRow)rawRow;
          compactRow.setRowFormatter(this.projectionFormat);
          fetchRow = compactRow;
        }
        else {
          fetchRow = this.gfContainer.newExecRow(rawRow);
        }

        if (GemFireXDUtils.TraceRSIter) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "GemFireResultSet.singleResultGetAllOnLocalIndex: GetAll returning row ["
                  + RowUtil.toString(fetchRow) + "]");
          
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
              "GemFireResultSet.singleResultGetAllOnLocalIndex: fetch row class type =" + fetchRow.getClass() + " source ="+ (fetchRow instanceof AbstractCompactExecRow ?  ((AbstractCompactExecRow) fetchRow).getByteSource() : null));;
        }
      }
      else {
        if (GemFireXDUtils.TraceRSIter) {
          SanityManager
              .DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                  "GemFireResultSet.singleResultGetAllOnLocalIndex: GetAll returning row [null]");
        }
      }
    }

    return fetchRow;
  }

  /*
   * Reset and Set variables so GemFireResultSet
   * can use new GetAllExecutorMessage
   */
  private void resetGetAll() {
    this.getAllItr = null;
    this.getAllResults = null;
    this.getAllResultsItr = null;
    this.getAllKeysItr = null;
    this.currentMemberForGetAll = null;
    this.getAllMsg = null;
    this.singletonResultGetAll.reset();
  }
  
  /*
   * Reset this GemFireResultSet so can
   * be reused like for re-execution.
   */
  private void resetGfeResultSet() {
    this.getAllDone = false;
    this.currPos = 0;
    this.currentRoutingObject = null;
    this.getAllOnGlobalIndexDone = false;
    if (this.getAllKeysAndRoutingObjects != null) {
      this.getAllKeysAndRoutingObjects.clear();
    }
  }
  
  @Override
  public void reset(GfxdResultCollector<?> rc) throws StandardException {
    super.reset(rc);
    /**
     * Don't see this method to be used in a scenario, thus keeping code commented.
     */
    // this.gfKeys = null;
    // resetGetAll();
    // resetGfeResultSet();
    // finishResultSet();
  }

  private final GetAllSingletonResult singletonResultGetAll =
      new GetAllSingletonResult();

  public static final class GetAllSingletonResult {
    private Object currentRowForGetAll = null;

    private Object currentKeyForGetAll = null;

    void set(Object currentRow, Object currentKey) {
      this.currentRowForGetAll = currentRow;
      this.currentKeyForGetAll = currentKey;
    }

    void reset() {
      this.currentRowForGetAll = null;
      this.currentKeyForGetAll = null;
    }
  }
  
  public StringBuilder buildQueryPlan(StringBuilder builder, PlanUtils.Context context) {
    
    super.buildQueryPlan(builder, context);

    GemFireContainer gfc = getGFContainer();
    RowFormatter rf = getProjectionFormat();
    StringBuilder details = new StringBuilder(gfc.getSchemaName()).append(".").append(gfc.getTableName());
    if (rf != null)
    {
      boolean first = true;
      
      for (int inPosition = 0; inPosition < rf.getNumColumns(); inPosition++)
      {
        ColumnDescriptor cd = rf.getColumnDescriptor(inPosition);
        if (cd == null) {
          continue;
        }
        if (!first) {
          details.append(", ");
        }
        else {
          first = false;
          details.append(" ");
        }
        details.append(cd.getColumnName());
      }
    }
    
    PlanUtils.xmlAttribute(builder, PlanUtils.DETAILS, details);
    
    PlanUtils.xmlTermTag(builder, context, (doGetAll ? PlanUtils.OP_GETTALL
        : doGetAllOnLocalIndex ? PlanUtils.OP_GETTALL_IDX : PlanUtils.OP_GET));

    PlanUtils.xmlCloseTag(builder, context, this);

    return builder;
  }

  @Override
  public RowLocation fetch(final RowLocation loc, ExecRow destRow,
      FormatableBitSet validColumns, boolean faultIn, GemFireContainer container)
      throws StandardException {
    return com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil.fetch(loc,
        destRow, validColumns, faultIn, container, null, null, 0, this.tran);
  }

  @Override
  public void close(boolean cleanupOnError) throws StandardException {
    
    if (!this.isClosed) {
      
      ExecRow row = this.activation.getCurrentRow(this.resultsetNumber);
      
      this.clearCurrentRow();
      if((this.doGetAll || this.doGetAllOnLocalIndex) && this.getAllDone) {
        while ((row = this.fetchNext()) != null) {
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                "GemFireResultSet.close: row = " + row + "being released");
          }          
          row.releaseByteSource();
        }
      }
      //release the un consumed offheap byte source
      
      super.close(cleanupOnError);
    }
  }
  
  @Override
  public void releasePreviousByteSource() {
  }

  @Override
  public void setMaxSortingLimit(long limit) {
  }
}
