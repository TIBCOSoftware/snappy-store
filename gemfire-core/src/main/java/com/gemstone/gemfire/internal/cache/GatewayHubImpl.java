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

package com.gemstone.gemfire.internal.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.GatewayException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.cache.util.Gateway;
import com.gemstone.gemfire.cache.util.GatewayHub;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.GatewayCancelledException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.locks.DLockService;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.cache.tier.Acceptor;
import com.gemstone.gemfire.internal.cache.tier.sockets.AcceptorImpl;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventCallbackArgument;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock.StoppableWriteLock;
import com.gemstone.gemfire.pdx.internal.PeerTypeRegistration;

/**
 * Class <code>GatewayHubImpl</code> is an implementation of the
 * <code>GatewayHub</code> interface that manages a collection of
 * <code>Gateway</code>s. It listens for connections from other
 * <code>GatewayHub</code> s using an {@link Acceptor}.
 *
 * @author Barry Oglesby
 * @since 4.2
 *
 * @see GatewayImpl
 */
public class GatewayHubImpl implements GatewayHub, MembershipListener
{

  /** Synchronizes lifecycle state including start, stop, and _isRunning */
  private final Object controlLock = new Object();

  private class Stopper extends CancelCriterion {
    final CancelCriterion stopper;

    Stopper(CancelCriterion stopper) {
      this.stopper = stopper;
    }

    @Override
    public String cancelInProgress() {
      // checkFailure(); // done by stopper
      String oops = stopper.cancelInProgress();
      if (oops != null) {
        return oops;
      }
      if (GatewayHubImpl.this._isRunning) {
        return null;
      }
      return LocalizedStrings.GatewayHubImpl_HAS_BEEN_STOPPED.toLocalizedString()  ;
    }
    
    @Override
    public RuntimeException generateCancelledException(Throwable e) {
      RuntimeException result = stopper.generateCancelledException(e);
      if (result != null) {
        return result;
      }
      if (GatewayHubImpl.this._isRunning) {
        return null;
      }
      return new GatewayCancelledException(LocalizedStrings.GatewayHubImpl_HAS_BEEN_STOPPED.toLocalizedString(), e);
    }
  }
  protected final Stopper stopper;
  
  public CancelCriterion getCancelCriterion() {
    return stopper;
  }

  /** Protects lifecycle from colliding with distribution of data */
  private final StoppableReentrantReadWriteLock distributionRWLock;

  /**
   * The GemFire cache that is served by this <code>GatewayHub</code>
   */
  private final GemFireCacheImpl _cache;

  /**
   * The GemFire log writer
   */
  protected final LogWriterI18n _logger;

  /**
   * Whether the <code>Gateway</code> is running. Back to a volatile so it can
   * be sampled while someone else is holding the controlLock. This is needed so
   * it can be tested while a stop is in progress.
   */
  protected volatile boolean _isRunning;

  /**
   * The acceptor that does the actual serving
   */
  private AcceptorImpl _acceptor;

  /**
   * The port on which this <code>GatewayHub</code> listens for clients to
   * connect.
   */
  private int _port;

  /**
   * The identifier of this <code>GatewayHub</code>.
   */
  private volatile String _id;

  /**
   * The buffer size in bytes of the socket connection for this
   * <code>GatewayHub</code>
   */
  private int _socketBufferSize;

  /**
   * The maximum amount of time between client pings. This value is used by the
   * <code>ClientHealthMonitor</code> to determine the health of this
   * <code>BridgeServer</code>'s clients.
   */
  private int _maximumTimeBetweenPings;

  /**
   * Synchronizes updates to the {@link #allGateways} field
   */
  protected final Object allGatewaysLock = new Object();

  /**
   * A list of this <code>GatewayHub</code>'s known <code>Gateway</code>s.
   */
  protected volatile AbstractGateway allGateways[] = new AbstractGateway[0];

  /**
   * A list of the ids of this <code>GatewayHub</code>'s known
   * <code>Gateway</code>s.
   */
  private final List _gatewayIds = new CopyOnWriteArrayList();

  /**
   * An unmodifiable list of the ids of this <code>GatewayHub</code>'s known
   * <code>Gateway</code>s.
   */
  private final List _readOnlyGatewayIds = Collections.unmodifiableList(this._gatewayIds);

  /**
   * The <code>LinkedQueue</code> used by the <code>QueuedExecutor</code>.
   * This is stored in an instance variable so that its size can be monitored.
   */
  private final LinkedBlockingQueue _executorQueue = new LinkedBlockingQueue();
  
  
  /**
   * Boolean which indicates if this Hub should ever become a primary.
   * This is use by Sql Fabric to ensure that Hubs which are of type client 
   * never become primary as they will not be having GatewayQueues for dispatch 
   */
  final private boolean capableOfBecomingPrimary ; 

  /**
   * This boolean indicates that the hub is sql fabric hub 
   * with DBSynchronizer attached to it
   */
  //private volatile boolean isDBSynchronizerHub = false;
  
  /** 
   * byte indicating order of hub type
   */
  final private int hubType ;
  public static final int NO_HUB = 0;
//If a region is not associated with any  gfxd created hub it will be 1
  public static final int NON_GFXD_HUB = 0X01<<24;
  // if a region is associated with WAN_HUB only it would be 2
  public static final int GFXD_WAN_HUB = 0X02<<24;
//if a region is associated with any AsyncEventListenerHub then it would be 4. This means
  // that region may or may not have a WAN type hub
  public static final int GFXD_ASYNC_EVENT_HUB = 0x03<<24;
  //if a region is associated with any DBynchronizer type hub then it would be 8.
  public static final int GFXD_ASYNC_DBSYNCH_HUB = 0x04<<24;
  /**
   * The <code>QueuedExecutor</code> that is between the local
   * <code>DistributedSystem</code> and the WAN processing of the event. This
   * <code>QueuedExecutor</code> exists so that the local
   * <code>DistributedSystem</code> is not much affected by the WAN
   * processing.
   */
  private final ThreadPoolExecutor _executor;


  /**
   * The <code>DistributedLockService</code> for used for determining primary /
   * secondary roles for failover purposes.
   */
  protected DistributedLockService _lockService;

  /**
   * The token used by the <code>DistributedLockService</code>
   */
  protected final String _lockToken;

  /**
   * Whether this is the primary <code>GatewayHub</code>
   */
  protected volatile boolean _primary;

  /**
   * A <code>Thread</code> that is used to obtain the primary / secondary
   * lock. This <code>Thread</code> is only launched if this
   * <code>GatewayHub</code> is not the primary.
   */
  private Thread _lockObtainingThread;

