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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheListener;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.GatewayConfigurationException;
import com.gemstone.gemfire.cache.GatewayException;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.internal.EndpointManagerImpl;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.util.CacheListenerAdapter;
import com.gemstone.gemfire.cache.util.Gateway;
import com.gemstone.gemfire.cache.util.GatewayEventListener;
import com.gemstone.gemfire.cache.util.GatewayQueueAttributes;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.GatewayCancelledException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheServerHelper;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.security.GemFireSecurityException;
import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_NEW_VALUE;


/**
 * Represents a {@link Gateway}.
 *
 * @author Barry Oglesby
 * @since 4.2
 */
public class GatewayImpl extends AbstractGateway
{

  /**
   * Timeout tokens in the unprocessedEvents map after this many milliseconds.
   */
  static protected final int TOKEN_TIMEOUT
    = Integer.getInteger("Gateway.TOKEN_TIMEOUT", 15000).intValue();

  /**
   * Size of the oplog file used for the persistent queue in bytes
   */
  static public final int QUEUE_OPLOG_SIZE
    = Integer.getInteger("gemfire.GatewayQueueOpLogSize", 1024*1024*100).intValue();


  /**
   * Time, in seconds, that we allow before a gateway is considered
   * dead and should be aborted
   */
  static private final long GATEWAY_TIMEOUT
  = Integer.getInteger("gemfire.GATEWAY_TIMEOUT", 30).intValue();

  /**
   * The list of endpoints (host and port) to which this <code>Gateway</code>
   * is connected. Use pattern of replace instead of modify, which means:
   *
   * <pre>
   * List newEndpoints = new ArrayList(this._endpoints);
   * newEndpoints.add(new EndpointImpl(id, host, port));
   * this._endpoints = Collections.unmodifiableList(newEndpoints);
   * </pre>
   */
  private volatile List _endpoints; // always replace never modify

  /**
   * The list of <code>GatewayEventListeners</code> to which this
   * <code>Gateway</code> invokes the callback. Use pattern of replace
   * instead of modify, which means:<pre>
   *   List newListeners = new ArrayList(this._listeners);
   *   newListeners.add(listener);
   *   this._listeners = Collections.unmodifiableList(newListeners);
   * </pre>
   */
  private volatile List _listeners; // always replace never modify

  /**
   * The <code>GatewayQueueAttributes</code> for this <code>Gateway</code>.
   */
  private volatile GatewayQueueAttributes _queueAttributes;

  /**
   * Whether this is a primary <code>Gateway</code>
   */
  private volatile boolean _primary;

  /**
   * An object used by a secondary <code>Gateway</code> to wait - notify on
   * when becoming primary
   */
  private final Object _primaryLock = new Object();

  protected class Stopper extends CancelCriterion {

    /* (non-Javadoc)
     * @see com.gemstone.gemfire.CancelCriterion#cancelInProgress()
     */
    @Override
    public String cancelInProgress() {
      String reason = GatewayImpl.this._hub.getCancelCriterion().cancelInProgress();
      if (reason != null) {
        return reason;
      }
      if (GatewayImpl.this._isRunning) {
        return null;
      }
      return "Gateway has been stopped";
    }

    /* (non-Javadoc)
     * @see com.gemstone.gemfire.CancelCriterion#generateCancelledException(java.lang.Throwable)
     */
    @Override
    public RuntimeException generateCancelledException(Throwable e) {
      String reason = cancelInProgress();
      if (reason == null) {
        return null;
      }
      RuntimeException result = GatewayImpl.this._hub.getCancelCriterion().generateCancelledException(e);
      if (result != null) {
        return result;
      }
      return new GatewayCancelledException("Gateway has been stopped"); // TODO pick better exception
    }

  }
  protected final Stopper stopper = new Stopper();

  public CancelCriterion getCancelCriterion() {
    return stopper;
  }

  /**
   * The <code>pool</code> used by this <code>Gateway</code>.
   */
  protected volatile PoolImpl _proxy;

  /**
   * The buffer size in bytes of the socket connection between this code>Gateway
   * </code> and its receiving <code>Gateway</code>
   */
  private int _socketBufferSize;

  /**
   * The amount of time in milliseconds that a socket read between this
   * <code>Gateway</code> and its receiving <code>Gateway</code> will block.
   */
  private final int _socketReadTimeout;

  /**
   * The <code>GatewayEventProcessor</code> used by this <code>Gateway</code>
   * to do the following:
   * <ul>put events on its queue (if primary) or map (if secondary)
   * <li>read batches of events off the queue (if primary)
   * <li>manage the map as events are added to the queue (if secondary)
   * <li>dispatch events to its receiver
   * </ul>
   */
  private volatile GatewayEventProcessor _eventProcessor;

  /**
   * Whether this <code>Gateway</code> is used by a <code>GatewayParallelImpl</code>
   */
  private final boolean _usedInParallel;
  /**
   * The number of times to peek on shutdown before giving up and shutting down.
   */
  protected static final int MAXIMUM_SHUTDOWN_PEEKS = Integer.getInteger(
      "Gateway.MAXIMUM_SHUTDOWN_PEEKS", 20).intValue();

  /**
   * The queue size threshold used to warn the user. If the queue reaches this
   * size, log a warning.
   */
  protected static final int QUEUE_SIZE_THRESHOLD = Integer.getInteger(
      "Gateway.QUEUE_SIZE_THRESHOLD", 5000).intValue();

  /**
   * Unique ID for pool names
   */
  private static final AtomicInteger ID_COUNTER = new AtomicInteger();

  ////////////////////// Constructors //////////////////////

  /**
   * Constructor. Creates a new <code>GatewayImpl</code> with the default
   * configuration.
   */
  GatewayImpl(GatewayHubImpl hub, String id) {
    this(hub, id, false, null);
  }

  /**
   * Constructor. Creates a new <code>GatewayImpl</code> for use in parallel.
   */
  GatewayImpl(GatewayHubImpl hub, String id, boolean usedInParallel,
      GatewayStats allStatistics) {
    super(hub, id, id, allStatistics);
    this._usedInParallel = usedInParallel;
    this._endpoints = Collections.EMPTY_LIST;
    this._listeners = Collections.EMPTY_LIST;
    this._isRunning = false;
    this._queueAttributes = new GatewayQueueAttributes();    
    this._primary = false;
    this._socketBufferSize = DEFAULT_SOCKET_BUFFER_SIZE;
    this._socketReadTimeout = DEFAULT_SOCKET_READ_TIMEOUT;
  }

  ///////////////////// Instance Methods /////////////////////

  public void addEndpoint(String id, String host, int port)
      throws GatewayException
  {
    synchronized (this.controlLock) {
      checkRunning();
      // If an endpoint with the id, host and port is already defined, throw an
      // exception
      if (alreadyDefinesEndpoint(id, host, port)) {
        throw new GatewayException(LocalizedStrings.GatewayImpl_GATEWAY_0_ALREADY_DEFINES_AN_ENDPOINT_EITHER_WITH_ID_1_OR_HOST_2_AND_PORT_3.toLocalizedString(
                new Object[] {this._id, id, host, Integer.valueOf(port)}));
      }

      // If another gateway defines this same endpoint and its not used in a
      // parallel gateway, throw an exception. If it is used in a parallel
      // gateway, it is ok to define the same endpoint in multiple gateways.
      String[] otherGateway = new String[1];
      if (!this._usedInParallel && otherGatewayDefinesEndpoint(host, port, otherGateway)) {
        throw new GatewayException(LocalizedStrings.GatewayImpl_GATEWAY_0_CANNOT_DEFINE_ENDPOINT_HOST_1_AND_PORT_2_BECAUSE_IT_IS_ALREADY_DEFINED_BY_GATEWAY_3.toLocalizedString(
              new Object[] {this._id, host, Integer.valueOf(port), otherGateway[0]}));
      }

      // If the gateway is attempting to add an endpoint to its own hub, throw
      // an exception
      if (isConnectingToOwnHub(host, port)) {
        throw new GatewayException(LocalizedStrings.GatewayImpl_GATEWAY_0_CANNOT_DEFINE_AN_ENDPOINT_TO_ITS_OWN_HUB_HOST_1_AND_PORT_2
            .toLocalizedString(new Object[] {this._id, host, Integer.valueOf(port)}));
      }

      // If listeners are already defined, throw an exception
      if (hasListeners()) {
        throw new GatewayException(LocalizedStrings.GatewayImpl_GATEWAY_0_CANNOT_DEFINE_AN_ENDPOINT_BECAUSE_AT_LEAST_ONE_LISTENER_IS_ALREADY_DEFINED_BOTH_LISTENERS_AND_ENDPOINTS_CANNOT_BE_DEFINED_FOR_THE_SAME_GATEWAY
            .toLocalizedString(this._id));
      }

      List newEndpoints = new ArrayList(this._endpoints);
      newEndpoints.add(new EndpointImpl(id, host, port));
      this._endpoints = Collections.unmodifiableList(newEndpoints);
    }
  }

  public List getEndpoints()
  {
    return this._endpoints;
  }

  public boolean hasEndpoints() {
    return getEndpoints().size() > 0;
  }

  public void addListener(GatewayEventListener listener) throws GatewayException {
    synchronized (this.controlLock) {
      checkRunning();
      // If endpoints are already defined, throw an exception
      if (hasEndpoints()) {
        throw new GatewayException(LocalizedStrings.GatewayImpl_GATEWAY_0_CANNOT_DEFINE_A_LISTENER_BECAUSE_AT_LEAST_ONE_ENDPOINT_IS_ALREADY_DEFINED_BOTH_LISTENERS_AND_ENDPOINTS_CANNOT_BE_DEFINED_FOR_THE_SAME_GATEWAY.toLocalizedString(this._id));
      }

      List newListeners = new ArrayList(this._listeners);
      newListeners.add(listener);
      this._listeners = Collections.unmodifiableList(newListeners);
    }
  }

  public List getListeners() {
    return this._listeners;
  }

