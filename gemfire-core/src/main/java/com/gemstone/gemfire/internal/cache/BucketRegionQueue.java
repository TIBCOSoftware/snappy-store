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
/**
 * 
 */
package com.gemstone.gemfire.internal.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.hdfs.internal.AbstractBucketRegionQueue;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.persistence.query.mock.ByteComparator;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySenderEventProcessor;
import com.gemstone.gemfire.internal.cache.wan.parallel.BucketRegionQueueUnavailableException;
import com.gemstone.gemfire.internal.cache.wan.parallel.ConcurrentParallelGatewaySenderQueue;

/**
 * @author Suranjan Kumar
 * 
 */
public class BucketRegionQueue extends AbstractBucketRegionQueue {

  /**
   * The <code>Map</code> mapping the regionName->key to the queue key. This
   * index allows fast updating of entries in the queue for conflation. This is
   * necesaary for Colocated regions and if any of the regions use same key for
   * data.
   */
  private final Map indexes;

  /**
   * A transient queue to maintain the eventSeqNum of the events that are to be
   * sent to remote site. It is cleared when the queue is cleared.
   */
  private final BlockingQueue<Object> eventSeqNumQueue = new LinkedBlockingQueue<Object>();
  
  //private final BlockingQueue<EventID> eventSeqNumQueueWithEventId = new LinkedBlockingQueue<EventID>();

  private long lastKeyRecovered;

  /**
   * @param regionName 
   * @param attrs
   * @param parentRegion
   * @param cache
   * @param internalRegionArgs
   */
  public BucketRegionQueue(String regionName, RegionAttributes attrs,
      LocalRegion parentRegion, GemFireCacheImpl cache,
      InternalRegionArguments internalRegionArgs) {
    super(regionName, attrs, parentRegion, cache, internalRegionArgs);
    this.keySet();
    indexes = new ConcurrentHashMap<Object, Long>();    
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * com.gemstone.gemfire.internal.cache.BucketRegion#initialize(java.io.InputStream
   * ,
   * com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember
   * , com.gemstone.gemfire.internal.cache.InternalRegionArguments)
   */
  @Override
  protected void initialize(InputStream snapshotInputStream,
      InternalDistributedMember imageTarget,
      InternalRegionArguments internalRegionArgs) throws TimeoutException,
      IOException, ClassNotFoundException {

    super.initialize(snapshotInputStream, imageTarget, internalRegionArgs);

    //take initialization writeLock inside the method after synchronizing on tempQueue
    loadEventsFromTempQueue();
    
    getInitializationLock().writeLock().lock();
    try {
      if (!this.keySet().isEmpty()) {
        if (getPartitionedRegion().getColocatedWith() == null) {
          List<EventID> keys = new ArrayList<EventID>(this.keySet());
          Collections.sort(keys, new Comparator<EventID>() {
            @Override
            public int compare(EventID o1, EventID o2) {
              int compareMem = new ByteComparator().compare(
                  o1.getMembershipID(), o2.getMembershipID());
              if (compareMem == 1) {
                return 1;
              } else if (compareMem == -1) {
                return -1;
              } else {
                if (o1.getThreadID() > o2.getThreadID()) {
                  return 1;
                } else if (o1.getThreadID() < o2.getThreadID()) {
                  return -1;
                } else {
                  return o1.getSequenceID() < o2.getSequenceID() ? -1 : o1
                      .getSequenceID() == o2.getSequenceID() ? 0 : 1;
                }
              }
            }
          });
          for (EventID eventID : keys) {
            eventSeqNumQueue.add(eventID);
          }
        } else {
          TreeSet<Long> sortedKeys = new TreeSet<Long>(this.keySet());
          //although the empty check for this.keySet() is done above, 
          //do the same for sortedKeys as well because the keySet() might have become 
          //empty since the above check was made (keys might have been destroyed through BatchRemoval)
          //fix for #49679 NoSuchElementException thrown from BucketRegionQueue.initialize
          if (!sortedKeys.isEmpty()) {
            for (Long key : sortedKeys) {
              eventSeqNumQueue.add(key);
            }
            lastKeyRecovered = sortedKeys.last();
            final AtomicLong eventSeqNum = getEventSeqNum();
            if (eventSeqNum != null) {
              setIfGreater(eventSeqNum, lastKeyRecovered);
            }
          }
        }

        if (getLogWriterI18n().fineEnabled()) {
          getLogWriterI18n().fine(
              "For bucket " + getId() + " ,total keys recovered are : "
                  + eventSeqNumQueue.size() + " last key recovered is : "
                  + lastKeyRecovered + " and the seqNo is " + getEventSeqNum());
        }
      }
      this.initialized = true;
    }
    finally {
      notifyEventProcessor();
      getInitializationLock().writeLock().unlock();
    }
  }

  
  @Override
  public void beforeAcquiringPrimaryState() {
    int batchSize = this.getPartitionedRegion().getParallelGatewaySender()
        .getBatchSize();
    Iterator<Object> itr = eventSeqNumQueue.iterator();
    markEventsAsDuplicate(batchSize, itr);
  }
  
