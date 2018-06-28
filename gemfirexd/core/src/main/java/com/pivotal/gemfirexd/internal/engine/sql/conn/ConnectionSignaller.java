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
import java.util.Collection;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * This singleton class encapsulates sending of batch messages to other members
 * of the distributed system when new connections are opened or closed. It
 * provides for a thread that will send out a periodic messages when a specified
 * "batch-size" of changes to connections have been done, or given maximum wait
 * time has elapsed.
 * 
 * Currently used for sending messages to <code>EmbedConnection</code> close to
 * other members and for sending JDBC client connection changes to
 * <code>ServerLocator</code>s for load balancing of clients.
 * 
 * @author swale
 */
public final class ConnectionSignaller extends Thread {

  /**
   * Static singleton instance of the signaller thread object.
   */
  private static volatile ConnectionSignaller instance =
    new ConnectionSignaller();

  /**
   * Used to lock the static instance and the connection list in the instance.
   */
  private static final Object instanceLock = new Object();

  /**
   * Maximum time (in millis) to wait for clean stop before forceful interrupt
   * of this thread.
   */
  private static final int MAX_JOIN_WAIT = 1000;

  /** the set of connection states to be processed */
  private final SortedSet<ConnectionStateKey> connectionList;

  /** flag to stop the thread when set to false */
  private volatile boolean shouldContinue;

  /**
   * Default constructor for this singleton class. Private -- use
   * {@link #getInstance()} to get the singleton instance.
   */
  private ConnectionSignaller() {
    super(LogWriterImpl.createThreadGroup(
        LocalizedStrings.CONNECTION_DISTRIBUTOR_THREAD.toLocalizedString(),
        (LogWriterI18n)null), LocalizedStrings.CONNECTION_DISTRIBUTOR_THREAD
        .toLocalizedString());
    this.shouldContinue = true;
    this.connectionList = new TreeSet<ConnectionStateKey>();
    this.setDaemon(true);
    this.start();
  }

  /**
   * Get the instance of this singleton class creating it in the first call in a
   * thread-safe manner.
   */
  public static ConnectionSignaller getInstance() {
    final ConnectionSignaller inst = instance;
    if (inst != null) {
      return inst;
    }
    synchronized (instanceLock) {
      if (instance == null) {
        instance = new ConnectionSignaller();
      }
      return instance;
    }
  }