  public boolean hasListeners() {
    return getListeners().size() > 0;
  }

  public void setSocketBufferSize(int socketBufferSize)
  {
    synchronized (this.controlLock) {
      checkRunning();
      this._socketBufferSize = socketBufferSize;
    }
  }

  public int getSocketBufferSize()
  {
    synchronized (this.controlLock) {
      return this._socketBufferSize;
    }
  }

  public void setSocketReadTimeout(int socketReadTimeout)
  {
    synchronized (this.controlLock) {
      checkRunning();
      getLogger().warning(LocalizedStrings.GatewayImpl_GATEWAY_SOCKET_READ_TIMEOUT_DISABLED);
      // do nothing on purpose...
      // setSocketReadTimeout is now optional and this impl ignores it
      // setSocketReadTimeout was causing too many problems because customers
      //    kept using too small of a value
    }
  }

  public int getSocketReadTimeout()
  {
    synchronized (this.controlLock) {
      return this._socketReadTimeout;
    }
  }

  /**
   * Set whether this is a primary <code>Gateway</code>
   */
  @Override
  protected void setPrimary(boolean primary)
  {
    this._primary = primary;
  }

  /**
   * Returns whether this is a primary <code>Gateway</code>
   *
   * @return whether this is a primary <code>Gateway</code>
   */
  public boolean getPrimary()
  {
    return this._primary;
  }

  public void setQueueAttributes(GatewayQueueAttributes queueAttributes)
  {
    synchronized (this.controlLock) {
      checkRunning();
      // See bug 44558. The gateway.stop method has been changed to close the
      // region. If the previous queue attributes enabled persistence and the
      // new ones don't, destroy the disk files. Don't use the existing queue
      // attributes since they may have been modified in place. If the event
      // processor is null, the gateway has not been started, so there isn't any
      // way to know the previous state. This method will throw an
      // IllegalStateException if the previous state is incompatible with the
      // new state.
      if (this._eventProcessor != null
          && ((SingleWriteSingleReadRegionQueue) this._eventProcessor._eventQueue)
              .isPersistent() && (!queueAttributes.getEnablePersistence())) {
        this._eventProcessor.destroyQueuePersistenceFiles();
      }
      this._queueAttributes = queueAttributes;
    }
  }

  public GatewayQueueAttributes getQueueAttributes()
  {
    synchronized (this.controlLock) {
      return this._queueAttributes;
    }
  }

  public GatewayQueueAttributes getQueueAttributesNoSync()
  {
    return this._queueAttributes;
  }
  
  /**
   * This method has been added for test only purpose. 
   * This can be used by DUnit or Hydra tests to get the RegionQueue enclosed by this GatewayImpl.
   *  
   * @return    RegionQueue     RegionQueue enclosed by this gateway
   */
  public RegionQueue getRegionQueueTestOnly() {
    return this._eventProcessor._eventQueue;
  }

  public int getConcurrencyLevel() {
    return DEFAULT_CONCURRENCY_LEVEL;
  }
  
  /**
   * Returns whether another <code>Gateway</code> already defines an
   * <code>Endpoint</code> with the same host and port.
   *
   * @param host
   *          The host of the endpoint
   * @param port
   *          The port that the endpoint is listening on
   * @param otherGateway
   *          The other <code>Gateway</code> defining this
   *          <code>Endpoint</code>
   * @return whether another <code>Gateway</code> already defines an
   *         <code>Endpoint</code> with the same host and port
   * @guarded.By {@link #controlLock}
   */
  private boolean otherGatewayDefinesEndpoint(String host, int port,
      String[] otherGateway)
  {
    boolean otherGatewayDefined = false;
    // Iterate through all gateways and compare their endpoints to the input
    // host and port.
    for (Iterator i = getGatewayHub().getGateways().iterator(); i.hasNext();) {
      Gateway gateway = (Gateway)i.next();
      // Do not compare the current gateway
      if (!getId().equals(gateway.getId())) {
        for (Iterator j = gateway.getEndpoints().iterator(); j.hasNext();) {
          Endpoint endpoint = (Endpoint)j.next();
          // If the endpoint's host and port are equal to the input host and
          // port, answer true; else continue.
          if (endpoint.getHost().equals(host) && endpoint.getPort() == port) {
            otherGatewayDefined = true;
            otherGateway[0] = gateway.getId();
            break;
          }
        }
      }
    }
    return otherGatewayDefined;
  }

  /**
   * Returns whether an <code>Endpoint</code> with id is already defined by
   * this <code>Gateway</code>.
   *
   * @param id
   *          The id to verify
   * @param host
   *          The host of the endpoint
   * @param port
   *          The port that the endpoint is listening on
   * @return whether an <code>Endpoint</code> with id is already defined by
   *         this <code>Gateway</code>
   */
  private boolean alreadyDefinesEndpoint(String id, String host, int port)
  {
    boolean alreadyDefined = false;
    for (Iterator i = this._endpoints.iterator(); i.hasNext();) {
      Endpoint endpoint = (Endpoint)i.next();
      // If the ids are equal or the host and port are equal, then the
      // requested endpoint is already defined.
      if (endpoint.getId().equals(id)
          || (endpoint.getHost().equals(host) && endpoint.getPort() == port)) {
        alreadyDefined = true;
        break;
      }
    }
    return alreadyDefined;
  }

  /**
   * Returns whether this <code>Gateway</code> is attempting to add an
   * <code>Endpoint</code> to its own <code>GatewayHub</code>.
   *
   * @param host
   *          The host of the endpoint
   * @param port
   *          The port that the endpoint is listening on
   * @return whether this <code>Gateway</code> is attempting to add an
   *         <code>Endpoint</code> to its <code>GatewayHub</code>
   */
  private boolean isConnectingToOwnHub(String host, int port)
  {
    // These tests work where the host is specified as:
    // IP address string (e.g. host=10.80.10.80)
    // Short host name (e.g. host=bishop)
    // Fully-qualified host name (e.g. bishop.gemstone.com)
    // The localhost (e.g. localhost)
    // The loopback address (e.g. 127.0.0.1)

    // First compare the port with the hub's port. If they are the same,
    // then compare the host with the local host.
    boolean isConnectingToOwnHub = false;
    if (port == this._hub.getPort()) {
      // The ports are equal. Now, compare the hosts. Do a best guess
      // determination whether the input host is the same as the local
      // host.
      try {
        String localHostName = SocketCreator.getLocalHost()
            .getCanonicalHostName();
        String requestedHostName = InetAddress.getByName(host)
            .getCanonicalHostName();
        if (localHostName.equals(requestedHostName)
            || requestedHostName.startsWith("localhost")) {
          isConnectingToOwnHub = true;
        }
      }
      catch (UnknownHostException e) {
      }
    }
    return isConnectingToOwnHub;
  }

  /**
   * Returns the GemFire cache
   *
   * @return the GemFire cache
   */
  protected Cache getCache()
  {
    return this._cache;
  }

  /**
   * Returns the <code>pool</code>
   *
   * @return the <code>pool</code>
   */
  protected PoolImpl getProxy()
  {
    synchronized (this.controlLock) {
      return this._proxy;
    }
  }

  public void start() throws IOException
  {
    start(null);
  }
  
  protected void start(GatewayParallelImpl gpi) throws IOException {  
    synchronized (this.controlLock) {
      if (this._isRunning) {
        return;
      }

      // If the current stats are closed due to previously stopping this gateway,
      // recreate them using the GatewayParallelImpl's stats if necessary
      if (getStatistics().isClosed()) {
        setStatistics(new GatewayStats(this._cache.getDistributedSystem(),
            getGatewayHubId(), getId(), gpi == null ? null
                : gpi.getStatistics()));
      }

      if(hasEndpoints()) {
        // Create ConnectionProxy to the Endpoints
        Properties properties = new Properties();
        StringBuffer buffer = new StringBuffer();
        for (Iterator i = getEndpoints().iterator(); i.hasNext();) {
          Endpoint endpoint = (Endpoint)i.next();
          buffer.append(endpoint.getId()).append('=').append(endpoint.getHost())
          .append(':').append(endpoint.getPort());
          if (i.hasNext()) {
            buffer.append(',');
          }
        }
        properties.setProperty("endpoints", buffer.toString());
        properties.setProperty("socketBufferSize", String
            .valueOf(this._socketBufferSize));
        // we don't want any connections until we call acquireConnection
        // this fixes bug 39430
        properties.setProperty("connectionsPerServer", "0");
        properties.setProperty("readTimeout", String
            .valueOf(this._socketReadTimeout));

        this._proxy = createPool(properties);
        EndpointManagerImpl emi = (EndpointManagerImpl) this._proxy.getEndpointManager();
        emi.setGatewayStats(getStatistics());
      }

      // Initialize the event processor - done after the proxy is created
      try {
        /*
         * isRunning must be false when we initialize the queue, otherwise we can encounter distributed deadlock with P2P reader threads
         * seeing isRunning=true and then trying to get the controlLock, meanwhile the controlLock is held while we are doing stateFlush
         * and waiting for replies that need the p2p reader thread to unstick.
         * 
         * See bug #41921
         */
        GatewayEventProcessor ev = initializeEventProcessor();
        /*
         * We have to set isRunning=true before we actually start the GEP thread because of bug #39772, where the thread would exit early
         */
        setRunning(true);
        ev.start();
        
        ((GemFireCacheImpl) getCache()).getPdxRegistry().gatewayStarted(this);
      }
      catch (GemFireSecurityException e) {
        setRunning(false);
        throw e;
      }
      catch (GatewayConfigurationException e) {
        setRunning(false);
        throw e;
      }

      this.getLogger().info(LocalizedStrings.SerialGatewaySenderImpl_STARTED__0, this);
    }
  }
  
  public void pause()
  {
    synchronized (this.controlLock) {
      if (!this._isRunning) {
        return;
      }
      this._eventProcessor.pauseDispatching();
      getLogger().info(LocalizedStrings.GatewayImpl_PAUSED__0, this);
    }
  }

