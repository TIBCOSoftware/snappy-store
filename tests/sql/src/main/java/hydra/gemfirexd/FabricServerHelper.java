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

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.pivotal.gemfirexd.FabricLocator;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricService.State;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import hydra.BasePrms;
import hydra.DistributedSystemHelper;
import hydra.EnvHelper;
import hydra.FileUtil;
import hydra.HostDescription;
import hydra.HostHelper;
import hydra.HostHelper.OSType;
import hydra.HydraInternalException;
import hydra.HydraRuntimeException;
import hydra.Log;
import hydra.MasterController;
import hydra.PortHelper;
import hydra.ProcessMgr;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import hydra.blackboard.SharedCounters;
import java.io.File;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.*;

/**
 * Helps hydra clients manage fabric servers and stand-alone locators.
 * Methods are thread-safe.
 */
public class FabricServerHelper {

  /** Description used to start the current fabric server */
  protected static FabricServerDescription TheFabricServerDescription;

  /** Properties used to start the current fabric server */
  private static Properties TheFabricServerProperties;

  /** Perpetual endpoint for the locator (if any). */
  private static Endpoint TheLocatorEndpoint;

  private static LogWriter log = Log.getLogWriter();

//------------------------------------------------------------------------------
// FabricServer
//------------------------------------------------------------------------------

  /**
   * Starts a fabric server using boot properties from the {@link
   * FabricServerDescription} to which this VM is wired using {@link
   * FabricServerPrms#clientNames}.  Returns the existing fabric server
   * if it is already started.
   *
   * @throws HydraRuntimeException if an attempt is made to reconfigure an
   *         existing fabric server.
   */
  public static synchronized FabricServer startFabricServer() {
    log.info("Starting the fabric server");
    Properties p = getBootProperties();
    return startFabricServer(p);

  }

  /**
   * Starts a fabric server using the given boot properties, typically
   * modified properties obtained using {@link #getBootProperties} or {@link
   * #getBootProperties(String)}.  Returns the existing fabric server if it
   * is already started.
   *
   * @throws HydraRuntimeException if an attempt is made to reconfigure an
   *         existing fabric server.
   */
  public static synchronized FabricServer startFabricServer(
                                          Properties bootProperties) {
    // default to partitioned tables for tests
    log.info("Starting the fabric server: " + prettyprint(bootProperties));
    FabricServer fs = FabricServiceManager.getFabricServerInstance();
    FabricService.State status = fs.status();
    switch (status) {
      case UNINITIALIZED:
      case STOPPED:
        try {
          fs.start(bootProperties);
        } catch (SQLException e) {
          String s = "Unable to start fabric server";
          throw new HydraRuntimeException(s, e);
        }
        fs = FabricServiceManager.getFabricServerInstance();
        FabricService.State statusNow = fs.status();
        if (statusNow != FabricService.State.RUNNING) {
          String s = "Expected fabric server to be RUNNING, but it is: "
                   + statusNow;
          throw new HydraRuntimeException(s);
        }
        log.info("Started the fabric server");

        // save the boot properties for future reference
        TheFabricServerProperties = bootProperties;
        break;

      case RUNNING:
        if (TheFabricServerProperties == null) {
          // block attempt to start fabric server in multiple ways
          String s = "Fabric server was already started without"
                   + " FabricServerHelper using an unknown, and possibly"
                   + " different, configuration";
          throw new HydraRuntimeException(s);

        } else {
          if (!TheFabricServerProperties.equals(bootProperties)) {
            // block attempt to reconnect fabric server with clashing properties
            String s = "Fabric server already exists using boot properties "
                     + TheFabricServerProperties + ", cannot also use "
                     + bootProperties;
            throw new HydraRuntimeException(s);
          }
        } // else it already uses these properties, which is fine

        log.info("Already started the fabric server");
        break;

      case STARTING:
      case STOPPING:
      case WAITING:
      default:
        throw new HydraRuntimeException("Unexpected state: " + status);
    }
    return fs;
  }

  /**
   * Returns the fabric server instance, regardless of its state.
   */
  public static synchronized FabricServer getFabricServer() {
    return FabricServiceManager.getFabricServerInstance();
  }

