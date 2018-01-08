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

package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.AttributesMutator;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheListener;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.DiskWriteAttributes;
import com.gemstone.gemfire.cache.DiskWriteAttributesFactory;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.util.GatewayQueueAttributes;
import com.gemstone.gemfire.distributed.GatewayCancelledException;

/**
 * Class <code>SingleWriteSingleReadRegionQueue</code> is a
 * <code>RegionQueue</code> implementation that expects a single thread
 * putting entries into it and a single thread taking entries from it.
 *
 * @author Barry Oglesby
 *
 * @since 4.2
 */
public class SingleWriteSingleReadRegionQueue implements RegionQueue
{

  /**
   * The key into the <code>Region</code> used when taking entries from the
   * queue. This value is either set when the queue is instantiated or read from
   * the <code>Region</code> in the case where this queue takes over where a
   * previous one left off.
   */
  protected long _headKey = -1;

  /**
   * The key into the <code>Region</code> used when putting entries onto the
   * queue. This value is either set when the queue is instantiated or read from
   * the <code>Region</code> in the case where this queue takes over where a
   * previous one left off.
   */
  protected final AtomicLong _tailKey = new AtomicLong();

  protected final LinkedList<Long> _peekedIds = new LinkedList<Long>();
  /**
   * The name of the <code>Region</code> backing this queue
   */
  private final String _regionName;

  /**
   * The name of the <code>DiskStore</code> to overflow this queue
   */
  private final String _diskStoreName;

  /**
   * The name of the directory in which to store overflowed queue entries
   * @deprecated as of prPersistSprint2
   */
  @Deprecated
  protected String _overflowDirectory;

  /**
   * The maximum amount of memory (MB) to allow in the queue before overflowing
   * entries to disk
   */
  protected int _maximumQueueMemory;

  /**
   * The maximum number of entries in a batch.
   */
  protected int _batchSize;

  /**
   * Whether conflation is enabled for this queue.
   */
  protected boolean _enableConflation;

  /**
   * Whether persistence is enabled for this queue.
   */
  protected boolean _enablePersistence;

  /**
   * Statistics for this queue. Null if no stats.
   */
  protected GatewayStats _stats;

  /**
   * The <code>Region</code> backing this queue
   */
  protected Region _region;

  /**
   * The <code>Map</code> mapping the regionName->key to the queue key. This
   * index allows fast updating of entries in the queue for conflation.
   */
  protected Map _indexes;

  /**
   * The <code>LogWriterI18n</code> used by this queue
   */
  protected LogWriterI18n _logger;

  /**
   * The maximum allowed key before the keys are rolled over
   */
  protected static final long MAXIMUM_KEY = Long.MAX_VALUE;

  /**
   * Whether the <code>Gateway</code> queue should be no-ack instead of ack.
   */
  private static final boolean NO_ACK =
    Boolean.getBoolean("gemfire.gateway-queue-no-ack");

  private static final String HEAD_KEY_57 = "HEAD_KEY";
  private static final String TAIL_KEY_57 = "TAIL_KEY";

  /**
   * Constructor. This constructor forces the queue to be initialized from the
   * region.
   *
   * @param cache
   *          The GemFire <code>Cache</code>
   * @param regionName
   *          The name of the region on which to create the queue. A region with
   *          this name will be retrieved if it exists or created if it does
   *          not.
   * @param attributes
   *          The <code>GatewayQueueAttributes</code> containing queue
   *          attributes like the name of the directory in which to store
   *          overflowed queue entries and the maximum amount of memory (MB) to
   *          allow
   * @param listener
   *          A <code>CacheListener</code> to for the region to use
   * @param stats
   *          a <code>GatewayStats</code> to record this queue's statistics in
   */
  public SingleWriteSingleReadRegionQueue(Cache cache, String regionName,
      GatewayQueueAttributes attributes, CacheListener listener,
      GatewayStats stats) {
    // The queue starts out with headKey and tailKey equal to -1 to force
    // them to be initialized from the region.
    this(cache, regionName, attributes, listener, stats, -1, -1);
  }

  public Region getRegion()
  {
    return this._region;
  }

