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

package cacheperf.comparisons.gemfirexd;

import hydra.BasePrms;
import hydra.BridgeHelper;
import hydra.CacheHelper;
import hydra.ClientVmInfo;
import hydra.ClientVmMgr;
import hydra.ClientVmNotFoundException;
import hydra.ConfigPrms;
import hydra.DistributedSystemHelper;
import hydra.HydraConfigException;
import hydra.HydraRuntimeException;
import hydra.HydraThreadLocal;
import hydra.Log;
import hydra.MasterController;
import hydra.PoolHelper;
import hydra.RemoteTestModule;
import hydra.StopSchedulingTaskOnClientOrder;
import hydra.gemfirexd.FabricServerDescription;
import hydra.gemfirexd.FabricServerHelper;
import hydra.gemfirexd.LonerHelper;
import hydra.gemfirexd.NetworkServerHelper;
import hydra.gemfirexd.GfxdConfigPrms;
import hydra.gemfirexd.GfxdTestConfig;

import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import objects.query.BaseQueryFactory;
import objects.query.GFEQueryFactory;
import objects.query.OQLQueryFactory;
import objects.query.QueryFactory;
import objects.query.QueryHelper;
import objects.query.QueryPrms;
import objects.query.SQLQueryFactory;
import cacheperf.CachePerfClient;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.CacheTransactionManager;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.control.RebalanceOperation;
import com.gemstone.gemfire.cache.control.RebalanceResults;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.cache.partition.PartitionMemberInfo;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.partition.PartitionRegionInfo;
import com.gemstone.gemfire.cache.query.FunctionDomainException;
import com.gemstone.gemfire.cache.query.IndexExistsException;
import com.gemstone.gemfire.cache.query.IndexNameConflictException;
import com.gemstone.gemfire.cache.query.NameResolutionException;
import com.gemstone.gemfire.cache.query.Query;
import com.gemstone.gemfire.cache.query.QueryInvocationTargetException;
import com.gemstone.gemfire.cache.query.RegionNotFoundException;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.utils.ExecutionPlanUtils;

/**
 * Client used to measure cache query performance.
 */
public class QueryPerfClient extends CachePerfClient {

  protected static final boolean useExistingData = QueryPerfPrms.useExistingData();
  protected static Map<String,List<Integer>> PrimaryBucketList;

  protected boolean logQueriesSetting;

  protected int queryAPI;
  protected QueryFactory queryFactory;
  protected String queryObjectType;
  protected int txIsolation;
  protected long queryPlanFrequency;
  protected int maxQueryPlanners;
  protected boolean queryPlanGenerationEnabled = false;

  //----------------------------------------------------------------------------
  //  Trim interval names
  //----------------------------------------------------------------------------
 
  protected static final int QUERIES = 6230814;
  protected static final int UPDATES = 6230815;
  protected static final int CREATES = 6230817;
  protected static final int DELETES = 6230818;
  
  protected static final String QUERY_NAME = "queries";
  protected static final String UPDATES_NAME = "updates";
  protected static final String CREATE_NAME = "creates";
  protected static final String DELETES_NAME = "deletes";

  public static void tmpTask() throws SQLException {
     FabricServerDescription fsd = GfxdTestConfig.getInstance().getFabricServerDescription("tester");
  }

  //----------------------------------------------------------------------------
  // locator startup
  //----------------------------------------------------------------------------

