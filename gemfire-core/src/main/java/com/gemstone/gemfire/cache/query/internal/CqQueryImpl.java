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

import com.gemstone.gemfire.i18n.LogWriterI18n;

import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.client.internal.ProxyCache;
import com.gemstone.gemfire.cache.client.internal.ServerCQProxy;
import com.gemstone.gemfire.cache.client.internal.ServerRegionProxy;
import com.gemstone.gemfire.cache.client.internal.UserAttributes;
import com.gemstone.gemfire.cache.query.CqAttributes;
import com.gemstone.gemfire.cache.query.CqAttributesMutator;
import com.gemstone.gemfire.cache.query.CqClosedException;
import com.gemstone.gemfire.cache.query.CqEvent;
import com.gemstone.gemfire.cache.query.CqException;
import com.gemstone.gemfire.cache.query.CqExistsException;
import com.gemstone.gemfire.cache.query.CqListener;
import com.gemstone.gemfire.cache.query.CqQuery;
import com.gemstone.gemfire.cache.query.CqState;
import com.gemstone.gemfire.cache.query.CqStatistics;
import com.gemstone.gemfire.cache.query.CqStatusListener;
import com.gemstone.gemfire.cache.query.Query;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.RegionNotFoundException;
import com.gemstone.gemfire.cache.query.CqResults;
import com.gemstone.gemfire.internal.cache.EnumListenerEvent;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientProxy;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.security.GemFireSecurityException;
import com.gemstone.org.jgroups.util.StringId;

/**
 * @author rmadduri
 * @author anil
 * @since 5.5
 * Represents the CqQuery object. Implements CqQuery API and CqAttributeMutator. 
 *  
 */
public class CqQueryImpl implements CqQuery, DataSerializable {
  
  protected String cqName;
  
  protected String queryString;
  
  /** 
   * This holds the keys that are part of the CQ query results.
   * Using this CQ engine can determine whether to execute 
   * query on old value from EntryEvent, which is an expensive
   * operation. 
   */
  private volatile HashMap<Object, Object> cqResultKeys;

  private static final Object TOKEN = new Object();

  /** 
   * This maintains the keys that are destroyed while the Results
   * Cache is getting constructed. This avoids any keys that are
   * destroyed (after query execution) but is still part of the 
   * CQs result.
   */
  private HashSet<Object> destroysWhileCqResultsInProgress;
  
  /**
   * To indicate if the CQ results key cache is initialized.
   */
  public volatile boolean cqResultKeysInitialized = false;
  
  protected LocalRegion cqBaseRegion;
  
  /** Boolean flag to see if the CQ is on Partitioned Region */
  public volatile boolean isPR = false;
  
  private Query query = null;
  
  private ClientProxyMembershipID clientProxyId = null;
  
  private CacheClientNotifier ccn = null;
  
  private CqAttributes cqAttributes = null;
  
  private LogWriterI18n logger;
  
  private LogWriterI18n securityLogger;
  
  private CqService cqService;
  
  private String regionName;
  
  private String serverCqName;
  
  private boolean isDurable = false ;
  
  private volatile ServerCQProxy cqProxy;
  
  // Stats counters
  private CqStatisticsImpl cqStats;
  
  private CqQueryVsdStats stats;
    
  private final CqStateImpl cqState = new CqStateImpl();
  
  private EnumListenerEvent cqOperation;
    
  private ExecutionContext queryExecutionContext = null;

  private ProxyCache proxyCache = null;
  
  /** identifier assigned to this query for FilterRoutingInfos */
  private Long filterID;
  
  /** To queue the CQ Events arriving during CQ execution with 
   * initial Results.
   */
  private volatile ConcurrentLinkedQueue queuedEvents = null;
  
  public final Object queuedEventsSynchObject = new Object();
  
  public static TestHook testHook = null;
  
  private boolean connected = false;

  /**
   * @return the filterID
   */
  public Long getFilterID() {
    return this.filterID;
  }

  /**
   * @param filterID the filterID to set
   */
  public void setFilterID(Long filterID) {
    this.filterID = filterID;
  }

  /**
   * Constructor.
   */
  public CqQueryImpl(){
  }
  
  public CqQueryImpl(CqService cqService, String cqName, String queryString, CqAttributes cqAttributes, ServerCQProxy serverProxy, boolean isDurable)  {
    this.cqName = cqName;
    this.cqAttributes = cqAttributes;
    this.queryString = queryString;
    this.logger = cqService.getCache().getLoggerI18n();
    this.securityLogger = cqService.getCache().getSecurityLoggerI18n();
    this.cqService = cqService;
    this.cqProxy = serverProxy;
    this.isDurable = isDurable ;
    serverCqName = cqName; // On Client Side serverCqName and cqName will be same.
  }
  
  /** 
   * returns CQ name
   */
  public String getName() {
    return this.cqName;
  }
  
  /** 
   * sets the CqName.
   */
  public void setName(String cqName) {
    this.cqName = this.serverCqName = cqName;
    //logger.fine("CqName is : " + this.cqName + " ServerCqName is : " + this.serverCqName);
  }

  public void setCqService(CqService cqService) {
    this.cqService = cqService;
    this.logger = cqService.getCache().getLoggerI18n();
  }
  
  /**
   * get the region name in CQ query
   */
  public String getRegionName() {
    return this.regionName;
  }

  /**
   * Initializes the CqQuery.
   * creates Query object, if its valid adds into repository.
   */
  public void initCq() throws CqException, CqExistsException {
    // Check if query is valid.
    validateCq();
    
    // Add cq into meta region.    
    // Check if Name needs to be generated.
    if (cqName == null) {
      // in the case of cqname internally generated, the CqExistsException needs
      // to be taken care internally.
      while(true) {
        setName(cqService.generateCqName());
        try {
          cqService.addToCqMap(this);
        } catch (CqExistsException ex) {
          logger.fine("Got CqExistsException while intializing cq : " + cqName + " Error : " + ex.getMessage());
          continue;
        }
        break;
      } 
    } else {
      // CQ Name is supplied by user.
      cqService.addToCqMap(this);
    }
      
    // Initialize the VSD statistics
    StatisticsFactory factory = cqService.getCache().getDistributedSystem();
    this.stats = new CqQueryVsdStats(factory, this.serverCqName);
    this.cqStats = new CqStatisticsImpl(this);
    
    // Update statistics with CQ creation.
    this.cqService.stats.incCqsStopped();
    this.cqService.stats.incCqsCreated();
    this.cqService.stats.incCqsOnClient();
  }

