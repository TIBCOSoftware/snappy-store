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

package com.pivotal.gemfirexd.internal.engine.ddl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheListener;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.RegionQueue;
import com.gemstone.gemfire.internal.cache.RegionQueueException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gnu.trove.TLongHashSet;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdOpConflationHandler;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegion.RegionValue;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdReadWriteLock;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdReentrantReadWriteLock;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.execute.CreateSchemaConstantAction;

/**
 * Class <code>GfxdDDLRegionQueue</code> is a <code>RegionQueue</code>
 * implementation for DDL messages. This is used for replaying messages in
 * original order when a node comes up and can handle multiple threads putting
 * entries into it and single VM (can have multiple threads) taking entries from
 * it. This does not take a distributed lock so does not guarantee that the
 * order of elements in the queue will be uniform in all caches in the
 * distributed system. However, it does guarantee that the order of elements
 * inserted by one VM will be the same in all VMs. If there can be multiple VMs
 * taking entries from the queue, then an explicit distributed lock must be
 * obtained before invoking {@link #take}.
 * 
 * @author swale
 * @since 6.1
 */
public final class GfxdDDLRegionQueue implements RegionQueue {

  private final GfxdDDLRegion region;

  private final String regionName;

  private final SortedSet<QueueValue> queue;

  /**
   * this set is to track and ignore any duplicates; can't do with queue alone
   * (or conflation index) since items will be removed from those
   */
  private final TLongHashSet queueTracker;

  private long queueSequenceId;

  private final GfxdReadWriteLock queueLock;

  private final GfxdOpConflationHandler<QueueValue> conflationHandler;

  private volatile boolean initialized;

  private boolean cleared;

  /**
   * Constructor for DDL region queue.
   * 
   * @param regionName
   *          The name of region on which to create the queue. This region must
   *          not exist.
   * @param cache
   *          The GemFire cache.
   * @param persistDD
   *          boolean value if true indicates that data dictionary needs to be
   *          persisted
   * @param persistentDir
   *          Directory path to be used for persistence of this queue.
   * @param listener
   *          Any {@link CacheListener} to be attached to the DDL region.
   */
  public GfxdDDLRegionQueue(String regionName, GemFireCacheImpl cache,
      boolean persistDD, String persistentDir,
      CacheListener<Long, RegionValue> listener) {
    this.regionName = regionName;
    this.queue = new TreeSet<QueueValue>();
    this.queueTracker = new TLongHashSet();
    this.queueSequenceId = 1;
    this.queueLock = new GfxdReentrantReadWriteLock("GfxdDDLRegionQueue",
        false);
    this.conflationHandler = new GfxdOpConflationHandler<QueueValue>();
    this.initialized = false;
    this.cleared = false;
    try {
      this.region = GfxdDDLRegion.createInstance(this, cache, this.regionName,
          listener, persistDD, persistentDir);
    } catch (IOException e) {
      throw new InternalGemFireError(
          LocalizedStrings.GemFireCache_UNEXPECTED_EXCEPTION
              .toLocalizedString(), e);
    } catch (ClassNotFoundException e) {
      throw new InternalGemFireError(
          LocalizedStrings.GemFireCache_UNEXPECTED_EXCEPTION
              .toLocalizedString(), e);
    }
  }

  /**
   * Constructor to wrap an existing DDL region with a queue.
   * 
   * @param region
   *          The DDL region on which to create the queue. This region must
   *          already exist.
   */
  public GfxdDDLRegionQueue(final GfxdDDLRegion region) {
    this.region = region;
    this.regionName = region.getName();
    this.queue = new TreeSet<QueueValue>();
    this.queueTracker = new TLongHashSet();
    this.queueSequenceId = 1;
    this.queueLock = new GfxdReentrantReadWriteLock("GfxdDDLRegionQueue",
        false);
    this.conflationHandler = new GfxdOpConflationHandler<QueueValue>();
    this.initialized = false;
    this.cleared = false;
  }
  
  public void initializeQueue(GfxdDataDictionary dd) {
    initializeQueue(dd, true);
  }
  
