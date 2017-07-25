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

package com.gemstone.gemfire.internal.shared.unsafe;

import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.shared.BufferAllocator;

/**
 * Generic implementation of {@link BufferAllocator} for direct ByteBuffers
 * using Java NIO API.
 */
public class DirectBufferAllocator extends BufferAllocator {

  private static final DirectBufferAllocator globalInstance =
      new DirectBufferAllocator();

  private static DirectBufferAllocator instance = globalInstance;

  public static DirectBufferAllocator instance() {
    return instance;
  }

  public static synchronized void setInstance(DirectBufferAllocator allocator) {
    instance = allocator;
  }

  public static synchronized void resetInstance() {
    instance = globalInstance;
  }

  protected DirectBufferAllocator() {
  }

  @Override
  public ByteBuffer allocate(int size, String owner) {
    return ByteBuffer.allocateDirect(size);
  }

  @Override
  public ByteBuffer allocateForStorage(int size) {
    return ByteBuffer.allocateDirect(size);
  }

  @Override
  public void clearPostAllocate(ByteBuffer buffer) {
    // clear till the capacity and not limit since former will be a factor
    // of 8 and hence more efficient in Unsafe.setMemory
    clearBuffer(buffer, 0, buffer.capacity());
  }

  @Override
  public Object baseObject(ByteBuffer buffer) {
    return null;
  }

  @Override
  public long baseOffset(ByteBuffer buffer) {
    return UnsafeHolder.getDirectBufferAddress(buffer);
  }

  @Override
  public ByteBuffer expand(ByteBuffer buffer, int required, String owner) {
    assert required > 0 : "expand: unexpected required = " + required;

    final int currentUsed = buffer.limit();
    if (currentUsed + required > buffer.capacity()) {
      final int newLength = BufferAllocator.expandedSize(currentUsed, required);
      final ByteBuffer newBuffer = ByteBuffer.allocateDirect(newLength)
          .order(buffer.order());
      buffer.rewind();
      newBuffer.put(buffer);
      UnsafeHolder.releaseDirectBuffer(buffer);
      newBuffer.rewind(); // position at start as per the contract of expand
      return newBuffer;
    } else {
      buffer.limit(currentUsed + required);
      return buffer;
    }
  }

  @Override
  public ByteBuffer fromBytesToStorage(byte[] bytes, int offset, int length) {
    final ByteBuffer buffer = allocateForStorage(length);
    buffer.put(bytes, offset, length);
    // move to the start
    buffer.rewind();
    return buffer;
  }

  @Override
  public ByteBuffer transfer(ByteBuffer buffer, String owner) {
    if (buffer.isDirect()) {
      return buffer;
    } else {
      return super.transfer(buffer, owner);
    }
  }

  @Override
  public void release(ByteBuffer buffer) {
    // reserved bytes will be decremented via FreeMemory implementations
    UnsafeHolder.releaseDirectBuffer(buffer);
  }

  @Override
  public boolean isDirect() {
    return true;
  }

  @Override
  public void close() {
    UnsafeHolder.releasePendingReferences();
  }
}
