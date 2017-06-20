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
package sql.wan;


import com.gemstone.gemfire.internal.AvailablePortHelper;
import com.gemstone.gemfire.cache.query.Struct;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.impl.sql.compile.CreateGatewaySenderNode;

import hydra.ClientVmInfo;
import hydra.ConfigHashtable;
import hydra.GatewayReceiverDescription;
import hydra.HostHelper;
import hydra.HydraRuntimeException;
import hydra.HydraVector;
import hydra.Log;
import hydra.MasterController;
import hydra.RemoteTestModule;
import hydra.TestConfig;
import hydra.blackboard.Blackboard;
import hydra.gemfirexd.FabricServerHelper;
import hydra.gemfirexd.GatewayReceiverHelper;
import hydra.gemfirexd.GatewaySenderPrms;
import hydra.gemfirexd.GfxdConfigPrms;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

import sql.ClientDiscDBManager;
import sql.DiscDBManager;
import sql.GFEDBClientManager;
import sql.GFEDBManager;
import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.ddlStatements.DDLStmtIF;
import sql.ddlStatements.FunctionDDLStmt;
import sql.sqlutil.ResultSetHelper;
import util.PRObserver;
import util.StopStartPrms;
import util.StopStartVMs;
import util.TestException;
import util.TestHelper;

public class WanTest extends sql.SQLTest {
  protected static WanTest wanTest = new WanTest();
  final static String diskStore = "WanDiskStore";
  protected static String sgSender = "sgSender";
  protected static String sgReceiver = "sgReceiver";
  private static final String SPACE = " ";
  
  public static ConfigHashtable conftab = TestConfig.tab();
  public static int myVMid = RemoteTestModule.getMyVmid(); 
  public static int numOfPeersPerSite = conftab.intAt(SQLWanPrms.numOfPeersPerSite, 0); //accessors + data store
  public static int numOfDataStoresPerSite = conftab.intAt(SQLWanPrms.numOfDataStoresPerSite, 0);
  public static int numOfLocators = conftab.intAt(SQLWanPrms.numOfLocators);
  public static int numOfAccessors = conftab.intAt(SQLWanPrms.numOfAccessors, 0);
  public static int numOfWanSites = conftab.intAt(SQLWanPrms.numOfWanSites);
  public static int numOfThreadPerVM = conftab.intAt(SQLWanPrms.numOfThreadsPerVM, 0);
  public static int numOfThreadsPerSite = conftab.intAt(SQLWanPrms.numOfThreadsPerSite, 0);
  public static int numOfAccessorThreadsPerSite = conftab.intAt(SQLWanPrms.numOfAccessorThreadsPerSite, 0);
  public static int numOfThreadPerStoreVM = conftab.intAt(SQLWanPrms.numOfThreadsPerStoreVM, 0);
  public static int numOfThreadsPerAccessorVM = conftab.intAt(SQLWanPrms.numOfThreadsPerAccessorVM, 0);
  public static boolean setupDataStore = false;
  public static boolean isWanAccessorsConfig = conftab.booleanAt(SQLWanPrms.isWanAccessorsConfig, false); 
  public static boolean enableQueueConflation = conftab.booleanAt(SQLWanPrms.enableQueueConflation, false);
  public static boolean manualStart = conftab.booleanAt(GatewaySenderPrms.manualStart, false);
  public static boolean isParallel = conftab.booleanAt(GatewaySenderPrms.isParallel, false);
  public static Blackboard bb = SQLBB.getBB();
  public static boolean isAccessor = false;
  public static boolean isStore = false;
  public static int derbyDDLThread = -1;
  public static int gfeDDLThread = -1; 
  //public static int myWanSite = (myVMid - numOfLocators)/numOfPeersPerSite + 1;
  public static int myWanSite = -1;
  public String sg;
  String gatewayID = "gatewayID" ;
  public static SQLWanBB wanBB = SQLWanBB.getBB();
  public static SQLWanSenderBB senderBB = SQLWanSenderBB.getBB();
  public static boolean useSamePartitionAllWanSites = conftab.booleanAt(SQLWanPrms.useSamePartitionAllWanSites, false);
  public static boolean isSingleSitePublisher = TestConfig.tab().
    booleanAt(sql.wan.SQLWanPrms.isSingleSitePublisher, false);
  public  static boolean isTicket43696Fixed=false;
  public static boolean isSender = false;
  public static boolean isAllToAll = false;
  public static boolean isSerial = false;
  public static boolean withChildSite = false;
  public static int childSite = -1;
  protected static Object indexLock = new Object();
  protected static boolean toCreateIndex = true;
  public static boolean useDefaultSenderSetting = TestConfig.tab().
  booleanAt(sql.wan.SQLWanPrms.useDefaultSenderSetting, false);
  
  
  //============================================================================
  // INITTASKS
  //============================================================================

  /**
   * Creates a (disconnected) locator.
   */
  public static void createLocatorTask() {
    FabricServerHelper.createLocator();
  }

  /**
   * Connects a locator to its (admin-only) distributed system.
   */
  public static void startAndConnectLocatorTask() {
    String networkServerConfig = GfxdConfigPrms.getNetworkServerConfig();
    if (networkServerConfig == null) {
      Log.getLogWriter().info("Starting peer locator only");
      FabricServerHelper.startLocator();
    } else {
      Log.getLogWriter().info("Starting network locator");
      FabricServerHelper.startLocator(networkServerConfig);
    }     
  }

  /**
   * Stops a locator.
   */
  public static void stopLocatorTask() {
    FabricServerHelper.stopLocator();
  }
  
  public static void stopServerTask() {
    FabricServerHelper.stopFabricServer();
  }
  
  public synchronized static void HydraTask_initialize() {
    wanTest.initialize();
    if (sqlTest == null) sqlTest = new SQLTest();
  }
  
  protected void initialize() {
    PRObserver.installObserverHook();
    PRObserver.initialize(RemoteTestModule.getMyVmid());
    //Log.getLogWriter().info("my client name is " + RemoteTestModule.getMyClientName());
    super.initialize();
  }
  
  //determine myWanSite, derbyDDLThread, gfeDDLThread, etc
  public static void HydraTask_initWanTest() {
    wanTest.initWanTest();
  }

  protected void initWanTest() { 
    if (numOfWanSites > 5) throw new TestException("test could not handle more than 5 wan sites yet");
    //if (numOfWanSites < 3) throw new TestException("test should have more than 2 wan sites");

    // in case of hdfs one first vm is used to start hadoop cluster
    if (hasHdfs) myVMid--;
    
    myWanSite = FabricServerHelper.getDistributedSystemId();
    if (myVMid < numOfAccessors ) {
      isAccessor = true;
      int whichOne =(int) bb.getSharedCounters().incrementAndRead(
          SQLBB.wanDerbyDDLThread);
      if (whichOne == 1) {
        derbyDDLThread = myTid();
        Log.getLogWriter().info("derbyDDLThread is " + derbyDDLThread);
      } //determine derby ddl thread,
    
      if ((myTid())%numOfAccessorThreadsPerSite == 0) {
        gfeDDLThread = myTid();
        Log.getLogWriter().info("gfeDDLThread is " + gfeDDLThread);
        
        Log.getLogWriter().info("testUniqueKeys is " + testUniqueKeys);
        Log.getLogWriter().info("withReplicateTables is " + TestConfig.tab().booleanAt(SQLPrms.withReplicatedTables, false));
      } //determine gfe ddl thread, each wan site has one
    }
    
    if (numOfAccessors == 0) throw new TestException("test issue, num of accessors must set in the conf");
    
    //computes sender in each site, will use only 2 in each site for serial sender
    if (myVMid >= numOfAccessors && myVMid < numOfAccessors + numOfDataStoresPerSite * numOfWanSites) {
      int storeId = myVMid - numOfAccessors;
      if(isParallel){
        isSender = true;
      }else if (storeId % numOfDataStoresPerSite == 0 || storeId % numOfDataStoresPerSite == 1)
        isSender = true;
    }
  }
  
  //init wan configuration settings, only one thread to execute this method
  public static void HydraTask_initBBForWanConfig() {
    if (myTid() == derbyDDLThread) {
      wanTest.initBBForWanConfig();
    }
  }
  
  protected void initBBForWanConfig() {
    boolean enableQueuePersistence = conftab.booleanAt(SQLWanPrms.enableQueuePersistence, false);     
    bb.getSharedMap().put("enableQueuePersistence", enableQueuePersistence);
    Log.getLogWriter().info("enableQueuePersistence is " + enableQueuePersistence);
    
    //setting topology
    int whichOne = random.nextInt(3);
    switch (whichOne) {
    case 0:         
      isAllToAll = true;        
      break;
    case 1:
      isSerial = true;
      break;
    case 2:
      withChildSite = true;
      childSite = random.nextInt(numOfWanSites) + 1;
      break;
    }
    wanBB.getSharedMap().put("isAllToAll", isAllToAll);
    wanBB.getSharedMap().put("isSerial", isSerial);
    wanBB.getSharedMap().put("withChildSite", withChildSite);
    wanBB.getSharedMap().put("childSite", childSite);
  }
  
  public static synchronized void HydraTask_startFabricServerTask() {
    wanTest.startFabricServerTask();
  }
  
  protected void startFabricServerTask() {
    if (isSender) startFabricServerSGSender();
    else startFabricServer();
  }
  
  public static synchronized void HydraTask_startFabricServerSGTask() {
    wanTest.startFabricServerSGTask();
  }
  
  protected void startFabricServerSGTask() {
    if (isSender) startFabricServerSGSender();
    else startFabricServerSG();
    /*
    boolean serverStarted = false;
    int sleepMs = 60000;
    do {
      try {
        if (isSender) startFabricServerSGSender();
        else startFabricServerSG();
        serverStarted = true;
      } catch (HydraRuntimeException e) {
        Log.getLogWriter().warning("not able to start fabric server " 
            + TestHelper.getStackTrace(e));
        MasterController.sleepForMs(sleepMs);
      }
    } while (!serverStarted);
    */
  }
  
