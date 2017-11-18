/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package cacheperf.comparisons.gemfirexd.tpcc;

import cacheperf.CachePerfPrms;
import cacheperf.comparisons.gemfirexd.QueryPerfClient;
import cacheperf.comparisons.gemfirexd.QueryPerfException;
import cacheperf.comparisons.gemfirexd.QueryPerfPrms;
import cacheperf.comparisons.gemfirexd.QueryUtil;
import cacheperf.comparisons.gemfirexd.tpcc.TPCCStats.Stmt;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueStats;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderStats;
import hdfs.HDFSPrms;
import hydra.BasePrms;
import hydra.ConfigPrms;
import hydra.DistributedSystemHelper;
import hydra.EnvHelper;
import hydra.HadoopDescription;
import hydra.HadoopHelper;
import hydra.HostDescription;
import hydra.FileUtil;
import hydra.HydraConfigException;
import hydra.HydraRuntimeException;
import hydra.HydraTimeoutException;
import hydra.MasterController;
import hydra.HydraThreadLocal;
import hydra.Log;
import hydra.Prms;
import hydra.ProcessMgr;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import hydra.blackboard.SharedLock;
import hydra.blackboard.SharedMap;
import hydra.gemfirexd.GatewayReceiverHelper;
import hydra.gemfirexd.GatewaySenderHelper;
import hydra.gemfirexd.GatewaySenderPrms;
import hydra.gemfirexd.GfxdConfigPrms;
import hydra.gemfirexd.HDFSStoreDescription;
import hydra.gemfirexd.HDFSStoreHelper;
import hydra.gemfirexd.NetworkServerHelper;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.rmi.RemoteException;
import java.sql.BatchUpdateException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import objects.query.QueryPrms;
import objects.query.tpcc.*;
import sql.SQLHelper;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import util.TestHelper;

public class TPCCClient extends QueryPerfClient implements TxTypes {

  protected static final String CONFLICT_STATE = "X0Z02";
  protected static final String DEADLOCK_STATE = "ORA-00060"; // oracle table lock
  protected static final String DUPLICATE_STR = "The statement was aborted because it would have caused a duplicate key value";
  protected static final String QUERY_HDFS_STR = " --gemfirexd-properties queryHDFS=true \n";

  protected static final boolean allowRemoteSuppliers = TPCCPrms.allowRemoteSuppliers();
  protected static final boolean omitPayUpdateDist = TPCCPrms.omitPayUpdateDist();
  protected static final boolean omitPayUpdateWhse = TPCCPrms.omitPayUpdateWhse();
  protected static final boolean optimizeDelivGetOrderId = TPCCPrms.optimizeDelivGetOrderId();
  protected static final boolean readAllDelivGetOrderId = TPCCPrms.readAllDelivGetOrderId();
  protected static final boolean timeStmts = TPCCPrms.timeStmts();
  protected static final boolean useExistingData = QueryPerfPrms.useExistingData();
  protected static final boolean usePutDML = TPCCPrms.usePutDML();
  protected static final String insertOp= TPCCPrms.usePutDML() ? "PUT" : "INSERT";
  protected static List<Integer> warehouseIDs; // unique warehouse IDs for this site

  // trim intervals
  protected static final int WAREHOUSE    = 1480001;
  protected static final int ITEM         = 1480002;
  protected static final int STOCK        = 1480003;
  protected static final int DISTRICT     = 1480004;
  protected static final int CUSTOMER     = 1480005;
  protected static final int ORDER        = 1480006;
  protected static final int TRANSACTIONS = 1480007;
  protected static final int TRANSACTIONS_A = 1480008;
  protected static final int TRANSACTIONS_B = 1480009;
  protected static final int TRANSACTIONS_C = 1480010;

  protected static final String WAREHOUSE_NAME    = "warehouse";
  protected static final String ITEM_NAME         = "item";
  protected static final String STOCK_NAME        = "stock";
  protected static final String DISTRICT_NAME     = "district";
  protected static final String CUSTOMER_NAME     = "customer";
  protected static final String ORDER_NAME        = "order";
  protected static final String TRANSACTIONS_NAME = "transactions";
  protected static final String TRANSACTIONS_A_NAME = "transactions_a";
  protected static final String TRANSACTIONS_B_NAME = "transactions_b";
  protected static final String TRANSACTIONS_C_NAME = "transactions_c";

  protected static final long NANOSECOND = 1000000000L;

  // hydra thread locals
  protected static HydraThreadLocal localtpccstats = new HydraThreadLocal();
  protected static HydraThreadLocal localids = new HydraThreadLocal();
  protected static HydraThreadLocal localcards = new HydraThreadLocal();
  protected static HydraThreadLocal localcurrentkeys = new HydraThreadLocal();

  public TPCCStats tpccstats; // statistics
  protected IDs ids; // unique warehouse and district ids for this thread
  protected int[] cards; // transaction card deck for this thread
  protected int[][] currentKeys; // current keys for each warehouse-district for this thread

  protected boolean logQueries;
  protected boolean logQueryResults;

  protected int commitCount; // number of rows per commit (when loading data)
  protected boolean useScrollableResultSets;

  // table sizes
  protected int numWarehouses;
  protected int numItems;
  protected int numDistrictsPerWarehouse;
  protected int numCustomersPerDistrict;

  protected int itemBase;
  protected int customerBase;

  protected ResultSet rs = null;
  protected int result = 0;

    //NewOrder Txn
      protected PreparedStatement stmtGetCustWhse = null;
      protected PreparedStatement stmtGetDist = null;
      protected PreparedStatement stmtInsertNewOrder = null;
      protected PreparedStatement stmtUpdateDist = null;
      protected PreparedStatement stmtInsertOOrder = null;
      protected PreparedStatement stmtGetItem = null;
      protected PreparedStatement stmtGetStock = null;
      protected PreparedStatement stmtUpdateStock = null;
      protected PreparedStatement stmtInsertOrderLine = null;

    //Payment Txn
      protected PreparedStatement payUpdateWhse = null;
      protected PreparedStatement payGetWhse = null;
      protected PreparedStatement payUpdateDist = null;
      protected PreparedStatement payGetDist = null;
      protected PreparedStatement payCountCust = null;
      protected PreparedStatement payCursorCustByName = null;
      protected PreparedStatement payGetCust = null;
      protected PreparedStatement payGetCustCdata = null;
      protected PreparedStatement payUpdateCustBalCdata = null;
      protected PreparedStatement payUpdateCustBal = null;
      protected PreparedStatement payInsertHist = null;

    //Order Status Txn
      protected PreparedStatement ordStatCountCust = null;
      protected PreparedStatement ordStatGetCust = null;
      protected PreparedStatement ordStatGetNewestOrd = null;
      protected PreparedStatement ordStatGetCustBal = null;
      protected PreparedStatement ordStatGetOrder = null;
      protected PreparedStatement ordStatGetOrderLines = null;

    //Delivery Txn
      protected PreparedStatement delivGetOrderId = null;
      protected PreparedStatement delivDeleteNewOrder = null;
      protected PreparedStatement delivGetCustId = null;
      protected PreparedStatement delivUpdateCarrierId = null;
      protected PreparedStatement delivUpdateDeliveryDate = null;
      protected PreparedStatement delivSumOrderAmount = null;
      protected PreparedStatement delivUpdateCustBalDelivCnt = null;

    //Stock Level Txn
      protected PreparedStatement stockGetDistOrderId = null;
      protected PreparedStatement stockGetCountStock = null;

//------------------------------------------------------------------------------
// blackboard initialization task
//------------------------------------------------------------------------------

  /**
   * Initializes the blackboard with warehouse/district ids.
   */
  public static void initializeBlackboardTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initLocalVariables(-1);
    c.initLocalParameters();
    if (c.ttgid == 0) {
      c.initializeBlackboard();
    }
    if (c.jid == 0) {
      c.initializeWarehouseIDs();
    }
  }

  private void initializeWarehouseIDs() {
    warehouseIDs = new ArrayList();
    for (int i = toWanSite(); i <= this.numWarehouses; i+= this.numWanSites) {
      warehouseIDs.add(i);
    }
    Log.getLogWriter().info("Operating on warehouses: " + warehouseIDs);
  }

  private void initializeBlackboard() {
    Log.getLogWriter().info("Using"
       + " numThreads="               + this.numThreads + " using"
       + " numWarehouses="            + this.numWarehouses + " with"
       + " numDistrictsPerWarehouse=" + this.numDistrictsPerWarehouse);

    //if (this.numThreads > this.numWarehouses * this.numDistrictsPerWarehouse) {
    //  String s = "There are more client threads than warehouse/district pairs";
    //  throw new HydraConfigException(s);
    //}

    // generate all unique warehouse/district id pairs
    List list = new ArrayList();
    for (int j = 1; j <= this.numDistrictsPerWarehouse; j++) {
      for (int i = 1; i <= this.numWarehouses; i++) {
        list.add(new IDs(i, j));
      }
    }

    // randomize the order
    //Collections.shuffle(list);

    // put them in the map for future lookup
    SharedMap map = TPCCBlackboard.getInstance().getSharedMap();
    for (int i = 0; i < list.size(); i++) {
      map.put(i, list.get(i));
      if (this.logQueries) {
        Log.getLogWriter().info("Added IDs: " + i + "," +  list.get(i));
      }
    }
  }

  /**
   * Reads the blackboard to get warehouse/district ids.
   */
  public static void readBlackboardTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initLocalVariables(-1); // need to set this.ttgid
    c.readBlackboard();
  }

  private void readBlackboard() {
    this.ids = getIDs();
    setIDs(this.ids);
  }

//------------------------------------------------------------------------------
// statistics task
//------------------------------------------------------------------------------

  /**
   *  TASK to register the tpcc performance statistics object.
   */
  public static void openStatisticsTask() {
    TPCCClient c = new TPCCClient();
    c.openStatistics();
  }

  private void openStatistics() {
    this.tpccstats = getTPCCStats();
    if (this.tpccstats == null) {
      this.tpccstats = TPCCStats.getInstance();
      RemoteTestModule.openClockSkewStatistics();
    }
    setTPCCStats(this.tpccstats);
  }
  
  /** 
   *  TASK to unregister the performance statistics object.
   */
  public static void closeStatisticsTask() {
    TPCCClient c = new TPCCClient();
    c.initHydraThreadLocals();
    c.closeStatistics();
    c.updateHydraThreadLocals();
  }
  
  protected void closeStatistics() {
    MasterController.sleepForMs(2000);
    if (this.tpccstats != null) {
      RemoteTestModule.closeClockSkewStatistics();
      this.tpccstats.close();
    }
  }

//------------------------------------------------------------------------------
// wan
//------------------------------------------------------------------------------

  /**
   * Returns the wan site for this JVM based on the hydra logical client name.
   * Assumes that the client name is of the form name_site_vm.
   */
  protected int toWanSite() throws NumberFormatException {
    String clientName = RemoteTestModule.getMyClientName();
    String arr[] = clientName.split("_");
    if (arr.length != 3) {
      return 1;
    }
    try {
      return Integer.parseInt(arr[1]);
    } catch (NumberFormatException e) {
      String s = clientName
               + " is not in the form <name>_<wanSiteNumber>_<itemNumber>";
      throw new HydraRuntimeException(s, e);
    }
  }

  /**
   * Creates the gateway senders for this JVM's wan site using the gateway
   * sender descriptions.  One thread in each wan site creates all senders.
   */
  public static void createGatewaySendersTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.sttgid == 0) {
      c.createGatewaySenders();
    }
  }
  private void createGatewaySenders() throws SQLException {
    int wanSite = toWanSite(); // this is the DSID
    Statement stmt = this.connection.createStatement();
    List<String> ddls = GatewaySenderHelper.getGatewaySenderDDL(wanSite);
    for (String ddl : ddls) {
      Log.getLogWriter().info("Executing " + ddl);
      stmt.execute(ddl);
      Log.getLogWriter().info("Executed " + ddl);
    }
  }

  /**
   * Creates the gateway receivers for this JVM's wan site using the gateway
   * receiver descriptions.  One thread in each wan site creates all receivers.
   */
  public static void createGatewayReceiversTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.sttgid == 0) {
      c.createGatewayReceivers();
    }
  }
  private void createGatewayReceivers() throws SQLException {
    int wanSite = toWanSite(); // this is the DSID
    Statement stmt = this.connection.createStatement();
    List<String> ddls = GatewayReceiverHelper.getGatewayReceiverDDL(wanSite);
    for (String ddl : ddls) {
      Log.getLogWriter().info("Executing " + ddl);
      stmt.execute(ddl);
      Log.getLogWriter().info("Executed " + ddl);
    }
  }

  /**
   * Starts the gateway senders for this JVM's wan site using the gateway
   * sender descriptions.  One thread in each wan site starts all senders.
   */
  public static void startGatewaySendersTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.sttgid == 0) {
      c.startGatewaySenders();
    }
  }
  private void startGatewaySenders() throws SQLException {
    int wanSite = toWanSite(); // this is the DSID
    String procedure = "CALL SYS.START_GATEWAYSENDER(?)";
    CallableStatement call = this.connection.prepareCall(procedure);
    List<String> ids = GatewaySenderHelper.getGatewaySenderIds(wanSite);
    for (String id : ids) {
      Log.getLogWriter().info("Starting gateway sender " + id);
      call.setString(1, id);
      call.execute();
      Log.getLogWriter().info("Started gateway sender " + id);
    }
  }

//------------------------------------------------------------------------------
// create disk stores
//------------------------------------------------------------------------------

  public static void createDiskStoresTask()
  throws FileNotFoundException, IOException, SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.queryAPI == QueryPrms.GFXD && c.sttgid == 0) {
      c.createDiskStores();
    }
  }
  private void createDiskStores()
  throws FileNotFoundException, IOException, SQLException {
    Integer dsfn = TPCCPrms.getDiskStoreFileNum();
    if (dsfn != null) {
      String fn = getDDLFileName("diskstores", dsfn);
      if (FileUtil.exists(fn)) {
        for (String stmt : getDDLStatements(fn)) {
          Log.getLogWriter().info("Creating disk store: " + stmt);
          execute(stmt, this.connection);
          commitDDL();
        }
      } else {
        String s = fn + " not found";
        throw new HydraConfigException(s);
      }
    }
  }

//------------------------------------------------------------------------------
// create hdfs store
//------------------------------------------------------------------------------

  public static void createHdfsStoreTask() {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.queryAPI == QueryPrms.GFXD && c.sttgid == 0) {
      c.createHdfsStore();
    }
  }
  private void createHdfsStore() {
    String stmt = getHDFSStoreDDL();
    Log.getLogWriter().info("Creating hdfs store: " + stmt);
    try {
      execute(stmt, this.connection);
    } catch (SQLException e) {
      throw new QueryPerfException(stmt + " failed " + e);
    }
  }
  private String getHDFSStoreDDL() {
    HadoopDescription hdd = HadoopHelper.getHadoopDescription(ConfigPrms.getHadoopConfig());
    HDFSStoreDescription hsd = HDFSStoreHelper.getHDFSStoreDescription(GfxdConfigPrms.getHDFSStoreConfig());

    String hdfsStoreName = hsd.getName();
    String hdfsDiskStoreName = hsd.getDiskStoreDescription().getName();
    String nameNodeURL = "'" + hdd.getNameNodeURL() + "'";
    String homeDir = "'" + hsd.getHomeDir() + "'";

    StringBuffer buff = new StringBuffer();
    buff.append("CREATE HDFSSTORE ").append(hdfsStoreName)
        .append(" NAMENODE ").append(nameNodeURL)
        .append(" HOMEDIR ").append(homeDir)
        .append(" DISKSTORENAME ").append(hdfsDiskStoreName)
        //.append(" QUEUEPERSISTENT true ")
        //.append(" MAXQUEUEMEMORY 100 ")
        //.append(" BATCHTIMEINTERVAL 5000 ")
        ;

    return buff.toString();
  }

  /** For HDFS tests, we need to add --gemfirexd-properties queryHdfs=true to queries for tables:
   *  customer, history, stock, order, order_line and new_order.
   */
  private String getQueryHDFSProperty() {
    if ((this.queryAPI == QueryPrms.GFXD) && TPCCPrms.useHDFSStorePR()) {
      if (TPCCPrms.queryHDFS()) {
        return QUERY_HDFS_STR;
      }
    }
    return "";
  }

