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

package com.pivotal.gemfirexd.internal.engine.distributed;

import com.gemstone.gemfire.ForcedDisconnectException;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalLocator;
import com.gemstone.gemfire.distributed.internal.membership.jgroup.MembershipManagerHelper;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.org.jgroups.Event;
import com.gemstone.org.jgroups.JChannel;
import com.gemstone.org.jgroups.stack.Protocol;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl;
import com.pivotal.gemfirexd.internal.iapi.error.ShutdownException;

import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.VM;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({ "serial" })
public class GemFireXDReconnectDUnit extends DistributedSQLTestBase {

  public GemFireXDReconnectDUnit(String name) {
    super(name);
  }

  /**
   * testReconnectServer is based on a test by the same name in GemFireXDHADUnit.
   * It starts two servers and a client.  A schema is created and one of the
   * tables is populated.  An async client thread then performs queries on the
   * populated table while one of the servers is crashed and reconnected.
   * The untouched server is then shut down and queries are performed on the
   * reconnected server to demonstrate that it recovered its schema and
   * data.
   * @throws Exception
   */
  public void testReconnectServer() throws Exception {
    Properties props = new Properties();
    // set a reconnect-wait that's long enough for failure-detection to clean the view
    // when the server is crashed
    props.put(DistributionConfig.MAX_WAIT_TIME_FOR_RECONNECT_NAME, "15000");
    props.put(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME, "false");
    startVMs(1, 2, 0, null, props); // #clients, #servers, mcast-port, server-groups, connect props
    clientSQLExecute(1, "create table Account ("
        + " id int primary key, name varchar(100), type int )"
        + " replicate");
    for (int i = 1; i < 21; ++i) {
      serverSQLExecute(1, "insert into Account values (" + i + ", 'Account "
          + i + "'," + (i % 2) + " )");
    }
    addExpectedException(new int[] { 1 }, new int[] { 1, 2 },
        new Object[] { ShutdownException.class, CacheClosedException.class, ForcedDisconnectException.class });
    for (int i = 0; i < 10; i++) {
      String createStmnt = "create table testTable" + i + "(id" + i
          + " int, name" + i + " varchar(100))";
      clientSQLExecute(1, createStmnt);
    }
    // start async queries in clients and cause a forced-disconnect
    // in one of the servers.  The server should reconnect and the clients
    // should be (relatively) unaffected.
    List<AsyncInvocation> ainvoke = new ArrayList<AsyncInvocation>();
    getLogWriter().info("client vm size = "+this.clientVMs.size());
    GemFireXDHADUnit.selectQueryAndExecute(this.clientVMs.get(0), ainvoke);
    forceDisconnectAServerVM(this.serverVMs.get(0), true); // throws exception if reconnect doesn't occur
    getLogWriter().info("forcing a second reconnect");
    forceDisconnectAServerVM(this.serverVMs.get(0), true);
    for (int i = 0; i < ainvoke.size(); i++) {
      GemFireXDHADUnit.joinAsyncInvocation(ainvoke.get(i), 30 * 1000);
    }
    for (int i = 0; i < ainvoke.size(); i++) {
      if (ainvoke.get(i).exceptionOccurred()) {
        fail("exception during " + i, ainvoke.get(i).getException());
      }
    }
    // stop the unaffected server and show that queries still work correctly
    stopVM(this.serverVMs.get(1), "server");
    GemFireXDHADUnit.selectQueryAndExecute(this.clientVMs.get(0), ainvoke);
    for (int i = 0; i < ainvoke.size(); i++) {
      GemFireXDHADUnit.joinAsyncInvocation(ainvoke.get(i), 30 * 1000);
    }
    for (int i = 0; i < ainvoke.size(); i++) {
      if (ainvoke.get(i).exceptionOccurred()) {
        fail("exception during " + i, ainvoke.get(i).getException());
      }
    }
    removeExpectedException(new int[] { 1 }, new int[] { 1, 2 },
        new Object[] { ShutdownException.class, CacheClosedException.class, ForcedDisconnectException.class });
  }
  
  
  /**
   * ensure that locator services can auto-reconnect.  Start two locators
   * and force-disconnect one of them a couple of times.  Then stop the other
   * locator and show that the one that got the force-disconnects is usable.
   * 
   */
  public void testReconnectLocator() throws Exception {
    int locPort1 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    int locPort2 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
  
    final String address = "localhost";
    // try with both host:port and host[port] for client connection
    String locators = address + '[' + locPort1 + "]," + address + '['
        + locPort2 + "]";

    Properties props = new Properties();
    props.put(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME, "false");
    props.setProperty(DistributionConfig.LOCATORS_NAME, locators);
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.put(DistributionConfig.MAX_WAIT_TIME_FOR_RECONNECT_NAME, "15000");
    
    startLocatorVM(address, locPort1, null, props);
    startLocatorVM(address, locPort2, null, props);
    
    addExpectedException(new int[] { }, new int[] { 1, 2 },
        new Object[] { ShutdownException.class, CacheClosedException.class, ForcedDisconnectException.class });

    forceDisconnectAServerVM(this.serverVMs.get(0), true);
    forceDisconnectAServerVM(this.serverVMs.get(0), true);

    removeExpectedException(new int[] { }, new int[] { 1, 2 },
        new Object[] { ShutdownException.class, CacheClosedException.class, ForcedDisconnectException.class });

    // stop the other locator
    stopVMNum(-2);
    
    // ensure that both clients and servers can connect to the restarted locator
    startVMs(1, 1, 0, null, props);
  }

