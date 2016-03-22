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
package sql.datagen;

import hydra.Log;
import hydra.ProcessMgr;
import hydra.RemoteTestModule;
import hydra.TestConfig;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import sql.SQLPrms;
import sql.generic.SqlUtilityHelper;
import util.TestException;

import com.gemstone.gemfire.LogWriter;

/**
 * DataGenerator
 * 
 * @author Rahul Diyewar
 */

public class DataGenerator {
  public LogWriter log;
  protected static String url = "jdbc:gemfirexd://localhost:1530/";
  protected static String driver = "com.pivotal.gemfirexd.jdbc.ClientDriver";
  public static final Random rand = new Random(SQLPrms.getRandSeed());

  private static DataGenerator datagen = null;
  final private Mapper mapper;
  private Map<String, TableMetaData> tableMetaMap = new HashMap<String, TableMetaData>();
  private int totalThreads = -1;

  private DataGenerator() {
    log = Log.getLogWriter();
    log.info("DataGenerator initialize in this vm");
    mapper = Mapper.getMapper();
  }

  public static DataGenerator getDataGenerator() {
    if (datagen == null) {
      synchronized (DataGenerator.class) {
        if (datagen == null) {
          datagen = new DataGenerator();
        }
      }
    }
    return datagen;
  }

  public Mapper getMapper() {
    return mapper;
  }

  public int getTotalThreads() {
    return totalThreads;
  }

  public synchronized void parseMapperFile(String mapperFile, Connection conn) {
    if (mapperFile != null && (new File(mapperFile).exists())) {
      try {
        mapper.parseMapperFile(mapperFile, conn);
      } catch (Exception e) {
        throw new RuntimeException("Error in parsing mapper file ", e);
      }
    } else {
      throw new RuntimeException("mapper file does not exists : " + mapperFile);
    }
  }

  public Map<String, TableMetaData> getTableMetaMap() {
    return tableMetaMap;
  }

  public void generateCSVs(String[] fullTableName, int[] rows,
      String[] csvFile, Connection conn) {

    // get table meta data
    for (int i = 0; i < fullTableName.length; i++) {
      String tablename = fullTableName[i].trim().toUpperCase();
      fullTableName[i] = tablename;
      TableMetaData tableMeta = new TableMetaDataGenerator().generate(
          tablename, rows[i], csvFile[i], conn);
      tableMetaMap.put(tableMeta.getTableName(), tableMeta);
    }

    // generate CSVs
    for (String tablename : fullTableName) {
      generateCSVPerTable(tableMetaMap.get(tablename), conn);
    }
  }

  public void generateCSVPerTable(TableMetaData table, Connection conn) {
    log.info("Start Generating CSV for " + table.toString());
    long startTime = System.currentTimeMillis();
    final char sep = getFieldSeparator();

    // disable header as it is not supported for derby
    boolean printHeader = false;

    int rows = table.getTotalRows();
    int batchNum = 1000;
    if (rows > 10000)
      batchNum = 10000;
    if (rows > 100000)
      batchNum = 100000;
    if (rows > 1000000)
      batchNum = 1000000;

    batchNum = rows;

    List<ColumnMetaData> columnList = table.getColumns();
    // output file writer
    try {
      FileWriter fstream = new FileWriter(table.getCsvFileName());
      BufferedWriter out = new BufferedWriter(fstream);

      // export column names first
      if (printHeader == true) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columnList.size(); i++) {
          String column = columnList.get(i).getColumnName();
          if (i < columnList.size() - 1) {
            sb.append(column).append(sep);
          } else {
            sb.append(column).append('\n');
          }
        }
        out.write(sb.toString());
      }

      do {
        int rowBatch = (rows - batchNum > 0) ? batchNum : rows;
        rows = rows - rowBatch;

        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> rowsList = getNewRows(table, rowBatch, conn);

        // export values
        for (Map row : rowsList) {
          for (int i = 0; i < columnList.size(); i++) {
            String column = columnList.get(i).getColumnName();
            Object v = row.get(column);
            if (v == null)
              v = "";
            if (v instanceof Timestamp)
              v = timestampToString((Timestamp) v);
            if (v instanceof Date)
              v = dateToString((Date) v);
            sb.append(v);

            if (i < columnList.size() - 1) {
              sb.append(sep);
            } else {
              sb.append('\n');
            }
          }
        }
        out.write(sb.toString());
      } while (rows > 0);

