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
      Object externalStore,
      int cachedBatchSize,
      Boolean useCompression,
      String baseTable,
      String dml,
      String[] dependents) {
    this.entityName = entityName;
    this.schema = schema;
    this.externalStore = externalStore;
    this.cachedBatchSize = cachedBatchSize;
    this.useCompression = useCompression;
    this.baseTable = baseTable;
    this.dml = dml;
    this.dependents = dependents;
  }

  public String entityName;
  public Object schema;
  // No type specified as the class is in snappy core
  public Object externalStore;
  public int cachedBatchSize;
  public boolean useCompression;
  public String baseTable;
  public String dml;
  public String[] dependents;

}
