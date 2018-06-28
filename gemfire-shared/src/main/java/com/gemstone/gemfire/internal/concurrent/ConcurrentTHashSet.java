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

package com.gemstone.gemfire.internal.concurrent;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gnu.trove.HashingStats;
import com.gemstone.gnu.trove.PrimeFinder;
import com.gemstone.gnu.trove.THash;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TIntArrayList;
import com.gemstone.gnu.trove.TObjectHashingStrategy;
import com.gemstone.gnu.trove.TObjectProcedure;

/**
 * A concurrent version of Trove's {@link THashSet}.
 * 
 * @author swale
 * @since gfxd 1.0
 * 
 * @param <T>
 *          the type of elements maintained by this set
 */
@SuppressWarnings("serial")
public class ConcurrentTHashSet<T> extends THashParameters implements Set<T> {

  public static final int DEFAULT_CONCURRENCY = 16;

  protected final ConcurrentTHashSegment<T>[] segments;
  protected final int numSegments;
  private final AtomicLong totalSize;

  public ConcurrentTHashSet() {
    this(DEFAULT_CONCURRENCY, THash.DEFAULT_INITIAL_CAPACITY,
        THash.DEFAULT_LOAD_FACTOR, null, null);
  }

  public ConcurrentTHashSet(TObjectHashingStrategy strategy) {
    this(DEFAULT_CONCURRENCY, THash.DEFAULT_INITIAL_CAPACITY,
        THash.DEFAULT_LOAD_FACTOR, strategy, null);
  }

  public ConcurrentTHashSet(int concurrency) {
    this(concurrency, THash.DEFAULT_INITIAL_CAPACITY,
        THash.DEFAULT_LOAD_FACTOR, null, null);
  }

  public ConcurrentTHashSet(int concurrency, int initialCapacity) {
    this(concurrency, initialCapacity, THash.DEFAULT_LOAD_FACTOR, null, null);
  }

