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
package com.pivotal.gemfirexd.internal.engine.management.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;

import javax.management.ObjectName;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.management.ManagementException;
import com.gemstone.gemfire.management.internal.BaseManagementService;
import com.gemstone.gemfire.management.internal.FederationComponent;
import com.gemstone.gemfire.management.internal.LocalManager;
import com.gemstone.gemfire.management.internal.MBeanJMXAdapter;
import com.gemstone.gemfire.management.internal.SystemManagementService;
import com.gemstone.gemfire.management.internal.SystemManagementService.GemFireXDMBean;
import com.gemstone.gemfire.management.internal.beans.MemberMBeanBridge;
import com.gemstone.gemfire.management.internal.beans.RegionMBeanBridge;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.GfxdMemberMXBean;
import com.pivotal.gemfirexd.internal.engine.management.GfxdResourceEvent;
import com.pivotal.gemfirexd.internal.engine.management.StatementMXBean;
import com.pivotal.gemfirexd.internal.engine.management.TableMXBean;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ReferencedKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;

/**
 *
 * @author Abhishek Chaudhari, Ajay Pande
 * @since gfxd 1.0
 */
public class InternalManagementService {
  private static final Set<String> DEFAULT_SERVERGROUP_SET = Collections.singleton(ManagementUtils.DEFAULT_SERVER_GROUP);

  private static final Object INSTANCE_LOCK = InternalManagementService.class;
  // we use a conncurrent hashmap only for read safety in gets and iterators
  // without requiring locks
  private static final Map<GemFireStore, InternalManagementService> INSTANCES = new ConcurrentHashMap<GemFireStore, InternalManagementService>(4, 0.7f, 1);

  private SystemManagementService gfManagementService;
  private ConnectionWrapperHolder connectionWrapperHolder;
  private MBeanUpdateScheduler    updateScheduler;
  private LogWriter               logger;
  private MBeanDataUpdater<GfxdMemberMBeanBridge> memberMBeanDataUpdater;
  private MBeanDataUpdater<TableMBeanBridge>      tableMBeanDataUpdater;

  private Map<ObjectName, Cleanable> cleanables = new HashMap<ObjectName, Cleanable>();

  private boolean isStopped;

  private InternalManagementService(GemFireStore store) {
    GemFireCacheImpl cache = store.getGemFireCache();
    this.gfManagementService     = (SystemManagementService) BaseManagementService.getManagementService(cache);
    this.logger                  = cache.getLogger();
    this.connectionWrapperHolder = new ConnectionWrapperHolder();
    this.updateScheduler         = new MBeanUpdateScheduler();
    this.memberMBeanDataUpdater  = new MemberMBeanDataUpdater();
    this.tableMBeanDataUpdater   = new TableMBeanDataUpdater();

    this.updateScheduler.scheduleTaskWithFixedDelay(this.memberMBeanDataUpdater, MemberMBeanDataUpdater.class.getSimpleName(), 500);
    this.updateScheduler.scheduleTaskWithFixedDelay(this.tableMBeanDataUpdater, TableMBeanDataUpdater.class.getSimpleName(), 500);

    this.isStopped = false;
  }

  /**
   * Returns a newly created or the existing instance of the management service
   * for a cache.
   *
   * @param store
   *          GemFireStore for which to get the management service.
   */
  public static InternalManagementService getInstance(GemFireStore store) {
    InternalManagementService foundInstance = null;
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache != null && cache.isClosed() == false
        && cache.isCacheAtShutdownAll() == false) {
      if (store != null) { // store should be not-null
        foundInstance = INSTANCES.get(store);
        if (foundInstance == null) { // first level check
          synchronized (INSTANCE_LOCK) {
            foundInstance = INSTANCES.get(store);
            if (foundInstance == null) { // second level check
              foundInstance = new InternalManagementService(store);
              INSTANCES.clear(); // clear old instances if existed.
              INSTANCES.put(store, foundInstance);
            }
          }
        }
      }
    }
    return foundInstance;
  }

  public static InternalManagementService getAnyInstance() {
    for (InternalManagementService inst : INSTANCES.values()) {
      return inst;
    }
    return null;
  }

  /** for tests only */
  public GfxdConnectionWrapper getConnectionWrapperForTEST() {
    final ConnectionWrapperHolder holder = this.connectionWrapperHolder;
    return holder != null ? holder.wrapper : null;
  }

  // /**
  // * @return the gfManagementService
  // */
  // public SystemManagementService getGfManagementService() {
  // return this.gfManagementService;
  // }