  protected void clearQueues(){
    getInitializationLock().writeLock().lock();
    try {
      this.indexes.clear();
      this.eventSeqNumQueue.clear();
    }
    finally {
      getInitializationLock().writeLock().unlock();
    }
  }
  
  @Override
  protected boolean virtualPut(EntryEventImpl event, boolean ifNew,
      boolean ifOld, Object expectedOldValue, boolean requireOldValue,
      long lastModified, boolean overwriteDestroyed) throws TimeoutException,
      CacheWriterException {
    boolean success = super.virtualPut(event, ifNew, ifOld, expectedOldValue,
        requireOldValue, lastModified, overwriteDestroyed);
    if (success) {
      if (getPartitionedRegion().getColocatedWith() == null) {
        return success;
      }

      if (getPartitionedRegion().isConflationEnabled() && this.getBucketAdvisor().isPrimary()) {
        Object object = event.getNewValue();
        Long key = (Long)event.getKey();
        if (object instanceof Conflatable) {
          if (logger.fineEnabled()) {
            logger.fine("Key :" + key + " , Object : " + object
                + " is conflatable");
          }
          // TODO: TO optimize by destroying on primary and secondary separately
          // in case of conflation
          conflateOldEntry((Conflatable)object, key);
        } else {
          if (logger.fineEnabled()) {
            logger.fine("Object : " + object + " is not conflatable");
          }
        }
      }
    }
    return success;
  }

  private void conflateOldEntry(Conflatable object, Long tailKey) {
    PartitionedRegion region = this.getPartitionedRegion();
    Conflatable conflatableObject = object;
    Object keyToConflate = conflatableObject.getKeyToConflate();
    String rName = object.getRegionToConflate();
    if (logger.fineEnabled()) {
      logger.fine(" The region name is : " + rName);
    }
    Map latestIndexesForRegion = (Map)this.indexes.get(rName);
    if (latestIndexesForRegion == null) {
      latestIndexesForRegion = new ConcurrentHashMap();
      this.indexes.put(rName, latestIndexesForRegion);
    }
    Long previousTailKey = (Long)latestIndexesForRegion.put(keyToConflate,
            tailKey);
    region.getParallelGatewaySender().getStatistics()
    .incConflationIndexesMapSize();
    if (logger.fineEnabled()) {
        logger.fine(" The key that was put in index map is : " + keyToConflate + " and tailkey is " + tailKey);
    }
    if (region.isConflationEnabled() && conflatableObject.shouldBeConflated()) {
      if (previousTailKey != null) {
        if (logger.fineEnabled()) {
          logger.fine(this + ": Conflating " + object + " at queue index= "
              + tailKey + " and previousTailKey: " + previousTailKey);
        }
        AbstractGatewaySenderEventProcessor ep = region.getParallelGatewaySender().getEventProcessor();
        if (ep == null) return;
        ConcurrentParallelGatewaySenderQueue queue = (ConcurrentParallelGatewaySenderQueue)ep.getQueue();
        // Give the actual conflation work to another thread.
        // ParallelGatewaySenderQueue takes care of maintaining a thread pool.
        queue.conflateEvent(conflatableObject, getId(), previousTailKey);
      }
    } else {
      if (logger.fineEnabled()) {
        logger.fine(this + ": Not conflating " + object);
      }
    }
  }

