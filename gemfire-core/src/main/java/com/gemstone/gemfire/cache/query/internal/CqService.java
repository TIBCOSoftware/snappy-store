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
package com.gemstone.gemfire.cache.query.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheEvent;
import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionEvent;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.internal.GetEventValueOp;
import com.gemstone.gemfire.cache.client.internal.QueueManager;
import com.gemstone.gemfire.cache.client.internal.ServerCQProxy;
import com.gemstone.gemfire.cache.client.internal.UserAttributes;
import com.gemstone.gemfire.cache.query.CqAttributes;
import com.gemstone.gemfire.cache.query.CqClosedException;
import com.gemstone.gemfire.cache.query.CqException;
import com.gemstone.gemfire.cache.query.CqExistsException;
import com.gemstone.gemfire.cache.query.CqListener;
import com.gemstone.gemfire.cache.query.CqQuery;
import com.gemstone.gemfire.cache.query.CqServiceStatistics;
import com.gemstone.gemfire.cache.query.CqStatusListener;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.QueryInvalidException;
import com.gemstone.gemfire.cache.query.RegionNotFoundException;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor.CacheProfile;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.FilterProfile;
import com.gemstone.gemfire.internal.cache.FilterRoutingInfo;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientProxy;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.Part;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.org.jgroups.util.StringId;

/**
 * @author Rao Madduri
 * @since 5.5
 *
 * Implements the CqService functionality.
 * 
 */
/**
 * @author agingade
 *
 */
public final class CqService  {
 
  // System property to maintain the CQ event references for optimizing the updates.
  // This will allows to run the CQ query only once during update events.   
  public static final boolean MAINTAIN_KEYS = 
    Boolean.valueOf(System.getProperty("gemfire.cq.MAINTAIN_KEYS", "true")).booleanValue(); 
  
  private static final String CQ_NAME_PREFIX = "GfCq";
  
  private final Cache cache;
  
  private static volatile CqService cqServiceSingleton;
  /**
   * Manages cq pools to determine if a status of connect or disconnect needs to be sent out
   */
  private static HashMap cqPoolsConnected = new HashMap();

  
  /** Manages CQ objects. uses serverCqName as key and CqQueryImpl as value 
   * @guarded.By cqQueryMapLock
   */
  private volatile HashMap cqQueryMap = new HashMap();
  private final Object cqQueryMapLock = new Object();

  /**
   * Used by client when multiuser-authentication is true.
   */
  private final HashMap<String, UserAttributes> cqNameToUserAttributesMap = new HashMap<String, UserAttributes>();
  
  private final LogWriterI18n logger;
  
  // private boolean isServer = true;
  
  /*
  // Map to manage CQ to satisfied CQ events (keys) for optimizing updates. 
  private final HashMap cqToCqEventKeysMap = 
      CqService.MAINTAIN_KEYS ? new HashMap() : null;
  */
  
  // Map to manage the similar CQs (having same query - performance optimization).
  // With query as key and Set of CQs as values.
  private HashMap matchingCqMap = null;

  // CQ Service statistics
  public CqServiceStatisticsImpl cqServiceStats;
  public CqServiceVsdStats stats;
  
  // CQ identifier, also used in auto generated CQ names
  private volatile long cqId = 1;
  
  /**
   * Used to synchronize access to CQs in the repository
   */
  final Object cqSync = new Object();
    
  /* This is to manage region to CQs map, client side book keeping. */
  private final HashMap baseRegionToCqNameMap = new HashMap();
  
  
  /**
   * Constructor. 
   * @param c The cache used for the service
   */
  private CqService(final Cache c) {
    if (c == null) {
      throw new IllegalStateException(LocalizedStrings.CqService_CACHE_IS_NULL.toLocalizedString());
    }
    GemFireCacheImpl gfc = (GemFireCacheImpl) c;
    gfc.getCancelCriterion().checkCancelInProgress(null);

    this.cache = gfc;
    this.logger = gfc.getLoggerI18n();

//    final LogWriterImpl.LoggingThreadGroup group =
//      LogWriterImpl.createThreadGroup("CqExecutor Threads", logger);
    
    //if (this.cache.getCacheServers().isEmpty()) {
    //  isServer = false;
    //}    
  }
  
  /**
   * Creates the root CQ meta region and establishes the server connection.
   * @throws CqException
   */
  private void initCqService() {
    /*
    if (CqService.MAINTAIN_KEYS) {
      synchronized (cqToCqEventKeysMap) {
        cqToCqEventKeysMap.clear();
      }
    }
    */
    
    // Initialize the Map which maintains the matching cqs.
    this.matchingCqMap = new HashMap();
    
    // Initialize the VSD statistics
    StatisticsFactory factory = cache.getDistributedSystem();
    this.stats = new CqServiceVsdStats(factory);
    this.cqServiceStats  = new CqServiceStatisticsImpl(this);

    if (logger.fineEnabled()) {
      logger.fine("Initialized CqService Successfully.");
    }
  }
    
  /**
   * Returns the singleton CqService.
   * @param cache
   * @return CqService
   */
  public static synchronized CqService getCqService(Cache cache)
  {
    if (cqServiceSingleton == null) {
      cqServiceSingleton = new CqService(cache);
      boolean initSuccessful = false;
      try {
        cqServiceSingleton.initCqService();
        initSuccessful = true;
      } finally {
        if (!initSuccessful) {
          cqServiceSingleton = null;
        }
      }
    }
    return cqServiceSingleton;
  }
  
  /**
   * Returns the singleton CqService if it is running, otherwise returns null.
   * @since 6.5
   */
  public static synchronized CqService getRunningCqService()
  {
    return cqServiceSingleton;
  }

  /** 
   * Returns the state of the cqService.
   */
  public static boolean isRunning() {
    return cqServiceSingleton != null;
  }
  
  /**
   * Returns the cache associated with the cqService.
   */
  public Cache getCache() {
    return this.cache;
  }
    
  /**
   * Constructs a new named continuous query, represented by an instance of
   * CqQuery. The CqQuery is not executed, however, until the execute method
   * is invoked on the CqQuery. The name of the query will be used
   * to identify this query in statistics archival.
   *
   * @param cqName the String name for this query
   * @param queryString the OQL query
   * @param cqAttributes the CqAttributes
   * @param serverProxy the serverProxy for this client 
   * @param isDurable true if the CQ is durable 
   * @return the newly created CqQuery object
   * @throws CqExistsException if a CQ by this name already exists on this
   * client
   * @throws IllegalArgumentException if queryString or cqAttr is null
   * @throws IllegalStateException if this method is called from a cache
   *         server
   * @throws QueryInvalidException if there is a syntax error in the query
   * @throws CqException if failed to create cq, failure during creating  
   *         managing cq metadata info.
   *   E.g.: Query string should refer only one region, join not supported.
   *         The query must be a SELECT statement.
   *         DISTINCT queries are not supported.
   *         Projections are not supported.
   *         Only one iterator in the FROM clause is supported, and it must be a region path. 
   *         Bind parameters in the query are not supported for the initial release.
   *
   */
  public synchronized CqQuery newCq(String cqName, String queryString, CqAttributes cqAttributes, ServerCQProxy serverProxy, boolean isDurable)
  throws QueryInvalidException, CqExistsException, CqException {
    if (queryString == null) {
      throw new IllegalArgumentException(LocalizedStrings.CqService_NULL_ARGUMENT_0.toLocalizedString("queryString"));

    } else if (cqAttributes == null ) {
      throw new IllegalArgumentException(LocalizedStrings.CqService_NULL_ARGUMENT_0.toLocalizedString("cqAttribute"));
    }
    
    if (isServer()) {
      throw new IllegalStateException(
        LocalizedStrings.CqService_CLIENT_SIDE_NEWCQ_METHOD_INVOCATION_ON_SERVER.toLocalizedString());
    }
    
    // Check if the given cq already exists.
    if (cqName != null && isCqExists(cqName)) {
      throw new CqExistsException(
        LocalizedStrings.
          CqService_CQ_WITH_THE_GIVEN_NAME_ALREADY_EXISTS_CQNAME_0
        .toLocalizedString(cqName));
    }
    
    CqQueryImpl cQuery = new CqQueryImpl(this, cqName, queryString, cqAttributes, serverProxy, isDurable);
    cQuery.updateCqCreateStats();

    //cQuery.initCq();
    
    // Check if query is valid.  
    cQuery.validateCq();
    
    // Add cq into meta region.    
    // Check if Name needs to be generated.
    if (cqName == null) {
      // in the case of cqname internally generated, the CqExistsException needs
      // to be taken care internally.
      while(true) {
        cQuery.setName(generateCqName());
        try {
          addToCqMap(cQuery);
        } catch (CqExistsException ex) {
          logger.fine("Got CqExistsException while intializing cq : " + cQuery.getName() + " Error : " + ex.getMessage());
          continue;
        }
        break;
      } 
    } else {
      addToCqMap(cQuery);
    }

    this.addToBaseRegionToCqNameMap(cQuery.getBaseRegionName(), cQuery.getServerCqName());
    
    return cQuery;
  }
  

