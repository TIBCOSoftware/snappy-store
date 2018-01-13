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
package com.gemstone.gemfire.cache.query.internal.index;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.query.AmbiguousNameException;
import com.gemstone.gemfire.cache.query.FunctionDomainException;
import com.gemstone.gemfire.cache.query.IndexStatistics;
import com.gemstone.gemfire.cache.query.IndexType;
import com.gemstone.gemfire.cache.query.NameResolutionException;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.QueryInvocationTargetException;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.gemstone.gemfire.cache.query.internal.CompiledValue;
import com.gemstone.gemfire.cache.query.internal.ExecutionContext;
import com.gemstone.gemfire.cache.query.internal.MapIndexable;
import com.gemstone.gemfire.cache.query.internal.RuntimeIterator;
import com.gemstone.gemfire.cache.query.types.ObjectType;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;

public class MapRangeIndex extends AbstractIndex
{
  private final RegionEntryToValuesMap entryToMapKeysMap;

  final private boolean isAllKeys;

  final String[] patternStr;

  private final Map<Object, RangeIndex> mapKeyToValueIndex;

  private final Object[] mapKeys;

  MapRangeIndex(String indexName, Region region, String fromClause,
      String indexedExpression, String projectionAttributes,
      String origFromClause, String origIndxExpr, String[] defintions,
      boolean isAllKeys, String[] multiIndexingKeysPattern, Object[] mapKeys) {
    super(indexName, region, fromClause, indexedExpression,
        projectionAttributes, origFromClause, origIndxExpr, defintions, null);
    this.mapKeyToValueIndex = new ConcurrentHashMap<Object, RangeIndex>(2, 0.75f, 1);
    RegionAttributes ra = region.getAttributes();
    this.entryToMapKeysMap = new RegionEntryToValuesMap(new java.util.concurrent.ConcurrentHashMap(ra.getInitialCapacity(),ra.getLoadFactor(), ra.getConcurrencyLevel()),
        true /* user target list as the map keys will be unique*/);
    this.isAllKeys = isAllKeys;
    this.mapKeys = mapKeys;
    if (this.isAllKeys) {
      this.patternStr = new String[] { new StringBuilder(indexedExpression)
          .deleteCharAt(indexedExpression.length() - 2).toString() };

    }
    else {
      this.patternStr = multiIndexingKeysPattern;
    }
  }

  @Override
  void addMapping(RegionEntry entry) throws IMQException
  {
    this.evaluator.evaluate(entry, true);
    addSavedMappings(entry);
    clearCurrState();
  }

  private void addSavedMappings(RegionEntry entry) throws IMQException {
    for (Object rangeInd : this.mapKeyToValueIndex.values()) {
      ((RangeIndex)rangeInd).addSavedMappings(entry);
    }
  }

  @Override
  protected InternalIndexStatistics createStats(String indexName) {
    return new InternalIndexStatistics() {
    };
  } 

  @Override
  public ObjectType getResultSetType()
  {
    return this.evaluator.getIndexResultSetType();
  }

  @Override
  void instantiateEvaluator(IndexCreationHelper ich)
  {
    this.evaluator = new IMQEvaluator(ich);
  }

  @Override
  public void initializeIndex() throws IMQException
  {
    evaluator.initializeIndex();
  }

  @Override
  void lockedQuery(Object key, int operator, Collection results,
      CompiledValue iterOps, RuntimeIterator runtimeItr,
      ExecutionContext context, List projAttrib,
      SelectResults intermediateResults, boolean isIntersection)
      throws TypeMismatchException, FunctionDomainException,
      NameResolutionException, QueryInvocationTargetException
  {
    Object[] mapKeyAndVal = (Object[])key;
    RangeIndex ri = this.mapKeyToValueIndex.get(mapKeyAndVal[1]);
    if (ri != null) {
      long start = System.nanoTime();
      ri.internalIndexStats.incUsesInProgress(1);
      ri.lockedQuery(mapKeyAndVal[0], operator, results, iterOps, runtimeItr,
          context, projAttrib, intermediateResults, isIntersection);
      ri.internalIndexStats.incNumUses();
      ri.internalIndexStats.incUsesInProgress(-1);
      ri.internalIndexStats.incUseTime(System.nanoTime() - start);
    }
  }

  @Override
  void lockedQuery(Object lowerBoundKey, int lowerBoundOperator,
      Object upperBoundKey, int upperBoundOperator, Collection results,
      Set keysToRemove, ExecutionContext context) throws TypeMismatchException, FunctionDomainException,
      NameResolutionException, QueryInvocationTargetException
  {
    throw new UnsupportedOperationException(
        "Range grouping for MapIndex condition is not supported");

  }

