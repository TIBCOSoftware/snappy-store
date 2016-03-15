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
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.GemFireCache;
import com.gemstone.gemfire.cache.client.internal.locator.*;
import com.gemstone.gemfire.cache.client.internal.locator.wan.*;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem.ConnectListener;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem.DisconnectListener;
import com.gemstone.gemfire.distributed.internal.membership.QuorumChecker;
import com.gemstone.gemfire.distributed.internal.membership.jgroup.GFJGBasicAdapter;
import com.gemstone.gemfire.distributed.internal.membership.jgroup.JGroupMember;
import com.gemstone.gemfire.distributed.internal.membership.jgroup.LocatorImpl;
import com.gemstone.gemfire.distributed.internal.tcpserver.TcpClient;
import com.gemstone.gemfire.distributed.internal.tcpserver.TcpHandler;
import com.gemstone.gemfire.distributed.internal.tcpserver.TcpServer;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.ManagerLogWriter;
import com.gemstone.gemfire.internal.SecurityLogWriter;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.admin.remote.DistributionLocatorId;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.management.internal.JmxManagerLocator;
import com.gemstone.gemfire.management.internal.JmxManagerLocatorRequest;
import com.gemstone.gemfire.management.internal.JmxManagerLocatorResponse;
import com.gemstone.org.jgroups.Address;
import com.gemstone.org.jgroups.JChannel;
import com.gemstone.org.jgroups.stack.GossipData;
import com.gemstone.org.jgroups.stack.GossipServer;

/**
 * Provides the implementation of a distribution <code>Locator</code>
 * as well as internal-only functionality.  Currently, a distribution
 * locator is implemented using a JGroups {@link GossipServer}.
 * 
 * This class has APIs that perform essentially three layers of 
 * services. At the bottom layer is the JGroups location service. On
 * top of that you can start a distributed system. And then on top
 * of that you can start server location services.
 * 
 * Server Location Service
 * DistributedSystem
 * Peer Location Service
 * 
 * The startLocator() methods provide a way to start all three
 * services in one call. Otherwise, the services can be started 
 * independently
 * <code>
 * locator = createLocator()
 * locator.startPeerLocation();
 * locator.startDistributeSystem();
 *
 * @author David Whitlock
 * @since 4.0
 */
public class InternalLocator extends Locator implements ConnectListener {

  /** How long (in milliseconds) a member that we haven't heard from
   * in a while should live before we call it dead? */
  private static final long EXPIRY_MS = 60000; // one minute
  
  /** system property name for forcing an locator distribution manager type */
  public static final String FORCE_LOCATOR_DM_TYPE = "Locator.forceLocatorDMType";
  
  /** system property name for inhibiting DM banner */
  public static final String INHIBIT_DM_BANNER = "Locator.inhibitDMBanner";

  /////////////////////  Instance Fields  //////////////////////

  /** The logger to which this locator logs */
  private LogWriterI18n logger;

  /** The security logger to which this locator logs */
  private LogWriterI18n securityLogger;

  /** The tcp server responding to locator requests */
  private final TcpServer server;

  /**
   * @since 5.7
   */
  private final PrimaryHandler handler;
  
  /** The distributed system owned by this locator, if any.
   * Note that if a ds already exists because the locator is
   * being colocated in a normal member this field will be null.
   */
  private InternalDistributedSystem myDs;
  /** The cache owned by this locator, if any.
   * Note that if a cache already exists because the locator is
   * being colocated in a normal member this field will be null.
   */
  private Cache myCache;
  
  /** locator state file */
  private File stateFile;
  
  /** product use logging */
  private ProductUseLog productUseLog;
  
  private boolean peerLocator;
  
  private ServerLocator serverLocator;
  
  protected volatile LocatorStats stats;

  //TODO - these to properties are a waste of memory once
  //the system is started.
  private Properties env;
  
  /** the gossip server used for peer location */
  private LocatorImpl locatorImpl;
  
  private DistributionConfigImpl config;

  private final LocatorMembershipListenerImpl locatorListener;
  
  private ConcurrentMap<Integer, Set<DistributionLocatorId>> allLocatorsInfo = new ConcurrentHashMap<Integer, Set<DistributionLocatorId>>();
  
  private ConcurrentMap<Integer, Set<String>> allServerLocatorsInfo = new ConcurrentHashMap<Integer, Set<String>>();

  /** whether the locator was stopped during forced-disconnect processing but a reconnect will occur */
  private volatile boolean stoppedForReconnect;
  
  private final AtomicBoolean shutdownHandled = new AtomicBoolean(false);
  
  private final ExecutorService _executor;
  
  private Thread restartThread;
  
  
  {
    final LogWriterImpl.LoggingThreadGroup loggerGroup = LogWriterImpl
        .createThreadGroup("WAN Locator Discovery Logger Group", this.logger);

    ThreadFactory tf = new ThreadFactory() {
      public Thread newThread(Runnable command) {
        Thread thread = new Thread(loggerGroup, command,
            "WAN Locator Discovery Thread");
        thread.setDaemon(true);
        return thread;
      }
    };
    this._executor = Executors.newCachedThreadPool(tf);
  }

  //////////////////////  Static Methods  /////////////////////
  
  /** the locator hosted by this JVM. As of 7.0 it is a singleton. */
  private static InternalLocator locator; // must synchronize on locatorLock
  private static final Object locatorLock = new Object();