  /**
   * Executes the given CqQuery, if the CqQuery for that name is not there
   * it registers the one and executes. This is called on the Server. 
   * @param cqName
   * @param queryString
   * @param cqState
   * @param clientProxyId
   * @param ccn
   * @param manageEmptyRegions whether to update the 6.1 emptyRegions map held in the CCN
   * @param regionDataPolicy the data policy of the region associated with the
   *        query.  This is only needed if manageEmptyRegions is true.
   * @param emptyRegionsMap map of empty regions.    
   * @throws IllegalStateException if this is called at client side.
   * @throws CqException
   */
  public synchronized CqQuery executeCq(String cqName, String queryString,
      int cqState, ClientProxyMembershipID clientProxyId, 
      CacheClientNotifier ccn, boolean isDurable, boolean manageEmptyRegions,
      int regionDataPolicy, Map emptyRegionsMap) 
    throws CqException, RegionNotFoundException, CqClosedException {
    if (!isServer()) {
      throw new IllegalStateException(
          LocalizedStrings.CqService_SERVER_SIDE_EXECUTECQ_METHOD_IS_CALLED_ON_CLIENT_CQNAME_0
              .toLocalizedString(cqName));
    }
    
    String serverCqName = constructServerCqName(cqName, clientProxyId);
    CqQueryImpl cQuery = null;
    
    // If this CQ is not yet registered in Server, register CQ.
    if (!isCqExists(serverCqName)) {
      cQuery = new CqQueryImpl(this, cqName, queryString, null, null, isDurable);
      cQuery.setServerCqName(constructServerCqName(cqName, clientProxyId));
      
      try {
        cQuery.registerCq(clientProxyId, ccn, cqState);
        if (manageEmptyRegions) { // new in 6.1
          if (emptyRegionsMap != null && emptyRegionsMap.containsKey(cQuery.getBaseRegionName())){
            regionDataPolicy = 0;
          }
          ccn.updateMapOfEmptyRegions(ccn.getClientProxy(clientProxyId, true).getRegionsWithEmptyDataPolicy(), 
              cQuery.getBaseRegionName(), regionDataPolicy);
        }
      } catch (CqException cqe) {
        logger.info(LocalizedStrings.CqService_EXCEPTION_WHILE_REGISTERING_CQ_ON_SERVER_CQNAME___0, cQuery.getName());
        cQuery = null;
        throw cqe;
      }
      
    } else {
      cQuery = (CqQueryImpl)getCq(serverCqName);
      // Initialize the state of CQ.
      if(((CqStateImpl)cQuery.getState()).getState() != cqState) {
        cQuery.setCqState(cqState);
        // Add to the matchedCqMap.
        addToMatchingCqMap(cQuery);    
        // addToCqEventKeysMap(cQuery);
        // Send state change info to peers.
        cQuery.getCqBaseRegion().getFilterProfile().setCqState(cQuery);
      }
   }
    
    
    if (logger.fineEnabled() ) {
      logger.fine("Successfully created CQ on the server. CqName : " + cQuery.getName());
    }
    return cQuery;
  }
  
  /*
  public void addToCqEventKeysMap(CqQuery cq){
    if (cqToCqEventKeysMap != null) {
      synchronized (cqToCqEventKeysMap){
        String serverCqName = ((CqQueryImpl)cq).getServerCqName();
        if (!cqToCqEventKeysMap.containsKey(serverCqName)){
          cqToCqEventKeysMap.put(serverCqName, new HashSet());
          if (_logger.fineEnabled()) {
            _logger.fine("CQ Event key maintenance for CQ, CqName: " + 
              serverCqName + " is Enabled." + " key maintenance map size is: " +
              cqToCqEventKeysMap.size());
          }
        }
      } // synchronized
    }    
  }
  */

  public boolean hasCq(){
    HashMap cqMap = cqQueryMap;
    return (cqMap.size() > 0);
  }
  
  
  /**
   * Adds the given CQ and cqQuery object into the CQ map.
   */
  public void addToCqMap(CqQueryImpl cq) throws CqExistsException, CqException {
    // On server side cqName will be server side cqName.
    String sCqName = cq.getServerCqName();
    if (logger.fineEnabled()){
      logger.fine("Adding to CQ Repository. CqName : " + cq.getName() + 
          " ServerCqName : " + sCqName);
    }
    HashMap cqMap = cqQueryMap;
      if (cqMap.containsKey(sCqName)) {
        throw new CqExistsException(
          LocalizedStrings.CqService_A_CQ_WITH_THE_GIVEN_NAME_0_ALREADY_EXISTS.toLocalizedString(sCqName));
      }
      synchronized (cqQueryMapLock) {
        HashMap tmpCqQueryMap = new HashMap(cqQueryMap);
        try {
          tmpCqQueryMap.put(sCqName, cq);
        }catch (Exception ex){
          StringId errMsg = LocalizedStrings.CqQueryImpl_FAILED_TO_STORE_CONTINUOUS_QUERY_IN_THE_REPOSITORY_CQNAME_0_1;
          Object[] errMsgArgs = new Object[] {sCqName, ex.getLocalizedMessage()};
          logger.error(errMsg, errMsgArgs);
          throw new CqException (errMsg.toLocalizedString(errMsgArgs), ex);
        }    
        UserAttributes attributes = UserAttributes.userAttributes.get();
        if (attributes != null) {
          this.cqNameToUserAttributesMap.put(cq.getName(), attributes);
        }
        cqQueryMap = tmpCqQueryMap;
      }
  }

  /**
   * Removes given CQ from the cqMap..
   */
  public void removeCq(String cqName) {
    // On server side cqName will be server side cqName.
    synchronized (cqQueryMapLock) {
      HashMap tmpCqQueryMap = new HashMap(cqQueryMap);
      tmpCqQueryMap.remove(cqName);
      this.cqNameToUserAttributesMap.remove(cqName);
      cqQueryMap = tmpCqQueryMap;
    }
  }
  
  /**
   * Retrieve a cq by client cq name from server
   * @return the CqQuery or null if not found
   */
  public CqQuery getClientCqFromServer(ClientProxyMembershipID clientProxyId, String clientCqName) {
    // On server side cqName will be server side cqName.
    HashMap cqMap = cqQueryMap;
    return (CqQuery)cqMap.get(this.constructServerCqName(clientCqName, clientProxyId));
  }

  /**
   * Retrieve a CqQuery by name.
   * @return the CqQuery or null if not found
   */
  public CqQuery getCq(String cqName) {
    // On server side cqName will be server side cqName.
    HashMap cqMap = cqQueryMap;
    return (CqQuery)cqQueryMap.get(cqName);
  }

  /**
   * Clears the CQ Query Map.
   */
  public void clearCqQueryMap() {
    // On server side cqName will be server side cqName.
    HashMap  oldMap = null;
    synchronized (cqQueryMapLock) {
      oldMap = cqQueryMap;
      cqQueryMap = new HashMap();
    }
  }

  /**
   * Retrieve  all registered CQs
   */
  public CqQuery[] getAllCqs(){
    CqQuery[] cQuerys = new CqQuery[0]; // Empty array.
    HashMap cqMap = cqQueryMap;
    if (cqMap.size() > 0) {
      Collection cqs = cqMap.values();
      cQuerys = new CqQuery[cqs.size()];
      //Now that we do copy-on-write for cqMap, we might not
      //need to call toArray()
      cqs.toArray(cQuerys);
    }
    return cQuerys;
  }

  /**
   * Retruns all the cqs on a given region.
   * 
   */
  public CqQuery[] getAllCqs(final String regionName)throws CqException{
    if (regionName == null){
      throw new IllegalArgumentException(LocalizedStrings.CqService_NULL_ARGUMENT_0.toLocalizedString("regionName"));
    }
    
    String[] cqNames = null;
    CqQuery[] cQuerys = null;
    
    synchronized(this.baseRegionToCqNameMap){
      ArrayList cqs = (ArrayList)this.baseRegionToCqNameMap.get(regionName);
      if(cqs == null) {
        return cQuerys;
      }
      cqNames = new String[cqs.size()];
      cqs.toArray(cqNames);
    }
    
    ArrayList cQueryList = new ArrayList();
    for(int cqCnt=0; cqCnt < cqNames.length; cqCnt++){
      CqQuery cq = getCq(cqNames[cqCnt]);
      if (cq != null){
        cQueryList.add(cq);
      }
    }
    if(!cQueryList.isEmpty()){
      cQuerys = (CqQuery[])cQueryList.toArray(new CqQueryImpl[0]);
    }

    return cQuerys;
    
  }

  /**
   * Executes all the cqs on this client.
   */
  public synchronized void executeAllClientCqs()throws CqException{
    executeCqs(this.getAllCqs());
  }
  
  /**
   * Executes all the cqs on a given region.
   */
  public synchronized void executeAllRegionCqs(final String regionName)throws CqException{
     executeCqs(getAllCqs(regionName));
  }
  
  /**
   * Executes all the given cqs.
   */
  public synchronized void executeCqs(CqQuery[] cqs)throws CqException{
    if(cqs == null) {
      return;
    }
    String cqName = null;
    for (int cqCnt=0; cqCnt < cqs.length; cqCnt++) {
      CqQuery cq = cqs[cqCnt];
      if (!cq.isClosed() && cq.isStopped()) {
        try {
          cqName = cq.getName();
          cq.execute();
        } catch (QueryException qe) {
          if(logger.finerEnabled()){
            logger.finer("Failed to execute the CQ, CqName : " + cqName + 
                " Error : " + qe.getMessage());
          }
        } catch (CqClosedException cce){
          if(logger.finerEnabled()){
            logger.finer("Failed to execute the CQ, CqName : " + cqName + 
                " Error : " + cce.getMessage());
          }
        }
      }
    }    
  }
  
