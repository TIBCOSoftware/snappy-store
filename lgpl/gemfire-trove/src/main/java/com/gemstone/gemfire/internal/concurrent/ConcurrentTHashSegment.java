/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
/*
 * Contains code from GNU Trove having the license below.
 *
 * Copyright (c) 2001, Eric D. Friedman All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package com.gemstone.gemfire.internal.concurrent;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.gemstone.gnu.trove.HashingStats;
import com.gemstone.gnu.trove.PrimeFinder;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectHashingStrategy;

/**
 * Base class for a segment of concurrent hash maps allowing for appropriate
 * locking to make it thread-safe.
 * 
 * Adapted from Trove's <tt>TObjectHash</tt>.
 * 
 * @author swale
 * @since gfxd 1.0
 * 
 * @param <E>
 *          the type of objects in the segment
 */
@SuppressWarnings("serial")
final class ConcurrentTHashSegment<E> extends ReentrantReadWriteLock implements
    MapResult {

  static final Object REMOVED = THashSet.REMOVED;

  /** the set of Objects */
  Object[] set;

  /** the current number of occupied slots in the hash. */
  int size;

  /** the current number of free slots in the hash. */
  int free;

  /**
   * The maximum number of elements allowed without allocating more space.
   */
  int maxSize;

  /** for {@link MapResult} */
  boolean newValueInsert;

  /** parameters used for hashing */
  final THashParameters params;

  /** tracks the total size of the top-level ConcurrentTHash structure */
  final AtomicLong totalSize;

  ConcurrentTHashSegment(int initialCapacity, THashParameters params,
      AtomicLong totalSize) {
    int capacity = PrimeFinder.nextPrime((int)Math.ceil(initialCapacity
        / params.loadFactor));
    this.set = new Object[capacity];
    this.params = params;
    this.totalSize = totalSize;
    computeMaxSize(capacity, params);
  }

  private void computeMaxSize(int capacity, THashParameters params) {
    // need at least one free slot for open addressing
    this.maxSize = Math.min(capacity - 1,
        (int)Math.floor(capacity * params.loadFactor));
    this.free = capacity - this.size; // reset the free element count
  }

  public final int size() {
    super.readLock().lock();
    final int sz = this.size;
    super.readLock().unlock();
    return sz;
  }

  public final boolean contains(Object key, final int hash) {
    super.readLock().lock();
    try {
      return index(key, hash) >= 0;
    } finally {
      super.readLock().unlock();
    }
  }

  public final Object getKey(Object key, final int hash) {
    super.readLock().lock();
    try {
      return getKeyNoLock(key, hash);
    } finally {
      super.readLock().unlock();
    }
  }

  public final Object getKeyNoLock(Object key, final int hash) {
    final int index = index(key, hash);
    if (index >= 0) {
      return set[index];
    }
    else {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  final <O> int toArray(O[] a, final Object[] set, int offset) {
    for (Object o : set) {
      if (o != null && o != REMOVED) {
        a[offset++] = (O)o;
      }
    }
    return offset;
  }

  @SuppressWarnings("unchecked")
  final <O> void toCollection(Collection<O> a, final Object[] set) {
    for (Object o : set) {
      if (o != null && o != REMOVED) {
        a.add((O)o);
      }
    }
  }

  public final Object add(E e, final int hash) {
    super.writeLock().lock();
    try {
      return addP(e, hash);
    } finally {
      super.writeLock().unlock();
    }
  }

  public final Object put(E e, final int hash) {
    super.writeLock().lock();
    try {
      final Object[] set = this.set;
      int index = insertionIndex(e, hash, set);

      if (index < 0) {
        index = -index - 1;
      }
      Object old = set[index];
      set[index] = e;

      postInsertHook(old == null);
      return old;
    } finally {
      super.writeLock().unlock();
    }
  }

  final Object addP(E e, final int hash) {
    final Object[] set = this.set;
    int index = insertionIndex(e, hash, set);

    if (index < 0) {
      index = -index - 1;
      return set[index]; // already present in set, nothing to add
    }

    Object old = set[index];
    set[index] = e;

    postInsertHook(old == null);
    return null; // yes, we added something
  }

  public final <K, C, P> Object create(final K key,
      final MapCallback<K, E, C, P> valueCreator, final C context,
      final P createParams, final int hash) {
    super.writeLock().lock();
    try {
      final Object[] set = this.set;
      int index = insertionIndex(key, hash, set);

      if (index < 0) {
        index = -index - 1;
        return set[index]; // already present in set, nothing to add
      }

      Object old = set[index];
      this.newValueInsert = true;
      Object e = valueCreator.newValue(key, context, createParams, this);
      if (this.newValueInsert) {
        set[index] = e;

        postInsertHook(old == null);
        return e;
      }
      else {
        return null;
      }
    } finally {
      super.writeLock().unlock();
    }
  }

  public final Object remove(Object key, final int hash) {
    super.writeLock().lock();
    try {
      return removeP(key, hash);
    } finally {
      super.writeLock().unlock();
    }
  }

  final Object removeP(Object key, final int hash) {
    int index = index(key, hash);
    if (index >= 0) {
      return removeAt(index);
    }
    else {
      return null;
    }
  }

  public final boolean retainAll(Collection<?> c) {
    boolean changed = false;
    super.writeLock().lock();
    try {
      final Object[] set = this.set;
      Object o;
      for (int i = set.length; i-- > 0;) {
        o = set[i];
        if (o != null && o != REMOVED && !c.contains(o)) {
          removeAt(i);
          changed = true;
        }
      }
    } finally {
      super.writeLock().unlock();
    }
    return changed;
  }

  public final boolean clear() {
    int sz;
    super.writeLock().lock();
    try {
      final int size = this.set.length;
      for (int index = 0; index < size; index++) {
        clearAt(index);
      }
      sz = this.size;
      this.totalSize.addAndGet(-sz);
      this.size = 0;
      this.free = size;
    } finally {
      super.writeLock().unlock();
    }
    return (sz > 0);
  }

  /**
   * Locates the index of <tt>key</tt>.
   * 
   * @param key
   *          an <code>Object</code> key to search
   * 
   * @return the index of <tt>key</tt> or -1 if it isn't in the set.
   */
  final int index(Object key, final int hash) {
    final Object[] set;
    int probe, index, length;
    Object cur;
    final TObjectHashingStrategy hashingStrategy;
    final HashingStats stats;

    set = this.set;
    length = set.length;
    hashingStrategy = this.params.hashingStrategy;
    stats = this.params.stats;
    index = hash % length;
    cur = set[index];

    if (cur != null && (cur == REMOVED || !hashingStrategy.equals(cur, key))) {
      long start = -1L;
      if (stats != null) {
        start = stats.getNanoTime();
      }

      // see Knuth, p. 529
      probe = 1 + (hash % (length - 2));

      do {
        index -= probe;
        if (index < 0) {
          index += length;
        }
        cur = set[index];
      } while (cur != null
          && (cur == REMOVED || !hashingStrategy.equals(cur, key)));
      if (stats != null) {
        stats.endQueryResultsHashCollisionProbe(start);
      }
    }

    return cur == null ? -1 : index;
  }

  /**
   * Locates the index at which <tt>key</tt> can be inserted. if there is
   * already a value equal()ing <tt>key</tt> in the set, returns that value's
   * index as <tt>-index - 1</tt>.
   * 
   * @param key
   *          an <code>Object</code> key to search
   * 
   * @return the index of a FREE slot at which obj can be inserted or, if obj is
   *         already stored in the hash, the negative value of that index, minus
   *         1: -index -1.
   */
  final int insertionIndex(Object key, final int hash, final Object[] set) {
    int probe, index, length;
    Object cur;
    final TObjectHashingStrategy hashingStrategy;
    final HashingStats stats;

    length = set.length;
    hashingStrategy = this.params.hashingStrategy;
    stats = this.params.stats;
    index = hash % length;
    cur = set[index];

    if (cur == null) {
      return index; // empty, all done
    }
    else if (cur != REMOVED && hashingStrategy.equals(cur, key)) {
      return -index - 1; // already stored
    }
    else { // already FULL or REMOVED, must probe
      long start = -1L;
      if (stats != null) {
        start = stats.getNanoTime();
        stats.incQueryResultsHashCollisions();
      }

      // compute the double hash
      probe = 1 + (hash % (length - 2));

      // if the slot we landed on is FULL (but not removed), probe
      // until we find an empty slot, a REMOVED slot, or an element
      // equal to the one we are trying to insert.
      // finding an empty slot means that the value is not present
      // and that we should use that slot as the insertion point;
      // finding a REMOVED slot means that we need to keep searching,
      // however we want to remember the offset of that REMOVED slot
      // so we can reuse it in case a "new" insertion (i.e. not an update)
      // is possible.
      // finding a matching value means that we've found that our desired
      // key is already in the table
      if (cur != REMOVED) {
        // starting at the natural offset, probe until we find an
        // offset that isn't full.
        do {
          index -= probe;
          if (index < 0) {
            index += length;
          }
          cur = set[index];
        } while (cur != null && cur != REMOVED
            && !hashingStrategy.equals(cur, key));
      }

      // if the index we found was removed: continue probing until we
      // locate a free location or an element which equal()s the
      // one we have.
      if (cur == REMOVED) {
        int firstRemoved = index;
        while (cur != null
            && (cur == REMOVED || !hashingStrategy.equals(cur, key))) {
          index -= probe;
          if (index < 0) {
            index += length;
          }
          cur = set[index];
        }
        if (stats != null) {
          stats.endQueryResultsHashCollisionProbe(start);
        }
        return (cur != null && cur != REMOVED) ? -index - 1 : firstRemoved;
      }
      // if it's full, the key is already stored
      if (stats != null) {
        stats.endQueryResultsHashCollisionProbe(start);
      }
      return (cur != null && cur != REMOVED) ? -index - 1 : index;
    }
  }

  /**
   * After an insert, this hook is called to adjust the size/free values of the
   * set and to perform rehashing if necessary.
   */
  protected final void postInsertHook(boolean usedFreeSlot) {
    if (usedFreeSlot) {
      free--;
    }

    this.totalSize.incrementAndGet();
    // rehash whenever we exhaust the available space in the table
    if (++size > maxSize || free == 0) {
      // choose a new capacity suited to the new state of the table
      // if we've grown beyond our maximum size, increase capacity;
      // if we've exhausted the free spots, rehash to the same capacity,
      // which will free up any stale removed slots for reuse.
      final int capacity = capacity();
      double expansionFactor = Math.min(2.0,
          (3.0 - (2.0 * this.params.loadFactor)));
      int newCapacity = size > maxSize ? PrimeFinder
          .nextPrime((int)((double)capacity * expansionFactor)) : capacity;
      rehash(newCapacity);
      computeMaxSize(capacity(), this.params);
    }
  }

  protected void rehash(int newCapacity) {
    final Object[] oldSet = this.set;
    int oldCapacity = oldSet.length;
    final Object[] set = new Object[newCapacity];
    final TObjectHashingStrategy hashingStrategy = this.params.hashingStrategy;

    for (int i = oldCapacity; i-- > 0;) {
      if (oldSet[i] != null && oldSet[i] != REMOVED) {
        Object o = oldSet[i];
        int hash = THashParameters.computeHashCode(o, hashingStrategy);
        int index = insertionIndex(o, hash, set);
        if (index < 0) {
          throwObjectContractViolation(set[(-index - 1)], o);
        }
        set[index] = o;
      }
    }
    this.set = set;
  }

  protected final int capacity() {
    return this.set.length;
  }

  protected Object removeAt(int index) {
    this.totalSize.decrementAndGet();
    this.size--;
    final Object o = this.set[index];
    this.set[index] = REMOVED;
    return o;
  }

  protected void clearAt(int index) {
    this.set[index] = null;
  }

  /**
   * Convenience methods to use in throwing exceptions about badly behaved user
   * objects employed as keys. We have to throw an IllegalArgumentException with
   * a rather verbose message telling the user that they need to fix their
   * object implementation to conform to the general contract for
   * java.lang.Object.
   * 
   * @param o1
   *          the first of the equal elements with unequal hash codes.
   * @param o2
   *          the second of the equal elements with unequal hash codes.
   * @exception IllegalArgumentException
   *              the whole point of this method.
   */
  protected final void throwObjectContractViolation(Object o1, Object o2)
      throws IllegalArgumentException {
    throw new IllegalArgumentException(
        "Equal objects must have equal hashcodes. "
            + "During rehashing, map discovered that "
            + "the following two objects claim to be "
            + "equal (as in java.lang.Object.equals()) "
            + "but their hashCodes (or those calculated by "
            + "your TObjectHashingStrategy) are not equal."
            + "This violates the general contract of "
            + "java.lang.Object.hashCode(). See bullet point two "
            + "in that method's documentation. " + "object #1 ="
            + objToString(o1) + "; object #2 =" + objToString(o2));
  }

  protected String objToString(Object o) {
    if (o instanceof Object[]) {
      return java.util.Arrays.toString((Object[])o);
    }
    else {
      return String.valueOf(o);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNewValueCreated(boolean created) {
    this.newValueInsert = created;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isNewValueCreated() {
    return this.newValueInsert;
  }
}
