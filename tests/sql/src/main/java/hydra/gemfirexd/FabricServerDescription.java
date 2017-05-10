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

package hydra.gemfirexd;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.pivotal.gemfirexd.Attribute;

import hydra.AbstractDescription;
import hydra.BasePrms;
import hydra.ConfigHashtable;
import hydra.EnvHelper;
import hydra.FileUtil;
import hydra.HostDescription;
import hydra.HostHelper;
import hydra.HydraConfigException;
import hydra.HydraInternalException;
import hydra.HydraRuntimeException;
import hydra.HydraVector;
import hydra.Prms;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import hydra.VmDescription;
import hydra.blackboard.SharedMap;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.UnknownHostException;
import java.net.InetAddress;
import java.rmi.RemoteException;
import java.util.*;

/**
 * Encodes information needed to describe and create a fabric server.
 */
public class FabricServerDescription extends AbstractDescription
implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final String LONER = "loner";

  /** The logical name of this fabric server description */
  private String name;

  /** Remaining parameters, in alphabetical order */

  private Integer ackSevereAlertThreshold; // GFE
  private Integer ackWaitThreshold; // GFE
  private Integer archiveDiskSpaceLimit; // GFE
  private Integer archiveFileSizeLimit; // GFE
  private Integer asyncDistributionTimeout; // GFE
  private Integer asyncMaxQueueSize; // GFE
  private Integer asyncQueueTimeout; // GFE
  private transient String bindAddress; // GFE
  private List<String> clientNames; // HYDRA
  private Boolean conserveSockets; // GFE
  private Boolean disableAutoReconnect; // GFE
  private Boolean disableTcp; // GFE
  private String distributedSystem; // HYDRA
  private Integer distributedSystemId; // GFE
  private Boolean enableNetworkPartitionDetection; // GFE
  private Boolean enableStatsGlobally; // GFXD
  private Boolean enableTimeStatistics; // GFE
  private Boolean enableTimeStatsGlobally; // GFXD
  private Boolean enforceUniqueHost; // GFE
  private String fabricSecurityName; // GFXD
  private FabricSecurityDescription fabricSecurityDescription; // from fabricSecurityName
  private Boolean hostData; // GFXD
  private Boolean lockMemory; // GFE
  private transient String locators; // GFE
  private Integer logDiskSpaceLimit; // GFE
  private transient String logFile; // GFE
  private Integer logFileSizeLimit; // GFE
  private String logLevel; // GFE
  private Integer maxNumReconnectTries; // GFE
  private Integer maxWaitTimeForReconnect; // GFE
  private String mcastAddress; // GFE
  private Boolean mcastDistributionEnabled; // HYDRA
  private String mcastFlowControl; // GFE
  private Integer mcastPort; // GFE
  private Integer mcastRecvBufferSize; // GFE
  private Integer mcastSendBufferSize; // GFE
  private Integer mcastTtl; // GFE
  private String membershipPortRange; // GFE
  private Integer memberTimeout; // GFE
  private transient String nameOfMember; // GFE
  private String offHeapMemorySize; // GFE
  private Boolean persistDD; // GFXD
  private Boolean persistIndexes; // GFXD
  private Boolean persistQueues; // HYDRA
  private Boolean persistTables; // HYDRA
  private Boolean rebalance; // GFXD
  private String redundancyZone; // GFE
  private List<String> remoteDistributedSystems; // HYDRA
  private transient String remoteLocators; // HYDRA
  private transient String resourceDir; // HYDRA path to sysDir and sysDiskDir
  private Boolean saveSysDiskDir; // GFXD
  private transient String serverBindAddress; // GFE
  private List<String> serverGroups; // GFXD
  private int socketBufferSize; // GFE
  private int socketLeaseTime; // GFE
  private transient String statisticArchiveFile; // GFE
  private Integer statisticSampleRate; // GFE
  private Boolean statisticSamplingEnabled; // GFE
  private transient String sysDir; // HYDRA path to system.log/statArchive.gfs
  private transient String sysDiskDir; // GFXD
  private Map<String,List<String>> sysDiskDirBases;
  private Boolean tableDefaultPartitioned; // GFXD
  private Integer tcpPort; // GFE
  private Integer udpFragmentSize; // GFE
  private Integer udpRecvBufferSize; // GFE
  private Integer udpSendBufferSize; // GFE
  private Boolean useExistingSysDiskDir; // GFXD
  private Boolean useGenericSysDiskDir; // GFXD

//------------------------------------------------------------------------------
// Constructors
//------------------------------------------------------------------------------

  public FabricServerDescription() {
  }

