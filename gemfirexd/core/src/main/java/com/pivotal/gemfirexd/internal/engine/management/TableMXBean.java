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
package com.pivotal.gemfirexd.internal.engine.management;

import java.beans.ConstructorProperties;
import java.io.Serializable;
import java.util.List;
import com.gemstone.gemfire.management.EvictionAttributesData;
import com.gemstone.gemfire.management.PartitionAttributesData;
import com.pivotal.gemfirexd.internal.engine.management.impl.TableMBeanBridge;

/**
 * 
 * @author Abhishek Chaudhari
 * @since gfxd 1.0
 */
public interface TableMXBean {
  // [A] MBean Attributes
  // 0. Basic
  /**
   * Name of the table
   * 
   */
  String getName();

  /**
   * Parent schema of the table
   * 
   */
  String getParentSchema();

  /**
   * Server groups associated with table
   * 
   */
  String[] getServerGroups();

  /**
   * Table schema definition
   * 
   */
  List<String> getDefinition();

  // 1. from Region.getAttributes()

  /**
   * Data policy
   * 
   */
  String getPolicy(); // Region.getAttributes()

  /**
   * Partition details
   * 
   */
  String getPartitioningScheme(); // Region.getAttributes().getPartitionAttributes()

  /**
   * Number of inserts for table
   * 
   */
  int getInserts();

  /**
   * Number of updates for table
   * 
   */
  int getUpdates();

  /**
   * Number of deletes for table
   * 
   */
  int getDeletes();

  // 3. from MemoryAnalytics VTI
  /**
   * Entry overhead, in kilobytes. Only reflects the amount of memory required
   * to hold the table row in memory but not including the memory to hold its
   * key and value
   * 
   */
  double getEntrySize();

  /**
   * Key size for a table
   * 
   */
  double getKeySize();

  /**
   * Number of rows in a table
   * 
   */
  long getNumberOfRows();

  // Map<String, TableMBeanBridge.IndexInfoData> getIndexInfo();

  /**
   * Index details e.g. index name, index type, entry size, key size and row
   * count.
   * 
   * This is expensive method which uses memory analytics, iterates through all rows
   * Hence not recommended to be called repetitively
   * 
   */
  TableMBeanBridge.IndexStatsData[] listIndexStats();
  
  
  /**
   * Index details e.g. index name, index type, entry size, key size and row
   * count.
   * 
   */
  TableMBeanBridge.IndexInfoData[] listIndexInfo();

  // [B] MBean Operations
  /**
   * Table information e.g. Table Id, Table Name, table type, schema id, table
   * schema name, lock granularity,server groups, data policy, partition
   * attributes, resolver, expiration attributes, eviction attributes, disk
   * attributes, loader, writer, listners, asynchronous listeners, Gateway
   * enabled/disabled, gateway senders etc
   * 
   */
  TableMetadata fetchMetadata(); // GFXD VTI sys.tables

  // String[] listDiskStores();

  /**
   * Details of eviction policy e.g. Algorithm used for eviction, Maximum
   * entries in Region before eviction starts and Action to be taken if entries
   * reaches maximum value
   * 
   */
  EvictionAttributesData showEvictionAttributes();

  class TableMetadata implements Serializable {
    private static final long serialVersionUID = -205894101751338780L;

    String tableId;
    String tableName;
    String tableType;
    String schemaId;
    String tableSchemaname;
    String lockGranularity;
    String serverGroups;
    String dataPolicy;
    String partitionAttrs;
    String resolver;
    String expirationAttrs;
    String evictionAttrs;
    String diskAttrs;
    String loader;
    String writer;
    String listeners;
    String asyncListeners;
    String gatewayEnabled;
    String gatewaySenders;
    String offHeapEnabled;
    String rowLevelSecurityEnabled;

    @ConstructorProperties(value = { "tableId", "tableName", "tableType",
        "schemaId", "tableSchemaname", "lockGranularity", "serverGroups",
        "dataPolicy", "partitionAttrs", "resolver", "expirationAttrs",
        "evictionAttrs", "diskAttrs", "loader", "writer", "listeners",
        "asyncListeners", "gatewayEnabled", "gatewaySenders", "offHeapEnabled","rowLevelSecurityEnabled" })
    public TableMetadata(String tableId, String tableName, String tableType,
        String schemaId, String tableSchemaname, String lockGranularity,
        String serverGroups, String dataPolicy, String partitionAttrs,
        String resolver, String expirationAttrs, String evictionAttrs,
        String diskAttrs, String loader, String writer, String listeners,
        String asyncListeners, String gatewayEnabled, String gatewaySenders,
        String offHeapEnabled, String rowLevelSecurity) {
      update(tableId, tableName, tableType, schemaId, tableSchemaname,
          lockGranularity, serverGroups, dataPolicy, partitionAttrs, resolver,
          expirationAttrs, evictionAttrs, diskAttrs, loader, writer, listeners,
          asyncListeners, gatewayEnabled, gatewaySenders, offHeapEnabled, rowLevelSecurityEnabled);
    }

