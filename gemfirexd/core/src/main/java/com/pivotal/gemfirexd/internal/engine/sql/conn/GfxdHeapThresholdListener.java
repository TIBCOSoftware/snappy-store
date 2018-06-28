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

package com.pivotal.gemfirexd.internal.engine.sql.conn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.control.MemoryEvent;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholdListener;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceType;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds.MemoryState;
import com.gemstone.gemfire.internal.concurrent.ConcurrentHashSet;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.ResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.StoreStatistics;
import com.pivotal.gemfirexd.internal.iapi.error.ShutdownException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ContextId;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.StatementType;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Listener that is registered with GemFire resource manager to ensure
 * query's temporary workset's memory utilization is within 
 * the critical threshold limit.
 * 
 * <BR>
 * <BR>
 * Default critical memory limit is 90% of the JVM memory size. Gemfire property
 * settings can be used to modulate CRITICAL_UP and CRITICAL_DOWN percentages.
 * 
 * <BR>
 * <BR>
 * For DataStore nodes, maximum query working set is determined and canceled
 * until critical memory limit is down pausing with a time interval controlled by
 * gemfirexd.query-cancellation-interval (in millis) property.
 * 
 * <BR>
 * <BR>
 * For Query nodes, memory estimation can be done from {@link GemFireDistributedResultSet}
 * iterator clasess and {@link ResultHolder}.
 * 
 * <BR>
 * <BR>
 * Default value of query-cancellation-interval is 100 millisecond.
 * <BR>
 * <BR>
 * TODO estimate memory for the query node.
 * 
 * @author soubhikc
 *
 */
public final class GfxdHeapThresholdListener implements MemoryThresholdListener {
  
  /**
   * In every queryCancellationTimeInterval (in millis) a query shall get canceled
   * once memory breaches critical limit.
   */
  public static final int queryCancellationTimeInterval = 
                      Integer.getInteger(GfxdConstants.QUERY_CANCELLATION_TIME_INTERVAL, 
                                         GfxdConstants.DEFAULT_QUERYCANCELLATION_INTERVAL).intValue();

  private final GfxdQueryCanceller queryCanceller;

  private final Thread queryCancellerThread;

  private final LogWriterI18n logger;
  private final StoreStatistics stats;
  
  private final ConcurrentHashSet<DistributedMember> heapCriticalMembers;
  private final ConcurrentHashSet<DistributedMember> heapEvictionMembers;

  private static final String THREAD_NAME = "gemfirexd.QueryCanceller";

  // construct
  private GfxdHeapThresholdListener(GemFireCacheImpl cache) {
    this.logger = cache.getLoggerI18n();
    this.stats = Misc.getMemStoreBooting().getStoreStatistics();

    // check that there is no other QueryCanceller thread
    Thread t;
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread
        .getAllStackTraces().entrySet()) {
      t = entry.getKey();
      if (THREAD_NAME.equals(t.getName())) {
        final StringBuilder sb = new StringBuilder(
            "Existing QueryCanceller thread: ");
        final StackTraceElement[] lines = entry.getValue();
        sb.append(" name=").append(t.getName()).append(" id=")
            .append(t.getId()).append(" priority=").append(t.getPriority())
            .append(" state=").append(t.getState()).append(" isdaemon=")
            .append(t.isDaemon()).append('\n');
        for (int i = 0; i < lines.length; i++) {
          sb.append('\t').append(lines[i]).append('\n');
        }
        GemFireXDUtils.throwAssert(sb.toString());
      }
    }
    this.queryCanceller = new GfxdQueryCanceller();
    t = new Thread(this.queryCanceller, THREAD_NAME);
    t.setDaemon(true);
    // we are ready to listen to InternalResourceManager calls only, anything
    // else can wait because this will free up memory.
    t.setPriority(Thread.MAX_PRIORITY - 1);
    t.start();
    this.queryCancellerThread = t;