  /**
   * The <code>GatewayHubStats</code> used by this <code>GatewayHub</code>.
   */
  private GatewayHubStats _statistics;

  /**
   * The startup policy for this <code>GatewayHub</code>. The options are:
   * <ul>
   * <li>none</li>
   * <li>primary</li>
   * <li>secondary</li>
   * </ul>
   */
  private String _startupPolicy;

  /**
   * Whether to manually start this <code>GatewayHub</code>
   */
  private boolean _manualStart;

  /**
   * The ip address or host name that this <code>GatewayHub</code> will listen on.
   * @since 6.5.1
   */
  private String _bindAddress;

  /**
   * A boolean that defines whether the <code>GatewayHub</code> should
   * asynchronously distribute events it receives to its <code>Gateway</code>
   * s. This boolean is false by default (meaning the <code>GatewayHub</code>
   * synchronously distributes events it receives to its <code>Gateway</code>
   * s). Distribution from a <code>Gateway</code> to its remove
   * <code>Gateway</code> is always asynchronous. This boolean only decouples
   * the local distributed system operations from the <code>GatewayHub</code>
   * operations.
   * 
   * The <code>GatewayHub</code> uses a <code>QueuedExecutor</code> to
   * decouple event distribution from the activites of the
   * <code>DistributedSystem</code>. To asynchronously distribute events
   * received to <code>Gateway</code>s, the
   * <code>gemfire.useAsynchronousDistribution</code> java system property can
   * be set. Setting this boolean to true means that operations in the local
   * <code>DistributedSystem</code> will not be negatively affected
   * performance-wise by adding a <code>GatewayHub</code>. The disadvantage
   * is that setting this boolean to true might result in data loss if this VM
   * fails and the queue contains any data.
   */
  private static final boolean USE_ASYNCHRONOUS_DISTRIBUTION = Boolean
      .getBoolean("gemfire.asynchronous-gateway-distribution-enabled");


  /**
   * A boolean that defines whether the value of the conserve-sockets property
   * in the gemfire.properties file is preserved. By default, the value in
   * gemfire.properties is overridden so that queue ordering is preserved. If
   * ALLOW_CONSERVE_SOCKETS=true, then the conserve-sockets property is
   * used and queue ordering may not be preserved. This property can be set
   * using the System property called 'gemfire.gateway-conserve-sockets-allowed'.
   */
  protected static final boolean ALLOW_CONSERVE_SOCKETS =
    Boolean.getBoolean("gemfire.gateway-conserve-sockets-allowed");

  //////////////////////// Constructors //////////////////////

  /**
   * Constructor. Creates a new <code>GatewayHub</code> that serves the
   * contents of the give <code>Cache</code>. It has the default
   * configuration.
   *
   * @param cache
   *          The GemFire cache
   */
  public GatewayHubImpl(GemFireCacheImpl cache, String id, int port,
      boolean isCapableOfBecomingPrimary) {
    this._cache = cache;
    this.stopper = new Stopper(cache.getCancelCriterion());
    this.distributionRWLock = new StoppableReentrantReadWriteLock(stopper);
    this._logger = cache.getLoggerI18n();
    this._id = id;
    this._port = port;
    this._lockToken = getDistributedLockServiceName() + "-token";
    this._statistics = new GatewayHubStats(cache.getDistributedSystem(), id);
    this._socketBufferSize = DEFAULT_SOCKET_BUFFER_SIZE;
    this._maximumTimeBetweenPings = DEFAULT_MAXIMUM_TIME_BETWEEN_PINGS;
    this._startupPolicy = DEFAULT_STARTUP_POLICY;
    this._manualStart = DEFAULT_MANUAL_START;
    // TODO: merge: get rid of hubType; better get rid of all Hub related
    // classes/calls in trunk itself and merge from there
    this.hubType = NON_GFXD_HUB;
    this.capableOfBecomingPrimary = isCapableOfBecomingPrimary;
    this._bindAddress = DEFAULT_BIND_ADDRESS;
    {
      final ThreadGroup loggerGroup = LogWriterImpl.createThreadGroup(
          "Gateway Hub Logger Group", this._logger);

      // Create the Executor
      ThreadFactory tf = new ThreadFactory() {
        public Thread newThread(Runnable command) {
          Thread thread = new Thread(loggerGroup, command,
              "Queued Gateway Hub Thread");
          thread.setDaemon(true);
          return thread;
        }
      };
      this._executor = new ThreadPoolExecutor(1, 1/*max unused*/,
          120, TimeUnit.SECONDS,
          this._executorQueue, tf);
    }
  }

  ////////////////////// Instance Methods ///////////////////

  /**
   * Checks to see whether or not this <code>GatewayHub</code> is running. If
   * so, an {@link IllegalStateException}is thrown.
   */
  private void checkRunning()
  {
    if (stopper.cancelInProgress() == null) {
      throw new IllegalStateException(LocalizedStrings.GatewayHubImpl_A_BRIDGE_SERVERS_CONFIGURATION_CANNOT_BE_CHANGED_ONCE_IT_IS_RUNNING.toLocalizedString());
    }
  }

  public int getPort() {
    synchronized (this.controlLock) {
      return basicGetPort();
    }
  }

  private int basicGetPort() {
    if (this._acceptor != null) {
      return this._acceptor.getPort();

    } else {
      return this._port;
    }
  }

  public void setPort(int port) {
    synchronized (this.controlLock) {
      checkRunning();
      this._port = port;
    }
  }

  public String getId() {
    // Changed to volatile with no synchronization so that
    // GatewayImpl.GatewayEventDispatcher.toString doesn't
    // get into a deadlock situation.
    return this._id;
  }

  public void setId(String id) {
    synchronized (this.controlLock) {
      checkRunning();
      this._id = id;
    }
  }

  public String getBindAddress() {
    return this._bindAddress;
  }

  public void setBindAddress(String address) {
    synchronized (this.controlLock) {
      checkRunning();
      this._bindAddress = address;
    }
  }

  public void setSocketBufferSize(int socketBufferSize) {
    synchronized (this.controlLock) {
      this._socketBufferSize = socketBufferSize;
    }
  }

  public int getSocketBufferSize() {
    synchronized (this.controlLock) {
      return this._socketBufferSize;
    }
  }

  public void setMaximumTimeBetweenPings(int maximumTimeBetweenPings) {
    synchronized (this.controlLock) {
      this._maximumTimeBetweenPings = maximumTimeBetweenPings;
    }
  }

  public int getMaximumTimeBetweenPings() {
    synchronized (this.controlLock) {
      return this._maximumTimeBetweenPings;
    }
  }

  public Gateway addGateway(String id) throws GatewayException {
    return addGateway(id, Gateway.DEFAULT_CONCURRENCY_LEVEL);
  }
  
