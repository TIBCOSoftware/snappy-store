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

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.management.Notification;
import javax.management.ObjectName;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ResourceEvent;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.management.AlreadyRunningException;
import com.gemstone.gemfire.management.AsyncEventQueueMXBean;
import com.gemstone.gemfire.management.CacheServerMXBean;
import com.gemstone.gemfire.management.DiskStoreMXBean;
import com.gemstone.gemfire.management.DistributedLockServiceMXBean;
import com.gemstone.gemfire.management.DistributedRegionMXBean;
import com.gemstone.gemfire.management.DistributedSystemMXBean;
import com.gemstone.gemfire.management.GatewayReceiverMXBean;
import com.gemstone.gemfire.management.GatewaySenderMXBean;
import com.gemstone.gemfire.management.LocatorMXBean;
import com.gemstone.gemfire.management.LockServiceMXBean;
import com.gemstone.gemfire.management.ManagementException;
import com.gemstone.gemfire.management.ManagerMXBean;
import com.gemstone.gemfire.management.MemberMXBean;
import com.gemstone.gemfire.management.RegionMXBean;
import com.gemstone.gemfire.management.internal.beans.ManagementAdapter;


/**
 * This is the concrete implementation of ManagementService
 * which is the gateway to various JMX operations over a GemFire
 * System
 *
 * @author Pivotal Software, Inc.
 * @since 7.0
 */
public final class SystemManagementService extends BaseManagementService {


  /**
   * The concrete implementation of DistributedSystem that provides
   * internal-only functionality.
   */

  private InternalDistributedSystem system;

  /**
   * log writer, or null if there is no distributed system available
   */
  private LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();

  /**
   * core component for distribution
   */

  private LocalManager localManager;

  /**
   * This is a notification hub to listen all the notifications emitted from all
   * the MBeans in a peer cache./cache server
   */
  private NotificationHub notificationHub;

  /**
   * Local Filter chain for local MBean filters
   */

  private LocalFilterChain localFilterChain;

  /**
   * whether the service is closed or not if cache is closed automatically this
   * service will be closed
   */
  private volatile boolean closed = false;

  /**
   * has the management service has started yet
   */
  private volatile boolean isStarted = false;

  /**
   * Adapter to interact with platform MBean server
   */
  private MBeanJMXAdapter jmxAdapter;


  private Cache cache;

  private FederatingManager federatingManager;

  private final ManagementAgent agent;

  private ManagementResourceRepo repo;

  /**
   * This membership listener will listen on membership events after the node
   * has transformed into a Managing node.
   */
  private ManagementMembershipListener listener;


  /**
   * Proxy aggregator to create aggregate MBeans e.g. DistributedSystem and DistributedRegion
   * GemFire comes with a default aggregator.
   */
  private List<ProxyListener> proxyListeners;
  
  /**
   * Contains list on GFXDMbean for aggregation when manager starts
   * 
   */
  private Map<ObjectName, GemFireXDMBean> gemFireXDMBeansHolder = new ConcurrentHashMap<ObjectName, GemFireXDMBean>();

  public static BaseManagementService newSystemManagementService(Cache cache) {
    return new SystemManagementService(cache).init();
  }

  protected SystemManagementService(Cache cache) {
    this.cache = cache;
    this.system = (InternalDistributedSystem) cache.getDistributedSystem();
    // This is a safe check to ensure Management service does not start for a
    // system which is disconnected.
    // Most likely scenario when this will happen is when a cache is closed and we are at this point.
    if (!system.isConnected()) {
      throw new DistributedSystemDisconnectedException(
          LocalizedStrings.InternalDistributedSystem_THIS_CONNECTION_TO_A_DISTRIBUTED_SYSTEM_HAS_BEEN_DISCONNECTED
              .toLocalizedString());
    }
    this.localFilterChain = new LocalFilterChain();
    this.jmxAdapter = new MBeanJMXAdapter();
    this.repo = new ManagementResourceRepo();


    this.notificationHub = new NotificationHub(repo);
    if (system.getConfig().getJmxManager() && system.getConfig().getJmxManagerPort() != 0) {
      this.agent = new ManagementAgent(system.getConfig(), system.getLogWriterI18n());
    } else {
      this.agent = null;
    }
    ManagementFunction function = new ManagementFunction(notificationHub);
    FunctionService.registerFunction(function);
    this.proxyListeners = new CopyOnWriteArrayList<ProxyListener>();
  }

