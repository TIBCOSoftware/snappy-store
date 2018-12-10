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

package com.pivotal.gemfirexd.internal.catalog;

import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.cache.ExternalTableMetaData;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PolicyTableData;
import io.snappydata.thrift.CatalogMetadataDetails;
import io.snappydata.thrift.CatalogMetadataRequest;
import io.snappydata.thrift.CatalogTableObject;

/**
 * Need to keep GemXD independent of any snappy/spark/hive related
 * classes. An implementation of this can be made which adheres to this
 * interface and can be instantiated when the snappy embedded cluster
 * initializes and set into the GemFireStore instance.
 */
public interface ExternalCatalog {

  /**
   * Wait for initialization of the catalog. Should always be invoked
   * before calling any other method. Fails after waiting for some period
   * of time.
   */
  boolean waitForInitialization();

  /**
   * Will be used by the execution engine to route to JobServer
   * when it finds out that this table is a column table.
   *
   * @return true if the table is column table, false if row/ref table
   */
  boolean isColumnTable(String schema, String tableName, boolean skipLocks);

  /**
   * Will be used by the execution engine to execute query in gemfirexd
   * if tablename is of a row table.
   *
   * @return true if the table is row table, false if column/external table
   */
  boolean isRowTable(CatalogTableObject catalogTable);

  /**
   * Get the schema for a column table in Json format (as in Spark).
   */
  String getColumnTableSchemaAsJson(String schema, String tableName);

  /**
   * Retruns a map of DBs to list of store tables(those tables that
   * are in store DD) in catalog
   */
  Map<String, List<String>> getAllStoreTablesInCatalog();

  /**
   * Removes a table from the external catalog if it exists.
   */
  void removeTableIfExists(String schema, String table, boolean skipLocks);

  /**
   * Returns the schema in which this catalog is created
   */
  String catalogSchemaName();

  /**
   * Get the metadata for all external hive tables (including all their columns).
   */
  List<ExternalTableMetaData> getCatalogTables();

  /**
   * Get the details of all the policies created.
   */
  List<PolicyTableData> getPolicies();

  /**
   * Returns the meta data of the Hive Table
   */
  ExternalTableMetaData getCatalogTableMetadata(String schema, String tableName);

  /**
   * Generic method to get metadata from catalog.
   *
   * @param operation one of the get operation types with prefix CATALOG_ in snappydata.thrift
   * @param request   parameters for <code>operation</code>
   * @param result    the result filled filled in with metadata
   *
   * @return the current region for table metadata lookup if applicable else null
   */
  LocalRegion fillCatalogMetadata(int operation, CatalogMetadataRequest request,
      CatalogMetadataDetails result);

  /**
   * Generic method to update metadata of catalog. This will also perform
   * schema permission checks internally so callers don't need to do it.
   *
   * @param operation one of the update operation types with prefix CATALOG_ in snappydata.thrift
   * @param request   parameters for <code>operation</code>
   * @param user      current user executing the operation who will be checked for
   *                  required permissions
   */
  void updateCatalogMetadata(int operation, CatalogMetadataDetails request, String user);

  /**
   * Get the current catalog schema version
   */
  long getCatalogSchemaVersion();

  void close();
}
