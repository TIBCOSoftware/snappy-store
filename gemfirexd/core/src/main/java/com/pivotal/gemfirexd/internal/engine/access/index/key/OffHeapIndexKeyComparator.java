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

package com.pivotal.gemfirexd.internal.engine.access.index.key;

import java.util.Comparator;

import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraInfo;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.ExtractingIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeUtilities;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;

/**
 * A {@link Comparator} for index keys. It allows for partial key matches.
 * 
 * @author yjing, dsmith, swale, asif
 * @since gfxd 1.0
 */
public class OffHeapIndexKeyComparator extends IndexKeyComparator {

  private static final long serialVersionUID = 2728235215718814912L;

  /**
   * @param nCols
   *          number of key columns in the key
   */
  public OffHeapIndexKeyComparator(int nCols, boolean[] columnOrders,
      boolean caseSensitive) {
    super(nCols, columnOrders, caseSensitive);
  }

  /**
   * Compare two arrays of DataValueDescriptor, or
   * {@link CompactCompositeIndexKey}s or combination from low index to high
   * index. If one array is shorter than the other, than do a partial key match.
   * 
   * @return <0 if first key is smaller, 0 if the two keys are equal, >0 if the
   *         first key is larger
   * 
   * @see DataValueDescriptor#compare
   */
  @Override
  public int compare(final Object key, final Object mapKey) {

    if (key == mapKey) {
      return 0;
    }

    // second one is usually the map key (probably always)
    final CompactCompositeIndexKey ccik1, ccik2;
    DataValueDescriptor dvd1, dvd2;
    final DataValueDescriptor[] dvds1, dvds2;
    // if rows are not of the same length, then find the shorter length
    int cmpLen = this.nCols;
    int rowLength;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final Class<?> keyClass = key.getClass();
    final Class<?> mapKeyClass = mapKey.getClass();
    @Retained @Released OffHeapByteSource ccik1BS = null;
    @Retained @Released OffHeapByteSource ccik2BS = null;

    // all type checks inlined here to avoid multiple instanceof invocations
    if (mapKeyClass == CompactCompositeIndexKey.class) {
      ccik2 = (CompactCompositeIndexKey)mapKey;
      dvd2 = null;
      dvds2 = null;
      if (keyClass == CompactCompositeIndexKey.class
          || keyClass == ExtractingIndexKey.class) {
        ccik1 = (CompactCompositeIndexKey)key;
        dvd1 = null;
        dvds1 = null;
      }
      else if (keyClass == DataValueDescriptor[].class) {
        ccik1 = null;
        dvd1 = null;
        dvds1 = (DataValueDescriptor[])key;
        rowLength = dvds1.length;
        if (rowLength < cmpLen) {
          cmpLen = rowLength;
        }
      }
      else {
        ccik1 = null;
        dvd1 = (DataValueDescriptor)key;
        dvds1 = null;
        cmpLen = 1;
      }
    }
    else if (keyClass == CompactCompositeIndexKey.class
        || keyClass == ExtractingIndexKey.class) {
      ccik1 = (CompactCompositeIndexKey)key;
      dvd1 = null;
      dvds1 = null;
      if (mapKeyClass == DataValueDescriptor[].class) {
        ccik2 = null;
        dvd2 = null;
        dvds2 = (DataValueDescriptor[])mapKey;
        rowLength = dvds2.length;
        if (rowLength < cmpLen || cmpLen <= 0) {
          cmpLen = rowLength;
        }
      }
      else {
        ccik2 = null;
        dvd2 = (DataValueDescriptor)mapKey;
        dvds2 = null;
        cmpLen = 1;
      }
    }
    else {
      ccik1 = null;
      ccik2 = null;
      if (keyClass == DataValueDescriptor[].class) {
        dvd1 = null;
        dvds1 = (DataValueDescriptor[])key;
        rowLength = dvds1.length;
        if (rowLength < cmpLen || cmpLen <= 0) {
          cmpLen = rowLength;
        }
      }
      else {
        dvd1 = (DataValueDescriptor)key;
        dvds1 = null;
        cmpLen = 1;
      }
      if (mapKeyClass == DataValueDescriptor[].class) {
        dvd2 = null;
        dvds2 = (DataValueDescriptor[])mapKey;
        rowLength = dvds2.length;
        if (rowLength < cmpLen) {
          cmpLen = rowLength;
        }
      }
      else {
        dvd2 = (DataValueDescriptor)mapKey;
        dvds2 = null;
        cmpLen = 1;
      }
    }
    try {

    // get the bytes+formatter from CCIK keys in one round so as to avoid
    // doing it for every column
    byte[] ccik1Bytes = null, ccik2Bytes = null;
    long ccik1MemAddr = 0, ccik2MemAddr = 0;
    int ccik1BSLen = -1, ccik2BSLen = -1;
    RowFormatter ccik1RF = null, ccik2RF = null;
    int[] key1Positions = null, key2Positions = null;

    if (ccik1 != null) {
      final ExtraInfo indexInfo = ccik1.tableInfo;
      if (indexInfo == null) {
        throw RegionEntryUtils
            .checkCacheForNullTableInfo("IndexKeyComparator#compare");
      }
      int tries = 1;
      for (;;) {
        byte[] kbytes =  ccik1.getKeyBytes();
        if (kbytes != null) {
          ccik1Bytes = kbytes;
          ccik1RF = indexInfo.getPrimaryKeyFormatter();
          key1Positions = this.keySnapshotPositions;
          break;
        }
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        final Object vbytes = ccik1.getValueByteSource();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (vbytes != null) {
          final Class<?> vclass = vbytes.getClass();
          if (vclass == OffHeapRow.class) {
            final OffHeapRow ohrow = (OffHeapRow)vbytes;
            ccik1BS = ohrow;
            ccik1BSLen = ohrow.getLength();
            ccik1MemAddr = ohrow.getUnsafeAddress(0, ccik1BSLen);
            ccik1RF = indexInfo.getRowFormatter(ccik1MemAddr, ohrow);
          }
          else if (vclass == OffHeapRowWithLobs.class) {
            final OffHeapRowWithLobs ohrow = (OffHeapRowWithLobs)vbytes;
            ccik1BS = ohrow;
            ccik1BSLen = ohrow.getLength();
            ccik1MemAddr = ohrow.getUnsafeAddress(0, ccik1BSLen);
            ccik1RF = indexInfo.getRowFormatter(ccik1MemAddr, ohrow);
          }
          else {
            kbytes = (byte[])vbytes;
            ccik1Bytes = kbytes;
            ccik1RF = indexInfo.getRowFormatter(kbytes);
          }
          key1Positions = indexInfo.getPrimaryKeyColumns();
          break;
        }
        if ((tries++ % CompactCompositeIndexKey.MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
        if (tries > CompactCompositeIndexKey.MAX_TRIES) {
          throw RegionEntryUtils
              .checkCacheForNullKeyValue("IndexKeyComparator#compare");
        }
      }
    }
    if (ccik2 != null) {
      final ExtraInfo indexInfo = ccik2.tableInfo;
      if (indexInfo == null) {
        throw RegionEntryUtils
            .checkCacheForNullTableInfo("IndexKeyComparator#compare");
      }
      int tries = 1;
      for (;;) {
        byte[] kbytes =  ccik2.getKeyBytes();
        if (kbytes != null) {
          ccik2Bytes = kbytes;
          ccik2RF = indexInfo.getPrimaryKeyFormatter();
          key2Positions = this.keySnapshotPositions;
          break;
        }
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        final Object vbytes = ccik2.getValueByteSource();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (vbytes != null) {
          final Class<?> vclass = vbytes.getClass();
          if (vclass == OffHeapRow.class) {
            final OffHeapRow ohrow = (OffHeapRow)vbytes;
            ccik2BS = ohrow;
            ccik2BSLen = ohrow.getLength();
            ccik2MemAddr = ohrow.getUnsafeAddress(0, ccik2BSLen);
            ccik2RF = indexInfo.getRowFormatter(ccik2MemAddr, ohrow);
          }
          else if (vclass == OffHeapRowWithLobs.class) {
            final OffHeapRowWithLobs ohrow = (OffHeapRowWithLobs)vbytes;
            ccik2BS = ohrow;
            ccik2BSLen = ohrow.getLength();
            ccik2MemAddr = ohrow.getUnsafeAddress(0, ccik2BSLen);
            ccik2RF = indexInfo.getRowFormatter(ccik2MemAddr, ohrow);
          }
          else {
            kbytes = (byte[])vbytes;
            ccik2Bytes = kbytes;
            ccik2RF = indexInfo.getRowFormatter(kbytes);
          }
          key2Positions = indexInfo.getPrimaryKeyColumns();
          break;
        }
        if ((tries++ % CompactCompositeIndexKey.MAX_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
        if (tries > CompactCompositeIndexKey.MAX_TRIES) {
          throw RegionEntryUtils
              .checkCacheForNullKeyValue("IndexKeyComparator#compare");
        }
      }
    }

    for (int i = 0; i < cmpLen; i++) {
      int cmp;
      if (ccik1 != null) {
        final int rfPos1 = key1Positions[i];
        final int rfIndex1 = rfPos1 - 1;
        final ColumnDescriptor cd = ccik1RF.getColumnDescriptor(rfIndex1);
        final long offsetWidth1 = ccik1MemAddr != 0 ? ccik1RF
            .getOffsetAndWidth(rfIndex1, unsafe, ccik1MemAddr, ccik1BSLen, cd)
            : ccik1RF.getOffsetAndWidth(rfPos1, ccik1Bytes, cd);
        if (ccik2 != null) {
          final long offsetWidth2 = ccik2MemAddr != 0 ? ccik2RF
              .getOffsetAndWidth(key2Positions[i] - 1, unsafe, ccik2MemAddr,
                  ccik2BSLen, cd) : ccik2RF.getOffsetAndWidth(
              key2Positions[i], ccik2Bytes, cd);
          cmp = DataTypeUtilities.compare(unsafe, ccik1Bytes, ccik1MemAddr,
              ccik1BS, ccik2Bytes, ccik2MemAddr, ccik2BS, offsetWidth1,
              offsetWidth2, false, caseSensitive, cd);
        }
        else {
          // key1 is definitely DVD or DVD[]
          if (dvds2 != null) {
            dvd2 = dvds2[i];
          }
          // reverse the result of compare here since lhs,rhs are swapped
          // below due to dvd2 being passed as lhsDVD
          cmp = DataTypeUtilities.compare(unsafe, dvd2, ccik1Bytes,
              ccik1MemAddr, ccik1BS, offsetWidth1, false, caseSensitive, cd);
          if (cmp < 0) {
            return this.columnOrders[i];
          }
          else if (cmp > 0) {
            return -this.columnOrders[i];
          }
          else {
            continue;
          }
        }
      }
      else {
        // key1 is definitely DVD or DVD[]
        if (dvds1 != null) {
          dvd1 = dvds1[i];
        }
        if (ccik2 != null) {
          final int rfPos2 = key2Positions[i];
          final int rfIndex2 = rfPos2 - 1;
          final ColumnDescriptor cd = ccik2RF.getColumnDescriptor(rfIndex2);
          final long offsetWidth2 = ccik2MemAddr != 0 ? ccik2RF
              .getOffsetAndWidth(rfIndex2, unsafe, ccik2MemAddr, ccik2BSLen,
                  cd) : ccik2RF.getOffsetAndWidth(rfPos2, ccik2Bytes, cd);
          // ccik1 is null below
          cmp = DataTypeUtilities.compare(unsafe, dvd1, ccik2Bytes,
              ccik2MemAddr, ccik2BS, offsetWidth2, false, caseSensitive, cd);
        }
        else {
          if (dvds2 != null) {
            dvd2 = dvds2[i];
          }
          if (this.caseSensitive || !(dvd1 instanceof SQLChar)) {
            cmp = dvd1.compare(dvd2);
          }
          else {
            cmp = ((SQLChar)dvd1).compareIgnoreCase(dvd2);
          }
        }
      }
      if (cmp < 0) {
        return -this.columnOrders[i];
      }
      else if (cmp > 0) {
        return this.columnOrders[i];
      }
    }

    // we have a partial key match

    //Trick to replace the bytes of a key when
    //removing the value from the index.
    if (ccik1 != null) {
      if (keyClass == ExtractingIndexKey.class) {
        if (ccik2 != null) {
          ((ExtractingIndexKey)key).afterCompareWith(ccik2);
        }
      }
      /* now handling this via MapCallback.afterUpdate
      // if we have snapshotted the key previously and this is an insert
      // then replace with the inserted value bytes
      else if (ccik2 != null) {
        // we always assume that ccik1 is the incoming key while ccik2 is the
        // map key (this is always the case for our CSLM implementation and all
        // JDK ones too)
        if (ccik1.forInsert() && vbytesToRelease2 == null) {
          final Object vbytes = vbytesToRelease1;
          if (vbytes != null) {
            ccik2.update(vbytes, null);
          }
        }
      }
      */
    }

    return 0;
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "IndexKeyComparator#compare: unexpected exception", se);
    } finally {
      if (ccik1BS != null) {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        ccik1BS.release();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      }
      if (ccik2BS != null) {
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        ccik2BS.release();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
      }
    }
  }
}