  /**
   * Stops the fabric server if it is running, using shutdown properties from
   * the {@link FabricServerDescription} to which this VM is wired using {@link
   * FabricServerPrms#clientNames}.
   *
   * @throws HydraRuntimeException if there is a problem stopping the server.
   */
  public static synchronized void stopFabricServer() {
    log.info("Stopping the fabric server");
    Properties p = getShutdownProperties();
    stopFabricServer(p);

  }

  /**
   * Stops the fabric server if it is running, using the given shutdown
   * properties, typically modified properties obtained using {@link
   * #getShutdownProperties} or {@link #getShutdownProperties(String)}.
   *
   * @throws HydraRuntimeException if there is a problem stopping the server.
   */
  public static synchronized void stopFabricServer(
                                            Properties shutdownProperties) {
    log.info("Stopping the fabric server: " + prettyprint(shutdownProperties));
    FabricService fs = FabricServiceManager.currentFabricServiceInstance();
    if (fs == null) {
      log.info("Fabric server already stopped.");
      return;
    }
    FabricService.State status = fs.status();
    switch (status) {
      case UNINITIALIZED:
      case STOPPED:
        log.info("Fabric server already stopped.");
        break;
      case RUNNING:
        try {
          fs.stop(shutdownProperties);
        } catch (SQLException e) {
          String s = "Unable to stop fabric server";
          throw new HydraRuntimeException(s, e);
        }
        FabricService.State statusNow = fs.status();
        if (statusNow != FabricService.State.STOPPED) {
          String s = "Expected fabric server to be STOPPED, but it is: "
                   + statusNow;
          throw new HydraRuntimeException(s);
        }
        log.info("Stopped the fabric server");
        // allow the next start to have a different config
        TheFabricServerDescription = null;
        TheFabricServerProperties = null;
        break;
      case STARTING:
      case STOPPING:
      case WAITING:
      default:
        throw new HydraRuntimeException("Unexpected state: " + status);
    }
  }

  /**
   * Issues "gfxd shut-down-all" to a locator in the
   * distributed system of this JVM. Waits the given number of seconds for
   * the shut-down-all command to complete.
   *
   * @throws HydraRuntimeException if no locator is found.
   */
  public static synchronized void shutDownAllFabricServers(
                                  int shutDownAllCmdWaitSec) {
    log.info("Issuing shut-down-all command");
    List<Endpoint> locators = getSystemEndpoints();
    if (locators.size() == 0) {
      throw new HydraRuntimeException("No locators found");
    }
    String locId = locators.get(0).getId();

    String cmd = getGFXDCommand() + "rowstore shut-down-all -locators=" + locId;
    String output = ProcessMgr.fgexec(cmd, shutDownAllCmdWaitSec);
    log.info("Issued shut-down-all command:\n" + output);
  }

  /**
   * Issues "gfxd shut-down-all" to a locator in the specified
   * distributed system. Waits the given number of seconds for
   * the shut-down-all command to complete.
   *
   * @throws HydraRuntimeException if no locator is found.
   */
  public static synchronized void shutDownAllFabricServers(
         String dsName, int shutDownAllCmdWaitSec) {
    log.info("Issuing shut-down-all command to " + dsName);
    List<Endpoint> locators = getEndpoints(dsName);
    if (locators.size() == 0) {
      throw new HydraRuntimeException("No locators found");
    }
    String locId = locators.get(0).getId();
    String cmd = getGFXDCommand() + "rowstore shut-down-all -locators=" + locId;
    String output = ProcessMgr.fgexec(cmd, shutDownAllCmdWaitSec);
    log.info("Issued shut-down-all command:\n" + output);
  }

  /**
   * Issues "gfxd run" on the specified file to a locator in the specified
   * distributed system. Waits the given number of seconds for
   * the run command to complete.
   *
   * @throws HydraRuntimeException if no locator is found.
   */
  public static synchronized void executeSQLCommands(
         String dsName, String fn, int waitSec) {
    log.info("Issuing run command to " + dsName + " using " + fn);
    List<Endpoint> locators = getEndpoints(dsName);
    if (locators.size() == 0) {
      throw new HydraRuntimeException("No locators found");
    }
    Endpoint endpoint = locators.get(0);
    String cmd = getGFXDCommand() + "run -file=" + fn
               + " -locators=" + endpoint.getId();
    String output = ProcessMgr.fgexec(cmd, waitSec);
    log.info("Issued run command to " + dsName + " using " + fn);
  }