  /**
   * Stops all the cqs on a given region.
   */
  public synchronized void stopAllClientCqs()throws CqException{
    stopCqs(this.getAllCqs());
  }
  
  /**
   * Stops all the cqs on a given region.
   */
  public synchronized void stopAllRegionCqs(final String regionName)throws CqException{
    stopCqs(this.getAllCqs(regionName));
  }
  
  /**
   * Stops all the specified cqs.
   */
  public synchronized void stopCqs(CqQuery[] cqs)throws CqException{
    logger.fine("CqService.stopCqs cqs :" + 
      (cqs == null ? "null" : "(" + cqs.length + " queries)"));

    if(cqs == null) {
      return;
    }
    
    String cqName = null;
    for (int cqCnt=0; cqCnt < cqs.length; cqCnt++) {
      CqQuery cq = cqs[cqCnt];
      if (!cq.isClosed() && cq.isRunning()) {
        try {
          cqName = cq.getName();
          cq.stop();
        } catch (QueryException qe) {
          if(logger.finerEnabled()){
            logger.finer("Failed to stop the CQ, CqName : " + cqName + 
                " Error : " + qe.getMessage());
          }
        } catch (CqClosedException cce){
          if(logger.finerEnabled()){
            logger.finer("Failed to stop the CQ, CqName : " + cqName + 
                " Error : " + cce.getMessage());
          }
        }
      }
    }
  }

  /**
   * Closes all the cqs on a given region.
   */
  public void closeCqs(final String regionName)throws CqException{
    CqQueryImpl[] cqs = (CqQueryImpl[])this.getAllCqs(regionName);
    if(cqs != null) {
      String cqName = null;
      for (int cqCnt=0; cqCnt < cqs.length; cqCnt++) {
        try {
          CqQueryImpl cq = cqs[cqCnt] ;
          cqName = cq.getName();
          
          if(isServer()) {
            // invoked on the server
            cq.close(false);  
          } else { 
            // @todo grid: if regionName has a pool check its keepAlive
            boolean keepAlive = ((GemFireCacheImpl)this.cache).keepDurableSubscriptionsAlive();
            if(cq.isDurable() && keepAlive){
              if (logger.warningEnabled()){
                logger.warning(
                  LocalizedStrings.CqService_NOT_SENDING_CQ_CLOSE_TO_THE_SERVER_AS_IT_IS_A_DURABLE_CQ);
              } 
              cq.close(false);  
            } 
            else {
              cq.close(true);
            }
          }

        } catch (QueryException qe) {
          if(logger.finerEnabled()){
            logger.finer("Failed to close the CQ, CqName : " + cqName + 
                " Error : " + qe.getMessage());
          }
        } catch (CqClosedException cce){
          if(logger.finerEnabled()){
            logger.finer("Failed to close the CQ, CqName : " + cqName + 
                " Error : " + cce.getMessage());
          }
        }
      }
    }
  }

  /**
   * Called directly on server side.
   * @param cqName
   * @param clientId
   * @throws CqException
   */
  public void stopCq(String cqName, ClientProxyMembershipID clientId)
  throws CqException {
    String serverCqName = cqName;
    if (clientId != null) {
      serverCqName = this.constructServerCqName(cqName, clientId); 
    }
  
    CqQueryImpl cQuery = null;
    StringId errMsg = null;
    Exception ex = null;
    
    try {
      HashMap cqMap = cqQueryMap;
      if (!cqMap.containsKey(serverCqName)) {
//        throw new CqException(LocalizedStrings.CqService_CQ_NOT_FOUND_FAILED_TO_STOP_THE_SPECIFIED_CQ_0.toLocalizedString(serverCqName));
        /* gregp 052808: We should silently fail here instead of throwing error. This is to deal with races in recovery */
        return;
      }
      cQuery = (CqQueryImpl)getCq(serverCqName);
      
    } catch (CacheLoaderException e1) {
      errMsg = LocalizedStrings.CqService_CQ_NOT_FOUND_IN_THE_CQ_META_REGION_CQNAME_0;
      ex = e1;
    } catch (TimeoutException e2) {
      errMsg = LocalizedStrings.CqService_TIMEOUT_WHILE_TRYING_TO_GET_CQ_FROM_META_REGION_CQNAME_0;
      ex = e2;
    } finally {
      if (ex != null){
        logger.fine(errMsg.toLocalizedString(cqName));
        throw new CqException(errMsg.toLocalizedString(cqName), ex);
      }
    }
    
    try {
      if(!cQuery.isStopped()) {
        cQuery.stop();
      }
    } catch (CqClosedException cce){
      throw new CqException(cce.getMessage());
    } finally {
      // If this CQ is stopped, disable caching event keys for this CQ.
      //this.removeCQFromCaching(cQuery.getServerCqName());
      this.removeFromMatchingCqMap(cQuery);
    }
    // Send stop message to peers.
    cQuery.getCqBaseRegion().getFilterProfile().stopCq(cQuery);

  }

  /**
   * Called directly on server side.
   * @param cqName
   * @param clientProxyId
   * @throws CqException
   */
  public void closeCq(String cqName, ClientProxyMembershipID clientProxyId)
  throws CqException {
    String serverCqName = cqName;
    if (clientProxyId != null) {
      serverCqName = this.constructServerCqName(cqName, clientProxyId); 
    }
    
    CqQueryImpl cQuery = null;
    StringId errMsg = null;
    Exception ex = null;
    
    try {
      HashMap cqMap = cqQueryMap;
      if (!cqMap.containsKey(serverCqName)) {
//        throw new CqException(LocalizedStrings.CqService_CQ_NOT_FOUND_FAILED_TO_CLOSE_THE_SPECIFIED_CQ_0
//            .toLocalizedString(serverCqName));
        /* gregp 052808: We should silently fail here instead of throwing error. This is to deal with races in recovery */
        return;
      }
      cQuery = (CqQueryImpl)cqMap.get(serverCqName);
      
    } catch (CacheLoaderException e1) {
      errMsg = LocalizedStrings.CqService_CQ_NOT_FOUND_IN_THE_CQ_META_REGION_CQNAME_0;
      ex = e1;
    } catch (TimeoutException e2) {
      errMsg = LocalizedStrings.CqService_TIMEOUT_WHILE_TRYING_TO_GET_CQ_FROM_META_REGION_CQNAME_0;
      ex = e2;
    } finally {
      if (ex != null){
        logger.fine(errMsg.toLocalizedString(cqName));
        throw new CqException(errMsg.toLocalizedString(cqName), ex);
      }
    }
    
    try {
      cQuery.close(false);

      // Repository Region.
      // If CQ event caching is enabled, remove this CQs event cache reference.
      // removeCQFromCaching(serverCqName);
      
      // CqBaseRegion 
      try {
        LocalRegion baseRegion = cQuery.getCqBaseRegion();
        if (baseRegion != null && !baseRegion.isDestroyed()) {
          // Server specific clean up.
          if (isServer()){
            FilterProfile fp = baseRegion.getFilterProfile();
            if (fp != null) {
              fp.closeCq(cQuery);
            }
            CacheClientProxy clientProxy = cQuery.getCacheClientNotifier().getClientProxy(clientProxyId);
            clientProxy.decCqCount();
            if (clientProxy.hasNoCq()) {
              this.stats.decClientsWithCqs();
            }
          }
        } 
      }catch (Exception e){
        // May be cache is being shutdown
        if (logger.fineEnabled()) {
          logger.fine("Failed to remove CQ from the base region. CqName :" + cqName);
        }
      }
    
      if (isServer()){
        removeFromBaseRegionToCqNameMap(cQuery.getRegionName(), serverCqName);      
      }

      LocalRegion baseRegion = cQuery.getCqBaseRegion();
      if(baseRegion.getFilterProfile().getCqCount() <= 0){
        if (this.logger.fineEnabled()) {
          this.logger
          .fine("Should update the profile for this partitioned region "
              + baseRegion + " for not requiring old value");
        }
      }          
    } catch (CqClosedException cce){
      throw new CqException(cce.getMessage());
    } finally {
      this.removeFromMatchingCqMap(cQuery);
    }
  }
  
  
  public void closeAllCqs(boolean clientInitiated) {
    closeAllCqs(clientInitiated, getAllCqs());
  }

  /** 
   * Close all CQs executing in this VM, and release resources
   * associated with executing CQs.
   * CqQuerys created by other VMs are unaffected.
   */
  public void closeAllCqs(boolean clientInitiated, CqQuery[] cqs) {
    closeAllCqs(clientInitiated, cqs, ((GemFireCacheImpl)this.cache)
        .keepDurableSubscriptionsAlive());
  }

