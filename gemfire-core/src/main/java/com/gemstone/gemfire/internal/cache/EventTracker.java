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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.PureLogWriter;
import com.gemstone.gemfire.internal.SystemTimer.SystemTimerTask;
import com.gemstone.gemfire.internal.cache.ha.ThreadIdentifier;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCountDownLatch;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * EventTracker tracks the last sequence number for a particular
 * memberID:threadID.  It is used to avoid replaying events in
 * client/server and partitioned-region configurations.
 * 
 * @author bruce
 * @since 6.0
 */
public class EventTracker
{
  public static final boolean VERBOSE = Boolean.getBoolean("gemfire.EventTracker.VERBOSE");
  /**
   * a mapping of originator to the last event applied to this cache 
   *
   * Keys are instances of {@link ThreadIdentifier}, values are instances
   * of {@link EventSeqnoHolder}.
   */
  protected final ConcurrentMap<ThreadIdentifier, EventSeqnoHolder> recordedEvents 
      = new ConcurrentHashMap<ThreadIdentifier, EventSeqnoHolder>(100);
 
  /** a mapping of originator to putAllOperation's last status (true means
   * finished processing) applied to this cache. 
   *
   * Keys are instances of @link {@link ThreadIdentifier}, values are instances
   * of {@link PutAllOpProcessed}.
   */
  private final ConcurrentMap<ThreadIdentifier, PutAllOpProcessed> recordedPutAllOps 
      = new ConcurrentHashMap<ThreadIdentifier, PutAllOpProcessed>(100);
  
  /** a mapping of originator to putAllOperation's last version tags. This map
   * differs from {@link #recordedPutAllOps} in that the thread identifier used
   * here is the base member id and thread id of the putall, as opposed to the fake
   * thread id which is assigned for each bucket.
   * 
   * recordedPutAllOps are also only tracked on the secondary for partitioned regions
   * recordedPutAllVersionTags are tracked on both the primary and secondary.
   *
   * Keys are instances of @link {@link ThreadIdentifier}, values are instances
   * of {@link PutAllHolder}.
   */
  private final ConcurrentMap<ThreadIdentifier, PutAllHolder> recordedPutAllVersionTags 
      = new ConcurrentHashMap<ThreadIdentifier, PutAllHolder>(100);
  
  /**
   * The member that the region corresponding to this tracker (if any)
   * received its initial image from (if a replicate)
   */
  private volatile InternalDistributedMember initialImageProvider;

 
  /**
   * The cache associated with this tracker
   */
  GemFireCacheImpl cache;
 
  /**
   * The name of this tracker
   */
  String name;
  
  /**
   * whether or not this tracker has been initialized with state from
   * another process
   */
  volatile boolean initialized;
 
  /**
   * object used to wait for initialization 
   */
  final StoppableCountDownLatch initializationLatch;
  
  /**
  * Initialize the EventTracker's timer task.  This is stored in the cache
  * for tracking and shutdown purposes
  * @param cache the cache to schedule tasks with
  */
  public static ExpiryTask startTrackerServices(GemFireCacheImpl cache) {
    long expiryTime = Long.getLong("gemfire.messageTrackingTimeout",
        PoolFactory.DEFAULT_SUBSCRIPTION_MESSAGE_TRACKING_TIMEOUT / 3).longValue();
    ExpiryTask result = new ExpiryTask(cache, expiryTime);
    cache.getCCPTimer().scheduleAtFixedRate(result,
        expiryTime, expiryTime);
    //schedule(result, expiryTime);
    return result;
  }
  
  /**
   * Terminate the tracker's timer task
   * @param cache the cache holding the tracker task
   */
  public static void stopTrackerServices(GemFireCacheImpl cache) {
    cache.getEventTrackerTask().cancel();
  }
 
 /**
  * Create an event tracker
  * @param region the cache region to associate with this tracker
  */
  public EventTracker(LocalRegion region) {
   this.cache = region.cache;
   this.name = "Event Tracker for " + region.getName();
   this.initializationLatch = new StoppableCountDownLatch(region.stopper, 1);
 }

  /** start this event tracker */
  public void start() {
    if (this.cache.getEventTrackerTask() != null) {
      this.cache.getEventTrackerTask().addTracker(this);
    }
  }
  
  /** stop this event tracker */
  public void stop() {
    if (this.cache.getEventTrackerTask() != null) {
      this.cache.getEventTrackerTask().removeTracker(this);
    }
  }
  
