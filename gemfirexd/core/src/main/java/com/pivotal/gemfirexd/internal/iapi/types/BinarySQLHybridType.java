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

package com.pivotal.gemfirexd.internal.iapi.types;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraInfo;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

/**
 * Intermediate DVD type that can compare raw bytes against DVD or raw bytes
 * against raw bytes without requiring to deserialize in either case (except for
 * BINARY/BLOB/CLOB/XML/UDT types) using {@link DataTypeUtilities#compare}. This
 * also promotes the type to be compatible with that as present in the
 * underlying table/index.
 * 
 * NOTE: Keep this class as final; other places depend on it checking for .class
 * directly avoiding instanceof.
 */
public final class BinarySQLHybridType extends DataType {

  final DataValueDescriptor sqlValue;

  byte[] byteValue;

  long offsetAndWidth;

  private static final int BASE_MEMORY_USAGE =
      ClassSize.estimateBaseFromCatalog(BinarySQLHybridType.class);

  public static DataValueDescriptor getHybridType(
      DataValueDescriptor sqlValue, final DataTypeDescriptor targetType,
      final GemFireContainer container) throws StandardException {

    if (container != null && !container.isByteArrayStore()) {
      return sqlValue;
    }
    if (sqlValue.getClass() == BinarySQLHybridType.class) {
      assert isEquivalentType(((BinarySQLHybridType)sqlValue).sqlValue, targetType) 
          : "target=" + targetType.getTypeId().getTypeFormatId()
          + " binary types underlying value="
          + ((BinarySQLHybridType)sqlValue).sqlValue.getTypeFormatId();
      return sqlValue;
    }

    final byte[] byteValue;
    final long offsetAndWidth;
    // need to serialize only if byte[] to byte[] comparison would be more
    // efficient
    if (!sqlValue.isNull()) {
      // below is commented out because now we compare String against bytes in
      // an optimized way so no need to convert both to byte[] form
      /*
      if (sqlValue.canCompareBytesToBytes()) {
        if (sqlValue.getTypeFormatId() != targetType.getDVDTypeFormatId()) {
          final DataValueDescriptor tgtDVD = targetType.getNull();
          tgtDVD.setValue(sqlValue);
          sqlValue = tgtDVD;
        }              
        byteValue = new byte[sqlValue.getLengthInBytes(targetType)];
        sqlValue.writeBytes(byteValue, 0, targetType);
        offsetAndWidth = byteValue.length;
      }
      else */ {
        sqlValue = transformToEquivalent(sqlValue, true, targetType);
        byteValue = null;
        offsetAndWidth = 0;
      }
      return new BinarySQLHybridType(sqlValue, byteValue, offsetAndWidth);
    }
    else {
      return sqlValue;
    }
  }

  public static DataValueDescriptor getHybridTypeIfNeeded(
      DataValueDescriptor sqlValue, final DataTypeDescriptor targetType)
      throws StandardException {

    if (sqlValue.getClass() == BinarySQLHybridType.class) {
      assert isEquivalentType(((BinarySQLHybridType)sqlValue).sqlValue,
          targetType): "target=" + targetType.getTypeId().getTypeFormatId()
          + " binary types underlying value="
          + ((BinarySQLHybridType)sqlValue).sqlValue.getTypeFormatId();
      return sqlValue;
    }

    // need to serialize only if byte[] to byte[] comparison would be more
    // efficient; for other case transform to equivalent type
    if (!sqlValue.isNull()) {
      // below is commented out because now we compare String against bytes in
      // an optimized way so no need to convert both to byte[] form
      /*
      if (sqlValue.canCompareBytesToBytes()) {
        if (sqlValue.getTypeFormatId() != targetType.getDVDTypeFormatId()) {
          final DataValueDescriptor tgtDVD = targetType.getNull();
          tgtDVD.setValue(sqlValue);
          sqlValue = tgtDVD;
        }
        final byte[] byteValue; 
        byteValue = new byte[sqlValue.getLengthInBytes(targetType)];
        sqlValue.writeBytes(byteValue, 0, targetType);
        return new BinarySQLHybridType(sqlValue, byteValue, byteValue.length);
      }
      else */ {
        // do the transformation to equivalent type for efficient comparison
        // in DataTypeUtilities#compare
        return transformToEquivalent(sqlValue, true, targetType);
      }
    }
    else {
      return sqlValue;
    }
  }

  private BinarySQLHybridType(DataValueDescriptor sqlValue, byte[] byteValue,
      long offsetAndWidth) {
    this.sqlValue = sqlValue;
    this.byteValue = byteValue;
    this.offsetAndWidth = offsetAndWidth;
  }