  public Gateway addGateway(String id, int concurrencyLevel)
      throws GatewayException {
    synchronized (this.controlLock) {
      checkRunning();

      AbstractGateway gateway;
      synchronized (allGatewaysLock) {
        // If a gateway with the id is already defined, throw an exception
        if (alreadyDefinesGateway(id)) {
          throw new GatewayException(LocalizedStrings.GatewayHubImpl_GATEWAYHUB_0_ALREADY_DEFINES_A_GATEWAY_WITH_ID_1.toLocalizedString(new Object[] {this._id, id}));
        }
        
        if (concurrencyLevel < Gateway.DEFAULT_CONCURRENCY_LEVEL) {
          throw new GatewayException(
              LocalizedStrings.Gateway_INVALID_CONCURRENCY_LEVEL
                  .toLocalizedString(new Object[] { id, concurrencyLevel }));
        }

        gateway = concurrencyLevel > Gateway.DEFAULT_CONCURRENCY_LEVEL
            ? new GatewayParallelImpl(this, id, concurrencyLevel) : new GatewayImpl(this, id);
        AbstractGateway snap[] = allGateways; // volatile fetch
        this.allGateways = (AbstractGateway[]) ArrayUtils.insert(snap, snap.length,
            gateway);
        this._gatewayIds.add(id);
      }
      this._statistics.incNumberOfGateways();
      return gateway;
    }
  }

  public void removeGateway(String id) throws GatewayException {
    synchronized (this.controlLock) {
      checkRunning();

      synchronized (allGatewaysLock) {
        int pos = findGateway(id);
        if (pos == -1) {
          throw new GatewayException(LocalizedStrings.GatewayHubImpl_GATEWAYHUB_0_DOES_NOT_CONTAIN_A_GATEWAY_WITH_ID_1.toLocalizedString(new Object[] {this._id, id}));
        }
        this.allGateways = (AbstractGateway[]) ArrayUtils.remove(allGateways, pos);
        this._gatewayIds.remove(pos);
      }
      this._statistics.incNumberOfGateways(-1);
    }
  }

  public List getGateways() { // KIRK search product code for getGateways
    Gateway snap[] = this.allGateways;
    ArrayList result = new ArrayList();
    for (int i = 0; i < snap.length; i++) {
      result.add(snap[i]);
    }
    return result;
  }

  public List getGatewayIds() {
    return this._readOnlyGatewayIds;
  }

  /**
   * Sets the configuration of <b>this </b> <code>GatewayHub</code> based on
   * the configuration of <b>another </b> <code>GatewayHub</code>.
   *
   * @param other
   *          the <code>GatewayHub</code> form which to configure this one
   */
  public void configureFrom(GatewayHub other) throws GatewayException
  {
    synchronized (this.controlLock) {
      this.setSocketBufferSize(other.getSocketBufferSize());
      this.setMaximumTimeBetweenPings(other.getMaximumTimeBetweenPings());
      this.setStartupPolicy(other.getStartupPolicy());
      this.setManualStart(other.getManualStart());
      this.setBindAddress(other.getBindAddress());
      synchronized (other.getAllGatewaysLock()) {
        Iterator it = other.getGateways().iterator();
        while (it.hasNext()) {
          Gateway otherGateway = (Gateway)it.next();
          AbstractGateway gateway = (AbstractGateway) addGateway(
              otherGateway.getId(), otherGateway.getConcurrencyLevel());
          gateway.configureFrom(otherGateway);
        } // while
      } // synchronized
    } // synchronized
  }

  public void start() throws IOException
  {
    start(true);
  }

  /**
   * Thread group for any created threads
   *
   * Synchronized via {@link #controlLock}
   */
  private final ThreadGroup threadGroup = 
    LogWriterImpl.createThreadGroup("Gateway Hub Threads", (LogWriterI18n)null);

  public void start(boolean startGateways) throws IOException
  {
    synchronized (this.controlLock) {
      if (stopper.cancelInProgress() == null) {
        return;
      }
      
      // If the current stats are closed due to previously stopping this gateway
      // hub, recreate them
      if (getStatistics().isClosed()) {
        setStatistics(new GatewayHubStats(this._cache.getDistributedSystem(), getId()));
      }

      ((GemFireCacheImpl) getCache()).getPdxRegistry().startingGatewayHub();
      setRunning(true);

      // Initialize the DistributedLockService
      initializeDistributedLockService();

      // Add the MembershipListener (this was only a test for failover)
      //addGatewayMembershipListener();

      // Initialize primary
      initializePrimary();

      // Start the acceptor if necessary (to allow incoming updates)
      if (this._port != GatewayHub.DEFAULT_PORT) {
        this._acceptor = new AcceptorImpl(this._port, this._bindAddress,
            true /* notify by subscription */, this._socketBufferSize,
            this._maximumTimeBetweenPings, this.getCache(),
            CacheServer.DEFAULT_MAX_CONNECTIONS,
            CacheServer.DEFAULT_MAX_THREADS,
            CacheServer.DEFAULT_MAXIMUM_MESSAGE_COUNT,
            CacheServer.DEFAULT_MESSAGE_TIME_TO_LIVE, 0, null, null, 
            false, Collections.EMPTY_LIST,
            CacheServer.DEFAULT_TCP_NO_DELAY);

        this._acceptor.start();
      }

      // Start all the Gateways (to allow outgoing updates) if requested
      if (startGateways) {
        startGateways();
      }

      this._logger.info(LocalizedStrings.GatewayHubImpl_STARTED__0, this);
    }
  }

  public void startGateways() throws IOException {
    synchronized (this.controlLock) {
      synchronized (this.allGatewaysLock) {
        for (int i = 0; i < allGateways.length; i++) {
          AbstractGateway gateway = allGateways[i];
          gateway.setPrimary(this._primary);
          gateway.start();
        }
      }
    }
  }

  public void pauseGateways() {
    synchronized (this.controlLock) {
      synchronized (this.allGatewaysLock) {
        for (int i = 0; i < allGateways.length; i ++) {
          Gateway gateway = allGateways[i];
          gateway.pause();
        }
      }
    }
  }

  public void resumeGateways() {
    synchronized (this.controlLock) {
      synchronized (this.allGatewaysLock) {
        for (int i = 0; i < allGateways.length; i ++) {
          Gateway gateway = allGateways[i];
          gateway.resume();
        }
      }
    }
  }

  public void stopGateways() {
    final StoppableWriteLock writeLock = this.distributionRWLock.writeLock();
    writeLock.lock();
    try {
      synchronized (this.controlLock) {
        synchronized (this.allGatewaysLock) {
          for (int i = 0; i < allGateways.length; i++) {
            Gateway gateway = allGateways[i];
            gateway.stop();
          }
        }
      }
    } finally {
      writeLock.unlock();
    }
  }