  //write to bb for data stores to randomly chose from
  public static void HydraTask_initForServerGroup() {
    wanTest.initForServerGroup();
  }
  
  protected void initForServerGroup(){
    if (derbyDDLThread == myTid()) super.initForServerGroup();        
  }
  
  @SuppressWarnings("unchecked")
  protected String getSGForNode() {
    HydraVector vec = TestConfig.tab().vecAt(SQLPrms.serverGroups);
    int whichOne = -1;
    
    if (numOfDataStoresPerSite < 6) {
      throw new TestException ("not enough data node per site to satisfy all server group requirement" +
      		" must have at least 6 data node per site, but this test only has " + numOfDataStoresPerSite);
    }
    
    if (myVMid >= numOfAccessors && myVMid < numOfAccessors + numOfDataStoresPerSite * numOfWanSites) {
      int storeId = myVMid - numOfAccessors - 2; //2 senders
      whichOne = storeId % numOfDataStoresPerSite;
      Log.getLogWriter().info("which one is " + whichOne);
    } //calculate which one 
    
    if (whichOne >= vec.size()) {
      whichOne=vec.size()-1; //default to last element, if vms are more
    }

    String sg = (String)((HydraVector)vec.elementAt(whichOne)).elementAt(0); //get the item

    if (sg.startsWith("random")) {
      ArrayList<String> serverGroups = (ArrayList<String>) SQLBB.getBB().getSharedMap()
        .get("serverGroups");
      if(whichOne>=serverGroups.size()) {
        whichOne = random.nextInt(serverGroups.size()); //randomly select one
      }
      sg = serverGroups.get(whichOne); //a random server group except for the first 4 data stores
    }
    Log.getLogWriter().info("This data store is in " + sg);
    
    return sg;
  }
  
  /* not directly in conf now
  public static synchronized void HydraTask_startFabricServerSGSenderTask() {
    wanTest.startFabricServerSGSender();
  }
  */
  protected void startFabricServerSGSender() {
    //may need to set info for bring down sender vms or avoid HA test bring down senders
    
    Log.getLogWriter().info("Starting the fabric server");
    Properties p = FabricServerHelper.getBootProperties();
    if (p != null
        && "false".equalsIgnoreCase(p
            .getProperty(Attribute.GFXD_HOST_DATA))) {
      throw new TestException("test configure issue: " +
          "fabric server sg must be a data node");
    }
    p.setProperty("server-groups", sgSender);
    Log.getLogWriter().info("This data store is in " + sgSender);
    
    setClientVmInfoForSenderNode();
    
    startFabricServer(p);
  }
  
  //each wan site should have only 2 senders
  protected void setClientVmInfoForSenderNode() {
    ClientVmInfo target = new ClientVmInfo(RemoteTestModule.getMyVmid());
    if (target.getVmid() %2 == 0) {
      wanBB.getSharedMap().put("wan_" + myWanSite + "_sender1", target);
      Log.getLogWriter().info("wan_" + myWanSite + "_sender1: client vmID is " + target.getVmid());
      //restarted vm will have the same logic vmId.
    } else {
      wanBB.getSharedMap().put("wan_" + myWanSite + "_sender2", target);
      Log.getLogWriter().info("wan_" + myWanSite + "_sender2: client vmID is " + target.getVmid());
    }
  }
  
  
  /* not directly in conf
  public static synchronized void HydraTask_startFabricServerSGReceiverTask() {
    wanTest.startFabricServerSGReciever();
  }
  */
  
  protected void startFabricServerSGReciever() {
    //may need to set info for bring down sender vms or avoid HA test bring down senders
    
    Log.getLogWriter().info("Starting the fabric server");
    Properties p = FabricServerHelper.getBootProperties();   
    if (p != null
        && "false".equalsIgnoreCase(p
            .getProperty(Attribute.GFXD_HOST_DATA))) {
      throw new TestException("test configure issue: " +
          "fabric server sg must be a data node");
    }
    p.setProperty("server-groups", sgReceiver);
    Log.getLogWriter().info("This data store is in " + sgReceiver);
    
    startFabricServer(p);
  }
  
  public static synchronized void HydraTask_createGatewaySenders() {
    if (myTid() == gfeDDLThread) {
      wanTest.createGatewaySenders();
    }    
  }
  
  protected int getNextRemoteSiteId() {
    int myWanSiteId = myWanSite;
    return getNextRemoteSiteId(myWanSiteId);
  }
  
  protected int getNextRemoteSiteId(int myWanSiteId) {
    return ++myWanSiteId > numOfWanSites ? myWanSiteId % numOfWanSites : myWanSiteId;    
  }
  
  //returns a remote side id not same as the getNextRemoteSiteId()
  protected int getRandomRemoteSiteId() {
    int myWanSiteId = FabricServerHelper.getDistributedSystemId();
    return getRandomRemoteSiteId(myWanSiteId);
  }
  
  protected int getRandomRemoteSiteId(int myWanSiteId) { 
    //if (numOfWanSites < 3) throw new TestException ("test should have at least 3 wan sites");

    int nextWanSiteId = getNextRemoteSiteId(myWanSiteId);
    int randomRemoteSiteId = random.nextInt(numOfWanSites) + 1;
    while (randomRemoteSiteId == myWanSiteId || randomRemoteSiteId == nextWanSiteId ) {
      randomRemoteSiteId = random.nextInt(numOfWanSites) + 1;
    }
    return randomRemoteSiteId;    
  }
  
  
  protected void createGatewaySenders() {
    int [] remoteSiteIds = getRemoteSiteIds();
    int[] allIds = getAllSitesIds();
    if (remoteSiteIds == null) {
      isAllToAll = (Boolean) wanBB.getSharedMap().get("isAllToAll");
      isSerial = (Boolean) wanBB.getSharedMap().get("isSerial");
      withChildSite = (Boolean) wanBB.getSharedMap().get("withChildSite");
      childSite = (Integer)wanBB.getSharedMap().get("childSite");
      if (isAllToAll) Log.getLogWriter().info("isAllToAll is " + isAllToAll);
      if (isSerial) Log.getLogWriter().info("isSerial is " + isSerial);
      if (withChildSite) {
        Log.getLogWriter().info("withChildSite is " + withChildSite);
        Log.getLogWriter().info("child site is " + childSite);
      }
            
      if (isSerial) createGatewaySender(getNextRemoteSiteId()); //each site send to one site.
      if (isAllToAll) {        
        for (int id: allIds) {
          if (id != myWanSite) createGatewaySender(id); 
        }  
      } 
      if (withChildSite) {   
        int parentSite = (numOfWanSites == childSite) ? 1 : childSite + 1;
        if (myWanSite == childSite) {
          createGatewaySender(getNextRemoteSiteId()); //child site only connect to parent
        } else if (myWanSite == parentSite) {
          for (int id: allIds) {
            if (id != myWanSite) createGatewaySender(id); //parent connect to all site, including child
          }  
        } else {
          for (int id: allIds) {
            if (id != myWanSite && id!= childSite) 
              createGatewaySender(id); //others connect to all site, except for child
          }  
        }
      }
    } else {
      for (int id: remoteSiteIds) {
        createGatewaySender(id); 
      }
    }
  }
  
  protected int[] getAllSitesIds() {
    int [] ids = new int[numOfWanSites];
    for (int i=0; i<numOfWanSites; i++) 
      ids[i] = i+1;
    return ids;
  }
  