  /**
   * Initialize and populate the queue using the region as backing store.
   */
  public void initializeQueue(GfxdDataDictionary dd, boolean takeLock) {
    final List<QueueValue> removeList;
    // take DD read lock to avoid missing DDLs whose update comes before queue
    // population actually starts but after region GII
    if (takeLock) {
      dd.lockForReadingInDDLReplay(Misc.getMemStoreBooting());
    }
    try {
      // lock the queue during population
      lockQueue(true);
    } finally {
      if (takeLock) {
        // release DDL read lock
        dd.unlockAfterReading(null);
      }
    }
    this.conflationHandler.setLogPrefix(toString());
    try {
      removeList = populateQueue();
      this.initialized = true;
    } finally {
      unlockQueue(true);
    }
    // conflate the underlying region if possible
    this.region.doConflate(removeList, "INITQUEUE");
  }
  
  
  /**
   * Populate the {@link #queue} with initial data.
   */
  private List<QueueValue> populateQueue() {
    for (Object entry : this.region.entrySet()) {
      final Region.Entry<?, ?> rEntry = (Region.Entry<?, ?>)entry;
      final Object key;
      if (!rEntry.isDestroyed() && (key = rEntry.getKey()) instanceof Long) {
        addToQueue(new QueueValue((Long)key, (RegionValue)rEntry.getValue()),
            false, null);
      }
    }
    final ArrayList<QueueValue> removeList = new ArrayList<QueueValue>();
    for (QueueValue qEntry : this.queue) {
      doConflate(qEntry, removeList, null, false);
    }
    for (QueueValue qEntry : removeList) {
      this.queue.remove(qEntry);
    }
    return removeList;
  }

