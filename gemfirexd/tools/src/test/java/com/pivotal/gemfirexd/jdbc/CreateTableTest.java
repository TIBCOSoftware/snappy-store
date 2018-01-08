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
//
//  CreateSchemaTest.java
//  gemfire
//
//  Created by Eric Zoerner on 6/24/08.

package com.pivotal.gemfirexd.jdbc;

import java.math.BigDecimal;
import java.sql.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionAttributesImpl;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.gemstone.gnu.trove.TLongHashSet;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.ddl.GfxdTestRowLoader;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.MemScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.Hash1IndexScanController;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdListPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdRangePartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.MultipleInsertsLeveragingPutAllDUnit.BatchInsertObserver;
import com.pivotal.gemfirexd.internal.engine.distributed.MultipleInsertsLeveragingPutAllDUnit.BulkGetObserver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GfxdObjectSizer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import io.snappydata.test.dunit.VM;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.functionTests.tests.derbynet.SqlExceptionTest;
import org.apache.derbyTesting.junit.JDBC;
import udtexamples.UDTPrice;

@SuppressWarnings("serial")
public class CreateTableTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(CreateTableTest.class));
  }

  public CreateTableTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  public static void createTables(Connection conn) throws SQLException {
    PreparedStatement prepStmt = conn
        .prepareStatement("create table trade.customers "
            + "(cid int not null, cust_name varchar(100), "
            + "addr varchar(100), tid int, primary key (cid),"
            + "constraint cust_uk unique (tid))");
    prepStmt.execute();

    // add fk table portfolio
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int not null, "
        + "constraint portf_pk primary key (cid, sid), constraint cust_fk "
        + "foreign key (cid) references trade.customers (cid) on "
        + "delete restrict, constraint qty_ck check (qty >= 0), "
        + "constraint avail_ck check (availQty <= qty))");
    prepStmt.execute();
  }

  public static void populateData(Connection conn, boolean portFull,
      boolean skipPort) throws SQLException {
    populateData(conn, portFull, skipPort, false, false, false,
        "trade.customers", "trade.portfolio");
  }

  public static void populateData(Connection conn, boolean portFull,
      boolean skipPort, boolean hasDroppedColumns, boolean populateMoreData,
      boolean tidFk, String custTable, String portTable) throws SQLException {
    final int startIndex1, endIndex1, startIndex2, endIndex2;
    final String insQuery1, insQuery2;
    if (hasDroppedColumns) {
      startIndex1 = 110;
      endIndex1 = 200;
      startIndex2 = 120;
      endIndex2 = 140;
      insQuery1 = "insert into " + custTable
          + " (cid, tid) values (?, ?)";
      insQuery2 = "insert into " + portTable
          + "(cid, sid, availQty, tid) values (?, ?, ?, ?)";
    }
    else {
      if (populateMoreData) {
        startIndex1 = 110;
        endIndex1 = 200;
        startIndex2 = 120;
        endIndex2 = 140;
      }
      else {
        startIndex1 = 0;
        endIndex1 = 100;
        startIndex2 = 5;
        endIndex2 = 20;
      }
      insQuery1 = "insert into " + custTable
          + " (cid, cust_name, addr, tid) values (?, ?, ?, ?)";
      insQuery2 = "insert into " + portTable
          + "(cid, sid, qty, availQty, tid) values (?, ?, ?, ?, ?)";
    }
    PreparedStatement prepStmt = conn.prepareStatement(insQuery1);
    for (int index = startIndex1; index < endIndex1; ++index) {
      prepStmt.setInt(1, index);
      int colIndex = 2;
      if (!hasDroppedColumns) {
        prepStmt.setString(colIndex++, "CUST" + index);
        prepStmt.setString(colIndex++, "ADDR" + index);
      }
      prepStmt.setInt(colIndex, index + 1);
      prepStmt.execute();
    }

    if (skipPort) {
      return;
    }

    prepStmt = conn.prepareStatement(insQuery2);
    int startIdx;
    int endIdx;
    if (portFull) {
      startIdx = startIndex1;
      endIdx = endIndex1;
    }
    else {
      startIdx = startIndex2;
      endIdx = endIndex2;
    }
    for (int index = startIdx; index < endIdx; ++index) {
      prepStmt.setInt(1, index);
      prepStmt.setInt(2, index + 2);
      int colIndex = 3;
      if (!hasDroppedColumns) {
        prepStmt.setInt(colIndex++, index * 10);
      }
      prepStmt.setInt(colIndex++, index * 9);
      prepStmt.setInt(colIndex, tidFk ? (index + 1) : index);
      prepStmt.execute();
    }
  }

  private void createTablesAndPopulateData(Connection conn) throws SQLException {
    createTables(conn);
    populateData(conn, false, false);
  }

  public void testLOBUDTKey_GEMXD18() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema bob");
    try {
      stmt.execute("CREATE TABLE USERS_ROLES(USERID string NOT NULL, "
          + "ROLEID bigint NOT NULL, PRIMARY KEY (USERID,ROLEID))");
      fail("Expected exception for LOB type as primary key");
    } catch (SQLException sqle) {
      if (!"42832".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("CREATE TABLE USERS_ROLES(USERID string NOT NULL UNIQUE, "
          + "ROLEID bigint NOT NULL)");
      fail("Expected exception for LOB type as unique key");
    } catch (SQLException sqle) {
      if (!"42832".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // check UDT disallowed as key
    stmt.execute("CREATE TYPE UDTPrice " +
        "EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    try {
      stmt.execute("CREATE TABLE USERS_TICKETS(USERID string, "
          + "ROLEID bigint NOT NULL, TICKETPRICE UDTPrice UNIQUE)");
      fail("Expected exception for UDT type as unique key");
    } catch (SQLException sqle) {
      if (!"42832".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("CREATE TABLE USERS_TICKETS(USERID string NOT NULL, "
        + "ROLEID bigint NOT NULL, TICKETPRICE UDTPrice)");
    try {
      stmt.execute(
          "CREATE INDEX USERS_TICKETS_PRICE ON USERS_TICKETS(TICKETPRICE)");
      fail("Expected exception for UDT type as index key");
    } catch (SQLException sqle) {
      if (!"X0X67".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    stmt.execute("CREATE TABLE USERS_ROLES(USERID string NOT NULL, "
        + "ROLEID bigint NOT NULL) PARTITION BY COLUMN(USERID,ROLEID)");
    try {
      stmt.execute("CREATE INDEX USERS_ROLES_ID ON USERS_ROLES(USERID)");
      fail("Expected exception for LOB type as index key");
    } catch (SQLException sqle) {
      if (!"42832".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    stmt.execute("CREATE INDEX USERS_ROLES_ROLE ON USERS_ROLES(ROLEID)");

    stmt.execute("insert into users_roles values('two', 3), ('four', 5)");

    ResultSet rs;
    String[][] expected = new String[][]{
        new String[]{"two", "3"},
        new String[]{"four", "5"}
    };
    String[][] expected2 = new String[][]{
        new String[]{"two", "3"}
    };
    rs = stmt.executeQuery("select * from users_roles");
    JDBC.assertUnorderedResultSet(rs, expected);
    rs = stmt.executeQuery("select * from users_roles where roleId > 2");
    JDBC.assertUnorderedResultSet(rs, expected);
    rs = stmt.executeQuery(
        "select * from users_roles where roleId > 1 and roleId < 4");
    JDBC.assertUnorderedResultSet(rs, expected2);
    rs = stmt.executeQuery("select * from users_roles where roleId != 4");
    JDBC.assertUnorderedResultSet(rs, expected);
    rs = stmt.executeQuery("select * from users_roles where roleId != 5");
    JDBC.assertUnorderedResultSet(rs, expected2);

    // next for table having UDT type
    stmt.execute("CREATE INDEX USERS_TICKETS_ROLES ON USERS_TICKETS(ROLEID)");
    PreparedStatement pstmt = conn.prepareStatement(
        "insert into USERS_TICKETS values(?, ?, ?)");
    pstmt.setString(1, "two");
    pstmt.setInt(2, 3);
    pstmt.setObject(3, new UDTPrice(new BigDecimal("1.1"), new BigDecimal("2.2")));
    pstmt.execute();
    pstmt.setString(1, "four");
    pstmt.setInt(2, 5);
    pstmt.setObject(3, new UDTPrice(new BigDecimal("0.1"), new BigDecimal("3.2")));
    pstmt.execute();

    expected = new String[][]{
        new String[]{"two", "3", "highPrice is 2.2 low price is 1.1"},
        new String[]{"four", "5", "highPrice is 3.2 low price is 0.1"}
    };
    expected2 = new String[][]{
        new String[]{"two", "3", "highPrice is 2.2 low price is 1.1"},
    };
    rs = stmt.executeQuery("select * from users_tickets");
    JDBC.assertUnorderedResultSet(rs, expected);
    rs = stmt.executeQuery("select * from users_tickets where roleId > 2");
    JDBC.assertUnorderedResultSet(rs, expected);
    rs = stmt.executeQuery(
        "select * from users_tickets where roleId > 1 and roleId < 4");
    JDBC.assertUnorderedResultSet(rs, expected2);
    rs = stmt.executeQuery("select * from users_tickets where roleId != 4");
    JDBC.assertUnorderedResultSet(rs, expected);
    rs = stmt.executeQuery("select * from users_tickets where roleId != 5");
    JDBC.assertUnorderedResultSet(rs, expected2);
  }

  public void testNPE_43664() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema bob");
    stmt.execute("CREATE TABLE BOB.USERS_ROLES(USERID bigint NOT NULL, "
      + "ROLEID bigint NOT NULL, CONSTRAINT SQL110705151336323 PRIMARY KEY (USERID,ROLEID))");
    stmt.execute("CREATE INDEX IX_C1A01806 ON BOB.USERS_ROLES(USERID)");
    stmt.execute("CREATE INDEX IX_C19E5F31 ON BOB.USERS_ROLES(ROLEID)");
    stmt.execute("CREATE UNIQUE INDEX USERS_ROLES__USERID__ROLEID ON BOB.USERS_ROLES(USERID, ROLEID)");
    stmt.execute("insert into bob.users_roles values(2, 3), (4, 5)");
    stmt.execute("SELECT COUNT(*) AS COUNT_VALUE FROM bob.Users_Roles WHERE userId = 22 AND roleId = 55");
  }

  public void testIndexInfo() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema bob");
    stmt.execute("CREATE TABLE BOB.USERS_ROLES(USERID bigint NOT NULL, "
      + "ROLEID bigint NOT NULL, CONSTRAINT SQL110705151336323 PRIMARY KEY (USERID,ROLEID))");
    stmt.execute("CREATE INDEX IX_C1A01806 ON BOB.USERS_ROLES(USERID)");
    stmt.execute("CREATE INDEX IX_C19E5F31 ON BOB.USERS_ROLES(ROLEID)");
    stmt.execute("CREATE UNIQUE INDEX USERS_ROLES__USERID__ROLEID ON BOB.USERS_ROLES(USERID, ROLEID)");
    stmt.execute("insert into bob.users_roles values(2, 3), (4, 5)");
    stmt.execute("SELECT COUNT(*) AS COUNT_VALUE FROM bob.Users_Roles WHERE userId = 22 AND roleId = 55");
    
    stmt.execute("CREATE UNIQUE INDEX USERS_GLOBAL_USERID ON BOB.USERS_ROLES(USERID)");
    
    stmt.execute("select * from SYS.INDEXES");
    
    ResultSet rs = stmt.getResultSet();
    ResultSetMetaData rsmdata = rs.getMetaData();
    int colCount = rsmdata.getColumnCount();
    StringBuilder sb = new StringBuilder();
    for(int i = 1; i < colCount + 1; i++) {
      sb.append(rsmdata.getColumnName(i));
      if( i != colCount ) {
        sb.append(", ");
      }
    }
    System.out.print(sb.toString());
    System.out.println("\n---------------------------------------------------------------------------------\n");
    int cnt = 0;
    while(rs.next()) {
      cnt++;
      System.out.println
      (rs.getString(1) + " | " + rs.getString(2) + " | " + 
      rs.getString(3)  + " | " + rs.getString(4) + " | " + rs.getString(5) + " | " + rs.getString(6));
    }
    assertEquals(5, cnt);
  }
  
  public void testTriggers_1() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table flights(flight_id int not null,"
        + " segment_number int not null, aircraft varchar(20) not null) replicate");

    stmt.execute("create table flights_history(flight_id int not null,"
        + " aircraft varchar(20) not null, status varchar(50) not null)");
    
    String triggerStmnt = "CREATE TRIGGER trig1 " + "AFTER INSERT ON flights "
        + "REFERENCING NEW AS NEWROW "
        + "FOR EACH ROW MODE DB2SQL " + "INSERT INTO flights_history VALUES "
        + "(NEWROW.FLIGHT_ID, NEWROW.AIRCRAFT, " + "'INSERTED FROM trig1')";

    stmt.execute(triggerStmnt);

    stmt
        .execute("insert into flights values (1, 10, 'delta'), (2, 10, 'palta'), (3, 5, 'delta'), (4, 20, 'lalta')");

    triggerStmnt = "CREATE TRIGGER trig2 " + "AFTER UPDATE ON flights "
        + "REFERENCING OLD AS UPDATEDROW " + "FOR EACH ROW MODE DB2SQL "
        + "INSERT INTO flights_history VALUES "
        + "(UPDATEDROW.FLIGHT_ID, UPDATEDROW.AIRCRAFT, "
        + "'INSERTED FROM trig2')";
    // "values app.MYNOTIFYEMAIL('Jerry', 'Table x is about to be updated')";
    // "CALL NOTIFYEMAIL('kneeraj', 'email noti')";
    stmt.execute(triggerStmnt);

    stmt
        .execute("update flights set segment_number = 10 where segment_number = 20");

    stmt
        .execute("update flights set segment_number = 10 where segment_number = 5");

    triggerStmnt = "CREATE TRIGGER trig3 " + "AFTER DELETE ON flights "
    + "REFERENCING OLD AS DELETEDROW " + "FOR EACH ROW MODE DB2SQL "
    + "INSERT INTO flights_history VALUES "
    + "(DELETEDROW.FLIGHT_ID * 1000, DELETEDROW.AIRCRAFT, "
    + "'INSERTED BY trig3')";
    
    stmt.execute(triggerStmnt);
    
    triggerStmnt = "CREATE TRIGGER trig4 " + "AFTER DELETE ON flights "
    + "REFERENCING OLD AS DELETEDROW " + "FOR EACH ROW MODE DB2SQL "
    + "INSERT INTO flights_history VALUES "
    + "(DELETEDROW.FLIGHT_ID + 100, DELETEDROW.AIRCRAFT, "
    + "'INSERTED BY trig4')";
    
    stmt.execute(triggerStmnt);
    
    stmt.execute("delete from flights where aircraft = 'palta'");
    
    stmt.execute("select * from flights_history");
    System.out.println("\n\n");
    ResultSet rs = stmt.getResultSet();
    while (rs.next()) {
      System.out.println(rs.getObject(1) + ", " + rs.getObject(2) + ", "
          + rs.getObject(3));
    }
    
    stmt.execute("drop trigger trig1");
    stmt.execute("drop trigger trig2");
    stmt.execute("drop trigger trig3");
    stmt.execute("drop trigger trig4");
  }
  
  public static void sysoutmethod(Integer flight_id) {
    System.out.println("RowTriggers: the flight id: "+flight_id);
  }
  
  public static void sysoutStmntmethod(Integer flight_id) {
    System.out.println("StatementTriggers: the flight id: "+flight_id);
  }
  
  public void testTriggers_2() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table flights(flight_id int not null," +
                " segment_number int not null, aircraft varchar(20) not null)");
    
    stmt.execute("insert into flights values (1, 10, 'delta'), (2, 10, 'palta'), (3, 20, 'delta'), (4, 20, 'lalta')");
        
    stmt.execute("create procedure sysoutFLTIdProc( IN flt_id INT) " +
        "LANGUAGE JAVA PARAMETER STYLE JAVA  " +
        "EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.CreateTableTest.sysoutmethod(java.lang.Integer)'");

    String triggerStmnt = "CREATE TRIGGER syso AFTER UPDATE ON flights "
        + "REFERENCING OLD AS UPDATEDROW FOR EACH ROW MODE DB2SQL"
        + " call sysoutFLTIdProc(UPDATEDROW.flight_id)";
    
    stmt.execute(triggerStmnt);
    stmt.execute("update flights set flight_id = 100");
    stmt.execute("drop trigger syso");
  }
  
  public void DEBUGtestTriggers_3() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table flights(flight_id int not null," +
                " segment_number int not null, aircraft varchar(20) not null) replicate");
    
    stmt.execute("insert into flights values (1, 10, 'delta'), (2, 10, 'palta'), (3, 20, 'delta'), (4, 20, 'lalta')");
        
    stmt.execute("create procedure sysoutFLTIdProc( IN flt_id INT) " +
        "LANGUAGE JAVA PARAMETER STYLE JAVA  " +
        "EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.CreateTableTest.sysoutmethod(java.lang.Integer)'");

    stmt.execute("create procedure sysoutFLTIdProc_stmnt( IN flt_id INT) " +
        "LANGUAGE JAVA PARAMETER STYLE JAVA  " +
        "EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.CreateTableTest.sysoutStmntmethod(java.lang.Integer)'");
    
    String triggerStmnt = "CREATE TRIGGER syso AFTER UPDATE ON flights "
        + "REFERENCING OLD AS UPDATEDROW FOR EACH ROW MODE DB2SQL"
        + " call sysoutFLTIdProc(UPDATEDROW.flight_id)";
    
    stmt.execute(triggerStmnt);
    
    triggerStmnt = "CREATE TRIGGER syso_stmnt AFTER UPDATE ON flights "
      + "FOR EACH STATEMENT MODE DB2SQL"
      + " call sysoutFLTIdProc_stmnt(5555)";
    
    stmt.execute(triggerStmnt);
    
    for(int i=0; i<5; i++) {
      stmt.execute("update flights set flight_id = flight_id+100");
      System.out.println("====================================================\n");
    }
    
    stmt.execute("select * from flights");
    System.out.println("\n\n");
    ResultSet rs = stmt.getResultSet();
    while (rs.next()) {
      System.out.println(rs.getObject(1) + ", " + rs.getObject(2) + ", "
          + rs.getObject(3));
    }
  }
  
  public void DEBUGtestTriggers_triggerBeforeTrig() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table x (x int, constraint ck check (x > 0))");
    stmt.execute("create table unrelated (x int, constraint ckunrelated check (x > 0))");
    stmt.execute("create index x on x(x)");
    
    stmt.execute("values 1");

    //stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement drop table x");

    //stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement drop index x");

//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement alter table x add column y int");
//
//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement alter table x add constraint ck2 check(x > 0)");
//
//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement alter table x drop constraint ck");
//
//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement create index x2 on x (x)");
//
//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement create index xunrelated on unrelated(x)");
//
//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement drop index xunrelated"); 
//
//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement drop trigger tbad");
//
//    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement"); 
//    stmt.execute("create trigger tbad2 NO CASCADE before insert on x for each statement values 1");
//
    //stmt.execute("create trigger tokv1 NO CASCADE before insert on x for each statement values 1");
    stmt.execute("create trigger tokv1 NO CASCADE before insert on x for each statement values 1");
    stmt.execute("insert into x values 1");
    stmt.execute("select * from x");
    stmt.execute("drop trigger tokv1");

    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement set isolation to rr");
    stmt.execute("create trigger tbad NO CASCADE before insert on x for each statement lock table x in share mode");


    stmt.execute("create trigger tbadX NO CASCADE before insert on x for each statement insert into x values 1");

    stmt.execute("create trigger tbadX NO CASCADE before insert on x for each statement delete from x");

    stmt.execute("create trigger tbadX NO CASCADE before insert on x for each statement update x set x = x");


    stmt.execute("create trigger t1 NO CASCADE before update on x referencing badtoken as oldtable for each row values 1");
    stmt.execute("create trigger t1 NO CASCADE before update on x referencing old as oldrow new for each row values 1");
    stmt.execute("create trigger t1 NO CASCADE before update on x referencing old as oldrow " +
    		"new as newrow old as oldrow2 for each row values 1");
    stmt.execute("create trigger t1 NO CASCADE before update on x referencing new as newrow new as newrow2 " +
    		"old as oldrow2 for each row values 1");


    stmt.execute("create trigger t1 NO CASCADE before update on x referencing new_table as newtab for each row values 1");
    stmt.execute("create trigger t1 NO CASCADE before update on x referencing new as newrow for each statement values 1");


    stmt.execute("create trigger t1 NO CASCADE before update on x referencing old_table as old for each row select * from old");
    stmt.execute("create trigger t1 NO CASCADE before update on x referencing old_table as old for each statement values old.x");
    
    stmt.execute("create trigger t1 NO CASCADE before update on x referencing old_table as oldtable for each statement select * from old");
    stmt.execute("create trigger t1 NO CASCADE before update on x referencing old as oldtable for each row values old.x");

    stmt.execute("create table y (x int)");
    stmt.execute("create trigger t1 NO CASCADE before insert on x referencing new_table as newrowtab " +
    		"for each statement insert into y select x from newrowtab");

    stmt.execute("drop table x");
    stmt.execute("drop table y");
  }

  public void testExpirationFK_40581() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    boolean gotex = false;
    stmt.execute("CREATE TABLE FLIGHTS (FLIGHT_ID CHAR(6) NOT NULL "
        + ", SEGMENT_NUMBER INTEGER NOT NULL , ORIG_AIRPORT CHAR(3), "
        + "DEPART_TIME TIME, DEST_AIRPORT CHAR(3), ARRIVE_TIME TIME, "
        + "MEAL CHAR(1), FLYING_TIME DOUBLE PRECISION, MILES INTEGER, "
        + "AIRCRAFT VARCHAR(6), CONSTRAINT FLIGHTS_PK PRIMARY KEY "
        + "(FLIGHT_ID, SEGMENT_NUMBER), CONSTRAINT MEAL_CONSTRAINT CHECK "
        + "(meal IN ('B', 'L', 'D', 'S'))) PARTITION BY COLUMN (FLIGHT_ID) "
        + "EXPIRE ENTRY WITH TIMETOLIVE 60 ACTION DESTROY");
    try {
      stmt.execute("create table fltavailability(id char(6), "
          + "s_num integer not null, dummy_col int, "
          + "constraint cust_fk foreign key (id, s_num) references flights "
          + "(flight_id, Segment_number) on delete restrict)");
      fail("expected exception for FK with expiration");
    } catch (SQLException se) {
      gotex = true;
      assertEquals("X0Y99", se.getSQLState());
    }
    assertTrue(gotex);
  }

  private static int numHashIndexLookups;
  public void testBatchPrepStatementAndGetAll() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table employees(val int not null primary key, "
        + "name varchar(20)) ");
    PreparedStatement pstmt = conn
        .prepareStatement("INSERT INTO employees VALUES (?, ?)");
    pstmt.setInt(1, 2000);
    pstmt.setString(2, "Kelly Kaufmann");
    pstmt.addBatch();

    pstmt.setInt(1, 3000);
    pstmt.setString(2, "Bill Barnes");
    pstmt.addBatch();

    pstmt.executeBatch();

    ResultSet rs;
    BulkGetObserver bgos = new BulkGetObserver();
    GemFireXDQueryObserverHolder.putInstance(bgos);
    // observer for Hash1Index opens and lookups
    numHashIndexLookups = 0;
    GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
      boolean hashIndexOpened;

      @Override
      public void scanControllerOpened(Object sc,
          Conglomerate conglom) {
        if (sc instanceof MemScanController) {
          numHashIndexLookups = 0;
          this.hashIndexOpened = false;
          if (sc instanceof Hash1IndexScanController) {
            this.hashIndexOpened = true;
          }
        }
      }

      @Override
      public void beforeInvokingContainerGetTxRowLocation(RowLocation rl) {
        if (this.hashIndexOpened) {
          numHashIndexLookups++;
        }
      }
    });

    // check for prepared statement
    numHashIndexLookups = 0;
    pstmt = conn
        .prepareStatement("select * from employees where val in (?, ?, ?)");
    pstmt.setInt(1, 3000);
    pstmt.setInt(2, 2000);
    pstmt.setInt(3, 4000);
    rs = pstmt.executeQuery();
    int numRows = 0;
    while (rs.next()) {
      if (rs.getInt(1) == 2000) {
        assertEquals("Kelly Kaufmann", rs.getString(2));
      }
      else {
        assertEquals(3000, rs.getInt(1));
        assertEquals("Bill Barnes", rs.getString(2));
      }
      numRows++;
    }
    assertEquals(2, numRows);
    assertEquals(3, bgos.getBatchSize());
    assertEquals(0, numHashIndexLookups);

    // check for unprepared statement
    numHashIndexLookups = 0;
    stmt.execute("select * from employees where val in (1000, 2000, 3000)");
    rs = stmt.getResultSet();
    numRows = 0;
    while (rs.next()) {
      if (rs.getInt(1) == 2000) {
        assertEquals("Kelly Kaufmann", rs.getString(2));
      }
      else {
        assertEquals(3000, rs.getInt(1));
        assertEquals("Bill Barnes", rs.getString(2));
      }
      numRows++;
    }
    assertEquals(2, numRows);
    assertEquals(3, bgos.getBatchSize());
    assertEquals(0, numHashIndexLookups);

    // however, with a loader installed on the table, it should GFE activation
    // with gets in a loop
    numHashIndexLookups = 0;
    GfxdCallbacksTest.addLoader(null, "employees",
        "com.pivotal.gemfirexd.jdbc.TestRowLoader", "");
    // check for prepared statement
    pstmt = conn
        .prepareStatement("select * from employees where val in (?, ?, ?, ?)");
    pstmt.setInt(1, 3000);
    pstmt.setInt(2, 2000);
    pstmt.setInt(3, 1000);
    pstmt.setInt(4, 4000);
    rs = pstmt.executeQuery();
    numRows = 0;
    while (rs.next()) {
      if (rs.getInt(1) == 2000) {
        assertEquals("Kelly Kaufmann", rs.getString(2));
      }
      else if (rs.getInt(1) == 1000) {
        assertEquals("Mark Black", rs.getString(2));
      }
      else {
        assertEquals(3000, rs.getInt(1));
        assertEquals("Bill Barnes", rs.getString(2));
      }
      numRows++;
    }
    assertEquals(3, numRows);
    assertEquals(0, numHashIndexLookups);
    // check for unprepared statement
    stmt.execute("select * from employees where val in "
        + "(2000, 3000, 4000, 1000)");
    rs = stmt.getResultSet();
    numRows = 0;
    while (rs.next()) {
      if (rs.getInt(1) == 2000) {
        assertEquals("Kelly Kaufmann", rs.getString(2));
      }
      else if (rs.getInt(1) == 1000) {
        assertEquals("Mark Black", rs.getString(2));
      }
      else {
        assertEquals(3000, rs.getInt(1));
        assertEquals("Bill Barnes", rs.getString(2));
      }
      numRows++;
    }
    assertEquals(3, numRows);
    assertEquals(0, numHashIndexLookups);

    GemFireXDQueryObserverHolder.clearInstance();
  }

  public void testBatchStatement_NoPutAll() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    BatchInsertObserver bos = new BatchInsertObserver();
    GemFireXDQueryObserverHolder.setInstance(bos);

    stmt
        .execute("create table employees(val int not null primary key, name varchar(20)) ");

    stmt.addBatch("INSERT INTO employees VALUES (2000, 'Kelly')");
    stmt.addBatch("INSERT INTO employees VALUES (3000, 'Jelly')");
    stmt.addBatch("INSERT INTO employees VALUES (4000, 'Belly')");

    stmt.executeBatch();
    
    assertEquals(bos.getBatchSize(), 0);
  }

  
  public void DEBUGtestOuterJoinMultipleTables_ForDebuggingOuterJoin1() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table bdg(name varchar(30), bid int not null) replicate");
    s.execute("create table res(person varchar(30), bid int not null, rid int not null)");
    s.execute("create table dom(domain varchar(30), rid int not null, did int not null)");
    
    s.execute("insert into bdg values('404', 1), ('405', 2)");
    s.execute("insert into res values('graham', 1, 101), ('lisa', 1, 102)");
    s.execute("insert into dom values('www.grahamellis.co.uk', 101, 201), ('www.sheepbingo.co.uk', 101, 202 )");
    
    s.execute("select * from (bdg left join res on bdg.bid = res.bid) left join dom on res.rid = dom.rid");
    ResultSet rs = s.getResultSet();
    while(rs.next()) {
      System.out.println("rs.colname: " + rs.getMetaData() + " and value: "
          + rs.getObject(1) + ", " + rs.getObject(2) + ", " + rs.getObject(3)
          + ", " + rs.getObject(4) + ", " + rs.getObject(5) + ", "
          + rs.getObject(6) + ", " + rs.getObject(7) + ", " + rs.getObject(8));
    }
  }
  
  public void DEBUGtestTMP_ForDebuggingOuterJoinBug41560() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
   
    s.execute(" create table trade.customers (cid int not null, cust_name varchar(100), " +
    		" addr varchar(100), tid int, primary key (cid))  replicate");
   
    s.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, " +
    		" exchange varchar(10) not null, " +
    		"tid int, constraint sec_pk primary key (sec_id), " +
    		"constraint sec_uq unique (symbol, exchange), " +
    		"constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate");
  
    s.execute(" create table trade.portfolio (cid int not null, sid int not null, " +
    		"qty int not null, availQty int not null, " +
    		"tid int, constraint portf_pk primary key (cid, sid), " +
    		"constraint cust_fk foreign key (cid) references trade.customers (cid) on delete restrict, " +
    		"constraint sec_fk foreign key (sid) references trade.securities (sec_id), " +
    		"constraint qty_ck check (qty>=0), " +
    		"constraint avail_ch check (availQty>=0 and availQty<=qty))  partition by column (cid)");
  