  public void updateCqCreateStats() {
    // Initialize the VSD statistics
    StatisticsFactory factory = cqService.getCache().getDistributedSystem();
    this.stats = new CqQueryVsdStats(factory, this.serverCqName);
    this.cqStats = new CqStatisticsImpl(this);

    // Update statistics with CQ creation.
    this.cqService.stats.incCqsStopped();
    this.cqService.stats.incCqsCreated();
    this.cqService.stats.incCqsOnClient();
  }

  /**
   * Validates the CQ. Checks for cq constraints. 
   * Also sets the base region name.
   */
  public void validateCq() {
    Cache cache = cqService.getCache();
    DefaultQuery locQuery = (DefaultQuery)((GemFireCacheImpl)cache).getLocalQueryService().newQuery(this.queryString);
    this.query = locQuery;
//    assert locQuery != null;
    
    // validate Query.
    Object[] parameters = new Object[0]; // parameters are not permitted
    
    // check that it is only a SELECT statement (possibly with IMPORTs)
    CompiledSelect select = locQuery.getSimpleSelect();
    if (select == null) {
      throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_CQ_QUERIES_MUST_BE_A_SELECT_STATEMENT_ONLY.toLocalizedString());
    }    
    
    // must not be a DISTINCT select
    if (select.isDistinct()) {
      throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_SELECT_DISTINCT_QUERIES_NOT_SUPPORTED_IN_CQ.toLocalizedString());
    }
    
    // get the regions referenced in this query
    Set regionsInQuery = locQuery.getRegionsInQuery(parameters);
    // check for more than one region is referenced in the query
    // (though it could still be one region referenced multiple times)
    if (regionsInQuery.size() > 1 || regionsInQuery.size() < 1) {
      throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_CQ_QUERIES_MUST_REFERENCE_ONE_AND_ONLY_ONE_REGION.toLocalizedString());
    }
    this.regionName = (String)regionsInQuery.iterator().next();
    
    // make sure the where clause references no regions
    Set regions = new HashSet();
    CompiledValue whereClause = select.getWhereClause();
    if (whereClause != null) {
      whereClause.getRegionsInQuery(regions, parameters);
      if (!regions.isEmpty()) {
        throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_THE_WHERE_CLAUSE_IN_CQ_QUERIES_CANNOT_REFER_TO_A_REGION.toLocalizedString());
      }
    }
    List fromClause = select.getIterators();
    // cannot have more than one iterator in FROM clause
    if (fromClause.size() > 1) {
      throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_CQ_QUERIES_CANNOT_HAVE_MORE_THAN_ONE_ITERATOR_IN_THE_FROM_CLAUSE.toLocalizedString());
    }
    
    // the first iterator in the FROM clause must be just a CompiledRegion
    CompiledIteratorDef itrDef = (CompiledIteratorDef)fromClause.get(0);
    // By process of elimination, we know that the first iterator contains a reference
    // to the region. Check to make sure it is only a CompiledRegion
    if (!(itrDef.getCollectionExpr() instanceof CompiledRegion)) {
      throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_CQ_QUERIES_MUST_HAVE_A_REGION_PATH_ONLY_AS_THE_FIRST_ITERATOR_IN_THE_FROM_CLAUSE.toLocalizedString());
    }
    
    // must not have any projections
    List projs = select.getProjectionAttributes();
    if (projs != null) {
      throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_CQ_QUERIES_DO_NOT_SUPPORT_PROJECTIONS.toLocalizedString());
    }
    
    // check the orderByAttrs, not supported
    List orderBys = select.getOrderByAttrs();
    if (orderBys != null) {
      throw new UnsupportedOperationException(LocalizedStrings.CqQueryImpl_CQ_QUERIES_DO_NOT_SUPPORT_ORDER_BY.toLocalizedString());
    }   
    
    // Set Query ExecutionContext, that will be used in later execution.
    this.setQueryExecutionContext(new QueryExecutionContext(null, this.cqService.getCache()));
    
  }
  
  
