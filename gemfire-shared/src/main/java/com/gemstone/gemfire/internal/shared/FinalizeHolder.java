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

package com.gemstone.gemfire.internal.shared;

import java.lang.ref.ReferenceQueue;
import java.util.ListIterator;

import com.gemstone.gemfire.internal.shared.FinalizeObject.BatchFinalize;
import com.gemstone.gnu.trove.TLinkedList;

/**
 * Holds reference queue, pending queue etc for {@link FinalizeObject} cleanup.
 *
 * @author swale
 */
public final class FinalizeHolder {

  /**
   * The reference queue where finalizers are pushed in and cleaned up by a
   * cleaner thread later.
   */
  final ReferenceQueue<Object> referenceQueue;

  /**
   * Keeps the reference of the finalizers else they will get GCed. Use of
   * TLinked with TLinkable implementation by FinalizeObject is deliberate to
   * minimize memory overhead which is important especially in low memory
   * conditions when finalizers will be run "forcefully" before each new
   * allocation of FinalizeObject.
   */
  private final TLinkedList finalizerQueue;

  /**
   * This queue holds the items that failed the finalize in first pass so
   * additional passes will be made over them.
   */
  private final TLinkedList pendingQueue;

  /** number of items added in the finalizerQueue */
  private long numAdds;

  /** maximum number of finalizers to clear in foreground threads */
  private static final int MAX_FOREGROUND_FINALIZE = 8;

  FinalizeHolder() {
    this.referenceQueue = new ReferenceQueue<>();
    this.finalizerQueue = new TLinkedList();
    this.pendingQueue = new TLinkedList();
    this.numAdds = 0;
  }

  // TODO: PERF: change to use the CAS techniques of ConcurrentLinkedDeque
  synchronized final long addToFinalizerQueue(FinalizeObject obj) {
    this.finalizerQueue.add(obj);
    return ++this.numAdds;
  }

  // TODO: PERF: change to use the CAS techniques of ConcurrentLinkedDeque
  synchronized final void removeFromFinalizerQueue(FinalizeObject obj) {
    this.finalizerQueue.remove(obj);
  }

  synchronized final void clearFinalizeQueue() {
    this.finalizerQueue.clear();
  }

  public final void addToPendingQueue(FinalizeObject obj) {
    if (emptyReferenceQueue(this.pendingQueue, obj, MAX_FOREGROUND_FINALIZE) >=
        MAX_FOREGROUND_FINALIZE) {
      invokePendingFinalizers(MAX_FOREGROUND_FINALIZE);
    }
  }

  synchronized final int addToQueue(Object obj, TLinkedList list) {
    list.add(obj);
    return list.size();
  }

  synchronized final Object pollQueue(final TLinkedList list) {
    return list.size() > 0 ? list.removeFirst() : null;
  }

  private synchronized int emptyReferenceQueue(final TLinkedList pendingQ,
      final FinalizeObject newObj, int maxPoll) {
    assert Thread.holdsLock(this);

    final TLinkedList finalizerQ = this.finalizerQueue;
    Object o;
    while (maxPoll-- > 0 && (o = this.referenceQueue.poll()) != null) {
      ((FinalizeObject)o).clearSuper();
      finalizerQ.remove(o);
      pendingQ.add(o);
    }
    if (newObj != null) {
      pendingQ.add(newObj);
    }
    return pendingQ.size();
  }

  private static void addToBatch(Object o, TLinkedList finalizeList,
      TLinkedList batchFinalizeList) {
    if (o instanceof BatchFinalize) {
      int bsize = batchFinalizeList.size();
      if (bsize == 1) {
        BatchFinalize batch = (BatchFinalize)batchFinalizeList.getFirst();
        BatchFinalize merged = batch.merge((BatchFinalize)o);
        if (merged != null) {
          if (merged != batch) { // replace
            batchFinalizeList.removeFirst();
            batchFinalizeList.addFirst(merged);
          }
        }
        else {
          batchFinalizeList.add(o);
        }
      }
      else if (bsize == 0) {
        batchFinalizeList.add(o);
      }
      else {
        BatchFinalize bobj = (BatchFinalize)o;
        // search for the item with which it can be merged
        @SuppressWarnings("unchecked")
        ListIterator<Object> biter = batchFinalizeList.listIterator(0);
        boolean added = false;
        while (biter.hasNext()) {
          BatchFinalize batch = (BatchFinalize)biter.next();
          BatchFinalize merged = batch.merge(bobj);
          if (merged != null) {
            if (merged != batch) { // replace
              biter.remove();
              biter.add(merged);
            }
            added = true;
            break;
          }
        }
        if (!added) {
          batchFinalizeList.add(o);
        }
      }
    }
    else {
      finalizeList.add(o);
    }
  }

  void doFinalize(FinalizeObject obj, TLinkedList pendingQ) {
    try {
      if (obj.doFinalize()) {
        obj.clearThis();
      }
      else {
        addToQueue(obj, pendingQ);
      }
    } catch (VirtualMachineError err) {
      throw err;
    } catch (Throwable t) {
      // since the exception is for a previous object, just log the
      // exception and move on
      ClientSharedUtils.getLogger(getClass()).warn(
          "FinalizeHolder: unexpected exception while invoking finalizer " + t, t);
      // clear it neverthless
      obj.clearThis();
    }
  }

  /**
   * Invokes {@link FinalizeObject#doFinalize()} methods for any
   * {@link FinalizeObject}s queued by GC after the contained objects are GCed.
   */
  public final void invokePendingFinalizers() {
    invokePendingFinalizers(1000);
  }

  /**
   * Invokes {@link FinalizeObject#doFinalize()} methods for any
   * {@link FinalizeObject}s queued by GC after the contained objects are GCed.
   */
  public final void invokePendingFinalizers(int maxPoll) {
    Object o;
    final TLinkedList pendingQ = this.pendingQueue;
    // keep clearing from queues and filling into local array for batch
    // finalization if possible
    TLinkedList finalizeList = new TLinkedList();
    TLinkedList batchFinalizeList = new TLinkedList();
    // clear from reference queue and local queue upfront;
    // maxPoll argument is to protect for the rare case where reference queue
    // is growing as fast as this method empties it
    emptyReferenceQueue(pendingQ, null, maxPoll);
    while (maxPoll-- > 0 && (o = pollQueue(pendingQ)) != null) {
      addToBatch(o, finalizeList, batchFinalizeList);
    }
    // now finalize all collected so far
    if (batchFinalizeList.size() > 0) {
      for (Object obj : batchFinalizeList) {
        doFinalize((FinalizeObject)obj, pendingQ);
      }
    }
    if (finalizeList.size() > 0) {
      for (Object obj : finalizeList) {
        doFinalize((FinalizeObject)obj, pendingQ);
      }
    }
  }

  final void initializeFinalizer(FinalizeObject obj) {
    final long n = addToFinalizerQueue(obj);
    if ((n % 4) == 0) {
      // empty the reference queue and check size of resulting pending queue
      if (emptyReferenceQueue(pendingQueue, null, MAX_FOREGROUND_FINALIZE) >=
          MAX_FOREGROUND_FINALIZE) {
        invokePendingFinalizers(MAX_FOREGROUND_FINALIZE);
      }
    }
  }
}