//------------------------------------------------------------------------------
// create tables
//------------------------------------------------------------------------------

  public static void createTablesTask()
  throws FileNotFoundException, IOException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No tables created, using existing data");
      return; // noop
    }
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.sttgid == 0) {
      c.createTables(false);
    }
  }
  public static void createWanTablesTask()
  throws FileNotFoundException, IOException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No tables created, using existing data");
      return; // noop
    }
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.sttgid == 0) {
      c.createTables(true);
    }
  }
  private void createTables(boolean isWanTable)
  throws FileNotFoundException, IOException, SQLException {
    if (this.queryAPI == QueryPrms.GFXD) {
      createHashFunction();
    }
    String gatewaySenderClause = "";
    if (isWanTable && this.queryAPI == QueryPrms.GFXD) {
      int wanSite = toWanSite(); // this is the DSID
      gatewaySenderClause = GatewaySenderHelper.getGatewaySenderClause(
                                GatewaySenderPrms.DEFAULT_SENDER_ID, wanSite);
    }
    String fn = getDDLFileName("tables", TPCCPrms.getTableFileNum());
    List<String> stmts = getDDLStatements(fn);
    for (String stmt : stmts) {
      if (this.queryAPI == QueryPrms.GFXD) {
        if (stmt.contains("partition")) {
          stmt += " buckets " + this.numWarehouses;
          if (TPCCPrms.useOffHeapMemoryPR()) {
            stmt += " offheap";
          }
          if (TPCCPrms.useHDFSStorePR()) {
            String hdfsStoreConfig = GfxdConfigPrms.getHDFSStoreConfig();
            HDFSStoreDescription hsd = HDFSStoreHelper.getHDFSStoreDescription(hdfsStoreConfig);
            stmt += " HDFSSTORE (" + hsd.getName() + ')';
            if (TPCCPrms.useHDFSWriteOnly()) {
              stmt += " WRITEONLY";
            }
          }
        }
        stmt += gatewaySenderClause;
      }
      Log.getLogWriter().info("Creating table: " + stmt);
      execute(stmt, this.connection);
      commitDDL();
    }
  }

  private void createHashFunction() throws SQLException {
    String stmt = "CREATE FUNCTION myhash(value INTEGER) "
                + "RETURNS INTEGER "
                + "PARAMETER STYLE java "
                + "LANGUAGE java "
                + "NO SQL "
                + "EXTERNAL NAME "
                + "'cacheperf.comparisons.gemfirexd.tpcc.TPCCClient.myhash'";
    Statement s = this.connection.createStatement();
    s.execute(stmt);
    s.close();
  }

  public static int myhash(int value) throws SQLException {
    int i = Integer.valueOf(value).hashCode();
    if (QueryPrms.logQueries()) {
      Log.getLogWriter().info("Invoked myhash(" + value + ")=" + i);
    }
    return i;
  }

//------------------------------------------------------------------------------
// load data
//------------------------------------------------------------------------------

  public static void loadWarehouseDataTask()
  throws InterruptedException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No warehouses loaded, using existing data");
      return; // noop
    }
    LoadData c = new LoadData();
    c.initialize(-1);
    c.init();
    c.loadWarehouseData();
  }

  public static void loadItemDataTask()
  throws InterruptedException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No items loaded, using existing data");
      return; // noop
    }
    LoadData c = new LoadData();
    c.initialize(-1);
    c.init();
    c.loadItemData();
  }

  public static void loadStockDataTask()
  throws InterruptedException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No stock loaded, using existing data");
      return; // noop
    }
    LoadData c = new LoadData();
    c.initialize(STOCK);
    c.init();
    c.cacheStartTrim(c.trimIntervals, c.trimInterval);
    c.loadStockData();
    c.cacheEndTrim(c.trimIntervals, c.trimInterval);
  }

  public static void loadDistrictDataTask()
  throws InterruptedException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No districts loaded, using existing data");
      return; // noop
    }
    LoadData c = new LoadData();
    c.initialize(-1);
    c.init();
    c.loadDistrictData();
  }

  public static void loadCustomerDataTask()
  throws InterruptedException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No customers loaded, using existing data");
      return; // noop
    }
    LoadData c = new LoadData();
    c.initialize(CUSTOMER);
    c.init();
    c.cacheStartTrim(c.trimIntervals, c.trimInterval);
    c.loadCustomerData();
    c.cacheEndTrim(c.trimIntervals, c.trimInterval);
  }

  public static void loadOrderDataTask()
  throws InterruptedException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No orders loaded, using existing data");
      return; // noop
    }
    LoadData c = new LoadData();
    c.initialize(ORDER);
    c.init();
    c.cacheStartTrim(c.trimIntervals, c.trimInterval);
    c.loadOrderData();
    c.cacheEndTrim(c.trimIntervals, c.trimInterval);
    c.updateHydraThreadLocals();
  }

//------------------------------------------------------------------------------
// create indexes
//------------------------------------------------------------------------------

  public static void createIndexesOnTablesTask()
  throws FileNotFoundException, IOException, SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No indexes created, using existing data");
      return; // noop
    }
    if (!TPCCPrms.createIndexes()) {
      Log.getLogWriter().info("No indexes created");
      return; // noop
    }
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.sttgid == 0) {
      c.createIndexesOnTables();
    }
  }
  private void createIndexesOnTables()
  throws FileNotFoundException, IOException, SQLException {
    String fn = getDDLFileName("indexes", TPCCPrms.getIndexFileNum());
    List<String> stmts = getDDLStatements(fn);
    for (String stmt : stmts) {
      execute(stmt, this.connection);
      commitDDL();
    }
  }

//------------------------------------------------------------------------------
// drop tables
//------------------------------------------------------------------------------

  public static void dropTablesTask()
  throws SQLException {
    if (useExistingData) {
      Log.getLogWriter().info("No tables dropped, using existing data");
      return; // noop
    }
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.queryAPI != QueryPrms.GFXD && c.sttgid == 0) {
      c.dropTables();
    }
  }
  private void dropTables()
  throws SQLException {
    dropTable("item");
    dropTable("stock");
    dropTable("order_line");
    dropTable("new_order");
    dropTable("oorder");
    dropTable("history");
    dropTable("district");
    dropTable("warehouse");
    dropTable("customer");
  }
  private void dropTable(String table)
  throws SQLException {
    String stmt;
    if (this.queryAPI == QueryPrms.ORACLE) {
      stmt = "drop table " + table;
    } else {
      stmt = "drop table if exists " + table;
    }
    try {
      execute(stmt, this.connection);
    } catch (SQLSyntaxErrorException e) {
      if (e.getMessage().contains("ORA-00942: table or view does not exist")) {
        Log.getLogWriter().info("HEY Table " + table + " does not exist");
      } else {
        Log.getLogWriter().info("HEY Table " + table + " does exist, but got an error anyway");
        throw e;
      }
    }
    commitDDL();
  }

//------------------------------------------------------------------------------
// HDFS (MapReduce) support 
//------------------------------------------------------------------------------

  public static final String HDFS_FLUSH_QUEUE_CALL = "call sys.HDFS_FLUSH_QUEUE(?,?)";
  public static final String HDFS_FORCE_COMPACTION_CALL = "call sys.HDFS_FORCE_COMPACTION(?,?)";

  public static void hdfsFlushAndCompactTask() throws SQLException {
    if (TPCCPrms.useHDFSStorePR()) {
      TPCCClient c = new TPCCClient();
      c.initialize();
      if (c.ttgid == 0) {
        if (TPCCPrms.getHDFSFlushAndCompact()) {
          c.hdfsFlushQueues();
          c.hdfsForceCompaction();
        }
      }
    }
  }


  private void hdfsFlushQueues() throws SQLException {
    final Connection conn = openTmpConnection();
    List<String> tableNames = getPartitionedTables();
    try {
      PreparedStatement ps = conn.prepareStatement(HDFS_FLUSH_QUEUE_CALL);
      for (String tableName : tableNames) {
        ps.setString(1, tableName);
        ps.setInt(2, 0); // wait forever
        ps.execute();
      }
      ps.close();
    } finally {
      closeTmpConnection(conn);
    }
  }

  private void hdfsForceCompaction() throws SQLException {
    final Connection conn = openTmpConnection();
    List<String> tableNames = getPartitionedTables();
    try {
      PreparedStatement ps = conn.prepareStatement(HDFS_FORCE_COMPACTION_CALL);
      for (String tableName : tableNames) {
        ps.setString(1, tableName);
        ps.setInt(2, 0); // wait forever
        ps.execute();
      }
      ps.close();
    } finally {
      closeTmpConnection(conn);
    }
  }

  public static void dumpHDFSCustomerTableTask() {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.queryAPI == QueryPrms.GFXD && c.sttgid == 0) {
      c.dumpHDFSCustomerTable();
    }
  }

  private void dumpHDFSCustomerTable() {
    String mapReduceClassName = HDFSPrms.getMapReduceClassName();

    String hadoopConfig = ConfigPrms.getHadoopConfig();
    HadoopDescription hdd = HadoopHelper.getHadoopDescription(hadoopConfig);
    String confDir = hdd.getResourceManagerDescription().getConfDir();
    HDFSStoreDescription hsd = HDFSStoreHelper.getHDFSStoreDescription(GfxdConfigPrms.getHDFSStoreConfig());
    String hdfsStoreName = hsd.getName();
    String hdfsDiskStoreName = hsd.getDiskStoreDescription().getName();
    String homeDir = hsd.getHomeDir();

    String sep = File.separator;

    int vmid = RemoteTestModule.getMyVmid();
    String clientName = RemoteTestModule.getMyClientName();
    String host = RemoteTestModule.getMyHost();
    HostDescription hd = TestConfig.getInstance().getClientDescription( clientName ).getVmDescription().getHostDescription();

    String jtests = System.getProperty("JTESTS");
    String MRJarPath = jtests + sep + ".." + sep + "extraJars" + sep + "tpcc-mapreduce.jar";
    String gemfirexdJarPath = jtests + sep + ".." + sep + ".." + sep + "product-gfxd" + sep + "lib" + sep + "gemfirexd.jar";
    String gemfirexdClientJarPath = jtests + sep + ".." + sep + ".." + sep + "product-gfxd" + sep + "lib" + sep + "gemfirexd-client.jar";
    //String hbaseJarPath = jtests + sep + ".." + sep + ".." + sep + "product" + sep + "lib" + sep + "hbase-0.94.4-gemfire-r42703.jar";
    String gemfireLibPath = jtests + sep + ".." + sep + ".." + sep + "product" + sep + "lib";
    String hbaseJarPath = getHbaseJar(gemfireLibPath);

    // Use the first locator for this distributed system
    String url = "jdbc:gemfirexd://" + NetworkServerHelper.getNetworkLocatorEndpoints().get(0);
    
    String cmd = "env CLASSPATH=" + System.getProperty( "java.class.path" ) + " ";
    cmd += "env HADOOP_CLASSPATH=" + System.getProperty( "java.class.path" ) + " ";
    cmd += hdd.getHadoopDist() + sep + "bin" + sep + "yarn ";
    cmd += "--config " + confDir + " ";
    cmd += "jar " + MRJarPath + " ";
    cmd += mapReduceClassName + " ";
    cmd += " -libjars " + " " + MRJarPath + "," + gemfirexdJarPath + "," + hbaseJarPath + "," + gemfirexdClientJarPath + " ";
    cmd += homeDir + " ";
    cmd += url + " ";

    String logfn = hd.getUserDir() + sep + "vm_" + vmid + "_" + clientName + "_" + host + "_" + mapReduceClassName + "_" + "CUSTOMER" + "_" + System.currentTimeMillis() + ".log";
    int pid = ProcessMgr.bgexec(host, cmd + "CUSTOMER", hd.getUserDir(), logfn);
    try {
      RemoteTestModule.Master.recordHDFSPIDNoDumps(hd, pid, false);
    } catch (RemoteException e) {
      String s = "Failed to record PID: " + pid;
      throw new HydraRuntimeException(s, e);
    }
    int maxWaitSec = (int)TestConfig.tab().longAt( Prms.maxResultWaitSec );
    if (!ProcessMgr.waitForDeath(host, pid, maxWaitSec)) {
      String s = "Waited more than " + maxWaitSec + " seconds for MapReduce Job";
      throw new HydraTimeoutException(s);
    }
    try {
      RemoteTestModule.Master.removeHDFSPIDNoDumps(hd, pid, false);
    } catch (RemoteException e) {
      Log.getLogWriter().info("execMapReduceJob caught " + e + ": " + TestHelper.getStackTrace(e));
      String s = "Failed to remove PID: " + pid;
      throw new HydraRuntimeException(s, e);
    }

    Log.getLogWriter().info("Completed MapReduce job  on host " + host +
                              " using command: " + cmd +
                              ", see " + logfn + " for output");

    try {
      String stmt = "SELECT warehouseId, districtId, districtBalance FROM hdfsCustomer ORDER BY warehouseId, districtId ASC";
      PreparedStatement myQuery = this.connection.prepareStatement(stmt);
      ResultSet rs = myQuery.executeQuery();
             
      while (rs.next()) {
        int wid = rs.getInt("warehouseId");
        int did = rs.getInt("districtId");
        float d_balance = rs.getFloat("districtBalance");
        Log.getLogWriter().info("hdfsCustomer record : warehouseId = " + wid + " districtId = " + did + " districtBalance = " + d_balance);
      }
      rs.close();
      rs = null;
    } catch (SQLException e) {
      throw new TestException("Unexpected Exception caught in dumpHDFSCustomerTable " + e + TestHelper.getStackTrace(e));
    }

    try {
      String stmt = "SELECT c_id, c_d_id, c_w_id, c_balance FROM customer " + getQueryHDFSProperty() + " WHERE c_balance > 0 ORDER BY c_w_id, c_d_id, c_id ASC";
      PreparedStatement myQuery = this.connection.prepareStatement(stmt);
      ResultSet rs = myQuery.executeQuery();
             
      while (rs.next()) {
        int cid = rs.getInt("c_id");
        int did = rs.getInt("c_d_id");
        int wid = rs.getInt("c_w_id");
        float balance = rs.getFloat("c_balance");
        Log.getLogWriter().info("gfxdCustomer record : " + wid + "-" + did + "-" + cid + " has a balance of " + balance);
      }
      rs.close();
      rs = null;
    } catch (SQLException e) {
      throw new TestException("Unexpected Exception caught in dumpHDFSCustomerTable " + e + TestHelper.getStackTrace(e));
    }
  }

  /** Returns the current hbase*gemfire*.jar file.
   *  Hbase file name format is: 
   *     $GEMFIRE/lib/hbase-X.XX.X-gemfire-rXXXXX.jar           
   */
  private static String getHbaseJar(String gemfireLibPath) {
    class HbaseJarFilter implements FileFilter {
      public boolean accept(File fn) {
       return fn.getName().startsWith("hbase") &&
              fn.getName().contains("gemfire") &&
              fn.getName().endsWith(".jar");
      }
    };
    List<File> hbaseJars = FileUtil.getFiles(new File(gemfireLibPath),
                                             new HbaseJarFilter(),
                                             false);
    if (hbaseJars.size() != 1) {
      throw new TestException("TestException: cannot uniquely identify gemfire/lib/hbase*gemfire*.jar, please check for a change in hbase jar file naming");
    }
    File matchingJarFile = hbaseJars.get(0);
    return matchingJarFile.getAbsolutePath();
  }

