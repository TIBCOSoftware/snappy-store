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

package com.gemstone.gemfire.internal.shared;

import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.logging.Logger;

/**
 * Set the global system properties. This class is the supported way to change
 * system properties before client (or server) side API is invoked that uses one
 * or more properties defined in the class.
 *
 * @author swale
 * @since gfxd 1.1
 */
public final class SystemProperties {

  /** The name of the "logFile" property */
  public static final String LOG_FILE_NAME = "log-file";

  /**
   * The socket buffer size to use for reading.
   */
  public static final String SOCKET_INPUT_BUFFER_SIZE_NAME =
      "socket-input-buffer-size";

  /**
   * The socket buffer size to use for writing.
   */
  public static final String SOCKET_OUTPUT_BUFFER_SIZE_NAME =
      "socket-output-buffer-size";

  /**
   * Read timeout for the connection, in seconds. Only for thin client
   * connections.
   */
  public static final String READ_TIMEOUT_NAME = "read-timeout";

  /**
   * TCP KeepAlive IDLE timeout in seconds for the network server and client
   * sockets. This is the idle time after which a TCP KeepAlive probe is sent
   * over the socket to determine if the other side is alive or not.
   */
  public static final String KEEPALIVE_IDLE_NAME = "keepalive-idle";

  /**
   * TCP KeepAlive INTERVAL timeout in seconds for the network server and client
   * sockets. This is the time interval between successive TCP KeepAlive probes
   * if there is no response to the previous probe ({@link #KEEPALIVE_IDLE_NAME})
   * to determine if the other side is alive or not.
   *
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  public static final String KEEPALIVE_INTVL_NAME = "keepalive-interval";

  /**
   * TCP KeepAlive COUNT for the network server and client sockets. This is the
   * number of TCP KeepAlive probes sent before declaring the other side to be
   * dead.
   *
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  public static final String KEEPALIVE_CNT_NAME = "keepalive-count";

  /**
   * The maximum size of <code>DNSCacheService</code>. Zero or negative means to
   * use Java default DNS cache and not <code>DNSCacheService</code>. Default is
   * {@value #DEFAULT_DNS_CACHE_SIZE}.
   */
  public static final String DNS_CACHE_SIZE = "dns-cache-size";

  /**
   * Time interval in seconds after which <code>DNSCacheService</code> is
   * flushed and refreshed on demand for DNS entries that do no specify a TTL. A
   * value of zero or negative means the cache is never flushed of such records.
   * Default is {@value #DEFAULT_DNS_CACHE_FLUSH_INTERVAL} seconds.
   */
  public static final String DNS_CACHE_FLUSH_INTERVAL =
      "dns-cache-flush-interval";

  /**
   * Whether to use selector based server (recommended) for the thrift server or
   * per connection thread server model. Thrift SSL implementation cannot use
   * selectors yet. Default is true for non-SSL and false for SSL.
   */
  public static final String THRIFT_SELECTOR_SERVER = "thrift-selector";

  public static final String DEFAULT_PROPERTY_NAME_PREFIX = "gemfire.";
  public static final String DEFAULT_GFXDCLIENT_PROPERTY_NAME_PREFIX =
      "gemfirexd.client.";

  public static String DEFAULT_LOG_FILE = null;
  public static final int DEFAULT_INPUT_BUFFER_SIZE = 32 * 1024;
  public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 32 * 1024;
  // these are the defaults for the per-socket TCP keepalive parameters
  public static final int DEFAULT_KEEPALIVE_IDLE = 20;
  public static final int DEFAULT_KEEPALIVE_INTVL = 1;
  public static final int DEFAULT_KEEPALIVE_CNT = 10;
  public static final int DEFAULT_DNS_CACHE_SIZE = 0;
  public static final int DEFAULT_DNS_CACHE_FLUSH_INTERVAL = 3600;

  public static final String GFXD_FACTORY_PROVIDER = "com.pivotal.gemfirexd."
      + "internal.engine.store.entry.GfxdObjectFactoriesProvider";

  private String logFile;
  private int sockInputBufferSize;
  private int sockOutputBufferSize;
  private int keepAliveIdle;
  private int keepAliveIntvl;
  private int keepAliveCnt;
  private int dnsCacheSize;
  private int dnsCacheFlushInterval;

  /**
   * Common internal product callbacks on client and server side to possibly
   * plugin some system behaviour.
   */
  public interface Callbacks {

