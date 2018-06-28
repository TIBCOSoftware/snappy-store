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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.partitioned.PRLocallyDestroyedException;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySenderEventProcessor;
import com.gemstone.gemfire.internal.cache.wan.parallel.ParallelGatewaySenderImpl;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.procedure.ProcedureExecutionContext;
import io.snappydata.test.dunit.SerializableRunnable;


public abstract class GfxdWanCommonTestBase extends GfxdWanTestBase {
  public GfxdWanCommonTestBase(String name) {
    super(name);
  }
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
  }
  @Override
  protected void vmTearDown() throws Exception {
    super.vmTearDown();
  }
  
  /**
   * Basic Insert doesn't user BULK DML route.
   * It gets notification just as in case of gemfire region.
   * @throws Exception
   */

  public void testBasicInsert() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doInsertOperations
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
  }
  
  protected abstract String getCreateGatewayDML();

  public void testBasicPrepStmt() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024) DEFAULT 'Pivotal', "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doPrepStmtInsertOperations
    // server 4 belongs to site A
    serverExecute(4, prepInsert());
    // doInsertOperations
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
  }
  
  /**
   * Basic Insert/Update/Delete doesn't user BULK DML route.
   * It gets notification just as in case of gemfire region.
   * @throws Exception
   */
  public void testBasicUpdateDelete() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doInsertOperations
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    
    //doUpdateOperations
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ID = 1");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    
    //doDeleteOperations
    executeSql(SITE_A,
        "Delete from  EMP.PARTITIONED_TABLE where ID = 1");
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
  }
  
  public void testNonPKBasedUpdateDelete() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doInsertOperations
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    
    //doUpdateOperations
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A714'");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    
    //doDeleteOperations
    executeSql(SITE_A,
        "Delete from  EMP.PARTITIONED_TABLE where DESCRIPTION = 'Second'");
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
  }
  
  public void testBatchInsert() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { CacheClosedException.class });
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024) DEFAULT 'Pivotal',"
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doBatchInsertOperations
    // server 4 belongs to site A
    serverExecute(4, batchInsert());
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
  }
  
  public void testBatchUpdate() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024) DEFAULT 'Pivotal', "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doBatchInsertOperations
    // server 4 belongs to site A
    serverExecute(4, batchInsert());
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    
    // doBatchUpdateOperations
    // server 4 belongs to site A
    serverExecute(4, batchUpdate());
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);
  }
  
  public void testBatchDelete() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { ForceReattemptException.class });
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024) DEFAULT 'Pivotal', "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doBatchInsertOperations
    // server 4 belongs to site A
    serverExecute(4, batchInsert());
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    
    // doBatchUpdateOperations
    // server 4 belongs to site A
    serverExecute(4, batchUpdate());
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);
    
    // doBatchDeleteOperations
    serverExecute(4, batchDelete());
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "empty", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "empty", true, false);
  }
  
  public void testBULKUpdate() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { ForceReattemptException.class });
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024), "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doBatchInsertOperations
    // server 4 belongs to site A
    serverExecute(4, batchInsertForBULK());
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A714'");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    
    // two rows are with same desc: SECOND
    // do bulk update
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set COMPANY = 'Pivotal' where DESCRIPTION = 'Second'");
    // verify within site : ADDRESS=A715
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);
    //wait for queue to be empty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify in remote site : ADDRESS=A715
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);
  }
  
  public void testBULKDelete() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024), "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doBatchInsertOperations
    // server 4 belongs to site A
    serverExecute(4, batchInsertForBULK());
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    // update record to make desc same
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A714'");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    
    // two rows are with same desc: SECOND
    // do bulk update
    executeSql(SITE_A,
        "Delete from EMP.PARTITIONED_TABLE where DESCRIPTION = 'Second'");
    // verify within site : ADDRESS=A715
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "empty", true, false);
    //wait for queue to be empty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify in remote site : ADDRESS=A715
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "empty", true, false);
  }
  
  public void testTxOperations() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024), "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doTxInsertOperations
    // server 4 belongs to site A
    serverExecute(4, txInsert());
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);

    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id2", true, false);
    
  }
  
  public void testTxInsertUpdateDeleteOperations() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024), "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doTxInsertOperations
    // server 4 belongs to site A
    serverExecute(4, txInsert());
    serverExecute(4, txUpdate());
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);

    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id10", true, false);
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "id10", true, false);
    
    serverExecute(4, txDelete());
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    sqlExecuteVerify(SITE_A,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "empty", true, false);

    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    sqlExecuteVerify(SITE_B,
        "select COMPANY from EMP.PARTITIONED_TABLE WHERE ID = 2", goldenFile,
        "empty", true, false);
  }
  
  private Runnable txUpdate() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          conn.setAutoCommit(false);
          //conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
          Statement st = conn.createStatement();
          //st.execute("insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'Pivotal')");
          //st.execute("insert into EMP.PARTITIONED_TABLE values (2, 'Second', 'J 605', 'Zimbra')");
          st.execute("Update EMP.PARTITIONED_TABLE set COMPANY = 'Pivotal' where DESCRIPTION = 'First'");
          st.execute("Update EMP.PARTITIONED_TABLE set COMPANY = 'Pivotal' where DESCRIPTION = 'Second'");
          conn.commit();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  private Runnable txDelete() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          conn.setAutoCommit(false);
          //conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
          Statement st = conn.createStatement();
          //st.execute("insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'Pivotal')");
          //st.execute("insert into EMP.PARTITIONED_TABLE values (2, 'Second', 'J 605', 'Zimbra')");
          st.execute("Delete from EMP.PARTITIONED_TABLE where ID = 1");
          st.execute("Delete from EMP.PARTITIONED_TABLE where ID = 2");
          conn.commit();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }

  public void testBasicInsertOnNonPKBasedTable() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable (NON PK)
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null ) "
        + " partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doInsertOperations
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
  }
  
  public void testUpdateOnNonPKBasedTable() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable (NON PK)
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null ) "
        + " partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doInsertOperations
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id7", true, false);
    
  //doUpdateOperations
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ID = 1");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "id2", true, false);
    
    //doDeleteOperations
    executeSql(SITE_A,
        "Delete from  EMP.PARTITIONED_TABLE where ID = 1");
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ID = 1", goldenFile,
        "empty", true, false);
  }

  
  public void testIdentityColumnGeneratedAlways() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { PRLocallyDestroyedException.class, 
        ForceReattemptException.class
        });
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID bigint GENERATED ALWAYS AS IDENTITY, "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doInsertOperations
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE (DESCRIPTION, ADDRESS)values ('First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE DESCRIPTION = 'First'", goldenFile,
        "id8", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify that ID is same on both site.
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE DESCRIPTION = 'First'", goldenFile,
        "id8", true, false);
    
    //doUpdateOperations
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A714'");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "id2", true, false);
    
    //doDeleteOperations
    executeSql(SITE_A,
        "Delete from  EMP.PARTITIONED_TABLE where ADDRESS = 'A714'");
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "empty", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "empty", true, false);
  }
  
  public void testIdentityColumnGeneratedDefault() throws Exception {

    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    // start two sites
    // each having 2 data-store nodes, 1 accessor and 1 locator
    // 1 locator
    // 1 datastore + sender
    // 1 datastore + sender + receiver
    // 1 accessor + sender + receiver
    startSites(2);
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { PRLocallyDestroyedException.class });
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID bigint GENERATED BY DEFAULT AS IDENTITY (START WITH 5,INCREMENT BY 5), "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    // doInsertOperations
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE (DESCRIPTION, ADDRESS)values ('First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE DESCRIPTION = 'First'", goldenFile,
        "id8", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify that ID is same on both site.
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE DESCRIPTION = 'First'", goldenFile,
        "id8", true, false);
    
    //doUpdateOperations
    executeSql(SITE_A,
        "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A714'");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "id2", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "id2", true, false);
    
    //doDeleteOperations
    executeSql(SITE_A,
        "Delete from  EMP.PARTITIONED_TABLE where ADDRESS = 'A714'");
    sqlExecuteVerify(SITE_A,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "empty", true, false);
    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    // verify
    sqlExecuteVerify(SITE_B,
        "select ADDRESS from EMP.PARTITIONED_TABLE WHERE ADDRESS = 'A714'", goldenFile,
        "empty", true, false);
  }
  
  public void testDAP_PartitionedRegion() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    startSites(2);
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { PRLocallyDestroyedException.class });
    // create gatewayreceiver
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    // create gatewaysender
    final String createGWS = getCreateGatewayDML();
    executeSql(SITE_A, createGWS);
    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024) DEFAULT 'Pivotal',"
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)");
    executeSql(SITE_B, createPTable);

    serverExecute(4, wanInsertDAP("EMP.PARTITIONED_TABLE"));
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.PARTITIONED_TABLE",
        goldenFile, "batch_id2", true, false);

    final String wanUpdateDAP = "CREATE PROCEDURE wan_update_dap(IN inParam1 VARCHAR(50)) LANGUAGE JAVA PARAMETER STYLE JAVA MODIFIES SQL DATA EXTERNAL NAME '"
        + GfxdWanCommonTestBase.class.getName() + ".wanUpdateDAP'";

    serverExecute(4, callDAP(wanUpdateDAP, "EMP.PARTITIONED_TABLE"));

    sqlExecuteVerify(SITE_A, "select COMPANY from EMP.PARTITIONED_TABLE ORDER BY ID",
        goldenFile, "batch_id8", true, false);
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select COMPANY from EMP.PARTITIONED_TABLE ORDER BY ID",
        goldenFile, "batch_id8", true, false);
  }
  
  public static Runnable wanInsertDAP(final String table) throws SQLException {
    SerializableRunnable wanInsert = new SerializableRunnable(
        "DAP Caller") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          boolean orig = conn.getAutoCommit();
          conn.setAutoCommit(false);
          st.addBatch("insert into " + table + " values (1, 'First', 'A', 'VMWARE')");
          st.addBatch("insert into " + table + " values (2, 'Second', 'B', 'VMWARE')");
          st.addBatch("insert into " + table + " values (3, 'Third', 'C', 'VMWARE')");
          st.addBatch("insert into " + table + " values (4, 'Fourth', 'D', 'VMWARE')");
          st.addBatch("insert into " + table + " values (5, 'Fifth', 'E', 'VMWARE')");
          st.addBatch("insert into " + table + " values (6, 'Sixth', 'F', 'VMWARE')");
          st.executeBatch();
          conn.commit();
          conn.setAutoCommit(orig);
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return wanInsert;
  }

  public static void wanUpdateDAP(String inParam1,
      ProcedureExecutionContext context) throws SQLException {
    Connection conn = context.getConnection();
    Statement st = conn.createStatement();
    st.execute("<local> Update "+inParam1+ " set COMPANY = 'PIVOTAL' where ID = 2 OR ID = 4 OR ID = 6");
  }
  
  public static Runnable callDAP(final String procedure, final String table){
    SerializableRunnable senderConf = new SerializableRunnable(
        "DAP Caller") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.execute(procedure);
          CallableStatement cs = null;
          cs = conn.prepareCall("CALL wan_update_dap(?) ON SERVER GROUPS (sg2)");
          cs.setString(1, table);
          cs.execute();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
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
          st.addBatch("insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'Pivotal')");
          st.addBatch("insert into EMP.PARTITIONED_TABLE values (2, 'Second', 'J 605', 'Pivotal')");
          st.executeBatch();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable batchUpdate() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.addBatch("Update EMP.PARTITIONED_TABLE set COMPANY = 'Pivotal' where DESCRIPTION = 'First'");
          st.addBatch("Update EMP.PARTITIONED_TABLE set COMPANY = 'Pivotal' where DESCRIPTION = 'Second'");
          st.executeBatch();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable batchDelete() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.addBatch("Delete from EMP.PARTITIONED_TABLE where ID = 1");
          st.addBatch("Delete from EMP.PARTITIONED_TABLE where ID = 2");
          st.executeBatch();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable batchInsertForBULK() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          st.addBatch("insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'Pivotal')");
          st.addBatch("insert into EMP.PARTITIONED_TABLE values (2, 'Second', 'J 605', 'Zimbra')");
          st.executeBatch();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable txInsert() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        boolean origAutocommit = false;
        Connection conn = null;
        try {
          conn = TestUtil.jdbcConn;
          origAutocommit = conn.getAutoCommit();
          conn.setAutoCommit(false);
          //conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
          Statement st = conn.createStatement();
          st.execute("insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'Pivotal')");
          st.execute("insert into EMP.PARTITIONED_TABLE values (2, 'Second', 'J 605', 'Zimbra')");
          conn.commit();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        } finally {
          if (conn != null) {
            try {
              conn.setAutoCommit(origAutocommit);  
            } catch (SQLException e) {
              // Ignore
            }
            
          }
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable prepInsert() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          conn.setAutoCommit(false);
          conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          PreparedStatement prep = conn.prepareStatement("insert into "
              + "EMP.PARTITIONED_TABLE (ID, DESCRIPTION, ADDRESS, COMPANY) values (?, ?, ?, ?)");
          prep.setInt(1,1);
          prep.setString(2, "First");
          prep.setString(3, "A714");
          prep.setString(4, "Pivotal");
          prep.addBatch();
          prep.setInt(1,2);
          prep.setString(2, "Second");
          prep.setString(3, "J 605");
          prep.setString(4, "Zimbra");
          prep.addBatch();
          prep.executeBatch();
          conn.commit();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
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
        ParallelGatewaySenderImpl sender = null;
        for (GatewaySender s : senders) {
          if (s.getId().equals(senderId)) {
            sender = (ParallelGatewaySenderImpl)s;
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
        waitForCriterion(wc, 60000, 500, true);
      }
    };
    return waitForQueueToDrain;
  }
}