  public void closeAllCqs(boolean clientInitiated, CqQuery[] cqs,
      boolean keepAlive) {
    
    CqQueryImpl cQuery = null;
    //CqQuery[] cqs = getAllCqs();
    if (cqs != null) {
      String cqName = null;
      logger.fine("Closing all CQs, number of CQ to be closed : " + cqs.length);
      for (int cqCnt=0; cqCnt < cqs.length; cqCnt++){
        try {
          cQuery = (CqQueryImpl)cqs[cqCnt];
          cqName = cQuery.getName();
//          boolean keepAlive = ((GemFireCache)this.cache).keepDurableSubscriptionsAlive();
         
          if(isServer()) {
            cQuery.close(false);  
          } else {
            if (clientInitiated) {
              cQuery.close(true);
            } 
            else {
              if(!isServer() && cQuery.isDurable() && keepAlive){
                if (logger.warningEnabled()){
                  logger.warning(
                    LocalizedStrings.CqService_NOT_SENDING_CQ_CLOSE_TO_THE_SERVER_AS_IT_IS_A_DURABLE_CQ);
                }
                cQuery.close(false);
              }
              else {
                cQuery.close(true);
              }
            }
          }
        } catch (QueryException cqe) {
          if (!CqService.isRunning()) {
            // Not cache shutdown
            logger.warning(LocalizedStrings.CqService_FAILED_TO_CLOSE_CQ__0___1, new Object[] {cqName, cqe.getMessage()});
          }
          if (logger.finerEnabled()){
            logger.finer(cqe.getMessage(), cqe);
          }
        } catch (CqClosedException cqe){
          if (!CqService.isRunning()) {
            // Not cache shutdown
            logger.warning(LocalizedStrings.CqService_FAILED_TO_CLOSE_CQ__0___1, new Object[] {cqName, cqe.getMessage()});
          }
          if (logger.finerEnabled()){
            logger.finer(cqe.getMessage(), cqe);
          } 
        }
      }
    }
  }
  
  /**
   * @param op
   * @param regionName
   * @throws CqException
   */
  public void handleCqMonitorOp(int op, String regionName) throws CqException {
    // The implementation of enable/disable cq is changed.
    // Instead calling enable/disable client calls execute/stop methods
    // at cache and region level.
    // This method is retained for future purpose, to support admin level apis
    // similar to enable/disable at system/client level.
    // Should never come.
    throw new CqException (LocalizedStrings.CqService_INVALID_CQ_MONITOR_REQUEST_RECEIVED.toLocalizedString());
  }
  
  /**
   * Get statistics information for all CQs
   * @return the CqServiceStatistics
   */
  public CqServiceStatistics getCqStatistics() {
    return cqServiceStats;
  }
  
  /**
   * Server side method.
   * @param clientProxyId
   * @throws CqException
   */
  public void closeClientCqs(ClientProxyMembershipID clientProxyId)
  throws CqException {
    if (logger.fineEnabled()){
      logger.fine("Closing Client CQs for the client: " + clientProxyId);
    }
    List<CqQueryImpl> cqs = getAllClientCqs(clientProxyId);
    for (CqQuery cq: cqs) {
      CqQueryImpl cQuery = (CqQueryImpl)cq;
      try {
        cQuery.close(false);
      } catch (QueryException qe) {
        if(logger.finerEnabled()){
          logger.finer("Failed to close the CQ, CqName : " + cQuery.getName() + 
              " Error : " + qe.getMessage());
        }
      } catch (CqClosedException cce) {
        if(logger.finerEnabled()){
          logger.finer("Failed to close the CQ, CqName : " + cQuery.getName() + 
              " Error : " + cce.getMessage());
        }
      }
    }
  }

  /**
   * Returns all the CQs registered by the client.
   * @param clientProxyId
   * @return CQs registered by the client.
   */
  public List<CqQueryImpl> getAllClientCqs(ClientProxyMembershipID clientProxyId){
    CqQuery[] cqs = getAllCqs();
    ArrayList<CqQueryImpl> clientCqs = new ArrayList<CqQueryImpl>();
    
    for (int cnt=0; cnt < cqs.length; cnt++) {
      CqQueryImpl cQuery = (CqQueryImpl)cqs[cnt];
      ClientProxyMembershipID id = cQuery.getClientProxyId(); 
      if (id != null && id.equals(clientProxyId)) {
          clientCqs.add(cQuery);
      }
    }
    return clientCqs;   
  }
  
  /**
   * Returns all the durable client CQs registered by the client.
   * @param clientProxyId
   * @return CQs registered by the client.
   */
  public List<String> getAllDurableClientCqs(ClientProxyMembershipID clientProxyId) throws CqException {
    if (clientProxyId == null) {
      throw new CqException (LocalizedStrings.CqService_UNABLE_TO_RETRIEVE_DURABLE_CQS_FOR_CLIENT_PROXY_ID.toLocalizedString(clientProxyId));
    }
    List<CqQueryImpl> cqs = getAllClientCqs(clientProxyId);
    ArrayList<String> durableClientCqs = new ArrayList<String>();
    
    for (CqQueryImpl cQuery: cqs) {
      if (cQuery != null && cQuery.isDurable()) {
        ClientProxyMembershipID id = cQuery.getClientProxyId(); 
        if (id != null && id.equals(clientProxyId)) {
            durableClientCqs.add(cQuery.getName());
        }
      }
    }
    return durableClientCqs;   
  }
  
  /**
   * Server side method.
   * Closes non-durable CQs for the given client proxy id.
   * @param clientProxyId
   * @throws CqException
   */
  public void closeNonDurableClientCqs(ClientProxyMembershipID clientProxyId)
  throws CqException {
    if (logger.fineEnabled()){
      logger.fine("Closing Client CQs for the client: " + clientProxyId);
    }
    List<CqQueryImpl> cqs = getAllClientCqs(clientProxyId);
    for (CqQuery cq: cqs) {
      CqQueryImpl cQuery = (CqQueryImpl)cq;
      try {
        if (!cQuery.isDurable()) {
          cQuery.close(false);
        }
      } catch (QueryException qe) {
        if(logger.finerEnabled()){
          logger.finer("Failed to close the CQ, CqName : " + cQuery.getName() + 
              " Error : " + qe.getMessage());
        }
      } catch (CqClosedException cce) {
        if(logger.finerEnabled()){
          logger.finer("Failed to close the CQ, CqName : " + cQuery.getName() + 
              " Error : " + cce.getMessage());
        }
      }
    }
  }
  
  /**
   * Is the CQ service in a cache server environment
   * @return true if cache server, false otherwise
   */
  public boolean isServer() {
    if (this.cache.getCacheServers().isEmpty()) {
      return false;
    }    
    return true;
  }
  
  /**
   * Close the CQ Service after cleanup if any.
   */
  public static synchronized void closeCqService() {
    if (cqServiceSingleton != null) {
      final LogWriterI18n l = cqServiceSingleton.logger;
      if (l.fineEnabled()) {
        l.fine("Closing CqService." + cqServiceSingleton);
      }
      try {
        cqServiceSingleton.cleanup();
      } finally {
        if (l.fineEnabled()) {
          l.fine("Closed CqService."  + cqServiceSingleton);
        }
        cqServiceSingleton = null;
      }
    }
  }
  
  /**
   * Cleans up the CqService.
   */
  private void cleanup() {
    logger.fine("Cleaning up CqService.");
    // Close All the CQs.
    // Need to take care when Clients are still connected...
    closeAllCqs(false);
  }
  
  /**
   * @return Returns the serverCqName.
   */
  public String constructServerCqName(String cqName, ClientProxyMembershipID clientProxyId) {
    String cName = null;
    if (clientProxyId.isDurable()) {
      cName = cqName + "__" + clientProxyId.getDurableId();
    }
    else {
      cName = cqName + "__" + clientProxyId.getDSMembership();
    }
    return cName;
  }
  
  /*
   * Checks if CQ with the given name already exists.
   * @param cqName name of the CQ.
   * @return true if exists else false.
   */
  private synchronized boolean isCqExists(String cqName) {
    boolean status = false;
    HashMap cqMap =cqQueryMap;
    status = cqMap.containsKey(cqName);
    return status;
  }

  /*
   * Generates a name for CQ.
   * Checks if CQ with that name already exists if so generates a new cqName.
   */
  public synchronized String generateCqName() {
    while (true) {
      String cqName = CQ_NAME_PREFIX + (cqId++);
      if (!isCqExists(cqName)) {
        return cqName;
      }
    }
  }
  
  /**
   * Invokes the CqListeners for the given CQs.
   * @param cqs list of cqs with the cq operation from the Server.
   * @param messageType base operation
   * @param key 
   * @param value
   */
  public void dispatchCqListeners(HashMap cqs, int messageType, Object key,
      Object value, byte[] delta, QueueManager qManager, EventID eventId) {
    CqQueryImpl cQuery = null;
    Object[] fullValue = new Object[1];
    Iterator iter = cqs.entrySet().iterator();
    String cqName = null;
    while (iter.hasNext()) {
      try {
      Map.Entry entry = (Map.Entry)iter.next();
      cqName = (String)entry.getKey();
      cQuery = (CqQueryImpl)this.getCq(cqName);
      
      if (cQuery == null || (!cQuery.isRunning() && cQuery.getQueuedEvents() == null)) {
        if (logger.fineEnabled()){
          logger.fine("Unable to invoke CqListener, " + 
              ((cQuery == null)? "CQ not found":" CQ is Not running") + 
              ", CqName : " + cqName);
        }
        continue;
      }
      
      Integer cqOp = (Integer)entry.getValue();
      
      // If Region destroy event, close the cq.
      if (cqOp.intValue() == MessageType.DESTROY_REGION) {
        // The close will also invoke the listeners close().
        try {
          cQuery.close(false);
        } catch (Exception ex) {
          // handle?
        }
        continue;
      }
      
      // Construct CqEvent.
      CqEventImpl cqEvent = null;
      cqEvent = new CqEventImpl(cQuery, getOperation(messageType),
          getOperation(cqOp.intValue()), key, value, delta, qManager, eventId);
      
      // Update statistics
      cQuery.updateStats(cqEvent);

      // Check if CQ Event needs to be queued.
      if (cQuery.getQueuedEvents() != null) {
        synchronized(cQuery.queuedEventsSynchObject) {
          // Get latest value.
          ConcurrentLinkedQueue queuedEvents = cQuery.getQueuedEvents();
          // Check to see, if its not set to null while waiting to get 
          // Synchronization lock.
          if (queuedEvents != null) {
            if (logger.fineEnabled()) {
              logger.fine("Queueing event for key: " + key);
            }
            cQuery.getVsdStats().incQueuedCqListenerEvents();
            queuedEvents.add(cqEvent);
            continue;
          }
        }
      }

      this.invokeListeners(cqName, cQuery, cqEvent, fullValue);
      if (value == null) {
        value = fullValue[0];
      }

      } // outer try
      catch(Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        logger.warning(LocalizedStrings.CqService_ERROR_PROCESSING_CQLISTENER_FOR_CQ_0, cqName, t);
        
        if (t instanceof VirtualMachineError) {
          logger.warning(LocalizedStrings.CqService_VIRTUALMACHINEERROR_PROCESSING_CQLISTENER_FOR_CQ_0, cqName, t);
          return;
        }
      }
    } // iteration.
  }

