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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.common;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import io.snappydata.thrift.BlobChunk;
import io.snappydata.thrift.ClobChunk;
import io.snappydata.thrift.ColumnDescriptor;
import io.snappydata.thrift.ColumnValue;
import io.snappydata.thrift.Decimal;
import io.snappydata.thrift.SnappyType;
import org.apache.spark.unsafe.Platform;
import org.apache.thrift.TBaseHelper;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;

/**
 * A compact way to represent a set of primitive and non-primitive values. The
 * optimization is only useful when there a multiple primitive values
 * (int/byte/long/short/float/double/boolean) in the list.
 * <p>
 * This packs the primitives in a byte[] while non-primitives are packed in an
 * Object[] to minimize the memory overhead. Consequently it is not efficient to
 * directly iterate over this as a List. Users of this class should normally
 * find the type of the element at a position, and then invoke the appropriate
 * getter instead, e.g. if the type of element is {@link SnappyType#INTEGER}
 * then use the getInt() method.
 * <p>
 * Note that the getters and setters of this class do not do any type checks or
 * type conversions by design and will happily get/set garbage or throw
 * {@link IndexOutOfBoundsException} or {@link NullPointerException} assuming
 * that caller has done the requisite type checks.
 * <p>
 * Layout of the <code>primitives</code> array:
 * <pre>
 *   .----------------------- 1 byte type for each field (-ve of type for null)
 *   |
 *   |       .--------------- Long for each field. Value as long for primitives.
 *   |       |                Index into Object array for non-primitives.
 *   |       |
 *   |       |
 *   V       V
 *   +-------+---------------+
 *   |       |               |
 *   +-------+---------------+
 *    \-----/ \-------------/
 *     header      body
 * </pre>
 */
public class OptimizedElementArray {

  /**
   * Holds the primitive values with types at start (-ve of type for nulls).
   * Also has the offset into {@link #nonPrimitives} for non-primitives.
   */
  protected long[] primitives;
  protected Object[] nonPrimitives;
  protected int nonPrimSize;

  protected int headerSize;
  protected transient int hash;
  protected boolean hasLobs;

  protected OptimizedElementArray() {
  }

  protected OptimizedElementArray(OptimizedElementArray other) {
    this(other, true);
  }

  protected OptimizedElementArray(OptimizedElementArray other,
      boolean copyValues) {
    final long[] prims = other.primitives;
    final Object[] nonPrims = other.nonPrimitives;
    final int header = other.headerSize;
    if (prims != null) {
      if (copyValues) {
        this.primitives = prims.clone();
      } else {
        // only copy the types
        this.primitives = new long[prims.length];
        System.arraycopy(prims, 0, this.primitives, 0, header);
      }
    }
    if (nonPrims != null) {
      this.nonPrimitives = copyValues ? nonPrims.clone()
          : new Object[nonPrims.length];
    }
    this.nonPrimSize = other.nonPrimSize;
    this.headerSize = header;
    if (copyValues) {
      this.hash = other.hash;
    }
    this.hasLobs = other.hasLobs;
  }

  /**
   * Initialize the array for given {@link ColumnDescriptor}s.
   *
   * @param metadata the list of {@link ColumnDescriptor}s ordered by their position
   */
  public OptimizedElementArray(final List<ColumnDescriptor> metadata) {
    int nonPrimitiveIndex = 0;
    final int numFields = metadata.size();
    // a byte for type of each field at the start
    final int headerSize = (numFields + 7) >>> 3;
    this.primitives = new long[headerSize + numFields];
    Iterator<ColumnDescriptor> iter = metadata.iterator();
    for (int index = 0; index < numFields; index++) {
      final ColumnDescriptor cd = iter.next();
      final SnappyType type = cd.type;
      setType(index, type.getValue());
      switch (type) {
        case INTEGER:
        case REAL:
        case BIGINT:
        case DOUBLE:
        case FLOAT:
        case BOOLEAN:
        case TINYINT:
        case NULLTYPE:
        case SMALLINT:
          // skip primitives that will be stored directly in "primitives"
          break;
        case BLOB:
        case CLOB:
          // set the position and also space for finalizer
          nonPrimitiveIndex++;
          this.primitives[headerSize + index] = nonPrimitiveIndex++;
          this.hasLobs = true;
          break;
        default:
          // set the position for non-primitives
          this.primitives[headerSize + index] = nonPrimitiveIndex++;
          break;
      }
    }
    if (nonPrimitiveIndex > 0) {
      this.nonPrimitives = new Object[nonPrimitiveIndex];
      this.nonPrimSize = nonPrimitiveIndex;
    }
    this.headerSize = headerSize;
  }

