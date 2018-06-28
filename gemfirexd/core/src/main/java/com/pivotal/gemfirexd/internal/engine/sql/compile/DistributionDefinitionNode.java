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
package com.pivotal.gemfirexd.internal.engine.sql.compile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.EvictionAlgorithm;
import com.gemstone.gemfire.cache.ExpirationAttributes;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;

import com.gemstone.gemfire.internal.cache.EvictionAttributesImpl;
import com.gemstone.gemfire.internal.cache.PartitionAttributesImpl;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ForeignKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnDefinitionNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ConstantNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ConstraintDefinitionNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FKConstraintDefinitionNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.QueryTreeNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.TableElementList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.TableElementNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.TableName;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNodeList;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;

/**
 * @author yjing
 * @author swale
 */
public class DistributionDefinitionNode extends TableElementNode {

  private int policy;

  private int redundancy;

  private ArrayList<ColumnReference> columns;

  private ArrayList<ValueNodeList> values;

  private int maxPartSize;

  private TableName colocateTable;

  private Properties tableProps = null;

  private TableDescriptor tableDesc = null;

  private boolean isPersistent;

  private boolean customHashing = false;

  SortedSet<String> serverGroups;

  private String rowEncoderClass;

  // status flags for canColocate method
  private static final int SUCCESS = 0;

  private static final int ERR_GROUPS = 1;

  private static final int ERR_REDUNDANCY = 2;

  private static final int ERR_PARTITIONPOLICY = 3;

  private static final int ERR_BUCKETS = 4;

  private static final int ERR_OTHER = 5;

  public DistributionDefinitionNode() {
    this.policy = DistributionDescriptor.NONE;
    this.redundancy = 0;
    this.columns = null;
    this.values = null;
    this.maxPartSize = 0;
    this.colocateTable = null;
    this.isPersistent = false;
    this.serverGroups = new TreeSet<String>();
  }

  public DistributionDefinitionNode(DistributionDescriptor desc) {
    this.policy = desc.getPolicy();
    this.redundancy = desc.getRedundancy();
    this.columns = null;
    this.values = null;
    this.maxPartSize = desc.getMaxPartSize();
    this.colocateTable = null;
    this.isPersistent = desc.getPersistence();
    this.serverGroups = desc.getServerGroups();
  }

  @Override
  public void init(Object policy) {
    assert policy instanceof Integer;
    this.policy = ((Integer)policy).intValue();
  }

  public void setRedundancy(int redundancy) {
    this.redundancy = redundancy;
  }

  public void setMaxPartSize(int maxPartSize) {
    this.maxPartSize = maxPartSize;
  }

  public void setPolicy(int policy) {
    this.policy = policy;
  }

  public void setServerGroups(SortedSet<String> groups) {
    this.serverGroups = groups;
  }

  public void setCustomHashing(boolean customHashing) {
    this.customHashing = customHashing;
  }

  public void setRowEncoderClass(String encoderClass) {
    this.rowEncoderClass = encoderClass;
  }

  public void addColumnReference(ColumnReference cr) {
    if (this.columns == null) {
      this.columns = new ArrayList<ColumnReference>();
    }
    this.columns.add(cr);
  }

  public void setColocatedTable(TableName tn) {
    this.colocateTable = tn;
  }

  public final void setPersistence(boolean isPersistent) {
    this.isPersistent = isPersistent;
  }

  public final boolean getPersistence() {
    return this.isPersistent;
  }

  public final boolean getCustomHashing() {
    return this.customHashing;
  }

  public String getRowEncoderClass() {
    return this.rowEncoderClass;
  }

  public void setTableProperties(Properties props) {
    this.tableProps = props;
  }

  public Properties getTableProperties() {
    return this.tableProps;
  }

  public void setTableDescriptor(TableDescriptor td) {
    this.tableDesc = td;
  }

  public TableName getColocatedTable() {
    return this.colocateTable;
  }

  private boolean isEvictionOrExpirationSet(TableName refTable) {
    boolean isSet = false;
    Region<?, ?> refRegion = Misc.getRegionByPath(refTable
        .getFullTableNameAsRegionPath(), true);
    RegionAttributes<?, ?> rattrs = refRegion.getAttributes();
    EvictionAction ea = rattrs.getEvictionAttributes().getAction();
    if (!(ea == EvictionAction.NONE || ea == EvictionAction.OVERFLOW_TO_DISK)
        || rattrs.getEntryIdleTimeout().getTimeout() > 0
        || rattrs.getEntryTimeToLive().getTimeout() > 0
        || rattrs.getRegionIdleTimeout().getTimeout() > 0
        || rattrs.getRegionTimeToLive().getTimeout() > 0) {
      isSet = true;
    }
    return isSet;
  }

  private void checkReferencedTables(TableElementIterator elementList,
      DataDictionary dd, String fullTableName) throws StandardException {
    ArrayList<ConstraintElement> foreignkeyConstraintList =
      getAllConstraintsWithType(elementList, DataDictionary.FOREIGNKEY_CONSTRAINT);
    for (ConstraintElement fkeyConstraint : foreignkeyConstraintList) {
      TableName refTable = fkeyConstraint.getReferencedTableName();
      if (fullTableName.equals(refTable.getFullTableName())) {
        continue;
      }
      if (isEvictionOrExpirationSet(refTable)) {
        throw StandardException.newException(SQLState.LANG_INVALID_REFERENCE,
            fullTableName, refTable.getFullTableName());
      }
    }
  }

