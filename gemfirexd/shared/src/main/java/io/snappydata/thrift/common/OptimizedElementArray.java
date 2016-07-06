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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.Limits;
import io.snappydata.thrift.*;

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
 */
public class OptimizedElementArray {

  protected static final int DEFAULT_CAPACITY = 4;

  protected byte[] primitives;
  protected BitSet primNulls;

  protected Object[] nonPrimitives;

  /**
   * Given an index, provides its position in one of {@link #primitives} and
   * {@link #nonPrimitives} arrays. When the result is negative, it indicates
   * the negative of (index + 1) in {@link #nonPrimitives} list while for
   * positive or zero it is the index in {@link #primitives} list calculated as
   * byte position i.e. (value/8) is the index while (value%8) is the offset.
   */
  protected int[] positionMap;

  protected byte[] types;

  protected int primSize;
  protected int nonPrimSize;
  protected int size;
  protected transient int hash;

  protected OptimizedElementArray() {
    this(true);
  }

  protected OptimizedElementArray(boolean useTypes) {
    this.primitives = new byte[DEFAULT_CAPACITY * 4];
    this.nonPrimitives = new Object[DEFAULT_CAPACITY];
    this.positionMap = new int[DEFAULT_CAPACITY * 2];
    if (useTypes) {
      this.types = new byte[DEFAULT_CAPACITY * 2];
    }
  }

  protected OptimizedElementArray(OptimizedElementArray other) {
    this(other, true);
  }

  protected OptimizedElementArray(OptimizedElementArray other,
      boolean copyValues) {
    byte[] prims = other.primitives;
    Object[] nonPrims = other.nonPrimitives;
    byte[] types = other.types;
    if (prims != null) {
      this.primitives = copyValues ? prims.clone() : new byte[prims.length];
    }
    if (nonPrims != null) {
      this.nonPrimitives = copyValues ? nonPrims.clone()
          : new Object[nonPrims.length];
    }
    this.positionMap = other.positionMap.clone();
    if (types != null) {
      this.types = types.clone();
    }
    this.primSize = other.primSize;
    this.nonPrimSize = other.nonPrimSize;
    this.size = other.size;
  }

  /**
   * Initialize the array for given {@link SnappyType}s.
   * 
   * @param types
   *          the array of element types ordered by their position
   */
  public OptimizedElementArray(SnappyType[] types, boolean useTypes) {
    int index = 0;
    int primitivePosition = 0, nonPrimitivePosition = 1;
    this.positionMap = new int[types.length];
    if (useTypes) {
      this.types = new byte[types.length];
    }

    for (SnappyType type : types) {
      if (useTypes) {
        this.types[index] = (byte)type.getValue();
      }
      switch (type) {
        case INTEGER:
        case REAL:
          this.positionMap[index++] = primitivePosition;
          primitivePosition += 4;
          break;
        case BIGINT:
        case DOUBLE:
        case FLOAT:
          this.positionMap[index++] = primitivePosition;
          primitivePosition += 8;
          break;
        case BOOLEAN:
        case TINYINT:
        case NULLTYPE:
          this.positionMap[index++] = primitivePosition;
          primitivePosition++;
          break;
        case SMALLINT:
          this.positionMap[index++] = primitivePosition;
          primitivePosition += 2;
          break;
        default:
          this.positionMap[index++] = -nonPrimitivePosition;
          nonPrimitivePosition++;
          break;
      }
    }
    if (primitivePosition > 0) {
      this.primSize = primitivePosition;
      this.primitives = new byte[primitivePosition];
    }
    if (nonPrimitivePosition > 1) {
      this.nonPrimSize = nonPrimitivePosition - 1;
      this.nonPrimitives = new Object[this.nonPrimSize];
    }
    this.size = index;
  }

  /**
   * Initialize the array for given {@link ColumnDescriptor}s.
   * 
   * @param metadata
   *          the list of {@link ColumnDescriptor}s ordered by their position
   * @param useTypes if true then also store the {@link SnappyType}s
   *        explicitly else the types will be tracked separately elsewhere and
   *        this class will expect proper getter/setter methods to be invoked
   */
  public OptimizedElementArray(final List<ColumnDescriptor> metadata,
      boolean useTypes) {
    this(getTypes(metadata), useTypes);
  }

  public static SnappyType[] getTypes(List<ColumnDescriptor> metadata) {
    final SnappyType[] types = new SnappyType[metadata.size()];
    int index = 0;
    for (ColumnDescriptor cd : metadata) {
      types[index++] = cd.type;
    }
    return types;
  }

