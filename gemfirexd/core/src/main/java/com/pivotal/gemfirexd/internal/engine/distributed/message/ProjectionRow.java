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
import java.util.Arrays;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.BucketRegion.RawValue;
import com.gemstone.gemfire.internal.cache.BucketRegion.RawValueFactory;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;

/**
 * This class encapsulates projection applied on existing row without explicitly
 * having a {@link RowFormatter} for the projection but only having the
 * projection column positions. In addition this class extends {@link RawValue}
 * and also has a factory for self ({@link ProjectionRowFactory} so that GFE
 * layers can directly provide ProjectionRow as the RawValue result to be
 * serialized.
 * <p>
 * This is useful on remote data store node execution of
 * {@link GetExecutorMessage}, for example, where we will like to avoid creating
 * a RowFormatter just for that operation.
 * 
 * @author swale
 * @since 7.0
 */
public final class ProjectionRow extends RawValue implements GfxdSerializable {

  private transient RowFormatter formatter;

  private transient int[] fixedColumns;

  private transient int[] varColumns;

  private transient int[] lobColumns;

  private transient int targetFormatOffsetBytes;

  private transient boolean hasProjection;

  /** for deserialization */
  public ProjectionRow() {
    super(null);
  }

  ProjectionRow(final Object value) {
    super(value);
  }

  final void setProjectionInfo(final RowFormatter rf, final int[] fixedColumns,
      final int[] varColumns, final int[] lobColumns, final int offsetBytes) {
    this.formatter = rf;
    this.fixedColumns = fixedColumns;
    this.varColumns = varColumns;
    this.lobColumns = lobColumns;
    this.targetFormatOffsetBytes = offsetBytes;
    this.hasProjection = true;
  }

  /**
   * Get the {@link AbstractCompactExecRow} for the projected columns for local
   * node execution. Remote node execution is handled in
   * toData/fromData/getGfxdID that will perform the serialization compatable
   * with CompactExecRow* classes.
   * 
   * TODO: PERF: can be optimized further by having a ProjectionExecRow that
   * will encapsulate the full row + projection and do ops on the fly
   */
  public static final AbstractCompactExecRow getCompactExecRow(
      final Object rawValue, final GemFireContainer container,
      final RowFormatter targetFormat, final int[] allColumns,
      final int[] lobColumns) {
    // keep the byte[] compatible with projection RowFormatter expected by
    // CompactExecRow* classes
    final Class<?> rawValueClass = rawValue.getClass();
    if (lobColumns == null) {
      final byte[] bytes;
      final RowFormatter formatter;
      if (rawValueClass == byte[].class) {
        bytes = (byte[])rawValue;
      }
      else if (rawValueClass == byte[][].class) {
        bytes = ((byte[][])rawValue)[0];
      }
      else {
        final OffHeapByteSource bs = (OffHeapByteSource)rawValue;
        formatter = container.getRowFormatter(bs);
        return container.newExecRowFromBytes(
            formatter.generateColumns(bs, allColumns, targetFormat),
            targetFormat);
      }
      formatter = container.getRowFormatter(bytes);
      return container.newExecRowFromBytes(
          formatter.generateColumns(bytes, allColumns, targetFormat),
          targetFormat);
    }
    else {
      byte[][] heapByteArrays = null;
      byte[] heapBytes = null;
      OffHeapByteSource ohBytes = null;
      OffHeapRowWithLobs ohByteArrays = null;
      final RowFormatter formatter;
      if (rawValueClass == byte[][].class){
        heapByteArrays = (byte[][])rawValue;
        heapBytes = heapByteArrays[0];
        formatter = container.getRowFormatter(heapBytes);
      }
      else if(rawValueClass == byte[].class){
        heapBytes = (byte[])rawValue;
        formatter = container.getRowFormatter(heapBytes);
      }
      else if (rawValueClass == OffHeapRowWithLobs.class) {
       ohBytes = ohByteArrays = (OffHeapRowWithLobs)rawValue;
       formatter = container.getRowFormatter(ohByteArrays);
      }
      else if (rawValueClass == OffHeapRow.class) {
        final OffHeapRow ohRow = (OffHeapRow)rawValue;
        ohBytes = ohRow;
        formatter = container.getRowFormatter(ohRow);
      }
      else {
        formatter = container.getCurrentRowFormatter();
      }
      final byte[][] result = new byte[lobColumns.length + 1][];
      // generate the first byte[]
      if (allColumns != null) {
        // if all non-LOB columns have been projected, then don't bother copying
        // anything below
        if ((allColumns.length - lobColumns.length) != (formatter
            .getNumColumns() - formatter.numLobs()) ||
            // also copy for a non-current schema version
            targetFormat.schemaVersion != formatter.schemaVersion) {
          result[0] = ohBytes != null ? formatter.generateColumns(ohBytes,
              allColumns, targetFormat) : formatter.generateColumns(heapBytes,
              allColumns, targetFormat);
        }
        else {
          result[0] = ohBytes != null ? ohBytes.getRowBytes() : heapBytes;
        }
      }
      // now copy the LOB byte arrays by reference since they are immutable
      for (int index = 0; index < lobColumns.length; index++) {
        result[index + 1] = ohByteArrays != null ? formatter.getLob(
            ohByteArrays, lobColumns[index]) : formatter.getLob(heapByteArrays,
            lobColumns[index]);
      }
      return container.newExecRowFromByteArrays(result, targetFormat);
    }
  }