//------------------------------------------------------------------------------
// execution support
//------------------------------------------------------------------------------

  private ResultSet execute(String stmt, Connection conn)
  throws SQLException {
    if (this.logQueries) {
      Log.getLogWriter().info("Executing: " + stmt + " on: " + conn);
    } 
    ResultSet rs = null;
    Statement s = conn.createStatement();
    boolean result = s.execute(stmt);
    if (result == true) {
      rs = s.getResultSet();
    }
    if (this.logQueries) {
      Log.getLogWriter().info("Executed: " + stmt + " on: " + conn);
    }
    s.close();
    return rs;
  }

  private void execute(PreparedStatement stmt)
  throws SQLException {
    if (this.logQueries) {
      Log.getLogWriter().info("Executing: " + stmt);
    }
    stmt.execute();
    if (this.logQueries) {
      Log.getLogWriter().info("Executed: " + stmt);
    }
  }

  private String getDDLFileName(String type, int num) {
    String fn = "$JTESTS/cacheperf/comparisons/gemfirexd/tpcc/ddl/" + type + num
              + "." + QueryPrms.getAPIString(this.queryAPI) + ".txt";
    String newfn = EnvHelper.expandEnvVars(fn);
    Log.getLogWriter().info("DDL file: " + fn);
    return newfn;
  }

  private List<String> getDDLStatements(String fn)
  throws FileNotFoundException, IOException {
    Log.getLogWriter().info("Reading statements from " + fn);
    String text = FileUtil.getText(fn).trim();
    StringTokenizer tokenizer = new StringTokenizer(text, ";", false);
    List<String> stmts = new ArrayList();
    while (tokenizer.hasMoreTokens()) {
      String stmt = tokenizer.nextToken().trim();
      stmts.add(stmt);
    }
    Log.getLogWriter().info("Read statements: " + stmts);
    return stmts;
  }

  private void commitDDL() {
    if (this.queryAPI != QueryPrms.GFXD) {
      try {
        this.connection.commit();
      } catch (SQLException e) {
        throw new QueryPerfException("Commit failed: " + e);
      }
    } // GFXD does not need to commit DDL
  }

//------------------------------------------------------------------------------
// TRANSACTION BENCHMARK TASK
//------------------------------------------------------------------------------

  /**
   * The TPCC benchmark task run for a fixed time (unbatched) that is the sum of
   * {@link TPCCPrms#trimSeconds} and {@link TPCCPrms#workSeconds}. It also uses
   * a trim interval read from {@link TPCCPrms#txTrimInterval}, so can be
   * invoked more than once using "transactions_a/b/c". This makes the task
   * suitable for INITTASKs and CLOSETASKs.
   */
  public static void executeTPCCTransactionsFixedTask()
  throws InterruptedException, SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize(intFor(TPCCPrms.getTxTrimInterval()));
    c.executeTPCCTransactionsFixed();
  }
  private void executeTPCCTransactionsFixed()
  throws InterruptedException, SQLException {
    int trimSec = CachePerfPrms.getTrimSeconds();
    int workSec = CachePerfPrms.getWorkSeconds();
    Log.getLogWriter().info("Executing TPCC transactions for " + trimSec
                           + " trim seconds plus " + workSec + " work seconds");
    long current = NanoTimer.getTime();
    long warm = current + trimSec * NANOSECOND;
    long end = warm + workSec * NANOSECOND;
    Log.getLogWriter().info("Times: current=" + current + " warm=" + warm
                           + " end=" + end);
    boolean warmed = false;
    while (true) {
      // shuffle and run the full deck
      jTPCCUtil.shuffle(this.cards, this.rng);
      for (int i = 0; i < this.cards.length; i++) {
        this.tpccstats.setTxCardInProgress(i);
        executeTPCCTransaction(this.cards[i]);
        current = NanoTimer.getTime();
        if (!warmed && current >= warm) {
          cacheStartTrim(this.trimIntervals, this.trimInterval);
          warmed = true;
        }
        if (current >= end) {
          cacheEndTrim(this.trimIntervals, this.trimInterval);
          updateHydraThreadLocals();
          if (!warmed) {
            Log.getLogWriter().warning("Terminating before warmup");
          }
          return;
        }
      }
    }
  }

  /**
   * The TPCC benchmark task.
   */
  public static void executeTPCCTransactionsTask()
  throws InterruptedException, SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize(TRANSACTIONS);
    c.executeTPCCTransactions();
  }
  private void executeTPCCTransactions()
  throws InterruptedException, SQLException {
    int throttleMs = getThrottleMs();
    do {
      executeTaskTerminator(); // does not commit
      executeWarmupTerminator(); // does not commit
      enableQueryPlanGeneration();

      if (this.queryPlanGenerationEnabled) {
        // dump a query plan for each transaction type
        executeTPCCTransaction(NEW_ORDER);
        executeTPCCTransaction(ORDER_STATUS);
        executeTPCCTransaction(PAYMENT);
        executeTPCCTransaction(DELIVERY);
        executeTPCCTransaction(STOCK_LEVEL);
      } else {
        // shuffle and run the full deck
        jTPCCUtil.shuffle(this.cards, this.rng);
        for (int i = 0; i < this.cards.length; i++) {
          this.tpccstats.setTxCardInProgress(i);
          executeTPCCTransaction(this.cards[i]);
          if (throttleMs != 0) {
            Thread.sleep(throttleMs);
          }
        }
      }
      disableQueryPlanGeneration();
      ++this.batchCount;
      ++this.count;
      ++this.keyCount; // not really needed
    } while (!executeBatchTerminator()); // does not commit
  }
  private void executeTPCCTransaction(int transactionType)
  throws SQLException {
    switch (transactionType) {
      case PAYMENT:
        if (this.logQueries) Log.getLogWriter().info("PAYMENT TX");
        this.tpccstats.setTxTypeInProgress(PAYMENT);
        paymentTransaction();
        break;
      case STOCK_LEVEL:
        if (this.logQueries) Log.getLogWriter().info("STOCK LEVEL TX");
        this.tpccstats.setTxTypeInProgress(STOCK_LEVEL);
        stockLevelTransaction();
        break;
      case ORDER_STATUS:
        if (this.logQueries) Log.getLogWriter().info("ORDER STATUS TX");
        this.tpccstats.setTxTypeInProgress(ORDER_STATUS);
        orderStatusTransaction();
        break;
      case DELIVERY:
        if (this.logQueries) Log.getLogWriter().info("DELIVERY TX");
        this.tpccstats.setTxTypeInProgress(DELIVERY);
        deliveryTransaction();
        break;
      case NEW_ORDER:
        if (this.logQueries) Log.getLogWriter().info("NEW ORDER TX");
        this.tpccstats.setTxTypeInProgress(NEW_ORDER);
        newOrderTransaction();
        break;
      default:
        String s = "Should not happen";
        throw new QueryPerfException(s);
    }
  }

//------------------------------------------------------------------------------
// DELIVERY TRANSACTION
//------------------------------------------------------------------------------

  private void deliveryTransaction() throws SQLException {
    int index = this.rng.nextInt(0, warehouseIDs.size() - 1);
    int warehouseID = warehouseIDs.get(index);
    int orderCarrierID = this.rng.nextInt(1, 10);
    deliveryTransaction(warehouseID, orderCarrierID);
  }

  private void deliveryTransaction(int w_id, int o_carrier_id)
  throws SQLException {
    int d_id, no_o_id, c_id;
    float ol_total;
    int[] orderIDs;
    boolean newOrderRemoved;

    long start = this.tpccstats.startTransaction(DELIVERY);
    long stmt_start = 0;
    try {
      orderIDs = new int[10];
      for (d_id = 1; d_id <= 10; d_id++) {
        do {
          no_o_id = -1;
          if (delivGetOrderId == null) {
            String stmt = "SELECT no_o_id FROM";
            if (this.queryAPI == QueryPrms.GFXD) {
              stmt += " --gemfirexd-properties" +
                      " statementAlias=delivGetOrderId \n";
            }
            stmt += " new_order " + 
                    getQueryHDFSProperty() + 
                    " WHERE no_d_id = ? AND no_w_id = ?" +
                    " ORDER BY no_o_id ASC";
            if (optimizeDelivGetOrderId) {
              switch (this.queryAPI) {
                case QueryPrms.GFXD:
                  stmt += " FETCH FIRST ROW ONLY";
                  break;
                case QueryPrms.MYSQL:
                case QueryPrms.GPDB:
                case QueryPrms.MYSQLC:
                  stmt += " LIMIT 1";
                  break;
                case QueryPrms.ORACLE:
                  stmt = "SELECT no_o_id FROM (SELECT no_o_id FROM new_order" +
                         " WHERE no_d_id = ? AND no_w_id = ?" +
                         " ORDER BY no_o_id ASC) WHERE ROWNUM = 1";
                  break;
                default:
                  unsupported();
              }
            }
            delivGetOrderId = this.connection.prepareStatement(stmt);
          }
          delivGetOrderId.setInt(1, d_id);
          delivGetOrderId.setInt(2, w_id);
          if (this.logQueries) {
            if (optimizeDelivGetOrderId) {
              switch (this.queryAPI) {
                case QueryPrms.GFXD:
                  Log.getLogWriter().info("EXECUTING: " +
                    "SELECT no_o_id FROM new_order" +
                          " WHERE no_d_id = " + d_id +
                          " AND no_w_id = " + w_id +
                          " ORDER BY no_o_id ASC FETCH FIRST ROW ONLY");
                  break;
                case QueryPrms.MYSQL:
                case QueryPrms.MYSQLC:
                case QueryPrms.GPDB:
                  Log.getLogWriter().info("EXECUTING: " +
                    "SELECT no_o_id FROM new_order" +
                          " WHERE no_d_id = " + d_id +
                          " AND no_w_id = " + w_id +
                          " ORDER BY no_o_id ASC LIMIT 1");
                  break;
                case QueryPrms.ORACLE:
                  Log.getLogWriter().info("EXECUTING: " +
                    "SELECT no_o_id FROM (SELECT no_o_id FROM new_order" +
                          " WHERE no_d_id = " + d_id +
                          " AND no_w_id = " + w_id +
                          " ORDER BY no_o_id ASC) WHERE ROWNUM = 1");
                  break;
                default:
                  unsupported();
              }
            } else {
              Log.getLogWriter().info("EXECUTING: " +
                "SELECT no_o_id FROM new_order" +
                      " WHERE no_d_id = " + d_id +
                      " AND no_w_id = " + w_id +
                      " ORDER BY no_o_id ASC");
            }
          }
          if (this.timeStmts) {
            stmt_start = this.tpccstats.startStmt(Stmt.delivGetOrderId);
          }
          rs = delivGetOrderId.executeQuery();
          if (rs.next()) no_o_id = rs.getInt("no_o_id");
          if (this.timeStmts) {
            this.tpccstats.endStmt(Stmt.delivGetOrderId, stmt_start);
          }
          if (this.logQueryResults) Log.getLogWriter().info("no_o_id=" + no_o_id);
          orderIDs[d_id-1] = no_o_id;
          rs.close();
          rs = null;

          newOrderRemoved = false;
          if (no_o_id != -1) {
            if (delivDeleteNewOrder == null) {
              delivDeleteNewOrder = this.connection.prepareStatement(
                        "DELETE FROM new_order" +
                        " WHERE no_d_id = ?" +
                        " AND no_w_id = ?" +
                        " AND no_o_id = ?");
            }
            delivDeleteNewOrder.setInt(1, d_id);
            delivDeleteNewOrder.setInt(2, w_id);
            delivDeleteNewOrder.setInt(3, no_o_id);
            if (this.logQueries) {
              Log.getLogWriter().info("EXECUTING: " +
                        "DELETE FROM new_order" +
                        " WHERE no_d_id = " + d_id +
                        " AND no_w_id = " + w_id +
                        " AND no_o_id = " + no_o_id);
            }
            if (this.timeStmts) {
              stmt_start = this.tpccstats.startStmt(Stmt.delivDeleteNewOrder);
            }
            result = delivDeleteNewOrder.executeUpdate();
            if (this.timeStmts) {
              this.tpccstats.endStmt(Stmt.delivDeleteNewOrder, stmt_start);
            }

            if (result > 0) newOrderRemoved = true;
          }
        } while (no_o_id != -1 && !newOrderRemoved);

        if (no_o_id != -1) {
          if (delivGetCustId == null) {
            String stmt = "SELECT o_c_id FROM";
            if (this.queryAPI == QueryPrms.GFXD) {
              stmt += " --gemfirexd-properties statementAlias=delivGetCustId \n";
            }
            stmt += " oorder " + 
                    getQueryHDFSProperty() +
                    " WHERE o_id = ? AND o_d_id = ? AND o_w_id = ?";
            delivGetCustId = this.connection.prepareStatement(stmt);
          }
          delivGetCustId.setInt(1, no_o_id);
          delivGetCustId.setInt(2, d_id);
          delivGetCustId.setInt(3, w_id);
          if (this.logQueries) {
            Log.getLogWriter().info("EXECUTING: " +
              "SELECT o_c_id FROM oorder" +
                      " WHERE o_id = " + no_o_id +
                      " AND o_d_id = " + d_id +
                      " AND o_w_id = " + w_id);
          }
          if (this.timeStmts) {
            stmt_start = this.tpccstats.startStmt(Stmt.delivGetCustId);
          }
          rs = delivGetCustId.executeQuery();
          if (!rs.next()) {
            String s = "o_id=" + no_o_id + " o_d_id=" + d_id
                     + " o_w_id=" + w_id;
            throw new DataNotFoundException(s);
          }
          c_id = rs.getInt("o_c_id");
          if (this.timeStmts) {
            this.tpccstats.endStmt(Stmt.delivGetCustId, stmt_start);
          }
          if (this.logQueryResults) Log.getLogWriter().info("o_c_id=" + c_id);
          rs.close();
          rs = null;

          if (delivUpdateCarrierId == null) {
            delivUpdateCarrierId = this.connection.prepareStatement(
                        "UPDATE oorder SET o_carrier_id = ?" +
                        " WHERE o_id = ?" +
                        " AND o_d_id = ?" +
                        " AND o_w_id = ?");
          }
          delivUpdateCarrierId.setInt(1, o_carrier_id);
          delivUpdateCarrierId.setInt(2, no_o_id);
          delivUpdateCarrierId.setInt(3, d_id);
          delivUpdateCarrierId.setInt(4, w_id);
          if (this.logQueries) {
            Log.getLogWriter().info("EXECUTING: " +
                        "UPDATE oorder SET o_carrier_id = " + o_carrier_id +
                        " WHERE o_id = " + no_o_id +
                        " AND o_d_id = " + d_id +
                        " AND o_w_id = " + w_id);
          }
          if (this.timeStmts) {
            stmt_start = this.tpccstats.startStmt(Stmt.delivUpdateCarrierId);
          }
          result = delivUpdateCarrierId.executeUpdate();
          if (this.timeStmts) {
            this.tpccstats.endStmt(Stmt.delivUpdateCarrierId, stmt_start);
          }
          if (result != 1) {
            String s = "o_id=" + no_o_id + " o_d_id=" + d_id
                     + " o_w_id=" + w_id;
            throw new DataNotFoundException(s);
          }

          if (delivUpdateDeliveryDate == null) {
            delivUpdateDeliveryDate = this.connection.prepareStatement(
                        "UPDATE order_line SET ol_delivery_d = ?" +
                        " WHERE ol_o_id = ?" +
                        " AND ol_d_id = ?" +
                        " AND ol_w_id = ?");
          }
          Timestamp timestamp = new Timestamp(System.currentTimeMillis());
          delivUpdateDeliveryDate.setTimestamp(1, timestamp);
          delivUpdateDeliveryDate.setInt(2,no_o_id);
          delivUpdateDeliveryDate.setInt(3,d_id);
          delivUpdateDeliveryDate.setInt(4,w_id);
          if (this.logQueries) {
            Log.getLogWriter().info("EXECUTING: " +
                        "UPDATE order_line SET ol_delivery_d = " + timestamp +
                        " WHERE ol_o_id = " + no_o_id +
                        " AND ol_d_id = " + d_id +
                        " AND ol_w_id = " + w_id);
          }
          if (this.timeStmts) {
            stmt_start = this.tpccstats.startStmt(Stmt.delivUpdateDeliveryDate);
          }
          result = delivUpdateDeliveryDate.executeUpdate();
          if (this.timeStmts) {
            this.tpccstats.endStmt(Stmt.delivUpdateDeliveryDate, stmt_start);
          }
          if (result == 0) {
            String s = "ol_o_id=" + no_o_id + " ol_d_id=" + d_id
                     + " ol_w_id=" + w_id;
            throw new DataNotFoundException(s);
          }

          if (delivSumOrderAmount == null) {
            String stmt = "SELECT SUM(ol_amount) AS ol_total FROM";
            if (this.queryAPI == QueryPrms.GFXD) {
              stmt += " --gemfirexd-properties" +
                      " statementAlias=delivSumOrderAmount \n";
            }
            stmt += " order_line " + 
                    getQueryHDFSProperty() +
                    " WHERE ol_o_id = ?" +
                    " AND ol_d_id = ? AND ol_w_id = ?";
            delivSumOrderAmount = this.connection.prepareStatement(stmt);
          }
          delivSumOrderAmount.setInt(1, no_o_id);
          delivSumOrderAmount.setInt(2, d_id);
          delivSumOrderAmount.setInt(3, w_id);
          if (this.logQueries) {
            Log.getLogWriter().info("EXECUTING: " +
              "SELECT SUM(ol_amount) AS ol_total FROM order_line" +
                      " WHERE ol_o_id = " + no_o_id +
                      " AND ol_d_id = " + d_id +
                      " AND ol_w_id = " + w_id);
          }
          if (this.timeStmts) {
            stmt_start = this.tpccstats.startStmt(Stmt.delivSumOrderAmount);
          }
          rs = delivSumOrderAmount.executeQuery();
          if (!rs.next()) {
            String s = "ol_o_id=" + no_o_id + " ol_d_id=" + d_id
                     + " ol_w_id=" + w_id;
            throw new DataNotFoundException(s);
          }
          ol_total = rs.getFloat("ol_total");
          if (this.timeStmts) {
            this.tpccstats.endStmt(Stmt.delivSumOrderAmount, stmt_start);
          }
          if (this.logQueryResults) Log.getLogWriter().info("ol_total=" + ol_total);
          rs.close();
          rs = null;

          if (delivUpdateCustBalDelivCnt == null) {
            delivUpdateCustBalDelivCnt = this.connection.prepareStatement(
              "UPDATE customer SET c_balance = c_balance + ?" +
                      ", c_delivery_cnt = c_delivery_cnt + 1" +
                      " WHERE c_id = ?" +
                      " AND c_d_id = ?" +
                      " AND c_w_id = ?");
          }
          delivUpdateCustBalDelivCnt.setFloat(1, ol_total);
          delivUpdateCustBalDelivCnt.setInt(2, c_id);
          delivUpdateCustBalDelivCnt.setInt(3, d_id);
          delivUpdateCustBalDelivCnt.setInt(4, w_id);
          if (this.logQueries) {
            Log.getLogWriter().info("EXECUTING: " +
              "UPDATE customer SET c_balance = c_balance + " + ol_total +
                      ", c_delivery_cnt = c_delivery_cnt + 1" +
                      " WHERE c_id = " + c_id +
                      " AND c_d_id = " + d_id +
                      " AND c_w_id = " + w_id);
          }
          if (this.timeStmts) {
            stmt_start = this.tpccstats.startStmt(Stmt.delivUpdateCustBalDelivCnt);
          }
          result = delivUpdateCustBalDelivCnt.executeUpdate();
          if (this.timeStmts) {
            this.tpccstats.endStmt(Stmt.delivUpdateCustBalDelivCnt, stmt_start);
          }
          if (result == 0) {
            String s = "c_id=" + c_id + " c_w_id=" + w_id
                     + " c_d_id=" + d_id;
            dumpCustomer(c_id, w_id, d_id);
            throw new DataNotFoundException(s);
          }
        }
      }
      boolean committed = commit(DELIVERY, start);
      if (committed) {
        for (int i = 1; i <= 10; i++) {
          if (orderIDs[i-1] < 0) {
            // @todo WRITE STAT ON SKIPPED DELIVERIES skippedDeliveries++;
          }
        }
      }
    } catch (SQLException e) {
      rollbackIfConflict(DELIVERY, start, e);
    }
  }

  private void dumpCustomer(int c_id, int w_id, int d_id)
  throws SQLException {
    String stmt = "SELECT c_balance, c_delivery_cnt, c_last FROM";
    if (this.queryAPI == QueryPrms.GFXD) {
      stmt += " --gemfirexd-properties statementAlias=dumpCustomer \n";
    }
    stmt += " customer " +
            getQueryHDFSProperty() +
            " WHERE c_id = ? AND c_d_id = ? AND c_w_id = ?";
    PreparedStatement pstmt = this.connection.prepareStatement(stmt);
    pstmt.setInt(1, c_id);
    pstmt.setInt(2, d_id);
    pstmt.setInt(3, w_id);
    Log.getLogWriter().severe(
        "DEBUG: SELECT c_balance, c_delivery_cnt, c_last" +
               " FROM customer" +
               " WHERE c_id = " + c_id +
               " AND c_d_id = " + d_id +
               " AND c_w_id = " + w_id);
    String s = "c_id=" + c_id + " c_w_id=" + w_id + " c_d_id=" + d_id;
    ResultSet rs = pstmt.executeQuery();
    if (!rs.next()) {
      Log.getLogWriter().severe("DEBUG: " + s + " does not exist");
    } else {
      float c_balance = rs.getFloat("c_balance");
      int c_delivery_cnt = rs.getInt("c_delivery_cnt");
      String c_last = rs.getString("c_last");
      Log.getLogWriter().severe("DEBUG: Found " + s + " c_last=" + c_last
         + " c_balance=" + c_balance + " c_delivery_cnt=" + c_delivery_cnt);
    }
    rs.close();
    rs = null;
  }

