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
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.store;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.query.IndexMaintenanceException;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ClassPathLoader;
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RecoveryLock;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.SizeEntry;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException.PutAllPartialResult;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.partitioned.PREntriesIterator;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgument;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgumentImpl;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.DataAsAddress;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.catalog.ExternalCatalog;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager.ForeignKeyInformation;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalRowLocation;
import com.pivotal.gemfirexd.internal.engine.access.index.MemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.key.IndexKeyComparator;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemDeleteOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemInsertOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.MemUpdateOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexInsertOperation;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexInsertOperation.InsertOrUpdateValue;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheListener;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdEvictionCriteria;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector.ListResultCollectorValue;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdSingleResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.ContainsKeyBulkExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.ContainsUniqueKeyBulkExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ColumnQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.AbstractGfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.DefaultGfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockable;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraIndexInfo;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireRegionSizeResultSet.RegionSizeMessage;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireUpdateActivation.MultipleKeyValueHolder;
import com.pivotal.gemfirexd.internal.engine.sql.execute.IdentityValueManager;
import com.pivotal.gemfirexd.internal.engine.store.entry.GfxdTXEntryState;
import com.pivotal.gemfirexd.internal.engine.store.entry.HDFSSplitIteratorWrapper;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.io.LimitObjectInput;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ForeignKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ReferencedKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.access.SpaceInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Compensation;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Page;
import com.pivotal.gemfirexd.internal.iapi.store.raw.RecordHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogInstant;
import com.pivotal.gemfirexd.internal.iapi.types.*;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSTABLESRowFactory;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.sizer.ObjectSizer;

/**
 * Masquerades as a ContainerHandle, but has almost none of the behavior of one.
 * 
 * @author Eric Zoerner
 * @author Rahul Dubey
 * @author swale
 * @author asif
 * TODO determine if ContainerHandle interface can be removed somehow
 */
