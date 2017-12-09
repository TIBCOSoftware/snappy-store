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
package com.pivotal.gemfirexd.query;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;

@SuppressWarnings("serial")
public class QueryChecksDUnit  extends DistributedSQLTestBase {
  
  public QueryChecksDUnit(String name) {
    super(name);
  }
  
  // bug test #43314
  public void testSimpleAvg() throws Exception {
    
    startVMs(1, 2);
    
    Connection c = TestUtil.getConnection();

    c.createStatement().execute(
        "create table test_t ( pk_col int primary key, col_ints int ) ");

    c.createStatement().execute(
        "insert into test_t values ( 1, 2302), ( 2, 4690), ( 3, 4901)");

    ResultSet r = c.createStatement().executeQuery(
        "select avg(cast(col_ints as real)) from test_t ");

    assertTrue(r.next());

    assertEquals(Float.valueOf(3964.3333f), r.getFloat(1));
  }
  
  public void testProjectionWithClobs() throws Exception {
    
    startVMs(1, 3);
    
    Connection conn = TestUtil.getConnection();
    
    Statement st = conn.createStatement();
    st
        .execute("create table Layout (     uuid_ varchar(75),      plid bigint not null primary key,       " +
                        "groupId bigint, companyId bigint,       privateLayout smallint, layoutId bigint,        " +
                        "parentLayoutId bigint,  name varchar(4000),     title varchar(4000),    description varchar(4000),      " +
                        "type_ varchar(75),      typeSettings clob,      hidden_ smallint,       friendlyURL varchar(255),       " +
                        "iconImage smallint,     iconImageId bigint,     themeId varchar(75),    colorSchemeId varchar(75),      " +
                        "wapThemeId varchar(75), wapColorSchemeId varchar(75),   css varchar(4000),      priority integer,       " +
                        "layoutPrototypeId bigint,       dlFolderId bigint)");
    
    st.execute("insert into Layout (uuid_, groupId, companyId, privateLayout, layoutId, parentLayoutId, name, title, description, type_, " +
                "typeSettings, hidden_, friendlyURL, iconImage, iconImageId, themeId, colorSchemeId, wapThemeId, wapColorSchemeId, css, " +
                "priority, layoutPrototypeId, dlFolderId, plid) values ('3333-2332-3323-3332', 112, 3323, 33, 3323, 33232, 'NAME : CHAKRABORTY, KUMAR CHAKRABORTY, PRANAB KUMAR CHAKRABORTY, KUMAR PRANAB KUMAR CHAKRABORTY, SOUBHIK KUMAR PRANAB KUMAR CHAKRABORTY'," +
                " 'title: MISTER, MONSIEUR, ��������� (Dan''na), don, ������, ', ' description is self descriptive in name / title. what else ?', 'TYPE-1', " +
                " cast ( 'dafaafsasfasdfasdfasdfasdfasddfasfasdfasd' as clob), 1, 'http://sb.blogspot.com', 1, 1122, 'THEME-1', 'dfadfa', " +
                " 'wapTheme-2' , 'wapColorScheme-11', '<html> <!css type fo scripts> ', 1, 1, 1, 1000) ");
    /*
    ResultSet rs = st
    .executeQuery("select layoutimpl0_.plid as plid13_0_, layoutimpl0_.uuid_ as uuid2_13_0_, layoutimpl0_.groupId as groupId13_0_," +
                " layoutimpl0_.companyId as companyId13_0_, layoutimpl0_.privateLayout as privateL5_13_0_," +
                " layoutimpl0_.layoutId as layoutId13_0_, layoutimpl0_.parentLayoutId as parentLa7_13_0_," +
                " layoutimpl0_.name as name13_0_, layoutimpl0_.title as title13_0_, layoutimpl0_.description as descrip10_13_0_," +
                " layoutimpl0_.type_ as type11_13_0_, layoutimpl0_.hidden_ as hidden13_13_0_, layoutimpl0_.friendlyURL as friendl14_13_0_," +
                " layoutimpl0_.iconImage as iconImage13_0_, layoutimpl0_.iconImageId as iconIma16_13_0_," +
                " layoutimpl0_.themeId as themeId13_0_, layoutimpl0_.colorSchemeId as colorSc18_13_0_," +
                " layoutimpl0_.wapThemeId as wapThemeId13_0_, layoutimpl0_.wapColorSchemeId as wapColo20_13_0_," +
                " layoutimpl0_.css as css13_0_, layoutimpl0_.priority as priority13_0_," +
                " layoutimpl0_.layoutPrototypeId as layoutP23_13_0_, layoutimpl0_.dlFolderId as dlFolderId13_0_" +
                " from Layout layoutimpl0_ where " +
                    "layoutimpl0_.plid=1000");
    ResultSet rs = st
    .executeQuery("select layoutimpl0_.plid as plid13_0_, " +
                "layoutimpl0_.uuid_ as uuid2_13_0_, " +
                "layoutimpl0_.groupId as groupId13_0_, " +
                "layoutimpl0_.companyId as companyId13_0_, " +
                "layoutimpl0_.privateLayout as privateL5_13_0_, " +
                "layoutimpl0_.layoutId as layoutId13_0_, " +
                "layoutimpl0_.parentLayoutId as parentLa7_13_0_, " +
                "layoutimpl0_.name as name13_0_, " +
                "layoutimpl0_.title as title13_0_, " +
                "layoutimpl0_.description as descrip10_13_0_, " +
                "layoutimpl0_.type_ as type11_13_0_, " +
                "layoutimpl0_.typeSettings as typeSet12_13_0_, " +
                "layoutimpl0_.hidden_ as hidden13_13_0_, " +
                "layoutimpl0_.friendlyURL as friendl14_13_0_, " +
                "layoutimpl0_.iconImage as iconImage13_0_, " +
                "layoutimpl0_.iconImageId as iconIma16_13_0_, " +
                "layoutimpl0_.themeId as themeId13_0_, " +
                "layoutimpl0_.colorSchemeId as colorSc18_13_0_, " +
                "layoutimpl0_.wapThemeId as wapThemeId13_0_, " +
                "layoutimpl0_.wapColorSchemeId as wapColo20_13_0_, " +
                "layoutimpl0_.css as css13_0_, " +
                "layoutimpl0_.priority as priority13_0_, " +
                "layoutimpl0_.layoutPrototypeId as layoutP23_13_0_, " +
                "layoutimpl0_.dlFolderId as dlFolderId13_0_ " +
                "from Layout layoutimpl0_ where layoutimpl0_.plid=1000");
    */
    ResultSet rs = st
    .executeQuery("select " +
                "layoutimpl0_.uuid_ as uuid2_13_0_, " +
                "layoutimpl0_.plid as plid13_0_, " +
                "layoutimpl0_.typeSettings as typeSet12_13_0_, " +
                "layoutimpl0_.hidden_ as hidden13_13_0_ " +
                "from Layout layoutimpl0_ where layoutimpl0_.plid=1000");
    while(rs.next()) {
      assertEquals(rs.getString(1), "3333-2332-3323-3332");
      assertEquals(rs.getLong(2), 1000);
      assertEquals(rs.getString(3), "dafaafsasfasdfasdfasdfasdfasddfasfasdfasd");
      assertEquals(rs.getInt(4), 1);
    }
  }
  