//------------------------------------------------------------------------------
// NEW ORDER TRANSACTION
//------------------------------------------------------------------------------

  private void newOrderTransaction() throws SQLException {
    int index = this.rng.nextInt(0, warehouseIDs.size() - 1);
    int warehouseID = warehouseIDs.get(index);
    int districtID = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
    int customerID = jTPCCUtil.getCustomerID(this.rng,
                                             this.numCustomersPerDistrict);
    int nItems = this.rng.nextInt(5, 15);
    int[] itemIDs = new int[nItems];
    int[] supplierWarehouseIDs = new int[nItems];
    int[] orderQuantities = new int[nItems];
    int allLocal = 1;
    for (int i = 0; i < nItems; i++) {
      itemIDs[i] = jTPCCUtil.getItemID(this.rng, this.numItems);
      if (!allowRemoteSuppliers || this.rng.nextInt(1, 100) > 1) {
        supplierWarehouseIDs[i] = warehouseID;
      } else { // 1% of suppliers are remote
        do {
          int tmp_index = this.rng.nextInt(0, warehouseIDs.size() - 1);
          supplierWarehouseIDs[i] = warehouseIDs.get(tmp_index);
        }
        while (supplierWarehouseIDs[i] == warehouseID && this.numWarehouses > 1);
        allLocal = 0;
      }
      orderQuantities[i] = this.rng.nextInt(1, 10);
    }
    if (this.rng.nextInt(1, 100) == 1) { // roll back 1% of the orders
      itemIDs[nItems-1] = -12345;
    }
    newOrderTransaction(warehouseID, districtID, customerID, nItems, allLocal,
                        itemIDs, supplierWarehouseIDs, orderQuantities);
  }

  private void newOrderTransaction(int w_id, int d_id, int c_id, int o_ol_cnt, int o_all_local, int[] itemIDs, int[] supplierWarehouseIDs, int[] orderQuantities)
  throws SQLException {
    float c_discount, w_tax, d_tax = 0, i_price;
    int d_next_o_id, o_id = -1, s_quantity;
    String c_last = null, c_credit = null, i_name, i_data, s_data;
    String s_dist_01, s_dist_02, s_dist_03, s_dist_04, s_dist_05;
    String s_dist_06, s_dist_07, s_dist_08, s_dist_09, s_dist_10;
    String ol_dist_info = null;
    int ol_supply_w_id, ol_i_id, ol_quantity;
    int s_remote_cnt_increment;
    float ol_amount = 0;
    boolean newOrderRowInserted;

    long start = this.tpccstats.startTransaction(NEW_ORDER);
    long stmt_start = 0;
    try {
      if (stmtGetCustWhse == null) {
        String stmt = "SELECT c_discount, c_last, c_credit, w_tax FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=stmtGetCustWhse \n";
        }
        stmt += " customer " + getQueryHDFSProperty() + ", warehouse " + 
                " WHERE w_id = ?" +
                " AND w_id = c_w_id AND c_d_id = ? AND c_id = ?";
        stmtGetCustWhse = this.connection.prepareStatement(stmt);
      }
      stmtGetCustWhse.setInt(1, w_id);
      stmtGetCustWhse.setInt(2, d_id);
      stmtGetCustWhse.setInt(3, c_id);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT c_discount, c_last, c_credit, w_tax" +
                " FROM customer, warehouse" +
                 " WHERE w_id = " + w_id +
                 " AND w_id = c_w_id AND c_d_id = " + d_id +
                 " AND c_id = " + c_id);
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.stmtGetCustWhse);
      }
      rs = stmtGetCustWhse.executeQuery();
      if (!rs.next()) {
        String s = "w_id=" + w_id + " c_d_id=" + d_id + " c_id=" + c_id;
        throw new DataNotFoundException(s);
      }
      c_discount = rs.getFloat("c_discount");
      c_last = rs.getString("c_last");
      c_credit = rs.getString("c_credit");
      w_tax = rs.getFloat("w_tax");
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.stmtGetCustWhse, stmt_start);
      }
      if (this.logQueryResults) Log.getLogWriter().info("c_discount=" + c_discount
         + "c_last=" + c_last + "c_credit=" + c_credit + "w_tax=" + w_tax);
      rs.close();
      rs = null;

      newOrderRowInserted = false;
      while (!newOrderRowInserted) {
        if (stmtGetDist == null) {
          String stmt = "SELECT d_tax FROM";
          //String stmt = "SELECT d_next_o_id, d_tax FROM" +
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " --gemfirexd-properties statementAlias=stmtGetDist \n";
          }
          stmt += " district WHERE d_id = ? AND d_w_id = ?";
          //stmt += " district WHERE d_id = ? AND d_w_id = ? FOR UPDATE";
          stmtGetDist = this.connection.prepareStatement(stmt);
        }
        stmtGetDist.setInt(1, d_id);
        stmtGetDist.setInt(2, w_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            //"SELECT d_next_o_id, d_tax FROM district" +
            "SELECT d_tax FROM district" +
                " WHERE d_id = " + d_id +
                " AND d_w_id = " + w_id);
                //" AND d_w_id = " + w_id + " FOR UPDATE");
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.stmtGetDist);
        }
        rs = stmtGetDist.executeQuery();
        if (!rs.next()) {
          String s = "d_id=" + d_id + " d_w_id=" + w_id;
          throw new DataNotFoundException(s);
        }
        d_next_o_id = getNextKey(w_id, d_id);
        //d_next_o_id = rs.getInt("d_next_o_id");
        d_tax = rs.getFloat("d_tax");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.stmtGetDist, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("d_next_o_id=" + d_next_o_id
           + " d_tax=" + d_tax);
        rs.close();
        rs = null;
        o_id = d_next_o_id;

        try {
          if (stmtInsertNewOrder == null) {
            stmtInsertNewOrder = this.connection.prepareStatement(
                insertOp + " INTO new_order (no_o_id, no_d_id, no_w_id) " +
                   "VALUES ( ?, ?, ?)");
          }
          stmtInsertNewOrder.setInt(1, o_id);
          stmtInsertNewOrder.setInt(2, d_id);
          stmtInsertNewOrder.setInt(3, w_id);
          if (this.logQueries) {
            Log.getLogWriter().info("EXECUTING: " +
                insertOp + " INTO new_order (no_o_id, no_d_id, no_w_id) " +
                   "VALUES ( " + o_id + ", " + d_id + ", " + w_id + ")");
          }
          if (this.timeStmts) {
            stmt_start = this.tpccstats.startStmt(Stmt.stmtInsertNewOrder);
          }
          stmtInsertNewOrder.executeUpdate();
          if (this.timeStmts) {
            this.tpccstats.endStmt(Stmt.stmtInsertNewOrder, stmt_start);
          }
          newOrderRowInserted = true;
        } catch (SQLException e) {
          try {
            rollbackIfConflict(NEW_ORDER, start, e);
          } catch (SQLException ee) {
            rollbackIfDuplicate(NEW_ORDER, start, ee);
          }
          start = this.tpccstats.startTransaction(NEW_ORDER);
        }
      }
      //if (stmtUpdateDist == null) {
      //  stmtUpdateDist = this.connection.prepareStatement(
      //          "UPDATE district SET d_next_o_id = d_next_o_id + 1 " +
      //          " WHERE d_id = ?" +
      //          " AND d_w_id = ?");
      //}
      //stmtUpdateDist.setInt(1,d_id);
      //stmtUpdateDist.setInt(2,w_id);
      //if (this.logQueries) {
      //  Log.getLogWriter().info("EXECUTING: " +
      //          "UPDATE district SET d_next_o_id = d_next_o_id + 1 " +
      //          " WHERE d_id = " + d_id +
      //          " AND d_w_id = " + w_id);
      //}
      //if (this.timeStmts) {
      //  stmt_start = this.tpccstats.startStmt(Stmt.stmtUpdateDist);
      //}
      //result = stmtUpdateDist.executeUpdate();
      //if (this.timeStmts) {
      //  this.tpccstats.endStmt(Stmt.stmtUpdateDist, stmt_start);
      //}
      //if (result == 0) {
      //  String s = "Cannot update next_order_id on DISTRICT for d_id="
      //           + d_id + " d_w_id=" + w_id;
      //  throw new DataNotFoundException(s);
      //}
      if (stmtInsertOOrder == null) {
        stmtInsertOOrder = this.connection.prepareStatement(
          insertOp + " INTO oorder " +
          " (o_id, o_d_id, o_w_id, o_c_id, o_entry_d, o_ol_cnt, o_all_local)" +
          " VALUES (?, ?, ?, ?, ?, ?, ?)");
      }
      stmtInsertOOrder.setInt(1,o_id);
      stmtInsertOOrder.setInt(2,d_id);
      stmtInsertOOrder.setInt(3,w_id);
      stmtInsertOOrder.setInt(4,c_id);
      Timestamp timestamp = new Timestamp(System.currentTimeMillis());
      stmtInsertOOrder.setTimestamp(5, timestamp);
      stmtInsertOOrder.setInt(6,o_ol_cnt);
      stmtInsertOOrder.setInt(7,o_all_local);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          insertOp + " INTO oorder " +
          " (o_id, o_d_id, o_w_id, o_c_id, o_entry_d, o_ol_cnt, o_all_local)" +
          " VALUES (" + o_id + ", " + d_id + ", " + w_id + ", " + c_id +
                 ", " + timestamp + ", " + o_ol_cnt + ", " + o_all_local + ")");
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.stmtInsertOOrder);
      }
      stmtInsertOOrder.executeUpdate();
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.stmtInsertOOrder, stmt_start);
      }

      for (int ol_number = 1; ol_number <= o_ol_cnt; ol_number++) {
        ol_supply_w_id = supplierWarehouseIDs[ol_number-1];
        ol_i_id = itemIDs[ol_number-1];
        ol_quantity = orderQuantities[ol_number-1];

        if (ol_i_id == -12345) {
          // expected condition generated 1% of the time
          String s = "Expected NEW-ORDER error condition exercising rollback";
          throw new ExpectedException(s);
        }

        if (stmtGetItem == null) {
          String stmt = "SELECT i_price, i_name , i_data FROM";
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " --gemfirexd-properties statementAlias=stmtGetItem \n";
          }
          stmt += " item WHERE i_id = ?";
          stmtGetItem = this.connection.prepareStatement(stmt);
        }
        stmtGetItem.setInt(1, ol_i_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT i_price, i_name , i_data FROM item WHERE i_id = " + ol_i_id);
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.stmtGetItem);
        }
        rs = stmtGetItem.executeQuery();
        if (!rs.next()) {
          String s = "i_id=" + ol_i_id;
          throw new DataNotFoundException(s);
        }
        i_price = rs.getFloat("i_price");
        i_name = rs.getString("i_name");
        i_data = rs.getString("i_data");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.stmtGetItem, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("i_price=" + i_price
           + "i_name=" + i_name + "i_data=" + i_data);
        rs.close();
        rs = null;

        if (stmtGetStock == null) {
          String stmt = "SELECT s_quantity, s_data, s_dist_01, s_dist_02," +
                        " s_dist_03, s_dist_04, s_dist_05,  s_dist_06," +
                        " s_dist_07, s_dist_08, s_dist_09, s_dist_10 FROM";
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " --gemfirexd-properties statementAlias=stmtGetStock \n";
          }
          stmt += " stock " + 
                  getQueryHDFSProperty() + 
                  " WHERE s_i_id = ? AND s_w_id = ? FOR UPDATE";
          stmtGetStock = this.connection.prepareStatement(stmt);
        }
        stmtGetStock.setInt(1, ol_i_id);
        stmtGetStock.setInt(2, ol_supply_w_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT s_quantity, s_data, s_dist_01, s_dist_02, s_dist_03," +
                  " s_dist_04, s_dist_05,  s_dist_06, s_dist_07, s_dist_08," +
                  " s_dist_09, s_dist_10" +
                  " FROM stock WHERE s_i_id = " + ol_i_id +
                  " AND s_w_id = " + ol_supply_w_id + " FOR UPDATE");
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.stmtGetStock);
        }
        rs = stmtGetStock.executeQuery();
        if (!rs.next()) {
          String s = "i_id=" + ol_i_id + " w_id=" + ol_supply_w_id;
          throw new DataNotFoundException(s);
        }
        s_quantity = rs.getInt("s_quantity");
        s_data = rs.getString("s_data");
        s_dist_01 = rs.getString("s_dist_01");
        s_dist_02 = rs.getString("s_dist_02");
        s_dist_03 = rs.getString("s_dist_03");
        s_dist_04 = rs.getString("s_dist_04");
        s_dist_05 = rs.getString("s_dist_05");
        s_dist_06 = rs.getString("s_dist_06");
        s_dist_07 = rs.getString("s_dist_07");
        s_dist_08 = rs.getString("s_dist_08");
        s_dist_09 = rs.getString("s_dist_09");
        s_dist_10 = rs.getString("s_dist_10");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.stmtGetStock, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("s_quantity=" + s_quantity
          + "s_data=" + s_data + "s_dist_01=" + s_dist_01
          + "s_dist_02=" + s_dist_02 + "s_dist_03=" + s_dist_03
          + "s_dist_04=" + s_dist_04 + "s_dist_05=" + s_dist_05
          + "s_dist_06=" + s_dist_06 + "s_dist_07=" + s_dist_07
          + "s_dist_08=" + s_dist_08 + "s_dist_09=" + s_dist_09
          + "s_dist_10=" + s_dist_10);
        rs.close();
        rs = null;

        if (s_quantity - ol_quantity >= 10) {
          s_quantity -= ol_quantity;
        } else {
          s_quantity += -ol_quantity + 91;
        }

        if (ol_supply_w_id == w_id) {
          s_remote_cnt_increment = 0;
        } else {
          s_remote_cnt_increment = 1;
          this.tpccstats.incRemoteSuppliers();
        }

        if (stmtUpdateStock == null) {
          stmtUpdateStock = this.connection.prepareStatement(
             "UPDATE stock SET s_quantity = ? , s_ytd = s_ytd + ?, "
             + "s_remote_cnt = s_remote_cnt + ? "
             + "WHERE s_i_id = ? AND s_w_id = ?");
        }
        stmtUpdateStock.setInt(1, s_quantity);
        stmtUpdateStock.setInt(2, ol_quantity);
        stmtUpdateStock.setInt(3, s_remote_cnt_increment);
        stmtUpdateStock.setInt(4, ol_i_id);
        stmtUpdateStock.setInt(5, ol_supply_w_id);
        stmtUpdateStock.addBatch();
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
             "UPDATE stock SET s_quantity = " + s_quantity
             + " , s_ytd = s_ytd + " + ol_quantity + ", "
             + "s_remote_cnt = s_remote_cnt + " + s_remote_cnt_increment
             + " WHERE s_i_id = " + ol_i_id
             + " AND s_w_id = " + ol_supply_w_id + " [batched]");
        }

        ol_amount = ol_quantity * i_price;
        switch((int)d_id) {
          case 1: ol_dist_info = s_dist_01; break;
          case 2: ol_dist_info = s_dist_02; break;
          case 3: ol_dist_info = s_dist_03; break;
          case 4: ol_dist_info = s_dist_04; break;
          case 5: ol_dist_info = s_dist_05; break;
          case 6: ol_dist_info = s_dist_06; break;
          case 7: ol_dist_info = s_dist_07; break;
          case 8: ol_dist_info = s_dist_08; break;
          case 9: ol_dist_info = s_dist_09; break;
          case 10: ol_dist_info = s_dist_10; break;
          default:
            throw new HydraRuntimeException("Illegal district id: " + d_id);
        }
        if (stmtInsertOrderLine == null) {
          stmtInsertOrderLine = this.connection.prepareStatement(
              insertOp + " INTO order_line (ol_o_id, ol_d_id, ol_w_id, ol_number, "
              + "ol_i_id, ol_supply_w_id, ol_quantity, ol_amount, ol_dist_info)"
              + " VALUES (?,?,?,?,?,?,?,?,?)");
        }
        stmtInsertOrderLine.setInt(1, o_id);
        stmtInsertOrderLine.setInt(2, d_id);
        stmtInsertOrderLine.setInt(3, w_id);
        stmtInsertOrderLine.setInt(4, ol_number);
        stmtInsertOrderLine.setInt(5, ol_i_id);
        stmtInsertOrderLine.setInt(6, ol_supply_w_id);
        stmtInsertOrderLine.setInt(7, ol_quantity);
        stmtInsertOrderLine.setFloat(8, ol_amount);
        stmtInsertOrderLine.setString(9, ol_dist_info);
        stmtInsertOrderLine.addBatch();
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
              insertOp + " INTO order_line (ol_o_id=" + o_id + " ol_d_id=" + d_id + " ol_w_id=" + w_id + " ol_number=" + ol_number + " ol_i_id=" + ol_i_id + " ol_supply_w_id=" + ol_supply_w_id + " ol_quantity=" + ol_quantity + " ol_amount=" + ol_amount + " ol_dist_info=" + ol_dist_info + ") [batched]");
        }
      } // end-for

      if (this.logQueries) {
        Log.getLogWriter().info("Executing " + insertOp + " INTO order_line [batch]");
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.stmtInsertOrderLine);
      }
      stmtInsertOrderLine.executeBatch();
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.stmtInsertOrderLine, stmt_start);
      }
      if (this.logQueries) {
        Log.getLogWriter().info("Executing UPDATE stock [batch]");
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.stmtUpdateStock);
      }
      stmtUpdateStock.executeBatch();
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.stmtUpdateStock, stmt_start);
      }
      commit(NEW_ORDER, start);
      if (o_all_local == 0) this.tpccstats.incDistributedTx();
      stmtInsertOrderLine.clearBatch();
      stmtUpdateStock.clearBatch();
    } catch (ExpectedException e) {
      if (this.logQueries) Log.getLogWriter().info(e.getMessage());
      // no problem, we expected some of this, so just roll back
      abortTransaction(NEW_ORDER, start, e);
    } catch (SQLException e) {
      rollbackIfConflict(NEW_ORDER, start, e);
    } finally {
      if (stmtInsertOrderLine != null) {
        stmtInsertOrderLine.clearBatch();
      }
      if (stmtUpdateStock != null) {
        stmtUpdateStock.clearBatch();
      }
    }
  }

