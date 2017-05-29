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
package com.gemstone.gemfire.admin;

import hydra.GemFireDescription;
import hydra.GemFirePrms;
import hydra.HydraConfigException;
import hydra.TestConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import javax.net.ssl.SSLHandshakeException;

import security.CredentialGenerator;

import com.gemstone.gemfire.admin.jmx.JMXHelper;
import com.gemstone.gemfire.admin.jmx.internal.AgentConfigImpl;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfigImpl;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.FlowControlParams;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.Config;
import com.gemstone.gemfire.internal.ConfigSource;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.admin.remote.DistributionLocatorId;
import com.gemstone.gemfire.security.AuthenticationFailedException;
import com.gemstone.gemfire.security.AuthenticationRequiredException;
import com.gemstone.gemfire.security.GemFireSecurityException;
import com.gemstone.gemfire.security.SecurityTestUtil;

import dunit.Host;
import dunit.VM;
import dunit.DistributedTestCase;

/**
 * Tests the functionality of the {@link AdminDistributedSystem} security
 * 
 * @author Harsh Khanna
 * @since 5.5
 */
public class AdminDSSecurityDUnitTest extends AdminDUnitTestCase {

  /** The index of the VM in which the Locator Agent is hosted */
  protected static final int LOCATOR_VM = 1;

  private static VM locatorVM = null;

  private static boolean hookRegistered = false;

  protected String locators;

  private Properties dsProperties;

  private Properties javaProperties;

  private static final String[] expectedExceptions = {
      AuthenticationRequiredException.class.getName(),
      AuthenticationFailedException.class.getName(),
      GemFireSecurityException.class.getName(),
      SSLHandshakeException.class.getName(),
      ClassNotFoundException.class.getName(),
      java.rmi.NoSuchObjectException.class.getName(),
      javax.naming.NameNotFoundException.class.getName()
      };

  /**
   * Creates a new <code>AdminDSSecurityDUnitTest</code>
   */
  public AdminDSSecurityDUnitTest(String name) {
    super(name);
    dsProperties = new Properties();
  }

  @Override
  public boolean isJMX() {
    return true;
  }

  @Override
  public void setUp() throws Exception {
    boolean setUpFailed = true;
    try {
      DistributionManager.isDedicatedAdminVM = true;
      disconnectAllFromDS();

      // Success!
      setUpFailed = false;
    }
    finally {
      if (setUpFailed) {
        try {
          disconnectAllFromDS();
        }
        finally {
          DistributionManager.isDedicatedAdminVM = false;
        }
      }
    }

    final Host host = Host.getHost(0);
    locatorVM = host.getVM(LOCATOR_VM);
  }

  @Override
  public void tearDown2() throws Exception {
    // Note we only get here when explicitly called from caseTearDown
    try {
      ;
    }
    finally {
      try {
        disconnectAllFromDS();
      }
      finally {
        DistributionManager.isDedicatedAdminVM = false;
      }
    }
  }

  @Override
  protected void startAgent() throws Exception {
    if (isJMX()) {
      getLogWriter().info("[startAgent]");
      if (!hookRegistered) {
        Runtime.getRuntime().addShutdownHook(shutdownThread);
        hookRegistered = true;
      }

      int pid = hydra.ProcessMgr.getProcessId();
      String cwd = System.getProperty("user.dir");
      String propFileName = "agent.properties";

      File agentProps = new File(cwd + File.separator + propFileName);
      OutputStream outS = new FileOutputStream(agentProps);
      dsProperties.store(outS, "Store Agent Properties");
      outS.flush();
      outS.close();

      SecurityTestUtil.clearStaticSSLContext();
      SecurityTestUtil.setJavaProps(javaProperties);

      AgentConfigImpl config = new AgentConfigImpl(dsProperties);
      
      //set auto-connect to false. See #40390
      config.setAutoConnect(false);      
      this.agent = JMXHelper.startAgent(this, config, getDSConfig(), pid);
    }
  }

