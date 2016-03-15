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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;

import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.xmlcache.CacheXmlParser;

import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.SerializableDelta;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitor;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.VisitorAdaptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.BetweenOperatorNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ConstantNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.UserTypeConstantNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNodeList;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils.GfxdComparableFuzzy;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils.GfxdRange;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation.PlainRangeValueHolder;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation;

/**
 * Custom partitioning using the {@link PartitionResolver} interface to handle
 * the "PARTITION BY RANGE" GemFire DDL extension.
 * 
 * @author Sumedh Wale
 * @author kneeraj
 * @since 6.0
 */
public class GfxdRangePartitionResolver extends GfxdPartitionResolver {

  /** name of the column to be used for partitioning */
  private String columnName;

  /** list of ranges to be used for range partitioning */
  private final ValueNodeList ranges;

  /**
   * Map that contains the ranges and allows to quickly search for the range of
   * a given value. Also allows to check for overlapping ranges.
   */
  protected final SortedMap<Object, Object[]> rangeMap;

  /**
   * If the partitioning column is the primary key column or one of the column
   * comprising the primary key then indexes to the actual column in the key
   * DVD[] in GemFire key
   */
  private int pkIndexInGemFireKey = -1;

  /**
   * If the partitioning column is not in the primary key then this indexes to
   * the actual position of the partitioning column in the row.
   */
  private int colIndexInVal = -1;

  private ColumnReference colRef;

  private volatile String ddlString;

  /**
   * Constructor that takes the ranges in the form of {@link ValueNodeList}
   * created during parsing to be used for the given {@link ColumnReference}.
   * 
   * @param columnRef
   *          the {@link ColumnReference} for which ranges have been defined
   * @param ranges
   *          the list of ranges to be used for partitioning on the given
   *          column; each {@link ValueNode} in the list is expected to be a
   *          {@link BetweenOperatorNode}
   */
  public GfxdRangePartitionResolver(ColumnReference columnRef,
      ValueNodeList ranges) {
    this.columnName = columnRef.getSQLColumnName();
    this.ranges = ranges;
    this.rangeMap = new TreeMap<Object, Object[]>(
        new ResolverUtils.GfxdRangeComparator("PARTITION BY RANGE ("
            + this.columnName + ')'));
    this.colRef = columnRef;
  }

  /**
   * Constructor to directly populate the resolver with the list of
   * {@link GfxdRange} objects. The <code>command</code> argument of the
   * {@link GfxdRange} is populated by the constructor.
   * 
   * Note: Invoking {@link #bindExpression(FromList, SubqueryList, Vector)}
   * after using this constructor does not make sense and is not allowed.
   * 
   * @param columnName
   *          the name of the column for which ranges have been defined
   * @param ranges
   *          the list of ranges to be used for partitioning on the given
   *          column; each element in the list is expected to be a
   *          {@link GfxdRange} object
   */
  public GfxdRangePartitionResolver(String columnName,
      List<ResolverUtils.GfxdRange> ranges) throws StandardException {
    if (columnName == null || ranges == null) {
      throw StandardException.newException(
          SQLState.LANG_NULL_TO_PRIMITIVE_PARAMETER, "PARTITION BY RANGE",
          "NULL COLUMN NAME OR RANGES");
    }
    final String command = "PARTITION BY RANGE (" + columnName + ')';
    this.ranges = null;
    this.rangeMap = new TreeMap<Object, Object[]>(
        new ResolverUtils.GfxdRangeComparator(command));

    int partitionNum = 1;
    for (ResolverUtils.GfxdRange range : ranges) {
      if (range != null) {
        range.setCommand(command);
        Object[] valarray = new Object[2];
        valarray[0] = range;
        valarray[1] = Integer.valueOf(partitionNum);
        this.rangeMap.put(range, valarray);
        ++partitionNum;
      }
    }
  }

  /** for cloning */
  private GfxdRangePartitionResolver(final ValueNodeList ranges,
      final SortedMap<Object, Object[]> rangeMap) {
    this.ranges = null;
    this.rangeMap = rangeMap;
  }

