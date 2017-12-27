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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.distributed.internal.OverflowQueueWithDMStats;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEvictorTask;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceType;
import com.gemstone.gemfire.internal.cache.control.MemoryEvent;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.cache.control.ResourceListener;
import com.gemstone.gemfire.internal.concurrent.AB;
import com.gemstone.gemfire.internal.concurrent.CFactory;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.LauncherBase;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectLongHashMap;

/**
 * Triggers centralized eviction(asynchronously) when the ResourceManager sends
 * an eviction event for on-heap regions. This is registered with the ResourceManager.
 * 
 * @author Yogesh, Suranjan, Amardeep, rholmes
 * @since 6.0
 * 
 */
public class HeapEvictor implements ResourceListener<MemoryEvent> {

  // Add 1 for the management task that's putting more eviction tasks on the queue
  public static final int MAX_EVICTOR_THREADS = Integer.getInteger(
      "gemfire.HeapLRUCapacityController.MAX_EVICTOR_THREADS", (Runtime.getRuntime().availableProcessors()*4)) + 1;

  public static final boolean DISABLE_HEAP_EVICTIOR_THREAD_POOL = Boolean
      .getBoolean("gemfire.HeapLRUCapacityController.DISABLE_HEAP_EVICTIOR_THREAD_POOL");

  public static final String EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_PROP =
      LauncherBase.EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_PROP;

  public static final boolean EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST = Boolean.valueOf(
      System.getProperty(EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_PROP,
          "true")).booleanValue(); 

  public static final String EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR_PROP =
      LauncherBase.EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR_PROP;

  public static final boolean EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR =
      Boolean.valueOf(System.getProperty(EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR_PROP,
          Boolean.toString(EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST))).booleanValue();

  public static final int MINIMUM_ENTRIES_PER_BUCKET = Integer
  .getInteger("gemfire.HeapLRUCapacityController.inlineEvictionThreshold",0);
  
  public static final long TOTAL_BYTES_TO_EVICT_FROM_HEAP; 

  public static final int BUCKET_SORTING_INTERVAL = Integer.getInteger(
      "gemfire.HeapLRUCapacityController.higherEntryCountBucketCalculationInterval",
      100).intValue();
  
  private static final String EVICTOR_THREAD_GROUP_NAME = "EvictorThreadGroup";
  
  private static final String EVICTOR_THREAD_NAME = "EvictorThread";

  static {
    float evictionBurstPercentage = Float.parseFloat(System.getProperty(
        ResourceManager.EVICTION_BURST_PERCENT_PROP, "0.4"));
    long maxTenuredBytes = HeapMemoryMonitor.getTenuredPoolMaxMemory();
    TOTAL_BYTES_TO_EVICT_FROM_HEAP = (long)(maxTenuredBytes * 0.01 * evictionBurstPercentage);
  }
  
  private ThreadPoolExecutor evictorThreadPool = null;

  private final LogWriterI18n logger;

  private final AB mustEvict = CFactory.createAB(false);

  protected final Cache cache;  

  private final ArrayList testTaskSetSizes = new  ArrayList();
  public volatile int testAbortAfterLoopCount = Integer.MAX_VALUE;
  
  private BlockingQueue<Runnable> poolQueue;
  
  private final AB isRunning = CFactory.createAB(true);
  
  public HeapEvictor(Cache gemFireCache) {
    this.cache = gemFireCache;
    this.logger = cache.getLoggerI18n();
    initializeEvictorThreadPool();
  }

  protected boolean includePartitionedRegion(PartitionedRegion region) {
    return (region.getEvictionAttributes().getAlgorithm().isLRUHeap() 
        && (region.getDataStore() != null) 
        && !region.getAttributes().getEnableOffHeapMemory());
  }
  
  protected boolean includeLocalRegion(LocalRegion region) {
    return (region.getEvictionAttributes().getAlgorithm().isLRUHeap() 
        && !region.getAttributes().getEnableOffHeapMemory());
  }
  
  private List<LocalRegion> getAllRegionList() {
    List<LocalRegion> allRegionList = new ArrayList<LocalRegion>();
    InternalResourceManager irm = (InternalResourceManager)cache
        .getResourceManager();
    
    for (ResourceListener<MemoryEvent> listener : irm.getResourceListeners(getResourceType())) {
      if (listener instanceof PartitionedRegion) {
        PartitionedRegion pr = (PartitionedRegion)listener;
        if (includePartitionedRegion(pr)) {
          allRegionList.addAll(pr.getDataStore().getAllLocalBucketRegions());
        }
      }
      else if (listener instanceof LocalRegion) {
        LocalRegion lr = (LocalRegion)listener;
        if (includeLocalRegion(lr)) {
          allRegionList.add(lr);
        }
      }
    }

    if (HeapEvictor.MINIMUM_ENTRIES_PER_BUCKET > 0) {
      Iterator<LocalRegion> iter = allRegionList.iterator();
      while(iter.hasNext()){
        LocalRegion lr = iter.next();
        if (lr instanceof BucketRegion) {
          if (((BucketRegion)lr).getNumEntriesInVM() <= HeapEvictor.MINIMUM_ENTRIES_PER_BUCKET) {
            iter.remove();
          }
        }        
      }
    }
    return allRegionList;
  }
  
