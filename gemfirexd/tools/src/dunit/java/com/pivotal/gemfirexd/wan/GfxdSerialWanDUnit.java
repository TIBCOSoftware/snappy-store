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
package com.pivotal.gemfirexd.wan;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.wan.GatewayReceiver;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySenderEventProcessor;
import com.gemstone.gemfire.internal.cache.wan.serial.SerialGatewaySenderImpl;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import io.snappydata.test.dunit.AvailablePortHelper;
import io.snappydata.test.dunit.DistributedTestBase;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import junit.framework.Assert;
import org.w3c.dom.Element;

@SuppressWarnings("serial")
public class GfxdSerialWanDUnit extends GfxdWanTestBase {

  public GfxdSerialWanDUnit(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  /*
   * This test is to ensure that events are dispatched for DML ops on
   * all buckets regardless of the sender being on the same datastore or different.
   * (The startTwoSites uses 1 locator, 1 store with server groups sg1,
   * one store with server groups sg1 and sgSender, and one accessor with
   * server groups sg1 and sgsender, for each site.  So basically, we have one node 
   * that is a  datastore but not a sender, and two nodes that are datastores with buckets
   * divided among them.)  
   */
  public void testDispatchForInsertsOnAllBuckets() throws Exception {

    // Bug #51626.  Allow the test when the bug is fixed.
    if (isTransactional) {
      return;
    }
    
    startTwoSites();
    // Set up serial sender and receiver 
    executeSql(SITE_B,
        "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)");
    executeSql(
        SITE_A,
        "create gatewaysender MySender (remotedsid 2 isparallel false )" +
            " server groups (sgSender)");
    
    final String createTable = "create table testtable (ID int, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, ID1 int,"
        + "primary key (ID)) " 
        + " partition by primary key server groups (sg1)";

    // Create a table on sender site and attach a sender to it
    executeSql(SITE_A, createTable + " GatewaySender (MySender) " + getSQLSuffixClause());

    // Get an available port and start a Derby network server to listen on it
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    DBSynchronizerTestBase.startNetworkServer(netPort);
    
    
    // Form a derby connection url
    String derbyDbUrl = "jdbc:derby://localhost:" + netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    Statement derbyStmt = derbyConn.createStatement();
    derbyStmt = derbyConn.createStatement();

    DBSynchronizerTestBase.createDerbyValidationArtefacts(netPort);
    
    Runnable createListener = DBSynchronizerTestBase.getExecutorForWBCLConfiguration("SG1", "TESTDBSYNC",
        "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
        Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);

    executeOnSite(SITE_B, createListener);

    // Create table on receiver site and attach the listener to it
    executeSql(SITE_B, createTable + "AsyncEventListener (TESTDBSYNC) " + getSQLSuffixClause());

    // Start the asynceventlistener
    Runnable startListener = DBSynchronizerTestBase.startAsyncEventListener("TESTDBSYNC");
    executeOnSite(SITE_B, startListener);

    for (int i = 1; i <= 113; i++) {
      executeSql(SITE_A, "insert into testtable values("+ i +", 'desc" + i + "', 'addr1', " + i +")");  
    }
    
    // pk-based
    executeSql(SITE_A, "update testtable set description = 'modified_desc1' where id = 1");
    // bulk
    executeSql(SITE_A, "update testtable set description = 'modified_desc1' where description = 'desc1'");
    executeSql(SITE_A, "update testtable set description = 'modified_desc2' where description = 'modified_desc1'");
    
    executeSql(SITE_A, "delete from testtable where id = 1");
    
    DBSynchronizerTestBase.blockForValidation(netPort);
    Runnable stopListener = DBSynchronizerTestBase.stopAsyncEventListener("TESTDBSYNC");
    executeOnSite(SITE_B, stopListener);
    
    Thread.sleep(5000);

    int vmNum = this.getSiteAccessorServerNum(SITE_B);
    int networkPort = this.startNetworkServer(vmNum, "SG1", null);
    Connection clientConn = TestUtil.getNetConnection(networkPort, null, null);
    
    Statement stmt = clientConn.createStatement();
    ResultSet derbyRS = derbyStmt.executeQuery("select * from testtable");
    ResultSet gfxdRS = stmt.executeQuery("select * from testtable");
    
    Element resultElement = Misc.resultSetToXMLElement(gfxdRS, true, true);
    Element expectedElement = Misc.resultSetToXMLElement(derbyRS, true, true);
    Map<String, Integer> resultMap = TestUtil
        .xmlElementToFrequencyMap(resultElement);
    Map<String, Integer> expectedResultMap = TestUtil
        .xmlElementToFrequencyMap(expectedElement);
    String resultStr = Misc.serializeXML(resultElement);
    String expectedStr = Misc.serializeXML(expectedElement);

    getLogWriter().info(
        "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
    getLogWriter().info(
        "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
    
    if (!TestUtil.compareFrequencyMaps(expectedResultMap, resultMap)) {
      getLogWriter().info(
          "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
      getLogWriter().info(
          "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
      Assert.fail("Expected string in derby does not "
          + "match the result from GemFireXD.");
    }
  }

  /*
   * Scenario; Two WAN sites. Receiver has DBSynchronizer attached to a table.
   * The bug is about updates and deletes fired on sender getting filtered before
   * getting enqueued for DBSynchronizer on the receiver.
   */
  public void testBug51507() throws Exception {
    startSites(2);

    // Set up serial sender and receiver 
    executeSql(SITE_B,
        "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)");
    executeSql(
        SITE_A,
        "create gatewaysender MySender (remotedsid 2 isparallel false )" +
            " server groups (sgSender)");
    
    final String createTable = "create table testtable (ID int, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, ID1 int,"
        + "primary key (ID)) " 
        + " replicate server groups (sg1)";

    // Create a table on sender site and attach a sender to it
    executeSql(SITE_A, createTable + " GatewaySender (MySender) " + getSQLSuffixClause());

    // Get an available port and start a Derby network server to listen on it
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    DBSynchronizerTestBase.startNetworkServer(netPort);
    
    
    // Form a derby connection url
    String derbyDbUrl = "jdbc:derby://localhost:" + netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    Statement derbyStmt = derbyConn.createStatement();
    derbyStmt = derbyConn.createStatement();

    DBSynchronizerTestBase.createDerbyValidationArtefacts(netPort);
    
    Runnable createListener = DBSynchronizerTestBase.getExecutorForWBCLConfiguration("SG1", "TESTDBSYNC",
        "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
        Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000, "", false);

    executeOnSite(SITE_B, createListener);

    // Create table on receiver site and attach the listener to it
    executeSql(SITE_B, createTable + "AsyncEventListener (TESTDBSYNC) " + getSQLSuffixClause());

    // Start the asynceventlistener
    Runnable startListener = DBSynchronizerTestBase.startAsyncEventListener("TESTDBSYNC");
    executeOnSite(SITE_B, startListener);

    executeSql(SITE_A, "insert into testtable values(1, 'desc1', 'addr1', 1)");
    // pk-based
    executeSql(SITE_A, "update testtable set description = 'modified_desc1' where id = 1");
    // bulk
    executeSql(SITE_A, "update testtable set description = 'modified_desc1' where description = 'desc1'");
    executeSql(SITE_A, "update testtable set description = 'modified_desc2' where description = 'modified_desc1'");
    
    executeSql(SITE_A, "delete from testtable where id = 1");
    
    DBSynchronizerTestBase.blockForValidation(netPort);
    Runnable stopListener = DBSynchronizerTestBase.stopAsyncEventListener("TESTDBSYNC");
    executeOnSite(SITE_B, stopListener);
    
    Thread.sleep(5000);

    int vmNum = this.getSiteAccessorServerNum(SITE_B);
    int networkPort = this.startNetworkServer(vmNum, "SG1", null);
    Connection clientConn = TestUtil.getNetConnection(networkPort, null, null);
    
    Statement stmt = clientConn.createStatement();
    ResultSet derbyRS = derbyStmt.executeQuery("select * from testtable");
    ResultSet gfxdRS = stmt.executeQuery("select * from testtable");
    
    Element resultElement = Misc.resultSetToXMLElement(gfxdRS, true, true);
    Element expectedElement = Misc.resultSetToXMLElement(derbyRS, true, true);
    Map<String, Integer> resultMap = TestUtil
        .xmlElementToFrequencyMap(resultElement);
    Map<String, Integer> expectedResultMap = TestUtil
        .xmlElementToFrequencyMap(expectedElement);
    String resultStr = Misc.serializeXML(resultElement);
    String expectedStr = Misc.serializeXML(expectedElement);

    getLogWriter().info(
        "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
    getLogWriter().info(
        "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
    
    if (!TestUtil.compareFrequencyMaps(expectedResultMap, resultMap)) {
      getLogWriter().info(
          "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
      getLogWriter().info(
          "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
      Assert.fail("Expected string in derby does not "
          + "match the result from GemFireXD.");
    }
  }
  
  public void testSerialWanNewDunitConfig() throws Exception {
    startSites();
    addExpectedException(new String[] { SITE_A, SITE_B, SITE_C, SITE_D },
        new Object[] { FunctionExecutionException.class });
    
    addExpectedException(new String[] { SITE_A, SITE_B, SITE_C, SITE_D},
        new String[] {"Could not connect"});
    
    VM siteA = this.serverVMs.get(3);
    VM siteB = this.serverVMs.get(7);
    VM siteC = this.serverVMs.get(11);
    VM siteD = this.serverVMs.get(15);
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)" ;
    executeSql(siteA, createGWR);
    executeSql(siteB, createGWR);
    executeSql(siteC, createGWR);
    executeSql(siteD, createGWR);
    
    executeSql(siteA, "CREATE gatewaysender mySender (remotedsid 2) server groups (sgSender)");
    executeSql(siteA, "CREATE gatewaysender mySenderB (remotedsid 3) server groups (sgSender)");
    executeSql(siteB, "CREATE gatewaysender mySender (remotedsid 1) server groups (sgSender)");
    executeSql(siteC, "CREATE gatewaysender mySender (remotedsid 4) server groups (sgSender)");
    
    final String createPTable = "create table EMP.REPLICATED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) replicate server groups (sg1)";
    
    executeSql(siteA, createPTable + " GatewaySender (MySender,mySenderB)" + getSQLSuffixClause());
    executeSql(siteB, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(siteC, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(siteD, createPTable + getSQLSuffixClause());
    
    String createCTable = "create table EMP.PARTITIONED_TABLE (ID int not null, " +
    		"DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID), " +
    		"foreign key (ID) references EMP.REPLICATED_TABLE (id)) " +
    		"partition by column(ADDRESS) redundancy 1 server groups (sg1)";

    executeSql(siteA, createCTable + " GatewaySender (MySender,mySenderB)" + getSQLSuffixClause());
    executeSql(siteB, createCTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(siteC, createCTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(siteD, createCTable + getSQLSuffixClause());
    
    executeSql(siteA,
        "insert into EMP.REPLICATED_TABLE values (1, 'First', 'A714')");
    executeSql(siteA,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");

    executeSql(siteA,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    String goldenFile = TestUtil.getResourcesDir()
    + "/lib/GemFireXDGatewayDUnit.xml";
    sqlExecuteVerify(siteA, "select ID from EMP.PARTITIONED_TABLE", goldenFile,
        "id1", true, false);
    sqlExecuteVerify(siteB, "select ID from EMP.PARTITIONED_TABLE", goldenFile,
        "id1", true, false);
    executeSql(siteA,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDERB', 0, 0)");
    sqlExecuteVerify(siteC, "select ID from EMP.PARTITIONED_TABLE", goldenFile,
        "id1", true, false);
    executeSql(siteC,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(siteD, "select ID from EMP.PARTITIONED_TABLE", goldenFile,
        "id1", true, false);
    
    
    executeSql(siteB,
        "Update EMP.REPLICATED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A714'");
    
    executeSql(siteB,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A714'");
    
    executeSql(siteB,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(siteA, "select DESCRIPTION from EMP.PARTITIONED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(siteA,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDERB', 0, 0)");
    sqlExecuteVerify(siteC, "select DESCRIPTION from EMP.PARTITIONED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(siteC,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(siteD, "select DESCRIPTION from EMP.PARTITIONED_TABLE", goldenFile,
        "id2", true, false);
  }

  public void testBasicDMLOperationPropagationOnUseCase1Schema_PlusVersions48125()
      throws Exception {
    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
        + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(1, 0, "sg1", props);
    

    // DS1 Member
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(2, 0, null, props);
    joinVMs(true, async3, async4);
    assertTrue(this.serverVMs.size() == 4);
    addExpectedException(this.serverVMs.get(2),
        new Object[] { CacheClosedException.class });
    addExpectedException("Could not connect", this.serverVMs.get(3));
    addExpectedException(this.serverVMs.get(3),
        new Object[] { CacheClosedException.class });

    Runnable createReceiver = createGatewayReceiver(0, 0);
    serverExecute(3, createReceiver);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "tableGrp,senderGrp", props);

    // DS0 Member
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(2, 0, "tableGrp", props);
    joinVMs(true, async5, async6);
    assertTrue(this.serverVMs.size() == 6);
    addExpectedException("Could not connect", this.serverVMs.get(5));
    serverExecute(5, createGatewaySender("mySender", "2", "senderGrp"));

    VM receiver1 = serverVMs.get(2);
    VM sender0 = serverVMs.get(4);
    VM ds1member = serverVMs.get(3);
    VM ds0member = serverVMs.get(5);
    


    this.executeSql(receiver1, "create schema EMP");
    this.executeSql(sender0, "create schema EMP");

    String table = "CREATE TABLE SECT_CHANNEL_DATA("
        + "CHANNEL_TXN_ID varchar(30) NOT NULL primary key,"
        + "     CLIENT_ID int not null ," + "     CLIENT_NAME VARCHAR (100) ,"
        + "     CLIENT_ACCOUNT VARCHAR (100) ,"
        + "     COMPANY_ID VARCHAR (100) ,"
        + "     CLIENT_REF_NO VARCHAR (100) ," + "     VALUE_DATE TIMESTAMP ,"
        + "     AMOUNT DECIMAL (16,2) ," + "     CURRENCY VARCHAR (20) ,"
        + "     PAY_METHOD VARCHAR (100) ,"
        + "     PAY_VEHICLE VARCHAR (100) ,"
        + "     ORIG_BANK_ID VARCHAR (100) ,"
        + "     BACKOFFICE_CODE VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "     BENE_ACCNT_NO VARCHAR (100) ,"
        + "     BENE_NAME VARCHAR (100) ," + "     BENE_ADDR VARCHAR (100) ,"
        + "     BENE_BANK_ID VARCHAR (100) ,"
        + "     BENE_BANK_NAME VARCHAR (100) ,"
        + "     BENE_BANK_ADDR VARCHAR (256) ,"
        + "     CLIENT_FILE_ID VARCHAR (100) ,"
        + "     CLIENT_FILE_TIME TIMESTAMP ,"
        + "     TXN_CREATED_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP,"
        + "     RAW_DATA CLOB NOT NULL ,"
        + "     DATA_LIFE_STATUS SMALLINT NOT NULL ,"
        + "     MATCH_STATUS SMALLINT WITH DEFAULT 1 ,"
        + "     LAST_MATCH_DATE TIMESTAMP ," + "     MATCH_CATEG_ID INTEGER ,"
        + "     IS_REJECTED CHAR (1) WITH DEFAULT 'N' ,"
        + "     IS_CANCELLED CHAR (1) WITH DEFAULT 'N' ,"
        + "     FILE_TYPE_ID VARCHAR (36) NOT NULL ,"
        + "     ETL_TIME INTEGER NOT NULL ,"
        + "     CHANNEL_NAME VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "     TXN_TYPE VARCHAR (30) WITH DEFAULT 'UNKNOWN' ,"
        + "     HIT_STATUS SMALLINT ," + "     ACTUAL_VALUE_DATE TIMESTAMP ,"
        + "     LAST_UPDATE_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP"
        + "    )" + "    PARTITION BY column(CLIENT_ID)" + "    REDUNDANCY 1"
        + "    EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW"
        + getSQLSuffixClause();

    this.executeSql(sender0, table);
    this.executeSql(receiver1, table);

    this.executeSql(sender0,
        "alter table SECT_CHANNEL_DATA set GatewaySender (MySender)");

    String sql1 = "INSERT INTO SECT_CHANNEL_DATA  (CHANNEL_TXN_ID,CLIENT_ID,"
        + "CLIENT_NAME,CLIENT_ACCOUNT,COMPANY_ID,CLIENT_REF_NO,VALUE_DATE,"
        + "AMOUNT,CURRENCY,PAY_METHOD,PAY_VEHICLE,ORIG_BANK_ID,BACKOFFICE_CODE,"
        + "BENE_ACCNT_NO,BENE_NAME,BENE_ADDR,BENE_BANK_ID,BENE_BANK_NAME,"
        + "BENE_BANK_ADDR,CLIENT_FILE_ID,CLIENT_FILE_TIME,TXN_CREATED_TIME,"
        + "RAW_DATA,DATA_LIFE_STATUS,MATCH_STATUS,LAST_MATCH_DATE,"
        + "MATCH_CATEG_ID,IS_REJECTED,IS_CANCELLED,FILE_TYPE_ID,ETL_TIME,"
        + "CHANNEL_NAME,TXN_TYPE,HIT_STATUS,ACTUAL_VALUE_DATE,LAST_UPDATE_TIME) "
        + "VALUES('testCol1',1,null,'19882893','6777734','7XWzXJPAaUR2D',"
        + "{ts '2012-03-02 00:00:00'},125.37,'GBP',null,null,'ouuAeSpCLP',"
        + "'IPAY','P988WgvLVJ','JoqVZBxRox','0MBMP4UveC0ExdgnXZzS',"
        + "'WU0D8ZPtAT',null,'auqvTxVzXtmUHujYOEto',null,null,"
        + "{ts '2013-03-08 16:14:33'},'<Clob>',0,1,null,null,'N','N',"
        + "'1a53b9a4-f0df-4abb-9da8-33f23183579s',-1,'H2H','DDI',null,null,"
        + "{ts '2013-03-08 16:14:33'})";

    String sql114 = "INSERT INTO SECT_CHANNEL_DATA  (CHANNEL_TXN_ID,CLIENT_ID,"
        + "CLIENT_NAME,CLIENT_ACCOUNT,COMPANY_ID,CLIENT_REF_NO,VALUE_DATE,"
        + "AMOUNT,CURRENCY,PAY_METHOD,PAY_VEHICLE,ORIG_BANK_ID,BACKOFFICE_CODE,"
        + "BENE_ACCNT_NO,BENE_NAME,BENE_ADDR,BENE_BANK_ID,BENE_BANK_NAME,"
        + "BENE_BANK_ADDR,CLIENT_FILE_ID,CLIENT_FILE_TIME,TXN_CREATED_TIME,"
        + "RAW_DATA,DATA_LIFE_STATUS,MATCH_STATUS,LAST_MATCH_DATE,"
        + "MATCH_CATEG_ID,IS_REJECTED,IS_CANCELLED,FILE_TYPE_ID,ETL_TIME,"
        + "CHANNEL_NAME,TXN_TYPE,HIT_STATUS,ACTUAL_VALUE_DATE,LAST_UPDATE_TIME) "
        + "VALUES('testCol2',1,null,'19882893','6777734','7XWzXJPAaUR2D',"
        + "{ts '2012-03-02 00:00:00'},125.37,'GBP',null,null,'ouuAeSpCLP',"
        + "'IPAY','P988WgvLVJ','JoqVZBxRox','0MBMP4UveC0ExdgnXZzS','WU0D8ZPtAT',"
        + "null,'auqvTxVzXtmUHujYOEto',null,null,{ts '2013-03-08 16:14:33'},"
        + "'<Clob>',0,1,null,null,'N','N','1a53b9a4-f0df-4abb-9da8-33f23183579s',"
        + "-1,'H2H','DDI',null,null,{ts '2013-03-08 16:14:33'})";

    this.executeSql(ds1member, sql1);

    this.executeSql(ds0member, sql114);
    // wait for gateway sender queue to flush
    this.executeSql(sender0,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    Object result = receiver1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select * from SECT_CHANNEL_DATA");
        assertTrue(rs.next());
        int res = rs.getInt(2);
        assertEquals(1, res);
        assertTrue(rs.next());
        res = rs.getInt(2);
        assertEquals(1, res);
        assertFalse(rs.next());
        conn.close();
        return res;
      }
    });
    assertEquals(1, ((Integer)result).intValue());

    // also check for the case of inconsistent schema versions (#48125)
    // create using CREATE in first site while CREATE+ALTER in second
    String table21 = "CREATE TABLE EMP.SECT_CHANNEL_DATA2("
        + "CHANNEL_TXN_ID varchar(30) NOT NULL primary key,"
        + "     CLIENT_ID int not null ," + "     CLIENT_NAME VARCHAR (100) ,"
        + "     CLIENT_ACCOUNT VARCHAR (100) ,"
        + "     COMPANY_ID VARCHAR (100) ,"
        + "     CLIENT_REF_NO VARCHAR (100) ," + "     VALUE_DATE TIMESTAMP ,"
        + "     AMOUNT DECIMAL (16,2) ," + "     CURRENCY VARCHAR (20) ,"
        + "     PAY_METHOD VARCHAR (100) ,"
        + "     PAY_VEHICLE VARCHAR (100) ,"
        + "     ORIG_BANK_ID VARCHAR (100) ,"
        + "     BACKOFFICE_CODE VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "     BENE_ACCNT_NO VARCHAR (100) ,"
        + "     BENE_NAME VARCHAR (100) ," + "     BENE_ADDR VARCHAR (100) ,"
        + "     BENE_BANK_ID VARCHAR (100) ,"
        + "     BENE_BANK_NAME VARCHAR (100) ,"
        + "     BENE_BANK_ADDR VARCHAR (256) ,"
        + "     CLIENT_FILE_ID VARCHAR (100) ,"
        + "     CLIENT_FILE_TIME TIMESTAMP ,"
        + "     TXN_CREATED_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP,"
        + "     RAW_DATA CLOB NOT NULL ,"
        + "     DATA_LIFE_STATUS SMALLINT NOT NULL ,"
        + "     MATCH_STATUS SMALLINT WITH DEFAULT 1 ,"
        + "     LAST_MATCH_DATE TIMESTAMP ," + "     MATCH_CATEG_ID INTEGER ,"
        + "     IS_REJECTED CHAR (1) WITH DEFAULT 'N' ,"
        + "     IS_CANCELLED CHAR (1) WITH DEFAULT 'N' ,"
        + "     FILE_TYPE_ID VARCHAR (36) NOT NULL ,"
        + "     ETL_TIME INTEGER NOT NULL ,"
        + "     CHANNEL_NAME VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "     TXN_TYPE VARCHAR (30) WITH DEFAULT 'UNKNOWN' ,"
        + "     HIT_STATUS SMALLINT ," + "     ACTUAL_VALUE_DATE TIMESTAMP,"
        + "     LAST_UPDATE_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP"
        + "    )" + "    PARTITION BY column(CLIENT_ID)" + "    REDUNDANCY 1"
        + "    EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW"
        + getSQLSuffixClause();
    
    String table22 = "CREATE TABLE EMP.SECT_CHANNEL_DATA2("
        + "CHANNEL_TXN_ID varchar(30) NOT NULL primary key,"
        + "     CLIENT_ID int not null ," + "     CLIENT_NAME VARCHAR (100) ,"
        + "     CLIENT_ACCOUNT VARCHAR (100) ,"
        + "     COMPANY_ID VARCHAR (100) ,"
        + "     CLIENT_REF_NO VARCHAR (100) ," + "     VALUE_DATE TIMESTAMP ,"
        + "     AMOUNT DECIMAL (16,2) ," + "     CURRENCY VARCHAR (20) ,"
        + "     PAY_METHOD VARCHAR (100) ,"
        + "     PAY_VEHICLE VARCHAR (100) ,"
        + "     ORIG_BANK_ID VARCHAR (100) ,"
        + "     BACKOFFICE_CODE VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "     BENE_ACCNT_NO VARCHAR (100) ,"
        + "     BENE_NAME VARCHAR (100) ," + "     BENE_ADDR VARCHAR (100) ,"
        + "     BENE_BANK_ID VARCHAR (100) ,"
        + "     BENE_BANK_NAME VARCHAR (100) ,"
        + "     BENE_BANK_ADDR VARCHAR (256) ,"
        + "     CLIENT_FILE_ID VARCHAR (100) ,"
        + "     CLIENT_FILE_TIME TIMESTAMP ,"
        + "     TXN_CREATED_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP,"
        + "     RAW_DATA CLOB NOT NULL ,"
        + "     DATA_LIFE_STATUS SMALLINT NOT NULL ,"
        + "     MATCH_STATUS SMALLINT WITH DEFAULT 1 ,"
        + "     LAST_MATCH_DATE TIMESTAMP ," + "     MATCH_CATEG_ID INTEGER ,"
        + "     IS_REJECTED CHAR (1) WITH DEFAULT 'N' ,"
        + "     IS_CANCELLED CHAR (1) WITH DEFAULT 'N' ,"
        + "     FILE_TYPE_ID VARCHAR (36) NOT NULL ,"
        + "     ETL_TIME INTEGER NOT NULL ,"
        + "     CHANNEL_NAME VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "     TXN_TYPE VARCHAR (30) WITH DEFAULT 'UNKNOWN' ,"
        + "     HIT_STATUS SMALLINT ," + "     ACTUAL_VALUE_DATE TIMESTAMP"
        + "    )" + "    PARTITION BY column(CLIENT_ID)" + "    REDUNDANCY 1"
        + "    EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW"
        + getSQLSuffixClause();
    
    String table23 = "ALTER TABLE EMP.SECT_CHANNEL_DATA2 ADD COLUMN "
        + "LAST_UPDATE_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP";

    this.executeSql(sender0, table21);
    this.executeSql(receiver1, table22);
    this.executeSql(receiver1, table23);

    this.executeSql(sender0,
        "alter table EMP.SECT_CHANNEL_DATA2 set GatewaySender (MySender)");

    // check the table versions on the two sites should not match
    final SerializableCallable sel1 = new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("VALUES SYS.GET_TABLE_VERSION("
            + "'EMP', 'SECT_CHANNEL_DATA2')");
        assertTrue(rs.next());
        int result = rs.getInt(1);
        assertFalse(rs.next());
        conn.close();
        return result;
      }
    };
    result = sender0.invoke(sel1);
    assertEquals(1, ((Integer)result).intValue());
    result = ds0member.invoke(sel1);
    assertEquals(1, ((Integer)result).intValue());
    result = receiver1.invoke(sel1);
    assertEquals(2, ((Integer)result).intValue());
    result = ds1member.invoke(sel1);
    assertEquals(2, ((Integer)result).intValue());

    // check that below goes through with INCREMENT_TABLE_VERSION procedure
    sql1 = "INSERT INTO EMP.SECT_CHANNEL_DATA2 (CHANNEL_TXN_ID,CLIENT_ID,"
        + "CLIENT_NAME,CLIENT_ACCOUNT,COMPANY_ID,CLIENT_REF_NO,VALUE_DATE,"
        + "AMOUNT,CURRENCY,PAY_METHOD,PAY_VEHICLE,ORIG_BANK_ID,BACKOFFICE_CODE,"
        + "BENE_ACCNT_NO,BENE_NAME,BENE_ADDR,BENE_BANK_ID,BENE_BANK_NAME,"
        + "BENE_BANK_ADDR,CLIENT_FILE_ID,CLIENT_FILE_TIME,TXN_CREATED_TIME,"
        + "RAW_DATA,DATA_LIFE_STATUS,MATCH_STATUS,LAST_MATCH_DATE,"
        + "MATCH_CATEG_ID,IS_REJECTED,IS_CANCELLED,FILE_TYPE_ID,ETL_TIME,"
        + "CHANNEL_NAME,TXN_TYPE,HIT_STATUS,ACTUAL_VALUE_DATE,LAST_UPDATE_TIME) "
        + "VALUES('testCol1',1,null,'19882893','6777734','7XWzXJPAaUR2D',"
        + "{ts '2012-03-02 00:00:00'},125.37,'GBP',null,null,'ouuAeSpCLP',"
        + "'IPAY','P988WgvLVJ','JoqVZBxRox','0MBMP4UveC0ExdgnXZzS',"
        + "'WU0D8ZPtAT',null,'auqvTxVzXtmUHujYOEto',null,null,"
        + "{ts '2013-03-08 16:14:33'},'<Clob>',0,1,null,null,'N','N',"
        + "'1a53b9a4-f0df-4abb-9da8-33f23183579s',-1,'H2H','DDI',null,null,"
        + "{ts '2013-03-08 16:14:33'})";

    sql114 = "INSERT INTO EMP.SECT_CHANNEL_DATA2  (CHANNEL_TXN_ID,CLIENT_ID,"
        + "CLIENT_NAME,CLIENT_ACCOUNT,COMPANY_ID,CLIENT_REF_NO,VALUE_DATE,"
        + "AMOUNT,CURRENCY,PAY_METHOD,PAY_VEHICLE,ORIG_BANK_ID,BACKOFFICE_CODE,"
        + "BENE_ACCNT_NO,BENE_NAME,BENE_ADDR,BENE_BANK_ID,BENE_BANK_NAME,"
        + "BENE_BANK_ADDR,CLIENT_FILE_ID,CLIENT_FILE_TIME,TXN_CREATED_TIME,"
        + "RAW_DATA,DATA_LIFE_STATUS,MATCH_STATUS,LAST_MATCH_DATE,"
        + "MATCH_CATEG_ID,IS_REJECTED,IS_CANCELLED,FILE_TYPE_ID,ETL_TIME,"
        + "CHANNEL_NAME,TXN_TYPE,HIT_STATUS,ACTUAL_VALUE_DATE,LAST_UPDATE_TIME) "
        + "VALUES('testCol2',1,null,'19882893','6777734','7XWzXJPAaUR2D',"
        + "{ts '2012-03-02 00:00:00'},125.37,'GBP',null,null,'ouuAeSpCLP',"
        + "'IPAY','P988WgvLVJ','JoqVZBxRox','0MBMP4UveC0ExdgnXZzS','WU0D8ZPtAT',"
        + "null,'auqvTxVzXtmUHujYOEto',null,null,{ts '2013-03-08 16:14:33'},"
        + "'<Clob>',0,1,null,null,'N','N','1a53b9a4-f0df-4abb-9da8-33f23183579s',"
        + "-1,'H2H','DDI',null,null,{ts '2013-03-08 16:14:33'})";

    this.executeSql(ds0member,
        "CALL SYS.INCREMENT_TABLE_VERSION('EMP', 'SECT_CHANNEL_DATA2', 1)");
    this.executeSql(ds0member, sql114);
    // wait for gateway sender queue to flush
    this.executeSql(sender0,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    final SerializableCallable sel2 = new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt
            .executeQuery("select * from EMP.SECT_CHANNEL_DATA2");
        int numResults = 0;
        while (rs.next()) {
          numResults++;
          assertEquals(1, rs.getInt(2));
        }
        conn.close();
        return numResults;
      }
    };
    // should succeed with INCREMENT_TABLE_VERSION
    result = receiver1.invoke(sel2);
    assertEquals(1, ((Integer)result).intValue());

    // full restart of site1 and recheck
    stopVMNums(-5, -6);
    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    async5 = restartServerVMAsync(5, 0, null, props);
    async6 = restartServerVMAsync(6, 0, "tableGrp,senderGrp", props);
    joinVMs(false, async5, async6);
    this.executeSql(sender0, sql1);
    // wait for gateway sender queue to flush
    this.executeSql(sender0,
        "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    result = receiver1.invoke(sel2);
    assertEquals(2, ((Integer)result).intValue());
  }

  public void testBasicDMLOperationPropagationToWanSitesOnReplicatedAlterTable()
      throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
        + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Member
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(1, 0, "tableGrp", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "tableGrp,senderGrp", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);

    serverExecute(5, createGatewaySender("mySender", "2", "senderGrp"));

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    this.executeSql(reciever, "create table EMP.TESTTABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) replicate server groups (sg1)"
        + getSQLSuffixClause());
    this.executeSql(sender, "create table EMP.TESTTABLE (ID int, "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) replicate server groups (tableGrp)"
        + getSQLSuffixClause());

    this.executeSql(sender,
        "alter table EMP.TESTTABLE set GatewaySender (MySender)");

    // check the SYSTABLES GATEWAYSENDERS column
    SerializableCallable checkSYSTABLES = new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select gatewaysenders from "
            + "sys.systables where tablename='TESTTABLE'");
        assertTrue(rs.next());
        String senders = rs.getString(1);
        rs.close();
        conn.close();
        return senders;
      }
    };
    String senders = (String)serverExecute(4, checkSYSTABLES);
    assertEquals("MYSENDER", senders);
    senders = (String)serverExecute(5, checkSYSTABLES);
    assertEquals("MYSENDER", senders);

    serverExecute(4, batchInsert());
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    this.executeSql(sender,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where ID = 1");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation
    this.executeSql(sender,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }
  public void testBasicDMLOperationPropagationToWanSitesOnReplicatedTable()
      throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
        + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int, "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) replicate gatewaysender (MYSENDER) "
        + "server groups (sg1)"
        + getSQLSuffixClause();
    this.executeSql(reciever, createTable);
    this.executeSql(sender, createTable);

    serverExecute(4, batchInsert());
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    this.executeSql(sender,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where ID = 1");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation
    this.executeSql(sender,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

  }

  public void DEBUGtestBasicDMLOperationPropagationToWanSitesOnReplicatedTable()
      throws Exception {

    // site1 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // site2 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
        + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // site2 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "DEMOGOUP", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    serverExecute(3, createConfiguration2());
    
    

    // site1 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "DEMOGOUP", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createConfiguration1());
    serverExecute(4, startSender());
    
    serverExecute(4, doInserts());
    serverExecute(4, doUpdates());
    pause(5000);
    
  }
  
  public void testBasicDMLOperationPropagationToWanSitesOnPartitionedTable()
      throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    // String createTable =
    // "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) , primary key (ID)) replicate gatewaysender (MYSENDER) server groups (sg1) ";
    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) gatewaysender (MYSENDER) server groups (sg1) " + getSQLSuffixClause();
    this.executeSql(reciever, createTable);
    this.executeSql(sender, createTable);

    // Test insert propagation
    // this.executeSql(sender,
    // "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");

    serverExecute(4, batchInsert());
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    this.executeSql(sender,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where ID = 1");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation
    this.executeSql(sender,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

  }

  public void testBasicDMLOperationPropagationToWanSitesOnPartitionedTable_1()
      throws Exception {

    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024)) gatewaysender (MYSENDER) server groups (sg1) " + getSQLSuffixClause();
    this.executeSql(reciever, createTable);
    this.executeSql(sender, createTable);

    // Test insert propagation
    this.executeSql(sender,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    this.executeSql(sender,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where ID = 1");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation
    this.executeSql(sender,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

  }

  public void testTableWithNoPrimaryKeys_2Sites() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
    + "/lib/GemFireXDGatewayDUnit.xml";

    startSites(2);

    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    
    executeSql(SITE_A,"CREATE gatewaysender mySender (remotedsid 2 isparallel false ) server groups (sg1)");
    
    final String createPTable = "create table EMP.REPLICATED_TABLE(ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024)) replicate server groups (sg1)";

    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    VM accessor = getSiteAccessor(SITE_A);
    
    accessor.invoke(GfxdSerialWanDUnit.class, "batchInsert_NoPrimaryKey");
    
    sqlExecuteVerify(SITE_A, "select * from EMP.REPLICATED_TABLE", goldenFile,
        "batch_id5", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select * from EMP.REPLICATED_TABLE", goldenFile,
        "batch_id5", true, false);
    
    executeSql(SITE_A, "Update EMP.REPLICATED_TABLE set DESCRIPTION = 'First' where ADDRESS = 'J 604'");
    sqlExecuteVerify(SITE_A, "select * from EMP.REPLICATED_TABLE", goldenFile,
        "batch_id6", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select * from EMP.REPLICATED_TABLE", goldenFile,
        "batch_id6", true, false);
    
    executeSql(SITE_B, "insert into EMP.REPLICATED_TABLE values (11, 'First', 'X 604')");
    executeSql(SITE_B, "insert into EMP.REPLICATED_TABLE values (12, 'First', 'Y 604')");
    executeSql(SITE_B, "insert into EMP.REPLICATED_TABLE values (13, 'First', 'Z 604')");
    executeSql(SITE_B, "insert into EMP.REPLICATED_TABLE values (14, 'First', 'X 604')");
    executeSql(SITE_B, "insert into EMP.REPLICATED_TABLE values (15, 'First', 'Y 604')");
    
    sqlExecuteVerify(SITE_B, "select * from EMP.REPLICATED_TABLE", goldenFile,
        "batch_id7", true, false);
  }
  
  public void testBasicDMLOperationPropagationToWanSitesOnPartitionedTableWithIdentityAlways()
      throws Exception {

    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM receiver = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(receiver, "create schema EMP");
    this.executeSql(sender, "create schema EMP");
    String createTable = "create table EMP.TESTTABLE (ID int, "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) partition by primary key gatewaysender "
        + "(MYSENDER) server groups (sg1)";
    // also test that DMLs are always propagated as SQL strings for tables
    // having identity columns (#43012)
    String createIdentTable = "create table EMP.TESTTABLE2 (ID bigint "
        + "GENERATED ALWAYS AS IDENTITY, DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024), primary key (ID)) partition by primary key "
        + "gatewaysender (MYSENDER) server groups (sg1)";
    String createIdentTable2 = "create table EMP.TESTTABLE3 (ID bigint "
        + "GENERATED ALWAYS AS IDENTITY, DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024), primary key (ID)) partition by primary key "
        + "gatewaysender (MYSENDER) server groups (sg1)";
    String createIdentTable3 = "create table EMP.TESTTABLE4 (ID bigint "
        + "GENERATED BY DEFAULT AS IDENTITY, DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024), primary key (DESCRIPTION)) partition by "
        + "primary key gatewaysender (MYSENDER) server groups (sg1)";

    this.executeSql(receiver, createTable + getSQLSuffixClause());
    this.executeSql(receiver, createIdentTable + getSQLSuffixClause());
    this.executeSql(receiver, createIdentTable2 + getSQLSuffixClause());
    this.executeSql(receiver, createIdentTable3 + getSQLSuffixClause());
    this.executeSql(sender, createTable + getSQLSuffixClause());
    this.executeSql(sender, createIdentTable + getSQLSuffixClause());
    this.executeSql(sender, createIdentTable2 + getSQLSuffixClause());
    this.executeSql(sender, createIdentTable3 + getSQLSuffixClause());

    // Test insert propagation
    this.executeSql(sender,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(receiver, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test identy insert propagation
    // Do some inserts on receiver side to force overlap of identity values
    // in case inserts go as puts
    this.executeSql(receiver,
        "insert into EMP.TESTTABLE2 (DESCRIPTION, ADDRESS) "
            + "values ('First', 'J 604')");
    this.executeSql(receiver,
        "insert into EMP.TESTTABLE2 (DESCRIPTION, ADDRESS) "
            + "values ('Second', 'J 605')");
    this.executeSql(receiver,
        "insert into EMP.TESTTABLE3 (DESCRIPTION, ADDRESS) "
            + "values ('First', 'J 604')");
    this.executeSql(receiver,
        "insert into EMP.TESTTABLE3 (DESCRIPTION, ADDRESS) "
            + "values ('Second', 'J 605')");
    this.executeSql(receiver,
        "insert into EMP.TESTTABLE4 (DESCRIPTION, ADDRESS) "
            + "values ('First', 'J 604')");
    this.executeSql(receiver,
        "insert into EMP.TESTTABLE4 (DESCRIPTION, ADDRESS) "
            + "values ('Second', 'J 605')");
    // now check propagation from sender
    this.executeSql(sender,
        "insert into EMP.TESTTABLE2 (DESCRIPTION, ADDRESS) "
            + "values ('Third', 'J 606')");
    this.executeSql(sender,
        "insert into EMP.TESTTABLE2 (DESCRIPTION, ADDRESS) "
            + "values ('Fourth', 'J 607')");
    this.executeSql(sender,
        "insert into EMP.TESTTABLE3 (DESCRIPTION, ADDRESS) "
            + "values ('Third', 'J 606')");
    this.executeSql(sender,
        "insert into EMP.TESTTABLE3 (DESCRIPTION, ADDRESS) "
            + "values ('Fourth', 'J 607')");
    this.executeSql(sender,
        "insert into EMP.TESTTABLE4 (DESCRIPTION, ADDRESS) "
            + "values ('Third', 'J 606')");
    this.executeSql(sender,
        "insert into EMP.TESTTABLE4 (DESCRIPTION, ADDRESS) "
            + "values ('Fourth', 'J 607')");
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // these should have propagated to wan site 2 as DML string so no overlap
    this.sqlExecuteVerify(receiver,
        "select DESCRIPTION from EMP.TESTTABLE2 order by ID", goldenFile,
        "ident_id", true, false);
    this.sqlExecuteVerify(receiver,
        "select DESCRIPTION from EMP.TESTTABLE3 order by ID", goldenFile,
        "ident_id", true, false);
    this.sqlExecuteVerify(receiver,
        "select DESCRIPTION from EMP.TESTTABLE4 order by ID", goldenFile,
        "ident_id", true, false);
    // also check on sender
    this.sqlExecuteVerify(sender,
        "select DESCRIPTION from EMP.TESTTABLE2 order by ID", goldenFile,
        "ident_id2", true, false);
    this.sqlExecuteVerify(sender,
        "select DESCRIPTION from EMP.TESTTABLE3 order by ID", goldenFile,
        "ident_id2", true, false);
    this.sqlExecuteVerify(sender,
        "select DESCRIPTION from EMP.TESTTABLE4 order by ID", goldenFile,
        "ident_id2", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    this.executeSql(sender,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation
    this.executeSql(sender,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void testBasicDMLOperationPropagationToWanSitesOnReplicatedTableWithSkipListenerFlag()
      throws Exception {

    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2"
        + fileSeparator + "Receiver");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);
    addExpectedException(this.serverVMs.get(2),
        new Object[] { CacheClosedException.class });

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1"
        + fileSeparator + "Sender");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);
    addExpectedException(this.serverVMs.get(3),
        new Object[] { CacheClosedException.class });

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    // DS0 Accessor
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1"
        + fileSeparator + "Accessor");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    props.put(com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS, "true");
    Connection conn = TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) replicate gatewaysender (MYSENDER) server groups (sg1) ";
    this.executeSql(reciever, createTable + getSQLSuffixClause());
    this.executeSql(sender, createTable + getSQLSuffixClause());

    // Test insert propagation
    stmt.execute("insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    stmt.execute("Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation

    stmt.execute("Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void testBasicDMLOperationPropagationToWanSitesOnPartitionedTableWithSkipListenerFlag()
      throws Exception {

    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2"
        + fileSeparator + "Receiver");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);
    addExpectedException(this.serverVMs.get(2),
        new Object[] { CacheClosedException.class });

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1"
        + fileSeparator + "Sender");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);
    addExpectedException(this.serverVMs.get(3),
        new Object[] { CacheClosedException.class });

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    // DS0 Accessor
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1"
        + fileSeparator + "Accessor");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    props.put(com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS, "true");
    Connection conn = TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) gatewaysender (MYSENDER) server groups (sg1) ";
    this.executeSql(reciever, createTable + getSQLSuffixClause());
    this.executeSql(sender, createTable + getSQLSuffixClause());

    // Test insert propagation
    stmt.execute("insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    stmt.execute("Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation

    stmt.execute("Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void testGatewayReceivers() throws Exception {
    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    Properties props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    startLocatorVM("localhost", nyPort, null, props);
    // assertTrue(this.serverVMs.size() == 1);

    // DS1 Receiver1
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(2, 0, "sg1", props);
    joinVMs(true, async3);
    // assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver2
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async4);
    // assertTrue(this.serverVMs.size() == 3);

    serverExecute(3, createGatewayReceiver(11111, 11113));

  }

  public void testNonPKTableInserts() throws Exception {
    try {
      String goldenFile = TestUtil.getResourcesDir()
          + "/lib/GemFireXDGatewayDUnit.xml";

      // DS0 Locator
      Properties props = new Properties();
      int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
      props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
      props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
      props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
          + "]");
      startLocatorVM("localhost", lnPort, null, props);
      assertTrue(this.serverVMs.size() == 1);

      // DS1 Locator
      int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
      props = new Properties();
      props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
      props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
      props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
          + "]");
      props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
          "localhost[" + lnPort + "]");
      startLocatorVM("localhost", nyPort, null, props);
      assertTrue(this.serverVMs.size() == 2);

      // DS1 Receiver
      props = new Properties();
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
      props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
          + "]");
      AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
      joinVMs(true, async3);
      assertTrue(this.serverVMs.size() == 3);
      addExpectedException("Could not connect", this.serverVMs.get(2));

      serverExecute(3, createGatewayReceiver(0, 0));
      serverExecute(3, createGatewaySender("mySender", "1", "sg1"));

      // DS0 Sender
      props = new Properties();
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
      props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
          + "]");
      // props.put(Attribute.SKIP_LISTENERS, "true");
      AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
      joinVMs(true, async4);
      
      assertTrue(this.serverVMs.size() == 4);
      addExpectedException("Could not connect", this.serverVMs.get(3));
      serverExecute(4, createGatewayReceiver(0, 0));
      serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

      VM reciever = serverVMs.get(2);
      VM sender = serverVMs.get(3);

      this.executeSql(reciever, "create schema EMP");
      this.executeSql(sender, "create schema EMP");

      String createTable = "create table EMP.TESTTABLE (ID bigint , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024)) "
          + "gatewaysender (MYSENDER) server groups (sg1) ";
      this.executeSql(reciever, createTable + getSQLSuffixClause());
      this.executeSql(sender, createTable + getSQLSuffixClause());

      // Test insert propagation
      for (int i = 0; i < 20; ++i) {
        this.executeSql(sender, "insert into EMP.TESTTABLE values(" + i
            + ", 'First', 'J 604')");
      }
      serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
      final List<?> senderKeys = (List<?>)serverExecute(4,
          new SerializableCallable() {
            public Object call() throws Exception {
              String path = Misc.getRegionPath("EMP", "TESTTABLE", null);
              LocalRegion rgn = (LocalRegion)Misc.getRegion(path, true, false);
              List<Long> keys = new ArrayList<Long>();
              @SuppressWarnings("unchecked")
              Iterator<Long> itr = rgn.keySet().iterator();
              while (itr.hasNext()) {
                Long key = itr.next();
                keys.add(key);
                Object value = rgn.get(key);
                Misc.getCacheLogWriter().info(
                    "YOGI sender " + key + ":"
                        + Arrays.toString((value instanceof ByteSource ? ((ByteSource) value).getRowBytes() : (byte[]) value)));
              }
              return keys;
            }
          });
      final List<?> rcvrKeys = (List<?>)serverExecute(3,
          new SerializableCallable() {
        public Object call() throws Exception {
          String path = Misc.getRegionPath("EMP", "TESTTABLE", null);
          LocalRegion rgn = (LocalRegion)Misc.getRegion(path, true, false);
          List<Long> keys = new ArrayList<Long>();
          @SuppressWarnings("unchecked")
          Iterator<Long> itr = rgn.keySet().iterator();

          while (itr.hasNext()) {
            Long key = itr.next();
            keys.add(key);
            Object value = rgn.get(key);
            Misc.getCacheLogWriter().info(
                "YOGI receiver " + key + ":"
                    + Arrays.toString((value instanceof ByteSource ? ((ByteSource) value).getRowBytes() : (byte[]) value)));
          }
          return keys;
        }
      });

      assertEquals(20, senderKeys.size());
      assertEquals(20, rcvrKeys.size());
      // TODO: [sumedh] what's the purpose of below observer?
      // it will always create PK constraint violations??
      //serverExecute(3, setObserver(rcvrKeys));
      for (int i = 0; i < 20; ++i) {
        this.executeSql(reciever, "insert into EMP.TESTTABLE values(" + i
            + ", 'Second', 'J 604')");
      }
      serverExecute(3, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
      this.sqlExecuteVerify(reciever, "select count(*) from EMP.TESTTABLE",
          null, "40", true, false);
      this.sqlExecuteVerify(
          sender,
          "select count(*) as FIELD1 from EMP.TESTTABLE where DESCRIPTION =  'First' ",
          goldenFile, "id6", true, false);

    }
    finally {
      serverExecute(3, unsetObserver());
    }
  }

  public void testConfig1() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    serverExecute(3, createGatewayReceiver(0, 0));
    // serverExecute(3, createGatewaySender("mySender", "1", "sg1"));

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    // props.put(Attribute.SKIP_LISTENERS, "true");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    // serverExecute(4, createGatewayReceiver(0, 0));
    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    // DS0 Member
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    // props.put(Attribute.SKIP_LISTENERS, "true");
    AsyncVM async5 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);

    // DS1 Member
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(4);

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) partition by primary key redundancy 1 gatewaysender (MYSENDER) server groups (sg1)";
    this.executeSql(reciever, createTable + getSQLSuffixClause());
    this.executeSql(sender, createTable + getSQLSuffixClause());

    // Test insert propagation
    this.executeSql(sender,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    this.executeSql(sender,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where ID = 1");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation
    this.executeSql(sender,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(reciever, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void testMultiSitePropagation() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // TK Locator
    int tkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "3");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + nyPort + "]");
    startLocatorVM("localhost", tkPort, null, props);
    assertTrue(this.serverVMs.size() == 3);
    // TK Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);
    serverExecute(4, createGatewayReceiver(0, 0));
    // NY Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);
    serverExecute(5, createGatewayReceiver(0, 0));
    // NY Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);
    addExpectedException("Could not connect", this.serverVMs.get(5));
    serverExecute(6, createGatewaySender("mySender", "3", "sg1"));
    // LN Receiver & Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async7 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async7);
    assertTrue(this.serverVMs.size() == 7);
    addExpectedException("Could not connect", this.serverVMs.get(6));
    serverExecute(7, createGatewayReceiver(0, 0));
    serverExecute(7, createGatewaySender("mySender", "2", "sg1"));
    // TK Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async8 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async8);
    assertTrue(this.serverVMs.size() == 8);
    addExpectedException("Could not connect", this.serverVMs.get(7));
    serverExecute(8, createGatewaySender("mySender", "1", "sg1"));

    VM tk = serverVMs.get(3);
    VM ny = serverVMs.get(4);
    VM ln = serverVMs.get(6);

    this.executeSql(tk, "create schema EMP");
    this.executeSql(ny, "create schema EMP");
    this.executeSql(ln, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) partition by primary key redundancy 1 gatewaysender (MYSENDER) server groups (sg1)";
    this.executeSql(tk, createTable + getSQLSuffixClause());
    this.executeSql(ny, createTable + getSQLSuffixClause());
    this.executeSql(ln, createTable + getSQLSuffixClause());

    // Test insert propagation
    this.executeSql(ln,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    this.sqlExecuteVerify(ln, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(ny, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(tk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    this.executeSql(ln,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    
    // Test delete propagation
    this.executeSql(ln,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void testMultiSiteAlltoAllPropagation() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // TK Locator
    int tkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "3");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + nyPort + "]");
    startLocatorVM("localhost", tkPort, null, props);
    assertTrue(this.serverVMs.size() == 3);

    // TK Sender && Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);
    addExpectedException("Could not connect", this.serverVMs.get(3));
    serverExecute(4, createGatewayReceiver(0, 0));
    serverExecute(4, createGatewaySender("mySenderA", "1", "sg1"));
    serverExecute(4, createGatewaySender("mySenderB", "2", "sg1"));

    // NY Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);
    addExpectedException("Could not connect", this.serverVMs.get(4));
    serverExecute(5, createGatewayReceiver(0, 0));
    serverExecute(5, createGatewaySender("mySenderA", "1", "sg1"));
    serverExecute(5, createGatewaySender("mySenderB", "3", "sg1"));

    // LN Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);
    addExpectedException("Could not connect", this.serverVMs.get(5));
    serverExecute(6, createGatewayReceiver(0, 0));
    serverExecute(6, createGatewaySender("mySenderA", "2", "sg1"));
    serverExecute(6, createGatewaySender("mySenderB", "3", "sg1"));

    VM tk = serverVMs.get(3);
    VM ny = serverVMs.get(4);
    VM ln = serverVMs.get(5);

    this.executeSql(tk, "create schema EMP");
    this.executeSql(ny, "create schema EMP");
    this.executeSql(ln, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) partition by primary key redundancy 1 gatewaysender (MYSENDERA,MYSENDERB) server groups (sg1)";
    this.executeSql(tk, createTable + getSQLSuffixClause());
    this.executeSql(ny, createTable + getSQLSuffixClause());
    this.executeSql(ln, createTable + getSQLSuffixClause());

    // Test insert propagation
    this.executeSql(ln,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    this.sqlExecuteVerify(ln, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(ny, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    this.sqlExecuteVerify(tk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    this.executeSql(ln,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    
    this.executeSql(ln,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void testMultiFourSitePropagation() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // TK Locator
    int tkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "3");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + nyPort + "]");
    startLocatorVM("localhost", tkPort, null, props);
    assertTrue(this.serverVMs.size() == 3);

    // HK Locator
    int hkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "4");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + tkPort + "]");
    startLocatorVM("localhost", hkPort, null, props);
    assertTrue(this.serverVMs.size() == 4);

    // HK Sender && Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);
    addExpectedException("Could not connect", this.serverVMs.get(4));
    serverExecute(5, createGatewayReceiver(0, 0));
    serverExecute(5, createGatewaySender("mySender", "1", "sg1"));

    // TK Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);
    addExpectedException("Could not connect", this.serverVMs.get(5));
    serverExecute(6, createGatewayReceiver(0, 0));
    serverExecute(6, createGatewaySender("mySender", "4", "sg1"));

    // NY Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async7 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async7);
    assertTrue(this.serverVMs.size() == 7);
    addExpectedException("Could not connect", this.serverVMs.get(6));
    serverExecute(7, createGatewayReceiver(0, 0));
    serverExecute(7, createGatewaySender("mySender", "3", "sg1"));

    // LN Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async8 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async8);
    assertTrue(this.serverVMs.size() == 8);
    addExpectedException("Could not connect", this.serverVMs.get(7));
    serverExecute(8, createGatewayReceiver(0, 0));
    serverExecute(8, createGatewaySender("mySender", "2", "sg1"));

    VM hk = serverVMs.get(4);
    VM tk = serverVMs.get(5);
    VM ny = serverVMs.get(6);
    VM ln = serverVMs.get(7);

    this.executeSql(hk, "create schema EMP");
    this.executeSql(tk, "create schema EMP");
    this.executeSql(ny, "create schema EMP");
    this.executeSql(ln, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) partition by primary key redundancy 1 gatewaysender (MYSENDER) server groups (sg1)";
    this.executeSql(hk, createTable + getSQLSuffixClause());
    this.executeSql(tk, createTable + getSQLSuffixClause());
    this.executeSql(ny, createTable + getSQLSuffixClause());
    this.executeSql(ln, createTable + getSQLSuffixClause());

    this.executeSql(ln,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    this.sqlExecuteVerify(ln, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(ny, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(tk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(hk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    this.executeSql(ln,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    
    this.executeSql(ln,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));

    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void testMultiSiteAlltoAllFourSitePropagation() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // TK Locator
    int tkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "3");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + nyPort + "]");
    startLocatorVM("localhost", tkPort, null, props);
    assertTrue(this.serverVMs.size() == 3);

    // HK Locator
    int hkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "4");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + tkPort + "]");
    startLocatorVM("localhost", hkPort, null, props);
    assertTrue(this.serverVMs.size() == 4);

    // HK Sender && Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);
    addExpectedException("Could not connect", this.serverVMs.get(4));
    serverExecute(5, createGatewayReceiver(0, 0));
    serverExecute(5, createGatewaySender("mySenderA", "1", "sg1"));
    serverExecute(5, createGatewaySender("mySenderB", "2", "sg1"));
    serverExecute(5, createGatewaySender("mySenderC", "3", "sg1"));

    // TK Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);
    addExpectedException("Could not connect", this.serverVMs.get(5));
    serverExecute(6, createGatewayReceiver(0, 0));
    serverExecute(6, createGatewaySender("mySenderA", "1", "sg1"));
    serverExecute(6, createGatewaySender("mySenderB", "2", "sg1"));
    serverExecute(6, createGatewaySender("mySenderC", "4", "sg1"));

    // NY Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async7 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async7);
    assertTrue(this.serverVMs.size() == 7);
    addExpectedException("Could not connect", this.serverVMs.get(6));
    serverExecute(7, createGatewayReceiver(0, 0));
    serverExecute(7, createGatewaySender("mySenderA", "1", "sg1"));
    serverExecute(7, createGatewaySender("mySenderB", "3", "sg1"));
    serverExecute(7, createGatewaySender("mySenderC", "4", "sg1"));

    // LN Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async8 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async8);
    assertTrue(this.serverVMs.size() == 8);
    addExpectedException("Could not connect", this.serverVMs.get(7));
    serverExecute(8, createGatewayReceiver(0, 0));
    serverExecute(8, createGatewaySender("mySenderA", "2", "sg1"));
    serverExecute(8, createGatewaySender("mySenderB", "3", "sg1"));
    serverExecute(8, createGatewaySender("mySenderC", "4", "sg1"));

    VM hk = serverVMs.get(4);
    VM tk = serverVMs.get(5);
    VM ny = serverVMs.get(6);
    VM ln = serverVMs.get(7);
    
    this.executeSql(hk, "create schema EMP");
    this.executeSql(tk, "create schema EMP");
    this.executeSql(ny, "create schema EMP");
    this.executeSql(ln, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) partition by primary key redundancy 1 gatewaysender (MYSENDERA,MYSENDERB,MYSENDERC) server groups (sg1)";
    this.executeSql(hk, createTable + getSQLSuffixClause());
    this.executeSql(tk, createTable + getSQLSuffixClause());
    this.executeSql(ny, createTable + getSQLSuffixClause());
    this.executeSql(ln, createTable + getSQLSuffixClause());

    this.executeSql(ln,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    this.sqlExecuteVerify(ln, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(ny, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(tk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(hk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    this.executeSql(ln,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    this.executeSql(ln,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

  }
  public void testMultiFourRandomSitePropagation_2() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // TK Locator
    int tkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "3");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + nyPort + "]");
    startLocatorVM("localhost", tkPort, null, props);
    assertTrue(this.serverVMs.size() == 3);

    // HK Locator
    int hkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "4");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + tkPort + "]");
    startLocatorVM("localhost", hkPort, null, props);
    assertTrue(this.serverVMs.size() == 4);

    // HK Sender && Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);
    addExpectedException("Could not connect", this.serverVMs.get(4));
    serverExecute(5, createGatewayReceiver(0, 0));
    serverExecute(5, createGatewaySender("mySenderA", "2", "sg1"));
    serverExecute(5, createGatewaySender("mySenderB", "3", "sg1"));

    // TK Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);
    addExpectedException("Could not connect", this.serverVMs.get(5));
    serverExecute(6, createGatewayReceiver(0, 0));
    serverExecute(6, createGatewaySender("mySenderA", "2", "sg1"));
    serverExecute(6, createGatewaySender("mySenderB", "4", "sg1"));

    // NY Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async7 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async7);
    assertTrue(this.serverVMs.size() == 7);
    addExpectedException("Could not connect", this.serverVMs.get(6));
    serverExecute(7, createGatewayReceiver(0, 0));
    serverExecute(7, createGatewaySender("mySenderA", "1", "sg1"));
    serverExecute(7, createGatewaySender("mySenderB", "3", "sg1"));
    serverExecute(7, createGatewaySender("mySenderC", "4", "sg1"));

    // LN Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async8 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async8);
    assertTrue(this.serverVMs.size() == 8);
    addExpectedException("Could not connect", this.serverVMs.get(7));
    serverExecute(8, createGatewayReceiver(0, 0));
    serverExecute(8, createGatewaySender("mySenderA", "2", "sg1"));

    VM hk = serverVMs.get(4);
    VM tk = serverVMs.get(5);
    VM ny = serverVMs.get(6);
    VM ln = serverVMs.get(7);

    this.executeSql(hk, "create schema EMP");
    this.executeSql(tk, "create schema EMP");
    this.executeSql(ny, "create schema EMP");
    this.executeSql(ln, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) partition by primary key redundancy 1 gatewaysender (MYSENDERA, MYSENDERB, MYSENDERC) server groups (sg1)";
    this.executeSql(hk, createTable + getSQLSuffixClause());
    this.executeSql(tk, createTable + getSQLSuffixClause());
    this.executeSql(ny, createTable + getSQLSuffixClause());
    this.executeSql(ln, createTable + getSQLSuffixClause());

    this.executeSql(ln,
        "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
    this.sqlExecuteVerify(ln, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));

    this.sqlExecuteVerify(ny, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(tk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    
    this.sqlExecuteVerify(hk, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    this.executeSql(ln,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));


    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));

    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    
    this.executeSql(ln,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(7, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));

    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
  }

  public void YOGS_testBug44506() throws Exception {
    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // TK Locator
    int tkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "3");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + nyPort + "]");
    startLocatorVM("localhost", tkPort, null, props);
    assertTrue(this.serverVMs.size() == 3);

    // TK Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);
    serverExecute(4, createGatewayReceiver(0, 0));
    serverExecute(4, createGatewaySender("mySender", "1", "sg1"));

    // NY Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);
    serverExecute(5, createGatewayReceiver(0, 0));
    serverExecute(5, createGatewaySender("mySender", "3", "sg1"));

    // LN Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);
    serverExecute(6, createGatewayReceiver(0, 0));
    serverExecute(6, createGatewaySender("mySender", "2", "sg1"));

    VM tk = serverVMs.get(3);
    VM ny = serverVMs.get(4);
    VM ln = serverVMs.get(5);

//    this.executeSql(tk, "create schema EMP");
//    this.executeSql(ny, "create schema EMP");
//    this.executeSql(ln, "create schema EMP");

    //String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024)) gatewaysender (MYSENDER) server groups (sg1)";
    
    String createTable  = "create table trade.txhistory(cid int, oid int, sid int, qty int, price decimal (30, 20), " +
    		"type varchar(10), tid int,  constraint type_ch check (type in ('buy', 'sell')))  " +
    		"partition by range (cid) ( VALUES BETWEEN 0 AND 999, VALUES BETWEEN 1009 AND 1102, " +
    		"VALUES BETWEEN 1193 AND 1251, VALUES BETWEEN 1291 AND 1677, VALUES BETWEEN 1678 AND 10000) GATEWAYSENDER(MYSENDER)";
    this.executeSql(tk, createTable);
    this.executeSql(ny, createTable);
    this.executeSql(ln, createTable);

    //struct(CID:4472,OID:3484,SID:487,QTY:609,PRICE:46.62000000000000000000,ORDERTIME:2012-03-27 20:18:39.919,TYPE:buy,TID:256)
    //struct(CID:4906,OID:3113,SID:97,QTY:644,PRICE:13.28000000000000100000,ORDERTIME:2012-03-27 20:18:25.762,TYPE:buy,TID:221)
    //struct(CID:3864,OID:3160,SID:904,QTY:941,PRICE:36.81000000000000000000,ORDERTIME:2012-03-27 20:18:27.32,TYPE:buy,TID:232)
    
    this.executeSql(ln, "insert into trade.txhistory values (4472, 3484, 487,609,46.62,'buy',256)");
    this.executeSql(ln, "insert into trade.txhistory values (1292, 3113, 97, 644,13.28,'buy',221)");
//    this.executeSql(ln, "insert into trade.txhistory values (4472, 3484, 487,609,46.62,'buy',256)");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (5555, 'First', 'J 604')");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (7777, 'First', 'J 604')");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (4444, 'First', 'J 604')");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (1111, 'First', 'J 604')");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (3333, 'First', 'J 604')");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (2222, 'First', 'J 604')");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (8888, 'First', 'J 604')");
//    this.executeSql(ln, "insert into EMP.TESTTABLE values (6666, 'First', 'J 604')");
    
        
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(5, verifyRegionSize("/TRADE/TXHISTORY", 9));
    
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(4, verifyRegionSize("/TRADE/TXHISTORY", 9));
    
    this.executeSql(ln,
        "Update TRADE.TXHISTORY set type = 'sell'");
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(5, verifyRegionSize("/TRADE/TXHISTORY", 9));
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(4, verifyRegionSize("/TRADE/TXHISTORY", 9));

    this.executeSql(ln,
        "Delete from  TRADE.TXHISTORY where type = 'sell'");
    serverExecute(6, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(5, verifyRegionSize("/TRADE/TXHISTORY", 9));
    serverExecute(5, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(4, verifyRegionSize("/TRADE/TXHISTORY", 9));
    
  }
  
  public void testBug44506() throws Exception {
    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // NY Receiver 
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);
    serverExecute(3, createGatewayReceiver(0,0));
    
    // LN Sender 
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);
    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM ln = serverVMs.get(3);    
    VM ny = serverVMs.get(2);
    
    String createTableLn = "create table trade.txhistory( cid int, oid int, sid int, qty int, type varchar(10), tid int,  constraint type_ch check (type in ('buy', 'sell')))  partition"
        + " by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17))   GATEWAYSENDER(MYSENDER) server groups(sg1)";

    String createTableNy = "create table trade.txhistory( cid int, oid int, sid int, qty int, type varchar(10), tid int,  constraint type_ch check (type in ('buy', 'sell')))  partition"
      + " by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) server groups(sg1) ";

    this.executeSql(ny, createTableNy + getSQLSuffixClause());
    this.executeSql(ln, createTableLn + getSQLSuffixClause());
    
    this.executeSql(ln, "insert into trade.txhistory values (3917, 3484, 170 ,609,'buy',130)");
    this.executeSql(ln, "insert into trade.txhistory values (3917, 3484, 170 ,609,'buy',131)");
    this.executeSql(ln, "insert into trade.txhistory values (3917, 3484, 170 ,609,'buy',132)");
    this.executeSql(ln, "insert into trade.txhistory values (3917, 3484, 170 ,609,'buy',133)");
    this.executeSql(ln, "insert into trade.txhistory values (3917, 3484, 170 ,609,'buy',134)");
    serverExecute(4, verifyRegionSize("/TRADE/TXHISTORY", 5));
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(3, verifyRegionSize("/TRADE/TXHISTORY", 5));
    
    this.executeSql(ln, "delete from trade.txhistory where cid=3917 and sid=170 and tid=132");
    serverExecute(4, verifyRegionSize("/TRADE/TXHISTORY", 4));
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    serverExecute(3, verifyRegionSize("/TRADE/TXHISTORY", 4));
  }
  
  public void testWanTransactions() throws Exception {
    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // NY Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);
    addExpectedException(this.serverVMs.get(2),
        new Object[] { CacheClosedException.class });
    serverExecute(3, createGatewayReceiver(0, 0));

    // LN Sender & Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);
    addExpectedException(this.serverVMs.get(3),
        new Object[] { CacheClosedException.class });
    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM ny = serverVMs.get(2);
    VM ln = serverVMs.get(3);

    String createCustTable =  "create table customer (c_w_id  integer not null,  " +
    		                           "      c_d_id  integer not null, " +
    		                           "      c_id    integer not null, " +
    		                           "      c_discount   decimal(4,4),"+
    		                           "      c_credit       char(2)," +
    		                           "      c_last         varchar(16)," +
    		                           "      c_first        varchar(16)," +
    		                           "      c_credit_lim   decimal(12,2)," +
    		                           "      c_balance      decimal(12,2),"+
                                           "      c_ytd_payment  float," +
                                           "      c_payment_cnt  integer," +
                                           "      c_delivery_cnt integer," +
                                           "      c_street_1     varchar(20)," +
                                           "      c_street_2     varchar(20)," +
                                           "      c_city         varchar(20),"+
                                           "      c_state        char(2)," +
                                           "      c_zip          char(9),        " +
                                           "      c_phone        char(16)," +
                                           "      c_since        timestamp," +
                                           "      c_middle       char(2)," +
                                           "      c_data         varchar(500)"+
                                           ") partition by (c_w_id) redundancy 1 buckets 9 gatewaysender (MYSENDER )" ;

    String createHistoryTable = "create table history ("+
      "  h_c_id   integer,"+
      "  h_c_d_id integer,"+
      "  h_c_w_id integer,"+
      "  h_d_id   integer,"+
      "  h_w_id   integer,"+
      "  h_date   timestamp,"+
      "  h_amount decimal(6,2),"+
      "  h_data   varchar(24)"+
      " ) partition by (h_c_w_id) colocate with (customer) redundancy 1 buckets  9 gatewaysender ( MYSENDER )" ;
    
    this.executeSql(ny, createCustTable + getSQLSuffixClause());
    this.executeSql(ln, createCustTable + getSQLSuffixClause());
    this.executeSql(ny, createHistoryTable + getSQLSuffixClause());
    this.executeSql(ln, createHistoryTable + getSQLSuffixClause());
    
    String alterTable = "alter table customer add constraint pk_customer  primary key (c_w_id, c_d_id, c_id)";
    
    this.executeSql(ny, alterTable);
    this.executeSql(ln, alterTable);
    
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort + "]");
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, "oktk");
    Connection con = TestUtil.getConnection(props);
    
    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Statement stmt = con.createStatement();

    stmt.execute("INSERT INTO history (h_c_id, h_c_d_id, h_c_w_id, h_d_id, h_w_id) VALUES (3,2,9,2,9)");
    stmt.execute("INSERT INTO customer (c_id, c_d_id, c_w_id) VALUES (3,2,9)");
    con.commit();
//    stmt.execute("INSERT INTO customer (c_id, c_d_id, c_w_id) VALUES (166,3,2)");
//    stmt.execute("INSERT INTO customer (c_id, c_d_id, c_w_id) VALUES (166,4,2)");
//    stmt.execute("INSERT INTO history (h_c_id) VALUES (7)");
//    stmt.execute("INSERT INTO history (h_c_id) VALUES (88)");
//    con.commit();
//    stmt.execute("INSERT INTO customer (c_id, c_d_id, c_w_id) VALUES (155,3,1)");
//    stmt.execute("INSERT INTO customer (c_id, c_d_id, c_w_id) VALUES (154,4,1)");
//    con.commit();
    //stmt.executeBatch();
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
  }

  public void DEBUGtestMultiSiteOrderingIssue() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // LN Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // NY Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // TK Locator
    int tkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "3");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + nyPort + "]");
    startLocatorVM("localhost", tkPort, null, props);
    assertTrue(this.serverVMs.size() == 3);

    // HK Locator
    int hkPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "4");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + tkPort + "]");
    startLocatorVM("localhost", hkPort, null, props);
    assertTrue(this.serverVMs.size() == 4);

    // HK Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + hkPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);
    serverExecute(5, createGatewayReceiver(0, 0));

    // TK Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + tkPort
        + "]");
    AsyncVM async6 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async6);
    assertTrue(this.serverVMs.size() == 6);
    serverExecute(6, createGatewayReceiver(0, 0));

    // NY Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async7 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async7);
    assertTrue(this.serverVMs.size() == 7);
    serverExecute(7, createGatewayReceiver(0, 0));

    // LN Accessor Sender 1
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async8 = invokeStartServerVM(1, 0, "sg1,sg2", props);
    joinVMs(true, async8);
    assertTrue(this.serverVMs.size() == 8);
    serverExecute(8, createGatewaySender("mySenderA", "2", "sg2"));
    serverExecute(8, createGatewaySender("mySenderB", "3", "sg2"));
    serverExecute(8, createGatewaySender("mySenderC", "4", "sg2"));

    // LN Accessor Sender 2
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async9 = invokeStartServerVM(1, 0, "sg1,sg2", props);
    joinVMs(true, async9);
    assertTrue(this.serverVMs.size() == 9);

    // LN Datastore1
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async10 = invokeStartServerVM(1, 0, "sg1,sg2", props);
    joinVMs(true, async10);
    assertTrue(this.serverVMs.size() == 10);

    // LN Datastore2
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async11 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async11);
    assertTrue(this.serverVMs.size() == 11);

    final VM hk = serverVMs.get(4);
    final VM tk = serverVMs.get(5);
    final VM ny = serverVMs.get(6);
    final VM ln = serverVMs.get(7);

    this.executeSql(hk, "create schema EMP");
    this.executeSql(tk, "create schema EMP");
    this.executeSql(ny, "create schema EMP");
    this.executeSql(ln, "create schema EMP");

    String createParentTable = "create table EMP.PARENT (ID int not null, DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID)) partition by primary key redundancy 1 gatewaysender (MYSENDERA, MYSENDERB, MYSENDERC) server groups (sg1)";
    String createChildTable = "create table EMP.CHILD (ID int not null, DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), primary key (ID), foreign key (ID) references emp.parent (id)) partition by primary key redundancy 1 gatewaysender (MYSENDERA, MYSENDERB, MYSENDERC) server groups (sg1)";
    
    this.executeSql(hk, createParentTable);
    this.executeSql(tk, createParentTable);
    this.executeSql(ny, createParentTable);
    this.executeSql(ln, createParentTable);
    this.executeSql(hk, createChildTable);
    this.executeSql(tk, createChildTable);
    this.executeSql(ny, createChildTable);
    this.executeSql(ln, createChildTable);
    for(int i=0 ; i <25; i++){
      launchInsertThread(serverVMs.get(7), i);
    }
    for(int i=25 ; i <50; i++){
      launchInsertThread(serverVMs.get(8), i);
    }
    for(int i=50 ; i <75; i++){
      launchInsertThread(serverVMs.get(9), i);
    }
    for(int i=75 ; i <100; i++){
      launchInsertThread(serverVMs.get(10), i);
    }    

    pause(180000);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));


    this.sqlExecuteVerify(ny, "select ID from EMP.PARENT", goldenFile,
        "id1", true, false);
    this.sqlExecuteVerify(ny, "select ID from EMP.CHILD", goldenFile,
        "id1", true, false);
    this.sqlExecuteVerify(tk, "select ID from EMP.PARENT", goldenFile,
        "id1", true, false);
    this.sqlExecuteVerify(tk, "select ID from EMP.CHILD", goldenFile,
        "id1", true, false);
    this.sqlExecuteVerify(hk, "select ID from EMP.PARENT", goldenFile,
        "id1", true, false);
    this.sqlExecuteVerify(hk, "select ID from EMP.CHILD", goldenFile,
        "id1", true, false);


    this.executeSql(ln,
        "Update EMP.PARENT set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.executeSql(ln,
    "Update EMP.CHILD set DESCRIPTION = 'Second' where DESCRIPTION = 'First'");
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "id2", true, false);
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "id2", true, false);
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "id2", true, false);
    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "id2", true, false);

    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "id2", true, false);
    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "id2", true, false);

    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "id2", true, false);
    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "id2", true, false);

    this.executeSql(ln,
    "Delete from  EMP.CHILD where DESCRIPTION = 'Second'");
    
    this.executeSql(ln,
        "Delete from  EMP.PARENT where DESCRIPTION = 'Second'");

    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "empty", true, false);
    this.sqlExecuteVerify(ln, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "empty", true, false);

    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(8, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERA", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERB", 0));
    serverExecute(9, getExecutorToWaitForQueuesToDrain("MYSENDERC", 0));

    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "empty", true, false);
    this.sqlExecuteVerify(ny, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "empty", true, false);
    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "empty", true, false);
    this.sqlExecuteVerify(tk, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "empty", true, false);
    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.PARENT",
        goldenFile, "empty", true, false);
    this.sqlExecuteVerify(hk, "select DESCRIPTION from EMP.CHILD",
        goldenFile, "empty", true, false);

  }
  
  public void launchInsertThread(final VM ln ,final int i){
    Thread t = new Thread() {
      @Override
      public void run() {
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "insert into EMP.PARENT values ("+i+", 'First', 'J 604')",true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "insert into EMP.CHILD values ("+i+", 'First', 'J 604')",true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "insert into EMP.PARENT values ("+(100+i)+", 'First', 'J 604')",true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "insert into EMP.CHILD values ("+(100+i)+", 'First', 'J 604')",true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Update EMP.PARENT set DESCRIPTION = 'Second' where ID = "+i,true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Update EMP.CHILD set DESCRIPTION = 'Second' where ID = "+i,true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Delete from  EMP.CHILD where ID = "+i,true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Delete from  EMP.PARENT where ID = "+i,true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Update EMP.PARENT set DESCRIPTION = 'Second' where ID = "+(100+i),true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Update EMP.CHILD set DESCRIPTION = 'Second' where ID = "+(100+i),true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Delete from  EMP.CHILD where ID = "+(100+i),true,false });
        ln.invoke(TestUtil.class, "sqlExecute", new Object[] { "Delete from  EMP.PARENT where ID = "+(100+i),true,false });
      };
    };
    t.start();
  }
  
  public void testBug42962() throws Exception {
    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    props.put(com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS, "true");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");
    String createTable = "create table emp.TESTTABLE (id int not null, cust_name varchar(100),"
        + " since date, addr varchar(100), tid int, primary key (id) )redundancy 1  gatewaysender (MYSENDER) server groups (sg1) ";
    this.executeSql(reciever, createTable + getSQLSuffixClause());
    this.executeSql(sender, createTable + getSQLSuffixClause());

    this.executeSql(reciever, "create index emp.i1 on emp.testtable(tid)");
    // Test insert propagation by inserting in a data store node of DS.DS0
    // Set a query observer

    this.executeSql(sender, "insert into EMP.TESTTABLE values (1, 'name_1','"
        + new java.sql.Date(System.currentTimeMillis()) + "', 'J 604',1)");
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    for (int i = 2; i < 10; ++i) {
      this.executeSql(sender,
          "insert into EMP.TESTTABLE values (" + i + ", 'name_" + i + "','"
              + new java.sql.Date(System.currentTimeMillis()) + "', 'J 604',"
              + i + ")");
    }
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.executeSql(reciever, "create index emp.i2 on emp.testtable(cust_name)");

  }

  private volatile static Bug42930Tester bugtester;

  public void testConcurrentDML_DDL_Bug42930() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName() + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME,
        "localhost[" + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    props.put(com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS, "true");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    // DS1 Member
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);

    VM reciever = serverVMs.get(2);
    VM sender = serverVMs.get(3);
    //VM member = serverVMs.get(4);

    this.executeSql(reciever, "create schema EMP");
    this.executeSql(sender, "create schema EMP");
    String createTable = "create table emp.TESTTABLE (id int not null, cust_name varchar(100),"
        + " since date, addr varchar(100), tid int, primary key (id) )redundancy 1 "
        + " gatewaysender (MYSENDER) server groups (sg1) ";

    this.executeSql(reciever, createTable + getSQLSuffixClause());
    this.executeSql(sender, createTable + getSQLSuffixClause());

    this.executeSql(reciever, "create index emp.i1 on emp.testtable(tid)");
    // Test insert propagation by inserting in a data store node of DS.DS0
    // Set a query observer

    serverExecute(3, new SerializableRunnable("install observer") {
      @Override
      public void run() {
        bugtester = new Bug42930Tester("emp.i1", "tid");
        GemFireXDQueryObserverHolder.setInstance(bugtester);
      }
    });

    serverExecute(5, new SerializableRunnable("install observer") {
      @Override
      public void run() {
        bugtester = new Bug42930Tester("emp.i2", "tid");
        GemFireXDQueryObserverHolder.setInstance(bugtester);
      }
    });

    this.executeSql(sender, "insert into EMP.TESTTABLE values (1, 'name_1','"
        + new java.sql.Date(System.currentTimeMillis()) + "', 'J 604',1)");

    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // Validate call back invoked
    serverExecute(3, new SerializableRunnable("check callback invoked") {
      @Override
      public void run() {
        assertTrue(bugtester.cbInvoked);
        try {
          bugtester.indxCreator.join();
        }
        catch (InterruptedException e) {
          // ignore
        }
      }
    });
    serverExecute(5, new SerializableRunnable("check callback invoked") {
      @Override
      public void run() {
        assertTrue(bugtester.cbInvoked);
        try {
          bugtester.indxCreator.join();
        }
        catch (InterruptedException e) {
          // ignore
        }
      }
    });

    // This should have propagated to wan site 1
    this.sqlExecuteVerify(reciever, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);
  }

  public void testBasicDMLOperationPropagationToWanSitesOnReplicatedTableWithDDLReplay()
      throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
        + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createReceiver = createGatewayReceiver(0, 0);
    serverExecute(3, createReceiver);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM receiver = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(receiver, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    String createTable = "create table EMP.TESTTABLE (ID int , DESCRIPTION "
        + "varchar(1024) not null, ADDRESS varchar(1024)) "
        + "replicate gatewaysender (MYSENDER) server groups (sg1) ";
    this.executeSql(receiver, createTable + getSQLSuffixClause());
    this.executeSql(sender, createTable + getSQLSuffixClause());

    // Test insert propagation
    // this.executeSql(sender,
    // "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");

    serverExecute(4, batchInsert());
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    // This should have propagated to wan site 2
    this.sqlExecuteVerify(receiver, "select ID from EMP.TESTTABLE", goldenFile,
        "id1", true, false);

    // Test update propagation (bulk)
    // This should have propagated to wan site 2
    this.executeSql(sender,
        "Update EMP.TESTTABLE set DESCRIPTION = 'Second' where "
            + "DESCRIPTION = 'First'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "id2", true, false);

    // Test delete propagation
    this.executeSql(sender,
        "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
    this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);
    serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
    this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
        goldenFile, "empty", true, false);

    // start another receiver and bring down the first receiver afterwards
    // DS1 Receiver2
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async5 = invokeStartServerVM(1, 0, "sg1", props);
    joinVMs(true, async5);
    assertTrue(this.serverVMs.size() == 5);

    serverExecute(5, new SerializableRunnable() {
      @Override
      public void run() {
        GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
        GatewayReceiver receiver = cache.getGatewayReceiver("MYRCVR");
        assertNotNull(receiver);
        assertTrue(receiver.isRunning());
      }
    });
  }

  public void testBatchOperationPropagationToWanSites_45652()
      throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    // DS0 Locator
    Properties props = new Properties();
    int lnPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "1");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    startLocatorVM("localhost", lnPort, null, props);
    assertTrue(this.serverVMs.size() == 1);

    // DS1 Locator
    int nyPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "2");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
        + lnPort + "]");
    startLocatorVM("localhost", nyPort, null, props);
    assertTrue(this.serverVMs.size() == 2);

    // DS1 Receiver
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + nyPort
        + "]");
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1", props);
    joinVMs(true, async3);
    assertTrue(this.serverVMs.size() == 3);

    Runnable createRecevier = createGatewayReceiver(0, 0);
    serverExecute(3, createRecevier);

    // DS0 Sender
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + lnPort
        + "]");
    AsyncVM async4 = invokeStartServerVM(4, 0, "sg1", props);
    joinVMs(true, async4);
    assertTrue(this.serverVMs.size() == 4);

    serverExecute(4, createGatewaySender("mySender", "2", "sg1"));

    VM receiver = serverVMs.get(2);
    VM sender = serverVMs.get(3);

    this.executeSql(receiver, "create schema EMP");
    this.executeSql(sender, "create schema EMP");

    for (int i = 1; i <= 2; i++) {
      String genStr = (i == 1 ? "" : " generated always as identity");
      String createTable = "create table EMP.TESTTABLE (ID bigint" + genStr
          + ", DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
          + "primary key (ID)) gatewaysender (MYSENDER) server groups (sg1) ";
      this.executeSql(receiver, createTable + getSQLSuffixClause());
      this.executeSql(sender, createTable + getSQLSuffixClause());

      // Test batch insert propagation
      serverExecute(4, batchPrepInsert(i == 1));
      serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
      // This should have propagated to wan site 2
      this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
          goldenFile, "batch_id1", true, false);

      // Also test batch non-prepared insert propagation
      serverExecute(4, batchNonPrepInsert(i == 1));
      serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
      // This should have propagated to wan site 2
      this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
          goldenFile, "batch_id2", true, false);

      // Test update propagation (bulk)
      // This should have propagated to wan site 2
      this.executeSql(sender,
          "Update EMP.TESTTABLE set DESCRIPTION = 'Second' "
              + "where DESCRIPTION = 'First'");
      this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE",
          goldenFile, "batch_id3", true, false);
      serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
      this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
          goldenFile, "batch_id3", true, false);

      // Test delete propagation
      this.executeSql(sender,
          "Delete from  EMP.TESTTABLE where DESCRIPTION = 'Second'");
      this.sqlExecuteVerify(sender, "select DESCRIPTION from EMP.TESTTABLE", goldenFile,
          "batch_id4", true, false);
      serverExecute(4, getExecutorToWaitForQueuesToDrain("MYSENDER", 0));
      this.sqlExecuteVerify(receiver, "select DESCRIPTION from EMP.TESTTABLE",
          goldenFile, "batch_id4", true, false);

      this.executeSql(sender, "drop table emp.testtable");
      this.executeSql(receiver, "drop table emp.testtable");
    }
  }

  private static class Bug42930Tester extends GemFireXDQueryObserverAdapter {
    final String indexName;

    volatile boolean cbInvoked = false;

    final String field;

    private volatile Thread indxCreator;

    private volatile int onEventCount = 0;

    private final Runnable runnable;

    Bug42930Tester(String name, String fieldName) {
      this.indexName = name;
      this.field = fieldName;
      runnable = new Runnable() {
        public void run() {
          try {
            Connection conn = TestUtil.getConnection();
            Statement stmt = conn.createStatement();
            if (onEventCount % 2 == 0) {
              stmt.execute("create index " + indexName + " on emp.testtable ("
                  + field + ")");
            }
            else {
              stmt.execute(" drop index " + indexName);
            }
          }
          catch (Exception e) {
            throw new GemFireXDRuntimeException(e);
          }

        }
      };
    }

    @Override
    public void beforeIndexUpdatesAtRegionLevel(LocalRegion owner,
        EntryEventImpl event, RegionEntry entry) {
      cbInvoked = true;
      // start thread to create index
      indxCreator = new Thread(runnable);
      indxCreator.start();
      try {
        indxCreator.join(10000);
      }
      catch (InterruptedException e) {
        throw new GemFireXDRuntimeException(e);
      }
      ++onEventCount;
    }

  }

  public void sqlExecuteVerify(VM vm, String sql, String goldenTextFile,
      String resultSetID, boolean usePrepStmt, boolean checkTypeInfo)
      throws Exception {
    execute(vm, sql, true, goldenTextFile, resultSetID, null, null, null,
        usePrepStmt, checkTypeInfo, false);
  }

  public void executeSql(VM vm, String sql) throws Exception {
    
    executeSql(vm, sql, false);
  }

  public void executeSql(VM vm, String sql, boolean doVerify) throws Exception {
    if(this.isDefaultOffHeapConfigured()) {     
      vm.invoke(setOffHeapProperty() );
    }
    execute(vm, sql, doVerify, null, null, null, null, null, true, false, false);
  }
  
  private static Runnable setOffHeapProperty() {
    SerializableRunnable csr = new SerializableRunnable("offheap propery setter") {

      @Override
      public void run() throws CacheException {
        System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
        System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
        
      }
      
    };
    return csr;
  }
  public static Runnable setObserver(final List<?> rcvrKeys) {
    SerializableRunnable receiverConf = new SerializableRunnable(
        "Configurator") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserver sqo = new GemFireXDQueryObserverAdapter() {
          volatile private int k = 0;

          @Override
          public long overrideUniqueID(long actualUniqueID, boolean forRegionKey) {
            // only override during execution to ensure uniqueness of unique IDs
            // else generated one can clash with overridden one from other DS
            if (forRegionKey && k < rcvrKeys.size()) {
              long newID = ((Long)rcvrKeys.get(k++)).longValue();
              getGlobalLogger().info(
                  "For actual uniqueID=" + actualUniqueID
                      + " overriding with ID=" + newID + ", k=" + k);
              return newID;
            }
            else {
              return actualUniqueID;
            }
          }
        };
        GemFireXDQueryObserverHolder.setInstance(sqo);
      }
    };
    return receiverConf;
  }

  public static Runnable unsetObserver() {
    SerializableRunnable receiverConf = new SerializableRunnable(
        "Configurator") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter());
      }
    };
    return receiverConf;
  }

  public static Runnable createGatewayReceiver(final int startPort,
      final int endPort) {
    SerializableRunnable receiverConf = new SerializableRunnable(
        "Receiver Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();
          str.append("create gatewayreceiver myrcvr (bindaddress 'localhost'");
          if (startPort != 0) {
            str.append(" startport " + startPort);
          }
          else {
            str.append(" startport " + GatewayReceiver.DEFAULT_START_PORT);
          }
          if (endPort != 0) {
            str.append(" endport " + endPort);
          }
          else {
            str.append(" endport " + GatewayReceiver.DEFAULT_END_PORT);
          }
          str.append(") server groups (sg1)");
          st.execute(str.toString());
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return receiverConf;
  }
  
  public static Runnable createConfiguration1() {
    SerializableRunnable receiverConf = new SerializableRunnable(
        "Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();
          str.append("CREATE DISKSTORE STORE1");
          st.execute(str.toString());
          
          st = conn.createStatement();
          str = new StringBuilder();
          str.append("CREATE GATEWAYSENDER SENDER1 ( REMOTEDSID 2  ENABLEPERSISTENCE true  DISKSTORENAME STORE1 manualstart true) SERVER GROUPS (DEMOGOUP)");
          st.execute(str.toString());
          st = conn.createStatement();
          str = new StringBuilder();
          str.append("CREATE TABLE CALL_LOG ( CALL_ID VARCHAR(50) NOT NULL, CALL_CENTER VARCHAR(10) NOT NULL,"+
              "CALLER_NAME VARCHAR(50) NOT NULL,  CALL_DETAIL VARCHAR(500) NOT NULL, CALLED_AT TIMESTAMP NOT NULL,"+
              "CALL_STATUS VARCHAR(10) NOT NULL, PRIMARY KEY (CALL_ID) ) replicate SERVER GROUPS (DEMOGOUP) GATEWAYSENDER (SENDER1)");
          st.execute(str.toString());
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return receiverConf;
  }
  
  public static Runnable startSender() {
    SerializableRunnable receiverConf = new SerializableRunnable(
        "Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();
          str.append("CALL SYS.START_GATEWAYSENDER ('SENDER1')");
          st.execute(str.toString());
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return receiverConf;
  }

  public static Runnable createConfiguration2() {
    SerializableRunnable receiverConf = new SerializableRunnable(
        "Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();

          st = conn.createStatement();
          str = new StringBuilder();
          str.append("CREATE GATEWAYRECEIVER RECEIVER2 (bindaddress 'localhost' " +
              "STARTPORT 7785  ENDPORT 7789) SERVER GROUPS (DEMOGOUP)");
          st.execute(str.toString());

          st = conn.createStatement();
          str = new StringBuilder();
          str.append("CREATE TABLE CALL_LOG ( CALL_ID VARCHAR(50) NOT NULL, CALL_CENTER VARCHAR(10) NOT NULL,"+
              "CALLER_NAME VARCHAR(50) NOT NULL,  CALL_DETAIL VARCHAR(500) NOT NULL, CALLED_AT TIMESTAMP NOT NULL,"+
              "CALL_STATUS VARCHAR(10) NOT NULL, PRIMARY KEY (CALL_ID) ) replicate SERVER GROUPS (DEMOGOUP)");
          st.execute(str.toString());
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return receiverConf;
  }
  
  public static Runnable addListener() {
    SerializableRunnable listConf = new SerializableRunnable(
        "LISTENER Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          
          CallableStatement cs = conn.prepareCall("CALL SYS.ADD_LISTENER(?,?,?,?,?,?)");
          cs.setString(1, "USERTABLELISTENER");
          cs.setString(2, "EMP");
          cs.setString(3, "TESTTABLE");
          cs.setString(4, "com.pivotal.gemfirexd.wan.RouterListener");
          cs.setString(5, "");
          cs.setString(6, "");
          cs.execute();  
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return listConf;
  }

  public static Runnable createGatewaySender(final String id,
      final String remoteDs, final String serverGroups) {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        String expectedEx = id.toUpperCase() + ": Could not connect";
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();
          // receiver might not be created yet
          TestUtil.addExpectedException(expectedEx);
          str.append("CREATE gatewaysender " + id + " (remotedsid " + remoteDs
              + " manualstart false) server groups (" + serverGroups + ") ");
          st.execute(str.toString());
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        } finally {
          TestUtil.removeExpectedException(expectedEx);
        }
      }

    };
    return senderConf;
  }

  public static Runnable createGatewaySenderDefault() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        String defaultID = "sender_proxy_1";
        String expectedEx = defaultID.toUpperCase() + ": Could not connect";
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();
          // receiver might not be created yet
          TestUtil.addExpectedException(expectedEx);
          str.append("CREATE GATEWAYSENDER " + defaultID
              + " (REMOTEDSID 2 MANUALSTART false  ENABLEBATCHCONFLATION true"
              + " BATCHSIZE 4  BATCHTIMEINTERVAL 60000 ENABLEPERSISTENCE false"
              + "  MAXQUEUEMEMORY 100 ) SERVER GROUPS ( proxy_1 )");
          st.execute(str.toString());
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        } finally {
          TestUtil.removeExpectedException(expectedEx);
        }
      }

    };
    return senderConf;
  }

  public static Runnable batchInsert() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.addBatch("insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
          st.executeBatch();
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return senderConf;
  }
  
  public static Runnable inserts() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.execute("insert into EMP.TESTTABLE (DESCRIPTION, ADDRESS) values ('First')");
          st.execute("insert into EMP.TESTTABLE (DESCRIPTION, ADDRESS) values ('Second')");
          st.execute("insert into EMP.TESTTABLE (DESCRIPTION, ADDRESS) values ('Third')");
          st.execute("insert into EMP.TESTTABLE (DESCRIPTION, ADDRESS) values ('Fourth')");
          //conn.commit();
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return senderConf;
  }
  
  public static Runnable updates() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          
          st.execute("update EMP.TESTTABLE set DESCRIPTION ='Fifth' where ID=5");
          st.execute("update EMP.TESTTABLE set DESCRIPTION ='Sixth' where ID=6");
          st.execute("update EMP.TESTTABLE set DESCRIPTION ='Seventh' where ID=7");
          st.execute("update EMP.TESTTABLE set DESCRIPTION ='Eighth' where ID=8");
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return senderConf;
  }

  public static Runnable batchPrepInsert(final boolean insertId) {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          PreparedStatement pstmt;
          if (insertId) {
            pstmt = conn.prepareStatement("insert into "
                + "EMP.TESTTABLE (DESCRIPTION, ID) values (?, ?)");
            pstmt.setString(1, "First");
            pstmt.setInt(2, 1);
            pstmt.addBatch();
            pstmt.setString(1, "Second");
            pstmt.setInt(2, 2);
            pstmt.addBatch();
            pstmt.setString(1, "Third");
            pstmt.setInt(2, 3);
            pstmt.addBatch();
          }
          else {
            pstmt = conn.prepareStatement("insert into "
                + "EMP.TESTTABLE (DESCRIPTION) values (?)");
            pstmt.setString(1, "First");
            pstmt.addBatch();
            pstmt.setString(1, "Second");
            pstmt.addBatch();
            pstmt.setString(1, "Third");
            pstmt.addBatch();
          }
          pstmt.executeBatch();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }

  public static Runnable batchNonPrepInsert(final boolean insertId) {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement stmt = conn.createStatement();
          if (insertId) {
            stmt.addBatch("insert into EMP.TESTTABLE "
                + "(DESCRIPTION, ID) values ('Fourth', 4)");
            stmt.addBatch("insert into EMP.TESTTABLE "
                + "(DESCRIPTION, ID, ADDRESS) values ('Fifth', 5, 'J 605')");
            stmt.addBatch("insert into EMP.TESTTABLE "
                + "(ID, DESCRIPTION, ADDRESS) values (6, 'Sixth', 'J 606')");
          }
          else {
            stmt.addBatch("insert into EMP.TESTTABLE "
                + "(DESCRIPTION) values ('Fourth')");
            stmt.addBatch("insert into EMP.TESTTABLE "
                + "(DESCRIPTION, ADDRESS) values ('Fifth', 'J 605')");
            stmt.addBatch("insert into EMP.TESTTABLE "
                + "(DESCRIPTION, ADDRESS) values ('Sixth', 'J 606')");
          }
          stmt.executeBatch();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }

  public static Runnable doInserts() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.execute("insert into CALL_LOG values ('A','pune','yogs','status',timestamp('1992-01-01 12:30:30'), 'incomplete')"); 
