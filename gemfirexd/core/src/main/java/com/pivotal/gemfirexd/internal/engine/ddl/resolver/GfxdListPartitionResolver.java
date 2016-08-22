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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.THashMapWithLongContext;
import com.gemstone.gemfire.internal.cache.TObjectHashingStrategyWithLongContext;
import com.gemstone.gemfire.internal.cache.xmlcache.CacheXmlParser;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gnu.trove.TObjectHashingStrategy;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.SerializableDelta;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitor;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.VisitorAdaptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ColumnReference;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ConstantNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNodeList;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SingleHopInformation;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

/**
 * Custom partitioning using the {@link PartitionResolver} interface to handle
 * the "PARTITION BY LIST" GemFire DDL extension.
 * 
 * @author Kumar Neeraj
 * @since 6.0
 */
public final class GfxdListPartitionResolver extends GfxdPartitionResolver {

  /** name of the column to be used for partitioning */
  private String columnName;

  /** list of list of values to be used for list partitioning */
  private final ArrayList<ValueNodeList> valueLists;

  /**
   * Map that contains the list of values and the corresponding routingObjects
   */
  protected final HashMap<Object, Integer> valueListMap;

  /**
   * Map that contains the list of values serialized to bytes and the
   * corresponding routingObjects.
   */
  protected final THashMapWithLongContext serializedValueListMap;

  protected Integer newListValue;

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

  private static final TObjectHashingStrategy bytesCompareStrategy =
    new TObjectHashingStrategy() {

    private static final long serialVersionUID = 4635405514016630546L;

    @Override
    public final int computeHashCode(final Object o) {
      return ResolverUtils.addBytesToHash((byte[])o, 0);
    }

    @Override
    public final boolean equals(final Object o1, final Object o2) {
      return Arrays.equals((byte[])o1, (byte[])o2);
    }
  };

  static final class SerializedBytesCompare implements
      TObjectHashingStrategyWithLongContext {

    @Override
    public final int computeHashCode(final Object obj, final long offsetAndWidth) {
      final byte[] bytes = (byte[])obj;
      final int width = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      return ResolverUtils.addBytesToHash(bytes, offset, width, 0);
    }

    @Override
    public final boolean equals(final Object obj1, final Object obj2,
        final long offsetAndWidth) {
      // left side object is the one in map which is serialized column bytes
      // right side object is the incoming bytes
      final byte[] bytes = (byte[])obj1;
      final byte[] otherBytes = (byte[])obj2;
      final int width = (int)offsetAndWidth;
      final int offset = (int)(offsetAndWidth >>> Integer.SIZE);
      if (bytes.length == width) {
        for (int index = 0; index < bytes.length; ++index) {
          if (bytes[index] != otherBytes[offset + index]) {
            return false;
          }
        }
        return true;
      }
      return false;
    }
  };

  public GfxdListPartitionResolver(ColumnReference columnRef,
      ArrayList<ValueNodeList> valueLists) {
    this.columnName = columnRef.getSQLColumnName();
    this.valueLists = valueLists;
    this.valueListMap = new HashMap<Object, Integer>();
    this.serializedValueListMap = new THashMapWithLongContext(
        bytesCompareStrategy, new SerializedBytesCompare());
    this.newListValue = Integer.valueOf(0);
    this.colRef = columnRef;
  }

  /**
   * For tests only. Do not use otherwise since this does not initialize the
   * resolver properly.
   */
  public GfxdListPartitionResolver(List<List<Object>> valueLists) {
    this.valueListMap = new HashMap<Object, Integer>();
    int idx = 0;
    for (List<Object> arrList : valueLists) {
      for (Object val : arrList) {
        this.valueListMap.put(val, Integer.valueOf(idx));
      }
      ++idx;
    }
    this.valueLists = null;
    this.serializedValueListMap = null;
  }

  /** for cloning */
  private GfxdListPartitionResolver(final ArrayList<ValueNodeList> valueLists,
      final HashMap<Object, Integer> valueListMap,
      final THashMapWithLongContext serializedValueListMap) {
    this.valueLists = valueLists;
    this.valueListMap = valueListMap;
    this.serializedValueListMap = serializedValueListMap;
  }

  /**
   * Return the ranges as a property. This allows proper conversion to XML by
   * {@link CacheXmlParser}.
   */
  public Properties getConfig() {
    Properties props = new Properties();
    props.setProperty("LISTS", this.valueListMap.toString());
    return props;
  }