  public void resume()
  {
    synchronized (this.controlLock) {
      if (!this._isRunning) {
        return;
      }
      this._eventProcessor.resumeDispatching();
      getLogger().info(LocalizedStrings.GatewayImpl_RESUMED__0, this);
    }
  }

  public boolean isPaused() {
    boolean isPaused = false;
    GatewayEventProcessor ev = this._eventProcessor;
    if (ev != null) {
      isPaused = ev.getIsPaused();
    }
    return isPaused;
  }

  public PoolImpl createPool(Properties props) {
    String name = "GatewayPool-" + getGatewayPoolId();
    PoolFactoryImpl pf = (PoolFactoryImpl)PoolManager.createFactory();
    // @todo grid: switch this to use PoolImpl instead of BridgePoolImpl
    try {
      pf.init(props, false, true);
    } catch (IllegalArgumentException e) {
      if(e.getMessage().contains("Couldn't find any Endpoint")){
        throw e;
      }else {
        this._logger.warning(LocalizedStrings.GatewayImpl_UnknownHost, e);  
      }
    }
    PoolImpl result = (PoolImpl)pf.create(name);
    return result;
  }


  private static int getGatewayPoolId() {
    return ID_COUNTER.incrementAndGet();
  }

  /**
   * Ensure that the ConnectionProxyImpl class gets loaded
   *
   * @see SystemFailure#loadEmergencyClasses()
   */
  public static void loadEmergencyClasses() {
    PoolImpl.loadEmergencyClasses();
  }

  /**
   * Close this instance's proxy.
   *
   * @see SystemFailure#emergencyClose()
   */
  @Override
  public void emergencyClose() {
    this._isRunning = false; // try!
    {
      PoolImpl bpi = this._proxy;
      if (bpi != null) {
        bpi.emergencyClose();
      }
    }
  }
  
  /**
   * For bug 39255
   */
  private void stompProxyDead() {
    Runnable stomper = new Runnable() {
      public void run() {
        PoolImpl bpi = GatewayImpl.this._proxy;
        if (bpi != null) {
          try {
            bpi.destroy();
          }
          catch (Exception e) {/* ignore */
          }
        }
      }
    };
    ThreadGroup tg = LogWriterImpl.createThreadGroup(
        "Proxy Stomper Group", GatewayImpl.this.getLogger());
    Thread t = new Thread(tg, stomper, "Gateway Proxy Stomper");
    t.setDaemon(true);
    t.start();
    try {
      t.join(GATEWAY_TIMEOUT * 1000);
      return;
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    this.getLogger().warning( LocalizedStrings.
        GatewayImpl_GATEWAY_0_IS_NOT_CLOSING_CLEANLY_FORCING_CANCELLATION,
        this);
    // OK, either we've timed out or been interrupted.  Time  for
    // violence.
    t.interrupt(); // give up
    this._proxy.emergencyClose(); // VIOLENCE!
  }

  public void stop()
  {
    synchronized (this.controlLock) {
      if (!this._isRunning) {
        return;
      }
      setRunning(false);
      
      setFailoverComplete(false);

      // Stop the dispatcher
      GatewayEventProcessor ev = this._eventProcessor;
      if (ev != null) {
        try {
          ev.resumeDispatching();
          ev.stopProcessing();
        }
        catch (Exception e) {/* ignore */
        }
      }

      // Stop the proxy (after the dispatcher, so the socket is still
      // alive until after the dispatcher has stopped)
      stompProxyDead();

      // Close the listeners
      for (Iterator i = this._listeners.iterator(); i.hasNext();) {
        ((GatewayEventListener) i.next()).close();
      }

      // Close the statistics
      if (this._statistics != null) {
        this._statistics.close();
      }

      this.getLogger().info(LocalizedStrings.GatewayImpl_STOPPED__0, this);
    }
  }

  public int getQueueSize()
  {
    synchronized (this.controlLock) {
      GatewayEventProcessor ev = this._eventProcessor;
      if (ev == null) {
        return 0;
      }
      return ev._eventQueue.size();
    }
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(this._primary ? "Primary" : "Secondary");
    sb.append(" Gateway to ");
    sb.append(this._id);
    if (hasEndpoints()) {
      if (!isConnectedNoSync()) {
        sb.append(" not");
      }
      sb.append(" connected to ");
      sb.append(this._endpoints); // replaced and not modified in place... safe
    } else if (hasListeners()) {
      sb.append(" with listeners ");
      sb.append(this._listeners); // replaced and not modified in place... safe
    }
    return sb.toString();
  }

  public boolean isConnected()
  {
    synchronized (this.controlLock) {
      return isConnectedNoSync();
    }
  }

  private boolean isConnectedNoSync()
  {
    // want a nosync flavor so toString does not cause deadlocks
    if (!this._isRunning) {
      return false;
    }
    GatewayEventProcessor ev = this._eventProcessor;
    if (ev == null) {
      return false;
    }
    if (!ev.isAlive()) {
      return false;
    }
    if (!this._isRunning) {
      return false;
    }
    PoolImpl bpi = this._proxy; // volatile fetch
    if (bpi == null) {
      return false;
    }
    return !bpi.isDestroyed();
  }

  /**
   * Sets this <code>Gateway</code> to be running or stopped
   *
   * @param running
   *          Whether this <code>Gateway</code> is running or stopped
   */
  private void setRunning(boolean running)
  {
    synchronized (this.controlLock) {
      this._isRunning = running;
    }
  }

  /**
   * Distributes the event and operation to this <code>Gateway</code>'s
   * <code>EventDispatcher</code>
   *
   * @param operation
   *          The operation of the event (e.g. AFTER_CREATE, AFTER_UPDATE, etc.)
   * @param event
   *          The event to distribute
   */
  @Override
  public void distribute(EnumListenerEvent operation, EntryEventImpl event)
  {
    if (!isRunning()) {
      return;
    }
    
    {
      // Serialization must be done outside of the control lock because PDX
      // might perform messaging that can deadlock if we're holding the controlLock.
      @Unretained(ENTRY_EVENT_NEW_VALUE)
      Object newValue = event.getRawNewValue();
      if (CachedDeserializableFactory.preferObject()
          || newValue instanceof CachedDeserializable) {
        // What if the value contained in the CachedDeserializable has been deserialized?
        // TODO: clarify why we do not need to check for that in this case.
        // Note that if the value is stored off-heap then newValue is a CachedDeserializable and is in serialized form.
      } else if (newValue instanceof byte[]) {
        // just bytes so nothing needed
      } else {
        // So if the event's new value is in deserialized form serialize it here
        // and record it in the event.
        try {
          byte[] value = CacheServerHelper.serialize(newValue);
          // TODO this looks wrong. We shouldn't get calling setNewValue with a CachedDeserializable.
          // Instead we should be calling setSerializedNewValue with the byte[] value.
          // Also what if getRawNewValue returns a CachedDeserializable?
          event.setNewValue(CachedDeserializableFactory.create(value));
        } catch (IOException e) {
          this.getLogger().severe(
              LocalizedStrings.GatewayImpl_0_AN_EXCEPTION_OCCURRED_WHILE_QUEUEING_1_TO_PERFORM_OPERATION_2_FOR_3,
              new Object[] {this, getId(), operation, event}, e);
        }
      }
    }
    
    synchronized (this.controlLock) {
      // If this gateway is not running, return
      if (!isRunning()) {
        return;
      }

      if (this.getLogger().fineEnabled()) {
        // We can't deserialize here for logging purposes so don't
        // call getNewValue.
        //event.getNewValue(); // to deserialize the value if necessary
        this.getLogger().fine(this + ": About to queue operation " + operation + " for "+ getId() + ": "
            + event);
      }
      try {
        GatewayEventProcessor ev = this._eventProcessor;
        if (ev == null) {
          stopper.checkCancelInProgress(null);
          ((InternalDistributedSystem)this._cache.getDistributedSystem())
            .getCancelCriterion().checkCancelInProgress(null);
          // event processor will be null if there was an authorization problem
          // connecting to the other site (bug #40681)
          if (ev == null) {
            throw new GatewayCancelledException("Event processor thread is gone");
          }
        }
        if (ev != null) {
          ev.enqueueEvent(operation, event);
        }
      }
      catch (CancelException e) {
        throw e;
      }
      catch (Exception e) {
        this.getLogger().severe(
            LocalizedStrings.GatewayImpl_0_AN_EXCEPTION_OCCURRED_WHILE_QUEUEING_1_TO_PERFORM_OPERATION_2_FOR_3,
            new Object[] {this, getId(), operation, event}, e);
      }
    }
  }

  /**
   * Initializes this <code>Gateway</code>'s <code>GatewayEventProcessor</code>
   */
  private GatewayEventProcessor initializeEventProcessor() {
    GatewayEventProcessor ev = new GatewayEventProcessor(this);
    this._eventProcessor = ev;

    if (this.getLogger().fineEnabled()) {
      this.getLogger().fine(this + ": Created event processor " + ev);
    }

    return ev;
    
  }

  /**
   * Wait to be told to become the primary <code>Gateway</code>. This method
   * is invoked by the <code>Gateway</code>'s<code>EventDispatcher</code>
   * to wait until it is primary before processing the queue.
   */
  protected void waitToBecomePrimary() throws InterruptedException
  {
    if (getPrimary()) {
      return;
    }
    synchronized (this._primaryLock) {
      while (!getPrimary()) {
        this.getLogger().info(LocalizedStrings.GatewayImpl_0__WAITING_TO_BECOME_PRIMARY_GATEWAY, this);
        this._primaryLock.wait();
      }
    } // synchronized
  }

  /**
   * @guarded.By failoverCompletedLock
   */
  private boolean failoverCompleted = false;

  private final Object failoverCompletedLock = new Object();

  protected void waitForFailoverCompletion()
  {
    synchronized (this.failoverCompletedLock) {
      if (this.failoverCompleted) {
        return;
      }
      this.getLogger().info(LocalizedStrings.GatewayImpl_0__WAITING_FOR_FAILOVER_COMPLETION, this);
      try {
        while (!this.failoverCompleted) {
          this.failoverCompletedLock.wait();
        }
      }
      catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        this._cache.getCancelCriterion().checkCancelInProgress(ex);
        this.getLogger().info(LocalizedStrings.GatewayImpl_0_DID_NOT_WAIT_FOR_FAILOVER_COMPLETION_DUE_TO_INTERRUPTION, this);
      }
    }
  }

