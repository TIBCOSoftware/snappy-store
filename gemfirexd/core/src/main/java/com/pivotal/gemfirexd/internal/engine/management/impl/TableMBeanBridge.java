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
package com.pivotal.gemfirexd.internal.engine.management.impl;

import java.beans.ConstructorProperties;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

import javax.management.openmbean.TabularData;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InternalPartitionResolver;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.diag.MemoryAnalyticsVTI;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.TableMXBean.TableMetadata;
import com.pivotal.gemfirexd.internal.engine.management.impl.InternalManagementService.ConnectionWrapperHolder;
import com.pivotal.gemfirexd.internal.engine.management.impl.TableMBeanDataUpdater.MemoryAnalyticsHolder;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSTABLESRowFactory;

/**
*
* @author Abhishek Chaudhari
* @since gfxd 1.0
*/
public class TableMBeanBridge implements Cleanable, Updatable<TableMBeanBridge> {

  private String                  tableName;
  private String                  parentSchema;
  private List<String>       definition;
  private String                  partitioningScheme;

  private GemFireContainer        container;
  private ConnectionWrapperHolder connectionWrapperHolder;
  private MemoryAnalyticsData     memoryAnalytics;
  private TableStatsData          tableStatsData;
  private TableMetadata           tableMetadata;

  private LogWriter logWriter;
  private int       updateToken;

  // cached statements
  private PreparedStatement metadataStatement;  
  private PreparedStatement indexInfoStatement;
  
  private MemoryAnalyticsVTI indexMemAnalVTI;
  
  private final static int INDEX_NAME_INDEX=3;
  private final static int INDEX_TYPE_INDEX=7;
  private final static int INDEX_COLUMNS_INDEX=4;
  private final static int INDEX_UNIQUE_INDEX=5;

  public TableMBeanBridge(GemFireContainer container, ConnectionWrapperHolder connectionHolder) {
    this(container, connectionHolder, new ArrayList<String>());
  }

  public TableMBeanBridge(GemFireContainer container, ConnectionWrapperHolder connectionHolder, List<String> definition) {
    this.container               = container;
    this.tableName               = this.container.getTableName();
    this.parentSchema            = this.container.getSchemaName();
    this.definition              = definition;
    this.connectionWrapperHolder = connectionHolder;
    this.tableMetadata           = ManagementUtils.TABLE_METADATA_NA;
    this.logWriter               = Misc.getCacheLogWriter();

    this.partitioningScheme      = ManagementUtils.NA;

    InternalPartitionResolver<?, ?> resolver = GemFireXDUtils
        .getInternalResolver(this.container.getRegion());
    if (resolver != null) {
      this.partitioningScheme = resolver.getDDLString();
    }

    // Init update tasks
    this.memoryAnalytics = new MemoryAnalyticsData(0.0, 0.0, 0);
    this.tableStatsData  = new TableStatsData(this.container.getRegion().getName(), resolver != null);
    
    //Init Index memAnalyticVTI
    this.indexMemAnalVTI = new MemoryAnalyticsVTI(true);
  }

  public String getName() {
    return tableName;
  }

  public String getParentSchema() {
    return parentSchema;
  }

  String getFullName() {
    return getParentSchema() + "." + getName();
  }

  public String[] getServerGroups() {
    SortedSet<String> serverGroups = ServerGroupUtils.getServerGroupsFromContainer(this.container);
    return serverGroups.toArray(ManagementUtils.EMPTY_STRING_ARRAY);
  }

  public List<String> getDefinition() {
    return this.definition ;    
  }

//  public int getScans() {
//    return this.tableStatsData.getScans();
//  }

  public int getInserts() {
    return this.tableStatsData.getInserts();
  }

  public int getUpdates() {
    return this.tableStatsData.getUpdates();
  }

  public int getDeletes() {
    return this.tableStatsData.getDeletes();
  }

  public double getEntrySize() {
    return this.memoryAnalytics.getEntrySize();
  }

  public double getKeySize() {
    return this.memoryAnalytics.getKeySize();
  }

//  public int getNumberOfRows() {
//    return this.memoryAnalytics.getRowCount();
//  }

  public String getPolicy() {
    return this.container.getRegionAttributes().getDataPolicy().toString();
  }