//------------------------------------------------------------------------------
// Accessors, in alphabetical order after name
//------------------------------------------------------------------------------

  /**
   * Returns the logical name of this fabric server description.
   */
  public String getName() {
    return this.name;
  }

  /**
   * Sets the logical name of this fabric server description.
   */
  private void setName(String str) {
    this.name = str;
  }

  public Integer getAckSevereAlertThreshold() {
    return this.ackSevereAlertThreshold;
  }
  private void setAckSevereAlertThreshold(Integer i) {
    this.ackSevereAlertThreshold = i;
  }

  public Integer getAckWaitThreshold() {
    return this.ackWaitThreshold;
  }
  private void setAckWaitThreshold(Integer i) {
    this.ackWaitThreshold = i;
  }

  public Integer getArchiveDiskSpaceLimit() {
    return this.archiveDiskSpaceLimit;
  }
  private void setArchiveDiskSpaceLimit(Integer i) {
    this.archiveDiskSpaceLimit = i;
  }

  public Integer getArchiveFileSizeLimit() {
    return this.archiveFileSizeLimit;
  }
  private void setArchiveFileSizeLimit(Integer i) {
    this.archiveFileSizeLimit = i;
  }

  public Integer getAsyncDistributionTimeout() {
    return this.asyncDistributionTimeout;
  }
  private void setAsyncDistributionTimeout(Integer i) {
    this.asyncDistributionTimeout = i;
  }

  public Integer getAsyncMaxQueueSize() {
    return this.asyncMaxQueueSize;
  }
  private void setAsyncMaxQueueSize(Integer i) {
    this.asyncMaxQueueSize = i;
  }

  public Integer getAsyncQueueTimeout() {
    return this.asyncQueueTimeout;
  }
  private void setAsyncQueueTimeout(Integer i) {
    this.asyncQueueTimeout = i;
  }

  /**
   * Client-side transient.  Returns the bind address for the local host.
   */
  private synchronized String getBindAddress() {
    if (this.bindAddress == null) {
      String addr = DistributionConfig.DEFAULT_BIND_ADDRESS;
      if (TestConfig.tab().booleanAt(Prms.useIPv6)) {
        addr = HostHelper.getHostAddress();
        if (addr == null) {
          String s = "IPv6 address is not available for this host";
          throw new HydraRuntimeException(s);
        }
      }
      this.bindAddress = addr;
    }
    return this.bindAddress;
  }

  public List<String> getClientNames() {
    return this.clientNames;
  }
  private void setClientNames(List<String> list) {
    this.clientNames = list;
  }

  public Boolean getConserveSockets() {
    return this.conserveSockets;
  }
  private void setConserveSockets(Boolean bool) {
    this.conserveSockets = bool;
  }

  public Boolean getDisableAutoReconnect() {
    return this.disableAutoReconnect;
  }
  private void setDisableAutoReconnect(Boolean  bool) {
    this.disableAutoReconnect = bool;
  }

  public Boolean getDisableTcp() {
    return this.disableTcp;
  }
  private void setDisableTcp(Boolean bool) {
    this.disableTcp = bool;
  }

  public String getDistributedSystem() {
    return this.distributedSystem;
  }
  private void setDistributedSystem(String str) {
    this.distributedSystem = str;
  }

  public Integer getDistributedSystemId() {
    return this.distributedSystemId;
  }
  private void setDistributedSystemId(Integer i) {
    this.distributedSystemId = i;
  }

  public Boolean getEnableNetworkPartitionDetection() {
    return this.enableNetworkPartitionDetection;
  }
  private void setEnableNetworkPartitionDetection(Boolean bool) {
    this.enableNetworkPartitionDetection = bool;
  }

  public Boolean getEnableStatsGlobally() {
    return this.enableStatsGlobally;
  }
  private void setEnableStatsGlobally(Boolean bool) {
    this.enableStatsGlobally = bool;
  }

  public Boolean getEnableTimeStatistics() {
    return this.enableTimeStatistics;
  }
  private void setEnableTimeStatistics(Boolean bool) {
    this.enableTimeStatistics = bool;
  }

  public Boolean getEnableTimeStatsGlobally() {
    return this.enableTimeStatsGlobally;
  }
  private void setEnableTimeStatsGlobally(Boolean bool) {
    this.enableTimeStatsGlobally = bool;
  }

  public Boolean getEnforceUniqueHost() {
    return this.enforceUniqueHost;
  }

  private void setEnforceUniqueHost(Boolean bool) {
    this.enforceUniqueHost = bool;
  }

  /**
   * Returns the fabric security name.
   */
  public String getFabricSecurityName() {
    return this.fabricSecurityName;
  }

  /**
   * Sets the fabric security name.
   */
  private void setFabricSecurityName(String str) {
    this.fabricSecurityName = str;
  }

  /**
   * Returns the fabric security description.
   */
  public FabricSecurityDescription getFabricSecurityDescription() {
    return this.fabricSecurityDescription;
  }

  /**
   * Sets the fabric security description.
   */
  private void setFabricSecurityDescription(FabricSecurityDescription fsd) {
    this.fabricSecurityDescription = fsd;
  }

  public Boolean getHostData() {
    return this.hostData;
  }
  private void setHostData(Boolean bool) {
    this.hostData = bool;
  }

  public Boolean getLockMemory() {
    return this.lockMemory;
  }
  private void setLockMemory(Boolean bool) {
    this.lockMemory = bool;
  }

  /**
   * Client-side transient.  Returns all locators that are currently available
   * for this distributed system.  Used to configure "locators" property.
   */
  private synchronized String getLocators() {
    if (this.locators == null) {
      String locs = "";
      String dsname = getDistributedSystem();
      List<FabricServerHelper.Endpoint> endpoints =
           FabricServerHelper.getEndpoints(dsname);
      if (endpoints != null) {
        for (FabricServerHelper.Endpoint endpoint : endpoints) {
          if (locs.length() > 0) locs += ",";
          locs += endpoint.getId();
        }
      }
      this.locators = locs;
    }
    return this.locators;
  }

  public Integer getLogDiskSpaceLimit() {
    return this.logDiskSpaceLimit;
  }
  private void setLogDiskSpaceLimit(Integer i) {
    this.logDiskSpaceLimit = i;
  }

  /**
   * Client-side transient.  Returns absolute path to log file.
   */
  private synchronized String getLogFile() {
    if (this.logFile == null) {
      this.logFile = this.getSysDir() + "/system.log";
    }
    return this.logFile;
  }

  public Integer getLogFileSizeLimit() {
    return this.logFileSizeLimit;
  }
  private void setLogFileSizeLimit(Integer i) {
    this.logFileSizeLimit = i;
  }

  public String getLogLevel() {
    return this.logLevel;
  }
  private void setLogLevel(String str) {
    this.logLevel = str;
  }

  public Integer getMaxNumReconnectTries() {
    return this.maxNumReconnectTries;
  }
  private void setMaxNumReconnectTries(Integer i) {
    this.maxNumReconnectTries = i;
  }

  private Integer getMaxWaitTimeForReconnect() {
     return this.maxWaitTimeForReconnect;
  }
  private void setMaxWaitTimeForReconnect(Integer i) {
    this.maxWaitTimeForReconnect = i;
  }

  public String getMcastAddress() {
    return this.mcastAddress;
  }
  private void setMcastAddress(String str) {
    this.mcastAddress = str;
  }

  public Boolean getMcastDistributionEnabled() {
    return this.mcastDistributionEnabled;
  }
  private void setMcastDistributionEnabled(Boolean bool) {
    this.mcastDistributionEnabled = bool;
  }

  public String getMcastFlowControl() {
    return this.mcastFlowControl;
  }
  private void setMcastFlowControl(String str) {
    this.mcastFlowControl = str;
  }

  public Integer getMcastPort() {
    return this.mcastPort;
  }
  private void setMcastPort(Integer i) {
    this.mcastPort = i;
  }

  public Integer getMcastRecvBufferSize() {
    return this.mcastRecvBufferSize;
  }
  private void setMcastRecvBufferSize(Integer i) {
    this.mcastRecvBufferSize = i;
  }

  public Integer getMcastSendBufferSize() {
    return this.mcastSendBufferSize;
  }
  private void setMcastSendBufferSize(Integer i) {
    this.mcastSendBufferSize = i;
  }

  public Integer getMcastTtl() {
    return this.mcastTtl;
  }
  private void setMcastTtl(Integer i) {
    this.mcastTtl = i;
  }

  public String getMembershipPortRange() {
    return this.membershipPortRange;
  }
  private void setMembershipPortRange(String str) {
    this.membershipPortRange = str;
  }

  public Integer getMemberTimeout() {
    return this.memberTimeout;
  }
  private void setMemberTimeout(Integer i) {
    this.memberTimeout = i;
  }

  /**
   * Client-side transient.  Returns the name of the member.
   *
   * This is really the "name" property, but that was taken by the field
   * for the logical description name.
   */
  private synchronized String getNameOfMember() {
    if (this.nameOfMember == null) {
      this.nameOfMember = RemoteTestModule.getCurrentThread() == null
                        ? this.name
                        : this.name + "_" + RemoteTestModule.getMyHost()
                                    + "_" + RemoteTestModule.getMyPid();
    }
    return this.nameOfMember;
  }

  public String getOffHeapMemorySize() {
    return this.offHeapMemorySize;
  }
  private void setOffHeapMemorySize(String str) {
    this.offHeapMemorySize= str;
  }

  public Boolean getPersistDD() {
    return this.persistDD;
  }
  private void setPersistDD(Boolean bool) {
    this.persistDD = bool;
  }

  public Boolean getPersistIndexes() {
    return this.persistIndexes;
  }
  private void setPersistIndexes(Boolean bool) {
    this.persistIndexes = bool;
  }

  public Boolean getPersistQueues() {
    return this.persistQueues;
  }
  private void setPersistQueues(Boolean bool) {
    this.persistQueues = bool;
  }

  public Boolean getPersistTables() {
    return this.persistTables;
  }
  private void setPersistTables(Boolean bool) {
    this.persistTables = bool;
  }

  public Boolean getRebalance() {
    return this.rebalance;
  }
  private void setRebalance(Boolean bool) {
    this.rebalance = bool;
  }

  public String getRedundancyZone() {
    return this.redundancyZone;
  }
  private void setRedundancyZone(String str) {
    this.redundancyZone = str;
  }

  public List<String> getRemoteDistributedSystems() {
    return this.remoteDistributedSystems;
  }
  private void setRemoteDistributedSystems(List<String> systems) {
    this.remoteDistributedSystems = systems;
  }

  /**
   * Client-side transient.  Returns all locators that are currently
   * available for the remote distributed systems.  Used to configure
   * "remote-distributed-systems" property.
   */
  protected synchronized String getRemoteLocators() {
    if (this.remoteLocators == null) {
      String locs = "";
      List<String> dsnames = getRemoteDistributedSystems();
      if (dsnames != null) {
        List<FabricServerHelper.Endpoint> endpoints =
             FabricServerHelper.getEndpoints(dsnames);
        for (FabricServerHelper.Endpoint endpoint : endpoints) {
          if (locs.length() > 0) locs += ",";
          locs += endpoint.getId();
        }
      }
      this.remoteLocators = locs;
    }
    return this.remoteLocators;
  }

  /**
   * Client-side transient.  Returns absolute path to the system directories.
   */
  private synchronized String getResourceDir() {
    if (this.resourceDir == null) {
      HostDescription hd = getHostDescription();
      String dir = hd.getResourceDir();
      if (dir == null) {
        dir = hd.getUserDir();
      }
      this.resourceDir = dir;
    }
    return this.resourceDir;
  }

  public Boolean saveSysDiskDir() {
    return this.saveSysDiskDir;
  }
  private void setSaveSysDiskDir(Boolean bool) {
    this.saveSysDiskDir = bool;
  }

  /**
   * Client-side transient.  Returns the server bind address for the local host.
   */
  private synchronized String getServerBindAddress() {
    if (this.serverBindAddress == null) {
      this.serverBindAddress = getBindAddress();
    }
    return this.serverBindAddress;
  }

  public List<String> getServerGroups() {
    return this.serverGroups;
  }
  public String getServerGroupsProperty() {
    String str = "";
    for (String group : this.serverGroups) {
      str += str.length() == 0 ? group : "," + group;
    }
    return str.length() == 0 ? null : str;
  }
  private void setServerGroups(List<String> list) {
    this.serverGroups = list;
  }

  public Integer getSocketBufferSize() {
    return this.socketBufferSize;
  }
  private void setSocketBufferSize(Integer i) {
    this.socketBufferSize = i;
  }

  public Integer getSocketLeaseTime() {
    return this.socketLeaseTime;
  }
  private void setSocketLeaseTime(Integer i) {
    this.socketLeaseTime = i;
  }

  /**
   * Client-side transient.  Returns absolute path to statistic archive file.
   */
  private synchronized String getStatisticArchiveFile() {
    if (this.statisticArchiveFile == null) {
      this.statisticArchiveFile = this.getSysDir() + "/statArchive.gfs";
    }
    return this.statisticArchiveFile;
  }

  public Integer getStatisticSampleRate() {
    return this.statisticSampleRate;
  }
  private void setStatisticSampleRate(Integer i) {
    this.statisticSampleRate = i;
  }

  public Boolean getStatisticSamplingEnabled() {
    return this.statisticSamplingEnabled;
  }
  private void setStatisticSamplingEnabled(Boolean bool) {
    this.statisticSamplingEnabled = bool;
  }

  /**
   * Client-side transient.  Returns absolute path to system directory.
   * Lazily creates and registers it.  This directory contains system logs
   * and statistics archives.
   */
  public synchronized String getSysDir() {
    if (this.sysDir == null) {
      HostDescription hd = getHostDescription();
      String dir = this.getResourceDir() + "/"
                 + "vm_" + RemoteTestModule.getMyVmid()
                 + "_" + RemoteTestModule.getMyClientName()
                 + "_" + hd.getHostName()
                 + "_" + RemoteTestModule.getMyPid();
      FileUtil.mkdir(new File(dir));
      try {
        RemoteTestModule.Master.recordDir(hd, getName(), dir);
      } catch (RemoteException e) {
        String s = "Unable to access master to record directory: " + dir;
        throw new HydraRuntimeException(s, e);
      }
      this.sysDir = dir;
    }
    return this.sysDir;
  }

  /*
   * Client-side transient. Returns the absolute path to the disk directory.
   * Defaults to the system directory.
   * <p>
   * Lazily creates and registers the directory.  This directory contains
   * the persistent data dictionary, queues, and tables.
   * <p>
   * Autogenerates the directory name using the user-defined bases for the
   * local host, if specified. Round robins the bases across the servers
   * that use them.
   * <p>
   * Example: /export/.../test-0328-112342/vm_3_server2_disk
   *
   * @throws HydraRuntimeException if a directory cannot be created.
   */
  public synchronized String getSysDiskDir() {
    if (this.sysDiskDir == null && (getPersistDD() || getPersistQueues()
                                                   || getPersistTables())) {
      Map<String,List<String>> bases = this.getSysDiskDirBases();
      if (bases != null) {
        HostDescription hd = getHostDescription();
        String hostName = hd.getHostName();
        List<String> paths = bases.get(hostName);
        if (paths != null) {
          // get the next path, round robin with other clients,
          // regardless of the fabric server description name
          SharedMap map = SysDiskDirBlackboard.getInstance().getSharedMap();
          Integer nextPathNum, newPathNum = null;
          do {
            nextPathNum = (Integer)map.get(hostName);
            newPathNum = (nextPathNum + 1) % paths.size();
          } while (!map.replace(hostName, nextPathNum, newPathNum));
          String path = paths.get(nextPathNum);
          String testDir = (new File(getSysDir())).getParentFile().getName();
          // do not add pid here since dirs must survive pid change
          String dir = path;
          if (!this.useGenericSysDiskDir) {
            dir += File.separator + testDir;
          }
          dir += File.separator + "vm_" + RemoteTestModule.getMyVmid() + "_"
              + RemoteTestModule.getMyClientName()
              + "_" + hd.getHostName() + "_disk";
          dir = EnvHelper.expandEnvVars(dir, hd);
          if (this.useExistingSysDiskDir) {
            if (!FileUtil.exists(dir)) {
              String s = BasePrms.nameForKey(FabricServerPrms.useExistingSysDiskDir)
                       + "=true but system disk directory was not found: " + dir;
              throw new HydraRuntimeException(s);
            }
          } else {
            if (this.useGenericSysDiskDir && FileUtil.exists(dir)) {
              String s = BasePrms.nameForKey(FabricServerPrms.useExistingSysDiskDir)
                       + "=false but system disk directory already exists: " + dir;
              throw new HydraRuntimeException(s);
            }
            FileUtil.mkdir(new File(dir));
          }
          if (!this.saveSysDiskDir()) {
            try {
              RemoteTestModule.Master.recordDir(hd, getName(), dir);
            } catch (RemoteException e) {
              String s = "Unable to access master to record directory: " + dir;
              throw new HydraRuntimeException(s, e);
            }
          }
          this.sysDiskDir = dir;
        }
      }
      if (this.sysDiskDir == null) { // default to system directory
        HostDescription hd = getHostDescription();
        String dir = this.getResourceDir() + "/"
                   + "vm_" + RemoteTestModule.getMyVmid()
                   + "_" + RemoteTestModule.getMyClientName()
                   + "_" + hd.getHostName()
                   + "_disk";
        FileUtil.mkdir(new File(dir));
        try {
          RemoteTestModule.Master.recordDir(hd, getName(), dir);
        } catch (RemoteException e) {
          String s = "Unable to access master to record directory: " + dir;
          throw new HydraRuntimeException(s, e);
        }
        this.sysDiskDir = dir;
      }
    }
    return this.sysDiskDir;
  }

  /**
   * Returns the disk directory bases.
   */
  private Map<String,List<String>> getSysDiskDirBases() {
    return this.sysDiskDirBases;
  }

  /**
   * Sets the disk directory bases.
   */
  private void setSysDiskDirBases(Map<String,List<String>> bases) {
    this.sysDiskDirBases = bases;
  }

  public Boolean useExistingSysDiskDir() {
    return this.useExistingSysDiskDir;
  }
  private void setUseExistingSysDiskDir(Boolean bool) {
    this.useExistingSysDiskDir = bool;
  }

  public Boolean useGenericSysDiskDir() {
    return this.useGenericSysDiskDir;
  }
  private void setUseGenericSysDiskDir(Boolean bool) {
    this.useGenericSysDiskDir = bool;
  }

  public Boolean getTableDefaultPartitioned() {
    return this.tableDefaultPartitioned;
  }
  private void setTableDefaultPartitioned(Boolean bool) {
    this.tableDefaultPartitioned = bool;
  }

  public Integer getTcpPort() {
    return this.tcpPort;
  }
  private void setTcpPort(Integer i) {
    this.tcpPort = i;
  }

  public Integer getUdpFragmentSize() {
    return this.udpFragmentSize;
  }
  private void setUdpFragmentSize(Integer i) {
    this.udpFragmentSize = i;
  }

  public Integer getUdpRecvBufferSize() {
    return this.udpRecvBufferSize;
  }
  private void setUdpRecvBufferSize(Integer i) {
    this.udpRecvBufferSize = i;
  }

  public Integer getUdpSendBufferSize() {
    return this.udpSendBufferSize;
  }
  private void setUdpSendBufferSize(Integer i) {
    this.udpSendBufferSize = i;
  }

  /**
   * Returns the host description for this hydra client VM, after validating
   * that it is legal for it to use this description.
   */
  private HostDescription getHostDescription() {
    // make sure this is a hydra client
    String clientName = RemoteTestModule.getMyClientName();
    if (clientName == null) {
      String s = "This method can only be invoked from hydra client VMs";
      throw new HydraInternalException(s);
    }
    // if the description specifies clients, make sure this one belongs
    if (this.clientNames != null && !this.clientNames.contains(clientName)) {
      String s = "Logical client " + clientName
               + " is not in the list of clients supported by "
               + BasePrms.nameForKey(FabricServerPrms.names) + "=" + getName();
      throw new HydraRuntimeException(s);
    }
    return TestConfig.getInstance().getClientDescription(clientName)
                                   .getVmDescription().getHostDescription();
  }