    /** Get prefix to be used for system property names. */
    String getSystemPropertyNamePrefix();

    /** Get system property for given key or null if key not set. */
    String getSystemProperty(String key, SystemProperties properties)
        throws PrivilegedActionException;
  }

  public static class DefaultCallbacks implements Callbacks,
      PrivilegedExceptionAction<String> {

    private final String propertyNamePrefix;
    private String _propertyName;

    public DefaultCallbacks(String propertyNamePrefix) {
      this.propertyNamePrefix = propertyNamePrefix;
    }

    /**
     * {@inheritDoc}
     */
    public String getSystemPropertyNamePrefix() {
      return this.propertyNamePrefix;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized String getSystemProperty(String key,
        SystemProperties properties) throws PrivilegedActionException {
      this._propertyName = this.propertyNamePrefix + key;
      String value = AccessController.doPrivileged(this);
      this._propertyName = null;
      return value;
    }

    /**
     * {@inheritDoc}
     */
    public String run() {
      final String key = this._propertyName;
      if (key != null) {
        return System.getProperty(key);
      }
      else {
        return null;
      }
    }
  }

  private static final SystemProperties serverInstance;
  private static final SystemProperties clientInstance;

  private volatile Callbacks callbacks;

  static {
    final Callbacks cb;
    if (isUsingGemFireXDEntryPoint()) {
      cb = getGFXDServerCallbacks();
    }
    else {
      cb = new DefaultCallbacks(DEFAULT_PROPERTY_NAME_PREFIX);
    }
    serverInstance = new SystemProperties(cb);

    clientInstance = new SystemProperties(new DefaultCallbacks(
        DEFAULT_GFXDCLIENT_PROPERTY_NAME_PREFIX));
  }

  public static boolean isUsingGemFireXDEntryPoint() {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    for (StackTraceElement frame : stack) {
      final String frameCls = frame.getClassName();
      if ("com.pivotal.gemfirexd.internal.engine.store.GemFireStore"
          .equals(frameCls)
          || "com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor"
              .equals(frameCls)
          || "com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher"
              .equals(frameCls)
          || "com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl"
              .equals(frameCls)
          || "com.pivotal.gemfirexd.tools.GfxdDistributionLocator"
              .equals(frameCls)
          || "com.pivotal.gemfirexd.tools.GfxdAgentLauncher"
              .equals(frameCls)
          || "com.pivotal.gemfirexd.internal.engine.fabricservice.FabricAgentImpl"
              .equals(frameCls)
          || "com.pivotal.gemfirexd.tools.GfxdSystemAdmin"
               .equals(frameCls)
          || "com.pivotal.gemfirexd.internal.GemFireXDVersion"
              .equals(frameCls)
          || "io.snappydata.gemxd.SnappyDataVersion$"
              .equals(frameCls)) {
        return true;
      }
    }
    return false;
  }

  private SystemProperties(Callbacks cb) {
    this.callbacks = cb;
    // no external instances allowed
    refreshProperties();
  }

  /**
   * Get an instance appropriate for server instances. Usually this will use a
   * different prefix and means of reading file properties than "client"
   * instances.
   */
  public static SystemProperties getServerInstance() {
    return serverInstance;
  }

  /**
   * Get an instance appropriate for client instances. Usually this will use a
   * different prefix and means of reading file properties than "server"
   * instances.
   * <p>
   * NOTE: This will also read server prefixed instances as fallback to handle
   * the case for few common properties (like in <code>ClientSharedUtils</code>)
   * when used in JVMs that are configure to be both client and server.
   */
  public static SystemProperties getClientInstance() {
    return clientInstance;
  }

  public static Callbacks getGFXDServerCallbacks() {
    final String provider = GFXD_FACTORY_PROVIDER;
    try {
      Class<?> factoryProvider = Class.forName(provider);
      Method method;

      method = factoryProvider.getDeclaredMethod("getSystemCallbacksImpl");
      return (Callbacks)method.invoke(null);
    } catch (Exception e) {
      throw new IllegalStateException("Exception in obtaining GemFireXD "
          + "Objects Factory provider class", e);
    }
  }

  public synchronized void setCallbacks(Callbacks cb) {
    this.callbacks = cb;
    // reinitialize the property values if required
    refreshProperties();
  }

  public synchronized Callbacks getCallbacks() {
    return this.callbacks;
  }

  public synchronized void refreshProperties() {
    this.logFile = getString(LOG_FILE_NAME, DEFAULT_LOG_FILE);
    this.sockInputBufferSize = getInteger(
        SOCKET_INPUT_BUFFER_SIZE_NAME, DEFAULT_INPUT_BUFFER_SIZE);
    this.sockOutputBufferSize = getInteger(
        SOCKET_OUTPUT_BUFFER_SIZE_NAME, DEFAULT_OUTPUT_BUFFER_SIZE);
    this.keepAliveIdle = getInteger(KEEPALIVE_IDLE_NAME,
        DEFAULT_KEEPALIVE_IDLE);
    this.keepAliveIntvl = getInteger(KEEPALIVE_INTVL_NAME,
        DEFAULT_KEEPALIVE_INTVL);
    this.keepAliveCnt = getInteger(KEEPALIVE_CNT_NAME,
        DEFAULT_KEEPALIVE_CNT);
    this.dnsCacheSize = getInteger(DNS_CACHE_SIZE,
        DEFAULT_DNS_CACHE_SIZE);
    this.dnsCacheFlushInterval = getInteger(DNS_CACHE_FLUSH_INTERVAL,
        DEFAULT_DNS_CACHE_FLUSH_INTERVAL);
  }

  public String getString(final String name, final String defaultValue) {
    try {
      final Callbacks cb = this.callbacks;
      String value = cb.getSystemProperty(name, this);
      if (value != null) {
        return value;
      }
      else if (this == clientInstance) {
        // try with server instance
        return serverInstance.getString(name, defaultValue);
      }
      else {
        return defaultValue;
      }
    } catch (PrivilegedActionException | SecurityException e) {
      Logger log = ClientSharedUtils.getLogger();
      if (log != null) {
        StringBuilder msg = new StringBuilder(
            "Could not read system property: ");
        msg.append(name);
        ClientSharedUtils.getStackTrace(e, msg, null);
        log.warning(msg.toString());
      }
    }
    return defaultValue;
  }

  public int getInteger(String name, int defaultValue) {
    String value = getString(name, null);
    if (value != null) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException nfe) {
        throw ClientSharedUtils.newRuntimeException(
            "Expected integer value for system property [" + name
                + "] but found [" + value + "]", nfe);
      }
    }
    return defaultValue;
  }

  public long getLong(String name, long defaultValue) {
    String value = getString(name, null);
    if (value != null) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException nfe) {
        throw ClientSharedUtils.newRuntimeException(
            "Expected long value for system property [" + name
                + "] but found [" + value + "]", nfe);
      }
    }
    else {
      return defaultValue;
    }
  }

  public boolean getBoolean(String name, boolean defaultValue) {
    String value = getString(name, null);
    if (value != null) {
      return Boolean.parseBoolean(value);
    }
    else {
      return defaultValue;
    }
  }

  public String getSystemPropertyNamePrefix() {
    return this.callbacks.getSystemPropertyNamePrefix();
  }

  public String getLogFile() {
    return this.logFile;
  }

  public void setLogFile(String fileName) {
    this.logFile = fileName;
  }

  public int getSocketInputBufferSize() {
    return this.sockInputBufferSize;
  }

  public void setSocketInputBufferSize(int size) {
    this.sockInputBufferSize = size;
  }

  public int getSocketOutputBufferSize() {
    return this.sockOutputBufferSize;
  }

  public void setSocketOutputBufferSize(int size) {
    this.sockOutputBufferSize = size;
  }

  public int getKeepAliveIdle() {
    return this.keepAliveIdle;
  }

  public void setKeepAliveIdle(int v) {
    this.keepAliveIdle = v;
  }

  public int getKeepAliveInterval() {
    return this.keepAliveIntvl;
  }

  public void setKeepAliveInterval(int v) {
    this.keepAliveIntvl = v;
  }

  public int getKeepAliveCount() {
    return this.keepAliveCnt;
  }

  public void setKeepAliveCount(int v) {
    this.keepAliveCnt = v;
  }

  public int getDNSCacheSize() {
    return this.dnsCacheSize;
  }

  public void setDNSCacheSize(int v) {
    this.dnsCacheSize = v;
  }

  public int getDNSCacheFlushInterval() {
    return this.dnsCacheFlushInterval;
  }

  public void setDNSCacheFlushInterval(int v) {
    this.dnsCacheFlushInterval = v;
  }
}
