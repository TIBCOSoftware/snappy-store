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

package com.gemstone.gemfire.internal.cache.lru;

import java.util.List;

import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InternalRegionArguments;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.PlaceHolderDiskRegion;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;


/**
 *  AbstractLRUClockHand holds the lrulist, and the behavior for
 *  maintaining the list in a cu-pipe and determining the next entry to be removed.
 *  Each EntriesMap that supports LRU holds one of these.
 */
public class NewLRUClockHand  {
  private BucketRegion bucketRegion = null;

  /**  The last node in the LRU list after which all new nodes are added */
  protected LRUClockNode tail = new GuardNode();

  /**  The starting point in the LRU list for searching for the LRU node */
  protected LRUClockNode head = new GuardNode();
  
  /** The object for locking the head of the cu-pipe. */
  final protected HeadLock lock;

  /**  Description of the Field */
  final private LRUStatistics stats;
  /** Counter for the size of the LRU list */
  protected int size = 0;
  
public static final boolean debug = Boolean.getBoolean("gemfire.verbose-lru-clock");

public static LogWriterI18n logWriter;

static private final int maxEntries;

static {
  String squelch = System.getProperty("gemfire.lru.maxSearchEntries");
  if (squelch == null)
    maxEntries = -1;
  else
    maxEntries = Integer.parseInt(squelch);
}

  /** only used by enhancer */
  // protected NewLRUClockHand( ) { }
  
//   private long size = 0;

public NewLRUClockHand(Object region, EnableLRU ccHelper,
      InternalRegionArguments internalRegionArgs) {
    setBucketRegion(region);
    this.lock = new HeadLock();
     // behavior relies on a single evicted node in the pipe when the pipe is empty.
    if ( NewLRUClockHand.debug ) {
      NewLRUClockHand.logWriter = GemFireCacheImpl.getExisting("").getDistributedSystem().getLogWriter().convertToLogWriterI18n();
    }
    initHeadAndTail();
    if (this.bucketRegion != null) {
      this.stats = internalRegionArgs.getPartitionedRegion() != null ? internalRegionArgs
          .getPartitionedRegion().getEvictionController().stats
          : null;
    }
    else {
      LRUStatistics tmp = null;
      if (region instanceof PlaceHolderDiskRegion) {
        tmp = ((PlaceHolderDiskRegion)region).getPRLRUStats();
      } else if (region instanceof PartitionedRegion) {
        tmp = ((PartitionedRegion)region).getPRLRUStatsDuringInitialization(); // bug 41938
        PartitionedRegion pr = (PartitionedRegion)region;
        if (tmp != null) {
          pr.getEvictionController().stats = tmp;
        }
      }
      if (tmp == null) {
        StatisticsFactory sf = GemFireCacheImpl.getExisting("").getDistributedSystem();
        tmp = ccHelper.initStats(region, sf);
      }
      this.stats = tmp;
    }
  }

  public void setBucketRegion(Object r) {
    if (r instanceof BucketRegion) {
      this.bucketRegion = (BucketRegion)r; // see bug 41388
    }
  }
 
  public NewLRUClockHand( Region region, EnableLRU ccHelper
  ,NewLRUClockHand oldList) {
    setBucketRegion(region);
    this.lock = new HeadLock();
     // behavior relies on a single evicted node in the pipe when the pipe is empty.
    if ( NewLRUClockHand.debug ) {
      NewLRUClockHand.logWriter = region.getCache().getDistributedSystem().getLogWriter().convertToLogWriterI18n();
    }
    initHeadAndTail();
    if (oldList.stats == null) {
      // see bug 41388
      StatisticsFactory sf = region.getCache().getDistributedSystem();
      this.stats = ccHelper.initStats(region, sf);
    } else {
      this.stats = oldList.stats;
      if (this.bucketRegion != null) {
        this.stats.decrementCounter(this.bucketRegion.getCounter());
        this.bucketRegion.resetCounter();
      }
      else {
        this.stats.resetCounter();
      }
    }
  }
  
  /**  Description of the Method */
  public void close() {
    closeStats();
    if(bucketRegion!=null)
      bucketRegion.close();
  }

  public void closeStats() {
    LRUStatistics ls = this.stats;
    if( ls != null ) {
      ls.close();
    }
  }

  /**
   *  Adds a new lru node for the entry between the current tail and head
   *  of the list.
   *
   * @param  aNode  Description of the Parameter
   */  
  public final void appendEntry( final LRUClockNode aNode ) {
    synchronized (this.lock) {
      if (aNode.nextLRUNode() != null || aNode.prevLRUNode() != null) {
        return;
      }

      if (debug) {
        logWriter
            .info(LocalizedStrings.NewLRUClockHand_ADDING_ANODE_TO_LRU_LIST, aNode);
      }
      aNode.setNextLRUNode(this.tail);
      this.tail.prevLRUNode().setNextLRUNode(aNode);
      aNode.setPrevLRUNode(this.tail.prevLRUNode());
      this.tail.setPrevLRUNode(aNode);
      
      this.size++;
    }
  }

