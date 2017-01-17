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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.ObjToByteArraySerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import org.apache.spark.unsafe.Platform;

/**
 * GfxdHeapDataOutputStream extends {@link HeapDataOutputStream} from which it
 * derives most of its functionality. The only difference is in the
 * write(bytes[] source, int offset, int len) which wraps the source byte[]
 * passed for creating the internal ByteBuffer. The instance of this class can
 * be used if the source byte[] array is immutable in terms of its data , a
 * guarantee which an application may be able to offer. The benefit gained is in
 * preventing the copying of data from source byte[] to internal byte buffer.
 *
 * @author Asif
 */
public final class GfxdHeapDataOutputStream extends HeapDataOutputStream
    implements ObjToByteArraySerializer {

  static final int MIN_SIZE = Integer.getInteger(
      "gemfirexd.heap-output-stream-size", 512);

  final GfxdHeapThresholdListener thresholdListener;

  final String query;

  final boolean wrapBytes;

  // private final int byteArrayWrapLength;
  public GfxdHeapDataOutputStream(
      final GfxdHeapThresholdListener thresholdListener, final String query,
      final boolean wrapBytes, final Version v) {
    this(MIN_SIZE, thresholdListener, query, wrapBytes, v);
  }

  public GfxdHeapDataOutputStream(final int minSize,
      final GfxdHeapThresholdListener thresholdListener, final String query,
      final boolean wrapBytes, final Version v) {
    super(minSize, v);
    this.thresholdListener = thresholdListener;
    this.query = query;
    this.wrapBytes = wrapBytes;
    markForReuse();
  }

  @Override
  public final void write(byte[] source, int offset, int len) {
    // if there is enough space in current byte buffer, then use that instead
    // of always wrapping to avoid one additional allocation
    if (this.wrapBytes &&
        this.buffer.position() > 0 && this.buffer.remaining() < len) {
      this.writeWithByteArrayWrappedConditionally(source, offset, len);
    } else {
      super.write(source, offset, len);
    }
  }

  /**
   * Write a byte buffer to this HeapDataOutputStream,
   * <p>
   * The contents of the buffer between the position and the limit
   * are copied to the output stream or the buffer kept as is (if wrapBytes
   * has been passed as true in constructor).
   */
  @Override
  public final void write(ByteBuffer source) {
    if (this.wrapBytes) {
      this.writeWithByteBufferWrappedConditionally(source);
    } else {
      super.write(source);
    }
  }

  /**
   * Efficient copy of data from given source object to self.
   * Used by generated code of ComplexTypeSerializer implementation.
   */
  public final void copyMemory(final Object src, long srcOffset, int length) {
    // require that buffer is a heap byte[] one
    ByteBuffer buffer = this.buffer;
    byte[] dst = buffer.array();
    int offset = buffer.arrayOffset();
    int pos = buffer.position();
    // copy into as available space first
    final int remainingSpace = buffer.capacity() - pos;
    if (remainingSpace < length) {
      Platform.copyMemory(src, srcOffset, dst,
          Platform.BYTE_ARRAY_OFFSET + offset + pos, remainingSpace);
      buffer.position(pos + remainingSpace);
      srcOffset += remainingSpace;
      length -= remainingSpace;
      ensureCapacity(length);
      // refresh buffer variables
      buffer = this.buffer;
      dst = buffer.array();
      offset = buffer.arrayOffset();
      pos = buffer.position();
    }
    // copy remaining bytes
    Platform.copyMemory(src, srcOffset, dst,
        Platform.BYTE_ARRAY_OFFSET + offset + pos, length);
    buffer.position(pos + length);
  }

  @Override
  protected final void expand(int amount) {
    Misc.checkMemoryRuntime(thresholdListener, query, amount);
    super.expand(amount);
  }
}
