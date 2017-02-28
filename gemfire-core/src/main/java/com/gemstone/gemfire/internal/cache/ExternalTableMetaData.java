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
package com.gemstone.gemfire.internal.cache;

public class ExternalTableMetaData {

  public ExternalTableMetaData(String entityName,
      Object schema,
      String tableType,
      Object externalStore,
      int columnBatchSize,
      int columnMaxDeltaRows,
      String compressionCodec,
      String baseTable,
      String dml,
      String[] dependents) {
    this.entityName = entityName;
    this.schema = schema;
    this.tableType = tableType;
    this.externalStore = externalStore;
    this.columnBatchSize = columnBatchSize;
    this.columnMaxDeltaRows = columnMaxDeltaRows;
    this.compressionCodec = compressionCodec;
    this.baseTable = baseTable;
    this.dml = dml;
    this.dependents = dependents;
  }

  public String entityName;
  public Object schema;
  public String tableType;
  // No type specified as the class is in snappy core
  public Object externalStore;
  public int columnBatchSize;
  public int columnMaxDeltaRows;
  public String compressionCodec;
  public String baseTable;
  public String dml;
  public String[] dependents;
}