  public final SnappyType getSQLType(int index) {
    return SnappyType.findByValue(this.types[index]);
  }

  public final boolean getBoolean(int index) {
    return getByte(index) != 0;
  }

  public final boolean isNull(int index) {
    int pos = this.positionMap[index];
    if (pos < 0) {
      return (this.nonPrimitives[-pos - 1] == null);
    }
    else {
      return this.primNulls != null && this.primNulls.get(index);
    }
  }

  public final byte getByte(int index) {
    return this.primitives[this.positionMap[index]];
  }

  public final short getShort(int index) {
    final byte[] prims = this.primitives;
    int primIndex = this.positionMap[index];
    int result = prims[primIndex++] & 0xff;
    return (short)((result << 8) | (prims[primIndex] & 0xff));
  }

  public final int getInt(int index) {
    final byte[] prims = this.primitives;
    int primIndex = this.positionMap[index];
    int result = prims[primIndex++] & 0xff;
    result = (result << 8) | (prims[primIndex++] & 0xff);
    result = (result << 8) | (prims[primIndex++] & 0xff);
    return ((result << 8) | (prims[primIndex] & 0xff));
  }

  public final long getLong(int index) {
    final byte[] prims = this.primitives;
    int primIndex = this.positionMap[index];
    long result = prims[primIndex++] & 0xff;
    result = (result << 8) | (prims[primIndex++] & 0xff);
    result = (result << 8) | (prims[primIndex++] & 0xff);
    result = (result << 8) | (prims[primIndex++] & 0xff);
    result = (result << 8) | (prims[primIndex++] & 0xff);
    result = (result << 8) | (prims[primIndex++] & 0xff);
    result = (result << 8) | (prims[primIndex++] & 0xff);
    return ((result << 8) | (prims[primIndex] & 0xff));
  }

  public final float getFloat(int index) {
    return Float.intBitsToFloat(getInt(index));
  }

  public final double getDouble(int index) {
    return Double.longBitsToDouble(getLong(index));
  }

  public final Object getObject(int index) {
    return this.nonPrimitives[(-this.positionMap[index]) - 1];
  }

