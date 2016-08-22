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

import java.util.AbstractCollection;
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gnu.trove.HashingStats;
import com.gemstone.gnu.trove.PrimeFinder;
import com.gemstone.gnu.trove.THash;
import com.gemstone.gnu.trove.TLongObjectProcedure;
import com.gemstone.gnu.trove.TLongProcedure;
import com.gemstone.gnu.trove.TObjectProcedure;

/**
 * A concurrent version of Trove's <tt>TLongObjectHashMap</tt>.
 *
 * @author swale
 * @since gfxd 1.0
 *
 * @param <T>
 *          the type of mapped values
 */
@SuppressWarnings("serial")
public final class ConcurrentTLongObjectHashMap<T> extends THashParameters
    implements Map<Long, T> {

  public static final int DEFAULT_CONCURRENCY = 16;

  private final ConcurrentTLongObjectHashSegment<T>[] segments;
  private final AtomicLong totalSize;

  private transient Set<Long> keySet;
  private transient Collection<T> values;
  private transient Set<Map.Entry<Long, T>> entrySet;

  public ConcurrentTLongObjectHashMap() {
    this(DEFAULT_CONCURRENCY, THash.DEFAULT_INITIAL_CAPACITY,
        THash.DEFAULT_LOAD_FACTOR, null);
  }

  public ConcurrentTLongObjectHashMap(int concurrency) {
    this(concurrency, THash.DEFAULT_INITIAL_CAPACITY,
        THash.DEFAULT_LOAD_FACTOR, null);
  }

  public ConcurrentTLongObjectHashMap(int concurrency, int initialCapacity) {
    this(concurrency, initialCapacity, THash.DEFAULT_LOAD_FACTOR, null);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentTLongObjectHashMap(int concurrency, int initialCapacity,
      float loadFactor, HashingStats stats) {
    super(loadFactor, null, stats);

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
    this.segments = new ConcurrentTLongObjectHashSegment[concurrency];
    for (int index = 0; index < concurrency; index++) {
      this.segments[index] = new ConcurrentTLongObjectHashSegment<T>(segSize,
          this, this.totalSize);
    }
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public T get(Object key) {
    if (key != null) {
      long k = (Long)key;
      final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(k);
      return (T)segmentFor(hash).get(k, hash);
    }
    else {
      throw new NullPointerException("null key");
    }
  }

  @SuppressWarnings("unchecked")
  public T getPrimitive(long key) {
    final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(key);
    return (T)segmentFor(hash).get(key, hash);
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public T put(Long key, T value) {
    if (key != null) {
      long k = key;
      final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(k);
      return (T)segmentFor(hash).put(k, value, hash);
    }
    else {
      throw new NullPointerException("null key");
    }
  }

  /**
   * Like {@link #put(Long, Object)} but takes a primitive long as key to avoid
   * creating a Long object.
   *
   * @see #put(Long, Object)
   */
  public Object putPrimitive(long key, T value) {
    final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(key);
    return segmentFor(hash).put(key, value, hash);
  }

  /**
   * If the specified key is not already associated with a value, associate it
   * with the given value. This is equivalent to
   *
   * <pre>
   * if (!map.containsKey(key))
   *   return map.put(key, value);
   * else
   *   return map.get(key);
   * </pre>
   *
   * except that the action is performed atomically.
   *
   * @param key
   *          long key with which the specified value is to be associated
   * @param value
   *          value to be associated with the specified key
   *
   * @return the previous value associated with the specified key, or
   *         <tt>null</tt> if there was no mapping for the key. (A <tt>null</tt>
   *         return can also indicate that the map previously associated
   *         <tt>null</tt> with the key)
   *
   * @throws NullPointerException
   *           if the specified key is null
   */
  public Object putIfAbsent(long key, T value) {
    final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(key);
    return segmentFor(hash).putIfAbsent(key, value, hash);
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public T remove(Object key) {
    if (key != null) {
      long k = (Long)key;
      final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(k);
      return (T)segmentFor(hash).remove(k, hash);
    }
    else {
      throw new NullPointerException("null key");
    }
  }

  public Object removePrimitive(long key) {
    final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(key);
    return segmentFor(hash).remove(key, hash);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void putAll(Map<? extends Long, ? extends T> m) {
    if (m != null) {
      Long key;
      T value;
      for (Map.Entry<? extends Long, ? extends T> e : m.entrySet()) {
        key = e.getKey();
        value = e.getValue();
        if (key != null) {
          putPrimitive(key, value);
        }
        else {
          throw new NullPointerException("null key");
        }
      }
    }
    else {
      throw new NullPointerException("null collection");
    }
  }

  public final long longSize() {
    return this.totalSize.get();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int size() {
    final long size = this.totalSize.get();
    return size < Integer.MAX_VALUE ? (int)size : Integer.MAX_VALUE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isEmpty() {
    return this.totalSize.get() == 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean containsKey(Object key) {
    if (key != null) {
      long k = (Long)key;
      final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(k);
      return segmentFor(hash).contains(k, hash);
    }
    else {
      throw new NullPointerException("null key");
    }
  }

  public boolean containsKeyPrimitive(long key) {
    final int hash = ConcurrentTLongObjectHashSegment.computeHashCode(key);
    return segmentFor(hash).contains(key, hash);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean containsValue(Object value) {
    for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
      if (seg.containsValue(value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clear() {
    for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
      seg.clear();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<Long> keySet() {
    if (this.keySet == null) {
      this.keySet = new AbstractSet<Long>() {
        @Override
        public Iterator<Long> iterator() {
          return new HashIterator<Long>() {
            @Override
            public Long next() {
              return nextKey();
            }
          };
        }

        @Override
        public boolean contains(Object k) {
          return ConcurrentTLongObjectHashMap.this.containsKey(k);
        }

        @Override
        public boolean remove(Object k) {
          return ConcurrentTLongObjectHashMap.this.remove(k) != null;
        }

        @Override
        public int size() {
          return ConcurrentTLongObjectHashMap.this.size();
        }

        @Override
        public boolean isEmpty() {
          return ConcurrentTLongObjectHashMap.this.isEmpty();
        }

        @Override
        public void clear() {
          ConcurrentTLongObjectHashMap.this.clear();
        }
      };
    }
    return this.keySet;
  }

  /**
   * returns the keys of the map.
   *
   * @return a <code>Set</code> value
   */
  public long[] keys() {
    long[] resultKeys;
    acquireAllLocks(false);
    try {
      int size = 0;
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        size += seg.size;
      }
      resultKeys = new long[size];
      int index = 0;
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        long[] keys = seg.keys;
        Object[] vals = seg.values;
        Object val;
        int sz = keys.length;
        for (int i = 0; i < sz; i++) {
          val = vals[i];
          if (val != null && val != ConcurrentTLongObjectHashSegment.REMOVED) {
            resultKeys[index++] = keys[i];
          }
        }
      }
    } finally {
      releaseAllLocks(false);
    }
    return resultKeys;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Collection<T> values() {
    if (this.values == null) {
      this.values = new AbstractCollection<T>() {
        @Override
        public Iterator<T> iterator() {
          return new HashIterator<T>() {
            @Override
            public T next() {
              return nextValue();
            }
          };
        }

        @Override
        public boolean contains(Object v) {
          return ConcurrentTLongObjectHashMap.this.containsValue(v);
        }

        @Override
        public int size() {
          return ConcurrentTLongObjectHashMap.this.size();
        }

        @Override
        public boolean isEmpty() {
          return ConcurrentTLongObjectHashMap.this.isEmpty();
        }

        @Override
        public void clear() {
          ConcurrentTLongObjectHashMap.this.clear();
        }
      };
    }
    return this.values;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<Map.Entry<Long, T>> entrySet() {
    if (this.entrySet == null) {
      this.entrySet = new AbstractSet<Map.Entry<Long, T>>() {
        @Override
        public Iterator<Map.Entry<Long, T>> iterator() {
          return new HashIterator<Map.Entry<Long, T>>() {
            @Override
            public Map.Entry<Long, T> next() {
              return nextEntry();
            }
          };
        }

        @Override
        public boolean contains(Object o) {
          if (o instanceof Map.Entry<?, ?>) {
            @SuppressWarnings("unchecked")
            Map.Entry<Long, T> e = (Map.Entry<Long, T>)o;
            return ConcurrentTLongObjectHashMap.this.containsKey(e.getKey());
          }
          else {
            return false;
          }
        }

        @Override
        public boolean remove(Object o) {
          if (o instanceof Map.Entry<?, ?>) {
            @SuppressWarnings("unchecked")
            Map.Entry<Long, T> e = (Map.Entry<Long, T>)o;
            return ConcurrentTLongObjectHashMap.this.remove(e.getKey()) != null;
          }
          else {
            return false;
          }
        }

        @Override
        public int size() {
          return ConcurrentTLongObjectHashMap.this.size();
        }

        @Override
        public void clear() {
          ConcurrentTLongObjectHashMap.this.clear();
        }
      };
    }
    return this.entrySet;
  }

  public long estimateMemoryOverhead(SingleObjectSizer sizer) {
    long totalOverhead = sizer.sizeof(this);
    for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
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
  private ConcurrentTLongObjectHashSegment<T> segmentFor(final int hash) {
    return this.segments[hash % this.segments.length];
  }

  /**
   * Apply the given procedure for each key in the map. Do not invoke any
   * operation on this map directly from inside the body of procedure itself
   * else it will result in a deadlock.
   */
  public boolean forEachKey(TLongProcedure proc) {
    acquireAllLocks(false);
    try {
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        int size = seg.size;
        long key;
        Object value;
        for (int index = 0; index < size; index++) {
          key = seg.keys[index];
          value = seg.values[index];
          if (value != null &&
              value != ConcurrentTLongObjectHashSegment.REMOVED) {
            if (!proc.execute(key)) {
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
   * Apply the given procedure for each value in the map. Do not invoke any
   * operation on this map directly from inside the body of procedure itself
   * else it will result in a deadlock.
   */
  public boolean forEachValue(TObjectProcedure proc) {
    acquireAllLocks(false);
    try {
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        for (Object o : seg.values) {
          if (o != null && o != ConcurrentTLongObjectHashSegment.REMOVED) {
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
   * Apply the given procedure for each key, value pair in the map. Do not
   * invoke any operation on this map directly from inside the body of procedure
   * itself else it will result in a deadlock.
   */
  public boolean forEachEntry(TLongObjectProcedure proc) {
    acquireAllLocks(false);
    try {
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        int size = seg.size;
        long key;
        Object value;
        for (int index = 0; index < size; index++) {
          key = seg.keys[index];
          value = seg.values[index];
          if (value != null &&
              value != ConcurrentTLongObjectHashSegment.REMOVED) {
            if (!proc.execute(key, value)) {
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

  private void acquireAllLocks(boolean forWrite) {
    if (forWrite) {
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        seg.writeLock().lock();
      }
    }
    else {
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        seg.readLock().lock();
      }
    }
  }

  private void releaseAllLocks(boolean forWrite) {
    if (forWrite) {
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        seg.writeLock().unlock();
      }
    }
    else {
      for (ConcurrentTLongObjectHashSegment<T> seg : this.segments) {
        seg.readLock().unlock();
      }
    }
  }

  abstract class HashIterator<K> implements Iterator<K> {

    private ConcurrentTLongObjectHashSegment<T> seg;
    private int segIndex;
    private long[] keys;
    private Object[] values;
    private long currentKey;
    private Object currentObj;
    private long nextKey;
    private Object nextObj;
    private int nextIndex;

    HashIterator() {
      setMap(segments[0]);
    }

    final void setMap(ConcurrentTLongObjectHashSegment<T> segment) {
      this.seg = segment;
      // acquire read lock since we need snap of both keys and values
      segment.readLock().lock();
      this.keys = segment.keys;
      this.values = segment.values;
      segment.readLock().unlock();
      this.currentKey = 0;
      this.currentObj = null;
      moveNext();
    }

    @Override
    public final boolean hasNext() {
      return this.nextObj != null;
    }

    final long nextKey() {
      final Object o = this.currentObj = this.nextObj;
      if (o != null) {
        final long k = this.currentKey = this.nextKey;
        moveNext();
        return k;
      }
      else {
        throw new NoSuchElementException();
      }
    }

    @SuppressWarnings("unchecked")
    final T nextValue() {
      final Object o = this.currentObj = this.nextObj;
      this.currentKey = this.nextKey;
      if (o != null) {
        moveNext();
        return (T)o;
      }
      else {
        throw new NoSuchElementException();
      }
    }

    @SuppressWarnings("unchecked")
    final Map.Entry<Long, T> nextEntry() {
      final Object o = this.currentObj = this.nextObj;
      if (o != null) {
        final long k = this.currentKey = this.nextKey;
        moveNext();
        return new SimpleEntry<Long, T>(k, (T)o);
      }
      else {
        throw new NoSuchElementException();
      }
    }

    @Override
    public final void remove() {
      int hash;
      if (this.currentObj != null) {
        final long key = this.currentKey;
        hash = ConcurrentTLongObjectHashSegment.computeHashCode(key);
        this.seg.remove(key, hash);
      }
      else {
        throw new NoSuchElementException();
      }
    }

    final void moveNext() {
      Object o;
      final int size = this.values.length;
      this.nextObj = null;
      for (int i = this.nextIndex; i < size; i++) {
        o = this.values[i];
        if (o != null && o != ConcurrentTLongObjectHashSegment.REMOVED) {
          this.nextIndex = i + 1;
          this.nextKey = this.keys[i];
          this.nextObj = o;
          break;
        }
      }
      if (this.nextObj == null) {
        // move to next segment
        if (++this.segIndex < segments.length) {
          setMap(segments[this.segIndex]);
        }
      }
    }
  }
}
