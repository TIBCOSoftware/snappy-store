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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.security.AuthenticationFailedException;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.FabricLocator;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.impl.services.monitor.FileMonitor;
import com.pivotal.gemfirexd.security.SecurityTestUtils.AuthenticationSchemes;
import com.pivotal.gemfirexd.security.SecurityTestUtils.priv_set;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

/**
 * 
 * @author soubhikc
 */
@SuppressWarnings("serial")
public class GemFireXDAuthenticationDUnit extends DistributedSQLTestBase {

  String origdebugtrue;
  String origmonitorverbose;

  public GemFireXDAuthenticationDUnit(String name) {
    super(name);
    if (getLogLevel().startsWith("fine")) {
      origdebugtrue = System.setProperty("gemfirexd.debug.true",
          "TraceAuthentication,TraceFabricServerBoot");
      origmonitorverbose = System.setProperty("gemfirexd.monitor.verbose",
          "true");
    }
  }

  @Override
  public void beforeClass() throws Exception {
    final LdapTestServer server = LdapTestServer.getInstance();
    if (!server.isServerStarted()) {
      server.startServer();
    }
  }

  @Override
  public void afterClass() throws Exception {
    final LdapTestServer server = LdapTestServer.getInstance();
    if (server.isServerStarted()) {
      server.stopService();
    }
  }

  @Override
  public void tearDown2() throws Exception {
    Connection conn = TestUtil.jdbcConn;
    if (conn == null && GemFireStore.getBootedInstance() != null) {
      conn = TestUtil.getConnection();
    }
    if (conn != null) {
      TestUtil.dropAllUsers(conn);
    }
    if (getLogLevel().startsWith("fine")) {
      if(origdebugtrue == null)
        System.clearProperty("gemfirexd.debug.true");
      else
        System.setProperty("gemfirexd.debug.true", origdebugtrue);
    
      if(origmonitorverbose == null)
        System.clearProperty("gemfirexd.monitor.verbose");
      else
        System.setProperty("gemfirexd.monitor.verbose", origmonitorverbose);
    }
    super.tearDown2();
  }

