/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package io.snappydata.thrift.common;

import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransport;

/**
 * Adds optimized writeBinary for direct ByteBuffers and implements
 * ({@link TProtocolDirectBinary}) for reading avoiding an additional
 * copy if the underlying transport allows it.
 */
public final class TBinaryProtocolDirect extends TBinaryProtocol
    implements TProtocolDirectBinary {

  private final TNonblockingTransport nonBlockingTransport;
  private final boolean useDirectBuffers;

  public TBinaryProtocolDirect(TTransport trans, boolean useDirectBuffers) {
    super(trans);
    if (trans instanceof TNonblockingTransport) {
      this.nonBlockingTransport = (TNonblockingTransport)trans;
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
      int length = readI32();
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
    writeI32(length);
    // call this in blocking transport case also to correctly deal with
    // case when ByteBuffer is not a heap one
    ThriftUtils.writeByteBuffer(buffer, trans_, nonBlockingTransport, length);
  }

  public static class Factory extends TBinaryProtocol.Factory {

    protected final boolean useDirectBuffers;

    public Factory(boolean useDirectBuffers) {
      this.useDirectBuffers = useDirectBuffers;
    }

    public TProtocol getProtocol(TTransport trans) {
      return new TBinaryProtocolDirect(trans, this.useDirectBuffers);
    }
  }
}
