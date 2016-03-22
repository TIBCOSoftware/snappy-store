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

import hydra.FileUtil;
import hydra.Log;
import hydra.MasterController;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import sql.SQLPrms;
import sql.generic.SqlUtilityHelper;
import util.TestException;

import com.gemstone.gemfire.LogWriter;
import com.google.common.primitives.Ints;

/**
 * DataGenerator Helper provides static methods to access functionality of
 * DataGenerator
 * 
 * @author Rahul Diyewar
 */

public class DataGeneratorHelper {
  static public LogWriter log = Log.getLogWriter();

  static DataGenerator datagen = null;

  final static Map<String, Queue<Map<String, Object>>> tableRowsQueueMap = new HashMap<String, Queue<Map<String, Object>>>();

  /**
   * Initialise and host instance of DataGenerator. It also creates CSVs for
   * populate task and rows in DataGeneratorBB for Insert.
   * 
   * @param mapper
   *          mapper file
   * @param tableNames
   *          tablesNames
   * @param rowCounts
   *          Rows count for CSVs
   * @param conn
   *          GemfireXD connection
   */
  public static synchronized void initDataGenerator(String mapper,
      String[] tableNames, int[] rowCounts, Connection conn) {
    
    if (DataGeneratorBB.getBB().getSharedCounters()
        .incrementAndRead(DataGeneratorBB.PARSE_MAPPER) == 1) {
      if (datagen == null) {        
        File mfile = new File(mapper);
        String destMapper = null;
        if(mfile.exists()){
          destMapper = "datagenmapper.prop";
          FileUtil.copyFile(mapper, destMapper);
        }else{
          log.warning("Mapper file does not Exists: " + mapper);
        }
        
        log.info("Init DataGenerator in this vm with mapper : " + destMapper);
        try {
          datagen = DataGenerator.getDataGenerator();
          datagen.parseMapperFile(destMapper, conn);
        } catch (Exception e) {
          throw new TestException("", e);
        }

        // create csv for populate task
        generateCSVsForPopulate(datagen, tableNames, rowCounts, conn);

        // create rows in BB for insert. isInsertInBB is default true.
        if(SQLPrms.isInsertInBB()){
          generateRowsInBBForInserts(datagen, tableNames, conn);
        }
      }else{
        throw new TestException("Datagenerator is already initialized");
      }    
    }else{
      log.info("DataGenerator is already initialized by other vm");
    }
  }

  private static void generateCSVsForPopulate(final DataGenerator datagen, final String[] tableNames,
      final int[] rowCounts, final Connection conn) {
    String[] outputFiles = new String[tableNames.length];
    for (int i = 0; i < tableNames.length; i++) {
      outputFiles[i] = tableNames[i].trim().toUpperCase() + "_populate.csv";
      DataGeneratorBB.setCSVFileName(tableNames[i], outputFiles[i]);
    }
    log.info("Generating CSVs for tables=" + Arrays.asList(tableNames)
        + ", rows=" + Ints.asList(rowCounts) 
        + ", outputFiles=" + Arrays.asList(outputFiles));
   
    datagen.generateCSVs(tableNames, rowCounts, outputFiles, conn);
  }

  private static void generateRowsInBBForInserts(final DataGenerator datagen, final String[] fullTableNames,
      final Connection conn) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        while (true) {
          for (String key : datagen.getTableMetaMap().keySet()) {
            TableMetaData table = datagen.getTableMetaMap().get(key);
            String tableName = table.getTableName();
            if (DataGeneratorBB.isRowsLowThresholdReached(tableName)) {
              log.info("Adding 1000 new rows in BB for insert for table "
                  + tableName);
              List<Map<String, Object>> rowList;
              try {
                rowList = datagen.getNewRows(table,1000, conn);
                DataGeneratorBB.addTableRowsToBB(tableName, rowList);
                DataGeneratorBB.setRowsLowThresholdReached(tableName, false);
              } catch (Exception e) {
               throw new TestException("Error in creating rows in BB for insert ", e);
              }
                            
            }
          }
          log.info("DataGenerator - datapopulator for insert, Sleeping for 2 sec");
          MasterController.sleepForMs(2 * 1000);
        }
      }
    }, "DataGenerator_insertRows").start();
  }

  /**
   * Get new row for the table form BlackBoard
   * 
   * @param fullTableName
   *          Format SchemaName.TableName
   * @return Map<String, Object> key=fully qualified columnName (SCHEMANAME.TABLENAME.COLUMNNAME) in upper case, value=Object
   */
  public static Map<String, Object> getNewRow(String fullTableName) {
    String key = fullTableName.toUpperCase();
    Queue<Map<String, Object>> tableRowsQueue = null;
    Map<String, Object> rowMap = null; 
    synchronized (tableRowsQueueMap) {
      tableRowsQueue = tableRowsQueueMap.get(key);
      if (tableRowsQueue == null) {
        DataGeneratorBB.getBB();
        tableRowsQueue = DataGeneratorBB.getRowsFromBB(key);
        tableRowsQueueMap.put(key, tableRowsQueue);
        log.info("Initialize local rows cache for table " + fullTableName
            + " with cached is " + tableRowsQueue.size() + " rows");
      }
      else if (tableRowsQueue.size() < 10) {
        // populate 100 rows from blackboard
        tableRowsQueue.addAll(DataGeneratorBB.getRowsFromBB(key));
        log.info("Added 100 rows into local cache for table " + fullTableName
            + " total cached is " + tableRowsQueue.size());
      }
      rowMap = tableRowsQueue.poll(); 
    }
    
    if (rowMap == null){
      log.info("DataGenerator - No row in local queue, waiting for 2 sec");
      MasterController.sleepForMs(2 * 1000);
      rowMap = getNewRow(fullTableName);
    }
    return rowMap;
  }
  
  public static Object getTidsList() {
    ArrayList<Integer> tids = new ArrayList<Integer>();
    // todo - get correct tids
    int num = 100;
    for (int i = 0; i <= num; i++) {
      tids.add(new Integer(i));
    }
    return tids;
  }
}