  public DistributionDescriptor bind(TableElementList elementList,
      DataDictionary dd) throws StandardException {
    return bind(newTableElementIterator(elementList), dd);
  }

  public DistributionDescriptor bind(TableDescriptor td, DataDictionary dd)
      throws StandardException {
    return bind(newTableDescriptorIterator(td, dd), dd);
  }

  /**
   * Based on different distribution policies, validate input parameters and
   * resolve the policy in default case.
   * 
   * @param elementList
   * @param dd
   */
  private DistributionDescriptor bind(TableElementIterator elementList,
      DataDictionary dd) throws StandardException {

    DistributionDescriptor distributionDesc = null;
    String fullTableName;
    if (this.tableProps != null) {
      String schemaName = (String)this.tableProps
          .get(GfxdConstants.PROPERTY_SCHEMA_NAME);
      SchemaDescriptor sd = getSchemaDescriptor(schemaName, false);
      // inherit server groups from schema if required
      resetServerGroups(sd);
      String tableName = (String)this.tableProps
          .get(GfxdConstants.PROPERTY_TABLE_NAME);
      fullTableName = Misc.getFullTableName(schemaName, tableName, null);
    }
    else {
      fullTableName = Misc.getFullTableName(this.tableDesc, null);
    }
    if (!Misc.getMemStoreBooting().isHadoopGfxdLonerMode())
      checkReferencedTables(elementList, dd, fullTableName);
    switch (this.policy) {
      case DistributionDescriptor.NONE: {
        distributionDesc = resolveDefaultPartitionPolicy(elementList, dd, fullTableName);
        break;
      }
      case DistributionDescriptor.PARTITIONBYEXPRESSION: {
        distributionDesc = validatePartitionByExpression(elementList, dd);
        break;
      }
      case DistributionDescriptor.PARTITIONBYLIST:
      case DistributionDescriptor.PARTITIONBYRANGE: {
        distributionDesc = validatePartitionByList(elementList, dd);
        break;
      }
      case DistributionDescriptor.PARTITIONBYPRIMARYKEY: {
        distributionDesc = validatePartitionByPrimaryKey(elementList, dd,
            fullTableName);
        break;
      }
      
      case DistributionDescriptor.LOCAL:
      case DistributionDescriptor.REPLICATE: {
        distributionDesc = dd.getDataDescriptorGenerator()
            .newDistributionDescriptor(this.policy, null, 0, 0, null,
                this.isPersistent, this.serverGroups);
        break;
      }
      default: {
        throw new GemFireXDRuntimeException("DistributionDefinitionNode#bind: "
            + "Unknown partition policy!" + this.policy);
      }
    }
    if (this.colocateTable != null) {
      distributionDesc = validateColocatePolicy(elementList, dd,
          distributionDesc, fullTableName);
    }
    // make node an accessor or datastore depending on server groups
    setServerGroupsPolicy(fullTableName);

    return distributionDesc;
  }

  private boolean refTablePartitionedByPrimaryAndFKOnPrimaryKey(
      TableName refTable, ConstraintElement fkeyConstraint)
      throws StandardException {
    PartitionAttributesImpl pattrs = getPartitionAttributes(refTable
        .getFullTableNameAsRegionPath());
    if (pattrs != null &&
        pattrs.getPartitionResolver() instanceof GfxdPartitionResolver) {
      GfxdPartitionResolver spr = (GfxdPartitionResolver)pattrs
          .getPartitionResolver();
      if (spr.isPartitioningKeyThePrimaryKey()) {
        String[] partitionColNames = spr.getColumnNames();
        String[] refColNames = fkeyConstraint.getReferencedColumnNames();
        if (refColNames == null || refColNames.length == 0) {
          refColNames = fkeyConstraint.getConstraintColumnNames();
        }
        partitionColNames = partitionColNames.clone(); // don't change original
        refColNames = refColNames.clone(); // don't change original
        Arrays.sort(partitionColNames);
        Arrays.sort(refColNames);
        if (Arrays.equals(partitionColNames, refColNames)) {
          return true;
        }
      }
    }
    return false;
  }

  private void setAppropGfxdRslvrForDefltPartitioning(String[] colNames,
      String[] refColNames, PartitionAttributesImpl thisAttr,
      PartitionAttributesImpl refAttrs, TableDescriptor refTD)
      throws StandardException {
    GfxdPartitionResolver refRslvr = (GfxdPartitionResolver)refAttrs
        .getPartitionResolver();
    this.policy = refTD.getDistributionDescriptor().getPolicy();
    GfxdPartitionResolver rslvr = refRslvr.cloneForColocation(colNames,
        refColNames, refRslvr.getMasterTable(false /* immediate master*/));
    thisAttr.setPartitionResolver(rslvr);
    // also copy the total number of buckets
    thisAttr.setTotalNumBuckets(refAttrs.getTotalNumBuckets());
  }