  /**
   * Returns true if there is a fabric server in the RUNNING state in this JVM.
   * All other states return false.
   */
  public static boolean isFabricServerRunning() {
    FabricService fs = FabricServiceManager.currentFabricServiceInstance();
    return fs != null && fs.status().equals(FabricService.State.RUNNING);
  }

  /**
   * Returns true if there is a fabric server in the STOPPED state in this JVM
   * or if no fabric server exists. All other states return false.
   */
  public static boolean isFabricServerStopped() {
    FabricService fs = FabricServiceManager.currentFabricServiceInstance();
    return fs == null || fs.status().equals(FabricService.State.STOPPED);
  }

  /**
   * Returns the gfxd or gfxd.bat command. Simply add arguments and exec.
   */
  public static String getGFXDCommand() {
    HostDescription hd = TestConfig.getInstance()
        .getClientDescription(RemoteTestModule.getMyClientName())
        .getVmDescription().getHostDescription();
    char sep = hd.getFileSep();

    String productBin = hd.getGemFireHome() + sep + ".." + sep + "snappy"
                      + sep + "bin";
    String productHiddenBin = hd.getGemFireHome() + sep + ".." + sep + "snappy"
                      + sep + "bin";

    String gfxdScript = null;
    switch (hd.getOSType()) {
      case unix:
        gfxdScript = "snappy-sql";
        break;
      case windows:
        gfxdScript = "snappy-sql.bat";
        break;
    }
    String gfxd = productBin + sep + gfxdScript;
    if (!FileUtil.exists(gfxd)) {
      gfxd = productHiddenBin + sep + gfxdScript;
      if (!FileUtil.exists(gfxd)) {
        String s = gfxdScript + " not found in " + productBin
                 + " or " + productHiddenBin;
        throw new HydraRuntimeException(s);
      }
    }

    String cmd = null;
    switch (hd.getOSType()) {
      case unix:
        cmd = "env GFXD_JAVA=" + hd.getJavaHome() + sep + "bin" + sep + "java "
            + gfxd;
        break;
      case windows:
        cmd = "cmd /c "
            + "set GFXD_JAVA=" + hd.getJavaHome() + sep + "bin" + sep + "java.exe "
            + "&& cmd /c " + gfxd;
        break;
    }
    return cmd + " "; // make it easy on users by adding the trailing space here
  }

//------------------------------------------------------------------------------
// Names and Ids
//------------------------------------------------------------------------------

  /**
   * Returns the distributed system id for this JVM.
   */
  public static Integer getDistributedSystemId() {
    return getFabricServerDescription().getDistributedSystemId();
  }

  /**
   * Returns the distributed system id for the given distributed system name.
   */
  public static int getDistributedSystemId(String distributedSystemName) {
    for (FabricServerDescription fsd :
         GfxdTestConfig.getInstance().getFabricServerDescriptions().values()) {
      if (fsd.getDistributedSystem().equals(distributedSystemName)) {
        return fsd.getDistributedSystemId();
      }
    }
    String s = "Distributed system not found: " + distributedSystemName;
    throw new HydraRuntimeException(s);
  }

  /**
   * Returns the distributed system name for this JVM.
   */
  public static String getDistributedSystemName() {
    return getFabricServerDescription().getDistributedSystem();
  }

  /**
   * Returns the distributed system name for the given distributed system id.
   */
  public static String getDistributedSystemName(int distributedSystemId) {
    for (FabricServerDescription fsd :
         GfxdTestConfig.getInstance().getFabricServerDescriptions().values()) {
      if (fsd.getDistributedSystemId() == distributedSystemId) {
        return fsd.getDistributedSystem();
      }
    }
    String s = "Distributed system not found: " + distributedSystemId;
    throw new HydraRuntimeException(s);
  }

//------------------------------------------------------------------------------
// Properties
//------------------------------------------------------------------------------