  @SuppressWarnings("unchecked")
  protected int[] getRemoteSiteIds() {
    Vector<String> remoteSiteIds = TestConfig.tab().vecAt(SQLWanPrms.mineToRemoteId, new HydraVector());
    if (remoteSiteIds.size() == 0 ) return null; //randomly choose remote site id
    else {
      String thisWanRemoteIds = remoteSiteIds.elementAt(myWanSite-1); //wan site is 1 based
      Log.getLogWriter().info("thisWanRemoteIds is " + thisWanRemoteIds);
      if (thisWanRemoteIds.startsWith("random")) return null; //randomly choose remote site id
      else {
        String s = thisWanRemoteIds.replaceAll(" ", "");
        String[] ids = s.split(",");
        int [] rids = new int[ids.length];
        for (int i = 0; i< ids.length; i++) {
          rids[i] = Integer.parseInt(ids[i]);
        }
        return rids;
      }
    }
  }
  
  
  protected void createGatewaySender(int remoteSiteId) {
    String ddl = getSenderDDL(remoteSiteId);
    
    Connection conn = getGFEConnection();
    try {
      Log.getLogWriter().info("executing " + ddl);
      conn.createStatement().execute(ddl);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    commit(conn);
    closeGFEConnection(conn);
  }
  
  @SuppressWarnings("unchecked")
  protected String getSenderDDL(int remoteDSID) {
    String senderID = "sender" + "_" + myWanSite + "_" + remoteDSID;
    
    //add sender id to wanBB for create table statement
    ArrayList<String> senderIDs = (ArrayList<String>)wanBB.getSharedMap().get(myWanSite+"_senderIDs");
    if (senderIDs == null) senderIDs = new ArrayList<String>();
    senderIDs.add(senderID);
    wanBB.getSharedMap().put(myWanSite+"_senderIDs", senderIDs);
        
    StringBuilder buf = new StringBuilder();
    //String senderConfig = " "; //using default for now /*random.nextBoolean()? " ": getSenderConfig(); */
    
    StringBuilder parallelSenderConfig = new StringBuilder();
    if (!(!isParallel && random.nextBoolean())) { //configure serial sender using "isparallel false" sometimes.
      parallelSenderConfig.append(CreateGatewaySenderNode.ISPARALLEL)
          .append(SPACE).append(isParallel).append(SPACE);
    }
    
    String senderConfig = (useDefaultSenderSetting || random.nextBoolean()) ? " ": getSenderConfig(); 
    buf.append("CREATE GATEWAYSENDER ")
       .append(senderID)
       .append(" ( ")
       .append("remotedsid").append(SPACE)
       .append(remoteDSID).append(SPACE)
       .append(senderConfig)
       .append(parallelSenderConfig.toString())
       .append(")");
    //add server group
    buf.append(" SERVER GROUPS ( ")
         .append(sgSender).append(" )");    
    return buf.toString();  
  }
  
  protected String getSenderConfig() {
    StringBuilder buf = new StringBuilder();
    int socketReadTimeOut = conftab.intAt(GatewaySenderPrms.socketReadTimeout, 
        random.nextInt(10000) + 5000); //defualt 10000 ms
    int batchSize = random.nextInt(200) + 50; //default 100 msg
    int batchTimeInterval = random.nextInt(2000) + 500; //default 1000 ms
    boolean enableQueuePersistence = (Boolean)bb.getSharedMap().get("enableQueuePersistence");
    boolean diskSynchronous = random.nextBoolean();
    int maxQueueMemory = random.nextInt(200) + 10; //default 100 mb
    int alertThreashold = random.nextInt(60000) + 60000; //in ms
    
    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.SOCKETREADTIMEOUT).append(SPACE)
       .append(socketReadTimeOut).append(SPACE);
    
    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.MANUALSTART).append(SPACE)
       .append(manualStart).append(SPACE)  ;  
       
    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.ENABLEBATCHCONFLATION).append(SPACE)
       .append(enableQueueConflation).append(SPACE);
    
    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.BATCHSIZE).append(SPACE)
       .append(batchSize).append(SPACE);
    
    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.BATCHTIMEINTERVAL).append(SPACE)
       .append(batchTimeInterval).append(SPACE);
       
    if (enableQueuePersistence || random.nextBoolean())
      buf.append(CreateGatewaySenderNode.ENABLEPERSISTENCE).append(SPACE)
        .append(enableQueuePersistence).append(SPACE);
    
    if (enableQueuePersistence) {
      if (random.nextBoolean()) {
        buf.append(CreateGatewaySenderNode.DISKSYNCHRONOUS).append(SPACE)
            .append(diskSynchronous).append(SPACE);
      } else {
        Log.getLogWriter().info("Gateway sender DISKSYNCHRONOUS uses default");
      }
    }
    
    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.DISKSTORENAME).append(SPACE)
      .append(diskStore).append(SPACE);

    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.MAXQUEUEMEMORY).append(SPACE)
        .append(maxQueueMemory).append(SPACE);
    
    if (random.nextBoolean())
      buf.append(CreateGatewaySenderNode.ALERTTHRESHOLD).append(SPACE)
        .append(alertThreashold).append(SPACE);
    
    return buf.toString();
  }
  
  public static synchronized void HydraTask_createGatewayReceivers() {
    if (myTid() == gfeDDLThread) {
      wanTest.createGatewayReceivers();
    }    
  }
    
  //which server group receivers should be on
  protected void createGatewayReceivers() {
    //using sender server group same as receiver group
    createGatewayReceivers(sgSender);
  }
  
  protected void createGatewayReceivers(Connection conn, String sg) {
    try {
      Statement stmt = conn.createStatement();
      List<String> ddls = GatewayReceiverHelper.getGatewayReceiverDDL(myWanSite);
      for (String ddl : ddls) {
        Log.getLogWriter().info("Executing " + ddl + " SERVER GROUPS (" + sg + ")");
        stmt.execute(ddl + " SERVER GROUPS (" + sg + ")");
        Log.getLogWriter().info("Executed " + ddl + " SERVER GROUPS (" + sg + ")");
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void createGatewayReceivers(String sg) {
    Connection gConn = getGFEConnection();
    createGatewayReceivers(gConn, sg);
    closeGFEConnection(gConn);
  }

  public static synchronized void HydraTask_createGFEDataStore() {
    wanTest.createGFEDataStore();
  }
  
  public static synchronized void HydraTask_createGFXDDB() {
    wanTest.createGFXDDB();
  }

  //needs only one thread to setup (multiple threads for a node is data store node and querying)
  @Override
  protected void createGFEDataStore() {
    if (setupDataStore == false) {
      setupDataStore();     
      setupDataStore = true;
    }        
  }
  
  // declare which server group the data store node is in
  // for test purpose (same port number within a wan site, each node need to be in 
  // different server group to setup gateway hub without conflict
  protected void setupDataStore() {     
    if (!isWanAccessorsConfig) {
      sg  = "SG" + ((myVMid-numOfLocators) % numOfPeersPerSite +1); //for nodes in both client/server roles
    } else {
      sg = "SG" + ((myVMid-numOfLocators-numOfAccessors) % (numOfDataStoresPerSite) +1);
    }
        
    Properties info = getGemFireProperties();   
    info.setProperty("host-data", "true");
    Log.getLogWriter().info("This data store is in " + sg);
    
    /* we do not need to set up the server group properties if 
     * this server is in default/all server group
     */
    info.setProperty("server-groups", sg); 
    startGFXDDB(info);
  }
  
  public static void HydraTask_setUpGatewayHub() {
    if (myTid() == gfeDDLThread) {
      wanTest.createGatewayHub();
    }
    //each site has one thread creates hub, which is distributed to all nodes in the sg
    //it needs to have same port # to distribute to other nodes in the sg
    //to have hubs in all the node/vm, need to call procedures multiple times and with
    //different sever groups for hydra test purpose
    //may need to expand to multiple hosts so that each sg could have the same port and 
    //only one procedure call is needed
  }
  
  public static void HydraTask_addGateway() {
    if (myTid() == gfeDDLThread) {
      wanTest.addGateway();
      wanTest.addGatewayEndPoint();
    } //each site has one thread creates hub, which is distributed to all nodes in the site
  }
  
  public static void HydraTask_startGatewayHub() {
    if (myTid() == gfeDDLThread) {
      wanTest.startGatewayHub();
    } //each site has one thread creates hub, which is distributed to all nodes in the site
  }
  
  public static void HydraTask_startGateway() {
  //    TODO, call the procedure no matter whether gateway is started or not in startGTWHub
  // and should get expected exception if the gateway already started  
  }
  
  //each vm will have its own server group
  protected void createGatewayHub(){
    Connection conn = getGFEConnection();
    String hubID = "hub_" + myWanSite;     
    String hostname = HostHelper.getLocalHost();  
    //createGatewayHub(conn, hubID, hostname, sg);
    
    //TODO to create hub in more than one vm/node based on gateway HA

    String sg = null;
    for (int i=1; i<numOfDataStoresPerSite+1; i++) {    
      sg = "SG" + i;
      createGatewayHub(conn, hubID, hostname, sg, i);
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void createGatewayHub(Connection conn, String hubID, 
      String hostname, String sg, int portIndex) {
    int hubPort = -1;
    int[] freeTCPPorts = null;
    try {
            
      CallableStatement cs = conn
          .prepareCall("CALL SYS.ADD_GATEWAY_HUB(?,?, ?,?,?,?,?)");
      if (wanBB.getSharedCounters().incrementAndRead(SQLWanBB.synchWanEndPorts) ==1) {
        //only one thread will add wan ports to bb
        freeTCPPorts = AvailablePortHelper.getRandomAvailableTCPPorts(numOfWanSites * numOfDataStoresPerSite);
        SQLWanBB.getBB().getSharedMap().put("wanEndPoints", freeTCPPorts);
        StringBuilder ports = new StringBuilder();
        for (int port: freeTCPPorts) {
          ports.append(port + ", ");
        }
        Log.getLogWriter().info("Free TCP ports are :" + ports.toString());
      } 
      freeTCPPorts = (int[]) SQLWanBB.getBB().getSharedMap().get("wanEndPoints");
      while (freeTCPPorts == null){
        MasterController.sleepForMs(300);
        freeTCPPorts = (int[]) SQLWanBB.getBB().getSharedMap().get("wanEndPoints");
      }
      hubPort = freeTCPPorts[(myWanSite - 1) * numOfDataStoresPerSite +  (portIndex-1)];  
      //myWanSite starts from 1, each data store (different SG) in the site 
      //portIndex starts from 1
      
      //cs.setString(1, serverGroup.toString());
      cs.setString(1, sg);//
      cs.setString(2, hubID);
      cs.setInt(3, hubPort);
      cs.setNull(4, Types.INTEGER);
      cs.setNull(5, Types.INTEGER);
      
      int startupPolicy = random.nextInt(3);
      if (startupPolicy ==0) cs.setNull(6, Types.VARCHAR);
      else if (startupPolicy == 1) cs.setString(6, "primary");
      else {
        cs.setString(6, "secondary");
        Log.getLogWriter().info("trying to become secondaary");
      }
      
      //manual start
      if (random.nextBoolean()) cs.setNull(7, Types.BOOLEAN);
      else {
        cs.setBoolean(7, true);
        Log.getLogWriter().info("Need to manually start hub");
      }
      cs.execute();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    ArrayList<Integer> hubPorts = (ArrayList<Integer>) bb.getSharedMap().get("portID_wanSite"+myWanSite);
    if (hubPorts == null) hubPorts = new ArrayList<Integer>();
    hubPorts.add(hubPort);
    bb.getSharedMap().put("portID_wanSite"+myWanSite, hubPorts); //setup port number
    bb.getSharedMap().put("hostname_wanSite"+myWanSite, hostname);  //for multi hosts
    Log.getLogWriter().info("my wan site is " + myWanSite);
    //Log.getLogWriter().info("server group that hub is created on is " + serverGroup.toString());
    Log.getLogWriter().info("server group that hub is created on is " + sg);
    Log.getLogWriter().info("the hub created on port " + hubPort);
  }
  
  protected void addGateway() {
    //setting gateway queue
    Connection conn = getGFEConnection();    
    boolean enableQueuePersistence = (Boolean)bb.getSharedMap().get("enableQueuePersistence");
    Log.getLogWriter().info("adding Gateway");
   
    for (int i=1; i<= numOfWanSites; i++) {
      if (i != myWanSite) {
        try {
          CallableStatement cs1 = conn.prepareCall("CALL SYS.ADD_GATEWAY(?, ?)");
          CallableStatement cs2 = conn.prepareCall("CALL SYS.CONFIGURE_GATEWAY_QUEUE(?,?,?,?,?,?,?,?,?)");
  
          cs1.setString(1, "hub_" + myWanSite);
          cs1.setString(2, gatewayID+i);
          cs1.execute();
          commit(conn);
          Log.getLogWriter().info("gateway " + gatewayID+i + " is created for hub_" + myWanSite);
               
          cs2.setString(1, "hub_" + myWanSite);
          cs2.setString(2, gatewayID+i);
          cs2.setNull(3,Types.INTEGER); //BATCH_SIZE
          cs2.setNull(4,Types.INTEGER); //BATCH_TIME_INTERVAL
          cs2.setBoolean(5,enableQueueConflation);  //BATCH_CONFLATION, default to false -- can be used to check listener invocation
          cs2.setNull(6, Types.INTEGER);  //MAXIMUM_QUEUE_MEMORY in megabytes
          if (random.nextBoolean()) {
            cs2.setNull(7, Types.VARCHAR); //DIR_PATH
            Log.getLogWriter().info("no specific diskstore provided");
          }
          else {
            cs2.setString(7, diskStore);
          }
          cs2.setBoolean(8, enableQueuePersistence);  //ENABLE_PERSISTENCE   
          cs2.setNull(9, Types.INTEGER); //ALTRERT_THRESHOLD default is 0
          cs2.execute();          
          commit(conn);
          Log.getLogWriter().info("Gateway and gateway queue are added");
        } catch (SQLException se) {
         SQLHelper.handleSQLException(se);
        }
      }
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void addGatewayEndPoint() {
  Connection conn = getGFEConnection();
  String gatewayID = "gatewayID" ;
  Log.getLogWriter().info("adding Gateway end point");
  try {
    CallableStatement cs = conn.prepareCall("CALL SYS.ADD_GATEWAY_ENDPOINT(?, ?,?,?)");
    for (int i = 1; i<= numOfWanSites; i++ ) {
      if (myWanSite != i) {
        ArrayList<Integer> ports = (ArrayList<Integer>) bb.getSharedMap().get("portID_wanSite"+i);
        for (int port: ports) {
          String hostname = (String)bb.getSharedMap().get("hostname_wanSite"+i);
          cs.setString(1, "hub_" + myWanSite);
          cs.setString(2, gatewayID+i);           
          cs.setString(3, hostname);
          cs.setInt(4, port);
          cs.execute();
          commit(conn);
          Log.getLogWriter().info("added end point for hub_" + myWanSite + ": remote hubID: hub_" + i 
              + " port#: " + port);
        }
      } //wan site starts with 1    
    }
  } catch (SQLException se) {
    SQLHelper.handleSQLException(se);
  }  
  }
  
  protected void startGatewayHub() {
    Connection conn = getGFEConnection();
    Log.getLogWriter().info("starting the gateway hub_" + myWanSite);
    try {
      CallableStatement cs = conn.prepareCall("CALL SYS.START_GATEWAY_HUB(?,?)");
      cs.setString(1, "hub_" + myWanSite);
      cs.setBoolean(2, true);  //TODO, to test stratGatway when set as false (not starting gateway
      cs.execute();
      Log.getLogWriter().info("gateway hub_" + myWanSite +" is started.");
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  @Override
  @SuppressWarnings("unchecked")
  protected void createTables(Connection conn) {
    String driver; 
    //gfe and derby use same drivers, it could be used when client server driver is used in gfe.
    String url;
  
    try {
      driver = conn.getMetaData().getDriverName();
      url = conn.getMetaData().getURL();
      Log.getLogWriter().info("Driver name is " + driver + " url is " + url);
    } catch (SQLException se) {
      throw new TestException("Not able to get driver name" + TestHelper.getStackTrace(se));
    }
    
    //to get creat table statements from config file
    String[] derbyTables = SQLPrms.getCreateTablesStatements(true);
    String[] gfeDDL = null;
    
    try {
      Statement s = conn.createStatement();
      if (url.equals(DiscDBManager.getUrl()) || url.equals(ClientDiscDBManager.getUrl())) {
        for (int i =0; i<derbyTables.length; i++) {
          Log.getLogWriter().info("about to create table " + derbyTables[i]);
          s.execute(derbyTables[i]);  
          Log.getLogWriter().info("created table " + derbyTables[i]);
        }
      } else if (url.equals(GFEDBManager.getUrl())
          || url.startsWith(GFEDBClientManager.getProtocol())
          || url.startsWith(GFEDBClientManager.getDRDAProtocol())) {
        if (!testPartitionBy) gfeDDL = SQLPrms.getGFEDDL(); //use config
        else {
          while (wanBB.getSharedCounters().incrementAndRead(SQLWanBB.synchWanSiteParitionKeys)!=1) {
            Log.getLogWriter().info("waiting for synchWanSiteParitionKeys to become 1");
            MasterController.sleepForMs(500);
          }
          if (!useSamePartitionAllWanSites) {
            gfeDDL=getGFEDDLPartition(); //get gfeDDL    
          } else {
            if (wanBB.getSharedMap().get("gfeDDL") ==null) {
              //no one creates any tables yet
              gfeDDL=getGFEDDLPartition(); //get gfeDDL      
              wanBB.getSharedMap().put("gfeDDL", gfeDDL);
            } else {
              //use the existing info
              gfeDDL = (String[]) wanBB.getSharedMap().get("gfeDDL");   
            }            
          }
          writePartitionForWanSites();
          wanBB.getSharedMap().put("numOfPRs" + myWanSite, 
              SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs));
          Log.getLogWriter().info("numOfPRs is " + 
              SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs));
          Log.getLogWriter().info("put numOfPRs into wanBB for " + myWanSite);
          wanBB.getSharedCounters().zero(SQLWanBB.synchWanSiteParitionKeys);
        }
        
        // create hdfs
        if (hasHdfs) { 
          Log.getLogWriter().info("creating hdfs extn...");
          gfeDDL = SQLPrms.getHdfsDDL(gfeDDL);
          
          // Add HDFS queue regions to numOfPRs
          long numOfPRs = SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs);
          SQLBB.getBB().getSharedCounters().add(SQLBB.numOfPRs, numOfPRs);
          Log.getLogWriter().info("numOfPRs after adding HDFS regions is "
                  + SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs));
        }
        
        for (int i =0; i<gfeDDL.length; i++) {
          String ddl = gfeDDL[i].toUpperCase();
          if (ddl.contains("REPLICATE")) {
            if (ddl.contains("SERVER GROUPS") && !ddl.contains(sgSender)) {
              int index = ddl.indexOf("SERVER GROUPS");
              StringBuffer start = new StringBuffer((gfeDDL[i].substring(0, index-1)));
              StringBuffer sb = new StringBuffer(gfeDDL[i].substring(index));
              sb.insert(sb.indexOf("SG"), sgSender + ",");
              gfeDDL[i] = (start.append(sb)).toString();
            }

            if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
                && !((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
                equalsIgnoreCase("default")) {
              gfeDDL[i] += " SERVER GROUPS (" + sgSender + "," +
                SQLBB.getBB().getSharedMap().get(tradeSchemaSG) + ") ";
            }  else if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
                && ((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
                equalsIgnoreCase("default")) {
              gfeDDL[i] += " SERVER GROUPS (" + sgSender + ",SG1,SG2,SG3,SG4) ";
            }//inherit from schema server group
          } //handle replicate tables for sg
          
          ArrayList<String> senderIDs = (ArrayList<String>)wanBB.getSharedMap().get(myWanSite+"_senderIDs");
          if (senderIDs == null) throw new TestException("senderIDs are not setting yet for creating tables");

          StringBuilder senders = new StringBuilder();
          senders.append(" GATEWAYSENDER(");
          for (int j =0; j<senderIDs.size(); j++) {
            senders.append(senderIDs.get(j));
            if (j<senderIDs.size()-1) senders.append(", ");
          }
          senders.append(")");

          // enable offheap
          if (isOffheap && randomizeOffHeap) {
            throw new TestException("SqlPrms.isOffheap and SqlPrms.randomizeOffHeap are both set to true");
          }
          if (isOffheap){
            Log.getLogWriter().info("enabling offheap." );
            for (int index =0; index<gfeDDL.length; index++) {
              if (gfeDDL[index].toLowerCase().indexOf(SQLTest.OFFHEAPCLAUSE.toLowerCase()) < 0) { // don't add twice
                gfeDDL[index] += OFFHEAPCLAUSE;
              }
            }          
          }
          if (randomizeOffHeap) {
            Log.getLogWriter().info("Randomizing off-heap in some tables but not others");
            for (int index =0; index<gfeDDL.length; index++) {
              if (gfeDDL[index].toLowerCase().indexOf(SQLTest.OFFHEAPCLAUSE.toLowerCase()) < 0) { // don't add twice
                if (TestConfig.tab().getRandGen().nextInt(1, 100) <= 50) {
                  gfeDDL[index] += OFFHEAPCLAUSE;
                }
              }
            }
          }
          
          /*
            Log.getLogWriter().info("about to create table " + gfeDDL[i] 
              + "HUB('hub_" + myWanSite + "')");
            s.execute(gfeDDL[i] + "HUB('hub_" + myWanSite + "')");  
            Log.getLogWriter().info("created table " + gfeDDL[i] 
              + "HUB('hub_" + myWanSite + "')");
          */
          Log.getLogWriter().info("about to create table " + gfeDDL[i] 
             + senders.toString());
          s.execute(gfeDDL[i] + senders.toString());  
          Log.getLogWriter().info("created table " + gfeDDL[i] 
             + senders.toString());
        }
      } else {
        throw new TestException("Got incorrect url or setting.");
      }
      s.close();
      commit(conn);  
    } catch (SQLException se) {
        SQLHelper.printSQLException(se);
        throw new TestException ("Not able to create tables\n" 
            + TestHelper.getStackTrace(se));
    }   
    
    //create index on derby table
    if (url.equals(DiscDBManager.getUrl()) || url.equals(ClientDiscDBManager.getUrl())) {
      createDerbyIndex(conn);
    }
  }
  
  //each site has its own partition key
  protected void writePartitionForWanSites() {
    Log.getLogWriter().info("buyordersPartition is :" + SQLBB.getBB().getSharedMap().get("buyordersPartition"));
    Log.getLogWriter().info("customersPartition is :" + SQLBB.getBB().getSharedMap().get("customersPartition"));
    Log.getLogWriter().info("networthPartition is :" + SQLBB.getBB().getSharedMap().get("networthPartition"));
    Log.getLogWriter().info("portfolioPartition is :" + SQLBB.getBB().getSharedMap().get("portfolioPartition"));
    Log.getLogWriter().info("securitiesPartition is :" + SQLBB.getBB().getSharedMap().get("securitiesPartition"));
    Log.getLogWriter().info("sellordersPartition is :" + SQLBB.getBB().getSharedMap().get("sellordersPartition"));
    Log.getLogWriter().info("txhistoryPartition is :" + SQLBB.getBB().getSharedMap().get("txhistoryPartition"));
    
    wanBB.getSharedMap().put(myWanSite+"_buyordersPartition", 
        SQLBB.getBB().getSharedMap().get("buyordersPartition"));
    wanBB.getSharedMap().put(myWanSite+"_customersPartition", 
        SQLBB.getBB().getSharedMap().get("customersPartition"));
    wanBB.getSharedMap().put(myWanSite+"_networthPartition", 
        SQLBB.getBB().getSharedMap().get("networthPartition"));
    wanBB.getSharedMap().put(myWanSite+"_portfolioPartition", 
        SQLBB.getBB().getSharedMap().get("portfolioPartition"));
    wanBB.getSharedMap().put(myWanSite+"_securitiesPartition", 
        SQLBB.getBB().getSharedMap().get("securitiesPartition"));
    wanBB.getSharedMap().put(myWanSite+"_sellordersPartition", 
        SQLBB.getBB().getSharedMap().get("sellordersPartition"));
    wanBB.getSharedMap().put(myWanSite+"_txhistoryPartition", 
        SQLBB.getBB().getSharedMap().get("txhistoryPartition"));    
  }
        
  //determine the thread will do derby DDL ops
  public static void HydraTask_createDiscDB() {
    if (derbyDDLThread == myTid()) {
      wanTest.createDiscDB();
    }
  }
  
  public static void HydraTask_createDiscSchemas() {
    if (derbyDDLThread == myTid()) {
      wanTest.createDiscSchemas();
    }
  }
  
  public static void HydraTask_createDiscTables() {
    if (derbyDDLThread == myTid()) {
      wanTest.createDiscTables();
    }
  }
  
  public static void HydraTask_createGFEDBForAccessors() {
      wanTest.createGFEDBForAccessors(); //access to gfe connection
  }
  
  public static void HydraTask_createGFESchemas() {     
    if (myTid() == gfeDDLThread) {
      wanTest.createGFESchemas();
    }
  }
  
  protected void createGFESchemas() {
    Connection conn = getGFEConnection();
    createGFESchemas(conn);  
    commit(conn);    
    closeGFEConnection(conn);
  }
    
  protected void createGFESchemas(Connection conn) {
    
    Log.getLogWriter().info("creating schemas in gfe.");
    if (!testServerGroupsInheritence) createSchemas(conn);
    else {
      while (wanBB.getSharedCounters().incrementAndRead(SQLWanBB.synchWanSiteSchemas)!=1) {
        Log.getLogWriter().info("waiting for synchWanSiteParitionKeys to become 1");
        MasterController.sleepForMs(500);
      }
      if (SQLBB.getBB().getSharedMap().get(SQLTest.tradeSchemaSG) == null) {
        //no one creates the schema yet
        String[] schemas = SQLPrms.getGFESchemas(); //with server group
        createSchemas(conn, schemas);
      } else {
        //use the existing info
        String sg = (String) SQLBB.getBB().getSharedMap().get(SQLTest.tradeSchemaSG);  
        
        String[] schemas = SQLPrms.getSchemas();        
        
        if (!sg.equals("default")) {
          schemas[0] += " DEFAULT SERVER GROUPS (" +sg +") ";  //only if it uses default server groups
        } else if ((SQLTest.hasAsyncDBSync || SQLTest.isWanTest) && SQLTest.isHATest) {
          schemas[0] += " DEFAULT SERVER GROUPS (SG1,SG2,SG3,SG4) ";
         //this is to ensure that vm with DBSynchronizer does not partitioned region/tables
        } else {
          //do nothing -- create schema on default (every data node)
        } //schema[0] is for trade schema
       
        createSchemas(conn, schemas);
      }
      wanBB.getSharedCounters().zero(SQLWanBB.synchWanSiteSchemas);
    }
    Log.getLogWriter().info("done creating schemas in gfe."); 
  }
  
  public static void HydraTask_createDiskStores() {
    if (myTid() == gfeDDLThread)
      wanTest.createDiskStores();
  }
  
  public static void HydraTask_createGFETables() {      
    if (myTid() == gfeDDLThread) {
      wanTest.createGFETables();
    }
  }
  
  protected void createGFETables() {
    if (testPartitionBy && !useSamePartitionAllWanSites) {
      //need to create table synchronously to avoid partitioned key setting by another site
      getLock();
      super.createGFETables();
      releaseLock();      
    } else
      super.createGFETables();
  }

  protected static int myTid() {
    return RemoteTestModule.getCurrentThread().getThreadId();
  }

    
  public static void HydraTask_checkQueueEmpty() {
    wanTest.checkQueueEmpty();
  }

  protected void checkQueueEmpty() {
    /* no longer use hub, using gateway senders instead now
    int sleepMs = 10;
    List hubs = GemFireCacheImpl.getInstance().getGatewayHubs();
      if(hubs.isEmpty()) {
        Log.getLogWriter().info("no hubs available");
        return;
      } 
      GatewayHub hub = (GatewayHub)hubs.get(0);
      Iterator it = hub.getGateways().iterator();
      while (it.hasNext()) {
        final Gateway gateway = (Gateway)it.next();
        while (!gateway.isRunning() && gateway.getQueueSize() != 0) {
          Log.getLogWriter().info("waiting for queue to be empty, current queue size is " + gateway.getQueueSize()); 
          hydra.MasterController.sleepForMs(sleepMs);
        }
        Log.getLogWriter().info("queue size is " + gateway.getQueueSize() + 
                        " for gateway: " + gateway.getId());
      }
      */
    
    if (isSender) checkGatewayQueueEmpty(); //only sender has gateway queues
  }

  public static void HydraTask_verifyPublisherResultSets() {
    wanTest.verifyResultSets();
  }

  public static void HydraTask_verifyResultSets() {
    wanTest.verifyResultSets();
  }
  
  @Override
  protected void verifyResultSets() {
    if (myTid()== gfeDDLThread) {
      int sleepMs = 100;
      while (!isLastKeyArrived()) {
        Log.getLogWriter().info("last key is not arrived yet, sleep for " + sleepMs + " ms");
        hydra.MasterController.sleepForMs(sleepMs);             
      } 
      
      //verifyResult after the last key is arrived
      super.verifyResultSets();     
    }
  }
  
  protected void verifyResultSetsFromBB() {
    Connection gConn = getGFEConnection(); 
    verifyResultSetsFromBB(gConn);
    closeGFEConnection(gConn);
  }
  
  @SuppressWarnings("unchecked")
  protected void verifyResultSetsFromBB(Connection gConn) {
    if (myTid() == gfeDDLThread) {
      int sleepMs = 5000;
      while (!isLastKeyArrived()) {
        Log.getLogWriter().info("last key is not arrived yet, sleep for " + sleepMs + " ms");
        hydra.MasterController.sleepForMs(sleepMs);             
      } 
      
      //verifyResult after the last key is arrived       
      List<Struct> myList = null;
      List<Struct> baseSiteList = null; //one of the sites as the base.
      int baseWanSite = (Integer) wanBB.getSharedMap().get("baseWanSite");
      Log.getLogWriter().info("comparing data from my wan site " + myWanSite + " with wan site " + baseWanSite);
      boolean throwException = false;
      StringBuffer str = new StringBuffer();
      try {
        ResultSet rs = gConn.createStatement().executeQuery("select tableschemaname, tablename "
            + "from sys.systables where tabletype = 'T' and tableschemaname not like 'SYS%'");
        while (rs.next()) {
          String schemaName = rs.getString(1);
          String tableName = rs.getString(2);
          myList = getResultSet(gConn, schemaName, tableName);
          baseSiteList = (List<Struct>)wanBB.getSharedMap().get(schemaName+"_"+tableName);
          
          try {
            Log.getLogWriter().info(ResultSetHelper.listToString(myList));
            Log.getLogWriter().info("comparing result set for table " + schemaName+"."+tableName);
            ResultSetHelper.compareResultSets(baseSiteList, myList, "wanSite" + baseWanSite, "wanSite"+myWanSite);
          }catch (TestException te) {
            Log.getLogWriter().info("do not throw Exception yet, until all tables are verified");
            throwException = true;
            str.append(te.getMessage() + "\n");
          } //temporary
        }
        if (throwException) {
          throw new TestException ("verify results failed: " + str);
        }    
      }catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }   
    }
    
  }
  
  //check if the the lastKey is arrived.
  protected boolean isLastKeyArrived() {
    Connection gConn = getGFEConnection();
    boolean isLastKeyArrived = isLastKeyArrived(gConn);
    closeGFEConnection(gConn);
    return isLastKeyArrived;
  }
  
  //check if the the lastKey is arrived.
  protected boolean isLastKeyArrived(Connection gConn) {
    List<Integer> aList = new ArrayList<Integer>();
    int last_key = (int) SQLBB.getBB().getSharedCounters().read(SQLBB.defaultEmployeesPrimary);
    try {
      ResultSet rs = gConn.createStatement().executeQuery("select eid from default1.employees");
      while (rs.next()) {
         aList.add(rs.getInt("EID"));
      }
      Log.getLogWriter().info("list is " + aList.toString() + ", expected last key is " + last_key);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    if (aList.size() == last_key) return true; //how many records are in the result sets
    else return false;
  }
  
  //randomly select a wan site as base site, others wan sites will compare to this site.
  public static void HydraTask_writeSiteOneToBB() {
    wanTest.writeBaseSiteToBB();
  }
  
  //need to configured in the conf that only one site will run this
  protected void writeBaseSiteToBB() {
    Connection gConn = getGFEConnection(); 
    writeBaseSiteToBB(gConn);
    closeGFEConnection(gConn);
  }
  
  //need to configured in the conf that only one site will run this
  protected void writeBaseSiteToBB(Connection gConn) {
    if (myTid() == gfeDDLThread && !isSingleSitePublisher) {
      int sleepMs = 5000;
      while (!isLastKeyArrived()) {
        Log.getLogWriter().info("last key is not arrived yet, sleep for " + sleepMs + " ms");
        hydra.MasterController.sleepForMs(sleepMs);             
      } 
    }
    
    if (myTid() == gfeDDLThread) { 
      List<Struct> list = null;
      try {
        ResultSet rs = gConn.createStatement().executeQuery("select tableschemaname, tablename "
            + "from sys.systables where tabletype = 'T' ");
        while (rs.next()) {
          String schemaName = rs.getString(1);
          String tableName = rs.getString(2);
          list = getResultSet(gConn, schemaName, tableName);
          //Log.getLogWriter().info("before write to bb: " + ResultSetHelper.listToString(list));
          wanBB.getSharedMap().put(schemaName+"_"+tableName, list);         
          //list = (List<Struct>)wanBB.getSharedMap().get(tableName);
          //Log.getLogWriter().info("now gets the list from the bb, size is " + list.size());
          //Log.getLogWriter().info("after write to bb: " + ResultSetHelper.listToString(list));
        
          Log.getLogWriter().info("base site is wanSite" + myWanSite);
          wanBB.getSharedMap().put("baseWanSite", myWanSite); //this site used as the base site
        }
      }catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      } 
    }
  }
  
  protected List<Struct> getResultSet (Connection conn, String schema,
      String table) {
    List<Struct> tableList = null;
    try {
      String select = "select * from " + schema + "." + table;       
      ResultSet rs = conn.createStatement().executeQuery(select);
      tableList = ResultSetHelper.asList(rs, false);          
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      commit(conn);
    }
    return tableList;
  }
  
  protected void writeSiteOneToBB(Connection gConn, 
      String schema, String table) {
    try {
      String select = "select * from " + schema + "." + table;       
      ResultSet rs = gConn.createStatement().executeQuery(select);
      List<Struct> tableList = ResultSetHelper.asList(rs, false);
      wanBB.getSharedMap().put(table, tableList);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    } finally {
      commit(gConn);
    }
  }
  
  //compare gemfirexd operation replication in each site 
  public static void HydraTask_verifyWanSiteReplication() {
    wanTest.verifyResultSetsFromBB();
  }
  
  //put into table emp at the end before verification, used to verify wan site receives the last key.
  //should executed by one thread if in single site publisher -- gfeDDLThread in publish site
  public static void HydraTask_putLastKey() {
    if (myTid() == gfeDDLThread) {
      wanTest.putLastKey();
    }
  }
  
  protected void putLastKey() {
    Connection gConn = getGFEConnection();
    putLastKey(gConn); 
    closeGFEConnection(gConn);
  }
  
  protected void putLastKey(Connection gConn) {
    try {
      int last_key = (int) SQLBB.getBB().getSharedCounters().incrementAndRead(SQLBB.defaultEmployeesPrimary);
      String insert_last_key = "insert into default1.employees values (" + last_key + ", null, null, null, null)";
      Log.getLogWriter().info("last_key is " + last_key);
      Statement gstmt = gConn.createStatement();
      gstmt.execute(insert_last_key);
      commit(gConn);
      if (hasDerbyServer) {
        Log.getLogWriter().info("write last_key to derby");
        Connection dConn = getDiscConnection();
        Statement dstmt = dConn.createStatement();
        int count = dstmt.executeUpdate(insert_last_key);
        Log.getLogWriter().info("derby inserts " + count);
        commit(dConn);
      }
      
    }catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  /**
   * turn off some of the gateway in receiving wan sites, but keep the hub listener running
   * for single site publisher case
   */
  public static void HydraTask_stopGateway() {    
      wanTest.stopGateway();
  }
  
  protected void stopGateway() {
    if (random.nextBoolean() && getMyTid() == gfeDDLThread) {
      Connection gConn = getGFEConnection();
      stopGateway(gConn); //stop only gateway, listener is not stopped
    }
  }
  
  public static void HydraTask_removeGateway() {
    wanTest.removeGateway();
  }
  
  protected void removeGateway() {
    if (myTid()== gfeDDLThread) {
      Connection gConn = getGFEConnection();
      removeGateway(gConn);
    }
  }
  
  protected void removeGateway(Connection gConn) {
    //remove non existing hub id
    try {
      CallableStatement cs = gConn.prepareCall("CALL SYS.REMOVE_GATEWAY(?, ?)");
      for (int i = 1; i<= numOfWanSites; i++ ) {
        if (myWanSite != i) {
          Log.getLogWriter().info("in wan site " + myWanSite 
              + " and removing gateway for nonexisting hub" + i);
          cs.setString(1, "hub_" + i);      //hub is not in this wan site   
          cs.setString(2, gatewayID+i);  
          cs.execute();
          java.sql.SQLWarning warning = gConn.getWarnings();
          SQLHelper.printSQLWarning(warning);          
          commit(gConn);
          Log.getLogWriter().warning("Did not get exception that the hub id is not found");
        }
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
    /*
    //remove non existing gatewayID
    try {
      CallableStatement cs = gConn.prepareCall("CALL SYS.REMOVE_GATEWAY(?, ?)");
      for (int i = 1; i<= numOfWanSites; i++ ) {
        if (myWanSite == i) {
          Log.getLogWriter().info("in wan site " + myWanSite 
              + " and removing non existing gateway for hub" + i);
          cs.setString(1, "hub_" + i);       
          cs.setString(2, gatewayID+i); //gateway is not in this hub 
          cs.execute();
          java.sql.SQLWarning warning = gConn.getWarnings();
          SQLHelper.printSQLWarning(warning);          
          gConn.commit();
          Log.getLogWriter().warning("Did not get exception" +
              " that the gateway does not in this hub");
        }
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    */
    
    //removing not stopped gateway in a hub
    try {
      CallableStatement cs = gConn.prepareCall("CALL SYS.REMOVE_GATEWAY(?, ?)");
      for (int i = 1; i<= numOfWanSites; i++ ) {
        if (myWanSite != i) {
          Log.getLogWriter().info("in wan site " + myWanSite 
              + " and removing gateway for hub" + myWanSite);
          cs.setString(1, "hub_" + myWanSite);       
          cs.setString(2, gatewayID+i);  //gateway not stopped yet
          cs.execute();
          java.sql.SQLWarning warning = gConn.getWarnings();
          SQLHelper.printSQLWarning(warning);          
          commit(gConn);
          Log.getLogWriter().warning("Did not get exception that the gateway " +
              gatewayID+i+ "is not stopped");
        }
      }
    } catch (SQLException se) {
      if (se.getSQLState().equals("38000")) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("Got expected gateway not stopped exception," +
            " continuing testing");
      } else if (se.getSQLState().equals("0A000") && !isTicket43696Fixed) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("Got expected not able to remove gateway exception," +
        " continuing testing");        
      } else SQLHelper.handleSQLException(se);
    }
    
    stopGateway(gConn);
    //stopGatewayHub(gConn);
    
    //removing stopped gateways
    try {
      CallableStatement cs = gConn.prepareCall("CALL SYS.REMOVE_GATEWAY(?, ?)");
      for (int i = 1; i<= numOfWanSites; i++ ) {
        if (myWanSite != i) {
          Log.getLogWriter().info("in wan site " + myWanSite 
              + " and removing gateway for hub" + myWanSite);
          cs.setString(1, "hub_" + myWanSite);      //hub is not in this wan site   
          cs.setString(2, gatewayID+i);  
          cs.execute();
          java.sql.SQLWarning warning = gConn.getWarnings();
          SQLHelper.printSQLWarning(warning);          
          commit(gConn);
        }
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  @SuppressWarnings("unchecked")
  protected void stopGateway(Connection gConn) {
    try {
      CallableStatement cs = gConn.prepareCall("CALL SYS.STOP_GATEWAYSENDER(?)");
      ArrayList<String> senderIDs = (ArrayList<String>)wanBB.getSharedMap().get(myWanSite+"_senderIDs");
      for (String id: senderIDs) {
        Log.getLogWriter().info("stopping gatewaysender " + id);
        cs.setString(1, id);
        cs.execute();
        java.sql.SQLWarning warning = gConn.getWarnings();
        SQLHelper.printSQLWarning(warning);          
        commit(gConn);
        Log.getLogWriter().info("stopped gatewaysender " + id);
      }
      for (int i = 1; i<= numOfWanSites; i++ ) {
        if (myWanSite != i) {
          Log.getLogWriter().info("in wan site " + myWanSite 
              + " and stopping gateway " + gatewayID +i + " for hub" + myWanSite);
          cs.setString(1, "hub_" + myWanSite);        
          cs.setString(2, gatewayID+i);  
          cs.execute();
          java.sql.SQLWarning warning = gConn.getWarnings();
          SQLHelper.printSQLWarning(warning);          
          commit(gConn);
          Log.getLogWriter().info("in wan site " + myWanSite 
              + " and stopped gateway " + gatewayID +i + " for hub" + myWanSite);
        }
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  
  /* old api
  protected void stopGateway(Connection gConn) {
    try {
      if (random.nextBoolean()) {
        CallableStatement cs = gConn.prepareCall("CALL SYS.STOP_GATEWAY(?, ?)");
        for (int i = 1; i<= numOfWanSites; i++ ) {
          if (myWanSite != i) {
            Log.getLogWriter().info("in wan site " + myWanSite 
                + " and stopping gateway " + gatewayID +i + " for hub" + myWanSite);
            cs.setString(1, "hub_" + myWanSite);        
            cs.setString(2, gatewayID+i);  
            cs.execute();
            java.sql.SQLWarning warning = gConn.getWarnings();
            SQLHelper.printSQLWarning(warning);          
            commit(gConn);
            Log.getLogWriter().info("in wan site " + myWanSite 
                + " and stopped gateway " + gatewayID +i + " for hub" + myWanSite);
          }
        }
      } else {
        Log.getLogWriter().info("in wan site " + myWanSite 
            + " and stopping all gateway for hub" + myWanSite);
        CallableStatement cs = gConn.prepareCall("CALL SYS.STOP_GATEWAYS(?)");
        cs.setString(1, "hub_" + myWanSite);
        cs.execute();
        commit(gConn);
        Log.getLogWriter().info("in wan site " + myWanSite 
            + " and stopped all gateways for hub" + myWanSite);
      }
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  protected void stopGatewayHub(Connection gConn) {
    try {
      Log.getLogWriter().info("in wan site " + myWanSite 
          + " and stopping gateway hub for hub" + myWanSite);
      CallableStatement cs = gConn.prepareCall("CALL SYS.STOP_GATEWAY_HUB(?)");
      cs.setString(1, "hub_" + myWanSite);
      cs.execute();
      commit(gConn);
      Log.getLogWriter().info("in wan site " + myWanSite 
          + " and stopped gateway hub for hub" + myWanSite);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  */
  
  public static void HydraTask_populateTables() {
    if (dumpThreads && RemoteTestModule.getCurrentThread().getThreadId() == derbyDDLThread)
      wanTest.dumpThreads();
    else
      wanTest.populateTables();
  }
  
  public static void HydraTask_doDMLOp() {
    wanTest.doDMLOp();
  }
  
  public static void HydraTask_createFunctionToPopulate(){
    wanTest.createFunctionToPopulate();
  }
  
  protected void createFunctionToPopulate() {
    if (myTid() == gfeDDLThread) {
      super.createFunctionToPopulate();
    }
  }
    
  public static void HydraTask_populateThruLoader() {
    wanTest.populateThruLoader();
  }
  
  protected void populateThruLoader() {
    if (myTid() == gfeDDLThread) {
      super.populateThruLoader();
    }
  }
  
  public static void HydraTask_setTableCols() {
    wanTest.setTableCols();
  }
  
  protected void setTableCols() {
    if (myTid() == derbyDDLThread) {
      super.setTableCols();
    }
  }
  
  public static void  HydraTask_createIndex() {
    wanTest.createIndex();
  }
  
  protected void createIndex() {
    boolean gotLock = false;
    synchronized(indexLock) {
      gotLock = toCreateIndex;
      toCreateIndex = false;
    }
    if (gotLock) {
      super.createIndex();
      MasterController.sleepForMs(30000);
      synchronized(indexLock) {
        toCreateIndex = true; //allow next one to create index
      }
    }
  }
  
  public static void HydraTask_createProcedures() {
    wanTest.createProcedures();
  }
  
  public static void HydraTask_callProcedures() {
    wanTest.callProcedures();
  }
  
  public static void HydraTask_doOp() {
    wanTest.doOps();
  }
  
  public static void HydraTask_cycleStoreVms() {
    wanTest.cycleStoreVms();
  }
  
  protected void cycleStoreVms() {
    int numToKill = TestConfig.tab().intAt(StopStartPrms.numVMsToStop, 1);
    long numOfPRs = (Long) wanBB.getSharedMap().get("numOfPRs" + myWanSite);
    List<ClientVmInfo> vms = null;
    //make sure that the index in wanBB corresponding to the wanSite
    if (wanBB.getSharedCounters().incrementAndRead(myWanSite) == 1) { 
      //relaxing a little for HA tests
      int sleepMS = 20000;
      Log.getLogWriter().info("allow  " + sleepMS/1000 + " seconds before killing others");
      MasterController.sleepForMs(sleepMS);
      vms = stopStartNonSenderVMs(numToKill);
      
      Log.getLogWriter().info("Total number of PR is " + numOfPRs);
      PRObserver.waitForRebalRecov(vms, 1, (int)numOfPRs, null, null, false);
      
      wanBB.getSharedCounters().zero(myWanSite);      
    } 
  }  
  
  protected List<ClientVmInfo> stopStartNonSenderVMs(int numToKill) {    
    return stopStartNonSenderVMs(numToKill, "datastore_" + myWanSite + "_" );
  }
  
  @SuppressWarnings("unchecked")
  protected List <ClientVmInfo> stopStartNonSenderVMs(int numToKill, String target) {
    Object vm1 = wanBB.getSharedMap().get("wan_" + myWanSite + "_sender1");
    Object vm2 = wanBB.getSharedMap().get("wan_" + myWanSite + "_sender2");
    List <ClientVmInfo> vmList;
    List<String> stopModeList;
    if (vm1 == null || vm2 == null) {      
      throw new TestException("each site should have 2 sender nodes");
    }
    ArrayList<Integer> senders = new ArrayList<Integer>();
    senders.add(((ClientVmInfo) vm1).getVmid().intValue());
    senders.add(((ClientVmInfo) vm2).getVmid().intValue());
    
    Object[] tmpArr = StopStartVMs.getOtherVMsWithExcludeVmid(numToKill, target, senders);
    // get the VMs to stop; vmList and stopModeList are parallel lists
    
    vmList = (List<ClientVmInfo>)(tmpArr[0]);
    stopModeList = (List<String>)(tmpArr[1]);
    for (ClientVmInfo client : vmList) {
      PRObserver.initialize(client.getVmid());
    } //clear bb info for the vms to be stopped/started

    if (vmList.size() != 0) StopStartVMs.stopStartVMs(vmList, stopModeList);
    return vmList;
  }
  
  public static void HydraTask_cycleSenderVms() {
    wanTest.cycleSenderVms();
  }
  
  @SuppressWarnings("unchecked")
  protected void cycleSenderVms() {
    //make sure that the index in wanBB corresponding to the wanSite
    if (senderBB.getSharedCounters().incrementAndRead(myWanSite) == 1) {     
      Object vm1 = wanBB.getSharedMap().get("wan_" + myWanSite + "_sender1");
      Object vm2 = wanBB.getSharedMap().get("wan_" + myWanSite + "_sender2");
      
      //only stop one sender in the test
      List<ClientVmInfo> vms = new ArrayList();
      if (random.nextBoolean()) vms.add((ClientVmInfo) vm1);
      else vms.add((ClientVmInfo) vm2);
          
      ArrayList stopModeList = new ArrayList();
      stopModeList.add(TestConfig.tab().stringAt(StopStartPrms.stopModes));
          
      int threeMinutes = 120000;
      int additionalWaitSec = random.nextInt(threeMinutes);
      //relaxing a little more for wan HA tests
      int sleepMS = 20000 + additionalWaitSec;
      Log.getLogWriter().info("allow  " + sleepMS/1000 + " seconds before killing others");
      MasterController.sleepForMs(sleepMS);
      StopStartVMs.stopStartVMs(vms, stopModeList); 
      //make sure that sender does not host partition table    
      
      //reduce the rate of bring down senders
      if (random.nextInt(10) != 1) {
        int msPerMinute = 60 * 1000;
        int waitTime = msPerMinute + random.nextInt(msPerMinute); 
        MasterController.sleepForMs(waitTime);
        Log.getLogWriter().info("allow  " + waitTime/1000 + " seconds before killing others");
      }
      
      senderBB.getSharedCounters().zero(myWanSite);      
    } 
  }  
  
  public static void HydraTask_createFuncForProcedures() {
    if (myTid() == gfeDDLThread) {
      wanTest.createFuncForProcedures();
    }
  }
  
  protected void createFuncForProcedures() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    createFuncForProcedures(dConn, gConn);
    
    if (dConn!= null) {
      commit(dConn);
      closeDiscConnection(dConn);
    }
    
    commit(gConn);
    closeGFEConnection(gConn);  
  }
  
  protected void createFuncForProcedures(Connection dConn, Connection gConn) {
    Log.getLogWriter().info("performing create function multiply Op, myTid is " + getMyTid()); 
    try {
      FunctionDDLStmt.createFuncMultiply(dConn);
    } catch (SQLException se) {
      if (se.getSQLState().equalsIgnoreCase("X0Y68") &&
          isWanTest)
        Log.getLogWriter().info("get expected multiple function creation " +
            "in derby in multi wan publisher case, continuing tests");
      else
        SQLHelper.handleSQLException(se);
    }
    
    try {
      FunctionDDLStmt.createFuncMultiply(gConn);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }
  
  public static void HydraTask_checkConstraints() {
    if (myTid() == derbyDDLThread) {
      wanTest.checkConstraints();
    } //only one thread is needed as test will verify if all wan sites get replica of others
  }
  
  protected void checkConstraints() {
    Connection gConn = getGFEConnection();    
    checkUniqConstraints(gConn);
    checkFKConstraints(gConn);
    closeGFEConnection(gConn);
  }
  
  public static void HydraTask_createCompaniesTableInDerby() {
    wanTest.createCompaniesTableInDerby();
  }
  
  public static void HydraTask_createCompaniesTableInGfxd() {
    wanTest.createCompaniesTableInGfxd();
  }
  
  protected void createCompaniesTableInGfxd() {
    if (myTid() == gfeDDLThread) {
      Connection gConn = getGFEConnection();
      getLock();
      createCompaniesTable(gConn);
      releaseLock();
      commit(gConn);
      closeGFEConnection(gConn);
    }
  }
  
  protected void createCompaniesTableInDerby() {
    Connection dConn = null;
    if (hasDerbyServer && myTid() == derbyDDLThread) {
      dConn = getDiscConnection();

      try {
        log().info(companyTable);
        if (dConn != null) executeStatement(dConn, companyTable);     
        commit(dConn);
        closeDiscConnection(dConn);
      } catch (SQLException se) {
        SQLHelper.handleSQLException(se);
      }
    }
  }
  
  protected void createCompaniesTable(Connection gConn) {
    String companiesDDL = "companiesDDL";
    while (wanBB.getSharedCounters().incrementAndRead(SQLWanBB.synchWanSiteParitionKeys)!=1) {
      Log.getLogWriter().info("waiting for synchWanSiteParitionKeys to become 1");
      MasterController.sleepForMs(500);
    }
    String sql = null;
    //all site use the same partition for companies table
    if (wanBB.getSharedMap().get(companiesDDL) ==null) {
      sql = getCompaniesDDL();    
      wanBB.getSharedMap().put(companiesDDL, sql);
    } else {
      sql = (String) wanBB.getSharedMap().get(companiesDDL);   
    }            

    writeCompaniesPartitionForWanSites();
    wanBB.getSharedMap().put("numOfPRs" + myWanSite, 
        SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs));
    Log.getLogWriter().info("numOfPRs is " + 
        SQLBB.getBB().getSharedCounters().read(SQLBB.numOfPRs));
    Log.getLogWriter().info("put numOfPRs into wanBB for " + myWanSite);
    wanBB.getSharedCounters().zero(SQLWanBB.synchWanSiteParitionKeys);
    
    sql = getWanExtension(sql);
    
    try {
      log().info("in gfxd executing " + sql);
      executeStatement(gConn, sql);
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
    
  }
  
  protected String getCompaniesDDL() {   
    String companiesPartition = " ";
    if (testPartitionBy) {
      companiesPartition = getTablePartition(TestConfig.tab().stringAt(SQLPrms.companiesTableDDLExtension, "trade.companies:random"));
    }
    
    String companiesDDLExtension = companiesPartition + 
      getRedundancyClause(companiesPartition, TestConfig.tab().stringAt(SQLPrms.companiesTableRedundancy, " ") +
          getCompaniesPersistence()); 
      
    return companyTable + companiesDDLExtension;
  }
  
  protected void writeCompaniesPartitionForWanSites() {
    Log.getLogWriter().info("companiesPartition is :" + SQLBB.getBB().getSharedMap().get("companiesPartition"));
   
    wanBB.getSharedMap().put(myWanSite+"_companiesPartition", 
        SQLBB.getBB().getSharedMap().get("companiesPartition"));    
  }
  
  @SuppressWarnings("unchecked")
  protected String getWanExtension(String sql) {
    String ddl = sql.toUpperCase();
    if (ddl.contains("REPLICATE")) {
      if (ddl.contains("SERVER GROUPS") && !ddl.contains(sgSender)) {
        int index = ddl.indexOf("SERVER GROUPS");
        StringBuffer start = new StringBuffer((sql.substring(0, index-1)));
        StringBuffer sb = new StringBuffer(sql.substring(index));
        sb.insert(sb.indexOf("SG"), sgSender + ",");
        sql = (start.append(sb)).toString();
      }

      if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
          && !((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
          equalsIgnoreCase("default")) {
        sql += "SERVER GROUPS (" + sgSender + "," +
          SQLBB.getBB().getSharedMap().get(tradeSchemaSG) + ")";
      }  else if (!ddl.contains("SERVER GROUPS") && testServerGroupsInheritence
          && ((String)SQLBB.getBB().getSharedMap().get(tradeSchemaSG)).
          equalsIgnoreCase("default")) {
        sql += "SERVER GROUPS (" + sgSender + ",SG1,SG2,SG3,SG4)";
      }//inherit from schema server group
    } //handle replicate tables for sg
    
    ArrayList<String> senderIDs = (ArrayList<String>)wanBB.getSharedMap().get(myWanSite+"_senderIDs");
    if (senderIDs == null) throw new TestException("senderIDs are not setting yet for creating tables");

    StringBuilder senders = new StringBuilder();
    senders.append("GATEWAYSENDER(");
    for (int j =0; j<senderIDs.size(); j++) {
      senders.append(senderIDs.get(j));
      if (j<senderIDs.size()-1) senders.append(", ");
    }
    senders.append(")");

    return sql + senders.toString();        
  }
  
  public static void HydraTask_dropCompaniesFKInDerby() {
    wanTest.dropCompaniesFKInDerby();
  }
  
  protected void dropCompaniesFKInDerby() {
    Connection dConn = null;
    if (hasDerbyServer && myTid() == derbyDDLThread) {
      dConn = getDiscConnection();
      log().info("derby executing");
      dropCompaniesFK(dConn);
      commit(dConn);
      closeDiscConnection(dConn);
    }
  }
  
  public static void HydraTask_dropCompaniesFKInGfxd() {
    wanTest.dropCompaniesFKInGfxd();
  }
  
  protected void dropCompaniesFKInGfxd() {
    if (myTid() == gfeDDLThread) {
      Connection gConn = getGFEConnection();
      log().info("gfxd executing");
      dropCompaniesFK(gConn);
      commit(gConn);
      closeGFEConnection(gConn);
    }
  }
  
  public static void HydraTask_createUDTPriceTypeInDerby() {
    wanTest.createUDTPriceTypeInDerby();
  }
  
  protected void createUDTPriceTypeInDerby() {
    Connection dConn = null;
    if (hasDerbyServer && myTid() == derbyDDLThread) {
      dConn = getDiscConnection();
      log().info("derby executing");
      createUDTPriceType(dConn);
      commit(dConn);
      closeDiscConnection(dConn);
    }
  }
  
  public static void HydraTask_createUDTPriceTypeInGfxd() {
    wanTest.createUDTPriceTypeInGfxd();
  }
  
  protected void createUDTPriceTypeInGfxd() {
    if (myTid() == gfeDDLThread) {
      Connection gConn = getGFEConnection();
      log().info("gfxd executing");
      createUDTPriceType(gConn);
      commit(gConn);
      closeGFEConnection(gConn);
    }
  }
  
  public static void HydraTask_createUUIDTypeInDerby() {
    wanTest.createUUIDTypeInDerby();
  }
  
  protected void createUUIDTypeInDerby() {
    Connection dConn = null;
    if (hasDerbyServer && myTid() == derbyDDLThread) {
      dConn = getDiscConnection();
      log().info("derby executing");
      createUUIDType(dConn);
      commit(dConn);
      closeDiscConnection(dConn);
    }
  }
  
  public static void HydraTask_createUUIDTypeInGfxd() {
    wanTest.createUUIDTypeInGfxd();
  }
  
  protected void createUUIDTypeInGfxd() {
    if (myTid() == gfeDDLThread) {
      Connection gConn = getGFEConnection();
      log().info("gfxd executing");
      createUUIDType(gConn);
      commit(gConn);
      closeGFEConnection(gConn);
    }
  }
  
  public static void hydraTask_createUDTPriceFunctionsInDerby() {
    wanTest.createUDTPriceFunctionsInDerby();
  }
  
  protected void createUDTPriceFunctionsInDerby() {
    Connection dConn = null;
    if (hasDerbyServer && myTid() == derbyDDLThread) {
      dConn = getDiscConnection();
      log().info("derby executing");
      createUDTPriceFunction(dConn);
      commit(dConn);
      closeDiscConnection(dConn);
    }
  }
  
  public static void hydraTask_createUDTPriceFunctionsInGfxd() {
    wanTest.createUDTPriceFunctionsInGfxd();
  }
  
  protected void createUDTPriceFunctionsInGfxd() {
    if (myTid() == gfeDDLThread) {
      Connection gConn = getGFEConnection();
      log().info("gfxd executing");
      createUDTPriceFunction(gConn);
      commit(gConn);
      closeGFEConnection(gConn);
    }
  }
  
  protected void doDDLOp(Connection dConn, Connection gConn) {
    //work around not able to drop procedures in multi wan site issue
    //by not executing the ddl
    if (ddls.length == 0) {
      log().info("No ddl statement to be executed");
      return;
    }
    
    int ddl = ddls[random.nextInt(ddls.length)];
    DDLStmtIF ddlStmt= ddlFactory.createDDLStmt(ddl); //dmlStmt of a table

    ddlStmt.doDDLOp(dConn, gConn);
    
    commit(dConn); 
    commit(gConn);

  }
 
  public static synchronized void HydraTask_createHDFSSTORE() {
    if (myTid() == gfeDDLThread) {
      SQLTest.HydraTask_createHDFSSTORE();
    }    
  }  
  
  public static void HydraTask_shutDownAllFabricServers(){
    wanTest.shutDownAllFabricServers();
  }

 protected void shutDownAllFabricServers() {
   Log.getLogWriter().info("shuting down all FabricServers");
   FabricServerHelper.shutDownAllFabricServers(300);

   // wait for shutdown
   while (true) {
     if (!FabricServerHelper.isFabricServerStopped()) {
       Log.getLogWriter().info("Waiting for " + 5 + " sec as for shutdown of FabricServer");
       MasterController.sleepForMs(5000);
     } else {
       Log.getLogWriter().info("Completed shutdown of FabricServer...");
       return;
     }
   }
  }
}
