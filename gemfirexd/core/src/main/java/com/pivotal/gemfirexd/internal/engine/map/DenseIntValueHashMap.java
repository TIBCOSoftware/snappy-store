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
package com.pivotal.gemfirexd.internal.engine.map;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;

/**
 * A compact hash map with any key object but only with int value.
 * This avoids Integer object creation and key object deserialization
 * depends on {@link DenseHashMapSerializer} implementation. The interface offers
 * opportunity to avoid full key deserialization during hashCode computation, equal comparison
 * and rehashing.
 * <p/>
 * Per Entry overhead is 4 bytes (pointer to byte[]). Its by experiment found
 * the best put/get performance and memory usage tradeoff is achieved with simple key and fixed
 * width value serialization. A base64 encoded key (assuming ascii only) or multiple entries packed
 * together into a single entry (a.k.a separate chaining) did not yield much memory benefit whereas
 * performance was significantly effected. In fact, for separate chaining memory consumption increased due to
 * length prefixed every entry consuming 4 more bytes.
 * <p/>
 * Concurrency is at the Segment level. Number of segments by default is 8 times the number of
 * cpu cores and should be sufficient for parallelism.
 * <p/>
 * This is based on open addressing and suffers usual primary clustering problem. This might show up
 * even more because of LoadFactor being 0.85 by default unlike proposed 0.50 but compensates for it during expansion
 * which brings down the LoadFactor to 0.65. Due to sparsity of the byte[][] total memory consumed for 1 million
 * entries shows 12 bytes per entry overhead when measured using runtime memory usage.
 * <p/>
 */
public class DenseIntValueHashMap<K> {

  private static final byte[] TOMBSTONE = new byte[0];

  protected final DenseHashMapSerializer<K> serializer;

  private float loadFactor = 0.85f;

  private final DenseIntValueHashMap.Segment<K>[] segments;

  private static final boolean TRACE = true;

  public DenseIntValueHashMap() {
    this(new DHMDefaultSerializer(), 32, -1);
  }

  public DenseIntValueHashMap(int initialCapacity) {
    this(new DHMDefaultSerializer(), initialCapacity, -1);
  }

  public DenseIntValueHashMap(int initialCapacity,
      int concurrency) {
    this(new DHMDefaultSerializer(), initialCapacity, concurrency);
  }

  public DenseIntValueHashMap(DenseHashMapSerializer serializer) {
    this(serializer, 32, -1);
  }

  public DenseIntValueHashMap(DenseHashMapSerializer serializer,
      int initialCapacity) {
    this(serializer, initialCapacity, -1);
  }

  public DenseIntValueHashMap(DenseHashMapSerializer serializer,
      int initialCapacity,
      int concurrency) {

    this.serializer = serializer;
    if (concurrency <= 0) {
      concurrency = Runtime.getRuntime().availableProcessors() * 8;
    }
    segments = new DenseIntValueHashMap.Segment[concurrency];

    int capacity = 1;
    // make capacity power of 2 instead of just taking a rounded off number.
    // a rounded off number isn't good for probing.
    while (capacity < initialCapacity)
      capacity *= 2;

    for (int i = 0; i < segments.length; i++) {
      segments[i] = new DenseIntValueHashMap.Segment(i, serializer, loadFactor, capacity);
    }
  }

  public final int size() {
    int sz = 0;
    for (Segment s : segments) {
      if (s != null) {
        sz += s.size;
      }
    }
    return sz;
  }

  public void put(K key, int value) {
    int hash = serializer.getHashCode(key);
    segments[hash & (segments.length - 1)].putEntry(key, hash, value, false);
  }

  public void putIfAbsent(K key, int value) {
    int hash = serializer.getHashCode(key);
    segments[hash & (segments.length - 1)].putEntry(key, hash, value, true);
  }

  public int get(K key) {
    int hash = serializer.getHashCode((K)key);
    return segments[hash & (segments.length - 1)].getEntry(key, hash);
  }

  public int remove(K key) {
    int hash = serializer.getHashCode((K)key);
    return segments[hash & (segments.length - 1)].removeEntry(key, hash);
  }

  public final static int readInt(byte[] kv, int offset, int numBytes) {
    return RowFormatter.readInt(kv, offset, numBytes);
  }

  public final static void writeInt(byte[] kv, int val, int offset, int numBytes) {
    assert val < 0xffff || numBytes != 2;
    RowFormatter.writeInt(kv, val, offset, numBytes);
  }

  static final class Segment<K> extends ReentrantLock {

    private final int id;
    private final DenseHashMapSerializer serializer;
    private final float loadFactor;

    private AtomicReferenceArray<byte[]> table;
    private int size = 0;
    private int numBits;
    private int numRehash = 0;

