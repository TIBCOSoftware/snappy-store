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

package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireCheckedException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.AbstractOperationMessage;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

/**
 * Function messages that need to execute on multiple key in a region can extend
 * this class.
 * 
 * @author vivekb
 * 
 */
public abstract class RegionMultiKeyExecutorMessage extends
    RegionExecutorMessage<Object> {

  // Key List pertaining to one member
  protected List<Object> membersToKeys = null;

  protected GfxdCallbackArgument commonCallbackArg;

  protected String regionPath;

  protected int prId = -1;

  // transient arguments used as execution parameters

  protected transient PartitionedRegion pr;

  /** the target when a single node has to be targeted */
  protected transient DistributedMember target = null;
  
  // Handle Projection
  /** the ordered list of fixed width column positions in the projection */
  protected int[] projectionFixedColumns;

  /** the ordered list of variable width column positions in the projection */
  protected int[] projectionVarColumns;

  /** the ordered list of LOB column positions in the projection */
  protected int[] projectionLobColumns;

  /**
   * The ordered list of all column positions in the projection excluding the
   * LOB columns. This list is not transported across but is only used in case
   * of local execution for better efficiency in generating the byte array.
   */
  protected transient int[] projectionAllColumns;

  protected byte targetFormatOffsetBytes;

  protected transient boolean hasProjection;

  protected transient RowFormatter targetFormat;

  // flags used in serialization are below
  protected static final short IS_PARTITIONED_TABLE =
    RegionExecutorMessage.UNRESERVED_FLAGS_START;
  protected static final short HAS_CALLBACK_ARG = (IS_PARTITIONED_TABLE << 1);
  protected static final short HAS_PROJECTION = (HAS_CALLBACK_ARG << 1);
  /** the unreserved flags start for child classes */
  protected static final short UNRESERVED_FLAGS_START = (HAS_PROJECTION << 1);

  /** Empty constructor for deserialization. Not to be invoked directly. */
  protected RegionMultiKeyExecutorMessage(boolean ignored) {
    super(true);
  }

  protected RegionMultiKeyExecutorMessage(
      ResultCollector<Object, Object> collector, final LocalRegion region,
      Set<Object> routingObjects, final TXStateInterface tx,
      boolean timeStatsEnabled, final RowFormatter targetFormat,
      final int[] projectionFixedColumns, final int[] projectionVarColumns,
      final int[] projectionLobColumns, final int[] projectionAllColumns) {
    super(collector, region, routingObjects, tx, timeStatsEnabled, true);
    this.regionPath = region.getFullPath();
    if (region.getPartitionAttributes() != null) {
      this.pr = (PartitionedRegion)region;
      this.prId = this.pr.getPRId();
    }
    this.hasProjection = projectionAllColumns != null;
    if (this.hasProjection) {
      final int offsetBytes = targetFormat.getNumOffsetBytes();
      assert offsetBytes > 0 && offsetBytes <= 4: offsetBytes;
      this.targetFormat = targetFormat;
      this.projectionFixedColumns = projectionFixedColumns;
      this.projectionVarColumns = projectionVarColumns;
      this.projectionLobColumns = projectionLobColumns;
      this.projectionAllColumns = projectionAllColumns;
      this.targetFormatOffsetBytes = (byte)offsetBytes;
    }
    this.membersToKeys = new ArrayList<Object>();
  }

  /** copy constructor */
  protected RegionMultiKeyExecutorMessage(
      final RegionMultiKeyExecutorMessage other) {
    super(other);
    this.membersToKeys = other.membersToKeys;
    this.commonCallbackArg = other.commonCallbackArg;
    this.regionPath = other.regionPath;
    this.pr = other.pr;
    this.prId = other.prId;
    this.targetFormat = other.targetFormat;
    this.projectionFixedColumns = other.projectionFixedColumns;
    this.projectionVarColumns = other.projectionVarColumns;
    this.projectionLobColumns = other.projectionLobColumns;
    this.projectionAllColumns = other.projectionAllColumns;
    this.targetFormatOffsetBytes = other.targetFormatOffsetBytes;
    this.hasProjection = other.hasProjection;
    this.target = other.target;
  }

  @Override
  protected abstract RegionMultiKeyExecutorMessage clone();

  /**
   * @see AbstractOperationMessage#computeCompressedShort(short)
   */
  @Override
  protected short computeCompressedShort(short flags) {
    flags = super.computeCompressedShort(flags);
    if (this.prId >= 0) {
      flags |= IS_PARTITIONED_TABLE;
    }
    if (this.commonCallbackArg != null) {
      flags |= HAS_CALLBACK_ARG;
    }
    if (this.hasProjection) {
      flags |= HAS_PROJECTION;
    }
    return flags;
  }

  @Override
  public void toData(DataOutput out)
      throws IOException {
    final long begintime = this.timeStatsEnabled ? XPLAINUtil
        .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
        : -2/*ignore nested call*/) : 0;
    super.toData(out);
    // write the region ID or path
    if (this.prId >= 0) {
      InternalDataSerializer.writeUnsignedVL(this.prId, out);
    }
    else {
      DataSerializer.writeString(this.regionPath, out);
    }

    InternalDataSerializer.writeObject(this.membersToKeys, out);
    if (this.commonCallbackArg != null) {
      InternalDataSerializer.writeObject(this.commonCallbackArg, out);
    }

    // write the projection
    if (this.hasProjection) {
      GetExecutorMessage.writeUIntArray(this.projectionFixedColumns, out);
      GetExecutorMessage.writeUIntArray(this.projectionVarColumns, out);
      GetExecutorMessage.writeUIntArray(this.projectionLobColumns, out);
      out.writeByte(this.targetFormatOffsetBytes);
    }

    if (begintime != 0) {
      this.ser_deser_time = XPLAINUtil.recordTiming(begintime);
    }
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    ser_deser_time = this.timeStatsEnabled ? (ser_deser_time == 0 ? -1 /*record*/
    : -2/*ignore nested call*/) : 0;
    super.fromData(in);

    // read region first
    if ((flags & IS_PARTITIONED_TABLE) != 0) {
      this.prId = (int)InternalDataSerializer.readUnsignedVL(in);
    }
    else {
      this.regionPath = DataSerializer.readString(in);
    }

    this.membersToKeys = InternalDataSerializer.readObject(in);
    if ((flags & HAS_CALLBACK_ARG) != 0) {
      this.commonCallbackArg = DataSerializer.readObject(in);
    }
   
    // read any projection
    this.hasProjection = (flags & HAS_PROJECTION) != 0;
    if (this.hasProjection) {
      this.projectionFixedColumns = GetExecutorMessage.readIntArray(in);
      this.projectionVarColumns = GetExecutorMessage.readIntArray(in);
      this.projectionLobColumns = GetExecutorMessage.readIntArray(in);
      this.targetFormatOffsetBytes = in.readByte();
    }

    // recording end of de-serialization here instead of
    // AbstractOperationMessage.
    if (this.timeStatsEnabled && ser_deser_time == -1) {
      this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
    }
  }

  protected String getID() {
    return getShortClassName();
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";regionPath=").append(this.regionPath);
    if (this.pr != null) {
      sb.append(";prId=").append(this.prId);
    }
    if (this.commonCallbackArg != null) {
      sb.append(";callbackArg=");
      ArrayUtils.objectString(this.commonCallbackArg, sb);
    }
    if (this.hasProjection) {
      if (this.projectionFixedColumns != null) {
        sb.append(";projectionFixedColumns=").append(
            Arrays.toString(this.projectionFixedColumns));
      }
      if (this.projectionVarColumns != null) {
        sb.append(";projectionVarColumns=").append(
            Arrays.toString(this.projectionVarColumns));
      }
      if (this.projectionLobColumns != null) {
        sb.append(";projectionLobColumns=").append(
            Arrays.toString(this.projectionLobColumns));
      }
    }
  }

  @Override
  protected void setIgnoreReplicateIfSetOperators(boolean ignoreReplicate) {
    // do nothing
  }

  public final DistributedMember getTarget() {
    return this.target;
  }

  @Override
  protected final void processMessage(DistributionManager dm)
      throws GemFireCheckedException {
    if (this.region == null) {
      if (this.prId >= 0) { // PR case
        this.pr = PartitionedRegion.getPRFromId(this.prId);
        if (this.pr == null) {
          throw new ForceReattemptException(
              LocalizedStrings.PartitionMessage_0_COULD_NOT_FIND_PARTITIONED_REGION_WITH_ID_1
                  .toLocalizedString(new Object[] {
                      Misc.getGemFireCache().getMyId(),
                      Integer.valueOf(this.prId) }));
        }
        this.region = this.pr;
        this.regionPath = region.getFullPath();
      }
      else {
        this.region = Misc.getGemFireCache().getRegionByPathForProcessing(
            this.regionPath);
        if (this.region == null) {
          throw new ForceReattemptException(
              LocalizedStrings.Region_CLOSED_OR_DESTROYED
                  .toLocalizedString(this.regionPath));
        }
      }
    }

    for (Object key : this.membersToKeys) {
      if (key instanceof KeyWithRegionContext) {
        ((KeyWithRegionContext)key).setRegionContext(this.region);
      }
    }

    super.processMessage(dm);
  }
  
  /* 
   * Reset these variables since Different copies of same message, 
   * for different members, would be different
   */
  protected void resetKeysPerMember() {
    if (this.membersToKeys != null) {
      this.membersToKeys.clear();
    }
  }
  
  /**
   * @return the hasProjection
   */
  public final boolean hasProjection() {
    return hasProjection;
  }
  
  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.
   * RegionExecutorMessage#optimizeForWrite()
   */
  @Override
  public boolean optimizeForWrite() {
    return false;
  }
  
  /* (non-Javadoc)
   * @see com.pivotal.gemfirexd.internal.engine.distributed.message.
   * GfxdFunctionMessage#isHA()
   */
  @Override
  public boolean isHA() {
    return true;
  }

  /* 
   * Set these variables since Different copies of same message, 
   * for different members, would be different
   */
  public List<Object> getKeysPerMember(DistributedMember member) {
    return this.membersToKeys;
  }
  
  /*
   * Estimate memory usage
   */
  public abstract long estimateMemoryUsage() throws StandardException;
}
