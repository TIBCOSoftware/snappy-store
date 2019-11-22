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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;



import junit.framework.TestSuite;
import junit.textui.TestRunner;

/**
 * test for inserts, updates and maintenance of primary key index
 *
 * @author Rahul Dubey
 */
public class PkAsRegionKeyTest extends JdbcTestBase {
	
  public PkAsRegionKeyTest (String name ) {
	super(name);
  }
  
  public static void main(String[] args) {
	TestRunner.run(new TestSuite(PkAsRegionKeyTest.class));
  }
  
  /**
   * Test for pk index creation and index insert.
   * @throws SQLException
   */
  
  public void testPkAsRegionKeySingleColumn() throws SQLException {
	
	Connection conn = getConnection();      
	Statement s = conn.createStatement();
	s.execute("create table t1 (c1 int primary key, c2 char(200))");
	s.execute("insert into t1 (c1, c2) values (10, 'YYYY')");
	s.execute("insert into t1 (c1, c2) values (20, 'YYYY')");
	ResultSet rs = s.executeQuery("select * from t1 where t1.c1=10");

  assertTrue("No rows in ResultSet", rs.next());      
  int num = rs.getInt(1);
  assertEquals("Wrong row returned" + num, 10, num);  
  assertFalse("Expected a result set containing one row " +
              "but there is another with " + rs.getInt(1),
              rs.next());
    s.execute("drop table t1");
  }
  
  /**
   * Test for pk index creation with multi cloumn pk index.
   * @throws SQLException
   */
  public void testPkAsRegionKeyMultiColumn() throws SQLException {
	  Connection conn = getConnection();      
	  Statement s = conn.createStatement();
	  PreparedStatement psInsert = null;
	  ResultSet rs = null;
	  
	  s.execute("create table t1 (c1 int not null , c2 int not null, c3 int not null," +
	  		" c4 varchar (200) , PRIMARY KEY (c1, c2, c3))");
	  
	  psInsert = conn.prepareStatement("insert into t1 ( c1, c2, c3, c4 ) " +
	  		"values (?, ?, ?, ?)");
	  
	  int numInsert = 10;
	  int intValue = 0;
	  for (int i = 0 ; i < numInsert ; i++ ) {
      psInsert.setInt(1, (intValue + 10 ));
      psInsert.setInt(2, (intValue + 20));
      psInsert.setInt(3, (intValue + 30));
      psInsert.setString(4, "YYYY");
      intValue+=10;
      int r = psInsert.executeUpdate();
      assertEquals("Insert failed at : " + i, 1, r);
	  }
	  
	  rs = s.executeQuery("Select * from t1 where t1.c1 = 10");
	  
    assertTrue("No rows in ResultSet", rs.next());
    int num = rs.getInt(1);
    assertEquals("Wrong row returned", 10, num);
    assertFalse("Expected a result set conatining one row " +
                "but there is another with " + rs.getInt(1),
                rs.next());
    s.execute("drop table t1");
  }
  