  /**
   * see {@link Thread#run()}
   */
  @Override
  public void run() {
    if (GemFireXDUtils.TraceConnectionSignaller) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
          "thread starting.");
    }
    final ArrayList<ConnectionState> changeList =
        new ArrayList<ConnectionState>();
    int numProcessNows = 0;
    while (this.shouldContinue) {
      try {
        synchronized (instanceLock) {

          if (this.shouldContinue) {
            while (this.connectionList.isEmpty() && this.shouldContinue) {
              if (GemFireXDUtils.TraceConnectionSignaller) {
                SanityManager.DEBUG_PRINT(
                    GfxdConstants.TRACE_CONNECTION_SIGNALLER,
                    "waiting for a new item in the queue");
              }
              instanceLock.wait(MAX_JOIN_WAIT);
            }
            Iterator<ConnectionStateKey> iter = this.connectionList.iterator();
            // first take out any connection states to be processed immediately
            fillInConnectionsToProcess(iter, changeList);
            // if nothing to be done immediately, start wait for others;
            // also avoid starvation by forcing wait after couple of loops
            // of immediate processings
            if (changeList.size() == 0 || numProcessNows >= 2) {
              iter = this.connectionList.iterator();
              ConnectionStateKey conn;
              long waitMillis;
              while (iter.hasNext()) {
                conn = iter.next();
                waitMillis = conn.endWaitTime() - System.currentTimeMillis();
                final boolean processNow;
                if (waitMillis > 0) {
                  // The batch size is not yet complete and time limit not
                  // exhausted, wait for some time to fill up.
                  if (!this.shouldContinue) {
                    break;
                  }
                  if (GemFireXDUtils.TraceConnectionSignaller) {
                    SanityManager.DEBUG_PRINT(
                        GfxdConstants.TRACE_CONNECTION_SIGNALLER,
                        "waiting for a new item in the queue for " + waitMillis
                            + "ms");
                  }
                  instanceLock.wait(waitMillis);
                  // re-initialize the iterator since the list is likely to have
                  // changed above after the wait
                  iter = this.connectionList.iterator();
                  // reset processNow count since we have waited once
                  numProcessNows = 0;
                  // sure to have at least one element
                  assert iter.hasNext();
                  // reinitialize the connection to the front of the list since
                  // the list may have changed
                  conn = iter.next();
                  processNow = conn.processNow();
                }
                else {
                  processNow = true;
                }
                if (processNow) {
                  // remove this item from the list
                  iter.remove();
                  changeList.add(conn.connectionState());
                  // also add other connections to be processed immediately
                  fillInConnectionsToProcess(iter, changeList);
                  // break the loop to process the connections in changeList
                  // however, check for possible starvation of waiters
                  if (numProcessNows < 4) {
                    break;
                  }
                }
              }
              // reset processNow count if there are no remaining waiters
              if (numProcessNows > 0 && this.connectionList.size() == 0) {
                numProcessNows = 0;
              }
            }
          }
        }
        if (changeList.size() > 0) {
          ++numProcessNows;
          for (ConnectionState connState : changeList) {
            if (!this.shouldContinue) {
              break;
            }
            if (GemFireXDUtils.TraceConnectionSignaller) {
              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_CONNECTION_SIGNALLER,
                  "distributing changes for item: " + connState.toString());
            }
            // Distribute the connection state change to required nodes.
            connState.distribute();
          }
          changeList.clear();
        }

        // This clears the pending references of the offheap.
        // Kept this call here to avoid creating a new thread.
        UnsafeHolder.releasePendingReferences();

        // invoke the finalizer reference queue
        FinalizeObject.getServerHolder().invokePendingFinalizers();
      } catch (InterruptedException ie) {
        SystemFailure.checkFailure();
        // Some interruption other than shutdown has caused it so exit.
        this.shouldContinue = false;
        break;
      } catch (CancelException e) {
        // Shutdown started so exit.
        this.shouldContinue = false;
        break;
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        SystemFailure.checkFailure();
        try {
          final LogWriterI18n logger = Misc.getI18NLogWriter();
          logger.warning(LocalizedStrings.CONNECTION_CHANGE_PROCESS_FAILED, t);
        } catch (CancelException ce) {
          // Shutdown started so exit.
          this.shouldContinue = false;
          break;
        }
      }
    }
    if (GemFireXDUtils.TraceConnectionSignaller) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
          "thread ending.");
    }
  }

  /**
   * Add a new {@link ConnectionState} to be processed by this thread object.
   * This method also aggregates the given state in the list invoking
   * {@link ConnectionState#accumulate(ConnectionState)} and also orders the
   * list appropriately to place the {@link ConnectionState}s to be processed
   * first at the start.
   */
  public void add(ConnectionState newState) {
    assert newState != null;

    if (GemFireXDUtils.TraceConnectionSignaller) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
          "adding new item to queue: " + newState);
    }
    ConnectionStateKey connState, newConnState = null;
    synchronized (instanceLock) {
      final Iterator<ConnectionStateKey> iter = this.connectionList.iterator();
      while (iter.hasNext()) {
        connState = iter.next();
        if (GemFireXDUtils.TraceConnectionSignaller) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
              "trying to accumulate [" + newState.toString() + "] into: "
                  + connState.toString());
        }
        if (connState.connectionState().accumulate(newState)) {
          if (GemFireXDUtils.TraceConnectionSignaller) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
                "accumulated [" + newState.toString() + "] to obtain: "
                    + connState.toString());
          }
          // sort order may have changed, so remove and add again
          iter.remove();
          newConnState = connState;
          break;
        }
      }
      if (newConnState == null) {
        newConnState = new ConnectionStateKey(newState);
      }
      if (GemFireXDUtils.TraceConnectionSignaller) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
            "after accumulation adding new item to queue: "
                + newConnState.toString());
      }
      this.connectionList.add(newConnState);
      instanceLock.notify();
    }
  }

  /**
   * Add a new {@link ConnectionState} to be processed by this thread object
   * synchronously. This method first aggregates the given state in the list
   * invoking {@link ConnectionState#accumulate(ConnectionState)} and then
   * invokes {@link ConnectionState#distribute()} for either the accumulated
   * item, if any, or the current item being inserted.
   */
  public void addSynchronous(ConnectionState newState) {
    assert newState != null;

    if (GemFireXDUtils.TraceConnectionSignaller) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
          "[sync] adding new item to queue synchronously: " + newState);
    }
    ConnectionStateKey connState;
    synchronized (instanceLock) {
      final Iterator<ConnectionStateKey> iter = this.connectionList.iterator();
      while (iter.hasNext()) {
        connState = iter.next();
        if (GemFireXDUtils.TraceConnectionSignaller) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
              "[sync] trying to accumulate [" + newState.toString()
                  + "] into: " + connState.toString());
        }
        if (connState.connectionState().accumulate(newState)) {
          if (GemFireXDUtils.TraceConnectionSignaller) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
                "[sync] accumulated [" + newState.toString() + "] to obtain: "
                    + connState.toString());
          }
          // will be invoked synchronously so remove from the list
          iter.remove();
          newState = connState.connectionState();
          break;
        }
      }
    }
    if (GemFireXDUtils.TraceConnectionSignaller) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
          "[sync] distributing changes for item: " + newState.toString());
    }
    newState.distribute();
  }

  /**
   * Stop the thread started by this {@link ConnectionSignaller} forcing it to
   * quit if necessary.
   * 
   * There may be cases of improper behaviour if this stop method is invoked
   * concurrently with start ({@link #getInstance()}), but such a situation
   * cannot arise since the start/stop of the store layer is handled properly in
   * a single-threaded manner at the derby layer.
   */
  public static ConnectionSignaller signalStop() {
    final ConnectionSignaller signaller = instance;
    if (signaller == null) {
      return null;
    }
    try {
      if (signaller.isAlive()) {
        // try to stop the thread cleanly
        synchronized (instanceLock) {
          signaller.shouldContinue = false;
          instanceLock.notifyAll();
        }
        // wait for sometime for the thread to stop
        signaller.join(MAX_JOIN_WAIT / 2);
        // still alive: interrupt forcefully
        if (signaller.isAlive()) {
          signaller.interrupt();
          signaller.join(MAX_JOIN_WAIT / 2);
        }
        if (GemFireXDUtils.TraceConnectionSignaller) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
              (signaller.isAlive() ? "giving up waiting to stop"
                  : "successfully stopped")
                  + " the periodic connection signaller thread");
        }
      }
    } catch (InterruptedException ie) {
      // do not wait for the thread to stop; just exit
      Thread.currentThread().interrupt();
    } finally {
      // before going away interrupt the signaller thread if still alive
      if (signaller.isAlive()) {
        signaller.interrupt();
      }
      // remove the static instance
      instance = null;
    }
    return signaller;
  }

  /**
   * Fill in the connection states to be processed immediately in the given
   * collection iterating over the source using the given iterator and removing
   * the affected items from the source.
   */
  private void fillInConnectionsToProcess(Iterator<ConnectionStateKey> iter,
      Collection<ConnectionState> changeList) {
    ConnectionStateKey conn;
    while (iter.hasNext()) {
      conn = iter.next();
      if (GemFireXDUtils.TraceConnectionSignaller) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
            "peeking new item: " + conn.toString());
      }
      // If the batch size is not yet complete, retain in the list so
      // as to wait for some time to fill up later.
      if (conn.processNow()) {
        if (GemFireXDUtils.TraceConnectionSignaller) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONNECTION_SIGNALLER,
              "adding new item for processing: " + conn.toString());
        }
        changeList.add(conn.connectionState());
        iter.remove();
      }
    }
  }
}