  public static InternalLocator getLocator() {
    // synchronize in order to fix #46336 (race condition in createLocator)
    synchronized (locatorLock) {
      return locator;
    }
  }
  public static boolean hasLocator() {
    synchronized (locatorLock) {
      return locator != null;
    }
  }
  private static boolean removeLocator(InternalLocator l) {
    if (l == null) return false;
    synchronized (locatorLock) {
      if (hasLocator()) {
        if (l.equals(locator)) {
          locator = null;
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Create a locator that listens on a given port. This locator will not have
   * peer or server location services available until they are started by
   * calling startServerLocation or startPeerLocation on the locator object.
   * 
   * @param port
   *                the tcp/ip port to listen on
   * @param logFile
   *                the file that log messages should be written to
   * @param stateFile
   *                the file that state should be read from / written to for recovery               
   * @param logger
   *                a log writer that should be used (logFile parameter is
   *                ignored)
   * @param securityLogger
   *                the logger to be used for security related log messages
   * @param distributedSystemProperties
   *                optional properties to configure the distributed system
   *                (e.g., mcast addr/port, other locators)
   * @param startDistributedSystem if true then this locator will also start its own ds
   */
  public static InternalLocator createLocator(
      int port,
      File logFile,
      File stateFile,
      LogWriterI18n logger,
      LogWriterI18n securityLogger,
      InetAddress bindAddress,
      String hostnameForClients,
      java.util.Properties distributedSystemProperties, boolean startDistributedSystem) throws IOException {
    synchronized (locatorLock) {
      if (hasLocator()) {
        throw new IllegalStateException("A locator can not be created because one already exists in this JVM.");
      }
      InternalLocator l = new InternalLocator(port, logFile, stateFile, logger, securityLogger, bindAddress, hostnameForClients, distributedSystemProperties, null, startDistributedSystem);
      locator = l;
      return l;
    }
  }
  
  private static void setLocator(InternalLocator l) {
    synchronized(locatorLock) {
      if (locator != null  &&  locator != l) {
        throw new IllegalStateException("A locator can not be created because one already exists in this JVM.");
      }
      locator = l;
    }
  }

  
  /**
   * Creates a distribution locator that runs in this VM on the given
   * port and bind address and creates a distributed system.
   * 
   * @param port
   *    the tcp/ip port to listen on
   * @param logFile
   *    the file that log messages should be written to
   * @param logger
   *    a log writer that should be used (logFile parameter is ignored)
   * @param securityLogger
   *    the logger to be used for security related log messages
   * @param dsProperties
   *    optional properties to configure the distributed system (e.g., mcast addr/port, other locators)
   * @param peerLocator
   *    enable peer location services
   * @param enableServerLocator
   *    enable server location services
   * @param hostnameForClients
   *    the name to give to clients for connecting to this locator
   * @throws IOException 
   * @since 7.0
   */
  public static InternalLocator startLocator(
      int port,
      File logFile,
      File stateFile,
      LogWriterI18n logger,
      LogWriterI18n securityLogger,
      InetAddress bindAddress,
      java.util.Properties dsProperties,
      boolean peerLocator, 
      boolean enableServerLocator,
      String hostnameForClients
      )
      throws IOException
    {
    return startLocator(port, logFile, stateFile, logger, securityLogger, bindAddress, true, dsProperties, peerLocator, enableServerLocator, hostnameForClients);
    }
  /**
   * Creates a distribution locator that runs in this VM on the given
   * port and bind address.
   * 
   * @param port
   *    the tcp/ip port to listen on
   * @param logFile
   *    the file that log messages should be written to
   * @param logger
   *    a log writer that should be used (logFile parameter is ignored)
   * @param securityLogger
   *    the logger to be used for security related log messages
   * @param startDistributedSystem
   *    if true, a distributed system is started
   * @param dsProperties
   *    optional properties to configure the distributed system (e.g., mcast addr/port, other locators)
   * @param peerLocator
   *    enable peer location services
   * @param enableServerLocator
   *    enable server location services
   * @param hostnameForClients
   *    the name to give to clients for connecting to this locator
   * @throws IOException 
   * @deprecated as of 7.0 use startLocator(int, File, File, LogWriterI18n, LogWriterI18n, InetAddress, java.util.Properties, boolean, boolean, String) instead.
   */
  public static InternalLocator startLocator(
    int port,
    File logFile,
    File stateFile,
    LogWriterI18n logger,
    LogWriterI18n securityLogger,
    InetAddress bindAddress,
    boolean startDistributedSystem,
    java.util.Properties dsProperties,
    boolean peerLocator, 
    boolean enableServerLocator,
    String hostnameForClients
    )
    throws IOException
  {

    if(!peerLocator && !enableServerLocator) {
      throw new IllegalArgumentException(LocalizedStrings
          .InternalLocator_EITHER_PEER_LOCATOR_OR_SERVER_LOCATOR_MUST_BE_ENABLED
          .toLocalizedString());
    }
    
    System.setProperty(FORCE_LOCATOR_DM_TYPE, "true");
    InternalLocator slocator = null;
    boolean startedLocator = false;
    try {
      
    slocator = createLocator(port, logFile, stateFile, logger, securityLogger, bindAddress, hostnameForClients, dsProperties, startDistributedSystem);
    
    if (enableServerLocator) {
      slocator.handler.willHaveServerLocator = true;
    }
    
    if(peerLocator)  {
      slocator.startPeerLocation(startDistributedSystem);
    }
    if(startDistributedSystem) {
      slocator.startDistributedSystem();
      // fix bug #46324
      final InternalDistributedSystem ids = (InternalDistributedSystem)slocator.myDs;
      if (ids != null) {
        ids.getDistributionManager().addHostedLocators(ids.getDistributedMember(), getLocatorStrings());
      }
    }
    // during the period when the product is using only paper licenses we always
    // start server location services in order to be able to log information
    // about the use of cache servers
//    if(enableServerLocator) {
//      slocator.startServerLocation(InternalDistributedSystem.getConnectedInstance());
//  }
    InternalDistributedSystem sys = InternalDistributedSystem.getConnectedInstance();
    if (sys != null) {
      slocator.startServerLocation(sys);
    }
    
    slocator.endStartLocator(null);
    startedLocator = true;
    return slocator;

    } finally {
      System.getProperties().remove(FORCE_LOCATOR_DM_TYPE);
      if (!startedLocator) {
        // fix for bug 46314
        removeLocator(slocator);
      }
    }
  }
  
  /***
   * Determines if this VM is a locator which must ignore a shutdown.
   * @return true if this VM is a locator which should ignore a shutdown , false if it is a normal member.
   */
  public static boolean isDedicatedLocator() {
    InternalLocator internalLocator = getLocator();
    if (internalLocator == null)
      return false;
    
    InternalDistributedSystem ids = (InternalDistributedSystem)internalLocator.myDs;
    if (ids == null) {
      return false;
    }
    DM dm = ids.getDistributionManager();
    if (dm.isLoner()) {
      return false;
    }
    DistributionManager distMgr = (DistributionManager)ids.getDistributionManager();
    return distMgr.getDMType() == DistributionManager.LOCATOR_DM_TYPE;
  }
  
  public static LocatorStatusResponse statusLocator(int port, InetAddress bindAddress) throws IOException {
    //final int timeout = (60 * 2 * 1000); // 2 minutes
    final int timeout = Integer.MAX_VALUE; // 2 minutes

    try {
      return (LocatorStatusResponse) TcpClient.requestToServer(bindAddress, port,
          new LocatorStatusRequest(), timeout, true);
    }
    catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Stops the distribution locator that runs on the given port and
   * bind address.
   */
  public static void stopLocator(int port, InetAddress bindAddress) 
    throws ConnectException {
    TcpClient.stop(bindAddress, port);
  }

  /**
   * Returns information about the locator running on the given host
   * and port or <code>null</code> if the information cannot be
   * obtained.  Two <code>String</code>s are returned: the first
   * string is the working directory of the locator and the second
   * string is the product directory of the locator.
   */
  public static String[] getLocatorInfo(InetAddress host, int port) {
    return TcpClient.getInfo(host, port);
  }

  ///////////////////////  Constructors  //////////////////////
  
  /**
   * Creates a new <code>Locator</code> with the given port, log file, logger,
   * and bind address.
   * 
   * @param port
   *                the tcp/ip port to listen on
   * @param logF
   *                the file that log messages should be written to
   * @param stateF
   *    the file that state should be read from / written to for recovery
   * @param logwriter
   *                a log writer that should be used (logFile parameter is
   *                ignored)
   * @param securityLogger
   *                the logger to be used for security related log messages
   * @param hostnameForClients
   *    the name to give to clients for connecting to this locator
   * @param distributedSystemProperties
   *                optional properties to configure the distributed system
   *                (e.g., mcast addr/port, other locators)
   * @param cfg the config if being called from a distributed system; otherwise null.
   * @param startDistributedSystem if true locator will start its own distributed system
   * @throws IOException
   */
  private InternalLocator(
    int port,
    File logF,
    File stateF,
    LogWriterI18n logwriter,
    LogWriterI18n securityLogger,
    InetAddress bindAddress,
    String hostnameForClients,
    java.util.Properties distributedSystemProperties, DistributionConfigImpl cfg, boolean startDistributedSystem) {
    this.port = port;
    this.logFile = logF;
    this.bindAddress = bindAddress;
    this.hostnameForClients = hostnameForClients;
    if (stateF == null) {
      this.stateFile = new File("locator" + port + "state.dat");
    }
    else {
      this.stateFile = stateF;
    }
    File productUseFile = new File("locator"+port+"views.log");
    this.productUseLog = new ProductUseLog(productUseFile);
    this.config = cfg;
    
    env = new Properties();

    // set bind-address explicitly only if not wildcard and let any explicit
    // value in distributedSystemProperties take precedence (#46870)
    if (bindAddress != null && !bindAddress.isAnyLocalAddress()) {
      env.setProperty(DistributionConfig.BIND_ADDRESS_NAME,
          bindAddress.getHostAddress());
    }
    
    

    if (distributedSystemProperties != null) {
      env.putAll(distributedSystemProperties);
    }
    env.setProperty(DistributionConfig.CACHE_XML_FILE_NAME, "");

    // create a DC so that all of the lookup rules, gemfire.properties, etc,
    // are considered and we have a config object we can trust
    if (this.config == null) {
      this.config = new DistributionConfigImpl(env);
      this.env.clear();
      this.env.putAll(this.config.getProps());
    }
    
    if (logwriter == null
        && logFile != null
        && this.config.getLogFile().toString().equals(DistributionConfig.DEFAULT_LOG_FILE.toString())) {
      config.unsafeSetLogFile(logFile);
    }

    // create a log writer that will be used by both the GossipServer
    // and by the distributed system
    if (logwriter == null) {
      FileOutputStream[] fos = new FileOutputStream[1];
      this.logger = InternalDistributedSystem.createLogWriter(true, false,
          false, config, !startDistributedSystem, fos);
      if (fos[0] != null) {
        env.put(DistributionConfig.LOG_OUTPUTSTREAM_NAME, fos[0]);
      }
    }
    else {
      this.logger = logwriter;
    }
    this.logger.fine("LogWriter for locator is created.");

    if (securityLogger == null) {
      FileOutputStream[] fos = new FileOutputStream[1];

      File securityLogFile = config.getSecurityLogFile();
      if (securityLogFile == null || securityLogFile.equals(new File(""))) {
        // fix for bug 42562
        this.securityLogger = new SecurityLogWriter(config
                 .getSecurityLogLevel(), this.logger.convertToLogWriter());
      } else {
        this.securityLogger = InternalDistributedSystem.createLogWriter(true,
          false, true, config, false, fos);
      }
      if (fos[0] != null) {
        env.put(DistributionConfig.SECURITY_LOG_OUTPUTSTREAM_NAME, fos[0]);
      }
    }
    else {
      this.securityLogger = securityLogger;
    }
    this.securityLogger.fine("SecurityLogWriter for locator is created.");

    this.locatorListener = new LocatorMembershipListenerImpl(this);
    this.handler = new PrimaryHandler(this.port, this.logger,
        this, locatorListener);

    com.gemstone.org.jgroups.util.GemFireTracer.getLog(
        InternalLocator.class).setLogWriter(this.logger);
    com.gemstone.org.jgroups.util.GemFireTracer.getLog(
        InternalLocator.class).setSecurityLogWriter(this.securityLogger);
    ThreadGroup group = LogWriterImpl.createThreadGroup("Distribution locators", logger);
    stats = new LocatorStats();
    server = new TcpServer(this.port, this.bindAddress, null, this.config,
        this.handler, new DelayedPoolStatHelper(), group, this.toString());
  }

  private void startTcpServer() throws IOException {
    this.logger.info( LocalizedStrings.InternalLocator_STARTING_0, this);
    server.start();
    
    try { 
      Thread.sleep(1000); 
    } 
    catch (InterruptedException ie) {
      // always safe to exit this thread...
      Thread.currentThread().interrupt();
      this.logger.warning(LocalizedStrings.ONE_ARG, "Interrupted", ie);
    }
  }

  public DistributionConfigImpl getConfig() {
    return config;
  }
  
  public LogWriterI18n getLogger() {
    return logger;
  }

  /**
   * Start peer location in this locator. If you plan on starting a distributed
   * system later, this method should be called first so that the distributed
   * system can use this locator.
   * 
   * @param withDS true if a distributed system has been or will be started
   * @throws IOException
   * @since 5.7
   */
  public void startPeerLocation(boolean withDS) throws IOException {
    if(isPeerLocator()) {
      throw new IllegalStateException(LocalizedStrings.InternalLocator_PEER_LOCATION_IS_ALREADY_RUNNING_FOR_0.toLocalizedString(this));
    }
    this.logger.info(LocalizedStrings.InternalLocator_STARTING_PEER_LOCATION_FOR_0, this);
    
    String locatorsProp = this.config.getLocators();
    
    // check for settings that would require only locators to hold the
    // coordinator - e.g., security and network-partition detection
    boolean locatorsAreCoordinators = false;
    boolean networkPartitionDetectionEnabled = this.config.getEnableNetworkPartitionDetection();
    if (networkPartitionDetectionEnabled) {
      locatorsAreCoordinators = true;
    }
    else {
      // check if security is enabled
      String prop = this.config.getSecurityPeerAuthInit();
      locatorsAreCoordinators =  (prop != null && prop.length() > 0);
      if (!locatorsAreCoordinators) {
        locatorsAreCoordinators = Boolean.getBoolean("gemfire.disable-floating-coordinator");
      }
    }
    if (locatorsAreCoordinators) {
      this.logger.config(LocalizedStrings.InternalLocator_FORCING_GROUP_COORDINATION_INTO_LOCATORS);
    }

    // LOG: moved these into InternalDistributedSystem.initialize -- the only other code path constructs InternalLocator 1st which also sets these
    //com.gemstone.org.jgroups.util.GemFireTracer.setLogWriter(this.logWriter);
    //com.gemstone.org.jgroups.util.GemFireTracer
    //    .setSecurityLogWriter(this.securityLogWriter);

    // install gemfire serialization and socket functions into jgroups
    JChannel.setDefaultGFFunctions(new GFJGBasicAdapter());
    this.locatorImpl = new LocatorImpl(port, EXPIRY_MS,
        this.bindAddress, this.stateFile, locatorsProp, locatorsAreCoordinators,
        networkPartitionDetectionEnabled, withDS
        );
    this.handler.addHandler(GossipData.class, this.locatorImpl);
    peerLocator = true;
    if(!server.isAlive()) {
      startTcpServer();
    }
  }

  /**
   * @return the gossipServer
   */
  public LocatorImpl getLocatorHandler() {
    return this.locatorImpl;
  }

  /**
   * Start a distributed system whose life cycle is managed by this locator. When
   * the locator is stopped, this distributed system will be disconnected. If a
   * distributed system already exists, this method will have no affect.
   * 
   * @throws UnknownHostException
   * @since 5.7
   */
  public void startDistributedSystem() throws UnknownHostException {
    InternalDistributedSystem existing = InternalDistributedSystem.getConnectedInstance();
    if (existing != null) {
      this.logger.config(LocalizedStrings.InternalLocator_USING_EXISTING_DISTRIBUTED_SYSTEM__0, existing);
      if (getLocatorHandler() != null) {
        // let the GossipServer know the system's address so they can start
        // servicing requests
        Address addr = ((JGroupMember)existing.getDistributedMember().getNetMember()).getAddress();
        getLocatorHandler().setLocalAddress(addr);
      }
      // don't set the ds variable, so it won't be closed by the locator shutting down
      startCache(existing);
    }
    else {
      if (System.getProperty("p2p.joinTimeout", "").length() == 0) {
          System.setProperty("p2p.joinTimeout", "5000");
        }

      String thisLocator;
      {
        StringBuilder sb = new StringBuilder(100);
        if (bindAddress != null) {
          sb.append(bindAddress.getHostAddress());
        }
        else {
          sb.append(SocketCreator.getLocalHost().getHostAddress());
        }
        sb.append('[').append(port).append(']');
        thisLocator = sb.toString();
      }
      

      if(peerLocator) {
          // append this locator to the locators list from the config properties
          //this.logger.config("ensuring that this locator is in the locators list");
          boolean setLocatorsProp = false;
          String locatorsProp = this.config.getLocators();
          if (locatorsProp != null && locatorsProp.trim().length() > 0) {
            if (!locatorsProp.contains(thisLocator)) {
              locatorsProp = locatorsProp + "," + thisLocator;
              setLocatorsProp = true;
            }
          }
          else {
            locatorsProp = thisLocator;
            setLocatorsProp = true;
          }
          if (setLocatorsProp) {
            Properties updateEnv = new Properties();
            updateEnv.setProperty(DistributionConfig.LOCATORS_NAME, locatorsProp);
            this.config.setApiProps(updateEnv);
            // fix for bug 41248
            String propName = DistributionConfig.GEMFIRE_PREFIX +
                                 DistributionConfig.LOCATORS_NAME;
            if (System.getProperty(propName) != null) {
              System.setProperty(propName, locatorsProp);
            }
          }

          // No longer default mcast-port to zero. See 46277.
        }

        
        Properties connectEnv = new Properties();
        // patch in the logwriter parameter, or the logwriter created
        // using a DistributionConfig earlier in this method
        connectEnv.put(DistributionConfig.LOG_WRITER_NAME, this.logger);
        connectEnv.put(DistributionConfig.DS_CONFIG_NAME, this.config);
        if (this.env.containsKey(DistributionConfig.LOG_OUTPUTSTREAM_NAME)) {
          connectEnv.put(DistributionConfig.LOG_OUTPUTSTREAM_NAME, this.env.get(DistributionConfig.LOG_OUTPUTSTREAM_NAME));
        }
        if (this.env.containsKey(DistributionConfig.SECURITY_LOG_OUTPUTSTREAM_NAME)) {
          connectEnv.put(DistributionConfig.SECURITY_LOG_OUTPUTSTREAM_NAME, this.env.get(DistributionConfig.SECURITY_LOG_OUTPUTSTREAM_NAME));
        }

        this.logger.info(LocalizedStrings.InternalLocator_STARTING_DISTRIBUTED_SYSTEM);
        if (this.logger.configEnabled()) {
          this.logger.config(LocalizedStrings.InternalDistributedSystem_STARTUP_CONFIGURATIONN_0, this.config.toLoggerString());
        }

        myDs = (InternalDistributedSystem)DistributedSystem.connect(connectEnv);
        
        myDs.addDisconnectListener(new DisconnectListener() {
          @Override
          public void onDisconnect(InternalDistributedSystem sys) {
            stop(false, false);
          }
        });
        
        startCache(myDs);
        
        logger.info(LocalizedStrings.InternalLocator_LOCATOR_STARTED_ON__0, thisLocator);
          
          ((InternalDistributedSystem)myDs).setDependentLocator(this);

    }
  }
  
  private void startCache(DistributedSystem ds) {
    GemFireCacheImpl gfc = GemFireCacheImpl.getInstance();
    if (gfc == null) {
      this.logger.info(LocalizedStrings.ONE_ARG, "Creating cache for locator.");
      this.myCache = new CacheFactory(ds.getProperties()).create();
      gfc = (GemFireCacheImpl)this.myCache;
    } else {
      this.logger.info(LocalizedStrings.ONE_ARG, "Using existing cache for locator.");
      ((InternalDistributedSystem) ds).handleResourceEvent(
          ResourceEvent.LOCATOR_START, this);

    }
    startJmxManagerLocationService(gfc);
 
  }
  
  /**
   * End the initialization of the locator. This method should
   * be called once the location services and distributed
   * system are started.
   * 
   * @param distributedSystem
   *                The distributed system to use for the statistics.
   *                
   * @since 5.7
   * 
   * @throws UnknownHostException
   */
  public void endStartLocator(InternalDistributedSystem distributedSystem) throws UnknownHostException {
    env = null;
    if (distributedSystem == null) {
      distributedSystem = InternalDistributedSystem.getConnectedInstance();
    }
    if(distributedSystem != null) {
      onConnect(distributedSystem);
    } else {
      InternalDistributedSystem.addConnectListener(this);
    }
    //TODO: Kishor : I dont want old versions to execute this code. As of now I dont have any way. 
    exchangeLocalLocators(config, locatorListener);
    exchangeRemoteLocators(config, locatorListener);
  }
  
  /**
   * Start server location services in this locator. Server location
   * can only be started once there is a running distributed system.
   * 
   * @param distributedSystem
   *                The distributed system which the server location services
   *                should use. If null, the method will try to find an already
   *                connected distributed system.
   * @throws ExecutionException 
   * @since 5.7
   */
  public void startServerLocation(InternalDistributedSystem distributedSystem)
    throws IOException
  {
    if(isServerLocator()) {
      throw new IllegalStateException(LocalizedStrings.InternalLocator_SERVER_LOCATION_IS_ALREADY_RUNNING_FOR_0.toLocalizedString(this));
    }
    this.logger.info(LocalizedStrings.InternalLocator_STARTING_SERVER_LOCATION_FOR_0, this);
    
    if (distributedSystem == null) {
      distributedSystem = InternalDistributedSystem.getConnectedInstance();
      if (distributedSystem == null) {
        throw new IllegalStateException(LocalizedStrings.InternalLocator_SINCE_SERVER_LOCATION_IS_ENABLED_THE_DISTRIBUTED_SYSTEM_MUST_BE_CONNECTED.toLocalizedString());
      }
    }

    this.productUseLog.monitorUse(distributedSystem);
    
    ServerLocator sl = new ServerLocator(this.port, 
                                         this.bindAddress,
                                         this.hostnameForClients,
                                         this.logFile,
                                         this.productUseLog,
                                         getConfig().getName(),
                                         distributedSystem,
                                         stats);
    this.handler.addHandler(LocatorListRequest.class, sl);
    this.handler.addHandler(ClientConnectionRequest.class, sl);
    this.handler.addHandler(QueueConnectionRequest.class, sl);
    this.handler.addHandler(ClientReplacementRequest.class, sl);
    this.handler.addHandler(GetAllServersRequest.class, sl);
    this.handler.addHandler(LocatorStatusRequest.class, sl);
    this.serverLocator = sl;
    if(!server.isAlive()) {
      startTcpServer();
    }
  }

 /**
   * For WAN 70 Exchange the locator information within the distributed system
   * 
   * @param config
   */
  private void exchangeLocalLocators(DistributionConfigImpl config, LocatorMembershipListener locatorListener) {
    String localLocator = config.getStartLocator();
    DistributionLocatorId locatorId = null;
    if (localLocator.equals(DistributionConfig.DEFAULT_START_LOCATOR)) {
      locatorId = new DistributionLocatorId(this.port, config.getBindAddress());
    }
    else {
      locatorId = new DistributionLocatorId(localLocator);
    }
    LocatorHelper.addLocator(config.getDistributedSystemId(), locatorId, this, locatorListener, null);

    RemoteLocatorJoinRequest request = buildRemoteDSJoinRequest(config);
    StringTokenizer locatorsOnThisVM = new StringTokenizer(
        config.getLocators(), ",");
    while (locatorsOnThisVM.hasMoreTokens()) {
      DistributionLocatorId localLocatorId = new DistributionLocatorId(
          locatorsOnThisVM.nextToken());
      if (!locatorId.equals(localLocatorId)) {
        LocatorDiscovery localDiscovery = new LocatorDiscovery(this,
            localLocatorId, request, logger, locatorListener);
        LocatorDiscovery.LocalLocatorDiscovery localLocatorDiscovery = localDiscovery.new LocalLocatorDiscovery();
        this._executor.execute(localLocatorDiscovery);
      }
    }
  }

  /**
   * For WAN 70 Exchange the locator information across the distributed systems
   * (sites)
   * 
   * @param config
   */
  private void exchangeRemoteLocators(DistributionConfigImpl config, LocatorMembershipListener locatorListener) {
    RemoteLocatorJoinRequest request = buildRemoteDSJoinRequest(config);
    String remoteDustributedSystems = config.getRemoteLocators();
    if (remoteDustributedSystems.length() > 0) {
      StringTokenizer remoteLocators = new StringTokenizer(
          remoteDustributedSystems, ",");
      while (remoteLocators.hasMoreTokens()) {
        DistributionLocatorId remoteLocatorId = new DistributionLocatorId(
            remoteLocators.nextToken());
        LocatorDiscovery localDiscovery = new LocatorDiscovery(this,
            remoteLocatorId, request, this.logger, locatorListener);
        LocatorDiscovery.RemoteLocatorDiscovery remoteLocatorDiscovery = localDiscovery.new RemoteLocatorDiscovery();
        this._executor.execute(remoteLocatorDiscovery);
      }
    }
  }
  
  private RemoteLocatorJoinRequest buildRemoteDSJoinRequest(
      DistributionConfigImpl config) {
    String localLocator = config.getStartLocator();
    DistributionLocatorId locatorId = null;
    if (localLocator.equals(DistributionConfig.DEFAULT_START_LOCATOR)) {
      locatorId = new DistributionLocatorId(this.port, config.getBindAddress());
    }
    else {
      locatorId = new DistributionLocatorId(localLocator);
    }
    RemoteLocatorJoinRequest request = new RemoteLocatorJoinRequest(
        config.getDistributedSystemId(), locatorId, "");
    return request;
  }
  
  
  /**
   * Stop this locator.
   */
  @Override
  public void stop() {
    stop(false, true);
  }
  
  /**
   * Was this locator stopped during forced-disconnect processing but should
   * reconnect?
   */
  public boolean getStoppedForReconnect() {
    return this.stoppedForReconnect;
  }
  
  /**
   * Stop this locator
   * @param stopForReconnect - stopping for distributed system reconnect
   * @param waitForDisconnect - wait up to 60 seconds for the locator to completely stop
   */
  public void stop(boolean stopForReconnect, boolean waitForDisconnect) {
    if (this.server.isShuttingDown()) {
      // fix for bug 46156
      // If we are already shutting down don't do all of this again.
      // But, give the server a bit of time to shut down so a new
      // locator can be created, if desired, when this method returns 
      if (!stopForReconnect && waitForDisconnect) {
        long endOfWait = System.currentTimeMillis() + 60000;
        boolean log = false;
        if (this.server.isAlive() && this.logger.fineEnabled()) {
          this.logger.fine("sleeping to wait for the locator server to shut down...");
          log = true;
        }
        while (this.server.isAlive() && System.currentTimeMillis() < endOfWait) {
          try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        if (log) {
          if (this.server.isAlive()) {
            this.logger.fine("60 seconds have elapsed waiting for the locator server to shut down - terminating wait and returning");
          } else {
            this.logger.fine("the locator server has shut down");
          }
        }
      }
      return;
    }
    this.stoppedForReconnect = stopForReconnect;
    
    if (this.server.isAlive()) {
      this.logger.info(LocalizedStrings.InternalLocator_STOPPING__0, this);
      try {
        stopLocator(this.port, this.bindAddress);
      } catch ( ConnectException ignore ) {
        // must not be running
      }
      boolean interrupted = Thread.interrupted();
      try {
        this.server.join(60 * 1000);
  
      } catch (InterruptedException ex) {
        interrupted = true;
        this.logger.warning(LocalizedStrings.InternalLocator_INTERRUPTED_WHILE_STOPPING__0, this, ex);
        
        // Continue running -- doing our best to stop everything...
      }
      finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
  
      if (this.server.isAlive()) {
        this.logger.severe(LocalizedStrings.InternalLocator_COULD_NOT_STOP__0__IN_60_SECONDS, this);
      }
    }

    removeLocator(this);

    handleShutdown();

    this.logger.info(LocalizedStrings.InternalLocator_0__IS_STOPPED, this);
    
    if (stoppedForReconnect) {
      launchRestartThread();
    }
  }
  
  public boolean isStopped() {
    return this.server == null  ||  !this.server.isAlive();
  }
  
  private void handleShutdown() {
    if (!this.shutdownHandled.compareAndSet(false, true)) {
      return; // already shutdown
    }
    productUseLog.close();
    if (myDs != null) {
      ((InternalDistributedSystem)myDs).setDependentLocator(null);
    }
    if (this.myCache != null && !this.stoppedForReconnect) {
      this.logger.info(LocalizedStrings.DEBUG, "Closing locator's cache");
      try {
        this.myCache.close();
      } catch (RuntimeException ex) {
        this.logger.info(LocalizedStrings.DEBUG, "Could not close locator's cache because: " + ex);
      }
    }
    
    if(stats != null) {
      stats.close();
    }

    allLocatorsInfo.clear();
    allServerLocatorsInfo.clear();
    if (myDs != null && !this.stoppedForReconnect) {
      if (myDs.isConnected()) {
        this.logger.info( LocalizedStrings.
          InternalLocator_DISCONNECTING_DISTRIBUTED_SYSTEM_FOR_0,
          this);
        myDs.disconnect();
      }
    }
  }

  /**
   * Waits for a locator to be told to stop.
   * 
   * @throws InterruptedException TODO-javadocs
   */
  public void waitToStop() throws InterruptedException {
    boolean restarted;
    do {
      restarted = false;
      this.server.join();
      if (this.stoppedForReconnect) {
        restarted = this.myDs.waitUntilReconnected(-1, TimeUnit.SECONDS);
      }
    } while (restarted);
  }
  
  /** launch a thread that will restart location services */
  private void launchRestartThread() {
    final ManagerLogWriter outlogger = new ManagerLogWriter(LogWriterImpl.INFO_LEVEL, System.out);
    // create a thread group having a last-chance exception-handler
    ThreadGroup group = LogWriterImpl.createThreadGroup("Locator restart thread group", (LogWriterI18n)outlogger);
    this.restartThread = new Thread(group, "Location services restart thread") {
      public void run() {
        boolean restarted;
        try {
          restarted = attemptReconnect();
          outlogger.info("attemptReconnect returned " + restarted);
        } catch (InterruptedException e) {
          outlogger.info("attempt to restart location services was interrupted", e);
        } catch (IOException e) {
          outlogger.info("attempt to restart location services terminated", e);
        }
        InternalLocator.this.restartThread = null;
      }
    };
    this.restartThread.setDaemon(true);
    this.restartThread.start();
  }
  /**
   * reconnects the locator to a restarting DistributedSystem.  If quorum checks
   * are enabled this will start peer location services before a distributed
   * system is available if the quorum check succeeds.  It will then wait
   * for the system to finish reconnecting before returning.  If quorum checks
   * are not being done this merely waits for the distributed system to reconnect
   * and then starts location services.
   * @return true if able to reconnect the locator to the new distributed system
   */
  public boolean attemptReconnect() throws InterruptedException, IOException {
    boolean restarted = false;
    LogWriter log = new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out);
    if (this.stoppedForReconnect) {
      log.info("attempting to restart locator");
      boolean tcpServerStarted = false;
      final InternalDistributedSystem ds = this.myDs;
      if (ds == null || ds.getConfig() == null) {
        return false;
      }
      long waitTime = ds.getConfig().getMaxWaitTimeForReconnect()/2;
      QuorumChecker checker = null;
      while (ds.getReconnectedSystem() == null &&
          !ds.isReconnectCancelled()) {
        if (checker == null) {
          checker = this.myDs.getQuorumChecker();
          if (checker != null) {
            log.info("The distributed system returned this quorum checker: " + checker);
          }
        }
        if (checker != null && !tcpServerStarted) {
          boolean start = checker.checkForQuorum(3*this.myDs.getConfig().getMemberTimeout(), log);
          if (start) {
            // start up peer location.  server location is started after the DS finishes
            // reconnecting
            log.info("starting peer location");
            this.allLocatorsInfo.clear();
            this.allServerLocatorsInfo.clear();
            this.stoppedForReconnect = false;
            this.myDs = null;
            this.myCache = null;
            restartWithoutDS(log);
            tcpServerStarted = true;
            setLocator(this);
          }
        }
        ds.waitUntilReconnected(waitTime, TimeUnit.MILLISECONDS);
      }
      InternalDistributedSystem newSystem = (InternalDistributedSystem)ds.getReconnectedSystem();
//      LogWriter log = new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out);
      if (newSystem != null) {
//        log.fine("reconnecting locator: starting location services");
        if (!tcpServerStarted) {
          this.allLocatorsInfo.clear();
          this.allServerLocatorsInfo.clear();
          this.stoppedForReconnect = false;
        }
        restartWithDS(newSystem, GemFireCacheImpl.getInstance(), newSystem.getLogWriter());
        setLocator(this);
        restarted = true;
      }
    }
    return restarted;
  }
  
  
  private void restartWithoutDS(LogWriter log) throws IOException {
    synchronized (locatorLock) {
      if (locator != this && hasLocator()) {
        throw new IllegalStateException("A locator can not be created because one already exists in this JVM.");
      }
      this.myDs = null;
      this.myCache = null;
      log.info("Locator restart: initializing TcpServer peer location services");
      this.server.restarting(null, null);
      if (this.productUseLog.isClosed()) {
        this.productUseLog.reopen();
      }
      if (!this.server.isAlive()) {
        log.info("Locator restart: starting TcpServer");
        startTcpServer();
      }
    }
  }
  
  private void restartWithDS(InternalDistributedSystem newSystem, GemFireCacheImpl newCache, LogWriter log) throws IOException {
    synchronized (locatorLock) {
      if (locator != this && hasLocator()) {
        throw new IllegalStateException("A locator can not be created because one already exists in this JVM.");
      }
      this.myDs = newSystem;
      this.myCache = newCache;
      this.logger = (LogWriterI18n)log;
      ((InternalDistributedSystem)myDs).setDependentLocator(this);
      log.info("Locator restart: initializing TcpServer");
      this.server.restarting(newSystem, newCache);
      if (this.productUseLog.isClosed()) {
        this.productUseLog.reopen();
      }
      this.productUseLog.monitorUse(newSystem);
      if (!this.server.isAlive()) {
        log.info("Locator restart: starting TcpServer");
        startTcpServer();
      }
      log.info("Locator restart: initializing JMX manager");
      startJmxManagerLocationService(newCache);
      endStartLocator((InternalDistributedSystem)myDs);
      myDs.getLogWriter().info("Locator restart completed");
    }
  }
  
  
  // implementation of abstract method in Locator
  @Override
  public DistributedSystem getDistributedSystem() {
    return myDs;
  }
  
  @Override
  public boolean isPeerLocator() {
    return peerLocator;
  }
  
  @Override
  public boolean isServerLocator() {
    return this.serverLocator != null;
  }

  /**
   * Returns null if no server locator;
   * otherwise returns the advisee that represents the server locator.
   */
  public ServerLocator getServerLocatorAdvisee() {
    return this.serverLocator;
  }

  private static class PrimaryHandler implements TcpHandler {
    private volatile HashMap<Class, TcpHandler> handlerMapping = new HashMap<Class, TcpHandler>();
    private volatile HashSet<TcpHandler> allHandlers = new HashSet<TcpHandler>();
    private TcpServer tcpServer;
    private LogWriterI18n logger;
    private final LocatorMembershipListenerImpl locatorListener;
    private final List<LocatorJoinMessage> locatorJoinMessages;
    private final Object locatorJoinObject = new Object();
    InternalLocator interalLocator;
    boolean willHaveServerLocator;  // flag to avoid warning about missing handlers during startup

    public PrimaryHandler(int port, LogWriterI18n logger,
        InternalLocator locator, LocatorMembershipListenerImpl listener) {
      this.logger = logger;
      this.locatorListener = listener;
      interalLocator = locator;
      this.locatorJoinMessages = new ArrayList<LocatorJoinMessage>();
    }

    // this method is synchronized to make sure that no new handlers are added while
    //initialization is taking place.
    public synchronized void init(TcpServer tcpServer) {
      this.tcpServer = tcpServer;
      for(Iterator itr = allHandlers.iterator(); itr.hasNext();) {
        TcpHandler handler = (TcpHandler) itr.next();
        handler.init(tcpServer);
      }
    }

    public void restarting(DistributedSystem ds, GemFireCache cache) {
      if (ds != null) {
        this.logger = ds.getLogWriter().convertToLogWriterI18n();
        for (TcpHandler handler: this.allHandlers) {
          handler.restarting(ds, cache);
        }
      }
    }

    public Object processRequest(Object request) throws IOException {
      TcpHandler handler = (TcpHandler)handlerMapping.get(request.getClass());
      if (handler != null) {
        Object result;
        result = handler.processRequest(request);
        return result;
      }
      else {
        Object response;
        if(request instanceof RemoteLocatorJoinRequest) {
          response = updateAllLocatorInfo((RemoteLocatorJoinRequest) request);
        }
        else if(request instanceof LocatorJoinMessage) {
          response = informAboutRemoteLocators((LocatorJoinMessage) request);
        }
        else if(request instanceof RemoteLocatorPingRequest) {
          response = getPingResponse((RemoteLocatorPingRequest) request);
        }
        else if(request instanceof RemoteLocatorRequest) {
          response = getRemoteLocators((RemoteLocatorRequest) request);
        }
        else {
          if ( ! (willHaveServerLocator && (request instanceof ServerLocationRequest)) ) {
            if(logger.warningEnabled()) {
              logger.warning(
                  LocalizedStrings.InternalLocator_EXPECTED_ONE_OF_THESE_0_BUT_RECEIVED_1,
                  new Object[] {handlerMapping.keySet(), request});
            }
          }
        return null;
        }
        return response;
      }
    }

    /**
     * A locator from the request is checked against the existing remote locator
     * metadata. If it is not available then added to existing remote locator
     * metadata and LocatorMembershipListener is invoked to inform about the
     * this newly added locator to all other locators available in remote locator
     * metadata. As a response, remote locator metadata is sent.
     * 
     * @param request
     */
    private synchronized Object updateAllLocatorInfo(RemoteLocatorJoinRequest request) {
      int distributedSystemId = request.getDistributedSystemId();
      DistributionLocatorId locator = request.getLocator();

      LocatorHelper.addLocator(distributedSystemId, locator, interalLocator, locatorListener, null);
      return new RemoteLocatorJoinResponse(interalLocator.getAllLocatorsInfo());
    }

    private Object getPingResponse(RemoteLocatorPingRequest request) {
     return new RemoteLocatorPingResponse();
    }

    private Object informAboutRemoteLocators(LocatorJoinMessage request){
      synchronized (locatorJoinObject) {
        if (locatorJoinMessages.contains(request)) {
          return null;
        }
        locatorJoinMessages.add(request);  
      }
      int distributedSystemId = request.getDistributedSystemId();
      DistributionLocatorId locator = request.getLocator();
      DistributionLocatorId sourceLocatorId = request.getSourceLocator();

      LocatorHelper.addLocator(distributedSystemId, locator, interalLocator, locatorListener, sourceLocatorId);
      return null;
    }

    private Object getRemoteLocators(RemoteLocatorRequest request) {
      int dsId = request.getDsId();
      Set<String> locators = interalLocator.getRemoteLocatorInfo(dsId);
      return new RemoteLocatorResponse(locators);
    }

    private JmxManagerLocatorResponse findJmxManager(JmxManagerLocatorRequest request) {
      JmxManagerLocatorResponse result = null;
      // NYI
      return result;
    }

    public void shutDown() {
      try {
      for(Iterator itr = allHandlers.iterator(); itr.hasNext(); ) {
        TcpHandler handler = (TcpHandler) itr.next();
        handler.shutDown();
      }
      } finally {
        this.interalLocator.handleShutdown();
      }
    }
    
    public synchronized boolean isHandled(Class clazz) {
      return this.handlerMapping.containsKey(clazz);
    }
    
    public synchronized void addHandler(Class clazz, TcpHandler handler) {
      HashMap tmpHandlerMapping = new HashMap(handlerMapping);
      HashSet tmpAllHandlers = new HashSet(allHandlers);
      tmpHandlerMapping.put(clazz, handler);
      if(tmpAllHandlers.add(handler) && tcpServer != null ) {
        handler.init(tcpServer);
      }
      handlerMapping = tmpHandlerMapping;
      allHandlers = tmpAllHandlers;
    }
    
    public void endRequest(Object request,long startTime) {
      TcpHandler handler = (TcpHandler) handlerMapping.get(request.getClass());
      if(handler != null) {
        handler.endRequest(request, startTime);
      }
    }
    
    public void endResponse(Object request,long startTime) {
      TcpHandler handler = (TcpHandler) handlerMapping.get(request.getClass());
      if(handler != null) {
        handler.endResponse(request, startTime);
      }
    }
    
  }
  
  
  
  public void onConnect(InternalDistributedSystem sys) {
    try {
      stats.hookupStats(sys,  SocketCreator.getLocalHost().getCanonicalHostName() + "-" + server.getBindAddress().toString());
    } catch(UnknownHostException uhe) {
      uhe.printStackTrace();
    }
  }

  
  /**
   * Returns collection of locator strings representing every locator instance
   * hosted by this member.
   * 
   * @see #getLocators()
   */
  public static Collection<String> getLocatorStrings() {
    Collection<String> locatorStrings = null;
    try {
      Collection<DistributionLocatorId> locatorIds = 
          DistributionLocatorId.asDistributionLocatorIds(getLocators());
      locatorStrings = DistributionLocatorId.asStrings(locatorIds);
    } catch (UnknownHostException e) {
      locatorStrings = null;
    }
    if (locatorStrings == null || locatorStrings.isEmpty()) {
      return null;
    } else {
      return locatorStrings;
    }
  }
  
  /**
   * A helper object so that the TcpServer can record
   * its stats to the proper place. Stats are only recorded
   * if a distributed system is started.
   * 
   */
  protected class DelayedPoolStatHelper implements PoolStatHelper {
    
    public void startJob() {
      stats.incRequestInProgress(1);
      
    }
    public void endJob() {
      stats.incRequestInProgress(-1);
    }
    
  }

  @Override
  public Set<String> getRemoteLocatorInfo(int dsId) {
    return this.allServerLocatorsInfo.get(dsId);
  }

  public ConcurrentMap<Integer,Set<DistributionLocatorId>> getAllLocatorsInfo() {
    return this.allLocatorsInfo;
  }
  
  @Override
  public ConcurrentMap<Integer,Set<String>> getAllServerLocatorsInfo() {
    return this.allServerLocatorsInfo;
  }

  public void startJmxManagerLocationService(GemFireCacheImpl gfc) {
    if (gfc.getJmxManagerAdvisor() != null) {
      if (!this.handler.isHandled(JmxManagerLocatorRequest.class)) {
        this.handler.addHandler(JmxManagerLocatorRequest.class, new JmxManagerLocator(gfc));
      }
    }
  }
}
