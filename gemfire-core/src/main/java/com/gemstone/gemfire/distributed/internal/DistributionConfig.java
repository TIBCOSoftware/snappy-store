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

package com.gemstone.gemfire.distributed.internal;

import java.io.File;
import java.net.InetAddress;
import java.util.Properties;

import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.internal.Config;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.ManagerLogWriter;
import com.gemstone.gemfire.internal.shared.LauncherBase;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.tcp.Connection;
import com.gemstone.gemfire.memcached.GemFireMemcachedServer;

/**
 * Provides accessor (and in some cases mutator) methods for the
 * various GemFire distribution configuration properties.  The
 * interface also provides constants for the names of properties and
 * their default values.
 *
 * <P>
 *
 * Decriptions of these properties can be found <a
 * href="../DistributedSystem.html#configuration">here</a>.
 *
 * @see com.gemstone.gemfire.internal.Config
 *
 * @author David Whitlock
 * @author Darrel Schneider
 *
 * @since 2.1
 */
public interface DistributionConfig extends Config, ManagerLogWriter.LogConfig {


  ////////////////////  Instance Methods  ////////////////////

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#name">"name"</a> property
   * Gets the member's name.
   * A name is optional and by default empty.
   * If set it must be unique in the ds.
   * When set its used by tools to help identify the member.
   * <p> The default value is: {@link #DEFAULT_NAME}.
   * @return the system's name.
   */
  public String getName();
  /**
   * Sets the member's name.
   * <p> The name can not be changed while the system is running.
   * @throws IllegalArgumentException if the specified value is not acceptable.
   * @throws com.gemstone.gemfire.UnmodifiableException if this attribute can not be modified.
   * @throws com.gemstone.gemfire.GemFireIOException if the set failure is caused by an error
   *   when writing to the system's configuration file.
   */
  public void setName(String value);
  /**
   * Returns true if the value of the <code>name</code> attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isNameModifiable();
  /** The name of the "name" property */
  public static final String NAME_NAME = "name";

  /**
   * The default system name.
   * <p> Actual value of this constant is <code>""</code>.
   */
  public static final String DEFAULT_NAME = "";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#mcast-port">"mcast-port"</a>
   * property
   */
  public int getMcastPort();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#mcast-port">"mcast-port"</a>
   * property
   */
  public void setMcastPort(int value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isMcastPortModifiable();

  /** The name of the "mcastPort" property */
  public static final String MCAST_PORT_NAME = "mcast-port";

  /** The default value of the "mcastPort" property */
  public static final int DEFAULT_MCAST_PORT = 10334;
  /**
   * The minimum mcastPort.
   * <p> Actual value of this constant is <code>0</code>.
   */
  public static final int MIN_MCAST_PORT = 0;
  /**
   * The maximum mcastPort.
   * <p> Actual value of this constant is <code>65535</code>.
   */
  public static final int MAX_MCAST_PORT = 65535;


  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#tcp-port">"tcp-port"</a>
   * property
   */
  public int getTcpPort();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#tcp-port">"tcp-port"</a>
   * property
   */
  public void setTcpPort(int value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isTcpPortModifiable();
  /** The name of the "tcpPort" property */
  public static final String TCP_PORT_NAME = "tcp-port";
  /** The default value of the "tcpPort" property */
  public static final int DEFAULT_TCP_PORT = 0;
  /**
   * The minimum tcpPort.
   * <p> Actual value of this constant is <code>0</code>.
   */
  public static final int MIN_TCP_PORT = 0;
  /**
   * The maximum tcpPort.
   * <p> Actual value of this constant is <code>65535</code>.
   */
  public static final int MAX_TCP_PORT = 65535;


  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#mcast-address">"mcast-address"</a>
   * property
   */
  public InetAddress getMcastAddress();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#mcast-address">"mcast-address"</a>
   * property
   */
  public void setMcastAddress(InetAddress value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isMcastAddressModifiable();

  /** The name of the "mcastAddress" property */
  public static final String MCAST_ADDRESS_NAME = "mcast-address";

  /** The default value of the "mcastAddress" property.
   * Current value is <code>239.192.81.1</code>
   */
  public static final InetAddress DEFAULT_MCAST_ADDRESS = AbstractDistributionConfig._getDefaultMcastAddress();

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#mcast-ttl">"mcast-ttl"</a>
   * property
   */
  public int getMcastTtl();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#mcast-ttl">"mcast-ttl"</a>
   * property
   */
  public void setMcastTtl(int value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isMcastTtlModifiable();

  /** The name of the "mcastTtl" property */
  public static final String MCAST_TTL_NAME = "mcast-ttl";

  /** The default value of the "mcastTtl" property */
  public static final int DEFAULT_MCAST_TTL = 32;
  /**
   * The minimum mcastTtl.
   * <p> Actual value of this constant is <code>0</code>.
   */
  public static final int MIN_MCAST_TTL = 0;
  /**
   * The maximum mcastTtl.
   * <p> Actual value of this constant is <code>255</code>.
   */
  public static final int MAX_MCAST_TTL = 255;
  
  
  public static final int MIN_DISTRIBUTED_SYSTEM_ID = -1;
  
  public static final int MAX_DISTRIBUTED_SYSTEM_ID = 255;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#bind-address">"bind-address"</a>
   * property
   */
  public String getBindAddress();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#bind-address">"bind-address"</a>
   * property
   */
  public void setBindAddress(String value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isBindAddressModifiable();

  /** The name of the "bindAddress" property */
  public static final String BIND_ADDRESS_NAME = "bind-address";

  /** The default value of the "bindAddress" property.
   * Current value is an empty string <code>""</code>
   */
  public static final String DEFAULT_BIND_ADDRESS = "";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#server-bind-address">"server-bind-address"</a>
   * property
   */
  public String getServerBindAddress();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#server-bind-address">"server-bind-address"</a>
   * property
   */
  public void setServerBindAddress(String value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isServerBindAddressModifiable();

  /** The name of the "serverBindAddress" property */
  public static final String SERVER_BIND_ADDRESS_NAME = "server-bind-address";

  /** The default value of the "serverBindAddress" property.
   * Current value is an empty string <code>""</code>
   */
  public static final String DEFAULT_SERVER_BIND_ADDRESS = "";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#locators">"locators"</a> property
   */
  public String getLocators();
  /**
   * Sets the system's locator list.
   * A locator list is optional and by default empty.
   * Its used to by the system to locator other system nodes
   * and to publish itself so it can be located by others.
   * @param value must be of the form <code>hostName[portNum]</code>.
   *  Multiple elements are allowed and must be seperated by a comma.
   * @throws IllegalArgumentException if the specified value is not acceptable.
   * @throws com.gemstone.gemfire.UnmodifiableException if this attribute can not be modified.
   * @throws com.gemstone.gemfire.GemFireIOException if the set failure is caused by an error
   *   when writing to the system's configuration file.
   */
  public void setLocators(String value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isLocatorsModifiable();

  /** The name of the "locators" property */
  public static final String LOCATORS_NAME = "locators";

  /** The default value of the "locators" property */
  public static final String DEFAULT_LOCATORS = "";

  /**
   * returns the value of the <a href="../DistribytedSystem.html#start-locator">"start-locator"
   * </a> property
   */
  public String getStartLocator();
  /**
   * Sets the start-locators property.  This is a string in the form
   * bindAddress[port] and, if set, tells the distributed system to start
   * a locator prior to connecting
   * @param value must be of the form <code>hostName[portNum]</code>
   */
  public void setStartLocator(String value);
  /**
   * returns true if the value of the attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isStartLocatorModifiable();
  /**
   * The name of the "start-locators" property
   */
  public static final String START_LOCATOR_NAME = "start-locator";
  /**
   * The default value of the "start-locators" property
   */
  public static final String DEFAULT_START_LOCATOR = "";
  
  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#deploy-working-dir">"deploy-working-dir"</a> property
   */
  public File getDeployWorkingDir();
  
  /**
   * Sets the system's deploy working directory.
   * @throws IllegalArgumentException if the specified value is not acceptable.
   * @throws com.gemstone.gemfire.UnmodifiableException if this attribute can not be modified.
   * @throws com.gemstone.gemfire.GemFireIOException if the set failure is caused by an error
   *   when writing to the system's configuration file.
   */
  public void setDeployWorkingDir(File value);
  
  /**
   * Returns true if the value of the deploy-working-dir attribute can 
   * currently be modified. Some attributes can not be modified while the system is running.
   */
  public boolean isDeployWorkingDirModifiable();
  
  /**
   * The name of the "deploy-working-dir" property.
   */
  public static final String DEPLOY_WORKING_DIR = "deploy-working-dir";
  
  /**
   * Default will be the current working directory as determined by 
   * <code>System.getProperty("user.dir")</code>.
   */
  public static final File DEFAULT_DEPLOY_WORKING_DIR = new File(".");
  
  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#user-command-packages">"user-command-packages"</a> property
   */
  public String getUserCommandPackages();
  
  /**
   * Sets the system's user command path.
   * @throws IllegalArgumentException if the specified value is not acceptable.
   * @throws com.gemstone.gemfire.UnmodifiableException if this attribute can not be modified.
   * @throws com.gemstone.gemfire.GemFireIOException if the set failure is caused by an error
   *   when writing to the system's configuration file.
   */
  public void setUserCommandPackages(String value);
  
  /**
   * Returns true if the value of the user-command-packages attribute can 
   * currently be modified. Some attributes can not be modified while the system is running.
   */
  public boolean isUserCommandPackagesModifiable();
  
  /**
   * The name of the "user-command-packages" property.
   */
  public static final String USER_COMMAND_PACKAGES = "user-command-packages";
  
  /**
   * The default value of the "user-command-packages" property
   */
  public static final String DEFAULT_USER_COMMAND_PACKAGES = "";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#log-file">"log-file"</a> property
   *
   * @return <code>null</code> if logging information goes to standard
   *         out
   */
  public File getLogFile();
  /**
   * Sets the system's log file.
   * <p> Non-absolute log files are relative to the system directory.
   * <p> The system log file can not be changed while the system is running.
   * @throws IllegalArgumentException if the specified value is not acceptable.
   * @throws com.gemstone.gemfire.UnmodifiableException if this attribute can not be modified.
   * @throws com.gemstone.gemfire.GemFireIOException if the set failure is caused by an error
   *   when writing to the system's configuration file.
   */
  public void setLogFile(File value);
  /**
   * Returns true if the value of the logFile attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isLogFileModifiable();
  /** The name of the "logFile" property */
  public static final String LOG_FILE_NAME = LauncherBase.LOG_FILE;

  /**
   * The default log file.
   * <p> Actual value of this constant is <code>""</code> which directs
   * log message to standard output.
   */
  public static final File DEFAULT_LOG_FILE = new File("");

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#log-level">"log-level"</a> property
   *
   * @see com.gemstone.gemfire.internal.LogWriterImpl
   */
  public int getLogLevel();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#log-level">"log-level"</a> property
   *
   * @see com.gemstone.gemfire.internal.LogWriterImpl
   */
  public void setLogLevel(int value);

  /**
   * Returns true if the value of the logLevel attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isLogLevelModifiable();

  /** The name of the "logLevel" property */
  public static final String LOG_LEVEL_NAME = "log-level";

  /**
   * The default log level.
   * <p> Actual value of this constant is {@link LogWriterImpl#CONFIG_LEVEL}.
   */
  public static final int DEFAULT_LOG_LEVEL = LogWriterImpl.CONFIG_LEVEL;
  /**
   * The minimum log level.
   * <p> Actual value of this constant is {@link LogWriterImpl#ALL_LEVEL}.
   */
  public static final int MIN_LOG_LEVEL = LogWriterImpl.ALL_LEVEL;
  /**
   * The maximum log level.
   * <p> Actual value of this constant is {@link LogWriterImpl#NONE_LEVEL}.
   */
  public static final int MAX_LOG_LEVEL = LogWriterImpl.NONE_LEVEL;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#statistic-sampling-enabled">"statistic-sampling-enabled"</a>
   * property
   */
  public boolean getStatisticSamplingEnabled();
  /**
   * Sets StatisticSamplingEnabled
   */
  public void setStatisticSamplingEnabled(boolean newValue);
  /**
   * Returns true if the value of the StatisticSamplingEnabled attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isStatisticSamplingEnabledModifiable();
  /** The name of the "statisticSamplingEnabled" property */
  public static final String STATISTIC_SAMPLING_ENABLED_NAME =
    "statistic-sampling-enabled";

  /** The default value of the "statisticSamplingEnabled" property */
  public static final boolean DEFAULT_STATISTIC_SAMPLING_ENABLED = true;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#statistic-sample-rate">"statistic-sample-rate"</a>
   * property
   */
  public int getStatisticSampleRate();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#statistic-sample-rate">"statistic-sample-rate"</a>
   * property
   */
  public void setStatisticSampleRate(int value);
  /**
   * Returns true if the value of the statisticSampleRate attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isStatisticSampleRateModifiable();
  /**
   * The default statistic sample rate.
   * <p> Actual value of this constant is <code>1000</code> milliseconds.
   */
  public static final int DEFAULT_STATISTIC_SAMPLE_RATE = 1000;
  /**
   * The minimum statistic sample rate.
   * <p> Actual value of this constant is <code>100</code> milliseconds.
   */
  public static final int MIN_STATISTIC_SAMPLE_RATE = 100;
  /**
   * The maximum statistic sample rate.
   * <p> Actual value of this constant is <code>60000</code> milliseconds.
   */
  public static final int MAX_STATISTIC_SAMPLE_RATE = 60000;

  /** The name of the "statisticSampleRate" property */
  public static final String STATISTIC_SAMPLE_RATE_NAME =
    "statistic-sample-rate";

  /**
   * Returns the value of the <a href="../DistributedSystem.html#statistic-archive-file">"statistic-archive-file"</a> property.
   *
   * @return <code>null</code> if no file was specified
   */
  public File getStatisticArchiveFile();
  /**
   * Sets the value of the <a href="../DistributedSystem.html#statistic-archive-file">"statistic-archive-file"</a> property.
   */
  public void setStatisticArchiveFile(File value);
  /**
   * Returns true if the value of the statisticArchiveFile attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isStatisticArchiveFileModifiable();
  /** The name of the "statisticArchiveFile" property */
  public static final String STATISTIC_ARCHIVE_FILE_NAME =
    "statistic-archive-file";

  /**
   * The default statistic archive file.
   * <p> Actual value of this constant is <code>""</code> which
   * causes no archive file to be created.
   */
  public static final File DEFAULT_STATISTIC_ARCHIVE_FILE = new File(""); // fix for bug 29786


  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#cache-xml-file">"cache-xml-file"</a>
   * property
   */
  public File getCacheXmlFile();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#cache-xml-file">"cache-xml-file"</a>
   * property
   */
  public void setCacheXmlFile(File value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isCacheXmlFileModifiable();
  /** The name of the "cacheXmlFile" property */
  public static final String CACHE_XML_FILE_NAME = "cache-xml-file";

  /** The default value of the "cacheXmlFile" property */
  public static final File DEFAULT_CACHE_XML_FILE = new File("cache.xml");

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#ack-wait-threshold">"ack-wait-threshold"</a>
   * property
   */
  public int getAckWaitThreshold();

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#ack-wait-threshold">"ack-wait-threshold"</a>
   * property
     * Setting this value too low will cause spurious alerts.
     */
  public void setAckWaitThreshold(int newThreshold);
  /**
   * Returns true if the value of the AckWaitThreshold attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isAckWaitThresholdModifiable();
  /** The name of the "ackWaitThreshold" property */
  public static final String ACK_WAIT_THRESHOLD_NAME = "ack-wait-threshold";

  /**
   * The default AckWaitThreshold.
   * <p> Actual value of this constant is <code>15</code> seconds.
   */
  public static final int DEFAULT_ACK_WAIT_THRESHOLD = 15;
  /**
   * The minimum AckWaitThreshold.
   * <p> Actual value of this constant is <code>1</code> second.
   */
  public static final int MIN_ACK_WAIT_THRESHOLD = 1;
  /**
   * The maximum AckWaitThreshold.
   * <p> Actual value of this constant is <code>MAX_INT</code> seconds.
   */
  public static final int MAX_ACK_WAIT_THRESHOLD = Integer.MAX_VALUE;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#ack-severe-alert-threshold">"ack-severe-alert-threshold"</a>
   * property
   */
  public int getAckSevereAlertThreshold();

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#ack-severe-alert-threshold">"ack-severe-alert-threshold"</a>
   * property
     * Setting this value too low will cause spurious forced disconnects.
     */
  public void setAckSevereAlertThreshold(int newThreshold);
  /**
   * Returns true if the value of the ackSevereAlertThreshold attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isAckSevereAlertThresholdModifiable();
  /** The name of the "ackSevereAlertThreshold" property */
  public static final String ACK_SEVERE_ALERT_THRESHOLD_NAME = "ack-severe-alert-threshold";
  /**
   * The default ackSevereAlertThreshold.
   * <p> Actual value of this constant is <code>0</code> seconds, which
   * turns off shunning.
   */
  public static final int DEFAULT_ACK_SEVERE_ALERT_THRESHOLD = 0;
  /**
   * The minimum ackSevereAlertThreshold.
   * <p> Actual value of this constant is <code>0</code> second,
   * which turns off shunning.
   */
  public static final int MIN_ACK_SEVERE_ALERT_THRESHOLD = 0;
  /**
   * The maximum ackSevereAlertThreshold.
   * <p> Actual value of this constant is <code>MAX_INT</code> seconds.
   */
  public static final int MAX_ACK_SEVERE_ALERT_THRESHOLD = Integer.MAX_VALUE;


  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#archive-file-size-limit">"archive-file-size-limit"</a>
   * property
   */
  public int getArchiveFileSizeLimit();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#archive-file-size-limit">"archive-file-size-limit"</a>
   * property
   */
  public void setArchiveFileSizeLimit(int value);
  /**
   * Returns true if the value of the ArchiveFileSizeLimit attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isArchiveFileSizeLimitModifiable();
  /**
   * The default statistic archive file size limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int DEFAULT_ARCHIVE_FILE_SIZE_LIMIT = 0;
  /**
   * The minimum statistic archive file size limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int MIN_ARCHIVE_FILE_SIZE_LIMIT = 0;
  /**
   * The maximum statistic archive file size limit.
   * <p> Actual value of this constant is <code>1000000</code> megabytes.
   */
  public static final int MAX_ARCHIVE_FILE_SIZE_LIMIT = 1000000;

  /** The name of the "ArchiveFileSizeLimit" property */
  public static final String ARCHIVE_FILE_SIZE_LIMIT_NAME =
    "archive-file-size-limit";
  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#archive-disk-space-limit">"archive-disk-space-limit"</a>
   * property
   */
  public int getArchiveDiskSpaceLimit();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#archive-disk-space-limit">"archive-disk-space-limit"</a>
   * property
   */
  public void setArchiveDiskSpaceLimit(int value);
  /**
   * Returns true if the value of the ArchiveDiskSpaceLimit attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isArchiveDiskSpaceLimitModifiable();
  /**
   * The default archive disk space limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int DEFAULT_ARCHIVE_DISK_SPACE_LIMIT = 0;
  /**
   * The minimum archive disk space limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int MIN_ARCHIVE_DISK_SPACE_LIMIT = 0;
  /**
   * The maximum archive disk space limit.
   * <p> Actual value of this constant is <code>1000000</code> megabytes.
   */
  public static final int MAX_ARCHIVE_DISK_SPACE_LIMIT = 1000000;

  /** The name of the "ArchiveDiskSpaceLimit" property */
  public static final String ARCHIVE_DISK_SPACE_LIMIT_NAME =
    "archive-disk-space-limit";
  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#log-file-size-limit">"log-file-size-limit"</a>
   * property
   */
  public int getLogFileSizeLimit();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#log-file-size-limit">"log-file-size-limit"</a>
   * property
   */
  public void setLogFileSizeLimit(int value);
  /**
   * Returns true if the value of the LogFileSizeLimit attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isLogFileSizeLimitModifiable();
  /**
   * The default log file size limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int DEFAULT_LOG_FILE_SIZE_LIMIT = 0;
  /**
   * The minimum log file size limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int MIN_LOG_FILE_SIZE_LIMIT = 0;
  /**
   * The maximum log file size limit.
   * <p> Actual value of this constant is <code>1000000</code> megabytes.
   */
  public static final int MAX_LOG_FILE_SIZE_LIMIT = 1000000;

  /** The name of the "LogFileSizeLimit" property */
  public static final String LOG_FILE_SIZE_LIMIT_NAME =
    "log-file-size-limit";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#log-disk-space-limit">"log-disk-space-limit"</a>
   * property
   */
  public int getLogDiskSpaceLimit();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#log-disk-space-limit">"log-disk-space-limit"</a>
   * property
   */
  public void setLogDiskSpaceLimit(int value);
  /**
   * Returns true if the value of the LogDiskSpaceLimit attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isLogDiskSpaceLimitModifiable();
  /**
   * The default log disk space limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int DEFAULT_LOG_DISK_SPACE_LIMIT = 0;
  /**
   * The minimum log disk space limit.
   * <p> Actual value of this constant is <code>0</code> megabytes.
   */
  public static final int MIN_LOG_DISK_SPACE_LIMIT = 0;
  /**
   * The maximum log disk space limit.
   * <p> Actual value of this constant is <code>1000000</code> megabytes.
   */
  public static final int MAX_LOG_DISK_SPACE_LIMIT = 1000000;

  /** The name of the "LogDiskSpaceLimit" property */
  public static final String LOG_DISK_SPACE_LIMIT_NAME =
    "log-disk-space-limit";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#ssl-enabled">"ssl-enabled"</a>
   * property.
   */
  public boolean getSSLEnabled();

  /**
   * The default ssl-enabled state.
   * <p> Actual value of this constant is <code>false</code>.
   */
  public static final boolean DEFAULT_SSL_ENABLED = false;
    /** The name of the "SSLEnabled" property */
  public static final String SSL_ENABLED_NAME =
    "ssl-enabled";

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#ssl-enabled">"ssl-enabled"</a>
   * property.
   */
  public void setSSLEnabled( boolean enabled );

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#ssl-protocols">"ssl-protocols"</a>
   * property.
   */
   public String getSSLProtocols( );

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#ssl-protocols">"ssl-protocols"</a>
   * property.
   */
   public void setSSLProtocols( String protocols );

  /**
   * The default ssl-protocols value.
   * <p> Actual value of this constant is <code>any</code>.
   */
  public static final String DEFAULT_SSL_PROTOCOLS = "any";
  /** The name of the "SSLProtocols" property */
  public static final String SSL_PROTOCOLS_NAME =
    "ssl-protocols";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#ssl-ciphers">"ssl-ciphers"</a>
   * property.
   */
   public String getSSLCiphers( );

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#ssl-ciphers">"ssl-ciphers"</a>
   * property.
   */
   public void setSSLCiphers( String ciphers );

   /**
   * The default ssl-ciphers value.
   * <p> Actual value of this constant is <code>any</code>.
   */
  public static final String DEFAULT_SSL_CIPHERS = "any";
  /** The name of the "SSLCiphers" property */
  public static final String SSL_CIPHERS_NAME =
    "ssl-ciphers";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#ssl-require-authentication">"ssl-require-authentication"</a>
   * property.
   */
   public boolean getSSLRequireAuthentication( );

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#ssl-require-authentication">"ssl-require-authentication"</a>
   * property.
   */
   public void setSSLRequireAuthentication( boolean enabled );

   /**
   * The default ssl-require-authentication value.
   * <p> Actual value of this constant is <code>true</code>.
   */
  public static final boolean DEFAULT_SSL_REQUIRE_AUTHENTICATION = true;
  /** The name of the "SSLRequireAuthentication" property */
  public static final String SSL_REQUIRE_AUTHENTICATION_NAME =
    "ssl-require-authentication";


  /** The name of an internal property that specifies a {@link
   * com.gemstone.gemfire.i18n.LogWriterI18n} instance to log to.
   *  Set this property with put(), not with setProperty()
   *
   * @since 4.0 */
  public static final String LOG_WRITER_NAME = "log-writer";

  /** The name of an internal property that specifies a 
   *  a DistributionConfigImpl that the locator is passing
   *  in to a ds connect.
   *  Set this property with put(), not with setProperty()
   *
   * @since 7.0 */
  public static final String DS_CONFIG_NAME = "ds-config";
  
  /**
   * The name of an internal property that specifies whether
   * the distributed system is reconnecting after a forced-
   * disconnect.
   * @since 8.1
   */
  public static final String DS_RECONNECTING_NAME = "ds-reconnecting";
  
  /**
   * The name of an internal property that specifies the
   * quorum checker for the system that was forcibly disconnected.
   * This should be used if the DS_RECONNECTING_NAME property
   * is used.
   */
  public static final String DS_QUORUM_CHECKER_NAME = "ds-quorum-checker";
  
  /**
   * The name of an internal property that specifies a {@link
   * com.gemstone.gemfire.LogWriter} instance to log security messages to. Set
   * this property with put(), not with setProperty()
   *
   * @since 5.5
   */
  public static final String SECURITY_LOG_WRITER_NAME = "security-log-writer";

  /** The name of an internal property that specifies a
   * FileOutputStream associated with the internal property
   * LOG_WRITER_NAME.  If this property is set, the
   * FileOutputStream will be closed when the distributed
   * system disconnects.  Set this property with put(), not
   * with setProperty()
   * @since 5.0
   */
  public static final String LOG_OUTPUTSTREAM_NAME = "log-output-stream";

  /**
   * The name of an internal property that specifies a FileOutputStream
   * associated with the internal property SECURITY_LOG_WRITER_NAME. If this
   * property is set, the FileOutputStream will be closed when the distributed
   * system disconnects. Set this property with put(), not with setProperty()
   *
   * @since 5.5
   */
  public static final String SECURITY_LOG_OUTPUTSTREAM_NAME = "security-log-output-stream";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#socket-lease-time">"socket-lease-time"</a>
   * property
   */
  public int getSocketLeaseTime();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#socket-lease-time">"socket-lease-time"</a>
   * property
   */
  public void setSocketLeaseTime(int value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isSocketLeaseTimeModifiable();

  /** The name of the "socketLeaseTime" property */
  public static final String SOCKET_LEASE_TIME_NAME = "socket-lease-time";

  /** The default value of the "socketLeaseTime" property */
  public static final int DEFAULT_SOCKET_LEASE_TIME = 60000;
  /**
   * The minimum socketLeaseTime.
   * <p> Actual value of this constant is <code>0</code>.
   */
  public static final int MIN_SOCKET_LEASE_TIME = 0;
  /**
   * The maximum socketLeaseTime.
   * <p> Actual value of this constant is <code>600000</code>.
   */
  public static final int MAX_SOCKET_LEASE_TIME = 600000;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#socket-buffer-size">"socket-buffer-size"</a>
   * property
   */
  public int getSocketBufferSize();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#socket-buffer-size">"socket-buffer-size"</a>
   * property
   */
  public void setSocketBufferSize(int value);
  /**
   * Returns true if the value of the
   * attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isSocketBufferSizeModifiable();

  /** The name of the "socketBufferSize" property */
  public static final String SOCKET_BUFFER_SIZE_NAME = "socket-buffer-size";

  /** The default value of the "socketBufferSize" property */
  public static final int DEFAULT_SOCKET_BUFFER_SIZE = 32768;
  /**
   * The minimum socketBufferSize.
   * <p> Actual value of this constant is <code>1024</code>.
   */
  public static final int MIN_SOCKET_BUFFER_SIZE = 1024;
  /**
   * The maximum socketBufferSize.
   * <p> Actual value of this constant is <code>20000000</code>.
   */
  public static final int MAX_SOCKET_BUFFER_SIZE = Connection.MAX_MSG_SIZE;

  public static final boolean VALIDATE = Boolean.getBoolean("gemfire.validateMessageSize");
  public static final int VALIDATE_CEILING = Integer.getInteger("gemfire.validateMessageSizeCeiling",  8 * 1024 * 1024).intValue();

  /**
   * Get the value of the
   * <a href="../DistributedSystem.html#mcast-send-buffer-size">"mcast-send-buffer-size"</a>
   * property
   */
  public int getMcastSendBufferSize();

  /**
   * Set the value of the
   * <a href="../DistributedSystem.html#mcast-send-buffer-size">"mcast-send-buffer-size"</a>
   * property
   */
  public void setMcastSendBufferSize(int value);

  /**
   * Returns true if the corresponding property is currently modifiable.
   * Some attributes can't be modified while a DistributedSystem is running.
   */
  public boolean isMcastSendBufferSizeModifiable();

  /**
   * The name of the corresponding property
   */
  public static final String MCAST_SEND_BUFFER_SIZE_NAME = "mcast-send-buffer-size";

  /**
   * The default value of the corresponding property
   */
  public static final int DEFAULT_MCAST_SEND_BUFFER_SIZE = 65535;


  /**
   * The minimum size of the buffer, in bytes.
   * <p> Actual value of this constant is <code>2048</code>.
   */
  public static final int MIN_MCAST_SEND_BUFFER_SIZE = 2048;

  /**
   * Get the value of the
   * <a href="../DistributedSystem.html#mcast-recv-buffer-size">"mcast-recv-buffer-size"</a>
   * property
   */
  public int getMcastRecvBufferSize();

  /**
   * Set the value of the
   * <a href="../DistributedSystem.html#mcast-recv-buffer-size">"mcast-recv-buffer-size"</a>
   * property
   */
  public void setMcastRecvBufferSize(int value);

  /**
   * Returns true if the corresponding property is currently modifiable.
   * Some attributes can't be modified while a DistributedSystem is running.
   */
  public boolean isMcastRecvBufferSizeModifiable();

  /**
   * The name of the corresponding property
   */
  public static final String MCAST_RECV_BUFFER_SIZE_NAME = "mcast-recv-buffer-size";

  /**
   * The default value of the corresponding property
   */
  public static final int DEFAULT_MCAST_RECV_BUFFER_SIZE = 1048576;

  /**
   * The minimum size of the buffer, in bytes.
   * <p> Actual value of this constant is <code>2048</code>.
   */
  public static final int MIN_MCAST_RECV_BUFFER_SIZE = 2048;




  /**
   * Get the value of the
   * <a href="../DistributedSystem.html#mcast-flow-control">"mcast-flow-control"</a>
   * property.
   */
  public FlowControlParams getMcastFlowControl();

  /**
   * Set the value of the
   * <a href="../DistributedSystem.html#mcast-flow-control">"mcast-flow-control"</a>
   * property
   */
  public void setMcastFlowControl(FlowControlParams values);

  /**
   * Returns true if the corresponding property is currently modifiable.
   * Some attributes can't be modified while a DistributedSystem is running.
   */
  public boolean isMcastFlowControlModifiable();

  /**
   * The name of the corresponding property
   */
  public static final String MCAST_FLOW_CONTROL_NAME = "mcast-flow-control";

  /**
   * The default value of the corresponding property
   */
  public static final FlowControlParams DEFAULT_MCAST_FLOW_CONTROL
    = new FlowControlParams(1048576, (float)0.25, 5000);

  /**
   * The minimum byteAllowance for the mcast-flow-control setting of
   * <code>100000</code>.
   */
  public static final int MIN_FC_BYTE_ALLOWANCE = 10000;

  /**
   * The minimum rechargeThreshold for the mcast-flow-control setting of
   * <code>0.1</code>
   */
  public static final float MIN_FC_RECHARGE_THRESHOLD = (float)0.1;

  /**
   * The maximum rechargeThreshold for the mcast-flow-control setting of
   * <code>0.5</code>
   */
  public static final float MAX_FC_RECHARGE_THRESHOLD = (float)0.5;

  /**
   * The minimum rechargeBlockMs for the mcast-flow-control setting of
   * <code>500</code>
   */
  public static final int MIN_FC_RECHARGE_BLOCK_MS = 500;

  /**
   * The maximum rechargeBlockMs for the mcast-flow-control setting of
   * <code>60000</code>
   */
  public static final int MAX_FC_RECHARGE_BLOCK_MS = 60000;

  /**
   * Get the value of the
   * <a href="../DistributedSystem.html#udp-fragment-size">"udp-fragment-size"</a>
   * property.
   */
  public int getUdpFragmentSize();

  /**
   * Set the value of the
   * <a href="../DistributedSystem.html#udp-fragment-size">"udp-fragment-size"</a>
   * property
   */
  public void setUdpFragmentSize(int value);

  /**
   * Returns true if the corresponding property is currently modifiable.
   * Some attributes can't be modified while a DistributedSystem is running.
   */
  public boolean isUdpFragmentSizeModifiable();

  /**
   * The name of the corresponding property
   */
  public static final String UDP_FRAGMENT_SIZE_NAME = "udp-fragment-size";

  /**
   * The default value of the corresponding property
   */
  public static final int DEFAULT_UDP_FRAGMENT_SIZE = 60000;

  /** The minimum allowed udp-fragment-size setting of 1000
  */
  public static final int MIN_UDP_FRAGMENT_SIZE = 1000;

  /** The maximum allowed udp-fragment-size setting of 60000
  */
  public static final int MAX_UDP_FRAGMENT_SIZE = 60000;






  /**
   * Get the value of the
   * <a href="../DistributedSystem.html#udp-send-buffer-size">"udp-send-buffer-size"</a>
   * property
   */
  public int getUdpSendBufferSize();

  /**
   * Set the value of the
   * <a href="../DistributedSystem.html#udp-send-buffer-size">"udp-send-buffer-size"</a>
   * property
   */
  public void setUdpSendBufferSize(int value);

  /**
   * Returns true if the corresponding property is currently modifiable.
   * Some attributes can't be modified while a DistributedSystem is running.
   */
  public boolean isUdpSendBufferSizeModifiable();

  /**
   * The name of the corresponding property
   */
  public static final String UDP_SEND_BUFFER_SIZE_NAME = "udp-send-buffer-size";

  /**
   * The default value of the corresponding property
   */
  public static final int DEFAULT_UDP_SEND_BUFFER_SIZE = 65535;

  /**
   * The minimum size of the buffer, in bytes.
   * <p> Actual value of this constant is <code>2048</code>.
   */
  public static final int MIN_UDP_SEND_BUFFER_SIZE = 2048;



  /**
   * Get the value of the
   * <a href="../DistributedSystem.html#udp-recv-buffer-size">"udp-recv-buffer-size"</a>
   * property
   */
  public int getUdpRecvBufferSize();

  /**
   * Set the value of the
   * <a href="../DistributedSystem.html#udp-recv-buffer-size">"udp-recv-buffer-size"</a>
   * property
   */
  public void setUdpRecvBufferSize(int value);

  /**
   * Returns true if the corresponding property is currently modifiable.
   * Some attributes can't be modified while a DistributedSystem is running.
   */
  public boolean isUdpRecvBufferSizeModifiable();

  /**
   * The name of the corresponding property
   */
  public static final String UDP_RECV_BUFFER_SIZE_NAME = "udp-recv-buffer-size";

  /**
   * The default value of the unicast receive buffer size property
   */
  public static final int DEFAULT_UDP_RECV_BUFFER_SIZE = 1048576;

  /**
   * The default size of the unicast receive buffer property if tcp/ip sockets are
   * enabled and multicast is disabled
   */
  public static final int DEFAULT_UDP_RECV_BUFFER_SIZE_REDUCED = 65535;

  /**
   * The minimum size of the buffer, in bytes.
   * <p> Actual value of this constant is <code>2048</code>.
   */
  public static final int MIN_UDP_RECV_BUFFER_SIZE = 2048;


  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#disable-tcp">"disable-tcp"</a>
   * property
   */
  public boolean getDisableTcp();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#disable-tcp">"disable-tcp"</a>
   * property.
   */
  public void setDisableTcp(boolean newValue);
  /**
   * Returns true if the value of the DISABLE_TCP attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isDisableTcpModifiable();
  /** The name of the corresponding property */
  public static final String DISABLE_TCP_NAME = "disable-tcp";

  /** The default value of the corresponding property */
  public static final boolean DEFAULT_DISABLE_TCP = false;


  /**
   * Turns on timing statistics for the distributed system
   */
  public void setEnableTimeStatistics(boolean newValue);

  /**
   * Returns the value of <a
   * href="../DistributedSystem.html#enable-time-statistics">enable-time-statistics</a>
   * property
   */
  public boolean getEnableTimeStatistics();

  /** the name of the corresponding property */
  public static final String ENABLE_TIME_STATISTICS_NAME = "enable-time-statistics";

  /** The default value of the corresponding property */
  public static final boolean DEFAULT_ENABLE_TIME_STATISTICS = false;


  /** Turns on network partition detection */
  public void setEnableNetworkPartitionDetection(boolean newValue);
  /**
   * Returns the value of the enable-network-partition-detection property
   */
  public boolean getEnableNetworkPartitionDetection();
  /** the name of the corresponding property */
  public static final String ENABLE_NETWORK_PARTITION_DETECTION_NAME =
    "enable-network-partition-detection";
  public static final boolean DEFAULT_ENABLE_NETWORK_PARTITION_DETECTION = false;

  /**
   * Get the value of the
   * <a href="../DistributedSystem.html#member-timeout">"member-timeout"</a>
   * property
   */
  public int getMemberTimeout();

  /**
   * Set the value of the
   * <a href="../DistributedSystem.html#member-timeout">"member-timeout"</a>
   * property
   */
  public void setMemberTimeout(int value);

  /**
   * Returns true if the corresponding property is currently modifiable.
   * Some attributes can't be modified while a DistributedSystem is running.
   */
  public boolean isMemberTimeoutModifiable();

  /**
   * The name of the corresponding property
   */
  public static final String MEMBER_TIMEOUT_NAME = "member-timeout";

  /**
   * The default value of the corresponding property
   */
  public static final int DEFAULT_MEMBER_TIMEOUT = 5000;

  /** The minimum member-timeout setting of 1000 milliseconds */
  public static final int MIN_MEMBER_TIMEOUT = 10;

  /**The maximum member-timeout setting of 600000 millieseconds */
  public static final int MAX_MEMBER_TIMEOUT = 600000;


  public static final String MEMBERSHIP_PORT_RANGE_NAME = "membership-port-range";
  
  public static final int[] DEFAULT_MEMBERSHIP_PORT_RANGE = new int[]{1024,65535};
  
  public int[] getMembershipPortRange();
  
  public void setMembershipPortRange(int[] range);
  
  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#conserve-sockets">"conserve-sockets"</a>
   * property
   */
  public boolean getConserveSockets();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#conserve-sockets">"conserve-sockets"</a>
   * property.
   */
  public void setConserveSockets(boolean newValue);
  /**
   * Returns true if the value of the ConserveSockets attribute can currently
   * be modified.
   * Some attributes can not be modified while the system is running.
   */
  public boolean isConserveSocketsModifiable();
  /** The name of the "conserveSockets" property */
  public static final String CONSERVE_SOCKETS_NAME = "conserve-sockets";

  /** The default value of the "conserveSockets" property */
  public static final boolean DEFAULT_CONSERVE_SOCKETS = false;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#roles">"roles"</a>
   * property
   */
  public String getRoles();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#roles">"roles"</a>
   * property.
   */
  public void setRoles(String roles);
  /** The name of the "roles" property */
  public static final String ROLES_NAME = "roles";
  /** The default value of the "roles" property */
  public static final String DEFAULT_ROLES = "";


  /**
   * The name of the "max wait time for reconnect" property */
  public static final String MAX_WAIT_TIME_FOR_RECONNECT_NAME = "max-wait-time-reconnect";

  /**
   * Default value for MAX_WAIT_TIME_FOR_RECONNECT, 60,000 milliseconds.
   */
  public static final int DEFAULT_MAX_WAIT_TIME_FOR_RECONNECT = 60000;

  /**
   * Sets the max wait timeout, in milliseconds, for reconnect.
   * */
  public void setMaxWaitTimeForReconnect( int timeOut);

  /**
   * Returns the max wait timeout, in milliseconds, for reconnect.
   * */
  public int getMaxWaitTimeForReconnect();

  /**
   * The name of the "max number of tries for reconnect" property.
   * */
  public static final String MAX_NUM_RECONNECT_TRIES = "max-num-reconnect-tries";

  /**
   * Default value for MAX_NUM_RECONNECT_TRIES.
   */
  public static final int DEFAULT_MAX_NUM_RECONNECT_TRIES = 3;

  /**
   * Sets the max number of tries for reconnect.
   * */
  public void setMaxNumReconnectTries(int tries);

  /**
   * Returns the value for max number of tries for reconnect.
   * */
  public int getMaxNumReconnectTries();

  // ------------------- Asynchronous Messaging Properties -------------------

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#async-distribution-timeout">
   * "async-distribution-timeout"</a> property.
   */
  public int getAsyncDistributionTimeout();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#async-distribution-timeout">
   * "async-distribution-timeout"</a> property.
   */
  public void setAsyncDistributionTimeout(int newValue);
  /**
   * Returns true if the value of the asyncDistributionTimeout attribute can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isAsyncDistributionTimeoutModifiable();

  /** The name of the "asyncDistributionTimeout" property */
  public static final String ASYNC_DISTRIBUTION_TIMEOUT_NAME = "async-distribution-timeout";
  /** The default value of "asyncDistributionTimeout" is <code>0</code>. */
  public static final int DEFAULT_ASYNC_DISTRIBUTION_TIMEOUT = 0;
  /** The minimum value of "asyncDistributionTimeout" is <code>0</code>. */
  public static final int MIN_ASYNC_DISTRIBUTION_TIMEOUT = 0;
  /** The maximum value of "asyncDistributionTimeout" is <code>60000</code>. */
  public static final int MAX_ASYNC_DISTRIBUTION_TIMEOUT = 60000;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#async-queue-timeout">
   * "async-queue-timeout"</a> property.
   */
  public int getAsyncQueueTimeout();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#async-queue-timeout">
   * "async-queue-timeout"</a> property.
   */
  public void setAsyncQueueTimeout(int newValue);
  /**
   * Returns true if the value of the asyncQueueTimeout attribute can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isAsyncQueueTimeoutModifiable();

  /** The name of the "asyncQueueTimeout" property */
  public static final String ASYNC_QUEUE_TIMEOUT_NAME = "async-queue-timeout";
  /** The default value of "asyncQueueTimeout" is <code>60000</code>. */
  public static final int DEFAULT_ASYNC_QUEUE_TIMEOUT = 60000;
  /** The minimum value of "asyncQueueTimeout" is <code>0</code>. */
  public static final int MIN_ASYNC_QUEUE_TIMEOUT = 0;
  /** The maximum value of "asyncQueueTimeout" is <code>86400000</code>. */
  public static final int MAX_ASYNC_QUEUE_TIMEOUT = 86400000;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#async-max-queue-size">
   * "async-max-queue-size"</a> property.
   */
  public int getAsyncMaxQueueSize();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#async-max-queue-size">
   * "async-max-queue-size"</a> property.
   */
  public void setAsyncMaxQueueSize(int newValue);
  /**
   * Returns true if the value of the asyncMaxQueueSize attribute can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isAsyncMaxQueueSizeModifiable();

  /** The name of the "asyncMaxQueueSize" property */
  public static final String ASYNC_MAX_QUEUE_SIZE_NAME = "async-max-queue-size";
  /** The default value of "asyncMaxQueueSize" is <code>8</code>. */
  public static final int DEFAULT_ASYNC_MAX_QUEUE_SIZE = 8;
  /** The minimum value of "asyncMaxQueueSize" is <code>0</code>. */
  public static final int MIN_ASYNC_MAX_QUEUE_SIZE = 0;
  /** The maximum value of "asyncMaxQueueSize" is <code>1024</code>. */
  public static final int MAX_ASYNC_MAX_QUEUE_SIZE = 1024;

  /** @since 5.7 */
  public static final String CLIENT_CONFLATION_PROP_NAME = "conflate-events";
  /** @since 5.7 */
  public static final String CLIENT_CONFLATION_PROP_VALUE_DEFAULT = "server";
  /** @since 5.7 */
  public static final String CLIENT_CONFLATION_PROP_VALUE_ON = "true";
  /** @since 5.7 */
  public static final String CLIENT_CONFLATION_PROP_VALUE_OFF = "false";
  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#conflate-events">"conflate-events"</a>
   * property.
   * @since 5.7
   */
  public String getClientConflation();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#conflate-events">"conflate-events"</a>
   * property.
   * @since 5.7
   */
  public void setClientConflation(String clientConflation);
  /** @since 5.7 */
  public boolean isClientConflationModifiable();
  // -------------------------------------------------------------------------
  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#durable-client-id">"durable-client-id"</a>
   * property.
   */
  public String getDurableClientId();

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#durable-client-id">"durable-client-id"</a>
   * property.
   */
  public void setDurableClientId(String durableClientId);

  /**
   * Returns true if the value of the durableClientId attribute can currently
   * be modified. Some attributes can not be modified while the system is
   * running.
   */
  public boolean isDurableClientIdModifiable();

  /** The name of the "durableClientId" property */
  public static final String DURABLE_CLIENT_ID_NAME = "durable-client-id";

  /**
   * The default durable client id.
   * <p> Actual value of this constant is <code>""</code>.
   */
  public static final String DEFAULT_DURABLE_CLIENT_ID = "";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#durable-client-timeout">"durable-client-timeout"</a>
   * property.
   */
  public int getDurableClientTimeout();

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#durable-client-timeout">"durable-client-timeout"</a>
   * property.
   */
  public void setDurableClientTimeout(int durableClientTimeout);

  /**
   * Returns true if the value of the durableClientTimeout attribute can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isDurableClientTimeoutModifiable();

  /** The name of the "durableClientTimeout" property */
  public static final String DURABLE_CLIENT_TIMEOUT_NAME = "durable-client-timeout";

  /**
   * The default durable client timeout in seconds.
   * <p> Actual value of this constant is <code>"300"</code>.
   */
  public static final int DEFAULT_DURABLE_CLIENT_TIMEOUT = 300;

  /**
   * Returns user module name for client authentication initializer in <a
   * href="../DistributedSystem.html#security-client-auth-init">"security-client-auth-init"</a>
   */
  public String getSecurityClientAuthInit();

  /**
   * Sets the user module name in <a
   * href="../DistributedSystem.html#security-client-auth-init">"security-client-auth-init"</a>
   * property.
   */
  public void setSecurityClientAuthInit(String attValue);

  /**
   * Returns true if the value of the authentication initializer method name can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isSecurityClientAuthInitModifiable();

  /** The name of user defined method name for "security-client-auth-init" property*/
  public static final String SECURITY_CLIENT_AUTH_INIT_NAME = "security-client-auth-init";

  /**
   * The default client authentication initializer method name.
   * <p> Actual value of this is in format <code>"jar file:module name"</code>.
   */
  public static final String DEFAULT_SECURITY_CLIENT_AUTH_INIT = "";

  /**
   * Returns user module name authenticating client credentials in <a
   * href="../DistributedSystem.html#security-client-authenticator">"security-client-authenticator"</a>
   */
  public String getSecurityClientAuthenticator();

  /**
   * Sets the user defined method name in <a
   * href="../DistributedSystem.html#security-client-authenticator">"security-client-authenticator"</a>
   * property.
   */
  public void setSecurityClientAuthenticator(String attValue);

  /**
   * Returns true if the value of the authenticating method name can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isSecurityClientAuthenticatorModifiable();

  /** The name of factory method for "security-client-authenticator" property */
  public static final String SECURITY_CLIENT_AUTHENTICATOR_NAME = "security-client-authenticator";

  /**
   * The default client authentication method name.
   * <p> Actual value of this is fully qualified <code>"method name"</code>.
   */
  public static final String DEFAULT_SECURITY_CLIENT_AUTHENTICATOR = "";

  /**
   * Returns name of algorithm to use for Diffie-Hellman key exchange <a
   * href="../DistributedSystem.html#security-client-dhalgo">"security-client-dhalgo"</a>
   */
  public String getSecurityClientDHAlgo();

  /**
   * Set the name of algorithm to use for Diffie-Hellman key exchange <a
   * href="../DistributedSystem.html#security-client-dhalgo">"security-client-dhalgo"</a>
   * property.
   */
  public void setSecurityClientDHAlgo(String attValue);

  /**
   * Returns true if the value of the Diffie-Hellman algorithm can currently be
   * modified. Some attributes can not be modified while the system is running.
   */
  public boolean isSecurityClientDHAlgoModifiable();

  /**
   * The name of the Diffie-Hellman symmetric algorithm "security-client-dhalgo"
   * property.
   */
  public static final String SECURITY_CLIENT_DHALGO_NAME = "security-client-dhalgo";

  /**
   * The default Diffie-Hellman symmetric algorithm name.
   * <p>
   * Actual value of this is one of the available symmetric algorithm names in
   * JDK like "DES", "DESede", "AES", "Blowfish".
   */
  public static final String DEFAULT_SECURITY_CLIENT_DHALGO = "";

  /**
   * Returns user defined method name for peer authentication initializer in <a
   * href="../DistributedSystem.html#security-peer-auth-init">"security-peer-auth-init"</a>
   */
  public String getSecurityPeerAuthInit();

  /**
   * Sets the user module name in <a
   * href="../DistributedSystem.html#security-peer-auth-init">"security-peer-auth-init"</a>
   * property.
   */
  public void setSecurityPeerAuthInit(String attValue);

  /**
   * Returns true if the value of the AuthInit method name can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isSecurityPeerAuthInitModifiable();

  /** The name of user module for "security-peer-auth-init" property*/
  public static final String SECURITY_PEER_AUTH_INIT_NAME = "security-peer-auth-init";

  /**
   * The default client authentication method name.
   * <p> Actual value of this is fully qualified <code>"method name"</code>.
   */
  public static final String DEFAULT_SECURITY_PEER_AUTH_INIT = "";

  /**
   * Returns user defined method name authenticating peer's credentials in <a
   * href="../DistributedSystem.html#security-peer-authenticator">"security-peer-authenticator"</a>
   */
  public String getSecurityPeerAuthenticator();

  /**
   * Sets the user module name in <a
   * href="../DistributedSystem.html#security-peer-authenticator">"security-peer-authenticator"</a>
   * property.
   */
  public void setSecurityPeerAuthenticator(String attValue);

  /**
   * Returns true if the value of the security module name can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isSecurityPeerAuthenticatorModifiable();

  /** The name of user defined method for "security-peer-authenticator" property*/
  public static final String SECURITY_PEER_AUTHENTICATOR_NAME = "security-peer-authenticator";

  /**
   * The default client authentication method.
   * <p> Actual value of this is fully qualified <code>"method name"</code>.
   */
  public static final String DEFAULT_SECURITY_PEER_AUTHENTICATOR = "";

  /**
   * Returns user module name authorizing client credentials in <a
   * href="../DistributedSystem.html#security-client-accessor">"security-client-accessor"</a>
   */
  public String getSecurityClientAccessor();

  /**
   * Sets the user defined method name in <a
   * href="../DistributedSystem.html#security-client-accessor">"security-client-accessor"</a>
   * property.
   */
  public void setSecurityClientAccessor(String attValue);

  /** The name of the factory method for "security-client-accessor" property */
  public static final String SECURITY_CLIENT_ACCESSOR_NAME = "security-client-accessor";

  /**
   * The default client authorization module factory method name.
   * <p> Actual value of this is fully qualified <code>"method name"</code>.
   */
  public static final String DEFAULT_SECURITY_CLIENT_ACCESSOR = "";

  /**
   * Returns user module name authorizing client credentials in <a
   * href="../DistributedSystem.html#security-client-accessor-pp">"security-client-accessor-pp"</a>
   */
  public String getSecurityClientAccessorPP();

  /**
   * Sets the user defined method name in <a
   * href="../DistributedSystem.html#security-client-accessor-pp">"security-client-accessor-pp"</a>
   * property.
   */
  public void setSecurityClientAccessorPP(String attValue);

  /** The name of the factory method for "security-client-accessor-pp" property */
  public static final String SECURITY_CLIENT_ACCESSOR_PP_NAME = "security-client-accessor-pp";

  /**
   * The default client post-operation authorization module factory method name.
   * <p> Actual value of this is fully qualified <code>"method name"</code>.
   */
  public static final String DEFAULT_SECURITY_CLIENT_ACCESSOR_PP = "";

  /**
   * Get the current log-level for security logging.
   *
   * @return the current security log-level
   */
  public int getSecurityLogLevel();

  /**
   * Set the log-level for security logging.
   *
   * @param level
   *                the new security log-level
   */
  public void setSecurityLogLevel(int level);

  /**
   * Returns true if the value of the logLevel attribute can currently be
   * modified. Some attributes can not be modified while the system is running.
   */
  public boolean isSecurityLogLevelModifiable();

  /**
   * The name of "security-log-level" property that sets the log-level for
   * security logger obtained using
   * {@link DistributedSystem#getSecurityLogWriter()}
   */
  public static final String SECURITY_LOG_LEVEL_NAME = "security-log-level";

  /**
   * Returns the value of the "security-log-file" property
   *
   * @return <code>null</code> if logging information goes to standard out
   */
  public File getSecurityLogFile();

  /**
   * Sets the system's security log file containing security related messages.
   * <p>
   * Non-absolute log files are relative to the system directory.
   * <p>
   * The security log file can not be changed while the system is running.
   *
   * @throws IllegalArgumentException
   *                 if the specified value is not acceptable.
   * @throws com.gemstone.gemfire.UnmodifiableException
   *                 if this attribute can not be modified.
   * @throws com.gemstone.gemfire.GemFireIOException
   *                 if the set failure is caused by an error when writing to
   *                 the system's configuration file.
   */
  public void setSecurityLogFile(File value);

  /**
   * Returns true if the value of the <code>security-log-file</code> attribute
   * can currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isSecurityLogFileModifiable();

  /**
   * The name of the "security-log-file" property. This property is the path of
   * the file where security related messages are logged.
   */
  public static final String SECURITY_LOG_FILE_NAME = "security-log-file";

  /**
   * The default security log file.
   * <p> *
   * <p>
   * Actual value of this constant is <code>""</code> which directs security
   * log messages to the same place as the system log file.
   */
  public static final File DEFAULT_SECURITY_LOG_FILE = new File("");

  /**
   * Get timeout for peer membership check when security is enabled.
   *
   * @return Timeout in milliseconds.
   */
  public int getSecurityPeerMembershipTimeout();

  /**
   * Set timeout for peer membership check when security is enabled. The timeout must be less
   * than peer handshake timeout.
   * @param attValue
   */
  public void setSecurityPeerMembershipTimeout(int attValue);

  /**
   * Returns true if the value of the peer membership timeout attribute can currently be modified.
   * Some attributes can not be modified while the system is running.
   * @return true if timeout is modifiable.
   */
  public boolean isSecurityPeerMembershipTimeoutModifiable();

  /** The name of the peer membership check timeout property */
  public static final String SECURITY_PEER_VERIFYMEMBER_TIMEOUT_NAME = "security-peer-verifymember-timeout";

  /**
   * The default peer membership check timeout is 1 second.
   */
  public static final int DEFAULT_SECURITY_PEER_VERIFYMEMBER_TIMEOUT = 1000;

  /**
   * Max membership timeout must be less than max peer handshake timeout. Currently this is set to
   * default handshake timeout of 60 seconds.
   */
  public static final int MAX_SECURITY_PEER_VERIFYMEMBER_TIMEOUT = 60000;

  /**
   * Returns all properties starting with <a
   * href="../DistributedSystem.html#security-">"security-"</a>.
   */
  public Properties getSecurityProps();

  /**
   * Returns the value of security property <a
   * href="../DistributedSystem.html#security-">"security-"</a>
   * for an exact attribute name match.
   */
  public String getSecurity(String attName);

  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#security-">"security-*"</a>
   * property.
   */
  public void setSecurity(String attName, String attValue);

  /**
   * Returns true if the value of the security attributes can
   * currently be modified. Some attributes can not be modified while the
   * system is running.
   */
  public boolean isSecurityModifiable();

  /** For the "security-" prefixed properties */
  public static final String SECURITY_PREFIX_NAME = "security-";

  /** The prefix used for Gemfire properties set through java system properties */
  public static final String GEMFIRE_PREFIX = "gemfire.";

  /** The prefix used for SnappyData properties set through java system properties */
  public static final String SNAPPY_PREFIX = SystemProperties.SNAPPY_PREFIX;

  /** For the "custom-" prefixed properties */
  public static final String USERDEFINED_PREFIX_NAME = "custom-";
  
  /** This will be used internally */
  public static final String GFXD_USERDEFINED_PREFIX_NAME = "gemfirexd.custom.";
  
  /** For ssl keystore and trust store properties */
  public static final String SSL_SYSTEM_PROPS_NAME = "javax.net.ssl";

  /** Suffix for ssl keystore and trust store properties for JMX*/
  public static final String JMX_SSL_PROPS_SUFFIX = "-jmx";

  /** For security properties starting with sysprop in gfsecurity.properties file */
  public static final String SYS_PROP_NAME = "sysprop-";
   /**
    * The property decides whether to remove unresponsive client from the server.
    */
   public static final String REMOVE_UNRESPONSIVE_CLIENT_PROP_NAME = "remove-unresponsive-client";

   /**
    * The default value of remove unresponsive client is false.
    */
   public static final boolean DEFAULT_REMOVE_UNRESPONSIVE_CLIENT = false;
   /**
    * Returns the value of the <a
    * href="../DistributedSystem.html#remove-unresponsive-client">"remove-unresponsive-client"</a>
    * property.
    * @since 6.0
    */
   public boolean getRemoveUnresponsiveClient();
   /**
    * Sets the value of the <a
    * href="../DistributedSystem.html#remove-unresponsive-client">"remove-unresponsive-client"</a>
    * property.
    * @since 6.0
    */
   public void setRemoveUnresponsiveClient(boolean value);
   /** @since 6.0 */
   public boolean isRemoveUnresponsiveClientModifiable();

   /** @since 6.3 */
   public static final String DELTA_PROPAGATION_PROP_NAME = "delta-propagation";

   public static final boolean DEFAULT_DELTA_PROPAGATION = true;
   /**
    * Returns the value of the <a
    * href="../DistributedSystem.html#delta-propagation">"delta-propagation"</a>
    * property.
    * @since 6.3
    */
   public boolean getDeltaPropagation();

   /**
    * Sets the value of the <a
    * href="../DistributedSystem.html#delta-propagation">"delta-propagation"</a>
    * property.
    * @since 6.3
    */
   public void setDeltaPropagation(boolean value);

   /** @since 6.3 */
   public boolean isDeltaPropagationModifiable();
   
   /**
    * @since 6.6
    */
   public static final String DISTRIBUTED_SYSTEM_ID_NAME = "distributed-system-id";
   public static final int DEFAULT_DISTRIBUTED_SYSTEM_ID = -1;

   public static final String REDUNDANCY_ZONE_NAME = "redundancy-zone";
   public static final String DEFAULT_REDUNDANCY_ZONE = null;
   
   /**
    * @since 6.6
    */
   public void setDistributedSystemId(int distributedSystemId);

   public void setRedundancyZone(String redundancyZone);
   
   /**
    * @since 6.6
    */
   public int getDistributedSystemId();
   
   public String getRedundancyZone();
   
   /**
    * @since 6.6.2
    */
   public void setSSLProperty(String attName, String attValue);
   
   /**
    * @since 6.6.2
    */
   public Properties getSSLProperties();

   /**
    * @since 7.5
    */
   public Properties getJmxSSLProperties();
   /**
    * @since 6.6
    */
   public static final String ENFORCE_UNIQUE_HOST_NAME = "enforce-unique-host";
   /** Using the system property to set the default here to retain backwards compatibility
    * with customers that are already using this system property.
    */
   public static boolean DEFAULT_ENFORCE_UNIQUE_HOST = Boolean.getBoolean("gemfire.EnforceUniqueHostStorageAllocation");
   
   public void setEnforceUniqueHost(boolean enforceUniqueHost);
   
   public boolean getEnforceUniqueHost();

   public Properties getUserDefinedProps();


   /**
    * Returns the value of the <a
    * href="../DistributedSystem.html#name">"groups"</a> property
    * <p> The default value is: {@link #DEFAULT_GROUPS}.
    * @return the value of the property
    * @since 7.0
    */
   public String getGroups();
   /**
    * Sets the groups gemfire property.
    * <p> The groups can not be changed while the system is running.
    * @throws IllegalArgumentException if the specified value is not acceptable.
    * @throws com.gemstone.gemfire.UnmodifiableException if this attribute can not be modified.
    * @throws com.gemstone.gemfire.GemFireIOException if the set failure is caused by an error
    *   when writing to the system's configuration file.
    * @since 7.0
    */
   public void setGroups(String value);
   /**
    * Returns true if the value of the <code>groups</code> attribute can currently
    * be modified.
    * Some attributes can not be modified while the system is running.
    * @since 7.0
    */
   public boolean isGroupsModifiable();
   /** The name of the "groups" property 
    * @since 7.0
    */
   public static final String GROUPS_NAME = "groups";
   /**
    * The default groups.
    * <p> Actual value of this constant is <code>""</code>.
    * @since 7.0
    */
   public static final String DEFAULT_GROUPS = "";

   /** Any cleanup required before closing the distributed system */
  public void close();

  public void setRemoteLocators(String locators);
  public String getRemoteLocators();
  /** The name of the "remote-locators" property */
  public static final String REMOTE_LOCATORS_NAME = "remote-locators";
  /** The default value of the "remote-locators" property */
  public static final String DEFAULT_REMOTE_LOCATORS = "";

  public boolean getJmxManager();
  public void setJmxManager(boolean value);
  public boolean isJmxManagerModifiable();
  public static String JMX_MANAGER_NAME = "jmx-manager";
  public static boolean DEFAULT_JMX_MANAGER = false;

  
  public boolean getJmxManagerStart();
  public void setJmxManagerStart(boolean value);
  public boolean isJmxManagerStartModifiable();
  public static String JMX_MANAGER_START_NAME = "jmx-manager-start";
  public static boolean DEFAULT_JMX_MANAGER_START = false;
  
  public int getJmxManagerPort();
  public void setJmxManagerPort(int value);
  public boolean isJmxManagerPortModifiable();
  public static String JMX_MANAGER_PORT_NAME = "jmx-manager-port";
  public static int DEFAULT_JMX_MANAGER_PORT = 1099;
  
  public boolean getJmxManagerSSL();
  public void setJmxManagerSSL(boolean value);
  public boolean isJmxManagerSSLModifiable();
  public static String JMX_MANAGER_SSL_NAME = "jmx-manager-ssl";
  public static boolean DEFAULT_JMX_MANAGER_SSL = false;

  public boolean getJmxManagerSSLRequireAuthentication();
  public void setJmxManagerSSLRequireAuthentication(boolean value);
  public boolean isJmxManagerSSLRequireAuthenticationModifiable();
  public static String JMX_MANAGER_SSL_REQUIRE_AUTHENTICATION_NAME = "jmx-manager-ssl-require-authentication";
  public static boolean DEFAULT_JMX_MANAGER_SSL_REQUIRE_AUTHENTICATION = true;

  public String getJmxManagerSSLProtocols();
  public void setJmxManagerSSLProtocols(String protocols);
  public boolean isJmxManagerSSLProtocolsModifiable();
  public static String JMX_MANAGER_SSL_PROTOCOLS_NAME = "jmx-manager-ssl-protocols";
  public static String DEFAULT_JMX_MANAGER_SSL_PROTOCOLS = "any";

  public String getJmxManagerSSLCiphers();
  public void setJmxManagerSSLCiphers(String ciphers);
  public boolean isJmxManagerSSLCiphersModifiable();
  public static String JMX_MANAGER_SSL_CIPHERS_NAME = "jmx-manager-ssl-ciphers";
  public static String DEFAULT_JMX_MANAGER_SSL_CIPHERS = "any";

  public String getJmxManagerBindAddress();
  public void setJmxManagerBindAddress(String value);
  public boolean isJmxManagerBindAddressModifiable();
  public static String JMX_MANAGER_BIND_ADDRESS_NAME = "jmx-manager-bind-address";
  public static String DEFAULT_JMX_MANAGER_BIND_ADDRESS = "";
  
  public String getJmxManagerHostnameForClients();
  public void setJmxManagerHostnameForClients(String value);
  public boolean isJmxManagerHostnameForClientsModifiable();
  public static String JMX_MANAGER_HOSTNAME_FOR_CLIENTS_NAME = "jmx-manager-hostname-for-clients";
  public static String DEFAULT_JMX_MANAGER_HOSTNAME_FOR_CLIENTS = "";
  
  public String getJmxManagerPasswordFile();
  public void setJmxManagerPasswordFile(String value);
  public boolean isJmxManagerPasswordFileModifiable();
  public static String JMX_MANAGER_PASSWORD_FILE_NAME = "jmx-manager-password-file";
  public static String DEFAULT_JMX_MANAGER_PASSWORD_FILE = "";
  
  public String getJmxManagerAccessFile();
  public void setJmxManagerAccessFile(String value);
  public boolean isJmxManagerAccessFileModifiable();
  public static String JMX_MANAGER_ACCESS_FILE_NAME = "jmx-manager-access-file";
  public static String DEFAULT_JMX_MANAGER_ACCESS_FILE = "";
  
  public int getJmxManagerHttpPort();
  public void setJmxManagerHttpPort(int value);
  public boolean isJmxManagerHttpPortModifiable();
  public static String JMX_MANAGER_HTTP_PORT_NAME = "jmx-manager-http-port";
  public static int DEFAULT_JMX_MANAGER_HTTP_PORT = 7070;

  public int getJmxManagerUpdateRate();
  public void setJmxManagerUpdateRate(int value);
  public boolean isJmxManagerUpdateRateModifiable();
  public static final int DEFAULT_JMX_MANAGER_UPDATE_RATE = 4000;
  public static final int MIN_JMX_MANAGER_UPDATE_RATE = 1000;
  public static final int MAX_JMX_MANAGER_UPDATE_RATE = 60000*5;
  public static final String JMX_MANAGER_UPDATE_RATE_NAME =
    "jmx-manager-update-rate";

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#memcached-port">"memcached-port"</a> property
   * @return the port on which GemFireMemcachedServer should be started
   * @since 7.0
   */
  public int getMemcachedPort();
  public void setMemcachedPort(int value);
  public boolean isMemcachedPortModifiable();
  public static String MEMCACHED_PORT_NAME = "memcached-port";
  public static int DEFAULT_MEMCACHED_PORT = 0;

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#memcached-protocol">"memcached-protocol"</a> property
   * @return the protocol for GemFireMemcachedServer
   * @since 7.0
   */
  public String getMemcachedProtocol();
  public void setMemcachedProtocol(String protocol);
  public boolean isMemcachedProtocolModifiable();
  public static String MEMCACHED_PROTOCOL_NAME = "memcached-protocol";
  public static String DEFAULT_MEMCACHED_PROTOCOL = GemFireMemcachedServer.Protocol.ASCII.name();
  
  /**
   * Returns the value of the <a 
   * href="../DistributedSystem.html#off-heap-memory-size">"off-heap-memory-size"</a> 
   * property.
   * @since 7.5
   */
  public String getOffHeapMemorySize();
  /**
   * Sets the value of the <a 
   * href="../DistributedSystem.html#off-heap-memory-size">"off-heap-memory-size"</a> 
   * property.
   * @since 7.5
   */
  public void setOffHeapMemorySize(String value);
  /**
   * Returns true if the value of the <a 
   * href="../DistributedSystem.html#memory-size">"memory-size"</a>
   * property can be modified. Some attributes can not be modified while the 
   * system is running.
   * @since SnappyData 0.9
   */
  public boolean isMemorySizeModifiable();

  /**
   * Returns the value of the <a
   * href="../DistributedSystem.html#memory-size">"memory-size"</a>
   * property.
   * @since SnappyData 0.9
   */
  public String getMemorySize();
  /**
   * Sets the value of the <a
   * href="../DistributedSystem.html#memory-size">"memory-size"</a>
   * property.
   * @since SnappyData 0.9
   */
  public void setMemorySize(String value);
  /**
   * Returns true if the value of the <a
   * href="../DistributedSystem.html#off-heap-memory-size">"off-heap-memory-size"</a>
   * property can be modified. Some attributes can not be modified while the
   * system is running.
   * @since 7.5
   */
  public boolean isOffHeapMemorySizeModifiable();
  /** 
   * The name of the "off-heap-memory-size" property 
   * @since 7.5
   */
  public static final String OFF_HEAP_MEMORY_SIZE_NAME = "off-heap-memory-size";
  /** 
   * The default <a 
   * href="../DistributedSystem.html#off-heap-memory-size">"off-heap-memory-size"</a>
   * value of <code>""</code>. 
   * @since 7.5
   */
  public static final String DEFAULT_OFF_HEAP_MEMORY_SIZE = "";

  
  /**
   * The name of the "default-auto-reconnect" property
   * @since 7.5
   */
  public static final String DISABLE_AUTO_RECONNECT_NAME = "disable-auto-reconnect";

  /** The default value of the corresponding property */
  public static final boolean DEFAULT_DISABLE_AUTO_RECONNECT = true;
  
  /**
   * Gets the value of <a href="../DistributedSystem.html#disable-auto-reconnect">"disable-auto-reconnect"</a>
   */
  public boolean getDisableAutoReconnect();

  /**
   * Sets the value of <a href="../DistributedSystem.html#disable-auto-reconnect">"disable-auto-reconnect"</a>
   * @param value the new setting
   */
  public void setDisableAutoReconnect(boolean value);
 
  /**
   * The name of the "lock-memory" property.  Used to cause pages to be locked
   * into memory, thereby preventing them from being swapped to disk.
   * @since 7.5
   */
  public static String LOCK_MEMORY_NAME = "lock-memory";
  public static final boolean DEFAULT_LOCK_MEMORY = false;
  /**
   * Gets the value of <a href="../DistributedSystem.html#lock-memory">"lock-memory"</a>
   */
  public boolean getLockMemory();
  /**
   * Set the value of <a href="../DistributedSystem.html#lock-memory">"lock-memory"</a>
   * @param value the new setting
   */
  public void setLockMemory(boolean value);
  public boolean isLockMemoryModifiable();

  /**
   * The name of the "memory-size" property. Total memory taken by SnappyData in off-heap mode.
   * @since SnappyData 0.9 not used in rowstore
   */
  public static final String MEMORY_SIZE_NAME = "memory-size";

  /**
   * The default <a
   * href="../DistributedSystem.html#memory-size">"memory-size"</a>
   * value of <code>""</code>.
   * @since SnappyData 0.9 not used in rowstore
   */
  public static final String DEFAULT_MEMORY_SIZE = "";
}
