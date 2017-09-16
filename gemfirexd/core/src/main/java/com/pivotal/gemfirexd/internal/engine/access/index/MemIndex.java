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

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerateController;
import com.pivotal.gemfirexd.internal.engine.access.MemScanController;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.io.Storable;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.DynamicCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowLocationRetRowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.StoreCostController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.TransactionManager;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.store.access.conglomerate.ConglomerateUtil;

/**
 * @author yjing
 *
 */
public abstract class MemIndex extends DataType implements MemConglomerate {

  /** unused now */
  private static final long serialVersionUID = 1L;

  public static final String LOCAL_HASH_INDEX="Hash1Index";
  public static final String LOCAL_SORTED_MAP_INDEX="SortedMap2Index";
  public static final String GLOBAL_HASH_INDEX="GlobalHashIndex";

  /**the strings for accessing the index properties */
  public static final String PROPERTY_BASECONGLOMID = "baseConglomerateId";

  /** comparator property for skipList */
  public static final String PROPERTY_INDEX_COMPARATOR = "indexComparator";

  public static final String PROPERTY_EXTRA_INDEX_INFO = "extraIndexInfo";

  public static final String PROPERTY_ROWLOCCOLUMN = "rowLocationColumn";

  public static final String PROPERTY_NUNIQUECOLUMNS = "nUniqueColumns";

  public static final String PROPERTY_NKEYFIELDS = "nKeyFields";

  public static final String PROPERTY_UNIQUEWITHDUPLICATENULLS =
    "uniqueWithDuplicateNulls";

  /** this property is set to true for index on a DataDictionary table */
  public static final String PROPERTY_DDINDEX = "ddIndex";

  /**the id of the Container */
  protected ContainerKey id;

  /** the container for this index */
  protected GemFireContainer container;

  /** The format id's of each column in the secondary index. */
  protected int[] format_ids;

  /** the container of the base table */
  protected GemFireContainer baseContainer;

  /** the transaction during conglomerate creation */
  protected transient Transaction tran;

  /** the sort information for each column */
  protected boolean[] ascDescInfo; // not yet used

  /** the collation information for each column */
  protected int[] collation_ids;

  //protected int indexType;

  protected int keyColumns;

  protected int rowLocationColumn;

  protected boolean unique;

  protected boolean uniqueWithDuplicateNulls;
  
  protected long estimatedRowsize;

  private boolean ddIndex;

