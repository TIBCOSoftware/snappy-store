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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.cache.LockTimeoutException;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.ThrottlingMemLinkedQueueWithDMStats;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
 * {@link ResultCollector} used for query executions in GemFireXD. This allows
 * for streaming of results on the query node by returning a
 * <code>BlockingQueue</code> that will wait for just one result. The iterator
 * returned by this queue is a one time only (i.e. only one iteration allowed
 * which will consume all results) blocking iterator that will wait for all
 * results to arrive. Consequently proper HA functionality cannot be provided
 * when streaming is enabled since a member may go down during the next() call
 * of the iterator. This will then throw a {@link CancelException} which the
 * application will need to handle and re-execute the query if required.
 * 
 * @author swale
 */
public final class GfxdQueryStreamingResultCollector extends
    LinkedBlockingQueue<Object> implements
    GfxdResultCollector<Object> {

  private static final long serialVersionUID = -3092253414028358183L;

  private volatile GemFireException gemfireException;

  /**
   * If a {@link CancelException} is thrown from one of the nodes then this
   * stores the member where the exception originated.
   */
  private volatile DistributedMember cancelledMember;

  private final GfxdResultCollectorHelper helper;

  private transient ReplyProcessor21 processor;

  private volatile boolean endReached;

  private volatile boolean getInvoked;

  private final transient AtomicLong resultDataSize;

  private transient long totalBlockingTime;

  private volatile boolean eofQueued;

  private static final String NAME = GfxdQueryStreamingResultCollector.class
      .getSimpleName();

  private static final Object EOF = new Object() {
    @Override
    public final String toString() {
      return NAME + ".EOF";
    }
  };

  public GfxdQueryStreamingResultCollector() {
    this(new GfxdResultCollectorHelper());
  }

  private GfxdQueryStreamingResultCollector(GfxdResultCollectorHelper helper) {
    this.helper = helper;
    this.resultDataSize = new AtomicLong(0L);
    this.totalBlockingTime = 0L;
  }

  public final void setResultMembers(Set<DistributedMember> members) {
    this.helper.setResultMembers(members);
  }

  public final Set<DistributedMember> getResultMembers() {
    return this.helper.getResultMembers();
  }

  public final boolean setupContainersToClose(
      Collection<GemFireContainer> containers, GemFireTransaction tran)
      throws StandardException {
    return this.helper.setupContainersToClose(this, containers, tran);
  }

  public void setNumRecipients(int n) {
  }

  public GfxdResultCollectorHelper getStreamingHelper() {
    return this.helper;
  }

  // keep a reference of processor to avoid it getting GCed
  public final void setProcessor(ReplyProcessor21 processor) {
    this.processor = processor;
  }

  public final ReplyProcessor21 getProcessor() {
    return this.processor;
  }

  /**
   * Adds a single function execution result from a remote node to the
   * ResultCollector
   * 
   * @param resultOfSingleExecution the result of one execution
   */
  // !!ezoerner added argument member, but not yet used in implementation
  // [sumedh] now using member argument
  public void addResult(DistributedMember member,
      Object resultOfSingleExecution) {
    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER, toString()
          + "#addResult: adding result: " + resultOfSingleExecution
          + " from member: " + member);
    }
    if (resultOfSingleExecution == null) {
      return;
    }
    boolean addMember;
    if (resultOfSingleExecution instanceof Throwable) {
      processException((Throwable)resultOfSingleExecution, member);
      // exceptions can be fatal ones like low memory exception, so never
      // add the member as having sent a valid reply (#44762)
      addMember = false;
    } else if (this.gemfireException != null) {
      if (GemFireXDUtils.TraceFunctionException | GemFireXDUtils.TraceRSIter) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER, this
            + "#addResult: dropping result from member [" + member +
            "] due to a previous failure");
      }
      addMember = true;
    } else if (this.eofQueued) {
      addMember = true;
    } else {
      addMember = true;
      ByteArrayDataInput din = null;
      // apply TX changes for ResultHolder
      if (resultOfSingleExecution instanceof ResultHolder) {
        ResultHolder holder = (ResultHolder)resultOfSingleExecution;
        holder.applyRemoteTXChanges(member);
        din = holder.getByteArrayDataInput();
      } else if (resultOfSingleExecution instanceof SnappyResultHolder) {
        din = ((SnappyResultHolder)resultOfSingleExecution).getByteArrayDataInput();
      }
      offer(resultOfSingleExecution);
      final byte[] bytes;
      if (din != null && (bytes = din.array()) != null) {
        this.resultDataSize.getAndAdd(bytes.length);
      }
      String timeoutReason = null;
      // wait for queue to drain if data size or count of elements is too large
      final long totalMaxWaitMillis = 10L * GfxdLockSet.MAX_LOCKWAIT_VAL;
      long loopStartTime = 0L;
      while (this.resultDataSize.get() > DistributionManager.INCOMING_QUEUE_BYTE_LIMIT ||
          size() > DistributionManager.INCOMING_QUEUE_LIMIT ||
          ThrottlingMemLinkedQueueWithDMStats.isCriticalUp()) {
        long startTime = System.currentTimeMillis();
        if (loopStartTime == 0L) loopStartTime = startTime;
        synchronized (this.resultDataSize) {
          if (this.totalBlockingTime > totalMaxWaitMillis) {
            timeoutReason = "total blocking time exceeded " +
                totalMaxWaitMillis + " millis";
            break;
          }
          try {
            this.resultDataSize.wait(500L);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(ie);
          }
          long endTime = System.currentTimeMillis();
          if (endTime - loopStartTime > GfxdLockSet.MAX_LOCKWAIT_VAL) {
            timeoutReason = "current blocking time exceeded " +
                GfxdLockSet.MAX_LOCKWAIT_VAL + " millis";
            break;
          }
          if (this.eofQueued) {
            timeoutReason = "endResults() invoked";
            break;
          }
          this.totalBlockingTime += endTime - startTime;
        }
      }
      if (timeoutReason != null) {
        processException(new LockTimeoutException(
            "GfxdQueryStreamingResultCollector.addResult: failed after " +
                timeoutReason), member);
      }
    }
    if (addMember) {
      this.helper.addResultMember(member);
    }
  }

  private void notifyWaiters() {
    synchronized (this.resultDataSize) {
      this.resultDataSize.notifyAll();
    }
  }

  /**
   * Waits if necessary for the computation to complete, and then retrieves its
   * result.<br>
   * If {@link Function#hasResult()} is false, upon calling
   * {@link ResultCollector#getResult()} throws {@link FunctionException}.
   * 
   * @return the computed result
   * @throws FunctionException
   *           if something goes wrong while retrieving the result
   */
  public LinkedBlockingQueue<Object> getResult()
      throws FunctionException, ReplyException {
    this.getInvoked = true;
    if (this.processor != null) {
      this.processor.startWait();
    }
    return this;
  }

  /**
   * Call back provided to caller, which is called after function execution is
   * complete and caller can retrieve results using
   * {@link ResultCollector#getResult()}
   */
  public final void endResults() {
    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER, toString()
          + "#endResults: ending results by adding EOF");
    }
    try {
      if (this.processor != null) {
        this.processor.endWait();
      }
    } finally {
      offer(EOF);
      this.helper.closeContainers(this, true);
      this.eofQueued = true;
      notifyWaiters();
    }
  }

  @Override
  public Iterator<Object> iterator() {
    return new Itr();
  }

  /**
   * This iterator actually allows creating multiple iterators unlike the
   * default {@link #iterator()} of this class.
   */
  public Iterator<Object> reusableIterator() {
    return super.iterator();
  }

  /**
   * Waits if necessary for at most the given time for the computation to
   * complete, and then retrieves its result, if available. <br>
   * If {@link Function#hasResult()} is false, upon calling
   * {@link ResultCollector#getResult()} throws {@link FunctionException}.
   * 
   * @param timeout
   *          the maximum time to wait
   * @param unit
   *          the time unit of the timeout argument
   * @return computed result
   * @throws FunctionException
   *           if something goes wrong while retrieving the result
   */
  public final LinkedBlockingQueue<Object> getResult(long timeout,
      TimeUnit unit) throws FunctionException {
    throw new UnsupportedOperationException(
        "getResult with timeout not expected to be invoked in GemFireXD");
  }

  public void setException(Throwable exception) {
    processException(exception, GemFireStore.getMyId());
  }

  private void handleResultHolderRemoval(final Object removed) {
    ByteArrayDataInput din = null;
    if (removed instanceof SnappyResultHolder) {
      din = ((SnappyResultHolder)removed).getByteArrayDataInput();
    } else if (removed instanceof ResultHolder) {
      din = ((ResultHolder)removed).getByteArrayDataInput();
    }
    final byte[] bytes;
    if (din != null && (bytes = din.array()) != null) {
      this.resultDataSize.getAndAdd(-bytes.length);
      notifyWaiters();
    }
  }

  public void clearResults() {
    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER, toString()
          + "#clearResults: clearing results and exceptions");
    }
    this.gemfireException = null;
    this.cancelledMember = null;
    this.helper.clear(this);
    this.processor = null;
    if (this.getInvoked) {
      Object element = null;
      while (!this.endReached) {
        try {
          do {
            element = this.poll(5, TimeUnit.SECONDS);
            if (element == null) {
              Misc.getGemFireCache().getCancelCriterion()
                  .checkCancelInProgress(null);
            }
          } while (element == null);
          if (element == EOF) {
            this.endReached = true;
          } else {
            handleResultHolderRemoval(element);
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(ie);
          break;
        }finally {
          if (!this.endReached && GemFireXDUtils.isOffHeapEnabled()
              && element instanceof ResultHolder) {
            OffHeapReleaseUtil.freeOffHeapReference((ResultHolder) element);
          }
        }
      }
      this.getInvoked = false;
    } else {
      if (GemFireXDUtils.isOffHeapEnabled()) {
        // The local execution result must already be in place.
        // we need to clear that
        Object element;
        try {
          do {
            element = this.poll(5, TimeUnit.SECONDS);
            try {
              if (element == null) {
                Misc.getGemFireCache().getCancelCriterion()
                    .checkCancelInProgress(null);
              }
              if (element == EOF) {
                break;
              } else {
                handleResultHolderRemoval(element);
              }
            } finally {
              if (GemFireXDUtils.isOffHeapEnabled() && element instanceof ResultHolder) {
                OffHeapReleaseUtil.freeOffHeapReference((ResultHolder) element);
              }
            }
          } while (element != null);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(ie);
        }
      }
    }
  }

  @Override
  public String toString() {
    return NAME + '@' + Integer.toHexString(System.identityHashCode(this))
        + "[processor: " + this.processor + ']';
  }

  /**
   * Process a given exception and return true if the exception denotes that the
   * node has gone down.
   */
  private boolean processException(Throwable t,
      DistributedMember member) {
    if (GemFireXDUtils.TraceFunctionException | GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER, toString()
          + "#processException: from member [" + member + "] got exception", t);
    }
    synchronized (this) {
      member = StandardException.fixUpRemoteException(t, member);
      if (t instanceof ReplyException) {
        t = t.getCause();
      }
      if (GemFireXDUtils.retryToBeDone(t)) {
        if (t instanceof GemFireException) {
          this.gemfireException = (GemFireException)t;
        }
        else {
          this.gemfireException = new FunctionException(t);
        }
        this.cancelledMember = member;
        return true;
      }
      else if (this.cancelledMember == null) {
        if (this.gemfireException == null) {
          if (t instanceof FunctionException) {
            this.gemfireException = (FunctionException)t;
            if (t.getCause() != null) {
              t = t.getCause();
            }
          }
          else {
            this.gemfireException = new FunctionException(t);
          }
        }
        ((FunctionException)this.gemfireException).addException(t);
      }
    }
    return false;
  }

  private final class Itr implements Iterator<Object> {

    private transient Object current;

    public Itr() {
      moveNext();
    }

    public boolean hasNext() {
      return (this.current != EOF);
    }

    public Object next() {
      final Object current = this.current;
      if (current == EOF) {
        throw new NoSuchElementException();
      }
      boolean ok = false;
      try {
        moveNext();
        ok = true;
        return current;
      } finally {
        if (!ok && GemFireXDUtils.isOffHeapEnabled()) {
          // release the current result holder
          OffHeapReleaseUtil.freeOffHeapReference(current);
        }
      }
    }

    public void remove() {
      throw new UnsupportedOperationException("not expected to be invoked");
    }

    private void moveNext() {
      try {
        while (true) {
          this.current = GfxdQueryStreamingResultCollector.this.poll(1,
              TimeUnit.SECONDS);
          if (this.current != null) {
            break;
          }
          Misc.getGemFireCache().getCancelCriterion()
              .checkCancelInProgress(null);
        }
        handleResultHolderRemoval(this.current);
        // if we got an exception, consume everything and throw back the
        // cumulative exception
        Throwable cause = gemfireException;
        if (cause == null) {
          if (this.current == EOF) {
            GfxdQueryStreamingResultCollector.this.endReached = true;
          }
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                GfxdQueryStreamingResultCollector.this.toString()
                    + ".Iterator#moveNext: took a new value from queue: "
                    + String.valueOf(this.current));
          }
        }
        else {
          if (GemFireXDUtils.TraceRSIter) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
                GfxdQueryStreamingResultCollector.this.toString()
                    + ".Iterator#moveNext: got exception", cause);
          }
         
          if (GemFireXDUtils.isOffHeapEnabled() && this.current instanceof ResultHolder) {
            OffHeapReleaseUtil.freeOffHeapReference(this.current);
          }
          while (this.current != EOF) {            
            this.current = GfxdQueryStreamingResultCollector.this.poll(1, TimeUnit.SECONDS);
            try {
              if (this.current == null) {
                Misc.getGemFireCache().getCancelCriterion()
                    .checkCancelInProgress(null);
              } else {
                handleResultHolderRemoval(this.current);
              }
            } finally {
              if (GemFireXDUtils.isOffHeapEnabled()
                  && this.current instanceof ResultHolder) {
                OffHeapReleaseUtil.freeOffHeapReference(this.current);
              }
            }            
          }
          synchronized (GfxdQueryStreamingResultCollector.this) {
            GfxdQueryStreamingResultCollector.this.endReached = true;
            // always wrap the exception to get the full stacktrace
            // remove some wrapper function exceptions to avoid long
            // unnecessary stacks
            while (cause instanceof FunctionExecutionException
                || (cause.getClass().equals(FunctionException.class) && cause
                    .getMessage().equals(String.valueOf(cause.getCause())))) {
              cause = cause.getCause();
            }
            final GemFireXDRuntimeException gfxdEx =
              new GemFireXDRuntimeException(cause);
            if (cancelledMember != null) {
              gfxdEx.setOrigin(cancelledMember);
            }
            throw gfxdEx;
          }
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(ie);
      }
    }
  }

  @Override
  public GfxdResultCollector<Object> cloneCollector() {
    return new GfxdQueryStreamingResultCollector();
  }
}
