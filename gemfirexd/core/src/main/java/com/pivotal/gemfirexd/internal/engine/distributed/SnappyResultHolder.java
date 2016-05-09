/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.Types;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.distributed.message.LeadNodeExecutorMsg;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.*;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;
import com.pivotal.gemfirexd.internal.snappy.SparkSQLExecute;

/**
 * Holds the results obtained from lead node execution.
 */
public final class SnappyResultHolder extends GfxdDataSerializable {

  private transient SparkSQLExecute exec;

  private transient ByteArrayDataInput dis;
  private transient volatile String[] colNames;
  private transient volatile String[] tableNames;
  private transient volatile boolean[] nullability;
  private transient volatile int[] colTypes;
  private transient volatile int[] precisions;
  private transient volatile int[] scales;
  private DataValueDescriptor[] templateDVDRow;
  private ValueRow execRow;
  private DataTypeDescriptor[] dtds;

  public SnappyResultHolder(SparkSQLExecute exec) {
    this.exec = exec;
  }

  /** for deserialization */
  public SnappyResultHolder() {
  }

  @Override
  public byte getGfxdID() {
    return SNAPPY_RESULT_HOLDER;
  }

  public void setMetadata(SnappyResultHolder other) {
    this.tableNames = other.tableNames;
    this.colNames = other.colNames;
    this.nullability = other.nullability;
    this.colTypes = other.colTypes;
    this.precisions = other.precisions;
    this.scales = other.scales;
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    this.exec.serializeRows(out);
  }

  @Override
  public void fromData(DataInput in) throws IOException {
    final int numBytes = InternalDataSerializer.readArrayLength(in);
    if (numBytes > 0) {
      final byte[] rawData = DataSerializer.readByteArray(in, numBytes);
      Version v = InternalDataSerializer.getVersionForDataStreamOrNull(in);
      fromSerializedData(rawData, numBytes, v);
    }
  }

  public final void fromSerializedData(final byte[] rawData,
      final int numBytes, final Version v) throws IOException {
    final ByteArrayDataInput dis = new ByteArrayDataInput();
    dis.initialize(rawData, 0, numBytes, v);
    byte metaInfo = dis.readByte();
    if (metaInfo == 0x01) {
      tableNames = DataSerializer.readStringArray(dis);
      colNames = DataSerializer.readStringArray(dis);
      nullability = DataSerializer.readBooleanArray(dis);
      int totCols = colNames.length;
      this.precisions = new int[totCols];
      this.scales = new int[totCols];
      dtds = new DataTypeDescriptor[totCols];
      this.colTypes = new int[totCols];
      for (int i = 0; i < totCols; i++) {
        int columnType = (int)InternalDataSerializer.readSignedVL(dis);
        this.colTypes[i] = columnType;
        if (columnType == StoredFormatIds.SQL_DECIMAL_ID) {
          // read the precision and the scale
          precisions[i] = (int)InternalDataSerializer.readSignedVL(dis);
          scales[i] = (int)InternalDataSerializer.readSignedVL(dis);
        } else {
          precisions[i] = -1;
          scales[i] = -1;
        }
      }
    }
    this.dis = dis;
  }

  private void makeTemplateDVDArr() {
    dtds = new DataTypeDescriptor[colTypes.length];
    DataValueDescriptor[] dvds = new DataValueDescriptor[colTypes.length];
    for (int i = 0; i < colTypes.length; i++) {
      int typeId = colTypes[i];
      DataValueDescriptor dvd = getNewNullDVD(typeId, i, dtds,
          precisions[i], scales[i]);
      dvds[i] = dvd;
    }
    this.templateDVDRow = dvds;
    this.execRow = new ValueRow(templateDVDRow);
    // determine eight col groups and partial col
    int numCols = colTypes.length;
    if (numEightColGrps < 0) {
      numEightColGrps = numCols / 8;
      numPartialCols = numCols % 8;
    }
  }

  public String[] getColumnNames() {
    return this.colNames;
  }

  public int[] getColumnTypes() {
    return this.colTypes;
  }

  public String[] getTableNames() { return this.tableNames; }

  public void prepareSend(LeadNodeExecutorMsg msg) {
    this.exec.packRows(msg, this);
  }

  private int numEightColGrps = -1;
  private int numPartialCols = 0;

  public ExecRow getNextRow() throws IOException, ClassNotFoundException, StandardException {
    if (this.dis != null && this.dis.available() > 0) {
      if (templateDVDRow == null) {
        makeTemplateDVDArr();
      }
      CallbackFactoryProvider.getClusterCallbacks().readDVDArray(
          templateDVDRow, colTypes, this.dis, numEightColGrps, numPartialCols);
      return this.execRow;
    }
    this.dis = null;
    return null;
  }

  private DataValueDescriptor getNewNullDVD(
    int storeType, int colNum, DataTypeDescriptor[] dtds, int precision, int scale) {
    DataValueDescriptor dvd;
    int jdbcTypeId;
    DataTypeDescriptor dtd;
    boolean nullable = nullability[colNum];
    switch(storeType) {
      case StoredFormatIds.SQL_TIMESTAMP_ID :
        dvd = new SQLTimestamp();
        jdbcTypeId = Types.TIMESTAMP;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_BOOLEAN_ID :
        dvd = new SQLBoolean();
        jdbcTypeId = Types.BOOLEAN;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_DATE_ID :
        dvd = new SQLDate();
        jdbcTypeId = Types.DATE;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_LONGINT_ID :
        dvd = new SQLLongint();
        jdbcTypeId = Types.BIGINT;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_SMALLINT_ID :
        dvd = new SQLSmallint();
        jdbcTypeId = Types.SMALLINT;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_TINYINT_ID :
        dvd = new SQLTinyint();
        jdbcTypeId = Types.INTEGER;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_INTEGER_ID:
        dvd = new SQLInteger();
        jdbcTypeId = Types.INTEGER;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_DECIMAL_ID:
        dvd = new SQLDecimal();
        try {
          dtd = DataTypeDescriptor.getSQLDataTypeDescriptor
            ("java.math.BigDecimal", precision, scale, nullable, precision);
        } catch (StandardException e) {
          throw new GemFireXDRuntimeException(e);
        }
        break;

      case StoredFormatIds.SQL_REAL_ID:
        dvd = new SQLReal();
        jdbcTypeId = Types.REAL;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_DOUBLE_ID:
        dvd = new SQLDouble();
        jdbcTypeId = Types.DOUBLE;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_CLOB_ID:
      case StoredFormatIds.SQL_VARCHAR_ID:
        dvd = new SQLClob();
        jdbcTypeId = Types.CLOB;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(jdbcTypeId, nullable);
        break;

      case StoredFormatIds.SQL_BLOB_ID:
        dvd = new SQLBlob();
        jdbcTypeId = Types.BLOB;
        dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
            jdbcTypeId, nullable);
        break;

      default :
        // TODO: what exception should be thrown? Check.
        throw new IllegalStateException("SnappyResultHolder: cannot handle type: " + storeType);
    }
    dtds[colNum] = dtd;
    return dvd;
  }

  public DataTypeDescriptor[] getDtds() {
    makeTemplateDVDArr();
    return this.dtds;
  }
}