//  public void checkAndSetCqOnRegion(){
//    LocalRegion queryRegion = (LocalRegion)this.cqService.getCache().getRegion(this.regionName);
//    queryRegion.requiresOldValueInEvents(true);
//  }
  
  
  /**
   * Register the Query on server.
   * @param p_clientProxyId
   * @param p_ccn
   * @throws CqException
   */
  public void registerCq(ClientProxyMembershipID p_clientProxyId, 
      CacheClientNotifier p_ccn, int p_cqState) 
      throws CqException, RegionNotFoundException {      
    
    CacheClientProxy clientProxy = null;    
    this.clientProxyId = p_clientProxyId; 
    //servConnection = serverSideConnection;

    if (p_ccn != null) {
      this.ccn = p_ccn;
      clientProxy = p_ccn.getClientProxy(p_clientProxyId, true);
    }
    
    /*
    try {
      initCq();
    } catch (CqExistsException cqe) {
      // Should not happen.
      throw new CqException(LocalizedStrings.CqQueryImpl_UNABLE_TO_CREATE_CQ_0_ERROR__1.toLocalizedString(new Object[] { cqName, cqe.getMessage()}));
    }
    */
    
    validateCq();
    
    StringId msg = LocalizedStrings.ONE_ARG;
    Throwable t = null;
    try {
      this.query = constructServerSideQuery();
      logger.fine("Server side query for the cq: " + cqName + " is: " + this.query.getQueryString());
    } catch (Exception ex) {
      t = ex;
      if (ex instanceof ClassNotFoundException) {
        msg = LocalizedStrings.CqQueryImpl_CLASS_NOT_FOUND_EXCEPTION_THE_ANTLRJAR_OR_THE_SPCIFIED_CLASS_MAY_BE_MISSING_FROM_SERVER_SIDE_CLASSPATH_ERROR_0;
      } else {
        msg = LocalizedStrings.CqQueryImpl_ERROR_WHILE_PARSING_THE_QUERY_ERROR_0;
      }
    } finally {
      if (t != null) {
        if (logger.fineEnabled()) {
          logger.fine(msg.toLocalizedString(t), t);
        }
        throw new CqException(msg.toLocalizedString(t));
      }
    }
    
    // Update Regions Book keeping.
    // TODO replace getRegion() with getRegionByPathForProcessing() so this doesn't block
    // if the region is still being initialized
    this.cqBaseRegion = (LocalRegion)cqService.getCache().getRegion(regionName); 
    if (this.cqBaseRegion == null) {
      throw new RegionNotFoundException(LocalizedStrings.CqQueryImpl_REGION__0_SPECIFIED_WITH_CQ_NOT_FOUND_CQNAME_1
          .toLocalizedString(new Object[] {regionName, this.cqName}));
    }
    
    // Make sure that the region is partitioned or 
    // replicated with distributed ack or global.
    DataPolicy dp = this.cqBaseRegion.getDataPolicy();
    this.isPR = dp.withPartitioning();
    if (!(this.isPR || dp.withReplication())) {
      String errMsg = "The region " + this.regionName + 
          "  specified in CQ creation is neither replicated nor partitioned; " +
      "only replicated or partitioned regions are allowed in CQ creation.";
      if (logger.fineEnabled()){
        logger.fine(errMsg);
      }
      throw new CqException(errMsg);
    }
    if ((dp.withReplication() && 
        (!(cqBaseRegion.getAttributes().getScope().isDistributedAck() || 
        cqBaseRegion.getAttributes().getScope().isGlobal())))) {
      String errMsg = "The replicated region " + this.regionName + 
          " specified in CQ creation does not have scope supported by CQ." +
          " The CQ supported scopes are DISTRIBUTED_ACK and GLOBAL.";
      if (logger.fineEnabled()){
        logger.fine(errMsg);
      }
      throw new CqException(errMsg);
    }
    
    //checkAndSetCqOnRegion();
    
    //Can be null by the time we are here
    if (clientProxy != null) {
      clientProxy.incCqCount();
      if (clientProxy.hasOneCq()) {
        cqService.stats.incClientsWithCqs();
      }
      if (logger.fineEnabled() ) {
        logger.fine("Added CQ to the base region: " + cqBaseRegion.getFullPath() + " With key as: " + serverCqName);
      }
    }
    
    // this.cqService.addToCqEventKeysMap(this);
    this.updateCqCreateStats();
    
    // Initialize the state of CQ.
    if(this.cqState.getState() != p_cqState) {
      setCqState(p_cqState);
      // Add to the matchedCqMap.
      cqService.addToMatchingCqMap(this);    
    }

    // Initialize CQ results (key) cache.
    if(CqService.MAINTAIN_KEYS) {
      this.cqResultKeys = new HashMap <Object, Object>();
      // Currently the CQ Result keys are not cached for the Partitioned 
      // Regions. Supporting this with PR needs more work like forcing 
      // query execution on primary buckets only; and handling the bucket
      // re-balancing. Once this is added remove the check with PR region.
      // Only the events which are seen during event processing is 
      // added to the results cache (not from the CQ Results).
      if (this.isPR){
        this.setCqResultsCacheInitialized();
      } else {
        this.destroysWhileCqResultsInProgress = new HashSet <Object>();
      }
    } 

    if (p_ccn != null) {
      try {
        cqService.addToCqMap(this);
      } catch (CqExistsException cqe) {
        // Should not happen.
        throw new CqException(LocalizedStrings.CqQueryImpl_UNABLE_TO_CREATE_CQ_0_ERROR__1.toLocalizedString(new Object[] { cqName, cqe.getMessage()}));
      }
      this.cqBaseRegion.getFilterProfile().registerCq(this);
    }
  }

  /**
   * For Test use only.
   * @return CQ Results Cache.
   */
  public Set<Object> getCqResultKeyCache() {
    if (this.cqResultKeys != null){
      synchronized (this.cqResultKeys) {
        return Collections.synchronizedSet(new HashSet<Object>(this.cqResultKeys.keySet()));
      }
    } else {
      return null;
    }
  }

  /**
   * Returns if the passed key is part of the CQs result set.
   * This method needs to be called once the CQ result key caching
   * is completed (cqResultsCacheInitialized is true).
   * @param key
   * @return true if key is in the Results Cache.
   */
  public boolean isPartOfCqResult(Object key) {
    // Handle events that may have been deleted,
    // but added by result caching.
    if (this.cqResultKeys == null) {
      if (this.logger.warningEnabled()) {        
        logger.warning(LocalizedStrings.CqQueryImpl_Null_CQ_Result_Key_Cache_0);
        return false;
      }
    }

    synchronized (this.cqResultKeys) {
      if (this.destroysWhileCqResultsInProgress != null) {
        //this.logger.fine("Removing keys from Destroy Cache  For CQ :" + 
        //this.cqName + " Keys :" + this.destroysWhileCqResultsInProgress);
        for (Object k : this.destroysWhileCqResultsInProgress){
          this.cqResultKeys.remove(k);  
        }
        this.destroysWhileCqResultsInProgress = null;
      }
      return this.cqResultKeys.containsKey(key);
    }
  }
    
  /**
   * Adds into the CQ Results key cache.
   * @param key
   */
  public void addToCqResultKeys(Object key) {
    if (!CqService.MAINTAIN_KEYS){
      return;
    }
    
    //this.logger.fine("Adding key to Results Cache For CQ :" + 
    //this.cqName + " key :" + key);
    if (this.cqResultKeys != null) {
      synchronized (this.cqResultKeys) { 
        this.cqResultKeys.put(key, TOKEN);
        if (!this.cqResultKeysInitialized){
          // This key could be coming after add, destroy.
          // Remove this from destroy queue.
          //this.logger.fine("Removing key from Destroy Cache For CQ :" + 
          //this.cqName + " key :" + key);
          if (this.destroysWhileCqResultsInProgress != null){
            this.destroysWhileCqResultsInProgress.remove(key);
          }
        }
      }
    }
  }

  
  /**
   * Removes the key from CQ Results key cache. 
   * @param key
   * @param isTokenMode if true removes the key if its in destroy token mode
   *          if false removes the key without any check.
   */
  public void removeFromCqResultKeys(Object key, boolean isTokenMode) {
    if (!CqService.MAINTAIN_KEYS){
      return;
    }
    //this.logger.fine("Removing key from Results Cache For CQ :" + 
    //this.cqName + " key :" + key);
    if (this.cqResultKeys != null) {
      synchronized (this.cqResultKeys) { 
        if (isTokenMode && this.cqResultKeys.get(key) != Token.DESTROYED){
          return;
        }
        this.cqResultKeys.remove(key);
        if (!this.cqResultKeysInitialized){
          //this.logger.fine("Adding key to Destroy Cache For CQ :" + 
          //this.cqName + " key :" + key);
          if (this.destroysWhileCqResultsInProgress != null){
            this.destroysWhileCqResultsInProgress.add(key);
          }
        }
      }
    }
  }
  
  /**
   * Marks the key as destroyed in the CQ Results key cache.
   * @param key
   */
  public void markAsDestroyedInCqResultKeys(Object key){
    if (!CqService.MAINTAIN_KEYS){
      return;
    }
    //this.logger.fine("Marking key in Results Cache For CQ :" + 
    //    this.cqName + " key :" + key);

    if (this.cqResultKeys != null) {
      synchronized (this.cqResultKeys) { 
        this.cqResultKeys.put(key, Token.DESTROYED);
        if (!this.cqResultKeysInitialized){
          //this.logger.fine("Adding key to Destroy Cache For CQ :" + 
          //this.cqName + " key :" + key);
          if (this.destroysWhileCqResultsInProgress != null){
            this.destroysWhileCqResultsInProgress.add(key);
          }
        }
      }
    }    
  }
  
  
  /**
   * Sets the CQ Results key cache state as initialized.
   */
  public void setCqResultsCacheInitialized() {
    if (CqService.MAINTAIN_KEYS) {
      this.cqResultKeysInitialized = true;
    }
  }
   
  /**
   * Returns the size of the CQ Result key cache.
   * @return size of CQ Result key cache.
   */
  public int getCqResultKeysSize() {
    if (this.cqResultKeys == null) {
      return 0;
    }
    synchronized (this.cqResultKeys) { 
      return this.cqResultKeys.size();
    }
  }
  
  /**
   * Returns true if old value is required for query processing.
   */
  public boolean isOldValueRequiredForQueryProcessing(Object key){
    if (this.cqResultKeysInitialized && this.isPartOfCqResult(key)) {
      return false;
    }
    return true;
  }
  
  /**
   * Returns parameterized query used by the server.
   * This method replaces Region name with $1 and if type is not specified
   * in the query, looks for type from cqattributes and appends into the
   * query.
   * @return String modified query.
   * @throws CqException
   */
  private Query constructServerSideQuery() throws QueryException {
    GemFireCacheImpl cache = (GemFireCacheImpl)cqService.getCache();
    DefaultQuery locQuery = (DefaultQuery)cache.getLocalQueryService().newQuery(this.queryString);      
    CompiledSelect select = locQuery.getSimpleSelect();
    CompiledIteratorDef from = (CompiledIteratorDef)select.getIterators().get(0);
    // WARNING: ASSUMES QUERY WAS ALREADY VALIDATED FOR PROPER "FORM" ON CLIENT;
    // THIS VALIDATION WILL NEED TO BE DONE ON THE SERVER FOR NATIVE CLIENTS,
    // BUT IS NOT DONE HERE FOR JAVA CLIENTS.
    // The query was already checked on the client that the sole iterator is a
    // CompiledRegion
    this.regionName = ((CompiledRegion)from.getCollectionExpr()).getRegionPath();
    from.setCollectionExpr(new CompiledBindArgument(1));
    return locQuery;    
  }
  
  public ServerCQProxy getCQProxy() {
    return this.cqProxy;
  }
  /**
   * Initializes the connection using the BridgeWriter, BridgeClient or BridgeLoader
   * (with extablishcallback connection) from the client region.
   * Also sets the cqBaseRegion value of this CQ.
   * @throws CqException
   */
  public void initConnectionProxy() throws CqException, RegionNotFoundException {
    cqBaseRegion = (LocalRegion)cqService.getCache().getRegion(regionName);
    // Check if the region exists on the local cache.
    // In the current implementation of 5.1 the Server Connection is (ConnectionProxyImpl)
    // is obtained by the Bridge Client/writer/loader on the local region.
    if (cqBaseRegion == null){
      throw new RegionNotFoundException( LocalizedStrings.CqQueryImpl_REGION_ON_WHICH_QUERY_IS_SPECIFIED_NOT_FOUND_LOCALLY_REGIONNAME_0
          .toLocalizedString(regionName));
    }
    
    ServerRegionProxy srp = cqBaseRegion.getServerProxy();
    if (srp != null) {
      if (logger.finerEnabled()){
        logger.finer("Found server region proxy on region. RegionName :" + regionName);
      }
      this.cqProxy = new ServerCQProxy(srp);
      if(!srp.getPool().getSubscriptionEnabled()) {
         throw new CqException("The 'queueEnabled' flag on Pool installed on Region " + regionName + " is set to false.");
      }
    } else {
      throw new CqException("Unable to get the connection pool. The Region does not have a pool configured.");
    }
    
//    if (proxy == null) {
//      throw new CqException(LocalizedStrings.CqQueryImpl_UNABLE_TO_GET_THE_CONNECTIONPROXY_THE_REGION_MAY_NOT_HAVE_A_BRIDGEWRITER_OR_BRIDGECLIENT_INSTALLED_ON_IT.toLocalizedString());
//    } else if(!proxy.getEstablishCallbackConnection()){
//      throw new CqException(LocalizedStrings.CqQueryImpl_THE_ESTABLISHCALLBACKCONNECTION_ON_BRIDGEWRITER_CLIENT_INSTALLED_ON_REGION_0_IS_SET_TO_FALSE
//        .toLocalizedString(regionName));
//    }
  }
  
  /**
   * Closes the Query.
   *        On Client side, sends the cq close request to server.
   *        On Server side, takes care of repository cleanup.
   * @throws CqException
   */
  public void close() throws CqClosedException, CqException {
    close(true);
  }
  
  /**
   * Closes the Query.
   *        On Client side, sends the cq close request to server.
   *        On Server side, takes care of repository cleanup.
   * @param sendRequestToServer true to send the request to server.
   * @throws CqException
   */
  public void close(boolean sendRequestToServer) throws CqClosedException, CqException {
    if(logger.fineEnabled()) {
      logger.fine("Started closing CQ CqName : " + cqName + " SendRequestToServer : " + sendRequestToServer);
    }   
    // Synchronize with stop and execute CQ commands
    synchronized(this.cqState) {
      // Check if the cq is already closed.
      if (this.isClosed()) {
        //throw new CqClosedException("CQ is already closed, CqName : " + this.cqName);
        if (logger.fineEnabled()){
          logger.fine("CQ is already closed, CqName : " + this.cqName);
        }
        return;
      }

      int stateBeforeClosing = this.cqState.getState();
      this.cqState.setState(CqStateImpl.CLOSING);      
      boolean isClosed = false;

      // Client Close. Proxy is null in case of server.
      // Check if this has been sent to server, if so send
      // Close request to server.    
      Exception exception = null;
      if (this.cqProxy != null && sendRequestToServer) {
        try {
          if (this.proxyCache != null) {
            if (this.proxyCache.isClosed()) {
              throw new CacheClosedException("Cache is closed for this user.");
            }
            UserAttributes.userAttributes.set(this.proxyCache
                .getUserAttributes());
          }
          cqProxy.close(this);
          isClosed = true;
        } 
        catch (CancelException e) {
          throw e;
        } 
        catch(Exception ex) {
          if(shutdownInProgress()) {
            return;
          }
          exception = ex;
        } finally {
          UserAttributes.userAttributes.set(null);
        }
      }

      // Cleanup the resource used by cq.
      this.removeFromCqMap(); 

      if (cqProxy == null || !sendRequestToServer || isClosed) {       
        // Stat update.
        if (stateBeforeClosing == CqStateImpl.RUNNING) {
          cqService.stats.decCqsActive();
        } else if (stateBeforeClosing == CqStateImpl.STOPPED) {
          cqService.stats.decCqsStopped();
        }

        // Clean-up the CQ Results Cache.
        if (this.cqResultKeys != null) {
          synchronized (this.cqResultKeys){
            this.cqResultKeys.clear();
          }
        }

        // Set the state to close, and update stats
        this.cqState.setState(CqStateImpl.CLOSED);
        cqService.stats.incCqsClosed();
        cqService.stats.decCqsOnClient();
      } else {
        if(shutdownInProgress()) {
          return;
        }
        // Hasn't able to send close request to any server.
        if (exception != null) {
          throw new CqException(LocalizedStrings.CqQueryImpl_FAILED_TO_CLOSE_THE_CQ_CQNAME_0_ERROR_FROM_LAST_ENDPOINT_1
              .toLocalizedString(new Object[] {this.cqName, exception.getLocalizedMessage()}), exception.getCause());   
        } else {
          throw new CqException(LocalizedStrings.CqQueryImpl_FAILED_TO_CLOSE_THE_CQ_CQNAME_0_THE_SERVER_ENDPOINTS_ON_WHICH_THIS_CQ_WAS_REGISTERED_WERE_NOT_FOUND
              .toLocalizedString(this.cqName));   
        }
      }
    }

    // Invoke close on Listeners if any.
    if (this.cqAttributes != null) {
      CqListener[] cqListeners = this.getCqAttributes().getCqListeners();

      if (cqListeners != null) {
        if (logger.fineEnabled()){
          logger.fine("Invoking CqListeners close() api for the CQ, CqName : " + cqName + 
              " Number of CqListeners :" + cqListeners.length);
        }
        for (int lCnt=0; lCnt < cqListeners.length; lCnt++) {
          try {
            cqListeners[lCnt].close();
            // Handle client side exceptions.
          } catch (Exception ex) {
            logger.warning( 
                LocalizedStrings.CqQueryImpl_EXCEPTION_OCCOURED_IN_THE_CQLISTENER_OF_THE_CQ_CQNAME_0_ERROR_1,
                new Object[] {cqName, ex.getLocalizedMessage()});
            logger.fine(ex.getMessage(), ex);
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
            logger.warning(
                LocalizedStrings.CqQueryImpl_RUNTIMEEXCEPTION_OCCOURED_IN_THE_CQLISTENER_OF_THE_CQ_CQNAME_0_ERROR_1,
                new Object[] {cqName, t.getLocalizedMessage()});
            logger.fine(t.getMessage(), t);
          }        
        }
      }
    }
    if (logger.fineEnabled()) {
      logger.fine("Successfully closed the CQ. " + cqName);
    }
  } 
    
  /**
   * Removes the CQ from CQ repository.
   * @throws CqException
   */
  private void removeFromCqMap() throws CqException {
    try {
      cqService.removeCq(this.serverCqName);
    } catch (Exception ex){
      StringId errMsg = LocalizedStrings.CqQueryImpl_FAILED_TO_REMOVE_CONTINUOUS_QUERY_FROM_THE_REPOSITORY_CQNAME_0_ERROR_1;
      Object[] errMsgArgs = new Object[] {cqName, ex.getLocalizedMessage()};
      logger.error(errMsg, errMsgArgs);
      throw new CqException (errMsg.toLocalizedString(errMsgArgs), ex);
    }    
    if (logger.fineEnabled()){
      logger.fine("Removed CQ from the CQ repository. CQ Name:" + this.cqName);
    }
  }

  /** 
   * Returns the QueryString of this CQ.
   */
  public String getQueryString() {
    return queryString;
  }
  
  /**
   * Return the query after replacing region names with parameters
   * @return the Query for the query string
   */
  public Query getQuery(){
    return query;
  }
  
  
  /**
   * @see com.gemstone.gemfire.cache.query.CqQuery#getStatistics()
   */
  public CqStatistics getStatistics() {
    return cqStats;
  }
  
  /**
   * Get clients distributed system ID
   * @return - ClientProxyMembershipID of the client
   */
  public ClientProxyMembershipID getClientProxyId() {
    return this.clientProxyId;
  }
  
  /**
   * Get CacheClientNotifier of this CqQuery.
   * @return CacheClientNotifier 
   */
  public CacheClientNotifier getCacheClientNotifier() {
    return this.ccn;
  }
  
  public CqAttributes getCqAttributes() {
    return cqAttributes;
  }
  
  public LocalRegion getCqBaseRegion() {
    return this.cqBaseRegion;
  }
  
  /**
   * Clears the resource used by CQ.
   * @throws CqException
   */
  private void cleanup() throws CqException {
    // Repository Region.
    /*
    if (cqService.isServer()){
      // If CQ event caching is enabled, remove this CQs event cache reference.
      cqService.removeCQFromCaching(this.serverCqName);
    }
    */

    
    // CqBaseRegion 
    try {
      if (this.cqBaseRegion != null && !this.cqBaseRegion.isDestroyed()) {
        // Server specific clean up.
        if (cqService.isServer()){
          this.cqBaseRegion.getFilterProfile().closeCq(this);
          CacheClientProxy clientProxy = ccn.getClientProxy(clientProxyId);
          clientProxy.decCqCount();
          if (clientProxy.hasNoCq()) {
            cqService.stats.decClientsWithCqs();
          }
        }
      } 
    }catch (Exception ex){
      // May be cache is being shutdown
      if (logger.fineEnabled()) {
        logger.fine("Failed to remove CQ from the base region. CqName :" + cqName);
      }
    }
  
    if (!cqService.isServer()){
      this.cqService.removeFromBaseRegionToCqNameMap(this.regionName, this.serverCqName);      
    }
  }
  
  /**
   * @return Returns the Region name on which this cq is created.
   */
  public String getBaseRegionName() {
    
    return this.regionName;
  }

  /**
   * @return Returns the serverCqName.
   */
  public String getServerCqName() {
    
    return this.serverCqName;
  }

  /**
   * @param serverCqName The serverCqName to set.
   */
  public void setServerCqName(String serverCqName) {
    
    this.serverCqName = serverCqName;
  }
  
  /**
   * @return Returns the cqListeners.
   */
  public CqListener[] getCqListeners() {
    
    return cqAttributes.getCqListeners();
  }
  
  
  /**
   * Start or resume executing the query.
   */
  public void execute() throws CqClosedException, RegionNotFoundException, CqException {
    executeCqOnRedundantsAndPrimary(false);
  }

  /**
   * Start or resume executing the query.
   * Gets or updates the CQ results and returns them.
   */
  public CqResults executeWithInitialResults() 
    throws CqClosedException, RegionNotFoundException, CqException {
    
    synchronized(queuedEventsSynchObject) {
      //Prevents multiple calls to executeWithInitialResults from squishing
      //each others queuedEvents.  Forces the second call to wait
      //until first call is completed.      
      while (queuedEvents != null) {
        try {
          queuedEventsSynchObject.wait();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      //At this point we know queuedEvents is null and no one is adding to queuedEvents yet.
      this.queuedEvents = new ConcurrentLinkedQueue();
    }
    
    if (CqQueryImpl.testHook != null) {
      testHook.pauseUntilReady();
    }
    // Send CQ request to servers.
    //If an exception is thrown, we need to clean up the queuedEvents 
    //or else client will hang on next executeWithInitialResults
    CqResults initialResults;
    try {
      initialResults = (CqResults)executeCqOnRedundantsAndPrimary(true);
    }
    catch (CqClosedException e) { 
      queuedEvents = null;
      throw e;
    }
    catch (RegionNotFoundException e) {
      queuedEvents = null;
      throw e;
    }
    catch (CqException e) {
      queuedEvents = null;
      throw e;
    }
    catch (RuntimeException e) {
      queuedEvents = null;
      throw e;
    }
    
    //lock was released earlier so that events that are received while executing 
    //initial results can be added to the queue.
    synchronized(queuedEventsSynchObject) {
      // Check if any CQ Events are received during query execution.
      // Invoke the CQ Listeners with the received CQ Events.
      try {
        if (!this.queuedEvents.isEmpty()) {
          try {
            Runnable r = new Runnable(){
              public void run(){ 
                Object[] eventArray = null;
                if (CqQueryImpl.testHook != null) {
                  testHook.setEventCount(queuedEvents.size());
                }
                // Synchronization for the executer thread.
                synchronized (queuedEventsSynchObject){
                  try {
                    eventArray = queuedEvents.toArray();
                  
                     //Process through the events
                    for (Object cqEvent : eventArray) { 
                      cqService.invokeListeners(cqName, CqQueryImpl.this, 
                          (CqEventImpl)cqEvent);
                      stats.decQueuedCqListenerEvents();
                    } 
                  }
                  finally {
                    //Make sure that we notify waiting threads or else possible dead lock
                    queuedEvents.clear();
                    queuedEvents = null;
                    queuedEventsSynchObject.notify();
                  }
                }
              }
            };
            final LogWriterImpl.LoggingThreadGroup group =
              LogWriterImpl.createThreadGroup("CQEventHandler", logger);
            Thread thread = new Thread(group, r, "CQEventHandler For " + cqName);
            thread.setDaemon(true);
            thread.start();    
          } catch (Exception ex) {
            if (logger.fineEnabled()){
              logger.fine("Exception while invoking the CQ Listener with queued events.", ex);
            }
          }
        } else {
          queuedEvents = null;
        }
      }
      finally {
        queuedEventsSynchObject.notify();
      }
      return initialResults;
    }
  }
  
  /**
   * This executes the CQ first on the redundant server and then on the primary server.
   * This is required to keep the redundancy behavior in accordance with the HAQueue
   * expectation (wherein the events are delivered only from the primary).
   * @param executeWithInitialResults boolean
   * @return Object SelectResults in case of executeWithInitialResults
   */
  public Object executeCqOnRedundantsAndPrimary(boolean executeWithInitialResults) 
  throws CqClosedException, RegionNotFoundException, CqException {

    Object initialResults = null;
    
    synchronized (this.cqState) {
      if (this.isClosed()) {
        throw new CqClosedException(LocalizedStrings.CqQueryImpl_CQ_IS_CLOSED_CQNAME_0
            .toLocalizedString(this.cqName));
      }
      if (this.isRunning()) {
        throw new IllegalStateException(LocalizedStrings.CqQueryImpl_CQ_IS_IN_RUNNING_STATE_CQNAME_0
            .toLocalizedString(this.cqName));
      }

      if (logger.fineEnabled()) {
        logger.fine("Performing Execute" + ((executeWithInitialResults)?"WithInitialResult":"") + 
            " request for CQ. CqName :" + this.cqName);
      }
      this.cqBaseRegion = (LocalRegion)cqService.getCache().getRegion(this.regionName); 

      // If not server send the request to server.
      if (!cqService.isServer()) {
        // Send execute request to the server.

        // If CqService is initialized using the pool, the connection proxy is set using the
        // pool that initializes the CQ. Else its set using the Region proxy. 
        if (this.cqProxy == null){
          initConnectionProxy();
        }

        boolean success = false;
        try {
          if (this.proxyCache != null) {
            if (this.proxyCache.isClosed()) {
              throw new CacheClosedException("Cache is closed for this user.");
            }
            UserAttributes.userAttributes.set(this.proxyCache
                .getUserAttributes());
          }
          if(executeWithInitialResults) {
            initialResults = cqProxy.createWithIR(this);
            if (initialResults == null) {
              String errMsg = "Failed to execute the CQ.  CqName: " + 
              this.cqName + ", Query String is: " + this.queryString;
              throw new CqException(errMsg);                    
            }
          } else {
            cqProxy.create(this);
          }
          success = true;
        } catch (Exception ex) {
          // Check for system shutdown.
          if (this.shutdownInProgress()){
            throw new CqException("System shutdown in progress.");  
          }
          if (ex.getCause() instanceof GemFireSecurityException) {
            if (securityLogger.warningEnabled()) {
              securityLogger.warning(LocalizedStrings.CqQueryImpl_EXCEPTION_WHILE_EXECUTING_CQ_EXCEPTION_0, ex, null);              
            }
            throw new CqException(
              LocalizedStrings.CqQueryImpl_GOT_SECURITY_EXCEPTION_WHILE_EXECUTING_CQ_ON_SERVER.toLocalizedString(), ex.getCause());  
          } else if(ex instanceof CqException) {
            throw (CqException)ex;
          } else {
            String errMsg = 
              LocalizedStrings.CqQueryImpl_FAILED_TO_EXECUTE_THE_CQ_CQNAME_0_QUERY_STRING_IS_1_ERROR_FROM_LAST_SERVER_2
              .toLocalizedString(new Object[] {this.cqName, this.queryString, ex.getLocalizedMessage()});
            if (logger.fineEnabled()){
              logger.fine(errMsg, ex);
            }
            throw new CqException(errMsg, ex);
          }
        } finally {
          if(!success && !this.shutdownInProgress()) {
            try {
              cqProxy.close(this);
            } catch(Exception e) {
              logger.fine("Exception cleaning up failed cq", e);
              UserAttributes.userAttributes.set(null);
            }
          }
          UserAttributes.userAttributes.set(null);
        }
      } 
      this.cqState.setState(CqStateImpl.RUNNING);
    }
    //If client side, alert listeners that a cqs have been connected
    if (!cqService.isServer()) {
      connected = true;
      CqListener[] cqListeners = getCqAttributes().getCqListeners();
      for (int lCnt=0; lCnt < cqListeners.length; lCnt++) {
        if (cqListeners[lCnt] != null) {
          if (cqListeners[lCnt] instanceof CqStatusListener) {
            CqStatusListener listener = (CqStatusListener) cqListeners[lCnt];
            listener.onCqConnected(); 
          }
        }
      }
    }
    // Update CQ-base region for book keeping.
    this.cqService.stats.incCqsActive();
    this.cqService.stats.decCqsStopped();
    return initialResults;
  }

  /**
   * Check to see if shutdown in progress.
   * @return true if shutdown in progress else false.
   */
  private boolean shutdownInProgress() {    
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache == null || cache.isClosed()) {
      return true; //bail, things are shutting down
    }
    
    
    String reason = cqProxy.getPool().getCancelCriterion().cancelInProgress();
    if (reason != null) {
      return true;
    }  
    return false;
    
  }
  
  /**
   * Stop or pause executing the query.
   */
  public void stop()throws CqClosedException, CqException {
    boolean isStopped = false;
    synchronized (this.cqState) {
      if (this.isClosed()) {
        throw new CqClosedException(LocalizedStrings.CqQueryImpl_CQ_IS_CLOSED_CQNAME_0
            .toLocalizedString(this.cqName));
      }
      
      if (!(this.isRunning())) {
        throw new IllegalStateException(LocalizedStrings.CqQueryImpl_CQ_IS_NOT_IN_RUNNING_STATE_STOP_CQ_DOES_NOT_APPLY_CQNAME_0
            .toLocalizedString(this.cqName));
      }
      
      Exception exception = null;
      try {
        if (this.proxyCache != null) {
          if (this.proxyCache.isClosed()) {
            throw new CacheClosedException("Cache is closed for this user.");
          }
          UserAttributes.userAttributes.set(this.proxyCache
              .getUserAttributes());
        }
        if(cqProxy!=null) {
          cqProxy.stop(this);
          isStopped = true;
        }
      } catch(Exception e ) {
        exception = e;
      } finally {
        UserAttributes.userAttributes.set(null);
      }
       if (cqProxy == null || isStopped) {
         // Change state and stats on the client side
         this.cqState.setState(CqStateImpl.STOPPED);
         this.cqService.stats.incCqsStopped();
         this.cqService.stats.decCqsActive();
         logger.fine("Successfully stopped the CQ. " + cqName);
       } else {
         // Hasn't able to send stop request to any server.
         if (exception != null) {
           throw new CqException(LocalizedStrings.CqQueryImpl_FAILED_TO_STOP_THE_CQ_CQNAME_0_ERROR_FROM_LAST_SERVER_1
              .toLocalizedString(new Object[] {this.cqName, exception.getLocalizedMessage()}),
              exception.getCause());
         } else {
           throw new CqException(LocalizedStrings.CqQueryImpl_FAILED_TO_STOP_THE_CQ_CQNAME_0_THE_SERVER_ENDPOINTS_ON_WHICH_THIS_CQ_WAS_REGISTERED_WERE_NOT_FOUND
              .toLocalizedString(this.cqName));
         }
       }
    }
  }
  
  /**
   * Return the state of this query.
   * Should not modify this state without first locking it.
   * @return STOPPED RUNNING or CLOSED
   */
  public CqState getState() {
    return this.cqState;
  }

 /**
  * Sets the state of the cq.
  * Server side method. Called during cq registration time.
  */
  public void setCqState(int state) {
    if (this.isClosed()) {
      throw new CqClosedException(LocalizedStrings.CqQueryImpl_CQ_IS_CLOSED_CQNAME_0
          .toLocalizedString(this.cqName));
    }

    synchronized (cqState) {
      if (state == CqStateImpl.RUNNING){
        if (this.isRunning()) {
          //throw new IllegalStateException(LocalizedStrings.CqQueryImpl_CQ_IS_NOT_IN_RUNNING_STATE_STOP_CQ_DOES_NOT_APPLY_CQNAME_0
          //  .toLocalizedString(this.cqName));
        }
        this.cqState.setState(CqStateImpl.RUNNING);
        this.cqService.stats.incCqsActive();
        this.cqService.stats.decCqsStopped();
      } else if(state == CqStateImpl.STOPPED) {
        this.cqState.setState(CqStateImpl.STOPPED);
        this.cqService.stats.incCqsStopped();
        this.cqService.stats.decCqsActive();
      } else if(state == CqStateImpl.CLOSING) {
        this.cqState.setState(state);
      }
    }
  }

  public CqAttributesMutator getCqAttributesMutator() {
    return (CqAttributesMutator)this.cqAttributes;
  }
  /**
   * @return Returns the cqOperation.
   */
  public EnumListenerEvent getCqOperation() {
    return this.cqOperation;
  }
  
  /**
   * @param cqOperation The cqOperation to set.
   */
  public void setCqOperation(EnumListenerEvent cqOperation) {
    this.cqOperation = cqOperation;
  }
  
  /**
   * Update CQ stats
   * @param cqEvent object
   */
  public void updateStats(CqEvent cqEvent) {
    this.stats.updateStats(cqEvent);  // Stats for VSD
  }
  
  /**
   * Return true if the CQ is in running state
   * @return true if running, false otherwise
   */
  public boolean isRunning() {
    return this.cqState.isRunning();
  }
  
  /**
   * Return true if the CQ is in Sstopped state
   * @return true if stopped, false otherwise
   */ 
  public boolean isStopped() {
    return this.cqState.isStopped();
  }
  
  /**
   * Return true if the CQ is closed
   * @return true if closed, false otherwise
   */
  public boolean isClosed() {
    return this.cqState.isClosed();
  }

  /**
   * Return true if the CQ is in closing state.
   * @return true if close in progress, false otherwise
   */
  public boolean isClosing() {
    return this.cqState.isClosing();
  }
  
  /**
   * Return true if the CQ is durable
   * @return true if durable, false otherwise
   */
  public boolean isDurable() {
    return this.isDurable;
  }

  /**
   * Returns a reference to VSD stats of the CQ
   * @return VSD stats of the CQ
   */
  public CqQueryVsdStats getVsdStats() {
    return stats;
  }

  public ExecutionContext getQueryExecutionContext() {
    return queryExecutionContext;
  }

  public void setQueryExecutionContext(ExecutionContext queryExecutionContext) {
    this.queryExecutionContext = queryExecutionContext;
  }

  public ConcurrentLinkedQueue getQueuedEvents() {
    return this.queuedEvents;
  }
  
  /* DataSerializableFixedID methods ---------------------------------------- */

  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    //this.cqName = DataSerializer.readString(in);
    synchronized(cqState) {
      this.cqState.setState(DataSerializer.readInteger(in));
    }
    this.isDurable = DataSerializer.readBoolean(in);
    this.queryString = DataSerializer.readString(in);
    this.filterID = in.readLong();
  }

  /*
  public int getDSFID() {
    return CQ_QUERY;
  }
  */
  
  public void toData(DataOutput out) throws IOException {
    //DataSerializer.writeString(this.cqName, out);
    DataSerializer.writeInteger(this.cqState.getState(), out);
    DataSerializer.writeBoolean(this.isDurable, out);
    DataSerializer.writeString(this.queryString, out);
    out.writeLong(this.filterID);
  }

  void setProxyCache(ProxyCache proxyCache){
    this.proxyCache = proxyCache;
  }
  
  boolean isConnected() {
    return connected;
  }
  
  void setConnected(boolean connected) {
    this.connected = connected;
  }
    
  /** Test Hook */
  public interface TestHook {
    public void pauseUntilReady();
    public void ready();
    public int numQueuedEvents();
    public void setEventCount(int count);
  }
}