  public static boolean needsTransformToEquivalent(
      final DataValueDescriptor sqlValue, final DataTypeDescriptor targetDTD)
      throws StandardException {
    /*
    // always transform for those that are best compared byte to byte
    if (sqlValue.canCompareBytesToBytes()) {
      return true;
    }
    */
    return !(isEquivalentType(sqlValue, targetDTD));
  }

  public static DataValueDescriptor transformToEquivalent(
      DataValueDescriptor sqlValue, final boolean notNull,
      final DataTypeDescriptor targetDTD) throws StandardException {
    if (isEquivalentType(sqlValue, targetDTD)) {
      return sqlValue; 
    }

    final DataValueDescriptor tgtDVD = targetDTD.getNull();
    if (notNull) {
      tgtDVD.setValue(sqlValue);
    }
    return tgtDVD;
  }
  
  public static boolean isEquivalentType(final DataValueDescriptor sqlValue,
      final DataTypeDescriptor targetDTD) throws StandardException {
    final int existingType = sqlValue.getTypeFormatId();
    final int targetType = targetDTD.getDVDTypeFormatId();
    if (existingType == targetType) {
      return true;
    }
    // string type
    if (isStringType(existingType)) {
      if (isStringType(targetType)) {
        return true;
      }
    }
    // numeric type
    else if (isNumericType(existingType)) {
      if (isNumericType(targetType)) {
        return true;
      }
    }
    // date/time type
    else if (isDateTimeType(existingType)) {
      if (isDateTimeType(targetType)) {
        return true;
      }
    }
    // binary type
    else if (isBinaryType(existingType)) {
      if (isBinaryType(targetType)) {
        return true;
      }
    }
    return false;
  }
  
  public static boolean isNumericType(int dvdFormatId) {
    switch (dvdFormatId) {
      case StoredFormatIds.SQL_BOOLEAN_ID:
      case StoredFormatIds.SQL_TINYINT_ID:
      case StoredFormatIds.SQL_SMALLINT_ID:
      case StoredFormatIds.SQL_INTEGER_ID:
      case StoredFormatIds.SQL_LONGINT_ID:
      case StoredFormatIds.SQL_REAL_ID:
      case StoredFormatIds.SQL_DOUBLE_ID:
      // decimal type deliberately not here since repeated conversion
      // to and from BigDecimal is costly, so exact match is best
        return true;
      default:
        return false;
    }
  }

  public static boolean isStringType(int dvdFormatId) {
    switch (dvdFormatId) {
      case StoredFormatIds.SQL_CHAR_ID:
      case StoredFormatIds.SQL_VARCHAR_ID:
      case StoredFormatIds.SQL_LONGVARCHAR_ID:
      case StoredFormatIds.SQL_CLOB_ID:
        return true;
      default:
        return false;
    }
  }

  public static boolean isDateTimeType(int dvdFormatId) {
    switch (dvdFormatId) {
      case StoredFormatIds.SQL_DATE_ID:
      case StoredFormatIds.SQL_TIME_ID:
      case StoredFormatIds.SQL_TIMESTAMP_ID:
        return true;
      default:
        return false;
    }
  }

  public static boolean isBinaryType(int dvdFormatId) {
    switch (dvdFormatId) {
      case StoredFormatIds.SQL_BIT_ID:
      case StoredFormatIds.SQL_VARBIT_ID:
      case StoredFormatIds.SQL_LONGVARBIT_ID:
      case StoredFormatIds.SQL_BLOB_ID:
        return true;
      default:
        return false;
    }
  }

  public final byte[] getByteValue() {
    return this.byteValue;
  }

  public final DataValueDescriptor getSQLValue() {
    return this.sqlValue;
  }

  @Override
  public boolean compare(int op, ExecRow row, boolean byteArrayStore,
      int logicalPosition, boolean orderedNulls, boolean unknownRV)
      throws StandardException {

    assert byteArrayStore: "unexpected ExecRow of type "
        + row.getClass().getName() + ": " + row;

    return compare(op, (AbstractCompactExecRow)row, this.sqlValue,
        this.byteValue, this.offsetAndWidth, logicalPosition, orderedNulls,
        unknownRV);
  }

