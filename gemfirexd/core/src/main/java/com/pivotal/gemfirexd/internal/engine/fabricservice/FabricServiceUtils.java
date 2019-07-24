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

package com.pivotal.gemfirexd.internal.engine.fabricservice;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.distributed.internal.AbstractDistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.GemFireSparkConnectorCacheImpl;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.Property;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.services.jmx.GfxdAgentConfigImpl;
import com.pivotal.gemfirexd.internal.impl.services.monitor.FileMonitor;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

public final class FabricServiceUtils {

  public static FileMonitor initCachedMonitorLite(final Properties inProps,
      final HashMap<Object, Object> sysProps) {
    if (sysProps != null) {
      // noinspection SynchronizationOnLocalVariableOrMethodParameter
      synchronized (sysProps) {
        return initCachedMonitorLiteImpl(inProps, sysProps);
      }
    } else {
      return initCachedMonitorLiteImpl(inProps, sysProps);
    }
  }

  private static FileMonitor initCachedMonitorLiteImpl(Properties inProps,
      HashMap<Object, Object> sysProps) {
    // Cannot use privilege block of monitorlite as that itself initializes
    // application properties file.
    String gfxdPropFile = inProps.getProperty(Property.PROPERTIES_FILE);
    // SQLF:BC
    boolean isSQLF = false;
    if (gfxdPropFile == null || gfxdPropFile.length() == 0) {
      gfxdPropFile = inProps.getProperty(Property.SQLF_PROPERTIES_FILE);
      isSQLF = true;
    }
    if (gfxdPropFile != null && gfxdPropFile.length() > 0) {
      System.setProperty(isSQLF ? Property.SQLF_PROPERTIES_FILE
          : Property.PROPERTIES_FILE, gfxdPropFile);
      if (sysProps != null) {
        sysProps.put(isSQLF ? Property.SQLF_PROPERTIES_FILE
            : Property.PROPERTIES_FILE, gfxdPropFile);
      }
    }
    return (FileMonitor)Monitor.getCachedMonitorLite(false);
  }

  public static Properties preprocessProperties(final Properties inProps,
      final FileMonitor monitorlite, final HashMap<Object, Object> sysProps,
      final boolean noThrow) throws SQLException {
    if (sysProps != null) {
      // noinspection SynchronizationOnLocalVariableOrMethodParameter
      synchronized (sysProps) {
        return preprocessPropertiesImpl(inProps, monitorlite, sysProps, noThrow);
      }
    } else {
      return preprocessPropertiesImpl(inProps, monitorlite, null, noThrow);
    }
  }

  private static Properties preprocessPropertiesImpl(Properties inProps,
      FileMonitor monitorlite, HashMap<Object, Object> sysProps,
      final boolean noThrow) throws SQLException {

    if (monitorlite == null) {
      monitorlite = initCachedMonitorLite(inProps, null);
    }

    // here we don't know trace is on or off, so initializing first.
    String disabledFlags = inProps.getProperty(SanityManager.DEBUG_FALSE);
    if (disabledFlags == null) {
      disabledFlags = monitorlite.getJVMProperty(SanityManager.DEBUG_FALSE);
    }
    if (disabledFlags != null) {
      SanityManager.addDebugFlags(disabledFlags, false);
    }
    String flags = inProps.getProperty(SanityManager.DEBUG_TRUE);
    if (flags == null) {
      flags = monitorlite.getJVMProperty(SanityManager.DEBUG_TRUE);
    }
    if (flags != null) {
      SanityManager.addDebugFlags(flags, true);
    }

    Properties fileProps = monitorlite.getApplicationProperties();
    Properties outProps = null;
    Properties promoteToSystemProperties = new Properties();

    Properties inAndFileProps = inProps;
    if (fileProps != null) {
      inAndFileProps = new Properties(fileProps);
      Enumeration<?> propNames = inProps.propertyNames();
      while (propNames.hasMoreElements()) {
        final String propName = (String)propNames.nextElement();
        final String propValue = inProps.getProperty(propName);
        
        if (propValue != null) {
          inAndFileProps.setProperty(propName, propValue);
        }
        else {
          inAndFileProps.put(propName,  inProps.get(propName));
        }
        
      }
    }

    outProps = new Properties();
    
    String bootIndicator = inProps.getProperty(GfxdConstants.PROPERTY_BOOT_INDICATOR);
    if(GfxdConstants.BT_INDIC.FABRICAGENT.toString().equals(bootIndicator)) {
      outProps.putAll(GfxdAgentConfigImpl.gfxdFilterOutAgentProperties(outProps));
    }

    Enumeration<?> propNames = inAndFileProps.propertyNames();
    while (propNames.hasMoreElements()) {
      String propName = (String)propNames.nextElement();
      final String propValue = inAndFileProps.getProperty(propName);
      final Object propObj;
      if (propValue != null) {
        if (AuthenticationServiceBase.isSecurityProperty(propName, propValue,
            promoteToSystemProperties)) {
          continue;
        }
        // normalize user names
        if (propName.startsWith(com.pivotal.gemfirexd
            .internal.iapi.reference.Property.USER_PROPERTY_PREFIX)
            //SQLF:BC
       ||   propName.startsWith(com.pivotal.gemfirexd
                .internal.iapi.reference.Property.SQLF_USER_PROPERTY_PREFIX)
           ) {
          try {
            propName = IdUtil.getDBUserId(propName, true);
          } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
          }
        }
        outProps.setProperty(propName, propValue);
      }
      else if ((propObj = inAndFileProps.get(propName)) != null) {
        outProps.put(propName, propObj);
      }
    }