  /**
   * Create an index conglomerate.
   * <p>
   * Create a heap conglomerate. This method is called from the index factory to
   * create a new instance of an index.
   * <p>
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  public final void create(GemFireTransaction tran, int segmentId,
      long containerId, DataValueDescriptor[] template,
      ColumnOrdering[] columnOrder, int[] collationIds, Properties properties,
      int tmpFlag) throws StandardException {
    this.tran = tran;

    // Make sure the container was actually created.
    if (containerId < 0) {
      throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
    }

    // Keep track of what segment the container's in.
    this.id = ContainerKey.valueOf(segmentId, containerId);

    // Heap requires a template representing every column in the table.
    if ((template == null) || (template.length == 0)) {
      throw StandardException
          .newException(SQLState.HEAP_COULD_NOT_CREATE_CONGLOMERATE);
    }

    // get format id's from each column in template and store it in the
    // conglomerate state.
    this.format_ids = ConglomerateUtil.createFormatIds(template);

    this.collation_ids = ConglomerateUtil.createCollationIds(
        this.format_ids.length, collationIds);

    String property_value = null;
    property_value = properties.getProperty(PROPERTY_BASECONGLOMID);

    if (property_value == null) {
      throw (StandardException.newException(SQLState.BTREE_PROPERTY_NOT_FOUND,
          PROPERTY_BASECONGLOMID));
    }

    ContainerKey baseConglomerateId = ContainerKey.valueOf(
        ContainerHandle.TABLE_SEGMENT, Long.parseLong(property_value));
    this.baseContainer = Misc.getMemStore().getContainer(baseConglomerateId);

    this.ascDescInfo = new boolean[template.length]; // not yet used

    for (int i = 0; i < ascDescInfo.length; i++) {

      if ((columnOrder != null) && (i < columnOrder.length)) {
        ascDescInfo[i] = columnOrder[i].getIsAscending();
      }
      else {
        ascDescInfo[i] = true; // default values - ascending order
      }
    }

    property_value = properties.getProperty(PROPERTY_NUNIQUECOLUMNS);

    if (property_value == null) {
      throw (StandardException.newException(SQLState.BTREE_PROPERTY_NOT_FOUND,
          PROPERTY_NUNIQUECOLUMNS));
    }

    final int uniqueColumns = Integer.parseInt(property_value);

    property_value = properties.getProperty(PROPERTY_UNIQUEWITHDUPLICATENULLS);
    if (property_value == null) {
      this.uniqueWithDuplicateNulls = false;
    }
    else {
      this.uniqueWithDuplicateNulls = Boolean.parseBoolean(property_value);
    }

    property_value = properties.getProperty(PROPERTY_NKEYFIELDS);

    if (property_value == null) {
      throw (StandardException.newException(SQLState.BTREE_PROPERTY_NOT_FOUND,
          PROPERTY_NKEYFIELDS));
    }

    final int indexColumns = Integer.parseInt(property_value);

    // if number of indexColumns is same as number of uniqueColumns, then
    // this is not a unique index else it is
    this.unique = this.uniqueWithDuplicateNulls
        || (uniqueColumns != indexColumns);

    this.keyColumns = indexColumns - 1;

    // Get rowLocationColumn //
    property_value = properties.getProperty(PROPERTY_ROWLOCCOLUMN);

    if (SanityManager.DEBUG) {
      if (property_value == null)
        SanityManager.THROWASSERT(PROPERTY_ROWLOCCOLUMN
            + "property not passed to index create()");
    }

    if (property_value == null) {
      throw (StandardException.newException(SQLState.BTREE_PROPERTY_NOT_FOUND,
          PROPERTY_BASECONGLOMID));
    }
    this.rowLocationColumn = Integer.parseInt(property_value);

    property_value = properties.getProperty(PROPERTY_DDINDEX);
    if (property_value != null) {
      this.ddIndex = Boolean.parseBoolean(property_value);
    }
    else {
      this.ddIndex = false;
    }

    // set the template row for GemFireContainer
    properties.put(GfxdConstants.PROP_TEMPLATE_ROW, template);

    // estimate the per row memory.
    long rowmemory = 0L;
    for (DataValueDescriptor dvd : template) {
      if (dvd instanceof RowLocation) {
        continue;
      }
      rowmemory += dvd.estimateMemoryUsage();
    }
    estimatedRowsize = rowmemory;

    // allocate memory for this index
    allocateMemory(properties, tmpFlag);
    this.tran = null;
  }

  /**
   * this method is used to allocate the memory in the store.
   * <b>Note</b>Every subclass must override this method.
   * @throws StandardException 
   */
  protected abstract void allocateMemory(Properties properties, int tmpFlag)
      throws StandardException;

  public boolean requiresContainer() {
    return true;
  }

  public abstract String getIndexTypeName();

  public boolean caseSensitive() {
    return true;
  }

  public void addColumn(TransactionManager tran, int column_id,
      Storable template_column, int collation_id) throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  public void compressConglomerate(TransactionManager tran, Transaction xact)
      throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  public MemScanController defragmentConglomerate(TransactionManager tran,
      Transaction xact, boolean hold, int open_mode, int lock_level,
      LockingPolicy locking_policy, int isolation_level)
      throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  public void drop(TransactionManager tran) throws StandardException {
    tran.getRawStoreXact().dropContainer(this.id);
  }

