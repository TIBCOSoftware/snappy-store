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

import java.lang.ref.WeakReference;

import com.gemstone.gnu.trove.TLinkable;

/**
 * Base class to be used by classes that need to implement some actions in
 * finalize.
 * 
 * This will eventually be used by all server/client classes that need
 * finalizers (instead of the expensive finalize methods) so is in the shared
 * location now part of GemFire.
 * <p>
 * Note that there is a significant performance impact on GC when using
 * {@link Object#finalize()} vs the approach below, so former should be removed
 * from all code and never used in product code. It also leads to significant
 * delays in GC itself leading to memory buildups.
 * <p>
 * Also note that this uses a {@link WeakReference} rather than a
 * <code>PhantomReference</code>. The reason is that PhantomReference is not
 * cleared when it is enqueued (at least in Java 7/8) so the referent remains
 * alive till it is explicitly cleared by the queue poller. This unnecessarily
 * increases the lifetime of the referent and can cause trouble if there is
 * a rapid increase of such objects. Using a {@link WeakReference} avoids this
 * where the referent will be cleared as before it is enqueued and will
 * otherwise behave similar to a <code>PhantomReference</code> assuming the
 * referent is not overriding the finalize method too.
 *
 * @author swale
 */
@SuppressWarnings("serial")
public abstract class FinalizeObject extends WeakReference<Object>
    implements TLinkable {

  // Seperate holders for server and client side so that they do not intefere
  // with one another (e.g. server-side executor trying to cleanup client-side
  // connection waiting on reply from server-itself that can lead to deadlocks)
  private static volatile FinalizeHolder serverHolder = new FinalizeHolder();
  private static volatile FinalizeHolder clientHolder = new FinalizeHolder();

  public static FinalizeHolder getServerHolder() {
    return serverHolder;
  }

  public static FinalizeHolder getClientHolder() {
    return clientHolder;
  }

  public static synchronized void clearFinalizationQueues() {
    serverHolder.clearFinalizeQueue();
    clientHolder.clearFinalizeQueue();
    serverHolder = new FinalizeHolder();
    clientHolder = new FinalizeHolder();
  }

  /**
   * for TLinkable implementation so as to minimize memory footprint when
   * creating serial lists of FinalizeObjects
   */
  private TLinkable next;
  private TLinkable prev;

  private FinalizeObject(final Object referent, final FinalizeHolder holder) {
    super(referent, holder.referenceQueue);
    holder.initializeFinalizer(this);
  }

  protected FinalizeObject(final Object referent, final boolean server) {
    this(referent, server ? serverHolder : clientHolder);
  }

  @Override
  public final void clear() {
    if (super.get() != null) {
      super.clear();
      getHolder().removeFromFinalizerQueue(this);
    }
  }

  final void clearSuper() {
    super.clear();
  }

  public final void clearAll() {
    clear();
    clearThis();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final TLinkable getNext() {
    return this.next;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final TLinkable getPrevious() {
    return this.prev;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setNext(TLinkable linkable) {
    this.next = linkable;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setPrevious(TLinkable linkable) {
    this.prev = linkable;
  }

  public abstract FinalizeHolder getHolder();

  protected abstract void clearThis();

  /**
   * The finalizer method for the object for post GC cleanup.
   * Return false to abort finalization just yet and place in a pending queue
   * to be retried later.
   */
  protected abstract boolean doFinalize() throws Exception;

  /**
   * Simple wrapper class to share state across the base object and
   * {@link FinalizeObject}.
   */
  public static final class State {

    public byte state;

    public State(byte b) {
      this.state = b;
    }
  }

  /**
   * Interface that finalizers (extensions of FinalizeObject) can implement to
   * indicate batching of finalization where possible e.g. for remote messaging
   * which can be expensive one at a time.
   */
  public interface BatchFinalize {
    BatchFinalize merge(BatchFinalize other);
  }
}
