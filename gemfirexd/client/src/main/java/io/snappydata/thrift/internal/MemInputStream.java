/*
 * Adapted from Android's ByteArrayInputStream having license below.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
package io.snappydata.thrift.internal;

import java.io.IOException;
import java.io.InputStream;

import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.pivotal.gemfirexd.internal.shared.common.io.DynamicByteArrayOutputStream;

/**
 * Extension of JDK ByteArrayInputStream to use a shared buffer
 * with corresponding OutputStream if required.
 */
public final class MemInputStream extends InputStream {

  /**
   * The output stream containing the bytes to stream.
   */
  protected DynamicByteArrayOutputStream buffer;

  /**
   * The current position within the byte stream.
   */
  protected int pos;

  /**
   * The current mark position. Initially set to 0 or the <code>offset</code>
   * parameter within the constructor.
   */
  protected int mark;

  /**
   * Constructs a new {@code MemInputStream} on the byte array
   * {@code buf}.
   *
   * @param buf the byte array to stream over.
   */
  public MemInputStream(byte[] buf) {
    this.buffer = new DynamicByteArrayOutputStream(buf);
  }

  /**
   * Constructs a new {@code MemInputStream} on the byte array
   * {@code buf} with the initial position set to {@code offset} and the
   * number of bytes available set to {@code offset} + {@code length}.
   *
   * @param buf    the byte array to stream over.
   * @param length the number of bytes available for streaming.
   */
  public MemInputStream(byte[] buf, int length) {
    this.buffer = new DynamicByteArrayOutputStream(buf);
    this.buffer.setPosition(Math.min(length, buf.length));
  }

  /**
   * Constructs a new {@code MemInputStream} on the output byte stream
   * {@code buf} with the initial position set to {@code offset} and the
   * number of bytes available set to {@code offset} + {@code length}.
   *
   * @param buf    the output byte stream to stream over.
   * @param length the number of bytes available for streaming.
   */
  MemInputStream(DynamicByteArrayOutputStream buf, int length) {
    this.buffer = buf;
    this.buffer.setPosition(Math.min(length, buf.getUsed()));
  }

  /**
   * Returns the number of remaining bytes.
   *
   * @return {@code count - pos}
   */
  @Override
  public synchronized int available() {
    return buffer.getUsed() - pos;
  }

  /**
   * Closes this stream and frees resources associated with this stream.
   *
   * @throws IOException if an I/O error occurs while closing this stream.
   */
  @Override
  public void close() throws IOException {
    // Do nothing on close, this matches JDK behavior.
  }

  /**
   * Sets a mark position in this MemInputStream. The parameter
   * {@code readlimit} is ignored. Sending {@code reset()} will reposition the
   * stream back to the marked position.
   *
   * @param readlimit ignored.
   * @see #markSupported()
   * @see #reset()
   */
  @Override
  public synchronized void mark(int readlimit) {
    mark = pos;
  }

  /**
   * Indicates whether this stream supports the {@code mark()} and
   * {@code reset()} methods. Returns {@code true} since this class supports
   * these methods.
   *
   * @return always {@code true}.
   * @see #mark(int)
   * @see #reset()
   */
  @Override
  public boolean markSupported() {
    return true;
  }

  /**
   * Reads a single byte from the source byte array and returns it as an
   * integer in the range from 0 to 255. Returns -1 if the end of the source
   * array has been reached.
   *
   * @return the byte read or -1 if the end of this stream has been reached.
   */
  @Override
  public synchronized int read() {
    return pos < buffer.getUsed() ? buffer.getByteArray()[pos++] & 0xFF : -1;
  }

  /**
   * Reads at most {@code len} bytes from this stream and stores
   * them in byte array {@code b} starting at {@code offset}. This
   * implementation reads bytes from the source byte array.
   *
   * @param buf    the byte array in which to store the bytes read.
   * @param offset the initial position in {@code b} to store the bytes read
   *               from this stream.
   * @param length the maximum number of bytes to store in {@code b}.
   * @return the number of bytes actually read or -1 if no bytes were read and
   * the end of the stream was encountered.
   * @throws IndexOutOfBoundsException if {@code offset < 0} or {@code length < 0},
   *                                   or if {@code offset + length} is greater
   *                                   than the size of {@code b}.
   * @throws NullPointerException      if {@code b} is {@code null}.
   */
  @Override
  public synchronized int read(byte[] buf, int offset, int length) {
    UnsafeHolder.checkBounds(buf.length, offset, length);
    // Are there any bytes available?
    final int count = buffer.getUsed();
    if (this.pos >= count) {
      return -1;
    }
    if (length == 0) {
      return 0;
    }
    int copylen = count - pos < length ? count - pos : length;
    System.arraycopy(buffer.getByteArray(), pos, buf, offset, copylen);
    pos += copylen;
    return copylen;
  }

  /**
   * Resets this stream to the last marked location. This implementation
   * resets the position to either the marked position, the start position
   * supplied in the constructor or 0 if neither has been provided.
   *
   * @see #mark(int)
   */
  @Override
  public synchronized void reset() {
    pos = mark;
  }

  /**
   * Skips {@code byteCount} bytes in this InputStream. Subsequent
   * calls to {@code read} will not return these bytes unless {@code reset} is
   * used. This implementation skips {@code byteCount} number of bytes in the
   * target stream. It does nothing and returns 0 if {@code byteCount}
   * is negative.
   *
   * @return the number of bytes actually skipped.
   */
  @Override
  public synchronized long skip(long byteCount) {
    if (byteCount <= 0) {
      return 0;
    }
    int temp = pos;
    final int count = buffer.getUsed();
    pos = count - pos < byteCount ? count : (int)(pos + byteCount);
    return pos - temp;
  }

  final byte[] getBuffer() {
    return buffer.getByteArray();
  }

  final int size() {
    return buffer.getUsed();
  }

  final void changeBuffer(byte[] b) {
    this.buffer = new DynamicByteArrayOutputStream(b);
  }

  final void changePosition(int position) {
    this.pos = position;
  }

  final void changeSize(int newSize) {
    buffer.setPosition(newSize);
  }
}
