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

package com.gemstone.gemfire.internal.shared;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.locks.LockSupport;

/**
 * Intermediate class that extends both an OutputStream and WritableByteChannel.
 * 
 * @author swale
 * @since gfxd 1.1
 */
public abstract class OutputStreamChannel extends OutputStream implements
    WritableByteChannel, Closeable {

  protected final WritableByteChannel channel;
  protected volatile Thread parkedThread;
  protected volatile long bytesWritten;

  /**
   * nanos to park reader thread to wait for writing data in non-blocking mode
   * (will be explicitly signalled by selector if data can be written)
   */
  protected static final long PARK_NANOS = 200L;
  protected static final long PARK_NANOS_MAX = 15000000000L;

  protected OutputStreamChannel(WritableByteChannel channel) {
    this.channel = channel;
  }

  /**
   * Get the underlying {@link WritableByteChannel}.
   */
  public final WritableByteChannel getUnderlyingChannel() {
    return this.channel;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public abstract int write(ByteBuffer src) throws IOException;

  /**
   * Common base method to write a given ByteBuffer source via an intermediate
   * direct byte buffer owned by the implementation of this class.
   */
  protected final int writeBuffered(final ByteBuffer src,
      final ByteBuffer channelBuffer) throws IOException {
    int srcLen = src.remaining();
    // flush large direct buffers directly into channel
    final boolean flushBuffer = srcLen > (channelBuffer.limit() >>> 1) &&
        src.isDirect();
    int numWritten = 0;
    while (srcLen > 0) {
      final int remaining = channelBuffer.remaining();
      if (srcLen <= remaining) {
        channelBuffer.put(src);
        return (numWritten + srcLen);
      } else {
        // flush directly if there is nothing in the channel buffer
        if (flushBuffer && channelBuffer.position() == 0) {
          return numWritten + writeBufferNonBlocking(src);
        }
        // copy src to buffer and flush
        if (remaining > 0) {
          // lower limit of src temporarily to remaining
          final int srcPos = src.position();
          src.limit(srcPos + remaining);
          try {
            channelBuffer.put(src);
          } finally {
            // restore the limit
            src.limit(srcPos + srcLen);
          }
          srcLen -= remaining;
          numWritten += remaining;
          assert srcLen == src.remaining() : "srcLen=" + srcLen
              + " srcRemaining=" + src.remaining();
        }
        // if we were able to write the full buffer then try writing the
        // remaining from source else return with whatever was written
        if (!flushBufferNonBlockingBase(channelBuffer)) {
          return numWritten;
        } else if (flushBuffer) {
          return numWritten + writeBufferNonBlocking(src);
        }
        // for non-direct buffers use channel buffer for best performance
        // so loop back and try again
      }
    }
    return numWritten;
  }

  protected boolean flushBufferNonBlocking(final ByteBuffer buffer,
      boolean isChannelBuffer) throws IOException {
    return flushBufferNonBlockingBase(buffer);
  }

  protected final boolean flushBufferNonBlockingBase(final ByteBuffer buffer)
      throws IOException {
    buffer.flip();

    final boolean flushed;
    try {
      writeBufferNonBlocking(buffer);
    } finally {
      // if we failed to write the full buffer then compact the remaining bytes
      // to the start so we can start filling it again
      if (buffer.hasRemaining()) {
        buffer.compact();
        flushed = false;
      }
      else {
        buffer.clear();
        flushed = true;
      }
    }
    return flushed;
  }

  protected int writeBuffer(final ByteBuffer buffer) throws IOException {
    long parkNanos = 0;
    int numWritten;
    while ((numWritten = this.channel.write(buffer)) == 0) {
      if (!buffer.hasRemaining()) {
        break;
      }
      // at this point we are out of the selector thread and don't want to
      // create unlimited size buffers upfront in selector, so will use simple
      // signalling between selector and this thread to proceed
      this.parkedThread = Thread.currentThread();
      LockSupport.parkNanos(PARK_NANOS);
      this.parkedThread = null;
      if ((parkNanos += PARK_NANOS) > PARK_NANOS_MAX) {
        throw new SocketTimeoutException("Connection write timed out.");
      }
    }
    if (numWritten > 0) {
      this.bytesWritten += numWritten;
    }
    return numWritten;
  }

  protected int writeBufferNonBlocking(ByteBuffer buffer) throws IOException {
    int numWritten = this.channel.write(buffer);
    if (numWritten > 0) {
      this.bytesWritten += numWritten;
    }
    return numWritten;
  }

  public final Thread getParkedThread() {
    return this.parkedThread;
  }

  public final long getBytesWritten() {
    return this.bytesWritten;
  }
}
