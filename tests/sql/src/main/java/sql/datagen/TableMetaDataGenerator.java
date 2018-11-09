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

package sql.datagen;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;

import hydra.Log;
import util.TestException;

public class TableMetaDataGenerator {
  DataGenerator dagen = DataGenerator.getDataGenerator();
  Mapper mapper = dagen.getMapper();

  public TableMetaData generate(String fullTableName, int rows, String csvFile,
      Connection conn) {
    TableMetaData tableMeta = new TableMetaData(fullTableName);
    tableMeta.setCsvFileName(csvFile);
    tableMeta.setTotalRows(rows);

    // separate schemaname and tablename
    String schemaName = null;
    String tableName = fullTableName;
    int schemaIndex;
    if ((schemaIndex = fullTableName.indexOf('.')) >= 0) {
      schemaName = fullTableName.substring(0, schemaIndex);
      tableName = fullTableName.substring(schemaIndex + 1);
    }

    try {
      final DatabaseMetaData meta = conn.getMetaData();
      final String[] types = { "TABLE" };
      ResultSet rs = meta.getTables(null, schemaName, tableName, types);

      while (rs.next()) {
        if (!tableName.equals(rs.getString("TABLE_NAME"))) {
          throw new RuntimeException("unexpected table name "
              + rs.getString("TABLE_NAME") + ", expected: " + tableName);
        }
      }
      // get primary key columns
      HashSet<String> primaryKeys = new HashSet<>();
      ResultSet keyList = meta.getPrimaryKeys(null, schemaName, tableName);
      while (keyList.next()) {
        String pk = keyList.getString("COLUMN_NAME");
        primaryKeys.add(pk);
        tableMeta.addtoPKList(pk);
      }

      // get unique key columns
      HashSet<String> uniqueKeys = new HashSet<>();
      ResultSet uniqueList = meta.getIndexInfo(null, schemaName, tableName,
          true, false);
      while (uniqueList.next()) {
        String u = uniqueList.getString("COLUMN_NAME");
        if (!primaryKeys.contains(u)) {
          uniqueKeys.add(u);
          tableMeta.addToUniqueList(u);
        }
      }

      // get foreign key columns
      HashSet<String> importedKeys = new HashSet<>();
      ResultSet importList = meta.getImportedKeys(null, schemaName, tableName);
      while (importList.next()) {
        String fktable = importList.getString("FKTABLE_NAME");
        String fkColumn = importList.getString("FKCOLUMN_NAME");
        String parentTable = importList.getString("PKTABLE_NAME");
        String parentCol = importList.getString("PKCOLUMN_NAME");

        FKContraint fk = new FKContraint(fktable, fkColumn, parentTable,
            parentCol);
        importedKeys.add(fk.toString());
        tableMeta.addToFKList(fk);
      }

      String s = ((importedKeys.size() > 0) ? " Foreign Keys:" + importedKeys
          : "") + ((uniqueKeys.size() > 0) ? " Unique Keys:" + uniqueKeys : "");

      Log.getLogWriter().info(
          "Parsing Table:" + fullTableName + " with " + rows
              + " rows, primary key: " + primaryKeys + s);

      // get metadata for each column
      ResultSet rsColumns = meta.getColumns(null, schemaName, tableName, null);
      while (rsColumns.next()) {
        ColumnMetaData column = new ColumnMetaData(dagen.rand);
        column.setFullTableName(fullTableName.trim().toUpperCase());
        column.setColumnName(rsColumns.getString("COLUMN_NAME").trim().toUpperCase());
        column.setDataType(rsColumns.getInt("DATA_TYPE"));
        column.setColumnSize(rsColumns.getInt("COLUMN_SIZE"));
        column.setDecimalDigits(rsColumns.getInt("DECIMAL_DIGITS"));
        MappedColumnInfo mappedCol = mapper.getColumnNameMapping().get(
            column.getFullColumnName());
        column.setMappedColumn(mappedCol);

        if (mappedCol instanceof FKMappedColumn) {
          MappedColumnInfo parent = mapper.getColumnNameMapping().get(
              ((FKMappedColumn) mappedCol).getFullFkParentColumn());
          parent.setFKParent(true);
        }

        if (primaryKeys.contains(column.getColumnName())) {
          column.setPrimary(true);
        }

        if (uniqueKeys.contains(column.getColumnName())) {
          column.setUnique(true);
        }

        tableMeta.addColumns(column);
      }

    } catch (SQLException se) {
      throw new TestException(se.getMessage());
    }
    return tableMeta;
  }
}