  /**
   * retrieve a deep copy of the state of the event tracker.  Synchronization
   * is not used while copying the tracker's state.
   */
  public Map<ThreadIdentifier, EventSeqnoHolder> getState() {
    Map<ThreadIdentifier, EventSeqnoHolder> result = new HashMap<ThreadIdentifier, EventSeqnoHolder>(recordedEvents.size());
    for (Iterator<Map.Entry<ThreadIdentifier, EventSeqnoHolder>> it=recordedEvents.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<ThreadIdentifier, EventSeqnoHolder> entry = it.next();
      EventSeqnoHolder holder = entry.getValue();
      result.put(entry.getKey(), new EventSeqnoHolder(
          holder.lastSeqno, null)); // don't transfer version tags - adds too much bulk just so we can do client tag recovery
    }
    return result;
  }
  
  /**
   * record the given state in the tracker.
   * @param provider the member that provided this state
   * @param state a Map obtained from getState();
   */
  public void recordState(InternalDistributedMember provider, Map<ThreadIdentifier, EventSeqnoHolder> state) {
    this.initialImageProvider = provider;
    LogWriterI18n log = this.cache.getLoggerI18n();
    StringBuffer sb = null;
    if (log.finerEnabled() || VERBOSE) {
      sb = new StringBuffer(200);
      sb.append("Recording initial state for ")
        .append(this.name)
        .append(": ");
    }
    for (Iterator<Map.Entry<ThreadIdentifier, EventSeqnoHolder>> it=state.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<ThreadIdentifier, EventSeqnoHolder> entry = it.next();
      if (sb != null) {
        sb.append("\n  ")
          .append(entry.getKey().expensiveToString())
          .append("; sequenceID=")
          .append(entry.getValue()); 
      }
      // record only if we haven't received an event that is newer
      recordSeqno(entry.getKey(), entry.getValue(), true);
    }
    if (sb != null) {
      log.info(LocalizedStrings.DEBUG, sb.toString());
    }
    // fix for bug 41622 - hang in GII.  This keeps ops from waiting for the
    // full GII to complete
    setInitialized();
  }
  
  /**
   * Use this method to ensure that the tracker is put in an initialized state
   */
  public void setInitialized() {
    this.initializationLatch.countDown();
    this.initialized = true;
  }
  
  /**
   * Wait for the tracker to finishe being initialized
   */
  public void waitOnInitialization() throws InterruptedException {
    this.initializationLatch.await();
  }
  
  /**
   * Record an event sequence id if it is higher than what we currently have.
   * This is intended for use during initial image transfer.
   * @param membershipID the key of an entry in the map obtained from getEventState()
   * @param evhObj the value of an entry in the map obtained from getEventState()
   */
  protected void recordSeqno(ThreadIdentifier membershipID, EventSeqnoHolder evhObj){
    recordSeqno(membershipID, evhObj, false);
  }
  
  /**
   * Record an event sequence id if it is higher than what we currently have.
   * This is intended for use during initial image transfer.
   * @param threadID the key of an entry in the map obtained from getEventState()
   * @param evh the value of an entry in the map obtained from getEventState()
   * @param ifAbsent only record this state if there's not already an entry for this memberID
   */
  private void recordSeqno(ThreadIdentifier threadID, EventSeqnoHolder evh, boolean ifAbsent) {
    boolean removed;
    StringBuilder sb = null; 
    if (VERBOSE) {
      sb = new StringBuilder("recordSeqNo: threadID=").append(threadID)
          .append(" newEvh:").append(evh).append(" ifAbsent=").append(ifAbsent)
          .append('\n');
    }
    
    do {
      removed = false;
      EventSeqnoHolder oldEvh = recordedEvents.putIfAbsent(
          threadID, evh);
      if (oldEvh != null) {
        synchronized(oldEvh) {
          if (oldEvh.removed) {
            // need to wait for an entry being removed by the sweeper to go away
            removed = true;
            if (sb != null) {
              sb.append("waiting on oldEvh=").append(oldEvh).append('\n');
            }
            continue;
          }
          else {
            if (ifAbsent) {
              break;
            }
            if (sb != null) {
              sb.append("operating on oldEvh=").append(oldEvh).append('\n');
            }
            oldEvh.endOfLifeTimer = 0;
            if (oldEvh.lastSeqno < evh.lastSeqno) {
              oldEvh.lastSeqno = evh.lastSeqno;
              oldEvh.versionTag = evh.versionTag;
              if (sb != null) {
                sb.append("converted to oldEvh=").append(oldEvh).append('\n');
              }
              //              Exception e = oldEvh.context;
//              oldEvh.context = new Exception("stack trace");
//              oldEvh.context.initCause(e);
              //cache.getLogger().info("Debug: updated seqno " + oldEvh);
            }
          }
        }
      }
      else {
        evh.endOfLifeTimer = 0;
        if (sb != null) {
          sb.append("setting endOfLifeTimer to zero").append('\n');
        }
//        evh.context = new Exception("stack trace");
        //cache.getLogger().info("Debug: set new seqno " + evh);
      }
    } while (removed);
    
    if (sb != null) {
      LogWriterI18n log = this.cache.getLoggerI18n();
      log.info(LocalizedStrings.DEBUG, sb.toString());
    }
    
  }
  
