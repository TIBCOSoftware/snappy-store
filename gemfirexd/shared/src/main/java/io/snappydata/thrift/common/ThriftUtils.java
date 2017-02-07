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
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumMap;

import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import io.snappydata.thrift.HostAddress;
import io.snappydata.thrift.TransactionAttribute;
import org.apache.spark.unsafe.Platform;
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
        SocketParameters.findSSLParameterByPropertyName(key).setParameter(
            sslParams, value);
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
    if (ClientSharedUtils.wrapsFullArray(buffer)) {
      return buffer.array();
    } else {
      final int numBytes = buffer.remaining();
      final byte[] bytes = new byte[numBytes];
      buffer.get(bytes, 0, numBytes);
      return bytes;
    }
  }

  public static ByteBuffer readDirectBuffer(TNonblockingTransport transport,
      int length) throws TTransportException {
    if (length == 0) {
      return ByteBuffer.wrap(ClientSharedData.ZERO_ARRAY);
    }
    ByteBuffer buffer = Platform.allocateDirectBuffer(length);
    try {
      while (length > 0) {
        length -= transport.read(buffer);
      }
    } catch (IOException ioe) {
      throw new TTransportException(ioe);
    }
    return buffer;
  }

  public static void writeDirectBuffer(ByteBuffer buffer,
      TTransport transport, TNonblockingTransport nonblockingTransport,
      int length) throws TTransportException {
    if (buffer.hasArray()) {
      transport.write(buffer.array(), buffer.position() + buffer.arrayOffset(),
          length);
    } else if (nonblockingTransport != null) {
      try {
        while (length > 0) {
          length -= nonblockingTransport.write(buffer);
        }
      } catch (IOException ioe) {
        throw new TTransportException(ioe);
      }
    } else {
      final byte[] bytes = toBytes(buffer);
      transport.write(bytes, 0, length);
    }
  }
}
