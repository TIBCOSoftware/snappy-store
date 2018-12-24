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

package com.pivotal.gemfirexd.internal.engine.sql.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.catalog.DependableFinder;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.Dependent;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.Provider;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.*;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import org.eclipse.collections.api.block.procedure.primitive.IntProcedure;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

public final class ExtraTableInfo extends ExtraInfo implements Dependent {

  private final UUID tableID;

  private final UUID oid;

  final DataDictionary dd;

  private TableDescriptor td;

  GemFireContainer container;

  private RowFormatter formatter;

  private boolean isLatestSchema;

  private volatile String[] pkColumnNames;

  private final int schemaVersion;

  private RowFormatter refKeyFormatter;

  private int[] referencedKeyColumns;

  private int[] referencedKeyFixedColumns;

  private int[] referencedKeyVarColumns;

  private GemFireContainer[] refContainers;

  private HashMap<int[], long[]> refKeyColumns2IndexNumbers;

  private IntObjectHashMap<ColumnDescriptor> autoGenColumns;

  private HashMap<String, ColumnDescriptor> autoGenColumnNames;

  /**
   * Containers that have foreign key constraint to this table, if any.
   */
  private GemFireContainer[] fkContainers;

  private ExtraTableInfo(UUID oid, UUID tableID, DataDictionary dd,
      int schemaVersion) {
    this.oid = oid;
    this.tableID = tableID;
    this.dd = dd;
    this.schemaVersion = schemaVersion;
  }

  public final TableDescriptor getTableDescriptor() {
    return this.td;
  }

