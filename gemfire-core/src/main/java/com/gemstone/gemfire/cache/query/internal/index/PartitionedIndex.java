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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.query.Index;
import com.gemstone.gemfire.cache.query.IndexExistsException;
import com.gemstone.gemfire.cache.query.IndexNameConflictException;
import com.gemstone.gemfire.cache.query.IndexStatistics;
import com.gemstone.gemfire.cache.query.IndexType;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.QueryInvocationTargetException;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.gemstone.gemfire.cache.query.internal.CompiledValue;
import com.gemstone.gemfire.cache.query.internal.ExecutionContext;
import com.gemstone.gemfire.cache.query.internal.RuntimeIterator;
import com.gemstone.gemfire.cache.query.types.ObjectType;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * This class implements a Partitioned index over a group of partitioned region
 * buckets.
 * 
 * @since 5.1
 * @author Rahul Dubey
 */

public class PartitionedIndex extends AbstractIndex
{

  /**
   * Contains the reference for all the local indexed buckets.
   */
  private final List bucketIndexes = Collections.synchronizedList(new ArrayList());

  /**
   * Type on index represented by this partitioned index.
   * 
   * @see IndexType#FUNCTIONAL
   * @see IndexType#PRIMARY_KEY
   * @see IndexType#HASH
   */
  private final IndexType type;
 
  /**
   * Number of remote buckets indexed when creating an index on the partitioned 
   * region instance.
   */
  private int numRemoteBucektsIndexed;

  /**
   * String for imports if needed for index creations
   */
  private final String imports;

  /**
   * The map to contain IndexStatistics for MapRangeIndex containing indexes, So
   * that we will not have to create multiple copies for buckets.
   */
  private final ConcurrentHashMap mapIndexStats = new ConcurrentHashMap();

  private final ReadWriteLock removeIndexLock = new ReentrantReadWriteLock();

  /**
   * Constructor for partitioned indexed. Creates the partitioned index on given
   * a partitioned region. An index can be created programmatically or through 
   * cache.xml during initialization.
   */
  public PartitionedIndex(IndexType iType, String indexName, Region r,
      String indexedExpression, String fromClause,  String imports) {
    super(indexName, r, fromClause, indexedExpression, null, fromClause, indexedExpression, null, null);
    this.type = iType;
    this.imports = imports;
   
    if (iType == IndexType.HASH) {
      if (!getRegion().getAttributes().getIndexMaintenanceSynchronous()) {
        throw new UnsupportedOperationException(
            LocalizedStrings.DefaultQueryService_HASH_INDEX_CREATION_IS_NOT_SUPPORTED_FOR_ASYNC_MAINTENANCE
                .toLocalizedString());
      }
    }
  }

  /**
   * Adds an index on a bucket to the list of already indexed buckets in the 
   * partitioned region.
   * @param index bucket index to be added to the list.
   */
  public void addToBucketIndexes(Index index)
  {
    synchronized(this.bucketIndexes) {
      bucketIndexes.add(index);
    }
  }

  public void removeFromBucketIndexes(Index index)
  {
    synchronized(this.bucketIndexes) {
      bucketIndexes.remove(index);
    }
  }

  /**
   * Returns the number of locally indexed buckets.
   * 
   * @return int number of buckets.
   */
  public int getNumberOfIndexedBuckets()
  {
    synchronized(this.bucketIndexes) {
      return bucketIndexes.size();
    }
  }

  /**
   * Gets a collection of all the bucket indexes created so far.
   * 
   * @return bucketIndexes collection of all the bucket indexes.
   */
  public List getBucketIndexes()
  {
    synchronized(this.bucketIndexes) {
      List indexes = new ArrayList(this.bucketIndexes.size());
      indexes.addAll(this.bucketIndexes);
      return indexes;
    }
  }

  /**
   * Returns one of the bucket index. 
   * To get all bucket index use getBucketIndexes()
   */
  public Index getBucketIndex()
  {
    Index index = null;
    synchronized(this.bucketIndexes) {
      if (this.bucketIndexes.size() > 0) {
        index = (Index)this.bucketIndexes.get(0);
      }
    }
    return index;
  }
  
