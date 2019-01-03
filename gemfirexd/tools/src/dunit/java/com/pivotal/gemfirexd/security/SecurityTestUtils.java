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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.GemFireCache;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.test.dunit.AvailablePortHelper;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.standalone.DUnitBB;

/**
 * 
 * @author soubhikc
 */
@SuppressWarnings("serial")
public class SecurityTestUtils extends DistributedSQLTestBase {

  public SecurityTestUtils(String name) {
    super(name);
  }

  public enum AuthenticationSchemes {

    BUILTIN() {

      @Override
      public Properties startupProps(boolean setExplicitPeerAuth,
          boolean forStartup, boolean withAuthorization,
          boolean withOnlyNewSysUser, boolean readOnlyDistUser) {

        if (withOnlyNewSysUser) {
          String u = GemFireXDUtils.getRandomString(false);
          sysUser = "SYSTEM_" + u;
          sysPwd = pwdPrefix + sysUser;
          DUnitBB.getBB().put(sysMapKey, sysUser);

          getGlobalLogger().info(
              "generating only a new sys user " + sysUser + " pwd " + sysPwd);

          String userlist = (String)DUnitBB.getBB().get(csMapKey);
          getGlobalLogger().info(
              "dist-sys users present in " + csMapKey + " = " + userlist);
        }
        else if (forStartup) {

          String u = GemFireXDUtils.getRandomString(false);
          sysUser = "SYSTEM_" + u;
          sysPwd = pwdPrefix + sysUser;
          DUnitBB.getBB().put(sysMapKey, sysUser);
          getGlobalLogger()
              .info("recorded sys user " + sysUser + " pwd " + sysPwd);

          StringBuilder sb = new StringBuilder();

          for (int i = 1; i <= 10; i++) {
            if (i != 1) {
              sb.append(",");
            }
            sb.append("DIST_SYS_").append(i < 10 ? "0" : "").append(i).append(
                "_");
            u = GemFireXDUtils.getRandomString(false);
            sb.append(u.toUpperCase());
          }
          String userlist = sb.toString();

          getGlobalLogger().info("Creating dist-sys users  " + userlist);

          clusterUsers = new String[10];
          clusterUsers = (String[])SharedUtils.toSortedSet(userlist, false)
              .toArray(clusterUsers);

          assertTrue(clusterUsers.length == 10);

          getGlobalLogger()
              .info(
                  "putting into shared map with key " + csMapKey + " = "
                      + userlist);

          DUnitBB.getBB().put(csMapKey, userlist);
        }

        Properties props = new Properties();
        setCommonProperties(props, withAuthorization, readOnlyDistUser);

        try {
          props.setProperty(Property.USER_PROPERTY_PREFIX + sysUser,
              AuthenticationServiceBase.encryptPassword(sysUser, sysPwd));
        } catch (StandardException se) {
          throw new IllegalArgumentException(se);
        }

        props.setProperty(Property.GFXD_AUTH_PROVIDER,
            com.pivotal.gemfirexd.Constants.AUTHENTICATION_PROVIDER_BUILTIN);

        if (setExplicitPeerAuth) {
          props.setProperty(Property.GFXD_SERVER_AUTH_PROVIDER,
              com.pivotal.gemfirexd.Constants.AUTHENTICATION_PROVIDER_BUILTIN);
        }

        return props;
      }

      @Override
      Properties bootCredentials() {
        /* such is the case when startupProps is not called in this VM.
         * only One sysuser can exist at a time here. 
         * AuthenticationSchemes.clear() will clean up this every time.
         */
        if (sysUser == null) {
          sysUser = (String)DUnitBB.getBB().get(sysMapKey);
          sysPwd = pwdPrefix + sysUser;
          getGlobalLogger().info(
              "boot credentials sysUser " + sysUser);
        }
        
        Properties props = new Properties();
        props.setProperty(PartitionedRegion.rand.nextBoolean()
            ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
            : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, sysUser);
        props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, sysPwd);
        return props;
      }

      @Override
      public Properties connectionCredentials(boolean dbUsersOnly,
          boolean randomizeUsers) {
        Properties props = new Properties();
        if (clusterUsers == null) {
          String userlist = (String)DUnitBB.getBB().get(csMapKey);

          getGlobalLogger().info(
              "read from shared map with key " + csMapKey + " = " + userlist);
          assertTrue(userlist != null);

          clusterUsers = userlist.split(",");
        }

        for (String s : clusterUsers) {
          assertTrue(s != null);
        }

        String usr = null;
        if (randomizeUsers) {
          usr = clusterUsers[PartitionedRegion.rand.nextInt(clusterUsers.length)];
        }
        else {
          usr = clusterUsers[disUsrIdx++];
        }

        assert usr != null;
        props.setProperty(PartitionedRegion.rand.nextBoolean()
            ? com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR
            : com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, usr);
        props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, pwdPrefix + usr);
        return props;

      }