  /**
   * This method will initialize all the internal components for Management and
   * Monitoring
   *
   * It will a)start an JMX connectorServer b) create a notification hub
   * c)register the ManagementFunction
   */
  private SystemManagementService init() {

    try {
      this.localManager = new LocalManager(repo, system, this,cache);
      this.localManager.startManager();
      this.listener = new ManagementMembershipListener(this, logger);
      system.getDistributionManager().addMembershipListener(listener);
      isStarted = true;
      return this;
    } catch (CancelException e) {
      // Rethrow all CancelExceptions (fix for defect 46339)
      throw e;
    } catch (Exception e) {
      // Wrap all other exceptions as ManagementExceptions
      logger.error(e);
      throw new ManagementException(e);
    }

  }

  /**
   *For internal Use only
   */
  public LocalManager getLocalManager() {
    return localManager;
  }

  public NotificationHub getNotificationHub() {
    return notificationHub;
  }

  public FederatingManager getFederatingManager() {
    return federatingManager;
  }

  public MBeanJMXAdapter getJMXAdapter() {
    return jmxAdapter;
  }

  public boolean isStartedAndOpen() {
    if (!isStarted) {
      return false;
    }
    if (closed) {
      return false;
    }
    if (!system.isConnected()) {
      return false;
    }
    return true;
  }

  private void verifyManagementService() {
    if (!isStarted) {
      throw new ManagementException(
          ManagementStrings.Management_Service_MANAGEMENT_SERVICE_NOT_STARTED_YET
              .toLocalizedString());
    }
    if (!system.isConnected()) {
      throw new ManagementException(
          ManagementStrings.Management_Service_NOT_CONNECTED_TO_DISTRIBUTED_SYSTEM
              .toLocalizedString());
    }
    if (closed) {
      throw new ManagementException(
          ManagementStrings.Management_Service_MANAGEMENT_SERVICE_IS_CLOSED
              .toLocalizedString());
    }
  }

  @Override
  public void close() {
    synchronized (instances) {
      if (closed) {
        // its a no op, hence not logging any exception
        return;
      }
      if (logger.fineEnabled()) {
        logger.fine("Closing Management Service");
      }
      if(listener != null && system.isConnected()){
        system.getDistributionManager().removeMembershipListener(listener);
      }
      // Stop the Federating Manager first . It will ensure MBeans are not getting federated.
      // while un-registering
      if (federatingManager != null && federatingManager.isRunning()) {
        federatingManager.stopManager();
      }
      this.notificationHub.cleanUpListeners();
      jmxAdapter.cleanJMXResource();
      if (localManager.isRunning()) {
        localManager.stopManager();
      }
      if (this.agent != null && this.agent.isRunning()) {
        this.agent.stopAgent();
      }
      getGemFireCacheImpl().getJmxManagerAdvisor().broadcastChange();
      instances.remove(cache);
      localManager  = null;
      closed = true;
    }

  }

  @Override
  public <T> void federate(ObjectName objectName, Class<T> interfaceClass,
      boolean notificationEmitter) {
    verifyManagementService();
    String domain = objectName.getDomain();
    boolean gfxdSystem = GemFireCacheImpl.gfxdSystem();
    if (!domain.equalsIgnoreCase(ManagementConstants.OBJECTNAME__DEFAULTDOMAIN)
        && !domain.equalsIgnoreCase(ManagementConstants.OBJECTNAME__GEMFIREXDDOMAIN)) {
      throw new ManagementException(
          ManagementStrings.Management_Service__0__NOT_A__1__DOMAIN_MBEAN
              .toLocalizedString(objectName, gfxdSystem ? "GemFire/GemFireXD" : "GemFire"));
    }

    if(!jmxAdapter.isRegistered(objectName)){
      throw new ManagementException(
          ManagementStrings.Management_Service_MBEAN__0__NOT_REGISTERED_IN__1__DOMAIN
              .toLocalizedString(objectName, gfxdSystem ? "GemFire/GemFireXD" : "GemFire"));
    }
    if(notificationEmitter && !jmxAdapter.hasNotificationSupport(objectName)){
      throw new ManagementException(
          ManagementStrings.Management_Service_MBEAN__0__DOES_NOT_HAVE_NOTIFICATION_SUPPORT
              .toLocalizedString(objectName));
    }

    //All validation Passed. Now create the federation Component
    Object object = jmxAdapter.getMBeanObject(objectName);
    FederationComponent fedComp = new FederationComponent(object, objectName,
        interfaceClass, notificationEmitter);
    if (ManagementAdapter.refreshOnInit.contains(interfaceClass)) {
        fedComp.refreshObjectState(true);// Fixes 46387
    }
    localManager.markForFederation(objectName, fedComp);

    if (isManager()) {
      afterCreateProxy(objectName, interfaceClass, object, fedComp);
    }

  }

