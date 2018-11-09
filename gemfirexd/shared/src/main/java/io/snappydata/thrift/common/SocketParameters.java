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

package io.snappydata.thrift.common;

import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.pivotal.gemfirexd.Attribute;
import io.snappydata.thrift.ServerType;
import org.apache.thrift.transport.TSSLTransportFactory;

/**
 * A class to hold all of the socket parameters including SSL parameters.
 */
public class SocketParameters extends
    TSSLTransportFactory.TSSLTransportParameters {

  /**
   * Default input and output socket buffer size.
   */
  public static final int DEFAULT_BUFFER_SIZE = 32 * 1024;

  private static final java.util.HashMap<String, Param> paramsMap =
      new java.util.HashMap<>();

  private static final java.util.HashMap<String, Param> sslParamsMap =
      new java.util.HashMap<>();

  private static final String[] EMPTY_CIPHERS = new String[0];

  public static java.util.Collection<Param> getAllParamsNoSSL() {
    return paramsMap.values();
  }

  public static Param findSSLParameterByPropertyName(String propertyName) {
    Param.init();
    Param p = sslParamsMap.get(propertyName
        .toLowerCase(java.util.Locale.ENGLISH));
    if (p != null) {
      return p;
    } else {
      throw new IllegalArgumentException("Unknown SSL property '"
          + propertyName + "'; expected one of: " + sslParamsMap.keySet());
    }
  }

  /**
   * Enumeration of all the supported socket parameters (except "serverType")
   * including SSL properties. The enumeration has method to parse the string
   * value and set into SocketParameters object so provides a uniform way to
   * read in socket properties and initialize SocketParameters.
   */
  public enum Param {
    INPUT_BUFFER_SIZE(Attribute.SOCKET_INPUT_BUFFER_SIZE, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.inputBufferSize = Integer.parseInt(value);
      }
    },
    OUTPUT_BUFFER_SIZE(Attribute.SOCKET_OUTPUT_BUFFER_SIZE, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.outputBufferSize = Integer.parseInt(value);
      }
    },
    READ_TIMEOUT(Attribute.READ_TIMEOUT, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.readTimeout = Integer.parseInt(value);
      }
    },
    KEEPALIVE_IDLE(Attribute.KEEPALIVE_IDLE, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keepAliveIdle = Integer.parseInt(value);
      }
    },
    KEEPALIVE_INTERVAL(Attribute.KEEPALIVE_INTVL, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keepAliveInterval = Integer.parseInt(value);
      }
    },
    KEEPALIVE_COUNT(Attribute.KEEPALIVE_CNT, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keepAliveCount = Integer.parseInt(value);
      }
    },
    SSL_PROTOCOL("protocol", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.protocol = value;
      }
    },
    SSL_ENABLED_PROTOCOLS("enabled-protocols", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.sslEnabledProtocols = value.split(":");
      }
    },
    SSL_CIPHER_SUITES("cipher-suites", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.cipherSuites = value.split(":");
      }
    },
    SSL_CLIENT_AUTH("client-auth", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.clientAuth = Boolean.parseBoolean(value);
      }
    },
    SSL_KEYSTORE("keystore", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keyStore = value;
        params.isKeyStoreSet = true;
      }
    },
    SSL_KEYSTORE_TYPE("keystore-type", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keyStoreType = value;
      }
    },
    SSL_KEYSTORE_PASSWORD("keystore-password", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keyPass = value;
      }
    },
    SSL_KEYMANAGER_TYPE("keymanager-type", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keyManagerType = value;
      }
    },
    SSL_TRUSTSTORE("truststore", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.trustStore = value;
        params.isTrustStoreSet = true;
      }
    },
    SSL_TRUSTSTORE_TYPE("truststore-type", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.trustStoreType = value;
      }
    },
    SSL_TRUSTSTORE_PASSWORD("truststore-password", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.trustPass = value;
      }
    },
    SSL_TRUSTMANAGER_TYPE("trustmanager-type", true) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.trustManagerType = value;
      }
    };

    private final String propertyName;

    Param(String propertyName, boolean ssl) {
      this.propertyName = propertyName;
      if (ssl) {
        sslParamsMap.put(propertyName, this);
      } else {
        paramsMap.put(propertyName, this);
      }
    }

    static void init() {
    }

    public abstract void setParameter(SocketParameters params, String value);

    public String getPropertyName() {
      return this.propertyName;
    }
  }

  private volatile ServerType serverType;
  private boolean hasSSLParams;
  private String[] sslEnabledProtocols;
  private int inputBufferSize;
  private int outputBufferSize;
  private int readTimeout;
  private int keepAliveIdle;
  private int keepAliveInterval;
  private int keepAliveCount;

  /**
   * Default empty parameters.
   */
  public SocketParameters() {
    super(null, EMPTY_CIPHERS, false);
    this.protocol = null;

    // set socket parameters from global defaults
    final SystemProperties props = SystemProperties.getClientInstance();
    this.inputBufferSize = props.getInteger(
        Attribute.SOCKET_INPUT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
    this.outputBufferSize = props.getInteger(
        Attribute.SOCKET_OUTPUT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
    this.readTimeout = props.getInteger(Attribute.READ_TIMEOUT, 0);
    this.keepAliveIdle = props.getInteger(SystemProperties.KEEPALIVE_IDLE,
        SystemProperties.DEFAULT_KEEPALIVE_IDLE);
    this.keepAliveInterval = props.getInteger(SystemProperties.KEEPALIVE_INTVL,
        SystemProperties.DEFAULT_KEEPALIVE_INTVL);
    this.keepAliveCount = props.getInteger(SystemProperties.KEEPALIVE_CNT,
        SystemProperties.DEFAULT_KEEPALIVE_CNT);
  }

  /**
   * Create empty parameters specifying the server type.
   */
  public SocketParameters(ServerType serverType) {
    this();
    this.serverType = serverType;
  }

  public void setServerType(ServerType serverType) {
    this.serverType = serverType;
  }

  public final ServerType getServerType() {
    return this.serverType;
  }

  public final int getInputBufferSize() {
    return this.inputBufferSize;
  }

  public final int getOutputBufferSize() {
    return this.outputBufferSize;
  }

  public final int getReadTimeout() {
    return this.readTimeout;
  }

  public final int getKeepAliveIdle() {
    return this.keepAliveIdle;
  }

  public final int getKeepAliveInterval() {
    return this.keepAliveInterval;
  }

  public final int getKeepAliveCount() {
    return this.keepAliveCount;
  }

  void setHasSSLParams() {
    this.hasSSLParams = true;
  }

  public final boolean hasSSLParams() {
    return this.hasSSLParams;
  }

  public final String getSSLProtocol() {
    return this.protocol;
  }

  public final String[] getSSLEnabledProtocols() {
    return this.sslEnabledProtocols;
  }

  public final String getSSLKeyStore() {
    return this.keyStore;
  }

  public final String getSSLKeyPass() {
    return this.keyPass;
  }

  public final String getSSLKeyManagerType() {
    return this.keyManagerType;
  }

  public final String getSSLKeyStoreType() {
    return this.keyStoreType;
  }

  public final String getSSLTrustStore() {
    return this.trustStore;
  }

  public final String getSSLTrustPass() {
    return this.trustPass;
  }

  public final String getSSLTrustManagerType() {
    return this.trustManagerType;
  }

  public final String getSSLTrustStoreType() {
    return this.trustStoreType;
  }

  public final String[] getSSLCipherSuites() {
    return this.cipherSuites;
  }

  public final boolean getSSLClientAuth() {
    return this.clientAuth;
  }

  public final boolean isSSLKeyStoreSet() {
    return this.isKeyStoreSet;
  }

  public final boolean isSSLTrustStoreSet() {
    return this.isTrustStoreSet;
  }
}
