/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.snappy.hivetables.dto;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.ExternalTableMetaData;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.diag.HiveTablesVTI;
import com.pivotal.gemfirexd.internal.snappy.hivetables.ExternalHiveTablesCollectorFunction;

/**
 * Result returned by {@link ExternalHiveTablesCollectorFunction}. Contains Collection of
 * {@link ExternalTableMetaData}.
 * <br/>
 * <b>Important Note:</b> Only contains those fields of {@link ExternalTableMetaData} which are
 * required by {@link HiveTablesVTI} are serialized. Remaining fields will be null.
 */
public class ExternalHiveTablesCollectorResult extends GfxdDataSerializable {

  private Collection<ExternalTableMetaData> tablesMetadata;

  public ExternalHiveTablesCollectorResult(Collection<ExternalTableMetaData> tablesMetadata) {
    this.tablesMetadata = tablesMetadata;
  }

  public ExternalHiveTablesCollectorResult() {
  }

  @Override
  public byte getGfxdID() {
    return HIVE_TABLES_COLLECTOR_RESULT;
  }

  public Collection<ExternalTableMetaData> getTablesMetadata() {
    return tablesMetadata;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    InternalDataSerializer.writeArrayLength(tablesMetadata.size(), out);
    for (ExternalTableMetaData md : tablesMetadata) {
      DataSerializer.writeString(md.entityName, out);
      DataSerializer.writeObject(md.schema, out);
      DataSerializer.writeString(md.tableType, out);
      DataSerializer.writeString(md.compressionCodec, out);
      DataSerializer.writeString(md.shortProvider, out);
      DataSerializer.writeString(md.dataSourcePath, out);
      DataSerializer.writeString(md.viewText, out);
      InternalDataSerializer.writeArrayLength(md.columns.size(), out);
      for (ExternalTableMetaData.Column c : md.columns) {
        DataSerializer.writeString(c.name, out);
        out.writeInt(c.typeId);
        DataSerializer.writeString(c.typeName, out);
        out.writeInt(c.precision);
        out.writeInt(c.scale);
        out.writeInt(c.maxWidth);
        out.writeBoolean(c.nullable);
      }
    }
  }

  @Override
  public void fromData(DataInput out) throws IOException,
      ClassNotFoundException {
    int tableMetadataSize = InternalDataSerializer.readArrayLength(out);
    this.tablesMetadata = new ArrayList<>(tableMetadataSize);
    for (int i = 0; i < tableMetadataSize; i++) {
      ExternalTableMetaData md = new ExternalTableMetaData();
      md.entityName = DataSerializer.readString(out);
      md.schema = DataSerializer.readObject(out);
      md.tableType = DataSerializer.readString(out);
      md.compressionCodec = DataSerializer.readString(out);
      md.shortProvider = DataSerializer.readString(out);
      md.dataSourcePath = DataSerializer.readString(out);
      md.viewText = DataSerializer.readString(out);
      int columnsSize = InternalDataSerializer.readArrayLength(out);
      List<ExternalTableMetaData.Column> columns = new ArrayList<>(columnsSize);
      for (int j = 0; j < columnsSize; j++) {
        ExternalTableMetaData.Column c = new ExternalTableMetaData.Column(
            DataSerializer.readString(out), out.readInt(), DataSerializer.readString(out),
            out.readInt(), out.readInt(), out.readInt(), out.readBoolean());
        columns.add(c);
      }
      md.columns = columns;
      this.tablesMetadata.add(md);
    }
  }
}