public final class GemFireContainer extends AbstractGfxdLockable implements
    ContainerHandle, SortedIndexContainer {

  /* ------ Flags for the container --------- */

  /** set if byte array store else DataValueDescriptor array */
  private static final int BYTEARRAY_STORE = 0x01;

  /** set if container is for a DataDictionary index */
  private static final int DDINDEX = 0x02;

  /**
   * set if a BucketRowLocation is required for this container as the
   * RowLocation in indexes else LocalRowLocation is required
   */
  private static final int HAS_BUCKET_ROWLOC = 0x04;

  /** set if container is a global index for a primary key of a table */
  private static final int FOR_PRIMARY_KEY = 0x08;

  /** set if this container is for SYS.SYSTABLES system table */
  private static final int FOR_SYSTABLES = 0x20;

  /** set if this container is for an application table */
  private static final int FOR_APPTABLE = 0x40;

  /** set if this container is for an application table */
  private static final int FOR_APPTABLE_OR_GLOBAL_INDEX = 0x80;

  /** set if this container has overflow configured as eviction */
  private static final int IS_OVERFLOW = 0x100;

  /** set if generate always as identity columns are defined in this table or 
   * table have no primary keys defined.*/
  private static final int HAS_AUTO_GENERATED_COLUMNS = 0x200;
  
  /* ------ End Flags for the container --------- */

  public static final String COLON_SEPERATOR = ":";

  /** The GemFire Region where data for this container is stored. */
  private LocalRegion region;

  /** The attributes for the region. */
  private RegionAttributes<?, ?> regionAttributes;

  /**
   * The internal region arguments used to create the region. Set only if
   * initialization has been delayed, else is null if region creation is done.
   */
  private InternalRegionArguments iargs;

  /** for local index */
  private ConcurrentSkipListMap<Object, Object> skipListMap;

  /** flags as bitmasks for this container */
  private int containerFlags;

  /** schema name of the table */
  private final String schemaName;

  /** name of the table */
  private final String tableName;

  /** qualified name of the table */
  private final String qualifiedName;

  /** a unique ID for this container */
  private String uuid;

  /** The ContainerKey for this container. */
  private final ContainerKey id;

  /** The ContainerKey for the base container/table. Used in case of Index. */
  private final ContainerKey baseId;

  /** The cluster-wide unique ID of the DDL that created this container. */
  private long ddlId;

  /** The column positions for the base container/table. Used in case of Index. */
  private final int[] baseColumnPositions;

  /** The base {@link GemFireContainer} for the case of an index. */
  private final GemFireContainer baseContainer;

  /** Memory estimate per row, used for cost calculations */
  private volatile long rowSize;

  /** Stores the conglomerate for this container. */
  private MemConglomerate conglom;

  /** Whether the index is a unique one or not. */
  private boolean isUniqueIndex;

  /**
   * Template row for the table. Will be either a {@link AbstractCompactExecRow}
   * or a {@link ValueRow} depending on whether raw byte storage is true or not.
   */
  private ExecRow templateRow;

  private ExtraTableInfo tableInfo;

  private DistributionDescriptor distributionDesc;

  /**
   * RowEncoder for object tables.
   */
  private final RowEncoder encoder;

  boolean hasLobs;

  /** the current version of the schema of this table */
  private int schemaVersion;

  /**
   * if set then this table has only one schema (e.g. no multiple schemas due
   * to ALTER TABLE or recovery from old format data which had no schema
   * versions so would use {@link RowFormatter#TOKEN_RECOVERY_VERSION}
   */
  public RowFormatter singleSchema;

  /**
   * the table info adjusted after recovery from disk at the end of DDL replay
   * to be used for old product data recovered from disk not having a version
   */
  private ExtraTableInfo tableInfoOnRecovery;

  /**
   * the schema version corresponding to old pre 1.1 product's
   * {@link #tableInfoOnRecovery}
   */
  private int schemaVersionOnRecovery;

  /**
   * The table infos for previous schema versions.
   */
  private final ArrayList<ExtraTableInfo> oldTableInfos;

  private final ExtraIndexInfo indexInfo;

  private final IndexKeyComparator comparator;

  private final GFContainerLocking locking;

  private Properties properties;

  private volatile boolean isIndexInitialized;

  private volatile boolean hasLoaderAnywhere;

  private final boolean CACHE_GLOBAL_INDEX = SystemProperties
      .getServerInstance().getBoolean("cacheGlobalIndex", false);

  private final boolean CACHE_GLOBAL_INDEX_IN_MAP = SystemProperties
      .getServerInstance().getBoolean("cacheGlobalIndexINMap", false);

  private CacheMap globalIndexMap;

  private AtomicReference<ExternalTableMetaData> externalTableMetaData =
      new AtomicReference<ExternalTableMetaData>(null);

  private final IndexStats stats;
  /**
   * !!!:ezoerner:20080320 need to determine what exceptions this should throw
   * 
   * @param id
   *          DOCUMENT ME!
   * @param properties
   *          DOCUMENT ME!
   * @throws StandardException
   */
  public GemFireContainer(ContainerKey id, Properties properties)
      throws StandardException {

    this.id = id;
    this.baseId = (ContainerKey)properties.get(MemIndex.PROPERTY_BASECONGLOMID);
    final Object baseCols = properties
        .get(GfxdConstants.PROPERTY_INDEX_BASE_COL_POS);
    this.baseColumnPositions = baseCols != null ? ((int[])baseCols).clone()
        : null;
    this.containerFlags = 0x0;

    final LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    String[] outName = new String[2];
    prepareContainerName(id.getContainerId(), properties, lcc, outName);
    this.schemaName = outName[0];
    this.tableName = outName[1];

    this.tableInfo = getExtraTableInfo(properties, this.schemaName);

    final GemFireStore memStore = Misc.getMemStore();
    final Cache gfCache = memStore.getGemFireCache();
    final GemFireTransaction tran = lcc != null ? (GemFireTransaction)lcc
        .getTransactionExecute() : null;

    if (this.baseId != null) {
      this.baseContainer = memStore.getContainer(this.baseId);
      String baseContainerName = (baseContainer != null ? baseContainer
          .getQualifiedTableName() : null);
      this.qualifiedName = Misc.getFullTableName(this.schemaName,
          this.tableName, lcc) + COLON_SEPERATOR + "base-table"
          + COLON_SEPERATOR + baseContainerName;
      final String value = properties.getProperty(
          GfxdConstants.PROPERTY_CONSTRAINT_TYPE, "-1");
      setFlag(FOR_PRIMARY_KEY,
          Integer.parseInt(value) == DataDictionary.PRIMARYKEY_CONSTRAINT);
    }
    else {
      this.baseContainer = null;
      this.qualifiedName = Misc.getFullTableName(this.schemaName,
          this.tableName, lcc);
    }
    initUUID(lcc, tran);

    setTraceLock();
    this.locking = getContainerLockingPolicy(properties, this.schemaName,
        this.tableName, this.qualifiedName, this.baseId, this);

    String propVal = properties.getProperty(MemIndex.PROPERTY_DDINDEX);
    if (propVal != null) {
      setFlag(DDINDEX, Boolean.parseBoolean(propVal));
    }
    this.comparator = (IndexKeyComparator)properties
        .get(MemIndex.PROPERTY_INDEX_COMPARATOR);
    if (this.comparator != null) {
      // this is the case of local index
      assert this.baseId != null: "expected non-null baseId for local index"
          + this.qualifiedName;
      this.indexInfo = (ExtraIndexInfo)properties
          .get(MemIndex.PROPERTY_EXTRA_INDEX_INFO);
      // initialize the indexInfo
      if (this.baseContainer.isCandidateForByteArrayStore()) {
        this.indexInfo.initRowFormatter(this.baseColumnPositions,
            this.baseContainer, (TableDescriptor)properties
                .get(GfxdConstants.PROPERTY_INDEX_BASE_TABLE_DESC));
        this.skipListMap = new ConcurrentSkipListMap<Object, Object>(
            this.comparator, true, true,
            CompactCompositeIndexKey.getNodeFactory());
      }
      else {
        this.skipListMap = new ConcurrentSkipListMap<Object, Object>(
            this.comparator, true, true);
      }
      this.region = null;
      this.singleSchema = this.baseContainer.getCurrentRowFormatter();
      this.oldTableInfos = null;

      if (this.baseContainer.isApplicationTable() &&
          !Misc.isSnappyHiveMetaTable(getSchemaName())) {
        this.stats = new IndexStats(gfCache.getDistributedSystem(), qualifiedName);
      }
      else {
        this.stats = null;
      }
      // Write the index creation records to disk store if table is persistent.
      // It doesn't really matter if the index creation ultimately fails due
      // to some reason (e.g. uniqueness violation on some other node) and this
      // node fails before receiving index destroy since this will just be a
      // dangling write in IF file which would otherwise not be a problem since
      // DataDictionary will not have the entry.
      if (this.baseContainer.isApplicationTable()
          && this.baseContainer.regionAttributes.getDataPolicy()
              .withPersistence()) {
        String diskStoreName = this.baseContainer.regionAttributes
            .getDiskStoreName();
        DiskStoreImpl store;
        if (diskStoreName != null && (store = Misc.getGemFireCache()
            .findDiskStore(diskStoreName)) != null) {
          store.writeIndexCreate(getUUID());
        }
      }
      this.globalIndexMap = null;
      this.encoder = null;
      return;
    }

    this.stats = null;

    Region<?, ?> rootRegion = gfCache.getRegion(schemaName);
    // if schema not found, create it now
    if (rootRegion == null) {
      rootRegion = memStore.createSchemaRegion(this.schemaName, tran);
    }
    assert rootRegion != null: "Schema '" + schemaName + "' not found";
    this.properties = properties;
    String rowEncoderClass = properties.getProperty(
        GfxdConstants.TABLE_ROW_ENCODER_CLASS_KEY);
    if (rowEncoderClass != null) {
      try {
        this.encoder = (RowEncoder)ClassPathLoader.getLatest()
            .forName(rowEncoderClass).newInstance();
      } catch (Exception e) {
        throw StandardException.newException(
            SQLState.UNEXPECTED_EXCEPTION_FOR_ROW_ENCODER, e, qualifiedName);
      }
    } else {
      this.encoder = null;
    }
    this.regionAttributes = (RegionAttributes<?, ?>)properties
        .get(GfxdConstants.REGION_ATTRIBUTES_KEY);
    this.indexInfo = null;
    this.schemaVersion = 1;
    this.singleSchema = getCurrentRowFormatter();
    this.oldTableInfos = new ArrayList<ExtraTableInfo>();
    initTableFlags();
  }

  public void incPointStats() {
    if (this.stats != null) {
      this.stats.incPointLookupStats();
    }
  }

  public void incRangeScanStats() {
    if (this.stats != null) {
      this.stats.incScanStats();
    }
  }

  public void invalidateHiveMetaData() {
    externalTableMetaData.set(null);
  }

  public ExternalTableMetaData fetchHiveMetaData(boolean refresh) {
    ExternalTableMetaData metaData;
    if (refresh || (metaData = externalTableMetaData.get()) == null) {
      // for column store, get the row buffer table name
      String schemaName = this.schemaName;
      String tableName = this.tableName;
      if (isColumnStore()) {
        String fullName = getRowBufferTableName(this.qualifiedName);
        int schemaIndex = fullName.indexOf('.');
        schemaName = fullName.substring(0, schemaIndex);
        tableName = fullName.substring(schemaIndex + 1);
      }
      ExternalCatalog extcat = Misc.getMemStore().getExistingExternalCatalog();
      // containers are created during initialization, ignore them
      externalTableMetaData.compareAndSet(null, extcat.getHiveTableMetaData(
              schemaName, tableName, true));
      if (isPartitioned()) {
        metaData = externalTableMetaData.get();
        if (metaData == null) return null;
        ((PartitionedRegion)this.region).setColumnBatchSizes(
            metaData.columnBatchSize, metaData.columnMaxDeltaRows,
            SystemProperties.SNAPPY_MIN_COLUMN_DELTA_ROWS);
        return metaData;
      }
      return externalTableMetaData.get();
    } else {
      return metaData;
    }
  }

  public static String getRowBufferTableName(String columnBatchTableName) {
    String tableName = columnBatchTableName.replace(
        SystemProperties.SHADOW_SCHEMA_NAME_WITH_SEPARATOR, "");
    return tableName.substring(0, tableName.length() -
        SystemProperties.SHADOW_TABLE_SUFFIX.length());
  }

  public boolean cachesGlobalIndex() {
    return this.globalIndexMap != null;
  }

  public CacheMap getGlobalIndexCache() {
    return this.globalIndexMap;
  }
  
  public void setLoaderInstalled(boolean flag) {
    this.hasLoaderAnywhere = flag;
  }

  public final boolean getHasLoaderAnywhere() {
    return this.hasLoaderAnywhere;
  }

  private ExtraTableInfo getExtraTableInfo(Properties properties,
      String schemaName) {
    // for temporary tables use null ExtraTableInfo and not base table's
    if (this.id.getSegmentId() == ContainerHandle.TEMPORARY_SEGMENT) {
      if (!GfxdConstants.SESSION_SCHEMA_NAME.equals(schemaName)) {
        Assert.fail("Expected schemaName for temporary table to be "
            + GfxdConstants.SESSION_SCHEMA_NAME + " but got " + schemaName);
      }
      return null;
    }
    return (ExtraTableInfo)properties.get(GfxdConstants.GFXD_DTDS);
  }

  private void setFlag(int mask) {
    this.containerFlags = GemFireXDUtils.set(this.containerFlags, mask);
  }

  private void setFlag(int mask, boolean on) {
    this.containerFlags = GemFireXDUtils.set(this.containerFlags, mask, on);
  }

  public void initialize(Properties properties, GfxdIndexManager indexManager,
      GemFireTransaction tran, ColumnDescriptorList cdl) throws StandardException {

    Cache gfCache = Misc.getGemFireCache();
    LogWriterI18n logger = gfCache.getLogger().convertToLogWriterI18n();
    final boolean byteArrayStore;

    if (this.baseId == null && isCandidateForByteArrayStore()) {
      byteArrayStore = true;
    }
    else {
      byteArrayStore = false;
      if (GfxdConstants.SYS_TABLENAME_STRING.equals(this.tableName)) {
        setFlag(FOR_SYSTABLES);
      }
    }
    // set the template row
    if (byteArrayStore) {
      setFlag(BYTEARRAY_STORE);
      this.tableInfo.initRowFormatter(this);
    }
    else {
      DataValueDescriptor[] template = (DataValueDescriptor[])properties
          .get(GfxdConstants.PROP_TEMPLATE_ROW);
      // check for last column as RowLocation
      int nCols = template.length;
      final ValueRow row;
      if (template[nCols - 1] instanceof RowLocation) {
        nCols--;
        row = new ValueRow(nCols);
        for (int i = 0; i < nCols; i++) {
          row.setColumn(i + 1, template[i]);
        }
      }
      else {
        row = new ValueRow(nCols);
        row.setRowArray(template);
      }
      this.templateRow = row.getNewNullRow();
      this.singleSchema = null;
    }

    if (this.comparator != null) {
      return;
    }
    Region<?, ?> rootRegion = gfCache.getRegion(schemaName);
    RegionAttributes<?, ?> rattrs = (RegionAttributes<?, ?>)properties
        .get(GfxdConstants.REGION_ATTRIBUTES_KEY);

    LocalRegion rgn = null;
    final LanguageConnectionContext lcc;
//    String hdfsStoreName = rattrs.getHDFSStoreName();
//    if (hdfsStoreName != null) {
//        AttributesFactory af = new AttributesFactory(rattrs);        
//        //TODO: is there any other data policy that is with HDFS?
//        af.setDataPolicy(DataPolicy.HDFS_PARTITION);
//        rattrs = af.create();
//    }
    try {
      // explicitly validate the region attributes and partition attributes
      PartitionAttributesImpl pattrs = (PartitionAttributesImpl)rattrs
          .getPartitionAttributes();
      if (pattrs != null) {
        pattrs.validateAttributes();
        pattrs.validateWhenAllAttributesAreSet(false);
        if (indexManager != null) {
          if (pattrs.getLocalMaxMemory() != 0) {
            indexManager.initialize(tran, true, false);
          }
          else {
            // no index manager for accessor region
            // indexManager = null;
          }
        }
      }
      else if (indexManager != null) {
        // now index manager is attached to empty regions also for FK checks
        indexManager.initialize(tran, false, !rattrs.getDataPolicy()
            .withStorage());
      }
      if (tran != null) {
        lcc = tran.getLanguageConnectionContext();
      }
      else {
        lcc = Misc.getLanguageConnectionContext();
      }
      initUUID(lcc, tran);
      AttributesFactory.validateAttributes(rattrs);
      this.iargs = new InternalRegionArguments()
          .setDestroyLockFlag(true)
          .setRecreateFlag(false)
          .setIndexUpdater(indexManager)
          .setUserAttribute(this)
          .setKeyRequiresRegionContext(regionKeyRequiresRegionContext())
          .setUsedForIndex(isGlobalIndex())
          .setUUID(this.ddlId);
      // for local regions set the meta-region flag to avoid direct
      // participation in a transaction
      if (rattrs.getScope().isLocal()) {
        this.iargs.setIsUsedForMetaRegion(true);
      }
      if (logger.fineEnabled()) {
        logger.fine("This container's " + this.toString() + " byteArrayStore="
            + byteArrayStore + " id=" + this.ddlId + " attributes=" + rattrs);
      }

      final boolean skipRegionInitialization = lcc != null
          && lcc.skipRegionInitialization();

      try {
        rgn = (LocalRegion)((LocalRegion)rootRegion).createSubregion(
            this.tableName, rattrs, this.iargs, skipRegionInitialization);
        // clear the initializing region (JIRA: GEMXD-1)
        LocalRegion.clearInitializingRegion();
        if (!skipRegionInitialization) {
          initNumRows(rgn);
          this.iargs = null; // free the internal region arguments
        }
      } catch (TimeoutException e) {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
      } catch (IOException e) {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
      } catch (ClassNotFoundException e) {
        throw StandardException.newException(
            SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
      }
    } catch (RegionExistsException ex) {
      // [sumedh] A genuine case should be caught at the derby level while other
      // case where it can arise is receiving a GfxdDDLMessage for a table
      // creation that has already been replayed using the hidden
      // _DDL_STMTS_REGION which is handled appropriately by the replay code.
      throw StandardException.newException(
          SQLState.LANG_OBJECT_ALREADY_EXISTS_IN_OBJECT, "Table",
          this.tableName, "Schema", this.schemaName);
    } catch (IllegalStateException ex) {
      // in case there is a problem with region attributes
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, ex, ex.toString());
    }
    this.region = rgn;
    assert this.region != null;
    this.regionAttributes = this.region.getAttributes();
    initTableFlags();
    // initialize EvictionCriteria if any
    if (rgn.getCustomEvictionAttributes() != null) {
      CustomEvictionAttributes ceAttrs = rgn.getCustomEvictionAttributes();
      if (ceAttrs.getCriteria() instanceof GfxdEvictionCriteria) {
        ((GfxdEvictionCriteria)ceAttrs.getCriteria()).initialize(this, lcc);
      }
    }
    setFlag(
        HAS_AUTO_GENERATED_COLUMNS,
        (isByteArrayStore() && !isPrimaryKeyBased())
            || (cdl != null ? cdl.hasAutoIncrementAlways() : false));
  }

  private long getDDLId(final LanguageConnectionContext lcc,
      final GemFireTransaction tran) {
    if (tran != null) {
      return tran.getDDLId();
    }
    else if (lcc != null) {
      return ((GemFireTransaction)lcc.getTransactionExecute()).getDDLId();
    }
    else {
      return 0;
    }
  }

  private void initUUID(final LanguageConnectionContext lcc,
      final GemFireTransaction tran) {
    this.ddlId = getDDLId(lcc, tran);
    this.uuid = Misc.getFullTableName(this.schemaName, this.tableName, lcc)
        + COLON_SEPERATOR + this.ddlId;
  }

  public final void preInitializeRegion() throws StandardException {
    this.region.preInitialize(this.iargs);
  }

  public final void initializeRegion() throws StandardException {
    try {
      ((LocalRegion)this.region.getParentRegion()).initialize(this.region,
          this.iargs);
      
      //potential issue identified similar to #47662
      if (isPartitioned()) {
        this.region.invokeUUIDPostInitialize();
      }
      //fix for #47662
      if (hasAutoGeneratedCols()) {
        final VMIdAdvisor uuidAdvisor = this.region.getUUIDAdvisor();
        if (uuidAdvisor != null && !Misc.isSnappyHiveMetaTable(getSchemaName())) {
          SanityManager.DEBUG_PRINT("info:", "Initializing UUID advisor "
              + uuidAdvisor);
          uuidAdvisor.handshake();
        }
      }
      this.iargs = null; // free the internal region arguments
    } catch (TimeoutException e) {
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
    } catch (IOException e) {
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
    } catch (ClassNotFoundException e) {
      throw StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, e, e.toString());
    }
  }

  public void initNumRows(LocalRegion r) {
    if (r.getPartitionAttributes() != null) {
      // get the sizes from individual buckets
      final PartitionedRegion pr = (PartitionedRegion)r;
      if (pr.getDataStore() != null) {
        if (pr.isHDFSReadWriteRegion()) {
          this.previousNumRows = -1;
        } else {
          this.previousNumRows = pr.entryCountEstimate(null, pr.getDataStore()
              .getAllLocalBucketIds(), false);
        }
      }
    }
    else {
      this.previousNumRows = r.size();
    }
    if (r.getConcurrencyChecksEnabled()) {
      SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_CONGLOM,
          "Concurrency checks enabled for table " + getQualifiedTableName());
    }
  }

  public static GemFireContainer getGemFireContainer(TableDescriptor td,
      GemFireTransaction tran) throws StandardException {
    assert td != null: "Expected non-null TableDescriptor for "
        + "GemFireContainer#getGemFireContainer";
    final MemConglomerate conglom = tran.findExistingConglomerate(td
        .getHeapConglomerateId());
    final GemFireContainer container = conglom.getGemFireContainer();
    
    assert container != null: "Expected non-null GemFireContainer for "
        + "TableDescriptor: " + td;
    return container;
  }

  public GfxdIndexManager setup(TableDescriptor td, DataDictionary dd,
      LanguageConnectionContext lcc, GemFireTransaction tran, boolean hasFk,
      DistributionDescriptor distributionDesc, Activation activation,
      ColumnDescriptorList cdl) throws StandardException {
    GfxdIndexManager indexManager = null;
    refreshCachedInfo(td, distributionDesc, activation);
    // set the index maintenance listener
    if (!getSchemaName().equalsIgnoreCase(GfxdConstants.SYSTEM_SCHEMA_NAME)) {
      if (!isObjectStore()) {
        indexManager = GfxdIndexManager.newIndexManager(dd, td, this,
            lcc.getDatabase(), hasFk);
      }
      initialize(this.properties, indexManager, tran, cdl);
    }
    this.properties = null;
    return indexManager;
  }

  public void refreshCachedInfo(TableDescriptor td,
      DistributionDescriptor distributionDesc, Activation activation)
      throws StandardException {
    setDistributionDescriptor(distributionDesc);
    if (distributionDesc != null) {
      final RegionAttributes<?, ?> rattr = getRegionAttributes();
      final CacheLoader<?, ?> ldr = rattr.getCacheLoader();
      if (ldr != null) {
        ((GfxdCacheLoader)ldr).setTableDetails(td);
      }
      final PartitionAttributes<?, ?> pattrs = rattr.getPartitionAttributes();
      if (pattrs != null) {
        final PartitionResolver<?, ?> pr = rattr.getPartitionAttributes()
            .getPartitionResolver();
        if (pr instanceof GfxdPartitionResolver) {
          final GfxdPartitionResolver spr = (GfxdPartitionResolver)pr;
          spr.setTableDetails(td, this);
          spr.updateDistributionDescriptor(distributionDesc);
          spr.setColumnInfo(td, activation);
        }
      }
    }
    this.tableInfo.refreshCachedInfo(td, this);
  }

  public void setIndexInitialized() {
    this.isIndexInitialized = true;
  }

  public void setDistributionDescriptor(DistributionDescriptor desc) {
    this.distributionDesc = desc;
  }

  public final DistributionDescriptor getDistributionDescriptor() {
    return this.distributionDesc;
  }

  private static void prepareContainerName(long containerId, Properties props,
      LanguageConnectionContext lcc, String[] outName) {
    assert outName != null && outName.length == 2:
      "expected array of size 2 for output container name";
    String schemaName = props.getProperty(GfxdConstants.PROPERTY_SCHEMA_NAME);
    String tableName = props.getProperty(GfxdConstants.PROPERTY_TABLE_NAME);

    if (schemaName == null) {
      schemaName = Misc.getDefaultSchemaName(lcc);
      // !!!:ezoerner:20080821
      // lcc should not be null here #39484
      // assert lcc != null;
      // assert that we have region for the default schema
      assert Misc.getGemFireCache().getRegion(schemaName) != null:
        "region for default schema should have been found";
    }
    // expand any {id} variable in the table name to the conglom id
    if (tableName == null) {
      tableName = "{id}";
    }
    assert tableName != null;
    tableName = tableName.replaceFirst("\\{id\\}", String.valueOf(containerId));

    outName[0] = schemaName;
    outName[1] = tableName;
  }

  @SuppressWarnings({ "unchecked", "deprecation" })
  private static GFContainerLocking getContainerLockingPolicy(Properties props,
      String schemaName, String tableName, final String qualifiedName,
      ContainerKey baseId, GemFireContainer container) {
    if (baseId != null) {
      boolean ddIndex;
      String propVal = props.getProperty(MemIndex.PROPERTY_DDINDEX);
      if (propVal != null) {
        ddIndex = Boolean.parseBoolean(propVal);
      }
      else {
        ddIndex = false;
      }
      if (!ddIndex && schemaName != null && schemaName.length() > 0) {
        // for indexes the locking policy is copied from base table
        GemFireContainer baseContainer = Misc.getMemStore()
            .getContainer(baseId);
        assert baseContainer != null: "expected to find base container for index "
            + qualifiedName;
        return baseContainer.getLockingPolicy();
      }
      else {
        return null;
      }
    }

    RegionAttributes<Object, Object> rattrs = (RegionAttributes<Object,
        Object>)props.get(GfxdConstants.REGION_ATTRIBUTES_KEY);
    final AttributesFactory<Object, Object> af;

    // ???:ezoerner:20080609 do temp tables get created here
    // if so, then they need to be Scope LOCAL in a session schema
    // TODO: SW: why LOCAL for session schema? should be replicated with empty
    // on accessors as usual; possibly also needs changes to QueryInfo routing
    if (rattrs == null) {
      af = new AttributesFactory<Object, Object>();
      af.setScope(Scope.LOCAL);
      af.setInitialCapacity(GemFireXDUtils.getDefaultInitialCapacity());
      af.setConcurrencyChecksEnabled(false);
      rattrs = af.create();
      props.put(GfxdConstants.REGION_ATTRIBUTES_KEY, rattrs);
    }
    else if (GfxdConstants.SESSION_SCHEMA_NAME.equals(schemaName)
        && !rattrs.getScope().isLocal()) {
      af = new AttributesFactory<Object, Object>(rattrs);
      af.setPartitionAttributes(null);
      af.setScope(Scope.LOCAL);
      af.setDataPolicy(DataPolicy.NORMAL);
      af.setInitialCapacity(GemFireXDUtils.getDefaultInitialCapacity());
      af.setConcurrencyChecksEnabled(false);
      rattrs = af.create();
      props.put(GfxdConstants.REGION_ATTRIBUTES_KEY, rattrs);
    }
    else if (!rattrs.getScope().isLocal()) {
      final GfxdLockable lockable;
      if (container != null) {
        lockable = container;
      }
      else {
        lockable = new DefaultGfxdLockable(qualifiedName, null);
      }
      return new GFContainerLocking(lockable, false, container);
    }
    return null;
  }

  public static GFContainerLocking getContainerLockingPolicy(long containerId,
      Properties props, LanguageConnectionContext lcc) {
    String[] outName = new String[2];
    prepareContainerName(containerId, props, lcc, outName);
    ContainerKey baseId = (ContainerKey)props
        .get(MemIndex.PROPERTY_BASECONGLOMID);
    return getContainerLockingPolicy(props, outName[0], outName[1],
        Misc.getFullTableName(outName[0], outName[1], lcc), baseId, null);
  }

  public void setExtraTableInfo(ExtraTableInfo tableInfo) {
    this.tableInfo = tableInfo;
    schemaUpdated();
  }

  public void schemaUpdated() {
    if (this.oldTableInfos.isEmpty()) {
      this.singleSchema = getCurrentRowFormatter();
    }
  }

  public final boolean hasLobs() {
    return this.hasLobs;
  }

  public final boolean hasLobsInAnySchema() {
    if (this.hasLobs) {
      return true;
    }
    final ArrayList<ExtraTableInfo> oldInfos = this.oldTableInfos;
    if (oldInfos != null) {
      int index = oldInfos.size();
      while (--index >= 0) {
        if (oldInfos.get(index).getRowFormatter().hasLobs()) {
          return true;
        }
      }
    }
    return false;
  }

  public final boolean hasLobsInAllSchema() {
    if (!this.hasLobs) {
      return false;
    }
    final ArrayList<ExtraTableInfo> oldInfos = this.oldTableInfos;
    if (oldInfos != null) {
      int index = oldInfos.size();
      while (--index >= 0) {
        if (!oldInfos.get(index).getRowFormatter().hasLobs()) {
          return false;
        }
      }
    }
    return true;
  }

  public final ExtraTableInfo getExtraTableInfo() {
    return this.tableInfo;
  }

  /**
   * Return the table info for this container given the first row byte array.
   */
  private final ExtraTableInfo getExtraTableInfoForMultiSchema(
      final byte[] rowBytes) {
    final int schemaVersion = RowFormatter.readVersion(rowBytes);
    // schemaVersion == TOKEN_RECOVERY_VERSION indicates recovery from old
    // product files
    if (isCurrentVersion(schemaVersion)) {
      return this.tableInfo;
    }
    else if (schemaVersion != RowFormatter.TOKEN_RECOVERY_VERSION) {
      return this.oldTableInfos.get(schemaVersion - 1);
    }
    else {
      assert this.tableInfoOnRecovery != null: "unexpected null "
          + "tableInfoOnRecovery for row=" + Arrays.toString(rowBytes);
      return this.tableInfoOnRecovery;
    }
  }

  /**
   * Return the table info for this container given the first row byte array.
   */
  public final ExtraTableInfo getExtraTableInfo(final byte[] rawRow) {

    // if there are no old schemas then simply return current formatter
    if (this.singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion((rowVersion = readVersion(rawRow))):
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return this.tableInfo;
    } else if (rawRow == null) {
      return this.tableInfo;
    } else {
      return getExtraTableInfoForMultiSchema(rawRow);
    }
  }

  /**
   * Return the table info for this container given the first row byte array.
   */
  private ExtraTableInfo getExtraTableInfoForMultiSchema(
      final OffHeapByteSource rawRow) {
    final long memAddr = rawRow.getUnsafeAddress();
    final int schemaVersion = RowFormatter.readVersion(memAddr);
    // schemaVersion == TOKEN_RECOVERY_VERSION indicates recovery from old
    // product files
    if (isCurrentVersion(schemaVersion)) {
      return this.tableInfo;
    }
    else if (schemaVersion != RowFormatter.TOKEN_RECOVERY_VERSION) {
      return this.oldTableInfos.get(schemaVersion - 1);
    }
    else {
      assert this.tableInfoOnRecovery != null: "unexpected null "
          + "tableInfoOnRecovery for row="
          + Arrays.toString(rawRow.getRowBytes());
      return this.tableInfoOnRecovery;
    }
  }

  /**
   * Return the table info for this container given the first row byte array.
   */
  public final ExtraTableInfo getExtraTableInfo(final OffHeapByteSource rawRow) {
    // if there are no old schemas then simply return current formatter
    if (this.singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion((rowVersion = readVersion(rawRow))):
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return this.tableInfo;
    } else if (rawRow == null) {
      return this.tableInfo;
    } else {
      return getExtraTableInfoForMultiSchema(rawRow);
    }
  }

  /**
   * Return the table info for this container given the first row byte array.
   */
  public final ExtraTableInfo getExtraTableInfo(final Object rawRow) {

    // if there are no old schemas then simply return current formatter
    if (this.singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion((rowVersion = readVersion_(rawRow))):
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return this.tableInfo;
    } else if (rawRow == null) {
      return this.tableInfo;
    }

    final Class<?> cls = rawRow.getClass();
    if (cls == byte[].class) {
      return getExtraTableInfoForMultiSchema((byte[])rawRow);
    }
    else if (cls == byte[][].class) {
      return getExtraTableInfoForMultiSchema(((byte[][])rawRow)[0]);
    }
    else if (OffHeapByteSource.isOffHeapBytesClass(cls)) {
      return getExtraTableInfoForMultiSchema((OffHeapByteSource)rawRow);
    }
    else {
      return this.tableInfo;
    }
  }

  private final int readVersion(@Unretained final byte[] rowBytes) {
    if (rowBytes != null && isByteArrayStore()) {
      return RowFormatter.readVersion(rowBytes);
    }
    else {
      return this.schemaVersion;
    }
  }

  private int readVersion(final OffHeapByteSource rowBytes) {
    if (rowBytes != null && isByteArrayStore()) {
      return RowFormatter.readVersion(rowBytes.getUnsafeAddress());
    } else {
      return this.schemaVersion;
    }
  }

  @Unretained
  private final int readVersion_(@Unretained final Object rawRow) {
    if (rawRow == null) return this.schemaVersion;
    final Class<?> cls = rawRow.getClass();
    if (cls == byte[].class) {
      return readVersion((byte[])rawRow);
    }
    else if (cls == byte[][].class) {
      return readVersion(((byte[][])rawRow)[0]);
    }
    else if (OffHeapByteSource.isOffHeapBytesClass(cls)) {
      return readVersion((OffHeapByteSource)rawRow);
    }
    else {
      return this.schemaVersion;
    }
  }

  private final boolean isCurrentVersion(int version) {
    return version == this.schemaVersion || version == 0;
  }

  /**
   * Few sanity checks before performing a schema change for column
   * add/drop/modify.
   */
  private final void checkSchemaChange() {
    // push the current RowFormatter into the oldFormatters list
    if (this.oldTableInfos.size() != (this.schemaVersion - 1)) {
      GemFireXDUtils.throwAssert("unexpected mismatch of old TableInfos "
          + this.oldTableInfos + " and schemaVersion=" + this.schemaVersion);
    }
    if (!isByteArrayStore()) {
      GemFireXDUtils
          .throwAssert("unexpected call for table without byte array store");
    }

    // before changing the schema shape, ensure that WAN queues, if any, are
    // drained completely
    // TODO: we should also add the provision of propagating ALTER TABLE to
    // backend particularly for this case
    waitForGatewayQueuesToFlush();
  }

  /**
   * Schedule adding a new column to the container for commit time.
   */
  public final void scheduleAddColumn(ColumnDescriptor cd,
      LanguageConnectionContext lcc) throws StandardException {
    checkSchemaChange();
    this.tableInfo.refreshAtCommit(cd, -1, lcc);
  }

  /**
   * Schedule dropping a column of the container for commit time.
   */
  public final void scheduleDropColumn(int columnPos,
      LanguageConnectionContext lcc) throws StandardException {
    checkSchemaChange();
    this.tableInfo.refreshAtCommit(null, columnPos, lcc);
    final GfxdIndexManager indexManager = (GfxdIndexManager)getRegion()
        .getIndexUpdater();
    if (indexManager != null) {
      indexManager.invalidateFor(columnPos, lcc);
    }
  }

  /**
   * Actually change the schema version of this table for column formatting
   * change after ALTER TABLE ... ADD/DROP/MODIFY COLUMN. This should be done at
   * the very last since it involves changes that cannot be undone.
   */
  public final void schemaVersionChange(final DataDictionary dd,
      final TableDescriptor td, final LanguageConnectionContext lcc)
      throws StandardException {
    // this tableInfo is no longer current
    this.tableInfo.setLatestSchema(false);
    this.oldTableInfos.add(this.tableInfo);

    this.schemaVersion++;
    this.singleSchema = null;
    ExtraTableInfo.newExtraTableInfo(dd, td, lcc.getContextManager(),
        this.schemaVersion, this, true);
    if (isByteArrayStore()) {
      this.tableInfo.initRowFormatter(this);
    }
  }

  /**
   * Add a new column to the container for all old RowFormatters. Note that a
   * new RowFormatter will have to be created separately by a call to
   * {@link #schemaVersionChange} regardless.
   */
  public final void addColumnAtCommit(ColumnDescriptor cd) {
    for (ExtraTableInfo tableInfo : this.oldTableInfos) {
      tableInfo.getRowFormatter().addColumn(cd);
    }
    if (this.tableInfoOnRecovery != null) {
      this.tableInfoOnRecovery.getRowFormatter().addColumn(cd);
    }
  }

  /**
   * Drop an existing column from the container for all old RowFormatter. Note
   * that a new RowFormatter will have to be created separately by a call to
   * {@link #schemaVersionChange} regardless.
   */
  public final void dropColumnAtCommit(int columnPos) {
    for (ExtraTableInfo tableInfo : this.oldTableInfos) {
      tableInfo.getRowFormatter().dropColumn(columnPos);
    }
    if (this.tableInfoOnRecovery != null) {
      this.tableInfoOnRecovery.getRowFormatter().dropColumn(columnPos);
    }
  }

  /**
   * Refresh any information cached in {@link GFContainerLocking} when table
   * shape changes.
   */
  public final void refreshLockingInfo() {
    if (this.locking != null) {
      this.locking.update(this);
    }
  }

  /**
   * Set the schema version post recovery from disk. This is used as the actual
   * schema version to use for rows recovered from older format using
   * {@link RowFormatter#TOKEN_RECOVERY_VERSION} as their version.
   */
  public final void initPre11SchemaVersionOnRecovery(final DataDictionary dd,
      final LanguageConnectionContext lcc) throws StandardException {
    // adjust the RowFormatter for lack of schema version in the row so use
    // TOKEN_RECOVERY_VERSION
    final TableDescriptor td = getTableDescriptor();
    this.tableInfoOnRecovery = ExtraTableInfo.newExtraTableInfo(dd, td,
        lcc.getContextManager(), RowFormatter.TOKEN_RECOVERY_VERSION, this,
        false);
    this.tableInfoOnRecovery.refreshCachedInfo(td, this);
    this.tableInfoOnRecovery.initRowFormatter(this);
    this.tableInfoOnRecovery.setLatestSchema(false);
    this.singleSchema = null;
    this.schemaVersionOnRecovery = this.schemaVersion;
    // unmark current tableInfo as current since this new tableInfo is set
    // later and when looking up schema we may need to check for this too
    if (this.tableInfo != null) {
      this.tableInfo.setLatestSchema(false);
    }
  }

  /**
   * Get the schema version of pre 1.1 product disk data.
   */
  public final int getPre11SchemaVersionOnRecovery() {
    return this.schemaVersionOnRecovery;
  }

  /**
   * Get the current schema version if this is a byte array store else -1.
   */
  public final int getCurrentSchemaVersion() {
    if (isByteArrayStore()) {
      return this.schemaVersion;
    }
    else {
      return -1;
    }
  }

  public final ExtraIndexInfo getExtraIndexInfo() {
    return this.indexInfo;
  }

  public final String getSchemaName() {
    return this.schemaName;
  }

  public final String getTableName() {
    return this.tableName;
  }

  public final String getQualifiedTableName() {
    return this.qualifiedName;
  }

  public final String getUUID() {
    return this.uuid;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Comparator<? super SortedIndexKey> getComparator() {
    Comparator<Object> cmp = this.comparator;
    if (cmp != null) {
      return cmp;
    }
    else {
      throw new UnsupportedOperationException("getComparator: "
          + "not a local index: " + toString());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public LocalRegion getBaseRegion() {
    if (this.baseContainer != null) {
      return this.baseContainer.getRegion();
    }
    else {
      throw new UnsupportedOperationException("getBaseRegion: not an index: "
          + toString());
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SortedIndexKey getIndexKey(byte[] indexKeyBytes, RegionEntry entry) {
    return new CompactCompositeIndexKey(getExtraIndexInfo(), indexKeyBytes);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SortedIndexKey getIndexKey(Object val, RegionEntry entry) {
    try {
      return getLocalIndexKey(val, entry, false, false);
    } catch (StandardException se) {
      throw new IndexMaintenanceException(se);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void insertIntoIndex(SortedIndexKey indexKey, RegionEntry entry,
      boolean isPutDML) {    
    if (GemFireXDUtils.TraceIndex) {
      GfxdIndexManager.traceIndex("GemFireContainer#insertIntoIndexes: "
          + "index key = %s DiskEntry to be inserted = %s", entry);
    }
    try {
      SortedMap2IndexInsertOperation.doMe(null, null, this, indexKey,
          (RowLocation)entry, isUniqueIndex(), null, isPutDML);
    } catch (StandardException se) {
      throw new IndexMaintenanceException(se);
    }
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Override
  public void buildIndexFromSorted(
      Iterator<Entry<SortedIndexKey, Object>> entryIterator) {
    this.skipListMap.buildFromSorted((Iterator)entryIterator);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void mergeValuesForNonUnique(SortedIndexKey mergeInto,
      SortedIndexKey mergeFrom) throws IndexMaintenanceException {
    Object result = mergeInto.getTransientValue();
    Object from = mergeFrom.getTransientValue();
    if (!this.isUniqueIndex) {
      final Class<?> o2Class = from.getClass();
      final InsertOrUpdateValue updater = SortedMap2IndexInsertOperation
          .insertOrUpdateValueForPut;

      if (o2Class == RowLocation[].class) {
        RowLocation[] rows = (RowLocation[])from;
        for (RowLocation rl : rows) {
          result = updater.updateValue(mergeInto, result, rl, this);
        }
        mergeInto.setTransientValue(result);
      }
      else if (o2Class == ConcurrentTHashSet.class) {
        @SuppressWarnings("unchecked")
        ConcurrentTHashSet<Object> set = (ConcurrentTHashSet<Object>)from;
        for (Object o : set) {
          result = updater.updateValue(mergeInto, result, (RowLocation)o, this);
        }
        mergeInto.setTransientValue(result);
      }
      else {
        mergeInto.setTransientValue(updater.updateValue(mergeInto, result,
            (RowLocation)from, this));
      }
    }
    else {
      throw new IndexMaintenanceException(
          GemFireXDUtils.newDuplicateKeyViolation("unique constraint",
              getQualifiedTableName(),
              "key=" + mergeInto + ", rows=" + ArrayUtils.objectString(result),
              ArrayUtils.objectString(from), null, null));
    }
  }



  public static GemFireContainer getContainerFromIdentityKey(String key) {
    String tableName;
    int uuidIndex = key.lastIndexOf(':');
    if (uuidIndex > 0) {
      tableName = key.substring(0, uuidIndex);
    }
    else {
      tableName = key;
    }
    return (GemFireContainer)Misc.getRegionForTableByPath(tableName, true)
        .getUserAttribute();
  }

  public final int getNumColumns() {
    return this.tableInfo != null ? this.tableInfo.getRowFormatter()
        .getNumColumns() : -1;
  }

  /**
   * Return the current RowFormatter for this table.
   */
  public final RowFormatter getCurrentRowFormatter() {
    return this.tableInfo != null ? this.tableInfo.getRowFormatter() : null;
  }

  private final RowFormatter getRowFormatterForMultiSchema(final byte[] rowRow) {
    final int schemaVersion = RowFormatter.readVersion(rowRow);
    // schemaVersion == TOKEN_RECOVERY_VERSION indicates recovery from old
    // product files
    if (isCurrentVersion(schemaVersion)) {
      return this.tableInfo.getRowFormatter();
    }
    else if (schemaVersion != RowFormatter.TOKEN_RECOVERY_VERSION) {
      return this.oldTableInfos.get(schemaVersion - 1).getRowFormatter();
    }
    else {
      assert this.tableInfoOnRecovery != null: "unexpected null "
          + "tableInfoOnRecovery for row=" + Arrays.toString(rowRow);
      return this.tableInfoOnRecovery.getRowFormatter();
    }
  }

  public final RowFormatter getRowFormatter(final byte[] rawRow) {
    // if there are no old schemas then simply return current formatter
    final RowFormatter singleSchema = this.singleSchema;
    if (singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion(rowVersion = readVersion(rawRow)) :
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return singleSchema;
    } else if (!isByteArrayStore()) {
      return null;
    } else if (rawRow == null) {
      return this.tableInfo.getRowFormatter();
    } else {
      return getRowFormatterForMultiSchema(rawRow);
    }
  }

  public final RowFormatter getRowFormatter(final byte[][] rawRow) {
    // if there are no old schemas then simply return current formatter
    final RowFormatter singleSchema = this.singleSchema;
    if (singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion(rowVersion = readVersion(rawRow[0])) :
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return singleSchema;
    } else if (!isByteArrayStore()) {
      return null;
    } else if (rawRow == null || rawRow.length == 0) {
      return this.tableInfo.getRowFormatter();
    } else {
      return getRowFormatterForMultiSchema(rawRow[0]);
    }
  }

  private RowFormatter getRowFormatterForMultiSchema(final long memAddr,
      @Unretained final OffHeapByteSource rawRow) {
    final int schemaVersion = RowFormatter.readVersion(memAddr);
    // schemaVersion == TOKEN_RECOVERY_VERSION indicates recovery from old
    // product files
    if (isCurrentVersion(schemaVersion)) {
      return this.tableInfo.getRowFormatter();
    }
    else if (schemaVersion != RowFormatter.TOKEN_RECOVERY_VERSION) {
      return this.oldTableInfos.get(schemaVersion - 1).getRowFormatter();
    }
    else {
      assert this.tableInfoOnRecovery != null: "unexpected null "
          + "tableInfoOnRecovery for row="
          + Arrays.toString(rawRow.getRowBytes());
      return this.tableInfoOnRecovery.getRowFormatter();
    }
  }

  public final RowFormatter getRowFormatter(
      @Unretained final OffHeapByteSource rawRow) {
    // if there are no old schemas then simply return current formatter
    final RowFormatter singleSchema = this.singleSchema;
    if (singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion(rowVersion = readVersion(rawRow)):
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return singleSchema;
    } else if (!isByteArrayStore()) {
      return null;
    } else if (rawRow == null) {
      return this.tableInfo.getRowFormatter();
    } else {
      return getRowFormatterForMultiSchema(rawRow.getUnsafeAddress(), rawRow);
    }
  }

  public final RowFormatter getRowFormatter(final long memAddr,
      @Unretained final OffHeapByteSource rawRow) {
    // if there are no old schemas then simply return current formatter
    final RowFormatter singleSchema = this.singleSchema;
    if (singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion(rowVersion = readVersion(rawRow)):
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return singleSchema;
    } else if (!isByteArrayStore()) {
      return null;
    } else {
      return getRowFormatterForMultiSchema(memAddr, rawRow);
    }
  }

  /**
   * Return the RowFormatter for given row bytes of this table.
   * 
   * @return the RowFormatter, or null if this is not a byte array store table.
   */
  public final RowFormatter getRowFormatter(@Unretained final Object rawRow) {
    // if there are no old schemas then simply return current formatter
    final RowFormatter singleSchema = this.singleSchema;
    if (singleSchema != null) {
      int rowVersion;
      assert isCurrentVersion(rowVersion = readVersion_(rawRow)):
          "unexpected version in row=" + rowVersion + ", schemaVersion="
              + this.schemaVersion;

      return singleSchema;
    } else if (!isByteArrayStore()) {
      return null;
    } else if (rawRow == null) {
      return this.tableInfo.getRowFormatter();
    }

    final Class<?> cls = rawRow.getClass();
    if (cls == byte[].class) {
      return getRowFormatterForMultiSchema((byte[])rawRow);
    }
    else if (cls == byte[][].class) {
      return getRowFormatterForMultiSchema(((byte[][])rawRow)[0]);
    }
    else if (OffHeapByteSource.isOffHeapBytesClass(cls)) {
      final OffHeapByteSource bs = (OffHeapByteSource)rawRow;
      return getRowFormatterForMultiSchema(bs.getUnsafeAddress(), bs);
    }
    else {
      return this.tableInfo.getRowFormatter();
    }
  }

  public final RowFormatter getRowFormatter(
      @Unretained final OffHeapByteSource vbs, final ExtraTableInfo tableInfo) {
    return tableInfo != null ? tableInfo.getRowFormatter(vbs)
        : getRowFormatter(vbs);
  }

  public final RowFormatter getRowFormatter(final byte[] vbytes,
      final ExtraTableInfo tableInfo) {
    return tableInfo != null ? tableInfo.getRowFormatter(vbytes)
        : getRowFormatter(vbytes);
  }

  /**
   * Return the RowFormatter for a row prefix in this table. This allocates a
   * new column descriptor list, it is best to reuse this list as much as
   * possible before allocating a new one.
   * 
   * Consider caching cdl prefixes here.
   * 
   * @param numCols
   *          the number of columns in the prefix
   * 
   * @return the RowFormatter for given number of columns, or null if this is
   *         not a byte array store table.
   */
  public final RowFormatter getRowFormatter(final int numCols) {
    if (isByteArrayStore()) {
      final RowFormatter rf = this.tableInfo.getRowFormatter();
      final ColumnDescriptor[] cdl = rf.columns;
      if (numCols == cdl.length) {
        return rf;
      }
      else {
        return new RowFormatter(cdl, numCols, this.schemaVersion, this, true);
      }
    }
    else {
      return null;
    }
  }

  /**
   * Return the RowFormatter for a (compacted) partial row in this table.
   * 
   * @param validColumns
   *          the columns to include in the list
   * @return the RowFormatter for the given columns, or null if this is not a
   *         byte array store table.
   */
  public final RowFormatter getRowFormatter(final FormatableBitSet validColumns) {
    if (isByteArrayStore()) {
      if (validColumns != null) {
        return new RowFormatter(this.tableInfo.getTableDescriptor()
            .getColumnDescriptorList(), validColumns, this.schemaVersion, this);
      }
      return this.tableInfo.getRowFormatter();
    }
    return null;
  }

  /**
   * Return the RowFormatter for a (compacted) partial row in this table.
   * 
   * @param validColumns
   *          the columns to include in the list
   * @param isPrimaryKeyFormatter
   *          indicates whether this row formatter is for primary key columns.
   * @return the RowFormatter for the given columns, or null if this is not a
   *         byte array store table.
   */
  public final RowFormatter getRowFormatter(final int[] validColumns,
      TableDescriptor td, final int schemaVersion,
      final boolean isPrimaryKeyFormatter) {
    return new RowFormatter(td.getColumnDescriptorList(), validColumns,
        this.schemaName, this.tableName, schemaVersion, this,
        isPrimaryKeyFormatter);
  }

  /**
   * Return the RowFormatter for a (compacted) partial row in this table.
   * 
   * @param cqi
   *          the columns to include in the list
   * @return the RowFormatter for the given columns, or null if this is not a
   *         byte array store table.
   */
  public final RowFormatter getRowFormatter(final ColumnQueryInfo[] cqi) {
    return new RowFormatter(cqi, this.schemaVersion, this);
  }

  /**
   * Clear all the entries in the underlying region. The distributed lock on the
   * table and DataDictionary must be held before invoking this method, so no
   * concurrent DML operations are in progress.
   * 
   * This operation is designed to never fail since there is no way to recover
   * back to previous state if the clear succeeds on some nodes but fails on
   * others.
   */
  public final void clear(final LanguageConnectionContext lcc,
      final GemFireTransaction tc) throws StandardException {
    if (this.skipListMap != null) {
      // this should never get invoked for index directly rather only on table
      this.skipListMap.clear();
    }
    else if (this.region != null && !this.region.isDestroyed()
        // nothing to be done in DDL replay
        && !Misc.initialDDLReplayInProgress()) {
      // for partitioned regions, step through all the hosted BucketRegions and
      // clear them
      if (isPartitioned()) {
        final PartitionedRegion pr = (PartitionedRegion)this.region;
        // first acquire the recovery lock to avoid any rebalance etc.
        // this is done only on the source node
        if (!lcc.isConnectionForRemote()) {
          final AtomicBoolean locked = new AtomicBoolean();
          final RecoveryLock rl = ColocationHelper.getLeaderRegion(pr).getRecoveryLock();
          rl.lock();
          locked.set(true);
          // register operation for releasing the lock at commit/abort
          tc.logAndDo(new MemOperation(null) {
            @Override
            public void doMe(Transaction xact, LogInstant instant,
                LimitObjectInput in) throws StandardException, IOException {
              if (GemFireXDUtils.TraceConglom) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
                    "Invoked localClear.RecoveryLock.unlock with locked="
                        + locked.get() + " for " + xact);
              }
              if (locked.compareAndSet(true, false)) {
                try {
                  rl.unlock();
                } catch (Exception e) {
                  // ignore any exception
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
                      "GemFireContainer#clear: ignoring exception "
                          + "in bucket recovery lock release", e);
                }
              }
            }

            @Override
            public boolean doAtCommitOrAbort() {
              return true;
            }

            @Override
            public Compensation generateUndo(Transaction xact,
                LimitObjectInput in) throws StandardException, IOException {
              // release the lock in any case if it has been acquired
              return this;
            }
          });
        }

        pr.clearLocalPrimaries();
      }
      else {
        // fire the clear only on the source node for replicated tables
        if (this.region.getScope().isLocal() || !lcc.isConnectionForRemote()) {
          try {
            this.region.clear();
          } catch (Exception e) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
                "GemFireContainer#clear: ignoring exception "
                    + "in region clear", e);
          }
        }
      }
      // now clear the indexes
      if (!isGlobalIndex()) {
        final GfxdIndexManager indexManager = (GfxdIndexManager)this.region
            .getIndexUpdater();
        // index manager can be null for temporary tables
        if (indexManager != null) {
          for (GemFireContainer container : indexManager.getAllIndexes()) {
            container.clear(lcc, tc);
          }
        }
      }
      // reset the count of the rows
      this.previousNumRows = 0;
      // remove the identity key entries, if any
      removeIdentityRegionEntries(tc);
    }
  }

  public final int getTotalNumRows(final LanguageConnectionContext lcc)
      throws StandardException {
    final DataPolicy policy = this.regionAttributes.getDataPolicy();
    if (policy.withPartitioning() || this.regionAttributes.getScope().isLocal()
        || policy.withStorage()) {
      return this.region.size();
    }
    else if (policy.withReplication() || !policy.withStorage()) {
      final RegionSizeMessage rmsg = new RegionSizeMessage(
          this.region.getFullPath(), new GfxdSingleResultCollector(),
          this.region, null, lcc);
      try {
        Object result = rmsg.executeFunction();
        assert result instanceof Integer;
        return ((Integer)result).intValue();
      } catch (SQLException sqle) {
        throw Misc.wrapRemoteSQLException(sqle, sqle, null);
      }
    }
    else {
      throw GemFireXDRuntimeException.newRuntimeException("Unknown region type: "
          + this.region, null);
    }
  }

  /**
   * Get the number of rows in the local VM for primary buckets only.
   */
  public final int getLocalNumRows(Set<Integer> bucketIds,
      boolean withSecondaries, boolean queryHDFS) throws StandardException {
    final DataPolicy policy = this.regionAttributes.getDataPolicy();
    if (policy.withPartitioning()) {
      PartitionedRegion pr = (PartitionedRegion)this.region;
      
      pr.setQueryHDFS(queryHDFS);
      
      if (withSecondaries) {
        
        Set<Integer> allBuckets = pr.getDataStore()
            .getAllLocalBucketIds();
        
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceAggreg) {
            Map<Integer, SizeEntry> bucketSizes = null;
            if (pr.getDataStore() != null) {
              bucketSizes = pr.getDataStore().getSizeLocallyForBuckets(
                  allBuckets);
            }
            int size = 0;
            if (bucketSizes != null) {
              for(SizeEntry entry : bucketSizes.values()) {
                size += entry.getSize();
              }

              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_AGGREG,
                  "witSecondaries=" + withSecondaries
                      + " local buckets size determined with "
                      + allBuckets.size() + " buckets as bucketIds=" + allBuckets
                      + " as \n" + bucketSizes.size() + " bucket sizes as "
                      + bucketSizes + " totaling to " + size);
            }
          }
        }
        
        return pr.entryCount(pr.getTXState(), allBuckets);
      }
      else if (bucketIds != null) {
        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceAggreg) {
            Map<Integer, SizeEntry> bucketSizes = null;
            if (pr.getDataStore() != null) {
              bucketSizes = pr.getDataStore().getSizeLocallyForBuckets(
                  bucketIds);
            }

            int size = 0;
            if (bucketSizes != null) {
              for(SizeEntry entry : bucketSizes.values()) {
                size += entry.getSize();
              }
            
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AGGREG,
                  "Bucket Sizes determined with " + bucketIds.size()
                      + " buckets as bucketIds=" + bucketIds + " as \n"
                      + bucketSizes.size() + " bucket sizes as " + bucketSizes
                      + " totalling to " + size);
            
            }
          }
        }
        
        return pr.entryCount(pr.getTXState(), bucketIds);
      }
      else {
        return pr.entryCount(pr.getTXState(), pr.getDataStore()
            .getAllLocalPrimaryBucketIds());
      }
    }
    else if (policy.withReplication()
        || this.regionAttributes.getScope().isLocal()) {
      return this.region.size();
    }
    else {
      throw GemFireXDRuntimeException.newRuntimeException("Unknown region type: "
          + this.region, null);
    }
  }

  final void drop(final GemFireTransaction tran) throws StandardException {
    try {
      if (this.baseContainer != null) {
        accountIndexMemory(false, true);
      }
      localDestroy(tran);
    } finally {
      // remove dependencies for ExtraTableInfo
      ExtraTableInfo tableInfo = this.tableInfoOnRecovery;
      if (tableInfo != null) {
        tableInfo.drop(tran);
      }
      ArrayList<ExtraTableInfo> oldInfos = this.oldTableInfos;
      if (oldInfos != null && !oldInfos.isEmpty()) {
        for (ExtraTableInfo info : oldInfos) {
          info.drop(tran);
        }
      }
      tableInfo = this.tableInfo;
      if (tableInfo != null) {
        tableInfo.drop(tran);
      }

      // drop dependencies for index manager
      LocalRegion r = this.region;
      GfxdIndexManager indexManager;
      if (r != null
          && (indexManager = (GfxdIndexManager)r.getIndexUpdater()) != null) {
        indexManager.drop(tran);
      }
      if (this.skipListMap != null) {
        releaseIndexMemory();
      }
    }
  }

  /**
   * Destroy the underlying datastore (GemFire region or skipList) locally.
   */
  final void localDestroy(final GemFireTransaction tran)
      throws StandardException {
    final LocalRegion region = this.region;
    if (this.skipListMap != null && this.baseContainer.isApplicationTable()) {
      this.skipListMap.clear();
      LocalRegion baseRegion;
      // in CREATE TABLE failure, the baseRegion may not have been created yet
      if (this.baseContainer.isApplicationTable()
          && (baseRegion = this.baseContainer.getRegion()) != null
          && baseRegion.getDataPolicy().withPersistence()) {
        // write IF record for index delete
        DiskStoreImpl dsImpl = baseRegion.getDiskStore();
        if (dsImpl != null) {
          if (GemFireXDUtils.TracePersistIndex) {
            GfxdIndexManager.traceIndex("GemFireContainer::localDestroy "
                + " writing the delete index dif record for "
                + this.qualifiedName + " in " + dsImpl);
          }
          dsImpl.writeIndexDelete(this.uuid);
        }
        if (this.stats != null) {
          this.stats.close();
        }
      }
    }
    else if (region == null || region.isDestroyed()) {
      final LanguageConnectionContext lcc;
        // [sumedh] A genuine case should be caught at the derby level
        // while other case where it can arise is receiving a GfxdDDLMessage
        // for a region destruction that has already been replayed using
        // the hidden _DDL_STMTS_REGION.
        if ((region == null || ((!Misc.initialDDLReplayInProgress() && tran != null)
            && (lcc = tran.getLanguageConnectionContext()) != null
            && !lcc.isConnectionForRemote()))
            && GemFireXDUtils.TraceDDLReplay) {
//        if (GemFireXDUtils.TraceDDLReplay) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
              "GemFireContainer#destroy: region ["
                  + (region != null ? region.getFullPath() : "null")
                  + "] for table '" + this.qualifiedName
                  + "' already destroyed");
        throw new RegionDestroyedException(toString(), this.qualifiedName);
      }
    }
    else if (region.isInitialized()) {
      // before dropping the table, ensure that WAN queues, if any, are
      // drained completely         
      waitForGatewayQueuesToFlush();
      // Do not distribute region destruction to other caches since the
      // distribution is already handled by GfxdDDLMessages.
      // [sumedh] Special treatment for partitioned region to skip the parent
      // colocated region check. DDL locking ensures that there is no concurrent
      // DML on the region so there is no problem with inconsistency for PR with
      // parent region existing on some nodes and not on others.
      if (this.regionAttributes.getDataPolicy().withPartitioning()) {      
//        ((PartitionedRegion)region).localDestroyRegion(null, true);
        final LanguageConnectionContext lcc;
        if (!Misc.initialDDLReplayInProgress() && tran != null
            && (lcc = tran.getLanguageConnectionContext()) != null
            && !lcc.isConnectionForRemote()) {
          region.destroyRegion(null);
        } else if (Misc.initialDDLReplayInProgress()) {
          ((PartitionedRegion)region).localDestroyRegion(null, true);
        }
      }
      else {        
        region.localDestroyRegion();
      }
      removeIdentityRegionEntries(tran);
    }
    else {
      // cleanup a failed initialization
      region.cleanupFailedInitialization();
    }
    if (this.globalIndexMap != null) {
      this.globalIndexMap.destroyCache();
    }
    tran.getLockSpace().addToFreeResources(this);
  }

  public void removeIdentityRegionEntries(final GemFireTransaction tran) {
    // remove this region's identity column entry if any
    final LanguageConnectionContext lcc;
    if (!Misc.initialDDLReplayInProgress() && tran != null
        && (lcc = tran.getLanguageConnectionContext()) != null
        && !lcc.isConnectionForRemote()) {
      // get the identity column positions of the table
      final ExtraTableInfo tableInfo = getExtraTableInfo();
      if (tableInfo != null && tableInfo.hasAutoGeneratedColumns()) {
        GemFireStore store = Misc.getMemStore();
        LocalRegion identityRgn = store.getIdentityRegion();
        if (identityRgn != null) {
          try {
            IdentityValueManager.GetIdentityValueMessage msg =
                new IdentityValueManager.GetIdentityValueMessage(
                    identityRgn, getUUID(), 0, 0, lcc);
            msg.executeFunction();
          } catch (Exception ignore) {
          }
        }
      }
    }
  }

  private void waitForGatewayQueuesToFlush() {
    final LocalRegion region = getRegion();

    Set<GatewaySender> senders = getGatewaySenders(region);
    
    for(GatewaySender s : senders) {
      AbstractGatewaySender sender = (AbstractGatewaySender)s;
      if (!sender.isRunning()) {
        continue;
      }

      final RegionQueue senderQ = sender.getQueue();
      final long end = System.currentTimeMillis()
          + GfxdLockSet.MAX_LOCKWAIT_VAL;
      if (senderQ != null) {
        while (senderQ.size() != 0) {
          Throwable t = null;
          try {
            Thread.sleep(100);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            t = ie;
          }
          // check for JVM going down
          Misc.checkIfCacheClosing(t);
          if (System.currentTimeMillis() > end || !sender.isRunning()) {
            break;
          }
        }
      }
    }
    
  }
  
  @SuppressWarnings("unchecked")
  public Set<GatewaySender> getGatewaySenders(final LocalRegion region) {
    THashSet tempSet = new THashSet();
    if (region.checkNotifyGatewaySender()) {
      final Set<String> allGatewaySenderIds = region.getAllGatewaySenderIds();
      for (Object s : region.getCache().getAllGatewaySenders()) {
        final AbstractGatewaySender sender = (AbstractGatewaySender)s;
        if (!sender.getId().startsWith(
            AsyncEventQueueImpl.ASYNC_EVENT_QUEUE_PREFIX)
            && allGatewaySenderIds.contains(sender.getId())) {
          tempSet.add(sender);
        }
      }
    }

    return tempSet;
  }
  
  @SuppressWarnings("unchecked")
  public Set<AsyncEventQueue> getAsyncEventQueues(final LocalRegion region) {
    THashSet tempSet = new THashSet();
    if (region.checkNotifyGatewaySender()) {
      final Set<String> allAsyncEventQueueIds = region.getAsyncEventQueueIds();
      for (Object q : region.getCache().getAsyncEventQueues()) {
        final AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)q;
        if (!asyncQueue.getId().startsWith(
            AsyncEventQueueImpl.ASYNC_EVENT_QUEUE_PREFIX)
            && allAsyncEventQueueIds.contains(asyncQueue.getId())) {
          tempSet.add(asyncQueue);
        }
      }
    }

    return tempSet;
  }

  public final boolean isInitialized() {
    if (this.region != null) {
      return this.region.isInitialized();
    }
    else {
      return this.isIndexInitialized;
    }
  }

  /**
   * Get the underlying GemFire Region
   * 
   * @return the underlying GemFire Region
   */
  public final LocalRegion getRegion() {
    return this.region;
  }

  public final boolean isAccessorForRegion() {
    final LocalRegion r = this.region;
    if (r != null) {
      if (r.getPartitionAttributes() != null) {
        return r.getPartitionAttributes().getLocalMaxMemory() == 0;
      }
      else {
        return !r.getDataPolicy().withStorage();
      }
    }
    return false;
  }

  public final RegionAttributes<?, ?> getRegionAttributes() {
    return this.regionAttributes;
  }

  /**
   * Get the underlying SkipList Map for a local sorted index.
   * 
   * IMPORTANT NOTE: any code using skiplist directly to do gets/iteration
   * should take note of potential race conditions on index key not qualifying
   * against obtained RowLocations; see the code in
   * SortedMap2IndexScanController.qualifyRow; use SortedMap2IndexScanController
   * where possible instead of direct reads; similarly code doing writes should
   * use MapCallbacks to ensure atomic changeover of values (though no code
   * other than in GfxdIndexManager should be updating index in the first place)
   */
  public final ConcurrentSkipListMap<Object, Object> getSkipListMap() {
    return this.skipListMap;
  }

  /**
   * Get the {@link GfxdIndexManager} for this application table or index.
   */
  public GfxdIndexManager getIndexManager() {
    final LocalRegion r = this.region;
    if (r != null) {
      return (GfxdIndexManager)r.getIndexUpdater();
    }
    final GemFireContainer c = this.baseContainer;
    if (c != null) {
      return c.getIndexManager();
    }
    else {
      return null;
    }
  }

  public final TXStateInterface getActiveTXState(final GemFireTransaction tr) {
    return tr != null && (this.containerFlags &
        FOR_APPTABLE_OR_GLOBAL_INDEX) != 0 ? tr.getActiveTXState() : null;
  }

  /**
   * This method is intended to return Iterator on the entries contained in the
   * local data store and current transactional view & should span only the
   * entries contained in the primary buckets if the "primaryOnly" parameter is
   * true.
   * 
   * It is expected that the caller will take a read lock on current entry in
   * transactional context since transactional writes do not take synchronized
   * lock when committing that can result in race conditions for disk entries.
   * 
   * @return Iterator on the entries contained in the local data store and
   *         transactional context
   */
  public final Iterator<?> getEntrySetIterator(final TXStateInterface tx,
      final boolean primaryOnly, final int scanType, boolean includeValues) {
    final boolean forUpdate =
      (scanType & TransactionController.OPENMODE_FORUPDATE) != 0;
    final boolean globalScan =
      (scanType & GfxdConstants.SCAN_OPENMODE_FOR_GLOBALINDEX) != 0;
    final boolean isPR = regionAttributes.getPartitionAttributes() != null;

    // TODO: SW: can we get rid of this global block? shouldn't this be using
    // a local iterator on each relevant node instead? this is not optimized
    // at all for disk iteration
    if (globalScan && isPR) {
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer#getEntrySetIterator: In Global Scan block "
                + "for container " + this);
      }
      return new PREntriesFullIterator((PartitionedRegion)this.region);
    }

    final InternalDataView view = this.region.getDataView(tx);
    if (GemFireXDUtils.TraceConglomUpdate) {
      final boolean isTXView = (view instanceof TXStateInterface);
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          "GemFireContainer#getEntrySetIterator: Creating "
              + (isPR ? "Partitioned Region " : "Replicated Region ")
              + " based " + (isTXView ? "TX" : "non-TX")
              + " iterator for container " + this
              + (tx != null ? ("; " + tx) : ""));
    }
    return view.getLocalEntriesIterator((InternalRegionFunctionContext)null,
        primaryOnly, forUpdate, includeValues, this.region);
  }

  /**
   * This method is intended to return Iterator on the entries contained in the
   * local data store and current transactional view & should span only the
   * entries contained in the primary buckets if the "primaryOnly" parameter is
   * true. This method will limit the iterator to only the buckets specified by
   * the provided function context, if any.
   * 
   * It is expected that the caller will take a read lock on current entry in
   * transactional context since transactional writes do not take synchronized
   * lock when committing that can result in race conditions for disk entries.
   * 
   * @return Iterator on the entries contained in the local data store and
   *         transactional context, constrained by the bucket set, if any, of
   *         the provided function context
   */
  public final Iterator<?> getEntrySetIteratorForFunctionContext(
      final InternalRegionFunctionContext context,
      final GemFireTransaction tran, final TXStateInterface tx,
      final int scanType, boolean primaryOnly) {

    final boolean forUpdate =
      (scanType & TransactionController.OPENMODE_FORUPDATE) != 0;
    final InternalDataView view = this.region.getDataView(tx);
    if (GemFireXDUtils.TraceConglomUpdate) {
      final boolean isPR = regionAttributes.getPartitionAttributes() != null;
      final boolean isTXView = (view instanceof TXStateInterface);
      @SuppressWarnings("unchecked")
      final Set<Integer> bucketSet = context.getLocalBucketSet(this.region);
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          "GemFireContainer#getEntrySetIterator: Creating "
              + (isPR ? "Partitioned Region " : "Replicated Region ")
              + " based " + (isTXView ? "TX" : "non-TX")
              + " iterator for container " + this + ", with bucketSet "
              + bucketSet + "; TX = " + tran);
    }
    return view.getLocalEntriesIterator(context, primaryOnly, forUpdate, true,
        this.region);
  }

  public final Iterator<?> getEntrySetIteratorForBucketSet(
      final Set<Integer> bucketSet, final GemFireTransaction tran,
      final TXStateInterface tx, final int scanType, boolean primaryOnly, boolean fetchRemote) {

    final boolean forUpdate = (scanType & TransactionController
        .OPENMODE_FORUPDATE) != 0;
    final InternalDataView view = this.region.getDataView(tx);
    if (GemFireXDUtils.TraceConglomUpdate) {
      final boolean isPR = regionAttributes.getPartitionAttributes() != null;
      final boolean isTXView = (view instanceof TXStateInterface);

      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          "GemFireContainer#getEntrySetIteratorForBucketSet: Creating "
              + (isPR ? "Partitioned Region " : "Replicated Region ")
              + " based " + (isTXView ? "TX" : "non-TX")
              + " iterator for container " + this + ", with bucketSet "
              + bucketSet + "; TX = " + tran);
    }
    return view.getLocalEntriesIterator(bucketSet, primaryOnly, forUpdate,
        true, this.region, fetchRemote);
  }
  
  public Iterator<?> getEntrySetIteratorHDFSSplit(Object hdfsSplit) {
    //final InternalDataView view = this.region.getDataView((TXStateInterface) null);
    if (GemFireXDUtils.TraceConglomUpdate) {
      final boolean isPR = regionAttributes.getPartitionAttributes() != null;
      //final boolean isTXView = (view instanceof TXStateInterface);

      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          "GemFireContainer#getEntrySetIteratorHDFSSplit: Creating "
              + (isPR ? "Partitioned Region " : "Replicated Region ")
              + " based "
              + " iterator for container " + this + ", with split "
              + hdfsSplit);
    }
    return HDFSSplitIteratorWrapper.getIterator(this.region, hdfsSplit);
  }

  public static Iterator<?> getEntrySetIteratorForRegion(LocalRegion r) {

    final boolean isPR = r.getAttributes().getPartitionAttributes() != null;
      
    if (isPR) {
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer#getEntrySetIterator: In Global Scan block "
                + "for container " + r);
      }
      return new PREntriesFullIterator((PartitionedRegion)r);
    }

    final InternalDataView view = r.getDataView((TXStateInterface)null);
    if (GemFireXDUtils.TraceConglomUpdate) {
      final boolean isTXView = (view instanceof TXStateInterface);
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          "GemFireContainer#getEntrySetIterator: Creating "
              + (isPR ? "Partitioned Region " : "Replicated Region ")
              + " based " + (isTXView ? "TX" : "non-TX")
              + " iterator for container " + r + "; ");
    }
    return view.getLocalEntriesIterator((InternalRegionFunctionContext)null,
        true, false, false, r);
    
  }
  /**
   * Returns a full scan on the table that will fetch entries from all nodes
   * used by global index scan for index consistency checks. The difference from
   * the PR scan is that each returned entry is a RowLocation as expected by
   * GemFireXD scan controllers rather than Region.Entry like the GFE iterator.
   * 
   * TODO: PERF: we can avoid wrapping with Region.Entry in the iterator below,
   * but will it be important given that this is invoked only for full index
   * checks
   * 
   * @author asif
   */
  private static final class PREntriesFullIterator implements
      PREntriesIterator<Object> {

    private final PREntriesIterator<?> entriesItr;

    PREntriesFullIterator(PartitionedRegion pr) {
      this.entriesItr = (PREntriesIterator<?>)pr.allEntries().iterator();
    }

    @Override
    public boolean hasNext() {
      return this.entriesItr.hasNext();
    }

    @Override
    public RowLocation next() {
      final Region.Entry<?, ?> entry = (Region.Entry<?, ?>)this.entriesItr
          .next();
      assert entry != null;
      if (!entry.isDestroyed()) {
        if (GemFireXDUtils.TraceConglomRead) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
              "PREntriesFullIterator#next: returning entry: " + entry);
        }
        return extractRowLocationFromEntry(entry);
      }
      else {
        return null;
      }
    }

    @Override
    public PartitionedRegion getPartitionedRegion() {
      return this.entriesItr.getPartitionedRegion();
    }

    @Override
    public int getBucketId() {
      return this.entriesItr.getBucketId();
    }

    @Override
    public Bucket getBucket() {
      return this.entriesItr.getBucket();
    }

    @Override
    public BucketRegion getHostedBucketRegion() {
      return this.entriesItr.getHostedBucketRegion();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("Unsupported Operation remove!");
    }

    private static RowLocation extractRowLocationFromEntry(
        final Region.Entry<?, ?> entry) {
      if (entry != null) {
        final Class<?> cls = entry.getClass();
        if (cls == LocalRegion.NonTXEntry.class) {
          return (RowLocation)((LocalRegion.NonTXEntry)entry).getRegionEntry();
        }
        else if (cls == TXEntry.class) {
          return (GfxdTXEntryState)((TXEntry)entry).getTXEntryState();
        }
        else {
          return (RowLocation)((EntrySnapshot)entry).getRegionEntry();
        }
      }
      else {
        return null;
      }
    }
  }

  /**
   * DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   */
  public long getRowSize() {
    boolean queryHDFS;
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc != null) {
      queryHDFS = lcc.getQueryHDFS();
      CompilerContext cc = (CompilerContext) (lcc.getContextManager().getContext(CompilerContext.CONTEXT_ID));
      // if cc == null, it might be using cached query plan, therefore skip setting queryHDFS 
      if (cc != null) {
        if (this.regionAttributes.getDataPolicy().withPartitioning()) {
          if (cc.getHasQueryHDFS()) {
            ((PartitionedRegion)this.region).setQueryHDFS(cc.getQueryHDFS());
          }
          else {
            ((PartitionedRegion)this.region).setQueryHDFS(queryHDFS);
          }
        }
      }
    }
    final long sz = this.rowSize;
    if (sz > 0) {
      return sz;
    }

    if (this.regionAttributes.getDataPolicy().withPartitioning()) {
      assert this.region instanceof PartitionedRegion;
      final PartitionedRegion pr = (PartitionedRegion)this.region;
      if (pr.getLocalMaxMemory() == 0) {
        return 0L;
      }
    }
    /*
     * // !!ezoerner::20090414 this was trunk version // so would compile with
     * new function execution // APIs itr =
     * pr.getLocalDataSet(false).values().iterator();
     */
    // Asif: We cannot assume non tx ,because if inserts are done during startup
    // within transaction then committed data will be zero rows which can give
    // incorrect results for update as the optimizer may think no rows are
    // present
    final TXStateInterface tx = isApplicationTableOrGlobalIndex() ? TXManagerImpl
        .getCurrentTXState() : null;
    // iterate only over primary for HDFS region
    boolean primaryOnly = this.getRegion().isHDFSReadWriteRegion();
    final Iterator<?> itr = this.getEntrySetIterator(tx,
        primaryOnly, TransactionController
            .OPENMODE_FORUPDATE /* so that TX data is considered */, false);
    long newRowSize = 0;
    while (itr.hasNext()) {
      try {
        final RowLocation rl = (RowLocation)itr.next();
        Assert.assertTrue(rl != null, "unexpected null encountered");
        if (rl.isUpdateInProgress()) {
          continue;
        }
        // #43228
        if (isGlobalIndex()) {
          Object val = rl.getValueWithoutFaultIn(this);
          if (val != null) {
            assert val instanceof GlobalRowLocation;
            return ((GlobalRowLocation)val).estimateMemoryUsage();
          }
        }
        final ExecRow row = rl.getRowWithoutFaultIn(this);
        if (row != null) {
          newRowSize = row.estimateRowSize();
          row.releaseByteSource();
          // this will cache the row size and will not calculate every single
          // time.
          this.rowSize = newRowSize;
          break;
        }
      } catch (StandardException se) {
        // move on to the next entry.
      } catch (PrimaryBucketException pe) {
        // move on to the next entry.
      }
    } // while loop

    return newRowSize;
  }

  /**
   * Inserts the given row into the table returning the region key. Also sets
   * the routing object, if any, used by the PartitionRegion machinery in the
   * {@link LanguageConnectionContext#getContextObject()}.
   */
  public Object insertRow(DataValueDescriptor[] row, GemFireTransaction tran,
      final TXStateInterface tx, LanguageConnectionContext lcc, boolean isPutDML)
      throws StandardException {
    if (tran == null || !tran.needLogging()) {
      /*
      if (lcc != null && !lcc.isInsertEnabled()) {
        return null;
      }
      */

      if (GemFireXDUtils.TraceConglomUpdate | GemFireXDUtils.TraceQuery ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: inserting row [" + RowUtil.toString(row)
                + "] [" + tx + "] in container: " + toString());
      }

      final int[] primaryKeyColumns;
      Object regionKey;
      final Object val;
      if (isByteArrayStore()) {
        final RowFormatter rf = this.tableInfo.getRowFormatter();
        CompactCompositeRegionKey cKey = null;
        primaryKeyColumns = this.tableInfo.getPrimaryKeyColumns();
        if ((regionKey = getGeneratedKey(primaryKeyColumns)) == null) {
          cKey = new CompactCompositeRegionKey((OffHeapRegionEntry)null, this.tableInfo);
          regionKey = cKey;
        }
        val = getPrimaryKeyBytesAndValue(row, rf, cKey, primaryKeyColumns);
      } else if (isObjectStore()) {
        Map.Entry<RegionKey, Object> entry = this.encoder.fromRow(row, this);
        regionKey = entry.getKey();
        val = entry.getValue();
      }
      else {
        // clone the row since this row will get reused by derby
        // !!!:ezoerner:20080324 this will no longer be an issue when we start
        // storing in serialized form
        final DataValueDescriptor[] clone = row.clone();
        primaryKeyColumns = GemFireXDUtils
            .getPrimaryKeyColumns(getTableDescriptor());
        for (int i = 0; i < row.length; i++) {
          clone[i] = row[i].getClone();
          // also invoke setRegionContext() here that will turn DVD into
          // its final shape to avoid any state changes during reads that
          // have potential race conditions
          clone[i].setRegionContext(this.region);
        }
        if ((regionKey = getGeneratedKey(primaryKeyColumns)) == null) {
          regionKey = getPrimaryKeyDVDs(primaryKeyColumns, clone);
        }
        val = clone;
      }

      final Object routingObj = insert(regionKey, val, tx, lcc,
          false /* is cache loaded*/, isPutDML);
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      if (observer != null && routingObj != null) {
        observer.afterSingleRowInsert(routingObj);
      }
      if (lcc != null) {
        lcc.setContextObject(routingObj);
      }
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: inserted row [" + RowUtil.toString(row)
                + "] in container: " + toString());
      }
      return regionKey;
    }
    else {
      final MemInsertOperation op = new MemInsertOperation(this, row, null,
          null, false /* is cache loaded*/);
      tran.logAndDo(op);
      return op.getRegionKey();
    }
  }

  // For bulk fk checks
  /**
   * Result of lookup of a given fk key
   */
  public static final class BulkKeyLookupResult extends GfxdDataSerializable {
    public Object gfKey;
    public boolean exists;

    public BulkKeyLookupResult() {
    }

    public BulkKeyLookupResult(Object key, boolean contains) {
      this.gfKey = key;
      this.exists = contains;
    }

    @Override
    public String toString() {
      String s = "gfKey=" + this.gfKey + ",exists=" + this.exists;
      return s;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      InternalDataSerializer.writeObject(gfKey, out);
      out.writeBoolean(exists);
    }

    @Override
    public void fromData(final DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      this.gfKey = InternalDataSerializer.readObject(in);
      this.exists = in.readBoolean();
    }

    @Override
    public byte getGfxdID() {
      return GfxdSerializable.GFXD_BULK_KEY_LOOKUP_RESULT;
    }
  }

  /**
   * Bulk check foreign key constraints on all parent containers
   *  
   * @param tc
   * @param tx
   * @param er The rows to be inserted for which fk constraints are to 
   * be checked
   * @return list of rows that do not violate FK constraints
   * @throws StandardException
   */
  private ArrayList<ExecRow> bulkCheckForeignKeyContraints(
      GemFireTransaction tc, final TXStateInterface tx, 
      final ExecRow[] er)
      throws StandardException {

    // rows that satisfy FK constraints will be collected in the successList
    ArrayList<ExecRow> successList = null;
    final GfxdIndexManager idxManager = (GfxdIndexManager)this.region
        .getIndexUpdater();
    ForeignKeyInformation[] fkInfo = (ForeignKeyInformation[]) idxManager.getFKS();
    
    if (GemFireXDUtils.TraceConglomUpdate) {
      SanityManager.DEBUG_PRINT( GfxdConstants.TRACE_CONGLOM_UPDATE, 
          "GFC#bulkCheckForeignKeyContraints for region=" + 
      this.region.getFullPath() +" FK info=" + Arrays.toString(fkInfo));
    }

    if (fkInfo == null || fkInfo.length == 0) {
      return null;
    }
    TXManagerImpl txMgr = null;
    TXStateInterface proxy = null;
    try {
      if (tx != null) {
        proxy = tx.getProxy();
        if (proxy != tx) {
          if (GemFireXDUtils.TraceTran) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
                "GfxdIndexManager#checkForeignKeyConstraint: "
                    + " setting proxy transaction: " + proxy
                    + " in current thread replacing " + tx);
          }
          txMgr = tc.getTransactionManager();
          txMgr.masqueradeAs(proxy);
          tc.setActiveTXState(proxy, false);
        }
      }
      ForeignKeyInformation fk;
      ForeignKeyConstraintDescriptor fkcd;
      for (int index = 0; index < fkInfo.length; index++) {
        fk = fkInfo[index];
        fkcd = fk.getFkcd();
        // nothing to be done for replicate region in case of bulk checks
        if (!fk.getRefContainer().getRegion().getDataPolicy().withPartitioning()) {
          continue;
        }
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              "GFC#bulkCheckForeignKeyContraints: bulk checks for " +
              "parent reference container=" + fk.getRefContainer() + 
              "constraint descriptor=" + fkcd);
        }
        // For bulk fk check on the first refcontainer check all rows
        // For subsequent refcontainers send only those rows that satisfied
        // fk constraints for the prior refcontainers
        if (fkcd.getReferencedConstraint().getConstraintType() == 
            DataDictionary.UNIQUE_CONSTRAINT) {
          if (successList == null) {
          // First refcontainer
            successList = bulkCheckFkOnUniqueKeyColumns(fk.getRefContainer(), 
                fk.refIndex(), fkcd, fk.getRefColsPartColsMap(), er, tc, proxy);
          } else {
            // subsequent refcontainers
            ExecRow rowsToBeInserted[] = new ExecRow[successList.size()];
            successList.toArray(rowsToBeInserted);
            successList = bulkCheckFkOnUniqueKeyColumns(fk.getRefContainer(), 
                fk.refIndex(), fkcd, fk.getRefColsPartColsMap(), er, tc, proxy);
          }
        } else {
          if (successList == null) {
            // First refcontainer
            successList = bulkCheckFkOnPrimaryKeyColumns(fk.getRefContainer(),
                fkcd, proxy, tc, er);
          } else {
            // subsequent refcontainers
            ExecRow rowsToBeInserted[] = new ExecRow[successList.size()];
            successList.toArray(rowsToBeInserted);
            successList = bulkCheckFkOnPrimaryKeyColumns(fk.getRefContainer(),
                fkcd, proxy, tc, rowsToBeInserted);
          }
        }
      }
    } finally {
      if (txMgr != null) {
        if (GemFireXDUtils.TraceTran) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TRAN,
              "GfxdIndexManager#checkForeignKeyConstraint:: "
                  + " switching back to: " + tx
                  + " in current thread instead of: " + proxy);
        }
        txMgr.masqueradeAs(tx);
        tc.setActiveTXState(tx, false);
      }
    }
    return successList;
  }
  
  /**
   * For a given array of rows this will do bulk FK constraint checks on a 
   * given parent container for a unique key constraint descriptor.
   * 
   * @param refContainer The GemFireContainer for the table referred by 
   * the foreign key
   * @param refIndex FK index
   * @param fkCD Constraint descriptor for the foreign key
   * @param refColsPartColsMap
   * @param er An array of rows for which FK constraints are to be checked
   * @param tc
   * @param tx
   * @return list of rows that can be successfully inserted without causing FK
   *         violation on the refContainer
   * @throws StandardException
   */
  private ArrayList<ExecRow> bulkCheckFkOnUniqueKeyColumns(
    final GemFireContainer refContainer, GemFireContainer refIndex,
    ForeignKeyConstraintDescriptor fkCD, int[] refColsPartColsMap,
    final ExecRow[] er, GemFireTransaction tc, final TXStateInterface tx)
    throws StandardException {
  final GfxdIndexManager idxManager = (GfxdIndexManager) this.region
      .getIndexUpdater();
  boolean localIndexLookup = false;
  final LocalRegion refRegion = refContainer.getRegion();
  final boolean isOnPR = refRegion.getDataPolicy().withPartitioning();
  
  // a map of foreign keys (key formed of columns that have a FK constraint)
  // to row list. Since multiple rows to be inserted can have same value in 
  // FK columns, the map is for key to list instead of just key to row
  Map<Object, List<ExecRow>> foreignKeysToExecRows = 
      new HashMap<Object, List<ExecRow>>();
  // successList is a list of rows that do not cause FK violations. 
  // currently we throw exception on detecting a FK violation. However
  // this can be used if we decide to insert
  // rows excluding those that cause violations
  ArrayList<ExecRow> successList = new ArrayList<ExecRow>();

  GfxdFunctionMessage<Object> msg = null;
  GfxdPartitionResolver refResolver = null;
  try {
    final ReferencedKeyConstraintDescriptor refCD = fkCD
        .getReferencedConstraint();
    int[] colPositionsInRefTable = refCD.getReferencedColumns();
    int[] colPositionsInThisTable = fkCD.getKeyColumns();
    if (isOnPR) {
      refResolver = (GfxdPartitionResolver) refRegion
          .getPartitionAttributes().getPartitionResolver();
      localIndexLookup = refIndex.isLocalIndex();
    } else {
      localIndexLookup = true;
    }
    DataValueDescriptor[] keys = null;
    DataValueDescriptor dvd = null;
      
    // form the FK column keys
    final int len = colPositionsInThisTable.length;
    if (len > 1) {
      for (int j = 0; j < er.length; j++) {
        boolean breakLoop = false;
        keys = new DataValueDescriptor[len];
        for (int i = 0; i < len && breakLoop != true; i++) {
          dvd = er[j].getColumn(colPositionsInThisTable[i]);
          // skip FK check like other DBs if any column is null (#41168)
          if (dvd == null || dvd.isNull()) {
            breakLoop = true;
            keys = null;
          } else {
            keys[i] = dvd;
          }
        }
        
        // no fk check for nulls #41168
        if (keys == null) {
          successList.add(er[j]);
        } else if (refRegion == this.getRegion())  {
          // case of self referential constraint (#43159)
          // check whether the row being inserted itself satisfies the
          // constraint
          boolean selfSatisfied = true;
          for (int i = 0; i < len; i++) {
            if (!idxManager.compareKeyToBytes(er[j], keys[i], 
                colPositionsInRefTable[i], colPositionsInThisTable[i])) {
              selfSatisfied = false;
              break;
            }
          }
          if (selfSatisfied) {
            successList.add(er[j]);
          } else {
            addEntryToFkToExecRowsMap(foreignKeysToExecRows, keys, er[j]);
          }
        } else {
          addEntryToFkToExecRowsMap(foreignKeysToExecRows, keys, er[j]);
        }
      }
    } else {
      for (int j = 0; j < er.length; j++) {
        dvd = er[j].getColumn(colPositionsInThisTable[0]);
        // skip FK check like other DBs if any column is null (#41168)
        if (dvd == null || dvd.isNull()) {
          successList.add(er[j]);
        } else if (refRegion == this.getRegion())  {
          if (dvd.equals(er[j].getColumn(colPositionsInRefTable[0]))) {
            successList.add(er[j]);
          } else {
             addEntryToFkToExecRowsMap(foreignKeysToExecRows, dvd, er[j]);
          }
        } else {
          addEntryToFkToExecRowsMap(foreignKeysToExecRows, dvd, er[j]);
        }
      }
    }

    Object[] foreignKeys2 = foreignKeysToExecRows.keySet().toArray();
    Object result = null;
    if (foreignKeys2 != null && foreignKeys2.length > 0) {
      if (!isOnPR) {
        assert false : "Bulk fk checks not expected to be done on replicated " +
            "parent region";
      return null;
      } else if (localIndexLookup) {
        Object[] routingObjects = new Object[foreignKeys2.length];
        if (len == 1) {
          for (int i = 0; i < foreignKeys2.length; i++) {
            routingObjects[i] = refResolver
                .getRoutingKeyForColumn((DataValueDescriptor) foreignKeys2[i]);
          }
        } else if (refColsPartColsMap == null) {
          for (int i = 0; i < foreignKeys2.length; i++) {
            routingObjects[i] = refResolver
                .getRoutingObjectsForPartitioningColumns((DataValueDescriptor[]) foreignKeys2[i]);
          }
        } else {
          for (int i = 0; i < foreignKeys2.length; i++) {
            // need to create a separate key for getting routing object.
            // form a routing object from the partitioning columns that are
            // subset of (parent's) unique key 
            final int partitioningColsCount = refResolver
                .getPartitioningColumnsCount();
            keys = (DataValueDescriptor[]) foreignKeys2[i];
            DataValueDescriptor[] rkeys = 
                new DataValueDescriptor[partitioningColsCount];
            for (int j = 0; j < partitioningColsCount; j++) {
              rkeys[j] = keys[refColsPartColsMap[j]];
            }
            routingObjects[i] = refResolver
                .getRoutingObjectsForPartitioningColumns(rkeys);
          }
        }
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              "GFC#bulkCheckFkOnUniqueKeyColumns: sending " +
                  "ContainsUniqueKeyBulkExecutorMessage for local index look up" +
                  " parent reference region=" + refRegion ); 
        }
        msg = new ContainsUniqueKeyBulkExecutorMessage(refRegion,
            colPositionsInRefTable, foreignKeys2, 
            routingObjects, tx, tc.getLanguageConnectionContext());
        result = msg.executeFunction();
        assert result instanceof GfxdListResultCollector;
        // process the result, throw exception in case of FK violation
        processResultOfBulkExecutorMessage((GfxdListResultCollector) result, 
            foreignKeysToExecRows, fkCD);
      } else {
        LocalRegion globalIndexRegion = refIndex.getRegion();

        if (globalIndexRegion == null) {
          Assert.fail("unexpected null global index region for unique index "
              + "on columns " + refCD.getColumnDescriptors()
              + " for parent table " + refCD.getTableDescriptor());
        }
        Object[] routingObjects = new Object[foreignKeys2.length];
        Object[] indexKeys = new Object[foreignKeys2.length];
        boolean formCompositeKey = foreignKeys2[0] instanceof DataValueDescriptor[];
        for (int i = 0; i < foreignKeys2.length; i++) {
          if (formCompositeKey) {
            indexKeys[i] = new CompositeRegionKey((DataValueDescriptor[]) foreignKeys2[i]);
            List<ExecRow> l = foreignKeysToExecRows.remove(foreignKeys2[i]);
            foreignKeysToExecRows.put(indexKeys[i], l);
          } else {
            indexKeys[i] = foreignKeys2[i];
          }
          routingObjects[i] = Integer.valueOf(PartitionedRegionHelper
              .getHashKey((PartitionedRegion) globalIndexRegion, indexKeys[i]));
        }
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              "GFC#bulkCheckFkOnUniqueKeyColumns: sending " +
                  "ContainsKeyBulkExecutorMessage for global index look up" +
                  "global index=" + globalIndexRegion ); 
        }
        msg = new ContainsKeyBulkExecutorMessage(globalIndexRegion, 
            indexKeys, routingObjects, tx, tc.getLanguageConnectionContext());
        result = msg.executeFunction();
        assert result instanceof GfxdListResultCollector;
        // process the result, throw exception in case of FK violation
        processResultOfBulkExecutorMessage((GfxdListResultCollector) result, 
            foreignKeysToExecRows, fkCD);
      }
    }
    for (List<ExecRow> l : foreignKeysToExecRows.values()) {
      successList.addAll(l);
    }
    return successList;
    } catch (SQLException sqle) {
      throw Misc.wrapRemoteSQLException(sqle, sqle, null);
    } catch (StandardException se) {
      throw se;
    } catch (RuntimeException re) {
      Throwable cause = idxManager.handleFKException(re);
      throw StandardException.newException(SQLState.LANG_FK_VIOLATION, cause,
          fkCD.getConstraintName(), this.getTableName(), "INSERT");
    }
}
  
  /**
   * For a given array of rows this will do bulk FK constraint checks on a 
   * given parent container for a primary key constraint descriptor.
   * 
   * @param refContainer
   *          The GemFireContainer for the table referred by the foreign key
   * @param fkCd Constraint descriptor for the foreign key
   * @param tx
   * @param tc
   * @param er An array of rows for which fk constraints are to be checked
   * @return list of rows that can be successfully inserted without causing FK
   *         violation on the refContainer
   * @throws StandardException
   */
  private ArrayList<ExecRow> bulkCheckFkOnPrimaryKeyColumns(
      final GemFireContainer refContainer,
      ForeignKeyConstraintDescriptor fkCd, final TXStateInterface tx,
      final GemFireTransaction tc, final ExecRow[] er)
      throws StandardException {
    final GfxdIndexManager idxManager = (GfxdIndexManager) this.region
        .getIndexUpdater();
    LocalRegion refRegion = refContainer.getRegion();
    final PartitionAttributes<?, ?> pattrs = refRegion.getPartitionAttributes();
    final DataPolicy refDP = refRegion.getDataPolicy();
    final int[] refCols = fkCd.getReferencedColumns();
    int[] colPositionsInThisTable = fkCd.getKeyColumns();
    
    // a map of foreign keys (key formed of columns that have a FK constraint)
    // to row list. Since multiple rows to be inserted can have same value in 
    // FK columns, the map is for key to a list of rows
    Map<Object, List<ExecRow>> foreignKeysToExecRows = 
        new HashMap<Object, List<ExecRow>>();
    // successList is a list of rows that do not cause FK violations. 
    // currently we throw exception on detecting a FK violation. However
    // this can be used if we decide to insert
    // rows excluding those that cause violations
    ArrayList<ExecRow> successList = new ArrayList<ExecRow>();
    Object gfKey = null;

    final boolean isOnReplicate = refDP.withReplication();
    assert !isOnReplicate;

    final GfxdPartitionResolver refResolver;
    final boolean requiresGlobalIndex;
    if (pattrs != null) {
      refResolver = (GfxdPartitionResolver) pattrs.getPartitionResolver();
      requiresGlobalIndex = refResolver.requiresGlobalIndex();
    } else {
      refResolver = null;
      requiresGlobalIndex = false;
    }

    final int size = refCols.length;
    if (size == 0) {
      GemFireXDUtils.throwAssert("foreign key constraint should not have "
          + "zero columns: " + fkCd + "; for container: " + this);
    }

    //form keys for FK columns
//    if (requiresGlobalIndex && tx != null) {
    if (requiresGlobalIndex) {
      if (size == 1) {
        for (int i = 0; i < er.length; i++) {
          final DataValueDescriptor key = er[i].getColumn(refCols[0]);
          // no fk check for nulls #41168
          if (key == null || key.isNull()) {
//            gfKey = null;
            successList.add(er[i]);
          } else if (refRegion == this.getRegion()) {
            // case of self referential constraint (#43159)
            // check whether the row being inserted itself satisfies the
            // constraint
            if (key.equals(er[i].getColumn(colPositionsInThisTable[0]))) {
              successList.add(er[i]);
            } else {
              addEntryToFkToExecRowsMap(foreignKeysToExecRows, gfKey, er[i]);
            }
          } else {
            gfKey = key;
            addEntryToFkToExecRowsMap(foreignKeysToExecRows, gfKey, er[i]);
          }
        }
      } else {
        for (int i = 0; i < er.length; i++) {
          DataValueDescriptor[] primaryKey = new DataValueDescriptor[size];
          DataValueDescriptor dvd;
          Boolean breakLoop = false;
          for (int index = 0; index < size && breakLoop != true; index++) {
            dvd = er[i].getColumn(refCols[index]);
            if (dvd == null || dvd.isNull()) {
              primaryKey = null;
              breakLoop = true;
            } else {
              primaryKey[index] = dvd;
            }
          }
          // no fk check for nulls #41168
          if (primaryKey == null) {
//            gfKey = null;
            successList.add(er[i]);
          } else if (refRegion == this.getRegion()) {
            // case of self referential constraint (#43159)
            // check whether the row being inserted itself satisfies the
            // constraint
            boolean selfSatisfied = true;
            for (int k = 0; k < size; k++) {
              if (!idxManager.compareKeyToBytes(er[i], primaryKey[k], 
                  colPositionsInThisTable[k], refCols[k])) {
                selfSatisfied = false;
                break;
              }
            }
            if (selfSatisfied) {
              successList.add(er[i]);
            } else {
              addEntryToFkToExecRowsMap(foreignKeysToExecRows, gfKey, er[i]);
            }
          } else {
            gfKey = new CompositeRegionKey(primaryKey);
            addEntryToFkToExecRowsMap(foreignKeysToExecRows, gfKey, er[i]);
          }
        }
      }
    } else {
      for (int i = 0; i < er.length; i++) {
        gfKey = idxManager.getRefRegionKey(refContainer, refCols, fkCd, er[i],
             this.tableInfo);
        // no fk check for nulls #41168
        if (gfKey == null) {
          successList.add(er[i]);
        } else if (refRegion == this.getRegion()) {
          // case of self referential constraint (#43159)
          // check whether the row being inserted itself satisfies the
          // constraint
          final Object refKey = GemFireXDUtils.convertIntoGemFireRegionKey(er[i],
              this, this.tableInfo, null);
          if (refKey != null && refKey.equals(gfKey)) {
            successList.add(er[i]);
          } else {
            addEntryToFkToExecRowsMap(foreignKeysToExecRows, gfKey, er[i]);
          }
        } else {
          addEntryToFkToExecRowsMap(foreignKeysToExecRows, gfKey, er[i]);
        }
      }
    }
    
    ContainsKeyBulkExecutorMessage bmsg = null;
    Object[] foreignKeys2 = foreignKeysToExecRows.keySet().toArray();
    try {
      if (refResolver != null && foreignKeys2 != null &&
          foreignKeys2.length > 0) {
        Object[] routingObjects = new Object[foreignKeys2.length];
        if (requiresGlobalIndex) {
          for (int k = 0; k < foreignKeys2.length; k++) {
            if (foreignKeys2[k] != null) {
              refRegion = refResolver.getGlobalIndexContainer().getRegion();
              routingObjects[k] = Integer.valueOf(PartitionedRegionHelper
                  .getHashKey((PartitionedRegion) refRegion, foreignKeys2[k]));
            }
          }
        } else {
          for (int k = 0; k < foreignKeys2.length; k++) {
            if (foreignKeys2[k] != null) {
              routingObjects[k] = refResolver.getRoutingObject(foreignKeys2[k],
                  null, refRegion);
            }
          }
        }
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
           "GFC#bulkCheckFkOnPrimaryKeyColumns sending " +
           "ContainsKeyBulkExecutorMessage for " + 
           (requiresGlobalIndex ? "global index:" : "local index on region:")
           + refRegion);
        }
        bmsg = new ContainsKeyBulkExecutorMessage(refRegion, foreignKeys2,
            routingObjects, tx, tc.getLanguageConnectionContext());
        Object result = bmsg.executeFunction();
        assert result instanceof GfxdListResultCollector;
        // process the result, throw exception in case of FK violation
        processResultOfBulkExecutorMessage((GfxdListResultCollector) result,
          foreignKeysToExecRows, fkCd);
      }
      for (List<ExecRow> l : foreignKeysToExecRows.values()) {
        successList.addAll(l);
      }
      return successList;
  } catch (SQLException sqle) {
      throw Misc.wrapRemoteSQLException(sqle, sqle, null);
    } catch (StandardException se) {
      throw se;
    } catch (RuntimeException re) {
      Throwable cause = idxManager.handleFKException(re);
      throw StandardException.newException(
          SQLState.LANG_FK_VIOLATION,
          cause,
          fkCd.getConstraintName(),
          this.getTableName(),
          "INSERT");
    }
  }
  
  /**
   * Adds an entry to foreign keys to exec rows map 
   */
  private void addEntryToFkToExecRowsMap(final Map<Object, 
    List<ExecRow>> foreignKeysToExecRows, final Object key, final ExecRow er) {
    List<ExecRow> l = foreignKeysToExecRows.get(key);
    if (l == null) {
      foreignKeysToExecRows.put(key, l = new ArrayList<ExecRow>());
    }
    l.add(er);
  }

  /**
   * Processes the result returned either by 
   * {@link ContainsKeyBulkExecutorMessage} OR by 
   * {@link ContainsUniqueKeyBulkExecutorMessage}.
   * 
   * Currently throws exception if FK violation detected
   * 
   * @param result Result returned by the bulk executor message
   * @param foreignKeysToExecRows map of foreign keys to exec rows 
   * @param fkCD foreign key constraint descriptor
   * @throws StandardException
   */
  private void processResultOfBulkExecutorMessage(GfxdListResultCollector 
    result, Map<Object, List<ExecRow>> foreignKeysToExecRows, 
    ForeignKeyConstraintDescriptor fkCD) throws StandardException {
    ArrayList<Object> resultList = result.getResult();
    for (Object oneResult : resultList) {
      ListResultCollectorValue l = (ListResultCollectorValue) oneResult;
      BulkKeyLookupResult brs = (BulkKeyLookupResult) l.resultOfSingleExecution;
      if (!brs.exists) {
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              "GFC#processResultOfBulkExecutorMessage found a FK constraint "
                  + "violation for FK key info=" + brs);
        }
        // throw exception in case of FK violation
        ExecRow row = foreignKeysToExecRows.get(brs.gfKey).get(0);
        throw StandardException.newException(
            SQLState.LANG_FK_VIOLATION,
            null,
            fkCD.getConstraintName(),
            this.getTableName(),
            "INSERT",
            row == null ? "(currentRow is null)"
                : com.pivotal.gemfirexd.internal.impl.sql.execute.RowUtil
                    .toString(row, fkCD.getKeyColumns()));
        // [shirishd] To continue processing even in case of FK
        // exceptions uncomment the following and remove the above code
        // to throw exception. Currently this is not done as we do
        // not have a way to tell the user
        // which all rows in batch insert failed
        // foreignKeysToExecRows.remove(brs.gfKey);
      }
    }
  }

  public ArrayList<Object> doBulkFkChecks(final ArrayList<Object> rows,
      final LanguageConnectionContext lcc, final RowFormatter rf,
      final TXStateInterface tx) throws StandardException {
    int numRows = rows.size();
    ArrayList<ExecRow> successList = null;
    GemFireTransaction tc = (GemFireTransaction) lcc.getTransactionExecute();
    final boolean byteArrayStore = isByteArrayStore();
    assert rf != null;
    final ExecRow[] er = new ExecRow[numRows];
    final boolean hasLobs = this.hasLobs && reallyHasLobs(rf);
    if (isOffHeap()) {
      for (int i = 0; i < numRows; i++) {
        Object o = rows.get(i);
        if (byteArrayStore) {
          if (hasLobs) {
            er[i] = new OffHeapCompactExecRowWithLobs(o, rf);
          }
          else {
            er[i] = new OffHeapCompactExecRow(o, rf);
          }
        }
      }
    }
    else {
      for (int i = 0; i < numRows; i++) {
        Object o = rows.get(i);
        if (byteArrayStore) {
          if (hasLobs) {
            er[i] = new CompactExecRowWithLobs((byte[][])o, rf);
          }
          else {
            er[i] = new CompactExecRow((byte[])o, rf);
          }
        }
      }
    }
    // get the rows that do not violate FK constraints
    successList = bulkCheckForeignKeyContraints(tc, tx, er);
    ArrayList<Object> rowList = null;
    if (successList != null) {
      rowList = new ArrayList<Object>();
      for (ExecRow r : successList) {
        if (byteArrayStore) {
          if (hasLobs) {
            rowList.add(((AbstractCompactExecRow)r).getRowByteArrays());
          }
          else {
            rowList.add(((AbstractCompactExecRow)r).getRowBytes());
          }
        }
      }
    }
    return rowList;
  }

  /**
   * Tells whether to skip bulk FK checks. Bulk FK checks are done 
   * when a parent table is a partitioned region.
   * 
   * @return true or false
   */
  public boolean canDoBulkFKChecks() {
    boolean doBulkChecks = false;
    final GfxdIndexManager idxManager = (GfxdIndexManager) this.region
        .getIndexUpdater();
    if (idxManager == null) {
      return false;
    }
    ForeignKeyInformation[] fkInfoArray = (ForeignKeyInformation[]) idxManager
        .getFKS();
    if (fkInfoArray == null || fkInfoArray.length == 0) {
      return false;
    }
    ForeignKeyInformation fk;
    for (int index = 0; index < fkInfoArray.length; index++) {
      fk = fkInfoArray[index];
      // nothing to be done for replicate fk region
      if (fk.getRefContainer().getRegion().getDataPolicy().
          withPartitioning()) {
        doBulkChecks = true;
      }
    }
    return doBulkChecks;
  }
  // End for bulk fk checks

  /**
   * Insert a list of given rows in the container. Each row is assumed to be
   * already in its "raw" form i.e. byte[]/byte[][] for byte array store and
   * DVD[] otherwise. For the former case having single column primary key, it
   * is assumed that key will also be in the list i.e. odd position values will
   * be keys in the list while even ones will be raw values.
   */
  public final void insertMultipleRows(final ArrayList<Object> rows,
      final TXStateInterface tx, final LanguageConnectionContext lcc,
      final boolean markPkBased, boolean isPutDML) throws StandardException {
    final boolean byteArrayStore = isByteArrayStore();
    final RowFormatter rf;
    final int[] primaryKeyColumns;
    final int numRows = rows.size();
    final boolean possibleDuplicate;
    boolean bulkFkChecksEnabled;
    final boolean skipListeners;
    final boolean skipConstraintChecks;
    if (lcc != null) {
      possibleDuplicate = lcc.isPossibleDuplicate();
      skipListeners = lcc.isSkipListeners();
      // turn insert into a PUT if skipConstraintChecks is true
      if ((skipConstraintChecks = lcc.isSkipConstraintChecks())) {
        isPutDML = true;
      }
      bulkFkChecksEnabled = !skipConstraintChecks && lcc.bulkFkChecksEnabled();
    }
    else {
      possibleDuplicate = false;
      skipListeners = false;
      skipConstraintChecks = false;
      bulkFkChecksEnabled = false;
    }
    if (byteArrayStore) {
      rf = this.tableInfo.getRowFormatter();
      primaryKeyColumns = this.tableInfo.getPrimaryKeyColumns();
    }
    else {
      rf = null;
      primaryKeyColumns = GemFireXDUtils
          .getPrimaryKeyColumns(getTableDescriptor());
    }
    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      observer.insertMultipleRowsBeingInvoked(numRows);
      observer.invokeCacheCloseAtMultipleInsert();
    }
    if (GemFireXDUtils.TraceConglomUpdate | GemFireXDUtils.TraceQuery && false) {
      final StringBuilder insertString = new StringBuilder(
          "GemFireContainer: inserting multiple rows [");
      for (int i = 0; i < rows.size(); i++) {
        insertString.append('(').append(newExecRow(null, rows.get(i),
            this.tableInfo, false)).append(") ");
      }
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          insertString.append("] [").append(tx).append("] in container: ")
              .append(toString()).toString());
    }
    if (this.region.getDataPolicy() == DataPolicy.EMPTY
        && !this.region.getScope().isLocal()) {
      if (((DistributedRegion)this.region).noInitializedReplicate()) {
        throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
            "insert of rows into replicated table " + this.qualifiedName);
      }
    }
    
    // start bulk fk check
    ArrayList<Object> rowList = null;
    final InternalDistributedSystem dsys = Misc.getDistributedSystem();
    if (bulkFkChecksEnabled && byteArrayStore && !dsys.isLoner()
        && canDoBulkFKChecks()) {
      // get the rows the that can be successfully inserted after 
      // checking for FK constraint violations
      rowList = doBulkFkChecks(rows, lcc, rf, tx);
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
          "GFC#insertMultipleRows: number of rows that can be iserted " +
          "successfully after bulk fk checks=" + (rowList != null ? 
          rowList.size() :  "0"));
      }
    } else {
      // no bulk FK checks process the input rows
      bulkFkChecksEnabled = false;
      rowList = rows;
    }
    // end for bulk fk check
    final boolean noLobs = rf == null || !rf.hasLobs();
    Map<Object, Object> toBeInsertedRows = new LinkedHashMap<>();
    Object[] callbackArgs = null;
    int j = 0;
    try {
//      for (Object val : rows) {
      if (rowList != null) {
        for (Object val : rowList) {
          Object regionKey;
          if (byteArrayStore) {
            if (primaryKeyColumns == null) {
              regionKey = getGeneratedKey();
            }
            else {
              regionKey = new CompactCompositeRegionKey(
                  noLobs ? (byte[])val : ((byte[][])val)[0], this.tableInfo);
            }
            final Object routingObject = GemFireXDUtils
                .getRoutingObjectFromGlobalIndex(this.region, regionKey, val);
            // Listeners should be skipped only when
            // there are no ParallelGatewaySender and SerialGatewaySender
            // is DMLStr enabled.
            // marking skipListener as false.
            // this is because parallel has to receive this event
            // and serial with dml-str can filter it anyway as it 
            // is non-pk
            final Object sca = getCallbackArgument(routingObject, lcc, tx, false,
                false, markPkBased, skipListeners, bulkFkChecksEnabled,
                skipConstraintChecks);
            if (sca != null) {
              if (callbackArgs == null) {
                callbackArgs = new Object[numRows];
              }
              callbackArgs[j++] = sca;
            }
          }
          else {
            // clone the row since this row will get reused by derby
            // !!!:ezoerner:20080324 this will no longer be an issue when we start
            // storing in serialized form
            // [sumedh] row has already been cloned for putAll case
            final DataValueDescriptor[] row = (DataValueDescriptor[])val;
            if (isObjectStore()) {
              Map.Entry<RegionKey, Object> entry = this.encoder.fromRow(row, this);
              regionKey = entry.getKey();
              val = entry.getValue();
            } else {
              for (int i = 0; i < row.length; i++) {
                // also invoke setRegionContext() here that will turn DVD into
                // its final shape to avoid any state changes during reads that
                // have potential race conditions
                row[i].setRegionContext(this.region);
              }
              if ((regionKey = getGeneratedKey(primaryKeyColumns)) == null) {
                regionKey = getPrimaryKeyDVDs(primaryKeyColumns, row);
              }
              val = row;
            }
            final Object routingObject = GemFireXDUtils
                .getRoutingObjectFromGlobalIndex(this.region, regionKey, val);
            // marking skipListener as false.
            // this is because parallel has to receive this event
            // and serial with dml-str can filter it anyway as it 
            // is non-pk
            final Object sca = getCallbackArgument(routingObject, lcc, tx, false,
                false, markPkBased, skipListeners, bulkFkChecksEnabled,
                skipConstraintChecks);
            if (sca != null) {
              if (callbackArgs == null) {
                callbackArgs = new Object[numRows];
              }
              callbackArgs[j++] = sca;
            }
          }
          final Object oldObj = toBeInsertedRows.put(regionKey, val);
          if (oldObj != null && !isPutDML) {
            throw new EntryExistsException(regionKey.toString(), oldObj);
          }
        }
      }
      if (observer != null) {
        observer.putAllCalledWithMapSize(toBeInsertedRows.size());
      }

      DistributedPutAllOperation putAllOp = null;
      if (isPutDML) {
        putAllOp = this.region.newPutAllForPUTDmlOperation(toBeInsertedRows);
      }
      else {
        putAllOp = this.region.newPutAllOperation(toBeInsertedRows);
      }         

      if (putAllOp != null) {
        if (possibleDuplicate) {
          putAllOp.getBaseEvent().setPossibleDuplicate(possibleDuplicate);
        }
        if (callbackArgs != null) {
          putAllOp.setCallbackArgs(callbackArgs);
        }
        this.region.basicPutAll(toBeInsertedRows, putAllOp, null);
        if (observer != null && observer.throwPutAllPartialException()) {
          PutAllPartialResult fakeResult = new PutAllPartialResult(
              toBeInsertedRows.size());
          fakeResult.saveFailedKey(toBeInsertedRows.keySet().iterator().next(),
              new PartitionedRegionStorageException(LocalizedStrings
                  .PRHARRedundancyProvider_UNABLE_TO_FIND_ANY_MEMBERS_TO_HOST_A_BUCKET_IN_THE_PARTITIONED_REGION_0
                  .toLocalizedString(PRHARedundancyProvider
                      .INSUFFICIENT_STORES_MSG, PRHARedundancyProvider.PRLOG_PREFIX
                          + this.region.getFullPath())));
          PutAllPartialResultException ppre = new PutAllPartialResultException(
              fakeResult);
          throw ppre;
        }
      }
    } catch (GemFireException gfeex) {
      // EntryExistsException can be top-level or wrapped inside
      // TransactionException, for example
      EntryExistsException eee = null;
      Throwable t = gfeex;
      while (t != null) {
        if (t instanceof EntryExistsException) {
          eee = (EntryExistsException)t;
          break;
        }
        t = t.getCause();
      }
      if (eee != null) {
        // ignore for posDup
        boolean isGlobalIndex = isGlobalIndex();
        if (!isGlobalIndex && possibleDuplicate) {
          return;
        }
        // convert to standard Derby exception for duplicate key
        final String constraintType;
        if (isGlobalIndex) {
          constraintType = "unique constraint";
        }
        else {
          constraintType = "primary key constraint";
        }
        // put only a limited set of key,values in message
        String valueMessage = null;
        if (toBeInsertedRows != null) {
          if (toBeInsertedRows.size() > 5) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            int num = 0;
            for (Map.Entry<Object, Object> e : toBeInsertedRows.entrySet()) {
              sb.append(e.getKey()).append('=').append(e.getValue())
                  .append(", ");
              if (++num >= 5) {
                sb.append("...]");
                break;
              }
            }
            valueMessage = sb.toString();
          }
          else {
            valueMessage = toBeInsertedRows.toString();
          }
        }
        throw GemFireXDUtils.newDuplicateKeyViolation(constraintType,
            this.qualifiedName, valueMessage, eee);
      }
      throw Misc.processGemFireException(gfeex, gfeex, "insert of keys "
          + toBeInsertedRows.keySet() + " into table " + this.qualifiedName,
          true);
    } catch (RuntimeException e) {
      throw processRuntimeException(e, "insert of keys "
          + toBeInsertedRows.keySet() + " into table " + this.qualifiedName);
    }
  }

  /**
   * Inserts either the given key/value into the table. Also sets the routing
   * object, if any, used by the PartitionRegion machinery in the
   * {@link LanguageConnectionContext#getContextObject()} when the
   * "needsRoutingObject" parameter is set to true.
   */
  public void insert(Object key, Object value, boolean needsRoutingObject,
      GemFireTransaction tran, final TXStateInterface tx,
      LanguageConnectionContext lcc, boolean isCacheLoaded, boolean isPutDML, boolean wasPutDML)
      throws StandardException {
    if (tran == null || !tran.needLogging()) {
      if (needsRoutingObject) {
        Object routingObj = insert(key, value, tx, lcc, isCacheLoaded, isPutDML);
        if (lcc != null) {
          /*
          if (!lcc.isInsertEnabled()) {
            return;
          }
          */
          lcc.setContextObject(routingObj);
        }
      }
      else {
        insert(key, value, null, tx, lcc, isCacheLoaded, isPutDML, wasPutDML);
      }
    }
    else {
      MemInsertOperation op = new MemInsertOperation(this, null, key, value,
          isCacheLoaded);
      tran.logAndDo(op);
    }
  }

  public final long newUUIDForRegionKey() {
    // no need to check for overflow here; insert will throw a constraint
    // violation if there is really a duplicate
    final long uuid = this.region.newUUID(false);
    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer == null) {
      return uuid;
    }
    return observer.overrideUniqueID(uuid, true);
  }

  private Long getGeneratedKey() throws StandardException {
    // generate a new unique long to use as region key
    try {
      return newUUIDForRegionKey();
    } catch (IllegalStateException ise) {
      throw StandardException.newException(SQLState.GENERATED_KEY_OVERFLOW,
          ise, getQualifiedTableName());
    }
  }

  private Long getGeneratedKey(final int[] primaryKeyColumns)
      throws StandardException {
    if (primaryKeyColumns == null) {
      return getGeneratedKey();
    }
    return null;
  }

  private Object getPrimaryKeyBytesAndValue(final DataValueDescriptor[] row,
      final RowFormatter rf, final CompactCompositeRegionKey cKey,
      final int[] primaryKeyColumns) throws StandardException {
    final byte[] valBytes;
    final Object val;
    if (!rf.hasLobs()) {
      valBytes = rf.generateBytes(row);
      if (cKey == null) {
        return valBytes;
      }
      // sending a zero length byte array for other side to
      // understand value is hidden in key portion.
      // disabled for now to avoid serializing row twice via key + value
      // in case like ProcessBatch etc; now CCRK always serializes
      // key bytes only
      //val = ReuseFactory.getZeroLenByteArray();
      val = valBytes;
    }
    else {
      final byte[][] rowByteArrays = rf.generateByteArrays(row);
      if (cKey == null) {
        return rowByteArrays;
      }
      valBytes = rowByteArrays[0];
      // disabled for now to avoid serializing row twice via key + value
      // in case like ProcessBatch etc; now CCRK always serializes
      // key bytes only
      //rowByteArrays[0] = ReuseFactory.getZeroLenByteArray();
      val = rowByteArrays;
    }
    //cKey.valueBytes =  valBytes;
    cKey.setValueBytes(valBytes);
    return val;
  }

  private Object getPrimaryKeyDVDs(final int[] primaryKeyColumns,
      final DataValueDescriptor[] clone) {
    assert clone != null;
    final int numCols = primaryKeyColumns.length;
    if (numCols == 1) {
      // one column primary key.
      final DataValueDescriptor key = clone[primaryKeyColumns[0] - 1];
      // also invoke setRegionContext() here that will turn DVD into
      // its final shape to avoid any state changes during reads that
      // have potential race conditions
      key.setRegionContext(this.region);
      return key;
    }
    final DataValueDescriptor[] primaryKey = new DataValueDescriptor[numCols];
    // clone the row since this row will get reused by derby
    for (int i = 0; i < numCols; i++) {
      primaryKey[i] = clone[primaryKeyColumns[i] - 1];
      // also invoke setRegionContext() here that will turn DVD into
      // its final shape to avoid any state changes during reads that
      // have potential race conditions
      primaryKey[i].setRegionContext(this.region);
    }
    return new CompositeRegionKey(primaryKey);
  }

  /**
   * Insert the given key, value in the container and return the routing object,
   * if any, used by the PartitionedRegion machinery.
   */
  private Object insert(Object key, Object value, final TXStateInterface tx,
      LanguageConnectionContext lcc, boolean isCacheLoaded, boolean isPutDML)
      throws StandardException {
    assert key != null && value != null;
    Object routingObject = GemFireXDUtils.getRoutingObjectFromGlobalIndex(
        this.region, key, value);
    // Insert will always be handled in AsyncDBSynchronizer as onEvent, so we
    // keep the routing object as is
    insert(key, value, routingObject, tx, lcc, isCacheLoaded, isPutDML, false /*wasPutDML*/);
    return routingObject;
  }

  /**
   * Insert the given key, value with routing object (if any) in the container.
   */
  private void insert(Object key, Object value, Object callbackArg,
      final TXStateInterface tx, LanguageConnectionContext lcc,
      boolean isCacheLoaded, boolean isPutDML, boolean wasPutDML)
      throws StandardException {
    final boolean possibleDuplicate;
    final boolean isTrigger;
    final boolean skipListeners;
    final boolean skipConstraintChecks;
    if (lcc != null) {
      possibleDuplicate = lcc.isPossibleDuplicate();
      isTrigger = lcc.isTriggerBody();
      skipListeners = lcc.isSkipListeners();
      // turn insert into a PUT if skipConstraintChecks is true
      if ((skipConstraintChecks = lcc.isSkipConstraintChecks())) {
        isPutDML = true;
      }
    }
    else {
      possibleDuplicate = false;
      isTrigger = false;
      skipListeners = false;
      skipConstraintChecks = false;
    }
    try {
      if (this.region.getDataPolicy() == DataPolicy.EMPTY
          && !this.region.getScope().isLocal()) {
        if (((DistributedRegion)this.region).noInitializedReplicate()) {
          throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
              "insert of key " + key + " into replicated table "
                  + this.qualifiedName);
        }
      }
      // don't use thread local type callback arg for global index updates since
      // it will happen while the parent's event is still executing and will
      // override its callback arg
      Object sca = getCallbackArgument(callbackArg, lcc, tx, !isTrigger,
          isCacheLoaded, true, skipListeners, false, skipConstraintChecks);
      final long startPut = CachePerfStats.getStatTime();
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: inserting key [" + key + "] value [" + value
                + "] [" + tx + "] in container: " + toString());
      }
      // if this is a possible duplicate from client, then convert to a put to
      // enable distribution to WAN queues, secondaries etc.
      //Convert to update only if it is not a Global Index . Ref Bug #47655
      EntryEventImpl event;
      if (possibleDuplicate && !isGlobalIndex()) {
        event = this.region.newUpdateEntryEvent(key, value, sca);
        event.setPossibleDuplicate(true);
        event.setTXState(tx);
        this.region.validatedPut(event, startPut);
        // TODO OFFHEAP: validatedPut calls freeOffHeapResources
      }
      else if (isPutDML) {
        event = this.region.newPutEntryEvent(key, value, sca);        
        event.setTXState(tx);
        this.region.validatedPut(event, startPut);
        // TODO OFFHEAP: validatedPut calls freeOffHeapResources
      }
      else {
        event = this.region.newCreateEntryEvent(key, value, sca);
        event.setTXState(tx);
        this.region.validatedCreate(event, startPut);
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      int bid = event.getBucketId();
      observer.bucketIdcalculated(bid);
    }
        // TODO OFFHEAP: validatedCreate calls freeOffHeapResources
      }
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: successfully inserted key [" + key + "] value ["
                + value + "] in container: " + toString());
      }
    } catch (GemFireException gfeex) {
      // EntryExistsException can be top-level or wrapped inside
      // TransactionException, for example
      EntryExistsException eee = null;
      Throwable t = gfeex;
      while (t != null) {
        if (t instanceof EntryExistsException) {
          eee = (EntryExistsException)t;
          break;
        }
        t = t.getCause();
      }
      if (eee != null) {
        // ignore for posDup
        boolean isGlobalIndex = isGlobalIndex();
        if (!isGlobalIndex && possibleDuplicate) {
          return;
        }
        // convert to standard Derby exception for duplicate key
        final String constraintType;
        if (isGlobalIndex && wasPutDML) {
          constraintType = "Update of partitioning column not supported";
        }
        else if (isGlobalIndex) {
          constraintType = "unique constraint";
        }
        else {
          constraintType = "primary key constraint";
        }
        if (isGlobalIndex && wasPutDML) {
          throw GemFireXDUtils.newPutPartitionColumnViolation(constraintType,
              this.qualifiedName, eee.getLocalizedMessage(), eee.getOldValue(),
              value, eee);
        }
        else {
          throw GemFireXDUtils.newDuplicateKeyViolation(constraintType,
            this.qualifiedName, value, eee);
        }
      }
      throw Misc.processGemFireException(gfeex, gfeex, "insert of key " + key
          + " into table " + this.qualifiedName, true);
    } catch (StandardException ex) {
      if (isCacheLoaded) {
        if (!SQLState.LANG_DUPLICATE_KEY_CONSTRAINT.equals(ex.getSQLState())
            && !SQLState.LANG_FK_VIOLATION.equals(ex.getSQLState())) {
          throw ex;
        }
      }
      else {
        throw ex;
      }
    } catch (RuntimeException e) {
      throw processRuntimeException(e, "insert of key " + key + " into table "
          + this.qualifiedName);
    }
  }

  private Object getCallbackArgument(Object callbackArg,
      LanguageConnectionContext lcc, TXStateInterface tx,
      boolean threadLocalType, boolean isCacheLoaded, boolean isPkBased,
      boolean skipListeners, boolean bulkFkChecksEnabled,
      boolean skipConstraintChecks) {
    if (isApplicationTable()) {
      Object sca = GemFireXDUtils.wrapCallbackArgs(callbackArg, lcc, tx != null,
          threadLocalType, isCacheLoaded, isPkBased, skipListeners,
          bulkFkChecksEnabled, skipConstraintChecks);
      // in case event comes as insert from BulkDML execution as in GFE
      // txns, need to wrap the callbackArg correctly to avoid duplicates
      GatewaySenderEventCallbackArgument gatewayCallbackArg = lcc != null ? lcc
          .getGatewaySenderEventCallbackArg() : null;
      if (gatewayCallbackArg != null) {
        GatewaySenderEventCallbackArgumentImpl newCallbackArg =
          new GatewaySenderEventCallbackArgumentImpl(
            sca, gatewayCallbackArg.getOriginatingDSId(),
            gatewayCallbackArg.getRecipientDSIds(), true);
        sca = newCallbackArg;
      }
      return sca;
    }
    // this can happen for global index updates from within the context
    // of table updates
    /*
    else {
      // assert that gateway callback arg should not be set
      if (lcc != null && lcc.getGatewaySenderEventCallbackArg() != null) {
        SanityManager.THROWASSERT("unexpected gateway sender callbackArg: "
            + lcc.getGatewaySenderEventCallbackArg() + ", for " + this);
      }
    }
    */
    return null;
  }

  /**
   * Put the given key, value with routing object (if any) in the container.
   */
  public void put(Object key, Object value, Object callbackArg)
      throws StandardException {
    try {
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: updating key [" + key + "] with value [" + value
                + "] callbackArg=" + callbackArg + ", in container: "
                + toString());
      }
      this.region.put(key, value, callbackArg);
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: successfully updated key [" + key
                + "] with value [" + value + "] in container: " + toString());
      }
    } catch (GemFireException gfeex) {
      throw Misc.processGemFireException(gfeex, gfeex, "put of key " + key
          + " into table " + this.qualifiedName, true);
    } catch (RuntimeException e) {
      throw processRuntimeException(e, "put of key " + key + " into table "
          + this.qualifiedName);
    }
  }

  public Object pkBasedDelete(Object key, GemFireTransaction tran,
      final TXStateInterface tx, LanguageConnectionContext lcc)
      throws StandardException {
    assert (key instanceof Long || key instanceof RegionKey):
      "key can only be of type long or RegionKey; found: " + key;
    // set the routing object as callback argument else global index
    // lookup can happen here as well as on data store nodes (bug #40144)
    Object routingObject = GemFireXDUtils.getRoutingObjectFromGlobalIndex(
        this.region, key, null);
    if (tran == null || !tran.needLogging()) {
      return deleteFromContainer(key, routingObject, true, tx, lcc, false);
    }
    else {
      MemDeleteOperation op = new MemDeleteOperation(this, key, routingObject,
          true, false);
      tran.logAndDo(op);
      return op.getOldValue();
    }
  }

  public Object delete(Object key, Object callbackArg, boolean isPkBased,
      GemFireTransaction tran, final TXStateInterface tx,
      LanguageConnectionContext lcc, boolean doEvict) throws StandardException {
    if (tran == null || !tran.needLogging()) {
      return deleteFromContainer(key, callbackArg, isPkBased, tx, lcc, doEvict);
    }
    else {
      MemDeleteOperation op = new MemDeleteOperation(this, key, callbackArg,
          false, doEvict);
      tran.logAndDo(op);
      return op.getOldValue();
    }
  }

  private Object deleteFromContainer(Object key, Object callbackArg,
      boolean isPkBased, final TXStateInterface tx,
      LanguageConnectionContext lcc, boolean doEvict) throws StandardException {
    try {
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: deleting key [" + key + "] from container: "
                + toString() + " with callbackArg: " + callbackArg);
      }

      Object oldValue = null;
      final boolean possibleDuplicate;
      final boolean skipListeners;
      final boolean skipConstraintChecks;
      if (lcc != null) {
        possibleDuplicate = lcc.isPossibleDuplicate();
        skipListeners = lcc.isSkipListeners();
        skipConstraintChecks = lcc.isSkipConstraintChecks();
      }
      else {
        possibleDuplicate = false;
        skipListeners = false;
        skipConstraintChecks = false;
      }
      // don't use thread local type callback arg for global index updates since
      // it will happen while the parent's event is still executing and will
      // override its callback arg
      boolean isTrigger = lcc != null && lcc.isTriggerBody();
      final Object sca = getCallbackArgument(callbackArg, lcc, tx, !isTrigger,
          false, isPkBased, skipListeners, false, skipConstraintChecks);

      String strRepOfKeyBeingRemoved = null;
     
      if (GemFireXDUtils.TraceConglomUpdate) {
         strRepOfKeyBeingRemoved = key.toString();
      }
      
      final EntryEventImpl event = this.region.newDestroyEntryEvent(key,
          sca);
      event.setPossibleDuplicate(possibleDuplicate);
      event.setTXState(tx);
        //Increment the use count , if the CCRK contains byte source , so that index maintenance happens correctly
        // In post event we decrement it
       /* if(this.isApplicationTable() && this.isByteArrayStore()) {
          if(CompactCompositeKey.class.isAssignableFrom(key.getClass())) {
            CompactCompositeKey cck = (CompactCompositeKey)key;
             Object vbs = cck.getValueByteSource();
             if(OffHeapByteSource.isOffHeapBytesClass(vbs.getClass())) {
               ((OffHeapByteSource)vbs).use();
               
             }
          }
        }*/
      if (doEvict) {
        if (GemFireXDUtils.TraceConglomUpdate) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              "GemFireContainer: evicting key [" + strRepOfKeyBeingRemoved + "] from container: "
                  + toString() + " with callbackArg: " + callbackArg);
        }
        event.setOperation(Operation.CUSTOM_EVICT_DESTROY);
        event.setCustomEviction(true);
      }
      oldValue = this.region.validatedDestroy(key, event);  
      // TODO OFFHEAP: validatedDestroy calls freeOffHeapResources
      //Asif: With key deleted, the offheap byte source of the region key is no longer valid, so the key is stored in
      //string format before destroy
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: deleted key [" + strRepOfKeyBeingRemoved + "] from container: "
                + toString() + " with callbackArg: " + callbackArg);
      }
      return oldValue;
    } catch (GemFireException gfeex) {
      StandardException se;
      try {
        se = Misc.processGemFireException(gfeex, gfeex, "delete of key " + key
            + " from table " + this.qualifiedName, true);
      } catch (GemFireException ge) {
        throw ge;
      } catch (Exception ignore) {
        // rethrow real one
        throw Misc.processGemFireException(gfeex, gfeex,
            "<none> could not log key name as key bytes from offheap"
                + " could not be obtained as memory was already freed."
                + " table is " + this.qualifiedName, true);
      }
      throw se;
    } catch (RuntimeException e) {
      // In case the problem is with offheap key, like it is already freed, the
      // act of obtaining the key string itself will throw exception and hide
      // the actual exception
      StandardException se;
      try {
        se = processRuntimeException(e, "delete of key " + key + " from table "
            + this.qualifiedName);
      } catch (Exception ignore) {
        throw processRuntimeException(e,
            "<none> could not log key name as key bytes from offheap"
                + " could not be obtained as memory was already freed."
                + " table is " + this.qualifiedName);
      }
      throw se;
    }
  }

  /**
   * Update a row for primary-key based operations.
   * 
   * Don't use for bulk updates: use {@link #replacePartialRowInContainer} for
   * scan based updates.
   */
  public Object replacePartialRow(Object key, FormatableBitSet validColumns,
      DataValueDescriptor[] changedRow, Object callbackArg,
      GemFireTransaction tran, final TXStateInterface tx,
      LanguageConnectionContext lcc, MultipleKeyValueHolder mkvh, boolean flushBatch)
      throws StandardException {
    if (tran == null || !tran.needLogging()) {
      return replacePartialRow(key, callbackArg, true /* isPkBased */,
          validColumns, changedRow, true, tx, lcc, mkvh, flushBatch);
    }
    else {
      MemUpdateOperation op = new MemUpdateOperation(this, changedRow, null,
          key, validColumns, -1, callbackArg);
      tran.logAndDo(op);
      return op.getOldValue();
    }
  }

  public Object replacePartialRow(RegionEntry entry,
      FormatableBitSet validColumns, DataValueDescriptor[] changedRow,
      int bucketId, GemFireTransaction tran, final TXStateInterface tx,
      LanguageConnectionContext lcc) throws StandardException {
    // this statement should be looked again rdubey.
    /*
    if (entry == GemFireStore.INVALID_KEY) {
      throw new IllegalStateException("invalid row id");
    }
    */
    if (tran == null || !tran.needLogging()) {
      return replacePartialRowInContainer(entry, validColumns, changedRow,
          bucketId, tx, lcc);
    }
    else {
      MemUpdateOperation op = new MemUpdateOperation(this, changedRow, entry,
          entry.getKeyCopy(), validColumns, bucketId, null);
      tran.logAndDo(op);
      return op.getOldValue();
    }
  }

  /**
   * Update existing row and return the existing value.
   * 
   * @param key
   *          the region key, either a row id or the primary key
   * @param callbackArg
   *          which can be instance of GfxdCallbackrgument containing routing
   *          key if already available (e.g. in table scan), for the key
   * @param isPkBased
   *          true if this operation is a primary key based update (as opposed
   *          to via a bulk scan)
   * @param validColumns
   *          which columns are valid in changedRow
   * @param changedRow
   *          the new field values
   * @param typeResolution
   *          set to true if the "changedRow" has pristine types and may need
   *          type conversions to match with those in the table
   * @throws StandardException
   *           on Unique Key violation.
   */
  private Object replacePartialRow(Object key, Object callbackArg,
      boolean isPkBased, FormatableBitSet validColumns,
      DataValueDescriptor[] changedRow, boolean typeResolution,
      final TXStateInterface tx, LanguageConnectionContext lcc,
      MultipleKeyValueHolder mkvh, boolean flushBatch) throws StandardException {

    assert (key instanceof Long) || (key instanceof RegionKey): key.getClass()
        .getName();
    try {
      // @yjing: do the type resolution here.
      if (typeResolution && this.tableInfo != null) {
        // it is a user table. It looks awkward to do in this way.
        final RowFormatter rf = this.tableInfo.getRowFormatter();
        if (validColumns != null) { // update partial columns
          for (int column = validColumns.anySetBit(); column != -1;
              column = validColumns.anySetBit(column)) {
            DataTypeDescriptor dtd = rf.getType(column + 1);
            DataValueDescriptor origDVD = changedRow[column];
            if (origDVD != null
                && origDVD.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
              final DataValueDescriptor dvd = dtd.getNull();
              dvd.setValue(origDVD);
              changedRow[column] = dvd;
            }
            if (origDVD != null
                && dtd.getDVDTypeFormatId() == StoredFormatIds.SQL_CHAR_ID
                && origDVD.getString() != null) {
              // See #46933
              ((SQLChar)origDVD).normalize(dtd, origDVD);
            }
          }
        }
        else { // update all columns Is it possible?
          for (int column = 0; column < changedRow.length; ++column) {
            final DataTypeDescriptor dtd = rf.getType(column + 1);
            final DataValueDescriptor origDVD = changedRow[column];
            if (origDVD != null
                && origDVD.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
              final DataValueDescriptor dvd = dtd.getNull();
              dvd.setValue(origDVD);
              changedRow[column] = dvd;
            }
            if (origDVD != null
                && origDVD.getTypeFormatId() == StoredFormatIds.SQL_CHAR_ID
                && origDVD.getString() != null) {
              // See #46933
              ((SQLChar)origDVD).normalize(dtd, origDVD);
            }
          }
        }
      }
      final SerializableDelta delta = new SerializableDelta(changedRow,
          validColumns);
      if (mkvh != null && this.region.getDataPolicy().withPartitioning()) {
        try {
          callbackArg = getRoutingObject(this.region, key, delta);
        } catch (EntryNotFoundException enfe) {
          GemFireXDUtils.checkForInsufficientDataStore(this.region);
          // In case of global index lookup for routing object this exception
          // means that the entry has not been added in the global index so
          // it is not there. So returning null from here is fine.
          if (flushBatch) {
            doPutAllOfAllDeltas(mkvh, lcc);
          }
          return null;
        }
      }
      final boolean possibleDuplicate;
      final boolean isTrigger;
      final boolean skipListeners;
      final boolean skipConstraintChecks;
      if (lcc != null) {
        possibleDuplicate = lcc.isPossibleDuplicate();
        isTrigger = lcc.isTriggerBody();
        skipListeners = lcc.isSkipListeners();
        skipConstraintChecks = lcc.isSkipConstraintChecks();
      }
      else {
        possibleDuplicate = false;
        isTrigger = false;
        skipListeners = false;
        skipConstraintChecks = false;
      }
      // don't use thread local type callback arg for global index updates since
      // it will happen while the parent's event is still executing and will
      // override its callback arg
      final Object sca = getCallbackArgument(callbackArg, lcc, tx, mkvh == null
          && !isTrigger, false, isPkBased, skipListeners, false,
          skipConstraintChecks);
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: updating key {" + key + "] with row ["
                + RowUtil.toString(changedRow) + "] and callbackArg="
                + sca + " [" + tx + "] in container: "
                + toString() + " IS GFE Tx Set ="
                + Misc.getGemFireCache().getTxManager().exists());
      }

      Object oldValue = null;
      String strRepOfKeyBeingUpdated = null;
      if (GemFireXDUtils.TraceConglomUpdate) {
        strRepOfKeyBeingUpdated = key.toString();
      }
      try {
        if (mkvh == null) {
          final long startPut = CachePerfStats.getStatTime();
          final EntryEventImpl event = this.region.newUpdateEntryEvent(key,
              delta, sca);
          event.setPossibleDuplicate(possibleDuplicate);
          event.setTXState(tx);
          oldValue = this.region.validatedPut(event, startPut);
          // TODO OFFHEAP validatedPut calls freeOffHeapResources
        }
        else {
          mkvh.addKeyValueAndCallbackArg(key, delta, sca);
          if (flushBatch) {
            doPutAllOfAllDeltas(mkvh, lcc);
          }
        }
      } catch (PartitionedRegionDistributionException prde) {
        if (prde.getCause() instanceof EntryNotFoundException) {
          throw (EntryNotFoundException)prde.getCause();
        }
        else if (prde.getCause() instanceof TransactionException) {
          throw (TransactionException)prde.getCause();
        }
        else {
          throw prde;
        }
      }

      // Asif: Old value null cannot be used to identify update is successful or
      // not as in case of PR and transactional updates , the old value returned
      // is null
      /*if (oldValue == null) {
        throw new EntryNotFoundException("Update on key=" + key
            + " could not be done, no row exists");
      }*/
     //Asif: With the update happening, the OffHeapByteSource location in CCR key is no longer valid, so 
      //storing the key in string representation before update
      
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "GemFireContainer: updated key [" + strRepOfKeyBeingUpdated + "] with row ["
                + RowUtil.toString(changedRow) + "] in container: "
                + toString());
      }
      return oldValue;
    } catch (GemFireException gfeex) {
      throw Misc.processGemFireException(gfeex, gfeex, "update of key " + key
          + " in table " + this.qualifiedName, true);
    } catch (RuntimeException e) {
      throw processRuntimeException(e, "update of key " + key + " in table "
          + this.qualifiedName);
    }
  }

  private Object getRoutingObject(LocalRegion r, Object key, Object value) {
    InternalPartitionResolver<?, ?> pr = (InternalPartitionResolver<?, ?>)r
        .getPartitionAttributes().getPartitionResolver();
    return pr.getRoutingObject(key, value, null, r);
  }

  public VersionedObjectList doPutAllOfAllDeltas(MultipleKeyValueHolder mkvh,
      LanguageConnectionContext lcc) throws StandardException {
    LinkedHashMap<Object, Object> toBeInsertedDeltas = null;
    boolean possibleDuplicate = false;
    try {
      assert mkvh != null: "Null MultipleKeyValueHolder object not expected";
      toBeInsertedDeltas = mkvh.getToBeInsertedDeltas();
      if (toBeInsertedDeltas == null) {
        return null;
      }
      final DistributedPutAllOperation putAllOp = this.region
          .newPutAllOperation(toBeInsertedDeltas);
      possibleDuplicate = lcc != null && lcc.isPossibleDuplicate();
      Object[] callbackArgs = mkvh.getCallbackArgs().toArray();
      if (putAllOp != null) {
        if (possibleDuplicate) {
          putAllOp.getBaseEvent().setPossibleDuplicate(possibleDuplicate);
        }
        if (callbackArgs != null) {
          putAllOp.setCallbackArgs(callbackArgs);
        }
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
            .getInstance();
        if (observer != null) {
          observer.putAllCalledWithMapSize(toBeInsertedDeltas.size());
        }
        return this.region.basicPutAll(toBeInsertedDeltas, putAllOp, null);
      }
      return null;
    } catch (PutAllPartialResultException papre) {
      // some rows modified by putAll
      GemFireXDUtils.checkForInsufficientDataStore(this.region);
      return papre.getResult().getSucceededKeysAndVersions();
    } catch (GemFireException gfeex) {
      // EntryExistsException can be top-level or wrapped inside
      // TransactionException, for example
      EntryExistsException eee = null;
      Throwable t = gfeex;
      while (t != null) {
        if (t instanceof EntryExistsException) {
          eee = (EntryExistsException)t;
          break;
        }
        t = t.getCause();
      }
      if (eee != null) {
        // ignore for posDup
        boolean isGlobalIndex = isGlobalIndex();
        if (!isGlobalIndex && possibleDuplicate) {
          return null;
        }
        // convert to standard Derby exception for duplicate key
        final String constraintType;
        if (isGlobalIndex) {
          constraintType = "unique constraint";
        }
        else {
          constraintType = "primary key constraint";
        }
        // put only a limited set of key,values in message
        String valueMessage = null;
        if (toBeInsertedDeltas != null) {
          if (toBeInsertedDeltas.size() > 5) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            int num = 0;
            for (Map.Entry<Object, Object> e : toBeInsertedDeltas.entrySet()) {
              sb.append(e.getKey()).append('=').append(e.getValue())
                  .append(", ");
              if (++num >= 5) {
                sb.append("...]");
                break;
              }
            }
            valueMessage = sb.toString();
          }
          else {
            valueMessage = toBeInsertedDeltas.toString();
          }
        }
        throw GemFireXDUtils.newDuplicateKeyViolation(constraintType,
            this.qualifiedName, valueMessage, eee);
      }
      throw Misc.processGemFireException(gfeex, gfeex, "insert of keys "
          + (toBeInsertedDeltas != null ? toBeInsertedDeltas.keySet()
              : "(null)") + " into table " + this.qualifiedName, true);
    } catch (RuntimeException e) {
      throw processRuntimeException(e, "insert of keys "
          + (toBeInsertedDeltas != null ? toBeInsertedDeltas.keySet()
              : "(null)") + " into table " + this.qualifiedName);
    }
  }
  /**
   * Update existing row and return the current object in the region for bulk
   * updates via DML scan executions.
   * 
   * Don't use for primary key based updates: use {@link #replacePartialRow(
   *   Object, FormatableBitSet, DataValueDescriptor[], Object,
   *   GemFireTransaction, TXStateInterface, LanguageConnectionContext,
   *   MultipleKeyValueHolder, boolean)} for that case.
   * 
   * @param entry
   *          the region entry
   * @param validColumns
   *          which columns are valid in changedRow
   * @param changedRow
   *          the new field values
   * @param bucketId
   *          int identifying the bucket ID. This can be -1 if the region is not
   *          a partitioned region or if it is not overflowing to disk
   * @throws StandardException
   *           on Unique Key violation.
   */
  private Object replacePartialRowInContainer(RegionEntry entry,
      FormatableBitSet validColumns, DataValueDescriptor[] changedRow,
      int bucketId, final TXStateInterface tx, LanguageConnectionContext lcc)
      throws StandardException {

    Object key = entry.getKeyCopy();
    LogWriterI18n logger = this.region.getCache().getLoggerI18n();
    Object routingObject = GemFireXDUtils.getRoutingObject(bucketId);
    if (routingObject == null && isPartitioned()) {
      // find the routing object from key and value if possible
      // this will happen in case of table scan (see bug #40072)
      //if (!Token.isRemoved(entry._getValue())) {
      if (!entry.isDestroyedOrRemoved()) {
        routingObject = GemFireXDUtils.getRoutingObjectFromGlobalIndex(key,
            entry, this.region);
      }
      else {
        throw new EntryDestroyedException("Update on key=" + key
            + " could not be done as row is already removed");
      }
    }
    if (logger.fineEnabled()) {
      logger.fine("GemFireContainer::replacePartialRow: Before fetching "
          + "old value for entry=" + entry + " with bucket ID = " + bucketId);
    }
    // for derby code path, type resolution will not be required
    return replacePartialRow(key, routingObject, false /* isPkBased */,
        validColumns, changedRow, false, tx, lcc, null, false);
  }

  private StandardException processRuntimeException(final RuntimeException e,
      final String op) {
    return Misc.processRuntimeException(e, op, getRegion());
  }

  /**
   * This method will return the object which would be stored in the region.
   * Expected types of args are {@link DataValueDescriptor}[], Object[] and
   * java.sql.ResultSet.
   * 
   * @param val
   *          - value to be concerted
   * @return - converted value
   * @throws StandardException
   * @throws SQLException
   */
  public Object makeValueAsPerStorage(Object val) throws StandardException,
      SQLException {
    DataValueDescriptor[] retDVDArray = null;
    final ColumnDescriptorList cdl = this.tableInfo.getTableDescriptor()
        .getColumnDescriptorList();
    final int numOfCols = cdl.size();
    if (val instanceof DataValueDescriptor[]) {
      retDVDArray = (DataValueDescriptor[])val;
      if (retDVDArray.length != numOfCols) {
        throw StandardException.newException(
            SQLState.ROWLOADER_INVALID_COLUMN_ARRAY_LENGTH, retDVDArray.length,
            numOfCols);
      }
    }
    else if (val instanceof Object[]) {
      Object[] tmpval = (Object[])val;
      if (tmpval.length != numOfCols) {
        throw StandardException.newException(
            SQLState.ROWLOADER_INVALID_COLUMN_ARRAY_LENGTH, tmpval.length,
            numOfCols);
      }
      retDVDArray = new DataValueDescriptor[numOfCols];
      Object o;
      for (int index = 0; index < numOfCols; ++index) {
        final DataValueDescriptor dvd = cdl.elementAt(index).columnType
            .getNull();
        o = tmpval[index];
        if (o != null) {
          dvd.setObjectForCast(o, true, o.getClass().getName());
        }
        else {
          dvd.setToNull();
        }
        retDVDArray[index] = dvd;
      }
    }
    else if (val instanceof List<?>) {
      final List<?> lst = (List<?>)val;
      if (lst.size() != numOfCols) {
        throw StandardException.newException(
            SQLState.ROWLOADER_INVALID_COLUMN_ARRAY_LENGTH, lst.size(),
            numOfCols);
      }
      retDVDArray = new DataValueDescriptor[numOfCols];
      Object o;
      for (int index = 0; index < numOfCols; ++index) {
        final DataValueDescriptor dvd = cdl.elementAt(index).columnType
            .getNull();
        o = lst.get(index);
        if (o != null) {
          dvd.setObjectForCast(o, true, o.getClass().getName());
        }
        else {
          dvd.setToNull();
        }
        retDVDArray[index] = dvd;
      }
    }
    else if (val instanceof ResultSet) {
      ResultSet rs = (ResultSet)val;
      retDVDArray = new DataValueDescriptor[numOfCols];
      if (rs != null && rs.next()) {
        if (rs.getMetaData().getColumnCount() != numOfCols) {
          throw StandardException.newException(
              SQLState.ROWLOADER_INVALID_COLUMN_ARRAY_LENGTH, rs.getMetaData()
                  .getColumnCount(), numOfCols);
        }
        for (int index = 0; index < numOfCols; ++index) {
          final DataValueDescriptor dvd = cdl.elementAt(index).columnType
              .getNull();
          Object o = rs.getObject(index + 1);
          if (o != null) {
            dvd.setObjectForCast(o, true, o.getClass().getName());
          }
          else {
            dvd.setToNull();
          }
          retDVDArray[index] = dvd;
        }
        rs.close();
      }
      else {
        return null;
      }
    }
    else if (val == null) {
      return null;
    }
    else {
      throw new IllegalArgumentException(
          "unknown value type passed as argument: " + val);
    }
    if (isByteArrayStore()) {
      return this.tableInfo.getRowFormatter().generateBytes(retDVDArray);
    }
    return retDVDArray;
  }

  /**
   * Estimated number of rows in the local partition of the region. This is
   * incremented from {@link GfxdIndexManager#postEvent} so should give a good
   * estimate but not exact since it is not atomic by design.
   */
  private long previousNumRows = 0;

  private boolean indexTraceOnForThisTable;

  private static final short MAX_COUNTER = 1000;

  private short counter = MAX_COUNTER;

  public final long getNumRows() {

    long rows;
    // #41519; the counter can become negative since it is not volatile and
    // threads can read incorrect value; since this is just an estimate we
    // will live with it instead of adding a volatile memory barrier
    if ((rows = this.previousNumRows) < 0) {

      if (GemFireXDUtils.TraceConglom) {
        // curly braces in the below log line will enable grepLogs to pick it up
        // as suspect strings.
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
            "[warning getting row count -ve, so determining the "
                + "region's local size " + this.region.getName() + " {3} "
                + rows);
      }

      final LocalRegion lr = this.region;
      if (lr == null || !lr.isInitialized() || lr.isDestroyed()) {
        return 0;
      }
      rows = 0;
      if (isPartitioned()) {
        final PartitionedRegion pr = (PartitionedRegion)lr;
        if (pr.getLocalMaxMemory() > 0) {
          boolean currentSettingOfQueryHFDS = pr.includeHDFSResults();
          try {
            pr.setQueryHDFS(false);
            // since only to get local size, so not to call entryCountEstimate()
            rows = pr.entryCount(null, pr.getDataStore().getAllLocalBucketIds());
          } finally {
            pr.setQueryHDFS(currentSettingOfQueryHFDS);
          }
        }
        else {
          //let accessors have some basic view of the table/g.index.
          rows = pr.sizeEstimate();
        }
      }
      else {
        rows = lr.size();
      }
      this.previousNumRows = rows;
    }

    return rows;
  }

  public void updateNumRows(boolean dec) {
    // deliberate non-volatile, thread-unsafe writes and reads
    if (dec) {
      --this.previousNumRows;
    } else {
      ++this.previousNumRows;
    }
    // reset rowSize for re-calculation after many inserts/updates
    if (this.counter-- <= 0) {
      this.counter = MAX_COUNTER;
      this.rowSize = 0;
    }
  }

  /*
  public void updateNumRows() {
    // do not cache the number of rows
    //if(numRows > 0) return numRows;
    if (this.previousNumRows > 1) {
      if (counter++ < MAX_COUNTER) {
        return;
      }
    }
    if (this.region instanceof PartitionedRegion) {
      // !!!:ezoerner:20080827  test method? is this a safe method
      // to call? see PartitionedRegion#localSize()
      long rows = ((PartitionedRegion)this.region).getLocalSize();
      //long diff = (this.previousNumRows - rows);
      //diff = (diff < 0 ? -diff : diff);
      /*soubhik:20090113 if the underlying rows are changing too
      * much between the calls to this function, then refrain it 
      * from caching. Although this is counter productive as 
      * when cpu usage is high we must not increase pressure 
      * with this call. We are not parameterizing it as GF should
      * provide with cheaper calls to getLocalSize() of a region 
      * (i.e. just a Map.size() kind, with constant log(n) time). 
      *
      //cachingEnabled = (diff < 200 ? true : false);
      this.previousNumRows = rows;
    }
    else {
      this.previousNumRows = this.region.size();
    }
    counter = 0;
  }
  */

  @Override
  public String toString() {
    final String type = ((this.baseId != null ? (this.comparator != null
        ? "local-index: " : "global-index: ") + (this.isUniqueIndex
            ? "{UNIQUE} " : "") : "table: "));

    return '[' + type + this.qualifiedName + ", id: " + this.id
        + ", isAppTable=" + this.isApplicationTable() + ']';
  }

  public boolean isLocalIndex() {
    return (this.skipListMap != null);
  }

  public boolean isGlobalIndex() {
    return (this.baseId != null && this.comparator == null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isUniqueIndex() {
    return this.isUniqueIndex;
  }

  public final boolean isGlobalIndexForPrimaryKey() {
    return GemFireXDUtils.isSet(this.containerFlags, FOR_PRIMARY_KEY);
  }

  public final boolean isSYSTABLES() {
    return GemFireXDUtils.isSet(this.containerFlags, FOR_SYSTABLES);
  }

  public final boolean isApplicationTable() {
    return GemFireXDUtils.isSet(this.containerFlags, FOR_APPTABLE);
  }

  public final boolean isApplicationTableOrGlobalIndex() {
    return GemFireXDUtils.isSet(this.containerFlags,
        FOR_APPTABLE_OR_GLOBAL_INDEX);
  }

  @Override
  public Page addPage() throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public Page addPage(int flag) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public void backupContainer(String backupContainerPath)
      throws StandardException {
    throw new AssertionError("unexpected execution");

  }

  @Override
  public void close() {
    throw new AssertionError("unexpected execution");
  }

  public final boolean open(GemFireTransaction tran, int mode)
      throws StandardException {
    if (this.locking != null && !tran.skipLocks(this, null)) {
      boolean waitForLock = ((mode & ContainerHandle.MODE_LOCK_NOWAIT) == 0);

      // The lock only flag corresponds to DDL statement modifying a table
      // or index so take the distributed write lock
      final int modeMask = (ContainerHandle.MODE_FORUPDATE
          | ContainerHandle.MODE_OPEN_FOR_LOCK_ONLY);
      if ((mode & modeMask) == modeMask) {
        return this.locking.lockContainer(tran, this, waitForLock, true);
      }
      else {
        // Open the container in read mode regardless of whether updates are
        // to be done since locking for that shall be handled by GFE layer
        return this.locking.lockContainer(tran, this, waitForLock, false);
      }
    }
    return false;
  }

  /**
   * Close the container at TX end releasing any held locks on the container.
   */
  public final void closeForEndTransaction(GemFireTransaction tran,
      boolean forUpdate) {
    if (this.locking != null && tran != null && !tran.skipLocks()
        // only release the read locks for non-TX case, else those are released
        // at commit/rollback (#43120)
        && !tran.isTransactional()) {
      this.locking.unlockContainer(tran, this);
    }
  }

  @Override
  public void compactRecord(RecordHandle record) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public void compressContainer() throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public void flushContainer() throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  private void addProperty(Properties props, String name, Object value) {
    if (props.get(name) == null) {
      if (value != null) {
        if (value instanceof String) {
          props.setProperty(name, (String)value);
        }
        else {
          props.put(name, value);
        }
      }
    }
  }

  @Override
  public void getContainerProperties(Properties props) throws StandardException {
    addProperty(props, GfxdConstants.GFXD_DTDS, this.tableInfo);
    addProperty(props, GfxdConstants.PROPERTY_SCHEMA_NAME, this.schemaName);
    addProperty(props, GfxdConstants.PROPERTY_TABLE_NAME, this.tableName);
    addProperty(props, GfxdConstants.REGION_ATTRIBUTES_KEY,
        this.regionAttributes);
    addProperty(props, MemIndex.PROPERTY_BASECONGLOMID, this.baseId);
    addProperty(props, MemIndex.PROPERTY_INDEX_COMPARATOR, this.comparator);
    addProperty(props, MemIndex.PROPERTY_DDINDEX, GemFireXDUtils.isSet(
        this.containerFlags, DDINDEX));
    if (this.baseContainer != null) {
      addProperty(props, GfxdConstants.PROPERTY_INDEX_BASE_COL_POS,
          this.baseColumnPositions);
      addProperty(props, GfxdConstants.PROPERTY_INDEX_BASE_TABLE_DESC,
          this.baseContainer.getTableDescriptor());
    }
  }

  @Override
  public long getEstimatedPageCount(int flag) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  /**
   * Returns the number of <em>local</em> rows.
   */
  @Override
  public final long getEstimatedRowCount(int flag) throws StandardException {
    return getNumRows();
  }

  @Override
  public Page getFirstPage() throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public ContainerKey getId() {
    return id;
  }

  @Override
  public ContainerKey getBaseId() {
    return baseId;
  }
  
  public int[] getBaseColumnPositions() {
    return baseColumnPositions;
  }

  public GemFireContainer getBaseContainer() {
    return this.baseContainer;
  }

  @Override
  public GFContainerLocking getLockingPolicy() {
    return this.locking;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this)
      return true;

    if (other instanceof GemFireContainer) {
      GemFireContainer otherContainer = (GemFireContainer)other;
      return (this.id.getContainerId() == otherContainer.id.getContainerId()
          && this.id.getSegmentId() == otherContainer.id.getSegmentId());
    }
    else {
      return false;
    }
  }

  @Override
  public final int hashCode() {
    return this.id.hashCode();
  }

  @Override
  public Page getNextPage(long prevNum) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public Page getPage(long pageNumber) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public Page getPageForCompress(int flag, long pageno)
      throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public Page getPageForInsert(int flag) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public Page getPageNoWait(long pageNumber) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public long getReusableRecordIdSequenceNumber() throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public SpaceInfo getSpaceInfo() throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public Object getUniqueId() {
    return Long.valueOf(getId().getContainerId());
  }

  @Override
  public Page getUserPageNoWait(long pageNumber) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public Page getUserPageWait(long pageNumber) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isTemporaryContainer() {
    return this.id.getSegmentId() == ContainerHandle.TEMPORARY_SEGMENT;
  }

  @Override
  public RecordHandle makeRecordHandle(long pageNumber, int recordId)
      throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public void preAllocate(int numPage) {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public void removePage(Page page) throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  @Override
  public void setEstimatedRowCount(long count, int flag)
      throws StandardException {

  }

  @Override
  public void setLockingPolicy(LockingPolicy newLockingPolicy) {
    throw new AssertionError("unexpected execution");
  }

  /**
   * Get the {@link MemConglomerate} for this container.
   */
  public MemConglomerate getConglomerate() {
    return this.conglom;
  }

  /**
   * Set the {@link MemConglomerate} for this container.
   */
  public void setConglomerate(MemConglomerate conglom) {
    this.conglom = conglom;
    if (isLocalIndex()) {
      this.isUniqueIndex = ((MemIndex)conglom).isUnique();
    }
    else {
      this.isUniqueIndex = isGlobalIndex();
    }
  }

  /**
   * Get a new template row with empty DVDs for DVD[] storage.
   */
  public ExecRow newTemplateRow() {
    return this.templateRow.getNewNullRow();
  }

  /**
   * Get a new template row with given valid columns and having given
   * RowFormatter for byte array store.
   */
  public ExecRow newTemplateRow(final RowFormatter targetFormat,
      final int[] validColumns) throws StandardException {
    if (isByteArrayStore()) {
      assert targetFormat != null;
      return newCompactExecRow(null, targetFormat);
    }
    final int size = validColumns.length;
    final DataValueDescriptor[] row = new DataValueDescriptor[size];
    for (int index = 0; index < size; ++index) {
      row[index] = this.templateRow.getColumn(validColumns[index]).getNewNull();
    }
    return new ValueRow(row);
  }

  public final void initCompactTemplateRow(final ExtraTableInfo tableInfo)
      throws StandardException {
    if (tableInfo != null && isByteArrayStore()) {
      final RowFormatter rf = tableInfo.getRowFormatter();
      final AbstractCompactExecRow cRow = newCompactExecRow(null, rf);
      final int numLobs = rf.numLobs();
      if (numLobs > 0) {
        cRow.setRowArray(new byte[numLobs + 1][], rf);
      }
      this.templateRow = cRow;
    }
  }

  /**
   * Get the number of columns in this table or index.
   */
  public int numColumns() {
    final TableDescriptor td = getTableDescriptor();
    // TableDescriptor is null for SYS tables that use TabInfoImpl
    if (td != null) {
      return td.getNumberOfColumns();
    }
    // TemplateRow for SYS tables has an additional column for RowLocation
    return this.templateRow.nColumns();
  }

  /**
   * Gets the table descriptor for the container.
   * 
   * @return td the table descriptor
   */
  public final TableDescriptor getTableDescriptor() {
    return (this.tableInfo != null ? this.tableInfo.getTableDescriptor() : null);
  }

  /**
   * Can this table contain any data locally?
   */
  public final boolean isDataStore() {
    return this.region != null
        && this.region.getDataPolicy().withStorage()
        && (!this.region.getDataPolicy().withPartitioning() || this.region
            .getPartitionAttributes().getLocalMaxMemory() != 0);
  }

  /**
   * Returns true if byte array row format.
   * 
   * @return true if yte aray row format, false otherwise
   */
  public final boolean isByteArrayStore() {
    return (this.containerFlags & BYTEARRAY_STORE) != 0;
  }

  public final boolean isObjectStore() {
    return this.encoder != null;
  }

  public final RowEncoder getRowEncoder() {
    return this.encoder;
  }

  private final boolean isCandidateForByteArrayStore() {
    return !GfxdConstants.SYSTEM_SCHEMA_NAME.equals(this.schemaName)
        // TODO: SW: why for session schema??
        && !GfxdConstants.SESSION_SCHEMA_NAME.equals(this.schemaName)
        && !isObjectStore();
  }

  /**
   * Returns true if table is partitioned.
   */
  public final boolean isPartitioned() {
    return this.regionAttributes.getDataPolicy().withPartitioning();
  }

  public final boolean isRowBuffer() {
    return isPartitioned() && ((PartitionedRegion)this.region).needsBatching();
  }

  public final boolean isColumnStore() {
    // latter check is not useful currently since only column tables use
    // object store, but still added the check for possible future use
    // (e.g. local index table on column store)
    return isObjectStore() &&
        this.tableName.endsWith(SystemProperties.SHADOW_TABLE_SUFFIX);
  }

  public final boolean isOffHeap() {
    final LocalRegion region = this.region;
    if (region != null) {
      return region.getEnableOffHeapMemory();
    }
    return this.regionAttributes != null
        && this.regionAttributes.getEnableOffHeapMemory();
  }

  /**
   * Returns true if table is of type over flow
   * 
   */
  public final boolean isOverFlowType() {
    return GemFireXDUtils.isSet(this.containerFlags, IS_OVERFLOW);
  }
  
  /**
   * Returns true if any column is defined as 
   * 'generate always' or if table have no primary keys i.e.
   * primary key is auto generated.
   */
  public final boolean hasAutoGeneratedCols() {
    return GemFireXDUtils.isSet(this.containerFlags, HAS_AUTO_GENERATED_COLUMNS);    
  }

  /**
   * Returns true if the underlying region is partitioned and has redundancy.
   * 
   * @return true if region is partitioned and redundant.
   */
  public final boolean isRedundant() {
    if (this.regionAttributes.getDataPolicy().withPartitioning()) {
      return this.regionAttributes.getPartitionAttributes()
          .getRedundantCopies() > 0;
    }
    return false;
  }

  public final boolean hasBucketRowLoc() {
    return GemFireXDUtils.isSet(this.containerFlags, HAS_BUCKET_ROWLOC);
  }

  /**
   * Return true if the Region key is an instanceof {@link KeyWithRegionContext}
   * that requires an invocation of
   * {@link KeyWithRegionContext#setRegionContext(LocalRegion)} from GFE layer
   * after deserialization on remote node before use.
   */
  public final boolean regionKeyRequiresRegionContext() {
    // now we set the keyRequitesRegionContext flag on LocalRegion for all
    // application tables and also global index so DVD#setRegionContext gets
    // invoked before putting in region
    final int[] pkCols;
    return (isByteArrayStore()
        && (pkCols = this.tableInfo.getPrimaryKeyColumns()) != null
        && pkCols.length > 0) || isGlobalIndex();
  }

  private void initTableFlags() {
    setFlag(HAS_BUCKET_ROWLOC, isPartitioned());
    setFlag(FOR_APPTABLE, !this.isLocalIndex()
        && !this.regionAttributes.getScope().isLocal() && !this.isGlobalIndex());
    setFlag(FOR_APPTABLE_OR_GLOBAL_INDEX, !this.isLocalIndex()
        && !this.regionAttributes.getScope().isLocal());
    final EvictionAttributes ea = this.regionAttributes.getEvictionAttributes();
    setFlag(IS_OVERFLOW,
        ea != null && ea.getAction().equals(EvictionAction.OVERFLOW_TO_DISK));
  }

  /**
   * Create the appropriate ExecRow instance for a complete base row given a
   * rawStoreRow. The rawStore can be byte[] byte[][]if coming from
   * GetExecutorSingleKeyMessage
   * 
   * @param rawStoreRow
   *          is a byte[], byte[][], or offheap bytes or DataValueDescriptor[]
   * @return either an AbstractCompactExecRow or a ValueRow
   */
  public final ExecRow newExecRow(@Unretained final Object rawStoreRow) {
    if (rawStoreRow != null) {
      if (isByteArrayStore()) {
        final Class<?> rawStoreRowClass = rawStoreRow.getClass();
        if (rawStoreRowClass == byte[].class) {
          final byte[] rowBytes = (byte[])rawStoreRow;
          return newExecRowFromBytes(rowBytes, getRowFormatter(rowBytes));
        }
        else if (rawStoreRowClass == byte[][].class) {
          final byte[][] rowByteArrays = (byte[][])rawStoreRow;
          return newExecRowFromByteArrays(rowByteArrays,
              getRowFormatter(rowByteArrays));
        }
        else if (rawStoreRowClass == OffHeapRow.class) {
          @Unretained final OffHeapRow rowBytes = (OffHeapRow)rawStoreRow;
          return newExecRowFromByteSource(rowBytes, getRowFormatter(rowBytes));
        }
        else if (rawStoreRowClass == OffHeapRowWithLobs.class) {
          @Unretained final OffHeapRowWithLobs rowByteArrays =
              (OffHeapRowWithLobs)rawStoreRow;
          return newExecRowFromByteSource(rowByteArrays,
              getRowFormatter(rowByteArrays));
        }
        else {
          GemFireXDUtils.throwAssert("Unhandle rawStoreRow type = "
              + rawStoreRowClass);
          return null;
        }
      }
      else {
        assert rawStoreRow instanceof DataValueDescriptor[]:
          "unexpected type of entry value: " + rawStoreRow.getClass().getName();
        return newValueRow((DataValueDescriptor[])rawStoreRow);
      }
    }
    else {
      // can be null for region.get(<non existing key>).
      return null;
    }
  }

  public final ExecRow newExecRow(final Object rawKey,
      @Unretained final Object rawStoreRow) {
    if (isObjectStore()) {
      return this.encoder.toRow(rawKey, rawStoreRow, this);
    } else {
      return newExecRow(rawStoreRow);
    }
  }

  /**
   * Create the appropriate ExecRow instance for a complete base row given a
   * rawStoreRow and the ExtraTableInfo.
   * 
   * @param rawStoreRow
   *          is a byte[], byte[][], or a DataValueDescriptor[]
   * @param tableInfo
   *          the ExtraTableInfo the given rawStoreRow
   * 
   * @return either an AbstractCompactExecRow or a ValueRow
   */
  public final ExecRow newExecRow(final RegionEntry entry,
      final Object rawStoreRow, final ExtraTableInfo tableInfo,
      boolean faultIn) {
    if (rawStoreRow != null) {
      if (isByteArrayStore()) {
        Class<?> rawStoreRowClass = rawStoreRow.getClass();
        if (rawStoreRowClass == byte[].class) {
          final byte[] rowBytes = (byte[])rawStoreRow;
          return newExecRowFromBytes(rowBytes,
              getRowFormatter(rowBytes, tableInfo));
        }
        else if (rawStoreRowClass == byte[][].class) {
          final byte[][] rowByteArrays = (byte[][])rawStoreRow;
          return newExecRowFromByteArrays(rowByteArrays,
              getRowFormatter(rowByteArrays[0], tableInfo));
        }
        else if (OffHeapRegionEntry.class.isAssignableFrom(rawStoreRowClass)) {
          return createExecRowFromAddress((OffHeapRegionEntry)rawStoreRow,
              tableInfo, this.getRegion(), faultIn);
        }
        else {
          throw new AssertionError("Unexpected raw store data . Object is "
              + rawStoreRow);
        }
      } else if (isObjectStore()) {
        return this.encoder.toRow(entry.getRawKey(), rawStoreRow, this);
      }
      else {
        assert rawStoreRow instanceof DataValueDescriptor[]:
          "unexpected type of entry value: " + rawStoreRow.getClass().getName();
        return newValueRow((DataValueDescriptor[])rawStoreRow);
      }
    }
    else {
      // can be null for region.get(<non existing key>).
      return null;
    }
  }

  private static final boolean reallyHasLobs(final RowFormatter rf) {
    if (rf.isTableFormatter()) {
      return true;
    }
    else {
      return rf.hasLobs();
    }
  }

  public final AbstractCompactExecRow newExecRowFromBytes(
      final byte[] rowBytes, final RowFormatter rf) {
    if (!this.hasLobs || !reallyHasLobs(rf)) {
      if (!this.isOffHeap()) {
        return new CompactExecRow(rowBytes, rf);
      }
      else {
        return new OffHeapCompactExecRow(rowBytes, rf);
      }
    }
    else {
      // create a byte[][] on the fly with default values in LOB columns
      final byte[][] rowByteArrays = rf
          .createByteArraysWithDefaultLobs(rowBytes);
      if (!this.isOffHeap()) {
        return new CompactExecRowWithLobs(rowByteArrays, rf);
      }
      else {
        return new OffHeapCompactExecRowWithLobs(rowByteArrays, rf);
      }
    }
  }

  public final AbstractCompactExecRow newExecRowFromBytes(
      final byte[] rowBytes, final RowFormatter rf,
      final DataValueDescriptor[] row, final int rowLen, final boolean doClone) {
    if (!this.hasLobs || !reallyHasLobs(rf)) {
      if (!this.isOffHeap()) {
        return new CompactExecRow(rowBytes, rf, row, rowLen, doClone);
      }
      else {
        return new OffHeapCompactExecRow(rowBytes, rf, row, rowLen, doClone);
      }
    }
    else {
      // create a byte[][] on the fly with default values in LOB columns
      final byte[][] rowByteArrays = rf
          .createByteArraysWithDefaultLobs(rowBytes);
      if (!this.isOffHeap()) {
        return new CompactExecRowWithLobs(rowByteArrays, rf, row, rowLen,
            doClone);
      }
      else {
        return new OffHeapCompactExecRowWithLobs(rowByteArrays, rf, row,
            rowLen, doClone);
      }
    }
  }

  public final AbstractCompactExecRow newExecRowFromByteArrays(
      final byte[][] rowByteArrays, final RowFormatter rf) {
    if (this.hasLobs && reallyHasLobs(rf)) {
      if (!isOffHeap()) {
        return new CompactExecRowWithLobs(rowByteArrays, rf);
      }
      else {
        return new OffHeapCompactExecRowWithLobs(rowByteArrays, rf);
      }
    }
    else {
      if (!isOffHeap()) {
        return new CompactExecRow(rowByteArrays[0], rf);
      }
      else {
        return new OffHeapCompactExecRow(rowByteArrays[0], rf);
      }
    }
  }

  public final AbstractCompactExecRow newExecRowFromByteArrays(
      final byte[][] rowByteArrays, final RowFormatter rf,
      final DataValueDescriptor[] row, final int rowLen, final boolean doClone) {
    if (this.hasLobs && reallyHasLobs(rf)) {
      if (!isOffHeap()) {
        return new CompactExecRowWithLobs(rowByteArrays, rf, row, rowLen,
            doClone);
      }
      else {
        return new OffHeapCompactExecRowWithLobs(rowByteArrays, rf, row,
            rowLen, doClone);
      }
    }
    else {
      if (!isOffHeap()) {
        return new CompactExecRow(rowByteArrays[0], rf, row, rowLen, doClone);
      }
      else {
        return new OffHeapCompactExecRow(rowByteArrays[0], rf, row, rowLen,
            doClone);
      }
    }
  }

  public final AbstractCompactExecRow newExecRowFromByteSource(
      @Unretained final OffHeapRow rowBytes, final RowFormatter rf) {
    if (!this.hasLobs || !reallyHasLobs(rf)) {
      return new OffHeapCompactExecRow(rowBytes, rf);
    }
    else {
      return new OffHeapCompactExecRowWithLobs(rowBytes, rf);
    }
  }

  public final AbstractCompactExecRow newExecRowFromByteSource(
      @Unretained final OffHeapRow rowBytes, final RowFormatter rf,
      final DataValueDescriptor[] row, final int rowLen, final boolean doClone) {
    if (!this.hasLobs || !reallyHasLobs(rf)) {
      return new OffHeapCompactExecRow(rowBytes, rf, row, rowLen, doClone);
    }
    else {
      return new OffHeapCompactExecRowWithLobs(rowBytes, rf, row, rowLen,
          doClone);
    }
  }

  public final AbstractCompactExecRow newExecRowFromByteSource(
      @Unretained final OffHeapRowWithLobs rowByteArrays, final RowFormatter rf) {
    if (this.hasLobs && reallyHasLobs(rf)) {
      return new OffHeapCompactExecRowWithLobs(rowByteArrays, rf);
    }
    else {
      return new OffHeapCompactExecRow(rowByteArrays, rf);
    }
  }

  public final AbstractCompactExecRow newExecRowFromByteSource(
      @Unretained final OffHeapRowWithLobs rowByteArrays,
      final RowFormatter rf, final DataValueDescriptor[] row, final int rowLen,
      final boolean doClone) {
    if (this.hasLobs && reallyHasLobs(rf)) {
      return new OffHeapCompactExecRowWithLobs(rowByteArrays, rf, row, rowLen,
          doClone);
    }
    else {
      return new OffHeapCompactExecRow(rowByteArrays, rf, row, rowLen, doClone);
    }
  }

  public final AbstractCompactExecRow createExecRowFromAddress(
      RegionEntry offHeapEntry, final ExtraTableInfo tableInfo,
      LocalRegion rgn, boolean faultIn) {
    if (this.isPartitioned()) {
      int bucketID = ((RowLocation)offHeapEntry).getBucketID();
      rgn = RegionEntryUtils.getBucketRegion((PartitionedRegion)rgn, bucketID);
    }
    AbstractCompactExecRow row = null;
    Object source = RegionEntryUtils.convertOffHeapEntrytoByteSourceRetain(
        offHeapEntry, rgn, faultIn, false);
    if (source != null) {
      if (source.getClass() == byte[].class) {
        final byte[] vbytes = (byte[])source;
        final RowFormatter rf = getRowFormatter(vbytes, tableInfo);
        if (this.hasLobs && reallyHasLobs(rf)) {
          row = new OffHeapCompactExecRowWithLobs(vbytes, rf);
        }
        else {
          row = new OffHeapCompactExecRow(vbytes, rf);
        }
      }
      else {
        final OffHeapByteSource vbs = (OffHeapByteSource)source;
        final RowFormatter rf = getRowFormatter(vbs, tableInfo);
        if (this.hasLobs && reallyHasLobs(rf)) {
          row = new OffHeapCompactExecRowWithLobs(vbs, rf);
        }
        else {
          row = new OffHeapCompactExecRow(vbs, rf);
        }
      }
    }
    return row;
  }

  /**
   * Create the appropriate {@link AbstractCompactExecRow} instance for a
   * complete base row given a DataValueDescriptor array.
   * 
   * @param dvds
   *          a DataValueDescriptor[]
   * 
   * @return an {@link AbstractCompactExecRow}
   */
  public final AbstractCompactExecRow newCompactExecRow(
      final DataValueDescriptor[] dvds, final RowFormatter rf)
      throws StandardException {
    if (!this.hasLobs || !reallyHasLobs(rf)) {
      if (!isOffHeap()) {
        return new CompactExecRow(dvds, rf);
      }
      else {
        return new OffHeapCompactExecRow(dvds, rf);
      }
    }
    else if (!isOffHeap()) {
      return new CompactExecRowWithLobs(dvds, rf);
    }
    else {
      return new OffHeapCompactExecRowWithLobs(dvds, rf);
    }
  }

  /**
   * Create the appropriate ValueRow instance for a complete base row given a
   * DataValueDescriptor array.
   * 
   * @param dvds
   *          a DataValueDescriptor[]
   * 
   * @return a ValueRow
   */
  public final ValueRow newValueRow(DataValueDescriptor[] dvds) {
    final ValueRow vrow = new ValueRow(dvds.length);
    // for SYSTABLES set the remaining columns from the Region
    if (this.isSYSTABLES()) {
      final DataValueDescriptor schema =
        dvds[SYSTABLESRowFactory.SYSTABLES_SCHEMANAME - 1];
      final DataValueDescriptor table =
        dvds[SYSTABLESRowFactory.SYSTABLES_TABLENAME - 1];
      final String schemaName = (schema != null ? schema.toString() : null);
      final LocalRegion region;
      if (table != null
          && !GfxdConstants.SYSTEM_SCHEMA_NAME.equals(schemaName)
          && (region = (LocalRegion)Misc.getRegionForTableByPath(
              Misc.getFullTableName(schemaName, table.toString(), null),
              false)) != null) {
        String dataPolicy = "NORMAL";
        String pattrsStr = null;
        String resolverStr = null;
        String evictionStr = null;
        final StringBuilder expirationStr = new StringBuilder();
        String diskAttrsStr = null;
        String loaderStr = null;
        String writerStr = null;
        StringBuilder listenersStr = new StringBuilder();
        boolean gatewayEnabled = false;
        SortedSet<String> asyncListeners = null;
        String asyncListenersStr = null;
        SortedSet<String> senderIds = null;
        String senderIdsStr = null;
        dvds = dvds.clone();
        final RegionAttributes<?, ?> attrs = region.getAttributes();
        final PartitionAttributesImpl pattrs = (PartitionAttributesImpl)attrs
            .getPartitionAttributes();
        ExpirationAttributes expirationAttrs;
        final EvictionAttributes evictAttrs = attrs.getEvictionAttributes();
        final CacheLoader<?, ?> loader = attrs.getCacheLoader();
        final CacheWriter<?, ?> writer = attrs.getCacheWriter();
        final CacheListener<?, ?>[] listeners = attrs.getCacheListeners();
        boolean isOverflow = false;
        dataPolicy = attrs.getDataPolicy().toString();
        if (pattrs != null) {
          pattrsStr = pattrs.getStringForGFXD();
          resolverStr = ((InternalPartitionResolver<?, ?>)pattrs
              .getPartitionResolver()).getDDLString();
        }
        expirationAttrs = attrs.getRegionIdleTimeout();
        if (expirationAttrs != null && expirationAttrs.getTimeout() != 0) {
          expirationStr.append("region-idle-timeout: ").append(
              expirationAttrs.toString());
        }
        expirationAttrs = attrs.getRegionTimeToLive();
        if (expirationAttrs != null && expirationAttrs.getTimeout() != 0) {
          expirationStr.append(";region-time-to-live: ").append(
              expirationAttrs.toString());
        }
        expirationAttrs = attrs.getEntryIdleTimeout();
        if (expirationAttrs != null && expirationAttrs.getTimeout() != 0) {
          expirationStr.append(";entry-idle-timeout: ").append(
              expirationAttrs.toString());
        }
        expirationAttrs = attrs.getEntryTimeToLive();
        if (expirationAttrs != null && expirationAttrs.getTimeout() != 0) {
          expirationStr.append(";entry-time-to-live: ").append(
              expirationAttrs.toString());
        }
        if (evictAttrs != null && !evictAttrs.getAction().isNone()) {
          evictionStr = evictAttrs.toString();
          if (evictAttrs.getAction().isOverflowToDisk()) {
            isOverflow = true;
          }
        }
        // check for overflow or persistence for disk attributes and disk dirs
        if (isOverflow || attrs.getDataPolicy().withPersistence()) {
          diskAttrsStr = new StringBuilder("DiskStore is ").append(
              attrs.getDiskStoreName()).append(";").append(
              (attrs.isDiskSynchronous() ? " Synchronous writes to disk"
                  : "Asynchronous writes to disk")).toString();
        }
        if (loader != null) {
          loaderStr = loader.toString();
        }
        if (writer != null) {
          writerStr = writer.toString();
        }
        if (listeners != null && listeners.length > 0) {
          for (CacheListener<?, ?> listener : listeners) {
            if (listenersStr.length() > 0) {
              listenersStr.append(',');
            }
            if (listener instanceof GfxdCacheListener) {
              listenersStr.append(((GfxdCacheListener)listener).getName());
            }
            else {
              listenersStr.append(String.valueOf(listener));
            }
          }
        }
        // TODO: SW: get the actual Gateway sender IDs from system table
        Set<String> regionSenderIds = region.getGatewaySenderIds();
        Set<String> asyncQueueIds = region.getAsyncEventQueueIds();
        if (regionSenderIds != null && regionSenderIds.size() > 0) {
          senderIds = new TreeSet<String>();
          for (String id : regionSenderIds) {
            senderIds.add(id);
          }
          gatewayEnabled = true;
        }
        if (asyncQueueIds != null && asyncQueueIds.size() > 0) {
          asyncListeners = new TreeSet<String>();
          for (String asyncId : asyncQueueIds) {
            asyncListeners.add(asyncId);
          }
          gatewayEnabled = true;
        }
        if (asyncListeners != null) {
          asyncListenersStr = SharedUtils.toCSV(asyncListeners);
        }
        if (senderIds != null) {
          senderIdsStr = SharedUtils.toCSV(senderIds);
        }

        final DataValueDescriptor tType = dvds[SYSTABLESRowFactory.SYSTABLES_TABLETYPE - 1];
        if (tType != null && "T".equalsIgnoreCase(tType.toString()) &&
            !LocalRegion.isMetaTable(region.getFullPath())) {
          ExternalCatalog ec = Misc.getMemStore().getExternalCatalog();
          LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
          if (ec != null && lcc != null && lcc.isQueryRoutingFlagTrue() &&
              Misc.initialDDLReplayDone()) {
            if (ec.isColumnTable(schemaName, table.toString(), true)) {
              dvds[SYSTABLESRowFactory.SYSTABLES_TABLETYPE - 1] = new SQLChar("C");
            }
          }
        }

        dvds[SYSTABLESRowFactory.SYSTABLES_DATAPOLICY - 1] = new SQLVarchar(
            dataPolicy);
        dvds[SYSTABLESRowFactory.SYSTABLES_PARTITIONATTRS - 1] =
          new SQLLongvarchar(pattrsStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_RESOLVER - 1] = new SQLLongvarchar(
            resolverStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_EXPIRATIONATTRS - 1] =
          new SQLLongvarchar(expirationStr.length() > 0
              ? expirationStr.toString() : null);
        dvds[SYSTABLESRowFactory.SYSTABLES_EVICTIONATTRS - 1] =
          new SQLLongvarchar(evictionStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_DISKATTRS - 1] = new SQLLongvarchar(
            diskAttrsStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_LOADER - 1] = new SQLLongvarchar(
            loaderStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_WRITER - 1] = new SQLLongvarchar(
            writerStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_LISTENERS - 1] = new SQLLongvarchar(
            listenersStr.length() == 0 ? null : listenersStr.toString());
        dvds[SYSTABLESRowFactory.SYSTABLES_ASYNCLISTENERS - 1] = new SQLVarchar(
            asyncListenersStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_GATEWAYENABLED - 1] = new SQLBoolean(
            gatewayEnabled);
        dvds[SYSTABLESRowFactory.SYSTABLES_SENDERIDS - 1] = new SQLVarchar(
            senderIdsStr);
        dvds[SYSTABLESRowFactory.SYSTABLES_OFFHEAPENABLED - 1] = new SQLBoolean(
           attrs.getEnableOffHeapMemory());
      }
    }
    vrow.setRowArray(dvds);
    return vrow;
  }

  @Override
  public Object getName() {
    return this.qualifiedName;
  }

  public final Object getLocalIndexKey(ExecRow baseRow, Object entry)
      throws StandardException {
    return this.getLocalIndexKey(baseRow, entry, false, false);
  }

  public final CompactCompositeIndexKey getLocalIndexKey(ExecRow baseRow,
      Object entry, final boolean forInsert, final boolean createExtractingKey)
      throws StandardException {

    Object value = baseRow instanceof AbstractCompactExecRow
        ? ((AbstractCompactExecRow)baseRow).getByteSource()
        : baseRow.getRawRowValue(false);
    return getLocalIndexKey(value, entry, forInsert, createExtractingKey);
  }

  public final CompactCompositeIndexKey getLocalIndexKey(Object value,
      Object entry, final boolean forInsert, final boolean createExtractingKey)
      throws StandardException {

    // a different version of underlying byte[] is now handled seemlessly by
    // the call to ExtraIndexInfo.getRowFormatter(byte[]) that will fetch
    // the appropriate formatter from baseContainer as required
    Object vbs = getLocalIndexKeyValueBytes(value);
    final CompactCompositeIndexKey ccik;

    if (vbs != null) {
      ccik = createExtractingKey ? new ExtractingIndexKey(vbs, this.indexInfo)
          : new CompactCompositeIndexKey(vbs, this.indexInfo);
    }
    else if (value instanceof DataValueDescriptor[]) {
      // DVD[] - When does this case happen?
      // [sumedh] can happen for system tables, but it should not hit this
      return new CompactCompositeIndexKey((DataValueDescriptor[])value,
          this.indexInfo);
    }
    else {
      // Delta - which became null - hopefully that won't make it here
      throw new AssertionError("getIndexKey: Unhandled row " + value);
    }
    // check if value bytes is part of RegionEntry; if that is not the case then
    // snapshot the key bytes and then use that for index key else the key may
    // continue to hold an overflowed value in memory
    // don't snapshot for insert
    if (forInsert) {
      return ccik;
    }
    boolean snapShotKeyBytes = false;
    if (this.baseContainer.isOffHeap() && entry instanceof OffHeapRegionEntry) {
      if (OffHeapRegionEntryHelper
          .isAddressInvalidOrRemoved(((OffHeapRegionEntry)entry).getAddress())) {
        snapShotKeyBytes = true;
      }
    }
    else {
      Object ev = ((RegionEntry)entry)._getValue();
      if (ev == null || Token.isInvalidOrRemoved(ev)) {
        snapShotKeyBytes = true;
      }
    }
    if (snapShotKeyBytes) {
      ccik.snapshotKeyFromValue();
    }
    return ccik;
  }

  /**
   * Get the value bytes that can be used in {@link CompactCompositeIndexKey}
   * from an existing table row value.
   */
  public final Object getLocalIndexKeyValueBytes(Object value) {
    final Class<?> cls = value.getClass();
    if (cls == byte[].class) {
      return value;
    }
    else if (cls == byte[][].class) {
      return ((byte[][])value)[0];
    }
    else if (OffHeapByteSource.isOffHeapBytesClass(cls)) {
      return value;
    }
    else if (cls == DataAsAddress.class) {
      return OffHeapRegionEntryHelper.encodedAddressToObject(
          ((DataAsAddress)value).getEncodedAddress(), false, true);
    }
    return null;
  }

  public final ExtractingIndexKey getExtractingKey(Object rowBytes)
      throws StandardException {

    if (rowBytes != null) {
      // byte[] - we should just wrap the bytes
      return new ExtractingIndexKey(rowBytes, this.indexInfo);
    }
    return null;
  }

  // TODO - Dan - we could make global indexes use a CompactCompositeKey
  // if that will take less memory.
  public final Object getGlobalIndexKey(ExecRow baseRow)
      throws StandardException {

    final int[] baseColumnPositions = this.baseColumnPositions;
    /*
     * Set the columns in the index row that are based on columns in
     * the base row.
     */
    if (baseColumnPositions.length == 1) {
      return baseRow.getColumn(baseColumnPositions[0]);
    }
    else {
      final DataValueDescriptor[] indexKey =
          new DataValueDescriptor[baseColumnPositions.length];
      for (int index = 0; index < baseColumnPositions.length; ++index) {
        indexKey[index] = baseRow.getColumn(baseColumnPositions[index]);
      }
      return new CompositeRegionKey(indexKey);
    }
  }

  /**
   * Lock tracing for this container will be enabled when
   * {@link GfxdConstants#TRACE_LOCK_PREFIX} + {tablename} derby debug flag is
   * set.
   */
  @Override
  public boolean traceThisLock() {
    return SanityManager.TRACE_ON(GfxdConstants.TRACE_LOCK_PREFIX
        + this.qualifiedName)
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_DDLOCK);
  }

  public boolean isPrimaryKeyBased() {
    return this.tableInfo.getPrimaryKeyColumns()  != null; 
  }

  public void unlockRecordAfterRead(Transaction t, ContainerHandle container,
      RecordHandle record, boolean forUpdate, boolean row_qualified) {
    throw new AssertionError("unexpected execution");
  }

  public boolean zeroDurationLockRecordForWrite(Transaction t,
      RecordHandle record, boolean lockForPreviousKey, boolean waitForLock)
      throws StandardException {
    throw new AssertionError("unexpected execution");
  }

  public int getMode() {
    return LockingPolicy.MODE_CONTAINER;
  }
  
  /**
   * This returns memory overhead excluding individual entry sizes. Just additional
   * memory consumed by this data structure. 
   * 
   * @param sizer The sizer object that is to be used for estimating objects.
   * @return
   */
  public long estimateMemoryOverhead(SingleObjectSizer sizer) {
    long size = sizer.sizeof(this); 
    if (isApplicationTableOrGlobalIndex()) {
      size += this.region.estimateMemoryOverhead(sizer);
    }
    else {
      assert isLocalIndex();
      // nothing, use estimateEntryOverhead(...) on ConcurrentSkipList for better
      // break down of local index overhead.
    }
    
    return size;
  }

  /**
   * The {@link Delta} implementation for GemFireXD updates. Encapsulates the
   * changed column positions and values.
   * NOTE: Keep this as final since code depends on .class comparison
   */
  public static final class SerializableDelta extends GfxdDataSerializable
      implements Delta, Sizeable {

    private DataValueDescriptor[] changedRow;

    private FormatableBitSet validColumns;

    private transient int deltaSize = -1;

    private transient VersionTag<?> versionTag;

    private static final byte HAS_FBS = 0x1;
    private static final byte HAS_VERSION_TAG = 0x2;
    private static final byte HAS_PERSISTENT_VERSION_TAG = 0x4;

    /** constructor for deserialization */
    public SerializableDelta() {
    }

    public SerializableDelta(final DataValueDescriptor[] changedRow,
        final FormatableBitSet validColumns) {
      this.changedRow = changedRow;
      this.validColumns = validColumns;
    }

    @Override
    public Object apply(EntryEvent<?, ?> event) {
      return apply(event.getRegion(), event.getKey(),
          ((EntryEventImpl)event).getOldValueAsOffHeapDeserializedOrRaw(),
          event.getTransactionId() == null);
    }

    @Override
    public final Object apply(final Region<?, ?> region, final Object key,
        @Unretained final Object rowObject, boolean prepareForOffHeap) {

      if (rowObject == null || Token.isRemoved(rowObject)) {
        throw new EntryDestroyedException("Update on key=" + key
            + " could not be done as row (" + rowObject
            + ") is already destroyed");
      }

      final GemFireContainer container = (GemFireContainer)region
          .getUserAttribute();
      assert container != null: "SerializableDelta: container not set as "
          + "user attribute for the region '" + region.getFullPath() + "'";

      Object row = null;
      try {
        if (container.isByteArrayStore()) {
          final Class<?> cls = rowObject.getClass();
          final RowFormatter rf;
          if (cls == byte[].class) {
            final byte[] rowBytes = (byte[])rowObject;

            rf = container.getRowFormatter(rowBytes);
            if (!rf.hasLobs()) {
              row = rf.setColumns(this.validColumns, this.changedRow, rowBytes,
                  container.getCurrentRowFormatter());
            }
            else {
              // can happen in case of ALTER TABLE adding LOB columns
              // pass first byte[] separately but null byte[][] so LOBs will be
              // set to default values if required (or else to new value if
              // changed)
              row = rf.setColumns(this.validColumns, this.changedRow,
                  (byte[][])null, rowBytes, container.getCurrentRowFormatter());
            }
          }
          else if(cls == byte[][].class) {
            // must be a byte[][]
            final byte[][] rowBytes = (byte[][])rowObject;
            final byte[] firstBytes = rowBytes[0];
            rf = container.getRowFormatter(firstBytes);
            if (rf.hasLobs()) {
              row = rf.setColumns(this.validColumns, this.changedRow, rowBytes,
                  firstBytes, container.getCurrentRowFormatter());
            }
            else {
              row = rf.setColumns(this.validColumns, this.changedRow,
                  rowBytes[0], container.getCurrentRowFormatter());
            }
          }
          else if (cls == OffHeapRow.class) {
            @Unretained OffHeapRow offheapSource = (OffHeapRow)rowObject;
            rf = container.getRowFormatter(offheapSource);
            if (!rf.hasLobs()) {
              row = rf.setColumns(this.validColumns, this.changedRow,
                  offheapSource, container.getCurrentRowFormatter());
            }
            else {
              // can happen in case of ALTER TABLE adding LOB columns
              // pass byte source for first byte[] separately but null LOB
              // source so LOBs will be set to default values if required (or
              // else to new value if changed)
              row = rf.setColumns(this.validColumns, this.changedRow, null,
                  offheapSource, container.getCurrentRowFormatter(),
                  prepareForOffHeap);
            }
          }
          else if (cls == OffHeapRowWithLobs.class) {
            @Unretained OffHeapRowWithLobs offheapSource =
                (OffHeapRowWithLobs)rowObject;
            rf = container.getRowFormatter(offheapSource);
            if (rf.hasLobs()) {
              row = rf.setColumns(this.validColumns, this.changedRow,
                  offheapSource, null, container.getCurrentRowFormatter(),
                  prepareForOffHeap);
            }
            else {
              row = rf.setColumns(this.validColumns, this.changedRow,
                  (OffHeapByteSource)offheapSource,
                  container.getCurrentRowFormatter());
            }
          }
          else {
            throw new InternalGemFireError(
                "SerializableDelta.applyDelta: unknown value type " + cls
                    + ": " + rowObject);
          }
        }
        else {
          final LocalRegion lr = (LocalRegion)region;
          DataValueDescriptor[] oldValue = (DataValueDescriptor[])rowObject;
          // Rahul : cloning is important otherwise the old value and new value
          // will be same in listener invocation. Fixed for index maintenance.
          DataValueDescriptor[] thisRow = oldValue.clone();
          for (int i = 0; i < this.changedRow.length; i++) {
            if (this.changedRow[i] != null) {
              // need to make sure we replace the base values with clones since
              // the existing base values get recycled when the result sets are
              // closed !!!:ezoerner:20080325
              thisRow[i] = this.changedRow[i].getClone();
              // also invoke setRegionContext() here that will turn DVD into
              // its final shape to avoid any state changes during reads that
              // have potential race conditions
              thisRow[i].setRegionContext(lr);
            }
          }
          row = thisRow;
        }
        if (GemFireXDUtils.TraceConglomUpdate) {
          final StringBuilder sb = new StringBuilder();
          sb.append("Applied delta ");
          ArrayUtils.objectStringNonRecursive(this.changedRow, sb);
          sb.append(" on row ");
          ArrayUtils.objectStringNonRecursive(rowObject, sb);
          sb.append(" to get result ");
          ArrayUtils.objectStringNonRecursive(row, sb);
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
              sb.toString());
        }
      } catch (StandardException se) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "Unexpected failure in Delta apply", se);
      }
      assert row != null: "Row cannot be null after applying delta";
      return row;
    }

    @Override
    public Delta merge(final Region<?, ?> region, final Delta toMerge) {
      assert toMerge instanceof SerializableDelta: "unknown Delta to merge: "
          + toMerge;

      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "SerializableDelta#merge: toMerge [" + toMerge.toString()
                + "] this [" + toString() + ']');
      }

      final SerializableDelta other = (SerializableDelta)toMerge;
      final DataValueDescriptor[] otherRow = other.changedRow;
      final DataValueDescriptor[] thisRow = this.changedRow;

      final SerializableDelta newDelta;
      final DataValueDescriptor[] newRow;
      if (thisRow.length >= otherRow.length) {
        newRow = thisRow.clone();
        newDelta = new SerializableDelta(newRow, new FormatableBitSet(
            this.validColumns));

        final FormatableBitSet otherCols = other.validColumns;
        newDelta.validColumns.or(otherCols);
        for (int pos = otherCols.anySetBit(); pos >= 0; pos = otherCols
            .anySetBit(pos)) {
          newRow[pos] = otherRow[pos];
        }
      }
      else {
        newRow = otherRow.clone();
        newDelta = new SerializableDelta(newRow, new FormatableBitSet(
            other.validColumns));

        final FormatableBitSet thisCols = this.validColumns;
        newDelta.validColumns.or(thisCols);
        for (int pos = thisCols.anySetBit(); pos >= 0; pos = thisCols
            .anySetBit(pos)) {
          if (newRow[pos] == null) {
            newRow[pos] = thisRow[pos];
          }
        }
      }
      if (GemFireXDUtils.TraceConglomUpdate) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_UPDATE,
            "SerializableDelta#merge: after merge: " + newDelta.toString());
      }
      return newDelta;
    }

    /**
     * Return the DVD[] for the columns that have changed while the remaining
     * columns will be null;
     */
    public final DataValueDescriptor[] getChangedRow() {
      return this.changedRow;
    }

    /**
     * Return a {@link FormatableBitSet} for columns that have changed.
     * 
     * @return a {@link FormatableBitSet} of changed column positions.
     */
    public final FormatableBitSet getChangedColumns() {
      return this.validColumns;
    }

    public void setVersionTag(
        @SuppressWarnings("rawtypes") VersionTag versionTag) {
      this.versionTag = versionTag;
    }

    public VersionTag<?> getVersionTag() {
      return this.versionTag;
    }

    @Override
    public String toString() {
      return "SerializableDelta@0x"
          + Integer.toHexString(System.identityHashCode(this))
          + ": changedRow=" + RowUtil.toString(this.changedRow)
          + ", validColumns="
          + (this.validColumns != null ? this.validColumns.toString() : null)
          + (this.versionTag != null ? ", version=" + this.versionTag : "");
    }

    @Override
    public byte getGfxdID() {
      return GfxdSerializable.SERIALIZABLE_DELTA;
    }

    @Override
    public void toData(final DataOutput out) throws IOException {
      super.toData(out);
      // serialize each DataValueDescriptor, but handle null values specially
      final FormatableBitSet fbs = this.validColumns;
      final DataValueDescriptor[] row = this.changedRow;
      byte flags = 0x0;
      // need to serialize VersionTag for offheap
      final VersionTag<?> version = this.versionTag;
      if (version != null) {
        flags |= HAS_VERSION_TAG;
        if (version instanceof DiskVersionTag) {
          flags |= HAS_PERSISTENT_VERSION_TAG;
        }
      }
      if (fbs != null) {
        out.writeByte(HAS_FBS | flags);
        fbs.toData(out);
        InternalDataSerializer.writeArrayLength(row.length, out);
        DataValueDescriptor dvd;
        for (int pos = fbs.anySetBit(); pos >= 0; pos = fbs.anySetBit(pos)) {
          dvd = row[pos];
          if (dvd != null) {
            dvd.toData(out);
          }
          else {
            // DVD can be null in case update sets column to null value
            out.writeByte(DSCODE.NULL);
            out.writeByte(DSCODE.NULL);
          }
        }
      }
      else {
        out.writeByte(flags);
        DataType.writeDVDArray(row, out);
      }
      if (version != null) {
        InternalDataSerializer.invokeToData(version, out);
      }
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      final byte flags = in.readByte();
      if ((flags & HAS_FBS) != 0) {
        final FormatableBitSet fbs = new FormatableBitSet();
        fbs.fromData(in);
        this.validColumns = fbs;
        final int numColumns = InternalDataSerializer.readArrayLength(in);
        this.changedRow = new DataValueDescriptor[numColumns];
        for (int pos = fbs.anySetBit(); pos >= 0; pos = fbs.anySetBit(pos)) {
          this.changedRow[pos] = DataType.readDVD(in);
        }
      }
      else {
        DataType.readDVDArray(in);
      }
      if ((flags & HAS_VERSION_TAG) != 0) {
        this.versionTag = VersionTag.create(
            (flags & HAS_PERSISTENT_VERSION_TAG) != 0, in);
      }
    }

    @Override
    public int getSizeInBytes() {

      //toData/fromData never got called, estimating size.
      if (deltaSize < 0) {
        assert changedRow != null: "SerializableDelta: delta can never be null ";
        int sz = 0;
        DataValueDescriptor dvd;
        for (int index = 0; index < this.changedRow.length; ++index) {
          dvd = this.changedRow[index];
          try {
            sz += dvd != null ? dvd.getLength() : 0;
          } catch (StandardException e) {
            ; // ignore that DVD
          }
        }
        deltaSize = sz;
      }
      return deltaSize;
    }

    /**
     * @see Delta#cloneDelta()
     */
    @Override
    public final SerializableDelta cloneDelta() {
      final DataValueDescriptor[] newRow =
          new DataValueDescriptor[this.changedRow.length];
      int index = 0;
      for (DataValueDescriptor dvd : this.changedRow) {
        newRow[index] = dvd.getClone();
        index++;
      }
      return new SerializableDelta(newRow, this.validColumns.clone());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean allowCreate() {
      return false;
    }
  }

  /**
   * Implements container locking for user tables, indices, schemas to
   * synchronize concurrent DDL and DML executions. This deviates from the
   * contract of {@link LockingPolicy} in that it can return false if no locking
   * was required e.g. because this is a remote node. In case of a real timeout
   * it will throw the appropriate exception instead of returning false.
   * 
   * @author Sumedh Wale
   */
  static final class GFContainerLocking implements LockingPolicy {

    private final GfxdLockable containerLockObject;

    private final GfxdLockable ddLockObject;

    private final boolean isLocal;

    /**
     * cache if the table contains LOB columns; this is updated by
     * {@link #update(GemFireContainer)} when table shape changes; now using
     * DataDictionary locks themselves if the table has LOB columns to avoid
     * lock inversion caused by prepare of LOB procedures during
     * ResultSet.close() and other places which will take DataDictionary lock
     * while the ResultSet is still holding the table lock
     */
    private boolean hasLobs;

    private final boolean traceLock;

    GFContainerLocking(GfxdLockable lockable, boolean local,
        GemFireContainer container) {
      assert lockable != null: "expected non-null "
          + "GfxdLockable for GFContainerLocking constructor";
      this.containerLockObject = lockable;
      this.isLocal = local;
      final GemFireStore memStore = GemFireStore.getBootingInstance();
      final GfxdDataDictionary dd;
      if (memStore != null
          && (dd = memStore.getDatabase().getDataDictionary()) != null) {
        this.ddLockObject = dd.getLockObject();
      }
      else {
        this.ddLockObject = null;
      }
      if (container != null) {
        this.traceLock = container.traceLock();
        this.hasLobs = container.hasLobs;
      }
      else {
        this.traceLock = GemFireXDUtils.TraceLock;
      }
    }

    @Override
    public boolean lockContainer(final Transaction tran,
        final ContainerHandle container, boolean waitForLock, boolean forUpdate)
        throws StandardException {

      if (SanityManager.DEBUG) {
        if (!forUpdate && container == null) {
          SanityManager.THROWASSERT("expected non null container handle "
              + "when acquiring a lock on container");
        }
      }

      if (tran == null) {
        return false;
      }

      GemFireTransaction t = (GemFireTransaction)tran;
      t.setActiveState();

      // use DataDictionary locks for transactional DMLs
      final GfxdLockable lockObject = getLockableForTransaction(t, forUpdate);

      if (this.traceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
            "GFContainerLocking: acquiring " + (this.isLocal ? "local " : "")
                + (forUpdate ? "write" : "read") + " lock on object "
                + lockObject + " [TX " + t + ']',
            GemFireXDUtils.TraceLock ? new Throwable() : null);
      }
      final GfxdLockSet lockSet = t.getLockSpace();
      if (GfxdDataDictionary.SKIP_LOCKS.get()) {
        return true;
      }
      if (lockSet.acquireLock(lockObject,
          waitForLock ? GfxdLockSet.MAX_LOCKWAIT_VAL : 0, forUpdate,
          this.isLocal, forUpdate) != GfxdLockSet.LOCK_FAIL) {
        if (this.traceLock) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
              "GFContainerLocking: successfully acquired "
                  + (this.isLocal ? "local " : "")
                  + (forUpdate ? "write" : "read") + " lock on object "
                  + lockObject + " [TX " + t + "] for lock "
                  + lockObject.getReadWriteLock());
        }
        return true;
      }
      else if (waitForLock) {
        // if waiting for lock failed then throw LOCK_TIMEOUT exception
        throw lockSet.getLockService().getLockTimeoutException(lockObject,
            lockSet.getOwner(), true);
      }
      return false;
    }

    @Override
    public void unlockContainer(final Transaction tran,
        final ContainerHandle container) {
      if (tran == null) {
        return;
      }
      final GemFireTransaction t = (GemFireTransaction)tran;
      // null container indicates write lock release for the container
      final boolean forUpdate = (container == null);

      // use DataDictionary locks for transactional DMLs
      final GfxdLockable lockObject = getLockableForTransaction(t, forUpdate);

      if (this.traceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
            "GFContainerLocking: releasing " + (this.isLocal ? "local " : "")
                + (forUpdate ? "write " : "read ") + " lock on object "
                + lockObject + " [TX " + t + "] for lock "
                + lockObject.getReadWriteLock(),
            GemFireXDUtils.TraceLock ? new Throwable() : null);
      }
      t.getLockSpace().releaseLock(lockObject, forUpdate, this.isLocal);
      if (this.traceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
            "GFContainerLocking: released " + (this.isLocal ? "local " : "")
                + (forUpdate ? "write" : "read") + " lock on object "
                + lockObject + " [TX " + t + "] for lock "
                + lockObject.getReadWriteLock());
      }
    }

    private final GfxdLockable getLockableForTransaction(
        final GemFireTransaction tran, final boolean forUpdate) {
      if (GemFireXDUtils.TraceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
            "GFContainerLocking: getLockableForTransaction "
                + (this.isLocal ? "local " : "")
                + (forUpdate ? "write" : "read") + " lock"
                + (this.ddLockObject == null ? "(ddLockObject null)" : "")
                + " with isTransactional=" + tran.isTransactional()
                + " for transaction: " + tran);
      }
      // use DataDictionary locks for transactional DMLs
      // now also using DataDictionary locks for tables having LOB columns to
      // avoid lock inversion when LOB procedures are prepared during ResultSet
      // iteration/close while holding table locks
      return (!forUpdate && (this.hasLobs || tran.isTransactional())
          && this.ddLockObject != null) ? this.ddLockObject
              : this.containerLockObject;
    }

    final void update(GemFireContainer container) {
      this.hasLobs = container.hasLobs;
    }

    @Override
    public boolean lockRecordForRead(Transaction t, ContainerHandle container,
        RecordHandle record, boolean waitForLock, boolean forUpdate)
        throws StandardException {
      throw new AssertionError("unexpected execution");
    }

    @Override
    public boolean lockRecordForWrite(Transaction t, RecordHandle record,
        boolean lockForInsert, boolean waitForLock) throws StandardException {
      throw new AssertionError("unexpected execution");
    }

    @Override
    public void unlockRecordAfterRead(Transaction t, ContainerHandle container,
        RecordHandle record, boolean forUpdate, boolean row_qualified) {
      throw new AssertionError("unexpected execution");
    }

    @Override
    public boolean zeroDurationLockRecordForWrite(Transaction t,
        RecordHandle record, boolean lockForPreviousKey, boolean waitForLock)
        throws StandardException {
      throw new AssertionError("unexpected execution");
    }

    @Override
    public int getMode() {
      return LockingPolicy.MODE_CONTAINER;
    }
  }

  public int getIndexSize() {
    if (this.isLocalIndex()) {
      int sz = 0;
      ConcurrentSkipListMap<Object, Object> slm = this.skipListMap;
      if (slm == null || slm.isEmpty()) {
        sz = 0;
      }
      else {
        final Iterator<Object> itr = slm.values().iterator();
        while (itr.hasNext()) {
          final Object value = itr.next();

          if (value != null) {
            final Class<?> valueCls = value.getClass();

            if (valueCls == RowLocation[].class) {
              sz += ((RowLocation[])value).length;
            }
            else if (valueCls == ConcurrentTHashSet.class) {
              sz += ((ConcurrentTHashSet<?>)value).size();
            }
            else {
              assert value instanceof RowLocation: "unexpected type in index "
                  + valueCls.getName() + ": " + value;
              sz++;
            }
          }
        }
      }
      return sz;
    }
    throw new GemFireXDRuntimeException("getIndexSize " +
        "should be called only for local index but is being called for: " + this);
  }

  public void setIndexTrace(boolean indexTraceOn) {
    this.indexTraceOnForThisTable = indexTraceOn;
  }
  
  public boolean isIndexTraceOn() {
    return this.indexTraceOnForThisTable;
  }

  public void setGlobalIndexCaching() throws StandardException {
    GemFireStore store = Misc.getMemStore();
    boolean cacheGlobalIndexes = CACHE_GLOBAL_INDEX
        && store.isDataDictionaryPersistent() && store.isPersistIndexes();
    if (cacheGlobalIndexes) {
      if (!CACHE_GLOBAL_INDEX_IN_MAP) {
        try {
          globalIndexMap = GlobalIndexCacheWithLocalRegion.createInstance(qualifiedName);
        } catch (Exception e) {
          throw StandardException.newException(SQLState.JAVA_EXCEPTION, e);
        }
      }
      else {
        globalIndexMap = new GlobalIndexCacheWithInMemoryMap();
      }
    }
    else {
      globalIndexMap = null;
    }
  }

  public void updateCache(Serializable globalIndexKey, Object robj) {
    if (this.globalIndexMap != null) {
      this.globalIndexMap.put(globalIndexKey, robj);
    }
  }

  private StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();

  final ObjectSizer sizer = ObjectSizer.getInstance(false);

  // This field is to be used only for initial index setup. If index is big we compute
  // the cost step-wise and acquire memory accoringly
  long intialAccounting = 0;

  private AtomicLong sizeAccountedByIndex = new AtomicLong(0L);
  // Cuurrent overhead which is stamped in the region
  private int currenrOverhead = 0;
  // Total number of rows in Index
  private long totalRows = 0;

  private AtomicLong numOperations = new AtomicLong(0L);

  private boolean doAccounting() {
    if (!callback.isSnappyStore()) {
      return false;
    }
    LocalRegion baseRegion = getBaseRegion();
    if (baseRegion != null && !baseRegion.reservedTable()) {
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void accountMemoryForIndex(long cursorPos, boolean forceAccount) {
    if ((cursorPos >= 8
            && (cursorPos & (cursorPos - 1)) == 0) // Power of 2
            || forceAccount) {
      accountIndexMemory(true, false);
    }
  }

  public void resetInitialAccounting(){
    intialAccounting = 0;
  }

  public void accountIndexMemory(boolean askMemoryManager, boolean isDestroy) {
    if (!doAccounting()) {
      return;
    }
    LocalRegion baseRegion = getBaseRegion();
    final String baseTableContainerName = baseRegion.getFullPath();
    List<GemFireContainer> indexes = new ArrayList<>();
    indexes.add(this);
    final LinkedHashMap<String, Object[]> retEstimates = new LinkedHashMap<>();
    long sum = 0L;
    long totalOverhead = 0L;
    try {
      sizer.estimateIndexEntryValueSizes(baseTableContainerName, indexes, retEstimates, null);
      Misc.getCacheLogWriter().info("Computing initial index overhead of region " +
          "" + baseTableContainerName + " for index " + this.getName()) ;
      for (Map.Entry<String, Object[]> e : retEstimates.entrySet()) {
        long[] value = (long[]) e.getValue()[0];
        sum += value[0]; //constantOverhead
        sum += value[1]; //entryOverhead[0] + /entryOverhead[1]
        sum += value[2]; //keySize
        sum += value[3]; //valueSize
        long rowCount = value[5];
        totalOverhead += (rowCount == 0 ? 0 : Math.round((sum - intialAccounting) / rowCount));
        if (askMemoryManager) {
          // Only acquire memory while initial index creation. Rest all index accounting will be done by
          // region put/delete
          Misc.getCacheLogWriter().info("Total overhead computed = "+ sum + " intialAccounting = "+intialAccounting);
          baseRegion.acquirePoolMemory(0, sum - intialAccounting,
              false, null, false);
          intialAccounting = sum;
          sizeAccountedByIndex.set(sum);
        }
        totalRows = rowCount;
      }

      if (!isDestroy) {
        adjustAccountedMemory(totalOverhead);
        currenrOverhead = (int) totalOverhead;
      }
    } catch (StandardException | IllegalAccessException e) {
      throw new GemFireXDRuntimeException(e);
    } catch (InterruptedException ie) {
      Misc.checkIfCacheClosing(ie);
      Thread.currentThread().interrupt();
    }
  }

  // Release the current size of index from memory manager.
  private void releaseIndexMemory() {
    LocalRegion baseRegion = getBaseRegion();
    if (doAccounting()) {
      long memoryToBeFreed = currenrOverhead * totalRows;
      // Free all memory by index + (index overhead * row count)
      getBaseRegion().freePoolMemory(memoryToBeFreed + sizeAccountedByIndex.get(), false);
      if (!baseRegion.isDestroyed) {
        baseRegion.setIndexOverhead(-1 * currenrOverhead);
        // mark (index overhead * row count) as ignore bytes in local region , which will be ignored by
        // dropping the table
        baseRegion.incIgnoreBytes(memoryToBeFreed);
      }
    }
  }

  public void accountSnapshotEntry(int numBytes) {
    LocalRegion baseRegion = getBaseRegion();
    if (doAccounting()) {
      getBaseRegion().acquirePoolMemory(0, numBytes,
          false, null, false);
      sizeAccountedByIndex.addAndGet(numBytes);
    }
  }

  public void adjustAccountedMemory(long newValue) {
    LocalRegion baseRegion = getBaseRegion();
    if (doAccounting()) {
      long adjustedMemory = newValue - currenrOverhead;
      baseRegion.setIndexOverhead((int)adjustedMemory);
    }
  }

  public void runEstimation() {
    if (doAccounting()) {
      long numOps = numOperations.incrementAndGet();
      if (numOps >= 8
              && (numOps & (numOps - 1)) == 0) { // Power of 2
        // accounting in sync call. One of the operation will be slow.
        // But it will lead to more resiliency. for around 5 million rows in index
        // it takes around 500 ms.
        // @TODO will see if cost is very high will make it a async call.
        accountIndexMemory(false, false);
      }
    }
  }


}