  public static final boolean compare(int op,
      final AbstractCompactExecRow compactRow,
      final DataValueDescriptor thisValue, final byte[] thisByteValue,
      long thisOffsetAndWidth, int logicalPosition, boolean orderedNulls,
      boolean unknownRV) throws StandardException {

    /* now we allow for all types, not just strings
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceByteComparisonOptimization) {
        final ColumnDescriptor cd = compactRow.getColumnDescriptor(logicalPosition);
        if (typePrecedence() < cd.columnType.getTypeId().typePrecedence()) {
          if (!cd.getType().getNull().canCompareBytesToBytes()) {
            SanityManager.DEBUG_PRINT(
                GfxdConstants.TRACE_BYTE_COMPARE_OPTIMIZATION,
                "DataType:compare: Type "
                    + "conversion should have happened before comparison i.e. "
                    + "this object is expected to be BinarySQLHybridType");
            throw new InternalGemFireError(
                "Didn't expected type promotion at this level");
          }
        }
      }
    }
    */
    if (!orderedNulls) { // nulls are unordered
      if (thisValue.isNull()
          || compactRow.isNull(logicalPosition) == RowFormatter.OFFSET_AND_WIDTH_IS_NULL)
        return unknownRV;
    }

    final RowFormatter rf = compactRow.getRowFormatter();
    final Object rhs = compactRow.getByteSource(logicalPosition);
    final byte[] rhsBytes;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final OffHeapByteSource rhsBS;
    final long rhsAddr;
    final long rhsOffsetWidth;
    if (rhs == null || rhs.getClass() == byte[].class) {
      rhsBS = null;
      rhsAddr = 0;
      rhsBytes = (byte[])rhs;
      rhsOffsetWidth = rf.getOffsetAndWidth(logicalPosition, rhsBytes);
    }
    else {
      rhsBytes = null;
      rhsBS = (OffHeapByteSource)rhs;
      final int bytesLen = rhsBS.getLength();
      rhsAddr = rhsBS.getUnsafeAddress(0, bytesLen);
      rhsOffsetWidth = rf.getOffsetAndWidth(logicalPosition, unsafe, rhsAddr,
          bytesLen);
    }

    final int result = -Integer.signum(thisValue != null ? DataTypeUtilities
        .compare(unsafe, thisValue, rhsBytes, rhsAddr, rhsBS, rhsOffsetWidth,
            false, true, rf.getColumnDescriptor(logicalPosition - 1))
        : DataTypeUtilities.compare(unsafe, thisByteValue, 0, null, rhsBytes,
            rhsAddr, rhsBS, thisOffsetAndWidth, rhsOffsetWidth, false, true,
            rf.getColumnDescriptor(logicalPosition - 1)));

    if (!orderedNulls) { // nulls are unordered
      if (result == DataTypeUtilities.NULL_MAX
          || result == DataTypeUtilities.NULL_MIN) {
        return unknownRV;
      }
    }
    return applyOp(result, op);
  }

  @Override
  public boolean compare(int op, CompactCompositeKey key, int logicalPosition,
      boolean orderedNulls, boolean unknownRV) throws StandardException {
    return compare(op, key, this.sqlValue, this.byteValue, this.offsetAndWidth,
        logicalPosition, orderedNulls, unknownRV);
  }