  /**
   * Test primary key index with alter table command.
   */
  public void _testSingleColumnPkWithAlterTable() throws SQLException {
    Connection conn = getConnection();      
    Statement s = conn.createStatement();
    PreparedStatement psInsertBroker = null;
    PreparedStatement psInsertBrokerTickets = null;
    s.execute("Create table brokers (id int not null, name varchar(200))");
    s.execute("alter table brokers add primary key (id)");
    psInsertBroker = conn.prepareStatement("insert into brokers ( id, name ) " +
    		"values (?, ?)");
    int numInserts = 10;
    int key = 0;
    for (int i = 0 ; i < numInserts ; i++) {
      psInsertBroker.setInt(1,key+10);
      psInsertBroker.setString(2, "YYYY"+key);
      int ret = psInsertBroker.executeUpdate();
      key += 10;
      assertEquals("Insert should always retrun 1 row updated", ret, 1);
    }
    
    ResultSet rs = s.executeQuery("Select * from brokers");
    int numResults = 0;
    while (rs.next()) {
      numResults++;
    }
    assertEquals("Number of rows in result set should be " +
                  "equal to number of inserts",
                 numInserts,
                 numResults);    
    
    s.execute("Create table broker_tickets (id int not null, brokerId int, " +
    		"price double, quantity int, ticker varchar(20))");
    
    s.execute("alter table broker_tickets add primary key(id)");
    
    psInsertBrokerTickets= conn.prepareStatement("insert into broker_tickets " +
    		"(id, brokerId, price, quantity, ticker ) values (?, ?, ?, ?, ?)");
    
    key = 0;
    double price = 1.0;
    int quantity = 10;
    for (int i = 0; i < numInserts ; i++) {
      psInsertBrokerTickets.setInt(1, key +10);
      psInsertBrokerTickets.setInt(2, key+10);
      psInsertBrokerTickets.setDouble(3, price);
      psInsertBrokerTickets.setInt(4, quantity);
      psInsertBrokerTickets.setString(5, "YYYY"+key);
      int rt = psInsertBrokerTickets.executeUpdate();
      assertEquals("Insert should always retrun 1 row updated", rt, 1);
      key+=10;
      price += 0.01;
      
    }
    
    rs = s.executeQuery("select * from broker_tickets");
    
    numResults = 0;
    
    while (rs.next()) {
      numResults++;
    }
    assertEquals("Number of rows in result set should be " +
                 "equal to number of inserts",
                 numInserts,
                 numResults);    
    
    
    rs = s.executeQuery("select b.id,b.name from brokers b,broker_tickets bt " +
    		"where bt.brokerId = b.id and bt.price >= 1 and bt.price < 2");
    
    numResults = 0;
    while (rs.next()) {
      numResults++;
    }
    
    assertEquals("Number of rows in result set should be " +
                 "equal to number of inserts",
                 numInserts,
                 numResults);    
       
    rs = s.executeQuery("Select id from brokers");
    numResults = 0;
    while (rs.next()) {
      numResults++;
    }
    
    assertEquals("Number of rows in result set should be " +
                 "equal to number of inserts",
                 numInserts,
                 numResults);    
    
    rs = s.executeQuery("select b.id,b.name from brokers b,broker_tickets bt " +
    		"where bt.brokerId = b.id and bt.price >= 1 and bt.price < 2");
    
    numResults = 0;
    while (rs.next()) {
      numResults++;
    }
    
    assertEquals("Number of rows in result set should be " +
                 "equal to number of inserts",
                 numInserts,
                 numResults);    
    
   
    
    rs = s.executeQuery("select b.id,b.name from brokers b,broker_tickets bt " +
    		"where bt.brokerId = b.id and bt.price >= 1 and bt.price < 2");
    
    numResults = 0;
    while (rs.next()) {
      numResults++;
    }
    
    assertEquals("Number of rows in result set should be " +
                 "equal to number of inserts",
                 numInserts,
                 numResults);    
    
  }
  
  /**
   * Test to check pk index is maintained properly with updates to non PK fields
   * in the table.
   * 
   * @throws SQLException
   */
  
