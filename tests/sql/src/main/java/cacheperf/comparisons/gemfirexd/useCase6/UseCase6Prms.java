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
package cacheperf.comparisons.gemfirexd.useCase6;

import hydra.BasePrms;
import hydra.HydraConfigException;
import hydra.HydraVector;
import hydra.TestConfig;

import java.util.Vector;

public class UseCase6Prms extends BasePrms {

  static {
    setValues(UseCase6Prms.class);
  }
  public static Long mapperFile;
  /**
   * (String)
   * DDL file to use.
   */
  public static Long ddlFile;
  public static String getDDLFile() {
    Long key = ddlFile;
    return tasktab().stringAt(key, tab().stringAt(key));
  }

  /**
   * (int)
   * Number of buckets per server.
   */
  public static Long numBuckets;
  public static int getNumBuckets() {
    Long key = numBuckets;
    return tab().intAt(key, 113);
  }

  /**
   * (boolean)
   * Whether to record statement statistics.  Defaults to false.
   */
  public static Long timeStmts;
  public static boolean timeStmts() {
    Long key = timeStmts;
    return tasktab().booleanAt(key, tab().booleanAt(key, false));
  }

  /**(int) dmlTables -- which tables to be used in the test based on ddl stmt in the conf
   * this is used by DMLStmtsFactory to create dmlStmt.
   * Space serparated statements
   */
  public static Long dmlTables;
  /**
   * which tables are created
   * @return String array of table names
   */
  @SuppressWarnings("unchecked")
  public static String[] getTableNames() {
    Vector tables = tasktab().vecAt(UseCase6Prms.dmlTables,  TestConfig.tab().vecAt(UseCase6Prms.dmlTables, new HydraVector()));
    String[] strArr = new String[tables.size()];
    for (int i = 0; i < tables.size(); i++) {
      strArr[i] = (String)tables.elementAt(i); //get what tables are in the tests
    }
    return strArr;
  }

  /** (int) initial row count for each table to be populated in generic test framework
   *
   */

  public static Long initialRowCount;
  @SuppressWarnings("unchecked")
  public static int[] getInitialRowCountToPopulateTable() {
    Vector counts = tasktab().vecAt(UseCase6Prms.initialRowCount,  TestConfig.tab().vecAt(UseCase6Prms.initialRowCount, new HydraVector()));
    int size = counts.size();
    int defaultCountSize = 1000;
    boolean useDefaultCount = false;
    if (counts.size() == 0) {
      Vector tables = tasktab().vecAt(UseCase6Prms.dmlTables,  TestConfig.tab().vecAt(UseCase6Prms.dmlTables, new HydraVector()));
      size = tables.size();
      useDefaultCount = true;
    }

    int[] intArr = new int[size];
    for (int i = 0; i < size; i++) {
      intArr[i] = useDefaultCount? defaultCountSize: Integer.parseInt((String)counts.elementAt(i));
    }
    return intArr;
  }
}