  protected void completeFailover()
  {
    synchronized (this.failoverCompletedLock) {
      setFailoverComplete(true);
      this.failoverCompletedLock.notifyAll();
    }
  }

  private void setFailoverComplete(boolean failoverCompleted) {
    this.failoverCompleted = failoverCompleted;
  }

  /**
   * Become the primary <code>Gateway</code>. This method is invoked by the
   * <code>GatewayHub</code> when it becomes primary.
   *
   * @see GatewayHubImpl#launchLockObtainingThread
   */
  @Override
  protected void becomePrimary()
  {
    this.getLogger().info(LocalizedStrings.GatewayImpl_0__BECOMING_PRIMARY_GATEWAY, this);
    synchronized (this._primaryLock) {
      setPrimary(true);
      this._primaryLock.notify();
    }
  }

  /**
   * Class <code>EndpointImpl</code> represents a <code>Gateway</code>'s
   * endpoint
   *
   * @author Barry Oglesby
   *
   * @since 4.2
   */
  static protected class EndpointImpl implements Endpoint
  {

    /**
     * The id of the <code>Endpoint</code>
     */
    protected String _myid;

    /**
     * The host of the <code>Endpoint</code>
     */
    protected String _host;

    /**
     * The port of the <code>Endpoint</code>
     */
    protected int _port;

    /**
     * Constructor.
     *
     * @param id
     *          The id of the <code>Endpoint</code>
     * @param host
     *          The host of the <code>Endpoint</code>
     * @param port
     *          The port of the <code>Endpoint</code>
     */
    protected EndpointImpl(String id, String host, int port) {
      this._myid = id;
      this._host = host;
      this._port = port;
    }

    public String getId()
    {
      return this._myid;
    }

    public String getHost()
    {
      return this._host;
    }

    public int getPort()
    {
      return this._port;
    }

    @Override
    public String toString()
    {
      StringBuffer buffer = new StringBuffer();
      buffer.append(this._myid).append("=").append(this._host).append(":")
          .append(this._port);
      return buffer.toString();
    }
  }

  /**
   * Class <code>GatewayEventProcessor</code> is this <code>Gateway</code>'s
   * event processor thread. It processor events to its corresponding
   * <code>Gateway</code>
   *
   * @author Barry Oglesby
   *
   * @since 4.2
   */
  @SuppressWarnings("synthetic-access")
  protected class GatewayEventProcessor extends Thread  {

    /**
     * The <code>GatewayImpl</code> for which this
     * <code>GatewayEventDispatcher</code> processes messages.
     */
    protected final GatewayImpl _gateway;

    /**
     * The <code>RegionQueue</code> used to queue messages by this
     * <code>GatewayEventDispatcher</code>.
     */
    protected final RegionQueue _eventQueue;

    /**
     * The <code>GatewayEventDispatcher</code> used by this
     * <code>GatewayEventProcessor</code> to dispatch events.
     */
    protected final GatewayEventDispatcher _eventDispatcher;

    /**
     * The conflator faciliates message conflation
     */
    //protected BridgeEventConflator _eventConflator;
    
    private final Object unprocessedEventsLock = new Object();
    /**
     * A <code>Map</code> of events that have not been processed by the
     * primary yet. This map is created and used by a secondary
     * <code>Gateway</code> to keep track of events that have been received by
     * the secondary but not yet processed by the primary. Once an event has
     * been processed by the primary, it is removed from this map. This map will
     * only be used in the event that this <code>Gateway</code> becomes the
     * primary. Any events contained in this map will need to be sent to other
     * <code>Gateway</code>s.
     * Note: unprocessedEventsLock MUST be synchronized before using this map.
     */
    private Map unprocessedEvents;
    /**
     * A <code>Map</code> of tokens (i.e. longs) of entries that we have
     * heard of from the primary but not yet the secondary.
     * This map is created and used by a secondary
     * <code>Gateway</code> to keep track.
     * Note: unprocessedEventsLock MUST be synchronized before using this map.
     *       This is not a cut and paste error. sync unprocessedEventsLock
     *       when using unprocessedTokens.
     */
    private Map unprocessedTokens;

    /**
     * A boolean verifying whether a warning has already been issued if the
     * event queue has reached a certain threshold.
     */
    protected boolean _eventQueueSizeWarning = false;

    /**
     * An int id used to identify each batch.
     */
    protected int _batchId = 0;

    /**
     * A boolean verifying whether this <code>GatewayEventDispatcher</code> is
     * running.
     */
    protected volatile boolean _isStopped = false;

    /**
     * A boolean verifying whether this <code>GatewayEventDispatcher</code> is
     * paused.
     */
    protected volatile boolean _isPaused = false;

    /**
     * A lock object used to control pausing this dispatcher
     */
    protected final Object _pausedLock = new Object();

    /**
     * Constructor.
     *
     * @param gateway
     *          The <code>Gateway</code> on behalf of whom this dispatcher
     *          dispatches events.
     */
    protected GatewayEventProcessor(GatewayImpl gateway) {
      super(LogWriterImpl.createThreadGroup("Gateway Event Processor from "
          + gateway.getGatewayHubId() + " to " + gateway.getId(), gateway
          .getLogger()), "Gateway Event Processor from "
          + gateway.getGatewayHubId() + " to " + gateway.getId());

      this._gateway = gateway;
      this.unprocessedEvents = new LinkedHashMap();
      this.unprocessedTokens = new LinkedHashMap();

      // Create the event conflator
      //this._eventConflator = new BridgeEventConflator(this._logger);

      this._eventQueue = this.initializeMessageQueue();

      this._eventDispatcher = this.initializeEventDispatcher();
      setDaemon(true);
    }

    /**
     * Stops the dispatcher from dispatching events . The dispatcher will stay
     * alive until its queue is empty or a number of peeks is reached.
     *
     * @see GatewayImpl#MAXIMUM_SHUTDOWN_PEEKS
     */
    protected void stopProcessing()
    {
      if (this.isAlive()) {


      if (getLogger().fineEnabled()) {
        getLogger().fine(this + ":Notifying the dispatcher to terminate");
      }

      // If this is the primary, stay alive until the queue is empty
      // or a number of peeks is reached.
      if (getGateway().getPrimary()) {
        int numberOfPeeks = 0;
        try {
          try {
            while (this._eventQueue.peek() != null
                && numberOfPeeks != MAXIMUM_SHUTDOWN_PEEKS) {
              numberOfPeeks++;
              try {
                Thread.sleep(100);
              }
              catch (InterruptedException e) {/* ignore */
                // TODO this looks like a bug
              }
            }
          }
          catch (InterruptedException e) {
            /*
             * ignore, this will never be thrown by
             * SingleWriteSingleReadRegionQueue
             */
            // TODO if this won't be thrown, assert something here.
          }
        }
        catch (CacheException e) {/* ignore */
        }
      }
      setIsStopped(true);
      
      if (this.isAlive()) {
        this.interrupt();
        if (getLogger().fineEnabled()) {
          getLogger().fine(this
              + ":Joining with the dispatcher thread upto limit of 5 seconds");
        }
        try {
          this.join(5000); // wait for our thread to stop
          if (this.isAlive()) {
            getLogger().warning(
                LocalizedStrings.GatewayImpl_0_DISPATCHER_STILL_ALIVE_EVEN_AFTER_JOIN_OF_5_SECONDS,
                this);
            // if the server machine crashed or there was a nic failure, we need
            // to terminate the socket connection now to avoid a hang when closing
            // the connections later
            if (_eventDispatcher instanceof GatewayEventRemoteDispatcher) {
              GatewayEventRemoteDispatcher g = (GatewayEventRemoteDispatcher)_eventDispatcher;
              g.destroyConnection();
            }
          }
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          if (getLogger().warningEnabled()) {
            getLogger().warning(
              LocalizedStrings.GatewayImpl_0_INTERRUPTEDEXCEPTION_IN_JOINING_WITH_DISPATCHER_THREAD,
              this);
          }
        }

      }
      }
      
      // Close the processor's queue. This preserves the disk files if persistent.
      closeProcessor();
      
      if (getLogger().fineEnabled()) {
        getLogger().fine("Stopped dispatching: " + this);
      }
    }

    protected void pauseDispatching()
    {
      if (this._isPaused) {
        return;
      }
      this._isPaused = true;
    }

    protected void resumeDispatching()
    {
      if (!this._isPaused) {
        return;
      }
      this._isPaused = false;

      // Notify thread to resume
      if (getLogger().fineEnabled()) {
        getLogger().fine(this + ": Resuming processing");
      }
      synchronized (this._pausedLock) {
        this._pausedLock.notifyAll();
      }
    }

    protected void closeProcessor()
    {
      if (getLogger().fineEnabled()) {
        getLogger().fine("Closing dispatcher");
      }
      try {
        try {
          if (this._eventQueue.peek() != null) {
            getLogger()
                .warning(LocalizedStrings.GatewayImpl_DESTROYING_GATEWAYEVENTDISPATCHER_WITH_ACTIVELY_QUEUED_DATA);
          }
        }
        catch (InterruptedException e) {
          /*
           * ignore, this will never be thrown by
           * SingleWriteSingleReadRegionQueue
           */
          // TODO if this won't be thrown, assert it.
        }
      }
      catch (CacheException ignore) {
        // just checking in case we should log a warning
      }
      finally {
        this._eventQueue.close();
        if (getLogger().fineEnabled()) {
          getLogger().fine("Closed dispatcher");
        }
      }
    }

