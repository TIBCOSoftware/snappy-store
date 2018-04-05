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
package com.pivotal.gemfirexd.internal.engine.ddl.wan.messages;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgument;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectProcedure;
import com.pivotal.gemfirexd.callbacks.AsyncEventHelper;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.callbacks.TableMetaData;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.ddl.JavaObjectsList.DVDArrayJavaObjectsList;
import com.pivotal.gemfirexd.internal.engine.ddl.JavaObjectsList.PVSJavaObjectsList;
import com.pivotal.gemfirexd.internal.engine.distributed.message.BitSetSet;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.DVDStoreResultSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RawStoreResultSet;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeUtilities;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLBit;
import com.pivotal.gemfirexd.internal.iapi.types.SQLBlob;
import com.pivotal.gemfirexd.internal.iapi.types.SQLBoolean;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.iapi.types.SQLClob;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDate;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDouble;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.iapi.types.SQLLongVarbit;
import com.pivotal.gemfirexd.internal.iapi.types.SQLLongint;
import com.pivotal.gemfirexd.internal.iapi.types.SQLLongvarchar;
import com.pivotal.gemfirexd.internal.iapi.types.SQLReal;
import com.pivotal.gemfirexd.internal.iapi.types.SQLRef;
import com.pivotal.gemfirexd.internal.iapi.types.SQLSmallint;
import com.pivotal.gemfirexd.internal.iapi.types.SQLTime;
import com.pivotal.gemfirexd.internal.iapi.types.SQLTimestamp;
import com.pivotal.gemfirexd.internal.iapi.types.SQLTinyint;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarbit;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.iapi.types.UserType;
import com.pivotal.gemfirexd.internal.iapi.types.XML;
import com.pivotal.gemfirexd.internal.iapi.types.JSON;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;

/**
 * The instance of this class holds details of the DML operation & the
 * parameters passed
 * 
 * @author Asif, swale, ymahajan
 * 
 */