  /** record the event's threadid/sequenceid to prevent replay */
  public void recordEvent(InternalCacheEvent event) {
    EventID eventID = event.getEventId();
    if (ignoreEvent(event, eventID)) {
      return; // not tracked
    }

    LogWriterI18n log = this.cache.getLoggerI18n();
    LocalRegion lr = (LocalRegion)event.getRegion();
//    if (lr != null && lr.concurrencyChecksEnabled
//        && event.getOperation().isEntry() && lr.isUsedForPartitionedRegionBucket()
//        && event.getVersionTag() == null) {
//      log.severe(LocalizedStrings.DEBUG, "recording an event with no version tag", new Exception("stack trace"));
//    }

//    log.info(LocalizedStrings.DEBUG, "recording event " + event);

    ThreadIdentifier membershipID = new ThreadIdentifier(eventID.getMembershipID(),
        eventID.getThreadID());

    VersionTag tag = null;
    if (lr.getServerProxy() == null/* && event.hasClientOrigin()*/) { // clients do not need to store version tags for replayed events
      tag = event.getVersionTag();
      RegionVersionVector v = ((LocalRegion)event.getRegion()).getVersionVector();
      // bug #46453 - make sure ID references are canonical before storing
      if (v != null && tag != null) {
        tag.setMemberID(v.getCanonicalId(tag.getMemberID()));
        if (tag.getPreviousMemberID() != null) {
          tag.setPreviousMemberID(v.getCanonicalId(tag.getPreviousMemberID()));
        }
      }
    }
    EventSeqnoHolder newEvh = new EventSeqnoHolder(eventID.getSequenceID(), tag);
    if (log.finerEnabled() || VERBOSE) {
      log.info(LocalizedStrings.DEBUG, "region event tracker recording eventID:" + eventID
          + " CacheEvent:" + event);
    }
    recordSeqno(membershipID, newEvh);
    
    //If this is a put all, and concurrency checks are enabled, we need to
    //save the version tag in case we retry.
    if (lr.concurrencyChecksEnabled 
        && event.getOperation().isPutAll() && lr.getServerProxy() == null) {
      recordPutAllEvent(event, membershipID);
    }
  }

  /**
   * Record a version tag for a put all operation
   */
  private void recordPutAllEvent(InternalCacheEvent event, ThreadIdentifier membershipID) {
    EventID eventID = event.getEventId();
    
    VersionTag tag = event.getVersionTag();
    if (tag == null) {
      return;
    }
    
    RegionVersionVector v = ((LocalRegion)event.getRegion()).getVersionVector();
    // bug #46453 - make sure ID references are canonical before storing
    if (v != null) {
      tag.setMemberID(v.getCanonicalId(tag.getMemberID()));
      if (tag.getPreviousMemberID() != null) {
        tag.setPreviousMemberID(v.getCanonicalId(tag.getPreviousMemberID()));
      }
    }

    //Loop until we can successfully update the recorded put all operations
    //For this thread id.
    boolean retry = false;
    do {
      PutAllHolder putAllTracker = recordedPutAllVersionTags.get(membershipID);
      if(putAllTracker == null) {
        putAllTracker = new PutAllHolder();
        PutAllHolder old = recordedPutAllVersionTags.putIfAbsent(membershipID, putAllTracker);
        if(old != null) {
          retry = true;
          continue;
        }
      }
      synchronized(putAllTracker) {
        if(putAllTracker.removed) {
          retry = true;
          continue;
        }
        
        //Add the version tag for put all event.
        putAllTracker.putVersionTag(eventID, event.getVersionTag());
        retry = false;
      }
    } while(retry);
  }
  
