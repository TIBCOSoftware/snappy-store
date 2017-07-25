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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;

import sun.misc.Unsafe;

/**
 * Optimized implementation of {@link AtomicReferenceFieldUpdater} using the
 * internal Unsafe class if possible avoiding the various class checks.
 * 
 * @author swale
 * @since gfxd 1.0
 */
public final class UnsafeAtomicReferenceUpdater<T, V> extends
    AtomicReferenceFieldUpdater<T, V> {

  static final Unsafe unsafe = UnsafeHolder.getUnsafe();

  private final long offset;

  public UnsafeAtomicReferenceUpdater(Class<T> tclass, Class<V> vclass,
      String fieldName) {
    this.offset = getFieldOffet(tclass, vclass, fieldName);
  }

  public static <T, V> long getFieldOffet(Class<T> tclass,
      Class<V> expectedFieldClass, String fieldName) {
    Field field = null;
    Class<?> fieldClass = null;
    int modifiers = 0;
    try {
      field = tclass.getDeclaredField(fieldName);
      modifiers = field.getModifiers();
      fieldClass = field.getType();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }

    if (expectedFieldClass != fieldClass) {
      throw new ClassCastException("expectedFieldClass=" + expectedFieldClass
          + ", actual fieldClass=" + fieldClass);
    }
    if (!Modifier.isVolatile(modifiers)) {
      throw new IllegalArgumentException("Field '" + fieldName + "' in '"
          + tclass.getName() + "' must be volatile");
    }

    return unsafe.objectFieldOffset(field);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean compareAndSet(T obj, V expect, V update) {
    return unsafe.compareAndSwapObject(obj, offset, expect, update);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean weakCompareAndSet(T obj, V expect, V update) {
    // same as strong one
    return unsafe.compareAndSwapObject(obj, offset, expect, update);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void set(T obj, V newValue) {
    unsafe.putObjectVolatile(obj, offset, newValue);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void lazySet(T obj, V newValue) {
    unsafe.putOrderedObject(obj, offset, newValue);
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public V get(T obj) {
    return (V)unsafe.getObjectVolatile(obj, offset);
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public final V getAndSet(T obj, V newValue) {
    return (V)unsafe.getAndSetObject(obj, offset, newValue);
  }
}