  public boolean isRunning() {
    return this._isRunning;
  }

  static private volatile boolean emergencyClassesLoaded = false;

  /**
   * Ensure that AcceptorImpl and GatewayImpl classes get loaded.
   * 
   * @see SystemFailure#loadEmergencyClasses()
   */
  public static void loadEmergencyClasses() {
    if (emergencyClassesLoaded)
      return;
    emergencyClassesLoaded = true;
    AcceptorImpl.loadEmergencyClasses();
    GatewayImpl.loadEmergencyClasses();
  }

  /**
   * Close the acceptor and all the gateways
   * 
   * @see SystemFailure#emergencyClose()
   */
  public void emergencyClose() {
    this._isRunning = false; // don't synchronize
    AcceptorImpl ac = this._acceptor;
    if (ac != null) {
      ac.emergencyClose();
    }
    AbstractGateway snap[] = this.allGateways;
    for (int i = 0; i < snap.length; i ++) {
      allGateways[i].emergencyClose();
    }
  }

  public void stop()
  {
    final StoppableWriteLock writeLock = this.distributionRWLock.writeLock();
    boolean locked = false;
    try {
      try {
        writeLock.lock();
        locked = true;
      }
      catch (CancelException e) {
        this._logger.fine(this.toString() + ": System is cancelled; removing resources");
      }
      synchronized (this.controlLock) {
        if (!isRunning()) {
          return;
        }
    
        setRunning(false);

        // Stop the acceptor (to prevent incoming updates)
        if (this._acceptor != null) {
          try {
            this._acceptor.close();
          } catch (Exception e) {/* ignore */
          }
        }

        // Shutdown the executor (to prevent outgoing updates)
        if (this._executor != null) {
          this._executor.shutdown();
        }

        // Stop all the Gateways (to prevent outgoing updates)
        synchronized (this.allGatewaysLock) {
          for (int i = 0; i < allGateways.length; i++) {
            Gateway gateway = allGateways[i];
            try {
              gateway.stop();
            } catch (Exception e) {/* ignore */
            }
          }
        } // synchronized

        try {
          DistributedLockService.destroy(getDistributedLockServiceName());
        } catch (IllegalArgumentException e) {
          // service not found... ignore
        }

        if (this._lockObtainingThread != null
            && this._lockObtainingThread.isAlive()) {
          // wait a while for thread to terminate
          try {
            this._lockObtainingThread.join(3000);
          } catch (InterruptedException ex) {
            // ok we allowed our join to be cancelled
            // reset interrupt bit so this thread knows it has been interrupted
            Thread.currentThread().interrupt();
          }
          if (this._lockObtainingThread.isAlive()) {
            this._logger.info(LocalizedStrings.GatewayHubImpl_COULD_NOT_STOP_LOCK_OBTAINING_THREAD_DURING_GATEWAY_HUB_SHUTDOWN);
          }
        }

        // Close the statistics
        if (this._statistics != null) {
          this._statistics.close();
        }
        
        this._logger.info(LocalizedStrings.GatewayHubImpl_STOPPED__0, this);
      } // synchronized
    }
    finally {
      if (locked) {
        writeLock.unlock();
      }
    }
  }

  public Cache getCache()
  {
    return this._cache;
  }

  public boolean isPrimary()
  {
    return this._primary;
  }

  @Override
  public String toString()
  {
    StringBuffer buffer = new StringBuffer();
    buffer.append(
        USE_ASYNCHRONOUS_DISTRIBUTION ? "Asynchronous " : "Synchronous ")
        .append(this._primary ? "Primary" : "Secondary").append(
            " GatewayHub [id=").append(this._id).append(";bindAddress=")
            .append(this._bindAddress).append(";port=").append(
            basicGetPort()).append(";startupPolicy=").append(this._startupPolicy).append("]");
    return buffer.toString();
  }

  public String toDetailedString()
  {
    // Primary GatewayHub [id=HONGKONG-1;port=44444] connected to
    //   Primary Gateway to LONDON connected to
    //   [LONDON-1=ELONMAPGEMFP01:44444, LONDON-2=ELONMAPGEMFC01:44444]

    // NOTE: getGateways is not thread safe and may throw
    //       ConcurrentModificationException - below is one example of workaround
    StringBuilder sb = new StringBuilder();
    sb.append(toString());
    List currentGateways = null;
    try {
      currentGateways = new ArrayList(getGateways()); // should be thread safe
                                                      // now
    }
    catch (ConcurrentModificationException e) {
      currentGateways = null;
    }
    if (currentGateways == null) {
      sb.append(" connections are being modified.");
    }
    else {
      boolean and = false;
      for (Iterator iter = currentGateways.iterator(); iter.hasNext();) {
        Gateway gateway = (Gateway)iter.next();
        if (and)
          sb.append(" and");
        /*
         * if (!((GatewayImpl)gateway).isConnected()) // should this be on
         * public Gatway interface? sb.append(" not");
         */// is this useful??
        sb.append(" connected to ");
        sb.append(gateway);
        and = true;
      }
    }
    return sb.toString();
  }

  public void setStartupPolicy(String startupPolicy) throws GatewayException {
    synchronized (this.controlLock) {
      checkRunning();
      if (!startupPolicy.equals(STARTUP_POLICY_NONE)
          && !startupPolicy.equals(STARTUP_POLICY_PRIMARY)
          && !startupPolicy.equals(STARTUP_POLICY_SECONDARY)) {
        throw new GatewayException(LocalizedStrings.GatewayHubImpl_AN_UNKNOWN_GATEWAY_HUB_POLICY_0_WAS_SPECIFIED_IT_MUST_BE_ONE_OF_1_2_3.toLocalizedString(new Object[] {startupPolicy, STARTUP_POLICY_NONE, STARTUP_POLICY_PRIMARY, STARTUP_POLICY_SECONDARY}));
      }
      this._startupPolicy = startupPolicy;
    }
  }

  public String getStartupPolicy() {
    return this._startupPolicy;
  }

  public void setManualStart(boolean manualStart) {
    this._manualStart = manualStart;
  }

  public boolean getManualStart() {
    return this._manualStart;
  }

  /**
   * Returns the name of the <code>DistributedLockService</code> used by this
   * <code>GatewayHub</code>
   * 
   * @return the name of the <code>DistributedLockService</code> used by this
   *         <code>GatewayHub</code>
   */
  private String getDistributedLockServiceName() {
    return getClass().getName() + "-" + getId();
  }

