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
 
package com.pivotal.gemfirexd.internal.engine.store;

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.COMPACT_COMPOSITE_KEY_VALUE_BYTES;

import java.util.AbstractMap;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.gemstone.gemfire.internal.cache.SortedIndexKey;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
import com.gemstone.gemfire.internal.concurrent.SkipListNode;
import com.gemstone.gemfire.internal.concurrent.SkipListNodeFactory;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.key.IndexKeyComparator;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraIndexInfo;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

/**
 * A key for use in a local index. This key holds a reference to the bytes held
 * in the value, which uses less memory than keeping a copy of the bytes in the
 * value.
 * 
 * NOTE: This class is not final because ExtractingIndex extends
 * CompactCompositeIndexKey but {@link IndexKeyComparator} uses getClass()== in
 * the compare method, and IndexKeyComparator assumes that these are the only
 * two possibly types that can be passed to it. If this assumption changes, then
 * change {@link IndexKeyComparator#compare} method accordingly.
 * 
 * @author dsmith
 */
public class CompactCompositeIndexKey extends CompactCompositeKey implements
    SkipListNode<Object, Object>, SortedIndexKey {

  private static final long serialVersionUID = -5414797179905848493L;

  public CompactCompositeIndexKey(@Unretained(COMPACT_COMPOSITE_KEY_VALUE_BYTES) final Object value,
      final ExtraIndexInfo tabInfo) {
    super(value, tabInfo);
  }

  public CompactCompositeIndexKey(final DataValueDescriptor[] value,
      final ExtraIndexInfo tabInfo) throws StandardException {
    super(value, tabInfo);
  }

  public CompactCompositeIndexKey(final ExtraIndexInfo tabInfo,
      final DataValueDescriptor[] key) throws StandardException {
    super(tabInfo, tabInfo.getPrimaryKeyFormatter().generateBytes(key));
  }

  public CompactCompositeIndexKey(final ExtraIndexInfo tabInfo,
      final byte[] indexKeyBytes) {
    super(tabInfo, indexKeyBytes);
  }

  public final ExtraIndexInfo getIndexInfo() {
    return (ExtraIndexInfo)tableInfo;
  }

  @Override
  public final byte[] getKeyBytes() {
    return super.get();
  }

  public final int getLength() {
    int tries = 1;
    do {
      final byte[] kbytes = super.get();
      if (kbytes != null) {
        return kbytes.length;
      }
      final ExtraIndexInfo indexInfo = getIndexInfo();
      if (indexInfo != null) {
        @Retained @Released final Object vbytes = getValueByteSource();
        if (vbytes != null) {
          if (vbytes.getClass() == byte[].class) {
            final byte[] vbs = (byte[])vbytes;
            RowFormatter rf = indexInfo.getRowFormatter(vbs);
            return rf.getColumnsWidth(vbs,
                indexInfo.getPrimaryKeyFixedColumns(),
                indexInfo.getPrimaryKeyVarColumns(),
                indexInfo.getPrimaryKeyFormatter());
          }
          else {
            final OffHeapByteSource vbs = (OffHeapByteSource)vbytes;
            try {
              RowFormatter rf = indexInfo.getRowFormatter(vbs);
              return rf.getColumnsWidth(vbs,
                  indexInfo.getPrimaryKeyFixedColumns(),
                  indexInfo.getPrimaryKeyVarColumns(),
                  indexInfo.getPrimaryKeyFormatter());
            } finally {
              vbs.release();
            }
          }
        }
      }
      else {
        throw RegionEntryUtils
            .checkCacheForNullTableInfo("CompactCompositeIndexKey#getLength");
      }
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_TRIES);
    throw RegionEntryUtils
        .checkCacheForNullKeyValue("CompactCompositeIndexKey#getLength");
  }

  @Override
  public long estimateMemoryUsage() {
    final int bytesLen;
    final byte[] kbytes = super.get();
    if (kbytes != null) {
      bytesLen = kbytes.length;
    }
    else {
      // always assume that valueBytes points to a valid in-memory
      // RegionEntry value
      bytesLen = 0;
    }
    return bytesLen + ReflectionSingleObjectSizer.OBJECT_SIZE
        + ReflectionSingleObjectSizer.REFERENCE_SIZE /* ExtraInfo reference */;
  }

  /*
  /**
   * Mark this key as coming for insert (so then its value bytes can be used for
   * existing key if latter had been snapshotted before due to eviction).
   *
  public final void setForInsert() {
    // change the tableInfo to indicate key being used for insert, but avoid if
    // no valueBytes even in this key
    //if (this.valueBytes != null) {
    if (super.getValueBytes() != null) {
      final ExtraIndexInfo indexInfo = getIndexInfo();
      if (indexInfo != null) {
        this.tableInfo = indexInfo.getIndexInfoForInsert();
      }
    }
  }

  public final boolean forInsert() {
    final ExtraIndexInfo indexInfo = getIndexInfo();
    return indexInfo != null ? indexInfo.forInsert() : null;
  }
  */

  @Override
  @Retained
  public final Object getValueByteSource() {
    // return this.valueBytes;
    @Retained
    @Released
    // Only if retVal != currentVal
    Object retVal = null;
    int tries = 1;

    do {
      retVal = super.getValueByteSource();
      if (retVal != null) {
        final Class<?> valClass = retVal.getClass();
        if (valClass == byte[].class) {
          return retVal;
        }
        else if (OffHeapByteSource.isOffHeapBytesClass(valClass)) {
          final OffHeapByteSource bs = (OffHeapByteSource)retVal;
          if (bs.retain()) {
            @Unretained
            Object currentVal = super.getValueByteSource();
            if (currentVal != null && bs.equals(currentVal)) {
              return retVal;
            }
            else if (currentVal == null) {
              // key bytes must have been set
              bs.release();
              return null;
            }
            else {
              bs.release();
            }
          }
          else {
            // TODO:Asif:Fix this issue cleanly
            // Asif: There is some bug in the code where key bytes are set to
            // not null, but value bytes are not set to null which results in
            // inconsistency. For the time being fixing it forcefully here
            // Check if key bytes are not null
            byte[] keyBytes = this.getKeyBytes();
            if (keyBytes != null) {
              this.compareAndSetValueBytes(retVal, null);
            }
          }
        }
        else {
          return retVal;
        }
      }
      else {
        return null;
      }
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_TRIES);
    Misc.checkIfCacheClosing(null);
    throw new IllegalStateException("Unable to retain byte source=" + retVal
        + " for index key ");
  }

  /**
   * Compare this key with the given value bytes, to make sure that those
   * values bytes map to this same index key.
   * @param otherVBytes
   * @return true if otherVBytes has the same values for the fields that this
   * index key is considering.
   */
  public final boolean equalsValueBytes(Object otherVBytes) {
    final ExtraIndexInfo indexInfo = getIndexInfo();
    if (indexInfo != null) {
      final int[] keyPositions = indexInfo.getPrimaryKeyColumns();

      // Both the key bytes & value bytes cannot be null at the same
      // time.
      int tries = 1;
      do {
        final byte[] kbytes = this.getKeyBytes();
        final RowFormatter otherFormatter;
        final byte[] oVbytes;
        final OffHeapByteSource oVbs;
        if (otherVBytes == null || otherVBytes.getClass() == byte[].class) {
          oVbytes = (byte[])otherVBytes;
          oVbs = null;
          otherFormatter = indexInfo.getRowFormatter(oVbytes);
        }
        else {
          oVbytes = null;
          oVbs = (OffHeapByteSource)otherVBytes;
          otherFormatter = indexInfo.getRowFormatter(oVbs);
        }
        if (kbytes != null) {
          final RowFormatter keyFormatter = getKeyFormatter();
          if (oVbytes != null) {
            return RegionEntryUtils.compareRowBytesToKeyBytes(oVbytes,
                otherFormatter, kbytes, keyFormatter, keyPositions);
          }
          else {
            return RegionEntryUtils.compareRowBytesToKeyBytes(oVbs,
                otherFormatter, kbytes, keyFormatter, keyPositions);
          }
        }
        else {
          SimpleMemoryAllocatorImpl.skipRefCountTracking();
          @Retained @Released final Object vbs = getValueByteSource();
          SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          if (vbs != null) {
            if (vbs.getClass() == byte[].class) {
              final byte[] vbytes = (byte[])vbs;
              final RowFormatter rf = indexInfo.getRowFormatter(vbytes);
              if (oVbs == null) {
                return RegionEntryUtils.compareValueBytesToValueBytes(rf,
                    keyPositions, vbytes, oVbytes, otherFormatter);
              }
              else {
                return RegionEntryUtils.compareValueBytesToValueBytes(rf,
                    keyPositions, vbytes, oVbs, otherFormatter);
              }
            }
            else {
              final OffHeapByteSource vbytes = (OffHeapByteSource)vbs;
              try {
                final RowFormatter rf = indexInfo.getRowFormatter(vbytes);
                if (oVbs == null) {
                  return RegionEntryUtils.compareValueBytesToValueBytes(rf,
                      keyPositions, vbytes, oVbytes, otherFormatter);
                }
                else {
                  return RegionEntryUtils.compareValueBytesToValueBytes(rf,
                      keyPositions, vbytes, oVbs, otherFormatter);
                }
              } finally {
                SimpleMemoryAllocatorImpl.skipRefCountTracking();
                vbytes.release();
                SimpleMemoryAllocatorImpl.unskipRefCountTracking();
              }
            }
          }
        }
        if ((tries % MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= MAX_TRIES);
      throw new IllegalStateException("Unable to obtain either key bytes or "
          + "value bytes from the CCIK for ccik = " + this);
    }
    throw RegionEntryUtils
        .checkCacheForNullTableInfo("CompactCompositeIndexKey#equalsValueBytes(Object)");
  }

  // SkipListNode methods and fields. Since key itself is now the node, so this
  // results in overhead savings of 16-24 bytes per index entry.

  private volatile Object mapValue;
  private volatile CompactCompositeIndexKey next;
  /** version of this key which is incremented on each update to its value */
  private volatile int version;

  /** Updater for casValue */
  private static final AtomicReferenceFieldUpdater<CompactCompositeIndexKey,
      Object> valueUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
          CompactCompositeIndexKey.class, Object.class, "mapValue");

  /** Updater for casNext */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static final AtomicReferenceFieldUpdater<SkipListNode, SkipListNode>
      nextUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
          (Class)CompactCompositeIndexKey.class,
          (Class)CompactCompositeIndexKey.class, "next");

  private static final AtomicIntegerFieldUpdater<CompactCompositeIndexKey>
      versionUpdater = AtomicUpdaterFactory.newIntegerFieldUpdater(
          CompactCompositeIndexKey.class, "version");

  private CompactCompositeIndexKey(SkipListNode<Object, Object> next) {
    super((Object)null, (ExtraIndexInfo)null);
    this.mapValue = this;
    this.next = (CompactCompositeIndexKey)next;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getMapKey() {
    // this is itself both the key and node
    // null tableInfo indicates a marker node with null key
    return this.tableInfo != null ? this : null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getMapValue() {
    return this.mapValue;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getValidValue() {
    final Object v = this.mapValue;
    if (v != this && v != BASE_HEADER) {
      return v;
    }
    else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final SkipListNode<Object, Object> getNext() {
    return this.next;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setNext(SkipListNode<Object, Object> next) {
    this.next = (CompactCompositeIndexKey)next;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean casValue(Object cmp, Object val) {
    // update the version before updating the value so that any reader may not
    // miss the version change; it can mean multiple version changes for
    // single update but that will not hurt
    versionUpdater.incrementAndGet(this);
    return valueUpdater.compareAndSet(this, cmp, val);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean casNext(SkipListNode<Object, Object> cmp,
      SkipListNode<Object, Object> val) {
    return nextUpdater.compareAndSet(this, cmp, val);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int getVersion() {
    return this.version;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isMarker() {
    return this.mapValue == this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isBaseHeader() {
    return this.mapValue == BASE_HEADER;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean appendMarker(SkipListNode<Object, Object> f) {
    return casNext(f, new CompactCompositeIndexKey(f));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void helpDelete(SkipListNode<Object, Object> bi,
      SkipListNode<Object, Object> fi) {
    final CompactCompositeIndexKey b = (CompactCompositeIndexKey)bi;
    final CompactCompositeIndexKey f = (CompactCompositeIndexKey)fi;
    /*
     * Rechecking links and then doing only one of the
     * help-out stages per call tends to minimize CAS
     * interference among helping threads.
     */
    if (f == next && this == b.next) {
      if (f == null || f.mapValue != f) { // not already marked
        appendMarker(f);
      }
      else {
        b.casNext(this, f.next);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final SimpleImmutableEntry<Object, Object> createSnapshot() {
    final Object v = getValidValue();
    if (v != null) {
      // [sumedh] the next link can keep a large region of map alive via the key
      // given out below, but it should be fine since we do not store the index
      // keys for long and are always transient
      return new AbstractMap.SimpleImmutableEntry<Object, Object>(this, v);
    }
    else {
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void clear() {
    // clear mapValue to indicate that the key is no longer a node
    this.mapValue = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setTransientValue(Object value) {
    this.mapValue = value;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Object getTransientValue() {
    return this.mapValue;
  }

  public static SkipListNodeFactory<Object, Object> getNodeFactory() {
    return new NodeFactory();
  }

  private static final class NodeFactory implements
      SkipListNodeFactory<Object, Object>, java.io.Serializable {

    private static final long serialVersionUID = 3441201286289347935L;

    /**
     * {@inheritDoc}
     */
    @Override
    public SkipListNode<Object, Object> newNode(Object key, Object value,
        SkipListNode<Object, Object> next) {
      final CompactCompositeIndexKey ccik;
      if (key != null) {
        // key itself is the node
        ccik = (CompactCompositeIndexKey)key;
      }
      else {
        ccik = new CompactCompositeIndexKey((Object)null, null);
      }
      ccik.mapValue = value;
      ccik.next = (CompactCompositeIndexKey)next;
      return ccik;
    }
  }
}