    this.heapCriticalMembers = new ConcurrentHashSet<DistributedMember>(16,
        0.75f, 2);
    this.heapEvictionMembers = new ConcurrentHashSet<DistributedMember>(16,
        0.75f, 2);

    this.logger.info(LocalizedStrings.DEBUG, "GfxdHeapThreshold: "
        + "Query Cancellation Thread Started with query cancellation interval "
        + queryCancellationTimeInterval + "ms");
  }

  //handle the event
  public void onEvent(MemoryEvent event) {
    final MemoryState memoryState = event.getState();
    final DistributedMember member = event.getMember();
    
    if (!event.isLocal()) {
      if (memoryState.isCritical()) {
        this.heapCriticalMembers.add(member);
      } else {
        this.heapCriticalMembers.remove(member);
      }
      
      if (memoryState.isEviction()) {
        this.heapEvictionMembers.add(member);
      } else {
        this.heapEvictionMembers.remove(member);
      }
      
      return;
    }

    if (GemFireXDUtils.TraceHeapThresh) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
          "GfxdHeapThreshold: received memory event " + event);
    }

    if (memoryState.isCritical()) {
      queryCanceller.criticalUp();
    } else {
      queryCanceller.criticalDown();
    }
    
    if (memoryState.isEviction()) {
      queryCanceller.evictionUp();
    } else {
      queryCanceller.evictionDown();
    }
    
    if (memoryState.isEvictionDisabled()) {
      queryCanceller.evictionDisabled();
    }
  }

  public final boolean isEvictionUp(final DistributedMember member) {
    return this.heapEvictionMembers.contains(member);
  }

  public final boolean isCriticalUp(final DistributedMember member) {
    return this.heapCriticalMembers.contains(member);
  }

  private final class GfxdQueryCanceller implements Runnable {
    
    private volatile boolean _isStopped = false;
    //memory events
    private volatile boolean _critical = false;

    private volatile boolean _eviction = false;
    
    private boolean _evictionDisabled = false;
    
    //other working variables
    private Activation activationToCancel = null;
   
    
    public void run() {
      try {
         //GfxdHeapThresholdListener#stop will nullify theInstance anytime.
         while( ! _isStopped ) {

               boolean isFirstAfterWait = false; // a flag to detect whether first round after wait. avoids repeated logging.
               synchronized(this) {
                   while (!this._critical && !this._isStopped) {
                     
                       if(SanityManager.DEBUG) {
                         final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
                         if(observer != null) {
                           observer.criticalDownMemoryEvent(GfxdHeapThresholdListener.this);
                         }
                       }
                       if (GemFireXDUtils.TraceHeapThresh) {
                         SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                             "GfxdHeapThreshold: Sleeping as CRITICAL_DOWN event is received");
                       }

                       activationToCancel = null;
                       isFirstAfterWait = true;
                       this.wait();
                   }
               }
               
               if(SanityManager.DEBUG) {
                 final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
                 if(observer != null) {
                   observer.criticalUpMemoryEvent(GfxdHeapThresholdListener.this);
                 }
               }
               
               if(isFirstAfterWait && logger.infoEnabled()) {
                 logger.info(LocalizedStrings.DEBUG, "GfxdHeapThreshold: Processing CRITICAL_UP event");
                 isFirstAfterWait = false;
               }

               cancelTopMemoryConsumingQuery();
         }
      }
      catch (InterruptedException e) {
        if(! _isStopped) {
          if(logger.infoEnabled()) {
            logger.info(LocalizedStrings.DEBUG, "GfxdHeapThreshold: Query Cancellation Thread Interrupted ", e);
          }
        }
      }
      catch(ShutdownException ignore) {
        if(logger.infoEnabled()) {
          logger.info(LocalizedStrings.DEBUG, "GfxdHeapThreshold: Fabric server shutting down. Closing this thread. ");
        }
      }
      finally {
        if(logger.infoEnabled()) {
          logger.info(LocalizedStrings.DEBUG, "GfxdHeapThreshold: Query Cancellation Thread Stopped ");
        }
      }
    }
    
    /**
     * This function uses a brute force memory estimation by iterating 
     * over all the <B>active</B> activation objects in all language 
     * connection contexts. As Function Execution threads are limited
     * this list is not expected to be too long.
     *  
     * @throws InterruptedException
     */
    private void cancelTopMemoryConsumingQuery() throws InterruptedException {

        final long beginCancellation = NanoTimer.getTime();
        
        ContextService singleton = ContextService.getFactory();
        assert singleton != null;
        
        Iterator<ContextManager> contextIter = null;
      
        synchronized(singleton) {
          //any chance while we have been waiting, CRITICAL_DOWN has come ?
          if( !_critical ) {
            return;
          }
          ConcurrentHashSet<ContextManager> hset = singleton.getAllContexts();
          if (GemFireXDUtils.TraceHeapThresh) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                "GfxdHeapThreshold: Acquiring ContextService iterator of size "
                    + hset.size());
          }
          contextIter = hset.iterator();
        }
        
        assert contextIter != null : "GfxdHeapThreshold: Context Service should have been null" ;
        
        long maxEstimatedMemoryUsed = 0L;
        LanguageConnectionContext lcc = null;
      
         while(contextIter.hasNext() && _critical) {
            
             ContextManager cm = contextIter.next();
             
             lcc = (LanguageConnectionContext) cm.getContext(ContextId.LANG_CONNECTION);
             if (lcc == null) {
               if (GemFireXDUtils.TraceHeapThresh) {
                 SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                     "GfxdHeapThreshold: LCC is null");
               }
               continue;
             }
        
             ArrayList<Activation> allActs = lcc.getAllActivations();
             Activation[] acts = allActs.toArray(new Activation[allActs.size()]);
             Activation act = null;
             if (GemFireXDUtils.TraceHeapThresh) {
               SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                   "GfxdHeapThreshold: Got " + acts.length
                   + " Activations for LCC " + lcc);
             }
             for (int index = acts.length - 1; index >= 0; index--) {
                 act = acts[index];

                 if (act == null || act.isClosed() || !act.isInUse()) {
                   if (GemFireXDUtils.TraceHeapThresh) {
                     SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                         "GfxdHeapThreshold: Skipping " + index
                         + " activation for lcc " + lcc);
                   }
                   continue;
                 }
                 
                final ExecPreparedStatement ps = act.getPreparedStatement();
                if (ps == null) {
                  if (GemFireXDUtils.TraceHeapThresh) {
                    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                        "GfxdHeapThreshold: Skipping " + index
                            + " activation as PS is NULL for lcc " + lcc);
                  }
                  continue;
                }

                // fix for #45508 (ignore DROP/DELETE/TRUNCATE).
               if (!isCancellableQuery(act)) continue;

               //if already canceled and criticalDown is yet to receive.
                 if (act.isQueryCancelled()) {
                   if (GemFireXDUtils.TraceHeapThresh) {
                     SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                         "GfxdHeapThreshold: Skipping " + index + " activation "
                             + "as its already cancelled for lcc " + lcc);
                   }
                   continue;
                 }
                 
                 try {
                   long estimatedMemoryUsed = act.estimateMemoryUsage();
                   if(estimatedMemoryUsed > maxEstimatedMemoryUsed) {
                     maxEstimatedMemoryUsed = estimatedMemoryUsed;
                     activationToCancel = act;
                   }
                 }
                 catch (StandardException e) {
                   if (GemFireXDUtils.TraceHeapThresh) {
                     SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                         "GfxdHeapThreshold: Skipping " + index + " activation "
                             + "because of exception " + e + " for lcc " + lcc);
                   }
                   continue; 
                 }
                 catch(Throwable t) {
                   Error err;
                   if (t instanceof Error && SystemFailure.isJVMFailureError(
                       err = (Error)t)) {
                     SystemFailure.initiateFailure(err);
                     // If this ever returns, rethrow the error. We're poisoned
                     // now, so don't let this thread continue.
                     throw err;
                   }
                   SystemFailure.checkFailure();
                   if (logger.warningEnabled()) {
                     logger.warning(LocalizedStrings.DEBUG,
                         "GfxdHeapThreshold: Ignoring " + index + " activation "
                             + "because of runtime exception " + t + " for lcc " + lcc, t);
                   }
                   continue; 
                 }
             } //end of act list
             
         } //end of while

         if(_critical && activationToCancel != null) {
             activationToCancel.cancelOnLowMemory();
             activationToCancel = null;
             stats.collectQueryCancelledStats( (NanoTimer.getTime() - beginCancellation));
             Thread.yield();
         }

         //lets wait for sometime before next round so that criticalDown can arrive meanwhile.
         Thread.sleep(queryCancellationTimeInterval);
    }

    synchronized void stop() {
      _critical = false;
      _isStopped = true;
      this.notify();
      queryCancellerThread.interrupt();
    }
    
    synchronized void criticalUp() {
      _critical = true;
      this.notify();
    }
    
    synchronized void criticalDown() {
      _critical = false;
    }

    synchronized void evictionUp() {
      if (!_eviction) {
        _eviction = true;
      }
    }

    synchronized void evictionDown() {
      if (_eviction) {
        _eviction = false;
      }
    }

    synchronized void evictionDisabled() {
      _evictionDisabled = true;
    }
  }

  /**
   * Checks whether the prepared statement corresponding to the activation
   * can be aborted in low memory condition (CRITICAL UP event).
   * DELETE/DROP/TRUNCATE statements are allowed to be executed in low memory
   * condition.
   * @param act
   * @return
   */
  public static boolean isCancellableQuery(Activation act) {
    ExecPreparedStatement ps = act.getPreparedStatement();
    if (ps == null) {
      return false;
    }
    final ConstantAction ca = ps.getConstantAction();
    if(ca != null) {
      if(!ca.isCancellable()) {
        if (GemFireXDUtils.TraceHeapThresh) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
              "GfxdHeapThresholdListener.isCancellableQuery: Skipping ConstantAction statement " +
                  ps.getUserQueryString(act.getLanguageConnectionContext()) + " for cancellation");
        }
        return false;
      }
    }
    else {
      final int statementType = ps.getStatementType();
      switch (statementType) {
        case StatementType.DELETE:
          if (GemFireXDUtils.TraceHeapThresh) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
                "GfxdHeapThresholdListener.isCancellableQuery: Skipping data reduction statement " +
                    ps.getUserQueryString(act.getLanguageConnectionContext()) + " for cancellation");
          }
          return false;
        default: break;
      }
    }
    return true;
  }

  public static GfxdHeapThresholdListener createInstance(GemFireCacheImpl cache) {
    final GfxdHeapThresholdListener listener = new GfxdHeapThresholdListener(cache);
    cache.getResourceManager().addResourceListener(ResourceType.HEAP_MEMORY, listener);
    if (GemFireXDUtils.TraceHeapThresh) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
          "GfxdHeapThreshold: queryCancellationTimeInterval = "
              + queryCancellationTimeInterval);
    }
    return listener;
  }

  public void stop() {
    this.logger.info(LocalizedStrings.DEBUG, "GfxdHeapThreshold: "
        + "Stopping Query Cancellation Thread");
    this.queryCanceller.stop();
    try {
      this.queryCancellerThread.join();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      Misc.checkIfCacheClosing(ie);
    }
  }

  /**
   * to check whether critical event has arrived.
   * 
   * @return critical boolean state of the singleton listener.
   */
  @Override
  public final boolean isCritical() {
    return queryCanceller._critical;
  }

  /**
   * to check whether eviction event has arrived.
   * 
   * @return eviction boolean state of the singleton listener.
   */
  @Override
  public final boolean isEviction() {
    return queryCanceller._eviction;
  }

  public final boolean isEvictionDisabled() {
    return queryCanceller._evictionDisabled;
  }
}