  public void invokeListeners(String cqName, CqQueryImpl cQuery,
      CqEventImpl cqEvent) {
    invokeListeners(cqName, cQuery, cqEvent, null);
  }

  public void invokeListeners(String cqName, CqQueryImpl cQuery,
      CqEventImpl cqEvent, Object[] fullValue) {
    if (!cQuery.isRunning() || cQuery.getCqAttributes() == null) {
      return;
    }
    
    // invoke CQ Listeners.
    CqListener[] cqListeners = cQuery.getCqAttributes().getCqListeners();
    
    if (logger.fineEnabled()){
      logger.fine("Invoking CQ listeners for " + cqName + 
          " , number of listeners : " + cqListeners.length + " cqEvent : " + cqEvent);
    }
    
    for (int lCnt=0; lCnt < cqListeners.length; lCnt++) {
      try {
        // Check if the listener is not null, it could have been changed/reset
        // by the CqAttributeMutator.
        if (cqListeners[lCnt] != null){
          cQuery.getVsdStats().incNumCqListenerInvocations();
          try {
          if (cqEvent.getThrowable() != null) {
            cqListeners[lCnt].onError(cqEvent);
          } else {
            cqListeners[lCnt].onEvent(cqEvent);
          }
          } catch (InvalidDeltaException ide) {
            if (logger.fineEnabled()) {
              logger.fine("CqService.dispatchCqListeners(): "
                  + "Requesting full value...");
            }
            Part result = (Part)GetEventValueOp.executeOnPrimary(cqEvent
                .getQueueManager().getPool(), cqEvent.getEventID(), null);
            Object newVal = null;
            if (result == null || (newVal = result.getObject()) == null) {
              if (this.cache.getCancelCriterion().cancelInProgress() == null) {
                Exception ex = new Exception(
                    "Failed to retrieve full value from server for eventID "
                    + cqEvent.getEventID());
                logger.warning(
                    LocalizedStrings.CqService_EXCEPTION_IN_THE_CQLISTENER_OF_THE_CQ_CQNAME_0_ERROR__1,
                    new Object[] { cqName, ex.getMessage() });
                logger.fine(ex.getMessage(), ex);
              }
            }
            else {
              ((GemFireCacheImpl)this.cache).getCachePerfStats().incDeltaFullValuesRequested();
              cqEvent = new CqEventImpl(cQuery, cqEvent.getBaseOperation(),
                    cqEvent.getQueryOperation(), cqEvent.getKey(), newVal,
                    cqEvent.getDeltaValue(), cqEvent.getQueueManager(), cqEvent.getEventID());
              if (cqEvent.getThrowable() != null) {
                cqListeners[lCnt].onError(cqEvent);
              } else {
                cqListeners[lCnt].onEvent(cqEvent);
              }
              if (fullValue != null) {
                fullValue[0] = newVal;
              }
            }
          }
        }
        // Handle client side exceptions.
      } catch (Exception ex) {
        if (this.cache.getCancelCriterion().cancelInProgress() == null) {
          logger.warning(
              LocalizedStrings.CqService_EXCEPTION_IN_THE_CQLISTENER_OF_THE_CQ_CQNAME_0_ERROR__1,
              new Object[] { cqName, ex.getMessage()});
          logger.fine(ex.getMessage(), ex);
        }
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
        logger.warning(LocalizedStrings.CqService_RUNTIME_EXCEPTION_IN_THE_CQLISTENER_OF_THE_CQ_CQNAME_0_ERROR__1, new Object[] {cqName, t.getLocalizedMessage()});
        logger.fine(t.getMessage(), t);
      }      
    }    
  }
  
  public void invokeCqConnectedListeners(String cqName, CqQueryImpl cQuery, boolean connected) {
    if (!cQuery.isRunning() || cQuery.getCqAttributes() == null) {
      return;
    }
    cQuery.setConnected(connected);
    // invoke CQ Listeners.
    CqListener[] cqListeners = cQuery.getCqAttributes().getCqListeners();
    
    if (logger.fineEnabled()){
      logger.fine("Invoking CQ status listeners for " + cqName + 
          " , number of listeners : " + cqListeners.length);
    }
    
    for (int lCnt=0; lCnt < cqListeners.length; lCnt++) {
      try {
        if (cqListeners[lCnt] != null) {
          if (cqListeners[lCnt] instanceof CqStatusListener) {
            CqStatusListener listener = (CqStatusListener) cqListeners[lCnt];
            if (connected) {
              listener.onCqConnected();
            }
            else {
              listener.onCqDisconnected();
            }
          }
        }
        // Handle client side exceptions.
      } catch (Exception ex) {
        if (this.cache.getCancelCriterion().cancelInProgress() == null) {
          logger.warning(
              LocalizedStrings.CqService_EXCEPTION_IN_THE_CQLISTENER_OF_THE_CQ_CQNAME_0_ERROR__1,
              new Object[] { cqName, ex.getMessage()});
          logger.fine(ex.getMessage(), ex);
        }
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
        logger.warning(LocalizedStrings.CqService_RUNTIME_EXCEPTION_IN_THE_CQLISTENER_OF_THE_CQ_CQNAME_0_ERROR__1, new Object[] {cqName, t.getLocalizedMessage()});
        logger.fine(t.getMessage(), t);
      }      
    }    
  }

  /**
   * Returns the Operation for the given EnumListenerEvent type.
   * @param eventType
   * @return Operation
   */
  private Operation getOperation(int eventType) {  
    Operation op = null;
    switch (eventType) {
      case MessageType.LOCAL_CREATE :
        op = Operation.CREATE;
        break;
        
      case MessageType.LOCAL_UPDATE :
        op = Operation.UPDATE;
        break;
        
      case MessageType.LOCAL_DESTROY :
        op = Operation.DESTROY;
        break;        
        
      case MessageType.LOCAL_INVALIDATE :
        op = Operation.INVALIDATE;
        break;        
        
      case MessageType.CLEAR_REGION :
        op = Operation.REGION_CLEAR;
        break;        
        
      case MessageType.INVALIDATE_REGION :
        op = Operation.REGION_INVALIDATE;
        break;        
    }
    return op;    
  }

  public void processEvents (CacheEvent event, Profile localProfile, Profile[] profiles, FilterRoutingInfo frInfo) 
    throws CqException {
    //Is this a region event or an entry event
    if (event instanceof RegionEvent){
      processRegionEvent(event, localProfile, profiles, frInfo);
    } else {
      // Use the PDX types in serialized form.
      DefaultQuery.setPdxReadSerialized(this.cache, true);
      try {
        processEntryEvent (event, localProfile, profiles, frInfo);
      } finally {
        DefaultQuery.setPdxReadSerialized(this.cache, false);
      }
    }
  }

  private void processRegionEvent(CacheEvent event, Profile localProfile, Profile[] profiles, FilterRoutingInfo frInfo)  
    throws CqException {

    if (logger.fineEnabled()) {
      logger.fine("CQ service processing region event " + event); 
    }
    Integer cqRegionEvent = generateCqRegionEvent(event);
    
    for (int i=-1; i < profiles.length; i++) {
      CacheProfile cf;
      if (i<0) {
        cf = (CacheProfile)localProfile;
        if (cf == null) continue;
      } else {
        cf = (CacheProfile)profiles[i];
      }
      FilterProfile pf = cf.filterProfile;
      if (pf == null || pf.getCqMap().isEmpty()) {
        continue;
      }
      Map cqs = pf.getCqMap();
      HashMap cqInfo = new HashMap();
      Iterator cqIter = cqs.entrySet().iterator();
      while (cqIter.hasNext()){
        Map.Entry cqEntry = (Map.Entry)cqIter.next();
        CqQueryImpl cQuery = (CqQueryImpl)cqEntry.getValue();
        if (!event.isOriginRemote() && event.getOperation().isRegionDestroy() &&
            !((LocalRegion)event.getRegion()).isUsedForPartitionedRegionBucket()) {
          try {
            if (logger.finerEnabled()){
              logger.finer("Closing CQ on region destroy event. CqName :" 
                  + cQuery.getName());
            }
            cQuery.close(false);
          } 
          catch (Exception ex) {
            logger.fine("Failed to Close CQ on region destroy. CqName :" + 
                cQuery.getName(), ex);
          }      
        }
        cqInfo.put(cQuery.getFilterID(), cqRegionEvent);
        cQuery.getVsdStats().updateStats(cqRegionEvent);
      }
      if(pf.isLocalProfile()){
        frInfo.setLocalCqInfo(cqInfo);
      } else {
        frInfo.setCqRoutingInfo(cf.getDistributedMember(), cqInfo);
      }
    }
  }