//------------------------------------------------------------------------------
// Properties
//------------------------------------------------------------------------------

  /**
   * Returns the boot properties for this fabric server description, including
   * security properties, if any.
   */
  protected Properties getBootProperties() {
    String key;
    Properties p = new Properties();

    p.setProperty(DistributionConfig.NAME_NAME,
                  this.getNameOfMember());

    p.setProperty(DistributionConfig.ACK_SEVERE_ALERT_THRESHOLD_NAME,
                  getAckSevereAlertThreshold().toString());
    p.setProperty(DistributionConfig.ACK_WAIT_THRESHOLD_NAME,
                  getAckWaitThreshold().toString());
    p.setProperty(DistributionConfig.ARCHIVE_DISK_SPACE_LIMIT_NAME,
                  getArchiveDiskSpaceLimit().toString());
    p.setProperty(DistributionConfig.ARCHIVE_FILE_SIZE_LIMIT_NAME,
                  getArchiveFileSizeLimit().toString());
    p.setProperty(DistributionConfig.ASYNC_DISTRIBUTION_TIMEOUT_NAME,
                  getAsyncDistributionTimeout().toString());
    p.setProperty(DistributionConfig.ASYNC_MAX_QUEUE_SIZE_NAME,
                  getAsyncMaxQueueSize().toString());
    p.setProperty(DistributionConfig.ASYNC_QUEUE_TIMEOUT_NAME,
                  getAsyncQueueTimeout().toString());
    p.setProperty(DistributionConfig.BIND_ADDRESS_NAME,
                  getBindAddress());
    p.setProperty(DistributionConfig.CONSERVE_SOCKETS_NAME,
                  getConserveSockets().toString());
    p.setProperty(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME,
                  getDisableAutoReconnect().toString());
    p.setProperty(DistributionConfig.DISABLE_TCP_NAME,
                  getDisableTcp().toString());
    // locators set the distributedSystemId when they are started
    p.setProperty(DistributionConfig.ENABLE_NETWORK_PARTITION_DETECTION_NAME,
                  getEnableNetworkPartitionDetection().toString());
    p.setProperty(DistributionConfig.ENABLE_TIME_STATISTICS_NAME,
                  getEnableTimeStatistics().toString());
    p.setProperty(DistributionConfig.ENFORCE_UNIQUE_HOST_NAME,
                  getEnforceUniqueHost().toString());
    if (getFabricSecurityDescription() != null) {
      addProperties(getFabricSecurityDescription().getBootProperties(), p);
    }
    p.setProperty(Attribute.GFXD_HOST_DATA,
                  getHostData().toString());
    p.setProperty(DistributionConfig.LOCK_MEMORY_NAME,
                  getLockMemory().toString());
    p.setProperty(DistributionConfig.LOCATORS_NAME,
                  getLocators());
    p.setProperty(DistributionConfig.LOG_DISK_SPACE_LIMIT_NAME,
                  getLogDiskSpaceLimit().toString());
    p.setProperty(DistributionConfig.LOG_FILE_NAME,
                  getLogFile());
    p.setProperty(DistributionConfig.LOG_FILE_SIZE_LIMIT_NAME,
                  getLogFileSizeLimit().toString());
    p.setProperty(DistributionConfig.LOG_LEVEL_NAME,
                  getLogLevel());
    p.setProperty(DistributionConfig.MAX_NUM_RECONNECT_TRIES,
                  getMaxNumReconnectTries().toString());
    p.setProperty(DistributionConfig.MAX_WAIT_TIME_FOR_RECONNECT_NAME,
                  getMaxWaitTimeForReconnect().toString());
    p.setProperty(DistributionConfig.MCAST_ADDRESS_NAME,
                  getMcastAddress());
    p.setProperty(DistributionConfig.MCAST_FLOW_CONTROL_NAME,
                  getMcastFlowControl());
    p.setProperty(DistributionConfig.MCAST_PORT_NAME,
                  getMcastPort().toString());
    p.setProperty(DistributionConfig.MCAST_RECV_BUFFER_SIZE_NAME,
                  getMcastRecvBufferSize().toString());
    p.setProperty(DistributionConfig.MCAST_SEND_BUFFER_SIZE_NAME,
                  getMcastSendBufferSize().toString());
    p.setProperty(DistributionConfig.MCAST_TTL_NAME,
                  getMcastTtl().toString());
    p.setProperty(DistributionConfig.MEMBERSHIP_PORT_RANGE_NAME,
                  getMembershipPortRange());
    p.setProperty(DistributionConfig.MEMBER_TIMEOUT_NAME,
                  getMemberTimeout().toString());
    if (getOffHeapMemorySize() != null) {
      p.setProperty(DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME,
                    getOffHeapMemorySize().toString());
    }
    if (getRedundancyZone() != null) {
      p.setProperty(DistributionConfig.REDUNDANCY_ZONE_NAME,
                    getRedundancyZone());
    }
    p.setProperty(Attribute.GFXD_PERSIST_DD,
                  getPersistDD().toString());
    p.setProperty(Attribute.PERSIST_INDEXES,
                  getPersistIndexes().toString());
    FabricServerVersionHelper.setRebalance(p, getRebalance());
    p.setProperty(DistributionConfig.SERVER_BIND_ADDRESS_NAME,
                  getServerBindAddress());
    p.setProperty(DistributionConfig.SOCKET_BUFFER_SIZE_NAME,
                  getSocketBufferSize().toString());
    p.setProperty(DistributionConfig.SOCKET_LEASE_TIME_NAME,
                  getSocketLeaseTime().toString());
    p.setProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
                  getStatisticArchiveFile());
    p.setProperty(DistributionConfig.STATISTIC_SAMPLE_RATE_NAME,
                  getStatisticSampleRate().toString());
    p.setProperty(DistributionConfig.STATISTIC_SAMPLING_ENABLED_NAME,
                  getStatisticSamplingEnabled().toString());
    if (getServerGroups() != null) {
      p.setProperty(Attribute.SERVER_GROUPS,
                  getServerGroupsProperty());
    }
    if (getSysDiskDir() != null) {
      p.setProperty(Attribute.SYS_PERSISTENT_DIR,
                  getSysDiskDir());
    }
    p.setProperty(Attribute.TABLE_DEFAULT_PARTITIONED,
                  getTableDefaultPartitioned().toString());
    p.setProperty(DistributionConfig.TCP_PORT_NAME,
                  getTcpPort().toString());
    p.setProperty(DistributionConfig.UDP_FRAGMENT_SIZE_NAME,
                  getUdpFragmentSize().toString());
    p.setProperty(DistributionConfig.UDP_RECV_BUFFER_SIZE_NAME,
                  getUdpRecvBufferSize().toString());
    p.setProperty(DistributionConfig.UDP_SEND_BUFFER_SIZE_NAME,
                  getUdpSendBufferSize().toString());

    return p;
  }

  /**
   * Returns the shutdown properties for this fabric server description,
   * including security properties, if any.
   */
  protected Properties getShutdownProperties() {
    Properties p = new Properties();
    if (getFabricSecurityDescription() != null) {
      addProperties(getFabricSecurityDescription().getShutdownProperties(), p);
    }
    return p;
  }

