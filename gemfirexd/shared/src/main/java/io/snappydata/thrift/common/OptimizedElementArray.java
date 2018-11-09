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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
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

  protected static final long[] EMPTY = new long[0];

  protected OptimizedElementArray() {
    this.primitives = EMPTY;
  }

  protected OptimizedElementArray(OptimizedElementArray other) {
    this(other, false, true);
  }

  protected OptimizedElementArray(OptimizedElementArray other,
      boolean otherIsEmpty, boolean copyValues) {
    final long[] prims = other.primitives;
    final Object[] nonPrims = other.nonPrimitives;
    this.headerSize = other.headerSize;
    if (prims.length > 0) {
      // make a copy of primitives array in any case regardless of
      // "copyValues" since the types and non-primitive indexes are required
      this.primitives = prims.clone();
    } else {
      this.primitives = EMPTY;
    }
    if (nonPrims != null) {
      // copy values only if other row has some non-primitive values
      if (copyValues && !otherIsEmpty) {
        this.nonPrimitives = nonPrims.clone();
      } else {
        this.nonPrimitives = new Object[nonPrims.length];
      }
    }
    this.nonPrimSize = other.nonPrimSize;
    this.hasLobs = other.hasLobs;
    if (copyValues) {
      this.hash = other.hash;
    } else {
      // mark primitives as non-null since setters do not mark values
      // as non-null, and mark non-primitives as null
      setDefaultNullability();
    }
  }

  /**
   * Initialize the array for given {@link ColumnDescriptor}s.
   *
   * @param metadata the list of {@link ColumnDescriptor}s ordered by their position
   */
  public OptimizedElementArray(final List<ColumnDescriptor> metadata,
      boolean checkOutputParameters) {
    int nonPrimitiveIndex = 0;
    int numFields = metadata.size();
    // remove any output parameters at the end
    if (checkOutputParameters) {
      for (int rIndex = numFields - 1; rIndex >= 0; rIndex--) {
        if (!metadata.get(rIndex).isParameterOut()) {
          numFields = rIndex + 1;
          break;
        }
      }
    }
    // a byte for type of each field at the start
    final int headerSize = (numFields + 7) >>> 3;
    this.primitives = new long[headerSize + numFields];
    for (int index = 0; index < numFields; index++) {
      final ColumnDescriptor cd = metadata.get(index);
      final SnappyType type = cd.type;
      switch (type) {
        case INTEGER:
        case BIGINT:
        case DOUBLE:
        case FLOAT:
        case DATE:
        case TIMESTAMP:
        case TIME:
        case SMALLINT:
        case BOOLEAN:
        case TINYINT:
        case NULLTYPE:
          setType(index, type.getValue());
          // skip primitives that will be stored directly in "primitives"
          break;
        case BLOB:
        case CLOB:
          // set the position for non-primitives
          this.primitives[headerSize + index] = nonPrimitiveIndex++;
          // mark null
          setType(index, -type.getValue());
          this.hasLobs = true;
          break;
        default:
          // set the position for non-primitives
          this.primitives[headerSize + index] = nonPrimitiveIndex++;
          // mark null
          setType(index, -type.getValue());
          break;
      }
    }
    if (nonPrimitiveIndex > 0) {
      this.nonPrimitives = new Object[nonPrimitiveIndex];
      this.nonPrimSize = nonPrimitiveIndex;
    }
    this.headerSize = headerSize;
  }

  private void setDefaultNullability() {
    final long[] primitives = this.primitives;
    final int end = Platform.LONG_ARRAY_OFFSET + size();
    for (int offset = Platform.LONG_ARRAY_OFFSET; offset < end; offset++) {
      final byte snappyType = Platform.getByte(primitives, offset);
      switch (Math.abs(snappyType)) {
        case 4: // INTEGER
        case 5: // BIGINT
        case 7: // DOUBLE
        case 6: // FLOAT
        case 12: // DATE
        case 13: // TIME
        case 14: // TIMESTAMP
        case 3: // SMALLINT
        case 1: // BOOLEAN
        case 2: // TINYINT
        case 24: // NULLTYPE
          // primitives must be marked non-null
          if (snappyType < 0) {
            Platform.putByte(primitives, offset, (byte)-snappyType);
          }
          break;
        default:
          // non-primitives must be marked null
          if (snappyType > 0) {
            Platform.putByte(primitives, offset, (byte)-snappyType);
          }
      }
    }
  }

  public final void setType(int index, int snappyType) {
    Platform.putByte(this.primitives, Platform.LONG_ARRAY_OFFSET + index,
        (byte)snappyType);
  }

  /**
   * Returns the {@link SnappyType#getValue()} type for the column at given
   * index. The returned type will be negative if the column value is null.
   *
   * @param index 0-based index of the column
   */
  public final int getType(int index) {
    return Platform.getByte(primitives, Platform.LONG_ARRAY_OFFSET + index);
  }

  public final boolean isNull(int index) {
    return getType(index) < 0;
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

  public final long getDateTimeMillis(int index) {
    return getLong(index) * 1000L;
  }

  public final Timestamp getTimestamp(int index) {
    return Converters.getTimestamp(getLong(index));
  }

  public final Object getObject(int index) {
    return this.nonPrimitives[(int)this.primitives[headerSize + index]];
  }

  public final void initializeLobs(LobService lobService) throws SQLException {
    if (!hasLobs || nonPrimitives == null) return;

    final Object[] nonPrimitives = this.nonPrimitives;
    final long[] primitives = this.primitives;
    final int headerSize = this.headerSize;
    final int size = primitives.length - headerSize;
    for (int index = 0; index < size; index++) {
      final int type = getType(index);
      if (type == SnappyType.BLOB.getValue()) {
        final int lobIndex = (int)primitives[headerSize + index];
        if (nonPrimitives[lobIndex] instanceof BlobChunk) {
          BlobChunk chunk = (BlobChunk)nonPrimitives[lobIndex];
          nonPrimitives[lobIndex] = lobService.createBlob(chunk, false);
        }
      } else if (type == SnappyType.CLOB.getValue()) {
        final int lobIndex = (int)primitives[headerSize + index];
        if (nonPrimitives[lobIndex] instanceof ClobChunk) {
          ClobChunk chunk = (ClobChunk)nonPrimitives[lobIndex];
          nonPrimitives[lobIndex] = lobService.createClob(chunk, false);
        }
      }
    }
  }

  public final void setNull(int index) {
    int snappyType = getType(index);
    if (snappyType > 0) {
      setType(index, -snappyType);
    }
  }

  public final void setNull(int index, int snappyType) {
    setType(index, -snappyType);
  }

  public final int setNotNull(int index) {
    int snappyType = getType(index);
    if (snappyType < 0) {
      snappyType = -snappyType;
      setType(index, snappyType);
    }
    return snappyType;
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

  public final void setDateTime(int index, java.util.Date date) {
    setPrimLong(headerSize + index, Converters.getDateTime(date));
  }

  public final void setTimestamp(int index, Timestamp ts) {
    setPrimLong(headerSize + index, Converters.getTimestampNanos(ts));
  }

  public final void setTimestamp(int index, java.util.Date ts) {
    setPrimLong(headerSize + index, Converters.getTimestampNanos(ts));
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
      setNull(index, type.getValue());
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
    if (SnappyType.FLOAT.getValue() != 6) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.DOUBLE.getValue() != 7) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.DECIMAL.getValue() != 8) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.CHAR.getValue() != 9) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.VARCHAR.getValue() != 10) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.LONGVARCHAR.getValue() != 11) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.DATE.getValue() != 12) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.TIME.getValue() != 13) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.TIMESTAMP.getValue() != 14) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.BINARY.getValue() != 15) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.VARBINARY.getValue() != 16) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.LONGVARBINARY.getValue() != 17) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.BLOB.getValue() != 18) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.CLOB.getValue() != 19) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.SQLXML.getValue() != 20) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.ARRAY.getValue() != 21) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.MAP.getValue() != 22) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.STRUCT.getValue() != 23) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.NULLTYPE.getValue() != 24) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.JSON.getValue() != 25) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.JAVA_OBJECT.getValue() != 26) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.OTHER.getValue() != 27) {
      throw new AssertionError("typeId mismatch");
    }
    if (SnappyType.findByValue(28) != null) {
      throw new AssertionError("unhandled typeId 29");
    }
  }

  /**
   * this should exactly match ColumnValue.standardSchemeWriteValue
   */
  public final void writeStandardScheme(final BitSet changedColumns,
      TProtocol oprot) throws TException {
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
      final int snappyType = getType(index);
      // check more common types first
      switch (snappyType) {
        case 9: // CHAR
        case 10: // VARCHAR
        case 11: // LONGVARCHAR
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
        case 12: // DATE
          oprot.writeFieldBegin(ColumnValue.DATE_VAL_FIELD_DESC);
          oprot.writeI64(primitives[offset]);
          break;
        case 14: // TIMESTAMP
          oprot.writeFieldBegin(ColumnValue.TIMESTAMP_VAL_FIELD_DESC);
          oprot.writeI64(primitives[offset]);
          break;
        case 7: // DOUBLE
          oprot.writeFieldBegin(ColumnValue.DOUBLE_VAL_FIELD_DESC);
          oprot.writeDouble(Double.longBitsToDouble(primitives[offset]));
          break;
        case 8: // DECIMAL
          oprot.writeFieldBegin(ColumnValue.DECIMAL_VAL_FIELD_DESC);
          BigDecimal decimal = (BigDecimal)nonPrimitives[(int)primitives[offset]];
          Converters.getDecimal(decimal).write(oprot);
          break;
        case 6: // FLOAT
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
        case 13: // TIME
          oprot.writeFieldBegin(ColumnValue.TIME_VAL_FIELD_DESC);
          oprot.writeI64(primitives[offset]);
          break;
        case 19: // CLOB
        case 20: // SQLXML
        case 25: // JSON
          oprot.writeFieldBegin(ColumnValue.CLOB_VAL_FIELD_DESC);
          ClobChunk clob = (ClobChunk)nonPrimitives[(int)primitives[offset]];
          clob.write(oprot);
          break;
        case 18: // BLOB
          oprot.writeFieldBegin(ColumnValue.BLOB_VAL_FIELD_DESC);
          BlobChunk blob = (BlobChunk)nonPrimitives[(int)primitives[offset]];
          blob.write(oprot);
          break;
        case 15: // BINARY
        case 16: // VARBINARY
        case 17: // LONGVARBINARY
          oprot.writeFieldBegin(ColumnValue.BINARY_VAL_FIELD_DESC);
          byte[] bytes = (byte[])nonPrimitives[(int)primitives[offset]];
          oprot.writeBinary(ByteBuffer.wrap(bytes));
          break;
        case 21: // ARRAY
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
        case 22: // MAP
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
        case 23: // STRUCT
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
        case 24: // NULLTYPE
          oprot.writeFieldBegin(ColumnValue.NULL_VAL_FIELD_DESC);
          // not-null case since for null type will be -ve
          oprot.writeBool(false);
          break;
        case 26: // JAVA_OBJECT
          oprot.writeFieldBegin(ColumnValue.JAVA_VAL_FIELD_DESC);
          byte[] objBytes = ((Converters.JavaObjectWrapper)nonPrimitives[
              (int)primitives[offset]]).getSerialized();
          oprot.writeBinary(ByteBuffer.wrap(objBytes));
          break;
        default:
          // check for null
          if (snappyType < 0) {
            oprot.writeFieldBegin(ColumnValue.NULL_VAL_FIELD_DESC);
            oprot.writeBool(true);
          } else {
            throw new TProtocolException("write: unhandled typeId=" + snappyType +
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
      throws TException {
    initialize(numFields);
    final long[] primitives = this.primitives;
    int nonPrimSize = 0;
    int offset = this.headerSize;
    for (int index = 0; index < numFields; index++, offset++) {
      iprot.readStructBegin();
      TField field = iprot.readFieldBegin();
      final ColumnValue._Fields setField =
          ColumnValue._Fields.findByThriftId(field.id);
      SnappyType nullType = null;
      if (setField != null) {
        // check more common types first
        switch (setField) {
          case STRING_VAL:
            if (field.type == ColumnValue.STRING_VAL_FIELD_DESC.type) {
              ensureNonPrimCapacity(nonPrimSize);
              String str = iprot.readString();
              primitives[offset] = nonPrimSize;
              nonPrimitives[nonPrimSize++] = str;
              setType(index, SnappyType.VARCHAR.getValue());
            } else {
              nullType = SnappyType.VARCHAR;
            }
            break;
          case I32_VAL:
            if (field.type == ColumnValue.I32_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readI32());
              setType(index, SnappyType.INTEGER.getValue());
            } else {
              nullType = SnappyType.INTEGER;
            }
            break;
          case I64_VAL:
            if (field.type == ColumnValue.I64_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readI64());
              setType(index, SnappyType.BIGINT.getValue());
            } else {
              nullType = SnappyType.BIGINT;
            }
            break;
          case DATE_VAL:
            if (field.type == ColumnValue.DATE_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readI64());
              setType(index, SnappyType.DATE.getValue());
            } else {
              nullType = SnappyType.DATE;
            }
            break;
          case TIMESTAMP_VAL:
            if (field.type == ColumnValue.TIMESTAMP_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readI64());
              setType(index, SnappyType.TIMESTAMP.getValue());
            } else {
              nullType = SnappyType.TIMESTAMP;
            }
            break;
          case DOUBLE_VAL:
            if (field.type == ColumnValue.DOUBLE_VAL_FIELD_DESC.type) {
              setPrimLong(offset, Double.doubleToLongBits(iprot.readDouble()));
              setType(index, SnappyType.DOUBLE.getValue());
            } else {
              nullType = SnappyType.DOUBLE;
            }
            break;
          case DECIMAL_VAL:
            if (field.type == ColumnValue.DECIMAL_VAL_FIELD_DESC.type) {
              ensureNonPrimCapacity(nonPrimSize);
              Decimal decimal = new Decimal();
              decimal.read(iprot);
              primitives[offset] = nonPrimSize;
              nonPrimitives[nonPrimSize++] = Converters
                  .getBigDecimal(decimal);
              setType(index, SnappyType.DECIMAL.getValue());
            } else {
              nullType = SnappyType.DECIMAL;
            }
            break;
          case FLOAT_VAL:
            if (field.type == ColumnValue.FLOAT_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readI32());
              setType(index, SnappyType.FLOAT.getValue());
            } else {
              nullType = SnappyType.FLOAT;
            }
            break;
          case I16_VAL:
            if (field.type == ColumnValue.I16_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readI16());
              setType(index, SnappyType.SMALLINT.getValue());
            } else {
              nullType = SnappyType.SMALLINT;
            }
            break;
          case BOOL_VAL:
            if (field.type == ColumnValue.BOOL_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readBool() ? 1L : 0L);
              setType(index, SnappyType.BOOLEAN.getValue());
            } else {
              nullType = SnappyType.BOOLEAN;
            }
            break;
          case BYTE_VAL:
            if (field.type == ColumnValue.BYTE_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readByte());
              setType(index, SnappyType.TINYINT.getValue());
            } else {
              nullType = SnappyType.TINYINT;
            }
            break;
          case TIME_VAL:
            if (field.type == ColumnValue.TIME_VAL_FIELD_DESC.type) {
              setPrimLong(offset, iprot.readI64());
              setType(index, SnappyType.TIME.getValue());
            } else {
              nullType = SnappyType.TIME;
            }
            break;
          case CLOB_VAL:
            if (field.type == ColumnValue.CLOB_VAL_FIELD_DESC.type) {
              ensureNonPrimCapacity(nonPrimSize);
              ClobChunk clob = new ClobChunk();
              clob.read(iprot);
              primitives[offset] = nonPrimSize;
              nonPrimitives[nonPrimSize++] = clob;
              setType(index, SnappyType.CLOB.getValue());
              hasLobs = true;
            } else {
              nullType = SnappyType.CLOB;
            }
            break;
          case BLOB_VAL:
            if (field.type == ColumnValue.BLOB_VAL_FIELD_DESC.type) {
              ensureNonPrimCapacity(nonPrimSize);
              BlobChunk blob = new BlobChunk();
              blob.read(iprot);
              primitives[offset] = nonPrimSize;
              nonPrimitives[nonPrimSize++] = blob;
              setType(index, SnappyType.BLOB.getValue());
              hasLobs = true;
            } else {
              nullType = SnappyType.BLOB;
            }
            break;
          case BINARY_VAL:
            if (field.type == ColumnValue.BINARY_VAL_FIELD_DESC.type) {
              ensureNonPrimCapacity(nonPrimSize);
              byte[] bytes = ThriftUtils.toBytes(iprot.readBinary());
              primitives[offset] = nonPrimSize;
              nonPrimitives[nonPrimSize++] = bytes;
              setType(index, SnappyType.VARBINARY.getValue());
            } else {
              nullType = SnappyType.VARBINARY;
            }
            break;
          case NULL_VAL:
            setType(index, iprot.readBool() ? -SnappyType.NULLTYPE.getValue()
                : SnappyType.NULLTYPE.getValue());
            break;
          case ARRAY_VAL:
          case STRUCT_VAL:
            SnappyType fieldSQLType;
            byte fieldType;
            if (setField == ColumnValue._Fields.ARRAY_VAL) {
              fieldSQLType = SnappyType.ARRAY;
              fieldType = ColumnValue.ARRAY_VAL_FIELD_DESC.type;
            } else {
              fieldSQLType = SnappyType.STRUCT;
              fieldType = ColumnValue.STRUCT_VAL_FIELD_DESC.type;
            }
            if (field.type == fieldType) {
              ensureNonPrimCapacity(nonPrimSize);
              org.apache.thrift.protocol.TList alist = iprot.readListBegin();
              final int listSize = alist.size;
              final ArrayList<ColumnValue> values = new ArrayList<>(listSize);
              for (int i = 0; i < listSize; i++) {
                ColumnValue cv = new ColumnValue();
                cv.read(iprot);
                values.add(cv);
              }
              iprot.readListEnd();
              primitives[offset] = nonPrimSize;
              nonPrimitives[nonPrimSize++] = values;
              setType(index, fieldSQLType.getValue());
            } else {
              nullType = fieldSQLType;
            }
            break;
          case MAP_VAL:
            if (field.type == ColumnValue.MAP_VAL_FIELD_DESC.type) {
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
            } else {
              nullType = SnappyType.MAP;
            }
            break;
          case JAVA_VAL:
            if (field.type == ColumnValue.JAVA_VAL_FIELD_DESC.type) {
              ensureNonPrimCapacity(nonPrimSize);
              byte[] serializedBytes = ThriftUtils.toBytes(
                  iprot.readBinary());
              primitives[offset] = nonPrimSize;
              nonPrimitives[nonPrimSize++] = new Converters.JavaObjectWrapper(
                  serializedBytes);
              setType(index, SnappyType.JAVA_OBJECT.getValue());
            } else {
              nullType = SnappyType.JAVA_OBJECT;
            }
            break;
          default:
            throw ClientSharedUtils.newRuntimeException(
                "unknown column type = " + setField, null);
        }
        if (nullType != null) {
          // treat like null value
          org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          setType(index, -nullType.getValue());
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
          sqlTypeId = SnappyType.FLOAT.getValue();
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
          fieldVal = cv.getFieldValue();
          sqlTypeId = SnappyType.CLOB.getValue();
          hasLobs = true;
          break;
        case BLOB_VAL:
          fieldVal = cv.getFieldValue();
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
          fieldVal = serializedBytes != null ? new Converters.JavaObjectWrapper(
              serializedBytes) : null;
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
    // mark all non-primitives as null
    setDefaultNullability();
    // free the non-primitives to help GC if possible
    Arrays.fill(this.nonPrimitives, null);
    // skip primitive values since it doesn't hurt
    // and we need the non-primitive indexes
    this.hash = 0;
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
      final int snappyType = getType(index);
      sb.append("TYPE=").append(SnappyType.findByValue(Math.abs(snappyType)))
          .append(" VALUE=");
      // check more common types first
      switch (snappyType) {
        case 1: // BOOLEAN
          sb.append(primitives[offset] != 0);
          break;
        case 2: // TINYINT
        case 3: // SMALLINT
        case 4: // INTEGER
        case 5: // BIGINT
          sb.append(primitives[offset]);
          break;
        case 6: // FLOAT
          sb.append(Float.intBitsToFloat((int)primitives[offset]));
          break;
        case 7: // DOUBLE
          sb.append(Double.longBitsToDouble(primitives[offset]));
          break;
        case 8: // DECIMAL
        case 9: // CHAR
        case 10: // VARCHAR
        case 11: // LONGVARCHAR
        case 18: // BLOB
        case 19: // CLOB
        case 20: // SQLXML
        case 21: // ARRAY
        case 22: // MAP
        case 23: // STRUCT
        case 25: // JSON
        case 26: // JAVA_OBJECT
          Object o = nonPrimitives[(int)primitives[offset]];
          if (o != null) {
            sb.append("(type=").append(o.getClass().getName()).append(')');
          }
          sb.append(nonPrimitives[(int)primitives[offset]]);
          break;
        case 12: // DATE
          sb.append(primitives[offset]).append(',');
          break;
        case 13: // TIME
          sb.append(primitives[offset]).append(',');
          break;
        case 14: // TIMESTAMP
          sb.append(primitives[offset]).append(',');
          break;
        case 15: // BINARY
        case 16: // VARBINARY
        case 17: // LONGVARBINARY
          byte[] bytes = (byte[])nonPrimitives[(int)primitives[offset]];
          TBaseHelper.toString(ByteBuffer.wrap(bytes), sb);
          break;
        case 24: // NULLTYPE
          sb.append("NullType=false");
          break;
        default:
          // check for null
          if (snappyType < 0) {
            sb.append("NULL");
          } else {
            sb.append("UNKNOWN");
          }
          break;
      }
    }
    return sb.toString();
  }
}