  private void processEntryEvent(CacheEvent event, Profile localProfile, Profile[] profiles, FilterRoutingInfo frInfo)
  throws CqException {
    HashSet cqUnfilteredEventsSet_newValue = new HashSet();
    HashSet cqUnfilteredEventsSet_oldValue = new HashSet();
    boolean b_cqResults_newValue = false;
    boolean b_cqResults_oldValue = false;
    boolean queryOldValue;
    EntryEvent entryEvent = (EntryEvent)event;
    Object eventKey = entryEvent.getKey();
   
    boolean isDupEvent = ((EntryEventImpl)event).isPossibleDuplicate();
    // The CQ query needs to be applied when the op is update, destroy
    // invalidate and in case when op is create and its an duplicate 
    // event, the reason for this is when peer sends a duplicate event
    // it marks it as create and sends it, so that the receiving node
    // applies it (see DR.virtualPut()).
    boolean opRequiringQueryOnOldValue = (event.getOperation().isUpdate() || 
        event.getOperation().isDestroy() ||
        event.getOperation().isInvalidate() || 
        (event.getOperation().isCreate() && isDupEvent));

    HashMap matchedCqs = new HashMap();
    long executionStartTime = 0;
    for (int i=-1; i < profiles.length; i++) {
      CacheProfile cf;
      if (i<0) {
        cf = (CacheProfile)localProfile;
        if (cf == null) continue;
      } else {
        cf = (CacheProfile)profiles[i];
      }
      FilterProfile pf = cf.filterProfile;
      if (pf == null || pf.getCqMap().isEmpty()) {
        continue;
      }

      Map cqs = pf.getCqMap();
      
      if (this.logger.fineEnabled()) {
        this.logger.fine("Profile for " + cf.peerMemberId + " processing " + cqs.size() + " CQs");
      }

      if (cqs.isEmpty()) {
        continue;
      }
      
      
      // Get new value. If its not retrieved.
      if (cqUnfilteredEventsSet_newValue.isEmpty() && (event.getOperation().isCreate() ||  event.getOperation().isUpdate())) {
        Object newValue = entryEvent.getNewValue(); // TODO OFFHEAP: optimize by not copying the value on to the heap
        if (newValue != null) {
          //We have a new value to run the query on
          cqUnfilteredEventsSet_newValue.add(newValue);
        }
      }
      
      HashMap cqInfo = new HashMap();
      Iterator cqIter = cqs.entrySet().iterator();
      
      while (cqIter.hasNext()){
        Map.Entry cqEntry = (Map.Entry)cqIter.next();
        CqQueryImpl cQuery = (CqQueryImpl)cqEntry.getValue();
        b_cqResults_newValue = false;
        b_cqResults_oldValue = false;
        queryOldValue = false;
        if (cQuery == null){
          continue;
        }        
        String cqName = cQuery.getServerCqName();
        Long filterID = cQuery.getFilterID();

        if (this.logger.fineEnabled()) {
          this.logger.fine("Processing CQ : " + cqName + " Key: " + eventKey);
        }

        Integer cqEvent = null;
        if (matchedCqs.containsKey(cqName)) {
          cqEvent = (Integer)matchedCqs.get(cqName);
          if (logger.fineEnabled()){
            logger.fine("query " + cqName + " has already been processed and returned " + cqEvent);
          }
          if (cqEvent == null) {
            continue;
          }
          // Update the Cache Results for this CQ.
          if (!isDupEvent) {
            if (cqEvent.intValue() == MessageType.LOCAL_CREATE ||
                cqEvent.intValue() == MessageType.LOCAL_UPDATE) {
              cQuery.addToCqResultKeys(eventKey);
            } else if (cqEvent.intValue() == MessageType.LOCAL_DESTROY) {
              cQuery.markAsDestroyedInCqResultKeys(eventKey);
            }
          }
        } else {
          boolean error = false;
          synchronized (cQuery) {
            try {
              // Apply query on new value.
              if (!cqUnfilteredEventsSet_newValue.isEmpty()) {
                executionStartTime = this.stats.startCqQueryExecution();

                b_cqResults_newValue = evaluateQuery(cQuery, 
                    new Object[] {cqUnfilteredEventsSet_newValue});
                this.stats.endCqQueryExecution(executionStartTime);
              }

              // In case of Update, destroy and invalidate.
              // Apply query on oldValue.
              if (opRequiringQueryOnOldValue) {
                // Check if CQ Result is cached, if not apply query on old 
                // value. Currently the CQ Results are not cached for the 
                // Partitioned Regions. Once this is added remove the check 
                // with PR region.
                if (cQuery.cqResultKeysInitialized) {
                  b_cqResults_oldValue = cQuery.isPartOfCqResult(eventKey);
                  // For PR if not found in cache, apply the query on old value.
                  if (cQuery.isPR && b_cqResults_oldValue == false) {
                    queryOldValue = true;
                  } 
                  if (logger.fineEnabled() && !cQuery.isPR && 
                      !b_cqResults_oldValue){
                    logger.fine("Event Key not found in the CQ Result Queue. " +
                        "EventKey : " + eventKey + " CQ Name : " + cqName );
                  }
                } else {
                  queryOldValue = true;
                }

                if (queryOldValue) {
                  if (cqUnfilteredEventsSet_oldValue.isEmpty()) {
                    Object oldValue = entryEvent.getOldValue();
                    if (oldValue != null) {
                      cqUnfilteredEventsSet_oldValue.add(oldValue);
                    }
                  }
                  
                  // Apply query on old value.
                  if (!cqUnfilteredEventsSet_oldValue.isEmpty()) {
                    executionStartTime = this.stats.startCqQueryExecution();
                    b_cqResults_oldValue = evaluateQuery(cQuery, 
                        new Object[] {cqUnfilteredEventsSet_oldValue});
                    this.stats.endCqQueryExecution(executionStartTime);
                  } else {
                    if (logger.fineEnabled()) {
                      logger.fine("old value for event with key " + eventKey
                          + " is null - query execution not performed");
                    }
                  }
                } // Query oldValue
                
              } 
            } catch (Exception ex) {
              // Any exception in running the query should be caught here and 
              // buried because this code is running in-line with the message 
              // processing code and we don't want to kill that thread
              error = true;
              // CHANGE LOG MESSAGE:
              logger.info(
                 LocalizedStrings.CqService_ERROR_WHILE_PROCESSING_CQ_ON_THE_EVENT_KEY_0_CQNAME_1_ERROR_2,
                  new Object[] { ((EntryEvent)event).getKey(), cQuery.getName(), ex.getLocalizedMessage()});
            }

            if (error) {
              cqEvent = Integer.valueOf(MessageType.EXCEPTION); 
            } 
            else { 
              if (b_cqResults_newValue) {
                if (b_cqResults_oldValue) {
                  cqEvent = Integer.valueOf(MessageType.LOCAL_UPDATE);
                } else {
                  cqEvent = Integer.valueOf(MessageType.LOCAL_CREATE);
                }
                // If its create and caching is enabled, cache the key 
                // for this CQ.
                if (!isDupEvent) {
                  cQuery.addToCqResultKeys(eventKey);
                }
              } else if (b_cqResults_oldValue) {
                // Base invalidate operation is treated as destroy.
                // When the invalidate comes through, the entry will no longer 
                // satisfy the query and will need to be deleted.
                cqEvent = Integer.valueOf(MessageType.LOCAL_DESTROY);
                // If caching is enabled, mark this event's key as removed 
                // from the CQ cache.
                if (!isDupEvent) {
                  cQuery.markAsDestroyedInCqResultKeys(eventKey);
                }
              }
            }
          } //end synchronized(cQuery)

          // Get the matching CQs if any.
          synchronized (this.matchingCqMap){
            String query = cQuery.getQueryString();
            ArrayList matchingCqs = (ArrayList)matchingCqMap.get(query);
            if (matchingCqs != null) {
              Iterator iter = matchingCqs.iterator();
              while (iter.hasNext()) {
                String matchingCqName = (String)iter.next();
                if (!matchingCqName.equals(cqName)){
                  matchedCqs.put(matchingCqName, cqEvent);
                  if (logger.fineEnabled()) {
                    logger.fine("Adding CQ into Matching CQ Map: " + 
                      matchingCqName + " Event is: " + cqEvent);
                  }
                }
              }
            }
          }
        }

        if (cqEvent != null && cQuery.isRunning()){
          if (logger.fineEnabled()) {
            logger.fine("Added event to CQ with client-side name: " + 
              cQuery.cqName + " key: " + eventKey + " operation : " + cqEvent);
          }         
          cqInfo.put(filterID, cqEvent);
          CqQueryVsdStats stats = cQuery.getVsdStats();
          if (stats != null) {
            stats.updateStats(cqEvent);
          }
        }
      } 
      if (cqInfo.size() > 0) {
        if(pf.isLocalProfile()){
          if(this.logger.fineEnabled()){
            this.logger.fine("Setting local CQ matches to " + cqInfo);
          }
          frInfo.setLocalCqInfo(cqInfo);
        } else {
          if(this.logger.fineEnabled()){
            this.logger.fine("Setting CQ matches for " + cf.getDistributedMember()
                + " to " + cqInfo);
          }
          frInfo.setCqRoutingInfo(cf.getDistributedMember(), cqInfo);
        }
      }
    } // iteration over Profiles.
  }


/*  public void processEvents (EnumListenerEvent operation, CacheEvent event,
                             ClientUpdateMessage clientMessage, 
                             CM<ClientProxyMembershipID, CM<CqQuery, Boolean>> clientIds) 
  throws CqException {
  
    //Is this a region event or an entry event
    if (event instanceof RegionEvent){
      processRegionEvent(operation, event, clientMessage, clientIds);
    } else {
      processEntryEvent (operation, event, clientMessage, clientIds);
    }

  }
  
  private void processRegionEvent(EnumListenerEvent operation, CacheEvent event,
                                  ClientUpdateMessage clientMessage, 
                                  CM<ClientProxyMembershipID, CM<CqQuery, Boolean>> clientIds) 
  throws CqException {
    
    if (logger.fineEnabled()) {
      logger.fine("Processing region event for region " + 
                  ((LocalRegion)(event.getRegion())).getName());
    }
    HashMap filteredCqs = new HashMap(); 
    Integer cqRegionEvent = generateCqRegionEvent(operation);
    Iterator it = clientIds.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry me = (Map.Entry)it.next();
      ClientProxyMembershipID clientId = (ClientProxyMembershipID)me.getKey();
      CM cqsToBooleans = (CM)me.getValue();
      if (cqsToBooleans == null) {
        continue;
      }
      Set<CqQuery> cqs = cqsToBooleans.keySet();
      if (cqs.isEmpty()) {
        continue;
      }
      filteredCqs.clear();
      Iterator cqIt = cqs.iterator();
      while (cqIt.hasNext()) {
        CqQueryImpl cQuery = (CqQueryImpl)cqIt.next();
        if (operation == EnumListenerEvent.AFTER_REGION_DESTROY) {
          try {
            if (logger.finerEnabled()){
              logger.finer("Closing CQ on region destroy event. CqName :" 
                            + cQuery.getName());
            }
            cQuery.close(false);
          } 
          catch (Exception ex) {
            logger.fine("Failed to Close CQ on region destroy. CqName :" + 
                         cQuery.getName(), ex);
          }      

        }
        filteredCqs.put(cQuery.cqName, cqRegionEvent);
        cQuery.getVsdStats().updateStats(cqRegionEvent);
        
      }
      if (!filteredCqs.isEmpty()){
        ((ClientUpdateMessageImpl)clientMessage).addClientCqs(
            clientId, filteredCqs);
      }
      
    }
  
  }
  
  private void processEntryEvent(EnumListenerEvent operation, CacheEvent event,
                                 ClientUpdateMessage clientMessage, 
                                 CM<ClientProxyMembershipID, CM<CqQuery, Boolean>> clientIds)
  throws CqException {
    HashSet cqUnfilteredEventsSet_newValue = new HashSet();
    HashSet cqUnfilteredEventsSet_oldValue = new HashSet();
    boolean b_cqResults_newValue = false;
    boolean b_cqResults_oldValue = false;
    EntryEvent entryEvent = (EntryEvent)event;
    Object eventKey = entryEvent.getKey();
    if (operation == EnumListenerEvent.AFTER_CREATE || 
        operation == EnumListenerEvent.AFTER_UPDATE) {
      if (entryEvent.getNewValue() != null) {
        //We have a new value to run the query on
        cqUnfilteredEventsSet_newValue.clear();
        cqUnfilteredEventsSet_newValue.add(entryEvent.getNewValue());
      }
    }
     
    HashMap matchedCqs = new HashMap();
    long executionStartTime = 0;
    Iterator it = clientIds.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry me = (Map.Entry)it.next();
      ClientProxyMembershipID clientId = (ClientProxyMembershipID)me.getKey();
      if (logger.fineEnabled()) {
        logger.fine("Processing event for CQ filter, ClientId : " + clientId);
      }
      CM cqsToBooleans = (CM)me.getValue();
      if (cqsToBooleans == null) {
        continue;
      }
      Set<CqQuery> cqs = cqsToBooleans.keySet();
      if (cqs.isEmpty()) {
        continue;
      }
      HashMap filteredCqs = new HashMap();
      Iterator cqIt = cqs.iterator();
      while (cqIt.hasNext()) {
        CqQueryImpl cQuery = (CqQueryImpl)cqIt.next();
        b_cqResults_newValue = false;
        b_cqResults_oldValue = false;       
        if (cQuery == null || !(cQuery.isRunning())){
          continue;
        }        
        String cqName = cQuery.getServerCqName();
        Integer cqEvent = null;
        if (matchedCqs.containsKey(cqName)) {
          if (logger.fineEnabled()){
            logger.fine("Similar cq/query is already processed, getting the cq event-type from the matched cq.");
          }
          cqEvent = (Integer)matchedCqs.get(cqName);
        } else {
          boolean error = false;
          boolean hasSeenEvent = false;
          HashSet cqEventKeys = null;
          synchronized (cQuery) {
            try {
              // Apply query on new value.
              if (!cqUnfilteredEventsSet_newValue.isEmpty()) {
                executionStartTime = this.stats.startCqQueryExecution();
                b_cqResults_newValue = evaluateQuery(cQuery, 
                                                     new Object[] {cqUnfilteredEventsSet_newValue});
                this.stats.endCqQueryExecution(executionStartTime);
              }
              // Check if old value is cached, if not apply query on old value.
              if (cqToCqEventKeysMap != null) {
                synchronized (cqToCqEventKeysMap) {
                  if ((cqEventKeys = (HashSet)cqToCqEventKeysMap.get(cqName)) != null) {
                    hasSeenEvent = cqEventKeys.contains(eventKey);
                  }
                }
              }  
              if (!hasSeenEvent) {
                // get the oldValue.
                // In case of Update, destroy and invalidate.
                if (operation == EnumListenerEvent.AFTER_UPDATE || 
                    operation == EnumListenerEvent.AFTER_DESTROY ||
                    operation == EnumListenerEvent.AFTER_INVALIDATE) {
                  if (entryEvent.getOldValue() != null) {
                    cqUnfilteredEventsSet_oldValue.clear();
                    cqUnfilteredEventsSet_oldValue.add(entryEvent.getOldValue());
                    // Apply query on old value.
                    executionStartTime = this.stats.startCqQueryExecution();
                    b_cqResults_oldValue = evaluateQuery(cQuery, 
                                                         new Object[] {cqUnfilteredEventsSet_oldValue});
                    this.stats.endCqQueryExecution(executionStartTime);
                  }
                }
              }
            }
            catch (Exception ex) {
              //Any exception in running the query
              // should be caught here and buried
              //because this code is running inline with the 
              //message processing code and we don't want to 
              //kill that thread
              error = true;
              logger.info(
                LocalizedStrings.CqService_ERROR_WHILE_PROCESSING_CQ_ON_THE_EVENT_KEY_0_CQNAME_1_CLIENTID_2_ERROR_3,
                new Object[] { ((EntryEvent)event).getKey(), cQuery.getName(), clientId, ex.getLocalizedMessage()});
            }
            
            if (error) {
              cqEvent = Integer.valueOf(MessageType.EXCEPTION);  
            } 
            else {
              if (b_cqResults_newValue) {
                if (hasSeenEvent || b_cqResults_oldValue) {
                  cqEvent = Integer.valueOf(MessageType.LOCAL_UPDATE);
                } else {
                  cqEvent = Integer.valueOf(MessageType.LOCAL_CREATE);
                }
                // If its create and caching is enabled, cache the key for this CQ.
                if (!hasSeenEvent && cqEventKeys != null) {
                  cqEventKeys.add(eventKey);
                } 
              } 
              else if (hasSeenEvent || (b_cqResults_oldValue)) {
                // Base invalidate operation is treated as destroy.
                // When the invalidate comes through, the entry will no longer satisfy 
                // the query and will need to be deleted.
                cqEvent = Integer.valueOf(MessageType.LOCAL_DESTROY);
                // If caching is enabled, remove this event's key from the cache.
                if (hasSeenEvent && cqEventKeys != null) {
                  cqEventKeys.remove(eventKey);
                }
              }
            }
            
          } //end synchronized(cQuery)

          // Get the matching CQs if any.
          synchronized (this.matchingCqMap){
            String query = cQuery.getQueryString();
            ArrayList matchingCqs = (ArrayList)matchingCqMap.get(query);
            if (matchingCqs != null) {
              Iterator iter = matchingCqs.iterator();
              while (iter.hasNext()) {
                String matchingCqName = (String)iter.next();
                if (!matchingCqName.equals(cqName)){
                  matchedCqs.put(matchingCqName, cqEvent);
                }
              }
            }
          }
          
        }
 
        if (cqEvent != null){
          if (logger.fineEnabled()) {
            logger.fine("Event is added for the CQ, CqName (clientside): " + cQuery.cqName + 
            " With CQ Op : " + cqEvent + " for Client : " + clientId);
          }
          filteredCqs.put(cQuery.cqName, cqEvent);
          cQuery.getVsdStats().updateStats(cqEvent);
        }
 
      } // iteration over cqsToBooleans.keySet()
      if (!filteredCqs.isEmpty()){
        logger.fine("Adding event map for client : "+clientId + " with event map size : "+filteredCqs.size());
        ((ClientUpdateMessageImpl)clientMessage).addClientCqs(clientId, filteredCqs);
      }
    } // iteration over clientIds.entrySet()
  }
*/ 
 
