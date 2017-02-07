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

package io.snappydata.thrift.common;

import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransport;

/**
 * Adds optimized writeBinary for direct ByteBuffers avoiding an additional
 * copy if the underlying transport allows it.
 */
public final class TBinaryProtocolOpt extends TBinaryProtocol {

  private final TNonblockingTransport nonBlockingTransport;
  private final long stringLengthLimit;
  private final boolean useDirectBuffers;

  public TBinaryProtocolOpt(TTransport trans, boolean useDirectBuffers) {
    this(trans, -1, -1, false, true, useDirectBuffers);
  }

  public TBinaryProtocolOpt(TTransport transport, long stringLengthLimit,
      long containerLengthLimit, boolean strictRead, boolean strictWrite,
      boolean useDirectBuffers) {
    super(transport, stringLengthLimit, containerLengthLimit, strictRead,
        strictWrite);
    if (transport instanceof TNonblockingTransport) {
      this.nonBlockingTransport = (TNonblockingTransport)transport;
    } else {
      this.nonBlockingTransport = null;
    }
    this.stringLengthLimit = stringLengthLimit;
    this.useDirectBuffers = useDirectBuffers;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ByteBuffer readBinary() throws TException {
    if (this.useDirectBuffers && this.nonBlockingTransport != null) {
      int length = readI32();
      if (this.stringLengthLimit > 0 && length > this.stringLengthLimit) {
        throw new TProtocolException(TProtocolException.SIZE_LIMIT,
            "Binary field exceeded string size limit");
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

    private final boolean useDirectBuffers;

    public Factory(boolean useDirectBuffers) {
      super();
      this.useDirectBuffers = useDirectBuffers;
    }

    public Factory(boolean strictRead, boolean strictWrite,
        long stringLengthLimit, long containerLengthLimit,
        boolean useDirectBuffers) {
      super(strictRead, strictWrite, stringLengthLimit, containerLengthLimit);
      this.useDirectBuffers = useDirectBuffers;
    }

    public TProtocol getProtocol(TTransport trans) {
      return new TBinaryProtocolOpt(trans, stringLengthLimit_,
          containerLengthLimit_, strictRead_, strictWrite_, useDirectBuffers);
    }
  }
}