//          st.execute("insert into CALL_LOG values ('2','pune','yogs','status',timestamp('1992-01-01 12:30:30'), 'incomplete')");
//          st.execute("insert into CALL_LOG values ('3','pune','yogs','status',timestamp('1992-01-01 12:30:30'), 'incomplete')");
//          st.execute("insert into CALL_LOG values ('4','pune','yogs','status',timestamp('1992-01-01 12:30:30'), 'incomplete')");
          pause(5000);
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable doUpdates() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.execute("update CALL_LOG set call_status ='complete' where call_id='A'");
          pause(2000);
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return senderConf;
  }

  private static SerializableRunnable verifyRegionSize(
      final String regionName, final int regionSize) {
    SerializableRunnable verifyRegionSize = new SerializableRunnable(
        "waitForQueueToDrain") {
      @Override
      public void run() throws CacheException {
        Region r = Misc.getGemFireCache().getRegion(regionName);
        Misc.getGemFireCache().getLogger().fine("YOGS Region " + r.entrySet());
      }
    };
    return verifyRegionSize;
  }
  private static SerializableRunnable getExecutorToWaitForQueuesToDrain(
      final String senderId, final int regionSize) {
    SerializableRunnable waitForQueueToDrain = new SerializableRunnable(
        "waitForQueueToDrain") {
      @Override
      public void run() throws CacheException {

        Set<GatewaySender> senders = Misc.getGemFireCache()
            .getAllGatewaySenders();
        Misc.getGemFireCache()
            .getLogger()
            .fine(
                "Inside waitForQueueToDrain for sender " + senderId
                    + " all senders in cache are " + senders);
        SerialGatewaySenderImpl sender = null;
        for (GatewaySender s : senders) {
          if (s.getId().equals(senderId)) {
            sender = (SerialGatewaySenderImpl)s;
            break;
          }
        }
        AbstractGatewaySenderEventProcessor processor = sender
            .getEventProcessor();
        if (processor == null) return;
        final Region<?, ?> region = processor.getQueue().getRegion();
        WaitCriterion wc = new WaitCriterion() {
          public boolean done() {
            if (region.keySet().size() == regionSize) {
              Misc.getGemFireCache()
                  .getLogger()
                  .fine(
                      "Inside waitForQueueToDrain for sender " + senderId
                          + " queue is drained and empty ");
              return true;
            }
            Misc.getGemFireCache()
                .getLogger()
                .fine(
                    "Inside waitForQueueToDrain for sender " + senderId
                        + " queue is not yet drained " + region.keySet().size());
            return false;
          }

          public String description() {
            return "Expected queue entries: " + regionSize
                + " but actual entries: " + region.keySet().size();
          }
        };
        DistributedTestBase.waitForCriterion(wc, 60000, 500, true);
      }
    };
    return waitForQueueToDrain;
  }
  
  public static void batchInsert_NoPrimaryKey() {
    try {
      Connection conn = TestUtil.jdbcConn;
      Statement st = conn.createStatement();
      st.addBatch("insert into EMP.REPLICATED_TABLE values (1, 'First', 'A 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (2, 'First', 'B 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (3, 'First', 'C 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (4, 'First', 'D 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (5, 'First', 'E 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (6, 'First', 'F 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (7, 'First', 'G 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (8, 'First', 'H 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (9, 'First', 'I 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (10, 'First', 'K 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (1, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (2, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (3, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (4, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (5, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (6, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (7, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (8, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (9, 'Second', 'J 604')");
      st.addBatch("insert into EMP.REPLICATED_TABLE values (10, 'Second', 'J 604')");
      st.executeBatch();
    }
    catch (SQLException sqle) {
      throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
    }
  }
}
