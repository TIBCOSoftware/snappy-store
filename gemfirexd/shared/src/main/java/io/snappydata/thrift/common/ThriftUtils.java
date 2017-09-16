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
 * Changes for SnappyData data platform.
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

package io.snappydata.thrift.common;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.concurrent.locks.LockSupport;

import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.shared.unsafe.DirectBufferAllocator;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import io.snappydata.thrift.BlobChunk;
import io.snappydata.thrift.HostAddress;
import io.snappydata.thrift.TransactionAttribute;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * Some Thrift utility methods shared by client and server code. Only has static
 * methods so no instance allowed.
 */
public abstract class ThriftUtils {

  /**
   * no instance allowed
   */
  private ThriftUtils() {
  }

  public static boolean isThriftSelectorServer() {
    return SystemProperties.getServerInstance().getBoolean(
        Attribute.THRIFT_SELECTOR_SERVER, false);
  }

  public static HostAddress getHostAddress(final String hostNameAndAddress,
      final int port) {
    final int slashIndex = hostNameAndAddress.indexOf('/');
    if (slashIndex > 0) {
      final String hostName = hostNameAndAddress.substring(0, slashIndex);
      final String ipAddress = hostNameAndAddress.substring(slashIndex + 1);
      if (ipAddress.length() > 0 && !hostName.equals(ipAddress)) {
        return new HostAddress(hostName, port).setIpAddress(ipAddress);
      } else {
        return new HostAddress(hostName, port);
      }
    } else if (slashIndex == 0) {
      return new HostAddress(hostNameAndAddress.substring(1), port);
    } else {
      return new HostAddress(hostNameAndAddress, port);
    }
  }

  private static final SharedUtils.CSVVisitor<SocketParameters, Void>
      parseSSLParams = new SharedUtils.CSVVisitor<SocketParameters, Void>() {
    @Override
    public void visit(String str, SocketParameters sslParams, Void ignore) {
      int eqIndex = str.indexOf('=');
      if (eqIndex > 0) {
        String key = str.substring(0, eqIndex).trim();
        String value = str.substring(eqIndex + 1).trim();
        try {
          SocketParameters.findSSLParameterByPropertyName(key).setParameter(
              sslParams, value);
        } catch (NumberFormatException nfe) {
          throw new IllegalArgumentException(
              "Expected numeric format for SSL property '" + key +
                  "' but got: " + value, nfe);
        }
      } else {
        throw new IllegalArgumentException("Missing equality: expected "
            + "comma-separated <property>=<value> pairs");
      }
    }
  };

  /**
   * Convert comma-separated list of SSL key, value pairs into thrift SSL
   * parameters list.
   */
  public static void getSSLParameters(SocketParameters socketParams,
      String sslProperties) {
    if (sslProperties != null && sslProperties.length() > 0) {
      socketParams.setHasSSLParams();
      SharedUtils.splitCSV(sslProperties, parseSSLParams, socketParams, null);
    }
  }

  public static EnumMap<TransactionAttribute, Boolean> newTransactionFlags() {
    return new EnumMap<>(TransactionAttribute.class);
  }

  public static byte[] toBytes(ByteBuffer buffer) {
    return ClientSharedUtils.toBytes(buffer);
  }

  public static ByteBuffer readByteBuffer(TNonblockingTransport transport,
      int length) throws TTransportException {
    if (length == 0) {
      return ByteBuffer.wrap(ClientSharedData.ZERO_ARRAY);
    }
    if (transport.getBytesRemainingInBuffer() >= length) {
      ByteBuffer buffer = ByteBuffer.wrap(transport.getBuffer(),
          transport.getBufferPosition(), length);
      transport.consumeBuffer(length);
      return buffer;
    }

    // use normal byte array if length is not large since direct byte buffer
    // has additional overheads of allocation and finalization
    if (length <= (SocketParameters.DEFAULT_BUFFER_SIZE >>> 1)) {
      byte[] buffer = new byte[length];
      transport.readAll(buffer, 0, length);
      return ByteBuffer.wrap(buffer);
    }

    // this might be too big for ByteBuffer.allocateDirect -- see
    // sun.misc.VM.maxDirectMemory()
    ByteBuffer buffer;
    try {
      buffer = DirectBufferAllocator.instance().allocate(length, "THRIFT");
    } catch (OutOfMemoryError | RuntimeException ignored) {
      // fallback to heap buffer
      buffer = ByteBuffer.allocate(length);
    }
    buffer.limit(length);
    try {
      while (length > 0) {
        int numReadBytes = transport.read(buffer);
        if (numReadBytes > 0) {
          length -= numReadBytes;
        } else if (numReadBytes == 0) {
          // sleep a bit before retrying
          // TODO: this should use selector signal
          Thread.sleep(1);
        } else {
          throw new EOFException("Socket channel closed in read.");
        }
      }
    } catch (IOException | InterruptedException e) {
      throw new TTransportException(e instanceof EOFException
          ? TTransportException.END_OF_FILE : TTransportException.UNKNOWN);
    }
    buffer.flip();
    return buffer;
  }

  public static void writeByteBuffer(ByteBuffer buffer,
      TTransport transport, TNonblockingTransport nonBlockingTransport,
      int length) throws TTransportException {
    if (buffer.hasArray()) {
      transport.write(buffer.array(), buffer.position() + buffer.arrayOffset(),
          length);
    } else if (nonBlockingTransport != null) {
      try {
        final int position = buffer.position();
        while (length > 0) {
          int numWrittenBytes = nonBlockingTransport.write(buffer);
          if (numWrittenBytes > 0) {
            length -= numWrittenBytes;
          } else if (numWrittenBytes == 0) {
            // sleep a bit before retrying
            // TODO: this should use selector signal
            LockSupport.parkNanos(ClientSharedUtils.PARK_NANOS_FOR_READ_WRITE);
          } else {
            throw new EOFException("Socket channel closed in write.");
          }
        }
        // move back to original position
        buffer.position(position);
      } catch (IOException e) {
        throw new TTransportException(e instanceof EOFException
            ? TTransportException.END_OF_FILE : TTransportException.UNKNOWN);
      }
    } else {
      final byte[] bytes = ClientSharedUtils.toBytes(
          buffer, buffer.remaining(), length);
      transport.write(bytes, 0, length);
    }
  }

  public static void releaseBlobChunk(BlobChunk chunk) {
    UnsafeHolder.releaseIfDirectBuffer(chunk.chunk);
    chunk.chunk = null;
  }
}