  /**
   * Add a <code>QueueValue</code> to the queue and return true if item was
   * successfully inserted else false if item key was already present in queue.
   * Not thread-safe.
   */
  boolean addToQueue(QueueValue qValue, boolean conflate,
      List<QueueValue> conflatedItems) {
    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
          + ": trying to add entry in queue [" + qValue + "]");
    }
    final long itemId = qValue.getKey().longValue();
    // [sumedh] If we happen to get a duplicate from another VM after or
    // during GII then it will come in as a create since for that VM it is
    // a create, so need to skip it.
    if (!this.queueTracker.contains(itemId)) {
      long sequenceId = qValue.regValue.sequenceId;
      if (sequenceId <= 0) {
        sequenceId = qValue.regValue.sequenceId = this.queueSequenceId++;
      }
      else if (sequenceId >= this.queueSequenceId) {
        this.queueSequenceId = sequenceId + 1;
      }
      if (conflate && doConflate(qValue, conflatedItems, this.queue, true)) {
        return true;
      }
      this.queue.add(qValue);
      this.queueTracker.add(itemId);
      if (GemFireXDUtils.TraceDDLQueue) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
            + ": added entry in queue [" + qValue + "]");
      }
      return true;
    }
    return false;
  }

  /**
   * Find conflatable items and remove from queue, if required, for the given
   * queue entry but do not add this entry to the conflation index or remove
   * conflated items from conflation index.
   */
  public final boolean conflate(Conflatable confVal, Object confValEntry,
      boolean removeFromQueue, List<QueueValue> conflatedItems) {
    if (confVal.getRegionToConflate() != null) {
      lockQueue(true);
      try {
        return this.conflationHandler.applyConflate(confVal,
            confVal.getKeyToConflate(), confValEntry, conflatedItems, null,
            (removeFromQueue ? this.queue : null), false, false);
      } finally {
        unlockQueue(true);
      }
    }
    return false;
  }

  /**
   * Do conflation for the given queue entry and return true if there was
   * conflation for the entry else return false. Not thread-safe.
   */
  private boolean doConflate(QueueValue qValue,
      List<QueueValue> conflatedItems, SortedSet<QueueValue> queue,
      boolean skipExecuting) {
    final Object val = qValue.getValue();
    // if value is a Conflatable then conflate either all entries
    // corresponding to the key of Conflatable, or for the case when
    // key is null then conflate all entries corresponding to the "region"
    // of Conflatable
    if (val instanceof Conflatable) {
      final Conflatable confVal = (Conflatable)val;
      if (confVal.getRegionToConflate() != null) {
        if (this.conflationHandler.doConflate(confVal,
            confVal.getKeyToConflate(), qValue, conflatedItems, queue, true,
            skipExecuting)) {
          return true;
        }
        if (GemFireXDUtils.TraceConflation) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION, toString()
              + ": adding conflatable entry " + qValue
              + " with region=" + confVal.getRegionToConflate()
              + " key=" + confVal.getKeyToConflate()
              + " value=" + confVal.getValueToConflate());
        }
        this.conflationHandler.addToConflationIndex(confVal, qValue);
      }
    }
    return false;
  }

  /**
   * Remove a <code>QueueValue</code> from the queue. Not thread-safe. Always
   * invoke inside {@link #queueLock} write lock.
   */
  boolean removeFromQueue(QueueValue val) {
    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
          + ": removing entry from queue: " + val);
    }
    if (this.queueTracker.remove(val.getKey().longValue())) {
      // also clear conflatableIndex if value is a Conflatable
      if (val.getValue() instanceof Conflatable) {
        this.conflationHandler.removeFromConflationIndex((Conflatable)val
            .getValue());
      }
      this.queue.remove(val);
      if (this.queue.size() > 0) {
        this.queueSequenceId = this.queue.last().regValue.sequenceId + 1;
      }
      else {
        this.queueSequenceId = 1;
      }
      return true;
    }
    return false;
  }

  /**
   * Puts an object onto the tail of the queue.
   * 
   * @param object
   *          The object to put onto the queue.
   * 
   * @throws InterruptedException
   * @throws CacheException
   */
  public void put(Object object) throws InterruptedException, CacheException {
    long uuid = newUUID();
    put(Long.valueOf(uuid), object);
  }

  /**
   * Get a DS-wide unique long key that can be used for putting an object in the
   * queue.
   * 
   * @throws IllegalStateException
   *           if UUID overflows the total available in the entire distributed
   *           system
   */
  public final long newUUID() throws IllegalStateException {
    return this.region.newUUID(true);
  }

  /**
   * Put an element into the queue given a unique ID to be used for the new
   * object in the region backing the queue.
   * 
   * @param key
   *          The unique ID to be used for the object.
   * @param object
   *          The object to put onto the queue.
   * 
   * @return the sequence ID assigned to the key
   * 
   * @throws InterruptedException
   * @throws CacheException
   */
  public long put(Long key, Object object) throws InterruptedException,
      CacheException {
    return put(key, -1, object, true, false);
  }

  /**
   * Put an element into the queue given a unique ID to be used for the new
   * object in the region backing the queue.
   * 
   * @param key
   *          The unique ID to be used for the object.
   * @param sequenceId
   *          The sequence ID to be used for the object. Should normally be only
   *          used when updating an existing value using the return value of
   *          {@link #put(Long, long, Object, boolean, boolean)}.
   * @param object
   *          The object to put onto the queue.
   * @param conflate
   *          True to conflate entries from the queue and the underlying region.
   *          It is assumed that some kind of global lock is being held
   *          (GfxdDataDictionary) so that there is no case where another VM is
   *          doing a GII and sees partial state.
   * @param localPut
   *          If true then put is only done locally in the queue's region and is
   *          not distributed to other VMs.
   * 
   * @return the actual sequence ID assigned to the key
   * 
   * @throws InterruptedException
   * @throws CacheException
   */
  public long put(Long key, long sequenceId, Object object, boolean conflate,
      boolean localPut) throws InterruptedException, CacheException {

    final QueueValue qValue = new QueueValue(key, new RegionValue(object,
        sequenceId));
    final EntryEventImpl event = this.region.newUpdateEntryEvent(key,
        qValue.regValue, null);
    if (localPut) {
      // explicitly mark event as local
      event.setSkipDistributionOps();
    }
    if (conflate) {
      final List<QueueValue> conflatedItems = new ArrayList<>(4);
      putInQueue(qValue, true, conflatedItems);
      // put in the region in any case so all VMs get it in the queue region
      // and conflate independently
      this.region.validatedPut(event, CachePerfStats.getStatTime());
      // locally conflate entries from the region
      this.region.doConflate(conflatedItems, qValue);
    }
    else {
      putInQueue(qValue, false, null);
      this.region.validatedPut(event, CachePerfStats.getStatTime());
    }
    // TODO OFFHEAP: validatedPut calls freeOffHeapResources;
    return qValue.regValue.sequenceId;
  }

  /**
   * Put an element into the queue given a {@link QueueValue} with unique ID to
   * be used for the new object in the region backing the queue. However, it
   * does not put the value in the region so should be used only when value is
   * supposed to be received in the region at a later time.
   * 
   * @param qValue
   *          The {@link QueueValue} to be inserted into the queue.
   * @param conflate
   *          True to conflate entries from the queue.
   * @param conflatedItems
   *          If non-null and "conflate" was true, then the items that were
   *          conflated in queue as a result of this put are filled in.
   * 
   * @return true if the insertion was successful and false otherwise
   * 
   * @throws InterruptedException
   * @throws CacheException
   */
  private boolean putInQueue(QueueValue qValue, boolean conflate,
      List<QueueValue> conflatedItems) 
      throws InterruptedException, EntryExistsException {
    lockQueue(true);
    try {
      if (!addToQueue(qValue, conflate, conflatedItems)) {
        if (!Misc.getMemStoreBooting().isHadoopGfxdLonerMode())
          throw new InternalGemFireError("expected to add " + qValue
              + " to queue");
        else{
          // This can happen when same ddl from two hdfs stores are 
          // being replayed. 
          // See {@link HadoopGfxdLonerConfig#loadDDLQueueWithDDLsFromHDFS(GfxdDDLRegionQueue)}
          throw new EntryExistsException("expected to add " + qValue
              + " to queue", qValue);
        }
      }
    } finally {
      unlockQueue(true);
    }
    return true;
  }

  /**
   * Peeks the first object from the head of the queue without removing it. This
   * method returns null if there are no objects on the queue.
   * 
   * @return The object peeked.
   * 
   * @throws InterruptedException
   * @throws CacheException
   */
  public GfxdDDLQueueEntry peek() throws CacheException {
    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
          + ": Peeking an object");
    }
    lockQueue(false);
    try {
      if (isCleared()) {
        throw new RegionQueueException(toString() + ": queue has been cleared.");
      }
      if (!this.queue.isEmpty()) {
        QueueValue qValue = this.queue.first();
        if (GemFireXDUtils.TraceDDLQueue) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
              + ": Peeked object -> " + qValue);
        }
        return qValue;
      }
    } finally {
      unlockQueue(false);
    }
    return null;
  }

  /**
   * Peeks up to batchSize number of objects from the head of the queue without
   * removing them. As soon as it gets a null response from a peek, it stops
   * peeking.
   * 
   * @param batchSize
   *          The number of objects to peek from the queue. A value less than
   *          zero indicates all elements currently in the queue.
   * 
   * @return The list of objects peeked.
   * 
   * @throws InterruptedException
   * @throws CacheException
   */
  public List<GfxdDDLQueueEntry> peek(int batchSize)
      throws InterruptedException, CacheException {
    return peekOrTake(null, batchSize, -1, false, false);
  }

  /** Not implemented. */
  public List<GfxdDDLQueueEntry> peek(int batchSize, int timeToWait)
      throws InterruptedException, CacheException {
    throw new UnsupportedOperationException("Not implemented");
  }

  /**
   * Peeks ahead a certain number of entries (for batch peeks) starting at the
   * given offset and removes those objects from the queue but not from the
   * underlying region (unlike {@link #take()}. Returns a {@link List} of
   * {@link Region.Entry} objects.
   * 
   * @param batchSize
   *          The number of elements to peek from the queue. A value less than
   *          zero indicates all elements currently in the queue.
   * @param timeToWait
   *          The maximum time to wait while getting the elements. This is
   *          currently ignored.
   * 
   * @return The list of objects peeked.
   * 
   * @throws InterruptedException
   *           in case of an interrupt
   * @throws CacheException
   *           in case of an exception in Cache
   */
  public List<GfxdDDLQueueEntry> peekAndRemoveFromQueue(int batchSize,
      int timeToWait) throws InterruptedException, CacheException {
    return peekOrTake(null, batchSize, timeToWait, true, false);
  }

  /**
   * Takes the first object from the head of the queue. This method returns null
   * if there are no objects on the queue.
   * 
   * @return The object taken.
   * 
   * @throws CacheException
   * @throws InterruptedException
   */
  public GfxdDDLQueueEntry take() throws CacheException {
    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
          + ": Taking an object");
    }
    QueueValue qValue = null;
    lockQueue(true);
    try {
      if (!this.queue.isEmpty()) {
        qValue = this.queue.last();
        if (!removeFromQueue(qValue)) {
          throw new AssertionError("expected to remove element [" + qValue
              + "] from queue");
        }
      }
    } finally {
      unlockQueue(true);
    }
    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
          + ": Took object -> " + qValue);
    }
    if (qValue != null) {
      try {
        this.region.destroy(qValue.key);
      } catch (Exception ex) {
        SanityManager.DEBUG_PRINT("warning:" + GfxdConstants.TRACE_DDLQUEUE,
            toString() + " unexpected exception in take", ex);
      }
      if (GemFireXDUtils.TraceDDLQueue) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
            + ": Destroyed entry " + qValue);
      }
    }
    return qValue;
  }

  /**
   * Takes up to batchSize number of objects from the head of the queue. As soon
   * as it gets a null response from a take, it stops taking.
   * 
   * @param batchSize
   *          The number of objects to take from the queue. A value less than
   *          zero indicates all elements currently in the queue.
   * 
   * @return the <code>List</code> of objects taken from the queue.
   * 
   * @throws CacheException
   * @throws InterruptedException
   */
  public List<GfxdDDLQueueEntry> take(int batchSize) throws CacheException,
      InterruptedException {
    return peekOrTake(null, batchSize, -1, true, true);
  }

  /**
   * Peeks ahead or {@link #take}s a certain number of entries (for batch peeks)
   * starting at the given offset. Returns a {@link List} of
   * {@link Region.Entry} objects.
   * 
   * @param startEntry
   *          The start entry for peek. This can be null in which case the peek
   *          is started from the beginning. If it is not null, then the peek is
   *          started from the entry given <b>excluding</b> the given entry.
   * @param batchSize
   *          The number of elements to peek from the queue. A value less than
   *          zero indicates all elements currently in the queue.
   * @param timeToWait
   *          The maximum time to wait while getting the elements. This is
   *          currently ignored.
   * @param take
   *          True if entries need to be "taken" i.e. removed from region.
   * 
   * @return the <code>List</code> of objects peeked or taken from the queue.
   * 
   * @throws CacheException
   * @throws InterruptedException
   */
  private List<GfxdDDLQueueEntry> peekOrTake(GfxdDDLQueueEntry startEntry,
      int batchSize, int timeToWait, boolean take, boolean destroyFromRegion)
      throws CacheException {
    assert startEntry == null || startEntry instanceof QueueValue: "did "
        + " not expect startEntry of type " + startEntry.getClass();
    int origBatchSize = batchSize;

    if (GemFireXDUtils.TraceDDLQueue) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString() + ": "
          + (take ? "Taking " : "Peeking ") + batchSize
          + " objects starting from entry " + startEntry);
    }
    // deliberately using LinkedList below since callers frequently
    // use remove() methods on the list
    final LinkedList<GfxdDDLQueueEntry> entries =
        new LinkedList<GfxdDDLQueueEntry>();
    lockQueue(take);
    try {
      if (!take && isCleared()) {
        throw new RegionQueueException(toString() + ": queue has been cleared.");
      }
      SortedSet<QueueValue> peekSet;
      QueueValue startValue = (QueueValue)startEntry;
      if (startValue != null) {
        peekSet = this.queue.tailSet(startValue);
      }
      else {
        peekSet = this.queue;
      }
      for (QueueValue qValue : peekSet) {
        // skip the given startValue
        if (startValue != null
            && startValue.key.longValue() == qValue.key.longValue()) {
          continue;
        }
        if (batchSize-- == 0) {
          break;
        }
        entries.add(qValue);
        if (GemFireXDUtils.TraceDDLQueue) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
              + ": " + (take ? "Took" : "Peeked") + " object -> " + qValue);
        }
      }
      if (take) {
        QueueValue qValue;
        boolean removed;
        for (Object entry : entries) {
          qValue = (QueueValue)entry;
          if (destroyFromRegion) {
            removed = removeFromQueue(qValue);
          }
          else {
            // don't remove from conflation index since a complimentary
            // conflatable might come in during execution
            removed = this.queue.remove(entry);
          }
          if (!removed) {
            throw new InternalGemFireError("expected to remove element ["
                + entry + "] from queue");
          }
        }
      }
    } finally {
      unlockQueue(take);
    }
    if (destroyFromRegion) {
      for (GfxdDDLQueueEntry entry : entries) {
        try {
          this.region.destroy(entry.getKey());
        } catch (Exception ex) {
          SanityManager.DEBUG_PRINT("warning:" + GfxdConstants.TRACE_DDLQUEUE,
              toString() + " unexpected exception in peekOrTake", ex);
        }
        if (GemFireXDUtils.TraceDDLQueue) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLQUEUE, toString()
              + ": Destroyed entry " + entry);
        }
      }
    }
    assert origBatchSize < 0 || entries.size() <= origBatchSize: "expected "
        + "to retrieve at most " + origBatchSize + " elements but got "
        + entries.size() + ": " + entries;
    return entries;
  }

  /**
   * Removes a single object from the head of the queue without returning it.
   * This method assumes that the queue contains at least one object.
   * 
   * @throws InterruptedException
   * @throws CacheException
   */
  public void remove() throws InterruptedException, CacheException {
    take();
  }

  /** Not implemented. */
  public void remove(int top) throws CacheException {
    throw new UnsupportedOperationException("Not implemented");
  }

  public GfxdDDLRegion getRegion() {
    return this.region;
  }

  /**
   * Returns the size of the queue
   * 
   * @return the size of the queue
   */
  public int size() {
    return this.region.size();
  }

  final void lockQueue(boolean exclusive) {
    GfxdDDLRegion.acquireLock(this.queueLock, exclusive, this.region);
  }

  final void unlockQueue(boolean exclusive) {
    GfxdDDLRegion.releaseLock(this.queueLock, exclusive);
  }

  /**
   * Add a <code>CacheListener</code> to the queue. This method is NOT
   * THREAD-SAFE.
   * 
   * @param listener
   *          the <code>CacheListener</code> to add
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public void addCacheListener(CacheListener listener) {
    if (this.region != null) {
      this.region.getAttributesMutator().addCacheListener(listener);
    }
  }

  /** not implemented */
  public void removeCacheListener() {
    throw new UnsupportedOperationException("removing listener not allowed");
  }

  /**
   * Get the DDL queue after pre-processing like pushing system procedures, jar
   * procedures at the start, removing local DDLs etc.
   */
  public List<GfxdDDLQueueEntry> getPreprocessedDDLQueue(
      final List<GfxdDDLQueueEntry> currentQueue,
      final Map<DDLConflatable, DDLConflatable> skipRegionInit,
      String currentSchema, TObjectIntHashMap pre11TableSchemaVer,
      boolean traceConflation) {
    // push all system procedures at the start of queue since they may change
    // the behaviour of the system which can be required for DDLs etc.
    final ArrayList<GfxdDDLQueueEntry> preprocessedQueue =
        new ArrayList<GfxdDDLQueueEntry>();
    ListIterator<GfxdDDLQueueEntry> iter = currentQueue.listIterator();
    GfxdDDLPreprocess preprocessMsg;
    if (currentSchema == null) {
      currentSchema = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
    }
    while (iter.hasNext()) {
      final GfxdDDLQueueEntry entry = iter.next();
      final Object qVal = entry.getValue();
      if (qVal instanceof ReplayableConflatable) {
        // remove from queue if this object is marked to be skipped
        // in local execution
        if (((ReplayableConflatable)qVal).skipInLocalExecution()) {
          iter.remove();
          continue;
        }
      }
      // system/jar procedures should be executed at the start
      if (qVal instanceof GfxdDDLPreprocess
          && (preprocessMsg = (GfxdDDLPreprocess)qVal).preprocess()) {
        if (pre11TableSchemaVer != null
            && preprocessMsg instanceof GfxdSystemProcedureMessage) {
          GfxdSystemProcedureMessage m = (GfxdSystemProcedureMessage)qVal;
          // check for persisted PRE11_RECOVERY_SCHEMA_VERSIONs
          if (m.getSysProcMethod() == GfxdSystemProcedureMessage
              .SysProcMethod.setDatabaseProperty) {
            Object[] params = m.getParameters();
            String key = (String)params[0];
            if (key != null && key.startsWith(
                GfxdConstants.PRE11_RECOVERY_SCHEMA_VERSION)) {
              pre11TableSchemaVer.put(key
                  .substring(GfxdConstants.PRE11_RECOVERY_SCHEMA_VERSION
                      .length()), Integer.parseInt((String)params[1]));
            }
          }
        }
        preprocessedQueue.add(entry);
        iter.remove();
      }
      // also check if region intialization should be skipped for
      // any of the regions due to ALTER TABLE (#44280)
      else if (qVal instanceof DDLConflatable) {
        final DDLConflatable ddl = (DDLConflatable)qVal;
        if (skipRegionInit == null) {
          // need to add explicit "CREATE SCHEMA" DDLs for this case if the
          // schema changes
          String newSchema = ddl.getCurrentSchema();
          if (newSchema == null) {
            newSchema = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
          }
          if (!currentSchema.equals(newSchema)) {
            DDLConflatable schemaDDL = new DDLConflatable("SET SCHEMA "
                + newSchema, newSchema, new CreateSchemaConstantAction(
                newSchema, null), null, null, 0, true, null);
            final QueueValue qValue = new QueueValue(0L, new RegionValue(
                schemaDDL, 0L));
            iter.add(qValue);
          }
          currentSchema = newSchema;
        }
        else if (ddl.shouldDelayRegionInitialization()) {
          final ArrayList<QueueValue> conflatedItems = new ArrayList<>(4);
          if (conflate(ddl, ddl, false, conflatedItems)) {
            for (QueueValue confItem : conflatedItems) {
              Object confVal = confItem.getValue();
              DDLConflatable createDDL;
              if (confVal instanceof DDLConflatable
                  && (createDDL = (DDLConflatable)confVal).isCreateTable()) {
                // update the mapping for CREATE TABLE to this ALTER
                skipRegionInit.put(createDDL, ddl);
                if (traceConflation) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
                      "FabricDatabase: delaying initializing [" + confVal
                          + "] for DDL [" + ddl + ']');
                }
                // expect only one CREATE TABLE at max, so break now
                break;
              }
            }
          }
        }
      }
    }
    preprocessedQueue.addAll(currentQueue);
    return preprocessedQueue;
  }

  @Override
  public String toString() {
    return "GemFireXD DDL region queue on " + this.regionName;
  }

  /**
   * Clear all elements in the queue and queueMap but not from region. Also
   * close the queue for any peek or take operations since it will return
   * incorrect operations though underlying region and queue will still keep
   * getting populated and conflated.
   */
  public void clearQueue() {
    lockQueue(true);
    try {
      this.queue.clear();
      this.queueTracker.clear();
      this.cleared = true;
    } finally {
      unlockQueue(true);
    }
  }

  /** close the queue and its underlying region */
  @Override
  public void close() {
    lockQueue(true);
    try {
      this.region.close();
      this.queue.clear();
      this.queueSequenceId = 1;
      this.conflationHandler.close();
    } finally {
      unlockQueue(true);
    }
  }

  public boolean isInitialized() {
    return this.initialized;
  }

  boolean isCleared() {
    return this.cleared;
  }

  /**
   * Encapsulates the key and {@link RegionValue}.
   * 
   * @author swale
   */
  public static final class QueueValue implements GfxdDDLQueueEntry,
      Comparable<QueueValue> {

    private final Long key;

    private final RegionValue regValue;

    QueueValue(Long key, RegionValue value) {
      this.key = key;
      this.regValue = value;
    }

    public Long getKey() {
      return this.key;
    }

    public Object getValue() {
      return this.regValue.getValue();
    }

    public long getSequenceId() {
      return this.regValue.sequenceId;
    }

    public void setSequenceId(long seqId) {
      this.regValue.sequenceId = seqId;
    }

    public int compareTo(QueueValue other) {
      final long thisSeqId = this.regValue.sequenceId;
      final long otherSeqId = other.regValue.sequenceId;
      return (thisSeqId < otherSeqId ? -1 : (thisSeqId == otherSeqId ? this.key
          .compareTo(other.key) : 1));
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof QueueValue) {
        return (this.key.longValue() == ((QueueValue)other).key.longValue());
      }
      return false;
    }

    @Override
    public int hashCode() {
      return this.key.hashCode();
    }

    @Override
    public String toString() {
      return "{QueueValue: key=" + this.key + ", value=" + this.regValue + '}';
    }
  }
  
  public long estimateMemoryFootprint(SingleObjectSizer sizer) {
    return sizer.sizeof(this) + sizer.sizeof(queue)
        + sizer.sizeof(queueTracker) + sizer.sizeof(conflationHandler);
  }

  @Override
  public void release() {
   
  }
  
}