    private void destroyQueuePersistenceFiles() {
      //Destroy a files of a persistent region
      ((SingleWriteSingleReadRegionQueue)this._eventQueue).destroyPersistentFiles(getGateway().getCache());
    }
    
    /**
     * Returns whether this dispatcher is stopped (not dispatching any more
     * events)
     *
     * @return whether this dispatcher is stopped (not dispatching any more
     *         events)
     */
    protected boolean isStopped()
    {
      return this._isStopped;
    }

    protected void setIsStopped(boolean isStopped) {
      this._isStopped = isStopped;
    }

    protected boolean getIsStopped() {
      return this._isStopped;
    }

    protected boolean getIsPaused() {
      return this._isPaused;
    }

    /**
     * Increment the batch id. This method is not synchronized because this
     * dispatcher is the caller
     */
    protected void incrementBatchId()
    {
      // If _batchId + 1 == maximum, then roll over
      if (this._batchId + 1 == Integer.MAX_VALUE) {
        this._batchId = -1;
      }
      this._batchId++;
    }

    /**
     * Reset the batch id. This method is not synchronized because this
     * dispatcher is the caller
     */
    protected void resetBatchId()
    {
      this._batchId = 0;
    }

    /**
     * Returns the current batch id to be used to identify the next batch.
     *
     * @return the current batch id to be used to identify the next batch
     */
    protected int getBatchId()
    {
      return this._batchId;
    }

    protected void eventQueueRemove(int numberOfEventsToRemove) throws CacheException {
      this._eventQueue.remove(numberOfEventsToRemove);
    }

    protected Object eventQueueTake() throws CacheException, InterruptedException {
      return this._eventQueue.take();
    }

    protected int eventQueueSize() {
      return this._eventQueue.size();
    }

    protected GatewayImpl getGateway() {
      return this._gateway;
    }

    /**
     * Returns this processor's <code>LogWriterImpl</code>.
     * @return this processor's <code>LogWriterImpl</code>
     */
    public LogWriterI18n getLogger() {
      return getGateway()._logger;
    }

    /**
     * Initializes this processor's message queue. This queue is a
     * <code>RegionQueue</code>
     *
     * @see RegionQueue
     */
    protected RegionQueue initializeMessageQueue()
    {
      RegionQueue queue = null;

      // Create the region name
      StringBuilder regionNameBuffer = new StringBuilder();
      regionNameBuffer
        .append(getGateway().getGatewayHubId())
        .append('_')
        .append(getGateway().getId())
        .append("_EVENT_QUEUE");
      String regionName = regionNameBuffer.toString();

      // Define the cache listener if this is a secondary
      CacheListener listener = null;
      if (!getGateway().getPrimary()) {
        listener = new SecondaryGatewayListener(this);
        initializeListenerExecutor();
      }

      // Create the region queue
      queue = new SingleWriteSingleReadRegionQueue(getGateway().getCache(), regionName, getGateway().getQueueAttributes(), listener, getGateway().getStatistics());
      // this is deadcode to fix bug 37575
//       if (!getGateway().getPrimary()) {
//         // We need to make sure and account for entries obtained from gii.
//         // This is currently done with a simple iteration which might also
//         // iterator over entries that invoked a listener but that is ok since
//         // the hub can not yet call distribute on us because our constructor
//         // has not yet returned
//         Region qRegion = queue.getRegion();
//         Collection regionValues = qRegion.values();
//         Iterator i = regionValues.iterator();
//         ArrayList al = new ArrayList(regionValues.size());
//         while (i.hasNext()) {
//           Object o = i.next();
//           // Need instanceof check because headkey/tailkey are in here too
//           if (o instanceof GatewayEventImpl) {
//             al.add(o);
//           }
//         }
//         if (al.size() > 0) {
//           handlePrimaryEvents(al);
//         }
//       }

      if (getLogger().fineEnabled()) {
        getLogger().fine("Created queue: " + queue);
      }
      return queue;
    }

    protected GatewayEventDispatcher initializeEventDispatcher() {
      GatewayEventDispatcher dispatcher = null;
      if (getGateway().hasEndpoints()) {
        dispatcher = new GatewayEventRemoteDispatcher(this);
      } else {
        dispatcher = new GatewayEventCallbackDispatcher(this);
      }
      return dispatcher;
    }

    private boolean stopped() {
      if (this._isStopped) {
        return true;
      }
      if (GatewayImpl.this.stopper.cancelInProgress() != null) {
        return true;
      }
      return false;
    }

    /**
     * @return false on failure
     */
    private boolean waitForPrimary() {

      try {
        getGateway().waitToBecomePrimary();
      }
      catch (InterruptedException e) {
        // No need to set the interrupt bit, we're exiting the thread.
        if (!stopped()) {
          getLogger().severe(
              LocalizedStrings.GatewayImpl_AN_INTERRUPTEDEXCEPTION_OCCURRED_THE_THREAD_WILL_EXIT,
              e);
        }
        shutdownListenerExecutor();
        return false;
      }
      try {
        shutdownListenerExecutor();

        // Don't allow conserve-sockets = false so that ordering is preserved.
        DistributedSystem.setThreadsSocketPolicy(true);

        // Once notification has occurred, handle failover
        if (!stopped()) {
          handleFailover();
        }
      }
      catch (RegionDestroyedException e) {
        // This happens during handleFailover
        // because the region on _eventQueue can be closed.
        if (!stopped()) {
          getLogger().fine("Terminating due to " + e);
        }
        return false;
      }
      catch (CancelException e) {
        if (!stopped()) {
          getLogger().fine("Terminating due to " + e);
        }
        return false;
      }
      finally {
        // This is a good thing even on termination, because it ends waits
        // on the part of other threads.
        completeFailover();
      }
      return true;
    }

    private void processQueue() {
      GatewayQueueAttributes gqa = getGateway().getQueueAttributes();
      final int batchSize = gqa.getBatchSize();
      final int batchTimeInterval = gqa.getBatchTimeInterval();
      final int alertThreshold = gqa.getAlertThreshold();
      final GatewayStats statistics = getGateway().getStatistics();
      List events = null;
      for (;;) {
        if (stopped()) {
          break;
        }

        try {
          // Check if paused. If so, wait for resumption
          if (this._isPaused) {
            waitForResumption();
          }

          // Peek a batch
          if (getLogger().fineEnabled()) {
            getLogger().fine("Attempting to peek a batch of " + batchSize
                + " events");
          }
          for (;;) {
            // check before sleeping
            if (stopped()) {
              break;
            }

            // Check if paused. If so, wait for resumption
            if (this._isPaused) {
              waitForResumption();
            }

            // sleep a little bit, look for events
            boolean interrupted = Thread.interrupted();
            try {
              events = this._eventQueue.peek(batchSize, batchTimeInterval);
            }
            catch (InterruptedException e) {
              interrupted = true;
              GatewayImpl.this.getCancelCriterion().checkCancelInProgress(e);
              continue; // keep trying
            }
            finally {
              if (interrupted) {
                Thread.currentThread().interrupt();
              }
            }

            if (events.isEmpty()) {
              continue; // nothing to do!
            }
            // before dispatching the events, initialize the keys if required
            Iterator<?> it = events.iterator();
            while (it.hasNext()) {
              Object o = it.next();
              if (o != null && o instanceof GatewayEventImpl) {
                ((GatewayEventImpl)o).initializeKey();
              }
            }
            logBatchFine("During normal processing, dispatching the following ",
                events);
            boolean success = this._eventDispatcher.dispatchBatch(events, true);
            if (getLogger().fineEnabled()) {
              getLogger().fine("During normal processing, "
                  + (success ? "" : "un") + "successfully dispatched " +
                      events.size() + " events (batch #" + getBatchId() + ")" + " queue size=" + eventQueueSize());
            }

            // check again, don't do post-processing if we're stopped.
            if (stopped()) {
              break;
            }

            // If the batch is successfully processed, remove it from the queue.
            if (success) {
              // TODO - what to do if an exception occurs during remove()?
              eventQueueRemove(events.size());

              /*
               * FAILOVER TESTING CODE System.out.println(getName() + ": Removed " +
               * events.size() + " events from queue");
               */
              int queueSize = eventQueueSize();
              statistics.setQueueSize(queueSize);

              // Log an alert for each event if necessary
              if (alertThreshold > GatewayQueueAttributes.DEFAULT_ALERT_THRESHOLD) {
                it = events.iterator();
                long currentTime = System.currentTimeMillis();
                while (it.hasNext()) {
                  Object o = it.next();
                  if (o != null && o instanceof GatewayEventImpl) {
                    GatewayEventImpl ge = (GatewayEventImpl) o;
                    if (ge.getCreationTime()+alertThreshold < currentTime) {
                      getLogger().warning(
                        LocalizedStrings.GatewayImpl_EVENT_QUEUE_ALERT_OPERATION_0_REGION_1_KEY_2_VALUE_3_TIME_4,
                        new Object[] {
                          ge.getOperation(), ge.getRegionName(),
                          ge.getKey(), ge.getDeserializedValueForLogging(),
                          ge.getCallbackArgument(),
                          currentTime-ge.getCreationTime()});
                      statistics.incEventsExceedingAlertThreshold();
                    }
                  }
                }
              }

              if (this._eventQueueSizeWarning
                  && queueSize <= QUEUE_SIZE_THRESHOLD) {
               getLogger().info(
                 LocalizedStrings.GatewayImpl_THE_EVENT_QUEUE_SIZE_HAS_DROPPED_BELOW_THE_THRESHOLD_0,
                 QUEUE_SIZE_THRESHOLD);
                this._eventQueueSizeWarning = false;
              }
              incrementBatchId();
            } // successful batch
            else { // The batch was unsuccessful.
              statistics.incBatchesRedistributed();

              // Set posDup flag on each event in the batch
              it = events.iterator();
              while (it.hasNext() && !this._isStopped) {
                Object o = it.next();
                if (o != null && o instanceof GatewayEventImpl) {
                  GatewayEventImpl ge = (GatewayEventImpl) o;
                  ge.setPossibleDuplicate(true);
                }
              }
            } // unsuccessful batch
            if (getLogger().fineEnabled()) {
              getLogger().fine("Finished processing events (batch #"
                  + getBatchId() + ")");
            }
          } // for
        }
        catch (RegionDestroyedException e) {
          if (stopped()) {
            return; // don't print message
          }
          getLogger().info(LocalizedStrings.
              GatewayImpl_TERMINATED_DUE_TO_REGIONDESTROYEDEXCEPTION);
          setIsStopped(true);
        }
        catch (CancelException e) {
//          getLogger().info(LocalizedStrings.DEBUG, "caught cancel exception", e);
          setIsStopped(true);
        }
        catch (Throwable e) {
          Error err;
          if (e instanceof Error && SystemFailure.isJVMFailureError(
              err = (Error)e)) {
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

          // Well, OK.  Some strange nonfatal thing.
          if (stopped()) {
            return; // don't complain, just exit.
          }

          // We'll log it but continue on with the next batch.
          getLogger().severe(
              LocalizedStrings.GatewayImpl_AN_EXCEPTION_OCCURRED_THE_DISPATCHER_WILL_CONTINUE, e);
        }
      } // for
    }

    /**
     * Runs the dispatcher by taking a batch of messages from the queue and
     * sending them to the corresponding <code>Gateway</code>
     */

    @Override
    public void run()
    {
      try {
        // If this is not a primary gateway, wait for notification
        if (!getGateway().getPrimary()) {
          if (!waitForPrimary()) {
            return;
          }
        }
        else {
          // we are the primary so mark failover as being completed
          completeFailover();
        }

        // Begin to process the message queue after becoming primary
        if (getLogger().fineEnabled()) {
          getLogger().fine("Beginning to process the message queue");
        }

        /*
         * FAILOVER TESTING CODE this._logger.warning("Beginning to process the
         * message queue");
         */

        if (!getGateway().getPrimary()) {
          getLogger().warning(LocalizedStrings.
              GatewayImpl_ABOUT_TO_PROCESS_THE_MESSAGE_QUEUE_BUT_NOT_THE_PRIMARY);
        }

        // Sleep for a bit. The random is so that if several of these are
        // started at once, they will stagger a bit.
        try {
          Thread.sleep(new Random().nextInt(1000));
        }
        catch (InterruptedException e) {
          // no need to set the interrupt bit or throw an exception; just exit.
          return;
        }

        processQueue();
      }
      catch (CancelException e) {
        if (!this._isStopped) {
          getLogger().info( LocalizedStrings.
            GatewayImpl_A_CANCELLATION_OCCURRED_STOPPING_THE_DISPATCHER);
          setIsStopped(true);
        }
      }
      catch (Throwable e) {
        Error err;
        if (e instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)e)) {
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
        getLogger().severe( LocalizedStrings.
            GatewayImpl_MESSAGE_DISPATCH_FAILED_DUE_TO_UNEXPECTED_EXCEPTION, e);
      }

      //TODO:ASIF:CHECK IF CODE IS NEEDED FROM CS43_ha BRANCH ,IF WE NEED TO
      // TAKE THE
      //CODE OF TAKING UP RESIDUAL DATA
    }