  private Integer generateCqRegionEvent(CacheEvent event) {
    Integer cqEvent = null;
    if (event.getOperation().isRegionDestroy()) {
      cqEvent = Integer.valueOf(MessageType.DESTROY_REGION);
    }
    else if (event.getOperation().isRegionInvalidate()) {
      cqEvent = Integer.valueOf(MessageType.INVALIDATE_REGION);
    }
    else if (event.getOperation().isClear()){
      cqEvent = Integer.valueOf(MessageType.CLEAR_REGION);
    } 
    return cqEvent;
  }

  
  /**
   * Manages the CQs created for the base region.
   * This is managed here, instead of on the base region; since the cq could be
   * created on the base region, before base region is created (using newCq()).
   */
  public void addToBaseRegionToCqNameMap(String regionName, String cqName){
    synchronized(this.baseRegionToCqNameMap){
      ArrayList cqs = (ArrayList)this.baseRegionToCqNameMap.get(regionName);
      if (cqs == null){
        cqs = new ArrayList();
      }
      cqs.add(cqName);
      this.baseRegionToCqNameMap.put(regionName, cqs);
    }
  }
  
  public void removeFromBaseRegionToCqNameMap(String regionName, String cqName){
    synchronized(this.baseRegionToCqNameMap){
      ArrayList cqs = (ArrayList)this.baseRegionToCqNameMap.get(regionName);
      if (cqs != null){
        cqs.remove(cqName);
        if (cqs.isEmpty()){
          this.baseRegionToCqNameMap.remove(regionName);
        } else {
          this.baseRegionToCqNameMap.put(regionName, cqs);
        }
      }
    }
  }
  
