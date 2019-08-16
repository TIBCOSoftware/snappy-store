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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.*;

import org.apache.thrift.transport.TTransportException;

/**
 * A Factory for providing and setting up client SSL sockets
 * and client/server {@link SSLEngine} instances.
 */
public abstract class SSLFactory {

  private SSLFactory() {
    // no instance
  }

  /**
   * Get a configured SSL socket connected to the specified host and port.
   * <p>
   * If SSLSocketParameters are not null, then they are used to set the values
   * for the algorithms, keystore, truststore and other settings.
   * <p>
   * Else if SSLSocketParameters are null then the default settings are used.
   * Default settings are retrieved from System properties that are set.
   * <p>
   * Example system properties: -Djavax.net.ssl.trustStore=<truststore location>
   * -Djavax.net.ssl.trustStorePassword=password
   * -Djavax.net.ssl.keyStore=<keystore location>
   * -Djavax.net.ssl.keyStorePassword=password
   * <p>
   * All the client methods return a bound connection, so there is no need to
   * call open() on the TTransport.
   */
  public static SSLSocket getClientSocket(InetAddress hostAddress, int port,
      int timeout, SocketParameters params) throws TTransportException {
    SSLContext ctx = createSSLContext(params);
    return createClient(ctx.getSocketFactory(), hostAddress, port, timeout,
        params);
  }

  private static SSLSocket createClient(SSLSocketFactory factory,
      InetAddress hostAddress, int port, int timeout,
      final SocketParameters params) throws TTransportException {
    try {
      SSLSocket socket = (SSLSocket)factory.createSocket(hostAddress, port);
      socket.setSoTimeout(timeout);
      if (params != null) {
        if (params.getSSLEnabledProtocols() != null) {
          socket.setEnabledProtocols(params.getSSLEnabledProtocols());
        }
        if (params.getSSLCipherSuites() != null) {
          socket.setEnabledCipherSuites(params.getSSLCipherSuites());
        }
      }
      return socket;
    } catch (IOException ioe) {
      throw new TTransportException(TTransportException.NOT_OPEN, ioe);
    } catch (Exception e) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Could not connect to " + hostAddress + " on port " + port, e);
    }
  }

  public static SSLEngine createEngine(String peerHostName, int peerPort,
      SocketParameters params, boolean forClient) throws TTransportException {
    SSLContext ctx = createSSLContext(params);
    SSLEngine engine = ctx.createSSLEngine(peerHostName, peerPort);
    if (params != null) {
      if (params.getSSLEnabledProtocols() != null) {
        engine.setEnabledProtocols(params.getSSLEnabledProtocols());
      }
      if (params.getSSLCipherSuites() != null) {
        engine.setEnabledCipherSuites(params.getSSLCipherSuites());
      }
      if (forClient) {
        engine.setUseClientMode(true);
      } else {
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(params.getSSLClientAuth());
      }
    }
    return engine;
  }

  private static final String[] DEFAULT_PROTOCOLS = new String[]{"TLSv1.2",
      "Default", "TLSv1", "TLS"};

  public static SSLContext createSSLContext(SocketParameters params)
      throws TTransportException {
    SSLContext ctx;
    FileInputStream tsInput = null, ksInput = null;
    try {
      if (params == null || !params.hasSSLParams()) {
        return SSLContext.getDefault();
      } else if (!(params.isSSLKeyStoreSet() || params.isSSLTrustStoreSet())) {
        throw new TTransportException(
            "Either one of the KeyStore or TrustStore must be set "
                + "in SSLSocketParameters having explicit SSL parameters");
      } else if (params.getSSLProtocol() != null) {
        ctx = SSLContext.getInstance(params.getSSLProtocol());
      } else {
        ctx = null;
        NoSuchAlgorithmException failure = null;
        // Start from default to TLS (hopefully "default" will allow using
        // higher strength protocols if they become available in future).
        for (String protocol : DEFAULT_PROTOCOLS) {
          try {
            ctx = SSLContext.getInstance(protocol);
            break;
          } catch (NoSuchAlgorithmException nsae) {
            // continue searching for a protocol
            failure = nsae;
          }
        }
        if (failure != null) {
          throw failure;
        } else if (ctx == null) {
          throw new NoSuchAlgorithmException(
              java.util.Arrays.toString(DEFAULT_PROTOCOLS));
        }
      }
      TrustManagerFactory tmf;
      KeyManagerFactory kmf;
      KeyManager[] keyManagers = null;
      TrustManager[] trustManagers = null;

      if (params.isSSLTrustStoreSet()) {
        tmf = TrustManagerFactory.getInstance(params.getSSLTrustManagerType());
        KeyStore ts = KeyStore.getInstance(params.getSSLTrustStoreType());
        tsInput = new FileInputStream(params.getSSLTrustStore());
        ts.load(tsInput, params.getSSLTrustPass().toCharArray());
        tmf.init(ts);
        trustManagers = tmf.getTrustManagers();
      }

      if (params.isSSLKeyStoreSet()) {
        kmf = KeyManagerFactory.getInstance(params.getSSLKeyManagerType());
        KeyStore ks = KeyStore.getInstance(params.getSSLKeyStoreType());
        char[] keyPass = params.getSSLKeyPass() != null ? params.getSSLKeyPass()
            .toCharArray() : null;
        ksInput = new FileInputStream(params.getSSLKeyStore());
        ks.load(ksInput, keyPass);
        kmf.init(ks, keyPass);
        keyManagers = kmf.getKeyManagers();
      }

      ctx.init(keyManagers, trustManagers, null);

    } catch (Exception e) {
      throw new TTransportException("Error creating the transport", e);
    } finally {
      if (tsInput != null) {
        try {
          tsInput.close();
        } catch (Exception ce) {
          // ignore
        }
      }
      if (ksInput != null) {
        try {
          ksInput.close();
        } catch (Exception ce) {
          // ignore
        }
      }
    }
    return ctx;
  }
}
