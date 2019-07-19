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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.Callable;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.FabricLocator;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.TestUtil;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.AvailablePortHelper;
import io.snappydata.test.dunit.VM;

public class GfxdWanTestBase extends DistributedSQLTestBase{
  
  public static final String SITE_A = "A";
  public static final String SITE_B = "B";
  public static final String SITE_C = "C";
  public static final String SITE_D = "D";
  
  public GfxdWanTestBase(String name) {
    super(name);
  }
  
  public int getSiteAccessorServerNum(String site) throws Exception {
    if (site.equals(SITE_A)) {
      return 3;
    }
    else if (site.equals(SITE_B)) {
      return 7;
    }
    else if (site.equals(SITE_C)) {
      return 11;
    }
    else if (site.equals(SITE_D)) {
      return 15;
    }
    else {
      throw new Exception("Unexpected site: " + site);
    }
  }
  public VM getSiteAccessor(String site) throws Exception {
    return this.serverVMs.get(getSiteAccessorServerNum(site));
  }

  /**
   * Compares two sites for results of given select query.
   * Returns true if both are exactly equal, false otherwise. 
   */
  public boolean compareSites(String siteOne, String siteTwo,
      String query) throws Exception {
    
    int siteOneServerNum = getSiteAccessorServerNum(siteOne);
    int siteTwoServerNum = getSiteAccessorServerNum(siteTwo);
    
    int siteOnePort = startNetworkServer(siteOneServerNum, null, null);
    int siteTwoPort = startNetworkServer(siteTwoServerNum, null, null);

    getLogWriter().info("Comparing site results for query:" + query);

    String firstUrl = TestUtil.getNetProtocol("localhost", siteOnePort, false);
    Properties props = new Properties();

    Connection conn = DriverManager.getConnection(firstUrl, props);
    Statement st = conn.createStatement();
    st.execute(query);
    ResultSet result = st.getResultSet();

    String secondUrl = TestUtil.getNetProtocol("localhost", siteTwoPort, false);
    Connection conn2 = DriverManager.getConnection(secondUrl, props);
    Statement st2 = conn2.createStatement();
    st2.execute(query);
    ResultSet result2 = st2.getResultSet();
    boolean retVal = TestUtil.compareResults(result, result2, false);

    st.close();
    st2.close();
    conn.close();
    conn2.close();
    return retVal;
  }

  public void executeSql(String site, String sql) throws Exception {
    // Always use VM to invoke tasks rather than the servernums and clientnums
    VM siteAccessor = getSiteAccessor(site);
    executeSql(siteAccessor, sql);
  }
  
  public void executeOnSite(String site, Runnable runnable) throws Exception {
    getSiteAccessor(site).invoke(runnable);
  }

  public void executeOnSite(String site, Callable callable) throws Exception {
    getSiteAccessor(site).invoke(callable);
  }

  // Although a superclass has a similar implementation, this one is adapted for
  // sites and executing through only accessors of each site
  public void executeOnSitesAsync(String[] sites, Runnable runnable, boolean waitForAllToFinish) throws Exception {
    ArrayList<AsyncInvocation> asyncList = new ArrayList<AsyncInvocation>(sites.length);
    for(String site: sites) {
      asyncList.add(getSiteAccessor(site).invokeAsync(runnable));
    }
    if(waitForAllToFinish) {
      joinAsyncInvocation(asyncList);
    }
  }
  
  public void executeOnSitesAsync(String[] sites, Runnable runnable) throws Exception {
    executeOnSitesAsync(sites, runnable, true);
  }
  
  /*
   * Adds expected exceptions to all VMs of given sites except the locators
   */
  public void _addExpectedException(String[] sites, Object[] exceptionClasses) throws Exception {
    int start = -1;
    int end = -1;
    for(String site: sites){
      if(site.equals(SITE_A)){
        start = 1; end = 3;
      } else if(site.equals(SITE_B)) {
        start = 5; end = 7;
      } else if(site.equals(SITE_C)) {
        start = 9; end = 11;
      } else if(site.equals(SITE_D)) {
        start = 13; end = 15;
      } else {
        throw new Exception("Invalid site:" + site);
      }
      for(int i = start; i <=end; i++) {
        VM serverVM = serverVMs.get(i);
        getLogWriter().info("Adding expected exceptions to site " + site + ", VM=" +i + 
            ", VMDir=" + serverVM.getWorkingDirectory().getAbsolutePath());
        super.addExpectedException(serverVM, exceptionClasses);
      }
    }
  }