  @Override
  public String toString() {
    return "GfxdListPartitionResolver(" + getQualifiedTableName()
        + "): pkindexingfkey = " + this.pkIndexInGemFireKey
        + " colindexinval = " + this.colIndexInVal + ", keys in valueList: "
        + this.valueListMap.keySet();
  }

  @Override
  public String getDDLString() {
    if (this.ddlString == null) {
      synchronized (this) {
        if (this.ddlString == null) {
          final StringBuilder sb = new StringBuilder("PARTITION BY LIST (");
          @SuppressWarnings("unchecked")
          final ArrayList<Object>[] lists = new ArrayList[this.valueListMap
              .size()];
          int index;
          for (Map.Entry<Object, Integer> entry : this.valueListMap.entrySet()) {
            index = entry.getValue().intValue();
            if (lists[index] == null) {
              lists[index] = new ArrayList<Object>();
            }
            lists[index].add(entry.getKey());
          }
          ArrayList<Object> list;
          for (index = 0; index < lists.length; ++index) {
            list = lists[index];
            if (list == null) {
              break;
            }
            sb.append(list);
            sb.append(',');
          }
          sb.setCharAt(sb.length() - 1, ')');
          this.ddlString = sb.toString();
        }
      }
    }
    return this.ddlString;
  }

  @Override
  public String[] getColumnNames() {
    return this.partitionColumnNames;
  }

  @Override
  public boolean isUsedInPartitioning(String columnToCheck) {
    return this.columnName.equals(columnToCheck);
  }

  @Override
  public int getPartitioningColumnIndex(String partitionColumn) {
    if (this.columnName.equals(partitionColumn)) {
      return 0;
    }
    return -1;
  }

  @Override
  public void setColumnInfo(TableDescriptor td, Activation activation)
      throws StandardException {
    assert td != null;
    DistributionDescriptor distDescp = td.getDistributionDescriptor();
    assert distDescp != null;

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
        || (partitioningColIdxInPKey = pkMap.get(columnName)) == null) {
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
    assert colNameToIdxMap != null;
    Integer thisColIdx = colNameToIdxMap.get(this.columnName);
    assert thisColIdx != null;
    this.colIndexInVal = thisColIdx.intValue();
    // Decrementing it so that adjustment is not necessary while accessing in
    // DVD array while calculating routing object
    --this.colIndexInVal;
  }

  @Override
  public void bindExpression(FromList fromList, SubqueryList subqueryList,
      Vector<?> aggregateVector) throws StandardException {
    this.colRef.bindExpression(fromList, subqueryList, aggregateVector);
    for (ValueNodeList vnlist : this.valueLists) {
      vnlist.bindExpression(fromList, subqueryList, aggregateVector);
      vnlist.accept(new GfxdValueListVisitor("PARTITION BY LIST ("
          + this.columnName + ')'));
      incrementNewListValue();
    }
  }

  /** name of this {@link PartitionResolver} implementation */
  public String getName() {
    return "gfxd-list-partition-resolver";
  }

  @Override
  protected final boolean isPartitioningSubsetOfKey() {
    return this.pkIndexInGemFireKey != -1;
  }