//------------------------------------------------------------------------------
// Printing
//------------------------------------------------------------------------------

  public SortedMap<String,Object> toSortedMap() {
    SortedMap<String,Object> map = new TreeMap();
    String header = this.getClass().getName() + "." + this.getName() + ".";
    map.put(header + "ackSevereAlertThreshold", getAckSevereAlertThreshold());
    map.put(header + "ackWaitThreshold", getAckWaitThreshold());
    map.put(header + "archiveDiskSpaceLimit", getArchiveDiskSpaceLimit());
    map.put(header + "archiveFileSizeLimit", getArchiveFileSizeLimit());
    map.put(header + "asyncDistributionTimeout", getAsyncDistributionTimeout());
    map.put(header + "asyncMaxQueueSize", getAsyncMaxQueueSize());
    map.put(header + "asyncQueueTimeout", getAsyncQueueTimeout());
    map.put(header + "bindAddress", "autogenerated using hydra client localhost");
    map.put(header + "clientNames", getClientNames());
    map.put(header + "conserveSockets", getConserveSockets());
    map.put(header + "disableAutoReconnect", getDisableAutoReconnect());
    map.put(header + "disableTcp", getDisableTcp());
    map.put(header + "distributedSystem", getDistributedSystem());
    map.put(header + "distributedSystemId", getDistributedSystemId());
    map.put(header + "enableNetworkPartitionDetection", getEnableNetworkPartitionDetection());
    map.put(header + "enableStatsGlobally", getEnableStatsGlobally());
    map.put(header + "enableTimeStatistics", getEnableTimeStatistics());
    map.put(header + "enableTimeStatsGlobally", getEnableTimeStatsGlobally());
    map.put(header + "enforceUniqueHost", this.getEnforceUniqueHost());
    map.put(header + "fabricSecurityName", this.getFabricSecurityName());
    map.put(header + "hostData", getHostData());
    map.put(header + "lockMemory", getLockMemory());
    map.put(header + "locators", "deferred to hydra client");
    map.put(header + "logDiskSpaceLimit", getLogDiskSpaceLimit());
    map.put(header + "logFile", "autogenerated: system.log in gemfire system directory");
    map.put(header + "logFileSizeLimit", getLogFileSizeLimit());
    map.put(header + "logLevel", getLogLevel());
    map.put(header + "maxNumReconnectTries" , getMaxNumReconnectTries());
    map.put(header + "maxWaitTimeForReconnect" , getMaxWaitTimeForReconnect());
    map.put(header + "mcastAddress", getMcastAddress());
    map.put(header + "mcastDistributionEnabled", getMcastDistributionEnabled());
    map.put(header + "mcastFlowControl", getMcastFlowControl());
    map.put(header + "mcastPort", getMcastPort());
    map.put(header + "mcastRecvBufferSize", getMcastRecvBufferSize());
    map.put(header + "mcastSendBufferSize", getMcastSendBufferSize());
    map.put(header + "mcastTtl", getMcastTtl());
    map.put(header + "membershipPortRange", getMembershipPortRange());
    map.put(header + "memberTimeout", getMemberTimeout());
    map.put(header + "offHeapMemorySize", getOffHeapMemorySize());
    map.put(header + "persistDD", getPersistDD());
    map.put(header + "persistIndexes", getPersistIndexes());
    map.put(header + "persistQueues", getPersistQueues());
    map.put(header + "persistTables", getPersistTables());
    map.put(header + "rebalance", getRebalance());
    map.put(header + "redundancyZone", getRedundancyZone());
    map.put(header + "remoteDistributedSystems", getRemoteDistributedSystems());
    map.put(header + "saveSysDiskDir", saveSysDiskDir());
    map.put(header + "serverBindAddress", "autogenerated using hydra client localhost");
    map.put(header + "serverGroups", getServerGroups());
    map.put(header + "socketBufferSize", getSocketBufferSize());
    map.put(header + "socketLeaseTime", getSocketLeaseTime());
    map.put(header + "statisticArchiveFile", "autogenerated: statArchive.gfs in gemfire system directory");
    map.put(header + "statisticSampleRate", getStatisticSampleRate());
    map.put(header + "statisticSamplingEnabled", getStatisticSamplingEnabled());
    map.put(header + "sysDiskDir", "autogenerated");
    map.put(header + "sysDiskDirBases", this.getSysDiskDirBases());
    map.put(header + "tableDefaultPartitioned", getTableDefaultPartitioned());
    map.put(header + "tcpPort", getTcpPort());
    map.put(header + "udpFragmentSize", getUdpFragmentSize());
    map.put(header + "udpRecvBufferSize", getUdpRecvBufferSize());
    map.put(header + "udpSendBufferSize", getUdpSendBufferSize());
    map.put(header + "useExistingSysDiskDir", useExistingSysDiskDir());
    map.put(header + "useGenericSysDiskDir", useGenericSysDiskDir());
    return map;
  }

