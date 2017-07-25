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

import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.shared.unsafe.DirectBufferAllocator;
import org.apache.spark.unsafe.Platform;

/**
 * Heap ByteBuffer implementation of {@link BufferAllocator}.
 */
public final class HeapBufferAllocator extends BufferAllocator {

  private static final HeapBufferAllocator instance =
      new HeapBufferAllocator();

  public static HeapBufferAllocator instance() {
    return instance;
  }

  private HeapBufferAllocator() {
  }

  @Override
  public ByteBuffer allocate(int size, String owner) {
    return ByteBuffer.allocate(size);
  }

  @Override
  public ByteBuffer allocateForStorage(int size) {
    return ByteBuffer.allocate(size);
  }

  @Override
  public void clearPostAllocate(ByteBuffer buffer) {
    // JVM clears the allocated area
  }

  @Override
  public Object baseObject(ByteBuffer buffer) {
    return buffer.array();
  }

  @Override
  public long baseOffset(ByteBuffer buffer) {
    return Platform.BYTE_ARRAY_OFFSET + buffer.arrayOffset();
  }

  @Override
  public ByteBuffer expand(ByteBuffer buffer, int required, String owner) {
    assert required > 0 : "expand: unexpected required = " + required;

    final byte[] bytes = buffer.array();
    final int currentUsed = buffer.limit();
    if (currentUsed + required > buffer.capacity()) {
      final int newLength = BufferAllocator.expandedSize(currentUsed, required);
      final byte[] newBytes = new byte[newLength];
      System.arraycopy(bytes, buffer.arrayOffset(), newBytes, 0, currentUsed);
      return ByteBuffer.wrap(newBytes).order(buffer.order());
    } else {
      buffer.limit(currentUsed + required);
      return buffer;
    }
  }

  @Override
  public byte[] toBytes(ByteBuffer buffer) {
    if (buffer.position() == 0 && buffer.arrayOffset() == 0 &&
        buffer.limit() == buffer.capacity()) {
      return buffer.array();
    } else {
      return super.toBytes(buffer);
    }
  }

  @Override
  public ByteBuffer fromBytesToStorage(byte[] bytes, int offset, int length) {
    return ByteBuffer.wrap(bytes, offset, length);
  }

  @Override
  public ByteBuffer transfer(ByteBuffer buffer, String owner) {
    if (buffer.hasArray()) {
      return buffer;
    } else {
      ByteBuffer newBuffer = super.transfer(buffer, owner);
      // release the incoming direct buffer eagerly
      if (buffer.isDirect()) {
        DirectBufferAllocator.instance().release(buffer);
      }
      return newBuffer;
    }
  }

  @Override
  public void release(ByteBuffer buffer) {
  }

  @Override
  public boolean isDirect() {
    return false;
  }

  @Override
  public void close() {
  }
}
