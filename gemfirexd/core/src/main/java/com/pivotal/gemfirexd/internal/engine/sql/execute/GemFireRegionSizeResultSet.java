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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXState;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TargetResultSet;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

/**
 * This class is to short circuit count aggregation to region.size().
 * 
 * extra class is with the purpose to honor transactions, simple where criteria
 * etc later.
 * 
 * @author soubhikc
 * 
 */
public class GemFireRegionSizeResultSet extends AbstractGemFireResultSet
    implements NoPutResultSet {

  private final ExecRow candidate;

  private boolean returnedRow;

  private final GemFireContainer gfContainer;

  private final int resultSetNumber;
  
  private final Boolean withSecondaries;

  private int rows;

  public GemFireRegionSizeResultSet(Activation act, int resultSetNumber,
      GeneratedMethod resultRowAllocator, long conglomId, String tableName,
      String withSecondaries) throws StandardException {
    super(act);

    this.candidate = (ExecRow)resultRowAllocator.invoke(activation);
    this.resultSetNumber = resultSetNumber;
    this.withSecondaries = (withSecondaries.length() > 0 ? Boolean.parseBoolean(withSecondaries) : null);

    MemConglomerate conglom = this.tran.findExistingConglomerate(conglomId);
    GemFireContainer container = conglom.getGemFireContainer();

    if (container.isLocalIndex() || container.isGlobalIndex()) {
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "looked up index container from local/global index conglomId="
                  + conglomId + " : " + container);
        }
      }
      container = container.getBaseContainer();
    }
    this.gfContainer = container;

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "looked up container with conglomId=" + conglomId + " ("
                + tableName + ") : " + container);
      }
    }

    if (observer != null) {
      observer.createdGemFireXDResultSet(this);
    }
  }

  @Override
  public void finishResultSet(final boolean cleanupOnError) throws StandardException {
  }

  @SuppressWarnings("unchecked")
  @Override
  public void openCore() throws StandardException {
    this.returnedRow = false;
    this.gfContainer.open(this.tran, ContainerHandle.MODE_READONLY);

    try {
      // get bucketIds from function context instead of picking all local
      // primary bucket IDs so that proper failover is done if required
      final FunctionContext fc = this.activation.getFunctionContext();
      final Set<Integer> bucketIds;
      if (fc != null && withSecondaries == null) {
        bucketIds = ((RegionExecutorMessage<Object>)fc)
            .getLocalBucketSet(this.gfContainer.getRegion());
      }
      else {
        bucketIds = null;
      }
      
      if (SanityManager.DEBUG) {
        if (GemFireXDUtils.TraceAggreg) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG, "Opening "
              + GemFireRegionSizeResultSet.class.getSimpleName()
              + " with bucketIds=" + bucketIds + " withSecondaries="
              + withSecondaries);
        }
      }
      // get queryHDFS connection properties
      boolean queryHDFS = false;
      if (this.lcc != null) {
        queryHDFS = lcc.getQueryHDFS();
      }
      // get queryHDFS query hint, which overrides connection property
      if (this.activation != null) {
        if (this.activation.getHasQueryHDFS()) {
          // if user specifies queryHDFS query hint
          queryHDFS = this.activation.getQueryHDFS();
        }
      }
      this.rows = this.gfContainer.getLocalNumRows(bucketIds,
          (withSecondaries != null && withSecondaries.booleanValue()), queryHDFS);
    } finally {
      this.gfContainer.closeForEndTransaction(this.tran, false);
    }
  }

  @Override
  public boolean canUpdateInPlace() {
    return false;
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
   
  }

  @Override
  public double getEstimatedRowCount() {
    return 1.0;
  }

  @Override
  public ExecRow getNextRow() throws StandardException {

    if (returnedRow) {
      clearCurrentRow();
      return null;
    }

    candidate.getColumn(1).setValue(rows);

    returnedRow = true;

    return candidate;
  }

  @Override
  public ExecRow getNextRowCore() throws StandardException {
    return getNextRow();
  }

  @Override
  public final void clearCurrentRow() {
    activation.clearCurrentRow(resultSetNumber);
  }

  @Override
  public int getPointOfAttachment() {
    return 0;
  }

  @Override
  public int getScanIsolationLevel() {
    return 0;
  }

  @Override
  public boolean isForUpdate() {
    return false;
  }

  @Override
  public void markAsTopResultSet() {
  }

  @Override
  public boolean requiresRelocking() {
    return false;
  }

  @Override
  public int resultSetNumber() {
    return this.resultSetNumber;
  }

  @Override
  public boolean returnsRows() {
    return true;
  }

  /**
   * Class for replicated region's size from one of the data nodes.
   * 
   * @author soubhikc
   * 
   */
  public final static class RegionSizeMessage extends
      RegionExecutorMessage<Object> {

    private String qualifiedTableName;

    /** Empty constructor for deserialization. Not to be invoked directly. */
    public RegionSizeMessage() {
      super(true);
    }

    public RegionSizeMessage(final String tableName,
        ResultCollector<Object, Object> collector, LocalRegion region,
        Set<Object> routingObjects, LanguageConnectionContext lcc) {
      super(collector, region, routingObjects, getCurrentTXState(lcc),
          getTimeStatsSettings(lcc), true);
      this.qualifiedTableName = tableName;
    }

    private RegionSizeMessage(final RegionSizeMessage other) {
      super(other);
      this.qualifiedTableName = other.qualifiedTableName;
    }

    @Override
    protected RegionExecutorMessage<Object> clone() {
      return new RegionSizeMessage(this);
    }

    @Override
    protected void setArgsForMember(DistributedMember member,
        Set<DistributedMember> messageAwareMembers) {
    }

    @Override
    protected void execute() {
      final Region<?, ?> r = Misc.getRegionByPath(qualifiedTableName, true);
      assert !(r instanceof PartitionedRegion): "lets use PR.size for this .. ";
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "Calculating size for region " + qualifiedTableName);
      }

      lastResult(Integer.valueOf(r != null ? r.size() : 0), false, false, true);
    }

    @Override
    public void toData(DataOutput out)
        throws IOException {
      final long begintime = this.timeStatsEnabled ? XPLAINUtil
          .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
          : -2/*ignore nested call*/) : 0;
      super.toData(out);
      DataSerializer.writeString(qualifiedTableName, out);

      if (begintime != 0) {
        this.ser_deser_time = XPLAINUtil.recordTiming(begintime);
      }
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      super.fromData(in);

      qualifiedTableName = DataSerializer.readString(in);

      if (timeStatsEnabled) {
        this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
      }
    }

    @Override
    public boolean isHA() {
      return true;
    }

    @Override
    public boolean optimizeForWrite() {
      return true;
    }
    
    @Override
    public boolean withSecondaries() {
      return false;
    }

    @Override
    public byte getGfxdID() {
      return GET_REGION_SIZE_MSG;
    }
  }

  @Override
  public void markRowAsDeleted() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void positionScanAtRowLocation(RowLocation rLoc)
      throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void reopenCore() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void setCurrentRow(ExecRow row) {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void setNeedsRowLocation(boolean needsRowLocation) {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void setTargetResultSet(TargetResultSet trs) {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void updateRow(ExecRow row) throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void deleteRowDirectly() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }
  
  @Override
  public void accept(ResultSetStatisticsVisitor visitor) {
    visitor.setNumberOfChildren(0);
    visitor.visit(this);
  }

  @Override
  public boolean needsRowLocation() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void rowLocation(RowLocation rl) throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public void closeRowSource() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public ExecRow getNextRowFromRowSource() throws StandardException {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public FormatableBitSet getValidColumns() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public boolean needsToClone() {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  @Override
  public long estimateMemoryUsage() throws StandardException {
    return 0;
  }

  @Override
  public void setGfKeysForNCJoin(ArrayList<DataValueDescriptor> keys)
      throws StandardException {
    throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
        " Currently this method is not implemented or overridden for class "
            + this.getClass().getSimpleName());
  }
  
  @Override
  public StringBuilder buildQueryPlan(StringBuilder builder, PlanUtils.Context context) {
    super.buildQueryPlan(builder, context);

    PlanUtils.xmlTermTag(builder, context, PlanUtils.OP_AUTO_GEN_KEYS);

    PlanUtils.xmlCloseTag(builder, context, this);

    return builder;
  }

  @Override
  public RowLocation fetch(final RowLocation loc, ExecRow destRow,
      FormatableBitSet validColumns, boolean faultIn, GemFireContainer container)
      throws StandardException {
    return RowUtil.fetch(loc, destRow, validColumns, faultIn, container, null,
        null, 0, this.tran);
  }
  
  @Override
  public void releasePreviousByteSource() {
  }

  @Override
  public void setMaxSortingLimit(long limit) {
  }
}