  public boolean fetchMaxOnBTree(TransactionManager tran, Transaction xact,
      long conglomId, int open_mode, int lock_level,
      LockingPolicy locking_policy, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] fetchRow)
      throws StandardException {
    return false;
  }

  public final long getContainerid() {
    return this.id.getContainerId();
  }

  public final boolean isUnique() {
    return this.unique;
  }

  /**
   * Return the GemFireContainer for the base table. Convenience method.
   */
  public final GemFireContainer getBaseContainer() {
    return this.baseContainer;
  }

  /**
   * @see MemConglomerate#getGemFireContainer()
   */
  public final GemFireContainer getGemFireContainer() {
    return this.container;
  }

  /**
   * @see MemConglomerate#setGemFireContainer(GemFireContainer)
   */
  public final void setGemFireContainer(GemFireContainer container) {
    this.container = container;
  }

  public DynamicCompiledOpenConglomInfo getDynamicCompiledConglomInfo()
      throws StandardException {
    // no longer used by GemFireXD; instead have the template row in
    // OpenMemIndex itself
    return null;
  }

  public final ContainerKey getId() {
    return this.id;
  }

  public StaticCompiledOpenConglomInfo getStaticCompiledConglomInfo(
      TransactionController tc, long conglomId) throws StandardException {
    return this;
  }

  public boolean isTemporary() {
    return false;
  }

  public long load(TransactionManager tran, boolean createConglom,
      RowLocationRetRowSource rowSource) throws StandardException {
    long num_rows_loaded = 0;
    final MemIndexController conglomerateController = newMemIndexController();
    try {
      num_rows_loaded = conglomerateController.load((GemFireTransaction)tran,
          this, createConglom, rowSource);
    } finally {
      conglomerateController.close();
    }
    return num_rows_loaded;
  }

  public final MemConglomerateController open(TransactionManager tran,
      Transaction xact, boolean hold, int openMode, int lockLevel,
      LockingPolicy locking, StaticCompiledOpenConglomInfo staticInfo,
      DynamicCompiledOpenConglomInfo dynamicInfo) throws StandardException {
    final MemIndexController cc = newMemIndexController();
    cc.init((GemFireTransaction)tran, this, openMode, lockLevel, locking);
    return cc;
  }

  public final MemConglomerateController open(GemFireTransaction tran,
      int openMode, int lockLevel, LockingPolicy locking)
      throws StandardException {
    final MemIndexController cc = newMemIndexController();
    cc.init(tran, this, openMode, lockLevel, locking);
    return cc;
  }

  public final boolean openContainer(GemFireTransaction tran, int openMode,
      int lockLevel, LockingPolicy locking) throws StandardException {
    if (this.container != null) {
      return this.container.open(tran, openMode);
    }
    return false;
  }

  protected MemIndexController newMemIndexController() {
    return new MemIndexController();
  }

  public final MemScanController openScan(TransactionManager tran,
      Transaction xact, boolean hold, int openMode, int lockLevel,
      LockingPolicy locking_policy, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      StaticCompiledOpenConglomInfo staticInfo,
      DynamicCompiledOpenConglomInfo dynamicInfo) throws StandardException {
    return openScan(tran, xact, hold, openMode, lockLevel, locking_policy,
        isolation_level, scanColumnList, startKeyValue, startSearchOperator,
        qualifier, stopKeyValue, stopSearchOperator, staticInfo, dynamicInfo,
        null /* local data set */);
  }

  public final MemScanController openScan(TransactionManager tran,
      Transaction xact, boolean hold, int openMode, int lockLevel,
      LockingPolicy locking, int isolationLevel,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier[][] qualifier,
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      StaticCompiledOpenConglomInfo staticInfo,
      DynamicCompiledOpenConglomInfo dynamicInfo, Activation act)
      throws StandardException {
    final MemIndexScanController scanController = newMemIndexScanController();
    scanController.init((GemFireTransaction)tran, this, openMode, lockLevel,
        locking, scanColumnList, startKeyValue, startSearchOperator, qualifier,
        stopKeyValue, stopSearchOperator, act);
    return scanController;
  }

  abstract protected MemIndexScanController newMemIndexScanController();

  public final StoreCostController openStoreCost(TransactionManager tran,
      Transaction xact) throws StandardException {
    MemIndexCostController indexCost = newMemIndexCostController();
    indexCost.init((GemFireTransaction)tran, this, false,
        ContainerHandle.MODE_READONLY, TransactionController.MODE_TABLE);
    return indexCost;
  }

  abstract protected MemIndexCostController newMemIndexCostController();

  public void purgeConglomerate(TransactionManager tran, Transaction xact)
      throws StandardException {
    throw StandardException.newException(SQLState.BTREE_UNIMPLEMENTED_FEATURE);
  }

  public final LocalRegion getUnderlyingRegion() {
    return this.container.getRegion();
  }

  public final Serializable getGlobalHashIndexKey(Serializable keyValue)
      throws StandardException {
    assert keyValue != null;
    if (keyValue instanceof DataValueDescriptor[]) {
      if (((DataValueDescriptor[])keyValue).length == 1) {
        return ((DataValueDescriptor[])keyValue)[0];
      }
      return new CompositeRegionKey((DataValueDescriptor[])keyValue);
    }
    else {
      return keyValue;
    }
  }

  public final int getKeySize(Object row) throws StandardException {
    int size = 0;
    if(row instanceof CompactCompositeIndexKey) {
      return ((CompactCompositeIndexKey) row).getLength();
    }
    if (row instanceof DataValueDescriptor[]) {
      DataValueDescriptor[] dvdArr = (DataValueDescriptor[])row;
      for (int index = 0; index < dvdArr.length - 1; ++index) {
        size += dvdArr[index].getLength();
      }
    }
    else {
      size += ((DataValueDescriptor)row).getLength();
    }
    return size;
  }

  public void restoreToNull() {
       this.id= null;
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int getTypeFormatId() {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int estimateMemoryUsage() {
    return 0;
  }

  @Override
  public final int writeBytes(byte[] outBytes, int offset,
      DataTypeDescriptor dtd) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public int readBytes(byte[] inBytes, int offset, int columnWidth) {
    throw new UnsupportedOperationException("Not expected to be invoked for "
        + getClass());
  }

  @Override
  public int readBytes(long memOffset, int columnWidth,
      ByteSource bs) {
    throw new UnsupportedOperationException("Not expected to be invoked for "
        + getClass());
  }

  @Override
  public int computeHashCode(int maxWidth, int hash) {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public final void toDataForOptimizedResultHolder(DataOutput dos)
      throws IOException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public final void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  @Override
  public String toString() {
    return getClass().getName() + ": " + this.id + " on base container: "
        + this.baseContainer + " caseSensitive = " + caseSensitive();
  }

  public DataValueDescriptor getConglom() {
    return (this);
  }

  public boolean isNull() {
    return this.id == null;
  }

  public int compare(DataValueDescriptor other) throws StandardException {
    return 0;
  }

  public DataValueDescriptor getClone() {
    return null;
  }

  public int getLength() throws StandardException {
    return 0;
  }

  public int getHeight() throws StandardException {
    return 1;
  }

  public DataValueDescriptor getNewNull() {
    return null;
  }

  public String getString() throws StandardException {
    return null;
  }

  public String getTypeName() {
    return null;
  }

  public void readExternalFromArray(ArrayInputStream ais) throws IOException,
      ClassNotFoundException {
    throw new UnsupportedOperationException("unexpected invocation");
  }

  public void setValueFromResultSet(ResultSet resultSet, int colNumber,
      boolean isNullable) throws StandardException, SQLException {
  }

  public boolean isDDIndex() {
    return this.ddIndex;
  }

  public abstract void dumpIndex(String marker);
}
