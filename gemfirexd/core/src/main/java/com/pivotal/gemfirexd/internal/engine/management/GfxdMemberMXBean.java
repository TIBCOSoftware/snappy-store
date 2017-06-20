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
package com.pivotal.gemfirexd.internal.engine.management;

import java.beans.ConstructorProperties;
import java.io.Serializable;

/**
 * 
 * @author Abhishek Chaudhari
 * @since gfxd 1.0
 */
public interface GfxdMemberMXBean {
  // [A] MBean Attributes
  // 0. Basic
  /**
   * Name of the member
   */
  String getName();

  /**
   * Id of the member
   * 
   */
  String getId();

  /**
   * Server groups associated with member
   * 
   */
  String[] getGroups();

  /**
   * True if member is data store
   * 
   */
  boolean isDataStore();

  /**
   * True if member is locator
   * 
   */
  boolean isLocator();

  /**
   * True if member is lead
   *
   */
  boolean isLead();

  /**
   * Represents network server client connection stats. It has attributes e.g.
   * connection type (connectionStatsType), open connections
   * (connectionsOpened), closed connections (connectionsClosed), connections
   * attempted (connectionsAttempted ), Failed connections (connectionsFailed ),
   * connections life time (connectionLifeTime), open connections
   * (connectionsOpen) and idle connections (connectionsIdle)
   * 
   */
  NetworkServerConnectionStats getNetworkServerClientConnectionStats();

  /**
   * Represents network server peer connection stats. It has attributes e.g.
   * connection type (connectionStatsType), open connections
   * (connectionsOpened), closed connections (connectionsClosed), connections
   * attempted (connectionsAttempted ), Failed connections (connectionsFailed ),
   * connections life time (connectionLifeTime) and active connections
   * (connectionsActive)
   * 
   */
  NetworkServerConnectionStats getNetworkServerPeerConnectionStats();

  /**
   * Represents network server nested connection stats. It has attributes e.g.
   * connection type (connectionStatsType), open connections
   * (connectionsOpened), closed connections (connectionsClosed), active
   * connections (connectionsActive)
   * 
   */
  NetworkServerNestedConnectionStats getNetworkServerNestedConnectionStats();

  /**
   * Represents network server internal connection stats i.e. the connections
   * which GFXD opens internally. It has attributes e.g. connection type
   * (connectionStatsType), open connections (connectionsOpened), closed
   * connections (connectionsClosed), active connections (connectionsActive)
   * 
   */
  NetworkServerNestedConnectionStats getNetworkServerInternalConnectionStats();

  /**
   * Procedure calls completed for a member
   * 
   */
  int getProcedureCallsCompleted();

  /**
   * Procedure call in progress for a member
   * 
   */
  int getProcedureCallsInProgress();

  // 2. CachePerfStats
  // TabularData getCachePerfStats(); // TODO Abhishek - Name & exact stats TBD
  // - use CachePerfStats of the Cache - not Region level

  // [B] MBean Operations

  /**
   * Retrieves meta data of a member e.g. "id", "kind", "status", "hostData",
   * "isElder", "ipAddress", "host", "pid", "port", "roles", "netservers",
   * "locator", "serverGroups", "managerInfo", "systemProps", "gemFireProps",
   * "bootProps"
   * 
   */
  GfxdMemberMetaData fetchMetadata(); // GFXD VTI sys.members

  /**
   * Gets eviction percentage i.e. percentage of heap space that triggers
   * members of one or more GemFireXD server groups to perform LRU eviction on
   * tables that are configured for eviction.
   * 
   */
  float fetchEvictionPercent(); // GFXD Stored procedure

  /**
   * Sets eviction percentage i.e. percentage of heap space that triggers
   * members of one or more GemFireXD server groups to perform LRU eviction on
   * tables that are configured for eviction.
   * 
   */
  void updateEvictionPercent(float newValue);

  /**
   * Gets critical percentage which is intended to allow the eviction and GC
   * work to catch up
   * 
   */
  float fetchCriticalPercent(); // GFXD Stored procedure

  /**
   * Sets critical percentage which is intended to allow the eviction and GC
   * work to catch up
   * 
   */
  void updateCriticalPercent(float newValue);

  /**
   * Identifies dead lock in DS
   * 
   */
  String detectDeadlocks(); // GemFire utility OR
                            // ThreadMXBean.findDeadlockedThreads()/findMonitorDeadlockedThreads()

  final class GfxdMemberMetaData implements Serializable {
    private static final long serialVersionUID = -2937997948271422933L;

    private String id;
    private String kind;
    private String status;
    private String hostData;
    private String isElder;
    private String ipAddress;
    private String host;
    private String pid;
    private String port;
    private String roles;
    private String netservers;
    private String thriftServers;
    private String locator;
    private String serverGroups;
    private String managerInfo;
    private String systemProps;
    private String gemFireProps;
    private String bootProps;

    @ConstructorProperties(value = { "id", "kind", "status", "hostData",
        "isElder", "ipAddress", "host", "pid", "port", "roles", "netservers",
        "thriftServers", "locator", "serverGroups", "managerInfo",
        "systemProps", "gemFireProps", "bootProps" })
    public GfxdMemberMetaData(String id, String kind, String status,
        String hostData, String isElder, String ipAddress, String host,
        String pid, String port, String roles, String netservers,
        String thriftServers, String locator, String serverGroups,
        String managerInfo, String systemProps, String gemFireProps,
        String bootProps) {
      this.id = id;
      updateMetadata(kind, status, hostData, isElder, ipAddress, host, pid,
          port, roles, netservers, thriftServers, locator, serverGroups,
          managerInfo, systemProps, gemFireProps, bootProps);
    }