  /**
   * Constructor. This constructor lets the caller initialize the head and tail
   * of the queue.
   *
   * @param cache
   *          The GemFire <code>Cache</code>
   * @param regionName
   *          The name of the region on which to create the queue. A region with
   *          this name will be retrieved if it exists or created if it does
   *          not.
   * @param attributes
   *          The <code>GatewayQueueAttributes</code> containing queue
   *          attributes like the name of the directory in which to store
   *          overflowed queue entries and the maximum amount of memory (MB) to
   *          allow in the queue before overflowing entries to disk
   * @param listener
   *          A <code>CacheListener</code> to for the region to use
   * @param stats
   *          a <code>GatewayStats</code> to record this queue's statistics in
   * @param headKey
   *          The key of the head entry in the <code>Region</code>
   * @param tailKey
   *          The key of the tail entry in the <code>Region</code>
   */
  public SingleWriteSingleReadRegionQueue(Cache cache, String regionName,
      GatewayQueueAttributes attributes, CacheListener listener,
      GatewayStats stats, long headKey, long tailKey) {
    this._logger = cache.getLoggerI18n();
    this._regionName = regionName;
    assert(attributes!=null);
    this._diskStoreName = attributes.getDiskStoreName();
    if (this._diskStoreName == null) {
      this._overflowDirectory = attributes.getOverflowDirectory();
    } else {
      this._overflowDirectory = null;
    }
    this._enableConflation = attributes.getBatchConflation();
    this._maximumQueueMemory = attributes.getMaximumQueueMemory();
    this._batchSize = attributes.getBatchSize();
    this._enablePersistence = attributes.getEnablePersistence();
    this._stats = stats;
    this._headKey = headKey;
    this._tailKey.set(tailKey);
    this._indexes = new HashMap();
    initializeRegion(cache, listener);
    if (this._logger.fineEnabled()) {
      this._logger.fine(this + ": Contains " + size() + " elements");
    }
  }
  
  public void destroy() {
    getRegion().localDestroyRegion();
  }

  public synchronized void put(Object object) throws CacheException {
    putAndGetKey(object);
  }

