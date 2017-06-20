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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.io.UTFDataFormatException;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.DataInputStreamBase;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.BytesAndBitsForCompactor;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.ChunkType;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
 * 
 * @author asifs
 * 
 */
public abstract class OffHeapByteSource extends Chunk implements ByteSource {

  protected OffHeapByteSource(long address, int chunkSize, ChunkType chunkType) {
    super(address, chunkSize, chunkType);
  }

  protected OffHeapByteSource(long address) {
    super(address);
  }

  final byte[] getFullDataBytes() {
    int dataSize = getDataSize();
    byte[] result = new byte[dataSize];
    super.readBytes(0, result, 0, dataSize);
    SimpleMemoryAllocatorImpl.getAllocator().getStats().incReads();
    return result;
  }

  public long getUnsafeAddress(int offset, int size) {
    return super.getUnsafeBaseAddress(offset, size);
  }

  public long getUnsafeAddress() {
    return this.memoryAddress + OFF_HEAP_HEADER_SIZE;
  }

  @Override
  public final byte readByte(int offset) {
    return super.readByte(offset + getOffsetAdjustment());
    //return UnsafeMemoryChunk.readAbsoluteByte(getBaseDataAddress() + offset);
  }

  @Override
  public final void readBytes(int offset, byte[] bytes, int bytesOffset,
      int size) {
    super.readBytes(offset + getOffsetAdjustment(), bytes, bytesOffset, size);
  }

  @Override
  public final byte[] getRowBytes() {
    return getBaseRowBytes(
        getBaseDataAddress() + getOffsetAdjustment(), getLength());
  }

  public static final boolean isOffHeapBytesClass(Class<?> c) {
    return c == OffHeapRow.class || c == OffHeapRowWithLobs.class;
  }

  @Override
  @Unretained
  public abstract Object getDeserializedValue(Region r, RegionEntry re);