  /**
   * Returns the boot properties from the {@link FabricServerDescription}
   * to which this VM is wired using {@link FabricServerPrms#clientNames}.
   */
  public static Properties getBootProperties() {
    FabricServerDescription fsd = getFabricServerDescription();
    return getBootProperties(fsd);
  }

  /**
   * Returns the boot properties using the given description.
   */
  public static synchronized Properties getBootProperties(
                                         FabricServerDescription fsd) {
    log.info("Looking up boot properties");
    Properties p = fsd.getBootProperties();
    log.info("Looked up boot properties: " + prettyprint(p));
    return p;
  }

  /**
   * Returns the shutdown properties from the {@link FabricServerDescription}
   * to which this VM is wired using {@link FabricServerPrms#clientNames}.
   */
  public static Properties getShutdownProperties() {
    FabricServerDescription fsd = getFabricServerDescription();
    return getShutdownProperties(fsd);
  }

  /**
   * Returns the shutdown properties using the given description.
   */
  private static synchronized Properties getShutdownProperties(
                                         FabricServerDescription fsd) {
    log.info("Looking up shutdown properties");
    Properties p = fsd.getShutdownProperties();
    log.info("Looked up shutdown properties: " + prettyprint(p));
    return p;
  }

  /**
   * Returns a string containing indented properties, one per line.
   */
  private static String prettyprint(Properties p) {
    List l = Collections.list(p.propertyNames());
    SortedSet set = new TreeSet(l);
    StringBuffer buf = new StringBuffer();
    for (Iterator i = set.iterator(); i.hasNext();) {
      String key = (String)i.next();
      String val = p.getProperty(key);
      buf.append("\n  " + key + "=" + val);
    }
    return buf.toString();
  }

//------------------------------------------------------------------------------
// FabricServerDescription
//------------------------------------------------------------------------------

  /**
   * Returns the {@link FabricServerDescription} to which this VM is wired
   * using {@link FabricServerPrms#clientNames}.  Caches the result.
   *
   * @throws HydraRuntimeException if no configuration is wired to this client,
   */
  public static FabricServerDescription getFabricServerDescription() {
    String clientName = RemoteTestModule.getMyClientName();
    if (TheFabricServerDescription == null) {
      log.info("Looking up fabric server config for " + clientName);
      Map<String,FabricServerDescription> fsds =
              GfxdTestConfig.getInstance().getFabricServerDescriptions();
      for (FabricServerDescription fsd : fsds.values()) {
        if (fsd.getClientNames().contains(clientName)) {
          log.info("Looked up fabric server config for " + clientName + ":\n"
                                                         + fsd);
          TheFabricServerDescription = fsd; // cache it
          break;
        }
      }
      if (TheFabricServerDescription == null) {
        String s = clientName + " is not wired to any fabric server description"
                 + " using " + BasePrms.nameForKey(FabricServerPrms.clientNames)
                 + ". Either add it or use an alternate method that takes a "
                 + BasePrms.nameForKey(FabricServerPrms.names)
                 + " argument.";
        throw new HydraRuntimeException(s);
      }
    }
    return TheFabricServerDescription;
  }

//------------------------------------------------------------------------------
// Locator
//------------------------------------------------------------------------------

  /**
   * Creates a locator endpoint using the {@link FabricServerDescription}
   * to which this VM is wired using {@link FabricServerPrms#clientNames}.
   * The endpoint need only be created once.
   * <p>
   * During creation, selects a random port and registers the locator {@link
   * Endpoint} in the {@link LocatorBlackboard} map.  The locator will always
   * use this port during startup.
   * <p>
   * All locators associated with a given distributed system must be created
   * before locators are started, so that the locator list is complete.
   * At least one locator must be started before non-locators can connect.
   *
   * @throws HydraRuntimeException if the VM already has a locator that did
   *         not create its endpoint using this method.
   */
  public static synchronized void createLocator() {
    Endpoint endpoint = findEndpoint();
    Locator locator = DistributedSystemHelper.getLocator();
    if (locator == null) {
      if (endpoint == null) {
        generateEndpoint(getFabricServerDescription());
      } // else already created but not running
    } else if (endpoint == null) {
      String s = "Locator was already started without FabricServerHelper"
               + " using an unknown port";
      throw new HydraRuntimeException(s);
    } // else already created and running
    // SHOULD ALSO CHECK RUNNING FABRIC SERVER HERE
  }