  /*
   * Tests whether
   *   1. reconnect works after auth is enabled
   *   2. reconnect starts network server properly and accepts connection if
   *      it was enabled earlier
   */
  public void testReconnect_auth_and_netServer() throws Exception {
    int locPort1 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    int locPort2 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);

    final String address = "localhost";
    // try with both host:port and host[port] for client connection
    String locators = address + '[' + locPort1 + "]," + address + '['
        + locPort2 + "]";

    Properties props = new Properties();
    props.setProperty(DistributionConfig.LOCATORS_NAME, locators);
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.put(DistributionConfig.MAX_WAIT_TIME_FOR_RECONNECT_NAME, "15000");
    props.put(DistributionConfig.DISABLE_AUTO_RECONNECT_NAME, "false");
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("gemfirexd.user.sd", "pwd"); // system user
    props.setProperty("user", "sd");
    props.setProperty("password", "pwd");

    // locator1
    props.setProperty("start-locator", "localhost[" + locPort1 + ']');
    startVMs(0, 1, 0, null, props);

    // locator2
    props.setProperty("start-locator", "localhost[" + locPort2 + ']');
    startVMs(0, 1, 0, null, props);

    props.remove("start-locator");
    startVMs(1, 1, 0, null, props);

    // start network server
    int inetPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
//    getLogWriter().info("port = " + inetPort);
    startNetworkServer(3, null, props, inetPort);
    Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");

    Connection conn = TestUtil.getNetConnection(inetPort, null, props2);
    Statement st = conn.createStatement();
    st.execute("create table t1(col1 int, col2 int)");

    addExpectedException(new int[] { }, new int[] { 3 },
        new Object[] { ShutdownException.class, CacheClosedException.class,
            ForcedDisconnectException.class });

    forceDisconnectAServerVM(this.serverVMs.get(2), true);

    // make sure that a connection can be established after reconnect
    // without explicitly starting the network server
//    startNetworkServer(3, null, props, inetPort);
    conn = TestUtil.getNetConnection(inetPort, null, props2);
    st = conn.createStatement();
    st.execute("select * from t1");

    removeExpectedException(new int[] { }, new int[] { 3 },
        new Object[] { ShutdownException.class, CacheClosedException.class,
            ForcedDisconnectException.class });
  }

  public boolean forceDisconnectAServerVM(VM vm, final boolean waitForReconnect) {
    return (Boolean)vm.invoke(new SerializableCallable("crash distributed system") {
      public Object call() throws Exception {
        final DistributedSystem msys = InternalDistributedSystem.getAnyInstance();
        final FabricServiceImpl service = (FabricServiceImpl)FabricServiceManager.currentFabricServiceInstance();
        final Locator oldLocator = Locator.getLocator();
        MembershipManagerHelper.playDead(msys);
        JChannel c = MembershipManagerHelper.getJChannel(msys);
        Protocol udp = c.getProtocolStack().findProtocol("UDP");
        udp.stop();
        udp.passUp(new Event(Event.EXIT, new ForcedDisconnectException("killing member's ds")));
        if (oldLocator != null) {
          WaitCriterion wc = new WaitCriterion() {
            public boolean done() {
              return ((InternalLocator)oldLocator).isStopped();
            }
            public String description() {
              return "waiting for locator to stop: " + oldLocator;
            }
          };
          waitForCriterion(wc, 10000, 50, true);
        }
        if (waitForReconnect) {
          WaitCriterion wc = new WaitCriterion() {
            public boolean done() {
              return service.status() == FabricService.State.RECONNECTING;
            }
            public String description() {
              return "waiting for service to enter reconnecting state: " + service.status();
            }
          };
          waitForCriterion(wc, 30000, 200, true);
          getLogWriter().info("test is waiting for reconnect to finish");
          boolean reconnected = service.waitUntilReconnected(3, TimeUnit.MINUTES);
          getLogWriter().info("test is done waiting.  reconnected="+reconnected);
          if (!reconnected) {
            fail("expected the system to reconnect");
          }
        }
        return true;
      }
    });
  }
}