  public String getPartitioningScheme() {
    return this.partitioningScheme;
  }

  public String getColocationScheme() {
    return String.valueOf(this.container.getRegionAttributes().getPartitionAttributes());
  }

  public String getPersistenceScheme() {
    return null;
  }

  public TableMetadata fetchMetadata() {
    try {
      return retrieveMetadata();
    } catch (SQLException e) {
      this.logWriter.info("Error occurred while fetching MetaData for " + getFullName() +". Reason: " + e.getMessage());
      if (this.logWriter.fineEnabled()) {
        this.logWriter.fine(e);
      }
      return ManagementUtils.TABLE_METADATA_NA;
    }
  }

  private TableMetadata retrieveMetadata() throws SQLException {
    if (this.connectionWrapperHolder.hasConnection()) {
      if (this.metadataStatement == null) {
        this.metadataStatement = connectionWrapperHolder.getConnection().prepareStatement("<local>SELECT * FROM SYS."+GfxdConstants.SYS_TABLENAME_STRING+" WHERE TABLENAME=? and TABLESCHEMANAME=?");
        this.metadataStatement.setString(1, getName());
        this.metadataStatement.setString(2, getParentSchema());
      }
      ResultSet resultSet = this.metadataStatement.executeQuery();

      try {
        if (resultSet.next()) {
  //        ResultSetMetaData metaData = resultSet.getMetaData();
  //        int columnCount = metaData.getColumnCount();
  //        for (int i = 1; i <= columnCount; i++) {
  //          System.out.println(metaData.getColumnName(i) +"-"+metaData.getColumnLabel(i)+"-"+metaData.getColumnTypeName(i));
  //        }
          if (this.tableMetadata == null || this.tableMetadata == ManagementUtils.TABLE_METADATA_NA) {
            this.tableMetadata = new TableMetadata(
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLEID),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLENAME),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLETYPE),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SCHEMAID),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SCHEMANAME),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LOCKGRANULARITY),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SERVERGROUPS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_DATAPOLICY),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_PARTITIONATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_RESOLVER),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_EXPIRATIONATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_EVICTIONATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_DISKATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LOADER),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_WRITER),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LISTENERS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_ASYNCLISTENERS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_GATEWAYENABLED),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SENDERIDS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_OFFHEAPENABLED),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_ROW_LEVEL_SECURITY_ENABLED)
                );
          } else {
            this.tableMetadata.update(
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLEID),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLENAME),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLETYPE),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SCHEMAID),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SCHEMANAME),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LOCKGRANULARITY),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SERVERGROUPS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_DATAPOLICY),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_PARTITIONATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_RESOLVER),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_EXPIRATIONATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_EVICTIONATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_DISKATTRS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LOADER),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_WRITER),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LISTENERS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_ASYNCLISTENERS),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_GATEWAYENABLED),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SENDERIDS) ,
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_OFFHEAPENABLED),
                resultSet.getString(SYSTABLESRowFactory.SYSTABLES_ROW_LEVEL_SECURITY_ENABLED)
                );
          }
        }
      } finally {
        resultSet.close();
      }
    } else {
      this.tableMetadata = ManagementUtils.TABLE_METADATA_NA;
    }
    return this.tableMetadata;
  }

//
//  public TabularData showPartitionAttributes() {
//    return null;
//  }

  public TabularData showDiskAttributes() {
    return null;
  }