  /**
   * Starts a peer locator using the previously created endpoint and the boot
   * properties from its {@link FabricServerDescription}, if it is not already
   * started.
   * <p>
   * This method is synchronized across hydra client VMs to ensure that one
   * locator in each distributed system starts up before the others, to work
   * around Bug 30341.
   * <p>
   * {@link hydra.DistributedSystemHelper#getLocator} can be used to access
   * the underlying GFE locator, if needed.
   *
   * @throws HydraRuntimeException if a non-GemFireXD locator is already
   *                               running in this VM.
   */
  public static void startLocator() {
    _startLocator(null, null);
  }

  /**
   * Starts a server locator with the given network server configuration using
   * the previously created endpoint and the boot properties from its {@link
   * FabricServerDescription}, if it is not already started.
   * <p>
   * This method is synchronized across hydra client VMs to ensure that one
   * locator in each distributed system starts up before the others, to work
   * around Bug 30341.
   * <p>
   * {@link hydra.DistributedSystemHelper#getLocator} can be used to access
   * the underlying GFE locator, if needed.
   *
   * @throws HydraRuntimeException if a non-GemFireXD locator is already
   *                               running in this VM.
   */
  public static void startLocator(String networkServerConfig) {
    _startLocator(networkServerConfig, null);
  }
 
  /**
   * Starts a server locator with the given network server configuration using
   * the previously created endpoint and the given system user/password map,
   * if it is not already started.
   * <p>
   * This method is synchronized across hydra client VMs to ensure that one
   * locator in each distributed system starts up before the others, to work
   * around Bug 30341.
   * <p>
   * {@link hydra.DistributedSystemHelper#getLocator} can be used to access
   * the underlying GFE locator, if needed.
   *
   * @throws HydraRuntimeException if a non-GemFireXD locator is already
   *                               running in this VM.
   */
  public static void startLocator(String networkServerConfig,
                                  Map<String, String> systemUserPasswords) {
    _startLocator(networkServerConfig, systemUserPasswords);
  }

  private static synchronized void _startLocator(String networkServerConfig, 
                                    Map<String, String> systemUserPasswords) {
    Endpoint endpoint = findEndpoint();
    if (endpoint == null) {
      String s = "Locator has not been created yet";
      throw new HydraRuntimeException(s);
    }
    FabricServerDescription fsd = getFabricServerDescription();
    Properties bootProps = fsd.getBootProperties();
    Locator locator = DistributedSystemHelper.getLocator();
    if (systemUserPasswords != null) {
      for (Map.Entry<String, String> entry : systemUserPasswords.entrySet()) {
        String systemUser = entry.getKey();
        String password = entry.getValue();
        bootProps.put(Property.USER_PROPERTY_PREFIX + systemUser, password);
      }
    }
    bootProps.put(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME,
                  fsd.getDistributedSystemId().toString());
    bootProps.put(DistributionConfig.REMOTE_LOCATORS_NAME,
                  fsd.getRemoteLocators());

    if (locator == null) {
      DistributedSystem ds = DistributedSystemHelper.getDistributedSystem();
      if (ds == null) {
        SharedCounters counters = LocatorBlackboard.getInstance(
                       fsd.getDistributedSystem()).getSharedCounters();
        while (counters.incrementAndRead(LocatorBlackboard.locatorLock) != 1) {
          MasterController.sleepForMs(500);
        }
        log.info("Starting gemfirexd locator");
        try {
          log.info("Starting gemfirexd locator \"" + endpoint
                  + "\" using boot properties: " + prettyprint(bootProps));
          FabricServiceManager.getFabricLocatorInstance().start(
                                           endpoint.getAddress(),
                                           endpoint.getPort(),
                                           bootProps);
          if (networkServerConfig != null) {
            NetworkServerHelper.startNetworkLocators(networkServerConfig);
          }
        } catch (SQLException e) {
          String s = "Problem starting gemfirexd locator";
          throw new HydraRuntimeException(s, e);
        }
        log.info("Started locator: " + locator);
        TheFabricServerProperties = bootProps; // cache them
        counters.zero(LocatorBlackboard.locatorLock); // let others proceed

      } else {
        String s = "This VM is already connected to a distributed system. "
                 + "Too late to start a locator";
        throw new HydraRuntimeException(s);
      }
    } else if (TheFabricServerProperties == null) {
      // block attempt to start locator in multiple ways
      String s = "Locator was already started without FabricServerHelper"
               + " using an unknown, and possibly different, configuration";
      throw new HydraRuntimeException(s);

    } else if (!TheFabricServerProperties.equals(bootProps)) {
      // block attempt to connect to system with clashing configuration
      String s = "Already booted using properties "
               + TheFabricServerProperties + ", cannot also use " + bootProps;
      throw new HydraRuntimeException(s);

    } else {
      // make sure this it a running gemfirexd locator
      FabricLocator loc = FabricServiceManager.getFabricLocatorInstance();
      if (loc.status() != FabricService.State.RUNNING) {
        String s = "This VM already contains a non-GemFireXD locator";
        throw new HydraRuntimeException(s);
      }
    } // else it was already started with this configuration, which is fine
  }