  /**
   * @see DataSerializableFixedID#getDSFID()
   */
  @Override
  public final int getDSFID() {
    return DataSerializableFixedID.GFXD_TYPE;
  }

  /**
   * @see DataSerializableFixedID#toData(DataOutput)
   */
  @Override
  public final void toData(final DataOutput out) throws IOException {
    // GfxdDataSerializable.writeGfxdHeader(this, out);
    if (this.hasProjection) {
      if (this.lobColumns == null) {
        // serialize the projection byte[] directly without actually creating it
        serializeColumns(out);
      }
      else {
        assert this.formatter.hasLobs(): "unexpected LOB columns "
            + Arrays.toString(this.lobColumns) + " for " + formatter;
        byte[][] byteArrays = null;
        OffHeapRowWithLobs byteSource = null;
        // first write the byte[][] length
        InternalDataSerializer
            .writeArrayLength(this.lobColumns.length + 1, out);
        serializeColumns(out);
        final Class<?> rawValueClass = rawValue.getClass();
        if (rawValueClass == byte[][].class) {
          byteArrays = (byte[][])this.rawValue;
        } else if (rawValueClass == OffHeapRowWithLobs.class) {
          byteSource = (OffHeapRowWithLobs)this.rawValue;
        }
        // now write the LOB byte arrays
        for (int index = 0; index < this.lobColumns.length; ++index) {
          if (byteSource != null) {
            this.formatter
                .serializeLob(byteSource, this.lobColumns[index], out);
          }
          else {
            DataSerializer.writeByteArray(
                this.formatter.getLob(byteArrays, this.lobColumns[index]), out);
          }
        }
      }
    }
    else {
      // full row is either byte[] or byte[][] so we directly serialize it
      final Object v = this.rawValue;
      if (v != null) {
        final Class<?> vclass = v.getClass();
        if (vclass == byte[].class) {
          out.writeByte(InternalDataSerializer.BYTE_ARRAY);
          DataSerializer.writeByteArray((byte[])v, out);
        }
        else if (vclass == byte[][].class) {
          out.writeByte(InternalDataSerializer.ARRAY_OF_BYTE_ARRAYS);
          DataSerializer.writeArrayOfByteArrays((byte[][])v, out);
        }
        else {
          ((OffHeapByteSource)v).sendTo(out);
        }
      }
      else {
        out.writeByte(InternalDataSerializer.BYTE_ARRAY);
        DataSerializer.writeByteArray(null, out);
      }
    }
  }

