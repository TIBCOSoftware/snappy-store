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

package com.pivotal.gemfirexd;

import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;

/**
 * Base interface defining common peer services provided by {@link FabricServer}
 * and {@link FabricLocator}.
 * 
 * @author swale
 */
public interface FabricService {

  // keep this same as NetworkServerControl#DEFAULT_PORTNUMBER
  // must be hard coded because this code doesn't have access at compile time
  /** the default port for network server */
  final static int NETSERVER_DEFAULT_PORT = 1527;

  /**
   * Shutdown boolean property to enable/disable shutting down of all network
   * servers in {@link #stop(Properties)}. Default is true.
   */
  final static String STOP_NETWORK_SERVERS = "stop-netservers";

  /**
   * Status of the service enumerating different states the service can be in.
   * 
   * <CENTER> <B><a name="states">Different states of service</a></B> </CENTER>.
   * 
   * <dl>
   * <dt>{@link State#UNINITIALIZED}</dt>
   * <dd><U>Description</U>: This is the initial state of the status when
   * service on this peer is yet to be started or the start was unsuccessful.</dd>
   * </dl>
   * 
   * <dl>
   * <dt>{@link State#STARTING}</dt>
   * <dd><U>Description</U>: Service was started on the peer but is still in the
   * process of booting up its driver and joining the distributed system..</dd>
   * </dl>
   * 
   * <dl>
   * <dt>{@link State#WAITING}</dt>
   * <dd><U>Description</U>: This state indicates that the peer is waiting for
   * other offline members of the distributed system to come online.</dd>
   * </dl>
   * 
   * <dl>
   * <dt>{@link State#RUNNING}</dt>
   * <dd><U>Description</U>: Indicates that the peer is ready to service JDBC
   * connections in the application program.
   * </dl>
   * 
   * <dl>
   * <dt>{@link State#STOPPING}</dt>
   * <dd><U>Description</U>: The peer has initiated the shutdown process (
   * {@link FabricService#stop}). No new JDBC connections will be allowed
   * and existing ones will be closed. Current activity on the connection may
   * raise CacheClosedException.
   * </dl>
   * 
   * <dl>
   * <dt>{@link State#STOPPED}</dt>
   * <dd><U>Description</U>: The peer has successfully disconnected from
   * distributed system. No new JDBC connections will be allowed and existing
   * will be closed. Current activity on the connection may raise
   * CacheClosedException.
   * </dl>
   * 
   * <dl>
   * <dt>{@link State#RECONNECTING}</dt>
   * <dd><U>Description</U>: The peer has lost its connection to the
   * distributed system and is reconnecting.
   * </dl>
   * 
   */
  enum State {
    // the enumeration order represents the state transitions allowed.
    // e.g. one cannot move back to STARTING if current state is RUNNING
    // see FabricServiceImpl.startImpl
    UNINITIALIZED, STARTING, WAITING, STANDBY, RUNNING, STOPPING, STOPPED, RECONNECTING
  }

  /**
   * Start listening for network clients on a given host/port address. This can
   * be called multiple times to bind to multiple network interfaces.
   * 
   * By default this starts DRDA server ({@link #startDRDAServer} unless
   * {@link ClientSharedUtils#USE_THRIFT_AS_DEFAULT_PROP} system property is
   * set in which case it start a Thrift server {@link #startThriftServer}
   * 
   * @param bindAddress
   *          The host name or IP address to bind the network server. If this is
   *          null then use the "bind-address" GemFire property and if found
   *          listens only on that address, else binds to local loopback
   *          address.
   * @param port
   *          The port to bind the network server. A value &lt;= 0 will cause
   *          this to use the default port {@link #NETSERVER_DEFAULT_PORT}.
   * @param networkProperties
   *          <a href=
   *          "http://community.gemstone.com/display/sqlfabric/Connection+Attributes"
   *          >network server properties</a>.
   * @throws SQLException
   */
  NetworkInterface startNetworkServer(String bindAddress, int port,
      Properties networkProperties) throws SQLException;

  /**
   * Start listening for Thrift clients on a given host/port address. This can
   * be called multiple times to bind to multiple network interfaces.
   * 
   * @param bindAddress
   *          The host name or IP address to bind the network server. If this is
   *          null then use the "bind-address" GemFire property and if found
   *          listens only on that address, else binds to local loopback
   *          address.
   * @param port
   *          The port to bind the network server. A value &lt;= 0 will cause
   *          this to use the default port {@link #NETSERVER_DEFAULT_PORT}.
   * @param networkProperties
   *          <a href=
   *          "http://community.gemstone.com/display/sqlfabric/Connection+Attributes"
   *          >network server properties</a>.
   * @throws SQLException
   */
  NetworkInterface startThriftServer(String bindAddress, int port,
      Properties networkProperties) throws SQLException;

  /**
   * Start listening for DRDA clients on a given host/port address. This can
   * be called multiple times to bind to multiple network interfaces.
   * 
   * @param bindAddress
   *          The host name or IP address to bind the DRDA server. If this is
   *          null then use the "bind-address" GemFire property and if found
   *          listens only on that address, else binds to local loopback
   *          address.
   * @param port
   *          The port to bind the DRDA server. A value &lt;= 0 will cause
   *          this to use the default port {@link #NETSERVER_DEFAULT_PORT}.
   * @param networkProperties
   *          <a href=
   *          "http://community.gemstone.com/display/sqlfabric/Connection+Attributes"
   *          >network server properties</a>.
   * @throws SQLException
   */
  NetworkInterface startDRDAServer(String bindAddress, int port,
      Properties networkProperties) throws SQLException;

  /**
   * Stop all the {@link NetworkInterface}s started using
   * {@link #startNetworkServer(String, int, Properties)}.
   */
  void stopAllNetworkServers();

  /**
   * Get a collection of all the {@link NetworkInterface}s started so far using
   * {@link #startNetworkServer(String, int, Properties)}.
   */
  List<NetworkInterface> getAllNetworkServers();

  /**
   * Disconnect current virtual machine from distributed system. This should be
   * called once for every startup of the service.
   * 
   * @param shutdownCredentials
   *          Can optionally be null if Authentication is switched off,
   *          otherwise system user authentication credentials with which this
   *          instance was started.
   * @throws SQLException
   */
  void stop(Properties shutdownCredentials) throws SQLException;

  /**
   * Returns the status of this instance.
   * 
   * @return {@linkplain State}
   */
  State status();

  /**
   * Used to display the status of this service in SYS.MEMBERS.
   * Normally same as {@link #status()} but can be overridden
   * by child classes if they have other components to initialize
   * before showing the service as running.
   */
  default State serviceStatus() {
    return status();
  }

  /**
   * Test to see whether the FabricService is in the process of reconnecting
   * and recreating the cache after it has been removed from the system
   * by other members.<p>
   * This will also return true if the FabricService has finished reconnecting.
   * 
   * @return true if the FabricService is attempting to reconnect or has finished reconnecting
   */
  public boolean isReconnecting();

  /**
   * Wait for the FabricService to finish reconnecting to the distributed system.
   * @param time amount of time to wait, or -1 to wait forever
   * @param units
   * @return true if the cache was reconnected
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public boolean waitUntilReconnected(long time, TimeUnit units) throws InterruptedException;
  
  /**
   * Force the FabricService to stop reconnecting.  If the service
   * is currently connected this will cause it to stop.
   * 
   */
  public void stopReconnecting();
  

}