  @Override
  public CacheServerMXBean getLocalCacheServerMXBean(int serverPort) {
    CacheServerMXBean bean =  jmxAdapter.getClientServiceMXBean(serverPort);
    return bean;
  }

  @Override
  public long getLastUpdateTime(ObjectName objectName) {
    if (!isStartedAndOpen()) {
      return 0;
    }
    if (federatingManager == null) {
      return 0;

    } else if (!federatingManager.isRunning()) {
      return 0;
    }
    if (jmxAdapter.isLocalMBean(objectName)) {
      return 0;
    }
    return federatingManager.getLastUpdateTime(objectName);
  }

  @Override
  public DiskStoreMXBean getLocalDiskStoreMBean(String diskStoreName) {
    DiskStoreMXBean bean =  jmxAdapter.getLocalDiskStoreMXBean(diskStoreName);
    return bean;
  }

  @Override
  public LockServiceMXBean getLocalLockServiceMBean(String lockSreviceName) {
    LockServiceMXBean bean =  jmxAdapter.getLocalLockServiceMXBean(lockSreviceName);
    return bean;
  }

  @Override
  public RegionMXBean getLocalRegionMBean(String regionPath) {
    RegionMXBean bean = jmxAdapter.getLocalRegionMXBean(regionPath);
    return bean;
  }


  public <T> T getMBeanProxy(ObjectName objectName, Class<T> interfaceClass) {
    if (!isStartedAndOpen()) {
      return null;
    }
    if (federatingManager == null) {
      return null;

    } else if (!federatingManager.isRunning()) {
      return null;
    }

    return federatingManager.findProxy(objectName, interfaceClass);
  }

  @Override
  public MemberMXBean getMemberMXBean() {
    return jmxAdapter.getMemberMXBean();
  }

  @Override
  public Set<ObjectName> queryMBeanNames(DistributedMember member) {

    if (!isStartedAndOpen()) {
      return Collections.emptySet();
    }
    if (cache.getDistributedSystem().getDistributedMember().equals(member)) {
      return jmxAdapter.getLocalGemFireMBean().keySet();
    } else {
      if (federatingManager == null) {
        return Collections.emptySet();

      } else if (!federatingManager.isRunning()) {
        return Collections.emptySet();
      }
      return federatingManager.findAllProxies(member);
    }

  }

  @Override
  public ObjectName registerMBean(Object object, ObjectName objectName) {
    verifyManagementService();
    if (localFilterChain.isFiltered(objectName)) {
      return null;
    }
    return jmxAdapter.registerMBean(object, objectName, false);
  }

  public ObjectName registerInternalMBean(Object object, ObjectName objectName) {
    verifyManagementService();
    if (localFilterChain.isFiltered(objectName)) {
      return null;
    }
    return jmxAdapter.registerMBean(object, objectName, true);
  }

  public ObjectName registerGfxdMBean(Object object, ObjectName objectName) {
    verifyManagementService();
    if (localFilterChain.isFiltered(objectName)) {
      return null;
    }
    return jmxAdapter.registerGfxdMBean(object, objectName, false);
  }

  public ObjectName registerGfxdInternalMBean(Object object, ObjectName objectName) {
    verifyManagementService();
    if (localFilterChain.isFiltered(objectName)) {
      return null;
    }
    return jmxAdapter.registerGfxdMBean(object, objectName, true);
  }

