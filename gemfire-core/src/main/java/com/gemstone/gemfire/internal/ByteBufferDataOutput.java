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

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.cache.DiskEntry;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.store.SerializedDiskBuffer;
import com.gemstone.gemfire.internal.shared.BufferAllocator;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataOutputStream;

/**
 * A {@link SerializedDiskBuffer} that implements {@link DataOutput} writing
 * to a ByteBuffer expanding it as required.
 * <p>
 * Note: this can be further optimized by using the Unsafe API rather than
 * going through ByteBuffer API (e.g. see ChannelBufferUnsafeDataOutputStream)
 * but won't have an effect for large byte array writes (like for column data)
 */
public final class ByteBufferDataOutput extends SerializedDiskBuffer
    implements DataOutput, Closeable, VersionedDataStream {

  private static final int INITIAL_SIZE = 1024;

  private final BufferAllocator allocator;
  private ByteBuffer buffer;
  private final Version version;

  private String bufferOwner = "DATAOUTPUT";

  public ByteBufferDataOutput(Version version) {
    this(INITIAL_SIZE, version);
  }

  /**
   * This constructor can be used to give non-default buffer owner.
   * Kind of hack, to avoid large scale refactoring to pass shoulEvict
   * flag to UMM.
   */
  public ByteBufferDataOutput(int initialSize,
      BufferAllocator allocator,
      Version version,
      String bufferOwner) {
    this.allocator = allocator;
    this.buffer = allocator.allocate(initialSize, bufferOwner)
        .order(ByteOrder.BIG_ENDIAN);
    this.version = version;
    this.bufferOwner = bufferOwner;
  }

  public ByteBufferDataOutput(int initialSize, Version version) {
    // this uses allocations and expansion via BufferAllocator that has both
    // better efficiency for direct buffer (in expand) and applies system limits
    this.allocator = GemFireCacheImpl.getCurrentBufferAllocator();
    this.buffer = allocator.allocate(initialSize, bufferOwner)
        .order(ByteOrder.BIG_ENDIAN);
    this.version = version;
  }

  /**
   * Initialize the buffer serializing the given object to a byte buffer
   * (with initial reference count as 1). Normally there should be only
   * one calling thread that will {@link #release()} this when done.
   */
  public ByteBufferDataOutput serialize(Object obj) throws IOException {
    this.buffer.rewind();
    DataSerializer.writeObject(obj, this);
    this.buffer.flip();
    return this;
  }

  @Override
  public ByteBuffer getBufferRetain() {
    return retain() ? this.buffer.duplicate()
        : DiskEntry.Helper.NULL_BUFFER.duplicate();
  }

  @Override
  public ByteBuffer getBuffer() {
    return this.buffer.duplicate();
  }

  @Override
  public boolean needsRelease() {
    final ByteBuffer buffer = this.buffer;
    return buffer != null && buffer.isDirect();
  }

  @Override
  protected synchronized void releaseBuffer() {
    final ByteBuffer buffer = this.buffer;
    this.buffer = null;
    this.allocator.release(buffer);
  }

  @Override
  public synchronized void write(
      OutputStreamChannel channel) throws IOException {
    final ByteBuffer buffer = this.buffer.duplicate();
    if (buffer != null) {
      write(channel, buffer);
    } else {
      channel.write(DSCODE.NULL);
    }
  }

  @Override
  public int size() {
    // deliberately not synchronized since size() should always be protected
    // with a retain call if required and not lead to indeterminate results
    // due to an unexpected intervening release
    final ByteBuffer buffer = this.buffer;
    if (buffer != null) {
      return buffer.limit();
    } else {
      return 0;
    }
  }

  @Override
  public int getOffHeapSizeInBytes() {
    return 0; // will not be stored in region
  }

  @Override
  public Version getVersion() {
    return this.version;
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
    writeInt(Float.floatToIntBits(v));
  }

  @Override
  public void writeDouble(double v) throws IOException {
    writeLong(Double.doubleToLongBits(v));
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
    final Object baseObject = this.allocator.baseObject(buffer);
    final long address = this.allocator.baseOffset(buffer);
    final int position = buffer.position();
    ChannelBufferUnsafeDataOutputStream.writeUTFSegmentNoOverflow(s, 0, strLen,
        utfLen, baseObject, address + position);
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

  @Override
  public String toString() {
    return ClientSharedUtils.toString(this.buffer);
  }

  protected void ensureCapacity(int required) {
    final ByteBuffer buffer = this.buffer;
    final int position = buffer.position();
    if (buffer.limit() - position >= required) return;

    buffer.flip();
    ByteBuffer newBuffer = this.allocator.expand(buffer, required, bufferOwner);
    // set the position of newBuffer
    newBuffer.position(position);
    this.buffer = newBuffer;
  }
}