      @Override
      public void clear() {
        clusterUsers = null;
        sysUser = null;
        sysPwd = null;
        comonclear();
      }

    },
    LDAP() {

      /* sysUser 1
       * 
       * extending sysUser list below needs to extend in connectionCredentials() function
       * and startUpProps function.
       */
      private final static String gf1 = "gemfire1";

      // sysUser 2
      private final static String gf10 = "gemfire10";

      // dist-users
      private final static String gf2 = "gemfire2";

      private final static String gf3 = "gemfire3";

      private final static String gf4 = "gemfire4";

      private final static String gf5 = "gemfire5";

      {
        clusterUsers = new String[4];
        clusterUsers[0] = gf2;
        clusterUsers[1] = gf3;
        clusterUsers[2] = gf4;
        clusterUsers[3] = gf5;

        assertTrue(clusterUsers.length == 4);
      }

      // un-used.
      // private final static String gf6 = "gemfire6";
      // private final static String gf7 = "gemfire7";
      // private final static String gf8 = "gemfire8";
      // private final static String gf9 = "gemfire9";

      @Override
      public Properties startupProps(boolean setExplicitPeerAuth,
          boolean forStartup, boolean withAuthorization,
          boolean withOnlyNewSysUser, boolean readOnlyDistUser) {

        if (withOnlyNewSysUser) {
          assertTrue(
              "with this flag expected only once. add more sysUser if need more.",
              sysUser.equals(gf1));
          sysUser = gf10;
          sysPwd = sysUser;
        }
        else if (forStartup) {
          sysUser = gf1;
          sysPwd = sysUser;
        }

        Properties props = new Properties();
        setCommonProperties(props, withAuthorization, readOnlyDistUser);

        try {
          setLdapServerBootProperties(LdapTestServer.getInstance(), -1, -1,
              sysUser, props, PartitionedRegion.rand.nextBoolean());
        } catch (Exception e) {
          fail("failed to get LDAP server instance", e);
        }

        if (setExplicitPeerAuth) {
          props.setProperty(Property.GFXD_SERVER_AUTH_PROVIDER,
              com.pivotal.gemfirexd.Constants.AUTHENTICATION_PROVIDER_LDAP);
        }

        props.setProperty("SYS-USER", sysUser);

        return props;
      }

      @Override
      public Properties bootCredentials() {

        /* such is the case when startupProps is not called in this VM.
         * only One sysuser can exist at a time here. 
         * AuthenticationSchemes.clear() will clean up this every time.
         */
        if (sysUser == null) {

          for (Entry<Object, Object> e : System.getProperties().entrySet()) {
            String k = (String)e.getKey();
            String v = (String)e.getValue();

            if (k.equals("SYS-USER")) {
              sysUser = v;
              sysPwd = sysUser;
              break;
            }
          }

        }
        Properties props = new Properties();
        props.setProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, sysUser);
        props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, sysPwd);

        return props;
      }

      @Override
      public Properties connectionCredentials(boolean dbUsersOnly,
          boolean randomizeUsers) {
        Properties props = new Properties();

        for (String s : clusterUsers) {
          assertTrue(s != null);
        }

        String usr = null;
        if (randomizeUsers) {
          usr = clusterUsers[PartitionedRegion.rand.nextInt(clusterUsers.length)];
        }
        else {
          usr = clusterUsers[disUsrIdx++];
        }

        assert usr != null;
        props.setProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, usr);
        props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, usr);
        return props;

      }

      @Override
      public void clear() {
        sysUser = null;
        sysPwd = null;
        // we won't clear dist-users as its static and present in all VMS.
        comonclear();
      }

    };

    AuthenticationSchemes() {
    }

    protected final static String csMapKey = "GFXDAuthenticationClusterUsers";
    protected final static String sysMapKey = "GFXDAuthenticationSystemUser";

    private final static String pwdPrefix = "_PWD_";

    protected String[] clusterUsers = null;

    protected String sysUser = null;

    protected String sysPwd = null;

    protected int disUsrIdx = 0;
    
    abstract Properties startupProps(boolean setExplicitPeerAuth,
        boolean forStartup, boolean withAuthorization,
        boolean withOnlyNewSysUser, boolean readOnlyDistUser);

    abstract Properties bootCredentials();

    abstract Properties connectionCredentials(boolean dbUsersOnly,
        boolean randomizeUsers);

    public void CreateUsers(Connection conn) throws SQLException {

      if (clusterUsers == null) {
        String userlist = (String)DUnitBB.getBB().get(csMapKey);

        getGlobalLogger().info(
            "Won't need to create users ... initializing existing cluster users "
                + csMapKey + " = " + userlist);
        assertTrue(userlist != null);

        clusterUsers = userlist.split(",");

        return;
      }

      getGlobalLogger().info("about to create distributed sys users ");

      CallableStatement cusr = conn.prepareCall("call sys.create_user(?,?)");
      CallableStatement cpwd = conn.prepareCall("call sys.change_password(?,?,?)");
      int i = 0;
      for (String u : clusterUsers) {
        i++;
        if(i % 2 == 0) {
          cusr.setString(1, u);
          cusr.setString(2, "random_" + u);
          cusr.execute();
          getGlobalLogger().info("created distributed system user with random password " + u);

          try {
            cusr.setString(1, u);
            cusr.setString(2, pwdPrefix + u);
            cusr.execute();
            fail("User already exists exception expected.");
          } catch (SQLException sqle) {
            if(!"28504".equals(sqle.getSQLState())) {
              throw sqle;
            }
          }

          // change_password is only valid for BUILIN
          if (this == BUILTIN) {
            cpwd.setString(1, u);
            cpwd.setString(2, "random_" + u);
            cpwd.setString(3, pwdPrefix + u);
            cpwd.execute();
            getGlobalLogger().info(
                "changed password of distributed system user " + u);
          }
        }
        else {
          cusr.setString(1, u);
          cusr.setString(2, pwdPrefix + u);
          cusr.execute();
          getGlobalLogger().info("created distributed system user " + u);
        }
          
      }
    }

    protected void setCommonProperties(Properties props, boolean withAuthorization, boolean readOnly) {
      props.setProperty(Property.REQUIRE_AUTHENTICATION_PARAMETER, Boolean
          .toString(true));

      props.setProperty(DistributionConfig.GEMFIRE_PREFIX
          + DistributionConfig.LOG_LEVEL_NAME, "fine");
      props.setProperty(DistributionConfig.GEMFIRE_PREFIX
          + DistributionConfig.SECURITY_LOG_LEVEL_NAME, "finest");

      props.setProperty(Monitor.DEBUG_TRUE, GfxdConstants.TRACE_AUTHENTICATION
          + "," + GfxdConstants.TRACE_SYS_PROCEDURES + ","
          + GfxdConstants.TRACE_FABRIC_SERVICE_BOOT);

      if (withAuthorization) {
        props.setProperty(com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION, "true");
        if(readOnly) {
          props.setProperty(com.pivotal.gemfirexd.Property.AUTHZ_DEFAULT_CONNECTION_MODE,
              Property.READ_ONLY_ACCESS);
        }
        else {
          props.setProperty(com.pivotal.gemfirexd.Property.AUTHZ_DEFAULT_CONNECTION_MODE,
              Property.FULL_ACCESS);
        }

        props.setProperty(com.pivotal.gemfirexd.Property.AUTHZ_FULL_ACCESS_USERS, sysUser);
        if(readOnly) {
            String clusterUserList = (String)DUnitBB.getBB().get(csMapKey);
            props.setProperty(com.pivotal.gemfirexd.Property.AUTHZ_READ_ONLY_ACCESS_USERS,
                clusterUserList);
        }
      }
      else {
        props.setProperty(com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION, "false");
      }
    }

    public abstract void clear();

    protected void comonclear() {
      disUsrIdx = 0;
      SecurityTestUtils.tables.clearHistory();
    }

    public void resetDistUserIndex() {
      disUsrIdx = 0;
    }

    public void DropUsers(Connection conn) throws SQLException {

      getGlobalLogger().info("Dropping distributed sys users ");
      DUnitBB.getBB().remove(csMapKey);

      CallableStatement cusr = conn.prepareCall("call sys.drop_user(?)");
      for (String u : clusterUsers) {
        cusr.setString(1, u);
        cusr.execute();
        getGlobalLogger().info("dropped distributed system user " + u);
      }

    }

    public String getLocatorString() {
      String available_port = String.valueOf(AvailablePortHelper
          .getRandomAvailableTCPPort());
      return "localhost[" + available_port + "]";
    }
  }

  public static void assertCurrentSchema(Statement stmts, String schemaName)
      throws SQLException {

    String currentschema = currentSchema(stmts);
    assertEquals(schemaName.toLowerCase(), currentschema.toLowerCase());
  }
  
  public static String currentSchema(Statement stmts)
      throws SQLException {
    ResultSet rs = stmts
        .executeQuery("SELECT x from (VALUES(CURRENT SCHEMA)) as mySch(x) ");
    rs.next();
    return (String)rs.getObject(1);
  }

  public static void setupAuthentication(final AuthenticationSchemes scheme,
      final Properties sysprop) {

    invokeInEveryVM(new SerializableRunnable("setupAuthentication") {
      @Override
      public void run() {

        setSystemProperties(sysprop, scheme);

      }
    });

    setSystemProperties(sysprop, scheme);

  }

  public static void setSystemProperties(Properties sysprop,
      AuthenticationSchemes scheme) {

    for (Enumeration<?> e = sysprop.propertyNames(); e.hasMoreElements();) {
      String k = (String)e.nextElement();
      System.setProperty(k, sysprop.getProperty(k));
      getGlobalLogger().info("setting " + k + " = " + sysprop.getProperty(k));
    }

    Properties connAtt = scheme.bootCredentials();
    TestUtil.bootUserName = connAtt.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
    TestUtil.bootUserName = TestUtil.bootUserName == null ? connAtt.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : TestUtil.bootUserName;
    TestUtil.bootUserPassword = connAtt.getProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR);
    getGlobalLogger().info("Recorded bootUserName " + TestUtil.bootUserName);

    getGlobalLogger().info("System properties definition done .... ");
  }

  public static void clearAuthenticationSetUp(final Properties sysprop,
      final AuthenticationSchemes scheme) {

    SerializableRunnable clearSetup = new SerializableRunnable("clearAuthenticationSetup") {
      @Override
      public void run() {
        scheme.clear();
        TestUtil.bootUserName = null;
        for (Enumeration<?> e = sysprop.propertyNames(); e.hasMoreElements();) {
          String k = (String)e.nextElement();
          getGlobalLogger().info("clearing " + k);
          System.clearProperty(k);
        }
        // explicitly clear auth providers
        System.clearProperty(Attribute.AUTH_PROVIDER);
        System.clearProperty(Attribute.SERVER_AUTH_PROVIDER);
        getGlobalLogger().info("System user definition cleared on VM.... ");
      }
    };
    invokeInEveryVM(clearSetup);
    clearSetup.run();
    getGlobalLogger().info("System user definition cleared on local VM ");
  }
  
  public final static String commonSchemaName = "shared_TestSchema".toUpperCase();

  // Authorization methods
  public enum tables {

    SharedTable {
      
      private String tableschema = commonSchemaName;

      private volatile int num_rows = 0;

      private final String tabnm = this.name();

      private final String[] cols = new String[] { "st_1", "st_2", "st_3" };

      @Override
      public void createTable(Statement stmts) throws SQLException {
        ResultSet rs = stmts.executeQuery("VALUES CURRENT_USER");
        rs.next();
        tableschema = rs.getString(1);
        stmts.execute("create table " + tabnm + "(st_1 int, st_2 int, st_3 int)");
      }

      @Override
      public void doInsert(Statement stmts, String... valueList)
          throws SQLException {

        String query = "insert into " + tabnm + " values";
        if (valueList == null) {
          stmts.execute(query + "(1,1,1), (2,2,2), (3,3,3)");
        }
        else {

          for (String s : valueList) {
            query += s + (s.equals(valueList[valueList.length - 1]) ? "" : ",");
          }

          getGlobalLogger().info("executing " + query);
          stmts.execute(query);

        }
      }

      @Override
      public void doDelete(Statement stmts) {

        num_rows--;
      }

      private final String[] updateAlllist = new String[] {
          "update " + tabnm + " set st_1=100, st_2=200",
          "update " + tabnm + " set st_1=101, st_2=201",
          "update " + tabnm + " set st_1=102, st_2=202",
          "update " + tabnm + " set st_1=103, st_2=210",
          "update " + tabnm + " set st_1=104, st_2=220",
          "update " + tabnm + " set st_1=105, st_2=230", };

      private int currentUpIdx = 0;

      @Override
      public void doUpdate(Statement stmts, String[] col_list) throws SQLException {
        
        if(col_list == null || col_list.length == 0) {
          int rowsEffected = stmts.executeUpdate(updateAlllist[currentUpIdx++]);

          refreshNumRows();

          assertEquals(num_rows, rowsEffected);
        }
        
      }

      @Override
      public int rowCount(Statement stmts, int privileges) throws SQLException {

        int rowCount = 0;
        try {
          ResultSet rs = stmts.executeQuery("select count(1) from " + tabnm);
          assertTrue(this + "rowCount: ", rs.next());
          rowCount = rs.getInt(1);

          /* enable this after fixing #41781
          if (!priv_set.hasAnyPriv(privileges, priv_set.SELECT_ALL,
              priv_set.SELECT_ST_1, priv_set.SELECT_ST_1_ST_2)) {
            SanityManager.THROWASSERT("must have failed as we don't have select privilege of any column ");
          }*/

        }
        catch(SQLException sqle) {
          if (priv_set.hasAnyPriv(privileges, priv_set.SELECT_ALL,
              priv_set.SELECT_ST_1, priv_set.SELECT_ST_1_ST_2)) {
            throw sqle;
          }
          else if(!"42502".equals(sqle.getSQLState())) {
             throw sqle;
          }
          
          // okay, we don't fulfill the criteria (atleast one column select
          // access should be there for count). can't really check
          rowCount = -1;
        }

        refreshNumRows();

        //enable this after fixing #41781 if(rowCount != -1)
           assertEquals(num_rows, rowCount);

        return num_rows;
      }

      private void refreshNumRows() throws SQLException {

        String regionPath = Misc.getRegionPath(tableschema, tabnm
            .toUpperCase(), null);
        getGlobalLogger().info("getting region " + regionPath);

        Region<?,?> reg = Misc.getGemFireCache().getRegion(regionPath);
        
        assertTrue("Region expected " + regionPath, reg != null);

        num_rows = reg.size();
      }

      @Override
      public void reset() {
        num_rows = 0;
        currentUpIdx = 0;
      }

      @Override
      public int doSelect(Statement stmts, String[] col_list)
          throws SQLException {
        StringBuilder query = new StringBuilder("select ");

        if (col_list == null || col_list.length == 0) {
          query.append("*");
        }
        else {
          for (int i = 0; i < col_list.length; i++) {
            if (i != 0) {
              query.append(",");
            }
            query.append(col_list[i]);
          }
        }

        query.append(" from ");
        query.append(tabnm);

        getGlobalLogger().info(query.toString());
        ResultSet rs = stmts.executeQuery(query.toString());
        int rows = 0;
        while(rs.next()) rows++;

        return rows;
      }

      @Override
      public String[] columns() {
        return cols;
      }

      @Override
      public String getSchema() {
        return tableschema;
        
      }

    };

    abstract void createTable(Statement stmts) throws SQLException;

    abstract void doInsert(Statement stmts, String... valueList)
        throws SQLException;

    abstract void doUpdate(Statement stmts, String[] col_list) throws SQLException;

    abstract void doDelete(Statement stmts);

    abstract int rowCount(Statement stmts, int privileges) throws SQLException;

    abstract int doSelect(Statement stmts, String[] col_list)
        throws SQLException;

    abstract String[] columns();
    
    abstract String getSchema();

    abstract void reset();

    static void clearHistory() {
      for (tables t : tables.values()) {
        t.reset();
      }
    }
  }

  public enum priv_set {
    INSERT(0x1) {
      @Override
      public void addAuthFailureException(Set<String> expect) {
        expect.add("42500"); // SQLState.AUTH_NO_TABLE_PERMISSION
      }

      @Override
      public String privilege() {
        return "INSERT";
      }
    },
    DELETE(0x2) {
      @Override
      public void addAuthFailureException(Set<String> expect) {

      }

      @Override
      public String privilege() {
        return "DELETE";
      }
    },
    TRIGGER(0x4) {
      @Override
      public void addAuthFailureException(Set<String> expect) {

      }

      @Override
      public String privilege() {
        return "TRIGGER";
      }
    },
    SELECT_ALL(0x8) {
      
      @Override
      public void addAuthFailureException(Set<String> expect) {

      }

      @Override
      public String privilege() {
        return "SELECT";
      }
    },
    SELECT_ST_1(0x10, Arrays.asList(new String[] { "st_1" })) {
      @Override
      public void addAuthFailureException(Set<String> expect) {
        expect.add("42502"); // AUTH_NO_COLUMN_PERMISSION
      }
      
      @Override
      public String privilege() {
        return "SELECT";
      }
    },
    SELECT_ST_1_ST_2(0x20, Arrays.asList(new String[] { "st_1", "st_2" })) {
      @Override
      public void addAuthFailureException(Set<String> expect) {
        expect.add("42502"); // AUTH_NO_COLUMN_PERMISSION
      }

      @Override
      public String privilege() {
        return "SELECT";
      }
    },
    UPDATE_ALL(0x40) {
      @Override
      public void addAuthFailureException(Set<String> expect) {
        
      }

      @Override
      public String privilege() {
        return "UPDATE";
      }
    },
    UPDATE_ST_2(0x80, Arrays.asList(new String[] { "st_2" })) {
      @Override
      public void addAuthFailureException(Set<String> expect) {
        expect.add("42502"); // SQLState.AUTH_NO_COLUMN_PERMISSION
      }
      
      @Override
      public String privilege() {
        return "UPDATE";
      }
    },
    REFERENCES(0x100, Arrays.asList(new String[] { "st_3" })) {
      @Override
      public void addAuthFailureException(Set<String> expect) {

      }
      
      @Override
      public String privilege() {
        return "REFERENCES";
      }
    };

    private int priv_bit;

    private List<String> collist;

    abstract void addAuthFailureException(Set<String> expect);
    abstract String privilege();

    priv_set(int privbit) {
      this.priv_bit = privbit;
      this.collist = java.util.Collections.emptyList();
    }

    priv_set(int priv_bit, List<String> collist) {
      this.priv_bit = priv_bit;
      this.collist = collist;
    }

    public List<String> getAllowedColumnList() {
      return collist;
    }

    public static boolean hasPriv(int userPriv, priv_set targetPriv) {
      return (userPriv & targetPriv.priv_bit) != 0;
    }

    public static boolean hasAnyPriv(int userPriv, priv_set... targetPriv) {
      boolean result = false;
      for(priv_set p : targetPriv) {
         result |= ((userPriv & p.priv_bit) != 0);
      }
      return result;
    }
    
    public static int clearPriv(int userPriv, priv_set targetPriv) {
      return (userPriv & ~targetPriv.priv_bit);
    }

    public static String privString(int userPriv) {
      StringBuilder sb = new StringBuilder();
      do {

        sb.append((userPriv & 0x01) == 1 ? "1" : "0");

      } while ((userPriv = userPriv >>> 1) != 0);

      return sb.toString();
    }

    public static Set<String> getAllowedSQLStates(int privileges) {

      Set<String> exceptionStates = new HashSet<String>();

      for (priv_set p : priv_set.values()) {

        if (!hasPriv(privileges, p)) {
          p.addAuthFailureException(exceptionStates);
        }

      }

      return exceptionStates;

    }

  };

  public static void grantPrivilege(String schema, String dist_user,
      Statement stmts, ArrayList<Integer> grantList, priv_set... privileges) throws SQLException {

    int rightsGranted = 0;

    for (priv_set p : privileges) {
      StringBuilder sb = new StringBuilder();

      sb.append(p.privilege()).append(" ");

      List<String> columnlist = p.getAllowedColumnList();

      if (!columnlist.isEmpty()) {
        sb.append("(").append(columnlist.get(0));

        for (int idx = 1; idx < columnlist.size(); idx++) {
          sb.append(",").append(columnlist.get(idx));
        }

        sb.append(")");
      }
      String priv = sb.toString();
      getGlobalLogger().info("GRANTING " + priv + " to " + dist_user);
      stmts.execute("grant " + priv + " on " + schema + "."
          + tables.SharedTable.name() + " to " + dist_user);
      rightsGranted |= p.priv_bit;
    }

    grantList.add(Integer.valueOf(rightsGranted));
  }

  public static void checkDistUser(final Properties dist_user_conn_props,
      final String sharedSchemaName, final int privileges, boolean readOnlyConnection) throws SQLException {

    getGlobalLogger().info(
        "Got privileges " + priv_set.privString(privileges) + " (" + privileges
            + ") schemaName " + sharedSchemaName + " dist_user props "
            + dist_user_conn_props);

    assert dist_user_conn_props != null: " this should have been set ";

    Properties connectionProp = new Properties();
    connectionProp.putAll(dist_user_conn_props);
    String currentUser = connectionProp.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
    currentUser = currentUser == null ? connectionProp.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : currentUser;
    getGlobalLogger().info(
        "getting local connection using distributed system user "
            + connectionProp);
    Connection conn;
    conn = TestUtil.getConnection(connectionProp);
    getGlobalLogger().info("connection successfull. ");

    Statement stmts = conn.createStatement();

    // do sanity check of the current user's current schema rights.
    {
      final String distTable = "dist_table_1";
      getGlobalLogger()
          .info(
              "Check table " + distTable + " in " + currentUser
                  + " current schema");
      ResultSet tabexist = stmts
          .executeQuery("select tablename from sys.systables where tablename = '"
              + distTable + "'");

      if (tabexist.next()) {
        String tab = tabexist.getString(1);
        if (tab != null) {
          getGlobalLogger().info(
              tab + " exists in " + currentUser + " current schema");
        }
      }
      try {
        stmts.execute("drop table " + distTable);
      } catch (SQLException sqe_ignore) { }

      try {
        getGlobalLogger().info(
            "creating tables in OWN schema (" + currentUser + ")");
        stmts.execute("create table " + distTable
            + " ( col1 int, col2 int, primary key (col1))");
      } catch (SQLException sqe) {  
        //ignore if readOnlyConnection and ddl create fails, drop table anyway is ignored.
        if(! (readOnlyConnection && sqe.getSQLState().equals("25503")) ) {
          throw sqe;
        }
        else {
          ; //okay we have DLL creation restriction on readOnly connection
        }
        
        
      }
    }

    // now lets do the DML operations
    {
      getGlobalLogger().info("switching to schema " + sharedSchemaName);
      stmts.execute("SET SCHEMA " + sharedSchemaName);

      assertCurrentSchema(stmts, sharedSchemaName);

      getGlobalLogger().info("browsing " + tables.SharedTable);
      tables.SharedTable.rowCount(conn.createStatement(), privileges);

      checkSelect(tables.SharedTable, conn, stmts, privileges,
          dist_user_conn_props);

      checkInsert(tables.SharedTable, conn, stmts, privileges,
          dist_user_conn_props, readOnlyConnection);

      checkUpdate(tables.SharedTable, conn, stmts, privileges,
          dist_user_conn_props, readOnlyConnection);
    }

    connectionProp.clear();
    conn.close();
    conn = null;
  }

  static void checkSelect(tables tab, Connection conn, Statement stmts,
      int privileges, Properties props) throws SQLException {

    getGlobalLogger().info("executing row count on " + tab);
    final int rows = tab.rowCount(stmts, privileges);

    getGlobalLogger().info("Checking select of ALL columns in " + tab);
    try {
      int rowsSelected = tab.doSelect(stmts, tab.columns());
      
      if (!priv_set.hasPriv(privileges, priv_set.SELECT_ALL)) {
        SanityManager.THROWASSERT("must have failed as dist_user don't have access to all the columns of table "
            + tab.name());
      }
      
      assertEquals(rows, rowsSelected);
    } catch (SQLException expected) {
      if (priv_set.hasPriv(privileges, priv_set.SELECT_ALL)) {
        SanityManager.THROWASSERT(expected.getSQLState() + " shouldn't have failed " + expected);
      }
      else if (!priv_set.getAllowedSQLStates(privileges).contains(
          expected.getSQLState())) {
        SanityManager.THROWASSERT(expected.getSQLState() + " unexpected exception received "
            + expected);
      }
      else
        // okay. we didn't had Select privilege and hence this is expected.
        ;
    }

    getGlobalLogger().info("Checking select of few columns in " + tab);
    try {
      int rowsSelected = tab.doSelect(stmts, priv_set.SELECT_ST_1.getAllowedColumnList().toArray(
          new String[] {}));
      
      if (!priv_set.hasAnyPriv(privileges, priv_set.SELECT_ST_1,
          priv_set.SELECT_ST_1_ST_2, priv_set.SELECT_ALL)) {
        SanityManager
            .THROWASSERT("must have failed as selective columns privileges is not there in "
                + tab.name());
      }
      assertEquals(rows, rowsSelected);
      
    } catch (SQLException expected) {
      if (priv_set.hasAnyPriv(privileges, priv_set.SELECT_ALL,
          priv_set.SELECT_ST_1_ST_2, priv_set.SELECT_ST_1)) {
        SanityManager.THROWASSERT(expected.getSQLState()
            + " granted columns couldn't be selected " + expected);
      }
      else if (!priv_set.getAllowedSQLStates(privileges).contains(
          expected.getSQLState())) {
        SanityManager.THROWASSERT(expected.getSQLState() + " unexpected exception received "
            + expected);
      }
      else {
        ; //okay we don't have select privilege... 
      }
    }

    getGlobalLogger().info("Checking select of few other columns in " + tab);
    try {
      int rowsSelected = tab.doSelect(stmts, priv_set.SELECT_ST_1_ST_2.getAllowedColumnList()
          .toArray(new String[] {}));

      assertEquals(rows, rowsSelected);

      if (!priv_set.hasAnyPriv(privileges, priv_set.SELECT_ST_1_ST_2,
          priv_set.SELECT_ALL)) {
        SanityManager
            .THROWASSERT("must have failed as selective columns privileges is not there in "
                + tab.name());
      }
    } catch (SQLException expected) {
      if (priv_set.hasAnyPriv(privileges, priv_set.SELECT_ALL,
          priv_set.SELECT_ST_1_ST_2)) {
        SanityManager.THROWASSERT(expected.getSQLState()
            + " shouldn't have failed " + expected);
      }
      else if (!priv_set.getAllowedSQLStates(privileges).contains(
          expected.getSQLState())) {
        SanityManager.THROWASSERT(expected.getSQLState() + " unexpected exception received "
            + expected);
      }
      else {
        ; // okay we don't have select privilege...
      }
    }

  }

  static void checkInsert(tables tab, Connection conn, Statement stmts,
      int privileges, Properties props, boolean readOnlyConnection) throws SQLException {
    try {

      getGlobalLogger().info("Checking insert into " + tab);

      tab.doInsert(stmts, "(4,4,4)", "(5,5,5)", "(6,6,6)");

      if (!priv_set.hasPriv(privileges, priv_set.INSERT)) {
        String userName = props.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
        userName = userName == null ? props.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
        SanityManager.THROWASSERT("expected insert to fail as "
            + userName
            + " have priveleges to be " + priv_set.privString(privileges));
      }

    } catch (SQLException expected) {
      handleException(privileges, priv_set.INSERT, readOnlyConnection,
          expected, conn);
    }

    tab.rowCount(stmts, privileges);
  }

  static void checkUpdate(tables tab, Connection conn, Statement stmts,
      int privileges, Properties props, boolean readOnlyConnection) throws SQLException {
    try {

      getGlobalLogger().info("Checking update into " + tab);

      tab.doUpdate(stmts, null);

      if (!priv_set.hasPriv(privileges, priv_set.UPDATE_ALL)) {
        String userName = props.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR);
        userName = userName == null ? props.getProperty(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR) : userName;
        SanityManager.THROWASSERT("expected update to fail as "
            + userName
            + " have priveleges to be " + priv_set.privString(privileges));
      }

    } catch (SQLException expected) {
      handleException(privileges, priv_set.UPDATE_ALL, readOnlyConnection,
          expected, conn);
    }

    tab.rowCount(stmts, privileges);
  }
  
  private static void handleException(int privileges, priv_set targetPriv,
      boolean readOnlyConnection, SQLException expected, Connection conn)
      throws SQLException {

    if (!readOnlyConnection && priv_set.hasPriv(privileges, targetPriv)) {
      SanityManager.THROWASSERT(expected.getSQLState() + " shouldn't have failed " + expected);
    }
    else if (readOnlyConnection) {
      if (!conn.isReadOnly()) {
        SanityManager.THROWASSERT("Connection NOT READONLY ...");
      }
      if (!expected.getSQLState().equals("25502")) {
        SanityManager.THROWASSERT("Connection IS READONLY, expecting " + expected.getSQLState()
            + " (Write with readonly connection exception) but received - "
            + expected);
      }
    }
    else if (!priv_set.getAllowedSQLStates(privileges).contains(
        expected.getSQLState())) {
      SanityManager.THROWASSERT(expected.getSQLState() + " unexpected exception received "
          + expected);
    }
    else
      getGlobalLogger().info("Ignoring " + targetPriv + " exeception " + expected);
      // okay. we didn't had the privilege and hence this is expected.

  }

  public static void checkDistUser_everywhere(final Properties props,
      final String sharedSchemaName, final int privileges,
      final boolean readOnlyConnection) throws SQLException {
    invokeInEveryVM(SecurityTestUtils.class, "checkForDistUser", new Object[] {
        props, sharedSchemaName, privileges, readOnlyConnection });
  }

  public static void checkForDistUser(final Properties props,
      final String sharedSchemaName, final int privileges,
      final boolean readOnlyConnection) {
    try {
      checkDistUser(props, sharedSchemaName, privileges, readOnlyConnection);
    } catch (SQLException e) {
      e.printStackTrace();
      GemFireCache cache = Misc.getGemFireCacheNoThrow();
      throw GemFireXDRuntimeException.newRuntimeException("Check Exception in "
          + (cache != null ? cache.getDistributedSystem()
              .getDistributedMember() : "cache is null"), e);
    }
  }

  public static Properties startLdapServerAndGetBootProperties(
      int locatorPort, int mcastPort, String sysUser) throws Exception {
    return startLdapServerAndGetBootProperties(locatorPort, mcastPort,
        sysUser, null);
  }

  public static Properties startLdapServerAndGetBootProperties(
      int locatorPort, int mcastPort, String sysUser,
      String ldifFilePath) throws Exception {
    return startLdapServerAndGetBootProperties(locatorPort, mcastPort,
        sysUser, ldifFilePath, false);
  }

  public static Properties startLdapServerAndGetBootProperties(
      int locatorPort, int mcastPort, String sysUser,
      String ldifFilePath, boolean disableServerAuth) throws Exception {
    final LdapTestServer server;
    if (ldifFilePath != null) {
      server = LdapTestServer.getInstance(ldifFilePath);
    } else {
      server = LdapTestServer.getInstance();
    }
    if (!server.isServerStarted()) {
      int port = AvailablePortHelper.getRandomAvailableTCPPort();
      server.startServer("localhost", port);
    }
    Properties bootProps = new Properties();
    setLdapServerBootProperties(server, locatorPort, mcastPort,
        sysUser, bootProps, disableServerAuth);
    return bootProps;
  }

  public static void setLdapServerBootProperties(LdapTestServer server,
      int locatorPort, int mcastPort, String sysUser, Properties bootProps,
      boolean disableServerAuth) {
    int serverPort = server.getServerPort();
    if (locatorPort > 0) {
      bootProps.setProperty(DistributionConfig.LOCATORS_NAME,
          "localhost[" + locatorPort + ']');
    } else if (mcastPort > 0) {
      bootProps.setProperty(DistributionConfig.MCAST_PORT_NAME,
          Integer.toString(mcastPort));
    }
    bootProps.setProperty(DistributionConfig.SECURITY_LOG_LEVEL_NAME, "finest");
    bootProps.setProperty(Monitor.DEBUG_TRUE,
        GfxdConstants.TRACE_AUTHENTICATION + ","
            + GfxdConstants.TRACE_SYS_PROCEDURES + ","
            + GfxdConstants.TRACE_FABRIC_SERVICE_BOOT);
    bootProps.setProperty(Attribute.AUTH_PROVIDER,
        com.pivotal.gemfirexd.Constants.AUTHENTICATION_PROVIDER_LDAP);
    if (disableServerAuth) {
      bootProps.setProperty(Attribute.SERVER_AUTH_PROVIDER, "NONE");
    }
    bootProps.setProperty(
        com.pivotal.gemfirexd.Property.AUTH_LDAP_SEARCH_BASE,
        "ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
    bootProps.setProperty(
        com.pivotal.gemfirexd.Property.AUTH_LDAP_GROUP_SEARCH_BASE,
        "ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
    bootProps.setProperty(com.pivotal.gemfirexd.Property.AUTH_LDAP_SERVER,
        "ldap://localhost:" + serverPort);
    setUserProps(sysUser, bootProps);
  }

  public static void setUserProps(String user, Properties props) {
    props.setProperty(Attribute.USERNAME_ATTR, user);
    props.setProperty(Attribute.PASSWORD_ATTR, user);
  }
}