  public boolean hasSeenEvent(InternalCacheEvent event) {
//  ClientProxyMembershipID membershipID = event.getContext();
    EventID eventID = event.getEventId();
    if (ignoreEvent(event, eventID)) {
      return false; // not tracked
    }
    return hasSeenEvent(eventID, event);
  }
  
  public boolean hasSeenEvent(EventID eventID) {
    return hasSeenEvent(eventID, null);
  }

  public boolean hasSeenEvent(EventID eventID, InternalCacheEvent tagHolder) {
    ThreadIdentifier membershipID = new ThreadIdentifier(
        eventID.getMembershipID(), eventID.getThreadID());
//  if (membershipID == null || eventID == null) {
//    return false;
//  }
        
    EventSeqnoHolder evh = recordedEvents.get(membershipID);
    if (evh == null) {
      return false;
    }
    
    synchronized (evh) {
      if (evh.removed || evh.lastSeqno < eventID.getSequenceID()) {
        return false;
      }
      // log at fine because partitioned regions can send event multiple times
      // during normal operation during bucket region initialization
      if (DistributionManager.VERBOSE || BridgeServerImpl.VERBOSE || this.cache.getLogger().fineEnabled()) {
        this.cache.getLoggerI18n().info(LocalizedStrings.DEBUG, "Cache encountered replay of event with ID "
            + eventID
            + ".  Highest recorded for this source is " + evh.lastSeqno
//            + ", set in this context:", evh.context
            );
      }
      // bug #44956 - recover version tag for duplicate event
      if (evh.lastSeqno == eventID.getSequenceID() && tagHolder != null && evh.versionTag != null) {
        ((EntryEventImpl)tagHolder).setVersionTag(evh.versionTag);
      }
      return true;
    } // synchronized
  }

  public VersionTag findVersionTag(EventID eventID) {
    ThreadIdentifier membershipID = new ThreadIdentifier(
        eventID.getMembershipID(), eventID.getThreadID());
        
    EventSeqnoHolder evh = recordedEvents.get(membershipID);
    if (evh == null) {
      return null;
    }
    
    synchronized (evh) {
      if (evh.lastSeqno != eventID.getSequenceID()) {
        return null;
      }
      // log at fine because partitioned regions can send event multiple times
      // during normal operation during bucket region initialization
      if (DistributionManager.VERBOSE || BridgeServerImpl.VERBOSE || this.cache.getLogger().fineEnabled()) {
        if (evh.versionTag == null) {
          this.cache.getLoggerI18n().info(LocalizedStrings.DEBUG, "DEBUG: Could not recover version tag.  Found event holder with no version tag for " + eventID);
        }
      }
      return evh.versionTag;
    } // synchronized
  }
  
  public VersionTag findVersionTagForPutAll(EventID eventID) {
    ThreadIdentifier membershipID = new ThreadIdentifier(
        eventID.getMembershipID(), eventID.getThreadID());
        
    PutAllHolder evh = recordedPutAllVersionTags.get(membershipID);
    if (evh == null) {
      return null;
    }
    
    synchronized (evh) {
      return evh.getVersionTag(eventID);
    } // synchronized
  }

  /**
   * @param event
   * @param eventID
   * @return true if the event should not be tracked, false otherwise
   */
  private boolean ignoreEvent(InternalCacheEvent event, EventID eventID) {
    if (eventID == null) {
      return true;
    } else {
      boolean isVersioned = (event.getVersionTag() != null);
      boolean isClient = event.hasClientOrigin();
      if (isVersioned && isClient) {
        return false; // version tags for client events are kept for retries by the client
      }
      boolean isEntry = event.getOperation().isEntry();
      boolean isPr = event.getRegion().getAttributes().getDataPolicy().withPartitioning()
                   || ((LocalRegion)event.getRegion()).isUsedForPartitionedRegionBucket();
      return (!isClient &&   // ignore if it originated on a server, and
           isEntry &&        // it affects an entry and
          (!isPr && !GemFireCacheImpl.gfxdSystem()));  // is not on a PR
    }
  }