  @Override
  void lockedQuery(Object key, int operator, Collection results,
      Set keysToRemove, ExecutionContext context) throws TypeMismatchException, FunctionDomainException,
      NameResolutionException, QueryInvocationTargetException
  {
    Object[] mapKeyAndVal = (Object[])key;
    RangeIndex ri = this.mapKeyToValueIndex.get(mapKeyAndVal[1]);
    if (ri != null) {
      long start = System.nanoTime();
      ri.internalIndexStats.incUsesInProgress(1);
      ri.lockedQuery(mapKeyAndVal[0], operator, results, keysToRemove, context);
      ri.internalIndexStats.incNumUses();
      ri.internalIndexStats.incUsesInProgress(-1);
      ri.internalIndexStats.incUseTime(System.nanoTime() - start);
    }
  }

  @Override
  void recreateIndexData() throws IMQException
  {
    /*
     * Asif : Mark the data maps to null & call the initialization code of index
     */
    // TODO:Asif : The statistics data needs to be modified appropriately
    // for the clear operation
    this.mapKeyToValueIndex.clear();
    this.entryToMapKeysMap.clear();
    this.initializeIndex();

  }

  @Override
  void removeMapping(RegionEntry entry, int opCode) throws IMQException
  {
    // this implementation has a reverse map, so it doesn't handle
    // BEFORE_UPDATE_OP
    if (opCode == BEFORE_UPDATE_OP) {
      return;
    }

    Object values = this.entryToMapKeysMap.remove(entry);
    //Values in reverse coould be null if map in region value does not
    //contain any key which matches to index expression keys.
    if (values == null ) {
      return;
    }
    if (values instanceof Collection) {
      Iterator valuesIter = ((Collection)values).iterator();
      while (valuesIter.hasNext()) {
        Object key = valuesIter.next();
        RangeIndex ri = (RangeIndex)this.mapKeyToValueIndex.get(key);
        long start = System.nanoTime();
        ri.internalIndexStats.incUpdatesInProgress(1);
        ri.removeMapping(entry, opCode);
        ri.internalIndexStats.incUpdatesInProgress(-1);
        ri.internalIndexStats.incUpdateTime(System.nanoTime() - start);
      }
    }
    else {
      RangeIndex ri = (RangeIndex)this.mapKeyToValueIndex.get(values);
      long start = System.nanoTime();
      ri.internalIndexStats.incUpdatesInProgress(1);
      ri.removeMapping(entry, opCode);
      ri.internalIndexStats.incUpdatesInProgress(-1);
      ri.internalIndexStats.incUpdateTime(System.nanoTime() - start);
    }
  }

  public boolean clear() throws QueryException
  {
    throw new UnsupportedOperationException(
        "MapType Index method not supported");
  }

  public void clearCurrState() {
    for (Object rangeInd : this.mapKeyToValueIndex.values()) {
      ((RangeIndex)rangeInd).clearCurrState();
    }
  }

  public int getSizeEstimate(Object key, int op, int matchLevel)
      throws TypeMismatchException
  {
    Object[] mapKeyAndVal = (Object[])key;
    Object mapKey = mapKeyAndVal[1];
    RangeIndex ri = this.mapKeyToValueIndex.get(mapKey);
    if (ri != null) {
      return ri.getSizeEstimate(mapKeyAndVal[0], op, matchLevel);
    }
    else {
      return 0;
    }
  }

  @Override
  protected boolean isCompactRangeIndex() {
    return false;
  }
  
  public IndexType getType()
  {
    return IndexType.FUNCTIONAL;
  }

  @Override
  public boolean isMapType()
  {
    return true;
  }