  /**
   * This method finds the correct partition key based on the constraints
   * specified on table when the create statement does not contain Partition
   * clause. The order of the constraint selection: 1) Primary key 2) Unique key
   * 3) Foreign key
   * 
   * @throws StandardException
   *           thrown on error
   */
  private DistributionDescriptor resolveDefaultPartitionPolicy(
      TableElementIterator elementList, DataDictionary dd, String fullTableName)
      throws StandardException {

    // if default is to have REPLICATE tables then return that
    if (!Misc.getMemStore().isTableDefaultPartitioned()) {
      return dd.getDataDescriptorGenerator().newDistributionDescriptor(
          DistributionDescriptor.REPLICATE, null, this.redundancy,
          this.maxPartSize, null, this.isPersistent, this.serverGroups);
    }

    final ArrayList<ConstraintElement> foreignkeyConstraintList =
      getAllConstraintsWithType(elementList,
          DataDictionary.FOREIGNKEY_CONSTRAINT);
    for (ConstraintElement fkeyConstraint : foreignkeyConstraintList) {
      TableName refTable = fkeyConstraint.getReferencedTableName();
      if (fullTableName.equals(refTable.getFullTableName())) {
        continue;
      }
      // Neeraj: If foreign key is on unique key columns then don't allow
      // co-location for now. 
      // TODO Will re-visit later
      if (refTablePartitionedByPrimaryAndFKOnPrimaryKey(refTable,
          fkeyConstraint)) {
        TableDescriptor refTD = fkeyConstraint.getReferencedTableDescriptor();
        // check if colocation is possible
        PartitionAttributesImpl pattrs = getPartitionAttributes();
        PartitionAttributesImpl refPAttrs = getPartitionAttributes(refTD);
        // copy the total number of buckets from parent if not set explicitly
        int totalNumBuckets = -1;
        if (pattrs.hasTotalNumBuckets()) {
          totalNumBuckets = pattrs.getTotalNumBuckets();
        }
        if (canColocate(pattrs, totalNumBuckets,
            refTD, refPAttrs, false) == SUCCESS) {
          this.colocateTable = refTable;
          String[] columnNames = fkeyConstraint.getConstraintColumnNames();
          String[] refColumnNames = fkeyConstraint.getReferencedColumnNames();
          if (refColumnNames == null || refColumnNames.length == 0) {
            refColumnNames = columnNames;
          }
          setAppropGfxdRslvrForDefltPartitioning(columnNames, refColumnNames,
              pattrs, refPAttrs, refTD);
          String refRegionPath = refTable.getFullTableNameAsRegionPath();
          pattrs.setColocatedWith(refRegionPath);
          return dd.getDataDescriptorGenerator().newDistributionDescriptor(
              this.policy, columnNames, this.redundancy, this.maxPartSize,
              refRegionPath, this.isPersistent, this.serverGroups);
        }
      }
    }

    try {
      String[] keyConstraintCols;
      keyConstraintCols = getConstraintColumnNamesWithType(elementList,
          DataDictionary.PRIMARYKEY_CONSTRAINT);
      if (keyConstraintCols != null) {
        this.policy = DistributionDescriptor.PARTITIONBYPRIMARYKEY;
        return dd.getDataDescriptorGenerator().newDistributionDescriptor(
            this.policy, keyConstraintCols, this.redundancy, this.maxPartSize,
            null, this.isPersistent, this.serverGroups);
      }

      keyConstraintCols = getConstraintColumnNamesWithType(elementList,
          DataDictionary.UNIQUE_CONSTRAINT);
      if (keyConstraintCols != null) {
        this.policy = DistributionDescriptor.PARTITIONBYEXPRESSION;
        return dd.getDataDescriptorGenerator().newDistributionDescriptor(
            this.policy, keyConstraintCols, this.redundancy, this.maxPartSize,
            null, this.isPersistent, this.serverGroups);
      }

      keyConstraintCols = getConstraintColumnNamesWithType(elementList,
          DataDictionary.FOREIGNKEY_CONSTRAINT);
      if (keyConstraintCols != null) {
        this.policy = DistributionDescriptor.PARTITIONBYEXPRESSION;
        return dd.getDataDescriptorGenerator().newDistributionDescriptor(
            this.policy, keyConstraintCols, this.redundancy, this.maxPartSize,
            null, this.isPersistent, this.serverGroups);
      }

      this.policy = DistributionDescriptor.PARTITIONBYGENERATEDKEY;
      return dd.getDataDescriptorGenerator().newDistributionDescriptor(
          this.policy, (String[])null, this.redundancy, this.maxPartSize,
          null, this.isPersistent, this.serverGroups);
    } finally {
      if (this.tableProps == null) {
        // set the default partition resolver when invoked from ALTER TABLE
        PartitionAttributesImpl pattrs = getPartitionAttributes();
        GfxdPartitionResolver resolver = (GfxdPartitionResolver)pattrs
            .getPartitionResolver();
        if (!(resolver instanceof GfxdPartitionByExpressionResolver &&
            ((GfxdPartitionByExpressionResolver)resolver).isDefaultPartitioning())) {
          resolver = new GfxdPartitionByExpressionResolver();
          pattrs.setPartitionResolver(resolver);
        }
      }
    }
  }

