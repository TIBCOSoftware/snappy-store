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

package com.pivotal.gemfirexd.internal.engine.diag;

import java.sql.Clob;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.Iterator;
import java.util.ListIterator;

import com.gemstone.gemfire.internal.cache.ExternalTableMetaData;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplateNoAllNodesRoute;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialClob;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.shared.common.reference.Limits;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A virtual table that shows the hive tables and their columns
 * in a de-normalized form.
 */
public class HiveTablesVTI extends GfxdVTITemplate
    implements GfxdVTITemplateNoAllNodesRoute {

  public static final ThreadLocal<Boolean> SKIP_HIVE_TABLE_CALLS =
      new ThreadLocal<>();

  private final Logger logger = LoggerFactory.getLogger(getClass().getName());

  private Iterator<ExternalTableMetaData> tableMetas;
  private ExternalTableMetaData currentTableMeta;
  private ListIterator<ExternalTableMetaData.Column> currentTableColumns;
  private ExternalTableMetaData.Column currentTableColumn;

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  @Override
  public boolean next() {
    if (this.tableMetas == null) {
      final ExternalCatalog hiveCatalog;
      if (!Boolean.TRUE.equals(HiveTablesVTI.SKIP_HIVE_TABLE_CALLS.get()) &&
          (hiveCatalog = Misc.getMemStore().getExternalCatalog()) != null) {
        try {
          this.tableMetas = hiveCatalog.getHiveTables(true).iterator();
        } catch (Exception e) {
          // log and move on
          logger.warn("ERROR in retrieving Hive tables: " + e.toString());
          this.tableMetas = Collections.emptyIterator();
        }
      } else {
        this.tableMetas = Collections.emptyIterator();
      }
      this.currentTableColumns = Collections.emptyListIterator();
    }
    while (true) {
      if (this.currentTableColumns.hasNext()) {
        this.currentTableColumn = this.currentTableColumns.next();
        this.wasNull = false;
        return true;
      } else if (this.tableMetas.hasNext()) {
        this.currentTableMeta = this.tableMetas.next();
        this.currentTableColumns = this.currentTableMeta.columns.listIterator();
      } else {
        this.currentTableMeta = null;
        this.currentTableColumn = null;
        return false;
      }
    }
  }

  @Override
  protected Object getObjectForColumn(int columnNumber) throws SQLException {
    String provider = this.currentTableMeta.shortProvider;
    switch (columnNumber) {
      case 1: // SCHEMA
        return this.currentTableMeta.schema;
      case 2: // TABLE
        return this.currentTableMeta.entityName;
      case 3: // TYPE
        return this.currentTableMeta.tableType;
      case 4: // PROVIDER
        // show large provider names only for ordinal 0 and avoid repetition
        if (provider == null || provider.length() < 30) {
          return provider;
        } else if (this.currentTableColumns.nextIndex() == 1) {
          return provider;
        } else return "";
      case 5: // SOURCEPATH
        // only show for ordinal 0 and avoid repetition
        if (this.currentTableColumns.nextIndex() == 1) {
          return provider != null && (provider.startsWith("jdbc")
              || provider.endsWith("JdbcRelationProvider"))
              ? currentTableMeta.driverClass : currentTableMeta.dataSourcePath;
        } else return "";
      case 6: // COMPRESSION
        String compression = this.currentTableMeta.compressionCodec;
        return compression != null ? compression
            : ("COLUMN".equals(this.currentTableMeta.tableType)
            ? SystemProperties.SNAPPY_DEFAULT_COMPRESSION_CODEC : null);
      case 7: // COLUMN
        return this.currentTableColumn.name;
      case 8: // TYPEID
        return this.currentTableColumn.typeId;
      case 9: // TYPENAME
        return this.currentTableColumn.typeName;
      case 10: // ORDINAL
        return this.currentTableColumns.nextIndex();
      case 11: // PRECISION
        return this.currentTableColumn.precision;
      case 12: // SCALE
        return this.currentTableColumn.scale;
      case 13: // MAXWIDTH
        return this.currentTableColumn.maxWidth;
      case 14: // NULLABLE
        return this.currentTableColumn.nullable;
      case 15: // VIEWTEXT
        // only show for ordinal 0 and avoid repetition
        if (this.currentTableColumns.nextIndex() == 1) {
          return this.currentTableMeta.viewText;
        } else return null;
      default:
        throw new GemFireXDRuntimeException("unexpected column=" +
            columnNumber + " for HiveTablesVTI");
    }
  }

  @Override
  public Clob getClob(int columnNumber) throws SQLException {
    switch (columnNumber) {
      case 15: // VIEWTEXT
        String viewText = getString(columnNumber);
        return viewText != null ? new HarmonySerialClob(viewText) : null;

      default:
        throw Util.generateCsSQLException(SQLState.LANG_VTI_BLOB_CLOB_UNSUPPORTED,
            "HIVETABLES", columnNumber);
    }
  }

  /**
   * Metadata
   */

  private static final String SCHEMA = "SCHEMANAME";

  private static final String TABLE = "TABLENAME";

  private static final String TYPE = "TABLETYPE";

  private static final String PROVIDER = "PROVIDER";

  private static final String SOURCEPATH = "SOURCEPATH";

  private static final String COMPRESSION = "COMPRESSION";

  private static final String COLUMN = "COLUMNNAME";

  private static final String TYPEID = "TYPEID";

  private static final String TYPENAME = "TYPENAME";

  private static final String ORDINAL = "ORDINAL";

  private static final String PRECISION = "PRECISION";

  private static final String SCALE = "SCALE";

  private static final String MAXWIDTH = "MAXWIDTH";

  private static final String NULLABLE = "NULLABLE";

  private static final String VIEWTEXT = "VIEWTEXT";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(SCHEMA,
          Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(TABLE,
          Types.VARCHAR, false, 512),
      EmbedResultSetMetaData.getResultColumnDescriptor(TYPE,
          Types.VARCHAR, false, 64),
      EmbedResultSetMetaData.getResultColumnDescriptor(PROVIDER,
          Types.VARCHAR, true, 8192),
      EmbedResultSetMetaData.getResultColumnDescriptor(SOURCEPATH,
          Types.VARCHAR, false, Limits.DB2_VARCHAR_MAXWIDTH),
      EmbedResultSetMetaData.getResultColumnDescriptor(COMPRESSION,
          Types.VARCHAR, true, 64),
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
      EmbedResultSetMetaData.getResultColumnDescriptor(MAXWIDTH,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(NULLABLE,
          Types.BOOLEAN, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(VIEWTEXT,
          Types.CLOB, true),
  };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);
}