//------------------------------------------------------------------------------
// Configuration
//------------------------------------------------------------------------------

  /**
   * Creates fabric server descriptions from the test configuration parameters.
   */
  protected static Map configure(TestConfig config, GfxdTestConfig sconfig) {

    ConfigHashtable tab = config.getParameters();

    if (tab.booleanAt(Prms.manageLocatorAgents)) {
      String s = "Master-managed fabric server locators are unsupported.  Set "
               + BasePrms.nameForKey(Prms.manageLocatorAgents) + " = false";
    }

    // create a description for each fabric server name
    SortedMap<String,FabricServerDescription> fsds = new TreeMap();
    Map<String, Integer> dsids = new HashMap();
    int nextDSID = 0;
    List<String> usedClients = new ArrayList();
    Map<String,Integer> usedMcastPorts = new HashMap();
    Vector names = tab.vecAt(FabricServerPrms.names, new HydraVector());
    for (int i = 0; i < names.size(); i++) {
      String name = (String)names.elementAt(i);
      if (fsds.containsKey(name)) {
        String s = BasePrms.nameForKey(FabricServerPrms.names)
                 + " contains duplicate entries: " + names;
        throw new HydraConfigException(s);
      }
      FabricServerDescription fsd = createFabricServerDescription(name,
                                          config, sconfig, i,
                                          dsids, nextDSID,
                                          usedClients, usedMcastPorts);
      fsds.put(name, fsd);
    }
    return fsds;
  }

  /**
   * Creates the fabric server description using test configuration parameters
   * and product defaults.
   */
  private static FabricServerDescription createFabricServerDescription(
                 String name, TestConfig config, GfxdTestConfig sconfig,
                 int index, Map<String, Integer> dsids, int nextDSID,
                 List<String> usedClients, Map<String,Integer> usedMcastPorts) {

    ConfigHashtable tab = config.getParameters();

    FabricServerDescription fsd = new FabricServerDescription();
    fsd.setName(name);

    // ackSevereAlertThreshold
    {
      Long key = FabricServerPrms.ackSevereAlertThreshold;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_ACK_SEVERE_ALERT_THRESHOLD;
      }
      fsd.setAckSevereAlertThreshold(i);
    }
    // ackWaitThreshold
    {
      Long key = FabricServerPrms.ackWaitThreshold;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_ACK_WAIT_THRESHOLD;
      }
      fsd.setAckWaitThreshold(i);
    }
    // archiveDiskSpaceLimit
    {
      Long key = FabricServerPrms.archiveDiskSpaceLimit;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_ARCHIVE_DISK_SPACE_LIMIT;
      }
      fsd.setArchiveDiskSpaceLimit(i);
    }
    // archiveFileSizeLimit
    {
      Long key = FabricServerPrms.archiveFileSizeLimit;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_ARCHIVE_FILE_SIZE_LIMIT;
      }
      fsd.setArchiveFileSizeLimit(i);
    }
    // asyncDistributionTimeout
    {
      Long key = FabricServerPrms.asyncDistributionTimeout;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_ASYNC_DISTRIBUTION_TIMEOUT;
      }
      fsd.setAsyncDistributionTimeout(i);
    }
    // asyncMaxQueueSize
    {
      Long key = FabricServerPrms.asyncMaxQueueSize;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_ASYNC_MAX_QUEUE_SIZE;
      }
      fsd.setAsyncMaxQueueSize(i);
    }
    // asyncQueueTimeout
    {
      Long key = FabricServerPrms.asyncQueueTimeout;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_ASYNC_QUEUE_TIMEOUT;
      }
      fsd.setAsyncQueueTimeout(i);
    }
    // defer bindAddress
    // clientNames
    {
      Long key = FabricServerPrms.clientNames;
      Vector strs = tab.vecAtWild(key, index, null);
      if (strs == null) {
        String s = BasePrms.nameForKey(key)
                 + " is a required field and has no default value";
        throw new HydraConfigException(s);
      }
      List<String> cnames = getClientNames(strs, key, usedClients, tab);
      fsd.setClientNames(cnames);

      // add product jar to class paths
      for (String cname : cnames) {
        VmDescription vmd = config.getClientDescription(cname)
                                  .getVmDescription();
        HostDescription hd = vmd.getHostDescription();
        List<String> classPaths = new ArrayList();
        String classPath = hd.getGemFireHome()
                         + hd.getFileSep() + ".."
                         + hd.getFileSep() + "product-gfxd"
                         + hd.getFileSep() + "lib"
                         + hd.getFileSep() + "gemfirexd.jar";
        classPaths.add(classPath);
        vmd.setGemFireXDClassPaths(classPaths);
      }
    }
    // conserveSockets
    {
      Long key = FabricServerPrms.conserveSockets;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.TRUE;
      }
      fsd.setConserveSockets(bool);
    }
    // disableAutoReconnect
    {
      Long key = FabricServerPrms.disableAutoReconnect;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = DistributionConfig.DEFAULT_DISABLE_AUTO_RECONNECT;
      }
      fsd.setDisableAutoReconnect(bool);
    }
    // disableTcp
    {
      Long key = FabricServerPrms.disableTcp;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = DistributionConfig.DEFAULT_DISABLE_TCP;
      }
      fsd.setDisableTcp(bool);
    }
    // distributedSystem
    {
      Long key = FabricServerPrms.distributedSystem;
      String str = tab.getString(key, tab.getWild(key, index,
                       FabricServerPrms.DEFAULT_DISTRIBUTED_SYSTEM_NAME));
      if (str.equalsIgnoreCase(LONER)) {
        String s = BasePrms.nameForKey(FabricServerPrms.distributedSystem)
          + " cannot be set to \"" + LONER
          + "\". Use hydra.gemfirexd.LonerPrms instead.";
        throw new HydraConfigException(s);
      }
      // use the same dsid for all descriptions for a given system
      Integer dsid = dsids.get(str);
      if (dsid == null) {
        // try to use the dsid in the system name, if possible
        // note that the id is only used for locators
        dsid = toDSID(str);
        if (dsid == null || dsids.values().contains(dsid)) {
          // use the next available dsid
          do {
            dsid = Integer.valueOf(++nextDSID);
          } while (dsids.values().contains(dsid));
        }
        dsids.put(str, dsid);
      }
      fsd.setDistributedSystem(str);
      fsd.setDistributedSystemId(dsid);
    }
    // enableNetworkPartitionDetection
    {
      Long key = FabricServerPrms.enableNetworkPartitionDetection;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = DistributionConfig.DEFAULT_ENABLE_NETWORK_PARTITION_DETECTION;
      }
      fsd.setEnableNetworkPartitionDetection(bool);
    }
    // enableStatsGlobally
    {
      Long key = FabricServerPrms.enableStatsGlobally;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setEnableStatsGlobally(bool);

      // do not set the system property unless it is set to true
      // to avoid overriding the connection property
      if (fsd.getEnableStatsGlobally()) {
        String arg = "-D" + Attribute.GFXD_PREFIX
                   + Attribute.ENABLE_STATS + "=true";
        for (String cname : fsd.getClientNames()) {
          config.getClientDescription(cname).getVmDescription()
                .addExtraVMArg(arg);
        }
      }
    }
    // enableTimeStatistics
    {
      Long key = FabricServerPrms.enableTimeStatistics;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.TRUE; // overrides product default
      }
      fsd.setEnableTimeStatistics(bool);
    }
    // enableTimeStatsGlobally
    {
      Long key = FabricServerPrms.enableTimeStatsGlobally;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setEnableTimeStatsGlobally(bool);

      // do not set the system property unless it is set to true
      // to avoid overriding the connection property
      if (fsd.getEnableStatsGlobally() && fsd.getEnableTimeStatsGlobally()) {
        String arg = "-D" + Attribute.GFXD_PREFIX
                   + Attribute.ENABLE_TIMESTATS + "=true";
        for (String cname : fsd.getClientNames()) {
          config.getClientDescription(cname).getVmDescription()
                .addExtraVMArg(arg);
        }
      }
    }
      // enforceUniqueHost
      {
        Long key = FabricServerPrms.enforceUniqueHost;
        Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
        if (bool == null) {
          bool = DistributionConfig.DEFAULT_ENFORCE_UNIQUE_HOST;
        }
        fsd.setEnforceUniqueHost(bool);
      }
    // fabricSecurityName (generates fabricSecurityDescription)
    {
      Long key = FabricServerPrms.fabricSecurityName;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str != null && !str.equalsIgnoreCase(BasePrms.NONE)) {
        fsd.setFabricSecurityName("FabricSecurityDescription." + str);
        fsd.setFabricSecurityDescription(
            getFabricSecurityDescription(str, key, sconfig));
      }
    }
    // hostData
    {
      Long key = FabricServerPrms.hostData;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.TRUE;
      }
      fsd.setHostData(bool);
    }
    // lockMemory
    {
      Long key = FabricServerPrms.lockMemory;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = DistributionConfig.DEFAULT_LOCK_MEMORY;
      }
      fsd.setLockMemory(bool);
    }
    // defer locators
    // logDiskSpaceLimit
    {
      Long key = FabricServerPrms.logDiskSpaceLimit;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_LOG_DISK_SPACE_LIMIT;
      }
      fsd.setLogDiskSpaceLimit(i);
    }
    // defer logFile
    // logFileSizeLimit
    {
      Long key = FabricServerPrms.logFileSizeLimit;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_LOG_FILE_SIZE_LIMIT;
      }
      fsd.setLogFileSizeLimit(i);
    }
    // logLevel
    {
      Long key = FabricServerPrms.logLevel;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str == null) {
        str = LogWriterImpl.levelToString(DistributionConfig.DEFAULT_LOG_LEVEL);
      }
      fsd.setLogLevel(str);
    }
    // maxNumReconnectTries
    {
      Long key = FabricServerPrms.maxNumReconnectTries;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_MAX_NUM_RECONNECT_TRIES;
      }
      fsd.setMaxNumReconnectTries(i);
    }
    // maxWaitTimeForReconnect
    {
      Long key = FabricServerPrms.maxWaitTimeForReconnect;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_MAX_WAIT_TIME_FOR_RECONNECT;
      }
      fsd.setMaxWaitTimeForReconnect(i);
    }
    // mcastAddress
    {
      Long key = FabricServerPrms.mcastAddress;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str == null) {
        if (tab.booleanAt(Prms.useIPv6)) {
          int addr = tab.getRandGen().nextInt(1111, 8888);
          str = "FF38::" + addr;
        } else { // use IPv4
          int addr1 = tab.getRandGen().nextInt(81, 254);
          int addr2 = tab.getRandGen().nextInt(1, 254);
          str = "239.192." + addr1 + "." + addr2;
          // native client testing uses 224.10.11.[1-254] & 224.10.10.*
        }
      }
      fsd.setMcastAddress(str);
    }
    // mcastDistributionEnabled (hydra-specific)
    {
      Long key = FabricServerPrms.mcastDistributionEnabled;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      // complain if mcast is disabled here but was enabled for another member
      // can tell by checking whether a port has been chosen for this system
      if (usedMcastPorts.get(fsd.getDistributedSystem()) != null && !bool) {
        String s = BasePrms.nameForKey(FabricServerPrms.distributedSystem)
          + "=" + fsd.getDistributedSystem() + " has inconsistent values for "
          + BasePrms.nameForKey(FabricServerPrms.mcastDistributionEnabled);
        throw new HydraConfigException(s);
      }
      fsd.setMcastDistributionEnabled(bool);
    }
    // mcastFlowControl
    {
      Long key;
      key = FabricServerPrms.mcastFlowControlByteAllowance;
      Integer ba = tab.getInteger(key, tab.getWild(key, index, null));
      if (ba == null) {
        ba = DistributionConfig.DEFAULT_MCAST_FLOW_CONTROL.getByteAllowance();
      }
      key = FabricServerPrms.mcastFlowControlRechargeBlockMs;
      Integer rb = tab.getInteger(key, tab.getWild(key, index, null));
      if (rb == null) {
        rb = DistributionConfig.DEFAULT_MCAST_FLOW_CONTROL.getRechargeBlockMs();
      }
      key = FabricServerPrms.mcastFlowControlRechargeThreshold;
      Double rt = tab.getDouble(key, tab.getWild(key, index, null));
      if (rt == null) {
        rt = Double.valueOf(DistributionConfig.DEFAULT_MCAST_FLOW_CONTROL.getRechargeThreshold());
      }
      fsd.setMcastFlowControl(ba + "," + rt + "," + rb);
    }
    // mcastPort
    {
      // look up the previously chosen non-zero port for this system, if any
      Integer priorPort = usedMcastPorts.get(fsd.getDistributedSystem());

      Integer i = null;
      if (!fsd.getMcastDistributionEnabled()) {
        i = 0;
      } else {
        InetAddress addr = null;
        try {
          addr = InetAddress.getByName(fsd.getMcastAddress());
        } catch (UnknownHostException e) {
          String s = "Unable to get address for: " + fsd.getMcastAddress();
          throw new HydraRuntimeException(s, e);
        }
        Long key = FabricServerPrms.mcastPort;
        i = tab.getInteger(key, tab.getWild(key, index, priorPort));
        if (i == null) {
          i = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS, addr);
          usedMcastPorts.put(fsd.getDistributedSystem(), i);
        } else if (i != priorPort) {
          String s = BasePrms.nameForKey(FabricServerPrms.distributedSystem)
                   + "=" + fsd.getDistributedSystem()
                   + " has inconsistent values for "
                   + BasePrms.nameForKey(FabricServerPrms.mcastPort);
          throw new HydraConfigException(s);
        }
        if (!AvailablePort.isPortAvailable(i.intValue(),
                                           AvailablePort.JGROUPS, addr)) {
          String s = "Port is already in use: " + i;
          throw new HydraConfigException(s);
        }
      }
      fsd.setMcastPort(i);
    }
    // mcastRecvBufferSize
    {
      Long key = FabricServerPrms.mcastRecvBufferSize;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_MCAST_RECV_BUFFER_SIZE;
      }
      fsd.setMcastRecvBufferSize(i);
    }
    // mcastSendBufferSize
    {
      Long key = FabricServerPrms.mcastSendBufferSize;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_MCAST_SEND_BUFFER_SIZE;
      }
      fsd.setMcastSendBufferSize(i);
    }
    // mcastTtl
    {
      Long key = FabricServerPrms.mcastTtl;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = 0; // overrides product default
      }
      fsd.setMcastTtl(i);
    }
    // membershipPortRange
    {
      Long key = FabricServerPrms.membershipPortRange;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str == null) {
        str = DistributionConfig.DEFAULT_MEMBERSHIP_PORT_RANGE[0] + "-"
            + DistributionConfig.DEFAULT_MEMBERSHIP_PORT_RANGE[1];
      }
      fsd.setMembershipPortRange(str);
    }
    // memberTimeout
    {
      Long key = FabricServerPrms.memberTimeout;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_MEMBER_TIMEOUT;
      }
      fsd.setMemberTimeout(i);
    }
    // defer name (of member)
    // offHeapMemorySize
    {
      Long key = FabricServerPrms.offHeapMemorySize;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str != null) {
        fsd.setOffHeapMemorySize(str);
      }
    }
    // persistDD
    {
      Long key = FabricServerPrms.persistDD;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.TRUE;
      }
      fsd.setPersistDD(bool);
    }
    // persistIndexes
    {
      Long key = FabricServerPrms.persistIndexes;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.TRUE;
      }
      fsd.setPersistIndexes(bool);
    }
    // persistQueues
    {
      Long key = FabricServerPrms.persistQueues;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setPersistQueues(bool);
    }
    // persistTables
    {
      Long key = FabricServerPrms.persistTables;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setPersistTables(bool);
    }
    // rebalance
    {
      Long key = FabricServerPrms.rebalance;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setRebalance(bool);
    }
    // redundancyZone
    {
      Long key = FabricServerPrms.redundancyZone;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str != null && !str.equalsIgnoreCase(BasePrms.NONE)) {
        fsd.setRedundancyZone(str);
      }
    }
    // remoteDistributedSystems
    {
      Long key = FabricServerPrms.remoteDistributedSystems;
      Vector strs = tab.vecAtWild(key, index, null);
      if (strs != null) {
        for (Iterator it = strs.iterator(); it.hasNext();) {
          String str = tab.getString(key, it.next());
          if (str == null || str.equalsIgnoreCase(BasePrms.NONE)) {
            it.remove();
          }
        }
        if (strs.size() > 0) {
          // disallow with master-managed locators
          if (TestConfig.tab().booleanAt(Prms.manageLocatorAgents)) {
            String s = BasePrms.nameForKey(key) + " cannot be used when "
                     + BasePrms.nameForKey(Prms.manageLocatorAgents)
                     + "=true.  Use hydra client-managed locators.";
            throw new HydraConfigException(s);
          }
          fsd.setRemoteDistributedSystems(strs);
        }
      }
    }
    // defer resourceDir
    // saveSysDiskDir
    {
      Long key = FabricServerPrms.saveSysDiskDir;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setSaveSysDiskDir(bool);
    }
    // defer serverBindAddress
    // serverGroups
    {
      Long key = FabricServerPrms.serverGroups;
      Vector strs = tab.vecAtWild(key, index, null);
      if (strs != null) { // toss any occurrences of "default"
        for (Iterator i = strs.iterator(); i.hasNext();) {
          String str = tab.getString(key, i.next());
          if (str == null || str.equalsIgnoreCase(BasePrms.NONE)) {
            i.remove();
          }
        }
        if (strs.size() != 0) {
          fsd.setServerGroups(new ArrayList(strs));
        }
      }
    }
    // socketBufferSize
    {
      Long key = FabricServerPrms.socketBufferSize;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_SOCKET_BUFFER_SIZE;
      }
      fsd.setSocketBufferSize(i);
    }
    // socketLeaseTime
    {
      Long key = FabricServerPrms.socketLeaseTime;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_SOCKET_LEASE_TIME;
      }
      fsd.setSocketLeaseTime(i);
    }
    // defer statisticArchiveFile
    // statisticSampleRate
    {
      Long key = FabricServerPrms.statisticSampleRate;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_STATISTIC_SAMPLE_RATE;
      }
      fsd.setStatisticSampleRate(i);
    }
    // statisticSamplingEnabled
    {
      Long key = FabricServerPrms.statisticSamplingEnabled;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.TRUE; // overrides product default
      }
      fsd.setStatisticSamplingEnabled(bool);
    }
    // defer sysDir
    // defer sysDiskDir
    // sysDiskDirBases (from sysDiskDirBaseMapFileName)
    {
      Long key = FabricServerPrms.sysDiskDirBaseMapFileName;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str != null && !str.equalsIgnoreCase(BasePrms.NONE)) {
        Map<String,List<String>> bases = getSysDiskDirBases(key, str);
        fsd.setSysDiskDirBases(bases);
        initSysDiskDirBlackboard(bases);
      }
    }
    // tableDefaultPartitioned
    {
      Long key = FabricServerPrms.tableDefaultPartitioned;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.TRUE; // overrides product default
      }
      fsd.setTableDefaultPartitioned(bool);
    }
    // tcpPort
    {
      Long key = FabricServerPrms.tcpPort;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_TCP_PORT;
      }
      fsd.setTcpPort(i);
    }
    // udpFragmentSize
    {
      Long key = FabricServerPrms.udpFragmentSize;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_UDP_FRAGMENT_SIZE;
      }
      fsd.setUdpFragmentSize(i);
    }
    // udpRecvBufferSize
    {
      Long key = FabricServerPrms.udpRecvBufferSize;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_UDP_RECV_BUFFER_SIZE;
      }
      fsd.setUdpRecvBufferSize(i);
    }
    // udpSendBufferSize
    {
      Long key = FabricServerPrms.udpSendBufferSize;
      Integer i = tab.getInteger(key, tab.getWild(key, index, null));
      if (i == null) {
        i = DistributionConfig.DEFAULT_UDP_SEND_BUFFER_SIZE;
      }
      fsd.setUdpSendBufferSize(i);
    }
    // useExistingSysDiskDir
    {
      Long key = FabricServerPrms.useExistingSysDiskDir;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setUseExistingSysDiskDir(bool);
    }
    // useGenericSysDiskDir
    {
      Long key = FabricServerPrms.useGenericSysDiskDir;
      Boolean bool = tab.getBoolean(key, tab.getWild(key, index, null));
      if (bool == null) {
        bool = Boolean.FALSE;
      }
      fsd.setUseGenericSysDiskDir(bool);
    }
    return fsd;
  }