  /**
   * Returns the type of index this partitioned index represents.
   * @return  indexType type of partitioned index.
   */
  public IndexType getType()
  {
    return type;
  }

  /**
   * Returns the index for the bucket.
   */
  static public AbstractIndex getBucketIndex(PartitionedRegion pr, String indexName, Integer bId) 
  throws QueryInvocationTargetException {
    try {
      pr.checkReadiness();
    } catch (Exception ex) {
      throw new QueryInvocationTargetException(ex.getMessage());
    }
    PartitionedRegionDataStore prds = pr.getDataStore();
    BucketRegion bukRegion;
    bukRegion = prds.getLocalBucketById(bId);
    if (bukRegion == null) {
      throw new BucketMovedException(
          LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
              .toLocalizedString(),
          bId, pr.getFullPath());
    }
    AbstractIndex index = null;
    if (bukRegion.getIndexManager() != null) {
      index = (AbstractIndex)(bukRegion.getIndexManager().getIndex(indexName));
    } else {
      if (pr.getCache().getLogger().fineEnabled()) {
        pr.getCache().getLogger().fine("Index Manager not found for the bucket region " +
            bukRegion.getFullPath() + " unable to fetch the index " + indexName);
      }
      throw new QueryInvocationTargetException("Index Manager not found, " + 
          " unable to fetch the index " + indexName);
    }

    return index;
  }

  /**
   * Verify if the index is available of the buckets. If not create index
   * on the bucket.
   */
  public void verifyAndCreateMissingIndex(List buckets) throws QueryInvocationTargetException{
    PartitionedRegion pr = (PartitionedRegion)this.getRegion();
    PartitionedRegionDataStore prds = pr.getDataStore();

    for (Object bId : buckets) {
      // create index
      BucketRegion bukRegion = prds.getLocalBucketById((Integer)bId);
      if (bukRegion == null) {
        throw new QueryInvocationTargetException("Bucket not found for the id :" + bId);
      }
      IndexManager im = IndexUtils.getIndexManager(bukRegion, true); 
      if (im.getIndex(indexName) == null) { 
        try {
          if (pr.getCache().getLogger().fineEnabled()) {
            pr.getCache().getLogger().fine("Verifying index presence on bucket region. " +
                " Found index " + this.indexName + " not present on the bucket region " +
                bukRegion.getFullPath() + ", index will be created on this region.");
          }
               
          ExecutionContext externalContext = new ExecutionContext(null, bukRegion.getCache());
          externalContext.setBucketRegion(pr, bukRegion);
          
          im.createIndex(this.indexName, this.type, this.originalIndexedExpression, 
              this.fromClause, this.imports, externalContext, this);
        } catch (IndexExistsException iee) {           
          // Index exists.
        } catch (IndexNameConflictException ince) {
          // ignore.
        } 
      }
    }
  }
  
  public boolean acquireIndexReadLockForRemove() {
    boolean success = this.removeIndexLock.readLock().tryLock();
    if (success) {
      if (logger.fineEnabled()) {
        logger.fine("Acquired read lock on PartitionedIndex " + this.getName());
      }
    }
    return success;
  }

  public void releaseIndexReadLockForRemove() {
    this.removeIndexLock.readLock().unlock();

    if (logger.fineEnabled()) {
      logger.fine("Released read lock on PartitionedIndex " + this.getName());
    }
  }
  
  @Override
  protected boolean isCompactRangeIndex() {
    return false;
  }
  
  /**
   * Set the number of remotely indexed buckets when this partitioned index was 
   * created.
   * @param remoteBucketsIndexed int representing number of remote buckets.
   */
  public void setRemoteBucketesIndexed(int remoteBucketsIndexed)
  {
    this.numRemoteBucektsIndexed = remoteBucketsIndexed;
  }

  /**
   * Returns the number of remotely indexed buckets by this partitioned index.
   * @return int number of remote indexed buckets.
   */
  public int getNumRemoteBucketsIndexed()
  {
    return this.numRemoteBucektsIndexed;
  }