  public void addExpectedException(String[] sites, Object[] exceptionClasses) throws Exception {
    VM[] vms = getServerVMs(sites);
    for(int i = 0; i < vms.length; i++) {
      VM serverVM = vms[i];
      super.addExpectedException(serverVM, exceptionClasses);
    }
  }
  public void addExpectedException(String[] sites, String[] strings) throws Exception {
    VM[] vms = getServerVMs(sites);
    for(VM vm: vms) {
      for(String s : strings) {
        super.addExpectedException(s, vm);
      }
    }
  }
  
  protected VM[] getServerVMs(String[] sites) throws Exception {
    int start = -1;
    int end = -1;
    // 3 server VMs per site (since one is a locator)
    VM[] vms = new VM[sites.length*3];
    int index = 0;
    for(String site: sites){
      if(site.equals(SITE_A)){
        start = 1; end = 3;
      } else if(site.equals(SITE_B)) {
        start = 5; end = 7;
      } else if(site.equals(SITE_C)) {
        start = 9; end = 11;
      } else if(site.equals(SITE_D)) {
        start = 13; end = 15;
      } else {
        throw new Exception("Invalid site:" + site);
      }
      for(int i = start; i <=end; i++) {
        vms[index] = serverVMs.get(i);
        ++index;
      }
    }
    return vms;
    
  }

  public void sqlExecuteVerify(String site, String sql, String goldenTextFile,
      String resultSetID, boolean usePrepStmt, boolean checkTypeInfo)
      throws Exception {
    VM siteVM = getSiteAccessor(site);
    sqlExecuteVerify(siteVM, sql, goldenTextFile, resultSetID, usePrepStmt, checkTypeInfo);
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
    execute(vm, sql, doVerify, null, null, null, null, null, true, false, false);
  }
  
