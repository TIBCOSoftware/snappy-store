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
package com.pivotal.gemfirexd.diag;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;
import java.util.SortedSet;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import junit.framework.AssertionFailedError;
import org.apache.log4j.Logger;

@SuppressWarnings("serial")
public class GfxdDiagsDUnit extends DistributedSQLTestBase {

  public GfxdDiagsDUnit(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = true;
  }

  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
    DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = false;
  }

  /**
   * Test for a single member in SYS.MEMBERS virtual table.
   */
  public void testSingleMember() throws Exception {
    // test with a single server
    startClientVMs(1, 0, null);

    Statement stmt = TestUtil.getStatement();
    ResultSet rs = stmt.executeQuery("select * from SYS.MEMBERS");
    checkSingleMemberResult(rs);

    PreparedStatement pstmt = TestUtil.getPreparedStatement("select * from "
        + "SYS.MEMBERS order by KIND");
    rs = pstmt.executeQuery();
    checkSingleMemberResult(rs);

    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client", true, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client", false, false);

    // check the ID of this VM using DSID() builtin
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS where ID = DSID() AND ISELDER = 0", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only", true, false);

    // some queries with where conditions and joins
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where KIND <> 'locator(normal)'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only", true, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from"
            + " SYS.MEMBERS m, SYS.SYSTABLES t where"
            + " t.SERVERGROUPS = m.SERVERGROUPS and ISELDER = 0",
        TestUtil.getResourcesDir() + "/lib/checkDiags.xml",
        "single_client_only", false, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from"
            + " SYS.MEMBERS m, SYS.SYSTABLES t where"
            + " t.SERVERGROUPS = m.SERVERGROUPS and KIND <> 'locator(normal)'",
            TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only", false, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from"
            + " SYS.MEMBERS m, SYS.SYSTABLES t where"
            + " t.SERVERGROUPS = m.SERVERGROUPS and DSID() = ID and ISELDER=0",
            TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only", true, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from"
            + " SYS.MEMBERS m, SYS.SYSTABLES t where"
            + " t.SERVERGROUPS = m.SERVERGROUPS and KIND <> 'locator(normal)'"
            + " and ISELDER = 1", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);
  }

  /**
   * Test for a single member with server groups in SYS.MEMBERS virtual
   * table.
   */
  public void testSingleMemberWithServerGroups() throws Exception {
    Properties props = new Properties();
    props.setProperty("gemfire.roles", "role1,role2");
    props.setProperty("host-data", "true");
    startServerVMs(1, 0, " SG1, Sg2,sg3", props);
    
    serverExecute(1, checkSingleServerResultForServerGroups());    

    serverSQLExecute(1, "CREATE TABLE TESTTAB (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS ( SG2, sG1,sg3, Sg2 )");

    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_sgs", true, false);
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_sgs", false, false);

    // check the server groups of this VM using the GROUPS() builtin
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS where GROUPS() = SERVERGROUPS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_sgs", true, false);

    // some queries with where conditions and joins
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where KIND <> 'locator(normal)'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_only_sgs", true, false);
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where ISELDER = 0", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_only_sgs", false, false);
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and ISELDER = 0", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_only_sgs", false, false);
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS ="
            + " m.SERVERGROUPS and m.KIND <> 'locator(normal)'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_only_sgs", false, false);
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS ="
            + " m.SERVERGROUPS and m.SERVERGROUPS = GROUPS() and ISELDER=0",
            TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_server_only_sgs", true, false);
    sqlExecuteVerify(null, new int[] { 1 },
        "select KIND, ROLES, HOSTDATA, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and ISELDER = 0 and t.TABLENAME not "
            + "like 'TEST%'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);
  }

  public static SerializableRunnable checkSingleServerResultForServerGroups()
      throws Exception {
    SerializableRunnable checkSingleMemberResultForServerGroups =
        new SerializableRunnable("checkSingleMemberResultForServerGroups") {
      @Override
      public void run() throws CacheException {

        try {
          Statement stmt = TestUtil.getStatement();
          ResultSet rs = stmt.executeQuery("select * from SYS.MEMBERS");
          checkSingleServerResultForServerGroups(rs, "role1,role2",
              "SG1,SG2,SG3", true);
          PreparedStatement pstmt = TestUtil
              .getPreparedStatement("select * from " + "SYS.MEMBERS");
          rs = pstmt.executeQuery();
          checkSingleServerResultForServerGroups(rs, "role1,role2",
              "SG1,SG2,SG3", true);
        } catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }

      private void checkSingleServerResultForServerGroups(ResultSet rs,
          String roles, String sgs, boolean isServer) throws Exception {
        boolean isElder = false;
        String currKind;
        String currType;
        String currRoles;
        String currSgs;
        OrObject currLocators;
        for (int num = 1; num <= 2; ++num) {
          assertTrue("num=" + num, rs.next());

          assertTrue(!(isElder && rs.getBoolean("ISELDER")));
          assertTrue(!(isElder && (Boolean)rs.getObject("ISELDER")));
          if ((isElder = rs.getBoolean("ISELDER"))) {
            currKind = "locator(normal)";
            currType = "false";
            currRoles = "";
            currSgs = "";
            currLocators = getLocatorObj();
          }
          else {
            if (isServer) {
              currKind = "datastore(normal)";
            }
            else {
              currKind = "accessor(normal)";
            }
            currType = String.valueOf(isServer);
            currRoles = roles;
            currSgs = sgs;
            currLocators = null;
          }
          assertEquals(currKind, rs.getString("KIND"));
          assertEquals(currKind, rs.getObject("KIND"));
          assertEquals(currType, rs.getString("HOSTDATA"));
          assertEquals(currRoles, rs.getString("ROLES"));
          assertEquals(currRoles, rs.getObject("ROLES"));
          assertEquals(currSgs, rs.getString("SERVERGROUPS"));
          assertEquals(currSgs, rs.getObject("SERVERGROUPS"));
          assertEquals(currLocators, OrObject.create(rs.getString("LOCATOR")));
          assertEquals(currLocators, OrObject.create(rs.getObject("LOCATOR")));

          // just log other fields since no fixed value is known
          final Logger logger = getGlobalLogger();
          logger.info("Got ID as " + rs.getString("ID"));
          logger.info("Got ID as " + rs.getObject("ID"));
          logger.info("Got IPADDRESS as " + rs.getString("IPADDRESS"));
          logger.info("Got IPADDRESS as " + rs.getObject("IPADDRESS"));
          logger.info("Got HOST as " + rs.getString("HOST"));
          logger.info("Got HOST as " + rs.getObject("HOST"));
          logger.info("Got PID as " + rs.getObject("PID"));
          logger.info("Got PID as " + rs.getInt("PID"));
          logger.info("Got PORT as " + rs.getObject("PORT"));
          logger.info("Got PORT as " + rs.getInt("PORT"));
          logger
              .info("Got NETSERVERS as " + rs.getObject("NETSERVERS"));
          logger
              .info("Got NETSERVERS as " + rs.getString("NETSERVERS"));
          logger.info(
              "Got SYSTEMPROPS as " + rs.getObject("SYSTEMPROPS"));
          logger.info(
              "Got SYSTEMPROPS as " + rs.getString("SYSTEMPROPS"));
          logger.info(
              "Got GEMFIREPROPS as " + rs.getObject("GEMFIREPROPS"));
          logger.info(
              "Got GEMFIREPROPS as " + rs.getString("GEMFIREPROPS"));
          logger.info("Got BOOTPROPS as " + rs.getObject("BOOTPROPS"));
          logger.info("Got BOOTPROPS as " + rs.getString("BOOTPROPS"));

          // expect exception with unknown columns
          try {
            rs.getObject("VMID");
            fail("expected unknown column get to fail");
          } catch (SQLException ex) {
            if (!"S0022".equals(ex.getSQLState())) {
              throw ex;
            }
          }
          // expect exception with incorrect type conversion
          try {
            rs.getDate("PID");
            fail("expected type conversion error");
          } catch (SQLException ex) {
            if (!"22005".equals(ex.getSQLState())) {
              throw ex;
            }
          }
          try {
            rs.getTime("PORT");
            fail("expected type conversion error");
          } catch (SQLException ex) {
            if (!"22005".equals(ex.getSQLState())) {
              throw ex;
            }
          }
        }
        assertFalse(rs.next());
      }
    };
    return checkSingleMemberResultForServerGroups;
  }

  /**
   * Test for multiple members coming up and down in SYS.MEMBERS
   * virtual table.
   */
  public void testMultipleMembersChange() throws Exception {
    // start a couple of clients and three servers
    startClientVMs(2, 0, null);
    startServerVMs(3, 0, null);

    sqlExecuteVerify(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "multiple_members", true, false);
    sqlExecuteVerify(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "multiple_members", false, false);

    runQueriesAndCheckResults(3, 2, "", "");

    // stop a client and server and check again

    stopVMNums(2, -3);
    runQueriesAndCheckResults(2, 1, "", "");

    // start a network server and check from a JDBC client connection
    final int netPort = startNetworkServer(2, null, null);
    final Connection conn = TestUtil.getNetConnection(netPort, null, null);
    final ResultSet rs = conn.createStatement().executeQuery(
        "select KIND, LOCATOR, NETSERVERS from SYS.MEMBERS "
            + "order by KIND, NETSERVERS");
    // expect 1 locator, 2 datastores and 1 client VM
    int numResults = 0;
    // first one client with nothing
    assertTrue("expected 4 results but failed on " + (++numResults), rs.next());
    assertEquals("accessor(normal)", rs.getString(1));
    assertEquals(null, rs.getString(2));
    assertEquals("", rs.getString(3));
    // next a server with nothing
    assertTrue("expected 4 results but failed on " + (++numResults), rs.next());
    assertEquals("datastore(normal)", rs.getString(1));
    assertEquals(null, rs.getString(2));
    assertEquals("", rs.getString(3));
    // next a server with network server
    assertTrue("expected 4 results but failed on " + (++numResults), rs.next());
    assertEquals("datastore(normal)", rs.getString(1));
    assertEquals(null, rs.getString(2));
    assertEquals("localhost/127.0.0.1[" + netPort + ']', rs.getString(3));
    // last hydra's GemFireXD locator
    assertTrue("expected 4 results but failed on " + (++numResults), rs.next());
    assertEquals("locator(normal)", rs.getString(1));
    assertEquals(getLocatorObj(), OrObject.create(rs.getString(2)));
    assertEquals("", rs.getString(3));
    // close the result set and connection
    assertFalse("expected 4 results but got more", rs.next());
    conn.close();

    // start a server and check again

    restartVMNums(-3);
    runQueriesAndCheckResults(3, 1, "", "");

    // finally stop all but one client and one server and check again
    stopVMNums(-2, -3);
    runQueriesAndCheckResults(1, 1, "", "");
  }

  /**
   * Test for multiple members coming up and down in SYS.MEMBERS
   * virtual table having roles and server groups.
   */
  public void testMultipleMembersChangeWithServerGroups() throws Exception {
    Properties props = new Properties();
    props.setProperty("gemfire.roles", "role2, role1");

    // start a couple of clients and three servers
    startClientVMs(1, 0, " SG2, Sg3, sG1", props);
    startClientVMs(1, 0, "Sg1,SG3, Sg2 ", props);
    startServerVMs(3, 0, "sg3, Sg2,SG1 ", props);

    waitForCriterion(new WaitCriterion() {

      @Override
      public boolean done() {
        try {
          sqlExecuteVerify(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
              "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
                  + "SYS.MEMBERS", TestUtil.getResourcesDir()
                  + "/lib/checkDiags.xml", "multiple_members_sgs", true, false);
          return true;
        } catch (RMIException rmiex) {
          if (rmiex.getCause() instanceof AssertionFailedError) {
            return false;
          }
          else {
            throw rmiex;
          }
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }

      @Override
      public String description() {
        return "waiting for members to register in profiles";
      }
    }, 60000, 500, true);
    sqlExecuteVerify(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "multiple_members_sgs", false, false);

    // check the ID of this VM using DSID() builtin
    VM v = getClientVM(1);
    final SerializableCallable dsC = new SerializableCallable("Get local DSID() ") {
      
      @Override
      public Object call() throws Exception {
        Connection conn = TestUtil.getConnection();
        ResultSet r = conn.createStatement().executeQuery("values DSID()");
        assertTrue(r.next());
        return r.getString(1);
      }
    };

    String dsid;
    if (v != null) {
      dsid = (String)v.invoke(dsC);
    }
    else {
      dsid = (String)dsC.call();
    }
    assertTrue(dsid, dsid != null && dsid.length() > 0);

    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS where ID = '" + dsid + "'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only_sgs", false, false);

    // check the server groups of this VM using the GROUPS() builtin
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS where GROUPS() = SERVERGROUPS "
            + "and ID = '" + dsid + "'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only_sgs", true, false);

    final String roles = "role1,role2";
    final String sgs = "SG1,SG2,SG3";
    runQueriesAndCheckResults(3, 2, roles, sgs);

    // stop a client and server and check again

    stopVMNums(2, -3);
    runQueriesAndCheckResults(2, 1, roles, sgs);

    // start a server and check again

    restartServerVMNums(new int[] { 3 }, 0, sgs, props);
    runQueriesAndCheckResults(3, 1, roles, sgs);

    // finally stop all but one client and one server and check again
    stopVMNums(-2, -3);
    runQueriesAndCheckResults(1, 1, roles, sgs);
  }

  /**
   * Test for a single member with server groups in SYS.MEMBERS virtual
   * table and update of the SERVERGROUPS and HOSTDATA columns.
   */
  //temporarily suspending this test to avail explicit procedures.
  public void sb__testSingleMemberWithServerGroupsUpdate() throws Exception {
    Properties props = new Properties();
    props.setProperty("gemfire.roles", "role2, role1");
    startClientVMs(1, 0, " SG1,sg2, Sg3", props);

    Statement stmt = TestUtil.getStatement();
    ResultSet rs = stmt.executeQuery("select * from SYS.MEMBERS");
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3");

    PreparedStatement pstmt = TestUtil.getPreparedStatement("select * from "
        + "SYS.MEMBERS");
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3");

    // try updating the server groups
    PreparedStatement updPstmt = TestUtil.jdbcConn.prepareStatement(
        "select * from SYS.MEMBERS where KIND <> 'locator(normal)'",
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
    Statement updStmt = TestUtil.jdbcConn.createStatement(
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);

    boolean updateDone = false;
    boolean exCheckDone = false;
    while (!updateDone || !exCheckDone) {
      rs = updStmt.executeQuery("select * from SYS.MEMBERS");
      for (int numResults = 1; numResults <= 2; ++numResults) {
        assertTrue(rs.next());
        rs.updateString("SERVERGROUPS", "sg1, Sg3");
        // check that update for an admin member (non-GFXD) is disallowed
        if ("locator(normal)".equals(rs.getString("KIND"))) {
          // we might repeatedly get "locator" as the first member so check
          // for exception only one time
          if (!exCheckDone) {
            addExpectedException(new int[] { 1 }, null,
                GemFireXDRuntimeException.class);
            try {
              rs.updateRow();
              fail("expected an exception in updating admin");
            } catch (SQLException ex) {
              if (!"XJ124".equals(ex.getSQLState())) {
                throw ex;
              }
            }
            removeExpectedException(new int[] { 1 }, null,
                GemFireXDRuntimeException.class);
            exCheckDone = true;
            // cannot continue any longer after exception since ResultSet
            // will be closed and unusable
            break;
          }
        }
        else {
          rs.updateRow();
          updateDone = true;
        }
      }
    }
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG3");
    rs = updPstmt.executeQuery();
    assertTrue(rs.next());
    rs.updateObject("SERVERGROUPS", "Sg2, SG1 ");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2");

    // also try updating the HOSTDATA column
    updateDone = false;
    exCheckDone = false;
    while (!updateDone || !exCheckDone) {
      rs = updStmt.executeQuery("select * from SYS.MEMBERS");
      for (int numResults = 1; numResults <= 2; ++numResults) {
        assertTrue(rs.next());
        rs.updateBoolean("HOSTDATA", true);
        // check that update for an admin member (non-GFXD) is disallowed
        if ("locator(normal)".equals(rs.getString("KIND"))) {
          // we might repeatedly get "locator" as the first member so check
          // for exception only one time
          if (!exCheckDone) {
            addExpectedException(new int[] { 1 }, null,
                GemFireXDRuntimeException.class);
            try {
              rs.updateRow();
              fail("expected an exception in updating admin");
            } catch (SQLException ex) {
              if (!"XJ124".equals(ex.getSQLState())) {
                throw ex;
              }
            }
            removeExpectedException(new int[] { 1 }, null,
                GemFireXDRuntimeException.class);
            exCheckDone = true;
            // cannot continue any longer after exception since ResultSet
            // will be closed and unusable
            break;
          }
        }
        else {
          rs.updateRow();
          updateDone = true;
        }
      }
    }
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2", true);
    rs = updPstmt.executeQuery();
    assertTrue(rs.next());
    rs.updateObject("HOSTDATA", Boolean.FALSE);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2", false);

    // check updates using projection
    rs = updStmt.executeQuery("select KIND, HOSTDATA, SERVERGROUPS, ID from"
        + " SYS.MEMBERS where KIND <> 'locator(normal)'");
    assertTrue(rs.next());
    rs.updateString("SERVERGROUPS", "sg2, Sg3");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG2,SG3");

    // now for HOSTDATA
    rs = updStmt.executeQuery("select KIND, HOSTDATA, SERVERGROUPS, ID from"
        + " SYS.MEMBERS where ISELDER = 0");
    assertTrue(rs.next());
    rs.updateString("HOSTDATA", "true");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG2,SG3", true);
    rs = updStmt.executeQuery("select HOSTDATA, SERVERGROUPS, ID from"
        + " SYS.MEMBERS where KIND <> 'locator(normal)'");
    assertTrue(rs.next());
    rs.updateObject("HOSTDATA", Boolean.FALSE);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG2,SG3", false);

    // next try updates using an update statement which is not supported
    try {
      clientSQLExecute(1, "update SYS.MEMBERS set "
          + "SERVERGROUPS='sg2, SG1 ' where KIND <> 'locator(normal)'");
      fail("expected update statement to fail");
    } catch (SQLException ex) {
      if (!"42Y25".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // update server groups so that there is no intersect with table SGs
    rs = updStmt.executeQuery("select SERVERGROUPS, ID from"
        + " SYS.MEMBERS where ISELDER = 0");
    assertTrue(rs.next());
    rs.updateString("SERVERGROUPS", "tsg2, tSg4");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "TSG2,TSG4");

    // expect exception when trying to update a non-updatable column
    rs = updStmt.executeQuery("select ROLES, ID from SYS.MEMBERS "
        + "where ISELDER = 0");
    assertTrue(rs.next());
    rs.updateObject("ID", "newId");
    try {
      rs.updateRow();
      fail("expected an exception in updating a read-only column");
    } catch (SQLException ex) {
      if (!"42X31".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    rs = updStmt.executeQuery("select ROLES, ID from SYS.MEMBERS "
        + "where KIND <> 'locator(normal)'");
    assertTrue(rs.next());
    rs.updateObject("ROLES", "newrole");
    try {
      rs.updateRow();
      fail("expected an exception in updating a read-only column");
    } catch (SQLException ex) {
      if (!"42X31".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    clientSQLExecute(1, "CREATE TABLE TESTTAB (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS (SG1, SG3,sg2)");

    // empty intersection with TABLE due to change in server groups
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and ISELDER = 0", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and t.TABLENAME = 'TESTTAB' and "
            + "KIND <> 'locator(normal)'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", true, false);

    // check that the table has no data
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    PartitionAttributes<Object, Object> pattrs =
      new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver())
        .setLocalMaxMemory(0).create();
    expectedAttrs.setPartitionAttributes(pattrs);
    clientVerifyRegionProperties(1, null, "TESTTAB", expectedAttrs);

    // now update the server groups back and verify again
    rs = updPstmt.executeQuery();
    assertTrue(rs.next());
    rs.updateString("SERVERGROUPS", "  sg2, Sg3 , SG1 ");
    rs.updateRow();
    assertFalse(rs.next());

    // do full verification for updated roles and server groups
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_sgs", true, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_sgs", false, false);

    // some queries with where conditions and joins
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where KIND <> 'locator(normal)'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only_sgs", true, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where ISELDER = 0", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only_sgs", false, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and ISELDER = 0", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only_sgs", false, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and m.KIND <> 'locator(normal)'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "single_client_only_sgs", false, false);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and ISELDER = 0 and t.TABLENAME not like "
            + "'TEST%'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);

    // create a new table and check that is also has no data with datastore flag
    // still as false

    clientSQLExecute(1, "CREATE TABLE TESTTAB1 (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS (SG1, SG3,sg2)");

    expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    pattrs = new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver())
        .setLocalMaxMemory(0).create();
    expectedAttrs.setPartitionAttributes(pattrs);
    clientVerifyRegionProperties(1, null, "TESTTAB1", expectedAttrs);

    // set the datastore back to true and check that now it has data

    rs = updStmt.executeQuery("select KIND, HOSTDATA, ID from"
        + " SYS.MEMBERS where KIND <> 'locator(normal)'");
    assertTrue(rs.next());
    rs.updateObject("HOSTDATA", Boolean.TRUE);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3",
        true);

    clientSQLExecute(1, "CREATE TABLE TESTTAB2 (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS (SG1, SG3,sg2)");

    expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    pattrs = new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver()).create();
    expectedAttrs.setPartitionAttributes(pattrs);
    clientVerifyRegionProperties(1, null, "TESTTAB2", expectedAttrs);
  }

  /**
   * Test for multiple members coming up and down in SYS.MEMBERS virtual table
   * having roles and server groups and updating server groups and GFXD
   * client/server roles.
   */
  //temporarily suspending this test to avail explicit procedures.
  public void sb__testMultipleMembersChangeWithServerGroupsUpdate()
      throws Exception {
    Properties props = new Properties();
    props.setProperty("gemfire.roles", "role1, role2");

    // start a couple of clients and three servers
    startClientVMs(2, 0, " SG2, Sg3, sG1", props);
    startServerVMs(3, 0, "sg3, Sg2,SG1 ", props);

    sqlExecuteVerify(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "multiple_members_sgs", true, false);
    sqlExecuteVerify(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "multiple_members_sgs", false, false);

    final String roles = "role1,role2";
    String sgs = "SG1,SG2,SG3";
    runQueriesAndCheckResults(3, 2, roles, sgs);

    // update the server groups and reverse client/server roles

    PreparedStatement updPstmt = TestUtil.jdbcConn.prepareStatement(
        "select * from SYS.MEMBERS where KIND <> 'locator(normal)'",
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
    Statement updStmt = TestUtil.jdbcConn.createStatement(
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);

    ResultSet rs = updPstmt.executeQuery();
    for (int numResults = 1; numResults <= 5; ++numResults) {
      assertTrue(rs.next());
      boolean isDataStore = rs.getBoolean("HOSTDATA");
      if (isDataStore) {
        rs.updateString("SERVERGROUPS", " sg2 , SG1 ");
      }
      else {
        rs.updateString("SERVERGROUPS", "SG1,SG3");
      }
      rs.updateBoolean("HOSTDATA", !isDataStore);
      rs.updateRow();
    }
    assertFalse(rs.next());
    sgs = "SG1,SG3";
    String clientSGs = "SG1,SG2";
    runQueriesAndCheckResults(2, 3, roles, clientSGs, sgs, true);

    // stop a client and server and check again

    stopVMNums(2, -3);
    runQueriesAndCheckResults(1, 2, roles, clientSGs, sgs, true);

    // restart a server, convert it to a client and check that the new groups
    // and roles of other VMs are propagated

    joinVM(false, restartServerVMAsync(3, 0, clientSGs, props));
    // wait for the member to show up and then update its role
    DistributedMember member = getMemberForVM(this.serverVMs.get(2));
    getLogWriter().info("waiting for member [" + member.getId() + ']');
    do {
      Thread.sleep(1000);
      rs = updStmt.executeQuery("select * from SYS.MEMBERS where PID="
          + member.getProcessId());
    } while (!rs.next());
    rs.updateObject("HOSTDATA", Boolean.FALSE);
    rs.updateRow();
    assertFalse(rs.next());
    runQueriesAndCheckResults(1, 3, roles, clientSGs, sgs, true);

    // change the server groups to be the same with no intersection with table
    rs = updStmt.executeQuery("select * from SYS.MEMBERS where ISELDER = 0");
    for (int numResults = 1; numResults <= 4; ++numResults) {
      assertTrue(rs.next());
      rs.updateString("SERVERGROUPS", " tsg3 , TSG1 ");
      rs.updateRow();
    }
    assertFalse(rs.next());
    sgs = "TSG1,TSG3";
    runQueriesAndCheckResults(1, 3, roles, sgs, sgs, true);

    // change the client/server roles and server groups again
    rs = updPstmt.executeQuery();
    for (int numResults = 1; numResults <= 4; ++numResults) {
      assertTrue(rs.next());
      boolean isDataStore = rs.getBoolean("HOSTDATA");
      if (isDataStore) {
        rs.updateString("SERVERGROUPS", " sg3 , SG1 ");
      }
      else {
        rs.updateString("SERVERGROUPS", "SG1,SG2");
      }
      rs.updateString("HOSTDATA", String.valueOf(!isDataStore));
      rs.updateRow();
    }
    assertFalse(rs.next());
    sgs = "SG1,SG2";
    clientSGs = "SG1,SG3";
    runQueriesAndCheckResults(3, 1, roles, clientSGs, sgs, false);

    // restart the client and check that the new groups and roles of other VMs
    // are propagated

    restartClientVMNums(new int[] { 2 }, 0, clientSGs, props);
    member = getMemberForVM(this.clientVMs.get(1));
    getLogWriter().info("waiting for member [" + member.getId() + ']');
    do {
      Thread.sleep(1000);
      rs = updStmt.executeQuery("select * from SYS.MEMBERS where PID="
          + member.getProcessId());
    } while (!rs.next());
    runQueriesAndCheckResults(3, 2, roles, clientSGs, sgs, false);

    // change the server groups back and check again

    rs = updStmt.executeQuery("select * from SYS.MEMBERS where ISELDER = 0");
    for (int numResults = 1; numResults <= 5; ++numResults) {
      assertTrue(rs.next());
      rs.updateString("SERVERGROUPS", " sg3, sg1,SG2 ");
      rs.updateRow();
    }
    assertFalse(rs.next());
    sgs = "SG1,SG2,SG3";
    runQueriesAndCheckResults(3, 2, roles, sgs);

    // finally stop all but one VM and check again
    stopVMNums(-1, -2, -3, 2);
    runQueriesAndCheckResults(0, 1, roles, sgs);
  }

  public void testBug43219_VTITablesRouting() throws Exception {
    startVMs(1, 3);

    Properties cp = new Properties();
    cp.setProperty("log-level", getLogLevel());
    Connection conn = TestUtil.getConnection(cp);

    Statement st = conn.createStatement();

    if (GemFireXDUtils.hasTable(conn, "countries")) {
      st.execute("drop table countries");
    }

    st.execute("CREATE TABLE COUNTRIES (COUNTRY VARCHAR(26) NOT NULL, "
        + "COUNTRY_ISO_CODE CHAR(2) NOT NULL, REGION VARCHAR(26)) REPLICATE");

    ResultSet rs = st.executeQuery("select t.tablename, t.datapolicy, "
        + "m.hostdata, m.id from sys.systables t , sys.members m "
        + "where t.tablename = 'COUNTRIES'");

    ArrayList<String> dataPolicyNonEmpty = new ArrayList<String>();
    ArrayList<String> dataPolicyEmpty = new ArrayList<String>();
    while (rs.next()) {
      if (rs.getString(2).equalsIgnoreCase("REPLICATE")) {
        getLogWriter().info(
            "Got hostdata = " + rs.getString(3) + " datapolicy = "
                + rs.getString(2) + " id = " + rs.getString(4));
        assertTrue(rs.getString(3).equalsIgnoreCase("true"));
        dataPolicyNonEmpty.add(rs.getString(4));
      }
      else {
        if (rs.getString(2).equalsIgnoreCase("EMPTY")) {
          getLogWriter().info(
              "Got hostdata = " + rs.getString(3) + " datapolicy = "
                  + rs.getString(2) + " id = " + rs.getString(4));

          assertTrue(rs.getString(3).equalsIgnoreCase("false"));
          dataPolicyEmpty.add(rs.getString(4));
        }
      }
    }
    assertEquals(3, dataPolicyNonEmpty.size());
    assertEquals(1, dataPolicyEmpty.size());
  }

  private void runQueriesAndCheckResults(int numServers, int numClients,
      String roles, String sgs) throws Exception {
    runQueriesAndCheckResults(numServers, numClients, roles, sgs, sgs, false);
  }

  private void runQueriesAndCheckResults(int numServers, int numClients,
      String roles, String clientSGs, String serverSGs, boolean swapServerClient)
      throws Exception {
    Statement stmt = TestUtil.getStatement();
    ResultSet rs = stmt.executeQuery("select * from SYS.MEMBERS");
    checkMembersResultForServerGroups(false, numServers, numClients, 1, rs,
        roles, clientSGs, serverSGs);

    PreparedStatement pstmt = TestUtil.getPreparedStatement("select * from "
        + "SYS.MEMBERS");
    rs = pstmt.executeQuery();
    checkMembersResultForServerGroups(false, numServers, numClients, 1, rs,
        roles, clientSGs, serverSGs);

    final String tableSGs;
    if (clientSGs.length() > 0 || serverSGs.length() > 0) {
      tableSGs = "SG2, sG1,sg3 , Sg2";
      stmt.execute("CREATE TABLE TESTTAB (ID int not null primary key, "
          + "NAME varchar(64)) SERVER GROUPS ( " + tableSGs + " )");
    }
    else {
      tableSGs = "";
      stmt.execute("CREATE TABLE TESTTAB (ID int not null primary key, "
          + "NAME varchar(64))");
    }
    SortedSet<String> tableSGSet = SharedUtils.toSortedSet(tableSGs, false);
    SortedSet<String> clntSGSet = SharedUtils.toSortedSet(clientSGs, false);
    SortedSet<String> srvSGSet = SharedUtils.toSortedSet(serverSGs, false);
    boolean serversAreStores = true;
    if (tableSGs.length() > 0) {
      serversAreStores = GemFireXDUtils
          .setIntersect(tableSGSet, srvSGSet, null);
    }

    // check that the table has been created properly on accessor nodes and
    // datastore nodes
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    PartitionAttributes<Object, Object> pattrs =
      new PartitionAttributesFactory<Object, Object>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver())
        .setLocalMaxMemory(0).create();
    expectedAttrs.setPartitionAttributes(pattrs);
    for (int clientNum = 1; clientNum <= numClients; ++clientNum) {
      if (swapServerClient) {
        serverVerifyRegionProperties(clientNum, null, "TESTTAB", expectedAttrs);
      }
      else {
        clientVerifyRegionProperties(clientNum, null, "TESTTAB", expectedAttrs);
      }
    }
    if (serversAreStores) {
      pattrs = new PartitionAttributesFactory<Object, Object>()
          .setPartitionResolver(new GfxdPartitionByExpressionResolver())
          .create();
      expectedAttrs.setPartitionAttributes(pattrs);
    }
    for (int serverNum = 1; serverNum <= numServers; ++serverNum) {
      if (swapServerClient) {
        clientVerifyRegionProperties(serverNum, null, "TESTTAB", expectedAttrs);
      }
      else {
        serverVerifyRegionProperties(serverNum, null, "TESTTAB", expectedAttrs);
      }
    }

    // some queries with where conditions and joins
    rs = stmt.executeQuery("select KIND, HOSTDATA, ROLES, SERVERGROUPS, "
        + "ISELDER from SYS.MEMBERS where KIND <> 'locator(normal)'");
    checkMembersResultForServerGroups(true, numServers, numClients, 0, rs,
        roles, clientSGs, serverSGs);

    pstmt = TestUtil.getPreparedStatement("select KIND, HOSTDATA, ROLES, "
        + "SERVERGROUPS, ISELDER from SYS.MEMBERS where ISELDER = 0");
    rs = pstmt.executeQuery();
    checkMembersResultForServerGroups(true, numServers, numClients, 0, rs,
        roles, clientSGs, serverSGs);

    int numServersWithTable = 0;
    int numClientsWithTable = 0;
    if (GemFireXDUtils.setEquals(tableSGSet, srvSGSet)) {
      numServersWithTable = numServers;
    }
    if (GemFireXDUtils.setEquals(tableSGSet, clntSGSet)) {
      numClientsWithTable = numClients;
    }

    rs = stmt.executeQuery("select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, "
        + "ISELDER from SYS.MEMBERS m, SYS.SYSTABLES t where "
        + "groupsintersect(t.SERVERGROUPS, m.SERVERGROUPS) and "
        + "ISELDER = 0 and t.TABLENAME = 'TESTTAB'");
    checkMembersResultForServerGroups(true, numServersWithTable,
        numClientsWithTable, 0, rs, roles, clientSGs, serverSGs);

    pstmt = TestUtil.getPreparedStatement("select KIND, HOSTDATA, ROLES, "
        + "m.SERVERGROUPS, ISELDER from SYS.MEMBERS m, SYS.SYSTABLES"
        + " t where groupsintersect(t.SERVERGROUPS, m.SERVERGROUPS) and "
        + "KIND <> 'locator(normal)' and t.TABLENAME = 'TESTTAB'");
    rs = pstmt.executeQuery();
    checkMembersResultForServerGroups(true, numServersWithTable,
        numClientsWithTable, 0, rs, roles, clientSGs, serverSGs);

    rs = stmt.executeQuery("select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, "
        + "ISELDER from SYS.MEMBERS m, SYS.SYSTABLES t where "
        + "groupsintersect(t.SERVERGROUPS, m.SERVERGROUPS) "
        + "and KIND <> 'locator(normal)' and ISELDER = 1");
    assertFalse(rs.next());

    stmt.execute("drop table TESTTAB");
  }

  private void checkSingleMemberResult(ResultSet rs) throws SQLException {
    checkSingleMemberResultForServerGroups(rs, "", "");
  }

  private void checkSingleMemberResultForServerGroups(ResultSet rs,
      String roles, String sgs) throws SQLException {
    checkSingleMemberResultForServerGroups(rs, roles, sgs, false);
  }

  private void checkSingleMemberResultForServerGroups(ResultSet rs,
      String roles, String sgs, boolean isServer) throws SQLException {
    boolean isElder = false;
    String currKind;
    String currType;
    String currRoles;
    String currSgs;
    OrObject currLocators;
    for (int num = 1; num <= 2; ++num) {
      assertTrue("num=" + num, rs.next());

      assertTrue(!(isElder && rs.getBoolean("ISELDER")));
      assertTrue(!(isElder && (Boolean)rs.getObject("ISELDER")));
      if ((isElder = rs.getBoolean("ISELDER"))) {
        currKind = "locator(normal)";
        currType = "false";
        currRoles = "";
        currSgs = "";
        currLocators = getLocatorObj();
      }
      else {
        if (isServer) {
          currKind = "datastore(normal)";
        }
        else {
          currKind = "accessor(normal)";
        }
        currType = String.valueOf(isServer);
        currRoles = roles;
        currSgs = sgs;
        currLocators = null;
      }
      assertEquals(currKind, rs.getString("KIND"));
      assertEquals(currKind, rs.getObject("KIND"));
      assertEquals(currType, rs.getString("HOSTDATA"));
      assertEquals(currRoles, rs.getString("ROLES"));
      assertEquals(currRoles, rs.getObject("ROLES"));
      assertEquals(currSgs, rs.getString("SERVERGROUPS"));
      assertEquals(currSgs, rs.getObject("SERVERGROUPS"));
      assertEquals(currLocators, OrObject.create(rs.getString("LOCATOR")));
      assertEquals(currLocators, OrObject.create(rs.getObject("LOCATOR")));

      // just log other fields since no fixed value is known
      getLogWriter().info("Got ID as " + rs.getString("ID"));
      getLogWriter().info("Got ID as " + rs.getObject("ID"));
      getLogWriter().info("Got IPADDRESS as " + rs.getString("IPADDRESS"));
      getLogWriter().info("Got IPADDRESS as " + rs.getObject("IPADDRESS"));
      getLogWriter().info("Got HOST as " + rs.getString("HOST"));
      getLogWriter().info("Got HOST as " + rs.getObject("HOST"));
      getLogWriter().info("Got PID as " + rs.getObject("PID"));
      getLogWriter().info("Got PID as " + rs.getInt("PID"));
      getLogWriter().info("Got PORT as " + rs.getObject("PORT"));
      getLogWriter().info("Got PORT as " + rs.getInt("PORT"));
      getLogWriter().info("Got NETSERVERS as " + rs.getObject("NETSERVERS"));
      getLogWriter().info("Got NETSERVERS as " + rs.getString("NETSERVERS"));
      getLogWriter().info("Got SYSTEMPROPS as " + rs.getObject("SYSTEMPROPS"));
      getLogWriter().info("Got SYSTEMPROPS as " + rs.getString("SYSTEMPROPS"));
      getLogWriter().info("Got GEMFIREPROPS as " + rs.getObject("GEMFIREPROPS"));
      getLogWriter().info("Got GEMFIREPROPS as " + rs.getString("GEMFIREPROPS"));
      getLogWriter().info("Got BOOTPROPS as " + rs.getObject("BOOTPROPS"));
      getLogWriter().info("Got BOOTPROPS as " + rs.getString("BOOTPROPS"));

      // expect exception with unknown columns
      try {
        rs.getObject("VMID");
        fail("expected unknown column get to fail");
      } catch (SQLException ex) {
        if (!"S0022".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      // expect exception with incorrect type conversion
      try {
        rs.getDate("PID");
        fail("expected type conversion error");
      } catch (SQLException ex) {
        if (!"22005".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      try {
        rs.getTime("PORT");
        fail("expected type conversion error");
      } catch (SQLException ex) {
        if (!"22005".equals(ex.getSQLState())) {
          throw ex;
        }
      }
    }
    assertFalse(rs.next());
  }

  private static OrObject getLocatorObj() {
    // extract port separately
    return OrObject.create(getDUnitLocatorString(),
        "127.0.0.1[" + getDUnitLocatorPort() + ']');
  }

  private void checkMembersResultForServerGroups(boolean project,
      int numServers, int numClients, int numAdmins, ResultSet rs,
      String roles, String clientSGs, String serverSGs) throws SQLException {
    boolean isElder = false;
    String currKind = null;
    String currType;
    String currRoles;
    String currServerGroups;
    int totalNum = (numServers + numClients + numAdmins);
    while (totalNum-- >= 1) {
      assertTrue(
          "failed with totalNum=" + totalNum + " numServers=" + numServers
              + " numClients=" + numClients + " numAdmin=" + numAdmins, rs
              .next());

      assertTrue(numServers >= 0 && numClients >= 0 && numAdmins >= 0);
      assertTrue(!(isElder && rs.getBoolean("ISELDER")));
      assertTrue(!(isElder && (Boolean)rs.getObject("ISELDER")));
      isElder = rs.getBoolean("ISELDER");
      currType = rs.getString("HOSTDATA");
      currRoles = "";
      currServerGroups = "";
      if (currType == null || isElder) {
        --numAdmins;
        currKind = "locator(normal)";
      }
      else if ("true".equals(currType)) {
        --numServers;
        currKind = "datastore(normal)";
        currRoles = roles;
        currServerGroups = serverSGs;
      }
      else if ("false".equals(currType)) {
        --numClients;
        currKind = "accessor(normal)";
        currRoles = roles;
        currServerGroups = clientSGs;
      }
      else {
        fail("unexpected HOSTDATA: " + currType);
      }
      assertEquals(currKind, rs.getObject("KIND"));
      assertEquals(currKind, rs.getString("KIND"));
      assertEquals(currType, rs.getString("HOSTDATA"));
      assertEquals(currRoles, rs.getString("ROLES"));
      assertEquals(currRoles, rs.getObject("ROLES"));
      assertEquals(currServerGroups, rs.getString("SERVERGROUPS"));
      assertEquals(currServerGroups, rs.getObject("SERVERGROUPS"));

      if (!project) {
        getLogWriter().info("Got ID as " + rs.getString("ID"));
        getLogWriter().info("Got ID as " + rs.getObject("ID"));
        getLogWriter().info("Got IPADDRESS as " + rs.getString("IPADDRESS"));
        getLogWriter().info("Got IPADDRESS as " + rs.getObject("IPADDRESS"));
        getLogWriter().info("Got HOST as " + rs.getString("HOST"));
        getLogWriter().info("Got HOST as " + rs.getObject("HOST"));
        getLogWriter().info("Got PID as " + rs.getObject("PID"));
        getLogWriter().info("Got PID as " + rs.getInt("PID"));
        getLogWriter().info("Got PORT as " + rs.getObject("PORT"));
        getLogWriter().info("Got PORT as " + rs.getInt("PORT"));
      }
      else {
        try {
          rs.getString("ID");
          fail("expected unexpected column get to fail");
        } catch (SQLException ex) {
          if (!"S0022".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }

      // expect exception with unknown columns
      try {
        rs.getObject("VMID");
        fail("expected unknown column get to fail");
      } catch (SQLException ex) {
        if (!"S0022".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      // expect exception with incorrect type conversion
      try {
        rs.getTime("ISELDER");
        fail("expected type conversion error");
      } catch (SQLException ex) {
        if (!"22005".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      try {
        rs.getInt("KIND");
        fail("expected type conversion error");
      } catch (SQLException ex) {
        if (!"22018".equals(ex.getSQLState())) {
          throw ex;
        }
      }
    }
    assertTrue(numServers == 0 && numClients == 0 && numAdmins == 0);
    assertFalse(rs.next());
  }

  public static final class OrObject {
    public final Object o1, o2;

    private OrObject(Object o1, Object o2) {
      this.o1 = o1;
      this.o2 = o2;
    }

    public static OrObject create(Object o1) {
      return o1 != null ? new OrObject(o1, null) : null;
    }

    public static OrObject create(Object o1, Object o2) {
      return o1 != null ? new OrObject(o1, o2) : null;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof OrObject) {
        OrObject obj = this;
        OrObject other = (OrObject)o;
        if (other.o2 == null) {
          obj = other;
          other = this;
        }
        if (obj.o2 == null) {
          return ArrayUtils.objectEquals(obj.o1, other.o1) ||
              ArrayUtils.objectEquals(obj.o1, other.o2);
        } else {
          return ArrayUtils.objectEquals(obj.o1, other.o1) ||
              ArrayUtils.objectEquals(obj.o2, other.o2);
        }
      } else {
        return false;
      }
    }

    @Override
    public String toString() {
      return o2 == null ? String.valueOf(o1)
          : String.valueOf(o1) + ',' + String.valueOf(o2);
    }
  }
}