  protected final void setType(int index, int sqlType) {
    Platform.putByte(this.primitives, Platform.LONG_ARRAY_OFFSET + index,
        (byte)sqlType);
  }

  protected final int getRawType(int index) {
    return Platform.getByte(primitives, Platform.LONG_ARRAY_OFFSET + index);
  }

  protected final int getType(int index) {
    return Math.abs(getRawType(index));
  }

  public final SnappyType getSQLType(int index) {
    return SnappyType.findByValue(getType(index));
  }

  public final boolean isNull(int index) {
    return getRawType(index) < 0;
  }

  public final boolean getBoolean(int index) {
    return getByte(index) != 0;
  }

  public final byte getByte(int index) {
    return (byte)this.primitives[headerSize + index];
  }

  public final short getShort(int index) {
    return (short)this.primitives[headerSize + index];
  }

  public final int getInt(int index) {
    return (int)this.primitives[headerSize + index];
  }

  public final long getLong(int index) {
    return this.primitives[headerSize + index];
  }

  public final float getFloat(int index) {
    return Float.intBitsToFloat(getInt(index));
  }

  public final double getDouble(int index) {
    return Double.longBitsToDouble(getLong(index));
  }

  public final Date getDate(int index) {
    return Converters.getDate(getLong(index));
  }

  public final Time getTime(int index) {
    return Converters.getTime(getLong(index));
  }

  public final Timestamp getTimestamp(int index) {
    return Converters.getTimestamp(getLong(index));
  }

  public final Object getObject(int index) {
    return this.nonPrimitives[(int)this.primitives[headerSize + index]];
  }

  public final TIntArrayList getRemoteLobIndices() {
    if (!hasLobs || nonPrimitives == null) return null;

    final Object[] nonPrimitives = this.nonPrimitives;
    TIntArrayList lobIndices = null;
    final long[] primitives = this.primitives;
    final int headerSize = this.headerSize;
    final int size = primitives.length - headerSize;
    for (int index = 0; index < size; index++) {
      final int type = getType(index);
      if (type == SnappyType.BLOB.getValue()) {
        final int lobIndex = (int)primitives[headerSize + index];
        BlobChunk chunk = (BlobChunk)nonPrimitives[lobIndex];
        if (chunk != null && chunk.isSetLobId()) {
          if (lobIndices == null) {
            lobIndices = new TIntArrayList(4);
          }
          lobIndices.add(lobIndex);
        }
      } else if (type == SnappyType.CLOB.getValue()) {
        final int lobIndex = (int)primitives[headerSize + index];
        ClobChunk chunk = (ClobChunk)nonPrimitives[lobIndex];
        if (chunk != null && chunk.isSetLobId()) {
          if (lobIndices == null) {
            lobIndices = new TIntArrayList(4);
          }
          // -ve index to indicate a CLOB
          lobIndices.add(-lobIndex);
        }
      }
    }
    return lobIndices;
  }

  public final void initializeLobFinalizers(TIntArrayList lobIndices,
      CreateLobFinalizer createLobFinalizer) {
    final Object[] nonPrimitives = this.nonPrimitives;
    int lobIndex;
    final int size = lobIndices.size();
    for (int index = 0; index < size; index++) {
      lobIndex = lobIndices.getQuick(index);
      if (lobIndex > 0) {
        nonPrimitives[lobIndex - 1] = createLobFinalizer
            .execute((BlobChunk)nonPrimitives[lobIndex]);
      } else {
        lobIndex = -lobIndex;
        nonPrimitives[lobIndex - 1] = createLobFinalizer
            .execute((ClobChunk)nonPrimitives[lobIndex]);
      }
    }
  }

  public final BlobChunk getBlobChunk(int index, boolean clearFinalizer) {
    final int lobIndex = (int)this.primitives[headerSize + index];
    final BlobChunk chunk = (BlobChunk)this.nonPrimitives[lobIndex];
    if (clearFinalizer && chunk != null && chunk.isSetLobId()) {
      final FinalizeObject finalizer =
          (FinalizeObject)this.nonPrimitives[lobIndex - 1];
      if (finalizer != null) {
        finalizer.clearAll();
        this.nonPrimitives[lobIndex - 1] = null;
      }
    }
    return chunk;
  }

