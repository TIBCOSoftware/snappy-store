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

package com.gemstone.gemfire.internal.shared.unsafe;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * @author swale
 * @since gfxd 1.1
 */
public final class ChannelBufferUnsafeFramedInputStream extends
    ChannelBufferUnsafeDataInputStream {

  protected final int maxFrameBufferSize;

  public ChannelBufferUnsafeFramedInputStream(ReadableByteChannel channel)
      throws IOException {
    super(channel);
    this.maxFrameBufferSize = this.buffer.capacity() - 4 /* size of int */;
  }

  public ChannelBufferUnsafeFramedInputStream(ReadableByteChannel channel,
      int bufferSize) throws IOException {
    super(channel, bufferSize);
    this.maxFrameBufferSize = this.buffer.capacity() - 4 /* size of int */;
  }

  @Override
  public final int readFrame() throws IOException {
    int frameSize = readInt();
    final int maxFrameBufferSize = this.maxFrameBufferSize;
    // return 0 if the full frame was read else the remaining size of frame
    // considering the buffer size as the max possible frame size to be read
    if (frameSize > maxFrameBufferSize) {
      frameSize = maxFrameBufferSize;
    }
    final long addrLimit = this.addrLimit;
    final int available = (int)(addrLimit - this.addrPosition);
    if (available >= frameSize) {
      return 0;
    }
    else {
      final ByteBuffer buffer = this.buffer;
      // if there is no room in buffer for frameSize to fit, then compact
      final int capacity = buffer.capacity();
      final int position = (int)(this.addrPosition - this.baseAddress);
      final int limit = position + available;
      final int freeSpace = (capacity - position);
      if (frameSize > freeSpace) {
        buffer.position(position);
        buffer.limit(limit);
        buffer.compact();
      }
      else {
        // position the buffer at the limit for further channel reads
        buffer.position(limit);
        buffer.limit(capacity);
      }
      return (frameSize - available);
    }
  }

  @Override
  public final int readFrameFragment(int fragmentSize) throws IOException {
    final int numBytes = this.channel.read(this.buffer);
    if (numBytes >= 0) {
      this.bytesRead += numBytes;
      fragmentSize -= numBytes;
      if (fragmentSize <= 0) {
        // ready the frame for reading
        this.addrLimit = this.baseAddress + this.buffer.position();
        return 0;
      }
      else {
        return fragmentSize;
      }
    }
    else {
      throw new EOFException(
          "readFrameFragment: premature end of stream while reading "
              + fragmentSize + " bytes");
    }
  }
}