    public void update(String tableId, String tableName, String tableType,
        String schemaId, String tableSchemaname, String lockGranularity,
        String serverGroups, String dataPolicy, String partitionAttrs,
        String resolver, String expirationAttrs, String evictionAttrs,
        String diskAttrs, String loader, String writer, String listeners,
        String asyncListeners, String gatewayEnabled, String gatewaySenders,
        String offHeapEnabled, String rowLevelSecurityEnabled) {
      this.tableId = tableId;
      this.tableName = tableName;
      this.tableType = tableType;
      this.schemaId = schemaId;
      this.tableSchemaname = tableSchemaname;
      this.lockGranularity = lockGranularity;
      this.serverGroups = serverGroups;
      this.dataPolicy = dataPolicy;
      this.partitionAttrs = partitionAttrs;
      this.resolver = resolver;
      this.expirationAttrs = expirationAttrs;
      this.evictionAttrs = evictionAttrs;
      this.diskAttrs = diskAttrs;
      this.loader = loader;
      this.writer = writer;
      this.listeners = listeners;
      this.asyncListeners = asyncListeners;
      this.gatewayEnabled = gatewayEnabled;
      this.gatewaySenders = gatewaySenders;
      this.offHeapEnabled = offHeapEnabled;
      this.rowLevelSecurityEnabled = rowLevelSecurityEnabled;

    }

    /**
     * @return the tableId
     */
    public String getTableId() {
      return tableId;
    }

    /**
     * @return the tableName
     */
    public String getTableName() {
      return tableName;
    }

    /**
     * @return the tableType
     */
    public String getTableType() {
      return tableType;
    }

    /**
     * @return the schemaId
     */
    public String getSchemaId() {
      return schemaId;
    }

    /**
     * @return the tableSchemaname
     */
    public String getTableSchemaname() {
      return tableSchemaname;
    }

    /**
     * @return the lockGranularity
     */
    public String getLockGranularity() {
      return lockGranularity;
    }

    /**
     * @return the serverGroups
     */
    public String getServerGroups() {
      return serverGroups;
    }

    /**
     * @return the dataPolicy
     */
    public String getDataPolicy() {
      return dataPolicy;
    }

    /**
     * @return the partitionAttrs
     */
    public String getPartitionAttrs() {
      return partitionAttrs;
    }

    /**
     * @return the resolver
     */
    public String getResolver() {
      return resolver;
    }

    /**
     * @return the expirationAttrs
     */
    public String getExpirationAttrs() {
      return expirationAttrs;
    }

    /**
     * @return the evictionAttrs
     */
    public String getEvictionAttrs() {
      return evictionAttrs;
    }

    /**
     * @return the diskAttrs
     */
    public String getDiskAttrs() {
      return diskAttrs;
    }

    /**
     * @return the loader
     */
    public String getLoader() {
      return loader;
    }

    /**
     * @return the writer
     */
    public String getWriter() {
      return writer;
    }

    /**
     * @return the listeners
     */
    public String getListeners() {
      return listeners;
    }

    /**
     * @return the asyncListeners
     */
    public String getAsyncListeners() {
      return asyncListeners;
    }

    /**
     * @return the gatewayEnabled
     */
    public String getGatewayEnabled() {
      return gatewayEnabled;
    }

    /**
     * @return the gatewaySenders
     */
    public String getGatewaySenders() {
      return gatewaySenders;
    }

    /**
     * @return the offHeapEnabled
     */
    public String getOffHeapEnabled() {
      return this.offHeapEnabled;
    }

    /**
     * @return the rowLevelSecurityEnabled
     */
    public String getRowLevelSecurityEnabled() {
      return this.rowLevelSecurityEnabled;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(TableMetadata.class.getSimpleName()).append(" [tableId=");
      builder.append(tableId);
      builder.append(", tableName=").append(tableName);
      builder.append(", tableType=").append(tableType);
      builder.append(", schemaId=").append(schemaId);
      builder.append(", tableSchemaname=").append(tableSchemaname);
      builder.append(", lockGranularity=").append(lockGranularity);
      builder.append(", serverGroups=").append(serverGroups);
      builder.append(", dataPolicy=").append(dataPolicy);
      builder.append(", partitionAttrs=").append(partitionAttrs);
      builder.append(", resolver=").append(resolver);
      builder.append(", expirationAttrs=").append(expirationAttrs);
      builder.append(", evictionAttrs=").append(evictionAttrs);
      builder.append(", diskAttrs=").append(diskAttrs);
      builder.append(", loader=").append(loader);
      builder.append(", writer=").append(writer);
      builder.append(", listeners=").append(listeners);
      builder.append(", asyncListeners=").append(asyncListeners);
      builder.append(", gatewayEnabled=").append(gatewayEnabled);
      builder.append(", gatewaySenders=").append(gatewaySenders);
      builder.append(", offHeapEnabled=").append(this.offHeapEnabled);
      builder.append(", rowLevelSecurityEnabled=").append(this.rowLevelSecurityEnabled);
      builder.append("]");
      return builder.toString();
    }
  }
}
