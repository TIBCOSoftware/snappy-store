/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

import com.gemstone.gemfire.internal.concurrent.MapCallback;
import com.gemstone.gemfire.internal.concurrent.MapResult;
import com.gemstone.gemfire.internal.concurrent.THashParameters;
import com.gemstone.gnu.trove.TObjectHashingStrategy;

/**
 * An optimized HashSet using open addressing with quadratic probing.
 * In micro-benchmarks this is faster in both inserts, deletes and gets,
 * as well as mixed workloads than most other HashSet implementations
 * generally available in java (JDK HashSet, fastutil HashSets, or Trove's).
 * <p>
 * It adds additional APIs like {@link #create}, {@link #getKey},
 * {@link #addKey} which is the main reason for having this class.
 */
public class OpenHashSet<E> extends AbstractSet<E>
    implements Set<E>, Cloneable, java.io.Serializable {

  private static final long serialVersionUID = 2837689134511263091L;

  public static final Object REMOVED = new Object();
  // maximum power of 2 less than Integer.MAX_VALUE
  protected static final int MAX_CAPACITY = 1 << 30;

  protected final float loadFactor;
  protected final TObjectHashingStrategy hashingStrategy;

  protected int size;
  protected int occupied;
  protected int growThreshold;

  protected int mask;
  protected Object[] data;

  protected Function<OpenHashSet<E>, Void> postRehashHook;

  @SuppressWarnings("unused")
  public OpenHashSet() {
    this(16, 0.7f);
  }

  public OpenHashSet(int initialCapacity) {
    this(initialCapacity, 0.7f, THashParameters.DEFAULT_HASHING);
  }

  public OpenHashSet(int initialCapacity, float loadFactor) {
    this(initialCapacity, loadFactor, THashParameters.DEFAULT_HASHING);
  }

  public OpenHashSet(int initialCapacity, float loadFactor,
      TObjectHashingStrategy hashingStrategy) {
    final int capacity = nextPowerOf2(initialCapacity);
    this.loadFactor = loadFactor;
    this.growThreshold = (int)(loadFactor * capacity);
    this.mask = capacity - 1;
    this.data = new Object[capacity];
    this.hashingStrategy = hashingStrategy != null
        ? hashingStrategy : THashParameters.DEFAULT_HASHING;
  }

  public OpenHashSet(Collection<? extends E> c) {
    this(c.size());
    addAll(c);
  }

  public static int keyHash(Object k, TObjectHashingStrategy hashingStrategy) {
    return ClientResolverUtils.fastHashInt(hashingStrategy.computeHashCode(k));
  }

  protected final int insertionIndex(final Object[] data, final Object key,
      final int hash, final TObjectHashingStrategy hashingStrategy) {
    final int mask = this.mask;
    int pos = hash & mask;
    // try to fill the REMOVED slot but only if it is a new insertion
    // else we need to keep searching for possible existing value
    int removedPos = -1;
    int delta = 1;
    while (true) {
      final Object mapKey = data[pos];
      if (mapKey != null) {
        if (mapKey == REMOVED) {
          removedPos = pos;
        } else if (hashingStrategy.equals(mapKey, key)) {
          // return already present key position as negative
          return -pos - 1;
        }
        // quadratic probing with position increase by 1, 2, 3, ...
        pos = (pos + delta) & mask;
        delta++;
      } else {
        final boolean slotIsNull = (removedPos == -1);
        // if slot was a REMOVED token then skip incrementing the "occupied" count
        if (slotIsNull) {
          occupied++;
          return pos;
        } else {
          return removedPos;
        }
      }
    }
  }

  protected boolean doInsert(final Object[] data, final Object key,
      final int pos) {
    data[pos] = key;
    return handleNewInsert();
  }

  protected boolean doRemove(final Object[] data, final int pos) {
    // mark as deleted
    data[pos] = REMOVED;
    return handleRemove();
  }

  protected final int index(final Object[] data, final Object key,
      final int hash, final TObjectHashingStrategy hashingStrategy) {
    final int mask = this.mask;
    int pos = hash & mask;
    int delta = 1;
    while (true) {
      final Object mapKey = data[pos];
      if (mapKey != null) {
        if (mapKey != REMOVED && hashingStrategy.equals(mapKey, key)) {
          return pos;
        } else {
          // quadratic probing with position increase by 1, 2, 3, ...
          pos = (pos + delta) & mask;
          delta++;
        }
      } else {
        return -1;
      }
    }
  }

  protected final <K, C, P> Object create(final K key,
      final MapCallback<K, E, C, P> creator, final C context, final P params,
      final MapResult result, final int hash,
      final TObjectHashingStrategy hashingStrategy) {
    final Object[] data = this.data;
    final int mask = this.mask;
    int pos = hash & mask;
    // try to fill the REMOVED slot but only if it is a new insertion
    // else we need to keep searching for possible existing value
    int removedPos = -1;
    int delta = 1;
    while (true) {
      final Object mapKey = data[pos];
      if (mapKey != null) {
        if (mapKey == REMOVED) {
          removedPos = pos;
        } else if (hashingStrategy.equals(mapKey, key)) {
          // return old key
          return mapKey;
        }
        // quadratic probing with position increase by 1, 2, 3, ...
        pos = (pos + delta) & mask;
        delta++;
      } else {
        // insert into the map and rehash if required
        result.setNewValueCreated(true);
        final Object newKey = creator.newValue(key, context, params, result);
        if (result.isNewValueCreated()) {
          // if slot was a REMOVED token then skip incrementing the "occupied" count
          if (removedPos == -1) {
            occupied++;
          } else {
            pos = removedPos;
          }
          doInsert(data, newKey, pos);
          return newKey;
        } else {
          return null;
        }
      }
    }
  }

  public void setPostRehashHook(Function<OpenHashSet<E>, Void> hook) {
    this.postRehashHook = hook;
  }

  public final Object addKey(final Object key, final boolean replace,
      final int hash, final TObjectHashingStrategy hashingStrategy) {
    final Object[] data = this.data;
    final int pos = insertionIndex(data, key, hash, hashingStrategy);
    if (pos >= 0) {
      doInsert(data, key, pos);
      return null;
    } else {
      final int currentPos = -pos - 1;
      final Object mapKey = data[currentPos];
      if (replace) {
        data[currentPos] = key;
      }
      return mapKey;
    }
  }

  public final Object getKey(final Object key) {
    final TObjectHashingStrategy hashingStrategy = this.hashingStrategy;
    final Object[] data = this.data;
    final int pos = index(data, key, keyHash(key, hashingStrategy),
        hashingStrategy);
    if (pos >= 0) return data[pos];
    else return null;
  }

  public final Object removeKey(final Object key) {
    final TObjectHashingStrategy hashingStrategy = this.hashingStrategy;
    return removeKey(key, keyHash(key, hashingStrategy), hashingStrategy);
  }

  private Object removeKey(final Object key, final int hash,
      final TObjectHashingStrategy hashingStrategy) {
    final Object[] data = this.data;
    final int pos = index(data, key, hash, hashingStrategy);
    if (pos >= 0) {
      final Object mapKey = data[pos];
      doRemove(data, pos);
      return mapKey;
    } else {
      // no matching key
      return null;
    }
  }

  public boolean contains(Object key) {
    final TObjectHashingStrategy hashingStrategy = this.hashingStrategy;
    return index(data, key, keyHash(key, hashingStrategy), hashingStrategy) >= 0;
  }

  @Override
  public boolean add(E key) {
    final TObjectHashingStrategy hashingStrategy = this.hashingStrategy;
    final Object[] data = this.data;
    final int pos = insertionIndex(data, key, keyHash(key, hashingStrategy),
        hashingStrategy);
    if (pos >= 0) {
      doInsert(data, key, pos);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public boolean remove(Object key) {
    final TObjectHashingStrategy hashingStrategy = this.hashingStrategy;
    return removeKey(key, keyHash(key, hashingStrategy),
        hashingStrategy) != null;
  }

  @SuppressWarnings("NullableProblems")
  @Override
  public Itr<E> iterator() {
    return new Itr<>(this);
  }

  @Override
  public int size() {
    return this.size;
  }

  public final int capacity() {
    return this.data.length;
  }

  public final TObjectHashingStrategy getHashingStrategy() {
    return this.hashingStrategy;
  }

  @Override
  public void clear() {
    final Object[] data = this.data;
    final int size = data.length;
    for (int i = 0; i < size; i++) {
      data[i] = null;
    }
    this.size = 0;
    this.occupied = 0;
  }

  protected final boolean handleNewInsert() {
    size++;
    // check and trigger a rehash if load factor exceeded
    if (occupied <= growThreshold) {
      return false;
    } else {
      // double the capacity
      rehash(checkCapacity(data.length << 1));
      return true;
    }
  }

  protected final boolean handleRemove() {
    // reduce size but not the number of occupied cells
    // if number of deleted entries is too large then rehash to shrink
    if (--size > (occupied >>> 1) || data.length <= 128) {
      return false;
    } else {
      // half the capacity
      rehash(data.length >>> 1);
      return true;
    }
  }

  /**
   * Double the table's size and re-hash everything.
   * Caller must check for overloaded set before triggering a rehash.
   */
  protected final void rehash(final int newCapacity) {
    final Object[] data = this.data;
    final int capacity = data.length;

    final Object[] newData = new Object[newCapacity];
    final int newMask = newCapacity - 1;
    final TObjectHashingStrategy hashingStrategy = this.hashingStrategy;

    int oldPos = 0;
    while (oldPos < capacity) {
      final Object d = data[oldPos];
      if (d != null && d != REMOVED) {
        final int newHash = keyHash(d, hashingStrategy);
        int newPos = newHash & newMask;
        int delta = 1;
        // No need to check for equality here when we insert.
        while (true) {
          if (newData[newPos] == null) {
            // Inserting the key at newPos
            newData[newPos] = d;
            break;
          } else {
            newPos = (newPos + delta) & newMask;
            delta++;
          }
        }
      }
      oldPos++;
    }

    // all deleted entries marked REMOVED have been cleared
    this.occupied = this.size;
    this.data = newData;
    this.mask = newMask;
    this.growThreshold = (int)(loadFactor * newCapacity);

    if (this.postRehashHook != null) {
      this.postRehashHook.apply(this);
    }
  }

  protected static int checkCapacity(int capacity) {
    if (capacity > 0 && capacity <= MAX_CAPACITY) {
      return capacity;
    } else if (capacity == 0) {
      return 2;
    } else {
      throw new IllegalStateException("Capacity (" + capacity +
          ") can't be more than " + MAX_CAPACITY + " elements or negative");
    }
  }

  public static int nextPowerOf2(int n) {
    final int highBit = Integer.highestOneBit(n > 0 ? n : 2);
    return checkCapacity(highBit == n ? n : highBit << 1);
  }

  public static final class Itr<E> implements Iterator<E> {

    private final Object[] data;
    private Object result;
    private int pos;
    private int prevPos;
    private final OpenHashSet<?> set;

    Itr(OpenHashSet<?> set) {
      // take a snapshot of the array to avoid problems due to rehash
      this.data = set.data;
      this.result = null;
      this.pos = -1;
      this.prevPos = -1;
      this.set = set;
      advance(this.data);
    }

    private void advance(final Object[] data) {
      final int size = data.length;
      for (int pos = this.pos + 1; pos < size; pos++) {
        final Object d = data[pos];
        if (d != null && d != REMOVED) {
          this.result = d;
          this.pos = pos;
          return;
        }
      }
      // no next element
      this.result = null;
      this.pos = -1;
    }

    @Override
    public boolean hasNext() {
      return this.result != null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public E next() {
      final Object result = this.result;
      if (result != null) {
        final Object[] data = this.data;
        this.prevPos = this.pos;
        advance(data);
        return (E)result;
      } else {
        throw new NoSuchElementException("invalid iterator position");
      }
    }

    @Override
    public void remove() {
      final int pos = this.prevPos;
      if (pos >= 0) {
        final Object[] data = this.data;
        final OpenHashSet<?> set = this.set;
        // if no change in storage array (i.e. no rehash) then change in-place
        if (data == set.data) {
          set.doRemove(data, pos);
        } else {
          set.remove(data[pos]);
        }
      } else {
        throw new NoSuchElementException("invalid iterator position");
      }
    }
  }
}