  @Override
  public boolean isUsedInPartitioning(String columnToCheck) {
    return this.columnName.equals(columnToCheck);
  }

  @Override
  public int getPartitioningColumnIndex(String partitionColumn) {
    if (partitionColumn.equals(this.columnName)) {
      return 0;
    }
    return -1;
  }

  @Override
  public String[] getColumnNames() {
    return this.partitionColumnNames;
  }

  /** name of this {@link PartitionResolver} implementation */
  public String getName() {
    return "gfxd-range-partition-resolver";
  }

  /**
   * Return the ranges as a property. This allows proper conversion to XML by
   * {@link CacheXmlParser}.
   */
  public Properties getConfig() {
    Properties props = new Properties();
    props.setProperty("RANGES", this.toString());
    return props;
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
  @Override
  public void bindExpression(FromList fromList, SubqueryList subqueryList,
      Vector<?> aggregateVector) throws StandardException {
    if (this.columnName == null) {
      throw StandardException.newException(
          SQLState.LANG_NULL_TO_PRIMITIVE_PARAMETER, "PARTITION BY RANGE",
          "NULL COLUMN NAME");
    }
    this.ranges.bindExpression(fromList, subqueryList, aggregateVector);
    // Populate the TreeMap after bind is complete.
    this.ranges.accept(new GfxdRangeListVisitor("PARTITION BY RANGE ("
        + this.columnName + ')'));
  }

  @Override
  public void setColumnInfo(TableDescriptor td, Activation activation)
      throws StandardException {
    if (td == null) {
      throw new AssertionError("GfxdRangePartitionResolver: td cannot be null");
    }
    DistributionDescriptor distDescp = td.getDistributionDescriptor();
    if (distDescp == null) {
      throw new AssertionError(
          "GfxdRangePartitionResolver: distributionDescriptor cannot be null");
    }
    String[] partitionColNames = td.getDistributionDescriptor()
        .getPartitionColumnNames();
    assert partitionColNames != null;
    assert partitionColNames.length == 1;
    assert partitionColNames[0].equals(this.columnName);

    Map<String, Integer> pkMap = GemFireXDUtils
        .getPrimaryKeyColumnNamesToIndexMap(td,
            activation.getLanguageConnectionContext());
    Integer partitioningColIdxInPKey = null;
    // The below condition means that the partitioning column is neither primary
    // key or part of the primarykey.
    if (pkMap == null
        || (partitioningColIdxInPKey = pkMap.get(this.columnName)) == null) {
      // so determine the index of the partitioning column.
      setColumnIndexForNonPrimaryColumn(td);
      if (pkMap != null) {
        this.globalIndexContainer = GfxdPartitionResolver
            .getContainerOfGlobalIndex(td, pkMap);
        setGlobalIndexCaching(this.globalIndexContainer);
      }
    }
    else {
      this.pkIndexInGemFireKey = getIndexInPrimaryKey(pkMap,
          partitioningColIdxInPKey);
      if (pkMap.containsKey(this.columnName) && pkMap.size() == 1) {
        this.isPrimaryKeyPartitioningKey = true;
      }
      this.colIndexInVal = partitioningColIdxInPKey - 1;
    }
    this.partitionColumnNames = new String[] { this.columnName };
  }

  private void setColumnIndexForNonPrimaryColumn(TableDescriptor td)
      throws StandardException {
    HashMap<String, Integer> colNameToIdxMap = GemFireXDUtils
        .getColumnNamesToIndexMap(td, false);
    if (colNameToIdxMap == null) {
      return;
    }
    Integer thisColIdx = colNameToIdxMap.get(this.columnName);
    if (thisColIdx != null) {
      this.colIndexInVal = thisColIdx.intValue();
      // Decrementing it so that adjustment is not necessary while accessing in
      // DVD array while calculating routing object
      --this.colIndexInVal;
    }
  }

  @Override
  protected final boolean isPartitioningSubsetOfKey() {
    return this.pkIndexInGemFireKey != -1;
  }

  @Override
  public Object getRoutingObject(Object key, Object val, Region<?, ?> region) {
    Object routingObject;
    RegionKey gfkey = null;
    if (key instanceof RegionKey) {
      gfkey = (RegionKey)key;
    }
    if (this.pkIndexInGemFireKey != -1) {
      routingObject = getRoutingObjectForValue(gfkey
          .getKeyColumn(this.pkIndexInGemFireKey));
    }
    else {
      if (val != null && !(val instanceof SerializableDelta)) {
        assert this.colIndexInVal != -1: "unexpected colIndexInVal=-1 with val "
            + val;
        // TODO: PERF: avoid deserializing to DVD
        // soubhik: this is required as logicalPostion is 1 based whereas
        // colIndexInval is 0 based, getDVDArrayFromValue gets called
        // as per PartitionByExpressionResolver.
        final DataValueDescriptor idxObj = RegionEntryUtils.getDVDFromValue(val,
            this.colIndexInVal + 1, this.gfContainer);
        routingObject = getRoutingObjectForValue(idxObj);
      }
      else {
        assert this.globalIndexContainer != null && gfkey != null:
          "expected to find global index in resolver " + this.toString();
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
    if (this.globalIndexContainer != null) {
      updateGlobalIndexCache(gfkey, routingObject);
    }
    return routingObject;
  }

  private Serializable getRoutingObjectForValue(final DataValueDescriptor key) {
    final Object[] routingKey = this.rangeMap
        .get(new ResolverUtils.GfxdComparableFuzzy((Comparable)key,
            ResolverUtils.GfxdComparableFuzzy.GE));
    if (routingKey != null && routingKey[1] != null) {
      return (Integer)routingKey[1];
    }
    return Integer.valueOf(key.hashCode());
  }

  @Override
  public Object getRoutingKeyForColumn(DataValueDescriptor key) {
    return this.getRoutingObjectForValue(key);
  }

  @Override
  public Object[] getRoutingObjectsForRange(DataValueDescriptor lowerBound,
      boolean lowerBoundInclusive, DataValueDescriptor upperBound,
      boolean upperBoundInclusive) {
    ResolverUtils.GfxdComparableFuzzy lowerBndGfxd = null;
    ResolverUtils.GfxdComparableFuzzy upperBndGfxd = null;
    Comparable<Object> lowerComp = (Comparable)lowerBound;
    Comparable<Object> upperComp = (Comparable)upperBound;
    boolean firstRangeObjectToBeRemoved = false;
    if (lowerComp == null) {
      lowerBoundInclusive = false;
      if (upperComp == null) {
        return null;
      }
      else {
        // This means lowerbound is minus infinity, so exclusive flag is
        // hard coded as true which in this special case is treated as
        // minus infinity.
        lowerBndGfxd = new ResolverUtils.GfxdComparableFuzzy(lowerComp,
            ResolverUtils.GfxdComparableFuzzy.GT);
        upperBndGfxd = new ResolverUtils.GfxdComparableFuzzy(upperComp,
            upperBoundInclusive ? ResolverUtils.GfxdComparableFuzzy.LE
                : ResolverUtils.GfxdComparableFuzzy.LT);
      }
    }
    else if (upperBound == null) {
      lowerBndGfxd = new ResolverUtils.GfxdComparableFuzzy(lowerComp,
          lowerBoundInclusive ? ResolverUtils.GfxdComparableFuzzy.GE
              : ResolverUtils.GfxdComparableFuzzy.GT);
      // This means upperbound is plus infinity, so exclusive flag is
      // hard coded as false which in this special case is treated as
      // plus infinity.
      upperBndGfxd = new ResolverUtils.GfxdComparableFuzzy(upperComp,
          ResolverUtils.GfxdComparableFuzzy.LT);
    }
    else {
      lowerBndGfxd = new ResolverUtils.GfxdComparableFuzzy(lowerComp,
          lowerBoundInclusive ? ResolverUtils.GfxdComparableFuzzy.GE
              : ResolverUtils.GfxdComparableFuzzy.GT);
      upperBndGfxd = new ResolverUtils.GfxdComparableFuzzy(upperComp,
          upperBoundInclusive ? ResolverUtils.GfxdComparableFuzzy.LE
              : ResolverUtils.GfxdComparableFuzzy.LT);
    }
    SortedMap<Object, Object[]> subMap = this.rangeMap.subMap(lowerBndGfxd,
        upperBndGfxd);

    if (subMap.isEmpty()) {
      // If both the upper bound and lower bound are in the same range then
      // the submap will be empty. So checking that and if that is true then
      // return that value else null
      return checkAndreturnRoutingObjects(lowerBndGfxd, upperBndGfxd);
    }

    Object[] elements = subMap.keySet().toArray();
    ResolverUtils.GfxdRange firstRangeEntry = (ResolverUtils.GfxdRange)elements[0];

    // This check means that the lower bound is on the left side of the first
    // range and hence break in continuity.
    if ((firstRangeEntry.inRange(lowerBndGfxd) > 0)
        && (!((ResolverUtils.GfxdComparableFuzzy)firstRangeEntry.rangeStart())
            .getWrappedObject().equals(lowerBound))) {
      return null;
    }

    if (!lowerBoundInclusive && (lowerBound != null)) {
      // If the lower bound is equal to the end value of the first
      // range then the first range needs to be reomoved.
      firstRangeObjectToBeRemoved = checkIfFirstRangeObjectToBeRemoved(
          firstRangeEntry, lowerBndGfxd);
    }

    List<Integer> routingObjects = new ArrayList<Integer>();
    ResolverUtils.GfxdRange lastRangeOfSubMap = isSubMapContinuous(subMap,
        firstRangeObjectToBeRemoved, routingObjects);
    if (lastRangeOfSubMap == null) {
      return null;
    }

    Object[] upperBoundValue = null;
    upperBoundValue = this.rangeMap.get(upperBndGfxd);
    if (upperBoundValue == null) {
      return null;
    }
    ResolverUtils.GfxdRange upperBoundRange = (ResolverUtils.GfxdRange)upperBoundValue[0];
    Integer upperBoundRoutingValue = (Integer)upperBoundValue[1];

    boolean checkContinuity = true;
    if (!upperBoundInclusive) {
      if (lastRangeOfSubMap.getEnd().getWrappedObject().equals(upperBound)) {
        checkContinuity = false;
      }
    }
    if (checkContinuity) {
      if (!isContinuous(lastRangeOfSubMap, upperBoundRange)) {
        return null;
      }
      else {
        routingObjects.add(upperBoundRoutingValue);
      }
    }
    if (routingObjects.size() == 0) {
      return null;
    }
    else {
      return routingObjects.toArray();
    }
  }

  @Override
  /**
   * For a huge list of values it might be efficient to first look by bounds and
   * do a fuzzy lookup. If it returns null, then probably ranges are
   * non-continuous and therefore iterate over the list to find the ranges
   * involved.
   */
  // TODO:[soubhik] Need to decide whether fuzzy lookup is sufficient for
  // pruning in case of huge list. Also, need to see normal list ordering and
  // range comparator ordering differs.
  public Object[] getRoutingObjectsForList(DataValueDescriptor[] keys)
      throws ClassCastException {
    Object[] routingObjects = new Object[keys.length];
    for (int index = 0; index < keys.length; ++index) {
      routingObjects[index] = this.getRoutingObjectForValue(keys[index]);
    }
    return routingObjects;
  }

  private Object[] checkAndreturnRoutingObjects(
      ResolverUtils.GfxdComparableFuzzy lowerBndGfxd,
      ResolverUtils.GfxdComparableFuzzy upperBndGfxd) {
    Object[] val1 = this.rangeMap.get(lowerBndGfxd);
    Object[] val2 = this.rangeMap.get(upperBndGfxd);
    if (val1 == null || val2 == null) {
      return null;
    }
    if (val1[1] != val2[1]) {
      return null;
    }
    Object[] ret = new Object[1];
    ret[0] = val1[1];
    return ret;
  }

  private ResolverUtils.GfxdRange isSubMapContinuous(
      SortedMap<Object, Object[]> submap, boolean firstRangeObjectToBeRemoved,
      List<Integer> routingObjects) {
    ResolverUtils.GfxdRange prevKey = null;
    ResolverUtils.GfxdRange lastRange = null;
    for (Object[] tmpval : submap.values()) {
      ResolverUtils.GfxdRange thisKey = (ResolverUtils.GfxdRange)tmpval[0];
      lastRange = thisKey;
      Integer thisval = (Integer)tmpval[1];
      if (prevKey == null) {
        prevKey = thisKey;
        if (!firstRangeObjectToBeRemoved) {
          routingObjects.add(thisval);
        }
        continue;
      }
      if (!isContinuous(prevKey, thisKey)) {
        routingObjects = null;
        return null;
      }
      else {
        routingObjects.add(thisval);
        prevKey = thisKey;
      }
    }
    return lastRange;
  }

  private boolean checkIfFirstRangeObjectToBeRemoved(
      ResolverUtils.GfxdRange firstEntry,
      ResolverUtils.GfxdComparableFuzzy lowerBound) {
    Comparable lhs = (Comparable)((ResolverUtils.GfxdComparableFuzzy)firstEntry
        .rangeEnd()).getWrappedObject();
    Comparable rhs = (Comparable)lowerBound.getWrappedObject();
    if (lhs.compareTo(rhs) == 0) {
      return true;
    }
    return false;
  }

  private boolean isContinuous(ResolverUtils.GfxdRange previous,
      ResolverUtils.GfxdRange current) {
    Object prevObject = ((ResolverUtils.GfxdComparableFuzzy)previous.rangeEnd())
        .getWrappedObject();
    Object currObject = ((ResolverUtils.GfxdComparableFuzzy)current
        .rangeStart()).getWrappedObject();
    if (prevObject.equals(currObject)) {
      return true;
    }
    return false;
  }

  @Override
  public String toString() {
    return "GfxdRangePartitionResolver(" + getQualifiedTableName() + "): "
        + this.rangeMap.keySet();
  }

  @Override
  public String getDDLString() {
    if (this.ddlString == null) {
      synchronized (this) {
        if (this.ddlString == null) {
          final StringBuilder sb = new StringBuilder("PARTITION BY RANGE (")
              .append(this.columnName).append(") (");
          ResolverUtils.GfxdRange range;
          for (Object o : this.rangeMap.keySet()) {
            range = (ResolverUtils.GfxdRange)o;
            range.getDDLString(sb);
            sb.append(',');
          }
          sb.setCharAt(sb.length() - 1, ')');
          this.ddlString = sb.toString();
        }
      }
    }
    return this.ddlString;
  }

  /**
   * {@link Visitor} for traversing the {@link ValueNodeList} of the
   * {@link BetweenOperatorNode}s. This populates the
   * {@link GfxdPartitionResolver#rangeMap} with the ranges as specified in the
   * nodes that is used for lookup by the {@link GfxdPartitionResolver}.
   * 
   * @author swale
   * @since 6.0
   */
  class GfxdRangeListVisitor extends VisitorAdaptor {

    /** SQL command string used for exception messages. */
    private final String command;

    /**
     * Constructor that takes the current SQL command for throwing proper
     * exception messages.
     */
    public GfxdRangeListVisitor(String command) {
      this.command = command;
    }

    /**
     * Visit all the {@link BetweenOperatorNode}s and populate the
     * {@link GfxdRangePartitionResolver#rangeMap}.
     */
    public Visitable visit(Visitable node) throws StandardException {
      if (node instanceof BetweenOperatorNode) {
        BetweenOperatorNode betweenNode = (BetweenOperatorNode)node;
        ValueNodeList rightOperands = betweenNode.getRightOperandList();
        Integer newValue = Integer.valueOf(rangeMap.size() + 1);
        try {
          ConstantNode start = (ConstantNode)rightOperands.elementAt(0);
          if (start instanceof UserTypeConstantNode) {
            if (((UserTypeConstantNode)start).isInfinityValue()) {
              start = null;
            }
          }
          ConstantNode end = (ConstantNode)rightOperands.elementAt(1);
          if (end instanceof UserTypeConstantNode) {
            if (((UserTypeConstantNode)end).isInfinityValue()) {
              end = null;
            }
          }
          // Throw error if start is not lower than end
          //  if start and end are non-null
          // Compare DVDs to ignore +INFINITY,-INFINITY specials and nulls
          DataValueDescriptor startDVD,endDVD;
          startDVD = getDVDFromConstantNode(start, colRef);
          endDVD = getDVDFromConstantNode(end, colRef);
          if ((startDVD != null) && (endDVD != null) &&
              (startDVD.compare(endDVD) >= 0))
          {
            throw StandardException.newException(
              SQLState.NOT_IMPLEMENTED, "Begin range not less than end range");
          }
          ResolverUtils.GfxdRange range = new ResolverUtils.GfxdRange(
              this.command, startDVD, endDVD);
          Object[] valarray = new Object[2];
          valarray[0] = range;
          valarray[1] = Integer.valueOf(newValue);
          rangeMap.put(range, valarray);
        } catch (ClassCastException ex) {
          throw StandardException.newException(
              SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, ex,
              "partition by range", colRef.getColumnName());
        }
      }
      return node;
    }

    public boolean skipChildren(Visitable node) {
      return false;
    }

    public boolean stopTraversal() {
      return false;
    }

  }

  @Override
  public Serializable getRoutingObjectFromDvdArray(DataValueDescriptor[] values) {
    if (this.colIndexInVal != -1) {
      if (values == null) {
        throw new IllegalArgumentException(
            "GfxdRangePartitionResolver: Passed in dvd array is null");
      }
      assert values.length > this.colIndexInVal: "GfxdRangePartitionResolver"
          + "#getRoutingObjectFromDvdArray: Incomplete row passed as values";
      return getRoutingObjectForValue(values[this.colIndexInVal]);
    }
    throw new IllegalStateException(
        "GfxdRangePartitionResolver: The state of this resolver is unexpected");
  }

  @Override
  public Object getRoutingObjectsForPartitioningColumns(
      DataValueDescriptor[] partitioningColumnObjects) {
    if (partitioningColumnObjects.length != 1) {
      throw new IllegalArgumentException("GfxdRangePartitionResolver."
          + "getRoutingObjectsForPartitioningColumns: range partitioning is "
          + "done only on single column");
    }
    Object routingObject = null;
    routingObject = getRoutingObjectForValue(partitioningColumnObjects[0]);
    if (routingObject == null) {
      routingObject = partitioningColumnObjects[0].hashCode();
    }
    return routingObject;
  }

  @Override
  public boolean requiresGlobalIndex() {
    return (this.pkIndexInGemFireKey < 0);
  }

  @Override
  public GfxdPartitionResolver cloneObject() {
    final GfxdRangePartitionResolver newRslvr = new GfxdRangePartitionResolver(
        this.ranges, this.rangeMap);
    newRslvr.columnName = this.columnName;
    return newRslvr;
  }

  @Override
  public void setPartitionColumns(String[] partCols, String[] refPartCols) {
    assert partCols.length == 1:
      "Range partitioning is valid for single column only";
    this.columnName = partCols[0];
  }

  @Override
  public boolean okForColocation(GfxdPartitionResolver rslvr) {
    if (rslvr instanceof GfxdRangePartitionResolver) {
      SortedMap<Object, Object[]> rangeMapOfThePassedRslvr =
        ((GfxdRangePartitionResolver)rslvr).rangeMap;
      if (this.rangeMap.size() != rangeMapOfThePassedRslvr.size()) {
        return false;
      }
      Iterator<Object> itr1 = this.rangeMap.keySet().iterator();
      Iterator<Object> itr2 = rangeMapOfThePassedRslvr.keySet().iterator();
      while (itr1.hasNext() && itr2.hasNext()) {
        ResolverUtils.GfxdRange key1 = (ResolverUtils.GfxdRange)itr1.next();
        ResolverUtils.GfxdRange key2 = (ResolverUtils.GfxdRange)itr2.next();
        if (key1.rangeStart().compareTo(key2.rangeStart()) != 0) {
          return false;
        }
        if (key1.rangeEnd().compareTo(key2.rangeEnd()) != 0) {
          return false;
        }
        Object[] val1 = this.rangeMap.get(key1.rangeStart());
        Object[] val2 = rangeMapOfThePassedRslvr.get(key2.rangeStart());
        if (!val1[1].equals(val2[1])) {
          return false;
        }
        Object[] val1_end = this.rangeMap.get(key1.rangeEnd());
        Object[] val2_end = rangeMapOfThePassedRslvr.get(key2.rangeEnd());
        if (!val1_end[1].equals(val2_end[1])) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private int getTypeFormatIdFromRangeObject(GfxdRange range) {
    assert range != null;
    GfxdComparableFuzzy boundary = null;
    Comparable start = range.rangeStart();
    if (start != null) {
      boundary = (GfxdComparableFuzzy)start;
    }
    else {
      Comparable end = range.rangeEnd();
      boundary = (GfxdComparableFuzzy)end;
    }
    assert boundary != null;
    // assert boundary instanceof DataValueDescriptor;
    DataValueDescriptor dvd = (DataValueDescriptor)boundary.getWrappedObject();
    return dvd.getTypeFormatId();
  }

  @Override
  public void setResolverInfoInSingleHopInfoObject(SingleHopInformation info)
      throws StandardException {
    assert info != null: "unexpected null SingleHopInformation object";
    info.setResolverType(SingleHopInformation.RANGE_RESOLVER_FLAG);
    boolean typeFormatSet = false;
    Map map = null;
    ArrayList list = null;
    for (Entry<Object, Object[]> v : this.rangeMap.entrySet()) {
      Object val = v.getKey();
      assert val instanceof ResolverUtils.GfxdRange;
      GfxdRange range = (GfxdRange)val;
      if (!typeFormatSet) {
        int typeFormatId = getTypeFormatIdFromRangeObject(range);
        int[] typeArray = new int[] { typeFormatId };
        if (isValidTypeForSingleHop(typeArray, info)) {
          info.setTypeFormatIdArray(typeArray);
          typeFormatSet = true;
          list = new ArrayList();
        }
        else {
          info.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
          return;
        }
      }
      Integer value = (Integer)((Object[])v.getValue())[1];
      addRangeTolist(range, list, value);
    }
    info.setRangeValueHolderList(list);
  }

  private void addRangeTolist(GfxdRange range, ArrayList list, Integer value)
      throws StandardException {
    assert range != null;
    assert range != null;
    GfxdComparableFuzzy boundary = null;
    Object lowerBound = null;
    Object upperBound = null;
    Comparable start = range.rangeStart();
    if (start != null) {
      boundary = (GfxdComparableFuzzy)start;
      DataValueDescriptor dvd = (DataValueDescriptor)boundary
          .getWrappedObject();
      lowerBound = dvd.getObject();
    }

    Comparable end = range.rangeEnd();
    if (end != null) {
      boundary = (GfxdComparableFuzzy)end;
      DataValueDescriptor dvd = (DataValueDescriptor)boundary
          .getWrappedObject();
      upperBound = dvd.getObject();
    }

    PlainRangeValueHolder rangeHolder = new PlainRangeValueHolder(lowerBound,
        upperBound, value);
    list.add(rangeHolder);
  }
}