  @Override
  void addMapping(Object key, Object value, RegionEntry entry)
      throws IMQException
  {
    assert key instanceof Map;
    if (this.isAllKeys) {
      Iterator<Map.Entry<?, ?>> entries = ((Map)key).entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry<?, ?> mapEntry = entries.next();
        Object mapKey = mapEntry.getKey();
        Object indexKey = mapEntry.getValue();
        this.doIndexAddition(mapKey, indexKey, value, entry);
      }
    }
    else {
      for (Object mapKey : mapKeys) {
        Object indexKey = ((Map)key).get(mapKey);
        if (indexKey != null) {
          this.doIndexAddition(mapKey, indexKey, value, entry);
        }
      }
    }
  }

  @Override
  void saveMapping(Object key, Object value, RegionEntry entry)
      throws IMQException
  {
    assert key instanceof Map;
    if (this.isAllKeys) {
      Iterator<Map.Entry<?, ?>> entries = ((Map)key).entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry<?, ?> mapEntry = entries.next();
        Object mapKey = mapEntry.getKey();
        Object indexKey = mapEntry.getValue();
        this.saveIndexAddition(mapKey, indexKey, value, entry);
      }
    }
    else {
      for (Object mapKey : mapKeys) {
        Object indexKey = ((Map)key).get(mapKey);
        if (indexKey != null) {
          this.saveIndexAddition(mapKey, indexKey, value, entry);
        }
      }
    }
  }

  private void doIndexAddition(Object mapKey, Object indexKey, Object value,
      RegionEntry entry) throws IMQException
  {
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = this.mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      IndexStatistics stats = null;
      PartitionedIndex prIndex = null;
      if (this.region instanceof BucketRegion) {
        prIndex = (PartitionedIndex) this.getPRIndex();
        stats = prIndex.getStatistics(indexName+"-"+mapKey);
      }
      rg = new RangeIndex(indexName+"-"+mapKey, region, fromClause, indexedExpression,
          projectionAttributes, this.originalFromClause,
          this.originalIndexedExpression, this.canonicalizedDefinitions, stats);
      //Shobhit: We need evaluator to verify RegionEntry and IndexEntry inconsistency.
      rg.evaluator = this.evaluator;
      this.mapKeyToValueIndex.put(mapKey, rg);
    }
    rg.internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.addMapping(indexKey, value, entry);
    //This call is skipped when addMapping is called from MapRangeIndex
    //rg.internalIndexStats.incNumUpdates();
    rg.internalIndexStats.incUpdatesInProgress(-1);
    rg.internalIndexStats.incUpdateTime(System.nanoTime() - start);
    this.entryToMapKeysMap.add(entry, mapKey);
  }

  private void saveIndexAddition(Object mapKey, Object indexKey, Object value,
      RegionEntry entry) throws IMQException
  {
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = this.mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      IndexStatistics stats = null;
      PartitionedIndex prIndex = null;
      if (this.region instanceof BucketRegion) {
        prIndex = (PartitionedIndex) this.getPRIndex();
        stats = prIndex.getStatistics(indexName+"-"+mapKey);
      }
      rg = new RangeIndex(indexName+"-"+mapKey, region, fromClause, indexedExpression,
          projectionAttributes, this.originalFromClause,
          this.originalIndexedExpression, this.canonicalizedDefinitions, stats);
      rg.evaluator = this.evaluator;
      this.mapKeyToValueIndex.put(mapKey, rg);
    }
    rg.internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.saveMapping(indexKey, value, entry);
    //This call is skipped when addMapping is called from MapRangeIndex
    //rg.internalIndexStats.incNumUpdates();
    rg.internalIndexStats.incUpdatesInProgress(-1);
    rg.internalIndexStats.incUpdateTime(System.nanoTime() - start);
    this.entryToMapKeysMap.add(entry, mapKey);
  }

  public Map<Object, RangeIndex> getRangeIndexHolderForTesting()
  {
    return Collections.unmodifiableMap(this.mapKeyToValueIndex);
  }

  public String[] getPatternsForTesting()
  {
    return this.patternStr;
  }

  public Object[] getMapKeysForTesting()
  {
    return this.mapKeys;
  }

  @Override
  public boolean containsEntry(RegionEntry entry)
  {
    // TODO:Asif: take care of null mapped entries
    /*
     * return (this.entryToValuesMap.containsEntry(entry) ||
     * this.nullMappedEntries.containsEntry(entry) ||
     * this.undefinedMappedEntries .containsEntry(entry));
     */
    return this.entryToMapKeysMap.containsEntry(entry);
  }

  @Override
  public boolean isMatchingWithIndexExpression(CompiledValue condnExpr,
      String conditionExprStr, ExecutionContext context)
      throws AmbiguousNameException, TypeMismatchException,
      NameResolutionException
  {
    if (this.isAllKeys) {
      // check if the conditionExps is of type MapIndexable.If yes then check
      // the canonicalized string
      // stripped of the index arg & see if it matches.
      if (condnExpr instanceof MapIndexable) {
        MapIndexable mi = (MapIndexable)condnExpr;
        CompiledValue recvr = mi.getRecieverSansIndexArgs();
        StringBuffer sb = new StringBuffer();
        recvr.generateCanonicalizedExpression(sb, context);
        sb.append('[').append(']');
        return sb.toString().equals(this.patternStr[0]);

      }
      else {
        return false;
      }
    }
    else {
      for (String expr : this.patternStr) {
        if (expr.equals(conditionExprStr)) {
          return true;
        }
      }
      return false;
    }
  }

  @Override
  public boolean isEmpty() {
    return mapKeyToValueIndex.size() == 0 ? true : false;
  }
}
