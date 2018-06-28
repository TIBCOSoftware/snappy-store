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

import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.cache.client.internal.BridgeServerLoadMessage;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.cache.server.ServerLoad;
import com.gemstone.gemfire.cache.server.ServerMetrics;
import com.gemstone.gemfire.cache.server.internal.ConnectionCountProbe;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisee;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem.DisconnectListener;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem.ReconnectListener;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.BridgeServerAdvisor;
import com.gemstone.gemfire.internal.cache.BridgeServerAdvisor.BridgeServerProfile;
import com.gemstone.gemfire.internal.cache.CacheServerLauncher;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.Status;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.FabricLocator;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.NetworkInterface;
import com.pivotal.gemfirexd.NetworkInterface.ConnectionListener;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.diag.SessionsVTI;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.SecurityUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.conn.ConnectionSignaller;
import com.pivotal.gemfirexd.internal.engine.sql.conn.ConnectionState;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.jdbc.DRDAServerStarter;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.services.monitor.FileMonitor;
import com.pivotal.gemfirexd.internal.shared.common.reference.MessageId;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.jdbc.AutoloadedDriver;
import io.snappydata.thrift.ServerType;
import io.snappydata.thrift.common.SocketParameters;
import io.snappydata.thrift.common.ThriftUtils;
import io.snappydata.thrift.server.SnappyDataServiceImpl;
import io.snappydata.thrift.server.SnappyThriftServer;
import org.apache.thrift.TProcessor;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation of the fabric service startup.
 *
 * @author soubhikc
 *
 */
public abstract class FabricServiceImpl implements FabricService {

  private static final String driver = "io.snappydata.jdbc.EmbeddedDriver";

  protected static final String fabapi = "FabricServiceAPI";

  protected final Logger logger = LoggerFactory.getLogger(getClass());

  protected volatile State serverstatus = State.UNINITIALIZED;
  protected volatile State previousServerStatus = State.UNINITIALIZED;

  protected volatile State networkserverstatus = State.UNINITIALIZED;

  protected final HashMap<Object, Object> sysProps =
      new HashMap<>();

  protected String userName;
  protected String password;
  // bind address for network server
  private String clientBindAddress = null;
  // port for network server
  private int clientPort = -1;
  // properties for network server
  private Properties clientProperties = null;

  protected final HashSet<NetworkInterface> allNetServers = new HashSet<>();

  protected FileMonitor monitorlite;

  /**
   * the restarter reboots the FabricServiceImpl if the distributed system reconnects
   */
  private ServerRestarter restarter;

  /**
   * The singleton instance of {@link FabricServer} or {@link FabricLocator}.
   */
  private static volatile FabricService theInstance;

  public static FabricService getInstance() {
    return theInstance;
  }

  public static void setInstance(final FabricService instance) {
    theInstance = instance;
  }

  public Properties getSecurityPropertiesFromRestarter() {
    if (restarter != null) {
      return SecurityUtils.transformCredentialsForAutoReconnect(restarter.bootProperties);
    }
    return null;
  }

  public boolean isServer() {
    return false;
  }

  public String getProtocol() {
    return Attribute.PROTOCOL;
  }

  public String getNetProtocol() {
    return Attribute.DNC_PROTOCOL;
  }

  protected void startImpl(Properties bootProperties, boolean ignoreIfStarted,
      boolean isLocator) throws SQLException {

    long startTimeMillis = System.currentTimeMillis();
    // check that "this" must be locked
    Assert.assertHoldsLock(this, true);

    boolean existingInstance = false;
    if (bootProperties == null) {
      bootProperties = new Properties();
    }

    monitorlite = FabricServiceUtils.initCachedMonitorLite(bootProperties,
        sysProps);

    // clear any old instance lying around first
    GemFireCacheImpl oldcache = GemFireCacheImpl.getInstance();
    InternalDistributedSystem oldsys;
    if (oldcache != null && !oldcache.isClosed()
        || ((oldsys = InternalDistributedSystem.getConnectedInstance()) != null
            && oldsys.isConnected())) {
      // try to stop existing instance first
      try {
        // force status as RUNNING to try and stop
        if (ignoreIfStarted) {
          existingInstance = true;
        }
        else {
          this.serverstatus = State.RUNNING;
          final Properties stopProperties = new Properties();
          stopProperties.putAll(bootProperties);
          this.stop(stopProperties);
          notifyStop(this.isReconnecting());
          if (this.isReconnecting()) {
            setInstance(this);

          }
        }
      } catch (Exception ex) {
        // ignore and fallback to cache close / DS disconnect
        oldcache = GemFireCacheImpl.getInstance();
        if (oldcache != null && !oldcache.isClosed()) {
          oldcache.close();
          notifyStop(isReconnecting());
        }
        else {
          oldsys = InternalDistributedSystem.getConnectedInstance();
          if (oldsys != null && oldsys.isConnected()) {
            oldsys.disconnect();
            notifyStop(isReconnecting());
          }
          else {
            if (ex instanceof SQLException) {
              throw (SQLException)ex;
            }
            throw (RuntimeException)ex;
          }
        }
      }
    }
    else if (this.serverstatus != State.UNINITIALIZED
        && this.serverstatus != State.STOPPED
        && this.serverstatus != State.RECONNECTING) {
      notifyStop(isReconnecting());
    }

    if (!existingInstance && serverstatus != State.UNINITIALIZED
        && serverstatus != State.STOPPED
        && serverstatus != State.RECONNECTING) {
      throw new IllegalStateException("unexpected server status = "
          + this.serverstatus);
    }

    this.serverstatus = State.STARTING;
    Connection jdbcConn = null;
    boolean success = false;

    try {
      Properties startupProps = FabricServiceUtils.preprocessProperties(
          bootProperties, monitorlite, sysProps, false);

      if (!existingInstance && GemFireXDUtils.TraceFabricServiceBoot) {
        monitorlite.dumpProperties((isLocator ? "GemFireXD locator"
            : "GemFireXD peer") + " boot properties ", bootProperties);
      }

      // remember if rebalance was specified but remove from the properties
      // since underlying GemFire layers do not understand it
      Object rebalanceObj = startupProps.remove(CacheServerLauncher.REBALANCE);
      boolean rebalance = rebalanceObj != null
          && "true".equalsIgnoreCase(rebalanceObj.toString());

      this.userName = startupProps
          .getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
      this.userName = this.userName == null ? startupProps
          .getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR)
          : this.userName;
      this.password = startupProps
          .getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);

      // following is mandatory property for fabapi.
      if(!startupProps.containsKey(GfxdConstants.PROPERTY_BOOT_INDICATOR)) {
        startupProps.setProperty(GfxdConstants.PROPERTY_BOOT_INDICATOR,
            GfxdConstants.BT_INDIC.FABRICAPI.toString());
      }

      registerDrivers();