//------------------------------------------------------------------------------
// ORDER STATUS TRANSACTION
//------------------------------------------------------------------------------

  private void orderStatusTransaction() throws SQLException {
    int index = this.rng.nextInt(0, warehouseIDs.size() - 1);
    int warehouseID = warehouseIDs.get(index);
    int districtID = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
    int y = this.rng.nextInt(1, 100);
    String customerLastName = null;
    boolean customerByName;
    int customerID = -1;
    if (y <= 60) {
      customerByName = true;
      customerLastName = jTPCCUtil.getLastName(this.rng);
    } else {
      customerByName = false;
      customerID = jTPCCUtil.getCustomerID(this.rng,
                                           this.numCustomersPerDistrict);
    }
    orderStatusTransaction(warehouseID, districtID, customerID,
                             customerLastName, customerByName);
  }

  private void orderStatusTransaction(int w_id, int d_id, int c_id,
                                      String c_last, boolean c_by_name)
  throws SQLException {
    int namecnt, o_id = -1, o_carrier_id = -1;
    float c_balance;
    String c_first, c_middle;
    Date entdate = null;

    long start = this.tpccstats.startTransaction(ORDER_STATUS);
    long stmt_start = 0;
    try {
    if (c_by_name) {
      if (ordStatCountCust == null) {
        String stmt = "SELECT count(*) AS namecnt FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=ordStatCountCust \n";
        }
        stmt += " customer " + 
                getQueryHDFSProperty() + 
                " WHERE c_last = ? AND c_d_id = ? AND c_w_id = ?";
        ordStatCountCust = this.connection.prepareStatement(stmt);
      }
      ordStatCountCust.setString(1, c_last);
      ordStatCountCust.setInt(2, d_id);
      ordStatCountCust.setInt(3, w_id);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT count(*) AS namecnt FROM customer" +
                " WHERE c_last = " + c_last +
                " AND c_d_id = " + d_id + " AND c_w_id = " + w_id);
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.ordStatCountCust);
      }
      rs = ordStatCountCust.executeQuery();
      if (!rs.next()) {
        String s = "c_last=" + c_last + " c_d_id=" + d_id + " c_w_id=" + w_id;
        throw new DataNotFoundException(s);
      }
      namecnt = rs.getInt("namecnt");
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.ordStatCountCust, stmt_start);
      }
      if (this.logQueryResults) Log.getLogWriter().info("namecnt=" + namecnt);
      rs.close();
      rs = null;

      // pick the middle customer from the list of customers

      if (ordStatGetCust == null) {
        String stmt = "SELECT c_balance, c_first, c_middle, c_id FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=ordStatGetCust \n";
        }
        stmt += " customer " + 
                getQueryHDFSProperty() + 
                " WHERE c_last = ? AND c_d_id = ? AND c_w_id = ?" +
                " ORDER BY c_w_id, c_d_id, c_last, c_first";
        ordStatGetCust = this.connection.prepareStatement(stmt);
      }
      ordStatGetCust.setString(1, c_last);
      ordStatGetCust.setInt(2, d_id);
      ordStatGetCust.setInt(3, w_id);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT c_balance, c_first, c_middle, c_id FROM customer" +
                " WHERE c_last = " + c_last + " AND c_d_id = " + d_id +
                " AND c_w_id = " + w_id +
                " ORDER BY c_w_id, c_d_id, c_last, c_first");
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.ordStatGetCust);
      }
      rs = ordStatGetCust.executeQuery();
      if (!rs.next()) {
        String s = "c_last=" + c_last + " c_d_id=" + d_id + " c_w_id=" + w_id;
        throw new DataNotFoundException(s);
      }
      if (namecnt%2 == 1) namecnt++;
      for (int i = 1; i < namecnt / 2; i++) rs.next();
      c_id = rs.getInt("c_id");
      c_first = rs.getString("c_first");
      c_middle = rs.getString("c_middle");
      c_balance = rs.getFloat("c_balance");
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.ordStatGetCust, stmt_start);
      }
      if (this.logQueryResults) Log.getLogWriter().info("c_id=" + c_id
        + "c_first=" + c_first + "c_middle=" + c_middle
        + "c_balance=" + c_balance);
      rs.close();
      rs = null;
    }
    else {
      if (ordStatGetCustBal == null) {
        String stmt = "SELECT c_balance, c_first, c_middle, c_last FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=ordStatGetCustBal \n";
        }
        stmt += " customer " + 
                getQueryHDFSProperty() + 
                " WHERE c_id = ? AND c_d_id = ? AND c_w_id = ?";
        ordStatGetCustBal = this.connection.prepareStatement(stmt);
      }
      ordStatGetCustBal.setInt(1, c_id);
      ordStatGetCustBal.setInt(2, d_id);
      ordStatGetCustBal.setInt(3, w_id);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT c_balance, c_first, c_middle, c_last" +
                " FROM customer" +
                " WHERE c_id = " + c_id +
                " AND c_d_id = " + d_id +
                " AND c_w_id = " + w_id);
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.ordStatGetCustBal);
      }
      rs = ordStatGetCustBal.executeQuery();
      if (!rs.next()) {
        String s = "c_id=" + c_id + " c_d_id=" + d_id + " c_w_id=" + w_id;
        throw new DataNotFoundException(s);
      }
      c_last = rs.getString("c_last");
      c_first = rs.getString("c_first");
      c_middle = rs.getString("c_middle");
      c_balance = rs.getFloat("c_balance");
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.ordStatGetCustBal, stmt_start);
      }
      if (this.logQueryResults) Log.getLogWriter().info("c_last=" + c_last
        + "c_first=" + c_first + "c_middle=" + c_middle
        + "c_balance=" + c_balance);
      rs.close();
      rs = null;
    }

    // find the newest order for the customer

    if (ordStatGetNewestOrd == null) {
      String stmt = "SELECT MAX(o_id) AS maxorderid FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=ordStatGetNewestOrd \n";
      }
      stmt += " oorder " + 
              getQueryHDFSProperty() + 
              " WHERE o_w_id = ? AND o_d_id = ? AND o_c_id = ?";
      ordStatGetNewestOrd = this.connection.prepareStatement(stmt);
    }
    ordStatGetNewestOrd.setInt(1, w_id);
    ordStatGetNewestOrd.setInt(2, d_id);
    ordStatGetNewestOrd.setInt(3, c_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT MAX(o_id) AS maxorderid FROM oorder" +
              " WHERE o_w_id = " + w_id +
              " AND o_d_id = " + d_id +
              " AND o_c_id = " + c_id);
    }
    if (this.timeStmts) {
      stmt_start = this.tpccstats.startStmt(Stmt.ordStatGetNewestOrd);
    }
    rs = ordStatGetNewestOrd.executeQuery();
    if (rs.next()) {
      o_id = rs.getInt("maxorderid");
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.ordStatGetNewestOrd, stmt_start);
      }
      if (this.logQueryResults) Log.getLogWriter().info("maxorderid=" + o_id);
      rs.close();
      rs = null;

      // retrieve the carrier & order date for the most recent order.

      if (ordStatGetOrder == null) {
        String stmt = "SELECT o_carrier_id, o_entry_d FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=ordStatGetOrder \n";
        }
        stmt += " oorder " + 
                  getQueryHDFSProperty() + 
                " WHERE o_w_id = ? AND o_d_id = ?" +
                " AND o_c_id = ? AND o_id = ?";
        ordStatGetOrder = this.connection.prepareStatement(stmt);
      }
      ordStatGetOrder.setInt(1, w_id);
      ordStatGetOrder.setInt(2, d_id);
      ordStatGetOrder.setInt(3, c_id);
      ordStatGetOrder.setInt(4, o_id);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT o_carrier_id, o_entry_d FROM oorder" +
                " WHERE o_w_id = " + w_id +
                " AND o_d_id = " + d_id +
                " AND o_c_id = " + c_id +
                " AND o_id = " + o_id);
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.ordStatGetOrder);
      }
      rs = ordStatGetOrder.executeQuery();
      if (rs.next()) {
        o_carrier_id = rs.getInt("o_carrier_id");
        entdate = rs.getDate("o_entry_d");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.ordStatGetOrder, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("o_carrier_id=" + o_carrier_id
          + "o_entry_d=" + entdate);
      } else {
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.ordStatGetOrder, stmt_start);
        }
      }
    } else {
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.ordStatGetNewestOrd, stmt_start);
      }
    }
    rs.close();
    rs = null;

    // retrieve the order lines for the most recent order

    if (ordStatGetOrderLines == null) {
      String stmt = "SELECT ol_i_id, ol_supply_w_id, ol_quantity, ol_amount,"
                  + " ol_delivery_d FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=ordStatGetOrderLines \n";
      }
      stmt += " order_line " + 
              getQueryHDFSProperty() + 
              " WHERE ol_o_id = ? AND ol_d_id = ? AND ol_w_id = ?";
      ordStatGetOrderLines = this.connection.prepareStatement(stmt);
    }
    ordStatGetOrderLines.setInt(1, o_id);
    ordStatGetOrderLines.setInt(2, d_id);
    ordStatGetOrderLines.setInt(3, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT ol_i_id, ol_supply_w_id, ol_quantity, ol_amount, ol_delivery_d"
            + " FROM order_line"
            + " WHERE ol_o_id = " + o_id
            + " AND ol_d_id = " + d_id
            + " AND ol_w_id = " + w_id);
    }
    if (this.timeStmts) {
      stmt_start = this.tpccstats.startStmt(Stmt.ordStatGetOrderLines);
    }
    rs = ordStatGetOrderLines.executeQuery();
    while (rs.next()) {
      long ol_supply_w_id = rs.getLong("ol_supply_w_id");
      long ol_i_id = rs.getLong("ol_i_id");
      long ol_quantity = rs.getLong("ol_quantity");
      double ol_amount = rs.getDouble("ol_amount");
      Date ol_delivery_d = rs.getDate("ol_delivery_d");
      if (this.logQueryResults) Log.getLogWriter().info("ol_supply_w_id=" + ol_supply_w_id
        + "ol_i_id=" + ol_i_id + "ol_quantity=" + ol_quantity
        + "ol_amount=" + ol_amount + "ol_delivery_d=" + ol_delivery_d);
    }
    if (this.timeStmts) {
      this.tpccstats.endStmt(Stmt.ordStatGetOrderLines, stmt_start);
    }
    rs.close();
    rs = null;
    this.tpccstats.endTransaction(ORDER_STATUS, start, 0);
    } catch (DataNotFoundException e) {
      this.tpccstats.endTransactionNotFound(ORDER_STATUS, start);
      if (this.logQueries) {
        Log.getLogWriter().info(e.getClass().getName() + " " + e.getMessage());
      }
    }
  }