//    s.execute("create table trade.sellorders (oid int not null constraint orders_pk primary key, " +
//    		"cid int, sid int, qty int, " +
//    		"status varchar(10) default 'open', tid int, " +
//    		"constraint portf_fk foreign key (cid, sid) references trade.portfolio (cid, sid) on delete restrict, " +
//    		"constraint status_ch check (status in ('cancelled', 'open', 'filled')))  " +
//    		"partition by column (cid) colocate with (trade.portfolio)");
    
    s.execute("create table trade.sellorders (oid int not null constraint orders_pk primary key, " +
        "cid int, sid int, qty int, " +
        "status varchar(10) default 'open', tid int, " +
        "constraint status_ch check (status in ('cancelled', 'open', 'filled')))  " +
        "partition by column (cid) colocate with (trade.portfolio)");
    
    s.execute("insert into trade.customers values " +
        "(1, 'name1', 'addr1', 1), (2, 'name2', 'addr2', 2), (3, 'name3', 'addr3', 3) ");
    
    s.execute("insert into trade.securities values (1, 'sym1', 'nasdaq', 1), " +
        "(2, 'sym2', 'nye', 2), (3, 'sym3', 'lse', 3)");
    
    s.execute("insert into trade.portfolio values (1, 1, 100, 10, 1 ), " +
    "(2, 2, 200, 20, 2 ), (3, 3, 300, 30, 3 )");
    
    s.execute("insert into trade.sellorders values (1, 1, 1, 1, 'open', 1), " +
    "(2, 2, 2, 2, 'open', 2), (3, 3, 3, 3, 'open', 3)");
    
    s.execute("select * from trade.customers c LEFT OUTER JOIN trade.portfolio f " +
    		"LEFT OUTER JOIN trade.sellorders so on f.cid = so.cid on c.cid= f.cid where f.tid = 1");
    
    ResultSet rs = s.getResultSet();
    while(rs.next()) {
    }
  }
  
  public void DEBUGtestTMP_ForDebuggingOuterJoin2() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table emp.EMPLOYEE(lastname varchar(30), depId int) " +
                "partition by (depId)");
    s.execute("create table emp.DEPT(deptname varchar(30), depId int) " +
                "replicate");
    s.execute("insert into emp.employee values " +
        "('Jones', 33), ('Rafferty', 31), " +
        "('Robinson', 34), ('Steinberg', 33), " +
        "('Smith', 34), ('John', null)");
    s.execute("insert into emp.dept values ('sales', 31), " +
        "('engineering', 33), ('clerical', 34), ('marketing', 35)");
    s.execute("SELECT emp.Employee.LastName as lname, " +
      "emp.Employee.DepID as did1, " +
      "emp.Dept.DeptName as depname, " +
      "emp.Dept.DepID as did2" +
      "  FROM emp.employee  "
      + "RIGHT OUTER JOIN emp.dept ON emp.employee.depID = emp.dept.DepID");
    ResultSet rs = s.getResultSet();
    while(rs.next()) {
    }
  }
  
  public void DEBUGtestOuterJoinMultipleTables_ForDebuggingOuterJoin3() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table bdg(name varchar(30), bid int not null) replicate");
    s.execute("create table res(person varchar(30), bid int not null, rid int not null)");
    s.execute("create table dom(domain varchar(30), bid int not null, rid int not null)");
    
    s.execute("insert into bdg values('404', 1), ('405', 2)");
    s.execute("insert into res values('graham', 1, 101), ('lisa', 1, 102)");
    s.execute("insert into dom values('www.grahamellis.co.uk', 1, 101), ('www.sheepbingo.co.uk', 2, 102 )");
    
    s.execute("select * from " +
    		"bdg left outer join " +
    		"     res left outer join dom on res.rid = dom.rid " +
    		"on bdg.bid = res.bid");
    ResultSet rs = s.getResultSet();
    while(rs.next()) {
      System.out.println("rs.colname: " + rs.getMetaData() + " and value: "
          + rs.getObject(1) + ", " + rs.getObject(2) + ", " + rs.getObject(3)
          + ", " + rs.getObject(4) + ", " + rs.getObject(5) + ", "
          + rs.getObject(6) + ", " + rs.getObject(7) + ", " + rs.getObject(8));
    }
  }
  
  public void testOuterJoin_RRPRRR() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    
    s.execute("create table bdg(name varchar(30), bid int not null) replicate");
    s.execute("create table res(person varchar(30), bid int not null, rid int not null) " +
                "partition by (bid)");
    s.execute("create table dom(domain varchar(30), bid int not null, rid int not null) " +
                "replicate");
    s.execute("insert into bdg values('404', 1), ('405', 2)");
    s.execute("insert into res values('graham', 1, 101), ('lisa', 1, 102)");
    s.execute("insert into dom values('www.grahamellis.co.uk', 1, 101), ('www.sheepbingo.co.uk', 2, 102 )");
    String ojQuery1 = "select * from " +
                "bdg left outer join " +
                "     res left outer join dom on res.bid = dom.bid " +
                "on bdg.bid = res.bid";
    s.execute(ojQuery1);
    ResultSet rs = s.getResultSet();
    while(rs.next()) {
      System.out.println("rs.colname: " + rs.getMetaData() + " and value: "
          + rs.getObject(1) + ", " + rs.getObject(2) + ", " + rs.getObject(3)
          + ", " + rs.getObject(4) + ", " + rs.getObject(5) + ", "
          + rs.getObject(6) + ", " + rs.getObject(7) + ", " + rs.getObject(8));
    }
  }
  
  public void testOuterJoin() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table emp.EMPLOYEE(name varchar(30) primary key, depId int) replicate");
    s.execute("create table emp.DEPT(deptname varchar(30), depId int primary key)");
    s.execute("insert into emp.employee values " +
    		"('jones', 33), ('rafferty', 31), " +
    		"('Robinson', 34), ('Steinberg', 33), " +
    		"('Smith', 34), ('John', null)");
    s.execute("insert into emp.dept values ('sales', 31), " +
    		"('engineering', 33), ('clerical', 34), ('marketing', 35)");
    s.execute("SELECT *" +
        "  FROM emp.employee "
        + "LEFT OUTER JOIN emp.dept ON emp.employee.depID = emp.dept.DepID");
    ResultSet rs = s.getResultSet();
    while(rs.next()) {
      System.out.println("rs.colname: " + rs.getMetaData() + " and value: "
          + rs.getObject(1) + ", " + rs.getObject(2) + ", " + rs.getObject(3)
          + ", " + rs.getObject(4));
    }
  }
  
  public void testMultipleInsertOne() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table EMP.PARTITIONTESTTABLE_PARENT (ID int primary key,"
        + " SECONDID int , THIRDID int ) replicate");
    s.execute("create index IDX on EMP.PARTITIONTESTTABLE_PARENT(THIRDID)");
    s.execute("insert into EMP.PARTITIONTESTTABLE_PARENT values(1,2,4)");
    boolean gotException = false;
    addExpectedException(new Object[] { EntryExistsException.class,
        "SQLIntegrityConstraintViolationException" });
    try {
      s.execute("insert into EMP.PARTITIONTESTTABLE_PARENT values(1,2,3), (5,6,7), (8,9,10)");
    }
    catch(Exception e) {
      gotException = true;
    }
    assertTrue(gotException);
    removeExpectedException(new Object[] { EntryExistsException.class,
        "SQLIntegrityConstraintViolationException" });
    s.execute("select ID, SECONDID, THIRDID from EMP.PARTITIONTESTTABLE_PARENT");
    ResultSet rs = s.getResultSet();
    while(rs.next()) {
      System.out.println(rs.getInt(1)+", "+rs.getInt(2)+", "+rs.getInt(3));
    }
    rs.close();
    //s.execute("insert into EMP.PARTITIONTESTTABLE_PARENT select 1,2,3 union all select 3,4,5");
    //insert into t1 (smallintcol) values 1 union all values 2
    s.execute("create table EMP.PARTITIONTESTTABLE (ID int primary key,"
        + " SECONDID int , THIRDID int )");
    s.execute("insert into EMP.PARTITIONTESTTABLE select * from EMP.PARTITIONTESTTABLE_PARENT");
    s.execute("select ID, SECONDID, THIRDID from EMP.PARTITIONTESTTABLE");
    rs = s.getResultSet();
    while(rs.next()) {
      System.out.println(rs.getInt(1)+", "+rs.getInt(2)+", "+rs.getInt(3));
    }
    rs.close();
  }
  
  // Uncomment after fixing 40862
  public void testBug40862() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table EMP.PARTITIONTESTTABLE_PARENT (ID int not null,"
        + " SECONDID int not null, THIRDID int not null ) partition by column(SECONDID)");
    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null ) partition by column (SECONDID) " 
        + "colocate with (EMP.PARTITIONTESTTABLE_PARENT)");
    s.execute("alter table EMP.PARTITIONTESTTABLE_PARENT add constraint cust_pk_parent primary key (SECONDID)");
    s.execute("alter table EMP.PARTITIONTESTTABLE add constraint cust_pk primary key (SECONDID)");
    
    s.execute("drop table EMP.PARTITIONTESTTABLE");
    s.execute("drop table EMP.PARTITIONTESTTABLE_PARENT");
    s.execute("create table EMP.PARTITIONTESTTABLE_PARENT (ID int not null,"
        + " SECONDID int not null, THIRDID int not null ) partition by column(SECONDID)");
    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null ) partition by column (SECONDID) " 
        + "colocate with (EMP.PARTITIONTESTTABLE_PARENT)");
    s.execute("alter table EMP.PARTITIONTESTTABLE_PARENT add constraint cust_pk_parent primary key (SECONDID)");
    s.execute("alter table EMP.PARTITIONTESTTABLE add constraint cust_pk primary key (SECONDID)");
    
    s.execute("drop table EMP.PARTITIONTESTTABLE");
    s.execute("drop table EMP.PARTITIONTESTTABLE_PARENT");
    
  }

  public void testGfxdRowLoaderNullSchema() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table PARTITIONTESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");

    GfxdCallbacksTest.addLoader(null, "PARTITIONTESTTABLE",
        "com.pivotal.gemfirexd.ddl.GfxdTestRowLoader", "param1");

    s.execute("select * from partitiontesttable where id = 1");
    ResultSet rs = s.getResultSet();
    while (rs.next()) {
      assertEquals(1, rs.getInt(1));
      assertEquals(1, rs.getInt(2));
      assertEquals(1, rs.getInt(3));
    }
  }
  
  public void testGfxdRowLoaderParams() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");

    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");
    try {
      GfxdCallbacksTest
          .addLoader(
              "EMP",
              "PARTITIONTESTTABLE",
              "com.pivotal.gemfirexd.ddl.GfxdTestRowLoaderNoExists",
              "param1");
      fail("expected exception");
    } catch (SQLException ex) {
      // TODO add proper exception handling
    }
    GfxdCallbacksTest
    .addLoader(
        "EMP",
        "PARTITIONTESTTABLE",
        "com.pivotal.gemfirexd.ddl.GfxdTestRowLoader",
        "param1");
    
    s.execute("select * from emp.partitiontesttable where id = 1");
    ResultSet rs = s.getResultSet();
    while (rs.next()) {
      assertEquals(1, rs.getInt(1));
      assertEquals(1, rs.getInt(2));
      assertEquals(1, rs.getInt(3));
    }
    Cache cache = CacheFactory.getAnyInstance();
    Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");
    GfxdCacheLoader ldr = (GfxdCacheLoader)regtwo.getAttributes().getCacheLoader();
    GfxdTestRowLoader tldr = (GfxdTestRowLoader)ldr.getRowLoader();
    String params = tldr.getParams();
    assertEquals(params, "param1");
    
    s.execute("drop table emp.partitiontesttable");
    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");
    
    GfxdCallbacksTest
    .addLoader(
        "EMP",
        "PARTITIONTESTTABLE",
        "com.pivotal.gemfirexd.ddl.GfxdTestRowLoader",
        null);
    
    s.execute("select * from emp.partitiontesttable where id = 1");
    rs = s.getResultSet();
    while (rs.next()) {
      assertEquals(1, rs.getInt(1));
      assertEquals(1, rs.getInt(2));
      assertEquals(1, rs.getInt(3));
    }
    cache = CacheFactory.getAnyInstance();
    regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");
    ldr = (GfxdCacheLoader)regtwo.getAttributes().getCacheLoader();
    tldr = (GfxdTestRowLoader)ldr.getRowLoader();
    assertNull(tldr.getParams());
  }
  
  public void testBug40692() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");

    s.execute("create table EMP.TESTTABLE (ID int not null, "
        + "SECONDID int not null, THIRDID int not null, primary key (ID))");

    s.execute("create table EMP.TESTTABLE_FK (ID_FK int not null, "
        + "SECONDID_2 int not null, THIRDID_2 int not null, "
        + "foreign key (ID_FK) references EMP.TESTTABLE(ID))");

    s.execute("create table EMP.TESTTABLEwithEviction (ID int not null, "
        + "SECONDID int not null, THIRDID int not null, primary key (ID)) "
        + "EVICTION BY LRUCOUNT 100 EVICTACTION DESTROY");
    boolean gotException = false;
    try {
      s
          .execute("create table EMP.TESTTABLEwithEviction_FK (ID_FK int not null, "
              + "SECONDID_2 int not null, THIRDID_2 int not null, "
              + "foreign key (ID_FK) references EMP.TESTTABLEwithEviction(ID))");
    } catch (SQLException e) {
      if ("X0Y99".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    gotException = false;

    s
        .execute("create table EMP.TESTTABLE_EXPR (ID int not null, "
            + "SECONDID int not null, THIRDID int not null, primary key (ID)) " +
            		"EXPIRE ENTRY WITH TIMETOLIVE 1000 ACTION DESTROY");
    try {
      s.execute("create table EMP.TESTTABLE_EXPR_FK (ID_FK int not null, "
          + "SECONDID_2 int not null, THIRDID_2 int not null, "
          + "foreign key (ID_FK) references EMP.TESTTABLE_EXPR(ID))");
    } catch (SQLException e) {
      if ("X0Y99".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    // INVALIDATE keyword no longer in grammar - test removed
  }

  public void testBug40641() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    boolean gotException = false;
    addExpectedException(IllegalStateException.class);
    try {
      s.execute(
            "CREATE TABLE AIRLINES(AIRLINE CHAR(2) NOT NULL CONSTRAINT "
          + "AIRLINES_PK PRIMARY KEY, " + "AIRLINE_FULL VARCHAR(24), "
          + "BASIC_RATE DOUBLE PRECISION, "
          + "DISTANCE_DISCOUNT DOUBLE PRECISION, "
          + "BUSINESS_LEVEL_FACTOR DOUBLE PRECISION, "
          + "FIRSTCLASS_LEVEL_FACTOR DOUBLE PRECISION, "
          + "ECONOMY_SEATS INTEGER, " + "BUSINESS_SEATS INTEGER, "
          + "FIRSTCLASS_SEATS INTEGER) "
          + "REPLICATE EVICTION BY LRUCOUNT 100 EVICTACTION DESTROY");
    } catch (SQLException e) {
      gotException = true;
    }
    assertTrue(gotException);
  }

  /**
   * Test for proper locking with DDL and DML mix.
   */
  public void testDDLDML() throws SQLException {

    // reduce lock timeout for this test
    Properties props = new Properties();
    props.setProperty(GfxdConstants.MAX_LOCKWAIT, "10000");

    // Create a schema
    Connection conn = getConnection(props);
    if (conn.getTransactionIsolation() == Connection.TRANSACTION_NONE) {
      conn.setAutoCommit(false);  
    }

    PreparedStatement prepStmt = conn.prepareStatement("create schema trade");
    prepStmt.execute();

    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid))");
    prepStmt.execute();

    prepStmt = conn.prepareStatement("create index tid_idx on "
        + "trade.customers (TID)");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("drop index trade.tid_idx");
    prepStmt.execute();
    conn.createStatement().execute("drop table trade.customers");

    createTablesAndPopulateData(conn);
    prepStmt = conn.prepareStatement("update trade.customers set tid = 200 "
        + "where cid = 1");
    prepStmt.execute();

    // check successful lock and unlock without explicit ResultSet.close()
    // consuming all results
    Connection conn2 = DriverManager.getConnection(getProtocol());
    conn2.setAutoCommit(false);
    PreparedStatement prepStmt2 = conn2
        .prepareStatement("drop table trade.customers");
    prepStmt = conn.prepareStatement("drop table trade.portfolio");
    conn.createStatement().execute("delete from trade.customers where cid=2");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("select * from trade.customers");
    prepStmt.execute();
    ResultSet rs = prepStmt.getResultSet();
    for (int i = 0; i < 20 && rs.next(); ++i) {
      getLogger().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
    }
    while (rs.next()) {
      getLogger().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
    }
    prepStmt2.execute();

    // check successful lock and unlock with ResultSet.close()
    createTablesAndPopulateData(conn);

    prepStmt2 = conn2.prepareStatement("drop table trade.customers");
    prepStmt = conn.prepareStatement("drop table trade.portfolio");
    conn.createStatement().execute("delete from trade.customers where cid=2");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("select * from trade.customers");
    prepStmt.execute();
    rs = prepStmt.getResultSet();
    for (int i = 0; i < 20 && rs.next(); ++i) {
      getLogger().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
    }
    rs.close();
    prepStmt2.execute();

    // check lock timeout with no ResultSet.close() and not consuming all
    // results
    createTablesAndPopulateData(conn);

    prepStmt2 = conn2.prepareStatement("drop table trade.customers");
    prepStmt = conn.prepareStatement("drop table trade.portfolio");
    conn.createStatement().execute("delete from trade.customers where cid=2");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("select * from trade.customers");
    prepStmt.execute();
    rs = prepStmt.getResultSet();
    for (int i = 0; i < 20 && rs.next(); ++i) {
      getLogger().info(
          "Got CID: " + rs.getObject(1) + ", TID: " + rs.getObject(4));
    }
    addExpectedException(TimeoutException.class);
    try {
      prepStmt2.execute();
      fail("expected to throw a timeout exception");
    } catch (SQLException ex) {
      if (!"40XL1".equals(ex.getSQLState())) {
        throw ex;
      }
    } finally {
      removeExpectedException(TimeoutException.class);
    }
  }

  public void testBug40352() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))   partition by range (cid) "
            + "( VALUES BETWEEN 0.0 AND 99.0, VALUES BETWEEN 101.0 AND 111.02)");
 
    GfxdPartitionResolver spr = (GfxdPartitionResolver)Misc.getGemFireCache()
        .getRegion("/TRADE/CUSTOMERS").getAttributes().getPartitionAttributes()
        .getPartitionResolver();
    
    spr.getRoutingObjectsForRange(new SQLInteger(1), true, new SQLInteger(2), true);
  }
  