  /**
   * The Region this index is on.
   * @return the Region for this index
   */
  @Override
  public Region getRegion()
  {
    return super.getRegion();
  }

  /**
   * Not supported on partitioned index.
   */
  @Override
  void addMapping(RegionEntry entry) throws IMQException
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());
  }

  /**
   * Not supported on partitioned index.
   */

  @Override
  public void initializeIndex() throws IMQException
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());
  }

  /**
   * Not supported on partitioned index.
   */
  @Override
  void lockedQuery(Object key, int operator, Collection results,
      CompiledValue iterOps, RuntimeIterator indpndntItr,
      ExecutionContext context, List projAttrib,SelectResults intermediateResults, boolean isIntersection)
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());

  }

  /**
   * Not supported on partitioned index.
   */
  @Override
  void recreateIndexData() throws IMQException
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());

  }

  /**
   * Not supported on partitioned index.
   */
  @Override
  void removeMapping(RegionEntry entry, int opCode)
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());

  }

  /**
   * Returns false, clear is not supported on partitioned index.
   */

  public boolean clear() throws QueryException
  {
    return false;
  }

  /**
   * Not supported on partitioned index.
   */
  /*
  public void destroy()
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());
  }
  */

  /**
   * Not supported on partitioned index.
   */
  @Override
  public IndexStatistics getStatistics()
  {
    return this.internalIndexStats;
  }
  
  /**
   * Returns string representing imports.
   */
  public String getImports() {
    return imports;
  }

  /**
   * String representing the state.
   * 
   * @return string representing all the relevant information.
   */
  @Override
  public String toString()
  {
    StringBuffer st = new StringBuffer();
    st.append(super.toString()).append("imports : ").append(imports);
    return st.toString();
  }
  @Override
  protected InternalIndexStatistics createStats(String indexName) {
    if (this.internalIndexStats == null) {
      this.internalIndexStats = new PartitionedIndexStatistics(this.indexName);
    }   
    return this.internalIndexStats;
  }

  /**
   * This will create extra {@link IndexStatistics} statistics for MapType
   * PartitionedIndex.
   * @param indexName
   * @return New {@link PartitionedIndexStatistics}
   */
  protected InternalIndexStatistics createExplicitStats(String indexName) {
    return new PartitionedIndexStatistics(indexName);
  }
  /**
   * Internal class for partitioned index statistics. Statistics are not
   * supported right now.
   * 
   * @author rdubey
   */
  class PartitionedIndexStatistics extends InternalIndexStatistics {
    private final IndexStats vsdStats;
    
    public PartitionedIndexStatistics(String indexName) {
      this.vsdStats = new IndexStats(getRegion().getCache()
                                     .getDistributedSystem(), indexName);
    }
    
    /**
     * Return the total number of times this index has been updated
     */
    @Override
    public long getNumUpdates() {
      return this.vsdStats.getNumUpdates();
    }
    
    @Override
    public void incNumValues(int delta) {
      this.vsdStats.incNumValues(delta);
    }
    
    @Override
    public void incNumUpdates() {
      this.vsdStats.incNumUpdates();
    }
    
    @Override
    public void incNumUpdates(int delta) {
      this.vsdStats.incNumUpdates(delta);
    }
    
    @Override
    public void updateNumKeys(long numKeys) {
      this.vsdStats.updateNumKeys(numKeys);
    }
    
    @Override
    public void incNumKeys(long numKeys) {
      this.vsdStats.incNumKeys(numKeys);
    }

    @Override
    public void incUpdateTime(long delta) {
      this.vsdStats.incUpdateTime(delta);
    }
    @Override
    public void incUpdatesInProgress(int delta) {
      this.vsdStats.incUpdatesInProgress(delta);
    }
    
    @Override
    public void incNumUses() {
      this.vsdStats.incNumUses();
    }
    @Override
    public void incUseTime(long delta) {
      this.vsdStats.incUseTime(delta);
    }
    @Override
    public void incUsesInProgress(int delta) {
      this.vsdStats.incUsesInProgress(delta);
    }
    
    /**
     * Returns the total amount of time (in nanoseconds) spent updating this
     * index.
     */
    @Override
    public long getTotalUpdateTime() {
      return this.vsdStats.getTotalUpdateTime();
    }
    
    /**
     * Returns the total number of times this index has been accessed by a
     * query.
     */
    @Override
    public long getTotalUses() {
      return this.vsdStats.getTotalUses();
    }
    
    /**
     * Returns the number of keys in this index.
     */
    @Override
    public long getNumberOfKeys() {
      return this.vsdStats.getNumberOfKeys();
    }
    
    /**
     * Returns the number of values in this index.
     */
    @Override
    public long getNumberOfValues() {
      return this.vsdStats.getNumberOfValues();
    }
    
    @Override
    public void close() {
      this.vsdStats.close();
    }
    
    @Override
    public String toString() {
      StringBuffer sb = new StringBuffer();
      sb.append("No Keys = ").append(getNumberOfKeys()).append("\n");
      sb.append("No Values = ").append(getNumberOfValues()).append("\n");
      sb.append("No Uses = ").append(getTotalUses()).append("\n");
      sb.append("No Updates = ").append(getNumUpdates()).append("\n");
      sb.append("Total Update time = ").append(getTotalUpdateTime()).append("\n");
      return sb.toString();
    }
  }

  @Override
  void instantiateEvaluator(IndexCreationHelper ich) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ObjectType getResultSetType() {
    throw new UnsupportedOperationException();
  }

  /**
   * Not supported on partitioned index.
   */
  @Override
  void lockedQuery(Object lowerBoundKey, int lowerBoundOperator,
      Object upperBoundKey, int upperBoundOperator, Collection results,
      Set keysToRemove, ExecutionContext context) throws TypeMismatchException
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());

  }

  public int getSizeEstimate(Object key, int op, int matchLevel)
  {
    throw new UnsupportedOperationException(
        "This method should not have been invoked");
  }

  
  @Override
  void lockedQuery(Object key, int operator, Collection results, Set keysToRemove,
      ExecutionContext context ) throws TypeMismatchException
  {
    throw new RuntimeException("Not supported on partitioned index");
    
  }

  @Override
  void addMapping(Object key, Object value, RegionEntry entry)
      throws IMQException
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());
    
  }

  @Override
  void saveMapping(Object key, Object value, RegionEntry entry)
      throws IMQException
  {
    throw new RuntimeException(LocalizedStrings.PartitionedIndex_NOT_SUPPORTED_ON_PARTITIONED_INDEX.toLocalizedString());
    
  }

  /**
   * If this {@link PartitionedIndex} represents a {@link MapRangeIndex}
   * then for each map-key separate {@link IndexStatistics} will be maintained.
   * @param indexName
   * @return IndexStatistics for a MapIndex Key
   */
  public IndexStatistics getStatistics(String indexName) {
    //This is a Map type index.
    IndexStatistics stats = (IndexStatistics) mapIndexStats.get(indexName);
    if (stats == null) {
      stats = createExplicitStats(indexName);
      mapIndexStats.put(indexName, stats);
    }
    return stats;
  }

  public ConcurrentHashMap getMapIndexStats() {
    return mapIndexStats;
  }

  @Override
  public boolean isEmpty() {
    boolean empty = true;
    for (Object index : getBucketIndexes()){
      empty = ((AbstractIndex)index).isEmpty();
      if(!empty){
        return false;
      }
    }
    return empty;
  }

  /**
   * This makes current thread wait until all query threads are done using it.
   */
  public void acquireLockForRemoveIndex() {
    if (logger.fineEnabled()) {
      logger.fine("Acquiring write lock on PartitionedIndex " + this.getName());
    }
    removeIndexLock.writeLock().lock();
    if (logger.fineEnabled()) {
      logger.fine("Acquired write lock on PartitionedIndex " + this.getName());
    }
  }

  public void releaseLockForRemoveIndex() {
    if (logger.fineEnabled()) {
      logger.fine("Releasing write lock on PartitionedIndex " + this.getName());
    }
    removeIndexLock.writeLock().unlock();
    if (logger.fineEnabled()) {
      logger.fine("Released write lock on PartitionedIndex " + this.getName());
    }
  }
}