  private List<LocalRegion> getAllSortedRegionList(){
    List<LocalRegion> allRegionList = getAllRegionList();
    
    //Capture the sizes so that they do not change while sorting
    final TObjectLongHashMap sizes = new TObjectLongHashMap(allRegionList.size());
    for(LocalRegion r : allRegionList) {
      long size = r instanceof BucketRegion ?((BucketRegion)r).getSizeForEviction() : r.size();
      sizes.put(r, size);
    }
    
    //Sort with respect to other PR buckets also in case of multiple PRs
    Collections.sort(allRegionList, new Comparator<LocalRegion>() {
      public int compare(LocalRegion r1, LocalRegion r2) {        
        long numEntries1 = sizes.get(r1);
        long numEntries2 = sizes.get(r2);
        if (numEntries1 > numEntries2) {
          return -1;
        }
        else if (numEntries1 < numEntries2) {
          return 1;
        }
        return 0;
      }
    });
    return allRegionList;
  }

  public GemFireCacheImpl getGemFireCache() {
    return (GemFireCacheImpl)this.cache;
  }
  
  public LogWriterI18n getLoggerI18n() {
    return this.logger;
  }

  private void initializeEvictorThreadPool() {

    final ThreadGroup evictorThreadGroup = LogWriterImpl.createThreadGroup(
        getEvictorThreadGroupName(), getLoggerI18n());
    ThreadFactory evictorThreadFactory = new ThreadFactory() {
      private int next = 0;

      public Thread newThread(Runnable command) {
        Thread t = new Thread(evictorThreadGroup, command, getEvictorThreadName()
            + next++);
        t.setDaemon(true);
        return t;
      }
    };

    if (!DISABLE_HEAP_EVICTIOR_THREAD_POOL) {
      this.poolQueue = new OverflowQueueWithDMStats(getGemFireCache().getCachePerfStats().getEvictionQueueStatHelper());
      this.evictorThreadPool = new ThreadPoolExecutor(MAX_EVICTOR_THREADS, MAX_EVICTOR_THREADS,
          15, TimeUnit.SECONDS, this.poolQueue, evictorThreadFactory);
    }
  }

  /**
   * The task(i.e the region on which eviction needs to be performed) is
   * assigned to the threadpool.
   */
  private void submitRegionEvictionTask(Callable<Object> task) {
    evictorThreadPool.execute(new Runnable() {
      @Override
      public void run() {
        try {
          task.call();
        } catch (Exception e) {
          if (logger.warningEnabled()) {
            logger.warning(LocalizedStrings.ONE_ARG, e.toString(), e);
          }
        }
      }
    });
  }

  public ThreadPoolExecutor getEvictorThreadPool() {
    if(isRunning.get()) {
      return evictorThreadPool;
    }
    return null;
  }

  /**
   * returns the total number of tasks that are currently being executed or
   * queued for execution
   * 
   * @return sum of scheduled and running tasks
   */
  public int getRunningAndScheduledTasks() {
    if(isRunning.get()){
    return this.evictorThreadPool.getActiveCount()
        + this.evictorThreadPool.getQueue().size();
    }
    return -1;
  }
  
  private void createAndSubmitWeightedRegionEvictionTasks() {
    List<LocalRegion> allRegionList = getAllSortedRegionList();
    float numEntriesInVm = 0 ;
    for(LocalRegion lr : allRegionList){
      if(lr instanceof BucketRegion){
        numEntriesInVm = numEntriesInVm + ((BucketRegion)lr).getSizeForEviction();
      }else {
        numEntriesInVm = numEntriesInVm + lr.getRegionMap().sizeInVM();
      }
    }
    for(LocalRegion lr : allRegionList){
      List<LocalRegion> regionsForSingleTask = new ArrayList<LocalRegion>(1);
      float regionEntryCnt = 0;
      if(lr instanceof BucketRegion){
        regionEntryCnt = ((BucketRegion)lr).getSizeForEviction();
      }else {
        regionEntryCnt = lr.getRegionMap().sizeInVM();
      }
      float percentage = (regionEntryCnt/numEntriesInVm);
      long bytesToEvictPerTask = (long)(getTotalBytesToEvict() * percentage);
      regionsForSingleTask.add(lr);      
      if (mustEvict()) {
        submitRegionEvictionTask(new RegionEvictorTask(regionsForSingleTask, this,bytesToEvictPerTask));
      }else {
        break;
      }       
    }
  }

