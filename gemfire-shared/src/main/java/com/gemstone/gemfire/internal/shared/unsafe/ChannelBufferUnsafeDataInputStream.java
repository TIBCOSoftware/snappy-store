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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import javax.annotation.Nonnull;

import org.apache.spark.unsafe.Platform;

/**
 * A buffered DataInput abstraction over channel using direct byte buffers, and
 * using internal Unsafe class for best performance.
 * <p>
 * The implementation is not thread-safe by design. This particular class can be
 * used as an efficient, buffered DataInput implementation for file channels,
 * socket channels and other similar.
 *
 * @author swale
 * @since gfxd 1.0
 */
public class ChannelBufferUnsafeDataInputStream extends
    ChannelBufferUnsafeInputStream implements DataInput {

  public ChannelBufferUnsafeDataInputStream(ReadableByteChannel channel) {
    super(channel);
  }

  public ChannelBufferUnsafeDataInputStream(ReadableByteChannel channel,
      int bufferSize) {
    super(channel, bufferSize);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void readFully(@Nonnull byte[] b) throws IOException {
    readFully(b, 0, b.length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void readFully(@Nonnull byte[] b,
      int off, int len) throws IOException {
    while (true) {
      final int readBytes = super.read(b, off, len);
      if (readBytes >= len) {
        return;
      } else if (readBytes >= 0) {
        len -= readBytes;
        off += readBytes;
      } else {
        throw new EOFException();
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int skipBytes(int n) {
    return (int)skip(n);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long skip(long n) {
    n = Math.max(0, Math.min(n, this.addrLimit - this.addrPosition));
    this.addrPosition += n;
    return n;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean readBoolean() throws IOException {
    return readByte() != 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final byte readByte() throws IOException {
    if (this.addrPosition >= this.addrLimit) {
      refillBuffer(this.buffer, 1, "readByte: premature end of stream");
    }
    return Platform.getByte(null, this.addrPosition++);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int readUnsignedByte() throws IOException {
    return (readByte() & 0xff);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final short readShort() throws IOException {
    long addrPos = this.addrPosition;
    if ((this.addrLimit - addrPos) < 2) {
      refillBuffer(this.buffer, 2, "readShort: premature end of stream");
      addrPos = this.addrPosition;
    }
    this.addrPosition += 2;
    if (UnsafeHolder.littleEndian) {
      return Short.reverseBytes(Platform.getShort(null, addrPos));
    } else {
      return Platform.getShort(null, addrPos);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int readUnsignedShort() throws IOException {
    return (readShort() & 0xFFFF);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final char readChar() throws IOException {
    return (char)readShort();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final long readLong() throws IOException {
    long addrPos = this.addrPosition;
    if ((this.addrLimit - addrPos) < 8) {
      refillBuffer(this.buffer, 8, "readLong: premature end of stream");
      addrPos = this.addrPosition;
    }
    this.addrPosition += 8;
    if (UnsafeHolder.littleEndian) {
      return Long.reverseBytes(Platform.getLong(null, addrPos));
    } else {
      return Platform.getLong(null, addrPos);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final float readFloat() throws IOException {
    return Float.intBitsToFloat(readInt());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final double readDouble() throws IOException {
    return Double.longBitsToDouble(readLong());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String readLine() {
    throw new UnsupportedOperationException();
  }

  /**
   * {@inheritDoc}
   */
  @Nonnull
  @Override
  public String readUTF() throws IOException {
    return DataInputStream.readUTF(this);
  }
}
