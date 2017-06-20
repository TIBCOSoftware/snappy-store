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
package com.gemstone.gemfire.management.internal;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.rmi.AlreadyBoundException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import javax.management.MBeanServer;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.management.remote.rmi.RMIJRMPServerImpl;
import javax.management.remote.rmi.RMIServerImpl;
import javax.rmi.ssl.SslRMIClientSocketFactory;

import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.lang.StringUtils;
import com.gemstone.gemfire.internal.tcp.TCPConduit;
import com.gemstone.gemfire.management.ManagementException;
import com.gemstone.gemfire.management.ManagementService;
import com.gemstone.gemfire.management.ManagerMXBean;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Agent implementation that controls the JMX server end points for JMX 
 * clients to connect, such as an RMI server.
 * 
 * The ManagementAgent could be used in a loner or GemFire client to define and
 * control JMX server end points for the Platform MBeanServer and the GemFire
 * MBeans hosted within it. 
 *
 * @author Pivotal Software, Inc.
 * @since 7.0
 */
public class ManagementAgent  {
  
  /**
   * True if running. Protected by synchronizing on this Manager instance. I
   * used synchronization because I think we'll want to hold the same
   * synchronize while configuring, starting, and eventually stopping the
   * RMI server, the hidden management regions (in FederatingManager), etc
   */
  private boolean running = false;
  private Registry registry;
  private JMXConnectorServer cs;
  private final DistributionConfig config;
  private final LogWriterI18n logger;
  private final String PRODUCT ="SnappyData";
  /**
   * This system property is set to true when the embedded HTTP server is started so that the embedded pulse webapp
   * can use a local MBeanServer instead of a remote JMX connection.
   */
  private static final String PULSE_EMBEDDED_PROP = "pulse.embedded";
  
  private static final String PULSE_EMBEDDED_GFXD_PROP = "pulse.embedded.gfxd";
  
  public ManagementAgent(DistributionConfig config, LogWriterI18n logger) {
    this.config = config;
    this.logger = logger;
  }

  private LogWriterI18n getLogger() {
    return this.logger;
  }
  
  public synchronized boolean isRunning() {
    return this.running;
  }
  
  public synchronized void startAgent(){
    startHttpService();
    if (!this.running && this.config.getJmxManagerPort() != 0) {
      try {
        configureAndStart();
      }
      catch (IOException e) {
        throw new ManagementException(e);
      }
      this.running = true;
    }
  }
  
  public synchronized void stopAgent(){
    stopHttpService();
    
    if (!this.running) return;
    
    this.logger.info(LocalizedStrings.DEBUG, "Stopping jmx manager agent");
    try {
      cs.stop();
      UnicastRemoteObject.unexportObject(registry, true);
    } catch (IOException e) {
      throw new ManagementException(e);
    }
    
    this.running = false;
  }
  
  private Server httpServer;

