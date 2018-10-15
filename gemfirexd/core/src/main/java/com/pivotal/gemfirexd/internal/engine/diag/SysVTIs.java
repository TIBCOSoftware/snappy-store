/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.diag;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Iterator;

import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplateNoAllNodesRoute;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;

/**
 * A virtual table that shows all the virtual tables.
 */
public class SysVTIs extends GfxdVTITemplate
    implements GfxdVTITemplateNoAllNodesRoute {

  public static final String LOCAL_VTI = "LOCAL VTI";

  private Iterator<GemFireXDUtils.Pair<TableDescriptor, ResultSetMetaData>> vtis;
  private GemFireXDUtils.Pair<TableDescriptor, ResultSetMetaData> currentVTI;
  private int currentVTIColumn;

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  @Override
  public boolean next() throws SQLException {
    if (this.vtis == null) {
      this.vtis = Misc.getMemStoreBooting().getDatabase().getDataDictionary()
          .getRegisteredVTIs().iterator();
    }
    while (true) {
      if (this.currentVTI != null &&
          this.currentVTIColumn < this.currentVTI.getValue().getColumnCount()) {
        this.currentVTIColumn++;
        this.wasNull = false;
        return true;
      } else if (this.vtis.hasNext()) {
        this.currentVTI = this.vtis.next();
        this.currentVTIColumn = 0;
      } else {
        this.currentVTI = null;
        this.currentVTIColumn = Integer.MAX_VALUE;
        return false;
      }
    }
  }

  @Override
  protected Object getObjectForColumn(int columnNumber) throws SQLException {
    switch (columnNumber) {
      case 1: // SCHEMA
        return this.currentVTI.getKey().getSchemaName();
      case 2: // TABLE
        return this.currentVTI.getKey().getName();
      case 3: // TYPE
        return this.currentVTI.getKey().routeQueryToAllNodes()
            ? LOCAL_VTI : "VIRTUAL TABLE";
      case 4: // COLUMN
        return this.currentVTI.getValue().getColumnName(this.currentVTIColumn);
      case 5: // TYPEID
        return this.currentVTI.getValue().getColumnType(this.currentVTIColumn);
      case 6: // TYPENAME
        return this.currentVTI.getValue().getColumnTypeName(this.currentVTIColumn);
      case 7: // ORDINAL
        return this.currentVTIColumn;
      case 8: // PRECISION
        return this.currentVTI.getValue().getPrecision(this.currentVTIColumn);
      case 9: // SCALE
        return this.currentVTI.getValue().getScale(this.currentVTIColumn);
      case 10: // DISPLAYWIDTH
        return this.currentVTI.getValue().getColumnDisplaySize(this.currentVTIColumn);
      case 11: // NULLABLE
        return this.currentVTI.getValue().isNullable(this.currentVTIColumn) !=
            ResultSetMetaData.columnNoNulls;
      default:
        throw new GemFireXDRuntimeException("unexpected column=" +
            columnNumber + " for SysVTIs");
    }
  }

  /**
   * Metadata
   */

  private static final String SCHEMA = "SCHEMANAME";

  private static final String TABLE = "TABLENAME";

  private static final String TYPE = "TABLETYPE";

  private static final String COLUMN = "COLUMNNAME";

  private static final String TYPEID = "TYPEID";

  private static final String TYPENAME = "TYPENAME";

  private static final String ORDINAL = "ORDINAL";

  private static final String PRECISION = "PRECISION";

  private static final String SCALE = "SCALE";

  private static final String DISPLAYWIDTH = "DISPLAYWIDTH";

  private static final String NULLABLE = "NULLABLE";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(SCHEMA,
          Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(TABLE,
          Types.VARCHAR, false, 512),
      EmbedResultSetMetaData.getResultColumnDescriptor(TYPE,
          Types.VARCHAR, false, 64),
      EmbedResultSetMetaData.getResultColumnDescriptor(COLUMN,
          Types.VARCHAR, false, 512),
      EmbedResultSetMetaData.getResultColumnDescriptor(TYPEID,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(TYPENAME,
          Types.VARCHAR, false, 512),
      EmbedResultSetMetaData.getResultColumnDescriptor(ORDINAL,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(PRECISION,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(SCALE,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(DISPLAYWIDTH,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(NULLABLE,
          Types.BOOLEAN, false),
  };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);
}