  /** return the head entry in the list preserving the cupipe requirement of at
   * least one entry left in the list 
   */
  protected LRUClockNode getNextEntry() {
    synchronized (lock) {
      LRUClockNode aNode = NewLRUClockHand.this.head.nextLRUNode();
      if(aNode == this.tail) {
        return null;
      }
      
      LRUClockNode next = aNode.nextLRUNode();
      this.head.setNextLRUNode(next);
      next.setPrevLRUNode(this.head);
      
      aNode.setNextLRUNode(null);
      aNode.setPrevLRUNode(null);
      this.size--;
      return aNode;
    }
  }

  protected boolean checkRecentlyUsed(LRUClockNode aNode) {
    if (aNode.testRecentlyUsed()) {
      // Throw it back, it's in the working set
      aNode.unsetRecentlyUsed();
      // aNode.setInList();
      if (debug) {
        logWriter.info(LocalizedStrings
            .NewLRUClockHand_SKIPPING_RECENTLY_USED_ENTRY, aNode);
      }
      appendEntry(aNode);
      return true;
    }
    else {
      return false;
    }
  }

  /** return the Entry that is considered least recently used. The entry will no longer
    * be in the pipe (unless it is the last empty marker).
    */
  public LRUClockNode getLRUEntry() {
    return getLRUEntry(null);
  }

  /**
   * Return the Entry that is considered least recently used. The entry will no longer
   * be in the pipe (unless it is the last empty marker).
   *
   * @param skipLockedEntries if non-null then skip any locked entries and return
   *                          with unreleased monitor lock on entry that caller
   *                          is required to release using Unsafe API (SNAP-2012);
   *                          the provided list will contain the entries that were
   *                          skipped and must be put back into the LRU list
   */
  public LRUClockNode getLRUEntry(List<LRUClockNode> skipLockedEntries) {
    long numEvals = 0;
    
    for (;;) {
	LRUClockNode aNode = null;
	aNode = getNextEntry();

	if (debug && logWriter.finerEnabled()) {
	  logWriter.finer("lru considering " + aNode);
	}

      if ( aNode == null ) { // hit the end of the list
        this.stats.incEvaluations(numEvals);
        return aNode;
      } // hit the end of the list

      numEvals++;

      // TODO: TX: currently skip LRU processing for a transactionally locked
      // entry since value is already in TXState so no use of evicting it
      // until we have on-disk TXStates
      if (ExclusiveSharedSynchronizer.isWrite(aNode.getState())) {
        if (debug) {
          logWriter.info(LocalizedStrings
              .NewLRUClockHand_REMOVING_TRANSACTIONAL_ENTRY_FROM_CONSIDERATION);
        }
        appendEntry(aNode);
        continue;
      }

      boolean success = false;
      // if required skip a locked entry and keep the lock (caller should release)
      if (skipLockedEntries != null) {
        if (!UnsafeHolder.tryMonitorEnter(aNode, false)) {
          skipLockedEntries.add(aNode);
          continue;
        }
      } else {
        UnsafeHolder.monitorEnter(aNode);
      }
      // If this Entry is part of a transaction, skip it since
      // eviction should not cause commit conflicts
      try {
        if (aNode.testEvicted()) {
          if (debug) {
            logWriter
                .info(LocalizedStrings.NewLRUClockHand_DISCARDING_EVICTED_ENTRY);
          }
          continue;
        }

        // At this point we have any acceptable entry.  Now
        // use various criteria to determine if it's good enough
        // to return, or if we need to add it back to the list.
        if (maxEntries > 0 && numEvals > maxEntries) {
          if (debug) {
            logWriter
                .info(LocalizedStrings.NewLRUClockHand_GREEDILY_PICKING_AN_AVAILABLE_ENTRY);
          }
          this.stats.incGreedyReturns(1);
          // fall through, return this node           
        }
        else
        if (checkRecentlyUsed(aNode)) {
          continue; // keep looking
        }
        else {
          if ( debug ) logWriter.info(LocalizedStrings.NewLRUClockHand_RETURNING_UNUSED_ENTRY, aNode);
          // fall through, return this node
        }

        // Return the current node.
        this.stats.incEvaluations(numEvals);
        success = true;
        return aNode;
      } finally { // synchronized
        if (!success || skipLockedEntries == null) {
          UnsafeHolder.monitorExit(aNode);
        }
      }
    } // for
  }

  public void dumpList(LogWriterI18n log) {
    synchronized (lock) {
      int idx=1;
      for (LRUClockNode aNode = this.head; aNode != null; aNode = aNode.nextLRUNode())  {
        log.fine("  " + (idx++) + ") " + aNode);
      }
    }
  }
  
