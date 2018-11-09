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
/*
 * TCompactProtocolDirect.readVarInt32/writeVarInt32 methods have been taken
  * from Thrift's TCompactProtocol having license as below.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.snappydata.thrift.common;

import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.Helper;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransport;

/**
 * Adds optimized writeBinary for direct ByteBuffers and implements
 * ({@link TProtocolDirectBinary}) for reading avoiding an additional
 * copy if the underlying transport allows it.
 */
public final class TCompactProtocolDirect extends TCompactProtocol
    implements TProtocolDirectBinary {

  private final TNonblockingTransport nonBlockingTransport;
  private final boolean useDirectBuffers;

  public TCompactProtocolDirect(TTransport transport,
      boolean useDirectBuffers) {
    super(transport, -1, -1);
    if (transport instanceof TNonblockingTransport) {
      this.nonBlockingTransport = (TNonblockingTransport)transport;
    } else {
      this.nonBlockingTransport = null;
    }
    this.useDirectBuffers = useDirectBuffers;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ByteBuffer readDirectBinary() throws TException {
    if (this.useDirectBuffers && this.nonBlockingTransport != null) {
      int length = readVarInt32();
      if (length < 0) {
        throw new TProtocolException(TProtocolException.NEGATIVE_SIZE,
            "Negative length: " + length);
      }
      return ThriftUtils.readByteBuffer(this.nonBlockingTransport, length);
    } else {
      return super.readBinary();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void writeBinary(ByteBuffer buffer) throws TException {
    int length = buffer.remaining();
    writeVarInt32(length);
    // call this in blocking transport case also to correctly deal with
    // case when ByteBuffer is not a heap one
    ThriftUtils.writeByteBuffer(buffer, trans_, nonBlockingTransport, length);
  }

  /**
   * Copied from private TCompactProtocol.readVarint32
   */
  private int readVarInt32() throws TException {
    int result = 0;
    int shift = 0;
    if (trans_.getBytesRemainingInBuffer() >= 5) {
      byte[] buf = trans_.getBuffer();
      int pos = trans_.getBufferPosition();
      int off = 0;
      while (true) {
        byte b = buf[pos + off];
        result |= (b & 0x7f) << shift;
        if ((b & 0x80) != 0x80) break;
        shift += 7;
        off++;
      }
      trans_.consumeBuffer(off + 1);
    } else {
      while (true) {
        byte b = readByte();
        result |= (b & 0x7f) << shift;
        if ((b & 0x80) != 0x80) break;
        shift += 7;
      }
    }
    return result;
  }

  /**
   * Copied from private TCompactProtocol.writeVarint32
   */
  private void writeVarInt32(int n) throws TException {
    int idx = 0;
    final byte[] i32buf = Helper.getI32Buffer(this);
    while (true) {
      if ((n & ~0x7F) == 0) {
        i32buf[idx++] = (byte)n;
        // writeByteDirect((byte)n);
        break;
        // return;
      } else {
        i32buf[idx++] = (byte)((n & 0x7F) | 0x80);
        // writeByteDirect((byte)((n & 0x7F) | 0x80));
        n >>>= 7;
      }
    }
    trans_.write(i32buf, 0, idx);
  }

  public static class Factory implements TProtocolFactory {

    protected final boolean useDirectBuffers;

    public Factory(boolean useDirectBuffers) {
      this.useDirectBuffers = useDirectBuffers;
    }

    public TProtocol getProtocol(TTransport trans) {
      return new TCompactProtocolDirect(trans, this.useDirectBuffers);
    }
  }
}