//------------------------------------------------------------------------------
// PAYMENT TRANSACTION
//------------------------------------------------------------------------------

  private void paymentTransaction() throws SQLException {
    int index = this.rng.nextInt(0, warehouseIDs.size() - 1);
    int warehouseID = warehouseIDs.get(index);
    int districtID = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
    int customerDistrictID;
    int customerWarehouseID;
    if (this.rng.nextInt(1, 100) <= 85) {
      customerDistrictID = districtID;
      customerWarehouseID = warehouseID;
    } else {
      customerDistrictID = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
      do {
        index = this.rng.nextInt(0, warehouseIDs.size() - 1);
        customerWarehouseID = warehouseIDs.get(index);
      }
      while(customerWarehouseID == warehouseID && numWarehouses > 1);
    }

    boolean customerByName;
    String customerLastName = null;
    int customerID = -1;
//  if (this.rng.nextInt(1, 100) <= 60)  {
      // 60% lookups by last name
//    customerByName = true;
//    customerLastName = jTPCCUtil.getLastName(this.rng);
//    printMessage("Last name lookup = " + customerLastName);
//  } else {
//    // 40% lookups by customer ID
      customerByName = false;
      customerID = jTPCCUtil.getCustomerID(this.rng,
                                           this.numCustomersPerDistrict);
//  }

    float paymentAmount = (float)(this.rng.nextInt(100, 500000)/100.0);

    paymentTransaction(warehouseID, customerWarehouseID, paymentAmount,
                       districtID, customerDistrictID, customerID,
                       customerLastName, customerByName);
  }

  private void paymentTransaction(int w_id, int c_w_id, float h_amount, int d_id, int c_d_id, int c_id, String c_last, boolean c_by_name)
  throws SQLException {
    String w_street_1, w_street_2, w_city, w_state, w_zip, w_name;
    String d_street_1, d_street_2, d_city, d_state, d_zip, d_name;
    int namecnt;
    String c_first, c_middle, c_street_1, c_street_2, c_city, c_state, c_zip;
    String c_phone, c_credit = null, c_data = null, c_new_data, h_data;
    float c_credit_lim, c_discount, c_balance = 0;
    Date c_since;

    long start = this.tpccstats.startTransaction(PAYMENT);
    long stmt_start = 0;
    try {
      if (c_by_name) {
        // payment is by customer name
        if (payCountCust == null) {
          String stmt = "SELECT count(c_id) AS namecnt FROM";
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " --gemfirexd-properties statementAlias=payCountCust \n";
          }
          stmt += " customer " + 
                  getQueryHDFSProperty() + 
                  " WHERE c_last = ? AND c_d_id = ? AND c_w_id = ?";
          payCountCust = this.connection.prepareStatement(stmt);
        }
        payCountCust.setString(1, c_last);
        payCountCust.setInt(2, c_d_id);
        payCountCust.setInt(3, c_w_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT count(c_id) AS namecnt FROM customer " +
                  " WHERE c_last = " + c_last +
                  " AND c_d_id = " + c_d_id + " AND c_w_id = " + c_w_id);
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payCountCust);
        }
        rs = payCountCust.executeQuery();
        if (!rs.next()) {
          String s = "c_last=" + c_last + " c_d_id=" + c_d_id
                   + " c_w_id=" + c_w_id;
          throw new DataNotFoundException(s);
        }
        namecnt = rs.getInt("namecnt");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payCountCust, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("namecnt=" + namecnt);
        rs.close();
        rs = null;

        if (payCursorCustByName == null) {
          String stmt = "SELECT c_first, c_middle, c_id, c_street_1," +
                        " c_street_2, c_city, c_state, c_zip, c_phone," +
                        " c_credit, c_credit_lim, c_discount, c_balance," +
                        " c_since FROM";
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " --gemfirexd-properties" +
                    " statementAlias=payCursorCustByName \n";
          }
          stmt += " customer " + 
                  getQueryHDFSProperty() + 
                  " WHERE c_w_id = ? AND c_d_id = ? AND c_last = ?" +
                  " ORDER BY c_w_id, c_d_id, c_last, c_first ";
          payCursorCustByName = this.connection.prepareStatement(stmt);
        }
        payCursorCustByName.setInt(1, c_w_id);
        payCursorCustByName.setInt(2, c_d_id);
        payCursorCustByName.setString(3, c_last);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT c_first, c_middle, c_id, c_street_1, c_street_2, c_city," +
            " c_state, c_zip, c_phone, c_credit, c_credit_lim, c_discount," +
            " c_balance, c_since " +
            "  FROM customer WHERE c_w_id = " + c_w_id +
            " AND c_d_id = " + c_d_id + " AND c_last = " + c_last +
            " ORDER BY c_w_id, c_d_id, c_last, c_first ");
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payCursorCustByName);
        }
        rs = payCursorCustByName.executeQuery();
        if (!rs.next()) {
          String s = "c_last=" + c_last + " c_d_id=" + c_d_id
                   + " c_w_id=" + c_w_id;
          throw new DataNotFoundException(s);
        }
        if (namecnt%2 == 1) namecnt++;
        for (int i = 1; i < namecnt / 2; i++) rs.next();
        c_id = rs.getInt("c_id");
        c_first = rs.getString("c_first");
        c_middle = rs.getString("c_middle");
        c_street_1 = rs.getString("c_street_1");
        c_street_2 = rs.getString("c_street_2");
        c_city = rs.getString("c_city");
        c_state = rs.getString("c_state");
        c_zip = rs.getString("c_zip");
        c_phone = rs.getString("c_phone");
        c_credit = rs.getString("c_credit");
        c_credit_lim = rs.getFloat("c_credit_lim");
        c_discount = rs.getFloat("c_discount");
        c_balance = rs.getFloat("c_balance");
        c_since = rs.getDate("c_since");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payCursorCustByName, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("c_credit_lim=" + c_credit_lim
          + "c_id=" + c_id + "c_first=" + c_first + "c_middle=" + c_middle
          + "c_street_1=" + c_street_1 + "c_street_2=" + c_street_2
          + "c_city=" + c_city + "c_state=" + c_state + "c_zip=" + c_zip
          + "c_phone=" + c_phone + "c_credit=" + c_credit
          + "c_discount=" + c_discount + "c_balance=" + c_balance
          + "c_since=" + c_since);
        rs.close();
        rs = null;
      } else {
        // payment is by customer ID
        if (payGetCust == null) {
          String stmt = "SELECT c_first, c_middle, c_last, c_street_1," +
                        " c_street_2, c_city, c_state, c_zip, c_phone," +
                        " c_credit, c_credit_lim, c_discount, c_balance," +
                        " c_since FROM";
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " --gemfirexd-properties statementAlias=payGetCust \n";
          }
          stmt += " customer " + 
                  getQueryHDFSProperty() + 
                  " WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?";
          payGetCust = this.connection.prepareStatement(stmt);
        }
        payGetCust.setInt(1, c_w_id);
        payGetCust.setInt(2, c_d_id);
        payGetCust.setInt(3, c_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT c_first, c_middle, c_last, c_street_1, c_street_2," +
            " c_city, c_state, c_zip, c_phone, c_credit, c_credit_lim," +
            " c_discount, c_balance, c_since " +
            "  FROM customer WHERE c_w_id = " + c_w_id +
            " AND c_d_id = " + c_d_id + " AND c_id = " + c_id);
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payGetCust);
        }
        rs = payGetCust.executeQuery();
        if (!rs.next()) {
          String s = "c_id=" + c_id + " c_d_id=" + c_d_id + " c_w_id="
                   + c_w_id;
          throw new DataNotFoundException(s);
        }
        c_last = rs.getString("c_last");
        c_first = rs.getString("c_first");
        c_middle = rs.getString("c_middle");
        c_street_1 = rs.getString("c_street_1");
        c_street_2 = rs.getString("c_street_2");
        c_city = rs.getString("c_city");
        c_state = rs.getString("c_state");
        c_zip = rs.getString("c_zip");
        c_phone = rs.getString("c_phone");
        c_credit = rs.getString("c_credit");
        c_credit_lim = rs.getFloat("c_credit_lim");
        c_discount = rs.getFloat("c_discount");
        c_balance = rs.getFloat("c_balance");
        c_since = rs.getDate("c_since");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payGetCust, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("c_last=" + c_last
          + "c_first=" + c_first + "c_middle=" + c_middle
          + "c_street_1=" + c_street_1 + "c_street_2=" + c_street_2
          + "c_city=" + c_city + "c_state=" + c_state + "c_zip=" + c_zip
          + "c_phone=" + c_phone + "c_credit=" + c_credit
          + "c_credit_lim=" + c_credit_lim + "c_discount=" + c_discount
          + "c_balance=" + c_balance + "c_since=" + c_since);
        rs.close();
        rs = null;
      }

      c_balance += h_amount;

      if (c_credit.equals("BC")) {  // bad credit
        if (payGetCustCdata == null) {
          String stmt = "SELECT c_data FROM";
          if (this.queryAPI == QueryPrms.GFXD) {
            stmt += " --gemfirexd-properties statementAlias=payGetCustCdata \n";
          }
          stmt += " customer " + 
                  getQueryHDFSProperty() + 
                  " WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?";
          payGetCustCdata = this.connection.prepareStatement(stmt);
        }
        payGetCustCdata.setInt(1, c_w_id);
        payGetCustCdata.setInt(2, c_d_id);
        payGetCustCdata.setInt(3, c_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "SELECT c_data FROM customer" +
                  " WHERE c_w_id = " + c_w_id +
                  " AND c_d_id = " + c_d_id +
                  " AND c_id = " + c_id);
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payGetCustCdata);
        }
        rs = payGetCustCdata.executeQuery();
        if (!rs.next()) {
          String s = "c_id=" + c_id + " c_w_id=" + c_w_id + " c_d_id="
                   + c_d_id;
          throw new DataNotFoundException(s);
        }
        c_data = rs.getString("c_data");
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payGetCustCdata, stmt_start);
        }
        if (this.logQueryResults) Log.getLogWriter().info("c_data=" + c_data);
        rs.close();
        rs = null;

        c_new_data = c_id + " " + c_d_id + " " + c_w_id + " " + d_id + " "
                   + w_id  + " " + h_amount + " |";
        if (c_data.length() > c_new_data.length()) {
          c_new_data += c_data.substring(0,
                                         c_data.length()-c_new_data.length());
        } else {
          c_new_data += c_data;
        }
        if (c_new_data.length() > 500) {
          c_new_data = c_new_data.substring(0, 500);
        }

        if (payUpdateCustBalCdata == null) {
          payUpdateCustBalCdata = this.connection.prepareStatement(
            "UPDATE customer SET c_balance = ?, c_data = ?" +
                  " WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?");
        }
        payUpdateCustBalCdata.setFloat(1, c_balance);
        payUpdateCustBalCdata.setString(2, c_new_data);
        payUpdateCustBalCdata.setInt(3, c_w_id);
        payUpdateCustBalCdata.setInt(4, c_d_id);
        payUpdateCustBalCdata.setInt(5, c_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "UPDATE customer SET c_balance = " + c_balance +
                  ", c_data = " + c_new_data +
                  " WHERE c_w_id = " + c_w_id +
                  " AND c_d_id = " + c_d_id + " AND c_id = " + c_id);
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payUpdateCustBalCdata);
        }
        result = payUpdateCustBalCdata.executeUpdate();
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payUpdateCustBalCdata, stmt_start);
        }

        if (result == 0) {
          String s = "c_id=" + c_id + " c_w_id=" + c_w_id 
                   + " c_d_id=" + c_d_id;
          throw new DataNotFoundException(s);
        }
      } else { // GoodCredit
        if (payUpdateCustBal == null) {
          payUpdateCustBal = this.connection.prepareStatement(
            "UPDATE customer SET c_balance = ?" +
                  " WHERE c_w_id = ? AND c_d_id = ? AND c_id = ?");
        }
        payUpdateCustBal.setFloat(1, c_balance);
        payUpdateCustBal.setInt(2, c_w_id);
        payUpdateCustBal.setInt(3, c_d_id);
        payUpdateCustBal.setInt(4, c_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "UPDATE customer SET c_balance = " + c_balance +
                  " WHERE c_w_id = " + c_w_id +
                  " AND c_d_id = " + c_d_id + " AND c_id = " + c_id);
        }

        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payUpdateCustBal);
        }
        result = payUpdateCustBal.executeUpdate();
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payUpdateCustBal, stmt_start);
        }

        if (result == 0) {
          String s = "c_id=" + c_id + " c_w_id=" + c_w_id + " c_d_id="
                   + c_d_id;
          throw new DataNotFoundException(s);
        }
      }

      // START WORKAROUND FOR TX REQUIRING PR BEFORE RR

      if (!omitPayUpdateWhse) {
        if (payUpdateWhse == null) {
          payUpdateWhse = this.connection.prepareStatement(
             "UPDATE warehouse SET w_ytd = w_ytd + ? WHERE w_id = ?");
        }
        payUpdateWhse.setFloat(1,h_amount);
        payUpdateWhse.setInt(2,w_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
             "UPDATE warehouse SET w_ytd = w_ytd + " + h_amount +
             " WHERE w_id = " + w_id);
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payUpdateWhse);
        }
        result = payUpdateWhse.executeUpdate();
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payUpdateWhse, stmt_start);
        }
        if (result == 0) {
          String s = "w_id=" + w_id;
          throw new DataNotFoundException(s);
        }
      }

      if (payGetWhse == null) {
        String stmt = "SELECT w_street_1, w_street_2, w_city, w_state," +
                      " w_zip, w_name FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=payGetWhse \n";
        }
        stmt += " warehouse WHERE w_id = ?";
        payGetWhse = this.connection.prepareStatement(stmt);
      }
      payGetWhse.setInt(1, w_id);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT w_street_1, w_street_2, w_city, w_state, w_zip, w_name" +
                " FROM warehouse WHERE w_id = " + w_id);
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.payGetWhse);
      }
      rs = payGetWhse.executeQuery();
      if (!rs.next()) {
        String s = "w_id=" + w_id;
        throw new DataNotFoundException(s);
      }
      w_street_1 = rs.getString("w_street_1");
      w_street_2 = rs.getString("w_street_2");
      w_city = rs.getString("w_city");
      w_state = rs.getString("w_state");
      w_zip = rs.getString("w_zip");
      w_name = rs.getString("w_name");
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.payGetWhse, stmt_start);
      }
      if (this.logQueryResults) Log.getLogWriter().info("w_street_1=" + w_street_1
        + "w_street_2=" + w_street_2 + "w_city=" + w_city
        + "w_state=" + w_state + "w_zip=" + w_zip + "w_name=" + w_name);
      rs.close();
      rs = null;

      if (!omitPayUpdateDist) {
        if (payUpdateDist == null) {
          payUpdateDist = this.connection.prepareStatement(
            "UPDATE district SET d_ytd = d_ytd + ?" +
                    " WHERE d_w_id = ? AND d_id = ?");
        }
        payUpdateDist.setFloat(1, h_amount);
        payUpdateDist.setInt(2, w_id);
        payUpdateDist.setInt(3, d_id);
        if (this.logQueries) {
          Log.getLogWriter().info("EXECUTING: " +
            "UPDATE district SET d_ytd = d_ytd + " + h_amount +
                    " WHERE d_w_id = " + w_id + " AND d_id = " + d_id);
        }
        if (this.timeStmts) {
          stmt_start = this.tpccstats.startStmt(Stmt.payUpdateDist);
        }
        result = payUpdateDist.executeUpdate();
        if (this.timeStmts) {
          this.tpccstats.endStmt(Stmt.payUpdateDist, stmt_start);
        }
        if (result == 0) {
          String s = "d_id=" + d_id + " d_w_id=" + w_id;
          throw new DataNotFoundException(s);
        }
      }

      if (payGetDist == null) {
        String stmt = "SELECT d_street_1, d_street_2, d_city, d_state," +
                      " d_zip, d_name FROM";
        if (this.queryAPI == QueryPrms.GFXD) {
          stmt += " --gemfirexd-properties statementAlias=payGetDist \n";
        }
        stmt += " district WHERE d_w_id = ? AND d_id = ?";
        payGetDist = this.connection.prepareStatement(stmt);
      }
      payGetDist.setInt(1, w_id);
      payGetDist.setInt(2, d_id);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          "SELECT d_street_1, d_street_2, d_city, d_state, d_zip, d_name" +
                " FROM district WHERE d_w_id = " + w_id +
                " AND d_id = " + d_id);
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.payGetDist);
      }
      rs = payGetDist.executeQuery();
      if (!rs.next()) {
        String s = "d_id=" + d_id + " d_w_id=" + w_id;
        throw new DataNotFoundException(s);
      }
      d_street_1 = rs.getString("d_street_1");
      d_street_2 = rs.getString("d_street_2");
      d_city = rs.getString("d_city");
      d_state = rs.getString("d_state");
      d_zip = rs.getString("d_zip");
      d_name = rs.getString("d_name");
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.payGetDist, stmt_start);
      }
      if (this.logQueryResults) Log.getLogWriter().info("d_street_1=" + d_street_1
        + "d_street_2=" + d_street_2 + "d_city=" + d_city
        + "d_state=" + d_state + "d_zip=" + d_zip + "d_name=" + d_name);
      rs.close();
      rs = null;

      // END WORKAROUND FOR TX REQUIRING PR BEFORE RR

      if(w_name.length() > 10) w_name = w_name.substring(0, 10);
      if(d_name.length() > 10) d_name = d_name.substring(0, 10);
      h_data = w_name + "    " + d_name;

      if (payInsertHist == null) {
        payInsertHist = this.connection.prepareStatement(
          insertOp + " INTO history (h_c_d_id, h_c_w_id, h_c_id, h_d_id,"+
                " h_w_id, h_date, h_amount, h_data) " +
                " VALUES (?,?,?,?,?,?,?,?)");
      }
      payInsertHist.setInt(1, c_d_id);
      payInsertHist.setInt(2, c_w_id);
      payInsertHist.setInt(3, c_id);
      payInsertHist.setInt(4, d_id);
      payInsertHist.setInt(5, w_id);
      Timestamp timestamp = new Timestamp(System.currentTimeMillis());
      payInsertHist.setTimestamp(6, timestamp);
      payInsertHist.setFloat(7, h_amount);
      payInsertHist.setString(8, h_data);
      if (this.logQueries) {
        Log.getLogWriter().info("EXECUTING: " +
          insertOp + " INTO history (h_c_d_id, h_c_w_id, h_c_id, h_d_id,"+
                " h_w_id, h_date, h_amount, h_data) " +
                " VALUES (" + c_d_id + "," + c_w_id + "," + c_id +
                "," + d_id + "," + w_id + "," + timestamp
                + "," + h_amount + "," + h_data + ")");
      }
      if (this.timeStmts) {
        stmt_start = this.tpccstats.startStmt(Stmt.payInsertHist);
      }
      payInsertHist.executeUpdate();
      if (this.timeStmts) {
        this.tpccstats.endStmt(Stmt.payInsertHist, stmt_start);
      }

      commit(PAYMENT, start);

    } catch (SQLException e) {
      rollbackIfConflict(PAYMENT, start, e);
    }
  }

