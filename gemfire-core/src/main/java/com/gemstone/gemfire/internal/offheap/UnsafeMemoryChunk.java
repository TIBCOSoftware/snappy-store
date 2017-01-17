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
package com.gemstone.gemfire.internal.offheap;

import com.gemstone.gemfire.internal.SharedLibrary;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import org.apache.spark.unsafe.Platform;

public final class UnsafeMemoryChunk implements MemoryChunk {
  private static final UnsafeWrapper unsafe;
  static {
    unsafe = new UnsafeWrapper();
  }

  private final long data;
  private final int size;
  
  public UnsafeMemoryChunk(int size) {
    assert size >= 0;
    try {
    this.data = unsafe.allocateMemory(size);
    this.size = size;
    } catch (OutOfMemoryError err) {
      String msg = "Failed creating " + size + " bytes of off-heap memory during cache creation.";
      if (err.getMessage() != null && !err.getMessage().isEmpty()) {
        msg += " Cause: " + err.getMessage();
      }
      if (!SharedLibrary.is64Bit() && size >= (1024*1024*1024)) {
        msg += " The JVM looks like a 32-bit one. For large amounts of off-heap memory a 64-bit JVM is needed.";
      }
      throw new OutOfMemoryError(msg);
    }
  }

  public static int getPageSize() {
    return unsafe.getPageSize();
  }
  @Override
  public int getSize() {
    return this.size;
  }
  
  public long getMemoryAddress() {
    return this.data;
  }

  public static UnsafeWrapper getUnsafeWrapper() {
    return unsafe;
  }

  /**
   * Compare the first 'size' bytes at addr1 to those at addr2 and return true
   * if they are all equal.
   */
  public static boolean compareUnsafeBytes(long addr1, long addr2, int size) {
    final UnsafeWrapper unsafe = getUnsafeWrapper();
    if ((size >= 8) && ((addr1 & 7) == 0) && ((addr2 & 7) == 0)) {
      // We have 8 or more bytes to compare and the addresses are 8 byte aligned
      do {
        if (unsafe.getLong(addr1) != unsafe.getLong(addr2)) {
          return false;
        }
        size -= 8;
        addr1 += 8;
        addr2 += 8;
      } while (size >= 8);
    }
    while (size > 0) {
      if (unsafe.getByte(addr1) != unsafe.getByte(addr2)) {
        return false;
      }
      size--;
      addr1++;
      addr2++;
    }
    return true;
  }