  public final ClobChunk getClobChunk(int index, boolean clearFinalizer) {
    final int lobIndex = (int)this.primitives[headerSize + index];
    final ClobChunk chunk = (ClobChunk)this.nonPrimitives[lobIndex];
    if (clearFinalizer && chunk != null && chunk.isSetLobId()) {
      final FinalizeObject finalizer =
          (FinalizeObject)this.nonPrimitives[lobIndex - 1];
      if (finalizer != null) {
        finalizer.clearAll();
        this.nonPrimitives[lobIndex - 1] = null;
      }
    }
    return chunk;
  }

  public final void setNull(int index) {
    int rawType = getRawType(index);
    if (rawType > 0) {
      setType(index, -rawType);
    }
  }

  protected final void setPrimLong(int primIndex, long value) {
    this.primitives[primIndex] = value;
  }

  public final void setBoolean(int index, boolean value) {
    setPrimLong(headerSize + index, value ? 1L : 0L);
  }

  public final void setByte(int index, byte value) {
    setPrimLong(headerSize + index, value);
  }

  public final void setShort(int index, short value) {
    setPrimLong(headerSize + index, value);
  }

  public final void setInt(int index, int value) {
    setPrimLong(headerSize + index, value);
  }

  public final void setLong(int index, long value) {
    setPrimLong(headerSize + index, value);
  }

  public final void setFloat(int index, float value) {
    setPrimLong(headerSize + index, Float.floatToIntBits(value));
  }

  public final void setDouble(int index, double value) {
    setPrimLong(headerSize + index, Double.doubleToLongBits(value));
  }

  public final void setDate(int index, Date date) {
    setPrimLong(headerSize + index, Converters.getDateTime(date));
  }

  public final void setTime(int index, Time time) {
    setPrimLong(headerSize + index, Converters.getDateTime(time));
  }

  public final void setTimestamp(int index, Timestamp ts) {
    setPrimLong(headerSize + index, Converters.getTimestamp(ts));
  }

  public final void setObject(int index, Object value, SnappyType type) {
    if (value != null) {
      this.nonPrimitives[(int)this.primitives[headerSize + index]] = value;
      // we force change the type below for non-primitives which will work
      // fine since conversions will be changed on server
      if (getType(index) != type.getValue()) {
        setType(index, type.getValue());
      }
    } else {
      setNull(index);
    }
  }

