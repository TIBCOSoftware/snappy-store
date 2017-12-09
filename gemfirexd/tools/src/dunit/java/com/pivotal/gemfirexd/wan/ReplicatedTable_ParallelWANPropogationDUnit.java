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

import com.gemstone.gemfire.internal.cache.ForceReattemptException;
import com.gemstone.gemfire.internal.cache.partitioned.PRLocallyDestroyedException;
import com.pivotal.gemfirexd.TestUtil;

public class ReplicatedTable_ParallelWANPropogationDUnit extends GfxdWanTestBase {

  public ReplicatedTable_ParallelWANPropogationDUnit(String name) {
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
 
  /*
   * Test to validate ParallelGatewaySender propogates inserts in replicated table to remote site
   */
  
  public void testRRParallelWAN_2Sites_PKBASED_UPDATE_DELETE() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
    + "/lib/GemFireXDGatewayDUnit.xml";
    startSites(2);
    
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { ForceReattemptException.class });

    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    
    executeSql(SITE_A,"CREATE gatewaysender mySender (remotedsid 2 isparallel true ) server groups (sg1)");
    
    final String createPTable = "create table EMP.REPLICATED_TABLE(ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) replicate server groups (sg1)";

    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    executeSql(SITE_A,
        "insert into EMP.REPLICATED_TABLE values (1, 'First', 'A703')");
    sqlExecuteVerify(SITE_A, "select ID from EMP.REPLICATED_TABLE", goldenFile,
        "id1", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select ID from EMP.REPLICATED_TABLE", goldenFile,
        "id1", true, false);
    
    executeSql(SITE_A,
    "Update EMP.REPLICATED_TABLE set DESCRIPTION = 'Second' where ID = 1");
    sqlExecuteVerify(SITE_A, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(SITE_A,
    "Delete from  EMP.REPLICATED_TABLE where ID = 1");
    sqlExecuteVerify(SITE_A, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "empty", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "empty", true, false);
  }
  
  public void testPRParallelWAN_2Sites_NONPKBASED_UPDATE() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
    + "/lib/GemFireXDGatewayDUnit.xml";

    startSites(2);

    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    
    executeSql(SITE_A,"CREATE gatewaysender mySender (remotedsid 2 isparallel true ) server groups (sg1)");
    
    final String createPTable = "create table EMP.PARTITIONED_TABLE(ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) partition by column(ID) redundancy 1  server groups (sg1)";

    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A703')");
    sqlExecuteVerify(SITE_A, "select ID from EMP.PARTITIONED_TABLE", goldenFile,
        "id1", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select ID from EMP.PARTITIONED_TABLE", goldenFile,
        "id1", true, false);
    
    executeSql(SITE_A,
    "Update EMP.PARTITIONED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A703'");
    sqlExecuteVerify(SITE_A, "select DESCRIPTION from EMP.PARTITIONED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.PARTITIONED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(SITE_A,
    "Delete from EMP.PARTITIONED_TABLE where DESCRIPTION = 'Second' ");
    sqlExecuteVerify(SITE_A, "select DESCRIPTION from EMP.PARTITIONED_TABLE", goldenFile,
        "empty", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.PARTITIONED_TABLE", goldenFile,
        "empty", true, false);
    
  }
  
  public void testRRParallelWAN_2Sites_NONPKBASED_UPDATE() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
    + "/lib/GemFireXDGatewayDUnit.xml";

    startSites(2);

    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { ForceReattemptException.class });
    
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);
    
    executeSql(SITE_A,"CREATE gatewaysender mySender (remotedsid 2 isparallel true ) server groups (sg1)");
    
    final String createPTable = "create table EMP.REPLICATED_TABLE(ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
        + "primary key (ID)) replicate server groups (sg1)";

    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());
    
    executeSql(SITE_A,
        "insert into EMP.REPLICATED_TABLE values (1, 'First', 'A703')");
    sqlExecuteVerify(SITE_A, "select ID from EMP.REPLICATED_TABLE", goldenFile,
        "id1", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select ID from EMP.REPLICATED_TABLE", goldenFile,
        "id1", true, false);
    
    executeSql(SITE_A,
    "Update EMP.REPLICATED_TABLE set DESCRIPTION = 'Second' where ADDRESS = 'A703'");
    sqlExecuteVerify(SITE_A, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "id2", true, false);
    
    executeSql(SITE_A,
    "Delete from EMP.REPLICATED_TABLE where DESCRIPTION = 'Second' ");
    sqlExecuteVerify(SITE_A, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "empty", true, false);
    
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.REPLICATED_TABLE", goldenFile,
        "empty", true, false);
    
  }
}
