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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.*;
import com.gemstone.gemfire.admin.AlertLevel;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.execute.internal.FunctionServiceManager;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.locks.GrantorRequestProcessor;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.MembershipManager;
import com.gemstone.gemfire.distributed.internal.membership.QuorumChecker;
import com.gemstone.gemfire.distributed.internal.membership.jgroup.JGroupMembershipManager;
import com.gemstone.gemfire.distributed.internal.membership.jgroup.LocatorImpl;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.*;
import com.gemstone.gemfire.internal.admin.remote.DistributionLocatorId;
import com.gemstone.gemfire.internal.cache.CacheConfig;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.VMIdAdvisor;
import com.gemstone.gemfire.internal.cache.execute.FunctionServiceStats;
import com.gemstone.gemfire.internal.cache.execute.FunctionStats;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.tier.InternalBridgeMembership;
import com.gemstone.gemfire.internal.cache.tier.sockets.HandShake;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.MemoryAllocator;
import com.gemstone.gemfire.internal.offheap.OffHeapStorage;
import com.gemstone.gemfire.internal.process.ProcessLauncherContext;
import com.gemstone.gemfire.internal.security.SecurityLocalLogWriter;
import com.gemstone.gemfire.internal.security.SecurityManagerLogWriter;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.tcp.ConnectionTable;
import com.gemstone.gemfire.internal.util.LogFileUtils;
import com.gemstone.gemfire.internal.util.LogService;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCondition;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantLock;
import com.gemstone.gemfire.management.ManagementException;
import com.gemstone.gemfire.security.GemFireSecurityException;

/**
 * The concrete implementation of {@link DistributedSystem} that
 * provides internal-only functionality.
 *
 * @author David Whitlock
 * @since 3.0
 *
 */