  /**
   * Starts 4 sites, each with 4 VMs. In each site, first VM is locator, second
   * and third datastores and the fourth an accessor
   * 
   * @throws Exception
   */
  public void startSites() throws Exception{
    
    getLogWriter().info("Starting 4 WAN sites");
    
    getLogWriter().info("Starting SITE_A");
    //Site A 
    // Site A has 
    // 1 locator
    // 1 server with sg1 server group
    // 1 server with sg1 and sgSender server group 
    // 1 serve with sg1 and sgSender server group but that doesn't host data.
    Properties props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "_A");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    int aPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + aPort
        + "]");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    startLocatorVM("localhost", aPort, null, props);

    // start first server
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + aPort+ "]");
    props.setProperty("gemfirexd.debug.true","TraceDBSynchronizer");
    
    AsyncVM async1 = invokeStartServerVM(1, 0, "sg1", props); // sg1 is the server group
    AsyncVM async2 = invokeStartServerVM(2, 0, "sg1,sgSender", props); // group on which sender will be created
    props.setProperty("host-data", "false"); // accessor    
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1,sgSender", props);

    joinVMs(true, async1, async2, async3);
    
    assertTrue(this.serverVMs.size() == 4);// we have created 4 server Vms .. 1 locator and 3 server vms. 2 will host data 
    // 1 will not
    // 2 servers have sg1 and sgSender as the server - group
    getLogWriter().info("Started SITE_A");
    
    
    //Site B 
    // Site B has
    // 1 locator with local locator poiting to A's locator
    // 1 server with sg1 server group
    // 1 server with sg1 and sgSender server group
    // 1 server with sg1 and sgSender server group but doesn't host data
    getLogWriter().info("Starting SITE_B");
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "_B");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    int bPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + bPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["+ aPort + "]");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    startLocatorVM("localhost", bPort, null, props);

    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + bPort+ "]");
    props.setProperty("gemfirexd.debug.true","TraceDBSynchronizer");
    
    async1 = invokeStartServerVM(1, 0, "sg1", props);
    async2 = invokeStartServerVM(2, 0, "sg1,sgSender", props);
    props.setProperty("host-data", "false");
    async3 = invokeStartServerVM(3, 0, "sg1,sgSender", props);

    joinVMs(true, async1, async2, async3);
    assertTrue(this.serverVMs.size() == 8);
    getLogWriter().info("Started SITE_B");
    
    //Site C 
    // Site C has
    // 1 locator with local locator pointing to A's locator
    // 1 server with sg1 server group
    // 1 server with sg1 and sgSender server group
    // 1 server with sg1 and sgSender server group but doesn't host data
    getLogWriter().info("Starting SITE_C");
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "_C");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    int cPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + cPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["+ aPort + "]");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
    startLocatorVM("localhost", cPort, null, props);

    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + cPort+ "]");
    props.setProperty("gemfirexd.debug.true","TraceDBSynchronizer");
    
    async1 = invokeStartServerVM(1, 0, "sg1", props);
    async2 = invokeStartServerVM(2, 0, "sg1,sgSender", props);
    props.setProperty("host-data", "false");
    async3 = invokeStartServerVM(3, 0, "sg1,sgSender", props);

    joinVMs(true, async1, async2, async3);
    assertTrue(this.serverVMs.size() == 12);
    getLogWriter().info("Started SITE_C");
    
    //Site D 
    // Site D has
    // 1 locator with local locator pointing to A's locator
    // 1 server with sg1 server group
    // 1 server with sg1 and sgSender server group
    // 1 server with sg1 and sgSender server group but doesn't host data
    getLogWriter().info("Starting SITE_D");
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "_D");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    int dPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + dPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["+ aPort + "]");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
    startLocatorVM("localhost", dPort, null, props);

    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + dPort+ "]");
    props.setProperty("gemfirexd.debug.true","TraceDBSynchronizer");
    
    async1 = invokeStartServerVM(1, 0, "sg1", props);
    async2 = invokeStartServerVM(2, 0, "sg1,sgSender", props);
    props.setProperty("host-data", "false");
    async3 = invokeStartServerVM(3, 0, "sg1,sgSender", props);

    joinVMs(true, async1, async2, async3);
    getLogWriter().info("Started SITE_D");

    assertTrue(this.serverVMs.size() == 16);
  }
  
  public void startTwoSites() throws Exception {

    getLogWriter().info("Starting 2 WAN sites");

    int aLocatorPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    int bLocatorPort = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    
    getLogWriter().info("Starting SITE_A");
    //Site A 
    // Site A has 
    // 1 locator
    // 1 server with sg1 server group
    // 1 server with sg1 and sgSender server group 
    // 1 serve with sg1 and sgSender server group but that doesn't host data.
    Properties props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "_A");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + aLocatorPort
        + "]");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
    startLocatorVM("localhost", aLocatorPort, null, props);

    // start first server
    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + aLocatorPort+ "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["+ bLocatorPort + "]");
    props.setProperty("gemfirexd.debug.true","TraceDBSynchronizer");

    // sg1 is the server group
    AsyncVM async1 = invokeStartServerVM(1, 0, "sg1", props);
    // group on which sender will be created
    AsyncVM async2 = invokeStartServerVM(2, 0, "sg1,sgSender", props);
    props.setProperty("host-data", "false"); // accessor
    AsyncVM async3 = invokeStartServerVM(3, 0, "sg1,sgSender", props);

    joinVMs(true, async1, async2, async3);
    
    assertTrue(this.serverVMs.size() == 4);// we have created 4 server Vms .. 1 locator and 3 server vms. 2 will host data 
    // 1 will not
    // 2 servers have sg1 and sgSender as the server - group
    getLogWriter().info("Started SITE_A");
    
    //Site B 
    // Site B has
    // 1 locator with local locator poiting to A's locator
    // 1 server with sg1 server group
    // 1 server with sg1 and sgSender server group
    // 1 server with sg1 and sgSender server group but doesn't host data
    getLogWriter().info("Starting SITE_B");
    props = new Properties();
    props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, getUniqueName()
        + "_B");
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + bLocatorPort
        + "]");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["+ aLocatorPort + "]");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
    startLocatorVM("localhost", bLocatorPort, null, props);

    props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + bLocatorPort+ "]");
    props.setProperty("gemfirexd.debug.true","TraceDBSynchronizer");

    async1 = invokeStartServerVM(1, 0, "sg1", props);
    async2 = invokeStartServerVM(2, 0, "sg1,sgSender", props);
    props.setProperty("host-data", "false");
    async3 = invokeStartServerVM(3, 0, "sg1,sgSender", props);

    joinVMs(true, async1, async2, async3);

    assertTrue(this.serverVMs.size() == 8);
    getLogWriter().info("Started SITE_B");
    
  }

  public void startSites(int numSites) throws Exception {

    getLogWriter().info("Starting " + numSites + " WAN sites");
    int createdSites = 0;
    int remotePort = 0;
    
    while(createdSites < numSites){
      createdSites++;
      Properties props = new Properties();
      if (createdSites == 1) {
        getLogWriter().info("Starting SITE_A");
        props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR,
            getUniqueName() + "_A");
        props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "1");
      }
      if (createdSites == 2) {
        getLogWriter().info("Starting SITE_B");
        props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR,
            getUniqueName() + "_B");
        props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "2");
        props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
            + remotePort + "]");
      }
      if (createdSites == 3) {
        getLogWriter().info("Starting SITE_C");
        props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR,
            getUniqueName() + "_C");
        props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "3");
        props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
            + remotePort + "]");
      }
      if (createdSites == 4) {
        getLogWriter().info("Starting SITE_D");
        props.put(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR,
            getUniqueName() + "_D");
        props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "4");
        props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost["
            + remotePort + "]");
      }

      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
      int port = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
      props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + port
          + "]");
      startLocatorVM("localhost", port, null, props);

      props = new Properties();
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
      props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + port
          + "]");
      props.setProperty("gemfirexd.debug.true", "TraceDBSynchronizer,TraceDBSynchronizerHA");

      AsyncVM async1 = invokeStartServerVM(1, 0, "sg1,sg2", props);
      AsyncVM async2 = invokeStartServerVM(2, 0, "sg1,sg2,sgSender", props);
      props.setProperty("host-data", "false");
      AsyncVM async3 = invokeStartServerVM(3, 0, "sg1,sgSender", props);

      joinVMs(true, async1, async2, async3);
      if (createdSites == 1) {
        remotePort = port;
        assertTrue(this.serverVMs.size() == 4);
        getLogWriter().info("Started SITE_A");
      }
      if (createdSites == 2) {
        assertTrue(this.serverVMs.size() == 8);
        getLogWriter().info("Started SITE_B");
      }
      if (createdSites == 3) {
        assertTrue(this.serverVMs.size() == 12);
        getLogWriter().info("Started SITE_C");
      }
      if (createdSites == 4) {
        assertTrue(this.serverVMs.size() == 16);
        getLogWriter().info("Started SITE_D");
      }
    }
  }
  

  public void stopSites() throws Exception {

  }

  public static Integer createLocator(int dsId) throws Exception {
    // GfxdWanTestBase test = new GfxdWanTestBase(testName);
    int port = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    Properties props = new Properties();
    props.put(Attribute.SYS_PERSISTENT_DIR, "" + dsId);
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, "" + dsId);
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + port
        + "]");
    props.setProperty(DistributionConfig.START_LOCATOR_NAME, "localhost["
        + port + "],server=true,peer=true,hostname-for-clients=localhost");
    final FabricLocator locator = FabricServiceManager
        .getFabricLocatorInstance();
    locator.start("localhost", port, props);
    return port;
  }
  
  public static Integer createRemoteLocator(int dsId, int remoteLocPort) throws Exception{
    int port = AvailablePortHelper.getRandomAvailablePortForDUnitSite();
    Properties props = new Properties();
    props.put(Attribute.SYS_PERSISTENT_DIR, ""+dsId);
    props.setProperty(DistributionConfig.MCAST_PORT_NAME,"0");
    props.setProperty(DistributionConfig.DISTRIBUTED_SYSTEM_ID_NAME, ""+dsId);
    props.setProperty(DistributionConfig.LOCATORS_NAME, "localhost[" + port + "]");
    props.setProperty(DistributionConfig.START_LOCATOR_NAME, "localhost[" + port + "],server=true,peer=true,hostname-for-clients=localhost");
    props.setProperty(DistributionConfig.REMOTE_LOCATORS_NAME, "localhost[" + remoteLocPort + "]");
    final FabricLocator locator = FabricServiceManager.getFabricLocatorInstance();
    locator.start("localhost", port, props);
    return port;
  }
  
  protected String getSQLSuffixClause() {
    return "";
  }    
}
