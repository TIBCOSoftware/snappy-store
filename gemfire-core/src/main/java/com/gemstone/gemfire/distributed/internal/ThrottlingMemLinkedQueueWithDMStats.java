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

package com.gemstone.gemfire.distributed.internal;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;

/**
 * An instance of ThrottlingMemLinkedQueue allows the instantiator to
 * specify a maximum queue footprint (M) and a size to begin throttling (B) 
 * (which must be between 1 and the M).  When adding an element to
 * the queue, if the size of the queue is less than B, the element is
 * added immediately.  In case of udp, If the size of the queue has reached M, 
 * the add will block until the size is less than M.  If the size of the
 * queue is between B and M, the add will block with a sleep time that
 * is at least 1 millisecond, and is proportional to the size of the 
 * queue.
 *
 * ThrottlingMemLinkedQueue objects can currently hold only Sizeable objects.
 * Inserting other types of objects will cause class cast exceptions to be thrown
 * on put/take.
 *
 * @since 3.0
 *
 * @author bruce
 */

public class ThrottlingMemLinkedQueueWithDMStats extends OverflowQueueWithDMStats {
  private static final long serialVersionUID = 5425180246954573433L;

  /** The maximum size of data in the queue */
  private final long maxMemSize;
  
  /** The size at which to begin throttling */
  private final long startThrottleMemSize;

  /** The maximum size of the queue */
  private final int maxSize;
  
  /** The size at which to begin throttling */
  private final int startThrottleSize;

  /** The current memory footprint of the queue */
  private final AtomicLong memSize;

  /** Creates a new instance of ThrottlingMessageQueue */
  public ThrottlingMemLinkedQueueWithDMStats(long maxMemSize, long startThrottleMemSize,
                                             int maxSize, int startThrottleSize,
                                          ThrottledMemQueueStatHelper stats) {
    super(maxSize, stats);
    this.maxMemSize = maxMemSize;
    this.startThrottleMemSize = startThrottleMemSize;
    this.maxSize = maxSize;
    this.startThrottleSize = startThrottleSize;
    this.memSize = new AtomicLong(0L);
  }
  
  /** Check if the sender needs to be throttled. Returns the time the sender should sleep */
  public int getThrottleTime() {    
    return calculateThrottleTime();
  }
  
  public long getMemSize() {
    return memSize.get();
  }

  private int calculateThrottleTime() {
    int sleep;

    long myMemSize = memSize.get();
    if (myMemSize > startThrottleMemSize) {
      sleep = (int)(((float)(myMemSize - startThrottleMemSize) / (float)(maxMemSize - startThrottleMemSize)) * 100); 
    } else {
      if (isCriticalUp()) {
        return 10; // fixed sleep for CRITICAL_UP
      }
      int qSize = size();
      if (qSize > startThrottleSize) {
        sleep = (int)(((float)(qSize - startThrottleSize) / (float)(maxSize - startThrottleSize)) * 100);
      } else {
        // no need to throttle
        return 0;
      }
    }
       
    // Increment sleep count with linear step as the size approaches max value.
    sleep = sleep * ((sleep / 10) + 1);
    sleep = Math.max(sleep, 1);
    
    return sleep;
  }

  public static boolean isCriticalUp() {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    final HeapMemoryMonitor hmm;
    return cache != null &&
        (hmm = cache.getResourceManager(false).getHeapMonitor()) != null &&
        hmm.isCriticalUp();
  }

  @Override
  protected void preAdd(Object o) {
    try {
      preAddInterruptibly(o);
    } 
    catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      // TODO cancellation check?
    }
  }
  @Override
  protected void preAddInterruptibly(Object o) throws InterruptedException {
    if (Thread.interrupted()) throw new InterruptedException();
    // only block threads reading from tcp stream sockets.  blocking udp
    // will cause retransmission storms
    if (!DistributionMessage.isPreciousThread()) {
      long startTime = DistributionStats.getStatTime();
      do {
        try {
          int sleep = calculateThrottleTime();
          if (sleep > 0) {
            Thread.sleep(sleep);
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          // The interrupt terminates the throttling sleep and quickly
          // returns, which is probably the Right Thing.
        }
        if (DistributionStats.enableClockStats) {
          final long endTime = DistributionStats.getStatTime();
          ((ThrottledMemQueueStatHelper)this.stats).throttleTime(endTime-startTime);
          startTime = endTime;
        }
      } while (memSize.get() >= maxMemSize || isCriticalUp() || size() >= maxSize);
      
      ((ThrottledMemQueueStatHelper)this.stats).incThrottleCount();
    }

    if (o instanceof Sizeable) {
      int mem = ((Sizeable)o).getSize();
      ((ThrottledMemQueueStatHelper)this.stats).addMem(mem);
      this.memSize.getAndAdd(mem);
    }
  }
  
  @Override
  protected void postRemove(Object o) {
    if (o != null && (o instanceof Sizeable)) {
      int mem = ((Sizeable)o).getSize();
      this.memSize.getAndAdd(-mem);
      ((ThrottledMemQueueStatHelper)this.stats).removeMem(mem);
    }
  }
  
  @Override
  protected void postDrain(Collection c) {
    Iterator it = c.iterator();
    while (it.hasNext()) {
      postRemove(it.next());
    }
  }
}
