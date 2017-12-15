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

package com.pivotal.gemfirexd.ddl;

import java.sql.Connection;
import java.util.Properties;

import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.jdbc.DdlUtilsTest;

/**
 * Unit tests for DdlUtils with GemFireXD.
 * 
 * @author swale
 */
@SuppressWarnings("serial")
public class DdlUtilsDUnit extends DistributedSQLTestBase {

  public DdlUtilsDUnit(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  public void testSchemaCreation() throws Throwable {
    final Properties props = new Properties();
    // generates too many logs at fine level
    props.setProperty("log-level", "config");
    // start a client and servers with network servers
    startVMs(1, 3, 0, null, props);
    final int netPort = startNetworkServer(1, null, null);
    final String clientUrl = "localhost:" + netPort;
    final Connection conn = TestUtil.getConnection();
    final Connection netConn = TestUtil.getNetConnection("localhost", netPort,
        null, null);

    DdlUtilsTest.runImportExportTest(conn, netConn, clientUrl,
        TestUtil.currentUserName, TestUtil.currentUserPassword, 100, 65000,
        getServerVM(1));
  }

  public void testFKAutoGenRewrite_AlterAutoGen() throws Throwable {
    final Properties props = new Properties();
    // avoid partitioned tables by default
    props.setProperty(com.pivotal.gemfirexd.Attribute.TABLE_DEFAULT_PARTITIONED,
        "false");
    // generates too many logs at fine level
    props.setProperty("log-level", "config");
    // start a client and servers with network servers
    startVMs(1, 3, 0, null, props);
    final int netPort = startNetworkServer(1, null, null);
    final String hostName = "localhost";
    final Connection netConn = TestUtil.getNetConnection(hostName, netPort,
        null, null);

    DdlUtilsTest.runImportExportFKTest(netConn, hostName, netPort,
        TestUtil.currentUserName, TestUtil.currentUserPassword, 100, 65000,
        false, false, true);

    DdlUtilsTest.runImportExportFKTest(netConn, hostName, netPort,
        TestUtil.currentUserName, TestUtil.currentUserPassword, 100, 65000,
        false, true, false);
  }
}