  //kind of funny to test failure first instead of basic, but reason
  //is it checks a particular condition of 'member join' failure and
  //subsequently join success. Uncovered a bug in peer credential caching
  //of member join in AuthenticationServiceBase.
  public void testAuthenticationFailureDueToCredentialMismatch() throws Exception {

    final AuthenticationSchemes scheme = AuthenticationSchemes.BUILTIN;

    final String locatorPort = String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS));
    
    final Properties diffSystemProps = scheme.startupProps(false, true, false, true, false);
    final Properties locatorConnectionCredentials = scheme.bootCredentials();
    
    //override encrypted password in user DEFINITION as these will be passed to fabapi, which will
    //inturn do this.
    String userName = locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
    userName = userName == null ? locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
    diffSystemProps.setProperty(Property.USER_PROPERTY_PREFIX
        + userName,
        locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR));
    diffSystemProps.putAll(locatorConnectionCredentials);

    getLogWriter().info("diffSystem " + diffSystemProps);
    final Properties locatorBootProperties = new Properties();
    locatorBootProperties.putAll(diffSystemProps);

    SerializableCallable startlocator = new SerializableCallable(
        "starting locator") {

      @Override
      public Object call() {

        String currentHost = getIPLiteral();
        try {
          TestUtil.doCommonSetup(locatorBootProperties);
          String sysDirName = getSysDirName();
          locatorBootProperties.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, sysDirName);

          FabricLocator locator = FabricServiceManager
              .getFabricLocatorInstance();
          locator.start(currentHost, Integer.parseInt(locatorPort),
              locatorBootProperties);

        } catch (NumberFormatException e) {
          e.printStackTrace();
          getLogWriter().error("NumberFormatException occurred ", e);
          throw new RuntimeException(e);
        } catch (SQLException e) {
          e.printStackTrace();
          getLogWriter().error("SQLException occurred ", e);
          throw new RuntimeException(e);
        }

        return currentHost;
      }
    };

    VM vm1 = Host.getHost(0).getVM(0);
    getLogWriter().info(
        "Choosen vm1 pid = " + vm1.getPid()
            + " as locator VM and adding it to serverVM list. PropertyList " + locatorBootProperties);

    String locatorhost = (String)vm1.invoke(startlocator);
    final Class<?>[] expectedexceptionlist = new Class[] { SQLException.class,
        AuthenticationFailedException.class };
    
    try {
      // considering locator VM as server VM though it doesn't hosts data.
      this.serverVMs.add(vm1);

      final String locator = locatorhost + "[" + locatorPort + "]";

      VM vm2 = Host.getHost(0).getVM(1);

      final SerializableRunnable expectedException = new SerializableRunnable(
          "GemFireXDAuthenticationDUnit: add expected exception") {
        @Override
        public void run() {
          // needed for VM that will fail authentication as log file gets
          // created during boot
          // and we want this line between log creation and ds.connect(...)
          // attempt.
          for (Class<?> c : expectedexceptionlist) {
            FileMonitor monitor = (FileMonitor)Monitor
                .getCachedMonitorLite(false);
            monitor.report("<ExpectedException action=add>" + c.getName()
                + "</ExpectedException>");
          }

          TestUtil.addExpectedException(expectedexceptionlist);
        }
      };
      
      vm1.invoke(expectedException);
      vm2.invoke(expectedException);

      final Properties serverprops = scheme.startupProps(false, true,
          false, true, false);
      final Properties connprops = scheme.bootCredentials();
      // override encrypted password in user DEFINITION as these will be passed
      // to fabapi, which will
      // inturn do this.
      userName = connprops.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
      userName = userName == null ? connprops.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
      serverprops.setProperty(Property.USER_PROPERTY_PREFIX
          + userName, connprops
          .getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR));
      serverprops.putAll(scheme.bootCredentials());

      TestUtil.doCommonSetup(serverprops);
      serverprops.setProperty("locators", locator);
      final String vminfo =  " vm2 pid = "
      + vm2.getPid() ;

      getLogWriter().info(
          "Attempt 1 to start server " + vminfo + " with properties .... " + serverprops);
      
      //attempt failure of connection because of system user not recognized at the locator
      boolean isSuccess = false;
      try {

        //vm2 will get added here.
        startVMs(0, 1, 0, null, serverprops);
        isSuccess = true;

      } catch (Throwable e) {
        Throwable t = e;
        while ((t = t.getCause()) != null
            && !(t instanceof AuthenticationFailedException) )
          ;
        // means we haven't found AuthenticationFailedException, rather
        // something else.
        if (t == null || !(t instanceof AuthenticationFailedException)) {
          fail("Unexpected exception occurred. "
              + "must have failed due to credential mismatch... ", e);
        }
        else {
          getLogWriter().info("Got expected exception " + t);
        }
      }
      finally {
        if (isSuccess)
          SanityManager.THROWASSERT("server start failure is expected.... ");
      }

      //attempt failure of connection because user/password attributed is same as locator
      //but gemfirexd.user.xxx server system user definition is different & authentication failing
      //locally. #42536
      try {
        userName = locatorBootProperties.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
        userName = userName == null ? locatorBootProperties.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
        serverprops.put(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, userName);
        serverprops.put(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, locatorBootProperties.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR));
        
        getLogWriter().info(
            "Attempt 2 to start server " + vminfo + " with properties .... " + serverprops);

        SerializableCallable tryServerStart = new SerializableCallable(
            "starting server without password") {

          @Override
          public Object call() {

            expectedException.run();
            String currentHost = getIPLiteral();
            try {
              TestUtil.doCommonSetup(serverprops);
              String sysDirName = getSysDirName();
              serverprops.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, sysDirName);

              FabricServer server = FabricServiceManager
                  .getFabricServerInstance();
              server.start(serverprops);

            } catch (NumberFormatException e) {
              throw new RuntimeException(e);
            } catch (SQLException e) {
              throw new RuntimeException(e);
            }

            return currentHost;
          }
        };
        
        vm2.invoke(tryServerStart);
        
        isSuccess = true;

      } 
      catch (Throwable e) {
        Throwable t = e;
        
        boolean foundExpectedException = false;
        
        //now we can get SQLException from AuthenticationServiceBase#refreshAuthenticationServices.
        do {
          if (t instanceof AuthenticationFailedException
              || (t instanceof SQLException && "08004".equals(((SQLException)t)
                  .getSQLState()))) {
            foundExpectedException = true;
            break;
          }
        } while ((t = t.getCause()) != null);
        
        // means we haven't found AuthenticationFailedException, rather
        // something else.
        if (!foundExpectedException) {
          SanityManager.THROWASSERT("UnExpected exception occurred. must have failed due to credential mismatch locally ... ", e);
        }
        else {
          getLogWriter().info("Got expected exception " + t);
        }
      }
      finally {
        if (isSuccess)
          SanityManager.THROWASSERT("server start failure is expected.... ");
      }
      
      //attempt failure of connection because system password is not passed to the locator
      //instead locally exception is raised. #42537
      try {
        
        serverprops.remove(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
        getLogWriter().info(
            "Attempt 3 to start server " + vminfo + " with properties .... " + serverprops);

        SerializableCallable tryServerStart = new SerializableCallable(
            "starting server without password") {

          @Override
          public Object call() {

            expectedException.run();
            String currentHost = getIPLiteral();
            try {
              TestUtil.doCommonSetup(serverprops);
              String sysDirName = getSysDirName();
              serverprops.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, sysDirName);

              FabricServer server = FabricServiceManager
                  .getFabricServerInstance();
              server.start(serverprops);

            } catch (NumberFormatException e) {
              throw new RuntimeException(e);
            } catch (SQLException e) {
              throw new RuntimeException(e);
            }

            return currentHost;
          }
        };
        
        vm2.invoke(tryServerStart);
        
        isSuccess = true;

      } catch (Throwable e) {
        Throwable t = e;
        while ((t = t.getCause()) != null
            && !(t instanceof AuthenticationFailedException) )
          ;
        // means we haven't found AuthenticationFailedException, rather
        // something else.
        if (t == null || !(t instanceof AuthenticationFailedException) ) {
          SanityManager.THROWASSERT("UnExpected exception occurred. must have failed due to password missing... ", e);
        }
        else {
          getLogWriter().info("Got expected exception " + t);
        }
      }
      finally {
        if (isSuccess)
          SanityManager.THROWASSERT("server start failure is expected.... ");
      }

      TestUtil.doCommonSetup(diffSystemProps);
      diffSystemProps.setProperty("locators", locator);

      //attempt failure of connection because system user id is not passed to the locator
      //instead locally exception is raised.
      try {
        final Properties noUser = new Properties();
        noUser.putAll(diffSystemProps);
        noUser.remove(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
        noUser.remove(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR);
        assertFalse(noUser.containsKey(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR) || noUser.containsKey(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR));
        
        getLogWriter().info(
            "Attempt 4 to start server " + vminfo + " with properties .... " + noUser);
        
        SerializableCallable tryServerStart = new SerializableCallable(
            "starting server without user") {

          @Override
          public Object call() {

            expectedException.run();
            String currentHost = getIPLiteral();
            try {
              TestUtil.doCommonSetup(noUser);
              String sysDirName = getSysDirName();
              noUser.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, sysDirName);

              FabricServer server = FabricServiceManager
                  .getFabricServerInstance();
              server.start(noUser);

            } catch (NumberFormatException e) {
              throw new RuntimeException(e);
            } catch (SQLException e) {
              throw new RuntimeException(e);
            }

            return currentHost;
          }
        };
        
        vm2.invoke(tryServerStart);
        
        isSuccess = true;

      } catch (Throwable e) {
        Throwable t = e;
        while ((t = t.getCause()) != null
            && !(t instanceof SQLException) )
          ;
        
        getLogWriter().info("received " + t);
        // means we haven't found SQLException, rather
        // something else.
        if (t == null || !(t instanceof SQLException) ) {
          SanityManager.THROWASSERT("UnExpected exception occurred... ", e);
        }
        else if( !"XJ040".equals(((SQLException)t).getSQLState()) ) {
          SanityManager.THROWASSERT("UnExpected SQLException ... ", t);
        }
        else {
          t = t.getCause();
          assertTrue("expecting 08001 nested exception", t != null);
          if(!"08001".equals(((SQLException)t).getSQLState())) {
            SanityManager.THROWASSERT("UnExpected nested SQLException ... ", t);
          }
          getLogWriter().info("Got expected exception " + t);
        }
      }
      finally {
        removeExpectedException(null, new int[] { 1, 2 }, expectedexceptionlist);
        if (isSuccess)
          SanityManager
              .THROWASSERT("server start failure is expected due to user id missing.... ");
      }

      this.serverVMs.remove(vm2);
      
      getLogWriter().info(
          "Attempt 5 to start server " + vminfo + " with properties .... " + diffSystemProps);
      
      //now attempt a successful connection on VM 2
      String sysDirName = getSysDirName();
      diffSystemProps.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, sysDirName);
      startVMs(0, 1, 0, null, diffSystemProps);
    } finally {
      SerializableRunnable stoplocator = new SerializableRunnable(
          "stopping locator") {

        @Override
        public void run() {

          try {

            final FabricService service = FabricServiceManager
                .currentFabricServiceInstance();
            if (service != null) {
              service.stop(locatorConnectionCredentials);
            }
            scheme.clear();

          } catch (NumberFormatException e) {
            e.printStackTrace();
            getLogWriter().error("NumberFormatException occurred ", e);
            throw new RuntimeException(e);
          } catch (SQLException e) {
            e.printStackTrace();
            getLogWriter().error("SQLException occurred ", e);
            throw new RuntimeException(e);
          }
        }
      };

      vm1.invoke(stoplocator);
    }
    
  }

  public void testBUILTINSchemeWithValidCredentials() throws Exception {
    runAuthentication(AuthenticationSchemes.BUILTIN);
  }

  public void testLDAPSchemeWithValidCredentials() throws Exception {
    runAuthentication(AuthenticationSchemes.LDAP);
  }
  
  /**
   * Tests skipping of authentication for member connections (peer or internal connections)
   * for built-in type
   * @throws Exception
   */
  public void testSkipBUILTINAuthenticationForMemberConnections() throws Exception{
    runAuthentication(AuthenticationSchemes.BUILTIN, true);
    
  }
  /**
   * Tests skipping of authentication for member connections (peer or internal connections)
   * for LDAP type.
   * @throws Exception
   */
  public void testSkipLDAPAuthenticationForMemberConnections() throws Exception {
    runAuthentication(AuthenticationSchemes.LDAP, true);
  }

  private void runAuthentication(final AuthenticationSchemes scheme) throws Exception{
    runAuthentication(scheme, false);
  }
  private void runAuthentication(final AuthenticationSchemes scheme,
                                boolean checkAuthSkipping)
      throws Exception {

    boolean reStartRequired = false;
    getLogWriter().info("Testing for " + scheme);

    for (int i = 1; i <= 2; i++) {

      Properties extraServerProps = new Properties();
      getLogWriter().info(
          "With " + (i == 1 ? "Implicit" : "Explicit")
              + " peer authentication system property set ");

      prepareServers(extraServerProps, scheme // current scheme
          , (i == 1 ? false : true) // explicitPeerAuth
          , reStartRequired // restart
          , false // withAuthorization
          , true // skipPersistDDLReplay
          , false // generateOnlyNewSysUser
          , false //readOnlyConnection
      );

      if(checkAuthSkipping) {
        // Register an observer for user connection authentication skipping on the first server.
        // User connection authentication SHOULD NOT be skipped.
        GemFireXDQueryObserverAdapter notSkippedObserver = new GemFireXDQueryObserverAdapter(){
          @Override
          public void userConnectionAuthenticationSkipped(boolean skipped)
          {
            getLogWriter().info("Skipped authentication:"+skipped);
            assertFalse(skipped);
          }
        };
        Object[] args = {notSkippedObserver};
        getLogWriter().info("SERVER VMS:" + this.serverVMs.size());
        this.serverVMs.get(0).invoke(GemFireXDQueryObserverHolder.class, new String("setInstance"), args);
        
        // Register an observer for internal connection authentication skipping.
        // Internal conn authentication SHOULD be skipped.
        GemFireXDQueryObserverAdapter skippedObserver = new GemFireXDQueryObserverAdapter(){
          @Override
          public void memberConnectionAuthenticationSkipped(boolean skipped)
          {
            getLogWriter().info("Skipped authentication:"+skipped);
            assertTrue(skipped);
          }
        };
        Object[] args2 = {skippedObserver};
        getLogWriter().info("SERVER VMS:" + this.serverVMs.size());
        this.serverVMs.get(1).invoke(GemFireXDQueryObserverHolder.class, new String("setInstance"), args2);
      }
      
      final String locator = extraServerProps.getProperty("locators");

      Connection systemconn = null;
      
      try {
        
      systemconn = bootRestAsPeerClient(scheme, locator,
          reStartRequired);
      scheme.CreateUsers(systemconn);
      systemconn.createStatement().execute("create table dummy (k int) ");

        SerializableRunnable check = new SerializableRunnable(
            "Round " + i +" Check AuthenticationScheme = " + scheme) {

          @Override
          public void run() {
            TestUtil.loadDriver();
            Connection conn = null;
            String tableName = null;
            try {
              Properties connectionProp = new Properties();
              connectionProp.putAll(scheme.connectionCredentials(true, true));
              getLogWriter().info(
                  "getting local connection using distributed system user "
                      + connectionProp);
              conn = TestUtil.getConnection(connectionProp);

              String userName = connectionProp.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
              userName = userName == null ? connectionProp.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
              tableName = "dummy__"
                  + userName;
              String crTab = "create table " + tableName + " (k int) ";

              getLogWriter().info("creating tab : " + crTab);
              conn.createStatement().execute(crTab);

              connectionProp.clear();
              
              getLogWriter().info("Done checking ");
            } catch (SQLException ex) {
              if (!ex.getSQLState().equals("X0Y32")) {
                fail("Some exception ", ex);
              }
              else {
                dropTab(conn, tableName);
                run();
              }
            } catch (Exception ex) {
              fail("Test Failed with " + ex);
            } finally {
              dropTab(conn, tableName);
              try {
                if(conn != null)
                   conn.close();
              } catch (SQLException e) {
                fail("unexpected exception ", e);
              }
              conn = null;
            }
          }

          private void dropTab(Connection conn, String tableName) {
            if (conn != null) {
              String dpTab = "drop table " + tableName;
              getLogWriter().info("dropping tab : " + dpTab);
              try {
                conn.createStatement().execute(dpTab);
              } catch (SQLException e) {
                // ignore table doesn't exists
                if (!e.getSQLState().equals("42Y55")) {
                  fail("Exception while resetting ", e);
                }
              }
            }
          }
        };

        getLogWriter().info("Round "+i+" Verifying local connections on every VM ");
        invokeInEveryVM(check);
      } catch (Exception ex) {
        getLogWriter().error("Round " +i+ "Exception happened ", ex);
        fail("Round " + i + " got error ", ex);
      } finally {

        reStartRequired = true;

        if (systemconn != null) {
          TestUtil.deletePersistentFiles = true;
          try {
            shutDownEverything(systemconn, scheme, (i == 1 ? false : true),
                true, true, 0);
            systemconn.close();
          } catch (Exception e) {
            getLogWriter().error("unexpected exception in close", e);
          }
        }
      }
    }
  }

  public void DISABLED_GEMXD11_testSchemaSharedBetweenUsersWithoutAuthorization() throws Exception {
    Properties extraServerProps = new Properties();

    final AuthenticationSchemes scheme = AuthenticationSchemes.BUILTIN;

    prepareServers(extraServerProps, scheme // current scheme
        , true // explicitPeerAuth
        , false // restart
        , false // withAuthorization
        , false // skipPersistDDLReplay
        , false // generateOnlyNewSysUser
        , false //readOnlyConnection
    );
    startVMs(0, 1, 0, null, extraServerProps);

    String locator = extraServerProps.getProperty("locators");
    
    final Properties commonProps = new Properties();
    commonProps.setProperty("mcast-port", "0");
    commonProps.setProperty("start-locator", locator);
    commonProps.setProperty("locators", locator);
    commonProps.setProperty("host-data", "true");
    commonProps.setProperty("log-level", getLogLevel());
    //lets get back what we will loose in shutdown.

    final String sharedSchemaName = SecurityTestUtils.commonSchemaName;
    Connection systemconn = null;
    try {

      {
        systemconn = bootRestAsPeerClient(scheme, locator, false);
        scheme.CreateUsers(systemconn);

        Statement stmts = systemconn.createStatement();

        String systemSchema = scheme.sysUser.toUpperCase();

        SecurityTestUtils.assertCurrentSchema(stmts, systemSchema);

        getLogWriter().info("creating sample table on " + systemSchema);
        stmts.execute("create table SampleTable (i int, j int)");

        {
          final Properties prop = scheme.bootCredentials();
          getLogWriter().info(
              "Connecting to ds using system user " + prop);
          final Connection conn = DriverManager.getConnection(
              TestUtil.getProtocol(), prop);
          // We want to control transactions manually. Autocommit is on by
          // default in JDBC.
          //conn.setAutoCommit(false);
          getLogWriter().info("creating common schema " + sharedSchemaName);
          conn.createStatement().execute("create schema " + sharedSchemaName);
          conn.close();
        }

        getLogWriter().info(
            "switching to common schema " + sharedSchemaName + " from "
                + systemSchema);
        stmts.execute("SET SCHEMA " + sharedSchemaName);

        SecurityTestUtils.assertCurrentSchema(stmts, sharedSchemaName);

        getLogWriter().info("creating SharedTable on " + sharedSchemaName);
        stmts.execute("create table SharedTable (i int, j int) PERSISTENT redundancy 2");

        getLogWriter().info("inserting to SharedTable on " + sharedSchemaName);
        stmts.execute("insert into SharedTable values(1,1), (2,2), (3,3)");

        // fall back to current user schema.
        getLogWriter().info("switching to current user schema");
        stmts.execute("SET CURRENT SCHEMA USER");

        SecurityTestUtils.assertCurrentSchema(stmts, systemSchema);
        
        stmts.close();
        
        invokeInEveryVM(new SerializableRunnable("log current disk directory") {
          @Override
          public void run() {
            TestUtil.deletePersistentFiles = false;
            
            getLogWriter().info(GfxdConstants.SYS_PERSISTENT_DIR_PROP + " = " + System
                .getProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP));
          }
        });
        TestUtil.deletePersistentFiles = false;
        getLogWriter().info(GfxdConstants.SYS_PERSISTENT_DIR_PROP + " = " + System
            .getProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP));
        
        shutDownEverything(systemconn, scheme, true, false, false, -1);
        systemconn.close();
      }

      final Class<?>[] expectedexceptionlist = new Class[] { SQLException.class,
          AuthenticationFailedException.class };
      final VM locatorvm = this.serverVMs.get(0);
      // lets try to use shared schema, booting up with dist_user.
      {
        getLogWriter().info(
            "booting with first connection in vm "
                + this.serverVMs.get(0).getPid()
                + " using system user with implicit locator ");
        locatorvm.invoke(
            new SerializableRunnable(
                "bringing up server 0 with embeded locator") {
              public void run() {
                  // used to start server vm with locator.
                  final Properties distBootProp = new Properties();
                  distBootProp.putAll(scheme.startupProps(false, false, false,
                      false, false));

                  distBootProp.putAll(commonProps);
                  distBootProp.putAll(scheme.bootCredentials());

                  TestUtil.doCommonSetup(distBootProp);
                  distBootProp.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR,
                      System.getProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP));

                  //try unsuccessful boot.
                  {
                    for (Class<?> c : expectedexceptionlist) {
                      FileMonitor monitor = (FileMonitor)Monitor
                          .getCachedMonitorLite(false);
                      monitor.report("<ExpectedException action=add>" + c.getName()
                          + "</ExpectedException>");
                    }
                    Properties failProp = new Properties();
                    failProp.putAll(distBootProp);
                    failProp.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "giberrish");
                    try {
                      getLogWriter().info(
                          "attempting to fail bootstrap with first connection using dist user "
                              + failProp);
                       TestUtil.getConnection(failProp);
                       fail("Supposed to fail for improper system user credential");
                    } catch (SQLException sqle) {
                      Throwable t = sqle;
                      boolean foundExpectedException = false;
                      do {
                        if (t instanceof SQLException && "08004".equals(((SQLException)t).getSQLState())) {
                          foundExpectedException = true;
                          break;
                        }
                        else if (t instanceof StandardException && "08004".equals(((StandardException)t).getSQLState())) {
                          foundExpectedException = true;
                          break;
                        }
                        
                      } while ((t = t.getCause()) != null);
                      
                      if (!foundExpectedException) {
                        SanityManager
                        .THROWASSERT("UnExpected exception occurred. must have failed due to system user password mismatch... "
                            + sqle.getSQLState(), sqle);
                      }
                    }
                    finally {
                      TestUtil.removeExpectedException(expectedexceptionlist);
                    }
                  }
                  
                  //try successful boot
                  try {
                    getLogWriter().info(
                        "booting with first connection using system user in implicit locator "
                            + distBootProp);
  
                    Connection conn = TestUtil.getConnection(distBootProp);
                    getLogWriter()
                        .info(
                            "got a local connection, trying to switch to shared schema ");
                    conn.createStatement().execute(
                        "SET SCHEMA " + sharedSchemaName);
  
                    SecurityTestUtils.assertCurrentSchema(conn.createStatement(),
                        sharedSchemaName);
  
                  } catch (SQLException e) {
                    getLogWriter().error("got unexpected exception " + e);
                    e.printStackTrace();
                    fail("unexpected exception ", e);
                  }
              }
            });
      }
      {
        SerializableRunnable check = new SerializableRunnable(
            "verifying distributed user") {

          @Override
          public void run() {

            // used for first connection using dist_user.
            final Properties distConnectionProp = new Properties();
            distConnectionProp.putAll(commonProps);
            distConnectionProp.remove("start-locator");

            distConnectionProp.putAll(scheme.connectionCredentials(true, true));

            TestUtil.doCommonSetup(distConnectionProp);
            distConnectionProp.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR,
                System.getProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP));
            
            
              //try unsuccessful boot.
              {
                Properties failProp = new Properties();
                failProp.putAll(distConnectionProp);
                failProp.putAll(scheme.connectionCredentials(true, false));
                failProp.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "giberrish");
                try {
                  for (Class<?> c : expectedexceptionlist) {
                    FileMonitor monitor = (FileMonitor)Monitor
                        .getCachedMonitorLite(false);
                    monitor.report("<ExpectedException action=add>" + c.getName()
                        + "</ExpectedException>");
                  }
                  locatorvm.invoke(new SerializableRunnable("Add expected exception") {

                    @Override
                    public void run() {
                      TestUtil.addExpectedException(expectedexceptionlist);
                    }
                    
                  });
                  getLogWriter().info(
                      "attempting to fail bootstrap with first connection using dist user "
                          + failProp);
                  TestUtil.getConnection(failProp);
                  fail("Supposed to fail for improper distributed user credential");
                } catch (SQLException sqle) {

                  Throwable t = sqle;
                  boolean foundExpectedException = false;
                  do {
                    if (t instanceof AuthenticationFailedException
                        || (t instanceof SQLException && "08004"
                            .equals(((SQLException)t).getSQLState()))) {
                      foundExpectedException = true;
                      break;
                    }
                  } while ((t = t.getCause()) != null);
                  
                  if (!foundExpectedException) {
                    SanityManager
                        .THROWASSERT("UnExpected exception occurred. must have failed due to dist user password mismatch... "
                            + sqle.getSQLState(), sqle);
                  }
                  else {
                    getLogWriter().info("got expected exception " + t);
                  }
                }
                finally {
                  locatorvm.invoke(new SerializableRunnable("remove expected exception") {

                    @Override
                    public void run() {
                      TestUtil.removeExpectedException(expectedexceptionlist);
                    }
                    
                  });
                  
                  TestUtil.removeExpectedException(expectedexceptionlist);
                }
              }
              
              //try successful boot
              try {
                getLogWriter().info(
                    "booting with first connection using dist user "
                        + distConnectionProp);
  
                Connection conn = TestUtil.getConnection(distConnectionProp);
  
                Statement stmts = conn.createStatement();
  
                getLogWriter().info(
                    "got a local connection, trying to switch to shared schema ");
                stmts.execute("SET SCHEMA " + sharedSchemaName);
  
                SecurityTestUtils.assertCurrentSchema(stmts, sharedSchemaName);
  
                ResultSet rs = conn.createStatement().executeQuery(
                    "select count(1) from SharedTable");
                rs.next();
  
                assertEquals(3, rs.getInt(1));
  
                getLogWriter()
                    .info(
                        "shared table browsed successfully via local dist user connection ");
                rs.close();
  
                stmts.close();
                distConnectionProp.clear();
                conn.close();
                conn = null;
              } catch (SQLException e) {
                e.printStackTrace();
                fail("unexpected exception ", e);
              }
          }
        };

        // skipping first one as it is already started.
        for (int i = 1; i < this.serverVMs.size(); i++) {
          VM v = this.serverVMs.get(i);
          getLogWriter().info(
              "testing local connection (host-data=true) using dist user on " + v.getPid());
          v.invoke(check);
        }

        commonProps.setProperty("host-data", "false");
        // starting with 1, to skip controller VM as it will be null in the list.
        for (int i = 1; i < this.clientVMs.size(); i++) {
          VM v = this.clientVMs.get(i);
          getLogWriter().info(
              "testing local connection (host-data=false) using dist user on " + v.getPid());
          v.invoke(check);
        }
        check.run();
      }

      // cleanup time.
      final Properties distConnectionProp = new Properties();
      distConnectionProp.putAll(commonProps);
      distConnectionProp.remove("start-locator");

      distConnectionProp.putAll(scheme.connectionCredentials(true, true));

      TestUtil.doCommonSetup(distConnectionProp);
      distConnectionProp.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR,
          System.getProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP));
      
      Connection conn = TestUtil.getConnection(distConnectionProp);
      
      getLogWriter().info("dropping table "+sharedSchemaName+".SharedTable ");
      conn .createStatement().execute("drop table "+sharedSchemaName+".SharedTable ");

      getLogWriter().info("dropping schema " + sharedSchemaName);
      conn .createStatement().execute("drop schema " + sharedSchemaName + " RESTRICT ");

      shutDownEverything(conn, scheme, true, false, true, -1);
      conn.close();

    } catch (SQLException sqle) {
      getLogWriter().error("Unexpected error ", sqle);
      fail("Got error ", sqle);
      throw sqle;
    } finally {
//      shutDownEverything(conn, scheme, true, true);
//      conn.close();
    }
  }

  public void testSimpleAuthorization() throws Exception {
    
      final AuthenticationSchemes scheme = AuthenticationSchemes.BUILTIN;
      final Properties sysprop;
      Connection systemconn = null;
      
      try {
        {
          getLogWriter().info("booting with system user ... ");
    
          sysprop = scheme.startupProps(true, true, true, false,
              false);
          final Properties connprops = scheme.bootCredentials();
          String userName = connprops.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
          userName = userName == null ? connprops.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
          // override encrypted password in user DEFINITION as these will be passed
          // to fabapi, which will
          // inturn do this.
          sysprop.setProperty(Property.USER_PROPERTY_PREFIX
              + userName, connprops
              .getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR));
          sysprop.putAll(scheme.bootCredentials());
          
          sysprop.remove(com.pivotal.gemfirexd.Property.AUTHZ_DEFAULT_CONNECTION_MODE);
          sysprop.remove(com.pivotal.gemfirexd.Property.AUTHZ_FULL_ACCESS_USERS);
  
          final String locator = scheme.getLocatorString();
    
          sysprop.setProperty("mcast-port", "0");
          sysprop.setProperty("start-locator", locator);
          sysprop.setProperty("locators", locator);
          sysprop.setProperty("host-data", "true");
    
          getLogWriter().info("About to start with properties .... " + sysprop);
    
          startVMs(0, 1, 0, null, sysprop);
    
          sysprop.remove("start-locator");
          startVMs(0, 1, 0, null, sysprop);
    
          getLogWriter().info(
              "should have started with system user " + scheme.sysUser + " pwd "
                  + scheme.sysPwd);
    
          sysprop.setProperty("host-data", "false");
          int thst = Host.getHostCount();
          for (int h = thst - 1; h >= 0; h--) {
            int tvms = Host.getHost(h).getVMCount() - this.serverVMs.size();
            getLogWriter().info(
                "starting " + tvms + " clients of host " + h + " with " + sysprop);
            startVMs(tvms, 0, 0, null, sysprop);
          }

          getLogWriter().info(
              "Connecting to distributed system using system user " + scheme.bootCredentials());
          
          systemconn = TestUtil.getConnection(scheme.bootCredentials());;
    
          // create dist-sys level users
          scheme.CreateUsers(systemconn);
    
          Statement stmts = systemconn.createStatement();
          SecurityTestUtils.tables.SharedTable.createTable(stmts);
          SecurityTestUtils.tables.SharedTable.doInsert(stmts, "(1,1,1)",
              "(2,2,2)", "(3,3,3)");
    
        }
        
        final String sharedSchemaName = scheme.sysUser;
  
        {
          final Properties propsFirstuser = scheme.connectionCredentials(true,
              false);
    
          getLogWriter().info(
              "getting local connection using distributed system user "
                  + propsFirstuser);
          Connection conn = TestUtil.getConnection(propsFirstuser);
          getLogWriter().info("connection successfull. ");
  
          Statement stmts = conn.createStatement();
          
          getLogWriter().info("Switching to schema of System User " + sharedSchemaName);
          stmts.execute("SET SCHEMA " + sharedSchemaName);
          try {
            SecurityTestUtils.tables.SharedTable.doInsert(stmts, "(4,4,4)",
                "(5,5,5)", "(6,6,6)");
            fail("supposed to fail ... ");
          } catch (SQLException ex) {
            if (!priv_set.getAllowedSQLStates(0).contains(ex.getSQLState())) {
              fail(ex.getSQLState() + " unexpected exception received " + ex);
            }
            else {
            getLogWriter()
                .info(
                    "Got expected exeception " + ex.getSQLState() + " message: "
                        + ex);
            }
    
          }
          
          SecurityTestUtils.checkDistUser(propsFirstuser, sharedSchemaName, 0, false);
          
        }
        
        getLogWriter().info("Testing 41781 .............. ");
        //test #41781
        {
            // create table via dist_user_1
            final Properties propsSeconduser = scheme.connectionCredentials(true,
                false);
    
            getLogWriter().info(
                "getting local connection using 2nd distributed system user "
                    + propsSeconduser);
            Connection dist_user1_conn = TestUtil.getConnection(propsSeconduser);
            
            String distuser1 = propsSeconduser.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
            distuser1 = distuser1 == null ? propsSeconduser.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : distuser1;
            
            final Statement user1stmt = dist_user1_conn.createStatement();
            
            user1stmt.execute("create table dist_user1_table (col1 int primary key) ");
            user1stmt.execute("insert into dist_user1_table values (1), (2), (3), (4) ");
    
            getLogWriter().info(
                "data populated on " + distuser1
                    + ".dist_user1_table, switching to another dist_user ");
    
            // try selecting via dist_user_2
            final Properties propsThirduser = scheme.connectionCredentials(true,
                false);
    
            getLogWriter().info(
                "getting local connection using 3rd distributed system user "
                    + propsThirduser);
            Connection dist_user2_conn = TestUtil.getConnection(propsThirduser);
            
            final Statement user2stmt = dist_user2_conn.createStatement();
            
            try {
              user2stmt.execute("SET SCHEMA " + distuser1);

              {
                ResultSet rs1 = user2stmt
                    .executeQuery("select count(1) from dist_user1_table");
                rs1.next();
                //TODO ENABLE after fixing SanityManager.THROWASSERT("This query should have failed ...  ");
              }
              
              try {
                ResultSet rs1 = user2stmt
                    .executeQuery("select count(col1) from dist_user1_table");
                rs1.next();
                SanityManager.THROWASSERT("This query should have failed ...  ");
              }
              catch(SQLException sqle) {
                  if (!"42502".equals(sqle.getSQLState())) {
                    SanityManager.THROWASSERT(
                        "Got some other exception than expected .. ", sqle);
                  }
              }
              
              try {
                ResultSet rs1 = user2stmt
                    .executeQuery("select * from dist_user1_table");
                while (rs1.next())
                  ;
              } catch (SQLException sqle) {
                if (!"42502".equals(sqle.getSQLState())) {
                  SanityManager.THROWASSERT(
                      "Got some other exception than expected .. ", sqle);
                }
              }
              
              // For bug#46521
              try {
                // drop statement should fail
                user2stmt.execute("DROP TABLE dist_user1_table"); 
                SanityManager.THROWASSERT(
                    "This query should have failed with exception 42507...  ");
              } catch (SQLException sqle) {
                if (!"42507".equals(sqle.getSQLState())) {
                  SanityManager.THROWASSERT(
                      "Got some other exception than expected .. ", sqle);
                }
              }
              
              {
                user2stmt.execute("SET SCHEMA " + sharedSchemaName);
                
                ResultSet rs1 = user2stmt
                    .executeQuery("select count(1) from " + SecurityTestUtils.tables.SharedTable.name());
                while(rs1.next());
              }
              
            } catch (SQLException sqle) {
              getLogWriter().error("got exception state " + sqle.getSQLState() + " exception " + sqle);
              throw sqle;
            }
        }
        
        TestUtil.shutDown();
      }
      finally {
        shutDownEverything(systemconn, scheme, true, false, true, 0);
        systemconn = null;
      }
  }

  public void testWithAuthorizationSchemaSharingWithoutStmtCaching() throws Exception {

    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
      }
    });
    System.setProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING, "true");
    
    verifyAuthorizationChecks();

    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
      }
    });
    System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
  }
  
  
  public void testWithAuthorizationSchemaSharingWithStmtCaching() throws Exception {
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);
      }
    });
    System.clearProperty(GfxdConstants.GFXD_DISABLE_STATEMENT_MATCHING);

    verifyAuthorizationChecks();

  }
  
  
  private void verifyAuthorizationChecks() throws Exception {
    
    final AuthenticationSchemes scheme = AuthenticationSchemes.BUILTIN;

    final String sharedSchemaName = SecurityTestUtils.commonSchemaName;

    // Put up initial configuration
    configureUsers(scheme, sharedSchemaName);

    // boot up with different sys user.
    String locator = null;
    final ArrayList<Integer> checkPrivList = new ArrayList<Integer>();
    Connection systemconn = null;
    {
      Properties extraServerProps = new Properties();

      getLogWriter().info("rebooting with different system user ... ");
      prepareServers(extraServerProps, scheme // current scheme
          , true // explicitPeerAuth
          , true // restart
          , true // withAuthorization
          , false // skipPersistDDLReplay
          , true // generateOnlyNewSysUser
          , false //readOnlyConnection
      );

      getLogWriter().info(
          "should have started with system user " + scheme.sysUser + " pwd "
              + scheme.sysPwd);

      locator = extraServerProps.getProperty("locators");

      systemconn = bootRestAsPeerClient(scheme, locator, true);

      Statement stmts = systemconn.createStatement();

      stmts.execute("SET SCHEMA " + sharedSchemaName);

      // insert few more to check table is accessible
      SecurityTestUtils.tables.SharedTable.doInsert(stmts, "(4,4,4)", "(5,5,5)",
          "(6,6,6)");

      //if we add dist users here, make sure you add in doPrivilegeChecks
      //kind of acts like a stack via distuserindex in scheme, see scheme.resetDistUserIndex().
      {
          // first dist user
          Properties connProps = scheme.connectionCredentials(true, false);
          String dist_user = connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
          dist_user = dist_user == null ? connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : dist_user;
    
          SecurityTestUtils.grantPrivilege(sharedSchemaName, dist_user, stmts,
              checkPrivList, SecurityTestUtils.priv_set.INSERT, SecurityTestUtils.priv_set.SELECT_ST_1_ST_2);
    
          // next dist user
          connProps = scheme.connectionCredentials(true, false);
          dist_user = connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
          dist_user = dist_user == null ? connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : dist_user;
              
          SecurityTestUtils.grantPrivilege(sharedSchemaName, dist_user, stmts,
              checkPrivList, SecurityTestUtils.priv_set.SELECT_ALL);
    
          // next dist user
          connProps = scheme.connectionCredentials(true, false);
          dist_user = connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
          dist_user = dist_user == null ? connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : dist_user;
              
          SecurityTestUtils.grantPrivilege(sharedSchemaName, dist_user, stmts,
              checkPrivList, SecurityTestUtils.priv_set.UPDATE_ALL, SecurityTestUtils.priv_set.SELECT_ST_1);
    
          // next dist user
          connProps = scheme.connectionCredentials(true, false);
          dist_user = connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
          dist_user = dist_user == null ? connProps.getProperty(
              com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : dist_user;
              
          SecurityTestUtils.grantPrivilege(sharedSchemaName, dist_user, stmts,
              checkPrivList, SecurityTestUtils.priv_set.UPDATE_ST_2);
          
          
          getLogWriter().info("CheckPrivList built into " + checkPrivList);
          systemconn.close();
      }

    }

    try {

      doPrivilegeChecks(scheme, checkPrivList, sharedSchemaName, false);

    } catch (SQLException sqle) {
      getLogWriter().error(
          "Got error " + sqle.getSQLState() + "  " + sqle.getErrorCode() + "  "
              + sqle.getMessage());

      getLogWriter().error(SanityManager.getStackTrace(sqle));

    } finally {
      shutDownEverything(systemconn, scheme, true, false, true, -1);
      systemconn = null;
    }

    try {

      // bring up again to verify granted permissions are persisted alright.
      {
        Properties extraServerProps = new Properties();

        getLogWriter().info("rebooting with different system user ... ");
        prepareServers(extraServerProps, scheme // current scheme
            , true // explicitPeerAuth
            , true // restart
            , true // withAuthorization
            , false // skipPersistDDLReplay
            , true // generateOnlyNewSysUser
            , false //readOnlyConnection
        );

        getLogWriter().info(
            "should have started with system user " + scheme.sysUser + " pwd "
                + scheme.sysPwd);

        locator = extraServerProps.getProperty("locators");

        systemconn = bootRestAsPeerClient(scheme, locator, true);
      }

      // now check again
      doPrivilegeChecks(scheme, checkPrivList, sharedSchemaName, false);

    } finally {
      shutDownEverything(systemconn, scheme, true, false, true, -1);
      systemconn = null;
    }

    try {

      // bring up again with readOnly connection and verify privileges don't allow DML.
      {
        Properties extraServerProps = new Properties();

        getLogWriter().info("rebooting with different system user ... ");
        prepareServers(extraServerProps, scheme // current scheme
            , true // explicitPeerAuth
            , true // restart
            , true // withAuthorization
            , false // skipPersistDDLReplay
            , true // generateOnlyNewSysUser
            , true //readOnlyConnection
        );

        getLogWriter().info(
            "should have started with system user " + scheme.sysUser + " pwd "
                + scheme.sysPwd);

        locator = extraServerProps.getProperty("locators");

        systemconn = bootRestAsPeerClient(scheme, locator, true);
      }

      // now check again
      doPrivilegeChecks(scheme, checkPrivList, sharedSchemaName, true);

    } finally {
      shutDownEverything(systemconn, scheme, true, false, true, 0);
      systemconn = null;
    }
  }

  private void doPrivilegeChecks(AuthenticationSchemes scheme,
      ArrayList<Integer> checkPrivList, String sharedSchemaName, boolean readOnlyConnection)
      throws SQLException {

    scheme.resetDistUserIndex();

    {
      // First DS users with INSERT privilege.
      final Properties propsFirstuser = scheme.connectionCredentials(true,
          false);

      getLogWriter().info("invoking for first user " + propsFirstuser);
      SecurityTestUtils.checkDistUser_everywhere(propsFirstuser,
          sharedSchemaName, checkPrivList.get(0), readOnlyConnection);
    }

    {
      // Second DS users with SELECT privilege.
      final Properties propsSeconduser = scheme.connectionCredentials(true,
          false);
      getLogWriter().info("invoking for second user " + propsSeconduser);
      SecurityTestUtils.checkDistUser_everywhere(propsSeconduser,
          sharedSchemaName, checkPrivList.get(1), readOnlyConnection);
    }

    {
      // Third DS users with UPDATE privilege.
      final Properties propsThirduser = scheme.connectionCredentials(true,
          false);
      getLogWriter().info("invoking for third user " + propsThirduser);
      SecurityTestUtils.checkDistUser_everywhere(propsThirduser,
          sharedSchemaName, checkPrivList.get(2), readOnlyConnection);
    }

    {
      // Fourth DS users with limited column UPDATE privilege.
      final Properties propsThirduser = scheme.connectionCredentials(true,
          false);
      getLogWriter().info("invoking for fourth user " + propsThirduser);
      SecurityTestUtils.checkDistUser_everywhere(propsThirduser,
          sharedSchemaName, checkPrivList.get(3), readOnlyConnection);
    }
  }
  
  public void testClientFailoverWithAuthentication() throws Exception {
    final SerializableRunnable expectedException = new SerializableRunnable(
        "GemFireXDAuthenticationDUnit: add expected exception") {
      @Override
      public void run() {
        // needed for VM that will fail authentication as log file gets
        // created during boot
        // and we want this line between log creation and ds.connect(...)
        // attempt.
          FileMonitor monitor = (FileMonitor)Monitor
              .getCachedMonitorLite(false);
          monitor.report("<ExpectedException action=add>" + java.security.InvalidAlgorithmParameterException.class.getName()
              + "</ExpectedException>");

        TestUtil.addExpectedException(java.security.InvalidAlgorithmParameterException.class);
      }
    };
    
    
    final AuthenticationSchemes scheme = AuthenticationSchemes.LDAP;
    
    final String locatorPort = String.valueOf(AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS));
    
    final Properties locatorSystemProps = scheme.startupProps(false, true, false, false, false);
    locatorSystemProps.setProperty(com.pivotal.gemfirexd.Attribute.GFXD_PREFIX
        + DistributionConfig.LOG_LEVEL_NAME, getLogLevel());
    locatorSystemProps.remove("SYS-USER");
    
    final Properties locatorConnectionCredentials = scheme.bootCredentials();
    
    TestUtil.bootUserName = locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
    TestUtil.bootUserName = TestUtil.bootUserName == null ? locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : TestUtil.bootUserName;
    TestUtil.bootUserPassword = locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
    getLogWriter().info("Recorded bootUserName " + TestUtil.bootUserName);
    
    //override encrypted password in user DEFINITION as these will be passed to fabapi, which will
    //inturn do this.
    String userName = locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
    userName = userName == null ? locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
    locatorSystemProps.setProperty(Property.USER_PROPERTY_PREFIX
        + userName,
        locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR));
    locatorSystemProps.putAll(locatorConnectionCredentials);

    
    getLogWriter().info("locator system props " + locatorSystemProps);
    
    SerializableCallable startlocator = new SerializableCallable(
        "starting locator") {

      @Override
      public Object call() {

        String currentHost = getIPLiteral();
        try {
          Enumeration<?> e = locatorSystemProps.propertyNames();
          while(e.hasMoreElements()) {
            Object k = e.nextElement();
            if(k == null || !(k instanceof String)) {
              continue;
            }
            if(((String)k).contains("auth-ldap")) {
              String v = locatorSystemProps.getProperty((String)k);
              System.setProperty((String)k, v);
            }
          }
          TestUtil.doCommonSetup(locatorSystemProps);
          String sysDirName = getSysDirName();
          locatorSystemProps.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, sysDirName);

          FabricLocator locator = FabricServiceManager
              .getFabricLocatorInstance();
          locator.start(currentHost, Integer.parseInt(locatorPort),
              locatorSystemProps);

          TestUtil.bootUserName = locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
          TestUtil.bootUserName = TestUtil.bootUserName == null ? locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : TestUtil.bootUserName;
          TestUtil.bootUserPassword = locatorConnectionCredentials.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
          getLogWriter().info("Recorded bootUserName " + TestUtil.bootUserName);
        } catch (NumberFormatException e) {
          e.printStackTrace();
          getLogWriter().error("NumberFormatException occurred ", e);
          throw new RuntimeException(e);
        } catch (SQLException e) {
          e.printStackTrace();
          getLogWriter().error("SQLException occurred ", e);
          throw new RuntimeException(e);
        }

        getLogWriter().info("returning currentHost" + currentHost);
        return currentHost;
      }
    };

    VM vm1 = Host.getHost(0).getVM(0);
    getLogWriter().info(
        "Choosen vm1 pid = " + vm1.getPid()
            + " as locator VM and adding it to serverVM list. PropertyList " + locatorSystemProps);

    vm1.invoke(expectedException);
    String locatorhost = (String)vm1.invoke(startlocator);
    try {
      // considering locator VM as server VM though it doesn't hosts data.
      this.serverVMs.add(vm1);
      
      Properties networkServerProperties = new Properties();
      /* Enable this after fixing JCE version of sun.
       
       networkServerProperties.setProperty(
          com.pivotal.gemfirexd.Property.DRDA_PROP_SECURITYMECHANISM,
          Constants.SecurityMechanism.ENCRYPTED_USER_AND_PASSWORD_SECURITY
              .name());*/
      int locatorNetPort = startNetworkServer(1, null, networkServerProperties);
      getLogWriter().info("n/w server on Locator with " + locatorNetPort);

      final String locator = locatorhost + "[" + locatorPort + "]";
      getLogWriter().info("locator string " + locator);

      VM vm2 = Host.getHost(0).getVM(1);
      vm2.invoke(expectedException);
      
      final Properties serverSystemProps = new Properties();

      serverSystemProps.putAll(locatorSystemProps);
      TestUtil.doCommonSetup(serverSystemProps);
      serverSystemProps.setProperty("locators", locator);
      final String vm2info =  " vm2 pid = "
      + vm2.getPid() ;

      getLogWriter().info(
          "start server " + vm2info + " with properties .... " + serverSystemProps);
      //vm2 will get added here.
      startVMs(0, 1, 0, null, serverSystemProps);
      
      int server1NetPort = startNetworkServer(2, null, networkServerProperties);

      /* Enable this after fixing JCE version of sun.
       
       locatorConnectionCredentials.setProperty(
          com.pivotal.gemfirexd.Attribute.CLIENT_SECURITY_MECHANISM, String
              .valueOf(NetConfiguration.SECMEC_EUSRIDPWD));*/
      getLogWriter().info(
          "connecting to n/w server with " + locatorConnectionCredentials);
      Connection netConn = TestUtil.getNetConnection(locatorNetPort, "", locatorConnectionCredentials);
      // TODO: TX: failover not yet for transactions
      netConn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      netConn.setAutoCommit(false);
      getLogWriter().info("n/w server started with " + server1NetPort);
      
      Statement netSt = netConn.createStatement();
      
      ResultSet rs = netSt.executeQuery("select * from sys.systables");
      
      while(rs.next()) {
        getLogWriter().info(String.valueOf(rs.getObject(1)));
      }
      
      VM vm3 = Host.getHost(0).getVM(2);
      vm3.invoke(expectedException);
      
      final String vm3info =  " vm3 pid = "
        + vm3.getPid() ;
      getLogWriter().info(
          "start server " + vm3info + " with properties .... " + serverSystemProps);
      
      //vm3 will get added here.
      startVMs(0, 1, 0, null, serverSystemProps);
      
      int server2NetPort = startNetworkServer(3, null, networkServerProperties);
      getLogWriter().info("n/w server started with " + server2NetPort);
      
      stopVMNum(-2);
      
      getLogWriter().info("Checking after failover ");

      rs = netSt.executeQuery("select * from sys.systables");

      while (rs.next()) {
        getLogWriter().info(String.valueOf(rs.getObject(1)));
      }
            
    }
    finally {
    }
    
  }

  // soubhik: if this test fails, then probably authorization fix have arrived
  // in derby post 10.4 codebase.
  // [sumedh] This test now fails but failures get hidden and instead it just
  // logs errors instead. It should be modified to a) correct behaviour against
  // 10.8.2.2 which is now used (do we need to port those changes to GemFireXD?),
  // and b) fail in case of unexpected exceptions instead of catching the error
  public void testDERBY_WithAuthorizationSchemaSharing() throws Exception {

    AuthenticationSchemes scheme = AuthenticationSchemes.BUILTIN;

    final String sharedSchemaName = "derbySharedTest".toUpperCase();

    // Put up initial configuration
    Connection systemconn = null;
    Properties props = new Properties();
    Properties propsToClear = new Properties();
    String bootUserName = null, bootUserPassword = null;
    try {

      final Properties sysprop = scheme.startupProps(true, true, true, false, false);
      for (Enumeration<?> e = sysprop.propertyNames(); e.hasMoreElements();) {
        String k = (String)e.nextElement();

        String val = sysprop.getProperty(k);

        /*k = k.replace("gemfirexd.", "derby.");
        k = k.replace("distributedsystem.", "database.");*/

        if ("gemfirexd.authentication.required".equals(k)) {
          k = "derby.connection.requireAuthentication";
        }
        else if ("gemfirexd.auth-provider".equals(k)) {
          k = "derby.authentication.provider";
        }
        else if ("gemfirexd.sql-authorization".equals(k)) {
          k = "derby.database.sqlAuthorization";
        }
        else if ("gemfirexd.authz-default-connection-mode".equals(k)) {
          k = "derby.database.defaultConnectionMode";
        }
        else if ("gemfirexd.authz-full-access-users".equals(k)) {
          k = "derby.database.fullAccessUsers";
        }
        else {
          k = k.replace("gemfirexd.", "derby.");
        }
        
        if (k.contains("UserName")) {
          k = k.replace("UserName", "user");
        }
        
        if (k.startsWith("derby.user.SYSTEM_")) {
          val = scheme.bootCredentials().getProperty("password");
        }
        
        getLogWriter().info("setting property " + k + " = " + val);
        System.setProperty(k, val);
        propsToClear.put(k, val);
      }

      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();

      props.putAll(scheme.bootCredentials());

      getLogWriter().info("About to start with properties .... " + props);

      bootUserName = props.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
      bootUserName = bootUserName == null ? props.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : bootUserName;
      bootUserPassword = props.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
      final String derbyConn = "jdbc:derby:newDerbySecDB;";
      String sysConnUrl = derbyConn + "create=true;" + "user=" + bootUserName
          + ";password=" + bootUserPassword;

      getLogWriter().info("Connecting system user with " + sysConnUrl);
      systemconn = DriverManager.getConnection(sysConnUrl);

      {
        Statement stmts = systemconn.createStatement();

        stmts.execute("create table SampleTable (i int, j int)");

        stmts.execute("create schema " + sharedSchemaName);

        stmts.execute("insert into " + bootUserName
            + ".SampleTable values(1,1)");

        stmts.execute("SET SCHEMA " + sharedSchemaName);

        stmts.execute("create table SharedTable (i int, j int)");

        stmts.execute("insert into SharedTable values(1,1), (2,2), (3,3)");

        stmts.close();
      }

      // create dist-sys level users
      getLogWriter().info("about to create distributed sys users ");

      CallableStatement cusr = systemconn
          .prepareCall("call SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY(?,?)");
      for (String u : scheme.clusterUsers) {
        cusr.setString(1, "derby.user." + u);
        cusr.setString(2, "PWD_" + u);
        cusr.execute();
        getLogWriter().info("created database user " + u);
      }
      systemconn.close();

      {
        String connUrl = derbyConn + "user=" + scheme.clusterUsers[2]
            + ";password=PWD_" + scheme.clusterUsers[2];
        getLogWriter().info("Connection using database user " + connUrl);
        Connection dbconn = DriverManager.getConnection(connUrl);
        Statement dbStmt = dbconn.createStatement();

        SecurityTestUtils.assertCurrentSchema(dbStmt, scheme.clusterUsers[2]);

        getLogWriter().info("switching to shared schema ");
        dbStmt.execute("SET SCHEMA " + sharedSchemaName);

        SecurityTestUtils.assertCurrentSchema(dbStmt, sharedSchemaName);

        ResultSet rs;
        try {
          // this same table should get created on different schema than the
          // previous
          getLogWriter().info("browsing SharedTable ");
          rs = dbconn.createStatement().executeQuery(
              "select count(1) from SharedTable");
          fail("expected derby table authorization exception ");
          // rs.next();
          //
          // assertTrue("Expected 3 Got " + rs.getInt(1), rs.getInt(1) == 3);
          // rs.close();
        } catch (SQLException sqle) {
          if (!sqle.getSQLState().equals("42500")) {
            fail(sqle.getSQLState() + " unexpected exception received " + sqle);
          }
        }

        try {
          getLogWriter().info(
              "expecting auth failure while inserting to SharedTable ");
          dbStmt.execute("insert into SharedTable values(4,4), (5,5), (6,6)");
          fail("expected derby authorization exception ");
        } catch (SQLException expected) {
          if (dbconn.isReadOnly() && !expected.getSQLState().equals("25502")) {
            fail(expected.getSQLState()
                + " unexpected exception on readonly connection received "
                + expected);
          }
          else if (!expected.getSQLState().equals("42500")) {
            fail(expected.getSQLState() + " unexpected exception received "
                + expected);
          }

        }

        try {
          rs = dbconn.createStatement().executeQuery(
              "select count(1) from SharedTable");
          fail("expected derby table authorization exception ");
//        rs.next();
//
//        assertTrue("Expected 3 Got " + rs.getInt(1), rs.getInt(1) == 3);
//        rs.close();
        } catch (SQLException sqle) {
          if (!sqle.getSQLState().equals("42500")) {
            fail(sqle.getSQLState() + " unexpected exception received " + sqle);
          }
        }

        // getLogWriter().info("switching back to own schema ");
        // dbStmt.execute("SET SCHEMA USER");
        //
        // SecurityTestUtils.assertCurrentSchema(dbStmt,
        // scheme.clusterUsers[2]);

        try {
          getLogWriter().info("browsing " + bootUserName + ".SampleTable ");
          rs = dbconn.createStatement().executeQuery(
              "select count(1) from " + bootUserName + ".SampleTable");
          fail("expected derby table authorization exception ");
//        rs.next();
//
//        assertTrue("Expected 1 Got " + rs.getInt(1), rs.getInt(1) == 1);
//        rs.close();
        } catch (SQLException sqle) {
          if (!sqle.getSQLState().equals("42500")) {
            fail(sqle.getSQLState() + " unexpected exception received " + sqle);
          }
        }

        try {
          getLogWriter().info(
              "expecting auth failure while  inserting to " + bootUserName
                  + ".SampleTable ");
          dbconn.createStatement().execute(
              "insert into " + bootUserName + ".SampleTable values(1,1)");
          fail("expected derby authorization exception ");
        } catch (SQLException expected) {
          if (dbconn.isReadOnly() && !expected.getSQLState().equals("25502")) {
            fail(expected.getSQLState()
                + " unexpected exception on readonly connection received "
                + expected);
          }
          else if (!expected.getSQLState().equals("42500")) {
            fail(expected.getSQLState() + " unexpected exception received "
                + expected);
          }
        }

        dbStmt.close();

        dbconn.close();
      }

    } catch (SQLException sqle) {
      getLogWriter().error(
          "Got error " + sqle.getSQLState() + "  " + sqle.getErrorCode() + "  "
              + sqle.getMessage());

      getLogWriter().error(SanityManager.getStackTrace(sqle));

    } finally {
      String shuturl = "jdbc:derby:;shutdown=true;user=" + bootUserName
          + ";password=" + bootUserPassword + ";";
      getLogWriter().info("Shutting down the apache EmbeddedDriver " + shuturl);

      try {
        DriverManager.getConnection(shuturl);
      } catch (SQLException ex) {
        getLogWriter().info(
            "sqlstate " + ex.getSQLState() + "  " + ex.getErrorCode());
        // ignore derby bug of ownership during shutdown.
        // search throw tr.shutdownDatabaseException(); in EmbedConnection
        if (!ex.getSQLState().equals("XJ015") && ex.getErrorCode() != 50000) {
          getLogWriter().error(SanityManager.getStackTrace(ex));
          throw ex;
        }

      }
      finally {
        for (Object k : propsToClear.keySet()) {
          System.clearProperty((String)k);
        }
      }
    }

  }

  private void configureUsers(AuthenticationSchemes scheme,
      String sharedSchemaName) throws Exception {
    Connection systemconn = null;
    try {
      Properties extraServerProps = new Properties();

      prepareServers(extraServerProps, scheme // current scheme
          , true // explicitPeerAuth
          , false // restart
          , true // withAuthorization
          , false // skipPersistDDLReplay
          , false // generateOnlyNewSysUser
          , false //readOnlyConnection
      );

      String locator = extraServerProps.getProperty("locators");

      getLogWriter().info("booting all clients ");
      systemconn = bootRestAsPeerClient(scheme, locator, false);

      // create dist-sys level users
      scheme.CreateUsers(systemconn);

      getLogWriter().info("Acquired Connection, will create tables/schemas");
      Statement stmts = systemconn.createStatement();

      stmts.execute("create table SampleTable (i int, j int)");

      if(sharedSchemaName != null) {
        stmts.execute("create schema " + sharedSchemaName);
  
        stmts.execute("SET SCHEMA " + sharedSchemaName);
      }
      
      SecurityTestUtils.tables.SharedTable.createTable(stmts);
      SecurityTestUtils.tables.SharedTable.doInsert(stmts, "(1,1,1)", "(2,2,2)",
          "(3,3,3)");

      // fall back to current sys user schema.
      stmts.execute("SET CURRENT SCHEMA USER");

    } catch (Exception ex) {
      fail("Got exception ", ex);
    } finally {
      if (systemconn != null) {
        shutDownEverything(systemconn, scheme, true, false, true, -1);
        systemconn.close();
        systemconn = null;
      }
    }
  }

  private void prepareServers(Properties props, AuthenticationSchemes scheme,
      boolean setExplicitPeerAuth, boolean restart, boolean withAuthorization,
      boolean skipPersistedDDLReplay, boolean generateOnlyNewSysUser,
      boolean createReadOnlyConnection) throws Exception {

    final Properties sysprop = scheme.startupProps(setExplicitPeerAuth, true,
        withAuthorization, generateOnlyNewSysUser, createReadOnlyConnection);

    SecurityTestUtils.setupAuthentication(scheme, sysprop);

    final String locator = scheme.getLocatorString();

    props.setProperty("mcast-port", "0");
    props.setProperty("start-locator", locator);
    props.setProperty("locators", locator);
    props.setProperty("host-data", "true");
    TestUtil.deletePersistentFiles = skipPersistedDDLReplay;
    props.putAll(scheme.bootCredentials());

    getLogWriter().info("About to start with properties .... " + props);
    if (!restart) {
      startVMs(0, 1, 0, null, props);
    }
    else {
      restartServerVMNums(new int[] { 1 }, 0, null, props);
    }

    props.remove("start-locator");

    if (!restart) {
      startVMs(0, 1, 0, null, props);
    }
    else {
      restartServerVMNums(new int[] { 2 }, 0, null, props);
    }
  }

  private Connection bootRestAsPeerClient(
      AuthenticationSchemes scheme, String locator, boolean restart)
      throws Exception {

    Properties connectionProp = new Properties();

    // connect using system user credentials
    connectionProp.putAll(scheme.bootCredentials());
    connectionProp.setProperty("locators", locator);
    connectionProp.setProperty("mcast-port", "0");
    connectionProp.setProperty("host-data", "false");
    TestUtil.deletePersistentFiles = false;

    int thst = Host.getHostCount();
    for (int h = thst - 1; h >= 0; h--) {
      if (!restart) {
        int tvms = Host.getHost(h).getVMCount() - this.serverVMs.size()
            + 1 /* include controller VM as well */;
        getLogWriter().info("starting " + tvms + " clients because there are "
            + serverVMs.size() + " serverVMs. client start properties: "
            + connectionProp);
        startVMs(tvms, 0, 0, null, connectionProp);
      }
      else {
        int tvms = this.clientVMs.size();
        int[] vms = new int[tvms];
        for (int i = 1; i <= tvms; i++) {
          getLogWriter().info(
              "restarting clients " + i + " of host " + h + " with "
                  + connectionProp);
          vms[i - 1] = i;
        }
        restartClientVMNums(vms, 0, null, connectionProp);
      }
    }

    connectionProp = scheme.bootCredentials();
    getLogWriter().info(
        "Connecting to distributed system using system user " + connectionProp);
    
    TestUtil.loadDriver();
    final Connection conn = DriverManager.getConnection(TestUtil.getProtocol(),
        connectionProp);
    // We want to control transactions manually. Autocommit is on by
    // default in JDBC.
    //conn.setAutoCommit(false);

    return conn;
  }

  private void shutDownEverything(Connection systemconn,
      final AuthenticationSchemes scheme, boolean explicitAuthentication,
      boolean dropDistUser, boolean clearSystemUser, int lastVMNum)
      throws Exception {

    getLogWriter().info("Shutting down the servers and local EmbeddedDriver ");

    if (dropDistUser) {
      scheme.DropUsers(systemconn);
    }

    try {
      // TODO: using sync version below hangs consistently???
      stopAllVMsAsync(lastVMNum);
    } catch (Throwable t) {
      //just to refill the stack trace.
      throw new Exception("exception while shutting down", t);
    } //finally {
    if (clearSystemUser) {
      final Properties sysprop = scheme.startupProps(explicitAuthentication,
          false, false, false, false);
      SecurityTestUtils.clearAuthenticationSetUp(sysprop, scheme);
    }
    //}
  }
  
  public void test48314AlterPriv() throws Exception {
    // start a locator and some severs with auth enabled
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties props = new Properties();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("gemfirexd.user.sd", "pwd"); // system user
    props.setProperty("user", "sd");
    props.setProperty("password", "pwd");
    props.setProperty("gemfirexd.sql-authorization", "true");
    startVMs(0, 1, 0, null, props);
    props.remove("start-locator");
    props.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, props);
    
    // connect as system user 'sd'
    final Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");
    Connection conn1 = TestUtil.getConnection(props2);
    Statement systemUser_stmt = conn1.createStatement();
    // create a new user
    systemUser_stmt.execute("call sys.create_user('user1', 'a')");
    systemUser_stmt.execute("create table t1(col1 int not null, col2 int)");
    systemUser_stmt.execute("insert into t1 values (1, 1)");
    
    // connect as user 'user1'
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    Connection conn2 = TestUtil.getConnection(props2);
    Statement user1_stmt = conn2.createStatement();
    
    //TESTS
    
    // make sure that user 'user1' can ALTER a table in its own schema 
    // without the need of explicit grant privilege 
    user1_stmt.execute("create table user1.t2(col1 int)");
    user1_stmt.execute("insert into user1.t2 values (1)");
    user1_stmt.execute("alter table user1.t2 add column col2 int");
    ResultSet rs = user1_stmt.executeQuery("select col1 from user1.t2");
    assertTrue(rs.next());
    rs.close();
    user1_stmt.execute("alter table user1.t2 drop column col2 restrict");

    // alter the table in another schema when no privileges are assigned
    try {
      user1_stmt.execute("alter table sd.t1 add column col3 int");
    } catch (SQLException se) {
      if (se.getSQLState().equals("42500")) {
        //ignore the expected exception
      } else {
        throw se;
      }
    }
    
    // grant alter privileges and alter the table in another schema 
    systemUser_stmt.execute("grant select, alter on sd.t1 to user1");
    user1_stmt.execute("alter table sd.t1 add column col3 int");
    rs = user1_stmt.executeQuery("select col1 from sd.t1");
    assertTrue(rs.next());
    assertFalse(rs.next());
    rs.close();
    user1_stmt.execute("alter table sd.t1 drop column col3 restrict");
    
    // revoke the privilege and alter again
    systemUser_stmt.execute("revoke alter on sd.t1 from user1");
    try {
      user1_stmt.execute("alter table sd.t1 add column col3 int");
    } catch (SQLException se) {
      if (se.getSQLState().equals("42500")) {
        // ignore the expected exception
        // select should work
        rs = user1_stmt.executeQuery("select col1 from sd.t1");
        assertTrue(rs.next());
        assertFalse(rs.next());
        rs.close();
      } else {
        throw se;
      }
    }
    
    // grant all privileges and alter the table again
    systemUser_stmt.execute("grant all privileges on sd.t1 to user1");
    user1_stmt.execute("alter table sd.t1 add column col3 int");
    rs = user1_stmt.executeQuery("select col1 from sd.t1");
    assertTrue(rs.next());
    assertFalse(rs.next());
    rs.close();
    user1_stmt.execute("alter table sd.t1 drop column col3 restrict");
    // revoke the privilege and alter again
    systemUser_stmt.execute("revoke all privileges on sd.t1 from user1");
    try {
      user1_stmt.execute("alter table sd.t1 add column col3 int");
    } catch (SQLException se) {
      if (se.getSQLState().equals("42500")) {
        // ignore the expected exception
      } else {
        throw se;
      }
    }
    
    // make sure that the system user 'sd' can alter any table
    // first check own table
    systemUser_stmt.execute("alter table sd.t1 add column col3 int");
    rs = systemUser_stmt.executeQuery("select col1 from sd.t1");
    assertTrue(rs.next());
    assertFalse(rs.next());
    rs.close();
    systemUser_stmt.execute("alter table sd.t1 drop column col3 restrict");
    // next check table owned by user 'user1'
    systemUser_stmt.execute("alter table user1.t2 add column col2 int");
    rs = systemUser_stmt.executeQuery("select col1 from user1.t2");
    assertTrue(rs.next());
    assertFalse(rs.next());
    rs.close();
    systemUser_stmt.execute("alter table user1.t2 drop column col2 restrict");
    
  }
  
  // users should be able to create temp tables even if they
  // are not owners of session schema as temp tables are created
  // in session schema
  public void testTempTableBug50505() throws Exception {
    // start a locator and some severs with auth enabled
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties props = new Properties();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("sqlfire.user.sd", "pwd"); // system user
    props.setProperty("user", "sd");
    props.setProperty("password", "pwd");
    props.setProperty("sqlfire.sql-authorization", "true");
    startVMs(0, 1, 0, null, props);
    props.remove("start-locator");
    props.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, props);

    // connect as system user 'sd'
    final Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");
    Connection conn1 = TestUtil.getConnection(props2);
    conn1.setAutoCommit(true);
    Statement systemUser_stmt = conn1.createStatement();
    // create a new user
    systemUser_stmt.execute("call sys.create_user('user1', 'a')");

    // connect as user 'user1'
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    Connection conn2 = TestUtil.getConnection(props2);
    conn2.setAutoCommit(false);
    Statement user1_stmt = conn2.createStatement();

    // Tests
    // create a temp table as a system user
    systemUser_stmt.execute("DECLARE GLOBAL TEMPORARY "
        + "TABLE session.test_A_P (aa varchar(20)) NOT LOGGED");
    // this will delete rows on commit (i.e. immediately due to autocommit)
    systemUser_stmt.execute("insert into session.test_A_P values ('aaa')");
    systemUser_stmt.execute("select * from session.test_A_P");
    ResultSet rs = systemUser_stmt.getResultSet();
    assertFalse(rs.next());
    rs.close();
    systemUser_stmt.execute("drop table session.test_A_P");

    // recreate with preserve rows on commit
    systemUser_stmt.execute("DECLARE GLOBAL TEMPORARY "
        + "TABLE session.test_A_P (aa varchar(20)) "
        + "ON COMMIT PRESERVE ROWS NOT LOGGED");
    systemUser_stmt.execute("insert into session.test_A_P values ('aaa')");
    systemUser_stmt.execute("select * from session.test_A_P");
    rs = systemUser_stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals("aaa", rs.getString(1));
    rs.close();
    systemUser_stmt.execute("drop table session.test_A_P");

    // create a temp table as a non-system user
    // should not throw exception due to authorization failure
    // for session schema
    user1_stmt.execute("DECLARE GLOBAL TEMPORARY "
        + "TABLE session.test_A_U (aa varchar(20)) NOT LOGGED");
    user1_stmt.execute("insert into session.test_A_U values ('aaa')");
    user1_stmt.execute("select * from session.test_A_U");
    rs = user1_stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals("aaa", rs.getString(1));
    rs.close();
    // explicit commit should delete rows
    conn2.commit();
    user1_stmt.execute("insert into session.test_A_U values ('aaa')");
    conn2.commit();
    user1_stmt.execute("select * from session.test_A_U");
    rs = user1_stmt.getResultSet();
    assertFalse(rs.next());
    rs.close();
    // drop table should also not throw exception
    user1_stmt.execute("drop table session.test_A_U");
  }

  public void testset_fullaccess() throws Exception {
    // start a locator and some severs with auth enabled
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties props = new Properties();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("sqlfire.user.sd", "pwd"); // system user
    props.setProperty("user", "sd");
    props.setProperty("password", "pwd");
    props.setProperty("gemfirexd.sql-authorization", "false");
    props.setProperty("gemfirexd.authz-default-connection-mode", "FULLACCESS");
    
    startVMs(0, 1, 0, null, props);
    props.remove("start-locator");
    props.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, props);
    
    // connect as system user 'sd'
    final Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");
    Connection conn1 = TestUtil.getConnection(props2);
    Statement systemUser_stmt = conn1.createStatement();
    // create a new user
    systemUser_stmt.execute("call sys.create_user('user1', 'a')");
    systemUser_stmt.execute("call sys.create_user('user2', 'b')");
    systemUser_stmt.execute("create table s1(col1 int)");

    // connect as user 'user1'
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    Connection conn2 = TestUtil.getConnection(props2);
    Statement user1_stmt = conn2.createStatement();
    user1_stmt.execute("create table t1(col1 int)");
    user1_stmt.execute("insert into t1 values(1)");
    conn2.close();
    
    props2.setProperty("user", "user2");
    props2.setProperty("password", "b");
    Connection conn3 = TestUtil.getConnection(props2);
    Statement user2_stmt = conn3.createStatement();
    user2_stmt.execute("insert into user1.t1 values(1)");
  }
  
  public void testset_readonlyaccess() throws Exception {
    // start a locator and some severs with auth enabled
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties props = new Properties();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("sqlfire.user.sd", "pwd"); // system user
    props.setProperty("user", "sd");
    props.setProperty("password", "pwd");
    props.setProperty("gemfirexd.sql-authorization", "false");
    props.setProperty("gemfirexd.authz-default-connection-mode", "READONLYACCESS");
    props.setProperty("authz-full-access-users", "sd");
    
    startVMs(0, 1, 0, null, props);
    props.remove("start-locator");
    props.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, props);
    
    // connect as system user 'sd'
    final Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");
    Connection conn1 = TestUtil.getConnection(props2);
    Statement systemUser_stmt = conn1.createStatement();
    // create a new user
    systemUser_stmt.execute("call sys.create_user('user1', 'a')");
    systemUser_stmt.execute("create table s1(col1 int)");