  private TableDescriptor getTableDescriptor(TableName tName)
      throws StandardException {
    // check schema
    String schemaName = tName.getSchemaName();
    SchemaDescriptor sd = this.getSchemaDescriptor(schemaName);
    if (sd == null) {
      throw StandardException.newException(SQLState.LANG_SCHEMA_DOES_NOT_EXIST,
          schemaName);
    }

    // check table
    String tableName = tName.getTableName();
    TableDescriptor td = getTableDescriptor(tableName, sd);
    if (td == null) {
      throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND, tName
          .getFullTableName());
    }
    return td;
  }

  private void resetServerGroups(SchemaDescriptor sd) throws StandardException {
    if (sd != null
        && (this.serverGroups == null || this.serverGroups.size() == 0)) {
      this.serverGroups = sd.getDefaultServerGroups();
    }
  }

  private PartitionAttributesImpl getPartitionAttributes() {
    return (PartitionAttributesImpl)getAttributes().getPartitionAttributes();
  }

  private PartitionAttributesImpl getPartitionAttributes(String regionPath) {
    return (PartitionAttributesImpl)getAttributes(regionPath)
        .getPartitionAttributes();
  }

  private PartitionAttributesImpl getPartitionAttributes(TableDescriptor td) {
    return (PartitionAttributesImpl)getAttributes(td).getPartitionAttributes();
  }

  private RegionAttributes<?, ?> getAttributes() {
    if (this.tableProps != null) {
      return (RegionAttributes<?, ?>)this.tableProps
          .get(GfxdConstants.REGION_ATTRIBUTES_KEY);
    }
    else {
      return getAttributes(this.tableDesc);
    }
  }

  private RegionAttributes<?, ?> getAttributes(TableDescriptor td) {
    return getAttributes(Misc.getRegionPath(td, null));
  }

  private RegionAttributes<?, ?> getAttributes(String regionPath) {
    final Region<?, ?> region = Misc.getRegionByPath(regionPath, true);
    assert region != null: "expected region to exist";
    return region.getAttributes();
  }

  /** colocate only if the redundancy levels and server groups match */
  private int canColocate(PartitionAttributesImpl pattrs, int totalNumBuckets,
      TableDescriptor checkTD, PartitionAttributesImpl colocatePAttrs,
      boolean checkRslvrs) throws StandardException {
    if (pattrs != null && colocatePAttrs != null) {
      final DistributionDescriptor checkDesc = checkTD
          .getDistributionDescriptor();
      // cannot colocate if leader is not persistent while this table is;
      // cannot use DataPolicy from RegionAttributes since persistence will
      // not be set for an accessor, so use the DistributionDescriptor instead
      if (this.isPersistent && !checkDesc.getPersistence()) {
        return ERR_PARTITIONPOLICY;
      }
      // a -ve value indicates inherit totalNumBuckets from parent
      if (totalNumBuckets >= 0
          && colocatePAttrs.getTotalNumBuckets() != totalNumBuckets) {
        return ERR_BUCKETS;
      }
      if (pattrs.getRedundantCopies() == colocatePAttrs.getRedundantCopies()) {
        if (GemFireXDUtils.setEquals(this.serverGroups,
            checkDesc.getServerGroups())) {
          if (checkRslvrs &&
              pattrs.getPartitionResolver() instanceof GfxdPartitionResolver &&
              colocatePAttrs.getPartitionResolver() instanceof GfxdPartitionResolver) {
            GfxdPartitionResolver rslvr1 = (GfxdPartitionResolver)pattrs
                .getPartitionResolver();
            GfxdPartitionResolver rslvr2 = (GfxdPartitionResolver)colocatePAttrs
                .getPartitionResolver();
            if (!rslvr2.okForColocation(rslvr1)) {
              return ERR_PARTITIONPOLICY;
            }
          }
          return SUCCESS;
        }
        else {
          return ERR_GROUPS;
        }
      }
      else {
        return ERR_REDUNDANCY;
      }
    }
    return ERR_OTHER;
  }

  @SuppressWarnings({ "deprecation", "unchecked" })
  private void setServerGroupsPolicy(final String tableName)
      throws StandardException {
    if (this.tableProps == null) {
      return;
    }
    // check if there are any data stores available
    DistributionDescriptor.checkAvailableDataStore(
        getLanguageConnectionContext(), this.serverGroups, "CREATE TABLE for "
            + tableName);
    // set the data policy of this table as per the server groups
    RegionAttributes<Object, Object> attrs = (RegionAttributes<Object, Object>)
        this.tableProps.get(GfxdConstants.REGION_ATTRIBUTES_KEY);
    final DataPolicy dp = attrs.getDataPolicy();
    GemFireStore memStore = Misc.getMemStore();
    if (!ServerGroupUtils.isDataStore(tableName, this.serverGroups) &&
        // if this is a datadictionary table then allow for persistence
        !(memStore.isDataDictionaryPersistent() &&
            GfxdConstants.GFXD_DD_DISKSTORE_NAME.equals(attrs.getDiskStoreName()))) {
      if (dp.withPartitioning()) {
        final AttributesFactory<Object, Object> afact =
          new AttributesFactory<Object, Object>(attrs);
        afact.setEnableOffHeapMemory(false);
        if (dp.withPersistence()) {
          afact.setDiskStoreName(null);
          if (attrs.getHDFSStoreName() != null) {
            // do not reset dataPolicy or HDFS store name, there are some
            // checks on the accessor that need these
            afact.setDataPolicy(DataPolicy.HDFS_PARTITION);
          } else {
            afact.setHDFSStoreName(null);
            afact.setDataPolicy(DataPolicy.PARTITION);
          }                    
        }
        attrs = afact.create();
        this.tableProps.put(GfxdConstants.REGION_ATTRIBUTES_KEY, attrs);
        PartitionAttributesImpl pattrs = (PartitionAttributesImpl)attrs
            .getPartitionAttributes();
        pattrs.setLocalMaxMemory(0);
      }
      else {
        final AttributesFactory<Object, Object> afact =
            new AttributesFactory<Object, Object>(attrs);
        if (policy != DistributionDescriptor.LOCAL) {
          afact.setDataPolicy(DataPolicy.EMPTY);
        }
        // remove expiration/eviction attributes for empty region
        ExpirationAttributes disableExpAttrs = new ExpirationAttributes(0);
        if (attrs.getEntryIdleTimeout() != null) {
          afact.setEntryIdleTimeout(disableExpAttrs);
        }
        if (attrs.getEntryTimeToLive() != null) {
          afact.setEntryTimeToLive(disableExpAttrs);
        }
        if (attrs.getEvictionAttributes() != null) {
          afact.setEvictionAttributes(new EvictionAttributesImpl()
              .setAlgorithm(EvictionAlgorithm.NONE));
        }
        afact.setDiskStoreName(null);
        afact.setEnableOffHeapMemory(false);
        attrs = afact.create();
        this.tableProps.put(GfxdConstants.REGION_ATTRIBUTES_KEY, attrs);
      }
    }
    else {
      if (!memStore.isHadoopGfxdLonerMode()) {
        // for persistence we should have DataDictionary also as persistent
        if ((getPersistence() || dp.withPersistence())
            && !memStore.isDataDictionaryPersistent()) {
          throw StandardException.newException(SQLState.DD_NOT_PERSISTING,
              tableName);
        }
      }
    }
  }

  private DistributionDescriptor validateColocatePolicy(
      TableElementIterator elementList, DataDictionary dd,
      DistributionDescriptor newDistributionDescp, String srcTableName)
      throws StandardException {

    assert this.colocateTable != null: "validateColocatePolicy: "
        + "No target table is specified for the colocate policy";
    if (this.tableProps != null) {
      // bind should be done only in CREATE TABLE phase
      this.colocateTable.bind(dd);
    }

    TableDescriptor td = getTableDescriptor(this.colocateTable);
    String tableName = Misc.getFullTableName(td, null);
    DistributionDescriptor targetDistributionDesc = td
        .getDistributionDescriptor();
    assert targetDistributionDesc != null;
    // check that colocation is possible
    RegionAttributes<?, ?> attrs = getAttributes();
    PartitionAttributesImpl pattrs = (PartitionAttributesImpl)attrs
        .getPartitionAttributes();
    RegionAttributes<?, ?> checkAttrs = getAttributes(td);
    PartitionAttributesImpl checkPAttrs = (PartitionAttributesImpl)checkAttrs
        .getPartitionAttributes();
    // copy buckets from parent if not set explicitly
    if (pattrs != null && !pattrs.hasTotalNumBuckets() && checkPAttrs != null
        && checkPAttrs.hasTotalNumBuckets()) {
      pattrs.setTotalNumBuckets(checkPAttrs.getTotalNumBuckets());
    }
    final int colocateRes = canColocate(pattrs,
        pattrs != null ? pattrs.getTotalNumBuckets() : -1, td, checkPAttrs,
        true);
    if (colocateRes == ERR_GROUPS) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_COLOCATION_DIFFERENT_GROUPS, srcTableName,
          tableName, String.valueOf(this.serverGroups), String
              .valueOf(targetDistributionDesc.getServerGroups()));
    }
    else if (colocateRes == ERR_REDUNDANCY) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_COLOCATION_REDUNDANCY_OR_BUCKETS_MISMATCH,
          srcTableName, tableName,
          "redundancy: " + pattrs.getRedundantCopies(), "redundancy: "
              + checkPAttrs.getRedundantCopies());
    }
    else if (colocateRes == ERR_BUCKETS) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_COLOCATION_REDUNDANCY_OR_BUCKETS_MISMATCH,
          srcTableName, tableName, "buckets: " + pattrs.getTotalNumBuckets(),
          "buckets: " + checkPAttrs.getTotalNumBuckets());
    }
    else if (colocateRes == ERR_PARTITIONPOLICY) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_COLOCATION_PARTITIONINGPOLICY_MISMATCH,
          srcTableName, tableName, attrs.getDataPolicy().toString() + ':'
              + pattrs.getPartitionResolver(), checkAttrs.getDataPolicy()
              .toString() + ':' + checkPAttrs.getPartitionResolver());
    }
    else if (colocateRes != SUCCESS) {
      throw StandardException.newException(SQLState.LANG_INVALID_COLOCATION,
          srcTableName, tableName);
    }

    // check the colocation policy
    String columnNames[] = null;
    // validate the columns exists in the table to be created and the target
    // tables
    boolean newDDHasPartitionColumns = false;
    if (newDistributionDescp != null
        && newDistributionDescp.getPartitionColumnNames() != null) {
      newDDHasPartitionColumns = true;
    }
    if (this.columns != null || newDDHasPartitionColumns) {
      if (!newDDHasPartitionColumns) {
        columnNames = validatePartitionColumns(elementList);
      }
      else {
        columnNames = validatePartitionColumns(elementList,
            newDistributionDescp.getPartitionColumnNames());
      }
      // check in the target table

      if (!(tableName.toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX)
          || srcTableName.toUpperCase().endsWith(StoreCallbacks.SHADOW_TABLE_SUFFIX))) {
        int[] colpositions = targetDistributionDesc.getColumnPositionsSorted();
        int size = columnNames.length;
        if (colpositions.length != size) {
          throw StandardException.newException(
              SQLState.LANG_INVALID_COLOCATION_DIFFERENT_COL_COUNT, srcTableName,
              tableName, size, colpositions.length);
        }
        for (int i = 0; i < size; i++) {

          TableColumn src = elementList.getColumn(columnNames[i]);
          ColumnDescriptor tgt = td.getColumnDescriptor(colpositions[i]);

          if (!tgt.getType().getTypeName().equals(src.getType().getTypeName())
              || tgt.getType().getPrecision() != src.getType().getPrecision()
              || tgt.getType().getScale() != src.getType().getScale()
              || tgt.getType().getMaximumWidth() != src.getType()
              .getMaximumWidth()) {
            // SQLChar types can be collocated
            if (CallbackFactoryProvider.getStoreCallbacks().isSnappyStore()
                && DataTypeDescriptor.isCharacterStreamAssignable(tgt.getType().getJDBCTypeId())
                && DataTypeDescriptor.isCharacterStreamAssignable(src.getType().getJDBCTypeId())) {
              continue;
            }
          /*
          * if( !tgt.getType().equals(src.getType()) ) {
          *
          * To skip Nullability check .equals cannot be used.
          * Also bypassing collationtype and collationderivative checks.
          * Ticket 39873.
          * @see TypeDescriptorImpl#equals
          */
            throw StandardException.newException(
                SQLState.LANG_INVALID_COLOCATION_COL_TYPES_MISMATCH,
                srcTableName, tableName, src.getColumnName(), String.valueOf(src
                    .getType()), tgt.getColumnName(), String.valueOf(tgt
                    .getType()));
          }
        }
      }
    }
    return dd.getDataDescriptorGenerator().newDistributionDescriptor(
        targetDistributionDesc.getPolicy(), columnNames, this.redundancy,
        this.maxPartSize, Misc.getRegionPath(td, null), this.isPersistent,
        this.serverGroups);
  }

  private String[] validatePartitionColumns(TableElementIterator elementList)
      throws StandardException {

    assert this.columns.size() > 0;
    String[] columnNames = new String[this.columns.size()];
    for (int index = 0; index < this.columns.size(); index++) {
      ColumnReference cf = this.columns.get(index);
      String colName = cf.getColumnName();
      // check in the table to be created.
      if (!elementList.containsColumnName(colName)) {
        // should never happen
        throw StandardException.newException(SQLState.LANG_SYNTAX_ERROR,
            "The partition column (" + colName
                + ") does not exist in the table's column list");
      }
      columnNames[index] = colName;
    }
    return columnNames;
  }

  private String[] validatePartitionColumns(TableElementIterator elementList,
      String[] colNames) throws StandardException {

    assert colNames.length > 0;
    String[] columnNames = colNames;
    for (int index = 0; index < columnNames.length; index++) {
      String colName = columnNames[index];
      // System.out.println("XXXDistributionDefinitonNode:"+colName);
      // check in the table to be created.
      if (!elementList.containsColumnName(colName)) {
        // should never happen
        throw StandardException.newException(SQLState.LANG_SYNTAX_ERROR,
            "The partition column (" + colName
                + ") does not exist in the table's column list");
      }
      columnNames[index] = colName;
    }
    return columnNames;
  }

  private DistributionDescriptor validatePartitionByExpression(
      TableElementIterator elementList, DataDictionary dd)
      throws StandardException {
    String[] columnNames = this.columns != null
        ? validatePartitionColumns(elementList) : null;
    return dd.getDataDescriptorGenerator()
        .newDistributionDescriptor(this.policy, columnNames, this.redundancy,
            this.maxPartSize, null, this.isPersistent, this.serverGroups);
  }

  private DistributionDescriptor validatePartitionByPrimaryKey(
      TableElementIterator elementList, DataDictionary dd, String tableName)
      throws StandardException {
    // check if the table elements contains the primary key
    String[] pkCols = getConstraintColumnNamesWithType(elementList,
        DataDictionary.PRIMARYKEY_CONSTRAINT);
    if (pkCols == null || pkCols.length == 0) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_PARTITIONING_NO_PK, tableName);
    }
    return dd.getDataDescriptorGenerator().newDistributionDescriptor(
        this.policy, pkCols, this.redundancy, this.maxPartSize, null,
        this.isPersistent, this.serverGroups);
  }

  private DistributionDescriptor validatePartitionByList(
      TableElementIterator elementList, DataDictionary dd)
      throws StandardException {
    String[] columnNames = validatePartitionColumns(elementList);
    DistributionDescriptor distributionDesc = dd.getDataDescriptorGenerator()
        .newDistributionDescriptor(this.policy, columnNames, this.redundancy,
            this.maxPartSize, null, this.isPersistent, this.serverGroups);
    for (int index = 0; index < this.values.size(); ++index) {
      ArrayList<DataValueDescriptor> valueSet =
        new ArrayList<DataValueDescriptor>();
      ValueNodeList valueNodeList = this.values.get(index);
      for (int j = 0; j < valueNodeList.size(); ++j) {
        ValueNode valueNode = (ValueNode)valueNodeList.elementAt(j);
        assert valueNode instanceof ConstantNode: valueNode.toString();
        valueSet.add(((ConstantNode)valueNode).getValue());
      }
      distributionDesc.addValueSet(valueSet);
    }
    return distributionDesc;
  }

  private String[] getConstraintColumnNamesWithType(
      TableElementIterator elementList, int constraintType)
      throws StandardException {
    for (ConstraintElement element : elementList) {
      if (element.getConstraintType() == constraintType) {
        // found the key; break on the first key of given constraintType
        return element.getConstraintColumnNames();
      }
    }
    return null;
  }

  private ArrayList<ConstraintElement> getAllConstraintsWithType(
      TableElementIterator elementList, int constraintType)
      throws StandardException {
    ArrayList<ConstraintElement> list = new ArrayList<ConstraintElement>();
    /* TODO: We need a deterministic order for FK list for default colocation.
     * However, the code below is not enough and also needs to be sorted by
     * column names of this table (not reference table just in case this table
     *  has more than one FKs to same table but from different columns in this
     *  table)
    // sort the foreign key list to give a deterministic default colocation
    Collections.sort(list, new Comparator<ConstraintElement>() {
      @Override
      public int compare(ConstraintElement first, ConstraintElement second) {
        try {
          final TableName firstName = first.getReferencedTableName();
          final TableName secondName = second.getReferencedTableName();
          if (firstName == null) {
            return (secondName == null ? 0 : -1);
          }
          if (secondName == null) {
            return 1;
          }
          return firstName.getFullTableName().compareTo(
              secondName.getFullTableName());
        } catch (StandardException se) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "unexpected exception", se);
        }
      }
    });
    */
    for (ConstraintElement element : elementList) {
      if (element.getConstraintType() == constraintType) {
        list.add(element);
      }
    }
    return list;
  }

  public void addValueNodeList(ValueNodeList values) {
    if (this.values == null) {
      this.values = new ArrayList<ValueNodeList>();
    }
    this.values.add(values);
  }

  public TableElementIterator newTableElementIterator(
      TableElementList elementList) {
    return new TableElementListIterator(elementList);
  }

  public TableElementIterator newTableDescriptorIterator(
      TableDescriptor td, DataDictionary dd) {
    return new TableDescriptorIterator(td, dd);
  }

  /**
   * Interface to encapsulate a {@link ConstraintDefinitionNode} or
   * {@link ConstraintDescriptor} with methods useful for
   * {@link DistributionDefinitionNode}.
   * 
   * @author swale
   */
  static interface ConstraintElement {

    /**
     * Get the constraint type for this element.
     */
    int getConstraintType() throws StandardException;

    /**
     * Get the referenced table name if this is a foreign key constraint or null
     * if this is not a foreign key constraint element.
     */
    TableName getReferencedTableName() throws StandardException;

    /**
     * Get the referenced table descriptor if this is a foreign key constraint
     * or null if this is not a foreign key constraint element.
     */
    TableDescriptor getReferencedTableDescriptor() throws StandardException;

    /**
     * Get the array of column names that constitute this constraint.
     */
    String[] getConstraintColumnNames() throws StandardException;

    /**
     * Get the array of column names that constitute the referenced table if
     * this is a foreign key contraint or null otherwise.
     */
    String[] getReferencedColumnNames() throws StandardException;
  }

  /**
   * This interface encapsulates a table with iterator over its list of
   * constraints which can be either part of {@link TableElementList} as
   * {@link ConstraintDefinitionNode}s or as {@link ConstraintDescriptor}s in
   * {@link TableDescriptor}.
   * 
   * @author swale
   */
  static interface TableElementIterator extends
      Iterable<ConstraintElement> {

    /**
     * Return true if this table contains the given column name.
     */
    boolean containsColumnName(String columnName) throws StandardException;

    /**
     * Get the {@link TableColumn} corresponding to the given column name or
     * null if the column is not present in this table.
     */
    TableColumn getColumn(String columnName);
  }

  /**
   * Class to encapsulate information about a column of a table including its
   * name and type.
   * 
   * @author swale
   */
  private static class TableColumn {

    DataTypeDescriptor dtd;

    String name;

    private TableColumn(DataTypeDescriptor dtd, String name) {
      this.dtd = dtd;
      this.name = name;
    }

    /**
     * Get the {@link DataTypeDescriptor} of this column.
     */
    public DataTypeDescriptor getType() {
      return this.dtd;
    }

    /**
     * Get the name of this column.
     */
    public String getColumnName() {
      return this.name;
    }
  }

  /**
   * This class implements {@link TableElementIterator} to encapsulate a
   * {@link TableElementList} and the constraint nodes inside it.
   * 
   * @author swale
   */
  private class TableElementListIterator implements TableElementIterator,
      Iterator<ConstraintElement> {

    private final TableElementList elementList;

    private int currentIndex;

    private class ConstraintNodeElement implements ConstraintElement {

      ConstraintDefinitionNode consNode;

      private ConstraintNodeElement(QueryTreeNode node) {
        if (node instanceof ConstraintDefinitionNode) {
          this.consNode = (ConstraintDefinitionNode)node;
        }
        else {
          this.consNode = null;
        }
      }

      public int getConstraintType() {
        if (this.consNode != null) {
          return this.consNode.getConstraintType();
        }
        return -1;
      }

      public TableName getReferencedTableName() {
        if (this.consNode instanceof FKConstraintDefinitionNode) {
          return ((FKConstraintDefinitionNode)this.consNode).getRefTableName();
        }
        return null;
      }

      public TableDescriptor getReferencedTableDescriptor()
          throws StandardException {
        TableName refTableName = getReferencedTableName();
        if (refTableName != null) {
          return getTableDescriptor(refTableName);
        }
        return null;
      }

      public String[] getConstraintColumnNames() {
        if (this.consNode != null) {
          return this.consNode.getColumnList().getColumnNames();
        }
        return null;
      }

      public String[] getReferencedColumnNames() throws StandardException {
        if (this.consNode instanceof FKConstraintDefinitionNode) {
          return ((FKConstraintDefinitionNode)this.consNode)
              .getReferencedConstraintInfo().getReferencedColumnNames();
        }
        return null;
      }
    }

    private TableElementListIterator(TableElementList tableElementList) {
      this.elementList = tableElementList;
      this.currentIndex = -1;
    }

    public boolean containsColumnName(String columnName) {
      return this.elementList.containsColumnName(columnName);
    }

    public TableColumn getColumn(String columnName) {
      ColumnDefinitionNode col = this.elementList
          .findColumnDefinition(columnName);
      if (col != null) {
        return new TableColumn(col.getType(), col.getColumnName());
      }
      return null;
    }

    public Iterator<ConstraintElement> iterator() {
      return new TableElementListIterator(this.elementList);
    }

    public boolean hasNext() {
      return ((this.currentIndex + 1) < this.elementList.size());
    }

    public ConstraintElement next() {
      ++this.currentIndex;
      return new ConstraintNodeElement(this.elementList
          .elementAt(this.currentIndex));
    }

    public void remove() {
      throw new UnsupportedOperationException("remove not supported");
    }
  }

  /**
   * This class implements {@link TableElementIterator} to encapsulate a
   * {@link TableDescriptor} and the constraint descriptors inside it.
   * 
   * @author swale
   */
  private static class TableDescriptorIterator implements TableElementIterator,
      Iterator<ConstraintElement> {

    private final TableDescriptor td;

    private final ConstraintDescriptorList descList;

    private int currentIndex;

    private class ConstraintDescriptorElement implements ConstraintElement {

      ConstraintDescriptor desc;

      private ConstraintDescriptorElement(ConstraintDescriptor desc) {
        this.desc = desc;
      }

      public int getConstraintType() {
        return this.desc.getConstraintType();
      }

      public TableName getReferencedTableName() throws StandardException {
        TableDescriptor refTD = getReferencedTableDescriptor();
        if (refTD != null) {
          TableName refTable = new TableName();
          refTable.init(refTD.getSchemaName(), refTD.getName());
          return refTable;
        }
        return null;
      }

      public TableDescriptor getReferencedTableDescriptor()
          throws StandardException {
        if (this.desc instanceof ForeignKeyConstraintDescriptor) {
          return ((ForeignKeyConstraintDescriptor)this.desc)
              .getReferencedConstraint().getTableDescriptor();
        }
        return null;
      }

      public String[] getConstraintColumnNames() throws StandardException {
        return this.desc.getColumnDescriptors().getColumnNames();
      }

      public String[] getReferencedColumnNames() throws StandardException {
        if (this.desc instanceof ForeignKeyConstraintDescriptor) {
          return ((ForeignKeyConstraintDescriptor)this.desc)
              .getReferencedConstraint().getColumnDescriptors()
              .getColumnNames();
        }
        return null;
      }
    }

    private TableDescriptorIterator(TableDescriptor desc, DataDictionary dd) {
      this.td = desc;
      try {
        this.descList = dd.getConstraintDescriptors(desc);
      } catch (StandardException ex) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "Unexpected exception while getting constraint list for table: "
                + desc, ex);
      }
      this.currentIndex = -1;
    }

    private TableDescriptorIterator(TableDescriptor desc,
        ConstraintDescriptorList descList) {
      this.td = desc;
      this.descList = descList;
      this.currentIndex = -1;
    }

    public boolean containsColumnName(String columnName) {
      return (this.td.getColumnDescriptor(columnName) != null);
    }

    public TableColumn getColumn(String columnName) {
      ColumnDescriptor col = this.td.getColumnDescriptor(columnName);
      if (col != null) {
        return new TableColumn(col.getType(), col.getColumnName());
      }
      return null;
    }

    public Iterator<ConstraintElement> iterator() {
      return new TableDescriptorIterator(this.td, this.descList);
    }

    public boolean hasNext() {
      return ((this.currentIndex + 1) < this.descList.size());
    }

    public ConstraintElement next() {
      ++this.currentIndex;
      return new ConstraintDescriptorElement(this.descList
          .elementAt(this.currentIndex));
    }

    public void remove() {
      throw new UnsupportedOperationException("remove not supported");
    }
  }
}