  /** initialize table meta-data structure for regular application tables */
  public static ExtraTableInfo newExtraTableInfo(DataDictionary dd,
      TableDescriptor td, ContextManager cm, int schemaVersion,
      GemFireContainer gfc, boolean setAsCurrent) throws StandardException {
    final ExtraTableInfo tableInfo = new ExtraTableInfo(dd.getUUIDFactory()
        .createUUID(), td.getUUID(), dd, schemaVersion);
    dd.getDependencyManager().addDependency(tableInfo, td, cm);
    if (setAsCurrent) {
      if (gfc == null) {
        gfc = Misc.getMemStore().getContainer(
            ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT,
                td.getHeapConglomerateId()));
      }
      gfc.setExtraTableInfo(tableInfo);
    }
    tableInfo.container = gfc;
    return tableInfo;
  }

  /** invoke when dropping the table to clear out dependencies */
  public void drop(final GemFireTransaction tran) throws StandardException {
    // unregister from dependecy manager
    dd.getDependencyManager().clearDependencies(
        tran.getLanguageConnectionContext(), this, tran);
  }

  private final void refreshRowFormatter(final GemFireContainer container,
      final TableDescriptor td) throws StandardException {
    this.container = container;
    this.formatter = new RowFormatter(td.getColumnDescriptorList(),
        container.getSchemaName(), container.getTableName(),
        this.schemaVersion, container, true);
    container.initCompactTemplateRow(this);
    // also update any information cached by the locking subsystem
    container.refreshLockingInfo();
    container.schemaUpdated();
  }

  private final void refreshPrimaryKeyFormatter(
      final GemFireContainer container, final TableDescriptor td) {
    final int[] pkColumns = getPrimaryKeyColumns();
    if (pkColumns != null && pkColumns.length > 0
        && container.isByteArrayStore()) {
      setPrimaryKeyFormatter(container, td, pkColumns);
    }
    else {
      this.pkFormatter = null;
      this.primaryKeyFixedColumns = null;
      this.primaryKeyVarColumns = null;
    }
  }

  /**
   * Get the {@link ExtraTableInfo} as per the byte[] provided.
   */
  public final ExtraTableInfo getExtraTableInfo(final byte[] vbytes) {
    // if this is for the latest schema, then vbytes must have the same schema
    // else we may need to read vbytes to get the schema it represents
    if (this.isLatestSchema) {
      assert getSchemaVersionFromValueBytes(vbytes) == formatter.schemaVersion:
        "mismatch of versions: formatter=" + formatter.schemaVersion
            + ", fromBytes=" + getSchemaVersionFromValueBytes(vbytes);

      return this;
    }
    else {
      return this.container.getExtraTableInfo(vbytes);
    }
  }

  /**
   * Get the {@link RowFormatter} for rows of this table.
   */
  public final RowFormatter getRowFormatter() {
    return this.formatter;
  }

  @Override
  public final RowFormatter getRowFormatter(
      @Unretained final OffHeapByteSource vbytes) {
    // if this is for the latest schema, then vbytes must have the same schema
    // else we may need to read vbytes to get the schema it represents
    // Disabling this optimization for bug SNAP-766
//    if (this.isLatestSchema) {
//      assert getSchemaVersionFromValueBytes(vbytes) == formatter.schemaVersion:
//        "mismatch of versions: formatter=" + formatter.schemaVersion
//            + ", fromBytes=" + getSchemaVersionFromValueBytes(vbytes);
//
//      return this.formatter;
//    }
//    else {
//      return this.container.getRowFormatter(vbytes);
//    }
    return this.container.getRowFormatter(vbytes);
  }

  @Override
  public final RowFormatter getRowFormatter(final long memAddr,
      @Unretained final OffHeapByteSource vbytes) {
    // if this is for the latest schema, then vbytes must have the same schema
    // else we may need to read vbytes to get the schema it represents
    // Disabling this optimization for bug SNAP-766
//    if (this.isLatestSchema) {
//      assert getSchemaVersionFromValueBytes(unsafe, memAddr) ==
//          formatter.schemaVersion: "mismatch of versions: formatter="
//              + formatter.schemaVersion + ", fromBytes="
//              + getSchemaVersionFromValueBytes(unsafe, memAddr);
//
//      return this.formatter;
//    }
//    else {
//      return this.container.getRowFormatter(unsafe, memAddr, vbytes);
//    }
    return this.container.getRowFormatter(memAddr, vbytes);
  }

  @Override
  public final RowFormatter getRowFormatter(final byte[] vbytes) {
    // if this is for the latest schema, then vbytes must have the same schema
    // else we may need to read vbytes to get the schema it represents
    // Disabling this optimization for bug SNAP-766
//    if (this.isLatestSchema) {
//      assert getSchemaVersionFromValueBytes(vbytes) == formatter.schemaVersion:
//        "mismatch of versions: formatter=" + formatter.schemaVersion
//            + ", fromBytes=" + getSchemaVersionFromValueBytes(vbytes);
//
//      return this.formatter;
//    }
//    else {
//      return this.container.getRowFormatter(vbytes);
//    }
    return this.container.getRowFormatter(vbytes);
  }

  private static int getSchemaVersionFromValueBytes(byte[] vbytes) {
    return RowFormatter.readCompactInt(vbytes, 0);
  }

  public final boolean isValid() {
    return true;
  }

  /**
   * Get the {@link RowFormatter} for referenced key columns (i.e. foreign key
   * dependency of other tables) of this table.
   */
  public final RowFormatter getReferencedKeyRowFormatter() {
    return this.refKeyFormatter;
  }

  /**
   * Column indexes referenced for foreign key constraints by other tables.
   */
  public final int[] getReferencedKeyColumns() {
    return this.referencedKeyColumns;
  }

  /**
   * Column indexes referenced for foreign key constraints by other tables that
   * are fixed width ones.
   * <p>
   * This is initialized only if this table requires a RowFormatter for row i.e.
   * if this is for a table with byte array storage.
   */
  public final int[] getReferencedKeyFixedColumns() {
    return this.referencedKeyFixedColumns;
  }

  /**
   * Column indexes referenced for foreign key constraints by other tables that
   * are variable width ones.
   * <p>
   * This is initialized only if this table requires a RowFormatter for row i.e.
   * if this is for a table with byte array storage.
   */
  public final int[] getReferencedKeyVarColumns() {
    return this.referencedKeyVarColumns;
  }

  /**
   * Get the set of containers that have a foreign key reference to this table.
   */
  public final GemFireContainer[] getReferencedContainers() {
    return this.refContainers;
  }

  /**
   * Return the mapping from referenced key columns numbers of this table, to
   * the corresponding index conglomerate numbers that refer to those columns.
   */
  public final HashMap<int[], long[]> getReferencedKeyColumns2IndexNumbers() {
    return this.refKeyColumns2IndexNumbers;
  }

  public final void initRowFormatter(final GemFireContainer container)
      throws StandardException {
    if (this.td == null) {
      this.td = this.dd.getTableDescriptor(this.tableID);
    }
    refreshRowFormatter(container, this.td);
    refreshPrimaryKeyFormatter(container, this.td);
    refreshReferencedKeyFormatter(container, this.td);
    this.isLatestSchema = true;
  }

  public final void setLatestSchema(boolean flag) {
    this.isLatestSchema = flag;
  }

  private final void refreshAutoGenColumnInfo() {
    this.autoGenColumns = null;
    this.autoGenColumnNames = null;
    // loop over the table columns for getting auto-gen column information
    ColumnDescriptor cd;
    final ColumnDescriptorList cdl = this.td.getColumnDescriptorList();
    for (int index = 0; index < cdl.size(); ++index) {
      cd = cdl.elementAt(index);
      if (cd.isAutoincrement()) {
        if (this.autoGenColumns == null) { // user provided column indexes
          this.autoGenColumns = new IntObjectHashMap<>(4);
          this.autoGenColumnNames = new HashMap<>();
        }
        this.autoGenColumns.put(cd.getPosition(), cd);
        this.autoGenColumnNames.put(cd.getColumnName(), cd);
      }
    }
  }

  private final void refreshReferencedKeyInfo(final GemFireContainer container,
      final TableDescriptor td) throws StandardException {
    // first get the key column positions
    final FormatableBitSet bitSet = getReferencedKeyColumnPositionsBitSet();
    this.referencedKeyColumns = GemFireXDUtils
        .getColumnPositionsFromBitSet(bitSet);
    refreshReferencedKeyFormatter(container, td);
  }

  private final void refreshReferencedKeyFormatter(
      final GemFireContainer container, final TableDescriptor td) {
    if (this.referencedKeyColumns != null && container.isByteArrayStore()) {
      final int numColumns = this.referencedKeyColumns.length;
      final TIntArrayList fixedCols = new TIntArrayList(numColumns);
      final TIntArrayList varCols = new TIntArrayList(numColumns);
      this.refKeyFormatter = getRowFormatter(this.referencedKeyColumns,
          container, td, fixedCols, varCols, 0, false);
      if (fixedCols.size() > 0) {
        this.referencedKeyFixedColumns = fixedCols.toNativeArray();
      }
      if (varCols.size() > 0) {
        this.referencedKeyVarColumns = varCols.toNativeArray();
      }
    }
    else {
      this.refKeyFormatter = null;
      this.referencedKeyFixedColumns = null;
      this.referencedKeyVarColumns = null;
    }
  }

  private FormatableBitSet getReferencedKeyColumnPositionsBitSet()
      throws StandardException {

    final ConstraintDescriptorList cdl = this.dd
        .getConstraintDescriptors(this.td);
    final FormatableBitSet bitSet = new FormatableBitSet(this.td
        .getColumnDescriptorList().size() + 1);
    final HashSet<GemFireContainer> refContainerSet =
      new HashSet<GemFireContainer>();
    this.refKeyColumns2IndexNumbers = new HashMap<int[], long[]>();

    ConstraintDescriptor cd;
    ReferencedKeyConstraintDescriptor refcd;
    ConstraintDescriptorList fkcdl;
    ForeignKeyConstraintDescriptor fkcd;
    int[] baseColumnPositions;
    long[] conglomNumbers;
    for (int index = 0; index < cdl.size(); index++) {
      cd = cdl.elementAt(index);
      if (!(cd instanceof ReferencedKeyConstraintDescriptor)) {
        continue;
      }
      refcd = (ReferencedKeyConstraintDescriptor)cd;
      fkcdl = this.dd.getActiveConstraintDescriptors(refcd
          .getForeignKeyConstraints(ConstraintDescriptor.ENABLED));

      final int size = fkcdl.size();
      if (size == 0) {
        continue;
      }

      // get the base column positions from the table.
      ConglomerateDescriptor pkIndexConglom = this.td
          .getConglomerateDescriptor(refcd.getIndexId());
      baseColumnPositions = pkIndexConglom.getIndexDescriptor()
          .baseColumnPositions();

      // the backing index ids of the foreign key constraints
      conglomNumbers = new long[size];

      for (int i = 0; i < baseColumnPositions.length; ++i) {
        int position = baseColumnPositions[i];
        if (!bitSet.isSet(position)) {
          bitSet.set(position);
        }
      }
      for (int i = 0; i < size; i++) {
        fkcd = (ForeignKeyConstraintDescriptor)fkcdl.elementAt(i);
        conglomNumbers[i] = fkcd.getIndexConglomerateDescriptor(this.dd)
            .getConglomerateNumber();
        final GemFireContainer container = GemFireTransaction
            .findExistingConglomerate(
                fkcd.getTableDescriptor().getHeapConglomerateId(), null,
                null /* needed only for temp congloms */).getGemFireContainer();
        refContainerSet.add(container);
      }

      this.refKeyColumns2IndexNumbers.put(baseColumnPositions, conglomNumbers);
    }
    if (refContainerSet.size() > 0) {
      this.refContainers = new GemFireContainer[refContainerSet.size()];
      this.refContainers = refContainerSet.toArray(this.refContainers);
    }
    else {
      this.refContainers = null;
    }
    if (this.refKeyColumns2IndexNumbers.size() == 0) {
      this.refKeyColumns2IndexNumbers = null;
    }

    return bitSet;
  }

  /**
   * Get the foreign key containers of this table, if any. This method is not
   * thread safe and it should be invoked under the appropriate table lock, and
   * its result copied if need be.
   */
  public final GemFireContainer[] getForeignKeyContainers()
      throws StandardException {
    return this.fkContainers;
  }

  public final ColumnDescriptor getAutoGeneratedColumn(final int position) {
    if (this.autoGenColumns != null) {
      return (ColumnDescriptor)this.autoGenColumns.get(position);
    }
    return null;
  }

  public final ColumnDescriptor getAutoGeneratedColumn(final String name) {
    if (this.autoGenColumnNames != null) {
      return this.autoGenColumnNames.get(name);
    }
    return null;
  }

  public final boolean isAutoGeneratedColumn(final int position) {
    return this.autoGenColumns != null
        && this.autoGenColumns.containsKey(position);
  }

  public final int[] getAutoGeneratedColumns() {
    if (this.autoGenColumns != null) {
      final int[] keys = new int[this.autoGenColumns.size()];
      this.autoGenColumns.forEachKey(new IntProcedure() {
        private static final long serialVersionUID = 1126691153868080143L;

        private int index;

        @Override
        public void value(int k) {
          keys[index++] = k;
        }
      });
      return keys;
    }
    return null;
  }

  public final boolean hasAutoGeneratedColumns() {
    return this.autoGenColumns != null && this.autoGenColumns.size() > 0;
  }

  public final String[] getPrimaryKeyColumnNames() {
    return this.pkColumnNames;
  }

  public synchronized void refreshCachedInfo(final TableDescriptor tableDesc,
      GemFireContainer gfc) throws StandardException {
    if (tableDesc != null) {
      this.td = tableDesc;
    }
    else {
      this.td = this.dd.getTableDescriptor(this.tableID);
    }
    if (this.td == null) {
      throw new IllegalStateException("table descriptor should not be null");
    }
    // refresh constraint Desc list.
    // do it always since a FK constraint may be getting added
    this.td.emptyConstraintDescriptorList();
    this.dd.getConstraintDescriptors(this.td);

    final int[] pkColumns = GemFireXDUtils.getPrimaryKeyColumns(this.td);
    if (pkColumns != null && pkColumns.length > 0) {
      final int[] primaryKeyColumns = pkColumns.clone();
      final ColumnDescriptorList cdl = this.td.getPrimaryKey()
          .getColumnDescriptors();
      final String[] pkColumnNames = new String[cdl.size()];
      for (int index = 0; index < cdl.size(); index++) {
        pkColumnNames[index] = cdl.elementAt(index).getColumnName();
      }
      this.pkColumnNames = pkColumnNames;
      this.primaryKeyColumns = primaryKeyColumns;
    }
    else {
      this.primaryKeyColumns = null;
      this.pkColumnNames = null;
    }
    ArrayList<ForeignKeyConstraintDescriptor> fkcdList =
      new ArrayList<ForeignKeyConstraintDescriptor>();
    ConstraintDescriptorList cdList = this.td.getConstraintDescriptorList();
    for (int index = 0; index < cdList.size(); ++index) {
      ConstraintDescriptor cd = cdList.elementAt(index);
      if (cd.getConstraintType() == DataDictionary.FOREIGNKEY_CONSTRAINT) {
        fkcdList.add((ForeignKeyConstraintDescriptor)cd);
      }
    }
    final int numFKs = fkcdList.size();
    if (numFKs > 0) {
      this.fkContainers = new GemFireContainer[numFKs];
      ForeignKeyConstraintDescriptor fkcd;
      for (int index = 0; index < numFKs; index++) {
        fkcd = fkcdList.get(index);
        MemConglomerate conglom = GemFireTransaction.findExistingConglomerate(
            fkcd.getReferencedConstraint().getTableDescriptor()
                .getHeapConglomerateId(), null, null);
        this.fkContainers[index] = conglom.getGemFireContainer();
      }
    }
    else {
      this.fkContainers = null;
    }
    final LocalRegion r;
    if (gfc == null) {
      gfc = getGemFireContainer();
    }
    if (gfc != null) {
      if ((r = gfc.getRegion()) != null) {
        r.setKeyRequiresRegionContext(gfc.regionKeyRequiresRegionContext());
      }
      // only refresh the formatter if this is the current ExtraTableInfo
      // for the container
      if (this.formatter != null && gfc.getExtraTableInfo() == this) {
        refreshRowFormatter(gfc, this.td);
      }
      refreshPrimaryKeyFormatter(gfc, this.td);
      refreshReferencedKeyInfo(gfc, this.td);
    }
    refreshAutoGenColumnInfo();
  }

  /**
   * Get the version of this schema.
   */
  public final int getSchemaVersion() {
    return this.schemaVersion;
  }

  public final void refreshAtCommit(ColumnDescriptor addColumn, int dropColumn,
      LanguageConnectionContext lcc) throws StandardException {
    // don't schedule for refresh more than once for this ExtraTableInfo; this
    // is required for add/drop column now to avoid multiple table shape changes
    final GemFireTransaction tc = (GemFireTransaction)lcc
        .getTransactionExecute();
    // if table refresh has already been added then do nothing
    List<?> commitOps = tc.getLogAndDoListForCommit();
    TableInfoRefresh refresh;
    if (commitOps != null) {
      for (Object op : commitOps) {
        if (op instanceof TableInfoRefresh
            && (refresh = (TableInfoRefresh)op).tableInfo == this) {
          if (addColumn != null) {
            refresh.scheduleAddColumn(addColumn);
          }
          else if (dropColumn > 0) {
            refresh.scheduleDropColumn(dropColumn);
          }
          return;
        }
      }
    }

    final boolean schemaChanged = (addColumn != null || dropColumn > 0);
    refresh = new TableInfoRefresh(this, schemaChanged, tc);
    if (schemaChanged) {
      if (addColumn != null) {
        refresh.scheduleAddColumn(addColumn);
      }
      else {
        refresh.scheduleDropColumn(dropColumn);
      }
    }
    tc.logAndDo(refresh);
  }

  public void makeInvalid(int action, LanguageConnectionContext lcc)
      throws StandardException {
    switch (action) {
      case DependencyManager.ALTER_TABLE:
      case DependencyManager.TRUNCATE_TABLE:
      case DependencyManager.MODIFY_COLUMN_DEFAULT:
      case DependencyManager.CREATE_CONSTRAINT:
      case DependencyManager.DROP_CONSTRAINT:
      case DependencyManager.DROP_COLUMN:
      case DependencyManager.DROP_COLUMN_RESTRICT:
        if (this.td != null) { // check if table is initialized
          refreshAtCommit(null, -1, lcc);
        }
        break;
      default:
        return;
    }
  }

  public void prepareToInvalidate(Provider p, int action,
      LanguageConnectionContext lcc) throws StandardException {
  }

  public String getClassType() {
    return null;
  }

  public DependableFinder getDependableFinder() {
    return null;
  }

  public UUID getObjectID() {
    return this.oid;
  }

  public String getObjectName() {
    return null;
  }

  public boolean isDescriptorPersistent() {
    return false;
  }

  public final UUID getTableID() {
    return this.tableID;
  }

  public GemFireContainer getGemFireContainer() throws StandardException {
    GemFireContainer container;
    if ((container = this.container) != null) {
      return container;
    }
    return (this.container = Misc.getMemStore().getContainer(
        ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT,
            this.td.getHeapConglomerateId())));
  }
}
