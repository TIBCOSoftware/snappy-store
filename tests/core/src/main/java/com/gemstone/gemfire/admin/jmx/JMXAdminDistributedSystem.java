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
package com.gemstone.gemfire.admin.jmx;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.management.Attribute;
import javax.management.InstanceNotFoundException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;

import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.admin.AdminDistributedSystem;
import com.gemstone.gemfire.admin.AdminException;
import com.gemstone.gemfire.admin.Alert;
import com.gemstone.gemfire.admin.AlertLevel;
import com.gemstone.gemfire.admin.AlertListener;
import com.gemstone.gemfire.admin.BackupStatus;
import com.gemstone.gemfire.admin.CacheServer;
import com.gemstone.gemfire.admin.CacheServerConfig;
import com.gemstone.gemfire.admin.CacheVm;
import com.gemstone.gemfire.admin.CacheVmConfig;
import com.gemstone.gemfire.admin.DistributedSystemConfig;
import com.gemstone.gemfire.admin.DistributionLocator;
import com.gemstone.gemfire.admin.DistributionLocatorConfig;
import com.gemstone.gemfire.admin.GemFireHealth;
import com.gemstone.gemfire.admin.SystemMember;
import com.gemstone.gemfire.admin.SystemMemberCacheListener;
import com.gemstone.gemfire.admin.SystemMembershipEvent;
import com.gemstone.gemfire.admin.SystemMembershipListener;
import com.gemstone.gemfire.admin.jmx.internal.AdminDistributedSystemJmxImpl;
import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.Assert;

import dunit.DistributedTestCase;
import dunit.DistributedTestCase.WaitCriterion;

/**
 * An implementation of <code>AdminDistributedSystem</code> that
 * communicates via JMX.
 *
 * @author David Whitlock
 * @author Kirk Lund
 */
