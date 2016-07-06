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

package com.pivotal.gemfirexd.security;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.gemstone.gemfire.internal.AvailablePort;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;

@SuppressWarnings("serial")
public class LdapGroupAuthDUnit extends DistributedSQLTestBase {

  private final int locatorPort = AvailablePort
      .getRandomAvailablePort(AvailablePort.SOCKET);

  private final static String sysUser = "gemfire10";

  private final static int[] netPorts = new int[2];

  private final Connection[][] conns = new Connection[2][10];

  private final Statement[][] stmts = new Statement[2][10];

  public LdapGroupAuthDUnit(String name) {
    super(name);
  }

  @Override
  public void beforeClass() throws Exception {
    Properties bootProps = SecurityTestUtils
        .startLdapServerAndGetBootProperties(locatorPort, 0, sysUser);

    // start the locator with authentication first
    startLocatorVM("localhost", locatorPort, null, bootProps);
    // start a client and servers next
    startVMs(1, 3, 0, null, bootProps);
    // start network server on a couple of servers
    netPorts[0] = startNetworkServer(3, null, null);
    netPorts[1] = startNetworkServer(4, null, null);

    TestUtil.currentUserName = sysUser;
    TestUtil.currentUserPassword = sysUser;
    TestUtil.bootUserName = sysUser;
    TestUtil.bootUserPassword = sysUser;
  }

  @Override
  public void afterClass() throws Exception {
    super.baseShutDownAll();

    final LdapTestServer server = LdapTestServer.getInstance();
    if (server.isServerStarted()) {
      server.stopService();
    }
  }

  // shutdown nodes only once at the end in afterClass
  @Override
  public void baseShutDownAll() throws Exception {
    SQLException failure1 = LdapGroupAuthTest.closeStatements(conns[0],
        stmts[0]);
    SQLException failure2 = LdapGroupAuthTest.closeStatements(conns[1],
        stmts[1]);
    if (failure1 != null) {
      throw failure1;
    }
    if (failure2 != null) {
      throw failure2;
    }
  }

  // gemGroup1: gemfire1, gemfire2, gemfire3
  // gemGroup2: gemfire3, gemfire4, gemfire5
  // gemGroup3: gemfire6, gemfire7, gemfire8
  // gemGroup4: gemfire1, gemfire3, gemfire9
  // gemGroup5: gemfire7, gemfire4, gemGroup3
  // gemGroup6: gemfire2, gemGroup4, gemfire6

  public void testGroupAuthAddRemove() throws Exception {
    LdapGroupAuthTest.checkGroupAuthAddRemove(netPorts[0], conns[0],
        stmts[0]);
    LdapGroupAuthTest.checkGroupAuthAddRemove(netPorts[1], conns[1],
        stmts[1]);
  }

  public void testGroupAuthRefresh() throws Exception {
    LdapGroupAuthTest.checkGroupAuthRefresh(netPorts[0], conns[0], stmts[0]);
    LdapGroupAuthTest.checkGroupAuthRefresh(netPorts[1], conns[1], stmts[1]);
  }
}