  // / MembershipListener methods - not currently enabled ///
  public void memberJoined(final InternalDistributedMember id) {
  }

  public void memberDeparted(final InternalDistributedMember id,
      final boolean crashed) {
  }

  public void quorumLost(Set<InternalDistributedMember> failures, List<InternalDistributedMember> remaining) {
  }

  public void memberSuspect(InternalDistributedMember id,
      InternalDistributedMember whoSuspected) {
  }

  // private void addGatewayMembershipListener()
  // {
  // ((InternalDistributedSystem)this._cache.getDistributedSystem())
  // .getDistributionManager().addMembershipListener(this);
  // }

  public void distribute(EnumListenerEvent operation, EntryEventImpl event) {
    // If the event is local (see bug 35831) or an expiration ignore it.
    if (event.getOperation().isLocal() || event.getOperation().isExpiration()) {
      return;
    }

    // If this hub is not running, return
    if (stopper.cancelInProgress() != null) {
      if (this._logger.fineEnabled()) {
        this._logger
            .fine(this
                + ": Received operation and event to distribute before the gateway hub is running (operation="
                + operation + " event=" + event);
      }
      return;
    }

    this._statistics.incEventsReceived();
    
    // Clone the event. This prevents the GatewayEventCallbackArgument from
    // being created before the event is distributed to any peer GatewayHubs.
    // If it is created and set before distribution, the peer GatewayHub
    // will not process it correctly since the GECA is already set.
    EntryEventImpl clonedEvent = new EntryEventImpl(event);
    try {
    //Make sure that the right type of callback argument is set on the event
    //Fix for defect # 44398, 44396. Later on modified to fix #46162 as well.
    if (clonedEvent.getRawCallbackArgument() != null 
        && clonedEvent.getRawCallbackArgument() instanceof GatewaySenderEventCallbackArgument) {
      Object callBackArg = 
        ((WrappedCallbackArgument) clonedEvent.getRawCallbackArgument()).getOriginalCallbackArg();
      clonedEvent.setRawCallbackArgument(callBackArg);
    }
    if (USE_ASYNCHRONOUS_DISTRIBUTION) {
      // Use the Executor to execute the method in its own thread
      boolean calledExecute = false;
      try {
        final EnumListenerEvent finalOperation = operation;
        final EntryEventImpl finalEvent = clonedEvent;
        long start = this._statistics.startTime();
        this._executor.execute(new Runnable() {
          public void run() {
            // By default, override the value of the conserve-sockets property
            // in gemfire.properties so that queue ordering is preserved.
            // If ALLOW_CONSERVE_SOCKETS=true, then the conserve-sockets
            // property is used and queue ordering may not be preserved.
            if (!ALLOW_CONSERVE_SOCKETS) {
              DistributedSystem.setThreadsSocketPolicy(true);
            }

            // Distribute the event
            try {
              basicDistribute(finalOperation, finalEvent);
            }
            catch (CancelException e) {
              return; // nothing to do
            } finally {
              finalEvent.release();
            }
          }
        });
        calledExecute = true;
        this._statistics.endPut(start);
        this._statistics.setQueueSize(this._executorQueue.size());
      } catch (RejectedExecutionException e) {
        this._logger.warning(LocalizedStrings.GatewayHubImpl_0__DISTRIBUTION_REJECTED, this, e);
      } finally {
        if (!calledExecute) {
          clonedEvent.release();
        }
      }
    } else {
      // Execute the method in the same thread as the caller.
      //synchronized (this) {
        basicDistribute(operation, clonedEvent);
      //}
    }
    } finally {
      if (!USE_ASYNCHRONOUS_DISTRIBUTION) {
        clonedEvent.release();
      }
    }
  }

  /**
   * Returns the <code>GatewayHubStats</code>
   * 
   * @return the <code>GatewayHubStats</code>
   */
  protected GatewayHubStats getStatistics() {
    return this._statistics;
  }
  
  protected void setStatistics(GatewayHubStats statistics) {
    this._statistics = statistics;
  }

  /**
   * Sets this <code>Gateway</code> to be running or stopped
   * 
   * @param running
   *          Whether this <code>Gateway</code> is running or stopped
   */
  private void setRunning(boolean running) {
    synchronized (this.controlLock) {
      this._isRunning = running;
    }
  }

  /**
   * Returns whether a <code>Gateway</code> with id is already defined by this
   * <code>GatewayHub</code>. (thread safe)
   * 
   * @param id
   *          The id to verify
   * @return whether a <code>Gateway</code> with id is already defined by this
   *         <code>GatewayHub</code>
   * @guarded.By {@link #allGatewaysLock}
   */
  private boolean alreadyDefinesGateway(String id) {
    return findGateway(id) != -1;
  }

  /** Returns Gateway with id or null if no Gateway is found (thread safe) */
  /**
   * Return position of gateway in {@link #allGateways}, -1 if not found
   * 
   * @guarded.By {@link #allGatewaysLock}
   */
  private int findGateway(String id) {
    Gateway snap[] = allGateways; // volatile fetch
    int pos;
    for (pos = 0; pos < snap.length; pos++) {
      Gateway gateway = snap[pos];
      if (gateway.getId().equals(id)) {
        return pos;
      }
    }
    return -1;
  }

  /**
   * Distributes the event and operation to all known <code>Gateway</code> s
   * 
   * @param operation
   *          The operation of the event (e.g. AFTER_CREATE, AFTER_UPDATE, etc.)
   * @param event
   *          The event to distribute
   */
  public void basicDistribute(EnumListenerEvent operation, EntryEventImpl event) {
    final StoppableReentrantReadWriteLock.StoppableReadLock readLock = this.distributionRWLock
        .readLock();
    if (readLock.tryLock()) {
      try {
        if (this._logger.fineEnabled()) {
          this._logger.fine(this
              + ": About to notify all gateways to perform operation "
              + operation + " for " + event);
        }

        // Determine which gateways to distribute this event to.
        List gatewaysToDistribute = getGatewaysToDistribute(event);

        // Distribute the event to those gateways
        for (Iterator i = gatewaysToDistribute.iterator(); i.hasNext();) {
          AbstractGateway gateway = (AbstractGateway) i.next();
          gateway.distribute(operation, event);
        }
        this._statistics.incEventsProcessed();
        this._statistics.setQueueSize(this._executorQueue.size());
      } finally {
        readLock.unlock();
      }
    } else {
      if (this._logger.fineEnabled()) {
        this._logger
            .fine(this
                + ": Received operation and event to distribute before the gateway hub is running (operation="
                + operation + " event=" + event);
      }
      return;
    }
  }

  // private static boolean ONETIME = true;