  private void clearSecurityProperties() {

    Properties props = System.getProperties();
    Properties newProps = new Properties();
    Iterator propIter = props.entrySet().iterator();
    while (propIter.hasNext()) {
      Map.Entry propEntry = (Map.Entry)propIter.next();
      String propKey = (String)propEntry.getKey();
      if (!propKey.startsWith(DistributionConfigImpl.SECURITY_SYSTEM_PREFIX
          + DistributionConfig.SECURITY_PREFIX_NAME)) {
        newProps.put(propKey, propEntry.getValue());
      }
    }
    System.setProperties(newProps);
  }

  @Override
  protected void stopAgent() throws Exception {
    if (this.isJMX()) {
      getLogWriter().info("[stopAgent]");
      super.stopAgent();
      if (hookRegistered) {
        Runtime.getRuntime().removeShutdownHook(shutdownThread);
        hookRegistered = false;
      }
      clearSecurityProperties();
    }
  }

  @Override
  protected void assertAgent() throws Exception {
    if (isJMX()) {
      super.assertAgent();
      getLogWriter().info("Assert Agent " + this.tcSystem);
    }
  }

  // ////// Test Methods

  private DistributionConfig getDSConfig() {
    getLogWriter().info("Using test specific Distributed System Properties ");
    // Figure out our distributed system properties
    String gemfireName = System.getProperty(GemFirePrms.GEMFIRE_NAME_PROPERTY);
    if (gemfireName == null) {
      String s = "No gemfire name has been specified";
      throw new HydraConfigException(s);
    }
    else {
      final GemFireDescription gfd = TestConfig.getInstance()
          .getGemFireDescription(gemfireName);

      return new DistributionConfig() {
        public void close() {
        }

        public int getAckWaitThreshold() {
          return 0;
        }

        public int getArchiveDiskSpaceLimit() {
          return 0;
        }

        public int getArchiveFileSizeLimit() {
          return 0;
        }

        public int getAsyncDistributionTimeout() {
          return 0;
        }

        public int getAsyncMaxQueueSize() {
          return 0;
        }

        public int getAsyncQueueTimeout() {
          return 0;
        }

        public String getBindAddress() {
          return null;
        }

        public File getCacheXmlFile() {
          return null;
        }

        public boolean getConserveSockets() {
          return false;
        }

        public boolean getDisableTcp() {
          return false;
        }

        public String getClientConflation() {
          return null;
        }
        
        public String getDurableClientId() {
          return null;
        }

        public int getDurableClientTimeout() {
          return 0;
        }

        public boolean getEnableTimeStatistics() {
          return false;
        }

        public String getLicenseDataManagement() {
          return null;
        }

        public String getLicenseApplicationCache() {
          return null;
        }

        public File getLicenseWorkingDir() {
          return null;
        }

        public File getDeployWorkingDir() {
          return null;
        }

        public int getLicenseServerTimeout() {
          return 0;
        }

        public String getLocators() {
          return locators;
        }

        public int getLogDiskSpaceLimit() {
          return 0;
        }

        public File getLogFile() {
          String testName = "AdminDSSecurityDUnitTest";
//          String cwd = System.getProperty("user.dir");
          File file = new File(testName + "_" + hydra.ProcessMgr.getProcessId()
              + "_jmxagent.log");
          return file;
        }

        public int getLogFileSizeLimit() {
          return 0;
        }

        public int getLogLevel() {
          return LogWriterImpl.levelNameToCode(getDUnitLogLevel());
        }

        public int getMaxNumReconnectTries() {
          return 0;
        }

        public int getMaxWaitTimeForReconnect() {
          return 0;
        }

        public InetAddress getMcastAddress() {
          try {
            return InetAddress.getByName(gfd.getMcastAddress());
          }
          catch (UnknownHostException ex) {
            getLogWriter().error(ex);
            return null;
          }
        }

        public FlowControlParams getMcastFlowControl() {
          return null;
        }

        public int getMcastPort() {
          return 0;
        }

        public int getMcastRecvBufferSize() {
          return 0;
        }

        public int getMcastSendBufferSize() {
          return 0;
        }

        public int getMcastTtl() {
          return 0;
        }

        public int getMemberTimeout() {
          return 0;
        }

        public String getName() {
          return null;
        }

        public String getRoles() {
          return null;
        }

        public String getSSLCiphers() {
          return null;
        }

        public boolean getSSLEnabled() {
          return false;
        }

        public String getSSLProtocols() {
          return null;
        }

        public boolean getSSLRequireAuthentication() {
          return false;
        }

        public String getSecurity(String attName) {
          return null;
        }

        public String getSecurityClientAccessor() {
          return null;
        }

        public String getSecurityClientAccessorPP() {
          return null;
        }

        public String getSecurityClientAuthInit() {
          return null;
        }

        public String getSecurityClientAuthenticator() {
          return null;
        }

        public String getSecurityClientDHAlgo() {
          return null;
        }

        public File getSecurityLogFile() {
          return null;
        }

        public int getSecurityLogLevel() {
          return 0;
        }

        public String getSecurityPeerAuthInit() {
          return null;
        }

        public String getSecurityPeerAuthenticator() {
          return null;
        }

        public int getSecurityPeerMembershipTimeout() {
          return 0;
        }

        public Properties getSecurityProps() {
          return null;
        }

        public String getServerBindAddress() {
          return null;
        }

        public int getSocketBufferSize() {
          return 0;
        }

        public int getSocketLeaseTime() {
          return 0;
        }

        public String getStartLocator() {
          return null;
        }

        public File getStatisticArchiveFile() {
          return null;
        }

        public int getStatisticSampleRate() {
          return 0;
        }

        public boolean getStatisticSamplingEnabled() {
          return false;
        }

        public boolean getStatusMonitoringEnabled() {
          return false;
        }

        public int getStatusMonitoringPort() {
          return 0;
        }

        public int getTcpPort() {
          return 0;
        }

        public int getUdpFragmentSize() {
          return 0;
        }

        public int getUdpRecvBufferSize() {
          return 0;
        }

        public int getUdpSendBufferSize() {
          return 0;
        }
        
        public String getUserCommandPackages() {
          return null;
        }

        public boolean isAckWaitThresholdModifiable() {
          return false;
        }

        public boolean isArchiveDiskSpaceLimitModifiable() {
          return false;
        }

        public boolean isArchiveFileSizeLimitModifiable() {
          return false;
        }

        public boolean isAsyncDistributionTimeoutModifiable() {
          return false;
        }

        public boolean isAsyncMaxQueueSizeModifiable() {
          return false;
        }

        public boolean isAsyncQueueTimeoutModifiable() {
          return false;
        }

        public boolean isBindAddressModifiable() {
          return false;
        }

        public boolean isCacheXmlFileModifiable() {
          return false;
        }

        public boolean isConserveSocketsModifiable() {
          return false;
        }

        public boolean isDisableTcpModifiable() {
          return false;
        }

        public boolean isClientConflationModifiable() {
          return false;
        }
        
        public boolean isDurableClientIdModifiable() {
          return false;
        }

        public boolean isDurableClientTimeoutModifiable() {
          return false;
        }

        public boolean isDeployWorkingDirModifiable() {
          return false;
        }
        
        public boolean isLicenseDataManagementModifiable() {
          return false;
        }

        public boolean isLicenseApplicationCacheModifiable() {
          return false;
        }

        public boolean isLicenseWorkingDirModifiable() {
          return false;
        }

        public boolean isLicenseServerTimeoutModifiable() {
          return false;
        }

        public boolean isLocatorsModifiable() {
          return false;
        }

        public boolean isLogDiskSpaceLimitModifiable() {
          return false;
        }

        public boolean isLogFileModifiable() {
          return false;
        }

        public boolean isLogFileSizeLimitModifiable() {
          return false;
        }

        public boolean isLogLevelModifiable() {
          return false;
        }

        public boolean isMcastAddressModifiable() {
          return false;
        }

        public boolean isMcastFlowControlModifiable() {
          return false;
        }

        public boolean isMcastPortModifiable() {
          return false;
        }

        public boolean isMcastRecvBufferSizeModifiable() {
          return false;
        }

        public boolean isMcastSendBufferSizeModifiable() {
          return false;
        }

        public boolean isMcastTtlModifiable() {
          return false;
        }

        public boolean isMemberTimeoutModifiable() {
          return false;
        }

        public boolean isNameModifiable() {
          return false;
        }

        public boolean isSecurityClientAuthInitModifiable() {
          return false;
        }

        public boolean isSecurityClientAuthenticatorModifiable() {
          return false;
        }

        public boolean isSecurityClientDHAlgoModifiable() {
          return false;
        }

        public boolean isSecurityLogFileModifiable() {
          return false;
        }

        public boolean isSecurityLogLevelModifiable() {
          return false;
        }

        public boolean isSecurityModifiable() {
          return false;
        }

        public boolean isSecurityPeerAuthInitModifiable() {
          return false;
        }

        public boolean isSecurityPeerAuthenticatorModifiable() {
          return false;
        }

        public boolean isSecurityPeerMembershipTimeoutModifiable() {
          return false;
        }

        public boolean isServerBindAddressModifiable() {
          return false;
        }

        public boolean isSocketBufferSizeModifiable() {
          return false;
        }

        public boolean isSocketLeaseTimeModifiable() {
          return false;
        }

        public boolean isStartLocatorModifiable() {
          return false;
        }

        public boolean isStatisticArchiveFileModifiable() {
          return false;
        }

        public boolean isStatisticSampleRateModifiable() {
          return false;
        }

        public boolean isStatisticSamplingEnabledModifiable() {
          return false;
        }

        public boolean isTcpPortModifiable() {
          return false;
        }

        public boolean isUdpFragmentSizeModifiable() {
          return false;
        }

        public boolean isUdpRecvBufferSizeModifiable() {
          return false;
        }

        public boolean isUdpSendBufferSizeModifiable() {
          return false;
        }

        public boolean isUserCommandPackagesModifiable() {
          return false;
        }
        
        public void setAckWaitThreshold(int newThreshold) {
        }

        public void setArchiveDiskSpaceLimit(int value) {
        }

        public void setArchiveFileSizeLimit(int value) {
        }

        public void setAsyncDistributionTimeout(int newValue) {
        }

        public void setAsyncMaxQueueSize(int newValue) {
        }

        public void setAsyncQueueTimeout(int newValue) {
        }

        public void setBindAddress(String value) {
        }

        public void setCacheXmlFile(File value) {
        }

        public void setConserveSockets(boolean newValue) {
        }

        public void setDisableTcp(boolean newValue) {
        }

        public void setClientConflation(String clientConflation) {
        }
        
        public void setDurableClientId(String durableClientId) {
        }

        public void setDurableClientTimeout(int durableClientTimeout) {
        }

        public void setEnableTimeStatistics(boolean newValue) {
        }

        public void setDeployWorkingDir(File value) {
        }
        
        public void setLicenseDataManagement(String value) {
        }
        
        public void setLicenseApplicationCache(String value) {
        }
        
        public void setLicenseWorkingDir(File value) {
        }
        
        public void setLicenseServerTimeout(int value) {
        }

        public void setLocators(String value) {
        }

        public void setLogDiskSpaceLimit(int value) {
        }

        public void setLogFile(File value) {
        }

        public void setLogFileSizeLimit(int value) {
        }

        public void setLogLevel(int value) {
        }

        public void setMaxNumReconnectTries(int tries) {
        }

        public void setMaxWaitTimeForReconnect(int timeOut) {
        }

        public void setMcastAddress(InetAddress value) {
        }

        public void setMcastFlowControl(FlowControlParams values) {
        }

        public void setMcastPort(int value) {
        }

        public void setMcastRecvBufferSize(int value) {
        }

        public void setMcastSendBufferSize(int value) {
        }

        public void setMcastTtl(int value) {
        }

        public void setMemberTimeout(int value) {
        }

        public void setName(String value) {
        }

        public void setRoles(String roles) {
        }

        public void setSSLCiphers(String ciphers) {
        }

        public void setSSLEnabled(boolean enabled) {
        }

        public void setSSLProtocols(String protocols) {
        }

        public void setSSLRequireAuthentication(boolean ciphers) {
        }

        public void setSecurity(String attName, String attValue) {
        }

        public void setSecurityClientAccessor(String attValue) {
        }

        public void setSecurityClientAccessorPP(String attValue) {
        }

        public void setSecurityClientAuthInit(String attValue) {
        }

        public void setSecurityClientAuthenticator(String attValue) {
        }

        public void setSecurityClientDHAlgo(String attValue) {
        }

        public void setSecurityLogFile(File value) {
        }

        public void setSecurityLogLevel(int level) {
        }

        public void setSecurityPeerAuthInit(String attValue) {
        }

        public void setSecurityPeerAuthenticator(String attValue) {
        }

        public void setSecurityPeerMembershipTimeout(int attValue) {
        }

        public void setServerBindAddress(String value) {
        }

        public void setSocketBufferSize(int value) {
        }

        public void setSocketLeaseTime(int value) {
        }

        public void setStartLocator(String value) {
        }

        public void setStatisticArchiveFile(File value) {
        }

        public void setStatisticSampleRate(int value) {
        }

        public void setStatisticSamplingEnabled(boolean newValue) {
        }

        public void setTcpPort(int value) {
        }

        public void setUdpFragmentSize(int value) {
        }

        public void setUdpRecvBufferSize(int value) {
        }

        public void setUdpSendBufferSize(int value) {
        }

        public void setUserCommandPackages(String value) {
        }
        
        public String getAttribute(String attName) {
          return null;
        }

        public String getAttributeDescription(String attName) {
          return null;
        }

        public String[] getAttributeNames() {
          return null;
        }

        public Object getAttributeObject(String attName) {
          return null;
        }

        public Class getAttributeType(String attName) {
          return null;
        }

        public String[] getSpecificAttributeNames() {
          return null;
        }

        public boolean isAttributeModifiable(String attName) {
          return false;
        }

        public boolean sameAs(Config v) {
          return false;
        }

        @Override
        public void setAttribute(String attName, String attValue, ConfigSource source) {
        }

        @Override
        public void setAttributeObject(String attName, Object attValue, ConfigSource source) {
        }

        public void toFile(File f) throws IOException {
        }

        public Properties toProperties() {
          return null;
        }

        public boolean getEnableNetworkPartitionDetection() {
          return false;
        }
        public void setEnableNetworkPartitionDetection(boolean v) {
        }
        public boolean getDisableAutoReconnect() {
          return false;
        }
        public void setDisableAutoReconnect(boolean v) {
        }
        public boolean isEnableNetworkPartitionDetectionModifiable() {
          return false;
        }
        public int getAckSevereAlertThreshold() {
          return 0;
        }
        public void setAckSevereAlertThreshold(int i) {
        }
        public boolean isAckSevereAlertThresholdModifiable() {
          return false;
        }
        public boolean isDeltaPropagationModifiable() {
          return false;
        }        
        public void setDeltaPropagation(boolean b){
        }
        public boolean getDeltaPropagation() {
          return true;
        }
        public boolean getRemoveUnresponsiveClient() {
          return false;
        }
        public void setRemoveUnresponsiveClient(boolean v) {
        }
        public boolean isRemoveUnresponsiveClientModifiable() {
          return false;
        }

        public int[] getMembershipPortRange() {
          return null;
        }

        public void setMembershipPortRange(int[] range) {
        }

        public void setRemoteLocators(String locators) {
        }

        public String getRemoteLocators() {
          return null;
        }

        public void setDistributedSystemId(int distributedSystemId) {
        }

        public int getDistributedSystemId() {
          return -1;
        }
        
        public boolean getEnforceUniqueHost() {
          return false;
        }

        public String getRedundancyZone() {
          return null;
        }

        public Properties getUserDefinedProps() {
          return null;
        }

        public void setEnforceUniqueHost(boolean enforceUniqueHost) {
        }

        public void setRedundancyZone(String redundancyZone) {
        }

        public void setWritableWorkingDir(File value) {
        }

        public File getWritableWorkingDir() {
          return null;
        }

        @Override
        public void setSSLProperty(String attName, String attValue) {
        }

        @Override
        public Properties getSSLProperties() {
          return null;
        }

        public Properties getJmxSSLProperties() {
          return null;
        }

        @Override
        public String getGroups() {
          return null;
        }

        @Override
        public void setGroups(String value) {
        }

        @Override
        public boolean isGroupsModifiable() {
          return false;
        }

        @Override
        public ConfigSource getAttributeSource(String attName) {
          return null;
        }

        @Override
        public String toLoggerString() {
          return "";
        }

        @Override
        public boolean getJmxManager() {
          return false;
        }

        @Override
        public void setJmxManager(boolean value) {
        }

        @Override
        public boolean isJmxManagerModifiable() {
          return false;
        }

        @Override
        public boolean getJmxManagerStart() {
          return false;
        }

        @Override
        public void setJmxManagerStart(boolean value) {
        }

        @Override
        public boolean isJmxManagerStartModifiable() {
          return false;
        }

        @Override
        public int getJmxManagerPort() {
          return 0;
        }

        @Override
        public void setJmxManagerPort(int value) {
        }

        @Override
        public boolean isJmxManagerPortModifiable() {
          return false;
        }

        @Override
        public boolean getJmxManagerSSL() {
          return false;
        }

        @Override
        public void setJmxManagerSSL(boolean value) {
        }

        @Override
        public boolean isJmxManagerSSLModifiable() {
          return false;
        }

        @Override
        public String getJmxManagerBindAddress() {
          return "";
        }

        @Override
        public void setJmxManagerBindAddress(String value) {
        }

        @Override
        public boolean isJmxManagerBindAddressModifiable() {
          return false;
        }

        @Override
        public String getJmxManagerPasswordFile() {
          return "";
        }

        @Override
        public void setJmxManagerPasswordFile(String value) {
        }

        @Override
        public boolean isJmxManagerPasswordFileModifiable() {
          return false;
        }

        @Override
        public String getJmxManagerAccessFile() {
          return "";
        }

        @Override
        public void setJmxManagerAccessFile(String value) {
        }

        @Override
        public boolean isJmxManagerAccessFileModifiable() {
          return false;
        }

        @Override
        public int getJmxManagerHttpPort() {
          return 0;
        }

        @Override
        public void setJmxManagerHttpPort(int value) {
        }

        @Override
        public boolean isJmxManagerHttpPortModifiable() {
          return false;
        }

        @Override
        public int getMemcachedPort() {
          return 0;
        }

        @Override
        public void setMemcachedPort(int value) {
        }

        @Override
        public boolean isMemcachedPortModifiable() {
          return false;
        }

        @Override
        public String getJmxManagerHostnameForClients() {
          return "";
        }

        @Override
        public void setJmxManagerHostnameForClients(String value) {
        }

        @Override
        public boolean isJmxManagerHostnameForClientsModifiable() {
          return false;
        }

        @Override
        public int getJmxManagerUpdateRate() {
          return 0;
        }

        @Override
        public void setJmxManagerUpdateRate(int value) {
        }

        @Override
        public boolean isJmxManagerUpdateRateModifiable() {
          return false;
        }

        @Override
        public String getMemcachedProtocol() {
          return "ASCII";
        }

        @Override
        public void setMemcachedProtocol(String protocol) {
        }

        @Override
        public boolean isMemcachedProtocolModifiable() {
          return false;
        }

        @Override
        public String getOffHeapMemorySize() {
          return null;
        }

        @Override
        public void setOffHeapMemorySize(String value) {
        }

        @Override
        public boolean isMemorySizeModifiable() {
          return false;
        }

        @Override
        public String getMemorySize() {
          return null;
        }

        @Override
        public void setMemorySize(String value) {

        }

        @Override
        public boolean isOffHeapMemorySizeModifiable() {
          return false;
        }

        @Override
        public boolean getJmxManagerSSLRequireAuthentication() {
          return false;
        }
        @Override
        public void setJmxManagerSSLRequireAuthentication(boolean value) {
        }
        @Override
        public boolean isJmxManagerSSLRequireAuthenticationModifiable() {
          return false;
        }

        @Override
        public String getJmxManagerSSLProtocols() {
          return null;
        }
        @Override
        public void setJmxManagerSSLProtocols(String protocols) {
        }
        @Override
        public boolean isJmxManagerSSLProtocolsModifiable() {
          return false;
        }

        @Override
        public String getJmxManagerSSLCiphers() {
          return null;
        }
        @Override
        public void setJmxManagerSSLCiphers(String ciphers) {
        }
        @Override
        public boolean isJmxManagerSSLCiphersModifiable() {
          return false;
        }

        @Override
        public boolean getLockMemory() {
          return false;
        }

        @Override
        public void setLockMemory(boolean value) {
        }

        @Override
        public boolean isLockMemoryModifiable() {
          return false;
        }
      };
    }
  }