//------------------------------------------------------------------------------
// STOCK LEVEL TRANSACTION
//------------------------------------------------------------------------------

  private void stockLevelTransaction() throws SQLException {
    int threshold = this.rng.nextInt(10, 20);
    int index = this.rng.nextInt(0, warehouseIDs.size() - 1);
    int warehouseID = warehouseIDs.get(index);
    int districtID = this.rng.nextInt(1, this.numDistrictsPerWarehouse);
    stockLevelTransaction(warehouseID, districtID, threshold);
  }

  private void stockLevelTransaction(int w_id, int d_id, int threshold)
  throws SQLException {
    int o_id = 0;
    int i_id = 0;
    int stock_count = 0;

    long start = this.tpccstats.startTransaction(STOCK_LEVEL);
    long stmt_start = 0;

    //if (stockGetDistOrderId == null) {
    //  stockGetDistOrderId = this.connection.prepareStatement(
    //    "SELECT d_next_o_id" +
    //          " FROM district" +
    //          " --gemfirexd-properties statementAlias=stockGetDistOrderId \n" +
    //          " WHERE d_w_id = ?" +
    //          " AND d_id = ?");
    //}
    //stockGetDistOrderId.setInt(1, w_id);
    //stockGetDistOrderId.setInt(2, d_id);
    //if (this.logQueries) {
    //  Log.getLogWriter().info("EXECUTING: " +
    //    "SELECT d_next_o_id" +
    //          " FROM district" +
    //          " WHERE d_w_id = " + w_id +
    //          " AND d_id = " + d_id);
    //}
    //if (this.timeStmts) {
    //  stmt_start = this.tpccstats.startStmt(Stmt.stockGetDistOrderId);
    //}
    //rs = stockGetDistOrderId.executeQuery();
    //
    //if (!rs.next()) {
    //  String s = "d_w_id=" + w_id + " d_id=" + d_id;
    //  throw new DataNotFoundException(s);
    //}
    //o_id = rs.getInt("d_next_o_id");
    o_id = getCurrentKey(w_id, d_id);
    //if (this.timeStmts) {
    //  this.tpccstats.endStmt(Stmt.stockGetDistOrderId, stmt_start);
    //}
    if (this.logQueryResults) Log.getLogWriter().info("d_next_o_id=" + o_id);
    //rs.close();
    //rs = null;

    if (stockGetCountStock == null) {
      String stmt = "SELECT COUNT(DISTINCT (s_i_id)) AS stock_count FROM";
      if (this.queryAPI == QueryPrms.GFXD) {
        stmt += " --gemfirexd-properties statementAlias=stockGetCountStock \n";
      }
      stmt += " order_line " + getQueryHDFSProperty() + "," +
              " stock " + getQueryHDFSProperty() + 
              " WHERE ol_w_id = ? AND ol_d_id = ?" +
              " AND ol_o_id < ? AND ol_o_id >= ? - 20 AND s_w_id = ol_w_id" +
              " AND s_i_id = ol_i_id AND s_quantity < ?";
      stockGetCountStock = this.connection.prepareStatement(stmt);
    }
    stockGetCountStock.setInt(1, w_id);
    stockGetCountStock.setInt(2, d_id);
    stockGetCountStock.setInt(3, o_id);
    stockGetCountStock.setInt(4, o_id);
    stockGetCountStock.setInt(5, threshold);
    if (this.logQueries) {
      Log.getLogWriter().info("EXECUTING: " +
        "SELECT COUNT(DISTINCT (s_i_id)) AS stock_count" +
              " FROM order_line, stock" +
              " WHERE ol_w_id = " + w_id +
              " AND ol_d_id = " + d_id +
              " AND ol_o_id < " + o_id +
              " AND ol_o_id >= " + o_id + " - 20" +
              " AND s_w_id = ol_w_id" +
              " AND s_i_id = ol_i_id" +
              " AND s_quantity < " + threshold);
    }
    if (this.timeStmts) {
      stmt_start = this.tpccstats.startStmt(Stmt.stockGetCountStock);
    }
    rs = stockGetCountStock.executeQuery();

    if (!rs.next()) {
      String s = "ol_o_id=" + o_id + " ol_d_id=" + d_id + " ol_w_id="
               + w_id + " (...)";
      throw new DataNotFoundException(s);
    }
    stock_count = rs.getInt("stock_count");
    if (this.timeStmts) {
      this.tpccstats.endStmt(Stmt.stockGetCountStock, stmt_start);
    }
    if (this.logQueryResults) Log.getLogWriter().info("stock_count=" + stock_count);
    rs.close();
    rs = null;

    this.tpccstats.endTransaction(STOCK_LEVEL, start, 0);
  }

//------------------------------------------------------------------------------
// DBSYNCHRONIZER, QUEUES, ETC.
//------------------------------------------------------------------------------

  public static void waitForAsyncEventQueuesToDrainTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize(TRANSACTIONS);
    if (c.jid == 0) {
      c.waitForAsyncEventQueuesToDrain();
    }
  }
  private void waitForAsyncEventQueuesToDrain() throws SQLException {
    final StatisticsFactory f = DistributedSystemHelper.getDistributedSystem();
    Statistics[] stats =
      f.findStatisticsByType(f.findType(AsyncEventQueueStats.typeName));
    while (true) {
      long qsize = 0;
      for (int i=0; i < stats.length; i++) {
        qsize += stats[i].getInt("eventQueueSize");
      }
      if (qsize == 0) {
        break;
      }
      MasterController.sleepForMs(250);
    }
    cacheEndTrim(this.trimIntervals, this.trimInterval);
  }

  public static void waitForQueuesToDrainTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize(TRANSACTIONS);
    if (c.jid == 0) {
      c.waitForQueuesToDrain();
    }
  }
  private void waitForQueuesToDrain() throws SQLException {
    final StatisticsFactory f = DistributedSystemHelper.getDistributedSystem();
    Statistics[] stats =
      f.findStatisticsByType(f.findType(GatewaySenderStats.typeName));
    while (true) {
      long qsize = 0;
      for (int i=0; i < stats.length; i++) {
        qsize += stats[i].getInt(GatewaySenderStats.getEventQueueSizeId());
      }
      if (qsize == 0) {
        break;
      }
      MasterController.sleepForMs(250);
    }
    cacheEndTrim(this.trimIntervals, this.trimInterval);
  }

  /**
   * Validates that a random warehouse can be found in the back end database.
   */
  public static void validateWhseTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize(TRANSACTIONS);
    c.validateWhse();
  }

  private void validateWhse() throws SQLException {
    String stmt = "SELECT w_name FROM warehouse WHERE w_id = ?";
    PreparedStatement pstmt = this.connection.prepareStatement(stmt);
    int w_id = this.rng.nextInt(1, this.numWarehouses);
    pstmt.setInt(1, w_id);
    if (this.logQueries) {
      Log.getLogWriter().info("VALIDATING: " + 
        "SELECT w_name FROM warehouse WHERE w_id = " + w_id);
    }
    rs = pstmt.executeQuery();
    if (!rs.next()) {
      String s = "w_id=" + w_id;
      throw new DataNotFoundException(s);
    }
    String w_name = rs.getString("w_name");
    if (this.logQueryResults) {
      Log.getLogWriter().info("w_id=" + w_id + " w_name=" + w_name);
    }
    rs.close();
    rs = null;
    pstmt.close();
  }

