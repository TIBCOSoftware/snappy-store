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

import java.io.Closeable;
import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;

/**
 * Allocate, release and expand ByteBuffers (in-place if possible).
 */
public abstract class BufferAllocator implements Closeable {

  public static final String STORE_DATA_FRAME_OUTPUT =
      "STORE_DATA_FRAME_OUTPUT";

  /** special owner indicating execution pool memory */
  public static final String EXECUTION = "EXECUTION";

  /**
   * Allocate a new ByteBuffer of given size.
   */
  public abstract ByteBuffer allocate(int size, String owner);

  /**
   * Allocate a new ByteBuffer of given size for storage in a Region.
   */
  public abstract ByteBuffer allocateForStorage(int size);

  /**
   * Clears the memory to be zeros immediately after allocation.
   */
  public abstract void clearPostAllocate(ByteBuffer buffer);

  /**
   * Clear the given portion of the buffer setting it with zeros.
   */
  public final void clearBuffer(ByteBuffer buffer, int position, int numBytes) {
    UnsafeHolder.getUnsafe().setMemory(baseObject(buffer), baseOffset(buffer) +
        position, numBytes, (byte)0);
  }

  /**
   * Get the base object of the ByteBuffer for raw reads/writes by Unsafe API.
   */
  public abstract Object baseObject(ByteBuffer buffer);

  /**
   * Get the base offset of the ByteBuffer for raw reads/writes by Unsafe API.
   */
  public abstract long baseOffset(ByteBuffer buffer);

  /**
   * Expand given ByteBuffer to new capacity. The new buffer is positioned
   * at the start and caller has to reposition if required.
   *
   * @return the new expanded ByteBuffer
   */
  public abstract ByteBuffer expand(ByteBuffer buffer, int required,
      String owner);

  /**
   * Return the data as a heap byte array. Use of this should be minimal
   * when no other option exists.
   */
  public byte[] toBytes(ByteBuffer buffer) {
    final int bufferSize = buffer.remaining();
    return ClientSharedUtils.toBytesCopy(buffer, bufferSize, bufferSize);
  }

  /**
   * Return a ByteBuffer either copying from, or sharing the given heap bytes.
   */
  public abstract ByteBuffer fromBytesToStorage(byte[] bytes, int offset,
      int length);

  /**
   * Return a ByteBuffer either sharing data of given ByteBuffer
   * if its type matches, or else copying from the given ByteBuffer.
   */
  public ByteBuffer transfer(ByteBuffer buffer, String owner) {
    final int position = buffer.position();
    final ByteBuffer newBuffer = allocate(buffer.limit(), owner);
    buffer.rewind();
    newBuffer.order(buffer.order());
    newBuffer.put(buffer);
    buffer.position(position);
    newBuffer.position(position);
    return newBuffer;
  }

  /**
   * For direct ByteBuffers the release method is preferred to eagerly release
   * the memory instead of depending on heap GC which can be delayed.
   */
  public abstract void release(ByteBuffer buffer);

  /**
   * Indicates if this allocator will produce direct ByteBuffers.
   */
  public abstract boolean isDirect();

  /**
   * Return true if this is a managed direct buffer allocator.
   */
  public boolean isManagedDirect() {
    return false;
  }

  /**
   * Allocate a buffer passing a custom FreeMemoryFactory. Requires that
   * appropriate calls against Spark memory manager have already been done.
   * Only for managed buffer allocator.
   */
  public ByteBuffer allocateCustom(int size,
      UnsafeHolder.FreeMemoryFactory factory) {
    throw new UnsupportedOperationException("Not supported for " + toString());
  }

  /**
   * Any cleanup required at system close.
   */
  @Override
  public abstract void close();

  public static int expandedSize(int currentUsed, int required) {
    final long minRequired = (long)currentUsed + required;
    // increase the size by 50%
    final int newLength = (int)Math.min(Math.max((currentUsed * 3) >>> 1L,
        minRequired), Integer.MAX_VALUE - 1);
    if (newLength >= minRequired) {
      return newLength;
    } else {
      throw new IndexOutOfBoundsException("Cannot allocate more than " +
          newLength + " bytes but required " + minRequired);
    }
  }
}
