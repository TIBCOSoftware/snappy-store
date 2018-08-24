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
package sql.sqlBridge;

import java.io.PrintWriter;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import com.pivotal.gemfirexd.internal.drda.NetworkServerControl;

import hydra.ClientVmInfo;
import hydra.DistributedSystemHelper;
import hydra.HostHelper;
import hydra.HydraThreadLocal;
import hydra.Log;
import hydra.MasterController;
import hydra.PortHelper;
import hydra.Prms;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import splitBrain.SplitBrainBB;
import sql.GFEDBManager;
import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.ddlStatements.DDLStmtIF;
import sql.ddlStatements.FunctionDDLStmt;
import sql.ddlStatements.IndexDDLStmt;
import sql.ddlStatements.Procedures;
import sql.hdfs.HDFSSqlTest;
import sql.sqlutil.ResultSetHelper;
import util.PRObserver;
import util.StopStartVMs;
import util.TestException;
import util.TestHelper;
import hydra.gemfirexd.FabricServerHelper;
import hydra.gemfirexd.NetworkServerHelper;
import hydra.gemfirexd.GfxdConfigPrms;

public class SQLBridgeTest extends SQLTest {
  protected static SQLBridgeTest sqlbtest;
  protected static boolean useMcast;
  protected static String locatorString;
  protected static Properties bootProps;
  protected static Properties connProps;
  protected static String hostName;
  protected static int portNo;
  protected static NetworkServerControl netServer = null;  
  public static boolean useGemFireXDHA = TestConfig.tab().booleanAt(SQLPrms.
  		useGemFireXDHA, false);
  public static HydraThreadLocal myConn = new HydraThreadLocal();
  static String[] getQueries = {
    "select * from trade.customers where cid = ?",
    "select * from trade.securities where sec_id = ?",
    "select * from trade.networth where cid = ?",
    "select * from trade.buyorders where oid = ?",
    "select * from trade.sellorders where oid = ?",   
  };
  
  /**
   * Creates a (disconnected) locator.
   */
  public static void createLocatorTask() {
    DistributedSystemHelper.createLocator();
  }

  /**
   * Connects a locator to its (admin-only) distributed system.
   */
  public static void startAndConnectLocatorTask() {
    DistributedSystemHelper.startLocatorAndAdminDS();
  }
  
  /**
   * Stops a locator.
   */
  public static void stopLocatorTask() {
    DistributedSystemHelper.stopLocator();
  }
  
  //write to bb for data stores to randomly chose from
  public static void HydraTask_initForServerGroup() {
    if (sqlbtest == null) {
      sqlbtest = new SQLBridgeTest();
    }
    sqlbtest.initForServerGroup();
  }
  
  public static synchronized void HydraTask_initializeServer() {
    if (sqlbtest == null) {
      sqlbtest = new SQLBridgeTest();
    }
	  
	  PRObserver.installObserverHook();
	  PRObserver.initialize(RemoteTestModule.getMyVmid());
    
	  initializeParams();
  }
  
  private static void initializeParams() {
    hasDerbyServer = TestConfig.tab().booleanAt(Prms.manageDerbyServer, false);
    testUniqueKeys = TestConfig.tab().booleanAt(SQLPrms.testUniqueKeys, true);
    randomData =  TestConfig.tab().booleanAt(SQLPrms.randomData, false);
    isSerial =  TestConfig.tab().booleanAt(Prms.serialExecution, false);
    isSingleDMLThread = TestConfig.tab().booleanAt(SQLPrms.isSingleDMLThread, false);
    usingTrigger = TestConfig.tab().booleanAt(SQLPrms.usingTrigger, false);
    queryAnyTime = TestConfig.tab().booleanAt(SQLPrms.queryAnyTime, true);
    hasNetworth =  TestConfig.tab().booleanAt(SQLPrms.hasNetworth, false);
    //if(hasHdfs) hdfsSqlTest = new HDFSSqlTest();
  }
  	
