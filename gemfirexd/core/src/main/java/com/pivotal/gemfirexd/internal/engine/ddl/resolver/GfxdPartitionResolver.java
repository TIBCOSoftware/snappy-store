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

package com.pivotal.gemfirexd.internal.engine.ddl.resolver;

import java.io.Serializable;
import java.util.*;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.EntryOperation;
import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.RegionFunctionContext;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.pivotal.gemfirexd.internal.catalog.IndexDescriptor;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalRowLocation;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdSingleResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.FunctionUtils;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ConstantNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryList;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation.BucketAndNetServerInfo;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This is the base class for all GemFireXD {@link PartitionResolver}
 * implementations. It provides a
 * {@link #bindExpression(FromList, SubqueryList, Vector)} method to be invoked
 * during the bind phase of CREATE TABLE, that allows implementations to perform
 * any binding if required.
 * 
 * @author Sumedh Wale
 * @author Kumar Neeraj
 * @since 6.0
 */
public abstract class GfxdPartitionResolver implements
    InternalPartitionResolver<Object, Object>, Cloneable {

  protected String tableName;

  protected String schemaName;

  protected String masterTable;

  protected GemFireContainer gfContainer;

  protected int numBuckets;

  protected GfxdPartitionResolver masterResolver;

  protected String[] partitionColumnNames;

  protected volatile GemFireContainer globalIndexContainer;

  protected boolean isPrimaryKeyPartitioningKey;

  protected final LogWriterI18n gflogger;

  protected GfxdPartitionResolver() {
    // allow for null setting for tests
    if (GemFireStore.getBootedInstance() != null) {
      this.gflogger = Misc.getI18NLogWriter();
    }
    else {
      this.gflogger = null;
    }
  }

  /** not really declarable; use DDL */
  public final void init(Properties props) {
    throw new AssertionError("GfxdPartitionResolver#init: "
        + "not expected to be invoked");
  }

  /**
   * Bind this expression. This means binding the sub-expressions, as well as
   * figuring out what the return type is for this expression. This should be
   * invoked at the time when the rest of CREATE TABLE is being bound.
   * 
   * @param fromList
   *          The FROM list for the query this expression is in, for binding
   *          columns.
   * @param subqueryList
   *          The subquery list being built as we find SubqueryNodes
   * @param aggregateVector
   *          The aggregate vector being built as we find AggregateNodes
   * 
   * @exception StandardException
   *              Thrown on error
   */
  public abstract void bindExpression(FromList fromList,
      SubqueryList subqueryList, Vector<?> aggregateVector)
      throws StandardException;

  /**
   * Nothing to be done by default. Implementations should override this for any
   * necessary cleanup.
   */
  public void close() {
  }

  /**
   * Get the routing object given region key and raw value.
   */
  public abstract Object getRoutingObject(Object key, @Unretained Object val,
      Region<?, ?> region);

  /**
   * Get the routing object given region key, raw value and callback argument.
   */
  @Override
  public final Object getRoutingObject(Object key, Object val,
      Object callbackArg, Region<?, ?> region) {
    if (callbackArg != null) {
      if (callbackArg instanceof WrappedCallbackArgument) {
        callbackArg = ((WrappedCallbackArgument)callbackArg)
            .getOriginalCallbackArg();
      }
      if (callbackArg instanceof GfxdCallbackArgument) {
        final GfxdCallbackArgument ca = (GfxdCallbackArgument)callbackArg;
        if (ca.isRoutingObjectSet()) {
          return ca.getRoutingObject();
        }
      }
    }
    return getRoutingObject(key, val, region);
  }

  protected abstract boolean isPartitioningSubsetOfKey();

  public Serializable getRoutingObject(EntryOperation<Object, Object> opDetails) {
    // Asif: The callback argument will be instance of GfxdCallbackArgument iff
    // the operation on the table is PK based (i.e convertible into region.put &
    // region.destroy)
    GfxdCallbackArgument sca = null;
    Object callbackArg = opDetails.getCallbackArgument();
    final boolean finerEnabled = this.gflogger.fineEnabled();

    if (finerEnabled) {
      gflogger.finer(getClass().getName() + ".getRoutingObject: "
          + "callback arg=" + callbackArg + "; operation=" + opDetails);
    }
    if (callbackArg != null) {
      if (callbackArg instanceof GfxdCallbackArgument) {
        sca = (GfxdCallbackArgument)callbackArg;
        if (sca.isRoutingObjectSet()) {
          return sca.getRoutingObject();
        }
      }
      else {
        return (Serializable)callbackArg;
      }
    }
    boolean isEntryEventImpl = opDetails instanceof EntryEventImpl;
    EntryEventImpl eeImpl = null;
    EntryOperationImpl deOp = null;
    if (!isEntryEventImpl) {
      deOp = (EntryOperationImpl)opDetails;
    }
    else {
      eeImpl = (EntryEventImpl)opDetails;
    }

    @Unretained Object val = null;
    if (!isPartitioningSubsetOfKey()) {
      if (isEntryEventImpl) {
        val = eeImpl.getRawNewValue();
      }
      else {
        val = deOp.getNewValue();
      }
    }
    final Object key = opDetails.getKey();
    final Object routingObject = getRoutingObject(key, val,
        opDetails.getRegion());
    setRoutingObjectInCallbackArg(routingObject, key, eeImpl, deOp,
        isEntryEventImpl, sca);

    if (finerEnabled) {
      gflogger.finer(getClass().getName() + " routingObject returned is "
          + routingObject);
    }
    return (Serializable)routingObject;
  }

  /**
   * 
   * @param partitionColumnValue
   *          - The actual value of the column on which partitioning has been
   *          done
   * @return - If range and list then their routing objects as per range or list
   *         values else simple the hashcode of the parameter passed.
   */
  public abstract Object getRoutingKeyForColumn(
      DataValueDescriptor partitionColumnValue);

  public abstract Object[] getRoutingObjectsForRange(
      DataValueDescriptor lowerBound, boolean lowerBoundInclusive,
      DataValueDescriptor upperBound, boolean upperBoundInclusive);

  public Object getRoutingObjectForExpression(
      DataValueDescriptor expressionValue) {
    throw new UnsupportedOperationException("GfxdPartitionResolver::"
        + "getRoutingObjectForExpression: should not have been invoked");
  }

  /** the canonical expression for PARTITION BY EXPRESSION */
  public String getCanonicalizedExpression() {
    return null;
  }

  /**
   * Get a string representation of the form used in DDL declaration i.e.
   * PARTITION BY ...
   */
  public abstract String getDDLString();

  /**
   * Returns a routing object given an array of Dvds.
   * 
   * @param values
   *          input dvd array.
   * @return a routing object
   */
  public abstract Serializable getRoutingObjectFromDvdArray(
      DataValueDescriptor[] values);

  /**
   * Determine the routing key(s) given a list of keys. These are absolute
   * underlying buckets for Range and List partitioning and can be used to
   * determine member lists hosting these buckets.
   * 
   * @see PartitionedRegion#getMembersFromRoutingObjects
   * @param keys
   *          Keys can be primary key or unique key of the table or implicit
   *          RowID of the table if none defined.
   * @return array of routing keys.
   */
  public abstract Object[] getRoutingObjectsForList(DataValueDescriptor[] keys);

  public abstract Object getRoutingObjectsForPartitioningColumns(
      DataValueDescriptor[] partitioningColumnObjects);

  /**
   * 
   * @return - Names of all the columns which are used in partitioning
   */
  abstract public String[] getColumnNames();

  public final String getTableName() {
    return this.tableName;
  }

  public final String getSchemaName() {
    return this.schemaName;
  }

  protected final String getQualifiedTableName() {
    return this.schemaName + '.' + this.tableName;
  }

  public final void setTableDetails(final TableDescriptor td,
      final GemFireContainer container) {
    this.schemaName = td.getSchemaName();
    this.tableName = td.getName();
    if (container != null) {
      this.gfContainer = container;
    }
    else {
      this.gfContainer = (GemFireContainer)Misc.getRegionForTableByPath(
          Misc.getFullTableName(td, null), true).getUserAttribute();
    }
    this.numBuckets = this.gfContainer.getRegionAttributes()
        .getPartitionAttributes().getTotalNumBuckets();
  }

  public abstract void setColumnInfo(TableDescriptor td, Activation activation)
      throws StandardException;

  public final void setMasterTable(String masterTable) {
    this.masterTable = masterTable;
    if (this.masterTable != null) {
      final Region<?, ?> masterRegion = Misc.getRegionByPath(this.masterTable,
          true);
      this.masterResolver = (GfxdPartitionResolver)masterRegion.getAttributes()
          .getPartitionAttributes().getPartitionResolver();
    }
  }

  public final String getMasterTable(boolean rootMaster) {
    if (this.masterTable == null) {
      this.masterTable = Misc.getRegionPath(this.schemaName, this.tableName,
          null);
      return this.masterTable;
    }
    if (rootMaster) {
      return this.masterResolver != null ? this.masterResolver
          .getMasterTable(rootMaster) : this.masterTable;
    }
    else {
      return this.masterTable;
    }
  }

  public abstract GfxdPartitionResolver cloneObject();

  public final GfxdPartitionResolver cloneForColocation(
      String[] newPartitioningColumns, String[] refPartitioningColumns,
      String masterTable) {
    GfxdPartitionResolver resolver = this.cloneObject();
    resolver
        .setPartitionColumns(newPartitioningColumns, refPartitioningColumns);
    resolver.setMasterTable(masterTable);
    return resolver;
  }

  /**
   * This method does just the basic checking of whether the resolvers are
   * compatible resolvers or not and in case of range and list partitioning
   * whether they share the same list or range, as the case may be, or not.
   * 
   * @param rslvr
   * @return true if compatible else false
   */
  public abstract boolean okForColocation(GfxdPartitionResolver rslvr);

  protected abstract void setPartitionColumns(String[] partCols,
      String[] refPartCols);

  public abstract boolean isUsedInPartitioning(String columnToCheck);

  public abstract int getPartitioningColumnIndex(String partitionColumn);

  public void updateDistributionDescriptor(DistributionDescriptor desc) {
  }

  public String[] getPartitioningColumns() {
    return getColumnNames();
  }

  public int getPartitioningColumnsCount() {
    return 1;
  }

  public final boolean isPartitioningKeyThePrimaryKey() {
    return isPrimaryKeyPartitioningKey;
  }

  public GemFireContainer getGlobalIndexContainer() {
    return this.globalIndexContainer;
  }

  /**
   * return true if resolution given region key will require global index lookup
   */
  public abstract boolean requiresGlobalIndex();

  public boolean requiresConnectionContext() {
    return false;
  }

  /**
   * set single hop related information in the SingleHopInformation object
   * 
   * @throws StandardException
   */
  public abstract void setResolverInfoInSingleHopInfoObject(
      SingleHopInformation info) throws StandardException;

  public static GemFireContainer getContainerOfGlobalIndex(TableDescriptor td,
      Map<String, Integer> pkMap) {
    ConglomerateDescriptorList cdl = td.getConglomerateDescriptorList();
    Iterator<?> itr = cdl.iterator();
    GemFireContainer globalIndexContainer = null;
    LogWriterI18n logger = Misc.getI18NLogWriter();
    while (itr.hasNext()) {
      ConglomerateDescriptor cd = (ConglomerateDescriptor)itr.next();
      if (cd.isIndex() || cd.isConstraint()) {
        IndexDescriptor id = cd.getIndexDescriptor();
        if (id.indexType().equals(GfxdConstants.GLOBAL_HASH_INDEX_TYPE)) {
          int[] baseColPos = id.baseColumnPositions();
          if (baseColPos.length == pkMap.size()) {
            int cnt = 0;
            for (int colPos : baseColPos) {
              cnt++;
              boolean found = false;
              for (Integer idx : pkMap.values()) {
                if (idx.intValue() == colPos) {
                  found = true;
                  break;
                }
              }
              if (!found) {
                break; // Break from for (int colPos : baseColPos) loop
              }
              else if (cnt == (baseColPos.length)) {
                // This means we have found all the base positions
                globalIndexContainer = Misc.getMemStore().getContainer(
                    ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT,
                        cd.getConglomerateNumber()));

                if (logger.fineEnabled()) {
                  logger.fine("conglomerate name = " + cd.getConglomerateName()
                      + " and conglomeratedescriptor name = "
                      + cd.getDescriptorName());
                }
              }
            }
          }
        }
      }
    }
    return globalIndexContainer;
  }

  protected void setGlobalIndexCaching(GemFireContainer globalIndexContainer) throws StandardException {
    GemFireContainer basecontainer = globalIndexContainer.getBaseContainer();
    basecontainer.setGlobalIndexCaching();
  }

  protected void updateGlobalIndexCache(RegionKey gfKey, Object routingObject) {
    if (this.globalIndexContainer.getBaseContainer().getGlobalIndexCache() != null) {
      final Serializable globalIndexKey;
      final int size = gfKey.nCols();
      if (size == 1) {
        globalIndexKey = gfKey.getKeyColumn(0);
      }
      else {
        final DataValueDescriptor[] dvds = new DataValueDescriptor[size];
        gfKey.getKeyColumns(dvds);
        globalIndexKey = new CompositeRegionKey(dvds);
      }
      this.globalIndexContainer.getBaseContainer().updateCache(globalIndexKey,
          routingObject);
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      if (observer != null) {
        observer.afterPuttingInCached(globalIndexKey, routingObject);
      }
    }
  }
  
  protected static Object getRoutingObjectFromGlobalIndex(
      final GemFireContainer globalIndexContainer, final RegionKey gfKey) {

    // TODO: somehow check that this should never be called on a datastore node

    final LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    // Kneeraj: lcc can be null if the node has started to go down.
    if (lcc == null) {
      Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);
    }
    // expect that lcc is always non-null here since this should always
    // be invoked in the context of derby i.e. either at the query node
    // or via function execution; this should *never* get invoked via
    // distribution message execution since it surely means that global
    // index lookup has already taken place at the query node and this will
    // end up in another global index lookup at store node which is incorrect
    // behaviour; instead the source node should send the routing object as
    // callback argument
    // assert lcc != null: "Expected LanguageConnectionContext to be non-null";

    final GemFireContainer baseContainer = globalIndexContainer.getBaseContainer();
    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
    final boolean possibleDuplicate = (lcc != null ? lcc.isPossibleDuplicate()
        : false);
    Object result = null;
    PartitionedRegion globalIndxRgn = (PartitionedRegion)globalIndexContainer
        .getRegion();
    final Serializable globalIndexKey;
    final int size = gfKey.nCols();
    if (size == 1) {
      globalIndexKey = gfKey.getKeyColumn(0);
    }
    else {
      final DataValueDescriptor[] dvds = new DataValueDescriptor[size];
      gfKey.getKeyColumns(dvds);
      globalIndexKey = new CompositeRegionKey(dvds);
    }
    CacheMap globalIndexCache = null;
    try {
      if (observer != null) {
        observer.beforeGlobalIndexLookup(lcc, globalIndxRgn, globalIndexKey);
      }
      if (baseContainer.cachesGlobalIndex()) {
        globalIndexCache = baseContainer.getGlobalIndexCache();
        Object cachedVal = globalIndexCache.get(globalIndexKey);

        if (observer != null) {
          observer.beforeReturningCachedVal(globalIndexKey, cachedVal);
        }
        if (cachedVal != null) {
          return cachedVal;
        }
      }

      // using the bucketId itself as routing object will ensure that the
      // global index lookup function is routed to the correct node
      Serializable globalIndexRoutingObject = Integer
          .valueOf(PartitionedRegionHelper.getHashKey(globalIndxRgn,
              globalIndexKey));
      GfxdSingleResultCollector rc = new GfxdSingleResultCollector();
      result = FunctionUtils
          .executeFunctionOnRegionWithArgs(
              globalIndxRgn,
              globalIndexKey,
              (globalIndxRgn.includeHDFSResults() ? HdfsGlobalIndexLookupFunction.ID
                  : GlobalIndexLookupFunction.ID), rc, false,
              possibleDuplicate, true, null, Collections
                  .<Object> singleton(globalIndexRoutingObject));
    } catch (StandardException ex) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "Exception while looking up global index", ex);
    }
    if (observer != null) {
      observer.afterGlobalIndexLookup(lcc, globalIndxRgn, globalIndexKey,
          result);
    }
    if (result == null) {
      throw new EntryNotFoundException("No global index entry for: "
          + globalIndexKey);
    }
    if (globalIndexCache != null) {
      globalIndexCache.put(globalIndexKey, result);
      if (observer != null) {
        observer.afterPuttingInCached(globalIndexKey, result);
      }
    }
    return result;
  }

  protected static int getIndexInPrimaryKey(Map<String, Integer> pkMap,
      int partitioningColIdxInPKey) {
    int[] pseudoPKIndexes = new int[pkMap.size()];
    int index = 0;
    for (Integer idx : pkMap.values()) {
      pseudoPKIndexes[index++] = idx.intValue();
    }
    Arrays.sort(pseudoPKIndexes);
    int ret = -1;
    for (index = 0; index < pseudoPKIndexes.length; ++index) {
      if (partitioningColIdxInPKey == pseudoPKIndexes[index]) {
        ret = index;
        break;
      }
    }
    return ret;
  }

  protected static List<Integer> getIndexesInPrimaryKey(
      Map<String, Integer> pkMap, String[] partitionColumnNames) {
    TreeMap<Integer, Integer> pkIndexes = new TreeMap<Integer, Integer>();
    for (Integer idx : pkMap.values()) {
      pkIndexes.put(idx, null);
    }
    int index = 0;
    for (Map.Entry<Integer, Integer> pkEntry : pkIndexes.entrySet()) {
      pkEntry.setValue(Integer.valueOf(index++));
    }
    ArrayList<Integer> partitionIndexesInPk = new ArrayList<Integer>(
        partitionColumnNames.length);
    for (String colName : partitionColumnNames) {
      Integer idx = pkMap.get(colName);
      if (idx == null) {
        throw new AssertionError("Expected to find index for column name "
            + colName);
      }
      Integer pkIdx = pkIndexes.get(idx);
      assert pkIdx != null: "Expected to find index for column index " + idx;
      partitionIndexesInPk.add(pkIdx);
    }
    return partitionIndexesInPk;
  }

  protected static boolean checkIfArrayContainsSameStrings(String[] arr1,
      String[] arr2) {
    if (arr1 == null && arr2 == null) {
      return true;
    }
    else if (arr1 == null || arr2 == null) {
      return false;
    }
    if (arr1.length != arr2.length) {
      return false;
    }
    for (int i = 0; i < arr1.length; i++) {
      boolean found = false;
      String tmpStrFromDD = arr1[i];
      for (int j = 0; j < arr2.length; j++) {
        if (arr2[j].equalsIgnoreCase(tmpStrFromDD)) {
          found = true;
          break;
        }
      }
      if (!found) {
        return false;
      }
    }
    return true;
  }

  protected static boolean checkIfArray1SubsetOfArray2(String[] arr1,
      String[] arr2) {
    if (arr1 == null && arr2 == null) {
      return true;
    }
    else if (arr1 == null || arr2 == null) {
      return false;
    }
    if (arr1.length > arr2.length) {
      return false;
    }
    for (int i = 0; i < arr1.length; i++) {
      boolean found = false;
      String tmpStrFromDD = arr1[i];
      for (int j = 0; j < arr2.length; j++) {
        if (arr2[j].equalsIgnoreCase(tmpStrFromDD)) {
          found = true;
          break;
        }
      }
      if (!found) {
        return false;
      }
    }
    return true;
  }

  protected static void setRoutingObjectInCallbackArg(Object routingObject,
      Object key, EntryEventImpl eeImpl, EntryOperationImpl deOp,
      boolean isEntryEventImpl, GfxdCallbackArgument sca) {
    if (routingObject == null) {
      throw new InternalGemFireError("routing object should have been "
          + "determined by now for key: " + key);
    }
    if (sca != null) {
      if (sca.isFixedInstance()) {
        // We need to change the GfxdCalbackArgument
        sca = GemFireXDUtils.wrapCallbackArgs(routingObject, null, false,
            true /* thread local type */, sca.isCacheLoaded(),
            true /* isPkBased */, false /* skipListeners flag */, false, false);
        if (isEntryEventImpl) {
          eeImpl.setCallbackArgument(sca);
        }
        else {
          deOp.setCallbackArgument(sca);
        }
      }
      else {
        sca.setRoutingObject((Integer)routingObject);
      }
    }
    else if (isEntryEventImpl) {
      eeImpl.setCallbackArgument(routingObject);
    }
    else {
      deOp.setCallbackArgument(routingObject);
    }
  }

  protected static DataValueDescriptor getDVDFromConstantNode(
      ConstantNode node, ColumnReference colRef) throws StandardException {
    if (node == null) {
      return null;
    }
    DataValueDescriptor val = node.getValue();
    if (val == null || val.isNull()) {
      return null;
    }
    DataTypeDescriptor dtd = colRef.getTypeServices();
    if (val.getTypeFormatId() != dtd.getDVDTypeFormatId()) {
      DataValueDescriptor dvd = dtd.getNull();
      dvd.setValue(val);
      return dvd;
    }
    return val;
  }

  protected boolean isValidTypeForSingleHop(int[] typeFormatId,
      SingleHopInformation sinfo) throws StandardException {
    assert sinfo != null;
    boolean allSupportedTypes = checkIfAllTypesSupported(typeFormatId);
    if (allSupportedTypes) {
      Map<InternalDistributedMember, String> mbrToServerMap = GemFireXDUtils
          .getGfxdAdvisor().getAllNetServersWithMembers();
      // now get the bucket information and set in the single hop info object
      PartitionedRegion region = (PartitionedRegion)this.gfContainer
          .getRegion();
      final int numBuckets = this.numBuckets;
      int redundancy = region.getPartitionAttributes().getRedundantCopies();
      RegionAdvisor rAdvisor = region.getRegionAdvisor();
      ArrayList bansiList = new ArrayList();
      for (int bid = 0; bid < numBuckets; bid++) {
        BucketAdvisor ba = rAdvisor.getBucketAdvisor(bid);
        InternalDistributedMember pmbr = ba.getPrimary();
        Set<InternalDistributedMember> bOwners = ba.getProxyBucketRegion()
            .getBucketOwners();
        bOwners.remove(pmbr);
        String primaryServer = mbrToServerMap.get(pmbr);
        if (primaryServer == null) {
          if (SanityManager.TraceSingleHop) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                "GfxdPartitionResolver::isValidTypeForSingleHop server "
                    + "location of primary bucket server corresponding "
                    + "to bucketId=" + bid + " for table=" + this.tableName
                    + " not available");
          }
          primaryServer = "";
        }
        String[] secondaryServers = null;
        // if (!skipSecondaries) {
        secondaryServers = new String[redundancy];
        for(int i=0; i<redundancy; i++) {
          secondaryServers[i] = "";
        }
        int idx = 0;
        for (InternalDistributedMember mbr : bOwners) {
          String secServer = mbrToServerMap.get(mbr);
          if (secServer == null) {
            secServer = "";
          }
          secondaryServers[idx] = secServer;
          idx++;
        }

        BucketAndNetServerInfo bansi = new BucketAndNetServerInfo(bid,
            primaryServer, secondaryServers);
        bansiList.add(bansi);
      }
      sinfo.setBucketAndNetworkServerInfoList(bansiList);
      sinfo.setTotalNumberOfBuckets(region.getTotalNumberOfBuckets());
      region.getTotalNumberOfBuckets();
      return true;
    }
    return false;
  }

  /* first approach
  protected boolean isValidTypeForSingleHop(int[] typeFormatId,
      SingleHopInformation sinfo) throws StandardException {
    assert sinfo != null;
    boolean allSupportedTypes = checkIfAllTypesSupported(typeFormatId);
    boolean skipSecondaries = false;
    if (allSupportedTypes) {
      Map<InternalDistributedMember, String> mbrToServerMap = GemFireXDUtils
          .getGfxdAdvisor().getAllNetworkServersAndCorrespondingMemberMapping();
      // now get the bucket information and set in the single hop info object
      PartitionedRegion region = (PartitionedRegion)this.gfContainer
          .getRegion();
      RegionAdvisor rAdvisor = region.getRegionAdvisor();
      // try to create all buckets if not already created.
      for (int bid = 0; bid < numBuckets; bid++) {
        BucketAdvisor ba = rAdvisor.getBucketAdvisor(bid);
        if (ba.getBucketRedundancy() < 0) {
          if (SanityManager.TraceSingleHop) {
            SanityManager
                .DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                    "GfxdPartitionResolver::isValidTypeForSingleHop "
                        + "CREATE_ALL_BUCKETS_INTERNAL being called for: "
                        + region);
          }
          GfxdSystemProcedures.CREATE_ALL_BUCKETS_INTERNAL(region, tableName);
          try {
            if (SanityManager.TraceSingleHop) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                  "GfxdPartitionResolver::isValidTypeForSingleHop "
                      + "REBALANCE being called for: " + region,
                  new Exception());
            }
            GfxdSystemProcedures.REBALANCE_ALL_BUCKETS();
          } catch (SQLException sqle) {
            throw Misc.wrapSQLException(sqle, sqle);
          }
          break;
        }
      }
      ArrayList bansiList = new ArrayList();
      for (int bid = 0; bid < numBuckets; bid++) {
        BucketAdvisor ba = rAdvisor.getBucketAdvisor(bid);
        InternalDistributedMember pmbr = ba.getPrimary();
        Set<InternalDistributedMember> bOwners = ba.getProxyBucketRegion()
            .getBucketOwners();
        bOwners.remove(pmbr);
        String primaryServer = mbrToServerMap.get(pmbr);
        if (primaryServer == null) {
          sinfo
              .setResolverType(SingleHopInformation.NO_SINGLE_HOP_AS_PRIMARY_NOT_DETERMINED);
          bansiList.clear();
          if (SanityManager.TraceSingleHop) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                "GfxdPartitionResolver::isValidTypeForSingleHop server "
                    + "location of primary bucket server corresponding "
                    + "to bucket id: " + bid + " for region: " + this.tableName
                    + "not available, won't be any single hop");
          }
          return false;
        }
        String[] secondaryServers = null;
        if (!skipSecondaries) {
          secondaryServers = new String[bOwners.size()];
          int idx = 0;
          for (InternalDistributedMember mbr : bOwners) {
            String secServer = mbrToServerMap.get(mbr);
            if (secServer == null) {
              secondaryServers = null;
              sinfo.setSecondaryServerNotDeterminedForSomeBuckets();
              skipSecondaries = true;
              if (SanityManager.TraceSingleHop) {
                SanityManager.DEBUG_PRINT(SanityManager.TRACE_SINGLE_HOP,
                    "GfxdPartitionResolver::isValidTypeForSingleHop "
                        + "server location of secondary bucket server "
                        + "corresponding to bucket id: " + bid
                        + " for region: " + this.tableName
                        + "not available, only primaries will be used "
                        + "at client side");
              }
              break;
            }
            secondaryServers[idx] = secServer;
            idx++;
          }
        }
        // Misc.getCacheLogWriter().info("KN: for bid: " + bid +
        // ", primaryServer is: " + primaryServer + " and secondaryServers is: "
        // + secondaryServers + " primary member is: " + pmbr);
        BucketAndNetServerInfo bansi = new BucketAndNetServerInfo(bid,
            primaryServer, secondaryServers);
        bansiList.add(bansi);
      }
      sinfo.setBucketAndNetworkServerInfoList(bansiList);
      sinfo.setTotalNumberOfBuckets(region.getTotalNumberOfBuckets());
      region.getTotalNumberOfBuckets();
      return true;
    }
    return false;
  }
  */
  private boolean checkIfAllTypesSupported(int[] typeFormatId) {
    assert typeFormatId != null;
    boolean allSupported = true;
    for (int i = 0; i < typeFormatId.length; i++) {
      if (!SingleHopInformation.isSupportedTypeForSingleHop(typeFormatId[i])) {
        allSupported = false;
        break;
      }
    }
    return allSupported;
  }

  /**
   * Execution function to perform global index lookup. This is used instead of
   * region methods to avoid distributed deadlocks for cases like a constraint
   * check on a put operation that ends up invoking global index lookup for the
   * referenced table (see bugs #40296 and #40208)
   * 
   * @author swale
   * @since 6.0
   */
  @SuppressWarnings("serial")
  public static class GlobalIndexLookupFunction implements Function,
      Declarable {

    private final static String ID = "gfxd-GlobalIndexLookupFunction";

    protected transient boolean isQueryHdfs = false;
    
    /**
     * Added for tests that use XML for comparison of region attributes.
     * 
     * @see Declarable#init(Properties)
     */
    @Override
    final public void init(Properties props) {
      // nothing required for this function
    }

    @Override
    public void execute(FunctionContext context) {

      assert context instanceof RegionFunctionContext;

      RegionFunctionContext prContext = (RegionFunctionContext)context;
      PartitionedRegion region = (PartitionedRegion)prContext.getDataSet();
      Object indexKey = prContext.getArguments();

      Serializable routingObject = null;
      try {
        int bucketId = PartitionedRegionHelper.getHashKey(region, indexKey);
        // force local execution flag so we always get back deserialized value
        Object obj = region.getDataView().getLocally(indexKey, null, bucketId,
            region, false, true, null, null, false,
            region.includeHDFSResults() || isQueryHdfs);
        if (obj != null && !Token.isInvalid(obj)) {
          routingObject = ((GlobalRowLocation)obj).getRoutingObject();
        }
      } catch (Exception ex) {
        throw new FunctionExecutionException(ex);
      }
      context.getResultSender().lastResult(routingObject);
    }

    @Override
    public String getId() {
      return ID;
    }

    @Override
    final public boolean hasResult() {
      return true;
    }

    @Override
    public boolean optimizeForWrite() {
      return false;
    }

    @Override
    final public boolean isHA() {
      return true;
    }
  }

  /**
   * Note: If queryHdfs=true
   * 
   * @author vivekb
   *
   */
  @SuppressWarnings("serial")
  public static final class HdfsGlobalIndexLookupFunction extends
      GlobalIndexLookupFunction {
    
    private final static String ID = "hdfs-GlobalIndexLookupFunction";
    
    @Override
    public void execute(FunctionContext context) {
      this.isQueryHdfs = true;
      super.execute(context);
    }
    
    @Override
    public boolean optimizeForWrite() {
      return true;
    }
    
    @Override
    public String getId() {
      return ID;
    }
  }
}