public final class InternalDistributedSystem
    extends DistributedSystem
    implements OsStatisticsFactory, StatisticsManager {
  public static final String DISABLE_MANAGEMENT_PROPERTY = "gemfire.disableManagement";
  public static final String ENABLE_SLF4J_LOG_BRIDGE = "gemfire.enable.slf4j.log.bridge";

  public static final String ENABLE_CREATION_STACK_PROPERTY = "gemfire.enableCreationStack";
  public static final boolean ENABLE_CREATION_STACK = Boolean.getBoolean(ENABLE_CREATION_STACK_PROPERTY);

  /**
   * If auto-reconnect is going on this will hold a reference to it
   */
  public static volatile DistributedSystem systemAttemptingReconnect;

  
  /** The distribution manager that is used to communicate with the
   * distributed system. */
  protected DM dm;

  private final GrantorRequestProcessor.GrantorRequestContext grc;
  public GrantorRequestProcessor.GrantorRequestContext getGrantorRequestContext() {
    return grc;
  }

  /** Numeric id that identifies this node in a DistributedSystem */
  private long id;

  /** The log writer used to log information messages */
  protected LogWriter logger = null;

  /** The log writer used to log security related messages */
  protected LogWriter securityLogger = null;

//  /** The log writer was provided by an external entity */
//  private boolean externalLogWriterProvided = false;
//
  /** Set to non-null if this ds opens a local log file */
  private FileOutputStream localLogFileStream = null;

  /** Set to non-null if this ds opens a local security log file */
  private FileOutputStream localSecurityLogFileStream = null;

  /** Time this system was created */
  private final long startTime;

  /**
   * Guards access to {@link #isConnected}
   */
  protected final Object isConnectedMutex = new Object();

  /** Is this <code>DistributedSystem</code> connected to a
   * distributed system?
   *
   * @guarded.By {@link #isConnectedMutex} for writes
   */
  protected volatile boolean isConnected;

  /**
   * Set to true if this distributed system is a singleton; it will
   * always be the only member of the system.
   */
  private boolean isLoner = false;

  /** The sampler for this DistributedSystem.
   */
  private GemFireStatSampler sampler = null;

  /** A set of listeners that are invoked when this connection to the
   * distributed system is disconnected */
  private final Set listeners = new LinkedHashSet(); // needs to be ordered

  /** Set of listeners that are invoked whenever a connection is created to
   * the distributed system */
  private static final Set connectListeners = new LinkedHashSet(); // needs to be ordered

  /** auto-reconnect listeners */
  private static final List<ReconnectListener> reconnectListeners =
      new ArrayList<ReconnectListener>();

  /** gemfirexd disconnect listener */
  private DisconnectListener gfxdDisconnectListener;
  /**
   * whether this DS is one created to reconnect to the distributed 
   * system after a forced-disconnect.  This state is cleared once reconnect
   * is successful.
   */
  private boolean isReconnectingDS;
  
  /**
   * During a reconnect attempt this is used to perform quorum checks
   * before allowing a location service to be started up in this JVM.
   * If quorum checks fail then we delay starting location services until
   * a live locator can be contacted.
   */
  private QuorumChecker quorumChecker;
  

  /**
   * A Constant that maches the ThreadGroup name of ths shutdown hook.
   * This constant is used to insure consistency with {@link LogWriterImpl}.
   * Due to Bug 38407, be careful about moving this to another class.
   */
  public final static String SHUTDOWN_HOOK_NAME = "Distributed system shutdown hook";
  /**
   * A property to prevent shutdown hooks from being registered with the VM.
   * This is regarding bug 38407
   */
  public final static String DISABLE_SHUTDOWN_HOOK_PROPERTY = "gemfire.disableShutdownHook";

  /**
   * A property to append to existing log-file instead of truncating it.
   */
  public final static String APPEND_TO_LOG_FILE = "gemfire.append-log";

  ////////////////////  Configuration Fields  ////////////////////

  /** The config object used to create this distributed system */
  private final DistributionConfig originalConfig;

  /** The config object to which most configuration work is delegated */
  private DistributionConfig config;

  private final boolean statsDisabled = Boolean.getBoolean("gemfire.statsDisabled");

  private volatile boolean shareSockets = DistributionConfig.DEFAULT_CONSERVE_SOCKETS;

  /** if this distributed system starts a locator, it is stored here */
  private InternalLocator startedLocator;

  private List<ResourceEventsListener> resourceListeners;

  private final boolean disableManagement = Boolean.getBoolean(DISABLE_MANAGEMENT_PROPERTY);

  /**
   * VM ID advisor for this VM that can assign a DS-wide unique integer ID to
   * this VM when queried.
   */
  private VMIdAdvisor vmIdAdvisor;

  /**
   * Stack trace showing the creation of this instance of InternalDistributedSystem.
   */
  private final Throwable creationStack;

  private static boolean isHadoopGfxdLonerMode = false;

  /////////////////////  Static Methods  /////////////////////

  /**
   * Creates a new instance of <code>InternalDistributedSystem</code>
   * with the given configuration.
   */
  public static InternalDistributedSystem newInstance(Properties config) {
    boolean success = false;
    InternalDataSerializer.checkSerializationVersion();
    try {
      SystemFailure.startThreads();
    InternalDistributedSystem newSystem = new InternalDistributedSystem(config);
    newSystem.initialize();
    reconnectAttemptCounter = 0; // reset reconnect count since we just got a new connection
    notifyConnectListeners(newSystem);
    success = true;
    return newSystem;
    } finally {
      if (!success) {
        LogWriterImpl.cleanUpThreadGroups(); // bug44365 - logwriters accumulate, causing mem leak
        SystemFailure.stopThreads();
      }
    }
  }

  /**
   * Returns a connection to the distributed system that is suitable
   * for administration.  For administration, we are not as strict
   * when it comes to existing connections.
   *
   * @since 4.0
   */
  public static DistributedSystem connectForAdmin(Properties props,
                                                  LogWriterI18n logger) {
    return DistributedSystem.connectForAdmin(props, logger);
  }

  /**
   * Returns a connected distributed system for this VM, or null
   * if there is no connected distributed system in this VM.
   * This method synchronizes on the existingSystems collection.
   *
   * <p>author bruce
   * @since 5.0
   */
  public static InternalDistributedSystem getConnectedInstance() {
    InternalDistributedSystem result = null;
    synchronized (existingSystemsLock) {
      if (!existingSystems.isEmpty()) {
        InternalDistributedSystem existingSystem =
          (InternalDistributedSystem) existingSystems.get(0);
        if (existingSystem.isConnected())
          result = existingSystem;
      }
    }
    return result;
  }

  /**
   * Returns the current distributed system, if there is one.
   * Note: this method is no longer unsafe size existingSystems uses copy-on-write.
   * <p>author bruce
   * @since 5.0
   */
  public static InternalDistributedSystem unsafeGetConnectedInstance() {
    InternalDistributedSystem result = getAnyInstance();
    if (result != null) {
      if (!result.isConnected()) {
        result = null;
      }
    }
    return result;
  }

  public final VMIdAdvisor getVMIdAdvisor() {
    return this.vmIdAdvisor;
  }

  /**
   * Get an identifier efficiently with no messaging for each call that is
   * globally unique in the distributed system.
   *
   * @param throwOnOverflow
   *          if true then throw {@link IllegalStateException} when the UUIDs
   *          have been exhausted in the distributed system; note that it is not
   *          necessary that all possible long values would have been used by
   *          someone (e.g. a VM goes down without using its "block" of IDs)
   */
  public final long newUUID(final boolean throwOnOverflow)
      throws IllegalStateException {
    return this.vmIdAdvisor.newUUID(throwOnOverflow);
  }

  /**
   * Get a new integer identifier efficiently with no messaging for each call
   * that maybe globally unique in the distributed system. This returns an
   * integer so with large number of servers that are going down and coming up,
   * there is no guarantee of uniqueness. If the VM's unique ID overflows and it
   * is no longer possible to guarantee a unique DS-wide integer, then an
   * {@link IllegalStateException} is thrown.
   * <p>
   * It is recommended to always use {@link #newUUID} -- use this only if you
   * have to.
   *
   * @throws IllegalStateException
   *           thrown when UUIDs have been exhaused in the distributed system;
   *           note that it is not necessary that all possible integer values
   *           would have been used by someone (e.g. a VM goes down without
   *           using its "block" of IDs)
   */
  public final int newShortUUID() throws IllegalStateException {
    return this.vmIdAdvisor.newShortUUID();
  }

  /**
   * @return distribution stats, or null if there is no distributed system
   *         available
   */
  public static DMStats getDMStats() {
    InternalDistributedSystem sys = getAnyInstance();
    if (sys != null && sys.dm != null) {
      return sys.dm.getStats();
    }
    return null;
  }

  /**
   * @return a log writer, or null if there is no distributed system available
   */
  public static LogWriterI18n getLoggerI18n() {
    InternalDistributedSystem sys = getAnyInstance();
    if (sys != null && sys.logger != null) {
      return sys.logger.convertToLogWriterI18n();
    }
    return null;
  }



  //////////////////////  Constructors  //////////////////////

  /**
   * Creates a new <code>InternalDistributedSystem</code> with the
   * given configuration properties.  Does all of the magic of finding
   * the "default" values of properties.  See {@link
   * DistributedSystem#connect} for a list of exceptions that may be
   * thrown.
   *
   * @param nonDefault
   *        The non-default configuration properties specified by the
   *        caller
   *
   * @see DistributedSystem#connect
   */
  private InternalDistributedSystem(Properties nonDefault) {

    // register DSFID types first; invoked explicitly so that all message type
    // initializations do not happen in first deserialization on a possibly
    // "precious" thread
    if (!isHadoopGfxdLonerMode) {
      DSFIDFactory.registerTypes();
    }

    Object o = nonDefault.remove(DistributionConfig.SECURITY_LOG_WRITER_NAME);
    if (o instanceof LogWriterImpl) {
      this.securityLogger = (LogWriterImpl)o;
    }
    o = nonDefault.remove(DistributionConfig.LOG_WRITER_NAME);
    if (o instanceof LogWriterImpl) {
      // Who knows what kind of logger we might get?  Hydra logger?
      // So, be conservative.
      this.logger = (LogWriterImpl) o;
      if (this.securityLogger == null) {
        int securityLogLevel = DistributionConfig.DEFAULT_LOG_LEVEL;
        String sLogLevelStr = nonDefault
            .getProperty(DistributionConfig.SECURITY_LOG_LEVEL_NAME);
        if (sLogLevelStr != null && sLogLevelStr.length() > 0) {
          securityLogLevel = LogWriterImpl.levelNameToCode(sLogLevelStr);
        }
        this.securityLogger = new SecurityLogWriter(securityLogLevel,
            this.logger);
      }
//      this.externalLogWriterProvided = true;
    }
    o = nonDefault.remove(DistributionConfig.SECURITY_LOG_OUTPUTSTREAM_NAME);
    if (o instanceof FileOutputStream) {
      this.localSecurityLogFileStream = (FileOutputStream)o;
    }
    o = nonDefault.remove(DistributionConfig.LOG_OUTPUTSTREAM_NAME);
    if (o instanceof FileOutputStream) {
      this.localLogFileStream = (FileOutputStream)o;
    }

    o = nonDefault.remove(DistributionConfig.DS_RECONNECTING_NAME);
    if (o instanceof Boolean) {
      this.isReconnectingDS = ((Boolean)o).booleanValue();
    } else {
      this.isReconnectingDS = false;
    }
    
    o = nonDefault.remove(DistributionConfig.DS_QUORUM_CHECKER_NAME);
    if (o instanceof QuorumChecker) {
      this.quorumChecker = (QuorumChecker)o;
    }
    o = nonDefault.remove(DistributionConfig.DS_CONFIG_NAME);
    if (o instanceof DistributionConfigImpl) {
      this.originalConfig = (DistributionConfigImpl) o;
    } else {
      this.originalConfig = new DistributionConfigImpl(nonDefault);
    }
    
    
    ((DistributionConfigImpl)this.originalConfig).checkForDisallowedDefaults(); // throws IllegalStateEx
    this.shareSockets = this.originalConfig.getConserveSockets();
    this.startTime = System.currentTimeMillis();
    this.grc = new GrantorRequestProcessor.GrantorRequestContext(stopper);

    elderLock = new StoppableReentrantLock(stopper);
    elderLockCondition = elderLock.newCondition();
    
    this.creationStack = generateCreationStack();

//    if (DistributionConfigImpl.multicastTest) {
//      this.logger.warning("Use of multicast has been forced");
//    }
//    if (DistributionConfigImpl.forceDisableTcp) {
//      this.logger.warning("Use of UDP has been forced");
//    }
//    if (com.gemstone.gemfire.distributed.internal.membership.jgroup.JGroupMembershipManager.multicastTest) {
//      this.logger.warning("Use of multicast for all distributed cache operations has been forced");
//    }

  }

  ////////////////////  Instance Methods  ////////////////////

  /**
   * Registers a listener to the system
   *
   * @param listener
   *          listener to be added
   */
  public void addResourceListener(ResourceEventsListener listener) {
    resourceListeners.add(listener);
  }

  /**
   * Un-Registers a listener to the system
   *
   * @param listener
   *          listener to be removed
   */
  public void removeResourceListener(ResourceEventsListener listener) {
    resourceListeners.remove(listener);
  }

  /**
   * Handles a particular event associated with a resource
   *
   * @param event
   *          Resource event
   * @param resource
   *          resource on which event is generated
   */
  public void handleResourceEvent(ResourceEvent event, Object resource) {
    if (this.disableManagement) {
      return;
    }
    if (resourceListeners.size() == 0) {
      return;
    }
    notifyResourceEventListeners(event, resource);
  }

  public boolean isManagementDisabled() {
    return this.disableManagement;
  }

  /** Returns true if system is a loner (for testing) */
  public boolean isLoner() {
    return this.isLoner;
  }

  private MemoryAllocator offHeapStore = null;
  
  public MemoryAllocator getOffHeapStore() {
    return this.offHeapStore;
  }
  
  /**
   * Initializes this connection to a distributed system with the
   * current configuration state.
   */
  private void initialize() {
    if (this.originalConfig.getMcastPort() == 0 && this.originalConfig.getLocators().equals("")) {
      // no distribution
      this.isLoner = true;
//       throw new IllegalArgumentException("The "
//                                          + DistributionConfig.LOCATORS_NAME
//                                          + " attribute can not be empty when the "
//                                          + DistributionConfig.MCAST_PORT_NAME
//                                          + " attribute is zero.");
    }

    if (this.isLoner) {
      this.config = this.originalConfig;
    } else {
      this.config = new RuntimeDistributionConfigImpl(this);
      this.attemptingToReconnect = (reconnectAttemptCounter > 0);
    }
    try {
    SocketCreator.getDefaultInstance(this.config);

    if (this.logger == null) {
      FileOutputStream[] fos = new FileOutputStream[1];
      this.logger = createLogWriter(Boolean.getBoolean(APPEND_TO_LOG_FILE),
          this.isLoner, false, config, true, fos);
      this.localLogFileStream = fos[0];
      this.logger.fine("LogWriter is created.");

      File securityLogFile = config.getSecurityLogFile();
      if (securityLogFile == null || securityLogFile.equals(new File(""))) {
        this.securityLogger = new SecurityLogWriter(config
            .getSecurityLogLevel(), this.logger);
      }
      else {
        this.securityLogger = createLogWriter(false, this.isLoner, true,
            config, false, fos);
      }
      this.localSecurityLogFileStream = fos[0];
      this.securityLogger.fine("SecurityLogWriter is created.");
    }

    // initialize the JGroups loggers
    com.gemstone.org.jgroups.util.GemFireTracer.getLog(
        InternalDistributedSystem.class).setLogWriter(this.logger);
    com.gemstone.org.jgroups.util.GemFireTracer.getLog(
        InternalDistributedSystem.class).setSecurityLogWriter(this.securityLogger);

      // initialize the ClientSharedUtils logger
    if (this.logger instanceof LogWriterImpl) {
      final LogWriterImpl logImpl = (LogWriterImpl)this.logger;
      ClientSharedUtils.initLogger("com.gemstone.gemfire", null, false, false,
          GemFireLevel.create(logImpl.getLevel()), logImpl.getHandler());
    }
    if (this.attemptingToReconnect) {
      this.logger.fine("This thread is initializing a new DistributedSystem in order to reconnect to other members");
    }
    // Note we need loners to load the license in case they are a
    // bridge server and will need to enforce the member limit
    if (Boolean.getBoolean(InternalLocator.FORCE_LOCATOR_DM_TYPE)) {
      this.locatorDMTypeForced = true;
    }

    // Initialize the Diffie-Hellman and public/private keys
    try {
      HandShake.initCertsMap(this.config.getSecurityProps());
      HandShake.initPrivateKey(this.config.getSecurityProps());
      HandShake.initDHKeys(this.config);
    }
    catch (Exception ex) {
      throw new GemFireSecurityException(
        LocalizedStrings.InternalDistributedSystem_PROBLEM_IN_INITIALIZING_KEYS_FOR_CLIENT_AUTHENTICATION.toLocalizedString(), ex);
    }

    final long offHeapMemorySize = OffHeapStorage.parseOffHeapMemorySize(getConfig().getOffHeapMemorySize());

    this.offHeapStore = OffHeapStorage.createOffHeapStorage(getLogWriter(), this, offHeapMemorySize, this);
    
    // Note: this can only happen on a linux system
    if (getConfig().getLockMemory()) {
      // This calculation is not exact, but seems fairly close.  So far we have
      // not loaded much into the heap and the current RSS usage is already 
      // included the available memory calculation.
      long avail = LinuxProcFsStatistics.getAvailableMemory(logger);
      long size = offHeapMemorySize + Runtime.getRuntime().totalMemory();
      if (avail < size) {
        if (GemFireCacheImpl.ALLOW_MEMORY_LOCK_WHEN_OVERCOMMITTED) {
          logger.warning(LocalizedStrings.InternalDistributedSystem_MEMORY_OVERCOMMIT_WARN.toLocalizedString(size - avail));
        } else {
          throw new IllegalStateException(LocalizedStrings.InternalDistributedSystem_MEMORY_OVERCOMMIT.toLocalizedString(avail, size));
        }
      }
      
      this.logger.info("Locking memory. This may take a while...");
      GemFireCacheImpl.lockMemory();
      this.logger.info("Finished locking memory.");
    }

    try {
      startInitLocator();
    } catch (InterruptedException e) {
      throw new SystemConnectException("Startup has been interrupted", e);
    }


    synchronized (this.isConnectedMutex) {
      this.isConnected = true;
    }

    if (!this.isLoner) {
      try {
        if (this.quorumChecker != null) {
          this.quorumChecker.suspend();
        }
        this.dm = DistributionManager.create(this, this.logger.convertToLogWriterI18n(),
            this.securityLogger.convertToLogWriterI18n());
        // fix bug #46324
        if (InternalLocator.hasLocator()) {
          getDistributionManager().addHostedLocators(getDistributedMember(), InternalLocator.getLocatorStrings());
        }
      }
      finally {
        if (this.dm == null && this.quorumChecker != null) {
          this.quorumChecker.resume();
        }
        setDisconnected();
      }
    }
    else {
      this.dm = new LonerDistributionManager(this, this.logger.convertToLogWriterI18n());
    }

    Assert.assertTrue(this.dm.getSystem() == this);

    this.id = this.dm.getChannelId();

    if (!this.isLoner) {
      this.dm.restartCommunications();
    }
    synchronized (this.isConnectedMutex) {
      this.isConnected = true;
    }
    if (attemptingToReconnect  &&  (this.startedLocator == null)) {
      try {
        startInitLocator();
      } catch (InterruptedException e) {
        throw new SystemConnectException("Startup has been interrupted", e);
      }
    }
    try {
      endInitLocator();
    }
    catch (IOException e) {
      throw new GemFireIOException("Problem finishing a locator service start", e);
    }

      if (!statsDisabled) {
        // to fix bug 42527 we need a sampler
        // even if sampling is not enabled.
      this.sampler = new GemFireStatSampler(this);
      this.sampler.start();
    }

    if (this.logger instanceof ManagerLogWriter) {
      ((ManagerLogWriter)this.logger).startupComplete();
    }

    // create the VMIdAdvisor
    this.vmIdAdvisor = VMIdAdvisor.createVMIdAdvisor(this);

    // set any trace flags
    if (this.logger.finerEnabled()) {
      ExclusiveSharedSynchronizer.TRACE_LOCK = true;
    }

    File securityLogFile = config.getSecurityLogFile();
    if (this.securityLogger instanceof ManagerLogWriter &&
      securityLogFile != null && !securityLogFile.equals(new File(""))) {
      ((ManagerLogWriter)this.securityLogger).startupComplete();
    }
    //this.logger.info("ds created", new RuntimeException("DEBUG: STACK"));

    Assert.assertTrue(this.dm != null);
    }
    catch (RuntimeException ex) {
      this.config.close();
      throw ex;
    }

    resourceListeners = new CopyOnWriteArrayList<ResourceEventsListener>();
    this.reconnected = this.attemptingToReconnect;
    this.attemptingToReconnect = false;
  }

  /**
   * @since 5.7
   */
  private void startInitLocator() throws InterruptedException {
    String locatorString = this.originalConfig.getStartLocator();
    if (locatorString.length() > 0) {
      // when reconnecting we don't want to join with a colocated locator unless
      // there is a quorum of the old members available
      if (attemptingToReconnect && !this.isConnected) {
        if (this.quorumChecker != null) {
          if (logger.infoEnabled()) {
            logger.info("performing a quorum check to see if location services can be started early");
          }
          if (!quorumChecker.checkForQuorum(3*this.config.getMemberTimeout(), logger)) {
            if (this.logger.infoEnabled()) {
              this.logger.info("quorum check failed - not allowing location services to start early");
            }
            return;
          }
          if (this.logger.infoEnabled()) {
            this.logger.info("quorum check passed - allowing location services to start early");
          }
        }
      }
      DistributionLocatorId locId = new DistributionLocatorId(locatorString);
      try {
        this.startedLocator = InternalLocator.createLocator(
            locId.getPort(),
            null,
            null,
            this.logger.convertToLogWriterI18n(),
            this.securityLogger.convertToLogWriterI18n(),
            locId.getHost(),
            locId.getHostnameForClients(),
            this.originalConfig.toProperties(), false);
        if(locId.isPeerLocator()) {
          boolean startedPeerLocation = false;
          try {
            this.startedLocator.startPeerLocation(true);
            if (this.isConnected) {
              InternalDistributedMember id = this.dm.getDistributionManagerId();
              LocatorImpl gs = this.startedLocator.getLocatorHandler();
              gs.setLocalAddress(id);
            }
            startedPeerLocation = true;
          } finally {
            if (!startedPeerLocation) {
              this.startedLocator.stop();
              this.startedLocator = null;
            }
          }
        }
      } catch (IOException e) {
        throw new GemFireIOException( LocalizedStrings.
          InternalDistributedSystem_PROBLEM_STARTING_A_LOCATOR_SERVICE
            .toLocalizedString(), e);
      }
    }
  }

  /**
   * @since 5.7
   */
  private void endInitLocator() throws IOException {
    if (startedLocator != null) {
      String locatorString = this.originalConfig.getStartLocator();
//      DistributionLocatorId locId = new DistributionLocatorId(locatorString);
      boolean finished = false;
      try {
        // during the period when the product is using only paper licenses we always
        // start server location services in order to be able to log information
        // about the use of cache servers
        //      if(locId.isServerLocator()) {
        this.startedLocator.startServerLocation(this);
        //      }

        this.startedLocator.endStartLocator(this);
        finished = true;
      } finally {
        if (!finished) {
          this.startedLocator.stop();
          this.startedLocator = null;
        }
      }
    }
  }

  /** record a locator as a dependent of this distributed system */
  public void setDependentLocator(InternalLocator theLocator) {
    this.startedLocator = theLocator;
  }

  /**
   * Creates the log writer for a distributed system based on the system's
   * parsed configuration. The initial banner and messages are also entered into
   * the log by this method.
   *
   * @param appendToFile
   *                Whether new messages have to be appended to the log file
   * @param isLoner
   *                Whether the distributed system is a loner or not
   * @param isSecurityLog
   *                Whether a log for security related messages has to be
   *                created
   * @param config
   *                The DistributionConfig for the target distributed system
   * @param logConfig if true log the configuration
   * @param FOSHolder
   *                The FileOutputStream, if any, for the log writer is returned
   *                in this array at index zero
   * @throws GemFireIOException
   *                 if the log file can't be opened for writing
   */
  public static LogWriterImpl createLogWriter(
    boolean appendToFile,
    boolean isLoner,
    boolean isSecurityLog,
    DistributionConfig config,
    boolean logConfig, FileOutputStream[] FOSHolder
    )
  {
    LogWriterImpl logger = null;
    File logFile = config.getLogFile();
    String logFilePath = null;
    PrintStream out = null;
    String firstMsg = null;
    boolean firstMsgWarning = false;
    File tmpLogFile;
    final boolean useSLF4JBridge = SystemProperties.getServerInstance()
        .getBoolean(ENABLE_SLF4J_LOG_BRIDGE, true);

    if (isSecurityLog && (tmpLogFile = config.getSecurityLogFile()) != null
        && !tmpLogFile.equals(new File(""))) {
      logFile = tmpLogFile;
    }
      
    if (logFile == null || logFile.equals(new File(""))) {
      out = System.out;
    } else {
      if (logFile.exists()) {
        boolean useChildLogging = config.getLogFile() != null && !config.getLogFile().equals(new File("")) && config.getLogFileSizeLimit() != 0;
        boolean statArchivesRolling = config.getStatisticArchiveFile() != null && !config.getStatisticArchiveFile().equals(new File("")) && config.getArchiveFileSizeLimit() != 0 && config.getStatisticSamplingEnabled();
        if (!appendToFile || useChildLogging || statArchivesRolling) { // check useChildLogging for bug 50659
        File oldMain = ManagerLogWriter.getLogNameForOldMainLog(logFile, isSecurityLog || useChildLogging || statArchivesRolling);
	boolean succeeded = LogFileUtils.renameAggressively(logFile,oldMain);
	if(succeeded) {
	  firstMsg = LocalizedStrings.InternalDistributedSystem_RENAMED_OLD_LOG_FILE_TO_0.toLocalizedString(oldMain);
        } else {
          firstMsgWarning = true;
          firstMsg = LocalizedStrings.InternalDistributedSystem_COULD_NOT_RENAME_0_TO_1.toLocalizedString(new Object[] {logFile, oldMain});
        }
        }
      }
      logFilePath = logFile.getPath();
      if (!useSLF4JBridge) {
        FileOutputStream fos = null;
        try {
          fos = new FileOutputStream(logFile, true);
        } catch (FileNotFoundException ex) {
          final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl
              .getInternalProductCallbacks();
          if (sysCb != null) {
            if (!sysCb.isAdmin()) {
              String s = LocalizedStrings.InternalDistributedSystem_COULD_NOT_OPEN_LOG_FILE_0
                  .toLocalizedString(logFile);
              throw new GemFireIOException(s, ex);
            }
          }
          else {
            String s = LocalizedStrings.InternalDistributedSystem_COULD_NOT_OPEN_LOG_FILE_0
                .toLocalizedString(logFile);
            throw new GemFireIOException(s, ex);
          }
        }
        
        if ( fos != null ) {
          out = new PrintStream(fos);
        } else {
          out = System.out;
        }
      
      if (FOSHolder != null) {
        FOSHolder[0] = fos;
      }
    }
    }

    if (useSLF4JBridge) {
      if (isSecurityLog) {
        logger = new GFToSlf4jBridge(config.getName(), logFilePath,
            config.getSecurityLogLevel());
      } else {
        logger = new GFToSlf4jBridge(config.getName(), logFilePath,
            config.getLogLevel());
      }
      if (logger.infoEnabled()
          && !Boolean.getBoolean(InternalLocator.INHIBIT_DM_BANNER)) {
        logger.info(Banner.getString(null));
      }
    } else if (isLoner) {
      if (isSecurityLog) {
        logger = new SecurityLocalLogWriter(config.getSecurityLogLevel(), out);
      } else {
        logger = new LocalLogWriter(config.getLogLevel(), out);
      }

      if (logger.infoEnabled()
          && !Boolean.getBoolean(InternalLocator.INHIBIT_DM_BANNER)) {
        // do this on a loner to fix bug 35602
        logger.info(Banner.getString(null));
      }
    } else {
      ManagerLogWriter mlw;
      if (isSecurityLog) {
        mlw = new SecurityManagerLogWriter(config.getSecurityLogLevel(), out,
            config.getName());
      }
      else {
        mlw = new ManagerLogWriter(config.getLogLevel(), out, config.getName());
      }
      if (mlw.infoEnabled() && !Boolean.getBoolean(InternalLocator.INHIBIT_DM_BANNER)) {
        mlw.info(Banner.getString(null));
      }
      mlw.setConfig(config);
      logger = mlw;
    }
    if (firstMsg != null) {
      if (firstMsgWarning) {
        logger.warning(firstMsg);
      } else {
        logger.info(firstMsg);
      }
    }
    if (logConfig && logger.configEnabled()) {
      logger.config(LocalizedStrings.InternalDistributedSystem_STARTUP_CONFIGURATIONN_0, config.toLoggerString());
    }
    if (isLoner) {
      logger.info(LocalizedStrings.InternalDistributedSystem_RUNNING_IN_LOCAL_MODE_SINCE_MCASTPORT_WAS_0_AND_LOCATORS_WAS_EMPTY);
    }
    if (DataSerializer.DEBUG) {
      InternalDataSerializer.logger = logger;
    }

    // fix #46493 by moving redirectOutput invocation here
    if (ProcessLauncherContext.isRedirectingOutput()) {
      try {
        OSProcess.redirectOutput(config.getLogFile());
      } catch (IOException e) {
        logger.error(e);
        //throw new GemFireIOException("Unable to redirect output to " + config.getLogFile(), e);
      }
    }

    LogService.configure(logger);
    return logger;
  }

  /** Used by DistributionManager to fix bug 33362
   */
  void setDM(DM dm) {
    this.dm = dm;
  }

  public static String toHexString(byte[] bytes) {
    StringBuilder result = new StringBuilder((bytes.length * 2) + 1);
    for (int i = 0; i < bytes.length; i++) {
      result.append(Integer.toHexString(bytes[i]));
    }
    return result.toString();
  }



  /**
   * Checks whether or not this connection to a distributed system is
   * closed.
   *
   * @throws DistributedSystemDisconnectedException
   *         This connection has been {@link #disconnect(boolean, String, boolean) disconnected}
   */
  private void checkConnected() {
    if (!isConnected()) {
      throw newDisconnectedException(this.dm.getRootCause());
    }
  }

  public static DistributedSystemDisconnectedException newDisconnectedException(
      final Throwable cause) {
    return new DistributedSystemDisconnectedException(LocalizedStrings
        .InternalDistributedSystem_THIS_CONNECTION_TO_A_DISTRIBUTED_SYSTEM_HAS_BEEN_DISCONNECTED
            .toLocalizedString(), cause);
  }

  @Override
  public boolean isConnected() {
    if (this.dm == null) {
      return false;
    }
    if (this.dm.getCancelCriterion().cancelInProgress() != null) {
      return false;
    }
    if (this.isDisconnecting) {
      return false;
    }
    return this.isConnected;
  }

  /**
   * This class defers to the DM.  If we don't have a DM, we're dead.
   * @author jpenney
   */
  protected final class Stopper extends CancelCriterion {
    @Override
    public String cancelInProgress() {
      if (dm == null) {
        return "No dm";
      }
      return dm.getCancelCriterion().cancelInProgress();
    }

    @Override
    public RuntimeException generateCancelledException(Throwable e) {
      if (dm == null) {
        return new DistributedSystemDisconnectedException("no dm", e);
      }
      return dm.getCancelCriterion().generateCancelledException(e);
    }
  }

  /**
   * Handles all cancellation queries for this distributed system
   */
  private final Stopper stopper = new Stopper();

  @Override
  public CancelCriterion getCancelCriterion() {
    return stopper;
  }

  public boolean isDisconnecting() {
    if (this.dm == null) {
      return true;
    }
    if (this.dm.getCancelCriterion().cancelInProgress() != null) {
      return true;
    }
    if (!this.isConnected) {
      return true;
    }
    return this.isDisconnecting;
    }

  @Override
  public final LogWriter getLogWriter() {
    return this.logger;
  }

  @Override
  public final LogWriter getSecurityLogWriter() {
    return this.securityLogger;
  }

  public final LogWriterI18n getLogWriterI18n() {
    return (LogWriterI18n)this.logger;
  }

  /*
  public Cache myCache;

  public void setCache(Cache cache){
	  myCache=cache;
  }
  public Cache getCache(){
	  return myCache;
  }
  */
  /**
   * Returns the stat sampler
   */
  public GemFireStatSampler getStatSampler() {
    return this.sampler;
  }

  /** Has this system started the disconnect process? */
  protected volatile boolean isDisconnecting = false;

  /**
   * Disconnects this VM from the distributed system.  Shuts down the
   * distribution manager, and if necessary,
   */
  @Override
  public void disconnect() {
    disconnect(false, LocalizedStrings.InternalDistributedSystem_NORMAL_DISCONNECT.toLocalizedString(), false);
  }

  /**
   * Disconnects this member from the distributed system when an internal
   * error has caused distribution to fail (e.g., this member was shunned)
   * @param reason a string describing why the disconnect is occurring
   * @param cause an optional exception showing the reason for abnormal disconnect
   * @param shunned whether this member was shunned by the membership coordinator
   */
  public void disconnect(String reason, Throwable cause, boolean shunned) {
    boolean isForcedDisconnect = dm.getRootCause() instanceof ForcedDisconnectException;
    boolean reconnected = false;
    this.reconnected = false;
    if (isForcedDisconnect) {
      this.forcedDisconnect = true;
      reconnectAttemptCounter = 0;
      try {
        reconnected = tryReconnect(true, reason, GemFireCacheImpl.getInstance());
      } finally {
        reconnectAttemptCounter = 0;
      }
    }
    if (!reconnected) {
      disconnect(false, reason, shunned);
    }
  }

  /**
   * This is how much time, in milliseconds to allow a disconnect listener
   * to run before we interrupt it.
   */
  static private final long MAX_DISCONNECT_WAIT =
    Long.getLong("DistributionManager.DISCONNECT_WAIT",
        10 * 1000).longValue();

  /**
   * Run a disconnect listener, checking for errors and
   * honoring the timeout {@link #MAX_DISCONNECT_WAIT}.
   *
   * @param dc the listener to run
   */
  private void runDisconnect(final DisconnectListener dc,
      ThreadGroup tg) {
    // Create a general handler for running the disconnect
    Runnable r = new Runnable() {
      public void run() {
        try {
          disconnectListenerThread.set(Boolean.TRUE);
          dc.onDisconnect(InternalDistributedSystem.this);
        }
        catch (CancelException e) {
          logger.fine("Disconnect listener <" + dc + "> thwarted by cancellation: " + e,
              logger.finerEnabled() ? e : null);
        }
      }
    };

    // Launch it and wait a little bit
    Thread t = new Thread(tg, r, dc.toString());
    try {
      t.start();
      t.join(MAX_DISCONNECT_WAIT);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.convertToLogWriterI18n().warning(LocalizedStrings.InternalDistributedSystem_INTERRUPTED_WHILE_PROCESSING_DISCONNECT_LISTENER, e);
    }

    // Make sure the listener gets the cue to die
    if (t.isAlive()) {
      logger.convertToLogWriterI18n().warning(LocalizedStrings.InternalDistributedSystem_DISCONNECT_LISTENER_STILL_RUNNING__0, dc);
      t.interrupt();

      try {
        t.join(MAX_DISCONNECT_WAIT);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      if (t.isAlive())
        logger.convertToLogWriterI18n().warning(LocalizedStrings.InternalDistributedSystem_DISCONNECT_LISTENER_IGNORED_ITS_INTERRUPT__0, dc);
    }

  }

  public boolean isDisconnectListenerThread() {
    Boolean disconnectListenerThreadBoolean =
      (Boolean) this.disconnectListenerThread.get();

    return disconnectListenerThreadBoolean != null &&
           disconnectListenerThreadBoolean.booleanValue();
  }

  /**
   * Run a disconnect listener in the same thread sequence as the reconnect.
   * @param dc the listener to run
   * @param tg the thread group to run the listener in
   */

  private void runDisconnectForReconnect(final DisconnectListener dc,
      ThreadGroup tg){
    try {
      dc.onDisconnect(InternalDistributedSystem.this);
    } catch (DistributedSystemDisconnectedException e) {
      logger.fine("Disconnect listener <" + dc +"> thwarted by shutdown: " + e,
          logger.finerEnabled() ? e : null);
    }
  }

  /**
   * A logging thread group for the disconnect and shutdown listeners
   */
  private final ThreadGroup disconnectListenerThreadGroup =
    LogWriterImpl.createThreadGroup("Disconnect Listeners", (LogWriterI18n) null);

  /**
   * Disconnect cache, run disconnect listeners.
   *
   * @param doReconnect whether a reconnect will be done
   * @param reason the reason that the system is disconnecting
   *
   * @return a collection of shutdownListeners
   */
  private HashSet doDisconnects(boolean doReconnect, String reason) {
    // Make a pass over the disconnect listeners, asking them _politely_
    // to clean up.
    HashSet shutdownListeners = new HashSet();
    for (;;) {
      DisconnectListener listener = null;
      synchronized (this.listeners) {
        Iterator itr = listeners.iterator();
        if (!itr.hasNext()) {
          break;
        }
        listener = (DisconnectListener)itr.next();
        if (listener instanceof ShutdownListener) {
          shutdownListeners.add(listener);
        }
        itr.remove();
      } // synchronized

      if (doReconnect){
        runDisconnectForReconnect(listener, disconnectListenerThreadGroup);
      } else {
        runDisconnect(listener, disconnectListenerThreadGroup);
      }
    } // for
    return shutdownListeners;
  }

  /**
   * Process the shutdown listeners.  It is essential that the DM has been
   * shut down before calling this step, to ensure that no new listeners are
   * registering.
   *
   * @param shutdownListeners shutdown listeners initially registered with us
   */
  private void doShutdownListeners(HashSet shutdownListeners) {
    if (shutdownListeners == null) {
      return;
    }

    // Process any shutdown listeners we reaped during first pass
    Iterator it = shutdownListeners.iterator();
    while (it.hasNext()) {
      ShutdownListener s = (ShutdownListener)it.next();
      try {
        s.onShutdown(this);
      }
      catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        // things could break since we continue, but we want to disconnect!
        this.logger.convertToLogWriterI18n().severe(LocalizedStrings.InternalDistributedSystem_SHUTDOWNLISTENER__0__THREW, s, t);
      }
    }

    // During the window while we were running disconnect listeners, new
    // disconnect listeners may have appeared. After messagingDisabled is
    // set, no new ones will be created.  However, we must process any
    // that appeared in the interim.
    for (;;) {
      // Pluck next listener from the list
      DisconnectListener dcListener = null;
      ShutdownListener sdListener = null;
      synchronized (this.listeners) {
        Iterator itr = listeners.iterator();
        if (!itr.hasNext())
          break;
        dcListener = (DisconnectListener)itr.next();
        itr.remove();
        if (dcListener instanceof ShutdownListener)
          sdListener = (ShutdownListener)dcListener;
      }

      // Run the disconnect
      runDisconnect(dcListener, disconnectListenerThreadGroup);

      // Run the shutdown, if any
      if (sdListener != null) {
        try {
          // TODO: should we make sure this times out?
          sdListener.onShutdown(this);
        }
        catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          // Whenever you catch Error or Throwable, you must also
          // check for fatal JVM error (see above).  However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          SystemFailure.checkFailure();
          // things could break since we continue, but we want to disconnect!
          this.logger.convertToLogWriterI18n().severe(
              LocalizedStrings.InternalDistributedSystem_DISCONNECTLISTENERSHUTDOWN_THREW, t);
        }
      }
    } // for
  }

  /**
   * break any potential circularity in {@link #loadEmergencyClasses()}
   */
  private static volatile boolean emergencyClassesLoaded = false;

  /**
   * Ensure that the JGroupMembershipManager class gets loaded.
   *
   * @see SystemFailure#loadEmergencyClasses()
   */
  static public void loadEmergencyClasses() {
    if (emergencyClassesLoaded) return;
    emergencyClassesLoaded = true;
    JGroupMembershipManager.loadEmergencyClasses();
  }

  /**
   * Closes the membership manager
   *
   * @see SystemFailure#emergencyClose()
   */
  public void emergencyClose() {
    final boolean DEBUG = SystemFailure.TRACE_CLOSE;
    if (dm != null) {
      MembershipManager mm = dm.getMembershipManager();
      if (mm != null) {
        if (DEBUG) {
          System.err.println("DEBUG: closing membership manager");
        }
        mm.emergencyClose();
        if (DEBUG) {
          System.err.println("DEBUG: back from closing membership manager");
        }
      }
    }

    // Garbage collection
    // Leave dm alone; its CancelCriterion will help people die
    this.isConnected = false;
    if (dm != null) {
      dm.setRootCause(SystemFailure.getFailure());
    }
    this.isDisconnecting = true;
    this.listeners.clear();
    if (DEBUG) {
      System.err.println("DEBUG: done with InternalDistributedSystem#emergencyClose");
    }
  }

  private void setDisconnected() {
    synchronized (this.isConnectedMutex) {
      this.isConnected = false;
      isConnectedMutex.notifyAll();
    }
  }

  private void waitDisconnected() {
    synchronized (this.isConnectedMutex) {
      while (this.isConnected) {
        boolean interrupted = Thread.interrupted();
        try {
          this.isConnectedMutex.wait();
        }
        catch (InterruptedException e) {
          interrupted = true;
          getLogWriter().convertToLogWriterI18n().warning(
            LocalizedStrings.InternalDistributedSystem_DISCONNECT_WAIT_INTERRUPTED, e);
        }
        finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      } // while
    }
  }

  /**
   * Disconnects this VM from the distributed system. Shuts down the
   * distribution manager.
   *
   * @param preparingForReconnect
   *          true if called by a reconnect operation
   * @param reason
   *          the reason the disconnect is being performed
   * @param keepAlive
   *          true if user requested durable subscriptions are to be retained at
   *          server.
   */
  protected void disconnect(boolean preparingForReconnect, String reason, boolean keepAlive) {
    boolean isShutdownHook = (shutdownHook != null)
                             && (Thread.currentThread() == shutdownHook);

    if (!preparingForReconnect) {
      synchronized(reconnectListeners) {
        reconnectListeners.clear();
      }
    }

    try {
      HashSet shutdownListeners = null;
      try {
        if (logger.fineEnabled()) {
          logger.fine("DistributedSystem.disconnect invoked on " + this);
        }
        synchronized (GemFireCacheImpl.class) {
          // bug 36955, 37014: don't use a disconnect listener on the cache;
          // it takes too long.
          //
          // However, make sure cache is completely closed before starting
          // the distributed system close.
          GemFireCacheImpl currentCache = GemFireCacheImpl.getInstance();
          if (currentCache != null && !currentCache.isClosed()) {
            disconnectListenerThread.set(Boolean.TRUE); // bug #42663 - this must be set while closing the cache
            try {
              currentCache.close(reason, dm.getRootCause(), keepAlive, true); // fix for 42150
            }
            catch (VirtualMachineError e) {
              SystemFailure.initiateFailure(e);
              throw e;
            }
            catch (Throwable e) {
              SystemFailure.checkFailure();
              // Whenever you catch Error or Throwable, you must also
              // check for fatal JVM error (see above).  However, there is
              logger.convertToLogWriterI18n().warning(
                  LocalizedStrings.InternalDistributedSystem_EXCEPTION_TRYING_TO_CLOSE_CACHE, e);
            }
            finally {
              disconnectListenerThread.set(Boolean.FALSE);
            }
          }
          if (!preparingForReconnect) {
            cancelReconnect();
          }

        DiskCapacityMonitor.clearInstance();

          // While still holding the lock, make sure this instance is
          // marked as shutting down
          synchronized (this) {
            if (this.isDisconnecting) {
              // It's already started, but don't return
              // to the caller until it has completed.
              waitDisconnected();
              return;
            } // isDisconnecting
            this.isDisconnecting = true;

            if (!preparingForReconnect) {
              // move cancelReconnect above this synchronized block fix for bug 35202
              if (this.reconnectDS != null) {
                // break recursion
                if (logger.fineEnabled()) {
                  logger.fine("disconnecting reconnected DS: " + this.reconnectDS);
                }
                InternalDistributedSystem r = this.reconnectDS;
                this.reconnectDS = null;
                r.disconnect(false, null, false);
              }
            } // !reconnect
          } // synchronized (this)
        } // synchronized (GemFireCache.class)

        if (!isShutdownHook) {
          shutdownListeners = doDisconnects(attemptingToReconnect, reason);
        }

        if (this.logger instanceof ManagerLogWriter) {
          ((ManagerLogWriter)this.logger).shuttingDown();
        }

      }
      finally { // be ABSOLUTELY CERTAIN that dm closed
        try {
          // Do the bulk of the close...
          this.dm.close();
          // we close the locator after the DM so that when split-brain detection
          // is enabled, loss of the locator doesn't cause the DM to croak
          if (this.startedLocator != null) {
            this.startedLocator.stop(preparingForReconnect, false);
            this.startedLocator = null;
          }
        } finally { // timer canceled
          // bug 38501: this has to happen *after*
          // the DM is closed :-(
          if (!preparingForReconnect) {
            SystemTimer.cancelSwarm(this);
          }
        } // finally timer cancelled
      } // finally dm closed

      if (!isShutdownHook) {
        doShutdownListeners(shutdownListeners);
      }

      // closing the Aggregate stats
      if(functionServiceStats != null){
        functionServiceStats.close();
      }
      // closing individual function stats
      for (FunctionStats functionstats : functionExecutionStatsMap.values()) {
        functionstats.close();
      }

      (new FunctionServiceManager()).unregisterAllFunctions();

      if (this.sampler != null) {
        this.sampler.stop();
        this.sampler = null;
      }

      if (this.localLogFileStream != null) {
        if (this.logger instanceof ManagerLogWriter) {
          try {
            ((ManagerLogWriter)this.logger).closingLogFile();
          } catch(Exception e) {
            // Ignore, we are shutting down
          }
        }

        try {
          this.localLogFileStream.close();
        }
        catch (IOException ignore) {
        }
      }
      if (this.localSecurityLogFileStream != null) {
        if (this.securityLogger instanceof SecurityManagerLogWriter) {
          ((SecurityManagerLogWriter)this.securityLogger).closingLogFile();
        }
        try {
          this.localSecurityLogFileStream.close();
        }
        catch (IOException ignore) {
        }
      }
      ClientSharedUtils.clear();
      LogService.clear();
      // NOTE: no logging after this point :-)

      LogWriterImpl.cleanUpThreadGroups(); // bug35388 - logwriters accumulate, causing mem leak
      EventID.unsetDS();
      InternalBridgeMembership.unsetLogger();

    }
    finally {
      try {
        if (getOffHeapStore() != null) {
          getOffHeapStore().close();
        }
      } finally {
      try {
        removeSystem(this);
        // Close the config object
        this.config.close();
      }
      finally {
        // Finally, mark ourselves as disconnected
        setDisconnected();
        SystemFailure.stopThreads();
      }
      }
    }
  }

  /**
   * Returns the distribution manager for accessing this distributed system.
   */
  public DM getDistributionManager() {
    checkConnected();
    return this.dm;
  }

  /**
   * Returns the distribution manager without checking for connected or not so
   * can also return null.
   */
  public final DM getDM() {
    return this.dm;
  }
  
  /**
   * If this DistributedSystem is attempting to reconnect to the distributed system
   * this will return the quorum checker created by the old MembershipManager for
   * checking to see if a quorum of old members can be reached.
   * @return the quorum checking service
   */
  public final QuorumChecker getQuorumChecker() {
    return this.quorumChecker;
  }
  
  /**
   * Returns true if this DS has been attempting to reconnect but
   * the attempt has been cancelled.
   */
  public boolean isReconnectCancelled() {
    return this.reconnectCancelled;
  }

  /**
   * Returns whether or not this distributed system has the same
   * configuration as the given set of properties.
   *
   * @see DistributedSystem#connect
   */
  public boolean sameAs(Properties props) {
    return originalConfig.sameAs(DistributionConfigImpl.produce(props));
  }

  public boolean threadOwnsResources() {
    Boolean b = ConnectionTable.getThreadOwnsResourcesRegistration();
    if (b == null) {
      // thread does not have a preference so return default
      return !this.shareSockets;
    } else {
      return b.booleanValue();
    }
  }

  /**
   * Returns whether or not the given configuration properties refer
   * to the same distributed system as this
   * <code>InternalDistributedSystem</code> connection.
   *
   * @since 4.0
   */
  public boolean sameSystemAs(Properties props) {
    DistributionConfig other = DistributionConfigImpl.produce(props);
    DistributionConfig me = this.getConfig();

    if (!me.getBindAddress().equals(other.getBindAddress())) {
      return false;
    }

    // @todo Do we need to compare SSL properties?

    if (me.getMcastPort() != 0) {
      // mcast
      return me.getMcastPort() == other.getMcastPort() &&
        me.getMcastAddress().equals(other.getMcastAddress());

    } else {
      // locators
      String myLocators = me.getLocators();
      String otherLocators = other.getLocators();

      // quick check
      if (myLocators.equals(otherLocators)) {
        return true;

      } else {
        myLocators = canonicalizeLocators(myLocators);
        otherLocators = canonicalizeLocators(otherLocators);

        return myLocators.equals(otherLocators);
      }
    }
  }

  /**
   * Canonicalizes a locators string so that they may be compared.
   *
   * @since 4.0
   */
  private static String canonicalizeLocators(String locators) {
    SortedSet sorted = new TreeSet();
    StringTokenizer st = new StringTokenizer(locators, ",");
    while (st.hasMoreTokens()) {
      String l = st.nextToken();
      StringBuilder canonical = new StringBuilder();
      DistributionLocatorId locId = new DistributionLocatorId(l);
      if (!locId.isMcastId()) {
        String addr = locId.getBindAddress();
        if (addr != null && addr.trim().length() > 0) {
          canonical.append(addr);
        }
        else {
          canonical.append(locId.getHost().getHostAddress());
        }
        canonical.append("[");
        canonical.append(String.valueOf(locId.getPort()));
        canonical.append("]");
        sorted.add(canonical.toString());
      }
    }

    StringBuilder sb = new StringBuilder();
    for (Iterator iter = sorted.iterator(); iter.hasNext(); ) {
      sb.append((String) iter.next());
      if (iter.hasNext()) {
        sb.append(",");
      }
    }
    return sb.toString();
  }

  private final StoppableReentrantLock elderLock;
  private final StoppableCondition elderLockCondition;

  public StoppableReentrantLock getElderLock() {
    return elderLock;
  }
  public StoppableCondition getElderLockCondition() {
    return elderLockCondition;
  }

  /**
   * Returns the current configuration of this distributed system.
   */
  public DistributionConfig getConfig() {
    return this.config;
  }

  /**
   * Returns the id of this connection to the distributed system.
   * This is actually the port of the distribution manager's
   * distribution channel.
   *
   * @see com.gemstone.gemfire.distributed.internal.DistributionChannel#getId
   */
  @Override
  public long getId() {
    return this.id;
  }

  /**
   * Returns the string value of the distribution manager's id.
   */
  @Override
  public String getMemberId() {
    return String.valueOf(this.dm.getId());
  }

  @Override
  public InternalDistributedMember getDistributedMember() {
    return this.dm.getId();
  }
  @Override
  public Set<DistributedMember> getAllOtherMembers() {
    return dm.getAllOtherMembers();
  }
  @Override
  public Set<DistributedMember> getGroupMembers(String group) {
    return dm.getGroupMembers(group);
  }


  /**
   * Returns the configuration this distributed system was created with.
   */
  public DistributionConfig getOriginalConfig() {
    return this.originalConfig;
  }
  @Override
  public String getName() {
    return getOriginalConfig().getName();
  }

  ///////////////////////  Utility Methods  ///////////////////////

  /**
   * Since {@link DistributedSystem#connect} guarantees that there is
   * a canonical instance of <code>DistributedSystem</code> for each
   * configuration, we can use the default implementation of
   * <code>equals</code>.
   *
   * @see #sameAs
   */
  @Override
  public boolean equals(Object o) {
    return super.equals(o);
  }

  /**
   * Since we use the default implementation of {@link #equals
   * equals}, we can use the default implementation of
   * <code>hashCode</code>.
   */
  @Override
  public int hashCode() {
    return super.hashCode();
  }

  /**
   * Returns a string describing this connection to distributed system
   * (including highlights of its configuration).
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Connected ");
    String name = this.getName();
    if (name != null && !name.equals("")) {
      sb.append("\"");
      sb.append(name);
      sb.append("\" ");
    }
    sb.append("(id=");
    sb.append(Integer.toHexString(System.identityHashCode(this)));
    sb.append(") ");

    sb.append("to distributed system using ");
    int port = this.config.getMcastPort();
    if (port != 0) {
      sb.append("multicast port ");
      sb.append(port);
      sb.append(" ");

    } else {
      sb.append("locators \"");
      sb.append(this.config.getLocators());
      sb.append("\" ");
    }

    File logFile = this.config.getLogFile();
    sb.append("logging to ");
    if (logFile == null || logFile.equals(new File(""))) {
      sb.append("standard out ");

    } else {
      sb.append(logFile);
      sb.append(" ");
    }

    sb.append(" started at ");
    sb.append((new Date(this.startTime)).toString());

    if (!this.isConnected()) {
      sb.append(" (closed)");
    }

    return sb.toString().trim();
  }

  private final ArrayList<Statistics> statsList = new ArrayList<Statistics>();
  private int statsListModCount = 0;
  private long statsListUniqueId = 1;
  private final Object statsListUniqueIdLock = new Object();

  // As the function execution stats can be lot in number, its better to put
  // them in a map so that it will be accessible immediately
  private final ConcurrentHashMap<String, FunctionStats>  functionExecutionStatsMap = new ConcurrentHashMap<String, FunctionStats>();
  private FunctionServiceStats functionServiceStats = null;

  public int getStatListModCount() {
    return this.statsListModCount;
  }
  public List<Statistics> getStatsList() {
    return this.statsList;
  }

  @Override
  public final int getStatisticsCount() {
    int result = 0;
    List<Statistics> statsList = this.statsList;
    if (statsList != null) {
      result = statsList.size();
    }
    return result;
  }

  @Override
  public final Statistics findStatistics(long id) {
    List<Statistics> statsList = this.statsList;
    synchronized (statsList) {
      for (Statistics s : statsList) {
        if (s.getUniqueId() == id) {
          return s;
        }
      }
    }
    throw new RuntimeException(LocalizedStrings.PureStatSampler_COULD_NOT_FIND_STATISTICS_INSTANCE.toLocalizedString());
  }

  @Override
  public final boolean statisticsExists(long id) {
    List<Statistics> statsList = this.statsList;
    synchronized (statsList) {
      for (Statistics s : statsList) {
        if (s.getUniqueId() == id) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public final Statistics[] getStatistics() {
    List<Statistics> statsList = this.statsList;
    synchronized (statsList) {
      return statsList.toArray(new Statistics[statsList.size()]);
    }
  }

  // StatisticsFactory methods
  public Statistics createStatistics(StatisticsType type) {
    return createOsStatistics(type, null, 0, 0);
  }
  public Statistics createStatistics(StatisticsType type, String textId) {
    return createOsStatistics(type, textId, 0, 0);
  }
  public Statistics createStatistics(StatisticsType type, String textId, long numericId) {
    return createOsStatistics(type, textId, numericId, 0);
  }
  public Statistics createOsStatistics(StatisticsType type, String textId, long numericId, int osStatFlags) {
    if (this.statsDisabled) {
      return new DummyStatisticsImpl(type, textId, numericId);
    }
    long myUniqueId;
    synchronized (statsListUniqueIdLock) {
      myUniqueId = statsListUniqueId++; // fix for bug 30597
    }
    Statistics result = new LocalStatisticsImpl(type, textId, numericId, myUniqueId, false, osStatFlags, this);
    synchronized (statsList) {
      statsList.add(result);
      statsListModCount++;
    }
    return result;
  }

  public FunctionStats getFunctionStats(String textId) {
    FunctionStats stats = functionExecutionStatsMap.get(textId);
    if (stats == null) {
      stats = new FunctionStats(this, textId);
      FunctionStats oldStats = functionExecutionStatsMap.putIfAbsent(textId,
          stats);
      if (oldStats != null) {
        stats.close();
        stats = oldStats;
      }
    }
    return stats;
  }


  public FunctionServiceStats getFunctionServiceStats() {
    if (functionServiceStats == null) {
      synchronized (this) {
        if(functionServiceStats == null){
          functionServiceStats = new FunctionServiceStats(this, "FunctionExecution");
        }
      }
    }
    return functionServiceStats;
  }

  /**
   * For every registered statistic instance call the specified visitor.
   * This method was added to fix bug 40358
   */
  public void visitStatistics(StatisticsVisitor visitor) {
    synchronized (this.statsList) {
      for (Statistics s: this.statsList) {
        visitor.visit(s);
      }
    }
  }

  /**
   * Used to "visit" each instance of Statistics registered with
   * @see #visitStatistics
   */
  public interface StatisticsVisitor {
    public void visit(Statistics stat);
  }

  public Set<String> getAllFunctionExecutionIds() {
    return functionExecutionStatsMap.keySet();
  }


  public Statistics[] findStatisticsByType(final StatisticsType type) {
    final ArrayList hits = new ArrayList();
    visitStatistics(new StatisticsVisitor() {
        public void visit(Statistics s) {
          if (type == s.getType()) {
            hits.add(s);
          }
        }
      });
    Statistics[] result = new Statistics[hits.size()];
    return (Statistics[])hits.toArray(result);
  }

  public Statistics[] findStatisticsByTextId(final String textId) {
    final ArrayList hits = new ArrayList();
    visitStatistics(new StatisticsVisitor() {
        public void visit(Statistics s) {
          if (s.getTextId().equals(textId)) {
            hits.add(s);
          }
        }
      });
    Statistics[] result = new Statistics[hits.size()];
    return (Statistics[])hits.toArray(result);
  }
  public Statistics[] findStatisticsByNumericId(final long numericId) {
    final ArrayList hits = new ArrayList();
    visitStatistics(new StatisticsVisitor() {
        public void visit(Statistics s) {
          if (numericId == s.getNumericId()) {
            hits.add(s);
          }
        }
      });
    Statistics[] result = new Statistics[hits.size()];
    return (Statistics[])hits.toArray(result);
  }
  public Statistics findStatisticsByUniqueId(final long uniqueId) {
    synchronized (this.statsList) {
      for (Statistics s: this.statsList) {
        if (uniqueId == s.getUniqueId()) {
          return s;
        }
      }
    }
    return null;
  }

  /** for internal use only. Its called by {@link LocalStatisticsImpl#close}. */
  public void destroyStatistics(Statistics stats) {
    synchronized (statsList) {
      if (statsList.remove(stats)) {
        statsListModCount++;
      }
    }
  }

  public Statistics createAtomicStatistics(StatisticsType type) {
    return createAtomicStatistics(type, null, 0, 0);
  }
  public Statistics createAtomicStatistics(StatisticsType type, String textId) {
    return createAtomicStatistics(type, textId, 0, 0);
  }

  public Statistics createAtomicStatistics(StatisticsType type, String textId,
      long numericId) {
    return createAtomicStatistics(type, textId, numericId, 0);
  }

  public Statistics createAtomicStatistics(StatisticsType type, String textId,
      long numericId, long uniqueId) {
    if (this.statsDisabled) {
      return new DummyStatisticsImpl(type, textId, numericId);
    }

    long myUniqueId;
    if (uniqueId == 0) {
      synchronized (statsListUniqueIdLock) {
        myUniqueId = statsListUniqueId++; // fix for bug 30597
      }
    }
    else {
      myUniqueId = uniqueId;
    }
    Statistics result = StatisticsImpl.createAtomicNoOS(type, textId, numericId, myUniqueId, this);
    synchronized (statsList) {
      statsList.add(result);
      statsListModCount++;
    }
    return result;
  }


  // StatisticsTypeFactory methods
  private final static StatisticsTypeFactory tf = StatisticsTypeFactoryImpl.singleton();

  /**
   * Creates or finds a StatisticType for the given shared class.
   */
  public StatisticsType createType(String name, String description,
                                   StatisticDescriptor[] stats) {
    return tf.createType(name, description, stats);
  }
  public StatisticsType findType(String name) {
    return tf.findType(name);
  }
  public StatisticsType[] createTypesFromXml(Reader reader)
    throws IOException {
    return tf.createTypesFromXml(reader);
  }

  public StatisticDescriptor createIntCounter(String name, String description,
                                              String units) {
    return tf.createIntCounter(name, description, units);
  }
  public StatisticDescriptor createLongCounter(String name, String description,
                                               String units) {
    return tf.createLongCounter(name, description, units);
  }
  public StatisticDescriptor createDoubleCounter(String name, String description,
                                                 String units) {
    return tf.createDoubleCounter(name, description, units);
  }
  public StatisticDescriptor createIntGauge(String name, String description,
                                            String units) {
    return tf.createIntGauge(name, description, units);
  }
  public StatisticDescriptor createLongGauge(String name, String description,
                                             String units) {
    return tf.createLongGauge(name, description, units);
  }
  public StatisticDescriptor createDoubleGauge(String name, String description,
                                               String units) {
    return tf.createDoubleGauge(name, description, units);
  }
  public StatisticDescriptor createIntCounter(String name, String description,
                                              String units, boolean largerBetter) {
    return tf.createIntCounter(name, description, units, largerBetter);
  }
  public StatisticDescriptor createLongCounter(String name, String description,
                                               String units, boolean largerBetter) {
    return tf.createLongCounter(name, description, units, largerBetter);
  }
  public StatisticDescriptor createDoubleCounter(String name, String description,
                                                 String units, boolean largerBetter) {
    return tf.createDoubleCounter(name, description, units, largerBetter);
  }
  public StatisticDescriptor createIntGauge(String name, String description,
                                            String units, boolean largerBetter) {
    return tf.createIntGauge(name, description, units, largerBetter);
  }
  public StatisticDescriptor createLongGauge(String name, String description,
                                             String units, boolean largerBetter) {
    return tf.createLongGauge(name, description, units, largerBetter);
  }
  public StatisticDescriptor createDoubleGauge(String name, String description,
                                               String units, boolean largerBetter) {
    return tf.createDoubleGauge(name, description, units, largerBetter);
  }

  public long getStartTime() {
    return this.startTime;
  }

  /**
   * Makes note of a <code>ConnectListener</code> whose
   * <code>onConnect</code> method will be invoked when a connection is
   * created to a distributed system.
   * @return set of currently existing system connections
   */
  public static List addConnectListener(ConnectListener listener) {
    synchronized (existingSystemsLock) {
      synchronized (connectListeners) {
        connectListeners.add(listener);
        return existingSystems;
      }
    }
  }

  /**
   * Makes note of a <code>ReconnectListener</code> whose
   * <code>onReconnect</code> method will be invoked when a connection is
   * recreated to a distributed system during auto-reconnect.<p>
   *
   * The ReconnectListener set is cleared after a disconnect.
   */
  public static void addReconnectListener(ReconnectListener listener) {
//    (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("registering reconnect listener: " + listener);
    synchronized (existingSystemsLock) {
      synchronized (reconnectListeners) {
        reconnectListeners.add(listener);
      }
    }
  }

  /**
   * Removes a <code>ConnectListener</code> from the list of
   * listeners that will be notified when a connection is created to
   * a distributed system.
   * @return true if listener was in the list
   */
  public static boolean removeConnectListener(ConnectListener listener) {
    synchronized (connectListeners) {
      return connectListeners.remove(listener);
    }
  }

  /**
   * Notifies all registered <code>ConnectListener</code>s that a
   * connection to a distributed system has been created.
   */
  private static void notifyConnectListeners(InternalDistributedSystem sys) {
    synchronized (connectListeners) {
      for (Iterator iter = connectListeners.iterator(); iter.hasNext();) {
        try {
          ConnectListener listener = (ConnectListener) iter.next();
          listener.onConnect(sys);
        }
        catch (Throwable t) {
          Error err;
          if (t instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)t)) {
            SystemFailure.initiateFailure(err);
            // If this ever returns, rethrow the error. We're poisoned
            // now, so don't let this thread continue.
            throw err;
          }
          // Whenever you catch Error or Throwable, you must also
          // check for fatal JVM error (see above).  However, there is
          // _still_ a possibility that you are dealing with a cascading
          // error condition, so you also need to check to see if the JVM
          // is still usable:
          SystemFailure.checkFailure();
          sys.getLogWriter().convertToLogWriterI18n().severe(LocalizedStrings.InternalDistributedSystem_CONNECTLISTENER_THREW, t);
        }
      }
    }
  }

  /**
   * Removes a <code>ReconnectListener</code> from the list of
   * listeners that will be notified when a connection is recreated to
   * a distributed system.
   */
  public static void removeReconnectListener(ReconnectListener listener) {
    synchronized (reconnectListeners) {
      reconnectListeners.remove(listener);
    }
  }

  /**
   * Notifies all registered <code>ReconnectListener</code>s that a
   * connection to a distributed system has been recreated.
   * @param log used for logging exceptions thrown by listeners
   */
  private static void notifyReconnectListeners(InternalDistributedSystem oldsys, InternalDistributedSystem newsys, boolean starting, LogWriterI18n log) {
    List<ReconnectListener> listeners;
    synchronized (reconnectListeners) {
      listeners = new ArrayList<ReconnectListener>(reconnectListeners);
    }
    for (ReconnectListener listener: listeners) {
      try {
        if (starting) {
          listener.reconnecting(oldsys);
        } else {
          listener.onReconnect(oldsys, newsys);
        }
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        log.severe(LocalizedStrings.InternalDistributedSystem_CONNECTLISTENER_THREW, t);
      }
    }
  }

  /**
   * Notifies all resource event listeners. All exceptions are caught here and
   * only a warning message is printed in the log
   *
   * @param event
   *          Enumeration depicting particular resource event
   * @param resource
   *          the actual resource object.
   */
  private void notifyResourceEventListeners(ResourceEvent event, Object resource) {
    for (Iterator<ResourceEventsListener> iter = resourceListeners.iterator(); iter
        .hasNext();) {
      try {
        ResourceEventsListener listener = iter.next();
        listener.handleEvent(event, resource);
      } catch(CancelException e) {
        //ignore
      } catch (ManagementException ex) {
        if (event == ResourceEvent.CACHE_CREATE) {
          throw ex;
        }
        else {
          if (!(event == ResourceEvent.GATEWAYRECEIVER_CREATE || event == ResourceEvent.ASYNCEVENTQUEUE_CREATE)) {
            this.logger.warning(ex);
          }
        }
      } catch (Exception err) {
        this.logger.warning(err);
      } catch (VirtualMachineError e) {
        SystemFailure.initiateFailure(e);
        throw e;
      } catch (Throwable t) {
        SystemFailure.checkFailure();
        this.logger.warning(t);
      }
    }
  }
  
  /**
   * gemfirexd's disconnect listener is invoked before the cache is closed when
   * there is a forced disconnect
   */
  public void setGfxdForcedDisconnectListener(DisconnectListener listener) {
    synchronized(this.listeners) { 
      this.gfxdDisconnectListener = listener;
    }
  }
  
  private void notifyGfxdForcedDisconnectListener() {
    if (this.gfxdDisconnectListener != null) {
      try {
        if (logger.fineEnabled()) logger.fine("notifying sql disconnect listener");
        this.gfxdDisconnectListener.onDisconnect(this);
      } catch (VirtualMachineError e) {
        SystemFailure.initiateFailure(e);
        throw e;
      } catch (Throwable e) {
        SystemFailure.checkFailure();
        // TODO: should these be logged or ignored?  We need to see them
        this.logger.info("", e);
      }
      if (logger.fineEnabled()) logger.fine("finished notifying sql disconnect listener");
    }
  }
  
  

  /**
   * Makes note of a <code>DisconnectListener</code> whose
   * <code>onDisconnect</code> method will be invoked when this
   * connection to the distributed system is disconnected.
   */
  public void addDisconnectListener(DisconnectListener listener) {
    synchronized (this.listeners) {
      this.listeners.add(listener);

      Boolean disconnectListenerThreadBoolean =
        (Boolean) disconnectListenerThread.get();

      if (disconnectListenerThreadBoolean == null ||
          !disconnectListenerThreadBoolean.booleanValue()) {
        // Don't add disconnect listener after messaging has been disabled.
        // Do this test _after_ adding the listener to narrow the window.
        // It's possible to miss it still and never invoke the listener, but
        // other shutdown conditions will presumably get flagged.
        String reason = this.stopper.cancelInProgress();
        if (reason != null) {
          this.listeners.remove(listener); // don't leave in the list!
          throw new DistributedSystemDisconnectedException(LocalizedStrings.InternalDistributedSystem_NO_LISTENERS_PERMITTED_AFTER_SHUTDOWN_0.toLocalizedString(reason), dm.getRootCause());
        }
      }
    } // synchronized
  }

  /**
   * A non-null value of Boolean.TRUE will identify a thread being used to
   * execute disconnectListeners. {@link #addDisconnectListener} will
   * not throw ShutdownException if the value is Boolean.TRUE.
   */
  final ThreadLocal disconnectListenerThread = new ThreadLocal();

  /**
   * Removes a <code>DisconnectListener</code> from the list of
   * listeners that will be notified when this connection to the
   * distributed system is disconnected.
   * @return true if listener was in the list
   */
  public boolean removeDisconnectListener(DisconnectListener listener) {
    synchronized (this.listeners) {
      return this.listeners.remove(listener);
    }
  }

  /**
   * Returns any existing <code>InternalDistributedSystem</code> instance.
   * Returns <code>null</code> if no instance exists.
   */
  public static InternalDistributedSystem getAnyInstance() {
    List l = existingSystems;
    if (l.isEmpty()) {
      return null;
    }
    else {
      return (InternalDistributedSystem)l.get(0);
    }
  }
  /**
   * Test hook
   */
  public static List getExistingSystems() {
    return existingSystems;
  }

  @Override
  public Properties getProperties() {
    return this.config.toProperties();
  }

  @Override
  public Properties getSecurityProperties() {
    return this.config.getSecurityProps();
  }

  /**
   * Fires an "informational" <code>SystemMembershipEvent</code> in
   * admin VMs.
   *
   * @since 4.0
   */
  public void fireInfoEvent(Object callback) {
    throw new UnsupportedOperationException(LocalizedStrings.InternalDistributedSystem_NOT_IMPLEMENTED_YET.toLocalizedString());
  }

  /**
   * Installs a shutdown hook to ensure
   * that we are disconnected if an application VM shuts down
   * without first calling disconnect itself.
     */
  public static final Thread shutdownHook;

  static {
    // Create a shutdown hook to cleanly close connection if
    // VM shuts down with an open connection.
    ThreadGroup tg = LogWriterImpl.createThreadGroup(SHUTDOWN_HOOK_NAME, (LogWriterI18n) null);
    Thread tmp_shutdownHook = null;
    try {
      //Added for Adobe, see bug 38407
      if( ! Boolean.getBoolean(DISABLE_SHUTDOWN_HOOK_PROPERTY)) {
        tmp_shutdownHook = new Thread(tg, new Runnable() {
          public void run() {
            DistributedSystem ds = InternalDistributedSystem.getAnyInstance();
            setThreadsSocketPolicy(true /* conserve sockets */);
            if (ds != null && ds.isConnected()) {
              LogWriterI18n log = ((InternalDistributedSystem)ds).getLogWriterI18n();
              log.info(LocalizedStrings.InternalDistributedSystem_shutdownHook_shuttingdown);
              ((InternalDistributedSystem)ds).disconnect(false,
                  LocalizedStrings.InternalDistributedSystem_NORMAL_DISCONNECT
                      .toLocalizedString(), true);
              // this was how we wanted to do it for 5.7, but there were shutdown
              // issues in PR/dlock (see bug 39287)
//              InternalDistributedSystem ids = (InternalDistributedSystem)ds;
//              if (ids.getDistributionManager() != null &&
//                  ids.getDistributionManager().getMembershipManager() != null) {
//                ids.getDistributionManager().getMembershipManager()
//                  .uncleanShutdown("VM is exiting", null);
//              }
            }
          }
        }, SHUTDOWN_HOOK_NAME);
        Runtime.getRuntime().addShutdownHook(tmp_shutdownHook);
      }
    } finally {
      shutdownHook = tmp_shutdownHook;
    }
  }
  ///////////////////////  Inner Classes  ///////////////////////

  /**
   * A listener that gets invoked before this connection to the
   * distributed system is disconnected.
   */
  public interface DisconnectListener {

    /**
     * Invoked before a connection to the distributed system is
     * disconnected.
     *
     * @param sys the the system we are disconnecting from
     * process should take before returning.
     */
    public void onDisconnect(InternalDistributedSystem sys);

  }

  /**
   * A listener that gets invoked before and after a successful auto-reconnect
   */
  public interface ReconnectListener {
    /**
     * Invoked when reconnect attempts are initiated
     *
     * @param oldSystem the old DS, which is in a partially disconnected state
     * and cannot be used for messaging
     */
    public void reconnecting(InternalDistributedSystem oldSystem);

    /**
     * Invoked after a reconnect to the distributed system
     * @param oldSystem the old DS
     * @param newSystem the new DS
     */
    public void onReconnect(InternalDistributedSystem oldSystem, InternalDistributedSystem newSystem);
  }

  /**
   * A listener that gets invoked after this connection to the
   * distributed system is disconnected
   * @author jpenney
   *
   */
  public interface ShutdownListener extends DisconnectListener {
    /**
     * Invoked after the connection to the distributed system has
     * been disconnected
     * @param sys
     */
    public void onShutdown(InternalDistributedSystem sys);
  }

  /**
   * Integer representing number of tries already made
   * to reconnect and that failed.
   * */
  private volatile static int reconnectAttemptCounter = 0;

  /**
   * The time at which reconnect attempts last began
   */
  private static long reconnectAttemptTime;

  /**
   * Boolean indicating if DS needs to reconnect and reconnect
   * is in progress.
   * */
  private volatile boolean attemptingToReconnect = false;

  /**
   * Boolean indicating this DS joined through a reconnect attempt
   */
  private volatile boolean reconnected = false;
  
  /**
   * Boolean indicating that this member has been shunned by other members
   * or a network partition has occurred
   */
  private volatile boolean forcedDisconnect = false;

  /**
   * Used to keep track of the DS created by doing an reconnect on this.
   */
  private volatile InternalDistributedSystem reconnectDS;
  /**
   * Was this distributed system started with FORCE_LOCATOR_DM_TYPE=true?
   * We need to know when reconnecting.
   */
  private boolean locatorDMTypeForced;


  /**
   * Returns true if we are reconnecting the distributed system or
   * reconnect has completed.  If this returns true it means that
   * this instance of the DS is now disconnected and unusable.
   */
  public boolean isReconnecting(){
    return attemptingToReconnect || (reconnectDS != null);
  }

  /**
   * Returns true if we are reconnecting the distributed system
   * and this instance was created for one of the connection
   * attempts.  If the connection succeeds this state is cleared
   * and this method will commence to return false.
   */
  public boolean isReconnectingDS() {
    return this.isReconnectingDS;
  }
  
  /**
   * returns the membership socket of the old
   * distributed system, if available, when
   * isReconnectingDS returns true.  This is
   * used to connect the new DM to the distributed
   * system through RemoteTransportConfig.
   */
  public Object oldDSMembershipInfo() {
    if (this.quorumChecker != null) {
      return this.quorumChecker.getMembershipInfo();
    }
    return null;
  }
  
  /**
   * Returns true if this DS reconnected to the distributed system after
   * a forced disconnect or loss of required-roles
   */
  public boolean reconnected() {
    return this.reconnected;
  }
  
  /**
   * Returns true if this DS has been kicked out of the distributed system
   */
  public boolean forcedDisconnect() {
    return this.forcedDisconnect;
  }

  /**
   * If true then this DS will never reconnect.
   */
  private boolean reconnectCancelled = false;
  private Object reconnectCancelledLock = new Object();

  /** Make sure this instance of DS never does a reconnect.
   * Also if reconnect is in progress cancel it.
   */
  public void cancelReconnect() {
//    (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("cancelReconnect invoked", new Exception("stack trace"));
    synchronized(this.reconnectCancelledLock) {
      this.reconnectCancelled = true;
    }
    if (isReconnecting()) {
      synchronized (this.reconnectLock) { // should the synchronized be first on this and
    	  // then on this.reconnectLock.
        this.reconnectLock.notifyAll();
      }
    }
  }

  /**
   * This lock must be acquired *after* locking any GemFireCache.
   */
  private final Object reconnectLock = new Object();

  /**
   * Tries to reconnect to the distributed system on role loss
   * if configure to reconnect.
   *
   * @param oldCache cache that has apparently failed
   *
   */
  public boolean tryReconnect(boolean forcedDisconnect, String reason, GemFireCacheImpl oldCache) {
    synchronized (CacheFactory.class) { // bug #51355 - deadlock with an app thread creating the DS 
      synchronized (GemFireCacheImpl.class) {
        // bug 39329: must lock reconnectLock *after* the cache
        synchronized (reconnectLock) {
          if (!forcedDisconnect &&
              !oldCache.isClosed() &&
              oldCache.getCachePerfStats().getReliableRegionsMissing() == 0) {
            if (logger.fineEnabled()) logger.fine("tryReconnect: No required roles are missing.");
            return false;
          }

          if (logger.fineEnabled()) logger.fine("tryReconnect: forcedDisconnect="+forcedDisconnect+" gfxd listener="+this.gfxdDisconnectListener);
          if (forcedDisconnect) {
            // allow the fabric-service to stop before dismantling everything
            notifyGfxdForcedDisconnectListener();

            if (this.config.getDisableAutoReconnect()) {
              if (logger.fineEnabled()) logger.fine("tryReconnect: auto reconnect after forced disconnect is disabled");
              return false;
            }
          }
          reconnect(forcedDisconnect, reason);
          return this.reconnectDS != null && this.reconnectDS.isConnected();
        } // synchronized reconnectLock
      } // synchronized cache
    } // synchronized CacheFactory.class
  }


  /**
   * Returns the value for the number of time reconnect has been tried.
   * Test method used by DUnit.
   * */
  public static int getReconnectCount(){
    return reconnectAttemptCounter;
  }

  /**
   * A reconnect is tried when gemfire is configured to reconnect in
   * case of a required role loss. The reconnect will try reconnecting
   * to the distributed system every max-time-out millseconds for
   * max-number-of-tries configured in gemfire.properties file. It uses
   * the cache.xml file to intialize the cache and create regions.
   *
   * */
  private void reconnect(boolean forcedDisconnect, String reason) {

    // Collect all the state for cache
    // Collect all the state for Regions
    //Close the cache,
    // loop trying to connect, waiting before each attempt
    //
    // If reconnecting for lost-roles the reconnected system's cache will decide
    // whether the reconnected system should stay up.  After max-tries we will
    // give up.
    //
    // If reconnecting for forced-disconnect we ignore max-tries and keep attempting
    // to join the distributed system until successful

    this.attemptingToReconnect = true;
    InternalDistributedSystem ids = InternalDistributedSystem.getAnyInstance();
    if (ids == null) {
      ids = this;
    }

    // first save the current cache description.  This is created by
    // the membership manager when forced-disconnect starts.  If we're
    // reconnecting for lost roles then this will be null
    String cacheXML = null;
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    boolean inhibitCacheForGemFireXD = false;
    Properties gfxdSecurityProperties = null;
    if (cache != null) {
      if (cache.isGFXDSystem()) {
        inhibitCacheForGemFireXD = true;
        gfxdSecurityProperties = GemFireCacheImpl.getInternalProductCallbacks()
            .getSecurityPropertiesForReconnect();
      } else {
        cacheXML = cache.getCacheConfig().getCacheXMLDescription();
      }
    }

    LogWriterI18n log = getLoggerI18n();
    int logLevel = LogWriterImpl.INFO_LEVEL;
    if (log != null) {
      logLevel = ((LogWriterImpl)log).getLevel();
    }
    if (log == null || ((LogWriterImpl)log).isClosed()) {
      log = new ManagerLogWriter(logLevel, System.out);
    }

    DistributionConfig oldConfig = ids.getConfig();
    Properties configProps = getProperties();
    if (gfxdSecurityProperties != null) {
      configProps.putAll(gfxdSecurityProperties);
    }

    int timeOut = oldConfig.getMaxWaitTimeForReconnect();
    int maxTries = oldConfig.getMaxNumReconnectTries();
    
    boolean mcastDiscovery = oldConfig.getLocators().isEmpty()
        && oldConfig.getStartLocator().isEmpty()
        && oldConfig.getMcastPort() != 0;
    boolean mcastQuorumContacted = false;
    

    if (Thread.currentThread().getName().equals("CloserThread")) {
      if (log.fineEnabled()) {
        log.fine("changing thread name to ReconnectThread");
      }
      Thread.currentThread().setName("ReconnectThread");
    }
    
    // get the membership manager for quorum checks
    MembershipManager mbrMgr = this.dm.getMembershipManager();
    this.quorumChecker = mbrMgr.getQuorumChecker();
    if (log.fineEnabled()) {
      if (quorumChecker == null) {
        log.fine("no quorum checks will be performed during reconnect attempts");
      } else {
        log.fine("initialized quorum checking service: " + quorumChecker);
      }
    }
    
    if (log.fineEnabled()) {
      log.fine("reconnecting distributed system.  reconnect="+attemptingToReconnect+"; reconnectCancelled="+reconnectCancelled);
    }
    String appendToLogFile = System.getProperty(APPEND_TO_LOG_FILE);
    if (appendToLogFile == null) {
      System.setProperty(APPEND_TO_LOG_FILE, "true");
    }
    String inhibitBanner = System.getProperty(InternalLocator.INHIBIT_DM_BANNER);
    if (inhibitBanner == null) {
      System.setProperty(InternalLocator.INHIBIT_DM_BANNER, "true");
    }
    if (forcedDisconnect) {
      systemAttemptingReconnect = this;
    }
    try {
      while (this.reconnectDS == null || !this.reconnectDS.isConnected()) {
        synchronized(this.reconnectCancelledLock) {
          if (this.reconnectCancelled) {
            break;
          }
        }
        if (!forcedDisconnect) {
          if (log.fineEnabled()) {
            log.fine("Max number of tries : "+maxTries+" and max time out : "+timeOut);
          }
          if(reconnectAttemptCounter >= maxTries){
            if (log.fineEnabled()) {
              log.fine("Stopping the checkrequiredrole thread becuase reconnect : "+ reconnectAttemptCounter +" reached the max number of reconnect tries : "+maxTries);
            }
            reconnectAttemptCounter = 0;
            throw new CacheClosedException(LocalizedStrings.InternalDistributedSystem_SOME_REQUIRED_ROLES_MISSING.toLocalizedString());
          }
        }

        if (reconnectAttemptCounter == 0) {
          reconnectAttemptTime = System.currentTimeMillis();
        }
        reconnectAttemptCounter++;

        synchronized(this.reconnectCancelledLock) { 
          if (this.reconnectCancelled) {
            if (log.fineEnabled()) {
              log.fine("reconnect can no longer be done because of an explicit disconnect");
            }
            return;
          }
        }

        logger.info(LocalizedStrings.DISTRIBUTED_SYSTEM_RECONNECTING,
            new Object[]{reconnectAttemptCounter});
        try {
          disconnect(true, reason, false);
          // the log file is now closed - write to stdout
          log = new ManagerLogWriter(logLevel, System.out);
        }
        catch(Exception ee){
          log.convertToLogWriter().warning("Exception disconnecting for reconnect", ee);
        }

        try {
  //        log.fine("waiting " + timeOut + " before reconnecting to the distributed system");
          reconnectLock.wait(timeOut);
        }
        catch (InterruptedException e) {
          log.warning(LocalizedStrings.InternalDistributedSystem_WAITING_THREAD_FOR_RECONNECT_GOT_INTERRUPTED);
          Thread.currentThread().interrupt();
          return;
        }
        synchronized(this.reconnectCancelledLock) { 
          if (this.reconnectCancelled) {
            if (log.fineEnabled()) {
              log.fine("reconnect can no longer be done because of an explicit disconnect");
            }
            return;
          }
        }

        log.info(LocalizedStrings.DISTRIBUTED_SYSTEM_RECONNECTING,
            new Object[] { reconnectAttemptCounter });

        int savNumOfTries = reconnectAttemptCounter;
        try {
          // notify listeners of each attempt and then again after successful
          notifyReconnectListeners(this, this.reconnectDS, true, log);
          if (this.locatorDMTypeForced) {
            System.setProperty(InternalLocator.FORCE_LOCATOR_DM_TYPE, "true");
          }
  //        log.fine("DistributedSystem@"+System.identityHashCode(this)+" reconnecting distributed system.  attempt #"+reconnectAttemptCounter);
          if (mcastDiscovery  &&  (quorumChecker != null) && !mcastQuorumContacted) {
            mcastQuorumContacted = quorumChecker.checkForQuorum(3*this.config.getMemberTimeout(),
                log.convertToLogWriter());
            if (!mcastQuorumContacted) {
              if (log.fineEnabled()) {
                log.fine("quorum check failed - skipping reconnect attempt");
              }
              continue;
            }
            if (log.infoEnabled()) {
              log.info(LocalizedStrings.InternalDistributedSystem_QUORUM_OF_MEMBERS_CONTACTED);
            }
            mcastQuorumContacted = true;
            // bug #51527: become more aggressive about reconnecting since there are other 
            // members around now
            if (timeOut > 5000) {
              timeOut = 5000;
            }
          }
          configProps.put(DistributionConfig.DS_RECONNECTING_NAME, Boolean.TRUE);
          if (quorumChecker != null) {
            configProps.put(DistributionConfig.DS_QUORUM_CHECKER_NAME, quorumChecker);
          }
          InternalDistributedSystem newDS = null;
          synchronized(this.reconnectCancelledLock) { 
            if (this.reconnectCancelled) {
              if (log.fineEnabled()) {
                log.fine("reconnect can no longer be done because of an explicit disconnect");
              }
              return;
            }
          }
          try {
            newDS = (InternalDistributedSystem)connect(configProps);
          } catch (DistributedSystemDisconnectedException e) {
            synchronized (this.reconnectCancelledLock) {
              if (this.reconnectCancelled) {
                return;
              } else {
                throw e;
              }
            }
          } finally {
            if (newDS == null  &&  quorumChecker != null) {
              // make sure the quorum checker is listening for messages from former members
              quorumChecker.resume();
            }
          }
          if (newDS != null) { // newDS will not be null here but findbugs requires this check
            boolean cancelled;
            synchronized(this.reconnectCancelledLock) { 
              cancelled = this.reconnectCancelled;
            }
            if (cancelled) {
              newDS.disconnect();
            } else {
              this.reconnectDS = newDS;
              newDS.isReconnectingDS = false;
              notifyReconnectListeners(this, this.reconnectDS, false, log);
            }
          }
        }
        catch (SystemConnectException e) {
          // retry;
          if (log.fineEnabled()) {
            log.fine("Attempt to reconnect failed with SystemConnectException");
          }
          if (e.getMessage().contains("Rejecting the attempt of a member using an older version")
              || e.getMessage().contains("15806")) { // 15806 is in the message if it's been localized to another language
            log.warning(LocalizedStrings.InternalDistributedSystem_EXCEPTION_OCCURED_WHILE_TRYING_TO_CONNECT_THE_SYSTEM_DURING_RECONNECT, e);
            attemptingToReconnect = false;
            return;
          }
        }
        catch (GemFireConfigException e) {
          if (log.fineEnabled()) {
            log.fine("Attempt to reconnect failed with GemFireConfigException");
          }
        }
        catch (Exception ee) {
          log.warning(LocalizedStrings.InternalDistributedSystem_EXCEPTION_OCCURED_WHILE_TRYING_TO_CONNECT_THE_SYSTEM_DURING_RECONNECT, ee);
          attemptingToReconnect = false;
          return;
        }
        finally {
          if (this.locatorDMTypeForced) {
            System.getProperties().remove(InternalLocator.FORCE_LOCATOR_DM_TYPE);
          }
          reconnectAttemptCounter = savNumOfTries;
        }
      } // while()
    } finally {
      systemAttemptingReconnect = null;
      if (appendToLogFile == null) {
        System.getProperties().remove(APPEND_TO_LOG_FILE);
      } else {
        System.setProperty(APPEND_TO_LOG_FILE,  appendToLogFile);
      }
      if (inhibitBanner == null) {
        System.getProperties().remove(InternalLocator.INHIBIT_DM_BANNER);
      } else {
        System.setProperty(InternalLocator.INHIBIT_DM_BANNER, inhibitBanner);
      }
      if (quorumChecker != null) {
        mbrMgr.releaseQuorumChecker(quorumChecker);
      }
    }

    boolean cancelled;
    synchronized(this.reconnectCancelledLock) { 
      cancelled = this.reconnectCancelled;
    }
    if (cancelled) {
      if (log.fineEnabled()) {
        log.fine("reconnect can no longer be done because of an explicit disconnect");
      }
      if (reconnectDS != null) {
        reconnectDS.disconnect();
      }
      attemptingToReconnect = false;
      return;
    }

    try {
      DM newDM = this.reconnectDS.getDistributionManager();
      if ( !inhibitCacheForGemFireXD && (newDM instanceof DistributionManager) ) {
        // gemfirexd will have already replayed DDL and recovered.
        // Admin systems don't carry a cache, but for others we can now create
        // a cache
        if (((DistributionManager)newDM).getDMType() != DistributionManager.ADMIN_ONLY_DM_TYPE) {
          try {
            CacheConfig config = new CacheConfig();
            if (cacheXML != null) {
              config.setCacheXMLDescription(cacheXML);
            }
            cache = GemFireCacheImpl.create(this.reconnectDS, config);
            if (cache.getCachePerfStats().getReliableRegionsMissing() == 0){
              reconnectAttemptCounter = 0;
              cache.getLogger().fine("Reconnected properly");
            }
            else {
              // this try failed. The new cache will call reconnect again
            }
          }
          catch (CancelException ignor) {
              //getLogWriter().warning("Exception occured while trying to create the cache during reconnect : "+ignor.toString());
              throw ignor;
              // this.reconnectDS.reconnect();
          }
          catch (Exception e) {
            log.warning(LocalizedStrings.InternalDistributedSystem_EXCEPTION_OCCURED_WHILE_TRYING_TO_CREATE_THE_CACHE_DURING_RECONNECT, e);
          }
        }
      }
    } finally {
      attemptingToReconnect = false;
    }
  }

  /**
   * Validates that the configuration provided is the same as the configuration for this
   * InternalDistributedSystem
   * @param propsToCheck the Properties instance to compare with the existing Properties
   * @throws java.lang.IllegalStateException when the configuration is not the same other returns
   */
  public void validateSameProperties(Properties propsToCheck)
  {
    if (!this.sameAs(propsToCheck)) {
      StringBuilder sb = new StringBuilder();

      DistributionConfig wanted = DistributionConfigImpl.produce(propsToCheck);

      String[] validAttributeNames = this.originalConfig.getAttributeNames();
      for (int i = 0; i < validAttributeNames.length; i++) {
        String attName = validAttributeNames[i];
        Object expectedAtt = wanted.getAttributeObject(attName);
        String expectedAttStr = expectedAtt.toString();
        Object actualAtt = this.originalConfig.getAttributeObject(attName);
        String actualAttStr = actualAtt.toString();
        sb.append("  ");
        sb.append(attName);
        sb.append("=\"");
        if (actualAtt.getClass().isArray()) {
          actualAttStr = arrayToString(actualAtt);
          expectedAttStr = arrayToString(expectedAtt);
        }

        sb.append(actualAttStr);
        sb.append("\"");
        if (!expectedAttStr.equals(actualAttStr)) {
          sb.append(" ***(wanted \"");
          sb.append(expectedAtt);
          sb.append("\")***");
        }

        sb.append("\n");
      }

      if (this.creationStack == null) {
        throw new IllegalStateException(LocalizedStrings.InternalDistributedSystem_A_CONNECTION_TO_A_DISTRIBUTED_SYSTEM_ALREADY_EXISTS_IN_THIS_VM_IT_HAS_THE_FOLLOWING_CONFIGURATION_0
            .toLocalizedString(sb.toString()));
      } else {
        throw new IllegalStateException(LocalizedStrings.InternalDistributedSystem_A_CONNECTION_TO_A_DISTRIBUTED_SYSTEM_ALREADY_EXISTS_IN_THIS_VM_IT_HAS_THE_FOLLOWING_CONFIGURATION_0
            .toLocalizedString(sb.toString()), this.creationStack);
      }
    }
  }

  private Throwable generateCreationStack() {
    if (!ENABLE_CREATION_STACK) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    String[] validAttributeNames = this.originalConfig.getAttributeNames();
    for (int i = 0; i < validAttributeNames.length; i++) {
      String attName = validAttributeNames[i];
      Object actualAtt = this.originalConfig.getAttributeObject(attName);
      String actualAttStr = actualAtt.toString();
      sb.append("  ");
      sb.append(attName);
      sb.append("=\"");
      if (actualAtt.getClass().isArray()) {
        actualAttStr = arrayToString(actualAtt);
      }
      sb.append(actualAttStr);
      sb.append("\"");
      sb.append("\n");
    }
    return new Throwable("Creating distributed system with the following configuration:\n" + sb.toString());
  }
  
  private String arrayToString(Object obj) {
    if (!obj.getClass().isArray()) {
        return "-not-array-object-";
    }
    StringBuilder buff = new StringBuilder("[");
    int arrayLength = Array.getLength(obj);
    for (int i = 0; i < arrayLength - 1; i++ ) {
        buff.append(Array.get(obj, i).toString());
        buff.append(",");
    }
    buff.append(Array.get(obj, arrayLength - 1).toString());
    buff.append("]");

    return buff.toString();
  }

  public final boolean isShareSockets() {
    return shareSockets;
  }

  public final void setShareSockets(boolean shareSockets) {
    this.shareSockets = shareSockets;
  }

  /**
   * A listener that gets invoked whenever a connection is created to
   * a distributed system
   */
  public interface ConnectListener {

    /**
     * Invoked after a connection to the distributed system is created
     */
    public void onConnect(InternalDistributedSystem sys);
  }

  /**
   * Used by GemFireXD to specify whether the current VM is a GemFireXD peer
   * accessor, a GemFireXD locator, a GemFireXD administrator, or a datastore.
   *
   * A member can be only one of {@link MemberKind#isAccessor()},
   * {@link MemberKind#isLocator()}, {@link MemberKind#isStore()}.
   *
   * @author swale
   * @since 7.0
   */
  public interface MemberKind {

    /**
     * Returns true if the member is an GFXD peer client that will never host
     * any table data.
     */
    public boolean isAccessor();

    /**
     * Returns true if the member is an GFXD locator that joins as a normal but
     * will never host any table data, or user table meta-data.
     */
    public boolean isLocator();

    /**
     * Returns true if the member is a normal GFXD datastore node that can host
     * table data and service network clients.
     */
    public boolean isStore();

    /**
     * Returns true if the member is a GemFireXD administrator
     */
    public boolean isAdmin();
    /**
     * Returns true if the member is started as GFXD jmx agent.
     */
    public boolean isAgent();
  }

  public String forceStop() {
    if (this.dm == null) {
      return LocalizedStrings.InternalDistributedSystem_NO_DISTRIBUTION_MANAGER.toLocalizedString();
    }
    String reason = dm.getCancelCriterion().cancelInProgress();
    return reason;
  }

  public boolean hasAlertListenerFor(DistributedMember member) {
    return hasAlertListenerFor(member, AlertLevel.WARNING.getSeverity());
  }


  public boolean hasAlertListenerFor(DistributedMember member, int severity) {
    if (this.logger instanceof ManagerLogWriter) {
      ManagerLogWriter mlw = (ManagerLogWriter) this.logger;
      return mlw.hasAlertListenerFor(member, severity);
    }
    return false;
  }

  /**
   * see {@link com.gemstone.gemfire.admin.AdminDistributedSystemFactory}
   * @since 5.7
   */
  public static void setEnableAdministrationOnly(boolean adminOnly) {
    DistributedSystem.setEnableAdministrationOnly(adminOnly);
  }

  public static void setCommandLineAdmin(boolean adminOnly) {
    DistributedSystem.setEnableAdministrationOnly(adminOnly);
    DistributionManager.isCommandLineAdminVM = adminOnly;
  }

  public boolean isServerLocator(){
    return this.startedLocator.isServerLocator();
  }

  public static void setHadoopGfxdLonerMode(boolean hadoopLonerMode) {
    isHadoopGfxdLonerMode = hadoopLonerMode;
  }

  public static boolean isHadoopGfxdLonerMode() {
    return isHadoopGfxdLonerMode;
  }

  /**
   * Provides synchronized time for this process based on other processes in
   * this GemFire distributed system. GemFire distributed system coordinator
   * adjusts each member's time by an offset. This offset for each member is
   * calculated based on Berkeley Time Synchronization algorithm.
   *
   * @return time in milliseconds.
   */
  public long systemTimeMillis() {
    return dm.cacheTimeMillis();
  }

  @Override
  public boolean waitUntilReconnected(long time, TimeUnit units)
      throws InterruptedException {
    int sleepTime = 1000;
    long endTime = System.currentTimeMillis();
    if (time < 0) {
      endTime = Long.MAX_VALUE;
    } else {
      endTime += TimeUnit.MILLISECONDS.convert(time, units);
    }
    synchronized(this.reconnectLock) {
      InternalDistributedSystem recon = this.reconnectDS;
//      (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("IDS.waitUntilReconnected: reconnectCancelled = "+reconnectCancelled
//          +"; reconnectDS="+reconnectDS);


      while (attemptingToReconnect && (recon == null || !recon.isConnected())) {
        synchronized(this.reconnectCancelledLock) {
          if (this.reconnectCancelled) {
            break;
          }
        }
        if (time != 0) {
          this.reconnectLock.wait(sleepTime);
        }
        if (recon == null) {
          recon = this.reconnectDS;
        }
        if (time == 0  ||  System.currentTimeMillis() > endTime) {
//          (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("IDS.waitUntilReconnected timed out");
          break;
        }
      }
//      (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("IDS.waitUntilReconnected finished & returning: attemptingToReconnect="
//                +attemptingToReconnect+"; reconnectDS=" + recon);
      return !attemptingToReconnect  &&  recon != null  &&  recon.isConnected();
    }
  }

  @Override
  public DistributedSystem getReconnectedSystem() {
    return this.reconnectDS;
  }

  @Override
  public void stopReconnecting() {
//    (new ManagerLogWriter(LogWriterImpl.FINE_LEVEL, System.out)).fine("stopReconnecting invoked", new Exception("stack trace"));
    synchronized(this.reconnectCancelledLock) {
      this.reconnectCancelled = true;
    }
    synchronized(this.reconnectLock) {
      this.reconnectLock.notify();
    }
    disconnect(false, "stopReconnecting was invoked", false);
    this.attemptingToReconnect = false;
  }
}