  /**
   * Scenario isolated by Patrick in Pivotal forum.
   * @throws Exception
   */
  public void testForumIssue_OrderingWithJoin() throws Exception {
    Properties p = new Properties();
    p.setProperty("default-recovery-delay", "-1");
    startVMs(0, 1, 0, null, p);
    startVMs(1, 2, 0, null, p);
    final int netPort = startNetworkServer(1, null, null);
    final Connection netConn = TestUtil.getNetConnection("localhost", netPort,
        null, null);
    Statement st = netConn.createStatement();
    st
        .execute("create table Customers ( CustomerId integer not null generated always as identity, "
            + " CustomerCode    varchar(8)  not null, "
            + " constraint PKCustomers primary key (CustomerId), "
            + "constraint UQCustomers unique (CustomerCode) "
            + ") partition by column (CustomerId) redundancy 1 persistent");

    st.execute("insert into Customers (CustomerCode) values ('CC1')");
    st.execute("insert into Customers (CustomerCode) values ('CC2')");
    st.execute("insert into Customers (CustomerCode) values ('CC3')");
    st.execute("insert into Customers (CustomerCode) values ('CC4')");

    st
        .execute("create table Devices ( DeviceId    integer     not null    generated always as identity, "
            + " CustomerId  integer     not null,"
            + "MACAddress  varchar(12) not null,"
            + "constraint PKDevices primary key (DeviceId),"
            + "constraint UQDevices unique (CustomerId, MACAddress),"
            + "constraint FKDevicesCustomers foreign key (CustomerId) references Customers (CustomerId)"
            + ") partition by column (CustomerId) colocate with (Customers) redundancy 1 persistent");

    st
        .execute("insert into Devices (CustomerId, MACAddress) values (1, '000000000001')");
    st
        .execute("insert into Devices (CustomerId, MACAddress) values (1, '000000000002')");
    st
        .execute("insert into Devices (CustomerId, MACAddress) values (2, '000000000001')");
    st
        .execute("insert into Devices (CustomerId, MACAddress) values (2, '000000000002')");

    ResultSet res;

    // However when I attempt to return the full result set (i.e. with NULLs)
    // I get the exception.

    // For example the following queries return four rows and give me the
    // error:

    int cid, did;
    res = st
        .executeQuery("select c.*, d.DeviceId as devId from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by c.CustomerId");
    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 1 && (did == 2 || did == 1) && !res.wasNull());

    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 1 && (did == 2 || did == 1) && !res.wasNull());

    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 2 && (did == 3 || did == 4) && !res.wasNull());

    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 2 && (did == 3 || did == 4) && !res.wasNull());

    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue( (cid == 4 || cid == 3) && did == 0 && res.wasNull());

    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue( (cid == 4 || cid == 3) && did == 0 && res.wasNull());
    
    res = st
        .executeQuery("select c.*, d.DeviceId as devId from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by d.DeviceId");
    
    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 1 && (did == 1 || did == 2) && !res.wasNull());
    
    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 1 && (did == 1 || did == 2) && !res.wasNull());
    
    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 2 && (did == 3 || did == 4) && !res.wasNull());
    
    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue(cid == 2 && (did == 3 || did == 4) && !res.wasNull());
    
    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue( (cid == 4 || cid == 3) && did == 0 && res.wasNull());
    
    res.next();
    cid = res.getInt(1);
    did = res.getInt("devId");
    assertTrue( (cid == 4 || cid == 3) && did == 0 && res.wasNull());
    
    res = st
        .executeQuery("select c.*, d.MACAddress as macAd from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by c.CustomerId");
    while (res.next()) {
      getLogWriter().info(res.getInt(1) + " order by custId macAd = "
          + res.getString("macAd"));
    }

    // ...but the following one returns me six rows with NULL for MACAddress
    // for last two Customers as expected.

    res = st
        .executeQuery("select c.*, d.MACAddress as macAd from Customers c left outer join Devices d on c.CustomerId = d.CustomerId order by d.DeviceId");
    while (res.next()) {
      getLogWriter().info(res.getInt(1) + " order by devId macAd = "
          + res.getString("macAd"));
    }

    st.execute("drop table Devices");
    st.execute("drop table Customers");
  }
}