  /**
   * Tests connect to the API
   */
  public void testNoCredentials() throws Exception {
    Iterator iter = SecurityTestUtil.getAllGenerators().iterator();
    int port = -1;
    while (iter.hasNext()) {
      try {
        CredentialGenerator gen = (CredentialGenerator)iter.next();

        port = -1;
        dsProperties = new Properties();
        dsProperties.put("mcast-port", ""+AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS));
        javaProperties = new Properties();
        port = startLocator(gen, dsProperties, javaProperties);
        // Discard the javaProperties to discard any credentials in those
        javaProperties = null;

        startAgent();
        SecurityTestUtil.addExpectedExceptions(
            new String[] { AuthenticationFailedException.class.getName() }, 
            this.agent.getLogWriter());
        try {
          agent.connectToSystem();
          getLogWriter().info(
              "locators for agent = "
                  + agent.getDistributedSystem().getLocators());
  
          // Wait for time & see if connected?
          pause(10000);
        }
        finally {
          SecurityTestUtil.removeExpectedExceptions(
              new String[] { AuthenticationFailedException.class.getName() }, 
              this.agent.getLogWriter());
        }

        // Will do a mbs.connectToSystem
        if (this.agent != null && this.agent.isConnected()) {
          fail("AuthenticationFailedException was expected as the "
              + "AuthInitialize object not passed");
        }
        else {
          getLogWriter().info("Agent is not connected as expected.");
        }
      } catch (dunit.RMIException remoteEx) {
        if (!(remoteEx.getCause() instanceof AuthenticationFailedException)) {
          throw remoteEx;
        }
        // now expected from locator due to invalid properties for AuthInitialize
      } catch (AuthenticationFailedException afe) {
        // success
      } finally {
        if (agent != null)
          stopAgent();

        if (port != -1)
          locatorVM.invoke(SecurityTestUtil.class, "stopLocator", new Object[] {
              new Integer(port), expectedExceptions });
      }
    }
  }

  public void testInvalidCredentials() throws Exception {
    Iterator iter = SecurityTestUtil.getAllGenerators().iterator();
    int port = -1;
    while (iter.hasNext()) {
      try {
        CredentialGenerator gen = (CredentialGenerator)iter.next();

        dsProperties = new Properties();
        dsProperties.putAll(gen.getInvalidCredentials(4));
        javaProperties = new Properties();
        port = startLocator(gen, dsProperties, javaProperties);

        startAgent();
        SecurityTestUtil.addExpectedExceptions(
            new String[] { AuthenticationFailedException.class.getName() }, 
            this.agent.getLogWriter());
        try {
          agent.connectToSystem();
          
          // Wait for time & see if connected?
          pause(10000);
        }
        finally {
          SecurityTestUtil.removeExpectedExceptions(
              new String[] { AuthenticationFailedException.class.getName() }, 
              this.agent.getLogWriter());
        }
        
        // Will do a mbs.connectToSystem
        if (this.agent != null && this.agent.isConnected())
          fail("AuthenticationFailedException was expected as the incorrect credentials were passed");
        else
          getLogWriter().info("Agent is now not connected ");
      }
      finally {
        if (agent != null)
          stopAgent();

        if (port != -1)
          locatorVM.invoke(SecurityTestUtil.class, "stopLocator", new Object[] {
              new Integer(port), expectedExceptions });
      }
    }
  }

  public void testCredentials() throws Exception {
    Iterator iter = SecurityTestUtil.getAllGenerators().iterator();
    int port = -1;
    while (iter.hasNext()) {
      try {
        CredentialGenerator gen = (CredentialGenerator)iter.next();

        dsProperties = new Properties();
        dsProperties.putAll(gen.getValidCredentials(1));
        javaProperties = new Properties();
        port = startLocator(gen, dsProperties, javaProperties);

        startAgent();
        SecurityTestUtil.addExpectedExceptions(
            new String[] { AuthenticationFailedException.class.getName() },
            this.agent.getLogWriter());
        try {
          agent.connectToSystem();

          WaitCriterion wc = new WaitCriterion() {
            String excuse;
            public boolean done() {
              if (agent == null) {
                excuse = "agent is null";
                return false;
              }
              if (!agent.isConnected()) {
                excuse = "agent not connected";
                return false;
              }
              return true;
            }
            public String description() {
              return excuse;
            }
          };
          DistributedTestCase.waitForCriterion(wc, 30 * 1000, 1000, true);
          getLogWriter().info("Agent is now connected ");
        }
        finally {
          SecurityTestUtil.removeExpectedExceptions(
              new String[] { AuthenticationFailedException.class.getName() },
              this.agent.getLogWriter());
        }
      }
      finally {
        if (agent != null)
          stopAgent();

        if (port != -1)
          locatorVM.invoke(SecurityTestUtil.class, "stopLocator", new Object[] {
              new Integer(port), expectedExceptions });
      }
    }
  }

  private int startLocator(CredentialGenerator gen, Properties propsSet,
      Properties javaPropsSet) {
    getLogWriter().info("Start locator for CredentialGenerator " + gen);

    int port = -1;

    Properties extraProps = gen.getSystemProperties();
    Properties javaProps = gen.getJavaProperties();
    String authenticator = gen.getAuthenticator();
    String authInit = gen.getAuthInit();
    if (extraProps == null) {
      extraProps = new Properties();
    }
    port = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    locators = DistributedTestCase.getIPLiteral() + "[" + port + "]";

    propsSet.putAll(extraProps);
    propsSet.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    propsSet.setProperty(DistributionConfig.LOCATORS_NAME, locators);
    setProperty(propsSet, DistributionConfig.SECURITY_PEER_AUTH_INIT_NAME,
        authInit);
    setProperty(propsSet, DistributionConfig.SECURITY_PEER_AUTHENTICATOR_NAME,
        authenticator);

    if (javaProps != null) {
      javaPropsSet.putAll(javaProps);
    }
    locatorVM.invoke(SecurityTestUtil.class, "startLocator", new Object[] {
        getUniqueName(), new Integer(port), propsSet, javaProps,
        expectedExceptions });

    return port;
  }

  private void setProperty(Properties props, String key, String value) {
    if (key != null && value != null) {
      props.setProperty(key, value);
    }
  }

}
