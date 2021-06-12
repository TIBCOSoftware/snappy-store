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
package com.pivotal.gemfirexd.internal.engine.store.offheap;

import java.io.IOException;
import java.util.List;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.ListOfDeltas;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.cache.RegionEntryContext;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.offheap.MemoryAllocator;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.DataAsAddress;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer.SerializableDelta;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;

/**
 * Utility class for preparing OffHeap objects for storage.
 * 
 * @author asif
 * 
 */
public class OffHeapRegionEntryUtils {
  private static final int MAX_BATCH_SIZE = 512;
  @Retained
  public static Object prepareValueForUpdate(
      @Unretained OffHeapRegionEntry entry, RegionEntryContext r, Object val,
      boolean valueContainsMetaData) {
    // TODO:Asif:Case of update for sure?
    final Class<?> valClass = val.getClass();
    // check if there are any lob columns attached with the current row
    // No need to increment 'use count' because the lock is taken on the
    // entry
    @Unretained Object prevValue = OffHeapRegionEntryHelper._getValue(entry);
    LogWriter logger = Misc.getCacheLogWriterNoThrow();

    SimpleMemoryAllocatorImpl.setReferenceCountOwner(entry);
    if(logger != null && logger.fineEnabled()) {
      logger.fine("OffHeapRegionEntryUtils:: prepareValueForUpdate");
    }
    try {
      if (valClass == byte[].class) {
        return prepareValueForCreate(r, (byte[])val, false);
      }
      else if (valClass == byte[][].class) {
        if(valueContainsMetaData) {
          return prepareValueForUpdateWithPossibleLobColumnsModified(r,
            prevValue, (byte[][])val);
        }else {
          return prepareValueForCreate(r, val, false);
        }
      }
      else if (Delta.class.isAssignableFrom(valClass)) {
        return prepareValueForDelta((Delta)val,
            SimpleMemoryAllocatorImpl.getAllocator());
      }
      else {
        throw new UnsupportedOperationException("what is it?" + val);
      }
    } finally {
      SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);
    }
  }

  public static Object prepareValueForCreate(RegionEntryContext r,
      final byte[] data, boolean skipLastRowIfByteArrayArray) {
    // fix for bug 47875
    MemoryAllocator ma = SimpleMemoryAllocatorImpl.getAllocator();
    if (data.length < OffHeapRegionEntryHelper.MAX_LENGTH_FOR_DATA_AS_ADDRESS) {
      long addr = OffHeapRegionEntryHelper.encodeDataAsAddress(data, false,
          false);
      if (addr != 0L) {
        return new DataAsAddress(addr);
      }
    }
    OffHeapRow mc = (OffHeapRow)ma.allocate(data.length, OffHeapRow.TYPE);
    mc.writeBytes(0, data);
    return mc;
  }

  public static OffHeapRowWithLobs prepareValueForCreate(
      RegionEntryContext r, byte[][] data,
      final boolean skipLastRowIfByteArrayArray) {

    // fix for bug 47875
    MemoryAllocator ma = SimpleMemoryAllocatorImpl.getAllocator();
    int numLobs = skipLastRowIfByteArrayArray ? data.length - 2
        : data.length - 1;
    // The zeroth byte array needs to be prefixed with the data of the lob
    // byte arrays

    int extraBytesLen = OffHeapRowWithLobs.calcExtraChunkBytes(numLobs);
    OffHeapRowWithLobs chunk = (OffHeapRowWithLobs) ma.allocate(extraBytesLen
        + data[0].length, OffHeapRowWithLobs.TYPE);
    chunk.setNumLobs(numLobs);
    for (int i = 1; i <= numLobs; ++i) {
      byte[] colBytes = data[i];
      long address = 0l;
      //TODO:ASif: Find below why the commented check gives problem in offHeapBlobTest.
      //It seems there is a subtle difference between null array & 0 length array.
      //if (colBytes != null && colBytes.length > 0) {
      if (colBytes != null) {
        StoredObject storedObject = ma.allocateAndInitialize(colBytes, false,
            false, OffHeapRow.TYPE);
        // assert storedObject instanceof OffHeapByteSource;
        if (storedObject instanceof DataAsAddress) {
          address = ((DataAsAddress) storedObject).getEncodedAddress();
        } else {
          address = ((OffHeapRow) storedObject).getMemoryAddress();
        }
      }
      chunk.setLobAddress(i, address);
    }
    chunk.writeBytes(numLobs * OffHeapRowWithLobs.LOB_ADDRESS_WIDTH, data[0]);
    return chunk;
  }
 
  public static Object prepareValueForCreate(RegionEntryContext r, Object val,
      final boolean skipLastRowIfByteArrayArray) {
    // fix for bug 47875
    MemoryAllocator ma = SimpleMemoryAllocatorImpl.getAllocator();

    final Class<?> valClass = val.getClass();
    if (valClass == byte[].class) {
      return prepareValueForCreate(r, (byte[])val, skipLastRowIfByteArrayArray);
    }
    else if (valClass == byte[][].class){
      return prepareValueForCreate(r, (byte[][])val, skipLastRowIfByteArrayArray);
    }
    else if (Delta.class.isAssignableFrom(valClass)) {
      return prepareValueForDelta((Delta)val, ma);
    }
    else {
      throw new IllegalStateException("Unknown type for offheap storage. "
          + "Type is " + valClass);
    }
  }
 
  private static OffHeapByteSource prepareValueForDelta(Delta delta,
      MemoryAllocator ma) {

    HeapDataOutputStream hdos = new HeapDataOutputStream();
    final boolean isListOfDeltas;
    try {
      if (delta instanceof ListOfDeltas) {
        List<Delta> deltas = ((ListOfDeltas) delta).getDeltas();
        isListOfDeltas = true;
        // TODO:Asif : use compact int
        hdos.writeInt(deltas.size());
        for (Delta aDelta : deltas) {
          SerializableDelta sd = (SerializableDelta) aDelta;
          InternalDataSerializer.invokeToData(sd, hdos);
        }
      } else {
        isListOfDeltas = false;
        InternalDataSerializer.invokeToData((SerializableDelta)delta, hdos);
      }
    } catch (IOException ioe) {
      throw new GemFireXDRuntimeException(ioe);
    }
    byte[] data = hdos.toByteArray();
    OffHeapByteSource chunk = (OffHeapByteSource) ma.allocate(data.length,
        isListOfDeltas ? OffHeapDeltas.TYPE : OffHeapDelta.TYPE);
    chunk.writeBytes(0, data);
    return chunk;
  }

  private static Object prepareValueForUpdateWithPossibleLobColumnsModified(
      RegionEntryContext r, Object prevValue, byte[][] val) {
    int numExistingLobCols;
    // boolean isZerothRowModified = (modifiedArraysData[0] & (0x80 >> 0)) != 0;
    FormatableBitSet rowsModified = new FormatableBitSet(val[val.length - 1]);
    boolean is0thRowModified = rowsModified.isSet(0);
    boolean isAnyLobModified = rowsModified.anySetBit(0) != -1;

    if (prevValue instanceof OffHeapByteSource) {
      OffHeapByteSource currentChunk = (OffHeapByteSource) prevValue;
      numExistingLobCols = currentChunk.readNumLobsColumns(true);
      if (isAnyLobModified) {
        if (is0thRowModified) {
          // If 0th row is modified a new address is to be obtained. new
          // addresses also obtained
          // lob cols modified. For unmodfied lob cols, use count needs to be
          // increased.
          // the unmodified lobs addresses need to be copied.
          return prepareValueFor0thRowAndLobsModified(r, currentChunk, val,
              rowsModified, numExistingLobCols);
        } else {
          // If 0th row is not modified, the new address is same as old address,
          // so
          // increase the use count of old address , so that update does not
          // release the
          // old address. Fill in the new addresses of the modified lobs
          return prepareValueForOnlyLobsModified(r, currentChunk, val,
              rowsModified, numExistingLobCols);
        }
      } else {
        // this is same as update with lob cols unmodified
        return prepareValueForUpdateWithUnmodifiedLobColumns(currentChunk,
            val[0], numExistingLobCols);
      }
    }
    else if (prevValue instanceof DataAsAddress || prevValue == null
        || prevValue instanceof Token) {
      // Treat it as create
      if (isAnyLobModified) {
        return prepareValueForCreate(r, val, true /* skip last row */);
      } else {
        return prepareValueForCreate(r, val[0], false /*
                                                       * for single byte array
                                                       * does not matter
                                                       */);
      }
    }
    else {
      Assert.fail("prepareValueForUpdateWithUnmodifiedLobColumns: unknown "
          + "previous value of type " + prevValue.getClass() + ": " + prevValue);
      // never reached
      return null;
    }
  }

  @Retained
  private static Object prepareValueForOnlyLobsModified(RegionEntryContext r,
      final OffHeapByteSource currentChunk, byte[][] val,
      FormatableBitSet rowsModified, int numExistingLobCols) {
    int numLobs = val.length - 2;
    @Retained final OffHeapRowWithLobs chunk;
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();
    // Check if there is a need to obtain new main row address
    boolean reuseFullOldRowData = numExistingLobCols > 0;
    //TODO:Asif: Avoid using ByteBuffer wrapper instead have utility methods
    // which do the ops on byte array
    //ByteBuffer zerothRowWrapper = null;
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    if(logger != null && logger.fineEnabled()) {
      logger.fine("OffHeapRegionEntryUtils:: prepareValueForOnlyLobsModified:" +
          "flag reuseFullOldRowData="+ reuseFullOldRowData + " Num existing lobs = "+ numExistingLobCols
          + " . Num Lobs in byte[][] = " + numLobs);
    }
    if (reuseFullOldRowData) {
      byte[] zerothRowBytes = currentChunk.getFullDataBytes();
      chunk = (OffHeapRowWithLobs) ma.allocate(zerothRowBytes.length, OffHeapRowWithLobs.TYPE);
      chunk.writeBytes(0, zerothRowBytes);
      chunk.initNumLobColumns();
    } else {
      int extraBytesLen = OffHeapRowWithLobs.calcExtraChunkBytes(numLobs);
      byte[] zerothRowBytes = currentChunk.getRowBytes(); // OFFHEAP optimize: no need to copy this to a byte[] if it is off-heap. Instead get its length and then do a off-heap to off-heap copy.
      chunk = (OffHeapRowWithLobs) ma.allocate(extraBytesLen + zerothRowBytes.length, OffHeapRowWithLobs.TYPE);
      chunk.setNumLobs(numLobs);
      chunk.writeBytes(numLobs * OffHeapRowWithLobs.LOB_ADDRESS_WIDTH, zerothRowBytes);
    }

    for (int i = 1; i <= numLobs; ++i) {
      if (rowsModified.isSet(i)) {
        
        if(logger != null && logger.fineEnabled()) {
          logger.fine("OffHeapRegionEntryUtils:: prepareValueForOnlyLobsModified:" +
              "Lob number="+ i + " is modified");
        }
        long address = 0l;
        byte[] colBytes = val[i];

        if (colBytes != null ) {
          StoredObject storedObject = ma.allocateAndInitialize(colBytes, false, false, OffHeapRow.TYPE);
          // assert storedObject instanceof OffHeapByteSource;
          if (storedObject instanceof DataAsAddress) {
            address = ((DataAsAddress) storedObject).getEncodedAddress();
          } else {
            address = ((OffHeapRow) storedObject).getMemoryAddress();
          }

        }
        chunk.setLobAddress(i, address);
        /*if(reuseFullOldRowData) {
          zerothRowBuffer.writeLong(2 + (i - 1) * 8, address);
        }else {
          chunk.writeLong(2 + (i - 1) * 8, address);
        }*/
      }else {
        //For this lob address , it is not modified, so we need to increase its 
        // use count
        if(reuseFullOldRowData) {
         // long unmodifiedLobAddress = chunk.readLong(2 + (i - 1) * 8);
          long unmodifiedLobAddress = chunk.readAddressForLob(i);

          if(logger != null && logger.fineEnabled()) {
            logger.fine("OffHeapRegionEntryUtils:: prepareValueForOnlyLobsModified:" +
                "Lob address to reuse="+ Long.toHexString(unmodifiedLobAddress) + " for lob number ="+i);
          }
          if (unmodifiedLobAddress != 0l
              && OffHeapRegionEntryHelper.isOffHeap(unmodifiedLobAddress)) {
            if (!Chunk.retain(unmodifiedLobAddress)) {
              throw new IllegalStateException("Unable to use address "
                  + Long.toHexString(unmodifiedLobAddress));
            }

          }
        }
      }
    }
    /*if(reuseFullOldRowData) {
      
      chunk.writeBytes(0, zerothRowWrapper.array()); 
    }*/
    return chunk;
    

  }

  @Retained
  private static Object prepareValueFor0thRowAndLobsModified(
      RegionEntryContext r, OffHeapByteSource currentChunk, byte[][] val,
      FormatableBitSet rowsModified, int numExistingLobs) {
    int numLobs = val.length - 2;
    // The zeroth byte array needs to be prefixed with the data of the lob byte
    // arrays
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    if(logger != null && logger.fineEnabled()) {
      logger.fine("OffHeapRegionEntryUtils:: prepareValueFor0thRowAndLobsModified:" +
      		" Existing Lobs="+ numExistingLobs );
    }
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();
    int extraBytesLen = OffHeapRowWithLobs.calcExtraChunkBytes(numLobs);
    @Retained OffHeapRowWithLobs chunk = (OffHeapRowWithLobs) ma.allocate(extraBytesLen
        + val[0].length, OffHeapRowWithLobs.TYPE);
    chunk.setNumLobs(numLobs);
    for (int i = 1; i <= numLobs; ++i) {
      long address = 0l;
      if (rowsModified.isSet(i)) {
        
        if(logger != null && logger.fineEnabled()) {
          logger.fine("OffHeapRegionEntryUtils:: prepareValueFor0thRowAndLobsModified: " +
          		"Lob number ="+i + " is modified" );
        }
        byte[] colBytes = val[i];

        if (colBytes != null ) {
          StoredObject storedObject = ma.allocateAndInitialize(colBytes, false, false, OffHeapRow.TYPE);
          // assert storedObject instanceof OffHeapByteSource;
          if (storedObject instanceof DataAsAddress) {
            address = ((DataAsAddress) storedObject).getEncodedAddress();
          } else {
            address = ((OffHeapRow) storedObject).getMemoryAddress();
          }
        }

      } else {
        // the lob is not modified, copy the old address , if existingNumLobs >
        // 0
        if(logger != null && logger.fineEnabled()) {
          logger.fine("OffHeapRegionEntryUtils:: prepareValueFor0thRowAndLobsModified: " +
              "Lob number ="+i + " is not modified" );
        }
        if (numExistingLobs > 0) {
          address = currentChunk.readAddressForLob(i);
          if(logger != null && logger.fineEnabled()) {
            logger.fine("OffHeapRegionEntryUtils:: prepareValueFor0thRowAndLobsModified: " +
                "Existing Lob address ="+Long.toHexString(address) );
          }
          if(address != 0l && OffHeapRegionEntryHelper.isOffHeap(address)) {
            if(!Chunk.retain(address)) {
              throw new IllegalStateException("Unable to use lob with address = "
                  + Long.toHexString(address));
            }
          }else {
            if(logger != null && logger.fineEnabled()) {
              logger.fine("OffHeapRegionEntryUtils:: prepareValueFor0thRowAndLobsModified: " +
                  "Existing Lob address ="+Long.toHexString(address) + " is not offheap" );
            }

          }
        }
      }
      chunk.setLobAddress(i, address);
    }
    chunk.writeBytes(numLobs * OffHeapRowWithLobs.LOB_ADDRESS_WIDTH, val[0]);
    return chunk;
  }

  @Retained
  private static OffHeapByteSource prepareValueForUpdateWithUnmodifiedLobColumns(
      OffHeapByteSource currentChunk, byte[] newVal, int numLobs) {
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();
    if(numLobs == 0) {
      @Retained OffHeapByteSource chunk = (OffHeapByteSource) ma.allocate(newVal.length, OffHeapRow.TYPE);
      chunk.writeBytes(0, newVal);
      return chunk;
    } else {
      int extraBytes = OffHeapRowWithLobs.calcExtraChunkBytes(numLobs);
      @Retained OffHeapRowWithLobs chunk = (OffHeapRowWithLobs) ma.allocate(extraBytes + newVal.length, OffHeapRowWithLobs.TYPE);
      chunk.setNumLobs(numLobs);
      LogWriter logger = Misc.getCacheLogWriterNoThrow();
      for (int i = 1; i <= numLobs; ++i) {
        long address = currentChunk.readAddressForLob(i);
        if(logger != null && logger.fineEnabled()) {
          logger.fine("OffHeapRegionEntryUtils:: prepareValueForUpdateWithUnmodifiedLobColumns:" +
              "Existing lob address = "+ Long.toHexString(address) + " for lob number="+i);
        }
        chunk.setLobAddress(i, address);
        // Increment the use count of Lob addresses so that they do not get
        // released
        // when the current address is freed
        if (address != 0 && OffHeapRegionEntryHelper.isOffHeap(address)) {
          if(!Chunk.retain(address)) {
            throw new IllegalStateException("Unable to use the lob address="
                + Long.toHexString(address));
          }
        }else {
          if(logger != null && logger.fineEnabled()) {
            logger.fine("OffHeapRegionEntryUtils:: prepareValueForUpdateWithUnmodifiedLobColumns:" +
                " lob address = "+ Long.toHexString(address) + " is not stored offheap");
          }
        }
      }
      chunk.writeBytes(numLobs * OffHeapRowWithLobs.LOB_ADDRESS_WIDTH, newVal);
      return chunk;
    }
  }

  public static Object getHeapRowForInVMValue(OffHeapRegionEntry re) {

    SimpleMemoryAllocatorImpl.skipRefCountTracking();
    @Retained @Released Object val = RegionEntryUtils
        .convertOffHeapEntrytoByteSourceRetain(re, null, null, false, true);
    SimpleMemoryAllocatorImpl.unskipRefCountTracking();
    try {
      if (val instanceof OffHeapByteSource) {
        return ((OffHeapByteSource)val).getValueAsDeserializedHeapObject();
      }
      else {
        return val;
      }
    } finally {
      OffHeapHelper.releaseWithNoTracking(val);
    }
  }

  public static boolean isValidValueForGfxdOffHeapStorage(Object value) {
    if (value != null) {
      final Class<?> valClass = value.getClass();
      return valClass == byte[].class || valClass == byte[][].class
        || Delta.class.isAssignableFrom(valClass);
    }
    return false;
  }

  /**
   * Used to fill the batch byte array by reading bytes from offheap starting
   * from the next batch offset location
   * 
   * @param batch
   *          byte[] to fill
   * @param source
   *          The target offheap byte source to fill data from
   * @param batchNum
   *          The previous batch number which was filled starting from 0
   * @param totalLen
   *          The total length of the bytes to be read from the offheap location
   * @param totalBatchNum
   *          The total mnumber of batches that is needed to completely read the
   *          data
   * @param baseOffset
   *          The starting position in OffHeapByteSource to read the data from
   * @return  int indicating the bytes read          
   */
  public static int fillBatch(byte[] batch, OffHeapByteSource source,
      int batchNum, int totalLen, int totalBatchNum, final int baseOffset) {
    int numBytesToRead = (batchNum + 1) == totalBatchNum ? totalLen - batchNum
        * batch.length : batch.length;
    source.readBytes(baseOffset + batchNum * batch.length, batch, 0, numBytesToRead);
    return numBytesToRead;
  }

  /**
   * Used to fill the batch byte array by reading bytes from offheap starting
   * from the end location & going in reverse direction. The order of the
   * elements is not reversed. It is just that the batch fill starts by taking
   * the last n elements where n is the batch size.
   * 
   * @param batch
   *          byte[] to fill
   * @param source
   *          The target offheap byte source to fill data from
   * @param batchNum
   *          The previous batch number which was filled starting from 0
   * @param totalLen
   *          The total length of the bytes to be read from the offheap location
   * @param totalBatchNum
   *          The total mnumber of batches that is needed to completely read the
   *          data
   * @param endOffSet
   *          The end offset in OffHeapByteSource to read the data from
   * @return int indicating the bytes read
   */
  public static int fillBatchInReverse(byte[] batch, OffHeapByteSource source,
      int batchNum, int totalLen, int totalBatchNum, final int endOffSet) {
    int numBytesToRead = (batchNum + 1) == totalBatchNum ? totalLen - batchNum
        * batch.length : batch.length;
    source.readBytes(
        endOffSet + 1 - (batchNum * batch.length + numBytesToRead), batch, 0,
        numBytesToRead);
    return numBytesToRead;
  }

  public static int calculateBatchSize(int totalLength) {
    return totalLength < OffHeapRegionEntryUtils.MAX_BATCH_SIZE ? totalLength
        : OffHeapRegionEntryUtils.MAX_BATCH_SIZE;
  }
  
  public static int calculateNumberOfBatches(int totalLengthToRead,
      int batchSize) {
   if(batchSize == 0 ) {
     return 0;
   }
    return totalLengthToRead / batchSize
        + (totalLengthToRead % batchSize > 0 ? 1 : 0);
  }


}