  // No need to synchronize because it is called from a synchronized method
  private void removeIndex(Long qkey) {
    // Determine whether conflation is enabled for this queue and object
    Object o = getNoLRU(qkey, true, false, false);
    if (o instanceof Conflatable) {
      Conflatable object = (Conflatable)o;
      if (object.shouldBeConflated()) {
        // Otherwise, remove the index from the indexes map.
        String rName = object.getRegionToConflate();
        Object key = object.getKeyToConflate();

        Map latestIndexesForRegion = (Map)this.indexes.get(rName);
        if (latestIndexesForRegion != null) {
          // Remove the index.
          Long index = (Long)latestIndexesForRegion.remove(key);
          if (index != null) {
            this.getPartitionedRegion().getParallelGatewaySender()
                .getStatistics().decConflationIndexesMapSize();
            if (logger.fineEnabled()) {
              logger.fine(this + ": Removed index " + index + " for " + object);
            }
          }
        }
      }
    }
  }

  @Override
  protected void basicDestroy(final EntryEventImpl event,
      final boolean cacheWrite, Object expectedOldValue)
      throws EntryNotFoundException, CacheWriterException, TimeoutException {
    super.basicDestroy(event, cacheWrite, expectedOldValue);
    if (getPartitionedRegion().isConflationEnabled()) {
        removeIndex((Long)event.getKey());
    }
    // Primary buckets should already remove the key while peeking
    if (!this.getBucketAdvisor().isPrimary()) {
      this.eventSeqNumQueue.remove(event.getKey());
    }
  }

  public Object peek() {                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                
    Object key = null;
    Object object = null;
    //doing peek in initializationLock because during region destroy, the clearQueues 
    //clears the eventSeqNumQueue and can cause data inconsistency (defect #48984) 
    getInitializationLock().readLock().lock();
    try {
      if (this.getPartitionedRegion().isDestroyed()) {
        throw new BucketRegionQueueUnavailableException();
      }
      key = this.eventSeqNumQueue.peek();
      if (key != null) {
        object = getNoLRU(key, true, false, false);
        // In case of conflation and a race where bucket recovers
        // key-value from other bucket while put has come to this bucket.
        // if (object != null) {
        // ParallelGatewaySenderQueue queue =
        // (ParallelGatewaySenderQueue)getPartitionedRegion()
        // .getParallelGatewaySender().getQueues().toArray(new
        // RegionQueue[1])[0];
        // //queue.addToPeekedKeys(key);
        // }
        this.eventSeqNumQueue.remove(key);
      }
      return object; // OFFHEAP: ok since callers are careful to do destroys on
                     // region queue after finished with peeked object.
    }
    finally {
      getInitializationLock().readLock().unlock();
    }
  }
  
  protected void addToEventQueue(Object key, boolean didPut, EntryEventImpl event, int sizeOfHDFSEvent) {
    if (didPut) {
      if (this.initialized) {
        this.eventSeqNumQueue.add(key);
      }
      if (logger.fineEnabled()) {
        logger.fine("Put successfully in the queue : " + event.getRawNewValue()
            + " was initialized: " + this.initialized);
      }
    }
    if (this.getBucketAdvisor().isPrimary()) {
      incQueueSize(1);
    }
  }

  /**
   * It removes the first key from the queue.
   * 
   * @return Returns the key for which value was destroyed.
   * @throws ForceReattemptException
   */
  public Object remove() throws ForceReattemptException {
    Object key = this.eventSeqNumQueue.remove();  
    if (key != null) {
      destroyKey(key);
    }
    return key;
  }

  /**
   * It removes the first key from the queue.
   * 
   * @return Returns the value.
   * @throws InterruptedException
   * @throws ForceReattemptException
   */
  public Object take() throws InterruptedException, ForceReattemptException {
    throw new UnsupportedOperationException();
    // Currently has no callers.
    // To support this callers need to call freeOffHeapResources on the returned GatewaySenderEventImpl.
//     Object key = this.eventSeqNumQueue.remove();
//     Object object = null;
//     if (key != null) {
//       object = PartitionRegionHelper
//           .getLocalPrimaryData(getPartitionedRegion()).get(key);
//       /**
//        * TODO: For the time being this is same as peek. To do a batch peek we
//        * need to remove the head key. We will destroy the key once the event is
//        * delivered to the GatewayReceiver.
//        */
//       destroyKey(key);
//     }
//     return object; // TODO OFFHEAP: see what callers do with the returned GatewaySenderEventImpl. We need to inc its refcount before we do the destroyKey.
  }

  public boolean isReadyForPeek() {
    return !this.getPartitionedRegion().isDestroyed() && !this.isEmpty() && !this.eventSeqNumQueue.isEmpty()
        && getBucketAdvisor().isPrimary();
  }



}