  /**
   * Answers the list of <code>Gateway</code> s to which to distribute the
   * input <code>EntryEventImpl</code>.
   * 
   * @param event
   *          The <code>EntryEventImpl</code> to test
   * @return the list of <code>Gateway</code> s to which to distribute the
   *         input <code>EntryEventImpl</code>
   */
  private List getGatewaysToDistribute(EntryEventImpl event) {
    // The event can be in one of three states:
    // - originating in either a peer of this VM or this VM itself. In this
    // case, the callback argument will not be an instance of
    // GatewayEventCallbackArgument.
    // - originating in a client of this VM. In this case, the callback
    // argument will be an instance of GatewayEventCallbackArgument, but
    // the originating gateway hub id will not be set.
    // - originating in another WAN site. In this case, the callback
    // argument will be an instance of GatewayEventCallbackArgument with
    // all its proper instance variables initialized correctly
    List distributeToGateways = null;
    
    // Get the callback argument.
    Object callbackArg = event.getRawCallbackArgument();

    if (this._logger.fineEnabled()) {
      this._logger.fine(this + ": Determining recipient gateways for " + event);
    }
    
    Region region = event.getRegion();
    boolean sendToAllRecipients = false;
    boolean isPDXRegion = (region instanceof DistributedRegion && region.getName().equals(PeerTypeRegistration.REGION_NAME));
    if (isPDXRegion && event.getOperation().isCreate() 
        && !event.isPossibleDuplicate()) {
      //Fix for 46572  - It is important that a PDX type
      //definition reaches the remote site before any serialized objects
      //Therefore we forward the type to the remote site even though
      //it is already being sent by the sender
      //
      //We only send the type on if this DS had not yet seen it. That
      //prevents cycles in distribution.
      sendToAllRecipients = true;
    }

    if (callbackArg instanceof GatewayEventCallbackArgument) {
      GatewayEventCallbackArgument geca = (GatewayEventCallbackArgument) callbackArg;
      if (geca.getOriginatingGatewayHubId() == null
          // [bruce] gateway processing moved to pt2, before messaging, so we may get a geca from secondary
          // and need to allow it to the queue here
          || geca.getOriginatingGatewayHubId().equals(this._id)) {
        /*
         * EVENTID TESTING CODE
         * System.out.println("GatewayHubImpl.getGatewaysToDistribute CLIENT
         * CASE Using event id for " + event.getKey() + ": " +
         * geCallbackArg.getOriginalEventId());
         */
        // This event is from a client to this VM. If this is the primary,
        // initialize the gateway event callback argument correctly and
        // distribute this event to all gateways. If this is a secondary,
        // the callback argument will be initialized later (only if it is
        // actually processed by this secondary). Setting this data now in
        // the secondary was causing problems in the primary if it was set
        // before the event was serialized to the primary.
        if (this._primary) {
          geca.setOriginatingGatewayHubId(this._id);
          geca.initializeRecipientGateways(getGatewayIds());
        }

        // Distribute the event to all gateways.
        distributeToGateways = getGateways();
      } else {
        /*
         * EVENTID TESTING CODE
         * System.out.println("GatewayHubImpl.getGatewaysToDistribute GATEWAY
         * CASE Using event id for " + event.getKey() + ": " +
         * geCallbackArg.getOriginalEventId());
         */
        // This event is from a gateway. For each gateway, check whether any
        // of the following match:
        // - the originating gateway
        // - the sending gateway
        // - the original recipient gateways
        // If so, don't add the gateway to the list of gateways to which to
        // distribute this event. Otherwise, add the gateway to the list.
        distributeToGateways = new ArrayList();
        if (this._logger.fineEnabled()) {
          this._logger.fine(this + ": Event is from a gateway with " + geca
              + ". It may not be distributed to all gateways.");
        }
        for (Iterator i = getGateways().iterator(); i.hasNext();) {
          Gateway gateway = (Gateway) i.next();
          String gatewayId = gateway.getId();
          boolean distribute = true;
          if (this._logger.fineEnabled()) {
            this._logger.fine(this + ": Verifying " + gateway);
          }
          // Test if this gateway is the originating gateway for this event
          if (geca.getOriginatingGatewayHubId().equals(gatewayId)) {
            // This event originated in this gateway. Do not distribute it back.
            if (this._logger.fineEnabled()) {
              this._logger.fine(this + ": Event originated in " + gatewayId
                  + ". It is being dropped.");
            }
            distribute = false;
          }

          //Fix for 46572  - ignore the previous recipients if this is
          //a pdx type create.
          if(!sendToAllRecipients) {
            // Test if this gateway was one of the recipient gateways for this
            // event
            for (Iterator j = geca.getRecipientGateways().iterator(); j.hasNext();) {
              String recipientGatewayId = (String) j.next();
              if (recipientGatewayId.equals(gatewayId)) {
                if (this._logger.fineEnabled()) {
                  this._logger.fine(this
                      + ": Event has already been sent to gateway " + gatewayId
                      + ". It is being dropped.");
                }
                distribute = false;
                break;
              }
            }
          }

          // If the gateway passes the above tests, distribute the event to it
          if (distribute) {
            if (this._logger.fineEnabled()) {
              this._logger.fine(this
                  + ": Event is being distributed to gateway " + gatewayId);
            }
            geca.addRecipientGateway(gatewayId);
            distributeToGateways.add(gateway);
          }
        }
      }
    } else {
      // This event is not from a gateway. It is originating in either a peer
      // of this VM or this VM itself. Create the GatewayEventCallbackArgument
      // and set it in the event.
      // if (_logger.fineEnabled() && ONETIME) {
      // _logger.fine("normal dispatch", new Exception("stack trace"));
      // ONETIME = false;
      // }
      if (this._logger.fineEnabled()) {
        // if (!this._primary) {
        // this._logger.fine(this + ": event is not from a gateway. It is being
        // distributed to all gateways. cbarg=" + callbackArg, new
        // Exception("stack trace"));
        // }
        // else {
        this._logger
            .fine(this
                + ": event is not from a gateway. It is being distributed to all gateways.");
        // }
      }
      /*
       * EVENTID TESTING CODE
       * System.out.println("GatewayHubImpl.getGatewaysToDistribute PEER CASE
       * Using event id for " + event.getKey() + ": " +
       * event.createEventIdentifier());
       */
      GatewayEventCallbackArgument geCallbackArg = new GatewayEventCallbackArgument(
          callbackArg, this._id, getGatewayIds(),this.hubType != GatewayHubImpl.GFXD_WAN_HUB);
      // new GatewayEventCallbackArgument(
      // callbackArg,
      // event.createEventIdentifier(),
      // this._id,
      // getGatewayIds());

      event.setCallbackArgument(geCallbackArg);

      if (this._logger.fineEnabled()) {
        this._logger.fine(this + ": set callback argument: " + geCallbackArg);
      }

      // Distribute the event to all gateways.
      distributeToGateways = getGateways();
    }
    return distributeToGateways;
  }