  public static final boolean compare(int op, final CompactCompositeKey key,
      final DataValueDescriptor thisValue, final byte[] thisByteValue,
      long thisOffsetAndWidth, int logicalPosition, boolean orderedNulls,
      boolean unknownRV) throws StandardException {

    /* now we allow for all types, not just strings
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceByteComparisonOptimization) {
        final DataTypeDescriptor dtd = key.getExtraInfo()
            .getPrimaryKeyFormatter().getType(logicalPosition);
        if (typePrecedence() < dtd.getTypeId().typePrecedence()) {
          if (!cd.getType().getNull().canCompareBytesToBytes()) {
            SanityManager.DEBUG_PRINT(
                GfxdConstants.TRACE_BYTE_COMPARE_OPTIMIZATION,
                "DataType:compare: Type "
                    + "conversion should have happened before comparison i.e. "
                    + "this object is expected to be BinarySQLHybridType");
            throw new InternalGemFireError(
                "Didn't expected type promotion at this level");
          }
        }
      }
    }
    */

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    RowFormatter rf;
    byte[] rhsBytes;
    OffHeapByteSource rhsBS = null;
    long rhsAddr = 0;
    long rhsOffsetWidth;
    final ExtraInfo containerInfo = key.getExtraInfo();

    if (containerInfo == null) {
      throw RegionEntryUtils
          .checkCacheForNullTableInfo("BinarySQLHybridType#compare");
    }

    int tries = 1;
    try {
      for (;;) {
        if ((rhsBytes = key.getKeyBytes()) != null) {
          // got the bytes as keyBytes
          rf = containerInfo.getPrimaryKeyFormatter();
          rhsOffsetWidth = rf.getOffsetAndWidth(logicalPosition, rhsBytes);
          break;
        }

        @Retained @Released final Object valueByteSource =
            key.getValueByteSource();
        if (valueByteSource != null) {
          if (valueByteSource.getClass() == byte[].class) {
            rhsBytes = (byte[])valueByteSource;
            rf = containerInfo.getRowFormatter(rhsBytes);
            final int[] keyPositions = containerInfo.getPrimaryKeyColumns();
            logicalPosition = keyPositions[logicalPosition - 1];
            rhsOffsetWidth = rf.getOffsetAndWidth(logicalPosition, rhsBytes);
            break;
          }
          else {
            rhsBS = (OffHeapByteSource)valueByteSource;
            // got the bytes as bytesource
            final int bytesLen = rhsBS.getLength();
            rhsAddr = rhsBS.getUnsafeAddress(0, bytesLen);
            rf = containerInfo.getRowFormatter(rhsAddr, rhsBS);
            final int[] keyPositions = containerInfo.getPrimaryKeyColumns();
            logicalPosition = keyPositions[logicalPosition - 1];
            rhsOffsetWidth = rf.getOffsetAndWidth(logicalPosition, unsafe,
                rhsAddr, bytesLen);
            break;
          }
        }
        if ((tries % CompactCompositeKey.MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
        if (tries++ > CompactCompositeKey.MAX_TRIES) {
          throw RegionEntryUtils
              .checkCacheForNullKeyValue("BinarySQLHybridType#compare");
        }
      }

      if (!orderedNulls) { // nulls are unordered
        if (rhsOffsetWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL
            || thisValue.isNull()) {
          return unknownRV;
        }
      }

      // revert the result since the lhs and rhs are swapped in the comparison
      // here
      final int result = -Integer.signum(thisValue != null ? DataTypeUtilities
          .compare(unsafe, thisValue, rhsBytes, rhsAddr, rhsBS, rhsOffsetWidth,
              false, true, rf.getColumnDescriptor(logicalPosition - 1))
          : DataTypeUtilities.compare(unsafe, thisByteValue, 0, null, rhsBytes,
              rhsAddr, rhsBS, thisOffsetAndWidth, rhsOffsetWidth, false, true,
              rf.getColumnDescriptor(logicalPosition - 1)));

      if (!orderedNulls) { // nulls are unordered
        if (result == DataTypeUtilities.NULL_MAX
            || result == DataTypeUtilities.NULL_MIN) {
          return unknownRV;
        }
      }
      return applyOp(result, op);
    } finally {
      if (rhsBS != null) {
        rhsBS.release();
      }
    }
  }

  @Override
  public int compare(DataValueDescriptor other) throws StandardException {
    if (other.getClass() != BinarySQLHybridType.class) {
      return this.sqlValue.compare(other);
    }
    else {
      return this.sqlValue.compare(((BinarySQLHybridType)other).sqlValue);
    }
  }

  @Override
  public String getTypeName() {
    return this.sqlValue.getTypeName();
  }

  @Override
  public int estimateMemoryUsage() {
    int sz = BASE_MEMORY_USAGE;
    if (this.byteValue != null) {
      sz += this.byteValue.length;
    }
    return sz;
  }

  @Override
  public DataValueDescriptor getClone() {
    return new BinarySQLHybridType(this.sqlValue, this.byteValue,
        this.offsetAndWidth);
  }

  @Override
  public int getLength() throws StandardException {
    return this.sqlValue.getLength();
  }

  @Override
  public DataValueDescriptor getNewNull() {
    return null;
  }

  @Override
  public String getString() throws StandardException {
    return this.sqlValue.getString();
  }

  @Override
  public void readExternalFromArray(ArrayInputStream ais) throws IOException,
      ClassNotFoundException {
  }

  @Override
  public void setValueFromResultSet(ResultSet resultSet, int colNumber,
      boolean isNullable) throws StandardException, SQLException {
  }

  @Override
  public boolean isNull() {
    return this.sqlValue.isNull();
  }

  @Override
  public void restoreToNull() {
    this.sqlValue.restoreToNull();
    this.byteValue = null;
    this.offsetAndWidth = RowFormatter.OFFSET_AND_WIDTH_IS_NULL;
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int getTypeFormatId() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int writeBytes(byte[] outBytes, int offset, DataTypeDescriptor dtd) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int readBytes(byte[] inBytes, int offset, int columnWidth) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public int readBytes(long memOffset, int columnWidth, ByteSource bs) {
    throw new UnsupportedOperationException("unexpected invocation for "
        + getClass());
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void toDataForOptimizedResultHolder(DataOutput dos) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }
}
