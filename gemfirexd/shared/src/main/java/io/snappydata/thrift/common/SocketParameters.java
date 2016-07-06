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

import com.gemstone.gemfire.internal.shared.SystemProperties;
import io.snappydata.thrift.ServerType;
import org.apache.thrift.transport.TSSLTransportFactory;

/**
 * A class to hold all of the socket parameters including SSL parameters.
 */
public class SocketParameters extends
    TSSLTransportFactory.TSSLTransportParameters {

  private static final java.util.HashMap<String, Param> paramsMap =
      new java.util.HashMap<>();

  private static final java.util.HashMap<String, Param> sslParamsMap =
      new java.util.HashMap<>();

  private static final String[] EMPTY_CIPHERS = new String[0];

  public static java.util.Collection<Param> getAllParamsNoSSL() {
    return paramsMap.values();
  }

  public static Param findSSLParameterByPropertyName(String propertyName,
      boolean throwIfNotFound) {
    Param p = sslParamsMap.get(propertyName
        .toLowerCase(java.util.Locale.ENGLISH));
    if (p != null || !throwIfNotFound) {
      return p;
    }
    else {
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
    INPUT_BUFFER_SIZE(SystemProperties.SOCKET_INPUT_BUFFER_SIZE_NAME, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.inputBufferSize = Integer.parseInt(value);
      }
    },
    OUTPUT_BUFFER_SIZE(SystemProperties.SOCKET_OUTPUT_BUFFER_SIZE_NAME, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.outputBufferSize = Integer.parseInt(value);
      }
    },
    READ_TIMEOUT(SystemProperties.READ_TIMEOUT_NAME, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.readTimeout = Integer.parseInt(value);
      }
    },
    KEEPALIVE_IDLE(SystemProperties.KEEPALIVE_IDLE_NAME, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keepAliveIdle = Integer.parseInt(value);
      }
    },
    KEEPALIVE_INTERVAL(SystemProperties.KEEPALIVE_INTVL_NAME, false) {
      @Override
      public void setParameter(SocketParameters params, String value) {
        params.keepAliveInterval = Integer.parseInt(value);
      }
    },
    KEEPALIVE_COUNT(SystemProperties.KEEPALIVE_CNT_NAME, false) {
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
    }
    ;

    private final String propertyName;

    Param(String propertyName, boolean ssl) {
      this.propertyName = propertyName;
      if (ssl) {
        sslParamsMap.put(propertyName, this);
      }
      else {
        paramsMap.put(propertyName, this);
      }
    }

    public abstract void setParameter(SocketParameters params, String value);

    public String getPropertyName() {
      return this.propertyName;
    }
  }

  private volatile ServerType serverType;
  private boolean hasSSLParams;
  private String[] sslEnabledProtocols;
  private int inputBufferSize = -1;
  private int outputBufferSize = -1;
  private int readTimeout;
  private int keepAliveIdle = -1;
  private int keepAliveInterval = -1;
  private int keepAliveCount = -1;

  /**
   * Default empty parameters.
   */
  public SocketParameters() {
    super(null, EMPTY_CIPHERS, false);
    this.protocol = null;
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

  public final int getInputBufferSize(int defaultValue) {
    if (this.inputBufferSize > 0) {
      return this.inputBufferSize;
    }
    else {
      return defaultValue;
    }
  }

  public final int getOutputBufferSize(int defaultValue) {
    if (this.outputBufferSize > 0) {
      return this.outputBufferSize;
    }
    else {
      return defaultValue;
    }
  }

  public final int getReadTimeout(int defaultValue) {
    if (this.readTimeout != 0) {
      return this.readTimeout;
    }
    else {
      return defaultValue;
    }
  }

  public final int getKeepAliveIdle(int defaultValue) {
    if (this.keepAliveIdle >= 0) {
      return this.keepAliveIdle;
    }
    else {
      return defaultValue;
    }
  }

  public final int getKeepAliveInterval(int defaultValue) {
    if (this.keepAliveInterval >= 0) {
      return this.keepAliveInterval;
    }
    else {
      return defaultValue;
    }
  }

  public final int getKeepAliveCount(int defaultValue) {
    if (this.keepAliveCount >= 0) {
      return this.keepAliveCount;
    }
    else {
      return defaultValue;
    }
  }

  void setHasSSLParams(boolean hasSSLParams) {
    this.hasSSLParams = hasSSLParams;
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