  /**
   * Creates locator endpoints.
   */
  public static void createLocatorTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initLocalParameters();
    c.createLocator();
  }
  private void createLocator() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        DistributedSystemHelper.createLocator();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
        noop();
        break;
      case QueryPrms.GFXD:
        FabricServerHelper.createLocator();
        break;
      default:
        unsupported();
    }
  }

  /**
   * Starts locators.
   */
  public static void startLocatorTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initLocalParameters();
    c.startLocator();
  }
  private void startLocator() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        DistributedSystemHelper.startLocatorAndAdminDS();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
        LonerHelper.connect(); // for statistics
        break;
      case QueryPrms.GFXD:
        String networkServerConfig = GfxdConfigPrms.getNetworkServerConfig();
        if (networkServerConfig == null) {
          log().info("Starting peer locator only");
          FabricServerHelper.startLocator();
        } else {
          log().info("Starting network locator");
          FabricServerHelper.startLocator(networkServerConfig);
        }
        break;
      default:
        unsupported();
    }
  }

  //----------------------------------------------------------------------------
  // server startup
  //----------------------------------------------------------------------------

  /**
   * Starts fabric server.
   */
  public static void startFabricServerTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.startFabricServer();
    c.updateHydraThreadLocals();
  }
  protected void startFabricServer() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        DistributedSystemHelper.connect();
        Cache c = CacheHelper.createCache(ConfigPrms.getCacheConfig());
        this.tm = getTxMgr(c);
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
        LonerHelper.connect(); // for statistics
        break;
      case QueryPrms.GFXD:
        startFabricServer(QueryPerfPrms.retryOnXBM09());
        break;
      default:
        unsupported();
    }
  }
  private void startFabricServer(boolean retryOnXBM09) {
    boolean started = false;
    while (!started) {
      try {
        FabricServerHelper.startFabricServer();
        started = true;
      } catch (HydraRuntimeException e) {
        if (retryOnXBM09 && e.getCause() != null
            && e.getCause() instanceof SQLException
            && ((SQLException)e.getCause()).getSQLState().equalsIgnoreCase("XBM09")) {
          log().info("Retrying in 20 seconds due to XBM09: " + e.getCause().getMessage());
          MasterController.sleepForMs(20000);
        } else {
          throw e;
        }
      }
    }
  }

  /**
   * Starts network server for use by thin clients.
   */
  public static void startNetworkServerTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.startNetworkServer();
    c.updateHydraThreadLocals();
  }
  private void startNetworkServer() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        BridgeHelper.startBridgeServer(ConfigPrms.getBridgeConfig());
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
        noop();
        break;
      case QueryPrms.GFXD:
        String networkServerConfig = GfxdConfigPrms.getNetworkServerConfig();
        NetworkServerHelper.startNetworkServers(networkServerConfig);
        break;
      default:
        unsupported();
    }
  }

  //----------------------------------------------------------------------------
  // server shutdown
  //----------------------------------------------------------------------------

  /**
   * Kills a server.
   */
  public static void killServerTask() throws ClientVmNotFoundException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.ttgid == 0) {
      String target = QueryPerfPrms.getKillTarget();
      Log.getLogWriter().info("Killing a " + target);
      ClientVmInfo info = new ClientVmInfo(null, target, null);
      Log.getLogWriter().info("Killing this server: " + info);
      ClientVmMgr.stop("Stopping " + info, ClientVmMgr.MEAN_KILL,
                                           ClientVmMgr.NEVER, info);
      Log.getLogWriter().info("Killed this server: " + info);
    }
  }

  /**
   * Issues shut-down-all. Use {@link #waitForServerShutdownTask} to wait for
   * the shutdown to complete. Use {@link #bounceSelfTask} to bounce the
   * server JVMs.
   */
  public static void shutDownAllTask() {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.ttgid == 0) {
      int sleepSec = QueryPerfPrms.getSleepBeforeShutdownSec();
      if (sleepSec > 0) {
        Log.getLogWriter().info("Sleeping for " + sleepSec + " seconds before shutdown");
        MasterController.sleepForMs(sleepSec * 1000);
        Log.getLogWriter().info("Done sleeping before shutdown");
      }
      FabricServerHelper.shutDownAllFabricServers(300);
    }
  }

  /**
   * Wait for the fabric server in this JVM to shut down. Run this task in
   * all servers  to wait for shut-down-all to complete, for example.
   */
  public static synchronized void waitForServerShutdownTask() {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.jid == 0) {
      c.waitForServerShutdown();
    }
  }

  protected void waitForServerShutdown() {
    while (true) {
      if (!FabricServerHelper.isFabricServerStopped()) {
        MasterController.sleepForMs(5000);
      } else {
        return;
      }
    }
  }

  /**
   * Bounces this JVM using a nice exit and immediate restart.
   * Assign it to all servers to bounce them all, for example.
   */
  public static void bounceSelfTask()
  throws ClientVmNotFoundException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.jid == 0) {
      c.bounceSelf();
    }
  }

  private void bounceSelf()
  throws ClientVmNotFoundException {
    Log.getLogWriter().info("Bouncing self, catch you later...");
    ClientVmMgr.stopAsync("Killing myself nicely with immediate restart",
                           ClientVmMgr.NICE_KILL, ClientVmMgr.IMMEDIATE);
  }

  //----------------------------------------------------------------------------
  // connection management
  //----------------------------------------------------------------------------

  /**
   * Sets up loner distributed system connection.
   */
  public static void connectLonerTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.connectLoner();
    c.updateHydraThreadLocals();
  }
  private void connectLoner() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFXD:
        LonerHelper.connect(); // for statistics
        break;
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      default:
        unsupported();
    }
  }

  /**
   * Gets embedded connection for peer clients.
   */
  public static void connectPeerClientTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.connectPeerClient();
    c.updateHydraThreadLocals();
  }
  private void connectPeerClient() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        Cache c = CacheHelper.createCache(ConfigPrms.getCacheConfig());
        this.tm = getTxMgr(c);
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.mySQLSetup(this);
        break;
      case QueryPrms.ORACLE:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.oracleSetup(this);
        break;
      case QueryPrms.GPDB:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.gpdbSetup(this);
        break;  
      case QueryPrms.RTE:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.rteSetup(this);
        break;
      case QueryPrms.GFXD:
        this.connection = QueryUtil.gfxdEmbeddedSetup(this);
        break;
      default:
        unsupported();
    }
  }

  public static void connectThinClientTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.connectThinClient();
    c.updateHydraThreadLocals();
  }
  private void connectThinClient() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        Cache c = CacheHelper.createCache(ConfigPrms.getCacheConfig());
        this.tm = getTxMgr(c);
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.mySQLSetup(this);
        break;
      case QueryPrms.ORACLE:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.oracleSetup(this);
        break;
      case QueryPrms.GPDB:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.gpdbSetup(this);
        break;  
      case QueryPrms.RTE:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.rteSetup(this);
        break;
      case QueryPrms.GFXD:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.gfxdClientSetup(this);
        break;
      default:
        unsupported();
    }
  }

  public static void connectThinWanClientTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.connectThinWanClient();
    c.updateHydraThreadLocals();
  }
  private void connectThinWanClient() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFXD:
        LonerHelper.connect(); // for statistics
        this.connection = QueryUtil.gfxdWanClientSetup(this);
        break;
      default:
        unsupported();
    }
  }

  public static void closeConnectionTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.closeConnection();
    c.updateHydraThreadLocals();
  }
  private void closeConnection() throws SQLException {
    if (this.connection != null) {
      try {
        Log.getLogWriter().info("Closing connection (after first committing) " + this.connection);
        try {
          this.connection.commit();
        } catch (SQLException e) {
          if (QueryPerfPrms.isHA() && e.getMessage().contains("Please retry the operation")) {
            Log.getLogWriter().info("Connection already closed due to server failure");
            this.connection = null;
          } else {
            throw e;
          }
        }
        if (this.connection != null) {
          this.connection.close();
          this.connection = null;
        }
        Log.getLogWriter().info("Closed connection");
      } catch (SQLException e) {
        if (e.getSQLState().equalsIgnoreCase("X0Z01") && QueryPerfPrms.isHA()) {
          Log.getLogWriter().info("Connection already closed due to server failure");
          this.connection = null;
        } else {
          throw e;
        }
      }
    } else {
      Log.getLogWriter().info("Connection already closed");
    }
  }

  protected Connection openTmpConnection() throws SQLException {
    Connection conn;
    if (this.connection == null) {
      conn = QueryUtil.gfxdEmbeddedSetup(this);
      conn.setTransactionIsolation(conn.TRANSACTION_NONE);
    } else {
      conn = this.connection;
    }
    return conn;
  }

  protected void closeTmpConnection(Connection conn) throws SQLException {
    if (this.connection == null) {
      conn.close();
    }
  }

  //----------------------------------------------------------------------------
  // bucket management
  //----------------------------------------------------------------------------

  /**
   * INITTASK to assign buckets for partitioned tables.  This must
   * run only after all tables are created, and before creating data.
   * Guarantees a perfect bucket and primary balance.  Execute this
   * task once for each distributed system.
   */
  public static void createBucketsTask()
  throws InterruptedException, SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.sttgid == 0) {
      if (c.queryAPI == QueryPrms.GFE) {
        c.assignBuckets();
      } else if (c.queryAPI == QueryPrms.GFXD) {
        c.assignBucketsForTables();
      }
    }
  }

  private void assignBucketsForTables() throws SQLException {
    final Connection conn = openTmpConnection();
    try {
      CallableStatement cs =
              conn.prepareCall("call SYS.CREATE_ALL_BUCKETS( ? )");
      List<String> tableNames = getPartitionedTables();
      for (String tableName : tableNames) {
        Log.getLogWriter().info("Creating buckets for table " + tableName);
        cs.setString(1, tableName);
        cs.execute();
        Log.getLogWriter().info("Created buckets for table " + tableName);
      }
      cs.close();
    } finally {
      closeTmpConnection(conn);
    }
  }

  public static void rebalanceBucketsTask()
  throws InterruptedException, SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.sttgid == 0) {
      Log.getLogWriter().info("Rebalancing buckets");
      if (c.queryAPI == QueryPrms.GFE) {
        c.rebalance();
      } else if (c.queryAPI == QueryPrms.GFXD) {
        c.rebalanceTables();
      }
      Log.getLogWriter().info("Rebalanced buckets");
    }
  }

  private void rebalanceTables() throws InterruptedException, SQLException {
    final Connection conn = openTmpConnection();
    try {
      CallableStatement cs = conn.prepareCall("call SYS.REBALANCE_ALL_BUCKETS()");
      cs.execute();
      cs.close();

      final ResourceManager rm = CacheFactory.getAnyInstance()
                                             .getResourceManager();
      Log.getLogWriter().info("Waiting for existing rebalance");
      for (RebalanceOperation op : rm.getRebalanceOperations()) {
        op.getResults(); // blocking call
      }
      Log.getLogWriter().info("Waited for existing rebalance");
      /*
      Log.getLogWriter().info("Waiting for follow-on rebalance");
      rm.createRebalanceFactory().start().getResults();
      for (RebalanceOperation op : rm.getRebalanceOperations()) {
        op.getResults(); // blocking call
      }
      Log.getLogWriter().info("Waited for follow-on rebalance");
      */
    } finally {
      closeTmpConnection(conn);
    }
  }

  /**
   * Checks the bucket count and primary bucket count for each region/table
   * hosted by this datahost, if any.  Execute this task on every datahost.
   */
  public static void checkBucketsTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.queryAPI == QueryPrms.GFXD && c.jid == 0) {
      List<String> tableNames = c.getPartitionedThings();
      if (tableNames != null) {
        for (String tableName : tableNames) {
          c.checkBuckets(tableName);
        }
      }
    }
  }

  private void checkBuckets(String tableName) {
    String err = "";
    String warning = "";

    Statistics prstats = getPRStatsByTextId(tableName);
    if (prstats != null) {
      int datahosts = getNumDatahosts(tableName);
      if (datahosts < 2) return;
      Log.getLogWriter().info("Checking bucket balance for " + tableName
                             + " with " + datahosts + " datahosts");
      int copies = prstats.getInt("configuredRedundantCopies") + 1;
      int primaries = prstats.getInt("primaryBucketCount");
      int buckets = prstats.getInt("bucketCount");
      int totalPrimaries = prstats.getInt("totalNumBuckets");
      int totalBuckets = totalPrimaries * copies;
      int bucketsPerDatahost = totalBuckets / datahosts;
      if (buckets != bucketsPerDatahost) {
        if (totalBuckets % datahosts == 0 || buckets != bucketsPerDatahost + 1) {
          err += tableName + " has " + buckets + " buckets.\n";
          List<Integer> bucketList = getBucketList(tableName);
          Log.getLogWriter().info("Bucket list for " + tableName + " is " + bucketList);
        }
      }
      int primariesPerDatahost = totalPrimaries / datahosts;
      if (primaries != primariesPerDatahost) {
        if (totalPrimaries % datahosts == 0 || primaries != primariesPerDatahost + 1) {
          err += tableName + " has " + primaries + " primary buckets.\n";
          List<Integer> bucketList = getPrimaryBucketList(tableName);
          Log.getLogWriter().info("Primary bucket list for " + tableName + " is " + bucketList);
        }
      }
      Log.getLogWriter().info("Checked bucket balance for " + tableName
         + ", found " + buckets + " buckets and " + primaries + " primaries");
      if (warning.length() > 0) {
        Log.getLogWriter().warning("Bucket validation issues:\n" + warning);
      }
      if (err.length() > 0) {
        String s = "Bucket validation failures:\n" + err;
        throw new QueryPerfException(s);
      }
    }
  }

  /**
   * Logs the bucket ids for each partitioned region/table hosted by this
   * datahost, if any.  Execute this task on every datahost.
   */
  public static void printBucketListTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.queryAPI == QueryPrms.GFXD && c.jid == 0) {
      List<String> tableNames = c.getPartitionedThings();
      if (tableNames != null) {
        for (String tableName : tableNames) {
          List<Integer> bucketList = c.getBucketList(tableName);
          Log.getLogWriter().info("Bucket list for " + tableName + " is " + bucketList);
        }
      }
    }
  }

  protected List<Integer> getBucketList(String tableName) {
    Cache cache = CacheHelper.getCache();
    for (PartitionRegionInfo pri :
         PartitionRegionHelper.getPartitionRegionInfo(cache)) {
      String regionPath = pri.getRegionPath();
      if (regionPath.contains(tableName)) {
        Log.getLogWriter().info("Region path is " + regionPath);
        PartitionedRegion pr = (PartitionedRegion)cache.getRegion(regionPath);
        Log.getLogWriter().info("Region name is " + pr.getName());
        PartitionedRegionDataStore dataStore = pr.getDataStore();
        List<Integer> bids = (List<Integer>)pr.getLocalBucketsListTestOnly();
        for (Integer bid : bids) {
          Log.getLogWriter().info(pr.getName() + ": size of bucket[" + bid
                                  + "]=" + dataStore.getBucketSize(bid));
        }
        return bids;
      }
    }
    return null;
  }

  /**
   * Sets the primary bucket ids for each partitioned region/table hosted by
   * this datahost, if any.  Execute this task on every datahost.
   */
  public static void setPrimaryBucketListTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    if (c.jid == 0) {
      if (c.queryAPI == QueryPrms.GFXD || c.queryAPI == QueryPrms.GFE) {
        List<String> tableNames = c.getPartitionedThings();
        if (tableNames != null) {
          for (String tableName : tableNames) {
            if (PrimaryBucketList == null) {
              PrimaryBucketList = new HashMap();
            }
            PrimaryBucketList.put(tableName, c.getPrimaryBucketList(tableName));
          }
        }
        Log.getLogWriter().info("Primary buckets: " + PrimaryBucketList);
      }
    }
  }

  protected List<Integer> getPrimaryBucketList(String tableName) {
    for (PartitionRegionInfo pri :
         PartitionRegionHelper.getPartitionRegionInfo(CacheHelper.getCache())) {
      String regionPath = pri.getRegionPath();
      if (regionPath.contains(tableName)) {
        Log.getLogWriter().info("Region path is " + regionPath);
        Region region = CacheHelper.getCache().getRegion(regionPath);
        String regionName = region.getName();
        Log.getLogWriter().info("Region name is " + regionName);
        return (List<Integer>)((PartitionedRegion)region).getLocalPrimaryBucketsListTestOnly();
      }
    }
    return null;
  }

  /**
   * Checks the data load for each partitioned region/table hosted by this
   * datahost, in bytes, if any.  Execute this task on every datahost.  Has
   * option to disable.
   */
  public static void checkDataLoadTask() throws SQLException {
    if (QueryPerfPrms.checkDataLoadEnabled()) {
      QueryPerfClient c = new QueryPerfClient();
      c.initialize();
      if (c.queryAPI == QueryPrms.GFXD && c.jid == 0) {
        List<String> tableNames = c.getPartitionedThings();
        if (tableNames != null) {
          for (String tableName : tableNames) {
            List<Integer> bucketList = c.getBucketList(tableName);
            Log.getLogWriter().info("Bucket list for " + tableName + " is " + bucketList);
          }
          for (String tableName : tableNames) {
            c.checkDataLoad(tableName);
          }
        }
      }
      c.reportMemoryUsed();
    }
  }

  private void checkDataLoad(String tableName) {
    String err = "";
    String warning = "";

    for (PartitionRegionInfo pri :
         PartitionRegionHelper.getPartitionRegionInfo(CacheHelper.getCache())) {
      String regionPath = pri.getRegionPath();
      // Don't look at HDFS AEQs
      if (regionPath.contains(tableName) && !regionPath.contains("AsyncEventQueue_GEMFIRE_HDFS_BUCKETSORTED_QUEUE")) {
        int datahosts = getNumDatahosts(tableName);
        if (datahosts < 2) continue;
        Log.getLogWriter().info("Checking entry load for " + tableName
                               + " with " + datahosts + " datahosts");
        List<Long> sizes = new ArrayList();
        Set<PartitionMemberInfo> pmis = pri.getPartitionMemberInfo();
        long maxSize = 0;
        for (PartitionMemberInfo pmi : pmis) {
          long size = pmi.getSize();
          if (size > maxSize) maxSize = size;
          sizes.add(size);

        }
        if (maxSize >= 0) {
          for (Long size : sizes) {
            double ratio = size < maxSize ? (double)size/(double)maxSize
                                          : (double)maxSize/(double)size;
            if (ratio < 0.90) {
              err += tableName + " is imbalanced: " + sizes + "\n";
              break;
            }
          }
        }
        Log.getLogWriter().info("Checked entry load for " + tableName
           + ", found entry sizes " + sizes + " bytes");
      }
    }
    if (warning.length() > 0) {
      Log.getLogWriter().warning("Data load balance issues:\n" + warning);
    }
    if (err.length() > 0) {
      throw new QueryPerfException("Data load balance failures:\n" + err);
    }
  }

  private int getNumDatahosts(String tableName) {
    for (PartitionRegionInfo pri :
         PartitionRegionHelper.getPartitionRegionInfo(CacheHelper.getCache())) {
      if (pri.getRegionPath().contains(tableName)) {
        Set<PartitionMemberInfo> pmis = pri.getPartitionMemberInfo();
        return pmis.size();
      }
    }
    return 0;
  }

  private void reportMemoryUsed() throws SQLException {
    if (this.queryAPI != QueryPrms.GFXD) {
      noop();
    } else {
      Log.getLogWriter().info("Printing memory analytics...");
      Connection tmpconn = QueryUtil.gfxdEmbeddedSetup(this);
      tmpconn.setTransactionIsolation(QueryPerfPrms.TRANSACTION_NONE);
      String stmt = "select sum(entry_size), sum(key_size), sum(value_size), sum(total_size), table_name,index_name from sys.memoryanalytics group by table_name , index_name";
      Log.getLogWriter().info("Executing " + stmt);
      PreparedStatement memstmt = tmpconn.prepareStatement(stmt);
      ResultSet rs = memstmt.executeQuery();
      Log.getLogWriter().info("Executed " + stmt + ", reading results");
      long total_entry_size = 0L;
      long total_key_size = 0L;
      long total_value_size = 0L;
      long total_size = 0L;
      while (rs.next()) {
        total_entry_size += rs.getLong(1);
        total_key_size += rs.getLong(2);
        total_value_size += rs.getLong(3);
        total_size += rs.getLong(4);
      }
      Log.getLogWriter().info("MEMORY Analysis: total_size=" + total_size + " total_entry_size=" +  total_entry_size + " total_key_size=" + total_key_size + " total_value_size=" + total_value_size);
      rs.close();
      rs = null;
      tmpconn.close();
      Log.getLogWriter().info("Done printing memory analytics");
    }
  }

  public Statistics getPRStatsByTextId(String tableName) {
    String textId = getTextId(tableName);
    DistributedSystem ds = DistributedSystemHelper.getDistributedSystem();
    Statistics[] stats = ds.findStatisticsByTextId(textId);
    Statistics prStats = null;
    // There are several stats named after the table/region
    // - HdfsRegionStatistics
    // - PartitionedRegionStats
    // - DiskRegionStatistics
    // Make sure we get the right one!
    for (int i = 0; i < stats.length; i++) {
      if (stats[i].getType().getName().equals("PartitionedRegionStats")) {
        prStats = stats[i];
      }
    }
    return prStats;
  }

  public List<String> getPartitionedThings() throws SQLException {
    List<String> tableNames = null;
    switch (this.queryAPI) {
      case QueryPrms.GFE:
        tableNames = this.getPartitionedRegions();
        break;
      case QueryPrms.GFXD:
        tableNames = this.getPartitionedTables();
        break;
      default:
        break;
    }
    return tableNames;
  }

  public List<String> getPartitionedRegions() {
    List<String> regionNames = new ArrayList();
    Cache c = CacheHelper.getCache();
    if (c != null) {
      for (Region r : c.rootRegions()) {
        if (r.getAttributes().getDataPolicy().withPartitioning()) {
          regionNames.add(r.getName());
        }
      }
    }
    return regionNames;
  }

  public List<String> getPartitionedTables() throws SQLException {
    List<String> tableNames = new ArrayList();
    final Connection conn = openTmpConnection();
    try {
      PreparedStatement ps = conn.prepareStatement("SELECT tablename, datapolicy FROM sys.systables WHERE tableschemaname='APP'");
      ResultSet rs = ps.executeQuery();
      while (rs.next()) {
        String tablename = rs.getString("tablename");
        String datapolicy = rs.getString("datapolicy");
        if (datapolicy.contains("PARTITION")) {
          tableNames.add(tablename);
        }
      }
      rs.close();
      rs = null;
      ps.close();
    } finally {
      closeTmpConnection(conn);
    }
    return tableNames;
  }

  private String getTextId(String tableName) {
    switch (this.queryAPI) {
      case QueryPrms.GFE:
        return "/" + tableName;
      case QueryPrms.GFXD:
        return "/APP/" + tableName;
      default:
        throw new UnsupportedOperationException("Unsupported query API");
    }
  }

  /**
   * Returns true if this member is the primary for the key in the named partition region.
   * Returns false otherwise, or if the region is not partitioned.
   */
  protected boolean isPrimary(Object key, String regionName) {
    Region region = CacheHelper.getCache().getRegion(regionName);
    DistributedMember thisDM = DistributedSystemHelper.getDistributedSystem().getDistributedMember();
    if (PartitionRegionHelper.isPartitionedRegion(region)) {
      DistributedMember thatDM = PartitionRegionHelper.getPrimaryMemberForKey(region, key);
      return thisDM.equals(thatDM);
    }
    return false;
  }

  //----------------------------------------------------------------------------
  // disk store management
  //----------------------------------------------------------------------------

  public static void setEvictionHeapPercentageTask() throws SQLException{
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.setEvictionHeapPercentage();
  }

  private void setEvictionHeapPercentage() throws SQLException {
    int percentage = QueryPerfPrms.getEvictionHeapPercentage();
    if (percentage > 0) {
      if (this.queryAPI == QueryPrms.GFXD) {
        final Connection conn = openTmpConnection();
        try {
          CallableStatement cs = conn.prepareCall("call SYS.SET_EVICTION_HEAP_PERCENTAGE(?)");
          cs.setInt(1, percentage);
          cs.execute();
          cs.close();
        } finally {
          closeTmpConnection(conn);
        }
        float evictionHeapPercentage = Misc.getGemFireCache().getResourceManager().getEvictionHeapPercentage();
        Log.getLogWriter().info("Set evictionHeapPercentage=" + evictionHeapPercentage);
      } else {
        unsupported();
      }
    }
  }

  //----------------------------------------------------------------------------
  // dbsynchronizer
  //----------------------------------------------------------------------------

  /**
   * Creates and starts a DBSynchronizer.
   */
  public static void createAndStartDBSynchronizerTask() throws SQLException{
    String dbsName = QueryPerfPrms.getDBSynchronizerName();
    if (dbsName != null) {
      QueryPerfClient c = new QueryPerfClient();
      c.initialize();
      if (c.ttgid == 0) {
        c.createAndStartDBSynchronizer(dbsName);
      }
    }
  }

  private void createAndStartDBSynchronizer(String dbsName)
  throws SQLException {
    String driver = null;
    String url = null;
    switch (QueryPerfPrms.getDBAPI()) {
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
        driver = QueryUtil.mySQLDriver();
        url = QueryUtil.mySQLURL();
        break;
      case QueryPrms.ORACLE:
        driver = QueryUtil.oracleDriver();
        url = QueryUtil.oracleURL();
        break;
      case QueryPrms.GPDB:
        driver = QueryUtil.gpdbDriver();
        url = QueryUtil.gpdbURL();
        break;  
      default:
        unsupported();
    }
    String query = "create asynceventlistener "
      + QueryPerfPrms.getDBSynchronizerName()
      + " ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer'"
      + " initparams '" + driver + "," + url + "' "
      + QueryPerfPrms.getDBSynchronizerConfig()
      + " )";
    String groups = QueryPerfPrms.getDBSynchronizerServerGroups();
    if (groups != null) {
      query += " server groups ( " + groups + " )";
    }
    final Connection conn = openTmpConnection();
    Statement stmt = conn.createStatement();
    Log.getLogWriter().info("Creating dbsychronizer \"" + query + "\"");
    stmt.execute(query);
    Log.getLogWriter().info("Created dbsychronizer \"" + query + "\"");
    try {
      CallableStatement cs =
              conn.prepareCall("call SYS.START_ASYNC_EVENT_LISTENER( ? )");
      Log.getLogWriter().info("Starting dbsychronizer " + dbsName);
      cs.setString(1, dbsName);
      cs.execute();
      Log.getLogWriter().info("Started dbsychronizer " + dbsName);
      cs.close();
    } finally {
      closeTmpConnection(conn);
    }
  }

  //----------------------------------------------------------------------------
  // clean up
  //----------------------------------------------------------------------------
  public static void deleteQueryDataContainersTask() throws SQLException { 
    if (useExistingData) {
      Log.getLogWriter().info("No tables dropped, using existing data");
      return; // noop
    }
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.initAdditionalLocalParameters();
    if (c.sttgid == 0) {
      c.deleteQueryDataContainers();
    }
  }

  private void deleteQueryDataContainers() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        noop();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:  
      case QueryPrms.RTE:
        dropTables();
        break;
      case QueryPrms.GFXD:
        dropTables();
        dropSchema();
        break;
      default:
        unsupported();
    }
  }

  private void dropSchema() {
    String stmt =
        ((SQLQueryFactory) this.queryFactory).getDropSchemaStatement();
    Log.getLogWriter().info("Dropping schema using: " + stmt);
    try {
      ((SQLQueryFactory) this.queryFactory).execute(stmt, this.connection);
      this.connection.commit();
    } catch (SQLException e) {
      Log.getLogWriter().warning("Failed to drop shema: " + e.getMessage());
    }
  }

  private void dropTables() {
    List stmts = ((SQLQueryFactory) this.queryFactory).getDropTableStatements();
    for (Iterator i = stmts.iterator(); i.hasNext();) {
      String stmt = (String) i.next();
      Log.getLogWriter().info("Dropping table using: " + stmt);
      try {
        ((SQLQueryFactory) this.queryFactory).execute(stmt, this.connection);
        this.connection.commit();
      } catch (SQLException e) {
        Log.getLogWriter().warning("Failed to drop table: " + e.getMessage());
      }
    }
  }
  
  //----------------------------------------------------------------------------
  // Query plan tasks and support
  //----------------------------------------------------------------------------

  /**
   * Opens the query plan recorder for a single thread in the task thread group.
   * Turns on query logging temporarily if it is not already on.
   * This method is usable by any connected thread.
   */
  protected void enableQueryPlanGeneration()
  throws SQLException {
    if (!queryPlanGenerationEnabled && generateQueryPlan()) {
      Statement stmt = this.connection.createStatement();
      stmt.execute("call SYSCS_UTIL.SET_EXPLAIN_CONNECTION(1)");
      stmt.execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(1)");
      stmt.close();
      Log.getLogWriter().info("Enabled query plan generation and timing");
      queryPlanGenerationEnabled = true;
      logQueriesSetting = getLogQueries(); // save the original setting
      setLogQueries(true); // turn on query logging
    }
  }

  /**
   * Closes the query plan recorder for a single thread in the task thread group.
   * This method is usable by any connected thread.
   */
  protected void disableQueryPlanGeneration()
  throws SQLException {
    if (queryPlanGenerationEnabled) {
      Statement stmt = this.connection.createStatement();
      stmt.execute("call SYSCS_UTIL.SET_EXPLAIN_CONNECTION(0)");
      stmt.execute("call SYSCS_UTIL.SET_STATISTICS_TIMING(0)");
      stmt.close();
      Log.getLogWriter().info("Disabled query plan generation and timing");
      queryPlanGenerationEnabled = false;
      setLogQueries(logQueriesSetting); // restore the original setting
    }
  }

  protected boolean getLogQueries() {
    return ((BaseQueryFactory)this.queryFactory).getLogQueries();
  }

  protected void setLogQueries(boolean b) {
    ((BaseQueryFactory)this.queryFactory).setLogQueries(b);
  }

  /**
   * Returns whether this thread is a query planner and it is time to generate
   * a query plan based on the configured frequency.
   */
  protected boolean generateQueryPlan() {
    return this.queryPlanFrequency != 0 && this.queryAPI == QueryPrms.GFXD
        && this.sttgid < this.maxQueryPlanners //&& this.warmedUp
        && timeToGenerateQueryPlan();
  }

  /**
   * Returns true every <code>queryPlanFrequency</code> ms since the thread
   * first started on the task, regardless of how many times it is scheduled
   * or whether warmup is complete.
   */
  protected boolean timeToGenerateQueryPlan() {
    long now = System.currentTimeMillis();
    if (now - this.lastQueryPlanTime > this.queryPlanFrequency) {
      this.lastQueryPlanTime = now;
      return true;
    }
    return false;
  }

  /**
   * Dumps the query plans.  Establishes a temporary embedded
   * connection if the thread is not already connected.  Must be used by
   * a peer member.
   */
  public static void dumpQueryPlansTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(-1);
    if (c.queryAPI == QueryPrms.GFXD && c.sttgid == 0) {
      c.dumpQueryPlans();
    }
  }

  private void dumpQueryPlans() throws SQLException {
    final Connection conn = openTmpConnection();
    try {
      Statement stmt = conn.createStatement();
      ResultSet r = stmt.executeQuery("select STMT_ID, STMT_TEXT from SYS.STATEMENTPLANS");
      Log.getLogWriter().info("Extracting query plans");
      int numQueryPlans = 0;
      while (r.next()) {
        ++numQueryPlans;
        final String stmt_id = r.getString("STMT_ID");
        String stmtInfo = "stmt_id = " + stmt_id + " statement = "
                        + r.getString("STMT_TEXT");
        ExecutionPlanUtils plan =
                 new ExecutionPlanUtils(conn, stmt_id, null, true);
        String planAsText = String.valueOf(plan.getPlanAsText(null)).trim();
        Log.getLogWriter().info("Query plan...\n" + stmtInfo + "\n"
                                                  + planAsText);
      }
      Log.getLogWriter().info("Extracted " + numQueryPlans + " query plans");
    } finally {
      closeTmpConnection(conn);
    }
  }

  //----------------------------------------------------------------------------
  // Memory tasks
  //----------------------------------------------------------------------------

  public static void reportMemoryAnalyticsTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.reportMemoryAnalytics();
  }

  public static void measureEmptyTableMemoryUsageTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.measureEmptyTableMemoryUsage();
  }
  
  public static void measureLoadedTableMemoryUsageTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.measureLoadedTableMemoryUsage();
  }
  
  private void reportMemoryAnalytics() throws SQLException {
    if (this.queryAPI != QueryPrms.GFXD) {
      noop();
    } else {
      Log.getLogWriter().info("Printing memory analytics...");
      Connection tmpconn = QueryUtil.gfxdEmbeddedSetup(this);
      tmpconn.setTransactionIsolation(QueryPerfPrms.TRANSACTION_NONE);
      String stmt = "select * from sys.memoryanalytics";
      List<String> sizerHints = QueryPerfPrms.getSizerHints();
      if (sizerHints != null) {
        stmt += " -- GEMFIREXD-PROPERTIES";
        String hints = "";
        for (String sizerHint : sizerHints) {
          if (hints.length() > 0) {
            hints += ",";
          }
          hints += " sizerHints=" + sizerHint;
        }
        stmt += hints;
      }
      Log.getLogWriter().info("Executing " + stmt);
      PreparedStatement memstmt = tmpconn.prepareStatement(stmt);
      ResultSet rs = memstmt.executeQuery();
      Log.getLogWriter().info("Executed " + stmt + ", reading results");
      long total = 0L;
      long ndxTotal = 0L;
      long ndxOverheadTotal = 0L;
      long tableTotal = 0L;
      long tableRowTotal = 0L;
      long ndxRowTotal = 0L;
      long footprintTotal = 0L;
      while (rs.next()) {
        ResultSetMetaData rsmd = rs.getMetaData();
        StringBuilder s = new StringBuilder();
        String sqlentity = null;
        String memory = null;
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
          String colname = rsmd.getColumnName(i);
          Object obj = rs.getObject(colname);
          s.append(colname).append("=").append(obj).append(" ");
          if (colname.equals("SQLENTITY")) sqlentity = obj.toString();
          if (colname.equals("MEMORY")) memory = obj.toString();
        }
        Log.getLogWriter().info(s.toString());
        if (sqlentity != null && memory != null) {
          int index = memory.indexOf(" ");
          String mem = (index == -1) ? memory : memory.substring(0, index);
          long[] nums = getLongs(mem.split(","));
          if (nums.length == 3) {
            if (sqlentity.contains("(Entry Size, Value Size, Row Count)")) {
              tableTotal += nums[0] + nums[1];
              tableRowTotal += nums[2];
            } else if (sqlentity.contains("(Index Entry Size, Value Size, Row Count)")) {
              ndxTotal += nums[0] + nums[1];
              ndxRowTotal += nums[2];
            } else if (sqlentity.contains("(Index Entry Overhead, SkipList Size, Max Level)")) {
              ndxOverheadTotal += nums[0] + nums[1] + nums[2];
            } else if (sqlentity.contains("(gemfirexd,gemfire,others)")) {
              footprintTotal += nums[0] + nums[1] + nums[2];
            } else {
              Log.getLogWriter().warning("Skipping memory: " + mem);
            }
          } else {
            Log.getLogWriter().warning("Skipping memory: " + mem);
          }
        }
        total = tableTotal + ndxTotal + ndxOverheadTotal;
      }
      Log.getLogWriter().info("MEMORY: table=" + + tableTotal + " ndx=" + ndxTotal + " ndxOverhead=" + ndxOverheadTotal + " totalTableNdxOverheadMemory=" + total + " footprint=" + footprintTotal);
      Log.getLogWriter().info("ENTRIES: table=" + + tableRowTotal + " ndx=" + ndxRowTotal);
      rs.close();
      rs = null;
      tmpconn.close();
      Log.getLogWriter().info("Done printing memory analytics");
    }
  }

  private long[] getLongs(String[] strs) {
    long[] nums = new long[strs.length];
    for (int i = 0; i < strs.length; i++) {
      try {
        nums[i] = Long.parseLong(strs[i]);
      } catch (NumberFormatException e) {
        String s = strs[i] + " is not a long";
        throw new QueryPerfException(s);
      }
    }
    return nums;
  }

  private void measureEmptyTableMemoryUsage() {
    if (this.queryAPI != QueryPrms.GFXD) {
      noop();
    } else if (QueryPerfPrms.enableMemoryStats()) {
      this.querystats.setEmptyTableMemUse(usedMemory());    
    } else {
      String s = "This operation is a noop since "
               + BasePrms.nameForKey(QueryPerfPrms.enableMemoryStats)
               + "=false";
      Log.getLogWriter().info(s);
    }
  }
  
  private void measureLoadedTableMemoryUsage() {
    if (this.queryAPI != QueryPrms.GFXD) {
      noop();
    } else if (QueryPerfPrms.enableMemoryStats()) {
      this.querystats.setLoadedTableMemUse(usedMemory());    
    } else {
      String s = "This operation is a noop since "
               + BasePrms.nameForKey(QueryPerfPrms.enableMemoryStats)
               + "=false";
      Log.getLogWriter().info(s);
    }
  }
  
  protected long usedMemory() {
    //just for crazy sake, let's try to clear the system of garbage...more than once...
    System.gc();
    System.gc();
    System.gc();
    System.gc();
    Runtime runtime = Runtime.getRuntime();
    long freeMem = runtime.freeMemory();
    long totalMem = runtime.totalMemory();
    long usedMem = totalMem - freeMem;
    return usedMem;
  }
 

  //----------------------------------------------------------------------------
  // createQueryDataContainersTask
  //----------------------------------------------------------------------------

  public static void createQueryDataContainersTask() throws SQLException { 
    if (useExistingData) {
      Log.getLogWriter().info("No tables created, using existing data");
      return; // noop
    }
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.initAdditionalLocalParameters();
    if (c.sttgid == 0) {
      c.deleteQueryDataContainers(); // clean up first
      c.createQueryDataContainers();
    }
  }

  private void createQueryDataContainers() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE:
      case QueryPrms.OQL:
        noop(); // regions are created in a different task
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
        createTables();
        break;
      case QueryPrms.GFXD:
        createSchema();
        createTables();
        break;
      default:
        unsupported();
    }
  }

  public static void createQueryDataRegionsTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize();
    c.initAdditionalLocalParameters();
    c.createQueryDataRegions();
  }
  
  private void createQueryDataRegions() throws SQLException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE_GFK:
        loadDvdSerializer();
        //continue to finish gfe config...
      case QueryPrms.GFE:
        ((GFEQueryFactory)this.queryFactory).createRegions();
        break;
      case QueryPrms.OQL:
        ((OQLQueryFactory)this.queryFactory).createRegions();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB: 
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        noop();
        break;
      default:
        unsupported();
    }
  }

  // initialize to load dvdserializer so we can use dvd objects and gemfirekeys
  private void loadDvdSerializer() {
    try {
      Class misc =
        Class.forName("com.pivotal.gemfirexd.internal.iapi.types.DataType");
      Method initMethod = misc.getMethod("init", (Class[])null);
      initMethod.invoke(null, new Object[0]);   
    } catch (Exception e) {
      Log.getLogWriter().info(e);
    }
  }
  
  private void createSchema() throws SQLException {
    String stmt =
        ((SQLQueryFactory) this.queryFactory).getCreateSchemaStatement();
    Log.getLogWriter().info("Creating schema using: " + stmt);
    ((SQLQueryFactory) this.queryFactory).execute(stmt, this.connection);
    this.connection.commit();
  }

  private void createTables() throws SQLException {
    List stmts = ((SQLQueryFactory) this.queryFactory).getTableStatements();
    for (Iterator i = stmts.iterator(); i.hasNext();) {
      String stmt = (String) i.next();
      Log.getLogWriter().info("Creating table using: " + stmt);
      ((SQLQueryFactory) this.queryFactory).execute(stmt, this.connection);
    }
    this.connection.commit();
  }

  //----------------------------------------------------------------------------
  // createIndexesTask
  //----------------------------------------------------------------------------

  public static void createIndexesTask() throws SQLException, 
      NameResolutionException, RegionNotFoundException, IndexExistsException,
      IndexNameConflictException {
    if (useExistingData) {
      Log.getLogWriter().info("No indexes created, using existing data");
      return; // noop
    }
    if (QueryPerfPrms.createIndexes()) {
      QueryPerfClient c = new QueryPerfClient();
      c.initialize();
      c.initAdditionalLocalParameters();
      if (c.sttgid == 0) {
        c.createIndexes();
      }
    }
  }

  private void createIndexes() throws SQLException, NameResolutionException,
      RegionNotFoundException, IndexExistsException, IndexNameConflictException {
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE:
        noop();
        break;
      case QueryPrms.OQL:
        createOQLIndex();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        createSQLIndexes();
        break;
      default:
        unsupported();
    }
  }

  private void createSQLIndexes() throws SQLException {
    int result;
    List stmts = ((SQLQueryFactory) this.queryFactory).getIndexStatements();
    for (Iterator i = stmts.iterator(); i.hasNext();) {
      String stmt = (String) i.next();
      result =
          ((SQLQueryFactory) this.queryFactory).executeUpdate(stmt,
              this.connection);
      this.connection.commit();
    }
  }

  private void createOQLIndex() throws NameResolutionException,
      RegionNotFoundException, IndexExistsException, IndexNameConflictException {
    ((OQLQueryFactory) this.queryFactory).createIndexes();
  }

  //----------------------------------------------------------------------------
  // preparedCreateQueryDataTask
  //----------------------------------------------------------------------------

  public static void preparedCreateQueryDataTask() throws SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No data created, using existing data");
      throw new StopSchedulingTaskOnClientOrder(); // noop
    }
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(CREATES);
    c.initAdditionalLocalParameters();
    c.preparedCreateQueryData();
  }

  private void preparedCreateQueryData() throws SQLException {
    List pstmts = null;
    List stmts = null;
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE: 
        pstmts =
          ((GFEQueryFactory) this.queryFactory).getPreparedInsertObjects();
        break;
      case QueryPrms.OQL:
        pstmts =
            ((OQLQueryFactory) this.queryFactory).getPreparedInsertObjects();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        stmts =
            ((SQLQueryFactory) this.queryFactory).getPreparedInsertStatements();
        pstmts = convertStmtsToPStmts(stmts, connection);
        break;
      default:
        unsupported();
    }
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      begin();
      preparedCreateQueryData(key, pstmts, stmts);
      commit();
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
  }

  private void preparedCreateQueryData(int i, List pstmts, List stmts) throws SQLException {
    long start = this.querystats.startCreate();
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE:
        preparedInsertGFEData(i, pstmts);
        break;
      case QueryPrms.OQL:
        preparedInsertOQLData(i, pstmts);
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        preparedInsertSQLData(i, pstmts, stmts);
        break;
      default:
        unsupported();
    }
    this.querystats.endCreate(start, this.histogram);//, this.isMainWorkload);
  }

  private void preparedInsertSQLData(int i, List pstmts, List stmts) throws SQLException {
    ((SQLQueryFactory) this.queryFactory)
        .fillAndExecutePreparedInsertStatements(pstmts, stmts, i);
  }

  private void preparedInsertOQLData(int i, List pobjs) {
    ((OQLQueryFactory) this.queryFactory).fillAndExecutePreparedInsertObjects(
        pobjs, i);
  }
  
  private void preparedInsertGFEData(int i, List pobjs) {
    ((GFEQueryFactory) this.queryFactory).fillAndExecutePreparedInsertObjects(pobjs, i);
  }

  //----------------
  //Direct get
  //----------------
  public static void directGetDataTask() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(QUERIES);
    c.initAdditionalLocalParameters();
    c.directGetData();
  }

  private void directGetData() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    switch(this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE:
        getDataFromRegion();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:  
      case QueryPrms.GFXD:
        preparedQueryQueryData();
        break;
      case QueryPrms.RTE:
        preparedQueryQueryData();
        break;
      default:
        unsupported();
    }
  }
  
  private void getDataFromGFXDRegion() {
    int queryType = this.queryFactory.getQueryType();
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      getDataFromGFXDRegion(key, queryType);
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
  }
  
  private void getDataFromGFXDRegion(int i, int queryType) {
    ResultSet rs = null;
    Object key = null;
    Region region = null;
    Class c;
    try {
      c = Class.forName("com.pivotal.gemfirexd.internal.engine.Misc");
      Method getGemFireKeyMethod = c.getMethod("getGemFireKey", new Class[] {int.class});
      Object[] idArray = {i};
      key = getGemFireKeyMethod.invoke(null, idArray);   
      region = ((SQLQueryFactory)this.queryFactory).getRegionForQuery(queryType);
      //Log.getLogWriter().info("REGION ATTRIBUTES:" + region.getAttributes().toString());
    }
    catch(Exception e) {
      Log.getLogWriter().info(e);
    }
    
    long start = this.querystats.startQuery();
    ((SQLQueryFactory) this.queryFactory).directGet(key, region);
    this.querystats.endQuery(start, this.histogram);//, this.isMainWorkload);
  }
    
  private void getDataFromRegion() {
    int queryType = this.queryFactory.getQueryType();
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      getDataFromRegion(key, queryType);
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
  }

  private void getDataFromRegion(int i, int queryType) {
    Region region = null;
    Object entry = null;
    Object key = null;
    long start;
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
        try {
          Class c = Class.forName("com.pivotal.gemfirexd.internal.engine.Misc");
          Method getGemFireKeyMethod = c.getMethod("getGemFireKey", new Class[] {int.class});
          Object[] idArray = {i};
          key = getGemFireKeyMethod.invoke(null, idArray);   
        }
        catch(Exception e) {
          Log.getLogWriter().info(e);
        }
        region = ((GFEQueryFactory)this.queryFactory).getRegionForQuery(queryType);
        start = this.querystats.startQuery();
        entry = ((GFEQueryFactory) this.queryFactory).directGet(key, region);
        ((GFEQueryFactory) this.queryFactory).readResultSet(queryType, entry);
        this.querystats.endQuery(start, this.histogram);//, this.isMainWorkload);
        break;
      case QueryPrms.GFE:
        //change to key
        region = ((GFEQueryFactory)this.queryFactory).getRegionForQuery(queryType);
        //GemFireKey 
        key = new Integer(i);
        start = this.querystats.startQuery();
        entry = ((GFEQueryFactory) this.queryFactory).directGet(key, region);
        ((GFEQueryFactory) this.queryFactory).readResultSet(queryType, entry);
        this.querystats.endQuery(start, this.histogram);//, this.isMainWorkload);
        break;
      default:  
        unsupported();
    }
  }
  
  //----------------
  //Direct Update
  //----------------
  public static void preparedUpdateQueryDataTask() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(UPDATES);
    c.initAdditionalLocalParameters();
    c.directPreparedUpdateData();
  }

  private void directPreparedUpdateData() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    switch(this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE:
        updateDataFromRegion();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.GFXD:
        preparedUpdateQueryData();
        break;
      case QueryPrms.RTE:
        preparedUpdateQueryData();
        break;
      default:
        unsupported();
    }
  }
  private void updateDataFromRegion() {
    int queryType = this.queryFactory.getUpdateQueryType();
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      begin();
      updateDataFromRegion(key, queryType);
      commit();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
  }

  private void updateDataFromRegion(int i, int queryType) {
    Region region = null;
    Object entry = null;
    Object key = null;
    long start;
    switch (this.queryAPI) {
      case QueryPrms.GFE:
        start = this.querystats.startUpdate();
        ((GFEQueryFactory) this.queryFactory).directUpdate(i,queryType);
        this.querystats.endUpdate(start, this.histogram);//, this.isMainWorkload);
        break;
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
        //GemFireKey 
        /*
        try {
          Class c = Class.forName("com.pivotal.gemfirexd.internal.engine.Misc");
          Method getGemFireKeyMethod = c.getMethod("getGemFireKey", new Class[] {int.class});
          Object[] idArray = {i};
          key = getGemFireKeyMethod.invoke(null, idArray);   
        }
        catch(Exception e) {
          Log.getLogWriter().info(e);
        }
        entry = ((GFEQueryFactory) this.queryFactory).directGet(key, region);
        start = this.querystats.startUpdate();
        ((GFEQueryFactory) this.queryFactory).directUpdate(i, queryType);
        this.querystats.endUpdate(start, this.histogram);//, this.isMainWorkload);
        */     
      default:  
        unsupported();
    }
  
  }
  
  private void preparedUpdateQueryData() throws SQLException {
    int queryType = this.queryFactory.getUpdateQueryType();
    String stmt = this.queryFactory.getPreparedQuery(queryType);
    PreparedStatement pstmt = connection.prepareStatement(stmt);
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      begin();
      preparedUpdateQueryData(key, queryType, pstmt, stmt);
      commit();
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());

    pstmt.close();
  }

  private void preparedUpdateQueryData(int i, int queryType,
      PreparedStatement pstmt, String stmt) throws SQLException {
    long start = this.querystats.startUpdate();
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE:
        noop();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        ((SQLQueryFactory) this.queryFactory)
            .fillAndExecuteUpdatePreparedQueryStatement(pstmt, stmt, queryType, i);
        break;
      default:  
        unsupported();
    }
    this.querystats.endUpdate(start, this.histogram);//, this.isMainWorkload);

  }
  
  //----------------------------------------------------------------------------
  //update query data
  //----------------------------------------------------------------------------
  
  public static void updateQueryDataTask() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(UPDATES);
    c.initAdditionalLocalParameters();
    c.directUpdateData();
  }
  
  private void directUpdateData() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    switch(this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE:
        updateDataFromRegion();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.GFXD:
        updateQueryData();
        break;
      default:
        unsupported();
    }
  }
  
  private void updateQueryData() throws SQLException {
    int queryType = this.queryFactory.getUpdateQueryType();
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      begin();
      updateQueryData(key, queryType);
      commit();
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
  }

  private void updateQueryData(int i, int queryType) throws SQLException {
    String stmt = this.queryFactory.getQuery(queryType, i);
    long start = this.querystats.startUpdate();
    ((SQLQueryFactory)this.queryFactory).executeUpdate(stmt, this.connection);
    this.querystats.endUpdate(start, this.histogram);//, this.isMainWorkload);
  }
  
  //-----
  //Delete
  //-----
  public static void deleteQueryDataTask() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(DELETES);
    c.initAdditionalLocalParameters();
    c.deleteData();
  }
  
  private void deleteData() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    switch(this.queryAPI) {
      case QueryPrms.GFXD:
        deleteQueryData();
        break;
      default:
        unsupported();
    }
  }
  
  private void deleteQueryData() throws SQLException {
    int queryType = this.queryFactory.getDeleteQueryType();
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      begin();
      deleteQueryData(key, queryType);
      commit();
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
  }

  private void deleteQueryData(int i, int queryType) throws SQLException {
    String stmt = this.queryFactory.getQuery(queryType, i);
    long start = this.querystats.startUpdate();
    ((SQLQueryFactory) this.queryFactory).executeUpdate(stmt, this.connection);
    this.querystats.endUpdate(start, this.histogram);//, this.isMainWorkload);
  }
  
  //----------------
  //prepared delete
  //----------------
  public static void preparedDeleteQueryDataTask() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(DELETES);
    c.initAdditionalLocalParameters();
    c.preparedDeleteData();
  }

  private void preparedDeleteData() throws SQLException,
  NameResolutionException, TypeMismatchException, FunctionDomainException,
  QueryInvocationTargetException {
    switch(this.queryAPI) {
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB: 
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        preparedDeleteQueryData();
        break;
      default:
        unsupported();
    }
  }
 
  private void preparedDeleteQueryData() throws SQLException {
    int queryType = this.queryFactory.getDeleteQueryType();
    String stmt = this.queryFactory.getPreparedQuery(queryType);
    PreparedStatement pstmt = connection.prepareStatement(stmt);
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      begin();
      preparedDeleteQueryData(key, queryType, pstmt, stmt);
      commit();
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());

    pstmt.close();
  }

  private void preparedDeleteQueryData(int i, int queryType,
      PreparedStatement pstmt, String stmt) throws SQLException {
    long start = this.querystats.startUpdate();
    switch (this.queryAPI) {
      case QueryPrms.GFE_GFK:
      case QueryPrms.GFE_GFK_DVD:
      case QueryPrms.GFE:
        noop();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB: 
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        ((SQLQueryFactory) this.queryFactory)
            .fillAndExecuteUpdatePreparedQueryStatement(pstmt, stmt, queryType, i);
        break;
      default:
        unsupported();
    }
    this.querystats.endUpdate(start, this.histogram);//, this.isMainWorkload);

  }
  
  //----------------------------------------------------------------------------
  // queryQueryDataTask
  //----------------------------------------------------------------------------

  public static void queryQueryDataTask() throws SQLException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(QUERIES);
    c.initAdditionalLocalParameters();
    c.queryQueryData();
  }

  private void queryQueryData() throws SQLException {
    int queryType = this.queryFactory.getQueryType();
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      queryQueryData(key, queryType);
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
  }

  private void queryQueryData(int i, int queryType) throws SQLException {
    String stmt = this.queryFactory.getQuery(queryType, i);
    ResultSet rs = null;
    int rss = 0;
    long start = this.querystats.startQuery();
    switch (this.queryAPI) {
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        rs =
            ((SQLQueryFactory) this.queryFactory)
                .execute(stmt, this.connection);
        rss = ((SQLQueryFactory)this.queryFactory).readResultSet(queryType, rs);
        break;
      default:
        unsupported();
    }
    Statement s = rs.getStatement();
    rs.close();
    s.close();
    this.querystats.endQuery(start, rss, this.histogram);
  }

  //----------------------------------------------------------------------------
  // preparedQueryQueryDataTask
  //----------------------------------------------------------------------------
  public static void preparedQueryQueryDataTask() throws SQLException,
      NameResolutionException, TypeMismatchException, FunctionDomainException,
      QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(QUERIES);
    c.initAdditionalLocalParameters();
    c.preparedQueryQueryData();
  }

  private void preparedQueryQueryData() throws SQLException,
      NameResolutionException, TypeMismatchException, FunctionDomainException,
      QueryInvocationTargetException {
    switch (this.queryAPI) {
      case QueryPrms.OQL:
        preparedOQLQueryQueryData();
        break;
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        preparedSQLQueryQueryData();
        break;
      default:
        unsupported();
    }
  }

  private void preparedOQLQueryQueryData() throws NameResolutionException,
      TypeMismatchException, FunctionDomainException,
      QueryInvocationTargetException {
    int queryType = this.queryFactory.getQueryType();
    String stmt = this.queryFactory.getPreparedQuery(queryType);
    //PreparedStatement pstmt = connection.prepareStatement(stmt);
    //Query query = ((OQLQueryFactory) queryFactory).prepareOQLStatement(stmt);
    
    Query query = PoolHelper.getPool("clientPool").getQueryService().newQuery(stmt);
    //= cache.getQueryService().newQuery(stmt);
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      preparedOQLQueryQueryData(key, queryType, query);
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());

    //pstmt.close();
  }

  private void preparedOQLQueryQueryData(int i, int queryType, Query query)
      throws NameResolutionException, TypeMismatchException,
      FunctionDomainException, QueryInvocationTargetException {
    Object rs = null;
    long start = this.querystats.startQuery();
    switch (this.queryAPI) {
      case QueryPrms.OQL:
        rs =
            ((OQLQueryFactory) this.queryFactory)
                .fillAndExecutePreparedQueryStatement(query, queryType, i);
        ((OQLQueryFactory) this.queryFactory).readResultSet(queryType, rs);
        break;
      default:
        String s = "Unsupported API: " + QueryPrms.getAPIString(this.queryAPI);
        throw new HydraConfigException(s);
    }
    this.querystats.endQuery(start, this.histogram);//, this.isMainWorkload);
    //rs.close();
  }

  private void preparedSQLQueryQueryData() throws SQLException {
    int queryType = this.queryFactory.getQueryType();
    String stmt = this.queryFactory.getPreparedQuery(queryType);
    PreparedStatement pstmt = connection.prepareStatement(stmt);
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      preparedSQLQueryQueryData(key, queryType, pstmt, stmt);
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());

    pstmt.close();
  }

  private void preparedSQLQueryQueryData(int i, int queryType,
      PreparedStatement pstmt, String stmt) throws SQLException {
    ResultSet rs = null;
    int rss = 0;
    long start = this.querystats.startQuery();
    switch (this.queryAPI) {
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        rs = ((SQLQueryFactory) this.queryFactory)
                 .fillAndExecutePreparedQueryStatement(pstmt, stmt, queryType, i);
        rss = ((SQLQueryFactory)this.queryFactory).readResultSet(queryType, rs);
        break;
      default:
        unsupported();
    }
    this.querystats.endQuery(start, rss, this.histogram);
    rs.close();
  }
  
  //----------------------------------------------------------------------------
  // preparedMixQueryQueryDataTask
  //----------------------------------------------------------------------------
  public static void preparedMixQueryQueryDataTask() throws SQLException,
      NameResolutionException, TypeMismatchException, FunctionDomainException,
      QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(QUERIES);
    c.initAdditionalLocalParameters();
    c.preparedMixQueryQueryData();
  }

  private void preparedMixQueryQueryData() throws SQLException,
      NameResolutionException, TypeMismatchException, FunctionDomainException,
      QueryInvocationTargetException {
    switch (this.queryAPI) {
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB: 
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        preparedSQLMixQueryQueryData();
        break;
      default:
        unsupported();
    }
  }

  private void preparedSQLMixQueryQueryData() throws SQLException {
    Map<Integer, PreparedStatement> pstmts = new HashMap<Integer, PreparedStatement>();
    do {
      int queryType = this.queryFactory.getQueryType();
      PreparedStatement pstmt = pstmts.get(queryType);
      String stmt = null;
      if (pstmt == null) {
        stmt = this.queryFactory.getPreparedQuery(queryType);
        pstmt = connection.prepareStatement(stmt);
        pstmts.put(queryType, pstmt);
      }
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      preparedSQLQueryQueryData(key, queryType, pstmt, stmt);
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());

    Iterator<PreparedStatement> valueIterator = pstmts.values().iterator();
    while (valueIterator.hasNext()) {
      PreparedStatement pstmt = valueIterator.next();
      pstmt.close();
    }
  }
  
  //----------------------------------------------------------------------------
  // preparedMixQueryQueryDataTask
  //----------------------------------------------------------------------------
  public static void preparedMixUpdateAndQueryQueryDataTask() throws SQLException,
      NameResolutionException, TypeMismatchException, FunctionDomainException,
      QueryInvocationTargetException {
    QueryPerfClient c = new QueryPerfClient();
    c.initialize(QUERIES);
    c.initAdditionalLocalParameters();
    c.preparedMixUpdateAndQueryQueryData();
  }

  private void preparedMixUpdateAndQueryQueryData() throws SQLException,
    NameResolutionException, TypeMismatchException, FunctionDomainException,
    QueryInvocationTargetException {
    switch (this.queryAPI) {
      case QueryPrms.MYSQL:
      case QueryPrms.MYSQLC:
      case QueryPrms.ORACLE:
      case QueryPrms.GPDB:  
      case QueryPrms.RTE:
      case QueryPrms.GFXD:
        preparedSQLMixUpdateAndQueryQueryData();
        break;
      default:
        unsupported();
    }
  }
  
  private void preparedSQLMixUpdateAndQueryQueryData() throws SQLException {
    int queryType = this.queryFactory.getQueryType();
    String stmtQuery = this.queryFactory.getPreparedQuery(queryType);
    PreparedStatement pstmtQuery = connection.prepareStatement(stmtQuery);
    
    int updateType = this.queryFactory.getUpdateQueryType();
    String stmtUpdate = this.queryFactory.getPreparedQuery(updateType);
    PreparedStatement pstmtUpdate = connection.prepareStatement(stmtUpdate);
    
    int updatePercentage = QueryPerfPrms.getUpdatePercentage();
    do {
      int key = getNextKey();
      executeTaskTerminator();
      executeWarmupTerminator();
      enableQueryPlanGeneration();
      
      int n = this.rng.nextInt( 1, 100 );
      if ( n <= updatePercentage ) {
        begin();
        preparedUpdateQueryData(key, updateType, pstmtUpdate, stmtUpdate);
        commit();
      }
      else {       
        preparedSQLQueryQueryData(key,queryType, pstmtQuery, stmtQuery);
      }

      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount;
    } while (!executeBatchTerminator());
    
  }
  
  //----------------------------------------------------------------------------
  // openStatisticsTask and closeStatisticsTask
  //----------------------------------------------------------------------------

  /**
   *  TASK to register the query performance statistics object.
   */
  public static void openStatisticsTask() {
    QueryPerfClient c = new QueryPerfClient();
    c.initHydraThreadLocals();
    c.openStatistics();
    c.updateHydraThreadLocals();
  }

  private void openStatistics() {
    if (this.querystats == null) {
      this.querystats = QueryPerfStats.getInstance();
      RemoteTestModule.openClockSkewStatistics();
    }
  }

  /**
   *  TASK to unregister the performance statistics object.
   */
  public static void closeStatisticsTask() {
    QueryPerfClient c = new QueryPerfClient();
    c.initHydraThreadLocals();
    c.closeStatistics();
    c.updateHydraThreadLocals();
  }

  protected void closeStatistics() {
    MasterController.sleepForMs(2000);
    if (this.querystats != null) {
      RemoteTestModule.closeClockSkewStatistics();
      this.querystats.close();
    }
  }

  //----------------------------------------------------------------------------
  // debug tasks
  //----------------------------------------------------------------------------

  public static void configureDebuggingTask() {
    QueryPerfClient c = new QueryPerfClient();
    c.initLocalVariables(-1);
    if (c.jid == 0) {
      // set sanity manager trace flags first
      List<String> flags = QueryPerfPrms.getTraceFlags();
      if (flags != null) {
        Log.getLogWriter().info("Setting trace flags: " + flags);
        for (String flag : flags) {
          SanityManager.DEBUG_SET(flag);
          Log.getLogWriter().info("Set trace flag: " + flag);
        }
      }

      // set system properties last
      Map<String,String> props = QueryPerfPrms.getSystemProperties();
      if (props != null) {
        Log.getLogWriter().info("Setting system properties: " + props);
        for (String prop : props.keySet()) {
          System.setProperty(prop, props.get(prop));
          Log.getLogWriter().info("Set system property: " + prop + " " + props.get(prop));
        }
      }
    }
  }

  //----------------------------------------------------------------------------
  // Helper methods
  //----------------------------------------------------------------------------

  private void noop() {
    String s = "This operation is a noop for query API: "
             + QueryPrms.getAPIString(this.queryAPI);
    Log.getLogWriter().info(s);
  }

  protected void unsupported() {
    String s = "This operation is not supported for query API: "
             + QueryPrms.getAPIString(this.queryAPI);
    throw new HydraConfigException(s);
  }

  private List convertStmtsToPStmts(List stmts, Connection connection)
      throws SQLException {
    List pstmts = new ArrayList();
    for (int i = 0; i < stmts.size(); i++) {
      if (stmts.get(i) instanceof String) {
        String stmt = (String) stmts.get(i);
        PreparedStatement pstmt = connection.prepareStatement(stmt);
        pstmts.add(pstmt);
      }
      else if (stmts.get(i) instanceof List) {
        pstmts.add(convertStmtsToPStmts((List) stmts.get(i), connection));
      }
      else {
        String s =
            "Elements must be a statement or a list of statements.  The supplied element is of type "
                + stmts.get(i).getClass();
        throw new HydraConfigException(s);
      }
    }
    return pstmts;
  }

  //----------------------------------------------------------------------------
  // Hydra thread locals and their instance field counterparts
  //----------------------------------------------------------------------------

  protected Connection connection;
  public QueryPerfStats querystats;
  private long lastQueryPlanTime;

  private static HydraThreadLocal localconnection = new HydraThreadLocal();
  private static HydraThreadLocal localquerystats = new HydraThreadLocal();
  private static HydraThreadLocal locallastqueryplantime = new HydraThreadLocal();

  protected void initHydraThreadLocals() {
    super.initHydraThreadLocals();
    this.connection = getConnection();
    this.querystats = getQueryStats();
    this.lastQueryPlanTime = getLastQueryPlanTime();
  }

  protected void updateHydraThreadLocals() {
    super.updateHydraThreadLocals();
    setConnection(this.connection);
    setQueryStats(this.querystats);
    setLastQueryPlanTime(this.lastQueryPlanTime);
  }

  protected void resetHydraThreadLocals(boolean resetKeys) {
    super.resetHydraThreadLocals(resetKeys);
    locallastqueryplantime.set(null);
  }

  /**
   *  Gets the per-thread QueryStats wrapper instance.
   */
  protected QueryPerfStats getQueryStats() {
    QueryPerfStats querystats = (QueryPerfStats) localquerystats.get();
    return querystats;
  }

  /**
   *  Sets the per-thread QueryStats wrapper instance.
   */
  protected void setQueryStats(QueryPerfStats querystats) {
    localquerystats.set(querystats);
  }

  /**
   * Gets the per-thread Connection instance.
   */
  protected Connection getConnection() {
    Connection connection = (Connection) localconnection.get();
    return connection;
  }

  /** 
   * Sets the per-thread Connection instance.
   */
  protected void setConnection(Connection connection) {
    localconnection.set(connection);
  }

  /**
   * Gets the per-thread last query plan time.
   */
  protected long getLastQueryPlanTime() {
    Long t = (Long)locallastqueryplantime.get();
    return t == null ? getStartTime() : t;
  }

  /**
   * Sets the per-thread last query plan time.
   */
  protected void setLastQueryPlanTime(long t) {
    locallastqueryplantime.set(t);
  }

  //----------------------------------------------------------------------------
  // Overridden methods
  //----------------------------------------------------------------------------

  /** Updated at the beginning of tasks that invoke initialize(...). */
  protected void initLocalParameters() {
    super.initLocalParameters();
    this.queryAPI = QueryPrms.getAPI();
    this.txIsolation = QueryPerfPrms.getTxIsolation();
    this.queryPlanFrequency = QueryPerfPrms.getQueryPlanFrequency() * 1000;
    this.maxQueryPlanners = QueryPerfPrms.getMaxQueryPlanners();
    this.iterationsSinceTxEnd = 1; // to avoid reset in CachePerfClient.getNextKey()
  }

  /** Updated at the beginning of tasks that invoke initialize(...). */
  protected void initAdditionalLocalParameters() {
    this.queryObjectType = QueryPrms.getObjectType();
    this.queryFactory =
        QueryHelper.getQueryFactory(this.queryAPI, this.queryObjectType);
  }

  protected String nameFor(int name) {
      switch (name) {
        case QUERIES:
          return QUERY_NAME;
        case CREATES:
          return CREATE_NAME;
        case UPDATES:
          return UPDATES_NAME;
        case DELETES:
          return DELETES_NAME;
      }
      return super.nameFor(name);
  }

  protected boolean timeToExecuteBatchTerminator() {
    // @todo lises croak if there is no batch terminator
    if (this.batchTerminator != null) {
      Object o = executeTerminator(this.batchTerminator);
      if (((Boolean)o).booleanValue()) {
        return true;
      }
    }
    return false;
  }

  protected void terminateBatch() {
    cacheEndTrim(this.trimIntervals, this.trimInterval);
    updateHydraThreadLocals();
    DistributedSystem.releaseThreadsSockets();
  }

  protected void terminateWarmup() {
    this.warmedUp = true;
    this.warmupCount = this.count;
    this.warmupTime = System.currentTimeMillis() - this.startTime;
    sync();
    cacheStartTrim( this.trimIntervals, this.trimInterval );
  }

  protected void terminateTask() {
    if ( this.warmupTerminator != null && ! this.warmedUp ) {
      String s = "Task terminator " + this.taskTerminator
               + " ran before warmup terminator " + this.warmupTerminator;
      throw new HydraConfigException(s);
    }
    cacheEndTrim( this.trimIntervals, this.trimInterval );
    updateHydraThreadLocals();
    throw new StopSchedulingTaskOnClientOrder();
  }

  // invoked by GFE API types only
  protected CacheTransactionManager getTxMgr(Cache c) {
    return this.txIsolation != QueryPerfPrms.TRANSACTION_NONE
      ? (CacheTransactionManager)c.getCacheTransactionManager()
      : null;
  }

  // noop unless transaction-enabled GFE API type
  protected void begin() {
    if (this.tm != null) {
      this.tm.begin();
    }
  }

  protected void commit() {
    if (this.txIsolation != QueryPerfPrms.TRANSACTION_NONE) {
      long start = this.querystats.startCommit();
      if (this.connection != null) { // SQL API type
        try {
          this.connection.commit();
        } catch (SQLException e) {
          throw new QueryPerfException("Commit failed: " + e);
        }
      } else if (this.tm != null) { // GFE API type
        try {
          this.tm.commit();
        } catch (TransactionException e) {
          throw new QueryPerfException("Commit failed: " + e);
        }
      } else {
        String s = "Don't know how to commit using query api: "
                 + QueryPrms.getAPIString(this.queryAPI);
        throw new QueryPerfException(s);
      }
      this.querystats.endCommit(start);
    }
  }
}