    // dispatchBatch moved to GatewayEventRemoteDispatcher

    // dispatchBatch(List events) moved to GatewayEventRemoteDispatcher

    /**
     * Handle failover. This method is called when a secondary
     * <code>Gateway</code> becomes a primary <code>Gateway</code>.
     *
     * Once this secondary becomes the primary, it must:
     * <ul>
     * <li>Remove the queue's CacheListener
     * <li>Process the map of unprocessed events (those it has seen but the
     * previous primary had not yet processed before it crashed). These will
     * include both queued and unqueued events. Remove from the queue any events
     * that were already sent
     * <li>Clear the unprocessed events map
     * </ul>
     */
    protected void handleFailover()
    {
      /* We must hold this lock while we're processing these
       * maps to prevent us from handling a secondary event while failover
       * occurs. See enqueueEvent
       */
      synchronized (this.unprocessedEventsLock) {
        // Remove the queue's CacheListener
        this._eventQueue.removeCacheListener();
        this.unprocessedTokens = null;

        // Process the map of unprocessed events
        getLogger().info(LocalizedStrings.GatewayImpl_GATEWAY_FAILOVER_INITIATED_PROCESSING_0_UNPROCESSED_EVENTS, this.unprocessedEvents.size());
        GatewayStats statistics = getGateway().getStatistics();
        statistics.setQueueSize(eventQueueSize()); // to capture an initial size
        if (!this.unprocessedEvents.isEmpty()) {
          // do a reap for bug 37603
          reapOld(statistics, true); // to get rid of timed out events
          // now iterate over the region queue to figure out what unprocessed
          // events are already in the queue
          {
            Iterator it = this._eventQueue.getRegion().values().iterator();
            while (it.hasNext() && !stopped()) {
              Object o = it.next();
              if (o != null && o instanceof GatewayEventImpl) {
                GatewayEventImpl ge = (GatewayEventImpl)o;
                if (this.unprocessedEvents.remove(ge.getEventId()) != null) {
                  if (this.unprocessedEvents.isEmpty()) {
                    break;
                  }
                }
              }
            }
          }
          // now for every unprocessed event add it to the end of the queue
          {
            getLogger().info(LocalizedStrings.GatewayImpl_GATEWAY_FAILOVER_INITIATED_ADDING_0_UNPROCESSED_EVENTS, this.unprocessedEvents.size());
            Iterator it = this.unprocessedEvents.values().iterator();
            while (it.hasNext() && !stopped()) {
              EventWrapper ew = (EventWrapper)it.next();
              GatewayEventImpl gatewayEvent = ew.event;
              try {
                // Initialize each gateway event. This initializes the key,
                // value
                // and callback arg based on the EntryEvent.
                gatewayEvent.initialize();
              }
              catch (IOException e) {
                getLogger().warning(
                  LocalizedStrings.GatewayImpl_EVENT_FAILED_TO_BE_INITIALIZED_0,
                  gatewayEvent, e);
              }
              // Verify that they GatewayEventCallbackArgument is initialized.
              // If not,
              // initialize it. It won't be initialized if a client to this
              // GatewayHub
              // VM was the creator of this event. This Gateway will be the
              // first one
              // to process it. If will be initialized if this event was sent to
              // this
              // Gateway from another GatewayHub (eityher directly or
              // indirectly).
              GatewayEventCallbackArgument geca = gatewayEvent
                .getGatewayCallbackArgument();
              if (geca.getOriginatingGatewayHubId() == null) {
                geca
                    .setOriginatingGatewayHubId(getGateway().getGatewayHubId());
                geca.initializeRecipientGateways(getGateway().getGatewayHub()
                    .getGatewayIds());
              }
              try {
                if (getLogger().fineEnabled()) {
                  getLogger().fine(getGateway() + ": Queueing unprocessed event: " + gatewayEvent);
                }
                queuePrimaryEvent(gatewayEvent);
              }
              catch (IOException ex) {
                if (!stopped()) {
                  getLogger().warning(
                    LocalizedStrings.GatewayImpl_EVENT_DROPPED_DURING_FAILOVER_0,
                    gatewayEvent, ex);
                }
              }
              catch (CacheException ex) {
                if (!stopped()) {
                  getLogger().warning(
                    LocalizedStrings.GatewayImpl_EVENT_DROPPED_DURING_FAILOVER_0,
                    gatewayEvent, ex);
                }
              }
            }
          }
          // Clear the unprocessed events map
          statistics.clearUnprocessedMaps();
        }

        // Iterate the entire queue and mark all events as possible
        // duplicate
        if (getLogger().infoEnabled()) {
          getLogger().info(LocalizedStrings.GatewayImpl_0__MARKING__1__EVENTS_AS_POSSIBLE_DUPLICATES, new Object[] {getGateway(), Integer.valueOf(this._eventQueue.size())});
        }
        Iterator it = this._eventQueue.getRegion().values().iterator();
        while (it.hasNext() && !stopped()) {
          Object o = it.next();
          if (o != null && o instanceof GatewayEventImpl) {
            GatewayEventImpl ge = (GatewayEventImpl)o;
            ge.setPossibleDuplicate(true);
          }
        }

        this.unprocessedEvents = null;

        getLogger().info(LocalizedStrings.GatewayImpl_GATEWAY_FAILOVER_COMPLETED);
      } // synchronized
    }

    /**
     * Add the input object to the event queue
     */
    protected void enqueueEvent(EnumListenerEvent operation, EntryEvent event)
        throws IOException, CacheException
    {
      // There is a case where the event is serialized for processing. The
      // region is not
      // serialized along with the event since it is a transient field. I
      // created an
      // intermediate object (GatewayEventImpl) to avoid this since the region
      // name is
      // used in the sendBatch method, and it can't be null. See EntryEventImpl
      // for details.
      GatewayEventImpl gatewayEvent = null;

      boolean isPrimary = getGateway().getPrimary();
      if(!isPrimary) {
        //Fix for #40615. We need to check if we've now become the primary
        //while holding the unprocessedEventsLock. This prevents us from failing
        //over while we're processing an event as a secondaryEvent.
        synchronized (unprocessedEventsLock) {
          // Test whether this gateway is the primary.
          if (getGateway().getPrimary()) {
            isPrimary = true;
          }
          else {
            // If it is not, create an uninitialized GatewayEventImpl and
            // put it into the map of unprocessed events.
            gatewayEvent = new GatewayEventImpl(operation, event, false);
            handleSecondaryEvent(gatewayEvent);
          }
        }
      }
      if(isPrimary) {
        waitForFailoverCompletion();
        // If it is, create and enqueue an initialized GatewayEventImpl
        gatewayEvent = new GatewayEventImpl(operation, event);
        queuePrimaryEvent(gatewayEvent);
      }
    }