  /**
   * Reads from "addr" to "bytes". Number of bytes read/written is provided as
   * argument.
   */
  public static void readUnsafeBytes(final long addr, final byte[] bytes,
      final int length) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr,
        length);

    Platform.copyMemory(null, addr, bytes, Platform.BYTE_ARRAY_OFFSET, length);
  }

  /**
   * Reads from "offset" bytes after "addr" to "bytes". Number of bytes
   * read/written is provided as argument. The "offset" argument is being sent
   * separately instead of added in addr itself to workaround JDK bug in #51350
   * (see https://reviewboard.gemstone.com/r/3246 for more details).
   */
  public static void readUnsafeBytes(long addr, int offset, final byte[] bytes,
      int bytesOffset, int length) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr,
        length + offset);

    Platform.copyMemory(null, addr + offset, bytes,
        Platform.BYTE_ARRAY_OFFSET + bytesOffset, length);
  }

  public static byte readAbsoluteByte(long addr) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, 1);
    return unsafe.getByte(addr);
  }

  public static int readAbsoluteInt(long addr) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Integer.SIZE/Byte.SIZE);
    return unsafe.getInt(addr);
  }
  public static int readAbsoluteIntVolatile(long addr) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Integer.SIZE/Byte.SIZE);
    return unsafe.getIntVolatile(null, addr);
  }
  public static long readAbsoluteLong(long addr) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Long.SIZE/Byte.SIZE);
    return unsafe.getLong(addr);
  }
  public static long readAbsoluteLongVolatile(long addr) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Long.SIZE/Byte.SIZE);
    return unsafe.getLongVolatile(null, addr);
  }

  @Override
  public byte readByte(int offset) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(this.data+offset, 1);
    return readAbsoluteByte(this.data+offset);
  }

  public static void writeAbsoluteByte(long addr, byte value) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, 1);
    unsafe.putByte(addr, value);
  }
       
  public static void writeAbsoluteInt(long addr, int value) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Integer.SIZE/Byte.SIZE);
    unsafe.putInt(null, addr, value);
  }
  public static void writeAbsoluteIntVolatile(long addr, int value) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Integer.SIZE/Byte.SIZE);
    unsafe.putIntVolatile(null, addr, value);
  }
  public static boolean writeAbsoluteIntVolatile(long addr, int expected, int value) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Integer.SIZE/Byte.SIZE);
    return unsafe.compareAndSwapInt(null, addr, expected, value);
  }
  public static void writeAbsoluteLong(long addr, long value) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Long.SIZE/Byte.SIZE);
    unsafe.putLong(null, addr, value);
  }
  public static void writeAbsoluteLongVolatile(long addr, long value) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Long.SIZE/Byte.SIZE);
    unsafe.putLongVolatile(null, addr, value);
  }
  public static boolean writeAbsoluteLongVolatile(long addr, long expected, long value) {
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, Long.SIZE/Byte.SIZE);
    return unsafe.compareAndSwapLong(null, addr, expected, value);
  }

  @Override
  public void writeByte(int offset, byte value) {
    writeAbsoluteByte(this.data+offset, value);
  }

  public static void readAbsoluteBytes(long addr, int addrOffset, byte[] bytes) {
    readAbsoluteBytes(addr, addrOffset, bytes, 0, bytes.length);
  }

  @Override
  public void readBytes(int offset, byte[] bytes) {
    readBytes(offset, bytes, 0, bytes.length);
  }

  public static void writeAbsoluteBytes(long addr, byte[] bytes) {
    writeAbsoluteBytes(addr, bytes, 0, bytes.length);
  }
  
  public static void clearAbsolute(long addr, int size) {
    assert size >= 0: "Size=" + size + ", but size must be >= 0";
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, size);
    unsafe.setMemory(addr, size, (byte) 0);
  }

  @Override
  public void writeBytes(int offset, byte[] bytes) {
    writeBytes(offset, bytes, 0, bytes.length);
  }

  // the addrOffset argument is being sent separately instead of added in addr
  // itself to workaround JDK bug in #51350
  public static void readAbsoluteBytes(long addr, int addrOffset, byte[] bytes,
      int bytesOffset, int size) {
    // Throwing an Error instead of using the "assert" keyword because passing < 0 to
    // copyMemory(...) can lead to a core dump with some JVMs and we don't want to
    // require the -ea JVM flag.
    if (size < 0) {
      throw new AssertionError("Size=" + size + ", but size must be >= 0");
    }
    assert addrOffset >= 0: "AddrOffset=" + addrOffset + ", but addrOffset must be >= 0";
    assert bytesOffset >= 0: "BytesOffset=" + bytesOffset + ", but bytesOffset must be >= 0";
    assert bytesOffset + size <= bytes.length: "BytesOffset=" + bytesOffset + ",size=" + size + ",bytes.length=" + bytes.length + ", but bytesOffset + size must be <= " + bytes.length;
    if (size == 0) {
      return; // No point in wasting time copying 0 bytes
    }
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr,
        size + addrOffset);

    Platform.copyMemory(null, addr + addrOffset, bytes,
        Platform.BYTE_ARRAY_OFFSET + bytesOffset, size);
  }

  @Override
  public void readBytes(int offset, byte[] bytes, int bytesOffset, int size) {
    readAbsoluteBytes(this.data, offset, bytes, bytesOffset, size);
  }

  public static void writeAbsoluteBytes(long addr, byte[] bytes, int bytesOffset, int size) {
    // Throwing an Error instead of using the "assert" keyword because passing < 0 to
    // copyMemory(...) can lead to a core dump with some JVMs and we don't want to
    // require the -ea JVM flag.
    if (size < 0) {
      throw new AssertionError("Size=" + size + ", but size must be >= 0");
    }
    assert bytesOffset >= 0: "BytesOffset=" + bytesOffset + ", but bytesOffset must be >= 0";
    assert bytesOffset + size <= bytes.length: "BytesOffset=" + bytesOffset + ",size=" + size + ",bytes.length=" + bytes.length + ", but bytesOffset + size must be <= " + bytes.length;
    if (size == 0) {
      return; // No point in wasting time copying 0 bytes
    }
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(addr, size);

    Platform.copyMemory(bytes, Platform.BYTE_ARRAY_OFFSET + bytesOffset,
        null, addr, size);
  }

  public static void fill(long addr, int size, byte fill) {
    assert size >= 0: "Size=" + size + ", but size must be >= 0";
    unsafe.setMemory(addr, size, fill);
  }
  
  @Override
  public void writeBytes(int offset, byte[] bytes, int bytesOffset, int size) {
    writeAbsoluteBytes(this.data+offset, bytes, bytesOffset, size);
  }

  @Override
  public void release() {
    unsafe.freeMemory(this.data);
  }

  @Override
  public void copyBytes(int src, int dst, int size) {
    assert size >= 0: "Size=" + size + ", but size must be >= 0";
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(this.data+src, size);
    assert SimpleMemoryAllocatorImpl.validateAddressAndSizeWithinSlab(this.data+dst, size);
    unsafe.copyMemory(this.data+src, this.data+dst, size);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{MemoryAddress=" +
        getMemoryAddress() + ", Size=" + getSize() + '}';
  }
}
