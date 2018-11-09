/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.cache.store;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;

/**
 * A {@link SerializedDiskBuffer} to wrap heap bytes.
 */
public final class WrappedBytes extends SerializedDiskBuffer {

  private final byte[] buffer;
  private final int offset;
  private final int length;

  public WrappedBytes(byte[] buffer) {
    this(buffer, 0, buffer.length);
  }

  public WrappedBytes(byte[] buffer, int offset, int length) {
    this.buffer = buffer;
    this.offset = offset;
    this.length = length;
  }

  @Override
  public int referenceCount() {
    return 0;
  }

  @Override
  public boolean retain() {
    return true;
  }

  @Override
  public ByteBuffer getBufferRetain() {
    return getBuffer();
  }

  @Override
  public ByteBuffer getBuffer() {
    return ByteBuffer.wrap(this.buffer, this.offset, this.length);
  }

  @Override
  public void release() {
  }

  @Override
  public boolean needsRelease() {
    return false;
  }

  @Override
  protected void releaseBuffer() {
  }

  @Override
  public void write(OutputStreamChannel channel) throws IOException {
    channel.write(this.buffer, this.offset, this.length);
  }

  @Override
  public int size() {
    return this.length;
  }

  @Override
  public int getOffHeapSizeInBytes() {
    return 0;
  }

  @Override
  public String toString() {
    return ClientSharedUtils.toString(getBuffer());
  }
}