public class JMXAdminDistributedSystem extends JMXAdminImpl
  implements AdminDistributedSystem {

  /** The <code>GemFireHealth</code> for this
   * <code>DistributedSystem</code> */ 
  private JMXGemFireHealth health = null;

  /** The name of the JMX agent MBean */
  private ObjectName agentName;

  /** The name of the distributed system JMX MBean */
  private ObjectName dsObjectName;
  
  private JMXConnector connector;

  ////////////////////  Constructors  ////////////////////

  /**
   * Creates a new <code>JXMDistributedSystem</code> with the given
   * configuration. 
   *
   *
   */
  public JMXAdminDistributedSystem(final String mcastAddress,
                              final int mcastPort,
                              final String locators,
                              final String bindAddress,
                              final String remoteCommand,
                              MBeanServerConnection mbs,
                              JMXConnector conn,
                              ObjectName agentName) {

    this(new DistributedSystemConfig() {
        public String getMcastAddress() {
          return mcastAddress;
        }
        public void setMcastAddress(String mcastAddress) {
          throw new UnsupportedOperationException();
        }
        public int getMcastPort() {
          return mcastPort;
        }
        public void setMcastPort(int mcastPort) {
          throw new UnsupportedOperationException();
        }
        public void setEnableNetworkPartitionDetection(boolean newValue) {
          throw new UnsupportedOperationException();
        }
        public boolean getEnableNetworkPartitionDetection() {
          throw new UnsupportedOperationException();
        }
        public int getMemberTimeout() {
          throw new UnsupportedOperationException();
        }
        public void setMemberTimeout(int value) {
          throw new UnsupportedOperationException();
        }
        public boolean getDisableAutoReconnect() {
          throw new UnsupportedOperationException();
        }
        public void setDisableAutoReconnect(boolean value) {
          throw new UnsupportedOperationException();
        }
//        public int getMcastTtl() {
//          throw new UnsupportedOperationException();
//        }
//        public void setMcastTtl(int v) {
//          throw new UnsupportedOperationException();
//        }
//        public int getSocketLeaseTime() {
//          throw new UnsupportedOperationException();
//        }
//        public void setSocketLeaseTime(int v) {
//          throw new UnsupportedOperationException();
//        }
//        public int getSocketBufferSize() {
//          throw new UnsupportedOperationException();
//        }
//        public void setSocketBufferSize(int v) {
//          throw new UnsupportedOperationException();
//        }
//        public boolean getConserveSockets() {
//          throw new UnsupportedOperationException();
//        }
//        public void setConserveSockets(boolean v) {
//          throw new UnsupportedOperationException();
//        }
        public String getLocators() {
          return locators;
        }
        public void setBindAddress(String bindAddress) {
          throw new UnsupportedOperationException();
        }
        public String getBindAddress() {
          return bindAddress;
        }
        public void setServerBindAddress(String bindAddress) {
          throw new UnsupportedOperationException();
        }
        public String getServerBindAddress() {
          throw new UnsupportedOperationException();
        }
        public void setLocators(String locators) {
          throw new UnsupportedOperationException();
        }
        public String getRemoteCommand() {
          return remoteCommand;
        }
        public void setRemoteCommand(String cmd) {
          throw new UnsupportedOperationException();
        }
        public CacheServerConfig[] getCacheServerConfigs() {
          throw new UnsupportedOperationException();
        }
        public CacheServerConfig createCacheServerConfig() {
          throw new UnsupportedOperationException();
        }
        public void removeCacheServerConfig(CacheServerConfig
                                               managerConfig) {
          throw new UnsupportedOperationException();
        }
        public CacheVmConfig[] getCacheVmConfigs() {
          throw new UnsupportedOperationException();
        }
        public CacheVmConfig createCacheVmConfig() {
          throw new UnsupportedOperationException();
        }
        public void removeCacheVmConfig(CacheVmConfig
                                               managerConfig) {
          throw new UnsupportedOperationException();
        }
        public DistributionLocatorConfig[] getDistributionLocatorConfigs() {
          throw new UnsupportedOperationException();
        }
        public DistributionLocatorConfig createDistributionLocatorConfig() {
          throw new UnsupportedOperationException();
        }
        public void removeDistributionLocatorConfig(DistributionLocatorConfig
                                               managerConfig) {
          throw new UnsupportedOperationException();
        }
        public void addListener(ConfigListener listener) {
          throw new UnsupportedOperationException();
        }
        public void removeListener(ConfigListener listener) {
          throw new UnsupportedOperationException();
        }
        public boolean isSSLEnabled() {
          return DistributionConfig.DEFAULT_SSL_ENABLED;
        }
        public void setSSLEnabled(boolean enabled) {
          throw new UnsupportedOperationException();
        }
        public String getSSLProtocols() {
          return DistributionConfig.DEFAULT_SSL_PROTOCOLS;
        }
        public void setSSLProtocols(String protocols) {
          throw new UnsupportedOperationException();
        }
        public String getSSLCiphers() {
          return DistributionConfig.DEFAULT_SSL_CIPHERS;
        }
        public void setSSLCiphers(String ciphers) {
          throw new UnsupportedOperationException();
        }
        public boolean isSSLAuthenticationRequired() {
          return DistributionConfig.DEFAULT_SSL_REQUIRE_AUTHENTICATION;
        }
        public void setSSLAuthenticationRequired(boolean authRequired) {
          throw new UnsupportedOperationException();
        }
        public Properties getSSLProperties() {
          return new Properties();
        }
        public void setSSLProperties(Properties sslProperties) {
          throw new UnsupportedOperationException();
        }
//        public DistributionLocator[] getDistributionLocators() {
//          throw new UnsupportedOperationException();
//        }
//        public void setDistributionLocators(DistributionLocator[] locs) {
//          throw new UnsupportedOperationException();
//        }
        public String getEntityConfigXMLFile() {
          throw new UnsupportedOperationException();
        }
        public void setEntityConfigXMLFile(String xmlFile) {
          throw new UnsupportedOperationException();
        }
        public String getSystemId() {
          throw new UnsupportedOperationException();
        }
        public void setSystemId(String systemId) {
          throw new UnsupportedOperationException();
        }
        public String getSystemName() {
          throw new UnsupportedOperationException();
        }
        public void setSystemName(String systemName) {
          throw new UnsupportedOperationException();
        }
        public void validate() {

        }
        public void addSSLProperty(String key, String value) {
          throw new UnsupportedOperationException();
        }
        public void removeSSLProperty(String key) {
          throw new UnsupportedOperationException();
        }
        public String getLogFile() {
          throw new UnsupportedOperationException();
        }
        public void setLogFile(String logFile) {
          throw new UnsupportedOperationException();
        }
        public String getLogLevel() {
          throw new UnsupportedOperationException();
        }
        public void setLogLevel(String logLevel) {
          throw new UnsupportedOperationException();
        }
        public int getLogDiskSpaceLimit() {
          throw new UnsupportedOperationException();
        }
        public void setLogDiskSpaceLimit(int limit) {
          throw new UnsupportedOperationException();
        }
        public int getLogFileSizeLimit() {
          throw new UnsupportedOperationException();
        }
        public void setLogFileSizeLimit(int limit) {
          throw new UnsupportedOperationException();
        }
        public boolean getDisableTcp() {
          throw new UnsupportedOperationException();
        }
        public void setDisableTcp(boolean flag) {
          throw new UnsupportedOperationException();
        }
        public int getAckWaitThreshold() {
          throw new UnsupportedOperationException();
        }
        
        public void setAckWaitThreshold(int seconds) {
          throw new UnsupportedOperationException();
        }

        public int getAckSevereAlertThreshold() {
          throw new UnsupportedOperationException();
        }
        
        public void setAckSevereAlertThreshold(int seconds) {
          throw new UnsupportedOperationException();
        }

        public int getRefreshInterval() {
          throw new UnsupportedOperationException();
        }

        public void setRefreshInterval(int seconds) {
          throw new UnsupportedOperationException();
        }
        
        public String getMembershipPortRange() {
          throw new UnsupportedOperationException();
        }
        public void setMembershipPortRange(String membershipPortRange) {
          throw new UnsupportedOperationException();
        }

        public Object clone() {
          throw new UnsupportedOperationException();
        }
        public int getTcpPort() {
          throw new UnsupportedOperationException();
        }
        public void setTcpPort(int port) {
          throw new UnsupportedOperationException();
        }
      }, mbs, conn, agentName);
    Assert.assertTrue(agentName != null);
  }

  /**
   * Creates a new <code>JMXDistributedSystem</code> with the given
   * <Code>DistributedSystemConfig</code>.   
   *
   * @param config
   *        The configuration for connecting to the distributed system
   * @param mbs
   *        The connection JMX MBean server through which operations
   *        are invoked, etc.
   * @param agentName
   *        The name of the JMX agent MBean
   */
  public JMXAdminDistributedSystem(DistributedSystemConfig config,
                              MBeanServerConnection mbs,
                              JMXConnector conn,
                              final ObjectName agentName) {
    super(mbs, null);
    try {
      this.connector = conn;
      this.agentName = agentName;
      this.mbs.setAttribute(agentName, new Attribute("mcastPort", new Integer(config.getMcastPort())));
      this.mbs.setAttribute(agentName, new Attribute("mcastAddress", config.getMcastAddress()));
      this.mbs.setAttribute(agentName, new Attribute("locators", config.getLocators()));
      this.mbs.setAttribute(agentName, new Attribute("bindAddress", config.getBindAddress()));
      
      this.objectName = (ObjectName)
        this.mbs.invoke(agentName, "connectToSystem", new Object[0],
                        new String[0]);
      Assert.assertTrue(this.objectName != null);

      final MBeanServerConnection mbsc = mbs;
      WaitCriterion ev = new WaitCriterion() {
        public boolean done() {
          try {
            return ((Boolean)mbsc.getAttribute(agentName, "connected")).booleanValue();
          }
          catch (Exception e) {
            throw new InternalGemFireException("mbean error", e);
          }
        }
        public String description() {
          return "agent never connected: " + agentName;
        }
      };
      DistributedTestCase.waitForCriterion(ev, 20 * 1000, 200, true);

    } catch (Exception ex) {
      String s = "While creating a JMXDistributedSystem";
      throw new InternalGemFireException(s, ex);
    }
  }

  ////////////////////  Instance Methods  ////////////////////

  public boolean isMcastDiscovery() {
    throw new UnsupportedOperationException("not yet implemented");
  }
  
  public boolean isMcastEnabled() {
    throw new UnsupportedOperationException("not yet implemented");
  }

  public String getId() {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      return (String) this.mbs.getAttribute(this.objectName, "id");

    } catch (Exception ex) {
      String s = "While getting the id";
      throw new InternalGemFireException(s, ex);
    }
  }
  
  public LogWriter getLogWriter() {
    throw new UnsupportedOperationException("not yet implemented");
  }
  
  public String getName() {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      return (String) this.mbs.getAttribute(this.objectName, "systemName");
    } catch (Exception ex) {
      String s = "While getting the name";
      throw new InternalGemFireException(s, ex);
    }
  }
  
  public DistributionLocator[] getDistributionLocators() {
    checkForRmiConnection();
    try {
      ObjectName[] names = (ObjectName[]) 
          this.mbs.invoke(this.objectName, "manageDistributionLocators",
                          new Object[0], new String[0]);
                          
      DistributionLocator[] locators = new DistributionLocator[names.length]; 
      for (int i = 0; i < names.length; i++) {
        locators[i] = new JMXDistributionLocator(this.mbs, names[i]);
      }
      return locators;
      
    } catch (Exception ex) {
      String s = "While calling manageDistributionLocators";
      throw new InternalGemFireException(s, ex);
    }
  }
  
  public String getRemoteCommand() {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      return (String) this.mbs.getAttribute(this.objectName, "remoteCommand");

    } catch (Exception ex) {
      String s = "While getting the remote command";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void setRemoteCommand(String remoteCommand) {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      this.mbs.setAttribute(this.objectName,
                            new Attribute("remoteCommand", remoteCommand));

    } catch (Exception ex) {
      String s = "While setting the remote command";
      throw new InternalGemFireException(s, ex);
    }
  }

  public String getAlertLevelAsString() {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      return (String) this.mbs.getAttribute(this.objectName, "alertLevel");

    } catch (Exception ex) {
      String s = "While getting the alert level";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void setAlertLevelAsString(String level) {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      this.mbs.setAttribute(this.objectName,
                            new Attribute("alertLevel", level));

    } catch (Exception ex) {
      String s = "While setting the alert level";
      throw new InternalGemFireException(s, ex);
    }
  }

  public AlertLevel getAlertLevel() {
    return AlertLevel.forName(getAlertLevelAsString());
  }

  public void setAlertLevel(AlertLevel level) {
    setAlertLevelAsString(level.getName());
  }

  public String getMcastAddress() {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      return (String) this.mbs.getAttribute(this.objectName, "mcastAddress");

    } catch (Exception ex) {
      String s = "While getting the mcastAddress";
      throw new InternalGemFireException(s, ex);
    }
  }

  public int getMcastPort() {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      Integer value =
        (Integer) this.mbs.getAttribute(this.objectName, "mcastPort");
      return value.intValue();

    } catch (Exception ex) {
      String s = "While getting the mcastPort";
      throw new InternalGemFireException(s, ex);
    }
  }

  public String getLocators() {
    checkForRmiConnection();
    try {
      Assert.assertTrue(this.objectName != null);
      return (String) this.mbs.getAttribute(this.objectName, "locators");

    } catch (Exception ex) {
      String s = "While getting the locators";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void start() {
    checkForRmiConnection();
    try {
      this.mbs.invoke(this.objectName, "start", new Object[0],
                      new String[0]);

    } catch (Exception ex) {
      String s = "While starting";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void stop() {
    checkForRmiConnection();
    try {
      this.mbs.invoke(this.objectName, "stop", new Object[0],
                      new String[0]);

    } catch (Exception ex) {
      String s = "While stopping";
      throw new InternalGemFireException(s, ex);
    }
  }

  public String displayMergedLogs() {
    checkForRmiConnection();
    try {
      return (String) this.mbs.invoke(this.objectName, "displayMergedLogs",
                                      new Object[0], new String[0]);

    } catch (Exception ex) {
      String s = "While displaying the merged log files";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void addAlertListener(AlertListener listener) {
    checkForRmiConnection();
    JMXAlertListener listener2 = new JMXAlertListener(listener);
    try {
      Assert.assertTrue(this.objectName != null);
      this.mbs.addNotificationListener(this.objectName, listener2,
                                       null /* filter */,
                                       null /* handBack */);

    } catch (Exception ex) {
      String s = "While adding alert listener";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void removeAlertListener(AlertListener listener) {
    checkForRmiConnection();
    JMXAlertListener listener2 =
      JMXAlertListener.removeListener(listener);
    if (listener2 == null) {
      String s = "AlertListener " + listener + " was not registered";
      throw new IllegalArgumentException(s);
    }

    try {
      this.mbs.removeNotificationListener(this.objectName, listener2);

    } catch (Exception ex) {
      String s = "While removing alert listener";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void addMembershipListener(SystemMembershipListener listener) {
    checkForRmiConnection();
    JMXSystemMembershipListener listener2 = new JMXSystemMembershipListener(listener);
    try {
      Assert.assertTrue(this.objectName != null);
      this.mbs.addNotificationListener(this.objectName, listener2,
                                       null /* filter */,
                                       null /* handBack */);

    } catch (Exception ex) {
      String s = "While adding membership listener";
      throw new InternalGemFireException(s, ex);
    }
  }

  public void removeMembershipListener(SystemMembershipListener listener) {
    checkForRmiConnection();
    JMXSystemMembershipListener listener2 =
      JMXSystemMembershipListener.removeListener(listener);
    if (listener2 == null) {
      String s = "SystemMembershipListener " + listener + " was not registered";
      throw new IllegalArgumentException(s);
    }

    try {
      this.mbs.removeNotificationListener(this.objectName, listener2);

    } catch (Exception ex) {
      String s = "While removing membership listener";
      throw new InternalGemFireException(s, ex);
    }
  }

   public void addCacheListener(SystemMemberCacheListener listener){}

   public void removeCacheListener(SystemMemberCacheListener listener){}


  public SystemMember[] getSystemMemberApplications() throws AdminException {
    checkForRmiConnection();
    try {
      ObjectName[] names = (ObjectName[]) 
          this.mbs.invoke(this.objectName, "manageSystemMemberApplications",
                          new Object[0], new String[0]);
                          
      SystemMember[] members = new SystemMember[names.length]; 
      for (int i = 0; i < names.length; i++) {
        Assert.assertTrue(names[i] != null);
        members[i] = new JMXSystemMember(this.mbs, names[i]);
      }
      return members;
      
    } catch (Exception ex) {
      String s = "While calling manageSystemMemberApplications";
      throw new InternalGemFireException(s, ex);
    }
  }
  
  
  public String getLatestAlert() {
    checkForRmiConnection();
    try {
      return (String) this.mbs.invoke(this.objectName, "getLatestAlert",
                                      new Object[0], new String[0]);

    } catch (Exception ex) {
      String s = "While displaying the latest alert";
      throw new InternalGemFireException(s, ex);
    }
  }
  
  public GemFireHealth getGemFireHealth() {
    checkForRmiConnection();
    monitorGemFireHealth();
    return this.health;
  }
  public ObjectName monitorGemFireHealth() {
    checkForRmiConnection();
    synchronized (this) {
      ObjectName healthName = null;
      try {
        if (this.health == null) {
            healthName = (ObjectName) this.mbs.invoke(this.objectName,
                                              "monitorGemFireHealth",
                                              new Object[0],
                                              new String[0]);
            this.health = new JMXGemFireHealth(this.mbs, healthName);
  
        } else {
          healthName = this.health.objectName;
        }
      } catch (Exception ex) {
        String s = "While getting the GemFireHealth";
        throw new InternalGemFireException(s, ex);
      }
      return healthName;
    }
  }

  public DistributedSystemConfig getConfig() {
    checkForRmiConnection();
    Assert.assertTrue(this.objectName != null);
    return new JMXDistributedSystemConfig(this.mbs, this.objectName,
                                          this.agentName);
  }

  public boolean isConnected() {
    checkForRmiConnection();
    try {
      Object ret = this.mbs.getAttribute(this.agentName, "connected");
      return ((Boolean) ret).booleanValue();
    } 
    catch (Exception ex) {
      String s = "Checking isConnected";
      throw new InternalGemFireException(s, ex);
    }
  }
  
  public boolean isRunning() {
    return this.isConnected();
  }
  
  public void connect() {
    checkForRmiConnection();
    try {
      this.mbs.invoke(this.agentName, "connectToSystem",
                      new Object[0], new String[0]);

    } catch (Exception ex) {
      String s = "While connecting";
      throw new InternalGemFireException(s, ex);
    }
  }
    
  public void disconnect() {
    checkForRmiConnection();
    try {
      this.mbs.invoke(this.agentName, "disconnectFromSystem",
                      new Object[0], new String[0]);
    } catch (Exception ex) {
      String s = "While disconnecting";
      throw new InternalGemFireException(s, ex);
    }
  }

  public boolean waitToBeConnected(long timeout) {
    checkForRmiConnection();
    try {
      Boolean b = (Boolean)
        this.mbs.invoke(this.objectName, "waitToBeConnected",
                        new Object[] { new Long(timeout) },
                        new String[] { long.class.getName() });
      return b.booleanValue();

    } catch (Exception ex) {
      String s = "While waiting to be connected";
      throw new InternalGemFireException(s, ex);
    }
  }

  public DistributionLocator addDistributionLocator() {
    checkForRmiConnection();
    try {
      ObjectName name = (ObjectName)
        this.mbs.invoke(this.objectName, "manageDistributionLocator",
                        new Object[0], new String[0]);
      return new JMXDistributionLocator(this.mbs, name);

    } catch (Exception ex) {
      String s = "While adding a locator";
      throw new InternalGemFireException(s, ex);
    }
  }

  public CacheVm addCacheVm() throws AdminException {
    checkForRmiConnection();
    try {
      ObjectName name = (ObjectName)
        this.mbs.invoke(this.objectName, "manageCacheVm",
                        new Object[0], new String[0]);
      return new JMXCacheServer(this.mbs, name);

    } catch (Exception ex) {
      String s = "While adding a CacheServer";
      throw new InternalGemFireException(s, ex);
    }
  }
  public CacheServer addCacheServer() throws AdminException {
    checkForRmiConnection();
    try {
      ObjectName name = (ObjectName)
        this.mbs.invoke(this.objectName, "manageCacheServer",
                        new Object[0], new String[0]);
      return new JMXCacheServer(this.mbs, name);

    } catch (Exception ex) {
      String s = "While adding a CacheServer";
      throw new InternalGemFireException(s, ex);
    }
  }

  public CacheVm[] getCacheVms() throws AdminException {
    checkForRmiConnection();
    try {
      ObjectName[] names = (ObjectName[]) 
          this.mbs.invoke(this.objectName, "manageCacheVms",
                          new Object[0], new String[0]);
                          
      CacheVm[] servers = new CacheVm[names.length]; 
      for (int i = 0; i < names.length; i++) {
        servers[i] = new JMXCacheServer(this.mbs, names[i]);
      }
      return servers;
      
    } catch (Exception ex) {
      String s = "While calling manageCacheVms";
      throw new InternalGemFireException(s, ex);
    }
  }
  public CacheServer[] getCacheServers() throws AdminException {
    checkForRmiConnection();
    try {
      ObjectName[] names = (ObjectName[]) 
          this.mbs.invoke(this.objectName, "manageCacheServers",
                          new Object[0], new String[0]);
                          
      CacheServer[] servers = new CacheServer[names.length]; 
      for (int i = 0; i < names.length; i++) {
        servers[i] = new JMXCacheServer(this.mbs, names[i]);
      }
      return servers;
      
    } catch (Exception ex) {
      String s = "While calling manageCacheServers";
      throw new InternalGemFireException(s, ex);
    }
  }

  /**
   * Not implemented yet.
   * 
   * @since 5.6
   */
  public CacheServer[] getCacheServers(String durableClientId)
      throws AdminException
  {
    throw new UnsupportedOperationException("not yet implemented");
  }
  
  public String toString() {
    return this.getName();
  }
  
  public SystemMember lookupSystemMember(DistributedMember distributedMember) 
  throws AdminException {
    checkForRmiConnection();
    try {
      ObjectName name = (ObjectName) 
          this.mbs.invoke(this.objectName, "manageSystemMember",
                          new Object[] { distributedMember }, 
                          new String[] { DistributedMember.class.getName() });
      if (name == null) {
        return null;
      }
      return new JMXSystemMember(this.mbs, name);
      
    } catch (Exception ex) {
      String s = "While calling manageSystemMemberApplications";
      throw new InternalGemFireException(s, ex);
    }
  }
  
  public void closeRmiConnection() {
    if((connector != null)) {
      try {
        hydra.Log.getLogWriter().info("Closing the RMI connector...");
        connector.close();
      }
      catch (Exception e) {
        hydra.Log.getLogWriter().warning(e);
      } finally {
        connector = null;
        mbs = null;
      }
    }
  }
  
  protected void checkForRmiConnection() {
   if(null == this.mbs) {
    throw new IllegalStateException("MBeanServerConnection is already closed."); 
   }
  }
  
  void addJMXNotificationListener(NotificationListener listener) throws InstanceNotFoundException, IOException {
	checkForRmiConnection();	
	this.mbs.addNotificationListener(this.objectName, listener, null, null);	  
  }
  
  void removeJMXNotificationListener(NotificationListener listener) throws InstanceNotFoundException, IOException {
	checkForRmiConnection();	
	try {
	  this.mbs.removeNotificationListener(this.objectName, listener);
	} catch (ListenerNotFoundException e) {
	  hydra.Log.getLogWriter().warning(e.getMessage());
	}	  
  }

  ////////////////////////  Inner Classes  ////////////////////////

  /**
   * A JMX <code>NotificationListener</code> that translates its
   * notifications into {@link AlertEvent}s.
   */
  static class JMXAlertListener implements NotificationListener {

    /** Maps AlertListeners to their JMXAlertListeners */
    private static Map listeners = new IdentityHashMap();

    /////////////////////  Instance Fields  /////////////////////

    /** The alert listener to which the JMX notifications are sent */
    private AlertListener listener;

    //////////////////////  Static Methods  /////////////////////

    /**
     * Removes the <code>JMXAlertListener</code> for the given
     * <Code>AlertListener</code> from the list of listeners.
     *
     * @return The JMXAlertListener that corresponds to listener
     */
    public static JMXAlertListener removeListener(AlertListener listener) {
      return (JMXAlertListener) listeners.remove(listener);
    }

    ///////////////////////  Constructors  //////////////////////

    /**
     * Creates a new <code>JMXAlertListener</code> for the given
     * <code>AlertListener</code>.
     */
    JMXAlertListener(AlertListener listener) {
      this.listener = listener;
      JMXAlertListener.listeners.put(listener, this);
    }

    //////////////////////  Instance Methods  //////////////////////

    /**
     * Translate the given JMX <code>Notification</code> into an
     * <code>Alert</code> that is delivered to the
     * <code>AlertListener</code>.
     */
    public void handleNotification(Notification notification,
                                   Object handback) {
      String type = notification.getType();
      if (!AdminDistributedSystemJmxImpl.NOTIF_ALERT.equals(type)) {
        return;
      }

      String message = notification.getMessage();
      final com.gemstone.gemfire.internal.admin.Alert alert0 =
        com.gemstone.gemfire.internal.admin.remote.RemoteAlert.fromString(message);
      Alert alert = new Alert() {
          public AlertLevel getLevel() {
            return AlertLevel.forSeverity(alert0.getLevel());
          }

          public SystemMember getSystemMember() {
            String s = "Not implemented yet";
            throw new UnsupportedOperationException(s);
          }

          public String getConnectionName() {
            return alert0.getConnectionName();
          }

          public String getSourceId() {
            return alert0.getSourceId();
          }

          public String getMessage() {
            return alert0.getMessage();
          }

          public Date getDate() {
            return alert0.getDate();
          }

	      public String toString() {
		  return "Test JMXAdminDistributedSystem Alert date: " + getDate() + " message: \"" + getMessage()
		      + "\" source id: \"" + getSourceId() + "\" connection name: " + getConnectionName()  
		      + " alert level: " + getLevel();
	      }


        };
      this.listener.alert(alert);
    }
  }
  
  /**
   * A JMX <code>NotificationListener</code> that translates its
   * notifications into {@link MembershipEvent}s.
   */
  static class JMXSystemMembershipListener implements NotificationListener {

    /** Maps SystemMembershipListeners to their JMXSystemMembershipListeners */
    private static Map listeners = new IdentityHashMap();

    /////////////////////  Instance Fields  /////////////////////

    /** The membership listener to which the JMX notifications are sent */
    private SystemMembershipListener listener;

    //////////////////////  Static Methods  /////////////////////

    /**
     * Removes the <code>JMXSystemMembershipListener</code> for the given
     * <Code>SystemMembershipListener</code> from the list of listeners.
     *
     * @return The JMXSystemMembershipListener that corresponds to listener
     */
    public static JMXSystemMembershipListener removeListener(SystemMembershipListener listener) {
      return (JMXSystemMembershipListener) listeners.remove(listener);
    }

    ///////////////////////  Constructors  //////////////////////

    /**
     * Creates a new <code>JMXSystemMembershipListener</code> for the given
     * <code>SystemMembershipListener</code>.
     */
    JMXSystemMembershipListener(SystemMembershipListener listener) {
      this.listener = listener;
      JMXSystemMembershipListener.listeners.put(listener, this);
    }

    //////////////////////  Instance Methods  //////////////////////

    /**
     * Translate the given JMX <code>Notification</code> into an
     * <code>Membership</code> that is delivered to the
     * <code>SystemMembershipListener</code>.
     */
    public void handleNotification(Notification notification,
                                   Object handback) {

      final String memberId = notification.getMessage();
      SystemMembershipEvent event = new SystemMembershipEvent() {
          public String getMemberId() {
            return memberId;
          }
          public DistributedMember getDistributedMember() {
            return null;
          }
        };

      String type = notification.getType();
      if (AdminDistributedSystemJmxImpl.NOTIF_MEMBER_JOINED.equals(type)) {
        this.listener.memberJoined(event);

      } else if (AdminDistributedSystemJmxImpl.NOTIF_MEMBER_LEFT.equals(type)) {
        this.listener.memberLeft(event);

      } else if (AdminDistributedSystemJmxImpl.NOTIF_MEMBER_CRASHED.equals(type)) {
        this.listener.memberCrashed(event);
      }
    }
  }

  public Set<PersistentID> getMissingPersistentMembers() throws AdminException {
    return null;
  }

  public void revokePersistentMember(InetAddress host, String directory)
      throws AdminException {
  }
  
  public void revokePersistentMember(UUID diskStoreID) throws AdminException {
  }

  @Override
  public void unblockPersistentMember(UUID diskStoreID) throws AdminException {

  }

  public Set shutDownAllMembers() throws AdminException {
    return null;
  }
  
  public Set<DistributedMember> shutDownAllMembers(long timeout)
      throws AdminException {
    // TODO Auto-generated method stub
    return null;
  }

  public BackupStatus backupAllMembers(File targetDir) throws AdminException {
    return null;
  }

  public BackupStatus backupAllMembers(File targetDir,File baselineDir) throws AdminException {
    return null;
  }

  public Map<DistributedMember, Set<PersistentID>> compactAllDiskStores()
      throws AdminException {
    // TODO Auto-generated method stub
    return null;
  }
}

