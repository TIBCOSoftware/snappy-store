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

package com.gemstone.gemfire.internal;

import java.io.Closeable;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataOutputStream;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;

/**
 * Implements {@link DataOutput} writing to a direct ByteBuffer
 * expanding it as required.
 * <p>
 * Note: this can be further optimized by using the Unsafe API rather than
 * going through ByteBuffer API (e.g. see ChannelBufferUnsafeDataOutputStream)
 * but won't have an effect for large byte array writes (like for column data)
 */
public final class DirectByteBufferDataOutput
    implements DataOutput, Closeable, VersionedDataStream {

  private static final int INITIAL_SIZE = 1024;

  private ByteBuffer buffer;
  private final Version version;

  public DirectByteBufferDataOutput(Version version) {
    this(INITIAL_SIZE, version);
  }

  public DirectByteBufferDataOutput(int initialSize, Version version) {
    // this uses allocations and expansion via direct Unsafe API rather
    // than ByteBuffer.allocateDirect for better efficiency esp in expansion
    this.buffer = UnsafeHolder.allocateDirectBuffer(initialSize)
        .order(ByteOrder.BIG_ENDIAN);
    this.version = version;
  }

  @Override
  public Version getVersion() {
    return this.version;
  }

  public ByteBuffer getBuffer() {
    return this.buffer;
  }

  @Override
  public void write(int b) throws IOException {
    ensureCapacity(1);
    this.buffer.put((byte)b);
  }

  @Override
  public void write(@Nonnull byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(@Nonnull byte[] b, int off, int len) throws IOException {
    ensureCapacity(len);
    this.buffer.put(b, off, len);
  }

  @Override
  public void writeBoolean(boolean v) throws IOException {
    ensureCapacity(1);
    this.buffer.put(v ? (byte)1 : 0);
  }

  @Override
  public void writeByte(int v) throws IOException {
    ensureCapacity(1);
    this.buffer.put((byte)v);
  }

  @Override
  public void writeShort(int v) throws IOException {
    ensureCapacity(2);
    this.buffer.putShort((short)v);
  }

  @Override
  public void writeChar(int v) throws IOException {
    writeShort(v);
  }

  @Override
  public void writeInt(int v) throws IOException {
    ensureCapacity(4);
    this.buffer.putInt(v);
  }

  @Override
  public void writeLong(long v) throws IOException {
    ensureCapacity(8);
    this.buffer.putLong(v);
  }

  @Override
  public void writeFloat(float v) throws IOException {
    ensureCapacity(4);
    this.buffer.putInt(Float.floatToIntBits(v));
  }

  @Override
  public void writeDouble(double v) throws IOException {
    ensureCapacity(8);
    this.buffer.putLong(Double.doubleToLongBits(v));
  }

  @Override
  public void writeUTF(@Nonnull String s) throws IOException {
    // first calculate the UTF encoded length
    final int strLen = s.length();
    final int utfLen = ClientSharedUtils.getUTFLength(s, strLen);
    if (utfLen > 65535) {
      throw new UTFDataFormatException("encoded string too long: " + utfLen
          + " bytes");
    }
    // make required space
    ensureCapacity(utfLen + 2);
    final ByteBuffer buffer = this.buffer;
    // write the length first
    buffer.putShort((short)utfLen);
    // now write as UTF data using unsafe API
    final long address = UnsafeHolder.getDirectBufferAddress(buffer);
    final int position = buffer.position();
    ChannelBufferUnsafeDataOutputStream.writeUTFSegmentNoOverflow(s, 0, strLen,
        utfLen, null, address + position);
    buffer.position(position + utfLen);
  }

  @Override
  public void writeBytes(@Nonnull String s) throws IOException {
    if (s.length() > 0) {
      final byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
      ensureCapacity(bytes.length);
      this.buffer.put(bytes);
    }
  }

  @Override
  public void writeChars(@Nonnull String s) throws IOException {
    int len = s.length();
    if (len > 0) {
      final int required = len << 1;
      if (required < 0) {
        throw new IOException("Buffer overflow with required=" + required);
      }
      ensureCapacity(required);
      for (int i = 0; i < len; i++) {
        this.buffer.putChar(s.charAt(i));
      }
    }
  }

  @Override
  public void close() throws IOException {
  }

  protected void ensureCapacity(int required) {
    final ByteBuffer buffer = this.buffer;
    final int position = buffer.position();
    if (buffer.limit() - position >= required) return;

    final int newCapacity = Math.max((int)Math.min(
        (long)buffer.capacity() << 1L, Integer.MAX_VALUE), position + required);
    buffer.flip();
    // use the efficient C realloc() call that avoids copying if possible
    ByteBuffer newBuffer = UnsafeHolder.reallocateDirectBuffer(
        buffer, newCapacity);
    // set the position of newBuffer
    newBuffer.position(position);
    this.buffer = newBuffer;
  }
}