  /**
   * Initializes whether this is the primary <code>GatewayHub</code> by
   * attempting to get a distributed lock. If the lock is obtained, this is the
   * primary <code>GatewayHub</code>; otherwise it is not. 
   */
  private void initializePrimary() throws GatewayException {
    this._primary = false;
    
    //Guarded by control lock, so ok to refer to boolean capableOfBecomingPrimary
    if(this.capableOfBecomingPrimary) {
    
    // First, try to obtain the lock in this thread.
    // Wait 1 arbitrary second for the lock.
    if (this._logger.fineEnabled()) {
      this._logger.fine(this + ": Obtaining the lock on " + this._lockToken);
    }
    this._lockService.lock(this._lockToken, 1000, -1);

    String startupPolicy = getStartupPolicy();
    if (startupPolicy.equals(STARTUP_POLICY_NONE)
        || startupPolicy.equals(STARTUP_POLICY_PRIMARY)) {
      // If the lock is obtained, start this hub as primary; otherwise
      // start this hub as secondary.
      if (this._lockService.isHeldByCurrentThread(this._lockToken)) {
        startAsPrimary();
      } else {
        startAsSecondary();
      }
    } else if (startupPolicy.equals(STARTUP_POLICY_SECONDARY)) {
      // If the lock is obtained, unlock it and continue to try to get it for
      // 60 seconds. If at the end of 60 seconds, the lock is still obtained
      // by this thread, log a warning and continue as primary; otherwise fall
      // through and become secondary.
      if (this._lockService.isHeldByCurrentThread(this._lockToken)) {
        if (this._logger.fineEnabled()) {
          this._logger.fine(GatewayHubImpl.this + ": Obtained the lock on "
              + this._lockToken);
        }
        this._lockService.unlock(this._lockToken);
        boolean obtainedTheLock = true;
        long currentTimeMs = System.currentTimeMillis();
        long endTimeMs = currentTimeMs + STARTUP_POLICY_SECONDARY_WAIT;
        boolean continueToGetLock = true;
        while (continueToGetLock) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
          }
          this._lockService.lock(this._lockToken, 1000, -1);
          if (this._lockService.isHeldByCurrentThread(this._lockToken)) {
            if (System.currentTimeMillis() <= endTimeMs) {
              this._lockService.unlock(this._lockToken);
            } else {
              continueToGetLock = false;
            }
          } else {
            obtainedTheLock = false;
            break;
          }
        }
        if (obtainedTheLock) {
          startAsPrimary();
        } else {
          startAsSecondary();
        }
      } else {
        startAsSecondary();
      }
    }
    }else {
      startAsSecondary();
    }
    /*
    if (startupPolicy.equals(STARTUP_POLICY_NONE)) {

      // If the lock is obtained, set primary to true; otherwise this VM
      // is not the primary. Launch a thread to wait indefinitely for the
      // lock.
      if (this._lockService.isHeldByCurrentThread(this._lockToken)) {
        if (this._logger.fineEnabled()) {
          this._logger.fine(this + ": Obtained the lock on " + this._lockToken);
        }
        this._primary = true;
        this._logger.info(LocalizedStrings.GatewayHubImpl_0__STARTING_AS_PRIMARY, this);
      } else {
        if (this._logger.fineEnabled()) {
          this._logger.fine(this + ": Did not obtain the lock on " + this._lockToken);
        }
        // Launch thread to get the lock
        launchLockObtainingThread();
        this._logger.info(LocalizedStrings.GatewayHubImpl_0__STARTING_AS_SECONDARY, this);
      }
    } else if (startupPolicy.equals(STARTUP_POLICY_PRIMARY)) {
      // Try to obtain the lock in this thread.
      // Wait 1 arbitrary second for the lock.
      if (this._logger.fineEnabled()) {
        this._logger.fine(this + ": Obtaining the lock on " + this._lockToken);
      }
      this._lockService.lock(this._lockToken, 1000, -1);

      // If the lock is obtained, set primary to true; otherwise fail.
      if (this._lockService.isHeldByCurrentThread(this._lockToken)) {
        if (this._logger.fineEnabled()) {
          this._logger.fine(this + ": Obtained the lock on " + this._lockToken);
        }
        this._primary = true;
        this._logger.info(LocalizedStrings.GatewayHubImpl_0__STARTING_AS_PRIMARY, this);
      } else {
        String message = LocalizedStrings.GatewayHubImpl_0_FAILED_TO_START_AS_PRIMARY_BECAUSE_THE_LOCK_1_WAS_NOT_OBTAINED.toLocalizedString(new Object[] { this, this._lockToken});
        if (this._logger.fineEnabled()) {
          this._logger.fine(message);
        }
        throw new GatewayException(message);
      }
    } else if (startupPolicy.equals(STARTUP_POLICY_SECONDARY)) {
      // Try to obtain the lock in this thread.
      // Wait 1 arbitrary second for the lock.
      if (this._logger.fineEnabled()) {
        this._logger.fine(this + ": Obtaining the lock on " + this._lockToken);
      }
      this._lockService.lock(this._lockToken, 1000, -1);

      // If the lock is obtained, unlock it and continue to try to get it for 60 seconds.
      // If at the end of 60 seconds, the lock is still obtained by this thread, fail;
      // otherwise fall through and become secondary.
      if (this._lockService.isHeldByCurrentThread(this._lockToken)) {
        if (this._logger.fineEnabled()) {
          this._logger.fine(this + ": Obtained the lock on " + this._lockToken);
        }
        this._lockService.unlock(this._lockToken);
        boolean fail = true;
        long currentTimeMs = System.currentTimeMillis();
        long endTimeMs = currentTimeMs + STARTUP_POLICY_SECONDARY_WAIT ;
        while (System.currentTimeMillis() <= endTimeMs) {
          try {Thread.sleep(1000);} catch (InterruptedException e) {}
          //System.out.println("Waking up to get the lock again.");
          this._lockService.lock(this._lockToken, 1000, -1);
          if (this._lockService.isHeldByCurrentThread(this._lockToken)) {
            //System.out.println("obtained the lock");
            this._lockService.unlock(this._lockToken);
            //System.out.println("released the lock");
          } else {
            fail = false;
            break;
          }
        }
        if (fail) {
          String message = LocalizedStrings.GatewayHubImpl_0_FAILED_TO_START_AS_SECONDARY_BECAUSE_NO_PRIMARY_STARTED_WITHIN_1_SECONDS.toLocalizedString(new Object[] {this, Integer.valueOf(STARTUP_POLICY_SECONDARY_WAIT/1000)); 
          if (this._logger.fineEnabled()) {
            this._logger.fine(message);
          }
          throw new GatewayException(message);
        }
      }

      // Become secondary.
      if (this._logger.fineEnabled()) {
        this._logger.fine(this + ": Did not obtain the lock on " + this._lockToken);
      }
      // Launch thread to get the lock
      launchLockObtainingThread();
      this._logger.info(LocalizedStrings.GatewayHubImpl_0__STARTING_AS_SECONDARY, this);
    } else {
      // This should have been caught during hub creation.
      this._logger.severe(LocalizedStrings.GatewayHubImpl_AN_INVALID_STARTUP_POLICY_WAS_SPECIFIED_THIS_HUB_IS_NOT_STARTED_CORRECTLY);
    }
    */
  }

  /**
   * Starts this <code>GatewayHub</code> as primary by setting the appropriate
   * instance variable.
   */
  private void startAsPrimary() {
    if (this._logger.fineEnabled()) {
      this._logger.fine(this + ": Obtained the lock on " + this._lockToken);
    }

    // Set primary flag to true
    this._logger.info(LocalizedStrings.SerialGatewaySenderImpl_0__STARTING_AS_PRIMARY, this);
    this._primary = true;

    // If secondary startup policy, log a warning
    if (this._startupPolicy.equals(STARTUP_POLICY_SECONDARY)) {
      this._logger.warning(
          LocalizedStrings.SerialGatewaySenderImpl_0_STARTING_AS_PRIMARY_BECAUSE_NO_DESIGNATED_PRIMARY_STARTED_WITHIN_1_SECONDS,
          new Object[] {this, Integer.valueOf(STARTUP_POLICY_SECONDARY_WAIT/1000)});
    }
  }

  /**
   * Starts this <code>GatewayHub</code> as secondary by setting the
   * appropriate instance variable and launching a thread to obtain the lock.
   */
  private void startAsSecondary() {
    if (this._logger.fineEnabled()) {
      this._logger.fine(this + ": Did not obtain the lock on "
          + this._lockToken);
    }

    // Set primary flag to false
    this._logger.info(LocalizedStrings.SerialGatewaySenderImpl_0__STARTING_AS_SECONDARY, this);
    this._primary = false;

    // If primary startup policy, log a warning
    if (this._startupPolicy.equals(STARTUP_POLICY_PRIMARY)) {
      this._logger.warning(
          LocalizedStrings.SerialGatewaySenderImpl_0_CANNOT_START_AS_PRIMARY_BECAUSE_THE_LOCK_1_WAS_NOT_OBTAINED_IT_WILL_START_AS_SECONDARY_INSTEAD,
          new Object[] {this, GatewayHubImpl.this._lockToken});
    }

    // Launch thread to get the lock
    if(capableOfBecomingPrimary) {
      launchLockObtainingThread();
    }
  }

  /**
   * Initializes the <code>DistributedLockService</code> used by this
   * <code>GatewayHub</code> to determine primary / secondary roles for
   * failover purposes.
   */
  private void initializeDistributedLockService() {
    // Get the distributed system
    DistributedSystem distributedSystem = this._cache.getDistributedSystem();

    // Get the DistributedLockService
    String dlsName = getDistributedLockServiceName();
    this._lockService = DistributedLockService.getServiceNamed(dlsName);
    if (this._lockService == null) {
      this._lockService = DLockService.create(dlsName,
          (InternalDistributedSystem) distributedSystem, true /* distributed */,
          true /* destroyOnDisconnect */, true /* automateFreeResources */);
    }

    Assert.assertTrue(this._lockService != null);
    if (_logger.fineEnabled()) {
      _logger.fine(this + ": Obtained DistributedLockService: "
          + this._lockService);
    }
  }

  /**
   * Launches a thread that attempts to obtain the distributed lock. This thread
   * is launched only by secondary <code>GatewayHub</code>s. When a seconday
   * <code>GatewayHub</code> obtains the lock, it becomes the primary and
   * tells all of its <code>Gateway</code>'s to become primary.
   */
  private void launchLockObtainingThread() {
    this._lockObtainingThread = new Thread(threadGroup, new Runnable() {
      public void run() {
        try {
          // Attempt to obtain the lock
          if (GatewayHubImpl.this._logger.fineEnabled()) {
            GatewayHubImpl.this._logger.fine(GatewayHubImpl.this
                + ": Obtaining the lock on " + GatewayHubImpl.this._lockToken);
          }
          GatewayHubImpl.this._lockService.lock(GatewayHubImpl.this._lockToken,
              -1, -1);
          if (GatewayHubImpl.this._logger.fineEnabled()) {
            GatewayHubImpl.this._logger.fine(GatewayHubImpl.this
                + ": Obtained the lock on " + GatewayHubImpl.this._lockToken);
          }
          GatewayHubImpl.this._logger.info(
              LocalizedStrings.GatewayHubImpl_0_IS_BECOMING_PRIMARY_GATEWAY_HUB,
              GatewayHubImpl.this);

          // As soon the lock is obtained, set primary
          GatewayHubImpl.this._primary = true;
          synchronized (allGatewaysLock) {
            for (int i = 0; i < allGateways.length; i ++) {
              AbstractGateway gateway = allGateways[i];
              gateway.becomePrimary();
            }
          }
        }
        catch (CancelException e) {
          // no action necessary
        }
        catch (Exception e) {
          if (stopper.cancelInProgress() == null) {
            GatewayHubImpl.this._logger.severe( 
              LocalizedStrings.GatewayHubImpl_0_THE_THREAD_TO_OBTAIN_THE_FAILOVER_LOCK_WAS_INTERRUPTED__THIS_GATEWAY_HUB_WILL_NEVER_BECOME_THE_PRIMARY, 
              GatewayHubImpl.this , e);
          }
        }
      }
    }, "Gateway Hub Primary Lock Acquisition Thread");

    this._lockObtainingThread.setDaemon(true);
    this._lockObtainingThread.start();
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.gemstone.gemfire.cache.util.GatewayHub#getAllGatewaysLock()
   */
  public Object getAllGatewaysLock() {
    // TODO Auto-generated method stub
    return allGatewaysLock;
  }

  public boolean isDBSynchronizerOrGfxdGatewayHub() {
    return this.hubType == GFXD_ASYNC_DBSYNCH_HUB
        || this.hubType == GFXD_WAN_HUB;
  }

  boolean getGemFireXDStartedHub() {
    return this.hubType > NON_GFXD_HUB;
  }

  public int getHubType() {
    return this.hubType;
  }
}
