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

package com.gemstone.gemfire.internal.concurrent.unsafe;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import sun.misc.Unsafe;

/**
 * Optimized implementation of {@link AtomicLongFieldUpdater} using the internal
 * Unsafe class if possible avoiding the various class checks.
 *
 * @author swale
 * @since gfxd 1.0
 */
public final class UnsafeAtomicLongFieldUpdater<T> extends
    AtomicLongFieldUpdater<T> {

  static final Unsafe unsafe = UnsafeHolder.getUnsafe();

  private final long offset;

  public UnsafeAtomicLongFieldUpdater(Class<T> tclass, String fieldName) {
    this.offset = UnsafeAtomicReferenceUpdater.getFieldOffet(tclass,
        long.class, fieldName);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean compareAndSet(T obj, long expect, long update) {
    return unsafe.compareAndSwapLong(obj, offset, expect, update);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean weakCompareAndSet(T obj, long expect, long update) {
    // same as strong version
    return unsafe.compareAndSwapLong(obj, offset, expect, update);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void set(T obj, long newValue) {
    unsafe.putLongVolatile(obj, offset, newValue);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void lazySet(T obj, long newValue) {
    unsafe.putOrderedLong(obj, offset, newValue);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long get(T obj) {
    return unsafe.getLongVolatile(obj, offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final long getAndSet(T obj, long newValue) {
    return unsafe.getAndSetLong(obj, offset, newValue);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final long getAndAdd(T obj, final long delta) {
    return unsafe.getAndAddLong(obj, offset, delta);
  }

  @Override
  public long addAndGet(T obj, long delta) {
    return getAndAdd(obj, delta) + delta;
  }

  @Override
  public long getAndIncrement(T obj) {
    return getAndAdd(obj, 1);
  }

  @Override
  public long getAndDecrement(T obj) {
    return getAndAdd(obj, -1);
  }

  @Override
  public long incrementAndGet(T obj) {
    return getAndAdd(obj, 1) + 1;
  }

  @Override
  public long decrementAndGet(T obj) {
    return getAndAdd(obj, -1) - 1;
  }
}