  public void toData(final DataOutput out) throws IOException {
    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER,
          "OffHeapByteSource.toData: serializing offheap byte source=" + this);
    }
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int offsetAdjustment = getOffsetAdjustment();
    final int len = getDataSize(unsafe, this.memoryAddress) - offsetAdjustment;

    serializeBaseRowBytes(unsafe, getBaseDataAddress() + offsetAdjustment, len,
        out);
    SimpleMemoryAllocatorImpl.getAllocator().getStats().incReads();
  }

  @Override
  public void sendTo(final DataOutput out) throws IOException {
    out.writeByte(DSCODE.BYTE_ARRAY);
    toData(out);
  }

  // TODO:Asif: Optimize the serialization
  @Override
  public final void fillSerializedValue(BytesAndBitsForCompactor wrapper,
      byte userBits) {
    byte[] value = getRawBytes();
    wrapper.setData(value, userBits, value.length, true);
  }

  @Override
  public final boolean equals(Object o) {
    return super.equals(o);
  }

  @Override
  public final int hashCode() {
    return super.hashCode();
  }

  @Override
  public int getLength() {
    return getDataSize(UnsafeMemoryChunk.getUnsafeWrapper(),
        this.memoryAddress);
  }

  /**
   * The bit representing the value will always have mark as false, but the
   * behaviour of OffHeapByteSource representing serialized data or not will be
   * governed by the presence or absence of lob columns. If lob columns columns
   * count is 0 , it will not be serialized while writing on wire else it will
   * represent serialized data
   */
  @Override
  public final boolean isSerialized() {
    return this.isWithLobs();
  }

  public boolean isWithLobs() {
    return false;
  }

  public abstract int readNumLobsColumns(boolean throwExceptionOnWrongSource);

  public long readAddressForLob(int index) {
    throw new UnsupportedOperationException(
        "readAddressForLob only supported on OffHeapRowWithLobs but this is: "
            + this.getClass());
  }

  public long readAddressForLobIfPresent(int index) {
    return 0L;
  }

  @Unretained
  public final Object getGfxdByteSource(int index) {
    if (index != 0) {
      long address = readAddressForLobIfPresent(index);
      if (address != 0) {
        // It is Ok to create it without incrementing use count because these
        // lob locations
        // are governed by the base row access & use count would be increased
        // for base row.
        return RegionEntryUtils.convertLOBAddresstoByteSourceNoRetain(address);
      }
      else {
        return null;
      }
    }
    else {
      return this;
    }
  }

  public final byte[] getGfxdBytes(int index) {
    if (index != 0) {
      long address = readAddressForLobIfPresent(index);
      if (address != 0) {
        // It is Ok to create it without incrementing use count because these
        // lob locations
        // are governed by the base row access & use count would be increased
        // for base row.
        return RegionEntryUtils.convertLOBAddresstoBytes(
            UnsafeMemoryChunk.getUnsafeWrapper(), address);
      }
      else {
        return null;
      }
    }
    else {
      return getRowBytes();
    }
  }

  public final void serializeGfxdBytes(int index, DataOutput out)
      throws IOException {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    if (index != 0) {
      long address = readAddressForLobIfPresent(index);
      if (address != 0) {
        // It is Ok to create it without incrementing use count because these
        // lob locations
        // are governed by the base row access & use count would be increased
        // for base row.
        RegionEntryUtils.serializeLOBAddresstoBytes(unsafe, address, out);
      }
      else {
        InternalDataSerializer.writeByteArray(null, out);
      }
    }
    else {
      serializeBaseRowBytes(unsafe, getBaseDataAddress()
          + getOffsetAdjustment(), getLength(), out);
    }
  }

  public final void serializeGfxdBytesWithStats(int index, DataOutput out)
      throws IOException {
    serializeGfxdBytes(index, out);
    SimpleMemoryAllocatorImpl.getAllocator().getStats().incReads();
  }

  public final byte[][] getRowByteArrays() {
    int numLobs = readNumLobsColumns(true);
    byte[][] value = new byte[numLobs + 1][];
    value[0] = getRowBytes();
    for (int i = 1; i <= numLobs; ++i) {
      value[i] = getGfxdBytes(i);
    }
    return value;
  }

  public int getOffsetAdjustment() {
    return 0;
  }

  public static byte[] getBaseRowBytes(final long baseAddr, final int bytesLen) {
    // If the row does not have any lobs, the offheap byte source is not
    // serialized, else it is serialized
    byte[] result = new byte[bytesLen];
    UnsafeMemoryChunk.readUnsafeBytes(baseAddr, result, bytesLen);
    SimpleMemoryAllocatorImpl.getAllocator().getStats().incReads();
    return result;
  }

  public static void serializeBaseRowBytes(final UnsafeWrapper unsafe,
      final long baseAddr, final int bytesLen, final DataOutput out)
      throws IOException {
    // If the row does not have any lobs, the offheap byte source is not
    // serialized, else it is serialized
    InternalDataSerializer.writeArrayLength(bytesLen, out);
    OffHeapRegionEntryHelper.copyBytesToDataOutput(unsafe, baseAddr, bytesLen,
        out);
    SimpleMemoryAllocatorImpl.getAllocator().getStats().incReads();
  }

  @Override
  protected byte[] getRawBytes() {
    // If the row does not have any lobs, the offheap byte source is not
    // serialized
    return getRowBytes();
  }

  @Override
  public String toString() {
    return this.toStringForOffHeapByteSource();
  }

  public final DataInput getDataInputStreamWrapper(final int startPos,
      final int streamSize) {
    SimpleMemoryAllocatorImpl.getAllocator().getStats().incReads();
    return new DataInputStreamBase(startPos) {

      private final long baseAddr = OffHeapByteSource.this.getUnsafeAddress();

      /**
       * {@inheritDoc}
       */
      @Override
      public final int read() throws IOException {
        if (this.pos < streamSize) {
          final long currAddr = (this.baseAddr + this.pos);
          this.pos++;
          return (UnsafeMemoryChunk.getUnsafeWrapper().getByte(currAddr) & 0xff);
        }
        else {
          return -1;
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final int read(byte[] b, int off, int len) {
        if (b == null) {
          throw new NullPointerException();
        }
        else if (off < 0 || len < 0 || b.length < (off + len)) {
          throw new IndexOutOfBoundsException();
        }

        final int capacity = (streamSize - this.pos);
        if (len > capacity) {
          if (capacity == 0 && len > 0) {
            // end-of-file reached
            return -1;
          }
          len = capacity;
        }
        if (len > 0) {
          OffHeapByteSource.this.readBytes(this.pos, b, off, len);
          this.pos += len;
          return len;
        }
        else {
          return 0;
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final long skip(long n) {
        return skipOver(n, streamSize);
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public final int available() {
        return (streamSize - this.pos);
      }

      @Override
      public int skipBytes(int n) throws IOException {
        return skipOver(n, streamSize);
      }

      @Override
      public final int readUnsignedShort() throws IOException {
        long currentAddr = this.baseAddr + this.pos;
        this.pos += 2;
        final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
        byte firstByte = unsafe.getByte(currentAddr);
        byte secondByte = unsafe.getByte(++currentAddr);
        return (((firstByte & 0xff) << 8) | (secondByte & 0xff));
      }

      @Override
      public int readUnsignedByte() throws IOException {
        final long currAddr = this.baseAddr + this.pos;
        this.pos++;
        return (UnsafeMemoryChunk.getUnsafeWrapper().getByte(currAddr) & 0xff);
      }

      @Override
      public String readUTF() throws IOException {
        final int utfLen = readUnsignedShort();

        if ((this.pos + utfLen) <= streamSize) {
          if (this.charBuf == null || this.charBuf.length < utfLen) {
            int charBufLength = (((utfLen / 2) + 1) * 3);
            this.charBuf = new char[charBufLength];
          }
          final char[] chars = this.charBuf;

          final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
          final long memPos = this.baseAddr + this.pos;
          long currentAddr = memPos;
          final long addrLimit = currentAddr + utfLen;
          int nChars = 0;
          int char1, char2, char3;

          // quick check for ASCII strings first
          for (;currentAddr < addrLimit; currentAddr++, nChars++) {
            char1 = (unsafe.getByte(currentAddr) & 0xff);
            if (char1 < 128) {
              chars[nChars] = (char)char1;
              continue;
            }
            else {
              break;
            }
          }

          for (; currentAddr < addrLimit; currentAddr++, nChars++) {
            char1 = (unsafe.getByte(currentAddr) & 0xff);
            // classify based on the high order 3 bits
            switch (char1 >> 5) {
              case 6:
                if ((currentAddr + 1) < addrLimit) {
                  // two byte encoding
                  // 110yyyyy 10xxxxxx
                  // use low order 6 bits of the next byte
                  // It should have high order bits 10.
                  char2 = unsafe.getByte(++currentAddr);
                  if ((char2 & 0xc0) == 0x80) {
                    // 00000yyy yyxxxxxx
                    chars[nChars] = (char)((char1 & 0x1f) << 6 | (char2 & 0x3f));
                  }
                  else {
                    throwUTFEncodingError((int)(currentAddr - memPos), char1,
                        char2, null, 2);
                  }
                }
                else {
                  throw new UTFDataFormatException(
                      "partial 2-byte character at end (char1=" + char1 + ')');
                }
                break;
              case 7:
                if ((currentAddr + 2) < addrLimit) {
                  // three byte encoding
                  // 1110zzzz 10yyyyyy 10xxxxxx
                  // use low order 6 bits of the next byte
                  // It should have high order bits 10.
                  char2 = unsafe.getByte(++currentAddr);
                  if ((char2 & 0xc0) == 0x80) {
                    // use low order 6 bits of the next byte
                    // It should have high order bits 10.
                    char3 = unsafe.getByte(++currentAddr);
                    if ((char3 & 0xc0) == 0x80) {
                      // zzzzyyyy yyxxxxxx
                      chars[nChars] = (char)(((char1 & 0x0f) << 12)
                          | ((char2 & 0x3f) << 6) | (char3 & 0x3f));
                    }
                    else {
                      throwUTFEncodingError((int)(currentAddr - memPos), char1,
                          char2, char3, 3);
                    }
                  }
                  else {
                    throwUTFEncodingError((int)(currentAddr - memPos), char1,
                        char2, null, 3);
                  }
                }
                else {
                  throw new UTFDataFormatException(
                      "partial 3-byte character at end (char1=" + char1 + ')');
                }
                break;
              default:
                // one byte encoding
                // 0xxxxxxx
                chars[nChars] = (char)char1;
                break;
            }
          }
          this.pos = (int)(addrLimit - memPos);
          return new String(chars, 0, nChars);
        }
        else {
          throw new EOFException();
        }
      }

      @Override
      public final short readShort() throws IOException {
        long currentAddr = this.baseAddr + this.pos;
        this.pos += 2;
        final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
        int high = ((int)unsafe.getByte(currentAddr));
        int low = ((int)unsafe.getByte(++currentAddr));
        return (short)((high << 8) | (low & 0xff));
      }

      @Override
      public final long readLong() throws IOException {
        final long currentAddr = this.baseAddr + this.pos;
        this.pos += 8;
        final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
        return (OffHeapRegionEntryHelper.NATIVE_BYTE_ORDER_IS_LITTLE_ENDIAN)
            ? Long.reverseBytes(unsafe.getLong(currentAddr))
            : unsafe.getLong(currentAddr);
      }

      @Override
      public final int readInt() throws IOException {
        final long currentAddr = this.baseAddr + this.pos;
        this.pos += 4;
        final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
        return (OffHeapRegionEntryHelper.NATIVE_BYTE_ORDER_IS_LITTLE_ENDIAN)
            ? Integer.reverseBytes(unsafe.getInt(currentAddr))
            : unsafe.getInt(currentAddr);
      }

      @Override
      public void readFully(byte[] bytes, int offset, int length)
          throws IOException {
        OffHeapByteSource.this.readBytes(this.pos, bytes, offset,
            length);
        this.pos += length;
      }

      @Override
      public void readFully(byte[] bytes) throws IOException {
        OffHeapByteSource.this.readBytes(this.pos, bytes);
        this.pos += bytes.length;
      }

      @Override
      public float readFloat() throws IOException {
        int intbits = this.readInt();
        return Float.intBitsToFloat(intbits);
      }

      @Override
      public double readDouble() throws IOException {
        long longbits = this.readLong();
        return Double.longBitsToDouble(longbits);
      }

      @Override
      public char readChar() throws IOException {
        long currentAddr = this.baseAddr + this.pos;
        this.pos += 2;
        final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
        int first = ((int)unsafe.getByte(currentAddr));
        int second = ((int)unsafe.getByte(++currentAddr));
        return (char)((first << 8) | (second & 0xff));
      }

      @Override
      public byte readByte() throws IOException {
        final long currentAddr = this.baseAddr + this.pos;
        this.pos++;
        return UnsafeMemoryChunk.getUnsafeWrapper().getByte(currentAddr);
      }

      @Override
      public boolean readBoolean() throws IOException {
        final long currentAddr = this.baseAddr + this.pos;
        this.pos++;
        return UnsafeMemoryChunk.getUnsafeWrapper().getByte(currentAddr) != 0;
      }
    };
  }

  @Override
  public Object getValueAsDeserializedHeapObject() {
    return this.getDeserializedValue(null, null);
  }

  @Override
  public byte[] getValueAsHeapByteArray() {
    return this.getRowBytes();
  }

  public static int getUnsignedCompactIntNumBytes(int data) {
    int numBytes = 1;
    while (true) {
      if ((data & ~0x7F) == 0) {
        return numBytes;
      }
      else {
        ++numBytes;
        data >>>= 7;
      }
    }
  }
}