  public void _testSingleColumnPkUpdate() throws SQLException {
	  Connection conn = getConnection();      
	  Statement s = conn.createStatement();
	  PreparedStatement psInsert = null;
	  PreparedStatement psUpdate = null;
	  //s.execute("create table t1 (c1 int primary key, c2 char(200))");
	  s.execute("create table t1 (c1 int not null , c2 int not null, c3 int not null, " +
	  		"c4 varchar (200) , PRIMARY KEY (c1, c2, c3))");
	  
	  int key = 0;
	  int numInsert = 100;
	  psInsert = conn.prepareStatement("insert into t1 ( c1, c2, c3, c4 ) " +
	  		"values (?, ?, ?, ?)");
	  
	  for (int i = 0; i < numInsert ; i++){
	    psInsert.setInt(1, (key+ 10));
	    psInsert.setInt(2, (key + 20));
	    psInsert.setInt(3, (key + 30));
	    psInsert.setString(4, "YYYY");
	    int re = psInsert.executeUpdate();
	    //System.out.println("Inserted : "+re);
      assertEquals("Insert should always retrun 1 row updated", re, 1);
	    key+=10;
	  }
	  //s.execute("insert into t1 (c1, c2) values (10, 'YYYY')");
	 // s.execute("insert into t1 (c1, c2) values (20, 'YYYY')");
	  ResultSet rs = s.executeQuery("select * from t1 where t1.c1=10 and t1.c2=20 " +
	  		"and t1.c3=30");
	  
    assertTrue("No rows in ResultSet", rs.next());
    assertEquals("Wrong row returned", 10, rs.getInt(1));
    assertEquals("Wrong row returned", 20, rs.getInt(2));
    assertEquals("Wrong row returned", 30, rs.getInt(3));
    assertFalse("Expected a result set conatining one row " +
                  "but there is another with " + rs.getInt(1),
                rs.next());

	  int startValue = 0;
	  String updateValue = "DDDD";
	  psUpdate = conn.prepareStatement("update t1 set c4=? where c1=? And c2 = ? " +
	  		"And c3= ?");
	  
	  for (int i = 0 ; i < 1; i++) {
	    
	    updateValue = "DDDD"+i;
	    psUpdate.setString(1, updateValue);
	    psUpdate.setInt(2, startValue + 10);
	    psUpdate.setInt(3, startValue + 20);
	    psUpdate.setInt(4, startValue + 30);
	    int ret = psUpdate.executeUpdate();
      assertEquals("Insert should always return 1 row updated", ret, 1);

	    rs = s.executeQuery("select * from t1 where t1.c1=" + ( startValue +10 ) +
                          " And t1.c2= " + ( startValue + 20 ) + " and t1.c3=" +
                          ( startValue + 30 ));

      assertTrue("ResultSet should not be empty, should contain one row.",
                 rs.next());

      assertEquals("ResultSet mismatch", updateValue, rs.getString(3).trim());
	    
	    startValue +=10;

	  }

	  

  }
  
  /**
   * Test selection of non indexed columns based on multi-column primary key.
   * @throws SQLException
   */  
  public void disabled_testMulitColumnPkSelection() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    PreparedStatement psInsertBrokerTickets = null;

    s.execute("Create table broker_tickets (id int not null, brokerId int, " +
    		"firmId int,  price double, quantity int, ticker varchar(20), " +
    		"PRIMARY KEY (id, brokerId, firmId ))");
    
    psInsertBrokerTickets = conn.prepareStatement("insert into broker_tickets " +
    		"(id, brokerId, firmId,  price, quantity, ticker ) " +
    		"values (?, ?, ?, ?, ?, ?)");
    
    int numInserts = 5;
    int key = 0;
    double price = 1.0;
    int quantity = 10;
    for (int i = 0; i < numInserts; i++) {
      psInsertBrokerTickets.setInt(1, key + 10);
      psInsertBrokerTickets.setInt(2, key + 10);
      psInsertBrokerTickets.setInt(3, key + 10);
      psInsertBrokerTickets.setDouble(4, price);
      psInsertBrokerTickets.setInt(5, quantity);
      psInsertBrokerTickets.setString(6, "YYYY" + key);
      int rt = psInsertBrokerTickets.executeUpdate();
      assertEquals("Insert should always return 1 row updated", rt, 1);
      key += 10;
      price += 0.01;

    }

    ResultSet rs = s.executeQuery("Select * from broker_tickets");
    int numResults = 0;
    key = 0;
    while (rs.next()) {
      String ticker = rs.getString(6);
      if (!(ticker.trim().equalsIgnoreCase("YYYY" + key))) {
        fail("Expected colummn value : " + ("YYYY" + key) + " but  : "
            + ticker.trim() + " was found  and other column are 1st : "
            + rs.getInt(1) + " 2nd " + rs.getInt(2) + " 3rd " + rs.getInt(3)
            + " 4th " + rs.getDouble(4) + " 5th " + rs.getInt(5));
      }
      key += 10;
      numResults++;
    }
    