      out.close();
    } catch (IOException e) {
      throw new TestException("Error in writing to CSV for table "
          + table.getTableName(), e);
    }

    log.info("Generated CSV for " + table.getTableName() + " time elapsed: "
        + (System.currentTimeMillis() - startTime) + " ms");
  }

  public List<Map<String, Object>> getNewRows(TableMetaData table,
      int expectedRows, Connection conn) {
    log.info("Generated " + expectedRows + " new rows for table "
        + table.getTableName());

    List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
    List<String> pkList = table.getPKList();
    List<String> uniqueList = table.getUniqueList();
    List<FKContraint> fkConstraintList = table.getFKList();

    int pkCnt = pkList.size();
    int uniqueCnt = uniqueList.size();
    int fkCnt = fkConstraintList.size();

    List<Integer> tids = getTidsList();
    if (totalThreads == -1) {
      totalThreads = tids.size();
    }

    int errors = 0;
    int threshold = expectedRows * 2;
    int newRows = 0;
    boolean done = false;
    while (newRows < expectedRows && !done) {
      int tid = tids.get(rand.nextInt(totalThreads));
      List<String> excludeCols = new ArrayList<String>();
      Map<String, Object> colMap = new HashMap<String, Object>();

      if (fkCnt == 0) {
        // no fk
        if (pkCnt == 0) {
          Map<String, Object> rowData = getRow(table, colMap, tid);
          if (rowData != null) {
            data.add(rowData);
            newRows++;
          } else {
            errors++;
          }
        } else {
          // have pk columns
          int idx = 0;
          do {
            String pkcol = pkList.get(idx);
            ColumnMetaData pkCol = table.getColumnMeta(pkcol);
            Object pkVal = getValueForColumn(table, pkCol, colMap, tid);
            for (int j = 0; j < 10 && pkVal == null; j++) {
              errors++;
              tid = tids.get(rand.nextInt(totalThreads));
              pkVal = getValueForColumn(table, pkCol, colMap, tid);
            }
            if (pkVal != null) {
              MappedColumnInfo mappedCol = pkCol.getMappedColumn();
              int minRepeatVal = 1;
              int maxRepeatVal = -1;
              if (mappedCol != null && mappedCol instanceof FKMappedColumn) {
                minRepeatVal = ((FKMappedColumn) mappedCol).getMinRepeatValue();
                maxRepeatVal = ((FKMappedColumn) mappedCol).getMaxRepeatValue();
              }

              int repeatVal = minRepeatVal;
              if (maxRepeatVal != -1
                  && Math.abs(maxRepeatVal - minRepeatVal) > 0) {
                repeatVal += rand
                    .nextInt(Math.abs(maxRepeatVal - minRepeatVal));
              }

              colMap.put("TID", new Integer(tid));
              colMap.put(pkCol.getColumnName(), pkVal);
              for (int i = 0; i < repeatVal; i++) {
                Map<String, Object> rowData = getRow(table, colMap, tid);
                if (rowData != null) {
                  data.add(rowData);
                  newRows++;
                } else {
                  errors++;
                }
              }
              excludeCols.add(pkCol.getColumnName());
              resetParentFKValueMap(table, excludeCols);
            }
            idx++;
          } while (idx < (pkCnt - 1));
        }
      } else {
        int idx = 0;
        do {
          FKContraint fk = fkConstraintList.get(idx);
          ColumnMetaData fkCol = table.getColumnMetaForFKConstraint(fk);
          Object fkValue = getValueForColumn(table, fkCol, colMap, tid);
          for (int j = 0; j < 10 && fkValue == null; j++) {
            errors++;
            tid = tids.get(rand.nextInt(totalThreads));
            fkValue = getValueForColumn(table, fkCol, colMap, tid);
          }
          if (fkValue != null) {
            MappedColumnInfo mappedCol = fkCol.getMappedColumn();
            int minRepeatVal = 1;
            int maxRepeatVal = -1;
            if (mappedCol != null && mappedCol instanceof FKMappedColumn) {
              minRepeatVal = ((FKMappedColumn) mappedCol).getMinRepeatValue();
              maxRepeatVal = ((FKMappedColumn) mappedCol).getMaxRepeatValue();
            }

            int repeatVal = minRepeatVal;
            if (maxRepeatVal != -1 && Math.abs(maxRepeatVal - minRepeatVal) > 0) {
              repeatVal += rand.nextInt(Math.abs(maxRepeatVal - minRepeatVal));
            }

            colMap.put("TID", new Integer(tid));
            colMap.put(fkCol.getColumnName(), fkValue);
            for (int i = 0; i < repeatVal; i++) {
              Map<String, Object> rowData = getRow(table, colMap, tid);
              if (rowData != null) {
                data.add(rowData);
                newRows++;
              } else {
                errors++;
              }
            }
            excludeCols.add(fkCol.getColumnName());
            resetParentFKValueMap(table, excludeCols);
          }
          idx++;
        } while (idx < (fkCnt - 1));
      }

      if (errors > threshold) {
        log.warning("Error in generating rows for " + table.getTableName()
            + ". Insuffcient rows in FK parent. Generated only " + newRows
            + " rows out of " + expectedRows);
        done = true;
      }
    }

    resetParentFKValueMap(table, new ArrayList<String>());
    return data;
  }

  public Map<String, Object> getRow(TableMetaData table,
      Map<String, Object> columnValueMap, int tid) {
    table.increamentCurrentRowID();
    Map<String, Object> rowData = new HashMap<String, Object>();
    Set<String> keyset = columnValueMap.keySet();
    for (ColumnMetaData columnMeta : table.getColumns()) {
      String colName = columnMeta.getColumnName();
      Object value = null;
      if (keyset.contains(colName)) {
        value = columnValueMap.get(colName);
      } else {
        value = getValueForColumn(table, columnMeta, columnValueMap, tid);
      }

      if (value == null) {
        log.warning("Could not generate value for "
            + columnMeta.getFullColumnName() + " columnValueMap="
            + columnValueMap);
        return null;
      }

      if (value.equals(Mapper.nullToken)) {
        rowData.put(colName, null);
      } else if (value == Mapper.skipToken) {
      } else {
        rowData.put(colName, value);
      }
    }
    table.addToFKCompositeMap(rowData, tid);
    return rowData;
  }

  private Object getValueForColumn(TableMetaData table,
      ColumnMetaData columnMeta, Map<String, Object> columnValueMap, int tid) {
    MappedColumnInfo mapped = columnMeta.getMappedColumn();
    Object value = null;
    if (mapped == null) {
      value = new RandomValueGenerator().generateValues(table, columnMeta, tid);
    } else if (mapped instanceof FixedTokenMappedColumn) {
      value = getValueFromFixedToken((FixedTokenMappedColumn) mapped);
    } else if (mapped instanceof ValueListMappedColumn) {
      value = getValueFromValueList(table, (ValueListMappedColumn) mapped);
    } else if (mapped instanceof FKMappedColumn) {
      value = getValueFromFKParent(table, columnMeta, columnValueMap, tid);
    } else {
      value = new RandomValueGenerator().generateValues(table, columnMeta, tid);
    }

    if (value != null && columnMeta.isFKParent()) {
      columnMeta.addToFKValueMap(tid, value);
    }
    return value;
  }

  private Object getValueFromFixedToken(FixedTokenMappedColumn column) {
    return column.getFixedToken().getNext();
  }

  private Object getValueFromFKParent(TableMetaData table,
      ColumnMetaData colMeta, Map<String, Object> columnValueMap, int tid) {
    FKMappedColumn column = (FKMappedColumn) colMeta.getMappedColumn();
    String parent = column.getFkParentTable();
    TableMetaData parentMeta = tableMetaMap.get(parent);
    ColumnMetaData parentColMeta = parentMeta.getColumnMeta(column
        .getFkParentColumn());

    boolean unique = false;
    if (colMeta.isPrimary() || colMeta.isUnique()) {
      unique = true;
    }

    if (columnValueMap.size() > 0 && table.isCompositeFKColumn(colMeta)) {
      return parentMeta.getValueFromCokmpositeFK(parentColMeta, columnValueMap,
          tid);
    } else {
      return parentColMeta.getRandomValueFromFKList(tid, unique);
    }
  }

  private void resetParentFKValueMap(TableMetaData table,
      List<String> excludeCols) {
    for (ColumnMetaData colMeta : table.getColumns()) {
      if (!excludeCols.contains(colMeta.getColumnName())
          && colMeta.getMappedColumn() != null
          && colMeta.getMappedColumn() instanceof FKMappedColumn) {
        FKMappedColumn column = (FKMappedColumn) colMeta.getMappedColumn();
        String parent = column.getFkParentTable();
        TableMetaData parentMeta = tableMetaMap.get(parent);
        ColumnMetaData parentColMeta = parentMeta.getColumnMeta(column
            .getFkParentColumn());
        parentColMeta.resetFKValueMap();
      }
    }
  }

  private Object getValueFromValueList(TableMetaData table,
      ValueListMappedColumn column) {
    Object[] values = column.getValueList();
    boolean random = column.isRandomize();
    if (random) {
      return values[rand.nextInt(values.length - 1) + 1];
    } else {
      return values[table.getCurrentRowID() % values.length];
    }
  }

  protected char getFieldSeparator() {
    return ',';
  }

  protected String dateToString(Date dt) {
    final SimpleDateFormat formatterTimestamp = new SimpleDateFormat(
        getFormatterTimestamp());
    return formatterTimestamp.format(dt);
  }

  protected String timestampToString(Timestamp ts) {
    final SimpleDateFormat formatterTimestamp = new SimpleDateFormat(
        getFormatterTimestamp());
    return formatterTimestamp.format(ts);
  }

  protected String getFormatterTimestamp() {
    return "yyyy-MM-dd HH:mm:ss";
  }

  protected String getFormatterDate() {
    return "yyyy-MM-dd";
  }

  public List<Integer> getTidsList() {
    ArrayList<Integer> tids = new ArrayList<Integer>();
    TestConfig tc = TestConfig.getInstance();
    int num = tc.getTotalThreads();
    for (int i = 0; i < num; i++) {
      tids.add(new Integer(i));
    }
    return tids;
  }

  public static Connection getConnection() {
    Connection con = null;
    try {
      Class.forName(driver);
    } catch (java.lang.ClassNotFoundException e) {
      System.out.println("ClassNotFoundException: " + e.getMessage());
      System.exit(3);
    }

    try {
      java.util.Properties p = new java.util.Properties();
      // p.setProperty("user", "locatoradmin");
      // p.setProperty("password", "locatorpassword");
      con = DriverManager.getConnection(url, p);
    } catch (SQLException ex) {
      System.out.println("SQLException: " + ex.getMessage());
      System.exit(3);
    }

    return con;
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      String usage = "Usage: DataGenerator <table-names> [<host:port> <row-counts> <mapper-file>]"
          + "\n table-names  => comma separated table names"
          + "\n row-counts   => comma separated row-counts"
          + "\n mapper-file  => column mapper files";
      System.out.println(usage);
      System.exit(1);
    }

    String[] tableNames = null;
    int[] rowCounts = null;
    String mapperFile = null;

    // tablenames[]
    tableNames = args[0].split(",");

    // host[port]
    if (args.length > 1) {
      url = "jdbc:gemfirexd://" + args[1];
      System.out.println("url=" + url);
    }

    // rowcounts[]
    if (args.length > 2) {
      String[] multipleRowCount = args[2].split(",");
      rowCounts = new int[multipleRowCount.length];
      for (int i = 0; i < multipleRowCount.length; i++) {
        rowCounts[i] = Integer.parseInt(multipleRowCount[i]);
      }
    }

    // mapperfile
    if (args.length > 3) {
      mapperFile = args[3];
    }

    String[] outputFiles = new String[tableNames.length];
    for (int i = 0; i < tableNames.length; i++) {
      outputFiles[i] = tableNames[i] + ".csv";
    }

    Connection conn = getConnection();
    DataGenerator dg = null;
    try {
      dg = new DataGenerator();
      dg.log = Log.createLogWriter("datagenerator", "datagenerator_"
          + ProcessMgr.getProcessId(), true, "INFO", 0);
      dg.parseMapperFile(mapperFile, conn);
      dg.generateCSVs(tableNames, rowCounts, outputFiles, conn);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