    // Set mcast-port to zero by default if locators have been specified.
    final boolean hasLocators = outProps
        .getProperty(DistributionConfig.LOCATORS_NAME) != null
        || outProps.getProperty(DistributionConfig.START_LOCATOR_NAME) != null;
    final boolean hasMcastPort = outProps
        .getProperty(DistributionConfig.MCAST_PORT_NAME) != null
        || outProps.getProperty(DistributionConfig.MCAST_ADDRESS_NAME) != null;
    if (hasLocators && !hasMcastPort) {
      outProps.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    }

    // set default timeSlice to a reasonable value rather than infinite,
    // otherwise DDMReader.fill() may get stuck consuming both CPU resources as
    // well as blocking a thread on the server side even when the client has
    // departed
    // [sumedh] commented out since this causes unpredictable exceptions in
    // client and server stacks (#45439)
    /*
    defineSystemPropertyIfAbsent(null,
        com.pivotal.gemfirexd.Property.DRDA_PROP_TIMESLICE, "120", monitorlite,
        sysProps);
    */

    // now check, if authentication is ON user attribute is present.
    // password attribute is optional & depends on authentication scheme.
    // user attribute is used as database owner before driver load.
    if (!promoteToSystemProperties.isEmpty()) {
      String auth = promoteToSystemProperties
          .getProperty(com.pivotal.gemfirexd.internal.iapi.reference.Property.REQUIRE_AUTHENTICATION_PARAMETER);
      if (Boolean.parseBoolean(auth)) {
        if (InternalDriver.activeDriver() != null
            && InternalDriver.activeDriver().isActive()) {
          // StandardException se = StandardException
          //     .newException(SQLState.JDBC_DRIVER_ALREADY_REGISTER);
          // throw PublicAPI.wrapStandardException(se);
        }
        String userName = outProps
            .getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
        userName = userName == null ? outProps
            .getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR)
            : userName;
        if (!noThrow && userName == null
            && !"NONE".equalsIgnoreCase(promoteToSystemProperties
                .getProperty(com.pivotal.gemfirexd.internal.iapi.reference.Property.GFXD_SERVER_AUTH_PROVIDER))) {
          StandardException se = StandardException
              .newException(SQLState.CONNECT_USERID_ISNULL);
          throw PublicAPI.wrapStandardException(se);
        }
      }
    }

    // determine we have anywhere log-file property set.
    String gfxdLogFile = outProps.getProperty(GfxdConstants.GFXD_LOG_FILE);
    if (gfxdLogFile == null || gfxdLogFile.length() == 0) {

      gfxdLogFile = outProps.getProperty(DistributionConfig.GEMFIRE_PREFIX
          + DistributionConfig.LOG_FILE_NAME);

      if (gfxdLogFile == null || gfxdLogFile.length() == 0) {

        gfxdLogFile = outProps.getProperty(DistributionConfig.LOG_FILE_NAME);
        if (gfxdLogFile == null || gfxdLogFile.length() == 0) {

          gfxdLogFile = "gemfirexd-interface.log";

        } // log-file undefined

      } // gemfire.log-file undefined

    } // gemfirexd.log-file undefined

    defineSystemPropertyIfAbsent(GfxdConstants.GFXD_PREFIX,
        DistributionConfig.LOG_FILE_NAME, gfxdLogFile, monitorlite, sysProps);

    // now lets promote security properties to system properties.
    defineSystemPropertyIfAbsent(promoteToSystemProperties, monitorlite,
        sysProps);

    return outProps;
  }

  private static final void defineSystemPropertyIfAbsent(Properties prop,
      FileMonitor monitorlite, HashMap<Object, Object> sysProps) {

    for (Map.Entry<Object, Object> p : prop.entrySet()) {
      defineSystemPropertyIfAbsent(null, (String)p.getKey(),
          (String)p.getValue(), monitorlite, sysProps);
    }

  }

  public static final void defineSystemPropertyIfAbsent(String keyPrefix,
      String key, String value, FileMonitor monitorlite,
      HashMap<Object, Object> sysProps) {
    if (monitorlite == null) {
      monitorlite = (FileMonitor)Monitor.getMonitorLite();
    }
    final String searchKey = (keyPrefix != null ? keyPrefix + key : key);

    if (monitorlite.getJVMProperty(searchKey) != null) {
      return;
    }
    else if (keyPrefix != null) {
      // check gemfire prefix property is there, if yes take that value as
      // gemfirexd props as well. particularly log-file works this way.
      String sysvalue = monitorlite
          .getJVMProperty(DistributionConfig.GEMFIRE_PREFIX + key);
      if (sysvalue != null) {
        value = sysvalue;
      }
    }

    String oldVal = monitorlite.setJVMProperty(searchKey, value);
    if (sysProps != null) {
      sysProps.put(key, oldVal);
    }
  }

  protected static void clearSystemProperties(final FileMonitor monitorlite,
      final HashMap<Object, Object> sysProps) {
    if (sysProps != null) {
      // noinspection SynchronizationOnLocalVariableOrMethodParameter
      synchronized (sysProps) {
        clearSystemPropertiesImpl(monitorlite, sysProps);
      }
    } else {
      clearSystemPropertiesImpl(monitorlite, sysProps);
    }
  }

  private static void clearSystemPropertiesImpl(FileMonitor monitorlite,
      HashMap<Object, Object> sysProps) {

    if (monitorlite != null) {
      for (Map.Entry<Object, Object> ent : sysProps.entrySet()) {
        String key = (String)ent.getKey();
        String val = (String)ent.getValue();

        final String msg;
        if (val == null) {
          msg = "clearing " + key;
          monitorlite.clearJVMProperty(key);
        }
        else {
          msg = "restoring " + key + " to " + val;
          monitorlite.setJVMProperty(key, val);
        }

        if (SanityManager.DEBUG) {
          if (GemFireXDUtils.TraceFabricServiceBoot) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT,
                msg);
          }
        }
      }
    }

    sysProps.clear();
  }

  @SuppressWarnings("unchecked")
  public final static Properties filterGemFireProperties(Properties inProps,
      String defaultLogFileName) {

    final String[] gfeProps = getGFEPropNames();

    THashMap outProps = new THashMap();

    // Filter out the gemfire properties from the given set of properties and
    // add to dsProps.
    // Use an enumeration on the property names (instead of iterator on
    // entrySet) to ensure that all properties (including defaults) are
    // obtained.
    final Enumeration<?> propNames = inProps.propertyNames();
    while (propNames.hasMoreElements()) {
      final String propName = (String)propNames.nextElement();
      final String propValue = inProps.getProperty(propName);
      if (propName.startsWith(com.pivotal.gemfirexd.internal.iapi.reference.Property.HADOOP_GFXD_LONER)) {
        continue;
      }
      else if (propName.startsWith(DistributionConfig.GEMFIRE_PREFIX)) {
        outProps.put(
            propName.substring(DistributionConfig.GEMFIRE_PREFIX.length()),
            propValue);
      }
      else if (propName
          .startsWith(DistributionConfig.USERDEFINED_PREFIX_NAME) ) {
        outProps.put(
            DistributionConfig.GFXD_USERDEFINED_PREFIX_NAME
                + propName.substring(DistributionConfig.USERDEFINED_PREFIX_NAME
                    .length()), propValue);
      } else if ( propName.startsWith("gemfirexd.custom-")) {
        outProps.put(
            DistributionConfig.GFXD_USERDEFINED_PREFIX_NAME
                + propName.substring("gemfirexd.custom-".length()), propValue);
      }
      else if (propValue != null
          && Arrays.binarySearch(gfeProps, propName) >= 0) {
        outProps.put(propName, propValue);
      }
      else if (!propName.startsWith(GfxdConstants.GFXD_PREFIX)
          //SQLF:BC
          && !propName.startsWith(Attribute.SQLF_PREFIX)
          && !propName.startsWith(DistributionConfig.USERDEFINED_PREFIX_NAME) 
          && !propName.startsWith("derbyTesting.")
          && !propName.startsWith(GfxdConstants.SNAPPY_PREFIX)
          && !propName.startsWith("metastore-")
          && !propName.startsWith("spark.")
          && !propName.startsWith("jobserver.")
          && !propName.startsWith("zeppelin.")
          && !propName.startsWith("hive.")
          && !propName.startsWith("hadoop.")
          && !propName.startsWith("javax.jdo.")
          && !propName.startsWith(GemFireSparkConnectorCacheImpl.connectorPrefix)
          && !GfxdConstants.validExtraGFXDProperties.contains(propName)
          && !"BootPassword".equalsIgnoreCase(propName)
          && !"encryptionAlgorithm".equalsIgnoreCase(propName)
          && !"dataEncryption".equalsIgnoreCase(propName)
          && !"encryptionKey".equalsIgnoreCase(propName)
          && !"newBootPassword".equalsIgnoreCase(propName)
          && !"encryptionProvider".equalsIgnoreCase(propName)
          && !"encryptionKeyLength".equalsIgnoreCase(propName)
          ) {
        throw new IllegalArgumentException(
            "Unknown configuration attribute name '" + propName
                + "'. Valid names are: "
                + GfxdConstants.validExtraGFXDProperties
                + ". Valid cluster attributes: " + Arrays.toString(gfeProps));
      }
    }

    // default overrides w.r.t. gemfire property values
    if (!outProps.contains(DistributionConfig.LOG_FILE_NAME)) {
      outProps.put(DistributionConfig.LOG_FILE_NAME, defaultLogFileName);
    }
    Properties props = new Properties();
    props.putAll(outProps);
    return props;
  }

  public static InetAddress getListenAddress(String bindAddress,
      DistributionConfig config) throws UnknownHostException {
    final InetAddress listenAddress;
    if (bindAddress == null) {
      try {
        if (config == null) {
          InternalDistributedSystem dsys = Misc.getGemFireCache()
              .getDistributedSystem();
          config = dsys.getConfig();
        }
        bindAddress = config.getBindAddress();
      } catch (CacheClosedException cce) {
        // ignore
      }
    }
    if (bindAddress != null && bindAddress.length() > 0) {
      listenAddress = InetAddress.getByName(bindAddress);
    }
    else {
      // listenAddress = InetAddress.getLocalHost();
      // quick fix for bug #41107
      //listenAddress = InetAddress.getByAddress(InetAddress.getLocalHost()
      //    .getHostName(), new byte[] { 0, 0, 0, 0 });
      // use the loopback address by default
      listenAddress = InetAddress.getByName(null);
    }
    return listenAddress;
  }

  /**
   * Get all the known GFE property names.
   */
  public static String[] getGFEPropNames() {
    return AbstractDistributionConfig._getAttNames();
  }
}
