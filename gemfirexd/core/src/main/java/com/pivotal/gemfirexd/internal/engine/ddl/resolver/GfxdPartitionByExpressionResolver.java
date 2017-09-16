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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Vector;

import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.AbstractRegion;
import com.gemstone.gemfire.internal.cache.ColocationHelper;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.expression.ExpressionCompiler;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This class encapsulates the resolver when using a generic expression in the
 * PARTITION BY clause. Now after the PARTITION BY COLUMN case is treated as a
 * special case of expressions, this also takes care of resolving when only
 * column references are involed. For a generic expression it keeps a compiled
 * activation using derby, while for the column reference case it directly
 * calculates the hash code by XORing the hash codes of the individual columns.
 * Since the same resolver and activation instance can be used by multiple
 * threads, it makes use of a ThreadLocal object to store the DVDs for a
 * particular call to getRoutingObject() for the expression evaluator class.
 *
 * @author kneeraj
 * @author swale
 */
public final class GfxdPartitionByExpressionResolver extends
    GfxdPartitionResolver {

  private final ExpressionCompiler exprCompiler;

  private int[] columnPositionsInRow;

  private int[] columnPositionsInKey;

  private boolean isSubSetOfPrimary;

  private boolean requiresSerializedHash;

  private boolean customHashing;

  /**
   * this stores the partitioning columns in the same order as given in the
   * partition by clause; can be used to check for compatible types with a
   * colocated table for example
   */
  private String[] partColsOrigOrder;

  /** map of partitioning column name to partitioning index (0 based) */
  private final Map<String, Integer> columnToIndexMap = new HashMap<>();

  private boolean defaultPartitioning;

  private String toStringString;

  private int[] typeFormatIdArray;

  private boolean snappyStore = Misc.getMemStore().isSnappyStore();

  public GfxdPartitionByExpressionResolver() {
    this.defaultPartitioning = true;
    this.exprCompiler = null;
  }

  public GfxdPartitionByExpressionResolver(ValueNode node) {
    if (node instanceof ColumnReference) {
      String columnName = node.getColumnName();
      this.partColsOrigOrder = new String[1];
      this.partColsOrigOrder[0] = columnName;
      this.columnToIndexMap.put(columnName, 0);
      this.partitionColumnNames = this.partColsOrigOrder.clone();
      this.exprCompiler = null;
    }
    else {
      this.exprCompiler = new ExpressionCompiler(node, this.columnToIndexMap,
          "PARTITION BY");
    }
  }

  public GfxdPartitionByExpressionResolver(Object[] columns) {
    this.partColsOrigOrder = new String[columns.length];
    for (int index = 0; index < columns.length; ++index) {
      String columnName = ((ColumnReference)columns[index]).getColumnName();
      this.partColsOrigOrder[index] = columnName;
      this.columnToIndexMap.put(columnName, index);
    }
    this.partitionColumnNames = this.partColsOrigOrder.clone();
    this.exprCompiler = null;
  }

  private GfxdPartitionByExpressionResolver(ExpressionCompiler compiler) {
    this.exprCompiler = compiler;
  }

  @Override
  public void updateDistributionDescriptor(DistributionDescriptor desc) {
    String[] descCols = desc.getPartitionColumnNames();
    if (descCols != null) {
      this.partColsOrigOrder = descCols.clone();
      this.partitionColumnNames = this.partColsOrigOrder.clone();
      this.isPrimaryKeyPartitioningKey = false;
    }
    else if (this.partColsOrigOrder != null
        && this.partColsOrigOrder.length > 0
        && desc.getPolicy() != DistributionDescriptor.PARTITIONBYGENERATEDKEY) {
      desc.setPartitionColumnNames(this.partColsOrigOrder);
    }
  }

  @Override
  public void bindExpression(FromList fromList, SubqueryList subqueryList,
      Vector<?> aggregateVector) throws StandardException {
    // the node can be null for a cloned resolver which will have generated
    // method and activation already copied
    // it will also be null for partitioning by column or columns
    if (this.exprCompiler != null) {
      this.partColsOrigOrder = this.exprCompiler.bindExpression(fromList,
          subqueryList, aggregateVector);
      this.partitionColumnNames = this.partColsOrigOrder.clone();
    }
    this.toStringString = makeToStringString();
  }

  private String makeToStringString() {
    final StringBuilder sb = new StringBuilder(
        "GfxdPartitionByExpressionResolver@")
        .append(Integer.toHexString(System.identityHashCode(this))).append('(')
        .append(getQualifiedTableName()).append("): columnNames=")
        .append(Arrays.toString(this.partitionColumnNames))
        .append(" isPrimaryKeyPartitionKey=")
        .append(this.isPrimaryKeyPartitioningKey).append(" isSubSetOfPrimary=")
        .append(this.isSubSetOfPrimary).append(" defaultPartitioning=")
        .append(this.defaultPartitioning).append(" globalIndexRegion=")
        .append(this.globalIndexContainer).append(" requiresSerializedHash=")
        .append(this.requiresSerializedHash);
    if (this.exprCompiler != null) {
      sb.append(" expression=").append(
          this.exprCompiler.getCanonicalizedExpression());
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return this.toStringString;
  }

  @Override
  public String getDDLString() {
    if (this.exprCompiler != null) {
      return "PARTITION BY (" + this.exprCompiler.getCanonicalizedExpression()
          + ')';
    }
    else if (this.isPrimaryKeyPartitioningKey) {
      return "PARTITION BY PRIMARY KEY";
    }
    else {
      return "PARTITION BY COLUMN ("
          + GemFireXDUtils.toCSV(this.partitionColumnNames) + ')';
    }
  }

  public void setPartitionByPrimaryKey() {
    this.isPrimaryKeyPartitioningKey = true;
    this.isSubSetOfPrimary = true;
    this.columnPositionsInKey = null;
    this.defaultPartitioning = false;
  }

  public boolean isDefaultPartitioning() {
    return this.defaultPartitioning;
  }

  private boolean checkColumnsCompletelyPartOfPrimary(
      final Map<String, Integer> pkMap) {
    this.isPrimaryKeyPartitioningKey = false;
    if ((this.isSubSetOfPrimary = ExpressionCompiler
        .checkColumnsPartOfPrimaryKey(pkMap, this.partColsOrigOrder))) {
      this.isPrimaryKeyPartitioningKey =
          (pkMap.size() == this.partColsOrigOrder.length);
      return true;
    }
    else {
      return false;
    }
  }

  public void setCustomHashing(boolean input){
    this.customHashing = input;
  }

  private void setPrimaryColumnNames(Collection<String> pkColumnNames) {
    assert pkColumnNames != null && pkColumnNames.size() > 0;
    int cnt = 0;
    this.partColsOrigOrder = new String[pkColumnNames.size()];
    for (String pkColumnName : pkColumnNames) {
      this.partColsOrigOrder[cnt] = pkColumnName;
      this.columnToIndexMap.put(pkColumnName, cnt);
      ++cnt;
    }
    this.partitionColumnNames = this.partColsOrigOrder.clone();
  }

  @Override
  public void setColumnInfo(TableDescriptor td, Activation activation)
      throws StandardException {
    if (td == null) {
      throw new AssertionError(
          "GfxdPartitionByExpressionResolver: td cannot be null");
    }


    final DistributionDescriptor distDescp = td.getDistributionDescriptor();
    final LanguageConnectionContext lcc = activation
        .getLanguageConnectionContext();

    final Map<String, Integer> pkMap = GemFireXDUtils
        .getPrimaryKeyColumnNamesToIndexMap(td, lcc);
    if (this.defaultPartitioning) {
      // this is default partitioning case
      if (distDescp.getPolicy() == DistributionDescriptor
          .PARTITIONBYGENERATEDKEY) {
        // this is the case of partitioning by generated key column
        this.partColsOrigOrder = new String[0];
        this.partitionColumnNames = new String[0];

        // check for the special case of no-PK to PK by ALTER TABLE ADD
        // CONSTRAINT in which case there will be no longer any generated column
        // in the table, so have to use the PK for routing
        if (pkMap != null && pkMap.size() > 0) {
          setPartitionByPrimaryKey();
        }
        else {
          // For the case when generated key is the partitioning column we
          // set the isPrimaryKeyPartitionKey as false but isSubSetOfPrimary
          // as true. This is to return false in GfxdPartitionResolver#
          // isPartitioningKeyThePrimaryKey but allow using the region key for
          // hashcode calculation in getRoutingObject().
          this.isPrimaryKeyPartitioningKey = false;
          this.isSubSetOfPrimary = true;
          this.columnPositionsInKey = null;
          this.columnPositionsInRow = null;
          this.columnToIndexMap.clear();
          this.toStringString = makeToStringString();
          return;
        }
      }
      else {
        this.partColsOrigOrder = distDescp.getPartitionColumnNames();
        this.partitionColumnNames = this.partColsOrigOrder.clone();
        this.masterTable = distDescp.getColocateTableName();
      }
    }
    if ((this.partColsOrigOrder == null || this.partColsOrigOrder.length == 0)
        && this.isPrimaryKeyPartitioningKey) {
      // we should have validated PK presence already in DDN
      assert pkMap != null && pkMap.size() > 0;
      setPrimaryColumnNames(pkMap.keySet());
      this.isSubSetOfPrimary = true;
    }
    else if (pkMap != null) {
      if (!checkColumnsCompletelyPartOfPrimary(pkMap)) {
        this.globalIndexContainer = GfxdPartitionResolver
            .getContainerOfGlobalIndex(td, pkMap);
        setGlobalIndexCaching(this.globalIndexContainer);
      }
    }
    this.columnPositionsInKey = null;
    // Set the indexes of the GemFireKey in idxOfColsInKey when
    // PK > partitioning and partitioning columns are not contiguous from
    // the very start of array. So if PK is {part1, part2, other3} where
    // first two are part of partitioning columns we can still take the
    // key DVD[] as such during expression evaluation. Also re-arrange
    // the partitioning columns for the contiguous case if required since
    // then getRoutingObject() will not need to do the re-arrangement.
    if (this.isSubSetOfPrimary) {
      boolean isContiguous = true;
      HashSet<String> partCols = new HashSet<>();
      Collections.addAll(partCols, this.partColsOrigOrder);
      int cnt = 0;
      if (pkMap != null) {
        for (String pkCol : pkMap.keySet()) {
          if (partCols.size() == 0) {
            break;
          }
          if (!partCols.remove(pkCol)) {
            isContiguous = false;
            break;
          }
          this.partitionColumnNames[cnt++] = pkCol;
        }
      }
      if (!isContiguous) {
        // if the partitioning columns are not contiguous then populate the
        // idxOfColsInKey array
        this.partitionColumnNames = this.partColsOrigOrder.clone();
        final int numPartCols = this.partColsOrigOrder.length;
        if (numPartCols >= 1) {
          this.columnPositionsInKey = new int[this.partColsOrigOrder.length];
          int keyIndex;
          for (int index = 0; index < this.partColsOrigOrder.length; ++index) {
            String partCol = this.partColsOrigOrder[index];
            keyIndex = 0;
            for (String indexCol : pkMap.keySet()) {
              if (indexCol.equals(partCol)) {
                break;
              }
              ++keyIndex;
            }
            assert keyIndex < pkMap.size();
            this.columnPositionsInKey[index] = keyIndex + 1;
          }
        }
      }
    }
    this.columnPositionsInRow = new int[this.partitionColumnNames.length];
    this.columnToIndexMap.clear();
    HashMap<String, Integer> columnMap = GemFireXDUtils
        .getColumnNamesToIndexMap(td, true);
    for (int index = 0; index < this.partitionColumnNames.length; ++index) {
      String columnName = this.partitionColumnNames[index];
      Integer colIndex = columnMap.get(columnName);
      if (colIndex == null) {
        // should never happen
        throw StandardException.newException(SQLState.LANG_SYNTAX_ERROR,
            "The partition column (" + columnName
                + ") does not exist in the table's column list for table "
                + td.getQualifiedName());
      }
      // set the column to index map
      this.columnToIndexMap.put(columnName, index);
      // set the indexes of partitioning columns
      this.columnPositionsInRow[index] = colIndex;
    }
    if (this.exprCompiler != null) {
      // Check exprCompiler to make sure that this executed only
      // for the first time. This should not be executed for ALTER 
      // TABLE executeConstantAction.
      if (this.exprCompiler.compileExpression(td, lcc)) {
        this.requiresSerializedHash = false;
      }
    }
    else {
      // if there is a parent, then use its policy to compute routing object
      final String colocatedTable;
      if ((colocatedTable = distDescp.getColocateTableName()) != null) {
        GfxdPartitionByExpressionResolver resolver =
            (GfxdPartitionByExpressionResolver)GemFireXDUtils.getResolver(
                (AbstractRegion)Misc.getRegionForTableByPath(
                    colocatedTable, true));
        if (resolver != null) {
          this.requiresSerializedHash = resolver.requiresSerializedHash;
        }
      } else {
        // in this case we will calculate hash from serialized value during puts
        this.requiresSerializedHash = !customHashing;
      }
      // update requiresSerializedHash for any child tables (#43628)
      PartitionedRegion pr = (PartitionedRegion)Misc
          .getRegionForTableByPath(Misc.getFullTableName(td, lcc), false);
      if (pr != null) {
        Map<String, PartitionedRegion> colocatedRegions = ColocationHelper
            .getAllColocationRegions(pr);
        for (PartitionedRegion childPR : colocatedRegions.values()) {
          GfxdPartitionByExpressionResolver resolver =
              (GfxdPartitionByExpressionResolver)GemFireXDUtils.getResolver(childPR);
          if (resolver != null) {
            resolver.requiresSerializedHash = this.requiresSerializedHash;
          }
        }
      }
    }
    distDescp.setPartitionColumnNames(this.partitionColumnNames);
    distDescp.setColumnPositions(this.columnPositionsInRow);
    this.toStringString = makeToStringString();
    fillTypeFormatId(td);
  }

  @Override
  public GfxdPartitionResolver cloneObject() {
    GfxdPartitionByExpressionResolver clone =
      new GfxdPartitionByExpressionResolver(this.exprCompiler);
    clone.defaultPartitioning = this.defaultPartitioning;
    clone.partColsOrigOrder = this.partColsOrigOrder.clone();
    clone.partitionColumnNames = this.partitionColumnNames.clone();
    clone.columnToIndexMap.putAll(this.columnToIndexMap);
    return clone;
  }

  @Override
  public String[] getColumnNames() {
    return this.partitionColumnNames;
  }

  @Override
  public int getPartitioningColumnIndex(String partitionColumn) {
    Integer idx = this.columnToIndexMap.get(partitionColumn);
    if (idx != null) {
      return idx;
    }
    return -1;
  }

  @Override
  public final int getPartitioningColumnsCount() {
    return this.partColsOrigOrder.length;
  }

  private Serializable calcRoutingObject(DataValueDescriptor dvd,
      LanguageConnectionContext lcc) {
    if (this.exprCompiler == null) {
      // case of partitioning by columns
      if (snappyStore && customHashing) {
        return Misc.getUnifiedHashCodeFromDVD(dvd, this.numBuckets);
      } else {
        return Misc.getHashCodeFromDVD(dvd);
      }
    } else {
      // case of partitioning by general expression
      return invokeExpressionEvaluator(dvd, null, lcc);
    }
  }

  private Serializable calcRoutingObject(DataValueDescriptor[] dvds,
      LanguageConnectionContext lcc) {
    if (this.exprCompiler == null) {
      // case of partitioning by some columns or generated primary key
      if (snappyStore && customHashing) {
        return Misc.getUnifiedHashCodeFromDVD(dvds, this.numBuckets);
      } else {
        int hash = 0;
        if (dvds.length == 1) {
          // can be the case of generated primary key
          hash = Misc.getHashCodeFromDVD(dvds[0]);
        } else {
          assert (this.partitionColumnNames.length <= dvds.length);
          for (int index = 0; index < this.partitionColumnNames.length; ++index) {
            hash ^= Misc.getHashCodeFromDVD(dvds[index]);
          }
        }
        return hash;
      }

    } else {
      // case of partitioning by general expression
      return invokeExpressionEvaluator(null, dvds, lcc);
    }
  }

  private Serializable invokeExpressionEvaluator(DataValueDescriptor dvd,
      DataValueDescriptor[] dvds, LanguageConnectionContext lcc) {
    EmbedConnection conn = null;
    boolean contextSet = false;
    try {
      if (lcc == null) {
        lcc = Misc.getLanguageConnectionContext();
        if (lcc == null) {
          // Refer Bug 42810.In case of WAN, a PK based insert is converted into
          // region.put since it bypasses GemFireXD layer, the LCC can be null.
          conn = GemFireXDUtils.getTSSConnection(true, true, false);
          conn.getTR().setupContextStack();
          contextSet = true;
          lcc = conn.getLanguageConnectionContext();
          // lcc can be null if the node has started to go down.
          if (lcc == null) {
            Misc.getGemFireCache().getCancelCriterion()
                .checkCancelInProgress(null);
          }
        }
      }
      final DataValueDescriptor res = this.exprCompiler.evaluateExpression(dvd,
          dvds, lcc);
      if (GemFireXDUtils.TraceConglomRead) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM_READ,
            toString() + ": returning expression result: " + res + "(type: "
                + res.getTypeName() + ",id=" + res.getTypeFormatId() + ')');
      }
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      if (observer != null) {
        observer.afterGetRoutingObject(res);
      }
      return res.hashCode();
    } catch (StandardException ex) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "GfxdPartitionByExpressionResolver: "+this+" unexpected exception", ex);
    } finally {
      if (contextSet) {
        conn.getTR().restoreContextStack();
      }
    }
  }

  @Override
  public Object getRoutingObjectForExpression(
      final DataValueDescriptor expressionValue) {
    assert !this.requiresSerializedHash && this.exprCompiler != null:
      "GfxdPartitionByExpressionResolver.getRoutingObjectForExpression: should"
      + " never use hash based partitioning: " + toString();
    return Misc.getHashCodeFromDVD(expressionValue);
  }

  @Override
  public String getCanonicalizedExpression() {
    return this.exprCompiler != null ? this.exprCompiler
        .getCanonicalizedExpression() : null;
  }

  @Override
  public Object getRoutingKeyForColumn(DataValueDescriptor partColumnValue) {
    assert this.partitionColumnNames.length == 1:
      "GfxdPartitionByExpressionResolver.getRoutingKeyForColumn: should"
      + " be called only if partitioning column count is 1: " + toString();
    if (this.requiresSerializedHash) {
      final DataTypeDescriptor dtd = this.gfContainer.getCurrentRowFormatter()
          .getType(this.columnPositionsInRow[0]);
      return computeHashCode(partColumnValue, dtd, 0);
    }
    return calcRoutingObject(partColumnValue, null);
  }

  @Override
  public final Serializable getRoutingObjectFromDvdArray(
      final DataValueDescriptor[] values) {
    int numColumns = this.columnPositionsInRow.length;
    assert values != null && values.length >= numColumns;
    if (numColumns == 1) {
      final int pos = this.columnPositionsInRow[0];
      if (this.requiresSerializedHash) {
        final DataTypeDescriptor dtd = this.gfContainer
            .getCurrentRowFormatter().getType(pos);
        return computeHashCode(values[pos - 1], dtd, 0);
      }
      return calcRoutingObject(values[pos - 1], null);
    }
    if (this.requiresSerializedHash) {
      int hash = 0;
      int pos;
      final RowFormatter rf = this.gfContainer.getCurrentRowFormatter();
      for (int index = 0; index < numColumns; ++index) {
        pos = this.columnPositionsInRow[index];
        hash = computeHashCode(values[pos - 1], rf.getType(pos), hash);
      }
      return hash;
    }
    DataValueDescriptor[] valuesInOrder = new DataValueDescriptor[numColumns];
    for (int index = 0; index < numColumns; ++index) {
      valuesInOrder[index] = values[this.columnPositionsInRow[index] - 1];
    }
    return calcRoutingObject(valuesInOrder, null);
  }

  @Override
  public Object[] getRoutingObjectsForList(DataValueDescriptor[] keys) {
    assert this.partitionColumnNames.length == 1:
      "GfxdPartitionByExpressionResolver.getRoutingObjectsForList: should"
      + " be called only if partitioning column count is 1: " + toString();
    Object[] routingObjects = new Object[keys.length];
    if (this.requiresSerializedHash) {
      final DataTypeDescriptor dtd = this.gfContainer.getCurrentRowFormatter()
          .getType(this.columnPositionsInRow[0]);
      for (int index = 0; index < keys.length; ++index) {
        routingObjects[index] = computeHashCode(keys[index], dtd, 0);
      }
      return routingObjects;
    }
    for (int index = 0; index < keys.length; ++index) {
      routingObjects[index] = calcRoutingObject(keys[index], null);
    }
    return routingObjects;
  }

  @Override
  // [sumedh] the order of values is actually the order in which the resolver
  // sends the columns back in getColumnNames() so never need to reorder anyway
  public Object getRoutingObjectsForPartitioningColumns(
      DataValueDescriptor[] partitioningColumnObjects) {
    if (this.partitionColumnNames.length == partitioningColumnObjects.length) {
      if (this.requiresSerializedHash) {
        int hash = 0;
        final RowFormatter rf = this.gfContainer.getCurrentRowFormatter();
        for (int index = 0; index < partitioningColumnObjects.length; ++index) {
          hash = computeHashCode(partitioningColumnObjects[index],
              rf.getType(this.columnPositionsInRow[index]), hash);
        }
        return hash;
      }
      return calcRoutingObject(partitioningColumnObjects, null);
    }
    throw new AssertionError("GfxdPartitionByExpressionResolver."
        + "getRoutingObjectsForPartitioningColumns: size of "
        + "partitioningColumnObjects=" + partitioningColumnObjects.length
        + " should be equal to the size of " + "partitioningColumns="
        + this.partitionColumnNames.length + " for " + toString());
  }

  @Override
  public Object[] getRoutingObjectsForRange(DataValueDescriptor lowerBound,
      boolean lowerBoundInclusive, DataValueDescriptor upperBound,
      boolean upperBoundInclusive) {
    if (lowerBound != null && upperBound != null
        && this.partitionColumnNames.length == 1) {
      if (lowerBound.equals(upperBound) && lowerBoundInclusive
          && upperBoundInclusive) {
        if (this.requiresSerializedHash) {
          return new Object[] {computeHashCode(lowerBound,
              this.gfContainer.getCurrentRowFormatter().getType(
                  this.columnPositionsInRow[0]), 0)};
        }
        return new Object[] { calcRoutingObject(lowerBound, null) };
      }
    }
    return null;
  }

  @Override
  public boolean isUsedInPartitioning(String columnToCheck) {
    return this.columnToIndexMap.containsKey(columnToCheck);
  }

  @Override
  public boolean okForColocation(GfxdPartitionResolver rslvr) {
    // [sumedh] do we need to check that the target resolver has matching column
    // types or let the user have different column types?
    if (rslvr instanceof GfxdPartitionByExpressionResolver) {
      GfxdPartitionByExpressionResolver passed =
          (GfxdPartitionByExpressionResolver)rslvr;
      // Minimal check do determine if both have them have expr node at least
      return !(this.exprCompiler != null && passed.exprCompiler == null
          || this.exprCompiler == null && passed.exprCompiler != null);
    }
    return false;
  }

  @Override
  public boolean requiresGlobalIndex() {
    return !this.isSubSetOfPrimary;
  }

  @Override
  public boolean requiresConnectionContext() {
    return this.exprCompiler != null;
  }

  // test method
  public boolean columnsSubsetOfPrimary() {
    return this.isSubSetOfPrimary;
  }

  @Override
  public void setPartitionColumns(String[] partCols, String[] refPartCols) {

    this.partColsOrigOrder = partCols.clone();
    this.partitionColumnNames = partCols.clone();
    /*
    // Try to preserve the order of columns.
    // So first store the mapping of ref table column to this table column.
    // Then for each partitioning column in ref table ordering substitute
    // by this table column name looking from the map populated before.
    HashMap<String, String> refNewColMap = new HashMap<String, String>();
    for (int index = 0; index < partCols.length; ++index) {
      refNewColMap.put(refPartCols[index], partCols[index]);
    }
    this.partColsOrigOrder = new String[partCols.length];
    this.columnToIndexMap.clear();
    for (int index = 0; index < partCols.length; ++index) {
      String partCol = this.partColsOrigOrder[index];
      String newCol = refNewColMap.get(partCol);
      assert newCol != null;
      this.partColsOrigOrder[index] = newCol;
      this.columnToIndexMap.put(newCol, Integer.valueOf(index))
    }
    this.partitionColumnNames = this.partColsOrigOrder.clone();
    */
  }

  /** name of this {@link PartitionResolver} implementation */
  public String getName() {
    return "gfxd-expression-partition-resolver";
  }

  @Override
  protected final boolean isPartitioningSubsetOfKey() {
    return this.isSubSetOfPrimary;
  }

  @Override
  public Object getRoutingObject(Object key, @Unretained final Object val,
      Region<?, ?> region) {
    if (SanityManager.isFineEnabled) {
      gflogger.fine("GfxdPartitionByExpressionResolver:getRoutingObject: "
          + "key=" + key + "; value=" + val);
    }
    Object routingObject;
    final int numCols = getPartitioningColumnsCount();
    RegionKey gfkey = null;
    if (key instanceof RegionKey) {
      gfkey = (RegionKey)key;
    }
    if (this.isSubSetOfPrimary) {
      if (gfkey == null) { // generated key case
        routingObject = getGeneratedKeyRoutingObject(key);
      }
      else if (this.requiresSerializedHash) {
        assert this.exprCompiler == null;
        // calculate hashCode directly from serialized bytes
        final RowFormatter rf;
        final int[] keyPositions;
        // right side object is key bytes
        final CompactCompositeRegionKey ccrk = (CompactCompositeRegionKey)gfkey;
        final ExtraTableInfo tabInfo = ccrk.getTableInfo();
        byte[] kbytes;
        Object kbs = null;
        int tries = 1;
        try {
          for (;;) {
            if ((kbytes = ccrk.getKeyBytes()) != null) {
              rf = tabInfo.getPrimaryKeyFormatter();
              keyPositions = this.columnPositionsInKey;
              break;
            }
            kbs = ccrk.getValueByteSource();
            if (kbs != null) {
              rf = tabInfo.getRowFormatter();
              if (this.columnPositionsInKey == null) {
                // null idxsOfColsInKey indicates that PK >= partitioning cols
                // in contiguous order
                keyPositions = tabInfo.getPrimaryKeyColumns();
              }
              else {
                keyPositions = this.columnPositionsInRow;
              }
              break;
            }
            if ((tries++ % CompactCompositeRegionKey.MAX_TRIES_YIELD) == 0) {
              // enough tries; give other threads a chance to proceed
              Thread.yield();
            }
            if (tries > CompactCompositeRegionKey.MAX_TRIES) {
              throw RegionEntryUtils.checkCacheForNullKeyValue(
                  "GfxdPartitionByExpressionResolver#getRoutingObject");
            }
          }
          routingObject = kbytes != null ? computeHashCode(kbytes, rf,
              keyPositions, numCols) : computeHashCode(kbs, rf, keyPositions,
              numCols);
        } finally {
          if (kbs != null) {
            ccrk.releaseValueByteSource(kbs);
          }
        }
      }
      else {
        if (numCols == 1) {
          if (this.columnPositionsInKey == null) {
            routingObject = calcRoutingObject(gfkey.getKeyColumn(0), null);
          }
          else {
            assert this.columnPositionsInKey.length == 1;
            routingObject = calcRoutingObject(
                gfkey.getKeyColumn(this.columnPositionsInKey[0] - 1), null);
          }
        }
        else {
          final DataValueDescriptor[] dvds = new DataValueDescriptor[numCols];
          if (this.columnPositionsInKey == null) {
            gfkey.getKeyColumns(dvds);
          }
          else {
            // need to re-arrange the key but not for partition by columns case
            // since hashcode calculation can be done in desired order directly
            for (int index = 0; index < this.columnPositionsInKey.length; ++index) {
              dvds[index] = gfkey.getKeyColumn(this.columnPositionsInKey[index] - 1);
            }
          }
          //routingObject = invokeExpressionEvaluator(null, dvds, null);
          routingObject = calcRoutingObject(dvds, null);
        }
      }
    }
    else {
      if (val != null && !(val instanceof GemFireContainer.SerializableDelta)) {
        if (this.requiresSerializedHash) {
          assert this.exprCompiler == null;
          // this case of new row being inserted will always have the current
          // RowFormatter and not any of the previous ones before ALTER TABLEs
          final RowFormatter rf = this.gfContainer.getCurrentRowFormatter();
          // compute hashCode directly from serialized form
          routingObject = computeHashCode(val, rf,
              this.columnPositionsInRow, this.columnPositionsInRow.length);
        }
        else if (numCols == 1) {
          assert this.columnPositionsInRow.length == 1: "unexpected positions "
              + Arrays.toString(this.columnPositionsInRow);
          final DataValueDescriptor dvd = RegionEntryUtils.getDVDFromValue(val,
              this.columnPositionsInRow[0], this.gfContainer);
          routingObject = calcRoutingObject(dvd, null);
        }
        else {
          final DataValueDescriptor[] dvds = RegionEntryUtils
              .getDVDArrayFromValue(val, this.columnPositionsInRow,
                  this.gfContainer);
          routingObject = calcRoutingObject(dvds, null);
        }
      }
      else {
        assert this.globalIndexContainer != null && gfkey != null:
          "expected to find global index in resolver " + this.toString()
          + " and the key: " + gfkey;
        LocalRegion globalIndex = this.globalIndexContainer.getRegion();
        boolean queryHDFS = false;
        if (region instanceof PartitionedRegion) {
          queryHDFS = ((PartitionedRegion) region).includeHDFSResults();
        }
        if (globalIndex instanceof PartitionedRegion) {
          ((PartitionedRegion) globalIndex).setQueryHDFS(queryHDFS);
        }

        // lookup in the global index
        routingObject = getRoutingObjectFromGlobalIndex(
            this.globalIndexContainer, gfkey);
      }
    }
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      Object execRow = null;
      if (val != null && !(val instanceof GemFireContainer.SerializableDelta)) {
        execRow = this.gfContainer.newExecRow(val);
      }
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB, toString()
          + ": returning routingObject=" + routingObject + " for key=" + key
          + ", value=" + (execRow != null ? execRow.toString() : "(NA)"));
    }
    if (this.globalIndexContainer != null) {
      updateGlobalIndexCache(gfkey, routingObject);
    }
    return routingObject;
  }

  public static Integer getGeneratedKeyRoutingObject(Object key) {
    return key.hashCode();
  }

  private static int computeHashCode(final byte[] bytes,
      final RowFormatter rf, final int[] columnPositions, int numPositions) {
    int hash = 0;
    if (columnPositions != null) {
      for (int index = 0; index < numPositions; index++) {
        hash = rf.computeHashCode(columnPositions[index], bytes, hash);
      }
    }
    else {
      for (int position = 1; position <= numPositions; position++) {
        hash = rf.computeHashCode(position, bytes, hash);
      }
    }
    return hash;
  }

  private static int computeHashCode(final byte[][] byteArrays,
      final RowFormatter rf, final int[] columnPositions, int numPositions) {
    int hash = 0;
    if (columnPositions != null) {
      for (int index = 0; index < numPositions; index++) {
        final int position = columnPositions[index];
        hash = rf.computeHashCode(position,
            rf.getColumnAsByteSource(byteArrays, position), hash);
      }
    }
    else {
      for (int position = 1; position <= numPositions; position++) {
        hash = rf.computeHashCode(position,
            rf.getColumnAsByteSource(byteArrays, position), hash);
      }
    }
    return hash;
  }

  private static int computeHashCode(@Unretained final Object val,
      final RowFormatter rf, final int[] columnPositions, int numPositions) {

    @Unretained final OffHeapRow bsRow;
    @Unretained final OffHeapRowWithLobs bsLobs;
    final long bsAddr;
    final int bsLen;
    if (val != null) {
      Class<?> vclass = val.getClass();
      if (vclass == byte[][].class) {
        return computeHashCode((byte[][])val, rf, columnPositions,
            numPositions);
      }
      else if (vclass == byte[].class) {
        return computeHashCode((byte[])val, rf, columnPositions,
            numPositions);
      }
      else if (vclass == OffHeapRow.class) {
        bsRow = (OffHeapRow)val;
        bsLobs = null;
        bsLen = bsRow.getLength();
        bsAddr = bsRow.getUnsafeAddress(0, bsLen);
      }
      else {
        bsRow = null;
        bsLobs = (OffHeapRowWithLobs)val;
        bsLen = bsLobs.getLength();
        bsAddr = bsLobs.getUnsafeAddress(0, bsLen);
      }
    }
    else {
      return 0;
    }

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    int hash = 0;
    int logicalPosition;

    for (int index = 0; index < numPositions; ++index) {
      if (columnPositions != null) {
        logicalPosition = columnPositions[index];
      }
      else {
        logicalPosition = index + 1;
      }

      if (bsRow != null) {
        hash = rf.computeHashCode(logicalPosition, unsafe, bsAddr, bsLen, hash);
      }
      else {
        Object bsLob = rf.getColumnAsByteSource(bsLobs, logicalPosition);
        if (bsLob != null) {
          if (bsLob == bsLobs) {
            hash = rf.computeHashCode(logicalPosition, unsafe, bsAddr, bsLen,
                hash);
          }
          else if (bsLob instanceof OffHeapRow) {
            @Unretained final OffHeapRow ohrow = (OffHeapRow)bsLob;
            final int bytesLen = ohrow.getLength();
            final long memAddr = ohrow.getUnsafeAddress(0, bytesLen);
            hash = rf.computeHashCode(logicalPosition, unsafe, memAddr,
                bytesLen, hash);
          }
          else {
            hash = rf.computeHashCode(logicalPosition, (byte[])bsLob, hash);
          }
        }
      }
    }
    return hash;
  }

  public static int computeHashCode(final DataValueDescriptor dvd,
      final DataTypeDescriptor dtd, int hash) {
    if (dvd != null && !dvd.isNull()) {
      return dvd.computeHashCode(dtd != null ? dtd.getMaximumWidth()
          : 0, hash);
    }
    else {
      // convention is to add a single 0 byte for null value
      // The client side code of calculating routing objects has
      // been made compatible with the below logic of handling
      // nulls. If this change the corresponding methods of the client
      // side for example IntCoulmnRoutingObjectInfo.computeHashCode
      // sjould also change accordingly. Other types for which single hop is
      // supported should also be looked into.
      return ResolverUtils.addByteToBucketHash((byte)0, hash,
          dtd.getTypeId().getTypeFormatId());
    }
  }

  @Override
  public void setResolverInfoInSingleHopInfoObject(SingleHopInformation info)
      throws StandardException {
    if (this.exprCompiler != null || this.partitionColumnNames == null ||
        this.partitionColumnNames.length == 0) {
      // Not supporting partition by expression in the single hop
      // in the first cut of single hop implementation
      info.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
      return;
    }
    // It is a case of partition by column
    info.setResolverType(SingleHopInformation.COLUMN_RESOLVER_FLAG);
    info.setRequiredSerializedHash(this.requiresSerializedHash);
    if (isValidTypeForSingleHop(typeFormatIdArray, info)) {
      info.setTypeFormatIdArray(this.typeFormatIdArray);
    }
    else {
      info.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
    }
  }

  private void fillTypeFormatId(TableDescriptor td) {
    this.typeFormatIdArray = new int[this.partitionColumnNames.length];
    for (int i = 0; i < this.partitionColumnNames.length; i++) {
      this.typeFormatIdArray[i] = td
          .getColumnDescriptor(this.partitionColumnNames[i]).getType()
          .getDVDTypeFormatId();
    }
  }
}