  private Set<Callable<Object>> createRegionEvictionTasks() {
    @SuppressWarnings("unchecked")
    Set<Callable<Object>> evictorTaskSet = new THashSet();
    int threadsAvailable = getEvictorThreadPool().getCorePoolSize();
    long bytesToEvictPerTask = getTotalBytesToEvict() / threadsAvailable;
    List<LocalRegion> allRegionList = getAllRegionList();
    // This shuffling is not required when eviction triggered for the first time
    Collections.shuffle(allRegionList);
    int allRegionSetSize = allRegionList.size();
    if (allRegionList.isEmpty()) {
      return evictorTaskSet;
    }
    if (allRegionSetSize <= threadsAvailable) {
      for (LocalRegion region : allRegionList) {
        List<LocalRegion> regionList = new ArrayList<LocalRegion>(1);
        regionList.add(region);
        Callable<Object> task = new RegionEvictorTask(regionList, this, bytesToEvictPerTask);
        evictorTaskSet.add(task);
      }
      Iterator iterator=evictorTaskSet.iterator();
      while(iterator.hasNext())
      {
        RegionEvictorTask regionEvictorTask=(RegionEvictorTask)iterator.next();
        testTaskSetSizes.add(regionEvictorTask.getRegionList().size());
      }
      return evictorTaskSet;
    }
    int numRegionsInTask = allRegionSetSize / threadsAvailable;
    List<LocalRegion> regionsForSingleTask = null;
    Iterator<LocalRegion> itr = allRegionList.iterator();
    for (int i = 0; i < threadsAvailable; i++) {
      int count = 1;
      regionsForSingleTask = new ArrayList<LocalRegion>(numRegionsInTask);
      while (count <= numRegionsInTask) {
        if (itr.hasNext()) {
          regionsForSingleTask.add(itr.next());
        }
        count++;
      }
      evictorTaskSet.add(new RegionEvictorTask(regionsForSingleTask, this,bytesToEvictPerTask));
    }
    //Add leftover regions to last task 
    while (itr.hasNext()) {
      regionsForSingleTask.add(itr.next());
    }
    
    Iterator iterator=evictorTaskSet.iterator();
    while(iterator.hasNext())
    {
      RegionEvictorTask regionEvictorTask=(RegionEvictorTask)iterator.next();
      testTaskSetSizes.add(regionEvictorTask.getRegionList().size());
    }
    return evictorTaskSet;
  }
  
