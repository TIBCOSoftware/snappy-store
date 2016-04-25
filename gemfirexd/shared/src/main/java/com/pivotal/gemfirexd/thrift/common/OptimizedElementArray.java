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

package com.pivotal.gemfirexd.thrift.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.Limits;
import com.pivotal.gemfirexd.thrift.*;

/**
 * A compact way to represent a set of primitive and non-primitive values. The
 * optimization is only useful when there a multiple primitive values
 * (int/byte/long/short/float/double/boolean) in the list.
 * <p>
 * This packs the primitives in a byte[] while non-primitives are packed in an
 * Object[] to minimize the memory overhead. Consequently it is not efficient to
 * directly iterate over this as a List. Users of this class should normally
 * find the type of the element at a position, and then invoke the appropriate
 * getter instead, e.g. if the type of element is {@link FieldType#INTEGER} then
 * use the getInteger() method.
 * <p>
 * Note that the getters and setters of this class do not do any type checks or
 * type conversions by design and will happily get/set garbage or throw
 * {@link IndexOutOfBoundsException} or {@link NullPointerException} assuming
 * that caller has done the requisite type checks.
 * 
 * @author swale
 * @since gfxd 1.1
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
   * Initialize the array for given {@link FieldType}s.
   * 
   * @param fieldTypes
   *          the list of element types ordered by their position
   */
  public OptimizedElementArray(List<FieldType> fieldTypes) {
    int index = 0;
    int primitivePosition = 0, nonPrimitivePosition = 1;
    final FieldType[] types = fieldTypes.toArray(new FieldType[fieldTypes
        .size()]);
    this.positionMap = new int[types.length];
    this.types = new byte[types.length];

    for (FieldType type : types) {
      this.types[index] = (byte)type.getValue();
      switch (type) {
        case INTEGER:
        case FLOAT:
          this.positionMap[index++] = primitivePosition;
          primitivePosition += 4;
          break;
        case LONG:
        case DOUBLE:
          this.positionMap[index++] = primitivePosition;
          primitivePosition += 8;
          break;
        case BOOLEAN:
        case BYTE:
          this.positionMap[index++] = primitivePosition;
          primitivePosition++;
          break;
        case SHORT:
        case CHAR:
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
   * Initialize the array for given {@link GFXDType}s.
   * 
   * @param types
   *          the array of element types ordered by their position
   */
  public OptimizedElementArray(GFXDType[] types, boolean useTypes) {
    int index = 0;
    int primitivePosition = 0, nonPrimitivePosition = 1;
    this.positionMap = new int[types.length];
    if (useTypes) {
      this.types = new byte[types.length];
    }

    for (GFXDType type : types) {
      if (useTypes) {
        this.types[index] = (byte)type.getValue();
      }
      switch (type) {
        case INTEGER:
        case FLOAT:
          this.positionMap[index++] = primitivePosition;
          primitivePosition += 4;
          break;
        case BIGINT:
        case DOUBLE:
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
   * Initialize the array for given {@link GFXDType}s.
   * 
   * @param sqlTypes
   *          the list of element types ordered by their position
   * @param useTypes if true then also store the {@link GFXDType}s
   *        explicitly else the types will be tracked separately elsewhere and
   *        this class will expect proper getter/setter methods to be invoked
   * @param forSQLTypes
   *          ignored; only to distinguish from other constructors
   */
  public OptimizedElementArray(List<GFXDType> sqlTypes, boolean useTypes,
      int forSQLTypes) {
    this(sqlTypes.toArray(new GFXDType[sqlTypes.size()]), useTypes);
  }

  /**
   * Initialize the array for given {@link ColumnDescriptor}s.
   * 
   * @param metadata
   *          the list of {@link ColumnDescriptor}s ordered by their position
   * @param useTypes if true then also store the {@link GFXDType}s
   *        explicitly else the types will be tracked separately elsewhere and
   *        this class will expect proper getter/setter methods to be invoked
   */
  public OptimizedElementArray(final List<ColumnDescriptor> metadata,
      boolean useTypes) {
    this(getTypes(metadata), useTypes);
  }

  public static GFXDType[] getTypes(List<ColumnDescriptor> metadata) {
    final GFXDType[] types = new GFXDType[metadata.size()];
    int index = 0;
    for (ColumnDescriptor cd : metadata) {
      types[index++] = cd.type;
    }
    return types;
  }

  public final FieldType getType(int index) {
    return FieldType.findByValue(this.types[index]);
  }

  public final GFXDType getSQLType(int index) {
    return GFXDType.findByValue(this.types[index]);
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

  public final char getChar(int index) {
    return (char)getShort(index);
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
    final int blobType = GFXDType.BLOB.getValue();
    final int clobType = GFXDType.CLOB.getValue();
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

  protected final void setType(int index, FieldType ftype) {
    if (this.types != null && this.types[index] == 0) {
      this.types[index] = (byte)ftype.getValue();
    }
  }

  protected final void setType(int index, GFXDType sqlType) {
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

  public final void setChar(int index, char value) {
    setPrimShort(this.positionMap[index], (short)value);
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

  public final void setObject(int index, Object value, GFXDType type) {
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
      final GFXDType sqlType = GFXDType.findByValue(this.types[index]);
      switch (sqlType) {
        case CHAR:
        case VARCHAR:
        case NCHAR:
        case NVARCHAR:
        case LONGVARCHAR:
        case LONGNVARCHAR:
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
            cv.setDate_val(new DateTime(date.getTime()));
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case TIMESTAMP:
          java.sql.Timestamp ts = (java.sql.Timestamp)getObject(index);
          if (ts != null) {
            Timestamp tsv = new Timestamp(ts.getTime());
            int nanos = ts.getNanos();
            if (nanos != 0) {
              tsv.setNanos(nanos);
            }
            cv.setTimestamp_val(tsv);
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case TIME:
          Time time = (Time)getObject(index);
          if (time != null) {
            cv.setTime_val(new DateTime(time.getTime()));
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case FLOAT:
          cv.setFloat_val(getInt(index));
          break;
        case DOUBLE:
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
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream os = new ObjectOutputStream(bos);
            os.writeObject(o);
            os.flush();
            cv.setJava_val(bos.toByteArray());
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case PDX_OBJECT:
          PDXObject pdxObject = (PDXObject)getObject(index);
          if (pdxObject != null) {
            cv.setPdx_val(pdxObject);
          }
          else {
            cv.setNull_val(true);
          }
          break;
        case JSON_OBJECT:
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
          throw new AssertionError("setInto: unhandled type=" + sqlType);
      }
    }
    else {
      throw new AssertionError("setInfo: unexpected invocation for types=null");
    }
  }

  public final void addFieldValue(FieldValue fv) throws SQLException {
    ensureCapacity();
    // check for primitive types first
    switch (fv.__isset_bitfield) {
      case (1 << FieldValue.__INT_VAL_ISSET_ID):
        ensurePrimCapacity(4);
        this.positionMap[this.size] = this.primSize;
        setPrimInt(this.primSize, fv.int_val);
        setType(this.size, FieldType.INTEGER);
        this.size++;
        this.primSize += 4;
        return;
      case (1 << FieldValue.__LONG_VAL_ISSET_ID):
        ensurePrimCapacity(8);
        this.positionMap[this.size] = this.primSize;
        setPrimLong(this.primSize, fv.long_val);
        setType(this.size, FieldType.LONG);
        this.size++;
        this.primSize += 8;
        return;
      case (1 << FieldValue.__DOUBLE_VAL_ISSET_ID):
        ensurePrimCapacity(8);
        this.positionMap[this.size] = this.primSize;
        setPrimLong(this.primSize, Double.doubleToLongBits(fv.double_val));
        setType(this.size, FieldType.DOUBLE);
        this.size++;
        this.primSize += 8;
        return;
      case (1 << FieldValue.__BOOL_VAL_ISSET_ID):
        ensurePrimCapacity(1);
        this.positionMap[this.size] = this.primSize;
        setPrimBoolean(this.primSize, fv.bool_val);
        setType(this.size, FieldType.BOOLEAN);
        this.size++;
        this.primSize++;
        return;
      case (1 << FieldValue.__BYTE_VAL_ISSET_ID):
        ensurePrimCapacity(1);
        this.positionMap[this.size] = this.primSize;
        setPrimByte(this.primSize, fv.byte_val);
        setType(this.size, FieldType.BYTE);
        this.size++;
        this.primSize++;
        return;
      case (1 << FieldValue.__CHAR_VAL_ISSET_ID):
        ensurePrimCapacity(2);
        this.positionMap[this.size] = this.primSize;
        setPrimShort(this.primSize, fv.char_val);
        setType(this.size, FieldType.CHAR);
        this.size++;
        this.primSize += 2;
        return;
      case (1 << FieldValue.__FLOAT_VAL_ISSET_ID):
        ensurePrimCapacity(4);
        this.positionMap[this.size] = this.primSize;
        setPrimInt(this.primSize, fv.float_val);
        setType(this.size, FieldType.FLOAT);
        this.size++;
        this.primSize += 4;
        return;
      case (1 << FieldValue.__NULL_VAL_ISSET_ID):
        ensureNonPrimCapacity();
        this.nonPrimitives[this.nonPrimSize++] = null;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, FieldType.NATIVE_OBJECT);
        this.size++;
        return;
      case (1 << FieldValue.__REF_VAL_ISSET_ID):
        ensurePrimCapacity(4);
        this.positionMap[this.size] = this.primSize;
        setPrimInt(this.primSize, fv.ref_val);
        setType(this.size, FieldType.PDX_OBJECT);
        this.size++;
        this.primSize += 4;
        return;
      case (1 << FieldValue.__SHORT_VAL_ISSET_ID):
        ensurePrimCapacity(2);
        this.positionMap[this.size] = this.primSize;
        setPrimShort(this.primSize, fv.short_val);
        setType(this.size, FieldType.SHORT);
        this.size++;
        this.primSize += 2;
        return;
    }
    // then for non-primitive types
    ensureNonPrimCapacity();
    {
      String str = fv.string_val;
      if (str != null) {
        this.nonPrimitives[this.nonPrimSize++] = str;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.STRING);
        return;
      }
    }
    {
      Decimal decimal = fv.decimal_val;
      if (decimal != null) {
        this.nonPrimitives[this.nonPrimSize++] = Converters
            .getBigDecimal(decimal);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.DECIMAL);
        return;
      }
    }
    {
      Timestamp ts = fv.timestamp_val;
      if (ts != null) {
        this.nonPrimitives[this.nonPrimSize++] = Converters.getTimestamp(ts);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.TIMESTAMP);
        return;
      }
    }
    {
      if (fv.byte_array != null) {
        this.nonPrimitives[this.nonPrimSize++] = fv.getByte_array();
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.BINARY);
        return;
      }
    }
    {
      String charArr = fv.char_array;
      if (charArr != null) {
        this.nonPrimitives[this.nonPrimSize++] = ResolverUtils
            .getInternalChars(charArr, charArr.length());
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.CHAR_ARRAY);
        return;
      }
    }
    {
      if (fv.native_val != null) {
        this.nonPrimitives[this.nonPrimSize++] = Converters.getJavaObject(
            fv.getNative_val(), this.size);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.NATIVE_OBJECT);
        return;
      }
    }
    {
      List<ByteBuffer> byteArrArr = fv.byte_array_array;
      if (byteArrArr != null) {
        byte[][] arr = new byte[byteArrArr.size()][];
        for (int i = 0; i < arr.length; i++) {
          ByteBuffer buf = org.apache.thrift.TBaseHelper.rightSize(byteArrArr
              .get(i));
          arr[i] = buf != null ? buf.array() : null;
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.ARRAY_OF_BINARY);
        return;
      }
    }
    {
      List<Boolean> boolArr = fv.bool_array;
      if (boolArr != null) {
        boolean[] arr = new boolean[boolArr.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = boolArr.get(i);
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.BOOLEAN_ARRAY);
        return;
      }
    }
    {
      List<Double> doubleArr = fv.double_array;
      if (doubleArr != null) {
        double[] arr = new double[doubleArr.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = doubleArr.get(i);
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.DOUBLE_ARRAY);
        return;
      }
    }
    {
      List<Integer> floatArr = fv.float_array;
      if (floatArr != null) {
        float[] arr = new float[floatArr.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = Float.intBitsToFloat(floatArr.get(i));
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.FLOAT_ARRAY);
        return;
      }
    }
    {
      List<Integer> intArr = fv.int_array;
      if (intArr != null) {
        int[] arr = new int[intArr.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = intArr.get(i);
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.INT_ARRAY);
        return;
      }
    }
    {
      List<Integer> list = fv.list_val;
      if (list != null) {
        this.nonPrimitives[this.nonPrimSize++] = list;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.ARRAY);
        return;
      }
    }
    {
      List<Long> longArr = fv.long_array;
      if (longArr != null) {
        long[] arr = new long[longArr.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = longArr.get(i);
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.LONG_ARRAY);
        return;
      }
    }
    {
      List<Short> shortArr = fv.short_array;
      if (shortArr != null) {
        short[] arr = new short[shortArr.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = shortArr.get(i);
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.SHORT_ARRAY);
        return;
      }
    }
    {
      List<String> strArr = fv.string_array;
      if (strArr != null) {
        String[] arr = new String[strArr.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = strArr.get(i);
        }
        this.nonPrimitives[this.nonPrimSize++] = arr;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size++, FieldType.STRING_ARRAY);
        return;
      }
    }
    throw ClientSharedUtils.newRuntimeException("unknown field value: " + fv,
        null);
  }

  public final void addColumnValue(ColumnValue cv) throws SQLException {
    ensureCapacity();
    // check for primitive types first
    switch (cv.__isset_bitfield) {
      case (1 << ColumnValue.__I32_VAL_ISSET_ID):
        ensurePrimCapacity(4);
        this.positionMap[this.size] = this.primSize;
        setPrimInt(this.primSize, cv.i32_val);
        setType(this.size, GFXDType.INTEGER);
        this.size++;
        this.primSize += 4;
        return;
      case (1 << ColumnValue.__I64_VAL_ISSET_ID):
        ensurePrimCapacity(8);
        this.positionMap[this.size] = this.primSize;
        setPrimLong(this.primSize, cv.i64_val);
        setType(this.size, GFXDType.BIGINT);
        this.size++;
        this.primSize += 8;
        return;
      case (1 << ColumnValue.__DOUBLE_VAL_ISSET_ID):
        ensurePrimCapacity(8);
        this.positionMap[this.size] = this.primSize;
        setPrimLong(this.primSize, Double.doubleToLongBits(cv.double_val));
        setType(this.size, GFXDType.DOUBLE);
        this.size++;
        this.primSize += 8;
        return;
      case (1 << ColumnValue.__BOOL_VAL_ISSET_ID):
        ensurePrimCapacity(1);
        this.positionMap[this.size] = this.primSize;
        setPrimBoolean(this.primSize, cv.bool_val);
        setType(this.size, GFXDType.BOOLEAN);
        this.size++;
        this.primSize++;
        return;
      case (1 << ColumnValue.__BYTE_VAL_ISSET_ID):
        ensurePrimCapacity(1);
        this.positionMap[this.size] = this.primSize;
        setPrimByte(this.primSize, cv.byte_val);
        setType(this.size, GFXDType.TINYINT);
        this.size++;
        this.primSize++;
        return;
      case (1 << ColumnValue.__FLOAT_VAL_ISSET_ID):
        ensurePrimCapacity(4);
        this.positionMap[this.size] = this.primSize;
        setPrimInt(this.primSize, cv.float_val);
        setType(this.size, GFXDType.FLOAT);
        this.size++;
        this.primSize += 4;
        return;
      case (byte)(1 << ColumnValue.__NULL_VAL_ISSET_ID):
        ensureNonPrimCapacity();
        this.nonPrimitives[this.nonPrimSize++] = null;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.NULLTYPE);
        this.size++;
        return;
      case (1 << ColumnValue.__I16_VAL_ISSET_ID):
        ensurePrimCapacity(2);
        this.positionMap[this.size] = this.primSize;
        setPrimShort(this.primSize, cv.i16_val);
        setType(this.size, GFXDType.SMALLINT);
        this.size++;
        this.primSize += 2;
        return;
    }
    // then for non-primitive types
    ensureNonPrimCapacity();
    {
      String str = cv.string_val;
      if (str != null) {
        this.nonPrimitives[this.nonPrimSize++] = str;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, str.length() <= Limits.DB2_VARCHAR_MAXWIDTH
            ? GFXDType.VARCHAR : GFXDType.CLOB);
        this.size++;
        return;
      }
    }
    {
      Decimal decimal = cv.decimal_val;
      if (decimal != null) {
        this.nonPrimitives[this.nonPrimSize++] = Converters
            .getBigDecimal(decimal);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.DECIMAL);
        this.size++;
        return;
      }
    }
    {
      DateTime date = cv.date_val;
      if (date != null) {
        this.nonPrimitives[this.nonPrimSize++] = new java.sql.Date(
            date.secsSinceEpoch);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.DATE);
        this.size++;
        return;
      }
    }
    {
      Timestamp ts = cv.timestamp_val;
      if (ts != null) {
        this.nonPrimitives[this.nonPrimSize++] = Converters.getTimestamp(ts);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.TIMESTAMP);
        this.size++;
        return;
      }
    }
    {
      DateTime time = cv.time_val;
      if (time != null) {
        this.nonPrimitives[this.nonPrimSize++] = new java.sql.Time(
            time.secsSinceEpoch);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.TIME);
        this.size++;
        return;
      }
    }
    {
      if (cv.binary_val != null) {
        byte[] bytes = cv.getBinary_val();
        this.nonPrimitives[this.nonPrimSize++] = bytes;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, bytes.length <= Limits.DB2_VARCHAR_MAXWIDTH
            ? GFXDType.VARBINARY : GFXDType.BLOB);
        this.size++;
        return;
      }
    }
    {
      BlobChunk chunk = cv.blob_val;
      if (chunk != null) {
        this.nonPrimitives[this.nonPrimSize++] = chunk;
        this.positionMap[this.size] = -this.nonPrimSize;
        // also make space for finalizer of BlobChunk if required
        if (chunk.isSetLobId()) {
          ensureNonPrimCapacity();
          this.nonPrimSize++;
        }
        setType(this.size, GFXDType.BLOB);
        this.size++;
        return;
      }
    }
    {
      ClobChunk chunk = cv.clob_val;
      if (chunk != null) {
        this.nonPrimitives[this.nonPrimSize++] = chunk;
        this.positionMap[this.size] = -this.nonPrimSize;
        // also make space for finalizer of ClobChunk if required
        if (chunk.isSetLobId()) {
          ensureNonPrimCapacity();
          this.nonPrimSize++;
        }
        setType(this.size, GFXDType.CLOB);
        this.size++;
        return;
      }
    }
    {
      if (cv.java_val != null) {
        this.nonPrimitives[this.nonPrimSize++] = Converters.getJavaObject(
            cv.getJava_val(), this.size);
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.JAVA_OBJECT);
        this.size++;
        return;
      }
    }
    {
      PDXObject pdxObj = cv.pdx_val;
      if (pdxObj != null) {
        this.nonPrimitives[this.nonPrimSize++] = pdxObj;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.PDX_OBJECT);
        this.size++;
        return;
      }
    }
    {
      JSONObject jsonObj = cv.json_val;
      if (jsonObj != null) {
        this.nonPrimitives[this.nonPrimSize++] = jsonObj;
        this.positionMap[this.size] = -this.nonPrimSize;
        setType(this.size, GFXDType.JSON_OBJECT);
        this.size++;
        return;
      }
    }
    throw ClientSharedUtils.newRuntimeException("unknown column value: " + cv,
        null);
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