  protected long putAndGetKey(Object object) throws CacheException {
    // Get the tail key
    Long key = Long.valueOf(getTailKey());

    // Put the object into the region at that key
    this._region.put(key, object);

    // Increment the tail key
    //It is important that we increment the tail
    //key after putting in the region, this is the
    //signal that a new object is available.
    incrementTailKey();

    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Inserted " + key + "->" + object);
    }
    if (object instanceof Conflatable) {
      removeOldEntry((Conflatable)object, key);
    }
    return key.longValue();
  }

  public synchronized Object take() throws CacheException
  {
    resetLastPeeked();
    Object object = peekAhead();
    // If it is not null, destroy it and increment the head key
    if (object != null) {
      Long key = this._peekedIds.getLast();
      if (this._logger.finerEnabled()) {
        this._logger.finer(this + ": Retrieved " + key + "->" + object);
      }
      // Remove the entry at that key with a callback arg signifying it is
      // a WAN queue so that AbstractRegionEntry.destroy can get the value
      // even if it has been evicted to disk. In the normal case, the
      // AbstractRegionEntry.destroy only gets the value in the VM.
      this._region.destroy(key, WAN_QUEUE_TOKEN);
      
      updateHeadKey(key.longValue());
      
      if (this._logger.finerEnabled()) {
        this._logger.finer(this + ": Destroyed " + key + "->" + object);
      }
    }
    return object;
  }

  public List take(int batchSize) throws CacheException
  {
    List batch = new ArrayList(batchSize * 2);
    for (int i = 0; i < batchSize; i++) {
      Object obj = take();
      if (obj != null) {
        batch.add(obj);
      }
      else {
        break;
      }
    }
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Took a batch of " + batch.size()
          + " entries");
    }
    return batch;
  }

  /**
   * This method removes the last entry. However, it will
   * only let the user remove entries that they have peeked.
   * If the entry was not peeked, this method will silently
   * return.
   */
  public synchronized void remove() throws CacheException
  {
    if(this._peekedIds.isEmpty()) {
      return;
    }
    Long key = this._peekedIds.removeFirst();

    try {
      removeIndex(key);
      // Remove the entry at that key with a callback arg signifying it is
      // a WAN queue so that AbstractRegionEntry.destroy can get the value
      // even if it has been evicted to disk. In the normal case, the
      // AbstractRegionEntry.destroy only gets the value in the VM.
      this._region.destroy(key, WAN_QUEUE_TOKEN);
    } catch(EntryNotFoundException ok) {
      //this is acceptable because the conflation can remove entries
      //out from underneath us.
      if (this._logger.fineEnabled()) {
        this._logger.fine(this + ": Did not destroy entry at " + key
            + " it was not there. It should have been removed by conflation.");
      }
    }

    // Increment the head key
    updateHeadKey(key.longValue());

    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Destroyed entry at key " + key);
    }
  }

  /**
   * This method removes batchSize entries from the queue. It will
   * only remove entries that were previously peeked.
   * @param batchSize the number of entries to remove
   */
  public void remove(int batchSize) throws CacheException
  {
    for (int i = 0; i < batchSize; i++) {
      remove();
    }
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Removed a batch of " + batchSize
          + " entries");
    }
  }

  public Object peek() throws CacheException
  {
    resetLastPeeked();
    Object object = peekAhead();
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Peeked " + _peekedIds + "->" + object);
    }
    
    return object;
  }

  public List peek(int batchSize) throws CacheException
  {
    return peek(batchSize, -1);
  }

  public List peek(int batchSize, int timeToWait) throws CacheException {
    return peek(0, batchSize, timeToWait);
  }

  public List peek(int startIndex, int batchSize, int timeToWait)
      throws CacheException {
    long start = System.currentTimeMillis();
    long end = start + timeToWait;
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Peek start time=" + start + " end time="
          + end + " time to wait=" + timeToWait);
    }
    List batch = new ArrayList(batchSize * 2); // why *2?
    resetLastPeeked();
    while(batch.size() < batchSize) {
      Object object = peekAhead();
      // Conflate here
      if (object != null) {
        batch.add(object);
      }
      else {
        // If time to wait is -1 (don't wait) or time interval has elapsed
        long currentTime = System.currentTimeMillis();
        if (this._logger.finerEnabled()) {
          this._logger.finer(this + ": Peek current time: " + currentTime);
        }
        if (timeToWait == -1 || (end <= currentTime)) {
          if (this._logger.finerEnabled()) {
            this._logger.finer(this + ": Peek breaking");
          }
          break;
        }
        else {
          if (this._logger.finerEnabled()) {
            this._logger.finer(this + ": Peek continuing");
          }
          // Sleep a bit before trying again.
          try {
            Thread.sleep(getTimeToSleep(timeToWait));
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          continue;
        }
      }
    }
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Peeked a batch of " + batch.size()
          + " entries");
    }
    return batch;
  }

  private long getTimeToSleep(int timeToWait) {
    // Get the minimum of 50 and 5% of the time to wait (which by default is 1000 ms)
    long timeToSleep = Math.min(50l, ((long) (timeToWait*0.05)));
    
    // If it is 0, then try 50% of the time to wait
    if (timeToSleep == 0) {
      timeToSleep = (long) (timeToWait*0.50);
    }
    
    // If it is still 0, use the time to wait
    if (timeToSleep == 0) {
      timeToSleep = timeToWait;
    }
    
    return timeToSleep;
  }

  @Override
  public String toString()
  {
    return "queue " + this._regionName;
  }

  public int size()
  {
    int size = ((LocalRegion)this._region).entryCount();

    return size;
  }

  public void addCacheListener(CacheListener listener)
  {
    AttributesMutator mutator = this._region.getAttributesMutator();
    mutator.setCacheListener(listener);
  }

  public void removeCacheListener()
  {
    AttributesMutator mutator = this._region.getAttributesMutator();
    mutator.setCacheListener(null);
  }

  // No need to synchronize because it is called from a synchronized method
  protected boolean removeOldEntry(Conflatable object, Long tailKey)
      throws CacheException
  {
    boolean keepOldEntry = true;

    //_logger.warning("Checking conflation tail key: " + tailKey + " headKey "
    // + getHeadKey() + " batch size " + this._batchSize);
    // Determine whether conflation is enabled for this queue and object
    // Conflation is enabled iff:
    // - this queue has conflation enabled
    // - the object can be conflated
    if (this._enableConflation && object.shouldBeConflated()) {
      if (_logger.fineEnabled()) {
        _logger.fine(this + ": Conflating " + object + " at queue index="
            + tailKey + " queue size=" + size() + " head=" + this._headKey
            + " tail=" + tailKey);
      }

      // Determine whether this region / key combination is already indexed.
      // If so, it is already in the queue. Update the value in the queue and
      // set the shouldAddToQueue flag accordingly.
      String regionName = object.getRegionToConflate();
      Object key = object.getKeyToConflate();

      Map latestIndexesForRegion = (Map)this._indexes.get(regionName);
      if (latestIndexesForRegion == null) {
        latestIndexesForRegion = new HashMap();
        this._indexes.put(regionName, latestIndexesForRegion);
      }
      
      Long previousIndex = (Long) latestIndexesForRegion.put(key, tailKey);
      this._stats.incConflationIndexesMapSize();
      if (_logger.fineEnabled()) {
        _logger.fine(this + ": Adding index key=" + key + "->index="
            + tailKey + " for " + object + " head=" + this._headKey
            + " tail=" + tailKey);
      }

      // Test if the key is contained in the latest indexes map. If the key is
      // not contained in the latest indexes map, then it should be added to
      // the queue. 
      //
      // It no longer matters if we remove an entry that is going out in the current
      // batch, because we already put the latest value on the tail of the queue, and
      // peekedIds list prevents us from removing an entry that was not peeked.
      if (previousIndex != null) {
        if (_logger.fineEnabled()) {
          _logger
          .fine(this
              + ": Indexes contains index="
              + previousIndex
              + " for key="
              + key
              + " head="
              + this._headKey
              + " tail="
              + tailKey
              + " and it can be used.");
        }
        keepOldEntry = false;
      }
      else {
        if (_logger.fineEnabled()) {
          _logger
          .fine(this
              + ": No old entry for key="
              + key
              + " head="
              + this._headKey
              + " tail="
              + tailKey
              + " not removing old entry.");
        }
        keepOldEntry = true;
      }

      // Replace the object's value into the queue if necessary
      if (!keepOldEntry) {
        Conflatable previous = (Conflatable)this._region.remove(previousIndex);
        if (_logger.fineEnabled()) {
          _logger
              .fine(this + ": Previous conflatable at key=" + previousIndex
                  + " head=" + this._headKey + " tail=" + tailKey + ": "
                  + previous);
          _logger.fine(this + ": Current conflatable at key=" + tailKey
              + " head=" + this._headKey + " tail=" + tailKey + ": " + object);
          if(previous != null) {
            _logger.fine(this + ": Removed "
                + deserialize(previous.getValueToConflate()) + " and added "
                + deserialize(object.getValueToConflate()) + " for key=" + key
                + " head=" + this._headKey + " tail=" + tailKey
                + " in queue for region=" + regionName + " old event " + previous);
          }
        }
      }
    }
    else {
      if (_logger.fineEnabled()) {
        _logger.fine(this + ": Not conflating " + object + " queue size: "
            + size() + " head=" + this._headKey + " tail=" + tailKey);
      }
    }
    return keepOldEntry;
  }

  /**
   * Does a get that attempts to not fault values in from disk
   * or make the entry the most recent in the LRU.
   */
  private Object optimalGet(Long k) {
    // Get the object at that key (to remove the index).
    LocalRegion lr = (LocalRegion)this._region;
    Object o = null;
    try {
      o = lr.getValueInVM(k); // OFFHEAP deserialize
      if (o == null) {
        // must be on disk
        // fault it in w/o putting it back in the region
        o = lr.getValueOnDiskOrBuffer(k);
        if (o == null) {
          // try memory one more time in case it was already faulted back in
          o = lr.getValueInVM(k); // OFFHEAP deserialize
          if (o == null) {
            // if we get this far give up and just do a get
            o = lr.get(k);
          } else {
            if (o instanceof CachedDeserializable) {
              o = ((CachedDeserializable)o).getDeserializedValue(lr, lr.getRegionEntry(k));
            }
          }
        }
      } else {
        if (o instanceof CachedDeserializable) {
          o = ((CachedDeserializable)o).getDeserializedValue(lr, lr.getRegionEntry(k));
        }
      }
    } catch(EntryNotFoundException ok) {
      // just return null;
    }
    // bug #46023 do not return a destroyed entry marker
    if (o == Token.TOMBSTONE) {
      o = null;
    }
    return o;
  }

  // No need to synchronize because it is called from a synchronized method
  private void removeIndex(Long qkey) {
    // Determine whether conflation is enabled for this queue and object
    if (this._enableConflation) {
      // only call get after checking enableConflation for bug 40508
      Object o = optimalGet(qkey);
      if (o instanceof Conflatable) {
        Conflatable object = (Conflatable)o;
        if (object.shouldBeConflated()) {
          // Otherwise, remove the index from the indexes map.
          String regionName = object.getRegionToConflate();
          Object key = object.getKeyToConflate();

          Map latestIndexesForRegion = (Map)this._indexes.get(regionName);
          if (latestIndexesForRegion != null) {
            // Remove the index.
            Long index = (Long)latestIndexesForRegion.remove(key);
	    this._stats.decConflationIndexesMapSize();
            if (_logger.fineEnabled()) {
              if (index != null) {
                _logger.fine(this + ": Removed index " + index + " for " + object);
              }
            }
          }
        }
      }
    }
  }

  protected Object deserialize(Object serializedBytes)
  {
    Object deserializedObject = serializedBytes;
    if (serializedBytes instanceof byte[]) {
      byte[] serializedBytesCast = (byte[])serializedBytes;
      // This is a debugging method so ignore all exceptions like
      // ClassNotFoundException
      try {
        deserializedObject = EntryEventImpl.deserialize(serializedBytesCast);
      }
      catch (Exception e) {
      }
    }
    return deserializedObject;
  }
  
  /**
   * returns true if key a is before key b. This 
   * test handles keys that have wrapped around
   * @param a
   * @param b
   */
  private boolean before(long a, long b) {
    // a is before b if a < b or a>b and a MAXIMUM_KEY/2 larger than b
    // (indicating we have wrapped)
  	return a < b ^ a - b > (MAXIMUM_KEY / 2);
  }
  
  /**
   * returns true if key a is before key b. This 
   * test handles keys that have wrapped around
   * @param a
   * @param b
   */
  private boolean beforeOrEquals(long a, long b) {
  	return a <= b && b - a < (MAXIMUM_KEY / 2);
  }
  
  private long inc(long value) {
  	value++;
  	value = value == MAXIMUM_KEY ? 0 : value;
  	return value;
  }
  
  /**
   * Clear the list of peeked keys. The next peek will start
   * again at the head key.
   *
   */
  protected void resetLastPeeked() {
    this._peekedIds.clear();
  }

  /**
   * Finds the next object after the last key peeked
   *
   * @throws CacheException
   */
  protected Object peekAhead() throws CacheException
  {
    Object object = null;
    long currentKey = this._peekedIds.isEmpty() ? getHeadKey()
        : (this._peekedIds.getLast().longValue() + 1);
    
    //It's important here that we check where the current key
    //is in relation to the tail key before we check to see if the
    //object exists. The reason is that the tail key is basically
    //the synchronization between this thread and the putter thread.
    //The tail key will not be incremented until the object is put in the region
    //If we check for the object, and then check the tail key, we could
    //skip objects.
    // @todo don't do a get which updates the lru, instead just get the value
    // w/o modifying the LRU.
    // Note: getting the serialized form here (if it has overflowed to disk)
    // does not save anything since GatewayBatchOp needs to GatewayEventImpl
    // in object form.
    while(before(currentKey, getTailKey())
          // use optimalGet here to fix bug 40654
          && (object = optimalGet(Long.valueOf(currentKey))) == null ) {
    	if (this._logger.finerEnabled()) {
    		this._logger.finer(this + ": Trying head key + offset: "
    				+ currentKey);
    	}
    	currentKey= inc(currentKey);
        if (this._stats != null) {
          this._stats.incEventsNotQueuedConflated();
        }
    }

    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Peeked " + currentKey + "->" + object);
    }
    if(object != null) {
    	this._peekedIds.addLast(Long.valueOf(currentKey));
    }
    return object;
  }

  // !!ezoerner:20090325
  // Added this method for benefit of subclass SqlDDLRegionQueue,
  // to get the key as well as the value
  protected Object[] peekAheadGetKeyAndValue() throws CacheException {
    Object object = null;
    long currentKey = this._peekedIds.isEmpty() ? getHeadKey()
        : (this._peekedIds.getLast().longValue() + 1);

    // It's important here that we check where the current key
    // is in relation to the tail key before we check to see if the
    // object exists. The reason is that the tail key is basically
    // the synchronization between this thread and the putter thread.
    // The tail key will not be incremented until the object is put in the
    // region. If we check for the object, and then check the tail key,
    // we could skip objects.
    while (before(currentKey, getTailKey())
        && (object = this._region.get(Long.valueOf(currentKey))) == null) {
      if (this._logger.finerEnabled()) {
        this._logger.finer(this + ": Trying head key + offset: " + currentKey);
      }
      currentKey = inc(currentKey);
      if (this._stats != null) {
        this._stats.incEventsNotQueuedConflated();
      }
    }

    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Peeked " + currentKey + "->" + object);
    }
    if (object != null) {
      this._peekedIds.addLast(Long.valueOf(currentKey));
    }
    return object != null ? new Object[] { currentKey, object } : null;
  }

  /**
   * Returns the value of the tail key. The tail key points to an empty where
   * the next queue entry will be stored.
   *
   * @return the value of the tail key
   * @throws CacheException
   */
  protected long getTailKey() throws CacheException
  {
    long tailKey;
    // Test whether _tailKey = -1. If so, the queue has just been created.
    // Go into the region to get the value of TAIL_KEY. If it is null, then
    // this is the first attempt to access this queue. Set the _tailKey and
    // tailKey appropriately (to 0). If there is a value in the region, then
    // this queue has been accessed before and this instance is taking up where
    // a previous one left off. Set the _tailKey to the value in the region.
    // From now on, this queue will use the value of _tailKey in the VM to
    // determine the tailKey. If the _tailKey != -1, set the tailKey
    // to the value of the _tailKey.
    initializeKeys();
    
    tailKey = this._tailKey.get();
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Determined tail key: " + tailKey);
    }
    return tailKey;
  }

  /**
   * Increments the value of the tail key by one.
   *
   * @throws CacheException
   */
  protected void incrementTailKey() throws CacheException
  {
    this._tailKey.set(inc(this._tailKey.get()));
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Incremented TAIL_KEY for region "
          + this._region.getName() + " to " + this._tailKey);
    }
  }

  /**
   * If the keys are not yet initialized, initialize them from the region .
   *
   * TODO - We could initialize the _indexes maps at the time time
   * here. However, that would require iterating over the values of the
   * region rather than the keys, which could be much more expensive
   * if the region has overflowed to disk.
   * 
   * We do iterate over the values of the region in GatewayImpl at the time
   * of failover. see GatewayImpl.handleFailover. So there's a possibility we 
   * can consolidate that code with this method and iterate over the 
   * region once.
   * 
   * @throws CacheException
   */
  protected void initializeKeys() throws CacheException
  {
    if(_tailKey.get() != -1) {
      return;
    }
    synchronized(this) {
      long largestKey = -1;
      long largestKeyLessThanHalfMax = -1;
      long smallestKey = -1;
      long smallestKeyGreaterThanHalfMax = -1;
      remove57Keys();

      Set keySet = this._region.keySet();
      for(Iterator itr = keySet.iterator(); itr.hasNext(); ) {
        long key = ((Long) itr.next()).longValue();
        if(key > largestKey) {
          largestKey = key;
        }
        if(key > largestKeyLessThanHalfMax && key < MAXIMUM_KEY/2) {
          largestKeyLessThanHalfMax = key; 
        }

        if(key < smallestKey || smallestKey == -1) {
          smallestKey = key;
        }
        if((key < smallestKeyGreaterThanHalfMax || smallestKeyGreaterThanHalfMax == -1) && key > MAXIMUM_KEY/2) {
          smallestKeyGreaterThanHalfMax= key; 
        }
      }

      //Test to see if the current set of keys has keys that are
      //both before and after we wrapped around the MAXIMUM_KEY
      //If we do have keys that wrapped, the
      //head key should be something close to MAXIMUM_KEY
      //and the tail key should be something close to 0.
      //Here, I'm guessing that the head key should be greater than MAXIMUM_KEY/2
      // and the head key - tail key > MAXIMUM/2.
      if (smallestKeyGreaterThanHalfMax != -1 && largestKeyLessThanHalfMax != -1
          && (smallestKeyGreaterThanHalfMax - largestKeyLessThanHalfMax) > MAXIMUM_KEY / 2) {
        this._headKey = smallestKeyGreaterThanHalfMax;
        this._tailKey.set(inc(largestKeyLessThanHalfMax));
        if(this._logger.infoEnabled()) {
          this._logger.info(LocalizedStrings.SingleWriteSingleReadRegionQueue_0_DURING_FAILOVER_DETECTED_THAT_KEYS_HAVE_WRAPPED, new Object[] {this
              , this._tailKey, Long.valueOf(this._headKey)});
        }
      } else {
        this._headKey = smallestKey == -1 ? 0 : smallestKey;
        this._tailKey.set(inc(largestKey)); 
      }

      if (this._logger.fineEnabled()) {
        this._logger.fine(this + " Initialized tail key to: " + 
                          this._tailKey + ", head key to: " + this._headKey);
      }
    }
  }


  /**
   * For backwards compatability, we will check for and remove the HEAD_KEY and TAIL_KEY entries
   * that 57 used to keep track of the position in the queue.
   */
  private void remove57Keys() {
    boolean was57File = false;;
    if(this._region.containsKey(HEAD_KEY_57)) {
      this._region.destroy(HEAD_KEY_57);
      was57File = true;
    }
    if(this._region.containsKey(TAIL_KEY_57)) {
      this._region.destroy(TAIL_KEY_57);
      was57File = true;
    }
    if(was57File) {
      this._logger.info(LocalizedStrings.SingleWriteSingleReadRegionQueue_57_QUEUE_UPGRADED, this._regionName);
    }
  }

  /**
   * Returns the value of the head key. The head key points to the next entry to
   * be removed from the queue.
   *
   * @return the value of the head key
   * @throws CacheException
   */
  protected long getHeadKey() throws CacheException
  {
    long headKey;
    // Test whether _headKey = -1. If so, the queue has just been created.
    // Go into the region to get the value of HEAD_KEY. If it is null, then
    // this is the first attempt to access this queue. Set the _headKey and
    // headKey appropriately (to 0). If there is a value in the region, then
    // this queue has been accessed before and this instance is taking up where
    // a previous one left off. Set the _headKey to the value in the region.
    // From now on, this queue will use the value of _headKey in the VM to
    // determine the headKey. If the _headKey != -1, set the headKey
    // to the value of the _headKey.
    initializeKeys();
    headKey = this._headKey;
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Determined head key: " + headKey);
    }
    return headKey;
  }

  /**
   * Increments the value of the head key by one.
   *
   * @throws CacheException
   */
  protected void updateHeadKey(long destroyedKey) throws CacheException
  {
    this._headKey = inc(destroyedKey);
    if (this._logger.finerEnabled()) {
      this._logger.finer(this + ": Incremented HEAD_KEY for region "
          + this._region.getName() + " to " + this._headKey);
    }
  }

  protected boolean isPersistent() {
    return this._enablePersistence;
  }
  
  /**
   * Initializes the <code>Region</code> backing this queue. The
   * <code>Region</code>'s scope is DISTRIBUTED_NO_ACK and mirror type is
   * KEYS_VALUES and is set to overflow to disk based on the
   * <code>GatewayQueueAttributes</code>.
   *
   * @param cache
   *          The GemFire <code>Cache</code>
   * @param listener
   *          The GemFire <code>CacheListener</code>. The
   *          <code>CacheListener</code> can be null.
   * @see com.gemstone.gemfire.cache.util.GatewayQueueAttributes
   */
  protected void initializeRegion(Cache cache, CacheListener listener)
  {
    final GemFireCacheImpl gemCache = (GemFireCacheImpl)cache;
    this._region = cache.getRegion(this._regionName);
    if (this._region == null) {
    AttributesFactory factory = new AttributesFactory();

    // Set scope
    factory.setScope(NO_ACK ? Scope.DISTRIBUTED_NO_ACK: Scope.DISTRIBUTED_ACK);

    // Set persistence & replication
    if (this._enablePersistence) {
      factory.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
    }
    else {
      factory.setDataPolicy(DataPolicy.REPLICATE);
    }

    // Set listener if it is not null. The listener will be non-null
    // when the user of this queue is a secondary VM.
    if (listener != null) {
      factory.setCacheListener(listener);
    }
    // allow for no overflow directory
    // Set capacity controller to overflow to disk every
    // maximumMemoryInQueue MB using the default sizer (Sizeof)
    // to compute the size
    EvictionAttributes ea = EvictionAttributes.createLRUMemoryAttributes(
        this._maximumQueueMemory, null /* sizer */,
        EvictionAction.OVERFLOW_TO_DISK);
    factory.setEvictionAttributes(ea);

    if (this._overflowDirectory != null && !this._overflowDirectory.equals(GatewayQueueAttributes.DEFAULT_OVERFLOW_DIRECTORY)) {
      // Set disk write attributes for backward compatibility
      // we might follow BSI's getAttribFactoryForClientMessagesRegion()
      // to create a diskstore here
      /*
       * @todo: barry/greg: You might need to set time-interval and
       *        byte-threshold for best performance with oplogs. They default
       *        to 1 sec and 0 bytes.
       */
      DiskWriteAttributesFactory dwaf = new DiskWriteAttributesFactory();
      dwaf.setSynchronous(true); // fix for bug 37458 and 37509
      dwaf.setMaxOplogSizeInBytes(GatewayImpl.QUEUE_OPLOG_SIZE);
      dwaf.setRollOplogs(false); // fix for bug 40493
      DiskWriteAttributes dwa = dwaf.create();
      factory.setDiskWriteAttributes(dwa);

      // Set overflow disk directories
      File[] diskDirs = new File[1];
      diskDirs[0] = new File(this._overflowDirectory);
      if (!diskDirs[0].mkdirs() && !diskDirs[0].isDirectory()) {
        throw new DiskAccessException("Could not create directory "
            + diskDirs[0].getAbsolutePath(), this._region);
      }
      // fix for bug 40731
      factory.setDiskDirsAndSizes(diskDirs, new int[]{Integer.MAX_VALUE});
    }
    else {
      factory.setDiskStoreName(this._diskStoreName);
      // if persistence, we write to disk sync, if overflow, then use async
      // see feature request #41479
      factory.setDiskSynchronous(true/*this._enablePersistence*/);
    }
    // Create the region
    if (this._logger.fineEnabled()) {
      this._logger.fine(this + ": Attempting to create queue region: "
          + this._regionName);
    }
    final RegionAttributes ra = factory.createRegionAttributes();
      try {
        SingleReadWriteMetaRegion meta = new SingleReadWriteMetaRegion(
            this._regionName, ra, null, gemCache);
        try {
          this._region = gemCache.createVMRegion(this._regionName, ra,
              new InternalRegionArguments().setInternalMetaRegion(meta)
                  .setDestroyLockFlag(true).setSnapshotInputStream(null)
                  .setImageTarget(null));
          ((LocalRegion)this._region).destroyFilterProfile();
        }
        catch (IOException veryUnLikely) {
          this._logger.severe(
              LocalizedStrings.SingleWriteSingleReadRegionQueue_UNEXPECTED_EXCEPTION_DURING_INIT_OF_0,
              this.getClass(), veryUnLikely);
        }
        catch (ClassNotFoundException alsoUnlikely) {
          this._logger.severe(
              LocalizedStrings.SingleWriteSingleReadRegionQueue_UNEXPECTED_EXCEPTION_DURING_INIT_OF_0,
              this.getClass(), alsoUnlikely);
        }
        if (this._logger.fineEnabled()) {
          this._logger.fine(this + ": Created queue region: " + this._region);
        }
      }
      catch (CacheException e) {
        this._logger.severe(
            LocalizedStrings.SingleWriteSingleReadRegionQueue_0_THE_QUEUE_REGION_NAMED_1_COULD_NOT_BE_CREATED,
            new Object[] {this, this._regionName}, e);
      }
    }
    else {
      if (this._logger.fineEnabled()) {
        this._logger.fine(this + ": Retrieved queue region: " + this._region);
      }
    }
  }
  

  public void destroyPersistentFiles(Cache cache) {
    DiskStoreImpl diskStore = (DiskStoreImpl) cache.findDiskStore(this._diskStoreName);
    String region = "/" + this._regionName;
    if(diskStore.getDiskInitFile().getDiskRegionByName(region) != null) {
      diskStore.destroyRegion(region, true);
    }
  }
  
  @Override
  public void close() {
    Region r = getRegion();
    if (r != null && !r.isDestroyed()) {
      try {
        r.close();
      } catch (RegionDestroyedException e) {
      }
    }
  }


  /**
   * A secret meta region used whose contents
   *
   * @author Mitch Thomas
   * @since 4.3
   */
  static private class SingleReadWriteMetaRegion extends DistributedRegion
   {
    protected class Stopper extends CancelCriterion {
      @Override
      public String cancelInProgress() {
        GemFireCacheImpl gfc = SingleReadWriteMetaRegion.this.getCache();
        assert gfc!=null;

        if (gfc.closingGatewayHubsByShutdownAll) {
          return "Gateway hubs are being closed by shutdownall."; // this + ": closed";
        }
        return gfc.getCancelCriterion().cancelInProgress();
      }

      /* (non-Javadoc)
       * @see com.gemstone.gemfire.CancelCriterion#generateCancelledException(java.lang.Throwable)
       */
      @Override
      public RuntimeException generateCancelledException(Throwable e) {
        String result = cancelInProgress();
        return new GatewayCancelledException(result, e);
      }
    }

    @Override
    protected CancelCriterion createStopper() {
      return new Stopper();
    }

    protected SingleReadWriteMetaRegion(String regionName,
        RegionAttributes attrs, LocalRegion parentRegion, GemFireCacheImpl cache) {
      super(regionName, attrs, parentRegion, cache,
          new InternalRegionArguments().setDestroyLockFlag(true)
              .setRecreateFlag(false).setSnapshotInputStream(null)
              .setImageTarget(null));
    }

    @Override
    protected boolean isCopyOnRead() {
      return false;
    }

    // Prevent this region from participating in a TX, bug 38709 
    @Override
    final public boolean isSecret() {
      return true;
    }

    @Override
    public boolean deferRecovery() {
      return true;
    }

    //@override event tracker not needed for this type of region
    @Override
    public void createEventTracker() {
    }

    @Override
    final protected boolean shouldNotifyBridgeClients()
    {
      return false;
    }

    @Override
    final public boolean generateEventID()
    {
      return false;
    }
  }


  @Override
  public long estimateMemoryFootprint(SingleObjectSizer sizer) {
    return sizer.sizeof(this) + sizer.sizeof(_peekedIds);
  }

  @Override
  public void release() {
    
  }
}