  public final TIntArrayList requiresLobFinalizers() {
    final byte[] types = this.types;
    final Object[] nonPrimitives = this.nonPrimitives;
    final int[] positionMap = this.positionMap;
    final int size = this.size;
    TIntArrayList lobIndices = null;
    int lobIndex;
    byte type;
    final int blobType = SnappyType.BLOB.getValue();
    final int clobType = SnappyType.CLOB.getValue();
    for (int index = 0; index < size; index++) {
      type = types[index];
      if (type == blobType) {
        lobIndex = (-positionMap[index]) - 1;
        BlobChunk chunk = (BlobChunk)nonPrimitives[lobIndex];
        if (chunk != null && chunk.isSetLobId()) {
          if (lobIndices == null) {
            lobIndices = new TIntArrayList(4);
          }
          lobIndices.add(lobIndex);
        }
      }
      else if (type == clobType) {
        lobIndex = (-positionMap[index]) - 1;
        ClobChunk chunk = (ClobChunk)nonPrimitives[lobIndex];
        if (chunk != null && chunk.isSetLobId()) {
          if (lobIndices == null) {
            lobIndices = new TIntArrayList(4);
          }
          lobIndices.add(-lobIndex - 1);
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
      if (lobIndex >= 0) {
        nonPrimitives[lobIndex + 1] = createLobFinalizer
            .execute((BlobChunk)nonPrimitives[lobIndex]);
      }
      else {
        lobIndex = -lobIndex - 1;
        nonPrimitives[lobIndex + 1] = createLobFinalizer
            .execute((ClobChunk)nonPrimitives[lobIndex]);
      }
    }
  }

  public final BlobChunk getBlobChunk(int index, boolean clearFinalizer) {
    final int lobIndex = (-this.positionMap[index]) - 1;
    final BlobChunk chunk = (BlobChunk)this.nonPrimitives[lobIndex];
    if (clearFinalizer && chunk != null && chunk.isSetLobId()) {
      final FinalizeObject finalizer =
          (FinalizeObject)this.nonPrimitives[lobIndex + 1];
      if (finalizer != null) {
        finalizer.clearAll();
        this.nonPrimitives[lobIndex + 1] = null;
      }
    }
    return chunk;
  }

  public final ClobChunk getClobChunk(int index, boolean clearFinalizer) {
    final int lobIndex = (-this.positionMap[index]) - 1;
    final ClobChunk chunk = (ClobChunk)this.nonPrimitives[lobIndex];
    if (clearFinalizer && chunk != null && chunk.isSetLobId()) {
      final FinalizeObject finalizer =
          (FinalizeObject)this.nonPrimitives[lobIndex + 1];
      if (finalizer != null) {
        finalizer.clearAll();
        this.nonPrimitives[lobIndex + 1] = null;
      }
    }
    return chunk;
  }

  protected final void setType(int index, SnappyType sqlType) {
    if (this.types != null && this.types[index] == 0) {
      this.types[index] = (byte)sqlType.getValue();
    }
  }

  public final void setBoolean(int index, boolean value) {
    this.primitives[this.positionMap[index]] = (value ? (byte)1 : 0);
  }

  protected final void setPrimBoolean(int primIndex, boolean value) {
    this.primitives[primIndex] = (value ? (byte)1 : 0);
  }

  public final void setByte(int index, byte value) {
    this.primitives[this.positionMap[index]] = value;
  }

  protected final void setPrimByte(int primIndex, byte value) {
    this.primitives[primIndex] = value;
  }

  public final void setNull(int index) {
    int pos = this.positionMap[index];
    if (pos < 0) {
      this.nonPrimitives[-pos - 1] = null;
    }
    else {
      if (this.primNulls == null) {
        this.primNulls = new BitSet(this.positionMap.length);
      }
      this.primNulls.set(index);
    }
  }

  public final void setShort(int index, short value) {
    setPrimShort(this.positionMap[index], value);
  }

  protected final void setPrimShort(int primIndex, short value) {
    final byte[] prims = this.primitives;
    prims[primIndex++] = (byte)((value >>> 8) & 0xff);
    prims[primIndex] = (byte)(value & 0xff);
  }

  public final void setInt(int index, int value) {
    setPrimInt(this.positionMap[index], value);
  }

  protected final void setPrimInt(int primIndex, int value) {
    final byte[] prims = this.primitives;
    prims[primIndex++] = (byte)((value >>> 24) & 0xff);
    prims[primIndex++] = (byte)((value >>> 16) & 0xff);
    prims[primIndex++] = (byte)((value >>> 8) & 0xff);
    prims[primIndex] = (byte)(value & 0xff);
  }

  public final void setLong(int index, long value) {
    setPrimLong(this.positionMap[index], value);
  }

  protected final void setPrimLong(int primIndex, long value) {
    final byte[] prims = this.primitives;
    prims[primIndex++] = (byte)((value >>> 56) & 0xff);
    prims[primIndex++] = (byte)((value >>> 48) & 0xff);
    prims[primIndex++] = (byte)((value >>> 40) & 0xff);
    prims[primIndex++] = (byte)((value >>> 32) & 0xff);
    prims[primIndex++] = (byte)((value >>> 24) & 0xff);
    prims[primIndex++] = (byte)((value >>> 16) & 0xff);
    prims[primIndex++] = (byte)((value >>> 8) & 0xff);
    prims[primIndex] = (byte)(value & 0xff);
  }

  public final void setFloat(int index, float value) {
    setPrimInt(this.positionMap[index], Float.floatToIntBits(value));
  }

  public final void setDouble(int index, double value) {
    setPrimLong(this.positionMap[index], Double.doubleToLongBits(value));
  }

  public final void setObject(int index, Object value, SnappyType type) {
    this.nonPrimitives[(-this.positionMap[index]) - 1] = value;
    // we force change the type below for non-primitives which will work
    // fine since conversions will be changed on server
    if (this.types != null) {
      this.types[index] = (byte)type.getValue();
    }
  }

  public final void setInto(int index, ColumnValue cv) throws IOException {
    if (this.types != null) {
      cv.clear();
      // check for primitive null
      if (this.primNulls != null && this.primNulls.get(index)) {
        cv.setNull_val(true);
        return;
      }
      final SnappyType sqlType = SnappyType.findByValue(this.types[index]);
      assert sqlType != null : "setInto: unhandled type=" + this.types[index];
      switch (sqlType) {
        case CHAR:
        case VARCHAR:
        case LONGVARCHAR:
          String str = (String)getObject(index);
          if (str != null) {
            cv.setString_val(str);
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case INTEGER:
          cv.setI32_val(getInt(index));
          break;
        case BIGINT:
          cv.setI64_val(getLong(index));
          break;
        case DECIMAL:
          BigDecimal decimal = (BigDecimal)getObject(index);
          if (decimal != null) {
            cv.setDecimal_val(Converters.getDecimal(decimal));
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case SMALLINT:
          cv.setI16_val(getShort(index));
          break;
        case TINYINT:
          cv.setByte_val(getByte(index));
          break;
        case BOOLEAN:
          cv.setBool_val(getBoolean(index));
          break;
        case DATE:
          Date date = (Date)getObject(index);
          if (date != null) {
            cv.setDate_val(Converters.getDateTime(date));
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case TIMESTAMP:
          java.sql.Timestamp ts = (java.sql.Timestamp)getObject(index);
          if (ts != null) {
            cv.setTimestamp_val(Converters.getTimestamp(ts));
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case TIME:
          Time time = (Time)getObject(index);
          if (time != null) {
            cv.setTime_val(Converters.getDateTime(time));
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case REAL:
          cv.setFloat_val(getInt(index));
          break;
        case DOUBLE:
        case FLOAT:
          cv.setDouble_val(getDouble(index));
          break;
        case BINARY:
        case VARBINARY:
        case LONGVARBINARY:
          byte[] bytes = (byte[])getObject(index);
          if (bytes != null) {
            cv.setBinary_val(bytes);
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case JAVA_OBJECT:
          Object o = getObject(index);
          if (o != null) {
            cv.setJava_val(Converters.getJavaObjectAsBytes(o));
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case JSON:
          JSONObject jsonObject = (JSONObject)getObject(index);
          if (jsonObject != null) {
            cv.setJson_val(jsonObject);
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case CLOB:
          ClobChunk clob = (ClobChunk)getObject(index);
          if (clob != null) {
            cv.setClob_val(clob);
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case BLOB:
          BlobChunk blob = (BlobChunk)getObject(index);
          if (blob != null) {
            cv.setBlob_val(blob);
          }
          else {
            cv.setNull_val(true);
          }
          break;
        default:
          throw new IOException("setInto: unhandled type=" + sqlType);
      }
    } else {
      throw new IOException("setInfo: unexpected invocation for types=null");
    }
  }

  public final void addColumnValue(ColumnValue cv) throws SQLException {
    ensureCapacity();
    final ColumnValue._Fields setField = cv.getSetField();
    if (setField != null) {
      // check for primitive types first
      switch (setField) {
        case I32_VAL:
          ensurePrimCapacity(4);
          this.positionMap[this.size] = this.primSize;
          setPrimInt(this.primSize, cv.getI32_val());
          setType(this.size, SnappyType.INTEGER);
          this.size++;
          this.primSize += 4;
          break;
        case I64_VAL:
          ensurePrimCapacity(8);
          this.positionMap[this.size] = this.primSize;
          setPrimLong(this.primSize, cv.getI64_val());
          setType(this.size, SnappyType.BIGINT);
          this.size++;
          this.primSize += 8;
          break;
        case DOUBLE_VAL:
          ensurePrimCapacity(8);
          this.positionMap[this.size] = this.primSize;
          setPrimLong(this.primSize,
              Double.doubleToLongBits(cv.getDouble_val()));
          setType(this.size, SnappyType.DOUBLE);
          this.size++;
          this.primSize += 8;
          break;
        case STRING_VAL:
          ensureNonPrimCapacity();
          String str = (String)cv.getFieldValue();
          if (str != null) {
            this.nonPrimitives[this.nonPrimSize++] = str;
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, str.length() <= Limits.DB2_VARCHAR_MAXWIDTH
                ? SnappyType.VARCHAR : SnappyType.CLOB);
            this.size++;
          }
          break;
        case DECIMAL_VAL:
          ensureNonPrimCapacity();
          Decimal decimal = (Decimal)cv.getFieldValue();
          if (decimal != null) {
            this.nonPrimitives[this.nonPrimSize++] = Converters
                .getBigDecimal(decimal);
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.DECIMAL);
            this.size++;
          }
          break;
        case TIMESTAMP_VAL:
          ensureNonPrimCapacity();
          Timestamp ts = (Timestamp)cv.getFieldValue();
          if (ts != null) {
            this.nonPrimitives[this.nonPrimSize++] = Converters.getTimestamp(ts);
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.TIMESTAMP);
            this.size++;
          }
          break;
        case DATE_VAL:
          ensureNonPrimCapacity();
          DateTime date = (DateTime)cv.getFieldValue();
          if (date != null) {
            this.nonPrimitives[this.nonPrimSize++] = new java.sql.Date(
                date.secsSinceEpoch * 1000L);
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.DATE);
            this.size++;
          }
          break;
        case BOOL_VAL:
          ensurePrimCapacity(1);
          this.positionMap[this.size] = this.primSize;
          setPrimBoolean(this.primSize, cv.getBool_val());
          setType(this.size, SnappyType.BOOLEAN);
          this.size++;
          this.primSize++;
          break;
        case BYTE_VAL:
          ensurePrimCapacity(1);
          this.positionMap[this.size] = this.primSize;
          setPrimByte(this.primSize, cv.getByte_val());
          setType(this.size, SnappyType.TINYINT);
          this.size++;
          this.primSize++;
          break;
        case FLOAT_VAL:
          ensurePrimCapacity(4);
          this.positionMap[this.size] = this.primSize;
          setPrimInt(this.primSize, cv.getFloat_val());
          setType(this.size, SnappyType.REAL);
          this.size++;
          this.primSize += 4;
          break;
        case I16_VAL:
          ensurePrimCapacity(2);
          this.positionMap[this.size] = this.primSize;
          setPrimShort(this.primSize, cv.getI16_val());
          setType(this.size, SnappyType.SMALLINT);
          this.size++;
          this.primSize += 2;
          break;
        case TIME_VAL:
          ensureNonPrimCapacity();
          DateTime time = (DateTime)cv.getFieldValue();
          if (time != null) {
            this.nonPrimitives[this.nonPrimSize++] = new java.sql.Time(
                time.secsSinceEpoch * 1000L);
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.TIME);
            this.size++;
          }
          break;
        case BINARY_VAL:
          ensureNonPrimCapacity();
          byte[] bytes = cv.getBinary_val();
          if (bytes != null) {
            this.nonPrimitives[this.nonPrimSize++] = bytes;
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, bytes.length <= Limits.DB2_VARCHAR_MAXWIDTH
                ? SnappyType.VARBINARY : SnappyType.BLOB);
            this.size++;
          }
          break;
        case BLOB_VAL:
          ensureNonPrimCapacity();
          BlobChunk blob = (BlobChunk)cv.getFieldValue();
          if (blob != null) {
            this.nonPrimitives[this.nonPrimSize++] = blob;
            this.positionMap[this.size] = -this.nonPrimSize;
            // also make space for finalizer of BlobChunk if required
            if (blob.isSetLobId()) {
              ensureNonPrimCapacity();
              this.nonPrimSize++;
            }
            setType(this.size, SnappyType.BLOB);
            this.size++;
          }
          break;
        case CLOB_VAL:
          ensureNonPrimCapacity();
          ClobChunk clob = (ClobChunk)cv.getFieldValue();
          if (clob != null) {
            this.nonPrimitives[this.nonPrimSize++] = clob;
            this.positionMap[this.size] = -this.nonPrimSize;
            // also make space for finalizer of ClobChunk if required
            if (clob.isSetLobId()) {
              ensureNonPrimCapacity();
              this.nonPrimSize++;
            }
            setType(this.size, SnappyType.CLOB);
            this.size++;
          }
          break;
        case ARRAY_VAL:
          ensureNonPrimCapacity();
          @SuppressWarnings("unchecked")
          List<ColumnValue> arr = (List<ColumnValue>)cv.getFieldValue();
          if (arr != null) {
            this.nonPrimitives[this.nonPrimSize++] = arr;
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.ARRAY);
            this.size++;
          }
          break;
        case MAP_VAL:
          ensureNonPrimCapacity();
          @SuppressWarnings("unchecked")
          Map<ColumnValue, ColumnValue> map =
              (Map<ColumnValue, ColumnValue>)cv.getFieldValue();
          if (map != null) {
            this.nonPrimitives[this.nonPrimSize++] = map;
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.MAP);
            this.size++;
          }
          break;
        case STRUCT_VAL:
          ensureNonPrimCapacity();
          @SuppressWarnings("unchecked")
          List<ColumnValue> struct = (List<ColumnValue>)cv.getFieldValue();
          if (struct != null) {
            this.nonPrimitives[this.nonPrimSize++] = struct;
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.STRUCT);
            this.size++;
          }
          break;
        case JSON_VAL:
          ensureNonPrimCapacity();
          JSONObject jsonObj = (JSONObject)cv.getFieldValue();
          if (jsonObj != null) {
            this.nonPrimitives[this.nonPrimSize++] = jsonObj;
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.JSON);
            this.size++;
          }
          break;
        case JAVA_VAL:
          ensureNonPrimCapacity();
          byte[] serializedBytes = cv.getJava_val();
          if (serializedBytes != null) {
            this.nonPrimitives[this.nonPrimSize++] = Converters.getJavaObject(
                serializedBytes, this.size);
            this.positionMap[this.size] = -this.nonPrimSize;
            setType(this.size, SnappyType.JAVA_OBJECT);
            this.size++;
          }
          break;
        case NULL_VAL:
          ensureNonPrimCapacity();
          this.nonPrimitives[this.nonPrimSize++] = null;
          this.positionMap[this.size] = -this.nonPrimSize;
          setType(this.size, SnappyType.NULLTYPE);
          this.size++;
          break;
        default:
          throw ClientSharedUtils.newRuntimeException("unknown column value: " +
              (cv.getFieldValue() != null ? cv : "null"), null);
      }
    } else {
      // treat like null value
      ensureNonPrimCapacity();
      this.nonPrimitives[this.nonPrimSize++] = null;
      this.positionMap[this.size] = -this.nonPrimSize;
      setType(this.size, SnappyType.NULLTYPE);
      this.size++;
    }
  }

  protected final void ensureCapacity() {
    final int capacity = this.positionMap.length;
    if (this.size >= capacity) {
      final int newCapacity = capacity + (capacity >>> 1);
      int[] newMap = new int[newCapacity];
      System.arraycopy(this.positionMap, 0, newMap, 0, capacity);
      this.positionMap = newMap;
      if (this.types != null) {
        byte[] newTypes = new byte[newCapacity];
        System.arraycopy(this.types, 0, newTypes, 0, capacity);
        this.types = newTypes;
      }
    }
  }

  protected final void ensurePrimCapacity(int minCapacity) {
    if (this.primitives != null) {
      final int capacity = this.primitives.length;
      if ((this.primSize + minCapacity) > capacity) {
        final int newCapacity = capacity + (capacity >>> 1);
        byte[] newPrims = new byte[newCapacity];
        System.arraycopy(this.primitives, 0, newPrims, 0, capacity);
        this.primitives = newPrims;
      }
    }
    else {
      this.primitives = new byte[DEFAULT_CAPACITY * 4];
    }
  }

  protected final void ensureNonPrimCapacity() {
    if (this.nonPrimitives != null) {
      final int capacity = this.nonPrimitives.length;
      if (this.nonPrimSize >= capacity) {
        final int newCapacity = capacity + (capacity >>> 1);
        Object[] newNonPrims = new Object[newCapacity];
        System.arraycopy(this.nonPrimitives, 0, newNonPrims, 0, capacity);
        this.nonPrimitives = newNonPrims;
      }
    }
    else {
      this.nonPrimitives = new Object[DEFAULT_CAPACITY];
    }
  }

  public final int size() {
    return this.size;
  }

  public void clear() {
    Arrays.fill(this.positionMap, 0);
    Arrays.fill(this.nonPrimitives, null);
    this.primSize = 0;
    this.nonPrimSize = 0;
    this.size = 0;
  }

  @Override
  public int hashCode() {
    int h = this.hash;
    if (h != 0) {
      return h;
    }

    final int[] posMap = this.positionMap;
    if (posMap != null && posMap.length > 0) {
      for (int pos : posMap) {
        h = (31 * h) + pos;
      }
    }
    byte[] b = this.types;
    if (b != null && b.length > 0) {
      h = ResolverUtils.addBytesToHash(b, h);
    }
    b = this.primitives;
    if (b != null && b.length > 0) {
      h = ResolverUtils.addBytesToHash(b, h);
    }
    final Object[] nps = this.nonPrimitives;
    if (nps != null && nps.length > 0) {
      for (Object o : nps) {
        if (o != null) {
          h = (31 * h) + o.hashCode();
        }
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
    return Arrays.equals(this.positionMap, other.positionMap) &&
        Arrays.equals(this.types, other.types) &&
        Arrays.equals(this.primitives, other.primitives) &&
        Arrays.equals(this.nonPrimitives, other.nonPrimitives);
  }
}