  /**
   * A routine to provide synchronization running based on <memberShipID, threadID> 
   * of the requesting client
   * @param r - a Runnable to wrap the processing of putAllMsg
   * @param eventID - the base event ID of the putAllMsg
   *
   * @since 5.7
   */
  public void syncPutAll(Runnable r, EventID eventID) {
    Assert.assertTrue(eventID != null);
    ThreadIdentifier membershipID = new ThreadIdentifier(
      eventID.getMembershipID(), eventID.getThreadID());

    PutAllOpProcessed opSyncObj = recordedPutAllOps.putIfAbsent(membershipID, new PutAllOpProcessed(false));
    if (opSyncObj == null) {
      opSyncObj = recordedPutAllOps.get(membershipID);
    }
    synchronized (opSyncObj) {
      try {
        if (opSyncObj.getStatus() && this.cache.getLogger().fineEnabled()) {
          this.cache.getLogger().fine("SyncPutAll: The operation was performed by another thread.");
        }
        else {
          recordPutAllStart(membershipID);
          
          //Perform the put all
          r.run();
          // set to true in case another thread is waiting at sync 
          opSyncObj.setStatus(true);
          recordedPutAllOps.remove(membershipID);
        }
      }
      finally {
        recordedPutAllOps.remove(membershipID);
      }
    }
  }
  
  /**
   * Called when a put all is started on the local region. Used to clear
   * event tracker state from the last put all.
   */
  public void recordPutAllStart(ThreadIdentifier membershipID) {
    this.recordedPutAllVersionTags.remove(membershipID);
  }
  
  /**
   * @return the initialized
   */
  public boolean isInitialized() {
    return this.initialized;
  }
  
  /**
   * @param mbr the member in question
   * @return true if the given member provided the initial image event state for this tracker
   */
  public boolean isInitialImageProvider(DistributedMember mbr) {
    return (this.initialImageProvider != null)
      && (mbr != null)
      && this.initialImageProvider.equals(mbr);
  }
  
  /**
   * Test method for getting the set of recorded version tags.
   */
  protected ConcurrentMap<ThreadIdentifier, PutAllHolder> getRecordedPutAllVersionTags() {
    return recordedPutAllVersionTags;
  }
  
  @Override
  public String toString() {
    return ""+this.name+"(initialized=" + this.initialized+")";
  }
  
  /**
   * A sequence number tracker to keep events from clients from being
   * re-applied to the cache if they've already been seen.
   * @author bruce
   * @since 5.5
   */
  static class EventSeqnoHolder implements DataSerializable {
    private static final long serialVersionUID = 8137262960763308046L;

    /** event sequence number.  These  */
    long lastSeqno = -1;
    
    /** millisecond timestamp */
    transient long endOfLifeTimer;
    
    /** whether this entry is being removed */
    transient boolean removed;
    
    /**
     * version tag, if any, for the operation
     */
    VersionTag versionTag;
    
    // for debugging
//    transient Exception context;
    
    EventSeqnoHolder(long id, VersionTag versionTag) {
      this.lastSeqno = id;
      this.versionTag = versionTag;
    }
    
    public EventSeqnoHolder() {
    }
    
    @Override
    public String toString() {
      return "seqNo="+this.lastSeqno+",tag="+this.versionTag;
    }

    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      lastSeqno = in.readLong();
      versionTag = (VersionTag)DataSerializer.readObject(in);
    }