  public static synchronized void HydraTask_initialize() {
    if (sqlbtest == null) {
      sqlbtest = new SQLBridgeTest();
    }
	  
	  PRObserver.installObserverHook();
	  PRObserver.initialize(RemoteTestModule.getMyVmid());
	  
	  bootProps = sqlbtest.getGemFireProperties();
	  bootProps.setProperty("host-data", "true"); 
	  hostName = HostHelper.getIPAddress().getHostName();
    
    Log.getLogWriter().info("Connecting with properties: " + bootProps);
    //Log.getLogWriter().info("hostname is  " + hostName);   
    
    initializeParams();
  }
  
  public static synchronized void HydraTask_initialize_replayHA() {
    Object initDelayed = SQLBB.getBB().getSharedMap().get("initDelayed_" + RemoteTestModule.getMyVmid());
    if (initDelayed == null) {
            SQLBB.getBB().getSharedMap().put("initDelayed_" + RemoteTestModule.getMyVmid(), "true");
            Log.getLogWriter().info("first time for init DDL replay, do nothing");
            return;
    }
	  if (sqlbtest == null) {
			sqlbtest = new SQLBridgeTest();
		
			HydraTask_initialize();
			sqlbtest.createGFXDDB();
			
			int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
			List<ClientVmInfo>vms = new ArrayList<ClientVmInfo>();
			vms.add(new ClientVmInfo(RemoteTestModule.getMyVmid()));
			PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);
			StopStartVMs.StopStart_initTask();
	  } 
  	int sleepMS = 30000;
  	Log.getLogWriter().info("sleep for " + sleepMS/1000 + " sec");
  	MasterController.sleepForMs(sleepMS);
  }
  
  //each connection server should have only one thread performing this task. 
  @SuppressWarnings("unchecked")
	public static synchronized void HydraTask_initConnBridgePort() {
  	portNo = PortHelper.getRandomPort(); 	
  	int count = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.initServerPort);
  	while (count != 1) {
  		count = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.initServerPort);
  	}
  	HashMap	<String, ArrayList<Integer>> map = (HashMap) SQLBB.getBB().getSharedMap().get("serverPorts");
  	ArrayList<Integer> portLists;
  	if ( map== null) {
  		map = new HashMap <String, ArrayList<Integer>>();
  		portLists = new ArrayList<Integer>(); 	
  	} else {
  		portLists = map.get(hostName);
  		if (portLists == null) portLists = new ArrayList<Integer>();
  	}
  	portLists.add(portNo);
  	map.put(hostName, portLists);
  	SQLBB.getBB().getSharedMap().put("serverPorts", map);
  	SQLBB.getBB().getSharedCounters().zero(SQLBB.initServerPort);  	
  	
  	Log.getLogWriter().info("host " + hostName + " port " + portNo + 
			" is available for client connection");
  	//bootProps.put("port", portNo);
  }
  
  @SuppressWarnings("unchecked")
	protected static String getLocatorString() {
    List<DistributedSystemHelper.Endpoint> endpoints = DistributedSystemHelper.getEndpoints();
    return DistributedSystemHelper.endpointsToString(endpoints);
  }
  
  //create gfxd database (through connection) without ds
  public static synchronized void HydraTask_createGFXDDB() {
    sqlbtest.createGFXDDB();
    Object tableCreated = SQLBB.getBB().getSharedMap().get("tableCreated_" + RemoteTestModule.getMyVmid());
    if (tableCreated == null) {
      SQLBB.getBB().getSharedMap().put("tableCreated_" + RemoteTestModule.getMyVmid(), "true");
      Log.getLogWriter().info("first time, table has not been created, do nothing");
      return;
    } //first time table has not been created
    if (isHATest) {
    	addObserver();
    }
  }
  
  public synchronized static void HydraTask_startFabricServer() {
    sqlbtest.startFabricServer();
  }
  
  public synchronized static void HydraTask_startFabricServerSG() {
    sqlbtest.startFabricServerSG();
  }
  
  public synchronized static void HydraTask_createGFXDDBSGDBSynchronizer() {
    sqlbtest.createGFXDDBSGDBSynchronizer();
  }
  
  
  public static synchronized void HydraTask_startNetworkServer() {
  	String networkServerConfig = GfxdConfigPrms.getNetworkServerConfig();
    NetworkServerHelper.startNetworkServers(networkServerConfig);
  }
  
  public synchronized static void HydraTask_stopFabricServer() {
    sqlbtest.stopFabricServer();
  } 
  
  protected void createGFXDDB() {  
    startGFXDDB(bootProps);
  }
  
  protected static void addObserver() {
		int numOfPRs = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
		List<ClientVmInfo>vms = new ArrayList<ClientVmInfo>();
		vms.add(new ClientVmInfo(RemoteTestModule.getMyVmid()));
		PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);
		StopStartVMs.StopStart_initTask();
  }
  
  public static synchronized void HydraTask_initEdges() {
  	if (sqlbtest == null) {
  		sqlbtest = new SQLBridgeTest();  		
  	} 
    
    hasDerbyServer = TestConfig.tab().booleanAt(Prms.manageDerbyServer, false);
    testUniqueKeys = TestConfig.tab().booleanAt(SQLPrms.testUniqueKeys, true);
    randomData =  TestConfig.tab().booleanAt(SQLPrms.randomData, false);
    isSerial =  TestConfig.tab().booleanAt(Prms.serialExecution, false);
    isSingleDMLThread = TestConfig.tab().booleanAt(SQLPrms.isSingleDMLThread, false);
    usingTrigger = TestConfig.tab().booleanAt(SQLPrms.usingTrigger, false);
    queryAnyTime = TestConfig.tab().booleanAt(SQLPrms.queryAnyTime, true);
    hasNetworth =  TestConfig.tab().booleanAt(SQLPrms.hasNetworth, false);
    isEdge = true;
    
  }
  
  public static synchronized void HydraTask_createGFXDNetServer() throws Exception {
    sqlbtest.createGFXDNetServer();
  }
  
  protected void createGFXDNetServer() throws Exception {
    netServer = new NetworkServerControl(InetAddress.getByName(hostName),
        portNo);
    netServer.start(new PrintWriter(System.out));
    while(true) {
    	MasterController.sleepForMs(500);
      try {
        netServer.ping();
        break;
      }
      catch (Exception e) {
      	Log.getLogWriter().info("could not ping netServer "+TestHelper.getStackTrace(e));
      }
    }
    netServer.logConnections(true);
  }
  
  public static void HydraTask_createGFESchemasByClients() {
  	sqlbtest.createGFESchemasByClients();
  }
  
  protected void createGFESchemasByClients() {
    ddlThread = getMyTid();
    Connection conn = getGFXDClientConnection();
    Log.getLogWriter().info("creating schema in gfxd.");
    if (!testServerGroupsInheritence) createSchemas(conn);
    else {
      String[] schemas = SQLPrms.getGFESchemas(); //with server group
      createSchemas(conn, schemas); 
    }
    Log.getLogWriter().info("done creating schema in gfxd.");
    closeGFEConnection(conn);
  }
  
  public static void HydraTask_createGFETablesByClients() {
	  sqlbtest.createGFETablesByClients();
  }
  
  protected void createGFETablesByClients() {
    Connection conn = getGFXDClientConnection();
    Log.getLogWriter().info("creating tables in gfxd.");
    createTables(conn);
    Log.getLogWriter().info("done creating tables in gfxd.");
    closeGFEConnection(conn);
  }
  
  public static void HydraTask_populateTables(){
    sqlbtest.populateTables();
  }
  
  //when no verification is needed, pass dConn as null
  protected void populateTables() {
    Connection dConn = null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();  
    } //only verification case need to populate derby tables
    Connection gConn = getGFXDClientConnection();
    populateTables(dConn, gConn);   
    if (dConn !=null)    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  //perform dml statement
  public static void HydraTask_doDMLOp() {
   if(sqlbtest == null) sqlbtest = new SQLBridgeTest();
    sqlbtest.doDMLOp();
  }
  
  protected void doDMLOp() {
    if (networkPartitionDetectionEnabled) {
      doDMLOp_HandlePartition();
    } else {
      Connection dConn =null;
      if (hasDerbyServer) {
        dConn = getDiscConnection();
      }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
      Connection gConn = getGFXDClientConnection();
      doDMLOp(dConn, gConn);    
      if (dConn!=null) {
        closeDiscConnection(dConn);
        //Log.getLogWriter().info("closed the disc connection");
      }
      closeGFEConnection(gConn);
    }
    Log.getLogWriter().info("done dmlOp");
    
  }
  
  public static void HydraTask_createIndex() {
    sqlbtest.createIndex();
  }

  protected void createIndex() {
    Connection gConn = getGFXDClientConnection();
    
    if (setCriticalHeap) resetCanceledFlag();
    if (setTx && isHATest) resetNodeFailureFlag();
    if (setTx && testEviction) resetEvictionConflictFlag();
    
    createIndex(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }

  protected List <ClientVmInfo> stopStartVMs(int numToKill) { 	
  	if (useGemFireXDHA)
  		return stopStartVMs(numToKill, "server");
  	else
  		return stopStartVMs(numToKill, "store");
  }
  
  //initialize the map contains column information for each table
  public static void HydraTask_setTableCols() {
    sqlbtest.setTableCols();
  }
  
  //can be called only after table has been created  
  protected void setTableCols() {
    Connection gConn = getGFXDClientConnection();
    setTableCols(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_cycleStoreVms() {
    if (cycleVms) sqlbtest.cycleStoreVms();
  }
  
  public static synchronized void HydraTask_createDiscDB() {
  	sqlbtest.createDiscDB();
  }
 
  public static synchronized void HydraTask_createDiscSchemas() {
  	sqlbtest.createDiscSchemas();
  }
  public static synchronized void HydraTask_createDiscTables () {
  	sqlbtest.createDiscTables();
  }
  
  public static void HydraTask_createProcedures() {
  	sqlbtest.createProcedures();
  }
  
  public static void HydraTask_verifyProcedures() {
  	sqlbtest.verifyProcedures();
  }
  
  protected void createProcedures() {
    Log.getLogWriter().info("performing create procedure Op, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFXDClientConnection();
    
    sql.ddlStatements.ProcedureDDLStmt procStmt = new sql.ddlStatements.ProcedureDDLStmt();
    procStmt.createDDLs(dConn, gConn);

    if (dConn!= null) {
      commit(dConn);
      closeDiscConnection(dConn);
    }
    
    commit(gConn);
    closeGFEConnection(gConn);  
  }
  
  //get results set using CallableStatmenet
  protected void verifyProcedures() {
    if (!hasDerbyServer) {
      Log.getLogWriter().warning("skipping verify of query results through "
          + "created procedure call due to manageDerbyServer as false, myTid="
          + getMyTid());
      return;
    }
    Log.getLogWriter().info("verifying query results through created procedure call, myTid is " + getMyTid());
    Connection dConn = getDiscConnection();
    Connection gConn = getGFXDClientConnection();

    verifyProcedures(dConn, gConn);
    
    commit(dConn);
    commit(gConn);    
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);  
  }
  
  public static void HydraTask_callProcedures() {
    sqlbtest.callProcedures();
  }
  
  //get results set using CallableStatmenet
  protected void callProcedures() {
    Log.getLogWriter().info("call procedures, myTid is " + getMyTid());
    Connection dConn = null;
    if (hasDerbyServer){
      dConn = getDiscConnection();
    }
    Connection gConn = getGFXDClientConnection();
    Procedures.callProcedures(dConn, gConn);
    
    if (hasDerbyServer){
      commit(dConn);
      closeDiscConnection(dConn);
    }
    commit(gConn);    
    closeGFEConnection(gConn);  
  }
  
  public static void HydraTask_doOp() {
    int num = 10;
    boolean dmlOp = (random.nextInt(num)==0)? false: true; //1 out of num chance to do ddl operation
    if (dmlOp) sqlbtest.doDMLOp();
    else sqlbtest.doDDLOp();  
  }
  
  protected void doDDLOp() {    
    //TODO differentiate which ddl statements to be performed
    Log.getLogWriter().info("performing ddlOp, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFXDClientConnection();

    //perform the opeartions
    int ddl = ddls[random.nextInt(ddls.length)];
    DDLStmtIF ddlStmt= ddlFactory.createDDLStmt(ddl); //dmlStmt of a table
    
    ddlStmt.doDDLOp(dConn, gConn);

    if (hasDerbyServer) {
      commit(dConn);
      closeDiscConnection(dConn);
    }
    
    commit(gConn);
    closeGFEConnection(gConn);      
    
   
    Log.getLogWriter().info("done DDLOp");
  }
  
  public static void HydraTask_createFuncForProcedures() {
  	sqlbtest.createFuncForProcedures();
  }
  
  protected void createFuncForProcedures() {
    Log.getLogWriter().info("performing create function multiply Op, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFXDClientConnection();
    
    try {
      FunctionDDLStmt.createFuncMultiply(dConn);
      FunctionDDLStmt.createFuncMultiply(gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    if (dConn!= null) {
      commit(dConn);
      closeDiscConnection(dConn);
    }
    
    commit(gConn);
    closeGFEConnection(gConn);  
  }
  
  public static void HydraTask_createGFXDDBSG() {
  	sqlbtest.createGFXDDBSG();
  }
  
  public static void HydraTask_startFabricServerSGDBSynchronizer() {
  	sqlbtest.startFabricServerSGDBSynchronizer();
  }
  
  public static synchronized void HydraTask_createDBSynchronizer() {
  	sqlbtest.createDBSynchronizer();
  }
  
  public static synchronized void HydraTask_createOracleDBSynchronizer() throws SQLException {
    sqlbtest.createOracleDBSynchronizer();
  }
  
  protected void createOracleDBSynchronizer() throws SQLException {
    Connection conn = getGFXDClientConnection();
    createOracleDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }

  //use client driver connection
  protected void createDBSynchronizer() {
    Connection conn = getGFXDClientConnection();
    createNewDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }

  public static synchronized void HydraTask_startDBSynchronizer() {
  	sqlbtest.startDBSynchronizer();
  }
  
  protected void startDBSynchronizer(){
    boolean useNewApi = true;
    Connection conn = getGFXDClientConnection();
    if (useNewApi) startNewDBSynchronizer(conn);
    else startDBSynchronizer(conn);
    commit(conn);
    closeGFEConnection(conn);
  }
  
  public static void HydraTask_populateTablesDBSynchronizer() {
  	sqlbtest.populateTablesDBSynchronizer();
  } 
  
  //use client driver connection
  protected void populateTablesDBSynchronizer() {
  	Connection gConn = getGFXDClientConnection();
  	populateTables(null, gConn); //pass dConn as null for DBSynchronizer
  	closeGFEConnection(gConn);
  }
  
  public static void HydraTask_doDMLOpDBSynchronizer() {
  	sqlbtest.doDMLOpDBSynchronizer();
  }
  
  //use client driver connection
  protected void doDMLOpDBSynchronizer() {
  	Connection gConn = getGFXDClientConnection();
  	doDMLOp(null, gConn); //do not operation on derby directly
  	closeGFEConnection(gConn);
  }
  
  public static void HydraTask_putLastKeyDBSynchronizer() {
  	sqlbtest.putLastKeyDBSynchronizer();
  }
  
  //to wait until the last key is in the derby database 
  protected void putLastKeyDBSynchronizer() {
  	Connection dConn = getDiscConnection();
  	Connection gConn = getGFXDClientConnection();
  	putLastKeyDBSynchronizer(dConn, gConn);  	
  	closeDiscConnection(dConn);
  	closeGFEConnection(gConn);
  }
  
  public static void  HydraTask_verifyResultSetsDBSynchronizer() {
  	sqlbtest.verifyResultSetsDBSynchronizer();
  }
  
  protected void verifyResultSetsDBSynchronizer() {
  	Connection dConn = getDiscConnection();
  	Connection gConn = getGFXDClientConnection();
  	verifyResultSets(dConn, gConn);  	
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  public static synchronized void HydraTask_createDiskStores() {
    sqlbtest.createDiskStores();
  }
  
  protected void createDiskStores() {
    Connection conn = getGFXDClientConnection();
    createDiskStores(conn);
    commit(conn);
    closeGFEConnection(conn);
  }
  
  
  public static void HydraTask_setMyConn() {
    sqlbtest.setMyConn();
  }
  
  protected Connection getMyGFXDClientConnection() {
    Connection conn = null;
    try {
      conn = sql.GFEDBClientManager.getMyConnection();
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException ("Not able to get the connection " + TestHelper.getStackTrace(e));
    }
    return conn;
  }
  
  protected void setMyConn() {
    Connection conn = getMyGFXDClientConnection();
    myConn.set(conn);    
  }
  
  public static void HydraTask_getQuery() {
    sqlbtest.getQuery();
  }
  
  protected void getQuery() {
    Connection conn = (Connection) myConn.get();        
    getConvertQuery(conn);    
  }
  
  protected void getConvertQuery(Connection conn) {
    PreparedStatement query;  
    try {
      String whichQuery = getQueries[random.nextInt(getQueries.length)];
      Log.getLogWriter().info(whichQuery);
      query = conn.prepareStatement(whichQuery);      
      executeQuery(query, conn);
    } catch (SQLException se) {
      throw new TestException ("could not execute query: " + TestHelper.getStackTrace(se));
    }
  }
  
  private void executeQuery(PreparedStatement query, Connection conn) {
    ResultSet rs = null;
    for (int i=0; i<100; i++) {
      try {
        int key = random.nextInt(300);
        query.setInt(1, key); 
        Log.getLogWriter().info("selected primary key is " + key);
        rs = query.executeQuery();
        ResultSetHelper.asList(rs, false);
      } catch (SQLException se) {
        throw new TestException ("could not execute query: " + TestHelper.getStackTrace(se));
      } finally {
        SQLHelper.closeResultSet(rs, conn);
      }
    }
  }

  public static void HydraTask_populateGetTablesForGet(){
    sqlbtest.populateTablesForGet();
  }
  
  //when no verification is needed, pass dConn as null
  protected void populateTablesForGet() {
    Connection gConn = (Connection) myConn.get();
    if (getMyTid() % 15 == 0) populateTables(null, gConn);   
  }
  
  public static void  HydraTask_verifyResultSets() {
    sqlbtest.verifyResultSets();
  }
  
  protected void verifyResultSets() {
    if (!hasDerbyServer) {
      Log.getLogWriter().info("skipping verification of query results "
          + "due to manageDerbyServer as false, myTid=" + getMyTid());
      return;
    }
    Connection dConn = getDiscConnection();
    Connection gConn = getGFXDClientConnection();
    verifyResultSets(dConn, gConn);   
    closeGFEConnection(gConn);
    closeDiscConnection(dConn);
  }
  
  /* only part of the work threads will verify scrollable rs
   * others will do dml operations.
   */
  public static void HydraTask_verifyScrollableResultSet() {
    int chance = 5;
    if (random.nextInt(chance) == 1)
      sqlbtest.verifyScrollableResultSet();
    else 
      sqlbtest.doDMLOp();
  }
  
  protected void verifyScrollableResultSet() {
    if (!hasDerbyServer) {
      Connection gConn = getGFXDClientConnection();
      verifyScrollableResultSet(gConn);
      commit(gConn);      
      closeGFEConnection(gConn);
      return;
    }
    else if (!testUniqueKeys) {
      Log.getLogWriter().info("Do not have derby server to verify results");
      return;
    }
    Connection dConn = getDiscConnection();
    Connection gConn = getGFXDClientConnection();
    verifyScrollableResultSet(dConn, gConn);
    
    commit(dConn);
    commit(gConn);
    
    closeGFEConnection(gConn);
    closeDiscConnection(dConn);
  }
  
  public static void HydraTask_alterTableGenerateDefaultId() {
    sqlbtest.alterTableGenerateDefaultId();
  }
  
  protected void alterTableGenerateDefaultId() {
    Connection gConn = getGFXDClientConnection();
    alterTableGenerateDefaultId(gConn);
    commit(gConn);
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_createUniqIndex() {
    sqlbtest.createUniqIndex();
  }
  

  public synchronized static void HydraTask_waitForReconnect() {
    // wait to be reconnected
    boolean reconnected = sqlbtest.waitForReconnect();
    if (reconnected) {
      SplitBrainBB.addReconnectedClient(RemoteTestModule.getMyVmid());
    } else {
      throw new TestException("Wait for reconnect returned false");
    }
  }  
  
  public synchronized static void HydraTask_verifyReconnect() {
    Set expectedDisconnectList = (Set) SplitBrainBB
        .getExpectForcedDisconnects();
    Set reconnectedClients = (Set) SplitBrainBB.getReconnectedList();
    StringBuffer aStr = new StringBuffer();
    // Verify that all clients reconnected
    if (!expectedDisconnectList.equals(reconnectedClients)) {
      aStr.append("Expected all clients to reconnect but only these did: "
          + reconnectedClients + ".   expected list is "
          + expectedDisconnectList + "\n");
    }
    if (aStr.length() > 0) {
      throw new TestException(aStr.toString());
    }
    sqlbtest.reconnected();
  }
  
  public static void HydraTask_verifyDMLExecution() {
    HydraTask_doDMLOp();
  }
  
  protected void reconnected() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    if (gConn == null || (hasDerbyServer && dConn == null)) {
      throw new TestException(
          "After reconnect connections were found to be null. dConn =  "
              + dConn + " gConn = " + gConn);
    }
  }
  
  public static void HydraTask_createCompaniesTable() {
    sqlbtest.createCompaniesTable();
  }
  
  public static void HydraTask_createIndexOnCompanies() {
    sqlbtest.createIndexOnCompanies();
  }
  
  public static void hydraTask_createUDTPriceFunctions() {
    sqlbtest.createUDTPriceFunctions();  
  }
  
  public static void HydraTask_createUDTPriceType() {
    sqlbtest.createUDTPriceType();
  }
  
  public static void HydraTask_createUUIDType() {
    sqlbtest.createUUIDType();
  }
  
  public static void HydraTask_dropCompaniesFK() {
    sqlbtest.dropCompaniesFK();
  }
  
  public static void HydraTask_addCompaniesFK() {
    if (ticket46803fixed) sqlbtest.addCompaniesFK();
  }
  
  public static void HydraTask_alterTableDropColumn() {
    sqlbtest.alterTableDropColumn();
  }
  
  public static void HydraTask_createTestTrigger() throws SQLException {
    sqlbtest.createTestTrigger();
  }
  
  protected void createTestTrigger() throws SQLException {
    Connection dConn = getDiscConnection();
    Connection gConn = getGFXDClientConnection();
    createTestTrigger(dConn, gConn);   
    closeDiscConnection(dConn);
    closeGFEConnection(gConn);
  }
  
  protected void createTestTrigger(Connection dConn, Connection gConn) throws SQLException{
    String createCustomerDupTable = "create table trade.customersDup as " +
    		"select * from trade.customers with no data";
    
    String createTestInsertTrigger = "CREATE TRIGGER TRADE.CUSTOMERS_INSERTTRIGGER AFTER INSERT ON TRADE.CUSTOMERS REFERENCING NEW AS NEWROW " +
    		"FOR EACH ROW INSERT INTO TRADE.CUSTOMERSDUP  VALUES ( NEWROW.CID ,  " +
    		"  NEWROW.CUST_NAME ,  NEWROW.SINCE ,  NEWROW.ADDR , NEWROW.TID )";
    
    String createTestDeleteTrigger = "CREATE TRIGGER TRADE.CUSTOMERS_DELETETRIGGER AFTER DELETE ON TRADE.CUSTOMERS REFERENCING OLD AS OLDROW " +
    "FOR EACH ROW DELETE FROM TRADE.CUSTOMERSDUP WHERE CID = OLDROW.CID";
    
    //avoid #50118
    String createGfxdCustomerDupTable = createTableCustomersStmt.replace("trade.customers", "trade.customersDup");
    
    if (dConn != null) {
      Log.getLogWriter().info("In derby, executing " + createCustomerDupTable );
      dConn.createStatement().execute(createCustomerDupTable);
      Log.getLogWriter().info("In derby, executed " + createCustomerDupTable );
      Log.getLogWriter().info("In derby, executing " + createTestInsertTrigger );
      dConn.createStatement().execute(createTestInsertTrigger );
      Log.getLogWriter().info("In derby, executed " + createTestInsertTrigger );
      Log.getLogWriter().info("In derby, executing " + createTestDeleteTrigger );
      dConn.createStatement().execute(createTestDeleteTrigger );
      Log.getLogWriter().info("In derby, executed " + createTestDeleteTrigger );
      commit(dConn);
    }
    Log.getLogWriter().info("In gfxd, executing " + createGfxdCustomerDupTable);
    gConn.createStatement().execute(createGfxdCustomerDupTable);
    Log.getLogWriter().info("In gfxd, executed " + createGfxdCustomerDupTable );
    Log.getLogWriter().info("In gfxd, executing " + createTestInsertTrigger );
    gConn.createStatement().execute(createTestInsertTrigger );
    Log.getLogWriter().info("In gfxd, executed " + createTestInsertTrigger );
    Log.getLogWriter().info("In gfxd, executing " + createTestDeleteTrigger );
    gConn.createStatement().execute(createTestDeleteTrigger );
    Log.getLogWriter().info("In gfxd, executed " + createTestDeleteTrigger );
    commit(gConn);
    
  }
  
  
  public static void HydraTask_cleanupOracleRun() throws SQLException {
    if (sqlbtest == null) {
      sqlbtest = new SQLBridgeTest();
    }
    sqlbtest.cleanupOracleRun();
  }
  
  public static void HydraTask_createOracleUsers() throws SQLException{
    sqlbtest.createOracleUsers();
  }
  
  public static void HydraTask_createOracleTables() throws SQLException{
    sqlbtest.createOracleTables();
  }
  
  public static void HydraTask_putLastKeyOracleDBSynchronizer() throws SQLException {
    sqlbtest.putLastKeyOracleDBSynchronizer();
  }
  
  protected void putLastKeyOracleDBSynchronizer() throws SQLException {
    Connection oraConn = getOracleConnection();
    Connection gConn = getGFXDClientConnection();
    putLastKeyDBSynchronizer(oraConn, gConn);
    closeConnection(oraConn);
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_verifyResultSetsOracleDBSynchronizer() throws SQLException{
    sqlbtest.verifyResultSetsOracleDBSynchronizer();
  }

  protected void verifyResultSetsOracleDBSynchronizer() throws SQLException {
    Connection oraConn = getOracleConnection();
    Connection gConn = getGFXDClientConnection();
    verifyResultSets(oraConn, gConn);
    closeConnection(oraConn);
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_stopOracleDBSynchronizer() throws SQLException{
    if (sqlbtest == null) {
      sqlbtest = new SQLBridgeTest();
    }
    sqlbtest.stopOracleDBSynchronizer();
  }

  protected void stopOracleDBSynchronizer() throws SQLException {
    Connection gConn = getGFXDClientConnection();
    stopOracleDBSynchronizer(gConn);
    closeGFEConnection(gConn);
  }
  
  public synchronized static void HydraTask_startFabricServer_Once() {
        if (fabricServerStarted.get() == false) {
          fabricServerStarted.set(true);
          sqlbtest.startFabricServer();
        }
    }
}