  public ConcurrentTHashSet(TObjectHashingStrategy strategy,
      HashingStats stats) {
    this(DEFAULT_CONCURRENCY, THash.DEFAULT_INITIAL_CAPACITY,
        THash.DEFAULT_LOAD_FACTOR, strategy, stats);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentTHashSet(int concurrency, int initialCapacity,
      float loadFactor, TObjectHashingStrategy strategy, HashingStats stats) {
    super(loadFactor, strategy, stats);

    if (concurrency <= 0 || !(loadFactor > 0) || initialCapacity < 0) {
      throw new IllegalArgumentException();
    }

    if (concurrency > 1) {
      concurrency = PrimeFinder.nextPrime(concurrency);
    }
    int segSize = initialCapacity / concurrency;
    if (segSize < 2) {
      segSize = 2;
    }
    this.totalSize = new AtomicLong(0);
    this.segments = new ConcurrentTHashSegment[concurrency];
    this.numSegments = concurrency;
    for (int index = 0; index < concurrency; index++) {
      this.segments[index] = new ConcurrentTHashSegment<>(segSize, this,
          this.totalSize);
    }
  }

  public final long longSize() {
    return this.totalSize.get();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int size() {
    final long size = this.totalSize.get();
    return size < Integer.MAX_VALUE ? (int)size : Integer.MAX_VALUE;
  }

  public final long capacity() {
    long capacity = 0;
    acquireAllLocks(false);
    try {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        capacity += seg.capacity();
      }
    } finally {
      releaseAllLocks(false);
    }
    return capacity;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isEmpty() {
    return this.totalSize.get() == 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean contains(Object o) {
    if (o != null) {
      final int hash = computeHashCode(o, this.hashingStrategy);
      return segmentFor(hash).contains(o, hash);
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * Get the value in the set lookup up against given object, or null if not
   * present. This is different from {@link #contains(Object)} in that depending
   * on {@link TObjectHashingStrategy} the lookup object may not be identical to
   * the object in set.
   */
  @SuppressWarnings("unchecked")
  public final T get(Object o) {
    if (o != null) {
      final int hash = computeHashCode(o, this.hashingStrategy);
      return (T)segmentFor(hash).getKey(o, hash);
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * Like {@link #get(Object)} but skips acquiring any locks.No product code
   * should use this except for monitoring or other such purposes which should
   * not wait for locks and is not affected much by inaccurate results.
   */
  @SuppressWarnings("unchecked")
  public final T getUnsafe(Object o) {
    if (o != null) {
      final int hash = computeHashCode(o, this.hashingStrategy);
      Object result = segmentFor(hash).getKeyNoLock(o, hash);
      // result of this may not be as expected
      if (result != null && result != ConcurrentTHashSegment.REMOVED
          && this.hashingStrategy.equals(result, o)) {
        return (T)result;
      }
      else {
        return null;
      }
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean add(T e) {
    if (e != null) {
      final int hash = computeHashCode(e, this.hashingStrategy);
      return segmentFor(hash).add(e, hash) == null;
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * Like {@link #add(Object)} but returns the current key if already present in
   * set (instead of false) else null (instead of true).
   */
  public final Object addKey(T e) {
    if (e != null) {
      final int hash = computeHashCode(e, this.hashingStrategy);
      return segmentFor(hash).add(e, hash);
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * Like {@link #addKey(Object)} but replaces the current key if present.
   */
  public final Object put(T e) {
    if (e != null) {
      final int hash = computeHashCode(e, this.hashingStrategy);
      return segmentFor(hash).put(e, hash);
    } else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * Like {@link #add} but creates the value only if none present rather than
   * requiring a passed in pre-created object that may ultimately be thrown
   * away.
   * 
   * @param key
   *          key which is to be looked up in the set
   * @param valueCreator
   *          factory object to create the value to be inserted into the set, if
   *          required
   * @param context
   *          the context in which this method has been invoked and passed to
   *          <code>valueCreator</code> {@link MapCallback#newValue} method to
   *          create the new instance
   * @param createParams
   *          parameters to be passed to the <code>valueCreator</code>
   *          {@link MapCallback#newValue} method to create the new instance
   * 
   * @return the previous value present in the set for the specified key, or the
   *         new value obtained by invoking {@link MapCallback#newValue} if
   *         there was no mapping for the key, or null if
   *         {@link MapCallback#newValue} invoked
   *         {@link ConcurrentTHashSegment#setNewValueCreated} to false from
   *         within its body
   * 
   * @throws NullPointerException
   *           if the specified key or value is null
   */
  @SuppressWarnings("unchecked")
  public final <K, C, P> T create(final K key,
      final MapCallback<K, T, C, P> valueCreator, final C context,
      final P createParams) {
    if (key != null) {
      final int hash = computeHashCode(key, this.hashingStrategy);
      return (T)segmentFor(hash).create(key, valueCreator, context,
          createParams, hash);
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean remove(Object o) {
    if (o != null) {
      final int hash = computeHashCode(o, this.hashingStrategy);
      return segmentFor(hash).remove(o, hash) != null;
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * Like {@link #remove(Object)} but returns the current key if present in set
   * and removed (instead of true) else null (instead of false).
   */
  @SuppressWarnings("unchecked")
  public final T removeKey(Object o) {
    if (o != null) {
      final int hash = computeHashCode(o, this.hashingStrategy);
      return (T)segmentFor(hash).remove(o, hash);
    }
    else {
      throw new NullPointerException("null element");
    }
  }

  /**
   * Replace an old value with new value.
   * 
   * @param o
   *          the old value to be replaced
   * @param e
   *          the new value to be inserted
   * 
   * @return true if replace was successful, and false if old value was not
   *         found
   */
  public final boolean replace(Object o, T e) {
    if (o != null && e != null) {
      final int hashOld = computeHashCode(o, this.hashingStrategy);
      final int hashNew = computeHashCode(e, this.hashingStrategy);
      final int segOldIndex = segmentIndex(hashOld);
      final int segNewIndex = segmentIndex(hashNew);
      final boolean result;
      if (segOldIndex != segNewIndex) {
        final ConcurrentTHashSegment<T> segOld = this.segments[segOldIndex];
        final ConcurrentTHashSegment<T> segNew = this.segments[segNewIndex];
        // lock in the order of segment index to avoid lock reversal deadlocks
        final ConcurrentTHashSegment<T> segLock1, segLock2;
        if (segOldIndex < segNewIndex) {
          segLock1 = segOld;
          segLock2 = segNew;
        }
        else {
          segLock1 = segNew;
          segLock2 = segOld;
        }
        segLock1.writeLock().lock();
        try {
          segLock2.writeLock().lock();
          try {
            if ((result = (segOld.removeP(o, hashOld)) != null)) {
              segNew.addP(e, hashNew);
            }
          } finally {
            segLock2.writeLock().unlock();
          }
        } finally {
          segLock1.writeLock().unlock();
        }
      }
      else {
        final ConcurrentTHashSegment<T> seg = this.segments[segOldIndex];
        seg.writeLock().lock();
        try {
          if ((result = (seg.removeP(o, hashOld)) != null)) {
            seg.addP(e, hashNew);
          }
        } finally {
          seg.writeLock().unlock();
        }
      }
      return result;
    }
    else {
      throw new NullPointerException(o == null ? "null old value"
          : "null new value");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean containsAll(Collection<?> c) {
    for (Object o : c) {
      if (!contains(o)) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean addAll(Collection<? extends T> c) {
    boolean result = false;
    for (T e : c) {
      result |= add(e);
    }
    return result;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean retainAll(Collection<?> c) {
    int hash, segIndex;
    boolean result = false;
    final int nsegs = this.numSegments;
    // split by segment
    @SuppressWarnings("unchecked")
    final ArrayList<Object>[] cs = new ArrayList[nsegs];
    for (Object o : c) {
      if (o != null) {
        hash = computeHashCode(o, this.hashingStrategy);
        segIndex = hash % nsegs;
        if (cs[segIndex] == null) {
          cs[segIndex] = new ArrayList<Object>();
        }
        cs[segIndex].add(o);
      } else {
        throw new NullPointerException("null element");
      }
    }
    for (segIndex = 0; segIndex < nsegs; segIndex++) {
      ArrayList<Object> a = cs[segIndex];
      ConcurrentTHashSegment<T> seg = this.segments[segIndex];
      if (a != null) {
        result |= seg.retainAll(a);
      } else {
        result |= seg.clear();
      }
    }
    return result;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean removeAll(Collection<?> c) {
    boolean result = false;
    for (Object o : c) {
      result |= remove(o);
    }
    return result;
  }

  /**
   * Like {@link #removeAll(Collection)} but optimized for large size of
   * collection to minimize lock acquire/release and duration of locking.
   * 
   * Usually you will want to use this when size of collection is greater than
   * the concurreny level of the map.
   */
  public final boolean bulkRemoveAll(Collection<?> c) {
    if (c != null) {
      boolean result = false;
      // segregate by segment
      final int nsegs = this.numSegments;
      final int arrInitSize = (c.size() / nsegs) + 1;
      @SuppressWarnings("unchecked")
      final ArrayList<Object>[] objectsBySeg = new ArrayList[nsegs];
      final TIntArrayList[] hashesBySeg = new TIntArrayList[nsegs];
      for (Object o : c) {
        if (o != null) {
          final int hash = computeHashCode(o, this.hashingStrategy);
          final int segIndex = segmentIndex(hash);
          TIntArrayList segHashes = hashesBySeg[segIndex];
          if (segHashes == null) {
            hashesBySeg[segIndex] = segHashes = new TIntArrayList(arrInitSize);
            objectsBySeg[segIndex] = new ArrayList<Object>(arrInitSize);
          }
          segHashes.add(hash);
          objectsBySeg[segIndex].add(o);
        }
        else {
          throw new NullPointerException("null element");
        }
      }
      // now act by segment acquiring lock only once per segment
      for (int segIndex = 0; segIndex < nsegs; segIndex++) {
        final TIntArrayList segHashes = hashesBySeg[segIndex];
        if (segHashes != null) {
          final ConcurrentTHashSegment<T> seg = this.segments[segIndex];
          final Iterator<Object> segObjectsItr = objectsBySeg[segIndex]
              .iterator();
          final int size = segHashes.size();
          seg.writeLock().lock();
          try {
            for (int index = 0; index < size; index++) {
              result |= (seg.removeP(segObjectsItr.next(),
                  segHashes.getQuick(index)) != null);
            }
          } finally {
            seg.writeLock().unlock();
          }
        }
      }
      return result;
    }
    else {
      throw new NullPointerException("null collection");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clear() {
    for (ConcurrentTHashSegment<T> seg : this.segments) {
      seg.clear();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Itr iterator() {
    return new Itr();
  }

  public void lockAllSegmentsForRead() {
    acquireAllLocks(false);
  }

  public void unlockAllSegmentsAfterRead() {
    releaseAllLocks(false);
  }

  /**
   * Apply the given procedure for each element of the set. Do not invoke any
   * operation on this set directly from inside the body of procedure itself
   * else it will result in a deadlock.
   */
  public final boolean forEach(TObjectProcedure proc) {
    acquireAllLocks(false);
    try {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        for (Object o : seg.set) {
          if (o != null && o != ConcurrentTHashSegment.REMOVED) {
            if (!proc.execute(o)) {
              return false;
            }
          }
        }
      }
    } finally {
      releaseAllLocks(false);
    }
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object[] toArray() {
    int size = 0;
    int offset = 0;
    Object[] result;
    acquireAllLocks(false);
    try {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        size += seg.size;
      }
      result = new Object[size];
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        offset = seg.toArray(result, seg.set, offset);
      }
    } finally {
      releaseAllLocks(false);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public <E, C> E[] toArray(E[] a, MapCallback<?, T, C, ?> callback,
      C context) {
    int size = 0;
    int offset = 0;
    E[] result;
    acquireAllLocks(false);
    try {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        size += seg.size;
      }
      if (a.length >= size) {
        result = a;
      }
      else {
        Class<?> c = a.getClass();
        result = (E[])(c == Object[].class ? new Object[size]
            : Array.newInstance(c.getComponentType(), size));
      }
      if (callback != null) {
        callback.onToArray(context);
      }
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        offset = seg.toArray(result, seg.set, offset);
      }
    } finally {
      releaseAllLocks(false);
    }
    return result;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <E> E[] toArray(E[] a) {
    return toArray(a, null, null);
  }

  /** copy the contents in array and clear the map as atomic operation */
  @SuppressWarnings("unchecked")
  public <E> E[] drainTo(E[] a) {
    int size = 0;
    int offset = 0;
    E[] result;
    acquireAllLocks(true);
    try {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        size += seg.size;
      }
      if (a.length >= size) {
        result = a;
      } else {
        Class<?> c = a.getClass();
        result = (E[])(c == Object[].class ? new Object[size]
            : Array.newInstance(c.getComponentType(), size));
      }
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        offset = seg.toArray(result, seg.set, offset);
        seg.clear();
      }
    } finally {
      releaseAllLocks(true);
    }
    return result;
  }

  /**
   * This is a variant of toArray to send back the result as a set.
   */
  @SuppressWarnings("unchecked")
  public THashSet toSet() {
    int size = 0;
    THashSet result;
    acquireAllLocks(false);
    try {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        size += seg.size;
      }
      result = new THashSet(size);
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        seg.toCollection(result, seg.set);
      }
    } finally {
      releaseAllLocks(false);
    }
    return result;
  }

  /**
   * This variant of toArray is an unsafe one that takes no segment locks and
   * can return somewhat arbitrary results. No product code should use this
   * except for monitoring or other such purposes which should not wait for
   * locks and is not affected much by inaccurate results.
   */
  public <E> void toCollectionUnsafe(Collection<E> result) {
    for (ConcurrentTHashSegment<T> seg : this.segments) {
      seg.toCollection(result, seg.set);
    }
  }

  @Override
  public String toString() {
    Iterator<T> it = iterator();
    StringBuilder sb = new StringBuilder();
    sb.append("SET[");
    boolean firstItem = true;
    while (it.hasNext()) {
      if (firstItem) {
        firstItem = false;
      }
      else {
        sb.append(", ");
      }
      sb.append(it.next());
    }
    sb.append(']');
    return sb.toString();
  }

  public long estimateMemoryOverhead(SingleObjectSizer sizer) {
    long totalOverhead = sizer.sizeof(this);
    for (ConcurrentTHashSegment<T> seg : this.segments) {
      totalOverhead += sizer.sizeof(seg);
    }
    return totalOverhead;
  }

  /**
   * Returns the segment that should be used for key with given hash
   * 
   * @param hash
   *          the hash code for the key
   * @return the segment
   */
  private final ConcurrentTHashSegment<T> segmentFor(final int hash) {
    return this.segments[hash % this.numSegments];
  }

  private final int segmentIndex(final int hash) {
    return (hash % this.numSegments);
  }

  private final void acquireAllLocks(boolean forWrite) {
    if (forWrite) {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        seg.writeLock().lock();
      }
    }
    else {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        seg.readLock().lock();
      }
    }
  }

  private final void releaseAllLocks(boolean forWrite) {
    if (forWrite) {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        seg.writeLock().unlock();
      }
    }
    else {
      for (ConcurrentTHashSegment<T> seg : this.segments) {
        seg.readLock().unlock();
      }
    }
  }

  public final class Itr implements Iterator<T> {

    private ConcurrentTHashSegment<T> seg;
    private int segIndex;
    private Object[] set;
    private Object currentObj;
    private Object nextObj;
    private int nextIndex;

    Itr() {
      setSet(segments[0]);
      moveNext();
    }

    private final void setSet(ConcurrentTHashSegment<T> segment) {
      this.seg = segment;
      // no lock required since segment.set changes atomically after rehash
      this.set = segment.set;
      this.currentObj = null;
      this.nextIndex = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
      return this.nextObj != null;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public T next() {
      final Object o = this.currentObj = this.nextObj;
      if (o != null) {
        moveNext();
        return (T)o;
      }
      else {
        throw new NoSuchElementException();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove() {
      final Object o = this.currentObj;
      int hash;
      if (o != null) {
        hash = computeHashCode(o, hashingStrategy);
        this.seg.remove(o, hash);
      }
      else {
        throw new NoSuchElementException();
      }
    }

    private void moveNext() {
      this.nextObj = null;

      Object o;
      while (true) {
        final Object[] set = this.set;
        final int size = set.length;
        for (int i = this.nextIndex; i < size; i++) {
          o = set[i];
          if (o != null && o != ConcurrentTHashSegment.REMOVED) {
            this.nextIndex = i + 1;
            this.nextObj = o;
            return;
          }
        }
        if (this.nextObj != null) {
          return;
        }

        // move to next segment
        if (++this.segIndex < numSegments) {
          setSet(segments[this.segIndex]);
        } else {
          return;
        }
      }
    }
  }
}