//  public MBeanUpdateScheduler getUpdateScheduler() {
//    return this.updateScheduler;
//  }

  public boolean isStopped() {
    return isStopped;
  }

  public void stopService() {
//    stopManager(); // should we stop GemFire Manager/Management Service too??
    cleanUp();
    synchronized (INSTANCE_LOCK) {
      INSTANCES.clear(); // clear old instances if existed.
    }
    isStopped = true;
  }

  void cleanUp() {
//    logger.info("ABHISHEK: InternalManagementService.cleanUp() : "+connectionWrapperHolder.hasConnection());

    this.updateScheduler.stopAllTasks();
    this.updateScheduler = null;

    if (this.connectionWrapperHolder.hasConnection()) {
      this.connectionWrapperHolder.cleanUp();
    }
    this.connectionWrapperHolder = null;

    this.gfManagementService = null;
    this.logger              = null;
  }

  //**************** Event handler methods start *************
  public <T> void handleEvent(int eventId, T eventData) {
    try {
      switch (eventId) {
      case GfxdResourceEvent.FABRIC_DB__BOOT:
        handleFabricDBBoot((GemFireStore) eventData);
//        logInfo("ABHISHEK: FABRIC_DB__BOOT");
        break;
      case GfxdResourceEvent.FABRIC_DB__STOP:
        handleFabricDBStop((GemFireStore) eventData);
//        logInfo("ABHISHEK: FABRIC_DB__STOP");
        // close for gfManagementService will be handled by Cache.close()
        break;
      case GfxdResourceEvent.TABLE__CREATE:
        handleTableCreate((Object[]) eventData);
//        logInfo("ABHISHEK: TABLE__CREATE");
        //logger.warning("InternalManagementService.handleEvent() 8");
        //logInfo("InternalManagementService.handleEvent() 8");
        break;      
      case GfxdResourceEvent.TABLE__ALTER:     
        handleTableAlter((Object[]) eventData);
        break;     
      case GfxdResourceEvent.TABLE__DROP:
        handleTableDrop((String) eventData);
//        logInfo("ABHISHEK: TABLE__DROP");
        break;
      case GfxdResourceEvent.STATEMENT__CREATE:
        handleStatementCreate((GenericStatement) eventData);
//        logInfo("ABHISHEK: STATEMENT__CREATE");
        break;
      case GfxdResourceEvent.STATEMENT__INVALIDATE:
        handleStatementInvalidate((GenericStatement) eventData);
//        logInfo("ABHISHEK: STATEMENT__CANCEL");
        break;
      case GfxdResourceEvent.EMBEDCONNECTION__INIT:
        handleEmbedConnectionInit((EmbedConnection) eventData, true);
//        logInfo("ABHISHEK: EMBEDCONNECTION__INIT");
        break;
      default:
        break;
      }
    } catch (Throwable t) {
      logWarning("Error occurred while handling management event for " + eventData, t);
    }
  }

  private void logWarning(String message, Throwable t) {
    if (logger != null) {
      logger.warning(message, t);
    } else {
      SanityManager.DEBUG_PRINT(Level.WARNING.toString().toLowerCase(), message, t);
    }
  }

  private void logInfo(String message) {
    logInfo(message, null);
  }

  private void logInfo(String message, Throwable t) {
    if (logger != null && logger.infoEnabled()) {
      logger.info(message, t);
    } else {
      SanityManager.DEBUG_PRINT(Level.INFO.toString().toLowerCase(), message, t);
    }
  }

  private void logFine(String message, Throwable t) {
    if (logger != null && logger.fineEnabled()) {
      logger.fine(message, t);
    } else {
      SanityManager.DEBUG_PRINT(Level.FINE.toString().toLowerCase(), message, t);
    }
  }

  private void handleFabricDBBoot(GemFireStore store) {
    Set<String> serverGroups                   = getMyServerGroups();
    GemFireCacheImpl cache                     = store.getGemFireCache();
    InternalDistributedMember thisMember       = cache.getDistributedSystem().getDistributedMember();
    GfxdMemberMBeanBridge gfxdMemberBeanBridge = new GfxdMemberMBeanBridge(thisMember, serverGroups, this.connectionWrapperHolder);
    MemberMBeanBridge memberMBeanBridge        = new MemberMBeanBridge(cache, this.gfManagementService);
    String memberNameOrId                      = MBeanJMXAdapter.getMemberNameOrId(thisMember);

    // register aggregator for GFXD Aggregate Mbeans
    // DO NOT change the location for calling registerGFXDMbeanAggregator since
    // it affects cluster formation
    GfxdMBeanAggregator gfxdMBeanAggregator = registerGFXDMbeanAggregator(thisMember);

    for (String serverGroup : serverGroups) {
      // NOTE: We need to create a separate MBean Object for different
      // ObjectNames. We can use the same Bridge objects but the MBean instance
      // has to be different. It's not recommended to use the same MBean with
      // different ObjectNames, see this:
      // https://weblogs.java.net/blog/emcmanus/archive/2005/07/can_you_registe.html
      // And our MBeans being MBeanRegistration instances,
      // DefaultMBeanServerInterceptor.registerDynamicMBean() calls preRegister
      // to check for already registered MBean by MBean & not by ObjectName.
      GfxdMemberMBean gfxdMemberBean  = new GfxdMemberMBean(gfxdMemberBeanBridge, memberMBeanBridge);
      ObjectName      memberMBeanName = ManagementUtils.getMemberMBeanName(memberNameOrId, serverGroup);

      // Type casting to MemberMXBean to expose only those methods described in
      // the interface;
      // unregister if already registered due to previous unclean close
      if (this.gfManagementService.isRegistered(memberMBeanName)) {      
        unregisterMBean(memberMBeanName);
      }
      ObjectName registeredMBeanName = this.registerMBean((GfxdMemberMXBean) gfxdMemberBean, memberMBeanName);     
      this.gfManagementService.federate(registeredMBeanName, GfxdMemberMXBean.class, true /*notif emitter*/);
    }
    this.memberMBeanDataUpdater.addUpdatable(memberNameOrId, gfxdMemberBeanBridge); // start updates after creation
    
    //Handle aggregation of GFXDMBeans after manager start
    if(isManager()){
      this.handleGFXDMbeansAggregation(gfxdMBeanAggregator);
    }
  }

  // register new proxy listener for GFXD Mbeans
  public GfxdMBeanAggregator registerGFXDMbeanAggregator(InternalDistributedMember thisMember) {
    // TODO Ajay do this only on Manager?
    // add GFXDMbbeanAggregator to service
    GfxdDistributedSystemBridge gfxdDsBridge        = new GfxdDistributedSystemBridge(this.gfManagementService, this.connectionWrapperHolder, thisMember);
    GfxdMBeanAggregator         gfxdMbeanAggregator = new GfxdMBeanAggregator(gfxdDsBridge);
    this.gfManagementService.addProxyListener(gfxdMbeanAggregator);
    return gfxdMbeanAggregator;

  }

  private void handleFabricDBStop(GemFireStore store) {
    logInfo("Stopping GemFireXD Management/Monitoring ... ");
    Set<String> serverGroups             = getMyServerGroups();
    GemFireCacheImpl cache               = store.getGemFireCache();
    InternalDistributedMember thisMember = cache.getDistributedSystem().getDistributedMember();
    String memberNameOrId                = MBeanJMXAdapter.getMemberNameOrId(thisMember);

    this.memberMBeanDataUpdater.removeUpdatable(memberNameOrId); // first stop updates
    for (String serverGroup : serverGroups) {
      ObjectName memberMBeanName = ManagementUtils.getMemberMBeanName(memberNameOrId, serverGroup);

      this.unregisterMBean(memberMBeanName);
    }

    Set<ObjectName> unregisteredMBeanByPattern = this.unregisterMBeanByPattern(ManagementUtils.getGfxdMBeanPattern());

    logInfo("Unregistered GemFireXD MBeans: " + unregisteredMBeanByPattern);

//    this.logger.info("ABHISHEK this.mbeanDataUpdater: " + this.mbeanDataUpdater);
    stopService();
  }

  public static Set<String> getMyServerGroups() {
    Set<String> serverGroups = ServerGroupUtils.getMyGroupsSet();
    serverGroups.add(ManagementUtils.DEFAULT_SERVER_GROUP);
    return serverGroups;
  }

  @SuppressWarnings("unchecked")
  private void handleTableCreate(Object[] data) {
    GemFireContainer container = (GemFireContainer) data[0];    
    Set<String> serverGroupsToUse = Collections.EMPTY_SET;
    LocalRegion region = container.getRegion();

    // Do not display tables without region names or created in internal schema.
    if (region == null || hasInternalTableSchema(container)) {
      return;
    }
    RegionMBeanBridge<?, ?>   regionMBeanBridge = RegionMBeanBridge.getInstance(region);
    TableMBeanBridge          tableMBeanBridge  = new TableMBeanBridge(container, this.connectionWrapperHolder, 
        this.getTableDefinition(container.getSchemaName(), container.getTableName()));  
    GemFireCacheImpl          cache             = Misc.getGemFireCache();
    InternalDistributedMember thisMember        = cache.getDistributedSystem().getDistributedMember();
    String                    memberNameOrId    = MBeanJMXAdapter.getMemberNameOrId(thisMember);

    // determine group(s) to create TableMBean
    SortedSet<String> tableServerGroups = ServerGroupUtils.getServerGroupsFromContainer(container);
    //logger.warning("InternalManagementService.handleTableCreate() 3 tableServerGroups :: "+tableServerGroups);
    if (tableServerGroups != null && !tableServerGroups.isEmpty()) {
      serverGroupsToUse = new TreeSet<String>(tableServerGroups); // 'new' not needed as ServerGroupUtils.getServerGroupsFromContainer returns new set
      Set<String> memberGroupsSet = getMyServerGroups();
      serverGroupsToUse.retainAll(memberGroupsSet);
    }
    if (serverGroupsToUse.isEmpty()) {
      serverGroupsToUse = DEFAULT_SERVERGROUP_SET;
    }

    //logger.warning("InternalManagementService.handleTableCreate() 4 serverGroupsToUse :: "+serverGroupsToUse);
    for (String serverGroup : serverGroupsToUse) {
      TableMBean tableMBean = new TableMBean(tableMBeanBridge, regionMBeanBridge);
      // Subset of Server Groups for Region & for member needs to be used in ObjectNames
      //logger.warning("InternalManagementService.handleTableCreate() 5 before registering tableMBean :: "+tableMBean);
      // unregister if already registered due to previous unclean close
      ObjectName tableMBeanName = ManagementUtils.getTableMBeanName(serverGroup,
          memberNameOrId, container.getQualifiedTableName());
      if (this.gfManagementService.isRegistered(tableMBeanName)) {
        unregisterMBean(tableMBeanName);
      }
      ObjectName registeredMBeanName = this.registerMBean(tableMBean, tableMBeanName);
      //logger.warning("InternalManagementService.handleTableCreate() 6 before federating tableMBean :: "+tableMBean);
      this.gfManagementService.federate(registeredMBeanName, TableMXBean.class, false /*notif emitter*/);
      //logger.warning("InternalManagementService.handleTableCreate() 7 after federating tableMBean :: "+tableMBean);
    }
    this.tableMBeanDataUpdater.addUpdatable(tableMBeanBridge.getFullName(), tableMBeanBridge); // start updates after creation
  }  

  private boolean hasInternalTableSchema(GemFireContainer container) {
    for (String schema : CallbackFactoryProvider.getStoreCallbacks().getInternalTableSchemas()) {
      if (schema.equalsIgnoreCase(container.getSchemaName())) {
        return true;
      }
    }
    return false;
  }

  List<String> getTableDefinition (String parentSchema, String tableName){
    List<String> columnList = new ArrayList<String>(); 

    final EmbedConnection conn = this.connectionWrapperHolder.getConnection();
    if (conn == null) {
      return columnList;
    }
    try {
      LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
      final TransactionController tc = lcc.getTransactionExecute();
      SchemaDescriptor sd = lcc.getDataDictionary().getSchemaDescriptor(parentSchema, tc, true);
      TableDescriptor td = lcc.getDataDictionary().getTableDescriptor(tableName, sd, tc);
      
      List<String> primaryKeyList = new ArrayList<String>();
      ReferencedKeyConstraintDescriptor rkcd = td.getPrimaryKey();
      
      if(rkcd != null && rkcd.getColumnDescriptors().size() > 0){
        ColumnDescriptorList ls = rkcd.getColumnDescriptors();
        Iterator it = ls.iterator();
        while(it.hasNext()){
          ColumnDescriptor cd = (ColumnDescriptor)it.next();
          primaryKeyList.add(cd.getColumnName());
        }
      }
 
      ColumnDescriptorList allColumns = td.getColumnDescriptorList();

      if (allColumns.size() > 0) {
        Iterator it = allColumns.iterator();
        while (it.hasNext()) {
          ColumnDescriptor cd = (ColumnDescriptor) it.next();
          String colName = cd.getColumnName();
          String type = cd.columnType.getTypeName();
          int size = cd.columnType.getMaximumWidth();
          boolean isNullable = cd.isNullable;

          columnList.add("ColumnName=" + colName + ";  " + "Type=" + type + ";  " + "ColumnSize=" + size + ";  "
              + "IsNullable=" + (isNullable ? "YES":"NO") + ";  " + "ColumnDefintion=" + null + "; "
              + (primaryKeyList.contains(colName) == true ? "IsPrimaryKey=YES" : ""));
        }
      }
      
      Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);      
    } catch (Exception e) {
      this.logger.warning(" Error while fetching table definition for table "
          + parentSchema + "." + tableName + " Cause: " + e.getMessage()
          + " Full Exception : " + e, e);
    }    
    return columnList;
    
  }
  
  @SuppressWarnings("unchecked")     
  private void handleTableAlter(Object[] data) {    
    GemFireContainer container = (GemFireContainer) data[0];    
    String tableName = container.getQualifiedTableName();
    Set<String> serverGroupsToUse = Collections.EMPTY_SET;

    GemFireCacheImpl cache = Misc.getGemFireCache();
    InternalDistributedMember thisMember = cache.getDistributedSystem()
        .getDistributedMember();    
    String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);
    SortedSet<String> tableServerGroups = ServerGroupUtils
    .getServerGroupsFromContainer(container);   

    if (tableServerGroups != null && !tableServerGroups.isEmpty()) {
      serverGroupsToUse = new TreeSet<String>(tableServerGroups);
      Set<String> memberGroupsSet = getMyServerGroups();
      serverGroupsToUse.retainAll(memberGroupsSet);
    }

    if (serverGroupsToUse.isEmpty()) {
      serverGroupsToUse = DEFAULT_SERVERGROUP_SET;
    }

    try {
      for (String serverGroup : serverGroupsToUse) {
        ObjectName tableMBeanName = ManagementUtils.getTableMBeanName(
            serverGroup, memberNameOrId, tableName);
        TableMXBean mbean = (TableMXBean)InternalManagementService.getAnyInstance()
            .getMBeanInstance(tableMBeanName, TableMXBean.class);
        if (mbean != null) {
          TableMBean tableMbean = (TableMBean)mbean;
          tableMbean.setDefinition(getTableDefinition(container.getSchemaName(),
              container.getTableName()));
        }
      }
    } catch (Exception ex) {
      logger.warning("handleTableAlter exception == " + ex.getMessage() +
          " Full Exception = " + ex);
    }
  }

  private void handleTableDrop(String fullTableName) {
    GemFireCacheImpl cache = Misc.getGemFireCache();
    InternalDistributedMember thisMember = cache.getDistributedSystem().getDistributedMember();
    String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);

    this.tableMBeanDataUpdater.removeUpdatable(fullTableName); // first stop updates

    Set<ObjectName> unregisteredMBeanByPattern = this.unregisterMBeanByPattern(ManagementUtils.getTableMBeanGroupPattern(memberNameOrId, fullTableName));

    logFine("Unregistered following MBeans for \""+fullTableName+"\": " + unregisteredMBeanByPattern, null);
  }

  private void handleEmbedConnectionInit(final EmbedConnection connection, boolean checkStore) {
    if (connection == null) return;
    final InternalDistributedSystem system = Misc.getDistributedSystem();
    GemFireStore store = GemFireStore.getBootingInstance();
    if (checkStore && (store == null || !store.initialDDLReplayDone())) {
      // need to create connection wrapper which will need another embedded connection
      // that can fail since this is boot process itself, so register it in another thread
      ExecutorService executor = system.isLoner()
          // for loner, the DistributionManager pools are all synchronous in same thread
          ? ForkJoinPool.commonPool()
          : system.getDistributionManager().getWaitingThreadPool();
      executor.execute(() -> {
        long current = System.currentTimeMillis();
        long end = current + system.getConfig().getAckWaitThreshold() * 1000L;
        while (GemFireStore.getBootingInstance() == null && end > current) {
          try {
            Thread.sleep(100L);
            current = System.currentTimeMillis();
            Misc.checkIfCacheClosing(null);
          } catch (InterruptedException ie) {
            Misc.checkIfCacheClosing(ie);
          }
        }
        long timeout = Math.max(end - current, 5000L);
        GemFireXDUtils.waitForNodeInitialization(timeout, true, false);
        // try to proceed in any case even if node has not initialized yet
        handleEmbedConnectionInit(connection, false);
      });
      return;
    }
    final LanguageConnectionContext lcc = connection.getLanguageConnectionContext();
    if (lcc == null) return;
    GfxdConnectionHolder holder = GfxdConnectionHolder.getHolder();
    GfxdConnectionWrapper connectionWrapper = null;
    try {
      Properties props = new Properties();      
      props.setProperty(Attribute.QUERY_HDFS, Boolean.toString(lcc.getQueryHDFS()));
      props.setProperty(Attribute.ROUTE_QUERY, Boolean.toString(lcc.isQueryRoutingFlagTrue()));
      connectionWrapper = holder.createWrapper(connection.getSchema(), GemFireXDUtils.newUUID(), false, props);
    } catch (SQLException e) {
      logInfo("Error creating EmbedConnection for Management. Reason: " + e.getMessage());
      logFine(e.getMessage(), e);
    }
    // cleanup any old connection
    if (this.connectionWrapperHolder.hasConnection()) {
      this.connectionWrapperHolder.cleanUp();
    }
    this.connectionWrapperHolder.setConnectionWrapper(connectionWrapper);
  }

  private void handleStatementCreate(GenericStatement genericStatement) {
    GemFireCacheImpl          cache          = Misc.getGemFireCache();
    InternalDistributedMember thisMember     = cache.getDistributedSystem().getDistributedMember();
    String                    memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);

    PreparedStatement preparedStatement = genericStatement.getPreparedStatement();
    String statsId = preparedStatement.getStatementStats().getStatsId();
    ObjectName statementMBeanName = ManagementUtils.getStatementMBeanName(memberNameOrId, statsId);

    Object statementMBeanObject = this.gfManagementService.getJMXAdapter().getMBeanObject(statementMBeanName);
    if (statementMBeanObject != null) {
      // Related CachedStatement was invalidated earlier but MBean was yet to be
      // unregistered, just change the internal state.
      StatementMBean statementMBean = (StatementMBean) statementMBeanObject;
      statementMBean.updateBridge(genericStatement);
    } else {
      // This is a new statement
      StatementMBeanBridge statementMBeanBridge = new StatementMBeanBridge(genericStatement);
      StatementMBean       statementMbean       = new StatementMBean(statementMBeanBridge);

      ObjectName changedMBeanName = this.registerMBean(statementMbean, statementMBeanName);
      this.gfManagementService.federate(changedMBeanName, StatementMXBean.class, false);
    }
  }

  /*
   * *** Info from Soubhik on concurrent invalidate & reinstate:
   *
   * GFXD caches GenericPreparedStatement (GPS) instances against
   * GenericStatement(GS). GS is prepared for users query statement. GPS is
   * internal representation which contains GS.
   *
   * Number of statements cached in memory is fixed (by system property). Hence,
   * GPS instances might get invalidated out of the cache. So when another query
   * for the evicted query statement comes, new GPS instance get created for a
   * new GS. StatementStats object though isn't recreated, it remains.
   *
   * CachedStatement.setIdentity() - is called when new GPS is put the cache.
   * CachedStatement.clearIdentity() - is called when the GPS is evicted from
   * the cache. StatementMBean unregister should happen here.
   *
   * It might happen that the GPS of a query which got evicted last, might be
   * the one which just got added to the cache. i.e. Threads executing
   * CachedStatement.setIdentity() & CachedStatement.clearIdentity() for the
   * same thread would be different. Hence, when StatementMBean is yet to be
   * unregistered (via CachedStatement.clearIdentity()), registration for the
   * new StatementMBean for the same statement happens (via
   * CachedStatement.setIdentity()), we should keep the StatementMBean but
   * refresh the internals used by that MBean.
   */
  // CachedStatement is being invalidated
  private void handleStatementInvalidate(com.pivotal.gemfirexd.internal.iapi.sql.Statement statement) {
    if (statement instanceof GenericStatement) {
      GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
      if (cache != null) {
        InternalDistributedMember thisMember = cache.getDistributedSystem()
            .getDistributedMember();
        String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);

      GenericStatement  genericStatement  = (GenericStatement)statement;
      PreparedStatement preparedStatement = genericStatement.getPreparedStatement();
      if (preparedStatement != null) {
        StatementStats statementStats = preparedStatement.getStatementStats();
        if (statementStats != null) { // statement stats might not have been set yet.
          String statsId = statementStats.getStatsId();
          ObjectName statementMBeanName = ManagementUtils.getStatementMBeanName(memberNameOrId, statsId);
          Object statementMBeanObject = this.gfManagementService.getJMXAdapter().getMBeanObject(statementMBeanName);
          if (statementMBeanObject != null) {
            StatementMBean statementMBean = (StatementMBean) statementMBeanObject;
            if (statementMBean.getRefCount() <= 1) {
              // No other concurrent call for statement create, unregister now
              this.unregisterMBean(ManagementUtils.getStatementMBeanName(memberNameOrId, statsId));
            } else {
              // another concurrent call for statement create has come don't
              // unregister but decrement ref count
              statementMBean.decRefCount();
            }
          } // statmentObject != null
        } // statementStats != null
      } // preparedStatement != null
    } // statement instanceof GenericStatement
   }
  }
  //**************** Event handler methods end *************
  
  // **************************** Testing only methods start ******************
  public long getLastMemoryAnalyticsQueryTime() { 
    if (this.tableMBeanDataUpdater != null) {
      return  ((TableMBeanDataUpdater)this.tableMBeanDataUpdater).lastMemoryAnalyticsQueryTime();
    } else {
      return -2;
    }
  }
  
  public int getUpdateSchedulerRate() {
    if (this.updateScheduler != null) {
      return this.updateScheduler.getUpdateRate();
    } else {
      return -2;
    }
  }
  // **************************** Testing only methods end ********************

  //**************** MXBean Object Accessor methods start *************
  public GfxdMemberMXBean getMemberMXBean() {
    return (GfxdMemberMXBean) this.gfManagementService.getMemberMXBean();
  }

  public LocalManager getLocalManager() {
    return this.gfManagementService.getLocalManager();
  }
  //**************** MXBean Object Accessor methods end *************

  // **************** For MBean registration/unregistration start *************
  public <T> ObjectName registerMBean(final T bean, final ObjectName objectName) {
    // Add to the Cleanable resources to cleanup before unregister
    if (bean instanceof Cleanable) {
      this.cleanables.put(objectName, (Cleanable) bean);
    }
    return this.gfManagementService.registerGfxdInternalMBean((T) bean, objectName);
  }

  public void unregisterMBean(final ObjectName objectName) {
    this.gfManagementService.unregisterMBean(objectName);

    // clean up resources used by MBean
    Cleanable cleanable = this.cleanables.get(objectName);
    if (cleanable != null) {
      cleanable.cleanUp();
    }
  }

  public Set<ObjectName> unregisterMBeanByPattern(final ObjectName objectNamePattern) {
    Set<ObjectName> unregisterdMBeans = this.gfManagementService.unregisterMBeanByPattern(objectNamePattern);
//    System.out.println("Unregistered MBeans ... "+unregisterdMBeans);

    for (ObjectName objectName : unregisterdMBeans) {
      // clean up resources used by MBean
      Cleanable cleanable = this.cleanables.get(objectName);
      if (cleanable != null) {
        cleanable.cleanUp();
      }
    }

    return unregisterdMBeans;
  }

  public <T> T getMBeanInstance(ObjectName objectName, Class<T> interfaceClass) {
    return this.gfManagementService.getMBeanInstance(objectName, interfaceClass);
  }
  // **************** For MBean registration/unregistration end ***************

  /**
   * Returns whether this member is running the management service.
   *
   * @return True if this member is running the management service, false
   *         otherwise.
   */
  public boolean isManager() {
    return this.gfManagementService.isManager();
  }

  /**
   * Starts the management service on this member.
   */
  public void startManager() {
    this.gfManagementService.startManager();
  }

  /**
   * Stops the management service running on this member.
   */
  public void stopManager() {
    if (this.gfManagementService.isManager()) {
      try {
        this.gfManagementService.stopManager();
      } catch (ManagementException ignore) {
      }
    }
  }

  public void afterCreateProxy(ObjectName objectName, Class<?> interfaceClass, Object proxyObject, FederationComponent newVal) {
    this.gfManagementService.afterCreateProxy(objectName, interfaceClass, proxyObject, newVal);
  }

  public void afterRemoveProxy(ObjectName objectName, Class<?> interfaceClass, Object proxyObject, FederationComponent oldVal) {
    this.gfManagementService.afterRemoveProxy(objectName, interfaceClass, proxyObject, oldVal);
  }

  public void afterUpdateProxy(ObjectName objectName, Class<?> interfaceClass, Object proxyObject, FederationComponent newVal, FederationComponent oldVal) {
    this.gfManagementService.afterUpdateProxy(objectName, interfaceClass, proxyObject, newVal, oldVal);
  }

  /**
   * Connection Wrapper Holder class which wraps {@link GfxdConnectionWrapper}
   * instance.
   * NOTE: First 2 steps for init are:
   * <ol>
   * <li> Initialize Management Service for GFXD & create first GFXD MBean
   *   - {@link FabricDatabase#boot(boolean, java.util.Properties)}
   * <li> Cache a connection wrapper for 'new' after DDL replay
   *   - {@link FabricDatabase#postCreate(com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection, java.util.Properties)}
   * </ol>
   * The embedded connection is required for some MBeans but it can be used
   * reliably only after DDL replay in {@link FabricDatabase#postCreate(com.pivotal.gemfirexd.internal.iapi.jdbc.EngineConnection, java.util.Properties).
   * Hence this connection wrapper initially has no GfxdConnectionWrapper but
   * gets one after DDL replay. Until then
   * ConnectionWrapperHolder#hasConnection() returns false.
   *
   * @author Abhishek Chaudhari
   * @since gfxd 1.0
   */
  public static class ConnectionWrapperHolder implements Cleanable {
    private GfxdConnectionWrapper wrapper;

    private ConnectionWrapperHolder() { // not instantiable outside this file
      this(null);
    }

    private ConnectionWrapperHolder(GfxdConnectionWrapper wrapper) { // not instantiable outside this file
      this.wrapper = wrapper;
    }

    public void cleanUp() {
      if (hasConnection()) {
        this.wrapper.close(); // closes embedded connection & with force
      }
      this.wrapper = null;
    }

    public boolean hasConnection() {
      return getConnection()!= null;
    }

    /**
     * @return the connection
     */
    public EmbedConnection getConnection() {
      final GfxdConnectionWrapper wrapper = this.wrapper;
      return wrapper != null ? wrapper.getConnectionOrNull() : null;
    }

    public void setConnectionWrapper(GfxdConnectionWrapper wrapper) {
      this.wrapper = wrapper;
    }

    @Override
    public String toString() {
      return "ConnectionWrapperHolder [hasConnection()=" + hasConnection() + "]";
    }
  }

  private void handleGFXDMbeansAggregation(GfxdMBeanAggregator gfxdMBeanAggregator) {
    try {
      Map<ObjectName, GemFireXDMBean> gemFireXDMBeansHolder = this.gfManagementService.getGemFireXDMBeansHolder();
      if (gemFireXDMBeansHolder.size() > 0) {
        String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(Misc.getGemFireCache().getDistributedSystem().getDistributedMember());
        ObjectName thisMemberName = ManagementUtils.getMemberMBeanName(memberNameOrId, ManagementUtils.DEFAULT_SERVER_GROUP);
        for (GemFireXDMBean gemFireXDMBean : gemFireXDMBeansHolder.values()) {
          try {
            if (!(gemFireXDMBean.objectName.equals(thisMemberName))) {
              gfxdMBeanAggregator.afterCreateProxy(gemFireXDMBean.objectName, gemFireXDMBean.interfaceClass, gemFireXDMBean.proxyObject,
                  gemFireXDMBean.newVal);
            }
          } catch (Exception e) {
            logger.warning("Error in handleGFXDMbeansAggregation for: " + " objectName=" + gemFireXDMBean.objectName + " thisMemberName="
                + thisMemberName + " Exception cause = " + e.getCause() + " Exception = " + e);
          }
        }
        this.gfManagementService.clearGemFireXDMBeans();
      } else {
        logger.info("In restart no GFXDMBeans to be aggregated for "
            + MBeanJMXAdapter.getMemberNameOrId(Misc.getGemFireCache().getDistributedSystem().getDistributedMember()));
      }
    } catch (Exception ex) {
      logger.warning("Exception in handleGFXDMbeansAggregation, cause=" + ex.getCause() + " Exception=" + ex);
    }
  }
}