//------------------------------------------------------------------------------
// clientNames configuration support
//------------------------------------------------------------------------------

  /**
   * Returns the client names for the given string.
   */
  private static List<String> getClientNames(Vector strs, Long key,
                                             List<String> usedClients,
                                             ConfigHashtable tab) {
    // make sure there are no duplicates
    for (Iterator i = strs.iterator(); i.hasNext();) {
      String str = tab.getString(key, i.next());
      if (usedClients.contains(str)) {
        String s = BasePrms.nameForKey(key) + " contains duplicate: " + str;
        throw new HydraConfigException(s);
      }
      usedClients.add(str);
    }
    return new ArrayList(strs);
  }

//------------------------------------------------------------------------------
// Disk directory configuration support
//------------------------------------------------------------------------------

  /**
   * Returns the mapping of hosts to disk directory bases from the given file,
   * or null if no mapping is included in the file.
   * @throws HydraConfigException if the file is not found.
   */
  private static Map<String,List<String>> getSysDiskDirBases(Long key,
                                                             String str) {
    String fn = EnvHelper.expandEnvVars(str);
    try {
      List<String> tokens = FileUtil.getTextAsTokens(fn);
      if (tokens == null || tokens.size() == 0) {
        return null;
      } else {
        return getSysDiskDirBases(key, new ArrayList(tokens));
      }
    } catch (IOException e) {
      String s = "Problem reading " + BasePrms.nameForKey(key) + ": " + fn;
      throw new HydraConfigException(s, e);
    }
  }

  /**
   * Returns the mapping of hosts to disk directory bases from the given list,
   * which is expected to provide a list of hosts where each host is followed
   * by the bases for that host. Returns null if there are no bases.
   *
   * @throws HydraConfigException if the value is malformed or a field contains
   *         an illegal value or type.
   */
  private static Map<String,List<String>> getSysDiskDirBases(Long key,
                                                       List<String> vals) {
    if (vals == null || vals.size() == 0) {
      return null; // no tokens in disk dir base map file
    }
    SortedMap<String,List<String>> bases = new TreeMap();
    String currentHost = null;
    List<String> currentBases = null;
    for (String val : vals) {
      if (val.indexOf("/") == -1 && val.indexOf("\\") == -1) {
        // this is a host token
        currentHost = val;
        currentBases = new ArrayList();
        bases.put(currentHost, currentBases);
      } else {
        // this is a path token
        currentBases = bases.get(currentHost);
        if (currentBases == null) {
          String s = BasePrms.nameForKey(key) + " missing host for: " + val;
          throw new HydraConfigException(s);
        } else {
          currentBases.add(val);
        }
      }
    }
    for (String host : bases.keySet()) {
      if (bases.get(host).size() == 0) {
        String s = BasePrms.nameForKey(key) + " missing bases for: " + host;
        throw new HydraConfigException(s);
      }
    }
    return bases;
  }

  /**
   * Initializes the shared map with the current index for each host.
   */
  private static void initSysDiskDirBlackboard(Map<String,List<String>> bases) {
    SharedMap map = SysDiskDirBlackboard.getInstance().getSharedMap();
    for (String host : bases.keySet()) {
      map.put(host, Integer.valueOf(0));
    }
  }

