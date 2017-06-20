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

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.REGION_ENTRY_VALUE;

import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.AbstractRegionEntry;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraInfo;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

/**
 * Base class for compact composite keys. A compact composite key is 
 * a key that holds the value bytes for the entry and has information
 * on how to extract the key columns from the value bytes. It uses
 * those key columns in equals, hashCode, and compareTo.
 * 
 * @author soubhik, swale, dsmith
 */
public abstract class CompactCompositeKey extends AtomicReference<byte[]>
    implements Serializable {

  private static final long serialVersionUID = 0L;

  /**
   * Array pointing to value portion of the Region.
   * This is used via <code>valBytesUpdater</code> so is not unused.
   * 
   * CompactCompositeKey (or its subclasses) do not increment the reference count.
   * Instead, they are bound by the lifetime of the RegionEntry and use the RegionEntry
   * reference count.
   */
  @Retained(REGION_ENTRY_VALUE)
  private volatile Object valueBytes;

  /**
   * The table information that provides the schema used to interpret the bytes.
   * 
   * TODO: PERF: this can be removed from here and be only in CCRK while for
   * CCIK we don't normally need it and it can use a different class with that
   * in it for hashCode/equals if required (e.g. for irf map of new entries);
   * all remaining methods can pass through ExtraInfo where required (e.g.
   * IndexKeyComparator can keep it in constructor)
   */
  public transient ExtraInfo tableInfo;

  /**
   * Atomic reference updater for {@link #valueBytes} used for CAS operation on
   * that field.  This data, if off-heap, uses the RegionEntry reference count and does
   * not increment.
   */
  @Unretained(REGION_ENTRY_VALUE)
  private static final AtomicReferenceFieldUpdater<CompactCompositeKey, Object>
      valBytesUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
          CompactCompositeKey.class, Object.class, "valueBytes");

  public static final int MAX_TRIES = AbstractRegionEntry.MAX_READ_TRIES;
  public static final int MAX_TRIES_YIELD =
      AbstractRegionEntry.MAX_READ_TRIES_YIELD;

  public CompactCompositeKey(@Unretained(REGION_ENTRY_VALUE) final Object value,
      final ExtraInfo tabInfo) {

    this.valueBytes = value;
    this.tableInfo = tabInfo;
    // allow null tableInfo for marker objects
    //assert this.tableInfo != null;
  }

  public CompactCompositeKey(final ExtraInfo tabInfo,
      final byte[] keyBytes) {
    super(keyBytes);
    // tableInfo can be null in this case
    this.tableInfo = tabInfo;
  }

  public CompactCompositeKey(final DataValueDescriptor[] value,
      final ExtraInfo tabInfo) throws StandardException {
    super(tabInfo.getPrimaryKeyFormatter().generateBytesWithIdenticalDVDTypes(value));
    this.tableInfo = tabInfo;
    assert this.tableInfo != null;
  }

  public CompactCompositeKey(final DataValueDescriptor value,
      final ExtraInfo tabInfo) throws StandardException {
    super(tabInfo.getPrimaryKeyFormatter().generateBytesWithIdenticalDVDType(value));
    this.tableInfo = tabInfo;
    assert this.tableInfo != null;
  }

  public CompactCompositeKey() {
    super();
  }

  public final int nCols() {
    final ExtraInfo tabInfo = this.tableInfo;
    if (tabInfo != null) {
      return tabInfo.getPrimaryKeyColumns().length;
    }
    throw RegionEntryUtils
        .checkCacheForNullTableInfo("CompactCompositeKey#nCols()");
  }

  public final ExtraInfo getExtraInfo() {
    return this.tableInfo;
  }

  public final DataValueDescriptor getKeyColumn(final int index) {
    int tries = 1;
    do {
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      @Retained @Released final Object vbs = getValueByteSource();
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      final DataValueDescriptor dvd;
      try {
        dvd = RegionEntryUtils.entryKeyColumn(this.getKeyBytes(), vbs,
            this.tableInfo, index);
      } finally {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        this.releaseValueByteSource(vbs);
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      }
      if (dvd != null) {
        return dvd;
      }
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_TRIES);
    throw RegionEntryUtils
        .checkCacheForNullKeyValue("CompactCompositeKey#getKeyColumn(int)");
  }

  public final void setKeyColumn(final DataValueDescriptor dvd, final int index) {
    int tries = 1;
    do {
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      @Retained @Released final Object vbs = getValueByteSource();
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      try {
        if (RegionEntryUtils.entrySetKeyColumn(dvd, this.getKeyBytes(),
            vbs, this.tableInfo, index)) {
          return;
        }
      } finally {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        this.releaseValueByteSource(vbs);
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      }
      // false return value indicates retry
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_TRIES);
    throw RegionEntryUtils
        .checkCacheForNullKeyValue("CompactCompositeKey#getKeyColumn(int)");
  }

  public final void getKeyColumns(final DataValueDescriptor[] keys) {
    int tries = 1;
    do {
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      @Retained @Released final Object vbs = getValueByteSource();
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      try {
        if (RegionEntryUtils.entryKeyColumns(this.getKeyBytes(), vbs,
            this.tableInfo, keys)) {
          return;
        }
      } finally {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        this.releaseValueByteSource(vbs);
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      }
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_TRIES);
    throw RegionEntryUtils.checkCacheForNullKeyValue(
        "CompactCompositeKey#getKeyColumns(DataValueDescriptor[])");
  }

  public final void getKeyColumns(final Object[] keyObjects)
      throws StandardException {
    final ExtraInfo tabInfo = this.tableInfo;
    if (tabInfo != null) {
      int tries = 1;
      do {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        @Retained @Released final Object vbytes = getValueByteSource();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (vbytes != null) {
          if (vbytes.getClass() == byte[].class) {
            final byte[] bytes = (byte[])vbytes;
            final RowFormatter rf = tabInfo.getRowFormatter(bytes);
            final int[] keyPositions = tabInfo.getPrimaryKeyColumns();
            for (int index = 0; index < keyObjects.length; ++index) {
              keyObjects[index] = rf.getAsObject(keyPositions[index], bytes,
                  null);
            }
            return;
          }
          else {
            final OffHeapByteSource ohbytes = (OffHeapByteSource)vbytes;
            try {
              final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
              final int bytesLen = ohbytes.getLength();
              final long memAddr = ohbytes.getUnsafeAddress(0, bytesLen);

              final RowFormatter rf = tabInfo.getRowFormatter(ohbytes);
              final int[] keyPositions = tabInfo.getPrimaryKeyColumns();
              for (int index = 0; index < keyObjects.length; ++index) {
                final int colIndex = keyPositions[index] - 1;
                final ColumnDescriptor cd = rf.columns[colIndex];
                if (!cd.isLob) {
                  keyObjects[index] = rf.getAsObject(colIndex, cd, unsafe,
                      memAddr, bytesLen, ohbytes, null);
                }
                else {
                  keyObjects[index] = rf.getAsObject(colIndex + 1,
                      (OffHeapRowWithLobs)ohbytes, null);
                }
              }
            } finally {
              SimpleMemoryAllocatorImpl.skipRefCountTracking();
              ohbytes.release();
              SimpleMemoryAllocatorImpl.unskipRefCountTracking();
            }
            return;
          }
        } else {
          final byte[] kbytes = this.getKeyBytes();
          if (kbytes != null) {
            final RowFormatter rf = tabInfo.getPrimaryKeyFormatter();
            for (int index = 0; index < keyObjects.length; ++index) {
              keyObjects[index] = rf.getAsObject(index + 1, kbytes, null);
            }
            return;
          }
        }
        if ((tries % MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= MAX_TRIES);
      throw RegionEntryUtils.checkCacheForNullKeyValue(
          "CompactCompositeKey#getKeyColumns(Object[])");
    }
    else {
      throw RegionEntryUtils.checkCacheForNullTableInfo(
          "CompactCompositeKey#getKeyColumns(Object[])");
    }
  }

  /**
   * Return the {@link DataTypeDescriptor} for the key column at given 0-based
   * index.
   */
  public final DataTypeDescriptor getKeyColumnType(final int index) {
    final ExtraInfo tabInfo = this.tableInfo;
    if (tabInfo != null) {
      return tabInfo.getPrimaryKeyFormatter().columns[index].columnType;
    }
    throw RegionEntryUtils.checkCacheForNullTableInfo(
        "CompactCompositeKey#getKeyColumnType(int)");
  }

  public final byte[] snapshotKeyFromValue() {
    return snapshotKeyFromValue(true);
  }

  public final byte[] snapshotKeyFromValue(boolean storeInternally) {
    int tries = 1;
    for (;;) {
      byte[] kbytes = super.get();
      if (kbytes == null) {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        @Retained @Released final Object vbytes = getValueByteSource();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (vbytes != null) {
          byte[] hvbytes = null;
          OffHeapByteSource ohvbytes = null;
          try {
            if (vbytes.getClass() == byte[].class) {
              hvbytes = (byte[])vbytes;
            }
            else {
              ohvbytes = (OffHeapByteSource)vbytes;
            }
            final ExtraInfo tableInfo = this.tableInfo;
            if (tableInfo != null) {
              if (ohvbytes == null) {
                kbytes = tableInfo.getRowFormatter(hvbytes).generateColumns(
                    hvbytes, tableInfo.getPrimaryKeyColumns(),
                    tableInfo.getPrimaryKeyFormatter());
              }
              else {
                kbytes = tableInfo.getRowFormatter(ohvbytes).generateColumns(
                    ohvbytes, tableInfo.getPrimaryKeyColumns(),
                    tableInfo.getPrimaryKeyFormatter());
              }
              if (storeInternally) {
                if (compareAndSetKeyBytes(null, kbytes)) {
                  // The below operation will fail for OffHeapRegionEntry which
                  // is set as value but that should be
                  // ok. TODO:Asif: clean this portion with appropriate
                  // functionality going into its class.
                  // Make it better oops.
                  compareAndSetValueBytes(vbytes, null);
                  return kbytes;
                }
                else {
                  continue; // key changed so go back and retry
                }
              }
              else {
                return kbytes;
              }
            } else {
              throw RegionEntryUtils.checkCacheForNullTableInfo(
                  "CompactCompositeKey#snapshotKeyBytes(Object)");
            }
          } finally {
            if (ohvbytes != null) {
              SimpleMemoryAllocatorImpl.skipRefCountTracking();
              ohvbytes.release();
              SimpleMemoryAllocatorImpl.unskipRefCountTracking();
            }
          }
        }
        else if ((tries % MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
        else if (tries > MAX_TRIES) {
          throw RegionEntryUtils
              .checkCacheForNullKeyValue("RegionEntry#snapshotKeyFromValue");
        }
        else {
          // If the value bytes is of type OffHeapRegionEntry , check if the raw
          // key has the data
          @Unretained(REGION_ENTRY_VALUE) Object vbs = valBytesUpdater.get(this);
          if (vbs instanceof OffHeapRegionEntry) {
            Object keyBytes = ((AbstractRegionEntry)vbs).getRawKey();
            if (keyBytes != null) {
              if (storeInternally) {
                if (compareAndSetKeyBytes(null, kbytes)) {
                  // No need to replace OffHeapRegionEntry value
                  return (byte[])keyBytes;
                }
                else {
                  continue; // key changed so go back and retry
                }
              }
              else {
                return (byte[])keyBytes;
              }
            }
          }
          continue; // retry till one of key or value is non-null
        }
      }
      else {
        return kbytes;
      }
    }
  }

  public final boolean update(final Object vbytes, @Unretained final Object expectedBytes) {
    if (vbytes != null) {
      for (;;) {
        // get the key bytes before setting value bytes, so we can detect
        // a concurrent snapshotKeyFromValue that may null value bytes
        final byte[] kbytes = super.get();
        if (compareAndSetValueBytes(expectedBytes, vbytes)) {
          // do not attempt to nullify the keyBytes if ExtraInfo has not
          // yet been initialized
          // if key bytes is already null then nothing to do
          if (kbytes != null && getKeyColumns() != null) {
            if (compareAndSetKeyBytes(kbytes, null)) {
              return true;
            }
            else {
              // at this point it is possible that both key bytes and value
              // bytes are non-null, so retry the whole to set key bytes to null
              // (if value has not been changed by another thread)
              continue;
            }
          }
          else {
            return true;
          }
        }
        else {
          // valueBytes already changed by another thread
          return false;
        }
      }
    }
    else {
      Assert.fail("CCIK.update(byte[], byte[]): unexpected null vbytes");
      // never reached
      return false;
    }
  }

  public final void writeKeyBytes(DataOutput out) throws IOException {
    int tries = 1;
    do {
      final byte[] kbytes = this.getKeyBytes();
      if (kbytes != null) {
        DataSerializer.writeByteArray(kbytes, out);
        return;
      }
      else {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        @Retained @Released final Object vbytes = this.getValueByteSource();// this.valueBytes;
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (vbytes != null) {
          byte[] hvbytes = null;
          OffHeapByteSource ohvbytes = null;
          try {
            if (vbytes.getClass() == byte[].class) {
              hvbytes = (byte[])vbytes;
            }
            else {
              ohvbytes = (OffHeapByteSource)vbytes;
            }
            final ExtraInfo tableInfo = this.tableInfo;
            if (tableInfo != null) {
              // serialize key bytes directly from the row bytes
              final RowFormatter pkFormatter = tableInfo
                  .getPrimaryKeyFormatter();
              if (ohvbytes == null) {
                tableInfo.getRowFormatter(hvbytes).serializeColumns(hvbytes,
                    out, tableInfo.getPrimaryKeyFixedColumns(),
                    tableInfo.getPrimaryKeyVarColumns(),
                    pkFormatter.getNumOffsetBytes(),
                    pkFormatter.getOffsetDefaultToken(), pkFormatter);
              }
              else {
                tableInfo.getRowFormatter(ohvbytes).serializeColumns(ohvbytes,
                    out, tableInfo.getPrimaryKeyFixedColumns(),
                    tableInfo.getPrimaryKeyVarColumns(),
                    pkFormatter.getNumOffsetBytes(),
                    pkFormatter.getOffsetDefaultToken(), pkFormatter);
              }
              return;
            }
            else {
              throw RegionEntryUtils.checkCacheForNullTableInfo(
                  "CompactCompositeKey#writeKeyBytes");
            }
          } finally {
            if (ohvbytes != null) {
              SimpleMemoryAllocatorImpl.skipRefCountTracking();
              ohvbytes.release();
              SimpleMemoryAllocatorImpl.unskipRefCountTracking();
            }
          }
        }
        else if (this == getTokenKey()) { // case of TOKEN_KEY
          DataSerializer.writeByteArray(null, out);
          return;
        }
      }
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_TRIES);
    throw RegionEntryUtils
        .checkCacheForNullKeyValue("CompactCompositeKey#writeKeyBytes");
  }

  protected Object getTokenKey() {
    return null;
  }

  /**
   * Returns the hash for this key from the raw key or value bytes.
   * 
   * @return an integer as the hash code for this key.
   */
  @Override
  public int hashCode() {
    int tries = 1;
    do {
      final byte[] kbytes = getKeyBytes();
      OffHeapByteSource vbs = null;
      try {
        if (kbytes != null) {
          return RegionEntryUtils.entryHashCode(kbytes, null, null,
              this.tableInfo);
        }
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        @Retained @Released final Object vbytes = getValueByteSource();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (vbytes != null) {
          if (vbytes.getClass() == byte[].class) {
            return RegionEntryUtils.entryHashCode(null, (byte[])vbytes, null,
                this.tableInfo);
          }
          else {
            vbs = (OffHeapByteSource)vbytes;
            return RegionEntryUtils.entryHashCode(null, null, vbs,
                this.tableInfo);
          }
        }
      } catch (IllegalAccessException e) {
        // indicates retry
      } finally {
        if (vbs != null) {
          SimpleMemoryAllocatorImpl.skipRefCountTracking();
          vbs.release();
          SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        }
      }
      if ((tries % MAX_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= MAX_TRIES);
    throw RegionEntryUtils
        .checkCacheForNullKeyValue("CompactCompositeKey#hashCode");
  }

  /**
   * Equals implementation for this class. Compares this
   * {@link CompactCompositeKey} to specified argument.
   * 
   * @return true if the argument is not null and argument's dvd array contains
   *         the same data value.
   */
  @Override
  public final boolean equals(final Object o) {
    if(o == null) {
      return false;
    }
    if (this == o) {
      return true;
    }
    // expect the incoming object to be a CompactCompositeKey or RowLocation
    if (o instanceof CompactCompositeKey) {
      int tries = 1;
      do {
        final byte[] kbytes = getKeyBytes();
        OffHeapByteSource vbs = null;
        try {
          if (kbytes != null) {
            return RegionEntryUtils.entryEqualsKey(kbytes, null, null, null,
                this.tableInfo, (CompactCompositeKey)o);
          }
          SimpleMemoryAllocatorImpl.skipRefCountTracking();
          @Retained @Released final Object vbytes = getValueByteSource();
          SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          if (vbytes != null) {
            if (vbytes.getClass() == byte[].class) {
              return RegionEntryUtils.entryEqualsKey(null, null,
                  (byte[])vbytes, null, this.tableInfo, (CompactCompositeKey)o);
            }
            else {
              vbs = (OffHeapByteSource)vbytes;
              return RegionEntryUtils.entryEqualsKey(null, null, null, vbs,
                  this.tableInfo, (CompactCompositeKey)o);
            }
          }
        } catch (IllegalAccessException e) {
          // indicates retry
        } finally {
          if (vbs != null) {
            SimpleMemoryAllocatorImpl.skipRefCountTracking();
            vbs.release();
            SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          }
        }
        if ((tries % MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= MAX_TRIES);
      throw RegionEntryUtils
          .checkCacheForNullKeyValue("CompactCompositeKey#equals");
    }
    else if (o instanceof RowLocation) {
      return o.equals(this);
    }
    else if (o instanceof RegionKey) {
      return false;
    }
    Assert.fail("other object is not a CompactCompositeKey "
        + "or RowLocation or RegionKey but " + o.getClass().getName());
    // never reached
    return false;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("tableinfo(").append(this.tableInfo != null).append(").");
    sb.append(getClass().getSimpleName()).append('@')
        .append(Integer.toHexString(System.identityHashCode(this))).append("=");
    SimpleMemoryAllocatorImpl.skipRefCountTracking();
    @Retained @Released final Object valBytes = getValueByteSource();
    SimpleMemoryAllocatorImpl.unskipRefCountTracking();
    try {
      RegionEntryUtils.entryKeyString(this.getKeyBytes(), valBytes,
          this.tableInfo, sb);
    } finally {
      SimpleMemoryAllocatorImpl.skipRefCountTracking();
      this.releaseValueByteSource(valBytes);
      SimpleMemoryAllocatorImpl.unskipRefCountTracking();
    }
    return sb.toString();
  }

  public final byte[] getValueBytes() {
    SimpleMemoryAllocatorImpl.skipRefCountTracking();
    @Retained @Released final Object vbs = getValueByteSource();
    SimpleMemoryAllocatorImpl.unskipRefCountTracking();
    if (vbs != null) {
      if (vbs.getClass() == byte[].class) {
        return (byte[])vbs;
      }
      else {
        final OffHeapByteSource ohbytes = (OffHeapByteSource)vbs;
        try {
          return ohbytes.getRowBytes();
        } finally {
          SimpleMemoryAllocatorImpl.skipRefCountTracking();
          ohbytes.release();
          SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        }
      }
    }
    else {
      return null;
    }
  }

  /**
   * Returns true if getValueBytes would return null.
   * For off-heap this method and non-null valueBytes this method will not copy all the bytes to heap.
   */
  public boolean isValueNull() {
    return getRawValueByteSource() == null;
  }

  public byte[] getKeyBytes() {
    return super.get();
  }

  public final RowFormatter getKeyFormatter() {
    final ExtraInfo tableInfo = this.tableInfo;
    if (tableInfo != null) {
      return tableInfo.getPrimaryKeyFormatter();
    }
    return null;
  }

  protected final void setKeyBytes(final byte[] kbytes) {
    super.set(kbytes);
  }

  protected final boolean compareAndSetKeyBytes(final byte[] expect,
      final byte[] update) {
    return super.compareAndSet(expect, update);
  }

  protected final boolean compareAndSetValueBytes(
      @Unretained final Object expect,
      @Unretained(REGION_ENTRY_VALUE) final Object update) {

    boolean testOk = false;
    @Unretained(REGION_ENTRY_VALUE)
    final Object old = valBytesUpdater.get(this);
    if (old != null) {
      final Class<?> oldClass = old.getClass();
      if (oldClass == byte[].class) {
        if (old == expect) {
          testOk = true;
        }
        else if (expect instanceof byte[]) {
          // These byte arrays can be from conversion of DataAsAddress to
          // byte[]. There references may be unequal but still they may be
          // equal. We can check array equals only if byte[] length is < 8 , a
          // requirement for DataAsAddress generated byte[]. But even if we
          // don't reset byte[] it will be no harm, but lets do it any ways
          if (((byte[])old).length < OffHeapRegionEntryHelper.MAX_LENGTH_FOR_DATA_AS_ADDRESS
              && ((byte[])expect).length < OffHeapRegionEntryHelper.MAX_LENGTH_FOR_DATA_AS_ADDRESS) {
            testOk = Arrays.equals((byte[])old, (byte[])expect);
          }
        }
      }
      else if (OffHeapByteSource.isOffHeapBytesClass(oldClass)) {
        if (old == expect) {
          testOk = true;
        }
        else if (expect instanceof OffHeapByteSource
            && ((OffHeapByteSource)old).getMemoryAddress() ==
                ((OffHeapByteSource)expect).getMemoryAddress()) {
          testOk = true;
        }
      }
      // Do not replace OffHeapRegionEntry from regionKey
      else if (OffHeapRegionEntry.class.isAssignableFrom(oldClass)) {
        return false;
      }
      else {
        testOk = (old == expect);
      }
    }
    else if (expect == null) {
      testOk = true;
    }
    else {
      return false;
    }

    if (testOk) {
      return valBytesUpdater.compareAndSet(this, old, update);
    }
    else {
      return false;
    }
  }

  protected final int[] getKeyColumns() {
    final ExtraInfo containerInfo = this.tableInfo;
    if (containerInfo != null) {
      return containerInfo.getPrimaryKeyColumns();
    }
    return null;
  }

  public final void setValueBytes(@Unretained(REGION_ENTRY_VALUE) Object valBytes) {
    valBytesUpdater.set(this, valBytes);
  }

  public final void releaseValueByteSource(@Released Object vbs) {
    OffHeapHelper.release(vbs);
  }

/**
 * Must be overridden!  
 * @return a retained off-heap value in sub-classes.  This abstract base class returns
 * an unretained off-heap value.
 */
  @Retained // Sub-classes
  @Unretained(REGION_ENTRY_VALUE)
  public Object getValueByteSource() {
    return valBytesUpdater.get(this);
  }

  @Unretained(REGION_ENTRY_VALUE)
  public final Object getRawValueByteSource() {
    return valBytesUpdater.get(this);
  }

  public abstract long estimateMemoryUsage();
}
