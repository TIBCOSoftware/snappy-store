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
package com.pivotal.gemfirexd.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.diag.SortedCSVProcedures;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;

/**
 * Tests for GemFireXD specific diagnostic virtual tables in SYS namespace.
 * 
 * @author swale
 */
public class GfxdDiagsTest extends JdbcTestBase {

  public GfxdDiagsTest(String name) {
    super(name);
  }

  /**
   * Test for a single member in SYS.MEMBERS virtual table.
   */
  public void testSingleMember() throws Exception {
    setupConnection();

    Statement stmt = getStatement();
    ResultSet rs = stmt.executeQuery("select * from SYS.MEMBERS");
    checkSingleMemberResult(rs);

    PreparedStatement pstmt = getPreparedStatement("select * from "
        + "SYS.MEMBERS");
    rs = pstmt.executeQuery();
    checkSingleMemberResult(rs);

    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "loner_member", true, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "loner_member", false, false);

    // check the ID of this VM using DSID() builtin
    sqlExecuteVerifyText("select KIND, HOSTDATA, ROLES, SERVERGROUPS, "
        + "ISELDER from SYS.MEMBERS where ID = DSID()", getResourcesDir()
        + "/lib/checkDiags.xml", "loner_member", false, false);

    // some queries with where conditions and joins
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where KIND <> 'admin'", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_member", true, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where HOSTDATA IS NOT NULL", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_member", false, false);
    sqlExecuteVerifyText(
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where "
            + "t.SERVERGROUPS = m.SERVERGROUPS and HOSTDATA IS NOT NULL ",
        TestUtil.getResourcesDir() + "/lib/checkDiags.xml",
        "loner_member", false, false);
    sqlExecuteVerifyText(
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where "
            + "t.SERVERGROUPS = m.SERVERGROUPS and ID = DSID()", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_member", true, false);
    sqlExecuteVerifyText(
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where "
            + "t.SERVERGROUPS = m.SERVERGROUPS and KIND <> 'admin'", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_member", false, false);
    sqlExecuteVerifyText(
        "select distinct KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where "
            + "t.SERVERGROUPS = m.SERVERGROUPS and KIND <> 'admin'"
            + " and HOSTDATA IS NULL", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);
  }

  /**
   * Test for a single member with server groups in SYS.MEMBERS virtual
   * table.
   */
  public void testSingleMemberWithServerGroups() throws Exception {
    Properties props = new Properties();
    props.setProperty("server-groups", " SG1,sg2, Sg3");
    props.setProperty("gemfire.roles", "role1, role2");
    setupConnection(props);

    Statement stmt = getStatement();
    ResultSet rs = stmt.executeQuery("select * from SYS.MEMBERS");
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3");

    PreparedStatement pstmt = getPreparedStatement("select * from "
        + "SYS.MEMBERS");
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3");

    sqlExecute("CREATE TABLE TESTTAB (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS (SG1, SG3,sg2)", true);

    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", true, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);

    // check the server groups of this VM using the GROUPS() builtin
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where SERVERGROUPS = GROUPS()", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", true, false);

    // some queries with where conditions and joins
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where KIND <> 'admin'", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", true, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where HOSTDATA IS NOT NULL", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and HOSTDATA IS NOT NULL", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and t.SERVERGROUPS = GROUPS()", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", true, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and m.KIND <> 'admin'", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and HOSTDATA IS NOT NULL and t.TABLENAME not like "
            + "'TEST%'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);
  }

  /**
   * Test for a single member with server groups in SYS.MEMBERS virtual
   * table and update of the SERVERGROUPS and HOSTDATA columns.
   */
  //temporarily suspending this test to avail explicit procedures.
  public void sb__testSingleMemberWithServerGroupsUpdate() throws Exception {
    Properties props = new Properties();
    props.setProperty("server-groups", " SG1,sg2, Sg3");
    props.setProperty("gemfire.roles", "role2, role1");
    setupConnection(props);

    Statement stmt = getStatement();
    ResultSet rs = stmt.executeQuery("select * from SYS.MEMBERS");
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3");

    PreparedStatement pstmt = getPreparedStatement("select * from "
        + "SYS.MEMBERS");
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3");

    // try updating the server groups
    PreparedStatement updPstmt = TestUtil.jdbcConn.prepareStatement(
        "select * from SYS.MEMBERS", ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE);
    Statement updStmt = TestUtil.jdbcConn.createStatement(
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);

    rs = updStmt.executeQuery("select * from SYS.MEMBERS");
    assertTrue(rs.next());
    rs.updateString("SERVERGROUPS", "sg1, Sg3");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG3");
    // also check using the GROUPS() builtin
    rs = stmt.executeQuery("select * from SYS.MEMBERS "
        + "where GROUPS() = 'SG1,SG3'");
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG3");
    rs = updPstmt.executeQuery();
    assertTrue(rs.next());
    rs.updateObject("SERVERGROUPS", "Sg2, SG1 ");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2");
    // also check using the GROUPS() builtin
    rs = stmt.executeQuery("select * from SYS.MEMBERS "
        + "where GROUPS() = 'SG1,SG2'");
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2");

    // also try updating the HOSTDATA column
    rs = updStmt.executeQuery("select * from SYS.MEMBERS");
    assertTrue(rs.next());
    rs.updateBoolean("HOSTDATA", false);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2", false);
    rs = updPstmt.executeQuery();
    assertTrue(rs.next());
    rs.updateObject("HOSTDATA", Boolean.TRUE);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2", true);

    // check updates using projection
    rs = updStmt.executeQuery("select KIND, HOSTDATA, SERVERGROUPS, ID from"
        + " SYS.MEMBERS");
    assertTrue(rs.next());
    rs.updateString("SERVERGROUPS", "sg2, Sg3");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG2,SG3");
    // also check using the GROUPS() builtin
    rs = stmt.executeQuery("select * from SYS.MEMBERS "
        + "where GROUPS() = 'SG2,SG3'");
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG2,SG3");

    // now for HOSTDATA
    rs = updStmt.executeQuery("select KIND, HOSTDATA, SERVERGROUPS, ID from"
        + " SYS.MEMBERS");
    assertTrue(rs.next());
    rs.updateString("HOSTDATA", "false");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG2,SG3", false);
    rs = updStmt.executeQuery("select HOSTDATA, SERVERGROUPS, ID from"
        + " SYS.MEMBERS");
    assertTrue(rs.next());
    rs.updateObject("HOSTDATA", Boolean.TRUE);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG2,SG3", true);

    // next try updates using an update statement which is not supported
    try {
      stmt.execute("update SYS.MEMBERS set SERVERGROUPS='sg2, SG1 '");
      fail("expected update statement to fail");
    } catch (SQLException ex) {
      if (!"42Y25".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // update server groups so that there is no intersect with table SGs
    rs = updStmt.executeQuery("select SERVERGROUPS, ID from"
        + " SYS.MEMBERS");
    assertTrue(rs.next());
    rs.updateString("SERVERGROUPS", "tsg2, tSg4");
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "TSG2,TSG4");
    // also check using the GROUPS() builtin
    rs = stmt.executeQuery("select * from SYS.MEMBERS "
        + "where GROUPS() = 'TSG2,TSG4'");
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "TSG2,TSG4");

    // expect exception when trying to update a non-updatable column
    rs = updStmt.executeQuery("select ROLES, ID from SYS.MEMBERS");
    assertTrue(rs.next());
    try {
      rs.updateObject("ID", "newId");
      rs.updateRow();
      fail("expected an exception in updating a read-only column");
    } catch (SQLException ex) {
      if (!"42X31".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    rs = updStmt.executeQuery("select ROLES, ID from SYS.MEMBERS");
    assertTrue(rs.next());
    try {
      rs.updateObject("ROLES", "newrole");
      rs.updateRow();
      fail("expected an exception in updating a read-only column");
    } catch (SQLException ex) {
      if (!"42X31".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    sqlExecute("CREATE TABLE TESTTAB (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS (SG1, SG3,sg2)", true);

    // empty intersection with TABLE due to change in server groups
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and t.TABLENAME = 'TESTTAB'", getResourcesDir()
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
    verifyRegionProperties(null, "TESTTAB",
        regionAttributesToXML(expectedAttrs));

    // now update the server groups back and verify again
    rs = updPstmt.executeQuery();
    assertTrue(rs.next());
    rs.updateString("SERVERGROUPS", "  sg2, Sg3 , SG1 ");
    rs.updateRow();
    assertFalse(rs.next());

    // do full verification for updated roles and server groups
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", true, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);

    // check the server groups of this VM using the GROUPS() builtin
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, SERVERGROUPS, ISELDER, ROLES from "
            + "SYS.MEMBERS where SERVERGROUPS = GROUPS()", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", true, false);

    // some queries with where conditions and joins
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where KIND <> 'admin'", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", true, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS where HOSTDATA IS NOT NULL", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and HOSTDATA IS NOT NULL", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and m.KIND <> 'admin'", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and m.SERVERGROUPS = GROUPS()", getResourcesDir()
            + "/lib/checkDiags.xml", "loner_sgs", false, false);
    sqlExecuteVerifyText(
        "select KIND, HOSTDATA, ROLES, m.SERVERGROUPS, ISELDER from "
            + "SYS.MEMBERS m, SYS.SYSTABLES t where t.SERVERGROUPS = "
            + "m.SERVERGROUPS and HOSTDATA IS NOT NULL and t.TABLENAME not like "
            + "'TEST%'", TestUtil.getResourcesDir()
            + "/lib/checkDiags.xml", "empty", false, false);

    // create a new table and check that is also has no data with datastore flag
    // as false

    rs = updPstmt.executeQuery();
    assertTrue(rs.next());
    rs.updateBoolean("HOSTDATA", false);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3",
        false);

    sqlExecute("CREATE TABLE TESTTAB1 (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS (SG1, SG3,sg2)", true);

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
    verifyRegionProperties(null, "TESTTAB1",
        regionAttributesToXML(expectedAttrs));

    // set the datastore back to true and check that now it has data

    rs = updStmt.executeQuery("select KIND, HOSTDATA, ID from"
        + " SYS.MEMBERS");
    assertTrue(rs.next());
    rs.updateObject("HOSTDATA", Boolean.TRUE);
    rs.updateRow();
    assertFalse(rs.next());
    rs = pstmt.executeQuery();
    checkSingleMemberResultForServerGroups(rs, "role1,role2", "SG1,SG2,SG3",
        true);

    sqlExecute("CREATE TABLE TESTTAB2 (ID int not null primary key, "
        + "NAME varchar(64)) SERVER GROUPS (SG1, SG3,sg2)", true);

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
    verifyRegionProperties(null, "TESTTAB2",
        regionAttributesToXML(expectedAttrs));
  }

  /** functionality tests for {@link SortedCSVProcedures} */
  public void testSortedCSVProcedures() throws SQLException {
    // simple checks with some intersection
    String csv1 = "SG1,SG2,SG4";
    String csv2 = "SG2,SG3,SG5";
    checkIntersections(csv1, csv2, "SG2");
    checkUnions(csv1, csv2, "SG1,SG2,SG3,SG4,SG5");

    // some more different cases
    csv1 = "SG1,SG3,SG4";
    csv2 = "SG1,SG2,SG4";
    checkIntersections(csv1, csv2, "SG1,SG4");
    checkUnions(csv1, csv2, "SG1,SG2,SG3,SG4");

    csv1 = "G1,G3,G4";
    csv2 = "S1,S2,S4";
    checkIntersections(csv1, csv2, "");
    checkUnions(csv1, csv2, "G1,G3,G4,S1,S2,S4");

    csv1 = "SG11,SG2,SG4";
    csv2 = "SG1,SG2,SG44";
    checkIntersections(csv1, csv2, "SG2");
    checkUnions(csv1, csv2, "SG1,SG11,SG2,SG4,SG44");

    csv1 = "SG11,SG2,SG4";
    csv2 = "SG1,SG22,SG44";
    checkIntersections(csv1, csv2, "");
    checkUnions(csv1, csv2, "SG1,SG11,SG2,SG22,SG4,SG44");

    // check for one of the groups as null
    csv1 = "SG2,SG3";
    csv2 = "";
    checkIntersections(csv1, csv2, "");
    checkUnions(csv1, csv2, "SG2,SG3");

    csv1 = "";
    csv2 = "SG1";
    checkIntersections(csv1, csv2, "");
    checkUnions(csv1, csv2, "SG1");

    csv1 = "";
    csv2 = "";
    checkIntersections(csv1, csv2, "");
    checkUnions(csv1, csv2, "");
  }

  /** more tests for {@link SortedCSVProcedures} using the SQL functions */
  public void testGroupsBuiltins() throws Exception {
    Properties props = new Properties();
    props.setProperty("server-groups", " SG1,sg2, Sg3");
    props.setProperty("gemfire.roles", "gemfirexd.unknown, role1, role2");
    DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = true;
    try {
      setupConnection(props);

      // table with intersecting server groups
      sqlExecute("create table test1 (id int primary key, addr varchar(20)) "
          + "server groups (SG4, Sg2)", false);
      sqlExecuteVerifyText("select m.servergroups from sys.systables t, "
          + "SYS.MEMBERS m where t.tablename='TEST1' and "
          + "groupsintersect(t.servergroups, m.servergroups)", null,
          "SG1,SG2,SG3", true, false);

      // now check for table with no intersection in server groups
      sqlExecute("create table test2 (id int primary key, addr varchar(20)) "
          + "server groups (SG4, Sg)", true);
      sqlExecuteVerifyText("select m.servergroups from sys.systables t, "
          + "SYS.MEMBERS m where t.tablename='TEST2' and "
          + "groupsintersect(t.servergroups, m.servergroups)", null, null,
          false, false);
    } finally {
      DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = false;
    }
  }

  private void checkIntersections(String csv1, String csv2, String expected) {
    /*
    String groupExpected = expected;
    boolean groupExpectedRes = (expected.length() > 0);
    if (csv1.length() == 0) {
      groupExpected = csv2;
      groupExpectedRes = true;
    }
    else if (csv2.length() == 0) {
      groupExpected = csv1;
      groupExpectedRes = true;
    }
    */
    assertEquals(expected, SortedCSVProcedures.intersection(csv1, csv2));
    assertEquals(expected, SortedCSVProcedures.groupsIntersection(csv1, csv2));
    if (expected.length() > 0) {
      assertTrue(SortedCSVProcedures.intersect(csv1, csv2));
    }
    else {
      assertFalse(SortedCSVProcedures.intersect(csv1, csv2));
    }
    if (expected.length() > 0 || (csv1.length() == 0 && csv2.length() == 0)) {
      assertTrue(SortedCSVProcedures.groupsIntersect(csv1, csv2));
    }
    else {
      assertFalse(SortedCSVProcedures.groupsIntersect(csv1, csv2));
    }
  }

  private void checkUnions(String csv1, String csv2, String expected) {
    String groupExpected = expected;
    if (csv1.length() == 0 || csv2.length() == 0) {
      groupExpected = "";
    }
    assertEquals(expected, SortedCSVProcedures.union(csv1, csv2));
    assertEquals(groupExpected, SortedCSVProcedures.groupsUnion(csv1, csv2));
  }

  private void checkSingleMemberResult(ResultSet rs) throws SQLException {
    checkSingleMemberResultForServerGroups(rs, "", "");
  }

  private void checkSingleMemberResultForServerGroups(ResultSet rs,
      String roles, String sgs) throws SQLException {
    checkSingleMemberResultForServerGroups(rs, roles, sgs, true);
  }

  private void checkSingleMemberResultForServerGroups(ResultSet rs,
      String roles, String sgs, boolean isServer) throws SQLException {
    assertTrue(rs.next());

    assertTrue(rs.getBoolean("ISELDER"));
    assertEquals(Boolean.TRUE, rs.getObject("ISELDER"));
    final Object mcastPort = InternalDistributedSystem.getConnectedInstance()
        .getProperties().get("mcast-port");
    final String fmt = isServer ? "datastore(%s)" : "accessor(%s)";
    if (mcastPort == null || "0".equals(mcastPort)) {
      assertEquals("loner", rs.getString("KIND"));
      assertEquals("loner", rs.getObject("KIND"));
    }
    else {
      assertEquals(String.format(fmt, "normal"), rs.getString("KIND"));
      assertEquals(String.format(fmt, "normal"), rs.getObject("KIND"));
    }
    assertEquals(isServer, rs.getBoolean("HOSTDATA"));
    assertEquals(Boolean.valueOf(isServer), rs.getObject("HOSTDATA"));
    assertEquals(String.valueOf(isServer), rs.getString("HOSTDATA"));
    assertEquals(roles, rs.getString("ROLES"));
    assertEquals(roles, rs.getObject("ROLES"));
    assertEquals(sgs, rs.getString("SERVERGROUPS"));
    assertEquals(sgs, rs.getObject("SERVERGROUPS"));

    // just log other fields since no fixed value is known
    getLogger().info("Got ID as " + rs.getString("ID"));
    getLogger().info("Got ID as " + rs.getObject("ID"));
    getLogger().info("Got IPADDRESS as " + rs.getString("IPADDRESS"));
    getLogger().info("Got IPADDRESS as " + rs.getObject("IPADDRESS"));
    getLogger().info("Got HOST as " + rs.getString("HOST"));
    getLogger().info("Got HOST as " + rs.getObject("HOST"));
    getLogger().info("Got PID as " + rs.getObject("PID"));
    getLogger().info("Got PID as " + rs.getInt("PID"));
    getLogger().info("Got PORT as " + rs.getObject("PORT"));
    getLogger().info("Got PORT as " + rs.getInt("PORT"));

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
      rs.getTime("PID");
      fail("expected type conversion error");
    } catch (SQLException ex) {
      if (!"22005".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      rs.getDate("PORT");
      fail("expected type conversion error");
    } catch (SQLException ex) {
      if (!"22005".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    assertFalse(rs.next());
  }
}