  @Override
  public Object getRoutingObject(Object key, @Unretained Object val, Region<?, ?> region) {
    Object routingObject = null;
    RegionKey gfkey = null;
    if (key instanceof RegionKey) {
      gfkey = (RegionKey)key;
    }
    final int keyIndex = this.pkIndexInGemFireKey;
    if (keyIndex != -1) {
      // lookup without deserialization
      byte[] kbytes;
      final RowFormatter rf;
      final int logicalPosition;
      final long offsetAndWidth;
      // right side object is key bytes
      final CompactCompositeRegionKey ccrk = (CompactCompositeRegionKey)gfkey;
      final ExtraTableInfo tabInfo = ccrk.getTableInfo();
      int tries = 1;
      for (;;) {
        kbytes = ccrk.getValueBytes();
        if (kbytes != null) {
          rf = tabInfo.getRowFormatter();
          final int[] keyPositions = tabInfo.getPrimaryKeyColumns();
          // serialized bytes are part of value bytes
          logicalPosition = keyPositions[keyIndex];
          break;
        }
        else if ((kbytes = ccrk.getKeyBytes()) != null) {
          rf = tabInfo.getPrimaryKeyFormatter();
          // serialized bytes are part of key bytes
          logicalPosition = keyIndex + 1;
          break;
        }
        if ((tries++ % CompactCompositeRegionKey.MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
        if (tries > CompactCompositeRegionKey.MAX_TRIES) {
          throw RegionEntryUtils.checkCacheForNullKeyValue(
              "GfxdListPartitionResolver#getRoutingObject");
        }
      }
      offsetAndWidth = rf.getOffsetAndWidth(logicalPosition, kbytes);
      if (offsetAndWidth >= 0) {
        routingObject = this.serializedValueListMap.get(kbytes, offsetAndWidth);

        if (routingObject == null) {
          int typeId = rf.getColumnDescriptor(logicalPosition - 1).getType()
              .getTypeId().getTypeFormatId();
          routingObject = ResolverUtils.addBytesToBucketHash(kbytes,
              (int)(offsetAndWidth >>> Integer.SIZE),
              (int)offsetAndWidth, 0, typeId);
        }
      }
      else if (offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
        routingObject = this.valueListMap.get(rf
            .getColumnDescriptor(logicalPosition - 1).columnDefault);
      }
      if (this.gflogger.finerEnabled()) {
        this.gflogger.finer("primary key is partition key and key for which "
            + "routing wanted = " + key + " routingObject calculated = "
            + routingObject);
      }
    }
    else {
      if (val != null && !(val instanceof SerializableDelta)) {
        assert this.colIndexInVal != -1: "unexpected colIndexInVal=-1 with val "
            + val;
        // this case of new row being inserted will always have the current
        // RowFormatter and not any of the previous ones before ALTER TABLEs
        final RowFormatter rf = this.gfContainer.getCurrentRowFormatter();
        final ColumnDescriptor cd = rf.getColumnDescriptor(this.colIndexInVal);
        final byte[] vbytes;
        final Class<?> vclass = val.getClass();
        if (vclass == byte[].class) {
          if (cd.isLob) {
            vbytes = cd.columnDefaultBytes;
          } else {
            vbytes = (byte[])val;
          }
        } else if (vclass == byte[][].class) {
          if (cd.isLob) {
            vbytes = rf.getLob((byte[][])val, this.colIndexInVal + 1);
          } else {
            vbytes = ((byte[][])val)[0];
          }
        } else if (vclass == OffHeapRowWithLobs.class) {
          if (cd.isLob) {
            vbytes = rf.getLob((OffHeapRowWithLobs)val, this.colIndexInVal + 1); // OFFHEAP: optimize; no need to read all the bytes
          } else {
            vbytes = ((OffHeapRowWithLobs)val).getRowBytes(); // OFFHEAP: optimize; no need to read all the bytes
          }
        } else {
          if (cd.isLob) {
            vbytes = cd.columnDefaultBytes;
          } else {
            vbytes = ((OffHeapByteSource)val).getRowBytes(); // OFFHEAP: optimize; no need to read all the bytes
          }
        }
        final long offsetAndWidth = rf.getOffsetAndWidth(
            this.colIndexInVal + 1, vbytes, cd);
        if (offsetAndWidth >= 0) {
          routingObject = this.serializedValueListMap.get(vbytes, offsetAndWidth); // OFFHEAP: serializedValueListMap expected keys that are byte[]. To support offheap rows need to stategy comparator to also support off-heap
          if (routingObject == null) {
            int typeId = rf.getColumnDescriptor(this.colIndexInVal)
                .getType().getTypeId().getTypeFormatId();
            routingObject = ResolverUtils.addBytesToBucketHash(vbytes,
                (int)(offsetAndWidth >>> Integer.SIZE),
                (int)offsetAndWidth, 0, typeId);
          }
        }
        else if (offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT) {
          routingObject = this.valueListMap.get(rf
              .getColumnDescriptor(this.colIndexInVal).columnDefault);
        }
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
        routingObject = getRoutingObjectFromGlobalIndex(
            this.globalIndexContainer, gfkey);
      }
    }
    if (routingObject == null) {
      // for null partitioning column value use a default integer for routing
      routingObject = Integer.valueOf(-1);
    }
    if (this.globalIndexContainer != null) {
      updateGlobalIndexCache(gfkey, routingObject);
    }
    return routingObject;
  }

  @Override
  public Object[] getRoutingObjectsForList(DataValueDescriptor[] keys) {
    Object[] routingObjects = new Object[keys.length];
    for (int index = 0; index < keys.length; ++index) {
      DataValueDescriptor key = keys[index];
      Object routingObject = this.valueListMap.get(key);
      if (routingObject == null) {
        routingObjects[index] = getRoutingObjectForValueNotInList(key);
      }
      else {
        routingObjects[index] = routingObject;
      }
    }
    return routingObjects;
  }

  @Override
  public Object[] getRoutingObjectsForRange(DataValueDescriptor lowerBound,
      boolean lowerBoundInclusive, DataValueDescriptor upperBound,
      boolean upperBoundInclusive) {
    if (lowerBound != null && upperBound != null) {
      if (lowerBound.equals(upperBound) && (lowerBoundInclusive == true)
          && (lowerBoundInclusive == upperBoundInclusive)) {
        Object routingObject = this.valueListMap.get(lowerBound);
        if (routingObject == null) {
          routingObject = getRoutingObjectForValueNotInList(lowerBound);
        }
        return new Object[] { routingObject };
      }
    }
    return null;
  }

  @Override
  public Object getRoutingKeyForColumn(
      DataValueDescriptor partitioningColumnValue) {
    Object routingObject = this.valueListMap.get(partitioningColumnValue);
    if (routingObject == null) {
      routingObject = getRoutingObjectForValueNotInList(partitioningColumnValue);
    }
    return routingObject;
  }

  private Object getRoutingObjectForValueNotInList(
      DataValueDescriptor partitioningColumnValue) {
    Object routingObject = null;
    final DataTypeDescriptor dtd = this.gfContainer.getCurrentRowFormatter()
        .getType(this.colIndexInVal + 1);
    routingObject = GfxdPartitionByExpressionResolver.computeHashCode(
        partitioningColumnValue, dtd, 0);
    return routingObject;
  }

  private void incrementNewListValue() {
    ++this.newListValue;
  }

  /**
   * {@link Visitor} for traversing the {@link ValueNodeList} of each of the
   * list of values. This populates the
   * {@link GfxdListPartitionResolver#valueListMap} with each of the list of
   * values as specified in the partition list with same value.
   * 
   * @author kneeraj
   * @since 6.0
   */
  final class GfxdValueListVisitor extends VisitorAdaptor {

    /** SQL command string used for exception messages. */
    final String command;

    /**
     * Constructor that takes the current SQL command for throwing proper
     * exception messages.
     */
    public GfxdValueListVisitor(String command) {
      this.command = command;
    }

    /**
     * Visit all the {@link ValueNode}s and populates the
     * {@link GfxdListPartitionResolver#valueListMap}.
     */
    @Override
    public Visitable visit(Visitable node) throws StandardException {
      if (node instanceof ValueNode) {
        try {
          ConstantNode valueNode = (ConstantNode)node;
          final DataTypeDescriptor dtd = colRef.getTypeServices();
          if (dtd.getTypeId().isJSONTypeId()) {
            throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
                "JSON column '" + colRef.getColumnName()
                    + "' in partition by clause");
          }
          final DataValueDescriptor dvd = getDVDFromConstantNode(valueNode,
              colRef);
          final Integer oldVal = valueListMap.put(dvd, newListValue);
          boolean valueIsStringType, colIsStringType;
          // Allowing duplicates in the same list but if a value comes
          // in two different list then throw exception.
          if (oldVal != null && !oldVal.equals(newListValue)) {
            throw StandardException.newException(
                SQLState.LANG_DUPLICATE_PROPERTY,
                "PARTITION BY LIST for value " + dvd);
          }
          // also put the serialized value in the serialized value map
          final int length = dvd.getLengthInBytes(dtd);
          // If the value is longer than the column and it is a string,
          // do not truncate, throw an error
          if (dtd.getTypeId().isStringTypeId()  && 
             (dvd.getLength() > dtd.getMaximumWidth()))
          {
              throw StandardException
              .newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE,
                  "partition by list value too long", colRef.getColumnName());
          }
          // If one is a string and the other isn't, throw an error as well
          valueIsStringType = false;
          colIsStringType = false;
          if (valueNode != null) {
        	  int typeId = valueNode.getValue().getTypeFormatId();
        	// There isn't a macro for this checking of typeformatids, only typeids
        	// So just copy from BinarySQLHybridType.isStringType()
            valueIsStringType = ((typeId==StoredFormatIds.SQL_CHAR_ID) ||
            					(typeId==StoredFormatIds.SQL_VARCHAR_ID) ||
          						(typeId==StoredFormatIds.SQL_LONGVARCHAR_ID) ||
          						(typeId==StoredFormatIds.SQL_CLOB_ID));
          } 		
          colIsStringType = dtd.getTypeId().isStringTypeId();  		
          if (valueIsStringType != colIsStringType)
          {
              throw StandardException
              .newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE,
                  "partition by list value not compatible with column type",
                  colRef.getColumnName());
          }
          final byte[] bytes = new byte[length];
          dvd.writeBytes(bytes, 0, dtd);
          serializedValueListMap.put(bytes, newListValue);
        } catch (ClassCastException ex) {
          throw StandardException
              .newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, ex,
                  "partition by list", colRef.getColumnName());
        }
      }
      return node;
    }

    @Override
    public boolean skipChildren(Visitable node) {
      return false;
    }

    @Override
    public boolean stopTraversal() {
      return false;
    }
  }

  @Override
  public Serializable getRoutingObjectFromDvdArray(
      final DataValueDescriptor[] values) {
    if (values == null) {
      throw new IllegalArgumentException(
          "GfxdListPartitionResolver: Passed in dvd array is null");
    }
    Object routingObject = null;
    if (this.colIndexInVal != -1) {
      DataValueDescriptor val = values[this.colIndexInVal];
      routingObject = this.valueListMap.get(val);
      if (routingObject == null) {
        if (val == null) {
          routingObject = Integer.valueOf(0);
        }
        else {
          routingObject = getRoutingObjectForValueNotInList(val);
        }
      }
      return (Integer)routingObject;
    }
    throw new IllegalStateException(
        "GfxdListPartitionResolver: The state of this resolver is unexpected");
  }

  @Override
  public Object getRoutingObjectsForPartitioningColumns(
      DataValueDescriptor[] partitioningColumnObjects) {
    if (partitioningColumnObjects.length != 1) {
      throw new IllegalArgumentException("GfxdListPartitionResolver."
          + "getRoutingObjectsForPartitioningColumns: list partitioning "
          + "is done only on single column");
    }
    Object routingObject = null;
    routingObject = this.valueListMap.get(partitioningColumnObjects[0]);
    if (routingObject == null) {
      routingObject = getRoutingObjectForValueNotInList(
          partitioningColumnObjects[0]);
    }
    return routingObject;
  }

  @Override
  public boolean requiresGlobalIndex() {
    return (this.pkIndexInGemFireKey < 0);
  }

  @Override
  public GfxdPartitionResolver cloneObject() {
    final GfxdListPartitionResolver newRslvr = new GfxdListPartitionResolver(
        this.valueLists, this.valueListMap, this.serializedValueListMap);
    newRslvr.newListValue = Integer.valueOf(0);
    newRslvr.columnName = this.columnName;
    return newRslvr;
  }

  @Override
  public void setPartitionColumns(String[] partCols, String[] refPartCols) {
    assert partCols.length == 1:
      "List partitioning is valid for single column only";
    this.columnName = partCols[0];
  }

  @Override
  public boolean okForColocation(GfxdPartitionResolver rslvr) {
    if (rslvr instanceof GfxdListPartitionResolver) {
      HashMap<Object, Integer> valueListMapOfThePassedRslvr =
        ((GfxdListPartitionResolver)rslvr).valueListMap;
      if (this.valueListMap.size() != valueListMapOfThePassedRslvr.size()) {
        return false;
      }
      for (Map.Entry<Object, Integer> entry : this.valueListMap.entrySet()) {
        Object thiskey = entry.getKey();
        Object thisvalue = entry.getValue();
        Object valueFromPassed = valueListMapOfThePassedRslvr.get(thiskey);
        if (valueFromPassed == null) {
          return false;
        }
        if (!valueFromPassed.equals(thisvalue)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public void setResolverInfoInSingleHopInfoObject(SingleHopInformation info)
      throws StandardException {
    assert info != null: "unexpected null SingleHopInformation object";
    info.setResolverType(SingleHopInformation.LIST_RESOLVER_FLAG);
    boolean typeFormatSet = false;
    HashMap<Object, Integer> map = null;
    for (Object val : this.valueListMap.keySet()) {
      assert val instanceof DataValueDescriptor;
      DataValueDescriptor dvd = (DataValueDescriptor)val;
      if (!typeFormatSet) {
        int typeFormatId = dvd.getTypeFormatId();
        int[] typeArray = new int[] {typeFormatId};
        if (isValidTypeForSingleHop(typeArray, info)) {
          info.setTypeFormatIdArray(typeArray);
          map = new HashMap<Object, Integer>(5);
          typeFormatSet = true;
        }
        else {
          info.setResolverType(SingleHopInformation.NO_SINGLE_HOP_CASE);
          return;
        }
      }
      if (dvd.getTypeFormatId() == StoredFormatIds.SQL_SMALLINT_ID) {
        map.put(Short.valueOf(dvd.getShort()), this.valueListMap.get(dvd));
      }
      else {
        map.put(dvd.getObject(), this.valueListMap.get(dvd));
      }
    }
    info.setMapOfListValues(map);
  }
}