    public void toData(DataOutput out) throws IOException {
      out.writeLong(lastSeqno);
      DataSerializer.writeObject(versionTag, out);
    }
  }

  /**
   * A status tracker for each putAllOperation from originators specified by
   * membershipID and threadID in the cache
   * processed is true means the putAllOperation is processed by one thread 
   * no need to redo it by other threads.
   * @author Gester
   * @since 5.7
   */
  static class PutAllOpProcessed {
    /** whether the putAllOp is processed */
    private boolean processed;
  
    /**
     * creates a new instance to save status of a putAllOperation 
     * @param status if the putAllOperation has been processed 
     */
    PutAllOpProcessed(boolean status) {
      this.processed = status;
    }
    
    /**
     * setter method to change the status
     * @param status if the putAllOperation has been processed 
     */
    void setStatus(boolean status) {
      this.processed = status;
    }
    
    /**
     * getter method to peek the current status
     * @return current status
     */
    boolean getStatus() {
      return this.processed;
    }
    
    @Override
    public String toString() {
      return "PAOP("+this.processed+")";
    }
  }
  
  /**
   * A holder for the version tags generated for a put all operation. These
   * version tags are retrieved when a put all is retried.
   * @author Dan
   * @since 7.0
   * protected for test purposes only.
   */
  protected static class PutAllHolder {
    /**
     * Whether this object was removed by the cleanup thread.
     */
    boolean removed;

    /**
     * package-private for tests only
     */
    final LongObjectHashMap<VersionTag<?>> entryVersionTags =
        new LongObjectHashMap<>();

    /**
     * this is used to calculate the capacity of the map which is,
     * unfortunately, not exposed
     */
    private static final long lohmKeysOffset;

    /** millisecond timestamp */
    transient long endOfLifeTimer;

    static {
      try {
        Field f = LongObjectHashMap.class.getDeclaredField("keys");
        f.setAccessible(true);
        lohmKeysOffset = UnsafeHolder.getUnsafe().objectFieldOffset(f);
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    /**
     * creates a new instance to save status of a putAllOperation 
     */
    PutAllHolder() {
    }

    public void putVersionTag(EventID eventId, VersionTag<?> versionTag) {
      final int size;
      if (entryVersionTags.put(eventId.getSequenceID(), versionTag) == null &&
          (size = entryVersionTags.size()) > 100) {
        // if the capacity has grown much larger than size, then compact
        int capacity = ((long[])UnsafeHolder.getUnsafe().getObject(
            entryVersionTags, lohmKeysOffset)).length;
        if ((capacity >>> 1) > size) {
          entryVersionTags.compact();
        }
      }
      this.endOfLifeTimer = 0;
    }

    public VersionTag<?> getVersionTag(EventID eventId) {
      return entryVersionTags.get(eventId.getSequenceID());
    }
    
    @Override
    public String toString() {
      return "PutAllHolder tags=" + this.entryVersionTags;
    }
  }
  
  static class ExpiryTask extends SystemTimerTask {
    
    GemFireCacheImpl cache;
    long expiryTime;
    List trackers = new LinkedList();
    
    public ExpiryTask(GemFireCacheImpl cache, long expiryTime) {
      this.cache = cache;
      this.expiryTime = expiryTime;
    }
    void addTracker(EventTracker tracker) {
      synchronized(trackers) {
        trackers.add(tracker);
      }
    }
    void removeTracker(EventTracker tracker) {
      synchronized(trackers) {
        trackers.remove(tracker);
      }
    }
    int getNumberOfTrackers() {
      return trackers.size();
    }
    @Override
    public LogWriterI18n getLoggerI18n() {
      return this.cache.getLoggerI18n();
    }
    @Override
    public void run2() {
      long now = System.currentTimeMillis();
      long timeout = now - expiryTime;
      boolean finerEnabled = getLoggerI18n().finerEnabled();
      synchronized(trackers) {
        for (Iterator it=trackers.iterator(); it.hasNext(); ) {
          EventTracker tracker = (EventTracker)it.next();
          if (finerEnabled) {
            getLoggerI18n().finer(tracker.name + " sweeper: starting");
          }
          for (Iterator it2 = tracker.recordedEvents.entrySet().iterator(); it2.hasNext();) {
            Map.Entry e = (Map.Entry)it2.next();
            EventSeqnoHolder evh = (EventSeqnoHolder)e.getValue();
            synchronized(evh) {
              if (evh.endOfLifeTimer == 0) {
                evh.endOfLifeTimer = now; // a new holder - start the timer 
              }
              if (evh.endOfLifeTimer <= timeout) {
                evh.removed = true;
                evh.lastSeqno = -1;
                if (finerEnabled) {
                  getLoggerI18n().finer(tracker.name + " sweeper: removing " + e.getKey());
                }
                it2.remove();
              }
            }
          }
          
          //Remove put all operations we're tracking
          for (Iterator<Map.Entry<ThreadIdentifier, PutAllHolder>> it2 = tracker.recordedPutAllVersionTags.entrySet().iterator(); it2.hasNext();) {
            Map.Entry<ThreadIdentifier, PutAllHolder> e = it2.next();
            PutAllHolder evh = e.getValue();
            synchronized(evh) {
              if (evh.endOfLifeTimer == 0) {
                evh.endOfLifeTimer = now; // a new holder - start the timer 
              }
              //Remove the PutAll tracker only if the put all is complete
              //and it has expired.
              if (evh.endOfLifeTimer <= timeout) {
                evh.removed = true;
                if (finerEnabled) {
                  getLoggerI18n().finer(tracker.name + " sweeper: removing put all " + e.getKey());
                }
                it2.remove();
              }
            }
          }
          if (finerEnabled) {
            getLoggerI18n().finer(tracker. name + " sweeper: done");
          }
        }
      }
    }
    
  }
}