    protected Segment(int id, DenseHashMapSerializer serializer, float loadFactor, int initCapacity) {
      this.id = id;
      this.serializer = serializer;
      this.loadFactor = loadFactor;
      table = new AtomicReferenceArray<>(initCapacity);
      this.numBits = Integer.bitCount(initCapacity);
    }

    void putEntry(K key, final int keyHash, int value, boolean putIfAbsent) {
      lock();

      try {
        int index = probeLinear(key, keyHash, table, numBits, null, true);

        if (index < 0) {
          if (putIfAbsent) {
            return;
          }
          index = -index;
        }

        table.set(index, serializer.serialize(key, value));
        if (index >= 0) {
          size++;
        }

        ensureCapacity();

      } finally {
        unlock();
      }
    }

    private int getEntry(K key, int keyHash) {

      final AtomicReferenceArray<byte[]> tab = this.table;
      final int numBits = this.numBits;

      int index = probeLinear(key, keyHash, tab, numBits, null, false);
      if (index < 0) {
        index = -index;
      }

      final byte[] entry = tab.get(index);
      if(entry == null || entry == TOMBSTONE) {
        return -1;
      }

      return serializer.deserializeValue(entry);
    }

    public int removeEntry(K key, int keyHash) {
      lock();
      try {
        final int numBits = this.numBits;

        int index = probeLinear(key, keyHash, table, numBits, null, false);
        if (index < 0) {
          index = -index;
        }

        final byte[] entry = table.getAndSet(index, TOMBSTONE);
        if(entry == null || entry == TOMBSTONE) {
          return -1;
        }

        int retVal = serializer.deserializeValue(entry);
        size--;

        reduceCapacity();

        return retVal;
      } finally {
        unlock();
      }
    }


    private final int probeLinear(final K key,
        int keyHash,
        AtomicReferenceArray<byte[]> table,
        final int numBits,
        final byte[] entry1,
        final boolean forInsert) {

      assert key != null || entry1 != null : "key not supplied... ";

      final boolean useIndexInTable = entry1 != null;

      final int maxOffset = table.length() - 1;

      final int hashCode = useIndexInTable ?
          serializer.getHashCode(entry1) :
          keyHash;

      int index = hashCode & maxOffset;

      //byte[] entry2 = table[index];
      byte[] entry2 = table.get(index);

      if (entry2 == null) {
        return index;
      } else if (entry2 == TOMBSTONE) {
        if (forInsert) {
          return index;
        }
      } else if (useIndexInTable) {
        if (serializer.equals(entry1, entry2)) {
          return -index;
        }
      } else if (serializer.equals(key, entry2)) {
        return -index;
      }

      int stepValue = (hashCode >>> numBits) & maxOffset;
      stepValue = stepValue > 0 ? stepValue : 1;
      index = (index + stepValue) & maxOffset;

      final StringBuilder sb;

      if(TRACE) {
        sb = new StringBuilder();
      }

      int beginIdx = index;
      for (; ; ) {
        //entry2 = table[index];
        entry2 = table.get(index);

        if (entry2 == null) {
          return index;
        } else if (entry2 == TOMBSTONE) {
          if (forInsert) {
            return index;
          } else {
            return -index;
          }
        } else if (useIndexInTable) {
          if (serializer.equals(entry1, entry2)) {
            return -index;
          }
        } else if (serializer.equals(key, entry2)) {
          return -index;
        }

        index = index + 1 & maxOffset;

        assert (beginIdx != index) : "exhausted all the slots. This should have never happened.";
      }
    }

    private void ensureCapacity() {
      if ((double)size / table.length() > loadFactor) {
        int newCapacity = table.length();
        // expand until loadFactor goes down 20% lesser than configured.
        while ((double)size / newCapacity > (loadFactor - 0.2)) {
          newCapacity *= 2;
        }

        rehash(newCapacity);
      }
    }

    private void reduceCapacity() {
      int length = table.length();
      // are we dropping beyond 50% usage and 25% of loadFactor.
      while (length >= 2 && (double)size / length < (loadFactor / 4) && size < (length / 2)) {
        length /= 2;
      }

      if (length < table.length()) {
        rehash(length);
      }
    }

    private final void rehash(final int newCapacity) {
      numRehash++;
      final AtomicReferenceArray<byte[]> newTable = new AtomicReferenceArray<>(newCapacity);
      final int newNumBits = Integer.bitCount(newCapacity);

      for (int i = 0, len = table.length(); i < len; i++) {
        final byte[] entry = table.get(i);
        if (entry == null || entry == TOMBSTONE) {
          continue;
        }
        int index = probeLinear(null, -1, newTable, newNumBits, entry, false);
//        System.out.println(numRehash + " id=" + id + ", key=" + serializer.getKey(entry) + " @ " + index);
        newTable.set(index, entry);
      }

      this.table = newTable;
      this.numBits = newNumBits;
    }

    @Override
    public String toString() {
      return "Segment=" + id;
    }

  } // Segment

}