  @Override
  public void unregisterMBean(ObjectName objectName) {
    if (!isStartedAndOpen()) {
      return;
    }
    verifyManagementService();

    if (isManager()) {
      FederationComponent removedObj = localManager.getFedComponents().get(objectName);
      if (removedObj != null) { // only for MBeans local to Manager , not
                                // proxies
        afterRemoveProxy(objectName, removedObj.getInterfaceClass(), removedObj.getMBeanObject(), removedObj);
      }
    }

    jmxAdapter.unregisterMBean(objectName);
    localManager.unMarkForFederation(objectName);
  }

  public Set<ObjectName> unregisterMBeanByPattern(ObjectName objectNamePattern) {
    Set<ObjectName> matchingMBeans = Collections.emptySet();
    if (!isStartedAndOpen()) {
      return matchingMBeans;
    }
    verifyManagementService();

    matchingMBeans = jmxAdapter.queryMBeans(objectNamePattern);

    for (ObjectName objectName : matchingMBeans) {
      if (isManager()) {
        FederationComponent removedObj = localManager.getFedComponents().get(objectName);
        if (removedObj != null) { // only for MBeans local to Manager , not proxies
          afterRemoveProxy(objectName, removedObj.getInterfaceClass(), removedObj.getMBeanObject(), removedObj);
        }
      }

      jmxAdapter.unregisterMBean(objectName);
      if (localManager != null) {
        localManager.unMarkForFederation(objectName);
      }
    }

    return matchingMBeans;
  }

  @Override
  public boolean isManager() {
    return isManagerCreated() && federatingManager.isRunning();
  }

  public boolean isManagerCreated() {
    if(!isStartedAndOpen()){
      return false;
    }
    return federatingManager != null;
  }

  @Override
  public void startManager() {
    if (!getGemFireCacheImpl().getSystem().getConfig().getJmxManager()) {
      // fix for 45900
      throw new ManagementException("Could not start the manager because the gemfire property \"jmx-manager\" is false.");
    }
    synchronized (instances) {
      verifyManagementService();
      if (federatingManager != null && federatingManager.isRunning()) {
        throw new AlreadyRunningException(
            ManagementStrings.Management_Service_MANAGER_ALREADY_RUNNING
                .toLocalizedString());
      }

      boolean needsToBeStarted = false;
      if (!isManagerCreated()) {
        createManager();
        needsToBeStarted = true;
      } else if (!federatingManager.isRunning()) {
        needsToBeStarted = true;
      }
      if (needsToBeStarted) {
        boolean started = false;
        try {
          system.handleResourceEvent(ResourceEvent.MANAGER_START, null);
          federatingManager.startManager();
          if (this.agent != null) {
            this.agent.startAgent();
          }
          getGemFireCacheImpl().getJmxManagerAdvisor().broadcastChange();
          started = true;
        } catch (RuntimeException e) {
          if(logger.errorEnabled()){
            logger.error(LocalizedStrings.DEBUG, "Jmx manager could not be started because " + e);
          }
          if(logger.fineEnabled()){
            logger.fine("Jmx manager could not be started.", e);
          }
          throw e;
        } catch (Error e) {
          if(logger.errorEnabled()){
            logger.error(LocalizedStrings.DEBUG, "Jmx manager could not be started because " + e);
          }
          throw e;
        } finally {
          if (!started) {
            if (federatingManager != null) {
              federatingManager.stopManager();
            }
            system.handleResourceEvent(ResourceEvent.MANAGER_STOP, null);
          }
        }
      }
    }
    
    if(logger.infoEnabled()){
      logger.info(LocalizedStrings.DEBUG, "Jmx manager successfully started ");
    }
  }

  private GemFireCacheImpl getGemFireCacheImpl() {
    return (GemFireCacheImpl)this.cache;
  }

  /**
   * Creates a Manager instance in stopped state.
   *
   */
  public boolean createManager() {
    synchronized (instances) {
      if (federatingManager != null) {
        return false;
      }
      system.handleResourceEvent(ResourceEvent.MANAGER_CREATE, null);
      // An initialised copy of federating manager
      federatingManager = new FederatingManager(jmxAdapter, repo, system, this, cache);
      getGemFireCacheImpl().getJmxManagerAdvisor().broadcastChange();
      return true;
    }
  }