//    systemUser_stmt.execute("grant insert on sd.s1 to user1");

    // connect as user 'user1'
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    Connection conn2 = TestUtil.getConnection(props2);
    Statement user1_stmt = conn2.createStatement();
    user1_stmt.execute("select * from sd.s1");
    try {
      user1_stmt.execute("create table t1(col1 int)");
      fail("should throw exception");
      // user1_stmt.execute("insert into t1 values(1)");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("25503")) {
        throw se;
      }
    }
    
//    user1_stmt.execute("insert into sd.s1 values(1)");
    conn2.close();
  }
  
  public void testset_noaccess() throws Exception {
    // start a locator and some severs with auth enabled
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties props = new Properties();
    props.setProperty("start-locator", "localhost[" + locPort + ']');
    props.setProperty("auth-provider", "BUILTIN");
    props.setProperty("sqlfire.user.sd", "pwd"); // system user
    props.setProperty("user", "sd");
    props.setProperty("password", "pwd");
    props.setProperty("gemfirexd.sql-authorization", "false");
    props.setProperty("gemfirexd.authz-default-connection-mode", "NOACCESS");
    props.setProperty("authz-full-access-users", "sd");
    
    startVMs(0, 1, 0, null, props);
    props.remove("start-locator");
    props.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, props);
    
    // connect as system user 'sd'
    final Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");
    Connection conn1 = TestUtil.getConnection(props2);
    Statement systemUser_stmt = conn1.createStatement();
    // create a new user
    systemUser_stmt.execute("call sys.create_user('user1', 'a')");
    systemUser_stmt.execute("create table s1(col1 int)");

    // connect as user 'user1'
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    try {
    Connection conn2 = TestUtil.getConnection(props2);
    } catch (SQLException se) {
      if (!se.getSQLState().equals("08004")) {
        throw se;
      }
    }
  }

  public void testset_user_access_proc() throws Exception {
    // start a locator and some severs with auth enabled
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties startupProps = new Properties();
    startupProps.setProperty("start-locator", "localhost[" + locPort + ']');
    
    Properties authProperties = new Properties();
    authProperties.setProperty("auth-provider", "BUILTIN");
    authProperties.setProperty("sqlfire.user.sd", "pwd"); // system user
    authProperties.setProperty("user", "sd");
    authProperties.setProperty("password", "pwd");
    authProperties.setProperty("gemfirexd.sql-authorization", "true");
    authProperties.setProperty("gemfirexd.authz-default-connection-mode", "NOACCESS");
    authProperties.setProperty("authz-full-access-users", "sd");
    
    startupProps.putAll(authProperties);
    startVMs(0, 1, 0, null, startupProps);
    startupProps.remove("start-locator");
    startupProps.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, startupProps);
    
    // connect as system user 'sd'
    final Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");
    Connection conn1 = TestUtil.getConnection(props2);
    Statement systemUser_stmt = conn1.createStatement();
    // create new users
    systemUser_stmt.execute("call sys.create_user('user1', 'a')");
    systemUser_stmt.execute("call sys.create_user('user2', 'b')");

    //change the user access of user1 and user2
    systemUser_stmt
        .execute("CALL SYSCS_UTIL.SET_USER_ACCESS ('user1', 'FULLACCESS')");
    systemUser_stmt
        .execute("CALL SYSCS_UTIL.SET_USER_ACCESS ('USER2', 'READONLYACCESS')");
    
    verifyUserAccess(systemUser_stmt, "user1", "FULLACCESS");
    verifyUserAccess(systemUser_stmt, "USER2", "READONLYACCESS");
    
    systemUser_stmt.execute("create table s1(col1 int)");

    // connect as user 'user1', make sure user can ops
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    Connection conn2 = TestUtil.getConnection(props2);
    Statement user1_stmt = conn2.createStatement();
    user1_stmt.execute("create table t1(col1 int)");
    user1_stmt.execute("insert into t1 values(1)");
    user1_stmt.execute("drop table t1");
    conn2.close();

    // connect as user read only access 'user2', make sure user can do read-only
    // ops
    props2.setProperty("user", "user2");
    props2.setProperty("password", "b");
    Connection conn3 = TestUtil.getConnection(props2);
    Statement user2_stmt = conn3.createStatement();
    try {
      user2_stmt.execute("create table t1(col1 int)");
      fail("should throw exception");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("25503")) {
        throw se;
      }
    }
    conn3.close();
    
    //restart the cluster
    startupProps.setProperty("start-locator", "localhost[" + locPort + ']');
    startupProps.remove("locators");
    restartServerVMNums(new int[] { 1 }, 0, null, null);
    
    startupProps.remove("start-locator");
    startupProps.setProperty("locators", "localhost[" + locPort + ']');
    restartServerVMNums(new int[] { 2, 3, 4 }, 0, null, null);
    
    restartClientVMNums(new int[] { 1 }, 0, null, startupProps);
    
    // connect again as user 'user1'
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    conn2 = TestUtil.getConnection(props2);
    user1_stmt = conn2.createStatement();
    user1_stmt.execute("create table t1(col1 int)");
    user1_stmt.execute("insert into t1 values(1)");
    user1_stmt.execute("drop table t1");
    conn2.close();  
    
    // connect again as read only access user 'user2'
    props2.setProperty("user", "user2");
    props2.setProperty("password", "b");
    conn3 = TestUtil.getConnection(props2);
    user2_stmt = conn3.createStatement();
    try {
      user2_stmt.execute("create table t1(col1 int)");
      fail("should throw exception");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("25503")) {
        throw se;
      }
    }
    conn3.close();
    
    // remove the permissions and make sure that the uses can't connect
    systemUser_stmt.execute("CALL SYSCS_UTIL.SET_USER_ACCESS ('USER1', NULL)");
    systemUser_stmt.execute("CALL SYSCS_UTIL.SET_USER_ACCESS ('USER2', NULL)");
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    try {
      TestUtil.getConnection(props2);
      fail("should not have allowed a connection");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("08004")) {
        throw se;
      }
    }
    
    props2.setProperty("user", "user2");
    props2.setProperty("password", "b");
    try {
      TestUtil.getConnection(props2);
      fail("should not have allowed a connection");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("08004")) {
        throw se;
      }
    }
    
    verifyUserAccess(systemUser_stmt, "user1", "NOACCESS");
    verifyUserAccess(systemUser_stmt, "user2", "NOACCESS");
  }

  //make sure that  GET_USER_ACCESS() procedure returns expected access level
  private void verifyUserAccess(Statement systemUser_stmt, String user,
      String expectedAccessLevel) throws SQLException {
    systemUser_stmt.execute("VALUES SYSCS_UTIL.GET_USER_ACCESS ('" + user + "')");
    ResultSet rs = systemUser_stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals(expectedAccessLevel, rs.getString(1));
    rs.close();
  }
  
  public void testset_user_access_proc2() throws Exception {
    // start a locator and some severs with auth enabled
    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Properties startupProps = new Properties();
    startupProps.setProperty("start-locator", "localhost[" + locPort + ']');
    
    Properties authProperties = new Properties();
    authProperties.setProperty("auth-provider", "BUILTIN");
    authProperties.setProperty("sqlfire.user.sd", "pwd"); // system user
    authProperties.setProperty("user", "sd");
    authProperties.setProperty("password", "pwd");
    authProperties.setProperty("gemfirexd.sql-authorization", "true");
    authProperties.setProperty("gemfirexd.authz-default-connection-mode", "NOACCESS");
    authProperties.setProperty("authz-full-access-users", "sd,user1");
    
    startupProps.putAll(authProperties);
    startVMs(0, 1, 0, null, startupProps);
    startupProps.remove("start-locator");
    startupProps.setProperty("locators", "localhost[" + locPort + ']');
    startVMs(1, 3, 0, null, startupProps);
    
    // connect as system user 'sd'
    final Properties props2 = new Properties();
    props2.setProperty("user", "sd");
    props2.setProperty("password", "pwd");
    Connection conn1 = TestUtil.getConnection(props2);
    Statement systemUser_stmt = conn1.createStatement();
    // create new users
    systemUser_stmt.execute("call sys.create_user('user1', 'a')");
    systemUser_stmt.execute("call sys.create_user('user2', 'b')");

    // connect again as user 'user1'
    props2.setProperty("user", "user1");
    props2.setProperty("password", "a");
    Connection conn2 = TestUtil.getConnection(props2);
    Statement user1_stmt = conn2.createStatement();
    
    //change the user access of user1 to default level and try some ops 
    systemUser_stmt
        .execute("CALL SYSCS_UTIL.SET_USER_ACCESS ('USER1', NULL)");
    
    try {
      //query on existing connection should fail
      user1_stmt.execute("create table t1(col1 int)");
      fail("should not have allowed a connection");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("08004")) {
        throw se;
      }
    }
    conn2.close();
    
    try {
      //new connection should fail
      Connection conn3 = TestUtil.getConnection(props2);
      fail("should not have allowed a connection");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("08004")) {
        throw se;
      }
    }
    
    //change the user access of user1 to FULLACCESS level and try some ops 
    systemUser_stmt
        .execute("CALL SYSCS_UTIL.SET_USER_ACCESS ('USER1', 'FULLACCESS')");
    Connection conn4 = TestUtil.getConnection(props2);
    user1_stmt = conn4.createStatement();
    user1_stmt.execute("create table t1(col1 int)");
  }
}