    public void updateMetadata(String kind, String status, String hostData,
        String isElder, String ipAddress, String host, String pid, String port,
        String roles, String netservers, String thriftServers, String locator,
        String serverGroups, String managerInfo, String systemProps,
        String gemFireProps, String bootProps) {
      this.kind = kind;
      this.status = status;
      this.hostData = hostData;
      this.isElder = isElder;
      this.ipAddress = ipAddress;
      this.host = host;
      this.pid = pid;
      this.port = port;
      this.roles = roles;
      this.netservers = netservers;
      this.thriftServers = thriftServers;
      this.locator = locator;
      this.serverGroups = serverGroups;
      this.managerInfo = managerInfo;
      this.systemProps = systemProps;
      this.gemFireProps = gemFireProps;
      this.bootProps = bootProps;
    }

    /**
     * @return the id
     */
    public String getId() {
      return id;
    }

    /**
     * @param id
     *          the id to set
     */
    public void setId(String id) {
      this.id = id;
    }

    /**
     * @return the kind
     */
    public String getKind() {
      return kind;
    }

    /**
     * @param kind
     *          the kind to set
     */
    public void setKind(String kind) {
      this.kind = kind;
    }

    /**
     * @return the status
     */
    public String getStatus() {
      return status;
    }

    /**
     * @param status
     *          the status to set
     */
    public void setStatus(String status) {
      this.status = status;
    }

    /**
     * @return the hostData
     */
    public String getHostData() {
      return hostData;
    }

    /**
     * @param hostData
     *          the hostData to set
     */
    public void setHostData(String hostData) {
      this.hostData = hostData;
    }

    /**
     * @return the isElder
     */
    public String getIsElder() {
      return isElder;
    }

    /**
     * @param isElder
     *          the isElder to set
     */
    public void setIsElder(String isElder) {
      this.isElder = isElder;
    }

    /**
     * @return the ipAddress
     */
    public String getIpAddress() {
      return ipAddress;
    }

    /**
     * @param ipAddress
     *          the ipAddress to set
     */
    public void setIpAddress(String ipAddress) {
      this.ipAddress = ipAddress;
    }

    /**
     * @return the host
     */
    public String getHost() {
      return host;
    }

    /**
     * @param host
     *          the host to set
     */
    public void setHost(String host) {
      this.host = host;
    }

    /**
     * @return the pid
     */
    public String getPid() {
      return pid;
    }

    /**
     * @param pid
     *          the pid to set
     */
    public void setPid(String pid) {
      this.pid = pid;
    }

    /**
     * @return the port
     */
    public String getPort() {
      return port;
    }

    /**
     * @param port
     *          the port to set
     */
    public void setPort(String port) {
      this.port = port;
    }

    /**
     * @return the roles
     */
    public String getRoles() {
      return roles;
    }

    /**
     * @param roles
     *          the roles to set
     */
    public void setRoles(String roles) {
      this.roles = roles;
    }

    /**
     * @return the netservers
     */
    public String getNetservers() {
      return netservers;
    }

    /**
     * @param netservers
     *          the netservers to set
     */
    public void setNetservers(String netservers) {
      this.netservers = netservers;
    }

    /**
     * @return the thriftServers
     */
    public String getThriftServers() {
      return thriftServers;
    }

    /**
     * @param thriftServers
     *          the thriftServers to set
     */
    public void setThriftServers(String thriftServers) {
      this.thriftServers = thriftServers;
    }

    /**
     * @return the locator
     */
    public String getLocator() {
      return locator;
    }

    /**
     * @param locator
     *          the locator to set
     */
    public void setLocator(String locator) {
      this.locator = locator;
    }

    /**
     * @return the serverGroups
     */
    public String getServerGroups() {
      return serverGroups;
    }

    /**
     * @param serverGroups
     *          the serverGroups to set
     */
    public void setServerGroups(String serverGroups) {
      this.serverGroups = serverGroups;
    }

    /**
     * @return the managerInfo
     */
    public String getManagerInfo() {
      return managerInfo;
    }

    /**
     * @param managerInfo
     *          the managerInfo to set
     */
    public void setManagerInfo(String managerInfo) {
      this.managerInfo = managerInfo;
    }

    /**
     * @return the systemProps
     */
    public String getSystemProps() {
      return systemProps;
    }

    /**
     * @param systemProps
     *          the systemProps to set
     */
    public void setSystemProps(String systemProps) {
      this.systemProps = systemProps;
    }

    /**
     * @return the gemFireProps
     */
    public String getGemFireProps() {
      return gemFireProps;
    }

    /**
     * @param gemFireProps
     *          the gemFireProps to set
     */
    public void setGemFireProps(String gemFireProps) {
      this.gemFireProps = gemFireProps;
    }

    /**
     * @return the bootProps
     */
    public String getBootProps() {
      return bootProps;
    }

    /**
     * @param bootProps
     *          the bootProps to set
     */
    public void setBootProps(String bootProps) {
      this.bootProps = bootProps;
    }
  }

  /**
   * gets active threads
   * 
   */
  String[] activeThreads();

  /**
   * gets stack for specified thread id
   * 
   */
  String[] getStack(String Id);

}