//  public TabularData showEvictionAttributes() {
//    return null;
//  }

  /**
   * @return the memoryAnalytics
   */
  public MemoryAnalyticsData getMemoryAnalytics() {
    return this.memoryAnalytics;
  }

  public IndexStatsData[] listIndexStats() {
    List<IndexStatsData> indexStatsList = buildIndexStats();
    if (indexStatsList != null && !indexStatsList.isEmpty()) {
      return indexStatsList.toArray(new IndexStatsData[0]);
    }
    return new IndexStatsData[0];
  }  

  private List<IndexStatsData> buildIndexStats() {
    List<IndexStatsData> indexInfoList = new ArrayList<IndexStatsData>();
    try {            
      MemoryAnalyticsHolder memHolder = new MemoryAnalyticsHolder(indexMemAnalVTI);
      indexMemAnalVTI.setContainer(container);
      memHolder.init();           
      
      while (indexMemAnalVTI.next()) {        
        String indexName = indexMemAnalVTI.getString(memHolder.indexNameIndex);
        String indexType = indexMemAnalVTI.getString(memHolder.indexTypeIndex);        
        if (indexName != null) {
          IndexStatsData indexInfoData = new TableMBeanBridge.IndexStatsData(indexName, indexType,
              Double.parseDouble(indexMemAnalVTI.getString(memHolder.entrySizeIndex)),
              Double.parseDouble(indexMemAnalVTI.getString(memHolder.keySizeIndex)),
              indexMemAnalVTI.getLong(memHolder.numRowsIndex));
          indexInfoList.add(indexInfoData);
        }
      }
      return indexInfoList;
    } catch (NumberFormatException e) {
      logWriter.warning(e);
    } catch (SQLException e) {
      Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);
      logWriter.warning(e);
    } catch (Exception e) {
      logWriter.warning(e);
    } finally {
      // clean-up vti if required
    }
    return indexInfoList;
  }
  
  public IndexInfoData[] listIndexInfo() {
    try {
      return retrieveIndexInfo();
    } catch (SQLException e) {
      this.logWriter.info("Error occurred while fetching index for " + getFullName() +". Reason: " + e.getMessage());
      if (this.logWriter.fineEnabled()) {
        this.logWriter.fine(e);
      }
      return new IndexInfoData[0];
    }    
  }

  private synchronized IndexInfoData[] retrieveIndexInfo() throws SQLException {
    if (this.connectionWrapperHolder.hasConnection()) {
      if (this.indexInfoStatement == null) {
        this.indexInfoStatement = connectionWrapperHolder.getConnection().prepareStatement(
            "<local>SELECT * FROM SYS.INDEXES WHERE TABLENAME=? and SCHEMANAME=?");
        this.indexInfoStatement.setString(1, getName());
        this.indexInfoStatement.setString(2, getParentSchema());
      }
      ResultSet resultSet = this.indexInfoStatement.executeQuery();
      List<IndexInfoData> list = new ArrayList<IndexInfoData>();
      try {
        while (resultSet.next()) {
          list.add(new IndexInfoData(resultSet.getString(INDEX_NAME_INDEX), resultSet.getString(INDEX_TYPE_INDEX),
              resultSet.getString(INDEX_COLUMNS_INDEX), resultSet.getString(INDEX_UNIQUE_INDEX)));
        }
      } finally {
        resultSet.close();
      }
      return list.toArray(new IndexInfoData[0]);
    } else
      return null;
  }

  /**
   * @return the updateToken
   */
  public int getUpdateToken() {
    return this.updateToken;
  }

  /**
   * @param updateToken the updateToken to set
   */
  public void setUpdateToken(int updateToken) {
    this.updateToken = updateToken;
  }

  /* (non-Javadoc)
   * @see java.lang.Object#hashCode()
   */
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((parentSchema == null) ? 0 : parentSchema.hashCode());
    result = prime * result + ((tableName == null) ? 0 : tableName.hashCode());
    return result;
  }

  /* (non-Javadoc)
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    TableMBeanBridge other = (TableMBeanBridge) obj;
    if (parentSchema == null) {
      if (other.parentSchema != null) {
        return false;
      }
    } else if (!parentSchema.equals(other.parentSchema)) {
      return false;
    }
    if (tableName == null) {
      if (other.tableName != null) {
        return false;
      }
    } else if (!tableName.equals(other.tableName)) {
      return false;
    }
    return true;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(getClass().getSimpleName()).append(" [");
    builder.append(parentSchema).append(".");
    builder.append(tableName);
    builder.append(", policy=").append(tableName).append("]");
    return builder.toString();
  }


  static class MemoryAnalyticsData implements Serializable {
    private static final long serialVersionUID = -5803260729108394342L;

    volatile double entrySize;
    volatile double keySize;
    volatile long  rowCount;

    public MemoryAnalyticsData(double entrySize, double keySize, long rowCount) {
      this.entrySize = entrySize;
      this.keySize = keySize;
      this.rowCount = rowCount;
    }

    public void updateMemoryAnalytics(String entrySize, String keySize, String rowCount) {
//      System.out.println("_"+entrySize+"_"+keySize +"_"+entryCount+"_");
      this.entrySize = Double.valueOf(entrySize).doubleValue();
      this.keySize = Double.valueOf(keySize).doubleValue();
      this.rowCount = Float.valueOf(rowCount).intValue();
    }

    public void updateMemoryAnalytics(double entrySize, double keySize, long rowCount) {
      this.entrySize = entrySize;
      this.keySize = keySize;
      this.rowCount = rowCount;
    }

    /**
     * @return the entrySize
     */
    public double getEntrySize() {
      return entrySize;
    }

    /**
     * @return the keySize
     */
    public double getKeySize() {
      return keySize;
    }

    /**
     * @return the entryCount
     */
    public long getRowCount() {
      return rowCount;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(MemoryAnalyticsData.class.getSimpleName());
      builder.append(" [entrySize=").append(entrySize);
      builder.append(", keySize=").append(keySize);
      builder.append(", rowCount=").append(rowCount);
      builder.append("]");
      return builder.toString();
    }
  }
  
  public static class IndexStatsData extends MemoryAnalyticsData implements Serializable  {
    private static final long serialVersionUID = 7993411515378953750L;

    volatile String indexName;
    volatile String indexType;
    volatile int updateToken;

    @ConstructorProperties(value = { "indexName", "indexType", "entrySize", "keySize", "rowCount" })
    public IndexStatsData(String indexName, String indexType, double entrySize, double keySize, long rowCount) {
      super(entrySize, keySize, rowCount);
      this.indexName = indexName;
      this.indexType = indexType;
    }

    public void updateIndexStatsData(String entrySize, String keySize, String entryCount) {
      super.updateMemoryAnalytics(entrySize, keySize, entryCount);
    }

    public void updateIndexStatsData(double entrySize, double keySize, long entryCount) {
      LogWriter logWriter = Misc.getCacheLogWriterNoThrow();
      logWriter.info("Updating new indexInfoData for " + indexName + " entrySize=" + entrySize 
          + " keySize=" + keySize + " entryCount=" + entryCount);
      super.updateMemoryAnalytics(entrySize, keySize, entryCount);
    }

    /**
     * @return the indexName
     */
    public String getIndexName() {
      return indexName;
    }

    /**
     * @return the indexType
     */
    public String getIndexType() {
      return indexType;
    }

    /**
     * @return the updateToken
     */
    /*package*/int getUpdateToken() {
      return this.updateToken;
    }

    /**
     * @param updateToken the updateToken to set
     */
    /*package*/void setUpdateToken(int updateToken) {
      this.updateToken = updateToken;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(IndexInfoData.class.getSimpleName());
      builder.append("IndexInfoData [indexName=").append(indexName);
      builder.append(", indexType=").append(indexType);
      builder.append(", entrySize=").append(entrySize);
      builder.append(", keySize=").append(keySize);
      builder.append(", rowCount=").append(rowCount);
      builder.append("]");
      return builder.toString();
    }
  }  

  public static class IndexInfoData implements Serializable  {    

    private static final long serialVersionUID = 1L;
    volatile String indexName;
    volatile String indexType;
    volatile String columnsAndOrder;
    volatile String unique;
    

    @ConstructorProperties(value = { "indexName", "indexType", "columnsAndOrder", "unique"})
    public IndexInfoData(String indexName, String indexType, String columnsAndOrder, String unique) {     
      this.indexName = indexName;
      this.indexType = indexType;
      this.columnsAndOrder = columnsAndOrder;
      this.unique = unique;
    }

    /**
     * @return the indexName
     */
    public String getIndexName() {
      return indexName;
    }

    /**
     * @return the indexType
     */
    public String getIndexType() {
      return indexType;
    }
    

    public String getColumnsAndOrder() {
      return columnsAndOrder;
    }

    public void setColumnsAndOrder(String columnsAndOrder) {
      this.columnsAndOrder = columnsAndOrder;
    }

    public String getUnique() {
      return unique;
    }

    public void setUnique(String unique) {
      this.unique = unique;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(IndexInfoData.class.getSimpleName());
      builder.append("IndexInfoData [indexName=").append(indexName);
      builder.append(", indexType=").append(indexType);
      builder.append(", columnsAndOrder=").append(columnsAndOrder);
      builder.append(", unique=").append(unique);
      builder.append("]");
      return builder.toString();
    }
  }

  class TableStatsData {
    private          String  regionName;
    private          boolean isPR;
    private          String  statsTxtId;

//    private volatile int     scans;
    private volatile int     inserts;
    private volatile int     updates;
    private volatile int     deletes;

    TableStatsData(String regionName, boolean isPR) {
      this.regionName = regionName;
      this.isPR       = isPR;
      this.statsTxtId = CachePerfStats.REGIONPERFSTATS_TEXTID_PREFIX + (this.isPR ? CachePerfStats.REGIONPERFSTATS_TEXTID_PR_SPECIFIER : "") + this.regionName;

//      this.scans   = ManagementConstants.NOT_AVAILABLE_INT;
      this.inserts = ManagementConstants.ZERO;
      this.updates = ManagementConstants.ZERO;
      this.deletes = ManagementConstants.ZERO;
    }

    public void update() {
//      logWriter.info("ABHISHEK: TableMBeanBridge.TableStatsData.update() : entering");
      GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
      if (cache != null) {
        InternalDistributedSystem system = cache.getDistributedSystem();
        Statistics[] regionPerfStats = system.findStatisticsByTextId(statsTxtId);
        for (int i = 0; i < regionPerfStats.length; ) {
//          this.scans   = ManagementConstants.NOT_AVAILABLE_INT;
          this.inserts = regionPerfStats[i].get("creates").intValue();
          this.updates = regionPerfStats[i].get("puts").intValue();
          this.deletes = regionPerfStats[i].get("destroys").intValue();
          break; // break after finding first instance
          //TODO Abhishek what if there are two different sub-regions with same name? This actually needs to be addressed in RegionPerfStats.
        }
      }
//      logWriter.info("ABHISHEK: TableMBeanBridge.TableStatsData.update() : exiting");
    }

//    /**
//     * @return the scans
//     */
//    public int getScans() {
//      return scans;
//    }

    /**
     * @return the inserts
     */
    public int getInserts() {
      return inserts;
    }

    /**
     * @return the updates
     */
    public int getUpdates() {
      if (!isPR) {
        // For replicated region updates
        return Math.abs(updates - inserts);
      }
      return updates;
    }

    /**
     * @return the deletes
     */
    public int getDeletes() {
      return deletes;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(TableStatsData.class.getSimpleName());
      builder.append(" [regionName=").append(regionName);
//      builder.append(", scans=").append(scans);
      builder.append(", inserts=").append(inserts);
      builder.append(", updates=").append(updates);
      builder.append(", deletes=").append(deletes);
      builder.append("]");
      return builder.toString();
    }
  }

  @Override
  public void cleanUp() {
//    System.out.println("TableMBeanBridge.cleanUp()");
    try {
      if (this.metadataStatement != null && !this.metadataStatement.isClosed()) {
//        logWriter.info("ABHISHEK: TableMBeanBridge.cleanUp() : closing metadataStatement statement");
        try {
          this.metadataStatement.cancel();
        } finally {
          this.metadataStatement.close();
        }
//        logWriter.info("ABHISHEK: TableMBeanBridge.cleanUp() : closed metadataStatement statement");
      }
    } catch (SQLException e) {
      if (this.logWriter.fineEnabled()) {
        this.logWriter.fine(e);
      }
    }

    this.container = null;
    this.connectionWrapperHolder = null;
  }

  @Override
  public void update() {
    //logWriter.info("ABHISHEK: TableMBeanBridge.update() : entering : ");
    this.tableStatsData.update();
    //logWriter.info("ABHISHEK: TableMBeanBridge.update() : exiting : ");
  }  

  @Override
  public TableMBeanBridge getSelf() {
    return this;
  }
  
  public void setDefinition(List<String> newDefinition) {
    this.definition = newDefinition;     
  }
  
  public LocalRegion getRegion(){
    return this.container.getRegion();
  }
 
}
