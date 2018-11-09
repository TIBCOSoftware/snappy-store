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

package io.snappydata.thrift.server;

import java.net.InetSocketAddress;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import io.snappydata.thrift.common.SSLFactory;
import io.snappydata.thrift.common.SocketParameters;
import org.apache.thrift.transport.TTransportException;

/**
 * A Factory for providing and setting up server SSL wrapped SnappyTServerSocket.
 * <p>
 * Modified from <code>TSSLTransportFactory</code> to add Snappy specific config.
 */
public abstract class SnappyTSSLServerSocketFactory {

  private SnappyTSSLServerSocketFactory() {
    // no instance
  }

  /**
   * Get a configured SSL wrapped TServerSocket bound to the specified port and
   * interface.
   * <p>
   * If SocketParameters have SSL properties set, then they are used to set the
   * values for the algorithms, keystore, truststore and other settings.
   * <p>
   * Else if SocketParameters don't have SSL settings, then the default settings
   * are used. Default settings are retrieved from server System properties.
   *
   * Example system properties: -Djavax.net.ssl.trustStore=<truststore location>
   * -Djavax.net.ssl.trustStorePassword=password
   * -Djavax.net.ssl.keyStore=<keystore location>
   * -Djavax.net.ssl.keyStorePassword=password
   *
   *
   * @return An SSL wrapped {@link SnappyTSSLServerSocket}
   */
  public static SnappyTSSLServerSocket getServerSocket(
      InetSocketAddress bindAddress, SocketParameters params)
      throws TTransportException {
    SSLContext ctx = SSLFactory.createSSLContext(params);
    return createServer(ctx.getServerSocketFactory(), bindAddress, params);
  }

  private static SnappyTSSLServerSocket createServer(
      SSLServerSocketFactory factory, InetSocketAddress bindAddress,
      SocketParameters params) throws TTransportException {
    try {
      SSLServerSocket serverSocket = (SSLServerSocket)factory
          .createServerSocket(bindAddress.getPort(), 100,
              bindAddress.getAddress());
      if (params != null) {
        if (params.getSSLEnabledProtocols() != null) {
          serverSocket.setEnabledProtocols(params.getSSLEnabledProtocols());
        }
        if (params.getSSLCipherSuites() != null) {
          serverSocket.setEnabledCipherSuites(params.getSSLCipherSuites());
        }
        serverSocket.setNeedClientAuth(params.getSSLClientAuth());
      }
      return new SnappyTSSLServerSocket(serverSocket, bindAddress, params);
    } catch (Exception e) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Could not bind to host:port " + bindAddress.toString(), e);
    }
  }
}