  private void startHttpService() {
    
    GemFireCacheImpl cache = (GemFireCacheImpl)CacheFactory.getAnyInstance();
    
    final SystemManagementService managementService = (SystemManagementService) ManagementService.getManagementService(cache);

    final ManagerMXBean managerBean = managementService.getManagerMXBean();

    if (this.config.getJmxManagerHttpPort() != 0) {
      if (this.logger.infoEnabled()) {
        this.logger.info(LocalizedStrings.DEBUG, String.format(
          "Attempting to start HTTP service on port (%1$d) at bind-address (%2$s)...",
            this.config.getJmxManagerHttpPort(), this.config.getJmxManagerBindAddress()));
      }

      String productHome = StringUtils.EMPTY_STRING;
      String productName = StringUtils.EMPTY_STRING;
      productHome = System.getenv("SNAPPY_HOME");
      if (!StringUtils.isBlank(productHome) ){
        productName = PRODUCT;
      } else {
        if (cache.isGFXDSystem()) {
          productHome = System.getenv("GEMFIREXD");
          productName = "GEMFIREXD";
        } else {
          productHome = System.getenv("GEMFIRE");
          productName = "GEMFIRE";
        }
      }

      // Check for empty variable. if empty, then log message and exit HTTP server startup
      if (StringUtils.isBlank(productHome)) {
        final String message = ManagementStrings.HTTP_SERVICE_CANT_START.toLocalizedString(productName);
        setStatusMessage(managerBean, message);
        this.logger.info(LocalizedStrings.DEBUG, message);
        return;
      }

      // Find the Management WAR file
      final String gemfireWar = getGemFireWarLocation(productHome);

      if (gemfireWar == null) {
        this.logger.info(LocalizedStrings.DEBUG,
          "Unable to find GemFire REST API WAR file; the REST API to GemFire will not be exported and accessible.");
      }

      // Find the Pulse WAR file
      final String pulseWar = getPulseWarLocation(productHome, productName);

      if (pulseWar == null) {
        final String message = "Unable to find Pulse web application WAR file; Pulse will not start in embeded mode";
        setStatusMessage(managerBean, message);
        this.logger.info(LocalizedStrings.DEBUG, message);
      }

      try {
        if (isWebApplicationAvailable(gemfireWar, pulseWar)) {
          final String bindAddress = this.config.getJmxManagerBindAddress();
          final int port = this.config.getJmxManagerHttpPort();
          
          this.httpServer = JettyHelper.initJetty(bindAddress, port, logger);

          if (isWebApplicationAvailable(gemfireWar)) {
            this.httpServer = JettyHelper.addWebApplication(this.httpServer, "/gemfire", gemfireWar);
          }

          if (isWebApplicationAvailable(pulseWar)) {
            this.httpServer = JettyHelper.addWebApplication(this.httpServer, "/pulse", pulseWar);
          }

          if (this.logger.infoEnabled()) {
            this.logger.info(LocalizedStrings.DEBUG, String.format(
              "Starting embedded HTTP server on port (%1$d) at bind-address (%2$s)...",
                ((ServerConnector) this.httpServer.getConnectors()[0]).getPort(), bindAddress));
          }
          System.setProperty(PULSE_EMBEDDED_PROP, "true");
          
          if(productName.equals("GEMFIREXD") || productName.equals("SnappyData")){
            System.setProperty(PULSE_EMBEDDED_GFXD_PROP, "true");
          }
         
          this.httpServer = JettyHelper.startJetty(this.httpServer);

          // now, that the HTTP serever has been started, we can set the URL
          // used by web clients to connect to Pulse.
          if (isWebApplicationAvailable(pulseWar)) {
            managerBean.setPulseURL("http://".concat(getHost(bindAddress))
                .concat(":").concat(String.valueOf(port)).concat("/pulse/"));
          }
        }
      }
      catch (Exception e) {
        setStatusMessage(managerBean, "HTTP service failed to start with " + e.getClass().getSimpleName() + " '" + e.getMessage() + "'");
        throw new ManagementException("HTTP service failed to start", e);
      }
    }
    else {
      setStatusMessage(managerBean, "Embedded HTTP server configured not to start (jmx-manager-http-port=0)");
    }
  }

  private String getHost(final String bindAddress) throws UnknownHostException {
    if (!StringUtils.isBlank(this.config.getJmxManagerHostnameForClients())) {
      return this.config.getJmxManagerHostnameForClients();
    }
    else if (!StringUtils.isBlank(bindAddress)) {
      return InetAddress.getByName(bindAddress).getHostAddress();
    }
    else {
      return SocketCreator.getLocalHost().getHostAddress();
    }
  }

  // Use the GEMFIRE environment variable to find the GemFire product tree.
  // First, look in the $GEMFIRE/tools/Management directory
  // Second, look in the $GEMFIRE/lib directory
  // Finally, if we cannot find Management WAR file then return null...
  private String getGemFireWarLocation(final String gemfireHome) {
    assert !StringUtils.isBlank(gemfireHome) : "The GEMFIRE environment variable must be set!";

    if (new File(gemfireHome + "/tools/Extensions/gemfire.war").isFile()) {
      return gemfireHome + "/tools/Extensions/gemfire.war";
    }
    else if (new File(gemfireHome + "/lib/gemfire.war").isFile()) {
      return gemfireHome + "/lib/gemfire.war";
    }
    else {
      return null;
    }
  }

  // Use the GEMFIRE environment variable to find the GemFire product tree.
  // First, look in the $GEMFIRE/tools/Pulse directory
  // Second, look in the $GEMFIRE/lib directory
  // Finally, if we cannot find the Management WAR file then return null...
  private String getPulseWarLocation(final String productHome, final String productName) {
    assert !StringUtils.isBlank(productHome) : ManagementStrings.ASSERT_PRODUCT_ENV_VAR_MSG.toLocalizedString(productName);

    if (productName.equals(PRODUCT)) {
      String jarLoc = new File(getClass().getProtectionDomain().getCodeSource().getLocation().getPath()).getParent();
      if (new File(jarLoc + "/pulse.war").isFile()) {
        return jarLoc + "/" + "pulse.war";
      }
    }
    if (new File(productHome + "/tools/Pulse/pulse.war").isFile()) {
      return productHome + "/tools/Pulse/pulse.war";
    } else if (new File(productHome + "/lib/pulse.war").isFile()) {
      return productHome + "/lib/pulse.war";
    } else {
      return null;
    }
  }