  /**
   * Stops the currently running locator, if it exists.
   */
  public static synchronized void stopLocator() {
    stopFabricServer();
  }

//------------------------------------------------------------------------------
// Locator Endpoints
//------------------------------------------------------------------------------

  /**
   * Returns all peer locator endpoints from the {@link LocatorBlackboard}
   * map, a possibly empty list.  This includes all locators that have
   * ever started, regardless of their distributed system or current active
   * status.
   */
  public static synchronized List<Endpoint> getEndpoints() {
    List<Endpoint> endpoints = new ArrayList();
    Collection c = LocatorBlackboard.getInstance().getSharedMap().getMap()
                                    .values();
    endpoints.addAll(c);
    return endpoints;
  }

  /**
   * Returns all peer locator endpoints for the distributed system from the
   * {@link FabricServerDescription} to which this VM is wired using {@link
   * FabricServerPrms#clientNames}.
   * <p>
   * The endpoints are looked up in the {@link LocatorBlackboard} map, a
   * possibly empty list.  This includes all locators that have ever
   * started for this system, regardless of their current active status.
   */
  public static synchronized List<Endpoint> getSystemEndpoints() {
    FabricServerDescription fsd = getFabricServerDescription();
    String distributedSystemName = fsd.getDistributedSystem();
    return getEndpoints(distributedSystemName);
  }

  /**
   * Returns all peer locator endpoints for the specified distributed system
   * from the {@link LocatorBlackboard} map, a possibly empty list.  This
   * includes all locators that have ever started for the system,
   * regardless of their current active status.
   */
  public static synchronized List<Endpoint> getEndpoints(String distributedSystemName) {
    List<Endpoint> endpoints = new ArrayList();
    for (Iterator i = getEndpoints().iterator(); i.hasNext();) {
      Endpoint endpoint = (Endpoint)i.next();
      if (endpoint.getDistributedSystemName().equals(distributedSystemName)) {
        endpoints.add(endpoint);
      }
    }
    return endpoints;
  }

  /**
   * Returns all peer locator endpoints for the specified distributed systems
   * from the {@link LocatorBlackboard} map, a possibly empty list.  This
   * includes all locators that have ever started for the systems,
   * regardless of their current active status.
   */
  public static synchronized List<Endpoint> getEndpoints(List<String> distributedSystemNames) {
    List<Endpoint> endpoints = new ArrayList();
    for (String distributedSystemName : distributedSystemNames) {
      endpoints.addAll(getEndpoints(distributedSystemName));
    }
    return endpoints;
  }

  /**
   * Finds the peer locator endpoint for this VM in the shared {@link
   * LocatorBlackboard} map, if it exists.  Caches the result.
   */
  private static synchronized Endpoint findEndpoint() {
    if (TheLocatorEndpoint == null) {
      Integer vmid = RemoteTestModule.getMyVmid();
      TheLocatorEndpoint = (Endpoint)LocatorBlackboard.getInstance()
                                    .getSharedMap().get(vmid);
    }
    return TheLocatorEndpoint;
  }

