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

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.auth.callback.UserAuthenticator;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.PermissionsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TablePermsDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ScanQualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.Orderable;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.LDAPAuthenticationSchemeImpl;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.DataDictionaryImpl;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSROUTINEPERMSRowFactory;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSTABLEPERMSRowFactory;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.TabInfoImpl;
import com.pivotal.gemfirexd.internal.impl.sql.execute.GranteeIterator;
import com.pivotal.gemfirexd.jdbc.JUnit4TestBase;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class LdapGroupAuthTest extends JUnit4TestBase {

  private final static String sysUser = "gemfire10";

  private static int netPort;

  private static final Connection[] conns = new Connection[10];

  private static final Statement[] stmts = new Statement[10];

  @BeforeClass
  public static void startServer() throws Exception {
    Properties bootProperties = SecurityTestUtils
        .startLdapServerAndGetBootProperties(0, 0, sysUser);
    TestUtil.setupConnection(bootProperties, LdapGroupAuthTest.class);
    netPort = TestUtil.startNetserverAndReturnPort();

    TestUtil.currentUserName = sysUser;
    TestUtil.currentUserPassword = sysUser;
    TestUtil.bootUserName = sysUser;
    TestUtil.bootUserPassword = sysUser;
  }

  @AfterClass
  public static void classTearDown() throws Exception {
    SQLException failure = closeStatements(conns, stmts);
    JUnit4TestBase.classTearDown();
    netPort = 0;
    final LdapTestServer server = LdapTestServer.getInstance();
    if (server.isServerStarted()) {
      server.stopService();
    }
    if (failure != null) {
      throw failure;
    }
    // delete persistent DataDictionary files
    TestUtil.deleteDir(new File("datadictionary"));
    TestUtil.deleteDir(new File("globalIndex"));
  }

  // gemGroup1: gemfire1, gemfire2, gemfire3
  // gemGroup2: gemfire3, gemfire4, gemfire5
  // gemGroup3: gemfire6, gemfire7, gemfire8
  // gemGroup4: gemfire1, gemfire3, gemfire9
  // gemGroup5: gemfire7, gemfire4, gemGroup3
  // gemGroup6: gemfire2, gemGroup4, gemfire6

  /**
   * Basic test for the internal method that retrieves LDAP group members as
   * also recursive group retrieval. The loaded sample "auth.ldif" has one group
   * (gemGroup5) that refers to another (gemGroup3).
   */
  @Test
  public void ldapGroupMembers() throws Exception {
    Set<String> expectedGroup1Members = new HashSet<String>(
        Arrays.asList(new String[] { "GEMFIRE1", "GEMFIRE2", "GEMFIRE3" }));
    Set<String> expectedGroup2Members = new HashSet<String>(
        Arrays.asList(new String[] { "GEMFIRE3", "GEMFIRE4", "GEMFIRE5" }));
    Set<String> expectedGroup3Members = new HashSet<String>(
        Arrays.asList(new String[] { "GEMFIRE6", "GEMFIRE7", "GEMFIRE8" }));
    Set<String> expectedGroup4Members = new HashSet<String>(
        Arrays.asList(new String[] { "GEMFIRE1", "GEMFIRE3", "GEMFIRE9" }));
    Set<String> expectedGroup5Members = new HashSet<String>(Arrays.asList(
        new String[] { "GEMFIRE4", "GEMFIRE6", "GEMFIRE7", "GEMFIRE8" }));
    Set<String> expectedGroup6Members = new HashSet<String>(
        Arrays.asList(new String[] { "GEMFIRE2", "GEMFIRE6", "GEMFIRE1",
            "GEMFIRE3", "GEMFIRE9" }));
    Set<String> expectedGroup7Members = Collections.emptySet();

    Set<String> group1Members = getLdapGroupMembers("gemGroup1");
    Assert.assertEquals(expectedGroup1Members, group1Members);
    Set<String> group2Members = getLdapGroupMembers("GemGroup2");
    Assert.assertEquals(expectedGroup2Members, group2Members);
    Set<String> group3Members = getLdapGroupMembers("GEMGROUP3");
    Assert.assertEquals(expectedGroup3Members, group3Members);
    Set<String> group4Members = getLdapGroupMembers("gemGroup4");
    Assert.assertEquals(expectedGroup4Members, group4Members);
    Set<String> group5Members = getLdapGroupMembers("gemGroup5");
    Assert.assertEquals(expectedGroup5Members, group5Members);
    Set<String> group6Members = getLdapGroupMembers("gemgroup6");
    Assert.assertEquals(expectedGroup6Members, group6Members);
    Set<String> group7Members = getLdapGroupMembers("gemgroup7");
    Assert.assertEquals(expectedGroup7Members, group7Members);
  }

  /**
   * Basic test for the internal method that retrieves LDAP group members as
   * also recursive group retrieval for Active Directory kind of config.
   * The loaded sample is "authAD.ldif" similar to "auth.ldif" but using
   * "givenName" instead of "uid". This does not use "sAMAccountName" like
   * in MS AD since that is not supported in the default schemas in Apache DS
   * but that difference will not matter in what is being tested here namely
   * custom user search filter. This also has an entry for escape characters (a comma)
   * in gemfire11 and corresponding group definition have the user in gemGroup8.
   */
  @Test
  public void ldapADGroupMembers() throws Exception {
    classTearDown();
    Properties bootProperties = SecurityTestUtils
        .startLdapServerAndGetBootProperties(0, 0, sysUser,
            TestUtil.getResourcesDir() + "/lib/ldap/authAD.ldif");
    bootProperties.setProperty(
        com.pivotal.gemfirexd.Property.AUTH_LDAP_SEARCH_FILTER,
        "(&(objectClass=inetOrgPerson)(givenName=%USERNAME%))");
    TestUtil.setupConnection(bootProperties, LdapGroupAuthTest.class);

    Set<String> expectedGroup1Members = new HashSet<>(
        Arrays.asList("GEMFIRE1", "GEMFIRE2", "GEMFIRE3"));
    Set<String> expectedGroup2Members = new HashSet<>(
        Arrays.asList("GEMFIRE3", "GEMFIRE4", "GEMFIRE5"));
    Set<String> expectedGroup3Members = new HashSet<>(
        Arrays.asList("GEMFIRE6", "GEMFIRE7", "GEMFIRE8"));
    Set<String> expectedGroup4Members = new HashSet<>(
        Arrays.asList("GEMFIRE1", "GEMFIRE3", "GEMFIRE9"));
    Set<String> expectedGroup5Members = new HashSet<>(Arrays.asList(
        "GEMFIRE4", "GEMFIRE6", "GEMFIRE7", "GEMFIRE8"));
    Set<String> expectedGroup6Members = new HashSet<>(
        Arrays.asList("GEMFIRE2", "GEMFIRE6", "GEMFIRE1",
            "GEMFIRE3", "GEMFIRE9"));
    Set<String> expectedGroup7Members = Collections.emptySet();
    Set<String> expectedGroup8Members = new HashSet<>(
        Arrays.asList("GEMFIRE6", "GEMFIRE11"));

    Set<String> group1Members = getLdapGroupMembers("gemGroup1");
    Assert.assertEquals(expectedGroup1Members, group1Members);
    Set<String> group2Members = getLdapGroupMembers("GemGroup2");
    Assert.assertEquals(expectedGroup2Members, group2Members);
    Set<String> group3Members = getLdapGroupMembers("GEMGROUP3");
    Assert.assertEquals(expectedGroup3Members, group3Members);
    Set<String> group4Members = getLdapGroupMembers("gemGroup4");
    Assert.assertEquals(expectedGroup4Members, group4Members);
    Set<String> group5Members = getLdapGroupMembers("gemGroup5");
    Assert.assertEquals(expectedGroup5Members, group5Members);
    Set<String> group6Members = getLdapGroupMembers("gemgroup6");
    Assert.assertEquals(expectedGroup6Members, group6Members);
    Set<String> group7Members = getLdapGroupMembers("gemgroup7");
    Assert.assertEquals(expectedGroup7Members, group7Members);
    Set<String> group8Members = getLdapGroupMembers("gemgroup8");
    Assert.assertEquals(expectedGroup8Members, group8Members);

    classTearDown();
    startServer();
  }

  /**
   * Test for internal GrantIterator and DataDictionary methods that have been
   * added to traverse group permissions with and without top-level GRANT/REVOKE
   */
  @Test
  public void grantRevokeTraverseGroupPermissions() throws Exception {
    Properties props = new Properties();
    SecurityTestUtils.setUserProps("gemfire9", props);
    EmbedConnection conn = (EmbedConnection)TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();

    LanguageConnectionContext lcc = conn.getLanguageConnection();
    conn.getTR().setupContextStack();
    DataDictionaryImpl dd = (DataDictionaryImpl)lcc.getDataDictionary();
    GranteeIterator iter;
    List<String> grantees;
    TablePermsDescriptor perm;
    try {
      stmt.execute("create table test1 (id1 int primary key, v1 int)");

      lcc.beginNestedTransaction(true);
      TransactionController tc = lcc.getTransactionCompile();
      SchemaDescriptor sd = dd.getSchemaDescriptor("GEMFIRE9", tc, true);
      TableDescriptor td = dd.getTableDescriptor("TEST1", sd, tc);

      perm = new TablePermsDescriptor(dd, null, sysUser, td.getUUID(), "N",
          "N", "y", "N", "N", "N", "N");

      // checks for GRANT
      grantees = Arrays.asList("GEMFIRE1", "LDAPGROUP:GEMGROUP3");
      iter = new GranteeIterator(grantees, perm, true,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter,
          "GEMFIRE1", null,
          "LDAPGROUP:GEMGROUP3", "GEMGROUP3",
          "GEMFIRE8", "GEMGROUP3",
          "GEMFIRE7", "GEMGROUP3",
          "GEMFIRE6", "GEMGROUP3");

      grantees = Arrays.asList("GEMFIRE3", "LDAPGROUP:GEMGROUP2");
      iter = new GranteeIterator(grantees, perm, true,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter,
          "GEMFIRE3", null,
          "LDAPGROUP:GEMGROUP2", "GEMGROUP2",
          "GEMFIRE3", "GEMGROUP2",
          "GEMFIRE4", "GEMGROUP2",
          "GEMFIRE5", "GEMGROUP2");

      grantees = Arrays.asList("LDAPGROUP:GEMGROUP1", "GEMFIRE2",
          "GEMFIRE5");
      iter = new GranteeIterator(grantees, perm, true,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter,
          "LDAPGROUP:GEMGROUP1", "GEMGROUP1",
          "GEMFIRE1", "GEMGROUP1",
          "GEMFIRE2", "GEMGROUP1",
          "GEMFIRE3", "GEMGROUP1",
          "GEMFIRE2", null,
          "GEMFIRE5", null);

      grantees = Arrays.asList("GEMFIRE5", "LDAPGROUP:GEMGROUP3",
          "GEMFIRE6");
      iter = new GranteeIterator(grantees, perm, true,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter, "GEMFIRE5", null,
          "LDAPGROUP:GEMGROUP3", "GEMGROUP3",
          "GEMFIRE6", "GEMGROUP3",
          "GEMFIRE7", "GEMGROUP3",
          "GEMFIRE8", "GEMGROUP3",
          "GEMFIRE6", null);

      // check for explicit group-name prefix (for REFRESH)
      grantees = Arrays.asList("GEMGROUP2:GEMFIRE5", "GEMFIRE2",
          "LDAPGROUP:GEMGROUP3", "GEMGROUP7:GEMFIRE8", "GEMFIRE6");
      iter = new GranteeIterator(grantees, perm, true,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter, "GEMFIRE5", "GEMGROUP2",
          "GEMFIRE2", null,
          "LDAPGROUP:GEMGROUP3", "GEMGROUP3",
          "GEMFIRE6", "GEMGROUP3",
          "GEMFIRE7", "GEMGROUP3",
          "GEMFIRE8", "GEMGROUP3",
          "GEMFIRE8", "GEMGROUP7",
          "GEMFIRE6", null);

      // checks after top-level GRANT/REVOKE
      stmt.execute(
          "grant insert on table test1 to gemfire1, ldapGroup: gemGroup3");
      stmt.execute(
          "grant select on table test1 to gemfire3, ldapGroup: gemGroup2");
      stmt.execute("grant update on table test1 "
          + "to ldapGroup: gemGroup1, gemfire2, gemfire5");
      stmt.execute("grant delete on table test1 "
          + "to gemfire5, ldapGroup:gemGroup3, gemfire6");

      grantees = Arrays.asList("GEMFIRE1", "LDAPGROUP:GEMGROUP3");
      iter = new GranteeIterator(grantees, perm, false,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter,
          "GEMFIRE1", null,
          "LDAPGROUP:GEMGROUP3", "GEMGROUP3",
          "GEMFIRE8", "GEMGROUP3",
          "GEMFIRE7", "GEMGROUP3");
          // no GEMFIRE6 since it was given specific delete permission later
          // "GEMFIRE6", "GEMGROUP3"

      perm = new TablePermsDescriptor(dd, null, sysUser, td.getUUID(), "y",
          "N", "N", "N", "N", "N", "N");
      grantees = Arrays.asList("GEMFIRE3", "LDAPGROUP:GEMGROUP2");
      iter = new GranteeIterator(grantees, perm, false,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter,
          "GEMFIRE3", null,
          "LDAPGROUP:GEMGROUP2", "GEMGROUP2",
          "GEMFIRE4", "GEMGROUP2");
          // no GEMFIRE5 since it was given specific insert+delete permission
          // "GEMFIRE5", "GEMGROUP2"

      perm = new TablePermsDescriptor(dd, null, sysUser, td.getUUID(), "N",
          "N", "N", "y", "N", "N", "N");
      grantees = Arrays.asList("LDAPGROUP:GEMGROUP1", "GEMFIRE2",
          "GEMFIRE5");
      iter = new GranteeIterator(grantees, perm, false,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter,
          "LDAPGROUP:GEMGROUP1", "GEMGROUP1",
          // no GEMFIRE1 or GEMFIRE3 since they were given specific perms
          // "GEMFIRE1", "GEMGROUP1",
          // "GEMFIRE3", "GEMGROUP1",
          "GEMFIRE2", null,
          "GEMFIRE5", null);

      grantees = Arrays.asList("LDAPGROUP:GEMGROUP1");
      iter = new GranteeIterator(grantees, perm, false,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter,
          "LDAPGROUP:GEMGROUP1", "GEMGROUP1");
          // no GEMFIRE1, GEMFIRE2, GEMFIRE3 since all given specific perms
          // "GEMFIRE1", "GEMGROUP1",
          // "GEMFIRE2", "GEMGROUP1",
          // "GEMFIRE3", "GEMGROUP1"

      perm = new TablePermsDescriptor(dd, null, sysUser, td.getUUID(), "N",
          "y", "N", "N", "N", "N", "N");
      grantees = Arrays.asList("GEMFIRE5", "LDAPGROUP:GEMGROUP3",
          "GEMFIRE6");
      iter = new GranteeIterator(grantees, perm, false,
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.TABLEID_INDEX_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, dd, tc);
      checkGrantees(iter, "GEMFIRE5", null,
          "LDAPGROUP:GEMGROUP3", "GEMGROUP3",
          "GEMFIRE7", "GEMGROUP3",
          "GEMFIRE8", "GEMGROUP3",
          "GEMFIRE6", null);

      ArrayList<PermissionsDescriptor> outDescriptors = new ArrayList<>();
      // check the dd.getAllLDAPDescriptorsHavingPermissions API
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP1",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          // all members of GEMGROUP1 were given specific permissions
          new String[] { "LDAPGROUP:GEMGROUP1", "GEMGROUP1",
              "N", "N", "N", "N", "N", "N", "y" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP2",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          // other members of GEMGROUP2 were given specific permissions
          new String[] { "GEMFIRE4", "GEMGROUP2", "N", "N", "N", "N", "y",
              "N", "N" },
          new String[] { "LDAPGROUP:GEMGROUP2", "GEMGROUP2", "N", "N", "N",
              "N", "y", "N", "N" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP3",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          // GEMFIRE6 was given specific permissions
          new String[] { "GEMFIRE7", "GEMGROUP3", "N", "y", "y", "N", "N",
              "N", "N" },
          new String[] { "GEMFIRE8", "GEMGROUP3", "N", "y", "y", "N", "N",
              "N", "N" },
          new String[] { "LDAPGROUP:GEMGROUP3", "GEMGROUP3", "N", "y", "y",
              "N", "N", "N", "N" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP4",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP5",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);

      // do actual (partial) revoke and check
      stmt.execute("revoke insert on table test1 "
          + "from ldapGroup: gemGroup3, ldapGroup: gemGroup1");
      stmt.execute("revoke select on table test1 from gemfire3, gemfire4");
      stmt.execute("revoke update on table test1 "
          + "from ldapGroup: gemGroup1, gemfire5");
      stmt.execute("revoke delete on table test1 "
          + "from gemfire6, ldapGroup:gemGroup3, gemfire5");

      // heap scan with additional qualifier for tableID
      outDescriptors.clear();
      TabInfoImpl ti = dd.getNonCoreTI(
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM);
      ScanQualifier[][] qualifier = dd.getExecutionFactory()
          .getScanQualifier(1);
      qualifier[0][0].setQualifier(
          SYSTABLEPERMSRowFactory.TABLEID_COL_NUM - 1,
          null, // No column name
          new SQLChar(td.getUUID().toString()), Orderable.ORDER_OP_EQUALS,
          false, false, false);
      dd.getDescriptorViaHeap(qualifier, ti, null, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "GEMFIRE1", null, "N", "N", "y", "N", "N", "N", "y" },
          new String[] { "GEMFIRE2", null, "N", "N", "N", "N", "N", "N", "y" },
          new String[] { "GEMFIRE3", null, "N", "N", "N", "N", "N", "N", "y" },
          new String[] { "GEMFIRE5", null, "N", "N", "N", "N", "y", "N", "N" },
          new String[] { "GEMFIRE6", null, "N", "N", "y", "N", "N", "N", "N" },
          new String[] { "LDAPGROUP:GEMGROUP2", "GEMGROUP2",
              "N", "N", "N", "N", "y", "N", "N" });

      // check again using dd.getAllLDAPDescriptorsHavingPermissions
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP1",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP2",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "LDAPGROUP:GEMGROUP2", "GEMGROUP2", "N", "N", "N",
              "N", "y", "N", "N" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP3",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP4",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP5",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);

    } finally {
      stmt.execute("drop table test1");
      stmt.close();

      lcc.commitNestedTransaction();
      conn.getTR().restoreContextStack();
      conn.close();
    }
  }

  /**
   * Test for REFRESH_LDAP_GROUP API.
   */
  @Test
  public void refreshLdapGroup() throws Exception {
    Properties props = new Properties();
    // grant permission to gemfire9 to execute REFRESH_LDAP_GROUP
    SecurityTestUtils.setUserProps(sysUser, props);
    Connection sysConn = TestUtil.getConnection(props);
    Statement sysStmt = sysConn.createStatement();

    SecurityTestUtils.setUserProps("gemfire9", props);
    EmbedConnection conn = (EmbedConnection)TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();

    try {
      stmt.execute("call SYS.REFRESH_LDAP_GROUP('gemGroup5')");
      // Assert.fail("SYS.REFRESH_LDAP_GROUP did not fail from normal user");
    } catch (SQLException sqle) {
      if (!"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    sysStmt.execute(
        "grant execute on PROCEDURE SYS.REFRESH_LDAP_GROUP to gemfire9");

    LanguageConnectionContext lcc = conn.getLanguageConnection();
    conn.getTR().setupContextStack();
    DataDictionaryImpl dd = (DataDictionaryImpl)lcc.getDataDictionary();
    try {
      stmt.execute("create table test1 (id1 int primary key, v1 int)");

      lcc.beginNestedTransaction(true);
      TransactionController tc = lcc.getTransactionCompile();

      ArrayList<PermissionsDescriptor> outDescriptors = new ArrayList<>();
      // check the dd.getAllLDAPDescriptorsHavingPermissions API
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP3",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP5",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP3",
          DataDictionaryImpl.SYSROUTINEPERMS_CATALOG_NUM,
          SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP5",
          DataDictionaryImpl.SYSROUTINEPERMS_CATALOG_NUM,
          SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
          DataDictionaryImpl.SYSROUTINEPERMS_CATALOG_NUM,
          SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors);

      // checks after GRANT
      stmt.execute(
          "grant insert on table test1 to ldapGroup: gemGroup3, gemfire1");
      stmt.execute(
          "grant select on table test1 to gemfire3, ldapGroup: gemGroup5");
      stmt.execute("grant delete on table test1 to ldapGroup: gemGroup3");
      stmt.execute(
          "grant update on table test1 to ldapGroup: gemGroup7, gemfire3");
      sysStmt.execute(
          "grant execute on procedure SYS.DROP_USER to ldapGroup: gemGroup7");
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP3",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "GEMFIRE6", "GEMGROUP3", "N", "y", "y", "N", "y",
              "N", "N" },
          new String[] { "GEMFIRE7", "GEMGROUP3", "N", "y", "y", "N", "y",
              "N", "N" },
          new String[] { "GEMFIRE8", "GEMGROUP3", "N", "y", "y", "N", "y",
              "N", "N" },
          new String[] { "LDAPGROUP:GEMGROUP3", "GEMGROUP3", "N", "y", "y",
              "N", "N", "N", "N" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP5",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "GEMFIRE4", "GEMGROUP5", "N", "N", "N", "N", "y",
              "N", "N" },
          new String[] { "LDAPGROUP:GEMGROUP5", "GEMGROUP5", "N", "N", "N",
              "N", "y", "N", "N" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "LDAPGROUP:GEMGROUP7", "GEMGROUP7", "N", "N", "N",
              "N", "N", "N", "y" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
          DataDictionaryImpl.SYSROUTINEPERMS_CATALOG_NUM,
          SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkRoutinePermissions(outDescriptors,
          new String[] { "LDAPGROUP:GEMGROUP7", "GEMGROUP7" });

      // refresh with no changes should retain as before
      stmt.execute("call SYS.REFRESH_LDAP_GROUP('gemGroup3')");
      stmt.execute("call SYS.REFRESH_LDAP_GROUP('gemGroup7')");

      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP3",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "GEMFIRE6", "GEMGROUP3", "N", "y", "y", "N", "y",
              "N", "N" },
          new String[] { "GEMFIRE7", "GEMGROUP3", "N", "y", "y", "N", "y",
              "N", "N" },
          new String[] { "GEMFIRE8", "GEMGROUP3", "N", "y", "y", "N", "y",
              "N", "N" },
          new String[] { "LDAPGROUP:GEMGROUP3", "GEMGROUP3", "N", "y", "y",
              "N", "N", "N", "N" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP5",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "GEMFIRE4", "GEMGROUP5", "N", "N", "N", "N", "y",
              "N", "N" },
          new String[] { "LDAPGROUP:GEMGROUP5", "GEMGROUP5", "N", "N", "N",
              "N", "y", "N", "N" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
          DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
          SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkTablePermissions(outDescriptors,
          new String[] { "LDAPGROUP:GEMGROUP7", "GEMGROUP7", "N", "N", "N",
              "N", "N", "N", "y" });
      outDescriptors.clear();
      dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
          DataDictionaryImpl.SYSROUTINEPERMS_CATALOG_NUM,
          SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
      checkRoutinePermissions(outDescriptors,
          new String[] { "LDAPGROUP:GEMGROUP7", "GEMGROUP7" });

      // update gemGroup3, gemGroup7 and check again
      LdapTestServer server = LdapTestServer.getInstance();
      server.removeAttribute(
          "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
          "uniqueMember",
          "uid=gemfire8,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
          "uniqueMember",
          "uid=gemfire5,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com", "member",
          "uid=gemfire3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com", "member",
          "cn=gemGroup1,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com", "member",
          "uid=gemfire5,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      try {

        Set<String> expectedGroup3Members = new HashSet<String>(Arrays.asList(
            new String[] { "GEMFIRE5", "GEMFIRE6", "GEMFIRE7" }));
        Set<String> group3Members = getLdapGroupMembers("gemgroup3");
        Assert.assertEquals(expectedGroup3Members, group3Members);
        Set<String> expectedGroup7Members = new HashSet<String>(Arrays.asList(
            new String[] { "GEMFIRE3", "GEMFIRE1", "GEMFIRE2", "GEMFIRE5" }));
        Set<String> group7Members = getLdapGroupMembers("gemgroup7");
        Assert.assertEquals(expectedGroup7Members, group7Members);

        // now refresh with above changes to gemGroup3, gemGroup7
        stmt.execute("call SYS.REFRESH_LDAP_GROUP('gemGroup3')");
        sysStmt.execute("call SYS.REFRESH_LDAP_GROUP('gemGroup7')");

        outDescriptors.clear();
        dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP3",
            DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
            SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
        checkTablePermissions(outDescriptors,
            new String[] { "GEMFIRE6", "GEMGROUP3", "N", "y", "y", "N", "y",
                "N", "N" },
            new String[] { "GEMFIRE7", "GEMGROUP3", "N", "y", "y", "N", "y",
                "N", "N" },
            // INSERT/DELETE for newly added user and UPDATE from gemGroup7
            new String[] { "GEMFIRE5", "GEMGROUP3", "N", "y", "y", "N", "N",
                "N", "y" },
            // only SELECT permission from gemGroup5 should remain
            new String[] { "GEMFIRE8", "GEMGROUP3", "N", "N", "N", "N", "y",
                "N", "N" },
            new String[] { "LDAPGROUP:GEMGROUP3", "GEMGROUP3", "N", "y", "y",
                "N", "N", "N", "N" });
        outDescriptors.clear();
        dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP5",
            DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
            SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
        checkTablePermissions(outDescriptors,
            new String[] { "GEMFIRE4", "GEMGROUP5", "N", "N", "N", "N", "y",
                "N", "N" },
            new String[] { "LDAPGROUP:GEMGROUP5", "GEMGROUP5", "N", "N", "N",
                "N", "y", "N", "N" });
        outDescriptors.clear();
        dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
            DataDictionaryImpl.SYSTABLEPERMS_CATALOG_NUM,
            SYSTABLEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
        checkTablePermissions(outDescriptors,
            // GEMFIRE1, GEMFIRE3 have been given specific access
            new String[] { "GEMFIRE2", "GEMGROUP7", "N", "N", "N", "N", "N",
                "N", "y" },
            // GEMFIRE5 got clubbed into GEMFIRE3 above
            new String[] { "LDAPGROUP:GEMGROUP7", "GEMGROUP7", "N", "N", "N",
                "N", "N", "N", "y" });
        outDescriptors.clear();
        dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
            DataDictionaryImpl.SYSROUTINEPERMS_CATALOG_NUM,
            SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
        checkRoutinePermissions(outDescriptors,
            new String[] { "GEMFIRE1", "GEMGROUP7" },
            new String[] { "GEMFIRE2", "GEMGROUP7" },
            new String[] { "GEMFIRE3", "GEMGROUP7" },
            new String[] { "GEMFIRE5", "GEMGROUP7" },
            new String[] { "LDAPGROUP:GEMGROUP7", "GEMGROUP7" });

        sysStmt.execute("revoke execute on procedure SYS.DROP_USER "
            + "from ldapGroup: gemGroup7 restrict");
        outDescriptors.clear();
        dd.getAllLDAPDescriptorsHavingPermissions("GEMGROUP7",
            DataDictionaryImpl.SYSROUTINEPERMS_CATALOG_NUM,
            SYSROUTINEPERMSRowFactory.LDAP_GROUP_NAME, tc, outDescriptors);
        checkRoutinePermissions(outDescriptors);

      } finally {
        server.addAttribute(
            "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "uniqueMember",
            "uid=gemfire8,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "uniqueMember",
            "uid=gemfire5,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "member",
            "uid=gemfire3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "member",
            "cn=gemGroup1,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "member",
            "uid=gemfire5,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      }

    } finally {
      sysStmt.execute("revoke execute on PROCEDURE SYS.REFRESH_LDAP_GROUP "
          + "from gemfire9 restrict");
      sysStmt.execute("revoke execute on PROCEDURE SYS.REFRESH_LDAP_GROUP "
          + "from gemfire9 restrict");
      sysStmt.execute("revoke execute on procedure SYS.DROP_USER "
          + "from ldapGroup: gemGroup7 restrict");
      sysStmt.execute("drop table gemfire9.test1");
      sysStmt.close();
      sysConn.close();

      stmt.close();
      conn.getTR().restoreContextStack();
      conn.close();
    }
  }

  /**
   * Test having different combinations of GRANT/REVOKE for LDAP group members
   * and normal members.
   */
  @Test
  public void groupAuthAddRemove() throws Exception {
    checkGroupAuthAddRemove(netPort, conns, stmts);
  }

  /**
   * Test having different combinations with LDAP group refresh procedure.
   */
  @Test
  public void groupAuthRefresh() throws Exception {
    checkGroupAuthRefresh(netPort, conns, stmts);
  }

  public static void createStatements(int numStmts, int netPort,
      Connection[] conns, Statement[] stmts) throws SQLException {
    Properties props = new Properties();
    for (int i = 0; i < numStmts; i++) {
      SecurityTestUtils.setUserProps("gemfire" + (i + 1), props);
      if (stmts[i] != null) {
        try {
          stmts[i].close();
        } catch (SQLException sqle) {
          // ignore at this point
        }
      }
      if (conns[i] != null) {
        try {
          conns[i].rollback();
        } catch (SQLException sqle) {
          // ignore at this point
        }
        try {
          conns[i].close();
        } catch (SQLException sqle) {
          // ignore at this point
        }
      }
      conns[i] = TestUtil.getNetConnection(netPort, null, props);
      stmts[i] = conns[i].createStatement();
    }
  }

  public static SQLException closeStatements(Connection[] conns,
      Statement[] stmts) throws SQLException {
    SQLException failure = null;

    for (int i = 0; i < stmts.length; i++) {
      Statement stmt = stmts[i];
      if (stmt != null) {
        try {
          stmt.getConnection().commit();
          stmt.close();
        } catch (SQLException sqle) {
          failure = sqle;
        }
        stmts[i] = null;
      }
    }
    for (int i = 0; i < conns.length; i++) {
      Connection conn = conns[i];
      if (conn != null) {
        try {
          conn.rollback();
          conn.close();
        } catch (SQLException sqle) {
          failure = sqle;
        }
        conns[i] = null;
      }
    }
    return failure;
  }

  private Set<String> getLdapGroupMembers(String ldapGroup) throws Exception {
    final LDAPAuthenticationSchemeImpl ldapAuth;
    UserAuthenticator auth = ((AuthenticationServiceBase)Misc
        .getMemStoreBooting().getDatabase().getAuthenticationService())
            .getAuthenticationScheme();
    if (auth instanceof LDAPAuthenticationSchemeImpl) {
      ldapAuth = (LDAPAuthenticationSchemeImpl)auth;
    } else {
      throw new javax.naming.NameNotFoundException(
          "Require LDAP authentication scheme for "
              + "LDAP group support but is " + auth);
    }
    return ldapAuth.getLDAPGroupMembers(ldapGroup);
  }

  private void checkGrantees(GranteeIterator granteeIter,
      String... expectedGrantees) {
    LinkedList<String> expected = new LinkedList<String>(
        Arrays.asList(expectedGrantees));
    while (granteeIter.hasNext()) {
      String grantee = granteeIter.next();
      String group = granteeIter.getCurrentLdapGroup();
      ListIterator<String> expectedIter = expected.listIterator();
      while (expectedIter.hasNext()) {
        String expectedGrantee = expectedIter.next();
        String expectedGroup = expectedIter.next();
        if (grantee.equals(expectedGrantee) && ArrayUtils.objectEquals(
            granteeIter.getCurrentLdapGroup(), expectedGroup)) {
          // indicates match found
          grantee = null;
          expectedIter.previous();
          expectedIter.previous();
          expectedIter.remove();
          expectedIter.next();
          expectedIter.remove();
          break;
        }
      }
      if (grantee != null) {
        Assert.fail("Failed to find grantee=" + grantee + " group=" + group
            + " among expected: " + expected);
      }
    }
    if (!expected.isEmpty()) {
      Assert.fail("No match found for expected elements: " + expected);
    }
  }

  private void checkTablePermissions(List<PermissionsDescriptor> descs,
      String[]... expectedGrantees) {
    if (descs.size() != expectedGrantees.length) {
      Assert.fail("Expected " + expectedGrantees.length + " descriptors "
          + Arrays.deepToString(expectedGrantees) + ", got: " + descs);
    }
    HashMap<String, String[]> granteePerms = new HashMap<>(
        expectedGrantees.length);
    for (String[] e : expectedGrantees) {
      granteePerms.put(e[0], e);
    }
    for (PermissionsDescriptor perm : descs) {
      TablePermsDescriptor desc = (TablePermsDescriptor)perm;
      String[] perms = granteePerms.get(desc.getGrantee());
      if (perms[1] == null) {
        Assert.assertNull(desc.getLdapGroup());
      } else {
        Assert.assertEquals(perms[1], desc.getLdapGroup());
      }
      Assert.assertEquals(perms[2], desc.getAlterPriv());
      Assert.assertEquals(perms[3], desc.getDeletePriv());
      Assert.assertEquals(perms[4], desc.getInsertPriv());
      Assert.assertEquals(perms[5], desc.getReferencesPriv());
      Assert.assertEquals(perms[6], desc.getSelectPriv());
      Assert.assertEquals(perms[7], desc.getTriggerPriv());
      Assert.assertEquals(perms[8], desc.getUpdatePriv());
    }
  }

  private void checkRoutinePermissions(List<PermissionsDescriptor> descs,
      String[]... expectedGrantees) {
    if (descs.size() != expectedGrantees.length) {
      Assert.fail("Expected " + expectedGrantees.length + " descriptors "
          + Arrays.deepToString(expectedGrantees) + ", got: " + descs);
    }
    HashMap<String, String[]> granteePerms = new HashMap<>(
        expectedGrantees.length);
    for (String[] e : expectedGrantees) {
      granteePerms.put(e[0], e);
    }
    for (PermissionsDescriptor desc : descs) {
      String[] perms = granteePerms.get(desc.getGrantee());
      if (perms[1] == null) {
        Assert.assertNull(desc.getLdapGroup());
      } else {
        Assert.assertEquals(perms[1], desc.getLdapGroup());
      }
    }
  }

  public static void checkAuthzFailure(String sql, Statement stmt)
      throws SQLException {
    try {
      stmt.execute(sql);
      Assert.fail("expected to fail with authorization exception");
    } catch (SQLException sqle) {
      if (!"42500".equals(sqle.getSQLState())
          && !"42502".equals(sqle.getSQLState())
          && !"42504".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  /**
   * Test having different combinations of GRANT/REVOKE for LDAP group members
   * and normal members.
   */
  public static void checkGroupAuthAddRemove(int netPort, Connection[] conns,
      Statement[] stmts) throws Exception {
    // should fail without credentials
    try {
      DriverManager.getConnection(TestUtil.getNetProtocol(
          "localhost", netPort));
      Assert.fail("expected connection failure with no credentials");
    } catch (SQLException sqle) {
      if (!"08004".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    Properties props = new Properties();
    // grant permission to gemfire9 to execute REFRESH_LDAP_GROUP
    SecurityTestUtils.setUserProps(sysUser, props);
    Connection sysConn = TestUtil.getConnection(props);
    Statement sysStmt = sysConn.createStatement();

    // connect with proper credentials
    createStatements(8, netPort, conns, stmts);

    // check auth failure on a table created by another user

    Statement ostmt = stmts[6];
    ostmt
        .execute("create table gemfire7.test1 (id1 int primary key, v1 int)");
    ostmt.execute("create table gemfire7.test2 (id2 int, v2 varchar(10))");
    ostmt.execute("insert into gemfire7.test2 values (5, 'v5')");

    checkAuthzFailure("insert into gemfire7.test1 values (1, 1), (2, 2)",
        stmts[0]);
    checkAuthzFailure("update gemfire7.test2 set v2='v5_1' where id2=5",
        stmts[1]);
    checkAuthzFailure("delete from gemfire7.test2", stmts[2]);

    // now check after having granted permissions to some users+groups

    // try grant on a non-existent group
    try {
      ostmt.execute("grant insert on table gemfire7.test1 "
          + "to gemfire1, ldapGroup: nogroup");
      Assert.fail("expected failure when granting on a non-existent group");
    } catch (SQLException sqle) {
      if (!"4251B".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // access should still be absent for all users
    checkAuthzFailure("delete from gemfire7.test2 where id2=5", stmts[0]);
    checkAuthzFailure("update gemfire7.test2 set v2='v5_1' where id2=5",
        stmts[2]);

    ostmt.execute("grant insert on table gemfire7.test1 "
        + "to gemfire1, ldapGroup: gemGroup2");
    ostmt.execute("grant insert on table gemfire7.test2 "
        + "to ldapGroup : gemGroup1, gemfire4");
    // check insert permission for all granted users but failure in other ops
    stmts[0].execute("insert into gemfire7.test1 values (1, 1), (2, 2)");
    stmts[2]
        .execute("insert into gemfire7.test2 values (1, 'v1'), (2, 'v2')");
    try {
      stmts[3].execute("insert into gemfire7.test1 values (1, 1), (2, 2)");
      Assert.fail("expected primary key constraint violation");
    } catch (SQLException sqle) {
      if (!"23505".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmts[3]
        .execute("insert into gemfire7.test2 values (2, 'v2'), (3, 'v3')");
    stmts[4].execute("insert into gemfire7.test1 values (3, 3), (4, 4)");
    checkAuthzFailure("update gemfire7.test1 set v1=2 where id1=1", stmts[0]);
    checkAuthzFailure("delete from gemfire7.test1", stmts[1]);
    checkAuthzFailure("update gemfire7.test2 set v2='v2_1' where v2='v2'",
        stmts[2]);
    checkAuthzFailure("alter table gemfire7.test1 add column c1 double",
        stmts[3]);
    checkAuthzFailure("alter table gemfire7.test2 add column c1 bigint",
        stmts[4]);
    checkAuthzFailure("select * from gemfire7.test1", stmts[5]);
    checkAuthzFailure("select * from gemfire7.test2 where id2 > 1", stmts[3]);
    // give alter table privilege and check again
    ostmt.execute("grant alter on gemfire7.test1 to gemfire8, "
        + "ldapGroup: gemGroup3, gemfire4");
    stmts[3].execute("alter table gemfire7.test1 add column c1 double");
    stmts[6].execute("alter table gemfire7.test2 add column c1 bigint");
    stmts[5].execute("alter table gemfire7.test1 add column c2 bigint");
    stmts[5].execute("alter table gemfire7.test1 drop column c1");
    stmts[7].execute("alter table gemfire7.test1 drop column c2");
    stmts[7].execute("alter table gemfire7.test1 add column c2 bigint");
    stmts[3].execute("alter table gemfire7.test1 add column c1 double");
    checkAuthzFailure("alter table gemfire7.test2 drop column c1", stmts[2]);
    checkAuthzFailure("alter table gemfire7.test1 drop column c1", stmts[2]);
    checkAuthzFailure("alter table gemfire7.test1 drop column c2", stmts[0]);
    checkAuthzFailure("alter table gemfire7.test1 drop column c1", stmts[1]);
    checkAuthzFailure("alter table gemfire7.test1 drop column c2", stmts[4]);
    stmts[3].execute("alter table gemfire7.test1 drop column c2");
    stmts[5].execute("alter table gemfire7.test1 drop column c1");
    checkAuthzFailure("alter table gemfire7.test2 drop column c1", stmts[3]);
    checkAuthzFailure("alter table gemfire7.test2 drop column c1", stmts[5]);
    checkAuthzFailure("alter table gemfire7.test2 drop column c1", stmts[7]);
    stmts[6].execute("alter table gemfire7.test2 drop column c1");

    // check no access after revoke
    ostmt.execute(
        "revoke insert on gemfire7.test1 from ldapGroup : gemGroup2");
    stmts[0].execute("insert into gemfire7.test1 values (5, 5), (6, 6)");
    // gemfire4 has insert permission because given specific alter permission
    // that has overwritten the LDAPGROUP
    try {
      stmts[3].execute("insert into gemfire7.test1 values (5, 5), (6, 6)");
      Assert.fail("expected primary key constraint violation");
    } catch (SQLException sqle) {
      if (!"23505".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmts[3].execute("insert into gemfire7.test1 values (8, 8), (9, 9)");
    checkAuthzFailure("update gemfire7.test1 set v1=2 where id1=2", stmts[0]);
    checkAuthzFailure("insert into gemfire7.test1 values (5, 5), (6, 6)",
        stmts[2]);
    checkAuthzFailure("insert into gemfire7.test1 values (5, 5), (6, 6)",
        stmts[4]);


    // Using DROP_USER for RoutinePerms tests. It will only remove permissions
    // for LDAP users.
    // check auth failure on a restricted routine
    checkAuthzFailure("call sys.drop_user('gemfire8')", stmts[0]);
    // now check after having granted permissions to some users+groups
    // try grant on a non-existent group
    try {
      sysStmt.execute("grant execute on procedure sys.drop_user "
          + "to gemfire7, ldapGroup: nogroup");
      Assert.fail("expected failure when granting on a non-existent group");
    } catch (SQLException sqle) {
      if (!"4251B".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // access should still be absent for all users
    checkAuthzFailure("call sys.drop_user('gemfire8')", stmts[0]);

    sysStmt.execute("grant execute on procedure sys.drop_user "
        + "to gemfire7, ldapGroup: gemGroup2");
    // check execute permission for all granted users but failure for others
    stmts[2].execute("call sys.drop_user('gemfire8')");
    stmts[3].execute("call sys.drop_user('gemfire9')");
    stmts[4].execute("call sys.drop_user('gemfire1')");
    stmts[6].execute("call sys.drop_user('gemfire2')");
    stmts[6].execute("call sys.drop_user('gemfire2')");
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[0]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[1]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[5]);
    // gemfire1 must have lost insert permissions now
    checkAuthzFailure("insert into gemfire7.test1 values (9, 9)", stmts[0]);

    // revoke routine permissions and check no access
    sysStmt.execute("revoke execute on procedure sys.drop_user "
        + "from ldapGroup: gemGroup2 restrict");
    checkAuthzFailure("call sys.drop_user('gemfire8')", stmts[2]);
    checkAuthzFailure("call sys.drop_user('gemfire9')", stmts[3]);
    checkAuthzFailure("call sys.drop_user('gemfire1')", stmts[4]);
    stmts[6].execute("call sys.drop_user('gemfire2')");
    stmts[6].execute("call sys.drop_user('gemfire8')");
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[0]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[1]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[5]);
    sysStmt.execute("grant execute on procedure sys.drop_user "
        + "to gemfire7, ldapGroup: gemGroup2");
    stmts[2].execute("call sys.drop_user('gemfire8')");
    stmts[3].execute("call sys.drop_user('gemfire9')");
    stmts[4].execute("call sys.drop_user('gemfire1')");
    stmts[6].execute("call sys.drop_user('gemfire2')");
    stmts[6].execute("call sys.drop_user('gemfire2')");
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[0]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[1]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[5]);
    sysStmt.execute("revoke execute on procedure sys.drop_user "
        + "from ldapGroup: gemGroup2, gemfire7 restrict");
    checkAuthzFailure("call sys.drop_user('gemfire8')", stmts[0]);
    checkAuthzFailure("call sys.drop_user('gemfire9')", stmts[1]);
    checkAuthzFailure("call sys.drop_user('gemfire1')", stmts[2]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[3]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[4]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[5]);
    checkAuthzFailure("call sys.drop_user('gemfire2')", stmts[6]);

    // drop tables at the end
    stmts[6].execute("drop table gemfire7.test1");
    stmts[6].execute("drop table gemfire7.test2");

    sysStmt.close();
    sysConn.close();
  }

  /**
   * Test for SYS.REFRESH_LDAP_GROUP with LDAP membership changes.
   */
  public static void checkGroupAuthRefresh(int netPort, Connection[] conns,
      Statement[] stmts) throws Exception {

    Properties props = new Properties();
    SecurityTestUtils.setUserProps(sysUser, props);
    Connection sysConn = TestUtil.getNetConnection(netPort, null, props);
    Statement sysStmt = sysConn.createStatement();

    // create some connections
    createStatements(9, netPort, conns, stmts);

    // grant permission to gemfire9 to execute REFRESH_LDAP_GROUP
    sysStmt.execute(
        "grant execute on PROCEDURE SYS.REFRESH_LDAP_GROUP to gemfire9");

    // check auth failure on a table created by another user

    Statement stmt = stmts[8];
    stmt.execute("create table test1 (id1 int primary key, v1 int)");
    stmt.execute("create table test2 (id2 int, v2 varchar(10))");

    try {
      stmt.execute("insert into gemfire9.test2 values (5, 'v5')");
      checkAuthzFailure("insert into gemfire9.test1 values (1, 1), (2, 2)",
          stmts[0]);
      checkAuthzFailure("update gemfire9.test2 set v2='v5_1' where id2=5",
          stmts[1]);
      checkAuthzFailure("delete from gemfire9.test2", stmts[2]);

      // now check after having granted permissions to some users+groups
      stmt.execute(
          "grant insert on table test1 to ldapGroup: gemGroup2, gemfire1");
      stmt.execute("grant insert on table gemfire9.test2 "
          + "to ldapGroup : gemGroup3, gemfire3");
      stmt.execute(
          "grant select on table test1 to gemfire3, ldapGroup: gemGroup5");
      stmt.execute("grant delete on table test1 to ldapGroup: gemGroup3");
      stmt.execute(
          "grant update on table test1 to ldapGroup: gemGroup7, gemfire3");
      sysStmt.execute(
          "grant execute on procedure SYS.DROP_USER to ldapGroup: gemGroup7");

      // check permissions for all granted users but failure in other ops
      stmts[0].execute("insert into gemfire9.test1 values (1, 1), (2, 2)");
      stmts[2]
          .execute("insert into gemfire9.test2 values (1, 'v1'), (2, 'v2')");
      try {
        stmts[4].execute("insert into gemfire9.test1 values (1, 1), (2, 2)");
        Assert.fail("expected primary key constraint violation");
      } catch (SQLException sqle) {
        if (!"23505".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      stmts[5]
          .execute("insert into gemfire9.test2 values (2, 'v2'), (3, 'v3')");
      stmts[6]
          .execute("insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')");
      stmts[7]
          .execute("insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')");
      stmts[4].execute("insert into gemfire9.test1 values (3, 3), (4, 4)");
      checkAuthzFailure(
          "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')", stmts[0]);
      checkAuthzFailure(
          "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')", stmts[4]);
      checkAuthzFailure("update gemfire9.test1 set v1=2 where id1=1",
          stmts[0]);
      checkAuthzFailure("update gemfire9.test1 set v1=3 where id1=2",
          stmts[1]);
      stmts[2].execute("update gemfire9.test1 set v1=4 where id1=3");
      checkAuthzFailure("update gemfire9.test1 set v1=5 where id1=4",
          stmts[3]);
      checkAuthzFailure("update gemfire9.test1 set v1=5 where id1=4",
          stmts[4]);
      checkAuthzFailure("delete from gemfire9.test1", stmts[1]);
      checkAuthzFailure("update gemfire9.test2 set v2='v2_1' where v2='v2'",
          stmts[2]);
      checkAuthzFailure("select * from gemfire9.test1", stmts[1]);
      stmts[3].executeQuery("select * from gemfire9.test1").close();
      stmts[5].executeQuery("select * from gemfire9.test1").close();
      checkAuthzFailure("select * from gemfire9.test2 where id2 > 1",
          stmts[3]);
      stmts[6].execute("delete from gemfire9.test1");

      // check DROP_USER procedure which only revokes user permissions for LDAP
      for (int i = 0; i < 9; i++) {
        checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[i]);
      }

      // update gemGroup3, gemGroup7 and check again for changed members
      LdapTestServer server = LdapTestServer.getInstance();
      server.removeAttribute(
          "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
          "uniqueMember",
          "uid=gemfire8,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
          "uniqueMember",
          "uid=gemfire5,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com", "member",
          "uid=gemfire4,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com", "member",
          "cn=gemGroup1,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      server.addAttribute(
          "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com", "member",
          "uid=gemfire6,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      try {

        // now refresh with above changes to gemGroup3, gemGroup7
        stmt.execute("call SYS.REFRESH_LDAP_GROUP('gemGroup3')");
        sysStmt.execute("call SYS.REFRESH_LDAP_GROUP('gemGroup7')");

        // check permissions for all refreshed users but failure in other ops
        stmts[0].execute("insert into gemfire9.test1 values (1, 1), (2, 2)");
        stmts[2].execute(
            "insert into gemfire9.test2 values (1, 'v1'), (2, 'v2')");
        try {
          stmts[4]
              .execute("insert into gemfire9.test1 values (1, 1), (2, 2)");
          Assert.fail("expected primary key constraint violation");
        } catch (SQLException sqle) {
          if (!"23505".equals(sqle.getSQLState())) {
            throw sqle;
          }
        }
        stmts[4].execute(
            "insert into gemfire9.test2 values (2, 'v2'), (3, 'v3')");
        stmts[5].execute(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')");
        stmts[6].execute(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')");
        stmts[4].execute("insert into gemfire9.test1 values (3, 3), (4, 4)");
        checkAuthzFailure(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')",
            stmts[0]);
        checkAuthzFailure(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')",
            stmts[7]);
        // update will work only for those that also have select permissions
        // i.e. that overlap with gemGroup5 (gemfire4, gemfire6)
        checkAuthzFailure("update gemfire9.test1 set v1=2 where id1=1",
            stmts[0]);
        checkAuthzFailure("update gemfire9.test1 set v1=3 where id1=2",
            stmts[1]);
        stmts[2].execute("update gemfire9.test1 set v1=4 where id1=3");
        stmts[3].execute("update gemfire9.test1 set v1=2 where id1=1");
        checkAuthzFailure("update gemfire9.test1 set v1=4 where id1=3",
            stmts[4]);
        stmts[5].execute("update gemfire9.test1 set v1=5 where id1=4");
        checkAuthzFailure("delete from gemfire9.test1", stmts[1]);
        checkAuthzFailure("update gemfire9.test2 set v2='v2_1' where v2='v2'",
            stmts[2]);
        checkAuthzFailure("select * from gemfire9.test1", stmts[1]);
        stmts[3].executeQuery("select * from gemfire9.test1").close();
        stmts[5].executeQuery("select * from gemfire9.test1").close();
        checkAuthzFailure("select * from gemfire9.test2 where id2 > 1",
            stmts[3]);
        stmts[6].execute("delete from gemfire9.test1");
        // check for DROP_USER procedure access after group refresh
        checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[4]);
        checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[6]);
        checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[7]);
        checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[8]);
        stmts[5].execute("call SYS.DROP_USER('gemfire8')");
        stmts[0].execute("call SYS.DROP_USER('gemfire6')");
        stmts[1].execute("call SYS.DROP_USER('gemfire1')");
        stmts[2].execute("call SYS.DROP_USER('gemfire6')");
        stmts[3].execute("call SYS.DROP_USER('gemfire6')");
        // gemfire1 should no longer be able to execute DROP_USER since dropped
        checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[5]);
        // gemfire6 should no longer be able to execute DROP_USER since dropped
        checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[5]);

        // check no access after revoke
        stmt.execute(
            "revoke insert on gemfire9.test2 from ldapGroup : gemGroup3");
        sysStmt.execute("revoke execute on procedure SYS.DROP_USER "
            + "from ldapGroup: gemGroup7 restrict");

        // check permissions for all refreshed users but failure in other ops
        checkAuthzFailure("insert into gemfire9.test1 values (1, 1), (2, 2)",
            stmts[0]);
        stmts[2].execute(
            "insert into gemfire9.test2 values (1, 'v1'), (2, 'v2')");
        stmts[4].execute("insert into gemfire9.test1 values (1, 1), (2, 2)");
        stmts[2].execute(
            "insert into gemfire9.test2 values (2, 'v2'), (3, 'v3')");
        checkAuthzFailure(
            "insert into gemfire9.test2 values (2, 'v2'), (3, 'v3')",
            stmts[4]);
        checkAuthzFailure(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')",
            stmts[5]);
        checkAuthzFailure(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')",
            stmts[6]);
        stmts[4].execute("insert into gemfire9.test1 values (3, 3), (4, 4)");
        checkAuthzFailure(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')",
            stmts[0]);
        checkAuthzFailure(
            "insert into gemfire9.test2 values (5, 'v5'), (6, 'v6')",
            stmts[7]);
        // update will work only for those that also have select permissions
        // i.e. that overlap with gemGroup5 (gemfire4, gemfire6)
        checkAuthzFailure("update gemfire9.test1 set v1=2 where id1=1",
            stmts[0]);
        checkAuthzFailure("update gemfire9.test1 set v1=3 where id1=2",
            stmts[1]);
        stmts[2].execute("update gemfire9.test1 set v1=4 where id1=3");
        stmts[3].execute("update gemfire9.test1 set v1=2 where id1=1");
        checkAuthzFailure("update gemfire9.test1 set v1=4 where id1=3",
            stmts[4]);
        checkAuthzFailure("update gemfire9.test1 set v1=5 where id1=4",
            stmts[5]);
        checkAuthzFailure("delete from gemfire9.test1", stmts[1]);
        checkAuthzFailure("update gemfire9.test2 set v2='v2_1' where v2='v2'",
            stmts[2]);
        checkAuthzFailure("select * from gemfire9.test1", stmts[1]);
        stmts[3].executeQuery("select * from gemfire9.test1").close();
        checkAuthzFailure("select * from gemfire9.test1", stmts[5]);
        checkAuthzFailure("select * from gemfire9.test2 where id2 > 1",
            stmts[3]);
        stmts[6].execute("delete from gemfire9.test1");
        for (int i = 0; i < 9; i++) {
          checkAuthzFailure("call SYS.DROP_USER('gemfire8')", stmts[i]);
        }

      } finally {
        server.addAttribute(
            "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "uniqueMember",
            "uid=gemfire8,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup3,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "uniqueMember",
            "uid=gemfire5,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "member",
            "uid=gemfire4,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "member",
            "cn=gemGroup1,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
        server.removeAttribute(
            "cn=gemGroup7,ou=ldapTesting,dc=pune,dc=gemstone,dc=com",
            "member",
            "uid=gemfire6,ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      }
    } finally {

      // drop tables at the end
      stmt.execute("drop table gemfire9.test1");
      stmt.execute("drop table gemfire9.test2");

      sysStmt.execute("revoke execute on procedure SYS.DROP_USER "
          + "from ldapGroup: gemGroup7 restrict");

      sysStmt.close();
      sysConn.close();
    }
  }
}