  private boolean isWebApplicationAvailable(final String warFileLocation) {
    return !StringUtils.isBlank(warFileLocation);
  }

  private boolean isWebApplicationAvailable(final String... warFileLocations) {
    for (String warFileLocation : warFileLocations) {
      if (isWebApplicationAvailable(warFileLocation)) {
        return true;
      }
    }

    return false;
  }

  private void setStatusMessage(ManagerMXBean mBean, String message) {
    mBean.setPulseURL("");
    mBean.setStatusMessage(message);
  }

  private void stopHttpService() {
    if (this.httpServer != null) {
      this.logger.info(LocalizedStrings.DEBUG, "Stopping the HTTP service...");
      try {
        this.httpServer.stop();
      } catch (Exception e) {
        this.logger.warning(LocalizedStrings.DEBUG, "Failed to stop the HTTP service because: " + e);
      } finally {
        try {
          this.httpServer.destroy();
        } catch (Exception ignore) {
          this.logger.error(LocalizedStrings.ERROR, "Failed to properly release resources held by the HTTP service: ", ignore);
        } finally {
          this.httpServer = null;
          System.clearProperty("catalina.base");
          System.clearProperty("catalina.home");
        }
      }
    }
  }

  /**
   * http://docs.oracle.com/javase/6/docs/technotes/guides/management/agent.html#gdfvq
   * https://blogs.oracle.com/jmxetc/entry/java_5_premain_rmi_connectors
   * https://blogs.oracle.com/jmxetc/entry/building_a_remotely_stoppable_connector
   * https://blogs.oracle.com/jmxetc/entry/jmx_connecting_through_firewalls_using
   */
  private void configureAndStart() throws IOException {
    // KIRK: I copied this from https://blogs.oracle.com/jmxetc/entry/java_5_premain_rmi_connectors
    //       we'll need to change this significantly but it's a starting point
    
    // get the port for RMI Registry and RMI Connector Server
    final int port = this.config.getJmxManagerPort();
    final String hostname;
    final InetAddress bindAddr;
    if (this.config.getJmxManagerBindAddress().equals("")) {
      hostname = SocketCreator.getLocalHost().getHostName();
      bindAddr = null;
    } else {
      hostname = this.config.getJmxManagerBindAddress();
      bindAddr = InetAddress.getByName(hostname);
    }
    
    final boolean ssl = this.config.getJmxManagerSSL();

    this.logger.info(LocalizedStrings.DEBUG, "Starting jmx manager agent on port " + port + (bindAddr != null ? (" bound to " + bindAddr) : "") + (ssl ? " using SSL" : ""));

    final SocketCreator sc = SocketCreator.createNonDefaultInstance(ssl,
        this.config.getJmxManagerSSLRequireAuthentication(),
        this.config.getJmxManagerSSLProtocols(),
        this.config.getJmxManagerSSLCiphers(),
        this.config.getJmxSSLProperties());
    RMIClientSocketFactory csf = ssl ? new SslRMIClientSocketFactory() : null;//RMISocketFactory.getDefaultSocketFactory();
      //new GemFireRMIClientSocketFactory(sc, getLogger());
    RMIServerSocketFactory ssf = new GemFireRMIServerSocketFactory(sc, getLogger(), bindAddr);

    // Following is done to prevent rmi causing stop the world gcs
    System.setProperty("sun.rmi.dgc.server.gcInterval", Long.toString(Long.MAX_VALUE-1));
    
    // Create the RMI Registry using the SSL socket factories above.
    // In order to use a single port, we must use these factories 
    // everywhere, or nowhere. Since we want to use them in the JMX
    // RMI Connector server, we must also use them in the RMI Registry.
    // Otherwise, we wouldn't be able to use a single port.
    //
    // Start an RMI registry on port <port>.
    registry = LocateRegistry.createRegistry(port, csf, ssf);
    
    // Retrieve the PlatformMBeanServer.
    final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

    // Environment map. KIRK: why is this declared as HashMap?
    final HashMap<String,Object> env = new HashMap<String,Object>();
    
    String pwFile = this.config.getJmxManagerPasswordFile();
    if (pwFile != null && pwFile.length() > 0) {
      env.put("jmx.remote.x.password.file", pwFile);
    }
    String accessFile =  this.config.getJmxManagerAccessFile();
    if (accessFile != null && accessFile.length() > 0) {
      env.put("jmx.remote.x.access.file", accessFile);
    }
    
    // Manually creates and binds a JMX RMI Connector Server stub with the 
    // registry created above: the port we pass here is the port that can  
    // be specified in "service:jmx:rmi://"+hostname+":"+port - where the
    // RMI server stub and connection objects will be exported.
    // Here we choose to use the same port as was specified for the   
    // RMI Registry. We can do so because we're using \*the same\* client
    // and server socket factories, for the registry itself \*and\* for this
    // object.
    final RMIServerImpl stub = new RMIJRMPServerImpl(port, csf, ssf, env);
    
    // Create an RMI connector server.
    //
    // As specified in the JMXServiceURL the RMIServer stub will be
    // registered in the RMI registry running in the local host on
    // port <port> with the name "jmxrmi". This is the same name the
    // out-of-the-box management agent uses to register the RMIServer
    // stub too.
    //
    // The port specified in "service:jmx:rmi://"+hostname+":"+port
    // is the second port, where RMI connection objects will be exported.
    // Here we use the same port as that we choose for the RMI registry. 
    // The port for the RMI registry is specified in the second part
    // of the URL, in "rmi://"+hostname+":"+port
    //
    // We construct a JMXServiceURL corresponding to what we have done
    // for our stub...
    final JMXServiceURL url = new JMXServiceURL(
        "service:jmx:rmi://"+hostname+":"+port+"/jndi/rmi://"+hostname+":"+port+"/jmxrmi");
    
    // Create an RMI connector server with the JMXServiceURL
    //    
    // KIRK: JDK 1.5 cannot use JMXConnectorServerFactory because of 
    // http://bugs.sun.com/view_bug.do?bug_id=5107423
    // but we're using JDK 1.6
    cs = new RMIConnectorServer(new JMXServiceURL("rmi",hostname,port),
          env,stub,mbs) {
      @Override
      public JMXServiceURL getAddress() { return url;}

      @Override
      public synchronized void start() throws IOException {
        try {
          registry.bind("jmxrmi", stub);
        } catch (AlreadyBoundException x) {
          final IOException io = new IOException(x.getMessage());
          io.initCause(x);
          throw io;
        }
        super.start();
      }
    };
    // This may be the 1.6 way of doing it but the problem is it does not use our "stub".
    //cs = JMXConnectorServerFactory.newJMXConnectorServer(url, env, mbs);
       
    
    // Start the RMI connector server.
    //
    //System.out.println("Start the RMI connector server on port "+port);
    cs.start();
    this.logger.info(LocalizedStrings.DEBUG, "Finished starting jmx manager agent.");
    //System.out.println("Server started at: "+cs.getAddress());

    // Start the CleanThread daemon... KIRK: not sure what CleanThread is...
    //
    //final Thread clean = new CleanThread(cs);
    //clean.start();
  }
  
  private static class GemFireRMIClientSocketFactory implements RMIClientSocketFactory, Serializable {
    private static final long serialVersionUID = -7604285019188827617L;
    
    private /*final hack to prevent serialization*/ transient SocketCreator sc;
    private /*final hack to prevent serialization*/ transient LogWriterI18n logger;
    
    public GemFireRMIClientSocketFactory(SocketCreator sc, LogWriterI18n logger) {
      this.sc = sc;
      this.logger = logger;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      return this.sc.connectForClient(host, port, this.logger, 0/*no timeout*/);
    }
  };
  private static class GemFireRMIServerSocketFactory implements RMIServerSocketFactory, Serializable {
    private static final long serialVersionUID = -811909050641332716L;
    private /*final hack to prevent serialization*/ transient SocketCreator sc;
    private /*final hack to prevent serialization*/ transient LogWriterI18n logger;
    private final InetAddress bindAddr;
    
    public GemFireRMIServerSocketFactory(SocketCreator sc, LogWriterI18n logger, InetAddress bindAddr) {
      this.sc = sc;
      this.logger = logger;
      this.bindAddr = bindAddr;
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
      return this.sc.createServerSocket(port, TCPConduit.getBackLog(), this.bindAddr, this.logger);
    }
  };
}