  public long getExpensiveListCount() {
    synchronized (lock) {
      long count = 0;
      for (LRUClockNode aNode = this.head.nextLRUNode(); aNode != this.tail; aNode = aNode.nextLRUNode()) {
        count++;
      }
      return count;
    }
  }
  
  public String getAuditReport( ) {
    LRUClockNode h = this.head;
    int totalNodes = 0;
    int evictedNodes = 0;
    int usedNodes = 0;
    while( h != null ) {
      totalNodes++;
      if ( h.testEvicted() ) evictedNodes++;
      if ( h.testRecentlyUsed() ) usedNodes++;
      h = h.nextLRUNode();
    }
    StringBuilder result = new StringBuilder(128);
    result.append("LRUList Audit: listEntries = ")
      .append(totalNodes)      
      .append(" evicted = ")
      .append(evictedNodes)
      .append(" used = ")
      .append(usedNodes);
    return result.toString();
  }

  /** unsynchronized audit...only run after activity has ceased. */
  public void audit( ) {
    System.out.println(getAuditReport());
  }
  
  /** remove an entry from the pipe... (marks it evicted to be skipped later) */
  public boolean unlinkEntry(LRUClockNode entry) {
    if (debug) {
      logWriter.info(LocalizedStrings.NewLRUClockHand_UNLINKENTRY_CALLED, entry);
    }
    entry.setEvicted();
    stats().incDestroys();
    synchronized(lock) {
      LRUClockNode next = entry.nextLRUNode();
      LRUClockNode prev = entry.prevLRUNode();
      if(next == null || prev == null) {
        //not in the list anymore.
        return false;
      }
      next.setPrevLRUNode(prev);
      prev.setNextLRUNode(next);
      entry.setNextLRUNode(null);
      entry.setPrevLRUNode(null);
    }
    return true;
  }
  
  /**
   *  Get the modifier for lru based statistics.
   *
   * @return    The LRUStatistics for this Clock hand's region.
   */
  public final LRUStatistics stats() {
    return this.stats; 
  }  /**
   * called when an LRU map is cleared... resets stats and releases prev and next.
   */

  public void clear(RegionVersionVector rvv) {
    if (rvv != null) {
      return; // when concurrency checks are enabled the clear operation removes entries iteratively
    }
    synchronized  (this.lock ) {
      if (bucketRegion!=null) {
        this.stats.decrementCounter(bucketRegion.getCounter());
        bucketRegion.resetCounter();
      }
      else {
        this.stats.resetCounter();
      }
      initHeadAndTail();
      //      LRUClockNode node = this.tail;
      //      node.setEvicted();
      //      
      //      // NYI need to walk the list and call unsetInList for each one.
      //      
      //      // tail's next should already be null.
      //      setHead( node );
    }
  }

  private void initHeadAndTail() {
    //I'm not sure, but I think it's important that we 
    //drop the references to the old head and tail on a region clear
    //That will prevent any concurrent operations that are messing
    //with existing nodes from screwing up the head and tail after
    //the clear.
    //Dan 9/23/09
    this.head = new GuardNode();
    this.tail = new GuardNode();
    this.head.setNextLRUNode(this.tail);
    this.tail.setPrevLRUNode(this.head);
    this.size = 0;
  }
  
  /** perform work of clear(), after subclass has properly synchronized */
//  private void internalClear() {
//    stats().resetCounter();
//    LRUClockNode node = this.tail;
//    node.setEvicted();
//
//    // NYI need to walk the list and call unsetInList for each one.
//    
//    // tail's next should already be null.
//    setHead( node );
//  }

  /** Marker class name to identify the lock more easily in thread dumps */
  protected static class HeadLock extends Object  { }
  
  private static final class GuardNode implements LRUClockNode {

    private LRUClockNode next;
    LRUClockNode prev;

    public int getEntrySize() {
      return 0;
    }

    public LRUClockNode nextLRUNode() {
      return next;
    }

    public LRUClockNode prevLRUNode() {
      return prev;
    }

    public void setEvicted() {
    }

    public void setNextLRUNode(LRUClockNode next) {
      this.next = next;
    }

    public void setPrevLRUNode(LRUClockNode prev) {
      this.prev = prev;
    }

    public void setRecentlyUsed() {
    }

    public boolean testEvicted() {
      return false;
    }

    public boolean testRecentlyUsed() {
      return false;
    }

    public void unsetEvicted() {
    }

    public void unsetRecentlyUsed() {
    }

    public int updateEntrySize(EnableLRU ccHelper) {
      return 0;
    }

    public int updateEntrySize(EnableLRU ccHelper, Object value) {
      return 0;
    }

    public int getState() {
      return 0;
    }

    @Override
    public boolean isValueNull() {
      return false; // Guard nodes can never be null
    }
  }
}