    private void queuePrimaryEvent(GatewayEventImpl gatewayEvent)
        throws IOException, CacheException
    {
      // Queue the event
      GatewayStats statistics = getGateway().getStatistics();
      if (getLogger().fineEnabled()) {
        getLogger().fine(getGateway() + ": Queueing event ("
            + (statistics.getEventsQueued() + 1) + "): " + gatewayEvent);
      }
      long start = statistics.startTime();
      try {
        if(shouldEnqueue(gatewayEvent)) {
          this._eventQueue.put(gatewayEvent);
        }
      }
      catch (InterruptedException e) {
        //Asif Not expected from SingleWriteSingleReadRegionQueue as it does not throw
        //InterruptedException. But since both HARegionQueue and SingleReadSingleWriteRegionQueue
        //extend RegionQueue , it has to handle InterruptedException
        Thread.currentThread().interrupt();
        GatewayImpl.this.getCancelCriterion().checkCancelInProgress(e);
      }

      statistics.endPut(start);
      if (getLogger().fineEnabled()) {
        getLogger().fine(getGateway() + ": Queued event ("
            + (statistics.getEventsQueued()) + "): " + gatewayEvent);
      }
      //this._logger.warning(getGateway() + ": Queued event (" +
      // (statistics.getEventsQueued()) + "): " + gatewayEvent + " queue size: "
      // + this._eventQueue.size());
      /*
       * FAILOVER TESTING CODE System.out.println(getName() + ": Queued event (" +
       * (statistics.getEventsQueued()) + "): " + gatewayEvent.getId());
       */
      int queueSize = eventQueueSize();
      statistics.setQueueSize(queueSize);
      if (!this._eventQueueSizeWarning && queueSize >= QUEUE_SIZE_THRESHOLD) {
        getLogger().warning(
          LocalizedStrings.GatewayImpl_0_THE_EVENT_QUEUE_SIZE_HAS_REACHED_THE_THRESHOLD_1,
          new Object[] {getGateway(), Integer.valueOf(QUEUE_SIZE_THRESHOLD)});
        this._eventQueueSizeWarning = true;
      }
    }

    /**
     * Test to see if we should enqueue a gateway event.
     */
    private boolean shouldEnqueue(GatewayEventImpl gatewayEvent) {
      LocalRegion region = gatewayEvent.getRegion();
      //GatewayEventListeners should not see events on Meta Regions
      //This fixes 43978
      return hasEndpoints() || !region.isUsedForMetaRegion();
    }

    /**
     * Update an unprocessed event in the unprocessed events map. This method is
     * called by a secondary <code>Gateway</code> to store a gateway event
     * until it is processed by a primary <code>Gateway</code>. The
     * complexity of this method is the fact that the event could be processed
     * first by either the primary or secondary <code>Gateway</code>.
     *
     * If the primary processes the event first, the map will already contain an
     * entry for the event (through
     * {@link com.gemstone.gemfire.internal.cache.GatewayImpl.SecondaryGatewayListener#afterDestroy}).
     * When the secondary processes the event, it will remove it from the map.
     *
     * If the secondary processes the event first, it will add it to the map.
     * When the primary processes the event (through
     * {@link com.gemstone.gemfire.internal.cache.GatewayImpl.SecondaryGatewayListener#afterDestroy}),
     * it will then be removed from the map.
     *
     * @param gatewayEvent
     *          The event being processed
     */
    protected void handleSecondaryEvent(GatewayEventImpl gatewayEvent)
    {
      basicHandleSecondaryEvent(gatewayEvent);
    }

    /**
     * Update an unprocessed event in the unprocessed events map. This method is
     * called by a primary <code>Gateway</code> (through
     * {@link com.gemstone.gemfire.internal.cache.GatewayImpl.SecondaryGatewayListener#afterCreate})
     * to notify the secondary <code>Gateway</code> that an event has been
     * added to the queue. 
     * Once an event has been added to the queue, the
     * secondary no longer needs to keep track of it in the unprocessed events
     * map. The complexity of this method is the fact that the event could be
     * processed first by either the primary or secondary <code>Gateway</code>.
     *
     * If the primary processes the event first, the map will not contain an
     * entry for the event. It will be added to the map in this case so that
     * when the secondary processes it, it will know that the primary has
     * already processed it, and it can be safely removed.
     *
     * If the secondary processes the event first, the map will already contain
     * an entry for the event.
     * In this case, the event can be removed from the map.
     *
     * @param gatewayEvent
     *          The event being processed
     */
    protected void handlePrimaryEvent(final GatewayEventImpl gatewayEvent)
    {
      Executor my_executor = this._executor;
      if (my_executor == null) {
        // should mean we are now primary
        return;
      }
      try {
        my_executor.execute(new Runnable() {
            public void run() {
              basicHandlePrimaryEvent(gatewayEvent);
            }
          });
      } catch (RejectedExecutionException ex) {
        throw ex;
      }
    }

    /**
     * Called when the primary gets rid of an event from the queue
     * This method added to fix bug 37603
     */
    protected void handlePrimaryDestroy(final GatewayEventImpl gatewayEvent)
    {
      Executor my_executor = this._executor;
      if (my_executor == null) {
        // should mean we are now primary
        return;
      }
      try {
        my_executor.execute(new Runnable() {
            public void run() {
              basicHandlePrimaryDestroy(gatewayEvent);
            }
          });
      } catch (RejectedExecutionException ex) {
        throw ex;
      }
    }

//     protected void handlePrimaryEvents(final ArrayList eventList)
//     {
//       Executor my_executor = this._executor;
//       if (my_executor == null) {
//         // should mean we are now primary
//         return;
//       }
//       try {
//         my_executor.execute(new Runnable() {
//           public void run()
//           {
//             final int eventCount = eventList.size();
//             final Long to = Long.valueOf(System.currentTimeMillis() + TOKEN_TIMEOUT);
//             final Map my_unprocessedEvents = unprocessedEvents;
//             if (my_unprocessedEvents == null)
//               return;
//             synchronized (my_unprocessedEvents) {
//               if (_unprocessedEvents == null)
//                 return;
//               // now we can safely use the _unprocessedEvents field
//               for (int i = 0; i < eventCount; i++) {
//                 GatewayEventImpl gatewayEvent = (GatewayEventImpl)eventList
//                     .get(i);
//                 handleEvent(gatewayEvent, true, to);
//               }
//             }
//           }
//           });
//       } catch (RejectedExecutionException ex) {
//         throw ex;
//       }
//     }

    /**
     * Just remove the event from the unprocessed events map if it is present.
     * This method added to fix bug 37603
     */
    protected void basicHandlePrimaryDestroy(final GatewayEventImpl gatewayEvent) {
      if (getPrimary()) {
        // no need to do anything if we have become the primary
        return;
      }
      GatewayStats statistics = getGateway().getStatistics();
      // Get the event from the map
      synchronized (unprocessedEventsLock) {
        if (this.unprocessedEvents == null)
          return;
        // now we can safely use the unprocessedEvents field
        Object v = this.unprocessedEvents.remove(gatewayEvent.getEventId());
        if (v != null) {
          statistics.incUnprocessedEventsRemovedByPrimary();
        }
      }
    }

    protected void basicHandlePrimaryEvent(final GatewayEventImpl gatewayEvent) {
      if (getPrimary()) {
        // no need to do anything if we have become the primary
        return;
      }
      GatewayStats statistics = getGateway().getStatistics();
      // Get the event from the map
      synchronized (unprocessedEventsLock) {
        if (this.unprocessedEvents == null)
          return;
        // now we can safely use the unprocessedEvents field
        Object v = this.unprocessedEvents.remove(gatewayEvent.getEventId());

        if (v == null) {
          // first time for the event
          if (getLogger().finerEnabled()) {
            try {
              gatewayEvent.initialize();
            }
            catch (Exception e) {
            }
            if (getLogger().finerEnabled()) {
              getLogger().finer(GatewayImpl.this + ": fromPrimary "
                  + " event " + gatewayEvent.getEventId() + ":"
                  + gatewayEvent.getKey() + "->"
                  + deserialize(gatewayEvent.getValue())
                  + " added to unprocessed token map.");
            }
          }
          {
            Object mapValue = Long.valueOf(System.currentTimeMillis() + TOKEN_TIMEOUT);
            Object oldv = this.unprocessedTokens.put(gatewayEvent.getEventId(), mapValue);
            if (oldv == null) {
              statistics.incUnprocessedTokensAddedByPrimary();
            } else {
              // its ok for oldv to be non-null
              // this shouldn't happen anymore @todo add an assertion here
            }
          }
        }
        else {
          // already added by secondary (i.e. hub)
          // the secondary
          // gateway has already seen this event, and it can be safely
          // removed (it already was above)
          if (getLogger().finerEnabled()) {
            try {
              gatewayEvent.initialize();
            }
            catch (Exception e) {
            }
            getLogger().finer(GatewayImpl.this
                               + ": Primary create/update event "
                               + gatewayEvent.getEventId() + ":" + gatewayEvent.getKey()
                               + "->" + deserialize(gatewayEvent.getValue())
                               + " removed from unprocessed events map");
          }
          statistics.incUnprocessedEventsRemovedByPrimary();
        }
        reapOld(statistics, false);
      }
    }