  /**
   * Generates a peer locator endpoint with a random port for the given fabric
   * server description and stores it in the shared {@link LocatorBlackboard}
   * map.  Caches the result.
   */
  private static synchronized Endpoint generateEndpoint(
                                       FabricServerDescription fsd) {
    Endpoint endpoint = findEndpoint();
    if (endpoint == null) {
      log.info("Generating peer locator endpoint");
      Integer vmid = RemoteTestModule.getMyVmid();
      String name = RemoteTestModule.getMyClientName();
      String host = HostHelper.getCanonicalHostName();
      String addr = HostHelper.getHostAddress();
      int port = PortHelper.getRandomPort();
      String id = addr + "[" + port + "]";
      String ds = fsd.getDistributedSystem();
      endpoint = new Endpoint(id, name, vmid.intValue(), host, addr, port, ds);
      log.info("Generated peer locator endpoint: " + endpoint);
      LocatorBlackboard.getInstance().getSharedMap().put(vmid, endpoint);
    }
    TheLocatorEndpoint = endpoint; // cache it
    return endpoint;
  }

  /**
   * Represents the endpoint for a peer locator.
   */
  public static class Endpoint implements Serializable {
    String id, name, host, addr, ds;
    int vmid, port;

    /**
     * Creates an endpoint for a locator.
     *
     * @param id   the locator id.
     * @param name the logical hydra client VM name from {@link
     *             ClientPrms#names} found via the {@link
     *             ClientPrms#CLIENT_NAME_PROPERTY} system property.
     * @param vmid the logical hydra client VM ID found via {@link
     *             RemoteTestModule#getMyVmid}.
     * @param host the locator host.
     * @param addr the locator address.
     * @param port the locator port.
     * @param ds   the locator distributed system name.
     */
    public Endpoint(String id, String name, int vmid,
                    String host, String addr, int port, String ds) {
      if (id == null) {
        throw new IllegalArgumentException("id cannot be null");
      }
      if (name == null) {
        throw new IllegalArgumentException("name cannot be null");
      }
      if (host == null) {
        throw new IllegalArgumentException("host cannot be null");
      }
      if (addr == null) {
        throw new IllegalArgumentException("addr cannot be null");
      }
      if (ds == null) {
        throw new IllegalArgumentException("ds cannot be null");
      }

      this.id   = id;
      this.name = name;
      this.vmid = vmid;
      this.host = host;
      this.addr = addr;
      this.port = port;
      this.ds   = ds;
    }

    /**
     * Returns the unique locator logical endpoint ID.
     */
    public String getId() {
      return this.id;
    }

    /**
     * Returns the locator logical VM name.
     */
    public String getName() {
      return this.name;
    }

    /**
     * Returns the locator logical VM ID.
     */
    public int getVmid() {
      return this.vmid;
    }

    /**
     * Returns the locator host.
     */
    public String getHost() {
      return this.host;
    }

    /**
     * Returns the locator address.
     */
    public String getAddress() {
      return this.addr;
    }

    /**
     * Returns the locator port.
     */
    public int getPort() {
      return this.port;
    }

    /**
     * Returns the locator distributed system name.
     */
    public String getDistributedSystemName() {
      return this.ds;
    }

    public boolean equals(Object obj) {
      if (obj instanceof Endpoint) {
        Endpoint endpoint = (Endpoint)obj;
        return endpoint.getId().equals(this.getId())
            && endpoint.getName().equals(this.getName())
            && endpoint.getVmid() == this.getVmid()
            && endpoint.getHost().equals(this.getHost())
            && endpoint.getAddress().equals(this.getAddress())
            && endpoint.getPort() == this.getPort()
            && endpoint.getDistributedSystemName()
                       .equals(this.getDistributedSystemName());
      }
      return false;
    }

    public int hashCode() {
      return this.port;
    }

    /**
     * Returns the endpoint as a string.
     */
    public String toString() {
      return this.id + "(" + this.ds + ":vm_" + this.vmid + "_" + this.name
                     + "_" + this.host + ")";
    }
  }
}
