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

  /**
   * TCP KeepAlive IDLE timeout in seconds for the network server and client
   * sockets. This is the idle time after which a TCP KeepAlive probe is sent
   * over the socket to determine if the other side is alive or not.
   */
  public static final String KEEPALIVE_IDLE = "keepalive-idle";

  /**
   * TCP KeepAlive INTERVAL timeout in seconds for the network server and client
   * sockets. This is the time interval between successive TCP KeepAlive probes
   * if there is no response to the previous probe ({@link #KEEPALIVE_IDLE}) to
   * determine if the other side is alive or not.
   * <p>
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  public static final String KEEPALIVE_INTVL = "keepalive-interval";

  /**
   * TCP KeepAlive COUNT for the network server and client sockets. This is the
   * number of TCP KeepAlive probes sent before declaring the other side to be
   * dead.
   * <p>
   * Note that this may not be supported by all platforms (e.g. Solaris), in
   * which case this will be ignored and an info-level message logged that the
   * option could not be enabled on the socket.
   */
  public static final String KEEPALIVE_CNT = "keepalive-count";

  // these are the defaults for the per-socket TCP keepalive parameters
  public static final int DEFAULT_KEEPALIVE_IDLE = 20;
  public static final int DEFAULT_KEEPALIVE_INTVL = 4;
  public static final int DEFAULT_KEEPALIVE_CNT = 10;

  public static final String DEFAULT_PROPERTY_NAME_PREFIX = "gemfire.";
  public static final String DEFAULT_GFXDCLIENT_PROPERTY_NAME_PREFIX =
      "gemfirexd.client.";

  /** The prefix used for SnappyData properties set through java system properties */
  public static final String SNAPPY_PREFIX = "snappydata.store.";

  public static final String SHADOW_SCHEMA_NAME = "SNAPPYSYS_INTERNAL";

  public static final String SHADOW_TABLE_SUFFIX = "_COLUMN_STORE_";

  public static final String SHADOW_SCHEMA_SEPARATOR = "____";

  public static final String SHADOW_SCHEMA_NAME_WITH_SEPARATOR =
      SHADOW_SCHEMA_NAME + SHADOW_SCHEMA_SEPARATOR;

  public static final String SNAPPY_HIVE_METASTORE = "SNAPPY_HIVE_METASTORE";
  public static final String SNAPPY_HIVE_METASTORE_PATH =
      "/" + SNAPPY_HIVE_METASTORE;

  /**
   * Hidden region that is used for DDL string puts for DDL statement replay on
   * new servers in GemFireXD.
   */
  public final static String DDL_STMTS_REGION = "_DDL_STMTS_META_REGION";
  public final static String DDL_STMTS_REGION_PATH = "/" + DDL_STMTS_REGION;

  public static final String GFXD_FACTORY_PROVIDER = "com.pivotal.gemfirexd."
      + "internal.engine.store.entry.GfxdObjectFactoriesProvider";

  public static final String SNAPPY_DEFAULT_COMPRESSION_CODEC = "lz4";

  public static final int SNAPPY_MIN_COLUMN_DELTA_ROWS = 200;

  /**
   * Common internal product callbacks on client and server side to possibly
   * plugin some system behaviour.
   */
  public interface Callbacks {

    /**
     * Get prefix to be used for system property names.
     */
    String getSystemPropertyNamePrefix();

    /**
     * Get system property for given key or null if key not set.
     */
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
      this._propertyName = key.startsWith(this.propertyNamePrefix)
          ? key : this.propertyNamePrefix + key;
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
      } else {
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
    } else {
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
          .equals(frameCls) ||
          "com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor"
              .equals(frameCls) ||
          "com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher"
              .equals(frameCls) ||
          "com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl"
              .equals(frameCls) ||
          "com.pivotal.gemfirexd.tools.GfxdDistributionLocator"
              .equals(frameCls) ||
          "com.pivotal.gemfirexd.tools.GfxdAgentLauncher"
              .equals(frameCls) ||
          "com.pivotal.gemfirexd.internal.engine.fabricservice.FabricAgentImpl"
              .equals(frameCls) ||
          "com.pivotal.gemfirexd.tools.GfxdSystemAdmin"
              .equals(frameCls) ||
          "com.pivotal.gemfirexd.internal.GemFireXDVersion"
              .equals(frameCls) ||
          "io.snappydata.gemxd.SnappyDataVersion$".equals(frameCls) ||
          "io.snappydata.gemxd.SnappyDataVersion".equals(frameCls) ||
          "org.apache.spark.sql.SnappySession$".equals(frameCls) ||
          "org.apache.spark.sql.SnappySession".equals(frameCls) ||
          "io.snappydata.gemxd.ClusterCallbacksImpl$".equals(frameCls)) {
        return true;
      }
    }
    return false;
  }

  // no external instances allowed
  private SystemProperties(Callbacks cb) {
    this.callbacks = cb;
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
    try {
      Class<?> factoryProvider = Class.forName(GFXD_FACTORY_PROVIDER);
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
  }

  public synchronized Callbacks getCallbacks() {
    return this.callbacks;
  }

  public String getString(final String name, final String defaultValue) {
    try {
      final Callbacks cb = this.callbacks;
      String value = cb.getSystemProperty(name, this);
      if (value != null) {
        return value;
      } else if (this == clientInstance) {
        // try with server instance
        return serverInstance.getString(name, defaultValue);
      } else {
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
    } else {
      return defaultValue;
    }
  }

  public boolean getBoolean(String name, boolean defaultValue) {
    String value = getString(name, null);
    if (value != null) {
      return Boolean.parseBoolean(value);
    } else {
      return defaultValue;
    }
  }

  public String getSystemPropertyNamePrefix() {
    return this.callbacks.getSystemPropertyNamePrefix();
  }
}