//  In this test, table customers is range partitioned on its primary key (cid),
//  and buyorders has cid field reference to customers (buyorders first foreign key).
//  By default colocation/partition, buyorders should be partitioned on its cid field (to achieve colocation),
//  therefore the sid field in buyorders is neither partition field nor primary key field. It should not throw the exception.
//  This test confirms that the above does not happen. Before the fix this test was failing.
  public void testBug40024() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.customers (cid int not null, cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))   partition by range (cid) "
            + "( VALUES BETWEEN 0 AND 999, VALUES BETWEEN 1000 AND 1102, VALUES BETWEEN 1103 AND 1250, "
            + "VALUES BETWEEN 1251 AND 1677, VALUES BETWEEN 1678 AND 10000)");

    s
        .execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null, "
            + "price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id), "
            + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check "
            + "(exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))");

    s
        .execute("create table trade.buyorders(oid int not null constraint buyorders_pk primary key, cid int, "
            + "sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10), "
            + "tid int, constraint bo_cust_fk foreign key (cid) references trade.customers (cid), "
            + "constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id) on delete restrict, "
            + "constraint bo_qty_ck check (qty>=0))");

    s.execute("update trade.buyorders set sid = sid - 10 where cid = 1");

  }

   public void testCreateTable() throws SQLException, StandardException {
   // Create a schema
   Connection conn = getConnection();
   Statement s = conn.createStatement();
   s.execute("create schema EMP");

   // Get the cache;
   Cache cache = CacheFactory.getAnyInstance();

   // Check for PARTITION BY COLUMN
   s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
   + " SECONDID int not null, THIRDID int not null, primary key (ID, SECONDID))"
   + " PARTITION BY COLUMN (ID)");

   Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");

   RegionAttributes rattr = regtwo.getAttributes();
   PartitionResolver pr = rattr.getPartitionAttributes()
   .getPartitionResolver();

   assertNotNull(pr);

   GfxdPartitionByExpressionResolver scpr = (GfxdPartitionByExpressionResolver)pr;

   assertNotNull(scpr);

   assertTrue(scpr.columnsSubsetOfPrimary());

   assertEquals(1, scpr.getColumnNames().length);

   assertEquals("ID", scpr.getColumnNames()[0]);
   }

   public void testCreateTable1() throws SQLException, StandardException {
   // Create a schema
   Connection conn = getConnection();
   Statement s = conn.createStatement();
   s.execute("create schema EMP");

   // Get the cache;
   Cache cache = CacheFactory.getAnyInstance();

   // Check for PARTITION BY COLUMN
   s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
   + " SECONDID int not null, THIRDID int not null, primary key (SECONDID))"
   + " PARTITION BY COLUMN (ID)");

   Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");

   RegionAttributes rattr = regtwo.getAttributes();
   PartitionResolver pr = rattr.getPartitionAttributes()
   .getPartitionResolver();

   assertNotNull(pr);

   GfxdPartitionByExpressionResolver scpr = (GfxdPartitionByExpressionResolver)pr;

   assertNotNull(scpr);

   assertFalse(scpr.columnsSubsetOfPrimary());

   assertEquals(1, scpr.getColumnNames().length);

   assertEquals("ID", scpr.getColumnNames()[0]);
   }

   public void testCreateTable12() throws SQLException, StandardException {
   // Create a schema
   Connection conn = getConnection();
   Statement s = conn.createStatement();
   s.execute("create schema EMP");

   // Get the cache;
   Cache cache = CacheFactory.getAnyInstance();

   // Check for PARTITION BY COLUMN
   s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
   + " SECONDID int not null, THIRDID int not null, primary key (SECONDID))"
   + " PARTITION BY COLUMN (ID, SECONDID)");

   Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");

   RegionAttributes rattr = regtwo.getAttributes();
   PartitionResolver pr = rattr.getPartitionAttributes()
   .getPartitionResolver();

   assertNotNull(pr);

   GfxdPartitionByExpressionResolver scpr = (GfxdPartitionByExpressionResolver)pr;

   assertNotNull(scpr);

   assertFalse(scpr.columnsSubsetOfPrimary());

   assertEquals(2, scpr.getColumnNames().length);

   assertEquals("ID", scpr.getColumnNames()[0]);

   assertEquals("SECONDID", scpr.getColumnNames()[1]);
   }

  public void testCreateReplicatedTable() throws Exception {
    // Create a schema
    setupConnection();
    sqlExecute("create schema EMP", Boolean.FALSE);

    // Create replicated table
    sqlExecute("create table EMP.REPLICATEDTESTTABLE (ID int not null, "
        + " SECONDID int not null, THIRDID int not null, primary key "
        + "(SECONDID)) REPLICATE", Boolean.TRUE);

    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.REPLICATE);
    expectedAttrs.setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    // Test the table region attributes on VM
    verifyRegionProperties("EMP", "REPLICATEDTESTTABLE", TestUtil
        .regionAttributesToXML(expectedAttrs));

    // Do some inserts and selects
    sqlExecute("insert into EMP.REPLICATEDTESTTABLE values (1, 2, 3)", true);
    sqlExecute("insert into EMP.REPLICATEDTESTTABLE values (4, 3, 3)", true);
    sqlExecute("insert into EMP.REPLICATEDTESTTABLE values (2, 1, 4)", true);
    sqlExecuteVerifyText(
        "SELECT * FROM EMP.REPLICATEDTESTTABLE", getResourcesDir()
            + "/lib/checkCreateTable.xml", "replicated_test",
        Boolean.TRUE /* use prep statement */,
        Boolean.FALSE /* do not check for type info */);

    // drop the table
    sqlExecute("drop table EMP.REPLICATEDTESTTABLE", true);
    // drop schema and shutdown
    sqlExecute("drop schema EMP RESTRICT", false);
  }

  // This test added for the case - when partition keys are subset of primary
  // key. Bug 39591 where ArrayIndexOutOfBoundException was coming.
  public void testCreateTable_39591() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Get the cache;
    Cache cache = CacheFactory.getAnyInstance();

    // Check for PARTITION BY RANGE
    s
        .execute("create table EMP.TESTTABLE (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, NAME varchar(1024) not null, primary key (ID, DESCRIPTION,NAME))"
            + "PARTITION BY COLUMN ( DESCRIPTION,NAME )");

    for (int i = 0; i < 8; ++i) {
      s.execute("insert into EMP.TESTTABLE values (" + (i + 1) + ", 'First"
          + (i + 1) + "', 'J 604','Asif" + (i + 1) + "' )");
    }

    Region regtwo = cache.getRegion("/EMP/TESTTABLE");

    RegionAttributes rattr = regtwo.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();

    assertNotNull(pr);

    GfxdPartitionByExpressionResolver scpr = (GfxdPartitionByExpressionResolver)pr;

    assertNotNull(scpr);

    assertTrue(scpr.columnsSubsetOfPrimary());

    assertEquals(2, scpr.getColumnNames().length);

    assertEquals("DESCRIPTION", scpr.getColumnNames()[0]);

    assertEquals("NAME", scpr.getColumnNames()[1]);
  }

  public void testCreateTable3() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");
    Cache cache = CacheFactory.getAnyInstance();
    boolean gotException = false;
    try {
      s.execute("create table EMP.PARTITIONTESTTABLE_ZERO (ID int not null, "
          + " DESCRIPTION varchar(1024) not null)"
          + "PARTITION BY LIST ( ID ) ( VALUES (10, 20 ),"
          + " VALUES (50, 60), VALUES (12, 34, 10, 45))");
    } catch (SQLException ex) {
      gotException = true;
    }
    assertTrue(gotException);
    s.execute("create table EMP.PARTITIONTESTTABLE_ZERO (ID int not null, "
        + " DESCRIPTION varchar(1024) not null)"
        + "PARTITION BY LIST ( ID ) ( VALUES (10, 20 ),"
        + " VALUES (50, 60), VALUES (12, 34, 45))");
    Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE_ZERO");

    RegionAttributes rattr = regtwo.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();
    assertNotNull(pr);

    GfxdListPartitionResolver slpr = (GfxdListPartitionResolver)pr;
    assertNotNull(slpr);

    DataValueDescriptor lowerBound = new SQLInteger(50);
    DataValueDescriptor upperBound = new SQLInteger(50);
    boolean lowerBoundInclusive = false, upperBoundInclusive = false;

    Object[] routingObjects = slpr.getRoutingObjectsForRange(lowerBound,
        lowerBoundInclusive, upperBound, upperBoundInclusive);
    assertNull(routingObjects);

    lowerBoundInclusive = true;
    routingObjects = slpr.getRoutingObjectsForRange(lowerBound,
        lowerBoundInclusive, upperBound, upperBoundInclusive);
    assertNull(routingObjects);

    upperBoundInclusive = true;
    routingObjects = slpr.getRoutingObjectsForRange(lowerBound,
        lowerBoundInclusive, upperBound, upperBoundInclusive);
    assertEquals(1, routingObjects.length);
    assertEquals(1, ((Integer)routingObjects[0]).intValue());
  }

  public void testRanges_colocated() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Get the cache;
    Cache cache = CacheFactory.getAnyInstance();

    // Check for PARTITION BY RANGE
    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "PARTITION BY LIST ( ID ) ( VALUES (10, 20 ),"
        + " VALUES (50, 60), VALUES (12, 34, 45))");

    s.execute("create table EMP.PARTITIONTESTTABLE_COLOCATED (ID int "
        + "not null, DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "PARTITION BY LIST ( ID ) ( VALUES (10, 20 ),"
        + " VALUES (50, 60), VALUES (12, 34, 45)) "
        + "COLOCATE WITH (EMP.PARTITIONTESTTABLE)");

    Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE_COLOCATED");
    RegionAttributes rattr = regtwo.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();
    GfxdListPartitionResolver rpr = (GfxdListPartitionResolver)pr;
    assertNotNull(rpr);

    DataValueDescriptor[] keys = new DataValueDescriptor[] {
        new SQLInteger(10), new SQLInteger(20), new SQLInteger(50),
        new SQLInteger(60), new SQLInteger(12), new SQLInteger(34),
        new SQLInteger(45) };

    Object[] routingObjects = rpr.getRoutingObjectsForList(keys);
    assertEquals(7, routingObjects.length);
    assertEquals(0, routingObjects[0]);
    assertEquals(0, routingObjects[1]);
    assertEquals(1, routingObjects[2]);
    assertEquals(1, routingObjects[3]);
    assertEquals(2, routingObjects[4]);
    assertEquals(2, routingObjects[5]);
    assertEquals(2, routingObjects[6]);

    s.close();
    conn.close();
  }

  public void testPartitionByPrimaryGfxdPartitionResolver()
      throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Get the cache;
    Cache cache = CacheFactory.getAnyInstance();

    // Check for PARTITION BY RANGE
    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID)) " +
        		"PARTITION BY PRIMARY KEY");

    Region reg = cache.getRegion("/EMP/PARTITIONTESTTABLE");
    RegionAttributes rattr = reg.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();
    GfxdPartitionByExpressionResolver rpr = (GfxdPartitionByExpressionResolver)pr;
    assertNotNull(rpr);

    String[] columns = rpr.getColumnNames();

    assertEquals(1, columns.length);
    assertEquals("ID", columns[0]);

    s.execute("create table EMP.PARTITIONTESTTABLE2 (ID int not null, "
       + " DESCRIPTION varchar(1024) not null, NAME varchar(1024) not null, " +
       		"primary key (ID, DESCRIPTION))");

    reg = cache.getRegion("/EMP/PARTITIONTESTTABLE2");
    rattr = reg.getAttributes();
    pr = rattr.getPartitionAttributes().getPartitionResolver();
    assertTrue(pr instanceof GfxdPartitionByExpressionResolver);

    GfxdPartitionByExpressionResolver dpr =  (GfxdPartitionByExpressionResolver)pr;
    assertNotNull(dpr);
    assertTrue(dpr.isDefaultPartitioning());

    columns = dpr.getColumnNames();

    assertEquals(2, columns.length);
    assertEquals("ID", columns[0]);
    assertEquals("DESCRIPTION", columns[1]);

    s.close();
    conn.close();
  }

  public void testDefaultGfxdPartitionResolver() throws SQLException,
      StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Get the cache;
    Cache cache = CacheFactory.getAnyInstance();

    // Check for PARTITION BY RANGE
    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))");

    Region reg = cache.getRegion("/EMP/PARTITIONTESTTABLE");
    RegionAttributes rattr = reg.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();
    GfxdPartitionByExpressionResolver rpr = (GfxdPartitionByExpressionResolver)pr;
    assertNotNull(rpr);
    assertTrue(rpr.isDefaultPartitioning());

    String[] columns = rpr.getColumnNames();

    assertEquals(1, columns.length);
    assertEquals("ID", columns[0]);

    // Just check if the refColumns are set and its length is 1.
    assertEquals(1, rpr.getPartitioningColumnsCount());

    s
        .execute("create table EMP.PARTITIONTESTTABLE2 (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, NAME varchar(1024) not null, primary key (ID, DESCRIPTION))");

    reg = cache.getRegion("/EMP/PARTITIONTESTTABLE2");
    rattr = reg.getAttributes();
    pr = rattr.getPartitionAttributes().getPartitionResolver();
    rpr = (GfxdPartitionByExpressionResolver)pr;
    assertNotNull(rpr);
    assertTrue(rpr.isDefaultPartitioning());

    columns = rpr.getColumnNames();

    assertEquals(2, columns.length);
    assertEquals("ID", columns[0]);
    assertEquals("DESCRIPTION", columns[1]);

    s.execute("create table EMP.PARTITIONTESTTABLE3 (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, NAME varchar(1024) not null, "
        + "CONSTRAINT constraint1 unique (ID))");

    reg = cache.getRegion("/EMP/PARTITIONTESTTABLE3");
    rattr = reg.getAttributes();
    pr = rattr.getPartitionAttributes().getPartitionResolver();
    rpr = (GfxdPartitionByExpressionResolver)pr;
    assertNotNull(rpr);
    assertTrue(rpr.isDefaultPartitioning());

    columns = rpr.getColumnNames();

    assertEquals(1, columns.length);
    assertEquals("ID", columns[0]);

    // Just check if the refColumns are set and its length is 1.
    assertEquals(1, rpr.getPartitioningColumnsCount());

    s.execute("create table EMP.PARTITIONTESTTABLE4 " +
    		"(ID int not null, DESCRIPTION varchar(1024) not null, " +
    		"NAME varchar(1024) not null, CONSTRAINT neeraj_uniq1 unique (NAME), " +
    		"unique (ID))");

    reg = cache.getRegion("/EMP/PARTITIONTESTTABLE4");
    rattr = reg.getAttributes();
    pr = rattr.getPartitionAttributes().getPartitionResolver();
    rpr = (GfxdPartitionByExpressionResolver)pr;
    assertNotNull(rpr);
    assertTrue(rpr.isDefaultPartitioning());

    columns = rpr.getColumnNames();

    assertEquals(1, columns.length);
    assertEquals("NAME", columns[0]);

    // Just check if the refColumns are set and its length is 1.
    assertEquals(1, rpr.getPartitioningColumnsCount());

    s.close();
    conn.close();
  }

  public void testCreateTable39635() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    s.execute("CREATE SCHEMA EMP");

    s.execute("create table EMP.TESTTABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null ) PARTITION BY RANGE ( ID )"
        + " ( VALUES BETWEEN 0 and 3, VALUES BETWEEN  3 and 6 , VALUES BETWEEN 6 and  +Infinity )");

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      s.execute("insert into EMP.TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "')");
    }
    conn.commit();
    conn.close();
  }

  public void testDefaultColocate_39857() throws SQLException,
      StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    Cache cache = CacheFactory.getAnyInstance();
    s.execute("create table EMP.TESTTABLE1 (ID1 int primary key,"
        + "DESCRIPTION1 varchar(1024), ADDRESS1 varchar(1024))");
    s.execute("create table EMP.TESTTABLE2 (ID2 int primary key, "
        + "DESCRIPTION2 varchar(1024), ADDRESS2 varchar(1024)) "
        + "partition by primary key colocate with (EMP.TESTTABLE1)");
    Region regtwo = cache.getRegion("/EMP/TESTTABLE2");

    RegionAttributes rattr = regtwo.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();

    assertNotNull(pr);
    GfxdPartitionByExpressionResolver cpr = (GfxdPartitionByExpressionResolver)pr;
    assertNotNull(cpr);
    int numofcolumns = cpr.getColumnNames().length;
    assertEquals(1, numofcolumns);
    assertEquals("ID2", cpr.getColumnNames()[0]);
    String masterTable = cpr.getMasterTable(false /* immediate master*/);
    assertEquals("/EMP/TESTTABLE1", masterTable);
  }

  public void test_39559() throws SQLException, StandardException {
   Connection conn = getConnection();
   Statement s = conn.createStatement();
   s.execute("create schema trade");
   s.execute("create table trade.customers (cid int not null, primary key (cid))");
   s.execute("create table trade.securities (sec_id int not null, primary key (sec_id))");
   s.execute("create table trade.portfolio " +
        "(cid int not null, sid int not null, qty int not null, " +
        "availQty int not null, subTotal decimal(30,20), tid int)");

   try {
      s.execute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, "
          + "subTotal decimal(30,20), tid int)");
      fail("expected existing table exception");
    } catch (SQLException ex) {
      if (!"X0Y32".equals(ex.getSQLState())) {
        throw ex;
      }
    }
  }

  // Kneeraj: This test fails after checkin for bug #39999
  // This test needs to be revisited after fix for #39999
  // The creation of this table     sqlExecute("create table trade.portfolio (cid int not null, sid int "
  // + "not null, qty int not null, availQty int not null, subTotal "
  // + "decimal(30,20), tid int) partition by range (sid) "
  // + "(values between 1 and 5, values between 5 and 10) "
  // + "colocate with (trade.customers) server groups (sg2, sg1)", false);
  // will fail if the main table has been created with     sqlExecute("create table trade.customers (cid int not null, primary key "
  // + "(cid)) server groups (sg1, SG2)", true);
  //
  public void testColocatedServerGroups() throws SQLException,
      StandardException {
    Properties props = new Properties();
    props.put("server-groups", "SG1, sg2");
    setupConnection(props);
    sqlExecute("create table trade.customers (cid int not null, primary key "
        + "(cid)) server groups (sg1, SG2)", true);
    checkServerGroups("trade.customers", "sg1", "sg2");

    // check failure when columns do not match
    try {
      sqlExecute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, subTotal "
          + "decimal(30,20), tid int) partition by column (cid, sid) "
          + "colocate with (trade.customers) server groups (SG1, SG2)", false);
      fail("Expected table creation of incompatible columns to fail");
    } catch (SQLException ex) {
      // expect to fail with SQLException
      if (!"X0Y91".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // check failure when partitioning columns do not exist
    try {
      sqlExecute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, subTotal "
          + "decimal(30,20), tid int) partition by range (subTot) "
          + "(values between 1.0 and 5.0, values between 5.0 and 10.0) "
          + "colocate with (trade.customers) server groups (SG1, SG2)", false);
      fail("Expected table creation with incompatible partitioning to fail");
    } catch (SQLException ex) {
      // expect to fail with SQLException syntax error
      if (!"42X04".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // check failure for incompatible partitioning
    try {
      sqlExecute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, subTotal "
          + "decimal(30,20), tid int) partition by range (subTotal) "
          + "(values between 1.0 and 5.0, values between 5.0 and 10.0) "
          + "colocate with (trade.customers) server groups (SG1, SG2)", false);
      fail("Expected table creation with incompatible partitioning to fail");
    } catch (SQLException ex) {
      // expect to fail with SQLException
      final String state = ex.getSQLState();
      if (!"X0Y92".equals(state) && !"X0Y95".equals(state)) {
        throw ex;
      }
    }

    // check failures when server groups do not match
    try {
      sqlExecute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, subTotal "
          + "decimal(30,20), tid int, primary key(cid)) partition by "
          + "primary key colocate with (trade.customers)", false);
      fail("Expected table creation in different server groups to fail");
    } catch (SQLException ex) {
      // expect to fail with SQLException
      if (!"X0Y93".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      sqlExecute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, subTotal "
          + "decimal(30,20), tid int) partition by column (cid) "
          + "colocate with (trade.customers) server groups (sg1)", false);
      fail("Expected table creation in different server groups to fail");
    } catch (SQLException ex) {
      // expect to fail with SQLException
      if (!"X0Y93".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // try with matching server groups for partition by primary key but no PK
    try {
      sqlExecute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, subTotal "
          + "decimal(30,20), tid int) partition by primary key "
          + "colocate with (trade.customers) server groups (SG2, SG1)", true);
    } catch (SQLException ex) {
      // expect to fail with SQLException
      if (!"X0Y97".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // check success with matching server groups
    sqlExecute("create table trade.portfolio (cid int not null, sid int "
        + "not null, qty int not null, availQty int not null, subTotal "
        + "decimal(30,20), tid int) partition by column (cid) "
        + "colocate with (trade.customers) server groups (SG2, SG1)", false);
    checkServerGroups("trade.portfolio", "sg1", "sg2");
    GfxdPartitionResolver resolver = checkColocation("trade.portfolio",
        "trade", "customers");
    assertTrue(resolver instanceof GfxdPartitionByExpressionResolver);

    // same check for matching server groups for partition by primary key
    sqlExecute("drop table trade.portfolio", true);

    // check for failure on dropping a non-existent table
    Connection conn = getConnection();
    PreparedStatement pstmt = null, pstmt2;
    try {
      pstmt = conn.prepareStatement("drop table trade.port");
      pstmt.execute();
      fail("expected to fail in dropping a non-existent table");
    } catch (SQLException sqle) {
      if (!"42Y55".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // should pass with "IF EXISTS"
    pstmt2 = conn.prepareStatement("drop table if exists trade.port");
    pstmt2.execute();
    // now try after actually creating the table
    sqlExecute("create table trade.port (cid int not null, sid int)", true);
    if (pstmt == null) {
      pstmt = conn.prepareStatement("drop table trade.port");
    }
    pstmt.execute();
    sqlExecute("create table trade.port (cid int not null, sid int)", true);
    pstmt2.execute();
    try {
      pstmt.execute();
      fail("expected to fail in dropping a non-existent table");
    } catch (SQLException sqle) {
      if (!"42Y55".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    sqlExecute("create table trade.port (cid int not null, sid int)", true);
    pstmt.execute();
    pstmt2.execute();

    // IF EXISTS should work for non-existent schema
    sqlExecute("drop table if exists trad.port", true);

    sqlExecute("create table trade.portfolio (cid int not null, sid int "
        + "not null, qty int not null, availQty int not null, subTotal "
        + "decimal(30,20), tid int, primary key(cid)) partition by primary key"
        + " colocate with (trade.customers) server groups (SG2, SG1)", true);
    checkServerGroups("trade.portfolio", "sg1", "sg2");
    resolver = checkColocation("trade.portfolio", "trade", "customers");
    assertTrue(resolver instanceof GfxdPartitionByExpressionResolver);

    // check failure for different and incompatible partitioning scheme
    sqlExecute("drop table trade.portfolio", true);
    try {
      sqlExecute("create table trade.portfolio (cid int not null, sid int "
          + "not null, qty int not null, availQty int not null, subTotal "
          + "decimal(30,20), tid int) partition by range (sid) "
          + "(values between 1 and 5, values between 5 and 10) "
          + "colocate with (trade.customers) server groups (sg2, sg1)", false);
      fail("expected exception with incompatible partitioning schemes");
    } catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // check success with matching server groups for default colocation
    sqlExecute("create table trade.securities (sec_id int not null, "
        + "primary key (sec_id)) server groups (sg2, sg1)", true);
    checkServerGroups("trade.securities", "sg1", "sg2");
    sqlExecute("create table trade.portfolio (cid int not null, sid int "
        + "not null, qty int not null, availQty int not null, subTotal "
        + "decimal(30,20), tid int, foreign key (sid) references "
        + "trade.securities(sec_id)) server groups (SG1, SG2)", false);
    checkServerGroups("trade.portfolio", "sg1", "sg2");
    resolver = checkColocation("trade.portfolio", "trade", "securities");
    assertTrue(resolver instanceof GfxdPartitionByExpressionResolver
        && ((GfxdPartitionByExpressionResolver)resolver)
            .isDefaultPartitioning());

    // check non-matching server groups have no default colocation
    sqlExecute("create table trade.portfolio2 (cid int not null, sid int "
        + "not null, qty int not null, availQty int not null, subTotal "
        + "decimal(30,20), tid int, foreign key (sid) references "
        + "trade.securities(sec_id)) server groups (Sg2)", false);
    checkServerGroups("trade.portfolio2", "SG2");
    resolver = checkColocation("trade.portfolio2", null, null);
    assertTrue(resolver instanceof GfxdPartitionByExpressionResolver
        && ((GfxdPartitionByExpressionResolver)resolver)
            .isDefaultPartitioning());
    sqlExecute("drop table trade.portfolio2", false);
    sqlExecute("create table trade.portfolio2 (cid int not null, sid int "
        + "not null, qty int not null, availQty int not null, subTotal "
        + "decimal(30,20), tid int, foreign key (sid) references "
        + "trade.securities(sec_id))", false);
    checkServerGroups("trade.portfolio2", (String[])null);
    resolver = checkColocation("trade.portfolio2", null, null);
    assertTrue(resolver instanceof GfxdPartitionByExpressionResolver
        && ((GfxdPartitionByExpressionResolver)resolver)
            .isDefaultPartitioning());

    sqlExecute("drop table trade.portfolio", true);
    try {
      sqlExecute("drop table trade.securities", false);
      fail("Expected exception while dropping the table");
    } catch (SQLException ex) {
      // expect exception while dropping trade.securities
      if (!"X0Y25".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    sqlExecute("drop table trade.portfolio2", true);
    sqlExecute("drop table trade.securities", false);
  }

  public void testDuplicateConstraint_39558() throws Exception {

    setupConnection();

    // create schema and verify that the schema region exists
    sqlExecute("create schema emp", true);
    assertNotNull(Misc.getRegionForTable("EMP", false));

    // create table with constraint
    sqlExecute("create table emp.availability "
        + "(hotel_id int not null, booking_date date not null, "
        + "rooms_taken int, constraint hotelavail_pk primary key "
        + "(hotel_id, booking_date))", false);

    // creating a constraint with same name should throw an exception
    try {
      sqlExecute("create table emp.availability2 "
          + "(hotel_id int not null, booking_date date not null, "
          + "rooms_taken int, constraint hotel_ck check (rooms_taken > 0), "
          + " constraint hotelavail_pk primary key (hotel_id, "
          + "booking_date))", false);
      fail("unexpectedly did not get exception when trying to create a"
          + " constraint that already exists");
    } catch (SQLException ex) {
      // check for the expected exception
      if (!"X0Y32".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // verify that the table region doesn't exist
    assertNull("Did not expect the region to exist", Misc
        .getRegionForTable("EMP.AVAILABILITY2", false));

    // now try to create with proper constraint
    sqlExecute("create table emp.availability2 "
        + "(hotel_id int not null, booking_date date not null, "
        + "rooms_taken int, constraint hotel_ck check (rooms_taken > 0), "
        + " constraint hotelavail_pk2 primary key (hotel_id, "
        + "booking_date))", false);

    // verify that the table region exists
    assertNotNull("Expected the region to exist", Misc
        .getRegionForTable("EMP.AVAILABILITY2", false));

    // check that the constraints belong to the proper tables

    sqlExecuteVerifyText("select TABLENAME, CONSTRAINTNAME from "
        + "SYS.SYSCONSTRAINTS con, SYS.SYSTABLES tab where con.TABLEID = "
        + "tab.TABLEID and CONSTRAINTNAME='HOTELAVAIL_PK'", getResourcesDir()
        + "/lib/checkCreateTable.xml", "emp_constr1", false, false);

    sqlExecuteVerifyText("select TABLENAME, CONSTRAINTNAME from "
        + "SYS.SYSCONSTRAINTS con, SYS.SYSTABLES tab where con.TABLEID = "
        + "tab.TABLEID and CONSTRAINTNAME='HOTEL_CK'", getResourcesDir()
        + "/lib/checkCreateTable.xml", "emp_constr2", true, false);

    sqlExecuteVerifyText("select TABLENAME, CONSTRAINTNAME from "
        + "SYS.SYSCONSTRAINTS con, SYS.SYSTABLES tab where con.TABLEID = "
        + "tab.TABLEID and ( CONSTRAINTNAME='HOTELAVAIL_PK' or "
        + "CONSTRAINTNAME='HOTEL_CK' or CONSTRAINTNAME='HOTELAVAIL_PK2' )",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "emp_constr3", true, false);

    // non-index lookups
    sqlExecuteVerifyText("select TABLENAME, CONSTRAINTNAME from "
        + "SYS.SYSCONSTRAINTS con, SYS.SYSTABLES tab where con.TABLEID = "
        + "tab.TABLEID and ( CONSTRAINTNAME like '%AVAIL_PK' or "
        + "CONSTRAINTNAME like '%_CK' or CONSTRAINTNAME like '%_PK2' )",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "emp_constr3", true, false);

    int numRows = sqlExecuteVerify("select * from SYS.SYSCONSTRAINTS where "
        + "CONSTRAINTNAME like '%_PK'", false);
    assertEquals("Expected exactly one row for HOTELAVAIL_PK", 1, numRows);
    numRows = sqlExecuteVerify("select * from SYS.SYSCONSTRAINTS where "
        + "CONSTRAINTNAME like '%_CK'", true);
    assertEquals("Expected exactly one row for HOTEL_CK", 1, numRows);
    numRows = sqlExecuteVerify("select * from SYS.SYSCONSTRAINTS where "
        + "CONSTRAINTNAME like '%AVAIL_PK2'", true);
    assertEquals("Expected exactly one row for HOTELAVAIL_PK2", 1, numRows);

    // drop the tables and schema
    sqlExecute("drop table emp.availability", false);
    sqlExecute("drop table emp.availability2", true);
    sqlExecute("drop schema emp restrict", false);
  }
  
  public void testCreateTableWithCascadeDeleteRule() throws Exception{
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table trade.customers (cid int not null, cust_name varchar(100), " +
                                                     "since date, addr varchar(100)," +
                                                     "tid int, primary key (cid))");
    try {
    
      Monitor.getStream().println(
          "<ExpectedException action=add>"
              + "java.sql.SQLFeatureNotSupportedException"
              + "</ExpectedException>");
       Monitor.getStream().flush();
       
       Monitor.getStream().println("<ExpectedException action=add>"
           + "java.sql.SQLException"
           + "</ExpectedException>");
       Monitor.getStream().flush();  
      s.execute("create table trade.networth (cid int not null, cash decimal (30, 20), " +
                                                    "securities decimal (30, 20), loanlimit int, " +
                                                    "availloan decimal (30, 20),  tid int," +
                                                    "constraint netw_pk primary key (cid), " +
                                                    "constraint cust_newt_fk foreign key (cid) references trade.customers (cid) on delete CASCADE, " +
                                                    "constraint cash_ch check (cash>=0), " +
                                                    "constraint sec_ch check (securities >=0)," +
                                                    "constraint availloan_ck check (loanlimit>=availloan and availloan >=0))");
    fail("Exception is expected!");
    }
    catch (Exception e) {
      Monitor.getStream().println("<ExpectedException action=remove>"
          + "java.sql.SQLException"
          + "</ExpectedException>");
      Monitor.getStream().flush();  
      
      Monitor.getStream().println(
          "<ExpectedException action=remove>"
              + "java.sql.SQLFeatureNotSupportedException"
              + "</ExpectedException>");
       Monitor.getStream().flush();

    }
    s.execute("create table trade.networth (cid int not null, cash decimal (30, 20), " +
        "securities decimal (30, 20), loanlimit int, " +
        "availloan decimal (30, 20),  tid int," +
        "constraint netw_pk primary key (cid), " +
        "constraint cust_newt_fk foreign key (cid) references trade.customers (cid) on delete RESTRICT, " +
        "constraint cash_ch check (cash>=0), " +
        "constraint sec_ch check (securities >=0)," +
        "constraint availloan_ck check (loanlimit>=availloan and availloan >=0))");  
  
  }
  
  public void testCompatibleColocations1() throws Exception {
    setupConnection();
    boolean gotException = false;
    sqlExecute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid))   partition by range (cid) "
        + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, "
        + "VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, "
        + "VALUES BETWEEN 1678 AND 10000)", false);
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by column (cid) colocate with (trade.customers)",
          false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by primary key colocate with (trade.customers)",
          false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 20 ),"
              + " VALUES (50, 60), VALUES (12, 34, 45)) colocate with (trade.customers)",
          false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
          + "cust_name varchar(100), since date, addr varchar(100), "
          + "tid int, primary key (cid))   partition by range (cid) "
          + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 901, " // change here in end value
          + "VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, "
          + "VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
          + "cust_name varchar(100), since date, addr varchar(100), "
          + "tid int, primary key (cid))   partition by range (cid) "
          + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, " 
          + "VALUES BETWEEN 1255 AND 1678, " // change here -- range VALUES BETWEEN 902 AND 1255 missing
          + "VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    // Now check that a proper case is ok
    try {
      sqlExecute("create table trade.customers2 (cid int not null, "
          + "cust_name varchar(100), since date, addr varchar(100), "
          + "tid int, primary key (cid))   partition by range (cid) "
          + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, "
          + "VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, "
          + "VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    }
    catch (SQLException ex) {
      gotException = true;
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    assertFalse(gotException);
  }
  
  public void testCompatibleColocations2() throws Exception {
    setupConnection();
    boolean gotException = false;
    sqlExecute("create table trade.customers (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 20 ),"
              + " VALUES (50, 60), VALUES (12, 34, 45))",
          false);
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by column (cid) colocate with (trade.customers)",
          false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) partition by primary key colocate with (trade.customers)",
          false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
              + "cust_name varchar(100), since date, addr varchar(100), "
              + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 30 )," // a bit different value in this list
              + " VALUES (50, 60), VALUES (12, 34, 45)) colocate with (trade.customers)",
          false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
          + "cust_name varchar(100), since date, addr varchar(100), "
          + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 30 )," 
          + " VALUES (50, 60), VALUES (12, 34, 45), VALUES (300, 200)) " // // one extra list of values
          		+ "colocate with (trade.customers)",
      false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      sqlExecute(
          "create table trade.customers2 (cid int not null, "
          + "cust_name varchar(100), since date, addr varchar(100), "
          + "tid int, primary key (cid))   partition by range (cid) "
          + "( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, " 
          + "VALUES BETWEEN 1255 AND 1678, VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)", false);
    }
    catch (SQLException ex) {
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    // Now check that a proper case is ok
    try {
      sqlExecute("create table trade.customers2 (cid int not null, "
          + "cust_name varchar(100), since date, addr varchar(100), "
          + "tid int, primary key (cid)) PARTITION BY LIST ( CID ) ( VALUES (10, 20 ),"
          + " VALUES (50, 60), VALUES (12, 34, 45)) colocate with (trade.customers)",
      false);
    }
    catch (SQLException ex) {
      gotException = true;
      if (!"X0Y95".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    assertFalse(gotException);
  }

  public void testBug41004() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("create table ctstable1 (TYPE_ID int, TYPE_DESC varchar(32), "
        + "primary key(TYPE_ID))");
    stmt.execute("create table ctstable2 (KEY_ID int, COF_NAME varchar(32), "
        + "PRICE float, TYPE_ID int, primary key(KEY_ID), "
        + "foreign key(TYPE_ID) references ctstable1)");

    // check the index types and colocation
    final String schemaName = getCurrentDefaultSchemaName();
    AlterTableTest.checkDefaultPartitioning(schemaName + ".ctstable1",
        "TYPE_ID");
    AlterTableTest.checkDefaultPartitioning(schemaName + ".CTSTABLE2",
        "TYPE_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable1", "LOCALHASH1",
        "TYPE_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable2", "GLOBALHASH",
        "KEY_ID");
    AlterTableTest.checkIndexType(schemaName, "ctstable2", "LOCALSORTEDMAP",
        "TYPE_ID");
    AlterTableTest.checkColocation(schemaName + ".ctstable2", schemaName,
        "ctstable1");

    stmt.execute("insert into ctstable1 values (5, 'Type1')");
    stmt.execute("insert into ctstable2 values (10, 'COF1', 20.0, 5)");

    ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
    GemFireXDQueryObserverHolder.setInstance(observer);

    ResultSet rs = stmt.executeQuery("select * from ctstable1");
    assertTrue(rs.next());
    assertEquals(5, rs.getInt("TYPE_ID"));
    assertEquals(Integer.valueOf(5), rs.getObject(1));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable1", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select TYPE_ID from ctstable2");
    assertTrue(rs.next());
    assertEquals(Integer.valueOf(5), rs.getObject("TYPE_ID"));
    assertEquals(Integer.valueOf(5), rs.getObject(1));
    assertFalse(rs.next());

    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.TYPE_ID = t2.TYPE_ID");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2", ScanType.TABLE);
    observer.checkAndClear();

    rs = stmt.executeQuery("select t1.TYPE_ID, t2.KEY_ID, t2.PRICE "
        + "from ctstable1 t1, ctstable2 t2 where t1.TYPE_ID = t2.TYPE_ID "
        + "and t2.TYPE_ID > 2");
    assertTrue(rs.next());
    assertEquals(20.0d, rs.getDouble("PRICE"));
    assertEquals(Double.valueOf(20.0), rs.getObject(3));
    assertFalse(rs.next());

    observer
        .addExpectedScanType(schemaName + ".ctstable1", ScanType.HASH1INDEX);
    observer.addExpectedScanType(schemaName + ".ctstable2",
        ScanType.SORTEDMAPINDEX);
    observer.checkAndClear();
  }

  public void testBug40608() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema trade");
    stmt.execute("create table trade.customers (cid int not null, cash "
        + "decimal (30, 20), loanlimit int, tid int, constraint cust_pk "
        + "primary key (cid)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow synchronous");
    Region<?, ?> r1 = CacheFactory.getAnyInstance().getRegion(
        "/TRADE/CUSTOMERS");
    RegionAttributes<?, ?> rattrs1 = r1.getAttributes();
    EvictionAttributes eattrs1 = rattrs1.getEvictionAttributes();
    ObjectSizer os1 = eattrs1.getObjectSizer();

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), "
        + "loanlimit int, availloan decimal (30, 20),  tid int, "
        + "constraint netw_pk primary key (cid), "
        + "constraint cust_newt_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict, constraint cash_ch "
        + "check (cash>=0), constraint sec_ch check (securities >=0), "
        + "constraint availloan_ck check (loanlimit>=availloan and "
        + "availloan >=0)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow synchronous");

    Region<?, ?> r2 = CacheFactory.getAnyInstance()
        .getRegion("/TRADE/NETWORTH");
    RegionAttributes<?, ?> rattrs2 = r2.getAttributes();
    EvictionAttributes eattrs2 = rattrs2.getEvictionAttributes();
    assertTrue(eattrs2.getAction().isOverflowToDisk());
    assertTrue(eattrs2.getAlgorithm().isLRUHeap());
    ObjectSizer os2 = eattrs2.getObjectSizer();
    assertTrue(os2 == os1);
  }

  public void testSYSTABLES() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema trade");
    stmt.execute("create table trade.customers (cid int not null, cash "
        + "decimal (30, 20), loanlimit int, tid int, constraint cust_pk "
        + "primary key (cid)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow");
    Region<?, ?> r1 = CacheFactory.getAnyInstance().getRegion(
        "/TRADE/CUSTOMERS");
    RegionAttributes<?, ?> rattrs1 = r1.getAttributes();
    EvictionAttributes eattrs1 = rattrs1.getEvictionAttributes();
    ObjectSizer os1 = eattrs1.getObjectSizer();

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), "
        + "loanlimit int, availloan decimal (30, 20),  tid int, "
        + "constraint netw_pk primary key (cid), "
        + "constraint cust_newt_fk foreign key (tid) references "
        + "trade.customers (cid) on delete restrict, constraint cash_ch "
        + "check (cash>=0), constraint sec_ch check (securities >=0), "
        + "constraint availloan_ck check (loanlimit>=availloan and "
        + "availloan >=0)) redundancy 2 eviction by lruheappercent "
        + "evictaction overflow synchronous");

    Region<?, ?> r2 = CacheFactory.getAnyInstance()
        .getRegion("/TRADE/NETWORTH");
    RegionAttributes<?, ?> rattrs2 = r2.getAttributes();
    EvictionAttributes eattrs2 = rattrs2.getEvictionAttributes();
    assertTrue(eattrs2.getAction().isOverflowToDisk());
    assertTrue(eattrs2.getAlgorithm().isLRUHeap());
    ObjectSizer os2 = eattrs2.getObjectSizer();
    assertTrue(os2 == os1);

    ResultSet rs = stmt.executeQuery("select * from sys.systables");
    checkTableProperties(rs);
    rs = stmt.executeQuery("select * from sys.systables where "
        + "tableschemaname='" + getCurrentDefaultSchemaName() + "'");
    checkTableProperties(rs);
    rs.close();
  }

  // [sjigyasu] This test is valid only with TX isolation level NONE and autocommit off.
  // We should review this test when we support savepoints
  public void testBug41711() throws SQLException {
    Connection conn = getConnection();
    conn.setAutoCommit(false);
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    Statement stmt = conn.createStatement();
    stmt.execute("create schema trade");
    stmt.execute("create table trade.customers (cid int not null, tid int, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");

    stmt.execute("insert into trade.customers values (1, 1)");
    stmt.execute("insert into trade.customers values (2, 2)");

    final ResultSet rs = stmt.executeQuery("select tableschemaname, tablename "
        + "from sys.systables where tabletype = 'T' order by tablename");
    int id = -1;
    while (rs.next()) {
      try {
        conn.createStatement().execute(
            "insert into trade.customers values (" + id + ", " + id + ')');
        fail("expected check constraint violation");
      } catch (SQLException ex) {
        if (!"23513".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      --id;
    }
    rs.close();
  }

  // fk on unique key columns can be created now.
  public void DEBUGtestFKOnUniqueCols() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema music");
    stmt.execute("CREATE TABLE music.genre "
        + "(fakepk int not null primary key, genre_name char(40) NOT NULL, "
        + "CONSTRAINT genre_uq UNIQUE (genre_name))");
    boolean gotexception = false;
    try {
      stmt.execute(" CREATE TABLE music.tracks "
          + "(album_id int NOT NULL, GEnre_name char(40) NOT NULL, "
          + "CONSTRAINT tracks_pk PRIMARY KEY (album_id), "
          + "CONSTRAINT tracks_genre_fk FOREIGN KEY (genre_name) REFERENCES "
          + "music.genre (genre_name) ON DELETE NO ACTION ON UPDATE NO ACTION)");
    } catch (SQLException ex) {
      gotexception = true;
      assertTrue("0A000".equals(ex.getSQLState()));
    }
    assertTrue(gotexception);
  }

  //FIXME GemFireXD does not yet support START WITH/INCREMENT BY on GENERATED ALWAYS
  //public void testIdentityGeneratedAlways() throws Exception {
  public void _testIdentityGeneratedAlways() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size
    // add expected warning
    addExpectedException(SQLWarning.class);
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED ALWAYS AS IDENTITY (START WITH 8, INCREMENT BY 1), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    removeExpectedException(SQLWarning.class);
    // expect warning for the explicit INCREMENT BY specification
    SQLWarning sw = stmt.getWarnings();
    assertNotNull(sw);
    if (!"X0Z12".equals(sw.getSQLState()) || !sw.getMessage().contains("1")) {
      throw sw;
    }
    assertNull(sw.getNextWarning());

    final int numRows = 4000;
    // insertion in this table should start with 8
    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 8, 0, null, true);

    // No warnings for default start and increment by
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED ALWAYS AS IDENTITY, "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect no warning for the default start
    sw = stmt.getWarnings();
    assertNull(sw);

    // Now check for IDENTITY column with BIGINT size
    stmt.execute("drop table trade.customers");
    addExpectedException(SQLWarning.class);
    stmt.execute("create table trade.customers (cid bigint not null "
        + "GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 7), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    removeExpectedException(SQLWarning.class);
    // expect warning for the non-default increment
    sw = stmt.getWarnings();
    assertNotNull(sw);
    if (!"X0Z12".equals(sw.getSQLState()) || !sw.getMessage().contains("7")) {
      throw sw;
    }
    assertNull(sw.getNextWarning());

    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 1, 0, null, true);

    stmt.execute("drop table trade.customers");
  }
  
  public void testIdentityGeneratedByDefault() throws Exception {
    // reduce logs
    reduceLogLevelForTest("config");

    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED BY DEFAULT AS IDENTITY (START WITH 8, INCREMENT BY 1), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect warning for the explicit INCREMENT BY specification
    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    final int numRows = 4000;
    // insertion in this table should start with 8
    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 8, 0, null,false);

    // No warnings for default start and increment by
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (cid int not null "
        + "GENERATED BY DEFAULT AS IDENTITY, "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect no warning for the default start
    sw = stmt.getWarnings();
    assertNull(sw);

    // Now check for IDENTITY column with BIGINT size
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (cid bigint not null "
        + "GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 7), "
        + "tid int, primary key (cid), constraint cust_ck check (cid >= 0))");
    // expect warning for the non-default increment
    sw = stmt.getWarnings();
    assertNull(sw);

    runIdentityChecksForCustomersTable(conn, numRows, new int[] { 1 },
        new String[] { "CID" }, 1, 1, 0, null,false);

    stmt.execute("drop table trade.customers");
  }
  
  public void testGeneratedByDefaultStartWithIncrementBy() throws Exception{
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute(
        "create table TESTTABLE (ID int unique not null GENERATED by default AS IDENTITY (START WITH 5, increment by 2) ,"
            + " phone int not null)  ");
    // expect warning for the explicit INCREMENT BY specification
    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);
    
    conn.createStatement().execute("insert into TESTTABLE (id, phone) values (default,1)");
    conn.createStatement().execute("insert into TESTTABLE (phone) values (1)");
    conn.createStatement().execute("insert into TESTTABLE (id, phone) values (default,1)");
    conn.createStatement().execute("insert into TESTTABLE (phone) values (1)");
    
    ResultSet rs = conn.createStatement().executeQuery(
        "select max(id) from TESTTABLE ");
    rs.next();
    assertEquals(11,rs.getInt(1));
    
  }
  
  public void testIdentityColumnWithWAN() throws Exception{
    Connection conn = getConnection();
    
    Statement stmt = conn.createStatement();
    
    try{
      stmt.execute(
          "create table TESTTABLE (ID int unique not null GENERATED ALWAYS AS IDENTITY ,"
              + " phone int not null) gatewaysender (MYSENDER) ");  
    }catch(Exception e){
      if(!e.getMessage().contains("When GatewaySender is attached, only valid types for identity column")){
        fail("Unexpected exception", e);
      }
    }
    
  }
  
  public void testColocationWithBuckets_42952() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0))");

    // check that the tables should be colocated
    checkValidColocation_42952(stmt);

    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");

    // check failure in default colocation when number of buckets do not match

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");

    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0)) BUCKETS 13");

    // check that the tables should not be colocated
    Region<Object, Object> custReg = Misc.getRegionForTable("TRADE.CUSTOMERS",
        true);
    Region<Object, Object> netReg = Misc.getRegionForTable("TRADE.NETWORTH",
        true);
    PartitionAttributes<?, ?> custAttrs = custReg.getAttributes()
        .getPartitionAttributes();
    PartitionAttributes<?, ?> netAttrs = netReg.getAttributes()
        .getPartitionAttributes();

    assertEquals(7, custAttrs.getTotalNumBuckets());
    assertEquals(13, netAttrs.getTotalNumBuckets());
    assertNull(netAttrs.getColocatedWith());

    // check the resolver for networth table
    PartitionResolver<?, ?> custResolver = custAttrs.getPartitionResolver();
    PartitionResolver<?, ?> netResolver = netAttrs.getPartitionResolver();
    assertEquals(GfxdRangePartitionResolver.class, custResolver.getClass());
    assertEquals(GfxdPartitionByExpressionResolver.class,
        netResolver.getClass());

    GfxdRangePartitionResolver custRangeResolver =
        (GfxdRangePartitionResolver)custResolver;
    GfxdPartitionByExpressionResolver netColResolver =
      (GfxdPartitionByExpressionResolver)netResolver;
    assertTrue(custRangeResolver.getDDLString().contains("3332"));
    assertTrue(custRangeResolver.getDDLString().contains("8330"));
    assertEquals("PARTITION BY PRIMARY KEY", netColResolver.getDDLString());

    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");

    // check failure in explicit colocation when number of buckets do not match

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");

    addExpectedException(new Object[] { "X0Y94", "X0Y95" });
    try {
      stmt.execute("create table trade.networth (cid int not null, "
          + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
          + "availloan decimal (30, 20), tid int, constraint netw_pk "
          + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
          + "references trade.customers (cid) on delete restrict, "
          + "constraint cash_ch check (cash>=0), constraint sec_ch check "
          + "(securities >=0), constraint availloan_ck check "
          + "(loanlimit>=availloan and availloan >=0)) BUCKETS 13 "
          + "partition by column (cid) colocate with (trade.customers)");
      fail("expected exception in incompatible colocation");
    } catch (SQLException sqle) {
      if (!"X0Y94".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("create table trade.networth (cid int not null, "
          + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
          + "availloan decimal (30, 20), tid int, constraint netw_pk "
          + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
          + "references trade.customers (cid) on delete restrict, "
          + "constraint cash_ch check (cash>=0), constraint sec_ch check "
          + "(securities >=0), constraint availloan_ck check "
          + "(loanlimit>=availloan and availloan >=0)) BUCKETS 13 "
          + "partition by range (cid) (VALUES BETWEEN 0 AND 1666, "
          + "VALUES BETWEEN 1666 AND 3332, VALUES BETWEEN 3332 AND 4998, "
          + "VALUES BETWEEN 4998 AND 6664, VALUES BETWEEN 6664 AND 8330, "
          + "VALUES BETWEEN 8330 AND 10000) colocate with (trade.customers)");
      fail("expected exception in incompatible colocation");
    } catch (SQLException sqle) {
      if (!"X0Y94".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("create table trade.networth (cid int not null, "
          + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
          + "availloan decimal (30, 20), tid int, constraint netw_pk "
          + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
          + "references trade.customers (cid) on delete restrict, "
          + "constraint cash_ch check (cash>=0), constraint sec_ch check "
          + "(securities >=0), constraint availloan_ck check "
          + "(loanlimit>=availloan and availloan >=0)) BUCKETS 7 "
          + "partition by column (cid) colocate with (trade.customers)");
      fail("expected exception in incompatible colocation");
    } catch (SQLException sqle) {
      if (!"X0Y95".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    removeExpectedException(new Object[] { "X0Y94", "X0Y95" });

    // finally success with explicit colocation with or without explicit
    // BUCKETS clause
    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0)) "
        + "partition by range (cid) (VALUES BETWEEN 0 AND 1666, "
        + "VALUES BETWEEN 1666 AND 3332, VALUES BETWEEN 3332 AND 4998, "
        + "VALUES BETWEEN 4998 AND 6664, VALUES BETWEEN 6664 AND 8330, "
        + "VALUES BETWEEN 8330 AND 10000) colocate with (trade.customers)");
    checkValidColocation_42952(stmt);
    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since date, addr varchar(100), "
        + "tid int, primary key (cid)) partition by range (cid) "
        + "(VALUES BETWEEN 0 AND 1666, VALUES BETWEEN 1666 AND 3332, "
        + "VALUES BETWEEN 3332 AND 4998, VALUES BETWEEN 4998 AND 6664, "
        + "VALUES BETWEEN 6664 AND 8330, VALUES BETWEEN 8330 AND 10000) "
        + "BUCKETS 7");
    stmt.execute("create table trade.networth (cid int not null, "
        + "cash decimal (30, 20), securities decimal (30, 20), loanlimit int, "
        + "availloan decimal (30, 20), tid int, constraint netw_pk "
        + "primary key (cid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict, "
        + "constraint cash_ch check (cash>=0), constraint sec_ch check "
        + "(securities >=0), constraint availloan_ck check "
        + "(loanlimit>=availloan and availloan >=0)) BUCKETS 7 "
        + "partition by range (cid) (VALUES BETWEEN 0 AND 1666, "
        + "VALUES BETWEEN 1666 AND 3332, VALUES BETWEEN 3332 AND 4998, "
        + "VALUES BETWEEN 4998 AND 6664, VALUES BETWEEN 6664 AND 8330, "
        + "VALUES BETWEEN 8330 AND 10000) colocate with (trade.customers)");
    checkValidColocation_42952(stmt);

    stmt.execute("drop table trade.networth");
    stmt.execute("drop table trade.customers");
  }

  /**
   * From Derby's SqlExceptionTest#testSerializedException
   */
  public void testSerializedException_43160() throws Exception {
    setupConnection();
    getStatement().execute(
        "CREATE TABLE tableWithPK (c1 int primary key, c2 int)");
    for (int i = 1; i <= 2; i++) {
      final Connection conn;
      // first with embedded connection
      if (i == 1) {
        conn = getConnection();
      }
      else {
        // next with network connection
        conn = startNetserverAndGetLocalNetConnection();
      }
      Statement stmt = conn.createStatement();
      try {
        // generate some exception by inserting some duplicate
        // primary keys in the same batch
        // This will generate some chained / nested transactions
        // as well
        String insertData = "INSERT INTO tableWithPK values "
            + "(1, 1), (2, 2), (3, 3), (4, 4), (5, 5)";
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.executeBatch();

        // In case the statement completes successfully which is not
        // expected. May not fail because replicated region insert
        // checks for value being the same while lastModifiedTime should
        // be different to qualify as a different update, so try again.
        Thread.sleep(10);
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.addBatch(insertData);
        stmt.executeBatch();

        fail("Unexpected: SQL statement should have failed");
      } catch (SQLException se) {
        // Verify the SQLException can be serialized (DERBY-790)
        SQLException se_ser = SqlExceptionTest.recreateSQLException(se);
        // and that the original and serialized exceptions are equals
        SqlExceptionTest.assertSQLState("Unexpected SQL State",
            se.getSQLState(), se_ser);
        SqlExceptionTest.assertSQLExceptionEquals(se, se_ser);
      } finally {
        stmt.execute("delete from tableWithPK");
        if (i == 2) {
          stopNetServer();
        }
      }
    }
    getStatement().execute("DROP TABLE tableWithPK");
  }

  public void testBug43889() throws Exception {
    setupConnection();
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    // first test for partitioned tables with PK partitioning
    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");

    stmt.execute("create table storesChild("
        + "id char(4) CONSTRAINT PK_id PRIMARY KEY, fk char(4),"
        + "constraint store_fk foreign key (fk) "
        + "references stores (stor_id) on delete restrict) "
        + "partition by primary key");

    try {
      stmt.execute("drop table stores");
      fail("expected exception in drop table");
    } catch (SQLException sqle) {
      if (!"X0Y25".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("drop table storesChild");
    stmt.execute("drop table stores");

    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");
    stmt.execute("drop table stores");

    // next test for partitioned tables with default partitioning
    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");

    stmt.execute("create table storesChild("
        + "id char(4) CONSTRAINT PK_id PRIMARY KEY, fk char(4),"
        + "constraint store_fk foreign key (fk) "
        + "references stores (stor_id) on delete restrict)");

    try {
      stmt.execute("drop table stores");
      fail("expected exception in drop table");
    } catch (SQLException sqle) {
      if (!"X0Y98".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("drop table storesChild");
    stmt.execute("drop table stores");

    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL)");
    stmt.execute("drop table stores");

    // lastly for replicated tables
    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL) replicate");

    stmt.execute("create table storesChild("
        + "id char(4) CONSTRAINT PK_id PRIMARY KEY, fk char(4),"
        + "constraint store_fk foreign key (fk) "
        + "references stores (stor_id) on delete restrict) replicate");

    try {
      stmt.execute("drop table stores");
      fail("expected exception in drop table");
    } catch (SQLException sqle) {
      if (!"X0Y25".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    stmt.execute("drop table storesChild");
    stmt.execute("drop table stores");

    stmt.execute("CREATE TABLE stores("
        + "stor_id        char(4)           NOT NULL"
        + "   CONSTRAINT UPK_storeid PRIMARY KEY,"
        + "stor_name      varchar(40)           NULL,"
        + "stor_address   varchar(40)           NULL,"
        + "city           varchar(20)           NULL,"
        + "state          char(2)               NULL,"
        + "zip            char(5)               NULL) replicate");
    stmt.execute("drop table stores");
  }

  public void testBug43720() throws Exception {
    setupConnection();
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();
    stmt.execute("create table temp (x int) partition by column (x)");
    assertEquals(1, stmt.executeUpdate("insert into temp values (1)"));
    assertEquals(1, stmt.executeUpdate("insert into temp values (2)"));
    assertEquals(1, stmt.executeUpdate("insert into temp values (3)"));
    stmt.execute("create view temp2 as select * from temp where x > 2");
    assertEquals(1, stmt.executeUpdate("insert into temp values (4)"));
    try {
      stmt.execute("drop table temp");
      fail("expected an exception in drop");
    } catch (SQLException sqle) {
      if (!"X0Y23".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    assertEquals(1, stmt.executeUpdate("insert into temp values (5)"));
  }

  public void testBug43628() throws Exception {
    setupConnection();
    // load the DDLs
    GemFireXDUtils.executeSQLScripts(jdbcConn, new String[] { getResourcesDir()
        + "/lib/bug43628.ddl" }, false, getLogger(), null, null, false);

    // now inserts into the table (problem of requiresSerializedHash not getting
    // updated by ALTER TABLE)
    PreparedStatement pstmt = jdbcConn.prepareStatement("INSERT INTO REVERSOS "
        + "(ID_TRANSACCION, FECHA_TELCEL, PUERTO) VALUES (?, ?, ?)");
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v);
      pstmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
      pstmt.setInt(3, v + 100);
      pstmt.execute();
    }

    // verify the inserts
    pstmt.close();
    pstmt = jdbcConn.prepareStatement("select id_transaccion from reversos "
        + "where puerto=?");
    ResultSet rs;
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v + 100);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(v, rs.getInt(1));
      assertFalse(rs.next());
    }
    pstmt.close();

    // now inserts into the second table (problem of parent having no PK but
    // colocated child having PK)
    pstmt = jdbcConn.prepareStatement("INSERT INTO REVERSOS2 "
        + "(ID_TRANSACCION, FECHA_TELCEL, PUERTO) VALUES (?, ?, ?)");
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v);
      pstmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
      pstmt.setInt(3, v + 100);
      pstmt.execute();
    }

    // verify the inserts
    pstmt.close();
    pstmt = jdbcConn.prepareStatement("select id_transaccion from reversos2 "
        + "where puerto=?");
    for (int v = 1; v <= 20; v++) {
      pstmt.setInt(1, v + 100);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(v, rs.getInt(1));
      assertFalse(rs.next());
    }
    pstmt.close();
  }

  public static void runIdentityChecksForCustomersTable(final Connection conn,
      final int numRows, final int[] cidPos, final String[] cidCols,
      final int numIdentityColumns, final int startValue, final int numServers,
      final DistributedSQLTestBase dunitTest, final boolean isGeneratedAlways) throws Exception {

    Statement stmt = conn.createStatement();
    // some inserts in the table
    TLongHashSet[] allCidValues = new TLongHashSet[numIdentityColumns];
    TLongHashSet cidValues;
    for (int index = 0; index < numIdentityColumns; index++) {
      cidValues = new TLongHashSet(2 * numRows);
      allCidValues[index] = cidValues;
    }
    // check for existing rows
    ResultSet rs;
    int totalRows = numRows;
    int maxValue = -1;
    PreparedStatement pstmtSel = conn
        .prepareStatement("select * from trade.customers");
    // startValue <= 0 indicates existing rows in table while > 0 indicates
    // no existing rows with given start value for the generated identity column
    if (startValue <= 0) {
      rs = pstmtSel.executeQuery();
      while (rs.next()) {
        totalRows++;
      }
      rs.close();
      if (startValue < 0) {
        rs = stmt.executeQuery("select max(cid) from trade.customers");
        rs.next();
        maxValue = rs.getInt(1);
        assertTrue("unexpected maxValue=" + maxValue + " startValue="
            + startValue, maxValue <= (-startValue));
        rs.close();
      }
    }
    int tid;
    int cnt1 = 0;
    PreparedStatement pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)");
    for (tid = 5; tid < (numRows / 4 + 5); tid++) {
      pstmt.setInt(1, tid);
      assertEquals(1, pstmt.executeUpdate());
      cnt1++;
    }
    // check for uniqueness of the inserted values
    rs = pstmtSel.executeQuery();
    while (rs.next()) {
      int index = 0;
      long cidVal;
      for (int pos : cidPos) {
        cidValues = allCidValues[index++];
        cidVal = rs.getLong(pos);
        if (!cidValues.add(cidVal)) {
          fail("unexpected duplicate for cid(" + pos + ") " + cidVal);
        }
        if (startValue > 0) {
          assertTrue("expected cid column to start from " + startValue
              + " but was " + cidVal, cidVal >= startValue);
        }
      }
    }
    rs.close();
    if (startValue < 0) {
      // check that newly inserted values are greater than existing startValue
      rs = pstmtSel.executeQuery();
      int cnt2 = 0;
      while (rs.next()) {
        boolean inc = false;
        long cidVal;
        for (int pos : cidPos) {
          cidVal = rs.getLong(pos);
          if (cidVal > maxValue) {
            inc = true;
          }
        }
        if (inc) {
          cnt2++;
        }
      }
      rs.close();
      assertEquals(cnt1, cnt2);
    }

    // check for auto-generated results
    VM vm;
    if (numServers > 0) {
      vm = dunitTest.getServerVM(1);
      Object[] result = (Object[])vm.invoke(CreateTableTest.class,
          "runIdentityChecks1", new Object[] { tid, numRows,
              numIdentityColumns, allCidValues });
      tid = (Integer)result[0];
      allCidValues = (TLongHashSet[])result[1];
    }
    else {
      Object[] result = runIdentityChecks1(tid, numRows, numIdentityColumns,
          allCidValues);
      tid = (Integer)result[0];
    }
    if (numServers > 1) {
      vm = dunitTest.getServerVM(2);
      Object[] result = (Object[])vm.invoke(CreateTableTest.class,
          "runIdentityChecks2", new Object[] { tid, numRows,
              numIdentityColumns, cidPos, allCidValues });
      tid = (Integer)result[0];
      allCidValues = (TLongHashSet[])result[1];
    }
    else {
      Object[] result = runIdentityChecks2(tid, numRows, numIdentityColumns,
          cidPos, allCidValues);
      tid = (Integer)result[0];
    }
    ResultSet autoGen;
    for (; tid < numRows + 5; tid++) {
      stmt.execute("insert into trade.customers (tid) values (" + tid + ")",
          cidCols);
      autoGen = stmt.getGeneratedKeys();
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
    }

    for (int index = 0; index < numIdentityColumns; index++) {
      assertEquals(totalRows, allCidValues[index].size());
    }

    // first check failure for invalid columns
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)",
        new int[] { cidPos[0] == 2 ? 1 : 2 });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0E".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)", new int[] { 7 });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0E".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    pstmt = conn
        .prepareStatement("insert into trade.customers (tid) values (?)",
            new String[] { "none" });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0F".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)", new String[] { "tid" });
    pstmt.setInt(1, 1);
    try {
      pstmt.execute();
      fail("expected failure for non auto-generated columns");
    } catch (SQLException sqle) {
      if (!"X0X0F".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    // also exception when trying to insert the always auto-generated column
    if (isGeneratedAlways) {
      try {
        stmt.execute("insert into trade.customers (cid, tid) values (1, 1)");
        fail("expected failure for non auto-generated columns");
      }
      catch (SQLException sqle) {
        if (!"42Z23".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
    }

    // now explicitly get the auto-incremented values during inserts

    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?)",
        Statement.RETURN_GENERATED_KEYS);
    // check for auto-gen result set for single inserts
    for (tid = 100; tid < (numRows / 4 + 100); tid++) {
      pstmt.setInt(1, tid);
      pstmt.execute();
      autoGen = pstmt.getGeneratedKeys();
      assertNotNull(autoGen);
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
    }
    // check for auto-gen result set for batch inserts
    int numNewRows = 0;
    for (; tid < (numRows / 2 + 100); tid++, numNewRows++) {
      pstmt.setInt(1, tid);
      pstmt.addBatch();
    }
    int[] batchResults = pstmt.executeBatch();
    assertEquals(numNewRows, batchResults.length);
    for (int res : batchResults) {
      assertEquals(1, res);
    }
    autoGen = pstmt.getGeneratedKeys();
    assertNotNull(autoGen);
    while (numNewRows-- > 0) {
      assertTrue(
          "expected next element with " + numNewRows + " rows remaining",
          autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
    }
    assertFalse(autoGen.next());
    // lastly check for auto-gen result set with multiple inserts in batch
    pstmt = conn.prepareStatement(
        "insert into trade.customers (tid) values (?), (?), (?), (?)",
        Statement.RETURN_GENERATED_KEYS);
    numNewRows = 0;
    for (; tid < (numRows + 100); numNewRows++) {
      pstmt.setInt(1, tid++);
      pstmt.setInt(2, tid++);
      pstmt.setInt(3, tid++);
      pstmt.setInt(4, tid++);
      pstmt.addBatch();
    }
    batchResults = pstmt.executeBatch();
    assertEquals(numNewRows, batchResults.length);
    for (int res : batchResults) {
      assertEquals(4, res);
    }
    autoGen = pstmt.getGeneratedKeys();
    assertNotNull(autoGen);
    numNewRows <<= 2;
    while (numNewRows-- > 0) {
      assertTrue(
          "expected next element with " + numNewRows + " rows remaining",
          autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
    }
    assertFalse(autoGen.next());

    for (int index = 0; index < numIdentityColumns; index++) {
      assertEquals(totalRows + numRows, allCidValues[index].size());
    }

    // also check using select
    for (int index = 0; index < numIdentityColumns; index++) {
      cidValues = allCidValues[index];
      cidValues.clear();
    }
    rs = pstmtSel.executeQuery();
    while (rs.next()) {
      int index = 0;
      for (int pos : cidPos) {
        cidValues = allCidValues[index++];
        if (!cidValues.add(rs.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") " + rs.getLong(pos));
        }
      }
    }
    rs.close();
    for (int index = 0; index < numIdentityColumns; index++) {
      assertEquals(totalRows + numRows, allCidValues[index].size());
    }

    if (startValue < 0) {
      // check that newly inserted values are greater than existing startValue
      rs = pstmtSel.executeQuery();
      int cnt2 = 0;
      while (rs.next()) {
        boolean inc = false;
        long cidVal;
        for (int pos : cidPos) {
          cidVal = rs.getLong(pos);
          if (cidVal > maxValue) {
            inc = true;
          }
        }
        if (inc) {
          cnt2++;
        }
      }
      rs.close();
      assertEquals(numRows * 2, cnt2);
    }

    pstmt.close();
    stmt.close();
  }

  public static Object[] runIdentityChecks1(int tid, final int numRows,
      final int numIdentityColumns, final TLongHashSet[] allCidValues)
      throws Exception {
    TLongHashSet cidValues;
    ResultSet autoGen;
    final PreparedStatement pstmt = getConnection().prepareStatement(
        "insert into trade.customers (tid) values (?)",
        Statement.RETURN_GENERATED_KEYS);
    for (; tid < numRows / 2 + 5; tid++) {
      pstmt.setInt(1, tid);
      assertEquals(1, pstmt.executeUpdate());
      autoGen = pstmt.getGeneratedKeys();
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
      autoGen.close();
    }
    return new Object[] { tid, allCidValues };
  }

  public static Object[] runIdentityChecks2(int tid, final int numRows,
      final int numIdentityColumns, final int[] cidPos,
      final TLongHashSet[] allCidValues) throws Exception {
    TLongHashSet cidValues;
    ResultSet autoGen;
    final PreparedStatement pstmt = getConnection().prepareStatement(
        "insert into trade.customers (tid) values (?)",
        cidPos);
    for (; tid < (numRows * 3) / 4 + 5; tid++) {
      pstmt.setInt(1, tid);
      assertEquals(1, pstmt.executeUpdate());
      autoGen = pstmt.getGeneratedKeys();
      assertTrue(autoGen.next());
      for (int pos = 1; pos <= numIdentityColumns; pos++) {
        cidValues = allCidValues[pos - 1];
        if (!cidValues.add(autoGen.getLong(pos))) {
          fail("unexpected duplicate for cid(" + pos + ") "
              + autoGen.getLong(pos));
        }
      }
      assertFalse(autoGen.next());
      autoGen.close();
    }
    return new Object[] { tid, allCidValues };
  }

  private void checkTableProperties(ResultSet rs) throws SQLException {
    final PartitionAttributesImpl pattrs1 = new PartitionAttributesImpl();
    pattrs1.setRedundantCopies(2);
    pattrs1.setRecoveryDelay(1000);
    final PartitionAttributesImpl pattrs2 = new PartitionAttributesImpl();
    pattrs2.setRedundantCopies(2);
    pattrs2.setColocatedWith("/TRADE/CUSTOMERS");
    pattrs2.setRecoveryDelay(1000);
    final EvictionAttributes evictAttrs = EvictionAttributes
        .createLRUHeapAttributes(new GfxdObjectSizer(),
            EvictionAction.OVERFLOW_TO_DISK);
    //final Properties diskProps = new Properties();
    //diskProps.put(DiskWriteAttributesImpl.SYNCHRONOUS_PROPERTY, "true");
    //final DiskWriteAttributesImpl diskAttrs = new DiskWriteAttributesImpl(
      //  diskProps);
    String diskAttribs = "DiskStore is "+ GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME
    + "; "+"Synchronous writes to disk";
    while (rs.next()) {
      if ("customers".equals(rs.getString("TABLENAME").toLowerCase())) {
        // check for DataPolicy and partition attributes
        assertEquals("PARTITION", rs.getString("DATAPOLICY"));
        assertEquals(pattrs1.getStringForGFXD(), rs.getString("PARTITIONATTRS"));
        // check for partition resolver
        assertEquals("PARTITION BY PRIMARY KEY", rs.getString("RESOLVER"));
        // check for eviction attributes
        assertEquals(evictAttrs.toString(), rs.getString("EVICTIONATTRS"));
        // check for disk attributes
        // null check for other attributes
        assertNull(rs.getString("EXPIRATIONATTRS"));
        // ??ezoerner:20100318 should this be sync or async?
        assertEquals(diskAttribs,rs.getString("DISKATTRS"));
        assertNull(rs.getString("LOADER"));
        assertNull(rs.getString("WRITER"));
        assertNull(rs.getString("LISTENERS"));
        assertNull(rs.getString("ASYNCLISTENERS"));
        assertNull(rs.getObject("ASYNCLISTENERS"));
        assertFalse(rs.getBoolean("GATEWAYENABLED"));
      }
      else if ("networth".equals(rs.getString("TABLENAME").toLowerCase())) {
        // check for DataPolicy and partition attributes
        assertEquals("PARTITION", rs.getString("DATAPOLICY"));
        assertEquals(pattrs2.getStringForGFXD(), rs.getString("PARTITIONATTRS"));
        // check for partition resolver
        assertEquals("PARTITION BY COLUMN (TID)", rs.getString("RESOLVER"));
        // check for eviction attributes
        assertEquals(evictAttrs.toString(), rs.getString("EVICTIONATTRS"));
        // check for disk attributes
        assertEquals(diskAttribs, rs.getString("DISKATTRS"));
        // null check for other attributes
        assertNull(rs.getString("EXPIRATIONATTRS"));
        assertNull(rs.getString("LOADER"));
        assertNull(rs.getString("WRITER"));
        assertNull(rs.getString("LISTENERS"));
        assertNull(rs.getObject("ASYNCLISTENERS"));
        assertNull(rs.getString("ASYNCLISTENERS"));
        assertFalse(rs.getBoolean("GATEWAYENABLED"));
      }
    }
  }

  private void checkValidColocation_42952(final Statement stmt)
      throws SQLException {
    Region<Object, Object> custReg = Misc.getRegionForTable("TRADE.CUSTOMERS",
        true);
    Region<Object, Object> netReg = Misc.getRegionForTable("TRADE.NETWORTH",
        true);
    PartitionAttributes<?, ?> custAttrs = custReg.getAttributes()
        .getPartitionAttributes();
    PartitionAttributes<?, ?> netAttrs = netReg.getAttributes()
        .getPartitionAttributes();

    assertEquals(7, custAttrs.getTotalNumBuckets());
    assertEquals(7, netAttrs.getTotalNumBuckets());
    assertEquals("/TRADE/CUSTOMERS", netAttrs.getColocatedWith());

    // check the resolver for networth table
    PartitionResolver<?, ?> custResolver = custAttrs.getPartitionResolver();
    PartitionResolver<?, ?> netResolver = netAttrs.getPartitionResolver();
    assertEquals(GfxdRangePartitionResolver.class, custResolver.getClass());
    assertEquals(GfxdRangePartitionResolver.class, netResolver.getClass());

    GfxdRangePartitionResolver custRangeResolver =
        (GfxdRangePartitionResolver)custResolver;
    GfxdRangePartitionResolver netRangeResolver =
        (GfxdRangePartitionResolver)netResolver;
    assertTrue(custRangeResolver.getDDLString().contains("3332"));
    assertTrue(custRangeResolver.getDDLString().contains("8330"));
    assertEquals(custRangeResolver.getDDLString(),
        netRangeResolver.getDDLString());

    // do a few inserts in parent and child
    stmt.execute("insert into trade.customers (cid, tid) values (1000, 2000)");
    stmt.execute("insert into trade.customers (cid, tid) values (2000, 4000)");
    stmt.execute("insert into trade.customers (cid, tid) values (3000, 6000)");
    stmt.execute("insert into trade.customers (cid, tid) values (10000, 20000)");
    stmt.execute("insert into trade.customers (cid, tid) values (20000, 40000)");
    stmt.execute("insert into trade.customers (cid, tid) values (30000, 60000)");

    stmt.execute("insert into trade.networth (cid, tid) values (1000, 2000)");
    stmt.execute("insert into trade.networth (cid, tid) values (2000, 4000)");
    stmt.execute("insert into trade.networth (cid, tid) values (3000, 6000)");
    stmt.execute("insert into trade.networth (cid, tid) values (10000, 20000)");
    // check for FK violation
    addExpectedException("23503");
    try {
      stmt.execute("insert into trade.networth (cid, tid) values (40000, 80000)");
      fail("expected FK violation");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("insert into trade.networth (cid, tid) values (50000, 50000)");
      fail("expected FK violation");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    removeExpectedException("23503");
    // few more valid inserts
    stmt.execute("insert into trade.networth (cid, tid) values (20000, 40000)");
    stmt.execute("insert into trade.networth (cid, tid) values (30000, 60000)");
  }

  public void testBug45803() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    // Create some partitioned tables, partitioned by range
    // But with invalid ranges (end value is less than or equal to start)
    // Each should fail with 0A000 : "Not supported : Begin range not less than end range"
    boolean gotException = false;
    try {
      // Start cannot be greater than end
      s.execute("create table BADRANGE1 (ID int not null) "
          + "PARTITION BY RANGE(ID) (VALUES BETWEEN 5 and 2)");
    } catch (SQLException e) {
      if ("0A000".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    gotException = false;

    try {
      // Start and end cannot be equal
      s.execute("create table BADRANGE1 (ID int not null) "
          + "PARTITION BY RANGE(ID) (VALUES BETWEEN 5 and 5)");
    } catch (SQLException e) {
      if ("0A000".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    gotException = false;

    try {
      // DATE values in wrong order
      s.execute("create table BADRANGE1 (ID date not null) "
          + "PARTITION BY RANGE(ID) (VALUES BETWEEN '1972-05-01' AND '1964-07-14')");
    } catch (SQLException e) {
      if ("0A000".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
  }

  public void testBug45808() throws SQLException, StandardException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    // Create some partitioned tables, partitioned by column
    // But with invalid column lists (dups or columns that aren't in table)
    // Each should fail with 42X12 (column dup) or 42X01 (column not found)
    boolean gotException = false;
    try {
      // Duplicate column names
      s.execute("create table BADCOL1 (ID int not null, PHONENUM varchar(15)) "
          + "PARTITION BY COLUMN(PHONENUM, ID, PHONENUM)");
    } catch (SQLException e) {
      if ("42X12".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
    gotException = false;

    try {
      // Column name in partition clause isn't in table
      s.execute("create table BADCOL2 (ID int not null, PHONENUM varchar(15)) "
          + "PARTITION BY COLUMN(PHONENUM, LASTNAME, ID)");
    } catch (SQLException e) {
      if ("42X01".equalsIgnoreCase(e.getSQLState())) {
        gotException = true;
      }
    }
    assertTrue(gotException);
  }
  
  public void testBug50069() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    try {
      st.execute("CREATE TABLE T.TABLE_DATA (ID VARCHAR (36) NOT NULL , CONSTRAINT DATA_PK PRIMARY KEY(ID)) "
          + "PARTITION BY PRIMARY KEY REDUNDANCY 1 PERSISTENT ASYNCHRONOUS OFFHEAP");
      fail("Expected IllegalStateException due to no off-heap memory being configured.");
  } catch (SQLException expected) {
      if (!(expected.getCause() instanceof IllegalStateException)) {
        fail("Caught unexpected exception", expected);
      }
    }
  }
  
  public void testMemScaleDDL() throws Exception {
    System.setProperty("gemfire.off-heap-memory-size", "500m");
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    
    st.execute("create table mytab(col1 int primary key, col2 varchar(10)) offheap");
    LocalRegion region = (LocalRegion)Misc.getRegion("APP/MYTAB", true, false);
    RegionAttributes<?, ?> ra = region.getAttributes();
    assertTrue(ra.getEnableOffHeapMemory());
    
    PreparedStatement ps = conn.prepareStatement("insert into mytab values (?, ?)");
    for (int i = 0; i < 12345; i++) {
      ps.setInt(1, i);
      ps.setString(2, "abcdefghij");
      ps.execute();
    }
    
    st.execute("select * from mytab where col1 = 7");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(7, rs.getInt(1));
    assertEquals("abcdefghij", rs.getString(2));
    rs.close();
  }
  
  class ColumnMetaData {
    int columnDisplaySize;
    String columnName;
    int columnType;
    int precision;
    int scale;
    int isNullable;
    
    ColumnMetaData(int columnDisplaySize, String columnName, int columnType,
        int precision, int scale, int isNullable) {
      this.columnDisplaySize = columnDisplaySize;
      this.columnName = columnName;
      this.columnType = columnType;
      this.precision = precision;
      this.scale = scale;
      this.isNullable = isNullable;
    }
    
    @Override
    public boolean equals(Object other) {
      ColumnMetaData o = (ColumnMetaData) other;
      return this.columnDisplaySize == o.columnDisplaySize &&
          this.columnName.equals(o.columnName) &&
          this.columnType == o.columnType &&
          this.precision == o.precision &&
          this.scale == o.scale &&
          this.isNullable == o.isNullable;
          
    }
  };
  
  public ColumnMetaData[] getColumnMetaData(Statement stmt, String query)
      throws SQLException {
    ResultSet rs1 = stmt.executeQuery(query);
    assertTrue(rs1.next());
    
    ResultSetMetaData rsm = rs1.getMetaData();
    int columnCount = rsm.getColumnCount();

    ColumnMetaData[] columnMetaData = new ColumnMetaData[columnCount];
    for (int i = 1; i <= columnCount; i++) {
      int columnDisplaySize = rsm.getColumnDisplaySize(i);
      String columnName = rsm.getColumnName(i);
      int columnType = rsm.getColumnType(i);
      int precision = rsm.getPrecision(i);
      int scale = rsm.getScale(i);
      int isNullable = rsm.isNullable(i);

      columnMetaData[i - 1] = new ColumnMetaData(columnDisplaySize,
          columnName, columnType, precision, scale, isNullable);
    }
    rs1.close();
    return columnMetaData;
  }
  
  // #50116: For 'create table t2 as select * from t1' query replace the 
  // sqltext in DDLConflatable by a generated SQL text that has columns 
  // definitions instead of the select clause so that even if the base table
  // (t1) is dropped, DDL replay will not fail
  public void test50116_CTAS() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();
    
    // base table1
    stmt.execute("create table trade.base1 "
        + "(oid int not null constraint orders_pk primary key, "
        + "cid int default 4, sid int, " + "qty int, ask decimal (30, 20), "
        + "order_time timestamp, status varchar(10), "
        + "tid int) partition by (oid) persistent");

    // base table2
    stmt.execute("create table trade.base2 "
        + "(oid int not null constraint orders_pk2 primary key, "
        + "cid int default 4, sid int, " + "qty int, ask decimal (30, 20), "
        + "order_time timestamp, status varchar(10), "
        + "tid int) partition by (oid) persistent");

    // derived tables created with 'create table <derived> as select from
    // <base>'
    stmt.execute("create table trade.derived1 as select * from "
        + "trade.base1 with no data persistent");
    stmt.execute("create table trade.derived2(a, b, c, d, e, f, g, h) as " +
            "select * from trade.base1 with no data persistent");
    stmt.execute("create table trade.derived3(a, g) as select oid, status " +
            "from trade.base1 with no data persistent");
    stmt.execute("create table trade.derived4 as select oid, status from "
        + "trade.base1 with no data persistent");
    stmt.execute("create table trade.derived5 as select trade.base1.oid, " +
            "trade.base2.status from trade.base1 join trade.base2 on " +
            "trade.base1.oid = trade.base2.oid with no data persistent");
    stmt.execute("create table trade.derived6(a, g) as select oid*2, status " +
            "from trade.base1 with no data persistent");
    
    //insert some data in each derived table
    stmt.execute("insert into trade.derived1 values (1, 1, 1, 1, 1.1, " +
            "'1994-01-01 23:59:59', 'STATUS', 1)");
    stmt.execute("insert into trade.derived2 values (1, 1, 1, 1, 1.1, " +
            "'1994-01-01 23:59:59', 'STATUS', 1)");
    stmt.execute("insert into trade.derived3 values (1,'STATUS')");
    stmt.execute("insert into trade.derived4 values (1,'STATUS')");
    stmt.execute("insert into trade.derived5 values (1,'STATUS')");
    stmt.execute("insert into trade.derived6 values (1,'STATUS')");
    
    // grab the column meta data before restart 
    ColumnMetaData[] derived1_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived1");
    ColumnMetaData[] derived2_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived2"); 
    ColumnMetaData[] derived3_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived3"); 
    ColumnMetaData[] derived4_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived4");
    ColumnMetaData[] derived5_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived5"); 
    ColumnMetaData[] derived6_meta_old = getColumnMetaData(stmt,
        "select * from trade.derived6"); 
        
    //drop base tables 
    stmt.execute("drop table trade.base1");
    stmt.execute("drop table trade.base2");
    
    shutDown();
    conn = TestUtil.getConnection(props);
    stmt = conn.createStatement();
    
    // grab the column meta data after restart and make sure that it
    // is same as that of the one before restart
    ColumnMetaData[] derived1_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived1"); 
    assertTrue(Arrays.equals(derived1_meta_old, derived1_meta_after_restart));
    
    ColumnMetaData[] derived2_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived2"); 
    assertTrue(Arrays.equals(derived2_meta_old, derived2_meta_after_restart));
    
    ColumnMetaData[] derived3_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived3"); 
    assertTrue(Arrays.equals(derived3_meta_old, derived3_meta_after_restart));
    
    ColumnMetaData[] derived4_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived4");
    assertTrue(Arrays.equals(derived4_meta_old, derived4_meta_after_restart));
    
    ColumnMetaData[] derived5_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived5");
    assertTrue(Arrays.equals(derived5_meta_old, derived5_meta_after_restart));
    
    ColumnMetaData[] derived6_meta_after_restart = getColumnMetaData(stmt,
        "select * from trade.derived6");
    assertTrue(Arrays.equals(derived6_meta_old, derived6_meta_after_restart));
  }
  
  public void test50116_50757_CTAS2() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmt = conn.createStatement();
    
    stmt.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' " +
    		"LANGUAGE JAVA");
    
    stmt.execute("create table trade.all_datatypes " +
         "(col1 bigint, " +
         "col2 blob, " +
         "col3 char(2), " +
         "col4 char(10) for bit data, " +
         "col5 clob, " +
         "col6 date, " +
         "col7 decimal(10, 6), " +
         "col8 double, " +
         "col9 float, " +
         "col10 integer, " +
         "col11 long varchar, " +
         "col12 long varchar for bit data, " +
         "col13 numeric(10, 6), " +
         "col14 real, " +
         "col15 smallint, " +
         "col16 time, " +
         "col17 timestamp, " +
         "col18 trade.UUID, " +
         "col19 varchar(2), " +
         "col20 varchar(10) for bit data) ");
    
    stmt.execute("create table trade.all_datatypes_derived as select * from "
        + "trade.all_datatypes with no data persistent");
    
    PreparedStatement pstmt = conn.prepareStatement("insert into " +
    		"trade.all_datatypes_derived values " +
            "(1, " +
            "cast(X'0031' as blob), " +
            "'a', " +
            "x'102030', " +
            "'abc', " +
            "'1998-04-18', " +
            "1.1, " +
            "1.1, " +
            "1.1, " +
            "10, " +
            "'aa', " +
            "x'102030', " +
            "1.1, " +
            "1.1, " +
            "1, " +
            "'00:00:00', " +
            "'1994-01-01 23:59:59', " +
            "?, "   +  // UUID
            "'aa', " +
            "x'102030')");
    
    UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
    pstmt.setObject(1, uid); //col18 UUID
    pstmt.execute();
        
    String query = "select * from trade.all_datatypes_derived";
    
    // grab the column meta data before restart 
    ColumnMetaData[] derived_meta_old = getColumnMetaData(stmt,
        query);
    
    //drop base tables 
    stmt.execute("drop table trade.all_datatypes");
    shutDown();
    conn = TestUtil.getConnection(props);
    stmt = conn.createStatement();
    
    // grab the column meta data after restart and make sure that it
    // is same as that of the one before restart 
    ColumnMetaData[] derived_meta_after_restart = getColumnMetaData(stmt,
        query);
    assertTrue(Arrays.equals(derived_meta_old, derived_meta_after_restart));
  }
}
