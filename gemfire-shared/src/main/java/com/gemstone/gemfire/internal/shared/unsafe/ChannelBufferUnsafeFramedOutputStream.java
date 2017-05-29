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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * Extension of {@link ChannelBufferUnsafeDataOutputStream} to write in frames
 * i.e. write the size of frame at the start. This is like
 * <code>TFramedTransport</code> but the max frame size will be limited to the
 * buffer size so that we never create/allocate large buffers and the frame size
 * is only a hint to the receiving server to stop waiting for more data and
 * start processing it.
 * 
 * @author swale
 * @since gfxd 1.1
 */
public final class ChannelBufferUnsafeFramedOutputStream extends
    ChannelBufferUnsafeDataOutputStream {

  protected boolean doWriteFrameSize;

  public ChannelBufferUnsafeFramedOutputStream(WritableByteChannel channel)
      throws IOException {
    super(channel);
    // position the buffer to skip the length of frame at the start
    this.addrPosition += 4;
    this.doWriteFrameSize = true;
  }

  public ChannelBufferUnsafeFramedOutputStream(WritableByteChannel channel,
      int bufferSize) throws IOException {
    super(channel, bufferSize);
    // position the buffer to skip the length of frame at the start
    this.addrPosition += 4;
    this.doWriteFrameSize = true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void flush() throws IOException {
    if (this.doWriteFrameSize) {
      // write the length now at the start
      final long baseAddr = this.baseAddress;
      putInt(baseAddr, (int)(this.addrPosition - baseAddr - 4));
      super.flushBufferBlocking(this.buffer);
    }
    else {
      if (this.addrPosition > this.baseAddress) {
        super.flushBufferBlocking(this.buffer);
      }
      // reset "doWriteFrameSize" to true to indicate start of a new frame
      // whose frame size will be written at next flush
      this.doWriteFrameSize = true;
    }
    // position the buffer to skip the length of frame at the start
    this.addrPosition += 4;
  }

  @Override
  protected void flushBufferBlocking(final ByteBuffer buffer)
      throws IOException {
    if (this.doWriteFrameSize) {
      // write the length now at the start
      final long baseAddr = this.baseAddress;
      putInt(baseAddr, (int)(this.addrPosition - baseAddr - 4));
      // set the flag to false to indicate that frame size no longer
      // needs to be written till the actual end of message (which will
      // happen when the explicit flush is invoked)
      this.doWriteFrameSize = false;
    }
    super.flushBufferBlocking(buffer);
  }
}