    private void basicHandleSecondaryEvent(final GatewayEventImpl gatewayEvent) {
      GatewayStats statistics = getGateway().getStatistics();
      // Get the event from the map
      
        Assert.assertHoldsLock(unprocessedEventsLock, true);
        Assert.assertTrue(this.unprocessedEvents != null);
        // @todo add an assertion that !getPrimary()
        // now we can safely use the unprocessedEvents field
        Object v = this.unprocessedTokens.remove(gatewayEvent.getEventId());

        if (v == null) {
          // first time for the event
          if (getLogger().finerEnabled()) {
            try {
              gatewayEvent.initialize();
            }
            catch (Exception e) {
            }
            getLogger().finer(GatewayImpl.this + ": fromSecondary "
                + " event " + gatewayEvent.getEventId() + ":"
                + gatewayEvent.getKey() + "->"
                + deserialize(gatewayEvent.getValue())
                + " added to unprocessed events map.");
          }
          {
            Object mapValue = new EventWrapper(gatewayEvent);
            Object oldv = this.unprocessedEvents.put(gatewayEvent.getEventId(), mapValue);
            if (oldv == null) {
              statistics.incUnprocessedEventsAddedBySecondary();
            } else {
              // put old one back in
              this.unprocessedEvents.put(gatewayEvent.getEventId(), oldv);
              // already added by secondary (i.e. hub)
              if (getLogger().warningEnabled()) {
                try {
                  gatewayEvent.initialize();
                }
                catch (IOException e) {
                  getLogger().warning(
                    LocalizedStrings. GatewayImpl_EVENT_FAILED_TO_BE_INITIALIZED_0,
                    gatewayEvent, e);
                }
                getLogger().warning(
                  LocalizedStrings.GatewayImpl_0_THE_UNPROCESSED_EVENTS_MAP_ALREADY_CONTAINED_AN_EVENT_FROM_THE_HUB_1_SO_IGNORING_NEW_EVENT_2,
                  new Object[] {GatewayImpl.this, GatewayImpl.this.getGatewayHubId(), gatewayEvent});
              }
            }
          }
        }
        else {
          // token already added by primary already removed
          if (getLogger().finerEnabled()) {
            try {
              gatewayEvent.initialize();
            }
            catch (Exception e) {
            }
            getLogger().finer(GatewayImpl.this
                               + ": Secondary created event " + gatewayEvent.getEventId()
                               + ":" + gatewayEvent.getKey() + "->"
                               + deserialize(gatewayEvent.getValue())
                               + " removed from unprocessed token map");
          }
          statistics.incUnprocessedTokensRemovedBySecondary();
        }
        reapOld(statistics, false);
    }
    /**
     * When the Number of unchecked events exceeds this threshold and the number
     * of tokens in the map exceeds this threshold then a check will be done for
     * old tokens.
     */
    static private final int REAP_THRESHOLD = 1000;

    /*
     * How many events have happened without a reap check being done?
     */
    private int uncheckedCount = 0;

    /**
     * Call to check if a cleanup of tokens needs to be done
     */
    private void reapOld(final GatewayStats statistics, boolean forceEventReap)
    {
      synchronized (this.unprocessedEventsLock) {
        if (uncheckedCount > REAP_THRESHOLD) { // only check every X events
          uncheckedCount = 0;
          long now = System.currentTimeMillis();
          if (!forceEventReap && this.unprocessedTokens.size() > REAP_THRESHOLD) {
            Iterator it = this.unprocessedTokens.entrySet().iterator();
            int count = 0;
            while (it.hasNext()) {
              Map.Entry me = (Map.Entry)it.next();
              long meValue = ((Long)me.getValue()).longValue();
              if (meValue <= now) {
                // @todo log fine level message here
                // it has expired so remove it
                it.remove();
                count++;
              }
              else {
                // all done try again
                break;
              }
            }
            if (count > 0) {
              statistics.incUnprocessedTokensRemovedByTimeout(count);
            }
          }
          if (forceEventReap || this.unprocessedEvents.size() > REAP_THRESHOLD)  {
            Iterator it = this.unprocessedEvents.entrySet().iterator();
            int count = 0;
            while (it.hasNext()) {
              Map.Entry me = (Map.Entry)it.next();
              EventWrapper ew = (EventWrapper)me.getValue();
              if (ew.timeout <= now) {
                // @todo log fine level message here
                // it has expired so remove it
                it.remove();
                count++;
              }
              else {
                // all done try again
                break;
              }
            }
            if (count > 0) {
              statistics.incUnprocessedEventsRemovedByTimeout(count);
            }
          }
        }
        else {
          uncheckedCount++;
        }
      }
    }

    protected void waitForResumption() throws InterruptedException {
      synchronized (this._pausedLock) {
        if (!this._isPaused) {
          return;
        }
        if (getLogger().fineEnabled()) {
          getLogger().fine(this + ": Pausing processing");
        }
        while (this._isPaused) {
          this._pausedLock.wait();
        }
      }
    }

    /**
     * Logs a batch of events to the <code>LogWriterI18n</code>.
     *
     * @param events
     *          The batch of events to log
     **/
    protected void logBatchFine(String message, List events)
    {
      if (getLogger().fineEnabled()) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(message);
        buffer.append(events.size()).append(" events");
        buffer.append(" (batch #" + getBatchId());
        buffer.append("):\n");
        for (Iterator i = events.iterator(); i.hasNext();) {
          GatewayEventImpl ge = (GatewayEventImpl)i.next();
          buffer.append("\tEvent ").append(ge.getEventId()).append(":");
          buffer.append(ge.getKey()).append("->");
          buffer.append(ge.getDeserializedValueForLogging());
          buffer.append("\n");
        }
        getLogger().fine(buffer.toString());
      }
    }

    protected Object deserialize(byte[] serializedBytes)
    {
      Object deserializedObject = serializedBytes;
      // This is a debugging method so ignore all exceptions like
      // ClassNotFoundException
      try {
        deserializedObject = EntryEventImpl.deserialize(serializedBytes);
      }
      catch (Exception e) {
      }
      return deserializedObject;
    }

    @Override
    public String toString()
    {
      StringBuffer buffer = new StringBuffer();
      GatewayQueueAttributes gqa = getGateway().getQueueAttributesNoSync();
      buffer
        .append("GatewayEventProcessor[")
        .append("gatewayId=")
        .append(getGateway().getId())
        .append(";gatewayHubId=")
        .append(getGateway().getGatewayHubId());
      if (gqa.getDiskStoreName()!=null) {
        buffer.append(";diskStoreName=")
        .append(gqa.getDiskStoreName());
      } else {
        buffer.append(";overflowDirectory=")
        .append(gqa.getOverflowDirectory());
      }
      buffer
        .append(";batchSize=")
        .append(gqa.getBatchSize())
        .append(";batchTimeInterval=")
        .append(gqa.getBatchTimeInterval())
        .append(";batchConflation=")
        .append(gqa.getBatchConflation())
        .append(";enablePersistence=")
        .append(gqa.getEnablePersistence())
        .append("]");
      return buffer.toString();
    }

    private ExecutorService _executor;

    /**
     * Initialize the Executor that handles listener events. Only used by
     * non-primary gateways
     */
    private void initializeListenerExecutor()
    {
      // Create the ThreadGroups
      final ThreadGroup loggerGroup = LogWriterImpl.createThreadGroup(
          "Gateway Listener Group", getLogger());

      // Create the Executor
      ThreadFactory tf = new ThreadFactory() {
          public Thread newThread(Runnable command) {
            Thread thread =  new Thread(loggerGroup, command, "Queued Gateway Listener Thread");
            thread.setDaemon(true);
            return thread;
          }
        };
      LinkedBlockingQueue q = new LinkedBlockingQueue();
      this._executor = new ThreadPoolExecutor(1, 1/*max unused*/,
                                              120, TimeUnit.SECONDS, q, tf);
    }

    private void shutdownListenerExecutor()
    {
      if (this._executor != null) {
        this._executor.shutdown();
        this._executor = null;
      }
    }
  }

  /**
   * Class <code>SecondaryGatewayListener</code> is a
   * <code>CacheListener</code> that handles afterCreate events for the
   * <code>RegionQueue</code>. These afterCreate events are based on creates
   * from the primary <code>Gateway</code> queue. It also handles afterUpdate
   * which happens when the primary conflates a queue entry.
   *
   * @author Barry Oglesby
   *
   * @since 4.2
   */
  protected class SecondaryGatewayListener extends CacheListenerAdapter
   {

    /**
     * The <code>GatewayEventProcessor</code> used by this
     * <code>CacheListener</code> to process events.
     */
    private final GatewayEventProcessor _eventProcessor;

    protected SecondaryGatewayListener(GatewayEventProcessor eventProcessor) {
      this._eventProcessor = eventProcessor;
    }


    @Override
    public void afterCreate(EntryEvent event)
    {
      // fix bug 35730
      if (GatewayImpl.this.getPrimary()) {
        // The secondary has failed over to become the primary. There is a small
        // window where the secondary has become the primary, but the listener
        // is
        // still set. Ignore any updates to the map at this point. It is unknown
        // what the state of the map is. This may result in duplicate events
        // being sent.
        //GatewayImpl.this._logger.severe(GatewayImpl.this + ": XXXXXXXXX IS
        // PRIMARY BUT PROCESSING AFTER_DESTROY EVENT XXXXXXXXX: " + event);
        return;
      }

      // Send event to the event dispatcher
      GatewayEventImpl gatewayEvent = (GatewayEventImpl)event.getNewValue();
      this._eventProcessor.handlePrimaryEvent(gatewayEvent);
    }

    @Override
    public void afterDestroy(EntryEvent event) {
      // fix bug 37603
      if (GatewayImpl.this.getPrimary()) {
        return;
      }

      // Send event to the event dispatcher
      Object oldValue = event.getOldValue();
      if (oldValue instanceof GatewayEventImpl) {
        GatewayEventImpl gatewayEvent = (GatewayEventImpl)oldValue;
        this._eventProcessor.handlePrimaryDestroy(gatewayEvent);
      }
    }
  }
  /**
   * Has a reference to a GatewayEventImpl and has a timeout value.
   */
  private static class EventWrapper  {
    /**
     * Timeout events received from secondary after 5 minutes
     */
    static private final int EVENT_TIMEOUT
      = Integer.getInteger("Gateway.EVENT_TIMEOUT", 5 * 60 * 1000).intValue();
    public final long timeout;
    public final GatewayEventImpl event;
    public EventWrapper(GatewayEventImpl e) {
      this.event = e;
      this.timeout = System.currentTimeMillis() + EVENT_TIMEOUT;
    }
  }
}
