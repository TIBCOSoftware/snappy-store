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
package com.pivotal.gemfirexd.internal.engine.distributed.offheap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.distributed.PreparedStatementDUnit;
import com.pivotal.gemfirexd.jdbc.Bugs3Test.DataGenerator;

public class OffHeapPreparedStatementDUnit extends PreparedStatementDUnit {

  public OffHeapPreparedStatementDUnit(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    this.configureDefaultOffHeap(true);
  }

  @Override
  public String getSuffix() {
    return " offheap ";
  }

  public void testLobsInsertForReplicate() throws Exception {

    startVMs(1, 3);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange)," +
        " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate offheap");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, companytype smallint, " +
    		"uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), companyinfo clob, " +
    		"note long varchar, histprice trade.udtprice, asset bigint, logo varchar(100) for bit data, tid int, " +
    		"constraint comp_pk primary key (symbol, exchange)) replicate offheap");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    rs = st.executeQuery("select * from trade.securities");
    int k = 0;
    while (rs.next()) {
      String symbol = rs.getString(2);
      String exchange = rs.getString(4);
      dg.insertIntoCompanies(psComp, symbol, exchange);
      ++k;
    }
    assertTrue(k >= 1);

  }

  public void testLobsInsertForPartitioned() throws Exception {

    startVMs(1, 3);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
        + "constraint sec_uq unique (symbol, exchange), " +
        "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) offheap redundancy 2");

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?,?, ?)");

    DataGenerator dg = new DataGenerator();
    for (int i = 1; i < 2; ++i) {
      dg.insertIntoSecurities(psSec, i);
    }

    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null, "
        + "companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, "
        + "companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, "
        + "asset bigint, logo varchar(100) for bit data, tid int," +
        " constraint comp_pk primary key (symbol, exchange)) offheap redundancy 2");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    rs = st.executeQuery("select * from trade.securities");
    int k = 0;
    while (rs.next()) {
      String symbol = rs.getString(2);
      String exchange = rs.getString(4);
      dg.insertIntoCompanies(psComp, symbol, exchange);
      ++k;
    }
    assertTrue(k >= 1);

  }
}