  // Since the amount of memory used is to a large degree dependent upon when
  // garbage collection is run, it's difficult to determine when to stop 
  // evicting.  So, an initial calculation is done to determine the number of
  // evictions that are likely needed in order to bring memory usage below the
  // eviction threshold.  This number is stored in 'numFastLoops' and we
  // quickly loop through this number performing evictions.  We then continue
  // to evict, but at a progressively slower rate waiting either for an event
  // which indicates we've dropped below the eviction threshold or another
  // eviction event with an updated "number of bytes used".  If we get another
  // eviction event with an updated "number of bytes used" then 'numFastLoops'
  // is recalculated and we start over.
  protected volatile int numEvictionLoopsCompleted = 0;
  protected volatile int numFastLoops;
  private long previousBytesUsed;
  private final Object evictionLock = new Object();
  StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
  @Override
  public void onEvent(final MemoryEvent event) {
    if (DISABLE_HEAP_EVICTIOR_THREAD_POOL) {
      return;
    }

    //Disable centralized eviction for snappydata. Critical_UP eviction will be still there.
    if(callback.isSnappyStore() && event.getState() == MemoryThresholds.MemoryState.EVICTION){
      return;
    }

    
    // Do we care about eviction events and did the eviction event originate
    // in this VM ...
    if(this.isRunning.get() && event.isLocal()) {
      if (event.getState().isEviction()) {
        final LogWriter logWriter = cache.getLogger();
        
        // Have we previously received an eviction event and already started eviction ...
        if (this.mustEvict.get() == true) {
          if (logWriter.fineEnabled()) {
            logWriter.fine("Updating eviction in response to memory event: " + event + ". previousBytesUsed=" + previousBytesUsed);
          }

          if(callback.isSnappyStore()){
            logWriter.info("CRITICAL_UP event received by HeapEvictor thread. Total bytes used now is " + event.getBytesUsed());
          }
          // We lock here to make sure that the thread that was previously
          // started and running eviction loops is in a state where it's okay
          // to update the number of fast loops to perform.
          synchronized (evictionLock) {
            numEvictionLoopsCompleted = 0;
            numFastLoops = (int) ((event.getBytesUsed() - event.getThresholds().getEvictionThresholdClearBytes()
                + getTotalBytesToEvict()) / getTotalBytesToEvict());
            evictionLock.notifyAll();
          }
          
          // We've updated the number of fast loops to perform, and there's
          // already a thread running the evictions, so we're done.
          return;
        }
        
        if (!this.mustEvict.compareAndSet(false, true)) {
          // Another thread just started evicting.
          return;
        }
        
        numEvictionLoopsCompleted = 0;
        numFastLoops = (int) ((event.getBytesUsed() - event.getThresholds().getEvictionThresholdClearBytes()
            + getTotalBytesToEvict()) / getTotalBytesToEvict());
        if (logWriter.fineEnabled()) {
          logWriter.fine("Starting eviction in response to memory event: " + event);
        }
        
        // The new thread which will run in a loop performing evictions
        final Runnable evictionManagerTask = new Runnable() {
          @Override
          public void run() {
            // Has the test hook been set which will cause eviction to abort early
            if (numEvictionLoopsCompleted < testAbortAfterLoopCount) {
              try {
                // Submit tasks into the queue to do the evictions
                if (EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR) {
                  createAndSubmitWeightedRegionEvictionTasks();
                } else {
                  for (Callable<Object> task : createRegionEvictionTasks()) {
                    submitRegionEvictionTask(task);
                  }
                }
                RegionEvictorTask.setLastTaskCompletionTime(System.currentTimeMillis());
  
                // Make sure that another thread isn't processing a new eviction event
                // and changing the number of fast loops to perform.
                synchronized (evictionLock) {
                  int delayTime = getEvictionLoopDelayTime();
                  if (logWriter.fineEnabled()) {
                    logWriter.fine("Eviction loop delay time calculated to be " + delayTime + " milliseconds. Fast Loops="
                        + numFastLoops + ", Loop #=" + numEvictionLoopsCompleted+1);
                  }
                  numEvictionLoopsCompleted++;
                  try {
                    // Wait and release the lock so that the number of fast loops
                    // needed can be updated by another thread processing a new
                    // eviction event.
                    evictionLock.wait(delayTime);
                  } catch (InterruptedException iex) {
                    // Loop and try again
                  }
                }
                
                // Do we think we're still above the eviction threshold ...
                if (HeapEvictor.this.mustEvict.get()) {
                  // Submit this runnable back into the thread pool and execute
                  // another pass at eviction.
                  HeapEvictor.this.evictorThreadPool.execute(this);
                }
              } catch (RegionDestroyedException e) {
                // A region destroyed exception might be thrown for Region.size() when a bucket
                // moves due to rebalancing. retry submitting the eviction task without
                // logging an error message. fixes bug 48162
                if (HeapEvictor.this.mustEvict.get()) {
                  HeapEvictor.this.evictorThreadPool.execute(this);
                }
              }
            }
          }
        };
        
        // Submit the first pass at eviction into the pool
        this.evictorThreadPool.execute(evictionManagerTask);
          
      } else {
        this.mustEvict.set(false);
      }
    }
  }

  protected int getEvictionLoopDelayTime() {
    int delayTime = 850; // The waiting period when running fast loops
    if ((numEvictionLoopsCompleted - numFastLoops) > 2) {
      delayTime = 3000;  // Way below the threshold
    } else if (numEvictionLoopsCompleted >= numFastLoops) {
      delayTime = (numEvictionLoopsCompleted - numFastLoops + 3) * 500; // Just below the threshold
    }
    
    return delayTime;  
  }
  
  public boolean mustEvict() {
    return this.mustEvict.get();
  }
  
  public void close() {
    getEvictorThreadPool().shutdownNow();
    isRunning.set(false);
  }
  
  public ArrayList testOnlyGetSizeOfTasks()
  {
    if(isRunning.get())
      return testTaskSetSizes;
    return null;
  }
  
  protected String getEvictorThreadGroupName() {
    return HeapEvictor.EVICTOR_THREAD_GROUP_NAME;
  }
  
  protected String getEvictorThreadName() {
    return HeapEvictor.EVICTOR_THREAD_NAME;
  }
  
  public long getTotalBytesToEvict() {
    return TOTAL_BYTES_TO_EVICT_FROM_HEAP;
  }
  
  protected ResourceType getResourceType() {
    return ResourceType.HEAP_MEMORY;
  }
}