    assertEquals("Number of rows in result set should be " +
                 "equal to number of inserts",
                 numInserts,
                 numResults);    

    key = 0;
    for (int i = 0; i < numInserts; i++) {
      rs = s.executeQuery("select * from broker_tickets where id=" + (key + 10)
          + "and brokerId=" + (key + 10) + "and firmId=" + (key + 10));

      numResults = 0;

      // this will fail with Invalid character string format
      // for type Double because for bug in MemHeapController fetch.
      assertTrue("ResultSet should have one row.", rs.next());
      
      String ticker = rs.getString(6);
      
      assertEquals("YYYY" + key, ticker.trim());
    }
  }
  
  /**
   * Test query without primary key on base table. Checks long rowIds are still working.
   * @throws SQLException
   */
  public void testQueryWithOutPk() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    PreparedStatement psInsertBrokerTickets = null;
    s.execute("Create table broker_tickets (id int not null, brokerId int, " +
    		"firmId int,  price double, quantity int, ticker varchar(20))");
    
    psInsertBrokerTickets = conn.prepareStatement("insert into broker_tickets " +
    		"(id, brokerId, firmId,  price, quantity, ticker ) " +
    		"values (?, ?, ?, ?, ?, ?)");
    
    int numInserts = 5;
    int key = 0;
    double price = 1.0;
    int quantity = 10;
    for (int i = 0; i < numInserts; i++) {
      psInsertBrokerTickets.setInt(1, key + 10);
      psInsertBrokerTickets.setInt(2, key + 10);
      psInsertBrokerTickets.setInt(3, key + 10);
      psInsertBrokerTickets.setDouble(4, price);
      psInsertBrokerTickets.setInt(5, quantity);
      psInsertBrokerTickets.setString(6, "YYYY" + key);
      int rt = psInsertBrokerTickets.executeUpdate();
      assertEquals( 1, rt);
      key += 10;
      price += 0.01;

    }
    int searchKey = 10;

    ResultSet rs = s.executeQuery("Select id, brokerId, firmId " +
    		"from broker_tickets where id="+searchKey+"and brokerId="+searchKey+
    		"and firmId="+searchKey);
    
    if (!rs.next()){
      fail("ResultSet should have at least one row.");
    }
    
    assertEquals(searchKey, rs.getInt(1) );
    
    int numResults = 0;
    
    rs = s.executeQuery("Select * from broker_tickets");
    
    while(rs.next()){
      numResults++;
    }
    
    assertEquals(numInserts, numResults );
    
    s.execute("Create index idIndex on broker_tickets(id)");
    searchKey = 0;
    for (int i = 0 ; i < numInserts ;i++) {
      rs  = s.executeQuery("Select * from broker_tickets where id = "
          +(searchKey+10));
      
      assertTrue("Expected one row with id ="+(searchKey+10), rs.next());
      assertEquals((searchKey+10),rs.getInt(1) );
      assertEquals((searchKey+10),rs.getInt(2) );
      assertEquals((searchKey+10),rs.getInt(3) );
      
      searchKey+=10;
    }
    
    searchKey = 0;
    
    for (int i = 0 ; i < numInserts ;i++) {
      rs  = s.executeQuery("Select id, brokerId from broker_tickets where id = " +
      		""+(searchKey+10));
      
      assertTrue("Expected one row with id ="+(searchKey+10), rs.next());
      assertEquals((searchKey+10), rs.getInt(1) );
      assertEquals((searchKey+10), rs.getInt(2) );
              
      searchKey+=10;
    }
    s.execute("drop table broker_tickets");
  }

  /**
   * Test RegionEntry in row location.
   * 
   * @throws SQLException
   */
  public void testRegionEntryAsRowLocation() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    PreparedStatement psUpdateBrokerTickets = null;
    PreparedStatement psInsertBrokerTickets = null;
    s.execute("Create table broker_tickets (id int not null, "
        + "brokerId int not null, firmId int not null, price double, "
        + "quantity int, ticker varchar(20), PRIMARY KEY (id, brokerId))");

    psInsertBrokerTickets = conn.prepareStatement("insert into broker_tickets "
        + "(id, brokerId, firmId,  price, quantity, ticker ) "
        + "values (?, ?, ?, ?, ?, ?)");

    int numInserts = 5;
    int key = 0;
    double price = 1.0;
    int quantity = 10;
    for (int i = 0; i < numInserts; i++) {
      psInsertBrokerTickets.setInt(1, key + 10);
      psInsertBrokerTickets.setInt(2, key + 10);
      psInsertBrokerTickets.setInt(3, key + 10);
      psInsertBrokerTickets.setDouble(4, price);
      psInsertBrokerTickets.setInt(5, quantity);
      psInsertBrokerTickets.setString(6, "YYYY" + key);
      int rt = psInsertBrokerTickets.executeUpdate();
      assertEquals("Insert should return 1.", 1, rt);
      key += 10;
      price += 0.01;
    }
    int searchKey = 10;

    ResultSet rs = s.executeQuery("Select id, brokerId, firmId from "
        + "broker_tickets where id=" + searchKey + "and brokerId=" + searchKey
        + "and firmId=" + searchKey);

    if (!rs.next()) {
      fail("ResultSet should have at least one row.");
    }
    int expected = 20; // debuggin.
    rs = s.executeQuery("select firmId from broker_tickets where firmId ="
        + expected);
    assertTrue("ResultSet should have on row with firmId " + expected,
        rs.next());
    assertEquals(expected, rs.getInt(1));

    s.execute("create index broker_firmId on broker_tickets(firmId)");

    searchKey = 0;

    for (int i = 0; i < numInserts; i++) {
      rs = s.executeQuery("Select * from broker_tickets where firmId="
          + (searchKey + 10));
      if (!rs.next()) {
        fail("ResultSet should have atleast one row.");
      }
      assertEquals("Expected value not found ", (searchKey + 10), rs.getInt(1));
      assertEquals("Expected value not found ", (searchKey + 10), rs.getInt(2));
      assertEquals("Expected value not found ", (searchKey + 10), rs.getInt(3));

      searchKey += 10;
    }

    psUpdateBrokerTickets = conn.prepareStatement("Update broker_tickets "
        + "set firmId = ? where firmId = ?");

    key = 0;
    for (int i = 0; i < numInserts; i++) {
      psUpdateBrokerTickets.setInt(1, (key + 10) * 10);
      psUpdateBrokerTickets.setInt(2, (key + 10));
      int result = psUpdateBrokerTickets.executeUpdate();
      assertEquals("Updated should modify one row for key  " + (key + 10), 1,
          result);
      key += 10;
    }

    searchKey = 0;
    for (int i = 0; i < numInserts; i++) {
      rs = s.executeQuery("Select * from broker_tickets where firmId="
          + (searchKey + 10) * 10);
      if (!rs.next()) {
        fail("ResultSet should have atleast one row for : " + (searchKey + 10)
            * 10);
      }
      assertEquals("Expected value not found ", (searchKey + 10), rs.getInt(1));
      assertEquals("Expected value not found ", (searchKey + 10), rs.getInt(2));
      assertEquals("Expected value not found ", (searchKey + 10) * 10,
          rs.getInt(3));

      searchKey += 10;
    }
    s.execute("drop table broker_tickets");
  }

  /**
   * Test for bug 39651, cascaded delete causing out of memory.
   * @throws SQLException
   */
  public void _testCascadedDeletes() throws SQLException {

    Connection conn = getConnection();
    

    Statement createStatement = conn.createStatement();
    
    createStatement.execute("create table trade.customers (cid int not null, "
            + "cust_name varchar(100), since date, addr varchar(100), tid int, " +
            "primary key (cid))");

    
    createStatement.execute("create table trade.networth (cid int not null, " +
    		"cash decimal (30, 20), securities decimal (30, 20), loanlimit int, " +
    		"availloan decimal (30, 20),  tid int, " +
    		"constraint netw_pk primary key (cid), " +
    		"constraint cust_newt_fk foreign key (cid) references trade.customers (cid) on delete cascade )");



    PreparedStatement psNetworth = conn
        .prepareStatement("insert into trade.networth values (?,?,?,?,?,?) ");
    PreparedStatement psCustomer = conn
        .prepareStatement("insert into trade.customers values (?,?,?,?,?)");

    System.out.println("Memory before table insertions : "
        + Runtime.getRuntime().freeMemory() + " and the max : "
        + Runtime.getRuntime().maxMemory());
    for (int i = 0; i < 10; i++) {
      psCustomer.setInt(1, i);
      psCustomer.setString(2, "customer_" + i);
      psCustomer.setDate(3, new Date(System.currentTimeMillis()));
      psCustomer.setString(4, "OREGON");
      psCustomer.setInt(5, 0);
      int k = psCustomer.executeUpdate();
      if (k != 1) {
        throw new RuntimeException("Data not inserted customer : " + i);
      }
    }

    for (int i = 0; i < 10; i++) {
      psNetworth.setInt(1, i);
      psNetworth.setBigDecimal(2, new BigDecimal(10000 + i));
      psNetworth.setBigDecimal(3, new BigDecimal(10000 + i));
      psNetworth.setInt(4, i);
      psNetworth.setBigDecimal(5, new BigDecimal(10000 + i));
      psNetworth.setInt(6, i);
      int k = psNetworth.executeUpdate();
      if (k != 1) {
        throw new RuntimeException("Data not inserted Networth : " + i);
      }

    }

    System.out.println("Memory after table insertions : "
        + Runtime.getRuntime().freeMemory() + " and the max : "
        + Runtime.getRuntime().maxMemory());

    PreparedStatement psCustDelete = conn
        .prepareStatement("delete from trade.customers where cid=?");

    psCustDelete.setInt(1, 1);
    psCustDelete.execute();
    System.out.println("Memory after table delete : "
        + Runtime.getRuntime().freeMemory() + " and the max : "
        + Runtime.getRuntime().maxMemory());

  }
  
  /**
   * Test index scans in a single vm DS with tables with redundancy.
   * @throws Exception
   */
  public void testSingleVmIndexScans () throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    PreparedStatement psInsertBrokerTickets = null;
    s.execute("Create table broker_tickets (id int not null, ticketPrice int not null ," +
        " firmId int not null ,  price double, quantity int, ticker varchar(20)," +
        " PRIMARY KEY (id)) redundancy 3");
    
    
    psInsertBrokerTickets = conn.prepareStatement("insert into broker_tickets " +
        "(id, ticketPrice, firmId,  price, quantity, ticker ) " +
        "values (?, ?, ?, ?, ?, ?)");
    
    int numInserts = 1000;
    int key = 0;
    double price = 1.0;
    int quantity = 10;
    for (int i = 0; i < numInserts; i++) {
      psInsertBrokerTickets.setInt(1, key + 10);
      if ((i % 2) == 0) {
        psInsertBrokerTickets.setInt(2, 10);
      } else {
        psInsertBrokerTickets.setInt(2, 20);
      }
      psInsertBrokerTickets.setInt(3, key + 10);
      psInsertBrokerTickets.setDouble(4, price);
      psInsertBrokerTickets.setInt(5, quantity);
      psInsertBrokerTickets.setString(6, "YYYY" + key);
      int rt = psInsertBrokerTickets.executeUpdate();
      assertEquals("Insert should return 1.", 1, rt) ; 
      key += 10;
      price += 0.01;

    }
    s.execute("create index index_ticketPrice on broker_tickets (ticketPrice)");
    ResultSet rs = s.executeQuery("select avg(distinct ticketPrice) from " +
    		"broker_tickets");
    while (rs.next()) {
      assertEquals("Result should match ", 15, rs.getInt(1));
    }
    s.execute("drop table broker_tickets");
  } 

}