//------------------------------------------------------------------------------
// TRANSACTION SUPPORT
//------------------------------------------------------------------------------

  protected boolean commit(int txType, long start) throws SQLException {
    if (this.logQueries) {
      Log.getLogWriter().info("Committing..." + getTxType(txType));
    }
    boolean committed = false;
    long commitStart = this.tpccstats.startCommit();
    try {
      this.connection.commit();
      committed = true;
      this.tpccstats.endTransaction(txType, start, commitStart);
      if (this.logQueries) {
        Log.getLogWriter().info("Committed " + getTxType(txType));
      }
    } catch (SQLException e) {
      rollbackIfConflict(txType, start, e);
    }
    return committed;
  }

  protected void abortTransaction(int txType, long start, Exception e) throws SQLException {
    if (this.logQueries) {
      Log.getLogWriter().info("Aborting transaction..." + getTxType(txType));
    }
    this.connection.rollback();
    this.tpccstats.endTransactionAbort(txType, start);
    if (this.logQueries) {
      Log.getLogWriter().info("Aborted transaction " + getTxType(txType)
         + " due to " + e.getClass().getName() + ": " + e.getMessage());
    }
  }

  protected void rollbackIfConflict(int txType, long start, SQLException e)
  throws SQLException {
    if (e instanceof BatchUpdateException) {
      SQLException ex = e;
      while (ex != null) {
        if (ex.getSQLState().equals(CONFLICT_STATE)) {
          rollbackTransaction(txType, start, ex);
          return;
        } else if (this.queryAPI == QueryPrms.ORACLE && e.getSQLState().equals(DEADLOCK_STATE)) {
          rollbackTransaction(txType, start, ex);
          return;
        }
        ex = ex.getNextException();
      }
    } else if (e.getSQLState().equals(CONFLICT_STATE)) {
      rollbackTransaction(txType, start, e);
      return;
    } else if (this.queryAPI == QueryPrms.ORACLE && e.getSQLState().equals(DEADLOCK_STATE)) {
      rollbackTransaction(txType, start, e);
      return;
    }
    if (this.logQueries) {
      Log.getLogWriter().severe("Unexpected exception in " + getTxType(txType)
         + " due to " + e.getClass().getName() + ": " + e.getMessage());
      SQLHelper.printSQLException(e);
    }
    throw e;
  }

  protected void rollbackTransaction(int txType, long start, SQLException e)
  throws SQLException {
    if (this.logQueries) {
      Log.getLogWriter().info("Rolling back..." + getTxType(txType));
    }
    if (this.queryAPI != QueryPrms.GFXD) {
      this.connection.rollback();
    } // GFXD automatically rolls back from a conflict
    // but mark rollback stats anyway to show conflicts
    this.tpccstats.endTransactionRollback(txType, start);
    if (this.logQueries) {
      Log.getLogWriter().info("Rolled back " + getTxType(txType)
         + " due to " + e.getClass().getName() + ": " + e.getMessage());
      SQLHelper.printSQLException(e);
    }
  }

  protected void rollbackIfDuplicate(int txType, long start, SQLException e)
  throws SQLException {
    if (e.getMessage().startsWith(DUPLICATE_STR)) {
      if (this.logQueries) {
        Log.getLogWriter().info("Rolling back..." + getTxType(txType));
      }
      this.connection.rollback();
      this.tpccstats.endTransactionRestart(txType, start);
      if (this.logQueries) {
        Log.getLogWriter().info("Rolled back " + getTxType(txType)
           + " due to " + e.getClass().getName() + ": " + e.getMessage());
        SQLHelper.printSQLException(e);
      }
    } else {
      if (this.logQueries) {
        Log.getLogWriter().severe("Unexpected exception in " + getTxType(txType)
           + " due to " + e.getClass().getName() + ": " + e.getMessage());
        SQLHelper.printSQLException(e);
      }
      throw e;
    }
  }

  public static void dumpBucketsHook() {
    String clientName = RemoteTestModule.getMyClientName();
    // @todo rework this to determine if this is a datahost
    if (clientName.contains("server")
        || clientName.contains("sender") || clientName.contains("receiver")
        || clientName.contains("dbsync") || clientName.contains("prdata")) {
      Log.getLogWriter().info("Dumping local buckets");
      ResultSetHelper.dumpLocalBucket();
      Log.getLogWriter().info("Dumped local buckets");
    }
  }

  public static void dumpBucketsTask() {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.jid == 0) {
      Log.getLogWriter().info("Dumping local buckets");
      ResultSetHelper.dumpLocalBucket();
      Log.getLogWriter().info("Dumped local buckets");
    }
  }

//------------------------------------------------------------------------------
// script spawning for Chethan Kumar
//------------------------------------------------------------------------------

  /**
   * Starts the script specified in {@link TPCCPrms#script}. Only one thread
   * on each host will start and stop the script.
   */
  public static void startScriptTask() throws RemoteException {
    String script = TPCCPrms.getScript();
    if (script != null) {
      TPCCClient c = new TPCCClient();
      c.initLocalVariables(-1);
      c.startScript(script);
    }
  }

  private static void startScript(String script) throws RemoteException {
    SharedLock lock = TPCCBlackboard.getInstance().getSharedLock();
    lock.lock();
    try {
      SharedMap map = TPCCBlackboard.getInstance().getSharedMap();
      HostDescription hd = TestConfig.getInstance()
            .getClientDescription(RemoteTestModule.getMyClientName())
            .getVmDescription().getHostDescription();
      String hostName = hd.getHostName();
      Integer pid = (Integer)map.get(hostName);
      if (pid == null || pid == 0) {
        Log.getLogWriter().info("Starting script: " + script);
        pid = ProcessMgr.bgexec(script);
        map.put(hostName, pid);
        RemoteTestModule.Master.recordPID(hd, pid);
        Log.getLogWriter().info("Started script: " + script + " at " + hostName + ":" + pid);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops the script started in {@link #startScriptTask}.
   */
  public static void stopScriptTask() throws RemoteException {
    String script = TPCCPrms.getScript();
    if (script != null) {
      TPCCClient c = new TPCCClient();
      c.initLocalVariables(-1);
      c.stopScript(script);
    }
  }

  private static void stopScript(String script) throws RemoteException {
    SharedLock lock = TPCCBlackboard.getInstance().getSharedLock();
    lock.lock();
    try {
      SharedMap map = TPCCBlackboard.getInstance().getSharedMap();
      HostDescription hd = TestConfig.getInstance()
            .getClientDescription(RemoteTestModule.getMyClientName())
            .getVmDescription().getHostDescription();
      String hostName = hd.getHostName();
      Integer pid = (Integer)map.get(hostName);
      if (pid != null && pid != 0) {
        Log.getLogWriter().info("Stopping script: " + script + " at " + hostName + ":" + pid);
        ProcessMgr.killProcess(hostName, pid);
        map.put(hostName, 0);
        RemoteTestModule.Master.removePID(hd, pid);
        Log.getLogWriter().info("Stopped script: " + script);
      }
    } finally {
      lock.unlock();
    }
  }

//------------------------------------------------------------------------------
// hydra thread locals
//------------------------------------------------------------------------------

  protected void initHydraThreadLocals() {
    super.initHydraThreadLocals();
    this.tpccstats = getTPCCStats();
    this.ids = getIDs();
    this.cards = getCards();
    this.currentKeys = getCurrentKeys();
  }

  protected void updateHydraThreadLocals() {
    super.updateHydraThreadLocals();
    setTPCCStats(this.tpccstats);
    setIDs(this.ids);
    setCards(this.cards);
    setCurrentKeys(this.currentKeys);
  }

  /**
   * Gets the per-thread TPCCStats wrapper instance.
   */
  protected TPCCStats getTPCCStats() {
    TPCCStats tpccstats = (TPCCStats)localtpccstats.get();
    return tpccstats;
  }

  /**
   * Sets the per-thread TPCCStats wrapper instance.
   */
  protected void setTPCCStats(TPCCStats tpccstats) {
    localtpccstats.set(tpccstats);
  }

  /** return the ids from either the shared map or the hydra thread locals */
  protected IDs getIDs() {
    IDs i = (IDs)localids.get();
    if (i == null) {
      SharedMap map = TPCCBlackboard.getInstance().getSharedMap();
      if (map.size() == 0) {
        String s = "TPCCBlackboard has not been initialized";
        throw new HydraConfigException(s);
      }
      i = (IDs)map.get(this.ttgid % map.size());
      if (i == null) {
        String s = "TPCCBlackboard has no map entry for ttgid: " + this.ttgid;
        throw new HydraConfigException(s);
      }
      Log.getLogWriter().info("Using " + i + " for task thread " + this.ttgid);
    }
    return i;
  }

  protected void setIDs(IDs i) {
    localids.set(i);
  }

  protected int[] getCards() {
    int[] arr = (int[])localcards.get();
    if (arr == null) {
      int paymentWeight     = TPCCPrms.getPaymentWeight();
      int stockLevelWeight  = TPCCPrms.getStockLevelWeight();
      int orderStatusWeight = TPCCPrms.getOrderStatusWeight();
      int deliveryWeight    = TPCCPrms.getDeliveryWeight();
      int newOrderWeight    = TPCCPrms.getNewOrderWeight();
      Log.getLogWriter().info("Using transaction weights:"+
                               " payment="     + paymentWeight +
                               " stockLevel="  + stockLevelWeight +
                               " orderStatus=" + orderStatusWeight +
                               " delivery="    + deliveryWeight +
                               " newOrder="    + newOrderWeight);

      arr = new int[paymentWeight + stockLevelWeight + orderStatusWeight +
                    deliveryWeight + newOrderWeight]; 
      for (int i = 0; i < paymentWeight; i++) {
        arr[i] = PAYMENT;
      }
      for (int i = paymentWeight;
               i < paymentWeight + stockLevelWeight; i++) {
        arr[i] = STOCK_LEVEL;
      }
      for (int i = paymentWeight + stockLevelWeight;
               i < paymentWeight + stockLevelWeight + orderStatusWeight; i++) {
        arr[i] = ORDER_STATUS;
      }
      for (int i = paymentWeight + stockLevelWeight + orderStatusWeight;
               i < paymentWeight + stockLevelWeight + orderStatusWeight
                 + deliveryWeight; i++) {
        arr[i] = DELIVERY;
      }
      for (int i = paymentWeight + stockLevelWeight + orderStatusWeight
                 + deliveryWeight;
               i < paymentWeight + stockLevelWeight + orderStatusWeight
                 + deliveryWeight + newOrderWeight; i++) {
        arr[i] = NEW_ORDER;
      }
    }
    return arr;
  }

  protected void setCards(int[] arr) {
    localcards.set(arr);
  }

  /**
   * Gets the current keys for a thread's workload.
   */
  protected int[][] getCurrentKeys() {
    int[][] n = (int[][]) localcurrentkeys.get();
    if (n == null) {
      if (this.numCustomersPerDistrict < this.ttgid) {
        String s = "The number of threads for this task (" + this.numThreads
                 + ") must be less than or equal to numCustomersPerDistrict ("
                 + this.numCustomersPerDistrict + ")";
        throw new HydraConfigException(s);
      }
      // note that n[0][*] and n[*][0] are never used
      n = new int[this.numWarehouses+1][this.numDistrictsPerWarehouse+1];
      for (int i = 0; i <= this.numWarehouses; i++) {
        for (int j = 0; j <= this.numDistrictsPerWarehouse; j++) {
          n[i][j] = this.numCustomersPerDistrict - this.ttgid;
        }
      }
      localcurrentkeys.set(n);
    }
    return n;
  }

  /**
   * Sets the current keys for a thread's workload.
   */
  protected void setCurrentKeys(int[][] n) {
    localcurrentkeys.set(n);
  }

//------------------------------------------------------------------------------
// THROTTLING
//------------------------------------------------------------------------------

  protected int getThrottleMs() {
    int throttleMs = TPCCPrms.getThrottleMs();
    Log.getLogWriter().info("Throttle set at " + throttleMs + " ms");
    return throttleMs;
  }

//------------------------------------------------------------------------------
// TIMING FOR STARTUP
//------------------------------------------------------------------------------

  private static HydraThreadLocal localserverstartup = new HydraThreadLocal();

  /**
   * Starts timer for server startup. Run this in a client with statistics.
   */
  public static void startServerStartupTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.ttgid == 0) {
      localserverstartup.set(Long.valueOf(c.tpccstats.startServerStartup()));
    }
  }

  /**
   * Ends timer for server startup. Run this in a client with statistics.
   */
  public static void endServerStartupTask() throws SQLException {
    TPCCClient c = new TPCCClient();
    c.initialize();
    if (c.ttgid == 0) {
      c.tpccstats.endServerStartup((Long)localserverstartup.get());
    }
  }

//------------------------------------------------------------------------------
// OVERRIDDEN METHODS
//------------------------------------------------------------------------------

  protected void initLocalParameters() {
    super.initLocalParameters();

    this.logQueries = QueryPrms.logQueries();
    this.logQueryResults = QueryPrms.logQueryResults();

    // table sizes
    this.numWarehouses = TPCCPrms.getNumWarehouses();
    this.numDistrictsPerWarehouse = TPCCPrms.getNumDistrictsPerWarehouse();
    this.customerBase = TPCCPrms.getCustomerBase();
    this.numCustomersPerDistrict = TPCCPrms.getNumCustomersPerDistrict();
    if (this.numCustomersPerDistrict <= this.customerBase) {
      String s = BasePrms.nameForKey(TPCCPrms.numCustomersPerDistrict)
               + "=" + this.numCustomersPerDistrict
               + " must be greater than "
               + BasePrms.nameForKey(TPCCPrms.customerBase)
               + "=" + this.customerBase
               ;
      throw new QueryPerfException(s);
    }
    this.itemBase = TPCCPrms.getItemBase();
    this.numItems = TPCCPrms.getNumItems();
    if (this.numItems <= this.itemBase) {
      String s = BasePrms.nameForKey(TPCCPrms.numItems)
               + " must be greater than "
               + BasePrms.nameForKey(TPCCPrms.itemBase);
      throw new QueryPerfException(s);
    }
    Log.getLogWriter().info("Using table sizes:"+
                     " warehouses="            + this.numWarehouses +
                     " districtsPerWarehouse=" + this.numDistrictsPerWarehouse +
                     " items="                 + this.numItems +
                     " customersPerDistrict="  + this.numCustomersPerDistrict);

    // number of rows per commit when loading data
    this.commitCount = TPCCPrms.getCommitCount();
    this.useScrollableResultSets = TPCCPrms.useScrollableResultSets();
  }

  protected static int intFor(String name) {
    if (name.equalsIgnoreCase(WAREHOUSE_NAME)) {
      return WAREHOUSE;
    } else if (name.equalsIgnoreCase(ITEM_NAME)) {
      return ITEM;
    } else if (name.equalsIgnoreCase(STOCK_NAME)) {
      return STOCK;
    } else if (name.equalsIgnoreCase(DISTRICT_NAME)) {
      return DISTRICT;
    } else if (name.equalsIgnoreCase(CUSTOMER_NAME)) {
      return CUSTOMER;
    } else if (name.equalsIgnoreCase(ORDER_NAME)) {
      return ORDER;
    } else if (name.equalsIgnoreCase(TRANSACTIONS_NAME)) {
      return TRANSACTIONS;
    } else if (name.equalsIgnoreCase(TRANSACTIONS_A_NAME)) {
      return TRANSACTIONS_A;
    } else if (name.equalsIgnoreCase(TRANSACTIONS_B_NAME)) {
      return TRANSACTIONS_B;
    } else if (name.equalsIgnoreCase(TRANSACTIONS_C_NAME)) {
      return TRANSACTIONS_C;
    } else {
      String s = "Unexpected trim interval: " + name;
      throw new QueryPerfException(s);
    }
  }

  protected String nameFor(int name) {
    switch (name) {
      case WAREHOUSE:
        return WAREHOUSE_NAME;
      case ITEM:
        return ITEM_NAME;
      case STOCK:
        return STOCK_NAME;
      case DISTRICT:
        return DISTRICT_NAME;
      case CUSTOMER:
        return CUSTOMER_NAME;
      case ORDER:
        return ORDER_NAME;
      case TRANSACTIONS:
        return TRANSACTIONS_NAME;
      case TRANSACTIONS_A:
        return TRANSACTIONS_A_NAME;
      case TRANSACTIONS_B:
        return TRANSACTIONS_B_NAME;
      case TRANSACTIONS_C:
        return TRANSACTIONS_C_NAME;
    }
    return super.nameFor(name);
  }

  protected int getNextKey(int w, int d) {
    if (this.currentKeys[w][d] == 0) {
      this.currentKeys[w][d] = this.ttgid + 1;
    } else {
      this.currentKeys[w][d] += this.numThreads;
    }
    return this.currentKeys[w][d];
  }

  protected int getCurrentKey(int w, int d) {
    return this.currentKeys[w][d];
  }

  protected boolean getLogQueries() {
    return this.logQueries;
  }

  protected void setLogQueries(boolean b) {
    this.logQueries = b;
  }

//------------------------------------------------------------------------------
// IDS CLASS
//------------------------------------------------------------------------------

  public static class IDs implements Serializable {
    int warehouseID, districtID;

    public IDs(int wid, int did) {
      this.warehouseID = wid;
      this.districtID = did;
    }

    public int getWarehouseID() {
      return this.warehouseID;
    }

    public int getDistrictID() {
      return this.districtID;
    }

    public String toString() {
      return "warehouse_" + this.warehouseID + "_district_" + this.districtID;
    }
  }

  protected String getTxType(int txType) {
    switch (txType) {
      case 1: return "PAYMENT TX";
      case 2: return "STOCK LEVEL TX";
      case 3: return "ORDER STATUS TX";
      case 4: return "DELIVERY TX";
      case 5: return "NEW ORDER TX";
      default: throw new QueryPerfException("Should not happen");
    }
  }
}
