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

package com.gemstone.gemfire.internal;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.annotation.Nonnull;

import com.gemstone.gemfire.internal.shared.Version;

/**
 * Implements {@link DataInput} reading from given ByteBuffer.
 * <p>
 * Note: this can be further optimized by using the Unsafe API rather than
 * going through ByteBuffer API (e.g. see ChannelBufferUnsafeDataInputStream)
 * but won't have an effect for large byte array reads (like for column data)
 */
public final class ByteBufferDataInput
    implements DataInput, VersionedDataStream {

  private final ByteBuffer buffer;
  private final Version version;

  public ByteBufferDataInput(ByteBuffer buffer, Version version) {
    buffer.order(ByteOrder.BIG_ENDIAN);
    this.buffer = buffer;
    this.version = version;
  }

  public ByteBuffer getInternalBuffer() {
    return this.buffer;
  }

  @Override
  public Version getVersion() {
    return this.version;
  }

  @Override
  public void readFully(@Nonnull byte[] b) throws IOException {
    readFully(b, 0, b.length);
  }

  @Override
  public void readFully(@Nonnull byte[] b,
      int off, int len) throws IOException {
    this.buffer.get(b, off, len);
  }

  public void read(ByteBuffer target) {
    final int limit = this.buffer.limit();
    final int position = this.buffer.position();
    final int targetRemaining = target.remaining();

    if ((limit - position) > targetRemaining) {
      // reduce limit
      this.buffer.limit(targetRemaining + position);
      try {
        target.put(this.buffer);
      } finally {
        this.buffer.limit(limit);
      }
    } else {
      target.put(this.buffer);
    }
  }

  @Override
  public int skipBytes(int n) throws IOException {
    final ByteBuffer buffer = this.buffer;
    final int skip = Math.min(n, buffer.remaining());
    buffer.position(buffer.position() + skip);
    return skip;
  }

  @Override
  public boolean readBoolean() throws IOException {
    return this.buffer.get() == 1;
  }

  @Override
  public byte readByte() throws IOException {
    return this.buffer.get();
  }

  @Override
  public int readUnsignedByte() throws IOException {
    return this.buffer.get() & 0xFF;
  }

  @Override
  public short readShort() throws IOException {
    return this.buffer.getShort();
  }

  @Override
  public int readUnsignedShort() throws IOException {
    return this.buffer.getShort() & 0xFFFF;
  }

  @Override
  public char readChar() throws IOException {
    return this.buffer.getChar();
  }

  @Override
  public int readInt() throws IOException {
    return this.buffer.getInt();
  }

  @Override
  public long readLong() throws IOException {
    return this.buffer.getLong();
  }

  @Override
  public float readFloat() throws IOException {
    return Float.intBitsToFloat(this.buffer.getInt());
  }

  @Override
  public double readDouble() throws IOException {
    return Double.longBitsToDouble(this.buffer.getLong());
  }

  @Override
  public String readLine() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  @Nonnull
  public String readUTF() throws IOException {
    return DataInputStream.readUTF(this);
  }
}