  static {
    // check the type mapping which is hard-coded in switch-case matches
    // to avoid looking up SnappyType for typeId for every column in every row
    if (SnappyType.BOOLEAN.getValue() != 1) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.TINYINT.getValue() != 2) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.SMALLINT.getValue() != 3) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.INTEGER.getValue() != 4) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.BIGINT.getValue() != 5) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.REAL.getValue() != 6) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.DOUBLE.getValue() != 7) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.FLOAT.getValue() != 8) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.DECIMAL.getValue() != 9) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.CHAR.getValue() != 10) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.VARCHAR.getValue() != 11) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.LONGVARCHAR.getValue() != 12) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.DATE.getValue() != 13) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.TIME.getValue() != 14) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.TIMESTAMP.getValue() != 15) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.BINARY.getValue() != 16) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.VARBINARY.getValue() != 17) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.LONGVARBINARY.getValue() != 18) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.BLOB.getValue() != 19) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.CLOB.getValue() != 20) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.SQLXML.getValue() != 21) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.ARRAY.getValue() != 22) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.MAP.getValue() != 23) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.STRUCT.getValue() != 24) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.NULLTYPE.getValue() != 25) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.JSON.getValue() != 26) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.JAVA_OBJECT.getValue() != 27) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.OTHER.getValue() != 28) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.findByValue(29) != null) {
      throw new AssertionError("unhandled typeId 29");
    }
  }

  /**
   * this should exactly match ColumnValue.standardSchemeWriteValue
   */
  public final void writeStandardScheme(final BitSet changedColumns,
      TProtocol oprot) throws TException, IOException {
    final long[] primitives = this.primitives;
    final Object[] nonPrimitives = this.nonPrimitives;
    int offset = this.headerSize;
    int index;
    final int size;
    if (changedColumns == null) {
      index = 0;
      size = size();
      if (size == 0) return;
    } else {
      index = changedColumns.nextSetBit(0);
      size = 0;
      offset += index;
      if (index < 0) return;
    }
    while (true) {
      oprot.writeStructBegin(ColumnValue.STRUCT_DESC);

      // nulls will always have -ve types so no need for further null checks
      final int rawType = getRawType(index);
      // check more common types first
      switch (rawType) {
        case 10: // CHAR
        case 11: // VARCHAR
        case 12: // LONGVARCHAR
          oprot.writeFieldBegin(ColumnValue.STRING_VAL_FIELD_DESC);
          oprot.writeString((String)nonPrimitives[(int)primitives[offset]]);
          break;
        case 4: // INTEGER
          oprot.writeFieldBegin(ColumnValue.I32_VAL_FIELD_DESC);
          oprot.writeI32((int)primitives[offset]);
          break;
        case 5: // BIGINT
          oprot.writeFieldBegin(ColumnValue.I64_VAL_FIELD_DESC);
          oprot.writeI64(primitives[offset]);
          break;
        case 13: // DATE
          oprot.writeFieldBegin(ColumnValue.DATE_VAL_FIELD_DESC);
          oprot.writeI64(primitives[offset]);
          break;
        case 15: // TIMESTAMP
          oprot.writeFieldBegin(ColumnValue.TIMESTAMP_VAL_FIELD_DESC);
          oprot.writeI64(primitives[offset]);
          break;
        case 7: // DOUBLE
        case 8: // FLOAT
          oprot.writeFieldBegin(ColumnValue.DOUBLE_VAL_FIELD_DESC);
          oprot.writeDouble(Double.longBitsToDouble(primitives[offset]));
          break;
        case 9: // DECIMAL
          oprot.writeFieldBegin(ColumnValue.DECIMAL_VAL_FIELD_DESC);
          BigDecimal decimal = (BigDecimal)nonPrimitives[(int)primitives[offset]];
          Converters.getDecimal(decimal).write(oprot);
          break;
        case 6: // REAL
          oprot.writeFieldBegin(ColumnValue.FLOAT_VAL_FIELD_DESC);
          oprot.writeI32((int)primitives[offset]);
          break;
        case 3: // SMALLINT
          oprot.writeFieldBegin(ColumnValue.I16_VAL_FIELD_DESC);
          oprot.writeI16((short)primitives[offset]);
          break;
        case 1: // BOOLEAN
          oprot.writeFieldBegin(ColumnValue.BOOL_VAL_FIELD_DESC);
          oprot.writeBool(primitives[offset] != 0);
          break;
        case 2: // TINYINT
          oprot.writeFieldBegin(ColumnValue.BYTE_VAL_FIELD_DESC);
          oprot.writeByte((byte)primitives[offset]);
          break;
        case 14: // TIME
          oprot.writeFieldBegin(ColumnValue.TIME_VAL_FIELD_DESC);
          oprot.writeI64(primitives[offset]);
          break;
        case 20: // CLOB
        case 21: // SQLXML
        case 26: // JSON
          oprot.writeFieldBegin(ColumnValue.CLOB_VAL_FIELD_DESC);
          ClobChunk clob = (ClobChunk)nonPrimitives[(int)primitives[offset]];
          clob.write(oprot);
          break;
        case 19: // BLOB
          oprot.writeFieldBegin(ColumnValue.BLOB_VAL_FIELD_DESC);
          BlobChunk blob = (BlobChunk)nonPrimitives[(int)primitives[offset]];
          blob.write(oprot);
          break;
        case 16: // BINARY
        case 17: // VARBINARY
        case 18: // LONGVARBINARY
          oprot.writeFieldBegin(ColumnValue.BINARY_VAL_FIELD_DESC);
          byte[] bytes = (byte[])nonPrimitives[(int)primitives[offset]];
          oprot.writeBinary(ByteBuffer.wrap(bytes));
          break;
        case 22: // ARRAY
          oprot.writeFieldBegin(ColumnValue.ARRAY_VAL_FIELD_DESC);
          @SuppressWarnings("unchecked")
          List<ColumnValue> list =
              (List<ColumnValue>)nonPrimitives[(int)primitives[offset]];
          oprot.writeListBegin(new org.apache.thrift.protocol.TList(
              org.apache.thrift.protocol.TType.STRUCT, list.size()));
          for (ColumnValue cv : list) {
            cv.write(oprot);
          }
          oprot.writeListEnd();
          break;
        case 23: // MAP
          oprot.writeFieldBegin(ColumnValue.MAP_VAL_FIELD_DESC);
          @SuppressWarnings("unchecked")
          Map<ColumnValue, ColumnValue> map =
              (Map<ColumnValue, ColumnValue>)nonPrimitives[(int)primitives[offset]];
          oprot.writeMapBegin(new org.apache.thrift.protocol.TMap(
              org.apache.thrift.protocol.TType.STRUCT,
              org.apache.thrift.protocol.TType.STRUCT, map.size()));
          for (Map.Entry<ColumnValue, ColumnValue> entry : map.entrySet()) {
            entry.getKey().write(oprot);
            entry.getValue().write(oprot);
          }
          oprot.writeMapEnd();
          break;
        case 24: // STRUCT
          oprot.writeFieldBegin(ColumnValue.STRUCT_VAL_FIELD_DESC);
          @SuppressWarnings("unchecked")
          List<ColumnValue> struct =
              (List<ColumnValue>)nonPrimitives[(int)primitives[offset]];
          oprot.writeListBegin(new org.apache.thrift.protocol.TList(
              org.apache.thrift.protocol.TType.STRUCT, struct.size()));
          for (ColumnValue cv : struct) {
            cv.write(oprot);
          }
          oprot.writeListEnd();
          break;
        case 25: // NULLTYPE
          oprot.writeFieldBegin(ColumnValue.NULL_VAL_FIELD_DESC);
          // not-null case since for null type will be -ve
          oprot.writeBool(false);
          break;
        case 27: // JAVA_OBJECT
          oprot.writeFieldBegin(ColumnValue.JAVA_VAL_FIELD_DESC);
          byte[] objBytes = Converters.getJavaObjectAsBytes(
              nonPrimitives[(int)primitives[offset]]);
          oprot.writeBinary(ByteBuffer.wrap(objBytes));
          break;
        default:
          // check for null
          if (rawType < 0) {
            oprot.writeFieldBegin(ColumnValue.NULL_VAL_FIELD_DESC);
            oprot.writeBool(true);
          } else {
            throw new TProtocolException("write: unhandled typeId=" + rawType +
                " at index=" + index + " with size=" + size + "(changedCols=" +
                changedColumns + ")");
          }
      }

      oprot.writeFieldEnd();
      oprot.writeFieldStop();
      oprot.writeStructEnd();

      if (changedColumns == null) {
        index++;
        offset++;
        if (index >= size) return;
      } else {
        index = changedColumns.nextSetBit(index);
        offset = headerSize + index;
        if (index < 0) return;
      }
    }
  }

  public final void readStandardScheme(final int numFields, TProtocol iprot)
      throws TException, SQLException {
    initialize(numFields);
    final long[] primitives = this.primitives;
    int nonPrimSize = 0;
    int offset = this.headerSize;
    for (int index = 0; index < numFields; index++, offset++) {
      iprot.readStructBegin();
      TField field = iprot.readFieldBegin();
      final ColumnValue._Fields setField =
          ColumnValue._Fields.findByThriftId(field.id);
      if (setField != null) {
        // check more common types first
        switch (setField) {
          case STRING_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            String str = iprot.readString();
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = str;
            setType(index, SnappyType.VARCHAR.getValue());
            break;
          case I32_VAL:
            setPrimLong(offset, iprot.readI32());
            setType(index, SnappyType.INTEGER.getValue());
            break;
          case I64_VAL:
            setPrimLong(offset, iprot.readI64());
            setType(index, SnappyType.BIGINT.getValue());
            break;
          case DATE_VAL:
            setPrimLong(offset, iprot.readI64());
            setType(index, SnappyType.DATE.getValue());
            break;
          case TIMESTAMP_VAL:
            setPrimLong(offset, iprot.readI64());
            setType(index, SnappyType.TIMESTAMP.getValue());
            break;
          case DOUBLE_VAL:
            setPrimLong(offset, Double.doubleToLongBits(iprot.readDouble()));
            setType(index, SnappyType.DOUBLE.getValue());
            break;
          case DECIMAL_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            Decimal decimal = new Decimal();
            decimal.read(iprot);
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = Converters
                .getBigDecimal(decimal);
            setType(index, SnappyType.DECIMAL.getValue());
            break;
          case FLOAT_VAL:
            setPrimLong(offset, iprot.readI32());
            setType(index, SnappyType.FLOAT.getValue());
            break;
          case I16_VAL:
            setPrimLong(offset, iprot.readI16());
            setType(index, SnappyType.SMALLINT.getValue());
            break;
          case BOOL_VAL:
            setPrimLong(offset, iprot.readBool() ? 1L : 0L);
            setType(index, SnappyType.BOOLEAN.getValue());
            break;
          case BYTE_VAL:
            setPrimLong(offset, iprot.readByte());
            setType(index, SnappyType.TINYINT.getValue());
            break;
          case TIME_VAL:
            setPrimLong(offset, iprot.readI64());
            setType(index, SnappyType.TIME.getValue());
            break;
          case CLOB_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            ClobChunk clob = new ClobChunk();
            clob.read(iprot);
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = clob;
            setType(index, SnappyType.CLOB.getValue());
            hasLobs = true;
            break;
          case BLOB_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            BlobChunk blob = new BlobChunk();
            blob.read(iprot);
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = blob;
            setType(index, SnappyType.BLOB.getValue());
            hasLobs = true;
            break;
          case BINARY_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            byte[] bytes = TBaseHelper.byteBufferToByteArray(iprot.readBinary());
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = bytes;
            setType(index, SnappyType.VARBINARY.getValue());
            break;
          case NULL_VAL:
            setType(index, iprot.readBool() ? -SnappyType.NULLTYPE.getValue()
                : SnappyType.NULLTYPE.getValue());
            break;
          case ARRAY_VAL:
          case STRUCT_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            org.apache.thrift.protocol.TList alist = iprot.readListBegin();
            final int listSize = alist.size;
            final ArrayList<ColumnValue> array = new ArrayList<>(listSize);
            for (int i = 0; i < listSize; i++) {
              ColumnValue cv = new ColumnValue();
              cv.read(iprot);
              array.add(cv);
            }
            iprot.readListEnd();
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = array;
            setType(index, setField == ColumnValue._Fields.ARRAY_VAL
                ? SnappyType.ARRAY.getValue() : SnappyType.STRUCT.getValue());
            break;
          case MAP_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            org.apache.thrift.protocol.TMap m = iprot.readMapBegin();
            final int mapSize = m.size;
            Map<ColumnValue, ColumnValue> map = new HashMap<>(mapSize << 1);
            for (int i = 0; i < mapSize; i++) {
              ColumnValue k = new ColumnValue();
              ColumnValue v = new ColumnValue();
              k.read(iprot);
              v.read(iprot);
              map.put(k, v);
            }
            iprot.readMapEnd();
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = map;
            setType(index, SnappyType.MAP.getValue());
            break;
          case JAVA_VAL:
            ensureNonPrimCapacity(nonPrimSize);
            byte[] serializedBytes = TBaseHelper.byteBufferToByteArray(
                iprot.readBinary());
            primitives[offset] = nonPrimSize;
            nonPrimitives[nonPrimSize++] = Converters.getJavaObject(
                serializedBytes, index + 1);
            setType(index, SnappyType.JAVA_OBJECT.getValue());
            break;
          default:
            throw ClientSharedUtils.newRuntimeException(
                "unknown column type = " + setField, null);
        }
      } else {
        // treat like null value
        org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
        setType(index, -SnappyType.NULLTYPE.getValue());
      }
      iprot.readFieldEnd();
      // this is so that we will eat the stop byte. we could put a check here to
      // make sure that it actually *is* the stop byte, but it's faster to do it
      // this way.
      iprot.readFieldBegin();
      iprot.readStructEnd();
    }

    this.nonPrimSize = nonPrimSize;
  }

  public final void initialize(int numFields) {
    // a byte for type of each field at the start
    final int headerSize = (numFields + 7) >>> 3;
    this.primitives = new long[headerSize + numFields];
    this.nonPrimitives = null;
    this.nonPrimSize = 0;
    this.headerSize = headerSize;
    this.hash = 0;
    this.hasLobs = false;
  }

  public final void setColumnValue(int index,
      ColumnValue cv) throws SQLException {
    final ColumnValue._Fields setField = cv.getSetField();
    if (setField != null) {
      int sqlTypeId;
      Object fieldVal = Boolean.FALSE; // indicator for primitives
      // check more common types first
      switch (setField) {
        case STRING_VAL:
          fieldVal = cv.getFieldValue();
          sqlTypeId = SnappyType.VARCHAR.getValue();
          break;
        case I32_VAL:
          sqlTypeId = SnappyType.INTEGER.getValue();
          break;
        case I64_VAL:
          sqlTypeId = SnappyType.BIGINT.getValue();
          break;
        case DATE_VAL:
          sqlTypeId = SnappyType.DATE.getValue();
          break;
        case TIMESTAMP_VAL:
          sqlTypeId = SnappyType.TIMESTAMP.getValue();
          break;
        case DOUBLE_VAL:
          sqlTypeId = SnappyType.DOUBLE.getValue();
          break;
        case DECIMAL_VAL:
          Decimal decimal = (Decimal)cv.getFieldValue();
          fieldVal = decimal != null ? Converters.getBigDecimal(decimal) : null;
          sqlTypeId = SnappyType.DECIMAL.getValue();
          break;
        case FLOAT_VAL:
          sqlTypeId = SnappyType.REAL.getValue();
          break;
        case I16_VAL:
          sqlTypeId = SnappyType.SMALLINT.getValue();
          break;
        case BOOL_VAL:
          sqlTypeId = SnappyType.BOOLEAN.getValue();
          break;
        case BYTE_VAL:
          sqlTypeId = SnappyType.TINYINT.getValue();
          break;
        case TIME_VAL:
          sqlTypeId = SnappyType.TIME.getValue();
          break;
        case CLOB_VAL:
          ClobChunk clob = (ClobChunk)cv.getFieldValue();
          if (clob != null) {
            // also make space for finalizer of ClobChunk for remote lobId
            if (clob.isSetLobId()) {
              ensureNonPrimCapacity(nonPrimSize++);
            }
            fieldVal = clob;
          } else {
            fieldVal = null;
          }
          sqlTypeId = SnappyType.CLOB.getValue();
          hasLobs = true;
          break;
        case BLOB_VAL:
          BlobChunk blob = (BlobChunk)cv.getFieldValue();
          if (blob != null) {
            // also make space for finalizer of BlobChunk for remote lobId
            if (blob.isSetLobId()) {
              ensureNonPrimCapacity(nonPrimSize++);
            }
            fieldVal = blob;
          } else {
            fieldVal = null;
          }
          sqlTypeId = SnappyType.BLOB.getValue();
          hasLobs = true;
          break;
        case BINARY_VAL:
          fieldVal = cv.getBinary_val();
          sqlTypeId = SnappyType.VARBINARY.getValue();
          break;
        case NULL_VAL:
          setType(index, cv.getNull_val() ? -SnappyType.NULLTYPE.getValue()
              : SnappyType.NULLTYPE.getValue());
          return;
        case ARRAY_VAL:
          fieldVal = cv.getFieldValue();
          sqlTypeId = SnappyType.ARRAY.getValue();
          break;
        case MAP_VAL:
          fieldVal = cv.getFieldValue();
          sqlTypeId = SnappyType.MAP.getValue();
          break;
        case STRUCT_VAL:
          fieldVal = cv.getFieldValue();
          sqlTypeId = SnappyType.STRUCT.getValue();
          break;
        case JAVA_VAL:
          byte[] serializedBytes = cv.getJava_val();
          fieldVal = serializedBytes != null ? Converters.getJavaObject(
              serializedBytes, index + 1) : null;
          sqlTypeId = SnappyType.JAVA_OBJECT.getValue();
          break;
        default:
          throw ClientSharedUtils.newRuntimeException("unknown column value: " +
              (cv.getFieldValue() != null ? cv : "null"), null);
      }
      if (fieldVal == Boolean.FALSE) {
        setPrimLong(headerSize + index, cv.getPrimitiveLong());
        setType(index, sqlTypeId);
      } else if (fieldVal != null) {
        ensureNonPrimCapacity(nonPrimSize);
        primitives[headerSize + index] = nonPrimSize;
        nonPrimitives[nonPrimSize++] = fieldVal;
        setType(index, sqlTypeId);
      } else {
        // -ve typeId indicator for null values
        setType(index, -sqlTypeId);
      }
    } else {
      // treat like null value
      setType(index, -SnappyType.NULLTYPE.getValue());
    }
  }

  protected final void ensureNonPrimCapacity(final int nonPrimSize) {
    if (this.nonPrimitives != null) {
      final int capacity = this.nonPrimitives.length;
      if (nonPrimSize >= capacity) {
        final int newCapacity = Math.min(capacity + (capacity >>> 1), size());
        Object[] newNonPrims = new Object[newCapacity];
        System.arraycopy(this.nonPrimitives, 0, newNonPrims, 0, capacity);
        this.nonPrimitives = newNonPrims;
      }
    } else {
      this.nonPrimitives = new Object[4];
    }
  }

  public final int size() {
    return primitives.length - headerSize;
  }

  public void clear() {
    Arrays.fill(this.primitives, 0);
    Arrays.fill(this.nonPrimitives, null);
    this.nonPrimSize = 0;
    this.headerSize = 0;
    this.hash = 0;
    this.hasLobs = false;
  }

  @Override
  public int hashCode() {
    int h = this.hash;
    if (h != 0) {
      return h;
    }
    long[] prims = this.primitives;
    if (prims != null && prims.length > 0) {
      for (long l : prims) {
        h = ResolverUtils.addLongToHashOpt(l, h);
      }
    }
    final Object[] nps = this.nonPrimitives;
    if (nps != null && nps.length > 0) {
      for (Object o : nps) {
        h = ResolverUtils.addIntToHashOpt(o != null ? o.hashCode() : -1, h);
      }
    }
    return (this.hash = h);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof OptimizedElementArray &&
        equals((OptimizedElementArray)other);
  }

  public boolean equals(OptimizedElementArray other) {
    return this.nonPrimSize == other.nonPrimSize &&
        this.hasLobs == other.hasLobs &&
        Arrays.equals(this.primitives, other.primitives) &&
        Arrays.equals(this.nonPrimitives, other.nonPrimitives);
  }

  @Override
  public String toString() {
    final long[] primitives = this.primitives;
    final Object[] nonPrimitives = this.nonPrimitives;
    int offset = this.headerSize;
    final int size = size();
    final StringBuilder sb = new StringBuilder();
    for (int index = 0; index < size; index++, offset++) {
      if (index != 0) sb.append(',');
      // nulls will always have -ve types so no need for further null checks
      final int rawType = getRawType(index);
      // check more common types first
      switch (rawType) {
        case 1: // BOOLEAN
          sb.append(primitives[offset] != 0);
          break;
        case 2: // TINYINT
        case 3: // SMALLINT
        case 4: // INTEGER
        case 5: // BIGINT
          sb.append(primitives[offset]);
          break;
        case 6: // REAL
          sb.append(Float.intBitsToFloat((int)primitives[offset]));
          break;
        case 7: // DOUBLE
        case 8: // FLOAT
          sb.append(Double.longBitsToDouble(primitives[offset]));
          break;
        case 9: // DECIMAL
        case 10: // CHAR
        case 11: // VARCHAR
        case 12: // LONGVARCHAR
        case 19: // BLOB
        case 20: // CLOB
        case 21: // SQLXML
        case 22: // ARRAY
        case 23: // MAP
        case 24: // STRUCT
        case 26: // JSON
        case 27: // JAVA_OBJECT
          sb.append(nonPrimitives[(int)primitives[offset]]);
          break;
        case 13: // DATE
          sb.append(primitives[offset]).append(',');
          break;
        case 14: // TIME
          sb.append(primitives[offset]).append(',');
          break;
        case 15: // TIMESTAMP
          sb.append(primitives[offset]).append(',');
          break;
        case 16: // BINARY
        case 17: // VARBINARY
        case 18: // LONGVARBINARY
          byte[] bytes = (byte[])nonPrimitives[(int)primitives[offset]];
          TBaseHelper.toString(ByteBuffer.wrap(bytes), sb);
          break;
        case 25: // NULLTYPE
          sb.append("NullType=false");
          break;
        default:
          // check for null
          if (rawType < 0) {
            sb.append("NULL");
          } else {
            sb.append("UNKNOWN typeId=").append(rawType);
          }
          break;
      }
    }
    return sb.toString();
  }
}