public final class GfxdCBArgForSynchPrms extends GfxdDataSerializable implements
    GatewaySenderEventCallbackArgument, GfxdSerializable, Event,
    TableMetaData, Sizeable {

  private String dmlString;

  private int numParams;

  private DataValueDescriptor[] params;

  private byte[] sqlParamTypes;

  private BitSetSet notNullSet;

  /**
   * if this is the case of bulk insert, then the parameters will be raw
   * byte[]/byte[][] objects
   */
  private ArrayList<Object> bulkInsertRows;
  private boolean isBulkInsert;

  private boolean transactional;

  private int approxSize;

  private boolean posDup;

  // remap some java.sql.Types to fit into a byte
  // hardcoded values here such that they do not clash with any other
  // existing Types
  private static final byte JAVA_OBJECT = 100;
  private static final byte BLOB = 104;
  private static final byte CLOB = 105;
  private static final byte SQLXML = 109;
  private static final byte JSON = 110;

  /**
   * The id of the originating <code>GatewayReceiver</code> making the request
   */
  private int originatingDSId = GatewaySender.DEFAULT_DISTRIBUTED_SYSTEM_ID;

  /**
   * The set of <code>GatewayReceiver</code> s to which the event has been sent.
   * This set keeps track of the <code>GatewayReceiver</code> s to which the
   * event has been sent so that downstream <code>GatewayReceiver</code> s don't
   * resend the event to the same <code>GatewayReceiver</code>s.
   */
  private final THashSet receipientDSIds;

  // These fields are for local use , not serialized
  private transient GenericParameterValueSet pvs;

  private transient DataTypeDescriptor[] dtds;

  private String schema;

  private String tableName;

  private GemFireContainer container;

  private transient ExtraTableInfo tableInfo;
  
  private boolean isPutDML = false;

  public GfxdCBArgForSynchPrms(String dmlString, String schema,
      String tableName, boolean isTransactional,
      GatewaySenderEventCallbackArgument senderArg, boolean isPutDML) {
    this.dmlString = dmlString;
    this.schema = schema;
    this.tableName = tableName;
    this.approxSize = this.schema.length() * 2 + this.dmlString.length() * 2
        + 32;
    this.transactional = isTransactional;
    if (senderArg != null) {
      this.originatingDSId = senderArg.getOriginatingDSId();
      this.receipientDSIds = new THashSet(senderArg.getRecipientDSIds());
    }
    else {
      this.receipientDSIds = new THashSet();
    }
    this.isPutDML = isPutDML;
  }

  public GfxdCBArgForSynchPrms() {
    this.receipientDSIds = new THashSet();
  }

  public GfxdCBArgForSynchPrms(String dmlString, GenericParameterValueSet pvs,
      DataTypeDescriptor[] dtds, String schema, String tableName,
      boolean isTransactional, GatewaySenderEventCallbackArgument senderArg, boolean isPutDML)
      throws StandardException {
    this.dmlString = dmlString;
    this.schema = schema;
    this.tableName = tableName;
    this.numParams = pvs.getParameterCount();
    this.pvs = pvs;
    this.dtds = dtds;
    this.approxSize = this.schema.length() * 2 + this.dmlString.length() * 2
        + 32;
    this.approxSize += this.numParams % 8;
    for (int i = 0; i < this.numParams; ++i) {
      this.approxSize += this.pvs.getParameter(i).estimateMemoryUsage();
    }
    this.transactional = isTransactional;
    if (senderArg != null) {
      this.originatingDSId = senderArg.getOriginatingDSId();
      this.receipientDSIds = new THashSet(senderArg.getRecipientDSIds());
    }
    else {
      this.receipientDSIds = new THashSet();
    }
    this.isPutDML = isPutDML;
  }

  public GfxdCBArgForSynchPrms(String dmlString,
      ArrayList<Object> bulkInsertRows, GemFireContainer container,
      boolean isTransactional, GatewaySenderEventCallbackArgument senderArg, boolean isPutDML)
      throws StandardException {
    this.dmlString = dmlString;
    this.schema = container.getSchemaName();
    this.tableName = container.getTableName();
    this.container = container;
    this.bulkInsertRows = new ArrayList<Object>(bulkInsertRows);
    this.isBulkInsert = true;
    this.numParams = -1;
    this.transactional = isTransactional;
    if (senderArg != null) {
      this.originatingDSId = senderArg.getOriginatingDSId();
      this.receipientDSIds = new THashSet(senderArg.getRecipientDSIds());
    }
    else {
      this.receipientDSIds = new THashSet();
    }
    this.isPutDML = isPutDML;
  }

  private GfxdCBArgForSynchPrms(GfxdCBArgForSynchPrms other) {
    this.dmlString = other.dmlString;
    this.schema = other.schema;
    this.tableName = other.tableName;
    this.container = other.container;
    this.numParams = other.numParams;
    //need to clone this as this object gets reused. Fix for defect #48251
    if (other.pvs != null) {
      this.pvs = (GenericParameterValueSet)other.pvs.getClone();
    }
    else {
      this.pvs = null;
    }
    this.dtds = other.dtds;
    this.bulkInsertRows = other.bulkInsertRows;
    this.isBulkInsert = other.isBulkInsert;
    this.approxSize = other.approxSize;
    this.posDup = other.posDup;
    this.params = other.params;
    this.notNullSet = other.notNullSet;
    this.sqlParamTypes = other.sqlParamTypes;
    this.originatingDSId = other.originatingDSId;
    this.receipientDSIds = new THashSet((Collection<?>)other.receipientDSIds);
    this.isPutDML = other.isPutDML;
  }

  @Override
  public final GfxdCBArgForSynchPrms getClone() {
    return new GfxdCBArgForSynchPrms(this);
  }

  @Override
  public byte getGfxdID() {
    return GFXD_CB_ARG_DB_SYNCH;
  }

  @Override
  public void fromData(DataInput in) throws IOException,
          ClassNotFoundException {
    super.fromData(in);
    this.dmlString = DataSerializer.readString(in);
    this.schema = DataSerializer.readString(in);
    this.tableName = DataSerializer.readString(in);
    this.originatingDSId = (int)InternalDataSerializer.readUnsignedVL(in);
    int numRecips = (int)InternalDataSerializer.readUnsignedVL(in);
    this.receipientDSIds.clear();
    if (numRecips > 0) {
      for (int i = 0; i < numRecips; i++) {
        this.receipientDSIds.add(Integer.valueOf((int)InternalDataSerializer
            .readUnsignedVL(in)));
      }
    }
    final byte flags = in.readByte();
    this.isBulkInsert = (flags & 0x1) != 0;
    this.transactional = (flags & 0x2) != 0;
    this.isPutDML = (flags & 0x4) != 0;
    if (this.isBulkInsert) {
      this.bulkInsertRows = InternalDataSerializer.readArrayList(in);
      return;
    }
    this.numParams = (int)InternalDataSerializer.readUnsignedVL(in);
    if (this.numParams > 0) {
      this.approxSize = (int)InternalDataSerializer.readUnsignedVL(in);
      this.sqlParamTypes = new byte[this.numParams];
      this.params = new DataValueDescriptor[this.numParams];
      final BitSetSet notNulls = BitSetSet.fromData(in);
      for (int i = 0; i < this.numParams; i++) {
        final byte sqlType = in.readByte();
        this.sqlParamTypes[i] = sqlType;
        if (notNulls != null && notNulls.containsInt(i)) {
          final DataValueDescriptor dvd = getNullDVD(sqlType);
          dvd.fromDataForOptimizedResultHolder(in);
          this.params[i] = dvd;
        }
      }
      this.notNullSet = notNulls;
    }
    else {
      this.approxSize = this.schema.length() * 2 + this.dmlString.length() * 2
          + 32;
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeString(this.dmlString, out);
    DataSerializer.writeString(this.schema, out);
    DataSerializer.writeString(this.tableName, out);
    InternalDataSerializer.writeUnsignedVL(this.originatingDSId, out);
    final int numRecipIds;
    if (this.receipientDSIds != null
        && (numRecipIds = this.receipientDSIds.size()) > 0) {
      InternalDataSerializer.writeArrayLength(numRecipIds, out);
      try {
        this.receipientDSIds.forEach(new TObjectProcedure() {
          @Override
          public final boolean execute(Object o) {
            try {
              InternalDataSerializer.writeUnsignedVL((Integer)o, out);
              return true;
            } catch (IOException ioe) {
              throw new GemFireIOException(null, ioe);
            }
          }
        });
      } catch (GemFireIOException ioe) {
        if (ioe.getCause() instanceof IOException) {
          throw (IOException)ioe.getCause();
        }
        throw ioe;
      }
    }
    else {
      InternalDataSerializer.writeUnsignedVL(0, out);
    }
    byte flags = 0x0;
    if (this.isPutDML) {
      flags |= 0x4;
    }
    if (this.transactional) {
      flags |= 0x2;
    }
    if (this.isBulkInsert) {
      // indicates bulk inserts vs other bulk DMLs
      out.writeByte((flags | 0x1));
      InternalDataSerializer.writeArrayList(this.bulkInsertRows, out);
      return;
    }
    // indicates other bulk DMLs
    out.writeByte(flags);
    InternalDataSerializer.writeUnsignedVL(this.numParams, out);
    if (this.numParams > 0) {
      InternalDataSerializer.writeUnsignedVL(this.approxSize, out);
      if (this.pvs != null) {
        try {
          if (this.notNullSet == null) {
            final BitSetSet notNulls = new BitSetSet(this.numParams);
            for (int i = 0; i < this.numParams; i++) {
              DataValueDescriptor dvd = this.pvs.getParameter(i);
              if (dvd != null && !dvd.isNull()) {
                notNulls.addInt(i);
              }
            }
            this.notNullSet = notNulls;
          }
          BitSetSet.toData(this.notNullSet, out);
          for (int i = 0; i < this.numParams; i++) {
            int sqlType = mapSQLType(this.dtds[i].getJDBCTypeId());
            out.writeByte(sqlType);
            if (this.notNullSet.containsInt(i)) {
              DataValueDescriptor dvd = this.pvs.getParameter(i);
              dvd.toDataForOptimizedResultHolder(out);
            }
          }
        } catch (StandardException se) {
          throw new IOException(se);
        }
      }
      else {
        BitSetSet.toData(this.notNullSet, out);
        for (int i = 0; i < this.numParams; i++) {
          final byte sqlType = this.sqlParamTypes[i];
          final DataValueDescriptor dvd = this.params[i];
          out.writeByte(sqlType);
          if (dvd != null) {
            dvd.toDataForOptimizedResultHolder(out);
          }
        }
      }
    }
  }

  /**
   * Returns the id of the originating <code>GatewayReceiver</code> making the
   * request.
   * 
   * @return the id of the originating <code>GatewayReceiver</code> making the
   *         request
   */
  @Override
  public int getOriginatingDSId() {
    return this.originatingDSId;
  }

  /**
   * Sets the originating <code>SenderId</code> id
   * 
   * @param originatingDSId
   *          The originating <code>SenderId</code> id
   */
  @Override
  public void setOriginatingDSId(int originatingDSId) {
    this.originatingDSId = originatingDSId;
  }

  /**
   * Returns the list of <code>Gateway</code> s to which the event has been
   * sent.
   * 
   * @return the list of <code>Gateway</code> s to which the event has been sent
   */
  @Override
  @SuppressWarnings("unchecked")
  public Set<Integer> getRecipientDSIds() {
    return this.receipientDSIds;
  }

  /**
   * Initialize the original set of recipient <code>Gateway</code>s.
   * 
   * @param originalGatewaysReceivers
   *          The original recipient <code>Gateway</code>s.
   */
  @Override
  public void initializeReceipientDSIds(
      final Collection<Integer> originalGatewaysReceivers) {
    this.receipientDSIds.clear();
    if (originalGatewaysReceivers != null) {
      this.receipientDSIds.addAll(originalGatewaysReceivers);
    }
  }

  @Override
  public Type getType() {
    return this.isBulkInsert ? Type.BULK_INSERT : Type.BULK_DML;
  }

  public final boolean isBulkInsert() {
    return this.isBulkInsert;
  }

  @Override
  public String getDMLString() {
    if(AsyncEventHelper.POSTGRESQL_SYNTAX) {
      return this.dmlString.replace("\"", "");
    }
    return this.dmlString;
  }

  @Override
  public String getSchemaName() {
    return this.schema;
  }

  @Override
  public String getTableName() {
    return getContainer().getQualifiedTableName();
  }

  @Override
  public int getTableSchemaVersion() {
    return getContainer().getCurrentSchemaVersion();
  }

  @Override
  public boolean tableHasAutogeneratedColumns() {
    return getExtraTableInfo().hasAutoGeneratedColumns();
  }

  @Override
  public List<Object> getNewRow() throws UnsupportedOperationException {
    if (!this.isBulkInsert) {
      if (this.pvs != null) {
        return new PVSJavaObjectsList(this.pvs);
      }
      else {
        return new DVDArrayJavaObjectsList(this.params);
      }
    }
    else {
      throw new UnsupportedOperationException(
          "not expected to be invoked for a " + getType() + " operation");
    }
  }

  @Override
  public ResultSet getNewRowsAsResultSet() {
    if (this.isBulkInsert) {
      return new RawStoreResultSet(this.bulkInsertRows.iterator(), null,
          getExtraTableInfo().getRowFormatter());
    }
    else if (this.params != null) {
      return new DVDStoreResultSet(this.params, this.params.length, null, null,
          this);
    }
    else if (this.pvs != null) {
      return new DVDStoreResultSet(this.pvs, this);
    }
    else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getPrimaryKeysAsResultSet()
      throws UnsupportedOperationException {
    if (this.isBulkInsert) {
      final ExtraTableInfo tableInfo = getExtraTableInfo();
      final int[] pkCols = tableInfo.getPrimaryKeyColumns();
      if (pkCols != null) {
        return new RawStoreResultSet(this.bulkInsertRows.iterator(),
            tableInfo.getRowFormatter(), pkCols, tableInfo
                .getPrimaryKeyFormatter().getMetaData());
      }
      else {
        return null;
      }
    }
    else {
      throw new UnsupportedOperationException(
          "not expected to be invoked for a " + getType() + " operation");
    }
  }

  @Override
  public TableMetaData getResultSetMetaData() {
    return getExtraTableInfo().getRowFormatter().getMetaData();
  }

  @Override
  public boolean isOriginRemote() {
    return this.pvs == null;
  }

  @Override
  public boolean isPossibleDuplicate() {
    return this.posDup;
  }

  @Override
  public boolean isTransactional() {
    return this.transactional;
  }

  @Override
  public void setPossibleDuplicate(boolean posDup) {
    this.posDup = posDup;
  }

  @Override
  public boolean isLoad() {
    return false;
  }

  @Override
  public boolean isExpiration() {
    return false;
  }

  @Override
  public boolean isEviction() {
    return false;
  }

  @Override
  public List<Object> getOldRow() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("not expected to be invoked for a "
        + getType() + " operation");
  }

  @Override
  public ResultSet getOldRowAsResultSet() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("not expected to be invoked for a "
        + getType() + " operation");
  }

  @Override
  public int[] getModifiedColumns() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("not expected to be invoked for a "
        + getType() + " operation");
  }

  @Override
  public Object[] getPrimaryKey() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("not expected to be invoked for a "
        + getType() + " operation");
  }

  @Override
  public final boolean hasParameters() {
    return this.numParams > 0 || this.bulkInsertRows != null;
  }

  public final ArrayList<Object> getRawBulkInsertRows() {
    return this.bulkInsertRows;
  }

  public GemFireContainer getContainer() {
    final GemFireContainer container = this.container;
    if (container != null) {
      return container;
    }
    if (this.tableName != null) {
      this.container = (GemFireContainer)Misc.getRegionByPath(
          '/' + this.schema + '/' + this.tableName, true).getUserAttribute();
    }
    return this.container;
  }

  private ExtraTableInfo getExtraTableInfo() {
    if (this.tableInfo != null) {
      return this.tableInfo;
    }
    if (this.isBulkInsert) {
      if (this.bulkInsertRows.size() > 0) {
        return (this.tableInfo = getContainer().getExtraTableInfo(
            this.bulkInsertRows.get(0)));
      }
    }
    return (this.tableInfo = getContainer().getExtraTableInfo());
  }

  private int mapSQLType(int sqlType) throws IllegalStateException {
    switch (sqlType) {
      case Types.JAVA_OBJECT:
        return JAVA_OBJECT;
      case Types.BLOB:
        return BLOB;
      case Types.CLOB:
        return CLOB;
      case Types.SQLXML:
        return SQLXML;
      case JDBC40Translation.JSON:
        return JSON;
      default:
        if (sqlType < Byte.MIN_VALUE || sqlType > Byte.MAX_VALUE) {
          throw new IllegalStateException("Sql type =" + sqlType
              + " not supported as it exceeds Byte limit ");
        }
        else {
          return sqlType;
        }
    }
  }

  @Override
  public int getSizeInBytes() {
    return this.approxSize;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getTableColumnPosition(int column) throws SQLException {
    throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
        Integer.valueOf(column));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getColumnPosition(String columnName) throws SQLException {
    throw Util.generateCsSQLException(SQLState.LANG_COLUMN_NOT_FOUND,
        columnName);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getSchemaVersion() {
    return getExtraTableInfo().getSchemaVersion();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getDeclaredColumnWidth(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return this.dtds[column - 1].getMaximumWidth();
      }
      else {
        return getPrecision(column);
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getColumnCount() throws SQLException {
    return this.numParams;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int isNullable(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return this.dtds[column - 1].isNullable() ? columnNullable
            : columnNoNulls;
      }
      else {
        return columnNullableUnknown;
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSigned(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return this.dtds[column - 1].getTypeId().isNumericTypeId();
      }
      else {
        // don't need to map the type since the remapped types will never be
        // true in the call below
        return DataTypeDescriptor.isNumericType(this.sqlParamTypes[column - 1]);
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      // default to always false
      return false;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      final int sqlType;
      if (this.dtds != null) {
        sqlType = this.dtds[column - 1].getTypeId().getJDBCTypeId();
      }
      else {
        // don't need to map the type since the remapped types will never be
        // true in the call below
        sqlType = this.sqlParamTypes[column - 1];
      }
      switch (sqlType) {
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case Types.CLOB:
        case Types.SQLXML:
          return true;
        default:
          return false;
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSearchable(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      return true;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isCurrency(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      final int sqlType;
      if (this.dtds != null) {
        sqlType = this.dtds[column - 1].getTypeId().getJDBCTypeId();
      }
      else {
        // don't need to map the type since the remapped types will never be
        // true in the call below
        sqlType = this.sqlParamTypes[column - 1];
      }
      switch (sqlType) {
        case Types.DECIMAL:
        case Types.NUMERIC:
          return true;
        default:
          return false;
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isReadOnly(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      return true;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isWritable(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      return false;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      return false;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPrecision(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return DataTypeUtilities.getPrecision(this.dtds[column - 1]);
      }
      else {
        switch (this.sqlParamTypes[column - 1]) {
          case Types.NUMERIC:
          case Types.DECIMAL:
            return JDBC30Translation.UNKNOWN_PRECISION;
          case Types.SMALLINT:
            return 5;
          case Types.INTEGER:
            return 10;
          case Types.BIGINT:
            return 19;
          case Types.FLOAT:
            return 15;
          case Types.REAL:
            // This is the number of signed digits for IEEE float with mantissa
            // 24, ie. 2^24
            return 7;
          case Types.DOUBLE:
            // This is the number of signed digits for IEEE float with mantissa
            // 24, ie. 2^24
            return 15;
          case Types.CHAR:
          case Types.VARCHAR:
          case Types.LONGVARCHAR:
          case Types.BINARY:
          case Types.VARBINARY:
          case Types.LONGVARBINARY:
          case CLOB:
          case BLOB:
          case JSON:
            return JDBC30Translation.UNKNOWN_PRECISION;
          case Types.DATE:
            return 10;
          case Types.TIME:
            return 8;
          case Types.TIMESTAMP:
            return 26;
          case JAVA_OBJECT:
            return JDBC30Translation.UNKNOWN_PRECISION;
          default:
            throw Util.generateCsSQLException(SQLState.UNSUPPORTED_TYPE);
        }
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScale(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return this.dtds[column - 1].getScale();
      }
      else {
        switch (this.sqlParamTypes[column - 1]) {
          case Types.TIMESTAMP:
            return 6;
          default:
            return JDBC30Translation.UNKNOWN_SCALE;
        }
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getColumnType(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return this.dtds[column - 1].getTypeId().getJDBCTypeId();
      }
      else {
        final byte sqlType = this.sqlParamTypes[column - 1];
        switch (sqlType) {
          case BLOB:
            return Types.BLOB;
          case CLOB:
            return Types.CLOB;
          case JAVA_OBJECT:
            return Types.JAVA_OBJECT;
          case SQLXML:
            return Types.SQLXML;
          case JSON:
            return JDBC40Translation.JSON;
          default:
            return sqlType;
        }
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getColumnTypeName(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return this.dtds[column - 1].getTypeId().getSQLTypeName();
      }
      else {
        switch (this.sqlParamTypes[column - 1]) {
          case Types.BOOLEAN:
            return TypeId.BOOLEAN_NAME;
          case Types.INTEGER:
            return TypeId.INTEGER_NAME;
          case Types.SMALLINT:
            return TypeId.SMALLINT_NAME;
          case Types.TINYINT:
            return TypeId.TINYINT_NAME;
          case Types.BIGINT:
            return TypeId.LONGINT_NAME;
          case Types.DECIMAL:
            return TypeId.DECIMAL_NAME;
          case Types.DOUBLE:
            return TypeId.DOUBLE_NAME;
          case Types.REAL:
            return TypeId.REAL_NAME;
          case Types.CHAR:
            return TypeId.CHAR_NAME;
          case Types.VARCHAR:
            return TypeId.VARCHAR_NAME;
          case Types.LONGVARCHAR:
            return TypeId.LONGVARCHAR_NAME;
          case CLOB:
            return TypeId.CLOB_NAME;
          case Types.BINARY:
            return TypeId.BIT_NAME;
          case Types.VARBINARY:
            return TypeId.VARBIT_NAME;
          case Types.LONGVARBINARY:
            return TypeId.LONGVARBIT_NAME;
          case BLOB:
            return TypeId.BLOB_NAME;
          case Types.DATE:
            return TypeId.DATE_NAME;
          case Types.TIME:
            return TypeId.TIME_NAME;
          case Types.TIMESTAMP:
            return TypeId.TIMESTAMP_NAME;
          case SQLXML:
            return TypeId.XML_NAME;
          case JSON:
            return TypeId.JSON_NAME;
          case JAVA_OBJECT:
            final DataValueDescriptor dvd = this.params[column - 1];
            if (dvd != null) {
              try {
                final Object o = dvd.getObject();
                if (o != null) {
                  return o.getClass().getName();
                }
              } catch (StandardException se) {
                throw Util.generateCsSQLException(se);
              }
            }
            return "NULL UDT";
          default:
            throw Util.generateCsSQLException(SQLState.UNSUPPORTED_TYPE);
        }
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getColumnClassName(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      if (this.dtds != null) {
        return this.dtds[column - 1].getTypeId().getResultSetMetaDataTypeName();
      }
      else {
        switch (this.sqlParamTypes[column - 1]) {
          case Types.BIT:
          case Types.BOOLEAN:
            return "java.lang.Boolean";
          case Types.TINYINT:
            return "java.lang.Integer";
          case Types.SMALLINT:
            return "java.lang.Integer";
          case Types.INTEGER:
            return "java.lang.Integer";
          case Types.BIGINT:
            return "java.lang.Long";
          case Types.FLOAT:
          case Types.REAL:
            return "java.lang.Float";
          case Types.DOUBLE:
            return "java.lang.Double";
          case Types.NUMERIC:
          case Types.DECIMAL:
            return "java.math.BigDecimal";
          case Types.CHAR:
          case Types.VARCHAR:
          case Types.LONGVARCHAR:
            return "java.lang.String";
          case Types.DATE:
            return "java.sql.Date";
          case Types.TIME:
            return "java.sql.Time";
          case Types.TIMESTAMP:
            return "java.sql.Timestamp";
          case Types.BINARY:
          case Types.VARBINARY:
          case Types.LONGVARBINARY:
            return "byte[]";
          case BLOB:
            return "java.sql.Blob";
          case CLOB:
            return "java.sql.Clob";
          case SQLXML:
            return "com.pivotal.gemfirexd.internal.iapi.types.XML";
          case JSON:
            return "com.pivotal.gemfirexd.internal.iapi.types.JSON";
          case JAVA_OBJECT:
            final DataValueDescriptor dvd = this.params[column - 1];
            if (dvd != null) {
              try {
                final Object o = dvd.getObject();
                if (o != null) {
                  return o.getClass().getName();
                }
              } catch (StandardException se) {
                throw Util.generateCsSQLException(se);
              }
            }
            return "NULL UDT";
          default:
            throw Util.generateCsSQLException(SQLState.UNSUPPORTED_TYPE);
        }
      }
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    final int precision = getPrecision(column);
    // use some default display size if not known
    return precision != JDBC30Translation.UNKNOWN_PRECISION ? precision : 15;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getColumnLabel(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      // some dummy label for the column
      return "COLUMN_" + column;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getColumnName(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      // some dummy name for the column
      return "COLUMN_" + column;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSchemaName(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      return this.schema;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTableName(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      return this.tableName;
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getCatalogName(int column) throws SQLException {
    if (column > 0 && column <= this.numParams) {
      return "";
    }
    else {
      throw Util.generateCsSQLException(SQLState.COLUMN_NOT_FOUND,
          Integer.valueOf(column));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    try {
      return iface.cast(this);
    } catch (ClassCastException cce) {
      throw Util.generateCsSQLException(SQLState.UNABLE_TO_UNWRAP, iface);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this);
  }

  static final DataValueDescriptor getNullDVD(int sqlType) {
    switch (sqlType) {
      case Types.BINARY:
        return new SQLBit();
      case Types.BIT:
      case Types.BOOLEAN:
        return new SQLBoolean();
      case Types.CHAR:
        return new SQLChar();
      case Types.DATE:
        return new SQLDate();
      case Types.DOUBLE:
        return new SQLDouble();
      case Types.INTEGER:
        return new SQLInteger();
      case Types.BIGINT:
        return new SQLLongint();
      case Types.DECIMAL:
      case Types.NUMERIC:
        return new SQLDecimal();
      case Types.FLOAT:
      case Types.REAL:
        return new SQLReal();
      case Types.SMALLINT:
        return new SQLSmallint();
      case Types.TIME:
        return new SQLTime();
      case Types.TIMESTAMP:
        return new SQLTimestamp();
      case Types.TINYINT:
        return new SQLTinyint();
      case Types.VARCHAR:
        return new SQLVarchar();
      case Types.LONGVARCHAR:
        return new SQLLongvarchar();
      case Types.VARBINARY:
        return new SQLVarbit();
      case Types.LONGVARBINARY:
        return new SQLLongVarbit();
      case Types.REF:
        return new SQLRef();
      case BLOB:
        final SQLBlob blob = new SQLBlob();
        blob.setWrapBytesForSQLBlob(true);
        return blob;
      case CLOB:
        return new SQLClob();
      case JAVA_OBJECT:
        return new UserType();
      case SQLXML:
        return new XML();
      case JSON:
        return new JSON();
      default:
        throw GemFireXDRuntimeException.newRuntimeException(
            "unexpected SQL type=" + sqlType, null);
    }
  }

  @Override
  public String toString() {
    StringBuilder tempBuffer = new StringBuilder(
        "GfxdCBArgForSynchPrms:- dml string = " + this.dmlString);
    tempBuffer.append("; Schema = " + this.schema);
    tempBuffer.append("; Originator = " + this.originatingDSId);
    tempBuffer.append("; Recipients = " + this.receipientDSIds);
    if (this.bulkInsertRows != null) {
      tempBuffer.append("; Bulk insert rows = " + this.bulkInsertRows.size());
      return tempBuffer.toString();
    }
    tempBuffer.append("; Num Parameters = " + this.numParams);
    tempBuffer.append("; Approx Size = " + this.approxSize);
    if (this.numParams > 0) {
      if (this.pvs != null) {
        for (int i = 0; i < this.numParams; ++i) {
          try {
            DataValueDescriptor dvd = pvs.getParameter(i);
            if (dvd != null && !dvd.isNull()) {
              tempBuffer.append(";  param" + (i + 1) + " = " + dvd.getObject());
            }
            else {
              tempBuffer.append(";  param" + (i + 1) + " = " + null);
            }
            tempBuffer.append(", Jdbc Type = " + this.dtds[i].getJDBCTypeId());
          } catch (StandardException se) {
            tempBuffer.append("Exception = " + se);
          }
        }
      }
      else {
        for (int i = 0; i < this.numParams; ++i) {
          byte sqlType = this.sqlParamTypes[i];
          tempBuffer.append("; param" + (i + 1) + " = " + this.params[i]);
          tempBuffer.append(", Jdbc Type = " + sqlType);
        }
      }
    }
    return tempBuffer.toString();
  }
  
  public boolean isPutDML() {
    return this.isPutDML;
  }
}