  /**
   * Get the VSD ststs for CQ Service. There is one CQ Service per cache
   * @return reference to VSD stats object for the CQ service
   */
  public CqServiceVsdStats getCqServiceVsdStats() {
    return stats;
  }

  /**
   * Removes this CQ from CQ event Cache map. 
   * This disables the caching events for this CQ. 
   * @param cqName
   */
  /*
  synchronized public void removeCQFromCaching(String cqName){
    if (cqToCqEventKeysMap != null) {
      // Take a lock on CqQuery object. In processEvents the maps are
      // handled under CqQuery object.
      if (cqToCqEventKeysMap != null){
        synchronized (cqToCqEventKeysMap) {        
          cqToCqEventKeysMap.remove(cqName);
        }
      }
    } 
  }
  */
  
  /**
   * Returns the CQ event cache map.
   * @return HashMap cqToCqEventKeysMap
   * 
   * Caller must synchronize on the returned value in order
   * to inspect.
   */
  /*
  public HashMap getCqToCqEventKeysMap(){
    return cqToCqEventKeysMap; 
  }
  */
  
  /**
   * Adds the query from the given CQ to the matched CQ map.
   * @param cq
   */
  public void addToMatchingCqMap(CqQueryImpl cq) {
    synchronized(this.matchingCqMap){
      String cqQuery = cq.getQueryString();
      ArrayList matchingCQs = null;
      if (!matchingCqMap.containsKey(cqQuery)){
        matchingCQs = new ArrayList();
        matchingCqMap.put(cqQuery, matchingCQs);
        this.stats.incUniqueCqQuery();
      } else {
        matchingCQs = (ArrayList)matchingCqMap.get(cqQuery);
      }
      matchingCQs.add(cq.getServerCqName());
      if (logger.fineEnabled()) {
        this.logger.fine("Adding CQ into MatchingCQ map, CQName: " + cq.getServerCqName() +
            " Number of matched querys are : " + matchingCQs.size());
      }
    }
  }

  /**
   * Removes the query from the given CQ from the matched CQ map.
   * @param cq
   */
  public void removeFromMatchingCqMap(CqQueryImpl cq) {
    synchronized(this.matchingCqMap){
      String cqQuery = cq.getQueryString();
      if (matchingCqMap.containsKey(cqQuery)){
        ArrayList matchingCQs = (ArrayList)matchingCqMap.get(cqQuery);
        matchingCQs.remove(cq.getServerCqName());
        if (logger.fineEnabled()) {
          this.logger.fine("Removing CQ from MatchingCQ map, CQName: " + cq.getServerCqName() +
              " Number of matched querys are : " + matchingCQs.size());
        }
        if (matchingCQs.isEmpty()){
          matchingCqMap.remove(cqQuery);
          this.stats.decUniqueCqQuery();
        }
      } 
    }
  }

  /**
   * Returns the matching CQ map.
   * @return HashMap matchingCqMap
   */
  public HashMap getMatchingCqMap(){
    return matchingCqMap; 
  }

  /**
   * Applies the query on the event.
   * This method takes care of the performance related changed done to improve 
   * the CQ-query performance. When CQ-query is executed first time, it saves the 
   * query related information in the execution context and uses that info in later 
   * executions. 
   * @param cQuery
   * @param event
   * @return boolean
   */
  private boolean evaluateQuery(CqQueryImpl cQuery, Object[] event) throws Exception {
    ExecutionContext execContext = cQuery.getQueryExecutionContext();
    execContext.reset();
    execContext.setBindArguments(event);
    boolean status = false;
    
    // Check if the CQ query is executed once.
    // If not execute the query in normal way.
    // During this phase the query execution related info are stored in the
    // ExecutionContext.
    if (execContext.getScopeNum() <= 0) {
      SelectResults results = (SelectResults)((DefaultQuery)cQuery.getQuery()).executeUsingContext(execContext);
      if (results != null && results.size() > 0) {
        status = true;
      } 
    } else {
      // Execute using the saved query info (in ExecutionContext).
      // This avoids building resultSet, index look-up, generating build-plans
      // that are not required for; query execution on single object.
      CompiledSelect cs = ((DefaultQuery)(cQuery.getQuery())).getSelect();
      status = cs.evaluateCq(execContext);
    }
    return status;
  }

  UserAttributes getUserAttributes(String cqName) {
    return this.cqNameToUserAttributesMap.get(cqName);
  }
  
//  public static void memberLeft(String poolName) {
//    if (cqServiceSingleton != null  && !cqServiceSingleton.isServer()) {
//      cqServiceSingleton.sendMemberDisconnectedMessageToCqs(poolName);
//    }
//  }
//  
//  public static void memberCrashed(String poolName) {
//    if (cqServiceSingleton != null && !cqServiceSingleton.isServer()) {
//      cqServiceSingleton.sendMemberDisconnectedMessageToCqs(poolName);
//    }
//  }
//  
  
  public static void cqsDisconnected(Pool pool) {
    synchronized(cqPoolsConnected) {
      if (cqServiceSingleton != null && !cqServiceSingleton.isServer()) {
         cqServiceSingleton.invokeCqsConnected(pool, false);
      }
    }
  }
  
  public static void cqsConnected(Pool pool) {
    if (cqServiceSingleton != null && !cqServiceSingleton.isServer()) {
      cqServiceSingleton.invokeCqsConnected(pool, true);
    }
  }
  
  /**
   * Let cq listeners know that they are connected or disconnected
   */
  public void invokeCqsConnected(Pool pool, boolean connected) {
    String poolName = pool.getName();
    //Check to see if we are already connected/disconnected.
    //If state has not changed, do not invoke another connected/disconnected 
    synchronized(cqPoolsConnected) {
      //don't repeatily send same connect/disconnect message to cq's on repeated fails of RedundancySatisfier
      if (cqPoolsConnected.containsKey(poolName) && connected == (Boolean) cqPoolsConnected.get(poolName)){
        return;
      }
      cqPoolsConnected.put(poolName, connected);
    
      CqQuery[] cqs = this.getAllCqs();
      int numCqs = cqs.length;
      String cqName = null;
      for (int i = 0; i < numCqs; i++) {
        try {
          CqQuery query = cqs[i];
          if (query == null) {
            continue;
          }
          
          cqName = query.getName();
          CqQueryImpl cQuery = (CqQueryImpl) this.getCq(cqName);
  
          //Check cq pool to determine if the pool matches, if not continue.
          //Also if the connected state is already the same, we do not have to send status again.
	  if (cQuery == null) {
            continue;
          }
          Pool cqPool = cQuery.getCQProxy().getPool();
          if (cQuery.isConnected() == connected || !cqPool.getName().equals(poolName)) {
            continue;
          }
          
          if ((!cQuery.isRunning() && cQuery.getQueuedEvents() == null)) {
            if (logger.fineEnabled()) {
              logger.fine("Unable to invoke CqListener, "
                  + ((cQuery == null) ? "CQ not found" : " CQ is Not running")
                  + ", CqName : " + cqName);
            }
            continue;
          }
        
          this.invokeCqConnectedListeners(cqName, cQuery, connected);
        } catch (VirtualMachineError e) {
          SystemFailure.initiateFailure(e);
          throw e;
        } catch (Throwable t) {
          SystemFailure.checkFailure();
          logger.warning(LocalizedStrings.CqService_ERROR_SENDING_CQ_CONNECTION_STATUS, cqName,
              t);
  
          if (t instanceof VirtualMachineError) {
            logger
                .warning(
                    LocalizedStrings.CqService_VIRTUALMACHINEERROR_PROCESSING_CQLISTENER_FOR_CQ_0,
                    cqName, t);
            return;
          }
        }
      }
    }
  }
}