  /**
   * It will stop the federating Manager and restart the Local cache operation
   */
  @Override
  public void stopManager() {
    synchronized (instances) {
      verifyManagementService();
      if (federatingManager != null) {
        federatingManager.stopManager();
        system.handleResourceEvent(ResourceEvent.MANAGER_STOP, null);
        getGemFireCacheImpl().getJmxManagerAdvisor().broadcastChange();
        
        if (this.agent != null && this.agent.isRunning()) {
          this.agent.stopAgent();
        }
      }
    }

  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public DistributedLockServiceMXBean getDistributedLockServiceMXBean(
      String lockServiceName) {
    return jmxAdapter.getDistributedLockServiceMXBean(lockServiceName);
  }

  @Override
  public DistributedRegionMXBean getDistributedRegionMXBean(String regionName) {
    return jmxAdapter.getDistributedRegionMXBean(regionName);
  }

  @Override
  public DistributedSystemMXBean getDistributedSystemMXBean() {
    return jmxAdapter.getDistributedSystemMXBean();
  }

  public void addProxyListener(ProxyListener listener) {
    this.proxyListeners.add(listener);
  }

  public void removeProxyListener(ProxyListener listener) {
    this.proxyListeners.remove(listener);
  }

  public List<ProxyListener> getProxyListeners() {
    return this.proxyListeners;
  }

  @Override
  public ManagerMXBean getManagerMXBean() {
    return jmxAdapter.getManagerMXBean();
  }

  @Override
  public ObjectName getCacheServerMBeanName(int serverPort,DistributedMember member) {
    return MBeanJMXAdapter.getClientServiceMBeanName(serverPort,member);
  }

  @Override
  public ObjectName getDiskStoreMBeanName(DistributedMember member, String diskName) {
     return MBeanJMXAdapter.getDiskStoreMBeanName(member, diskName);
  }

  @Override
  public ObjectName getDistributedLockServiceMBeanName(String lockService) {
     return MBeanJMXAdapter.getDistributedLockServiceName(lockService);
  }

  @Override
  public ObjectName getDistributedRegionMBeanName(String regionPath) {
      return MBeanJMXAdapter.getDistributedRegionMbeanName(regionPath);
  }

  @Override
  public ObjectName getDistributedSystemMBeanName() {
    return MBeanJMXAdapter.getDistributedSystemName();
  }

  @Override
  public ObjectName getGatewayReceiverMBeanName(DistributedMember member) {
    return MBeanJMXAdapter.getGatewayReceiverMBeanName(member);
  }

  @Override
  public ObjectName getGatewaySenderMBeanName(DistributedMember member,
      String gatwaySenderId) {
    return MBeanJMXAdapter.getGatewaySenderMBeanName(member, gatwaySenderId);
  }

  @Override
  public ObjectName getAsyncEventQueueMBeanName(DistributedMember member, String queueId) {
    return MBeanJMXAdapter.getAsycnEventQueueMBeanName(member, queueId);
  }

  @Override
  public ObjectName getLockServiceMBeanName(DistributedMember member,
      String lockServiceName) {
    return MBeanJMXAdapter.getLockServiceMBeanName(member, lockServiceName);
  }

  @Override
  public ObjectName getManagerMBeanName() {
    return MBeanJMXAdapter.getManagerName();
  }

  @Override
  public ObjectName getMemberMBeanName(DistributedMember member) {
    return MBeanJMXAdapter.getMemberMBeanName(member);
  }

  @Override
  public ObjectName getRegionMBeanName(DistributedMember member, String regionPath) {
    return MBeanJMXAdapter.getRegionMBeanName(member, regionPath);
  }

  @Override
  public GatewayReceiverMXBean getLocalGatewayReceiverMXBean() {
    return jmxAdapter.getGatewayReceiverMXBean();
  }

  @Override
  public GatewaySenderMXBean getLocalGatewaySenderMXBean(String senderId) {
    return jmxAdapter.getGatewaySenderMXBean(senderId);
  }

  @Override
  public AsyncEventQueueMXBean getLocalAsyncEventQueueMXBean(String queueId) {
    return jmxAdapter.getAsyncEventQueueMXBean(queueId);
  }

  @Override
  public ObjectName getLocatorMBeanName(DistributedMember member) {
    return MBeanJMXAdapter.getLocatorMBeanName(member);
  }

  @Override
  public LocatorMXBean getLocalLocatorMXBean() {
    return jmxAdapter.getLocatorMXBean();
  }

  public boolean afterCreateProxy(ObjectName objectName, Class interfaceClass, Object proxyObject,
      FederationComponent newVal) {
    //store Mbeans for GEmfireXD for later aggregation
    if(objectName.getDomain().equals(ManagementConstants.OBJECTNAME__GEMFIREXDDOMAIN)){      
      gemFireXDMBeansHolder.put(objectName, new GemFireXDMBean(objectName, interfaceClass, proxyObject, newVal ));
    }
    for (ProxyListener listener : proxyListeners) {
      listener.afterCreateProxy(objectName, interfaceClass, proxyObject, newVal);
    }
    return true;
  }
  
  public boolean afterPseudoCreateProxy(ObjectName objectName, Class interfaceClass, Object proxyObject,
      FederationComponent newVal) {    
    for (ProxyListener listener : proxyListeners) {
      listener.afterPseudoCreateProxy(objectName, interfaceClass, proxyObject, newVal);
    }
    return true;
  }

  public boolean afterRemoveProxy(ObjectName objectName, Class interfaceClass, Object proxyObject,
      FederationComponent oldVal) {
    for (ProxyListener listener : proxyListeners) {
      listener.afterRemoveProxy(objectName, interfaceClass, proxyObject, oldVal);
    }
    return true;
  }

  public boolean afterUpdateProxy(ObjectName objectName, Class interfaceClass, Object proxyObject,
      FederationComponent newVal, FederationComponent oldVal) {

    for (ProxyListener listener : proxyListeners) {
      listener.afterUpdateProxy(objectName, interfaceClass, proxyObject, newVal, oldVal);
    }
    return true;
  }

  public void handleNotification(Notification notification) {
    for (ProxyListener listener : proxyListeners) {
      listener.handleNotification(notification);
    }
  }



  @Override
  public <T> T getMBeanInstance(ObjectName objectName, Class<T> interfaceClass) {
    if (jmxAdapter.isLocalMBean(objectName)) {
      return jmxAdapter.findMBeanByName(objectName, interfaceClass);
    } else {
      return this.getMBeanProxy(objectName, interfaceClass);
    }
  }

  public boolean isRegistered(ObjectName objectName) {
    return jmxAdapter.isRegistered(objectName);
  }

  public void logFine(String s){
    if (logger.fineEnabled()) {
      logger.fine(s);
    }
  }


  public void memberJoined(InternalDistributedMember id) {
    for (ProxyListener listener : proxyListeners) {
      listener.memberJoined(id);
    }
  }

  public void memberDeparted(InternalDistributedMember id, boolean crashed) {
    for (ProxyListener listener : proxyListeners) {
      listener.memberDeparted(id, crashed);
    }
  }

  public void memberSuspect(InternalDistributedMember id, InternalDistributedMember whoSuspected) {
    for (ProxyListener listener : proxyListeners) {
      listener.memberSuspect(id, whoSuspected);
    }
  }

  public void quorumLost(Set<InternalDistributedMember> failures, List<InternalDistributedMember> remaining) {
    for (ProxyListener listener : proxyListeners) {
      listener.quorumLost(failures, remaining);
    }
  }
  
  
  public class GemFireXDMBean{
    public ObjectName objectName = null;
    public Class interfaceClass = null;
    public Object proxyObject = null;
    public FederationComponent newVal = null;

    public GemFireXDMBean(ObjectName objectName, Class interfaceClass, Object proxyObject, FederationComponent newVal) {
      this.objectName = objectName;
      this.interfaceClass = interfaceClass;
      this.proxyObject = proxyObject;
      this.newVal = newVal;
    }
  }
  
  public void clearGemFireXDMBeans(){
    this.gemFireXDMBeansHolder.clear();
  }
  
  public Map<ObjectName, GemFireXDMBean> getGemFireXDMBeansHolder(){
    return this.gemFireXDMBeansHolder;   
  }
    
  

}