  private void serializeColumns(DataOutput out) throws IOException {
    // target formatter should be the latest one
    final RowFormatter targetFormatter = this.formatter.container
        .getCurrentRowFormatter();
    // serialize the required columns directly in the form that the target
    // projection RowFormatter expects
    // keep the serialization compatible with CompactExecRow* classes
    final int targetFormatOffsetIsDefault = RowFormatter
        .getOffsetValueForDefault(this.targetFormatOffsetBytes);
    final Class<?> rawValueClass = rawValue.getClass();

    // serialize the projection byte[] directly without actually creating it
    if (rawValueClass == byte[][].class) {
      this.formatter.serializeColumns(((byte[][])this.rawValue)[0], out,
          this.fixedColumns, this.varColumns,
          this.targetFormatOffsetBytes, targetFormatOffsetIsDefault,
          targetFormatter);
    } else if (rawValueClass == byte[].class) {
      this.formatter.serializeColumns((byte[])this.rawValue, out,
          this.fixedColumns, this.varColumns, this.targetFormatOffsetBytes,
          targetFormatOffsetIsDefault, targetFormatter);
    } else if (rawValueClass == OffHeapRowWithLobs.class) {
      this.formatter.serializeColumns((OffHeapRowWithLobs)this.rawValue,
          out, this.fixedColumns, this.varColumns,
          this.targetFormatOffsetBytes, targetFormatOffsetIsDefault,
          targetFormatter);
    } else { //rawValueClass == OffHeapRow.class
      this.formatter.serializeColumns((OffHeapRow)this.rawValue, out,
          this.fixedColumns, this.varColumns, this.targetFormatOffsetBytes,
          targetFormatOffsetIsDefault, targetFormatter);
    }
  }

  /**
   * @see DataSerializableFixedID#fromData(DataInput)
   */
  @Override
  public final void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    // should happen only when sending full row
    this.rawValue = DataSerializer.readObject(in);
  }

  /**
   * @see GfxdSerializable#getGfxdID()
   */
  @Override
  public byte getGfxdID() {
    if (this.hasProjection) {
      // keep serialization compatible with CompactExecRow* classes, so pretend
      // to be one of those classes
      // OFFHEAP: optimize; should probably allow for
      // serialization/deserialization of OffHeapCompactExecRows also which keep
      // data in offheap (then need to release on ExecRow release/GC)
      if (this.lobColumns == null) {
        return COMPACT_EXECROW;
      }
      return COMPACT_EXECROW_WITH_LOBS;
    }
    return PROJECTION_ROW;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("ProjectionRow(value=").append(this.rawValue);
    sb.append(",isByteArray=").append(isValueByteArray());
    if (this.fromCacheLoader) {
      sb.append(",fromCacheLoader=").append(this.fromCacheLoader);
    }
    if (this.hasProjection) {
      if (this.fixedColumns != null) {
        sb.append(",fixedColumns=").append(Arrays.toString(this.fixedColumns));
      }
      if (this.varColumns != null) {
        sb.append(",varColumns=").append(Arrays.toString(this.varColumns));
      }
      if (this.lobColumns != null) {
        sb.append(",lobColumns=").append(Arrays.toString(this.lobColumns));
      }
      if (this.targetFormatOffsetBytes != 0) {
        sb.append(",targetFormatOffsetBytes=").append(
            this.targetFormatOffsetBytes);
      }
    }
    else {
      sb.append(",sendFullRow=true");
    }
    return sb.append(')').toString();
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }

  /**
   * Factory for {@link ProjectionRow} that extends {@link RawValueFactory}.
   */
  public static final class ProjectionRowFactory extends RawValueFactory {

    private static final ProjectionRowFactory _instance =
      new ProjectionRowFactory();

    @Override
    protected ProjectionRow newInstance(final Object rawVal) {
      return new ProjectionRow(rawVal);
    }

    public static ProjectionRowFactory getFactory() {
      return _instance;
    }
  }
}
