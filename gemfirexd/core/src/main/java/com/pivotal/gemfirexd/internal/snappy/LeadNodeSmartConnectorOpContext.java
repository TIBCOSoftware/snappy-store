/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.snappy;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.conn.GenericLanguageConnectionContext;

/**
 * A class that holds parameters for operations to be done do for LeadNodeSmartConnectorOpMsg
 */
public final class LeadNodeSmartConnectorOpContext implements GfxdSerializable {

  public enum OpType { CREATE_TABLE, DROP_TABLE, CREATE_INDEX, DROP_INDEX, CREATE_UDF, DROP_UDF, ALTER_TABLE }

  private OpType type;

  // not all of these members are always used
  // these are used based on the OpType
  private String tableIdentifier;
  private String userSpecifiedJsonSchema;
  private String schemaDDL;
  private String provider;
  private byte[] options;
  private byte[] mode;
  private Boolean isBuiltIn = true;
  private Boolean ifExists = false;
  private String indexIdentifier; //set only by index operations
  private byte[] indexColumns; //set only by index operations

  private String db; // for udf
  private String functionName; // for udf
  private String className; // for udf
  private String jarURI; // for udf

  private String user; // for security
  private String authToken; // for security

  private Boolean addOrDropCol; // for alter table
  private String columnName; // for alter table
  private String columnDataType; // for alter table
  private Boolean columnNullable; // for alter table

  public LeadNodeSmartConnectorOpContext() {

  }

  public LeadNodeSmartConnectorOpContext(OpType type,
      String tableIdentifier,
      String provider,
      String userSpecifiedJsonSchema,
      String schemaDDL,
      byte[] mode,
      byte[] options,
      Boolean isBuiltIn,
      Boolean ifExists,
      String indexIdentifier,
      byte[] indexColumns,
      String db,
      String functionName,
      String className,
      String jarURI,
      Boolean addOrDropCol,
      String columnName,
      String columnDataType,
      Boolean columnNullable) {
    this.type = type;
    this.tableIdentifier = tableIdentifier;
    this.provider = provider;
    this.userSpecifiedJsonSchema = userSpecifiedJsonSchema;
    this.schemaDDL = schemaDDL;
    this.mode = mode;
    this.options = options;
    this.isBuiltIn = isBuiltIn;
    this.ifExists = ifExists;
    this.indexIdentifier = indexIdentifier;
    this.indexColumns = indexColumns;
    this.db = db;
    this.functionName = functionName;
    this.className = className;
    this.jarURI = jarURI;
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc != null) {
      this.user = ((GenericLanguageConnectionContext)lcc).getUserName();
      this.authToken = ((GenericLanguageConnectionContext)lcc).getAuthToken();
    }
    this.addOrDropCol = addOrDropCol;
    this.columnName = columnName;
    this.columnDataType = columnDataType;
    this.columnNullable = columnNullable;
  }

  @Override
  public byte getGfxdID() {
    return LEAD_NODE_CONN_OP_CTX;
  }

  @Override
  public int getDSFID() {
    return DataSerializableFixedID.GFXD_TYPE;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeObject(type, out);
    DataSerializer.writeString(tableIdentifier, out);
    DataSerializer.writeString(userSpecifiedJsonSchema, out);
    DataSerializer.writeByteArray(indexColumns, out);
    DataSerializer.writeString(indexIdentifier, out);
    DataSerializer.writeString(schemaDDL, out);
    DataSerializer.writeString(provider, out);
    DataSerializer.writeByteArray(mode, out);
    DataSerializer.writeByteArray(options, out);
    DataSerializer.writeBoolean(isBuiltIn, out);
    DataSerializer.writeBoolean(ifExists, out);
    DataSerializer.writeString(db, out);
    DataSerializer.writeString(functionName, out);
    DataSerializer.writeString(className, out);
    DataSerializer.writeString(jarURI, out);
    DataSerializer.writeString(this.user, out);
    DataSerializer.writeString(this.authToken, out);
    DataSerializer.writeBoolean(addOrDropCol, out);
    DataSerializer.writeString(columnName, out);
    DataSerializer.writeString(columnDataType, out);
    DataSerializer.writeBoolean(columnNullable, out);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    this.type = DataSerializer.readObject(in);
    this.tableIdentifier = DataSerializer.readString(in);
    this.userSpecifiedJsonSchema = DataSerializer.readString(in);
    this.indexColumns = DataSerializer.readByteArray(in);
    this.indexIdentifier = DataSerializer.readString(in);
    this.schemaDDL = DataSerializer.readString(in);
    this.provider = DataSerializer.readString(in);
    this.mode = DataSerializer.readByteArray(in);
    this.options = DataSerializer.readByteArray(in);
    this.isBuiltIn = DataSerializer.readBoolean(in);
    this.ifExists = DataSerializer.readBoolean(in);
    this.db = DataSerializer.readString(in);
    this.functionName = DataSerializer.readString(in);
    this.className = DataSerializer.readString(in);
    this.jarURI = DataSerializer.readString(in);
    this.user = DataSerializer.readString(in);
    this.authToken = DataSerializer.readString(in);
    this.addOrDropCol = DataSerializer.readBoolean(in);
    this.columnName = DataSerializer.readString(in);
    this.columnDataType = DataSerializer.readString(in);
    this.columnNullable = DataSerializer.readBoolean(in);
  }

  @Override
  public Version[] getSerializationVersions() {
    return new Version[0];
  }

  public String getTableIdentifier() {
    return tableIdentifier;
  }

  public String getProvider() {
    return provider;
  }

  public byte[] getOptions() {
    return options;
  }

  public OpType getType() {
    return type;
  }

  public String getUserSpecifiedJsonSchema() {
    return userSpecifiedJsonSchema;
  }

  public String getSchemaDDL() {
    return schemaDDL;
  }

  public byte[] getMode() {
    return mode;
  }

  public Boolean getIsBuiltIn() {
    return isBuiltIn;
  }

  public Boolean getIfExists() {
    return ifExists;
  }

  public String getIndexIdentifier() {
    return indexIdentifier;
  }

  public byte[] getIndexColumns() { return indexColumns; }

  public String getDb() { return db;}

  public String getFunctionName() { return functionName; }

  public String getClassName() { return className; }

  public String getjarURI() { return jarURI; }

  public String getUserName() { return this.user; }

  public String getAuthToken() { return this.authToken; }

  public Boolean getAddOrDropCol() { return addOrDropCol; }

  public String getColumnName() { return columnName; }

  public String getColumnDataType() { return columnDataType; }

  public Boolean getColumnNullable() { return columnNullable; }

}