//------------------------------------------------------------------------------
// Distributed system id configuration support
//------------------------------------------------------------------------------

  /**
   * Extracts a distributed system id from a distributed system name of the
   * form "dsname_dsid".  For example, "ds_4" would return 4.  Returns null
   * if the name is not in this form.
   */
  private static Integer toDSID(String dsName) {
    String dsid = dsName.substring(dsName.indexOf("_") + 1, dsName.length());
    try {
      return Integer.parseInt(dsid);
    } catch (NumberFormatException e) {
      return null;
    }
  }

//------------------------------------------------------------------------------
// Fabric security configuration support
//------------------------------------------------------------------------------

  /**
   * Returns the fabric security description for the given string.
   * @throws HydraConfigException if the given string is not listed in {@link
   *         FabricSecurityPrms#names}.
   */
  private static FabricSecurityDescription getFabricSecurityDescription(
                               String str, Long key, GfxdTestConfig sconfig) {
    FabricSecurityDescription fsd = sconfig.getFabricSecurityDescription(str);
    if (fsd == null) {
      String s = BasePrms.nameForKey(key) + " not found in "
               + BasePrms.nameForKey(FabricSecurityPrms.names) + ": " + str;
      throw new HydraConfigException(s);
    } else {
      return fsd;
    }
  }
}