      // safe as only 1 thread is expected to come in.
      final String protocol = getProtocol();
      // we dont  want query routing to happen from these connections
      if (!startupProps.contains(Attribute.ROUTE_QUERY)) {
        startupProps.setProperty(Attribute.ROUTE_QUERY, "false");
      }
      DriverManager.getDriver(protocol);

      jdbcConn = new AutoloadedDriver().connect(protocol, startupProps);

      final InternalDistributedSystem sys = InternalDistributedSystem
          .getConnectedInstance();
      if (sys == null || !sys.isConnected()) {
        throw new DistributedSystemDisconnectedException(
            "Failed to connect to the distributed system.");
      }
      GemFireXDUtils.initFlags();

      // start the rebalance factory if specified (#51584)
      GemFireCacheImpl cache;
      if (rebalance && !isLocator
          && (cache = GemFireCacheImpl.getInstance()) != null) {
        logger.info("Starting rebalance of buckets for the distributed system.");
        cache.getResourceManager().createRebalanceFactory().start();
      }

      if (existingInstance) {
        serverstatus = State.RUNNING;
      }
      success = true;
      if (this.restarter == null &&
          !sys.getConfig().getDisableAutoReconnect()) {
        registerServerRestarter(sys, bootProperties, ignoreIfStarted, isLocator);
      }
      registerForcedDisconnectListener(sys, bootProperties);
    } catch (SQLException sqle) {
      logger.error(sqle.getSQLState()
          + " error occurred while starting server : " + sqle);
      throw sqle;
    } finally {
      try {
        if (jdbcConn != null && !jdbcConn.isClosed()) {
          jdbcConn.close();
        }
      } catch (SQLException sqle) {
        // ignored
      }
      // if unsuccessful startup, move back to uninit for someone else to
      // try.
      if (serverstatus == State.STARTING) {
        serverstatus = State.UNINITIALIZED;
      }
      // clear the instance if startup failed
      if (!success) {
        theInstance = null;
      }
    }

    // register any auto-started network server
    registerAutoStartedNetworkServer();
    GemFireStore memStore = Misc.getMemStore();
    if (memStore != null) {
      memStore.getIndexPersistenceStats().endNodeUp(startTimeMillis);
    }
  }

  private void registerAutoStartedNetworkServer() throws SQLException {
    final Object sysServer = Monitor.getSystemModule(DRDAServerStarter.class
        .getName());
    if (sysServer != null) {
      networkserverstatus = State.STARTING;
      try {
        final NetworkInterfaceImpl netimpl = new DRDANetworkInterface(
            (DRDAServerStarter)sysServer);
        netimpl.internalStart(null);
      } catch (SQLException ex) {
        throw ex;
      } catch (Exception ex) {
        throw Util.javaException(ex);
      }
      // was the network server stopped due to reconnect?
      // relying on the fact that networkserverstatus will be in
      // STOPPED state (if it was started, otherwise should in UNINITIALIZED)
      // when reconnect happens
    } else if (clientPort > 0 && networkserverstatus == State.STOPPED
        && this.isReconnecting()) {
      this.startNetworkServer(clientBindAddress, clientPort, clientProperties);
    }
  }

  @Override
  public State status() {
    return serverstatus;
  }

  public void setShutdownAllIdentifier() {
    Misc.getMemStoreBooting().setShutdownAllMode();
  }

  @Override
  public synchronized void stop(Properties shutdownCredentials)
      throws SQLException {
    stopNoSync(shutdownCredentials,
        InternalDistributedSystem.getConnectedInstance(), false);
  }

  public void stopNoSync(Properties shutdownCredentials,
      InternalDistributedSystem sys, boolean forcedDisconnect) throws SQLException {

    /*
    if (serverstatus != State.RUNNING) {
      return;
    }
    */

    boolean stopNetServers = true;
    if (shutdownCredentials == null) {
      shutdownCredentials = new Properties();
    }
    else {
      stopNetServers = !"false".equalsIgnoreCase(shutdownCredentials
          .getProperty(STOP_NETWORK_SERVERS));
    }

    InternalDistributedSystem dsys = sys;
    if (dsys == null) {
      dsys = InternalDistributedSystem.getAnyInstance();
    }

    if (dsys == null || (!dsys.isConnected() && !forcedDisconnect)) {
      throw Util.newEmbedSQLException(SQLState.CLOUDSCAPE_SYSTEM_SHUTDOWN,
          null, new DistributedSystemDisconnectedException(
              "No connection to the distributed system"));
    }

    SQLException exception = null;

    try {

      // Skip identification in case the call is for shutdownAll, which will
      // happen from ShutdownRequest
      final GemFireStore store = GemFireStore.getBootedInstance();
      if(store == null || !store.isShutdownAll()) {
        authenticateShutdown(shutdownCredentials);
      }

      serverstatus = State.STOPPING;

      if (GemFireXDUtils.TraceFabricServiceBoot) {
        logger.info("Stopping the SnappyData instance.");
      }

      if (stopNetServers) {
        stopAllNetworkServers();
      }

      serviceShutdown();

      shutdownCredentials.setProperty(
          com.pivotal.gemfirexd.Attribute.SHUTDOWN_ATTR, Boolean.TRUE.toString());

      DriverManager.getConnection(getProtocol(), shutdownCredentials);
    } catch (SQLException ex) {
      if (ex.getSQLState().equals("XJ015")) {
        // clear the static instance
        this.userName = null;
        this.password = null;
        theInstance = null;
        return;
      }
      else {
        logger.warn("exception while shutting down " + ex);
        exception = ex;
      }
    }

    if (exception != null) {
      throw exception;
    }

    // check DS disconnected
    dsys = InternalDistributedSystem.getConnectedInstance();
    if (dsys != null && dsys.isConnected()) {
      throw new IllegalStateException("ds should be disconnected... ");
    }

    // clear cached monitorlite
    this.monitorlite = null;
    Monitor.clearCachedMonitorLite();

    // clear the static instance
    this.userName = null;
    this.password = null;
    theInstance = null;
  }

  protected void serviceShutdown() throws SQLException {
  }

  @Override
  public boolean isReconnecting() {
    return this.restarter != null && this.restarter.isReconnecting();
  }

  @Override
  public boolean waitUntilReconnected(long time, TimeUnit units)
      throws InterruptedException {
    return this.restarter != null && this.restarter.waitUntilReconnected(
        time, units);
  }

  @Override
  public void stopReconnecting() {
    if (this.restarter != null) {
      this.restarter.stopReconnecting();
    }
  }

  protected void reconnecting() {
//    (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("fabricservice: setting state to RECONNECTING");
    serverstatus = State.RECONNECTING;
  }


  /**
   * Authenticate the user before taking any action. Once shutdown is initiated,
   * we cannot fail with expected exceptions because network interface would
   * have already been closed.
   *
   * Unlike derby, we don't have n/w user operating while the database goes
   * down so we shutdown n/w interfaces first.
   *
   * Also, n/w interfaces can be brought down individually.
   */
  protected final void authenticateShutdown(Properties shutdownCredentials)
      throws SQLException {

    // this is an internal flag to bypass authentication. we don't user to have
    // it anywhere.
    if (shutdownCredentials.getProperty(
        GfxdConstants.PROPERTY_BOOT_INDICATOR) != null) {
      SanityManager.THROWASSERT("boot indicator cannot be user supplied.");
    }

    AuthenticationService authService = (AuthenticationService)Monitor
        .findServiceModule(Misc.getMemStore().getDatabase(),
            AuthenticationService.MODULE,
            GfxdConstants.PEER_AUTHENTICATION_SERVICE);

    if (authService == null) {
      return;
    }
    // doing authentication before n/w server shutdown.
    // taken from InternalDriver#connect...
    String failure;
    if ((failure = authService.authenticate(null, shutdownCredentials)) != null) {
      throw Util.generateCsSQLException(SQLState.NET_CONNECT_AUTH_FAILED,
          MessageService.getTextMessage(MessageId.AUTH_INVALID, failure));
    }
  }

  /**
   * Needed to do this because under any exceptional condition we need to clear
   * the system properties that was promoted by this api.
   *
   * Under success condition, stop will clear these properties.
   */
  protected void handleThrowable(Throwable t) throws SQLException {

    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("Handling Throwable during startup", t);
    }

    FabricServiceUtils.clearSystemProperties(monitorlite, sysProps);

    if (t instanceof SQLException) {
      throw (SQLException)t;
    }
    else if (t instanceof Error) {
      throw (Error)t;
    }
    else {
      throw GemFireXDRuntimeException.newRuntimeException(
          "GemFireXD:FabricServer#start exception ... ", t);
    }
  }

  /**
   * This method invoked from GemFire to notify waiting for another JVM to
   * initialize for disk GII.
   *
   * NOTE: It is deliberately not synchronized since it can be invoked by a
   * thread other than the booting thread itself which may be stuck waiting for
   * disk region initialization.
   */
  public void notifyWaiting(String regionPath,
      Set<PersistentMemberID> membersToWaitFor, Set<Integer> missingBuckets,
      PersistentMemberID myId, String message) {
    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("Accepting WAITING notification" +
          (message != null ? ": " + message : ""));
    }
    if (this.serverstatus != State.WAITING) {
      this.previousServerStatus = this.serverstatus;
    }
    this.serverstatus = State.WAITING;
    notifyWaitingInLauncher(regionPath, membersToWaitFor, missingBuckets, myId,
            message);
  }

  public static void notifyWaitingInLauncher(String regionPath,
      Set<PersistentMemberID> membersToWaitFor, Set<Integer> missingBuckets,
      PersistentMemberID myId, String message) {
    // if started from command-line then change the status in the file too
    CacheServerLauncher launcher = CacheServerLauncher.getCurrentInstance();
    if (launcher != null) {
      launcher.setWaitingStatus(regionPath, membersToWaitFor, missingBuckets,
          myId, message);
    }
  }

  /**
   * Only invoked to change the WAITING state to previous (STARTING/STANDBY).
   * Actual RUNNING status in launcher should only be set by launcher code
   * itself when it has started all the components (SNAP-1960).
   */
  protected void notifyRunningInLauncher(int stateIfWaiting) {
    CacheServerLauncher launcher = CacheServerLauncher.getCurrentInstance();
    if (launcher != null) {
      launcher.running(Misc.getDistributedSystem(), stateIfWaiting);
    }
  }

  /**
   * This method invoked from GemFire to end notify waiting for another JVM to
   * initialize for disk GII after a previous call to {@link #notifyWaiting}.
   *
   * NOTE: It is deliberately not synchronized since it can be invoked by a
   * thread other than the booting thread itself which may be stuck waiting for
   * disk region initialization.
   */
  public void endNotifyWaiting() {
    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("Accepting END WAITING notification with previous status " +
          this.previousServerStatus);
    }
    final State previousStatus = this.previousServerStatus;
    this.previousServerStatus = State.UNINITIALIZED;
    switch (previousStatus) {
      case STARTING:
        notifyRunningInLauncher(Status.STARTING);
        break;
      case STANDBY:
        notifyRunningInLauncher(Status.STANDBY);
        break;
      case RUNNING:
        break;
      default:
        return;
    }
    this.serverstatus = previousStatus;
  }

  /**
   * This method invoked from GemFireStore to notify booting up through
   * DriverManager.getConnection() instead of FabricServer api.
   */
  public void notifyRunning() {
    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("Accepting RUNNING notification");
    }
    serverstatus = State.RUNNING;
  }

  /**
   * This method invoked from GemFireStore to notify booting up through
   * DriverManager.getConnection() instead of FabricServer api.
   */
  public void notifyStop(boolean reconnecting) {
    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("Accepting STOPPED notification");
    }
    serverstatus = reconnecting ? State.RECONNECTING : State.STOPPED;
    FabricServiceUtils.clearSystemProperties(monitorlite, sysProps);
  }

  private NetworkInterface startNetworkServerImpl(String bindAddress, int port,
      Properties networkProperties, boolean thriftServer, String serverType)
      throws SQLException {

    if (serverstatus != State.RUNNING
        && GemFireStore.getBootedInstance() == null) {
      throw new IllegalStateException("server not started, cannot start "
          + "network interface. serverstatus = " + serverstatus);
    }

    networkserverstatus = State.STARTING;

    if (networkProperties == null) {
      networkProperties = new Properties();
    }

    synchronized (this) {
      if (this.monitorlite == null) {
        monitorlite = FabricServiceUtils.initCachedMonitorLite(
            networkProperties, sysProps);
      }
    }

    if (bindAddress == null && !thriftServer) {
      bindAddress = (String)networkProperties
          .remove(com.pivotal.gemfirexd.Property.DRDA_PROP_HOSTNAME);
    }
    // check if user/password passed explicitly
    String user = networkProperties
        .getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
    user = user == null ? networkProperties
        .getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : user;
    if (user != null) {
      synchronized (this) {
        this.userName = user;
        this.password = networkProperties
            .getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
      }
    }
    if (port <= 0) {
      port = NETSERVER_DEFAULT_PORT;
    }

    final InetAddress listenAddress = getListenAddress(bindAddress);

    assert listenAddress != null;

    if (port <= 1 || port >= 65535) {
      throw new IllegalArgumentException(
          "Allowed port range is between 1 to 65535 (excluding limits)");
    }

    logger.info("Starting " + serverType + " on: " + listenAddress +
        '[' + port + ']');

    this.clientPort = port;
    this.clientBindAddress = bindAddress;
    this.clientProperties = new Properties();
    this.clientProperties.putAll(networkProperties);

    final NetworkInterfaceImpl netImpl = thriftServer
        ? new ThriftNetworkInterface(listenAddress, port)
        : new DRDANetworkInterface(listenAddress, port);

    int numTries = 0;
    boolean retry = (port == NETSERVER_DEFAULT_PORT);
    // Start the netserver on the specified port. If the specified port is the
    // default port and it is already occupied by another process, try creating port
    // on an incremented number. Try this for 10 times before failing.
    do {
      try {
        numTries++;
        this.clientPort = port;
        netImpl.internalStart(networkProperties);
        retry = false;
      } catch (GemFireXDRuntimeException e) {
        if (retry && numTries <= 50) {
          // retry with an incremented port.
          netImpl.setPort(++port);
        } else throw e;
      }
    } while (retry && numTries <= 50);

    if (netImpl.getServerType().isThrift()) {
      serverType += (" (" + netImpl.getServerType().getProtocolString() + ')');
    }
    String startupMessage = "Started " + serverType + " on: " +
        listenAddress + '[' + port + ']';
    // set on launcher if run through it
    CacheServerLauncher launcher = CacheServerLauncher.getCurrentInstance();
    if (launcher != null) {
      launcher.setServerStartupMessage(startupMessage);
    }
    logger.info(startupMessage);

    return netImpl;
  }

  @Override
  public NetworkInterface startNetworkServer(String bindAddress, int port,
      Properties networkProperties) throws SQLException {
    return ClientSharedUtils.isThriftDefault()
        ? startThriftServer(bindAddress, port, networkProperties)
        : startDRDAServer(bindAddress, port, networkProperties);
  }

  @Override
  public NetworkInterface startThriftServer(String bindAddress, int port,
      Properties networkProperties) throws SQLException {
    final String serverString = isServer() ? "server" : "locator";
    final String thriftDisplay = networkProperties != null
        && "true".equalsIgnoreCase(networkProperties.getProperty(
            com.pivotal.gemfirexd.Attribute.THRIFT_USE_SSL))
            ? "Secure(SSL) Thrift " : "Thrift ";
    return startNetworkServerImpl(bindAddress, port, networkProperties, true,
        thriftDisplay + serverString);
  }

  @Override
  public NetworkInterface startDRDAServer(String bindAddress, int port,
      Properties networkProperties) throws SQLException {
    return startNetworkServerImpl(bindAddress, port, networkProperties, false,
        "DRDA server");
  }

  @Override
  public void stopAllNetworkServers() {
    networkserverstatus = State.STOPPING;

    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("Stopping all network interfaces ");
    }

    try {
      for (NetworkInterface ni : getAllNetworkServers()) {
        ni.stop();
      }

      // check for any autostarted network server
      final DRDAServerStarter server = (DRDAServerStarter)Monitor
          .getSystemModule(DRDAServerStarter.class.getName());
      if (server != null) {
        boolean stopped = false;
        try {
          server.ping();
          server.stop();
          for (int tries = 1; tries <= 200; tries++) {
            try {
              server.ping();
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                // ignore as we are shutting down anyway
              }
            } catch (Throwable t) {
              stopped = true;
              break;
            }
          }
        } catch (Throwable t) {
          stopped = true;
        }

        if (GemFireXDUtils.TraceFabricServiceBoot) {
          logger.info("Stopped " + server + " successfully. status " +
              (stopped ? State.STOPPED : State.RUNNING));
        }
      }
    } finally {
      networkserverstatus = State.STOPPED;
    }

    if (GemFireXDUtils.TraceFabricServiceBoot) {
      logger.info("All network interfaces stopped successfully.");
    }
  }

  @Override
  public List<NetworkInterface> getAllNetworkServers() {
    synchronized (this.allNetServers) {
      return new ArrayList<>(this.allNetServers);
    }
  }

  protected InetAddress getListenAddress(String bindAddress) {
    final InetAddress listenAddress;
    try {
      listenAddress = FabricServiceUtils.getListenAddress(bindAddress, null);
    } catch (UnknownHostException uhe) {
      final String msg = MessageService.getTextMessage(
          MessageId.CONN_BIND_ADDRESS_ERROR, bindAddress);
      logSevere(msg, uhe);
      GemFireXDRuntimeException rte = new GemFireXDRuntimeException(msg);
      rte.setStackTrace(new StackTraceElement[0]);
      throw rte;
    }
    return listenAddress;
  }

  /**
   * Base class for Thrift and DRDA network servers. It uses
   * {@link BridgeServerAdvisor} and related classes for load balancing etc.
   * Each instance of this class uses the GemFireXD server groups set for this
   * VM as the server groups for the bridge server.
   *
   * Also tracks the connections from thin clients. This is then published to
   * locators from the {@link ConnectionSignaller} thread.
   */
  public abstract class NetworkInterfaceImpl implements NetworkInterface,
      DistributionAdvisee, ConnectionListener, ConnectionState, ServerMetrics {

    protected InetAddress inetAddress;

    protected volatile String hostName;

    protected int port;

    protected final boolean isPreStarted;

    private int serialNumber;

    protected BridgeServerAdvisor advisor;

    // any connection listener added explicitly
    protected ConnectionListener connListener;

    // for connection tracking
    // TODO: add connection statistics

    protected volatile ServerLocation location;

    protected final ConnectionCountProbe probe;

    protected volatile int totalConnections;

    protected int connectionDelta;

    protected int clientCount;

    private boolean preferIPAddressForClients;

    private String initialLoad;

    protected Properties networkProps;

    protected NetworkInterfaceImpl(boolean preStarted) {
      this.probe = new ConnectionCountProbe();
      this.totalConnections = 0;
      this.connectionDelta = 0;
      this.clientCount = 0;

      this.isPreStarted = preStarted;
    }

    protected NetworkInterfaceImpl(InetAddress address, int portNumber,
        boolean preStarted) {
      this(preStarted);

      initAddress(address, portNumber);
    }

    protected void initAddress(InetAddress address, int portNumber) {
      this.inetAddress = address;
      this.port = portNumber;
      this.location = new ServerLocation(getHostNameForClients(), portNumber);
    }

    protected abstract void preStart(Properties networkProps);

    protected abstract void startServer(Properties networkProps);
    protected abstract void stopServer();

    void internalStart(Properties networkProps) {

      preStart(networkProps);

      if (networkserverstatus.ordinal() >= State.STOPPING.ordinal()) {
        throw GemFireXDRuntimeException.newRuntimeException("Cannot start "
            + "network servers while all network interfaces are shutting down",
            null);
      }

      if (GemFireXDUtils.TraceFabricServiceBoot) {
        logger.info((!this.isPreStarted ? "Starting " : "Wrapping ") + this +
            " on given address " + this.inetAddress + '[' + this.port + ']');
      }

      this.networkProps = networkProps;
      setHostNameForClients(networkProps);
      if (this.port > 0) {
        this.location = new ServerLocation(getHostNameForClients(), this.port);
      }

      this.serialNumber = DistributionAdvisor.createSerialNumber();

      // [soubhik] honor drda system properties to be overridden from here. The
      // properties argument in boot() is not honored by DRDAServer so instead
      // setting as system property.
      synchronized (FabricServiceImpl.this) {
        Properties oldProps = null;
        String propName, propValue, oldVal;
        try {
          if (networkProps != null) {
            // override the system properties with values passed in networkProps
            // storing original values
            oldProps = new Properties();
            final Enumeration<?> propNames = networkProps.propertyNames();
            while (propNames.hasMoreElements()) {
              propName = (String)propNames.nextElement();
              oldVal = monitorlite.getJVMProperty(propName);
              propValue = networkProps.getProperty(propName);
              if (oldVal != null) {
                oldProps.setProperty(propName, oldVal);
              }
              if (propValue != null) {
                monitorlite.setJVMProperty(propName, propValue);
              }
            }
          }

          startServer(networkProps);

          // wait for network server to initialize completely
          while (!status()) {
            Thread.sleep(50);
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          getCancelCriterion().checkCancelInProgress(ie);
        } finally {
          if (networkProps != null) {
            // reset the system properties to original values
            final Enumeration<?> propNames = networkProps.propertyNames();
            while (propNames.hasMoreElements()) {
              propName = (String)propNames.nextElement();
              if (oldProps == null ||
                  (oldVal = oldProps.getProperty(propName)) == null) {
                monitorlite.clearJVMProperty(propName);
              }
              else {
                monitorlite.setJVMProperty(propName, oldVal);
              }
            }
          }
        }
      }

      // Create a distribution advisor to make this NetworkServer appear
      // as a GFE BridgeServer and enable publishing of load information.
      // Ignore sending data if this is a stand-alone locator, but still
      // have the advisor to reply with proper profile
      this.advisor = BridgeServerAdvisor.createBridgeServerAdvisor(this);
      if (isServer()) {
        this.advisor.handshake();
      }

      if (GemFireXDUtils.TraceFabricServiceBoot) {
        logger.info(
            (!this.isPreStarted ? "Started " : "Wrapped ") + this
                + " successfully (prefer-ipaddress="
                + this.preferIPAddressForClients + "). status "
                + (status() ? State.RUNNING : " UNKNOWN "));
      }
      synchronized (allNetServers) {
        if (!allNetServers.add(this)) {
          stop();
          throw GemFireXDRuntimeException.newRuntimeException("Unexpected "
              + "existing network server when creating a new one", null);
        }
        networkserverstatus = State.RUNNING;
      }
    }

    @Override
    public void stop() {

      try {
        if (GemFireXDUtils.TraceFabricServiceBoot) {
          logger.info("Stopping " + this);
        }

        if (this.advisor != null && this.advisor.isInitialized()) {
          this.advisor.close();
        }
        synchronized (this) {
          if (this.connListener != null) {
            this.connListener.close();
            this.connListener = null;
          }
          this.clientCount = 0;
        }
        this.networkProps = null;
        stopServer();
        for (int tries = 1; tries <= 200 && status(); tries++) {
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            // ignore as we are shutting down anyway
          }
        }

        logger.info(
            "Stopped " + this + " successfully. status "
                + (isReconnecting() ? State.RECONNECTING
                    : (status() ? State.RUNNING : State.STOPPED)));
      } finally {
        synchronized (allNetServers) {
          allNetServers.remove(this);
          if (allNetServers.isEmpty()) {
            networkserverstatus = State.STOPPED;
          }
        }
      }
    }

    @Override
    public synchronized void setConnectionListener(ConnectionListener listener) {
      this.connListener = listener;
    }

    @Override
    public int getTotalConnections() {
      return this.totalConnections;
    }

    @Override
    public final String asString() {
      return getHostName() + '[' + getPort() + ']';
    }

    @Override
    public final String getHostName() {
      final String hostName = getHostNameForClients();
      if (hostName != null) {
        return hostName + '/' + this.inetAddress.getHostAddress();
      }
      return "/" + this.inetAddress.getHostAddress();
    }

    @Override
    public final int getPort() {
      return this.port;
    }

    public final void setPort(int port) {
      this.port = port;
    }

    public abstract void collectStatisticsSample();

    protected final String getHostNameForClients() {
      if (this.hostName != null) {
        return this.hostName;
      }

      return setHostNameForClients(this.networkProps);
    }

    private String getHostFromInetAddress(InetAddress address) {
      if (this.preferIPAddressForClients) {
        return address.getHostAddress();
      } else {
        // if the host name as specified in client-bind-address is already
        // qualified name then use it for clients else resolve
        String host = address.getHostName();
        if (host != null && host.indexOf('.') > 0) {
          return host;
        } else {
          return address.getCanonicalHostName();
        }
      }
    }

    protected final String setHostNameForClients(Properties networkProps) {
      // check for explicit address setting first
      String host = PropertyUtil.findAndGetProperty(networkProps,
          Attribute.HOSTNAME_FOR_CLIENTS, GfxdConstants.GFXD_PREFIX +
              Attribute.HOSTNAME_FOR_CLIENTS, monitorlite);
      if (host != null) {
        this.hostName = host;
        return host;
      }
      this.preferIPAddressForClients = PropertyUtil.getBooleanProperty(
          Attribute.PREFER_NETSERVER_IP_ADDRESS,
          GfxdConstants.GFXD_PREFER_NETSERVER_IP_ADDRESS, networkProps, false,
          monitorlite);
      // for wildcard address get the proper hostname if possible (see #43477)
      if (this.inetAddress.isAnyLocalAddress()) {
        try {
          final InetAddress localHost = SocketCreator.getLocalHost();
          if (localHost != null && !localHost.isLoopbackAddress()) {
            return (this.hostName = getHostFromInetAddress(localHost));
          }
        } catch (UnknownHostException uhe) {
          // ignored
        }
      }
      return (this.hostName = getHostFromInetAddress(this.inetAddress));
    }

    @Override
    public String toString() {
      return "Network interface " + asString();
    }

    @Override
    public int hashCode() {
      return (inetAddress.hashCode() ^ port) * 3;
    }

    protected boolean isAddressAvailable(int port, InetAddress addr) {
      ServerSocket ss = null;
      boolean isAvailable = false;
      try {
        ss = new ServerSocket(port, 0, addr);
        isAvailable = true;
      } catch (java.io.IOException ignore) {
      } finally {
        if (ss != null)
          try {
            ss.close();
          } catch (java.io.IOException ignore) {
          }
      }
      return isAvailable;
    }

    // DistributionAdvisee methods

    @Override
    public DM getDistributionManager() {
      return getSystem().getDistributionManager();
    }

    @Override
    public CancelCriterion getCancelCriterion() {
      return getSystem().getCancelCriterion();
    }

    @Override
    public InternalDistributedSystem getSystem() {
      return Misc.getDistributedSystem();
    }

    @Override
    public void fillInProfile(Profile profile) {
      assert profile instanceof BridgeServerProfile;
      final BridgeServerProfile bp = (BridgeServerProfile)profile;
      bp.setHost(getHostName());
      bp.setPort(getPort());

      final String[] groups = ServerGroupUtils.getMyGroupsArray();
      // add special group for thrift/DRDA server
      if (groups.length == 0) {
        bp.setGroups(new String[] { getServerType().getServerGroupName() });
      }
      else {
        final String[] allGroups = new String[groups.length + 1];
        allGroups[0] = getServerType().getServerGroupName();
        System.arraycopy(groups, 0, allGroups, 1, groups.length);
        bp.setGroups(allGroups);
      }

      bp.setMaxConnections(getMaxConnections());
      bp.setInitialLoad(this.probe.getLoad(this));
      bp.setLoadPollInterval(CacheServer.DEFAULT_LOAD_POLL_INTERVAL);
      bp.serialNumber = getSerialNumber();
      bp.finishInit();
      this.initialLoad = bp.getInitialLoad().toString();
    }

    @Override
    public DistributionAdvisor getDistributionAdvisor() {
      return this.advisor;
    }

    @Override
    public Profile getProfile() {
      return getDistributionAdvisor().createProfile();
    }

    @Override
    public String getName() {
      return "FabricServer.NetworkInterface";
    }

    @Override
    public String getFullPath() {
      return getName();
    }

    @Override
    public DistributionAdvisee getParentAdvisee() {
      return null;
    }

    @Override
    public int getSerialNumber() {
      return this.serialNumber;
    }

    // ConnectionListener methods

    protected void connectionOpened() {
      synchronized (this) {
        ++this.connectionDelta;
        ++this.totalConnections;
        /*
        if (firstConnection) {
          ++this.clientCount;
        }
        */
      }
      if (isServer() && this.advisor != null && this.advisor.isInitialized()) {
        ConnectionSignaller.getInstance().add(this);
      }
    }

    @Override
    public void connectionOpened(Socket clientSocket, int connectionNumber) {
      connectionOpened();
      final ConnectionListener listener = this.connListener;
      if (listener != null) {
        synchronized (this) {
          listener.connectionOpened(clientSocket, connectionNumber);
        }
      }
    }

    @Override
    public void connectionOpened(TTransport clientTransport,
        TProcessor processor, int connectionNumber) {
      connectionOpened();
      final ConnectionListener listener = this.connListener;
      if (listener != null) {
        synchronized (this) {
          listener.connectionOpened(clientTransport, processor,
              connectionNumber);
        }
      }
    }

    protected void connectionClosed() {
      synchronized (this) {
        --this.connectionDelta;
        --this.totalConnections;
        /*
        if (lastConnection) {
          --this.clientCount;
        }
        */
      }
      if (isServer() && this.advisor != null && this.advisor.isInitialized()) {
        ConnectionSignaller.getInstance().add(this);
      }
    }

    @Override
    public void connectionClosed(Socket clientSocket, int connectionNumber) {
      connectionClosed();
      final ConnectionListener listener = this.connListener;
      if (listener != null) {
        synchronized (this) {
          listener.connectionClosed(clientSocket, connectionNumber);
        }
      }
    }

    @Override
    public void connectionClosed(TTransport clientTransport,
        TProcessor processor, int connectionNumber) {
      connectionClosed();
      final ConnectionListener listener = this.connListener;
      if (listener != null) {
        synchronized (this) {
          listener.connectionClosed(clientTransport, processor,
              connectionNumber);
        }
      }
    }

    @Override
    public void close() {
      // nothing to be done
    }

    // ConnectionState methods

    @Override
    public boolean accumulate(ConnectionState other) {
      // nothing to be done since accumulation is already handled
      // using connectionDelta
      return (this == other);
    }

    @Override
    public void distribute() {
      // we don't have current information in BridgeServerAdvisor since
      // the NetworkInterfaceImpls do not register as BridgeServers so
      // use GfxdDistributionAdvisor instead
      final Set<DistributedMember> locators = GemFireXDUtils.getGfxdAdvisor()
          .adviseServerLocators(false);

      if (locators != null && locators.size() > 0) {
        final ServerLoad load = this.probe.getLoad(this);
        if (logger.isDebugEnabled()) {
          logger.debug("GemFireXD NetworkServerImpl: transmitting load " + load +
              " to locators " + locators);
        }

        // stats.setLoad(load);
        final BridgeServerLoadMessage message = new BridgeServerLoadMessage(
            load, this.location, null);
        message.setRecipients(locators);
        this.advisor.getDistributionManager().putOutgoing(message);
      }
    }

    @Override
    public synchronized int numChanges() {
      final int changes = this.connectionDelta;
      return (changes < 0 ? -changes : changes);
    }

    @Override
    public int minBatchSize() {
      return 3;
    }

    @Override
    public long waitMillis() {
      return 500L;
    }

    // ServerMetrics methods

    @Override
    public synchronized int getClientCount() {
      // currently this is not set properly which does not matter since
      // it is not used by locators anywhere
      return this.clientCount;
    }

    @Override
    public synchronized int getConnectionCount() {
      // also reset the changes as recorded in connectionDelta
      this.connectionDelta = 0;
      return this.totalConnections;
    }

    @Override
    public int getMaxConnections() {
      return CacheServer.DEFAULT_MAX_CONNECTIONS;
    }

    @Override
    public int getSubscriptionConnectionCount() {
      // no subscription connections yet for NetworkServer
      return 0;
    }

    public SessionsVTI.SessionInfo getSessionInfo() {
      final SessionsVTI.SessionInfo info = new SessionsVTI.SessionInfo();
      String infoString = "InitialLoad=" + this.initialLoad +
          SanityManager.lineSeparator +
          "CurrentLoad=" + this.probe.getLoad(this) +
          SanityManager.lineSeparator;
      info.memberid = getSystem().getMemberId();
      info.hostname = getHostName();
      info.serverListeningPort = getPort();
      info.networkInterfaceInfo = infoString;

      fillServerSessionInfo(info);

      return info;
    }

    protected void fillServerSessionInfo(SessionsVTI.SessionInfo info) {
    }
  }

  /**
   * The default Thrift based network server.
   */
  private final class ThriftNetworkInterface extends NetworkInterfaceImpl {

    private final SnappyThriftServer thriftService;
    private int maxThreads = Math.max(Runtime.getRuntime()
        .availableProcessors() * 4, Short.MAX_VALUE);
    private final SocketParameters socketParams;

    ThriftNetworkInterface(InetAddress address, int portNumber) {
      super(address, portNumber, false);
      this.thriftService = new SnappyThriftServer();
      this.socketParams = isServer() ? new SocketParameters(
          ServerType.THRIFT_SNAPPY_CP) : new SocketParameters(
          ServerType.THRIFT_LOCATOR_CP);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServerType getServerType() {
      return this.socketParams.getServerType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preStart(Properties networkProps) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void startServer(Properties networkProps) {
      try {
        final boolean isServer = isServer();
        // determine the thrift protocol, SSL properties etc.
        String propValue;
        final boolean useBinaryProtocol;
        final boolean useFramedTransport;
        boolean useSSL;
        if (networkProps != null) {
          useBinaryProtocol = Boolean.parseBoolean(networkProps.getProperty(
              Attribute.THRIFT_USE_BINARY_PROTOCOL));
          useFramedTransport = Boolean.parseBoolean(networkProps.getProperty(
              Attribute.THRIFT_USE_FRAMED_TRANSPORT));
          useSSL = Boolean.parseBoolean(networkProps.getProperty(
              Attribute.THRIFT_USE_SSL));
          // set SSL properties (csv format) into SSL params in SocketParameters
          propValue = networkProps.getProperty(Attribute.THRIFT_SSL_PROPERTIES);
          if (propValue != null) {
            useSSL = true;
            ThriftUtils.getSSLParameters(this.socketParams, propValue);
          }
          // parse remaining properties like socket buffer sizes, read timeout
          // and keep alive settings
          for (SocketParameters.Param param : SocketParameters
              .getAllParamsNoSSL()) {
            propValue = networkProps.getProperty(param.getPropertyName());
            if (propValue != null) {
              param.setParameter(this.socketParams, propValue);
            }
          }
        } else {
          useBinaryProtocol = false;
          useFramedTransport = false;
          useSSL = false;
        }
        socketParams.setServerType(ServerType.getServerType(isServer,
            useBinaryProtocol, useSSL));

        this.thriftService.start(this.inetAddress, this.port, this.maxThreads,
            isServer, useBinaryProtocol, useFramedTransport, useSSL,
            socketParams, this);
      } catch (TTransportException te) {
        throw new GemFireXDRuntimeException(te);
      }
    }

    @Override
    public void stopServer() {
      this.thriftService.stop();
      this.socketParams.setServerType(isServer() ? ServerType.THRIFT_SNAPPY_CP
          : ServerType.THRIFT_LOCATOR_CP);
    }

    @Override
    public boolean status() {
      return this.thriftService.isServing();
    }

    @Override
    public void connectionClosed(TTransport clientTransport,
        TProcessor processor, int connectionNumber) {
      super.connectionClosed(clientTransport, processor, connectionNumber);
      if (processor instanceof SnappyDataServiceImpl.Processor) {
        ((SnappyDataServiceImpl.Processor)processor).clientSocketClosed(
            clientTransport);
      }
    }

    @Override
    public void collectStatisticsSample() {
      this.thriftService.collectStatisticsSample();
    }

    @Override
    public void trace(boolean on) {
    }

    @Override
    public void trace(int connNum, boolean on) {
    }

    @Override
    public void logConnections(boolean on) {
    }

    @Override
    public void setTraceDirectory(String traceDirectory) {
    }

    @Override
    public String getSysinfo() {
      return null;
    }

    @Override
    public String getRuntimeInfo() {
      return null;
    }

    @Override
    public void setMaxThreads(int max) {
      this.maxThreads = max;
    }

    @Override
    public int getMaxThreads() {
      return this.maxThreads;
    }

    @Override
    public void setTimeSlice(int timeslice) {
    }

    @Override
    public int getTimeSlice() {
      return -1;
    }

    @Override
    public Properties getCurrentProperties() {
      Properties props = new Properties();
      final String[] thriftProps = new String[]{
          Attribute.THRIFT_USE_BINARY_PROTOCOL,
          Attribute.THRIFT_USE_FRAMED_TRANSPORT,
          Attribute.THRIFT_USE_SSL,
          Attribute.THRIFT_SSL_PROPERTIES};

      for (String p : thriftProps) {
        String v = networkProps.getProperty(p);
        if (v != null) {
          props.setProperty(p, v);
        }
      }
      return props;
    }

    @Override
    public String toString() {
      return "Thrift Network interface " + asString();
    }

    @Override
    public String getName() {
      return "FabricServer.ThriftNetworkInterface";
    }

    @Override
    protected void fillServerSessionInfo(SessionsVTI.SessionInfo info) {
      this.thriftService.getSessionInfo(info);
    }
  }

  /**
   * In addition to starting a DRDA server, it also uses
   * {@link BridgeServerAdvisor} and related classes for load balancing etc.
   * Each instance of this class uses the GemFireXD server groups set for this VM
   * as the server groups for the bridge server.
   *
   * Also tracks the <code>NetworkServerControl</code> connections from JDBC
   * thin clients. This is then published to locators from the
   * {@link ConnectionSignaller} thread.
   */
  private final class DRDANetworkInterface extends NetworkInterfaceImpl {

    private final DRDAServerStarter netserver;

    DRDANetworkInterface(InetAddress address, int portNumber) {
      super(address, portNumber, false);

      this.netserver = new DRDAServerStarter();
    }

    DRDANetworkInterface(DRDAServerStarter server) throws Exception {
      super(true);

      int[] outPort = new int[1];
      InetAddress addr = (InetAddress)server.invoke(
          DRDAServerStarter.NetworkServerControlProps.getHostAddressAndPort,
          new Object[] { outPort });
      initAddress(addr, outPort[0]);
      this.netserver = server;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServerType getServerType() {
      return ServerType.DRDA;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void preStart(Properties networkProps) {
      // initialize server start info
      if (!this.isPreStarted) {
        synchronized (FabricServiceImpl.this) {
          if (FabricServiceImpl.this.userName != null) {
            this.netserver.setStartInfo(this.inetAddress, this.port,
                FabricServiceImpl.this.userName,
                FabricServiceImpl.this.password, new PrintWriter(System.err));
          }
          else {
            this.netserver.setStartInfo(this.inetAddress, this.port,
                new PrintWriter(System.err));
          }
        }
      }

      if (!this.isPreStarted && !isAddressAvailable(port, inetAddress)) {
        final String err = MessageService.getTextMessage(
            MessageId.CONN_DRDA_ADDRESS_IN_USE_ERR, inetAddress, port);
        logSevere(err, null);
        GemFireXDRuntimeException rte = new GemFireXDRuntimeException(err);
        rte.setStackTrace(new StackTraceElement[0]);
        throw rte;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void startServer(Properties networkProps) {
      // boot if this is not an auto-started network server
      if (!this.isPreStarted) {
        this.netserver.boot(false, null);
      }

      // add self as ConnectionListener
      this.netserver.setConnectionListener(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void stopServer() {
      this.netserver.stop();
    }

    @Override
    public boolean status() {
      try {
        netserver.ping();
        return true;
      } catch (Throwable t) {
        return false;
      }
    }

    @Override
    public void collectStatisticsSample() {
      netserver.collectStatisticsSamples();
    }

    @Override
    public void trace(boolean on) {
      netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.traceBoolean,
          new Object[] { on });
    }

    @Override
    public void trace(int connNum, boolean on) {
      netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.traceConnNumBoolean,
          new Object[] { connNum, on });
    }

    @Override
    public void logConnections(boolean on) {
      netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.logConnections,
          new Object[] { on });
    }

    @Override
    public void setTraceDirectory(String traceDirectory) {
      netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.sendSetTraceDirectory,
          new Object[] { traceDirectory });
    }

    @Override
    public String getSysinfo() {

      Object ret = netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.sysinfo, null);

      assert ret instanceof String: "getSysinfo expected to return string";

      return (String)ret;
    }

    @Override
    public String getRuntimeInfo() {
      Object ret = netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.runtimeInfo, null);

      assert ret instanceof String: "getRuntimeInfo expected to return string";

      return (String)ret;
    }

    @Override
    public void setMaxThreads(int max) {
      netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.netSetMaxThreads,
          new Object[] { max });
    }

    @Override
    public int getMaxThreads() {
      String val = getCurrentProperties().getProperty(
          com.pivotal.gemfirexd.Property.DRDA_PROP_MAXTHREADS);
      return Integer.parseInt(val);
    }

    @Override
    public void setTimeSlice(int timeslice) {
      netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.netSetTimeSlice,
          new Object[] { timeslice });
    }

    @Override
    public int getTimeSlice() {
      String val = getCurrentProperties().getProperty(
          com.pivotal.gemfirexd.Property.DRDA_PROP_TIMESLICE);
      return Integer.parseInt(val);
    }

    @Override
    public Properties getCurrentProperties() {
      Object props = netserver.invoke(
          DRDAServerStarter.NetworkServerControlProps.getCurrentProperties,
          null);

      assert props instanceof Properties: "getCurrentProperties is expected "
          + "to have property bag returned.";

      return (Properties)props;
    }

    @Override
    public String toString() {
      return "DRDA Network interface " + asString();
    }

    @Override
    public String getName() {
      return "FabricServer.DRDANetworkInterface";
    }

    @Override
    protected void fillServerSessionInfo(SessionsVTI.SessionInfo info) {
      this.netserver.getSessionInfo(info);
    }
  }

  private void logSevere(String msg, Exception ex) {
    logger.error(msg, ex);

    if (msg != null) {
      System.err.println(msg);
    }
    if (ex != null) {
      ex.printStackTrace(System.err);
    }
  }

  static void registerDrivers() {
    try {
      Class.forName(driver).newInstance();
    } catch (ClassNotFoundException cnfe) {
      cnfe.printStackTrace();
      SanityManager.DEBUG_PRINT("warning", "Unable to load the JDBC driver "
          + driver + ":" + cnfe.getMessage());
    } catch (InstantiationException ie) {
      ie.printStackTrace();
      SanityManager.DEBUG_PRINT("warning",
          "Unable to instantiate the JDBC driver " + driver + ":"
              + ie.getMessage());
    } catch (IllegalAccessException iae) {
      iae.printStackTrace();
      SanityManager.DEBUG_PRINT("warning",
          "Not allowed to access the JDBC driver " + driver + ":"
              + iae.getMessage());
    }
  }

  private void registerServerRestarter(InternalDistributedSystem sys,
      Properties bootProperties, boolean ignoreIfStarted, boolean isLocator) {
    if (!sys.getConfig().getDisableAutoReconnect()) {
      this.restarter = new ServerRestarter(sys, bootProperties, ignoreIfStarted, isLocator);
      InternalDistributedSystem.addReconnectListener(this.restarter);
    }
  }

  private void registerForcedDisconnectListener(InternalDistributedSystem sys,
      Properties bootProperties) {
    // the forcedDisconnectListener shuts down the FabricServiceImpl if the
    // distributed system gets booted out of the system by other members
    ForcedDisconnectListener forcedDisconnectListener =
        new ForcedDisconnectListener(sys, bootProperties);
    sys.setGfxdForcedDisconnectListener(forcedDisconnectListener);
  }

  static class ForcedDisconnectListener implements DisconnectListener {
    InternalDistributedSystem sys;
    Properties bootProperties;


    ForcedDisconnectListener(InternalDistributedSystem sys, Properties bootProperties) {
      this.sys = sys;
      this.bootProperties = bootProperties;
    }

    @Override
    public void onDisconnect(InternalDistributedSystem sys) {
      // only stop the fabricservice if it's not going to be
      // rebooted.  Otherwise it goes away and the reconnected DS
      // won't have a service to use it.
      if (sys.forcedDisconnect() && sys.getConfig().getDisableAutoReconnect()) {
        FabricServiceImpl impl = ((FabricServiceImpl)getInstance());
        if (impl != null) {
          try {
            // noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized(impl) {
              sys.getLogWriter().fine("FabricService stopping due to forced-disconnect");
              impl.stopNoSync(this.bootProperties, sys, true);
            }
          } catch (SQLException e) {
            SanityManager.DEBUG_PRINT("warning:" + fabapi, "exception caught " +
                "while stopping service due to forced disconnect", e);
          }
        }
      }
    }
  }

  static class ServerRestarter implements ReconnectListener {
    InternalDistributedSystem sys;
    Properties bootProperties;
    boolean ignoreIfStarted;
    boolean isLocator;

    ServerRestarter(InternalDistributedSystem sys, Properties bootProperties,
        boolean ignoreIfStarted, boolean isLocator) {
      this.sys = sys;
      this.bootProperties = new Properties();
      for (Map.Entry<?, ?> en: bootProperties.entrySet()) {
        this.bootProperties.put(en.getKey(), en.getValue());
      }
      this.ignoreIfStarted = ignoreIfStarted;
      this.isLocator = isLocator;
    }

    @Override
    public void reconnecting(InternalDistributedSystem oldsys) {
      FabricServiceImpl impl = ((FabricServiceImpl)getInstance());
      if (impl != null) {
        // noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized(impl) {
          impl.reconnecting();
        }
      }
    }

    @Override
    public void onReconnect(InternalDistributedSystem oldSystem, InternalDistributedSystem newSystem) {
      FabricServiceImpl impl = ((FabricServiceImpl)getInstance());
      if (impl != null) {
        try {
          // noinspection SynchronizationOnLocalVariableOrMethodParameter
          synchronized(impl) {
            newSystem.getLogWriter().info("rebooting GemFireXD "+
                (isLocator?"locator":"server") + " instance");
            impl.startImpl(bootProperties, false, isLocator);
          }
          this.sys = InternalDistributedSystem.getConnectedInstance();
        } catch (SQLException e) {
          newSystem.getLogWriter().severe("Unable to recreate services due to exception", e);
          newSystem.disconnect();
          if (getInstance() == impl) {
            setInstance(null);
          }
        }
      }
    }

    public boolean isReconnecting() {
      return this.sys.isReconnecting() || this.sys.reconnected();
    }

    public boolean waitUntilReconnected(long time, TimeUnit units) throws InterruptedException {
//    (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("fabricservice: sys.reconnected() = " + sys.reconnected());
      return this.sys.reconnected() || this.sys.waitUntilReconnected(time, units);
    }

    public void stopReconnecting() {
      this.sys.stopReconnecting();
    }
  }

} // end of FabricServiceImpl
