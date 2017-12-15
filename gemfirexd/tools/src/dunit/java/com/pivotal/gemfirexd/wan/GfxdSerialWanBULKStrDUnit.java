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

import com.gemstone.gemfire.internal.cache.partitioned.PRLocallyDestroyedException;
import com.pivotal.gemfirexd.TestUtil;

public class GfxdSerialWanBULKStrDUnit extends GfxdWanCommonTestBase {
  private static final long serialVersionUID = 1L;

  public GfxdSerialWanBULKStrDUnit(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  // create serial gateway sender with bulk dml optimizations
  @Override
  protected String getCreateGatewayDML() {
    return  "CREATE gatewaysender MySender (remotedsid 2 ) server groups (sgSender)";
  }
  
  public void testDAP_ReplicatedRegion() throws Exception {

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
    final String createPTable = "create table EMP.REPLICATED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, COMPANY varchar(1024) DEFAULT 'VMWARE',"
        + "primary key (ID)) REPLICATE server groups (sg1)";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());

    serverExecute(4, wanInsertDAP("EMP.REPLICATED_TABLE"));
    sqlExecuteVerify(SITE_A, "select DESCRIPTION from EMP.REPLICATED_TABLE",
        goldenFile, "batch_id2", true, false);
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select DESCRIPTION from EMP.REPLICATED_TABLE",
        goldenFile, "batch_id2", true, false);

    final String wanUpdateDAP = "CREATE PROCEDURE wan_update_dap(IN inParam1 VARCHAR(50)) LANGUAGE JAVA PARAMETER STYLE JAVA MODIFIES SQL DATA EXTERNAL NAME '"
        + GfxdWanCommonTestBase.class.getName() + ".wanUpdateDAP'";

    serverExecute(4, callDAP(wanUpdateDAP, "EMP.REPLICATED_TABLE"));

    sqlExecuteVerify(SITE_A, "select COMPANY from EMP.REPLICATED_TABLE ORDER BY ID",
        goldenFile, "batch_id8", true, false);
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");
    sqlExecuteVerify(SITE_B, "select COMPANY from EMP.REPLICATED_TABLE ORDER BY ID",
        goldenFile, "batch_id8", true, false);
  }
}
