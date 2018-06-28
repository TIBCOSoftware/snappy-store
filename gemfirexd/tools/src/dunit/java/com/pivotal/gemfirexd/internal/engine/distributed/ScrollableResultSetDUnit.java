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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;

/**
 * @author kneeraj
 * 
 */
public class ScrollableResultSetDUnit extends DistributedSQLTestBase {

  public ScrollableResultSetDUnit(String name) {
    super(name);
  }

  private static final long serialVersionUID = 1L;

  public void testScrolling_embedClient() throws Exception {
    startVMs(1, 2);
    Connection conn = TestUtil.getConnection();
    runTest(conn);
  }
  
  public void testScrolling_thinClient() throws Exception {
    startVMs(0, 2);
    final int netPort = startNetworkServer(1, null, null);
    TestUtil.loadNetDriver();
    Connection conn = DriverManager.getConnection(TestUtil.getNetProtocol(
        "localhost", netPort));
    runTest(conn);
  }

  public void runTest(Connection conn) throws Exception {
    Statement s = conn.createStatement();
    String ddl = "create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint "
        + "sec_pk primary key (sec_id), constraint sec_uq unique "
        + "(symbol, exchange), constraint exc_ch check (exchange in "
        + "('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
        + " partition by column (sec_id, price)  REDUNDANCY 2";
    s.execute(ddl);

    ddl = "create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk primary key (cid, sid), "
        + "constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty))"
        + "  partition by range (cid) "
        + "( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, "
        + "VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, "
        + "VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 10000)  REDUNDANCY 2";
    s.execute(ddl);

    s.execute("insert into trade.securities values "
        + "(1204, 'lo', 33.9, 'hkse', 53), (1205, 'do', 34.9, 'fse', 54), (1206, 'jo', 35.9, 'tse', 55)");

    s.execute("insert into trade.portfolio values (508, 1204, 1080, 1080, "
        + "36612.00000000000000000000, 100)");

    Statement scrStmnt = conn.createStatement(
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    ResultSet rs = scrStmnt
        .executeQuery("SELECT sec_id, symbol FROM trade.securities");
    rs.last();
    rs.next();
    assertFalse(rs.next());
    assertTrue(rs.first());

    int cnt = 1;
    while (rs.next()) {
      cnt++;
    }

    assertEquals(3, cnt);
    // scrollable ResultSets now need to be closed explicitly for DDLs
    // to be unblocked
    scrStmnt.close();

    s.execute("drop table trade.securities");
    s.execute("drop table trade.portfolio");

    ddl = "create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint "
        + "sec_pk primary key (sec_id), constraint sec_uq unique "
        + "(symbol, exchange), constraint exc_ch check (exchange in "
        + "('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
        + " replicate";
    s.execute(ddl);

    ddl = "create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk primary key (cid, sid), "
        + "constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty))"
        + " replicate";
    s.execute(ddl);

    s.execute("insert into trade.securities values "
        + "(1204, 'lo', 33.9, 'hkse', 53), (1205, 'do', 34.9, 'fse', 54), (1206, 'jo', 35.9, 'tse', 55)");

    s.execute("insert into trade.portfolio values "
        + "(508, 1204, 1080, 1080, 36612.00000000000000000000, 100), "
        + "(508, 1205, 1080, 1080, 36612.00000000000000000000, 100), "
        + "(508, 1206, 1080, 1080, 36612.00000000000000000000, 100)");

    scrStmnt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_READ_ONLY);
    rs = scrStmnt.executeQuery("SELECT sec_id, symbol FROM trade.securities");
    rs.last();
    rs.next();
    assertFalse(rs.next());
    assertTrue(rs.first());

    cnt = 1;
    while (rs.next()) {
      cnt++;
    }

    assertEquals(3, cnt);

    rs = scrStmnt
        .executeQuery("SELECT sec_id, symbol, sid FROM trade.securities, trade.portfolio where sec_id = sid");
    rs.last();
    rs.next();
    assertFalse(rs.next());
    assertTrue(rs.first());

    cnt = 1;
    while (rs.next()) {
      cnt++;
    }

    assertEquals(3, cnt);

    rs = scrStmnt
        .executeQuery("SELECT * FROM trade.securities, trade.portfolio");
    rs.last();
    rs.next();
    assertFalse(rs.next());
    assertTrue(rs.first());

    cnt = 1;
    while (rs.next()) {
      cnt++;
    }

    assertEquals(9, cnt);
    
    
    //Bug#46234  
    rs = scrStmnt
         .executeQuery("SELECT * FROM trade.portfolio where cid = 508 and sid = 1204");
    rs.last();
    rs.next();
    assertFalse(rs.next());
    assertTrue(rs.first());

    cnt = 1;
    while (rs.next()) {
      cnt++;
    }

    assertEquals(1, cnt);
     
  }
}
